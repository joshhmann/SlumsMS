package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.Job;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.inventory.ItemConstants;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/*
 * Real bots ticket 09: bots buy their potions and ammo at real NPC shops, with their own mesos, at the server's
 * prices.
 *
 * TrainingBot's town stop asks siteFor which of its town's shops to walk to (ShopCatalog), walks there (GCMovement:
 * visibly on screen, the coarse planner offscreen) and then restocks through BotResupply, where this class is the
 * installed resupplier: it shops at that NPC once the bot stands on its map, and buys nothing anywhere else. A bot
 * goes shopping when something sent it back, or it is under its ammo kit or half its potion kit.
 *
 * A purchase follows Shop.buy and Shop.recharge exactly, minus the packets:
 *  - an item sold for mesos costs its listed price per unit, bought at most a stack (the client's quantity cap) per
 *    transaction, and only when it fits (InventoryManipulator.checkSpace, here BotHoldings.canHold);
 *  - a star or bullet is sold a full stack at a time (getSlotMax with the bot's Claw/Gun Mastery) for its listed price;
 *  - a recharge refills a held stack to that size for ceil(unitPrice x missing).
 * Mesos leave through BotHoldings.spendMeso and items arrive through BotHoldings.grantItem / rechargeHeld, so the ledger
 * sees both and the store's normal sweep writes them. Buying is a meso sink: none of it passes the ticket 15 yield caps.
 *
 * What it buys, in order of need, from what the shop lists:
 *  - ammo for its weapon (when its main attack fires any), up to 1,000-4,000 shots by level: recharge its stacks of
 *    the best stars or bullets, then the best stars, bullets or arrows the shop sells that its weapon fires at its
 *    level (most attack first);
 *  - MP potions when its main attack costs MP, HP potions always. The potion is the one whose heal is nearest a
 *    quarter of the bot's max, among the shop's plain single-purpose potions within half again of the cheapest per
 *    point (Red, Orange, White for HP; Blue, Mana Elixir for MP, as the bot grows);
 * first up to an essential share (a stack of ammo at most, a third of the potions), then up to its full kit, while its budget
 * lasts: what it holds above a reserve of a quarter of its mesos and at least 200 a level (fares, the next trip).
 * A bot that cannot afford ammo goes without, and fights in melee as ticket 08 has it.
 */
public final class BotShopping {

    private BotShopping() {}

    private static final Logger log = LoggerFactory.getLogger(BotShopping.class);

    private static final double RESERVE_SHARE = 0.25;
    private static final int RESERVE_PER_LEVEL = 200;
    private static final double POTION_HEAL_SHARE = 0.25;   // a potion should restore about a quarter of the max
    private static final double POTION_VALUE_SLACK = 1.5;   // ... at no more than 1.5x the cheapest price per point
    private static final double ESSENTIAL_POTION_SHARE = 1.0 / 3;

    // Ledger of the sink since boot (the GM read-out, !botyield).
    private static final AtomicLong visits = new AtomicLong();
    private static final AtomicLong mesosSpent = new AtomicLong();
    private static final AtomicLong itemsBought = new AtomicLong();
    private static final AtomicLong starsRecharged = new AtomicLong();

    // The weapon a bot fires (a seam: the server reads it from the item data, which tests do not have).
    private static volatile Function<Character, WeaponType> weaponOf = BotAttack::resolveEquippedWeaponType;

    static WeaponType weapon(Character bot) {
        try {
            return weaponOf.apply(bot);
        } catch (LinkageError | RuntimeException noItemData) {
            return null;
        }
    }

    static void useWeaponForTest(Function<Character, WeaponType> test) {
        weaponOf = test != null ? test : BotAttack::resolveEquippedWeaponType;
    }

    /* Puts the shop run in as ticket 08's resupply hook. */
    public static void install() {
        BotResupply.install(BotShopping::resupply);
    }

    // ---------------------------------------------------------------- the kit

    /* How many HP potions a bot of this level carries out of town. */
    static int hpGoal(int level) {
        return clamp(40 + 3 * level, 50, 400);
    }

    /* How many MP potions: a mage lives on them, anyone else whose attack costs MP needs fewer; none otherwise. */
    static int mpGoal(Character bot, boolean usesMp) {
        int level = bot.getLevel();
        if (isMage(bot.getJob())) {
            return clamp(60 + 4 * level, 80, 500);
        }
        return usesMp ? clamp(20 + 2 * level, 30, 250) : 0;
    }

    /*
     * Shots a bot carries out of town: arrows, bolts, stars or bullets (1,000 at level 0 to 4,000 from level 75). Stars
     * and bullets come a whole stack at a time, so a bot holds at least one stack whatever its size (9,999 on
     * MapleRoot's item data, 800 or so on stock v83).
     */
    static int ammoGoal(int level) {
        return Math.min(4000, 1000 + 40 * level);
    }

    /* What the bot may spend now: its mesos above the reserve. */
    static long budget(Character bot) {
        long meso = bot.getMeso();
        long reserve = Math.max((long) RESERVE_PER_LEVEL * bot.getLevel(), (long) Math.ceil(meso * RESERVE_SHARE));
        return Math.max(0, meso - reserve);
    }

    static boolean isMage(Job job) {
        return job != null && (job.isA(Job.MAGICIAN) || job.isA(Job.BLAZEWIZARD1));
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // ---------------------------------------------------------------- where to go

    /*
     * The shop in the bot's town to walk to for these needs: the one selling the most of what it needs (ammo it can
     * fire weighs most), nearest the town map on a tie. Null when no shop in town sells anything it needs.
     */
    public static ShopCatalog.Site siteFor(Character bot, int townMapId, SupplyNeeds needs) {
        if (bot == null || needs == null || !wantsShop(bot, needs)) {
            return null;
        }
        ShopCatalog.Site best = null;
        int bestScore = 0;
        for (ShopCatalog.Site site : ShopCatalog.town(bot, townMapId)) {
            int score = score(bot, site, needs);
            if (score > bestScore || (score == bestScore && score > 0 && best != null
                    && best.mapId() != townMapId && site.mapId() == townMapId)) {
                best = site;
                bestScore = score;
            }
        }
        return best;
    }

    /*
     * Whether the bot is low enough to go shopping, as a player would: something sent it back, or it is under its
     * ammo kit or half its potion kit. A stack merely short of full is not worth the walk.
     */
    static boolean wantsShop(Character bot, SupplyNeeds needs) {
        if (needs.shortage() != BotSupplies.Shortage.NONE) {
            return true;
        }
        int level = bot.getLevel();
        if (needs.ammoItemId() != 0 && needs.ammoHeld() < ammoGoal(level)) {
            return true;
        }
        if (needs.hpPotions() < hpGoal(level) / 2) {
            return true;
        }
        int mpGoal = mpGoal(bot, needs.usesMp());
        return mpGoal > 0 && needs.mpPotions() < mpGoal / 2;
    }

    private static int score(Character bot, ShopCatalog.Site site, SupplyNeeds needs) {
        WeaponType weapon = weapon(bot);
        int score = 0;
        if (needs.ammoItemId() != 0) {
            if (bestAmmo(bot, weapon, site) != null) {
                score += 4;
            } else if (!needs.toRecharge().isEmpty() && site.listing(needs.toRecharge().get(0).getItemId()) != null) {
                score += 3;
            }
        }
        if (pickPotion(bot, site, true) != null) {
            score += 2;
        }
        if (needs.usesMp() && pickPotion(bot, site, false) != null) {
            score += 2;
        }
        return score;
    }

    // botId -> the shop its town stop is walking it to (TrainingBot), so it buys there and not at a neighbour.
    private static final Map<Integer, ShopCatalog.Site> heading = new ConcurrentHashMap<>();

    /* Notes the shop the bot is on its way to; its restock buys there once it stands on that map. */
    public static void headFor(Character bot, ShopCatalog.Site site) {
        if (bot != null && site != null) {
            heading.put(bot.getId(), site);
        }
    }

    static void clearBot(int botId) {
        heading.remove(botId);
    }

    /* The shop the bot is restocking at: the one it headed for, when it stands on that map; else none. */
    static ShopCatalog.Site siteFor(Character bot) {
        ShopCatalog.Site headed = heading.remove(bot.getId());
        return headed != null && headed.mapId() == bot.getMapId() ? headed : null;
    }

    // ---------------------------------------------------------------- the resupplier

    /*
     * BotResupply's hook: shop at the NPC the bot's town stop walked it to (headFor), on that map. Nowhere else: a bot
     * buys only where it went to buy. False when it is not there or bought nothing.
     */
    static boolean resupply(Character bot, SupplyNeeds needs) {
        ShopCatalog.Site site = siteFor(bot);
        if (site == null) {
            return false;
        }
        Receipt receipt = shop(bot, site, needs);
        visits.incrementAndGet();
        if (receipt.isEmpty()) {
            log.info("[Shop] {} (lv {} {}) at npc {} on map {} ({} px away, {}): bought nothing ({} mesos, budget {})",
                    bot.getName(), bot.getLevel(), bot.getJob(), site.npcId(), site.mapId(), distance(bot, site),
                    observed(bot), bot.getMeso(), budget(bot));
            return false;
        }
        mesosSpent.addAndGet(receipt.spent);
        itemsBought.addAndGet(receipt.items);
        starsRecharged.addAndGet(receipt.recharged);
        log.info("[Shop] {} (lv {} {}) at npc {} on map {} ({} px away, {}): {} for {} mesos ({} left)", bot.getName(),
                bot.getLevel(), bot.getJob(), site.npcId(), site.mapId(), distance(bot, site), observed(bot),
                String.join(", ", receipt.lines()), receipt.spent, bot.getMeso());
        return true;
    }

    private static long distance(Character bot, ShopCatalog.Site site) {
        Point at = bot.getPosition();
        return at == null ? -1 : Math.round(at.distance(site.npcPos()));
    }

    private static String observed(Character bot) {
        try {
            return GCMovement.isMapObserved(bot.getMapId()) ? "on screen" : "offscreen";
        } catch (LinkageError | RuntimeException e) {
            return "offscreen";
        }
    }

    /* What one visit bought. */
    static final class Receipt {
        long spent;
        long items;
        long recharged;
        // what -> {quantity, mesos}, in the order first bought
        private final Map<String, long[]> bought = new LinkedHashMap<>();

        boolean isEmpty() {
            return items == 0 && recharged == 0;
        }

        void line(String what, int qty, long cost) {
            long[] sum = bought.computeIfAbsent(what, k -> new long[2]);
            sum[0] += qty;
            sum[1] += cost;
        }

        List<String> lines() {
            List<String> out = new ArrayList<>();
            bought.forEach((what, sum) -> out.add(sum[0] + "x " + what + " (" + sum[1] + ")"));
            return out;
        }
    }

    /* Buys what the bot needs at this shop, essentials first, within its budget. */
    static Receipt shop(Character bot, ShopCatalog.Site site, SupplyNeeds needs) {
        Receipt receipt = new Receipt();
        long[] budget = {budget(bot)};
        WeaponType weapon = weapon(bot);
        boolean wantsAmmo = needs.ammoItemId() != 0 && BotAmmo.usesAmmo(weapon);
        ShopCatalog.Listing hp = pickPotion(bot, site, true);
        ShopCatalog.Listing mp = needs.usesMp() || isMage(bot.getJob()) ? pickPotion(bot, site, false) : null;
        int hpGoal = hpGoal(bot.getLevel());
        int mpGoal = mpGoal(bot, needs.usesMp());

        // Essentials: a stack of ammo, a third of each potion.
        if (wantsAmmo) {
            restockAmmo(bot, weapon, site, essentialAmmo(bot, weapon, site), budget, receipt);
        }
        if (mp != null) {
            buyUpTo(bot, mp, (int) Math.ceil(mpGoal * ESSENTIAL_POTION_SHARE), false, budget, receipt);
        }
        if (hp != null) {
            buyUpTo(bot, hp, (int) Math.ceil(hpGoal * ESSENTIAL_POTION_SHARE), true, budget, receipt);
        }
        // Then the full kit.
        if (wantsAmmo) {
            restockAmmo(bot, weapon, site, fullAmmo(bot), budget, receipt);
        }
        if (mp != null) {
            buyUpTo(bot, mp, mpGoal, false, budget, receipt);
        }
        if (hp != null) {
            buyUpTo(bot, hp, hpGoal, true, budget, receipt);
        }
        if (wantsAmmo && (receipt.items > 0 || receipt.recharged > 0)) {
            BotAmmo.syncStats(bot, weapon);   // the first stack it fires may have changed: re-derive its totals
        }
        return receipt;
    }

    // ---------------------------------------------------------------- potions

    /*
     * The potion to buy for HP (or MP) here: a plain potion that restores only that, a flat amount, sold for mesos;
     * among those within POTION_VALUE_SLACK of the cheapest per point, the one nearest a quarter of the bot's max.
     */
    static ShopCatalog.Listing pickPotion(Character bot, ShopCatalog.Site site, boolean forHp) {
        int max = forHp ? bot.getCurrentMaxHp() : bot.getCurrentMaxMp();
        double target = Math.max(1, max * POTION_HEAL_SHARE);
        List<ShopCatalog.Listing> plain = new ArrayList<>();
        List<Integer> heals = new ArrayList<>();
        double cheapest = Double.MAX_VALUE;
        for (ShopCatalog.Listing l : site.items()) {
            if (!l.soldForMesos()) {
                continue;
            }
            SupplyData.Recovery r = SupplyData.get().recovery(l.itemId());
            if (r == null || r.hpRate() > 0 || r.mpRate() > 0 || (forHp ? r.restoresMp() || r.hp() <= 0
                    : r.restoresHp() || r.mp() <= 0)) {
                continue;
            }
            int heal = forHp ? BotVitals.hpHeal(bot, r) : BotVitals.mpHeal(bot, r);
            if (heal <= 0) {
                continue;
            }
            plain.add(l);
            heals.add(heal);
            cheapest = Math.min(cheapest, (double) l.price() / heal);
        }
        ShopCatalog.Listing best = null;
        double bestFit = Double.MAX_VALUE;
        for (int i = 0; i < plain.size(); i++) {
            ShopCatalog.Listing l = plain.get(i);
            int heal = heals.get(i);
            if ((double) l.price() / heal > cheapest * POTION_VALUE_SLACK) {
                continue;
            }
            double fit = Math.abs(Math.log(heal / target));
            if (best == null || fit < bestFit - 1e-9 || (Math.abs(fit - bestFit) <= 1e-9 && l.price() < best.price())) {
                best = l;
                bestFit = fit;
            }
        }
        return best;
    }

    /* Buys the potion until the bot holds {@code goal} potions of that kind (counting what it already has). */
    private static void buyUpTo(Character bot, ShopCatalog.Listing potion, int goal, boolean forHp, long[] budget,
                                Receipt receipt) {
        int want = goal - BotVitals.potionCount(bot, forHp);
        if (want <= 0) {
            return;
        }
        buyUnits(bot, potion, want, budget, receipt);
    }

    // ---------------------------------------------------------------- ammo

    /*
     * The ammo this shop sells for mesos that the bot's weapon fires at its level: most attack first, then cheapest
     * per unit. Null when the shop sells none.
     */
    static ShopCatalog.Listing bestAmmo(Character bot, WeaponType weapon, ShopCatalog.Site site) {
        ShopCatalog.Listing best = null;
        int bestWatk = -1;
        double bestUnit = Double.MAX_VALUE;
        for (ShopCatalog.Listing l : site.items()) {
            if (!l.soldForMesos() || !BotAmmo.fits(weapon, l.itemId(), bot.getLevel())) {
                continue;
            }
            int watk = SupplyData.get().projectileWatk(l.itemId());
            double unit = unitCost(bot, l);
            if (watk > bestWatk || (watk == bestWatk && unit < bestUnit)) {
                best = l;
                bestWatk = watk;
                bestUnit = unit;
            }
        }
        return best;
    }

    // A star or bullet listing's price buys a whole stack; anything else is per unit.
    private static double unitCost(Character bot, ShopCatalog.Listing l) {
        if (!ItemConstants.isRechargeable(l.itemId())) {
            return l.price();
        }
        int stack = SupplyData.get().slotMax(bot, l.itemId());
        return stack > 0 ? (double) l.price() / stack : Double.MAX_VALUE;
    }

    private static int stackOf(Character bot, WeaponType weapon, ShopCatalog.Site site) {
        ShopCatalog.Listing best = bestAmmo(bot, weapon, site);
        int id = best != null ? best.itemId() : BotAttackData.projectileFor(weapon);
        int stack = SupplyData.get().slotMax(bot, id);
        return stack > 0 ? stack : 1000;
    }

    private static int essentialAmmo(Character bot, WeaponType weapon, ShopCatalog.Site site) {
        return Math.min(stackOf(bot, weapon, site), ammoGoal(bot.getLevel()));
    }

    private static int fullAmmo(Character bot) {
        return ammoGoal(bot.getLevel());
    }

    /*
     * Brings the ammo the bot holds up to {@code goal}: first recharging its own stars or bullets that are at least
     * as good as what the shop sells (cheapest per star first), then buying the best the shop sells.
     */
    private static void restockAmmo(Character bot, WeaponType weapon, ShopCatalog.Site site, int goal, long[] budget,
                                    Receipt receipt) {
        ShopCatalog.Listing best = bestAmmo(bot, weapon, site);
        int bestWatk = best != null ? SupplyData.get().projectileWatk(best.itemId()) : -1;
        if (weapon == WeaponType.CLAW || weapon == WeaponType.GUN) {
            for (Item stack : BotAmmo.stacks(bot, weapon)) {
                if (BotAmmo.held(bot, weapon) >= goal) {
                    return;
                }
                if (site.listing(stack.getItemId()) == null
                        || SupplyData.get().projectileWatk(stack.getItemId()) < bestWatk) {
                    continue;   // this shop cannot recharge it, or a new stack of better ones is the better buy
                }
                recharge(bot, stack, budget, receipt);
            }
        }
        if (best == null) {
            return;
        }
        int want = goal - BotAmmo.held(bot, weapon);
        if (want > 0) {
            buyUnits(bot, best, want, budget, receipt);
        }
    }

    /* Shop.recharge: refill the stack to its full size for ceil(unitPrice x missing). */
    private static void recharge(Character bot, Item stack, long[] budget, Receipt receipt) {
        int full = SupplyData.get().slotMax(bot, stack.getItemId());
        int missing = full - stack.getQuantity();
        if (missing <= 0 || stack.getQuantity() < 0) {
            return;
        }
        int price = (int) Math.ceil(SupplyData.get().unitPrice(stack.getItemId()) * missing);
        if (price > budget[0] || !BotHoldings.spendMeso(bot, price)) {
            return;
        }
        int added = BotHoldings.rechargeHeld(bot, stack, full);
        if (added <= 0) {
            BotHoldings.grantMeso(bot, price);   // the stack moved under us: the shop takes nothing
            return;
        }
        budget[0] -= price;
        receipt.spent += price;
        receipt.recharged += added;
        receipt.line("recharge " + stack.getItemId(), added, price);
    }

    // ---------------------------------------------------------------- Shop.buy

    /*
     * Buys at least {@code want} units of the listing (a star or bullet comes a whole stack at a time), one
     * transaction per stack, as far as the budget and the bot's inventory go.
     */
    private static void buyUnits(Character bot, ShopCatalog.Listing l, int want, long[] budget, Receipt receipt) {
        boolean rechargeable = ItemConstants.isRechargeable(l.itemId());
        int stack = SupplyData.get().slotMax(bot, l.itemId());
        if (stack <= 0) {
            return;   // no item data: no idea what a stack is
        }
        int bought = 0;
        long cost = 0;
        while (want > 0) {
            int qty = rechargeable ? stack : Math.min(want, stack);
            long price = rechargeable ? l.price() : (long) l.price() * qty;   // exact: a float total undercharges
            if (!rechargeable && price > budget[0]) {
                qty = (int) Math.min(qty, budget[0] / l.price());
                price = (long) l.price() * qty;
            }
            if (qty <= 0 || price > budget[0] || price > Integer.MAX_VALUE || !buy(bot, l.itemId(), qty, (int) price)) {
                break;
            }
            budget[0] -= price;
            cost += price;
            bought += qty;
            want -= qty;
        }
        if (bought > 0) {
            receipt.spent += cost;
            receipt.items += bought;
            receipt.line(String.valueOf(l.itemId()), bought, cost);
        }
    }

    /* One Shop.buy transaction: room first (checkSpace), then the mesos, then the items. */
    private static boolean buy(Character bot, int itemId, int quantity, int price) {
        if (quantity <= 0 || quantity > Short.MAX_VALUE || bot.getMeso() < price) {
            return false;
        }
        Item item = new Item(itemId, (short) 0, (short) quantity, -1);
        if (!BotHoldings.canHold(bot, item) || !BotHoldings.spendMeso(bot, price)) {
            return false;
        }
        if (!BotHoldings.grantItem(bot, item)) {
            BotHoldings.grantMeso(bot, price);   // filled up in between: the sale does not happen
            return false;
        }
        return true;
    }

    // ---------------------------------------------------------------- read-out

    /* One line for the GM economy read-out. */
    public static String report() {
        return String.format("NPC shops (meso sink since boot): %,d visits, %,d mesos spent, %,d items bought, %,d"
                        + " stars/bullets recharged; starter stashes granted: %,d", visits.get(), mesosSpent.get(),
                itemsBought.get(), starsRecharged.get(), StarterStash.granted());
    }

    static void resetForTest() {
        visits.set(0);
        mesosSpent.set(0);
        itemsBought.set(0);
        starsRecharged.set(0);
    }
}
