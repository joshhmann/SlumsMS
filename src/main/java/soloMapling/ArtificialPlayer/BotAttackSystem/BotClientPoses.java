package soloMapling.ArtificialPlayer.BotAttackSystem;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/*
 * What MapleRoot's v83 client (MapleStory.exe, md5 89f69d20cc0457a2d7db5573082c13d7) does with an attack, read out of
 * the exe - docs/real-bots/client-remote-attack-rules.md has the addresses and the pseudocode, and
 * tools/bot-attack-rules/exe_rules.py dumps the same tables from the exe (src/test/resources/soloMapling/
 * client-attack-exe.tsv; BotClientPosesTest checks this class against that dump).
 *
 * - The plain poses: a weapon's Skill.wz-less attacks come from one of three tables indexed by the weapon's
 *   Character.wz "attack" type (1 one-handed, 2 spear/pole-arm, 3 bow, 4 crossbow, 5 two-handed, 6 wand/staff,
 *   7 claw, 8 knuckle, 9 gun): T1 for a melee swing, T2 for a shot, T3 for a spell. A local player's client picks
 *   from its route's table (0x766031 / 0x766272 / 0x7662f5); a viewer's client accepts a pose from any of the three
 *   (CUser::CheckAttackAction 0x92edb2 -> 0x7665b1 / 0x766525). A skill with skillType 3 uses the second row.
 * - The skill lists the exe hard-codes: the keydown skills, the ones a viewer draws without checking the pose, the
 *   ones shot without a bullet.
 * - The attack speed a player's client sends: MapleRoot hooks 0x765066 and sends a degree by weapon class plus Speed
 *   Infusion (BotAttackTiming.speedDegree); the stock exe sent the weapon's "attackSpeed" clamped to 2..10.
 *
 * The weapon data is read from the server's Character.wz (the same as the client's); a test supplies its own.
 */
public final class BotClientPoses {

    private BotClientPoses() {}

    /* T1 (melee), T2 (shoot), T3 (magic): [attackType - 1][0 = normal, 1 = skillType 3] -> body-action codes. */
    private static final int[][][] T1 = {
            {{5, 6, 7, 16, 17}, {8, 18}}, {{10, 13, 14, 19, 20}, {15, 21}}, {{9, 11}, {27}}, {{9, 19}, {}},
            {{9, 10, 11, 16, 17}, {12, 18}}, {{6}, {}}, {{16, 17}, {}}, {{16, 17}, {}}, {{9, 10, 10}, {}}};
    private static final int[][][] T2 = {
            {{}, {}}, {{}, {}}, {{22}, {27}}, {{23}, {23}}, {{}, {}}, {{}, {}}, {{24, 25, 26}, {24, 25, 26}},
            {{}, {}}, {{93}, {93}}};
    private static final int[][][] T3 = {
            {{28, 29}, {}}, {{}, {}}, {{}, {}}, {{}, {}}, {{}, {}}, {{28, 29}, {}}, {{}, {}}, {{}, {}}, {{}, {}}};

    /* is_keydown_skill (0x4fb08f). */
    static final Set<Integer> KEYDOWN = Set.of(1121001, 1221001, 1321001, 2121001, 2221001, 2321001, 3121004, 3221001,
            5101004, 5201002, 5221004, 13111002, 14111006, 15101003, 22121000, 22151001);
    /* OnAttack keeps the attacker's keydown pose for these (0x980452): Hurricane, Rapid Fire, Spark?, WA Hurricane. */
    static final Set<Integer> KEEPS_KEYDOWN_POSE = Set.of(3121004, 5221004, 15111006, 13111002);
    /* OnAttack draws these without CheckAttackAction (0x980722): Heal and the keydown/thrown skills. */
    static final Set<Integer> NO_ACTION_CHECK = Set.of(2301002, 3121004, 5221004, 5201002, 15111006, 13111002, 14111006);
    /* Shot without a bullet (0x7668b7): the shoot-route skills that throw nothing from the inventory. */
    static final Set<Integer> SHOOT_NO_BULLET = Set.of(3100001, 3110001, 3111004, 3200001, 3210001, 3211004, 4121003,
            4221003, 5121002, 5201006, 5211004, 5211005, 5221007, 5221008, 11101004, 13101002, 13101005, 13111000,
            14101006, 15111006, 15111007, 21100004, 21110004, 21120006);
    /* Shot without a shooting weapon (0x76681d): a pole-arm / knuckle / sword user's shoot-route skills. */
    static final Set<Integer> SHOOT_WITHOUT_WEAPON = Set.of(4121003, 4221003, 5121002, 11101004, 15111006, 15111007,
            21100004, 21110004, 21120006);

    /* A weapon's Character.wz info: its "attack" type, "attackSpeed" and "afterImage" (melee needs one). */
    public record WeaponInfo(int attackType, int attackSpeed, String afterImage) {
        static final WeaponInfo NONE = new WeaponInfo(0, 0, "");
    }

    /* A skill level's mobCount, attackCount and bulletCount (0 = the data has none). */
    public record Counts(int mobs, int attacks, int bullets) {
        static final Counts NONE = new Counts(0, 0, 0);
    }

    /* Where a skill level's counts come from: the server's skill data (the client's is the same), or a test's. */
    public interface CountSource {
        Counts counts(int skillId, int level);
    }

    private static volatile CountSource countSource = BotClientPoses::readCounts;

    static void useCountSource(CountSource source) {
        countSource = source;
    }

    public static Counts counts(int skillId, int level) {
        if (skillId <= 0) {
            return Counts.NONE;
        }
        Counts c = countSource.counts(skillId, Math.max(1, level));
        return c != null ? c : Counts.NONE;
    }

    private static Counts readCounts(int skillId, int level) {
        try {
            client.Skill skill = client.SkillFactory.getSkill(skillId);
            server.StatEffect e = skill != null ? skill.getEffect(level) : null;
            return e == null ? null : new Counts(e.getMobCount(), e.getAttackCount(), e.getBulletCount());
        } catch (RuntimeException | LinkageError e) {
            return null; // no skill data (a test without game data)
        }
    }

    private static volatile IntFunction<WeaponInfo> weaponSource = BotClientPoses::readWeapon;
    private static final Map<Integer, Optional<WeaponInfo>> weapons = new ConcurrentHashMap<>();

    /* Back to the server's own Character.wz and skill data (tests that compare them with the client's). */
    static void useServerData() {
        useWeaponSource(BotClientPoses::readWeapon);
        useCountSource(BotClientPoses::readCounts);
    }

    /* Replaces where weapon data comes from (tests); clears the cache. */
    static void useWeaponSource(IntFunction<WeaponInfo> source) {
        weaponSource = source;
        weapons.clear();
    }

    /* The weapon's info, or the class default (1H 1 ... gun 9, speed 6, an afterimage) when the data has none. */
    public static WeaponInfo weapon(int weaponItemId) {
        if (weaponItemId <= 0) {
            return WeaponInfo.NONE;
        }
        return weapons.computeIfAbsent(weaponItemId, id -> Optional.ofNullable(weaponSource.apply(id)))
                .orElseGet(() -> new WeaponInfo(defaultAttackType(weaponItemId), 6, "default"));
    }

    /* The client's attack type of a weapon class (item / 10000 % 100), 0 for none. */
    public static int defaultAttackType(int weaponItemId) {
        int wt = BotSkillActions.weaponClass(weaponItemId);
        if (wt >= 30 && wt <= 33) {
            return 1;
        }
        return switch (wt) {
            case 37, 38 -> 6;
            case 40, 41, 42 -> 5;
            case 43, 44 -> 2;
            case 45 -> 3;
            case 46 -> 4;
            case 47 -> 7;
            case 48 -> 8;
            case 49 -> 9;
            default -> 0;
        };
    }

    /* The plain poses a local player's client picks from for this route (T1 melee, T2 shoot, T3 magic). */
    public static List<Integer> plainPoses(BotAttackProfile.Route route, int attackType, boolean skillType3) {
        if (attackType < 1 || attackType > 9) {
            return List.of();
        }
        int[][][] table = switch (route) {
            case CLOSE -> T1;
            case RANGED -> T2;
            case MAGIC -> T3;
        };
        return toList(table[attackType - 1][skillType3 ? 1 : 0]);
    }

    /* Every plain pose a viewer's client accepts for a skill without its own action (T1 + T2 + T3). */
    public static List<Integer> acceptedPlainPoses(int attackType, boolean skillType3) {
        if (attackType < 1 || attackType > 9) {
            return List.of();
        }
        int row = skillType3 ? 1 : 0;
        java.util.List<Integer> all = new java.util.ArrayList<>();
        for (int[][][] table : new int[][][][]{T1, T2, T3}) {
            for (int code : table[attackType - 1][row]) {
                all.add(code);
            }
        }
        return all;
    }

    public static boolean isKeydown(int skillId) {
        return KEYDOWN.contains(skillId);
    }

    public static boolean keepsKeydownPose(int skillId) {
        return KEEPS_KEYDOWN_POSE.contains(skillId);
    }

    public static boolean skipsActionCheck(int skillId) {
        return NO_ACTION_CHECK.contains(skillId);
    }

    public static boolean shootsWithoutBullet(int skillId) {
        return SHOOT_NO_BULLET.contains(skillId);
    }

    public static boolean shootsWithoutShootingWeapon(int skillId) {
        return SHOOT_WITHOUT_WEAPON.contains(skillId);
    }

    /* bow, crossbow, claw, gun (0x8c470d): the weapons the shoot route draws from. */
    public static boolean isShootingWeapon(int weaponItemId) {
        int wt = BotSkillActions.weaponClass(weaponItemId);
        return wt == 45 || wt == 46 || wt == 47 || wt == 49;
    }

    /*
     * The attack speed a player holding this weapon sends. MapleRoot's client hooks 0x765066 and sets it from the
     * weapon class (BotAttackTiming.speedDegree); with -Dslums.botattacktiming=false, the stock rule: the weapon's
     * attackSpeed, clamped to 2..10.
     */
    public static int actionSpeed(int weaponItemId) {
        return actionSpeed(weaponItemId, 0);
    }

    /* As actionSpeed, with Speed Infusion's x (partyBooster, -1 or -2) on top under MapleRoot's rule. */
    public static int actionSpeed(int weaponItemId, int partyBooster) {
        if (BotAttackTiming.enabled() && weaponItemId > 0) {
            return BotAttackTiming.speedDegree(weaponItemId, partyBooster);
        }
        int speed = weapon(weaponItemId).attackSpeed();
        if (speed <= 0) {
            return BotAttackData.DEFAULT_ATTACK_SPEED;
        }
        return Math.max(2, Math.min(10, speed));
    }

    private static List<Integer> toList(int[] codes) {
        java.util.List<Integer> list = new java.util.ArrayList<>(codes.length);
        for (int code : codes) {
            list.add(code);
        }
        return List.copyOf(list);
    }

    private static WeaponInfo readWeapon(int itemId) {
        try {
            DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.CHARACTER);
            Data data = provider.getData(String.format("Weapon/%08d.img", itemId));
            Data info = data != null ? data.getChildByPath("info") : null;
            if (info == null) {
                return null;
            }
            return new WeaponInfo(DataTool.getIntConvert("attack", info, 0),
                    DataTool.getIntConvert("attackSpeed", info, 0), DataTool.getString("afterImage", info, ""));
        } catch (RuntimeException e) {
            return null; // no Character.wz (a test without game data) or no such weapon
        }
    }
}
