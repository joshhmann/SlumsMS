package scripting.portal;

import net.server.channel.handlers.ChangeMapSpecialRefusalAccess;
import org.junit.jupiter.api.Test;
import server.maps.GenericPortal;
import server.maps.Portal;
import soloMapling.WzTestData;

import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 (owner 2026-10-29): pressing Up on the Roof of the Fifth Tower's next00 (script pt_rosegarden) did nothing in a
 * real client and left no trace in the log. Every scripted-portal request now logs "portal script <name> by <chr> on
 * <map>: <ran|refused: reason>", the refusals before the script runs included, and a missing script file says so.
 */
class PortalScriptLoggingTest {

    @Test
    void theLogLineSaysWhoWhereAndWhat() {
        assertEquals("portal script pt_rosegarden by Tester on 211061001: ran",
                GenericPortal.scriptLogLine("pt_rosegarden", "Tester", 211061001, "ran"));
    }

    @Test
    void everyRefusalBeforeTheScriptHasAReason() {
        Portal p = mock(Portal.class);
        assertEquals("refused: no such portal on this map", ChangeMapSpecialRefusalAccess.refusal(null, false, false, false, false));
        assertEquals("refused: portal delay", ChangeMapSpecialRefusalAccess.refusal(p, true, false, false, false));
        assertEquals("refused: portal blocked for this character", ChangeMapSpecialRefusalAccess.refusal(p, false, true, false, false));
        assertEquals("refused: already changing maps", ChangeMapSpecialRefusalAccess.refusal(p, false, false, true, false));
        assertEquals("refused: banned", ChangeMapSpecialRefusalAccess.refusal(p, false, false, false, true));
        assertNull(ChangeMapSpecialRefusalAccess.refusal(p, false, false, false, false));
    }

    @Test
    void aMissingScriptIsReportedNotSilent() {
        Portal p = mock(Portal.class);
        when(p.getScriptName()).thenReturn("no_such_portal_script_qa");
        assertEquals(PortalScriptManager.Outcome.NO_SCRIPT, PortalScriptManager.getInstance().runPortalScript(p, null));
        assertTrue(PortalScriptManager.Outcome.NO_SCRIPT.text.startsWith("refused: "));
    }

    @Test
    void theRoseGardenPortalsNameScriptsTheServerHas() throws Exception {
        WzTestData.assumePresent("Map.wz");
        PortalScriptManager psm = PortalScriptManager.getInstance();
        assertEquals("pt_rosegarden", scriptOf(211061001, "next00"));
        assertEquals("pt_rosegardenout", scriptOf(211080000, "west00"));
        assertTrue(psm.hasPortalScript("pt_rosegarden"));
        assertTrue(psm.hasPortalScript("pt_rosegardenout"));
    }

    private static String scriptOf(int map, String portalName) throws Exception {
        String xml = Files.readString(WzTestData.dir().resolve("Map.wz/Map/Map2/" + map + ".img.xml"));
        Matcher m = Pattern.compile("<imgdir name=\"\\d+\">((?:(?!</imgdir>).)*?name=\"pn\" value=\"" + portalName
                + "\"(?:(?!</imgdir>).)*)</imgdir>", Pattern.DOTALL).matcher(xml);
        assertTrue(m.find(), portalName + " on " + map);
        Matcher s = Pattern.compile("name=\"script\" value=\"([^\"]*)\"").matcher(m.group(1));
        return s.find() ? s.group(1) : null;
    }
}
