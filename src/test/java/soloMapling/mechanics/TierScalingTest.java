package soloMapling.mechanics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import server.life.Monster;
import server.life.MonsterStats;
import server.maps.MapleMap;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.MechanicsModel.TierRules;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Round 11 item C: the tier's numbers reach the room, and only when a server asks for them. {@code
 * -Dslums.mechanics.tiers} off is the WZ numbers for everyone; on, only the ids the stage lists are scaled, once each,
 * and a respawn is scaled when it appears.
 */
class TierScalingTest {

    private static final List<Integer> LISTED = List.of(1210102);

    private static final String TIERED = """
            id: t
            name: Test PQ
            kind: PQ
            minPlayers: 2
            maxPlayers: 4
            timeLimitMinutes: 30
            sources: [ "ours" ]
            tiers:
              HARD: { minLevel: 21, mobHpMultiplier: 2, timeLimitMinutes: 20 }
            stages:
              - stage: 1
                map: 100
                npc: 9000
                solver: question-coupons
                roles:
                  - { id: leader, who: LEADER, min: 1, max: 1 }
                  - { id: answerer, who: NON_LEADER, min: party-1, max: party-1 }
                mobs: [ 1210102 ]
                items:
                  - { id: 4001007, name: Coupon, cleanup: true }
                objectives:
                  - { id: farm, kind: KILL_FOR_ITEM, role: answerer, mob: 1210102, item: 4001007, count: answer }
                answers:
                  - { id: 1, answer: 10 }
                clear: { eventProperty: 1stageclear }
            """;

    @AfterEach
    void switchOff() {
        System.clearProperty(TierScaling.FLAG);
    }

    private static Monster mob(int id, long hp) {
        MonsterStats stats = new MonsterStats();
        stats.setHp(hp);
        stats.setMp(30);
        stats.setExp(15);
        return new Monster(id, stats);
    }

    private static TierRules hard() {
        return new TierRules(Tier.HARD, 21, 255, 0, 2.0, 1.0, 20, 1.0);
    }

    private static TierRules stock() {
        return new TierRules(Tier.NORMAL, 21, 255, 0, 1.0, 1.0, 30, 1.0);
    }

    @Test
    void aListedMobDoublesUnderHardAndAnUnlistedOneDoesNot() {
        Monster listed = mob(1210102, 1_000);
        Monster neighbour = mob(1210103, 1_000);

        int changed = TierScaling.scale(List.of(listed, neighbour), LISTED, hard());

        assertEquals(1, changed);
        assertEquals(2_000, listed.getMobMaxHp(), "the mob the row names wears the tier's HP");
        assertEquals(1_000, neighbour.getMobMaxHp());
        assertEquals(15, listed.getStats().getExp(), "EXP is not part of this tier's numbers");
    }

    @Test
    void theStockTierChangesNothing() {
        Monster listed = mob(1210102, 1_000);

        assertEquals(0, TierScaling.scale(List.of(listed), LISTED, stock()));
        assertEquals(1_000, listed.getMobMaxHp());
        assertNull(listed.getChangedStats(), "NORMAL leaves the mob exactly as WZ made it");
    }

    @Test
    void twoTicksDoNotDoubleApply() {
        Monster listed = mob(1210102, 1_000);

        assertEquals(1, TierScaling.scale(List.of(listed), LISTED, hard()));
        assertEquals(0, TierScaling.scale(List.of(listed), LISTED, hard()), "the second tick finds it already scaled");
        assertEquals(2_000, listed.getMobMaxHp(), "2000, not 4000");
    }

    @Test
    void aRespawnIsScaledAgainAndADeadMobIsLeftAlone() {
        Monster first = mob(1210102, 1_000);
        TierScaling.scale(List.of(first), LISTED, hard());

        Monster respawned = mob(1210102, 1_000); // the map's spawner makes a fresh mob with the WZ stats
        assertEquals(1, TierScaling.scale(List.of(respawned), LISTED, hard()));
        assertEquals(2_000, respawned.getMobMaxHp());

        Monster dead = mob(1210102, 1_000);
        dead.setHpZero(); // force HP = 0, the game's own way
        assertEquals(0, TierScaling.scale(List.of(dead), LISTED, hard()), "a corpse is not scaled");
        assertEquals(1_000, dead.getMobMaxHp());
    }

    // ---- the run side: the switch, the tier's own rule lookup, and the map the stage names ------------

    private static MechanicsModel.Content tieredRow() throws Exception {
        StageData.Result r = StageData.parse(new StringReader(TIERED));
        assertEquals(List.of(), r.errors());
        MechanicsModel.Content content = r.content().get(0);
        assertNotNull(content.rules(Tier.HARD).orElse(null), "the row names HARD");
        return content;
    }

    private static RunState run(MechanicsModel.Content content, Tier tier) {
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new Random(3), 0, 0);
        return o.start(content, tier, List.of(
                new RunMember(1, true, false, MechanicsPersona.PERFECT),
                new RunMember(2, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT)));
    }

    @Test
    void withTheSwitchOffTheRoomKeepsItsWzNumbers() throws Exception {
        MechanicsModel.Content content = tieredRow();
        RunState run = run(content, Tier.HARD);
        Monster listed = mob(1210102, 1_000);
        EventInstanceManager eim = room(run, List.of(listed));

        assertEquals(0, TierScaling.applyTo(run, eim), "the switch is off by default");
        assertEquals(1_000, listed.getMobMaxHp());
        assertNull(TierScaling.rulesFor(run));
    }

    @Test
    void withTheSwitchOnAScriptedStartScalesTheStageTheRunIsOn() throws Exception {
        System.setProperty(TierScaling.FLAG, "true");
        MechanicsModel.Content content = tieredRow();
        RunState run = run(content, Tier.HARD);
        Monster listed = mob(1210102, 1_000);
        Monster neighbour = mob(1210103, 1_000);
        EventInstanceManager eim = room(run, List.of(listed, neighbour));

        assertEquals(1, TierScaling.applyTo(run, eim));
        assertEquals(2_000, listed.getMobMaxHp());
        assertEquals(1_000, neighbour.getMobMaxHp(), "the map's other life is none of the tier's business");
        assertEquals(0, TierScaling.applyTo(run, eim), "and the next tick changes nothing");
    }

    @Test
    void aNormalRunIsNeverScaledEvenWithTheSwitchOn() throws Exception {
        System.setProperty(TierScaling.FLAG, "true");
        RunState run = run(tieredRow(), Tier.NORMAL);
        Monster listed = mob(1210102, 1_000);

        assertNull(TierScaling.rulesFor(run), "NORMAL is the stock PQ");
        assertEquals(0, TierScaling.applyTo(run, room(run, List.of(listed))));
        assertEquals(1_000, listed.getMobMaxHp());
    }

    @Test
    void aRowWithoutTheTierScalesNothing() {
        System.setProperty(TierScaling.FLAG, "true");
        MechanicsModel.Content row = MechanicsRuns.contentFor("OrbisPQ");
        assertTrue(row.rules(Tier.HARD).isEmpty(), "only kpq.yaml names a tier today");
        RunState run = run(row, Tier.HARD);
        Monster listed = mob(1210102, 1_000);

        assertNull(TierScaling.rulesFor(run), "orbis.yaml names no tiers, so HARD has no numbers to apply");
        assertEquals(0, TierScaling.applyTo(run, room(run, List.of(listed))));
    }

    private static EventInstanceManager room(RunState run, List<Monster> mobs) {
        MapleMap map = mock(MapleMap.class);
        when(map.getAllMonsters()).thenReturn(new ArrayList<>(mobs));
        EventInstanceManager eim = mock(EventInstanceManager.class);
        when(eim.getMapInstance(run.stageData().mapId())).thenReturn(map);
        return eim;
    }
}
