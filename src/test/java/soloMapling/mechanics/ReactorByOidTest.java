package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;

import client.Client;
import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventInstanceManager;
import server.maps.MapObject;
import server.maps.MapleMap;
import server.maps.Reactor;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

/**
 * Guild Quest's Gatekeeper puzzle (stage 1, map 990000300) stands twenty statues in rows. They are reactors of three
 * WZ ids ({@code 9208000}, {@code 9208001}, {@code 9208002}) with the names {@code "1"} to {@code "20"}, and the
 * script that scores the puzzle names each one by its <em>map object id</em>: {@code scripts/reactor/9208000.js:34-37}
 * appends {@code objectId % 1000}, zero-padded to three digits, to {@code stage1combo} while the display runs and to
 * {@code stage1guess} while a player answers, and {@code scripts/npc/9040009.js:79} compares the two strings. Until now
 * a step could address a reactor by WZ id ({@link SolverStep.HitReactor}) or by WZ name
 * ({@link SolverStep.HitReactorNamed}); neither says "the statue whose object id is 2041", and an id that six statues
 * share hits whichever one the map lists first - which is a wrong answer to the puzzle, not a slow one.
 */
class ReactorByOidTest {

    private static final long NOW = 5_000_000L;
    private static final int STATUE = 9208000;

    private static Reactor statue(int objectId, int wzId, String name, int x, int y, int state) {
        Reactor r = mock(Reactor.class);
        when(r.getObjectId()).thenReturn(objectId);
        when(r.getId()).thenReturn(wzId);
        when(r.getName()).thenReturn(name);
        when(r.getState()).thenReturn((byte) state);
        when(r.getPosition()).thenReturn(new Point(x, y));
        return r;
    }

    /** Three statues of one WZ id, as the map stands them; object ids deliberately not in WZ order. */
    private static final class Ruins {
        final Reactor a = statue(2043, STATUE, "1", -80, 163, 0);
        final Reactor b = statue(2041, STATUE, "2", 132, 163, 0);
        final Reactor c = statue(2047, STATUE, "3", 344, 165, 0);
        final MapleMap map = mock(MapleMap.class);

        Ruins() {
            when(map.getReactorByOid(2043)).thenReturn(a);
            when(map.getReactorByOid(2041)).thenReturn(b);
            when(map.getReactorByOid(2047)).thenReturn(c);
            // the id lookup answers with the first one it finds, like the real map's hash order
            when(map.getReactorById(STATUE)).thenReturn(a);
            when(map.getReactors()).thenReturn(List.<MapObject>of(a, b, c));
        }
    }

    private static Character bot(MapleMap map, int x, int y) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(7);
        when(bot.getName()).thenReturn("Gloria");
        when(bot.getMap()).thenReturn(map);
        when(bot.getMapId()).thenReturn(990000300);
        when(bot.getPosition()).thenReturn(new Point(x, y));
        when(bot.getClient()).thenReturn(mock(Client.class));
        return bot;
    }

    private static void run(StepExecutor executor, Character bot, SolverStep step, long now, List<String> log) {
        executor.execute(null, null, null, bot, step, now, log::add);
    }

    // ---- the executor --------------------------------------------------------------------------

    @Test
    void theStepHitsTheStatueWhoseObjectIdItNamesAndNoOther() {
        Ruins ruins = new Ruins();
        Character bot = bot(ruins.map, 132, 163); // standing at the second statue
        List<String> log = new ArrayList<>();

        run(new StepExecutor(), bot, new SolverStep.HitReactorByOid(2041), NOW, log);

        verify(ruins.b, times(1)).hitReactor(bot.getClient());
        verify(ruins.a, never()).hitReactor(bot.getClient());
        verify(ruins.c, never()).hitReactor(bot.getClient());
        assertTrue(log.stream().anyMatch(l -> l.contains("hit reactor") && l.contains("2041")), log.toString());
    }

    @Test
    void theWzIdCannotTellStatuesApartWhichIsWhyTheStepExists() {
        Ruins ruins = new Ruins();

        // asking for "the reactor 9208000" is asking for whichever statue the map lists first, whatever the puzzle wants
        assertSame(ruins.a, StepExecutor.reactorFor(ruins.map, String.valueOf(STATUE)));
        // while the object id names exactly one
        assertSame(ruins.b, StepExecutor.reactorByOid(ruins.map, 2041));
        assertSame(ruins.c, StepExecutor.reactorByOid(ruins.map, 2047));
    }

    @Test
    void aBotAwayFromTheStatueWalksToItAndHitsNothingYet() {
        Ruins ruins = new Ruins();
        Character bot = bot(ruins.map, -300, 163);
        List<String> log = new ArrayList<>();

        try (MockedStatic<GCMovement> move = mockStatic(GCMovement.class)) {
            run(new StepExecutor(), bot, new SolverStep.HitReactorByOid(2047), NOW, log);

            move.verify(() -> GCMovement.move(bot, 344, 165), times(1));
        }
        verify(ruins.c, never()).hitReactor(bot.getClient());
    }

    @Test
    void aHitIsPacedLikeEveryOtherReactorHit() {
        Ruins ruins = new Ruins();
        Character bot = bot(ruins.map, 344, 165);
        StepExecutor executor = new StepExecutor();
        List<String> log = new ArrayList<>();

        run(executor, bot, new SolverStep.HitReactorByOid(2047), NOW, log);
        run(executor, bot, new SolverStep.HitReactorByOid(2047), NOW + 1_000, log);
        verify(ruins.c, times(1)).hitReactor(bot.getClient());

        run(executor, bot, new SolverStep.HitReactorByOid(2047), NOW + 3_100, log);
        verify(ruins.c, times(2)).hitReactor(bot.getClient());
    }

    @Test
    void anObjectIdTheMapDoesNotHoldHitsNothingAndSaysSoOnce() {
        Ruins ruins = new Ruins();
        Character bot = bot(ruins.map, 132, 163);
        StepExecutor executor = new StepExecutor();
        List<String> log = new ArrayList<>();

        run(executor, bot, new SolverStep.HitReactorByOid(31337), NOW, log);
        run(executor, bot, new SolverStep.HitReactorByOid(31337), NOW + 2_000, log);

        verify(ruins.a, never()).hitReactor(bot.getClient());
        verify(ruins.b, never()).hitReactor(bot.getClient());
        verify(ruins.c, never()).hitReactor(bot.getClient());
        assertEquals(1, log.size(), "one line, not one per tick: " + log);
        assertTrue(log.get(0).contains("31337"), log.get(0));

        MapleMap nowhere = null;
        assertEquals(null, StepExecutor.reactorByOid(nowhere, 2041));
    }

    // ---- the view ------------------------------------------------------------------------------

    private static LiveStageView viewOver(MapleMap stageMap) {
        MechanicsModel.Content content = MechanicsRuns.contentFor("KerningPQ");
        RunState run = new RunState(content, MechanicsModel.Tier.NORMAL,
                List.of(new RunMember(1, true, true, MechanicsPersona.PERFECT)), 60_000L, 1);
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getMapInstance(anyInt())).thenReturn(stageMap);
        return new LiveStageView(run, eim, NOW);
    }

    @Test
    void theViewListsEveryReactorByObjectIdAndReadsOneStateByIt() {
        Ruins ruins = new Ruins();
        when(ruins.b.getState()).thenReturn((byte) 3);

        LiveStageView view = viewOver(ruins.map);

        List<StageView.ReactorPlacement> all = view.reactors();
        assertEquals(3, all.size());
        assertEquals(new StageView.ReactorPlacement(2043, STATUE, "1", 0, -80, 163), all.get(0));
        assertEquals(new StageView.ReactorPlacement(2041, STATUE, "2", 3, 132, 163), all.get(1));
        assertEquals(3, view.reactorStateByOid(2041), "the state of that one statue");
        assertEquals(0, view.reactorStateByOid(2043));
        assertEquals(-1, view.reactorStateByOid(999), "no such reactor on the map");
    }

    @Test
    void aViewWithoutAMapKnowsNoReactors() {
        LiveStageView view = viewOver(null);

        assertTrue(view.reactors().isEmpty());
        assertEquals(-1, view.reactorStateByOid(2041));
        assertFalse(new StageView() {
            @Override
            public MechanicsModel.Stage stage() {
                return null;
            }

            @Override
            public List<RunMember> members() {
                return List.of();
            }

            @Override
            public String roleOf(int characterId) {
                return "";
            }

            @Override
            public java.util.OptionalInt answerFor(int characterId) {
                return java.util.OptionalInt.empty();
            }

            @Override
            public int itemCount(int characterId, int itemId) {
                return 0;
            }

            @Override
            public java.util.Optional<String> eventProperty(String key) {
                return java.util.Optional.empty();
            }

            @Override
            public int[] positionOf(int characterId) {
                return new int[]{0, 0};
            }
        }.reactors().iterator().hasNext(), "the interface's own default: a fake view knows none");
    }
}
