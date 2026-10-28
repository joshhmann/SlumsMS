package soloMapling.Economy.exchange.volatility;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeService;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.OrderBook;
import soloMapling.Economy.exchange.Ticker;
import soloMapling.Economy.exchange.TickerHistory;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.1 volatility in the running game: one minimum-priority daemon thread ({@code market-volatility}, every 30 s)
 * that rolls the random market events, runs the trader bots' turns, pays waiting exchange payouts into bots that are
 * online (always, even with traders off, so a bot's escrow never stays stuck), and now and then lets a bot say a
 * market rumour. Starts when any of {@code -Dslums.exchange.traders}, {@code -Dslums.market.events},
 * {@code -Dslums.market.rumours} is on; traders and rumours about the book also need {@code -Dslums.exchange=true}.
 * Nothing here throws into the game.
 */
public final class MarketVolatility {

    private static final Logger log = LoggerFactory.getLogger(MarketVolatility.class);
    static final long TICK_MS = 30_000;
    /** How often the traders are re-picked from the bots in the game (they log in and out on their play windows). */
    private static final long ROSTER_REFRESH_MS = 600_000L;

    private static volatile MarketVolatility running;

    private final MarketConfig config;
    private final MarketEvents events;
    private final MarketTraders traders;
    private final MarketRumours rumours;
    private final MarketNews news;
    private final FairValue fair;
    private final Random rng = new Random();
    private volatile List<MarketTraders.Trader> roster = List.of();
    private long rosterAt;
    private ScheduledExecutorService executor;
    private volatile MarketRumours.Rumour lastRumour;
    private long lastWarn;

    private MarketVolatility(MarketConfig config) {
        this.config = config;
        this.events = MarketEvents.get();
        this.traders = new MarketTraders(new Random());
        this.rumours = new MarketRumours(MarketRumours.yaml());
        this.news = MarketNews.get();
        this.fair = FairValue.fromSystem();
        FairValue.setCurrent(fair);
        this.traders.use(fair, news);
    }

    public MarketNews news() {
        return news;
    }

    public FairValue fair() {
        return fair;
    }

    /** The running instance, or null when every part is off. */
    public static MarketVolatility get() {
        return running;
    }

    public static synchronized void startIfEnabled() {
        if (running != null) {
            return;
        }
        MarketConfig c = MarketConfig.fromSystem();
        if (!c.traders() && !c.events() && !c.rumours()) {
            return;
        }
        try {
            MarketVolatility m = new MarketVolatility(c);
            ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "market-volatility");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            s.scheduleWithFixedDelay(m::tickSafely, 60, TICK_MS / 1000, TimeUnit.SECONDS);
            m.executor = s;
            running = m;
            log.info("Market volatility on: traders {} ({}), events {} ({}/week), rumours {}", c.traders(),
                    c.traderCount(), c.events(), c.eventsPerWeek(), c.rumours());
        } catch (Exception e) {
            log.warn("Market volatility did not start; the game is unaffected", e);
        }
    }

    public MarketConfig config() {
        return config;
    }

    public MarketEvents events() {
        return events;
    }

    public MarketTraders traders() {
        return traders;
    }

    public MarketRumours rumours() {
        return rumours;
    }

    public MarketRumours.Rumour lastRumour() {
        return lastRumour;
    }

    void tickSafely() {
        try {
            tick(System.currentTimeMillis());
        } catch (Exception e) {
            long now = System.currentTimeMillis();
            if (now - lastWarn > 15 * 60_000L) {
                lastWarn = now;
                log.warn("Market volatility tick failed", e);
            }
        }
    }

    void tick(long now) {
        if (soloMapling.server.BotShutdown.isQuiesced()) {
            return;
        }
        events.maybeRandom(now, TICK_MS, rng);
        Exchange ex = ExchangeService.exchange();
        if (ex == null) {
            return;
        }
        collectBotPayouts(ex);
        fair.maybeUpdate(now, MarketFlows.get(), FairValue.game(events));
        if (config.traders()) {
            traders.tick(ex, now, gameRoster(now), config, events, rumours);
        }
        if (config.rumours()) {
            maybeRumour(ex, now, false, null);
        }
    }

    /** Pays the exchange's waiting payouts into trader bots that are in the game. */
    private static void collectBotPayouts(Exchange ex) {
        for (long id : ex.botsWithPayout()) {
            BotTraderWallet w = walletOfBot(id);
            if (w != null) {
                ex.collectBot(w);
            }
        }
    }

    static BotTraderWallet walletOfBot(long id) {
        if (id > Integer.MAX_VALUE) {
            return null;
        }
        BotSM sm = CharacterStorage.getBotById((int) id);
        return sm == null ? null : BotTraderWallet.of(sm.getChr());
    }

    // ---------------------------------------------------------------- off-screen leaf finds

    /** The bots' abstract hunting pace (TrainingBot's KILLS_PER_MIN where no mob data gives a rate). */
    static final double KILLS_PER_MIN = 30.0;

    /**
     * Called from a bot's off-screen loot accrual: a trader bot (traders on) also finds Maple Leaves at the leaf's
     * own global drop chance on its map's continent (the table players roll; the bots' off-screen loot otherwise
     * leaves global drops out), under the running Rush or Blight. Granted through the bot's yield caps and holdings
     * like any other off-screen find; global rates are not touched. Never throws.
     */
    public static void offscreenLeafFinds(Character bot, long elapsedMs) {
        try {
            MarketVolatility m = running;
            if (m == null || !m.config.traders() || bot == null || !MarketTraders.isTrader(bot.getId())) {
                return;
            }
            server.life.MonsterGlobalDropEntry leaf = null;
            for (server.life.MonsterGlobalDropEntry de : server.life.MonsterInformationProvider.getInstance()
                    .getRelevantGlobalDrops(bot.getMapId())) {
                if (de.itemId == MarketEvents.MAPLE_LEAF && de.chance > 0) {
                    leaf = de;
                    break;
                }
            }
            if (leaf == null) {
                return;
            }
            double avg = Math.min(4, (Math.max(1, leaf.Minimum) + Math.max(1, leaf.Maximum)) / 2.0);
            int n = MarketTraders.offscreenLeaves(bot.getId(), elapsedMs, KILLS_PER_MIN,
                    MarketEvents.globalDropChance(MarketEvents.MAPLE_LEAF, leaf.chance), avg);
            if (n <= 0) {
                return;
            }
            client.inventory.Item item = new client.inventory.Item(MarketEvents.MAPLE_LEAF, (short) 0,
                    (short) Math.min(n, 100));
            if (soloMapling.ArtificialPlayer.BotYieldCaps.takeItem(bot, item)) {
                if (soloMapling.ArtificialPlayer.BotHoldings.grantItem(bot, item)) {
                    MarketFlows.supplied(MarketEvents.MAPLE_LEAF, item.getQuantity());
                } else {
                    soloMapling.ArtificialPlayer.BotYieldCaps.returnItem(bot, item);
                }
            }
        } catch (RuntimeException e) {
            // a lost find, never a broken hunt
        }
    }

    // ---------------------------------------------------------------- the game's roster of traders

    /** The traders (re-picked every 10 minutes from the roster bots in the game) with their in-game wallets. */
    public synchronized MarketTraders.Roster gameRoster(long now) {
        if (now - rosterAt > ROSTER_REFRESH_MS || roster.isEmpty()) {
            rosterAt = now;
            List<MarketTraders.Candidate> cands = new ArrayList<>();
            try {
                for (BotRoster.Member m : BotRoster.members()) {
                    // only bots in the game can trade: picking from the whole roster left ~1 of 16 online on TEST
                    if (m.status() == BotRoster.Status.ACTIVE && CharacterStorage.botLoggedIn(m.charId())) {
                        cands.add(new MarketTraders.Candidate(m.charId(),
                                SocialProfile.forCharacter(m.charId()).leading().name()));
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Market traders: could not read the roster", e);
            }
            roster = MarketTraders.keepAndFill(roster, cands, config.traderCount());
            MarketTraders.setTraderIds(roster.stream().map(MarketTraders.Trader::id).toList());
        }
        List<MarketTraders.Trader> list = roster;
        return new MarketTraders.Roster() {
            public List<MarketTraders.Trader> traders() {
                return list;
            }

            public LiveWallet walletOf(MarketTraders.Trader t) {
                BotTraderWallet w = walletOfBot(t.id());
                return w != null && w.usable() ? w : null;
            }

            public int guildOf(MarketTraders.Trader t) {
                BotTraderWallet w = walletOfBot(t.id());
                return w == null ? 0 : w.character0().getGuildId();
            }
        };
    }

    // ---------------------------------------------------------------- rumours

    /**
     * Maybe says one rumour (when the gap allows and the dice say so; {@code force}: now, ignoring both). The speaker
     * is a bot on a map with a real player (a trader if one is there), in map chat, or a megaphone when the shared
     * bot megaphone gate is open. Returns what was said, or null.
     */
    public MarketRumours.Rumour maybeRumour(Exchange ex, long now, boolean force, String ticker) {
        long gap = force ? 0 : config.rumourGapMs();
        if (!rumours.due(now, gap)) {
            return null;
        }
        if (!force && rng.nextDouble() >= (double) TICK_MS / Math.max(TICK_MS, config.rumourGapMs() / 2)) {
            return null;
        }
        List<Ticker> items = new ArrayList<>();
        for (Ticker t : ex.tickers()) {
            if (t.item()) {
                items.add(t);
            }
        }
        if (items.isEmpty()) {
            return null;
        }
        Ticker tk = ticker == null ? null : ex.ticker(ticker);
        if ((tk == null || !tk.item()) && rng.nextInt(100) < 70) { // fresh guild news first
            List<Ticker> withNews = new ArrayList<>();
            for (Ticker t : items) {
                if (!facts(ex, t, now, events, news).news().isEmpty()) {
                    withNews.add(t);
                }
            }
            tk = withNews.isEmpty() ? null : withNews.get(rng.nextInt(withNews.size()));
        }
        if (tk == null || !tk.item()) {
            tk = rng.nextInt(100) < 70 && ex.ticker("LEAF") != null && ex.open("LEAF") ? ex.ticker("LEAF")
                    : items.get(rng.nextInt(items.size()));
        }
        MarketRumours.Rumour r = rumours.make(facts(ex, tk, now, events, news), now, gap, config.fakePct(), rng);
        if (r == null || !soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.ok(r.line())) {
            return null;
        }
        Character speaker = speaker();
        if (speaker == null) {
            return null;
        }
        boolean mega = rng.nextInt(100) < config.megaphonePct()
                && soloMapling.ArtificialPlayer.BotCommandsPack.BotMegaphoneGate.tryAcquire();
        if (mega) {
            soloMapling.ArtificialPlayer.BotCommandsPack.MegaphoneCommands.BotSuperMegaphone(speaker, r.line());
        } else {
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotAmbientSpeak(speaker, r.line());
        }
        rumours.said(r);
        lastRumour = r;
        log.info("Market rumour ({}{}, {}): {} says \"{}\"", r.claim(), r.fake() ? ", made up" : "", r.ticker(),
                speaker.getName(), r.line());
        return r;
    }

    static MarketRumours.Facts facts(Exchange ex, Ticker tk, long now, MarketEvents events) {
        return facts(ex, tk, now, events, null);
    }

    /**
     * What is really going on with a ticker: the 24 h move, whale-sized orders on the book, every running event, and
     * the guild news about it (ELIXIR: wars, guilds gearing up, wipes and war losers; LEAF: war winners and losers;
     * CHAOS and WS: clears).
     */
    static MarketRumours.Facts facts(Exchange ex, Ticker tk, long now, MarketEvents events, MarketNews news) {
        TickerHistory.Stats s = ex.stats(tk.id());
        double move = s.change() == null || s.reference() == null || s.reference() == 0 ? 0
                : (double) s.change() / s.reference();
        long whale = Math.max(tk.id().equals("CHAOS") || tk.id().equals("WS") ? 20 : 200, s.qty() / 4);
        boolean bigBid = ex.depth(tk.id(), OrderBook.Side.BUY, 5).stream().anyMatch(l -> l.nx() >= whale);
        boolean bigAsk = ex.depth(tk.id(), OrderBook.Side.SELL, 5).stream().anyMatch(l -> l.nx() >= whale);
        List<MarketEvents.Kind> ks = new ArrayList<>();
        for (MarketEvents.Active a : events.active(now)) {
            if (a.kind().ticker.equals(tk.id())) {
                ks.add(a.kind());
            }
        }
        return new MarketRumours.Facts(tk.id(), move, bigBid, bigAsk, ks, newsFor(tk.id(), now, news));
    }

    static List<MarketRumours.News> newsFor(String ticker, long now, MarketNews news) {
        List<MarketRumours.News> out = new ArrayList<>();
        if (news == null) {
            return out;
        }
        if ("ELIXIR".equals(ticker)) {
            for (MarketNews.War w : news.wars(now)) {
                out.add(new MarketRumours.News(MarketRumours.Claim.WAR_STOCKUP,
                        java.util.Map.of("GUILD", w.aName(), "RIVAL", w.bName())));
            }
            for (MarketNews.Prep p : news.preps(now)) {
                out.add(new MarketRumours.News(MarketRumours.Claim.PREP,
                        java.util.Map.of("GUILD", p.guildName(), "BOSS", p.content())));
            }
        }
        for (MarketNews.Run r : news.recentRuns(now)) {
            if (r.cleared() && ("CHAOS".equals(ticker) || "WS".equals(ticker))) {
                out.add(new MarketRumours.News(MarketRumours.Claim.RUN_CLEAR,
                        java.util.Map.of("GUILD", r.guildName(), "BOSS", r.content())));
            } else if (!r.cleared() && "ELIXIR".equals(ticker)) {
                out.add(new MarketRumours.News(MarketRumours.Claim.RUN_WIPE,
                        java.util.Map.of("GUILD", r.guildName(), "BOSS", r.content())));
            }
        }
        for (MarketNews.End e : news.recentEnds(now)) {
            if (e.draw()) {
                continue;
            }
            if ("LEAF".equals(ticker)) {
                out.add(new MarketRumours.News(MarketRumours.Claim.WAR_WON, java.util.Map.of("GUILD", e.winnerName())));
            }
            if ("LEAF".equals(ticker) || "ELIXIR".equals(ticker)) {
                out.add(new MarketRumours.News(MarketRumours.Claim.WAR_LOST, java.util.Map.of("GUILD", e.loserName())));
            }
        }
        return out;
    }

    /** A bot standing on a map with a real player (a trader first), or null. */
    private Character speaker() {
        List<Character> any = new ArrayList<>();
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character c = sm.getChr();
            if (c == null || c.getMap() == null || !BotHelpers.isRosterBot(c)) {
                continue;
            }
            boolean listener = c.getMap().getAllPlayers().stream().anyMatch(o -> o instanceof Character p
                    && !BotHelpers.isBot(p));
            if (!listener) {
                continue;
            }
            if (MarketTraders.isTrader(c.getId())) {
                return c;
            }
            any.add(c);
        }
        return any.isEmpty() ? null : any.get(rng.nextInt(any.size()));
    }
}
