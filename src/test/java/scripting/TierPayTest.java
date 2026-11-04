package scripting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.mechanics.TierScaling;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A tier changes how <em>much</em> a party quest pays, and nothing else: the item ids are the same at every level, the
 * clear-stage EXP is identical, and with {@code -Dslums.mechanics.tiers} off every script pays exactly what it always
 * paid however the instance was started.
 *
 * <p>The scripts read the difficulty from the instance ("level", which {@code setup(level, lobbyid)} stores from
 * {@code startInstance}'s third argument) and clamp it to the two levels they define, so this test drives the scripts'
 * own {@code setEventRewards} with a fake instance rather than re-deriving what it should answer. EXP equality is the
 * load-bearing assertion: "harder pays more exp" is the hidden bonus the owner rejected, and it would be invisible in a
 * diff that only looked at item quantities.
 */
class TierPayTest {

    private static final List<String> SCRIPTS = List.of("event/KerningPQ.js", "event/HenesysPQ.js",
            "event/LudiPQ.js", "event/OrbisPQ.js");

    /** Loads a script file through the server's own Graal setup (the same one that runs it live). */
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** What {@code setEventRewards} touches on a live instance: the level property and the two setters. */
    public static final class FakeEim {
        private final int level;
        int eventLevel = -1;
        List<Object> ids;
        List<Object> qtys;
        List<Object> exp;

        FakeEim(int level) {
            this.level = level;
        }

        public int getIntProperty(String name) {
            return "level".equals(name) ? level : 0;
        }

        public void setEventRewards(int eventLevel, List<Object> rwds, List<Object> qtys) {
            this.eventLevel = eventLevel;
            // copied out: Graal hands the script's JS arrays over as PolyglotList, whose equals is identity, so comparing
            // two runs would compare two wrappers rather than two reward tables
            this.ids = new ArrayList<>(rwds);
            this.qtys = new ArrayList<>(qtys);
        }

        public void setEventClearStageExp(List<Object> gain) {
            this.exp = new ArrayList<>(gain);
        }
    }

    /** The event manager's whole job here: answer the script's own name, which is how it finds its row. */
    public static final class FakeEm {
        private final String name;

        FakeEm(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }
    }

    @AfterEach
    void switchOff() {
        System.clearProperty(TierScaling.FLAG);
        System.clearProperty(TierScaling.TIER_PROPERTY);
    }

    private static FakeEim rewards(String script, int level) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        FakeEim eim = new FakeEim(level);
        ((Invocable) engine).invokeFunction("setEventRewards", eim);
        assertTrue(eim.eventLevel > 0, script + ": setEventRewards was never called");
        assertNotNull(eim.exp, script + ": setEventClearStageExp was never called, so the EXP could not be compared");
        return eim;
    }

    @Test
    void withTiersOffEveryScriptPaysLevelOneHoweverItWasStarted() throws Exception {
        for (String script : SCRIPTS) {
            FakeEim one = rewards(script, 1);
            FakeEim two = rewards(script, 2);
            assertEquals(1, one.eventLevel, script + ": level 1 is level 1");
            assertEquals(1, two.eventLevel, script + ": tiers off, so a level-2 start still pays level 1");
            assertEquals(one.qtys, two.qtys, script + ": and the same quantities");
        }
    }

    @Test
    void aRowThatNamesHardPaysHardEvenWhenTheInstanceWasStartedAtOne() throws Exception {
        // the bot-led path: the party quest's own NPC script calls startInstance(..., 1), so the instance says level 1.
        // The row is what makes the run HARD, and the script has to see it or a lobby HARD run would pay NORMAL.
        System.setProperty(TierScaling.FLAG, "true");
        System.setProperty(TierScaling.TIER_PROPERTY, "HARD");
        for (String script : SCRIPTS) {
            String event = script.substring(script.lastIndexOf('/') + 1, script.length() - 3);
            ScriptEngine engine = new Loader().load(script);
            engine.put("em", new FakeEm(event));
            FakeEim eim = new FakeEim(1);   // started at the stock difficulty
            ((Invocable) engine).invokeFunction("setEventRewards", eim);
            assertEquals(2, eim.eventLevel, event + ": the row names HARD, so the level-2 block pays");
        }
    }

    @Test
    void withTheSwitchOnButNoTierAskedForEveryRowIsStillLevelOne() throws Exception {
        System.setProperty(TierScaling.FLAG, "true");
        for (String script : SCRIPTS) {
            String event = script.substring(script.lastIndexOf('/') + 1, script.length() - 3);
            ScriptEngine engine = new Loader().load(script);
            engine.put("em", new FakeEm(event));
            FakeEim eim = new FakeEim(1);
            ((Invocable) engine).invokeFunction("setEventRewards", eim);
            assertEquals(1, eim.eventLevel, event + ": -Dslums.mechanics.tier was not set, so the run is NORMAL");
        }
    }

    @Test
    void aHardRunPaysTheSameIdsAndTwiceTheQuantity() throws Exception {
        System.setProperty(TierScaling.FLAG, "true");
        for (String script : SCRIPTS) {
            FakeEim normal = rewards(script, 1);
            FakeEim hard = rewards(script, 2);
            assertEquals(1, normal.eventLevel, script + ": a level-1 start is NORMAL");
            assertEquals(2, hard.eventLevel, script + ": a level-2 start is HARD");
            assertEquals(normal.ids, hard.ids, script + ": the item ids must not change with the tier");
            assertEquals(normal.qtys.size(), hard.qtys.size(), script + ": one quantity per item");
            boolean moved = false;
            for (int i = 0; i < normal.qtys.size(); i++) {
                int n = ((Number) normal.qtys.get(i)).intValue();
                int h = ((Number) hard.qtys.get(i)).intValue();
                assertEquals(n * 2, h, script + ": item " + normal.ids.get(i) + " pays twice as much at HARD");
                moved |= h > n;
            }
            assertTrue(moved, script + ": a HARD run must actually pay more of something");
        }
    }

    @Test
    void expIsIdenticalAtBothLevels() throws Exception {
        System.setProperty(TierScaling.FLAG, "true");
        for (String script : SCRIPTS) {
            FakeEim normal = rewards(script, 1);
            FakeEim hard = rewards(script, 2);
            assertEquals(normal.exp, hard.exp, script + ": EXP is the same at HARD - a tier may move quantities only");
        }
    }

    @Test
    void aLevelTheScriptDoesNotDefineIsClampedToHard() throws Exception {
        System.setProperty(TierScaling.FLAG, "true");
        for (String script : SCRIPTS) {
            FakeEim master = rewards(script, 4);
            assertEquals(2, master.eventLevel,
                    script + ": the scripts define level 1 and 2 only, so anything above HARD pays the HARD block");
        }
    }
}
