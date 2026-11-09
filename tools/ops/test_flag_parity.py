"""tools/ops/flag-parity on fixture command lines (no host access)."""
import importlib.machinery
import importlib.util
import os
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
_loader = importlib.machinery.SourceFileLoader("flag_parity", os.path.join(HERE, "flag-parity"))
_spec = importlib.util.spec_from_loader("flag_parity", _loader)
fp = importlib.util.module_from_spec(_spec)
_loader.exec_module(fp)

LIVE = "/usr/bin/java -Xmx6G -Dmapleroot.login-port=8485 -Dslums.mechanics=true -Dslums.inspect=true -Dslums.exped.door=true -jar x.jar"
TEST = "/usr/bin/java -Xmx12G -Dmapleroot.login-port=8489 -Dslums.mechanics=true -Dslums.qa=true -Dslums.exped.door=true -jar x.jar"
ALLOW = "test-only slums.qa  # plumbing\nlive-only slums.inspect\nvalue mapleroot.login-port\n"


class FlagParityTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.allow_path = self.write("allow", ALLOW)
        self.allow = fp.read_allow(self.allow_path)

    def tearDown(self):
        self.tmp.cleanup()

    def write(self, name, text):
        p = os.path.join(self.tmp.name, name)
        with open(p, "w") as f:
            f.write(text)
        return p

    def bad(self, live, test, gains=None, loses=None):
        return fp.compare(fp.flags_of(live), fp.flags_of(test), self.allow, gains, loses)

    def test_declared_differences_pass(self):
        self.assertEqual([], self.bad(LIVE, TEST))

    def test_a_behaviour_flag_only_on_test_fails(self):
        bad = self.bad(LIVE, TEST.replace(" -jar", " -Dslums.bossfight.reach=true -jar"))
        self.assertEqual([("test-only", "slums.bossfight.reach", None, "true")], bad)

    def test_a_flag_only_on_live_fails(self):
        bad = self.bad(LIVE.replace(" -jar", " -Dslums.exchange.startHalted=true -jar"), TEST)
        self.assertEqual([("live-only", "slums.exchange.startHalted", "true", None)], bad)

    def test_a_different_value_fails_unless_declared(self):
        bad = self.bad(LIVE, TEST.replace("-Dslums.mechanics=true", "-Dslums.mechanics=false"))
        self.assertEqual([("value", "slums.mechanics", "true", "false")], bad)

    def test_the_release_conf_declares_its_live_changes(self):
        test = TEST.replace(" -jar", " -Dslums.altfight.instanceleash=true -jar")
        conf = self.write("r.conf", 'UNIT_EDITS=("s# -jar # -Dslums.altfight.instanceleash=true -jar #")\n'
                                    'UNIT_FLAGS_ABSENT=(-Dslums.inspect)\n')
        gains, loses = fp.conf_changes(conf)
        self.assertEqual({"slums.altfight.instanceleash": "true"}, gains)
        self.assertEqual({"slums.inspect"}, loses)
        self.assertEqual([], self.bad(LIVE, test, gains, loses))
        # a gain only counts with the value the conf gives live
        self.assertEqual(1, len(self.bad(LIVE, test.replace("instanceleash=true", "instanceleash=false"), gains, loses)))

    def test_a_script_with_continuation_lines_reads_every_flag(self):
        script = "#!/bin/bash\nsystemd-run -p X=1 \\\n  /usr/bin/java -Da=1 \\\n  -Db=2 -jar x.jar\n"
        self.assertEqual({"a": "1", "b": "2"}, fp.flags_of(script))

    def test_a_bad_allow_line_is_refused(self):
        with self.assertRaises(SystemExit):
            fp.read_allow(self.write("bad", "maybe slums.qa\n"))

    def test_the_repo_files_parse(self):
        root = os.path.abspath(os.path.join(HERE, "..", ".."))
        fp.read_allow(os.path.join(root, "docs", "release", "flag-parity.allow"))
        self.assertIn("slums.qa", fp.flags_of(open(os.path.join(root, "deploy", "test", "start-test.sh")).read()))


if __name__ == "__main__":
    unittest.main()
