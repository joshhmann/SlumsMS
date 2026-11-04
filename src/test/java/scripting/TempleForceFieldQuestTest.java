package scripting;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.QuestStatus;
import client.inventory.Inventory;
import constants.inventory.ItemConstants;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import server.quest.Quest;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static soloMapling.WzTestData.assumePresent;
import static soloMapling.WzTestData.dir;

/**
 * Quest 3521 "Force Field" (Temple of Time, keeper 2140000): the item turn-in.
 *
 * <p>The requirements are entirely in the data - {@code Quest.wz/Check.img.xml} declares start npc 2140000,
 * {@code lvmin 135}, prerequisite 3520 in state 2 and, in block 1, the six hand-in items; {@code Act.img.xml}
 * block 1 removes them and grants one {@code 4032002 Marble of Chaos}. Neither block declares a
 * {@code startscript}/{@code endscript} name, so there is no {@code scripts/quest/3521.js} to run: {@code
 * Quest.hasScriptRequirement} is false on both sides and {@code Quest.complete} does the removal and the grant
 * through {@code ItemAction}. That is why this test drives {@link Quest} instead of a script engine, and why
 * every count below is asserted against the real WZ data rather than a hand-written list.
 *
 * <p>The character is a bot (a {@link BotClient} whose {@code getPlayer()} is null) on purpose: that is the case
 * that used to blow up the item path with a NullPointerException out of
 * {@code AbstractPlayerInteraction.getPlayer()}, which made any item turn-in impossible outside a real client
 * session (see {@code InventoryManipulator.removeById(Character, ...)}, ticket 164).
 */
public class TempleForceFieldQuestTest {

    private static final int QUEST = 3521;
    private static final int KEEPER = 2140000;
    private static final int MARBLE_OF_CHAOS = 4032002;

    /** Check.img block 1 of 3521: the hand-in, item id to count. */
    private static final Map<Integer, Integer> REQUIRED = new LinkedHashMap<>();

    static {
        REQUIRED.put(4000446, 10); // Smiley Mask
        REQUIRED.put(4000451, 10); // Neutral Mask
        REQUIRED.put(4000456, 10); // Frowny Mask
        REQUIRED.put(4000460, 1);  // Whale's Helmet
        REQUIRED.put(4000461, 1);  // Knight's Mask
        REQUIRED.put(4000462, 1);  // Guardian's Horn
    }

    private static MockedStatic<DatabaseConnection> db;

    @BeforeAll
    static void wiz() {
        // Quest resolves the WZ directory in a static initializer, so the path has to be set before Quest loads.
        System.setProperty("wz-path", dir().toString());
        // ItemInformationProvider loads its card ids from the database; no MySQL here, so stub the connection and
        // let it read the rest (slot max, names) from the real WZ.
        db = mockStatic(DatabaseConnection.class);
        db.when(DatabaseConnection::getConnection).thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));
    }

    /**
     * The WZ check lives here, not in {@link #wiz()}: an assumption that fails in {@code @BeforeAll} aborts the whole
     * container and surefire reports {@code Tests run: 0}, which hides this class from the suite. Per test it aborts
     * that one test, which is counted as skipped - the same pattern as {@code server.maps.ReactorLoadTest}.
     * {@link #wiz()} still runs first (and {@link #closeDb()} still closes its static mock), because a container
     * whose {@code @BeforeAll} succeeded runs its teardown even when every test aborts.
     */
    @BeforeEach
    void requireWzData() {
        assumePresent("Quest.wz");
        assumePresent("Item.wz");
    }

    @AfterAll
    static void closeDb() {
        if (db != null) {
            db.close();
        }
    }

    @Test
    void completesWhenHoldingTheRequiredItems() {
        Character chr = keeperFinished();
        giveAll(chr, 2); // every required item plus two spares

        assertTrue(Quest.getInstance(QUEST).canComplete(chr, KEEPER), "six items are in the bag");
        Quest.getInstance(QUEST).complete(chr, KEEPER);

        assertEquals(QuestStatus.Status.COMPLETED, status(chr));
        assertLeftovers(chr);
        assertEquals(1, chr.getItemQuantity(MARBLE_OF_CHAOS, false), "Act.img block 1 grants the Marble of Chaos");
    }

    @Test
    void doesNotCompleteWhenAnItemIsMissing() {
        Character chr = keeperFinished();
        giveAll(chr, 2);
        removeItems(chr, 4000456, 3); // one Frowny Mask short

        assertFalse(Quest.getInstance(QUEST).canComplete(chr, KEEPER), "9 of the 10 Frowny Masks is not enough");
        Quest.getInstance(QUEST).complete(chr, KEEPER);

        assertEquals(QuestStatus.Status.STARTED, status(chr), "the hand-in is refused, the quest stays open");
        assertEquals(9, chr.getItemQuantity(4000456, false), "nothing was taken");
        assertEquals(0, chr.getItemQuantity(MARBLE_OF_CHAOS, false), "nothing was granted");
    }

    @Test
    void consumesTheHandInExactlyOnce() {
        Character chr = keeperFinished();
        giveAll(chr, 2);

        Quest.getInstance(QUEST).complete(chr, KEEPER);
        Map<Integer, Integer> afterFirst = counts(chr);

        assertFalse(Quest.getInstance(QUEST).canComplete(chr, KEEPER), "a completed quest cannot be handed in again");
        Quest.getInstance(QUEST).complete(chr, KEEPER);

        assertEquals(afterFirst, counts(chr), "a second complete() must not take the items a second time");
        assertLeftovers(chr);
        assertEquals(1, chr.getItemQuantity(MARBLE_OF_CHAOS, false), "and must not grant a second marble");
    }

    @Test
    void playsWithoutAQuestScriptFile() {
        assertFalse(Quest.getInstance(QUEST).hasScriptRequirement(false),
                "Check.img block 0 declares no startscript, so scripts/quest/3521.js is never loaded");
        assertFalse(Quest.getInstance(QUEST).hasScriptRequirement(true),
                "Check.img block 1 declares no endscript either");
        assertNull(new Loader().load("quest/" + QUEST + ".js"), "no quest script file exists for 3521");
        assertNull(new Loader().load("npc/" + KEEPER + ".js"), "the keeper has no npc script either");
        assertNotNull(Quest.getInstance(QUEST), "and the quest still plays from the data alone");
    }

    @Test
    void botCharacterCanAskWhetherItFits() {
        Character chr = keeperFinished();
        giveAll(chr, 2);

        // The regression: both of these went through client.getPlayer() and threw for a bot.
        assertTrue(chr.canHold(MARBLE_OF_CHAOS, 1));
        assertTrue(chr.getAbstractPlayerInteraction().canHoldAllAfterRemoving(
                java.util.List.of(MARBLE_OF_CHAOS), java.util.List.of(1),
                java.util.List.of(4000446), java.util.List.of(10)));
    }

    // ---- helpers ----

    /** A level-135 bot that has finished 3520 and has 3521 started, as the keeper would leave it. */
    private static Character keeperFinished() {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setMap(mock(MapleMap.class));
        chr.setLevel(135);
        chr.updateQuestStatus(new QuestStatus(Quest.getInstance(3520), QuestStatus.Status.COMPLETED));
        assertTrue(Quest.getInstance(QUEST).canStart(chr, KEEPER), "prerequisite chain: 3520 done, level 135");
        Quest.getInstance(QUEST).start(chr, KEEPER);
        assertEquals(QuestStatus.Status.STARTED, status(chr));
        return chr;
    }

    /**
     * Hands the character every required item plus {@code spare}'s worth, so the leftover check proves the
     * removal took the declared count and nothing more.
     */
    private static void giveAll(Character chr, int spare) {
        for (Map.Entry<Integer, Integer> e : REQUIRED.entrySet()) {
            give(chr, e.getKey(), e.getValue() + spare);
        }
    }

    private static void removeItems(Character chr, int itemId, int quantity) {
        Inventory inv = chr.getInventory(ItemConstants.getInventoryType(itemId));
        for (Item it : inv.listById(itemId)) {
            inv.removeItem(it.getPosition(), (short) quantity, false);
            return;
        }
    }

    private static void give(Character chr, int itemId, int quantity) {
        chr.getInventory(ItemConstants.getInventoryType(itemId)).addItem(new Item(itemId, (short) 0, (short) quantity));
    }

    /** Every item was given with two spares, so exactly two of each must survive the hand-in. */
    private static void assertLeftovers(Character chr) {
        for (Map.Entry<Integer, Integer> e : REQUIRED.entrySet()) {
            assertEquals(2, chr.getItemQuantity(e.getKey(), false), "item " + e.getKey());
        }
    }

    private static Map<Integer, Integer> counts(Character chr) {
        Map<Integer, Integer> held = new LinkedHashMap<>();
        for (Integer itemId : REQUIRED.keySet()) {
            held.put(itemId, chr.getItemQuantity(itemId, false));
        }
        held.put(MARBLE_OF_CHAOS, chr.getItemQuantity(MARBLE_OF_CHAOS, false));
        return held;
    }

    private static QuestStatus.Status status(Character chr) {
        QuestStatus qs = chr.getQuestNoAdd(Quest.getInstance(QUEST));
        return qs == null ? QuestStatus.Status.NOT_STARTED : qs.getStatus();
    }

    /** Graal loader for "is there a script file", the same harness {@code GateOfTimeScriptTest} uses. */
    private static final class Loader extends AbstractScriptManager {
        javax.script.ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }
}
