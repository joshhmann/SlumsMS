package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.Trade;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * 1.6.2 ticket 99: a companion hands things over in a normal trade window - after nutnnut/Cosmic
 * {@code BotInventoryManager.startTradeSequence/tickTrade} and {@code BotTradePacing}. The giver opens a trade with
 * the recipient, waits for them to accept, drags the items in one by one at a person's pace ({@link TradePacing}),
 * adds the mesos, says so in the window, waits for the recipient to press Trade and confirms. Nothing is teleported:
 * every item goes through {@link Trade#putFromInventory} (the player's own put, with its untradeable and cash
 * checks), the trade log, the fee and the partner's inventory checks, exactly as two players trading.
 *
 * <p>One implementation for every companion: an owned alt giving to its owner or a sibling alt, and (when its
 * switch allows) a party bot friend giving to its leader. A recipient with no person behind it (a sibling alt) has
 * its accept and its Trade button pressed by this flow too, with the same beats. Population bots keep their holdings
 * in BotHoldings, which a window cannot hand over yet: {@link #canGive} refuses them until that path exists.
 */
public final class BotGiveTrade {

    /** How long the recipient has to accept the invite, and then to press Trade. */
    static final long ACCEPT_TIMEOUT_MS = 30_000;
    static final long CONFIRM_TIMEOUT_MS = 60_000;
    static final long POLL_MS = 500;
    /** The v83 window holds nine items a side. */
    public static final int WINDOW_SLOTS = 9;

    /** One stack (or equip) to hand over: the inventory item itself and how many of it. */
    public record Give(InventoryType type, Item item, short quantity) {
    }

    /** How it ended: traded (the window completed), declined, timed out, or it could not start. */
    public enum End { TRADED, DECLINED, TIMED_OUT, CANCELLED, COULD_NOT_START }

    /**
     * A handover: who gives what to whom, what the giver says while it happens ({@code say}: its own voice, e.g. party
     * chat for an alt), the window line once everything is in, and what to do when it ends.
     */
    public record Plan(Character giver, Character recipient, List<Give> items, int mesos, String windowLine,
                       Consumer<String> say, Consumer<End> after) {
    }

    /** The trade window operations, so the flow can be tested without packets. */
    public interface Window {
        /** Opens the giver's trade and invites the recipient. False when it did not open. */
        boolean invite(Character giver, Character recipient);

        /** The giver's trade is still open. */
        boolean open(Character giver);

        /** The recipient accepted (both sides in the window). */
        boolean accepted(Character giver);

        /** A recipient with no person behind it accepts. */
        void accept(Character recipient, Character giver);

        Trade.PutResult put(Character giver, InventoryType type, short pos, short quantity, byte slot);

        void mesos(Character giver, int mesos);

        void chat(Character giver, String line);

        /** The recipient pressed Trade. */
        boolean partnerLocked(Character giver);

        /** Presses Trade for {@code who}. */
        void confirm(Character who);

        void cancel(Character giver);

        /** Calls {@code onTraded} when the giver's open window completes. */
        void onTraded(Character giver, Runnable onTraded);
    }

    /** When things happen: {@link BotTiming#after} in game, a hand-cranked queue in tests. */
    public interface Clock {
        void after(long ms, Runnable step);
    }

    static final Window TRADE = new Window() {
        @Override
        public boolean invite(Character giver, Character recipient) {
            Trade.startTrade(giver);
            Trade.inviteTrade(giver, recipient);
            return giver.getTrade() != null && giver.getTrade().getPartner() != null;
        }

        @Override
        public boolean open(Character giver) {
            return giver.getTrade() != null;
        }

        @Override
        public boolean accepted(Character giver) {
            Trade t = giver.getTrade();
            return t != null && t.isFullTrade();
        }

        @Override
        public void accept(Character recipient, Character giver) {
            Trade.visitTrade(recipient, giver);
        }

        @Override
        public Trade.PutResult put(Character giver, InventoryType type, short pos, short quantity, byte slot) {
            return Trade.putFromInventory(giver, type, pos, quantity, slot);
        }

        @Override
        public void mesos(Character giver, int mesos) {
            Trade t = giver.getTrade();
            if (t != null && !t.isLocked()) {
                t.setMeso(mesos);
            }
        }

        @Override
        public void chat(Character giver, String line) {
            Trade t = giver.getTrade();
            if (t != null) {
                t.chat(line);
            }
        }

        @Override
        public boolean partnerLocked(Character giver) {
            Trade t = giver.getTrade();
            return t != null && t.getPartner() != null && t.getPartner().isLocked();
        }

        @Override
        public void confirm(Character who) {
            if (who.getTrade() != null) {
                Trade.completeTrade(who);
            }
        }

        @Override
        public void cancel(Character giver) {
            Trade.cancelTrade(giver, Trade.TradeResult.NO_RESPONSE);
        }

        @Override
        public void onTraded(Character giver, Runnable onTraded) {
            Trade t = giver.getTrade();
            if (t != null) {
                t.setTradeResultCallback(r -> {
                    if (r == Trade.TradeResult.SUCCESSFUL) {
                        onTraded.run();
                    }
                });
            }
        }
    };

    private static volatile Window window = TRADE;
    private static volatile Clock clock = BotTiming::after;
    private static final Set<Integer> busy = ConcurrentHashMap.newKeySet();

    private BotGiveTrade() {
    }

    /** Tests: the window and the clock. */
    public static void useForTest(Window w, Clock c) {
        window = w == null ? TRADE : w;
        clock = c == null ? BotTiming::after : c;
        busy.clear();
    }

    /** Whether this character can hand things over in a window (a population bot cannot yet). */
    public static boolean canGive(Character giver) {
        return giver != null && !BotHelpers.isRosterBot(giver);
    }

    /** The giver is in the middle of a handover. */
    public static boolean isBusy(Character giver) {
        return giver != null && busy.contains(giver.getId());
    }

    /**
     * Starts a handover. False (and nothing happens) when the giver cannot give, is already handing something over,
     * either side is already in a trade, they are not on the same map, or there is nothing to give.
     */
    public static boolean start(Plan plan) {
        Character giver = plan.giver();
        Character recipient = plan.recipient();
        if (!canGive(giver) || recipient == null || giver.getId() == recipient.getId()
                || (plan.items().isEmpty() && plan.mesos() <= 0)) {
            return false;
        }
        if (giver.getTrade() != null || recipient.getTrade() != null || giver.getMap() == null
                || giver.getMap() != recipient.getMap()) {
            return false;
        }
        if (!busy.add(giver.getId())) {
            return false;
        }
        Run run = new Run(plan, new Random(ThreadLocalRandom.current().nextLong()));
        clock.after(TradePacing.stepMs(run.rng), run::invite);
        return true;
    }

    /** One handover in flight. Every step is one beat after the previous one. */
    private static final class Run {
        final Plan plan;
        final Random rng;
        final List<Give> queue;
        volatile boolean traded;
        long waitedMs;
        int slot = 1;

        Run(Plan plan, Random rng) {
            this.plan = plan;
            this.rng = rng;
            this.queue = new ArrayList<>(plan.items().subList(0, Math.min(WINDOW_SLOTS, plan.items().size())));
        }

        Character giver() {
            return plan.giver();
        }

        boolean headlessRecipient() {
            return plan.recipient().getClient() instanceof BotClient;
        }

        void invite() {
            if (!window.invite(giver(), plan.recipient())) {
                end(End.COULD_NOT_START);
                return;
            }
            window.onTraded(giver(), () -> traded = true);
            log("opens a trade with " + plan.recipient().getName());
            if (headlessRecipient()) {
                clock.after(TradePacing.stepMs(rng), () -> {
                    if (window.open(giver())) {
                        window.accept(plan.recipient(), giver());
                    }
                    waitAccept();
                });
            } else {
                clock.after(POLL_MS, this::waitAccept);
            }
        }

        void waitAccept() {
            if (!window.open(giver())) {
                end(End.DECLINED);
                return;
            }
            if (!window.accepted(giver())) {
                waitedMs += POLL_MS;
                if (waitedMs >= ACCEPT_TIMEOUT_MS) {
                    window.cancel(giver());
                    end(End.TIMED_OUT);
                    return;
                }
                clock.after(POLL_MS, this::waitAccept);
                return;
            }
            waitedMs = 0;
            clock.after(TradePacing.stepMs(rng), this::putNext);
        }

        void putNext() {
            if (!window.open(giver())) {
                end(End.CANCELLED);
                return;
            }
            if (!queue.isEmpty()) {
                Give g = queue.remove(0);
                Trade.PutResult r = window.put(giver(), g.type(), g.item().getPosition(), g.quantity(), (byte) slot);
                if (r == Trade.PutResult.ADDED) {
                    slot++;
                } else {
                    log("could not put " + g.item().getItemId() + " in the window: " + r);
                }
                clock.after(TradePacing.stepMs(rng), this::putNext);
                return;
            }
            if (plan.mesos() > 0) {
                window.mesos(giver(), Math.min(plan.mesos(), giver().getMeso()));
                clock.after(TradePacing.stepMs(rng), this::sayDone);
                return;
            }
            sayDone();
        }

        void sayDone() {
            if (!window.open(giver())) {
                end(End.CANCELLED);
                return;
            }
            if (plan.windowLine() != null) {
                window.chat(giver(), plan.windowLine());
            }
            if (headlessRecipient()) {
                clock.after(TradePacing.stepMs(rng), () -> {
                    if (window.open(giver())) {
                        window.confirm(plan.recipient());
                    }
                    waitConfirm();
                });
            } else {
                clock.after(POLL_MS, this::waitConfirm);
            }
        }

        void waitConfirm() {
            if (!window.open(giver())) {
                end(traded ? End.TRADED : End.CANCELLED);
                return;
            }
            if (!window.partnerLocked(giver())) {
                waitedMs += POLL_MS;
                if (waitedMs >= CONFIRM_TIMEOUT_MS) {
                    window.cancel(giver());
                    end(End.TIMED_OUT);
                    return;
                }
                clock.after(POLL_MS, this::waitConfirm);
                return;
            }
            clock.after(TradePacing.confirmMs(rng), () -> {
                if (window.open(giver())) {
                    window.confirm(giver());
                }
                clock.after(POLL_MS, this::finish);
            });
        }

        void finish() {
            if (traded) {
                end(End.TRADED);
            } else if (!window.open(giver())) {
                end(End.CANCELLED);
            } else {
                waitedMs += POLL_MS;
                if (waitedMs >= CONFIRM_TIMEOUT_MS) {
                    window.cancel(giver());
                    end(End.TIMED_OUT);
                    return;
                }
                clock.after(POLL_MS, this::finish); // both sides pressed Trade: the window closes on the next beat
            }
        }

        void end(End how) {
            if (how == End.CANCELLED && window.open(giver())) {
                window.cancel(giver());
            }
            busy.remove(giver().getId());
            log("trade with " + plan.recipient().getName() + " ended: " + how);
            if (plan.say() != null) {
                String line = switch (how) {
                    case TRADED -> null;
                    case DECLINED -> "oh ok, nvm then";
                    case TIMED_OUT -> "guess you're busy, nvm";
                    case CANCELLED -> "ok, cancelled";
                    case COULD_NOT_START -> "can't open a trade right now, try again in a bit";
                };
                if (line != null) {
                    plan.say().accept(line);
                }
            }
            if (plan.after() != null) {
                try {
                    plan.after().accept(how);
                } catch (RuntimeException ignored) {
                    // the after-step (re-equip, a follow-up offer) must not break the next handover
                }
            }
        }

        void log(String what) {
            Character owner = giver().isOwnedAlt() ? giver().getOwnedAltOwner() : null;
            if (owner != null && QaLog.on(QaLog.Topic.OWNED_ALT)) {
                QaLog.owner(QaLog.Topic.OWNED_ALT, owner.getId(), giver().getName() + " " + what);
            }
        }
    }
}
