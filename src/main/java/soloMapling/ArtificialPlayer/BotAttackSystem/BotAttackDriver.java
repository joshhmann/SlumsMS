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
 * then each tick picks the AoE skill when 2+ mobs are in reach else the
 * single-target one, faces the nearest, rolls each line from the bot's real numbers (BotDamage), and strikes via the
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

    /* Which configured attack a swing uses: AUTO smart-picks; SINGLE/AOE/ULTIMATE force one slot. */
    public enum Choice { AUTO, SINGLE, AOE, ULTIMATE }

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

    /* The item id of the weapon the bot holds, or 0. */
    static int equippedWeaponId(Character bot) {
        Item weapon = bot.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
        return weapon != null ? weapon.getItemId() : 0;
    }

    /* Release a despawned bot's cooldown timers so the maps don't grow unbounded. */
    public static void clearBot(int botId) {
        nextAttackByBot.remove(botId);
        nextUltimateByBot.remove(botId);
        skillReadyByBot.remove(botId);
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
        if (single == null && aoe == null && ultimate == null) {
            return AttackResult.miss("no attack for job " + bot.getJob() + " / weapon " + weapon);
        }

        // Face the nearest mob BEFORE measuring reach, so directional attacks are oriented
        // toward the fight (the reach box is built relative to the bot's facing).
        Monster nearest = nearestMob(bot);
        if (nearest == null) {
            return AttackResult.miss("no targetable mobs within " + SEEK_RANGE + "px (none, or all on a separate ledge)");
        }
        boolean facingLeft = faceTarget(bot, nearest.getPosition());

        // Pick the profile. Forced choices use exactly that slot; AUTO fires an AoE when 2+ mobs are in
        // reach, else the single-target attack - skipping any whose skill is still in its WZ cooltime.
        BotAttackProfile profile;
        boolean healUndead = false;
        if (choice == Choice.SINGLE) {
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
            boolean useAoe = packAttack != null && mobsInReach(bot, packAttack, facingLeft).size() >= 2;
            if (useAoe) {
                profile = packAttack;
            } else {
                profile = firstNonNull(ready(bot, single, now), ready(bot, aoe, now), ultReady ? ultimate : null);
                if (profile == null) {
                    return AttackResult.miss("every attack is in its cooltime");
                }
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

        List<Monster> targets = cap(mobsInReach(bot, profile, facingLeft), profile.numAttacked);
        if (healUndead) {
            targets = undeadOnly(targets); // Heal must not damage a living mob caught in the box
        }
        if (targets.isEmpty()) {
            String why = healUndead ? "no undead in heal range" : reachDiagnostic(bot, profile, facingLeft);
            return AttackResult.miss(supply.fallback() != null ? why + " (" + supply.fallback() + ")" : why);
        }

        int facingMask = facingLeft ? BotAttackData.FACING_LEFT_MASK : BotAttackData.FACING_RIGHT_MASK;
        int skillId = profile.skillId;
        int skillLevel = packetLevel(bot, profile);
        BotAttackProfile.Route route = profile.route;
        if (!BotSkillActions.weaponFits(skillId, equippedWeaponId(bot))) {
            // A viewer's client drops a skill the attacker's weapon cannot use, and tells the viewer so. A plain swing
            // of the weapon it holds is drawn instead (BotWeapons normally keeps the weapon right for the job).
            skillId = 0;
            skillLevel = 0;
            route = BotAttackProfile.Route.CLOSE;
        }
        // The skill's own pose (the client drops an attack whose pose the skill does not allow), else the weapon's.
        int bodyActionId = BotAttackData.actionFor(skillId, skillLevel, weapon);

        // Roll the lines per mob from the bot's real numbers (BotDamage: stats, gear, skills, buffs, the mob's
        // defence); sum a total for the GM report. Shadow Partner mirrors each throw, so the client draws double
        // the stars - match the packet; the shadow's lines are its share of the ones they mirror. A crit line is
        // encoded NEGATIVE (BotAttackData.encodeCritLine) so the viewer renders a yellow crit; BotAttackEffects
        // decodes the lines back before applying HP.
        int linesPerMob = shadowDoubled(bot, profile.numDamage);
        BotDamage.Swing swing = BotDamage.swing(bot, skillId, skillLevel, route, weapon,
                targets.size() + 1, linesPerMob != profile.numDamage);
        Map<Monster, List<Integer>> hits = new LinkedHashMap<>();
        int reported = 0;
        for (Monster mob : targets) {
            List<Integer> lines = swing.roll(mob, linesPerMob);
            for (int line : lines) {
                reported = (int) Math.min(Integer.MAX_VALUE, (long) reported + BotAttackData.decodeDamageLine(line));
            }
            hits.put(mob, lines);
        }

        boolean killed = switch (route) {
            case CLOSE  -> BotAttackEffects.meleeStrike(bot, hits, skillId, skillLevel,
                    bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
            case RANGED -> BotAttackEffects.rangedStrike(bot, hits, skillId, skillLevel,
                    supply.projectile(), bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
            case MAGIC  -> BotAttackEffects.magicStrike(bot, hits, skillId, skillLevel,
                    bodyActionId, facingMask, profile.speed, profile.hitDelayMs);
        };

        supply.commit(bot, route, skillId); // the ammo fired, the MP/HP the skill cost (and a potion if it is low)
        BotComboOrbs.afterStrike(bot, skillId); // after the packet: a finisher is drawn with the orbs it spends
        nextAttackByBot.put(bot.getId(), now + profile.cooldownMs);
        startCooltime(bot, skillId, skillLevel, now);
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
        int held = bot.getSkillLevel(profile.skillId);
        if (held > 0) {
            return held;
        }
        Skill skill = SkillFactory.getSkill(profile.skillId);
        return skill != null && skill.getMaxLevel() > 0
                ? Math.min(profile.skillLevel, skill.getMaxLevel()) : profile.skillLevel;
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
            if (!m.isAlive()) {
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

    /* The closest live mob within the seek radius (any direction), or null. */
    private static Monster nearestMob(Character bot) {
        Point botPos = bot.getPosition();
        if (botPos == null) {
            return null;
        }
        Monster nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (MapObject mo : bot.getMap().getMapObjectsInRange(botPos, SEEK_RANGE_SQ, List.of(MapObjectType.MONSTER))) {
            Monster m = (Monster) mo;
            if (!m.isAlive() || m.getPosition() == null) {
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
     */
    private static boolean onAttackableSurface(MapleMap map, Point botPos, Monster m) {
        if (m.isBoss()) {
            return true;
        }
        Point mp = m.getPosition();
        return mp == null || !GCMovement.onDifferentLedge(map, botPos.x, botPos.y, mp.x, mp.y);
    }

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
