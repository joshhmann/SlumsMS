package client;

import org.junit.jupiter.api.Test;
import server.quest.Quest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic 87fe36eda): a NOT_STARTED status that getQuest() added on a lookup is
 * not written to queststatus on save. Anything that carries state still is.
 */
class QuestPlaceholderSaveTest {

    private static QuestStatus status(QuestStatus.Status s) {
        Quest quest = mock(Quest.class);
        when(quest.getId()).thenReturn((short) 2000);
        return new QuestStatus(quest, s);
    }

    @Test
    void aLookedUpQuestIsAPlaceholder() {
        assertTrue(Character.isEmptyQuestPlaceholder(status(QuestStatus.Status.NOT_STARTED)));
    }

    @Test
    void aCompletedQuestIsKept() {
        assertFalse(Character.isEmptyQuestPlaceholder(status(QuestStatus.Status.COMPLETED)));
    }

    @Test
    void aForfeitedQuestIsKept() {
        QuestStatus qs = status(QuestStatus.Status.NOT_STARTED);
        qs.setForfeited(1);
        assertFalse(Character.isEmptyQuestPlaceholder(qs));
    }

    @Test
    void aRepeatedQuestIsKept() {
        QuestStatus qs = status(QuestStatus.Status.NOT_STARTED);
        qs.setCompleted(3);
        assertFalse(Character.isEmptyQuestPlaceholder(qs));
    }

    @Test
    void aQuestWithProgressIsKept() {
        QuestStatus qs = status(QuestStatus.Status.NOT_STARTED);
        qs.setProgress(0, "1");
        assertFalse(Character.isEmptyQuestPlaceholder(qs));
    }

    @Test
    void aQuestWithMedalMapsIsKept() {
        QuestStatus qs = status(QuestStatus.Status.NOT_STARTED);
        qs.addMedalMap(100000000);
        assertFalse(Character.isEmptyQuestPlaceholder(qs));
    }

    @Test
    void aQuestWithAnExpiryIsKept() {
        QuestStatus qs = status(QuestStatus.Status.NOT_STARTED);
        qs.setExpirationTime(System.currentTimeMillis() + 60_000);
        assertFalse(Character.isEmptyQuestPlaceholder(qs));
    }
}
