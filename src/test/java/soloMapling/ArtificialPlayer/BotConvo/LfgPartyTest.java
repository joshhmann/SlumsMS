package soloMapling.ArtificialPlayer.BotConvo;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0: an LFG thread leads to a real party and PQ trip when the other bot can come, and says so either way. */
class LfgPartyTest {

    static final long T0 = 1_793_000_000_000L;

    @AfterEach
    void reset() {
        LfgParty.resetForTest();
        BotThreads.resetForTest();
        System.clearProperty("solomapling.convo.lfgPartiesPer10Min");
    }

    static LfgParty.State free(int level) {
        return new LfgParty.State(level, false, false, false, false, false, true);
    }

    static LfgParty.Plan plan(int levelA, LfgParty.State b, long now) {
        return LfgParty.decide(1, "Kimchi", free(levelA), 2, "Bob", b, now);
    }

    private static Character chr(int slot, String name, int level) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(BotStore.BOT_ID_BASE + slot);
        c.setName(name);
        c.setLevel(level);
        return c;
    }

    @Test
    void thePqIsTheRecruitersLevelsLobbyInLifePqsBands() {
        // TEST trace 2026-10-26: a 32 asked for Ludi PQ, a 78 for Ludi, a 71 for Kerning PQ
        assertNull(LfgParty.lobbyFor(32));
        assertNull(LfgParty.lobbyFor(71));
        assertNull(LfgParty.lobbyFor(78));
        assertEquals("Kerning PQ", LfgParty.lobbyFor(25).label());
        assertEquals("Ludi PQ", LfgParty.lobbyFor(35).label());
        assertEquals("Orbis PQ", LfgParty.lobbyFor(70).label());
        for (int lv = 1; lv <= 200; lv++) {
            LifePq.Lobby lobby = LfgParty.lobbyFor(lv);
            assertEquals(LifePq.lobbyFor(lv), lobby, "LFG and LifePq agree at " + lv);
            if (lobby != null) {
                assertEquals(lobby, LifePq.byLabel(lobby.label()));
            }
        }
    }

    @Test
    void anLfgTopicOnlyComesUpWhenTheRecruiterFitsAPq() {
        Random rng = new Random(4);
        int lfg = 0;
        for (int i = 0; i < 300; i++) {
            assertTrue(BotThreads.topicFor(0, 0, 32, 25, 100000000, rng) != BotThreads.Topic.LFG, "lv 32: no lobby");
            assertTrue(BotThreads.topicFor(0, 0, 78, 40, 100000000, rng) != BotThreads.Topic.LFG, "lv 78: no lobby");
            lfg += BotThreads.topicFor(0, 0, 25, 80, 100000000, rng) == BotThreads.Topic.LFG ? 1 : 0;
        }
        assertTrue(lfg > 50, "a 25 recruiter talks LFG: " + lfg);
    }

    @Test
    void theThreadsLabelIsTheRecruitersPq() {
        assertEquals("Ludi PQ", BotThreads.about(BotThreads.Topic.LFG, chr(80_001, "Kimchi", 40), chr(80_002, "Bob", 25)));
        assertEquals("Kerning PQ", BotThreads.about(BotThreads.Topic.LFG, chr(80_001, "Kimchi", 25), chr(80_002, "Bob", 60)));
        assertNull(BotThreads.about(BotThreads.Topic.LFG, chr(80_001, "Kimchi", 71), chr(80_002, "Bob", 25)));
    }

    @Test
    void theOtherBotMustFitTheBandAndBeFree() {
        LifePq.Lobby kpq = LifePq.lobbyFor(25);
        assertEquals(LfgParty.Why.YES, LfgParty.fit(kpq, free(21)));
        assertEquals(LfgParty.Why.YES, LfgParty.fit(kpq, free(30)));
        assertEquals(LfgParty.Why.TOO_LOW, LfgParty.fit(kpq, free(20)));
        assertEquals(LfgParty.Why.TOO_HIGH, LfgParty.fit(kpq, free(31)));
        assertEquals(LfgParty.Why.PARTIED, LfgParty.fit(kpq, new LfgParty.State(25, true, false, false, false, false, true)));
        assertEquals(LfgParty.Why.BUSY, LfgParty.fit(kpq, new LfgParty.State(25, false, true, false, false, false, true)));
        assertEquals(LfgParty.Why.AFK, LfgParty.fit(kpq, new LfgParty.State(25, false, false, true, false, false, true)));
        assertEquals(LfgParty.Why.SHOP, LfgParty.fit(kpq, new LfgParty.State(25, false, false, false, true, false, true)));
        assertEquals(LfgParty.Why.OWNED, LfgParty.fit(kpq, new LfgParty.State(25, false, false, false, false, true, true)));
        assertEquals(LfgParty.Why.NO_TRIP, LfgParty.fit(kpq, new LfgParty.State(25, false, false, false, false, false, false)));
        assertEquals(LfgParty.Why.NO_PQ, LfgParty.fit(null, free(25)));
    }

    @Test
    void theRecruiterMustBeFreeTooAndHaveAPq() {
        LfgParty.Plan busy = LfgParty.decide(1, "Kimchi", new LfgParty.State(25, true, false, false, false, false, true),
                2, "Bob", free(25), T0);
        assertEquals(LfgParty.Why.RECRUITER_BUSY, busy.why());
        assertEquals(LfgParty.Why.NO_PQ, plan(32, free(32), T0).why());
        LfgParty.Plan yes = plan(40, free(36), T0);
        assertTrue(yes.yes());
        assertEquals("Ludi PQ", yes.lobby().label());
        assertEquals("party up for Ludi PQ", yes.decision());
        assertEquals(LfgParty.Why.TOO_LOW, plan(40, free(30), T0).why(), "a 30 can't come to the 40's Ludi PQ");
    }

    @Test
    void atMostThreeLfgPartiesPerTenMinutesWorldWide() {
        for (int i = 0; i < 3; i++) {
            assertTrue(plan(25, free(25), T0 + i).yes());
        }
        LfgParty.Plan fourth = plan(25, free(25), T0 + 10);
        assertEquals(LfgParty.Why.BUDGET, fourth.why());
        assertEquals(0, fourth.reservedAt(), "a no holds no slot");
        LfgParty.release(plan(25, free(25), T0 + 20)); // still capped: nothing reserved, nothing freed
        assertEquals(LfgParty.Why.BUDGET, plan(25, free(25), T0 + 30).why());
        assertTrue(plan(25, free(25), T0 + LfgParty.WINDOW_MS).yes(), "the oldest slot is ten minutes old");
    }

    @Test
    void aFailedPartyGivesItsSlotBack() {
        System.setProperty("solomapling.convo.lfgPartiesPer10Min", "1");
        LfgParty.Plan p = plan(25, free(25), T0);
        assertTrue(p.yes());
        assertEquals(LfgParty.Why.BUDGET, plan(25, free(25), T0 + 1).why());
        LfgParty.release(p);
        assertTrue(plan(25, free(25), T0 + 2).yes());
    }

    @Test
    void theBankSaysTheDecidedAnswer() {
        Random rng = new Random(5);
        for (int i = 0; i < 50; i++) {
            LfgParty.resetForTest();
            LfgParty.Plan yes = plan(25, free(26), T0);
            List<String[]> lines = LfgParty.lines(yes, rng);
            assertEquals("Kimchi", lines.get(0)[0]);
            assertTrue(lines.get(0)[1].contains("kpq"), lines.get(0)[1]);
            assertEquals("Bob", lines.get(1)[0]);
            assertTrue(LfgParty.truthful(yes, lines), lines.get(1)[1]);

            LfgParty.Plan low = plan(25, free(15), T0);
            List<String[]> no = LfgParty.lines(low, rng);
            assertEquals(2, no.size());
            assertTrue(LfgParty.truthful(low, no), no.get(1)[1]);
            assertFalse(LfgParty.agrees(no.get(1)[1]), no.get(1)[1]);
        }
        LfgParty.Plan partied = plan(25, new LfgParty.State(25, true, false, false, false, false, true), T0);
        assertTrue(LfgParty.lines(partied, new Random(1)).get(1)[1].matches(".*(party|pt|partied).*"));
    }

    @Test
    void aModelLineThatSaysTheWrongAnswerIsNotTruthful() {
        LfgParty.Plan yes = plan(25, free(26), T0);
        LfgParty.Plan no = plan(25, free(15), T0);
        List<String[]> agreeing = List.of(new String[]{"Kimchi", "lf kpq"}, new String[]{"Bob", "ya inv me"});
        List<String[]> refusing = List.of(new String[]{"Kimchi", "lf kpq"}, new String[]{"Bob", "nah too low lol"});
        List<String[]> waffling = List.of(new String[]{"Kimchi", "lf kpq"}, new String[]{"Bob", "hmm idk"});
        assertTrue(LfgParty.truthful(yes, agreeing));
        assertFalse(LfgParty.truthful(yes, refusing));
        assertFalse(LfgParty.truthful(yes, waffling), "a yes must be said");
        assertTrue(LfgParty.truthful(no, refusing));
        assertTrue(LfgParty.truthful(no, waffling));
        assertFalse(LfgParty.truthful(no, agreeing), "a 15 saying yes to kpq would be a lie");
    }

    @Test
    void thePromptTellsTheModelTheAnswer() {
        Character a = chr(80_001, "Kimchi", 25);
        Character b = chr(80_002, "Bob", 15);
        LfgParty.Plan low = plan(25, free(15), T0);
        String prompt = BotThreads.threadPrompt(a, b, BotThreads.Topic.LFG, "Kerning PQ", "Henesys", null, low, List.of());
        assertTrue(prompt.contains("Kimchi is looking for a party for Kerning PQ (levels 21-30)"), prompt);
        assertTrue(prompt.contains("Bob is too low for it (level 15, it needs 21) and says no"), prompt);
        LfgParty.Plan yes = plan(25, free(26), T0);
        assertTrue(LfgParty.promptTurn(yes).contains("Bob agrees to come"), LfgParty.promptTurn(yes));
    }

    @Test
    void onAYesTheInviteAndTripMayBeSaidOnANoOnlyTheAsk() {
        LfgParty.Plan yes = plan(25, free(26), T0);
        LfgParty.Plan no = plan(25, free(15), T0);
        assertTrue(LfgParty.allowed(yes).contains(ClaimRules.Kind.PARTY_OFFER));
        assertTrue(LfgParty.allowed(yes).contains(ClaimRules.Kind.COME));
        assertEquals(java.util.EnumSet.of(ClaimRules.Kind.PARTY_ASK), LfgParty.allowed(no));
        assertEquals(java.util.EnumSet.of(ClaimRules.Kind.PARTY_ASK), LfgParty.allowed(null));
    }

    @Test
    void aTripIsACommitmentUntilItEnds() {
        LfgParty.Plan p = plan(25, free(26), T0);
        LfgParty.holdForTest(p, T0 + LfgParty.TRIP_MS);
        assertTrue(LfgParty.holds(1, T0 + 1));
        assertTrue(LfgParty.holds(2, T0 + 1));
        assertFalse(LfgParty.holds(3, T0 + 1));
        assertFalse(LfgParty.holds(2, T0 + LfgParty.TRIP_MS));
        // a bot already on a trip is busy for the next thread
        LfgParty.State onTrip = new LfgParty.State(26, false, LfgParty.holds(2, T0 + 1), false, false, false, true);
        assertEquals(LfgParty.Why.BUSY, LfgParty.fit(p.lobby(), onTrip));
    }
}
