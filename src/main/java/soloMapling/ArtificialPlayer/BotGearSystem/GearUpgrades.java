package soloMapling.ArtificialPlayer.BotGearSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDamage;
import soloMapling.ArtificialPlayer.BotDecoratorSystem.BotWeapons;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Known;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Piece;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Plan;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Rules;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Valuer;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGearPlanner.Wearer;
import soloMapling.itemPool.EquipMetadataCache;
import soloMapling.itemPool.EquipOmitList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.IntFunction;

/**
 * 1.6.2 ticket 99 / ticket 162 part 3: upgrade sharing - after nutnnut/Cosmic {@code BotOfferManager}: a companion
 * weighs its spare gear against what the owner and its sibling alts wear AND carry, and offers each piece to whoever
 * it helps most ("any upgrades?", "trade upgrades"); "request?" weighs the owner's spare gear against the companion.
 *
 * <p>"Better" is one question behind one small interface, {@link Scorer}: how much a wearer gains if it may also
 * pick this piece. The default answers with our gear optimizer as it is ({@link BotGearPlanner}, the same planner,
 * yardstick and weapon rules {@link BotGear} dresses bots with); ticket 162's new optimizer (branch 162-gearopt)
 * plugs in with {@link #install} and nothing here changes. BotGear and BotGearPlanner are not touched.
 */
public final class GearUpgrades {

    /** How much better a wearer gets from a piece, with everything it already wears and carries counted first. */
    public interface Scorer {
        /** Above 0: the wearer would put {@code candidate} on (the larger, the better); 0 or less: it would not. */
        double gain(Character wearer, Equip candidate);
    }

    /** One offer: this spare piece to this character, for this gain. */
    public record Offer(Equip item, Character to, double gain) {
    }

    /** A piece that only adds defence still counts as an upgrade (the planner's sturdiness tie-break picked it). */
    static final double DEFENCE_ONLY_GAIN = 0.01;

    private static volatile Scorer scorer;

    private GearUpgrades() {
    }

    /** Ticket 162: a new optimizer answers "is this better?" from now on (null = back to the default). */
    public static void install(Scorer s) {
        scorer = s;
    }

    /** The scorer in use. */
    public static Scorer scorer() {
        Scorer s = scorer;
        return s != null ? s : Planner.LIVE;
    }

    /**
     * What of {@code giver}'s spare equips (its equip tab, tradeable ones) helps whom among {@code recipients}: each
     * piece goes to the recipient it helps most, best first. Empty when nothing helps anyone.
     */
    public static List<Offer> offers(Character giver, List<Character> recipients) {
        return offers(spares(giver), recipients, scorer());
    }

    static List<Offer> offers(List<Equip> spares, List<Character> recipients, Scorer s) {
        List<Offer> out = new ArrayList<>();
        for (Equip piece : spares) {
            Offer best = null;
            for (Character to : recipients) {
                if (to == null) {
                    continue;
                }
                double gain;
                try {
                    gain = s.gain(to, piece);
                } catch (RuntimeException e) {
                    continue; // missing item data for one piece never stops the rest
                }
                if (gain > 0 && (best == null || gain > best.gain())) {
                    best = new Offer(piece, to, gain);
                }
            }
            if (best != null) {
                out.add(best);
            }
        }
        out.sort(Comparator.comparingDouble(Offer::gain).reversed());
        return out;
    }

    /** The equips in a character's equip tab that could go into a trade window (no cash, no untradeable). */
    public static List<Equip> spares(Character c) {
        List<Equip> out = new ArrayList<>();
        Inventory bag = c.getInventory(InventoryType.EQUIP);
        bag.lockInventory();
        try {
            for (Item item : bag.list()) {
                if (item instanceof Equip e && tradeable(e)) {
                    out.add(e);
                }
            }
        } finally {
            bag.unlockInventory();
        }
        out.sort(Comparator.comparingInt(Item::getPosition));
        return out;
    }

    private static boolean tradeable(Equip e) {
        try {
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            return !ii.isUnmerchable(e.getItemId()) && !(ii.isDropRestricted(e.getItemId())
                    && !client.inventory.manipulator.KarmaManipulator.hasKarmaFlag(e)) && !e.isUntradeable();
        } catch (RuntimeException | LinkageError noData) {
            return !e.isUntradeable();
        }
    }

    /**
     * The default: our planner, asked twice - with what the wearer has, and with the candidate added. The candidate
     * is an upgrade when the second loadout wears it; the gain is the planner's damage difference (a defence piece
     * the tie-break chose counts {@link #DEFENCE_ONLY_GAIN}).
     */
    static final class Planner implements Scorer {

        static final Planner LIVE = new Planner(null, null);

        private final IntFunction<Known> facts;
        private final BiFunction<Character, Map<Short, Piece>, Valuer> valuers;

        Planner(IntFunction<Known> facts, BiFunction<Character, Map<Short, Piece>, Valuer> valuers) {
            this.facts = facts;
            this.valuers = valuers;
        }

        @Override
        public double gain(Character wearer, Equip candidate) {
            if (wearer == null || candidate == null) {
                return 0;
            }
            IntFunction<Known> known = facts != null ? facts : liveFacts(wearer);
            if (known == null || known.apply(candidate.getItemId()) == null) {
                return 0;
            }
            Map<Short, Piece> worn = new LinkedHashMap<>();
            Inventory body = wearer.getInventory(InventoryType.EQUIPPED);
            body.lockInventory();
            try {
                for (short slot : BotGearPlanner.SLOTS) {
                    if (body.getItem(slot) instanceof Equip e) {
                        worn.put(slot, piece(e, known.apply(e.getItemId())));
                    }
                }
            } finally {
                body.unlockInventory();
            }
            List<Piece> carried = new ArrayList<>();
            Inventory bag = wearer.getInventory(InventoryType.EQUIP);
            bag.lockInventory();
            try {
                for (Item item : bag.list()) {
                    if (item instanceof Equip e && e != candidate) {
                        Known k = known.apply(e.getItemId());
                        if (k != null) {
                            carried.add(piece(e, k));
                        }
                    }
                }
            } finally {
                bag.unlockInventory();
            }
            Wearer w = new Wearer(wearer.getJob().getJobNiche(), wearer.getLevel(), wearer.getFame(),
                    wearer.getStr(), wearer.getDex(), wearer.getInt(), wearer.getLuk(), 24);
            Piece held = worn.get(BotGearPlanner.WEAPON);
            int heldId = held != null ? held.itemId() : 0;
            Rules rules = new Rules(weapon -> BotGear.weaponAllowed(wearer, heldId, weapon),
                    weapon -> BotWeapons.takesShield(BotWeapons.kindOf(weapon)));
            Valuer valuer = valuers != null ? valuers.apply(wearer, worn) : liveValuer(wearer, worn);

            Plan without = BotGearPlanner.plan(w, worn, carried, valuer, rules);
            List<Piece> withIt = new ArrayList<>(carried);
            Piece candidatePiece = piece(candidate, known.apply(candidate.getItemId()));
            withIt.add(candidatePiece);
            Plan with = BotGearPlanner.plan(w, worn, withIt, valuer, rules);
            if (with == null || !with.wear().containsValue(candidatePiece)) {
                return 0;
            }
            double base = without != null ? without.after() : with.before();
            double gain = with.after() - base;
            return gain > 1e-9 ? gain : DEFENCE_ONLY_GAIN;
        }

        /*
         * Item facts and the yardstick as BotGear's own (liveFacts, liveValuer, piece - private there; ticket 162
         * may make them package-visible, then these three go).
         */
        static Piece piece(Equip e, Known known) {
            int sturdiness = e.getWdef() + e.getMdef() + e.getAcc() + e.getAvoid() + e.getHp() + e.getMp()
                    + e.getSpeed() + e.getJump() + e.getStr() + e.getDex() + e.getInt() + e.getLuk();
            return new Piece(e, e.getItemId(), e.getPosition(), known, e.getStr(), e.getDex(), e.getInt(), e.getLuk(),
                    e.getWatk(), e.getMatk() + e.getInt(), sturdiness);
        }

        private static IntFunction<Known> liveFacts(Character wearer) {
            if (!EquipMetadataCache.isInitialized()) {
                return null;
            }
            EquipMetadataCache cache = EquipMetadataCache.get();
            ItemInformationProvider itemData = ItemInformationProvider.getInstance();
            int gender = wearer.getGender();
            int rebirths = wearer.getReborns();
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
                        stats.getOrDefault("reqDEX", 0), stats.getOrDefault("reqINT", 0),
                        stats.getOrDefault("reqLUK", 0), stats.getOrDefault("reqPOP", 0));
            };
        }

        private static Valuer liveValuer(Character wearer, Map<Short, Piece> worn) {
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
                    byWeapon.computeIfAbsent(weaponId, id -> BotDamage.gearValue(wearer, id))
                            .of(str - now[0], dex - now[1], int_ - now[2], luk - now[3], watk - now[4], matk - now[5]);
        }
    }
}
