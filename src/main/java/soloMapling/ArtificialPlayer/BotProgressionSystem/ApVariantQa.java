package soloMapling.ArtificialPlayer.BotProgressionSystem;

import soloMapling.ArtificialPlayer.BotSupportSwitches;

/**
 * Auto-QA seam for t-gb-6 (-Dslums.bots.apvariants, ticket 163): the AP-variant rule for a bot id that need not be
 * online or exist yet, so a check can ask about the first roster slot past the boot without creating a bot. Read-only.
 */
public final class ApVariantQa {

    private ApVariantQa() {
    }

    /** Whether a bot with this id and rebirth count may follow an AP variant under the live switches. */
    public static boolean rolls(int botId, int reborns) {
        return BotProgression.variantsFor(BotSupportSwitches.apVariants(), botId, reborns,
                BotSupportSwitches.apVariantFromSlot());
    }

    /** The variant a never-reborn bot of this id rolls for this job's class (no weapon hint), when it may roll. */
    public static BotBuild.Variant variant(int jobId, int botId) {
        BotBuild.Choice base = new BotBuild.Choice(0, botId);
        return BotBuild.rollVariant(jobId, BotBuild.alongLineOf(jobId, base), botId, 0);
    }
}
