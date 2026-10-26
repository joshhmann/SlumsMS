package soloMapling.fishing;

import client.Character;
import client.SyntheticClient;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.SoloMaplingConstants;

/**
 * 1.6.0 (owner, 2026-10-26): every player character owns a Relaxer (3010000) and a Fishing Chair (3011000). New
 * characters get them at creation ({@code CharacterFactory}); existing ones once at their next login.
 *
 * <p>One-time mechanism: an area-info record ({@link #GIVEN_AREA}, saved with the character like the guide's "seen"
 * mark, no migration). Login with the record does nothing, so a chair sold or dropped later is not given again. Without
 * it, a character that owns either chair is just marked; otherwise both are put in the setup bag and it is marked. If
 * the bag has no room the mark is withheld (retry next login) and the player is told once ({@link #NOTICED_AREA}).
 * Bots are not touched; they have their own chair logic ({@link BotFishingChairs}).
 */
public final class StarterChairs {
    public static final int RELAXER = 3010000;
    public static final short GIVEN_AREA = 29151;
    public static final short NOTICED_AREA = 29152;
    private static final String MARK = "chairs=1.6.0";

    private StarterChairs() {
    }

    /** New characters: both chairs are starting items. */
    public static void addToRecipe(client.creator.CharacterFactoryRecipe recipe) {
        recipe.addStartingItem(RELAXER, 1, InventoryType.SETUP);
        recipe.addStartingItem(FishingPolicy.CHAIR, 1, InventoryType.SETUP);
    }

    static boolean isPlayer(Character chr) {
        return chr != null && !BotHelpers.isBot(chr) && !(chr.getClient() instanceof SyntheticClient)
                && chr.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID;
    }

    public static boolean given(Character chr) {
        return chr.getAreaInfo(GIVEN_AREA) != null;
    }

    /**
     * Login hook, run before the character info packet so the bag arrives with the chairs. Returns whether the
     * chairs were added now.
     */
    public static boolean onLogin(Character chr) {
        if (!isPlayer(chr) || given(chr)) {
            return false;
        }
        Inventory setup = chr.getInventory(InventoryType.SETUP);
        setup.lockInventory();
        try {
            if (setup.findById(RELAXER) != null || setup.findById(FishingPolicy.CHAIR) != null) {
                chr.getAreaInfos().put(GIVEN_AREA, MARK);
                return false;
            }
            if (setup.getNumFreeSlot() < 2) {
                return false;
            }
            setup.addItem(new Item(RELAXER, (short) 0, (short) 1));
            setup.addItem(new Item(FishingPolicy.CHAIR, (short) 0, (short) 1));
            chr.getAreaInfos().put(GIVEN_AREA, MARK);
            return true;
        } finally {
            setup.unlockInventory();
        }
    }

    /** Run once the player is in the world: tells them, a single time ever, that the chairs wait for bag room. */
    public static void noticeIfWaiting(Character chr) {
        if (!isPlayer(chr) || given(chr) || chr.getAreaInfo(NOTICED_AREA) != null) {
            return;
        }
        chr.getAreaInfos().put(NOTICED_AREA, MARK);
        chr.dropMessage(5, "You have a Relaxer and a Fishing Chair waiting. Free two Setup slots and log in again to get them.");
    }
}
