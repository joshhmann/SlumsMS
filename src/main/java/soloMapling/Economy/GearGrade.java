package soloMapling.Economy;

import client.inventory.Equip;
import constants.inventory.ItemConstants;
import soloMapling.FreeMarket.EquipValuation;

/**
 * 1.5.0 rc3: how good one equip is next to a clean one of its kind, as plain functions over
 * {@link EquipValuation.Facts} so the rules are testable without the item data.
 * <ul>
 *   <li><b>Godly</b> (bots never sell it; owner decision 1): more attack than 60% scrolls on every slot can give, or
 *   more main stats. Gear audit 2026-10-21 (docs/release/1.5.0-economy-trade.md): attack gain over the clean WZ stats
 *   above {@code 2 x slots} ({@code + 5} on weapons for the drop roll), or main-stat gain above
 *   {@code 2 x slots + 2}. Only 10%/30% passes or white scrolls get past it; the audit's simulation puts 0.3-0.7% of
 *   bot-scrolled items there. Slots are the item's WZ slots plus hammers.</li>
 *   <li><b>Above average</b> (bots buy it from players; owner decision 2): not godly, and
 *   {@code statGain + 3 x attackGain} at least {@code gear.aboveAverageScore} (3), with no slot burned by a failed
 *   scroll.</li>
 * </ul>
 * Every threshold is in economy.yaml under {@code gear.}.
 */
public final class GearGrade {

    public enum Grade { BELOW, AVERAGE, ABOVE, GODLY }

    private GearGrade() {
    }

    public static Grade grade(int itemId, EquipValuation.Facts f) {
        if (f == null) {
            return Grade.AVERAGE;
        }
        if (godly(itemId, f)) {
            return Grade.GODLY;
        }
        int score = f.statGain() + 3 * f.attackGain();
        int slots = Math.max(0, f.baseSlots()) + Math.max(0, f.hammers());
        int burned = Math.max(0, slots - Math.max(0, f.slotsLeft()) - Math.max(0, f.scrollsPassed()));
        if (burned == 0 && score >= EconomyConfig.getInt("gear.aboveAverageScore", 3)) {
            return Grade.ABOVE;
        }
        if (score < 0 || burned > 0) {
            return Grade.BELOW;
        }
        return Grade.AVERAGE;
    }

    public static boolean godly(int itemId, EquipValuation.Facts f) {
        if (f == null) {
            return false;
        }
        int slots = Math.max(0, f.baseSlots()) + Math.max(0, f.hammers());
        int perSlotAtt = EconomyConfig.getInt("gear.godlyAttackPerSlot", 2);
        int weaponAllowance = ItemConstants.isWeapon(itemId) ? EconomyConfig.getInt("gear.godlyWeaponAllowance", 5) : 0;
        int attLine = perSlotAtt * slots + weaponAllowance;
        int statLine = EconomyConfig.getInt("gear.godlyStatPerSlot", 2) * slots
                + EconomyConfig.getInt("gear.godlyStatAllowance", 2);
        return f.attackGain() > attLine || f.statGain() > statLine;
    }

    public static Grade grade(Equip equip) {
        try {
            return grade(equip.getItemId(), EquipValuation.factsOf(equip));
        } catch (RuntimeException | LinkageError noItemData) {
            return Grade.AVERAGE;
        }
    }

    public static boolean godly(Equip equip) {
        return grade(equip) == Grade.GODLY;
    }

    /**
     * This equip's value next to a clean one of the same id with every slot free ({@link EquipValuation}), clamped to
     * 0.5..4: the factor between an equip's price and the board's clean-equivalent price.
     */
    public static double quality(Equip equip) {
        try {
            EquipValuation.Facts f = EquipValuation.factsOf(equip);
            return quality(f);
        } catch (RuntimeException | LinkageError noItemData) {
            return 1.0;
        }
    }

    static double quality(EquipValuation.Facts f) {
        if (f == null) {
            return 1.0;
        }
        Integer mine = EquipValuation.estimate(f);
        Integer clean = EquipValuation.estimate(new EquipValuation.Facts(f.reqLevel(), f.npcPrice(), f.baseSlots(),
                f.baseSlots(), 0, 0, 0, 0, 0, false, false));
        if (mine == null || clean == null || clean <= 0) {
            return 1.0;
        }
        return Math.max(0.5, Math.min(4.0, mine / (double) clean));
    }
}
