package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotHoldings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/*
 * Real bots ticket 09: the one-time starter supply a bot gets on the 1.3.0 upgrade (or when it is minted).
 *
 * Before ticket 08 a bot's potions and ammo were never tracked, so in effect it had all it wanted; stored bots hold
 * none. Rather than strand them (a claw bot without stars does 2 damage a swing), each bot is given once what a player
 * of its level typically carries out of town: its kit from the shop rules (BotShopping's goals: 50-400 HP potions,
 * MP potions for a caster, 1,000-4,000 shots: arrows, or whole stacks of stars or bullets, at most two), in the potions every
 * general store sells (Red, Orange, White; Blue, Mana Elixir) and the shop ammo (Subi, Bullet, Arrows for Bow or
 * Crossbow). BotStore marks the bot stocked (botstore.StockedAt, migration 007) in the same write that stores the
 * items, so it is granted once.
 *
 * The items come in through BotHoldings.grantItem like loot, so the ledger and the store sweep see them, but not
 * through the ticket 15 yield caps: this is state carried over, not something taken from a map.
 */
public final class StarterStash {

    private StarterStash() {}

    private static final Logger log = LoggerFactory.getLogger(StarterStash.class);

    private static final int RED_POTION = 2000000;

    // The potions a general store sells, at the usual price (the price only weighs value when picking).
    static final ShopCatalog.Site GENERAL_STORE = new ShopCatalog.Site(0, 0, 0, new Point(), List.of(
            new ShopCatalog.Listing(0, RED_POTION, 50, 0),
            new ShopCatalog.Listing(1, ItemId.ORANGE_POTION, 160, 0),
            new ShopCatalog.Listing(2, ItemId.WHITE_POTION, 320, 0),
            new ShopCatalog.Listing(3, ItemId.BLUE_POTION, 200, 0),
            new ShopCatalog.Listing(4, ItemId.MANA_ELIXIR, 620, 0)));

    private static final int AMMO_STACKS = 2;

    private static final AtomicLong granted = new AtomicLong();

    /* How many bots got their stash since boot. */
    public static long granted() {
        return granted.get();
    }

    /* What the stash holds for this bot: item id and quantity, in the order it is granted. */
    static List<int[]> contents(Character bot) {
        List<int[]> out = new ArrayList<>();
        WeaponType weapon = BotShopping.weapon(bot);
        if (BotAmmo.usesAmmo(weapon)) {
            int ammo = ammoFor(bot, weapon);
            if (ammo != 0) {
                int goal = BotShopping.ammoGoal(bot.getLevel());
                if (ItemConstants.isRechargeable(ammo)) {
                    int stack = SupplyData.get().slotMax(bot, ammo);
                    for (int held = 0, i = 0; held < goal && i < AMMO_STACKS && stack > 0; held += stack, i++) {
                        out.add(new int[]{ammo, stack});
                    }
                } else {
                    out.add(new int[]{ammo, goal});
                }
            }
        }
        boolean usesMp = attackUsesMp(bot);
        ShopCatalog.Listing mp = usesMp || BotShopping.isMage(bot.getJob())
                ? BotShopping.pickPotion(bot, GENERAL_STORE, false) : null;
        int mpGoal = BotShopping.mpGoal(bot, usesMp);
        if (mp != null && mpGoal > 0) {
            out.add(new int[]{mp.itemId(), mpGoal});
        }
        ShopCatalog.Listing hp = BotShopping.pickPotion(bot, GENERAL_STORE, true);
        if (hp != null) {
            out.add(new int[]{hp.itemId(), BotShopping.hpGoal(bot.getLevel())});
        }
        return out;
    }

    // The shop ammo the weapon fires at this level: stars, bullets (from level 10), arrows or bolts.
    private static int ammoFor(Character bot, WeaponType weapon) {
        int id = BotAttackData.projectileFor(weapon);
        return id != 0 && BotAmmo.fits(weapon, id, bot.getLevel()) ? id : 0;
    }

    private static boolean attackUsesMp(Character bot) {
        try {
            return BotSupplies.needs(bot).usesMp();
        } catch (LinkageError | RuntimeException noAttackData) {
            return false;
        }
    }

    /*
     * Gives the bot its stash. What does not fit its USE tab is left out. Returns the units granted (0 when nothing
     * fit, which still counts as stocked: the bot had a full tab).
     */
    public static int grant(Character bot) {
        int units = 0;
        List<String> lines = new ArrayList<>();
        for (int[] entry : contents(bot)) {
            Item item = new Item(entry[0], (short) 0, (short) entry[1], -1);
            if (BotHoldings.grantItem(bot, item)) {
                units += entry[1];
                lines.add(entry[1] + "x " + entry[0]);
            }
        }
        granted.incrementAndGet();
        if (units > 0) {
            BotAmmo.syncStats(bot, BotShopping.weapon(bot));
        }
        log.info("[Stash] {} (lv {} {}) got its starter supplies: {}", bot.getName(), bot.getLevel(), bot.getJob(),
                lines.isEmpty() ? "none fit" : String.join(", ", lines));
        return units;
    }
}
