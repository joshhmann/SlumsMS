package soloMapling.worldevents;

import client.Character;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupplySystem.MobContactDamage;
import soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.qa.QaLog;
import tools.PacketCreator;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.0 madness (owner QA 2026-10-27: "did see bots not get hurt while mobs were walking through em"): an invasion's
 * own mobs hit every bot standing in them, server-side, on a short beat of {@link #BEAT_MS}.
 * <p>
 * Why here and not in the movement driver: a bot's touch damage ran only inside its GCMovement driver tick
 * ({@code BotContactDamage}), so a bot that driver does not step was never touched: a seated or resting bot (the
 * driver holds the sit and returns before the damage check), a bot in an old-engine town state machine (social bots'
 * town station, Henesys bots) and any bot not under dynamic movement at all. No client ever sends a hit for a bot, so
 * nothing else did. This pass walks the event map's characters once per beat instead, whatever moves them, and the
 * driver skips the event's mobs on an invasion map so no bot is hit twice. Scoped to the event's own mobs on a running
 * invasion's map: every other map and every other mob is untouched.
 * <p>
 * A hit is the same as the driver's: {@link MobContactDamage} (half the mob's attack, a miss chance), the damage
 * packet for the viewers, then {@link BotVitals#hurt}: off the bot's HP, it drinks a potion when low, and the swarm
 * death rules decide whether a heavy hit kills (per-bot cooldown, the event's pace and cap; only bots BotDeaths lets
 * die: grinders at the event and the town fighters). Each bot is hit at most once per {@link #IFRAME_MS} (the same
 * 2.5 s as the driver's i-frames). A seated bot that is hit stands up, and an AFK camper wakes. Owned alts and bots
 * in a trade or keeping a shop or a game are left alone. Only where a real player can see the map (as the driver).
 */
public final class InvasionSwarmTouch {

    static final long BEAT_MS = 500;
    static final long IFRAME_MS = 2_500;

    private static final Map<Integer, Long> lastHitAt = new ConcurrentHashMap<>();

    interface Hit {
        void land(ActiveEvent e, Character chr, Monster mob, long now);
    }

    /** Test seams: whether a real player sees the map, and what a hit does. */
    static volatile java.util.function.IntPredicate observed = GCMovement::isMapObserved;
    static volatile Hit hitter = InvasionSwarmTouch::hit;

    private InvasionSwarmTouch() {
    }

    /** Pure: the event mob that reaches this bot (the nearest within the swarm reach), or null. */
    static Monster reaching(Point bot, List<Monster> mobs) {
        if (bot == null) {
            return null;
        }
        Monster best = null;
        long bestD = Long.MAX_VALUE;
        for (Monster m : mobs) {
            Point p = m == null ? null : m.getPosition();
            if (p == null || !m.isAlive() || !GCMovement.withinSwarmReach(bot, p)) {
                continue;
            }
            if (m.getStats() != null && m.getStats().isFriendly()) {
                continue;
            }
            long d = Math.abs((long) p.x - bot.x) + Math.abs((long) p.y - bot.y);
            if (d < bestD) {
                best = m;
                bestD = d;
            }
        }
        return best;
    }

    /** Pure: may this bot be hit again now (its i-frames are over)? */
    static boolean due(Long lastHit, long now) {
        return lastHit == null || now - lastHit >= IFRAME_MS;
    }

    /** Whether this character on the map takes the event's hits. */
    static boolean hittable(Character chr) {
        if (chr == null || !BotHelpers.isBot(chr) || chr.isOwnedAlt() || BotDeaths.isDead(chr.getId())
                || CharacterStorage.checkIfInvisibleBot(chr.getId())) {
            return false;
        }
        return chr.getTrade() == null && chr.getPlayerShop() == null && chr.getMiniGame() == null;
    }

    /** One beat on a running invasion: every hittable bot an event mob reaches takes its hit. Returns the hits. */
    static int beat(ActiveEvent e, MapleMap map, long now) {
        if (e == null || e.finished || map == null || e.mobOids.isEmpty() || !observed.test(e.mapId())) {
            return 0;
        }
        List<Monster> mobs = new ArrayList<>();
        for (int oid : e.mobOids) {
            Monster m = map.getMonsterByOid(oid);
            if (m != null && m.isAlive()) {
                mobs.add(m);
            }
        }
        if (mobs.isEmpty()) {
            return 0;
        }
        int hits = 0;
        for (Character chr : new ArrayList<>(map.getAllPlayers())) {
            if (!hittable(chr) || !due(lastHitAt.get(chr.getId()), now)) {
                continue;
            }
            Monster mob = reaching(chr.getPosition(), mobs);
            if (mob == null) {
                continue;
            }
            lastHitAt.put(chr.getId(), now);
            hitter.land(e, chr, mob, now);
            hits++;
        }
        return hits;
    }

    private static void hit(ActiveEvent e, Character chr, Monster mob, long now) {
        int dmg = MobContactDamage.roll(chr, mob.getPADamage());
        AfkCampers.endCamp(chr.getId(), "hit by an invader");
        if (chr.getChair() > 0) {
            soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.standUp(chr); // a hit knocks you off it
        }
        int dir = mob.getPosition().x <= chr.getPosition().x ? 1 : -1;
        try {
            chr.getMap().broadcastMessage(chr, PacketCreator.damagePlayer(-1, mob.getId(), chr.getId(), Math.max(0, dmg),
                    0, dir, false, 0, false, 0, 0, 0), false);
        } catch (RuntimeException ignored) {
            // the hit still lands; the number is presentation
        }
        if (dmg > 0) {
            GCMovement.markAlerted(chr);
            soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityHooks.onMobHitBot(chr, mob, now);
            BotVitals.hurt(chr, dmg); // its HP, a potion when low, and the swarm death rules
        }
        if (QaLog.on(QaLog.Topic.WORLD_EVENT) && QaLog.every("swarm-touch:" + chr.getId(), 10_000)) {
            QaLog.map(QaLog.Topic.WORLD_EVENT, e.mapId(), "WEVENT TOUCH #" + e.id + " " + mob.getName() + " hits "
                    + chr.getName() + " (lv " + chr.getLevel() + ") for " + dmg + ", HP " + chr.getHp() + "/"
                    + chr.getCurrentMaxHp());
        }
    }

    /** The scheduled beat for one invasion (WorldEvents starts it with the event and cancels it at the end). */
    static Runnable task(ActiveEvent e) {
        return () -> {
            try {
                if (soloMapling.server.BotShutdown.isQuiesced()) {
                    return;
                }
                beat(e, WorldEvents.mapOf(e.mapId()), System.currentTimeMillis());
            } catch (RuntimeException ex) {
                // a map mid-teardown: the next beat tries again
            }
        };
    }

    /** An invasion ended: drop the i-frames that are over (the map is any bot's again). */
    static void forget(long now) {
        lastHitAt.values().removeIf(t -> now - t >= IFRAME_MS);
    }

    static void clearForTest() {
        lastHitAt.clear();
        observed = GCMovement::isMapObserved;
        hitter = InvasionSwarmTouch::hit;
    }
}
