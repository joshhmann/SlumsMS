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


def top_level(text, header):
    """The statements directly inside the first block that starts with header ("server {"): a brace walk, comment-free."""
    start = text.index(header) + len(header)
    out, depth = [], 1
    for line in text[start:].splitlines():
        code = line.split("#", 1)[0].strip()
        if depth == 1 and code:
            out.append(code)
        depth += code.count("{") - code.count("}")
        if depth <= 0:
            break
    return out


class NginxRedirectTest(unittest.TestCase):
    """audit: behind the front proxy every clean-URL redirect said http://<host>:8092/... until absolute_redirect off."""

    def test_test_server_nginx_has_no_absolute_redirects(self):
        with open(os.path.join(ROOT, "tools/site/test164/nginx.conf")) as f:
            directives = top_level(f.read(), "\n    server {")
        self.assertIn("absolute_redirect off;", directives,
                      "absolute_redirect off must be a server-block line (a location's redirect is not enough)")

    def test_the_live_recipe_says_server_level(self):
        with open(os.path.join(ROOT, "tools/site/slums-login.nginx.conf")) as f:
            text = f.read()
        part = text[text.index("# a) Clean URLs"):text.index("# b) The staff dashboard")]
        lines = part.splitlines()
        self.assertIn("absolute_redirect off;", lines, "part 3a must carry the line itself, unindented (server level)")
        self.assertIn("not in a location", part)
        self.assertIn("8092", part, "the comment says what the redirect leaked: the server's own port")


def flat(text):
    return " ".join(text.split())


def part(text, start, end):
    """The slice of a document between two markers, whitespace flattened."""
    return flat(text[text.index(start):text.index(end, text.index(start))])


def deploy_md():
    with open(os.path.join(ROOT, "docs/site/DEPLOY.md")) as f:
        return f.read()


class DeployDocTest(unittest.TestCase):
    """audit: section 3b did not say what release night 2026-11-01 taught (backups, the report map, the icon list)."""

    def test_step_1_backs_up_the_report_map_outside_conf_d(self):
        step = part(deploy_md(), "1. Stop the rebuilds", "2. Code and build")
        self.assertIn("/root/slums-report.conf.pre-161", step)
        self.assertNotIn("conf.d/slums-report.conf.pre", step, "a copy inside conf.d is loaded as config")

    def test_step_2_says_the_item_icon_list_is_copied(self):
        self.assertIn("extra-icon-items.txt", part(deploy_md(), "2. Code and build", "3. Swap the site in"))

    def test_step_5_installs_the_report_map_and_the_redirect_line(self):
        step = part(deploy_md(), "5. nginx", "6. Start the rebuilds")
        self.assertIn("slums-report.nginx.conf", step)
        self.assertIn("conf.d/slums-report.conf", step)
        self.assertIn("X-Forwarded-For", step)
        self.assertIn("absolute_redirect off", step)

    def test_step_8_adds_the_inspect_lines_when_the_unit_has_none(self):
        step = part(deploy_md(), "8. Staff Bots page", "### Website rollback")
        self.assertIn("SLUMS_LOGIN_INSPECT_", step)
        self.assertIn("add (or uncomment)", step)

    def test_the_rollback_restores_the_report_map(self):
        self.assertIn("slums-report.conf.pre-161", part(deploy_md(), "### Website rollback", "## 4. Add a release"))

    def test_the_release_night_note_names_what_went_differently(self):
        note = part(deploy_md(), "Release night 2026-11-01", "## 4. Add a release")
        for fact in ("extra-icon-items.txt", "absolute_redirect off", "slums-report.conf"):
            self.assertIn(fact, note)


if __name__ == "__main__":
    unittest.main()
