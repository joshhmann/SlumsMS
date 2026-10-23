package soloMapling.ArtificialPlayer.BotBrainSystem;

import soloMapling.ArtificialPlayer.BotConvo.ConvoContract;
import soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Ticket 59, owner decision 7: which model answers. Local Qwen first; an optional OpenAI-compatible cloud model for
 * OVERFLOW only - replies to real players when every local slot is busy or the local model is slow - inside a daily
 * budget ({@link CloudBudget}). Lower priorities take a local slot only when one is spare and otherwise get nothing,
 * so the caller uses the line bank or a script:
 * <ul>
 *   <li>{@link Priority#PLAYER}: local (queued within the deadline), or the cloud on overflow;</li>
 *   <li>{@link Priority#PARTNER}: local with a free slot;</li>
 *   <li>{@link Priority#THREAD}: local only when every slot is free;</li>
 *   <li>{@link Priority#AMBIENT}: local only when every slot is free and no player reply started in the last 10 s.</li>
 * </ul>
 * The cloud mode ({@code BOT_LLM_CLOUD_MODE}, {@link CloudMode}) can put the cloud first instead: for real players'
 * replies ({@code players}) or for every call ({@code all}). In every mode the local model is the fallback when the
 * cloud fails, times out or the budget is spent, every cloud call reserves its worst case against the hard daily cap
 * first, and in {@code all} mode calls that are not a player's own line stop going to the cloud at 80% of the day's
 * budget, so the rest is kept for players.
 * {@code -Dsolomapling.convo.forceBank=true} answers nothing (bank and scripts only, !qa cv-7);
 * {@code -Dsolomapling.convo.router=false} sends everything to the local model as before.
 */
public final class ChatRouter implements BotBrain {

    /** A brain whose free slots can be read (the local {@link OpenAICompatibleBrain}). */
    public interface Slotted extends BotBrain {
        int freeSlots();

        int maxSlots();
    }

    static final long AMBIENT_QUIET_MS = 10_000;
    /** In {@code all} mode, lower-priority calls stop going to the cloud at this share of the day's budget. */
    static final double LOW_PRIORITY_BUDGET_SHARE = 0.8;
    /** Worst-case output tokens of a thread and of a notes call (OpenAICompatibleBrain's limits). */
    static final int THREAD_MAX_TOKENS = 140;
    static final int NOTES_MAX_TOKENS = 120;

    /** Where the cloud sits (owner, 2026-10-22). */
    public enum CloudMode {
        /** Today's behaviour: real players' replies overflow to the cloud when local is full or slow. */
        OVERFLOW,
        /** Real players' replies go to the cloud first. */
        PLAYERS,
        /** Every LLM call goes to the cloud first (lower priorities only below 80% of the budget). */
        ALL;

        /** "overflow", "players" or "all" (any case); anything else is OVERFLOW. */
        public static CloudMode parse(String raw) {
            if (raw == null) {
                return OVERFLOW;
            }
            return switch (raw.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "players", "player" -> PLAYERS;
                case "all" -> ALL;
                default -> OVERFLOW;
            };
        }
    }

    /** What the hourly usage line counts besides the priorities. */
    static final String NOTES = "NOTES";

    private volatile CloudMode mode = CloudMode.OVERFLOW;
    private final Map<String, AtomicLong> cloudHour = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLong cloudFallbacks = new AtomicLong();
    private volatile long hourStart = -1;
    private volatile double hourStartSpent;

    private final Slotted local;
    private final BotBrain cloud;
    private final CloudBudget budget;
    private final long slowMs;
    private final LongSupplier clock;
    private volatile double localEwmaMs;
    private volatile long lastPlayerAt;
    private final Map<Priority, AtomicLong> toLocal = new EnumMap<>(Priority.class);
    private final Map<Priority, AtomicLong> toBank = new EnumMap<>(Priority.class);
    private final AtomicLong toCloud = new AtomicLong();
    private final AtomicLong forced = new AtomicLong();

    /** Output tokens a cloud reply may use at most (its max_tokens): the worst case the budget reserves. */
    private final int cloudMaxTokens;

    public ChatRouter(Slotted local, BotBrain cloud, CloudBudget budget, long slowMs, LongSupplier clock) {
        this(local, cloud, budget, slowMs, clock, 64);
    }

    public ChatRouter(Slotted local, BotBrain cloud, CloudBudget budget, long slowMs, LongSupplier clock,
                      int cloudMaxTokens) {
        this.cloudMaxTokens = Math.max(1, cloudMaxTokens);
        this.local = local;
        this.cloud = cloud;
        this.budget = budget;
        this.slowMs = slowMs;
        this.clock = clock;
        for (Priority p : Priority.values()) {
            toLocal.put(p, new AtomicLong());
            toBank.put(p, new AtomicLong());
        }
    }

    /** This router with the cloud placed by {@code mode}. */
    public ChatRouter withMode(CloudMode mode) {
        this.mode = mode == null ? CloudMode.OVERFLOW : mode;
        return this;
    }

    public CloudMode mode() {
        return mode;
    }

    /** Where a call of this priority goes now. */
    public enum Route { LOCAL, CLOUD, BANK }

    private boolean cloudReady() {
        return cloud != null && budget != null && budget.canSpend();
    }

    /** Whether this priority goes to the cloud first in the current mode (budget permitting). */
    boolean cloudFirst(Priority priority) {
        if (!cloudReady()) {
            return false;
        }
        return switch (mode) {
            case OVERFLOW -> false;
            case PLAYERS -> priority == Priority.PLAYER;
            case ALL -> priority == Priority.PLAYER || budget.usedShare() < LOW_PRIORITY_BUDGET_SHARE;
        };
    }

    /** The routing decision alone (no call). */
    public Route route(Priority priority) {
        if (ConvoSwitches.forceBank()) {
            return Route.BANK;
        }
        if (!ConvoSwitches.on("router")) {
            return Route.LOCAL;
        }
        if (cloudFirst(priority)) {
            return Route.CLOUD;
        }
        return localRoute(priority);
    }

    /** Where a call goes when the cloud is not first (or failed): local, overflow to the cloud, or the bank. */
    private Route localRoute(Priority priority) {
        int free = local.freeSlots();
        int max = local.maxSlots();
        boolean slow = localEwmaMs > slowMs;
        return switch (priority) {
            case PLAYER -> (free == 0 || slow) && cloudReady() ? Route.CLOUD : Route.LOCAL;
            case PARTNER -> free >= 1 ? Route.LOCAL : Route.BANK;
            case THREAD -> free >= max ? Route.LOCAL : Route.BANK;
            case AMBIENT -> free >= max && clock.getAsLong() - lastPlayerAt > AMBIENT_QUIET_MS ? Route.LOCAL : Route.BANK;
        };
    }

    @Override
    public CompletableFuture<Optional<ConvoContract.Reply>> reply(Priority priority, int playerId, List<Turn> conversation) {
        maybeReport();
        Route route = route(priority);
        if (priority == Priority.PLAYER) {
            lastPlayerAt = clock.getAsLong();
        }
        double reservation = 0;
        if (route == Route.CLOUD) {
            // the hard cap: the worst case (input at two characters a token, the full max_tokens out) is reserved first
            reservation = worstCase(conversation, priority == Priority.THREAD ? Math.max(cloudMaxTokens, THREAD_MAX_TOKENS)
                    : cloudMaxTokens);
            if (!budget.reserve(reservation)) {
                route = afterCloud(priority);
            }
        }
        final double reserved = reservation;
        switch (route) {
            case BANK -> {
                (ConvoSwitches.forceBank() ? forced : toBank.get(priority)).incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            case CLOUD -> {
                toCloud.incrementAndGet();
                cloudHour.computeIfAbsent(priority.name(), k -> new AtomicLong()).incrementAndGet();
                long started = clock.getAsLong();
                CompletableFuture<Optional<ConvoContract.Reply>> call = cloud.reply(priority, playerId, conversation);
                return call.handle((got, e) -> {
                    Optional<ConvoContract.Reply> r = e == null && got != null ? got : Optional.empty();
                    settle(reserved, conversation, r.map(ConvoContract.Reply::text), usageOf(call));
                    ChatStats.cloudCall(clock.getAsLong() - started, r.isPresent());
                    return r;
                }).thenCompose(r -> {
                    if (r.isPresent()) {
                        return CompletableFuture.completedFuture(r);
                    }
                    // the cloud failed or timed out: the local model (or, for low priorities without a slot, the bank)
                    cloudFallbacks.incrementAndGet();
                    return viaLocal(afterCloud(priority), priority, playerId, conversation);
                });
            }
            default -> {
                return viaLocal(route, priority, playerId, conversation);
            }
        }
    }

    /** Where a call goes when the cloud was tried and could not answer: never the cloud again. */
    private Route afterCloud(Priority priority) {
        Route r = localRoute(priority);
        return r == Route.CLOUD ? Route.LOCAL : r;
    }

    private CompletableFuture<Optional<ConvoContract.Reply>> viaLocal(Route route, Priority priority, int playerId,
                                                                     List<Turn> conversation) {
        if (route == Route.BANK) {
            toBank.get(priority).incrementAndGet();
            return CompletableFuture.completedFuture(Optional.empty());
        }
        toLocal.get(priority).incrementAndGet();
        long started = clock.getAsLong();
        return local.reply(priority, playerId, conversation).thenApply(r -> {
            long ms = clock.getAsLong() - started;
            localEwmaMs = localEwmaMs == 0 ? ms : localEwmaMs * 0.7 + ms * 0.3;
            return r;
        });
    }

    private double worstCase(List<Turn> conversation, int maxOut) {
        long chars = 0;
        for (Turn t : conversation) {
            chars += t.content() == null ? 0 : t.content().length();
        }
        return budget.cost((chars + 1) / 2 + 16, maxOut);
    }

    /** The call's own token use when its brain reports one (never another call's: rc6 bug hunt), else null. */
    static long[] usageOf(CompletableFuture<?> call) {
        return call instanceof OpenAICompatibleBrain.Metered<?> m ? m.usage() : null;
    }

    /**
     * Charges a cloud call: its own {@code usage} when the response had one, else an estimate from the text. It used
     * to read the brain-wide last usage, which a concurrent call could already have overwritten.
     */
    private void settle(double reserved, List<Turn> conversation, Optional<String> reply, long[] usage) {
        long in = 0;
        for (Turn t : conversation) {
            in += CloudBudget.estimateTokens(t.content());
        }
        long out = reply.map(x -> CloudBudget.estimateTokens(x) + 4).orElse(4L);
        if (usage != null && usage.length >= 2 && usage[0] > 0) {
            in = usage[0];
            out = Math.max(1, usage[1]);
        }
        budget.settle(reserved, in, out);
    }

    /**
     * One log line per hour with the cloud calls of the last hour by priority (and notes) and the dollars spent, so
     * real daily use can be read off the log. Called on every call and by the chat sweeper.
     */
    public void maybeReport() {
        if (cloud == null || budget == null) {
            return;
        }
        long now = clock.getAsLong();
        if (hourStart < 0) {
            hourStart = now;
            hourStartSpent = budget.spentToday();
            return;
        }
        if (now - hourStart < 3_600_000L) {
            return;
        }
        String line;
        synchronized (this) {
            if (now - hourStart < 3_600_000L) {
                return;
            }
            double spent = budget.spentToday();
            double hour = Math.max(0, spent - hourStartSpent); // a new day resets spentToday: count from zero then
            if (spent < hourStartSpent) {
                hour = spent;
            }
            line = usageLine(hour);
            cloudHour.clear();
            cloudFallbacks.set(0);
            hourStart = now;
            hourStartSpent = spent;
        }
        LOG.info(line);
    }

    /** The hourly usage line (also on the dashboard via {@link #describe}). */
    String usageLine(double hourUsd) {
        StringBuilder s = new StringBuilder("Cloud chat, last hour (mode ").append(mode.name().toLowerCase(java.util.Locale.ROOT))
                .append("): ");
        long total = 0;
        for (String k : new String[]{"PLAYER", "PARTNER", "THREAD", "AMBIENT", NOTES}) {
            long n = cloudHour.getOrDefault(k, new AtomicLong()).get();
            total += n;
            s.append(k.toLowerCase(java.util.Locale.ROOT)).append(' ').append(n).append(", ");
        }
        s.append("total ").append(total).append(", fell back to local ").append(cloudFallbacks.get())
                .append(String.format(java.util.Locale.ROOT, ", $%.4f this hour; ", hourUsd)).append(budget.describe());
        return s.toString();
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ChatRouter.class);

    @Override
    public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
        // legacy callers are not a player's own line (an on-sight roast, a GM test): never the cloud
        return reply(Priority.AMBIENT, playerId, conversation).thenApply(o -> o.map(ConvoContract.Reply::text));
    }

    @Override
    public CompletableFuture<Optional<String>> notes(List<Turn> conversation) {
        // in "all" mode the cloud writes the notes too, as a low priority (below 80% of the budget), local on failure
        maybeReport();
        if (mode != CloudMode.ALL || ConvoSwitches.forceBank() || !cloudReady()
                || budget.usedShare() >= LOW_PRIORITY_BUDGET_SHARE) {
            return local.notes(conversation);
        }
        double reserved = worstCase(conversation, NOTES_MAX_TOKENS);
        if (!budget.reserve(reserved)) {
            return local.notes(conversation);
        }
        toCloud.incrementAndGet();
        cloudHour.computeIfAbsent(NOTES, k -> new AtomicLong()).incrementAndGet();
        CompletableFuture<Optional<String>> call = cloud.notes(conversation);
        return call.handle((got, e) -> {
            Optional<String> r = e == null && got != null ? got : Optional.<String>empty();
            settle(reserved, conversation, r, usageOf(call));
            return r;
        }).thenCompose(r -> {
            if (r.isPresent()) {
                return CompletableFuture.completedFuture(r);
            }
            cloudFallbacks.incrementAndGet();
            return local.notes(conversation);
        });
    }

    @Override
    public boolean isLive() {
        return local.isLive();
    }

    double localEwmaMs() {
        return localEwmaMs;
    }

    public CloudBudget budget() {
        return budget;
    }

    @Override
    public String describe() {
        StringBuilder s = new StringBuilder(local.describe());
        s.append(" | cloud mode ").append(mode.name().toLowerCase(java.util.Locale.ROOT));
        s.append(" | router: local ").append(toLocal).append(", bank ").append(toBank).append(", cloud ")
                .append(toCloud.get()).append(", forced bank ").append(forced.get())
                .append(String.format(java.util.Locale.ROOT, ", local avg %.0f ms (slow at %d)", localEwmaMs, slowMs));
        if (cloud != null && budget != null) {
            s.append(" | ").append(budget.describe());
        } else {
            s.append(" | no cloud");
        }
        return s.toString();
    }
}
