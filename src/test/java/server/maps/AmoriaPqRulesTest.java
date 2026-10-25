package server.maps;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.Test;
import scripting.AbstractScriptManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Ticket 75: the Amorian Challenge (Amoria PQ) must be playable by 2 to 6 friends with no marriage, and by a party of a
 * single gender. These tests pin the stage maths that make it possible:
 *
 * <ul>
 *   <li>eligibility: no gender mask, no marriage, 2 to 6 players, bots never count;</li>
 *   <li>stages 2 and 3: the number of players that must stand on the platforms scales with the party instead of being
 *       hardcoded to 5, and the generated combination always matches that number (the stock base-3 generator could not
 *       be matched in ~79% of the draws);</li>
 *   <li>stage 4: the 50-item collection is pooled over the party and scaled down for small parties;</li>
 *   <li>stage 5: gate doors and the gathering area do not depend on the party size.</li>
 * </ul>
 */
class AmoriaPqRulesTest {

    private static final int AMORIA_STAGE2 = 670010300;
    private static final int AMORIA_STAGE4 = 670010500;

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** Stands in for EventInstanceManager: only what the stage NPC scripts touch. */
    public static final class FakeEim {
        private final List<Character> players = new ArrayList<>();
        private final Map<String, String> props = new HashMap<>();

        public List<Character> getPlayers() {
            return players;
        }

        public void setProperty(String key, String value) {
            props.put(key, value);
        }

        public String getProperty(String key) {
            return props.get(key);
        }

        public void setIntProperty(String key, int value) {
            props.put(key, String.valueOf(value));
        }

        public int getIntProperty(String key) {
            String value = props.get(key);
            return value == null ? 0 : Integer.parseInt(value);
        }

        public boolean isEventTeamTogether() {
            return true;
        }

        public void showClearEffect(boolean effect) {
        }

        public void showWrongEffect() {
        }

        public void linkToNextStage(int stage, String prefix, int mapId) {
        }

        public void mapMessage(int type, String message) {
        }
    }

    /** Stands in for NPCConversationManager: only what the stage NPC scripts touch. */
    public static final class FakeCm {
        private final Character player;
        private final MapleMap map;
        private final FakeEim eim;
        public String sent;
        public boolean disposed;
        public boolean leader = true;

        FakeCm(Character player, MapleMap map, FakeEim eim) {
            this.player = player;
            this.map = map;
            this.eim = eim;
        }

        public Character getPlayer() {
            return player;
        }

        public MapleMap getMap() {
            return map;
        }

        public int getMapId() {
            return AMORIA_STAGE2;
        }

        public FakeEim getEventInstance() {
            return eim;
        }

        public boolean isEventLeader() {
            return leader;
        }

        public boolean isLeader() {
            return leader;
        }

        public void sendNext(String text) {
            sent = text;
        }

        public void sendOk(String text) {
            sent = text;
        }

        public void mapMessage(int type, String text) {
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Character human(int x, int y) {
        Character chr = mock(Character.class);
        when(chr.isBot()).thenReturn(false);
        when(chr.getPosition()).thenReturn(new Point(x, y));
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }

    private static Character bot() {
        Character chr = mock(Character.class);
        when(chr.isBot()).thenReturn(true);
        return chr;
    }

    /** A map whose areas are horizontal bands: area i holds the points with y in [i * 10, i * 10 + 5]. */
    private static MapleMap mapWithAreas(int areas) {
        MapleMap map = mock(MapleMap.class);
        List<Rectangle> rects = new ArrayList<>();
        for (int i = 0; i < areas; i++) {
            rects.add(new Rectangle(0, i * 10, 1000, 5));
        }
        when(map.getAreas()).thenReturn(rects);
        when(map.getArea(anyInt())).thenAnswer(invocation -> rects.get(invocation.getArgument(0, Integer.class)));
        return map;
    }

    private static int slotsOf(int players) throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201044.js");
        assertNotNull(engine, "9201044.js evaluates");
        FakeEim eim = eimWith(players, 0);
        Invocable script = (Invocable) engine;
        assertEquals(players, number(script.invokeFunction("partySize", eim)), "every planted player is counted");
        return number(script.invokeFunction("comboSlots", eim));
    }

    private static int number(Object value) {
        return ((Number) value).intValue();
    }

    private static int[] parseCombo(String combo) {
        String[] parts = combo.split(",");
        int[] positions = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            positions[i] = Integer.parseInt(parts[i]);
        }
        return positions;
    }

    @Test
    void comboSlotsFollowThePartySizeAndCapAtFive() throws Exception {
        assertEquals(2, slotsOf(2), "2 players must stand on 2 platforms, not 5");
        assertEquals(3, slotsOf(3));
        assertEquals(5, slotsOf(5));
        assertEquals(5, slotsOf(6), "the puzzle has 5 platforms at most");
    }

    @Test
    void botsNeverTakeThePlaceOfAPlayer() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201044.js");
        FakeEim eim = new FakeEim();
        eim.getPlayers().add(human(0, 0));
        eim.getPlayers().add(human(0, 0));
        eim.getPlayers().add(bot());
        Invocable script = (Invocable) engine;

        assertEquals(2, number(script.invokeFunction("partySize", eim)));
        assertEquals(2, number(script.invokeFunction("comboSlots", eim)));
    }

    @Test
    void stage2CombinationAlwaysMatchesThePlayersThatCanStandOnIt() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201044.js");
        Invocable script = (Invocable) engine;

        for (int slots : new int[]{2, 3, 5}) {
            for (int draw = 0; draw < 500; draw++) {
                int[] positions = parseCombo((String) script.invokeFunction("generateCombo1", slots));
                assertEquals(9, positions.length, "stage 2 has 9 positions for 3 areas");
                int sum = 0;
                for (int i = 0; i < positions.length; i++) {
                    assertTrue(positions[i] >= 0 && positions[i] <= slots, "area " + i + " holds at most the whole party");
                    assertTrue(i < 3 || positions[i] == 0, "stage 2 only has 3 areas, area " + i + " must stay empty");
                    sum += positions[i];
                }
                assertEquals(slots, sum, "the combination must be reachable with " + slots + " players (stock generator could not be)");
            }
        }
    }

    @Test
    void stage3CombinationAlwaysMatchesThePlayersThatCanStandOnIt() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201044.js");
        Invocable script = (Invocable) engine;

        for (int slots : new int[]{2, 3, 5}) {
            for (int draw = 0; draw < 500; draw++) {
                int[] positions = parseCombo((String) script.invokeFunction("generateCombo2", slots));
                assertEquals(9, positions.length);
                int ones = 0;
                for (int position : positions) {
                    assertTrue(position == 0 || position == 1, "stage 3 wants one player per chosen platform");
                    ones += position;
                }
                assertEquals(slots, ones, "stage 3 must ask for exactly one player on " + slots + " platforms");
            }
        }
    }

    @Test
    void stage2AcceptsTwoPlayersStandingOnTheirOwnAreas() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201044.js");
        Invocable script = (Invocable) engine;

        FakeEim eim = new FakeEim();
        eim.getPlayers().add(human(0, 0));    // area 0
        eim.getPlayers().add(human(0, 12));   // area 1
        eim.getPlayers().add(bot());          // never counted
        int[] objset = new int[9];

        assertEquals(2, number(script.invokeFunction("comboOccupancy", objset, eim, mapWithAreas(3))));
        assertEquals(1, objset[0]);
        assertEquals(1, objset[1]);
        assertEquals(0, objset[2]);
        assertEquals(2, number(script.invokeFunction("comboSlots", eim)), "so the two of them open the gate, at 2 players");
    }

    @Test
    void stage3HandlesSixPlayersOverNineAreas() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201044.js");
        Invocable script = (Invocable) engine;

        FakeEim eim = new FakeEim();
        for (int i = 0; i < 6; i++) {
            eim.getPlayers().add(human(0, i * 10));    // one player per area, 0..5
        }
        int[] objset = new int[9];

        assertEquals(6, number(script.invokeFunction("comboOccupancy", objset, eim, mapWithAreas(9))));
        assertEquals(5, number(script.invokeFunction("comboSlots", eim)), "6 players still only need 5 platforms");
        for (int i = 0; i < 6; i++) {
            assertEquals(1, objset[i]);
        }
    }

    @Test
    void stage4ItemCountScalesDownForSmallParties() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201045.js");
        assertNotNull(engine, "9201045.js evaluates");
        Invocable script = (Invocable) engine;

        assertEquals(50, number(script.invokeFunction("stageItemRequirement", eimWith(6, 0))), "6 players keep the stock 50");
        assertEquals(25, number(script.invokeFunction("stageItemRequirement", eimWith(3, 0))));
        assertEquals(17, number(script.invokeFunction("stageItemRequirement", eimWith(2, 0))), "2 players farm 17");
        assertEquals(17, number(script.invokeFunction("stageItemRequirement", eimWith(2, 3))), "bots never add to the chore");
    }

    private static FakeEim eimWith(int humans, int bots) {
        FakeEim eim = new FakeEim();
        for (int i = 0; i < humans; i++) {
            eim.getPlayers().add(human(0, 0));
        }
        for (int i = 0; i < bots; i++) {
            eim.getPlayers().add(bot());
        }
        return eim;
    }

    @Test
    void stage4CountsAndTakesItemsFromTheWholeParty() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201045.js");
        Invocable script = (Invocable) engine;

        Character first = human(0, 0);
        Character second = human(0, 0);
        when(first.countItem(4031597)).thenReturn(12);
        when(second.countItem(4031597)).thenReturn(5);

        FakeEim eim = new FakeEim();
        eim.getPlayers().add(first);
        eim.getPlayers().add(second);
        eim.getPlayers().add(bot());

        assertEquals(17, number(script.invokeFunction("countPartyItems", eim, 4031597)), "the party pools its items");

        Inventory firstInv = mock(Inventory.class);
        Inventory secondInv = mock(Inventory.class);
        when(first.getInventory(InventoryType.ETC)).thenReturn(firstInv);
        when(second.getInventory(InventoryType.ETC)).thenReturn(secondInv);

        Item firstStack = mock(Item.class);
        when(firstStack.getPosition()).thenReturn((short) 3);
        when(firstStack.getQuantity()).thenReturn((short) 12);
        Item secondStack = mock(Item.class);
        when(secondStack.getPosition()).thenReturn((short) 7);
        when(secondStack.getQuantity()).thenReturn((short) 5);
        when(firstInv.listById(4031597)).thenReturn(List.of(firstStack));
        when(secondInv.listById(4031597)).thenReturn(List.of(secondStack));

        script.invokeFunction("takePartyItems", eim, 4031597, 17);

        verify(firstInv).removeItem((short) 3, (short) 12, false);
        verify(secondInv).removeItem((short) 7, (short) 5, false);
    }

    @Test
    void stage4StopsTakingOnceTheRequirementIsMet() throws Exception {
        ScriptEngine engine = new Loader().load("npc/9201045.js");
        Invocable script = (Invocable) engine;

        Character holder = human(0, 0);
        FakeEim eim = new FakeEim();
        eim.getPlayers().add(holder);

        Inventory inv = mock(Inventory.class);
        when(holder.getInventory(InventoryType.ETC)).thenReturn(inv);
        Item stack = mock(Item.class);
        when(stack.getPosition()).thenReturn((short) 2);
        when(stack.getQuantity()).thenReturn((short) 40);
        Item untouched = mock(Item.class);
        when(untouched.getPosition()).thenReturn((short) 5);
        when(untouched.getQuantity()).thenReturn((short) 9);
        when(inv.listById(4031597)).thenReturn(List.of(stack, untouched));

        script.invokeFunction("takePartyItems", eim, 4031597, 9);   // one player: the whole chore is 9

        verify(inv).removeItem((short) 2, (short) 9, false);
        verify(inv, never()).removeItem((short) 5, (short) 9, false);
    }

    @Test
    void eligibilityAcceptsTwoUnmarriedPlayersOfTheSameGender() throws Exception {
        ScriptEngine engine = new Loader().load("event/AmoriaPQ.js");
        assertNotNull(engine, "AmoriaPQ.js evaluates");
        assertEquals(2, number(engine.get("minPlayers")), "ticket 75: two friends are enough");
        assertEquals(6, number(engine.get("maxPlayers")));
        assertFalse((Boolean) engine.get("onlyMarriedPlayers"), "ticket 75: no marriage needed");

        Object[] team = eligible(engine, new Object[][]{{0, 40, true, false}, {0, 45, false, false}});
        assertEquals(2, team.length, "two unmarried players of one gender are a team");
    }

    @Test
    void eligibilityStillRejectsTooFewPlayers() throws Exception {
        ScriptEngine engine = new Loader().load("event/AmoriaPQ.js");
        assertEquals(0, eligible(engine, new Object[][]{{0, 40, true, false}}).length, "one player cannot run the challenge");
    }

    @Test
    void eligibilityRejectsPartiesOverSixPlayers() throws Exception {
        ScriptEngine engine = new Loader().load("event/AmoriaPQ.js");
        Object[][] members = new Object[7][];
        for (int i = 0; i < 7; i++) {
            members[i] = new Object[]{i % 2, 40 + i, i == 0, false};
        }
        assertEquals(0, eligible(engine, members).length);
        assertEquals(6, eligible(engine, java.util.Arrays.copyOf(members, 6)).length, "six players fit");
    }

    @Test
    void eligibilitySkipsBotsAndPlayersOutOfLevelRange() throws Exception {
        ScriptEngine engine = new Loader().load("event/AmoriaPQ.js");

        Object[] team = eligible(engine, new Object[][]{{1, 40, true, false}, {1, 50, false, false}, {1, 60, false, true}});
        assertEquals(2, team.length, "a bot is not a party member for the challenge");

        assertEquals(0, eligible(engine, new Object[][]{{0, 40, true, false}, {0, 39, false, false}}).length, "level 39 is out");
        assertEquals(2, eligible(engine, new Object[][]{{0, 40, true, false}, {0, 255, false, false}}).length, "level 255 is in");
    }

    /**
     * Ticket 75: stage 1 keeps one room per gender but its clear condition is "no mob left on the map", so a party of
     * one gender has to get into the other room too. Both doors now warp whoever uses them.
     */
    @Test
    void aSingleGenderPartyCanUseBothStageOneDoors() throws Exception {
        for (int gender = 0; gender < 2; gender++) {
            assertDoor(gender, "female00", "female01");
            assertDoor(gender, "male00", "male01");
        }
    }

    private static void assertDoor(int gender, String door, String roomPortal) throws Exception {
        ScriptEngine engine = new Loader().load("portal/gendergo.js");
        assertNotNull(engine, "gendergo.js evaluates");

        MapleMap map = mock(MapleMap.class);
        when(map.getId()).thenReturn(670010200);

        Character chr = human(0, 0);
        when(chr.getGender()).thenReturn(gender);
        when(chr.getMap()).thenReturn(map);

        Portal portal = mock(Portal.class);
        when(portal.getName()).thenReturn(door);

        scripting.portal.PortalPlayerInteraction pi = mock(scripting.portal.PortalPlayerInteraction.class);
        when(pi.getPlayer()).thenReturn(chr);
        when(pi.getPortal()).thenReturn(portal);

        Object entered = ((Invocable) engine).invokeFunction("enter", pi);
        assertTrue((Boolean) entered, "the " + door + " door lets gender " + gender + " through");
        verify(pi).playPortalSound();
        verify(pi).warp(670010200, roomPortal);
    }

    /**
     * Builds a party list the way EventManager does (party.getPartyMembersOnline()) and runs the event script's
     * eligibility filter over it. Each member is {gender, level, isLeader, isBot}; all live on the recruit map.
     */
    private static Object[] eligible(ScriptEngine engine, Object[][] members) throws Exception {
        List<net.server.world.PartyCharacter> party = new ArrayList<>();
        for (Object[] member : members) {
            boolean isBot = (Boolean) member[3];
            Character chr = isBot ? bot() : human(0, 0);
            when(chr.getGender()).thenReturn((Integer) member[0]);
            when(chr.getLevel()).thenReturn((Integer) member[1]);
            when(chr.isMarried()).thenReturn(false);

            net.server.world.PartyCharacter partyChr = mock(net.server.world.PartyCharacter.class);
            when(partyChr.getPlayer()).thenReturn(chr);
            when(partyChr.getLevel()).thenReturn((Integer) member[1]);
            when(partyChr.getMapId()).thenReturn(670010100);
            when(partyChr.isLeader()).thenReturn((Boolean) member[2]);
            party.add(partyChr);
        }

        Object result = ((Invocable) engine).invokeFunction("getEligibleParty", party);
        return (Object[]) result;
    }
}
