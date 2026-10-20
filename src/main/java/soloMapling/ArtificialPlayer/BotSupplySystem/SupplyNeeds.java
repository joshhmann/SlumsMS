package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.inventory.Item;

import java.util.List;

/*
 * Real bots 08: a bot's supplies as its town stop sees them, handed to the resupply hook (BotResupply).
 *
 * @param shortage     what sent it back, if anything (BotSupplies.Shortage.NONE on an ordinary town visit)
 * @param ammoItemId   the ammo it would buy for its weapon (a claw thrower's own star from ThrowingStarSelector), 0
 *                     when its main attack fires none
 * @param ammoHeld     how many it holds that its weapon can fire
 * @param toRecharge   its star or bullet stacks below what they recharge to (BotSupplies.rechargeTo: slot max plus
 *                     Claw/Gun Mastery), for a shop's recharge
 * @param hpPotions    HP potions it holds
 * @param mpPotions    MP potions it holds
 * @param usesMp       whether its main attack costs MP
 */
public record SupplyNeeds(BotSupplies.Shortage shortage, int ammoItemId, int ammoHeld, List<Item> toRecharge,
                          int hpPotions, int mpPotions, boolean usesMp) {

    /* Anything a shop could help with: a shortage, or running low on what it uses. */
    public boolean wantsAnything() {
        return shortage != BotSupplies.Shortage.NONE
                || (ammoItemId != 0 && ammoHeld < LOW_AMMO)
                || !toRecharge.isEmpty()
                || hpPotions < LOW_POTIONS
                || (usesMp && mpPotions < LOW_POTIONS);
    }

    // What counts as running low: a stack's worth of shots, a couple dozen potions.
    static final int LOW_AMMO = 200;
    static final int LOW_POTIONS = 20;
}
