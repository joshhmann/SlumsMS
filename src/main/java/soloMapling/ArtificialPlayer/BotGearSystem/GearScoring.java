package soloMapling.ArtificialPlayer.BotGearSystem;

import client.Character;
import client.inventory.Equip;

import java.util.Collection;
import java.util.Map;

/*
 * Ticket 162 (owner rule 2026-10-30: population bots and owned alts share one implementation of each behaviour): the
 * one gear-scoring entry point. Population bots dress through BotGear.review, which uses the same planner; upgrade
 * sharing with an owner or sibling alts (ticket 99 / 162 part 3, branch 162-companions) asks through this interface
 * so its "is this an upgrade for you?" answer is exactly what the bot would itself put on.
 *
 * Pure read: nothing here moves an item. The planner is the one -Dslums.gear.optimizer picks (climb by default,
 * pareto = BotGearOptimizer), so a switch-on changes both together.
 */
public interface GearScoring {

    /*
     * How much better the character's main attack gets (BotDamage's average line, the same yardstick as the
     * optimizer) when this equip is added to what it wears and carries and the best loadout is taken; 0 when the
     * equip would not be worn (no gain, cannot wear it, a weapon its skills cannot use, unknown or cash item).
     */
    double upgradeValue(Character chr, Equip candidate);

    /* upgradeValue as a fraction of what the character's main attack is now (0.3 = 30% more damage). */
    double upgradeFraction(Character chr, Equip candidate);

    /*
     * The best loadout from what the character wears and carries plus the extras (another character's items, for
     * an offer): body slot to equip, only the slots that change. Empty when nothing would change.
     */
    Map<Short, Equip> bestSet(Character chr, Collection<Equip> extras);

    /* The live implementation (BotGear's item data and damage yardstick). */
    static GearScoring live() {
        return BotGear.SCORING;
    }
}
