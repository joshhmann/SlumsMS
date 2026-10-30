package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import net.packet.Packet;
import soloMapling.server.BotAttackPackets;
import soloMapling.server.BotAttackPackets.AttackTarget;
import net.server.world.World;
import server.ItemInformationProvider;
import server.life.Monster;
import server.life.MonsterDropEntry;
import server.life.MonsterInformationProvider;
import server.maps.MapItem;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.MethodScheduler;
import tools.PacketCreator;
import tools.Randomizer;

import java.awt.Point;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
 * Lands a real bot hit (melee / ranged / magic): broadcast the attack packet with the
 * damage line(s) so players see it, then apply the summed damage directly - skipping the
 * vanilla applyAttack path, which is keyed off stats a bot lacks. The bot is always passed
 * explicitly (never the shared client).
 *
 * On a kill we credit EXP + death via monster.damage + killMonster(withDrops=false), then
 * drop loot ourselves: the vanilla path only drops for damagers passing isLoggedinWorld(),
 * which a headless bot fails, so a bot kill would otherwise drop nothing. Loot keeps
 * vanilla ownership (owner = bot; party-shared if grouped, else owner ~15s then FFA) at
 * world drop rates.
 */
public final class BotAttackEffects {

    private BotAttackEffects() {}

    /*
     * Credits to NutNNut for Packet IDs
     * Ranged hit (bow/crossbow/gun/claw): broadcast the shot carrying each mob's damage line(s) and the flying
     * projectile, apply the summed damage per mob, and drop loot for any it kills. Returns true if a mob died.
     * (The melee version is gone: every bot swing goes through strike / attackPacket, whose melee packet is checked
     * against the client's ranges.)
     */
    public static boolean rangedStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                       int skillLevel, int projectile, int bodyActionId, int facingMask,
                                       int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = BotAttackPackets.rangedAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), projectile, toTargets(hits, hitDelay), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    /*
     * Magic version (wand/staff): a skill is mandatory (mages have no basic attack). The charge
     * int is -1 for normal skills but a real value for keydown CHARGE skills (Big Bang) - the
     * client over-reads the packet and crashes if a charge skill is sent without it.
     */
    public static boolean magicStrike(Character bot, Map<Monster, List<Integer>> hits, int skillId,
                                      int skillLevel, int bodyActionId, int facingMask, int speed, short hitDelay) {
        if (notReady(bot, hits)) {
            return false;
        }
        Packet packet = BotAttackPackets.magicAttack(bot, skillId, skillLevel, facingMask,
                numAttackedAndDamage(hits), toTargets(hits, hitDelay),
                BotAttackData.magicChargeFor(skillId), speed, bodyActionId, 0);
        return broadcastAndApply(bot, packet, hits, hitDelay);
    }

    /*
     * The attack packet a swing plan sends (ticket 32): the plan's route, skill, level, pose, facing and attack speed,
     * the projectile for a shot from a shooting weapon (a pole-arm / knuckle shot carries none), and each mob's lines.
     * Pure apart from the attacker's id, so BotAttackClientValidityTest checks the exact bytes a viewer gets. Null
     * only for a melee attack whose weapon has no pose a viewer finds a range for (BotAttackPackets).
     */
    public static Packet attackPacket(Character bot, BotSwingPlan plan, Map<Integer, AttackTarget> targets,
                                      int numAttackedAndDamage, int projectile) {
        return switch (plan.route()) {
            case CLOSE -> BotAttackPackets.closeRangeAttack(bot, plan.weaponItemId(), plan.skillId(), plan.skillLevel(),
                    plan.facingMask(), numAttackedAndDamage, targets, plan.speed(), plan.action(), 0);
            case RANGED -> BotAttackPackets.rangedAttack(bot, plan.skillId(), plan.skillLevel(), plan.facingMask(),
                    numAttackedAndDamage, projectile, targets, plan.speed(), plan.action(), 0);
            case MAGIC -> BotAttackPackets.magicAttack(bot, plan.skillId(), plan.skillLevel(), plan.facingMask(),
                    numAttackedAndDamage, targets, BotAttackData.magicChargeFor(plan.skillId()), plan.speed(),
                    plan.action(), 0);
        };
    }

    /*
     * The keydown packet a player's client sends before a keydown / prepare skill (CUserLocal 0x96b0be -> the server's
     * SkillEffectHandler, which relays it as SKILL_EFFECT): skill, level, the keydown pose with the facing bit on
     * top, the attack speed. A viewer (CUserRemote::OnPrepare 0x980a81) starts the keydown pose and effect; for a
     * skill it does not know it throws, so only skills the client has are ever prepared.
     */
    public static Packet preparePacket(Character bot, BotSwingPlan plan) {
        int pose = plan.prepareActionAndFacing();
        return PacketCreator.skillEffect(bot, plan.skillId(), plan.skillLevel(), (byte) (pose & 0xFF),
                (pose >> 8) & 0xFF, (byte) plan.speed());
    }

    /* The release of a held keydown skill (Hurricane, Rapid Fire): the viewer ends the keydown pose. */
    public static Packet releasePacket(Character bot, BotSwingPlan plan) {
        return PacketCreator.skillCancel(bot, plan.skillId());
    }

    /*
     * Lands a planned swing: the keydown packet first when the skill has one, then the attack. A held keydown skill
     * (plan.shots() > 1: Hurricane, Rapid Fire) keeps shooting KEYDOWN_SHOT_MS apart - the client's keydown cadence -
     * each shot re-rolled by nextHits over the mobs then in reach, until no mob is left in reach or plan.shots() went
     * out, and then releases; onRelease gets the epoch ms of the release. Returns true if the first shot killed a mob.
     */
    public static boolean strike(Character bot, BotSwingPlan plan, Map<Monster, List<Integer>> hits, int projectile,
                                 short hitDelay, java.util.function.Supplier<Map<Monster, List<Integer>>> nextHits) {
        return strike(bot, plan, hits, projectile, hitDelay, nextHits, null);
    }

    public static boolean strike(Character bot, BotSwingPlan plan, Map<Monster, List<Integer>> hits, int projectile,
                                 short hitDelay, java.util.function.Supplier<Map<Monster, List<Integer>>> nextHits,
                                 java.util.function.LongConsumer onRelease) {
        if (notReady(bot, hits)) {
            return false;
        }
        if (plan.prepare()) {
            bot.getMap().broadcastMessage(bot, preparePacket(bot, plan), false);
        }
        Packet first = attackPacket(bot, plan, toTargets(hits, hitDelay), numAttackedAndDamage(hits), projectile);
        boolean killed = broadcastAndApply(bot, first, hits, hitDelay);
        if (plan.shots() > 1) {
            long startedAt = System.currentTimeMillis();
            MapleMap map = bot.getMap();
            MethodScheduler.runAfterDelay(() -> holdKeydown(bot, map, plan, projectile, hitDelay, nextHits, onRelease,
                    startedAt, 1), BotSwingPlan.KEYDOWN_SHOT_MS);
        }
        return killed;
    }

    /* Shot number `shot` of a held keydown skill (due at startedAt + shot * KEYDOWN_SHOT_MS), or its release. */
    private static void holdKeydown(Character bot, MapleMap map, BotSwingPlan plan, int projectile, short hitDelay,
                                    java.util.function.Supplier<Map<Monster, List<Integer>>> nextHits,
                                    java.util.function.LongConsumer onRelease, long startedAt, int shot) {
        if (bot.getMap() != map) {
            map.broadcastMessage(bot, releasePacket(bot, plan), false); // left the map mid-hold: end the pose there
            release(onRelease);
            return;
        }
        if (!bot.isAlive()) {
            // 1.6.2 bot audit (B22): a hold outlived its bot. Death leaves the map and the client state alone, so the
            // remaining shots still dealt damage, EXP and loot from a corpse. End the pose for the viewers and stop.
            map.broadcastMessage(bot, releasePacket(bot, plan), false);
            release(onRelease);
            return;
        }
        Map<Monster, List<Integer>> more = shot < plan.shots() ? nextHits.get() : null;
        if (notReady(bot, more)) {
            map.broadcastMessage(bot, releasePacket(bot, plan), false);
            release(onRelease);
            return;
        }
        broadcastAndApply(bot, attackPacket(bot, plan, toTargets(more, hitDelay), numAttackedAndDamage(more),
                projectile), more, hitDelay);
        long next = startedAt + (shot + 1) * BotSwingPlan.KEYDOWN_SHOT_MS;
        MethodScheduler.runAfterDelay(() -> holdKeydown(bot, map, plan, projectile, hitDelay, nextHits, onRelease,
                startedAt, shot + 1), Math.max(0, next - System.currentTimeMillis()));
    }

    private static void release(java.util.function.LongConsumer onRelease) {
        if (onRelease != null) {
            onRelease.accept(System.currentTimeMillis());
        }
    }

    private static boolean notReady(Character bot, Map<Monster, List<Integer>> hits) {
        return bot == null || bot.getMap() == null || hits == null || hits.isEmpty();
    }

    /* numAttacked (mobs, high nibble) | numDamage (lines per mob, low nibble). */
    static int numAttackedAndDamage(Map<Monster, List<Integer>> hits) {
        int numDamage = hits.values().iterator().next().size();
        return (hits.size() << 4) | numDamage;
    }

    /* Packet target map: each mob's object id -> its damage line(s). */
    static Map<Integer, AttackTarget> toTargets(Map<Monster, List<Integer>> hits, short hitDelay) {
        Map<Integer, AttackTarget> targets = new HashMap<>();
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            targets.put(hit.getKey().getObjectId(), new AttackTarget(hitDelay, hit.getValue()));
        }
        return targets;
    }

    /* Broadcast once, then apply each mob's summed damage + loot. True if any mob died. */
    private static boolean broadcastAndApply(Character bot, Packet packet,
                                             Map<Monster, List<Integer>> hits, short hitDelay) {
        if (packet != null) {
            bot.getMap().broadcastMessage(bot, packet, /* repeatToSource */ false);
        }
        GCMovement.markAlerted(bot); // hold the 5s ALERT pose so the bot's own idle/move broadcasts don't cancel it
        boolean anyKilled = false;
        for (Map.Entry<Monster, List<Integer>> hit : hits.entrySet()) {
            int total = 0;
            for (int line : hit.getValue()) {
                total += BotAttackData.decodeDamageLine(line); // crit lines arrive negative-encoded
            }
            if (applyDamageAndLoot(bot, hit.getKey(), total, hitDelay)) {
                anyKilled = true;
            }
        }
        return anyKilled;
    }

    /* Apply HP damage; on death, credit EXP + the death broadcast (no vanilla drops) and spawn our own loot. */
    static boolean applyDamageAndLoot(Character bot, Monster target, int damage, short hitDelay) {
        MapleMap map = bot.getMap();

        // 1.6.1 ticket 30: a boss whose map runs its phases (Zakum's arms, Von Leon's last form, Cygnus' guards and
        // second form, Magnus) takes a bot's hit by the same rules as a player's (MapleMap.damageMonster). Before this
        // a bot's killing blow on Von Leon or Cygnus ended the fight without the next phase: the event never cleared.
        boolean mechanics = MapleMap.hasBossMechanics(target.getId());
        if (mechanics) {
            if (map.bossDamageBlocked(target)) {
                return false;
            }
            map.beforeBossDamage(bot, target);
        }
        soloMapling.server.MapleRootSolo.runHook("bond ks", () ->   // 1.6.0 S2: a bot took another bot's mob
                soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.beforeBotHit(bot, target));
        boolean killed = target.damage(bot, damage, false); // register damage; false = allow death
        if (killed && mechanics && map.afterBossKilled(bot, target)) {
            return true;   // the map killed it and brought the next phase on
        }
        if (killed) {
            // withDrops=false: keep the EXP distribution + death broadcast, skip the
            // vanilla drop step (which yields nothing for a bot-only kill). We spawn the
            // loot ourselves below with proper ownership.
            map.killMonster(target, bot, false, hitDelay);
            dropMobLoot(bot, target, hitDelay);
            // No vacuum here: the dropped loot is collected organically by the bot itself - TrainingBot
            // walks over the pile and picks drops up one at a time (own + free-for-all), and any drop it
            // abandons expires via the normal map item lifetime. See TrainingBot loot handling + DropCommands.
        }
        return killed;
    }

    /*
     * Roll the mob's real drop table and spawn each result with vanilla ownership: owner =
     * the bot, drop-type 1 (party-shared) when grouped else 0 (owner-protected ~15s, then
     * FFA). Rates come from the world config (drop_rate/boss_drop_rate/meso_rate), not the
     * bot's own field (a cold bot never runs setWorldRates(), so its rate is a flat 1).
     * Drops are sprayed horizontally like vanilla instead of stacking on one tile.
     */
    private static void dropMobLoot(Character bot, Monster mob, short delay) {
        MapleMap map = bot.getMap();
        if (mob.dropsDisabled()) {
            return;
        }

        List<MonsterDropEntry> drops = MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mob.getId());
        if (drops == null || drops.isEmpty()) {
            return;
        }

        // Vanilla drop-type: party loot when grouped, else owner-own (15s) -> FFA.
        final byte dropType = (byte) (bot.getParty() != null ? 1 : 0);
        // World rates (config drop_rate/boss_drop_rate/meso_rate), matching a real player.
        final World world = bot.getWorldServer();
        final int dropRate = Math.max(1, mob.isBoss() ? world.getBossDropRate() : world.getDropRate());
        final int mesoRate = Math.max(1, world.getMesoRate());
        final ItemInformationProvider ii = ItemInformationProvider.getInstance();
        final int mobX = mob.getPosition().x;
        final int mobY = mob.getPosition().y;

        // Spray successful drops horizontally (0, +25, -25, +50, -50 ... px), the same
        // index-based fan-out vanilla uses, so loot spreads out instead of stacking.
        int index = 1;
        for (MonsterDropEntry de : drops) {
            int dropChance = (int) Math.min((long) de.chance * dropRate, Integer.MAX_VALUE);
            if (Randomizer.nextInt(999999) >= dropChance) {
                continue;
            }

            int spreadX = mobX + ((index % 2 == 0) ? (25 * ((index + 1) / 2)) : -(25 * (index / 2)));
            Point pos = new Point(spreadX, mobY);

            if (de.itemId == 0) { // meso
                int mesos = rollAmount(de.Minimum, de.Maximum);
                if (mesos > 0) {
                    mesos = Math.max(1, mesos * mesoRate);
                    map.spawnMesoDrop(mesos, pos, mob, bot, /* playerDrop */ false, dropType, delay);
                    index++;
                }
            } else {
                Item item;
                if (ItemConstants.getInventoryType(de.itemId) == InventoryType.EQUIP) {
                    item = server.GodlyDrops.mobDropEquip(ii, de.itemId); // 1.5.2: rare godly roll, as for players
                } else {
                    short qty = (short) (de.Maximum != 1 ? rollAmount(de.Minimum, de.Maximum) : 1);
                    item = new Item(de.itemId, (short) 0, qty);
                }
                map.spawnItemDrop(mob, bot, item, pos, dropType, /* playerDrop */ false);
                index++;
            }
        }
    }

    /* Inclusive-ish amount roll matching vanilla (min..max), guarded against nextInt(0). */
    private static int rollAmount(int min, int max) {
        int span = max - min;
        return min + (span > 0 ? Randomizer.nextInt(span) : 0);
    }
}
