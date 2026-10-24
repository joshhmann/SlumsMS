package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaSuite;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeRuntimeTest {
    @Test void realSupplyNeedAutomaticallyProducesAQueuedRestockAndDoesNotRescoreEveryTick() {
        String enabled = System.getProperty("slums.botlife.enabled");
        String execute = System.getProperty("slums.botlife.execute");
        String metrics = System.getProperty("slums.botlife.metrics");
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class);
        when(bot.getChr()).thenReturn(chr); when(bot.getRunning()).thenReturn(true);
        when(chr.getId()).thenReturn(1000005001); when(chr.getLevel()).thenReturn(30); when(chr.getJob()).thenReturn(Job.WARRIOR);
        var queue = new LifeActivityQueue();
        when(bot.lifeActivityState()).thenReturn(queue.state());
        when(bot.freeForAutomaticLifeActivity(anyLong())).thenReturn(true);
        when(bot.canStartLifeActivity(any(), anyLong())).thenAnswer(c -> c.getArgument(0) == Activity.RESTOCK || c.getArgument(0) == Activity.GRIND);
        long now = System.currentTimeMillis();
        var snapshot = new LifeInspection.Snapshot("Fixture",30,104040000,"TrainingBot","RUNNING",0,10,.1,false,false);
        var context = new LifePolicy.Context(new Needs(Map.of(Need.SUPPLIES,1.0,Need.PROGRESS,.7)),
                Map.of(Activity.GRIND,"here",Activity.RESTOCK,"trip"),0,0,0,false,false,false,
                new Current(Activity.GRIND,now-60_000,2_400_000,.8),List.of(),List.of());
        var report = new LifeObservation.Report(snapshot,LifeProfile.forBot(1000005001),context,
                LifePolicy.preview(context,LifeProfile.forBot(1000005001),now),queue.state());
        when(bot.prepareLifeActivity(any())).thenAnswer(c -> queue.prepare(c.getArgument(0),now,30_000));
        when(bot.submitLifeActivity(any())).thenAnswer(c -> queue.offer(c.getArgument(0),now));
        try (var qa=mockStatic(QaSuite.class); var roster=mockStatic(BotRoster.class);
             var observation=mockStatic(LifeObservation.class); var affinity=mockStatic(BotAffinity.class);
             var attacks=mockStatic(BotAttackDriver.class); var supplies=mockStatic(BotSupplies.class)) {
            System.setProperty("slums.botlife.enabled","true"); System.setProperty("slums.botlife.metrics","false");
            System.setProperty("slums.botlife.execute","true");
            qa.when(QaSuite::isEnabled).thenReturn(true);
            affinity.when(BotAffinity::knownPlayers).thenReturn(List.of());
            observation.when(() -> LifeObservation.weaponLevel(chr)).thenReturn(OptionalInt.empty());
            observation.when(() -> LifeObservation.capture(bot,now)).thenReturn(report);
            var runtime = new LifeRuntime(bot);
            runtime.phase("GRIND",now);
            runtime.tick(now);
            var decision = org.mockito.ArgumentCaptor.forClass(Result.class);
            verify(bot).prepareLifeActivity(decision.capture());
            assertEquals(Activity.RESTOCK,decision.getValue().selected().orElseThrow());
            assertTrue(queue.hasWaiting());
            assertNotNull(runtime.lastDecision(), "keep the actual result for the read-only inspector");
            assertEquals(Activity.RESTOCK, runtime.lastDecision().report().result().selected().orElseThrow());
            runtime.tick(now+1000);
            verify(bot,times(1)).prepareLifeActivity(any());
            assertFalse(runtime.memory().goals().isEmpty());
        } finally {
            restore("slums.botlife.enabled",enabled); restore("slums.botlife.metrics",metrics);
            restore("slums.botlife.execute",execute);
        }
    }
    @Test void disabledRuntimeDoesNotReadTheWorldOrSubmitWork() {
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class); when(bot.getChr()).thenReturn(chr);
        String old=System.getProperty("slums.botlife.enabled");
        try (var roster=mockStatic(BotRoster.class); var qa=mockStatic(QaSuite.class)) {
            System.clearProperty("slums.botlife.enabled");
            var runtime=new LifeRuntime(bot);
            clearInvocations(bot);
            runtime.tick(System.currentTimeMillis());
            verifyNoInteractions(bot);
        } finally { restore("slums.botlife.enabled",old); }
    }
    private static void restore(String key,String old) { if(old==null)System.clearProperty(key);else System.setProperty(key,old); }
}
