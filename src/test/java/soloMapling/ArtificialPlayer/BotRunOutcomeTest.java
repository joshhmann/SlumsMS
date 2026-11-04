package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConvo.ClaimRules;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pending;
import soloMapling.ArtificialPlayer.BotRunOutcome.Pool;
import soloMapling.ArtificialPlayer.BotRunOutcome.Slot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 run chatter, the bot's side: the phrase pool of {@code RunOutcomeDialogue.yaml} and the one slot a bot keeps for
 * what it has to say about the run it just left. The run's side (who speaks, win or loss) is {@code RunChatterTest}.
 */
class BotRunOutcomeTest {

    static final long T0 = 5_000_000L;
    static final int TOWN = 103000000;

    @BeforeEach
    @AfterEach
    void clean() {
        BotChatTuning.resetForTest();
        BotRunOutcome.reloadForTest();
    }

    static Character onMap(boolean alive) {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(chr.getMap()).thenReturn(map);
        when(chr.isAlive()).thenReturn(alive);
        return chr;
    }

    // ---- the pool -------------------------------------------------------------------------------

    @Test
    void thePackHasAPoolForEachOutcomeAndKindWithSeveralDistinctLines() {
        assertNotEquals(BotRunOutcome.pool(Pool.WIN_PQ), BotRunOutcome.pool(Pool.WIN_BOSS));
        assertNotEquals(BotRunOutcome.pool(Pool.LOSS_PQ), BotRunOutcome.pool(Pool.LOSS_BOSS));
        for (Pool pool : Pool.values()) {
            List<String> lines = BotRunOutcome.pool(pool);
            assertTrue(lines.size() >= 8, pool + " has " + lines.size() + " line(s): several variants so it does not read like a robot");
            assertEquals(lines.size(), new HashSet<>(lines).size(), pool + " repeats a line");
            for (String line : lines) {
                assertFalse(line.isBlank(), pool + " has an empty line");
            }
        }
    }

    @Test
    void mostLinesNameTheContentAndAnyTokenIsTheOneWeFill() {
        Pattern token = Pattern.compile("\\{[^}]*}");
        for (Pool pool : Pool.values()) {
            List<String> lines = BotRunOutcome.pool(pool);
            long naming = lines.stream().filter(l -> l.contains(BotRunOutcome.CONTENT_TOKEN)).count();
            assertTrue(naming * 4 >= lines.size() * 3, pool + ": " + naming + " of " + lines.size() + " name the content");
            for (String line : lines) {
                assertFalse(token.matcher(line.replace(BotRunOutcome.CONTENT_TOKEN, "")).find(),
                        "\"" + line + "\" carries a token other than {CONTENT}");
            }
        }
    }

    @Test
    void everyLineReadsAsAPlayerTypesItAndPromisesNothingTheBotMustDo() {
        Pattern disclosure = Pattern.compile("(?i)\\b(ai|bot|bots|robot|npc|llm|assistant|program|script)\\b");
        for (Pool pool : Pool.values()) {
            for (String raw : BotRunOutcome.pool(pool)) {
                String line = raw.replace(BotRunOutcome.CONTENT_TOKEN, "Cafe PQ, area 3");
                assertTrue(line.length() <= 60, "\"" + line + "\" is long for map chat");
                assertTrue(line.chars().allMatch(c -> c < 128), "\"" + line + "\" has a character the v83 client cannot show");
                assertFalse(disclosure.matcher(line).find(), "\"" + line + "\" says what a bot is");
                assertTrue(ClaimRules.classify(line).isEmpty(),
                        "\"" + line + "\" claims an action the say-do rule would hold the bot to");
            }
        }
    }

    @Test
    void aWinNeverReadsAsALossAndAKillNeverReadsAsAPartyQuest() {
        Pattern sad = Pattern.compile("(?i)\\b(dang|aww?|ugh|rip|fail(ed)?|didnt|couldnt|lost|tried)\\b");
        for (Pool pool : List.of(Pool.WIN_PQ, Pool.WIN_BOSS)) {
            for (String line : BotRunOutcome.pool(pool)) {
                assertFalse(sad.matcher(line).find(), pool + ": \"" + line + "\" is a win that sounds like a loss");
            }
        }
        Pattern glad = Pattern.compile("(?i)\\b(woo+|gg|yesss|we did it|cleared|we beat|killed)\\b");
        for (Pool pool : List.of(Pool.LOSS_PQ, Pool.LOSS_BOSS)) {
            for (String line : BotRunOutcome.pool(pool)) {
                assertFalse(glad.matcher(line).find(), pool + ": \"" + line + "\" is a loss that sounds like a win");
            }
        }
        for (String line : BotRunOutcome.pool(Pool.WIN_PQ)) {
            assertFalse(line.contains("kill") || line.contains("dead") || line.contains("down,"), line);
        }
        for (String line : BotRunOutcome.pool(Pool.LOSS_PQ)) {
            assertFalse(line.contains("got us") || line.contains("beat us") || line.contains("kill"), line);
        }
    }

    @Test
    void linesFillTheNameAreDifferentAndFollowTheRandomSourceGiven() {
        List<String> a = BotRunOutcome.lines(Pool.WIN_PQ, "Kerning PQ", 2, new Random(7));
        List<String> b = BotRunOutcome.lines(Pool.WIN_PQ, "Kerning PQ", 2, new Random(7));
        assertEquals(a, b, "the same random source gives the same lines");
        assertEquals(2, a.size());
        assertTrue(!a.get(0).equals(a.get(1)), "two speakers never say the same line: " + a);
        for (String line : a) {
            assertFalse(line.contains("{"), line);
        }
        Set<String> seen = new HashSet<>();
        for (int seed = 0; seed < 200; seed++) {
            seen.addAll(BotRunOutcome.lines(Pool.LOSS_BOSS, "Zakum", 1, new Random(seed)));
        }
        assertEquals(BotRunOutcome.pool(Pool.LOSS_BOSS).size(), seen.size(), "every variant gets used");
        assertTrue(seen.stream().anyMatch(l -> l.contains("Zakum got us") || l.contains("Zakum beat us")), seen.toString());
        assertEquals(List.of(), BotRunOutcome.lines(Pool.WIN_PQ, "x", 0, new Random(1)));
        assertEquals(BotRunOutcome.pool(Pool.WIN_PQ).size(),
                BotRunOutcome.lines(Pool.WIN_PQ, "x", 99, new Random(1)).size(), "never more than the pool has");
    }

    // ---- the slot -------------------------------------------------------------------------------

    @Test
    void aBotWithNothingPendingSaysNothingAndAsksNobody() {
        AtomicInteger asked = new AtomicInteger();
        assertFalse(BotRunOutcome.sayIfDue(new Slot(), onMap(true), T0, c -> {
            asked.incrementAndGet();
            return true;
        }, (c, l) -> true));
        assertEquals(0, asked.get());
    }

    @Test
    void aLineIsSaidOnceAndThenItIsGone() {
        Slot slot = new Slot();
        slot.set(new Pending("woo we beat Kerning PQ!", T0 + BotRunOutcome.TTL_MS));
        List<String> said = new ArrayList<>();
        Character chr = onMap(true);

        assertTrue(BotRunOutcome.sayIfDue(slot, chr, T0 + 3_000, c -> true, (c, l) -> said.add(l)));
        assertFalse(BotRunOutcome.sayIfDue(slot, chr, T0 + 6_000, c -> true, (c, l) -> said.add(l)));
        assertFalse(BotRunOutcome.sayIfDue(slot, chr, T0 + 9_000, c -> true, (c, l) -> said.add(l)));

        assertEquals(List.of("woo we beat Kerning PQ!"), said, "nothing is said twice for the same run");
        assertNull(slot.get());
    }

    @Test
    void aLineWaitsForSomeoneToSeeItAndForTheBotToBeInTheWorld() {
        Slot slot = new Slot();
        slot.set(new Pending("dang, Zakum got us", T0 + BotRunOutcome.TTL_MS));
        List<String> said = new ArrayList<>();

        assertFalse(BotRunOutcome.sayIfDue(slot, onMap(true), T0 + 1_000, c -> false, (c, l) -> said.add(l)),
                "no real player can see the map: the usual ambient rule");
        assertFalse(BotRunOutcome.sayIfDue(slot, onMap(false), T0 + 2_000, c -> true, (c, l) -> said.add(l)), "it is dead");
        Character nowhere = mock(Character.class);
        assertFalse(BotRunOutcome.sayIfDue(slot, nowhere, T0 + 3_000, c -> true, (c, l) -> said.add(l)), "between maps");
        assertFalse(BotRunOutcome.sayIfDue(slot, null, T0 + 3_000, c -> true, (c, l) -> said.add(l)));
        assertNotNull(slot.get(), "it still has the line");
        assertTrue(said.isEmpty());

        assertTrue(BotRunOutcome.sayIfDue(slot, onMap(true), T0 + 4_000, c -> true, (c, l) -> said.add(l)));
        assertEquals(List.of("dang, Zakum got us"), said);
    }

    @Test
    void aLineNobodySawIsDroppedWhenItGoesStale() {
        Slot slot = new Slot();
        slot.set(new Pending("gg", T0 + BotRunOutcome.TTL_MS));
        List<String> said = new ArrayList<>();

        assertFalse(BotRunOutcome.sayIfDue(slot, onMap(true), T0 + BotRunOutcome.TTL_MS + 1, c -> true,
                (c, l) -> said.add(l)));

        assertNull(slot.get(), "dropped, not kept for later");
        assertTrue(said.isEmpty(), "a win line two minutes late would read as a bot talking to itself");
    }

    @Test
    void aBusyMomentKeepsTheLineForTheNextTickAndUsesTheExistingBudget() {
        // the real ambient budget: one line a map every 3 s / multiplier (a town: 2 s), which two bots finishing a run
        // together run into. The second bot's line is not lost to that; it waits for its next tick.
        Slot first = new Slot();
        Slot second = new Slot();
        first.set(new Pending("woo we beat Kerning PQ!", T0 + BotRunOutcome.TTL_MS));
        second.set(new Pending("gg guys", T0 + BotRunOutcome.TTL_MS));
        List<String> said = new ArrayList<>();
        long[] clock = {T0};
        java.util.function.BiPredicate<Character, String> ambient = (c, l) -> {
            boolean through = BotChatTuning.takeAmbient(0, 1, TOWN, 1.5, clock[0]);
            if (through) {
                said.add(l);
            }
            return through;
        };

        assertTrue(BotRunOutcome.sayIfDue(first, onMap(true), clock[0], c -> true, ambient));
        clock[0] += 500;
        assertFalse(BotRunOutcome.sayIfDue(second, onMap(true), clock[0], c -> true, ambient), "the map was just spoken on");
        assertNotNull(second.get(), "its line stays");
        clock[0] += 2_500;
        assertTrue(BotRunOutcome.sayIfDue(second, onMap(true), clock[0], c -> true, ambient), "and goes out when the gap is over");
        assertEquals(List.of("woo we beat Kerning PQ!", "gg guys"), said);
    }

    @Test
    void aBotInTwoRunsInARowSpeaksAboutBothSubjectToTheSameBudget() {
        Slot slot = new Slot();
        List<String> said = new ArrayList<>();
        long[] clock = {T0};
        java.util.function.BiPredicate<Character, String> ambient = (c, l) -> {
            boolean through = BotChatTuning.takeAmbient(0, 1, TOWN, 1.5, clock[0]);
            if (through) {
                said.add(l);
            }
            return through;
        };
        Character chr = onMap(true);

        slot.set(new Pending("woo we beat Kerning PQ!", clock[0] + BotRunOutcome.TTL_MS));
        assertTrue(BotRunOutcome.sayIfDue(slot, chr, clock[0], c -> true, ambient));

        clock[0] += 10 * 60_000; // the second run, ten minutes later
        slot.set(new Pending("dang, we failed Ludi PQ", clock[0] + BotRunOutcome.TTL_MS));
        assertTrue(BotRunOutcome.sayIfDue(slot, chr, clock[0], c -> true, ambient));

        assertEquals(List.of("woo we beat Kerning PQ!", "dang, we failed Ludi PQ"), said);
    }

    @Test
    void aSecondRunEndingBeforeTheFirstWasSaidReplacesItInsteadOfSayingBoth() {
        Slot slot = new Slot();
        slot.set(new Pending("woo we beat Kerning PQ!", T0 + BotRunOutcome.TTL_MS));
        slot.set(new Pending("dang, we failed Ludi PQ", T0 + 30_000 + BotRunOutcome.TTL_MS));
        List<String> said = new ArrayList<>();

        BotRunOutcome.sayIfDue(slot, onMap(true), T0 + 31_000, c -> true, (c, l) -> said.add(l));
        BotRunOutcome.sayIfDue(slot, onMap(true), T0 + 34_000, c -> true, (c, l) -> said.add(l));

        assertEquals(List.of("dang, we failed Ludi PQ"), said, "the latest run is the one it is talking about");
    }
}
