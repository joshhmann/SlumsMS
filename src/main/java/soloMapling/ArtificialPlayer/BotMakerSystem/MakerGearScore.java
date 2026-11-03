package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.Character;
import client.inventory.Equip;

/*
 * Ticket 112: how the maker plan asks "would this crafted equip be an upgrade, and by how much attack?". The plan
 * never looks inside the gear code: it calls this.
 *
 * The signature is the one ticket 162's GearScoring.upgradeValue has (branch 162-gearopt, the new gear optimizer):
 * since release-1.6.2 merged both, live() is GearScoring.live()::upgradeValue and the plan ranks by the same answer
 * bots dress by (AttackGainScore, the slot-for-slot stand-in, is no longer the live scorer).
 */
@FunctionalInterface
public interface MakerGearScore {

    /* How much the character's main attack goes up (BotDamage's average line) with this equip worn instead of what it
     * wears there now; 0 when it would not be worn (no gain, cannot wear it, unknown item). Never moves an item. */
    double upgradeValue(Character chr, Equip candidate);

    /* The same gain asked at the piece's own required level: what it would bring to a character who can wear it, so
     * a piece above the wearer's level is not mistaken for junk (ticket 181 prog#P2). */
    default double holdingValue(Character chr, Equip candidate) {
        return upgradeValue(chr, candidate);
    }

    /* The live scorer: ticket 162's GearScoring, the one scorer bots and alts dress by. */
    static MakerGearScore live() {
        soloMapling.ArtificialPlayer.BotGearSystem.GearScoring scoring =
                soloMapling.ArtificialPlayer.BotGearSystem.GearScoring.live();
        return new MakerGearScore() {
            @Override
            public double upgradeValue(Character chr, Equip candidate) {
                return scoring.upgradeValue(chr, candidate);
            }

            @Override
            public double holdingValue(Character chr, Equip candidate) {
                return scoring.holdingValue(chr, candidate);
            }
        };
    }
}
