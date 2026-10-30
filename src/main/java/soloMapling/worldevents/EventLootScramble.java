package soloMapling.worldevents;

import java.util.Random;

/**
 * 1.6.2 owner report 2026-10-30 ("the birthday cake drops scrolls but no one picks up the items"): when a fight event
 * ends (the cake, an invasion's boss, a sighted boss falls) its drops land a moment after the event is over, and the
 * old release sent every called bot off to town on its very next tick, so nobody was left to loot them. Now the
 * called bots that were fighting there stay a little and scramble for the pile like players would: with the same
 * grind loot code they use on any map (GrindLoot: settle time, pacing, their loot habit, bag room, the yield caps
 * through BotHoldings), so a drop owned by someone else is theirs only once its 15 s ownership is over. A bot leaves
 * once nothing it could take is left within reach (after that ownership window) or its own patience runs out; some
 * do not stay at all. Whatever nobody takes expires as usual. Switch: {@code -Dsolomapling.worldEvents.lootAftermath}
 * (default on: a bug fix). Pure apart from the switch.
 */
public final class EventLootScramble {

    /** How long a monster drop belongs to its owner (MapItem.hasExpiredOwnershipTime, DropCommands.botCanLoot). */
    public static final long OWNER_MS = 15_000;
    /** After the ownership window: a few seconds to notice the pile is free before a dry bot gives up. */
    static final long GRACE_MS = 5_000;
    /** A bot's patience for the pile, from the fall: uniform in [min, max]. */
    static final long STAY_MIN_MS = 30_000;
    static final long STAY_MAX_MS = 60_000;
    /** The share that does not bother and heads off right away (as players do). */
    static final double SKIP_SHARE = 0.15;

    private EventLootScramble() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.worldEvents.lootAftermath", "true"));
    }

    /** Events whose mobs drop loot on their map: the fights. */
    static boolean fights(EventTemplate.Kind kind) {
        return kind == EventTemplate.Kind.GIANT_CAKE || kind == EventTemplate.Kind.INVASION
                || kind == EventTemplate.Kind.BOSS_SIGHTING;
    }

    /** Until when this bot may stay for the pile ({@code endedAt} = it does not stay). */
    static long stayUntil(long endedAt, Random rng) {
        if (rng.nextDouble() < SKIP_SHARE) {
            return endedAt;
        }
        return endedAt + STAY_MIN_MS + (long) (rng.nextDouble() * (STAY_MAX_MS - STAY_MIN_MS));
    }

    /**
     * Whether a bot still scrambles: before its patience runs out, and either the drops are still owned (wait for
     * them, as a player waits out someone else's loot) or there is something left it could take.
     */
    public static boolean stays(long now, long endedAt, long until, boolean lootLeft) {
        if (until <= endedAt || now >= until) {
            return false;
        }
        return now < endedAt + OWNER_MS + GRACE_MS || lootLeft;
    }
}
