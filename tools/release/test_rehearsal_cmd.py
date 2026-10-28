"""Offline: the rehearsal copy's ExecStart moves every fixed live port/dir to the rehearsal.

python3 -m unittest discover -s tools/release -p 'test_*.py'
"""
from pathlib import Path
import subprocess
import unittest

LIB = Path(__file__).resolve().parent / 'lib.sh'
LIVE = ('/usr/bin/java -Xmx4g -Dmapleroot.login-port=8485 -Dmapleroot.channel-base-port=7585 -Dslums.attackcapture=on '
        '-Dslums.market.export=true -Dslums.board=true -Dslums.auth.port=8186 -Dslums.auth.secretFile=/etc/slums-auth/secret '
        '-Dslums.auth.revoked=/var/lib/slums-login/revoked.txt -Dsolomapling.dashboard=true '
        '-Dsolomapling.dashboard.dir=/srv/slums-dashboard/data -Dslums.board.outbox=/var/lib/slums-market/outbox -jar Cosmic.jar')


def rewrite(cmd=LIVE):
    return subprocess.run(['bash', '-c', f'. "{LIB}"; rehearsal_cmd "$1" 8499 8585 /opt/x/rh', '_', cmd],
                          capture_output=True, text=True, check=True).stdout


class RehearsalCmd(unittest.TestCase):
    def test_no_live_port_or_dir_survives(self):
        out = rewrite()
        for live in ('8186', '8485', '7585', '/srv/slums-dashboard', '/var/lib/slums-market', '/var/lib/slums-login'):
            self.assertNotIn(live, out)
        self.assertIn('-Dslums.auth.port=9499', out)
        self.assertIn('-Dsolomapling.dashboard.dir=/opt/x/rh/scratch/dashboard', out)
        self.assertIn('-Dslums.board.outbox=/opt/x/rh/scratch/board/outbox', out)
        self.assertIn('-Dmapleroot.login-port=8499', out)
        self.assertNotIn('attackcapture', out)
        self.assertTrue(out.endswith(' -jar Cosmic.jar'))

    def test_each_flag_appears_once(self):
        out = rewrite()
        self.assertEqual(out.count('-Dslums.auth.port='), 1)
        self.assertEqual(out.count('-Dsolomapling.dashboard.dir='), 1)

    def test_unit_without_those_flags_still_gets_scratch_paths(self):
        out = rewrite('java -Dmapleroot.login-port=1 -Dmapleroot.channel-base-port=2 -jar a.jar')
        self.assertIn('-Dslums.auth.port=9499', out)
        self.assertIn('-Dslums.site.dir=/opt/x/rh/scratch/site', out)


if __name__ == '__main__':
    unittest.main()
