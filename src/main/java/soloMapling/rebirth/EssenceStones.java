package soloMapling.rebirth;

import client.Character;
import client.inventory.InventoryType;
import scripting.AbstractPlayerInteraction;
import soloMapling.ArtificialPlayer.BotProgressionSystem.BotRebirth;

import java.util.ArrayList;
import java.util.List;

/**
 * 1.6.1 (owner 2026-10-29, b083): players make their rebirth Essence Stone at Wyatt the Reborn (NPC 1300009,
 * {@code scripts/npc/rebirth.js}, "Make an Essence Stone"). Before this a player could not make one at all: the
 * {@code @craft} command is commented out (CommandsExecutor) and nothing opened {@code craft.js}, so only bots (which
 * craft in BotRebirth) could be reborn.
 *
 * <p>The recipes are craft.js's, the same ones the bots use ({@link BotRebirth#keyItemFor}/{@link
 * BotRebirth#ingredientsFor}): Blue Essence Stone (4033446) = Zakum Diamond (4032133) + Nine Spirit's Egg (4001094);
 * Red (4033442) = Rock of Time (4021010) + Von Leon's Seal (4001693); Black (4033450) = Cygnus Circlet (4000659),
 * made while you hold a Cygnus Knight Emblem (4032922): owner 2026-10-29, the emblem is a pass and is not used up
 * ({@link BotRebirth#isPass}). Wyatt makes the stone the player's next rebirth needs, once: the used-up items taken,
 * the stone given, only with room for it, never a second one while one is held. Bots keep their own path.
 */
public final class EssenceStones {

    private EssenceStones() {
    }

    /** What the player holds and how items move (the NPC's {@code cm}; a fake in tests). */
    public interface Hands {
        int count(int itemId);

        boolean canHold(int itemId);

        void take(int itemId);

        void give(int itemId);
    }

    /** What Wyatt said, and whether a stone was made. */
    public record Result(boolean made, String text) {
    }

    /** Where each ingredient comes from, for the "still missing" line. */
    static String source(int itemId) {
        return switch (itemId) {
            case 4032133 -> "a Zakum clear";
            case 4001094 -> "a Horntail clear";
            case 4021010 -> "a Pink Bean clear";
            case 4001693 -> "a Von Leon clear";
            case 4000659 -> "a Cygnus clear";
            case 4032922 -> "the guards of the Cygnus Knights' stronghold, or Chief Alex's quest Lost Emblem";
            default -> "boss runs";
        };
    }

    /** Wyatt's "Make an Essence Stone" for a player with {@code reborns} rebirths. */
    public static Result make(int reborns, Hands hands) {
        int stone = BotRebirth.keyItemFor(reborns);
        int[] ing = BotRebirth.ingredientsFor(reborns);
        if (stone < 0 || ing.length != 2) {
            return new Result(false, "You have been reborn three times already. No stone can take you further.");
        }
        if (hands.count(stone) > 0) {
            return new Result(false, "You already hold a #b#t" + stone + "##k #i" + stone + "#. That is the stone "
                    + "your next rebirth needs: bring it to me at level 200.");
        }
        int a = ing[0];
        int b = ing[1];
        int haveA = hands.count(a);
        int haveB = hands.count(b);
        if (haveA <= 0 || haveB <= 0) {
            List<String> missing = new ArrayList<>();
            if (haveA <= 0) missing.add("#r#t" + a + "##k (from " + source(a) + ")");
            if (haveB <= 0) missing.add("#r#t" + b + "##k (from " + source(b) + ")");
            return new Result(false, "Your next rebirth needs a #b#t" + stone + "##k #i" + stone + "#. I make it "
                    + "from one #b#t" + a + "##k" + (BotRebirth.isPass(b) ? ", while you hold one #b#t" + b
                    + "##k (I only need to see it)" : " and one #b#t" + b + "##k") + ".\r\n\r\nYou have:\r\n#i" + a + "# #t" + a
                    + "#: " + haveA + "\r\n#i" + b + "# #t" + b + "#: " + haveB + "\r\n\r\nStill missing: "
                    + String.join(" and ", missing) + ".");
        }
        if (!hands.canHold(stone)) {
            return new Result(false, "Make room for one item in your Etc inventory first, then talk to me again.");
        }
        List<String> taken = new ArrayList<>();
        for (int id : BotRebirth.consumedFor(reborns)) {
            hands.take(id);
            taken.add("#t" + id + "#");
        }
        hands.give(stone);
        String kept = BotRebirth.isPass(b) ? " Keep your #t" + b + "#: I only needed to see it." : "";
        return new Result(true, "Here is your #b#t" + stone + "##k #i" + stone + "#. I took your "
                + String.join(" and ", taken) + "." + kept + " Bring the stone to me at level 200 to be reborn.");
    }

    /** rebirth.js's line: the player talking to Wyatt, through the conversation's own item moves. */
    public static Result make(AbstractPlayerInteraction cm) {
        return make(cm.getPlayer().getReborns(), cm);
    }

    /** The same for a given rebirth count (auto-QA t-rb-3 makes a Black stone for a character of any rebirths). */
    public static Result make(int reborns, AbstractPlayerInteraction cm) {
        Character chr = cm.getPlayer();
        synchronized (chr) {
            return make(reborns, new Hands() {
                public int count(int itemId) {
                    return chr.getInventory(InventoryType.ETC).countById(itemId);
                }

                public boolean canHold(int itemId) {
                    return cm.canHold(itemId, 1);
                }

                public void take(int itemId) {
                    cm.gainItem(itemId, (short) -1);
                }

                public void give(int itemId) {
                    cm.gainItem(itemId, (short) 1);
                }
            });
        }
    }
}
