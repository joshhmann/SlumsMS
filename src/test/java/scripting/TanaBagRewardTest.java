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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 1.6.7 bagrewards (card: plunk-reward-full-bag's sibling for the Papulatus room): Tana (scripts/npc/2041025.js) set
 * the instance's "rewarded" latch and only then handed 100 Power Elixirs to every player with {@code gainItem}. A
 * player with a full Use tab lost the elixirs and, the latch being set, nobody in that run could ask again.
 *
 * <p>Now each player is measured before anything is granted, the latch is per player and set only after that
 * player's grant, and a player whose bag is short is told which tab to free and stays in the room (not warped out)
 * so they can come back. A player with room gets exactly what they got before.
 */
class TanaBagRewardTest {

    private static final int MAP = 220080001;          // Papulatus
    private static final int EXIT = 220080000;
    private static final int ELIXIRS = 2000005;
    private static final int GIVEN = 100;

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
    }

    public static final class ExitCm {
        public FakeEim eim;
        public Character player;
        public final List<String> oks = new ArrayList<>();
        public boolean disposed;
        public int warpTo = -1;

        public FakeEim getEventInstance() {
            return eim;
        }

        public int getMapId() {
            return MAP;
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

    /** A cleared Papulatus run with these players, the first of them talking to Tana and confirming. */
    private ExitCm talk(BossRewardBagTestSupport.Member talker, BossRewardBagTestSupport.Member... others) throws Exception {
        FakeEim eim = new FakeEim();
        eim.players.add(talker.chr);
        for (BossRewardBagTestSupport.Member other : others) {
            eim.players.add(other.chr);
        }
        return talkIn(eim, talker);
    }

    /** A later talk in the same run: same instance, same players, same latches. */
    private ExitCm talkIn(FakeEim eim, BossRewardBagTestSupport.Member talker) throws Exception {
        ExitCm cm = new ExitCm();
        cm.eim = eim;
        cm.player = talker.chr;

        ScriptEngine engine = new Loader().load("npc/2041025.js");
        assertNotNull(engine, "scripts/npc/2041025.js evaluates");
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");              // status 0: the yes/no question
        npc.invokeFunction("action", 1, 0, 0);   // status 1: rewards, then the way out
        return cm;
    }

    private int elixirs(BossRewardBagTestSupport.Member member) {
        return member.count(InventoryType.USE, ELIXIRS);
    }

    @Test
    void aFullUseTabGetsNothingIsNotLatchedAndIsToldWhichTabToFree() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm cm = talk(member);

        assertEquals(List.of("Your bag can't take the reward. Free 1 slot in your Use tab, then talk to me again."),
                cm.oks, "the refusal names how many slots and which tab");
        assertEquals(0, elixirs(member), "no elixir was handed out");
        assertNull(cm.eim.getProperty("rewarded_1000001"), "the player is not latched, so they can ask again");
        assertEquals(-1, cm.warpTo, "a player whose bag is short is not warped out of the room");
        assertTrue(cm.disposed, "the talk is still closed");
    }

    @Test
    void anExactFitGetsTheElixirsIsLatchedAndLeaves() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS - 1);

        ExitCm cm = talk(member);

        assertEquals(GIVEN, elixirs(member), "the 100 Power Elixirs arrived");
        assertEquals(BossRewardBagTestSupport.SLOTS, member.occupied(InventoryType.USE), "and filled the last slot");
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"), "the latch is set only after the grant");
        assertTrue(cm.oks.isEmpty(), "a player with room is told nothing about a bag");
        assertEquals(EXIT, cm.warpTo, "the talker may leave");
    }

    @Test
    void roomToSpareGetsExactlyWhatItGetsToday() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        ExitCm cm = talk(member);

        assertEquals(GIVEN, elixirs(member));
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"));
        assertEquals("true", cm.eim.getProperty("rewarded"), "the instance-wide latch still ends up set");
        assertEquals(EXIT, cm.warpTo);
    }

    @Test
    void aStackWithRoomTakesTheRewardWithNoFreeSlotInTheTab() throws Exception {
        // 24 slots taken, one of them 500 Power Elixirs in one stack of 32000: the reward merges, so no free slot is
        // needed and the player must not be refused.
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);
        member.stack(InventoryType.USE, ELIXIRS, 500);
        member.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS - 1);
        assertEquals(BossRewardBagTestSupport.SLOTS, member.occupied(InventoryType.USE));

        ExitCm cm = talk(member);

        assertEquals(500 + GIVEN, elixirs(member), "the 100 elixirs merged into the stack the player carried");
        assertEquals(BossRewardBagTestSupport.SLOTS, member.occupied(InventoryType.USE), "no slot was needed");
        assertEquals("true", cm.eim.getProperty("rewarded_1000001"));
        assertTrue(cm.oks.isEmpty());
        assertEquals(EXIT, cm.warpTo);
    }

    @Test
    void twoPlayersOneFullTheOtherRewardedAndTheFullOneComesBackForItOnce() throws Exception {
        BossRewardBagTestSupport.Member full = new BossRewardBagTestSupport.Member(1_000_001, world);
        BossRewardBagTestSupport.Member roomy = new BossRewardBagTestSupport.Member(1_000_002, world);
        full.fill(InventoryType.USE, BossRewardBagTestSupport.SLOTS);

        ExitCm first = talk(full, roomy);

        assertEquals(-1, first.warpTo, "the full player stays in the room");
        assertNull(first.eim.getProperty("rewarded_1000001"), "and is not latched");
        assertEquals(GIVEN, elixirs(roomy), "the other player is rewarded anyway");
        assertEquals("true", first.eim.getProperty("rewarded_1000002"), "and latched");

        // The full player frees a slot and asks again: the same instance, the same room.
        full.bag(InventoryType.USE).removeItem((short) 1);
        ExitCm second = talkIn(first.eim, full);

        assertEquals(GIVEN, elixirs(full), "the player who was short gets the reward once");
        assertEquals("true", second.eim.getProperty("rewarded_1000001"));
        assertEquals(GIVEN, elixirs(roomy), "the player who already had it is not paid twice");
        assertEquals(EXIT, second.warpTo, "and now they may leave");
    }

    @Test
    void talkingTwiceWithRoomGivesTheRewardOnce() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        ExitCm first = talk(member);
        ExitCm again = talkIn(first.eim, member);

        assertEquals(GIVEN, elixirs(member), "the second talk adds nothing");
        assertEquals(1, member.bag(InventoryType.USE).listById(ELIXIRS).size(), "still one stack");
        assertTrue(again.oks.isEmpty(), "nothing to say about a bag");
        assertEquals(EXIT, again.warpTo);
    }

    @Test
    void anUnclearedRunGivesNothing() throws Exception {
        BossRewardBagTestSupport.Member member = new BossRewardBagTestSupport.Member(1_000_001, world);

        FakeEim eim = new FakeEim();
        eim.cleared = false;
        eim.players.add(member.chr);
        ExitCm cm = new ExitCm();
        cm.eim = eim;
        cm.player = member.chr;

        ScriptEngine engine = new Loader().load("npc/2041025.js");
        engine.put("cm", cm);
        Invocable npc = (Invocable) engine;
        npc.invokeFunction("start");
        npc.invokeFunction("action", 1, 0, 0);

        assertEquals(0, elixirs(member), "a run that is not clear pays nothing");
        assertFalse(cm.eim.props.containsKey("rewarded_1000001"));
        assertEquals(EXIT, cm.warpTo, "leaving still works");
    }
}
