package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;

import java.util.Map;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/**
 * 1.6.0 S8 (brain reach): what {@link LifeRuntime} needs from a bot type to decide and act with the same brain. The
 * TrainingBot is the full host; the town and social bots are light hosts ({@link LifeTownLife}) that start from
 * different needs and offer fewer activities. The runtime never assumes a host's type.
 */
public interface LifeHost {
    Character getChr();

    boolean getRunning();

    /** Whether the host can start this activity now (destination, map cap, phase); the scorer only sees these. */
    boolean canStartLifeActivity(Activity activity, long at);

    /** No commitment (trade, conversation, party with a player, scene) holds the bot. */
    boolean freeForAutomaticLifeActivity(long at);

    LifeActivityQueue.State lifeActivityState();

    LifeActivityQueue.Request prepareLifeActivity(Result decision);

    LifeActivityQueue.Offer submitLifeActivity(LifeActivityQueue.Request request);

    /** The current activity is kept until {@code until} (the runtime re-decided and the same activity won). */
    void keepLifeActivity(Activity activity, long until);

    /** Ends a stay so a chosen friend request can start; false when nothing was ended. */
    default boolean interruptLifeDwell(long requestNumber) {
        return false;
    }

    void nudgeSoon(long delayMs);

    /**
     * 1.6.1 say-do: the current stay or grind lasts at least until {@code until} (never shortened): the bot said yes to
     * something its act starts in a few seconds. Hosts without timed stays ignore it.
     */
    default void holdLifeActivity(long until) {
    }

    /** A town or social bot: every activity it has keeps it in town, so a town plan step needs no extra pull. */
    default boolean townOnly() {
        return false;
    }

    /**
     * Ticket 158: whether this host runs quests (a grinder: the TrainingBot), and its home town (where a quest NPC may
     * stand; -1 for none). Light hosts never take quests.
     */
    default boolean runsQuests() {
        return false;
    }

    default int questHomeMap() {
        return -1;
    }

    /** The brain this host runs, or null (brain off, or a type without one). */
    default LifeRuntime lifeRuntime() {
        return null;
    }

    /** The activity the host is in now (with its start and expected length), or null to use the observation's. */
    default Current lifeCurrent(LifeProfile profile, long now) {
        return null;
    }

    /** A light host adjusts the needs it starts from (a social bot is among people, a wanderer gets restless). */
    default void lifeNeeds(Map<Need, Double> needs, long now) {
    }
}
