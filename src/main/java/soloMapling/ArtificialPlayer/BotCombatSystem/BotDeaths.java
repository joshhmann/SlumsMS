package soloMapling.ArtificialPlayer.BotCombatSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.qa.QaLog;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.5.0 rc3 (owner decision 5): rare deaths on harder maps. Bots used to never drop below 1 HP. Now a hit that
 * would kill a grinding bot may really kill it, but only when all of these hold:
 * <ul>
 *   <li>{@code BOT_COMBAT_DEATHS} is on, and a real player can see the map (offscreen bots never die);</li>
 *   <li>the bot is a grinder on its train map ({@link #setEligible}), not in an event, a party with a player, or a
 *       GM staging that says otherwise;</li>
 *   <li>1.6.0: on a world event's invasion map the next two rules give way to the invasion's own
 *       (soloMapling.worldevents.WorldEvents#swarmDeath): any map, a heavy hit, a per-bot cooldown and a per-event
 *       cap instead of the world's;</li>
 *   <li>the map is harder than its level: the map's mobs are {@link #HARDER_BY} or more levels above the bot;</li>
 *   <li>its own roll: {@link #BASE_CHANCE} x its carelessness habit; at most once per {@link #BOT_COOLDOWN_MS} per
 *       bot and {@link #WORLD_PER_10_MIN} deaths every 10 minutes server-wide.</li>
 * </ul>
 * A death goes through the game's own path (HP to 0: the EXP penalty, buffs cancelled), the bot lies down for a few
 * seconds, then TrainingBot respawns it in town, where it rests and restocks, and walks back to the map.
 */
public final class BotDeaths {
    private static final Logger log = LoggerFactory.getLogger(BotDeaths.class);

    private BotDeaths() {
    }

    static final int HARDER_BY = 3;
    static final double BASE_CHANCE = 0.35;
    static final long BOT_COOLDOWN_MS = 20 * 60_000L;
    static final int WORLD_PER_10_MIN = 6;
    static final long LIE_MIN_MS = 4_000, LIE_MAX_MS = 9_000;
    // The client's "dead" pose: move action 9 (v83 MoveAction DEAD) shifted, plus the facing bit.
    static final int DEAD_STANCE = 18;

    private static final Set<Integer> eligible = ConcurrentHashMap.newKeySet();
    private static final Map<Integer, Long> lastDeath = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lyingUntil = new ConcurrentHashMap<>();
    private static final java.util.Deque<Long> recent = new java.util.ArrayDeque<>();
    // GM/QA: these bots' next lethal hit kills regardless of map and roll (per bot: another bot must not consume it)
    private static final Set<Integer> forceNext = ConcurrentHashMap.newKeySet();

    /** A grinder on its train map may die there (TrainingBot sets and clears this). */
    public static void setEligible(int botId, boolean on) {
        if (on) {
            eligible.add(botId);
        } else {
            eligible.remove(botId);
        }
    }

    /** QA cb-3: this bot's next lethal hit (while eligible) kills it whatever the map and the roll. */
    public static void forceNextDeath(int botId, boolean on) {
        if (on) {
            forceNext.add(botId);
        } else {
            forceNext.remove(botId);
        }
    }

    /**
     * Re-checked at roll time (eligibility is only set at grind start): a bot that has since partied with or is
     * following a real player, is in an event, or is held by GM staging (cb-3 does not hold) never dies.
     */
    static boolean protectedFromDeath(Character bot) {
        if (bot.getEventInstance() != null
                || soloMapling.worldevents.WorldEventSteer.isStagingHeld(bot.getId())) {
            return true;
        }
        net.server.world.Party party = bot.getParty();
        if (party != null) {
            for (net.server.world.PartyCharacter pc : party.getMembers()) {
                if (pc == null || pc.getId() == bot.getId()) {
                    continue;
                }
                Character p = pc.getPlayer();
                if (p != null ? !soloMapling.ArtificialPlayer.BotHelpers.isBot(p)
                        : !soloMapling.ArtificialPlayer.BotHelpers.isArtificial(pc.getId())) {
                    return true; // a real member, online or not
                }
            }
        }
        Character followed = GCMovement.followTarget(bot);
        return followed != null && !soloMapling.ArtificialPlayer.BotHelpers.isBot(followed);
    }

    /** Whether a hit that would take the bot to 0 HP may kill it (and rolls for it). */
    public static boolean rollsDeath(Character bot) {
        if (bot == null || !eligible.contains(bot.getId()) || !BotCombatTuning.deaths()
                || !GCMovement.isMapObserved(bot) || protectedFromDeath(bot)) {
            return false;
        }
        long now = System.currentTimeMillis();
        boolean forced = forceNext.contains(bot.getId());
        // 1.6.0 world events: an invasion's own rules (no harder-map rule, no world cap; a per-bot cooldown and a
        // per-event cap) - the owner wants to see bots die in droves there
        Boolean swarm = forced ? null : soloMapling.worldevents.WorldEvents.swarmDeath(bot, now,
                ThreadLocalRandom.current());
        if (swarm != null) {
            if (swarm) {
                lastDeath.put(bot.getId(), now);
            }
            return swarm;
        }
        if (!forced) {
            Long last = lastDeath.get(bot.getId());
            if (last != null && now - last < BOT_COOLDOWN_MS) {
                return false;
            }
            if (!harder(MapMobIndex.level(bot.getMapId()), bot.getLevel())) {
                return false;
            }
            double chance = BASE_CHANCE * BotHabits.of(bot.getId()).carelessness();
            if (ThreadLocalRandom.current().nextDouble() >= chance) {
                return false;
            }
        }
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > 10 * 60_000L) {
                recent.pollFirst();
            }
            if (!forced && recent.size() >= WORLD_PER_10_MIN) {
                return false;
            }
            recent.addLast(now);
        }
        forceNext.remove(bot.getId());
        return true;
    }

    /** A map is harder than the bot's level when its mobs stand HARDER_BY or more levels above it. */
    static boolean harder(int mapMobLevel, int botLevel) {
        return mapMobLevel > 0 && mapMobLevel >= botLevel + HARDER_BY;
    }

    /** The bot dies now: HP to 0 through the game (EXP penalty), it stops and lies in the dead pose. */
    public static void die(Character bot) {
        long now = System.currentTimeMillis();
        lastDeath.put(bot.getId(), now);
        lyingUntil.put(bot.getId(), now + LIE_MIN_MS + ThreadLocalRandom.current().nextLong(LIE_MAX_MS - LIE_MIN_MS));
        GCMovement.stop(bot);
        GCMovement.holdForCommitment(bot);
        bot.updateHp(0);
        try {
            Boolean left = GCMovement.isFacingLeft(bot);
            bot.broadcastStance(DEAD_STANCE + (Boolean.TRUE.equals(left) ? 1 : 0));
        } catch (RuntimeException ignored) {
            // the pose is presentation only
        }
        // One line per death so a soak can count them (the world cap is WORLD_PER_10_MIN).
        log.info("[BotDeaths] {}{} (lv {}) died on map {}", bot.getName(), bot.isOwnedAlt() ? " (owned alt)" : "",
                bot.getLevel(), bot.getMapId());
        if (!bot.isOwnedAlt()) { // 1.6.1 ticket 13: an owned alt's death is its player's, not a population count
            try {
                soloMapling.worldevents.WorldEvents.getInstance().onBotDied(bot); // 1.6.0: counted at a world event
            } catch (RuntimeException ignored) {
                // flavor only
            }
        }
        if (QaLog.on(QaLog.Topic.HABITS)) {
            QaLog.map(QaLog.Topic.HABITS, bot.getMapId(), "DEATH " + bot.getName() + " (lv " + bot.getLevel()
                    + ") dies on map " + bot.getMapId() + " (mobs lv " + MapMobIndex.level(bot.getMapId())
                    + "), HP pots left " + soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.potionCount(bot, true));
        }
    }

    /** True while the bot lies dead (TrainingBot holds everything). */
    public static boolean isDead(int botId) {
        return lyingUntil.containsKey(botId);
    }

    /** True once the bot has lain long enough and should respawn now; clears the dead state. */
    public static boolean readyToRespawn(int botId) {
        Long until = lyingUntil.get(botId);
        if (until == null || System.currentTimeMillis() < until) {
            return false;
        }
        lyingUntil.remove(botId);
        return true;
    }

    /** Despawn: forget the bot. */
    public static void clearBot(int botId) {
        eligible.remove(botId);
        lyingUntil.remove(botId);
        lastDeath.remove(botId);
        forceNext.remove(botId);
    }
}
