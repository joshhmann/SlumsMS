package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import client.Skill;
import client.SkillFactory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.game.GameConstants;
import constants.skills.Cleric;
import constants.skills.Hermit;
import constants.skills.NightWalker;
import server.StatEffect;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.SwingSupply;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Targeting and route-dispatch model adapted from GreenCatMS bot combat. Credit: NutNNut.
 * Bot attack driver. Resolves the bot's attacks from its class + weapon (BotAttackConfig),
 * then each tick picks the AoE skill when 3+ mobs are in reach (BotSkillChoice; 2+ with BOT_COMBAT_HUMAN off) else
 * the single-target one, faces the nearest, rolls each line from the bot's real numbers (BotDamage), and strikes via the
 * profile's route (melee / ranged / magic). Re-attacks are throttled per bot via
 * nextAttackByBot; clearBot() releases a despawned bot's timer. botAttack() is
 * cooldown-gated; forceAttack() ignores it for the !bot attack GM test.
 */
public final class BotAttackDriver {

    // botId -> absolute epoch-ms before which this bot may not swing again.
    private static final Map<Integer, Long> nextAttackByBot = new ConcurrentHashMap<>();

    // Full-map ultimates (Dragon Roar / Genesis / Blizzard / Meteor Shower) clear the entire map in
    // one cast, so a grinding bot shouldn't fire one every swing. On top of the normal per-swing
    // cooldown they carry this much longer, separate cooldown; while it's cooling, AUTO keeps mobbing
    // with the class's sustained AoE (Crusher / Shining Ray / Ice Strike / Explosion) instead of
    // dropping to single-target. ~25s lets a bot use its ultimate roughly twice a minute.
    // botId -> absolute epoch-ms before which this bot's full-map ultimate is unavailable.
    private static final long FULL_MAP_ULTIMATE_COOLDOWN_MS = 25_000;
    private static final Map<Integer, Long> nextUltimateByBot = new ConcurrentHashMap<>();

    // A skill whose WZ data carries a cooltime (most of MapleRoot's custom attacks: Megiddo Flame, La Mancha
    // Spear, High Speed Shot, ...) is not cast again before that cooltime has passed; the bot swings one of
    // its other attacks meanwhile. botId -> (skillId -> epoch-ms the skill is ready again).
    private static final Map<Integer, Map<Integer, Long>> skillReadyByBot = new ConcurrentHashMap<>();

    // botId -> (extra skillId -> epoch-ms the bot last used it), for BotAttackConfig.Extra.everyMs.
    private static final Map<Integer, Map<Integer, Long>> extraUsedByBot = new ConcurrentHashMap<>();

    // botId -> epoch-ms of its last swing, kept only while the !qa attack log is on (its "gap" field: the cadence a
    // viewer sees, swing to swing).
    private static final Map<Integer, Long> lastSwingAtByBot = new ConcurrentHashMap<>();
    /*
     * 1.6.1 ticket 13 (owned alts): mobs a bot may swing at, per bot. An owned alt fights only mobs that are not a
     * stranger's (OwnedAltCombat), and that holds for every mob an AoE catches, not only the one it walked to. Empty for
     * every population bot (one map lookup a swing).
     */
    private static final Map<Integer, java.util.function.Predicate<Monster>> targetFilters = new ConcurrentHashMap<>();

    // Candidate pre-filter radius (px); the per-profile reach box is the hard gate.
    private static final int SEEK_RANGE = 700;
    private static final double SEEK_RANGE_SQ = (double) SEEK_RANGE * SEEK_RANGE;

    // A forward (non-surround) attack still reaches a hair behind the bot so a mob right on top
    // of it counts; mobs clearly behind are excluded (e.g. a crossbow won't fire backwards).
    private static final int BACK_MARGIN = 25;
    // A box reaches this far below the bot's feet, so a mob on the bot's own ground (same y) is inside it.
    private static final int FOOT_SLACK = 10;
    // Bosses are large and multi-bodied - Zakum's arms sit well above the bot's foothold - so
    // their part positions are tested against a vertically padded copy of the reach box.
    private static final int BOSS_Y_PAD = 300;
    // Two horizontal extents count as "centred on the bot" (a surround attack) when they match
    // within this slack (px).
    private static final int SYMMETRY_SLACK = 24;

    // Cleric Heal (2301002) damages undead as an AoE in v83 (client-authoritative). A cleric-line bot
    // casts it at an undead pack instead of its normal bolt; the MAGIC route renders the heal and applies
    // real damage like any bot magic attack. Heal is not a charge skill, so magicChargeFor(-1) already fits.
    private static final BotAttackProfile HEAL_PROFILE = BotAttackProfile.magicAoe(Cleric.HEAL, 1);

    private BotAttackDriver() {}

    /* Outcome of an attack attempt, for the GM command to report. */
    public record AttackResult(boolean hit, String monsterName, int damage, boolean killed, String reason, int skillId) {
        static AttackResult hit(String name, int dmg, boolean killed, int skillId) {
            return new AttackResult(true, name, dmg, killed, null, skillId);
        }
        static AttackResult miss(String reason) {
            return new AttackResult(false, null, 0, false, reason, 0);
        }
    }

    /* Which configured attack a swing uses: AUTO smart-picks; SINGLE/AOE/ULTIMATE force one slot; PROFILE one attack. */
    public enum Choice { AUTO, SINGLE, AOE, ULTIMATE, PROFILE }

    /*
     * Cooldown-gated swing at the in-reach mobs (AUTO: AoE when 2+ mobs are in reach, else
     * single). Cheap and safe to call every tick; most ticks do nothing. The FSM entry point.
     */
    public static AttackResult botAttack(Character bot) {
        long startedAt = System.nanoTime();
        try {
            AttackResult result = attack(bot, false, Choice.AUTO);
            BotAttackStats.record(bot, result);
            return result;
        } finally {
            BotPerformanceMonitor.recordCombatDamage(System.nanoTime() - startedAt);
        }
    }

    /* Force the SINGLE-target attack now, ignoring cooldown (backs !bot attack <id>). */
    public static AttackResult forceSingle(Character bot) {
        return attack(bot, true, Choice.SINGLE);
    }

    /* Force the sustained AoE attack now, ignoring cooldown (backs !bot attackaoe <id>). */
    public static AttackResult forceAoe(Character bot) {
        return attack(bot, true, Choice.AOE);
    }

    /* Force the full-map ultimate now, ignoring both cooldowns (backs !bot attackult <id>). */
    public static AttackResult forceUltimate(Character bot) {
        return attack(bot, true, Choice.ULTIMATE);
    }

    /*
     * Swing exactly this attack now, ignoring cooldowns and combo (backs !botshowcase, which cycles a job's every
     * attack so a viewer can check each one is drawn).
     */
    public static AttackResult forceProfile(Character bot, BotAttackProfile profile) {
        return attack(bot, true, Choice.PROFILE, profile);
    }

    /* The item id of the weapon the bot holds, or 0. */
    public static int equippedWeaponId(Character bot) {
        Item weapon = bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        return weapon != null ? weapon.getItemId() : 0;
    }

    /* When the bot's last swing ends (its pose has played out): 0 when it has not swung. */
    public static long swingEndsAt(int botId) {
        return nextAttackByBot.getOrDefault(botId, 0L);
    }

    /*
     * A held keydown skill was released (its mobs died or left its reach, or it shot its last shot): the bot may press
     * again from then on, as a player can, instead of at the end of the longest hold.
     */
    static void releasedEarly(int botId, long releasedAt) {
        nextAttackByBot.computeIfPresent(botId, (k, until) -> Math.min(until, releasedAt));
    }

    /* Speed Infusion's x (-1 / -2) while the bot has it: MapleRoot's client adds it to the attack speed. */
    static int speedInfusion(Character bot) {
        Integer x = bot.getBuffedValue(client.BuffStat.SPEED_INFUSION);
        return x != null ? x : 0;
    }

    /* Release a despawned bot's cooldown timers so the maps don't grow unbounded. */
    public static void clearBot(int botId) {
        nextAttackByBot.remove(botId);
        nextUltimateByBot.remove(botId);
        skillReadyByBot.remove(botId);
        extraUsedByBot.remove(botId);
        lastSwingAtByBot.remove(botId);
        targetFilters.remove(botId);
        BotAranCombo.clearBot(botId);
        BotAttackStats.clear(botId);
        BotComboOrbs.clearBot(botId);
        OffscreenCombat.clearBot(botId);
        BotSupplies.clearBot(botId);
    }

    /*
     * The bot's effective forward attack reach in px (its single-target profile's reach, else its AoE's).
     * Roaming callers use this to approach only to within striking distance instead of always closing to
     * melee range - so a ranged/magic bot stops and attacks from afar. 0 if it has no configured attack.
     */
    public static int attackReachX(Character bot) {
        BotAttackProfile p = reachProfile(bot);
        return p != null ? p.reachX : 0;
    }

    /* The bot's effective vertical attack reach in px; pairs with attackReachX for approach gating. */
    public static int attackReachY(Character bot) {
        BotAttackProfile p = reachProfile(bot);
        return p != null ? p.reachY : 0;
    }

    /* What the bot holds, for BotAttackConfig's owned-skill resolution (real bots 06). */
    static java.util.function.IntPredicate ownedSkills(Character bot) {
        return skillId -> bot.getSkillLevel(skillId) > 0;
    }

    /* The attack the bot fights with: its single-target one, else its AoE, else its ultimate; null for none. */
    public static BotAttackProfile primaryAttack(Character bot) {
        return primaryProfile(bot);
    }

    // The attack whose reach the bot closes to: a ranged bot out of ammo swings in melee (real bots 08), so it has
    // to walk up to the mob instead of standing off at bow range.
    private static BotAttackProfile reachProfile(Character bot) {
        BotAttackProfile p = primaryProfile(bot);
        return p != null && BotSupplies.outOfAmmo(bot) ? BotAttackProfile.basicSwing() : p;
    }

    private static BotAttackProfile primaryProfile(Character bot) {
        if (bot == null) {
            return null;
        }
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(bot.getJob(), weapon, ownedSkills(bot));
        if (atks.single() != null) {
            return atks.single();
        }
        return atks.aoe() != null ? atks.aoe() : atks.ultimate();
    }

    /* True if this bot's class has any configured AoE swing (sustained or ultimate) - roaming callers use
     * it to decide whether to step into the middle of a mob pack before firing (the AoE-reposition flourish). */
    public static boolean hasAoeAttack(Character bot) {
        if (bot == null) {
            return false;
        }
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(bot.getJob(), weapon, ownedSkills(bot));
        return atks.aoe() != null || atks.ultimate() != null;
    }

    private static AttackResult attack(Character bot, boolean force, Choice choice) {
        return attack(bot, force, choice, null);
    }

    private static AttackResult attack(Character bot, boolean force, Choice choice, BotAttackProfile forced) {
        if (bot == null || bot.getMap() == null) {
            return AttackResult.miss("bot or map is null");
        }
        long now = System.currentTimeMillis();
        if (!force && now < nextAttackByBot.getOrDefault(bot.getId(), 0L)) {
            return AttackResult.miss("on cooldown");
        }

        // A bot holding a weapon its job does not fight with (a changed job, the casual look) takes a fitting one
        // first: the viewer's client draws none of the job's skills with the wrong weapon.
        BotWeapons.ensureFittingWeapon(bot);
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackConfig.JobAttacks atks = BotAttackConfig.resolve(bot.getJob(), weapon, ownedSkills(bot));
        BotAttackProfile single = atks.single();
        BotAttackProfile aoe = atks.aoe();
        BotAttackProfile ultimate = atks.ultimate(); // throttled full-map nuke, or null

        // Forced slots must exist; AUTO needs at least one configured attack.
        if (choice == Choice.SINGLE && single == null) {
            return AttackResult.miss(bot.getJob() + " has no single-target attack");
        }
        if (choice == Choice.AOE && aoe == null) {
            return AttackResult.miss(bot.getJob() + " has no AoE attack");
        }
        if (choice == Choice.ULTIMATE && ultimate == null) {
            return AttackResult.miss(bot.getJob() + " has no ultimate attack");
        }
        if (single == null && aoe == null && ultimate == null && forced == null) {
            return AttackResult.miss("no attack for job " + bot.getJob() + " / weapon " + weapon);
        }

        // Face the nearest mob BEFORE measuring reach, so directional attacks are oriented
        // toward the fight (the reach box is built relative to the bot's facing).
        Monster nearest = nearestMob(bot);
        if (nearest == null) {
            return AttackResult.miss("no targetable mobs within " + SEEK_RANGE + "px (none, or all on a separate ledge)");
        }
        // 1.5.0 rc3: a player turns to the mob before swinging; a swing never goes out the same tick as the turn, so
        // no attack is drawn facing away from the fight (the turn goes out now, the swing on a later tick).
        if (!force && choice == Choice.AUTO && turnsFirst(bot, nearest, now)) {
            return AttackResult.miss("turning to face '" + nearest.getName() + "'");
        }
        boolean facingLeft = faceTarget(bot, nearest.getPosition());

        // Pick the profile. Forced choices use exactly that slot; AUTO fires an AoE when 2+ mobs are in
        // reach, else the single-target attack - skipping any whose skill is still in its WZ cooltime.
        BotAttackProfile profile;
        boolean healUndead = false;
        if (choice == Choice.PROFILE) {
            profile = forced;
        } else if (choice == Choice.SINGLE) {
            profile = single;
        } else if (choice == Choice.AOE) {
            profile = aoe;
        } else if (choice == Choice.ULTIMATE) {
            profile = ultimate;
        } else if (isClericVsUndead(bot, nearest)) {
            profile = HEAL_PROFILE; // Heal-as-damage on the undead pack
            healUndead = true;
        } else {
            // The AoE we'd throw at a pack: the full-map ultimate when it's off its long separate
            // cooldown, otherwise the sustained mob attack (Crusher / Shining Ray / Ice Strike /
            // Explosion). While the ultimate cools, the bot keeps mobbing with the sustained AoE
            // instead of dropping to single-target. Only escalate to an AoE when 2+ mobs are in reach.
            boolean ultReady = ultimate != null && now >= nextUltimateByBot.getOrDefault(bot.getId(), 0L)
                    && offCooltime(bot, ultimate, now);
            BotAttackProfile packAttack = ultReady ? ultimate : ready(bot, aoe, now);
            boolean useAoe;
            if (BotCombatTuning.human()) {
                // 1.5.0 rc3 (owner decision 1): the AoE on 3+ mobs, the single-target skill on a boss or a lone mob,
                // and now and then the other one (the bot's own sloppiness)
                int inReach = packAttack != null ? mobsInReach(bot, packAttack, facingLeft).size() : 0;
                BotAttackProfile singleReady = ready(bot, single, now);
                boolean sloppy = java.util.concurrent.ThreadLocalRandom.current().nextDouble()
                        < BotHabits.of(bot.getId()).sloppyNow();
                BotSkillChoice.Pick pick = BotSkillChoice.choose(packAttack != null, inReach, nearest.isBoss(),
                        BotCombatTuning.aoeMinMobs(), singleReady != null, sloppy);
                useAoe = pick.aoe();
                if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.COMBAT)) {
                    soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.COMBAT, bot.getMapId(), "CHOICE " + bot.getName()
                            + " " + bot.getJob() + ": " + inReach + " in AoE reach" + (nearest.isBoss() ? ", boss" : "")
                            + " -> " + (useAoe ? "AOE " + packAttack.skillId : "SINGLE "
                            + (singleReady != null ? singleReady.skillId : 0)) + " (" + pick.why() + ")");
                }
            } else {
                useAoe = packAttack != null && mobsInReach(bot, packAttack, facingLeft).size() >= 2;
            }
            if (useAoe) {
                profile = packAttack;
            } else {
                profile = firstNonNull(ready(bot, single, now), ready(bot, aoe, now), ultReady ? ultimate : null);
                if (profile == null) {
                    return AttackResult.miss("every attack is in its cooltime");
                }
            }
        }

        // A rotation extra that is ready (WZ cooltime, its own interval, an Aran finisher's combo) and has a mob in
        // reach goes first (ticket 32): the Aran finishers, Buccaneer's Double Blast / Energy Orb, Corsair's Rapid
        // Fire, Thunder Breaker's Whale Rising ... - forced choices and the cleric's heal skip them.
        BotAttackConfig.Extra extra = null;
        if (choice == Choice.AUTO && !healUndead) {
            extra = readyExtra(bot, atks.extras(), facingLeft, now);
            if (extra != null) {
                profile = extra.profile();
            }
        }

        // Panic/Coma need Combo Attack orbs, and a viewer's client draws a finisher without them as a plain swing:
        // charge the orbs up with another attack first (BotComboOrbs), then finish on full orbs.
        if (BotComboOrbs.mustCharge(bot, profile.skillId)) {
            profile = comboCharger(single, profile == aoe, weapon);
        }

        // What the swing takes out of the bot (real bots 08): MP for the skill, ammo for a ranged swing. A bot that
        // cannot pay swings its plain attack instead (SwingSupply), and pays for what it swung once it is out.
        SwingSupply supply = SwingSupply.prepare(bot, profile, weapon, packetLevel(bot, profile));
        profile = supply.profile();

        // What the client would put in the packet(s) for this skill with this weapon (BotSwingPlan): route, pose,
        // speed, keydown packets, the counts the skill's level allows. A skill the weapon cannot draw goes out as the
        // weapon's plain attack (a viewer would drop it, and say so in chat for the weapon).
        int weaponItemId = equippedWeaponId(bot);
        int linesHint = shadowDoubled(bot, profile.numDamage);
        BotSwingPlan plan = BotSwingPlan.of(profile, packetLevel(bot, profile), weaponItemId, facingLeft, linesHint,
                speedInfusion(bot));
        BotAttackProfile reachAs = plan.skillId() == profile.skillId ? profile : BotAttackProfile.basicSwing();

        List<Monster> targets = cap(mobsInReach(bot, reachAs, facingLeft), plan.maxMobs());
        if (healUndead) {
            targets = undeadOnly(targets); // Heal must not damage a living mob caught in the box
        }
        if (targets.isEmpty()) {
            String why = healUndead ? "no undead in heal range" : reachDiagnostic(bot, reachAs, facingLeft);
            return AttackResult.miss(supply.fallback() != null ? why + " (" + supply.fallback() + ")" : why);
        }

        int skillId = plan.skillId();
        int skillLevel = plan.skillLevel();
        BotAttackProfile.Route route = plan.route();
        int projectile = route == BotAttackProfile.Route.RANGED && BotClientPoses.isShootingWeapon(weaponItemId)
                ? supply.projectile() : 0;

        // Roll the lines per mob from the bot's real numbers (BotDamage: stats, gear, skills, buffs, the mob's
        // defence); sum a total for the GM report. A crit line is encoded NEGATIVE (BotAttackData.encodeCritLine)
        // so the viewer renders a yellow crit; BotAttackEffects decodes the lines back before applying HP.
        int lines = plan.lines();
        BotDamage.Swing swing = BotDamage.swing(bot, skillId, skillLevel, route, weapon,
                targets.size() + 1, linesHint != profile.numDamage);
        Map<Monster, List<Integer>> hits = new LinkedHashMap<>();
        int reported = 0;
        for (Monster mob : targets) {
            List<Integer> rolled = swing.roll(mob, lines);
            for (int line : rolled) {
                reported = (int) Math.min(Integer.MAX_VALUE, (long) reported + BotAttackData.decodeDamageLine(line));
            }
            hits.put(mob, rolled);
        }

        final BotAttackProfile reach = reachAs;
        final boolean left = facingLeft;
        final int botId = bot.getId();
        boolean killed = BotAttackEffects.strike(bot, plan, hits, projectile, profile.hitDelayMs, () -> {
            Map<Monster, List<Integer>> more = new LinkedHashMap<>();
            for (Monster mob : cap(mobsInReach(bot, reach, left), plan.maxMobs())) {
                more.put(mob, swing.roll(mob, lines));
            }
            return more;
        }, releasedAt -> releasedEarly(botId, releasedAt));
        BotAranCombo.afterStrike(bot, skillId, targets.size() * Math.max(1, plan.shots()));
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.ATTACK)) { // !qa bk-1
            Long last = lastSwingAtByBot.put(bot.getId(), now);
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.ATTACK, bot.getMapId(), bot.getName() + " "
                    + bot.getJob() + " skill " + skillId + " lv " + skillLevel + " route " + route + " action "
                    + plan.action() + " facing " + (plan.facingLeft() ? "left" : "right") + " speed " + plan.speed()
                    + (plan.prepare() ? " prepare " + plan.prepareAction() : "") + " shots " + plan.shots()
                    + " weapon " + weaponItemId + " " + weapon + " projectile " + projectile + " mobs "
                    + targets.size() + " lines " + lines + " dmg " + reported + (killed ? " killed" : "")
                    + (supply.fallback() != null ? " fallback " + supply.fallback() : "")
                    + " busy " + plan.busyMs(profile.cooldownMs) + "ms"
                    + (last != null && now - last < 10_000 ? " gap " + (now - last) + "ms" : ""));
        }

        supply.commit(bot, route, skillId); // the ammo fired, the MP/HP the skill cost (and a potion if it is low)
        BotComboOrbs.afterStrike(bot, skillId); // after the packet: a finisher is drawn with the orbs it spends
        nextAttackByBot.put(bot.getId(), now + plan.busyMs(profile.cooldownMs)); // the pose's length (BotAttackTiming)
        startCooltime(bot, skillId, skillLevel, now);
        if (extra != null && skillId == extra.profile().skillId) {
            extraUsedByBot.computeIfAbsent(bot.getId(), k -> new ConcurrentHashMap<>()).put(skillId, now);
            BotAranCombo.spend(bot, extra.comboCost());
        }
        if (profile == ultimate) { // firing the throttled full-map nuke starts its long cooldown
            nextUltimateByBot.put(bot.getId(), now + FULL_MAP_ULTIMATE_COOLDOWN_MS);
        }
        String label = targets.size() > 1
                ? targets.size() + " mobs (nearest '" + targets.get(0).getName() + "')"
                : targets.get(0).getName();
        return AttackResult.hit(label, reported, killed, skillId);
    }

    /*
     * The attack a combo bot charges its orbs with instead of a finisher: its own single-target attack when that
     * isn't a finisher (Hero's Brandish), else the warrior basics - Slash Blast for a pack, Power Strike for one.
     */
    static BotAttackProfile comboCharger(BotAttackProfile single, boolean forPack, WeaponType weapon) {
        if (single != null && !GameConstants.isFinisherSkill(single.skillId)) {
            return single;
        }
        BotAttackConfig.JobAttacks basics = BotAttackConfig.resolve(Job.WARRIOR, weapon);
        return forPack ? basics.aoe() : basics.single();
    }

    /*
     * Double the damage lines while Shadow Partner is up, so the packet's line count matches the
     * mirrored shadow the client draws (Lucky Seven 2->4, Quadruple Throw 4->8). Gated on the bot
     * casting the buff (bot self-buffs are only shown, never registered, so getBuffedValue is always
     * null here). Only the claw lines (Hermit, Night Walker) carry Shadow Partner. Capped at the
     * packet's 4-bit line count.
     */
    static int shadowDoubled(Character bot, int baseLines) {
        List<Integer> buffs = BotBuffConfig.buffsFor(bot);
        boolean shadow = buffs.contains(Hermit.SHADOW_PARTNER) || buffs.contains(NightWalker.SHADOW_PARTNER);
        return shadow ? Math.min(MAX_LINES, baseLines * 2) : baseLines;
    }

    // The attack packet packs mobs and lines into one byte, four bits each.
    private static final int MAX_LINES = 15;

    /*
     * The skill level the attack packet names: the bot's own level of the skill when it holds it, else
     * the profile's default capped at the skill's maximum, since a level past it names an effect the
     * viewer's client does not have (MapleRoot's custom 4th-job skills stop at 10, below the default).
     */
    public static int packetLevel(Character bot, BotAttackProfile profile) {
        if (profile.skillId == 0) {
            return 0;
        }
        Skill skill = SkillFactory.getSkill(profile.skillId);
        int max = skill != null && skill.getMaxLevel() > 0 ? skill.getMaxLevel() : Integer.MAX_VALUE;
        int held = bot.getSkillLevel(profile.heldSkill); // a hidden id goes out at the level of the skill it stands for
        if (held > 0) {
            return Math.min(held, max);
        }
        return Math.min(profile.skillLevel, max);
    }

    /* The first extra that is ready to use now with a mob in its reach, or null. */
    private static BotAttackConfig.Extra readyExtra(Character bot, List<BotAttackConfig.Extra> extras,
                                                    boolean facingLeft, long now) {
        for (BotAttackConfig.Extra e : extras) {
            BotAttackProfile p = e.profile();
            if (!offCooltime(bot, p, now)) {
                continue;
            }
            Map<Integer, Long> used = extraUsedByBot.get(bot.getId());
            long last = used != null ? used.getOrDefault(p.skillId, 0L) : 0L;
            if (now - last < e.everyMs()) {
                continue;
            }
            if (e.comboCost() > 0 && BotAranCombo.combo(bot, now) < e.comboCost()) {
                continue;
            }
            if (!mobsInReach(bot, p, facingLeft).isEmpty()) {
                return e;
            }
        }
        return null;
    }

    /* The profile when its skill is out of its WZ cooltime, else null. */
    private static BotAttackProfile ready(Character bot, BotAttackProfile profile, long now) {
        return profile != null && offCooltime(bot, profile, now) ? profile : null;
    }

    private static boolean offCooltime(Character bot, BotAttackProfile profile, long now) {
        Map<Integer, Long> ready = skillReadyByBot.get(bot.getId());
        return ready == null || now >= ready.getOrDefault(profile.skillId, 0L);
    }

    /* Starts the skill's WZ cooltime (seconds, at the level cast) when it has one. */
    private static void startCooltime(Character bot, int skillId, int skillLevel, long now) {
        if (skillId == 0) {
            return;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        StatEffect effect = skill != null ? skill.getEffect(Math.max(1, skillLevel)) : null;
        int seconds = effect != null ? effect.getCooldown() : 0;
        if (seconds > 0) {
            skillReadyByBot.computeIfAbsent(bot.getId(), k -> new ConcurrentHashMap<>())
                    .put(skillId, now + seconds * 1000L);
        }
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... options) {
        for (T option : options) {
            if (option != null) {
                return option;
            }
        }
        return null;
    }

    // A cleric-line bot (Cleric/Priest/Bishop) whose nearest target is undead: cast Heal-as-damage instead
    // of the normal bolt. Undead is a per-mob WZ flag reached via getStats() (there is no Monster.isUndead).
    private static boolean isClericVsUndead(Character bot, Monster nearest) {
        return nearest != null && bot.getJob() != null && bot.getJob().isA(Job.CLERIC)
                && nearest.getStats() != null && nearest.getStats().isUndead();
    }

    // Keep only the undead mobs — Heal damages undead but would otherwise "heal" a living mob in the box.
    private static List<Monster> undeadOnly(List<Monster> mobs) {
        List<Monster> out = new ArrayList<>(mobs.size());
        for (Monster m : mobs) {
            if (m.getStats() != null && m.getStats().isUndead()) {
                out.add(m);
            }
        }
        return out;
    }

    /* Alive mobs inside the attack's reach box, nearest first. */
    private static List<Monster> mobsInReach(Character bot, BotAttackProfile profile, boolean facingLeft) {
        Point botPos = bot.getPosition();
        if (botPos == null) {
            return List.of();
        }
        Rectangle box = reachBox(bot, profile, facingLeft);

        List<Monster> found = new ArrayList<>();
        for (MapObject mo : bot.getMap().getMapObjectsInRange(botPos, SEEK_RANGE_SQ, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (!m.isAlive() || !mayTarget(bot, m)) {
                continue;
            }
            Point mp = m.getPosition();
            if (mp == null) {
                continue;
            }
            // Bosses are big and multi-bodied: test their part positions against a vertically
            // padded box so e.g. Zakum's elevated arms still register as in-reach.
            Rectangle test = m.isBoss()
                    ? new Rectangle(box.x, box.y - BOSS_Y_PAD, box.width, box.height + 2 * BOSS_Y_PAD)
                    : box;
            // The reach box is a flat rectangle with no terrain sense, so a tall box would let the bot
            // hit a mob standing on a separate platform overhead/below. Drop mobs on a different ledge
            // than the bot (bosses exempt). Cheap (peek-only) and only run for box-passing mobs.
            if (test.contains(mp) && onAttackableSurface(bot.getMap(), botPos, m)) {
                found.add(m);
            }
        }
        found.sort((a, b) -> Double.compare(botPos.distanceSq(a.getPosition()), botPos.distanceSq(b.getPosition())));
        return found;
    }

    /*
     * The attack's effective hit rectangle in map coordinates.
     *
     * Melee uses the skill's real WZ range box when it defines one (correct per-skill size, and the
     * surround-vs-forward shape baked into the game data), otherwise the profile's reach defaults.
     *
     * Ranged and magic attacks are projectiles: the skill's WZ rectangle is only the animation/effect
     * box, not the throw/cast distance - for Lucky Seven and Magic Claw it's authored melee-tight, so a
     * bot using it would only land hits point-blank. For those routes we use the profile's ranged reach
     * so hit detection matches the real attack distance.
     *
     * Forward attacks are clipped to the bot's facing side; surround attacks (a box centred on the
     * character - mage AoE, Dragon Roar) keep both sides.
     */
    private static Rectangle reachBox(Character bot, BotAttackProfile profile, boolean facingLeft) {
        Point p = bot.getPosition();
        Rectangle wz = wzBox(profile.skillId, packetLevel(bot, profile), p, facingLeft);

        Rectangle box = (wz != null)
                ? wz
                : new Rectangle(p.x - profile.reachX, p.y - profile.reachY, profile.reachX * 2, profile.reachY * 2);

        // A surround attack's box extends comparably to both sides of the bot. Ranged is never surround
        // (a thrown star is one-directional); centred magic AoE can be.
        boolean surround = wz != null
                && profile.route != BotAttackProfile.Route.RANGED
                && isCentred(box, p.x);

        // Projectiles (ranged/magic): swap the animation-sized WZ box for the profile's true reach so the
        // attack lands at its real distance. Keep the surround shape for centred magic AoE, forward lane otherwise.
        if (profile.route == BotAttackProfile.Route.RANGED || profile.route == BotAttackProfile.Route.MAGIC) {
            box = new Rectangle(p.x - profile.reachX, p.y - profile.reachY,
                    profile.reachX * 2, profile.reachY * 2);
        }

        if (!surround) {
            box = clipForward(box, p.x, facingLeft);
        }
        return coverBody(box, p, facingLeft, surround);
    }

    /*
     * Stretch a box over the bot's own feet and body. Many melee boxes stop short of the attacker: Raging Blow and
     * Impaling Rays end at the foot line (rb.y = 0), and a Rectangle leaves out its bottom edge, so a mob standing
     * on the bot's own ground - the usual case - was never in reach; Brandish and Crusher also start 10-16 px in
     * front, missing a mob that has walked into the bot. The bot then stood in "range" swinging at nothing.
     */
    static Rectangle coverBody(Rectangle box, Point p, boolean facingLeft, boolean surround) {
        int top = box.y;
        int bottom = Math.max(box.y + box.height, p.y + FOOT_SLACK);
        int left = box.x;
        int right = box.x + box.width;
        if (surround) {
            left = Math.min(left, p.x - BACK_MARGIN);
            right = Math.max(right, p.x + BACK_MARGIN);
        } else if (facingLeft) {
            right = Math.max(right, p.x + BACK_MARGIN);
        } else {
            left = Math.min(left, p.x - BACK_MARGIN);
        }
        return new Rectangle(left, top, right - left, bottom - top);
    }

    /* The skill's WZ attack rectangle anchored at the bot and flipped for facing, or null. */
    private static Rectangle wzBox(int skillId, int skillLevel, Point from, boolean facingLeft) {
        if (skillId == 0) {
            return null;
        }
        Skill skill = SkillFactory.getSkill(skillId);
        if (skill == null) {
            return null;
        }
        StatEffect eff = skill.getEffect(Math.max(1, skillLevel));
        return (eff != null) ? eff.getAttackBox(from, facingLeft) : null;
    }

    /* True when the box extends comparably to both sides of the bot (a surround attack). */
    private static boolean isCentred(Rectangle box, int botX) {
        int left = botX - box.x;
        int right = (box.x + box.width) - botX;
        return left > 0 && right > 0 && Math.abs(left - right) <= SYMMETRY_SLACK;
    }

    /* Clip a box to the bot's facing side, keeping a small back margin for adjacency. */
    private static Rectangle clipForward(Rectangle box, int botX, boolean facingLeft) {
        if (facingLeft) {
            int right = Math.min(box.x + box.width, botX + BACK_MARGIN);
            return new Rectangle(box.x, box.y, Math.max(0, right - box.x), box.height);
        }
        int left = Math.max(box.x, botX - BACK_MARGIN);
        return new Rectangle(left, box.y, Math.max(0, (box.x + box.width) - left), box.height);
    }

    /*
     * Orient the bot toward the target before measuring reach and swinging; returns true when it now
     * faces left. GCMovement-driven bots (the roaming grinders) must face through GCMovement.face: that
     * sets the authoritative facingDir and broadcasts the turn, so the bot stays pointed at the mob
     * instead of snapping back to its travel facing the instant the swing ends. The legacy stance-flip
     * only set Character.stance, which GCMovement re-derives from facingDir and overwrites every tick -
     * so a mob behind the bot got a one-frame swing then an immediate revert. Bots not under dynamic
     * control (e.g. the GM TestAttackBot) fall back to that legacy flip.
     */
    private static boolean faceTarget(Character bot, Point target) {
        if (GCMovement.isEnabled(bot)) {
            boolean left = target.x < bot.getPosition().x;
            GCMovement.face(bot, left);
            return left;
        }
        MovementCommands.botFaceTowardsPoint(bot, target);
        return MovementCommands.facingLeft(bot);
    }

    // Mobs this close to the bot's x have no side: no turn is needed to hit them.
    private static final int TURN_DEADZONE_PX = 15;

    /*
     * 1.5.0 rc3 (owner decision 2, "face the mobs before attacking"): when a GC-driven bot faces away from the mob it
     * is about to hit, it turns now and swings on a later tick (a 120-300 ms reaction). True when it turned.
     */
    private static boolean turnsFirst(Character bot, Monster target, long now) {
        if (!BotCombatTuning.human() || !GCMovement.isEnabled(bot) || target.getPosition() == null) {
            return false;
        }
        Boolean facingLeft = GCMovement.isFacingLeft(bot);
        int dx = target.getPosition().x - bot.getPosition().x;
        if (facingLeft == null || Math.abs(dx) <= TURN_DEADZONE_PX || (dx < 0) == facingLeft) {
            return false;
        }
        GCMovement.face(bot, dx < 0);
        nextAttackByBot.put(bot.getId(), now + 120 + java.util.concurrent.ThreadLocalRandom.current().nextInt(181));
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.COMBAT)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.COMBAT, bot.getMapId(), "FACE " + bot.getName()
                    + " turns " + (dx < 0 ? "left" : "right") + " to '" + target.getName() + "' (dx " + dx
                    + ") before swinging");
        }
        return true;
    }

    /* The closest live mob within the seek radius (any direction), or null. */
    /**
     * 1.5.5: mobs a bot must never attack. The Mu Lung Dojo's "A transparent item for checking elimination" (9300216)
     * is what a floor's boss revives into; the floor's up portal (dojang_up.js) only opens while it is alive, so a bot
     * killing it locked the party on the floor ("There are still some monsters remaining").
     *
     * <p>1.6.0: nor a friendly mob (Mob.wz {@code friendly}, e.g. HPQ's Moon Bunny 9300061): the PQ needs it alive.
     */
    /** 1.6.1 ticket 13: only these mobs may this bot face or hit (null clears it). */
    public static void setTargetFilter(int botId, java.util.function.Predicate<Monster> filter) {
        if (filter == null) {
            targetFilters.remove(botId);
        } else {
            targetFilters.put(botId, filter);
        }
    }

    /** botMayTarget, and this bot's own filter (an owned alt never touches a stranger's mob). */
    static boolean mayTarget(Character bot, Monster m) {
        if (!botMayTarget(m)) {
            return false;
        }
        java.util.function.Predicate<Monster> f = targetFilters.get(bot.getId());
        return f == null || f.test(m);
    }

    public static boolean botMayTarget(Monster m) {
        return m != null && m.getId() != constants.id.MobId.TRANSPARENT_ITEM
                && (m.getStats() == null || !m.getStats().isFriendly());
    }

    private static Monster nearestMob(Character bot) {
        Point botPos = bot.getPosition();
        if (botPos == null) {
            return null;
        }
        Monster nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : bot.getMap().getMapObjectsInRange(botPos, SEEK_RANGE_SQ, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (!m.isAlive() || m.getPosition() == null || !mayTarget(bot, m)) {
                continue;
            }
            // Skip mobs on a separate ledge so the bot faces (and later swings at) a mob it can
            // actually reach, instead of fixating up at a platform overhead.
            if (!onAttackableSurface(bot.getMap(), botPos, m)) {
                continue;
            }
            double dsq = botPos.distanceSq(m.getPosition());
            if (dsq < bestSq) {
                bestSq = dsq;
                nearest = m;
            }
        }
        return nearest;
    }

    /*
     * A mob on a surface the bot can actually strike: not standing on a separate walkable ledge
     * above or below the bot. Bosses are exempt - large, multi-bodied, with parts on their own
     * footholds (and already handled by the padded reach box). Degrades to "true" (no filtering)
     * when the map's nav graph isn't baked, so it never silently blanks a bot's targets.
     *
     * 1.6.1: public so an owned alt picks its mob by this same rule (t-oa-1: it chose by its own, looser one and stood
     * at a mob this driver would not face).
     */
    public static boolean onAttackableSurface(MapleMap map, Point botPos, Monster m) {
        if (m.isBoss()) {
            return true;
        }
        Point mp = m.getPosition();
        return mp == null || !ledges.different(map, botPos, mp);
    }

    /** Are two points on different walkable ledges (nav-graph regions)? */
    public interface Ledges {
        boolean different(MapleMap map, Point a, Point b);
    }

    public static final Ledges NAV_LEDGES = (map, a, b) -> GCMovement.onDifferentLedge(map, a.x, a.y, b.x, b.y);
    /** The ledge test {@link #onAttackableSurface} uses; tests stub it (a mocked map has no nav graph). */
    public static volatile Ledges ledges = NAV_LEDGES;

    /* First max of a nearest-first list (the whole list if it's already small enough). */
    private static List<Monster> cap(List<Monster> mobs, int max) {
        return mobs.size() <= max ? mobs : new ArrayList<>(mobs.subList(0, max));
    }

    /*
     * Human-readable hint for the GM command when nothing was in reach: where the nearest mob
     * sits relative to the bot and the reach box it fell outside. Only walked on the miss path.
     */
    private static String reachDiagnostic(Character bot, BotAttackProfile profile, boolean facingLeft) {
        Point botPos = bot.getPosition();
        Monster nearest = nearestMob(bot);
        if (nearest == null) {
            return "no targetable mobs within " + SEEK_RANGE + "px (none, or all on a separate ledge)";
        }
        Rectangle box = reachBox(bot, profile, facingLeft);
        Point np = nearest.getPosition();
        return "nearest '" + nearest.getName() + "' at dx=" + (np.x - botPos.x) + " dy=" + (np.y - botPos.y)
                + " outside reach x[" + box.x + ".." + (box.x + box.width) + "] y[" + box.y + ".." + (box.y + box.height)
                + "] facing " + (facingLeft ? "left" : "right");
    }
}
