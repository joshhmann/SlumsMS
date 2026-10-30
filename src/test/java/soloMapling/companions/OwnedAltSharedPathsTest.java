package soloMapling.companions;

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
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotGrindSystem.Approach;
import soloMapling.ArtificialPlayer.BotGrindSystem.KsManners;
import soloMapling.ArtificialPlayer.BotGrindSystem.LootPicker;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 (owner 2026-10-30: "hopefully we're not creating separate systems ... for alts and playerbots"): an owned alt
 * fights, loots, walks and buffs through the population bots' code, with only its own rules on top.
 */
class OwnedAltSharedPathsTest {

    static final long NOW = 95_000_000L;
    static final int OWNER = 1, ALT = 2, STRANGER = 3;

    private MapleMap field;
    private final List<Character> chars = new ArrayList<>();
    private final List<Monster> mobs = new ArrayList<>();

    @BeforeEach
    void stage() {
        field = mock(MapleMap.class);
        when(field.getCharacters()).thenReturn(chars);
        when(field.getAllMonsters()).thenReturn(mobs);
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>());
        OwnedAltLoot.room = (a, d) -> true;
    }

    @AfterEach
    void unstage() {
        OwnedAltLoot.room = OwnedAltLoot.REAL_ROOM;
        OwnedAltCombat.forget(OWNER);
        OwnedAltCombat.forget(ALT);
        OwnedAltGate.forceIdleForQa(OWNER, false);
    }

    Character at(int id, int x, int y) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getMap()).thenReturn(field);
        when(c.getPosition()).thenReturn(new Point(x, y));
        return c;
    }

    Monster mob(int x) {
        Monster m = mock(Monster.class);
        when(m.isAlive()).thenReturn(true);
        when(m.getPosition()).thenReturn(new Point(x, 0));
        when(m.getObjectId()).thenReturn(900 + x);
        when(m.damageSnapshot()).thenReturn(new HashMap<>());
        mobs.add(m);
        return m;
    }

    // ── 1: kill-steal manners ──

    @Test
    void aStrangerStandingByAMobIsJudgedByKsManners() {
        Monster m = mob(1000);
        Character stranger = at(STRANGER, 1000 + 260, 0); // KsManners.PLAYER_MOB_PX: still the stranger's
        when(stranger.getClient()).thenReturn(mock(Client.class));
        chars.add(stranger);
        Set<Integer> party = Set.of(OWNER, ALT);
        try (MockedStatic<KsManners> ks = Mockito.mockStatic(KsManners.class, Mockito.CALLS_REAL_METHODS)) {
            assertTrue(OwnedAltCombat.strangers(m, field, party));
            ks.verify(() -> KsManners.ownerOf(eq(field), eq(List.of(stranger)), eq(m), org.mockito.ArgumentMatchers.anyLong()));
        }
        when(stranger.getPosition()).thenReturn(new Point(1000 + 261, 0));
        assertFalse(OwnedAltCombat.strangers(m, field, party), "past the bots' 260 px: free");
        when(stranger.getPosition()).thenReturn(new Point(1000, 91));
        assertFalse(OwnedAltCombat.strangers(m, field, party), "past the bots' 90 px ledge slack: free");
    }

    @Test
    void thePartyIsNeverAStrangerAndThePartysOwnHitStillWins() {
        Monster m = mob(1000);
        Character member = at(OWNER, 1010, 0);
        when(member.getClient()).thenReturn(mock(Client.class));
        chars.add(member);
        assertFalse(OwnedAltCombat.strangers(m, field, Set.of(OWNER, ALT)), "its own owner stands by it");
        Character stranger = at(STRANGER, 1010, 0);
        when(stranger.getClient()).thenReturn(mock(Client.class));
        chars.add(stranger);
        Map<Integer, Long> dealt = new HashMap<>(Map.of(OWNER, 5L));
        when(m.damageSnapshot()).thenReturn(dealt);
        when(m.lastHitBy(OWNER)).thenReturn(System.currentTimeMillis()); // the party is hitting it now
        assertFalse(OwnedAltCombat.strangers(m, field, Set.of(OWNER, ALT)),
                "the alt's layer on top: the party hit it first, a stranger walking up later does not take it");
    }

    // ── 2: loot ──

    @Test
    void theAltPicksThroughTheSharedLootPicker() {
        MapItem below = mock(MapItem.class); // b082: on the ledge below the alt
        when(below.getOwnerId()).thenReturn(OWNER);
        when(below.getPosition()).thenReturn(new Point(30, 150));
        when(below.getDropTime()).thenReturn(NOW - 5_000);
        MapItem level = mock(MapItem.class);
        when(level.getOwnerId()).thenReturn(OWNER);
        when(level.getPosition()).thenReturn(new Point(200, 0));
        when(level.getDropTime()).thenReturn(NOW - 5_000);
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList()))
                .thenReturn(new ArrayList<>(List.of(below, level)));
        Character alt = at(ALT, 0, 0), owner = at(OWNER, 0, 0);
        try (MockedStatic<LootPicker> lp = Mockito.mockStatic(LootPicker.class, Mockito.CALLS_REAL_METHODS)) {
            assertSame(level, OwnedAltLoot.pick(alt, owner, Set.of(OWNER, ALT), NOW, EnumSet.noneOf(InventoryType.class),
                    new boolean[1]));
            lp.verify(() -> LootPicker.nearest(eq(alt), any(), eq(NOW), any()));
        }
        assertEquals(LootPicker.SETTLE_MS, OwnedAltLoot.SETTLE_MS, "the bots' settle time (was 1,000 ms)");
        assertEquals(LootPicker.SAME_LEDGE_DY, OwnedAltLoot.SAME_LEDGE_DY);
    }

    @Test
    void itsOwnReachAndItsOwnersLeashStillHold() {
        MapItem far = mock(MapItem.class);
        when(far.getOwnerId()).thenReturn(ALT);
        when(far.getPosition()).thenReturn(new Point(OwnedAltLoot.REACH_X_PX + 10, 0));
        when(far.getDropTime()).thenReturn(NOW - 5_000);
        when(field.getMapObjectsInRange(any(Point.class), anyDouble(), anyList())).thenReturn(new ArrayList<>(List.of(far)));
        assertNull(OwnedAltLoot.pick(at(ALT, 0, 0), at(OWNER, 0, 0), Set.of(OWNER, ALT), NOW,
                EnumSet.noneOf(InventoryType.class), new boolean[1]), "past its 350 px reach");
        assertNull(OwnedAltLoot.pick(at(ALT, 300, 0), at(OWNER, 300 - OwnedAltCombat.LEASH_X_PX - 20, 0),
                Set.of(OWNER, ALT), NOW, EnumSet.noneOf(InventoryType.class), new boolean[1]), "past its owner's leash");
    }

    // ── 3 and 4: the approach and the buffs, through the companion tick ──

    Character owner() {
        Character o = at(OWNER, 0, 0);
        when(o.getName()).thenReturn("Main");
        when(o.isLoggedinWorld()).thenReturn(true);
        when(o.getMapId()).thenReturn(104_040_000);
        Client c = mock(Client.class);
        when(c.getLastPlayerAction()).thenReturn(NOW);
        when(o.getClient()).thenReturn(c);
        return o;
    }

    Character alt(Character owner) {
        Character a = at(ALT, 10, 0);
        when(a.getName()).thenReturn("Second");
        when(a.isOwnedAlt()).thenReturn(true);
        when(a.getOwnedAltOwner()).thenReturn(owner);
        when(a.getHp()).thenReturn(50);
        when(a.getMapId()).thenReturn(104_040_000);
        Inventory worn = mock(Inventory.class);
        when(worn.getItem((short) -11)).thenReturn(new Item(1302000, (short) -11, (short) 1));
        when(a.getInventory(InventoryType.EQUIPPED)).thenReturn(worn);
        return a;
    }

    @Test
    void theAltWalksIntoReachAndBuffsThroughTheBotsCode() {
        Character owner = owner();
        Character alt = alt(owner);
        Monster m = mob(400);
        when(m.damageSnapshot()).thenReturn(new HashMap<>(Map.of(OWNER, 5L)));
        when(m.lastHitBy(OWNER)).thenReturn(NOW - 1_000);
        try (MockedStatic<GCMovement> mv = Mockito.mockStatic(GCMovement.class);
             MockedStatic<BotAttackDriver> atk = Mockito.mockStatic(BotAttackDriver.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<Approach> ap = Mockito.mockStatic(Approach.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<BotBuffDriver> buffs = Mockito.mockStatic(BotBuffDriver.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<OwnedAltService> svc = Mockito.mockStatic(OwnedAltService.class)) {
            svc.when(OwnedAltService::getInstance).thenReturn(mock(OwnedAltService.class));
            atk.when(() -> BotAttackDriver.attackReachX(any())).thenReturn(100);
            atk.when(() -> BotAttackDriver.attackReachY(any())).thenReturn(60);
            buffs.when(() -> BotBuffDriver.maintain(any(), anyLong(), any())).thenReturn(1);
            mv.when(() -> GCMovement.isMapObserved(any(Character.class))).thenReturn(true);
            OwnedAltCompanion.State s = new OwnedAltCompanion.State(ALT);
            OwnedAltCompanion.tick(owner, alt, s, NOW, 0);
            ap.verify(() -> Approach.reach(alt));
            ap.verify(() -> Approach.walkTo(eq(alt), anyInt(), anyInt(), eq(Integer.MIN_VALUE)));
            atk.verify(() -> BotAttackDriver.botAttack(alt), Mockito.never());
            buffs.verify(() -> BotBuffDriver.selfBuff(eq(alt), eq(NOW), eq(0L), any(), eq(BotBuffDriver.Pay.ALL), any()));
            buffs.verify(() -> BotBuffDriver.maintain(eq(alt), eq(0L), eq(BotBuffDriver.Pay.ALL)),
                    Mockito.description("on time, paid out of its own MP"));
            assertEquals(NOW + 2_000, s.nextBuffMs, "looks again in 2 s, as a population bot does");
        }
    }

    @Test
    void anIdleOwnersAltDoesNotBuff() {
        Character owner = owner();
        Character alt = alt(owner);
        OwnedAltGate.forceIdleForQa(OWNER, true);
        try (MockedStatic<GCMovement> mv = Mockito.mockStatic(GCMovement.class);
             MockedStatic<BotBuffDriver> buffs = Mockito.mockStatic(BotBuffDriver.class, Mockito.CALLS_REAL_METHODS);
             MockedStatic<OwnedAltService> svc = Mockito.mockStatic(OwnedAltService.class)) {
            svc.when(OwnedAltService::getInstance).thenReturn(mock(OwnedAltService.class));
            mv.when(() -> GCMovement.isMapObserved(any(Character.class))).thenReturn(true);
            OwnedAltCompanion.tick(owner, alt, new OwnedAltCompanion.State(ALT), NOW, 0);
            buffs.verify(() -> BotBuffDriver.selfBuff(any(), anyLong(), anyLong(), any(), any(), any()), Mockito.never());
        }
    }

    @Test
    void anAltOnlyHasTheBuffsItLearned() {
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(ALT);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getJob()).thenReturn(client.Job.FIGHTER);
        when(alt.getSkillLevel(anyInt())).thenReturn(0);
        when(alt.getSkillLevel(constants.skills.Fighter.RAGE)).thenReturn(5);
        assertEquals(List.of(constants.skills.Fighter.RAGE), BotBuffConfig.buffsFor(alt),
                "not Iron Body or Power Guard it never learned (it used to count the whole Fighter kit)");
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(1_000_000_555);
        when(bot.getJob()).thenReturn(client.Job.FIGHTER);
        assertEquals(BotBuffConfig.buffsForJob(client.Job.FIGHTER), BotBuffConfig.buffsFor(bot),
                "a population bot that is not a real build still shows its job's kit");
    }

    @Test
    void anAltPaysForEverySelfBuffAndCastsNothingWithoutTheMp() {
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(ALT);
        when(alt.isOwnedAlt()).thenReturn(true);
        when(alt.getJob()).thenReturn(client.Job.FIGHTER);
        when(alt.getSkillLevel(anyInt())).thenReturn(0);
        when(alt.getSkillLevel(constants.skills.Fighter.POWER_GUARD)).thenReturn(10);
        try (MockedStatic<soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects> fx =
                     Mockito.mockStatic(soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.class,
                             Mockito.CALLS_REAL_METHODS); // its MP rule (payFor) is the shared one under test
             MockedStatic<soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals> vit =
                     Mockito.mockStatic(soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.class);
             MockedStatic<soloMapling.server.MethodScheduler> sched =
                     Mockito.mockStatic(soloMapling.server.MethodScheduler.class)) {
            fx.when(() -> soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.durationOf(any(), anyInt())).thenReturn(60_000);
            fx.when(() -> soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.isPartyBuff(anyInt())).thenReturn(false);
            fx.when(() -> soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects.castLevel(any(), anyInt())).thenReturn(10);
            vit.when(() -> soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.mpCost(any(), anyInt(), anyInt())).thenReturn(20);
            vit.when(() -> soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.affordMp(any(), anyInt())).thenReturn(false);
            assertEquals(0, BotBuffDriver.maintain(alt, 0, BotBuffDriver.Pay.ALL), "no MP and no MP potion: no cast");
            vit.when(() -> soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.affordMp(any(), anyInt())).thenReturn(true);
            assertEquals(1, BotBuffDriver.maintain(alt, 0, BotBuffDriver.Pay.ALL));
            vit.verify(() -> soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals.spend(alt, 20, 0));
        } finally {
            BotBuffDriver.clearBot(ALT);
        }
    }
}
