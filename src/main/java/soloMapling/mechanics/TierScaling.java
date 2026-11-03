package soloMapling.mechanics;

/**
 * Which difficulty tier a run is played at, and where a tier's numbers come from.
 *
 * <p>NORMAL is the stock party quest and is what the engine has always played: {@code Content.rules(NORMAL)} answers the
 * row's own gates even when the file carries no {@code tiers:} block. Any tier above it is opt-in twice over - the server
 * must run with {@code -Dslums.mechanics.tiers=true} and the content row must actually name the tier - because a row that
 * does not name a tier has no numbers for it, and inventing them here would make this class a second codebase.
 *
 * <p>The numbers themselves are never computed here. They are transcribed into each {@code content/*.yaml} from
 * {@code DIFFICULTY-CHAOS-PLAN.md} section 9, which is stamped "proposal only, nothing merged, owner may overwrite any
 * cell"; this class only decides whether a run is allowed to read them.
 *
 * <p>Two things stay unwired on purpose: the instance timer is the event script's own {@code eventTime}
 * ({@code KerningPQ.js:118} and siblings), so a tier's {@code timeLimitMinutes} is this engine's budget and not the
 * script's clock, and {@code maxLevel} is advisory until something enforces a ceiling (plan f3, f5).
 */
public final class TierScaling {

    /** The server switch; off unless the operator asks, like every other mechanics flag. */
    public static final String FLAG = "slums.mechanics.tiers";

    /** The tier a run asks for; a name from {@link MechanicsModel.Tier}, ignored unless {@link #FLAG} is on. */
    public static final String TIER_PROPERTY = "slums.mechanics.tier";

    private TierScaling() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(FLAG);
    }

    /**
     * The rules a run of {@code content} at {@code tier} may use, or null when it may not: unknown content, a tier the
     * row does not name, or any tier above NORMAL while the switch is off.
     */
    public static MechanicsModel.TierRules rulesFor(MechanicsModel.Content content, MechanicsModel.Tier tier) {
        if (content == null || tier == null) {
            return null;
        }
        if (tier != MechanicsModel.Tier.NORMAL && !enabled()) {
            return null;
        }
        return content.rules(tier).orElse(null);
    }

    /**
     * The tier the run should be started at. NORMAL unless the switch is on, the property names a tier, and the row
     * carries that tier - a name the row does not carry is not an error, it is simply the stock quest.
     */
    public static MechanicsModel.Tier requested(MechanicsModel.Content content) {
        MechanicsModel.Tier named = MechanicsModel.Tier.parse(System.getProperty(TIER_PROPERTY)).orElse(null);
        if (named == null || rulesFor(content, named) == null) {
            return MechanicsModel.Tier.NORMAL;
        }
        return named;
    }
}
