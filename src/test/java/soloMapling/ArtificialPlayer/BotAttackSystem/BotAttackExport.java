package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Job;
import client.inventory.WeaponType;
import constants.inventory.EquipType;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/*
 * The bots' side of an attack capture (ticket 32): for every job and each weapon kind its bots carry, the attacks
 * BotAttackConfig resolves and the attack header BotAttackDriver would send for each - the skill, the body actions
 * it picks from, the facing masks, speed, mobs and lines, projectile and charge - in the capture's column names.
 * BotAttackExportTest writes it to a file; tools/attack-capture/attack_capture.py diffs a capture against it.
 * The skill data is MapleRoot's (SkillPoseFixture), so this runs without a server.
 */
final class BotAttackExport {

    static final List<String> COLUMNS = List.of("jobId", "job", "role", "weaponKind", "weaponClass", "route",
            "skillId", "skillLevel", "display", "bodyActionIds", "actionNames", "facingMask", "speed",
            "numAttacked", "numDamage", "charge", "projectile", "note");

    private BotAttackExport() {}

    /* The header and one line per (job, weapon kind, attack role). */
    static List<String> lines() {
        SkillPoseFixture.install();
        List<String> lines = new ArrayList<>();
        lines.add(String.join("\t", COLUMNS));
        for (Job job : Job.values()) {
            for (EquipType kind : BotWeapons.weaponsFor(job)) {
                int weaponId = kind.getValue() * 1000;
                WeaponType weapon = weaponType(weaponId);
                BotAttackConfig.JobAttacks attacks = BotAttackConfig.resolve(job, weapon);
                addRow(lines, job, "single", kind, weaponId, weapon, attacks.single());
                addRow(lines, job, "aoe", kind, weaponId, weapon, attacks.aoe());
                addRow(lines, job, "ultimate", kind, weaponId, weapon, attacks.ultimate());
            }
        }
        return lines;
    }

    private static void addRow(List<String> lines, Job job, String role, EquipType kind, int weaponId,
                               WeaponType weapon, BotAttackProfile profile) {
        if (profile == null) {
            return;
        }
        int skillId = profile.skillId;
        int skillLevel = profile.skillLevel;
        BotAttackProfile.Route route = profile.route;
        String note = "";
        if (!BotSkillActions.weaponFits(skillId, weaponId)) {
            note = "skill " + skillId + " does not take this weapon; sent as a plain swing";
            skillId = 0;
            skillLevel = 0;
            route = BotAttackProfile.Route.CLOSE;
        }
        List<Integer> actions = BotSkillActions.poseCodes(skillId, skillLevel);
        if (actions.isEmpty()) {
            actions = Arrays.stream(BotAttackData.variantsFor(weapon)).boxed().toList();
        }
        StringJoiner row = new StringJoiner("\t");
        row.add(String.valueOf(job.getId()));
        row.add(job.name());
        row.add(role);
        row.add(kind.name());
        row.add(String.valueOf(BotSkillActions.weaponClass(weaponId)));
        row.add(route.name().toLowerCase());
        row.add(String.valueOf(skillId));
        row.add(String.valueOf(skillLevel));
        row.add("0");
        row.add(actions.stream().map(String::valueOf).collect(Collectors.joining(",")));
        row.add(actions.stream().map(BotSkillActions::nameOf).collect(Collectors.joining(",")));
        row.add(BotAttackData.FACING_RIGHT_MASK + "," + BotAttackData.FACING_LEFT_MASK);
        row.add(String.valueOf(profile.speed));
        row.add(String.valueOf(profile.numAttacked));
        row.add(String.valueOf(profile.numDamage));
        row.add(String.valueOf(route == BotAttackProfile.Route.MAGIC ? Math.max(0, BotAttackData.magicChargeFor(skillId)) : 0));
        row.add(String.valueOf(route == BotAttackProfile.Route.RANGED ? BotAttackData.projectileFor(weapon) : 0));
        row.add(note);
        lines.add(row.toString());
    }

    static WeaponType weaponType(int itemId) {
        return switch (itemId / 10000 % 100) {
            case 30 -> WeaponType.SWORD1H;
            case 31, 32 -> WeaponType.GENERAL1H_SWING;
            case 33 -> WeaponType.DAGGER_OTHER;
            case 37 -> WeaponType.WAND;
            case 38 -> WeaponType.STAFF;
            case 40 -> WeaponType.SWORD2H;
            case 41, 42 -> WeaponType.GENERAL2H_SWING;
            case 43 -> WeaponType.SPEAR_STAB;
            case 44 -> WeaponType.POLE_ARM_SWING;
            case 45 -> WeaponType.BOW;
            case 46 -> WeaponType.CROSSBOW;
            case 47 -> WeaponType.CLAW;
            case 48 -> WeaponType.KNUCKLE;
            case 49 -> WeaponType.GUN;
            default -> WeaponType.NOT_A_WEAPON;
        };
    }
}
