"""Cooperative foreground-permission handoff. No focus-lock changes or input."""
import ctypes as c
from ctypes import wintypes as w
import json
import os
from pathlib import Path
import time
import uuid


class FocusError(Exception):
    pass


def write_json(path, value):
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    temporary.replace(path)


def process_identity(pid):
    import windows
    handle = windows.k.OpenProcess(0x1000, False, pid)
    if not handle:
        raise FocusError('Cannot verify the registered driver process.')
    try:
        name = c.create_unicode_buffer(32768)
        size = w.DWORD(len(name))
        if not windows.k.QueryFullProcessImageNameW(handle, 0, name, c.byref(size)):
            raise FocusError('Cannot identify the registered driver executable.')
        fn = windows.api(windows.k, 'GetProcessTimes', w.BOOL, w.HANDLE,
                         c.POINTER(w.FILETIME), c.POINTER(w.FILETIME),
                         c.POINTER(w.FILETIME), c.POINTER(w.FILETIME))
        created, exited, kernel, user = (w.FILETIME() for _ in range(4))
        if not fn(handle, c.byref(created), c.byref(exited), c.byref(kernel), c.byref(user)):
            raise FocusError('Cannot verify driver creation time.')
        return {'pid': pid, 'created': created.dwHighDateTime << 32 | created.dwLowDateTime,
                'image': str(Path(name.value).resolve()).casefold()}
    finally:
        windows.k.CloseHandle(handle)


def grant(request, registration, foreground_pid, controller_pid, identify, allow):
    """A controller may grant only its registered, still-identical driver PID."""
    if (type(request.get('pid')) is not int or request['pid'] <= 0
            or request.get('pid') != registration.get('identity', {}).get('pid')
            or request.get('session') != registration.get('session')):
        raise FocusError('Focus request is not from the registered driver.')
    if time.monotonic() > request.get('expires', 0):
        raise FocusError('Focus request expired.')
    if foreground_pid != controller_pid:
        raise FocusError('Interactive controller is not foreground; no permission was requested.')
    if identify(request['pid']) != registration['identity']:
        raise FocusError('Registered driver process identity changed.')
    if not allow(request['pid']):
        raise FocusError('Windows refused the foreground-permission handoff.')
    return {'granted': True}


class Handoff:
    def __init__(self, root):
        self.folder = root / 'logs' / 'focus'
        self.folder.mkdir(parents=True, exist_ok=True)
        self.registration = {'identity': process_identity(os.getpid()),
                             'session': uuid.uuid4().hex}
        write_json(self.folder / 'driver-registration.json', self.registration)

    def request(self):
        request_id = uuid.uuid4().hex
        request = {'pid': os.getpid(), 'session': self.registration['session'],
                   'expires': time.monotonic() + 5}
        response = self.folder / (request_id + '.response.json')
        write_json(self.folder / (request_id + '.request.json'), request)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            if response.exists():
                result = json.loads(response.read_text(encoding='utf-8'))
                if not result.get('granted'):
                    raise FocusError(result.get('error', 'Foreground permission was not granted.'))
                return
            time.sleep(0.05)
        raise FocusError('Foreground controller did not respond. Start focus-controller.py in the interactive desktop.')
