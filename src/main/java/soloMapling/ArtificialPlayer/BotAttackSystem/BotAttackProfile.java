package soloMapling.ArtificialPlayer.BotAttackSystem;

/*
 * Original fixed-damage attack profile; the attack-plan concept is inspired by GreenCatMS. Credit: NutNNut for the idea.
 * One bot attack: its route, the skill that renders, how many mobs/lines it hits, its reach box,
 * and timing. Pure data - build them with the per-route factories (melee/meleeAoe/magic/ranged/...)
 * so reach/cooldown defaults stay in one place. Per-line damage is NOT a property of the profile;
 * it comes from the bot's real stats, gear, skills and buffs (BotDamage, real bots 07).
 */
public final class BotAttackProfile {

    public enum Route { CLOSE, RANGED, MAGIC }

    public final Route route;
    public final int numAttacked;   // mobs hit (1 = single-target, >1 = AoE)
    public final int numDamage;     // damage lines per mob
    public final int reachX;
    public final int reachY;
    public final int cooldownMs;
    public final short hitDelayMs;
    public final int speed;
    public final int skillId;       // 0 = no-skill basic swing
    public final int skillLevel;
    // The skill the bot must hold to use this attack: the attack's own skill, except for the ids a client sends for
    // another skill (Aran's Overswing / Full Swing double and triple hits are sent as hidden 2112000x / 2111000x ids
    // while the player holds Overswing 21120002 / Full Swing 21110002).
    public final int heldSkill;

    private BotAttackProfile(Route route, int numAttacked, int numDamage, int reachX, int reachY,
                             int cooldownMs, short hitDelayMs, int speed, int skillId) {
        this.route = route;
        this.numAttacked = numAttacked;
        this.numDamage = numDamage;
        this.reachX = reachX;
        this.reachY = reachY;
        this.cooldownMs = cooldownMs;
        this.hitDelayMs = hitDelayMs;
        this.speed = speed;
        this.skillId = skillId;
        this.skillLevel = skillId == 0 ? 0 : SKILL_LEVEL;
        this.heldSkill = skillId;
    }

    private BotAttackProfile(BotAttackProfile base, int heldSkill) {
        this.route = base.route;
        this.numAttacked = base.numAttacked;
        this.numDamage = base.numDamage;
        this.reachX = base.reachX;
        this.reachY = base.reachY;
        this.cooldownMs = base.cooldownMs;
        this.hitDelayMs = base.hitDelayMs;
        this.speed = base.speed;
        this.skillId = base.skillId;
        this.skillLevel = base.skillLevel;
        this.heldSkill = heldSkill;
    }

    /* This attack, usable while the bot holds heldSkill (a hidden id the client sends for that skill). */
    public BotAttackProfile heldAs(int skill) {
        return new BotAttackProfile(this, skill);
    }

    // Level only identifies the skill to the viewer's client; the line/mob count is sent in the packet. This is
    // the default for a bot that does not hold the skill; BotAttackDriver.packetLevel sends the bot's own level
    // when it has one and caps this one at the skill's maximum (MapleRoot's custom skills stop at 10).
    private static final int SKILL_LEVEL = 20;
    private static final int AOE_MOBS = 6;          // mobs an AoE swing hits
    private static final int MELEE_SPEED = 4;

    // ----- per-route factories (route defaults live here, not in the registry) -----
    public static BotAttackProfile melee(int skill, int lines) {
        return new BotAttackProfile(Route.CLOSE, 1, lines, 90, 60, 720, (short) 300, MELEE_SPEED, skill);
    }

    public static BotAttackProfile meleeAoe(int skill, int lines) {
        return new BotAttackProfile(Route.CLOSE, AOE_MOBS, lines, 130, 80, 780, (short) 320, MELEE_SPEED, skill);
    }

    // A multi-target primary swing with a caller-set mob cap - a few-target skill that players main for
    // mobbing (Spear Crusher, Raging Blow). Faster cooldown than the AoE factories since it's the
    // bread-and-butter attack, not an occasional pack-clear. Reach is a fallback only; melee reads the
    // skill's real WZ box when it has one.
    public static BotAttackProfile meleeMulti(int skill, int mobs, int lines) {
        return new BotAttackProfile(Route.CLOSE, mobs, lines, 130, 80, 720, (short) 300, MELEE_SPEED, skill);
    }

    // A no-skill basic swing (skillId 0) - the plain melee attack a Beginner makes with a sword
    // before any job skills exist. Single target, one line, melee reach. The render path uses the
    // weapon's own swing keyframe (BotAttackData.actionFor) and the profile's reach box, since there
    // is no skill animation or WZ range to read.
    public static BotAttackProfile basicSwing() {
        return new BotAttackProfile(Route.CLOSE, 1, 1, 90, 60, 720, (short) 300, MELEE_SPEED, 0);
    }

    public static BotAttackProfile ranged(int skill, int lines) {
        return new BotAttackProfile(Route.RANGED, 1, lines, 450, 150, 780, (short) 340, MELEE_SPEED, skill);
    }

    public static BotAttackProfile rangedAoe(int skill, int lines) {
        return new BotAttackProfile(Route.RANGED, AOE_MOBS, lines, 450, 200, 820, (short) 360, MELEE_SPEED, skill);
    }

    public static BotAttackProfile magic(int skill, int lines) {
        return new BotAttackProfile(Route.MAGIC, 1, lines, 450, 150, 840, (short) 360, MELEE_SPEED, skill);
    }

    // A shot that leaves from a non-shooting weapon (Aran's combo finishers, Shark Wave, Energy Orb): the client sends
    // it on the shoot route with no bullet (BotClientPoses.SHOOT_WITHOUT_WEAPON). Reach is a fallback; the AoE box of
    // the skill's WZ range governs melee-like reach.
    public static BotAttackProfile shotAoe(int skill, int mobs, int lines) {
        return new BotAttackProfile(Route.RANGED, mobs, lines, 300, 150, 820, (short) 360, MELEE_SPEED, skill);
    }

    public static BotAttackProfile magicAoe(int skill, int lines) {
        return new BotAttackProfile(Route.MAGIC, AOE_MOBS, lines, 450, 210, 900, (short) 380, MELEE_SPEED, skill);
    }
}
