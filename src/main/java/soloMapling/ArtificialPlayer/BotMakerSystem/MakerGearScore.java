package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.Character;
import client.inventory.Equip;

/*
 * Ticket 112: how the maker plan asks "would this crafted equip be an upgrade, and by how much attack?". The plan
 * never looks inside the gear code: it calls this.
 *
 * The signature is the one ticket 162's GearScoring.upgradeValue has (branch 162-gearopt, the new gear optimizer):
 * once that branch is merged, LIVE becomes GearScoring.live()::upgradeValue (one line, below) and the plan ranks by
 * the same answer bots dress by. Until then it is AttackGainScore: the bot damage yardstick on a slot-for-slot swap.
 */
@FunctionalInterface
public interface MakerGearScore {

    /* How much the character's main attack goes up (BotDamage's average line) with this equip worn instead of what it
     * wears there now; 0 when it would not be worn (no gain, cannot wear it, unknown item). Never moves an item. */
    double upgradeValue(Character chr, Equip candidate);

    /* The live scorer. 162-gearopt: replace with GearScoring.live()::upgradeValue. */
    static MakerGearScore live() {
        return AttackGainScore.INSTANCE;
    }
}
