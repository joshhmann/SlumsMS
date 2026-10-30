package soloMapling.companions;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import server.life.Monster;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 ticket 13 (owner 2026-10-28: "if they don't fight or anything I'm not sure if we should have shipped it"): the
 * companion driver makes a summoned alt fight with its owner through the bots' own attack system, loot into its own
 * bag, take real hits (its own potions) and die like a player, and stop when its owner idles.
 */
class OwnedAltCompanionTest {

    static final long NOW = 90_000_000L;
    static final int OWNER = 1, ALT = 2;

    private MockedStatic<GCMovement> movement;
    private MockedStatic<BotAttackDriver> attacks;
    private MockedStatic<OwnedAltService> services;
    private OwnedAltService service;
    private MapleMap field;
    private final List<Monster> mobs = new ArrayList<>();

    @BeforeEach
    void stage() {
        movement = Mockito.mockStatic(GCMovement.class);
        attacks = Mockito.mockStatic(BotAttackDriver.class, Mockito.CALLS_REAL_METHODS);
        attacks.when(() -> BotAttackDriver.attackReachX(any())).thenReturn(100);
        attacks.when(() -> BotAttackDriver.attackReachY(any())).thenReturn(60);
        attacks.when(() -> BotAttackDriver.botAttack(any())).thenReturn(
                new BotAttackDriver.AttackResult(true, "Snail", 37, false, null, 0));
        service = mock(OwnedAltService.class);
        services = Mockito.mockStatic(OwnedAltService.class);
        services.when(OwnedAltService::getInstance).thenReturn(service);
        field = mock(MapleMap.class);
        when(field.getAllMonsters()).thenReturn(mobs);
        when(field.getCharacters()).thenReturn(List.of());
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>());
        when(field.getReturnMapId()).thenReturn(100_000_000);
        OwnedAltLoot.room = (a, d) -> true;
    }

    @AfterEach
    void unstage() throws Exception {
        movement.close();
        attacks.close();
        services.close();
        OwnedAltGate.forceIdleForQa(OWNER, false);
        OwnedAltLoot.room = OwnedAltLoot.REAL_ROOM;
        OwnedAltCombat.forget(OWNER);
        OwnedAltCombat.forget(ALT);
        lying().remove(ALT);
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Long> lying() throws Exception {
        Field f = BotDeaths.class.getDeclaredField("lyingUntil");
        f.setAccessible(true);
        return (Map<Integer, Long>) f.get(null);
    }

    Character owner(long lastAction) {
        Character o = mock(Character.class);
        when(o.getId()).thenReturn(OWNER);
        when(o.getName()).thenReturn("Main");
        when(o.isLoggedinWorld()).thenReturn(true);
        when(o.getMap()).thenReturn(field);
        when(o.getMapId()).thenReturn(104_040_000);
        when(o.getPosition()).thenReturn(new Point(0, 0));
        Client c = mock(Client.class);
        when(c.getLastPlayerAction()).thenReturn(lastAction);
        when(o.getClient()).thenReturn(c);
        return o;
    }

    Character alt(Character owner, int weaponId) {
        Character a = mock(Character.class);
        when(a.getId()).thenReturn(ALT);
        when(a.getName()).thenReturn("Second");
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getOwnedAltOwner()).thenReturn(owner);
        when(a.getMap()).thenReturn(field);
        when(a.getPosition()).thenReturn(new Point(10, 0));
        when(a.getHp()).thenReturn(50);
        Inventory worn = mock(Inventory.class);
        when(worn.getItem((short) -11)).thenReturn(weaponId > 0 ? new Item(weaponId, (short) -11, (short) 1) : null);
        when(a.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        return a;
    }

    Monster mob(int x, int damagedBy) {
        Monster m = mock(Monster.class);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, 0));
        when(m.getObjectId()).thenReturn(500 + x);
        when(m.getName()).thenReturn("Snail");
        Map<Integer, Long> dealt = new HashMap<>();
        if (damagedBy > 0) {
            dealt.put(damagedBy, 5L);
            when(m.lastHitBy(damagedBy)).thenReturn(NOW - 1_000);
        }
        when(m.damageSnapshot()).thenReturn(dealt);
        mobs.add(m);
        return m;
    }

    @Test
    void itAttacksTheMobItsOwnerHitsWithTheBotAttackSystem() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        Monster m = mob(50, OWNER);
        movement.when(() -> GCMovement.isFollowing(alt)).thenReturn(true);
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        assertTrue(s.fighting);
        assertEquals(1, s.swings.get());
        assertEquals(37, s.damageDealt.get());
        movement.verify(() -> GCMovement.stop(alt)); // off the follow leash while it fights
        attacks.verify(() -> BotAttackDriver.botAttack(alt));
        attacks.verify(() -> BotAttackDriver.setTargetFilter(eq(ALT), any()));
        assertTrue(OwnedAltCombat.mayHit(m, field, OwnedAltCombat.partyIds(alt, owner)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void itsAttackDriverFacesOnlyThePartysFight() {
        // t-oa-1 (TEST 2026-10-29): the filter let the driver face a nearer quiet wild mob instead of the alt's target
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        Monster quiet = mob(30, 0);
        Monster owners = mob(60, OWNER);
        OwnedAltCompanion.tick(owner, alt, new OwnedAltCompanion.State(ALT), NOW, 0);
        org.mockito.ArgumentCaptor<java.util.function.Predicate<Monster>> filter =
                org.mockito.ArgumentCaptor.forClass(java.util.function.Predicate.class);
        attacks.verify(() -> BotAttackDriver.setTargetFilter(eq(ALT), filter.capture()));
        assertFalse(filter.getValue().test(quiet), "a mob nobody in the party touched");
        assertTrue(filter.getValue().test(owners));
    }

    @Test
    void outOfReachItWalksToTheMobFirst() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        mob(400, OWNER);
        // flat ground everywhere at y 0
        movement.when(() -> GCMovement.groundPointBelow(any(), anyInt(), anyInt()))
                .thenAnswer(inv -> new Point(inv.getArgument(1, Integer.class), 0));
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        // 1.6.2: the grind brain's approach (Approach): reach 100 x 0.8 = 80, so it walks to its stand band past the
        // mob's body (20 px half width + 22) and inside that reach - not the old "reach / 2 on the near side"
        org.mockito.ArgumentCaptor<Integer> x = org.mockito.ArgumentCaptor.forClass(Integer.class);
        movement.verify(() -> GCMovement.move(eq(alt), x.capture(), eq(0)));
        int dx = Math.abs(x.getValue() - 400);
        assertTrue(dx >= 42 && dx <= 72, "stands " + dx + " px off the mob");
        assertEquals(x.getValue(), s.lastMoveX);
        attacks.verify(() -> BotAttackDriver.botAttack(alt), never());
        // the shared walk is not re-issued while it is still heading there
        movement.when(() -> GCMovement.isMoving(alt)).thenReturn(true);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 200, 0);
        movement.verify(() -> GCMovement.move(eq(alt), anyInt(), anyInt()), times(1));
    }

    @Test
    void aMobItCannotLandAHitOnIsLeftAloneAndItLootsInstead() {
        // QA run 3 (TEST 2026-10-29): the alt kept at a mob it never hit, so it neither looted nor took a new fight
        attacks.when(() -> BotAttackDriver.botAttack(any())).thenReturn(
                new BotAttackDriver.AttackResult(false, null, 0, false, "no targetable mobs", 0));
        Character owner = owner(NOW + 60_000);
        Character alt = alt(owner, 1302000);
        Monster m = mob(50, OWNER);
        when(m.lastHitBy(OWNER)).thenReturn(NOW + 59_000);
        MapItem drop = mock(MapItem.class);
        when(drop.getOwnerId()).thenReturn(OWNER);
        when(drop.getPosition()).thenReturn(new Point(30, 0));
        when(drop.getDropTime()).thenReturn(NOW - 5_000);
        when(drop.getItem()).thenReturn(new Item(4000000, (short) 0, (short) 1));
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>(List.of(drop)));
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 60_000, 0);
        assertTrue(s.fighting);
        verify(alt, never()).pickupItem(any());
        OwnedAltCompanion.tick(owner, alt, s, NOW + 60_000 + OwnedAltCompanion.STALL_MS + 1, 0);
        assertTrue(s.skip.containsKey(m.getObjectId()), s.doing + " gate " + s.gate);
        assertTrue(s.doing.startsWith("gave up on"), s.doing);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 60_000 + OwnedAltCompanion.STALL_MS + 300, 0);
        verify(alt).pickupItem(drop);
        OwnedAltCompanion.tick(owner, alt, s,
                NOW + 60_000 + OwnedAltCompanion.STALL_MS + OwnedAltCompanion.GIVE_UP_MS + 1_000, 0);
        assertFalse(s.skip.containsKey(m.getObjectId()), "it tries again later");
    }

    @Test
    void anIdleOwnersAltStopsFightingFollowsAndTheOwnerHearsOnce() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        mob(50, OWNER);
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        assertTrue(s.fighting);
        OwnedAltGate.forceIdleForQa(OWNER, true);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 200, 0);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 400, 0);
        assertFalse(s.fighting);
        assertEquals(OwnedAltGate.Why.OWNER_IDLE, s.gate);
        assertEquals(1, s.swings.get(), "no swing while its owner idles");
        verify(owner, times(1)).yellowMessage(OwnedAltGate.Why.OWNER_IDLE.tell);
        movement.verify(() -> GCMovement.follow(alt, owner), Mockito.atLeastOnce());
        OwnedAltGate.forceIdleForQa(OWNER, false); // the owner moves again: it joins in again
        OwnedAltCompanion.tick(owner, alt, s, NOW + 600, 0);
        assertTrue(s.fighting);
        assertEquals(2, s.swings.get());
    }

    @Test
    void neverInATown() {
        when(field.isTown()).thenReturn(true);
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        mob(50, OWNER);
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        assertFalse(s.fighting);
        attacks.verify(() -> BotAttackDriver.botAttack(alt), never());
    }

    @Test
    void withoutAWeaponItCannotFightAndSaysSoOnce() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 0);
        mob(50, OWNER);
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 200, 0);
        assertFalse(s.fighting);
        verify(owner, times(1)).sendPacket(any());
        attacks.verify(() -> BotAttackDriver.botAttack(alt), never());
        Character undrawable = alt(owner, BotWeapons.UNDRAWABLE.iterator().next());
        assertTrue(OwnedAltCompanion.weaponProblem(undrawable).contains("can't fight with this weapon"),
                "a weapon a viewer's client cannot draw would close it: no swing");
    }

    @Test
    void itPicksUpThePartysDropIntoItsOwnBag() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        MapItem drop = mock(MapItem.class);
        when(drop.getOwnerId()).thenReturn(OWNER);
        when(drop.getPosition()).thenReturn(new Point(30, 0));
        when(drop.getDropTime()).thenReturn(NOW - 5_000);
        when(drop.getItem()).thenReturn(new Item(4000000, (short) 0, (short) 1));
        when(drop.isPickedUp()).thenReturn(false, true);
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>(List.of(drop)));
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        verify(alt).pickupItem(drop); // the player pickup path: its own inventory, the party meso split
        assertEquals(1, s.loots.get());
    }

    @Test
    void aFullBagIsSaidOnce() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        MapItem drop = mock(MapItem.class);
        when(drop.getOwnerId()).thenReturn(ALT);
        when(drop.getPosition()).thenReturn(new Point(30, 0));
        when(drop.getDropTime()).thenReturn(NOW - 5_000);
        when(drop.getItem()).thenReturn(new Item(4000000, (short) 0, (short) 1));
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>(List.of(drop)));
        OwnedAltLoot.room = (a, d) -> false;
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        for (int i = 0; i < 4; i++) {
            OwnedAltCompanion.tick(owner, alt, s, NOW + i * 300L, 0);
        }
        verify(alt, never()).pickupItem(any());
        verify(owner, times(1)).sendPacket(any());
        assertTrue(s.toldFull.contains(InventoryType.ETC));
    }

    @Test
    void aDeadAltGetsUpInTownAndGoesHomeAndItsOwnerIsTold() throws Exception {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        lying().put(ALT, 0L); // lain long enough
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        verify(alt).respawn(100_000_000);
        verify(service).sendHomeAfterDeath(eq(alt), contains("died and lost EXP"));
        assertTrue(s.gone);
        OwnedAltCompanion.tick(owner, alt, s, NOW + 200, 0);
        verify(service, times(1)).sendHomeAfterDeath(any(), anyString());
    }

    @Test
    void anAltLeftBehindCatchesUpAfterAFewSeconds() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        MapleMap elsewhere = mock(MapleMap.class);
        when(alt.getMap()).thenReturn(elsewhere);
        when(service.catchUp(owner, alt)).thenReturn(true);
        OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
        movement.when(() -> GCMovement.isTraveling(alt)).thenReturn(true);
        OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
        OwnedAltCompanion.tick(owner, alt, s, NOW + OwnedAltCompanion.CATCHUP_STUCK_MS + 1, 0);
        verify(service, never()).catchUp(any(), any()); // still walking there
        movement.when(() -> GCMovement.isTraveling(alt)).thenReturn(false);
        OwnedAltCompanion.tick(owner, alt, s, NOW + OwnedAltCompanion.CATCHUP_STUCK_MS + 2, 0);
        verify(service).catchUp(owner, alt);
    }

    // ── damage and death (BotVitals / BotDeaths with a real character) ─────────────────────────────────

    static Character realAlt(int maxHp) throws Exception {
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(OWNER);
        when(owner.getAccountID()).thenReturn(7);
        when(owner.getWorld()).thenReturn(1);
        BotClient c = new BotClient(1, 1);
        c.setAccID(7);
        Character alt = Character.getDefault(c);
        alt.setID(ALT + 40);
        alt.setWorld(1);
        c.setPlayer(alt);
        alt.markOwnedAlt(owner);
        for (String f : new String[]{"maxhp", "localmaxhp", "clientmaxhp"}) {
            setInt(alt, f, maxHp);
        }
        alt.updateHpMp(maxHp, 10);
        return alt;
    }

    static void setInt(Object target, String name, int value) throws Exception {
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field f = type.getDeclaredField(name);
                f.setAccessible(true);
                f.setInt(target, value);
                return;
            } catch (NoSuchFieldException e) {
                // look further up
            }
        }
        throw new NoSuchFieldException(name);
    }

    @Test
    void aMobsHitComesOffTheAltsHpAndALethalOneKillsIt() throws Exception {
        Character alt = realAlt(100);
        try {
            BotVitals.hurt(alt, 30);
            assertEquals(70, alt.getHp());
            assertFalse(BotDeaths.isDead(alt.getId()));
            BotVitals.hurt(alt, 500);
            // the game's own death path (HP to 0, the EXP penalty); a unit test has no server clock, so the buyback
            // window in Character.hpChangeAction may hold it at 1 here - the dead state is what matters
            assertTrue(alt.getHp() <= 1);
            assertTrue(BotDeaths.isDead(alt.getId()), "an owned alt is not kept alive like a population bot");
            int hp = alt.getHp();
            BotVitals.hurt(alt, 10); // lying dead: no more hits
            assertEquals(hp, alt.getHp());
        } finally {
            BotDeaths.clearBot(alt.getId());
        }
    }

    @Test
    void anAltKeepsTheWeaponItsPlayerGaveIt() throws Exception {
        Character alt = realAlt(100);
        alt.setJob(client.Job.WARRIOR); // a warrior holding no sword: a population bot would be handed one
        try (MockedStatic<soloMapling.itemPool.ItemInformationProviderUtilities> gen =
                     Mockito.mockStatic(soloMapling.itemPool.ItemInformationProviderUtilities.class)) {
            gen.when(() -> soloMapling.itemPool.ItemInformationProviderUtilities.getRandomEquipForWearing(any(), any()))
                    .thenReturn(1302000);
            assertFalse(BotWeapons.ensureFittingWeapon(alt), "no weapon appears for an owned alt");
            gen.verify(() -> soloMapling.itemPool.ItemInformationProviderUtilities.getRandomEquipForWearing(any(), any()),
                    never());
        }
        assertSame(null, alt.getInventory(InventoryType.EQUIPPED).getItem((short) -11));
    }
    // 1.6.2 (owner, grill 2026-10-30: "alt chat lines: whole party"): an alt's lines (loot, bag full, trade replies,
    // acknowledgements) go out on the shared party voice, so everyone in the owner's party sees them, not only the owner.
    @Test
    void anAltSpeaksToTheWholePartyThroughTheSharedPartyVoice() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);
        net.server.world.Party party = mock(net.server.world.Party.class);
        when(party.getMemberById(OWNER)).thenReturn(mock(net.server.world.PartyCharacter.class));
        when(alt.getParty()).thenReturn(party);
        try (MockedStatic<soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat> chat =
                     Mockito.mockStatic(soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat.class)) {
            OwnedAltCompanion.say(owner, alt, "my bag is full");
            chat.verify(() -> soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat.sayInParty(alt, "my bag is full"));
        }
        verify(owner, never()).sendPacket(any());   // the party voice already reaches the owner: no double line
    }

    @Test
    void anAltOutsideItsOwnersPartyStillTellsTheOwner() {
        Character owner = owner(NOW);
        Character alt = alt(owner, 1302000);   // no party
        try (MockedStatic<soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat> chat =
                     Mockito.mockStatic(soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat.class)) {
            OwnedAltCompanion.say(owner, alt, "omw");
            chat.verify(() -> soloMapling.ArtificialPlayer.BotPartySystem.BotPartyChat.sayInParty(any(), anyString()),
                    never());
        }
        verify(owner).sendPacket(any());
    }
}
