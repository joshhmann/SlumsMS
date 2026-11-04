package soloMapling.companions;

import client.Character;
import client.Client;
import client.Ring;
import client.inventory.Equip;
import client.inventory.manipulator.InventoryManipulator;
import net.server.channel.handlers.RingActionHandler;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Ticket 189 stage 2 (B2): a player marries their own out alt at John with no ceremony, behind
 * {@code -Dslums.ownedalts.marry}. Off, another player's alt and population bots: the old refusals.
 */
class OwnedAltWeddingTest {
    private String before;

    @BeforeEach
    void off() {
        before = System.getProperty(OwnedAltWedding.SWITCH);
        System.clearProperty(OwnedAltWedding.SWITCH);
    }

    @AfterEach
    void restore() {
        if (before == null) System.clearProperty(OwnedAltWedding.SWITCH);
        else System.setProperty(OwnedAltWedding.SWITCH, before);
    }

    private static Character owner() {
        Character o = mock(Character.class);
        when(o.getId()).thenReturn(1);
        when(o.getName()).thenReturn("Owner");
        when(o.getLevel()).thenReturn(60);
        return o;
    }

    private static Character altOf(Character owner, MapleMap map) {
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(22);
        when(a.getName()).thenReturn("Second");
        when(a.getLevel()).thenReturn(55);
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getOwnedAltOwner()).thenReturn(owner);
        when(a.getMap()).thenReturn(map);
        return a;
    }

    @Test
    void acceptsOnlyTheOwnersOwnOutAltWithTheSwitchOn() {
        MapleMap map = mock(MapleMap.class);
        Character owner = owner();
        when(owner.getMap()).thenReturn(map);
        Character alt = altOf(owner, map);
        assertFalse(OwnedAltWedding.accepts(owner, alt), "switch off");
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        assertTrue(OwnedAltWedding.accepts(owner, alt));
        Character stranger = owner();
        when(stranger.getId()).thenReturn(9);
        assertFalse(OwnedAltWedding.accepts(stranger, alt), "another player's alt");
        Character bot = mock(Character.class);
        when(bot.getLevel()).thenReturn(80);
        when(bot.getMap()).thenReturn(map);
        assertFalse(OwnedAltWedding.accepts(owner, bot), "a population bot");
        when(alt.getLevel()).thenReturn(49);
        assertFalse(OwnedAltWedding.accepts(owner, alt), "level");
        when(alt.getLevel()).thenReturn(55);
        when(alt.getMap()).thenReturn(mock(MapleMap.class));
        assertFalse(OwnedAltWedding.accepts(owner, alt), "other map");
        when(alt.getMap()).thenReturn(map);
        when(alt.isOwnedAltClosing()).thenReturn(true);
        assertFalse(OwnedAltWedding.accepts(owner, alt), "going home");
    }

    // ---- proposals (mode 0) ----

    private record Proposal(Character source, Character target, World world) {
    }

    private static Proposal proposal(boolean ownAlt) {
        MapleMap map = mock(MapleMap.class);
        Client c = mock(Client.class, RETURNS_DEEP_STUBS);
        World world = mock(World.class);
        when(c.getWorldServer()).thenReturn(world);
        Character source = owner();
        when(source.getMap()).thenReturn(map);
        when(source.getClient()).thenReturn(c);
        when(c.getPlayer()).thenReturn(source);
        when(source.haveItem(anyInt())).thenReturn(true);
        when(source.isAlive()).thenReturn(true);
        java.util.concurrent.atomic.AtomicInteger item = new java.util.concurrent.atomic.AtomicInteger();
        when(source.getMarriageItemId()).thenAnswer(i -> item.get());
        doAnswer(i -> { item.set(i.getArgument(0)); return null; }).when(source).setMarriageItemId(anyInt());
        Character other = owner();
        when(other.getId()).thenReturn(9);
        Character target = altOf(ownAlt ? source : other, map);
        when(target.getClient()).thenReturn(c);
        when(target.isAlive()).thenReturn(true);
        when(c.getChannelServer().getPlayerStorage().getCharacterByName("Second")).thenReturn(target);
        return new Proposal(source, target, world);
    }

    private static void propose(Proposal p) {
        int box = constants.id.ItemId.ENGAGEMENT_BOX_MOONSTONE;
        try (MockedStatic<InventoryManipulator> inv = mockStatic(InventoryManipulator.class)) {
            inv.when(() -> InventoryManipulator.checkSpace(any(Client.class), anyInt(), anyInt(), any())).thenReturn(true);
            inv.when(() -> InventoryManipulator.checkSpace(any(Character.class), anyInt(), anyInt(), any())).thenReturn(true);
            RingActionHandler.sendEngageProposal(p.source.getClient(), "Second", box);
        }
    }

    @Test
    void switchOnTheOwnerEngagesTheirOwnAlt() {
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        Proposal p = proposal(true);
        when(p.world.createRelationship(1, 22)).thenReturn(5);
        propose(p);
        verify(p.world).createRelationship(1, 22);
        verify(p.source).setPartnerId(22);
        verify(p.target).setPartnerId(1);
        int token = constants.id.ItemId.EMPTY_ENGAGEMENT_BOX_MOONSTONE;
        verify(p.target).setMarriageItemId(token + 1);
    }

    @Test
    void switchOffTheOldRefusalStands() {
        Proposal p = proposal(true);
        propose(p);
        verify(p.world, never()).createRelationship(anyInt(), anyInt());
        verify(p.source).dropMessage(eq(1), contains("is your alt"));
    }

    @Test
    void anotherPlayersAltIsRefusedEvenWithTheSwitchOn() {
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        Proposal p = proposal(false);
        propose(p);
        verify(p.world, never()).createRelationship(anyInt(), anyInt());
        verify(p.source).dropMessage(eq(1), contains("traveling with its owner"));
    }

    // ---- marry at John ----

    private static final class Fake extends OwnedAltWedding.Ops {
        final List<String> log = new ArrayList<>();
        final Character alt;
        boolean ownerRoom = true, altRoom = true, ownerDeliver = true, altDeliver = true;
        Pair<Integer, Integer> rings = new Pair<>(100, 101);

        Fake(Character alt) {
            this.alt = alt;
        }

        @Override Character partner(Character owner) { return alt; }
        @Override boolean hasRoom(Character c, int item) { return c == alt ? altRoom : ownerRoom; }
        @Override Equip newRing(int item) { return mock(Equip.class); }
        @Override Pair<Integer, Integer> createRings(int item, Character o, Character a) { log.add("createRings"); return rings; }
        @Override boolean deliver(Character c, Equip ring) { log.add(c == alt ? "deliverAlt" : "deliverOwner"); return c == alt ? altDeliver : ownerDeliver; }
        @Override void take(Character c, int item) { log.add("takeOwnerRing"); }
        @Override void deleteRings(Pair<Integer, Integer> r, Character a, int item) { log.add("deleteRings"); }
        @Override void removeToken(Character c, int item) { log.add((c == alt ? "tokenAlt" : "tokenOwner") + item); }
        @Override void removeFee(Character c) { log.add("fee"); }
        @Override void bond(Character o, Character a, int item, Pair<Integer, Integer> r) { log.add("bond"); }
    }

    private static Character[] couple() {
        MapleMap map = mock(MapleMap.class);
        Character owner = owner();
        Character alt = altOf(owner, map);
        for (Character c : new Character[]{owner, alt}) {
            when(c.getMapId()).thenReturn(constants.id.MapId.AMORIA);
            when(c.isAlive()).thenReturn(true);
        }
        when(owner.getPartnerId()).thenReturn(22);
        when(alt.getPartnerId()).thenReturn(1);
        when(owner.getMarriageItemId()).thenReturn(4031357);
        when(owner.haveItem(4031357)).thenReturn(true);
        when(alt.haveItem(4031358)).thenReturn(true);
        when(owner.haveItem(OwnedAltWedding.FEE_TICKET)).thenReturn(true);
        return new Character[]{owner, alt};
    }

    @Test
    void marryGivesBothARingTakesBothTokensAndOneTicket() {
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        Character[] c = couple();
        Fake ops = new Fake(c[1]);
        assertNull(OwnedAltWedding.marry(c[0], ops));
        assertEquals(List.of("createRings", "deliverOwner", "deliverAlt", "tokenOwner4031357", "tokenAlt4031358", "fee", "bond"), ops.log);
    }

    @Test
    void aRingWriteFailureLosesNothingAndChargesNothing() {
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        Character[] c = couple();
        Fake ops = new Fake(c[1]);
        ops.rings = new Pair<>(-1, -1);
        assertNotNull(OwnedAltWedding.marry(c[0], ops));
        assertEquals(List.of("createRings"), ops.log);
    }

    @Test
    void aFailedDeliveryRollsTheRingsBackWithoutTokenOrFeeLoss() {
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        Character[] c = couple();
        Fake ops = new Fake(c[1]);
        ops.altDeliver = false;
        assertNotNull(OwnedAltWedding.marry(c[0], ops));
        assertEquals(List.of("createRings", "deliverOwner", "deliverAlt", "takeOwnerRing", "deleteRings"), ops.log);
        Fake ops2 = new Fake(c[1]);
        ops2.ownerDeliver = false;
        assertNotNull(OwnedAltWedding.marry(c[0], ops2));
        assertEquals(List.of("createRings", "deliverOwner", "deleteRings"), ops2.log);
    }

    @Test
    void aFullEquipBagRefusesWithNoStateChange() {
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        Character[] c = couple();
        Fake ops = new Fake(c[1]);
        ops.altRoom = false;
        assertTrue(OwnedAltWedding.marry(c[0], ops).contains("alt's equip"));
        assertEquals(List.of(), ops.log);
    }

    @Test
    void noTicketNoSwitchOrWrongPlaceRefuseCleanly() {
        Character[] c = couple();
        Fake ops = new Fake(c[1]);
        assertNotNull(OwnedAltWedding.marry(c[0], ops), "switch off");
        System.setProperty(OwnedAltWedding.SWITCH, "true");
        when(c[0].haveItem(OwnedAltWedding.FEE_TICKET)).thenReturn(false);
        assertNotNull(OwnedAltWedding.marry(c[0], ops), "no ticket");
        when(c[0].haveItem(OwnedAltWedding.FEE_TICKET)).thenReturn(true);
        when(c[1].getMapId()).thenReturn(100000000);
        assertNotNull(OwnedAltWedding.marry(c[0], ops), "alt not in Amoria");
        assertEquals(List.of(), ops.log);
    }

    private static void withBags(Character... chars) {
        for (Character c : chars) {
            when(c.getInventory(any(client.inventory.InventoryType.class))).thenReturn(mock(client.inventory.Inventory.class));
        }
    }

    // ---- divorce with the alt out and away ----

    @Test
    void divorceWithTheAltOnlineClearsBothSides() {
        Character[] c = couple();
        Character owner = c[0], alt = c[1];
        Client oc = mock(Client.class, RETURNS_DEEP_STUBS);
        World world = mock(World.class, RETURNS_DEEP_STUBS);
        when(owner.getClient()).thenReturn(oc);
        when(oc.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage().getCharacterById(22)).thenReturn(alt);
        Ring ring = mock(Ring.class);
        withBags(owner, alt);
        when(owner.getMarriageRing()).thenReturn(ring);
        try (MockedStatic<Ring> rings = mockStatic(Ring.class)) {
            RingActionHandler.breakMarriageRecord(owner);
            rings.verify(() -> Ring.removeRing(ring));
        }
        verify(world).deleteRelationship(1, 22);
        verify(owner).setPartnerId(-1);
        verify(alt).setPartnerId(-1);
        verify(alt).addMarriageRing(null);
    }

    @Test
    void divorceWithTheAltAwayErasesItsRowInTheDatabase() throws Exception {
        Character[] c = couple();
        Character owner = c[0];
        Client oc = mock(Client.class, RETURNS_DEEP_STUBS);
        World world = mock(World.class, RETURNS_DEEP_STUBS);
        when(owner.getClient()).thenReturn(oc);
        when(oc.getWorldServer()).thenReturn(world);
        when(world.getPlayerStorage().getCharacterById(22)).thenReturn(null);
        withBags(owner);
        when(owner.getMarriageRing()).thenReturn(mock(Ring.class));
        Connection con = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        when(con.prepareStatement(contains("UPDATE characters SET marriageItemId=-1, partnerId=-1"))).thenReturn(ps);
        try (MockedStatic<Ring> rings = mockStatic(Ring.class);
             MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class)) {
            db.when(DatabaseConnection::getConnection).thenReturn(con);
            RingActionHandler.breakMarriageRecord(owner);
            rings.verify(() -> Ring.removeRing(any()));
        }
        verify(ps).setInt(1, 22);
        verify(ps).executeUpdate();
        verify(world).deleteRelationship(1, 22);
        verify(owner).setPartnerId(-1);
    }
}
