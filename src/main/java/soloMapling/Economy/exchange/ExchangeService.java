package soloMapling.Economy.exchange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.Economy.board.BoardFiles;
import soloMapling.Economy.board.SiteBoard;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Ticket 113: runs the exchange in the game server when {@code -Dslums.exchange=true} (off by default; then nothing
 * here runs and {@code !exchange} only says so). One minimum-priority daemon thread ({@code nx-exchange}), every
 * {@link ExchangeConfig#pollSeconds()}: read the site's {@code exchange-requests.json} when it changed and answer new
 * request ids, close expired orders, move payouts of offline accounts into their wallets, keep the regulars' quotes up,
 * and write {@code exchange-responses.json} and {@code exchange-book.json} to the board's outbox folder
 * ({@link SiteBoard#inbox()} / {@link SiteBoard#outbox()}). Start-up recovers from the database and halts on any
 * ledger mismatch. Nothing throws into the game.
 */
public final class ExchangeService {

    private static final Logger log = LoggerFactory.getLogger(ExchangeService.class);

    private static volatile ScheduledExecutorService executor;
    private static volatile Exchange exchange;
    private static volatile Runner runner;

    private ExchangeService() {
    }

    /** The running exchange, or null when off. */
    public static Exchange exchange() {
        return exchange;
    }

    /** Starts when {@code -Dslums.exchange=true}; otherwise does nothing. Never throws. */
    public static synchronized void startIfEnabled() {
        if (!ExchangeConfig.switchedOn() || executor != null) {
            return;
        }
        try {
            ExchangeConfig config = ExchangeConfig.fromSystem();
            Exchange ex = new Exchange(JdbcExchangeStore.forGame(), config, ExchangeService::accountInGame,
                    System::currentTimeMillis);
            List<String> problems = ex.recover();
            if (!problems.isEmpty()) {
                log.error("NX exchange HALTED at start: {} ledger mismatch(es), first: {}", problems.size(),
                        problems.get(0));
            }
            Runner r = new Runner(ex, SiteBoard.inbox(), SiteBoard.outbox(), System::currentTimeMillis);
            ScheduledExecutorService s = Executors.newSingleThreadScheduledExecutor(t -> {
                Thread th = new Thread(t, "nx-exchange");
                th.setDaemon(true);
                th.setPriority(Thread.MIN_PRIORITY);
                return th;
            });
            s.scheduleWithFixedDelay(r::tickSafely, 30, config.pollSeconds(), TimeUnit.SECONDS);
            exchange = ex;
            runner = r;
            executor = s;
            log.info("NX exchange on: {} open orders{}", ex.openOrders(), ex.halted() ? " (HALTED)" : "");
        } catch (Exception e) {
            log.warn("NX exchange did not start; the game is unaffected", e);
        }
    }

    public static synchronized void stop() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
            exchange = null;
            runner = null;
        }
    }

    /** Is any character of this account in a world right now (players, not bots)? */
    static boolean accountInGame(long account) {
        try {
            for (net.server.world.World w : net.server.Server.getInstance().getWorlds()) {
                for (client.Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c.getAccountID() == account) {
                        return true;
                    }
                }
            }
            return false;
        } catch (RuntimeException e) {
            return true; // when unsure, treat as online: the exchange then leaves the wallet alone
        }
    }

    /** The poll loop, separate from the thread so tests can drive it. */
    static final class Runner {
        private final Exchange ex;
        private final Path inbox;
        private final Path outbox;
        private final LongSupplier clock;
        private long lastModified = -1;
        private final Set<String> answered = new HashSet<>();
        private final Deque<Map<String, Object>> responses = new ArrayDeque<>();
        private long lastWarn;

        Runner(Exchange ex, Path inbox, Path outbox, LongSupplier clock) {
            this.ex = ex;
            this.inbox = inbox;
            this.outbox = outbox;
            this.clock = clock;
        }

        void tickSafely() {
            try {
                tick();
            } catch (Exception e) {
                long now = clock.getAsLong();
                if (now - lastWarn > TimeUnit.MINUTES.toMillis(15)) {
                    lastWarn = now;
                    log.warn("NX exchange tick failed", e);
                }
            }
        }

        void tick() throws IOException {
            long now = clock.getAsLong();
            boolean answeredAny = readRequests(now);
            ex.expire();
            ex.collectAll();
            ex.tickRegulars();
            if (answeredAny) {
                BoardFiles.writeJson(outbox.resolve(ExchangeSite.RESPONSES),
                        ExchangeSite.responsesFile(new ArrayList<>(responses), now));
            }
            BoardFiles.writeJson(outbox.resolve(ExchangeSite.BOOK), ExchangeSite.book(ex, now));
        }

        private boolean readRequests(long now) throws IOException {
            Path f = inbox.resolve(ExchangeSite.REQUESTS);
            long mtime;
            try {
                mtime = Files.getLastModifiedTime(f).toMillis();
            } catch (NoSuchFileException e) {
                return false;
            }
            if (mtime == lastModified) {
                return false;
            }
            lastModified = mtime;
            boolean any = false;
            for (ExchangeSite.Request r : ExchangeSite.requests(Files.readString(f, StandardCharsets.UTF_8))) {
                if (!answered.add(r.id())) {
                    continue;
                }
                any = true;
                responses.addLast(ExchangeSite.responseJson(ExchangeSite.answer(ex, r, now), r.account()));
                while (responses.size() > ExchangeSite.KEEP_RESPONSES) {
                    responses.removeFirst();
                }
            }
            if (answered.size() > 20_000) {
                answered.clear(); // places stay idempotent through the database; this only saves work
            }
            return any;
        }
    }
}
