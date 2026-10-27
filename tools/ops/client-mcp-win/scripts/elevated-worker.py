"""Owner-authorized local development worker. File queue only, no listeners.

Run with -B. Requests/responses stay in client-mcp/logs. No credential values
are accepted. Production hosts can run server.py directly with appropriate rights.

Long-running since 2026-10-27 (src/worker.py): lives `lifetime_hours` (default 12) after the last request, or
until stopped with 0; queues requests while the owner uses the PC instead of refusing them; restarts and logs the
test client back in after a crash, hang or disconnect; writes logs/worker-heartbeat.json. Settings are in
worker-config.json next to local-config.json. Exit codes: 0 stopped normally, 1 could not start, 2 crashed (the
Start-QA.cmd loop starts it again).
"""
import ctypes
import json
import os
from pathlib import Path
import sys

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'src'))
INBOX = ROOT / 'logs' / 'worker-inbox.jsonl'
OUTBOX = ROOT / 'logs' / 'worker-outbox.jsonl'
HEARTBEAT = ROOT / 'logs' / 'worker-heartbeat.json'
CONFIG = ROOT / 'worker-config.json'


def emit(value):
    with OUTBOX.open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(value) + '\n')


def main():
    try:
        from worker import Worker, load_config
        config = load_config(CONFIG)
        from calibrate import driver, action, Refusal

        class SafeError:
            refusal = Refusal

            @staticmethod
            def text(error):
                safe = str(error) if isinstance(error, (Refusal, driver.backend.Blocked)) else type(error).__name__
                return driver.secrets.redact(safe)

        INBOX.touch()
        previous = {}
        try:
            previous = json.loads(HEARTBEAT.read_text(encoding='utf-8'))
        except (OSError, ValueError):
            pass
        worker = Worker(driver, action, config, emit, INBOX, HEARTBEAT, SafeError)
        if str(previous.get('stopped') or '').startswith('crashed') and isinstance(previous.get('session'), dict):
            # Restarted by Start-QA after a crash: the client died with the old worker's job, so log it back in.
            worker.desired = previous['session']
        emit({'event': 'ready', 'pid': os.getpid(),
              'admin': bool(ctypes.windll.shell32.IsUserAnAdmin()),
              'idle_seconds': driver.activity.idle_seconds(),
              'interactive': driver.backend.interactive_desktop(),
              'display': driver.backend.primary_display(),
              'config': config})
    except Exception as error:
        detail = str(error) if type(error).__name__ in ('ConfigError', 'Blocked', 'Refusal') else ''
        emit({'event': 'initialization_failed', 'error_type': type(error).__name__, 'detail': detail})
        print('Worker could not start:', type(error).__name__, detail)
        return 1
    try:
        worker.run()
        print('Worker stopped:', worker.stopped)
        return 0
    except KeyboardInterrupt:
        worker.stopped = 'closed by the owner'
        worker.heartbeat()
        return 0
    except Exception as error:
        worker.stopped = 'crashed: ' + type(error).__name__
        worker.heartbeat()
        emit({'event': 'crashed', 'error_type': type(error).__name__})
        print('Worker crashed:', type(error).__name__)
        return 2


if __name__ == '__main__':
    sys.exit(main())
