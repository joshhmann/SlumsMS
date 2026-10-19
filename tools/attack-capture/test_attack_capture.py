"""python3 -m unittest discover -s tools/attack-capture"""
import io
import unittest

import attack_capture as ac


def attack(skill, name="", action="5", facing="0", speed="4", event="close", **extra):
    row = {"event": event, "char": "Gm", "jobId": "512", "job": "BUCCANEER", "skillId": skill, "skillName": name,
           "skillLevel": "20", "bodyActionId": action, "actionName": "", "facingMask": facing, "speed": speed,
           "display": "0", "numAttacked": "1", "numDamage": "1", "charge": "0", "projectile": "", "weaponClass": "48"}
    row.update(extra)
    return row


def bot(skill, actions="5,6", speed="4", mobs="1", **extra):
    row = {"jobId": "512", "job": "BUCCANEER", "role": "single", "weaponKind": "KNUCKLER", "weaponClass": "48",
           "route": "close", "skillId": skill, "skillLevel": "20", "display": "0", "bodyActionIds": actions,
           "actionNames": "", "facingMask": "0,128", "speed": speed, "numAttacked": mobs, "numDamage": "1",
           "charge": "0", "projectile": "0", "note": ""}
    row.update(extra)
    return row


class AttackCaptureTest(unittest.TestCase):

    def test_rotation_folds_repeats(self):
        rows = [attack("1", "A"), attack("1", "A"), attack("2", "B"), attack("1", "A")]
        self.assertEqual("1 A x2 > 2 B > 1 A", ac.rotation(rows))

    def test_a_matching_skill_has_no_problems(self):
        self.assertEqual([], ac.compare([attack("5121011", action="6", facing="128")], [bot("5121011")]))

    def test_a_pose_the_bot_never_picks_is_reported(self):
        problems = ac.compare([attack("5121011", action="91", actionName="octopus")], [bot("5121011")])
        self.assertEqual(1, len(problems))
        self.assertIn("bodyActionId: player 91", problems[0])

    def test_speed_and_mob_count_differences_are_reported(self):
        problems = ac.compare([attack("5121011", speed="2", numAttacked="6")], [bot("5121011")])
        self.assertTrue(any(p.startswith("speed:") for p in problems))
        self.assertTrue(any(p.startswith("numAttacked:") for p in problems))

    def test_diff_lists_player_only_and_bot_only_skills(self):
        out = io.StringIO()
        ac.diff([attack("5121011", "Octopunch"), attack("5111006", "Shockwave")],
                [bot("5121011"), bot("5121013", role="aoe")], out)
        text = out.getvalue()
        self.assertIn("MATCH  5121011 Octopunch", text)
        self.assertIn("PLAYER ONLY  5111006 Shockwave", text)
        self.assertIn("BOT ONLY     5121013", text)

    def test_summary_counts_skills_per_job(self):
        out = io.StringIO()
        ac.summarize([attack("5121011", "Octopunch"), attack("5121011", "Octopunch"),
                      attack("5121003", "Super Transformation", event="cast")], out)
        text = out.getvalue()
        self.assertIn("== BUCCANEER (512) - 2 attacks, 1 casts/keydowns", text)
        self.assertIn("    2  5121011 Octopunch", text)
        self.assertIn("cast     5121003 Super Transformation", text)


if __name__ == "__main__":
    unittest.main()
