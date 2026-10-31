package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** b248: one greeting per bot and player per gap, and no two bots on a map sending the same text. */
class RepeatGuardTest {

    private static final int MAP = 104_000_000;
    private static final int BOT_A = 1_000_000_001;
    private static final int BOT_B = 1_000_000_002;
    private static final int PLAYER = 42;
    private static final long T0 = 1_760_000_000_000L;

    @BeforeEach
    void setUp() {
        RepeatGuard.forgetForTest();
    }

    @Test
    void aBotGreetsTheSamePlayerOncePerGap() {
        Character bot = chr(BOT_A, MAP);
        Character player = chr(PLAYER, MAP);
        assertFalse(RepeatGuard.greetedRecently(bot, player));

        RepeatGuard.noteGreeting(BOT_A, PLAYER, T0);
        assertTrue(RepeatGuard.greetedRecently(BOT_A, PLAYER, T0));
        assertTrue(RepeatGuard.greetedRecently(BOT_A, PLAYER, T0 + RepeatGuard.GREETING_GAP_MS - 1));
        assertFalse(RepeatGuard.greetedRecently(BOT_A, PLAYER, T0 + RepeatGuard.GREETING_GAP_MS));
    }

    @Test
    void theGateIsPerPairNotPerBot() {
        RepeatGuard.noteGreeting(BOT_A, PLAYER, T0);
        assertTrue(RepeatGuard.greetedRecently(BOT_A, PLAYER, T0 + 1));
        assertFalse(RepeatGuard.greetedRecently(BOT_A, 43, T0 + 1), "another player");
        assertFalse(RepeatGuard.greetedRecently(BOT_B, PLAYER, T0 + 1), "another bot");
        // and the Character entry point stores the same pair
        RepeatGuard.forgetForTest();
        RepeatGuard.noteGreeting(chr(BOT_A, MAP), chr(PLAYER, MAP));
        assertTrue(RepeatGuard.greetedRecently(chr(BOT_A, MAP), chr(PLAYER, MAP)));
    }

    @Test
    void tenMinutesIsShorterThanTheSocialGateGreetCooldown() {
        // the greeting gate must never block a hello SocialGate itself would allow (40 minutes)
        assertTrue(RepeatGuard.GREETING_GAP_MS < soloMapling.ArtificialPlayer.SocialGate.pairMinutes(
                soloMapling.ArtificialPlayer.SocialGate.Act.GREET) * 60_000L);
    }

    @Test
    void twoBotsOnOneMapDoNotSayTheSameLine() {
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, RepeatGuard.normalise("Gz man!"), T0));
        assertFalse(RepeatGuard.claimMapLine(MAP, BOT_B, RepeatGuard.normalise("gz   MAN"), T0 + 1_000));
        assertFalse(RepeatGuard.claimMapLine(MAP, BOT_B, RepeatGuard.normalise("GZ, MAN!!"), T0 + RepeatGuard.MAP_SAME_LINE_MS - 1));
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_B, RepeatGuard.normalise("gz man"), T0 + RepeatGuard.MAP_SAME_LINE_MS));
    }

    @Test
    void theSameBotMayRepeatItself() {
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, RepeatGuard.normalise("lf pq"), T0));
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, RepeatGuard.normalise("lf pq"), T0 + 1_000));
    }

    @Test
    void otherMapsAreUnaffected() {
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, RepeatGuard.normalise("gz"), T0));
        assertTrue(RepeatGuard.claimMapLine(MAP + 1, BOT_B, RepeatGuard.normalise("gz"), T0));
    }

    @Test
    void normaliseIgnoresCaseWhitespaceAndPunctuation() {
        assertEquals("gzman", RepeatGuard.normalise("Gz,  MAN!!"));
        assertEquals("lfpartyquest", RepeatGuard.normalise(" lf  party quest?? "));
        assertEquals("", RepeatGuard.normalise("?! :)"));
    }

    @Test
    void punctuationOnlyLinesAreNeverBlocked() {
        String key = RepeatGuard.normalise(":)");
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, key, T0));
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_B, key, T0));
    }

    @Test
    void interjectionsAreNotCompared() {
        // "lol", "gz", "ya" are how people talk on a map: two bots saying one is not a script
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, RepeatGuard.normalise("lol"), T0));
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_B, RepeatGuard.normalise("LOL!"), T0));
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_A, RepeatGuard.normalise("gz"), T0));
        assertTrue(RepeatGuard.claimMapLine(MAP, BOT_B, RepeatGuard.normalise("gz"), T0));
    }

    private static Character chr(int id, int mapId) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("bot" + id);
        when(c.getMapId()).thenReturn(mapId);
        return c;
    }
}
