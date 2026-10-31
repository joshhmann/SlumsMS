package soloMapling.autoqa;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotMakerSystem.BotMaker;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyMeters;
import soloMapling.Economy.EconomyStore;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 112 decision 3: auto-QA t-mk-2, the bots' Maker batch; its wiring, its headroom and its verdict rules. */
class ProbesMakerTest {

    /** The world's spender, a normal bot, and the QA run's own bot. Distinct ids keep other suites' counters away. */
    private static final int SPENDER = 1_500_000_001;
    private static final int OTHER = 1_500_000_002;
    private static final int QA = 1_500_000_009;

    private final AtomicReference<LocalDate> day = new AtomicReference<>(LocalDate.ofEpochDay(300));

    @BeforeEach
    void setUp() {
        EconomyMeters.useForTest(new EconomyStore.Memory(), Runnable::run, day::get);
        for (int id : new int[]{SPENDER, OTHER, QA}) {
            BotMaker.forget(id);
        }
    }

    @AfterEach
    void reset() {
        EconomyConfig.useForTest(null);
        EconomyMeters.useForTest(null, null, null);
        BotMaker.releaseQaOperations();
    }

    private static void config(int perBot, int world) {
        EconomyConfig.useForTest(Map.of("maker.botBatch", "true", "maker.perBotDaily", String.valueOf(perBot),
                "maker.worldDaily", String.valueOf(world)));
    }

    private static Character bot(int id) {
        Character chr = Character.getDefault(new BotClient(1, 1));
        try {
            java.lang.reflect.Field f = Character.class.getDeclaredField("id");
            f.setAccessible(true);
            f.setInt(chr, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        chr.setName("Bot" + id);
        return chr;
    }

    private static List<BotMaker.Op> ops(int n, AtomicInteger calls) {
        List<BotMaker.Op> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(() -> {
                calls.incrementAndGet();
                return 0;
            });
        }
        return out;
    }

    /** Spends the world's whole day with a normal bot: the cap the check used to SKIP on. */
    private static void spendTheWorldCap(int world) {
        AtomicInteger calls = new AtomicInteger();
        assertEquals(world, BotMaker.run(bot(SPENDER), ops(world, calls), 99).made(), "the world's day is spent");
        assertEquals(0, BotMaker.worldCapLeft());
    }

    @Test
    void theCheckIsAutomatedNightlyAndUsesAKnownProbe() {
        Check c = AutoQaCatalog.find("t-mk-2");
        assertNotNull(c);
        assertEquals(Check.Kind.AUTO, c.kind());
        assertTrue(c.nightly());
        Pattern probe = Pattern.compile("^(?:probe|fixture) (\\w+)");
        int probes = 0;
        for (Check.Expect e : c.expects()) {
            Matcher m = probe.matcher(e.describe());
            if (m.find()) {
                assertTrue(ProbesMaker.NAMES.contains(m.group(1)), "unknown probe " + m.group(1));
                probes++;
            }
        }
        assertEquals(1, probes);
        assertNull(ProbesMaker.probe("noSuchProbe", null));
        for (String n : ProbesMaker.NAMES) {
            assertNull(Probes160.probe(n, null), n);
            assertNull(Probes162.probe(n, null), n);
            assertNull(Probes164.probe(n, null), n);
        }
    }

    @Test
    void withTheSwitchOffItSkipsAndNamesIt() {
        EconomyConfig.useForTest(Map.of("maker.botBatch", "false"));
        List<String> out = ProbesMaker.probe("makerBatch", null);
        assertTrue(out.get(0).startsWith("SKIP "), out.toString());
        assertTrue(out.get(0).contains("solomapling.economy.maker.botBatch"));
    }

    /**
     * TEST 10-31 (run 4d3de9837): mk-2 skipped with "0 operations left" while the world's own bots had spent the day.
     * The check no longer skips: it reserves exactly the shortfall for its own bot, so a busy world still runs it.
     */
    @Test
    void theHeadroomIsExactlyTheShortfallAndACapStillShortIsAFailure() {
        assertEquals(3, ProbesMaker.headroomNeeded(0, ProbesMaker.BATCH_OPS), "nothing left: reserve the whole batch");
        assertEquals(1, ProbesMaker.headroomNeeded(2, ProbesMaker.BATCH_OPS), "2 left of 3: reserve one");
        assertEquals(0, ProbesMaker.headroomNeeded(3, ProbesMaker.BATCH_OPS), "3 left: the world already fits it");
        assertEquals(0, ProbesMaker.headroomNeeded(300, ProbesMaker.BATCH_OPS));
        assertEquals(4, ProbesMaker.headroomNeeded(-1, ProbesMaker.BATCH_OPS), "an overspent day still fits exactly");

        String capped = ProbesMaker.capFailure(2, 303, ProbesMaker.BATCH_OPS);
        assertNotNull(capped);
        assertTrue(capped.startsWith("FAIL "), capped);
        assertTrue(capped.contains("2 of 303 operations left"), capped);
        assertNull(ProbesMaker.capFailure(3, 303, ProbesMaker.BATCH_OPS));
        assertNull(ProbesMaker.capFailure(300, 300, ProbesMaker.BATCH_OPS));
    }

    @Test
    void withRoomTheCheckTakesNoHeadroomAndLeavesNoneBehind() {
        config(10, 300);
        Character qa = bot(QA);
        AtomicInteger calls = new AtomicInteger();

        ProbesMaker.Run ran = ProbesMaker.withHeadroom(QA, ProbesMaker.BATCH_OPS,
                () -> BotMaker.run(qa, ops(ProbesMaker.BATCH_OPS, calls), 99));

        assertNull(ran.failure(), String.valueOf(ran.failure()));
        assertEquals(3, ran.result().made());
        assertEquals(0, BotMaker.qaOperationsHeld(), "the world's day had room, so no reservation was taken or left");
        assertEquals(297, BotMaker.worldCapLeft(), "the world's real cap and count, nothing added");
        assertEquals(BotMaker.worldCapLeft(), BotMaker.worldCapLeftFor(OTHER), "no bot gets headroom it did not need");
    }

    @Test
    void withTheWorldCapSpentTheCheckStagesItsOwnHeadroomAndStillPasses() {
        config(10, 4);
        spendTheWorldCap(4);
        Character qa = bot(QA);
        AtomicInteger calls = new AtomicInteger();

        ProbesMaker.Run ran = ProbesMaker.withHeadroom(QA, ProbesMaker.BATCH_OPS,
                () -> BotMaker.run(qa, ops(ProbesMaker.BATCH_OPS, calls), 99));

        assertNull(ran.failure(), String.valueOf(ran.failure()));
        assertEquals(3, ran.result().made(), "the reservation carried the batch on a world with 0 operations left");
        assertEquals(3, calls.get());
        long worldOps = EconomyMeters.get(BotMaker.WORLD_METER) - 4;
        var o = new ProbesMaker.Outcome(ran.result().made(), 1, 2, 14000, 0, 14000, 0, 5, 2, true,
                BotMaker.opsToday(QA), worldOps);
        assertEquals(List.of(), ProbesMaker.problems(o), "a real batch on a spent world is a PASS, not a skip");
    }

    @Test
    void theHeadroomIsReleasedAndOnlyTheRealOperationsRemain() {
        config(10, 4);
        spendTheWorldCap(4);
        Character qa = bot(QA);
        AtomicInteger calls = new AtomicInteger();

        ProbesMaker.Run ran = ProbesMaker.withHeadroom(QA, ProbesMaker.BATCH_OPS,
                () -> BotMaker.run(qa, ops(ProbesMaker.BATCH_OPS, calls), 99));
        assertEquals(3, ran.result().made());

        assertEquals(7, EconomyMeters.get(BotMaker.WORLD_METER), "only the QA bot's 3 real operations are left behind");
        assertEquals(0, BotMaker.releaseQaOperations(), "the batch already released the reservation");
        assertEquals(4 - 7, BotMaker.worldCapLeft(), "the world is back to its real count: the extra room is gone");
        assertEquals(BotMaker.worldCapLeft(), BotMaker.worldCapLeftFor(OTHER), "a normal bot never gets the headroom");
        assertEquals(BotMaker.worldCapLeft(), BotMaker.worldCapLeftFor(QA),
                "and the QA bot keeps no headroom past the world's own count");

        BotMaker.Result other = BotMaker.run(bot(OTHER), ops(1, calls), 99);
        assertEquals(0, other.made(), "a normal bot sees the full cap spent again");
        assertTrue(other.stoppedBy().startsWith("world daily limit"), other.stoppedBy());
        assertEquals(0, BotMaker.run(qa, ops(1, calls), 99).made(), "the QA bot is capped again too");
        assertEquals(7, EconomyMeters.get(BotMaker.WORLD_METER), "and nothing more was counted");
    }

    @Test
    void anExceptionMidBatchStillReleasesTheHeadroom() {
        config(10, 4);
        spendTheWorldCap(4);

        assertThrows(IllegalStateException.class, () -> ProbesMaker.withHeadroom(QA, ProbesMaker.BATCH_OPS, () -> {
            throw new IllegalStateException("the batch threw");
        }));

        assertEquals(0, BotMaker.releaseQaOperations(), "the finally released it");
        assertEquals(0, BotMaker.worldCapLeftFor(QA), "no headroom survives a batch that threw");
        assertEquals(4, EconomyMeters.get(BotMaker.WORLD_METER), "and it counted nothing");
    }

    @Test
    void aLeakedReservationIsAFailureNamingTheCapNotASkip() {
        config(10, 4);
        spendTheWorldCap(4);
        assertTrue(BotMaker.reserveQaOperations(QA, 3), "another run (or a crashed one) holds the headroom");

        ProbesMaker.Run ran = ProbesMaker.withHeadroom(QA, ProbesMaker.BATCH_OPS, () -> BotMaker.Result.NONE);

        assertNull(ran.result(), "no batch may run on a reservation it does not own");
        assertNotNull(ran.failure(), "a cap it cannot cover is a FAIL, never a SKIP");
        assertTrue(ran.failure().startsWith("FAIL "), ran.failure());
        assertTrue(ran.failure().contains("already held"), ran.failure());
        assertTrue(ran.failure().contains("0 of 4 operations left"), ran.failure());
        assertEquals(3, BotMaker.releaseQaOperations(), "the refused call left the held reservation alone");
    }

    @Test
    void theHeadroomBelongsToTheQaBotAndIsTakenOnce() {
        config(10, 4);
        spendTheWorldCap(4);
        AtomicInteger calls = new AtomicInteger();

        assertTrue(BotMaker.reserveQaOperations(QA, ProbesMaker.BATCH_OPS));
        assertFalse(BotMaker.reserveQaOperations(OTHER, ProbesMaker.BATCH_OPS), "set once: no second reservation");
        assertEquals(3, BotMaker.worldCapLeftFor(QA), "its own cap has the room");
        assertEquals(0, BotMaker.worldCapLeftFor(OTHER), "a normal bot's cap does not move");
        assertEquals(0, BotMaker.worldCapLeft(), "and the world's cap itself never changes");
        assertEquals(0, BotMaker.run(bot(OTHER), ops(1, calls), 99).made(), "a normal bot cannot spend the headroom");
    }

    private static ProbesMaker.Outcome good() {
        // 1 crystal + 2 disassemblies = 3 operations, though the disassemblies gave 4 more crystals
        return new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, true, 3, 3);
    }

    @Test
    void aCleanBatchHasNoProblems() {
        assertEquals(List.of(), ProbesMaker.problems(good()));
    }

    @Test
    void theFeeMustComeOffTheWalletToTheMeso() {
        var o = good();
        var low = new ProbesMaker.Outcome(o.made(), 1, 2, 14000, 100, 14000, 0, 5, 2, true, 3, 3);
        assertTrue(ProbesMaker.problems(low).get(0).contains("the client's fee is 14000"), ProbesMaker.problems(low).toString());
        var free = new ProbesMaker.Outcome(3, 1, 2, 14000, 14000, 14000, 0, 5, 2, true, 3, 3);
        assertFalse(ProbesMaker.problems(free).isEmpty(), "disassembling for free fails");
        var zeroFee = new ProbesMaker.Outcome(3, 1, 2, 5, 5, 0, 0, 5, 2, true, 3, 3);
        assertFalse(ProbesMaker.problems(zeroFee).isEmpty(), "a staged fee of 0 proves nothing");
    }

    @Test
    void theBadItemMustBeSkippedAndTheBatchMustCarryOn() {
        var disassembled = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, false, 3, 3);
        assertTrue(ProbesMaker.problems(disassembled).stream().anyMatch(s -> s.contains("bad equip")));
        var stoppedAtBad = new ProbesMaker.Outcome(1, 1, 2, 14000, 14000, 14000, 0, 1, 0, true, 1, 1);
        List<String> p = ProbesMaker.problems(stoppedAtBad);
        assertTrue(p.stream().anyMatch(s -> s.contains("made 1 operations, want 3")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("disassembled 0 of 2")), p.toString());
    }

    @Test
    void theCapsCountOperationsNotCrystals() {
        var byCrystals = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, true, 5, 5);
        List<String> p = ProbesMaker.problems(byCrystals);
        assertEquals(2, p.size(), p.toString());
        assertTrue(p.get(0).contains("not 5 crystals"));
        var worldOnly = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 0, 5, 2, true, 3, 0);
        assertEquals(1, ProbesMaker.problems(worldOnly).size());
    }

    @Test
    void noCrystalIsAFailure() {
        var o = new ProbesMaker.Outcome(3, 1, 2, 14000, 0, 14000, 100, 0, 2, true, 3, 3);
        assertTrue(ProbesMaker.problems(o).get(0).contains("100 leftovers left"));
    }
}
