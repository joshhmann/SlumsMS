package scripting.portal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.2 b090: the boss portals blamed the quest when the real block was too few rebirths. */
class BossEntryMessageTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakePlayer {
        public int reborns;

        public int getReborns() {
            return reborns;
        }
    }

    public static final class FakePi {
        public final FakePlayer player = new FakePlayer();
        public boolean questDone;
        public String message;
        public Integer warpedTo;

        public FakePlayer getPlayer() {
            return player;
        }

        public boolean isQuestCompleted(int id) {
            return questDone;
        }

        public void playerMessage(int type, String text) {
            message = text;
        }

        public void playPortalSound() {
        }

        public void warp(int map) {
            warpedTo = map;
        }
    }

    private static boolean enter(String boss, FakePi pi) throws Exception {
        ScriptEngine engine = new Loader().load("portal/" + boss + "Enter.js");
        return (Boolean) ((Invocable) engine).invokeFunction("enter", pi);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Magnus", "Lucid", "Will", "Verus", "Darknell"})
    void tooFewRebirthsSaysSoEvenWithTheQuestDone(String boss) throws Exception {
        FakePi pi = new FakePi();
        pi.player.reborns = 2;
        pi.questDone = true;
        assertFalse(enter(boss, pi));
        assertEquals("You need 3 rebirths", pi.message);
        assertNull(pi.warpedTo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Magnus", "Lucid", "Will", "Verus", "Darknell"})
    void theQuestMessageIsKeptWhenTheQuestBlocks(String boss) throws Exception {
        FakePi pi = new FakePi();
        pi.player.reborns = 3;
        assertFalse(enter(boss, pi));
        assertTrue(pi.message.contains("quest"), pi.message);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Magnus", "Lucid", "Will", "Verus", "Darknell"})
    void bothDoneLetsYouIn(String boss) throws Exception {
        FakePi pi = new FakePi();
        pi.player.reborns = 4;
        pi.questDone = true;
        assertTrue(enter(boss, pi));
        assertNull(pi.message);
        assertTrue(pi.warpedTo != null);
    }
}
