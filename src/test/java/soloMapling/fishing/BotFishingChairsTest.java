package soloMapling.fishing;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 (owner, 2026-10-26; ticket 92): about 1 in 20 hangout/explorer bots own a Fishing Chair. */
class BotFishingChairsTest {
    private static final int SLOTS = 200_000;

    @Test
    void aboutOneInTwentyHangoutAndExplorerBotsAndNoOthers() {
        Map<SocialProfile.Type, int[]> byType = new EnumMap<>(SocialProfile.Type.class);   // {bots, chosen}
        for (int slot = 0; slot < SLOTS; slot++) {
            SocialProfile.Type type = SocialProfile.forSlot(slot).leading();
            int[] row = byType.computeIfAbsent(type, t -> new int[2]);
            row[0]++;
            if (BotFishingChairs.eligible(BotStore.BOT_ID_BASE + slot)) {
                row[1]++;
            }
        }
        assertEquals(0, byType.get(SocialProfile.Type.ACHIEVER)[1]);
        assertEquals(0, byType.get(SocialProfile.Type.KILLER)[1]);
        for (SocialProfile.Type type : new SocialProfile.Type[]{SocialProfile.Type.SOCIALIZER, SocialProfile.Type.EXPLORER}) {
            int[] row = byType.get(type);
            double share = row[1] / (double) row[0];
            assertTrue(share > .045 && share < .055, type + " drew " + share);
        }
        // at the planned ~3,000 bots that is about 90 chairs (60% of bots are Socializers or Explorers)
        int first3000 = 0;
        for (int slot = 0; slot < 3000; slot++) {
            if (BotFishingChairs.eligible(BotStore.BOT_ID_BASE + slot)) first3000++;
        }
        assertTrue(first3000 > 60 && first3000 < 125, "chairs among 3,000 bots: " + first3000);
    }

    @Test
    void theDrawIsTheSameEveryTime() {
        for (int slot = 0; slot < 5000; slot++) {
            int id = BotStore.BOT_ID_BASE + slot;
            assertEquals(BotFishingChairs.eligible(id), BotFishingChairs.eligible(id));
        }
    }

    @Test
    void anEligibleBotGetsOneChairOnceAndOthersNone() {
        Character chosen = bot(firstSlot(true));
        assertTrue(BotFishingChairs.ensure(chosen));
        assertNotNull(chosen.getInventory(InventoryType.SETUP).findById(FishingPolicy.CHAIR));
        assertFalse(BotFishingChairs.ensure(chosen), "a later login finds the chair and adds none");
        assertEquals(1, chosen.getInventory(InventoryType.SETUP).countById(FishingPolicy.CHAIR));

        Character other = bot(firstSlot(false));
        assertFalse(BotFishingChairs.ensure(other));
        assertNull(other.getInventory(InventoryType.SETUP).findById(FishingPolicy.CHAIR));
    }

    @Test
    void fishingIsAChoiceOnlyWithAChairAndTheFlagsOn() {
        String enabled = System.getProperty("slums.fishing.enabled"), rewards = System.getProperty("slums.fishing.rewards");
        try {
            Character chr = bot(firstSlot(true));
            System.setProperty("slums.fishing.enabled", "true");
            System.setProperty("slums.fishing.rewards", "true");
            assertFalse(GameFishing.choosable(chr, map -> true), "no chair yet");
            BotFishingChairs.ensure(chr);
            assertTrue(GameFishing.choosable(chr, map -> true));
            assertFalse(GameFishing.choosable(chr, map -> false), "its fishing map is full");
            System.setProperty("slums.fishing.rewards", "false");
            assertFalse(GameFishing.choosable(chr, map -> true), "fishing flags off");
        } finally {
            restore("slums.fishing.enabled", enabled);
            restore("slums.fishing.rewards", rewards);
        }
    }

    @Test
    void anUnsellableChair() {
        assertEquals("fishing chair", soloMapling.Economy.SaleRules.whyNot(
                new client.inventory.Item(FishingPolicy.CHAIR, (short) 1, (short) 1)));
    }

    private static int firstSlot(boolean eligible) {
        for (int slot = 0; ; slot++) {
            if (BotFishingChairs.eligible(BotStore.BOT_ID_BASE + slot) == eligible) return slot;
        }
    }

    private static Character bot(int slot) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setID(BotStore.BOT_ID_BASE + slot);
        return chr;
    }

    private static void restore(String name, String value) {
        if (value == null) System.clearProperty(name); else System.setProperty(name, value);
    }
}
