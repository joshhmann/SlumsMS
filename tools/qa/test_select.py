import importlib.machinery
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
loader = importlib.machinery.SourceFileLoader("qa_select", str(HERE / "select"))
spec = importlib.util.spec_from_loader("qa_select", loader)
sel = importlib.util.module_from_spec(spec)
loader.exec_module(sel)

ROWS = sel.load_table(HERE / "impact.tsv")


def pick(*paths):
    return sel.select(list(paths), ROWS)


class SelectTest(unittest.TestCase):
    def test_known_path_selects_its_row_only(self):
        r = pick("src/main/java/soloMapling/companions/OwnedAltService.java")
        self.assertEqual("soloMapling/companions/**", r["junit"])
        self.assertEqual("oa,mx", r["autoqa"])
        self.assertFalse(r["broadened"])
        self.assertFalse(r["smoke"])

    def test_unknown_path_broadens_to_full_and_smoke(self):
        r = pick("src/main/java/soloMapling/companions/A.java", "somewhere/new/File.java")
        self.assertEqual(["somewhere/new/File.java"], r["unknown"])
        self.assertEqual("FULL", r["junit"])
        self.assertTrue(r["smoke"])
        self.assertTrue(r["broadened"])
        self.assertEqual("smoke,oa,mx", sel.autoqa_arg(r))

    def test_docs_only_selects_nothing_and_is_not_unknown(self):
        r = pick("docs/plans/1.7.0/D-x.md", "README.md")
        self.assertEqual([], r["unknown"])
        self.assertEqual("", r["junit"])
        self.assertEqual("", r["autoqa"])

    def test_every_matching_row_contributes(self):
        r = pick("src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/X.java",
                 "src/main/java/soloMapling/mechanics/Y.java")
        self.assertIn("bl", r["autoqaFamilies"])
        self.assertIn("mx", r["autoqaFamilies"])
        self.assertEqual(["bot.life", "pq.mechanics"], r["capabilities"])

    def test_brace_and_comma_columns_expand(self):
        r = pick("src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/Z.java")
        self.assertIn("soloMapling/ArtificialPlayer/BotCombatSystem/**", r["junitClasses"])
        self.assertIn("soloMapling/ArtificialPlayer/BotAttackSystem/**", r["junitClasses"])

    def test_full_row_forces_full_unit_suite(self):
        r = pick("database/migrations/100-x.sql")
        self.assertEqual("FULL", r["junit"])
        self.assertTrue(r["smoke"])
        self.assertFalse(r["broadened"])

    def test_glob_single_star_stays_in_directory(self):
        rx = sel.glob_regex("a/*.java")
        self.assertTrue(rx.match("a/B.java"))
        self.assertFalse(rx.match("a/b/B.java"))
        self.assertTrue(sel.glob_regex("a/**").match("a/b/c/D.java"))

    def test_table_rows_are_well_formed(self):
        for row in ROWS:
            self.assertTrue(row["regexes"], row["glob"])
            self.assertTrue(row["caps"] and row["junit"] and row["checks"], row["glob"])

    def test_bad_table_row_is_rejected(self):
        with tempfile.NamedTemporaryFile("w", suffix=".tsv", delete=False) as f:
            f.write("only\ttwo\n")
        with self.assertRaises(SystemExit):
            sel.load_table(f.name)

    def test_cli_paths_json(self):
        out = subprocess.run([sys.executable, str(HERE / "select"), "--paths", "-", "--json"],
                             input="x/unknown.txt\n", capture_output=True, text=True, check=True).stdout
        rec = json.loads(out)
        self.assertEqual(["x/unknown.txt"], rec["unknown"])
        self.assertEqual("FULL", rec["junit"])


if __name__ == "__main__":
    unittest.main()
