"""b377: deploy.sh's in-game "restarting in 1 minute" notice (the notice() helper in templates/deploy.body.sh).

The helper is cut out of the template as it ships and run in bash against a fake ops endpoint on 127.0.0.1."""
import json
import os
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest

HERE = Path(__file__).resolve().parent
TEMPLATE = (HERE / 'templates/deploy.body.sh').read_text()
HELPERS = TEMPLATE[TEMPLATE.index('ops_flag() {'):TEMPLATE.index('\nnow() {')]
SECRET = 'ops-secret-for-tests-0123456789'


class FakeOps(BaseHTTPRequestHandler):
    seen = []

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get('Content-Length', 0))).decode()
        FakeOps.seen.append((self.path, self.headers.get('X-Slums-Ops'), body))
        out = json.dumps({'ok': True, 'channels': 5}).encode()
        self.send_response(200)
        self.send_header('Content-Length', str(len(out)))
        self.end_headers()
        self.wfile.write(out)

    def log_message(self, *args):
        pass


class OpsNoticeTest(unittest.TestCase):
    def setUp(self):
        FakeOps.seen = []
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.dir = Path(self.tmp.name)
        self.server = HTTPServer(('127.0.0.1', 0), FakeOps)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.addCleanup(self.server.shutdown)
        self.secret = self.dir / 'ops-secret'
        self.secret.write_text(SECRET + '\n')

    def unit(self, port):
        f = self.dir / 'slumsms.service'
        f.write_text('[Service]\nExecStart=/usr/bin/java -Dslums.exped.door=true -Dslums.ops.port=%d '
                     '-Dslums.ops.secretFile=%s -jar target/Cosmic.jar\n' % (port, self.secret))
        return f

    def run_notice(self, unit_file, seconds='60', text='The server restarts in 1 minute for an update.'):
        script = 'set -euo pipefail\n' + HELPERS + '\nnotice %s && echo SENT || echo NOT-SENT\necho carried-on\n' % (
            "'" + text.replace("'", "'\\''") + "'")
        return subprocess.run(['bash', '-c', script], text=True, capture_output=True,
                              env={**os.environ, 'UNIT_FILE': str(unit_file), 'NOTICE_SECONDS': seconds})

    def test_the_notice_reaches_the_endpoint_with_the_secret_from_the_unit(self):
        r = self.run_notice(self.unit(self.server.server_port))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('SENT', r.stdout)
        self.assertEqual(FakeOps.seen, [('/notice', SECRET, 'The server restarts in 1 minute for an update.')])
        self.assertNotIn(SECRET, r.stdout + r.stderr, 'the secret is never printed')

    def test_the_secret_goes_on_stdin_never_on_a_command_line(self):
        self.assertIn('curl -sS -H @-', HELPERS)
        self.assertNotIn('-H "X-Slums-Ops', HELPERS)

    def test_a_dead_endpoint_never_stops_the_deploy(self):
        port = self.server.server_port
        self.server.shutdown()
        self.server.server_close()
        r = self.run_notice(self.unit(port))
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('NOT-SENT', r.stdout)
        self.assertIn('carried-on', r.stdout)
        self.assertIn('WARN: in-game notice not sent', r.stdout)

    def test_no_ops_flags_on_the_unit_skips_the_notice(self):
        f = self.dir / 'plain.service'
        f.write_text('[Service]\nExecStart=/usr/bin/java -jar target/Cosmic.jar\n')
        r = self.run_notice(f)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('in-game notice skipped', r.stdout)
        self.assertIn('NOT-SENT', r.stdout)
        self.assertEqual(FakeOps.seen, [])

    def test_notice_seconds_zero_sends_nothing(self):
        r = self.run_notice(self.unit(self.server.server_port), seconds='0')
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn('NOT-SENT', r.stdout)
        self.assertEqual(FakeOps.seen, [])

    def test_both_rehearsals_switch_the_notice_off(self):
        self.assertIn('export NOTICE_SECONDS=0', (HERE / 'rehearse-host.sh').read_text())
        self.assertIn("printf 'NOTICE_SECONDS=0\\n'", (HERE / 'rehearse-release-flow-host.sh').read_text())


if __name__ == '__main__':
    unittest.main()


class FiveMinuteWarningTest(unittest.TestCase):
    """Owner 9 Nov 2026: players get the first notice 5 minutes before the stop, a reminder at 1 minute, then 10 s."""

    def test_default_lead_is_five_minutes_with_a_one_minute_reminder(self):
        body = (HERE / 'templates' / 'deploy.body.sh').read_text()
        self.assertIn('NOTICE_SECONDS=${NOTICE_SECONDS:-300}', body)
        self.assertIn('restarts in 5 minutes', body)
        first = body.index('if notice "$NOTICE_TEXT"; then')
        mid = body.index('notice "$NOTICE_MID"', first)
        last = body.index('notice "$NOTICE_LAST"', first)
        self.assertLess(mid, last, 'the 1-minute reminder comes before the last notice')
        self.assertIn('sleep $((NOTICE_SECONDS - 60))', body[first:last])

