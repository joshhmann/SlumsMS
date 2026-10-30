package soloMapling.ArtificialPlayer;

import client.Character;
import client.QuestStatus;
import org.junit.jupiter.api.Test;
import server.quest.Quest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

// Ticket 07 rule 1: bots carry no quest state. The bot store has no quest column and the bot grind economy
// already treats quest content as out of scope, so the character-level quest writers must refuse a bot
// instead of building in-memory state that would be dropped at despawn while still awarding quest points.
// The guard is exercised through the real methods (CALLS_REAL_METHODS), so this fails if a writer stops
// asking whether the character is a bot.
class BotQuestStateBoundaryTest {

    private static Character character(int id) {
        Character chr = mock(Character.class, CALLS_REAL_METHODS);
        when(chr.getId()).thenReturn(id);
        return chr;
    }

    private static Quest quest() {
        Quest quest = mock(Quest.class);
        when(quest.getId()).thenReturn((short) 1234);
        return quest;
    }

    @Test
    void everyQuestWriterRefusesABot() {
        Character bot = character(BotStore.BOT_ID_BASE + 1);
        Quest quest = quest();

        assertEquals(QuestStatus.Status.NOT_STARTED, bot.getQuest(quest).getStatus());
        assertNotNull(bot.getQuestNAdd(quest));
        assertNull(bot.getQuestNoAdd(quest));

        bot.setQuestAdd(quest, (byte) QuestStatus.Status.STARTED.getId(), null);
        assertNull(bot.getQuestNoAdd(quest));

        assertNull(bot.getQuestRemove(quest));

        bot.updateQuestStatus(mock(QuestStatus.class));
        assertNull(bot.getQuestNoAdd(quest));
    }

    // Ticket 158: a bot that runs quests (BotQuests, -Dslums.botquests.enabled) keeps quest state in memory once
    // BotQuests switched it on for that character - and only then. Roster bots are still never saved to queststatus.
    @Test
    void aQuestingBotKeepsQuestStateOnlyOnceSwitchedOn() throws Exception {
        Character bot = character(BotStore.BOT_ID_BASE + 2);
        java.lang.reflect.Field quests = Character.class.getDeclaredField("quests");
        quests.setAccessible(true);
        quests.set(bot, new java.util.LinkedHashMap<Short, QuestStatus>());
        Quest quest = quest();

        bot.setQuestAdd(quest, (byte) QuestStatus.Status.COMPLETED.getId(), "");
        assertNull(bot.getQuestNoAdd(quest), "not questing: still refused");
        assertFalse(bot.botQuestState());

        bot.enableBotQuestState();
        bot.setQuestAdd(quest, (byte) QuestStatus.Status.COMPLETED.getId(), "");
        assertEquals(QuestStatus.Status.COMPLETED, bot.getQuestNoAdd(quest).getStatus());
        assertEquals(QuestStatus.Status.COMPLETED, bot.getQuestRemove(quest).getStatus());
        assertNull(bot.getQuestNoAdd(quest));
    }

    @Test
    void theGuardDrawsTheBotLineAtTheIdBaseNotAtTheConsoleId() {
        assertTrue(BotHelpers.isArtificial(BotStore.BOT_ID_BASE + 1));
        assertFalse(BotHelpers.isArtificial(BotStore.BOT_ID_BASE));
        assertFalse(BotHelpers.isArtificial(1_000));
        // the console pseudo-character is bot-like (isBot) but not an artificial player, so quest state
        // stays available to it
        assertFalse(BotHelpers.isArtificial(soloMapling.server.SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID));
    }
}
