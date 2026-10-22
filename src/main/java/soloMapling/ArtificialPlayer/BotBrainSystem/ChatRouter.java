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

    public ChatRouter(Slotted local, BotBrain cloud, CloudBudget budget, long slowMs, LongSupplier clock) {
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

    /** Where a call of this priority goes now. */
    public enum Route { LOCAL, CLOUD, BANK }

    /** The routing decision alone (no call). */
    public Route route(Priority priority) {
        if (ConvoSwitches.forceBank()) {
            return Route.BANK;
        }
        if (!ConvoSwitches.on("router")) {
            return Route.LOCAL;
        }
        int free = local.freeSlots();
        int max = local.maxSlots();
        boolean slow = localEwmaMs > slowMs;
        return switch (priority) {
            case PLAYER -> (free == 0 || slow) && cloud != null && budget != null && budget.canSpend() ? Route.CLOUD
                    : Route.LOCAL;
            case PARTNER -> free >= 1 ? Route.LOCAL : Route.BANK;
            case THREAD -> free >= max ? Route.LOCAL : Route.BANK;
            case AMBIENT -> free >= max && clock.getAsLong() - lastPlayerAt > AMBIENT_QUIET_MS ? Route.LOCAL : Route.BANK;
        };
    }

    @Override
    public CompletableFuture<Optional<ConvoContract.Reply>> reply(Priority priority, int playerId, List<Turn> conversation) {
        Route route = route(priority);
        if (priority == Priority.PLAYER) {
            lastPlayerAt = clock.getAsLong();
        }
        switch (route) {
            case BANK -> {
                (ConvoSwitches.forceBank() ? forced : toBank.get(priority)).incrementAndGet();
                return CompletableFuture.completedFuture(Optional.empty());
            }
            case CLOUD -> {
                toCloud.incrementAndGet();
                long started = clock.getAsLong();
                return cloud.reply(priority, playerId, conversation).thenApply(r -> {
                    long in = 0;
                    for (Turn t : conversation) {
                        in += CloudBudget.estimateTokens(t.content());
                    }
                    long out = r.map(x -> CloudBudget.estimateTokens(x.text()) + 4).orElse(4L);
                    if (cloud instanceof OpenAICompatibleBrain o) {
                        long[] usage = o.lastUsage();
                        if (usage[0] > 0) {
                            in = usage[0];
                            out = Math.max(1, usage[1]);
                        }
                    }
                    budget.charge(in, out);
                    ChatStats.cloudCall(clock.getAsLong() - started, r.isPresent());
                    return r;
                });
            }
            default -> {
                toLocal.get(priority).incrementAndGet();
                long started = clock.getAsLong();
                return local.reply(priority, playerId, conversation).thenApply(r -> {
                    long ms = clock.getAsLong() - started;
                    localEwmaMs = localEwmaMs == 0 ? ms : localEwmaMs * 0.7 + ms * 0.3;
                    return r;
                });
            }
        }
    }

    @Override
    public CompletableFuture<Optional<String>> complete(int playerId, List<Turn> conversation) {
        return reply(Priority.PLAYER, playerId, conversation).thenApply(o -> o.map(ConvoContract.Reply::text));
    }

    @Override
    public CompletableFuture<Optional<String>> notes(List<Turn> conversation) {
        return local.notes(conversation);
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
