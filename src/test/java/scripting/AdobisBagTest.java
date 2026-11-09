package scripting;

import client.Character;
import client.inventory.InventoryType;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 bagrewards (card zak-eyes-nocanhold): Adobis (scripts/npc/2030008.js) took the two trial documents and 30
 * Fire Ore teeth, recorded quest 100201 and only then handed out 5 Eyes of Fire with {@code cm.gainItem}. A full Etc
 * tab lost the Eyes after the materials were already gone, and the teeth are about 1500 kills to replace.
 *
 * <p>Now the Eyes are measured first, against the bag as the same talk's three takes will leave it (an Etc stack the
 * takes empty pays for the Eyes; a stack with something left pays for nothing), and nothing is taken when they do not
 * fit. A player whose bag has room sees no change at all.
 */
class AdobisBagTest {

    private static final int TRIAL_ONE = 4031061;
    private static final int TRIAL_TWO = 4031062;
    private static final int TEETH = 4000082;
    private static final int EYES = 4001017;

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeEm {
        public String getProperty(String key) {
            return "";
        }
    }

    public static final class AdobisCm {
        public Character player;
        public final List<String> oks = new ArrayList<>();
        public final List<String> nexts = new ArrayList<>();
        public final List<Integer> completed = new ArrayList<>();
        public boolean disposed;
        private final AbstractPlayerInteraction api;

        AdobisCm(BossRewardBagTestSupport.Member member) {
            this.player = member.chr;
            this.api = member.api;
        }

        public Character getPlayer() {
            return player;
        }

        public boolean haveItem(int itemId) {
            return api.haveItem(itemId);
        }

        public boolean haveItem(int itemId, int quantity) {
            return api.haveItem(itemId, quantity);
        }

        public boolean isQuestStarted(int questId) {
            return questId == 100200;
        }

        public boolean isQuestCompleted(int questId) {
            return false;
        }

        public FakeEm getEventManager(String name) {
            return new FakeEm();
        }

        public void sendSimple(String text) {
        }

        public void sendOk(String text) {
            oks.add(text);
        }

        public void sendNext(String text) {
            nexts.add(text);
        }

        public void completeQuest(int questId) {
            completed.add(questId);
        }

        public void gainItem(int itemId, short quantity) {
            api.gainItem(itemId, quantity);
        }

        public void dispose() {
            disposed = true;
        }
    }

    private MockedStatic<ItemInformationProvider> itemData;
    private final World world = mock(World.class);

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        BossRewardBagTestSupport.loadItemProviderClass();
    }

    @BeforeEach
    void setUp() {
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, Map.of());
    }

    @AfterEach
    void tearDown() {
        itemData.close();
    }

    /** Adobis offers the three stages; the player picks "Forging the Eyes of Fire". */
    private AdobisCm forge(BossRewardBagTestSupport.Member member) throws Exception {
        AdobisCm cm = new AdobisCm(member);
        ScriptEngine engine = new Loader().load("npc/2030008.js");
        assertNotNull(engine, "scripts/npc/2030008.js evaluates");
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");              // status 0: the stage list
        npc.invokeFunction("action", 1, 0, 2);   // status 1, selection 2: forge the Eyes
        return cm;
    }

    /** A full Etc tab holding the two trial documents, the teeth, and {@code fillers} junk slots. */
    private static void fullEtcWithMaterials(BossRewardBagTestSupport.Member member, int trialOne, int trialTwo,
                                             int teeth, int fillers) {
        member.stack(InventoryType.ETC, TRIAL_ONE, trialOne);
        member.stack(InventoryType.ETC, TRIAL_TWO, trialTwo);
        member.stack(InventoryType.ETC, TEETH, teeth);
        member.fill(InventoryType.ETC, fillers);
        assertEquals(BossRewardBagTestSupport.SLOTS, member.occupied(InventoryType.ETC), "the Etc tab is full");
    }

    @Test
    void aFullEtcTabWithNothingToEmptyTakesNothingAndSaysWhy() throws Exception {
        // Two of each document and 100 teeth: the takes would leave 1, 1 and 70, so no slot frees and the Eyes cannot
        // land. Before the fix this took the documents, the 30 teeth and the quest and gave nothing back.
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        fullEtcWithMaterials(member, 2, 2, 100, 21);

        AdobisCm cm = forge(member);

        assertEquals(List.of("Free 1 slot in your Etc tab, then come back, nothing was taken."), cm.oks);
        assertTrue(cm.nexts.isEmpty(), "the success line must not appear");
        assertTrue(cm.completed.isEmpty(), "quest 100201 was not recorded");
        assertEquals(2, member.count(InventoryType.ETC, TRIAL_ONE), "the first document is untouched");
        assertEquals(2, member.count(InventoryType.ETC, TRIAL_TWO), "the second document is untouched");
        assertEquals(100, member.count(InventoryType.ETC, TEETH), "the teeth are untouched");
        assertEquals(0, member.count(InventoryType.ETC, EYES), "and no Eyes were handed out");
        assertTrue(cm.disposed);
    }

    @Test
    void anExactFitThroughTheTakesForTheEyes() throws Exception {
        // The three takes empty three Etc stacks, which pays for the one slot the five Eyes need.
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        fullEtcWithMaterials(member, 1, 1, 30, 21);

        AdobisCm cm = forge(member);

        assertEquals(5, member.count(InventoryType.ETC, EYES), "the five Eyes of Fire arrived");
        assertEquals(0, member.count(InventoryType.ETC, TRIAL_ONE), "the first document was taken");
        assertEquals(0, member.count(InventoryType.ETC, TRIAL_TWO), "the second document was taken");
        assertEquals(0, member.count(InventoryType.ETC, TEETH), "the 30 teeth were taken");
        assertEquals(List.of(100201), cm.completed, "the quest was recorded");
        assertEquals(List.of("You #rhave completed the trials#k, from now on having my approval to challenge Zakum."),
                cm.nexts);
        assertTrue(cm.oks.isEmpty(), "nothing to say about a bag");
    }

    @Test
    void theCheckCountsTheStacksTheEyesThemselvesNeed() throws Exception {
        // The Eyes do not stack here (slot max 1), so five of them need five slots. The takes free two and the teeth
        // stack keeps 30 of its 60, so three slots are still missing and nothing may be taken.
        itemData.close();
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, Map.of(EYES, (short) 1));

        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        fullEtcWithMaterials(member, 1, 1, 60, 21);

        AdobisCm cm = forge(member);

        assertEquals(List.of("Free 3 slots in your Etc tab, then come back, nothing was taken."), cm.oks);
        assertEquals(1, member.count(InventoryType.ETC, TRIAL_ONE), "nothing was taken");
        assertEquals(60, member.count(InventoryType.ETC, TEETH));
        assertEquals(0, member.count(InventoryType.ETC, EYES));
        assertTrue(cm.completed.isEmpty());
    }

    @Test
    void aStackTheTakesHalfEmptyFreesNoSlotButTheDocumentsStillPay() throws Exception {
        // 100 teeth keep 70, so that stack frees nothing; the two single documents do free two slots, enough for one
        // Eyes stack. The check must count exactly that, not refuse because the tab is full going in.
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        fullEtcWithMaterials(member, 1, 1, 100, 21);

        AdobisCm cm = forge(member);

        assertEquals(5, member.count(InventoryType.ETC, EYES));
        assertEquals(70, member.count(InventoryType.ETC, TEETH), "only 30 teeth were taken");
        assertEquals(List.of(100201), cm.completed);
        assertTrue(cm.oks.isEmpty());
    }

    @Test
    void roomToSpareGetsExactlyWhatItGetsToday() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.stack(InventoryType.ETC, TRIAL_ONE, 1);
        member.stack(InventoryType.ETC, TRIAL_TWO, 1);
        member.stack(InventoryType.ETC, TEETH, 30);
        member.fill(InventoryType.ETC, 5);

        AdobisCm cm = forge(member);

        assertEquals(5, member.count(InventoryType.ETC, EYES));
        assertEquals(0, member.count(InventoryType.ETC, TEETH));
        assertEquals(List.of(100201), cm.completed);
        assertEquals(List.of("You #rhave completed the trials#k, from now on having my approval to challenge Zakum."),
                cm.nexts);
    }

    @Test
    void tooFewTeethStillSaysSoAndTakesNothing() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.stack(InventoryType.ETC, TRIAL_ONE, 1);
        member.stack(InventoryType.ETC, TRIAL_TWO, 1);
        member.stack(InventoryType.ETC, TEETH, 29);

        AdobisCm cm = forge(member);

        assertEquals(List.of("You have completed the trials, however there's still the need of #b30 #t4000082##k to "
                + "forge 5 #t4001017#."), cm.oks);
        assertEquals(1, member.count(InventoryType.ETC, TRIAL_ONE), "nothing was taken");
        assertEquals(29, member.count(InventoryType.ETC, TEETH));
        assertEquals(0, member.count(InventoryType.ETC, EYES));
        assertTrue(cm.completed.isEmpty());
        assertTrue(cm.disposed);
    }

    @Test
    void aPlayerMissingADocumentStillGetsTheOldMessage() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.stack(InventoryType.ETC, TRIAL_ONE, 1);
        member.stack(InventoryType.ETC, TEETH, 30);

        AdobisCm cm = forge(member);

        assertEquals(List.of("You lack some of the required items to forge the #b#t4001017##k."), cm.oks);
        assertEquals(30, member.count(InventoryType.ETC, TEETH), "nothing was taken");
        assertFalse(cm.completed.contains(100201));
    }
}
