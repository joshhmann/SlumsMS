package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.awt.Point;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * 1.5.0 rc6 (QA t-we-1): "during weddings bots should prob be talking about the wedding.. all are just talking about
 * random stuff" (the officiant itself said "finally 2nd job" between its lines), and "we-1 mixed sent me just to amoria
 * not the chapel".
 */
class WeddingChatterTest {

    @AfterEach
    void tearDown() {
        BotWeddings.resetForTest();
    }

    @Test
    void theCrowdTalksWeddingAndTheAltarSaysNothingAmbient() {
        assertNull(BotWeddings.overlay("finally 2nd job", true, false, n -> "x"), "the couple and the officiant");
        assertEquals("wait:GuestWait", BotWeddings.overlay("finally 2nd job", false, false, n -> "wait:" + n));
        assertEquals("cheer:Guest", BotWeddings.overlay("selling my old bow", false, true, n -> "cheer:" + n));
        assertEquals("small talk", BotWeddings.overlay("small talk", false, true, n -> null), "no line: as it was");
    }

    @Test
    void withNoWeddingOnALineIsUntouched() {
        Character bot = mock(Character.class);
        when(bot.getMapId()).thenReturn(680000000);
        String line = "finally 2nd job";
        assertSame(line, BotWeddings.ambientLine(bot, line));
    }

    @Test
    void theChapelHasItsAltarStepsAndAFloorForGuests() {
        MapleMap chapel = mock(MapleMap.class);
        when(chapel.getId()).thenReturn(BotWeddings.CHAPEL);
        Point altar = BotWeddings.altar(chapel);
        assertEquals(new Point(200, -123), altar, "on the altar steps (Map.wz 680000110 foothold 13)");
        assertEquals(new Point(110, 209), BotWeddings.guestArea(chapel, altar), "the floor below (foothold 7)");
        MapleMap town = mock(MapleMap.class);
        when(town.getId()).thenReturn(BotWeddings.AMORIA);
        assertEquals(altar, BotWeddings.guestArea(town, altar), "in town the guests stand by the altar");
    }

    @Test
    void theTemplateCanRunInTheChapelAndTheLinesExist() throws Exception {
        String yaml = Files.readString(Path.of("src/main/java/soloMapling/worldevents/WorldEvents.yaml"));
        assertTrue(yaml.contains("maps: [680000000, 680000110]"));
        String lines = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/CoupleDialogue.yaml"));
        assertTrue(lines.contains("  GuestWait:"));
        String speak = Files.readString(Path.of("src/main/java/soloMapling/ArtificialPlayer/BotCommandsPack/SocialCommands.java"));
        assertTrue(speak.contains("BotWeddings.ambientLine(bot, message)"), "every ambient line goes through it");
    }
}
