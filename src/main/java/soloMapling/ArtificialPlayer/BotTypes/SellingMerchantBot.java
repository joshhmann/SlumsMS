package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.FreeMarket.FMItem;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.NX_MERCHANT_BOT;
import static soloMapling.ArtificialPlayer.BotTypeManager.convertBotType;
import static soloMapling.BotLogger.log;
import static soloMapling.Environment.PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic;
import static soloMapling.Environment.PlatformPlacement.getCurrentPlatform;
import static soloMapling.Environment.PlatformPlacement.getMainPlatformIds;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateDarkScrollsList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateItem;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generatePotionsList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateScrollsList;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateThiefStarsList;
import static soloMapling.FreeMarket.FMEconomyManager.priceAdjustmentRules;
import static soloMapling.itemPool.ItemInformationProviderUtilities.getItemName;
import static soloMapling.itemPool.ItemUtilities.getItemMarketValue;
import static soloMapling.server.SoloMaplingUtilities.getRandomElement;
import static soloMapling.server.SoloMaplingUtilities.random;
import static soloMapling.server.SoloMaplingUtilities.rollChanceInverse;

public class SellingMerchantBot extends BotSM {
    private SellingState sellingState = SellingState.RESET;
    private List<String> hint = Collections.singletonList(getChr().getName());
    private List<FMItem> itemsToSell;
    private int itemIndex = 0;
    private boolean movedDuringAdvertise = false;

    private enum SellingState {
        RESET,
        SELECT_ITEM,
        ADVERTISE,
        CHECK_TRADES,
        IDLE_ACTIONS
    }

    private static final List<String> FLAVOR_NODES = List.of("ScamMessages", "BeggingMessages", "RWTMessages", "FunnyMessages");

    // b271 (the selling half of the FMBot fix): the item pool does not know every item's value (getItemMarketValue
    // returns null for an item its id, or its equip facts, do not price). Such an item is never stocked and never
    // sold, and its id is warned about once per JVM so a live log names the item instead of repeating.
    private static final Set<Integer> missingMarketValueIds = ConcurrentHashMap.newKeySet();
    static final int MISSING_MARKET_VALUE_LOG_LIMIT = 1_000;
    private static final Logger LOG = LoggerFactory.getLogger(SellingMerchantBot.class);

    public SellingMerchantBot(Character character) {
        super(character);
        dialoguePath = "MerchantBotDialogue.yaml";
        botType = "MerchantBot";
    }

    private void resetState() {
        itemIndex = 0;
        loadItemList();
        sellingState = SellingState.RESET;
    }

    private void loadItemList() {
        Supplier<List<FMItem>>[] generators = new Supplier[]{
                () -> generateScrollsList("A"),
                () -> generateDarkScrollsList("A"),
                () -> generateThiefStarsList("A"),
                () -> generatePotionsList("S")
        };
        itemsToSell = generators[random.nextInt(generators.length)].get();
    }

    private FMItem getCurrentItem() {
        if (itemsToSell == null || itemIndex >= itemsToSell.size()) {
            return null;
        }
        return itemsToSell.get(itemIndex);
    }

    private void selectNextItem() {
        if (itemsToSell == null || itemsToSell.isEmpty()) {
            loadItemList();
        }

        itemIndex++;
        if (itemIndex >= itemsToSell.size()) {
            itemIndex = 0;
            loadItemList();
        }
        if (itemsToSell == null || itemsToSell.isEmpty()) {
            return;   // this generator produced nothing (e.g. dark scrolls below pool version 39)
        }

        // b271: an item the pool cannot value is neither stocked nor sold, so walk on to the next one in this stock
        // list (at most one pass, about four items). A list with nothing valued leaves the trade state as it was
        // rather than offering a price nobody published.
        for (int tried = 0; tried < itemsToSell.size(); tried++) {
            FMItem currItem = itemsToSell.get(itemIndex);
            if (currItem != null) {
                Item item = generateItem(currItem.getItemId(), 1, 1);
                Integer askingPrice = askingPriceFor(item);
                if (askingPrice != null) {
                    stock(item, askingPrice);
                    return;
                }
            }
            itemIndex++;
            if (itemIndex >= itemsToSell.size()) {
                itemIndex = 0;
            }
        }
    }

    /**
     * b271: what this bot asks for {@code item}: its market value (a scroll at what it sells for now) times 0.9 through
     * the FM price rules, exactly as it always did. {@code null} when the item pool has no value for it - the bot then
     * neither stocks nor sells it, so nothing is ever offered at a price nobody published.
     *
     * @param item the item this bot would put in its trade window
     * @return the mesos to ask, or null when the pool does not know the item's value
     */
    static Integer askingPriceFor(Item item) {
        Integer rawValue = getItemMarketValue(item);
        // 162: a scroll is offered at what it sells for now (ScrollMarketPrice), the yaml price when that is off
        rawValue = soloMapling.Economy.ScrollMarketPrice.price(item.getItemId(), rawValue);
        if (rawValue == null || rawValue <= 0) {
            noteMissingMarketValue(item.getItemId());
            return null;
        }
        return priceAdjustmentRules((int) (rawValue * 0.9));
    }

    /** Puts one valued item up for sale at {@code askingPrice} and turns the bot into a seller. */
    private void stock(Item item, int askingPrice) {
        getTradeInventory().setItemForSaleMain(item);
        getTradeWants().resetTradeWants();
        getTradeWants().setMesoWanted(askingPrice);
        setTradeMode(BotTradeSM.TradeMode.SELLING);

        resetLastTradeResult();
        resetLastTradedCharacter();
    }

    /**
     * b271: records that the item pool has no value for {@code itemId} and warns about it once per JVM (the set is
     * bounded, so a bad item id cannot grow it without limit and the log cannot flood). The caller sells something
     * else; the warning is what makes the next live log able to name the item.
     *
     * @return true when this call was the first for the id (and so logged)
     */
    static boolean noteMissingMarketValue(int itemId) {
        if (missingMarketValueIds.size() >= MISSING_MARKET_VALUE_LOG_LIMIT || !missingMarketValueIds.add(itemId)) {
            return false;
        }
        LOG.warn("Item {} has no known market value: selling merchant bots skip it (b271)", itemId);
        return true;
    }

    private void advertise() {
        FMItem itm = getCurrentItem();
        if (itm == null) {
            return;
        }
        String itemName = getItemName(itm.getItemId());
        if (itemName != null && holdsForSale(itm.getItemId())) {
            String msg = buildSellingMessage(itemName);
            soloMapling.ArtificialPlayer.BotConvo.SayDo.withSource("ad", () -> SocialCommands.BotSpeak(getChr(), msg));
        }
    }

    /**
     * rc6 say-do: an "S> item" ad only for what this bot really has up for trade (its trade window's item) or in its
     * bag; an item it no longer holds is not advertised.
     */
    boolean holdsForSale(int itemId) {
        var forSale = getTradeInventory() == null ? null : getTradeInventory().getMainItemForSale();
        return holdsForSale(forSale, getChr() == null ? 0 : getChr().countItem(itemId), itemId);
    }

    /** Pure: the trade window's item is this one, or the bag holds at least one. */
    static boolean holdsForSale(client.inventory.Item forSale, int inBag, int itemId) {
        return forSale != null && forSale.getItemId() == itemId || inBag > 0;
    }

    static String buildSellingMessage(String itemName) {
        List<String> prefixes = List.of("Selling", "S>", "S>>", "SELL>", "Selling>");
        List<String> suffixes = List.of("You Offer", "Offer", "Trade Me", "just trade me!", "PM me",
                "no lowball", "no noobs", "no scammers", "Pros only", "hotties only",
                "baddies only", "no weebs", "English Only",
                "serious offers only", "dont waste my time", "legit only", "no time wasters");

        String msg = getRandomElement(prefixes) + " " + itemName + " " + getRandomElement(suffixes);

        int fillerCount = random.nextInt(4);
        for (int i = 0; i < fillerCount; i++) {
            msg += " @@@@@@@@";
        }

        msg = msg.replace("[", "").replace("]", "");

        if (random.nextDouble() < 0.15) {
            msg = msg.toUpperCase();
        }
        return msg;
    }

    // Dynamic movement lands on the exact picked pixel, so the old nudgeAwayFromOverlap
    // band-aid (recorded paths piling bots onto fixed endpoints) is no longer needed here.
    private boolean tryPlatformShuffleWhileAdvertising() {
        if (rollChanceInverse(10)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getCurrentPlatform(getChr()));
            return true;
        } else if (rollChanceInverse(20)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(List.of("m1", "m5")));
            return true;
        } else if (rollChanceInverse(30)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(List.of("m1", "m2")));
            return true;
        } else if (rollChanceInverse(70)) {
            int currentMap = getChr().getMapId();
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(getMainPlatformIds(currentMap)));
            return true;
        }
        return false;
    }

    private void handleIdleActions() {
        if (movedDuringAdvertise) {
            movedDuringAdvertise = false;
            return;
        }
        if (rollChanceInverse(10)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getCurrentPlatform(getChr()));
        } else if (rollChanceInverse(20)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(List.of("m1", "m5")));
        } else if (rollChanceInverse(30)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(List.of("m1", "m2")));
        } else if (rollChanceInverse(70)) {
            int currentMap = getChr().getMapId();
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(getMainPlatformIds(currentMap)));
        }
    }

    private boolean tryConvertToNXMerchant() {
        if (rollChanceInverse(100)) {
            convertBotType(getChr(), NX_MERCHANT_BOT);
            return true;
        }
        return false;
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        if (getState() == BotState.TRADING) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s SellingMerchantBot: %s", getChr().getName(), sellingState),
                String.format("%s", sellingState));

        switch (sellingState) {
            case RESET:
                resetState();
                sellingState = SellingState.SELECT_ITEM;
                break;
            case SELECT_ITEM:
                selectNextItem();
                sellingState = SellingState.ADVERTISE;
                break;
            case ADVERTISE:
                if (rollChanceInverse(25)) {
                    getDialogueHandler().executeBotFlavorDialogueBacked(getRandomElement(FLAVOR_NODES), this); // rc6 say-do
                } else {
                    advertise();
                }
                movedDuringAdvertise = tryPlatformShuffleWhileAdvertising();
                sellingState = SellingState.CHECK_TRADES;
                break;
            case CHECK_TRADES:
                checkForTrades();
                sellingState = SellingState.IDLE_ACTIONS;
                break;
            case IDLE_ACTIONS:
                handleIdleActions();
                if (tryConvertToNXMerchant()) {
                    return;
                }
                sellingState = SellingState.SELECT_ITEM;
                break;
            default:
                log("Unexpected state: " + sellingState);
                state = BotState.FINISHED;
                throw new IllegalStateException("Unexpected state: " + sellingState);
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
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
