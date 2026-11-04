package soloMapling.ArtificialPlayer.BotSocialSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama;
import soloMapling.ArtificialPlayer.BotGearSystem.GearUpgrades;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.Economy.PendingGrants;
import soloMapling.qa.QaLog;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.ToLongFunction;

/**
 * 1.6.4 ticket 271 Stage 4: bot-to-bot gifts - one bot hands another something it already owns, or simply says
 * "thanks, let's hunt later". Nothing is minted (no mesos, no items out of thin air), nothing is bought and no price
 * is agreed, so this is not a trade and not an economy change: the item leaves one bot's bag and lands in the
 * other's, and the rest is affinity.
 *
 * <p>What may be given, in the order {@link #choose} weighs them:
 * <ol>
 *   <li>an <b>equip</b> from the giver's spare tab that the receiver's own gear optimizer would actually put on
 *       ({@link GearUpgrades#scorer()} - the same answer {@code BotGear} dresses bots by, so job, level, stat, fame,
 *       gender, cash, omitted and rebirth-locked items are all the item data's call, never a list here; "works for
 *       every job" is simply an item whose req-job data is 0). Never a piece the giver would wear itself.</li>
 *   <li><b>recovery potions</b> the receiver is running low on (at most what brings it back to the low line, and
 *       never below what the giver keeps for itself). Potions come from {@link BotVitals#potionStacks}, so scrolls,
 *       cash and quest items can never be picked; neither can a bot's last stack.</li>
 *   <li>a <b>gesture</b>: no item at all, just the affinity (and the memory) of a favour, for a pair already warm
 *       enough to mean it.</li>
 * </ol>
 *
 * <p>Switches: on unless {@code -Dslums.botgifts=false}. Limits are one object read from JVM properties
 * ({@link #limits()}): per-giver cooldown, per-pair cooldown, per-receiver daily cap, world-wide hourly cap and a
 * vendor-value cap per gift.
 *
 * <p>Not wired into the talk planner or the brain (a later stage calls {@link #choose} and {@link #give}); the
 * population director's "free for something else" gates it needs are re-applied here on every gift.
 */
public final class BotBotGift {
    private static final Logger log = LoggerFactory.getLogger(BotBotGift.class);

    /** What a plan gives: an equip, potions, or a gesture with nothing in the bags. */
    public enum Kind {
        EQUIP, USABLE, GESTURE
    }

    /**
     * One gift: the two bots, the stack it comes from (null for a gesture), how much of it moves, and why this was
     * picked. The stack is the giver's own live item, re-checked when {@link #give} runs.
     */
    public record Plan(Character giver, Character receiver, Kind kind, Item item, int quantity, String why) {
        public boolean itemGift() {
            return kind != Kind.GESTURE;
        }

        public int itemId() {
            return item == null ? 0 : item.getItemId();
        }

        public String describe() {
            if (!itemGift()) {
                return "a gesture";
            }
            String name;
            try {
                name = ItemInformationProvider.getInstance().getName(itemId());
            } catch (RuntimeException | LinkageError noData) {
                name = null;
            }
            return (name == null ? "#" + itemId() : name) + " x" + quantity;
        }
    }

    /** Whether a {@link #give} happened, and why not when it did not. */
    public record Result(boolean ok, String why) {
        static Result no(String why) {
            return new Result(false, why);
        }

        static final Result YES = new Result(true, "given");
    }

    /**
     * Every number a gift is bounded by, in one place: how often one bot may give, how often one pair, how many
     * gifts one receiver may get per day, how many happen world-wide per hour, and the most one gift may be worth at
     * NPC prices.
     */
    public record Limits(long giverCooldownMs, long pairCooldownMs, int receiverPerDay, int perHour, long valueCap) {
    }

    /**
     * What the chooser reads of the bots' minds, their bond and their bags. Production answers through
     * {@link #live()}; a test passes its own table.
     */
    public interface MindsAndBonds {
        /** {@code botId}'s feeling about {@code otherId}, -100..100; 0 when they have no bond. */
        double bond(int botId, int otherId);

        /** The recovery stacks of one kind a bot holds, as the bots drink them (HP when {@code forHp}). */
        List<Item> potions(Character bot, boolean forHp);

        /** How many potions of that kind a bot holds. */
        int potionCount(Character bot, boolean forHp);
    }

    /** The live minds: the affinity chart and the supply code's own idea of a bot's potions. */
    private static final MindsAndBonds LIVE = new MindsAndBonds() {
        @Override
        public double bond(int botId, int otherId) {
            return BotBonds.score(botId, otherId);
        }

        @Override
        public List<Item> potions(Character bot, boolean forHp) {
            return BotVitals.potionStacks(bot, forHp);
        }

        @Override
        public int potionCount(Character bot, boolean forHp) {
            return BotVitals.potionCount(bot, forHp);
        }
    };

    /** How close the two bots have to be (a screen or so); they never walk to each other here. */
    static final int RANGE = 300;
    /** How close a real player has to be for the giver to say its line out loud. */
    static final int BUBBLE_RANGE = 500;
    /** The supply code's "running low" line for potions ({@code SupplyNeeds.LOW_POTIONS}). */
    static final int LOW_POTIONS = 20;
    /** What a giver never goes below when it shares potions: enough for its own trip. */
    static final int GIVER_KEEPS = 20;
    /** A gift of one or two potions is not a gift. */
    static final int MIN_POTION_GIFT = 5;
    /** A gesture only goes to a pair whose bond already means something. */
    static final double GESTURE_BOND = 10;

    private static final String[] EQUIP_LINES = {
            "here, take this - you'll get more use out of it than me",
            "saw this spare in my bag, thought of you",
            "try this on, it should fit you better than me",
    };
    private static final String[] POTION_LINES = {
            "you're low on pots, right? take some of mine",
            "here, I brought extra - save yourself the trip",
            "take these, I've got plenty",
    };
    private static final String[] GESTURE_LINES = {
            "thanks for earlier, let's hunt together again soon",
            "good run today - same time tomorrow?",
            "you're good company, you know that?",
    };

    // ── limits bookkeeping (rare writes, guarded by the class lock) ──
    private static final Map<Integer, Long> lastGiver = new HashMap<>();
    private static final Map<Long, Long> lastPair = new HashMap<>();
    private static final Map<Integer, Deque<Long>> receiverLog = new HashMap<>();
    private static final Deque<Long> hourly = new ArrayDeque<>();

    /**
     * Tests: a fake bond/potion source (null = the live one); every cooldown and cap starts empty and values go back
     * to NPC prices. The flag and the limit properties are left alone.
     */
    public static void useForTest(MindsAndBonds test) {
        minds = test;
        valueOverride = null;
        questItems = BotBotGift::questItemLive;
        synchronized (BotBotGift.class) {
            lastGiver.clear();
            lastPair.clear();
            receiverLog.clear();
            hourly.clear();
        }
    }

    private static volatile MindsAndBonds minds;

    private BotBotGift() {
    }

    // ── switches and limits ──

    /** Gifts are on unless {@code -Dslums.botgifts=false}. */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("slums.botgifts"));
    }

    /** The limits in force: the built-in modest defaults, each overridable by one JVM property. */
    public static Limits limits() {
        return new Limits(
                minutes("slums.botgifts.giver.cooldownMin", 6 * 60),
                minutes("slums.botgifts.pair.cooldownMin", 24 * 60),
                (int) Math.max(0, Math.min(50, Integer.getInteger("slums.botgifts.receiver.perDay", 3))),
                (int) Math.max(0, Math.min(200, Integer.getInteger("slums.botgifts.perHour", 15))),
                Math.max(0, Long.getLong("slums.botgifts.valueCap", 100_000L)));
    }

    private static long minutes(String key, int fallback) {
        return 60_000L * Math.max(0, Integer.getInteger(key, fallback));
    }

    // ── choosing ──

    /**
     * The gift {@code giver} would make {@code receiver} now, if any: the equip that helps it most, else the potions
     * it is short of, else a gesture between a pair that is already close. Empty when the giver may not give now
     * (switched off, limits reached, not the two free bots in range this needs) or has nothing worth giving.
     */
    public static Optional<Plan> choose(Character giver, Character receiver, MindsAndBonds mindsAndBonds,
                                        long now) {
        MindsAndBonds m = mindsAndBonds != null ? mindsAndBonds : live();
        String refusal = refusal(giver, receiver, now);
        if (refusal != null) {
            return Optional.empty();
        }
        Limits limits = limits();
        Plan plan = equip(giver, receiver, limits);
        if (plan == null) {
            plan = potions(giver, receiver, m, limits);
        }
        if (plan == null && m.bond(giver.getId(), receiver.getId()) >= GESTURE_BOND) {
            plan = new Plan(giver, receiver, Kind.GESTURE, null, 0, "a pair that is close enough for a kind word");
        }
        return Optional.ofNullable(plan);
    }

    /** The live minds seam (the affinity chart and the supply code). */
    public static MindsAndBonds live() {
        MindsAndBonds m = minds;
        return m != null ? m : LIVE;
    }

    /*
     * An equip from the giver's spare tab that helps the receiver and that the giver would not wear itself. The
     * optimizer's own scorer decides "could wear it" and "clearly better than what it has"; the trade rules decide
     * what may leave a bag at all (no cash, no untradeable); the quest check is kept explicit here.
     */
    private static Plan equip(Character giver, Character receiver, Limits limits) {
        Plan best = null;
        double bestGain = 0;
        for (Equip piece : GearUpgrades.spares(giver)) {
            if (questItems.test(piece.getItemId())) {
                continue;
            }
            if (worth(piece, 1) > limits.valueCap()) {
                continue;
            }
            if (gain(giver, piece) > 0) {
                continue; // the giver would wear it: its own upgrade, not a spare
            }
            double gain = gain(receiver, piece);
            if (gain > bestGain && BotHoldings.canHold(receiver, piece)) {
                bestGain = gain;
                best = new Plan(giver, receiver, Kind.EQUIP, piece, 1,
                        "an equip the receiver would wear (gain " + Math.round(gain * 1000) / 1000.0 + ")");
            }
        }
        return best;
    }

    private static double gain(Character who, Equip piece) {
        try {
            return GearUpgrades.scorer().gain(who, piece);
        } catch (RuntimeException | LinkageError noData) {
            return 0;
        }
    }

    private static volatile IntPredicate questItems = BotBotGift::questItemLive;

    /** Tests: which item ids are quest items (null = the item data's own answer). */
    static void useQuestItemsForTest(IntPredicate test) {
        questItems = test != null ? test : BotBotGift::questItemLive;
    }

    private static boolean questItemLive(int itemId) {
        try {
            return ItemInformationProvider.getInstance().isQuestItem(itemId);
        } catch (RuntimeException | LinkageError noData) {
            return false;
        }
    }

    /*
     * Potions for a receiver that is low, from a giver that keeps a trip's worth. Only recovery stacks are ever
     * listed (BotVitals' own idea of a potion), so no scroll, cash item or quest item can be picked, and the amount
     * is capped both by what the receiver still needs and by what the giver can spare.
     */
    private static Plan potions(Character giver, Character receiver, MindsAndBonds m, Limits limits) {
        Plan hp = potionGift(giver, receiver, m, limits, true);
        if (hp != null) {
            return hp;
        }
        // MP potions only for a receiver that already drinks them: a bot that holds none does not want any.
        if (m.potionCount(receiver, false) > 0) {
            return potionGift(giver, receiver, m, limits, false);
        }
        return null;
    }

    private static Plan potionGift(Character giver, Character receiver, MindsAndBonds m, Limits limits,
                                   boolean forHp) {
        int want = LOW_POTIONS - m.potionCount(receiver, forHp);
        int spare = m.potionCount(giver, forHp) - GIVER_KEEPS;
        int amount = Math.min(want, spare);
        if (amount < MIN_POTION_GIFT) {
            return null;
        }
        for (Item stack : m.potions(giver, forHp)) {
            int quantity = Math.min(amount, stack.getQuantity());
            if (quantity < MIN_POTION_GIFT) {
                continue;
            }
            if (worth(stack, quantity) > limits.valueCap()) {
                continue;
            }
            if (!BotHoldings.canHold(receiver, lotOf(stack, quantity))) {
                continue;
            }
            return new Plan(giver, receiver, Kind.USABLE, stack, quantity,
                    (forHp ? "HP" : "MP") + " potions the receiver is low on");
        }
        return null;
    }

    // ── giving ──

    /**
     * Carries {@code plan} out: the item leaves the giver and lands in the receiver, or neither bag changes at all.
     * Both bags need room; a failed step puts back what the earlier one took. A gesture changes no bag, only the
     * bond and the receiver's memory. The cooldown and cap bookkeeping are written only after a gift happened.
     */
    public static Result give(Plan plan) {
        if (!enabled()) {
            return Result.no("gifts are switched off");
        }
        if (plan == null) {
            return Result.no("no gift to give");
        }
        Character giver = plan.giver();
        Character receiver = plan.receiver();
        long now = SocialSim.now();
        String refusal = refusal(giver, receiver, now);
        if (refusal != null) {
            return Result.no(refusal);
        }
        Limits limits = limits();
        if (plan.itemGift()) {
            String moved = move(plan, limits);
            if (moved != null) {
                return Result.no(moved);
            }
        }
        remember(giver.getId(), receiver.getId(), now);
        record(plan, now);
        log.info("[BotGift] {} ({}) -> {} ({}): {} ({} on map {})", nameOf(giver), giver.getId(), nameOf(receiver),
                receiver.getId(), plan.describe(), plan.why(), giver.getMapId());
        qa(plan, now);
        maybeSay(plan, now);
        return Result.YES;
    }

    /* The transfer itself, under the holdings locks the trade path uses. Null when it went through. */
    private static String move(Plan plan, Limits limits) {
        Character giver = plan.giver();
        Character receiver = plan.receiver();
        Item source = plan.item();
        if (source == null || plan.quantity() <= 0 || plan.quantity() > source.getQuantity()) {
            return "the gift is no longer the size it was";
        }
        if (worth(source, plan.quantity()) > limits.valueCap()) {
            return "the gift is worth more than a gift may be";
        }
        Item gift = lotOf(source, plan.quantity());
        if (!BotHoldings.canHold(receiver, gift)) {
            return "no room in the receiver's bag";
        }
        boolean whole = plan.quantity() >= source.getQuantity();
        if (whole) {
            if (!BotHoldings.destroyHeldItem(giver, source)) {
                return "the gift left the giver's bag";
            }
        } else {
            int taken = BotHoldings.consumeHeld(giver, source, plan.quantity());
            if (taken != plan.quantity()) {
                if (taken > 0) {
                    giveBack(giver, source, taken, "bot gift undone");
                }
                return "the gift left the giver's bag";
            }
        }
        if (!BotHoldings.grantItem(receiver, gift)) {
            giveBack(giver, source, plan.quantity(), "bot gift undone");
            return "no room in the receiver's bag";
        }
        return null;
    }

    private static void giveBack(Character giver, Item source, int quantity, String why) {
        Item back = lotOf(source, quantity);
        if (!BotHoldings.grantItem(giver, back)) {
            PendingGrants.giveBack(giver, back, why);
        }
    }

    /* A copy of this stack, one lot of it: an equip keeps its own stats and upgrades, a use item its quantity. */
    private static Item lotOf(Item source, int quantity) {
        Item lot = source.copy();
        lot.setQuantity((short) quantity);
        return lot;
    }

    /*
     * The affinity a gift leaves: the receiver's side only, the precedent BotBonds.onTrade sets for a one-sided
     * favour (the giver's feeling is not moved by what it hands over). The event's own half-hour cooldown is ignored
     * because this gift already passed the per-pair one, and a trade a few minutes earlier must not swallow it.
     */
    private static void record(Plan plan, long now) {
        try {
            BotBonds.record(plan.receiver().getId(), plan.giver().getId(), BondEvent.GIFT, now, true);
        } catch (RuntimeException e) {
            log.warn("[BotGift] affinity not recorded for {} -> {}: {}", plan.giver().getId(),
                    plan.receiver().getId(), e.toString());
        }
        try {
            LifeMemory memory = LifeMemoryStore.of(plan.receiver().getId());
            if (memory != null) {
                memory.push(SocialProfile.Push.GIFTED);
                memory.noteMet(plan.giver().getId(), nameOf(plan.giver()),
                        "gave me " + plan.describe(), now);
            }
        } catch (RuntimeException e) {
            // the gift happened; a memory that cannot be loaded must not undo it
            log.warn("[BotGift] memory not written for {}: {}", plan.receiver().getId(), e.toString());
        }
    }

    private static String nameOf(Character c) {
        String name = c.getName();
        return name == null ? "#" + c.getId() : name;
    }

    private static void qa(Plan plan, long now) {
        if (!QaLog.on(QaLog.Topic.TRADE)) {
            return;
        }
        QaLog.map(QaLog.Topic.TRADE, plan.giver().getMapId(),
                "BOT GIFT " + nameOf(plan.giver()) + " -> " + nameOf(plan.receiver()) + " " + plan.describe()
                        + " (" + plan.why() + ") at " + now);
    }

    /* The line, only when a real person is close enough to see the bubble (PG-13, never about being a bot). */
    private static void maybeSay(Plan plan, long now) {
        Character giver = plan.giver();
        try {
            if (!seenByPlayer(giver)) {
                return;
            }
            String[] lines = plan.kind() == Kind.EQUIP ? EQUIP_LINES
                    : plan.kind() == Kind.USABLE ? POTION_LINES : GESTURE_LINES;
            Random rng = SocialSim.random(giver.getId(), "gift.line");
            SocialCommands.BotChatbubble(giver, lines[rng.nextInt(lines.length)]);
        } catch (RuntimeException e) {
            log.debug("[BotGift] no line from {}: {}", giver.getId(), e.toString());
        }
    }

    private static boolean seenByPlayer(Character giver) {
        if (giver.getMap() == null) {
            return false;
        }
        for (Character who : giver.getMap().getAllPlayers()) {
            if (BotHelpers.isVisibleRealPlayer(who) && giver.getPosition() != null
                    && who.getPosition() != null
                    && giver.getPosition().distanceSq(who.getPosition()) <= (double) BUBBLE_RANGE * BUBBLE_RANGE) {
                return true;
            }
        }
        return false;
    }

    // ── the gates ──

    /** Why these two may not exchange a gift now (null: they may), limits included. */
    static String refusal(Character giver, Character receiver, long now) {
        if (!enabled()) {
            return "gifts are switched off";
        }
        if (giver == null || receiver == null) {
            return "one of them is gone";
        }
        if (giver.getId() == receiver.getId()) {
            return "a bot cannot gift itself";
        }
        if (!BotHelpers.isRosterBot(giver) || !BotHelpers.isRosterBot(receiver)) {
            return "gifts are between two roster bots";
        }
        if (giver.getMap() == null || giver.getMap() != receiver.getMap()) {
            return "not on the same map";
        }
        if (!free(giver) || !free(receiver)) {
            return "one of them is busy";
        }
        if (giver.getPosition() == null || receiver.getPosition() == null
                || giver.getPosition().distanceSq(receiver.getPosition()) > (double) RANGE * RANGE) {
            return "out of range";
        }
        Limits limits = limits();
        long byGiver;
        long byPair;
        int byReceiver;
        int thisHour;
        synchronized (BotBotGift.class) {
            byGiver = lastGiver.getOrDefault(giver.getId(), Long.MIN_VALUE);
            byPair = lastPair.getOrDefault(pairKey(giver.getId(), receiver.getId()), Long.MIN_VALUE);
            byReceiver = recent(receiverLog.get(receiver.getId()), now, 24 * 60 * 60_000L);
            thisHour = recent(hourly, now, 60 * 60_000L);
        }
        if (byGiver != Long.MIN_VALUE && now - byGiver < limits.giverCooldownMs()) {
            return "the giver gave recently";
        }
        if (byPair != Long.MIN_VALUE && now - byPair < limits.pairCooldownMs()) {
            return "this pair gave recently";
        }
        if (byReceiver >= limits.receiverPerDay()) {
            return "the receiver has had its gifts today";
        }
        if (thisHour >= limits.perHour()) {
            return "the world has given enough this hour";
        }
        return null;
    }

    /* The population director's "free for something else" set, on the Character: online, not trading, not shopping,
     * not in a scene, not dead. A party or a boss wait is not a reason to refuse a gift. */
    private static boolean free(Character c) {
        return c.getClient() != null
                && c.getTrade() == null && c.getPlayerShop() == null && c.getShop() == null && c.getMiniGame() == null
                && !BotDeaths.isDead(c.getId())
                && !BotDrama.inScene(c.getId())
                && !BotWeddings.inCeremony(c.getId());
    }

    private static int recent(Deque<Long> log, long now, long window) {
        if (log == null) {
            return 0;
        }
        while (!log.isEmpty() && now - log.peekFirst() >= window) {
            log.pollFirst();
        }
        return log.size();
    }

    private static void remember(int giverId, int receiverId, long now) {
        synchronized (BotBotGift.class) {
            lastGiver.put(giverId, now);
            lastPair.put(pairKey(giverId, receiverId), now);
            receiverLog.computeIfAbsent(receiverId, k -> new ArrayDeque<>()).addLast(now);
            hourly.addLast(now);
            recent(hourly, now, 60 * 60_000L);
        }
    }

    private static long pairKey(int giverId, int receiverId) {
        return ((long) giverId << 32) | (receiverId & 0xffffffffL);
    }

    // ── what a gift is worth ──

    /** The NPC price of {@code quantity} of this stack (0 when the item data does not price it). */
    static long worth(Item stack, int quantity) {
        ToLongFunction<Item> override = valueOverride;
        if (override != null) {
            Item lot = lotOf(stack, quantity);
            return override.applyAsLong(lot);
        }
        try {
            return ItemInformationProvider.getInstance().getPrice(stack.getItemId(), quantity);
        } catch (RuntimeException | LinkageError noData) {
            return 0;
        }
    }

    private static volatile ToLongFunction<Item> valueOverride;

    /** Tests: value a lot this way instead of at NPC prices (null = NPC prices). */
    static void useValueForTest(ToLongFunction<Item> test) {
        valueOverride = test;
    }
}
