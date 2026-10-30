package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 158 item 4 (with ticket 156): quest questions are answered from the bot's own quest log, never invented. */
class BotQuestChatTest {
    IntFunction<String> oldQuests, oldMobs, oldNpcs;

    @BeforeEach
    void names() {
        oldQuests = BotQuestChat.questNames;
        oldMobs = BotQuestChat.mobNames;
        oldNpcs = BotQuestChat.npcNames;
        BotQuestChat.questNames = id -> Map.of(1001, "Snail Hunt", 1002, "Mushroom Menace").get(id);
        BotQuestChat.mobNames = id -> id == 100100 ? "Snail" : null;
        BotQuestChat.npcNames = id -> id == 1012100 ? "Athena Pierce" : null;
        BotQuestChat.clearNames();
        BotQuestIndex.set(BotQuestIndex.of(List.of(
                new BotQuestIndex.QuestMeta(1001, 1012100, 1012100, 10, 25, Map.of(100100, 10), 500, 0, Map.of(),
                        false, false, false, List.of("mob"), true),
                new BotQuestIndex.QuestMeta(1002, 1012100, 1012100, 40, 0, Map.of(100100, 30), 500, 0, Map.of(),
                        false, false, false, List.of("mob"), true))));
    }

    @AfterEach
    void restore() {
        BotQuestChat.questNames = oldQuests;
        BotQuestChat.mobNames = oldMobs;
        BotQuestChat.npcNames = oldNpcs;
        BotQuestChat.clearNames();
        BotQuestIndex.set(null);
    }

    static BotQuestLog.Active snail() {
        return new BotQuestLog.Active(1001, 1012100, 1012100, 100000000, 100000000, 100010000, Map.of(100100, 10), 0);
    }

    @Test
    void theRulesHearQuestQuestions() {
        for (String line : List.of("what quests r u doing?", "r u doing any quests", "u questing?",
                "what quest are you on", "which quest r u on")) {
            assertEquals(ChatTriage.Intent.QUESTS, ChatTriage.intentRule(line).orElse(null), line);
        }
        for (String line : List.of("recommend a quest", "any good quests for lvl 20?", "what quest should i do",
                "know any quests", "recommend me some quests pls")) {
            assertEquals(ChatTriage.Intent.RECOMMEND_QUEST, ChatTriage.intentRule(line).orElse(null), line);
        }
        for (String line : List.of("where do u train", "lf kpq", "wanna party", "nice quest lol")) {
            var intent = ChatTriage.intentRule(line).orElse(ChatTriage.Intent.NONE);
            assertNotEquals(ChatTriage.Intent.QUESTS, intent, line);
            assertNotEquals(ChatTriage.Intent.RECOMMEND_QUEST, intent, line);
        }
    }

    @Test
    void aBotOnAQuestNamesItAndItsRealProgress() {
        var log = new BotQuestLog();
        log.pick(snail());
        var picked = BotQuestChat.doing(log);
        assertTrue(picked.known());
        assertTrue(picked.fact().contains("about to take the quest \"Snail Hunt\" from Athena Pierce"), picked.fact());
        log.taken(BotQuestLog.Mode.REAL);
        log.addKills(100100, 4);
        var doing = BotQuestChat.doing(log);
        assertTrue(doing.fact().contains("\"Snail Hunt\" (4/10 snail)"), doing.fact());
        assertTrue(doing.scripted().contains("snail hunt") && doing.scripted().contains("4/10 snail"), doing.scripted());
        assertEquals("snail", doing.mustMention(), "the reply must name the quest");
    }

    @Test
    void aBotWithoutAQuestSaysSoAndNamesNone() {
        var none = BotQuestChat.doing(new BotQuestLog());
        assertFalse(none.known());
        assertFalse(none.scripted().contains("hunt"), none.scripted());
        assertNull(none.mustMention());
        var log = new BotQuestLog();
        log.pick(snail());
        log.complete(true, 1);
        var done = BotQuestChat.doing(log);
        assertTrue(done.known());
        assertTrue(done.fact().contains("finished \"Snail Hunt\""), done.fact());
    }

    @Test
    void aRecommendationIsAQuestTheBotKnowsThatFitsTheAsker() {
        var log = new BotQuestLog();
        log.pick(snail());
        log.complete(true, 1);
        log.pick(new BotQuestLog.Active(1002, 1012100, 1012100, 100000000, 100000000, 100010000, Map.of(100100, 30), 2));
        var low = BotQuestChat.recommend(log, 15);
        assertTrue(low.known());
        assertTrue(low.fact().contains("\"Snail Hunt\" (kill 10 snail for Athena Pierce)"), low.fact());
        var high = BotQuestChat.recommend(log, 45);
        assertTrue(high.fact().contains("Mushroom Menace"), "the level 40 one for a level 45: " + high.fact());
        var tooLow = BotQuestChat.recommend(log, 5);
        assertFalse(tooLow.known(), "nothing the bot knows fits a level 5");
        assertFalse(BotQuestChat.recommend(new BotQuestLog(), 15).known(), "a bot that never quested has nothing");
    }

    @Test
    void theChatActionCarriesTheFactAndTheWordTheReplyMustUse() {
        var log = new BotQuestLog();
        log.pick(snail());
        log.taken(BotQuestLog.Mode.MODELLED);
        var answer = BotQuestChat.doing(log);
        var result = new soloMapling.ArtificialPlayer.BotChatActions.Result(
                soloMapling.ArtificialPlayer.BotChatActions.Outcome.QUESTS, answer.fact(), answer.scripted(),
                answer.mustMention());
        assertTrue(result.fits(answer.scripted()));
        assertTrue(result.fits("ya doin the snail hunt rn"));
        assertFalse(result.fits("doing the mushroom quest lol"), "a made-up quest is replaced by the scripted line");
        var none = new soloMapling.ArtificialPlayer.BotChatActions.Result(
                soloMapling.ArtificialPlayer.BotChatActions.Outcome.QUESTS_NONE, "x", "no quests rn, just grinding", null);
        assertTrue(none.fits("nah just grinding"));
        assertFalse(none.fits("doing the snail quest"), "no quest in the log: the reply may not claim one");
    }
}
