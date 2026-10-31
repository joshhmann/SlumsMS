#!/usr/bin/env python3
"""Tests for tools/ops/autoqa-proof (b223).

Local only: builds throw-away git repos in a temp dir, never touches a server, never queues a run.

    python3 -m unittest tools.ops.test_autoqa_proof -v      # or: python3 tools/ops/test_autoqa_proof.py
"""
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
TOOL = os.path.join(HERE, "autoqa-proof")
RUNNER = os.path.join(HERE, "autoqa-run")
MAP = os.path.join(ROOT, "src", "main", "resources", "soloMapling", "autoqa-coverage.tsv")


def proof(*args, **kw):
    env = dict(os.environ)
    env.pop("AUTOQA_LOCAL_DIR", None)
    env.update(kw.pop("env", {}))
    return subprocess.run([sys.executable, TOOL] + list(args), capture_output=True, text=True, env=env, cwd=ROOT, **kw)


class GlobTest(unittest.TestCase):
    """The map's globs: * stays in one segment, ** crosses segments, ? is one character."""

    def checks(self, path):
        r = proof("--files", path, "--quiet")
        self.assertIn(r.returncode, (0, 3), r.stderr)
        return set(r.stdout.split()[1].split(",")) if r.stdout.startswith("tools/ops/autoqa-run") else set()

    def test_double_star_crosses_directories(self):
        self.assertIn("t-mx-1", self.checks("src/main/java/soloMapling/mechanics/KpqStaging.java"))
        self.assertIn("t-mx-1", self.checks("src/main/java/soloMapling/mechanics/content/Whatever.java"))

    def test_one_star_does_not_cross_directories(self):
        # client/Family*.java covers a sibling of Family.java, not a file in a subdirectory
        self.assertIn("t-fy-1", self.checks("src/main/java/client/FamilyBuffs.java"))
        self.assertNotIn("t-fy-1", self.checks("src/main/java/client/nested/FamilyBuffs.java"))

    def test_question_mark_is_one_character(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        m = os.path.join(tmp, "map.tsv")
        with open(m, "w", encoding="utf-8") as fh:
            fh.write("t-mx-1\tsrc/a?.java\ttest\n")
        hit = proof("--files", "src/ab.java", "--map", m, "--quiet")
        self.assertEqual("tools/ops/autoqa-run t-mx-1\n", hit.stdout, hit.stderr)
        miss = proof("--files", "src/abb.java", "--map", m, "--quiet")
        self.assertEqual(3, miss.returncode, miss.stdout)


class KnownChangeTest(unittest.TestCase):
    def test_a_kerning_pq_file_maps_to_the_kerning_checks(self):
        r = proof("--files", "src/main/java/soloMapling/mechanics/KpqStaging.java")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("t-mx-1", r.stdout)
        self.assertIn("t-mx-2", r.stdout)
        self.assertIn("t-mx-6", r.stdout)
        self.assertRegex(r.stdout.strip().splitlines()[-1], r"^tools/ops/autoqa-run [A-Za-z0-9,_-]+$")

    def test_a_release_tool_is_not_covered(self):
        r = proof("--files", "tools/release/stage")
        self.assertEqual(3, r.returncode, r.stdout + r.stderr)
        self.assertIn("no check covers this change", r.stdout)
        self.assertNotIn("autoqa-run ", r.stdout)

    def test_docs_only_needs_no_run(self):
        r = proof("--files", "docs/ops/qa-suite.md", "docs/qa/QA.md")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("docs only, no proof run needed", r.stdout)
        self.assertNotIn("tools/ops/autoqa-run", r.stdout)

    def test_a_unit_test_is_not_a_proof_run(self):
        r = proof("--files", "src/test/java/soloMapling/autoqa/AutoQaProofMapTest.java")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("tests only", r.stdout)

    def test_a_platform_file_means_every_check(self):
        r = proof("--files", "src/main/java/soloMapling/autoqa/AutoQaRunner.java", "--quiet")
        self.assertEqual("tools/ops/autoqa-run all\n", r.stdout)

    def test_a_switch_is_reported_with_the_checks_that_need_it(self):
        r = proof("--files", "src/main/java/soloMapling/mechanics/Orchestrator.java")
        self.assertEqual(0, r.returncode, r.stderr)
        self.assertIn("-Dslums.mechanics=true", r.stdout)

    def test_nothing_changed_is_not_an_error(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        git(tmp, "init", "-q")
        git(tmp, "commit", "-q", "--allow-empty", "-m", "empty")
        r = proof("--root", tmp, "--map", MAP)
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("nothing changed", r.stdout)


class RangeTest(unittest.TestCase):
    """Range mode on a real (throw-away) git history."""

    def test_a_range_names_the_checks_of_the_commit(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        git(tmp, "init", "-q")
        git(tmp, "config", "user.email", "t@example.invalid")
        git(tmp, "config", "user.name", "test")
        write(tmp, "README", "root\n")
        git(tmp, "add", "-A")
        git(tmp, "commit", "-q", "-m", "root")
        write(tmp, "docs/notes.md", "one\n")
        git(tmp, "add", "-A")
        git(tmp, "commit", "-q", "-m", "docs")
        base = git(tmp, "rev-parse", "HEAD").strip()
        write(tmp, "src/main/java/soloMapling/mechanics/KpqStaging.java", "class K {}\n")
        git(tmp, "add", "-A")
        git(tmp, "commit", "-q", "-m", "kpq")
        head = git(tmp, "rev-parse", "HEAD").strip()

        r = proof("--root", tmp, "--map", MAP, "%s..%s" % (base, head))
        self.assertEqual(0, r.returncode, r.stdout + r.stderr)
        self.assertIn("KpqStaging.java", r.stdout)
        self.assertIn("t-mx-1", r.stdout)

        docs = proof("--root", tmp, "--map", MAP, "%s^..%s" % (base, base))
        self.assertEqual(0, docs.returncode, docs.stdout + docs.stderr)
        self.assertIn("docs only, no proof run needed", docs.stdout)


class RunLineTest(unittest.TestCase):
    """The line the tool prints is one the runner accepts, in local mode."""

    def test_the_printed_line_queues_the_same_ids(self):
        tmp = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, tmp)
        r = proof("--files", "src/main/java/soloMapling/mechanics/KpqStaging.java")
        line = r.stdout.strip().splitlines()[-1]
        selector = line.split()[-1]
        env = {"AUTOQA_LOCAL_DIR": tmp}
        q = subprocess.run([RUNNER, selector], capture_output=True, text=True, env=dict(os.environ, **env), cwd=ROOT)
        self.assertEqual(0, q.returncode, q.stdout + q.stderr)
        self.assertIn("queued", q.stdout)
        with open(os.path.join(tmp, "run-queue.txt"), encoding="utf-8") as fh:
            queued = fh.read().strip().split("\t")[2]
        self.assertEqual(selector, queued)
        self.assertRegex(selector, r"^[A-Za-z0-9,_-]+$")

    def test_the_json_view_carries_the_same_ids(self):
        import json
        r = proof("--files", "src/main/java/soloMapling/mechanics/KpqStaging.java", "--json")
        data = json.loads(r.stdout)
        self.assertIn("t-mx-1", data["checks"])
        self.assertEqual(["t-in-3", "t-mx-1", "t-mx-2", "t-mx-3", "t-mx-4", "t-mx-5", "t-mx-6"], data["checks"])
        self.assertEqual([], data["unmapped"])


def write(root, rel, text):
    path = os.path.join(root, rel)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as fh:
        fh.write(text)


def git(root, *args):
    r = subprocess.run(["git", "-C", root] + list(args), capture_output=True, text=True)
    if r.returncode != 0:
        raise AssertionError("git %s: %s" % (" ".join(args), r.stderr))
    return r.stdout


if __name__ == "__main__":
    unittest.main(verbosity=2)
