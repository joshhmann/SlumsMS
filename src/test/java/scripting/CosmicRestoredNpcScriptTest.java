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
}
