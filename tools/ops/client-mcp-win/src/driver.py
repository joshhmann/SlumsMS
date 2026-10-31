"""Client driver policy and stdio MCP tools; credentials never enter responses."""
import base64
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import time

from PIL import Image, ImageChops, ImageDraw, ImageStat
from qapipe import LABEL as QA_LABEL, QaPipe

ROOT = Path(__file__).resolve().parents[1]
TOOLS = {
    'status': {},
    'start_client': {},
    'login': {'channel': {'type': 'integer', 'minimum': 1, 'maximum': 5}},
    'command': {'line': {'type': 'string', 'minLength': 1, 'maxLength': 160}},
    'go_to': {'map_id': {'type': 'integer', 'minimum': 0, 'maximum': 999999999},
              'channel': {'type': 'integer', 'minimum': 1, 'maximum': 5}},
    'screenshot': {'label': {'type': 'string', 'maxLength': 48}},
    'read_logs': {'lines': {'type': 'integer', 'minimum': 1, 'maximum': 200}},
    'stop_client': {},
    # UI checks (owner's go, 2026-10-25): one whitelisted key tap, a click or a left-button drag, in the field only.
    'press_key': {'name': {'type': 'string', 'maxLength': 10}, 'hold_ms': {'type': 'integer', 'minimum': 0, 'maximum': 3000}},
    'mouse_click': {'x': {'type': 'integer', 'minimum': 0, 'maximum': 799},
                    'y': {'type': 'integer', 'minimum': 0, 'maximum': 599},
                    'button': {'type': 'string', 'maxLength': 6, 'enum': ['left', 'right', 'double']}},
    # QA client (ticket 166): only with a SLUMS_QA_MODULE slums.dll (its pipes); a player build refuses these.
    'qa_wait': {'signal': {'type': 'string', 'maxLength': 24,
                           'enum': ['worldselect.ready', 'channels.ready', 'charselect.ready', 'field.ready']},
                'timeout_s': {'type': 'integer', 'minimum': 1, 'maximum': 120}},
    'qa_burst': {'fps': {'type': 'integer', 'minimum': 1, 'maximum': 30},
                 'ms': {'type': 'integer', 'minimum': 100, 'maximum': 5000},
                 'label': {'type': 'string', 'maxLength': 40}},
    'run_script': {'name': {'type': 'string', 'maxLength': 48}},
    'mouse_drag': {'x1': {'type': 'integer', 'minimum': 0, 'maximum': 799},
                   'y1': {'type': 'integer', 'minimum': 0, 'maximum': 599},
                   'x2': {'type': 'integer', 'minimum': 0, 'maximum': 799},
                   'y2': {'type': 'integer', 'minimum': 0, 'maximum': 599},
                   'steps': {'type': 'integer', 'minimum': 1, 'maximum': 60}},
}
CHAT_SETTLE = 1.0   # 0.3 s still lost the "!" of the first command after login (2026-10-25)
REQUIRED = {'qa_wait': ('signal',), 'qa_burst': ('fps', 'ms', 'label'), 'run_script': ('name',),
            'command': ('line',), 'press_key': ('name',), 'mouse_click': ('x', 'y'),
            'mouse_drag': ('x1', 'y1', 'x2', 'y2')}

# Single taps only: no modifiers, so no Alt+F4, no Ctrl shortcuts. Value = (virtual key, extended).
KEYS = {**{chr(c): (c, False) for c in range(ord('A'), ord('Z') + 1)},
        **{chr(c): (c, False) for c in range(ord('0'), ord('9') + 1)},
        **{'F' + str(n): (0x6F + n, False) for n in range(1, 13)},
        'ESCAPE': (0x1B, False), 'ENTER': (0x0D, False), 'TAB': (0x09, False), 'SPACE': (0x20, False),
        'BACKSPACE': (0x08, False), 'UP': (0x26, True), 'DOWN': (0x28, True), 'LEFT': (0x25, True),
        'RIGHT': (0x27, True), 'INSERT': (0x2D, True), 'DELETE': (0x2E, True), 'HOME': (0x24, True),
        'END': (0x23, True), 'PAGEUP': (0x21, True), 'PAGEDOWN': (0x22, True),
        # Ctrl on its own is the game's default attack key (2026-10-29: hits for the short damage numbers). It is a
        # single key here like the others: nothing else is held with it, so it still makes no Ctrl shortcut.
        'CTRL': (0x11, False)}
# The only key combinations: each opens a SlumsMS window, nothing that closes or leaves the game.
COMBOS = {'CTRL+F10': (0x11, 0x79),   # the in-game settings window (slums.dll D3D9 host)
          'CTRL+F11': (0x11, 0x7A)}   # freeze / unfreeze the slums.dll HUD
LOGIN_SETTLE = 2.0   # the field keeps loading (check-in window, UI) after it first matches; "!unhide" lost its "!"


class Refusal(Exception):
    pass


def inside(path, base=ROOT):
    resolved = Path(path).resolve()
    if not resolved.is_relative_to(base.resolve()):
        raise Refusal('Path escapes the client-mcp folder.')
    # Refuse junctions and symlinks in every component, including the base itself.
    current = Path(path).absolute()
    while current != current.parent:
        if current.exists() and current.lstat().st_file_attributes & 0x400:
            raise Refusal('Reparse points are not allowed in client-mcp paths.')
        if current == base:
            break
        current = current.parent
    return resolved


def verify_config(path):
    """Require exactly one endpoint pair; never silently rewrite an unsafe target."""
    text = path.read_text(encoding='utf-8-sig')
    hosts, ports = [], []
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith((';', '#', '[')):
            continue
        match = re.fullmatch(r'([^=]+)=(.*)', line)
        if not match:
            continue
        key, value = (part.strip() for part in match.groups())
        if key.lower() in ('host', 'ip', 'server', 'address'):
            hosts.append(value)
        if key.lower() == 'port':
            ports.append(value)
    if hosts != ['192.168.0.164'] or ports != ['8489']:
        raise Refusal('Refusing client config: require exactly 192.168.0.164 port 8489.')


class Secrets:
    def __init__(self, path):
        self.path = path
        self.values = ()

    def load(self):
        try:
            lines = inside(self.path).read_text(encoding='utf-8-sig').splitlines()
        except OSError:
            raise Refusal('Create secrets/account.txt: account name on line 1, password on line 2, PIC on optional line 3.') from None
        lines = [x for x in lines if x] if len(lines) == 4 and not lines[3] else lines
        if len(lines) not in (2, 3) or not all(lines) or any(len(x) > 64 for x in lines):
            raise Refusal('account.txt must have two or three nonempty lines (account, password, optional PIC), each at most 64 characters.')
        self.values = tuple(lines)
        return self.values

    def redact(self, text):
        # Load inside this process only, also before login, so copied logs are safe.
        try:
            self.load()
        except Refusal:
            # Missing/malformed secrets: callers must not return client log contents.
            return '[redacted: secret file unavailable]'
        for value in sorted(self.values, key=len, reverse=True):
            text = text.replace(value, '[REDACTED]')
            text = text.replace(json.dumps(value)[1:-1], '[REDACTED]')
        text = re.sub(r'(?i)(password|passwd|pwd|token|secret|authorization)\s*[:=]\s*\S+',
                      r'\1=[REDACTED]', text)
        return text


def log_tail(path, lines):
    if not path.exists():
        return '(not present)'
    with path.open('rb') as stream:
        stream.seek(0, 2)
        stream.seek(max(0, stream.tell() - 128 * 1024))
        data = stream.read().decode('utf-8', errors='replace')
    return '\n'.join(data.splitlines()[-lines:])


SCRIPT_NAME = re.compile(r'^[a-z0-9][a-z0-9_-]{0,47}$')
SCRIPT_TOOLS = {'start_client', 'login', 'command', 'press_key', 'mouse_click', 'mouse_drag', 'screenshot',
                'qa_wait', 'qa_burst', 'wait_ms', 'stop_client', 'go_to'}


def utc(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec='milliseconds')


def utc_now():
    return utc(time.time())


def load_script(root, name):
    """qa-scripts/<name>.json: {"id", "expect", "steps": [{"tool", "args", "note"}]}. Refused unless every step is a
    known tool (run_script cannot nest) and wait_ms stays short; the agent writes these, the runner only plays them."""
    if not SCRIPT_NAME.match(name or ''):
        raise Refusal('A script name is a-z 0-9 _ - (1-48).')
    path = inside(root / 'qa-scripts' / (name + '.json'), root)
    if not path.is_file():
        raise Refusal('No qa-scripts/' + name + '.json.')
    raw = path.read_bytes()
    try:
        script = json.loads(raw.decode('utf-8-sig'))
    except ValueError:
        raise Refusal('qa-scripts/' + name + '.json is not valid JSON.') from None
    steps = script.get('steps') if isinstance(script, dict) else None
    if not isinstance(steps, list) or not 1 <= len(steps) <= 200:
        raise Refusal('A script needs 1-200 steps.')
    for index, step in enumerate(steps):
        if not isinstance(step, dict) or step.get('tool') not in SCRIPT_TOOLS or not isinstance(step.get('args', {}), dict):
            raise Refusal('Step %d: unknown tool (allowed: %s).' % (index, ', '.join(sorted(SCRIPT_TOOLS))))
        if step['tool'] == 'wait_ms':
            ms = step.get('args', {}).get('ms')
            if type(ms) is not int or not 0 < ms <= 10000:
                raise Refusal('Step %d: wait_ms needs ms 1-10000 (prefer qa_wait on a signal).' % index)
    return raw, steps


class Driver:
    def __init__(self, root=ROOT, backend=None):
        self.root = root
        if backend is None:
            import windows as backend
        self.backend = backend
        self.activity = backend.InputGuard(inside(root / 'logs', root) / 'owner-input.json')
        self.client = backend.OwnedClient(inside(root / 'client', root))
        self.sender = backend.Sender(self.client, self.guard)
        self.secrets = Secrets(root / 'secrets' / 'account.txt')
        self.state = 'stopped'
        # Seconds without owner input before any tool but status may act (worker-config.json idle_minutes).
        self.idle_required = 600
        self.sensitive_screen = True
        self.field_dy = 0   # vertical offset of a moved status bar, in captured (native) pixels
        self.native = None
        self.last_shot = None
        self.ui = json.loads(inside(root / 'ui.json', root).read_text(encoding='utf-8'))
        from focus import Handoff
        self.focus = Handoff(root)
        self.qa = None   # QaPipe to a SLUMS_QA_MODULE client, set by start_client when the client has one
        self.qa_last = None

    def local_config(self):
        path = inside(self.root / 'local-config.json', self.root)
        value = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
        keys = ('force', 'allow_owned_focus', 'focus_handoff', 'run_now')
        if set(value) - set(keys) or any(not isinstance(value.get(key, False), bool) for key in keys):
            raise Refusal('local-config.json supports boolean force, allow_owned_focus, focus_handoff, and run_now '
                          'values only.')
        return value

    def forced(self):
        """The idle wait is off: Start-QA's Y for this run, or the controller's Run now (until the client stops)."""
        config = self.local_config()
        return config.get('force', False) or config.get('run_now', False)

    def clear_run_now(self):
        path = inside(self.root / 'local-config.json', self.root)
        value = self.local_config()
        value['run_now'] = False
        temporary = Path(str(path) + '.tmp')
        temporary.write_text(json.dumps(value), encoding='utf-8')
        os.replace(temporary, path)

    def guard(self):
        # Check this again before each keystroke/click and during every wait.
        self.reload_ui()
        idle = self.activity.idle_seconds()
        if idle < getattr(self, 'idle_required', 600) and not self.forced():
            raise Refusal('owner is using the PC')
        if not self.backend.interactive_desktop():
            raise Refusal('Run in the logged-on owner\'s interactive desktop; desktop may be locked.')
        self.client.focus_authorized = self.local_config().get('allow_owned_focus', False)
        self.client.focus_handoff = self.foreground_permission if self.local_config().get('focus_handoff', False) else None
        # Only the owner's local force (Start-QA's Y, or the controller's Run now) lets the driver raise its own game
        # window itself.
        self.client.force_focus = bool(self.forced())

    def foreground_permission(self):
        from focus import FocusError
        try:
            self.focus.request()
        except FocusError as error:
            raise Refusal(str(error)) from None

    def endpoint(self):
        folder = inside(self.root / 'client', self.root)
        for name in ('SlumsMS.exe', 'MapleStory.exe', 'slums.dll', 'config.ini'):
            inside(folder / name, self.root)
        verify_config(folder / 'config.ini')

    def display_guard(self):
        display = self.backend.primary_display()
        if display['virtual']:
            raise Refusal('Primary display is Parsec/virtual. Make the real monitor primary before starting the client.')
        return display

    def image(self):
        self.guard()
        hwnd = self.client.foreground()
        native = self.backend.capture(hwnd)
        self.native = native   # kept for matching a moved status bar at its exact pixel offset
        return native.resize((800, 600), Image.Resampling.LANCZOS)

    def ui_state(self):
        """The client's own QA state ([debug] uistate=1, SlumsMS 1.6.0): stage, chat focus and UI rects. Trusted only
        when fresh (under 2 s) and written by a client process this driver owns; otherwise None."""
        path = inside(self.root / 'client' / 'slums-ui-state.json', self.root)
        try:
            if time.time() - path.stat().st_mtime > 2.0:
                return None
            state = json.loads(path.read_text(encoding='ascii'))
        except (OSError, ValueError):
            return None
        if not isinstance(state, dict) or state.get('pid') not in self.client.pids():
            return None
        return state

    def matches(self, name, image=None):
        image = self.image() if image is None else image
        check = self.ui['screens'][name]
        if not check.get('templates'):
            raise Refusal('Screen templates for ' + name + ' have not been calibrated.')
        refs = []
        for template in check['templates']:
            file = inside(self.root / template['file'], self.root)
            with Image.open(file) as reference:
                refs.append((template, reference.convert('RGB')))
        if name in ('field', 'chat_open') and not self.matches_at(refs, image, 0):
            # A moved, resized or reskinned status bar: ask the client instead of the pixels when it reports.
            state = self.ui_state()
            if state is not None:
                return state.get('stage') == 'field' if name == 'field' else state.get('chat_open') is True
        if name == 'field':
            # SlumsMS 1.6.0 lets players move the Shop/Trade/Menu buttons (field-1) away from the status bar; the
            # bar's icon row (field-0) is the required marker, the button only confirms when it is still there.
            required = refs[:1]
            if self.matches_at(refs, image, 0) or self.matches_at(required, image, 0):
                return True
            # A moved bar: search upwards in the full-resolution capture, cropping at the exact pixel offset and
            # scaling like the templates, so a fractional offset in the 800x600 image does not blur the match.
            if self.field_dy and self.matches_native(required, self.field_dy):
                return True
            native = self.native
            if native is None:
                return False
            scale = native.size[1] / 600.0
            top = int(required[0][0]['box'][1] * scale)
            for dy in range(-1, -top - 1, -1):
                if self.matches_native(required, dy):
                    self.field_dy = dy
                    return True
            # Fallback on the 800x600 image with 40% more tolerance for the fractional-offset blur (measured: 27.3
            # against 20 at the right offset, 40+ one pixel off).
            for dy in range(-1, -required[0][0]['box'][1] - 1, -1):
                if self.matches_at(required, image, dy, 1.4):
                    self.field_dy = round(dy * scale)
                    return True
            return False
        if self.matches_at(refs, image, 0):
            return True
        if name == 'chat_open':
            # The chat input is part of the status bar: it moves with it (offset found by the field check).
            return bool(self.field_dy) and self.matches_native(refs, self.field_dy)
        return False

    def matches_native(self, refs, dy_native):
        native = self.native
        if native is None:
            return False
        sx, sy = native.size[0] / 800.0, native.size[1] / 600.0
        for template, reference in refs:
            left, top, right, bottom = template['box']
            box = (round(left * sx), round(top * sy) + dy_native, round(right * sx), round(bottom * sy) + dy_native)
            if box[1] < 0:
                return False
            actual = native.crop(box).resize((right - left, bottom - top), Image.Resampling.LANCZOS).convert('RGB')
            diff = ImageChops.difference(actual, reference)
            error = sum(ImageStat.Stat(diff).mean) / 3
            if error > template.get('tolerance', 12):
                return False
        return True

    @staticmethod
    def matches_at(refs, image, dy, slack=1.0):
        for template, reference in refs:
            left, top, right, bottom = template['box']
            actual = image.crop((left, top + dy, right, bottom + dy)).convert('RGB')
            diff = ImageChops.difference(actual, reference)
            error = sum(ImageStat.Stat(diff).mean) / 3
            if error > template.get('tolerance', 12) * slack:
                return False
        return True

    def wait(self, name, timeout=35):
        until = time.monotonic() + timeout
        consecutive = 0
        while time.monotonic() < until:
            self.guard()
            if not self.client.pids():
                raise Refusal('The owned client exited while waiting for ' + name + '.')
            try:
                passed = self.matches(name)
            except self.backend.Blocked as exc:
                # Window creation is expected to be asynchronous; never focus it.
                if 'found 0' in str(exc):
                    time.sleep(0.25)
                    continue
                raise
            consecutive = consecutive + 1 if passed else 0
            if consecutive >= 3:
                self.state = name
                if name == 'field':
                    self.sensitive_screen = False
                return
            time.sleep(0.25)
        shot = self.save_shot('timeout-' + name, include_image=False)
        raise Refusal('Timed out waiting for ' + name + '; screenshot: ' + shot['path'])

    def click(self, name):
        self.sender.click(*self.ui['points'][name])

    def wait_choice(self, names, timeout=35):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            image = self.image()
            for name in names:
                if self.matches(name, image):
                    self.state = name
                    return name
            time.sleep(0.2)
        shot = self.save_shot('timeout-login-transition', include_image=False)
        raise Refusal('Login transition timed out; screenshot: ' + shot['path'])

    def save_shot(self, label, include_image=True):
        image = self.image()
        protect = self.sensitive_screen
        screens = self.ui.get('screens', {})
        if protect:
            # Verified world/channel/character screens contain no credential fields.
            for name in ('world', 'channel', 'character', 'field', 'creation'):
                if screens.get(name, {}).get('templates') and self.matches(name, image):
                    protect = False
                    break
        elif screens.get('login', {}).get('templates') and self.matches('login', image):
            protect = True
        if protect:
            draw = ImageDraw.Draw(image)
            for rect in self.ui['secret_masks']:
                draw.rectangle(rect, fill='black')
        label = re.sub(r'[^a-zA-Z0-9_-]', '-', label)[:48] or 'client'
        stamp = datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S.%fZ')
        folder = inside(self.root / 'shots', self.root)
        folder.mkdir(exist_ok=True)
        path = inside(folder / (stamp + '-' + label + '.png'), self.root)
        # RGB copy has no text chunks, source metadata, or credentials in its name.
        image.copy().save(path, format='PNG')
        self.last_shot = str(path)
        result = {'path': str(path), 'width': image.width, 'height': image.height}
        if include_image:
            image.thumbnail((640, 480))
            buffer = io.BytesIO()
            image.save(buffer, format='PNG')
            result['_image'] = base64.b64encode(buffer.getvalue()).decode('ascii')
        return result

    def read_logs(self, lines=40):
        return {name: self.secrets.redact(log_tail(
            inside(self.root / 'client' / name, self.root), lines))
            for name in ('integration.log', 'crash.log')}

    def status(self):
        idle = self.activity.idle_seconds()
        display = self.backend.primary_display()
        pids = self.client.pids()
        dll = inside(self.root / 'client' / 'slums.dll', self.root)
        stamp = {'sha256': hashlib.sha256(dll.read_bytes()).hexdigest(),
                 'modified_utc': datetime.fromtimestamp(dll.stat().st_mtime, timezone.utc).isoformat()}
        required = getattr(self, 'idle_required', 600)
        return {'owner_idle_seconds': round(idle, 1), 'owner_idle': idle >= required,
                'idle_required_seconds': required,
                'local_force': self.local_config().get('force', False),
                'run_now': self.local_config().get('run_now', False),
                'interactive_desktop': self.backend.interactive_desktop(),
                'primary_display': display, 'client_running': bool(pids),
                'pids': pids, 'launcher_pid': self.client.launcher_pid,
                'state': self.state, 'build': stamp, 'window': self.window_info(pids),
                'last_logs': self.read_logs(8)}

    def window_info(self, pids):
        if not pids:
            return None
        try:
            return self.client.window_info()
        except Exception as error:
            return {'error': str(error) if isinstance(error, Refusal) else type(error).__name__}

    def start_client(self):
        self.endpoint()
        self.display_guard()
        if self.client.pids():
            raise Refusal('An owned client is already running. Stop it before starting another.')
        # Fail before launching if a required screen check is missing.
        for screen in self.ui['screens']:
            for item in self.ui['screens'][screen]['templates']:
                if not inside(self.root / item['file'], self.root).is_file():
                    raise Refusal('Missing screen template: ' + screen)
            if not self.ui['screens'][screen]['templates']:
                raise Refusal('Missing screen template: ' + screen)
        self.sensitive_screen = True
        self.client.start()
        self.wait('login', 60)
        self.qa = QaPipe.connect(self.client.pids(), timeout=5) if self.qa_build() else None
        return {'state': self.state, 'pids': self.client.pids(), 'qa_pipe': bool(self.qa)}

    def qa_build(self):
        build = inside(self.root / 'client' / 'slums-build.txt', self.root)
        return build.exists() and any(line.strip().startswith('release=') and line.strip().endswith('-qa')
                                      for line in build.read_text(encoding='utf-8', errors='replace').splitlines())

    def need_qa(self):
        if not self.qa or self.qa.closed:
            raise Refusal('No QA pipe: the client is not a running SLUMS_QA_MODULE build (start_client first).')
        return self.qa

    def qa_wait(self, signal, timeout_s=30):
        event = self.need_qa().wait(signal, timeout_s)
        if event is None:
            raise Refusal('Timed out after %d s waiting for the client signal %s.' % (timeout_s, signal))
        return {'signal': signal, 'client_ms': event.get('t')}

    def qa_burst(self, fps, ms, label):
        pipe = self.need_qa()
        if not QA_LABEL.match(label):
            raise Refusal('A burst label is 1-40 of A-Z a-z 0-9 - _.')
        pipe.burst(fps, ms, label)
        done = pipe.wait('burst.done', ms / 1000.0 + 10)
        if done is None:
            raise Refusal('The burst %s did not finish.' % label)
        return {'label': label, 'frames': done.get('frames'),
                'folder': str(self.root / 'client' / 'qa-captures' / label)}

    def reload_ui(self):
        # Re-read the calibration each run, so a recalibrated ui.json needs no worker restart.
        root = getattr(self, 'root', None)
        if root is not None:
            self.ui = json.loads(inside(root / 'ui.json', root).read_text(encoding='utf-8'))

    def login(self, channel=1):
        self.reload_ui()
        self.endpoint()
        self.prepare_login()
        account, password, *rest = self.secrets.load()
        pic = rest[0] if rest else None
        self.sensitive_screen = True
        self.sender.text(account)
        self.sender.key(9)
        self.sender.text(password)
        self.sender.key(13)
        screen = self.wait_choice(('terms', 'gender', 'world'))
        if screen == 'terms':
            self.accept_terms()
            screen = self.wait_choice(('gender', 'world'))
        if screen == 'gender':
            self.click('gender_confirm')
        self.wait('world')
        if self.qa:
            # QA build: click Bera only once the world list is handled and drawn, and click again when the client
            # shows no sign of the click (no channels.ready): the old fixed timing left an empty channel panel.
            if self.qa.wait('worldselect.ready', 30) is None:
                raise Refusal('The QA client never reported the world list (worldselect.ready).')
            for attempt in range(3):
                self.qa.drain()
                self.click('bera')
                if self.qa.wait('channels.ready', 4) is not None:
                    break
            else:
                shot = self.save_shot('no-channels-ready', include_image=False)
                raise Refusal('Three clicks on Bera drew no channel panel (no channels.ready); screenshot: ' + shot['path'])
        else:
            self.click('bera')
        self.wait('channel')
        self.click('channel_' + str(channel))
        self.click('channel_confirm')
        if self.wait_choice(('empty_character', 'character')) == 'empty_character':
            shot = self.save_shot('test-account-has-no-character', include_image=False)
            raise Refusal('The dedicated TEST account has no characters. Prepare a GM QA character on Bera first; screenshot: ' + shot['path'])
        self.click('first_character')
        self.click('character_confirm')
        if pic:
            self.enter_pic(pic)
        self.wait('field', 60)
        if 'basic_configuration' in self.ui['screens'] and self.matches('basic_configuration'):
            self.click('basic_configuration_close')
            deadline = time.monotonic() + 5
            while self.matches('basic_configuration'):
                if time.monotonic() >= deadline:
                    raise Refusal('Initial configuration dialog did not close.')
                time.sleep(0.1)
        time.sleep(LOGIN_SETTLE)
        self.command('!unhide')
        return {'state': 'field', 'world': 'Bera', 'channel': channel,
                'unhide_sent': True}

    def enter_pic(self, pic, settle=2.0, timeout=10):
        # TEST asks for the PIC over character select (secret_masks hides that box in screenshots). Type it once,
        # only while character select is still showing; never if the field is already loading.
        start = time.monotonic()
        while time.monotonic() - start < timeout:
            self.guard()
            if self.matches('field'):
                return False
            if time.monotonic() - start >= settle and self.matches('character'):
                self.sensitive_screen = True
                self.sender.text(pic)
                self.sender.key(13)
                return True
            time.sleep(0.25)
        return False

    def prepare_login(self):
        if not self.matches('login'):
            raise Refusal('Login screen is not visible.')
        self.click('account')
        if not self.matches('save_login_unchecked'):
            self.click('save_login_id')
            self.click('account')  # Keep the game cursor away from the checkbox check.
            self.wait('save_login_unchecked', 5)
        if not self.matches('login'):
            raise Refusal('Login screen changed before credential entry.')
        # Clear only the dedicated client login field, never the clipboard.
        self.sender.key(0x11, True)
        try:
            self.sender.key(0x41)
        finally:
            self.sender.key(0x11, False)
        self.sender.key(8)

    def accept_terms(self):
        # Optional first-login screen; activate OK only after its enabled check.
        for _ in range(20):
            if self.matches('terms_accept_ready'):
                self.click('terms_accept')
                return
            self.click('terms_scroll_page')
            self.click('terms_scroll_end')
        shot = self.save_shot('terms-scroll-timeout', include_image=False)
        raise Refusal('Terms acceptance did not become available; screenshot: ' + shot['path'])

    def command(self, line):
        self.endpoint()
        if not line or len(line) > 160 or any(ord(ch) < 32 for ch in line):
            raise Refusal('Chat line must be 1–160 printable characters, with no newlines.')
        if not self.matches('field'):
            raise Refusal('Field screen is not visible.')
        # Never send a caller-supplied credential into chat.
        self.secrets.load()
        if any(value in line for value in self.secrets.values):
            raise Refusal('Chat line contains protected account data.')
        if not self.matches('chat_open'):
            self.sender.key(13)
            self.wait('chat_open', 5)
            # The entry box takes input a moment after it shows; typing at once dropped the Shift+1 of
            # "!unhide" (2026-10-25) and the rest went out as plain chat.
            time.sleep(CHAT_SETTLE)
            # The first key after login is still lost even after the settle (2026-10-26: "unhide" went out as
            # chat, the "!" gone). A Backspace first absorbs it; in an empty entry box it does nothing.
            self.sender.key(8)
            time.sleep(0.1)
        if self.qa:
            self.qa.drain()   # a qa_wait after this command sees only what the command causes
        self.sender.text(line)
        self.sender.key(13)
        self.state = 'field'
        return {'sent': True}

    def go_to(self, map_id=None, channel=None):
        if (map_id is None) == (channel is None):
            raise Refusal('Provide exactly one of map_id or channel.')
        if map_id is not None:
            self.command('!warp ' + str(map_id))
        else:
            self.command('!changechannel ' + str(channel))
        # Observe a loading transition before accepting a stable field as success.
        until = time.monotonic() + 12
        while time.monotonic() < until:
            if not self.matches('field'):
                break
            time.sleep(0.1)
        else:
            shot = self.save_shot('no-map-transition', include_image=False)
            raise Refusal('No map loading transition observed; screenshot: ' + shot['path'])
        self.wait('field', 45)
        return {'state': 'field', 'map_id': map_id, 'channel': channel}

    def field_input(self):
        # Same gate as chat: the verified field, never the login or PIC screens.
        self.endpoint()
        if not self.matches('field'):
            raise Refusal('Field screen is not visible (a window over the status bar also hides it).')
        self.state = 'field'

    def press_key(self, name, hold_ms=0):
        name = name.upper()
        combo = COMBOS.get(name)
        key = KEYS.get(name)
        if key is None and combo is None:
            raise Refusal('Unsupported key. Use A-Z, 0-9, F1-F12, Escape, Enter, Tab, Space, Backspace, arrows, '
                          'Insert, Delete, Home, End, PageUp, PageDown, Ctrl (attack), Ctrl+F10 or Ctrl+F11.')
        if name == 'ESCAPE' and self.state == 'field':
            # Escape only closes things; a menu it opened hides the status bar the field check reads, and every
            # tool would then refuse until a restart. Allowed while the last verified screen was the field.
            self.endpoint()
            self.sender.key(0x1B)
            return {'pressed': name}
        self.field_input()
        if combo is not None:
            self.sender.key(combo[0], True)
            try:
                self.sender.key(combo[1])
            finally:
                self.sender.key(combo[0], False)
            return {'pressed': name}
        if name not in ('ESCAPE', 'ENTER') and self.matches('chat_open'):
            # A game key must not be typed into an open chat box (it happened after login's !unhide).
            self.sender.key(0x1B)
            deadline = time.monotonic() + 3
            while self.matches('chat_open'):
                if time.monotonic() >= deadline:
                    raise Refusal('Chat entry is open and did not close; key not sent.')
                time.sleep(0.1)
        if hold_ms and name in ('LEFT', 'RIGHT', 'UP', 'DOWN'):
            # Walking: hold an arrow up to 3 s. The guard and endpoint are checked before; the release always runs.
            hold_ms = max(0, min(int(hold_ms), 3000))
            self.sender.key(key[0], True, extended=key[1])
            try:
                end = time.monotonic() + hold_ms / 1000
                while time.monotonic() < end:
                    self.guard()
                    time.sleep(0.05)
            finally:
                self.sender.key(key[0], False, extended=key[1])
            return {'pressed': name, 'held_ms': hold_ms}
        self.sender.key(key[0], extended=key[1])
        return {'pressed': name}

    def mouse_click(self, x, y, button='left'):
        self.field_input()
        self.sender.click(x, y, right=button == 'right', double=button == 'double')
        return {'clicked': [x, y], 'button': button}

    def mouse_drag(self, x1, y1, x2, y2, steps=12):
        self.field_input()
        self.sender.drag(x1, y1, x2, y2, steps)
        return {'dragged': [[x1, y1], [x2, y2]], 'steps': steps}

    def stop_client(self):
        # Stop the client BEFORE closing the QA pipe: closing a pipe handle while the reader thread is blocked in a
        # synchronous ReadFile on it waits for that read, which hung the worker (2026-10-31 02:39 UTC). The client's
        # exit breaks the pipe, the read returns, and close() is then safe.
        stopped = self.client.stop()
        if self.qa:
            self.qa.close()
            self.qa_last, self.qa = self.qa, None   # run_script still saves this pipe's events
        self.state = 'stopped'
        self.sensitive_screen = True
        return {'stopped_pids': stopped}

    def run_script(self, name):
        """Plays qa-scripts/<name>.json and records runs/<name>-<UTC>/ (ticket 166). See NOTES-QA.md."""
        script, steps = load_script(self.root, name)
        started = time.time()
        stamp = datetime.fromtimestamp(started, timezone.utc).strftime('%Y%m%dT%H%M%SZ')
        run = inside(self.root / 'runs' / (name + '-' + stamp), self.root)
        run.mkdir(parents=True)
        results, labels, failed = [], [], None
        for index, step in enumerate(steps):
            tool, args = step['tool'], dict(step.get('args', {}))
            entry = {'step': index, 'tool': tool, 'note': step.get('note', ''), 'start_utc': utc_now()}
            try:
                if tool == 'wait_ms':
                    time.sleep(args['ms'] / 1000.0)
                    result = {'slept_ms': args['ms']}
                else:
                    self.validate(tool, args)
                    if tool == 'screenshot':
                        result = self.save_shot(self.secrets.redact(args.get('label', 'step%d' % index)), include_image=False)
                    else:
                        result = getattr(self, tool)(**args)
                if tool == 'qa_burst':
                    labels.append(args['label'])
                if isinstance(result, dict):
                    result.pop('_image', None)
                entry.update(ok=True, result=result)
            except Exception as error:
                message = str(error) if isinstance(error, (Refusal, self.backend.Blocked)) else type(error).__name__
                entry.update(ok=False, error=self.secrets.redact(message))
                failed = index
            entry['end_utc'] = utc_now()
            results.append(entry)
            if failed is not None:
                break
        # Collect what the run made: bursts, step screenshots, client events, the client logs' tails.
        for label in labels:
            source = inside(self.root / 'client' / 'qa-captures' / label, self.root)
            if source.is_dir():
                source.rename(run / ('burst-' + label))
        for entry in results:
            path = (entry.get('result') or {}).get('path')
            if path and Path(path).is_file() and Path(path).parent == self.root / 'shots':
                Path(path).replace(run / Path(path).name)
                entry['result']['path'] = Path(path).name
        pipe = self.qa or self.qa_last
        if pipe:
            with (run / 'events.jsonl').open('w', encoding='utf-8') as out:
                for event in pipe.events_since(started):
                    out.write(json.dumps(event) + '\n')
        logs = self.read_logs(200)
        for log_name, text in logs.items():
            (run / log_name).write_text(text or '', encoding='utf-8')
        build = inside(self.root / 'client' / 'slums-build.txt', self.root)
        manifest = {'script': name, 'script_sha256': hashlib.sha256(script).hexdigest(),
                    'expect': json.loads(script).get('expect', ''),
                    'client_build': build.read_text(encoding='utf-8').strip().splitlines() if build.exists() else [],
                    'qa_pipe': bool(pipe), 'start_utc': utc(started), 'end_utc': utc_now(),
                    'result': 'ERROR' if failed is not None else 'RECORDED', 'failed_step': failed, 'steps': results}
        (run / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
        return {'run': str(run), 'result': manifest['result'], 'failed_step': failed,
                'steps': len(results), 'bursts': labels}

    def validate(self, name, args):
        if not isinstance(name, str) or name not in TOOLS or not isinstance(args, dict) or set(args) - set(TOOLS[name]):
            raise Refusal('Unknown tool or unsupported tool arguments.')
        missing = [key for key in REQUIRED.get(name, ()) if key not in args]
        if missing:
            raise Refusal(name + ' requires ' + ', '.join(missing) + '.')
        for key, value in args.items():
            rule = TOOLS[name][key]
            if rule['type'] == 'integer':
                if type(value) is not int or not rule['minimum'] <= value <= rule['maximum']:
                    raise Refusal('Invalid integer argument: ' + key)
            elif not isinstance(value, str) or len(value) > rule['maxLength'] or (
                    'enum' in rule and value not in rule['enum']):
                raise Refusal('Invalid text argument: ' + key)

    def audit(self, name, ok):
        folder = inside(self.root / 'logs', self.root)
        folder.mkdir(exist_ok=True)
        now = datetime.now(timezone.utc)
        path = inside(folder / (now.strftime('%Y-%m-%d') + '.log'), self.root)
        # Never log arguments, raw exceptions, credentials, or screenshots.
        with path.open('a', encoding='utf-8') as stream:
            outcome = 'begin' if ok is None else ('ok' if ok else 'error')
            stream.write(now.isoformat() + ' ' + name + ' ' + outcome + '\n')

    def call(self, name, args):
        safe_name = name if isinstance(name, str) and name in TOOLS else 'unknown'
        try:
            # Activity gate precedes even argument validation for every non-status tool.
            if name != 'status':
                self.guard()
            self.validate(name, args)
            # Check audit storage before allowing any operation to change game state.
            self.audit(safe_name, None)
            if name == 'screenshot':
                result = self.save_shot(self.secrets.redact(args.get('label', 'client')))
            else:
                result = getattr(self, name)(**args)
            encoded_image = result.pop('_image', None) if isinstance(result, dict) else None
            text = self.secrets.redact(json.dumps(result, ensure_ascii=False))
            content = [{'type': 'text', 'text': text}]
            if encoded_image:
                content.append({'type': 'image', 'data': encoded_image, 'mimeType': 'image/png'})
            self.audit(safe_name, True)
            return {'content': content, 'isError': False}
        except Exception as error:
            # Only our deliberately safe policy messages may leave the process.
            message = str(error) if isinstance(error, (Refusal, self.backend.Blocked)) else (
                'Client driver operation failed (' + type(error).__name__ + '). See NOTES.md for troubleshooting.')
            message = self.secrets.redact(message)
            try:
                self.audit(safe_name, False)
            except Exception:
                message = 'Client driver cannot write its local audit log; operation refused.'
            return {'content': [{'type': 'text', 'text': message}], 'isError': True}
