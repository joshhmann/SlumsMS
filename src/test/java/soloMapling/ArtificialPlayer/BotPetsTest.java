package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.inventory.Pet;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.maps.MapItem;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.BotCommandsPack.DropCommands;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.5.0 rc4: bots with real pets (BotPets): who gets one, the pet itself, following, and the pet's loot rules. */
class BotPetsTest {

    private static final int BASE = BotStore.BOT_ID_BASE;

    @AfterEach
    void reset() {
        BotPets.resetForTest();
    }

    private static int idWith(BotHabits.Loot loot, boolean wantPet) {
        for (int id = BASE + 100; ; id++) {
            if (BotHabits.of(id).loot() == loot && (BotPets.choiceFor(id, 50) != null) == wantPet) {
                return id;
            }
        }
    }

    private static Character bot(int id, int level) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(id);
        bot.setLevel(level);
        bot.setPosition(new Point(100, 200));
        return bot;
    }

    @Test
    void thePetIsStableForABotAndOnlyFromLevel30() {
        for (int id = BASE + 100; id < BASE + 600; id++) {
            assertNull(BotPets.choiceFor(id, 29));
            assertEquals(BotPets.choiceFor(id, 30), BotPets.choiceFor(id, 30));
            assertEquals(BotPets.choiceFor(id, 30), BotPets.choiceFor(id, 120), "a level-up never changes the pet");
        }
    }

    @Test
    void aboutAQuarterHaveAPetAndEveryPetLooterHasALootingPet() {
        int n = 20_000;
        int pets = 0;
        int otherLooters = 0;
        int ownNames = 0;
        for (int id = BASE + 100; id < BASE + 100 + n; id++) {
            BotPets.Choice c = BotPets.choiceFor(id, 60);
            boolean petHabit = BotHabits.of(id).loot() == BotHabits.Loot.PET;
            if (petHabit) {
                assertNotNull(c, "a PET-habit bot always has its pet");
                assertTrue(c.magnet() && c.pouch(), "with a Meso Magnet and an Item Pouch");
            }
            if (c != null) {
                pets++;
                otherLooters += !petHabit && c.loots() ? 1 : 0;
                ownNames += java.util.Arrays.asList(BotPets.SPECIES).contains(c.name()) ? 0 : 1;
                assertTrue(c.name().length() <= 13, c.name());
            }
        }
        double share = pets / (double) n;
        assertTrue(share > 0.22 && share < 0.28, "pet share " + share);
        assertTrue(otherLooters > 0, "some other pet owners wear a loot equip too");
        assertTrue(ownNames > pets / 4 && ownNames < pets * 3 / 4, "about half are named");
    }

    @Test
    void everyPoolPetIsInTheClientData() {
        provider.DataProvider item = provider.DataProviderFactory.getDataProvider(provider.wz.WZFiles.ITEM);
        provider.Data names = provider.DataProviderFactory.getDataProvider(provider.wz.WZFiles.STRING).getData("Pet.img");
        assertEquals(BotPets.POOL.length, BotPets.SPECIES.length);
        for (int i = 0; i < BotPets.POOL.length; i++) {
            int id = BotPets.POOL[i];
            assertTrue(constants.inventory.ItemConstants.isPet(id));
            assertNotNull(item.getData("Pet/" + id + ".img"), "Item.wz/Pet/" + id);
            assertEquals(BotPets.SPECIES[i], provider.DataTool.getString(id + "/name", names, null), "pet " + id);
        }
    }

    @Test
    void attachGivesTheBotItsSummonedPetAndLootEquipsOnce() {
        int id = idWith(BotHabits.Loot.PET, true);
        Character bot = bot(id, 45);
        assertTrue(BotPets.attach(bot));
        assertFalse(BotPets.attach(bot), "a second login hook leaves it alone");

        Pet[] pets = bot.getPets();
        assertNotNull(pets[0]);
        assertNull(pets[1], "one pet, never a duplicate");
        assertTrue(pets[0].isBotPet() && pets[0].isSummoned());
        assertEquals(BotPets.choiceFor(id, 45).itemId(), pets[0].getItemId());
        assertEquals(new Point(100, 200), pets[0].getPos());
        assertTrue(bot.isEquippedMesoMagnet() && bot.isEquippedItemPouch());
        assertEquals(0, BotPets.lootingPetSlot(bot));
        assertEquals(BotHabits.Loot.PET, BotHabits.lootNow(bot), "the pet-loot habit finally happens");
        assertTrue(BotHabits.hasLootingPet(bot));
    }

    @Test
    void aBotWithoutAPetOrUnder30GetsNone() {
        Character young = bot(idWith(BotHabits.Loot.PET, true), 29);
        assertFalse(BotPets.attach(young));
        assertNull(young.getPets()[0]);
        assertEquals(BotHabits.Loot.BATCH, BotHabits.lootNow(young));

        Character petless = bot(idWith(BotHabits.Loot.BATCH, false), 70);
        assertFalse(BotPets.attach(petless));
        assertEquals(-1, BotPets.lootingPetSlot(petless));
    }

    @Test
    void aMissingPetItemMeansNoPetAndNoCrash() {
        BotPets.setItemCheckForTest(itemId -> false);
        Character bot = bot(idWith(BotHabits.Loot.PET, true), 50);
        assertFalse(BotPets.attach(bot));
        assertNull(bot.getPets()[0]);
        assertFalse(bot.isEquippedMesoMagnet());
        assertEquals(BotHabits.Loot.BATCH, BotHabits.lootNow(bot), "no pet: it batches like most players");
        assertNull(BotPets.stage(bot, true), "staging a missing pet refuses too");
    }

    @Test
    void attachIgnoresRealPlayers() {
        Character player = testutil.Mocks.chr("Player");
        when(player.getId()).thenReturn(BASE + 100);
        when(player.getLevel()).thenReturn(80);
        assertFalse(BotPets.attach(player));
        verify(player, never()).addPet(any(Pet.class));
    }

    @Test
    void aBotPetNeverGetsHungryAndIsNeverWrittenToThePetsTable() {
        Character bot = bot(idWith(BotHabits.Loot.PET, true), 50);
        BotPets.attach(bot);
        Pet pet = bot.getPets()[0];
        bot.runFullnessSchedule(0);
        bot.runFullnessSchedule(0);
        assertEquals(100, pet.getFullness());
        assertNotNull(bot.getPets()[0], "still summoned");
        pet.saveToDb(); // no row and no database here: a no-op, not an UPDATE of some real pet's row
    }

    @Test
    void detachTakesThePetAndItsEquipsAway() {
        Character bot = bot(idWith(BotHabits.Loot.PET, true), 50);
        BotPets.attach(bot);
        BotPets.detach(bot);
        assertNull(bot.getPets()[0]);
        assertFalse(bot.isEquippedMesoMagnet() || bot.isEquippedItemPouch());
        assertTrue(BotPets.attach(bot), "and the next login gives it back");
    }

    @Test
    void stagingGivesAnyBotALootingPet() {
        Character bot = bot(idWith(BotHabits.Loot.BATCH, false), 50);
        assertNotNull(BotPets.stage(bot, true));
        assertTrue(BotPets.hasBotPet(bot));
        assertEquals(BotHabits.Loot.PET, BotHabits.lootNow(bot));
        BotPets.forget(bot.getId());
        assertEquals(BotHabits.Loot.BATCH, BotHabits.lootNow(bot), "the staging override ends with the bot");
    }

    // ---- loot rules ----

    private static MapItem drop(int owner, long ageMs, int meso, boolean playerDrop, byte type) {
        MapItem mi = mock(MapItem.class);
        when(mi.getOwnerId()).thenReturn(owner);
        when(mi.getDropTime()).thenReturn(1_000_000L - ageMs);
        when(mi.getMeso()).thenReturn(meso);
        when(mi.isPlayerDrop()).thenReturn(playerDrop);
        when(mi.getDropType()).thenReturn(type);
        return mi;
    }

    @Test
    void thePetTakesItsOwnDropsAndNeverAPlayersDuringOwnership() {
        Character bot = bot(idWith(BotHabits.Loot.PET, true), 50);
        BotPets.attach(bot);
        long now = 1_000_000L;
        int me = bot.getId();
        assertTrue(BotPets.petMayTake(bot, drop(me, 2_000, 50, false, (byte) 0), now));
        assertTrue(BotPets.petMayTake(bot, drop(me, 2_000, 0, false, (byte) 0), now), "items with the pouch");
        // a real player's kill: not while it is theirs, whatever the drop type
        assertFalse(BotPets.petMayTake(bot, drop(42, 2_000, 50, false, (byte) 0), now));
        assertFalse(BotPets.petMayTake(bot, drop(42, 14_999, 50, false, (byte) 1), now));
        assertFalse(BotPets.petMayTake(bot, drop(42, 2_000, 50, false, (byte) 2), now), "even a free-for-all type");
        assertTrue(BotPets.petMayTake(bot, drop(42, 15_000, 50, false, (byte) 0), now), "after the window: anyone's");
        // what a player dropped on purpose is never a pet's
        assertFalse(BotPets.petMayTake(bot, drop(me, 60_000, 50, true, (byte) 2), now));
    }

    @Test
    void mesosNeedTheMagnetAndItemsThePouch() {
        Character bot = bot(idWith(BotHabits.Loot.BATCH, false), 50);
        BotPets.attach(bot, new BotPets.Choice(5000001, "Peanut", true, false));
        long now = 1_000_000L;
        assertTrue(BotPets.petMayTake(bot, drop(bot.getId(), 2_000, 10, false, (byte) 0), now));
        assertFalse(BotPets.petMayTake(bot, drop(bot.getId(), 2_000, 0, false, (byte) 0), now));
    }

    @Test
    void aPetPickupIsCreditedAndPlaysThePetAnimation() {
        Character bot = bot(idWith(BotHabits.Loot.PET, true), 50);
        BotPets.attach(bot);
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        MapItem mesos = mock(MapItem.class);
        when(mesos.getMeso()).thenReturn(321);
        when(mesos.getObjectId()).thenReturn(9);

        DropCommands.botPetLootSingleDrop(bot, mesos, 0);

        assertEquals(321, bot.getMeso());
        verify(map).pickItemDrop(any(Packet.class), eq(mesos));
    }

    // ---- following ----

    @Test
    void thePetWalksBehindItsBot() {
        assertEquals(new Point(100 - BotPets.BEHIND_PX, 50), BotPets.petSpot(100, 50, 2)); // walking right
        assertEquals(new Point(100 + BotPets.BEHIND_PX, 50), BotPets.petSpot(100, 50, 5)); // standing, facing left
        assertEquals(3, BotPets.petStance(3, false));
        assertEquals(5, BotPets.petStance(3, true));
        assertEquals(7, BotPets.petStance(7, false), "jumps with its bot");
        assertEquals(4, BotPets.petStance(20, true), "an attack or rope pose: the pet just stands");
    }

    @Test
    void followIsThrottledButAStopGoesOutAtOnce() {
        Character bot = bot(idWith(BotHabits.Loot.PET, true), 50);
        BotPets.attach(bot);
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);

        BotPets.follow(bot, 100, 200, 120, 0, 2, 7);
        BotPets.follow(bot, 106, 200, 120, 0, 2, 7);   // 50 ms later: held back
        verify(map, times(1)).broadcastMessage(eq(bot), any(Packet.class), eq(false));
        BotPets.follow(bot, 110, 200, 0, 0, 4, 7);     // the bot stops: the pet settles now
        verify(map, times(2)).broadcastMessage(eq(bot), any(Packet.class), eq(false));
        assertEquals(4, bot.getPets()[0].getStance());
    }

    @Test
    void aBotWithoutAPetSendsNoPetPackets() {
        Character bot = bot(idWith(BotHabits.Loot.BATCH, false), 50);
        MapleMap map = mock(MapleMap.class);
        bot.setMap(map);
        BotPets.follow(bot, 100, 200, 120, 0, 2, 7);
        verify(map, never()).broadcastMessage(any(Character.class), any(Packet.class), eq(false));
    }
}
