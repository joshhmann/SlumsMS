package soloMapling.autoqa;

import org.junit.jupiter.api.Test;
import soloMapling.autoqa.BotWatchJudge.Sample;
import soloMapling.autoqa.BotWatchJudge.Verdict;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 (owner 2026-10-26) bot watch: a track for each verdict, the picking, the teleport math and the bar. */
class BotWatchJudgeTest {

    static final BotWatchJudge.Limits LIM = BotWatchJudge.Limits.defaults();
    static final int FIELD = 104040000, HENESYS = 100000000, FM = 910000001;

    /** A sample builder: one bot standing still in a field, grinding; each test changes what it needs. */
    static final class S {
        long t;
        boolean online = true;
        int ch = 1, map = FIELD, x = 0, y = 0, target = -1, level = 30, kills, pots = 100, etc = 5, equip = 5;
        String act = "GRIND", chat;
        long exp = 1000, meso = 50_000, chatAt;
        boolean party, sit, town, mob = true, watched, jumpWatched, shop;
        int jump;

        Sample build() {
            return online ? new Sample(t, true, ch, map, x, y, act, target, null, level, exp, meso, kills, pots, etc,
                    equip, chat, chatAt, party, sit, town, mob, watched, jump, jumpWatched, shop) : Sample.offline(t);
        }
    }

    /** {@code n} samples 30 s apart; {@code at} edits the k-th one. */
    static List<Sample> track(int n, java.util.function.BiConsumer<Integer, S> at) {
        List<Sample> out = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            S s = new S();
            s.t = k * 30_000L;
            at.accept(k, s);
            out.add(s.build());
        }
        return out;
    }

    static BotWatchJudge.Judgement judge(List<Sample> t) {
        return BotWatchJudge.judge(t, LIM);
    }

    static UnaryOperator<S> town(UnaryOperator<S> more) {
        return s -> {
            s.map = HENESYS;
            s.town = true;
            s.mob = false;
            return more.apply(s);
        };
    }

    // ── GRIND ──

    @Test
    void grindThatGainsExpIsOk() {
        var j = judge(track(10, (k, s) -> { s.exp = 1000 + k * 50L; s.x = k * 40; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("EXP"), j.reason());
    }

    @Test
    void grindThatKillsIsOkEvenWithoutExp() {
        var j = judge(track(10, (k, s) -> { s.kills = k; s.x = k * 40; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("kills"), j.reason());
    }

    @Test
    void grindThatWalksAboutAMobMapWithNoExpIsAMismatch() {
        var j = judge(track(10, (k, s) -> s.x = k * 40));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
        assertTrue(j.reason().contains("no EXP or kill"), j.reason());
    }

    @Test
    void grindInTownIsAMismatch() {
        var j = judge(track(10, (k, s) -> { town(x -> x).apply(s); s.x = k * 40; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
        assertTrue(j.reason().contains("off any mob map"), j.reason());
    }

    @Test
    void grindOnTheWayToItsMapIsOk() {
        var j = judge(track(6, (k, s) -> { town(x -> x).apply(s); s.map = HENESYS + k; s.target = FIELD; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("on the way"), j.reason());
    }

    // ── IDLE ──

    @Test
    void aGrinderFrozenForTenMinutesIsIdle() {
        var j = judge(track(22, (k, s) -> { }));
        assertEquals(Verdict.IDLE, j.verdict(), j.reason());
        assertTrue(j.reason().contains("nothing changed for 10.5 min"), j.reason());
    }

    @Test
    void nineMinutesStillIsNotIdleYet() {
        var j = judge(track(19, (k, s) -> { })); // 9 min still: a MISMATCH (no EXP), not IDLE
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
    }

    @Test
    void aBreakMayStandStill() {
        var j = judge(track(30, (k, s) -> s.act = "BREAK"));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
    }

    @Test
    void anAfkCamperOnAChairMayStandStill() {
        var j = judge(track(30, (k, s) -> { town(x -> x).apply(s); s.act = "HANG_OUT"; s.sit = true; s.watched = true; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("sat"), j.reason());
    }

    @Test
    void aTownStayOffScreenIsParkedNotIdle() {
        var j = judge(track(30, (k, s) -> { town(x -> x).apply(s); s.act = "HANG_OUT"; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("off-screen"), j.reason());
    }

    @Test
    void aTownStayOnScreenFrozenForTenMinutesIsIdle() {
        var j = judge(track(30, (k, s) -> { town(x -> x).apply(s); s.act = "HANG_OUT"; s.watched = true; }));
        assertEquals(Verdict.IDLE, j.verdict(), j.reason());
        assertTrue(j.reason().contains("on screen"), j.reason());
    }

    @Test
    void aShortWatchScalesTheIdleLimit() {
        assertEquals(8 * 60_000L, BotWatchJudge.Limits.forWatch(10 * 60_000L).idleMs());
        assertEquals(10 * 60_000L, BotWatchJudge.Limits.forWatch(30 * 60_000L).idleMs());
        assertEquals(2 * 60_000L, BotWatchJudge.Limits.forWatch(60_000L).idleMs());
    }

    // ── HANG_OUT ──

    @Test
    void hangOutOnScreenThatChatsIsOk() {
        var j = judge(track(8, (k, s) -> {
            town(x -> x).apply(s);
            s.act = "HANG_OUT";
            s.watched = true;
            if (k >= 4) { s.chat = "anyone selling ilbis"; s.chatAt = 100_000; }
        }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("chatted"), j.reason());
    }

    @Test
    void hangOutOnScreenThatNeverMovesIsAMismatch() {
        var j = judge(track(8, (k, s) -> { town(x -> x).apply(s); s.act = "HANG_OUT"; s.watched = true; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
    }

    @Test
    void hangOutInAFieldIsAMismatch() {
        var j = judge(track(8, (k, s) -> { s.act = "HANG_OUT"; s.x = k * 50; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
        assertTrue(j.reason().contains("not in a town"), j.reason());
    }

    // ── RESTOCK, SELL, FREE_MARKET ──

    @Test
    void restockThatBuysPotionsIsOkWithTheSampleAfterIt() {
        // the trip ends (GRIND again) on the sample that shows the potions
        var j = judge(track(8, (k, s) -> {
            if (k < 5) { town(x -> x).apply(s); s.act = "RESTOCK"; s.pots = 3; s.x = k * 60; s.map = HENESYS + (k % 2); }
            else { s.pots = 200; s.exp = 2000 + k; }
        }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertEquals("potions 3 -> 200 after a town visit", j.segments().get(0).reason());
    }

    @Test
    void restockThatSitsInTownAndBuysNothingIsAMismatch() {
        var j = judge(track(26, (k, s) -> { town(x -> x).apply(s); s.act = "RESTOCK"; s.x = (k % 2) * 50; s.watched = true; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
        assertTrue(j.reason().contains("bought nothing"), j.reason());
    }

    @Test
    void sellThatGainsMesosIsOk() {
        var j = judge(track(8, (k, s) -> { s.map = FM; s.mob = false; s.act = "SELL"; s.meso = 1000 + k * 500L; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
    }

    @Test
    void sellWithNoMesoAndNoItemGoneIsAMismatch() {
        var j = judge(track(10, (k, s) -> { s.map = FM; s.mob = false; s.act = "SELL"; s.x = k * 30; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
        assertTrue(j.reason().contains("Free Market"), j.reason());
    }

    @Test
    void sellWithItsShopOpenIsOkWithoutABuyer() {
        // TEST 2026-10-26 (t-bw-1): a merchant bot's shop sat 10 min with no buyer and was called a mismatch
        var j = judge(track(10, (k, s) -> { s.map = FM; s.mob = false; s.act = "SELL"; s.x = k * 30; s.shop = true; }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.reason().contains("shop open"), j.reason());
    }

    @Test
    void freeMarketInTheFmIsOkAndOutsideItIsAMismatch() {
        assertEquals(Verdict.OK, judge(track(8, (k, s) -> { s.map = FM; s.mob = false; s.act = "FREE_MARKET"; })).verdict());
        var j = judge(track(10, (k, s) -> { town(x -> x).apply(s); s.act = "FREE_MARKET"; s.x = k * 30; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
    }

    // ── MOVE_MAP, PARTY_QUEST, EVENT, CHANGE_CHANNEL ──

    @Test
    void moveMapThatArrivesIsOk() {
        var j = judge(track(5, (k, s) -> {
            if (k < 3) { s.act = "MOVE_MAP"; s.target = k == 0 ? -1 : FIELD + 1; s.map = FIELD; s.x = k * 300; }
            else { s.map = FIELD + 1; s.target = FIELD + 1; s.exp = 2000 + k; }
        }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertEquals("reached map " + (FIELD + 1), j.segments().get(0).reason());
    }

    @Test
    void moveMapThatNeverGetsThereIsAMismatch() {
        var j = judge(track(24, (k, s) -> { s.act = "MOVE_MAP"; s.target = 200000000; s.x = (k % 3) * 100; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
        assertTrue(j.reason().contains("never reached map 200000000"), j.reason());
    }

    @Test
    void partyQuestInTheLobbyOrInAPartyIsOk() {
        assertEquals(Verdict.OK, judge(track(8, (k, s) -> { s.act = "PARTY_QUEST"; s.target = 103000800;
            s.map = k < 2 ? FIELD : 103000800; s.x = k * 20; })).verdict());
        assertEquals(Verdict.OK, judge(track(8, (k, s) -> { s.act = "PARTY_QUEST"; s.party = true; s.x = k * 20; })).verdict());
    }

    @Test
    void eventNeverReachedIsAMismatch() {
        var j = judge(track(24, (k, s) -> { s.act = "EVENT"; s.target = HENESYS; s.x = (k % 2) * 100; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
    }

    @Test
    void changeChannelThatStaysIsAMismatch() {
        assertEquals(Verdict.OK, judge(track(3, (k, s) -> { s.act = "CHANGE_CHANNEL"; s.ch = k == 0 ? 1 : 3; })).verdict());
        var j = judge(track(8, (k, s) -> { s.act = "CHANGE_CHANNEL"; s.x = k * 30; }));
        assertEquals(Verdict.MISMATCH, j.verdict(), j.reason());
    }

    // ── TELEPORTY ──

    @Test
    void aJumpWhereAPlayerWatchesIsTeleporty() {
        var j = judge(track(8, (k, s) -> { s.exp = 1000 + k; s.watched = true; if (k == 4) { s.jump = 1800; s.jumpWatched = true; } }));
        assertEquals(Verdict.TELEPORTY, j.verdict(), j.reason());
        assertTrue(j.reason().contains("jumped 1800 px"), j.reason());
    }

    @Test
    void aJumpOffScreenIsCountedButNotTeleporty() {
        // the movement driver relocates unwatched bots along baked edges: a jump nobody sees is by design
        var j = judge(track(8, (k, s) -> { s.exp = 1000 + k; if (k == 4) { s.jump = 1800; } }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertEquals(1, j.jumpsOffscreen());
    }

    @Test
    void excessJumpAllowsWalksFallsAndFlashJumpsButNotAMapAcross() {
        assertEquals(0, BotWatchJudge.excessJump(0, 0, 350, 0, 2000));    // a walk with Haste
        assertEquals(0, BotWatchJudge.excessJump(0, 0, 1000, 0, 2000));   // Flash Jump pace
        assertEquals(0, BotWatchJudge.excessJump(0, 0, 0, 1800, 2000));   // a long fall
        assertEquals(0, BotWatchJudge.excessJump(0, 0, 0, -1000, 2000));  // up a rope fast
        assertEquals(1600, BotWatchJudge.excessJump(0, 0, 2700, 0, 2000)); // across the map in 2 s
        assertEquals(400, BotWatchJudge.excessJump(0, 0, 0, -1500, 2000)); // straight up 1500 px in 2 s
    }

    // ── LOGGED_OUT ──

    @Test
    void loggingOutMidRestockIsLoggedOut() {
        var j = judge(track(6, (k, s) -> { s.act = "RESTOCK"; s.x = k * 100; if (k >= 3) { s.online = false; } }));
        assertEquals(Verdict.LOGGED_OUT, j.verdict(), j.reason());
        assertTrue(j.reason().contains("in the middle of RESTOCK"), j.reason());
    }

    @Test
    void loggingOutAfterAGrindIsASessionEnd() {
        var j = judge(track(6, (k, s) -> { s.exp = 1000 + k; if (k >= 3) { s.online = false; } }));
        assertEquals(Verdict.OK, j.verdict(), j.reason());
        assertTrue(j.segments().get(1).reason().contains("session over"), j.segments().get(1).reason());
    }

    @Test
    void loggingOutInAPartyIsLoggedOut() {
        var j = judge(track(6, (k, s) -> { s.exp = 1000 + k; s.party = true; if (k >= 3) { s.online = false; } }));
        assertEquals(Verdict.LOGGED_OUT, j.verdict(), j.reason());
    }

    // ── A bot that changes its mind ──

    @Test
    void theWorstStretchIsTheBotsVerdict() {
        // grinds fine, then a HANG_OUT on screen that never moves for 10 min: IDLE wins, named by its activity
        var j = judge(track(30, (k, s) -> {
            if (k < 6) { s.exp = 1000 + k; s.x = k * 40; }
            else { town(x -> x).apply(s); s.act = "HANG_OUT"; s.watched = true; s.exp = 1006; }
        }));
        assertEquals(Verdict.IDLE, j.verdict(), j.reason());
        assertTrue(j.reason().startsWith("HANG_OUT: "), j.reason());
        assertEquals(2, j.segments().size());
    }

    // ── Picking and the bar ──

    @Test
    void stratifyGivesEveryActivitySomeBotsRarestFirst() {
        List<Integer> grinders = new ArrayList<>();
        for (int i = 0; i < 1500; i++) {
            grinders.add(i);
        }
        Map<Integer, String> p = BotWatchJudge.stratify(Map.of("GRIND", grinders, "PARTY_QUEST", List.of(5000),
                "HANG_OUT", List.of(6000, 6001, 6002), "SELL", List.of()), 10, new Random(1));
        assertEquals(10, p.size());
        assertEquals("PARTY_QUEST", p.values().iterator().next()); // the rarest goes first
        assertEquals(1, p.values().stream().filter("PARTY_QUEST"::equals).count());
        assertEquals(3, p.values().stream().filter("HANG_OUT"::equals).count());
        assertEquals(6, p.values().stream().filter("GRIND"::equals).count());
        assertEquals(10, new HashSet<>(p.keySet()).size());
    }

    @Test
    void stratifyNeverPicksMoreThanThereAre() {
        assertEquals(2, BotWatchJudge.stratify(Map.of("GRIND", List.of(1, 2)), 40, new Random(1)).size());
        assertTrue(BotWatchJudge.stratify(Map.of(), 40, new Random(1)).isEmpty());
    }

    @Test
    void theBarIsEightyPercentOkTenPercentIdleAndNoTeleports() {
        assertNull(BotWatchJudge.checkProblem(summary(17, 1, 2, 0)));
        assertTrue(BotWatchJudge.checkProblem(summary(15, 1, 4, 0)).contains("75% OK"));
        assertTrue(BotWatchJudge.checkProblem(summary(16, 3, 1, 0)).contains("15% idle"));
        assertTrue(BotWatchJudge.checkProblem(summary(19, 0, 0, 1)).contains("1 teleported"));
        assertTrue(BotWatchJudge.checkProblem(summary(3, 0, 0, 0)).contains("only 3 bots"));
    }

    @Test
    void theSummaryLineNamesTheActivitiesThatWereNotOk() {
        var s = BotWatchJudge.summarize(List.of(Map.entry("GRIND", Verdict.OK), Map.entry("GRIND", Verdict.MISMATCH),
                Map.entry("HANG_OUT", Verdict.OK)));
        assertEquals("2/3 bots OK (67%), idle 0, mismatch 1, teleporty 0, logged out 0 over 10 min; not OK: GRIND 1/2",
                s.line(10));
        assertEquals(2, s.byActivity().get("GRIND").get("bots"));
        assertEquals(1, s.byActivity().get("GRIND").get("mismatch"));
    }

    static BotWatchJudge.Summary summary(int ok, int idle, int mismatch, int teleporty) {
        List<Map.Entry<String, Verdict>> v = new ArrayList<>();
        for (int i = 0; i < ok; i++) v.add(Map.entry("GRIND", Verdict.OK));
        for (int i = 0; i < idle; i++) v.add(Map.entry("GRIND", Verdict.IDLE));
        for (int i = 0; i < mismatch; i++) v.add(Map.entry("HANG_OUT", Verdict.MISMATCH));
        for (int i = 0; i < teleporty; i++) v.add(Map.entry("GRIND", Verdict.TELEPORTY));
        return BotWatchJudge.summarize(v);
    }

    @Test
    void phasesMapToTheActivityTheyStandFor() {
        assertEquals("GRIND", BotWatch.phaseActivity("GO_TRAIN", null));
        assertEquals("RESTOCK", BotWatch.phaseActivity("SHOP_DWELL", null));
        assertEquals("BREAK", BotWatch.phaseActivity("BREAK_REST", null));
        assertEquals("FREE_MARKET", BotWatch.phaseActivity("LIFE_TRAVEL",
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity.FREE_MARKET));
        assertEquals("NONE", BotWatch.phaseActivity("INIT", null));
    }
}
