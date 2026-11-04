package scripting;

import client.Client;
import client.Character;
import client.inventory.Item;
import client.inventory.Pet;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 1.6.4: the glove maker's stimulator branch (scripts/npc/2040020.js:257-266) took the materials, the fee and the
 * stimulator and then called {@code cm.gainRandomItem(newItem)}, a method no scripting manager defines and a variable
 * the script never declares. The run died right there, so a player who paid for a stimulator lost everything and got
 * nothing back, while the same branch without a stimulator handed the glove over normally.
 *
 * <p>These runs drive the real script against the real {@link NPCConversationManager} - only its inventory, meso and
 * dialog calls are stubbed - so a method the server does not define is exactly the TypeError the script used to hit.
 */
class GloveCraftStimulatorTest {
    private static final int STEEL_MISSEL = 1082005; // warrior glove, the stimulator option's item 0
    private static final int GLOVE_MATERIAL = 1082007;
    private static final int STEEL_PLATE = 4011001;
    private static final int STIMULATOR = 4130000;
    private static final int COST = 18000;
    private static final int STARTING_MESO = 50000;

    /** The manager as the script sees it: inventory, meso and dialog stubbed, every other method the real one. */
    private static final class GloveCm extends NPCConversationManager {
        final Map<Integer, Integer> items = new LinkedHashMap<>();
        final Map<Integer, Integer> randomStatsGains = new LinkedHashMap<>();
        final StringBuilder dialog = new StringBuilder();
        final Character player = mock(Character.class);
        int meso = STARTING_MESO;
        boolean disposed;

        GloveCm() {
            super(mock(Client.class), 2040020, "2040020");
            items.put(GLOVE_MATERIAL, 1);
            items.put(STEEL_PLATE, 1);
            items.put(STIMULATOR, 1);
        }

        @Override
        public Character getPlayer() {
            return player;
        }

        @Override
        public boolean haveItem(int itemid, int quantity) {
            return items.getOrDefault(itemid, 0) >= quantity;
        }

        @Override
        public boolean canHold(int itemid, int quantity) {
            return true;
        }

        @Override
        public Item gainItem(int id, short quantity, boolean randomStats, boolean showMessage, long expires, Pet from) {
            int now = items.getOrDefault(id, 0) + quantity;
            if (now <= 0) {
                items.remove(id);
            } else {
                items.put(id, now);
            }
            if (randomStats && quantity > 0) { // the stimulator roll the script asks for
                randomStatsGains.put(id, randomStatsGains.getOrDefault(id, 0) + quantity);
            }
            return null;
        }

        @Override
        public int getMeso() {
            return meso;
        }

        @Override
        public void gainMeso(int gain) {
            meso += gain;
        }

        @Override
        public void gainMeso(int gain, boolean show) {
            meso += gain;
        }

        @Override
        public void sendSimple(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void sendYesNo(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void sendOk(String text) {
            dialog.append(text).append('\n');
        }

        @Override
        public void dispose() {
            disposed = true;
        }
    }

    /** {@code Math.random()} pinned so the 1-in-10 destroy roll can be driven both ways. */
    public static final class PinnedMath {
        private final double value;

        PinnedMath(double value) {
            this.value = value;
        }

        public double random() {
            return value;
        }

        public int floor(double d) {
            return (int) Math.floor(d);
        }
    }

    private static Invocable loadGloveMaker(GloveCm cm, double roll) {
        ScriptEngine engine = new Loader().load("npc/2040020.js");
        assertNotNull(engine, "scripts/npc/2040020.js did not evaluate");
        engine.put("cm", cm);
        engine.put("Math", new PinnedMath(roll));
        return (Invocable) engine;
    }

    /** Walk the menu: main menu -> "Warrior glove with a Stimulator" -> Steel Missel -> accept. */
    private static void craftWithStimulator(Invocable script) throws Exception {
        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 5);
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 0, 0);
    }

    @Test
    void stimulatorSuccessGivesTheCraftedGlove() throws Exception {
        GloveCm cm = new GloveCm();
        craftWithStimulator(loadGloveMaker(cm, 0.15)); // floor(1.5) == 1: not the destroy roll

        assertEquals(1, cm.items.getOrDefault(STEEL_MISSEL, 0), "the glove the player paid for");
        assertFalse(cm.items.containsKey(GLOVE_MATERIAL), "glove material taken");
        assertFalse(cm.items.containsKey(STEEL_PLATE), "steel plate taken");
        assertFalse(cm.items.containsKey(STIMULATOR), "stimulator taken");
        assertEquals(STARTING_MESO - COST, cm.meso);
        assertTrue(cm.disposed);
        assertTrue(cm.dialog.toString().contains("the gloves are ready"), cm.dialog.toString());
    }

    @Test
    void stimulatorDestroyedTellsThePlayerInsteadOfThrowing() throws Exception {
        GloveCm cm = new GloveCm();
        craftWithStimulator(loadGloveMaker(cm, 0.05)); // floor(0.5) == 0: the 1-in-10 the script warns about

        assertFalse(cm.items.containsKey(STEEL_MISSEL), "the roll is supposed to eat the glove");
        assertTrue(cm.dialog.toString().contains("unusable"), cm.dialog.toString());
        assertTrue(cm.disposed);
    }

    /**
     * The defect: paying for a stimulator used to leave the player with fewer items and less meso and no result item
     * and no word about it. Either the glove arrives, or the player is told in plain words that the stimulator ate it.
     */
    @Test
    void aStimulatorCraftNeverJustKeepsThePlayersPayment() throws Exception {
        for (double roll : new double[] {0.0, 0.05, 0.99, 0.15}) {
            GloveCm cm = new GloveCm();
            int mesoBefore = cm.meso;
            int matsBefore = cm.items.getOrDefault(GLOVE_MATERIAL, 0) + cm.items.getOrDefault(STEEL_PLATE, 0);

            craftWithStimulator(loadGloveMaker(cm, roll));

            boolean paid = cm.items.getOrDefault(GLOVE_MATERIAL, 0) + cm.items.getOrDefault(STEEL_PLATE, 0) < matsBefore
                    || cm.meso < mesoBefore;
            boolean gotGlove = cm.items.getOrDefault(STEEL_MISSEL, 0) > 0;
            boolean toldWhy = cm.dialog.toString().contains("unusable");
            assertFalse(paid && !gotGlove && !toldWhy,
                    "roll " + roll + ": paid " + matsBefore + " mats and " + (mesoBefore - cm.meso) + " meso, got nothing, said nothing");
            assertTrue(gotGlove || toldWhy, "roll " + roll + ": no glove and no explanation");
        }
    }

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
