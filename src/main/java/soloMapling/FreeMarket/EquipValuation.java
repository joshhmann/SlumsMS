package soloMapling.FreeMarket;

import client.inventory.Equip;
import server.ItemInformationProvider;

import java.util.Map;

/**
 * A "normal value" for an equip that the YAML item pool does not price (1.4.2 economy lane).
 *
 * <p>The item pool prices only the equips people chase (and their scrolled variants). Every other equip came back
 * with no value, so an FM shopper bot skipped it with "no normal value" and never bought an equip from anyone. This
 * gives such an equip a deliberately modest value built from what the item itself says:
 * <ul>
 *   <li>a base from the required level ({@code 25 * (reqLevel + 10)^2}: 2,500 at level 0, 32,000 at 30,
 *       128,000 at 70, 302,500 at 100), never below twice the NPC sell price;</li>
 *   <li>stats above the item's clean WZ stats: each point of STR/DEX/INT/LUK adds 6% of the base plus 30,000, each
 *       point of weapon or magic attack 20% plus 100,000, and every 10 HP/MP 1% plus 2,000;</li>
 *   <li>stats below the clean ones take up to half the value off;</li>
 *   <li>each upgrade slot left adds 5% of the base; each slot burned by a failed scroll takes 10% off.</li>
 * </ul>
 * The result is capped at {@link #MAX_VALUE}. Cash and untradeable equips get no value: they cannot be traded.
 * A bot still buys only at or under this value from a real player (ticket 56A's 1.0x ceiling), so a modest value
 * keeps the bot purse safe from a "list junk at book value" farm.
 */
public final class EquipValuation {

    /** The most an unlisted equip can be worth to a bot. */
    public static final int MAX_VALUE = 8_000_000;

    private EquipValuation() {
    }

    /** What the item and its WZ entry say about one equip; plain numbers so the formula is testable without WZ. */
    public record Facts(int reqLevel, int npcPrice, int baseSlots, int slotsLeft, int scrollsPassed, int hammers,
                        int statGain, int attackGain, int hpMpGain, boolean cash, boolean untradeable) {
    }

    /** The normal value of an equip from its facts, or {@code null} when it cannot be traded. */
    public static Integer estimate(Facts f) {
        if (f == null || f.cash() || f.untradeable()) {
            return null;
        }
        long lvl = Math.max(0, f.reqLevel());
        double base = 25.0 * (lvl + 10) * (lvl + 10);
        base = Math.max(base, 2.0 * Math.max(0, f.npcPrice()));

        double multiplier = 1.0;
        double flat = 0;
        int stat = f.statGain(), att = f.attackGain(), hpmp = f.hpMpGain();
        if (stat > 0) {
            multiplier += 0.06 * stat;
            flat += 30_000.0 * stat;
        }
        if (att > 0) {
            multiplier += 0.20 * att;
            flat += 100_000.0 * att;
        }
        if (hpmp > 0) {
            multiplier += 0.01 * (hpmp / 10);
            flat += 2_000.0 * (hpmp / 10);
        }
        int loss = Math.max(0, -stat) + 3 * Math.max(0, -att) + Math.max(0, -hpmp) / 10;
        multiplier -= Math.min(0.5, 0.05 * loss);

        int slotsLeft = Math.max(0, f.slotsLeft());
        int burned = Math.max(0, f.baseSlots() + Math.max(0, f.hammers()) - slotsLeft - Math.max(0, f.scrollsPassed()));
        multiplier += 0.05 * slotsLeft;
        multiplier -= Math.min(0.5, 0.10 * burned);
        multiplier = Math.max(0.25, multiplier);

        double value = base * multiplier + flat;
        return (int) Math.max(1, Math.min(MAX_VALUE, Math.round(value)));
    }

    /** The normal value of a live equip, read from the item data; {@code null} when the data is missing. */
    public static Integer valueOf(Equip equip) {
        Facts facts = factsOf(equip);
        return facts == null ? null : estimate(facts);
    }

    public static Facts factsOf(Equip equip) {
        ItemInformationProvider ii;
        try {
            ii = ItemInformationProvider.getInstance();
        } catch (LinkageError | RuntimeException noItemData) {
            return null;
        }
        int id = equip.getItemId();
        Map<String, Integer> base = ii.getEquipStats(id);
        if (base == null) {
            return null;
        }
        boolean untradeable;
        try {
            untradeable = ii.isUntradeableRestricted(id) || ii.isAccountRestricted(id) || ii.isQuestItem(id);
        } catch (RuntimeException unknown) {
            untradeable = false;
        }
        int npc = Math.max(0, ii.getWholePrice(id));
        int statGain = gain(equip.getStr(), base, "STR") + gain(equip.getDex(), base, "DEX")
                + gain(equip.getInt(), base, "INT") + gain(equip.getLuk(), base, "LUK");
        int attGain = gain(equip.getWatk(), base, "PAD") + gain(equip.getMatk(), base, "MAD");
        int hpMpGain = gain(equip.getHp(), base, "MHP") + gain(equip.getMp(), base, "MMP");
        return new Facts(base.getOrDefault("reqLevel", 0), npc, base.getOrDefault("tuc", 0),
                equip.getUpgradeSlots(), equip.getLevel(), equip.getVicious(), statGain, attGain, hpMpGain,
                base.getOrDefault("cash", 0) != 0 || ii.isCash(id), untradeable);
    }

    private static int gain(int value, Map<String, Integer> base, String key) {
        return value - base.getOrDefault(key, 0);
    }
}
