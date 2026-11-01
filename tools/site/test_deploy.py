"""The website's deploy files (w22-ops-followups): the copies onto the servers and what they must carry.

python3 -m unittest discover -s tools/site -p 'test_*.py'

The shell scripts are run for real, with git, ssh and rsync replaced by stubs that record their arguments, so the test
sees the files a deploy would really copy (and nothing is sent anywhere).
"""
import os
import shutil
import stat
import subprocess
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))

GIT = """#!/bin/sh
case "$1 $2" in
    "rev-parse --show-toplevel") echo "$FAKE_ROOT" ;;
    "rev-parse --short") echo 0badc0de ;;
    *) echo "stub git: $*" >&2; exit 2 ;;
esac
"""
LOG_AND_PASS = """#!/bin/sh
echo "%s $*" >> "$FAKE_LOG"
"""


class DeployScriptTest(unittest.TestCase):
    """Runs one of the copy scripts against a fake repo, with stubs for git, ssh and rsync."""

    SCRIPT = ""
    FILES = ()

    def setUp(self):
        self.tmp = tempfile.mkdtemp(prefix="slums-deploy-")
        self.root = os.path.join(self.tmp, "repo")
        self.log = os.path.join(self.tmp, "log")
        self.bin = os.path.join(self.tmp, "bin")
        os.makedirs(self.bin)
        for name, text in (("git", GIT), ("ssh", LOG_AND_PASS % "ssh"), ("rsync", LOG_AND_PASS % "rsync")):
            path = os.path.join(self.bin, name)
            with open(path, "w") as f:
                f.write(text)
            os.chmod(path, os.stat(path).st_mode | stat.S_IXUSR)
        for rel in self.FILES:
            full = os.path.join(self.root, rel)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            with open(full, "w") as f:
                f.write("{}\n")

    def tearDown(self):
        shutil.rmtree(self.tmp)

    def run_script(self, script=None, *args):
        env = dict(os.environ, PATH=self.bin + ":" + os.environ["PATH"], FAKE_ROOT=self.root, FAKE_LOG=self.log)
        for key in ("SLUMS_TEST_HOST", "SLUMS_HS_DATA"):
            env.pop(key, None)
        return subprocess.run(["bash", os.path.join(ROOT, script or self.SCRIPT), *args], cwd=self.root, env=env,
                              capture_output=True, text=True, timeout=120)

    def copies(self):
        if not os.path.exists(self.log):
            return ""
        with open(self.log) as f:
            return f.read()

    def assert_copies_tree(self, rel, dest):
        """The run copied the whole of rel (the trailing slash on the source) to dest."""
        lines = [ln for ln in self.copies().splitlines() if ln.startswith("rsync ") and f" {rel}/ " in ln
                 and ln.rstrip().endswith(dest.rstrip("/") + "/")]
        self.assertTrue(lines, f"no rsync of {rel}/ to {dest} in:\n{self.copies()}")


class LiveBuildCopyTest(DeployScriptTest):
    """audit: the live build folder missed data/site/extra-icon-items.txt, so three icons were missing on release night."""

    SCRIPT = "tools/site/deploy-build-164.sh"
    FILES = ("tools/site/build.py", "docs/site/home.html", "docs/wiki/index.html",
             "data/site/hiddenstreet/items.json", "data/site/extra-icon-items.txt", "cache/site-icons/manifest.json")

    def test_the_item_icon_list_reaches_the_build_folder(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assert_copies_tree("data/site", "root@192.168.0.164:/opt/slums-site-build/data/site/")

    def test_a_missing_item_icon_list_stops_the_copy(self):
        os.remove(os.path.join(self.root, "data/site/extra-icon-items.txt"))
        r = self.run_script()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("missing data/site/extra-icon-items.txt", r.stderr)
        self.assertNotIn("rsync", self.copies(), "nothing is copied without the item icon list")


class TestSiteInstallTest(DeployScriptTest):
    """install.sh had the same gap as the live build copy."""

    SCRIPT = "tools/site/test164/install.sh"
    FILES = ("tools/site/build.py", "tools/site/test164/nginx.conf", "docs/site/home.html", "docs/wiki/index.html",
             "data/site/hiddenstreet/items.json", "data/site/extra-icon-items.txt", "cache/site-icons/manifest.json")

    def test_the_item_icon_list_reaches_the_test_build_folder(self):
        r = self.run_script()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assert_copies_tree("data/site", "root@192.168.0.164:/opt/slums-site-test/data/site/")

    def test_a_missing_item_icon_list_stops_the_install(self):
        os.remove(os.path.join(self.root, "data/site/extra-icon-items.txt"))
        r = self.run_script()
        self.assertNotEqual(r.returncode, 0)
        self.assertIn("missing data/site/extra-icon-items.txt", r.stderr)
        self.assertNotIn("rsync", self.copies(), "nothing is copied without the item icon list")


if __name__ == "__main__":
    unittest.main()
