package soloMapling.ArtificialPlayer.BotMessagingSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.ArtificialPlayer.BotMenus;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyBotOrders;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.BotShutdown;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.*;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.expirePlayerChatCommands;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;
import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.checkIfInvisibleBot;
import static soloMapling.server.ExecutorServiceManager.getExecutorService;
import static soloMapling.server.ExecutorServiceManager.getScheduledExecutorService;
import static soloMapling.BotLogger.log;

public class Dispatcher implements Runnable {

    private static final Dispatcher dispatcher = new Dispatcher(MessageQueue.getInstance());
    private final MessageQueue messageQueue;
    private final ExecutorService executor = getExecutorService();
    private final ScheduledExecutorService scheduler = getScheduledExecutorService();

    public Dispatcher(MessageQueue messageQueue) {
        log("Dispatcher OBJECT Created");
        this.messageQueue = messageQueue;
        this.scheduler.scheduleAtFixedRate(this, 0, 2, TimeUnit.SECONDS);
    }

    // Static method to access the singleton instance
    public static Dispatcher getInstance() {
        log("Dispatcher getInstance");
        return dispatcher;
    }

    @Override
    public void run() {
//        log("Dispatcher: RUN");
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: delivery touches maps and bot commands - a quiesced server drops the pass
        }
        processMessages();
    }

    //    @Override
    private void processMessages() {
        // Every waiting line, not one per pass: at one line per 2 s pass, a burst of more than five lines outlives
        // the queue's 10 s expiry (QueueCleaner) and the tail is dropped before any bot sees it.
        ChatMessage message;
        while ((message = messageQueue.getMessageNonBlocking("primary")) != null) {
            dispatch(message);
        }
    }

    private void dispatch(ChatMessage message) {
        try {
            if (partyOrder(message)) {
                return;
            }
            Collection<Character> chars_on_map = message.getMap().getCharacters();
            final int[] botToCall = new int[1];  // Using an array to hold the bot ID

            boolean characterFound = checkIfCharacterOnMap(chars_on_map, message, botToCall);

            // Determine if the message is for any registered bot
            if (characterFound) {
                handleBotRunning(botToCall, message);
            } else if (!routeWithoutExactName(chars_on_map, message)) {
                handleMessageWithNoBotName(message);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Ticket 14: a short order to the bots in the sender's party - "jason follow", "1 stop", "fire grind", "grind"
     * - carried out ahead of every other route. Party bots only: anything else, and any line that is more than an
     * order, goes on as natural chat. An order to one bot also turns its attention to the player.
     */
    private boolean partyOrder(ChatMessage message) {
        Character sender = message.getSender();
        if (getBotById(sender.getId()) != null) {
            return false; // a bot's own "lets grind" is chat, not an order to its party
        }
        java.util.Optional<BotCommandParser.Order> order = PartyBotOrders.read(sender, message.getContent());
        if (order.isEmpty()) {
            return false;
        }
        BotCommandParser.Order o = order.get();
        if (o.targets().size() == 1) {
            int botId = o.targets().get(0).id();
            BotChatRouting.attend(sender.getId(), botId, System.currentTimeMillis());
            BotNoiseFilter.noteEngaged(sender.getId(), botId);
        }
        log("[ticket14] " + sender.getName() + " -> " + o.verb() + " for "
                + (o.isAmbiguous() ? "ambiguous " + o.ambiguous() : o.everyone() ? "every party bot" : o.targets()));
        executor.execute(() -> PartyBotOrders.carryOut(sender, o));
        return true;
    }

    /**
     * bot-persistence ticket 19, reordered in 1.5.0 rc6 by the owner ({@link ChatRoute}): a line with no exact bot
     * name - (1) a name, nickname or typo wins anywhere on the map; (2) a close bot the player faces; (3) a clear reply
     * to a bot line in the hearing bubble, or to a live thread there; (4) else the closest bot in the bubble; (5) no
     * bot in the bubble: public chat, nobody answers. A yes to "u talking to me?", a menu bot's chat (ticket 21) and a
     * buff request keep their own paths. Every decision is logged ({@link RouteLog}). True when the line was handled.
     */
    private boolean routeWithoutExactName(Collection<Character> charsOnMap, ChatMessage message) {
        Character sender = message.getSender();
        long now = System.currentTimeMillis();
        String content = message.getContent();
        BotChatRouting.Pending pending = BotChatRouting.pendingFor(sender.getId());
        if (pending != null) {
            BotSM asker = getBotById(pending.botId());
            String name = asker == null || asker.getChr() == null ? null : asker.getChr().getName();
            if (BotChatRouting.confirmation(message, name, now).isPresent()) {
                handleBotRunning(new int[]{pending.botId()}, pending.original());
                return true;
            }
        }
        if (CharacterStorage.checkIfRespondant(sender) || CharacterStorage.checkIfInquirer(sender)) {
            return false;
        }
        List<BotAddressee.Candidate> nearby = new ArrayList<>();
        List<String> otherPlayers = new ArrayList<>();
        java.util.Map<Integer, ChatRoute.Bot> bots = new java.util.LinkedHashMap<>();
        boolean natural = BotMenus.natural();
        for (Character chr : charsOnMap) {
            if (chr.getId() == sender.getId() || checkIfInvisibleBot(chr.getId())) {
                continue;
            }
            BotSM bot = getBotById(chr.getId());
            if (bot != null) {
                nearby.add(new BotAddressee.Candidate(chr.getId(), chr.getName(), chr.getJob().getId()));
                java.awt.Point at = chr.getPosition();
                bots.put(chr.getId(), new ChatRoute.Bot(chr.getId(), chr.getName(), at.x, at.y,
                        natural && talksNaturally(bot)));
            } else {
                otherPlayers.add(chr.getName());
            }
        }
        if (nearby.isEmpty()) {
            return false;
        }
        BotAddressee.Result named = BotAddressee.resolve(content, nearby);
        int listening = listeningBot(sender, content, named, otherPlayers, now);
        BotChatRouting.Attention attention = listening < 0 ? null : BotChatRouting.attention(sender.getId());
        int channel = sender.getClient() == null ? 1 : sender.getClient().getChannel();
        List<MapChatLog.Line> lines = MapChatLog.recent(MapChatLog.channelOf(sender.getMap()), sender.getMapId(), now);
        java.awt.Point me = sender.getPosition();
        ChatRoute.Decision d = ChatRoute.decide(lines,
                new ChatRoute.Player(sender.getId(), sender.getName(), content, me.x, me.y, facingLeft(sender), listening,
                        attention == null ? 0 : attention.lastAt()),
                bots, soloMapling.ArtificialPlayer.BotConvo.BotThreads.liveView(channel, sender.getMapId(), now,
                        new soloMapling.ArtificialPlayer.BotConvo.BotThreads.Ask(content, me.x, me.y),
                        // only a thread the player can hear is a candidate: the router's own bubble rule
                        id -> ChatRoute.inBubble(bots.get(id), me.x, me.y)), named,
                RouteLog.wanted() ? bot -> tierOf(bot, sender.getId()) : null, now);
        java.util.function.BiConsumer<Integer, String> logAs = (bot, step) -> {
            BotSM b = bot < 0 ? null : getBotById(bot);
            String key = d.step().label.equals(step) ? d.chosenKey()
                    : b == null || b.getChr() == null ? null : b.getChr().getName();
            RouteLog.log(sender.getMapId(), sender.getId(), sender.getName(), content, d, key, step, lines, otherPlayers,
                    now);
        };
        // 1. a name wins anywhere, in the bubble or across the map
        if (d.step() == ChatRoute.Step.NAME) {
            logAs.accept(d.botId(), d.step().label);
            handleBotRunning(new int[]{d.botId()}, message);
            return true;
        }
        if (d.step() == ChatRoute.Step.NAME_ASK) {
            logAs.accept(d.botId(), d.step().label);
            askFirst(d.botId(), message, now);
            return true;
        }
        if (BotChatRouting.namesSomeoneElse(content, otherPlayers)) {
            logAs.accept(-1, ChatRoute.Step.NONE.label + " (names a player)");
            return true; // a line to another player: no bot butts in
        }
        // Mid-chat with a bot that has no menu and does not talk naturally: the next line is for that bot (ticket 21).
        int partner = BotConversation.partnerOf(sender.getId());
        BotSM partnerBot = partner < 0 ? null : getBotById(partner);
        if (partnerBot != null && !partnerBot.consumesFreeText() && partnerBot.getChr() != null
                && partnerBot.getChr().getMapId() == sender.getMapId()
                && !(BotMenus.natural() && talksNaturally(partnerBot))) {
            logAs.accept(partner, ChatRoute.Step.OTHER.label + " (menu-bot chat)");
            BotNoiseFilter.noteEngaged(sender.getId(), partner);
            BotConversation.say(partnerBot, sender, content, null);
            return true;
        }
        if (soloMapling.ArtificialPlayer.BotBuffRequestSystem.BotBuffRequestHandler.isBuffRequest(content)) {
            logAs.accept(-1, ChatRoute.Step.OTHER.label + " (buff request)");
            return true; // "hs pls": the buff handler already has the nearest eligible bot cast it
        }
        BotSM chosen = d.botId() < 0 ? null : getBotById(d.botId());
        if (chosen == null || chosen.getChr() == null || chosen.getChr().getMapId() != sender.getMapId()) {
            logAs.accept(-1, ChatRoute.Step.NONE.label);
            return true; // 5. nobody in the bubble: public chat
        }
        if (d.step().thread()) {
            // ticket 59: the player joins the thread; its last speaker answers with the thread in mind (JOIN)
            soloMapling.ArtificialPlayer.BotConvo.BotThreads.jumpIn(channel, sender.getMapId(), d.thread().id(),
                    sender.getName(), content, now);
        }
        if (d.step() == ChatRoute.Step.CLOSEST && BotChatRouting.decideConfigured()) {
            // a request for a kind of player ("which one of u is the thief") still asks the decision model first -
            // a network call, so off the dispatcher; the closest bot answers when it has no confident pick
            List<BotAddressee.Candidate> hearing = new ArrayList<>();
            for (ChatRoute.Candidate c : d.candidates()) {
                if (c.inBubble() && !c.key().startsWith("thread:")) {
                    BotAddressee.Candidate n = nearby.stream().filter(x -> x.id() == c.botId()).findFirst().orElse(null);
                    if (n != null) {
                        hearing.add(n);
                    }
                }
            }
            executor.execute(() -> {
                java.util.Optional<BotChatRouting.Choice> pick = BotChatRouting.decideByJob(content, hearing);
                if (pick.isPresent() && pick.get().confidence() >= config.YamlConfig.config.server.BOT_DECIDE_ASK) {
                    logAs.accept(pick.get().botId(), ChatRoute.Step.OTHER.label + " (job request)");
                    askFirst(pick.get().botId(), message, System.currentTimeMillis());
                } else {
                    logAs.accept(d.botId(), d.step().label);
                    routeTo(chosen, d, sender, content);
                }
            });
            return true;
        }
        logAs.accept(d.botId(), d.step().label);
        routeTo(chosen, d, sender, content);
        return true;
    }

    /** The player's facing from their last stance (odd = left), or null when it cannot be read. */
    static Boolean facingLeft(Character player) {
        if (player.getClient() instanceof client.SyntheticClient || player.getStance() == 0) {
            return null; // a pretend player sends no movement; stance 0 = no movement seen yet
        }
        return player.isFacingLeft();
    }

    /** How a bot feels about the player, for the routing log: partner, friend, rival, nemesis or neutral. */
    static String tierOf(int botId, int playerId) {
        try {
            soloMapling.ArtificialPlayer.BotDramaSystem.Romance r =
                    soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.with(playerId, botId);
            if (r != null && r.stage().together()) {
                return "partner";
            }
            soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState s =
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.state(botId, playerId);
            return s == null ? "neutral" : s.label();
        } catch (RuntimeException | LinkageError e) {
            return "-";
        }
    }

    private void routeTo(BotSM bot, ChatRoute.Decision d, Character sender, String line) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.CONVO)) { // !qa cv-8
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.CONVO, sender.getMapId(), "ROUTE \"" + line + "\" -> "
                    + bot.getChr().getName() + " [" + d.step().label + "]"
                    + (d.answers() == null ? "" : " (answers its \"" + d.answers().text() + "\")"));
        }
        executor.execute(() -> talkNaturally(bot, sender, line));
    }

    /** Whether this bot type shows a hint of its own when named (it overrides {@code BotSM.displayCommands}). */
    private static final ClassValue<Boolean> HAS_MENU = new ClassValue<>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            try {
                return type.getMethod("displayCommands", Character.class).getDeclaringClass() != BotSM.class;
            } catch (NoSuchMethodException e) {
                return false;
            }
        }
    };

    /** A line asking a merchant what it trades, when it trades something (ticket 26). */
    static boolean asksAboutStock(BotSM bot, String said) {
        return !said.isEmpty() && soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage.intentRule(said)
                .filter(i -> i == soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage.Intent.TRADE).isPresent()
                && soloMapling.ArtificialPlayer.MerchantStock.of(bot) != null;
    }

    static boolean showsOwnHint(BotSM bot) {
        return HAS_MENU.get(bot.getClass());
    }

    /**
     * Ticket 37: bots that talk like players - naming one gets a chat reply, not its menu. Service bots whose menus
     * are real choices (merchants, games, gacha, jump quests...) keep their menus.
     */
    static boolean talksNaturally(BotSM bot) {
        // HenesysBot: the townsfolk standing around Henesys are players to the people walking by; their menu is a
        // double-click extra, not a service (1.5.0: idle townsfolk only answered when named)
        return bot instanceof SocialBot || bot instanceof TrainingBot || bot instanceof FollowerBot
                || bot instanceof soloMapling.ArtificialPlayer.BotTypes.HenesysBot
                || (!bot.consumesFreeText() && !showsOwnHint(bot));
    }

    /**
     * Ticket 37: the bot listening to this player (-1 none). Attention ends - and is dropped - when the player went
     * quiet, walked away or turned to someone else.
     */
    private int listeningBot(Character sender, String line, BotAddressee.Result named, List<String> otherPlayers,
                             long now) {
        BotChatRouting.Attention attention = BotChatRouting.attention(sender.getId());
        if (attention == null) {
            return -1;
        }
        BotSM listening = BotMenus.natural() ? getBotById(attention.botId()) : null;
        Character chr = listening == null ? null : listening.getChr();
        BotChatRouting.Drift drift = BotChatRouting.drift(attention, now, chr != null && chr.getMap() != null,
                sender.getMapId(), chr == null ? -1 : chr.getMapId(),
                chr == null ? 0 : sender.getPosition().distance(chr.getPosition()));
        if (drift == BotChatRouting.Drift.LISTENING
                && BotChatRouting.addressesSomeoneElse(attention.botId(), named, line, otherPlayers)) {
            drift = BotChatRouting.Drift.GONE;
            log("[ticket37] " + sender.getName() + " turned to someone else; " + chr.getName() + " stops listening");
        }
        if (drift != BotChatRouting.Drift.LISTENING) {
            BotChatRouting.dropAttention(sender.getId());
            return -1;
        }
        return attention.botId();
    }

    /**
     * Natural chat with a bot: its own action, else a reply through the brain. Nothing typed ever opens the
     * window - an NPC window locks the player in place (owner, 2026-10-20) - so every window option has a chat
     * equivalent: "party", "where do u train", "trade", "follow", "train here", or just talk.
     */
    private void talkNaturally(BotSM bot, Character player, String line) {
        String said = BotChatRouting.withoutName(line, bot.getChr().getName());
        BotChatRouting.attend(player.getId(), bot.getChr().getId(), System.currentTimeMillis());
        BotNoiseFilter.noteEngaged(player.getId(), bot.getChr().getId()); // ticket 47: @quiet bots keeps this one
        BotConversation.engaged(bot.getChr(), player); // 1.5.1: a natural action is an exchange too: it stays put
        if (soloMapling.ArtificialPlayer.BotFameRequest.tryHandle(bot.getChr(), player, said)) {
            return; // 1.6.2 ticket 161: a friend famed the player who asked (slums.bots.famerequest)
        }
        if (soloMapling.Economy.BotGiftAsks.tryHandle(bot.getChr(), player, said)) {
            return; // 1.6.2: "got any pots?" - a friend hands over a small gift, anyone else says no (giftAsks.*)
        }
        if (bot.naturalAction(player, said)) {
            return;
        }
        BotConversation.talk(bot, player, said.isEmpty() ? "hey" : said);
    }

    private static final java.util.Map<Integer, Long> lastMenuAsk = new ConcurrentHashMap<>();
    static final long MENU_DEBOUNCE_MS = 1_500;

    /**
     * Ticket 37, the window on demand: the player double-clicked a bot (the client's character-info request).
     * Opens the bot's menu the way naming it did before natural chat; {@code BOT_MENU_WINDOW}
     * off shows the numbered chat hint instead. A second request within 1.5 s is the same double-click.
     */
    public static void openMenuOnDemand(Character player, int botId) {
        BotSM bot = getBotById(botId);
        if (player == null || bot == null || bot.getChr() == null || checkIfInvisibleBot(botId)) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastMenuAsk.put(player.getId(), now);
        if (last != null && now - last < MENU_DEBOUNCE_MS) {
            return;
        }
        dispatcher.executor.execute(() -> dispatcher.openMenu(player, bot));
    }

    private void openMenu(Character player, BotSM bot) {
        BotConversation.engaged(bot.getChr(), player); // 1.5.1: no walking off while its window is up
        BotMenus.requestWindow(player, bot);
        if (bot instanceof SocialBot socialBot) {
            socialBot.openMenuFor(player);
            return;
        }
        ChatMessage nameOnly = new ChatMessage(player, bot.getChr().getName());
        if (!bot.getRunning()) {
            startNewBotSession(bot, nameOnly);
        } else {
            handleExistingBotSession(bot, nameOnly);
        }
    }

    private void askFirst(int botId, ChatMessage message, long now) {
        BotSM bot = getBotById(botId);
        if (bot == null || bot.getChr() == null) {
            return;
        }
        BotNoiseFilter.noteEngaged(message.getSender().getId(), botId);
        BotConversation.engaged(bot.getChr(), message.getSender()); // 1.5.1: it waits for the answer where it is
        soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot.getChr(),
                BotChatRouting.askFirst(botId, message, now));
    }

    private boolean checkIfCharacterOnMap(Collection<Character> chars_on_map, ChatMessage message, int[] botToCall) {
        boolean characterFound = false;
        for (Character character : chars_on_map) {
            if (message.getContent().contains(character.getName())) {
                if (!checkIfInvisibleBot(character.getId())) {
                    botToCall[0] = character.getId();
                    characterFound = true;
                    break;
                }
            }
        }
        return characterFound;
    }

    private void handleBotRunning(int[] botToCall, ChatMessage message) {
        BotNoiseFilter.noteEngaged(message.getSender().getId(), botToCall[0]); // ticket 47: replies stay visible
        executor.execute(() -> {
            BotSM bot = getBotById(botToCall[0]);
            if (bot == null) {
                if (botToCall[0] != message.getSender().getId()) {
                    BotChatRouting.dropAttention(message.getSender().getId()); // ticket 37: they named a player
                }
                logBotNotFound(botToCall[0]);
                return;
            }
            if (BotMenus.natural() && bot.getRunning() && talksNaturally(bot)) {
                talkNaturally(bot, message.getSender(), message.getContent()); // ticket 37: a reply, not a menu
                return;
            }
            if (bot.getChr() != null) {
                BotConversation.engaged(bot.getChr(), message.getSender()); // 1.5.1: named, it stays to answer
            }

            if (!bot.getRunning()) {
                startNewBotSession(bot, message);
            } else {
                handleExistingBotSession(bot, message);
            }
        });
    }

    private void startNewBotSession(BotSM bot, ChatMessage message) {
        log("Bot not running. Start scheduledTask line");
        bot.setRunning(true);
        bot.getInteractors().setRespondant(message.getSender());
        bot.startScheduledTask();
        if (bot instanceof SocialBot socialBot) {
            socialBot.onFirstInteraction(message.getSender());
        }
    }

    private void handleExistingBotSession(BotSM bot, ChatMessage message) {
        if (bot instanceof SocialBot socialBot) {
            handleSocialBotSession(socialBot, message);
            return;
        }
        if (!bot.consumesFreeText()) {
            // bot-persistence ticket 21: a bot that does not read typed lines itself (a merchant, a gacha host, a
            // town wanderer) talks back through the brain. Its name alone shows its own hint (a merchant's) or
            // the shared menu window (a wanderer's), and opens the chat so the player's next lines reach it.
            String said = message.getContent().replace(bot.getChr().getName(), "").trim();
            if (said.isEmpty()) {
                if (showsOwnHint(bot)) {
                    bot.getDialogueHandler().listOptions(message.getSender(), bot);
                    BotConversation.open(bot, message.getSender());
                    return;
                }
                if (BotMenus.open(bot, message.getSender(), List.of(), (index, who) -> { })) {
                    BotConversation.open(bot, message.getSender());
                    return;
                }
            }
            if (BotConversation.say(bot, message.getSender(), said.isEmpty() ? message.getContent() : said, null)) {
                return;
            }
            if (asksAboutStock(bot, said)) {
                // Ticket 26: with no brain, a merchant still answers "what are you selling?" with its real stock.
                BotConversation.talk(bot, message.getSender(), said);
                return;
            }
        }
        log("bot already running");
        bot.getInteractors().setInquirer(message.getSender());
        bot.getDialogueHandler().listOptions(message.getSender(), bot);
        // One-line inquiry: a naming message that also carries a keyword ("Tiger wana party?") pre-selects the
        // menu option in the same breath. Strip the bot's name first so a name that itself contains a keyword
        // can't self-match, then re-enqueue the remainder onto the tertiary queue exactly as a stand-alone
        // follow-up would have arrived — BotOptionMenu.poll drains it on the bot's next tick with the same
        // trim/lowercase/contains matcher. The menu is already active (listOptions ran synchronously above),
        // and a non-matching remainder ("yo Tiger") falls through harmlessly like a junk follow-up would.
        String remainder = message.getContent().replace(bot.getChr().getName(), "").trim();
        if (!remainder.isEmpty()) {
            messageQueue.addMessage("tertiary", new ChatMessage(message.getSender(), remainder));
            bot.nudgeSoon(0L); // player is on the bot's map -> pull the pre-selected option forward so it feels instant
        }
    }

    private void handleSocialBotSession(SocialBot socialBot, ChatMessage message) {
        if (socialBot.hasActiveRespondant()) {
            log("[Dispatcher] SocialBot busy, ignoring second player");
            return;
        }
        log("[Dispatcher] SocialBot available, setting respondant");
        socialBot.getInteractors().setRespondant(message.getSender());
        socialBot.onFirstInteraction(message.getSender());
    }

    private void logBotNotFound(int botId) {
        log("No bot found for ID: " + botId);
    }

//    private void handleBotRunning(int[] botToCall, ChatMessage message) {
//        executor.execute(() -> {
//            BotSM bot = getBotById(botToCall[0]);
//            if (bot != null && !bot.getRunning()) {
//                log("Bot not running. Start scheduledTask line");
//                bot.setRunning(true);
//                bot.setRespondant(message.getSender());
//                bot.startScheduledTask();
//            } else if (bot != null & bot.getRunning()) {
//                log("bot already running");
//                bot.setInquirer(message.getSender());
//                bot.listOptions(message.getSender());
//            } else {
//                log("No bot found for ID: " + botToCall[0] + " or bot is already running.");
//            }
//        });
//    }

    private void handleMessageWithNoBotName(ChatMessage message) {
        // message does not contain any info w/ bot names.
        Character respondant = message.getSender();
        if (CharacterStorage.checkIfRespondant(respondant)) { // Check if message contains a respondant
            expirePlayerChatCommands(respondant); // Expires any bubbles for respondants, catch all.
            messageQueue.addMessage(message); // Put into 2nd queue
        } else if (CharacterStorage.checkIfInquirer(respondant)) {
            messageQueue.addMessage("tertiary", message);
        }
    }

    public void shutdown() {
        executor.shutdown();
        try {
            // Wait for existing tasks to complete
            if (!executor.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

            /*
            Message:
            - Has Bot Name -> start that bot

            - Doesn't have bot name
                -> contains a respondant -> 2ndary queue
                -> doesn't contain a respondant -> random chat, remove

            2ndary queue:
                - active bots pull from this.
             */