package soloMapling.ArtificialPlayer.BotGearSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import server.ItemInformationProvider;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 1.6.2 ticket 99 / ticket 162 part 3: upgrade sharing - after nutnnut/Cosmic {@code BotOfferManager}: a companion
 * weighs its spare gear against what the owner and its sibling alts wear AND carry, and offers each piece to whoever
 * it helps most ("any upgrades?", "trade upgrades"); "request?" weighs the owner's spare gear against the companion.
 *
 * <p>"Better" is one question behind one small interface, {@link Scorer}: how much a wearer gains if it may also
 * pick this piece. The default answers through {@link GearScoring#live()}, the one scorer population bots dress by
 * (owner rule 2026-10-30: one implementation for bots and alts); {@link #install} swaps in another (tests).
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
        return s != null ? s : LIVE;
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
     * The default: the one gear scorer bots dress by ({@link GearScoring#live()}, ticket 162; the planner
     * {@code -Dslums.gear.optimizer} picks). The candidate is an upgrade when the wearer's best loadout with it added
     * wears it; the gain is the scorer's damage gain (a defence piece the tie-break chose counts
     * {@link #DEFENCE_ONLY_GAIN}).
     */
    static Scorer from(GearScoring scoring) {
        return (wearer, candidate) -> {
            if (wearer == null || candidate == null
                    || !scoring.bestSet(wearer, List.of(candidate)).containsValue(candidate)) {
                return 0;
            }
            double gain = scoring.upgradeValue(wearer, candidate);
            return gain > 1e-9 ? gain : DEFENCE_ONLY_GAIN;
        };
    }

    /** The live default, over {@link GearScoring#live()}. */
    static final Scorer LIVE = from(GearScoring.live());
}
