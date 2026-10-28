package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBuddies.Familiarity;
import soloMapling.ArtificialPlayer.BotFamily.Gate;
import soloMapling.ArtificialPlayer.BotFamily.OfferCheck;
import soloMapling.ArtificialPlayer.BotFamily.Verdict;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 1.6.1 ticket 53: a regular becomes a player's junior only with players it has interacted with, and never spams. */
class BotFamilyTest {

    static final long NOW = 10_000_000_000L;

    static Familiarity f(boolean known, boolean wary, int lines, boolean party) {
        return new Familiarity(known, wary, lines, party, 0.2);
    }

    @Test
    void acceptsOnlyPlayersItHasInteractedWith() {
        assertEquals(Verdict.STRANGER, BotFamily.accepts(true, false, f(false, false, 0, false), false, 0.0));
        assertEquals(Verdict.ACCEPT, BotFamily.accepts(true, false, f(true, false, 0, false), false, 0.1));
        assertEquals(Verdict.ACCEPT, BotFamily.accepts(true, false, f(false, false, 0, true), false, 0.1), "party");
        assertEquals(Verdict.ACCEPT, BotFamily.accepts(true, false, f(false, false, 3, false), false, 0.1), "talked");
        assertEquals(Verdict.ROLL, BotFamily.accepts(true, false, f(true, false, 5, true), false, 0.95));
        assertEquals(Verdict.WARY, BotFamily.accepts(true, false, f(true, true, 5, true), true, 0.0), "even staged");
        assertEquals(Verdict.IN_FAMILY, BotFamily.accepts(true, true, f(true, false, 5, true), true, 0.0));
        assertEquals(Verdict.OFF, BotFamily.accepts(false, false, f(true, false, 5, true), true, 0.0));
        assertEquals(Verdict.ACCEPT, BotFamily.accepts(true, false, f(false, false, 0, false), true, 0.99), "QA staging");
    }

    static OfferCheck check(boolean known, int lines, int botLv, int playerLv, int juniors, long askedAt,
                            int playerToday, int worldHour, double roll) {
        return new OfferCheck(true, true, false, known, false, lines, botLv, playerLv, juniors, askedAt, playerToday,
                worldHour, roll, NOW);
    }

    @Test
    void offersRarelyAndOnlyToAKnownPlayerWithRoom() {
        assertEquals(Gate.OK, BotFamily.offerGate(check(true, 3, 30, 40, 0, 0, 0, 0, 0.05)));
        assertEquals(Gate.ROLL, BotFamily.offerGate(check(true, 3, 30, 40, 0, 0, 0, 0, 0.5)));
        assertEquals(Gate.STRANGER, BotFamily.offerGate(check(false, 9, 30, 40, 0, 0, 0, 0, 0.0)));
        assertEquals(Gate.SHORT_CHAT, BotFamily.offerGate(check(true, 2, 30, 40, 0, 0, 0, 0, 0.0)));
        assertEquals(Gate.LEVELS, BotFamily.offerGate(check(true, 3, 10, 20, 0, 0, 0, 0, 0.0)), "junior above 10");
        assertEquals(Gate.LEVELS, BotFamily.offerGate(check(true, 3, 40, 35, 0, 0, 0, 0, 0.0)), "senior not below");
        assertEquals(Gate.LEVELS, BotFamily.offerGate(check(true, 3, 30, 51, 0, 0, 0, 0, 0.0)), "within 20");
        assertEquals(Gate.PLAYER_FULL, BotFamily.offerGate(check(true, 3, 30, 40, 2, 0, 0, 0, 0.0)));
        assertEquals(Gate.TOO_SOON, BotFamily.offerGate(check(true, 3, 30, 40, 0,
                NOW - TimeUnit.DAYS.toMillis(6), 0, 0, 0.0)), "once a week per pair");
        assertEquals(Gate.OK, BotFamily.offerGate(check(true, 3, 30, 40, 0,
                NOW - TimeUnit.DAYS.toMillis(8), 0, 0, 0.0)));
        assertEquals(Gate.PLAYER_DAILY_CAP, BotFamily.offerGate(check(true, 3, 30, 40, 0, 0, 1, 0, 0.0)));
        assertEquals(Gate.WORLD_CAP, BotFamily.offerGate(check(true, 3, 30, 40, 0, 0, 0, 3, 0.0)));
        assertEquals(Gate.OFF, BotFamily.offerGate(new OfferCheck(false, true, false, true, false, 3, 30, 40, 0, 0, 0,
                0, 0.0, NOW)));
        assertEquals(Gate.IN_FAMILY, BotFamily.offerGate(new OfferCheck(true, true, true, true, false, 3, 30, 40, 0, 0,
                0, 0, 0.0, NOW)));
        assertEquals(Gate.WARY, BotFamily.offerGate(new OfferCheck(true, true, false, true, true, 3, 30, 40, 0, 0, 0,
                0, 0.0, NOW)));
    }
}
