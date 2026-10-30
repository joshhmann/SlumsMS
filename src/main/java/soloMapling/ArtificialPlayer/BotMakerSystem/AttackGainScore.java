package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.Character;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotDamage;

import java.util.Map;

/*
 * Ticket 112: the maker plan's stand-in scorer until 162-gearopt's GearScoring lands (see MakerGearScore). It values a
 * candidate equip as a straight swap for whatever the character wears in that slot, on the bot damage yardstick
 * (BotDamage.gearValue: the character's own main attack, mastery and buffs). A weapon is valued while held; anything
 * else with the weapon the character holds. Simpler than the optimizer: it does not re-plan the other slots (an
 * overall against a top and bottom, a two-hander against a shield). Never moves an item.
 */
final class AttackGainScore implements MakerGearScore {

    static final AttackGainScore INSTANCE = new AttackGainScore();

    private AttackGainScore() {
    }

    @Override
    public double upgradeValue(Character chr, Equip candidate) {
        if (chr == null || candidate == null || !wearable(chr, candidate.getItemId())) {
            return 0;
        }
        try {
            short slot = (short) -ItemConstants.getEquipSlotType(candidate.getItemId());
            if (slot >= 0) {
                return 0;
            }
            Item worn = chr.getInventory(InventoryType.EQUIPPED).getItem(slot);
            Equip now = worn instanceof Equip e ? e : null;
            Item held = chr.getInventory(InventoryType.EQUIPPED).getItem((short) -11);
            int heldId = held != null ? held.getItemId() : 0;
            boolean weapon = slot == -11;
            int[] d = delta(candidate, now);
            double gain;
            if (weapon) {
                BotDamage.GearValue before = BotDamage.gearValue(chr, heldId);
                BotDamage.GearValue after = BotDamage.gearValue(chr, candidate.getItemId());
                gain = after.of(d[0], d[1], d[2], d[3], d[4], d[5]) - before.of(0, 0, 0, 0, 0, 0);
            } else {
                BotDamage.GearValue value = BotDamage.gearValue(chr, heldId);
                gain = value.of(d[0], d[1], d[2], d[3], d[4], d[5]) - value.of(0, 0, 0, 0, 0, 0);
            }
            return Double.isFinite(gain) ? Math.max(0, gain) : 0;
        } catch (RuntimeException | LinkageError e) {
            return 0;   // no item or skill data: no answer is no upgrade
        }
    }

    /* STR, DEX, INT, LUK, attack, magic attack: candidate minus what is worn there now. */
    static int[] delta(Equip candidate, Equip now) {
        int[] d = {candidate.getStr(), candidate.getDex(), candidate.getInt(), candidate.getLuk(), candidate.getWatk(),
                candidate.getMatk()};
        if (now != null) {
            d[0] -= now.getStr();
            d[1] -= now.getDex();
            d[2] -= now.getInt();
            d[3] -= now.getLuk();
            d[4] -= now.getWatk();
            d[5] -= now.getMatk();
        }
        return d;
    }

    /* The item's level and job requirements (stat requirements are left to the real equip, which checks them). */
    static boolean wearable(Character chr, int itemId) {
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        Map<String, Integer> stats = ii.getEquipStats(itemId);
        if (stats == null || ii.isCash(itemId)) {
            return false;
        }
        if (ii.getEquipLevelReq(itemId) > chr.getLevel()) {
            return false;
        }
        return jobFits(stats.getOrDefault("reqJob", 0), chr.getJob().getJobNiche());
    }

    /* reqJob is a bit mask (0 = anyone; 1 warrior, 2 magician, 4 bowman, 8 thief, 16 pirate); niche = job / 100 % 10. */
    static boolean jobFits(int reqJob, int niche) {
        if (reqJob == 0) {
            return true;
        }
        if (reqJob < 0) {
            return niche == 0;   // -1: beginners only
        }
        return niche >= 1 && niche <= 5 && (reqJob & (1 << (niche - 1))) != 0;
    }
}
