package client.command.commands.gm3;

import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import soloMapling.qa.QaSuite;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BotWhyCommandTest {
    @Test void runningBotsShowTheirActualDecisionAndCurrentRequestRatherThanAShadowGuess() {
        var bot = mock(soloMapling.ArtificialPlayer.BotTypes.TrainingBot.class);
        var runtime = mock(soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.class);
        var profile = soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile.forBot(1000005001);
        var observed = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeInspection.Snapshot(
                "Fixture",30,100000000,"TrainingBot","RUNNING",10,10,.1,false,false);
        var context = new soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy.Context(
                new soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Needs(java.util.Map.of()),
                java.util.Map.of(),0,0,0,false,false,false,null,java.util.List.of(),java.util.List.of());
        var result = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Result(java.util.List.of(),
                java.util.Optional.of(soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity.RESTOCK),
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Outcome.CHOOSE);
        var state = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue.State(0,12,
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity.RESTOCK,false,12,0,
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue.End.NONE);
        var report = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeObservation.Report(observed,profile,context,result,null);
        var shadow = new soloMapling.ArtificialPlayer.BotLifeSystem.LifeObservation.Report(observed,profile,context,
                new soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Result(java.util.List.of(),
                        java.util.Optional.of(soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity.HANG_OUT),
                        soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Outcome.CHOOSE),null);
        var chr = mock(Character.class);
        when(chr.getId()).thenReturn(1000005001);
        when(bot.getChr()).thenReturn(chr);
        when(bot.lifeRuntime()).thenReturn(runtime);
        when(runtime.lastDecision()).thenReturn(new soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.DecisionSnapshot(1000,report));
        when(bot.lifeActivityState()).thenReturn(state);
        when(bot.lifePhase()).thenReturn(new soloMapling.ArtificialPlayer.BotTypes.TrainingBot.LifePhase("GO_TOWN",1500));
        try (var flag=mockStatic(soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.class);
             var observation=mockStatic(soloMapling.ArtificialPlayer.BotLifeSystem.LifeObservation.class,CALLS_REAL_METHODS)) {
            flag.when(soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime::enabled).thenReturn(true);
            observation.when(()->soloMapling.ArtificialPlayer.BotLifeSystem.LifeObservation.capture(bot,5000)).thenReturn(shadow);
            var lines=BotWhyCommand.inspectionLines(bot,5000);
            assertTrue(lines.stream().anyMatch(s->s.contains("selected Optional[RESTOCK]")),lines.toString());
            assertTrue(lines.stream().anyMatch(s->s.contains("running=12")));
            assertTrue(lines.stream().anyMatch(s->s.contains("GO_TOWN")&&s.contains("4 s")));
            assertFalse(lines.stream().anyMatch(s->s.contains("No activity is executed")));
            // 1.6.0 S1: the Bartle blend and its leading type, from the slot's seed.
            var leading = soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.forCharacter(1000005001).leading();
            assertTrue(lines.stream().anyMatch(s->s.startsWith("Bartle "+leading+" (")&&s.contains(", plastic ")&&s.contains(" from seed (")&&s.contains("rival pull")),lines.toString());
            assertTrue(lines.stream().anyMatch(s->s.startsWith("Life channels grinder ")),lines.toString());
            assertTrue(lines.stream().anyMatch(s->s.startsWith("Persona ")&&s.contains("voice ")),lines.toString());
            observation.verify(()->soloMapling.ArtificialPlayer.BotLifeSystem.LifeObservation.capture(bot,5000),never());
            verify(bot,never()).submitLifeActivity(any());
        }
    }

    @Test
    void previewRequiresBothQaAndExplicitFlag() {
        String old = System.getProperty("slums.botlife.preview");
        try (var qa = mockStatic(QaSuite.class)) {
            System.clearProperty("slums.botlife.preview");
            qa.when(QaSuite::isEnabled).thenReturn(true);
            assertFalse(BotWhyCommand.isEnabled());
            System.setProperty("slums.botlife.preview", "true");
            assertTrue(BotWhyCommand.isEnabled());
            qa.when(QaSuite::isEnabled).thenReturn(false);
            assertFalse(BotWhyCommand.isEnabled());
        } finally {
            if (old == null) System.clearProperty("slums.botlife.preview");
            else System.setProperty("slums.botlife.preview", old);
        }
    }

    @Test
    void disabledCommandDoesNotLookUpOrTouchBotsEvenIfInvokedDirectly() {
        Client client = mock(Client.class);
        Character gm = mock(Character.class);
        when(client.getPlayer()).thenReturn(gm);
        try (var qa = mockStatic(QaSuite.class)) {
            qa.when(QaSuite::isEnabled).thenReturn(false);
            new BotWhyCommand().execute(client, new String[]{"FixtureBot"});
            verify(client).getPlayer();
            verifyNoMoreInteractions(client);
            verify(gm).yellowMessage("Bot life preview needs QA and -Dslums.botlife.preview=true.");
        }
    }
}
