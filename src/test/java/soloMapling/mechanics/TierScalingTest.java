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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The tiers, both halves: the three rows that got one, checked against the proposal table rather than against themselves,
 * and round 11 item C's rule that a tier's numbers reach the room ({@code -Dslums.mechanics.tiers} off is the WZ numbers
 * for everyone; on, only the ids the stage lists are scaled, once each, and a respawn is scaled when it appears).
 *
 * <p>The HARD numbers are the {@code HPQ} / {@code LPQ} / {@code OPQ} rows of {@code DIFFICULTY-CHAOS-PLAN.md} section 9,
 * typed a second time here on purpose: a test that reads the row and compares it to the row proves nothing, and this is
 * the one place the transcription can be caught drifting. Every value is the table's, none derived here.
 */
class TierScalingTest {

    /** section 9, HARD rows: minLevel, maxLevel, mobLevelBonus, mobHpMultiplier, mobDamageMultiplier, minutes, mistake. */
    private static final Map<String, int[]> HARD = Map.of(
            "hpq", new int[]{30, 54, 20, 6, 15},   // multipliers checked separately, they are doubles
            "lpq", new int[]{55, 79, 20, 3, 55},
            "opq", new int[]{71, 95, 20, 4, 55});

    @AfterEach
    void switchOff() {
        System.clearProperty(TierScaling.FLAG);
        System.clearProperty(TierScaling.TIER_PROPERTY);
    }

    private static MechanicsModel.Content row(String id) {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the data folder parses: " + r.errors());
        return r.content().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theThreeRowsCarryTheirHardTierAndItIsTheTables() {
        for (String id : List.of("hpq", "lpq", "opq")) {
            MechanicsModel.Content c = row(id);
            MechanicsModel.TierRules hard = c.rules(MechanicsModel.Tier.HARD).orElseThrow(
                    () -> new AssertionError(id + " carries no HARD tier"));
            int[] table = HARD.get(id);
            assertEquals(table[0], hard.minLevel(), id + " HARD minLevel");
            assertEquals(table[1], hard.maxLevel(), id + " HARD maxLevel");
            assertEquals(table[2], hard.mobLevelBonus(), id + " HARD mobLevelBonus");
            assertEquals(table[3], hard.mobHpMultiplier(), 0.0001, id + " HARD mobHpMultiplier");
            assertEquals(1.5, hard.mobDamageMultiplier(), 0.0001, id + " HARD mobDamageMultiplier (flat 1.5)");
            assertEquals(table[4], hard.timeLimitMinutes(), id + " HARD timeLimitMinutes");
            assertEquals(1.25, hard.botMistakeFactor(), 0.0001, id + " HARD botMistakeFactor (the kpq.yaml example's)");
            // and only HARD: EXPERT and MASTER are in the table but deliberately not copied into the rows
            assertTrue(c.rules(MechanicsModel.Tier.EXPERT).isEmpty(), id + " carries EXPERT unexpectedly");
            assertTrue(c.rules(MechanicsModel.Tier.MASTER).isEmpty(), id + " carries MASTER unexpectedly");
        }
    }

    @Test
    void normalIsStillTheStockQuestAndNoTierMovedIt() {
        // the stock gates, exactly as the rows had them before the tiers were added
        Map<String, int[]> stock = Map.of("hpq", new int[]{10, 255, 10}, "lpq", new int[]{35, 255, 45},
                "opq", new int[]{51, 255, 45});
        for (String id : List.of("hpq", "lpq", "opq")) {
            MechanicsModel.Content c = row(id);
            int[] want = stock.get(id);
            assertEquals(want[0], c.minLevel(), id + " stock minLevel");
            assertEquals(want[1], c.maxLevel(), id + " stock maxLevel");
            assertEquals(want[2], c.timeLimitMinutes(), id + " stock clock");
            MechanicsModel.TierRules normal = c.rules(MechanicsModel.Tier.NORMAL).orElseThrow();
            assertEquals(c.minLevel(), normal.minLevel(), id + " NORMAL is the row's own floor");
            assertEquals(c.maxLevel(), normal.maxLevel());
            assertEquals(1.0, normal.mobHpMultiplier(), 0.0001, id + " NORMAL scales nothing");
            assertEquals(1.0, normal.mobDamageMultiplier(), 0.0001);
            assertEquals(1.0, normal.botMistakeFactor(), 0.0001);
        }
    }

    @Test
    void aTierStartsExactlyWhereItsMobBonusSaysAndLeavesRoomForTheNext() {
        for (String id : List.of("hpq", "lpq", "opq")) {
            MechanicsModel.Content c = row(id);
            MechanicsModel.TierRules hard = c.rules(MechanicsModel.Tier.HARD).orElseThrow();
            // rule 1 + rule 3 of section 9: the tier's floor is the stock floor plus the mob bonus, so the band does not
            // start below where its mobs are scaled for, and rule 2: the ceiling is the next tier's floor minus one
            assertEquals(c.minLevel() + hard.mobLevelBonus(), hard.minLevel(), id + " HARD floor follows its mob bonus");
            assertTrue(hard.maxLevel() > hard.minLevel(), id + " HARD has a band");
            assertTrue(hard.maxLevel() < c.maxLevel(), id + " HARD closes before the stock ceiling");
            assertTrue(hard.maxLevel() < 200, id + " room is left for an EXPERT floor under the table's 200 cap");
            // no gap anywhere the row does name tiers: each named tier's floor is the previous ceiling + 1, or the first
            EnumMap<MechanicsModel.Tier, MechanicsModel.TierRules> named = new EnumMap<>(MechanicsModel.Tier.class);
            for (MechanicsModel.Tier t : MechanicsModel.Tier.values()) {
                c.rules(t).ifPresent(rr -> named.put(t, rr));
            }
            MechanicsModel.Tier previous = null;
            for (MechanicsModel.Tier t : named.keySet()) {
                if (previous != null && previous != MechanicsModel.Tier.NORMAL) {
                    assertEquals(named.get(previous).maxLevel() + 1, named.get(t).minLevel(),
                            id + ": a gap between " + previous + " and " + t);
                }
                previous = t;
            }
        }
    }

    @Test
    void theSwitchDecidesWhetherARowAboveNormalMayBeRead() {
        MechanicsModel.Content hpq = row("hpq");
        System.clearProperty(TierScaling.FLAG);
        assertFalse(TierScaling.on());
        assertNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.HARD), "switch off: HARD is not readable");
        assertNotNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.NORMAL), "NORMAL needs no switch");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(hpq), "and no run starts above NORMAL");

        System.setProperty(TierScaling.FLAG, "true");
        assertTrue(TierScaling.on());
        assertNotNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.HARD), "switch on and the row names HARD");
        assertNull(TierScaling.rulesFor(hpq, MechanicsModel.Tier.EXPERT), "switch on but the row does not name EXPERT");
        assertNull(TierScaling.rulesFor(hpq, null));

        System.setProperty(TierScaling.TIER_PROPERTY, "HARD");
        assertEquals(MechanicsModel.Tier.HARD, TierScaling.requested(hpq));
        System.setProperty(TierScaling.TIER_PROPERTY, "EXPERT");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(hpq),
                "a tier the row does not carry is simply the stock quest");
        System.setProperty(TierScaling.TIER_PROPERTY, "nonsense");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(hpq));
    }

    @Test
    void aRowWithoutTiersIsUntouchedByTheSwitch() {
        System.setProperty(TierScaling.FLAG, "true");
        System.setProperty(TierScaling.TIER_PROPERTY, "HARD");
        MechanicsModel.Content ellin = row("ellinpq");
        assertTrue(ellin.rules(MechanicsModel.Tier.HARD).isEmpty(), "ellinpq.yaml names no tiers at all");
        assertEquals(MechanicsModel.Tier.NORMAL, TierScaling.requested(ellin),
                "the property names a tier the row does not carry, which is the stock quest, not an error");
        MechanicsModel.Content kpq = row("kpq");
        assertTrue(kpq.rules(MechanicsModel.Tier.EXPERT).isEmpty(), "kpq.yaml names HARD only: no EXPERT numbers to read");
    }

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
        MechanicsModel.Content row = MechanicsRuns.contentFor("EllinPQ");
        assertTrue(row.rules(Tier.HARD).isEmpty(), "the Ellin row names no tiers: kpq, hpq, lpq and opq are the ones that do");
        RunState run = run(row, Tier.HARD);
        Monster listed = mob(1210102, 1_000);

        assertNull(TierScaling.rulesFor(run), "ellinpq.yaml names no tiers, so HARD has no numbers to apply");
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
