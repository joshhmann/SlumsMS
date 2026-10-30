package soloMapling.ArtificialPlayer.BotScrollSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.ItemId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Choice;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.ScrollFacts;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Target;
import soloMapling.ArtificialPlayer.BotSupplySystem.ShopCatalog;
import soloMapling.FreeMarket.EquipValuation;
import soloMapling.itemPool.ItemDatabase;
import soloMapling.qa.QaLog;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;

/**
 * Ticket 11: bots really scroll their own equipment.
 *
 * <p>End to end, for a grinding bot standing in town (its town stop and store browse call {@link #townTick}):
 * <ol>
 *   <li><b>Collects</b> what its FM listings earned ({@link BotConsignment}).</li>
 *   <li><b>Buys</b> scrolls that fit its gear at an NPC shop on the map it stands on (v83 weapon stores sell the
 *   100% scrolls: Ellinia, Kerning, El Nath, Ludibrium, Leafre), and <b>posts wants</b> for the scroll it would pick
 *   from the FM, which the FM shopper bots buy for it ({@link ScrollWants}). Both within {@link ScrollBudget}.</li>
 *   <li><b>Scrolls</b> one equip every few seconds with the server's own roll
 *   ({@code ItemInformationProvider.scrollEquipWithId}: success, fail, curse; a white scroll saves the slot), worn
 *   gear first, then the gear in its bag; the scroll by its {@link Appetite}; it stops when the slots run out.
 *   A cursed item is gone ({@link BotHoldings#destroyHeldItem}); a worn item's new stats are re-derived
 *   ({@code equipChanged}). Players on the map see the real scroll effect.</li>
 *   <li><b>Market:</b> a finished equip the bot does not put on (BotGear) is listed in a bot FM shop at its
 *   EquipValuation price; a complete failure with no value is tossed.</li>
 *   <li><b>Megaphones</b> only for a real, notable result ({@link ScrollRules#megaphoneFor}), through ticket 47's
 *   world-event megaphones (the server-wide gate, a cooldown, the content pack), and at most one per bot per
 *   {@link #MEGAPHONE_COOLDOWN_MS}.</li>
 * </ol>
 */
public final class BotScrolling {

    private static final Logger log = LoggerFactory.getLogger(BotScrolling.class);

    public enum Outcome { SUCCESS, FAIL, CURSE }

    static final long MEGAPHONE_COOLDOWN_MS = 30 * 60_000L;
    static final long WANTS_EVERY_MS = 10 * 60_000L;
    static final long STEP_MIN_MS = 4_000, STEP_MAX_MS = 8_000, IDLE_MS = 60_000;
    static final int MIN_NPC_PRICE = 1_000;   // GM shops list scrolls at 1 meso: never a bot's shop
    static final int MAX_NPC_BUY_PER_SCROLL = 3;

    /** One roll as it happened. */
    public record RollReport(String bot, int equipId, boolean worn, int scrollId, int success, int curse,
                             boolean white, Outcome outcome, String before, String after) {
        public String line() {
            return bot + " used " + scrollId + " (" + success + "%" + (curse > 0 ? ", " + curse + "% boom on fail"
                    : "") + (white ? ", white scroll" : "") + ") on " + (worn ? "worn " : "") + equipId + ": "
                    + outcome + " | before " + before + " | after " + after;
        }
    }

    /** What a roll needs to know about items; the item data on the server, a stub in tests. */
    interface Facts {
        ScrollFacts scroll(int scrollId);

        List<Integer> reqs(int scrollId);

        /** Every plain scroll id of the item data that could go on this equip (by category). */
        List<Integer> catalogFor(int equipItemId);

        /** The item's upgrade slots when clean, plus hammers. */
        int totalSlots(Equip equip);

        boolean cash(int itemId);

        String name(int itemId);

        /** The scroll's normal FM value (item pool), or null. */
        Integer marketValue(int itemId);

        /*
         * 162: what a scroll trades for now: its learned market price (ScrollMarketPrice: real sales blended with
         * the item pool's price), or the item pool's price when -Dslums.scroll.marketPrice is off or nothing sold.
         */
        default Integer scrollPrice(int scrollId) {
            return soloMapling.Economy.ScrollMarketPrice.price(scrollId, marketValue(scrollId));
        }

        /** The equip's value (EquipValuation), or null when it cannot be traded. */
        Integer equipValue(Equip equip);

        /*
         * Ticket 162 part 5: how far the equip is above its clean stats, on the scrolls' gainWeight scale (attack 10,
         * main stats 3, the rest 1). 0 when unknown.
         */
        default double statScore(Equip equip) {
            return 0;
        }
    }

    interface Roller {
        /** The server's roll: the same equip (changed) or null when the curse destroyed it. */
        Item roll(Equip equip, int scrollId, boolean whiteScroll);
    }

    interface Megaphone {
        boolean offer(Character bot, String node, Map<String, String> tokens, String key);
    }

    static final Facts LIVE = new LiveFacts();
    private static volatile Facts facts = LIVE;
    private static volatile Roller roller = (equip, scrollId, white) ->
            ItemInformationProvider.getInstance().scrollEquipWithId(equip, scrollId, white, 0, false);
    private static volatile Megaphone megaphone = (bot, node, tokens, key) ->
            WorldEventMegaphones.getInstance().offerEvent(bot, node, tokens, key);
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** Per-bot pacing, in memory. */
    private static final class State {
        long nextStepAt;
        long wantsAt = Long.MIN_VALUE / 2;
        long megaphoneAt = Long.MIN_VALUE / 2;
        int npcMap = -1;
    }

    private static final Map<Integer, State> states = new ConcurrentHashMap<>();

    private BotScrolling() {
    }

    // ------------------------------------------------------------------ choosing

    /** The next roll a bot would make: which equip, which scroll. */
    public record Plan(Equip equip, Choice choice, ScrollFacts scroll) {
    }

    /** The equips a bot may scroll: worn real gear (weapon first), then the equips in its bag, slots left only. */
    static List<Equip> targets(Character bot) {
        List<Equip> out = new ArrayList<>();
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        Inventory bag = bot.getInventory(InventoryType.EQUIP);
        List<Equip> worn = new ArrayList<>();
        body.lockInventory();
        try {
            for (Item it : body.list()) {
                if (it instanceof Equip e && it.getPosition() < 0 && it.getPosition() > -100 && candidate(e)) {
                    worn.add(e);
                }
            }
        } finally {
            body.unlockInventory();
        }
        worn.sort((a, b) -> Boolean.compare(b.getPosition() == -11, a.getPosition() == -11));
        out.addAll(worn);
        bag.lockInventory();
        try {
            for (Item it : bag.list()) {
                if (it instanceof Equip e && candidate(e)) {
                    out.add(e);
                }
            }
        } finally {
            bag.unlockInventory();
        }
        return out;
    }

    private static boolean candidate(Equip e) {
        return e.getUpgradeSlots() > 0 && ScrollRules.scrollableEquip(e.getItemId()) && !facts.cash(e.getItemId());
    }

    /** The scrolls the bot holds that fit this equip (one entry per scroll id). */
    static List<ScrollFacts> fittingHeld(Character bot, Equip equip) {
        Map<Integer, ScrollFacts> out = new LinkedHashMap<>();
        Inventory use = bot.getInventory(InventoryType.USE);
        List<Integer> ids = new ArrayList<>();
        use.lockInventory();
        try {
            for (Item it : use.list()) {
                if (it.getQuantity() > 0 && ScrollRules.isPlainScroll(it.getItemId())) {
                    ids.add(it.getItemId());
                }
            }
        } finally {
            use.unlockInventory();
        }
        for (int id : ids) {
            if (out.containsKey(id)) {
                continue;
            }
            ScrollFacts f = facts.scroll(id);
            if (f != null && ScrollRules.fits(id, equip.getItemId(), equip.getUpgradeSlots(), facts.reqs(id))) {
                out.put(id, f);
            }
        }
        return new ArrayList<>(out.values());
    }

    static boolean holdsWhite(Character bot) {
        return bot.getInventory(InventoryType.USE).countById(ItemId.WHITE_SCROLL) > 0;
    }

    /**
     * 1.5.0 rc3: once the world's bots made {@code scrolling.godliesPerDay} (5) godly items today, they stick to 60%+
     * non-dark scrolls for the rest of the day.
     */
    static boolean longShotsAllowed() {
        try {
            return soloMapling.Economy.EconomyMeters.get(soloMapling.Economy.EconomyMeters.GODLIES)
                    < soloMapling.Economy.EconomyConfig.getLong("scrolling.godliesPerDay", 5);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /* Ticket 162 part 5: -Dslums.scroll.ev=true picks the scroll by nutnnut's EV planner (ScrollPlanner). */
    public static boolean evEnabled() {
        return Boolean.getBoolean("slums.scroll.ev");
    }

    /** What the bot scrolls next, or null when it has nothing it would use on anything. */
    public static Plan plan(Character bot, Appetite appetite, Random rng) {
        if (evEnabled()) {
            Plan ev = evPlan(bot, appetite);
            // 162 runfix: a gambler gambles. When the EV planner sees no play worth its meso, the gambler persona
            // still takes its appetite's pick (the biggest stats, a dark scroll now and then), as before the planner.
            return ev != null || appetite != Appetite.GAMBLER ? ev : rulesPlan(bot, appetite, rng);
        }
        return rulesPlan(bot, appetite, rng);
    }

    /** The appetite rule's pick (ScrollRules.choose), the pre-EV planner. */
    private static Plan rulesPlan(Character bot, Appetite appetite, Random rng) {
        boolean white = holdsWhite(bot);
        for (Equip e : targets(bot)) {
            List<ScrollFacts> fitting = fittingHeld(bot, e);
            Choice c = ScrollRules.choose(appetite, new Target(e.getItemId(), e.getUpgradeSlots(),
                    e.getPosition() < 0), fitting, white, rng, longShotsAllowed());
            if (c != null) {
                for (ScrollFacts f : fitting) {
                    if (f.scrollId() == c.scrollId()) {
                        return new Plan(e, c, f);
                    }
                }
            }
        }
        return null;
    }

    /*
     * Ticket 162 part 5: the EV planner's pick. Each equip with a slot left is a candidate; its options are the held
     * scrolls that fit it, after our own rules: a careful bot never uses a dark scroll, nobody but a gambler puts one
     * on worn gear, and once the world's godlies for the day are made only 60%+ non-dark scrolls are left (as
     * ScrollRules.choose). The value curve is the item's reproduction cost (ScrollValuer) over every plain scroll that
     * fits that equip at its FM price, from its own FM value (else EquipValuation) as the clean base; the planner
     * proposes the single best scroll now, or nothing when no play beats stopping by 20% of the scroll's value.
     * A white scroll goes with a long shot as ScrollRules decides it.
     */
    static Plan evPlan(Character bot, Appetite appetite) {
        boolean allowLong = longShotsAllowed();
        boolean whiteOk = holdsWhite(bot)
                && soloMapling.Economy.EconomyConfig.getBool("scrolling.whiteOnLongShots", false);
        List<ScrollPlanner.EquipCandidate> candidates = new ArrayList<>();
        Map<ScrollPlanner.EquipCandidate, Equip> equipOf = new java.util.IdentityHashMap<>();
        Map<Integer, ScrollFacts> factsOf = new HashMap<>();
        for (Equip e : targets(bot)) {
            boolean worn = e.getPosition() < 0;
            List<ScrollPlanner.ScrollOption> options = new ArrayList<>();
            for (ScrollFacts f : fittingHeld(bot, e)) {
                if (f.success() <= 0 || (!allowLong && (f.dark() || f.longShot()))
                        || (f.dark() && (appetite == Appetite.CAREFUL || (worn && appetite != Appetite.GAMBLER)))) {
                    continue;
                }
                // 162 runfix: the careful persona plays it safe under EV too: no 10%/30% long shot, except under a
                // white scroll on gear it does not wear (as ScrollRules.choose).
                if (appetite == Appetite.CAREFUL && f.longShot() && !(whiteOk && !worn)) {
                    continue;
                }
                Integer price = facts.scrollPrice(f.scrollId());   // what it would fetch on the market now
                options.add(new ScrollPlanner.ScrollOption(f.scrollId(), facts.name(f.scrollId()), f.success() / 100.0,
                        f.dark() ? f.curse() / 100.0 : 0, f.gainWeight(), price == null ? 0 : price));
                factsOf.put(f.scrollId(), f);
            }
            if (options.isEmpty()) {
                continue;
            }
            java.util.function.DoubleUnaryOperator curve = curveFor(e);
            ScrollPlanner.EquipCandidate c = new ScrollPlanner.EquipCandidate(e.getItemId(), facts.name(e.getItemId()),
                    facts.statScore(e), e.getUpgradeSlots(), facts.totalSlots(e), false, !worn, options, curve, curve,
                    0);
            candidates.add(c);
            equipOf.put(c, e);
        }
        ScrollPlanner.ScrollPlan best = ScrollPlanner.planBest(candidates);
        if (best == null) {
            return null;
        }
        ScrollFacts f = factsOf.get(best.scroll().scrollItemId());
        boolean white = whiteOk && f.longShot() && appetite != Appetite.GAMBLER;
        return new Plan(equipOf.get(best.equip()), new Choice(f.scrollId(), white), f);
    }

    // Curves per (item, slots, base price, scroll prices): the valuer memoizes inside a curve, so a kept curve answers
    // from memory; a scroll's market price moving (ScrollMarketPrice) makes a new key, so the curve follows the market.
    private static final Map<String, java.util.function.DoubleUnaryOperator> curves = new ConcurrentHashMap<>();

    // The reproduction-cost curve of this equip's kind: every plain scroll the item data has for it at its market price.
    private static java.util.function.DoubleUnaryOperator curveFor(Equip e) {
        List<soloMapling.itemPool.ScrollValuer.ScrollSpec> specs = curveSpecs(e);
        Integer known = facts.marketValue(e.getItemId());
        StringBuilder key = new StringBuilder().append(e.getItemId()).append(':').append(facts.totalSlots(e))
                .append(':').append(known == null ? -1 : known);
        for (soloMapling.itemPool.ScrollValuer.ScrollSpec sp : specs) {
            key.append(':').append(priceBucket(sp.mesoCost()));
        }
        String k = key.toString();
        java.util.function.DoubleUnaryOperator cached = curves.get(k);
        if (cached != null) {
            return cached;
        }
        if (curves.size() > 4096) {
            curves.clear();   // prices moved on, or a very varied world: start over
        }
        Integer base = known != null ? known : facts.equipValue(e);
        java.util.function.DoubleUnaryOperator curve = soloMapling.itemPool.ScrollValuer.reproductionValue(0,
                facts.totalSlots(e), specs, base == null ? 0 : base);
        if (known != null) {
            curves.put(k, curve);   // an EquipValuation base depends on the piece itself: not shared
        }
        return curve;
    }

    // A scroll price for the curve key: two significant digits (a 1% move does not cost a new DP solve).
    static long priceBucket(double price) {
        long p = Math.max(0, Math.round(price));
        long scale = 1;
        while (p >= 100 * scale) {
            scale *= 10;
        }
        return p / scale * scale;
    }

    private static List<soloMapling.itemPool.ScrollValuer.ScrollSpec> curveSpecs(Equip e) {
        List<soloMapling.itemPool.ScrollValuer.ScrollSpec> specs = new ArrayList<>();
        for (int id : facts.catalogFor(e.getItemId())) {
            ScrollFacts f = facts.scroll(id);
            if (f != null && f.success() > 0 && !f.dark()
                    && ScrollRules.fits(id, e.getItemId(), Math.max(1, e.getUpgradeSlots()), facts.reqs(id))) {
                Integer price = facts.scrollPrice(id);
                if (price == null || price <= 0) {
                    // 162 runfix: a scroll with no market price (event, quest or unlisted scrolls) cannot be bought to
                    // make the item again. Priced at 0 it made every +stat "free" to reproduce, so the curve stayed
                    // flat at the base and the planner declined every real scroll (t-sc-1/t-sc-2 on TEST 10-30).
                    continue;
                }
                specs.add(new soloMapling.itemPool.ScrollValuer.ScrollSpec(f.success() / 100.0, f.gainWeight(),
                        price));
            }
        }
        return specs;
    }

    // ------------------------------------------------------------------ rolling

    /**
     * One real roll: the scroll (and white scroll) leave the bot's bag, the server rolls, a curse destroys the equip
     * where it is (worn or in the bag), a worn item's stats are re-derived, and the map sees the scroll effect.
     * Null when the bot no longer holds the scroll or it no longer fits.
     */
    public static RollReport rollOnce(Character bot, Plan plan) {
        return rollOnce(bot, plan, true);
    }

    /** {@code qaLog} false: the caller logs the roll itself (!qa sc-2, which notes every line it gets back). */
    static RollReport rollOnce(Character bot, Plan plan, boolean qaLog) {
        Equip equip = plan.equip();
        int scrollId = plan.choice().scrollId();
        Inventory use = bot.getInventory(InventoryType.USE);
        Item scroll = use.findById(scrollId);
        if (scroll == null || !ScrollRules.fits(scrollId, equip.getItemId(), equip.getUpgradeSlots(),
                facts.reqs(scrollId)) || !stillHeld(bot, equip)) {
            return null;
        }
        boolean worn = equip.getPosition() < 0;
        String before = stats(equip);
        int levelBefore = equip.getLevel();
        Integer valueBefore = facts.equipValue(equip);
        if (BotHoldings.consumeHeld(bot, scroll, 1) != 1) {
            return null;
        }
        boolean white = false;
        if (plan.choice().whiteScroll()) {
            Item ws = use.findById(ItemId.WHITE_SCROLL);
            white = ws != null && BotHoldings.consumeHeld(bot, ws, 1) == 1;
        }
        boolean godlyBefore = soloMapling.Economy.GearGrade.godly(equip);
        Item result = roller.roll(equip, scrollId, white);
        Outcome outcome = result == null ? Outcome.CURSE
                : ((Equip) result).getLevel() > levelBefore ? Outcome.SUCCESS : Outcome.FAIL;
        if (outcome == Outcome.CURSE) {
            BotHoldings.destroyHeldItem(bot, equip);
            soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.event(bot,
                    soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory.Event.BOOM);
            int boomed = equip.getItemId();
            soloMapling.server.MapleRootSolo.runHook("beef boom", () -> // 1.6.0 S7: friends tease, maybe a rage quit
                    soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.onBotBoom(bot, boomed, valueBefore));
        }
        if (worn && outcome != Outcome.FAIL && bot.getMap() != null) {
            bot.equipChanged();   // the boomed piece off the look, or the new stats into the totals
        }
        if (bot.getMap() != null) {
            bot.getMap().broadcastMessage(PacketCreator.getScrollEffect(bot.getId(),
                    outcome == Outcome.SUCCESS ? Equip.ScrollResult.SUCCESS
                            : outcome == Outcome.CURSE ? Equip.ScrollResult.CURSE : Equip.ScrollResult.FAIL,
                    false, white));
        }
        ScrollLedger.rolled(outcome, white, outcome == Outcome.CURSE ? valueBefore : null);
        if (outcome == Outcome.SUCCESS && !godlyBefore && soloMapling.Economy.GearGrade.godly(equip)) {
            // 1.5.0 rc3: a bot-made godly counts toward the world's daily cap (scrolling.godliesPerDay)
            soloMapling.Economy.EconomyMeters.add(soloMapling.Economy.EconomyMeters.GODLIES, 1);
        }
        RollReport report = new RollReport(bot.getName(), equip.getItemId(), worn, scrollId,
                plan.scroll().success(), plan.scroll().curse(), white, outcome, before,
                outcome == Outcome.CURSE ? "destroyed" : stats(equip));
        if (log.isInfoEnabled()) {
            log.info("[Scroll] {} (lv {}) {}", bot.getName(), bot.getLevel(), report.line());
        }
        if (qaLog && QaLog.on(QaLog.Topic.SCROLL) && bot.getMap() != null) {
            QaLog.map(QaLog.Topic.SCROLL, bot.getMapId(), report.line());
        }
        maybeMegaphone(bot, equip, outcome, levelBefore);
        return report;
    }

    private static boolean stillHeld(Character bot, Equip equip) {
        Inventory inv = bot.getInventory(equip.getPosition() < 0 ? InventoryType.EQUIPPED : InventoryType.EQUIP);
        return inv.getItem(equip.getPosition()) == equip;
    }

    static String stats(Equip e) {
        StringBuilder s = new StringBuilder();
        s.append(e.getLevel()).append(" passed, ").append(e.getUpgradeSlots()).append(" slots left");
        append(s, "watk", e.getWatk());
        append(s, "matk", e.getMatk());
        append(s, "str", e.getStr());
        append(s, "dex", e.getDex());
        append(s, "int", e.getInt());
        append(s, "luk", e.getLuk());
        append(s, "wdef", e.getWdef());
        append(s, "mdef", e.getMdef());
        append(s, "acc", e.getAcc());
        append(s, "avoid", e.getAvoid());
        append(s, "hp", e.getHp());
        append(s, "mp", e.getMp());
        append(s, "speed", e.getSpeed());
        append(s, "jump", e.getJump());
        return s.toString();
    }

    private static void append(StringBuilder s, String what, int v) {
        if (v != 0) {
            s.append(", ").append(what).append(' ').append(v);
        }
    }

    // ------------------------------------------------------------------ megaphones

    static void maybeMegaphone(Character bot, Equip equip, Outcome outcome, int levelBefore) {
        int passed = outcome == Outcome.CURSE ? levelBefore : equip.getLevel();
        String node = ScrollRules.megaphoneFor(outcome, passed, facts.totalSlots(equip), equip.getUpgradeSlots());
        if (node == null) {
            return;
        }
        if ("ScrollPerfect".equals(node) && !soloMapling.Economy.GearGrade.godly(equip)) {
            return;   // 1.5.0 rc3 (gear audit): a "perfect" of 100%/60% scrolls is no news; only a godly result is
        }
        State st = states.computeIfAbsent(bot.getId(), id -> new State());
        long now = clock.getAsLong();
        synchronized (st) {
            if (now - st.megaphoneAt < MEGAPHONE_COOLDOWN_MS) {
                return;
            }
            st.megaphoneAt = now;
        }
        Map<String, String> tokens = new HashMap<>();
        tokens.put("ITEM", shortName(facts.name(equip.getItemId())));
        tokens.put("PASSED", String.valueOf(passed));
        tokens.put("TOTAL", String.valueOf(facts.totalSlots(equip)));
        if (megaphone.offer(bot, node, tokens, "scroll:" + bot.getId())) {
            ScrollLedger.megaphones.incrementAndGet();
        }
    }

    static String shortName(String name) {
        if (name == null || name.isBlank()) {
            return "item";
        }
        return name.toLowerCase(java.util.Locale.ROOT);
    }

    // ------------------------------------------------------------------ the market loop

    /**
     * After a roll on an equip in the bag: a better piece goes on (BotGear); a finished one the bot does not wear is
     * listed in a bot FM shop at its value; a finished complete failure with no value is tossed.
     */
    static String afterRoll(Character bot, Equip equip, Outcome outcome, boolean wasWorn) {
        if (outcome == Outcome.CURSE || wasWorn) {
            return null;
        }
        if (outcome == Outcome.SUCCESS) {
            BotGear.review(bot);
            if (equip.getPosition() < 0) {
                ScrollLedger.worn.incrementAndGet();
                return "put it on";
            }
        }
        if (equip.getUpgradeSlots() > 0 || equip.getPosition() < 0) {
            return null;
        }
        Integer value = facts.equipValue(equip);
        if (value != null && value > 0) {
            String where = BotConsignment.list(bot, equip, value);
            if (where != null) {
                return "listed it for " + value + " in " + where;
            }
        }
        if (equip.getLevel() == 0 && (value == null || value <= 0) && BotHoldings.destroyHeldItem(bot, equip)) {
            ScrollLedger.dropped.incrementAndGet();
            return "tossed it";
        }
        return null;
    }

    // ------------------------------------------------------------------ buying

    /** A scroll arrived for this bot (an FM shopper filled its want): log it. */
    static void noteBought(Character bot, int scrollId, int price, String from) {
        log.info("[Scroll] {} (lv {}) bought {} for {} from {}", bot.getName(), bot.getLevel(), scrollId, price, from);
        if (QaLog.on(QaLog.Topic.SCROLL) && bot.getMap() != null) {
            QaLog.map(QaLog.Topic.SCROLL, bot.getMapId(), bot.getName() + " bought " + scrollId + " for " + price
                    + " from " + from);
        }
    }

    /** Buys fitting scrolls at the NPC shops on the bot's map; how many it bought. */
    static int buyAtNpc(Character bot, List<ShopCatalog.Site> sites) {
        List<Equip> targets = targets(bot);
        if (targets.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (ShopCatalog.Site site : sites) {
            for (ShopCatalog.Listing l : site.items()) {
                int id = l.itemId();
                if (l.price() < MIN_NPC_PRICE || !ScrollRules.isPlainScroll(id)) {
                    continue;
                }
                int need = 0;
                for (Equip e : targets) {
                    if (ScrollRules.fits(id, e.getItemId(), e.getUpgradeSlots(), facts.reqs(id))) {
                        need += e.getUpgradeSlots();
                    }
                }
                need = Math.min(need, MAX_NPC_BUY_PER_SCROLL) - bot.getInventory(InventoryType.USE).countById(id);
                for (int i = 0; i < need; i++) {
                    if (!buyOne(bot, id, l.price())) {
                        break;
                    }
                    total++;
                }
            }
        }
        return total;
    }

    private static boolean buyOne(Character bot, int scrollId, int price) {
        Item item = new Item(scrollId, (short) 0, (short) 1);
        if (!BotHoldings.canHold(bot, item)
                || !ScrollBudget.tryBuy(bot.getId(), bot.getLevel(), bot.getMeso(), price, false)) {
            return false;
        }
        if (!BotHoldings.spendMeso(bot, price)) {
            ScrollBudget.refund(bot.getId(), price, false);
            return false;
        }
        if (!BotHoldings.grantItem(bot, item)) {
            BotHoldings.grantMeso(bot, price);
            ScrollBudget.refund(bot.getId(), price, false);
            return false;
        }
        ScrollLedger.bought(ScrollLedger.Source.NPC, price);
        noteBought(bot, scrollId, price, "an NPC shop");
        return true;
    }

    /** Posts FM wants for the scroll the bot would use next on its first gear that has no fitting scroll held. */
    static int postWants(Character bot, Appetite appetite, Random rng) {
        int posted = 0;
        for (Equip e : targets(bot)) {
            if (posted >= ScrollWants.MAX_WANTS_PER_BOT) {
                break;
            }
            if (!fittingHeld(bot, e).isEmpty()) {
                continue;
            }
            List<ScrollFacts> market = new ArrayList<>();
            for (int id : facts.catalogFor(e.getItemId())) {
                ScrollFacts f = facts.scroll(id);
                Integer value = facts.marketValue(id);
                if (f != null && value != null && value > 0
                        && ScrollRules.fits(id, e.getItemId(), e.getUpgradeSlots(), facts.reqs(id))) {
                    market.add(f);
                }
            }
            Choice c = ScrollRules.choose(appetite, new Target(e.getItemId(), e.getUpgradeSlots(),
                    e.getPosition() < 0), market, false, rng, longShotsAllowed());
            if (c == null) {
                continue;
            }
            long budget = ScrollBudget.maxPrice(bot.getId(), bot.getLevel(), bot.getMeso(), false);
            Integer want = facts.scrollPrice(c.scrollId());   // 162: today's market price, not the yaml's
            long price = Math.min(want == null ? 0 : want, budget);
            if (price > 0 && ScrollWants.post(bot, c.scrollId(), price)) {
                posted++;
            }
        }
        return posted;
    }

    // ------------------------------------------------------------------ the town tick

    /**
     * A grinding bot's town stop and store browse call this every macro tick: at most one step every few seconds
     * (collect, buy, post wants, one roll). Never throws.
     */
    public static void townTick(Character bot) {
        if (bot == null || !ScrollBudget.enabled()) {
            return;
        }
        try {
            State st = states.computeIfAbsent(bot.getId(), id -> new State());
            long now = clock.getAsLong();
            synchronized (st) {
                if (now < st.nextStepAt) {
                    return;
                }
                st.nextStepAt = now + STEP_MIN_MS + ThreadLocalRandom.current().nextLong(STEP_MAX_MS - STEP_MIN_MS);
            }
            BotConsignment.collect(bot);
            Random rng = ThreadLocalRandom.current();
            Appetite appetite = Appetite.of(bot.getId());
            boolean newMap;
            boolean wantsDue;
            synchronized (st) {
                newMap = st.npcMap != bot.getMapId();
                st.npcMap = bot.getMapId();
                wantsDue = now - st.wantsAt >= WANTS_EVERY_MS;
                if (wantsDue) {
                    st.wantsAt = now;
                }
            }
            if (newMap) {
                buyAtNpc(bot, ShopCatalog.onMap(bot, bot.getMapId()));
            }
            if (wantsDue) {
                postWants(bot, appetite, rng);
            }
            Plan plan = ScrollBudget.rollsLeft(bot.getId()) > 0 ? plan(bot, appetite, rng) : null;
            if (plan == null || !ScrollBudget.tryRoll(bot.getId())) {
                synchronized (st) {
                    st.nextStepAt = now + IDLE_MS;
                }
                return;
            }
            boolean wasWorn = plan.equip().getPosition() < 0;
            RollReport report = rollOnce(bot, plan);
            if (report != null) {
                String next = afterRoll(bot, plan.equip(), report.outcome(), wasWorn);
                if (next != null) {
                    log.info("[Scroll] {} {}: {}", bot.getName(), plan.equip().getItemId(), next);
                    if (QaLog.on(QaLog.Topic.SCROLL) && bot.getMap() != null) {
                        QaLog.map(QaLog.Topic.SCROLL, bot.getMapId(), bot.getName() + " " + next);
                    }
                }
            }
        } catch (RuntimeException e) {
            log.warn("Bot scrolling failed for bot {} ({})", bot.getId(), bot.getName(), e);
        }
    }

    /** One roll right now, outside town and outside the daily roll count (GM command, !qa sc-2). */
    public static List<String> stepNow(Character bot, Appetite appetite) {
        List<String> out = new ArrayList<>();
        Plan plan = plan(bot, appetite, ThreadLocalRandom.current());
        if (plan == null) {
            out.add(bot.getName() + " has nothing it would scroll (" + appetite + "): no slots left or no fitting"
                    + " scroll it would use");
            return out;
        }
        boolean wasWorn = plan.equip().getPosition() < 0;
        RollReport report = rollOnce(bot, plan, false);
        if (report == null) {
            out.add(bot.getName() + " could not use " + plan.choice().scrollId());
            return out;
        }
        out.add(report.line());
        String next = afterRoll(bot, plan.equip(), report.outcome(), wasWorn);
        if (next != null) {
            out.add(bot.getName() + " " + next);
        }
        return out;
    }

    /** One line about a bot for the GM: appetite, budget, what it would scroll next. */
    public static String describe(Character bot) {
        Appetite appetite = Appetite.of(bot.getId());
        Plan plan = plan(bot, appetite, new Random(bot.getId()));
        return bot.getName() + " (lv " + bot.getLevel() + ", " + appetite + ", " + bot.getMeso() + " mesos): "
                + ScrollBudget.status(bot.getId(), bot.getLevel()) + "; next: "
                + (plan == null ? "nothing" : plan.choice().scrollId() + (plan.choice().whiteScroll() ? " + white"
                : "") + " on " + plan.equip().getItemId() + " (" + stats(plan.equip()) + ")")
                + "; FM listings " + BotConsignment.listedBy(bot.getId());
    }

    public static void forget(int botId) {
        states.remove(botId);
        ScrollWants.forget(botId);
    }

    // ------------------------------------------------------------------ the item data

    private static final class LiveFacts implements Facts {
        private final Map<Integer, List<Integer>> catalog = new ConcurrentHashMap<>();

        private static ItemInformationProvider ii() {
            return ItemInformationProvider.getInstance();
        }

        @Override
        public ScrollFacts scroll(int scrollId) {
            Map<String, Integer> stats = ii().getEquipStats(scrollId);
            if (stats == null) {
                return null;
            }
            return new ScrollFacts(scrollId, stats.getOrDefault("success", 0), stats.getOrDefault("cursed", 0),
                    ScrollRules.gainWeight(stats));
        }

        @Override
        public List<Integer> reqs(int scrollId) {
            return ii().getScrollReqs(scrollId);
        }

        @Override
        public List<Integer> catalogFor(int equipItemId) {
            int cat = (equipItemId / 10000) % 100;
            return catalog.computeIfAbsent(cat, c -> {
                List<Integer> ids = new ArrayList<>();
                int base = 2040000 + c * 100;
                if (base < 2040000 || base >= 2049000) {
                    return ids;
                }
                for (int id = base; id < base + 100; id++) {
                    try {
                        if (ScrollRules.isPlainScroll(id) && ii().getEquipStats(id) != null
                                && ii().getEquipStats(id).getOrDefault("success", 0) > 0) {
                            ids.add(id);
                        }
                    } catch (RuntimeException missing) {
                        // not an item of this version
                    }
                }
                return List.copyOf(ids);
            });
        }

        @Override
        public int totalSlots(Equip equip) {
            Map<String, Integer> stats = ii().getEquipStats(equip.getItemId());
            int tuc = stats == null ? 0 : stats.getOrDefault("tuc", 0);
            return tuc + Math.max(0, equip.getVicious());
        }

        @Override
        public boolean cash(int itemId) {
            return ii().isCash(itemId);
        }

        @Override
        public String name(int itemId) {
            return ii().getName(itemId);
        }

        @Override
        public Integer marketValue(int itemId) {
            Integer price = ItemDatabase.getInstance().getItemPrice(itemId);
            return price == null || price <= 0 ? null : price;
        }

        @Override
        public Integer equipValue(Equip equip) {
            return EquipValuation.valueOf(equip);
        }

        @Override
        public double statScore(Equip equip) {
            EquipValuation.Facts f = EquipValuation.factsOf(equip);
            if (f == null) {
                return 0;
            }
            return 10.0 * Math.max(0, f.attackGain()) + 3.0 * Math.max(0, f.statGain())
                    + Math.max(0, f.hpMpGain()) / 5.0;
        }
    }

    // ------------------------------------------------------------------ test seams

    static void useForTest(Facts testFacts, Roller testRoller, Megaphone testMegaphone, LongSupplier testClock) {
        facts = testFacts == null ? LIVE : testFacts;
        roller = testRoller == null ? (equip, scrollId, white) ->
                ItemInformationProvider.getInstance().scrollEquipWithId(equip, scrollId, white, 0, false) : testRoller;
        megaphone = testMegaphone == null ? (bot, node, tokens, key) ->
                WorldEventMegaphones.getInstance().offerEvent(bot, node, tokens, key) : testMegaphone;
        clock = testClock == null ? System::currentTimeMillis : testClock;
        states.clear();
        curves.clear();
    }
}
