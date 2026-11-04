package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.6.4 t-ex-2 against the lines ex-2 writes (BossRunScenarios), with no alt: the check reads the scenario's own
 * {@code EX2 CHECK} summary, so a run that fought passes and a run that stood there or lost the room fails.
 */
class BossRunCatalogTest {

    static final String START = "EX2 START members=5 bots=4 alt=none sample=90s/150s mechanics=true"
            + " slums.mechanics.boss=true slums.bossfight.damagefloor=true slums.bossfight.deathcount=true";
    static final String ALTAR = "EX2 ALTAR t=4 hit reactor 2111001 as AutoQaOne, 9 mob(s) on the map now";
    static final String CHECK = "EX2 CHECK members=5/5 boss=yes reach=yes swings=812 dmg=401233 altar=yes"
            + " abandoned=no stuck=no boss-on=yes";
    static final String PASS = "EX2 PASS cleared members=5/5 swings=812 damage=401233 s=120";

    @Test
    void aRoomWhereTheBotsFoughtPasses() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-2", START, ALTAR, CHECK, PASS);
        for (Check.Verdict x : v) {
            assertEquals(Check.Status.PASS, x.status(), x.toString());
        }
    }

    @Test
    void aFightStillGoingWhenTheWindowClosedAlsoPasses() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-2", START, ALTAR, CHECK,
                "EX2 END still fighting when the window closed members=5/5 swings=812 damage=401233 s=150");
        assertEquals(List.of(Check.Status.PASS, Check.Status.PASS, Check.Status.PASS, Check.Status.PASS,
                Check.Status.PASS, Check.Status.PASS), v.stream().map(Check.Verdict::status).toList());
    }

    @Test
    void botsThatNeverReachedTheRoomFailEvenIfTheySwungSomewhereElse() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-2", START,
                "EX2 CHECK members=0/5 boss=no reach=no swings=812 dmg=401233 altar=no abandoned=no stuck=no"
                        + " boss-on=yes",
                "EX2 FAIL nobody reached 280030000 members=0/5 swings=812 damage=401233 s=120");
        assertEquals(List.of(Check.Status.PASS, Check.Status.FAIL, Check.Status.FAIL, Check.Status.FAIL,
                Check.Status.PASS, Check.Status.PASS), v.stream().map(Check.Verdict::status).toList());
    }

    @Test
    void aSwungAtButUnreachableTargetIsNotReachYes() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-2", START, ALTAR,
                "EX2 CHECK members=5/5 boss=yes reach=no swings=0 dmg=0 altar=yes abandoned=no stuck=no boss-on=yes",
                "EX2 FAIL no member swung in 120 s members=5/5 swings=0 damage=0 s=120");
        assertEquals(Check.Status.FAIL, v.get(2).status());
    }

    @Test
    void anAbandonOrAWedgedBotFailsTheCheck() throws Exception {
        var abandoned = AutoQaCatalogPatternTest.eval("t-ex-2", START, ALTAR, CHECK,
                "EX2 FAIL the run ended: the kill target 8800002 is not on the map members=5/5 swings=812"
                        + " damage=401233 s=120",
                "EX2 RUN t=120 ended=the kill target 8800002 is not on the map events=Abandon(\"the kill target\")");
        assertEquals(Check.Status.FAIL, abandoned.get(4).status(), "the Abandon line must be caught");
        var stuck = AutoQaCatalogPatternTest.eval("t-ex-2", START, ALTAR, CHECK,
                "EX2 PARTS t=10 empty", "RUN t=10 Bot42 could not be put in map 280030000: no portal there");
        assertEquals(Check.Status.FAIL, stuck.get(5).status(), "the stuck line must be caught");
    }

    /**
     * With the boss lane off the run is a baseline, never proof: the expectations that demand a fight fail. The
     * runner never gets here (the switch step SKIPs first), and this pins that a baseline cannot read as a pass if
     * somebody reorders the steps.
     */
    @Test
    void aBaselineRunCannotPassTheFightExpectations() throws Exception {
        var v = AutoQaCatalogPatternTest.eval("t-ex-2",
                "EX2 START members=5 bots=4 alt=none sample=90s/150s mechanics=false slums.mechanics.boss=unset",
                ALTAR,
                "EX2 CHECK members=5/5 boss=no reach=no swings=0 dmg=0 altar=yes abandoned=no stuck=no boss-on=no",
                "EX2 BASELINE boss=off members=5/5 swings=0 damage=0 s=120");
        assertEquals(List.of(Check.Status.PASS, Check.Status.PASS, Check.Status.FAIL, Check.Status.FAIL,
                Check.Status.PASS, Check.Status.PASS), v.stream().map(Check.Verdict::status).toList());
    }

    @Test
    void theCheckIsRegisteredOnThePartyQuestLaneWithAFourMinuteBudget() {
        Check c = AutoQaCatalog.find("t-ex-2");
        assertEquals(Check.Kind.AUTO, c.kind());
        assertEquals("pq:zakum", String.join(",", c.holds()));
        assertEquals(4, c.minutes());
        assertEquals(List.of("!qa run ex-2", "wait up to 160 s for /EX2 CHECK members=/",
                "the boss lane is on (-Dslums.mechanics.boss=true)", "!qa stop"),
                c.steps().stream().map(Check.Step::describe).toList());
    }
}
