package scripting;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1 (owner 2026-10-29): the Roof of the Fifth Tower (211061001) and the Gardener's Spot (211080000) carry
 * qrLimit = 3141 in their map data, a quest-record gate the client may check before it uses their script portals
 * (next00 / west00). The server now sets record 3141 when a player enters either map; a record already set is left.
 */
class RoseGardenQuestRecordTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMs {
        final int status;
        public final List<Integer> started = new ArrayList<>();

        FakeMs(int status) {
            this.status = status;
        }

        public int getQuestStatus(int id) {
            return id == 3141 ? status : 0;
        }

        public boolean forceStartQuest(int id) {
            started.add(id);
            return true;
        }
    }

    private static FakeMs enter(String script, int status) throws Exception {
        ScriptEngine engine = new Loader().load("map/onUserEnter/" + script + ".js");
        assertNotNull(engine, script);
        FakeMs ms = new FakeMs(status);
        ((Invocable) engine).invokeFunction("start", ms);
        return ms;
    }

    @Test
    void enteringTheRoofOrTheGardenSetsRecord3141Once() throws Exception {
        for (String script : List.of("211061001", "enter_211080000")) {
            assertEquals(List.of(3141), enter(script, 0).started, script + " sets the record");
            assertEquals(List.of(), enter(script, 1).started, script + " leaves a record already there");
        }
    }

    @Test
    void theMapsAskForRecord3141AndRunTheseScripts() throws Exception {
        WzTestData.assumePresent("Map.wz");
        String roof = Files.readString(WzTestData.dir().resolve("Map.wz/Map/Map2/211061001.img.xml"));
        String garden = Files.readString(WzTestData.dir().resolve("Map.wz/Map/Map2/211080000.img.xml"));
        assertTrue(roof.contains("name=\"qrLimit\" value=\"3141\""));
        assertTrue(garden.contains("name=\"qrLimit\" value=\"3141\""));
        // an empty onUserEnter makes MapFactory use the map id as the script name
        assertTrue(roof.contains("name=\"onUserEnter\" value=\"\""));
        assertTrue(garden.contains("name=\"onUserEnter\" value=\"enter_211080000\""));
    }
}
