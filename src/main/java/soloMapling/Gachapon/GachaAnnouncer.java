package soloMapling.Gachapon;

import client.Character;
import client.inventory.Item;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotMegaphoneGate;
import soloMapling.ArtificialPlayer.BotHelpers;
import tools.PacketCreator;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 1.5.0: the server-wide gachapon notice (the v83 yellow "X : got a(n) [item] from the Henesys gachapon" line with
 * the item) when anyone, player or bot, wins a tier the pool file marks {@code announce: true} (rare, jackpot).
 * <p>
 * Rate limits, all {@code -Dsolomapling.*} properties (seconds):
 * <ul>
 *   <li>{@code gachaNoticeGapSeconds} (20): at most one notice in that time, server-wide.</li>
 *   <li>{@code gachaNoticePlayerSeconds} (60): one notice per character in that time; a second win inside it is
 *   not announced.</li>
 *   <li>A player's notice that meets the gap waits in a short queue (5 deep, {@code gachaNoticeMaxDelaySeconds}
 *   (300) at most) and goes out when the gap opens: a player who won is announced, a little late at worst.</li>
 *   <li>A bot's notice must also get the bot megaphone budget ({@link BotMegaphoneGate}, one bot megaphone per
 *   {@code botMegaphoneGapSeconds} server-wide) and {@code gachaBotNoticeGapSeconds} (900) between bot notices.
 *   A bot that misses any of them just is not announced; it never waits.</li>
 *   <li>A player's notice claims the bot megaphone budget too ({@link BotMegaphoneGate#note()}), so bots' pink
 *   lines do not pile on right after it.</li>
 * </ul>
 * {@code -Dsolomapling.gachaNotices=false} turns notices off.
 */
public final class GachaAnnouncer {

    private static final Logger log = LoggerFactory.getLogger("server.gachapon.Gachapon");

    static final long DEFAULT_GAP_S = 20, DEFAULT_PLAYER_S = 60, DEFAULT_BOT_GAP_S = 900, DEFAULT_MAX_DELAY_S = 300;
    static final int QUEUE_MAX = 5;

    /** One notice: who won, what, at which machine. */
    public record Notice(Character winner, Item item, String townName, boolean bot, long at) {
    }

    private static final GachaAnnouncer INSTANCE = new GachaAnnouncer();

    private final AtomicLong lastAt = new AtomicLong(Long.MIN_VALUE / 2);
    private final AtomicLong lastBotAt = new AtomicLong(Long.MIN_VALUE / 2);
    private final Map<Integer, Long> lastByChar = new ConcurrentHashMap<>();
    private final Deque<Notice> queue = new ArrayDeque<>();
    private final AtomicLong sent = new AtomicLong(), sentBots = new AtomicLong(), skipped = new AtomicLong();
    private volatile LongSupplier clock = System::currentTimeMillis;
    private volatile BooleanSupplier botGate = BotMegaphoneGate::tryAcquire;
    private volatile Runnable gateNote = BotMegaphoneGate::note;
    private volatile Consumer<Notice> poster = GachaAnnouncer::post;
    private volatile boolean flushScheduled;

    private GachaAnnouncer() {
    }

    public static GachaAnnouncer get() {
        return INSTANCE;
    }

    static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.gachaNotices", "true"));
    }

    private static long seconds(String key, long fallback) {
        return TimeUnit.SECONDS.toMillis(Math.max(0, Long.getLong(key, fallback)));
    }

    /**
     * A win worth a notice. True when it went out now; false when it was queued (a player), skipped by a limit, or
     * notices are off. The reason is logged.
     */
    public synchronized boolean announce(Character winner, Item item, String townName) {
        if (!enabled() || winner == null || item == null) {
            return false;
        }
        long now = clock.getAsLong();
        boolean bot = BotHelpers.isBot(winner);
        Long last = lastByChar.get(winner.getId());
        if (last != null && now - last < seconds("solomapling.gachaNoticePlayerSeconds", DEFAULT_PLAYER_S)) {
            skipped.incrementAndGet();
            log.info("[GachaNotice] {} not announced: their last notice was {} s ago", winner.getName(),
                    (now - last) / 1000);
            return false;
        }
        Notice n = new Notice(winner, item, townName, bot, now);
        boolean gapOpen = now - lastAt.get() >= seconds("solomapling.gachaNoticeGapSeconds", DEFAULT_GAP_S);
        if (bot) {
            if (!gapOpen || now - lastBotAt.get() < seconds("solomapling.gachaBotNoticeGapSeconds", DEFAULT_BOT_GAP_S)
                    || !botGate.getAsBoolean()) {
                skipped.incrementAndGet();
                log.info("[GachaNotice] bot {} not announced (gap or bot megaphone budget)", winner.getName());
                return false;
            }
            lastBotAt.set(now);
            send(n, now);
            return true;
        }
        if (gapOpen && queue.isEmpty()) {
            send(n, now);
            return true;
        }
        if (queue.size() >= QUEUE_MAX) {
            skipped.incrementAndGet();
            log.info("[GachaNotice] {} not announced: the notice queue is full", winner.getName());
            return false;
        }
        lastByChar.put(winner.getId(), now);   // one queued notice per player
        queue.addLast(n);
        scheduleFlush();
        return false;
    }

    /** Sends queued players' notices whose time has come; drops the ones that waited too long. */
    public synchronized int flush() {
        flushScheduled = false;
        long now = clock.getAsLong();
        int out = 0;
        while (!queue.isEmpty()) {
            Notice n = queue.peekFirst();
            if (now - n.at() > seconds("solomapling.gachaNoticeMaxDelaySeconds", DEFAULT_MAX_DELAY_S)) {
                queue.pollFirst();
                skipped.incrementAndGet();
                continue;
            }
            if (now - lastAt.get() < seconds("solomapling.gachaNoticeGapSeconds", DEFAULT_GAP_S)) {
                break;
            }
            queue.pollFirst();
            send(n, now);
            out++;
        }
        if (!queue.isEmpty()) {
            scheduleFlush();
        }
        return out;
    }

    private void scheduleFlush() {
        if (flushScheduled || testClock) {
            return;
        }
        flushScheduled = true;
        long waitMs = Math.max(1_000, seconds("solomapling.gachaNoticeGapSeconds", DEFAULT_GAP_S)
                - (clock.getAsLong() - lastAt.get()) + 250);
        try {
            server.TimerManager.getInstance().schedule(this::flush, waitMs);
        } catch (RuntimeException e) {
            flushScheduled = false;   // no timer (a tool, a test): the next announce or flush() sends it
        }
    }

    private volatile boolean testClock;   // a test drives flush() itself

    private void send(Notice n, long now) {
        lastAt.set(now);
        lastByChar.put(n.winner().getId(), now);
        if (lastByChar.size() > 5_000) {
            long cutoff = now - seconds("solomapling.gachaNoticePlayerSeconds", DEFAULT_PLAYER_S);
            lastByChar.values().removeIf(at -> at < cutoff);
        }
        sent.incrementAndGet();
        if (n.bot()) {
            sentBots.incrementAndGet();
        } else {
            try {
                gateNote.run();
            } catch (RuntimeException ignored) {
                // the bot gate is a courtesy here
            }
        }
        poster.accept(n);
    }

    private static void post(Notice n) {
        Character chr = n.winner();
        Server.getInstance().broadcastMessage(chr.getWorld(), PacketCreator.gachaponMessage(n.item(), n.townName(), chr));
        log.info("[GachaNotice] {}{} : {} ({}) from the {} gachapon", chr.getName(), n.bot() ? " (bot)" : "",
                n.item().getItemId(), server.ItemInformationProvider.getInstance().getName(n.item().getItemId()),
                n.townName());
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.GACHA)) {
            // t-ga-1 can only see the notice if it is written down: the yellow packet itself leaves no log line, and
            // the check used to wait for a NOTICE line nobody produced.
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.GACHA, "NOTICE [11] " + chr.getName() + " : got "
                    + server.ItemInformationProvider.getInstance().getName(n.item().getItemId())
                    + " from the " + n.townName() + " gachapon");
        }
    }

    /** "notices 12 (3 by bots), 4 not announced, 0 waiting" */
    public synchronized String status() {
        return String.format("notices sent %d (%d by bots), %d not announced, %d waiting", sent.get(), sentBots.get(),
                skipped.get(), queue.size());
    }

    /** Test seam: clock, bot gate, gate note and poster (null restores the real one); a clean slate. */
    synchronized void useForTest(LongSupplier c, BooleanSupplier gate, Runnable note, Consumer<Notice> post) {
        testClock = c != null;
        clock = c == null ? System::currentTimeMillis : c;
        botGate = gate == null ? BotMegaphoneGate::tryAcquire : gate;
        gateNote = note == null ? BotMegaphoneGate::note : note;
        poster = post == null ? GachaAnnouncer::post : post;
        lastAt.set(Long.MIN_VALUE / 2);
        lastBotAt.set(Long.MIN_VALUE / 2);
        lastByChar.clear();
        queue.clear();
        sent.set(0);
        sentBots.set(0);
        skipped.set(0);
        flushScheduled = false;
    }
}
