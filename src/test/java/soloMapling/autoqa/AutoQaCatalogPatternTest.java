package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue's expectations against lines in the exact shape the game code writes them (copied from the hook
 * strings), including the cases the 2026-10-22 regex review found wrong. Each test feeds a check's expectations a
 * scripted log and looks at the verdicts - no steps run.
 */
class AutoQaCatalogPatternTest {

    /** Evaluates every expectation of a check against these lines; the verdicts in order. */
    static List<Check.Verdict> eval(String id, String... lines) throws Exception {
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        w.actor(1);
        for (String l : lines) {
            int sp = l.indexOf(' ');
            String kind = l.substring(0, sp), msg = l.substring(sp + 1);
            if (kind.startsWith("heard:")) {
                ((AutoQaRunnerTest.FakeActor) w.actor(1)).hear(kind.substring(6), msg);
            } else {
                w.qa(kind, msg);
            }
            w.now += 1000;
        }
        Check c = AutoQaCatalog.find(id);
        return evalAll(c, runAt(c, w));
    }

    static CheckRun runAt(Check c, AutoQaRunnerTest.FakeWorld w) {
        long now = w.now;
        w.now = 0; // the run "started" before every line
        CheckRun r = new CheckRun(c, w);
        w.now = now;
        r.actor(1);
        return r;
    }

    static List<Check.Verdict> evalAll(Check c, CheckRun r) throws Exception {
        List<Check.Verdict> out = new ArrayList<>();
        for (Check.Expect e : c.expects()) {
            out.add(e.eval(r));
        }
        return out;
    }

    static Check.Status status(List<Check.Verdict> v, int i) {
        return v.get(i).status();
    }

    /** t-cv-5 answers the thread by its subject: the staging hands it over as "VAR thread=..." in a STAGE line. */
    @Test
    void cv5LearnsTheThreadsSubjectFromItsStaging() {
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        w.actor(1);
        w.qa("STAGE", "told GM: thread: [looking for a party]");
        w.qa("STAGE", "told GM: VAR thread=Ludi PQ");
        w.now += 1000;
        Check c = AutoQaCatalog.find("t-cv-5");
        CheckRun r = runAt(c, w);
        r.lines();
        assertEquals("Ludi PQ? i can come too", r.fill("{thread}? i can come too"));
    }

    @Test
    void gu5ResultNamesTheGuildAndBossBeforeTheUpperCaseOutcome() throws Exception {
        List<Check.Verdict> v = eval("t-gu-5",
                "BOT_GUILD BOSS RUN SnailGang -> Zakum: crew Mochi, Nacho on ch1 map 211042300",
                "BOT_GUILD REAL Mochi went with AutoQaOne",
                "BOT_GUILD INSIDE Nacho left the world",
                "BOT_GUILD BACK Nacho",
                "BOT_GUILD RESULT SnailGang ZAKUM: WIPED, avg lv 92");
        for (int i = 0; i < v.size(); i++) {
            assertEquals(Check.Status.PASS, status(v, i), v.get(i).toString());
        }
    }

    @Test
    void id1SafeSpotsAreUpperCaseKinds() throws Exception {
        List<Check.Verdict> v = eval("t-id-1",
                "IDLE Mochi idle(rest) HIT at (10,20) -> ROPE_TOP (40,-80) clearance 120 travel 3",
                "TALLY Mochi: hits 2, moves 1");
        assertEquals(Check.Status.PASS, status(v, 0), v.get(0).toString());
    }

    @Test
    void cv6AcceptsEveryAnswerRateGives() throws Exception {
        for (String answer : List.of("Bank line #12 weighs x0.25 now.", "Bank line #12 is blacklisted: it won't be said again.",
                "Kept as a bad example; the quoted phrase is on the don't-say list. (data/convo/tune.json)",
                "A scripted line: noted in the ratings only.")) {
            List<Check.Verdict> v = eval("t-cv-6", "CONVO RATE bad by player 1a2b: x", "heard:YELLOW " + answer);
            assertEquals(Check.Status.PASS, status(v, 1), answer);
        }
    }

    @Test
    void td1ReadsTheSimulatedSaleFromItsStepLines() throws Exception {
        List<Check.Verdict> v = eval("t-td-1", "STEP BUY offer 900000 band 700000..950000 for 2044001",
                "STEP player: \"1.5m?\" -> OFFER 1500000", "STEP HAGGLE theirs 1500000 -> COUNTER 920000");
        assertEquals(Check.Status.PASS, status(v, 0));
        assertEquals(Check.Status.PASS, status(v, 1));
    }

    @Test
    void td3NeedsTheZeroSummaryAndElevenAnswersInsideTheBand() throws Exception {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            lines.add("STAGE   bot sells, fair 1,000,000, opens at 1,190,000 -> COUNTER at 950,000 (inside the band)");
        }
        lines.add("SUMMARY 0 answers outside the band (must be 0) over 11 lines");
        List<Check.Verdict> v = eval("t-td-3", lines.toArray(new String[0]));
        v.forEach(x -> assertEquals(Check.Status.PASS, x.status(), x.toString()));
    }

    @Test
    void dramaBotsAreLearnedFromTheirStagedAffinityLine() throws Exception {
        List<Check.Verdict> v = eval("t-rm-1",
                "AFFINITY STAGED bot 1000000042 -> 101 friend",
                "ROMANCE FLIRT Bot42 -> AutoQaOne: u look cute today",
                "ROMANCE DATING Bot42 + AutoQaOne",
                "ROMANCE FOLLOW Bot42 followed AutoQaOne",
                "heard:WHISPER Bot42: miss u already",
                "ROMANCE JEALOUS Bot42 sees Rival1");
        // FLIRT, DATING, FOLLOW, WHISPER {bot}, JEALOUS
        for (int i = 0; i < 5; i++) {
            assertEquals(Check.Status.PASS, status(v, i), v.get(i).toString());
        }
    }

    @Test
    void rm4CountsARealCrashNotTheNobodyToCrashLine() throws Exception {
        List<Check.Verdict> nobody = eval("t-rm-4", "ROMANCE CRASH: nobody to crash the reception (no ex or rival online)");
        assertEquals(Check.Status.FAIL, status(nobody, 1));
        List<Check.Verdict> real = eval("t-rm-4", "ROMANCE CRASH Ex1 crashes the reception of AutoQaOne & Bot42");
        assertEquals(Check.Status.PASS, status(real, 1));
    }

    @Test
    void cu2IgnoresCouplesItDidNotStage() throws Exception {
        List<Check.Verdict> v = eval("t-cu-2",
                "STAGE staged friends pair 1000000042 + 1000000043 (same gender)",
                "COUPLE PARTY Bot42 + Bot43: joined",
                "COUPLE SIT Other1 + Other2 on chair 3010000",
                "COUPLE MARRIED couple #9 Other1 + Other2");
        v.forEach(x -> assertEquals(Check.Status.PASS, x.status(), x.toString()));
        List<Check.Verdict> sat = eval("t-cu-2", "STAGE staged friends pair 1000000042 + 1000000043",
                "COUPLE PARTY Bot42 + Bot43: joined", "COUPLE SIT Bot43 + Bot42 on chair 3010000");
        assertEquals(Check.Status.FAIL, status(sat, 1));
    }

    @Test
    void ga1WantsTheJackpotNoticeNotAnyNotice() throws Exception {
        Check c = AutoQaCatalog.find("t-ga-1");
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = runAt(c, w);
        r.mark("jackpot");
        ((AutoQaRunnerTest.FakeActor) w.actor(1)).hear("NOTICE", "[6] Welcome to Henesys");
        Check.Expect notice = c.expects().get(3);
        assertEquals(Check.Status.FAIL, notice.eval(r).status(), notice.describe());
        ((AutoQaRunnerTest.FakeActor) w.actor(1)).hear("NOTICE", "[11] AutoQaOne : got a(n)");
        assertEquals(Check.Status.PASS, notice.eval(r).status());
    }

    @Test
    void rv2CountsOnlyTheCalledBotsDuringTheWar() {
        Check c = AutoQaCatalog.find("t-rv-2");
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = runAt(c, w);
        for (int i = 0; i < 7; i++) {
            w.qa("AFFINITY", "KS Nemesis takes Snail (oid " + i + ") from AutoQaOne, 3 left"); // its own grudge spree
            w.now += 10;
        }
        r.mark("war");
        w.now += 10;
        w.qa("RIVALRY", "KS WAR Nemesis calls 2 bot(s) on AutoQaOne (Helper1, Helper2) for 180 s");
        for (int i = 0; i < 5; i++) {
            w.now += 10;
            w.qa("AFFINITY", "KS Helper1 takes Snail (oid " + i + ") from AutoQaOne, 3 left");
        }
        assertEquals(null, AutoQaCatalog.ksAtMostFive(r));
        w.now += 10;
        w.qa("AFFINITY", "KS Helper1 takes Snail (oid 99) from AutoQaOne, 3 left");
        assertTrue(AutoQaCatalog.ksAtMostFive(r).contains("Helper1 took 6"));
    }

    @Test
    void rv2CountsTheWarEvenWhenTheNemesisStartsItBeforeTheMark() {
        // On TEST 2026-10-24 16:20 the nemesis called its own war 14 s into the run, before the harness reached its
        // mark; the old rule looked for a call after the mark, found none, and failed a war that behaved correctly.
        Check c = AutoQaCatalog.find("t-rv-2");
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = runAt(c, w);
        w.qa("RIVALRY", "KS WAR Nemesis calls 2 bot(s) on AutoQaOne (Helper1, Helper2) for 180 s");
        for (int i = 0; i < 6; i++) {
            w.now += 10;
            w.qa("AFFINITY", "KS Helper1 takes Snail (oid " + i + ") from AutoQaOne, 3 left");
        }
        r.mark("war");
        assertTrue(AutoQaCatalog.ksAtMostFive(r).contains("Helper1 took 6"));
        w.now += 10;
        w.qa("RIVALRY", "KS WAR over on 44 (stopped): 2 bot(s) went back");
        w.now += 10;
        w.qa("AFFINITY", "KS Helper1 takes Snail (oid 99) from AutoQaOne, 3 left"); // after the war: not its mobs
        assertTrue(AutoQaCatalog.ksAtMostFive(r).contains("Helper1 took 6"), "a take after the war is not counted");
    }

    @Test
    void td2FloorIsTheBandsLowEndNotTheOpeningAsk() {
        Check c = AutoQaCatalog.find("t-td-2");
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = runAt(c, w);
        w.qa("TRADE", "QUOTE Bot42 offers AutoQaOne sword at 1,190,000 (band 950,000..1,250,000)");
        w.now += 10;
        w.qa("TRADE", "QUOTE haggle 1000 -> COUNTER 960,000");
        assertEquals(null, AutoQaCatalog.haggleFloor(r));
        w.now += 10;
        w.qa("TRADE", "QUOTE haggle 1000 -> FINAL 900,000");
        assertTrue(AutoQaCatalog.haggleFloor(r).contains("under its band's low end"));
    }

    @Test
    void cb1AllowsTheAoeOnAFewMobsButNotASingleOnAPack() {
        Check c = AutoQaCatalog.find("t-cb-1");
        AutoQaRunnerTest.FakeWorld w = new AutoQaRunnerTest.FakeWorld();
        CheckRun r = runAt(c, w);
        w.qa("COMBAT", "CHOICE Bob Crusader: 1 in AoE reach -> AOE 1111008 (FEW)");
        w.qa("COMBAT", "CHOICE Bob Crusader: 5 in AoE reach, boss -> AOE 1111008 (BOSS)");
        w.qa("COMBAT", "CHOICE Bob Crusader: 4 in AoE reach -> SINGLE 1111003 (SLOPPY_SINGLE)");
        assertEquals(null, AutoQaCatalog.choicesSensible(r));
        w.qa("COMBAT", "CHOICE Bob Crusader: 4 in AoE reach -> SINGLE 1111003 (PACK)");
        assertTrue(AutoQaCatalog.choicesSensible(r).startsWith("1 of 4"));
    }

    @Test
    void anUnknownBotFailsCleanlyInsteadOfPassingALacksOrThrowing() throws Exception {
        // cu-2 with no staged pair: the {staged} lacks must not pass vacuously, and nothing may throw
        List<Check.Verdict> v = eval("t-cu-2", "COUPLE SIT Other1 + Other2 on chair 3010000");
        v.forEach(x -> assertEquals(Check.Status.FAIL, x.status(), x.toString()));
    }
}
