package soloMapling.ArtificialPlayer.BotSocialSystem;

/**
 * One social act as code: what has to be true, how much the actor wants it, and what it does. The 16 are registered in
 * {@link SocialActions}; {@code SocialActs} holds the implementations and the per-act table in its javadoc.
 *
 * <p>The parts, every one of them in the implementation, never in a side table:
 * <ul>
 *   <li>{@link #preconditions}: the legal gate. Reads only the {@link SocialContext} (what the actor knows). Null when
 *       the act is legal, else the reason (a short phrase for the log and the counters).</li>
 *   <li>target selection: the registry offers every candidate the actor can see; an act picks itself out by failing or
 *       passing {@code preconditions} for that target, so "who" is decided by the same gate as "whether".</li>
 *   <li>{@link #utility}: the expected value for the actor, from its needs, emotions, traits, its own edge to the
 *       target, what it remembers, the company the target keeps, the social norms and the pair's recent history. About
 *       0..1; the registry adds personality-scaled noise.</li>
 *   <li>{@link #needsIdle}: the execution requirement (an act that moves the bot needs an idle bot, one that only
 *       talks needs a free one); the registry checks it with the {@link SocialWorld}.</li>
 *   <li>{@link #execute}: success and failure conditions, the state changes, the memory written and the relationship
 *       consequences. Only called when preconditions, the requirement and the {@link SocialBudget} all passed. May
 *       return {@code happened=true} with a reason other than "ok" when the act was carried out but did not land
 *       (an apology refused).</li>
 * </ul>
 */
public interface SocialAction {

    SocialActKind kind();

    /** Null when legal; else why not. Pure over the context. */
    String preconditions(SocialContext c);

    /** Expected utility, about 0..1 (not clamped); only asked of a legal act. Pure over the context. */
    double utility(SocialContext c);

    /** True when carrying it out moves the bot (needs {@link SocialWorld#idle}); false when it only talks ({@link SocialWorld#free}). */
    default boolean needsIdle() {
        return false;
    }

    /**
     * True when the existing flow this act wraps already asks and records the {@link SocialBudget} itself (the bot-talk
     * greet, chat and ask acts do), so the registry must not record a second time.
     */
    default boolean recordsItself() {
        return false;
    }

    /** Does it. Must not throw: the registry turns any exception into a refused outcome. */
    SocialActions.Outcome execute(SocialContext c);
}
