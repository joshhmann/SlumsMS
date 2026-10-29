package scripting;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import soloMapling.menus.TutorialIslandMenu;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1: Shanks' trip is free for a player who came through "Start on Maple Island?"; the level 7 rule stays. */
class ShanksFreeTripTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static class Cm {
        public final Character chr;
        public int meso;
        public int level = 7;
        public boolean letter;
        public final List<String> said = new ArrayList<>();
        public int warpedTo = -1;
        public boolean disposed;

        Cm(Character chr) {
            this.chr = chr;
        }

        public Character getPlayer() { return chr; }
        public boolean haveItem(int id) { return letter && id == 4031801; }
        public int getLevel() { return level; }
        public int getMeso() { return meso; }
        public void gainMeso(int n) { meso += n; }
        public void gainItem(int id, int n) { }
        public void warp(int map, int portal) { warpedTo = map; }
        public void sendYesNo(String t) { said.add(t); }
        public void sendNext(String t) { said.add(t); }
        public void sendNextPrev(String t) { said.add(t); }
        public void sendOk(String t) { said.add(t); }
        public void dispose() { disposed = true; }
    }

    private static Character newbie(boolean free) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(7001);
        if (free) {
            chr.getAreaInfos().put(TutorialIslandMenu.TRIP_AREA, "freetrip=1.6.1");
        }
        return chr;
    }

    private static Invocable run(Cm cm) throws Exception {
        ScriptEngine engine = new Loader().load("npc/22000.js");
        assertNotNull(engine);
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        for (int i = 0; i < 3 && !cm.disposed; i++) {
            npc.invokeFunction("action", 1, 0, 0);
        }
        return npc;
    }

    @Test
    void anIslandStarterSailsFreeWithNoMesos() throws Exception {
        Cm cm = new Cm(newbie(true));
        run(cm);
        assertEquals(104000000, cm.warpedTo);
        assertEquals(0, cm.meso, "nothing was charged");
        assertTrue(cm.said.get(0).contains("for free"), cm.said.get(0));
        assertFalse(TutorialIslandMenu.freeTrip(cm.chr), "the free trip is used up");
    }

    @Test
    void theLevelRuleStillApplies() throws Exception {
        Cm cm = new Cm(newbie(true));
        cm.level = 6;
        run(cm);
        assertEquals(-1, cm.warpedTo);
        assertTrue(cm.said.get(cm.said.size() - 1).contains("Level 7"), cm.said.toString());
        assertTrue(TutorialIslandMenu.freeTrip(cm.chr), "not spent without sailing");
    }

    @Test
    void everyoneElseStillPays150() throws Exception {
        Cm poor = new Cm(newbie(false));
        poor.meso = 149;
        run(poor);
        assertEquals(-1, poor.warpedTo);

        Cm rich = new Cm(newbie(false));
        rich.meso = 200;
        run(rich);
        assertEquals(104000000, rich.warpedTo);
        assertEquals(50, rich.meso);
    }
}
