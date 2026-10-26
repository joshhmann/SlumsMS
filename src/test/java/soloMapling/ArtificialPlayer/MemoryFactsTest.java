package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 1.6.0 S3, "who you are": the last topic, inside jokes and goals a bot takes from a chat, scripted. */
class MemoryFactsTest {

    private static BotBrain.Turn u(String s) {
        return BotBrain.Turn.user(s);
    }

    private static BotBrain.Turn a(String s) {
        return BotBrain.Turn.assistant(s);
    }

    @Test
    void theTopicIsWhatTheNewestLineNamed() {
        assertEquals("KPQ", MemoryFacts.topic(List.of(u("hey"), a("sup, wanna kpq?"), u("sure"))));
        assertEquals("zhelm", MemoryFacts.topic(List.of(u("zak helm drop rate is so bad"))), "the helmet, not the boss");
        assertEquals("zakum", MemoryFacts.topic(List.of(u("zak run tonight?"))));
        assertEquals("level 70", MemoryFacts.topic(List.of(u("almost lvl 70"))));
        assertEquals("the FM", MemoryFacts.topic(List.of(u("meet me in the fm"))));
        assertNull(MemoryFacts.topic(List.of(u("hi"), a("hello"))));
        assertNull(MemoryFacts.topic(List.of(u("that amount is fine"))), "word boundaries: amount is not a mount");
    }

    @Test
    void anInsideJokeIsALineTheOtherSideLaughedAt() {
        assertEquals("my pet ate my return scroll", MemoryFacts.insideJoke(List.of(u("my pet ate my return scroll"),
                a("LMAO"))));
        assertEquals("u walk like a stump", MemoryFacts.insideJoke(List.of(a("u walk like a stump"), u("lol ok"))));
        assertNull(MemoryFacts.insideJoke(List.of(u("my pet ate my return scroll"), u("lol"))), "laughing at yourself");
        assertNull(MemoryFacts.insideJoke(List.of(u("ok"), a("lol"))), "too short");
        assertNull(MemoryFacts.insideJoke(List.of(u("are you a bot or what"), a("lol"))), "never AI talk");
        assertNull(MemoryFacts.insideJoke(List.of(u("my number is 5551234567"), a("lol"))), "nothing private");
        assertNull(MemoryFacts.insideJoke(List.of(u("my pet ate my scroll"), a("nice"))), "nobody laughed");
    }

    @Test
    void goalsAndWantedItemsGetFollowUpTags() {
        assertEquals(new MemoryFacts.Goal("wants zhelm", "item:zhelm"), MemoryFacts.goalIn("im saving up for a zhelm"));
        assertEquals(new MemoryFacts.Goal("trying for 4th job", "goal:4th job"),
                MemoryFacts.goalIn("trying for 4th job this week"));
        assertEquals(new MemoryFacts.Goal("wants to do zakum", "goal:zakum"), MemoryFacts.goalIn("i wanna do zak"));
        assertEquals(new MemoryFacts.Goal("trying for level 120", "goal:level 120"),
                MemoryFacts.goalIn("grinding to lv120 tonight"));
        assertEquals(new MemoryFacts.Goal("wants ilbis", "item:ilbis"), MemoryFacts.goalIn("lf ilbis cheap"));
        assertNull(MemoryFacts.goalIn("i dont want a zhelm"), "negated");
        assertNull(MemoryFacts.goalIn("i need pots"), "not in the vocabulary: nothing is invented");
        assertNull(MemoryFacts.goalIn("zhelm is nice"), "naming an item is not wanting it");
        assertEquals("item:pink adventurer cape",
                MemoryFacts.tagOf("said they're saving up for a pink adventurer cape"), "LLM notes are tagged too");
        assertEquals("", MemoryFacts.tagOf("is a good friend, always nice to me"));
        assertEquals(List.of(new MemoryFacts.Goal("wants zhelm", "item:zhelm")),
                MemoryFacts.goals(List.of(u("saving for a zhelm"), a("i want a zhelm too"), u("need a zhelm so bad"))),
                "the player's lines only, one per tag");
    }
}
