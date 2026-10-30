package scripting;

import client.Character;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner decisions 2026-10-26 for USE_ENABLE_CUSTOM_NPC_SCRIPT (docs/design/custom-npc-review.md): each custom NPC runs
 * in the server's Graal setup against a fake {@code cm}, with the flag ON as it will be in 1.6.0.
 */
class CustomNpcScriptsTest {

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

    private final boolean customWas = YamlConfig.config.server.USE_ENABLE_CUSTOM_NPC_SCRIPT;
    private final boolean starterWas = YamlConfig.config.server.USE_STARTER_MERGE;

    @BeforeEach
    void flagOn() {
        YamlConfig.config.server.USE_ENABLE_CUSTOM_NPC_SCRIPT = true;
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_ENABLE_CUSTOM_NPC_SCRIPT = customWas;
        YamlConfig.config.server.USE_STARTER_MERGE = starterWas;
    }

    @ParameterizedTest
    @ValueSource(strings = {"npc/9000017.js", "npc/9000036.js", "npc/9000040.js", "npc/mapleTV.js",
            "npc/scroll_generator.js", "npc/2082014.js", "npc/9201101.js"})
    void everyChangedScriptEvaluates(String path) {
        assertNotNull(new Loader().load(path), path);
    }

    /** What these scripts touch of NPCConversationManager. */
    public static class FakeCm {
        public final Map<Integer, Integer> inventory = new HashMap<>();
        public int meso;
        public String simple, yesNo, ok, next, getText, text;
        public int numberMax = -1;
        public boolean disposed, defaulted;
        public Integer shopOpened;
        public final FakePlayer player = new FakePlayer();

        public FakePlayer getPlayer() {
            return player;
        }

        public int getNpc() {
            return 1;
        }

        public void sendSimple(String t) {
            simple = t;
        }

        public void sendYesNo(String t) {
            yesNo = t;
        }

        public void sendOk(String t) {
            ok = t;
        }

        public void sendNext(String t) {
            next = t;
        }

        public void sendPrev(String t) {
            ok = t;
        }

        public void sendGetText(String t) {
            getText = t;
        }

        public String getText() {
            return text;
        }

        public void sendGetNumber(String t, int def, int min, int max) {
            numberMax = max;
        }

        public void sendDefault() {
            defaulted = true;
        }

        public void openShopNPC(int id) {
            shopOpened = id;
        }

        public boolean isQuestStarted(int q) {
            return false;
        }

        public void dispose() {
            disposed = true;
        }

        public int getMeso() {
            return meso;
        }

        public void gainMeso(int m) {
            meso += m;
        }

        public String numberWithCommas(int n) {
            return String.format("%,d", n);
        }

        public boolean haveItem(int id) {
            return haveItem(id, 1);
        }

        public boolean haveItem(int id, int q) {
            return inventory.getOrDefault(id, 0) >= q;
        }

        public boolean canHold(int id, int q) {
            return true;
        }

        public void gainItem(int id, int q) {
            inventory.merge(id, q, Integer::sum);
        }

        public int count(int id) {
            return inventory.getOrDefault(id, 0);
        }

        public int prizesLeft = 5;
        public int claims;

        public int getScrollGeneratorPrizesLeftToday() {
            return prizesLeft;
        }

        public boolean claimScrollGeneratorPrize() {
            if (prizesLeft <= 0) {
                return false;
            }
            prizesLeft--;
            claims++;
            return true;
        }
    }

    public static class FakePlayer {
        public boolean meetsReqs = true;
        public List<Object> mergeable = new ArrayList<>(List.of(new Object()));
        public List<String> preview = List.of("2: Junk Sword", "3: Junk Hat");
        public int mergeResult = Character.MERGE_OK;
        public int merges;

        public void setCS(boolean b) {
        }

        public boolean meetsEquipmentMergeRequirements() {
            return meetsReqs;
        }

        public List<Object> getMergeableEquipped() {
            return mergeable;
        }

        public List<String> previewMergeFromName(String name) {
            return "Junk Sword".equals(name) ? preview : null;
        }

        public int mergeItemsFromName(String name) {
            merges++;
            return mergeResult;
        }
    }

    // --- Coco 9000017 ------------------------------------------------------------------------------------------------

    private static final int CHAOS_60 = 2049100;
    private static final int[] COCO_MATS = {4031203, 4001356, 4000136, 4000082, 4001126, 4080100, 4000021, 4003005};

    @Test
    void cocoMakesAtMostFivePerTalk() throws Exception {
        FakeCm cm = new FakeCm();
        cm.meso = 1_000_000_000;
        for (int m : COCO_MATS) {
            cm.inventory.put(m, 100_000);
        }
        Invocable coco = load("npc/9000017.js", cm);
        coco.invokeFunction("start");
        coco.invokeFunction("action", 1, 0, 0);
        coco.invokeFunction("action", 1, 0, 0);
        assertEquals(5, cm.numberMax, "the number box offers 1 to 5");
        coco.invokeFunction("action", 1, 0, 100);   // a forged 100
        coco.invokeFunction("action", 1, 0, 0);
        assertEquals(0, cm.count(CHAOS_60), "100 is refused server-side");
        assertTrue(cm.disposed);

        FakeCm ok = new FakeCm();
        ok.meso = 1_000_000_000;
        for (int m : COCO_MATS) {
            ok.inventory.put(m, 100_000);
        }
        Invocable coco2 = load("npc/9000017.js", ok);
        coco2.invokeFunction("start");
        coco2.invokeFunction("action", 1, 0, 0);
        coco2.invokeFunction("action", 1, 0, 0);
        coco2.invokeFunction("action", 1, 0, 5);
        coco2.invokeFunction("action", 1, 0, 0);
        assertEquals(5, ok.count(CHAOS_60));
        assertEquals(1_000_000_000 - 6_000_000, ok.meso);
    }

    // --- Agent E 9000036 ---------------------------------------------------------------------------------------------

    @Test
    void agentEBeltRollHasAWorkingMenuAndOnlyBelts() throws Exception {
        for (int i = 0; i < 40; i++) {
            FakeCm cm = new FakeCm();
            cm.meso = 100_000;
            cm.inventory.put(4001006, 2);
            cm.inventory.put(4003005, 5);
            cm.inventory.put(4003004, 10);
            Invocable agent = load("npc/9000036.js", cm);
            agent.invokeFunction("start");
            agent.invokeFunction("action", 1, 0, 3);
            assertTrue(cm.simple.contains("#L0#"), cm.simple);
            assertFalse(cm.simple.contains("undefined"), cm.simple);
            agent.invokeFunction("action", 1, 0, 0);
            assertFalse(cm.yesNo.contains("medal"), cm.yesNo);
            agent.invokeFunction("action", 1, 0, 0);
            int belts = 0;
            for (Map.Entry<Integer, Integer> e : cm.inventory.entrySet()) {
                if (e.getValue() > 0 && e.getKey() / 10000 == 114) {
                    throw new AssertionError("a medal came out: " + e.getKey());
                }
                if (e.getValue() > 0 && e.getKey() >= 1132005 && e.getKey() <= 1132016) {
                    belts++;
                }
            }
            assertEquals(1, belts, "one belt from 1132005-1132016");
        }
    }

    @Test
    void agentEStatRingsCostOneToTwoMillion() throws Exception {
        int[] rings = {1112407, 1112408, 1112401, 1112413, 1112414, 1112405, 1112402};
        for (int r = 0; r < rings.length; r++) {
            FakeCm cm = new FakeCm();
            Invocable agent = load("npc/9000036.js", cm);
            agent.invokeFunction("start");
            agent.invokeFunction("action", 1, 0, 4);
            agent.invokeFunction("action", 1, 0, r);
            assertTrue(cm.yesNo.contains("#t" + rings[r] + "#"), cm.yesNo);
            long cost = ((Number) ((ScriptEngine) agent).eval("cost")).longValue();
            assertTrue(cost >= 1_000_000 && cost <= 2_000_000, rings[r] + " costs " + cost);
        }
    }

    @Test
    void agentEFaceAccessoryCostsMatchTheItems() throws Exception {
        FakeCm cm = new FakeCm();
        Invocable agent = load("npc/9000036.js", cm);
        agent.invokeFunction("start");
        agent.invokeFunction("action", 1, 0, 1);
        agent.invokeFunction("action", 1, 0, 9);
        assertEquals(500_000, ((Number) ((ScriptEngine) agent).eval("cost")).intValue());
    }

    // --- Dalair 9000040 ----------------------------------------------------------------------------------------------

    @Test
    void dalairRefusesWhenTheRequirementsAreNotMetEvenWithStarterMerge() throws Exception {
        YamlConfig.config.server.USE_STARTER_MERGE = true;
        FakeCm cm = new FakeCm();
        cm.meso = 1_000_000;
        cm.player.meetsReqs = false;
        Invocable dalair = load("npc/9000040.js", cm);
        dalair.invokeFunction("start");
        assertNotNull(cm.ok);
        assertTrue(cm.ok.contains("Maker level 3") && cm.ok.contains("level 160"), cm.ok);   // ticket 112: Maker 3 again
        assertTrue(cm.disposed);
        assertEquals(0, cm.player.merges);
    }

    @Test
    void dalairListsTheItemsAndMergesOnlyAfterYes() throws Exception {
        FakeCm cm = new FakeCm();
        cm.meso = 1_000_000;
        Invocable dalair = load("npc/9000040.js", cm);
        dalair.invokeFunction("start");
        assertNotNull(cm.next);
        dalair.invokeFunction("action", 1, 0, 0);
        assertNotNull(cm.getText);
        cm.text = "Junk Sword";
        dalair.invokeFunction("action", 1, 0, 0);
        assertNotNull(cm.yesNo, "a confirmation before anything is destroyed");
        assertTrue(cm.yesNo.contains("2: Junk Sword") && cm.yesNo.contains("3: Junk Hat"), cm.yesNo);
        assertEquals(0, cm.player.merges);

        dalair.invokeFunction("action", 1, 1, 0);   // yes
        assertEquals(1, cm.player.merges);
        assertEquals(950_000, cm.meso);
        assertTrue(cm.disposed);
    }

    @Test
    void dalairDoesNothingOnNo() throws Exception {
        FakeCm cm = new FakeCm();
        cm.meso = 1_000_000;
        Invocable dalair = load("npc/9000040.js", cm);
        dalair.invokeFunction("start");
        dalair.invokeFunction("action", 1, 0, 0);
        cm.text = "Junk Sword";
        dalair.invokeFunction("action", 1, 0, 0);
        dalair.invokeFunction("action", 0, 1, 0);   // no
        assertEquals(0, cm.player.merges);
        assertEquals(1_000_000, cm.meso);
        assertTrue(cm.disposed);
    }

    @Test
    void dalairChargesNothingWhenEverythingIsCapped() throws Exception {
        FakeCm cm = new FakeCm();
        cm.meso = 1_000_000;
        cm.player.mergeResult = Character.MERGE_ALL_CAPPED;
        Invocable dalair = load("npc/9000040.js", cm);
        dalair.invokeFunction("start");
        dalair.invokeFunction("action", 1, 0, 0);
        cm.text = "Junk Sword";
        dalair.invokeFunction("action", 1, 0, 0);
        dalair.invokeFunction("action", 1, 1, 0);
        assertEquals(1_000_000, cm.meso);
        assertTrue(cm.ok.contains("3 times"), cm.ok);

        FakeCm capped = new FakeCm();
        capped.meso = 1_000_000;
        capped.player.mergeable = new ArrayList<>();
        Invocable d2 = load("npc/9000040.js", capped);
        d2.invokeFunction("start");
        assertTrue(capped.ok.contains("3 times"), capped.ok);
        assertTrue(capped.disposed);
    }

    // --- Maple TV scroll generator -----------------------------------------------------------------------------------

    @Test
    void scrollGeneratorStopsAfterTheDailyLimit() throws Exception {
        FakeCm cm = new FakeCm();
        cm.prizesLeft = 0;
        Invocable gen = load("npc/scroll_generator.js", cm);
        gen.invokeFunction("start");
        assertNotNull(cm.ok);
        assertTrue(cm.ok.contains("5 prizes"), cm.ok);
        assertTrue(cm.disposed);
    }

    @Test
    void scrollGeneratorRecordsEachPrizeAndRefusesTheSixth() throws Exception {
        FakeCm cm = new FakeCm();
        cm.meso = 100_000_000;
        Invocable gen = load("npc/scroll_generator.js", cm);
        ((ScriptEngine) gen).eval("sgAppliedMeso = 2800000;");
        for (int i = 0; i < 5; i++) {
            assertEquals(Boolean.TRUE, gen.invokeFunction("performExchange", 2040000, 1), "prize " + (i + 1));
        }
        assertEquals(5, cm.claims);
        assertEquals(Boolean.FALSE, gen.invokeFunction("performExchange", 2040000, 1), "the sixth is refused");
        assertEquals(5, cm.count(2040000));
        assertEquals(100_000_000 - 5 * 2_800_000, cm.meso, "the sixth costs nothing");
    }

    @Test
    void scrollGeneratorTakesSuppliesWithoutMesosOrOtherSupplies() throws Exception {
        // Placing only one kind of supply (or no mesos) left -0 amounts, which Graal cannot pass to gainItem/gainMeso:
        // the exchange threw halfway, after the mesos were taken.
        FakeCm cm = new FakeCm();
        cm.inventory.put(4003004, 100);
        Invocable gen = load("npc/scroll_generator.js", cm);
        ((ScriptEngine) gen).eval("sgAppliedItems[0] = 100;");
        assertEquals(Boolean.TRUE, gen.invokeFunction("performExchange", 2040000, 1));
        assertEquals(0, cm.count(4003004));
        assertEquals(1, cm.count(2040000));
    }

    // --- Asia 2082014, T-1337 9201101 --------------------------------------------------------------------------------

    @Test
    void asiaAndT1337StayStockWithTheFlagOn() throws Exception {
        FakeCm asia = new FakeCm();
        Invocable a = load("npc/2082014.js", asia);
        a.invokeFunction("start");
        assertNull(asia.shopOpened);
        assertTrue(asia.defaulted);

        FakeCm t = new FakeCm();
        Invocable t1337 = load("npc/9201101.js", t);
        t1337.invokeFunction("start");
        assertNull(t.shopOpened);
        assertTrue(t.defaulted);
    }
}
