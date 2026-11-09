"""tools/ops/test-instance --dry-run (and the real path against a fake ssh): what setup/refresh-db would run on the host.
Nothing here reaches a host: ssh and scp are fakes on PATH that log their arguments."""
import os
import re
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
TOOL = os.path.join(HERE, "test-instance")
A_UNIT = "slums-persistence-test"
A_DB = "mapleroot-integration-db"
A_FOLDER = "/opt/mapleroot-dev/persistence-test"
PREFIX = re.compile(r"^\[host root@[^\]]+\] ")


class TestInstanceTool(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bin = os.path.join(self.tmp.name, "bin")
        os.mkdir(self.bin)
        self.log = os.path.join(self.tmp.name, "calls.log")
        for tool in ("ssh", "scp"):
            p = os.path.join(self.bin, tool)
            with open(p, "w") as f:
                f.write('#!/bin/bash\nfor a in "$@"; do :; done\nprintf "%%s\\n" "${@: -1}" >> "%s"\nexit 0\n' % self.log)
            os.chmod(p, os.stat(p).st_mode | stat.S_IXUSR)

    def tearDown(self):
        self.tmp.cleanup()

    def run_tool(self, *args):
        env = dict(os.environ, PATH=self.bin + os.pathsep + os.environ["PATH"])
        env.pop("SLUMS_TEST_HOST", None)
        r = subprocess.run([TOOL, *args], env=env, capture_output=True, text=True, timeout=60)
        calls = open(self.log).read().splitlines() if os.path.exists(self.log) else []
        return r, r.stdout + r.stderr, calls

    def host_lines(self, out):
        return [PREFIX.sub("", l) for l in out.splitlines() if PREFIX.match(l)]

    def test_setup_b_dry_run_prints_the_steps_and_runs_nothing(self):
        r, out, calls = self.run_tool("setup", "B", "--dry-run")
        self.assertEqual(0, r.returncode, out)
        self.assertEqual([], calls, "dry run must not ssh or scp")
        text = "\n".join(self.host_lines(out))
        for needle in ("mysql:8.4.9", "--single-transaction", "-p 127.0.0.1:3308:3306", "cp -al %s/Server/wz" % A_FOLDER,
                       "/dev/urandom", "chmod 600", "BOT_ROSTER_PEAK_ONLINE", ":3308/", "slums-test-b-db",
                       "/opt/mapleroot-dev/test-b"):
            self.assertIn(needle, text)
        self.assertIn("\\1 150|", text)

    def test_setup_c_uses_3310_and_population_50_and_never_3309(self):
        r, out, calls = self.run_tool("setup", "C", "--dry-run")
        self.assertEqual(0, r.returncode, out)
        text = "\n".join(self.host_lines(out))
        self.assertIn("127.0.0.1:3310:3306", text)
        self.assertIn("slums-test-c-db", text)
        self.assertIn("\\1 50|", text)
        self.assertIn("BOT_ROSTER_PEAK_ONLINE: 50$", text)
        self.assertNotIn("3309", out)

    def test_a_is_only_read_never_stopped_or_written(self):
        for inst in "BC":
            r, out, _ = self.run_tool("setup", inst, "--dry-run")
            r2, out2, _ = self.run_tool("refresh-db", inst, "--dry-run")
            for o in (out, out2):
                for line in self.host_lines(o):
                    self.assertNotIn(A_UNIT + " ", line + " ", "A's unit named: " + line)
                    if A_DB in line:
                        # only the read-only dump may name A's container
                        self.assertRegex(line, r"^docker exec %s sh -c 'mysqldump [^']*--single-transaction" % A_DB)
                        self.assertNotRegex(line.split("|")[0], r"mysql -u|DROP|CREATE|INSERT")
                        self.assertNotIn("systemctl", line)
                    if A_FOLDER in line:
                        self.assertTrue(
                            re.match(r"^\(cd %s/Server && tar cf - " % A_FOLDER, line)
                            or line.startswith("cp -al %s/Server/wz " % A_FOLDER)
                            or line.startswith("[ -d %s/Server ]" % A_FOLDER),
                            "A's folder used for more than reading: " + line)
                    self.assertNotRegex(line, r"docker (stop|rm|restart|kill|exec -i %s)" % A_DB)

    def test_secrets_are_made_on_the_host_0600_and_never_printed(self):
        r, out, _ = self.run_tool("setup", "B", "--dry-run")
        lines = self.host_lines(out)
        made = [l for l in lines if "/dev/urandom" in l]
        self.assertEqual(3, len(made))  # inspect, ops, db.env
        for l in made:
            self.assertTrue(l.startswith("umask 077;"), l)
        self.assertTrue(any("chmod 600" in l and "db.env" in l for l in lines))
        self.assertNotRegex(out, r"[0-9a-f]{32,}")          # no secret-looking value in the output
        self.assertNotIn("cat /etc/slums-test-b", out)       # secrets are not read back out

    def test_refresh_db_stops_only_its_own_unit_and_reloads_from_a(self):
        r, out, calls = self.run_tool("refresh-db", "B", "--dry-run")
        self.assertEqual(0, r.returncode, out)
        self.assertEqual([], calls)
        text = "\n".join(self.host_lines(out))
        self.assertIn("systemctl stop slums-test-b", text)
        self.assertNotIn("slums-test-c", text)
        self.assertIn("DROP DATABASE IF EXISTS mapleroot_persist", text)
        self.assertIn("--single-transaction", text)
        self.assertIn("bash /opt/mapleroot-dev/test-b/start-test.sh", text)
        order = [text.index(k) for k in ("systemctl stop slums-test-b", "DROP DATABASE", "mysqldump", "start-test.sh")]
        self.assertEqual(sorted(order), order)

    def test_a_and_unknown_instances_and_commands_are_refused(self):
        for args in (("setup", "A"), ("refresh-db", "A"), ("setup", "D"), ("setup", ""), ("bogus", "B"), ("setup",)):
            r, out, calls = self.run_tool(*args, "--dry-run") if len(args) > 1 else self.run_tool(*args)
            self.assertNotEqual(0, r.returncode, args)
            self.assertEqual([], calls, args)

    def test_the_real_path_runs_exactly_the_commands_the_dry_run_prints(self):
        for cmd in ("setup", "refresh-db"):
            os.path.exists(self.log) and os.remove(self.log)
            _, dry, _ = self.run_tool(cmd, "C", "--dry-run")
            printed = self.host_lines(dry)
            os.path.exists(self.log) and os.remove(self.log)
            r, out, calls = self.run_tool(cmd, "C")      # fake ssh: nothing real happens
            self.assertEqual(0, r.returncode, out)
            host_calls = [c for c in calls if not c.startswith("root@") and ":" not in c.split(" ")[0]]
            self.assertEqual(printed, host_calls)


if __name__ == "__main__":
    unittest.main()
