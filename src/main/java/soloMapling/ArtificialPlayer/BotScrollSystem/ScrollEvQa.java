package soloMapling.ArtificialPlayer.BotScrollSystem;

import client.Character;
import client.inventory.Equip;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Appetite;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Choice;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.ScrollFacts;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.Target;

import java.util.List;
import java.util.Random;

/**
 * Auto-QA seam for t-gb-5 (-Dslums.scroll.ev, ticket 163): read-only views of the scroll decision for a staged bot,
 * so a check can set the EV planner's pick beside the appetite rule's. Nothing here rolls, buys or changes state.
 */
public final class ScrollEvQa {

    private ScrollEvQa() {
    }

    /** The held scrolls that fit this equip, as the planner sees them (one per scroll id). */
    public static List<ScrollFacts> fitting(Character bot, Equip equip) {
        return BotScrolling.fittingHeld(bot, equip);
    }

    /** Whether long shots (10% / 30% and dark scrolls) are still allowed today (the world's godly cap). */
    public static boolean longShotsAllowed() {
        return BotScrolling.longShotsAllowed();
    }

    /** The EV planner's pick for this appetite (what plan() does with the switch on), or null. */
    public static BotScrolling.Plan ev(Character bot, Appetite appetite) {
        return BotScrolling.evPlan(bot, appetite);
    }

    /** The appetite rule's pick (what plan() does with the switch off) on this equip, or null; a fixed roll. */
    public static Choice rule(Character bot, Equip equip, Appetite appetite) {
        return ScrollRules.choose(appetite, new Target(equip.getItemId(), equip.getUpgradeSlots(),
                equip.getPosition() < 0), fitting(bot, equip), BotScrolling.holdsWhite(bot), new Random(1),
                longShotsAllowed());
    }
}
