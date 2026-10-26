package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TownWandererBot;
import soloMapling.qa.QaSuite;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** 1.6.0 S8 (brain reach): town and social bots decide with the same brain, from their own needs and activities. */
class LifeTownLifeTest {
    static final List<String> KEYS = List.of("enabled", "execute", "metrics", "reach");

    @Test
    void aRestlessWandererChoosesItsOwnMapHopThroughTheBrain() {
        var moves = new CountingMoves(true);
        var outcome = run(mock(TownWandererBot.class), LifeTownLife.Kind.TOWN, moves, Map.of(Need.BOREDOM, 1.0));
        assertEquals(1, moves.hops.get(), "the brain chose MOVE_MAP and the wanderer's own hop started: "
                + outcome.decision().report().result().ranking());
        assertEquals(Activity.MOVE_MAP, outcome.decision().report().result().selected().orElseThrow());
        assertEquals(0, outcome.life().lifeActivityState().active(), "a switch, not a trip: the request ended");
        assertEquals(LifeActivityQueue.End.COMPLETED, outcome.life().lifeActivityState().end());
    }

    @Test
    void aSocialBotOnlyGetsWhatItCanDoAndStartsFromItsOwnNeeds() {
        var moves = new CountingMoves(false);
        var outcome = run(mock(SocialBot.class), LifeTownLife.Kind.SOCIAL, moves, Map.of(Need.BOREDOM, .2));
        var context = outcome.decision().report().context();
        for (Activity a : List.of(Activity.GRIND, Activity.PARTY_QUEST, Activity.HELP_FRIEND, Activity.RESTOCK,
                Activity.MOVE_MAP, Activity.FISH)) {
            assertFalse(context.eligible().containsKey(a), a + " is a training bot's");
        }
        assertTrue(context.eligible().containsKey(Activity.HANG_OUT));
        assertFalse(context.needs().values().containsKey(Need.SUPPLIES), "no grinding, no potion need");
        assertTrue(context.needs().values().get(Need.SOCIAL) <= .2, "a social bot stands among people");
        assertEquals(Activity.HANG_OUT, outcome.decision().report().result().selected().orElseThrow());
        assertEquals(0, moves.hops.get());
    }

    @Test
    void trainingOnlyReachKeepsTownBotsOffTheBrain() {
        Map<String, String> before = save();
        try {
            System.setProperty("slums.botlife.enabled", "true");
            System.setProperty("slums.botlife.execute", "true");
            System.setProperty("slums.botlife.reach", "training");
            assertFalse(LifeRuntime.reachesAllBots());
            assertNull(LifeTownLife.forBot(mock(SocialBot.class), LifeTownLife.Kind.SOCIAL, new LifeTownLife.Moves() {}));
            System.clearProperty("slums.botlife.reach");
            assertTrue(LifeRuntime.reachesAllBots());
            System.clearProperty("slums.botlife.enabled");
            assertFalse(LifeRuntime.reachesAllBots(), "live has the brain off: nothing changes there");
        } finally {
            restore(before);
        }
    }

    record Outcome(LifeTownLife life, LifeRuntime.DecisionSnapshot decision) {}

    static final class CountingMoves implements LifeTownLife.Moves {
        final AtomicInteger hops = new AtomicInteger();
        final boolean canHop;
        CountingMoves(boolean canHop) { this.canHop = canHop; }
        public boolean canMoveMap() { return canHop; }
        public boolean moveMap() { hops.incrementAndGet(); return true; }
    }

    private Outcome run(soloMapling.ArtificialPlayer.BotSM bot, LifeTownLife.Kind kind, LifeTownLife.Moves moves,
                        Map<Need, Double> observedNeeds) {
        Map<String, String> before = save();
        var chr = mock(Character.class);
        var map = mock(server.maps.MapleMap.class);
        when(bot.getChr()).thenReturn(chr);
        when(bot.getRunning()).thenReturn(true);
        when(chr.getId()).thenReturn(1000006001);
        when(chr.getLevel()).thenReturn(40);
        when(chr.getJob()).thenReturn(Job.WARRIOR);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(100000000);
        when(map.getId()).thenReturn(100000000);
        when(map.getChannelId()).thenReturn(1);
        when(map.isTown()).thenReturn(true);
        long now = System.currentTimeMillis();
        var snapshot = new LifeInspection.Snapshot("Fixture", 40, 100000000, kind.name(), "RUNNING", 10, 10, .1, false, false);
        var needs = new EnumMap<Need, Double>(Need.class);
        needs.putAll(observedNeeds);
        needs.put(Need.SUPPLIES, .9); // a wanderer's empty potion bag must not matter
        var context = new LifePolicy.Context(new Needs(needs), Map.of(), 0, 2, 0, false, false, false, null,
                List.of(), List.of());
        var report = new LifeObservation.Report(snapshot, LifeProfile.forBot(1000006001), context,
                LifePolicy.preview(context, LifeProfile.forBot(1000006001), now), null);
        try (var qa = mockStatic(QaSuite.class); var roster = mockStatic(BotRoster.class);
             var observation = mockStatic(LifeObservation.class); var affinity = mockStatic(BotAffinity.class);
             var attacks = mockStatic(BotAttackDriver.class); var supplies = mockStatic(BotSupplies.class);
             var director = mockStatic(BotPopulationDirector.class); var commitments = mockStatic(BotCommitments.class);
             var conversation = mockStatic(BotConversation.class)) {
            System.setProperty("slums.botlife.enabled", "true");
            System.setProperty("slums.botlife.execute", "true");
            System.setProperty("slums.botlife.metrics", "false");
            affinity.when(BotAffinity::knownPlayers).thenReturn(List.of());
            director.when(() -> BotPopulationDirector.mayLogOut(any())).thenReturn(true);
            observation.when(() -> LifeObservation.weaponLevel(chr)).thenReturn(OptionalInt.empty());
            observation.when(() -> LifeObservation.capture(eq(bot), anyLong())).thenReturn(report);
            var life = LifeTownLife.forTest(bot, kind, moves);
            life.tick(now);                         // settles in: a fresh stay
            life.tick(now + 40 * 60_000L);          // the stay is long over: decide again
            var decision = life.lifeRuntime().lastDecision();
            assertNotNull(decision, "a " + kind + " bot decided with the brain");
            return new Outcome(life, decision);
        } finally {
            restore(before);
        }
    }

    private static Map<String, String> save() {
        Map<String, String> out = new HashMap<>();
        for (String key : KEYS) out.put(key, System.getProperty("slums.botlife." + key));
        return out;
    }

    private static void restore(Map<String, String> before) {
        before.forEach((key, value) -> {
            if (value == null) System.clearProperty("slums.botlife." + key);
            else System.setProperty("slums.botlife." + key, value);
        });
    }
}
