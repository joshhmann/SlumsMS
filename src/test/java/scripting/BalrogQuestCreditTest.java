package scripting;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quest 2239 "Balrog and the Seal" asks for one kill of mob 9101003, the quest-only "Lord Balrog" id (it is not in
 * Mob.wz). The Balrog fights credit it when the last unsealed Balrog part dies. The normal fight always did; the easy
 * fight (3+ players instead of 6+) did not, so a small party could clear Balrog and still not finish the quest.
 */
class BalrogQuestCreditTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeMob {
        private final int id;

        FakeMob(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
    }

    /** The parts of EventInstanceManager monsterKilled() uses on the clearing kill. */
    public static final class FakeEim {
        public final List<Integer> credited = new ArrayList<>();

        public int getIntProperty(String key) {
            return 2;   // two parts already down: this kill clears the fight
        }

        public void showClearEffect() {
        }

        public void clearPQ() {
        }

        public void dispatchRaiseQuestMobCount(int mobId, int mapId) {
            credited.add(mobId);
        }

        public void schedule(String method, long delay) {
        }
    }

    @ParameterizedTest(name = "{0} credits the Lord Balrog quest mob")
    @CsvSource({"event/BalrogBattle.js, 8830001", "event/BalrogBattle_Easy.js, 8830008"})
    void clearingKillCreditsQuest2239(String script, int unsealedPart) throws Exception {
        ScriptEngine engine = new Loader().load(script);
        assertNotNull(engine, script + " evaluates");
        FakeEim eim = new FakeEim();
        ((Invocable) engine).invokeFunction("monsterKilled", new FakeMob(unsealedPart), eim);

        assertTrue(eim.credited.contains(9101003), script + " credits 9101003, got " + eim.credited);
    }
}
