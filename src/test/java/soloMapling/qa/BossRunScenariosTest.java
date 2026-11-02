package soloMapling.qa;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ex-2's judgement, without a server: the verdict wording is the part a reader of the log has to trust, so every
 * branch of it is pinned here, including the ones that must NOT say PASS.
 */
class BossRunScenariosTest {

    private static BossRunScenarios.Observed seen(boolean bossOn, boolean cleared, String ended, int inRoom,
                                                  long swings, long damage, boolean abandon, boolean altar,
                                                  boolean windowOver) {
        return new BossRunScenarios.Observed(120, bossOn, cleared, ended, inRoom, 6, swings, damage, abandon, altar,
                windowOver);
    }

    @Test
    void aClearIsTheOnlyPass() {
        String line = BossRunScenarios.verdict(seen(true, true, null, 6, 400, 90_000, false, true, false));
        assertTrue(line.startsWith("EX2 PASS cleared "), line);
        assertTrue(line.contains("swings=400"), line);
    }

    @Test
    void switchesOffIsABaselineNotAVerdict() {
        String line = BossRunScenarios.verdict(seen(false, false, null, 6, 0, 0, false, true, false));
        assertTrue(line.startsWith("EX2 BASELINE boss=off "), line);
        assertFalse(line.contains("PASS") || line.contains("FAIL"), line);
    }

    @Test
    void anAbandonThatEndedTheRunIsReportedWithItsReason() {
        String line = BossRunScenarios.verdict(
                seen(true, false, "the kill target 8800002 is not on the map", 6, 40, 9_000, true, true, false));
        assertTrue(line.startsWith("EX2 FAIL the run ended: "), line);
        assertTrue(line.contains("8800002"), line);
    }

    @Test
    void anAbandonWithoutAnEndReasonStillSaysAbandoned() {
        String line = BossRunScenarios.verdict(seen(true, false, null, 6, 40, 9_000, true, true, false));
        assertTrue(line.startsWith("EX2 FAIL the run abandoned "), line);
    }

    @Test
    void aRunThatEndedIsNotBlamedOnTheAltar() {
        String line = BossRunScenarios.verdict(seen(true, false, "event-disposed", 0, 0, 0, false, false, false));
        assertTrue(line.contains("the run ended: event-disposed"), line);
        assertFalse(line.contains("Eye of Fire"), line);
    }

    @Test
    void noAltarClickFailsBeforeItBlamesTheBots() {
        String line = BossRunScenarios.verdict(seen(true, false, null, 6, 0, 0, false, false, false));
        assertTrue(line.contains("the Eye of Fire was never opened"), line);
    }

    @Test
    void nobodyInTheRoomFailsWithTheRoomNamed() {
        String line = BossRunScenarios.verdict(seen(true, false, null, 0, 0, 0, false, true, false));
        assertTrue(line.contains("nobody reached 280030000"), line);
    }

    @Test
    void standingThereWithoutSwingingIsAFail() {
        String line = BossRunScenarios.verdict(seen(true, false, null, 6, 0, 0, false, true, false));
        assertTrue(line.contains("no member swung in 120 s"), line);
    }

    @Test
    void swingsThatLandNothingAreAFail() {
        String line = BossRunScenarios.verdict(seen(true, false, null, 6, 30, 0, false, true, false));
        assertTrue(line.contains("30 swing(s) landed no damage"), line);
    }

    @Test
    void aFightStillGoingIsNotAVerdict() {
        String line = BossRunScenarios.verdict(seen(true, false, null, 6, 300, 50_000, false, true, true));
        assertTrue(line.startsWith("EX2 END still fighting "), line);
    }

    @Test
    void aRunThatEndedAfterFightingFailsWithTheReason() {
        String line = BossRunScenarios.verdict(seen(true, false, "members-out", 6, 300, 50_000, false, true, false));
        assertTrue(line.contains("the run ended: members-out"), line);
    }

    @Test
    void endedReadsTheRunsOwnWordAndDashMeansRunning() {
        assertNull(BossRunScenarios.endedBecause("stage=1 cleared=false ended=- events=0"));
        assertNull(BossRunScenarios.endedBecause("no run"));
        assertEquals("time", BossRunScenarios.endedBecause("stage=2 cleared=false ended=time events=3 | a | b"));
        assertEquals("members-out", BossRunScenarios.endedBecause("ended=members-out"));
    }

    @Test
    void theSwitchLineDecidesBossModeNotTheCaller() {
        assertTrue(BossRunScenarios.bossOn("mechanics=true slums.mechanics.boss=true slums.bossfight.deathcount=unset"));
        assertFalse(BossRunScenarios.bossOn("mechanics=true slums.mechanics.boss=unset"));
        assertFalse(BossRunScenarios.bossOn("mechanics=false slums.mechanics.boss=true"));
        assertFalse(BossRunScenarios.bossOn(null));
    }

    private static BossRunScenarios.Facts facts(int reached, int staged, boolean boss, boolean reach, long swings,
                                                long damage, boolean altar, boolean abandoned, boolean stuck,
                                                boolean bossOn) {
        return new BossRunScenarios.Facts(reached, staged, boss, reach, swings, damage, altar, abandoned, stuck, bossOn);
    }

    @Test
    void theCheckLineNamesEveryFactTheAutoQaCheckReads() {
        String line = BossRunScenarios.check(facts(5, 5, true, true, 400, 90_000, true, false, false, true));
        assertEquals("CHECK members=5/5 boss=yes reach=yes swings=400 dmg=90000 altar=yes abandoned=no stuck=no"
                + " boss-on=yes", line);
    }

    @Test
    void theCheckLineIsHonestAboutAFightThatDidNotHappen() {
        String quiet = BossRunScenarios.check(facts(2, 5, false, false, 0, 0, false, true, true, false));
        assertEquals("CHECK members=2/5 boss=no reach=no swings=0 dmg=0 altar=no abandoned=yes stuck=yes boss-on=no",
                quiet);
    }

    @Test
    void onlyZakumsOwnMobsCountAsABossTarget() {
        assertTrue(BossRunScenarios.bossMob(8800000)); // the fake body the altar spawns
        assertTrue(BossRunScenarios.bossMob(8800003));
        assertTrue(BossRunScenarios.bossMob(8810010));
        assertFalse(BossRunScenarios.bossMob(0)); // nothing chosen yet
        assertFalse(BossRunScenarios.bossMob(9420513)); // an ordinary mob in the room
        assertFalse(BossRunScenarios.bossMob(8810040)); // an arm's corpse, not a thing to fight
    }

    @Test
    void theAltIsOptionalSoTheRunCanBeStagedWithoutOne() {
        BossRunScenarios.Ex2ZakumRoom ex2 = new BossRunScenarios.Ex2ZakumRoom();
        assertEquals("[your alt's name]", ex2.usage());
    }

    @Test
    void ex2IsRegisteredWithTheMenuAndTheSuite() {
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("ex-2")));
        assertTrue(QaScenarios.all().stream().anyMatch(s -> s.id().equals("ex-2") && s.tickSeconds() == 1),
                "ex-2 is sampled every second, so it must ask for a tick");
    }
}
