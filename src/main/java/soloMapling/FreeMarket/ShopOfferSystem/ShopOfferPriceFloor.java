package soloMapling.FreeMarket.ShopOfferSystem;

import client.Character;
import server.ShopMath;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotConsignment;
import soloMapling.Economy.PriceBand;
import soloMapling.Economy.PriceBook;

/**
 * Ticket 181 (market#F-03): the one place an accepted offer writes a listing's price.
 *
 * <p>A bot stall used to take the player's number for whatever it accepted, so a listing asked at 1m could be emptied
 * at 700k - or anything above {@code ShopMath.isSaleablePrice}'s 1 meso. With {@code -Dslums.offer.pricefloor} the
 * price an accepted offer leaves on the shelf is floored:
 * <ul>
 *   <li>at {@link PriceBand#low() the bottom of a bot's selling band} on what the whole stack is worth
 *   ({@link PriceBook#stackValue}), so an accepted offer never sells a bot's stock under its own floor;</li>
 *   <li>and never under what the bot put into a {@link BotConsignment consigned} lot ({@link BotConsignment#basis}),
 *   so a scrolled piece a bot listed is never rewritten below the bot's own valuation of it.</li>
 * </ul>
 * The floor only ever writes the numbers this class returns: an offer at or above it stays exactly the player's number.
 * OFF, this writes what the caller computed, exactly as 1.6.4 did.
 */
public final class ShopOfferPriceFloor {

    private ShopOfferPriceFloor() {
    }

    /** Read on every call so a test can flip it. */
    static boolean enabled() {
        return Boolean.getBoolean("slums.offer.pricefloor");
    }

    /**
     * Writes the price an accepted offer leaves on {@code shopItem}.
     *
     * @param shopItem the listing the offer was accepted on
     * @param request  the price the offer path decided (the player's number, or the bot's counter)
     * @param friend   whether the offer came from a friend of the shop's bot ({@link PriceBand} wording only)
     * @return the price written
     */
    public static int write(PlayerShopItem shopItem, long request, boolean friend) {
        int price = Math.toIntExact(enabled() ? Math.max(request, floorFor(shopItem, friend)) : request);
        shopItem.setPrice(price);
        return price;
    }

    /**
     * The least this listing may be sold for: the selling band's bottom on the stack's fair value, raised to the bot's
     * own price for a consigned lot. Never over {@link ShopMath#MAX_MESO}, so the floor itself is a saleable price.
     */
    static long floorFor(PlayerShopItem shopItem, boolean friend) {
        long floor = PriceBand.of(PriceBand.Side.BOT_SELLS, PriceBook.stackValue(shopItem.getItem()), friend).low();
        Integer basis = BotConsignment.basis(shopItem);
        if (basis != null) {
            floor = Math.max(floor, basis);
        }
        return Math.min(floor, ShopMath.MAX_MESO);
    }

    /**
     * Whether the offer came from a friend of the shop's bot: affinity is only kept for an artificial owner, and an
     * offline or unknown player is never a friend. Memory only, never a load.
     */
    public static boolean friend(int ownerId, Character player) {
        return player != null && BotHelpers.isArtificial(ownerId) && BotAffinity.friend(ownerId, player.getId());
    }
}
