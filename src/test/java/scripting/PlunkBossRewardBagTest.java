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
import server.maps.MapleMap;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 bagrewards (card plunk-reward-full-bag): Plunk (scripts/npc/3004006.js) hands every player the room's clear
 * reward with {@code cm.gainItem} and then set the instance's "rewarded" latch. {@code gainItem} answers a full tab
 * with "Your inventory is full" and returns null, so a player with a full tab lost the reward and, the latch being
 * set for the whole instance, nobody present could ask again. Von Leon's Seal (4001693) is the only way to the
 * rebirth-2 stone, so losing it is the worst case.
 *
 * <p>Now each player's reward list is built once (random pick included) and measured before anything is taken or
 * granted, the latch is per player and set only after that player's grant, and a player whose bag is short is told
 * how many slots to free in which tab and stays in the room, unlatched, so they can come back. A player with room
 * gets the same ids, quantities, order and odds as before, and a bot is still skipped.
 */
class PlunkBossRewardBagTest {

    private static final int USE = InventoryType.USE.getType();
    private static final int ETC = InventoryType.ETC.getType();

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    public static final class FakeEim {
        final Map<String, String> props = new HashMap<>();
        final List<Character> players = new ArrayList<>();
        boolean cleared = true;

        public void setProperty(String key, Object value) {
            props.put(key, String.valueOf(value));
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public boolean isEventCleared() {
            return cleared;
        }

        public List<Character> getPlayers() {
            return players;
        }

        public String sendDmgDealt(int world) {
            return "";
        }
    }

    public static final class ExitCm {
        public FakeEim eim;
        public Character player;
        public int map = 280030000;
        public final List<String> oks = new ArrayList<>();
        public boolean disposed;
        public int warpTo = -1;

        public FakeEim getEventInstance() {
            return eim;
        }

        public int getMapId() {
            return map;
        }

        public Character getPlayer() {
            return player;
        }

        public void sendYesNo(String text) {
        }

        public void sendOk(String text) {
            oks.add(text);
        }

        public void warp(int mapId) {
            warpTo = mapId;
        }

        public void dispose() {
            disposed = true;
        }
    }

    private MockedStatic<ItemInformationProvider> itemData;
    private final World world = mock(World.class);
    private final List<Integer> unclaimedFor = new ArrayList<>();
    private final List<String> unclaimedBoss = new ArrayList<>();

    @BeforeAll
    static void loadItemProviderClass() throws Exception {
        BossRewardBagTestSupport.loadItemProviderClass();
    }

    @BeforeEach
    void setUp() {
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, Map.of());
        doAnswer(call -> {
            unclaimedBoss.add(String.valueOf((Object) call.getArgument(0)));
            unclaimedFor.add(call.getArgument(1, Integer.class));
            return null;
        }).when(world).removeUnclaimed(any(), anyInt());
    }

    @AfterEach
    void tearDown() {
        itemData.close();
    }

    /** A cleared run in that room, the first member talking to Plunk and confirming. */
    private ExitCm leave(BossRewardBagTestSupport.Member talker, int map,
                         BossRewardBagTestSupport.Member... others) throws Exception {
        FakeEim eim = new FakeEim();
        eim.players.add(talker.chr);
        for (BossRewardBagTestSupport.Member other : others) {
            eim.players.add(other.chr);
        }
        return leaveIn(eim, talker, map);
    }

    /** The same, with Math.random pinned, for a test that cares which of a boss's bands was rolled. */
    private ExitCm leaveWithRandom(double random, BossRewardBagTestSupport.Member talker, int map) throws Exception {
        FakeEim eim = new FakeEim();
        eim.players.add(talker.chr);
        ExitCm cm = new ExitCm();
        cm.eim = eim;
        cm.player = talker.chr;
        cm.map = map;
        return run(cm, random);
    }

    /** A later talk in the same run: same instance, same players, same latches. */
    private ExitCm leaveIn(FakeEim eim, BossRewardBagTestSupport.Member talker, int map) throws Exception {
        ExitCm cm = new ExitCm();
        cm.eim = eim;
        cm.player = talker.chr;
        cm.map = map;
        return run(cm, null);
    }

    private ExitCm run(ExitCm cm, Double random) throws Exception {
        ScriptEngine engine = new Loader().load("npc/3004006.js");
        assertNotNull(engine, "scripts/npc/3004006.js evaluates");
        if (random != null) {
            engine.eval("var __rolls = 0; Math.random = function() { __rolls++; return " + random + "; };");
        }
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");              // status 0: the yes/no question
        npc.invokeFunction("action", 1, 0, 0);   // status 1: rewards, then the way out
        return cm;
    }

    private static int count(BossRewardBagTestSupport.Member member, InventoryType tab, int itemId) {
        return member.count(tab, itemId);
    }

    @Test
    void aFullUseTabCostsNothingIsNotLatchedAndIsToldWhichTabToFree() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm cm = leave(member, 280030000);

        assertEquals(List.of("Your bag can't take the boss rewards. Free 1 slot in your Use tab, "
                + "then talk to me again."), cm.oks, "the refusal names how many slots and which tab");
        assertEquals(0, count(member, InventoryType.USE, 2000005), "no elixir was handed out");
        assertEquals(0, count(member, InventoryType.ETC, 4032133), "no Zakum certificate was handed out");
        assertNull(cm.eim.getProperty("rewarded_1000001"), "the player is not latched, so they can ask again");
        assertEquals(-1, cm.warpTo, "a player whose bag is short is not warped out of the room");
        assertTrue(cm.disposed, "the talk is still closed");
        assertTrue(unclaimedFor.isEmpty(), "nothing was cleared from the boss log for a reward that did not land");
    }

    @Test
    void anExactFitGetsTheZakumRewardIsLatchedAndLeaves() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS - 1);

        ExitCm cm = leave(member, 280030000);

        assertEquals(100, count(member, InventoryType.USE, 2000005), "the 100 Power Elixirs arrived");
        assertEquals(1, count(member, InventoryType.ETC, 4032133), "the Zakum certificate arrived");
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"), "the latch is set only after the grant");
        assertTrue(cm.oks.isEmpty(), "a player with room is told nothing about a bag");
        assertEquals(211042400, cm.warpTo, "the talker may leave");
        assertEquals(List.of(1_000_001), unclaimedFor, "the unclaimed boss-log entry is cleared for the player who was paid");
        assertTrue(unclaimedBoss.get(0).endsWith("ZAKUM"), "and it is the room's own entry: " + unclaimedBoss);
    }

    @Test
    void roomToSpareGetsExactlyWhatItGetsToday() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        ExitCm cm = leave(member, 280030000);

        assertEquals(100, count(member, InventoryType.USE, 2000005));
        assertEquals(1, count(member, InventoryType.ETC, 4032133));
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"));
        assertEquals("true", cm.eim.getProperty("rewarded"), "the instance-wide latch still ends up set");
        assertEquals(211042400, cm.warpTo);
    }

    @Test
    void vonLeonsSealAlwaysLandsAndTheRollHappensOncePerPlayer() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        ExitCm cm = leave(member, 211070100);

        assertEquals(1, count(member, InventoryType.ETC, 4001693), "Von Leon's Seal arrived");
        // The random 20% band is one of nine; whichever it is, exactly one extra item came along, in that quantity.
        int extras = count(member, InventoryType.USE, 2000005) + count(member, InventoryType.USE, 2022282)
                + count(member, InventoryType.USE, 2022179) + count(member, InventoryType.ETC, 4000999)
                + count(member, InventoryType.ETC, 4036544) + count(member, InventoryType.ETC, 4036545)
                + count(member, InventoryType.ETC, 4036546) + count(member, InventoryType.ETC, 4036547)
                + count(member, InventoryType.ETC, 4036548);
        assertTrue(extras > 0, "exactly one random pick came with the seal: " + member.bags);
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"));
        assertEquals(211070000, cm.warpTo);
    }

    @Test
    void aRolledUseItemWithAFullUseTabRefusesTheWholeList() throws Exception {
        // The 20% branch that pays 400 Power Elixirs. With the Use tab full the whole list must be refused, the Seal
        // included: a check that rolled again for the grant could have landed on an Etc branch and paid half.
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm cm = leaveWithRandom(0.05, member, 211070100);

        assertEquals(List.of("Your bag can't take the boss rewards. Free 1 slot in your Use tab, "
                + "then talk to me again."), cm.oks);
        assertEquals(0, count(member, InventoryType.ETC, 4001693), "the Seal was not handed out either");
        assertEquals(0, count(member, InventoryType.USE, 2000005));
        assertNull(cm.eim.getProperty("rewarded_1000001"));
        assertEquals(-1, cm.warpTo);
        assertTrue(unclaimedFor.isEmpty());
    }

    @Test
    void aRandomPickIsRolledOnceSoTheCheckAndTheGrantCannotDrift() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        ScriptEngine engine = new Loader().load("npc/3004006.js");
        assertNotNull(engine);
        // 0.45 is Von Leon's 2022282 branch: exactly one call to Math.random per player, before the space check.
        engine.eval("var __rolls = 0; Math.random = function() { __rolls++; return 0.45; };");
        ExitCm cm = new ExitCm();
        cm.eim = new FakeEim();
        cm.eim.players.add(member.chr);
        cm.player = member.chr;
        cm.map = 211070100;
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);

        assertEquals(1, ((Number) engine.eval("__rolls")).intValue(),
                "one roll per player: the list the check measured is the list that was granted");
        assertEquals(1, count(member, InventoryType.ETC, 4001693));
        assertEquals(1, count(member, InventoryType.USE, 2022282));
    }

    @Test
    void aFullEtcTabKeepsTheSealAndNamesEtcEvenWhenTheRollWasAUseBranch() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.ETC, BossRewardBagTestSupport.SLOTS);

        // The 20% band that pays 400 Power Elixirs: the only thing that will not fit is the Seal itself.
        ExitCm cm = leaveWithRandom(0.05, member, 211070100);

        assertEquals(List.of("Your bag can't take the boss rewards. Free 1 slot in your Etc tab, "
                + "then talk to me again."), cm.oks);
        assertEquals(0, count(member, InventoryType.ETC, 4001693), "the Seal is never dropped on the floor");
        assertNull(cm.eim.getProperty("rewarded_1000001"));
        assertEquals(-1, cm.warpTo);
    }

    @Test
    void aLongHardBossListNamesEveryTabItCannotFit() throws Exception {
        // Lucid hard pays 3 Use slots (2022282, 2022179, 2000005) and 6 Etc slots (4000999, 4036535, 4310000,
        // 4033732, 4001879, one core).
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.ETC, BossRewardBagTestSupport.SLOTS);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm cm = leave(member, 450004750);

        assertEquals(List.of("Your bag can't take the boss rewards. Free 3 slots in your Use tab and "
                + "6 slots in your Etc tab, then talk to me again."), cm.oks);
        assertNull(cm.eim.getProperty("rewarded_1000001"));
        assertEquals(-1, cm.warpTo);
        assertTrue(unclaimedFor.isEmpty(), "the boss log is untouched when nothing was paid");
    }

    @Test
    void aThousandElixirsNeedTheirOwnStacksAndAnExactFitStillGoesThrough() throws Exception {
        // Will hard pays 1000 Power Elixirs; at 100 per stack that is 10 slots, plus 2022282 and 2022179 in Use.
        itemData.close();
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, Map.of(2000005, (short) 100));

        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS - 12); // exactly 12 free

        ExitCm cm = leave(member, 450007440);

        assertEquals(1000, count(member, InventoryType.USE, 2000005), "all 1000 elixirs arrived");
        assertEquals(10, member.bag(InventoryType.USE).listById(2000005).size(), "as 10 stacks of 100");
        assertEquals(BossRewardBagTestSupport.SLOTS, member.occupied(InventoryType.USE), "and filled the tab exactly");
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"));
        assertEquals(450007240, cm.warpTo);
    }

    @Test
    void oneElixirStackShortIsRefusedWhole() throws Exception {
        itemData.close();
        itemData = BossRewardBagTestSupport.stubItemData((short) 32000, Map.of(2000005, (short) 100));

        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS - 11); // 11 free, 12 needed

        ExitCm cm = leave(member, 450007440);

        assertEquals(List.of("Your bag can't take the boss rewards. Free 1 slot in your Use tab, "
                + "then talk to me again."), cm.oks);
        assertEquals(0, count(member, InventoryType.USE, 2000005));
        assertNull(cm.eim.getProperty("rewarded_1000001"));
        assertEquals(-1, cm.warpTo);
    }

    @Test
    void twoPlayersOneFullTheOtherRewardedAndTheFullOneComesBackForItOnce() throws Exception {
        BossRewardBagTestSupport.Member full = new BossRewardBagTestSupport.Member(1_000_001, world);
        BossRewardBagTestSupport.Member roomy = new BossRewardBagTestSupport.Member(1_000_002, world);
        full.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm first = leave(full, 280030000, roomy);

        assertEquals(-1, first.warpTo, "the full player stays in the room");
        assertNull(first.eim.getProperty("rewarded_1000001"), "and is not latched");
        assertEquals(100, count(roomy, InventoryType.USE, 2000005), "the other player is rewarded anyway");
        assertEquals("true", first.eim.getProperty("rewarded_1000002"), "and latched");

        full.bag(InventoryType.USE).removeItem((short) 1);
        ExitCm second = leaveIn(first.eim, full, 280030000);

        assertEquals(100, count(full, InventoryType.USE, 2000005), "the player who was short gets the reward once");
        assertEquals("true", second.eim.getProperty("rewarded_1000001"));
        assertEquals(100, count(roomy, InventoryType.USE, 2000005), "the player who already had it is not paid twice");
        assertEquals(211042400, second.warpTo, "and now the talker may leave");
    }

    @Test
    void aFullPlayerIsToldByWhisperWhenSomebodyElseTalksFirst() throws Exception {
        BossRewardBagTestSupport.Member roomy = new BossRewardBagTestSupport.Member(1_000_001, world);
        BossRewardBagTestSupport.Member full = new BossRewardBagTestSupport.Member(1_000_002, world);
        full.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm cm = leave(roomy, 280030000, full);

        assertEquals(211042400, cm.warpTo, "the talker had room, so they leave");
        assertEquals(List.of("Your bag can't take the boss rewards. Free 1 slot in your Use tab, "
                + "then talk to me again."), full.drops, "the player whose bag is short is told directly");
        assertNull(cm.eim.getProperty("rewarded_1000002"));
        assertEquals(100, count(roomy, InventoryType.USE, 2000005));
    }

    @Test
    void talkingTwiceWithRoomGivesTheRewardOnce() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        ExitCm first = leave(member, 280030000);
        ExitCm again = leaveIn(first.eim, member, 280030000);

        assertEquals(100, count(member, InventoryType.USE, 2000005), "the second talk adds nothing");
        assertEquals(1, member.bag(InventoryType.USE).listById(2000005).size(), "still one stack");
        assertTrue(again.oks.isEmpty());
        assertEquals(211042400, again.warpTo);
        assertEquals(List.of(1_000_001), unclaimedFor, "the boss-log entry is cleared once");
    }

    @Test
    void aBotIsStillSkippedAndIsNotLatched() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        BossRewardBagTestSupport.Member bot = new BossRewardBagTestSupport.Member(1_000_002, world);
        when(bot.chr.isBot()).thenReturn(true);

        ExitCm cm = leave(member, 280030000, bot);

        assertEquals(100, count(member, InventoryType.USE, 2000005));
        assertEquals(0, count(bot, InventoryType.USE, 2000005), "a bot gets no reward");
        assertTrue(bot.drops.isEmpty(), "and is not messaged");
        assertEquals("true", cm.eim.getProperty("rewarded"), "the instance-wide latch ignores bots");
    }

    @Test
    void aRoomWithNoRewardTableIsUnchanged() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        MapleMap map = mock(MapleMap.class);
        when(map.getReturnMapId()).thenReturn(100000000);
        when(member.chr.getMap()).thenReturn(map);

        ExitCm cm = leave(member, 999);

        assertEquals(100000000, cm.warpTo, "an unknown room still falls back to its return map");
        assertTrue(member.bag(InventoryType.USE).listById(2000005).isEmpty(), "and pays nothing, as before");
        assertNull(cm.eim.getProperty("rewarded"), "no latch is invented for a room with no reward");
        assertTrue(cm.disposed);
    }

    /** Every reward list, exactly as the script gave it before the fix: same ids, order, quantities, duplicates. */
    private static Map<Integer, String> expectedRewardLists() {
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(280030000, "[[4032133,1],[2000005,100]]");
        expected.put(240060200, "[[4001094,1],[2022179,1],[2000005,200]]");
        // Von Leon rolls one of nine bands; 0.05 is the first, and the other eight are asserted separately below.
        expected.put(211070100, "[[4001693,1],[2000005,400]]");
        expected.put(271040100, "[[4000659,1],[4000999,500],[2000005,500],[2022282,3],[2022179,6],[2022283,3],[4036544,1]]");
        expected.put(450007440, "[[4000999,2000],[4036536,1],[4310000,8],[4001890,2],[2022282,10],[2022179,10],"
                + "[2000005,1000],[2022282,10],[4036544,5]]");
        expected.put(450010100, "[[4000999,2000],[4036538,1],[4310000,8],[4001893,2],[2022282,10],[2022282,10],"
                + "[2022179,10],[2000005,1000],[4036544,5]]");
        expected.put(450012210, "[[4000999,2000],[4036537,1],[4310000,8],[4001894,2],[2022282,10],[2022179,10],"
                + "[2000005,1000],[4036544,5]]");
        expected.put(450004750, "[[4000999,2000],[4036535,1],[4310000,8],[4033732,2],[4001879,2],[2022282,10],"
                + "[2022179,10],[2000005,1000],[4036544,5]]");
        expected.put(401060100, "[[4000999,4000],[4036540,1],[4310000,8],[4036543,1],[2022282,10],[2022179,10],"
                + "[2000005,1000],[4036544,7]]");
        expected.put(450007441, "[[4000999,800],[4001890,1],[2022282,6],[2022283,6],[2022179,7],[2000005,1000],"
                + "[4036544,3]]");
        expected.put(450010101, "[[4000999,800],[4001893,1],[2022282,6],[2022283,6],[2022179,7],[2000005,1000],"
                + "[4036544,3]]");
        expected.put(450012211, "[[4000999,800],[4001894,1],[2022282,6],[2022283,6],[2022179,7],[2000005,1000],"
                + "[4036544,3]]");
        expected.put(450004751, "[[4000999,800],[4033732,1],[4001879,1],[2022282,6],[2022283,6],[2022179,7],"
                + "[2000005,1000],[4036544,3]]");
        return expected;
    }

    @Test
    void everyRoomsRewardListIsTheOneTheScriptAlwaysGave() throws Exception {
        Map<Integer, String> expected = expectedRewardLists();
        assertEquals(13, expected.size(), "all thirteen reward rooms are covered");

        for (Map.Entry<Integer, String> room : expected.entrySet()) {
            ScriptEngine engine = new Loader().load("npc/3004006.js");
            assertNotNull(engine);
            // 0.05 picks the first of any random band: the low core 4036544, and Von Leon would pick the elixirs.
            engine.eval("Math.random = function() { return 0.05; };");
            String list = String.valueOf(engine.eval("JSON.stringify(clearRewards(" + room.getKey() + ").roll())"));
            assertEquals(room.getValue(), list, "room " + room.getKey());
        }
    }

    @Test
    void vonLeonsNineRandomBandsAreTheOnesTheScriptAlwaysGave() throws Exception {
        // [Math.random value, the list that has always followed]: the same bands, the same quantities, in order.
        Map<Double, String> bands = new LinkedHashMap<>();
        bands.put(0.05, "[[4001693,1],[2000005,400]]");
        bands.put(0.25, "[[4001693,1],[4000999,200]]");
        bands.put(0.45, "[[4001693,1],[2022282,1]]");
        bands.put(0.65, "[[4001693,1],[2022179,2]]");
        bands.put(0.82, "[[4001693,1],[4036544,1]]");
        bands.put(0.86, "[[4001693,1],[4036545,1]]");
        bands.put(0.90, "[[4001693,1],[4036546,1]]");
        bands.put(0.94, "[[4001693,1],[4036547,1]]");
        bands.put(0.98, "[[4001693,1],[4036548,1]]");

        for (Map.Entry<Double, String> band : bands.entrySet()) {
            ScriptEngine engine = new Loader().load("npc/3004006.js");
            engine.eval("Math.random = function() { return " + band.getKey() + "; };");
            assertEquals(band.getValue(),
                    String.valueOf(engine.eval("JSON.stringify(clearRewards(211070100).roll())")),
                    "Von Leon at Math.random() = " + band.getKey());
        }
    }

    @Test
    void everyCoreRandomPicksTheRightIdAndQuantity() throws Exception {
        Map<Double, String> bands = new LinkedHashMap<>();
        bands.put(0.05, "4036544");
        bands.put(0.25, "4036545");
        bands.put(0.45, "4036546");
        bands.put(0.65, "4036547");
        bands.put(0.85, "4036548");

        for (Map.Entry<Double, String> band : bands.entrySet()) {
            ScriptEngine engine = new Loader().load("npc/3004006.js");
            engine.eval("Math.random = function() { return " + band.getKey() + "; };");
            // Magnus pays 7 of the rolled core: the last entry of its list.
            String list = String.valueOf(engine.eval("JSON.stringify(clearRewards(401060100).roll())"));
            assertTrue(list.endsWith(",[" + band.getValue() + ",7]]"),
                    "Magnus at Math.random() = " + band.getKey() + " ended with " + band.getValue() + " x7, was " + list);
        }
    }
}
