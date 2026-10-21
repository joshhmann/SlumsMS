package scripting;

import org.junit.jupiter.api.Test;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.4.1 Cosmic diff (docs/release/cosmic-diff-1.4.1.md): NPC scripts the MapleRoot repack stripped and
 * this release restores from Cosmic. Each runs in the server's own Graal setup against a fake {@code cm}.
 */
class CosmicRestoredNpcScriptTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private static Invocable load(String path, Object cm) {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " exists and evaluates");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    /** The parts of NPCConversationManager Fredrick's script uses. */
    public static final class FredrickCm {
        private final boolean merchantOpen;
        private final boolean itemsWaiting;
        public boolean fredrickShown;
        public String ok;
        public boolean disposed;

        FredrickCm(boolean merchantOpen, boolean itemsWaiting) {
            this.merchantOpen = merchantOpen;
            this.itemsWaiting = itemsWaiting;
        }

        public boolean hasMerchant() {
            return merchantOpen;
        }

        public boolean hasMerchantItems() {
            return itemsWaiting;
        }

        public void showFredrick() {
            fredrickShown = true;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void dispose() {
            disposed = true;
        }
    }

    @Test
    void fredrickOpensTheRetrievalWindowWhenAClosedMerchantLeftItems() throws Exception {
        FredrickCm cm = new FredrickCm(false, true);
        load("npc/9030000.js", cm).invokeFunction("start");

        assertTrue(cm.fredrickShown, "the Store Banker window opens");
        assertNull(cm.ok, "no 'shops are disabled' text any more");
        assertTrue(cm.disposed);
    }

    @Test
    void fredrickWillNotPayOutWhileTheMerchantIsStillOpen() throws Exception {
        FredrickCm cm = new FredrickCm(true, true);
        load("npc/9030000.js", cm).invokeFunction("start");

        assertFalse(cm.fredrickShown);
        assertEquals("You have a Merchant open.", cm.ok);
        assertTrue(cm.disposed);
    }

    @Test
    void fredrickSaysSoWhenNothingIsWaiting() throws Exception {
        FredrickCm cm = new FredrickCm(false, false);
        load("npc/9030000.js", cm).invokeFunction("start");

        assertFalse(cm.fredrickShown);
        assertEquals("You don't have any items or mesos to be retrieved.", cm.ok);
        assertTrue(cm.disposed);
    }

    /** Rosen's script reaches the storage through cm.getPlayer().getStorage(). */
    public static final class FakeStorage {
        public Object client;
        public int npcId = -1;

        public void sendStorage(Object client, int npcId) {
            this.client = client;
            this.npcId = npcId;
        }
    }

    public static final class FakePlayer {
        public final FakeStorage storage = new FakeStorage();

        public FakeStorage getStorage() {
            return storage;
        }
    }

    public static final class StorageCm {
        public final FakePlayer player = new FakePlayer();
        public final Object client = new Object();
        public boolean disposed;

        public FakePlayer getPlayer() {
            return player;
        }

        public Object getClient() {
            return client;
        }

        public void dispose() {
            disposed = true;
        }
    }

    @Test
    void rosenOpensTheMagatiaStorage() throws Exception {
        StorageCm cm = new StorageCm();
        load("npc/2110000.js", cm).invokeFunction("start");

        assertEquals(2110000, cm.player.storage.npcId, "storage opens under Rosen's own NPC id");
        assertSame(cm.client, cm.player.storage.client);
        assertTrue(cm.disposed);
    }
    /** The parts of NPCConversationManager Rydole's weapon-making script uses. */
    public static final class MakerCm {
        public final java.util.Map<Integer, Integer> inventory = new java.util.HashMap<>();
        public int meso;
        public String simple;
        public String yesNo;
        public String ok;
        public boolean disposed;

        public void sendSimple(String text) {
            simple = text;
        }

        public void sendNext(String text) {
            ok = text;
        }

        public void sendYesNo(String text) {
            yesNo = text;
        }

        public void sendOk(String text) {
            ok = text;
        }

        public void dispose() {
            disposed = true;
        }

        public boolean canHold(int itemId, int quantity) {
            return true;
        }

        public int getMeso() {
            return meso;
        }

        public void gainMeso(int delta) {
            meso += delta;
        }

        public boolean haveItem(int itemId) {
            return haveItem(itemId, 1);
        }

        public boolean haveItem(int itemId, int quantity) {
            return inventory.getOrDefault(itemId, 0) >= quantity;
        }

        public void gainItem(int itemId, int quantity) {
            inventory.merge(itemId, quantity, Integer::sum);
        }

        public void gainItem(int itemId, int quantity, boolean randomStats, boolean show) {
            gainItem(itemId, quantity);
        }
    }

    @Test
    void rydoleIsTheLudibriumWeaponMakerAgainNotTheHubMenu() throws Exception {
        MakerCm cm = new MakerCm();
        Invocable rydole = load("npc/2040022.js", cm);
        rydole.invokeFunction("start");

        assertNotNull(cm.simple);
        assertTrue(cm.simple.contains("Create a Warrior weapon"), cm.simple);
        assertFalse(cm.simple.contains("Donor Shop"), "the copied hub menu is gone");
    }

    @Test
    void rydoleMakesAGladiusFromItsManualOresAndFee() throws Exception {
        MakerCm cm = new MakerCm();
        cm.meso = 20000;
        // Gladius (1302008), Cosmic matSet[0]: manual 4131000 x1, 4011001 x2, 4011004 x2, screws 4003000 x30
        cm.inventory.put(4131000, 1);
        cm.inventory.put(4011001, 2);
        cm.inventory.put(4011004, 2);
        cm.inventory.put(4003000, 30);
        Invocable rydole = load("npc/2040022.js", cm);

        rydole.invokeFunction("start");
        rydole.invokeFunction("action", 1, 0, 1); // Create a Warrior weapon
        assertTrue(cm.simple.contains("Gladius"), cm.simple);
        rydole.invokeFunction("action", 1, 0, 0); // Gladius
        assertTrue(cm.yesNo.contains("#t1302008#"), cm.yesNo);
        rydole.invokeFunction("action", 1, 0, 0); // yes

        assertEquals(1, cm.inventory.get(1302008), "the Gladius is made");
        assertEquals(0, cm.inventory.get(4131000), "the manual is used up");
        assertEquals(0, cm.inventory.get(4003000), "the screws are used up");
        assertEquals(2000, cm.meso, "the 18,000 meso fee is paid");
        assertTrue(cm.disposed);
    }

    @Test
    void rydoleRefusesWithoutTheMaterials() throws Exception {
        MakerCm cm = new MakerCm();
        cm.meso = 20000;
        Invocable rydole = load("npc/2040022.js", cm);

        rydole.invokeFunction("start");
        rydole.invokeFunction("action", 1, 0, 1);
        rydole.invokeFunction("action", 1, 0, 0);
        rydole.invokeFunction("action", 1, 0, 0);

        assertNull(cm.inventory.get(1302008));
        assertEquals(20000, cm.meso);
        assertTrue(cm.ok.startsWith("Sorry, but you're missing a required item"), cm.ok);
    }
}
