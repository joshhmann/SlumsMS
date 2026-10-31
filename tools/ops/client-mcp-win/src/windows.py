"""Small, local Win32 backend. Never activates a window or kills by name."""
import ctypes as c
from ctypes import wintypes as w
import os
import msvcrt
from pathlib import Path
import re
import struct
import threading
import time

import idleseed

from PIL import Image

u = c.WinDLL('user32', use_last_error=True)
k = c.WinDLL('kernel32', use_last_error=True)
g = c.WinDLL('gdi32', use_last_error=True)


def api(dll, name, restype, *args):
    fn = getattr(dll, name)
    fn.restype = restype
    fn.argtypes = args
    return fn


PTR = c.c_void_p
SIZE = c.c_size_t
SELF_INPUT_TAG = 0x534C554D
api(k, 'GetTickCount64', c.c_ulonglong)
api(u, 'GetForegroundWindow', w.HWND)
api(u, 'IsHungAppWindow', w.BOOL, w.HWND)
api(u, 'SetForegroundWindow', w.BOOL, w.HWND)
api(u, 'GetWindowLongW', c.c_long, w.HWND, c.c_int)
api(u, 'GetWindowRect', w.BOOL, w.HWND, c.POINTER(w.RECT))
api(u, 'ClientToScreen', w.BOOL, w.HWND, c.POINTER(w.POINT))
try:
    api(u, 'GetWindowDpiAwarenessContext', PTR, w.HWND)
    api(u, 'GetAwarenessFromDpiAwarenessContext', c.c_int, PTR)
except AttributeError:   # older Windows
    pass
api(u, 'keybd_event', None, w.BYTE, w.BYTE, w.DWORD, SIZE)
api(u, 'BringWindowToTop', w.BOOL, w.HWND)
api(u, 'ShowWindow', w.BOOL, w.HWND, c.c_int)
api(u, 'PrintWindow', w.BOOL, w.HWND, w.HDC, w.UINT)
api(u, 'GetWindowThreadProcessId', w.DWORD, w.HWND, c.POINTER(w.DWORD))
api(u, 'GetClassNameW', c.c_int, w.HWND, w.LPWSTR, c.c_int)
api(u, 'GetDC', w.HDC, w.HWND)
api(u, 'ReleaseDC', c.c_int, w.HWND, w.HDC)
api(g, 'CreateCompatibleDC', w.HDC, w.HDC)
api(g, 'CreateCompatibleBitmap', w.HBITMAP, w.HDC, c.c_int, c.c_int)
api(g, 'SelectObject', w.HANDLE, w.HDC, w.HANDLE)
api(g, 'DeleteObject', w.BOOL, w.HANDLE)
api(g, 'DeleteDC', w.BOOL, w.HDC)
api(g, 'BitBlt', w.BOOL, w.HDC, c.c_int, c.c_int, c.c_int, c.c_int,
    w.HDC, c.c_int, c.c_int, w.DWORD)
api(g, 'GetDIBits', c.c_int, w.HDC, w.HBITMAP, w.UINT, w.UINT, PTR, PTR, w.UINT)
api(k, 'CloseHandle', w.BOOL, w.HANDLE)
api(u, 'OpenInputDesktop', w.HANDLE, w.DWORD, w.BOOL, w.DWORD)
api(u, 'CloseDesktop', w.BOOL, w.HANDLE)
api(u, 'GetProcessWindowStation', w.HANDLE)
api(u, 'GetUserObjectInformationW', w.BOOL, w.HANDLE, c.c_int, PTR, w.DWORD,
    c.POINTER(w.DWORD))
api(k, 'CreateJobObjectW', w.HANDLE, PTR, w.LPCWSTR)
api(k, 'AssignProcessToJobObject', w.BOOL, w.HANDLE, w.HANDLE)
api(k, 'QueryInformationJobObject', w.BOOL, w.HANDLE, c.c_int, PTR, w.DWORD, PTR)
api(k, 'SetInformationJobObject', w.BOOL, w.HANDLE, c.c_int, PTR, w.DWORD)
api(k, 'TerminateProcess', w.BOOL, w.HANDLE, w.UINT)
api(k, 'OpenProcess', w.HANDLE, w.DWORD, w.BOOL, w.DWORD)
api(k, 'GetExitCodeProcess', w.BOOL, w.HANDLE, c.POINTER(w.DWORD))
api(k, 'QueryFullProcessImageNameW', w.BOOL, w.HANDLE, w.DWORD, w.LPWSTR,
    c.POINTER(w.DWORD))
api(k, 'ResumeThread', w.DWORD, w.HANDLE)


class Blocked(Exception):
    """Safe, intentionally human-readable refusal."""


class LastInput(c.Structure):
    _fields_ = [('cbSize', w.UINT), ('dwTime', w.DWORD)]


def last_input_tick():
    item = LastInput(c.sizeof(LastInput), 0)
    if not u.GetLastInputInfo(c.byref(item)):
        raise Blocked('Cannot determine owner activity.')
    now = k.GetTickCount64()
    return now - ((now - item.dwTime) & 0xffffffff)


class InputGuard:
    """Track physical input; injected input must not reset the owner's idle time.

    state_path (logs/owner-input.json) carries the clock across worker restarts (b023, idleseed.py)."""
    def __init__(self, state_path=None):
        self.state_path = state_path
        now_tick, now_wall = k.GetTickCount64(), time.time()
        last_input_wall = now_wall - (now_tick - last_input_tick()) / 1000
        seed = idleseed.seed_last_physical(now_wall, last_input_wall,
                                           idleseed.read_state(state_path) if state_path else None)
        self.last_physical = now_tick - int((now_wall - seed) * 1000)
        self.ready = threading.Event()
        self.healthy = False
        self.thread = threading.Thread(target=self._watch, daemon=True)
        self.thread.start()
        self.ready.wait(3)
        if state_path:
            threading.Thread(target=self._keep_state, daemon=True).start()

    def _keep_state(self):
        # Every few seconds while the hooks run: the last physical input seen and "watched until now".
        while True:
            if self.healthy and self.thread.is_alive():
                now_tick, now_wall = k.GetTickCount64(), time.time()
                try:
                    idleseed.write_state(self.state_path, now_wall - (now_tick - self.last_physical) / 1000, now_wall)
                except OSError:
                    pass
            time.sleep(5)

    def record_input(self, flags, extra, injected_mask):
        # Parsec and unrelated injected input still count as owner activity.
        # Ignore only the driver's own explicitly tagged SendInput events.
        if not (flags & injected_mask and extra == SELF_INPUT_TAG):
            self.last_physical = k.GetTickCount64()

    def _watch(self):
        callback = c.WINFUNCTYPE(c.c_ssize_t, c.c_int, SIZE, c.c_ssize_t)
        api(u, 'SetWindowsHookExW', w.HANDLE, c.c_int, callback, w.HINSTANCE, w.DWORD)
        api(u, 'CallNextHookEx', c.c_ssize_t, w.HANDLE, c.c_int, SIZE, c.c_ssize_t)
        api(u, 'UnhookWindowsHookEx', w.BOOL, w.HANDLE)

        def keyboard(code, message, ptr):
            if code >= 0:
                flags = c.cast(ptr, c.POINTER(w.DWORD))[2]
                extra = SIZE.from_address(ptr + 16).value
                self.record_input(flags, extra, 0x10)
            return u.CallNextHookEx(None, code, message, ptr)

        def mouse(code, message, ptr):
            if code >= 0:
                flags = c.cast(ptr, c.POINTER(w.DWORD))[3]
                extra = SIZE.from_address(ptr + (24 if c.sizeof(SIZE) == 8 else 20)).value
                self.record_input(flags, extra, 1)
            return u.CallNextHookEx(None, code, message, ptr)

        self.callbacks = (callback(keyboard), callback(mouse))
        hooks = [u.SetWindowsHookExW(13, self.callbacks[0], None, 0),
                 u.SetWindowsHookExW(14, self.callbacks[1], None, 0)]
        self.healthy = all(hooks)
        self.ready.set()
        try:
            if self.healthy:
                msg = w.MSG()
                while u.GetMessageW(c.byref(msg), None, 0, 0) > 0:
                    u.TranslateMessage(c.byref(msg))
                    u.DispatchMessageW(c.byref(msg))
        finally:
            self.healthy = False
            for hook in hooks:
                if hook:
                    u.UnhookWindowsHookEx(hook)

    def idle_seconds(self):
        if not self.healthy or not self.thread.is_alive():
            raise Blocked('Owner activity monitor is unavailable; refusing automation.')
        return max(0, (k.GetTickCount64() - self.last_physical) / 1000)


class Display(c.Structure):
    _fields_ = [('cb', w.DWORD), ('DeviceName', w.WCHAR * 32),
                ('DeviceString', w.WCHAR * 128), ('StateFlags', w.DWORD),
                ('DeviceID', w.WCHAR * 128), ('DeviceKey', w.WCHAR * 128)]


def primary_display():
    """Query the actual active primary output, including indirect display technology."""
    primary = None
    for index in range(32):
        item = Display()
        item.cb = c.sizeof(item)
        if not u.EnumDisplayDevicesW(None, index, c.byref(item), 0):
            break
        if item.StateFlags & 4:
            primary = {'device': item.DeviceName, 'adapter': item.DeviceString}
            break
    if not primary:
        raise Blocked('Cannot identify the primary display.')
    for _ in range(3):
        paths, modes = w.UINT(), w.UINT()
        if u.GetDisplayConfigBufferSizes(2, c.byref(paths), c.byref(modes)):
            raise Blocked('Cannot query active display topology.')
        path_data = c.create_string_buffer(paths.value * 72)
        mode_data = c.create_string_buffer(modes.value * 64)
        result = u.QueryDisplayConfig(2, c.byref(paths), path_data,
                                     c.byref(modes), mode_data, None)
        if result == 122:
            continue
        if result:
            raise Blocked('Cannot query active display topology.')
        outputs = []
        for index in range(paths.value):
            raw = path_data.raw[index * 72:(index + 1) * 72]
            source = c.create_string_buffer(84)
            source.raw = struct.pack('<II', 1, 84) + raw[:12] + bytes(64)
            if u.DisplayConfigGetDeviceInfo(source):
                raise Blocked('Cannot identify primary display source.')
            name = source.raw[20:84].decode('utf-16-le').split('\0')[0]
            if name != primary['device']:
                continue
            target = c.create_string_buffer(420)
            target.raw = struct.pack('<II', 2, 420) + raw[20:32] + bytes(400)
            if u.DisplayConfigGetDeviceInfo(target):
                raise Blocked('Cannot identify primary monitor.')
            friendly = target.raw[36:164].decode('utf-16-le').split('\0')[0]
            device_path = target.raw[164:420].decode('utf-16-le').split('\0')[0]
            technology = struct.unpack_from('<I', raw, 36)[0]
            outputs.append({'name': friendly, 'path': device_path,
                            'technology': technology})
        primary['outputs'] = outputs
        descriptor = str(primary)
        primary['virtual'] = bool(re.search(
            r'parsec|virtual|indirect|dummy|spacedesk|iddsample|remote display',
            descriptor, re.I)) or any(x['technology'] in (15, 16, 17, 0xffffffff)
                                     for x in outputs)
        if not outputs:
            raise Blocked('Cannot identify an active primary monitor; refusing launch.')
        return primary
    raise Blocked('Display topology changed during inspection; try status again.')


def interactive_desktop():
    def name(handle):
        size = w.DWORD()
        value = c.create_unicode_buffer(256)
        if not u.GetUserObjectInformationW(handle, 2, value, c.sizeof(value), c.byref(size)):
            return ''
        return value.value
    desktop = u.OpenInputDesktop(0, False, 1)
    if not desktop:
        return False
    try:
        return name(desktop).lower() == 'default' and name(
            u.GetProcessWindowStation()).lower() == 'winsta0'
    finally:
        u.CloseDesktop(desktop)


class Startup(c.Structure):
    _fields_ = [('cb', w.DWORD), ('reserved', w.LPWSTR), ('desktop', w.LPWSTR),
                ('title', w.LPWSTR), ('x', w.DWORD), ('y', w.DWORD),
                ('width', w.DWORD), ('height', w.DWORD), ('cx', w.DWORD),
                ('cy', w.DWORD), ('fill', w.DWORD), ('flags', w.DWORD),
                ('show', w.WORD), ('reserved_size', w.WORD), ('reserved2', PTR),
                ('stdin', w.HANDLE), ('stdout', w.HANDLE), ('stderr', w.HANDLE)]


class ProcessInfo(c.Structure):
    _fields_ = [('process', w.HANDLE), ('thread', w.HANDLE),
                ('pid', w.DWORD), ('tid', w.DWORD)]


api(k, 'CreateProcessW', w.BOOL, w.LPCWSTR, w.LPWSTR, PTR, PTR, w.BOOL,
    w.DWORD, PTR, w.LPCWSTR, c.POINTER(Startup), c.POINTER(ProcessInfo))


class OwnedClient:
    def __init__(self, folder):
        self.folder = Path(folder).resolve()
        self.job = None
        self.handles = {}
        self.launcher_pid = None
        self.focus_authorized = False
        self.focus_handoff = None
        self.force_focus = False
        self.lock_file = None

    def start(self):
        if self.pids():
            raise Blocked('The client started by this server is already running.')
        self.close_handles()
        self.lock_file = (self.folder.parent / 'logs' / 'client-owner.lock').open('a+b')
        try:
            self.lock_file.seek(0)
            if not self.lock_file.read(1):
                self.lock_file.write(b'0')
                self.lock_file.flush()
            self.lock_file.seek(0)
            msvcrt.locking(self.lock_file.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            self.lock_file.close()
            self.lock_file = None
            raise Blocked('Another client-driver already owns the copied client.') from None
        self.job = k.CreateJobObjectW(None, None)
        if not self.job:
            self.close_handles()
            raise Blocked('Cannot create an owned process job.')
        limits = c.create_string_buffer(144 if c.sizeof(SIZE) == 8 else 112)
        struct.pack_into('<I', limits, 16, 0x2000)  # KILL_ON_JOB_CLOSE, owned job only.
        if not k.SetInformationJobObject(self.job, 9, limits, len(limits)):
            self.close_handles()
            raise Blocked('Cannot enable cleanup for the owned client job.')
        start = Startup()
        start.cb = c.sizeof(start)
        info = ProcessInfo()
        exe = str(self.folder / 'SlumsMS.exe')
        if not k.CreateProcessW(exe, c.create_unicode_buffer('"' + exe + '"'),
                                None, None, False, 4, None, str(self.folder),
                                c.byref(start), c.byref(info)):
            error_code = c.get_last_error()
            self.close_handles()
            if error_code == 740:
                raise Blocked('SlumsMS.exe requires administrator privileges. Start this server manually from an elevated owner terminal; automatic elevation is refused.')
            raise Blocked('Could not launch the copied SlumsMS.exe (Windows error ' + str(error_code) + ').')
        try:
            # Assign before resuming: the launcher cannot create an unowned child.
            if not k.AssignProcessToJobObject(self.job, info.process):
                k.TerminateProcess(info.process, 1)
                k.CloseHandle(info.process)
                self.close_handles()
                raise Blocked('Could not isolate the copied client process.')
            self.handles[info.pid] = info.process
            self.launcher_pid = info.pid
            if k.ResumeThread(info.thread) == 0xffffffff:
                self.stop()
                raise Blocked('Could not resume the copied client process.')
        finally:
            k.CloseHandle(info.thread)

    def pids(self):
        if not self.job:
            return []
        data = c.create_string_buffer(8 + c.sizeof(SIZE) * 256)
        if not k.QueryInformationJobObject(self.job, 3, data, len(data), None):
            raise Blocked('Cannot verify owned client PIDs.')
        count = struct.unpack_from('<I', data.raw, 4)[0]
        if count > 256:
            raise Blocked('Unexpected owned process count.')
        fmt = '<Q' if c.sizeof(SIZE) == 8 else '<I'
        return [struct.unpack_from(fmt, data.raw, 8 + c.sizeof(SIZE) * i)[0]
                for i in range(count)]

    def handle(self, pid):
        if pid not in self.pids():
            raise Blocked('PID is not owned by this server.')
        if pid not in self.handles:
            handle = k.OpenProcess(0x1000 | 1 | 0x100000, False, pid)
            if not handle:
                raise Blocked('Cannot open owned client PID.')
            self.handles[pid] = handle
        return self.handles[pid]

    def image_path(self, pid):
        name = c.create_unicode_buffer(32768)
        size = w.DWORD(len(name))
        if not k.QueryFullProcessImageNameW(self.handle(pid), 0, name, c.byref(size)):
            raise Blocked('Cannot verify owned process executable.')
        path = Path(name.value).resolve()
        if path.parent != self.folder or path.name.lower() not in ('slumsms.exe', 'maplestory.exe'):
            raise Blocked('Owned process executable is outside the allowed client copy.')
        return str(path)

    def window(self):
        owned = set(self.pids())
        found = []
        callback = c.WINFUNCTYPE(w.BOOL, w.HWND, c.c_ssize_t)

        def visit(hwnd, _):
            pid = w.DWORD()
            u.GetWindowThreadProcessId(hwnd, c.byref(pid))
            if pid.value in owned and u.IsWindowVisible(hwnd):
                rect = w.RECT()
                u.GetClientRect(hwnd, c.byref(rect))
                if rect.right >= 640 and rect.bottom >= 480:
                    title = c.create_unicode_buffer(256)
                    u.GetWindowTextW(hwnd, title, len(title))
                    if re.search(r'maple|slums', title.value, re.I):
                        found.append((hwnd, pid.value, title.value))
            return True

        u.EnumWindows(callback(visit), 0)
        if len(found) != 1:
            raise Blocked('Expected exactly one owned game window; found ' + str(len(found)) + '.')
        self.image_path(found[0][1])
        return found[0]

    def window_info(self):
        """Read-only facts about the owned game window: frame style, rectangles, DPI awareness (2026-10-29: the
        proof for [graphics] borderless / dpiaware, which slums.dll does not log)."""
        hwnd, _, _ = self.window()
        style = u.GetWindowLongW(hwnd, -16) & 0xFFFFFFFF
        wr, cr = w.RECT(), w.RECT()
        u.GetWindowRect(hwnd, c.byref(wr))
        u.GetClientRect(hwnd, c.byref(cr))
        origin = w.POINT(0, 0)
        u.ClientToScreen(hwnd, c.byref(origin))
        try:
            awareness = u.GetAwarenessFromDpiAwarenessContext(u.GetWindowDpiAwarenessContext(hwnd))
        except (AttributeError, OSError):
            awareness = None
        return {'style': '%08X' % style, 'popup': bool(style & 0x80000000), 'caption': (style & 0x00C00000) == 0x00C00000,
                'window_rect': [wr.left, wr.top, wr.right, wr.bottom],
                'client_origin': [origin.x, origin.y], 'client_size': [cr.right, cr.bottom],
                'dpi_awareness': {0: 'unaware', 1: 'system', 2: 'per-monitor'}.get(awareness, awareness)}

    def foreground(self):
        hwnd, pid, _ = self.window()
        if u.GetForegroundWindow() != hwnd and self.focus_authorized:
            if getattr(self, 'force_focus', False):
                # The owner chose "skip the idle wait" for this run (local force): bring our own game window to the
                # front without the controller handoff. A tagged Alt tap lets Windows accept SetForegroundWindow.
                if u.IsIconic(hwnd):
                    u.ShowWindow(hwnd, 9)
                u.keybd_event(0x12, 0, 0, SELF_INPUT_TAG)
                u.keybd_event(0x12, 0, 2, SELF_INPUT_TAG)
                u.BringWindowToTop(hwnd)
                u.SetForegroundWindow(hwnd)
                time.sleep(0.2)
            else:
                if self.focus_handoff:
                    self.focus_handoff()
                if u.IsIconic(hwnd):
                    u.ShowWindow(hwnd, 9)
                if not u.SetForegroundWindow(hwnd):
                    raise Blocked('Windows refused authorized focus of the owned game window.')
        if u.GetForegroundWindow() != hwnd:
            raise Blocked('Owned game window is not foreground; refusing to steal focus. Foreground: '
                          + describe_window(u.GetForegroundWindow(), set(self.pids())))
        if u.IsIconic(hwnd):
            raise Blocked('Owned game window is minimized.')
        return hwnd

    def stop(self):
        # Exact job membership plus retained process handles prevents PID reuse.
        pids = self.pids()
        for pid in pids:
            self.image_path(pid)
        for pid in reversed(pids):
            handle = self.handle(pid)
            code = w.DWORD()
            if k.GetExitCodeProcess(handle, c.byref(code)) and code.value == 259:
                if not k.TerminateProcess(handle, 0):
                    raise Blocked('Could not stop an owned client PID.')
        deadline = time.monotonic() + 5
        while self.pids() and time.monotonic() < deadline:
            time.sleep(0.05)
        if self.pids():
            raise Blocked('Owned client did not exit within five seconds.')
        self.close_handles()
        return pids

    def close_handles(self):
        for handle in self.handles.values():
            k.CloseHandle(handle)
        self.handles.clear()
        if self.job:
            k.CloseHandle(self.job)
            self.job = None
        if self.lock_file:
            self.lock_file.close()
            self.lock_file = None


def window_hung(hwnd):
    # Read-only: Windows' own "not responding" test (no input for 5 s). Never sends anything to the window.
    return bool(u.IsHungAppWindow(hwnd))


def describe_window(hwnd, owned):
    """'class=<c> title=<t> pid=<p> ours=<bool>' for a refusal message (b044 diagnosis). No input, read only."""
    if not hwnd:
        return 'none (locked desktop or a secure screen?)'
    pid = w.DWORD()
    u.GetWindowThreadProcessId(hwnd, c.byref(pid))
    name = c.create_unicode_buffer(128)
    u.GetClassNameW(hwnd, name, len(name))
    title = c.create_unicode_buffer(64)
    u.GetWindowTextW(hwnd, title, len(title))
    return 'class=%s title=%s pid=%d ours=%s' % (name.value, title.value[:40], pid.value, pid.value in owned)


def capture(hwnd):
    rect = w.RECT()
    if not u.GetClientRect(hwnd, c.byref(rect)):
        raise Blocked('Cannot measure the game window.')
    width, height = rect.right, rect.bottom
    if not (640 <= width <= 4096 and 480 <= height <= 2160):
        raise Blocked('Unsupported game window size.')
    dc = u.GetDC(hwnd)
    memory = g.CreateCompatibleDC(dc)
    bitmap = g.CreateCompatibleBitmap(dc, width, height)
    previous = g.SelectObject(memory, bitmap)
    try:
        # Ask this owned window to render off-screen. Never read screen pixels:
        # transparent Discord/CUA overlays must not enter saved game screenshots.
        if not u.PrintWindow(hwnd, memory, 3):  # PW_CLIENTONLY | PW_RENDERFULLCONTENT
            raise Blocked('The owned game refused off-screen window capture.')
        g.SelectObject(memory, previous)
        header = struct.pack('<IiiHHIIiiII', 40, width, -height, 1, 32, 0,
                             width * height * 4, 0, 0, 0, 0)
        info = c.create_string_buffer(header + bytes(16))
        pixels = c.create_string_buffer(width * height * 4)
        if g.GetDIBits(dc, bitmap, 0, height, pixels, info, 0) != height:
            raise Blocked('Could not read captured game pixels.')
        return Image.frombytes('RGB', (width, height), pixels.raw, 'raw', 'BGRX')
    finally:
        g.SelectObject(memory, previous)
        g.DeleteObject(bitmap)
        g.DeleteDC(memory)
        u.ReleaseDC(hwnd, dc)


class MouseInput(c.Structure):
    _fields_ = [('dx', w.LONG), ('dy', w.LONG), ('data', w.DWORD),
                ('flags', w.DWORD), ('time', w.DWORD), ('extra', SIZE)]


class KeyboardInput(c.Structure):
    _fields_ = [('vk', w.WORD), ('scan', w.WORD), ('flags', w.DWORD),
                ('time', w.DWORD), ('extra', SIZE)]


class InputUnion(c.Union):
    _fields_ = [('mouse', MouseInput), ('keyboard', KeyboardInput)]


class Input(c.Structure):
    _anonymous_ = ('data',)
    _fields_ = [('type', w.DWORD), ('data', InputUnion)]


api(u, 'SendInput', w.UINT, w.UINT, c.POINTER(Input), c.c_int)


class Sender:
    def __init__(self, client, guard):
        self.client, self.guard = client, guard

    def send(self, events):
        self.guard()
        self.client.foreground()
        if isinstance(events, Input):
            events = [events]
        batch = (Input * len(events))(*events)
        if u.SendInput(len(events), batch, c.sizeof(Input)) != len(events):
            raise Blocked('Windows refused input to the owned game window.')

    def key(self, vk, down=None, extended=False):
        # Arrows, Home/End, PageUp/PageDown, Insert and Delete are extended keys; without the flag their scan
        # codes read as the numeric keypad.
        scan = u.MapVirtualKeyW(vk, 0)
        flags = 8 | (1 if extended else 0)
        press = Input(type=1)
        press.keyboard = KeyboardInput(0, scan, flags, 0, SELF_INPUT_TAG)
        release = Input(type=1)
        release.keyboard = KeyboardInput(0, scan, flags | 2, 0, SELF_INPUT_TAG)
        if down is False:
            self.release(release)
            return
        self.send(press)
        if down is None:
            try:
                time.sleep(0.06)  # DirectInput polls state; an atomic tap can be missed.
            finally:
                self.release(release)

    def release(self, event):
        # Always release a key/button we injected, even if the owner takes over.
        # This changes no focus and prevents leaving a synthetic key held down.
        if u.SendInput(1, c.byref(event), c.sizeof(Input)) != 1:
            raise Blocked('Windows refused release of an injected key or button.')

    def text(self, value):
        for char in value:
            key = u.VkKeyScanW(ord(char))
            if key == -1 or key & 0x600:
                raise Blocked('Text contains a character unsupported by the current keyboard layout.')
            shift = bool(key & 0x100)
            if shift:
                self.key(0x10, True)
            try:
                self.key(key & 255)
            finally:
                if shift:
                    self.key(0x10, False)
            time.sleep(0.02)

    def move(self, x, y):
        """Move the cursor to (x, y) in the 800x600 client space of the owned game window."""
        self.guard()
        hwnd = self.client.foreground()
        rect = w.RECT()
        u.GetClientRect(hwnd, c.byref(rect))
        point = w.POINT(round(x * rect.right / 800), round(y * rect.bottom / 600))
        u.ClientToScreen(hwnd, c.byref(point))
        # The cursor moves only over our foreground client, never via focus APIs.
        left, top = u.GetSystemMetrics(76), u.GetSystemMetrics(77)
        width, height = u.GetSystemMetrics(78), u.GetSystemMetrics(79)
        event = Input(type=0)
        event.mouse = MouseInput(round((point.x-left)*65535/(width-1)),
                                 round((point.y-top)*65535/(height-1)),
                                 0, 0x8000 | 0x4000 | 1, 0, SELF_INPUT_TAG)
        self.send(event)

    @staticmethod
    def buttons(right=False):
        down = Input(type=0)
        down.mouse = MouseInput(0, 0, 0, 8 if right else 2, 0, SELF_INPUT_TAG)
        release = Input(type=0)
        release.mouse = MouseInput(0, 0, 0, 0x10 if right else 4, 0, SELF_INPUT_TAG)
        return down, release

    def click(self, x, y, right=False, double=False):
        self.move(x, y)
        down, release = self.buttons(right)
        for _ in range(2 if double else 1):
            self.send(down)
            try:
                time.sleep(0.08)
            finally:
                self.release(release)
            time.sleep(0.05 if double else 0.1)

    def drag(self, x1, y1, x2, y2, steps=12):
        """Left-button drag in client space; the button is always released, even if the owner takes over."""
        self.move(x1, y1)
        time.sleep(0.1)
        down, release = self.buttons()
        self.send(down)
        try:
            time.sleep(0.15)
            for i in range(1, steps + 1):
                self.move(x1 + (x2 - x1) * i / steps, y1 + (y2 - y1) * i / steps)
                time.sleep(0.05)
            time.sleep(0.15)
        finally:
            self.release(release)
        time.sleep(0.2)
