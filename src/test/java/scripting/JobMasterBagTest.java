package scripting;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.npc.NPCConversationManager;
import server.ItemInformationProvider;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 jobmaster-fee-then-bag: the Job Master (scripts/npc/9010003.js) used to take the mesos, change the job and
 * only then hand the rewards out with {@code cm.gainItem}. {@code gainItem} answers a full tab with "Your inventory is
 * full" and returns null, so a player with a full bag paid the fee, got the job and lost the gear: a no-loss break.
 *
 * <p>The fix measures the whole reward list first, with the same counter {@code gainItem} itself consults
 * ({@link client.inventory.manipulator.InventoryManipulator#checkSpaceProgressively}), and refuses the advancement
 * while nothing has been taken and nothing has changed, naming the slots to free per tab.
 *
 * <p>Every test drives the real script through its real Graal engine and a fake {@code cm}, so what a player gets is
 * asserted, not the script text. The bags are real {@link Inventory} objects, so the counting runs for real.
 */
class JobMasterBagTest {

    /** Enough to hold anything a reward list needs; the tests fill it as far as the case requires. */
    private static final byte SLOTS = 24;

    private static final int ELIXIRS = 2000005;

    private static final List<InventoryType> TABS =
            List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP, InventoryType.ETC, InventoryType.CASH);

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private MockedStatic<ItemInformationProvider> itemData;
    private Character chr;
    private NPCConversationManager cm;
    private final Map<InventoryType, Inventory> bags = new EnumMap<>(InventoryType.class);

    /** Every "Choose your Job" list start() sent. */
    private final List<String> lists = new ArrayList<>();
    /** Every sendOk text, in order. */
    private final List<String> oks = new ArrayList<>();
    /** Every cm.gainItem as "id xqty". */
    private final List<String> granted = new ArrayList<>();
    /** Every cm.changeJobById call, whether it succeeded or not. */
    private final List<Integer> jobChanges = new ArrayList<>();
    /** Every chr.gainMeso delta, in order (negative is a charge, positive a refund). */
    private final List<Integer> mesos = new ArrayList<>();
    private boolean changeJobSucceeds = true;

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        // ItemInformationProvider's static init reads the DB and the WZ; load the class once against mocks, the way
        // BotInventoryAddTest does, so this test needs neither.
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.isPickupRestricted(anyInt())).thenReturn(false);
        when(ii.isCash(anyInt())).thenReturn(false);
        // Power Elixirs and the arrows stack, so a reward can merge into a stack the player already carries.
        when(ii.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 32000);
        itemData = mockStatic(ItemInformationProvider.class);
        itemData.when(ItemInformationProvider::getInstance).thenReturn(ii);

        chr = mock(Character.class);
        for (InventoryType tab : TABS) {
            bags.put(tab, new Inventory(chr, tab, SLOTS));
        }
        doAnswer(call -> bags.get(call.getArgument(0, InventoryType.class)))
                .when(chr).getInventory(any(InventoryType.class));
    }

    @AfterEach
    void tearDown() {
        itemData.close();
    }

    /** Loads scripts/npc/9010003.js for a character of that job and level, with bags as the test left them. */
    private Invocable load(int jobId, int level) {
        when(chr.getLevel()).thenReturn(level);
        when(chr.getReborns()).thenReturn(3);
        when(chr.isBeginnerJob()).thenReturn(jobId == 0 || jobId == 1000 || jobId == 2000);
        when(chr.getSkillLevel(anyInt())).thenReturn(0);
        when(chr.getJob()).thenReturn(Job.WARRIOR);

        cm = mock(NPCConversationManager.class, CALLS_REAL_METHODS);
        // doReturn, not when(...): the mock calls real methods, and the real getPlayer() dereferences a null Client
        doReturn(chr).when(cm).getPlayer();
        doReturn(chr).when(cm).getChar();
        doReturn(jobId).when(cm).getJobId();
        doReturn(false).when(cm).hasItem(anyInt());
        doReturn(1_000_000_000).when(cm).getMeso();
        doAnswer(call -> String.valueOf(call.getArgument(0, Integer.class))).when(cm).getJobName(anyInt());
        doAnswer(call -> lists.add(call.getArgument(0, String.class))).when(cm).sendSimple(anyString());
        doAnswer(call -> oks.add(call.getArgument(0, String.class))).when(cm).sendOk(anyString());
        doNothing().when(cm).sendYesNo(anyString());
        doNothing().when(cm).dispose();
        doNothing().when(cm).gainItem(anyInt(), anyShort());
        doNothing().when(cm).gainItem(anyInt(), anyShort(), anyBoolean());
        doNothing().when(cm).teachSkill(anyInt(), anyByte(), anyByte(), anyLong());
        doNothing().when(cm).teachFourthJobMasteries(anyInt());
        doAnswer(call -> {
            granted.add(call.getArgument(0, Integer.class) + " x" + call.getArgument(1, Short.class));
            return null;
        }).when(cm).gainItem(anyInt(), anyShort());
        doAnswer(call -> {
            jobChanges.add(call.getArgument(0, Integer.class));
            return changeJobSucceeds;
        }).when(cm).changeJobById(anyInt());
        doAnswer(call -> {
            mesos.add(call.getArgument(0, Integer.class));
            return null;
        }).when(chr).gainMeso(anyInt());

        ScriptEngine engine = new Loader().load("npc/9010003.js");
        assertNotNull(engine, "scripts/npc/9010003.js evaluates");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    /** start(), pick the offered job that leads to {@code target}, then answer yes, as a player does. */
    private void advanceTo(int jobId, int level, int target) throws Exception {
        Invocable script = load(jobId, level);
        script.invokeFunction("start");
        assertEquals(1, lists.size(), "start() sent one job list: " + lists);
        int index = indexOf(target, lists.get(0));
        assertTrue(index >= 0, "a job " + jobId + " character at level " + level + " is offered " + target
                + ", but the list was: " + lists.get(0));
        script.invokeFunction("action", 1, 0, index);
        script.invokeFunction("action", 1, 1, 0);
    }

    private static int indexOf(int jobId, String list) {
        Matcher m = Pattern.compile("#L(\\d+)#(\\d+)#l").matcher(list);
        while (m.find()) {
            if (Integer.parseInt(m.group(2)) == jobId) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }

    /** Fills that tab with {@code used} different items, so every one of them costs a slot. */
    private void fill(InventoryType tab, int used) {
        int base = switch (tab) {
            case EQUIP -> 1_000_000;
            case USE -> 2_001_000;
            case SETUP -> 3_000_000;
            case ETC -> 4_000_000;
            default -> 5_000_000;
        };
        int before = bags.get(tab).list().size();
        for (int i = 0; i < used; i++) {
            assertTrue(bags.get(tab).addItem(new Item(base + i, (short) 0, (short) 1, -1)) > 0,
                    "filler " + (base + i) + " fits in " + tab);
        }
        assertEquals(before + used, SLOTS - bags.get(tab).getNumFreeSlot(),
                "tab " + tab + " now has " + (before + used) + " slots taken");
    }

    /** Puts one stack of that item in the tab and returns it. */
    private Item stack(InventoryType tab, int itemId, short quantity) {
        Item item = new Item(itemId, (short) 0, quantity, -1);
        assertTrue(bags.get(tab).addItem(item) > 0, "a stack of " + itemId + " fits in " + tab);
        return item;
    }

    @Test
    void aFullBagPaysNothingGetsNothingAndNamesTheTabToFree() throws Exception {
        // Job 110 at level 70 is offered 111, whose test costs 75m to skip. A 111 hands out 50 Power Elixirs and
        // nothing else, so a Use tab with no room cannot take the rewards.
        fill(InventoryType.USE, SLOTS);
        advanceTo(110, 70, 111);

        assertEquals(List.of("Your bag can't take what I hand out for that job. Free 1 slot in your Use tab, "
                + "then talk to me again."), oks, "the refusal names how many slots and which tab");
        assertTrue(mesos.isEmpty(), "the fee was not charged, so nothing can be lost");
        assertTrue(granted.isEmpty(), "no reward was handed out");
        assertTrue(jobChanges.isEmpty(), "the job did not change");
    }

    @Test
    void aFullBagOnTwoTabsNamesBothWithTheirOwnCount() throws Exception {
        // Job 700's own gear list is 8 equips plus the 50 Power Elixirs: 8 Equip slots and 1 Use slot. The 700
        // branch of the script changes no job and costs nothing, but it still hands the gear out.
        fill(InventoryType.EQUIP, 18); // 6 free, 2 short of the gear
        fill(InventoryType.USE, SLOTS); // 0 free, 1 short of the elixirs
        advanceTo(0, 10, 700);

        assertEquals(List.of("Your bag can't take what I hand out for that job. Free 2 slots in your Equip tab and "
                + "1 slot in your Use tab, then talk to me again."), oks, "both tabs, each with its own count");
        assertTrue(granted.isEmpty(), "no gear and no elixirs were handed out");
        assertTrue(oks.stream().noneMatch(ok -> ok.contains("right path")), "the success line must not appear");
    }

    @Test
    void anExactFitAdvancesChargesTheFeeAndHandsEverythingOut() throws Exception {
        fill(InventoryType.USE, SLOTS - 1); // exactly one slot free for the 50 Power Elixirs
        advanceTo(110, 70, 111);

        assertEquals(List.of(111), jobChanges, "the advancement went through");
        assertEquals(List.of(-75_000_000), mesos, "the 3rd job fee is charged once, after the space check");
        assertEquals(List.of(ELIXIRS + " x50"), granted, "every reward arrived");
        assertTrue(oks.isEmpty(), "a successful advancement says nothing about a bag");
    }

    @Test
    void roomToSpareGetsExactlyWhatItGetsToday() throws Exception {
        // Job 0 at level 10 is offered 100/300/400/500/700. 300 hands out 50 Power Elixirs, a bow, a crossbow and
        // two kinds of 999 arrows, and the stock instructor advancements are free.
        advanceTo(0, 10, 300);

        assertEquals(List.of(300), jobChanges, "the job advanced");
        assertTrue(mesos.isEmpty(), "300 is a stock instructor job and costs nothing");
        assertEquals(List.of(ELIXIRS + " x50", "1452051 x1", "1462092 x1", "2060000 x999", "2061000 x999"),
                granted, "the same items in the same quantities as before the change");
        assertTrue(oks.isEmpty(), "no message a player did not get before");
    }

    @Test
    void aJobThatHandsOutNothingIsNeverRefused() throws Exception {
        // 1000 and 2000 (Noblesse, Legend) hand out nothing, so a completely full bag must not stand in the way.
        fill(InventoryType.EQUIP, SLOTS);
        fill(InventoryType.USE, SLOTS);
        fill(InventoryType.SETUP, SLOTS);
        fill(InventoryType.ETC, SLOTS);
        fill(InventoryType.CASH, SLOTS);
        advanceTo(0, 1, 1000);

        assertEquals(List.of(1000), jobChanges, "the job advanced with every tab full");
        assertTrue(granted.isEmpty(), "there was nothing to hand out");
        assertTrue(oks.isEmpty(), "and nothing was said about a bag");
        assertTrue(mesos.isEmpty(), "a beginner job costs nothing");
    }

    @Test
    void aRewardThatStacksOntoAnExistingStackNeedsNoFreeSlot() throws Exception {
        // 50 Power Elixirs merge into a stack of 1 that has room, which is what gainItem does with them, so a full
        // tab with that stack in it can still take the reward.
        stack(InventoryType.USE, ELIXIRS, (short) 1);
        fill(InventoryType.USE, SLOTS - 1);
        advanceTo(110, 70, 111);

        assertEquals(List.of(111), jobChanges, "the advancement went through");
        assertEquals(List.of(-75_000_000), mesos, "the fee was charged");
        assertEquals(List.of(ELIXIRS + " x50"), granted, "the elixirs were handed out");
    }

    @Test
    void anExistingStackWithNoRoomLeftNeedsAFreeSlot() throws Exception {
        // Same bag, but the stack is at the 32000 cap: the elixirs cannot merge, so they need the one slot there is
        // no room for, and the advancement is refused rather than half given.
        stack(InventoryType.USE, ELIXIRS, (short) 32000);
        fill(InventoryType.USE, SLOTS - 1);
        advanceTo(110, 70, 111);

        assertEquals(List.of("Your bag can't take what I hand out for that job. Free 1 slot in your Use tab, "
                + "then talk to me again."), oks, "a capped stack is not room");
        assertTrue(mesos.isEmpty(), "the fee was not charged");
        assertTrue(granted.isEmpty(), "no reward was handed out");
        assertTrue(jobChanges.isEmpty(), "the job did not change");
    }

    @Test
    void aRefusedAdvancementReturnsTheFee() throws Exception {
        // changeJobById says no for a job id with no Job behind it; the fee bought nothing, so it goes back.
        changeJobSucceeds = false;
        advanceTo(110, 70, 111);

        assertEquals(List.of(111), jobChanges, "the script asked for the advancement");
        assertEquals(List.of(-75_000_000, 75_000_000), mesos, "charged, then returned");
        assertTrue(granted.isEmpty(), "nothing was handed out for a failed advancement");
        assertEquals(1, oks.size(), "one message: " + oks);
        assertTrue(oks.get(0).contains("failed"), "the failure is still reported: " + oks.get(0));
        assertTrue(oks.get(0).contains("75,000,000 mesos#k have been returned"),
                "the player is told the mesos came back: " + oks.get(0));
    }

    @Test
    void aRefusalKeepsTheSuccessTextOutOfTheDialog() throws Exception {
        fill(InventoryType.USE, SLOTS);
        advanceTo(0, 10, 700);

        assertEquals(1, oks.size(), "one dialog: " + oks);
        assertFalse(oks.get(0).contains("right path"), "no success line when nothing was handed out");
    }
}
