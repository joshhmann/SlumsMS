package soloMapling.companions;

import client.Character;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionItems;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance;
import soloMapling.server.BotTiming;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 1.6.2 ticket 99: what makes an owned alt take the shared companion orders - the alt side only (who it obeys and
 * how it talks); the orders themselves are the shared ones ({@link CompanionStance} for stances and formations,
 * {@link CompanionItems} for trades, upgrades and supplies), the same a party bot friend takes. An alt obeys its owner
 * only, answers in party chat after a person's typing beat, and never leaves its owner's side on its own: stances
 * hold a spot inside the activity gate ({@link OwnedAltGate}), they are no autopilot.
 */
public final class OwnedAltOrders {

    private OwnedAltOrders() {
    }

    // nutnnut/Cosmic BotChatManager FOLLOW_REPLIES / STOP_REPLIES / MOVE_HERE_REPLIES, trimmed
    private static final List<String> FOLLOW_LINES = List.of("ok", "k", "omw", "coming", "right behind you", "kk");
    private static final List<String> STAY_LINES = List.of("ok ill wait here", "k", "staying put", "standing by");
    private static final List<String> MOVE_LINES = List.of("k, coming", "omw", "ok heading over", "k moving there");
    private static final List<String> SENTRY_LINES = List.of("k, holding here", "ok ill guard this spot",
            "got it, camping here");
    private static final List<String> PATROL_LINES = List.of("k, ill walk around here", "ok patrolling",
            "got it, roaming here");

    /** The alt's voice: party chat to its owner, a person's typing beat later. */
    public static void say(Character alt, String line) {
        if (alt == null || line == null || !alt.isOwnedAlt()) {
            return;
        }
        Character owner = alt.getOwnedAltOwner();
        BotTiming.afterRandom(600, 1500, () -> OwnedAltCompanion.say(owner, alt, line));
    }

    /** The alt as a companion for the shared item orders. */
    public static CompanionItems.Companion companion(Character alt) {
        return new CompanionItems.Companion(alt, line -> say(alt, line));
    }

    /**
     * A movement order from {@code player}: an alt obeys its owner only. "grind" to an alt holds this spot and fights
     * what comes (a sentry): an alt never goes training on its own. True when it answered.
     */
    public static boolean order(Character alt, Character player, Verb verb, boolean everyone) {
        if (!soloMapling.ArtificialPlayer.BotPartySystem.CompanionSwitches.orders()) {
            return false; // alts take the movement orders only with COMPANION_ORDERS_ENABLED
        }
        if (alt == null || player == null || alt.getOwnedAltOwner() == null
                || alt.getOwnedAltOwner().getId() != player.getId()) {
            return false;
        }
        Verb v = verb == Verb.GRIND ? Verb.SENTRY : verb;
        CompanionStance.Mode mode = CompanionStance.modeOf(v);
        if (mode == null) {
            return false;
        }
        CompanionStance.Stance before = CompanionStance.of(alt.getId(), alt.getMapId());
        CompanionStance.set(alt.getId(), mode, alt.getPosition(),
                player.getMap() == alt.getMap() ? player.getPosition() : null, alt.getMapId());
        if (everyone && before.mode == mode && mode == CompanionStance.Mode.FOLLOW) {
            return false; // already following: no need for every alt to say so
        }
        say(alt, pick(switch (mode) {
            case FOLLOW -> FOLLOW_LINES;
            case STAY -> STAY_LINES;
            case MOVE_HERE -> MOVE_LINES;
            case SENTRY -> SENTRY_LINES;
            case PATROL -> PATROL_LINES;
        }));
        return true;
    }

    private static String pick(List<String> lines) {
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }
}
