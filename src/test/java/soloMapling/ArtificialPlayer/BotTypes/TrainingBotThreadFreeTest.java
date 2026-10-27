package soloMapling.ArtificialPlayer.BotTypes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 (TEST social trace 2026-10-27): a training bot resting in town can talk in a bot-to-bot thread. Before, only
 * town bots could (a training bot's isAvailableForAmbientActions is always false), so 15 of 15 threads were between
 * bots that cannot take a PQ trip, follow a tip or sell from a bag.
 */
class TrainingBotThreadFreeTest {

    @Test
    void restingInTownOrAtAHangOutIsFree() {
        assertTrue(TrainingBot.freeForThread(TrainingBot.Phase.IN_TOWN, false, false, false, false));
        assertTrue(TrainingBot.freeForThread(TrainingBot.Phase.LIFE_DWELL, false, false, false, false));
    }

    @Test
    void errandsTripsGrindingAndPlayersAreNot() {
        assertFalse(TrainingBot.freeForThread(TrainingBot.Phase.IN_TOWN, true, false, false, false), "shop walk");
        for (TrainingBot.Phase p : new TrainingBot.Phase[]{TrainingBot.Phase.GRIND, TrainingBot.Phase.GO_TOWN,
                TrainingBot.Phase.SHOP_TRAVEL, TrainingBot.Phase.SHOP_DWELL, TrainingBot.Phase.GO_TRAIN,
                TrainingBot.Phase.LIFE_TRAVEL, TrainingBot.Phase.DECIDE, TrainingBot.Phase.BREAK_REST}) {
            assertFalse(TrainingBot.freeForThread(p, false, false, false, false), p.name());
        }
        assertFalse(TrainingBot.freeForThread(TrainingBot.Phase.IN_TOWN, false, true, false, false), "starting a trip");
        assertFalse(TrainingBot.freeForThread(TrainingBot.Phase.IN_TOWN, false, false, true, false), "event or sauna");
        assertFalse(TrainingBot.freeForThread(TrainingBot.Phase.LIFE_DWELL, false, false, false, true), "recruiting");
    }
}
