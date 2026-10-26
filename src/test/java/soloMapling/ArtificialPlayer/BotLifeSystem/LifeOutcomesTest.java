package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** 1.6.0 S8: the activity-outcome callback S6 and S9 plug into, and the bias hook. */
class LifeOutcomesTest {

    private static long started(LifeActivityQueue queue, Activity activity) {
        var request = queue.prepare(new Result(List.of(), Optional.of(activity), Outcome.CHOOSE), 0, 30_000);
        queue.offer(request, 0);
        queue.drain(0, true, () -> true, new LifeAcceptance.Executor() {
            public boolean supports(Activity a) { return true; }
            public Optional<LifeAcceptance.Accepted> start(Activity a) {
                return Optional.of(new LifeAcceptance.Accepted(a, "fixture"));
            }
        });
        return request.number();
    }

    @Test
    void theQueueTellsOnceHowAnActivityEnded() {
        var queue = new LifeActivityQueue();
        List<String> heard = new ArrayList<>();
        queue.onEnd((activity, end) -> heard.add(activity + ":" + end));
        long number = started(queue, Activity.HANG_OUT);
        assertFalse(queue.finish(number + 5, LifeActivityQueue.End.COMPLETED), "an old callback cannot end it");
        assertTrue(queue.finish(number, LifeActivityQueue.End.COMPLETED));
        assertFalse(queue.finish(number, LifeActivityQueue.End.CANCELLED), "only once");
        started(queue, Activity.PARTY_QUEST);
        queue.close();
        assertEquals(List.of("HANG_OUT:COMPLETED", "PARTY_QUEST:CANCELLED"), heard);
    }

    @Test
    void theBrainReportsSuccessAndFailureToListeners() {
        var bot = mock(TrainingBot.class);
        var chr = mock(Character.class);
        when(bot.getChr()).thenReturn(chr);
        when(chr.getId()).thenReturn(1000007001);
        List<LifeOutcomes.Outcome> heard = new ArrayList<>();
        LifeOutcomes.Listener listener = heard::add;
        LifeOutcomes.listen(listener);
        LifeOutcomes.Listener broken = o -> { throw new IllegalStateException("a bad hook"); };
        LifeOutcomes.listen(broken);
        try (var roster = mockStatic(BotRoster.class)) {
            var runtime = new LifeRuntime(bot);
            runtime.ended(Activity.PARTY_QUEST, LifeActivityQueue.End.COMPLETED);
            runtime.ended(Activity.HELP_FRIEND, LifeActivityQueue.End.CANCELLED);
            runtime.ended(Activity.GRIND, LifeActivityQueue.End.NONE);
        } finally {
            LifeOutcomes.remove(listener);
            LifeOutcomes.remove(broken);
        }
        assertEquals(2, heard.size());
        assertEquals(new LifeOutcomes.Outcome(1000007001, Activity.PARTY_QUEST, true, heard.get(0).at(), "completed"),
                heard.get(0));
        assertFalse(heard.get(1).success());
    }

    @Test
    void aPluginBiasAddsItsPullButIsClamped() {
        var profile = LifeProfile.forBot(1000007002);
        var context = new LifePolicy.Context(new Needs(Map.of()), Map.of(Activity.GRIND, "x", Activity.HANG_OUT, "x"),
                0, 0, 0, false, false, false, null, List.of(), List.of());
        var situation = new LifePolicy.Situation(new SocialProfile(0, 0), false, false, 0, false, null, 0, 1);
        double plain = personality(context, profile, situation, Activity.HANG_OUT);
        LifePolicy.Bias huge = (a, c, s) -> a == Activity.HANG_OUT ? 9 : 0;
        LifePolicy.addBias(huge);
        try {
            assertEquals(plain + LifePolicy.MAX_PLUGIN_BIAS, personality(context, profile, situation, Activity.HANG_OUT), 1e-9);
        } finally {
            LifePolicy.removeBias(huge);
        }
    }

    private static double personality(LifePolicy.Context context, LifeProfile profile, LifePolicy.Situation situation,
                                      Activity activity) {
        return LifePolicy.options(context, profile, situation).stream().filter(o -> o.activity() == activity)
                .findFirst().orElseThrow().personalityBonus();
    }
}
