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

/** 1.6.0 S8: the runtime plans between the goal refresh and the scorer, shows the plan, and re-plans only on change. */
class LifeRuntimePlanTest {
    @Test
    void aThirdJobBotPlansItsFourthJobAndTheNextStepGetsTheBonus() {
        Map<String, String> before = new HashMap<>();
        for (String key : List.of("enabled", "execute", "metrics")) before.put(key, System.getProperty("slums.botlife." + key));
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class);
        when(bot.getChr()).thenReturn(chr);
        when(bot.getRunning()).thenReturn(true);
        when(chr.getId()).thenReturn(1000008001);
        when(chr.getLevel()).thenReturn(112);
        when(chr.getJob()).thenReturn(Job.CRUSADER);
        var queue = new LifeActivityQueue();
        when(bot.lifeActivityState()).thenReturn(queue.state());
        when(bot.freeForAutomaticLifeActivity(anyLong())).thenReturn(true);
        when(bot.canStartLifeActivity(any(), anyLong())).thenAnswer(c -> c.getArgument(0) == Activity.GRIND
                || c.getArgument(0) == Activity.HANG_OUT || c.getArgument(0) == Activity.CHANGE_CHANNEL);
        long now = System.currentTimeMillis();
        var snapshot = new LifeInspection.Snapshot("Fixture", 112, 220010500, "TrainingBot", "RUNNING", 50, 10, .1, false, false);
        var context = new LifePolicy.Context(new Needs(Map.of(Need.SUPPLIES, 0.0, Need.BAG_SPACE, .1)),
                Map.of(Activity.GRIND, "here"), 0, 0, 0, false, false, false,
                new Current(Activity.GRIND, now - 60_000, 2_400_000, .8), List.of(), List.of());
        var report = new LifeObservation.Report(snapshot, LifeProfile.forBot(1000008001), context,
                LifePolicy.preview(context, LifeProfile.forBot(1000008001), now), queue.state());
        when(bot.prepareLifeActivity(any())).thenAnswer(c -> queue.prepare(c.getArgument(0), now, 30_000));
        when(bot.submitLifeActivity(any())).thenAnswer(c -> queue.offer(c.getArgument(0), now));
        try (var qa = mockStatic(QaSuite.class); var roster = mockStatic(BotRoster.class);
             var observation = mockStatic(LifeObservation.class); var affinity = mockStatic(BotAffinity.class);
             var attacks = mockStatic(BotAttackDriver.class); var supplies = mockStatic(BotSupplies.class)) {
            System.setProperty("slums.botlife.enabled", "true");
            System.setProperty("slums.botlife.execute", "true");
            System.setProperty("slums.botlife.metrics", "false");
            affinity.when(BotAffinity::knownPlayers).thenReturn(List.of());
            observation.when(() -> LifeObservation.weaponLevel(chr)).thenReturn(OptionalInt.empty());
            observation.when(() -> LifeObservation.capture(bot, now)).thenReturn(report);
            observation.when(() -> LifeObservation.capture(bot, now + 31_000)).thenReturn(report);
            var runtime = new LifeRuntime(bot);
            runtime.phase("GRIND", now);
            long grindChosen = LifeRuntime.chosen().getOrDefault("GRIND", 0L);
            long grindSelected = LifeRuntime.selected().getOrDefault("GRIND", 0L);
            runtime.tick(now);
            assertEquals(grindChosen, LifeRuntime.chosen().getOrDefault("GRIND", 0L), "keeping a grind is not a choice");
            assertEquals(grindSelected + 1, LifeRuntime.selected().getOrDefault("GRIND", 0L));
            var goals = runtime.memory().goals();
            assertEquals(LifeGoals.Kind.FOURTH_JOB, goals.getFirst().kind());
            assertEquals(List.of(LifePlanner.Step.GRIND_TO_LEVEL, LifePlanner.Step.TRAVEL_TO_INSTRUCTOR,
                    LifePlanner.Step.TALK_TO_INSTRUCTOR), runtime.memory().plans().getFirst().steps());
            var lines = runtime.describePlan();
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("Plan 4th job at level 120")
                    && l.contains("grind to level 120 -> travel to the job instructor's town -> talk to the job instructor")),
                    lines.toString());
            assertTrue(lines.stream().anyMatch(l -> l.startsWith("Next step: ")), lines.toString());
            // The GRIND option carries the plan step's bonus (if the grind step leads) on top of its habit pull.
            var decision = runtime.lastDecision().report().result();
            assertEquals(Activity.GRIND, decision.selected().orElseThrow());
            // No quieter channel was found (no crowd): CHANGE_CHANNEL never reaches the scorer.
            assertFalse(runtime.lastDecision().report().context().eligible().containsKey(Activity.CHANGE_CHANNEL));
            long replans = LifePlanner.replans();
            runtime.changed();
            runtime.tick(now + 31_000);
            assertEquals(replans, LifePlanner.replans(), "same goals and facts: the stored plan is reused");
        } finally {
            before.forEach((key, value) -> {
                if (value == null) System.clearProperty("slums.botlife." + key);
                else System.setProperty("slums.botlife." + key, value);
            });
        }
    }
}
