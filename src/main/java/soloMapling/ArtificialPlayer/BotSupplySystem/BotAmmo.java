package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.Job;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import constants.skills.Crossbowman;
import constants.skills.Hermit;
import constants.skills.Hunter;
import constants.skills.NightLord;
import constants.skills.NightWalker;
import constants.skills.WindArcher;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffConfig;
import soloMapling.ArtificialPlayer.BotHoldings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Real bots 08: a bot's arrows, bolts, stars and bullets, by the rules a player's ranged attack follows
 * (RangedAttackHandler): the first USE slot that fits the weapon, is allowed at the bot's level and holds enough for
 * the swing is what it fires; a swing fires the skill's bulletCount (bulletConsume when the skill sets one), twice
 * that under Shadow Partner; Soul Arrow needs no arrows at all; Shadow Stars still needs a stack but throws none of
 * it. Stars and bullets stay in their slot at 0 to be recharged.
 *
 * A bot's shown buffs count as up while it holds them, as everywhere else in the bot attack code (ticket 07): bot
 * buffs are drawn, not registered.
 */
public final class BotAmmo {

    private BotAmmo() {}

    // Skills the server lets through with no projectile at all (RangedAttackHandler).
    private static final Set<Integer> NO_PROJECTILE_SKILLS = Set.of(11101004, 15111007, 14101006, 13101005);

    // botId -> the projectile attack the bot's stat totals were last worked out with (see syncStats).
    private static final Map<Integer, Integer> statsAmmoWatk = new ConcurrentHashMap<>();

    /* True for the weapons that fire ammo. */
    public static boolean usesAmmo(WeaponType weapon) {
        return weapon == WeaponType.BOW || weapon == WeaponType.CROSSBOW || weapon == WeaponType.CLAW
                || weapon == WeaponType.GUN;
    }

    /* Whether this item is ammo the weapon fires at this level (RangedAttackHandler's rules). */
    static boolean fits(WeaponType weapon, int itemId, int level) {
        if (weapon == null) {
            return false;
        }
        return switch (weapon) {
            case CLAW -> ItemConstants.isThrowingStar(itemId)
                    && !(((itemId == ItemId.HWABI_THROWING_STARS || itemId == ItemId.BALANCED_FURY) && level < 70)
                    || (itemId == ItemId.CRYSTAL_ILBI_THROWING_STARS && level < 50));
            case GUN -> ItemConstants.isBullet(itemId)
                    && (itemId == ItemId.BLAZE_CAPSULE || itemId == ItemId.GLAZE_CAPSULE
                    ? level >= 70 : level > (itemId % 10) * 20 + 9);
            case BOW -> ItemConstants.isArrowForBow(itemId);
            case CROSSBOW -> ItemConstants.isArrowForCrossBow(itemId);
            default -> false;
        };
    }

    /* Whether the server's stat totals count this item's attack for the weapon (Character.reapplyLocalStats). */
    private static boolean countsForStats(WeaponType weapon, int itemId) {
        return switch (weapon) {
            case CLAW -> ItemConstants.isThrowingStar(itemId);
            case GUN -> ItemConstants.isBullet(itemId);
            case BOW -> ItemConstants.isArrowForBow(itemId);
            case CROSSBOW -> ItemConstants.isArrowForCrossBow(itemId);
            default -> false;
        };
    }

    /* Soul Arrow is up (shown and held): bows and crossbows fire without arrows. */
    public static boolean soulArrow(Character bot, WeaponType weapon) {
        if (weapon != WeaponType.BOW && weapon != WeaponType.CROSSBOW) {
            return false;
        }
        return shows(bot, Hunter.SOUL_ARROW) || shows(bot, Crossbowman.SOUL_ARROW) || shows(bot, WindArcher.SOUL_ARROW);
    }

    /* Shadow Stars is up: the claw still needs a stack of stars but throws none of it. */
    static boolean shadowStars(Character bot, WeaponType weapon) {
        return weapon == WeaponType.CLAW && shows(bot, NightLord.SHADOW_STARS)
                && shadowStarsUntil.getOrDefault(bot.getId(), 0L) > System.currentTimeMillis();
    }

    // Shadow Stars' cast costs its bulletConsume (200 stars) and lasts its duration; the WZ numbers stand in when
    // the data is not loaded.
    static final int SHADOW_STARS_COST = 200;
    static final int SHADOW_STARS_MS = 200_000;

    /* The stars one cast of Shadow Stars costs, and how long it lasts (ms), at the level the bot holds it. */
    static int shadowStarsCost(Character bot) {
        int level = bot.getSkillLevel(NightLord.SHADOW_STARS);
        int cost = level > 0 ? SupplyData.get().bulletConsume(NightLord.SHADOW_STARS, level) : 0;
        return cost > 0 ? cost : SHADOW_STARS_COST;
    }

    static int shadowStarsMs(Character bot) {
        int level = bot.getSkillLevel(NightLord.SHADOW_STARS);
        int ms = level > 0 ? SupplyData.get().duration(NightLord.SHADOW_STARS, level) : 0;
        return ms > 0 ? ms : SHADOW_STARS_MS;
    }

    // botId -> epoch-ms the bot's paid-for Shadow Stars runs out.
    private static final Map<Integer, Long> shadowStarsUntil = new ConcurrentHashMap<>();

    /*
     * A Night Lord showing Shadow Stars recasts it when it runs out, paying the 200 stars from one stack the way the
     * server takes them for a player's cast; a bot without a stack that big throws its stars as normal until it has.
     */
    static void keepShadowStars(Character bot, WeaponType weapon, long nowMs) {
        if (weapon != WeaponType.CLAW || !shows(bot, NightLord.SHADOW_STARS)
                || shadowStarsUntil.getOrDefault(bot.getId(), 0L) > nowMs) {
            return;
        }
        int cost = shadowStarsCost(bot);
        for (Item item : stacks(bot, weapon)) {
            if (item.getQuantity() >= cost && BotHoldings.consumeHeld(bot, item, cost) == cost) {
                shadowStarsUntil.put(bot.getId(), nowMs + shadowStarsMs(bot));
                return;
            }
        }
    }

    /* Whether the bot would keep Shadow Stars up (it shows the buff); offscreen pays its cost as a rate. */
    static boolean showsShadowStars(Character bot, WeaponType weapon) {
        return weapon == WeaponType.CLAW && shows(bot, NightLord.SHADOW_STARS);
    }

    private static boolean shadowPartner(Character bot) {
        return shows(bot, Hermit.SHADOW_PARTNER) || shows(bot, NightWalker.SHADOW_PARTNER);
    }

    private static boolean shows(Character bot, int buffId) {
        return BotBuffConfig.buffsFor(bot).contains(buffId) && bot.getSkillLevel(buffId) > 0;
    }

    /* True when a swing of this skill with this weapon draws ammo from the inventory at all. */
    public static boolean needsAmmo(Character bot, WeaponType weapon, int skillId) {
        return usesAmmo(weapon) && !NO_PROJECTILE_SKILLS.contains(skillId) && !soulArrow(bot, weapon);
    }

    /* How many a swing needs in its stack: the skill's bulletCount (1 for a plain shot), doubled by Shadow Partner. */
    static int neededPerSwing(Character bot, int skillId, int skillLevel) {
        int count = skillId == 0 ? 1 : Math.max(1, SupplyData.get().bulletCount(skillId, skillLevel));
        return shadowPartner(bot) ? count * 2 : count;
    }

    /* How many a swing uses up: bulletConsume when the skill sets one, else what it needs; none under Shadow Stars. */
    public static int usedPerSwing(Character bot, WeaponType weapon, int skillId, int skillLevel) {
        if (!needsAmmo(bot, weapon, skillId) || shadowStars(bot, weapon)) {
            return 0;
        }
        int consume = skillId == 0 ? 0 : SupplyData.get().bulletConsume(skillId, skillLevel);
        if (consume > 0) {
            return shadowPartner(bot) ? consume * 2 : consume;
        }
        return neededPerSwing(bot, skillId, skillLevel);
    }

    /* The stack a swing of this skill fires from, or null when the bot has none it can use. */
    public static Item usable(Character bot, WeaponType weapon, int skillId, int skillLevel) {
        int needed = neededPerSwing(bot, skillId, skillLevel);
        Inventory use = bot.getInventory(InventoryType.USE);
        use.lockInventory();
        try {
            for (short slot = 1; slot <= use.getSlotLimit(); slot++) {
                Item item = use.getItem(slot);
                if (item != null && item.getQuantity() >= needed && fits(weapon, item.getItemId(), bot.getLevel())) {
                    return item;
                }
            }
            return null;
        } finally {
            use.unlockInventory();
        }
    }

    /* Every stack the weapon can fire at the bot's level, in slot order (rechargeable ones at 0 included). */
    static List<Item> stacks(Character bot, WeaponType weapon) {
        List<Item> out = new ArrayList<>();
        Inventory use = bot.getInventory(InventoryType.USE);
        use.lockInventory();
        try {
            for (short slot = 1; slot <= use.getSlotLimit(); slot++) {
                Item item = use.getItem(slot);
                if (item != null && fits(weapon, item.getItemId(), bot.getLevel())) {
                    out.add(item);
                }
            }
        } finally {
            use.unlockInventory();
        }
        return out;
    }

    /* How many the bot holds that the weapon can fire. */
    public static int held(Character bot, WeaponType weapon) {
        int total = 0;
        for (Item item : stacks(bot, weapon)) {
            total += item.getQuantity();
        }
        return total;
    }

    /*
     * Fires {@code count} from the bot's stacks, first slot first (a long offscreen stretch runs through more than
     * one stack). Returns how many were actually there to fire.
     */
    static int fire(Character bot, WeaponType weapon, int count) {
        int fired = 0;
        for (Item item : stacks(bot, weapon)) {
            if (fired >= count) {
                break;
            }
            fired += BotHoldings.consumeHeld(bot, item, count - fired);
        }
        return fired;
    }

    /*
     * Keeps the bot's stat totals in step with the ammo it carries. The server counts the first fitting stack's
     * attack into the totals (Character.reapplyLocalStats) but only re-derives them on a stat or equip change, and
     * a player's empty stack keeps counting until then; the bot formula (BotDamage, ticket 07) reads the totals, so
     * when the ammo they would count changes - a stack runs out, the first ammo is looted - they are re-derived.
     */
    public static void syncStats(Character bot, WeaponType weapon) {
        int now = statsWatk(bot, weapon);
        Integer before = statsAmmoWatk.put(bot.getId(), now);
        if (before == null || before != now) {
            try {
                bot.refreshStatsAfterProgression();
            } catch (LinkageError | RuntimeException noItemData) {
                statsAmmoWatk.remove(bot.getId());   // not re-derived: try again next time
            }
        }
    }

    /* The projectile attack Character.reapplyLocalStats would count now: the first fitting stack holding any. */
    static int statsWatk(Character bot, WeaponType weapon) {
        Job job = bot.getJob();
        if (!usesAmmo(weapon) || job == null || !(job.isA(Job.THIEF) || job.isA(Job.BOWMAN) || job.isA(Job.PIRATE)
                || job.isA(Job.NIGHTWALKER1) || job.isA(Job.WINDARCHER1))) {
            return 0;
        }
        Inventory use = bot.getInventory(InventoryType.USE);
        use.lockInventory();
        try {
            for (short slot = 1; slot <= use.getSlotLimit(); slot++) {
                Item item = use.getItem(slot);
                if (item != null && countsForStats(weapon, item.getItemId()) && item.getQuantity() > 0) {
                    return SupplyData.get().projectileWatk(item.getItemId());
                }
            }
            return 0;
        } finally {
            use.unlockInventory();
        }
    }

    static void clearBot(int botId) {
        statsAmmoWatk.remove(botId);
        shadowStarsUntil.remove(botId);
    }
}
