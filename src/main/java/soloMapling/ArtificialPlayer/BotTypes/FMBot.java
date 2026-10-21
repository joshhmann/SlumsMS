package soloMapling.ArtificialPlayer.BotTypes;

import soloMapling.qa.QaLog;

import client.Character;
import client.inventory.Item;
import server.maps.HiredMerchant;
import server.maps.PlayerShop;
import server.maps.PlayerShopItem;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSellerCaps;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.FreeMarket.HiredMerchantAdapter;
import soloMapling.FreeMarket.PlayerShopAdapter;
import soloMapling.FreeMarket.ShopKeeper;
import soloMapling.itemPool.ItemUtilities;

import client.inventory.InventoryType;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotConsignment;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollWants;

import java.awt.*;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.Random;
import java.util.function.Predicate;


import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.FMRoomWarpPortalId;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botEnterFMRoom;
import static soloMapling.ArtificialPlayer.BotCommandsPack.WarpCommands.botExitFMRoom;
import static soloMapling.ArtificialPlayer.BotDialogueHandler.getRandomResolvedLine;
import static soloMapling.server.SoloMaplingUtilities.generateRandomNumber;
import static soloMapling.ArtificialPlayer.BotLogic.isInsideFM;
import static soloMapling.ArtificialPlayer.BotLogic.isInsideFMRooms;
import static soloMapling.ArtificialPlayer.BotLogic.isPointNear;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementStructures.MovementEnums.FreeMarketValues.FM_ENTRANCE;
import static soloMapling.ArtificialPlayer.BotMovementSystem.NavigationSystem.FMMovementCommands.getDoorPoint;
import static soloMapling.BotLogger.log;

public class FMBot extends BotSM {
    private FMBotState fmBotState = FMBotState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());

    private long startTime;
    private long endTime;

    private int fmRoom = 1;
    private int pinnedRoom; // !qa fm-1 (test server): > 0 keeps the bot shopping this one room


    private ShopKeeper currentTarget;

    // Visit queue: pre-built order of shops to visit in this room
    private List<ShopKeeper> visitQueue = new ArrayList<>();
    private int visitIndex = 0;
    private Set<Integer> segmentStartIndices = new HashSet<>();

    // Escalating walk chance (pity timer)
    private static final double BASE_WALK_CHANCE = 0.40;
    private static final double WALK_CHANCE_INCREMENT = 0.10;
    private double walkChance = BASE_WALK_CHANCE;

    // GC moves are fire-and-forget, so arrival is polled from the FSM tick. The driver abandons
    // stuck/unreachable moves (isMoving goes false), so bounded counters are enough to guarantee
    // the FSM can never wedge on a walk that won't finish.
    private static final int MAX_DOOR_WALK_ATTEMPTS = 3;
    private static final int MAX_SHOP_ARRIVAL_POLLS = 8;
    private int doorWalkAttempts = 0;
    private int shopArrivalPolls = 0;

    // 1.4.2: real players' shops. A bot visits them first, re-checks the room for new ones after every shop, and
    // comes back to one at most every REAL_SHOP_REVISIT_MS. It enters only while a visitor slot stays free for a
    // player after it, and only one FM bot sits in a given real shop at a time; it leaves after one browse beat.
    static final long REAL_SHOP_REVISIT_MS = 60_000;
    static final int REAL_SHOP_SPARE_SLOTS = 1;
    static final long OCCUPANT_STALE_MS = 30_000;   // a claim left by a bot that vanished inside frees itself
    private record Occupant(int botId, long since) {
    }
    private static final Map<Object, Occupant> realShopOccupant = new ConcurrentHashMap<>();
    private final Map<Object, Long> realShopLastVisit = new HashMap<>();
    private final Set<Object> deferredThisRoom = new HashSet<>();
    private boolean currentIsReal;
    private boolean seated;
    // Equips this bot bought (oldest first): when its equip tab is full it lets the oldest go to make room.
    private final Deque<Integer> boughtEquipIds = new ArrayDeque<>();

    public FMBot(Character character) {
        super(character);
        dialoguePath = "FMBotDialogue.yaml";
        botType = "FMBot";
    }

    private void setFMBotState(FMBotState state) {
        this.fmBotState = state;
    }

    protected enum FMBotState {
        RESET,
        NAV_TO_FM_ROOM,
        PROCESS_ROOM,
        BROWSING_ROOM,
        ACTION_PHASE,
        ENTER_SHOP,
        PROCESS_SHOP,
        EXIT_SHOP,
        EXIT_FM_ROOM
    }

    private void resetFMBotState() {
        setFMBotState(FMBotState.RESET);
        evaluateFMRoom();
    }

    // Self-looping: NAV_TO_FM_ROOM re-enters here every tick until the bot is inside the room.
    private void navToFMRoom() {
        if (isInsideFMRooms(this.getChr())) { // already inside FM room
            doorWalkAttempts = 0;
            return;
        }
        if (!isInsideFM(this.getChr())) {
            botExitFMRoom(getChr(), 1); // warp to the FM entrance; start the door walk next tick
            waitFor(1000);
            return;
        }
        Point doorPt = getDoorPoint(this.fmRoom);
        if (isPointNear(this.getChr().getPosition(), doorPt, 20) || doorWalkAttempts >= MAX_DOOR_WALK_ATTEMPTS) {
            // at the door - or the walk keeps dying, so enter anyway (the old code's timed warp
            // entered regardless of where the walk actually ended too)
            doorWalkAttempts = 0;
            botEnterFMRoom(this.getChr(), this.fmRoom);
            waitFor(1500); // settle beat so PROCESS_ROOM reads the room's shops, not the entrance
            return;
        }
        if (!GCMovement.isMoving(getChr())) { // not started yet, or the last attempt was abandoned
            doorWalkAttempts++;
            GCMovement.move(getChr(), doorPt.x, doorPt.y);
        }
        // still walking - stay in NAV_TO_FM_ROOM and re-check on the next tick
    }

    private void processRoom() {
        List<PlayerShop> shops = this.getChr().getMap().getAllPlayerShops();
        List<HiredMerchant> merchants = this.getChr().getMap().getAllHiredMerchants();
        buildVisitQueue(shops, merchants);
        deferredThisRoom.clear();
        visitQueue = realShopsFirst(visitQueue, FMBot::isRealPlayersShop);
        if (!visitQueue.isEmpty()) {
            segmentStartIndices.add(0);
        }
        discoverRealShops();
    }

    /** The queue with real players' shops moved to the front, both groups keeping their order. */
    static List<ShopKeeper> realShopsFirst(List<ShopKeeper> queue, Predicate<ShopKeeper> isReal) {
        List<ShopKeeper> out = new ArrayList<>(queue.size());
        for (ShopKeeper shop : queue) {
            if (isReal.test(shop)) {
                out.add(shop);
            }
        }
        for (ShopKeeper shop : queue) {
            if (!isReal.test(shop)) {
                out.add(shop);
            }
        }
        return out;
    }

    /**
     * Puts the room's real players' shops that are open, not already ahead in the queue and not visited by this
     * bot in the last {@link #REAL_SHOP_REVISIT_MS} next in line - a shop opened after the bot walked in is seen
     * on its next step, not on its next lap of the room.
     */
    private void discoverRealShops() {
        if (getChr().getMap() == null || !isInsideFMRooms(getChr())) {
            return;
        }
        List<ShopKeeper> present = new ArrayList<>();
        for (PlayerShop s : getChr().getMap().getAllPlayerShops()) {
            present.add(new PlayerShopAdapter(s));
        }
        for (HiredMerchant m : getChr().getMap().getAllHiredMerchants()) {
            if (!(m instanceof soloMapling.FreeMarket.HiredMerchantArtificial)) {
                present.add(new HiredMerchantAdapter(m));
            }
        }
        long now = System.currentTimeMillis();
        realShopLastVisit.values().removeIf(at -> now - at > REAL_SHOP_REVISIT_MS);
        realShopOccupant.values().removeIf(held -> now - held.since() > OCCUPANT_STALE_MS);
        int insertAt = Math.min(visitIndex, visitQueue.size());
        for (ShopKeeper shop : present) {
            if (!isRealPlayersShop(shop) || !shop.isOpen() || isQueuedFrom(shop.shop(), visitIndex)) {
                continue;
            }
            Long last = realShopLastVisit.get(shop.shop());
            if (last != null && now - last < REAL_SHOP_REVISIT_MS) {
                continue;
            }
            visitQueue.add(insertAt, shop);
            Set<Integer> shifted = new HashSet<>();
            for (int i : segmentStartIndices) {
                shifted.add(i >= insertAt ? i + 1 : i);
            }
            shifted.add(insertAt);   // walk over to it
            segmentStartIndices.clear();
            segmentStartIndices.addAll(shifted);
            insertAt++;
            if (QaLog.on(QaLog.Topic.FM_BUY)) {
                QaLog.map(QaLog.Topic.FM_BUY, getChr().getMapId(), getChr().getName() + " heads for "
                        + shop.getOwner() + "'s shop (" + describe(shop) + ")");
            }
        }
    }

    private boolean isQueuedFrom(Object shop, int from) {
        for (int i = Math.max(0, from); i < visitQueue.size(); i++) {
            if (visitQueue.get(i).shop() == shop) {
                return true;
            }
        }
        return false;
    }

    /** True for a shop a real player owns: a player's own PlayerShop, or a HiredMerchant that is not a bot's. */
    static boolean isRealPlayersShop(ShopKeeper shop) {
        if (shop instanceof PlayerShopAdapter adapter) {
            Character owner = adapter.getShop().getOwner();
            return owner != null && !BotHelpers.isBot(owner) && !BotHelpers.isArtificial(owner.getId());
        }
        if (shop instanceof HiredMerchantAdapter adapter) {
            HiredMerchant merchant = adapter.getMerchant();
            return !(merchant instanceof soloMapling.FreeMarket.HiredMerchantArtificial)
                    && !BotHelpers.isArtificial(merchant.getOwnerId());
        }
        return false;
    }

    private static String describe(ShopKeeper shop) {
        String kind = shop instanceof HiredMerchantAdapter ? "hired merchant" : "player shop";
        return kind + ", " + shop.freeVisitorSlots() + " free visitor slots";
    }

    /** Whether a bot may sit down in a real player's shop now: open, a slot spare after it, no other bot inside. */
    static boolean mayEnterRealShop(boolean open, int freeSlots, Integer occupant, int botId) {
        return open && freeSlots >= 1 + REAL_SHOP_SPARE_SLOTS && (occupant == null || occupant == botId);
    }

    /**
     * Builds the visit queue by sorting all shops by distance, then performing
     * a deck-cut shuffle to create variety in visit order while preserving
     * spatial locality within each segment.
     */
    private void buildVisitQueue(List<PlayerShop> shops, List<HiredMerchant> merchants) {
        List<ShopKeeper> allShops = new ArrayList<>();
        for (HiredMerchant m : merchants) {
            allShops.add(new HiredMerchantAdapter(m));
        }
        for (PlayerShop s : shops) {
            allShops.add(new PlayerShopAdapter(s));
        }

        if (allShops.isEmpty()) {
            visitQueue = allShops;
            visitIndex = 0;
            segmentStartIndices.clear();
            walkChance = BASE_WALK_CHANCE;
            return;
        }

        // Sort by effective distance from current position (same-row shops prioritized)
        Point currentPos = getChr().getPosition();
        allShops.sort((a, b) -> {
            double distA = effectiveDistance(currentPos, a.getPosition());
            double distB = effectiveDistance(currentPos, b.getPosition());
            return Double.compare(distA, distB);
        });

        // Deck cut: split into 3-4 segments and rearrange for visit order variety
        Random rng = new Random();
        int numSegments = allShops.size() < 4 ? 1 : (3 + rng.nextInt(2));
        visitQueue = deckCut(allShops, numSegments, rng);

        visitIndex = 0;
        walkChance = BASE_WALK_CHANCE;
    }

    /**
     * Splits the sorted shop list into segments and rearranges them randomly,
     * like cutting a deck of cards. Preserves spatial locality within each segment
     * but varies which part of the room the bot starts browsing from.
     */
    private List<ShopKeeper> deckCut(List<ShopKeeper> sorted, int numSegments, Random rng) {
        segmentStartIndices.clear();

        if (sorted.size() <= numSegments) {
            segmentStartIndices.add(0);
            return new ArrayList<>(sorted);
        }

        int segmentSize = sorted.size() / numSegments;
        List<List<ShopKeeper>> segments = new ArrayList<>();

        for (int i = 0; i < numSegments; i++) {
            int start = i * segmentSize;
            int end = (i == numSegments - 1) ? sorted.size() : (i + 1) * segmentSize;
            segments.add(new ArrayList<>(sorted.subList(start, end)));
        }

        Collections.shuffle(segments, rng);

        List<ShopKeeper> result = new ArrayList<>();
        for (List<ShopKeeper> segment : segments) {
            segmentStartIndices.add(result.size());
            result.addAll(segment);
        }

        return result;
    }

    private double effectiveDistance(Point from, Point to) {
        double distance = Math.sqrt(
                Math.pow(from.x - to.x, 2) +
                        Math.pow(from.y - to.y, 2)
        );
        // Same-row shops are heavily prioritized (they're on the same screen row)
        if (Math.abs(from.y - to.y) < 1.0) {
            distance /= 1000.0;
        }
        return distance;
    }

    //

    private void browsingRoom() {
        if (isAllShopsVisited()) {
            return;
        }
        ShopKeeper next = visitQueue.get(visitIndex);
        walkToShop(next);
    }

    private boolean isAllShopsVisited() {
        return visitQueue.isEmpty() || visitIndex >= visitQueue.size();
    }

    //

    /**
     * Decides whether to walk to the next shop using an escalating probability system.
     * - Segment starts (including first shop in room): always walk
     * - Otherwise: roll against walkChance. If walk, reset to base. If not, increment chance.
     * This guarantees bots eventually walk after a streak of stationary clicks,
     * creating natural visual variety.
     */
    private void walkToShop(ShopKeeper shop) {
        currentTarget = shop;
        boolean isSegmentStart = segmentStartIndices.contains(visitIndex);

        try {
            boolean shouldWalk;
            if (isSegmentStart) {
                shouldWalk = true;
            } else {
                shouldWalk = Math.random() < walkChance;
            }

            if (shouldWalk) {
                walkChance = BASE_WALK_CHANCE;
                Point pos = BotHelpers.getRandomizedPointXAxis(currentTarget.getPosition());
                GCMovement.move(this.getChr(), pos.x, pos.y);
                shopArrivalPolls = 0; // ENTER_SHOP holds until this walk lands
            } else {
                walkChance = Math.min(1.0, walkChance + WALK_CHANCE_INCREMENT);
            }
        } catch (Exception ignored) {
            // Issue with path mapping. all good just don't walk then
        }
        visitIndex++;
    }

    //

    private void actionPhase() {
//        SocialCommands.BotChatbubble(getChr(), "Action Phase");
        // handle any desired action (comments, resting, scrolling etc)
        // possibly an action phase 2 after processing shop
        return;
    }

    private void enterShop() {
        // enter shop based on location & cross off from internal list to not repeat
        seated = false;
        currentIsReal = false;
        if (currentTarget == null) {
//            System.out.println("processShop ERROR. NO VALID MERCHANT / SHOP");
            return;
        }
        if (!isRealPlayersShop(currentTarget)) {
            currentTarget.visitShop(getChr());
            seated = true;   // bots' shops: bought from as before, seated or not
            return;
        }
        currentIsReal = true;
        Object key = currentTarget.shop();
        int me = getChr().getId();
        boolean qa = QaLog.on(QaLog.Topic.FM_BUY);
        if (!currentTarget.isOpen()) {
            realShopLastVisit.put(key, System.currentTimeMillis());
            if (qa) {
                qaVisit("passes", "closed");
            }
            return;
        }
        int free = currentTarget.freeVisitorSlots();
        long now = System.currentTimeMillis();
        Occupant claim = realShopOccupant.compute(key, (k, held) -> held == null || held.botId() == me
                || now - held.since() > OCCUPANT_STALE_MS ? new Occupant(me, now) : held);
        Integer occupant = claim.botId() == me ? null : claim.botId();
        if (!mayEnterRealShop(true, free, occupant, me)) {
            if (occupant == null) {
                realShopOccupant.remove(key, claim);
            }
            // busy: come back to it once later in this lap, and leave the slots to the players
            if (deferredThisRoom.add(key)) {
                visitQueue.add(currentTarget);
            } else {
                realShopLastVisit.put(key, System.currentTimeMillis());
            }
            if (qa) {
                qaVisit("waits outside", free + " free slots" + (occupant != null ? ", another bot is inside" : "")
                        + ", keeps " + REAL_SHOP_SPARE_SLOTS + " free for players");
            }
            return;
        }
        seated = currentTarget.enter(getChr());
        realShopLastVisit.put(key, System.currentTimeMillis());
        if (!seated) {
            realShopOccupant.remove(key, claim);
        }
        if (qa) {
            qaVisit(seated ? "enters" : "could not enter", describe(currentTarget));
        }
    }

    private void qaVisit(String what, String why) {
        QaLog.map(QaLog.Topic.FM_BUY, getChr().getMapId(), getChr().getName() + " " + what + " "
                + currentTarget.getOwner() + "'s shop: " + why);
    }

    private void processShop() {
        // handle any shop related stuff, comments, buying, offering, window shopping
        if (currentTarget == null) {
//            System.out.println("processShop ERROR. NO VALID MERCHANT / SHOP");
            return;
        }
        if (!seated) {
            return;   // a real player's shop it could not sit down in: nothing to browse
        }
        purchaseShopItems();
        waitFor(1000); // browse beat before moving on to EXIT_SHOP
    }

    private List<PlayerShopItem> readShopItems() {
        List<PlayerShopItem> items = List.of();
        if (currentTarget instanceof HiredMerchantAdapter) {
            items = ((HiredMerchantAdapter) currentTarget).getMerchant().getItems();
        } else if (currentTarget instanceof PlayerShopAdapter) {
            items = ((PlayerShopAdapter) currentTarget).getShop().getItems();
        }
        return items;
    }

    /*
    Calculates a % chance to purchase 1 item based on probability.
     */
    private void purchaseShopItems() {
        List<PlayerShopItem> items = readShopItems();
        if (items.isEmpty()) {
            return;
        }

        for (int itemIndex = 0; itemIndex < items.size(); itemIndex++) {
            PlayerShopItem pItem = items.get(itemIndex);
            if (pItem.getBundles() < 1) {
                continue;
            }
            Item thisItem = pItem.getItem();
            int itemPrice = pItem.getPrice(); // item's FM price
            if (BotConsignment.isConsigned(pItem)) {
                continue;   // ticket 11: a bot's own listing is for players; the allowance must not pay a bot
            }
            if (ScrollRules.isPlainScroll(thisItem.getItemId())
                    && ScrollWants.wanted(thisItem.getItemId(), itemPrice)) {
                if (buyWantedScroll(pItem, itemIndex, itemPrice)) {
                    break;   // one purchase per visit, as below
                }
                continue;
            }
            Integer itemMarketValue = ItemUtilities.getItemMarketValue(thisItem);
            Boolean purchase = shouldPurchaseItem(itemPrice, itemMarketValue);
            // Ticket 56A: a real player's shop is paid within a price ceiling and a daily cap per seller account
            int sellerAccount = purchase ? realSellerAccount(currentTarget) : -1;
            boolean qa = QaLog.on(QaLog.Topic.FM_BUY);
            if (qa && !purchase) {
                qaBuy("skip", thisItem.getItemId(), itemPrice, itemMarketValue, itemMarketValue == null
                        || itemMarketValue <= 0 ? "no normal value" : itemPrice > itemMarketValue * 1.2
                        ? "over 1.2x normal value" : "price roll said no");
            }
            if (purchase && sellerAccount != -1) {
                if (sellerAccount == UNKNOWN_SELLER) {
                    if (qa) {
                        qaBuy("skip", thisItem.getItemId(), itemPrice, itemMarketValue, "seller account unknown");
                    }
                    continue;
                }
                if (!BotSellerCaps.withinPriceCeiling(itemPrice, itemMarketValue)) {
                    if (qa) {
                        qaBuy("skip", thisItem.getItemId(), itemPrice, itemMarketValue,
                                "above the price ceiling for a player's shop");
                    }
                    continue;
                }
                if (!BotSellerCaps.tryReserve(sellerAccount, currentTarget.getOwner(), itemPrice)) {
                    if (qa) {
                        qaBuy("skip", thisItem.getItemId(), itemPrice, itemMarketValue, "seller's daily cap reached");
                    }
                    continue;
                }
            }
            if (purchase) {
                StringBuilder unable = new StringBuilder();
                long topUp = prepareToPay(thisItem, itemPrice, unable);
                if (topUp < 0) {
                    if (sellerAccount >= 0) {
                        BotSellerCaps.release(sellerAccount, itemPrice);   // nothing will be paid
                    }
                    if (qa) {
                        qaBuy("skip", thisItem.getItemId(), itemPrice, itemMarketValue, unable.toString());
                    }
                    continue;
                }
                String buymsg = getRandomResolvedLine(FMBot.this, "PurchaseItem");
                if (buymsg != null) {
                    currentTarget.chat(getChr(), buymsg);
                }
                short bundlesBefore = pItem.getBundles();
                if (currentTarget instanceof HiredMerchantAdapter) {
                    currentTarget.botBuyItem(getChr(), pItem, (short) 1);
                } else if (currentTarget instanceof PlayerShopAdapter) {
                    currentTarget.botBuyItemPlayerShop(getChr(), pItem, itemIndex, (short) 1);
                }
                boolean bought = pItem.getBundles() < bundlesBefore;
                if (sellerAccount >= 0 && !bought) {
                    BotSellerCaps.release(sellerAccount, itemPrice);   // the purchase failed: nothing was paid
                }
                if (!bought && topUp > 0 && BotHoldings.spendMeso(getChr(), (int) topUp)) {
                    FMBotWallet.refund(getChr().getId(), topUp);   // the allowance it drew is not spent
                }
                if (bought && thisItem.getInventoryType() == InventoryType.EQUIP) {
                    boughtEquipIds.addLast(thisItem.getItemId());
                }
                if (qa) {
                    qaBuy(bought ? "BUY" : "buy failed", thisItem.getItemId(), itemPrice, itemMarketValue,
                            (sellerAccount >= 0 ? "player shop, account " + sellerAccount
                                    + " (" + BotSellerCaps.status(sellerAccount) + ")" : "bot shop")
                                    + (topUp > 0 ? ", " + topUp + " from the shopping allowance" : "")
                                    + (bought ? "" : ", the shop refused the sale"));
                }
                break;
            }
        }
    }

    /**
     * Ticket 11: a grinding bot posted a want for this scroll ({@link ScrollWants}). The bot that wants it pays the
     * price from its own mesos (never this shopper's allowance); a real player's shop is still bound by 56A's price
     * ceiling and per-seller cap. True when it was bought and handed over.
     */
    private boolean buyWantedScroll(PlayerShopItem pItem, int itemIndex, int itemPrice) {
        int scrollId = pItem.getItem().getItemId();
        Integer value = ItemUtilities.getItemMarketValue(pItem.getItem());
        int sellerAccount = realSellerAccount(currentTarget);
        boolean qa = QaLog.on(QaLog.Topic.FM_BUY);
        if (sellerAccount == UNKNOWN_SELLER) {
            return false;
        }
        boolean fromPlayer = sellerAccount >= 0;
        if (fromPlayer && (!BotSellerCaps.withinPriceCeiling(itemPrice, value)
                || !BotSellerCaps.tryReserve(sellerAccount, currentTarget.getOwner(), itemPrice))) {
            if (qa) {
                qaBuy("skip wanted", scrollId, itemPrice, value, "56A: over the price ceiling or the seller's cap");
            }
            return false;
        }
        ShopKeeper shop = currentTarget;
        String forBot = ScrollWants.fill(getChr(), scrollId, itemPrice, fromPlayer, () -> {
            short before = pItem.getBundles();
            if (shop instanceof HiredMerchantAdapter) {
                shop.botBuyItem(getChr(), pItem, (short) 1);
            } else if (shop instanceof PlayerShopAdapter) {
                shop.botBuyItemPlayerShop(getChr(), pItem, itemIndex, (short) 1);
            }
            return pItem.getBundles() < before;
        });
        if (forBot == null && fromPlayer) {
            BotSellerCaps.release(sellerAccount, itemPrice);
        }
        if (qa) {
            qaBuy(forBot != null ? "BUY for " + forBot : "skip wanted", scrollId, itemPrice, value,
                    forBot != null ? "scroll wanted by " + forBot + ", paid from its own mesos"
                            + (fromPlayer ? ", player shop (" + BotSellerCaps.status(sellerAccount) + ")" : "")
                            : "the wanting bot could not take it now");
        }
        return forBot != null;
    }

    /**
     * Readies the bot to pay {@code price} for {@code item}: room in its inventory (letting its oldest bought equip
     * go if that tab is full) and the mesos (topping up the shortfall from {@link FMBotWallet}). Returns the
     * mesos added to its wallet, or -1 with the reason in {@code why} when it cannot buy.
     */
    private long prepareToPay(Item item, int price, StringBuilder why) {
        Character me = getChr();
        if (!BotHoldings.canHold(me, item) && !makeRoom(item.getInventoryType())) {
            why.append("inventory full (").append(item.getInventoryType()).append(")");
            return -1;
        }
        long add = FMBotWallet.reserveShortfall(me.getId(), me.getMeso(), price);
        if (add < 0) {
            why.append("cannot afford: holds ").append(me.getMeso()).append(", shopping allowance left today ")
                    .append(FMBotWallet.remaining(me.getId()));
            return -1;
        }
        if (add > 0 && !BotHoldings.grantMeso(me, (int) add)) {
            FMBotWallet.refund(me.getId(), add);
            why.append("wallet cannot take the top-up");
            return -1;
        }
        return add;
    }

    /** Lets go of the oldest equip this bot bought to free an equip slot; false when it holds none of them. */
    private boolean makeRoom(InventoryType type) {
        if (type != InventoryType.EQUIP) {
            return false;
        }
        Iterator<Integer> oldest = boughtEquipIds.iterator();
        while (oldest.hasNext()) {
            int id = oldest.next();
            oldest.remove();
            Item held = getChr().getInventory(InventoryType.EQUIP).findById(id);
            if (held != null && BotHoldings.destroyHeldItem(getChr(), held)) {
                return true;
            }
        }
        return false;
    }

    private void qaBuy(String what, int itemId, int price, Integer value, String why) {
        QaLog.map(QaLog.Topic.FM_BUY, getChr().getMapId(), getChr().getName() + " " + what + " " + itemId + " @ "
                + price + " (normal " + value + ") in " + (currentTarget == null ? "?" : currentTarget.getOwner())
                + "'s shop: " + why);
    }

    private static final int UNKNOWN_SELLER = -2;

    /**
     * The account of a real player who owns this shop, -1 for a bot's shop (not capped), or {@link #UNKNOWN_SELLER}
     * when a player's shop's account cannot be found (then the bot does not buy).
     */
    static int realSellerAccount(ShopKeeper shop) {
        if (shop instanceof PlayerShopAdapter adapter) {
            Character owner = adapter.getShop().getOwner();
            if (owner == null) {
                return UNKNOWN_SELLER;
            }
            if (BotHelpers.isBot(owner) || BotHelpers.isArtificial(owner.getId())) {
                return -1;
            }
            BotSellerCaps.rememberAccount(owner.getId(), owner.getAccountID());
            return owner.getAccountID();
        }
        if (shop instanceof HiredMerchantAdapter adapter) {
            HiredMerchant merchant = adapter.getMerchant();
            if (merchant instanceof soloMapling.FreeMarket.HiredMerchantArtificial
                    || BotHelpers.isArtificial(merchant.getOwnerId())) {
                return -1;
            }
            int account = BotSellerCaps.accountOf(merchant.getOwnerId());
            return account < 0 ? UNKNOWN_SELLER : account;
        }
        return -1;
    }

    private void exitShop() {
        if (currentTarget == null) {
//            System.out.println("ERROR. NO VALID MERCHANT / SHOP");
            return;
        }
        if (seated || !currentIsReal) {
            currentTarget.removeVisitor(getChr());
        }
        if (currentIsReal) {
            realShopOccupant.computeIfPresent(currentTarget.shop(),
                    (k, held) -> held.botId() == getChr().getId() ? null : held);
        }
        currentTarget = null;
        seated = false;
        currentIsReal = false;
        discoverRealShops();
    }

    // Walks to the room's door portal, then warps out. Returns true once out of the room
    // (or immediately on a degenerate room id) so EXIT_FM_ROOM knows to advance.
    private boolean exitRoom() {
        int roomNumber = getChr().getMapId() - FM_ENTRANCE;
        if (roomNumber < 1 || !FMRoomWarpPortalId.containsKey(roomNumber)) {
            incrementFMRoom();
            return true;
        }
        Point doorPoint = getFMRoomDoorPortalPoint();
        if (!isPointNear(this.getChr().getPosition(), doorPoint, 20) && doorWalkAttempts < MAX_DOOR_WALK_ATTEMPTS) {
            if (!GCMovement.isMoving(getChr())) { // not started yet, or the last attempt was abandoned
                doorWalkAttempts++;
                GCMovement.move(getChr(), doorPoint.x, doorPoint.y);
            }
            return false; // still walking to the door - re-check on the next tick
        }
        doorWalkAttempts = 0;
        botExitFMRoom(getChr(), fmRoom);
        incrementFMRoom();
        return true;
    }

    /** !qa fm-1 (test server): shop only this FM room (1-12) from now on. */
    public void pinRoom(int room) {
        pinnedRoom = room;
        fmRoom = room;
    }

    private void evaluateFMRoom() {
        if (pinnedRoom > 0) {
            fmRoom = pinnedRoom;
            return;
        }
        if (getChr().getMapId() == FM_ENTRANCE) {
            fmRoom = generateRandomNumber(1, 12);
            return;
        }
        fmRoom = getChr().getMapId() - FM_ENTRANCE;
    }

    private void incrementFMRoom() {
        if (pinnedRoom > 0) {
            fmRoom = pinnedRoom;
            return;
        }
        fmRoom++;
        if (fmRoom >= 13) {
            fmRoom = 1;
        }
    }

    private Point getFMRoomDoorPortalPoint() {
        int roomNumber = getChr().getMapId() - FM_ENTRANCE;
        return getChr().getMap().getPortal(FMRoomWarpPortalId.get(roomNumber)).getPosition();
    }

    /**
     * Determines whether to purchase an item based on price-to-market-value ratio
     * Uses probabilistic logic to simulate buying behavior with varying desperation levels
     *
     * @param askingPrice the asking price for the item
     * @param marketValue the estimated fair market value
     * @return true if item should be purchased, false otherwise
     */
    public static boolean shouldPurchaseItem(Integer askingPrice, Integer marketValue) {
        if (marketValue == null) return false;
        if (askingPrice <= 0 || marketValue <= 0) return false;

        double ratio = (double) askingPrice / marketValue;
        if (ratio > 1.2) return false; // Never buy 20%+ overpriced

        double[] thresholds = {0.7, 0.85, 0.95, 1.0, 1.05, 1.1, 1.15, 1.2};
        double[] probabilities = {1.0, 0.95, 0.85, 0.75, 0.55, 0.35, 0.15, 0.05};

        for (int i = 0; i < thresholds.length; i++) {
            if (ratio <= thresholds[i]) {
                return new Random().nextDouble() < probabilities[i];
            }
        }

        return false;
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        getDebugger().debugLoggingFull(String.format("%s TutorialBotState: %s", this.getChr().getName(), fmBotState), String.format("%s", fmBotState));

        switch (fmBotState) {
            case RESET:
                resetFMBotState();
                setFMBotState(FMBotState.NAV_TO_FM_ROOM);
                break;
            case NAV_TO_FM_ROOM:
                navToFMRoom();
                if (isInsideFMRooms(this.getChr())) {
                    setFMBotState(FMBotState.PROCESS_ROOM);
                }
                break;
            case PROCESS_ROOM:
                processRoom();
                if (isAllShopsVisited()) {
                    setFMBotState(FMBotState.EXIT_FM_ROOM);
                    return;
                }
                setFMBotState(FMBotState.BROWSING_ROOM);
                break;
            case BROWSING_ROOM:
                browsingRoom();
                setFMBotState(FMBotState.ACTION_PHASE);
                break;
            case ACTION_PHASE:
                actionPhase();
                setFMBotState(FMBotState.ENTER_SHOP);
                break;
            case ENTER_SHOP:
                if (GCMovement.isMoving(getChr()) && shopArrivalPolls++ < MAX_SHOP_ARRIVAL_POLLS) {
                    return; // still walking to the shop - re-check on the next tick
                }
                enterShop();
                setFMBotState(FMBotState.PROCESS_SHOP);
                break;
            case PROCESS_SHOP:
                processShop();
                setFMBotState(FMBotState.EXIT_SHOP);
                break;
            case EXIT_SHOP:
                exitShop();
                if (isAllShopsVisited()) {
                    setFMBotState(FMBotState.EXIT_FM_ROOM);
                    return;
                }
                setFMBotState(FMBotState.BROWSING_ROOM);
                break;
            case EXIT_FM_ROOM:
                if (exitRoom()) {
                    setFMBotState(FMBotState.NAV_TO_FM_ROOM);
                }
                break;
            default:
                log("Unexpected state: " + fmBotState);
                state = BotState.FINISHED;
                resetFMBotState();
                throw new IllegalStateException("Unexpected state: " + state);
        }
    }

    @Override
    public void displayCommands(Character chr) {
        SocialCommands.displayPlayerChatCommands(chr, hint);
    }

    @Override
    public void processMessages() {
        try {
            ChatMessage message = MessageQueue.getInstance().getMessageWithTimeout("secondary", 1, TimeUnit.SECONDS);
            if (message == null) {
                return;
            }
//            handleBetCommand(message);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
