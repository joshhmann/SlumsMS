package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 162 runfix (t-bt-3 on TEST 10-30: one of the two staged bots was "in the middle of grind" for the whole 90 s). */
class TalkQaFreePairTest {

    private final java.util.function.IntFunction<LifeRuntime> before = BotTalk.lives;

    @AfterEach
    void tearDown() {
        BotTalk.lives = before;
    }

    private static Character bot(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        return c;
    }

    private static LifeRuntime life(String blocker) {
        LifeRuntime l = mock(LifeRuntime.class);
        when(l.commitBlocker(anyLong())).thenReturn(blocker);
        return l;
    }

    @Test
    void aGrindingBotIsPassedOverForTheFirstTwoThatAreFree() throws Exception {
        // Fail-before: the probe staged exactly two and waited on both; one grinding bot sank the check.
        Map<Integer, LifeRuntime> lives = Map.of(1, life("in the middle of grind"), 2, life(null), 3, life(null),
                4, life("in the middle of grind"));
        BotTalk.lives = lives::get;
        List<Character> staged = List.of(bot(1, "octogurl"), bot(2, "b"), bot(3, "c"), bot(4, "d"));
        List<String> held = new ArrayList<>();
        List<Character> two = TalkQa.awaitFreeOf(staged, 2, 0, held);
        assertEquals(List.of(staged.get(1), staged.get(2)), two);
        assertEquals(List.of("octogurl: in the middle of grind", "d: in the middle of grind"), held);
        // the old wait on a fixed pair could only report it held
        assertEquals(List.of("octogurl: in the middle of grind"), TalkQa.awaitFree(staged.subList(0, 2), 0));
    }

    @Test
    void fewerThanTwoFreeWhenTheWaitRunsOut() throws Exception {
        BotTalk.lives = id -> life(id == 1 ? null : "on its way somewhere (travel)");
        List<String> held = new ArrayList<>();
        List<Character> two = TalkQa.awaitFreeOf(List.of(bot(1, "a"), bot(2, "b")), 2, 0, held);
        assertEquals(1, two.size());
        assertEquals(1, held.size());
    }
}
