package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BotNoiseFilterTest {

    private static final int PLAYER = 1001;
    private static final int OTHER_PLAYER = 1002;
    private static final int BOT = 9001;

    @BeforeEach
    @AfterEach
    void reset() {
        BotNoiseFilter.resetForTest();
        System.clearProperty("solomapling.quietEngagedSeconds");
    }

    @Test
    void nothingIsHiddenByDefault() {
        assertFalse(BotNoiseFilter.hidesMegaphones(PLAYER));
        assertFalse(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "anyone selling ilbis?", false, 0));
    }

    @Test
    void quietMegaphonesHidesOnlyMegaphonesForThatPlayer() {
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(true, false));
        assertTrue(BotNoiseFilter.hidesMegaphones(PLAYER));
        assertFalse(BotNoiseFilter.hidesMegaphones(OTHER_PLAYER), "another player still hears bot megaphones");
        assertFalse(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "anyone selling ilbis?", false, 0),
                "map chatter is a separate switch");
    }

    @Test
    void quietBotsHidesAmbientChatterOnly() {
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, true));
        assertTrue(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "anyone selling ilbis?", false, 0));
        assertFalse(BotNoiseFilter.hidesMegaphones(PLAYER));
        assertFalse(BotNoiseFilter.hidesBotChat(OTHER_PLAYER, "Mia", BOT, "anyone selling ilbis?", false, 0));
    }

    @Test
    void linesAimedAtTheQuietPlayerStillShow() {
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, true));
        assertFalse(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "hey josh whats up", false, 0),
                "a line naming the player is for them");
        assertFalse(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "lets go to ludi", true, 0),
                "party members are never muted");
    }

    @Test
    void aBotThePlayerTalkedToStaysVisibleForAWhile() {
        System.setProperty("solomapling.quietEngagedSeconds", "60");
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, true));
        BotNoiseFilter.noteEngaged(PLAYER, BOT, 1_000);
        assertFalse(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "sure, invite me", false, 30_000));
        assertTrue(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT + 1, "sure, invite me", false, 30_000),
                "a different bot is still ambient");
        assertTrue(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "sure, invite me", false, 62_000),
                "the window runs out");
    }

    @Test
    void offClearsBoth() {
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(true, true));
        BotNoiseFilter.set(PLAYER, new BotNoiseFilter.Setting(false, false));
        assertFalse(BotNoiseFilter.hidesMegaphones(PLAYER));
        assertFalse(BotNoiseFilter.hidesBotChat(PLAYER, "Josh", BOT, "lol", false, 0));
    }

    @Test
    void shortNamesDoNotMatchEverything() {
        assertFalse(BotNoiseFilter.namesViewer("ok lol", "ok"));
        assertTrue(BotNoiseFilter.namesViewer("gg JOSHUA nice", "joshua"));
    }
}
