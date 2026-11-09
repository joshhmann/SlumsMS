"""tools/ops/test-instances.py: the instance table and the generated B/C start scripts (no host access)."""
import importlib.machinery
import importlib.util
import os
import subprocess
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))


def load(name, fname):
    loader = importlib.machinery.SourceFileLoader(name, os.path.join(HERE, fname))
    spec = importlib.util.spec_from_loader(name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


ti = load("test_instances_mod", "test-instances.py")
fp = load("flag_parity_mod", "flag-parity")


def a_text():
    return open(os.path.join(ROOT, "deploy", "test", "start-test.sh")).read()


def committed(name):
    return open(os.path.join(ROOT, ti.INSTANCES[name]["start_script"])).read()


class GeneratedStartScripts(unittest.TestCase):
    def test_committed_scripts_equal_the_generator_output(self):
        for n in "BC":
            self.assertEqual(ti.render_start(n), committed(n), "run: tools/ops/test-instances.py render-start " + n)

    def test_flags_are_a_minus_the_switched_off_list(self):
        a = fp.flags_of(a_text())
        for n in "BC":
            b = fp.flags_of(committed(n))
            self.assertEqual(set(ti.OFF_IN_BC), set(a) - set(b), n)
            self.assertEqual(set(), set(b) - set(a), n)

    def test_only_port_path_and_heap_values_differ(self):
        a = fp.flags_of(a_text())
        allowed = {"slums.ops.port", "slums.ops.secretFile", "slums.inspect.port", "slums.inspect.secretFile",
                   "mapleroot.login-port", "mapleroot.channel-base-port"}
        for n in "BC":
            b = fp.flags_of(committed(n))
            changed = {k for k in b if b[k] != a[k]}
            self.assertEqual(allowed, changed, n)

    def test_switched_off_flags_really_are_gone_and_the_rest_kept(self):
        for n in "BC":
            t = committed(n)
            for f in ti.OFF_IN_BC:
                self.assertNotIn("-D" + f + "=", t)
                self.assertNotIn("-D" + f + " ", t)
            for keep in ("-Dslums.auth.revoked=", "-Dsolomapling.dashboard=true", "-Dsolomapling.autopopulate=true"):
                self.assertIn(keep, t)

    def test_each_script_names_only_its_own_unit_folder_and_secrets(self):
        for n in "BC":
            t, i = committed(n), ti.INSTANCES[n]
            self.assertIn("--unit=" + i["unit"] + " ", t)
            self.assertIn("-Xms%s -Xmx%s" % (i["heap"], i["heap"]), t)
            self.assertIn("MemoryMax=%d" % i["memory_max"], t)
            self.assertIn("CPUQuota=" + i["cpu"], t)
            for leak in ("slums-persistence-test ", "/opt/mapleroot-dev/persistence-test", "/etc/slums-inspect-test",
                         "/etc/slums-ops-test", "soak-rc150e", "8489", "7985", "8199", "8198"):
                self.assertNotIn(leak, t, n + " still names A's " + leak)

    def test_a_changed_shape_of_a_is_refused_not_guessed(self):
        with self.assertRaises(SystemExit):
            ti.render_start("B", a_text().replace("-Xms12G -Xmx12G", "-Xmx12G"))
        with self.assertRaises(SystemExit):
            ti.render_start("A")

    def test_scripts_are_valid_bash(self):
        for n in "BC":
            subprocess.run(["bash", "-n", os.path.join(ROOT, ti.INSTANCES[n]["start_script"])], check=True)


class InstanceTable(unittest.TestCase):
    def test_values_match_the_approved_plan(self):
        b, c = ti.INSTANCES["B"], ti.INSTANCES["C"]
        self.assertEqual((8589, 8085, 8298, 8299, 3308, 150), tuple(b[k] for k in
                         ("login", "channel_base", "inspect", "ops", "db_port", "population")))
        self.assertEqual((8689, 8185, 8398, 8399, 3310, 50), tuple(c[k] for k in
                         ("login", "channel_base", "inspect", "ops", "db_port", "population")))
        self.assertEqual("4608M", b["heap"])
        self.assertEqual(6 * 1024 ** 3, b["memory_max"])
        self.assertEqual("200%", c["cpu"])
        self.assertIsNone(b["auth"])
        self.assertEqual(("slums-test-b-db", "slums-test-c-db"), (b["db_container"], c["db_container"]))

    def test_no_port_is_shared_between_instances(self):
        seen = {}
        for n in "ABC":
            for what, port in ti.ports(n).items():
                self.assertNotIn(port, seen, "%s %s port %d also used by %s" % (n, what, port, seen.get(port)))
                seen[port] = "%s %s" % (n, what)

    def test_live_db_port_3309_never_appears(self):
        for n in "ABC":
            self.assertNotIn(ti.LIVE_DB_PORT, ti.ports(n).values())
        for path in ("start-test-b.sh", "start-test-c.sh"):
            self.assertNotIn("3309", open(os.path.join(ROOT, "deploy", "test", path)).read())
        self.assertNotIn("3309", open(os.path.join(HERE, "test-instances.py")).read().replace("LIVE_DB_PORT = 3309", ""))

    def test_get_and_unknown(self):
        self.assertEqual("slums-test-c", ti.get("C", "unit"))
        with self.assertRaises(KeyError):
            ti.get("D", "unit")
        r = subprocess.run([os.path.join(HERE, "test-instances.py"), "get", "D", "unit"], capture_output=True, text=True)
        self.assertEqual(2, r.returncode)


if __name__ == "__main__":
    unittest.main()
