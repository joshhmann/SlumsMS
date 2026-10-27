package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import database.note.NoteDao;
import server.Trade;
import service.NoteService;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotMessagingSystem.ChatMessage;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MessageQueue;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeSM;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;
import soloMapling.server.NXCodeManager;
import tools.PacketCreator;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.botLeaveMessenger;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.botSendMessengerChat;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.botTypingStatus;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.isMessengerInviteAccepted;
import static soloMapling.ArtificialPlayer.BotCommandsPack.MapleMessengerCommands.sendMessengerInviteComplete;
import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.BUYING_MERCHANT_BOT;
import static soloMapling.ArtificialPlayer.BotTypeManager.BotType.SELLING_MERCHANT_BOT;
import static soloMapling.ArtificialPlayer.BotTypeManager.convertBotType;
import static soloMapling.BotLogger.log;
import static soloMapling.Environment.PlatformPlacement.botMoveToPlatformAnyUnoccupiedSpotDynamic;
import static soloMapling.Environment.PlatformPlacement.getCurrentPlatform;
import static soloMapling.Environment.PlatformPlacement.getMainPlatformIds;
import static soloMapling.FreeMarket.ArtificialShopGenerator.generateItem;
import static soloMapling.server.SoloMaplingUtilities.getRandomElement;
import static soloMapling.server.SoloMaplingUtilities.random;
import static soloMapling.server.SoloMaplingUtilities.rollChanceInverse;

public class NXMerchantBot extends BotSM {
    private NXState nxState = NXState.SETUP;
    private List<String> hint = Collections.singletonList(getChr().getName());
    private int advertiseCycles = 0;
    private static final int MAX_ADVERTISE_CYCLES = 15;
    private NxCodeDelivery delivery; // 1.6.0 nx-1: the code being handed over (DELIVER_CODE .. AWAIT_MESSENGER)
    private volatile boolean qaHold; // 1.6.0 nx-1: a !qa nx-1 bot stays an NX merchant instead of converting back

    private enum NXState {
        SETUP,
        ADVERTISE,
        CHECK_TRADES,
        DELIVER_CODE,
        AWAIT_MESSENGER,
        CONVERT_BACK
    }

    private static final List<String> FLAVOR_NODES = List.of("ScamMessages", "BeggingMessages", "RWTMessages", "FunnyMessages");

    public NXMerchantBot(Character character) {
        super(character);
        dialoguePath = "MerchantBotDialogue.yaml";
        botType = "MerchantBot";
    }

    private void setupNXSale() {
        // Use a filler item as the visual representation in trade
        client.inventory.Item filler = generateItem(4031865, 1, 100);
        getTradeInventory().setItemForSaleMain(filler);
        getTradeWants().resetTradeWants();
        int fiftyMill = 50_000_000;
        getTradeWants().setMesoWanted(fiftyMill);
        setTradeMode(BotTradeSM.TradeMode.SELLING);
        resetLastTradeResult();
        resetLastTradedCharacter();
    }

    /** rc6 say-do: its code is up for trade (the filler item in the window, 50m wanted): "S> 10k nx code" is true. */
    public boolean sellsCode() {
        return takesTradesNow() && getTradeInventory() != null && getTradeInventory().getMainItemForSale() != null;
    }

    private void advertise() {
        List<String> messages = List.of(
                "Selling 10k nx cash code, 50m TRADE ME!",
                "S> 10k NX code 50m, no lowballs",
                "NX CODE 10k >> 50m trade me!! legit only",
                "10k nx cash code for 50m, Pros only",
                "SELLING NX 10K CODE!! 50m!! no scammers",
                "S>> 10,000 NX code, 50m, serious offers only",
                "got nx codes, 10k for 50m, trade me fast"
        );
        String msg = getRandomElement(messages);
        soloMapling.ArtificialPlayer.BotConvo.SayDo.withSource("ad", () -> SocialCommands.BotSpeak(getChr(), msg));
    }

    /**
     * 1.6.0 nx-1: hands over the code for the trade that just completed (the 50m is already the bot's). Used to wait
     * 30 s for the messenger on the tick thread and type the code only if the buyer accepted: a declined or missed
     * invite, or a failed insert, left the buyer 50m down with no code. {@link NxCodeDelivery} now always ends with
     * the buyer holding the NX (messenger, note, whisper, or a direct credit if the code cannot be stored).
     */
    private void deliverNXCode() {
        Character buyer = getLastTradedCharacter();
        boolean paid = getLastTradeResult() == Trade.TradeResult.SUCCESSFUL;
        resetLastTradeResult();
        resetLastTradedCharacter();
        if (!paid || buyer == null) {
            nxState = NXState.CONVERT_BACK;
            return;
        }
        if (BotHelpers.isBot(buyer)) { // a bot never redeems: no code row for it
            QaLog.any(QaLog.Topic.TRADE, "NXCODE " + getChr().getName() + " sold to bot " + buyer.getName() + ": no code");
            nxState = NXState.CONVERT_BACK;
            return;
        }
        delivery = new NxCodeDelivery(new GamePort(), buyer.getId(), buyer.getName());
        if (delivery.start()) {
            nxState = NXState.AWAIT_MESSENGER;
            waitFor(2000);
        } else {
            delivery = null;
            nxState = NXState.CONVERT_BACK;
        }
    }

    private void awaitMessenger() {
        if (delivery == null || delivery.poll()) {
            delivery = null;
            nxState = NXState.CONVERT_BACK;
            if (!isWaiting()) { // typeCode already holds the bot until its lines and leave have landed
                waitFor(2500);  // the messenger leave lands before CONVERT_BACK
            }
            return;
        }
        waitFor(2000);
    }

    private Character onlineById(int id) {
        Character c = getChr().getWorldServer() == null ? null : getChr().getWorldServer().getPlayerStorage().getCharacterById(id);
        return c != null && c.isLoggedinWorld() ? c : null;
    }

    /** The game side of {@link NxCodeDelivery}. */
    private final class GamePort implements NxCodeDelivery.Port {
        public String mint(int nx) {
            return NXCodeManager.mintCode(nx);
        }

        public boolean saveNote(String buyerName, String text) {
            return new NoteService(new NoteDao()).sendNormal(text, getChr().getName(), buyerName);
        }

        public void showNotes(int buyerId) {
            Character b = onlineById(buyerId);
            if (b != null) {
                new NoteService(new NoteDao()).show(b);
            }
        }

        public boolean online(int buyerId) {
            return onlineById(buyerId) != null;
        }

        public void invite(int buyerId) {
            Character b = onlineById(buyerId);
            if (b != null) {
                sendMessengerInviteComplete(getChr(), b);
            }
        }

        public boolean accepted(int buyerId) {
            Character b = onlineById(buyerId);
            return b != null && getChr().getMessenger() != null && isMessengerInviteAccepted(getChr(), b);
        }

        public void typeCode(String code) {
            Character bot = getChr();
            botTypingStatus(bot, true);
            BotTiming.chain()
                    .pause(3000).run(() -> sendMessengerLine(bot, "here is the 10k nx code... be sure to write it down. Remember to NOT include dashes"))
                    .pause(7000).run(() -> sendMessengerLine(bot, code))
                    .pause(2000).run(() -> sendMessengerLine(bot, "enjoy it!"))
                    .pause(2000).run(() -> botLeaveMessenger(bot))
                    .start();
            waitFor(14_500); // hold CONVERT_BACK until the lines and the leave land
        }

        public void leaveMessenger() {
            botLeaveMessenger(getChr());
        }

        public boolean creditDirect(int buyerId, int nx) {
            Character b = onlineById(buyerId);
            if (b == null || b.getCashShop() == null) {
                return false;
            }
            b.getCashShop().gainCash(1, nx); // NX credit, as the coupon would
            b.dropMessage(5, "You received " + nx + " NX (Cash Shop balance).");
            return true;
        }

        public void whisper(int buyerId, String line) {
            Character b = onlineById(buyerId);
            if (b != null) {
                b.sendPacket(PacketCreator.getWhisperReceive(getChr().getName(), Math.max(0, getChr().getClient().getChannel() - 1), false, line));
            }
        }

        public void say(String line) {
            SocialCommands.BotSpeak(getChr(), line);
        }

        public void qa(String line, boolean problem) {
            QaLog.any(QaLog.Topic.TRADE, line + " (bot " + getChr().getName() + ")");
            if (problem) {
                log("[NXMerchantBot] " + getChr().getName() + ": " + line);
            }
        }

        public long now() {
            return System.currentTimeMillis();
        }
    }

    private static void sendMessengerLine(Character bot, String line) {
        if (bot.getMessenger() != null) { // the buyer may have closed it; the note still has the code
            botSendMessengerChat(bot, line);
        }
    }

    // Dynamic movement lands on the exact picked pixel, so the old nudgeAwayFromOverlap
    // band-aid (recorded paths piling bots onto fixed endpoints) is no longer needed here.
    private boolean tryPlatformShuffle() {
        if (rollChanceInverse(15)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getCurrentPlatform(getChr()));
            return true;
        } else if (rollChanceInverse(40)) {
            botMoveToPlatformAnyUnoccupiedSpotDynamic(getChr(), getRandomElement(List.of("m1", "m5")));
            return true;
        }
        return false;
    }

    private void convertBack() {
        if (random.nextBoolean()) {
            convertBotType(getChr(), SELLING_MERCHANT_BOT);
        } else {
            convertBotType(getChr(), BUYING_MERCHANT_BOT);
        }
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
        // Skip straight to delivery if trade completed while we were in TRADING state
        if (getLastTradeResult() == Trade.TradeResult.SUCCESSFUL && nxState != NXState.DELIVER_CODE
                && nxState != NXState.AWAIT_MESSENGER && nxState != NXState.CONVERT_BACK) {
            nxState = NXState.DELIVER_CODE;
        }

        getDebugger().debugLoggingFull(
                String.format("%s NXMerchantBot: %s", getChr().getName(), nxState),
                String.format("%s", nxState));

        switch (nxState) {
            case SETUP:
                setupNXSale();
                nxState = NXState.ADVERTISE;
                break;
            case ADVERTISE:
                // 4% (1/25) Chance to advertise flavor, 96% chance to advertise NX
                if (rollChanceInverse(25)) {
                    getDialogueHandler().executeBotFlavorDialogueBacked(getRandomElement(FLAVOR_NODES), this); // rc6 say-do
                } else {
                    advertise();
                }
                nxState = NXState.CHECK_TRADES;
                break;
            case CHECK_TRADES:
                checkForTrades();
                advertiseCycles++;
                tryPlatformShuffle();
                if (getLastTradeResult() == Trade.TradeResult.SUCCESSFUL) {
                    nxState = NXState.DELIVER_CODE;
                } else if (advertiseCycles >= MAX_ADVERTISE_CYCLES) {
                    nxState = NXState.CONVERT_BACK;
                } else {
                    nxState = NXState.ADVERTISE;
                }
                break;
            case DELIVER_CODE:
                deliverNXCode();
                break;
            case AWAIT_MESSENGER:
                awaitMessenger();
                break;
            case CONVERT_BACK:
                if (qaHold) {
                    advertiseCycles = 0;
                    nxState = NXState.SETUP;
                    break;
                }
                convertBack();
                break;
            default:
                log("Unexpected state: " + nxState);
                state = BotState.FINISHED;
                throw new IllegalStateException("Unexpected state: " + nxState);
        }
    }

    /** 1.6.0 nx-1: keeps this bot selling codes (no conversion back to an item merchant) for a QA run. */
    public void holdForQa() {
        qaHold = true;
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
