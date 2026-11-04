package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 "PQ difficulties", round 11 item B: the plumbing only. A run carries its tier, the tier is the row's {@code
 * tiers:} block through {@link MechanicsModel.Content#rules(Tier)}, the party quest's own {@code setup(difficulty,
 * leaderId)} gets the tier as a number, and the {@code RUN start} line names it - all of it with {@link Tier#NORMAL}
 * staying byte-identical to what runs today. No numbers, no scaling: that is the executor's job, behind its own switch.
 */
class DifficultyTierTest {

    private static final String GOOD = """
            id: t
            name: Test PQ
            kind: PQ
            minPlayers: 2
            maxPlayers: 4
            timeLimitMinutes: 30
            sources: [ "ours" ]
            stages:
              - stage: 1
                map: 100
                npc: 9000
                solver: question-coupons
                roles:
                  - { id: leader, who: LEADER, min: 1, max: 1 }
                  - { id: answerer, who: NON_LEADER, min: party-1, max: party-1 }
                items:
                  - { id: 4001007, name: Coupon, cleanup: true }
                objectives:
                  - { id: farm, kind: KILL_FOR_ITEM, role: answerer, mob: 1, item: 4001007, count: answer }
                answers:
                  - { id: 1, answer: 10 }
                clear: { eventProperty: 1stageclear }
            """;

    private Supplier<ScheduledExecutorService> wasScheduler;

    @AfterEach
    void tearDown() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        System.clearProperty(Mechanics.BOSS_FLAG);
    }

    private static MechanicsModel.Content withHardBlock() throws Exception {
        String tiered = GOOD.replace("sources:", """
                tiers:
                  HARD: { minLevel: 40, mobLevelBonus: 10, mobHpMultiplier: 4, timeLimitMinutes: 25, botMistakeFactor: 1.25 }
                sources:""");
        StageData.Result r = StageData.parse(new StringReader(tiered));
        assertEquals(List.of(), r.errors());
        return r.content().get(0);
    }

    @Test
    void theStockTierIsTheRowsOwnNumbers() throws Exception {
        MechanicsModel.TierRules normal = withHardBlock().rules(Tier.NORMAL).orElseThrow();
        assertEquals(Tier.NORMAL, normal.tier());
        assertEquals(0, normal.mobLevelBonus(), "nothing is scaled at NORMAL");
        assertEquals(1.0, normal.mobHpMultiplier());
        assertEquals(1.0, normal.mobDamageMultiplier());
        assertEquals(1.0, normal.botMistakeFactor());
        assertEquals(30, normal.timeLimitMinutes(), "the row's own clock, not the tier's");
    }

    @Test
    void aNonStockTierIsTheRowsOwnTierBlock() throws Exception {
        MechanicsModel.TierRules hard = withHardBlock().rules(Tier.HARD).orElseThrow();
        assertEquals(Tier.HARD, hard.tier());
        assertEquals(40, hard.minLevel());
        assertEquals(10, hard.mobLevelBonus());
        assertEquals(4.0, hard.mobHpMultiplier());
        assertEquals(25, hard.timeLimitMinutes());
        assertEquals(1.25, hard.botMistakeFactor());
        assertFalse(withHardBlock().rules(Tier.EXPERT).filter(r -> r.tier() == Tier.EXPERT
                && r.mobHpMultiplier() != 1.0).isPresent(), "a tier the row does not name stays stock");
    }

    /** The run's clock is the tier's own minutes, and NORMAL's are the row's, so the stock run is unchanged. */
    @Test
    void theRunClockIsTheTiersAndTheStockClockIsTheRows() {
        MechanicsModel.Content content = MechanicsRuns.contentFor("KerningPQ");
        Orchestrator o = new Orchestrator(Orchestrator.allSolvers(), new java.util.Random(1), 0, 0);
        List<RunMember> party = List.of(new RunMember(1, true, false, MechanicsPersona.PERFECT),
                new RunMember(2, false, true, MechanicsPersona.PERFECT),
                new RunMember(3, false, true, MechanicsPersona.PERFECT));
        assertEquals(Math.max(1, content.timeLimitMinutes()) * 60_000L,
                o.start(content, Tier.NORMAL, party).budgetMs(), "NORMAL is the row's own 30 minutes");
    }

    /** The number {@code setup(difficulty, leaderId)} receives, with NORMAL on 1: the stock call, unchanged. */
    @Test
    void theStockTierIsDifficultyOne() {
        assertEquals(1, KpqStaging.difficulty(Tier.NORMAL));
        assertEquals(1, KpqStaging.difficulty(null), "a caller that never heard of tiers gets the stock number");
        assertEquals(2, KpqStaging.difficulty(Tier.HARD));
        assertEquals(3, KpqStaging.difficulty(Tier.EXPERT));
        assertEquals(4, KpqStaging.difficulty(Tier.MASTER));
    }

    private void quietScheduler() {
        ScheduledExecutorService quiet = mock(ScheduledExecutorService.class);
        when(quiet.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenReturn(mock(ScheduledFuture.class));
        wasScheduler = MechanicsRuns.scheduler;
        MechanicsRuns.scheduler = () -> quiet;
    }

    private static Character member(int id, boolean bot) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(bot);
        when(c.getName()).thenReturn("m" + id);
        return c;
    }

    private static EventInstanceManager bossRoom(List<Character> players, int leaderId) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        EventManager em = mock(EventManager.class);
        Channel ch = mock(Channel.class);
        when(ch.getId()).thenReturn(4);
        when(ch.getWorld()).thenReturn(2);
        when(em.getChannelServer()).thenReturn(ch);
        when(em.getName()).thenReturn("ZakumBattle");
        when(eim.getEm()).thenReturn(em);
        when(eim.getName()).thenReturn("Zakum0");
        when(eim.getPlayers()).thenReturn(new ArrayList<>(players));
        when(eim.getLeaderId()).thenReturn(leaderId);
        return eim;
    }

    private List<String> runLine(Tier tier) {
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        quietScheduler();
        List<Character> players = new ArrayList<>();
        Character leader = member(9_300, false);
        players.add(leader);
        for (int i = 0; i < 5; i++) {
            players.add(member(9_301 + i, true));
        }
        EventInstanceManager eim = bossRoom(players, 9_300);
        when(eim.getPlayerById(9_300)).thenReturn(leader);

        List<String> lines = new ArrayList<>();
        MechanicsRuns.get().start(eim, MechanicsRuns.contentFor("ZakumBattle"), tier, true, 0, 1, 0, true, lines::add,
                null);
        return lines;
    }

    @Test
    void theRunLineNamesTheTierOnlyWhenItIsNotStock() {
        List<String> normal = runLine(Tier.NORMAL);
        assertTrue(normal.stream().anyMatch(l -> l.startsWith("RUN start ")), normal.toString());
        assertTrue(normal.stream().noneMatch(l -> l.contains("tier ")),
                "a NORMAL run logs exactly the line it always logged: " + normal);

        List<String> hard = runLine(Tier.HARD);
        assertTrue(hard.stream().anyMatch(l -> l.startsWith("RUN start ") && l.contains("tier HARD; ")),
                "a non-stock tier is named in the run's own line: " + hard);
    }
}
