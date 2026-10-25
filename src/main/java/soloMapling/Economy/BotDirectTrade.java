package soloMapling.Economy;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.Trade;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSellerCaps;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeCommands;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotTradeQueue;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.DropGameBot;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.ScrollingBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotWealth;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 1.5.0 rc3, owner decision 3: direct trades with bots in the trade window.
 * <ul>
 *   <li><b>Selling to a bot:</b> invite a grinding/social/wandering bot to trade and put an item in; it offers a price
 *   inside its band ({@link TradeSession}). A rival or nemesis refuses (affinity's own
 *   {@link AffinityReactions#refusesTrade}).</li>
 *   <li><b>Buying from a bot:</b> ask it in chat for something it carries ("got any claw scrolls?", "how much for the
 *   work gloves"); it names a price ({@link #chat}) and remembers the quote for a few minutes. Open a trade and it
 *   puts that item in the window.</li>
 *   <li><b>Haggling</b> in chat or in the window goes through {@link HaggleBrain} (rules, then the Decide model) and
 *   the chat brain words the answer; the price is always {@link PriceBand}'s, in code.</li>
 *   <li><b>Money:</b> a bot pays from its own mesos (keeping {@code directTrade.reserveShareOfCeiling} (0.25) of its
 *   wealth ceiling), inside ticket 56A's per-seller cap and the world's daily budget for bots buying from players.
 *   What it buys goes to the resale pool ({@link BotShops}).</li>
 * </ul>
 * Merchant-type bots (selling, buying, NX, scrolling, drop-game) keep their own trade flows.
 */
public final class BotDirectTrade {

    private static final Logger log = LoggerFactory.getLogger(BotDirectTrade.class);

    static final long POLL_MS = 700;
    static final long IDLE_MS = 120_000;
    static final long QUOTE_MS = TimeUnit.MINUTES.toMillis(5);

    /** A price a bot named in chat for an item it carries; {@code rounds}: the player's counters to it so far. */
    record Quote(Item item, long price, long expiresAt, int rounds) {
        Quote(Item item, long price, long expiresAt) {
            this(item, price, expiresAt, 0);
        }
    }

    static final class Live {
        final Character bot;
        final Character player;
        final TradeSession session;
        final Item selling;          // the item the bot took out of its bag for the window (selling mode)
        volatile boolean success;
        volatile List<Item> lastSeen = List.of();
        volatile long lastActivity = System.currentTimeMillis();
        int logged;

        Live(Character bot, Character player, TradeSession session, Item selling) {
            this.bot = bot;
            this.player = player;
            this.session = session;
            this.selling = selling;
        }
    }

    private static final Map<Integer, Live> live = new ConcurrentHashMap<>();       // by bot id
    private static final Map<Long, Quote> quotes = new ConcurrentHashMap<>();       // (bot, player)

    /** Expired quotes are never honoured; drop them once the map grows (rc6 bug hunt: it grew forever). */
    private static void pruneQuotes() {
        long now = System.currentTimeMillis();
        soloMapling.server.BoundedMaps.prune(quotes, 500, 2_000, q -> q.expiresAt() <= now);
    }

    private BotDirectTrade() {
    }

    public static boolean enabled() {
        return EconomyConfig.getBool("directTrade.enabled", true);
    }

    /**
     * True for bots that do not trade in person: the merchant types run their own trade flow, and service bots (FM
     * shoppers, hosts, games) stay on their job. Grinding, social, wandering and following bots trade, and so does an
     * idle staged bot (no state machine: a GM's or QA's).
     */
    public static boolean handlesOwnTrades(BotSM sm) {
        if (sm == null) {
            return false;
        }
        if (sm instanceof SellingMerchantBot || sm instanceof BuyingMerchantBot || sm instanceof NXMerchantBot
                || sm instanceof ScrollingBot || sm instanceof DropGameBot) {
            return true;
        }
        return !(sm instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot
                || sm instanceof soloMapling.ArtificialPlayer.BotTypes.SocialBot
                || sm instanceof soloMapling.ArtificialPlayer.BotTypes.TownWandererBot
                || sm instanceof soloMapling.ArtificialPlayer.BotTypes.FollowerBot);
    }

    /**
     * rc6 say-do: whether {@code bot} takes a trade {@code from} opens now ({@code from} null: anyone) - what its
     * "trade me" / "just open a trade with me" promises. A merchant type when it is set up to trade (its own flow); a
     * bot that trades in person (grinding, social, wandering, following, or a staged one) unless direct trades are off,
     * it is in one already or it refuses this player (a rival); a service bot never.
     */
    public static boolean takesTradeFrom(Character bot, Character from) {
        if (bot == null) {
            return false;
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        if (sm instanceof SellingMerchantBot || sm instanceof BuyingMerchantBot || sm instanceof NXMerchantBot
                || sm instanceof ScrollingBot || sm instanceof DropGameBot) {
            return sm.takesTradesNow();
        }
        if (handlesOwnTrades(sm) || !enabled() || live.containsKey(bot.getId())) {
            return false;
        }
        return from == null || BotHelpers.isBot(from) || !AffinityReactions.refusesTrade(bot, from);
    }

    private static long key(int botId, int playerId) {
        return ((long) botId << 32) ^ (playerId & 0xffffffffL);
    }

    // ---- the invite ----

    /** Trade.inviteTrade: a player invited a bot. Never throws; does nothing for merchant types. */
    public static void onInvite(Character bot, Character player) {
        try {
            if (!enabled() || bot == null || player == null || BotHelpers.isBot(player)) {
                return;
            }
            BotSM sm = CharacterStorage.getBotById(bot.getId());
            if (handlesOwnTrades(sm)) {
                return;
            }
            BotTiming.afterRandom(900, 1800, () -> answerInvite(bot, player));
        } catch (RuntimeException e) {
            log.debug("direct trade invite", e);
        }
    }

    static void answerInvite(Character bot, Character player) {
        Trade t = bot.getTrade();
        if (t == null || t.getPartner() == null || t.getPartner().getChr() != player) {
            return;   // the player cancelled the invite
        }
        BotTradeQueue.getInstance().removeTradeRequest(bot);
        PendingGrants.deliver(bot);
        if (AffinityReactions.refusesTrade(bot, player)) {
            qa(bot, "REFUSE " + bot.getName() + " will not trade with " + player.getName() + " (rival)");
            BotTradeCommands.declineTradeInvite(bot);
            return;
        }
        if (live.containsKey(bot.getId())) {
            BotTradeCommands.declineTradeInvite(bot);
            return;
        }
        BotTradeCommands.acceptTradeInvite(bot);
        if (bot.getTrade() == null) {
            return;
        }
        Quote q = quotes.remove(key(bot.getId(), player.getId()));
        boolean selling = q != null && q.expiresAt() > System.currentTimeMillis();
        GameWindow window = new GameWindow(bot, player);
        TradeSession session = selling
                ? new TradeSession(TradeSession.Mode.BOT_SELLS, window, new GameWallet(bot, player), PriceBook::fairValue)
                : new TradeSession(TradeSession.Mode.BOT_BUYS, window, new GameWallet(bot, player),
                PriceBook::botBuyValue);
        Item selling2 = null;
        if (selling) {
            Item inBag = q.item();
            if (holds(bot, inBag) && BotHoldings.destroyHeldItem(bot, inBag)) {
                Item copy = inBag.copy();
                if (BotTradeCommands.addHeldItemToTrade(bot, copy, 1)) {
                    selling2 = copy;
                } else {
                    PendingGrants.giveBack(bot, inBag, "could not put it in the trade window");
                }
            }
            if (selling2 == null) {
                window.say("Say you do not have it any more.", "oh i dont have that anymore sry", null);
                session = new TradeSession(TradeSession.Mode.BOT_BUYS, window, new GameWallet(bot, player),
                        PriceBook::botBuyValue);
            }
        }
        Live l = new Live(bot, player, session, selling2);
        window.live = l;
        live.put(bot.getId(), l);
        t = bot.getTrade();
        if (t != null) {
            t.setTradeResultCallback(result -> {
                if (result == Trade.TradeResult.SUCCESSFUL) {
                    l.success = true;
                }
            });
        }
        try {
            GCMovement.holdForCommitment(bot);
        } catch (RuntimeException | LinkageError ignored) {
            // no movement driver here (a test or an idle bot)
        }
        if (selling2 != null) {
            session.startSelling(selling2, q.price());
        } else {
            window.say("Ask what they want to sell; they put it in the window.", "whatcha got?", null);
        }
        qa(bot, "OPEN " + bot.getName() + " <-> " + player.getName() + " (" + session.mode() + ")");
        BotTiming.after(POLL_MS, () -> poll(l));
    }

    /** True while {@code item} is still in the bot's bag (the very object, not a copy). */
    public static boolean carries(Character bot, Item item) {
        return bot != null && item != null && holds(bot, item);
    }

    /**
     * 1.6.0 ticket 86: the bot named {@code price} for {@code item} in a whisper (it answered a megaphone buy request),
     * so a player who whispers back to haggle, or opens a trade within {@link #QUOTE_MS}, gets the same quote flow as
     * if they had asked in chat. The price must already be inside the bot's sell band; nothing here changes it.
     */
    public static void rememberQuote(Character bot, Character player, Item item, long price, int rounds) {
        if (bot == null || player == null || item == null || price <= 0) {
            return;
        }
        quotes.put(key(bot.getId(), player.getId()), new Quote(item, price, System.currentTimeMillis() + QUOTE_MS,
                Math.max(0, rounds)));
        pruneQuotes();
    }

    private static boolean holds(Character bot, Item item) {
        try {
            for (Item i : bot.getInventory(item.getInventoryType()).list()) {
                if (i == item) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // fall through
        }
        return false;
    }

    static void poll(Live l) {
        Trade t = l.bot.getTrade();
        boolean open = t != null && t.getPartner() != null && t.getPartner().getChr() == l.player;
        if (!open) {
            finish(l);
            return;
        }
        try {
            synchronized (l) {
                l.session.tick();
            }
        } catch (RuntimeException e) {
            log.warn("direct trade tick for {}", l.bot.getName(), e);
        }
        flushLog(l);
        if (System.currentTimeMillis() - l.lastActivity > IDLE_MS && l.session.state() == TradeSession.State.OPEN) {
            BotTradeCommands.writeTradeChat(l.bot, "gtg, closing");
            BotTradeCommands.declineTradeInvite(l.bot);
        }
        BotTiming.after(POLL_MS, () -> poll(l));
    }

    private static void finish(Live l) {
        if (!live.remove(l.bot.getId(), l)) {
            return;
        }
        try {
            GCMovement.releaseCommitmentHold(l.bot);
        } catch (RuntimeException | LinkageError ignored) {
            // no movement driver
        }
        if (l.success) {
            onSuccess(l);
        } else {
            l.session.closedUnsuccessful();
            if (l.selling != null) {
                // it never left the bot after all; a full bag keeps it waiting for the bot, never loses it
                PendingGrants.giveBack(l.bot, l.selling, "trade closed without a sale");
            }
            qa(l.bot, "CLOSED without a trade: " + l.bot.getName() + " <-> " + l.player.getName());
        }
        flushLog(l);
    }

    private static void onSuccess(Live l) {
        TradeSession s = l.session;
        PriceBook.Party botSide = new PriceBook.Party(-l.bot.getId(), null, null);
        PriceBook.Party playerSide = PriceBook.Party.of(l.player);
        if (s.mode() == TradeSession.Mode.BOT_SELLS && l.selling != null) {
            PriceBook.recordBetween(l.selling, s.price(), PriceBook.units(l.selling), PriceBook.Source.BOT_TRADE,
                    botSide, playerSide);
            qa(l.bot, String.format("SOLD %s sold %s to %s for %,d", l.bot.getName(), BotShops.nameOf(
                    l.selling.getItemId()), l.player.getName(), s.price()));
            return;
        }
        // the bot bought: the window's items are in its bag now; price them out and pass them on for resale
        List<Item> bought = l.lastSeen;
        long paid = s.price();
        long totalFair = 0;
        for (Item it : bought) {
            totalFair += PriceBook.stackValue(it);
        }
        for (Item it : bought) {
            long share = totalFair <= 0 ? paid : Math.round(paid * (PriceBook.stackValue(it) / (double) totalFair));
            PriceBook.recordBetween(it, share, PriceBook.units(it), PriceBook.Source.BOT_TRADE, playerSide, botSide);
            BotBuying.toResale(l.bot, it, share);
        }
        qa(l.bot, String.format("BOUGHT %s paid %s %,d for %d item(s)", l.bot.getName(), l.player.getName(), paid,
                bought.size()));
    }

    // ---- chat in the window ----

    /** Trade.chat: a line typed in a window. When the other side is a bot in a direct trade, it hears it. */
    public static void onWindowChat(Character speaker, Trade speakerTrade, String message) {
        if (speaker == null || speakerTrade == null || speakerTrade.getPartner() == null || BotHelpers.isBot(speaker)) {
            return;
        }
        Character bot = speakerTrade.getPartner().getChr();
        Live l = bot == null ? null : live.get(bot.getId());
        if (l == null || l.player != speaker) {
            return;
        }
        l.lastActivity = System.currentTimeMillis();
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            HaggleBrain.Read read = HaggleBrain.read(message, ChatDecide.get());
            qa(bot, "CHAT " + speaker.getName() + ": \"" + message + "\" -> " + read.move()
                    + (read.price() > 0 ? " " + read.price() : "") + " (" + read.source() + ")");
            synchronized (l) {
                l.session.onChat(read);
            }
            flushLog(l);
        });
    }

    // ---- chat outside the window: asking a bot what it sells ----

    /** What a bot answers to a trade line in chat: the fact for the chat brain, the scripted line, the must-say. */
    public record Answer(boolean yes, String fact, String scripted, String mustMention) {
    }

    /** Null when direct trades are off or this bot does not trade in person (the caller's old answer stands). */
    public static Answer chat(BotSM sm, Character bot, Character player, String line) {
        if (!enabled() || bot == null || player == null || handlesOwnTrades(sm)) {
            return null;
        }
        if (soloMapling.ArtificialPlayer.PlayerBusy.isBusy(player)) {
            return new Answer(false, "The player is busy; do not offer a trade now.", "maybe later", null);
        }
        if (BotAffinity.hostile(bot.getId(), player.getId())) {
            return new Answer(false, "Say no, you do not trade with them.", "nah not trading with u", null);
        }
        String text = line == null ? "" : line.toLowerCase(Locale.ROOT);
        long k = key(bot.getId(), player.getId());
        Quote q = quotes.get(k);
        HaggleBrain.Read read = HaggleBrain.rules(text);
        boolean friend = BotAffinity.friend(bot.getId(), player.getId());
        // haggling over a quote it already gave
        if (q != null && q.expiresAt() > System.currentTimeMillis() && read.move() == HaggleBrain.Move.OFFER) {
            Haggled next = haggleQuote(q, read.price(), friend, System.currentTimeMillis());
            PriceBand.Decision d = next.decision();
            quotes.put(k, next.quote());
            pruneQuotes();
            String p = PriceBook.shorthand(d.price());
            qa(bot, "QUOTE haggle " + read.price() + " -> " + d.kind() + " " + d.price());
            if (d.kind() == PriceBand.Kind.FINAL) {
                return new Answer(true, "Say " + p + " is your last price for the " + BotShops.nameOf(
                        q.item().getItemId()) + "; they can open a trade with you if they want it.",
                        p + " is my last price, trade me if u want it", p);
            }
            return new Answer(true, (d.kind() == PriceBand.Kind.ACCEPT ? "Agree to " : "Say you would do ") + p
                    + " for the " + BotShops.nameOf(q.item().getItemId()) + "; they should open a trade with you.",
                    d.kind() == PriceBand.Kind.ACCEPT ? "ok " + p + ", trade me" : "can do " + p + ", trade me", p);
        }
        List<Item> mine = BotShops.sellable(bot, new ArrayList<>());
        Item wanted = match(mine, text);
        if (wanted != null) {
            long f = PriceBook.stackValue(wanted);
            PriceBand.Band band = PriceBand.of(PriceBand.Side.BOT_SELLS, f, friend);
            long price = PriceBand.opening(band);
            quotes.put(k, new Quote(wanted, price, System.currentTimeMillis() + QUOTE_MS));
            pruneQuotes();
            String name = BotShops.nameOf(wanted.getItemId()).toLowerCase(Locale.ROOT);
            String p = PriceBook.shorthand(price);
            qa(bot, "QUOTE " + bot.getName() + " offers " + player.getName() + " " + name + " at " + price + " (band "
                    + band.low() + ".." + band.high() + ")");
            return new Answer(true, "Say you have " + name + (wanted.getQuantity() > 1 ? " x" + wanted.getQuantity() : "")
                    + " and want " + p + " for it; they can open a trade with you.",
                    "i got " + name + ", " + p + " and its yours. trade me", p);
        }
        if (text.matches(".*\\b(wts|selling|sell (u|you|ya)|buy my|want to sell|wanna sell|s>)\\b.*")) {
            return new Answer(true, "Say they can open a trade and put the item in; you will make an offer.",
                    "trade me and show it, ill make an offer", null);
        }
        if (mine.isEmpty()) {
            return new Answer(false, "Say you have nothing to sell right now.", "not selling anything rn", null);
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < Math.min(3, mine.size()); i++) {
            Item it = mine.get(i);
            long f = PriceBook.stackValue(it);
            long price = PriceBand.opening(PriceBand.of(PriceBand.Side.BOT_SELLS, f, friend));
            list.append(i == 0 ? "" : ", ").append(BotShops.nameOf(it.getItemId()).toLowerCase(Locale.ROOT))
                    .append(" ").append(PriceBook.shorthand(price));
        }
        return new Answer(true, "Say what you could sell them: " + list + ". They can ask for one.",
                "i got " + list, null);
    }

    /** A haggle over a quote: the bot's answer and the quote it now stands at. */
    record Haggled(Quote quote, PriceBand.Decision decision) {
    }

    /**
     * The player bid {@code theirs} for a quoted item: the band answers with the real round (a bot names its last price
     * after {@code bands.maxRounds} counters; it used to be told round 0 every time and haggle forever).
     */
    static Haggled haggleQuote(Quote q, long theirs, boolean friend, long now) {
        PriceBand.Band band = PriceBand.of(PriceBand.Side.BOT_SELLS, PriceBook.stackValue(q.item()), friend);
        PriceBand.Decision d = PriceBand.respond(band, q.price(), theirs, q.rounds());
        return new Haggled(new Quote(q.item(), d.price(), now + QUOTE_MS, q.rounds() + 1), d);
    }

    /** The bag item a line names: its full name, or most of its name's longer words. */
    static Item match(List<Item> items, String text) {
        Item best = null;
        int bestScore = 0;
        for (Item it : items) {
            String name = BotShops.nameOf(it.getItemId()).toLowerCase(Locale.ROOT);
            int score = 0;
            if (text.contains(name)) {
                score = 100;
            } else {
                String[] words = name.replaceAll("[^a-z0-9% ]", " ").split("\\s+");
                int long_ = 0, hit = 0;
                for (String w : words) {
                    if (w.length() >= 4 || w.endsWith("%")) {
                        long_++;
                        if (text.contains(w)) {
                            hit++;
                        }
                    }
                }
                if (long_ > 0 && hit * 2 >= long_ && hit > 0) {
                    score = 10 * hit;
                }
            }
            if (score > bestScore) {
                best = it;
                bestScore = score;
            }
        }
        return best;
    }

    // ---- the real window and wallet ----

    static final class GameWindow implements TradeSession.Window {
        final Character bot;
        final Character player;
        volatile Live live;

        GameWindow(Character bot, Character player) {
            this.bot = bot;
            this.player = player;
        }

        @Override
        public List<Item> partnerItems() {
            List<Item> items = BotTradeCommands.getPartnersItems(bot);
            List<Item> out = items == null ? List.of() : items;
            if (live != null) {
                live.lastSeen = out;
            }
            return out;
        }

        @Override
        public int partnerMeso() {
            return BotTradeCommands.readPartnerMeso(bot);
        }

        @Override
        public boolean partnerLocked() {
            return BotTradeCommands.isPartnerLocked(bot);
        }

        @Override
        public int postedMeso() {
            return BotTradeCommands.readMeso(bot);
        }

        @Override
        public boolean postMore(int delta) {
            int before = postedMeso();
            BotTradeCommands.setMeso(bot, delta);   // Trade.setMeso adds to what is posted, debiting the wallet
            return postedMeso() == before + delta;
        }

        @Override
        public void say(String fact, String scripted, String mustMention) {
            BotDirectTrade.say(bot, player, fact, scripted, mustMention, true);
        }

        @Override
        public void confirm() {
            BotTiming.afterRandom(600, 1200, () -> BotTradeCommands.confirmTrade(bot));
        }

        @Override
        public void cancel() {
            BotTiming.after(1500, () -> BotTradeCommands.declineTradeInvite(bot));
        }
    }

    static final class GameWallet implements TradeSession.Wallet {
        final Character bot;
        final Character player;
        final int account;
        long sellerReserved;

        GameWallet(Character bot, Character player) {
            this.bot = bot;
            this.player = player;
            this.account = player.getAccountID();
        }

        @Override
        public long spendable() {
            double keep = EconomyConfig.getDouble("directTrade.reserveShareOfCeiling", 0.25);
            long reserve = (long) (BotWealth.ceiling(bot) * Math.max(0, keep));
            return Math.max(0, (long) bot.getMeso() + BotTradeCommands.readMeso(bot) - reserve);
        }

        @Override
        public boolean reserve(long more) {
            if (!BotSellerCaps.tryReserve(account, player.getName(), more)) {
                return false;
            }
            if (!EconomyMeters.tryBotBuy(more)) {
                BotSellerCaps.release(account, more);
                return false;
            }
            sellerReserved += more;
            return true;
        }

        @Override
        public void release(long amount) {
            BotSellerCaps.release(account, amount);
            EconomyMeters.releaseBotBuy(amount);
            sellerReserved = Math.max(0, sellerReserved - amount);
        }

        @Override
        public boolean friend() {
            return BotAffinity.friend(bot.getId(), player.getId());
        }
    }

    // ---- saying things: the chat brain words it, the scripted line stands in ----

    static void say(Character bot, Character player, String fact, String scripted, String mustMention, boolean window) {
        Runnable fallback = () -> deliver(bot, scripted, window);
        BotBrain brain;
        try {
            brain = BotBrains.get();
        } catch (RuntimeException | LinkageError e) {
            fallback.run();
            return;
        }
        if (brain == null || !brain.isLive() || fact == null) {
            fallback.run();
            return;
        }
        String system = "You are " + bot.getName() + ", a MapleStory v83 player, trading with " + player.getName()
                + ". Reply with one short casual chat line (lowercase is fine, no quotes, no emojis). " + fact
                + (mustMention != null ? " Write the price exactly as " + mustMention + "." : "")
                + " Never name any other price.";
        // ticket 59: the player is haggling with the bot right now - a player's reply (the cloud may overflow it)
        brain.reply(soloMapling.ArtificialPlayer.BotBrainSystem.Priority.PLAYER, player.getId(),
                        List.of(BotBrain.Turn.system(system), BotBrain.Turn.user("(your turn)")))
                .thenAccept(reply -> {
                    String r = reply.map(soloMapling.ArtificialPlayer.BotConvo.ConvoContract.Reply::text).orElse(null);
                    boolean fits = r != null && !r.isBlank() && (mustMention == null
                            || r.toLowerCase(Locale.ROOT).contains(mustMention.toLowerCase(Locale.ROOT)))
                            && (mustMention != null || HaggleBrain.price(r) < 0);
                    deliver(bot, fits ? r : scripted, window);
                });
    }

    private static void deliver(Character bot, String line, boolean window) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (window && bot.getTrade() != null) {
            BotTradeCommands.writeTradeChat(bot, line);
        } else {
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot, line);
        }
    }

    // ---- logs ----

    private static void flushLog(Live l) {
        List<String> lines = l.session.log();
        synchronized (lines) {
            while (l.logged < lines.size()) {
                String line = lines.get(l.logged++);
                qa(l.bot, "TRADE " + l.bot.getName() + "/" + l.player.getName() + ": " + line);
                log.debug("[DirectTrade] {}/{}: {}", l.bot.getName(), l.player.getName(), line);
            }
        }
    }

    private static void qa(Character bot, String line) {
        if (QaLog.on(QaLog.Topic.TRADE)) {
            QaLog.any(QaLog.Topic.TRADE, line);
        }
    }

    /** "Bob <-> Alice (BOT_BUYS, price 1.2m)" for each open direct trade, for !economy. */
    public static List<String> status() {
        List<String> out = new ArrayList<>();
        for (Live l : live.values()) {
            out.add(l.bot.getName() + " <-> " + l.player.getName() + " (" + l.session.mode() + ", price "
                    + PriceBook.shorthand(l.session.price()) + ")");
        }
        return out;
    }

    /** GM staging: makes a bot quote an item to a player as if asked in chat. */
    public static Answer stageQuote(Character bot, Character player, String itemWords) {
        return chat(CharacterStorage.getBotById(bot.getId()), bot, player, itemWords);
    }
}
