package soloMapling.ArtificialPlayer.BotGuildSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.0 tune, TEST social trace 2026-10-26: "SlumLords needs to chill" three times in 25 minutes, twice by one bot
 * 13 s apart. No jab text twice on a map and channel within 30 minutes, and one jab per bot per 10 minutes.
 */
class GuildJabRepeatTest {

    @AfterEach
    void reset() {
        BotGuilds.clearJabsForTest();
    }

    @Test
    void theSameJabIsNotSaidTwiceOnAMapWithinHalfAnHour() {
        long t = 1_000_000_000L;
        assertTrue(BotGuilds.jabFresh(1, 100000000, "SlumLords needs to chill", t));
        BotGuilds.noteJab(7, 1, 100000000, "SlumLords needs to chill", t);
        assertFalse(BotGuilds.jabFresh(1, 100000000, "SlumLords needs to chill", t + 13_000), "13 s later, same bot");
        assertFalse(BotGuilds.jabFresh(1, 100000000, "slumlords needs to chill", t + 25 * 60_000L), "anyone, 25 min");
        assertTrue(BotGuilds.jabFresh(2, 100000000, "SlumLords needs to chill", t + 60_000), "another channel");
        assertTrue(BotGuilds.jabFresh(1, 103000000, "SlumLords needs to chill", t + 60_000), "another map");
        assertTrue(BotGuilds.jabFresh(1, 100000000, "SlumLords trying so hard lol", t + 60_000), "another jab");
        assertTrue(BotGuilds.jabFresh(1, 100000000, "SlumLords needs to chill", t + 30 * 60_000L), "after 30 min");
    }

    @Test
    void aBotJabsAtMostOnceInTenMinutes() {
        long t = 2_000_000_000L;
        assertTrue(BotGuilds.jabCooled(7, t));
        BotGuilds.noteJab(7, 1, 100000000, "SlumLords needs to chill", t);
        assertFalse(BotGuilds.jabCooled(7, t + 13_000));
        assertTrue(BotGuilds.jabCooled(8, t + 13_000), "another bot may");
        assertTrue(BotGuilds.jabCooled(7, t + 10 * 60_000L));
    }

    @Test
    void thePickSkipsALineSaidLately() {
        Map<String, String> tokens = Map.of("RIVAL", "SlumLords", "GUILD", "Legendz", "MEMBER", "Bob");
        String any = GuildLines.pick("CasualJab", tokens);
        if (any == null) {
            return; // the dialogue pack is not readable here
        }
        for (int i = 0; i < 20; i++) {
            assertEquals("SlumLords trying so hard lol", GuildLines.pick("CasualJab", tokens,
                    line -> !line.equals("SlumLords needs to chill")));
        }
        assertNull(GuildLines.pick("CasualJab", tokens, line -> false));
    }
}
