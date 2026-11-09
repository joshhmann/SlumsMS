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
import testhost

ROOT = Path(__file__).resolve().parents[1]
TOOLS = {
    'status': {},
    'start_client': {'instance': {'type': 'string', 'maxLength': 1, 'enum': ['a', 'b']}},
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
    # The steps of qa-scripts/*.yaml, one tool call each (ticket 170): the 187 orchestrator drives the login one step
    # at a time and reads the pipe's event log by position after each one.
    'login_credentials': {'vault': {'type': 'string', 'maxLength': 32}},
    'click_anchor': {'name': {'type': 'string', 'maxLength': 32}},
    'select_world': {'world': {'type': 'string', 'maxLength': 8, 'enum': ['Bera']}},
    'select_channel': {'channel': {'type': 'integer', 'minimum': 1, 'maximum': 5}},
    'select_character': {'slot': {'type': 'integer', 'minimum': 0, 'maximum': 14}},
    'qa_events': {'since': {'type': 'integer', 'minimum': 0, 'maximum': 1000000}},
    # P1-Q2: click an npc like a player. The QA client says where the npc is on screen (npc_pos); the worker clicks.
    'click_npc': {'npc': {'type': 'integer', 'minimum': 1, 'maximum': 99999999}},
    # qa-position-signal (1.6.6): where the character is (the server's !pos coordinates), for gm-0 scripts.
    'position': {},
    'click_option': {'option': {'type': 'integer', 'minimum': 1, 'maximum': 8}},
    # qa-dialog-row (1.6.7): row <row> of the open npc dialog from its own row list, also the client's local quest-npc
    # menu, which no NPC_TALK brings. rows: the count the script expects (0 = any); another count is refused.
    'click_dialog_row': {'row': {'type': 'integer', 'minimum': 1, 'maximum': 16},
                         'rows': {'type': 'integer', 'minimum': 0, 'maximum': 16}},
    # P1-Q2 (Zakum run): the QA client calls the item and party windows' own senders; no mouse, no window anchors.
    'drop_item': {'item': {'type': 'integer', 'minimum': 1000000, 'maximum': 5999999},
                  'count': {'type': 'integer', 'minimum': 1, 'maximum': 32767}},
    'party_create': {},
    'close_dialog': {},
    'dialog_next': {},
    'dialog_decline': {},   # qa-quest-dialog (1.6.7): No / Decline by the open dialog's own type (QA build 23)
    # P0-2 GP8: open a hired merchant selling <quantity> of <item> at <price>, the way a player sets one up.
    'merchant_open': {'item': {'type': 'integer', 'minimum': 1000000, 'maximum': 4999999},
                      'quantity': {'type': 'integer', 'minimum': 1, 'maximum': 2000},
                      'price': {'type': 'integer', 'minimum': 1, 'maximum': 2147483647},
                      'title': {'type': 'string', 'maxLength': 30}},
    'merchant_close': {},
    'fredrick_retrieve': {},
    # b344: picks up the newest drop of <item> on the field with the client's own pickup sender (QA build 15).
    'pickup_item': {'item': {'type': 'integer', 'minimum': 1000000, 'maximum': 5999999},
                    'count': {'type': 'integer', 'minimum': 1, 'maximum': 10}},
    # 1.6.5 GP4: the open NPC shop's own buy / sell / close packets (QA build 16). A sell without a quantity (0) sells
    # every stack of the item.
    'shop_buy': {'item': {'type': 'integer', 'minimum': 1000000, 'maximum': 5999999},
                 'quantity': {'type': 'integer', 'minimum': 1, 'maximum': 32767}},
    'shop_sell': {'item': {'type': 'integer', 'minimum': 1000000, 'maximum': 4999999},
                  'quantity': {'type': 'integer', 'minimum': 0, 'maximum': 32767}},
    'shop_close': {},
    # 1.6.5 GP4 leg B: uses the item's first USE stack (a potion), as a double-click does (QA build 17).
    'use_item': {'item': {'type': 'integer', 'minimum': 2000000, 'maximum': 2999999}},
    # 1.6.5 GP6: a trade with a player on the field by the trade window's own packets (QA build 17). trade_add_item
    # without a quantity (0) puts the whole stack; trade_confirm waits up to timeout_s for the partner and the result.
    'trade_invite': {'name': {'type': 'string', 'maxLength': 12}},
    'trade_accept': {},
    'trade_add_item': {'item': {'type': 'integer', 'minimum': 1000000, 'maximum': 4999999},
                       'quantity': {'type': 'integer', 'minimum': 0, 'maximum': 32767}},
    'trade_add_meso': {'meso': {'type': 'integer', 'minimum': 1, 'maximum': 2147483647}},
    'trade_confirm': {'timeout_s': {'type': 'integer', 'minimum': 1, 'maximum': 300}},
    'trade_cancel': {},
    # b360 (1.6.6): a jump with the game's jump key (Alt, keymap 56): optionally running left/right for run_ms first
    # and holding Up through it (catch 1) to grab a rope or ladder; everything is released after hold_ms.
    # qa-walk-to (1.6.7): walk on the current floor / climb a ladder or rope until the QA client's position says so.
    'walk_to': {'x': {'type': 'integer', 'minimum': -5000, 'maximum': 5000},
                'tolerance': {'type': 'integer', 'minimum': 2, 'maximum': 50},
                'timeout_ms': {'type': 'integer', 'minimum': 500, 'maximum': 30000}},
    'climb': {'y': {'type': 'integer', 'minimum': -5000, 'maximum': 5000},
              'tolerance': {'type': 'integer', 'minimum': 2, 'maximum': 50},
              'timeout_ms': {'type': 'integer', 'minimum': 500, 'maximum': 30000}},
    'jump': {'dir': {'type': 'string', 'maxLength': 5, 'enum': ['none', 'left', 'right']},
             'catch': {'type': 'integer', 'minimum': 0, 'maximum': 1},
             'run_ms': {'type': 'integer', 'minimum': 0, 'maximum': 2000},
             'hold_ms': {'type': 'integer', 'minimum': 100, 'maximum': 2000}},
    # P0-6: the Incubator on a target (the egg's item id, or inv:slot for an empty slot); only 5060002 is taken.
    'use_cash_item': {'item': {'type': 'integer', 'minimum': 5000000, 'maximum': 5999999},
                      'target': {'type': 'string', 'maxLength': 12}},
    'party_invite': {'name': {'type': 'string', 'maxLength': 12}},
    'mouse_drag': {'x1': {'type': 'integer', 'minimum': 0, 'maximum': 799},
                   'y1': {'type': 'integer', 'minimum': 0, 'maximum': 599},
                   'x2': {'type': 'integer', 'minimum': 0, 'maximum': 799},
                   'y2': {'type': 'integer', 'minimum': 0, 'maximum': 599},
                   'steps': {'type': 'integer', 'minimum': 1, 'maximum': 60}},
}
CHAT_SETTLE = 1.0   # 0.3 s still lost the "!" of the first command after login (2026-10-25)
COMMAND_ECHO_WINDOW = 1.0   # a chat line's echo is one server round trip; 1 s leaves room on a busy map
REQUIRED = {'qa_wait': ('signal',), 'qa_burst': ('fps', 'ms', 'label'), 'run_script': ('name',),
            'command': ('line',), 'press_key': ('name',), 'mouse_click': ('x', 'y'),
            'login_credentials': ('vault',), 'click_anchor': ('name',), 'select_world': ('world',),
            'select_channel': ('channel',), 'select_character': ('slot',),
            'mouse_drag': ('x1', 'y1', 'x2', 'y2'), 'click_npc': ('npc',), 'click_option': ('option',),
            'click_dialog_row': ('row',),
            'drop_item': ('item',), 'party_invite': ('name',), 'pickup_item': ('item',),
            'shop_buy': ('item', 'quantity'), 'shop_sell': ('item',), 'use_item': ('item',),
            'trade_invite': ('name',), 'trade_add_item': ('item',), 'trade_add_meso': ('meso',),
            'use_cash_item': ('item', 'target'), 'merchant_open': ('item', 'quantity', 'price')}

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
# A held key repeats its key-down the way a keyboard's auto-repeat does: the game acts once per key-down, so one down
# held for 3 s attacked once (qa-attack-step, Server Dev's packet read 2026-11-07). Windows' defaults: 250 ms, ~30/s.
KEY_REPEAT_DELAY = 0.25
# The game's jump key (keymap 56 = type 5 action 53, the stock binding). Alt is never sent with anything but arrows
# and Up, so it makes no window shortcut (no F4, Enter, Tab).
JUMP_KEY = 0x12
KEY_REPEAT_EVERY = 0.033
# The first chat line after a client start loses its first key, so command() sends this line first to absorb it.
# Never a toggle: !unhide casts GM Hide, which toggles, and priming with it before login's own !unhide hid the GM
# again (bots ignore hidden players: cv-13, 2026-11-01). !dispose only clears NPC/quest dialog state.
CHAT_PRIMER = '!dispose'
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


def test_host():
    """The TEST server's address: SLUMS_TEST_HOST through the one guard (testhost.py), allowlisted to TEST's boxes."""
    try:
        return testhost.host()
    except testhost.Refused as error:
        raise Refusal(str(error)) from None


def verify_config(path, port=None):
    """Require exactly one endpoint pair on a TEST instance (A's or B's port); never silently rewrite an unsafe
    target. `port` also requires that instance's port."""
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
    try:
        wanted = testhost.host()
        for value in hosts:
            testhost.host(value, source='config host')
        for value in ports:
            testhost.port(value, source='config port')
    except testhost.Refused as error:
        raise Refusal('Refusing client config: %s' % error) from None
    allowed = [port] if port else list(testhost.PORTS)
    if hosts != [wanted] or len(ports) != 1 or ports[0] not in allowed:
        raise Refusal('Refusing client config: require exactly %s port %s, the config has %s port %s.'
                      % (wanted, ' or '.join(allowed), ' and '.join(hosts) or 'no host',
                         ' and '.join(ports) or 'no port'))


def point_config(path, instance):
    """qa-test-b (owner 9 Nov): sets the client's login port to the run's TEST instance (a 8489, b 8589). Only a
    config that already passes verify_config (TEST's host, a TEST port) is rewritten, and only its one port line."""
    try:
        wanted = testhost.instance_port(testhost.instance(instance, source='instance'))
    except testhost.Refused as error:
        raise Refusal(str(error)) from None
    verify_config(path)
    text = path.read_text(encoding='utf-8-sig')
    lines = text.splitlines(keepends=True)
    for i, line in enumerate(lines):
        match = re.fullmatch(r'(\s*port\s*=\s*)(\S*)(\s*)', line, flags=re.IGNORECASE)
        if match:
            lines[i] = match.group(1) + wanted + match.group(3)
    path.write_text(''.join(lines), encoding='utf-8')
    verify_config(path, port=wanted)
    return wanted


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
SCRIPT_TOOLS = {'start_client', 'login', 'login_credentials', 'click_anchor', 'select_world', 'select_channel',
                'select_character', 'qa_events', 'command', 'press_key', 'mouse_click', 'mouse_drag', 'screenshot',
                'qa_wait', 'qa_burst', 'wait_ms', 'stop_client', 'go_to', 'click_npc', 'click_option', 'click_dialog_row', 'position',
                'walk_to', 'climb',
                'drop_item', 'party_create', 'party_invite', 'close_dialog',
                'dialog_next', 'dialog_decline', 'use_cash_item',
                'merchant_open', 'merchant_close', 'fredrick_retrieve', 'pickup_item',
                'shop_buy', 'shop_sell', 'shop_close', 'use_item', 'trade_invite', 'trade_accept',
                'trade_add_item', 'trade_add_meso', 'trade_confirm', 'trade_cancel', 'jump'}
NAME = re.compile(r'^[A-Za-z0-9]{4,12}$')   # a v83 character name (trade_invite)
# A QA vault alias (secrets/<vault>.txt) is a script name: secrets/aq-gm-1.txt. Never a path.
VAULT = re.compile(r'^[a-z0-9][a-z0-9-]{1,31}$')
# The QA build writes this once per login (slums.dll bypass.cpp ClientDataCaps); the manifest's caps come from it.
BUILD_STAMP_CAPS = re.compile(r'Build stamp: caps (0x[0-9A-Fa-f]{8})')
QA_EVENTS_MAX = 200
NPC_POS_TIMEOUT = 3.0   # the client answers npc_pos / click_option on its next frame
DROP_TIMEOUT = 6.0      # qa-drop-honest: the client waits up to 3 s for the server to take the items
# qa-walk-to (1.6.7): position polling while an arrow is held.
WALK_POLL = 0.04        # s between position reads
WALK_STALL = 0.6        # s without 2 px of progress = no_progress
WALK_AIR = 0.3          # s off every foothold, rope and ladder = fell
WALK_DROP_PX = 12       # standing this far below the start floor = fell
WALK_TAP = 0.03         # s a correction tap holds the arrow (halved after a tap crosses the target) ...
WALK_TAP_MIN = 0.015    # ... down to this; shorter taps can fall between DirectInput's polls
WALK_TAPS = 6           # correction taps before overshoot
GRAB_WAIT = 0.5         # s for Up / Down to grab the ladder or rope
GRAB_JUMP_WAIT = 0.8    # s after the jump (Up held) for the grab, before no_ladder
NPC_OPTION_ANCHOR = re.compile(r'^npc_dialog\.option_([1-8])$')
CHAR_NAME = re.compile(r'^[A-Za-z0-9]{4,12}$')
CASH_TARGET = re.compile(r'^([1-5]:[0-9]{1,3}|[1-5][0-9]{6})$')   # inv:slot, or an inventory item id   # v83 character names; the QA client checks the same


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
        self.chat_primed = True   # False after start_client: the first chat line after a login loses its first key
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
        # VM-only opt-in (owner OK 2026-10-30): the test VM's primary is a virtual display and the client runs there.
        if display['virtual'] and not (self.root / 'allow-virtual-display').exists():
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

    def screen_refs(self, name):
        check = self.ui['screens'][name]
        if not check.get('templates'):
            raise Refusal('Screen templates for ' + name + ' have not been calibrated.')
        refs = []
        for template in check['templates']:
            file = inside(self.root / template['file'], self.root)
            with Image.open(file) as reference:
                refs.append((template, reference.convert('RGB')))
        return refs

    def chat_pixels_match(self):
        """The chat_open template alone (no client-state fallback): which half of matches('chat_open') saw it open."""
        refs = self.screen_refs('chat_open')
        if self.matches_at(refs, self.image(), 0):
            return True
        return bool(self.field_dy) and self.matches_native(refs, self.field_dy)

    def chat_gate_refusal(self, what):
        # qa-chatgate-evidence (1.6.7): keep what the gate saw (horntail-stand-short-20261109T180625Z refused with
        # nothing kept): the screenshot, the pixel half of the check, and the client's own state with its age.
        shot = self.save_shot('chat-gate-refused', include_image=False)
        kept = {'utc': datetime.now(timezone.utc).isoformat(), 'shot': shot['path']}
        try:
            kept['pixel_match'] = self.chat_pixels_match()
        except Exception as error:  # evidence only: never hide the refusal behind it
            kept['pixel_match'] = 'error: ' + type(error).__name__
        path = inside(self.root / 'client' / 'slums-ui-state.json', self.root)
        try:
            kept['ui_state_age_s'] = round(time.time() - path.stat().st_mtime, 2)
            kept['ui_state'] = json.loads(path.read_text(encoding='ascii'))
        except (OSError, ValueError) as error:
            kept['ui_state_error'] = type(error).__name__
        out = inside(Path(shot['path']).with_suffix('.json'), self.root)
        out.write_text(json.dumps(kept, indent=1), encoding='utf-8')
        return Refusal('Chat entry is open and did not close; ' + what + ' not sent. Evidence: ' + str(out))

    def matches(self, name, image=None):
        image = self.image() if image is None else image
        refs = self.screen_refs(name)
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

    def start_client(self, instance=None):
        """Starts the client on the run's TEST instance (qa-test-b): its config.ini port is set to it first."""
        self.endpoint()
        point_config(inside(self.root / 'client' / 'config.ini', self.root), instance)
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
        self.chat_primed = False
        self.unhidden = False   # GM Hide is on again after a client start
        self.wait('login', 60)
        self.qa = QaPipe.connect(self.client.pids(), timeout=5) if self.qa_build() else None
        return {'state': self.state, 'pids': self.client.pids(), 'qa_pipe': bool(self.qa),
                'instance': testhost.instance(instance)}

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

    def qa_events(self, since=0):
        """The QA pipe's events from position `since` (a count of events read so far), oldest first, and `next` for
        the following call. The names are the client's (qamodule_policy.h); a bare signal carries no payload."""
        events, _ = self.need_qa().events_from(since)
        more = len(events) > QA_EVENTS_MAX
        events = events[:QA_EVENTS_MAX]
        out = []
        for event in events:
            item = {key: value for key, value in event.items() if key != '_at'}
            item['utc'] = utc(event.get('_at', time.time())).replace('+00:00', 'Z')
            out.append(item)
        return {'from': since, 'next': since + len(events), 'more': more, 'events': out}

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
        self.click_bera()
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

    def vault_secrets(self, vault):
        """(account, password[, pic]) of secrets/<vault>.txt: one file per QA vault alias, never a script value."""
        if not VAULT.match(vault or ''):
            raise Refusal('A vault alias is 2-32 of a-z 0-9 - (secrets/<vault>.txt).')
        path = inside(self.root / 'secrets' / (vault + '.txt'), self.root)
        if not path.is_file():
            raise Refusal('Create secrets/' + vault + '.txt: account on line 1, password on line 2, PIC on line 3.')
        return Secrets(path).load()

    def click_bera(self):
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
            # Release build (no pipe): the click is often lost the same way (b249), so click again while the channel
            # screen does not show; the caller's wait('channel') then reports a real failure with its screenshot.
            for attempt in range(3):
                self.click('bera')
                if self.channel_shows(6):
                    break

    def channel_shows(self, timeout):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            self.guard()
            try:
                if self.matches('channel'):
                    return True
            except self.backend.Blocked as exc:
                if 'found 0' not in str(exc):
                    raise
            time.sleep(0.25)
        return False

    def login_credentials(self, vault):
        """Types one vault account and its password at the login screen and presses Enter (ticket 170).

        Only the part that needs the vault: world, channel and character are the orchestrator's select_* steps, each
        after its own pipe signal. The PIC is kept in this process for select_character, never returned."""
        self.reload_ui()
        self.endpoint()
        self.prepare_login()
        account, password, *rest = self.vault_secrets(vault)
        self.vault_pic = rest[0] if rest else None
        self.sensitive_screen = True
        self.sender.text(account)
        self.sender.key(9)
        self.sender.text(password)
        self.sender.key(13)
        # qa-login-cred (1.6.7): a new account's first login shows the Terms of Use and then the gender prompt before
        # the world list; get past them the way login does (9 Nov, Silvia DSH dry check).
        first = []
        screen = self.wait_choice(('terms', 'gender', 'world'))
        if screen == 'terms':
            first.append('terms')
            self.accept_terms()
            screen = self.wait_choice(('gender', 'world'))
        if screen == 'gender':
            first.append('gender')
            self.click('gender_confirm')
        # No qa.drain() here: the orchestrator reads the pipe's log by position (qa_events) and must see everything.
        return {'vault': vault, 'entered': True, 'first_login': first}

    # ui.json points only client scripts click (`do: click, anchor: ...`); the driver itself never does. The Daily
    # Check-in window opens over the field at every login and hides the scene a script films (b008, 2026-11-01).
    SCRIPT_ANCHORS = ('checkin_close',)

    def click_anchor(self, name):
        """Clicks one calibrated ui.json point (docs/qa/client-scripts/anchors.yaml). x,y never come from a script.
        npc_dialog.option_<n> is not a point: it picks menu row n through the QA client (click_option); nor is
        npc_dialog.close, which ends the dialog the way its close button does (close_dialog), nor npc_dialog.next, its
        OK / Next / Yes button (dialog_next)."""
        option = NPC_OPTION_ANCHOR.match(str(name))
        if option:
            return self.click_option(int(option.group(1)))
        if name == 'npc_dialog.close':
            return self.close_dialog()
        if name == 'npc_dialog.next':
            return self.dialog_next()
        if name == 'npc_dialog.decline':
            return self.dialog_decline()
        if name not in (self.ui.get('points') or {}):
            raise Refusal('Anchor ' + str(name) + ' is not a calibrated point in ui.json: add it to '
                          'docs/qa/client-scripts/anchors.yaml first.')
        self.click(name)
        return {'clicked': name, 'point': list(self.ui['points'][name])}

    def select_world(self, world):
        if world != 'Bera':
            raise Refusal('Only the Bera world is configured for TEST.')
        if 'bera' not in (self.ui.get('points') or {}):
            raise Refusal('ui.json has no bera point (the world list is not calibrated).')
        self.click('bera')
        return {'world': world}

    def select_channel(self, channel):
        point = 'channel_' + str(channel)
        if point not in (self.ui.get('points') or {}):
            raise Refusal('ui.json has no ' + point + ' point.')
        self.click(point)
        self.click('channel_confirm')
        return {'channel': channel}

    def select_character(self, slot=0):
        """Picks the character in `slot` of the character-select screen and confirms it (PIC if the vault has one)."""
        point = 'first_character' if slot == 0 else 'character_slot_' + str(slot)
        if point not in (self.ui.get('points') or {}):
            raise Refusal('ui.json has no ' + point + ' point: only slot 0 (the first character) is calibrated.')
        if self.wait_choice(('empty_character', 'character')) == 'empty_character':
            shot = self.save_shot('vault-account-has-no-character', include_image=False)
            raise Refusal('This vault account has no characters on Bera; prepare one first; screenshot: ' + shot['path'])
        self.click(point)
        self.click('character_confirm')
        pic = getattr(self, 'vault_pic', None)
        if pic:
            self.enter_pic(pic)
        return {'slot': slot, 'character': point}

    def command(self, line):
        if not self.chat_primed:
            # The first chat line after a login loses its first key even with the Backspace below (the "!" of !warp
            # went out as plain chat in the first scripted b140 run, 2026-10-31 09:25 UTC). login() always sent
            # !unhide first, which absorbed it; the scripted login (select_* steps) doesn't, so do the same here.
            self.chat_primed = True
            # Exactly one !unhide per login: login() sends its own right after this primer; a scripted login
            # (select_* steps) sends none, so the primer is that one !unhide there (it toggles GM Hide: never twice).
            if line != '!unhide' and not getattr(self, 'unhidden', False):
                self.command('!unhide')
            else:
                self.command(CHAT_PRIMER)
            time.sleep(CHAT_SETTLE)
        if line == '!unhide':
            self.unhidden = True
        self.endpoint()
        if not line or len(line) > 160 or any(ord(ch) < 32 for ch in line):
            raise Refusal('Chat line must be 1–160 printable characters, with no newlines.')
        if not self.matches('field'):
            raise Refusal('Field screen is not visible.')
        # Never send a caller-supplied credential into chat.
        self.secrets.load()
        if any(value in line for value in self.secrets.values):
            raise Refusal('Chat line contains protected account data.')
        if self.matches('chat_open'):
            # An open, EMPTY chat box takes Shift+1 / Shift+2 as chat-type hotkeys, so "!warp" typed into it lost its
            # "!" (2026-10-31: after login and after a mouse click). Close it and open it fresh, as the old
            # cmd.sh did; Enter on an empty box closes it without sending anything.
            self.sender.key(13)
            deadline = time.monotonic() + 3
            while self.matches('chat_open'):
                if time.monotonic() >= deadline:
                    raise self.chat_gate_refusal('line')
                time.sleep(0.1)
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
        start = self.qa.events_from(1 << 62)[1] if self.qa and line.startswith('!') else None
        self.sender.key(13)
        self.state = 'field'
        if start is not None:
            self.check_command_echo(line, start)
        return {'sent': True}

    def check_command_echo(self, line, start):
        # qa-line-damaged (1.6.7): a command whose "!" was lost on the way in goes out as plain chat and comes back
        # as the client's own chat line, a tail of what was typed (cygnus-fight-player-20261109T193047Z: "4 8850011"
        # and "mobdamage 14 8850011", each right after a "Welcome to Bera!" banner, every step "ok"). Reads the log by
        # position, so a qa_wait after this still sees every event. The echo carries no speaker, so someone else's line
        # of 4+ characters that happens to end the command would also refuse it. A lost character that keeps the "!"
        # is not seen.
        deadline = time.monotonic() + COMMAND_ECHO_WINDOW
        while True:
            events, start = self.qa.events_from(start)
            for event in events:
                text = event.get('text') if event.get('ev') == 'chat.received' else None
                if event.get('kind') in (0, 1) and text and len(text) >= 4 and line.endswith(text):
                    raise Refusal('line_damaged: the command went out as chat "' + text + '"; it did not run.')
            if time.monotonic() >= deadline:
                return
            time.sleep(0.05)

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
        if hold_ms and (combo is not None or name in ('ESCAPE', 'ENTER', 'TAB', 'BACKSPACE')):
            # b024: hold_ms used to be ignored silently for every key but the arrows. Keys a hold makes no sense for
            # (or would repeat into an open chat box) now say so instead of returning as if held.
            raise Refusal('hold_ms works for single game keys (arrows, letters, digits, F-keys, Space, Ctrl, ...), '
                          'not for ' + name + '.')
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
                    raise self.chat_gate_refusal('key')
                time.sleep(0.1)
        if hold_ms:
            # Hold the key up to 3 s: arrows to walk, Ctrl to keep attacking, a skill key to charge or repeat (b024).
            # The guard is checked every 50 ms; the release always runs.
            hold_ms = max(0, min(int(hold_ms), 3000))
            self.sender.key(key[0], True, extended=key[1])
            try:
                start = time.monotonic()
                end = start + hold_ms / 1000
                repeat = start + KEY_REPEAT_DELAY
                while time.monotonic() < end:
                    self.guard()
                    now = time.monotonic()
                    if now >= repeat:
                        self.sender.key(key[0], True, extended=key[1])   # auto-repeat: another down, no up
                        repeat = now + KEY_REPEAT_EVERY
                    time.sleep(max(0.0, min(repeat, end) - time.monotonic()))
            finally:
                self.sender.key(key[0], False, extended=key[1])
            return {'pressed': name, 'held_ms': hold_ms}
        self.sender.key(key[0], extended=key[1])
        return {'pressed': name}

    def mouse_click(self, x, y, button='left'):
        self.field_input()
        self.sender.click(x, y, right=button == 'right', double=button == 'double')
        return {'clicked': [x, y], 'button': button}

    def click_npc(self, npc):
        """P1-Q2: clicks npc <id> where the QA client draws it. It does not walk: on out_of_view the script moves and
        tries again. Errors are the client's codes (npc_not_on_map, out_of_view, field_not_ready)."""
        pipe = self.need_qa()
        self.field_input()
        pipe.send('npc_pos %d' % npc)
        deadline = time.monotonic() + NPC_POS_TIMEOUT
        while True:
            left = deadline - time.monotonic()
            event = pipe.wait('npc.pos', left) if left > 0 else None
            if event is None:
                raise Refusal('field_not_ready')   # the client did not answer: no field to look in
            if event.get('npc') == npc:
                break
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'field_not_ready'))
        x, y = int(event['x']), int(event['y'])
        if not (0 <= x <= 799 and 0 <= y <= 599):
            raise Refusal('out_of_view')
        self.sender.click(x, y, right=False, double=False)
        return {'npc': npc, 'clicked': [x, y]}

    def position(self):
        """qa-position-signal (1.6.6): the character's map position from the QA client: map_id, x, y (the server's !pos
        coordinates) and whether it stands on a foothold, a rope or a ladder (all false: in the air). Presses nothing.
        Errors are the client's codes (field_not_ready, site_mismatch)."""
        pipe = self.need_qa()
        pipe.send('pos')
        event = pipe.wait('player.pos', NPC_POS_TIMEOUT)
        if event is None:
            raise Refusal('field_not_ready')   # the client did not answer: no field to look in
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'field_not_ready'))
        return {key: event[key] for key in ('map_id', 'x', 'y', 'foothold', 'rope', 'ladder')}

    def click_option(self, option):
        """P1-Q2: picks row <option> (1-8) of the open npc menu. The QA client ends the dialog through its own reply
        path, so the server gets the same packet a click sends; no mouse. Errors: not_a_menu, no_such_option."""
        pipe = self.need_qa()
        pipe.send('click_option %d' % option)
        deadline = time.monotonic() + NPC_POS_TIMEOUT
        while True:
            left = deadline - time.monotonic()
            event = pipe.wait('click.done', left) if left > 0 else None
            if event is None:
                raise Refusal('not_a_menu')   # no answer: no npc menu open to end
            if event.get('op') == 'click_option' and event.get('option') == option:
                break
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'not_a_menu'))
        return {'option': option, 'value': event.get('value')}

    def click_dialog_row(self, row, rows=0):
        """qa-dialog-row (1.6.7): picks row <row> of the open npc dialog from the dialog's own row list and ends it with
        OK, as a click on the row and NEXT do. That also answers the menu the client builds itself for a quest npc
        (Ifia 2161001, Wyatt 1300009, Rain 12101): a row past its quests sends NPC_TALK. rows > 0 refuses a dialog with
        another count. Errors: no_dialog, not_a_menu, row_count, no_such_row, site_mismatch."""
        command = 'click_dialog_row %d' % row + (' %d' % rows if rows else '')
        pipe = self.need_qa()
        pipe.send(command)
        deadline = time.monotonic() + NPC_POS_TIMEOUT
        while True:
            left = deadline - time.monotonic()
            event = pipe.wait('click.done', left) if left > 0 else None
            if event is None:
                raise Refusal('no_dialog')   # no answer: no npc dialog open
            if event.get('op') == 'click_dialog_row' and event.get('row') == row:
                break
        if not event.get('ok'):
            error = str(event.get('error') or 'no_dialog')
            if error == 'row_count':
                error += ': the dialog has %s rows' % event.get('rows')
            raise Refusal(error)
        return {'row': row, 'rows': event.get('rows'), 'value': event.get('value')}

    def qa_request(self, command, ev, matches, timeout_error, timeout=NPC_POS_TIMEOUT):
        """Sends one QA client command and returns its answer event; the client's error code is the refusal."""
        pipe = self.need_qa()
        pipe.send(command)
        deadline = time.monotonic() + timeout
        while True:
            left = deadline - time.monotonic()
            event = pipe.wait(ev, left) if left > 0 else None
            if event is None:
                raise Refusal(timeout_error)
            if matches(event):
                break
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or timeout_error))
        return event

    def close_dialog(self):
        """D1: ends the open npc dialog the way its close button does (any type; the server gets "end chat"). For the
        welcome dialog that blocks a new character's chat. Error: no_dialog."""
        self.qa_request('close_dialog', 'click.done', lambda e: e.get('op') == 'close_dialog', 'no_dialog')
        return {'op': 'close_dialog'}

    def dialog_next(self):
        """H1: presses OK / Next / Yes / Accept on the open npc dialog the way the button does (the server gets action
        1). Errors: no_dialog, use_click_option (a menu), no_next_button (a text or number prompt)."""
        event = self.qa_request('dialog_next', 'click.done', lambda e: e.get('op') == 'dialog_next', 'no_dialog')
        return {'op': 'dialog_next', 'type': event.get('type')}

    def dialog_decline(self):
        """qa-quest-dialog (1.6.7): presses No / Decline on the open npc dialog by its own type, also a stock quest's
        Accept/Decline dialog, which no NPC_TALK brings (QA build 23). Errors: no_dialog, no_decline_button,
        use_click_option (a menu), site_mismatch."""
        event = self.qa_request('dialog_decline', 'click.done', lambda e: e.get('op') == 'dialog_decline', 'no_dialog')
        return {'op': 'dialog_decline', 'type': event.get('type')}

    def merchant_open(self, item, quantity, price, title='QA shop'):
        """P0-2 GP8: sets up a hired merchant (the Fredrick check, CREATE, the item, Open) through the QA client, which
        needs a hired merchant item (503xxxx) in the Cash tab and a free-market room. The setup takes a few seconds.
        Errors: no_merchant_item, not_in_inventory, retrieve_from_fredrick_first, refused, timeout_<stage>."""
        if any(ch in title for ch in '"\\') or not title.strip() or any(ord(ch) < 32 or ord(ch) > 126 for ch in title):
            raise Refusal('title must be 1-30 printable characters without quotes')
        pipe = self.need_qa()
        pipe.send('merchant_open %d %d %d %s' % (item, quantity, price, title.strip()))
        deadline = time.monotonic() + 25
        while True:
            left = deadline - time.monotonic()
            event = pipe.wait('merchant.open', left) if left > 0 else None
            if event is None:
                raise Refusal('timeout')
            if event.get('item') == item:
                break
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return {'item': item, 'quantity': quantity, 'price': price, 'slot': event.get('slot'),
                'merchant_item': event.get('merchant_item')}

    def merchant_close(self):
        """P0-2 GP8: closes this character's hired merchant the way its owner does: visit it (maintenance), then the
        owner window's close; the unsold items and mesos go to Fredrick. Errors: no_merchant, timeout_<stage>."""
        pipe = self.need_qa()
        pipe.send('merchant_close')
        event = pipe.wait('merchant.close', 15)
        if event is None:
            raise Refusal('timeout')
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return {'closed': True}

    def fredrick_retrieve(self):
        """P0-2 GP8: Fredrick's Retrieve button (click Fredrick, 9030000, first, as a player would). Takes everything the
        closed merchant left; the answer is the server's FREDRICK_MESSAGE code (0x1E = handed over). Errors: refused
        (with the code), timeout, field_not_ready."""
        pipe = self.need_qa()
        pipe.send('fredrick_retrieve')
        event = pipe.wait('item.fredrick', 10)
        if event is None:
            raise Refusal('timeout')
        if not event.get('ok'):
            raise Refusal('%s (code %s)' % (event.get('error') or 'refused', event.get('code')))
        return {'retrieved': True, 'code': event.get('code')}

    def pickup_item(self, item, count=1):
        """b344: picks up the drop of <item> nearest the character (qa-pickup-nearest, QA build 18b) through the client's
        own pickup sender (0x00A09118), the way a player's pickup key does. Passes only when the server's
        REMOVE_ITEM_FROM_MAP names this character as the picker; the answer says where the drop was. Errors: no_drop,
        out_of_range (the nearest drop is outside the server's 800 x 600 pickup box; it names that drop), taken_by_other
        (with the picker), drop_gone, field_changed, site_mismatch, field_not_ready, timeout.
        qa-pickup-count (1.6.7): count N picks N drops one after another (the nearest each time) and answers
        {picked: N, item, picks: [{oid, x, y, distance, ok}]}; a refusal at pick k stops there with
        'picked k of N, then <error> | picks [...]'. count 1 answers as before."""
        if count == 1:
            return self.pickup_one(item)
        picks = []
        for _ in range(count):
            try:
                one = self.pickup_one(item)
            except Refusal as error:
                raise Refusal('picked %d of %d, then %s | picks %s' % (len(picks), count, error, json.dumps(picks)))
            picks.append({'oid': one.get('oid'), 'x': one.get('x'), 'y': one.get('y'),
                          'distance': one.get('distance'), 'ok': True})
        return {'picked': count, 'item': item, 'picks': picks}

    def pickup_one(self, item):
        pipe = self.need_qa()
        pipe.send('pickup_item %d' % item)
        event = pipe.wait('item.picked', 12)
        if event is None:
            raise Refusal('timeout')
        if not event.get('ok'):
            error = str(event.get('error') or 'refused')
            if error == 'out_of_range':
                raise Refusal('out_of_range (nearest drop at %s,%s, %s px away)'
                              % (event.get('x'), event.get('y'), event.get('distance')))
            raise Refusal('%s (picker %s)' % (error, event.get('picker')) if event.get('picker') else error)
        picked = {'picked': True, 'item': event.get('item'), 'oid': event.get('oid'), 'type': event.get('type'),
                  'slot': event.get('slot')}
        picked.update({key: event[key] for key in ('x', 'y', 'distance') if key in event})
        return picked

    def shop_wait(self, command, timeout):
        pipe = self.need_qa()
        pipe.send(command)
        event = pipe.wait('shop.result', timeout)
        if event is None:
            raise Refusal('timeout')
        return event

    def shop_buy(self, item, quantity):
        """1.6.5 GP4: buys <quantity> of <item> from the open NPC shop with the packet the shop's Buy button sends.
        Passes on the server's answer 0. Errors: not_enough_mesos, inventory_full, code_<n>, not_in_shop, no_shop,
        timeout, site_mismatch."""
        event = self.shop_wait('shop_buy %d %d' % (item, quantity), 10)
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return {'op': 'buy', 'item': event.get('item'), 'quantity': event.get('quantity'), 'code': event.get('code')}

    def shop_sell(self, item, quantity=0):
        """1.6.5 GP4: sells <quantity> of <item> at the open NPC shop (0 = every stack), one Sell request per stack, each
        answered by the server (8 = sold). Errors: not_in_inventory, not_enough_items / not_sold / timeout (with how many
        were sold before), no_shop, site_mismatch."""
        command = 'shop_sell %d %d' % (item, quantity) if quantity else 'shop_sell %d' % item
        event = self.shop_wait(command, 60)
        if not event.get('ok'):
            error = str(event.get('error') or 'refused')
            raise Refusal('%s (sold %s)' % (error, event.get('sold')) if event.get('sold') else error)
        return {'op': 'sell', 'item': event.get('item'), 'sold': event.get('sold'), 'stacks': event.get('stacks')}

    def shop_close(self):
        """1.6.5 GP4: closes the open NPC shop the way its close button does (NPC_SHOP [3]). Error: no_shop."""
        event = self.shop_wait('shop_close', 10)
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return {'op': 'close'}

    def use_item(self, item):
        """1.6.5 GP4 leg B: uses <item>'s first USE stack with USE_ITEM, as a double-click on it does. Passes once the
        stack's count dropped (the server took one). Errors: not_in_inventory, not_used, field_not_ready."""
        pipe = self.need_qa()
        pipe.send('use_item %d' % item)
        event = pipe.wait('item.used', 8)
        if event is None:
            raise Refusal('timeout')
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return {'used': True, 'item': event.get('item'), 'slot': event.get('slot'), 'left': event.get('left')}

    def trade_request(self, command):
        pipe = self.need_qa()
        pipe.send(command)
        event = pipe.wait('trade.result', 10)
        if event is None:
            raise Refusal('timeout')
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return event

    def trade_invite(self, name):
        """1.6.5 GP6: opens a trade and invites <name> (a player on this field), as the trade window's Invite does.
        Whether they accept shows as the trade.joined event. Errors: not_on_map, timeout."""
        if not NAME.match(str(name)):
            raise Refusal('name must be 4-12 letters or digits')
        event = self.trade_request('trade_invite %s' % name)
        return {'op': 'invite', 'name': event.get('name'), 'cid': event.get('cid')}

    def trade_accept(self):
        """1.6.5 GP6: accepts the last trade invite. Errors: no_invite, already_trading, timeout."""
        self.trade_request('trade_accept')
        return {'op': 'accept'}

    def trade_add_item(self, item, quantity=0):
        """1.6.5 GP6: puts <quantity> of <item>'s first stack into the trade (0 = the whole stack). Passes once the
        server shows it in the window. Errors: no_trade, not_in_inventory, not_enough_items, trade_full, timeout."""
        command = 'trade_add_item %d %d' % (item, quantity) if quantity else 'trade_add_item %d' % item
        event = self.trade_request(command)
        return {'op': 'add_item', 'item': event.get('item'), 'slot': event.get('slot'), 'quantity': event.get('quantity')}

    def trade_add_meso(self, meso):
        """1.6.5 GP6: offers <meso> mesos in the trade. Errors: no_trade, timeout (the server refused the amount)."""
        event = self.trade_request('trade_add_meso %d' % meso)
        return {'op': 'add_meso', 'meso': event.get('set')}

    def trade_confirm(self, timeout_s=120):
        """1.6.5 GP6: presses Trade, then waits up to <timeout_s> for the partner and the server's result. Passes on
        result 7 (done). Errors: no_trade, partner_cancelled, unsuccessful, unique_item_limit, ..., timeout."""
        self.trade_request('trade_confirm')
        event = self.need_qa().wait('trade.done', timeout_s)
        if event is None:
            raise Refusal('timeout')
        if not event.get('ok'):
            raise Refusal(str(event.get('error') or 'refused'))
        return {'op': 'confirm', 'done': True, 'result': event.get('result')}

    def trade_cancel(self):
        """1.6.5 GP6: closes the open trade, as its window's close does. Error: no_trade."""
        self.trade_request('trade_cancel')
        return {'op': 'cancel'}

    def jump(self, dir='none', catch=0, run_ms=0, hold_ms=800):
        """b360: jumps with the game's jump key. dir left/right holds that arrow for run_ms first (a running jump);
        catch holds Up through the jump, as a player grabs a rope or ladder. Every key is released after hold_ms, also
        when the owner takes over. Where the character ended up is the server's !pos (or its movement lines)."""
        arrows = {'none': None, 'left': KEYS['LEFT'], 'right': KEYS['RIGHT']}
        if dir not in arrows:
            raise Refusal('dir must be none, left or right')
        self.field_input()
        pressed = []
        try:
            if arrows[dir]:
                self.sender.key(arrows[dir][0], True, extended=True)
                pressed.append(arrows[dir][0])
                end = time.monotonic() + run_ms / 1000
                while time.monotonic() < end:
                    self.guard()
                    time.sleep(min(0.05, max(0.0, end - time.monotonic())))
            if catch:
                self.sender.key(KEYS['UP'][0], True, extended=True)
                pressed.append(KEYS['UP'][0])
            self.guard()
            self.sender.key(JUMP_KEY, True)
            try:
                time.sleep(0.06)  # DirectInput polls state; an atomic tap can be missed (as WindowsSender.key)
            finally:
                self.sender.key(JUMP_KEY, False)
            end = time.monotonic() + hold_ms / 1000
            while time.monotonic() < end:
                self.guard()
                time.sleep(min(0.05, max(0.0, end - time.monotonic())))
        finally:
            for vk in reversed(pressed):
                self.sender.key(vk, False, extended=True)
        return {'jumped': True, 'dir': dir, 'catch': bool(catch)}

    def _walk_read(self, start, deadline, track):
        """One position read for walk_to / climb, with the checks every read gets: timeout, map change, a fall."""
        self.guard()
        p = self.position()
        where = '(x %d, y %d)' % (p['x'], p['y'])
        if p['map_id'] != start['map_id']:
            raise Refusal('map_changed (map %d, x %d, y %d)' % (p['map_id'], p['x'], p['y']))
        now = time.monotonic()
        if p['foothold'] or p['rope'] or p['ladder']:
            track['air'] = None
        elif track.get('air') is None:
            track['air'] = now
        elif now - track['air'] > WALK_AIR:
            raise Refusal('fell ' + where)
        if track.get('floor') and p['foothold'] and p['y'] > start['y'] + WALK_DROP_PX:
            raise Refusal('fell ' + where)
        if now > deadline:
            raise Refusal('timeout ' + where)
        return p

    def _walk_settle(self, start, deadline, track, axis):
        """After a release: reads until the character stops (two equal reads, at most 0.5 s)."""
        last = self._walk_read(start, deadline, track)
        end = time.monotonic() + 0.5
        while time.monotonic() < end:
            time.sleep(WALK_POLL)
            p = self._walk_read(start, deadline, track)
            if p[axis] == last[axis]:
                return p
            last = p
        return last

    def _walk_hold(self, vk, start, deadline, track, axis, reached, grab=False):
        """Holds <vk> until reached(position) or the character stops making progress; releases it on every exit."""
        self.sender.key(vk, True, extended=True)
        try:
            p = self._walk_read(start, deadline, track)
            if grab:
                end = time.monotonic() + GRAB_WAIT
                jumped = vk != KEYS['UP'][0]   # only Up gets the jump
                while not (p['ladder'] or p['rope']):
                    if time.monotonic() > end:
                        if jumped:
                            raise Refusal('no_ladder (x %d, y %d)' % (p['x'], p['y']))
                        # A ladder or rope whose bottom is above the floor (LPQ stage 1: 45 px) is reached with a
                        # jump, Up still held, as a player does (b360 catch).
                        jumped = True
                        self.sender.key(JUMP_KEY, True)
                        try:
                            time.sleep(0.06)   # DirectInput polls state; an atomic tap can be missed
                        finally:
                            self.sender.key(JUMP_KEY, False)
                        end = time.monotonic() + GRAB_JUMP_WAIT
                    time.sleep(WALK_POLL)
                    p = self._walk_read(start, deadline, track)
            moved_at, moved_from = time.monotonic(), p[axis]
            while not reached(p):
                time.sleep(WALK_POLL)
                p = self._walk_read(start, deadline, track)
                now = time.monotonic()
                if abs(p[axis] - moved_from) >= 2:
                    moved_at, moved_from = now, p[axis]
                elif now - moved_at > WALK_STALL:
                    raise Refusal('no_progress (x %d, y %d)' % (p['x'], p['y']))
            return p
        finally:
            self.sender.key(vk, False, extended=True)

    def _walk_tap(self, vk, hold):
        self.guard()
        self.sender.key(vk, True, extended=True)
        try:
            time.sleep(hold)
        finally:
            self.sender.key(vk, False, extended=True)

    def _walk_correct(self, p, axis, target, tolerance, more, less, start, deadline, track):
        """Short taps toward <target> until within <tolerance>; a tap that crosses the target halves the next one."""
        hold = WALK_TAP
        for _ in range(WALK_TAPS):
            if abs(target - p[axis]) <= tolerance:
                return
            side = target > p[axis]
            self._walk_tap(KEYS[more if side else less][0], hold)
            p = self._walk_settle(start, deadline, track, axis)
            if (target > p[axis]) != side and abs(target - p[axis]) > tolerance:
                hold = max(WALK_TAP_MIN, hold / 2)
        if abs(target - p[axis]) > tolerance:
            raise Refusal('overshoot (x %d, y %d)' % (p['x'], p['y']))

    def walk_to(self, x, tolerance=6, timeout_ms=12000):
        """qa-walk-to (1.6.7): walks on the current floor until the QA client's position is within <tolerance> px of
        <x>, holding the arrow a player holds; a slide past the target is corrected with short taps. Errors (with the
        final x, y): not_on_ground, no_progress (a wall, the map edge, a window), fell, map_changed, overshoot,
        timeout. Every key it pressed is released."""
        self.field_input()
        began = time.monotonic()
        deadline = began + timeout_ms / 1000
        start = self.position()
        if not start['foothold']:
            raise Refusal('not_on_ground (x %d, y %d)' % (start['x'], start['y']))
        track = {'floor': True}
        p = start
        if abs(x - p['x']) > tolerance:
            right = x > p['x']
            vk = KEYS['RIGHT'][0] if right else KEYS['LEFT'][0]
            speed = {'v': 0.0, 'at': time.monotonic(), 'x': p['x']}

            def reached(q):
                now = time.monotonic()
                if now > speed['at']:
                    speed['v'] = abs(q['x'] - speed['x']) / (now - speed['at'])
                speed['at'], speed['x'] = now, q['x']
                left = (x - q['x']) if right else (q['x'] - x)
                return left <= speed['v'] * WALK_POLL + 2   # release a poll early: the slide does the rest
            self._walk_hold(vk, start, deadline, track, 'x', reached)
            p = self._walk_settle(start, deadline, track, 'x')
            self._walk_correct(p, 'x', x, tolerance, 'RIGHT', 'LEFT', start, deadline, track)
            p = self._walk_settle(start, deadline, track, 'x')
        return {'map_id': p['map_id'], 'x': p['x'], 'y': p['y'], 'from_x': start['x'], 'moved': p['x'] - start['x'],
                'ms': int((time.monotonic() - began) * 1000)}

    def climb(self, y, tolerance=6, timeout_ms=15000):
        """qa-walk-to (1.6.7): climbs the ladder or rope the character is on (or stands at the foot or top of: Up / Down
        grabs it; a bottom above the floor is reached with a jump, Up held) until its y is within <tolerance> px of <y>. Climbing past the top or bottom steps onto the floor
        there. Errors (with x, y): no_ladder, stopped_at_top / stopped_at_bottom, no_progress, fell, map_changed,
        timeout. Every key it pressed is released."""
        self.field_input()
        began = time.monotonic()
        deadline = began + timeout_ms / 1000
        start = self.position()
        track = {}
        p = start
        if abs(y - p['y']) > tolerance:
            up = y < p['y']
            vk = KEYS['UP'][0] if up else KEYS['DOWN'][0]

            def reached(q):
                if not (q['ladder'] or q['rope']):
                    return True              # stepped off at the top or bottom (or fell: the next read says)
                return q['y'] <= y if up else q['y'] >= y
            p = self._walk_hold(vk, start, deadline, track, 'y', reached,
                                grab=not (p['ladder'] or p['rope']))
            p = self._walk_settle(start, deadline, track, 'y')
            if abs(y - p['y']) > tolerance:
                if not (p['ladder'] or p['rope']):
                    raise Refusal('%s (x %d, y %d)' % ('stopped_at_top' if up else 'stopped_at_bottom', p['x'], p['y']))
                self._walk_correct(p, 'y', y, tolerance, 'DOWN', 'UP', start, deadline, track)
                p = self._walk_settle(start, deadline, track, 'y')
        return {'map_id': p['map_id'], 'x': p['x'], 'y': p['y'], 'foothold': p['foothold'], 'rope': p['rope'],
                'ladder': p['ladder'], 'from_y': start['y'], 'moved': p['y'] - start['y'],
                'ms': int((time.monotonic() - began) * 1000)}

    def use_cash_item(self, item, target):
        """P0-6: uses cash item <item> on <target> (an item id, or inv:slot) through the client's USE_CASH_ITEM packet.
        The QA client takes only the Incubator (5060002). Errors: not_in_inventory, target_not_in_inventory,
        field_not_ready. The answer says the request was sent; what the server did shows in the inventory and chat."""
        target = str(target)
        if not CASH_TARGET.match(target):
            raise Refusal('target must be an item id or inv:slot')
        event = self.qa_request('use_cash_item %d %s' % (item, target), 'item.use',
                                lambda e: e.get('item') == item, 'no_character')
        return {'item': item, 'slot': event.get('slot'), 'target_type': event.get('target_type'),
                'target_slot': event.get('target_slot')}

    def drop_item(self, item, count=1):
        """P1-Q2: drops <count> of the first stack of <item> through the client's own item-move sender (the packet a
        drag out of the item window sends). qa-drop-honest: ok only once the server took the items (the stack shrank);
        'left' is what stays in the slot. Errors: not_dropped (the server refused it, e.g. a GM character's drop),
        not_in_inventory, not_enough_items, no_character, site_mismatch."""
        event = self.qa_request('drop_item %d %d' % (item, count), 'item.drop',
                                lambda e: e.get('item') == item and e.get('count') == count, 'no_character',
                                DROP_TIMEOUT)
        return {'item': item, 'count': count, 'slot': event.get('slot'), 'left': event.get('left')}

    def party_create(self):
        """P1-Q2: creates a party through the client's own sender (the party window's Create)."""
        self.qa_request('party_create', 'party.sent', lambda e: e.get('op') == 'create', 'field_not_ready')
        return {'op': 'create'}

    def party_invite(self, name):
        """P1-Q2: invites <name> through the client's own sender (the party window's Invite). The client refuses
        itself or a full party without sending; the server's answer shows in chat."""
        if not CHAR_NAME.match(name):
            raise Refusal('name must be 4-12 letters or digits')
        self.qa_request('party_invite %s' % name, 'party.sent',
                        lambda e: e.get('op') == 'invite' and e.get('name') == name, 'field_not_ready')
        return {'op': 'invite', 'name': name}

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
