package soloMapling.ArtificialPlayer.BotAttackSystem;

/*
 * Everything a bot's attack packet(s) carry that a viewer's client looks at, worked out the way a player's own client
 * would fill them in (ticket 32; docs/real-bots/client-remote-attack-rules.md):
 *
 * - the route (melee / shot / spell packet) the player's client sends the skill on (profile.route, which
 *   BotAttackClientValidityTest checks against the exe's DoActiveSkill switch and MapleRoot's custom-skill table);
 * - the pose: the skill's own (a level's action, else one of its actions; Brandish/Raging Blow by the weapon), else
 *   one of the weapon's plain poses for that route (BotAttackData.actionFor);
 * - the attack speed: the weapon's attackSpeed (BotClientPoses.actionSpeed);
 * - for a keydown skill: the prepare packet (its keydown pose, 0x7fff when the client knows none) before the shots,
 *   and for Hurricane / Rapid Fire a stream of shots and a release packet;
 * - the mob and line counts the skill's level allows (mobCount, attackCount or bulletCount).
 *
 * A skill the weapon cannot use, or one with no pose a viewer accepts, is sent as the weapon's plain attack instead:
 * the viewer would drop it (and, for the weapon, say so in chat).
 */
public record BotSwingPlan(BotAttackProfile.Route route, int skillId, int skillLevel, int action, boolean facingLeft,
                           int speed, boolean prepare, int prepareAction, int shots, int maxMobs, int lines) {

    /* Shots of a held keydown skill (Hurricane, Rapid Fire) in one burst, and the gap between them. */
    public static final int KEYDOWN_SHOTS = 6;
    public static final long KEYDOWN_SHOT_MS = 120;

    /* This plan with another pose (tests walk every pose a plan may pick). */
    public BotSwingPlan withAction(int pose) {
        return new BotSwingPlan(route, skillId, skillLevel, pose, facingLeft, speed, prepare, prepareAction, shots,
                maxMobs, lines);
    }

    public int facingMask() {
        return facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
    }

    /* The keydown packet's pose field: the pose code, the left-facing bit on top (byte 2 bit 7 = bit 15). */
    public int prepareActionAndFacing() {
        return (prepareAction & 0x7fff) | (facingLeft ? 0x8000 : 0);
    }

    /* How long the swing keeps the bot busy beyond its normal cooldown: the rest of a keydown burst. */
    public long burstMs() {
        return shots > 1 ? (shots - 1) * KEYDOWN_SHOT_MS : 0;
    }

    /*
     * The plan for one swing of profile at skillLevel with the weapon held (item id), facing as given. linesHint is the
     * profile's line count (doubled by Shadow Partner by the caller) used when the skill data has none.
     */
    public static BotSwingPlan of(BotAttackProfile profile, int skillLevel, int weaponItemId, boolean facingLeft,
                                  int linesHint) {
        int skillId = profile.skillId;
        BotAttackProfile.Route route = profile.route;
        int level = skillLevel;
        int action = -1;
        if (skillId != 0 && BotSkillActions.weaponFits(skillId, weaponItemId)) {
            action = BotAttackData.actionFor(skillId, level, weaponItemId, route);
        }
        if (action < 0 || (route == BotAttackProfile.Route.RANGED && !BotClientPoses.isShootingWeapon(weaponItemId)
                && !BotClientPoses.shootsWithoutShootingWeapon(skillId))) {
            // Not drawable as the skill: the weapon's plain attack (a shooter shoots, everyone else swings).
            skillId = 0;
            level = 0;
            route = BotClientPoses.isShootingWeapon(weaponItemId) && profile.route == BotAttackProfile.Route.RANGED
                    ? BotAttackProfile.Route.RANGED : BotAttackProfile.Route.CLOSE;
            action = BotAttackData.actionFor(0, 0, weaponItemId, route);
        }
        int speed = BotClientPoses.actionSpeed(weaponItemId);
        boolean prepare = skillId != 0 && (BotClientPoses.isKeydown(skillId) || BotSkillActions.hasPrepare(skillId));
        int shots = skillId != 0 && BotClientPoses.keepsKeydownPose(skillId) ? KEYDOWN_SHOTS : 1;
        int maxMobs = profile.numAttacked;
        int lines = linesHint;
        if (skillId == 0) {
            maxMobs = 1;
            lines = Math.max(1, Math.min(linesHint, 2)); // a plain swing: one mob; a shadow-partnered throw draws two
        } else {
            BotClientPoses.Counts c = BotClientPoses.counts(skillId, level);
            if (c.mobs() > 0) {
                maxMobs = Math.min(maxMobs, c.mobs());
            }
            if (c.attacks() > 0 || c.bullets() > 0) {
                // a claw / bow / gun skill draws a line per bullet, a swing one per attack (the server's data
                // defaults the missing one to 1)
                int count = Math.max(c.attacks(), c.bullets());
                if (linesHint > profile.numDamage) { // Shadow Partner doubles what the client draws
                    count *= 2;
                }
                lines = Math.min(15, count);
            }
        }
        return new BotSwingPlan(route, skillId, level, action, facingLeft, speed, prepare,
                prepare ? BotSkillActions.prepareCode(skillId) : BotSkillActions.NO_ACTION, shots,
                Math.max(1, maxMobs), Math.max(1, lines));
    }
}
