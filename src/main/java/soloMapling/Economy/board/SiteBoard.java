package soloMapling.Economy.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.Economy.MegaBuyDesk;
import soloMapling.Economy.PriceBook;
import soloMapling.dashboard.AtomicFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 1.6.0 site write features (owner, 2026-10-26): the game server's side of the website's Buy/Sell board and price
 * alerts. The site never touches the game database and nothing here moves an item or a meso. File channel, like
 * {@code @weblogin} (contract: docs/design/track-d-plan.md, "Site board contract"):
 * <pre>
 *   inbox  (the site writes, the server reads)   posts.json, alerts.json
 *   outbox (the server writes, the site reads)   board.json, rejected.json, removed.json, alerts-fired.json
 * </pre>
 * Every 30 s the inbox files' modification times are checked; a changed file is read again, its posts are checked
 * ({@link BoardRules}), the board is rewritten and new posts get a bot's offer ({@link BoardOffers}: a whisper when
 * the poster is on, else a note). Every 15 min: alerts against the FM's listings ({@link BoardAlerts}), bots' own posts
 * ({@link BotBoardPosts}), a possible second offer on open posts, and every outbox file. The in-game board NPC at the
 * FM entrance shows the same posts ({@link #npcPage}); GMs take a post down with {@code !board remove <id>}.
 * <p>
 * Off by default. JVM properties only (no config.yaml keys):
 * <pre>
 *   -Dslums.board=true                     run it
 *   -Dslums.board.inbox=/var/lib/slums-market/inbox  (default; the site writes it, group slums-market reads)
 *   -Dslums.board.outbox=/var/lib/slums-market/outbox (default; files mode 0640, group from the setgid folder)
 *   -Dslums.board.state=Server/data/site-board-state.json   what it remembers across restarts
 * </pre>
 * One minimum-priority daemon thread ({@code site-board}); outbox files are written atomically ({@link BoardFiles}). Nothing
 * throws into the game: a failure is logged at most every 15 min and the last good board stays up.
 */
public final class SiteBoard {

    private static final Logger log = LoggerFactory.getLogger(SiteBoard.class);

    public static final String ENABLED = "slums.board";
    public static final String INBOX = "slums.board.inbox";
    public static final String OUTBOX = "slums.board.outbox";
    public static final String STATE = "slums.board.state";
    /** The site's inbox on 164 (group slums-market gives the game server read access). */
    public static final String DEFAULT_INBOX = "/var/lib/slums-market/inbox";
    /** Live's Server folder is mode 700, so the site reads the outbox here (root:slums-market, 2750; files 0640). */
    public static final String DEFAULT_OUTBOX = "/var/lib/slums-market/outbox";
    static final long POLL_SECONDS = 30;
    static final long CYCLE_MINUTES = 15;
    private static final long WARN_EVERY_MS = TimeUnit.MINUTES.toMillis(15);
    /** A post with fewer than {@link BoardState#OFFER_CAP} offers may get another after this long, by this chance. */
    static final long SECOND_OFFER_AFTER_MS = TimeUnit.HOURS.toMillis(1);
    static final double SECOND_OFFER_CHANCE = 0.25;
    public static final String NOTE_SENDER = "Slums Market";
    public static final List<String> OUTBOX_FILES = List.of("board.json", "rejected.json", "removed.json",
            "alerts-fired.json");

    private static volatile ScheduledExecutorService executor;
    private static volatile Engine engine;

    private SiteBoard() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED);
    }

    private static Path dataPath(String prop, String name) {
        String p = System.getProperty(prop);
        if (p != null && !p.isBlank()) {
            return Path.of(p.trim());
        }
        return Files.isDirectory(Path.of("Server")) ? Path.of("Server/data", name) : Path.of("data", name);
    }

    /** Where the site writes: {@code /var/lib/slums-market/inbox} unless {@code -Dslums.board.inbox} says otherwise. */
    public static Path inbox() {
        String p = System.getProperty(INBOX);
        return p != null && !p.isBlank() ? Path.of(p.trim()) : Path.of(DEFAULT_INBOX);
    }

    /** Where the site reads: {@code /var/lib/slums-market/outbox} unless {@code -Dslums.board.outbox} says otherwise. */
    public static Path outbox() {
        String p = System.getProperty(OUTBOX);
        return p != null && !p.isBlank() ? Path.of(p.trim()) : Path.of(DEFAULT_OUTBOX);
    }

    public static Path stateFile() {
        return dataPath(STATE, "site-board-state.json");
    }

    /** The board's offer limits: per account 10 an hour, 8 server-wide per 10 min, a bot rests 15 min. */
    static MegaBuyDesk.Limits defaultLimits() {
        return new MegaBuyDesk.Limits(0, Integer.getInteger("slums.board.accountPerHour", 10),
                TimeUnit.MINUTES.toMillis(10), Integer.getInteger("slums.board.serverPer10Min", 8),
                TimeUnit.MINUTES.toMillis(15));
    }

    /** Starts the board when {@code -Dslums.board=true}; otherwise does nothing. Never throws. */
    public static synchronized void startIfEnabled() {
        if (!enabled() || executor != null) {
            return;
        }
        try {
            Engine e = new Engine(inbox(), outbox(), stateFile(), new GameBoardWorld(), defaultLimits());
            ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "site-board");
                t.setDaemon(true);
                t.setPriority(Thread.MIN_PRIORITY);
                return t;
            });
            ex.scheduleWithFixedDelay(e::pollSafely, 60, POLL_SECONDS, TimeUnit.SECONDS);
            ex.scheduleWithFixedDelay(e::cycleSafely, 120, CYCLE_MINUTES * 60, TimeUnit.SECONDS);
            engine = e;
            executor = ex;
            log.info("Site board on: inbox {}, outbox {}", inbox().toAbsolutePath(), outbox().toAbsolutePath());
        } catch (RuntimeException e) {
            log.warn("Site board did not start; the game is unaffected", e);
        }
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            engine = null;
        }
    }

    // ---- the NPC and the GM command ----

    /** One page of the in-game board. {@code pages} >= 1. */
    public record NpcPage(String text, int page, int pages) {
        public boolean hasNext() {
            return page + 1 < pages;
        }

        public boolean hasPrev() {
            return page > 0;
        }
    }

    static final int NPC_PAGE_SIZE = 8;

    /** The board NPC's text for {@code page} (0-based): the open posts, newest first. Never throws. */
    public static NpcPage npcPage(int page) {
        Engine e = engine;
        if (e == null) {
            return new NpcPage("The Buy/Sell board is closed right now. Check back later!", 0, 1);
        }
        return npcPage(e.board(), page);
    }

    static NpcPage npcPage(List<Board.Post> board, int page) {
        List<Board.Post> posts = new ArrayList<>(board);
        posts.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        int pages = Math.max(1, (posts.size() + NPC_PAGE_SIZE - 1) / NPC_PAGE_SIZE);
        int p = Math.max(0, Math.min(page, pages - 1));
        StringBuilder sb = new StringBuilder();
        sb.append("#eBuy/Sell Board#n");
        if (posts.isEmpty()) {
            sb.append("\r\n\r\nNo posts right now. Post yours on the website (Market, Board): ")
                    .append("buyers and sellers find you here and on the site.");
            return new NpcPage(sb.toString(), 0, 1);
        }
        sb.append("  (").append(posts.size()).append(" open, page ").append(p + 1).append('/').append(pages)
                .append(")\r\nWhisper the poster or meet in the FM to trade.\r\n");
        for (Board.Post post : posts.subList(p * NPC_PAGE_SIZE, Math.min(posts.size(), (p + 1) * NPC_PAGE_SIZE))) {
            sb.append("\r\n").append(post.kind() == Board.Kind.WTB ? "#bWTB#k " : "#rWTS#k ")
                    .append("#t").append(post.itemId()).append('#')
                    .append(post.qty() > 1 ? " x" + post.qty() : "")
                    .append(" - ").append(PriceBook.shorthand(post.price())).append(post.qty() > 1 ? " ea" : "")
                    .append(" - #d").append(post.character()).append("#k");
        }
        if (p + 1 < pages || p > 0) {
            sb.append("\r\n");
        }
        if (p + 1 < pages) {
            sb.append("\r\n#L0#Next page#l");
        }
        if (p > 0) {
            sb.append("\r\n#L1#Previous page#l");
        }
        return new NpcPage(sb.toString(), p, pages);
    }

    /** {@code !board remove <id> [reason]}: the GM's answer. */
    public static String gmRemove(String id, String by, String reason) {
        Engine e = engine;
        if (e == null) {
            return "The site board is off (-Dslums.board).";
        }
        return e.remove(id, by, reason);
    }

    /** {@code !board}: a few lines on the board's state. */
    public static List<String> status() {
        Engine e = engine;
        if (e == null) {
            return List.of("Site board: off (start the server with -Dslums.board=true).");
        }
        return e.status();
    }

    // ---- one board ----

    /** One board over one inbox and outbox. Public for {@code !qa bd-1}, which runs its own on scratch folders. */
    public static final class Engine {
        private final Path inbox;
        private final Path outbox;
        private final Path stateFile;
        private final BoardWorld world;
        private final MegaBuyDesk.Limits limits;
        private final BoardState state;
        private final BotBoardPosts botPosts = new BotBoardPosts();
        private volatile List<Board.Post> playerOpen = List.of();
        private volatile List<Board.Rejection> rejected = List.of();
        private volatile List<Board.AccountAlerts> alerts = List.of();
        private long postsMtime = Long.MIN_VALUE;
        private long alertsMtime = Long.MIN_VALUE;
        private volatile boolean botPostsOn = true;
        private volatile long delayMinMs = Long.getLong("slums.board.offerDelayMinMs", 20_000);
        private volatile long delayMaxMs = Long.getLong("slums.board.offerDelayMaxMs", 120_000);
        private volatile Consumer<String> listener;
        private long failures;
        private volatile long lastWarnAt;

        public Engine(Path inbox, Path outbox, Path stateFile, BoardWorld world, MegaBuyDesk.Limits limits) {
            this.inbox = inbox;
            this.outbox = outbox;
            this.stateFile = stateFile;
            this.world = world;
            this.limits = limits;
            this.state = loadState(stateFile);
        }

        /** QA: offers after 1-3 s, bot posts off, every decision to {@code listener}. */
        public Engine forQa(long minMs, long maxMs, Consumer<String> listener) {
            this.delayMinMs = minMs;
            this.delayMaxMs = maxMs;
            this.listener = listener;
            this.botPostsOn = false;
            return this;
        }

        public Path outbox() {
            return outbox;
        }

        BoardState state() {
            return state;
        }

        private static BoardState loadState(Path f) {
            if (f == null) {
                return new BoardState();
            }
            try {
                return BoardState.fromJson(Files.readString(f, StandardCharsets.UTF_8));
            } catch (NoSuchFileException e) {
                return new BoardState();
            } catch (IOException | RuntimeException e) {
                log.warn("Site board state {} unreadable, starting fresh: {}", f, e.toString());
                return new BoardState();
            }
        }

        /** Every open post the board shows: players' (checked) and bots'. */
        public List<Board.Post> board() {
            List<Board.Post> all = new ArrayList<>(playerOpen);
            all.addAll(botPosts.open());
            return all;
        }

        public List<Board.Rejection> rejected() {
            return rejected;
        }

        void pollSafely() {
            safely("poll", this::poll);
        }

        void cycleSafely() {
            safely("15-minute run", this::cycle);
        }

        private void safely(String what, Runnable r) {
            try {
                r.run();
            } catch (Throwable t) {
                failures++;
                long now = System.currentTimeMillis();
                if (now - lastWarnAt >= WARN_EVERY_MS) {
                    lastWarnAt = now;
                    log.warn("Site board {} failed ({} so far); the game is unaffected", what, failures, t);
                } else {
                    log.debug("Site board {} failed", what, t);
                }
                if (t instanceof VirtualMachineError vme && !(t instanceof StackOverflowError)) {
                    throw vme;
                }
            }
        }

        private void note(String line) {
            log.info("[Board] {}", line);
            Consumer<String> l = listener;
            if (l != null) {
                l.accept(line);
            }
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.TRADE)) {
                soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.TRADE, "BOARD " + line);
            }
        }

        private static long mtime(Path p) {
            try {
                return Files.getLastModifiedTime(p).toMillis();
            } catch (IOException e) {
                return -1;   // missing: an empty file as far as the board cares
            }
        }

        /** Re-reads changed inbox files; new posts get an offer. True when something changed. */
        public synchronized boolean poll() {
            boolean changed = false;
            long pm = mtime(inbox.resolve("posts.json"));
            if (pm != postsMtime && readPosts()) {
                postsMtime = pm;
                changed = true;
                offerOnNew();
            }
            long am = mtime(inbox.resolve("alerts.json"));
            if (am != alertsMtime) {
                readAlerts();
                alertsMtime = am;
                changed = true;
            }
            if (changed) {
                writeBoard();
                saveState();
            }
            return changed;
        }

        /** The 15-minute run: posts again (expiry), bots' posts, more offers, alerts, every file. */
        public synchronized void cycle() {
            long pm = mtime(inbox.resolve("posts.json"));
            if (readPosts()) {
                postsMtime = pm;
            }
            long am = mtime(inbox.resolve("alerts.json"));
            if (am != alertsMtime) {
                readAlerts();
                alertsMtime = am;
            }
            offerOnNew();
            moreOffers();
            if (botPostsOn) {
                List<Board.Post> mine = botPosts.refresh(world, state.removedIds());
                note("BOTPOSTS open=" + mine.size());
            }
            fireAlerts();
            Set<String> present = new HashSet<>();
            playerOpen.forEach(p -> present.add(p.id()));
            rejected.forEach(r -> present.add(r.id()));
            state.prunePosts(present, world.now());
            writeBoard();
            saveState();
        }

        /** Reads and checks posts.json; false (the last board kept) when the file or the database could not be read. */
        private boolean readPosts() {
            Path f = inbox.resolve("posts.json");
            long now = world.now();
            BoardInbox.Posts in;
            BoardRules.Result r;
            try {
                in = Files.exists(f) ? BoardInbox.posts(Files.readString(f, StandardCharsets.UTF_8))
                        : new BoardInbox.Posts(List.of(), List.of());
                r = BoardRules.validate(in.posts(), world, state.removedIds(), now);
            } catch (IOException | RuntimeException e) {
                note("INBOX posts.json not read, keeping the last board: " + e.getMessage());
                return false;
            }
            List<Board.Rejection> rej = new ArrayList<>(in.malformed());
            rej.addAll(r.rejected());
            playerOpen = List.copyOf(r.open());
            rejected = List.copyOf(rej);
            note("POSTS open=" + playerOpen.size() + " rejected=" + rejected.size());
            return true;
        }

        private void readAlerts() {
            Path f = inbox.resolve("alerts.json");
            try {
                alerts = Files.exists(f) ? List.copyOf(BoardInbox.alerts(Files.readString(f, StandardCharsets.UTF_8)))
                        : List.of();
                note("ALERTS accounts=" + alerts.size() + " alerts="
                        + alerts.stream().mapToInt(a -> Math.min(BoardAlerts.MAX_ALERTS, a.alerts().size())).sum());
            } catch (IOException | RuntimeException e) {
                note("INBOX alerts.json unreadable, keeping the last alerts: " + e.getMessage());
            }
        }

        // ---- offers ----

        private void offerOnNew() {
            long now = world.now();
            for (Board.Post p : playerOpen) {
                if (state.firstSight(p.id(), now)) {
                    tryOffer(p, "new");
                }
            }
        }

        private void moreOffers() {
            long now = world.now();
            for (Board.Post p : playerOpen) {
                if (state.offers(p.id()) < BoardState.OFFER_CAP
                        && now - state.firstSeen(p.id()) >= SECOND_OFFER_AFTER_MS
                        && world.roll() < SECOND_OFFER_CHANCE) {
                    tryOffer(p, "later");
                }
            }
        }

        private void tryOffer(Board.Post p, String why) {
            BoardOffers.Offer o;
            try {
                o = BoardOffers.next(p, world, state, limits);
            } catch (RuntimeException e) {
                note("OFFER " + p.id() + " failed: " + e);
                return;
            }
            if (o == null) {
                note("NOOFFER " + p.id() + " " + p.kind() + " " + p.itemId() + " (" + why + "): no bot fits");
                return;
            }
            long delay = world.between((int) delayMinMs, (int) Math.max(delayMinMs, delayMaxMs));
            note("CLAIM " + o.botName() + " answers " + p.character() + "'s " + p.kind() + " " + p.id() + " in "
                    + delay / 1000 + "s (" + (o.botSells() ? "sells" : "buys") + " " + o.qty() + " for " + o.price()
                    + ", " + why + ")");
            world.later(delay, () -> deliver(o));
        }

        /** Sends an offer: a whisper to any character of the account that is on, else a note to the poster. */
        void deliver(BoardOffers.Offer o) {
            try {
                Board.Post p = o.post();
                if (playerOpen.stream().noneMatch(x -> x.id().equals(p.id()))) {
                    note("DROP " + p.id() + " closed before " + o.botName() + " answered");
                    return;
                }
                boolean still = o.botSells() ? world.stillHas(o.stock()) : world.spendable(o.botId()) >= o.price();
                if (!still) {
                    note("DROP " + o.botName() + " no longer has the " + (o.botSells() ? "item" : "mesos"));
                    return;
                }
                String item = world.itemName(p.itemId());
                BoardWorld.Recipient to = world.online(p.account(), p.character());
                if (to != null) {
                    String where = o.botSells() && o.stock() != null ? o.stock().where() : "im on ch" + o.channel();
                    String line = BoardOffers.line(o, item, true, where, world);
                    if (world.whisper(to, o.botName(), o.channel(), line)) {
                        if (o.botSells() && o.stock() != null && o.stock().source() == MegaBuyDesk.Source.BAG) {
                            world.quote(o.stock(), to, o.price(), o.rounds());
                        }
                        note("OFFER " + o.botName() + " -> " + to.name() + " whisper: \"" + line + "\"");
                        return;
                    }
                    note("WHISPER " + o.botName() + " -> " + to.name() + " held, sending a note");
                }
                String line = BoardOffers.line(o, item, false, null, world);
                if (world.note(o.botName(), p.character(), line)) {
                    note("OFFER " + o.botName() + " -> " + p.character() + " note: \"" + line + "\"");
                } else {
                    note("DROP " + o.botName() + " could not leave " + p.character() + " a note");
                }
            } catch (RuntimeException e) {
                note("DROP delivery failed: " + e);
            }
        }

        // ---- alerts ----

        private void fireAlerts() {
            if (alerts.isEmpty()) {
                return;
            }
            List<BoardAlerts.Fire> fired;
            try {
                fired = BoardAlerts.due(alerts, world.lowestListings(), state, world.now());
            } catch (RuntimeException e) {
                note("ALERTS skipped this run: " + e);
                return;
            }
            for (BoardAlerts.Fire f : fired) {
                Board.Alert a = f.alert();
                String item = world.itemName(a.itemId());
                String text = "Price alert: " + (item == null || item.isBlank() ? "item " + a.itemId() : item)
                        + " is listed at " + PriceBook.shorthand(f.listingPrice()) + " each in the Free Market (your alert: "
                        + (a.dir() == Board.Dir.BELOW ? "at or below " : "at or above ") + PriceBook.shorthand(a.price())
                        + "). Re-arm it on the website.";
                try {
                    BoardWorld.Recipient to = world.online(a.account(), null);
                    if (to != null) {
                        world.yellow(to, "[Market] " + text);
                        note("ALERT FIRED " + a.id() + " item " + a.itemId() + " at " + f.listingPrice() + " -> "
                                + to.name() + " (online)");
                    } else {
                        String last = world.lastPlayed(a.account());
                        boolean sent = last != null && world.note(NOTE_SENDER, last, text);
                        note("ALERT FIRED " + a.id() + " item " + a.itemId() + " at " + f.listingPrice() + " -> "
                                + (sent ? last + " (note)" : "nobody (no character to leave a note)"));
                    }
                } catch (RuntimeException e) {
                    note("ALERT FIRED " + a.id() + " but the message failed: " + e);
                }
            }
        }

        // ---- GM removal ----

        public String remove(String id, String by, String reason) {
            if (id == null || id.isBlank()) {
                return "Usage: !board remove <post id> [reason]";
            }
            String pid = id.trim();
            boolean known = board().stream().anyMatch(p -> p.id().equals(pid))
                    || rejected.stream().anyMatch(r -> r.id().equals(pid));
            if (!state.remove(pid, world.now(), by, reason == null ? "" : reason)) {
                return "Post " + pid + " was already removed.";
            }
            botPosts.remove(pid);
            playerOpen = playerOpen.stream().filter(p -> !p.id().equals(pid)).toList();
            note("REMOVED " + pid + " by " + by + (known ? "" : " (not on the board now)"));
            Runnable write = () -> {
                synchronized (this) {
                    writeBoard();
                    saveState();
                }
            };
            ScheduledExecutorService ex = executor;
            if (ex != null && engine == this) {
                ex.execute(() -> safely("removal", write));
            } else {
                CompletableFuture.runAsync(() -> safely("removal", write));
            }
            return known ? "Removed post " + pid + " from the board; the site will close it."
                    : "No open post " + pid + " right now; recorded the removal anyway (the site will close it if it exists).";
        }

        public synchronized List<String> status() {
            long bots = botPosts.open().size();
            List<String> out = new ArrayList<>();
            out.add("Site board: " + playerOpen.size() + " player posts, " + bots + " bot posts, " + rejected.size()
                    + " rejected, " + alerts.size() + " accounts with alerts.");
            out.add("Inbox " + inbox.toAbsolutePath() + ", outbox " + outbox.toAbsolutePath());
            List<Board.Post> b = board();
            b.sort((x, y) -> Long.compare(y.createdAt(), x.createdAt()));
            for (Board.Post p : b.subList(0, Math.min(8, b.size()))) {
                out.add(p.id() + "  " + p.kind() + " " + p.itemId() + " x" + p.qty() + " @ "
                        + PriceBook.shorthand(p.price()) + "  " + p.character());
            }
            return out;
        }

        // ---- files ----

        void writeBoard() {
            long now = world.now();
            write("board.json", BoardOutbox.board(board(), now));
            write("rejected.json", BoardOutbox.rejected(rejected, now));
            write("removed.json", BoardOutbox.removed(state.removals(), now));
            Set<String> alertIds = new HashSet<>();
            alerts.forEach(a -> a.alerts().forEach(al -> alertIds.add(al.id())));
            write("alerts-fired.json", BoardOutbox.alertsFired(state.firedAlerts(alertIds, now)));
        }

        private void write(String name, Object json) {
            try {
                BoardFiles.writeJson(outbox.resolve(name), json);
            } catch (IOException | RuntimeException e) {
                note("OUTBOX " + name + " not written: " + e);
            }
        }

        private void saveState() {
            if (stateFile == null) {
                return;
            }
            try {
                AtomicFiles.write(stateFile, state.toJson());
            } catch (IOException | RuntimeException e) {
                note("STATE not saved: " + e);
            }
        }
    }
}
