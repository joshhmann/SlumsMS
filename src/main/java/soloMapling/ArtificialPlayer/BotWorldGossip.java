package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Ticket 85 (player-likeness gap 1/5): bots talk about what really happened on the server. Boss kills, rare drops
 * (scrolls, mastery books, a boss's gear) and weddings land in a short news feed. Two things come of it:
 * <ul>
 *   <li><b>Memory:</b> the bots that were on the map remember the real player's deed ({@link BotMemory}), so a later
 *   chat can bring it up and {@link BotGossip} carries it a hop or two further.</li>
 *   <li><b>Talk:</b> now and then a bot near a real player says it out loud ("did u see X killed zakum"). News
 *   fades: it counts for half as much every {@link #HALF_LIFE_MS} and is gone after {@link #TTL_MS}; one item is
 *   mentioned at most {@value #MAX_MENTIONS} times, and the world hears at most one mention every
 *   {@link #MENTION_GAP_MS} ms.</li>
 * </ul>
 *
 * <p><b>Knowledge boundaries (Stage 3, ticket 271).</b> The feed is global bookkeeping, but a bot only ever voices
 * news it <i>knows</i>: it was one of the {@value #WITNESSES} bots on the actor's map when it happened (witnessed),
 * or a knower told it ({@link #pass}, along a {@code BotGossip} step: at most {@link BotMemory#MAX_HOPS} mouths from a
 * witness, known to the listener only after a short delay, kept as a REPORTED note of lower confidence). A bot that
 * neither saw nor heard it says nothing about it, in the world line, in a bot thread's gossip or in an event's
 * aftermath.
 *
 * <p>Best effort: any gate failing just drops the line.
 */
public final class BotWorldGossip implements EventSubscriber {

    /** EVENT (1.6.0): a world event (the giant cake, an invasion); {@code who} is its winner or "". */
    public enum Kind { BOSS, DROP, WEDDING, EVENT }

    /** One thing that happened. {@code who} is the actor's name; {@code other} the monster, item or spouse. */
    public static final class News {
        public final Kind kind;
        public final String who;
        public final int whoId;
        public final String what;
        public final String extra;
        public final long at;
        final Set<Integer> witnesses = new HashSet<>();
        /** Bots that were told (not witnesses): bot character id to how it reached them. Bounded by {@link #TOLD_CAP}. */
        final Map<Integer, Told> told = new ConcurrentHashMap<>();
        int mentions;

        void mentionedForTest() {
            mentions++;
        }

        /** Whether this bot was on the map when it happened (1.6.0 S4: it says "did u see" rather than "heard"). */
        public boolean witnessedBy(int botCharId) {
            return witnesses.contains(botCharId);
        }

        /**
         * Whether this bot may voice the news at {@code now}: it witnessed it, or was told and the telling has arrived
         * ({@link Told#knownAt}). A bot that neither saw nor heard it does not know it.
         */
        public boolean knows(int botCharId, long now) {
            if (witnesses.contains(botCharId)) {
                return true;
            }
            Told t = told.get(botCharId);
            return t != null && t.knownAt() <= now;
        }

        /** Mouths between this bot and a witness: 0 for a witness, 1.. for a told bot, -1 when it does not know yet. */
        public int hopsOf(int botCharId, long now) {
            if (witnesses.contains(botCharId)) {
                return 0;
            }
            Told t = told.get(botCharId);
            return t != null && t.knownAt() <= now ? t.hops() : -1;
        }

        News(Kind kind, String who, int whoId, String what, String extra, long at) {
            this.kind = kind;
            this.who = who;
            this.whoId = whoId;
            this.what = what;
            this.extra = extra;
            this.at = at;
        }
    }

    /** How a told bot came by a piece of news: from whom (a character id), how many mouths deep, and when it arrives. */
    public record Told(int fromId, int hops, long knownAt) {
    }

    /** A telling reaches its listener this long after it is made (a bot hears it the next time they chat). */
    public static final long TOLD_DELAY_MIN_MS = TimeUnit.SECONDS.toMillis(20), TOLD_DELAY_MAX_MS = TimeUnit.SECONDS.toMillis(90);
    /** Bots told one piece of news at most (it has {@value #WITNESSES} witnesses to start from). */
    static final int TOLD_CAP = 12;

    public static final long TTL_MS = TimeUnit.HOURS.toMillis(8);
    public static final long HALF_LIFE_MS = TimeUnit.HOURS.toMillis(2);
    public static final int MAX_MENTIONS = 3;
    public static final long MENTION_GAP_MS = TimeUnit.MINUTES.toMillis(4);
    static final long STEP_MS = TimeUnit.MINUTES.toMillis(2);
    static final int FEED_CAP = 30;
    static final int WITNESSES = 3;
    /** Per step, times freshness: how likely the best-faded news is mentioned at all. */
    static final double MENTION_CHANCE = 0.4;

    private static final BotWorldGossip INSTANCE = new BotWorldGossip();
    private static final List<News> feed = new CopyOnWriteArrayList<>();
    private static volatile boolean registered;
    /** Where memory writes run (they read and write the database); a seam for tests. */
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static volatile long lastStepAt;
    private static volatile long lastMentionAt;

    private BotWorldGossip() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.worldGossip", "true"));
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        EventBus bus = EventBus.getInstance();
        bus.subscribe(EventType.BOSS_KILL, INSTANCE);
        bus.subscribe(EventType.RARE_DROP, INSTANCE);
        registered = true;
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return enabled();
    }

    @Override
    public void onEvent(GameEvent event) {
        try {
            Character who = event.getMapleCharacter();
            if (who == null || event.getType() == null) {
                return;
            }
            long now = SocialSim.now();
            switch (event.getType()) {
                case BOSS_KILL -> {
                    if (event.getMessage() != null && !event.getMessage().isBlank()) {
                        record(Kind.BOSS, who, event.getMessage(), null, now);
                    }
                }
                case RARE_DROP -> {
                    Item item = event.getItem();
                    String name = item == null ? null : ItemInformationProvider.getInstance().getName(item.getItemId());
                    if (name != null && !name.isBlank()) {
                        record(Kind.DROP, who, name, event.getMessage(), now);
                    }
                }
                default -> {
                }
            }
        } catch (RuntimeException e) {
            // world news is flavor: never let it reach the kill or the drop
        }
    }

    /** A wedding (a real couple's, or a bot couple's): both names go in the feed as one item. */
    public static void onWedding(Character a, Character b) {
        try {
            if (!enabled() || a == null || b == null) {
                return;
            }
            record(Kind.WEDDING, a, b.getName(), null, SocialSim.now());
            // the spouse is a real player too: the witnesses remember them as well
            News n = feed.isEmpty() ? null : feed.get(feed.size() - 1);
            if (n != null && !BotHelpers.isArtificial(b.getId())) {
                remember(n, b.getId(), "was at the wedding of " + b.getName() + " and " + a.getName());
            }
        } catch (RuntimeException e) {
            // flavor only
        }
    }

    /**
     * 1.6.0 world events: an event that just ended joins the news ("the giant cake in henesys"), with its real
     * winner if there was one and the bots that were there as witnesses (they say "remember the cake lol"; others
     * "did u hear about..."). {@code botDeaths} goes in as the extra ("6 bots died").
     */
    public static void onWorldEvent(String what, String winner, int winnerId, int botDeaths, List<Integer> witnesses,
                                    long now) {
        try {
            if (!enabled() || what == null || what.isBlank()) {
                return;
            }
            News news = new News(Kind.EVENT, winner == null ? "" : winner, winner == null ? 0 : winnerId, what,
                    botDeaths > 0 ? String.valueOf(botDeaths) : null, now);
            for (int id : witnesses) {
                if (news.witnesses.size() < WITNESSES) {
                    news.witnesses.add(id);
                }
            }
            feed.add(news);
            while (feed.size() > FEED_CAP) {
                feed.remove(0);
            }
        } catch (RuntimeException e) {
            // flavor only
        }
    }

    static News record(Kind kind, Character who, String what, String extra, long now) {
        if (!enabled()) {
            return null;
        }
        boolean bot = BotHelpers.isArtificial(who.getId());
        if (bot && SocialSim.random(who.getId(), "world-news").nextDouble() >= 0.25) {
            return null; // bots kill and drop all day: only a few make the news
        }
        for (News n : feed) {
            if (n.kind == kind && n.whoId == who.getId() && n.what.equalsIgnoreCase(what) && now - n.at < TimeUnit.MINUTES.toMillis(10)) {
                return null; // the same deed twice (one fight, many hits)
            }
        }
        News news = new News(kind, who.getName(), who.getId(), what, extra, now);
        if (who.getMap() != null) {
            for (Character c : who.getMap().getAllPlayers()) {
                if (BotHelpers.isArtificial(c.getId()) && c.getId() != who.getId() && news.witnesses.size() < WITNESSES) {
                    news.witnesses.add(c.getId());
                }
            }
        }
        feed.add(news);
        while (feed.size() > FEED_CAP) {
            feed.remove(0);
        }
        if (!bot) {
            remember(news, who.getId(), noteText(news));
        } else {
            rememberBot(news, who.getId(), who.getMapId());
        }
        return news;
    }

    static String noteText(News n) {
        return switch (n.kind) {
            case BOSS -> "was there when they killed " + n.what;
            case DROP -> "saw them get " + n.what + (n.extra == null ? "" : " from " + n.extra);
            case WEDDING -> "was at their wedding to " + n.what;
            case EVENT -> "was there when they won " + n.what;
        };
    }

    /** The witnesses keep a note about the real player. Off the caller's thread: memory reads the database. */
    private static void remember(News news, int charId, String text) {
        if (news.witnesses.isEmpty()) {
            return;
        }
        List<Integer> bots = List.copyOf(news.witnesses);
        long at = news.at;
        writer.execute(() -> {
            for (int botCharId : bots) {
                try {
                    BotMemory.remember(botCharId - BotStore.BOT_ID_BASE, charId, List.of(text), BotMemory.Source.DIRECT,
                            0, 0, at);
                } catch (RuntimeException ex) {
                    // memory is best effort
                }
            }
        });
    }

    /**
     * Stage 3: the witnesses of a bot's deed keep a note about that BOT (a subject type of its own, first hand,
     * with the map where they saw it). Off the caller's thread: memory reads the database.
     */
    private static void rememberBot(News news, int actorCharId, int mapId) {
        if (news.witnesses.isEmpty()) {
            return;
        }
        List<Integer> bots = List.copyOf(news.witnesses);
        long at = news.at;
        String text = noteText(news);
        writer.execute(() -> {
            for (int botCharId : bots) {
                try {
                    BotMemory.remember(botCharId - BotStore.BOT_ID_BASE, actorCharId, BotMemory.Kind.NOTE, text,
                            valenceOf(news.kind), mapId, BotMemory.Provenance.WITNESSED, Double.NaN, 0, at);
                } catch (RuntimeException ex) {
                    // memory is best effort
                }
            }
        });
    }

    /** How a deed feels to whoever hears of it: a mild lift, more for a wedding. */
    static double valenceOf(Kind kind) {
        return kind == Kind.WEDDING ? 0.4 : 0.3;
    }

    // ── telling: how a bot that was not there comes to know ──

    /**
     * Bot {@code teller} tells bot {@code listener} a piece of news it knows: the one worth most (fresh, least told)
     * that the teller knows at most {@link BotMemory#MAX_HOPS} - 1 mouths from a witness, the listener does not know
     * yet and that is not about either of them. The listener comes to know it after a delay of
     * {@link #TOLD_DELAY_MIN_MS}..{@link #TOLD_DELAY_MAX_MS} and keeps a REPORTED note about whoever it concerns, one
     * hop further, at the lower confidence of hearsay. Called from a {@code BotGossip} step. Returns the news told, or
     * null when the teller knows nothing it may pass on.
     */
    public static News pass(int tellerId, int listenerId, long now, Random rng) {
        if (!enabled() || tellerId == listenerId) {
            return null;
        }
        News best = null;
        double bestWeight = 0;
        for (News n : recent(now)) {
            int hops = n.hopsOf(tellerId, now);
            if (hops < 0 || hops + 1 > BotMemory.MAX_HOPS || n.whoId == listenerId || n.whoId == tellerId
                    || n.witnesses.contains(listenerId) || n.told.containsKey(listenerId) || n.told.size() >= TOLD_CAP) {
                continue;
            }
            double w = freshness(now - n.at) * (0.5 + rng.nextDouble());
            if (w > bestWeight) {
                best = n;
                bestWeight = w;
            }
        }
        if (best == null) {
            return null;
        }
        return tell(best, tellerId, listenerId, now) ? best : null;
    }

    /**
     * {@code teller} tells {@code listener} this news. False when the teller does not know it, the listener does, or
     * it would be more than {@link BotMemory#MAX_HOPS} mouths from a witness.
     */
    static boolean tell(News n, int tellerId, int listenerId, long now) {
        int hops = n.hopsOf(tellerId, now) + 1;
        if (hops < 1 || hops > BotMemory.MAX_HOPS || listenerId == n.whoId || n.witnesses.contains(listenerId)
                || n.told.size() >= TOLD_CAP) {
            return false;
        }
        long delay = TOLD_DELAY_MIN_MS + (long) (SocialSim.random(listenerId, "news-told").nextDouble()
                * (TOLD_DELAY_MAX_MS - TOLD_DELAY_MIN_MS));
        Told t = new Told(tellerId, hops, now + delay);
        if (n.told.putIfAbsent(listenerId, t) != null) {
            return false;
        }
        rememberTold(n, listenerId, t);
        return true;
    }

    /** The listener keeps a REPORTED note about whoever the news is about (when it is about someone). */
    private static void rememberTold(News n, int listenerId, Told t) {
        if (n.whoId <= 0 || (!BotHelpers.isArtificial(n.whoId) && BotAffinity.optedOut(n.whoId))) {
            return;
        }
        String text = toldText(n);
        writer.execute(() -> {
            try {
                BotMemory.remember(listenerId - BotStore.BOT_ID_BASE, n.whoId, BotMemory.Kind.NOTE, text,
                        valenceOf(n.kind), 0, BotMemory.Provenance.REPORTED, Double.NaN, 0,
                        t.fromId() - BotStore.BOT_ID_BASE, t.hops(), t.knownAt());
            } catch (RuntimeException ex) {
                // memory is best effort
            }
        });
    }

    /** What a bot that was told keeps, in its own words: "heard they killed Zakum". */
    static String toldText(News n) {
        return switch (n.kind) {
            case BOSS -> "heard they killed " + n.what;
            case DROP -> "heard they got " + n.what + (n.extra == null ? "" : " from " + n.extra);
            case WEDDING -> "heard they got married to " + n.what;
            case EVENT -> "heard they won " + n.what;
        };
    }

    // ── talking ──

    /** How much a piece of news still counts: 1 when fresh, half every {@link #HALF_LIFE_MS}, 0 past {@link #TTL_MS}. */
    public static double freshness(long ageMs) {
        if (ageMs < 0 || ageMs > TTL_MS) {
            return 0;
        }
        return Math.pow(0.5, (double) ageMs / HALF_LIFE_MS);
    }

    /** From the population director's tick, at most once every {@link #STEP_MS}. */
    public static void maybeStep(long now) {
        if (!enabled() || feed.isEmpty() || now - lastStepAt < STEP_MS) {
            return;
        }
        lastStepAt = now;
        feed.removeIf(n -> now - n.at > TTL_MS);
        if (now - lastMentionAt < MENTION_GAP_MS) {
            return;
        }
        Random rng = SocialSim.random(0, "world-news");
        List<Character> bots = CharacterStorage.getAllBots().values().stream().map(BotSM::getChr).toList();
        List<News> voiceable = new ArrayList<>(); // news nobody knows (nobody saw it, nobody was told) is not in the pick
        for (News n : feed) {
            if (!voicers(n, bots, now).isEmpty()) {
                voiceable.add(n);
            }
        }
        News news = choose(voiceable, now, rng);
        if (news == null) {
            return;
        }
        List<Character> audienceBots = voicers(news, bots, now);
        if (audienceBots.isEmpty()) {
            return;
        }
        Character teller = audienceBots.get(rng.nextInt(audienceBots.size()));
        String line = line(news, news.witnesses.contains(teller.getId()), ageText(now - news.at), rng);
        if (line == null) {
            return;
        }
        news.mentions++;
        lastMentionAt = now;
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
            if (teller.getMap() != null && teller.isAlive()) {
                BotSpeak(teller, line);
            }
        }, 2 + rng.nextInt(6), TimeUnit.SECONDS);
    }

    /**
     * The bots that may bring this news up right now: on a map, alive, not in a chat, not the one it is about, with a
     * real listener on their map - and, Stage 3, only a bot that KNOWS it (it was a witness, or it was told and the
     * telling has arrived: {@link News#knows}). A bot that neither saw nor heard it is never among them.
     */
    static List<Character> voicers(News news, java.util.Collection<Character> bots, long now) {
        List<Character> out = new ArrayList<>();
        for (Character chr : bots) {
            if (chr == null || !news.knows(chr.getId(), now) || chr.getMap() == null || chr.getId() == news.whoId
                    || !chr.isAlive() || BotConversation.hasAnyChat(chr.getId()) || !realListenerFor(chr, news)) {
                continue;
            }
            out.add(chr);
        }
        return out;
    }

    /** A real, visible player (not the one it is about) stands on the bot's map. */
    private static boolean realListenerFor(Character bot, News news) {
        for (Character c : bot.getMap().getAllPlayers()) {
            if (!BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c) && c.getId() != news.whoId
                    && !news.what.equalsIgnoreCase(c.getName())) {
                return true;
            }
        }
        return false;
    }

    /** The news to bring up this step (null: none). Fresher and less-told wins; stale news rarely does. Pure. */
    static News choose(List<News> items, long now, Random rng) {
        News best = null;
        double bestWeight = 0;
        for (News n : items) {
            if (n.mentions >= MAX_MENTIONS || (n.kind != Kind.WEDDING && n.whoId > 0
                    && !BotHelpers.isArtificial(n.whoId) && BotAffinity.optedOut(n.whoId))) {
                continue;
            }
            double w = freshness(now - n.at) * (1.0 - 0.25 * n.mentions) * (0.5 + rng.nextDouble());
            if (w > bestWeight) {
                best = n;
                bestWeight = w;
            }
        }
        if (best == null || rng.nextDouble() >= MENTION_CHANCE * freshness(now - best.at) * 2) {
            return null;
        }
        return best;
    }

    /**
     * 1.6.0 S4: the news still worth telling - fresh, told fewer than {@link #MAX_MENTIONS} times, and not about a
     * player with {@code @botdrama off} - newest first, for bot threads ({@code BotGossip.factFor}). Memory only.
     */
    public static List<News> recent(long now) {
        List<News> out = new ArrayList<>();
        if (!enabled()) {
            return out;
        }
        for (News n : feed) {
            if (freshness(now - n.at) <= 0 || n.mentions >= MAX_MENTIONS
                    || (n.whoId > 0 && !BotHelpers.isArtificial(n.whoId) && BotAffinity.optedOut(n.whoId))) {
                continue;
            }
            out.add(0, n);
        }
        return out;
    }

    /** A piece of news in a few words, as a thread's subject ("Gm killed Zakum", "Gm got a Zakum Helmet"). */
    public static String about(News n) {
        return switch (n.kind) {
            case BOSS -> n.who + " killed " + n.what;
            case DROP -> n.who + " got " + n.what;
            case WEDDING -> n.who + " and " + n.what + " got married";
            case EVENT -> n.who.isEmpty() ? n.what : n.who + " won " + n.what;
        };
    }

    static String ageText(long ageMs) {
        return ageMs > TimeUnit.HOURS.toMillis(3) ? "earlier today" : ageMs > TimeUnit.MINUTES.toMillis(30) ? "earlier" : "";
    }

    /** The spoken line, in a witness's voice or a hearer's. Pure; never says what the speaker is. */
    public static String line(News n, boolean witness, String when, Random rng) {
        String[] pool = switch (n.kind) {
            case BOSS -> witness
                    ? new String[]{"did u see {W} killed {X}", "{W} killed {X} {T} wtf", "{W} took down {X}, i was there lol",
                            "did u see {W} kill {X}??"}
                    : new String[]{"did u hear {W} killed {X}", "heard {W} killed {X} {T}", "someone said {W} killed {X}",
                            "{W} killed {X}?? no way"};
            case DROP -> witness
                    ? new String[]{"{W} got a {X} {T}, so lucky", "did u see {W} get {X}", "{W} dropped {X} from {M} lol"}
                    : new String[]{"heard {W} got {X}", "did u hear {W} got {X}??", "{W} got {X} apparently, lucky"};
            case WEDDING -> witness
                    ? new String[]{"{W} and {X} got married!!", "did u see {W} and {X}'s wedding", "{W} and {X} had a cute wedding {T}"}
                    : new String[]{"did u hear {W} and {X} got married", "heard {W} and {X} tied the knot", "{W} and {X} got married?? gz"};
            // 1.6.0 world events: {X} "the giant cake in henesys"; {W} its winner (lines with {W} need one); {M}
            // how many bots died there
            case EVENT -> witness
                    ? (n.who.isEmpty()
                    ? new String[]{"remember {X} lol", "{X} was crazy {T}", "i was at {X}, so many ppl",
                            "{X} {T} was so fun"}
                    : new String[]{"remember {X} lol", "{W} got the last of {X}, i was there", "{X} was crazy {T}",
                            "gg {W} on {X}"})
                    : (n.who.isEmpty()
                    ? new String[]{"did u go to {X}?", "missed {X} {T} :(", "heard {X} was packed"}
                    : new String[]{"did u go to {X}?", "heard {W} won {X}", "missed {X} {T} :(", "{W} won {X}?? nice"});
        };
        String line = pool[rng.nextInt(pool.length)];
        if (n.kind == Kind.EVENT && n.extra != null && witness && rng.nextDouble() < 0.4) {
            line = rng.nextBoolean() ? "{M} ppl died at {X} lol" : "so many ppl died at {X} lmao";
        }
        line = line
                .replace("{W}", n.who).replace("{X}", n.what)
                .replace("{M}", n.extra == null ? "a monster" : n.extra)
                .replace("{T}", when == null ? "" : when);
        line = line.replaceAll(" +", " ").trim();
        return line.isEmpty() ? null : line;
    }

    // ── test seams ──

    static BotWorldGossip getInstanceForTest() {
        return INSTANCE;
    }

    static List<News> feedForTest() {
        return feed;
    }

    /** Test seam: where memory writes run (null restores the real one). */
    static void useWriterForTest(Executor testWriter) {
        writer = testWriter != null ? testWriter : ExecutorServiceManager::runAsync;
    }

    static void resetForTest() {
        feed.clear();
        lastStepAt = 0;
        lastMentionAt = 0;
    }

    static News newsForTest(Kind kind, String who, int whoId, String what, String extra, long at) {
        return new News(kind, who, whoId, what, extra, at);
    }
}
