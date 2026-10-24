package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.itemPool.EquipMetadataCache;
import tools.DatabaseConnection;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

class LifeObservationTest {
    private static final long NOW = 10_000_000;
    private static final int ID = 1_000_005_001;

    @Test
    void memoryOnlyRosterLookupNeverTriggersLazyDatabaseLoading() {
        try (var db = mockStatic(DatabaseConnection.class)) {
            assertNull(BotRoster.loadedMember(-123456));
            db.verifyNoInteractions();
        }
    }

    @Test
    void readsLoadedSessionPhaseAndLocalAffinityButNeverUnloadedRowsOrItemData() {
        var member = member(NOW - 30 * 60_000, 60);
        withFixture(member, fixture -> {
            Character friend = person(1);
            Character rivalOptedOut = person(2);
            Character hidden = person(3);
            when(hidden.isHidden()).thenReturn(true);
            Character bot = person(4);
            when(bot.getClient()).thenReturn(mock(BotClient.class));
            when(fixture.map.getAllPlayers()).thenReturn(List.of(friend, rivalOptedOut, hidden, bot));
            try (var affinity = mockStatic(BotAffinity.class)) {
                affinity.when(() -> BotAffinity.loaded(1)).thenReturn(true);
                affinity.when(() -> BotAffinity.loaded(2)).thenReturn(true);
                affinity.when(() -> BotAffinity.state(ID, 1)).thenReturn(AffinityState.FRIEND);
                affinity.when(() -> BotAffinity.state(ID, 2)).thenReturn(AffinityState.RIVAL);
                affinity.when(() -> BotAffinity.optedOut(2)).thenReturn(true);
                var report = LifeObservation.capture(fixture.bot, NOW);
                assertEquals(.5, report.context().needs().values().get(Need.FATIGUE));
                assertTrue(report.context().needs().values().containsKey(Need.BOREDOM));
                assertEquals(1, report.context().friends());
                assertEquals(0, report.context().rivals());
                assertFalse(report.context().needs().values().containsKey(Need.SOCIAL));
                assertTrue(report.context().unknown().contains("weapon requirements"));
                assertEquals(Activity.RESTOCK, report.result().selected().orElseThrow());
                assertNotNull(report.context().current());
                verify(fixture.bot, never()).requestRestockTrip();
            }
        });
    }

    @Test
    void absentFutureAndStaleSessionTimesRemainUnknown() {
        for (var member : new BotRoster.Member[]{null, member(NOW + 1, 60), member(1, 0)}) {
            withFixture(member, fixture -> {
                when(fixture.bot.lifePhase()).thenReturn(new TrainingBot.LifePhase("GRIND", NOW + 1));
                var report = LifeObservation.capture(fixture.bot, NOW);
                assertFalse(report.context().needs().values().containsKey(Need.FATIGUE));
                assertFalse(report.context().needs().values().containsKey(Need.BOREDOM));
                assertNull(report.context().current());
            });
        }
        withFixture(member(1, 60), fixture -> {
            var report = LifeObservation.capture(fixture.bot, NOW + 25 * 60 * 60_000L);
            assertFalse(report.context().needs().values().containsKey(Need.FATIGUE));
        });
    }

    @Test
    void localSamplingIsBoundedAndTravelHoldsSelection() {
        withFixture(null, fixture -> {
            List<Character> people = new ArrayList<>();
            for (int i = 0; i < 100; i++) people.add(person(100 + i));
            when(fixture.map.getAllPlayers()).thenReturn(people);
            when(fixture.bot.isGrinding()).thenReturn(false);
            when(fixture.bot.lifePhase()).thenReturn(new TrainingBot.LifePhase("GO_TOWN", NOW - 1000));
            try (var affinity = mockStatic(BotAffinity.class)) {
                affinity.when(() -> BotAffinity.loaded(anyInt())).thenReturn(true);
                affinity.when(() -> BotAffinity.state(anyInt(), anyInt())).thenReturn(AffinityState.NEUTRAL);
                var report = LifeObservation.capture(fixture.bot, NOW);
                assertEquals(64, report.context().strangers());
                assertTrue(report.context().unknown().contains("local sample truncated at 64"));
                assertEquals(Outcome.HOLD_COMMITMENT, report.result().outcome());
            }
        });
    }

    @Test
    void knownCachedWeaponRequirementsSeedAGearGoalWithoutLoadingItemData() {
        withFixture(null, fixture -> {
            var cache = mock(EquipMetadataCache.class);
            // A level-zero weapon metadata fixture: final reqLevel field defaults to zero on this mock.
            var entry = mock(EquipMetadataCache.EquipEntry.class);
            var inventory = mock(client.inventory.Inventory.class);
            var weapon = mock(client.inventory.Item.class);
            when(fixture.chr.getInventory(client.inventory.InventoryType.EQUIPPED)).thenReturn(inventory);
            when(inventory.getItem((short) -11)).thenReturn(weapon);
            when(weapon.getItemId()).thenReturn(1302000);
            when(cache.find(1302000)).thenReturn(entry);
            fixture.cache.when(EquipMetadataCache::isInitialized).thenReturn(true);
            fixture.cache.when(EquipMetadataCache::get).thenReturn(cache);
            var report = LifeObservation.capture(fixture.bot, NOW);
            assertTrue(report.context().goals().stream().anyMatch(g -> g.kind() == LifeGoals.Kind.WEAPON_UPGRADE
                    && g.targetLevel() == 40));
            assertFalse(report.context().unknown().contains("weapon requirements"));
            verify(inventory).lockInventory();
            verify(inventory).unlockInventory();
            fixture.cache.verify(EquipMetadataCache::get);
        });
    }

    @Test
    void phaseTelemetryFollowsTheRealTransitionWithoutChangingTheStateOwner() throws Exception {
        TrainingBot bot = mock(TrainingBot.class);
        var requests = TrainingBot.class.getDeclaredField("lifeActivities");
        requests.setAccessible(true);
        requests.set(bot, new LifeActivityQueue());
        var enter = TrainingBot.class.getDeclaredMethod("enterPhase",
                Class.forName("soloMapling.ArtificialPlayer.BotTypes.TrainingBot$Phase"));
        enter.setAccessible(true);
        Object grind = java.util.Arrays.stream(enter.getParameterTypes()[0].getEnumConstants())
                .filter(p -> p.toString().equals("GRIND")).findFirst().orElseThrow();
        enter.invoke(bot, grind);
        when(bot.lifePhase()).thenCallRealMethod();
        when(bot.isGrinding()).thenCallRealMethod();
        assertEquals("GRIND", bot.lifePhase().name());
        assertTrue(bot.lifePhase().sinceMs() > 0);
        assertTrue(bot.isGrinding());
    }

    private record Fixture(TrainingBot bot, MapleMap map, Character chr, MockedStatic<EquipMetadataCache> cache) {}

    private static void withFixture(BotRoster.Member member, java.util.function.Consumer<Fixture> action) {
        TrainingBot bot = mock(TrainingBot.class);
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        when(bot.getChr()).thenReturn(chr);
        when(chr.getId()).thenReturn(ID);
        when(chr.getLevel()).thenReturn(45);
        when(chr.getJob()).thenReturn(Job.FIGHTER);
        when(chr.getMap()).thenReturn(map);
        when(map.getAllPlayers()).thenReturn(List.of());
        when(bot.isGrinding()).thenReturn(true);
        when(bot.lifePhase()).thenReturn(new TrainingBot.LifePhase("GRIND", NOW - 10 * 60_000));
        var observed = new LifeInspection.Snapshot("FixtureBot", 45, 100000000, "TrainingBot", "RUNNING",
                0, 10, .25, false, false);
        try (var inspection = mockStatic(LifeInspection.class);
             var roster = mockStatic(BotRoster.class);
             var cache = mockStatic(EquipMetadataCache.class);
             var director = mockStatic(BotPopulationDirector.class)) {
            inspection.when(() -> LifeInspection.capture(bot)).thenReturn(observed);
            roster.when(() -> BotRoster.loadedMember(anyInt())).thenReturn(member);
            director.when(() -> BotPopulationDirector.mayLogOut(bot)).thenReturn(true);
            action.accept(new Fixture(bot, map, chr, cache));
            roster.verify(() -> BotRoster.loadedMember(ID - BotStore.BOT_ID_BASE));
            roster.verifyNoMoreInteractions();
            cache.verify(EquipMetadataCache::isInitialized);
            cache.verifyNoMoreInteractions();
        }
    }

    private static Character person(int id) {
        Character person = mock(Character.class);
        when(person.getId()).thenReturn(id);
        return person;
    }

    private static BotRoster.Member member(long loginAt, int minutes) {
        return new BotRoster.Member(5001, BotRoster.Status.ACTIVE, null, null, 0, 0, minutes,
                100000000, "grind", null, loginAt, 0, 0);
    }
}
