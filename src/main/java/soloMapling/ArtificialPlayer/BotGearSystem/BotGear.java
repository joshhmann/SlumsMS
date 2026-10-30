package soloMapling.ArtificialPlayer.BotGearSystem;

import client.Character;
import client.Job;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.EquipType;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackConfig;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDamage;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotSkillActions;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Plan;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Rules;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Wearer;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.itemPool.EquipOmitList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.IntPredicate;

/*
 * Real bots 10: a bot wears the best gear it owns. The gateway to BotGearPlanner: reads the bot (base stats, what it
 * wears, the equips it carries), asks the planner, and moves the equips between its equip tab and its body.
 *
 * Event-driven, never per tick: a review runs when the bot loots an equip (on screen or in the offscreen accrual)
 * and after it levels or advances, so a bot that gains nothing costs nothing. Offscreen bots follow the same rules.
 * The moves are plain inventory moves, so the next bot store sweep writes them with the rest of its holdings.
 *
 * Looks: only the real-gear slots are touched, never the cash overlay (-1xx), so a bot with an NX outfit keeps it.
 * Nothing is emptied but by an overall or a two-handed weapon. Its weapon stays one its skills can be drawn with:
 * the job's kinds (BotWeapons), the same attacks as before, and a 1st-job bot or a beginner keeps its weapon kind
 * (a 1st job's weapon picks its branch). Equips the item data does not know (MapleRoot's custom ones), cash, the
 * other gender's, the omit list's (broken sprites) and rebirth-locked equips are never put on.
 */
public final class BotGear {

    private static final Logger log = LoggerFactory.getLogger(BotGear.class);

    private BotGear() {}

    /* Off switch for the whole optimizer (bots then keep what they wear). */
    public static volatile boolean ENABLED = true;

    /*
     * Ticket 162 part 1: which optimizer dresses a bot. "climb" (the default, live behaviour): BotGearPlanner's hill
     * climb for every bot. "pareto": BotGearOptimizer (the port of nutnnut's Pareto DP) for bots on a map a player
     * can see or is about to (LodCounts.isMapActive, or every bot when the observer poll is not running), and the
     * climb behind GearMemo's skip-if-unchanged for offscreen bots. -Dslums.gear.optimizer=pareto|climb.
     */
    public static String optimizer() {
        return System.getProperty("slums.gear.optimizer", "climb").trim().toLowerCase(java.util.Locale.ROOT);
    }

    static boolean pareto() {
        return "pareto".equals(optimizer());
    }

    /* A bot a player can see (or is about to): the full optimizer. Every bot when the observer poll is off. */
    static boolean fullPass(Character bot) {
        try {
            return !soloMapling.ArtificialPlayer.GCMoveSystem.LodCounts.trackerRunning()
                    || soloMapling.ArtificialPlayer.GCMoveSystem.LodCounts.isMapActive(bot.getMapId());
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    // Weapons a beginner may pick up and fight with: no bow, claw or gun without the ammo and skills for them.
    private static final Set<EquipType> BEGINNER_WEAPONS = Set.of(EquipType.SWORD, EquipType.AXE, EquipType.MACE,
            EquipType.DAGGER, EquipType.WAND, EquipType.STAFF, EquipType.SWORD_2H, EquipType.AXE_2H, EquipType.MACE_2H,
            EquipType.SPEAR, EquipType.POLEARM);

    /* After a bot took an item into its inventory: an equip may be better than what it wears. */
    public static void afterLoot(Character bot, Item item) {
        if (item != null && item.getInventoryType() == InventoryType.EQUIP) {
            review(bot);
        }
    }

    /* After a bot's level or job changed: equips it could not wear before may now fit. */
    public static void afterProgress(Character bot) {
        review(bot);
    }

    /*
     * 1.6.1 ticket 30 (promise audit, REPORT item 2): a reborn bot stands at level 1 in its level-200 gear. It takes
     * off every piece a level 1 of its new job cannot wear (level, job, fame, or a stat its fresh base cannot meet)
     * into its equip bag, puts on the best it owns that does fit, and keeps the rest: afterProgress puts each piece
     * back on once its level, job and stats allow, as it levels. Left with no weapon it could fight with, it takes up
     * a starter weapon as a new beginner bot does. A piece the item data does not know stays on (nothing to judge it
     * by), and so does whatever a full bag has no room for. Returns how many pieces came off; never throws.
     */
    public static int afterRebirth(Character bot) {
        if (!ENABLED || bot == null || !BotHelpers.isBot(bot) || !EquipMetadataCache.isInitialized()) {
            return 0;
        }
        try {
            int off = afterRebirth(bot, liveFacts(bot), worn -> liveValuer(bot, worn));
            if (bot.getInventory(InventoryType.EQUIPPED).getItem(BotGearPlanner.WEAPON) == null && bot.getMap() != null) {
                soloMapling.ArtificialPlayer.BotCustomization.EquipBot(bot, STARTER_WEAPON); // a beginner's sword
            }
            return off;
        } catch (RuntimeException e) {
            log.warn("Rebirth gear of bot {} ({}) failed", bot.getId(), bot.getName(), e);
            return 0;
        }
    }

    /* The plain sword a new beginner bot is dressed in (BeginnerEquip): level 0, any job. */
    public static final int STARTER_WEAPON = 1302000;

    /* afterRebirth with its item data and yardstick handed in (tests). */
    static int afterRebirth(Character bot, IntFunction<Known> facts, Function<Map<Short, Piece>, Valuer> valuers) {
        int off = 0;
        synchronized (bot) {
            Inventory carried = bot.getInventory(InventoryType.EQUIP);
            Inventory body = bot.getInventory(InventoryType.EQUIPPED);
            Wearer wearer = new Wearer(bot.getJob().getJobNiche(), bot.getLevel(), bot.getFame(), bot.getStr(),
                    bot.getDex(), bot.getInt(), bot.getLuk(), carried.getNumFreeSlot());
            for (short slot : BotGearPlanner.SLOTS) {
                if (!(body.getItem(slot) instanceof Equip equip)) {
                    continue;
                }
                Known known = facts.apply(equip.getItemId());
                if (known == null || BotGearPlanner.fitsAlone(wearer, known)) {
                    continue;
                }
                short free = carried.getNextFreeSlot();
                if (free < 0) {
                    break;   // a full bag: what is left stays on
                }
                body.removeSlot(slot);
                bot.unequippedItem(equip);
                equip.setPosition(free);
                carried.addItemFromDB(equip);
                off++;
            }
            if (off > 0 && bot.getMap() != null) {
                bot.equipChanged();
            }
        }
        review(bot, facts, valuers);
        return off;
    }

    /* Dresses the bot in the best gear it owns. Returns whether anything moved; never throws. */
    public static boolean review(Character bot) {
        if (!ENABLED || bot == null || bot.getMap() == null || !BotHelpers.isBot(bot)
                || !EquipMetadataCache.isInitialized()) {
            return false;
        }
        try {
            return review(bot, liveFacts(bot), worn -> liveValuer(bot, worn));
        } catch (RuntimeException e) {
            log.warn("Gear review of bot {} ({}) failed", bot.getId(), bot.getName(), e);
            return false;
        }
    }

    /* The review with its item data and its yardstick (given what the bot wears now) handed in - tests. */
    static boolean review(Character bot, IntFunction<Known> facts, Function<Map<Short, Piece>, Valuer> valuers) {
        synchronized (bot) {
            boolean pareto = pareto();
            boolean full = !pareto || fullPass(bot);
            long signature = 0;
            if (!full) {
                // Ticket 162: the cheap pass - an offscreen bot whose gear and stats did not move since its last
                // review is left alone without reading a single equip.
                signature = GearMemo.signature(bot);
                if (GearMemo.unchanged(bot.getId(), signature)) {
                    return false;
                }
            }
            boolean changed = reviewNow(bot, facts, valuers, pareto && full);
            if (!full) {
                GearMemo.remember(bot.getId(), changed ? GearMemo.signature(bot) : signature);
            }
            return changed;
        }
    }

    private static boolean reviewNow(Character bot, IntFunction<Known> facts,
                                     Function<Map<Short, Piece>, Valuer> valuers, boolean optimizer) {
        {
            Inventory carried = bot.getInventory(InventoryType.EQUIP);
            Inventory body = bot.getInventory(InventoryType.EQUIPPED);
            List<Piece> owned = new ArrayList<>();
            carried.lockInventory();
            try {
                for (Item item : carried.list()) {
                    if (item instanceof Equip equip) {
                        Known known = facts.apply(equip.getItemId());
                        if (known != null) {
                            owned.add(piece(equip, known));
                        }
                    }
                }
            } finally {
                carried.unlockInventory();
            }
            if (owned.isEmpty()) {
                return false;   // the common case: nothing carried it could wear
            }
            Map<Short, Piece> worn = new LinkedHashMap<>();
            body.lockInventory();
            try {
                for (short slot : BotGearPlanner.SLOTS) {
                    if (body.getItem(slot) instanceof Equip equip) {
                        worn.put(slot, piece(equip, facts.apply(equip.getItemId())));
                    }
                }
            } finally {
                body.unlockInventory();
            }
            Wearer wearer = new Wearer(bot.getJob().getJobNiche(), bot.getLevel(), bot.getFame(), bot.getStr(),
                    bot.getDex(), bot.getInt(), bot.getLuk(), carried.getNumFreeSlot());
            Piece held = worn.get(BotGearPlanner.WEAPON);
            int heldId = held != null ? held.itemId() : 0;
            Rules rules = new Rules(weapon -> weaponAllowed(bot, heldId, weapon),
                    weapon -> BotWeapons.takesShield(BotWeapons.kindOf(weapon)));
            Plan plan = optimizer ? BotGearOptimizer.plan(wearer, worn, owned, valuers.apply(worn), rules)
                    : BotGearPlanner.plan(wearer, worn, owned, valuers.apply(worn), rules);
            if (plan == null) {
                return false;
            }
            if (!apply(bot, worn, plan.wear())) {
                return false;
            }
            if (log.isDebugEnabled()) {
                log.debug("Bot {} ({}) lv {} {} changed gear: main attack {} -> {}", bot.getId(), bot.getName(),
                        bot.getLevel(), bot.getJob(), Math.round(plan.before()), Math.round(plan.after()));
            }
            return true;
        }
    }

    /* Ticket 162: the shared scoring entry point (GearScoring) over the live item data and damage yardstick. */
    static final GearScoring SCORING = new GearScoring() {
        @Override
        public double upgradeValue(Character chr, Equip candidate) {
            if (chr == null || candidate == null || !EquipMetadataCache.isInitialized()) {
                return 0;
            }
            return BotGear.upgradeValue(chr, candidate, liveFacts(chr), worn -> liveValuer(chr, worn));
        }

        @Override
        public double upgradeFraction(Character chr, Equip candidate) {
            if (chr == null || candidate == null || !EquipMetadataCache.isInitialized()) {
                return 0;
            }
            return BotGear.upgradeFraction(chr, candidate, liveFacts(chr), worn -> liveValuer(chr, worn));
        }

        @Override
        public Map<Short, Equip> bestSet(Character chr, java.util.Collection<Equip> extras) {
            if (chr == null || !EquipMetadataCache.isInitialized()) {
                return Map.of();
            }
            return BotGear.bestSet(chr, extras, liveFacts(chr), worn -> liveValuer(chr, worn));
        }
    };

    /* GearScoring.upgradeValue with its item data and yardstick handed in (tests). */
    static double upgradeValue(Character chr, Equip candidate, IntFunction<Known> facts,
                               Function<Map<Short, Piece>, Valuer> valuers) {
        Plan plan = planWith(chr, List.of(candidate), facts, valuers);
        if (plan == null) {
            return 0;
        }
        for (Piece p : plan.wear().values()) {
            if (p.item() == candidate) {
                return Math.max(0, plan.after() - plan.before());
            }
        }
        return 0;
    }

    /* GearScoring.upgradeFraction with its item data and yardstick handed in (tests). */
    static double upgradeFraction(Character chr, Equip candidate, IntFunction<Known> facts,
                                  Function<Map<Short, Piece>, Valuer> valuers) {
        Plan plan = planWith(chr, List.of(candidate), facts, valuers);
        if (plan == null || plan.before() <= 0) {
            return 0;
        }
        for (Piece p : plan.wear().values()) {
            if (p.item() == candidate) {
                return Math.max(0, plan.after() - plan.before()) / plan.before();
            }
        }
        return 0;
    }

    /* GearScoring.bestSet with its item data and yardstick handed in (tests). */
    static Map<Short, Equip> bestSet(Character chr, java.util.Collection<Equip> extras, IntFunction<Known> facts,
                                     Function<Map<Short, Piece>, Valuer> valuers) {
        Plan plan = planWith(chr, extras, facts, valuers);
        if (plan == null) {
            return Map.of();
        }
        Inventory body = chr.getInventory(InventoryType.EQUIPPED);
        Map<Short, Equip> out = new LinkedHashMap<>();
        for (Map.Entry<Short, Piece> e : plan.wear().entrySet()) {
            if (body.getItem(e.getKey()) != e.getValue().item()) {
                out.put(e.getKey(), (Equip) e.getValue().item());
            }
        }
        return out;
    }

    // What the character's own planner would wear from its gear plus these extras; read only.
    private static Plan planWith(Character chr, java.util.Collection<Equip> extras, IntFunction<Known> facts,
                                 Function<Map<Short, Piece>, Valuer> valuers) {
        synchronized (chr) {
            Inventory carried = chr.getInventory(InventoryType.EQUIP);
            Inventory body = chr.getInventory(InventoryType.EQUIPPED);
            List<Piece> owned = new ArrayList<>();
            for (Item item : carried.list()) {
                if (item instanceof Equip equip) {
                    Known known = facts.apply(equip.getItemId());
                    if (known != null) {
                        owned.add(piece(equip, known));
                    }
                }
            }
            for (Equip equip : extras) {
                Known known = equip == null ? null : facts.apply(equip.getItemId());
                if (known != null) {
                    owned.add(piece(equip, known));
                }
            }
            Map<Short, Piece> worn = new LinkedHashMap<>();
            for (short slot : BotGearPlanner.SLOTS) {
                if (body.getItem(slot) instanceof Equip equip) {
                    worn.put(slot, piece(equip, facts.apply(equip.getItemId())));
                }
            }
            Wearer wearer = new Wearer(chr.getJob().getJobNiche(), chr.getLevel(), chr.getFame(), chr.getStr(),
                    chr.getDex(), chr.getInt(), chr.getLuk(), Math.max(carried.getNumFreeSlot(), extras.size()));
            Piece held = worn.get(BotGearPlanner.WEAPON);
            int heldId = held != null ? held.itemId() : 0;
            Rules rules = new Rules(weapon -> weaponAllowed(chr, heldId, weapon),
                    weapon -> BotWeapons.takesShield(BotWeapons.kindOf(weapon)));
            Valuer valuer = valuers.apply(worn);
            return pareto() ? BotGearOptimizer.plan(wearer, worn, owned, valuer, rules)
                    : BotGearPlanner.plan(wearer, worn, owned, valuer, rules);
        }
    }

    /*
     * Ticket 162 (dexless AP build, -Dslums.gear.dexless): the base secondary stat this bot's AP should stop at, from
     * BotGearOptimizer.secondaryTarget over every weapon it owns or holds that it may fight with. -1 (no cap) without
     * item data, for a class with no cap (secondaryFloor MAX_VALUE: magicians) or on any error.
     */
    public static int dexlessSecondaryTarget(Character bot,
                                             soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat primary,
                                             soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat secondary,
                                             int floor, int freeAp) {
        if (floor == Integer.MAX_VALUE || !EquipMetadataCache.isInitialized()) {
            return -1;
        }
        try {
            return dexlessSecondaryTarget(bot, primary, secondary, floor, freeAp, liveFacts(bot),
                    weaponId -> BotDamage.gearValue(bot, weaponId));
        } catch (RuntimeException | LinkageError e) {
            return -1;
        }
    }

    /* As above with the item data and the damage per weapon handed in (tests). */
    static int dexlessSecondaryTarget(Character bot,
                                      soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat primary,
                                      soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat secondary,
                                      int floor, int freeAp, IntFunction<Known> facts,
                                      java.util.function.IntFunction<BotDamage.GearValue> damage) {
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        Equip held = body.getItem(BotGearPlanner.WEAPON) instanceof Equip e ? e : null;
        int heldId = held != null ? held.getItemId() : 0;
        int heldWatk = held != null ? held.getWatk() : 0;
        int primaryBase = baseOf(bot, primary);
        int secondaryBase = baseOf(bot, secondary);
        int gearSecondary = totalOf(bot, secondary) - secondaryBase;
        List<Equip> weapons = new ArrayList<>();
        if (held != null) {
            weapons.add(held);
        }
        for (Item item : bot.getInventory(InventoryType.EQUIP).list()) {
            if (item instanceof Equip e && BotWeapons.kindOf(e.getItemId()) != null) {
                weapons.add(e);
            }
        }
        List<BotGearOptimizer.WeaponCand> cands = new ArrayList<>();
        int heldReq = 0;
        for (Equip w : weapons) {
            Known k = facts.apply(w.getItemId());
            if (k == null || k.slot() != BotGearPlanner.WEAPON || k.reqLevel() > bot.getLevel()
                    || (w != held && !weaponAllowed(bot, heldId, w.getItemId()))) {
                continue;
            }
            int req = reqOf(k, secondary);
            if (w == held) {
                heldReq = req;
            }
            BotDamage.GearValue value = damage.apply(w.getItemId());
            int dWatk = w.getWatk() - heldWatk;
            cands.add(new BotGearOptimizer.WeaponCand(req, (p, sec) -> value.of(
                    delta(primary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.STR, p - primaryBase)
                            + delta(secondary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.STR, sec - secondaryBase),
                    delta(primary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.DEX, p - primaryBase)
                            + delta(secondary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.DEX, sec - secondaryBase),
                    delta(primary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.INT, p - primaryBase)
                            + delta(secondary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.INT, sec - secondaryBase),
                    delta(primary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.LUK, p - primaryBase)
                            + delta(secondary, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat.LUK, sec - secondaryBase),
                    dWatk, 0)));
        }
        if (cands.isEmpty()) {
            return -1;
        }
        return BotGearOptimizer.secondaryTarget(floor, primaryBase, secondaryBase, gearSecondary, freeAp, cands,
                heldReq);
    }

    private static int delta(soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat which,
                             soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat dim, int d) {
        return which == dim ? d : 0;
    }

    private static int baseOf(Character c, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat s) {
        return switch (s) {
            case STR -> c.getStr();
            case DEX -> c.getDex();
            case INT -> c.getInt();
            case LUK -> c.getLuk();
        };
    }

    private static int totalOf(Character c, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat s) {
        return switch (s) {
            case STR -> c.getTotalStr();
            case DEX -> c.getTotalDex();
            case INT -> c.getTotalInt();
            case LUK -> c.getTotalLuk();
        };
    }

    private static int reqOf(Known k, soloMapling.ArtificialPlayer.BotProgressionSystem.BotBuild.Stat s) {
        return switch (s) {
            case STR -> k.reqStr();
            case DEX -> k.reqDex();
            case INT -> k.reqInt();
            case LUK -> k.reqLuk();
        };
    }

    private static Piece piece(Equip e, Known known) {
        int sturdiness = e.getWdef() + e.getMdef() + e.getAcc() + e.getAvoid() + e.getHp() + e.getMp()
                + e.getSpeed() + e.getJump() + e.getStr() + e.getDex() + e.getInt() + e.getLuk();
        return new Piece(e, e.getItemId(), e.getPosition(), known, e.getStr(), e.getDex(), e.getInt(), e.getLuk(),
                e.getWatk(), e.getMatk() + e.getInt(), sturdiness, e.getAcc());   // magic attack counts INT, as the server's totals
    }

    /*
     * Moves the equips: whatever a new piece replaces goes to the inventory slot the piece came from, and whatever
     * is taken off without a replacement (pants under an overall, a shield under a two-handed weapon) to a free one.
     * Every piece is checked to still be where the review saw it; a trade or a sale in between stops the moves.
     */
    static boolean apply(Character bot, Map<Short, Piece> worn, Map<Short, Piece> wear) {
        Inventory carried = bot.getInventory(InventoryType.EQUIP);
        Inventory body = bot.getInventory(InventoryType.EQUIPPED);
        boolean changed = false;
        try {
            for (Map.Entry<Short, Piece> e : worn.entrySet()) {
                short slot = e.getKey();
                if (wear.containsKey(slot)) {
                    continue;
                }
                Equip off = (Equip) e.getValue().item();
                short free = carried.getNextFreeSlot();
                if (free < 0 || body.getItem(slot) != off) {
                    return changed;
                }
                body.removeSlot(slot);
                bot.unequippedItem(off);
                off.setPosition(free);
                carried.addItemFromDB(off);
                changed = true;
            }
            for (Map.Entry<Short, Piece> e : wear.entrySet()) {
                short slot = e.getKey();
                Piece piece = e.getValue();
                if (worn.get(slot) == piece) {
                    continue;
                }
                Equip on = (Equip) piece.item();
                short from = piece.position();
                if (carried.getItem(from) != on) {
                    return changed;
                }
                Item off = body.getItem(slot);
                carried.removeSlot(from);
                if (off != null) {
                    body.removeSlot(slot);
                    bot.unequippedItem((Equip) off);
                    off.setPosition(from);
                    carried.addItemFromDB(off);
                }
                markUntradeableOnEquip(on);
                on.setPosition(slot);
                on.wear(true);
                bot.equippedItem(on);
                body.addItemFromDB(on);
                changed = true;
            }
            return changed;
        } finally {
            if (changed && bot.getMap() != null) {
                bot.equipChanged();   // the new look to the map, and the stat totals re-derived
            }
        }
    }

    // As InventoryManipulator.equip: an equip that turns untradeable once worn does so for a bot too.
    private static void markUntradeableOnEquip(Equip equip) {
        boolean untradeable;
        try {
            untradeable = ItemInformationProvider.getInstance().isUntradeableOnEquip(equip.getItemId());
        } catch (LinkageError | RuntimeException noItemData) {
            return;   // headless: no item data, nothing known to lock
        }
        if (untradeable) {
            equip.setFlag((short) (equip.getFlag() | ItemConstants.UNTRADEABLE));
        }
    }

    /*
     * Whether the bot may take up this weapon: one of its job's kinds, drawing the same attacks it draws now with
     * every one of them fitting the new weapon. A beginner and a 1st job keep the kind they hold.
     */
    static boolean weaponAllowed(Character bot, int heldId, int weaponId) {
        Job job = bot.getJob();
        EquipType kind = BotWeapons.kindOf(weaponId);
        if (kind == null || !BotWeapons.fits(job, weaponId)) {
            return false;
        }
        EquipType heldKind = heldId > 0 ? BotWeapons.kindOf(heldId) : null;
        if (BotWeapons.weaponsFor(job).isEmpty()) {
            return heldKind != null ? kind == heldKind : BEGINNER_WEAPONS.contains(kind);
        }
        boolean heldFits = heldKind != null && BotWeapons.fits(job, heldId);
        if (heldFits && job.getId() % 100 == 0) {
            return kind == heldKind;
        }
        IntPredicate owned = skillId -> bot.getSkillLevel(skillId) > 0;
        BotAttackConfig.JobAttacks with = BotAttackConfig.resolve(job, BotDamage.weaponTypeOf(weaponId), owned);
        if (!drawable(with, weaponId)) {
            return false;
        }
        if (!heldFits) {
            return true;
        }
        BotAttackConfig.JobAttacks now = BotAttackConfig.resolve(job, BotDamage.weaponTypeOf(heldId), owned);
        return skillOf(now.single()) == skillOf(with.single()) && skillOf(now.aoe()) == skillOf(with.aoe())
                && skillOf(now.ultimate()) == skillOf(with.ultimate());
    }

    private static boolean drawable(BotAttackConfig.JobAttacks attacks, int weaponId) {
        for (BotAttackProfile p : new BotAttackProfile[]{attacks.single(), attacks.aoe(), attacks.ultimate()}) {
            if (p != null && !BotSkillActions.weaponFits(p.skillId, weaponId)) {
                return false;
            }
        }
        return true;
    }

    private static int skillOf(BotAttackProfile p) {
        return p == null ? -1 : p.skillId;
    }

    /* The item data's answer for one equip, or null for one this bot must not be dressed in. */
    private static IntFunction<Known> liveFacts(Character bot) {
        EquipMetadataCache cache = EquipMetadataCache.get();
        ItemInformationProvider itemData = ItemInformationProvider.getInstance();
        int gender = bot.getGender();
        int rebirths = bot.getReborns();
        return itemId -> {
            EquipMetadataCache.EquipEntry entry = cache.find(itemId);
            if (entry == null || entry.cash || (entry.gender != 2 && entry.gender != gender)
                    || EquipOmitList.isOmitted(itemId)
                    || itemData.getIsItemRestrictedByPlayerReborns(rebirths, itemId)) {
                return null;
            }
            Map<String, Integer> stats = itemData.getEquipStats(itemId);
            int slotType = ItemConstants.getEquipSlotType(itemId);
            if (stats == null || slotType <= 0) {
                return null;
            }
            return new Known((short) -slotType, entry.reqLevel, entry.reqJob, stats.getOrDefault("reqSTR", 0),
                    stats.getOrDefault("reqDEX", 0), stats.getOrDefault("reqINT", 0), stats.getOrDefault("reqLUK", 0),
                    stats.getOrDefault("reqPOP", 0));
        };
    }

    /*
     * The bot's own damage formula (BotDamage.gearValue: its totals, mastery, skills and shown buffs), fed how far
     * each loadout's gear totals are from what it wears now. One yardstick per weapon weighed.
     */
    private static Valuer liveValuer(Character bot, Map<Short, Piece> worn) {
        int[] now = new int[6];
        for (Piece p : worn.values()) {
            now[0] += p.str();
            now[1] += p.dex();
            now[2] += p.int_();
            now[3] += p.luk();
            now[4] += p.watk();
            now[5] += p.matk();
        }
        Map<Integer, BotDamage.GearValue> byWeapon = new HashMap<>();
        return (weaponId, str, dex, int_, luk, watk, matk) ->
                byWeapon.computeIfAbsent(weaponId, id -> BotDamage.gearValue(bot, id))
                        .of(str - now[0], dex - now[1], int_ - now[2], luk - now[3], watk - now[4], matk - now[5]);
    }
}
