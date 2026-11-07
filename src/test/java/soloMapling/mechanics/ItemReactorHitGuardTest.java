package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.6 b365: the executor's guard behind the rows. A reactor whose current state is an item trigger (Reactor.wz type
 * 100) is never hit by a regular unless the stage row writes the exception down and the run fits it: a client never
 * hits such a reactor, and a server-side hit runs its script without the item.
 */
class ItemReactorHitGuardTest {

    static final int PLAYER = 7;
    static final int REGULAR = 1_000_000_321;

    /** A reactor standing in an item state: type 100 in state 0, which a client can see (state size 1). */
    static server.maps.Reactor itemReactor(int id, java.awt.Point at) {
        server.maps.ReactorStats stats = mock(server.maps.ReactorStats.class);
        when(stats.getType((byte) 0)).thenReturn(100);
        when(stats.getStateSize((byte) 0)).thenReturn((byte) 1);
        server.maps.Reactor r = mock(server.maps.Reactor.class);
        when(r.getId()).thenReturn(id);
        when(r.getStats()).thenReturn(stats);
        when(r.getState()).thenReturn((byte) 0);
        when(r.getReactorType()).thenReturn(100);
        when(r.getPosition()).thenReturn(at);
        return r;
    }

    static RunState run(String event, boolean withPlayer, int stage, int playerJob) {
        Content c = MechanicsRuns.contentFor(event);
        assertNotNull(c, event);
        Orchestrator orch = new Orchestrator(Orchestrator.allSolvers(), new Random(1), 0, 0);
        List<RunMember> party = new ArrayList<>();
        if (withPlayer) {
            party.add(new RunMember(PLAYER, true, false, MechanicsPersona.PERFECT, playerJob, 120));
        }
        party.add(new RunMember(REGULAR, !withPlayer, true, MechanicsPersona.PERFECT, 310, 120));
        RunState run = orch.start(c, Tier.NORMAL, party);
        if (stage > 1) {
            run.setStage(stage, 0);
        }
        return run;
    }

    /** The executor itself: a regular standing at an item reactor on a row with no exception does not hit it. */
    @Test
    void aRegularAtAnItemReactorWithNoWrittenExceptionDoesNotHitIt() {
        RunState run = run("ZakumPQ", true, 1, 0);
        java.awt.Point at = new java.awt.Point(100, 50);
        server.maps.Reactor reactor = itemReactor(2_112_011, at);
        server.maps.MapleMap map = mock(server.maps.MapleMap.class);
        when(map.getReactorById(2_112_011)).thenReturn(reactor);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(REGULAR);
        when(bot.getName()).thenReturn("Regular");
        when(bot.getMapId()).thenReturn(280_010_000);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new java.awt.Point(at));
        List<String> lines = new ArrayList<>();
        StepExecutor exec = new StepExecutor();
        for (long now = 5_000; now <= 20_000; now += 5_000) {
            exec.execute(run, null, null, bot, new SolverStep.HitReactor(2_112_011), now, lines::add);
        }
        verify(reactor, never()).hitReactor(any());
        assertTrue(lines.stream().anyMatch(l -> l.contains("not hitting it")), "the refusal is in the feed: " + lines);
        assertTrue(lines.stream().filter(l -> l.contains("not hitting it")).count() == 1, "said once: " + lines);
    }

    @Test
    void botOnlyLetsRegularsOnTheirOwnHitAndRefusesARunWithAPlayer() {
        server.maps.Reactor pot = itemReactor(2201004, new java.awt.Point(0, 0));
        assertNull(StepExecutor.itemReactorHitRefused(run("PapulatusBattle", false, 1, 0), pot),
                "regulars on their own: the written exception");
        assertNotNull(StepExecutor.itemReactorHitRefused(run("PapulatusBattle", true, 1, 0), pot),
                "a player in the run: the room waits for the item");
    }

    @Test
    void regularJobsOnlyRefusesTheWeaponOfAPlayersJobAndLetsTheOthersOpen() {
        server.maps.Reactor sword = itemReactor(6109005, new java.awt.Point(0, 0));
        server.maps.Reactor bow = itemReactor(6109006, new java.awt.Point(0, 0));
        RunState warriorLed = run("CWKPQ", true, 3, 110);
        assertNotNull(StepExecutor.itemReactorHitRefused(warriorLed, sword), "the warrior brings the sword");
        assertNull(StepExecutor.itemReactorHitRefused(warriorLed, bow), "no player is a bowman: the bow may open");
    }

    @Test
    void aReactorThatIsNotInAnItemStateIsNotTheGuardsBusiness() {
        server.maps.Reactor rock = mock(server.maps.Reactor.class);
        when(rock.getStats()).thenReturn(mock(server.maps.ReactorStats.class));
        when(rock.getReactorType()).thenReturn(0);
        assertNull(StepExecutor.itemReactorHitRefused(run("ZakumPQ", true, 1, 0), rock));
    }
}
