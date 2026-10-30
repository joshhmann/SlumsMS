package soloMapling.ArtificialPlayer.BotQuestSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 bot audit B5: a Temple of Time bot registered the lane map it hunts on, and nothing cleared that entry when the
 * quest ended or was dropped. It kept counting as busy (and kept other bots off the map) for up to
 * {@link TempleLanes#FORGET_MS} (6 h). The entry now carries its quest and goes the moment that quest ends.
 */
class TempleLaneLifetimeTest {

    static final int BOT = 1_800_000_123, OTHER_BOT = BOT + 1;
    static final int CHANNEL = 1, LANE = 104040000, OTHER_LANE = 104040001;
    static final int QUEST = 3500, OTHER_QUEST = 3501;
    static final long NOW = 1_800_000_000_000L;

    @BeforeEach
    void setUp() {
        TempleLanes.resetForTests();
    }

    @AfterEach
    void tearDown() {
        TempleLanes.resetForTests();
    }

    static BotQuestLog.Active active(int questId, int huntMap) {
        return new BotQuestLog.Active(questId, 1012100, 1012100, 100000000, 100000000, huntMap,
                Map.of(100100, 10), NOW);
    }

    @Test
    void completingTheQuestFreesItsLane() {
        BotQuestLog log = new BotQuestLog(BOT);
        TempleLanes.register(BOT, CHANNEL, LANE, QUEST, NOW);
        log.pick(active(QUEST, LANE));
        assertEquals(1, TempleLanes.hunters(CHANNEL, LANE, 0, NOW), "the lane counts while the quest runs");

        log.complete(true, NOW + 60_000);

        assertEquals(0, TempleLanes.hunters(CHANNEL, LANE, 0, NOW + 60_000),
                "a finished quest must not keep its lane busy");
        assertEquals(-1, TempleLanes.registeredMap(BOT));
    }

    @Test
    void droppingTheQuestFreesItsLane() {
        BotQuestLog log = new BotQuestLog(BOT);
        TempleLanes.register(BOT, CHANNEL, LANE, QUEST, NOW);
        log.pick(active(QUEST, LANE));

        log.drop();

        assertEquals(0, TempleLanes.hunters(CHANNEL, LANE, 0, NOW), "a dropped quest must not keep its lane busy");
        assertEquals(-1, TempleLanes.registeredMap(BOT));
    }

    @Test
    void anotherQuestEndingLeavesTheLaneAlone() {
        BotQuestLog log = new BotQuestLog(BOT);
        TempleLanes.register(BOT, CHANNEL, LANE, QUEST, NOW);
        log.pick(active(OTHER_QUEST, OTHER_LANE));

        log.complete(true, NOW);

        assertEquals(LANE, TempleLanes.registeredMap(BOT), "a different quest's completion must not clear the lane");
    }

    @Test
    void aLaneStaysUntilItsOwnQuestEnds() {
        TempleLanes.register(BOT, CHANNEL, LANE, QUEST, NOW);
        TempleLanes.register(OTHER_BOT, CHANNEL, LANE, QUEST, NOW);
        TempleLanes.unregister(OTHER_BOT, OTHER_QUEST);
        assertEquals(2, TempleLanes.hunters(CHANNEL, LANE, 0, NOW), "clearing another quest id clears nothing");
        TempleLanes.unregister(OTHER_BOT, QUEST);
        assertEquals(1, TempleLanes.hunters(CHANNEL, LANE, 0, NOW), "the matching quest id clears the entry");
        assertTrue(TempleLanes.registeredMap(OTHER_BOT) == -1);
    }
}
