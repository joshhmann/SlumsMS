package soloMapling.fishing;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

/**
 * 1.6.0 (owner, 2026-10-26; ticket 92): about 1 in 20 of the hangout and explorer bots (seeded Bartle type Socializer
 * or Explorer) own a Fishing Chair, so FISH becomes a choice their life system can make on the fishing maps when the
 * fishing flags are on (TrainingBot.canStartLifeActivity needs an owned chair).
 *
 * <p>Who gets one is drawn from the roster slot alone (a hash, independent of the Weyl draw that picks the Bartle
 * quadrant), so nothing is stored and the same bots are chosen on every boot. The chair is handed over where a bot
 * enters the world ({@code BotGeneration}): a new bot at creation, an existing one at its next login, once - the
 * chair is saved with its bag and never put up for sale ({@code SaleRules}), so later logins find it. No DB job.
 */
public final class BotFishingChairs {
    /** One in this many eligible bots draws a chair. */
    public static final int ONE_IN = 20;
    private static final long SALT = 0x5F15_C4A1_12L;

    private BotFishingChairs() {
    }

    /** Hangout (Socializer) and explorer types: the two whose life tables weigh FISH. */
    static boolean fishingType(SocialProfile.Type type) {
        return type == SocialProfile.Type.EXPLORER || type == SocialProfile.Type.SOCIALIZER;
    }

    /** The slot's 1-in-20 draw, ignoring its type. */
    static boolean drawn(int slot) {
        long z = slot + SALT;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return Long.remainderUnsigned(z, ONE_IN) == 0;
    }

    /** Whether this bot should own a Fishing Chair: its seeded type fishes and its slot drew one. */
    public static boolean eligible(int characterId) {
        int slot = SocialProfile.slotOf(characterId);
        return drawn(slot) && fishingType(SocialProfile.forSlot(slot).leading());
    }

    /**
     * Gives an eligible bot its chair if it has none. Cheap enough for every login: a hash, then (for the ~3% chosen)
     * one look in the setup bag. Returns whether a chair was added.
     */
    public static boolean ensure(Character bot) {
        if (bot == null || !eligible(bot.getId())) {
            return false;
        }
        Inventory setup = bot.getInventory(InventoryType.SETUP);
        if (setup == null) {
            return false;
        }
        setup.lockInventory();
        try {
            if (setup.findById(FishingPolicy.CHAIR) != null || setup.isFull()) {
                return false;
            }
            // straight into the bag: a bot's client has no player to send the gain to, and no WZ lookup is needed
            return setup.addItem(new Item(FishingPolicy.CHAIR, (short) 0, (short) 1)) >= 0;
        } finally {
            setup.unlockInventory();
        }
    }
}
