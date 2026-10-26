package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BotLifeScenarioTest {
    @TempDir Path directory;

    @Test
    void runningScenarioLogsActualPolicyAndRefusalWithoutMutatingTheWorld() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory,
                new QaTestSupport.ManualScheduler());
        suite.start(gm, "bl-1", new String[0]);
        String log = Files.readString(suite.runOf(1).logPath());
        assertTrue(log.contains("LIFE SHADOW case=socialite selected=HANG_OUT"));
        assertTrue(log.contains("LIFE SHADOW case=empty-potions selected=RESTOCK"));
        assertTrue(log.contains("LIFE SHADOW case=helper selected=HELP_FRIEND"));
        assertTrue(log.contains("LIFE REFUSAL accepted=false actionFact=false"));
        verifyNoInteractions(world);
        suite.stop(1, "done");
    }

    @Test
    void bl4AnswersTheGmAtOnceAndRunsTheRoundTripOffTheirThread() throws Exception {
        // TEST 2026-10-26: the round trip waited 15 s+ on the bots' DB share during a boot fill, inside !qa run, so
        // auto-QA saw no "QA bl-4 running" within 20 s. Now the answer comes first and the proof runs elsewhere.
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.lifeProfileProofAvailable()).thenReturn(true);
        when(world.lifeProfileProof(gm, "Fixture")).thenReturn(java.util.List.of(
                "LIFE PROFILE bot=Fixture slot=5001 SOCIALIZER (-0.62,-0.41): A 10% E 20% S 57% K 13% restored=true",
                "LIFE PROFILE PASS bot=1000005001 drift survived a social session and a reload"));
        java.util.List<Runnable> queued = new java.util.ArrayList<>();
        var old = BotLifeScenarios.ProfileSurvives.proofs;
        BotLifeScenarios.ProfileSurvives.proofs = queued::add;
        try {
            QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory,
                    new QaTestSupport.ManualScheduler());
            var answer = suite.start(gm, "bl-4", new String[]{"Fixture"});
            assertTrue(answer.getFirst().startsWith("QA bl-4 running"), answer.toString());
            verify(world, never()).lifeProfileProof(any(), any());
            assertEquals(1, queued.size(), "the proof waits on another thread");

            queued.getFirst().run();
            String log = Files.readString(suite.runOf(1).logPath());
            assertTrue(log.contains("LIFE PROFILE START bot=Fixture"), log);
            assertTrue(log.contains("LIFE PROFILE bot=Fixture slot=5001 SOCIALIZER (-0.62,-0.41)"), log);
            assertTrue(log.contains("LIFE PROFILE PASS bot=1000005001"), log);
            assertFalse(log.contains("LIFE LIFE"), log);
            suite.stop(1, "done");

            Character other = QaTestSupport.gm(2, "Other", 100000000);
            when(world.lifeProfileProof(other, null)).thenThrow(new QaRefusal("No online roster bot to test."));
            suite.start(other, "bl-4", new String[0]);
            queued.get(1).run();
            String failed = Files.readString(suite.runOf(2).logPath());
            assertTrue(failed.contains("LIFE PROFILE FAIL No online roster bot to test."), failed);
            suite.stop(2, "done");

            when(world.lifeProfileProofAvailable()).thenReturn(false);
            Character third = QaTestSupport.gm(3, "Third", 100000000);
            assertThrows(QaRefusal.class, () -> suite.start(third, "bl-4", new String[0]));
        } finally {
            BotLifeScenarios.ProfileSurvives.proofs = old;
        }
    }

    @Test
    void bl5LogsTheStagedGoalsPlanAndBotwhyLinesOnceTheBotGrinds() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 104040000);
        when(world.botLifeAutomaticEnabled()).thenReturn(true);
        when(world.mobLevel(gm)).thenReturn(12);
        when(world.stageGrinders(eq(gm), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(java.util.List.of(77));
        when(world.lifePlanStage(77)).thenReturn(java.util.List.of(), java.util.List.of(
                "PLAN STAGED bot=Fixture goal=MARRIAGE",
                "PLAN MARRIAGE steps=[MAKE_FRIENDS, FIND_A_CRUSH, GET_A_RING, WEDDING]",
                "WHY Plan get married (pressure 0.80): MARRIAGE: make friends in town -> spend time with a crush",
                "PLAN PASS bot=77"));
        var scheduler = new QaTestSupport.ManualScheduler();
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory, scheduler);
        suite.start(gm, "bl-5", new String[0]);
        for (int i = 0; i < 3; i++) scheduler.repeating.forEach(Runnable::run);
        String log = Files.readString(suite.runOf(1).logPath());
        assertTrue(log.contains("LIFE PLAN START bot=77"), log);
        assertTrue(log.contains("LIFE PLAN MARRIAGE steps=[MAKE_FRIENDS"), log);
        assertTrue(log.contains("LIFE WHY Plan get married"), log);
        assertEquals(1, log.split("LIFE PLAN PASS", -1).length - 1, "staged once");
        suite.stop(1, "done");
        verify(world).unstageGrinders(java.util.List.of(77));
    }

    @Test
    void cc1StagesACrowdQueuesTheChannelChangeAndSeesTheMove() throws Exception {
        QaWorld world = mock(QaWorld.class);
        Character gm = QaTestSupport.gm(1, "Admin", 104040000);
        when(world.botLifeAutomaticEnabled()).thenReturn(true);
        when(world.mobLevel(gm)).thenReturn(12);
        when(world.stageGrinders(eq(gm), any(), anyInt(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(java.util.List.of(78));
        when(world.botChannelAndMap(78)).thenReturn("1 104040000", "1 104040000", "3 104040000");
        when(world.lifeCrowdStage(78, 1.2)).thenReturn(java.util.List.of(
                "CC STAGE bot=Fixture map=104040000 ch=1 crowd=1.20 target=3 may=yes"));
        when(world.forceLifeChannel(78)).thenReturn("QUEUED");
        var scheduler = new QaTestSupport.ManualScheduler();
        QaSuite suite = QaTestSupport.suite(BotLifeScenarios.all(), world, directory, scheduler);
        var answer = suite.start(gm, "cc-1", new String[0]);
        assertTrue(answer.getFirst().startsWith("QA bc-1 running") || answer.getFirst().contains("bc-1"), answer.toString());
        suite.action(gm, "cc", new String[0]);
        scheduler.repeating.forEach(Runnable::run);
        scheduler.repeating.forEach(Runnable::run);
        String log = Files.readString(suite.runOf(1).logPath());
        assertTrue(log.contains("LIFE CC STAGE bot=Fixture map=104040000 ch=1 crowd=1.20 target=3 may=yes"), log);
        assertTrue(log.contains("LIFE CC QUEUED bot=78 result=QUEUED"), log);
        assertTrue(log.contains("LIFE CC MOVED bot=78 from ch1 to ch3 sameMap=true"), log);
        suite.stop(1, "done");
        verify(world).lifeCrowdStage(78, null);
        verify(world).unstageGrinders(java.util.List.of(78));
    }
}
