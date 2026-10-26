package soloMapling.ArtificialPlayer.BotAttackSystem;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/*
 * 1.6.0: whether a viewer's client can find a range for another character's melee attack. A melee attack it cannot
 * find one for CLOSES the viewer's client (no dialog, the session just ends).
 *
 * CUserRemote::OnMeleeAttack (0x980c90) takes the attack's range from the skill level's lt/rb when it has one
 * (0x980d3c). Otherwise it asks CActionMan::GetMeleeAttackRange (0x414440) for the range of the pose in the weapon's
 * afterimage: Character/Afterimage/<weapon afterImage>.img, or the skill's own Skill.wz afterimage/<weapon
 * afterImage> when the skill has one (0x936f34 builds that path). That reads the pose's slot in a table filled only
 * for the poses the image has an lt/rb for; any other slot fails ZtlSecure's checksum and throws out of packet
 * processing. A player's client never sends such a pose. A bot did: 1.6.0 gave gunslinger bots guns, and their idle
 * show-off swing sent Double Shot (doublefire, 86) as a melee attack; a gun's afterimage has a range for
 * swingT1-3 and proneStab only (seen twice on TEST, QA rm-2's partner and the Mu Lung Dojo entrance).
 *
 * The data is the client's own (soloMapling/bot-melee-ranges.tsv: the R, J and I rows of the test fixture
 * client-attack-data.tsv, read from the client's Data by tools/bot-attack-rules/client_attack_fixture.py).
 * docs/real-bots/client-remote-attack-rules.md has the addresses.
 */
public final class BotMeleeRange {

    static final String RESOURCE = "/soloMapling/bot-melee-ranges.tsv";

    private static final int SAVAGE = 55;           // 0x414456: a hard-coded range, no table read
    private static final int PALADIN_BLAST = 1221009;
    private static final int STAB_O2 = 17;          // 0x980dab: Blast looks up stabO2's range

    private static final Map<Integer, Set<Integer>> RANGE_LEVELS = new HashMap<>();
    private static final Set<Integer> ALL_LEVELS = levels("1-255");
    private static final Map<Integer, Map<String, Set<String>>> SKILL_AFTERIMAGES = new HashMap<>();
    private static final Map<String, Set<String>> AFTERIMAGES = new HashMap<>();

    static {
        load();
    }

    private BotMeleeRange() {}

    /*
     * Whether a viewer's client finds a range for a melee attack with this skill (0 = none) at this level, sent in
     * this pose by a character holding this weapon. True too when the client never asks (a weapon without an
     * afterimage: the attack is dropped before, harmlessly).
     */
    public static boolean hasRange(int skillId, int skillLevel, int weaponItemId, int action) {
        String afterImage = afterImage(weaponItemId);
        if (afterImage.isEmpty()) {
            return true;
        }
        if (skillId > 0) {
            Set<Integer> levels = RANGE_LEVELS.get(skillId);
            if (levels != null && levels.contains(Math.max(1, skillLevel))) {
                return true;
            }
        }
        int pose = skillId == PALADIN_BLAST ? STAB_O2 : action;
        if (pose == SAVAGE) {
            return true;
        }
        String name = BotSkillActions.nameOf(pose);
        // the table is filled by name, so a repeated name's later code (the claw and wand swings 24-30) stays empty
        if (name == null || BotSkillActions.codeOf(name) != pose) {
            return false;
        }
        Map<String, Set<String>> own = skillId > 0 ? SKILL_AFTERIMAGES.get(skillId) : null;
        Set<String> poses = own != null ? own.get(afterImage) : AFTERIMAGES.get(afterImage);
        return poses != null && poses.contains(name);
    }

    /* The weapon's afterimage as the client names it ("" for none). */
    static String afterImage(int weaponItemId) {
        if (weaponItemId <= 0) {
            return "";
        }
        String a = BotClientPoses.weapon(weaponItemId).afterImage();
        return "default".equals(a) ? classAfterImage(weaponItemId) : a;
    }

    /* The usual afterimage of a weapon class, for a weapon the server has no data for. */
    static String classAfterImage(int weaponItemId) {
        return switch (BotSkillActions.weaponClass(weaponItemId)) {
            case 30, 31, 47 -> "swordOL";
            case 33 -> "swordOS";
            case 32, 37, 38 -> "mace";
            case 40 -> "swordTL";
            case 41, 42 -> "axe";
            case 43 -> "spear";
            case 44 -> "poleArm";
            case 45 -> "bow";
            case 46 -> "crossBow";
            case 48 -> "knuckle";
            case 49 -> "gun";
            default -> "";
        };
    }

    /*
     * The plain melee pose a viewer finds a range for with this weapon (one of its T1 poses), or -1. What a melee
     * attack is sent as instead when its own pose has none.
     */
    public static int safePlainPose(int weaponItemId) {
        int attackType = BotClientPoses.weapon(weaponItemId).attackType();
        for (List<Integer> poses : List.of(BotClientPoses.plainPoses(BotAttackProfile.Route.CLOSE, attackType, false),
                BotClientPoses.acceptedPlainPoses(attackType, false))) {
            for (int pose : poses) {
                if (hasRange(0, 0, weaponItemId, pose)) {
                    return pose;
                }
            }
        }
        return -1;
    }

    /* The rows as loaded (tests compare them with the fixture). */
    static Map<Integer, Set<Integer>> rangeLevels() {
        return RANGE_LEVELS;
    }

    static Map<Integer, Map<String, Set<String>>> skillAfterImages() {
        return SKILL_AFTERIMAGES;
    }

    static Map<String, Set<String>> afterImages() {
        return AFTERIMAGES;
    }

    private static void load() {
        try (InputStream in = BotMeleeRange.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is missing from the jar");
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t", -1);
                switch (f[0]) {
                    // a "common" range covers every level (MapleRoot's custom skills: levels 1-10 written out, the
                    // rest in common; either way the client finds an lt/rb for the level it reads)
                    case "R" -> RANGE_LEVELS.put(Integer.parseInt(f[1]), "1".equals(f[3]) ? ALL_LEVELS : levels(f[2]));
                    case "J" -> SKILL_AFTERIMAGES.computeIfAbsent(Integer.parseInt(f[1]), k -> new HashMap<>())
                            .put(f[2], names(f[3]));
                    case "I" -> AFTERIMAGES.put(f[1], names(f[2]));
                    default -> throw new IllegalStateException("unknown row in " + RESOURCE + ": " + line);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    private static Set<Integer> levels(String compact) {
        Set<Integer> out = new HashSet<>();
        if (compact.isEmpty()) {
            return out;
        }
        for (String part : compact.split(",")) {
            String[] r = part.split("-");
            int from = Integer.parseInt(r[0]);
            int to = r.length > 1 ? Integer.parseInt(r[1]) : from;
            for (int n = from; n <= to; n++) {
                out.add(n);
            }
        }
        return out;
    }

    private static Set<String> names(String list) {
        return list.isEmpty() ? Set.of() : Set.of(list.split(","));
    }
}
