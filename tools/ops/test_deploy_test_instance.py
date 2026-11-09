"""tools/ops/deploy-test --instance A|B|C, dry-run only. ssh and scp are fakes on PATH that log their arguments and
return nothing, so no host is ever reached; the logged commands prove which unit, folder and DB each instance names."""
import os
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
DEPLOY = os.path.join(HERE, "deploy-test")
REMOTE = os.path.join(HERE, "deploy-test-remote.sh")

A_NAMES = ("slums-persistence-test", "mapleroot-integration-db", "/opt/mapleroot-dev/persistence-test")
B_NAMES = ("slums-test-b", "slums-test-b-db", "/opt/mapleroot-dev/test-b")
C_NAMES = ("slums-test-c", "slums-test-c-db", "/opt/mapleroot-dev/test-c")


class DeployTestInstance(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bin = os.path.join(self.tmp.name, "bin")
        os.mkdir(self.bin)
        self.log = os.path.join(self.tmp.name, "ssh.log")
        for tool in ("ssh", "scp"):
            p = os.path.join(self.bin, tool)
            with open(p, "w") as f:
                f.write('#!/bin/bash\necho "%s $*" >> "%s"\nexit 0\n' % (tool, self.log))
            os.chmod(p, os.stat(p).st_mode | stat.S_IXUSR)
        self.head = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()

    def tearDown(self):
        self.tmp.cleanup()

    def run_deploy(self, *args, pin=None):
        env = dict(os.environ, PATH=self.bin + os.pathsep + os.environ["PATH"])
        env.pop("SLUMS_TEST_HOST", None)
        if pin is not None:
            pf = os.path.join(self.tmp.name, "pin")
            with open(pf, "w") as f:
                f.write(pin + "\n")
            env["DEPLOY_TEST_PIN_FILE"] = pf
        r = subprocess.run([DEPLOY, "--dry-run", "--since", "HEAD~1", *args], cwd=ROOT, env=env,
                           capture_output=True, text=True, timeout=120)
        calls = open(self.log).read() if os.path.exists(self.log) else ""
        return r, r.stdout + r.stderr, calls

    def assert_names(self, text, mine, others):
        for n in mine:
            self.assertIn(n, text)
        for group in others:
            for n in group:
                self.assertNotIn(n, text, "names another instance's " + n)

    def test_default_is_instance_a(self):
        r, out, calls = self.run_deploy()
        self.assertEqual(0, r.returncode, out)
        self.assertIn("instance A", out)
        self.assert_names(out, A_NAMES, (B_NAMES, C_NAMES))
        self.assert_names(calls, (), (B_NAMES, C_NAMES))

    def test_explicit_a_is_the_same_as_the_default(self):
        _, out_default, _ = self.run_deploy()
        _, out_a, _ = self.run_deploy("--instance", "A")
        self.assertEqual(out_default, out_a)

    def test_b_targets_its_own_unit_folder_and_db_and_never_a(self):
        r, out, calls = self.run_deploy("--instance", "B")
        self.assertEqual(0, r.returncode, out)
        self.assertIn("instance B", out)
        self.assert_names(out, B_NAMES, (A_NAMES, C_NAMES))
        self.assertIn("deploy/test/start-test-b.sh", out)
        self.assert_names(calls, (), (A_NAMES, C_NAMES))

    def test_remote_command_for_b_never_names_a_and_the_reverse(self):
        _, out_b, _ = self.run_deploy("--instance", "B")
        _, out_a, _ = self.run_deploy("--instance", "A")
        rb = [l for l in out_b.splitlines() if l.startswith("remote command:")]
        ra = [l for l in out_a.splitlines() if l.startswith("remote command:")]
        self.assertEqual(1, len(rb))
        self.assertEqual(1, len(ra))
        self.assert_names(rb[0], B_NAMES, (A_NAMES, C_NAMES))
        self.assert_names(ra[0], A_NAMES, (B_NAMES, C_NAMES))

    def test_unknown_or_missing_instance_is_refused_before_any_ssh(self):
        for bad in ("D", "a", "", "B,C"):
            r, out, calls = self.run_deploy("--instance", bad)
            self.assertEqual(2, r.returncode, (bad, out))
            self.assertIn("unknown instance", out)
            self.assertEqual("", calls)
        r, out, calls = self.run_deploy("--instance")
        self.assertNotEqual(0, r.returncode)
        self.assertEqual("", calls)

    def test_c_refuses_a_commit_that_is_not_the_pin(self):
        r, out, calls = self.run_deploy("--instance", "C", pin="0" * 40)
        self.assertEqual(1, r.returncode, out)
        self.assertIn("pinned", out)
        self.assertEqual("", calls)

    def test_c_accepts_the_pinned_commit_and_names_only_c(self):
        r, out, calls = self.run_deploy("--instance", "C", pin=self.head)
        self.assertEqual(0, r.returncode, out)
        self.assert_names(out, C_NAMES, (A_NAMES, B_NAMES))
        self.assert_names(calls, (), (A_NAMES, B_NAMES))

    def test_c_force_overrides_the_pin(self):
        r, out, _ = self.run_deploy("--instance", "C", "--force", pin="0" * 40)
        self.assertEqual(0, r.returncode, out)
        self.assertIn("--force", out)

    def test_b_ignores_the_pin_and_force_is_not_needed(self):
        r, out, _ = self.run_deploy("--instance", "B", pin="0" * 40)
        self.assertEqual(0, r.returncode, out)

    def test_a_pin_file_with_a_short_or_empty_sha_is_not_a_wildcard(self):
        for pin in ("", "abc"):
            r, out, _ = self.run_deploy("--instance", "C", pin=pin)
            self.assertEqual(1, r.returncode, (pin, out))

    def test_commit_must_be_the_checked_out_head(self):
        r, out, _ = self.run_deploy("--instance", "B", "--commit", "0" * 40)
        self.assertNotEqual(0, r.returncode)
        r, out, _ = self.run_deploy("--instance", "B", "--commit", self.head)
        self.assertEqual(0, r.returncode, out)

    def test_feature_cannot_be_combined_with_b_or_c(self):
        r, out, calls = self.run_deploy("--feature", "--instance", "B")
        self.assertNotEqual(0, r.returncode)
        self.assertEqual("", calls)

    def test_the_remote_half_refuses_a_unit_and_db_from_different_instances(self):
        pairs = [
            ("/opt/mapleroot-dev/test-b", "slums-persistence-test", "mapleroot_persist", "slums-test-b-db"),
            ("/opt/mapleroot-dev/test-b", "slums-test-b", "mapleroot_persist", "mapleroot-integration-db"),
            ("/opt/mapleroot-dev/persistence-test", "slums-test-b", "mapleroot_persist", "mapleroot-integration-db"),
            ("/opt/mapleroot-dev/test-c", "slums-test-c", "mapleroot_persist", "slums-test-b-db"),
            ("/opt/mapleroot-dev/test-c", "slums-test-c", "mapleroot_persist", "mapleroot-integration-db"),
        ]
        for base, unit, dbn, dbc in pairs:
            r = subprocess.run(["bash", REMOTE, "/nonexistent", "x", "0", base, unit, dbn, dbc],
                               capture_output=True, text=True, timeout=30)
            self.assertEqual(1, r.returncode, (base, unit, dbc, r.stdout, r.stderr))
            self.assertIn("refusing unknown test target", r.stderr)

    def test_the_remote_half_without_a_db_container_means_a(self):
        # old callers (and test_deploy_test_remote.sh) pass six arguments
        r = subprocess.run(["bash", REMOTE, "/nonexistent", "x", "0", "/opt/mapleroot-dev/test-b", "slums-test-b",
                            "mapleroot_persist"], capture_output=True, text=True, timeout=30)
        self.assertEqual(1, r.returncode)
        self.assertIn("refusing unknown test target", r.stderr)


if __name__ == "__main__":
    unittest.main()
