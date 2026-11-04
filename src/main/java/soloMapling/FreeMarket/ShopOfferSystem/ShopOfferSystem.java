package soloMapling.FreeMarket.ShopOfferSystem;

import client.Character;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.server.MethodScheduler;
import tools.PacketCreator;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;

public class ShopOfferSystem {

    /**
     * A hold may outlive the delayed task it was taken for this long. A task that never runs (shutdown, dropped job)
     * must not pin a stall slot for the rest of the JVM's life.
     */
    static final long HOLD_GRACE_MS = 10 * 60 * 1000L;

    private static ShopOfferSystem instance;
    private final Map<Integer, HaggleSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<Integer, ShopMode> shopModes = new ConcurrentHashMap<>();
    /**
     * b181 F-04: the offers waiting on a stall, keyed by owner and slot. It replaces a set that was only ever added
     * to, so one accepted offer silenced that slot until the JVM restarted.
     */
    private final Map<Slot, Hold> holds = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private LongSupplier clock = System::currentTimeMillis;

    private static final double PRESENT_CHANCE = 0.60;

    public enum ShopMode {
        PRESENT,
        AFK
    }

    /** One stall slot: the shop's owner and the position of the listing inside it. */
    public record Slot(int ownerId, int itemIndex) {}

    /**
     * The listing an offer is holding. It names the shop and the exact listing it was made against, so the delayed
     * task and any follow-up offer act on that listing or on nothing (b181 F-05).
     */
    public static final class Hold {
        private final Object shop;
        private final Slot slot;
        private final PlayerShopItem listing;
        private final long expiresAt;

        private Hold(Object shop, Slot slot, PlayerShopItem listing, long expiresAt) {
            this.shop = shop;
            this.slot = slot;
            this.listing = listing;
            this.expiresAt = expiresAt;
        }

        public Slot slot() {
            return slot;
        }

        public PlayerShopItem listing() {
            return listing;
        }

        private boolean expired(long now) {
            return now >= expiresAt;
        }

        private boolean onShop(Object other) {
            return shop == other;
        }
    }

    private ShopOfferSystem() {}

    public static synchronized ShopOfferSystem getInstance() {
        if (instance == null) {
            instance = new ShopOfferSystem();
        }
        return instance;
    }

    public ShopMode getOrAssignMode(int ownerId) {
        return shopModes.computeIfAbsent(
                ownerId,
                id -> random.nextDouble() < PRESENT_CHANCE ? ShopMode.PRESENT : ShopMode.AFK
        );
    }

    public void onPlayerShopChat(Character player, PlayerShop shop, String message) {
        if (player == null || shop == null || message == null) return;
        if (!isBot(shop.getOwner())) return;

        List<PlayerShopItem> items = shop.getItems();
        OfferParser.ParsedOffer offer = OfferParser.parse(message, items);
        if (offer == null) return;

        if (heldBy(shop, shop.getOwner().getId(), offer.getItemIndex()) != null) {
            log("[ShopOfferSystem] Item locked, ignoring offer from " + player.getName());
            return;
        }

        log("[ShopOfferSystem] Offer detected from " + player.getName()
                + ": " + offer.getItemName() + " @ " + offer.getOfferPrice()
                + " (listing: " + offer.getShopItem().getPrice() + ")");

        cleanExpiredSessions();

        ShopMode mode = getOrAssignMode(shop.getOwner().getId());

        int responseDelay = 2000 + random.nextInt(4000);

        if (mode == ShopMode.PRESENT) {
            HaggleSession existingSession = activeSessions.get(player.getId());
            if (existingSession != null) {
                existingSession.touch();
                MethodScheduler.runAfterDelay(
                        () -> ShopOfferResponse.handlePresentOwner(player, shop, offer, existingSession),
                        responseDelay
                );
                return;
            }

            HaggleSession session = new HaggleSession(player.getId(), shop.getOwner().getId());
            activeSessions.put(player.getId(), session);
            MethodScheduler.runAfterDelay(
                    () -> ShopOfferResponse.handlePresentOwner(player, shop, offer, session),
                    responseDelay
            );
        } else {
            ShopOfferResponse.handleAFKOwner(player, shop, offer, this);
        }
    }

    public void onHiredMerchantChat(Character player, HiredMerchant merchant, String message) {
        if (player == null || merchant == null || message == null) return;

        List<PlayerShopItem> items = merchant.getItems();
        OfferParser.ParsedOffer offer = OfferParser.parse(message, items);
        if (offer == null) return;

        String ownerName = merchant.getOwner();
        if (heldBy(merchant, merchant.getOwnerId(), offer.getItemIndex()) != null) {
            log("[ShopOfferSystem] HiredMerchant item locked, ignoring offer from " + player.getName());
            return;
        }

        log("[ShopOfferSystem] HiredMerchant offer from " + player.getName()
                + ": " + offer.getItemName() + " @ " + offer.getOfferPrice()
                + " (listing: " + offer.getShopItem().getPrice() + ", owner: " + ownerName + ")");

        ShopOfferResponse.handleHiredMerchantAFK(
                player, ownerName, merchant.getOwnerId(), merchant.getMapId(), merchant,
                offer.getShopItem(), offer, this,
                () -> merchant.broadcastToVisitorsThreadsafe(PacketCreator.updateHiredMerchant(merchant, player))
        );
    }

    /**
     * Takes the hold on one listing while its price is being settled; null when another offer is already waiting on
     * that slot.
     */
    public Hold hold(Object shop, int ownerId, int itemIndex, PlayerShopItem listing, long holdMs) {
        long now = clock.getAsLong();
        Slot slot = new Slot(ownerId, itemIndex);
        Hold fresh = new Hold(shop, slot, listing, now + holdMs + HOLD_GRACE_MS);
        Hold taken = holds.compute(slot, (key, held) ->
                held != null && !held.expired(now) && held.onShop(shop) && held.listing == listing ? held : fresh);
        return taken == fresh ? fresh : null;
    }

    /** The live hold on this shop's slot, or null; a dead one is dropped on the way. */
    public Hold heldBy(Object shop, int ownerId, int itemIndex) {
        Slot slot = new Slot(ownerId, itemIndex);
        long now = clock.getAsLong();
        Hold held = holds.get(slot);
        if (held == null) return null;
        if (!held.onShop(shop) || held.expired(now) || !stillHeld(held)) {
            holds.remove(slot, held);
            return null;
        }
        return held;
    }

    /** Releases one hold, if it is still the one on record. */
    public void release(Hold hold) {
        if (hold != null) {
            holds.remove(hold.slot, hold);
        }
    }

    /**
     * True while the hold still names the same live listing: same shop open, same slot, same item, still for sale
     * (b181 F-05). Anything else means the deal is off.
     */
    public boolean stillHeld(Hold hold) {
        if (hold == null || hold.expired(clock.getAsLong()) || !open(hold.shop)) return false;
        List<PlayerShopItem> items = itemsOf(hold.shop);
        int index = hold.slot.itemIndex();
        if (items == null || index < 0 || index >= items.size()) return false;
        PlayerShopItem now = items.get(index);
        return now == hold.listing && now.isExist() && now.getBundles() > 0;
    }

    private static List<PlayerShopItem> itemsOf(Object shop) {
        if (shop instanceof PlayerShop playerShop) return playerShop.getItems();
        if (shop instanceof HiredMerchant merchant) return merchant.getItems();
        return null;
    }

    private static boolean open(Object shop) {
        if (shop instanceof PlayerShop playerShop) return playerShop.isOpen();
        if (shop instanceof HiredMerchant merchant) return merchant.isOpen();
        return false;
    }

    public void removeSession(int playerId) {
        activeSessions.remove(playerId);
    }

    private void cleanExpiredSessions() {
        Iterator<Map.Entry<Integer, HaggleSession>> it = activeSessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired()) {
                it.remove();
            }
        }
    }

    /** Test seam: run hold expiry on a hand-cranked clock instead of wall time. */
    void useClockForTest(LongSupplier testClock) {
        this.clock = testClock == null ? System::currentTimeMillis : testClock;
    }
}
