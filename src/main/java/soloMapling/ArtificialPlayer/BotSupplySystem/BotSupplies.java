package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.Character;
import client.inventory.Item;
import client.inventory.WeaponType;
import constants.inventory.ItemConstants;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackData;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackProfile;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotAttack;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/*
 * Real bots 08: whether a grinding bot can keep going on what it carries, and what it would restock.
 *
 * A bot leaves the grind for town (TrainingBot) when it is:
 *  - HP: under a quarter of its HP with no HP potion left;
 *  - MP: short of the MP for its main attack with no MP potion left;
 *  - AMMO: out of ammo its main (ranged) attack can fire.
 * In town it rests to full and offers its needs to the resupply hook (BotResupply, ticket 09's shop run). A bot whose
 * town stop brought it no ammo does not keep walking back for it: it grinds swinging its ranged weapon in melee (as a
 * player out of ammo can) until ammo turns up - loot, or ticket 09 - and only then counts running out again.
 */
public final class BotSupplies {

    private BotSupplies() {}

    private static final Logger log = LoggerFactory.getLogger(BotSupplies.class);

    public enum Shortage { NONE, HP, MP, AMMO }

    // Bots that went to town for ammo and came back without it (see the class comment).
    private static final Set<Integer> meleeWithoutAmmo = ConcurrentHashMap.newKeySet();

    /* What, if anything, should send this bot back to town now. */
    public static Shortage shortage(Character bot) {
        if (bot == null) {
            return Shortage.NONE;
        }
        if (bot.getHp() < bot.getCurrentMaxHp() * BotVitals.HP_RETREAT_AT && BotVitals.potionCount(bot, true) == 0) {
            return Shortage.HP;
        }
        BotAttackProfile main = BotAttackDriver.primaryAttack(bot);
        if (main == null) {
            return Shortage.NONE;
        }
        int level = BotAttackDriver.packetLevel(bot, main);
        int mp = BotVitals.mpCost(bot, main.skillId, level);
        if (mp > 0 && bot.getMp() < mp && BotVitals.potionCount(bot, false) == 0) {
            return Shortage.MP;
        }
        if (outOfAmmo(bot, main, level)) {
            if (meleeWithoutAmmo.contains(bot.getId())) {
                return Shortage.NONE;   // already tried town for it: fights in melee until ammo turns up
            }
            return Shortage.AMMO;
        }
        meleeWithoutAmmo.remove(bot.getId());   // it has ammo (again): running out sends it back next time
        return Shortage.NONE;
    }

    /* The main attack is a ranged one that needs ammo and the bot has none it can fire. */
    public static boolean outOfAmmo(Character bot) {
        BotAttackProfile main = BotAttackDriver.primaryAttack(bot);
        return main != null && outOfAmmo(bot, main, BotAttackDriver.packetLevel(bot, main));
    }

    /** Memory-only: a failed ammo trip already chose the existing melee fallback, so do not create a trip loop. */
    public static boolean shouldRetryAmmoTrip(int botId) { return !meleeWithoutAmmo.contains(botId); }

    // train#F2: a trip whose restock bought nothing (all its mesos are reserve, the town sells nothing it can fire)
    // must not be walked again at the next shortage, or the bot loops town -> shop -> train -> town forever. It grinds
    // it out for this long instead; a trip that buys anything at all clears the refusal.
    static final long RESTOCK_REFUSAL_MS = 10 * 60_000L;
    private static final Map<Integer, Long> refusedUntilMs = new ConcurrentHashMap<>();

    /* Whether a resupply trip may start now: a restock that bought nothing refuses one until RESTOCK_REFUSAL_MS on. */
    public static boolean mayResupply(int botId, long nowMs) {
        Long until = refusedUntilMs.get(botId);
        return until == null || nowMs >= until;
    }

    private static void noteRestock(int botId, BotResupply.RestockOutcome outcome, long nowMs) {
        if (outcome != BotResupply.RestockOutcome.NOTHING) {
            refusedUntilMs.remove(botId);
            return;
        }
        refusedUntilMs.put(botId, nowMs + RESTOCK_REFUSAL_MS);
        if (log.isDebugEnabled()) {
            log.debug("Supplies: bot {} bought nothing in town; no resupply trip for {} min",
                    botId, RESTOCK_REFUSAL_MS / 60_000);
        }
    }

    private static boolean outOfAmmo(Character bot, BotAttackProfile main, int level) {
        if (main.route != BotAttackProfile.Route.RANGED) {
            return false;
        }
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        return BotAmmo.needsAmmo(bot, weapon, main.skillId) && BotAmmo.usable(bot, weapon, main.skillId, level) == null
                && BotAmmo.usable(bot, weapon, 0, 0) == null;
    }

    /* What the bot holds and lacks, for the resupply hook. */
    public static SupplyNeeds needs(Character bot) {
        WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
        BotAttackProfile main = BotAttackDriver.primaryAttack(bot);
        boolean ranged = main != null && main.route == BotAttackProfile.Route.RANGED
                && BotAmmo.needsAmmo(bot, weapon, main.skillId);
        int mpCost = main != null ? BotVitals.mpCost(bot, main.skillId, BotAttackDriver.packetLevel(bot, main)) : 0;
        List<Item> recharge = new ArrayList<>();
        if (ranged) {
            for (Item stack : BotAmmo.stacks(bot, weapon)) {
                if (ItemConstants.isRechargeable(stack.getItemId()) && stack.getQuantity() < rechargeTo(bot, stack)) {
                    recharge.add(stack);
                }
            }
        }
        return new SupplyNeeds(shortage(bot),
                ranged ? BotAttackData.projectileFor(weapon, bot) : 0,
                ranged ? BotAmmo.held(bot, weapon) : 0,
                List.copyOf(recharge),
                BotVitals.potionCount(bot, true),
                BotVitals.potionCount(bot, false),
                mpCost > 0);
    }

    /* How full a star or bullet stack recharges: its slot max plus Claw/Gun Mastery's extra, as for a player. */
    public static int rechargeTo(Character bot, Item stack) {
        int max = SupplyData.get().slotMax(bot, stack.getItemId());
        return max > 0 ? max : stack.getQuantity();   // no item data: nothing to recharge
    }

    /*
     * The bot's town stop in one step: rest to full, then restock where it stands. TrainingBot splits the two
     * (arriveInTown, then restock once it has walked to a shop - ticket 09).
     */
    public static void visitTown(Character bot) {
        if (bot == null) {
            return;
        }
        arriveInTown(bot);
        restock(bot);
    }

    /* The first half of a town stop: rest to full, and what the bot now needs (null for no bot). */
    public static SupplyNeeds arriveInTown(Character bot) {
        if (bot == null) {
            return null;
        }
        BotVitals.rest(bot);
        return needs(bot);
    }

    /*
     * The second half: offer the bot's needs to the resupply hook (ticket 09 buys at the shop NPC the bot has walked
     * to), and if it still has no ammo for its ranged attack, settle for melee until some turns up.
     */
    public static BotResupply.RestockOutcome restock(Character bot) {
        return restock(bot, System.currentTimeMillis());
    }

    /* As above, on a given clock (tests): what the trip got done, and it is remembered (see mayResupply). */
    static BotResupply.RestockOutcome restock(Character bot, long nowMs) {
        if (bot == null) {
            return BotResupply.RestockOutcome.NOTHING;
        }
        SupplyNeeds needs = needs(bot);
        BotResupply.RestockOutcome outcome = needs.wantsAnything()
                ? BotResupply.request(bot, needs) : BotResupply.RestockOutcome.NOTHING;
        if (needs.wantsAnything()) {
            noteRestock(bot.getId(), outcome, nowMs);
        }
        if (outOfAmmo(bot)) {
            if (meleeWithoutAmmo.add(bot.getId()) && log.isDebugEnabled()) {
                log.debug("Supplies: bot {} ({}) found no ammo in town; fights in melee until it has some", bot.getId(),
                        bot.getName());
            }
        } else {
            meleeWithoutAmmo.remove(bot.getId());
        }
        return outcome;
    }

    /* Notes a bot leaving its grind for town over a shortage (the debug log the supply rates are checked from). */
    public static void noteRetreat(Character bot, Shortage shortage) {
        if (log.isDebugEnabled()) {
            WeaponType weapon = BotAttack.resolveEquippedWeaponType(bot);
            log.debug("Supplies: bot {} ({}) lv {} {} leaves for town: {} (HP {}/{}, MP {}/{}, HP pots {}, MP pots {}, ammo {}){}",
                    bot.getId(), bot.getName(), bot.getLevel(), bot.getJob(), shortage, bot.getHp(),
                    bot.getCurrentMaxHp(), bot.getMp(), bot.getCurrentMaxMp(), BotVitals.potionCount(bot, true),
                    BotVitals.potionCount(bot, false), BotAmmo.held(bot, weapon),
                    GCMovement.isMapObserved(bot)
                            ? " on screen" : " offscreen");
        }
    }

    /* Releases a despawned bot's supply state. */
    public static void clearBot(int botId) {
        meleeWithoutAmmo.remove(botId);
        refusedUntilMs.remove(botId);
        BotAmmo.clearBot(botId);
        BotVitals.clearBot(botId);
        OffscreenSupplies.clearBot(botId);
        BotShopping.clearBot(botId);
        soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling.forget(botId);   // ticket 11
    }
}
