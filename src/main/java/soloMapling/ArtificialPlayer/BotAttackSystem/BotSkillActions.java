package soloMapling.ArtificialPlayer.BotAttackSystem;

import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;

/*
 * What a viewer's client accepts in another character's attack packet: the body action (pose) and the weapon.
 *
 * A v83 client checks a remote attack before it draws anything (CUserRemote::OnAttack calls 0x92edb2 in MapleRoot's
 * MapleStory.exe). The pose must be one the skill allows: the level's own Skill.wz "action" when the level has one
 * (Spear Crusher's burster1/burster2), else one of the skill's "action" names, else - for a skill with no action the
 * client knows - one of the weapon's plain attack poses. The attacker's weapon must fit the skill: its Skill.wz
 * "weapon", or a few hard-coded pirate, Dawn Warrior and Blaze Wizard groups. When the pose fails the whole attack is
 * dropped - no pose, no skill effect, no damage numbers; when the weapon fails too, the viewer's chat also gets "This
 * skill cannot be used with the equipped item." Bots that sent a weapon swing for a skill with its own pose (Rising
 * Rage, Moon Dancer, Impaling Rays, ...) fought invisibly.
 *
 * The data comes from the server's Skill.wz, read once per job image and cached; a test supplies its own source.
 */
public final class BotSkillActions {

    private BotSkillActions() {}

    /*
     * The client's body-action codes in order: an action's code is its index here. Read from the action table
     * MapleStory.exe builds at 0x4a38e7 (0xbec620, 24-byte entries). Codes 24-26 and 28-30 repeat earlier names
     * (the claw and wand forms of the swings); a name resolves to its first code, as the client's own lookup does.
     * A Skill.wz action outside this list (MapleRoot's SMsolarPierce, SMsolLunaSlash, STtypoon) is unknown to the
     * client, which then treats the skill as having no action of its own.
     */
    static final List<String> CLIENT_ACTIONS = List.of(
            "walk1", "walk2", "stand1", "stand2", "alert", "swingO1", "swingO2", "swingO3", "swingOF", "swingT1",
            "swingT2", "swingT3", "swingTF", "swingP1", "swingP2", "swingPF", "stabO1", "stabO2", "stabOF", "stabT1",
            "stabT2", "stabTF", "shoot1", "shoot2", "swingO1", "swingO2", "swingO3", "shootF", "swingO1", "swingO3",
            "stabO1", "heal", "proneStab", "prone", "fly", "jump", "ladder", "rope", "dead", "sit",
            "tired", "alert2", "alert3", "alert4", "alert5", "alert6", "ladder2", "rope2", "shoot6", "magic1",
            "magic2", "magic3", "magic5", "burster1", "burster2", "savage", "avenger", "assaulter", "prone2", "assassination",
            "assassinationS", "rush", "rush2", "brandish1", "brandish2", "sanctuary", "meteor", "paralyze", "blizzard", "genesis",
            "ninjastorm", "blast", "holyshield", "showdown", "resurrection", "chainlightning", "smokeshell", "handgun", "somersault", "straight",
            "eburster", "backspin", "eorb", "screw", "doubleupper", "dragonstrike", "doublefire", "triplefire", "fake", "airstrike",
            "edrain", "octopus", "backstep", "shot", "recovery", "fireburner", "coolingeffect", "fist", "timeleap", "rapidfire",
            "homing", "ghostwalk", "ghoststand", "ghostjump", "ghostproneStab", "ghostfly", "ghostladder", "ghostrope", "ghostsit", "cannon",
            "torpedo", "darksight", "bamboo", "pyramid", "wave", "blade", "souldriver", "firestrike", "flamegear", "stormbreak",
            "vampire", "float", "swingT2PoleArm", "swingP1PoleArm", "swingP2PoleArm", "doubleSwing", "tripleSwing", "fullSwingDouble", "fullSwingTriple", "overSwingDouble",
            "overSwingTriple", "rollingSpin", "comboSmash", "comboFenrir", "comboTempest", "finalCharge", "combatStep", "finalBlow", "finalToss", "magicmissile",
            "dragonSpark", "dragonBreathe", "breathe_prepare", "dragonIceBreathe", "icebreathe_prepare", "infinityExplosion", "superMagicmissile", "illusion", "magicFlare", "elementalReset",
            "elementalRegistance", "dragonAura", "magicBooster", "dragonShield", "dragonFury", "dragonFly", "dragonSkin", "shockwave", "demolition", "snatch",
            "windspear", "windshot");

    private static final Map<String, Integer> CODE_BY_NAME = new HashMap<>();

    static {
        for (int code = 0; code < CLIENT_ACTIONS.size(); code++) {
            CODE_BY_NAME.putIfAbsent(CLIENT_ACTIONS.get(code), code);
        }
    }

    /* A skill's pose and weapon data: its "action" names, any per-level "action", and its "weapon" (0 = any). */
    public record SkillPose(List<String> actions, Map<Integer, String> levelActions, int weapon) {
        public static final SkillPose NONE = new SkillPose(List.of(), Map.of(), 0);
    }

    private static volatile IntFunction<SkillPose> source = BotSkillActions::readWz;
    private static final Map<Integer, Optional<SkillPose>> poses = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Data>> images = new ConcurrentHashMap<>();

    /* Replaces where the skill data comes from (tests); clears the cache. */
    static void useSource(IntFunction<SkillPose> newSource) {
        source = newSource;
        poses.clear();
    }

    /* The client's code for an action name, or -1 when the client does not know it. */
    public static int codeOf(String action) {
        return action == null ? -1 : CODE_BY_NAME.getOrDefault(action, -1);
    }

    /* The action name the client draws for a body-action code, or null for a code outside its table. */
    public static String nameOf(int code) {
        return code >= 0 && code < CLIENT_ACTIONS.size() ? CLIENT_ACTIONS.get(code) : null;
    }

    /*
     * The body-action codes the client accepts for this skill at this level, or an empty list when any of the
     * weapon's plain attack poses will do (no skill, no action node, or only actions the client does not know).
     */
    public static List<Integer> poseCodes(int skillId, int skillLevel) {
        SkillPose pose = pose(skillId);
        String levelAction = pose.levelActions().get(skillLevel);
        if (levelAction != null) {
            int code = codeOf(levelAction);
            return code >= 0 ? List.of(code) : List.of();
        }
        List<Integer> codes = new ArrayList<>(pose.actions().size());
        for (String action : pose.actions()) {
            int code = codeOf(action);
            if (code >= 0) {
                codes.add(code);
            }
        }
        return codes;
    }

    // The client's hard-coded weapon groups (0x766157), which win over the skill's Skill.wz "weapon".
    private static final Set<Integer> KNUCKLE_OR_GUN_SKILLS = Set.of(5001001, 15001001);
    private static final Set<Integer> KNUCKLE_SKILLS = Set.of(5101002, 5101003, 5101004, 5111002, 5111006, 5121001,
            5121002, 5121004, 5121005, 5121007, 15101003, 15101005, 15111003, 15111004);
    private static final Set<Integer> SWORD_SKILLS = Set.of(11101004, 11111006);
    private static final Set<Integer> WAND_OR_STAFF_SKILLS = Set.of(12101006, 12111005, 12111006, 22101000, 22181000, 22181003);

    /* The client's weapon class of an item: 30 one-handed sword ... 49 gun; 0 for no weapon. */
    public static int weaponClass(int weaponItemId) {
        return weaponItemId <= 0 ? 0 : weaponItemId / 10000 % 100;
    }

    /* Whether a viewer's client lets a character holding this weapon use this skill. A no-skill swing always fits. */
    public static boolean weaponFits(int skillId, int weaponItemId) {
        if (skillId <= 0) {
            return true;
        }
        int wt = weaponClass(weaponItemId);
        if (KNUCKLE_OR_GUN_SKILLS.contains(skillId)) {
            return wt == 39 || wt == 48 || wt == 49;
        }
        if (KNUCKLE_SKILLS.contains(skillId)) {
            return wt == 39 || wt == 48;
        }
        if (SWORD_SKILLS.contains(skillId)) {
            return wt == 30 || wt == 40;
        }
        if (WAND_OR_STAFF_SKILLS.contains(skillId)) {
            return wt == 37 || wt == 38;
        }
        int required = pose(skillId).weapon();
        return required == 0 || required == wt;
    }

    static SkillPose pose(int skillId) {
        if (skillId <= 0) {
            return SkillPose.NONE;
        }
        return poses.computeIfAbsent(skillId, id -> Optional.ofNullable(source.apply(id))).orElse(SkillPose.NONE);
    }

    private static SkillPose readWz(int skillId) {
        Data skill = image(skillId / 10000).map(img -> img.getChildByPath("skill/" + String.format("%07d", skillId)))
                .orElse(null);
        if (skill == null) {
            return null;
        }
        List<String> actions = new ArrayList<>();
        Data action = skill.getChildByPath("action");
        if (action != null) {
            for (Data name : action.getChildren()) {
                if (name.getData() instanceof String s) {
                    actions.add(s);
                }
            }
        }
        Map<Integer, String> levelActions = new HashMap<>();
        Data levels = skill.getChildByPath("level");
        if (levels != null) {
            for (Data level : levels.getChildren()) {
                String levelAction = DataTool.getString("action", level, null);
                if (levelAction != null) {
                    try {
                        levelActions.put(Integer.parseInt(level.getName()), levelAction);
                    } catch (NumberFormatException ignored) {
                        // not a level entry
                    }
                }
            }
        }
        return new SkillPose(List.copyOf(actions), Map.copyOf(levelActions), DataTool.getIntConvert("weapon", skill, 0));
    }

    private static Optional<Data> image(int job) {
        return images.computeIfAbsent(String.format("%03d.img", job), name -> {
            try {
                DataProvider provider = DataProviderFactory.getDataProvider(WZFiles.SKILL);
                return Optional.ofNullable(provider.getData(name));
            } catch (RuntimeException e) {
                return Optional.empty(); // no such job image, or no Skill.wz (a test without game data)
            }
        });
    }
}
