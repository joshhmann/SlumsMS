package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 (ticket 102): a rebirth takes exactly one key item, after the checks, when it happens. */
class RebirthKeyItemTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class Chr {
        public int reborns;
        public int level = 200;
        public int rebornsDone;

        public int getReborns() {
            return reborns;
        }

        public int getLevel() {
            return level;
        }

        public void executeRebornAsId(int job) {
            reborns++;
            rebornsDone++;
        }

        public void setPlayerExpRatesCerezeth(Object rate) {
        }
    }

    public static final class Cm extends MapleRootBrokenNpcScriptTest.FakeCm {
        public final Chr chr = new Chr();

        public int getJobId() {
            return 112;
        }

        public Chr getChar() {
            return chr;
        }

        public Chr getPlayer() {
            return chr;
        }

        public boolean hasItem(int id, int q) {
            return haveItem(id, q);
        }

        public void gainItem(int id, int q) {
            gainItem(id, q, 0);
        }

        private void gainItem(int id, int q, int unused) {
            inventory.merge(id, q, Integer::sum);
        }

        public void resetStats() {
        }

        public String letters(String s) {
            return s;
        }
    }

    private static Cm rebirth(Cm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/rebirth.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");                 // status 0
        npc.invokeFunction("action", 1, 0, 0);       // 1: menu
        npc.invokeFunction("action", 1, 0, 0);       // 2: pick "reborn"
        npc.invokeFunction("action", 1, 0, 0);       // 3: pick Explorer
        npc.invokeFunction("action", 1, 1, 0);       // 4: yes
        return cm;
    }

    @Test
    void eachRebirthTakesExactlyOneOfItsKeyItem() throws Exception {
        int[] keys = {4033446, 4033442, 4033450};
        for (int n = 0; n < 3; n++) {
            Cm cm = new Cm();
            cm.chr.reborns = n;
            cm.inventory.put(keys[n], 2);
            rebirth(cm);
            assertEquals(1, cm.chr.rebornsDone, "rebirth " + n);
            assertEquals(1, cm.count(keys[n]), "one key item taken, not two or none");
        }
    }

    @Test
    void withoutTheKeyItemNothingHappens() throws Exception {
        Cm cm = new Cm();
        rebirth(cm);
        assertEquals(0, cm.chr.rebornsDone);
        assertEquals(0, cm.count(4033446));
    }

    @Test
    void theKeyItemIsNotTakenWhenTheLevelCheckFails() throws Exception {
        Cm cm = new Cm();
        cm.inventory.put(4033446, 1);
        cm.chr.level = 150;
        rebirth(cm);
        assertEquals(0, cm.chr.rebornsDone);
        assertEquals(1, cm.count(4033446));
    }
}
