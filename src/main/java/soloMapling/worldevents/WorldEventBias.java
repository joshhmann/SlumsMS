package soloMapling.worldevents;

import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;

import java.util.EnumMap;
import java.util.Map;

/**
 * 1.6.0: how a bot's point on Bartle's chart leans it toward a world event it was offered (the EVENT activity of the
 * brain, {@link Activity#EVENT}). Plugged into {@link LifePolicy} as a {@link LifePolicy.Bias}, so it adds to the
 * scorer like any other pull and is clamped with the other plug-ins: needs, stickiness and commitments still win.
 * <ul>
 *   <li>Invasions, boss sightings and hot spots: Killers and Achievers rush (fights, the kill, the EXP).</li>
 *   <li>The giant cake: Socializers come to hang out and cheer, Achievers for the last hit.</li>
 *   <li>Treasure hunts: Explorers.</li>
 * </ul>
 * Only a bot with a standing offer ({@link WorldEventSteer#offerFor}) gets any of it. Pure apart from that read.
 */
public final class WorldEventBias implements LifePolicy.Bias {

    /** What any offer is worth before the bot's type: "something is happening" pulls everyone a little. */
    static final double BASE = .2;

    private static final Map<EventTemplate.Kind, Map<SocialProfile.Type, Double>> LEAN = new EnumMap<>(
            EventTemplate.Kind.class);

    static {
        LEAN.put(EventTemplate.Kind.INVASION, row(.26, .06, .06, .30));
        LEAN.put(EventTemplate.Kind.BOSS_SIGHTING, row(.27, .06, .04, .28));
        LEAN.put(EventTemplate.Kind.GIANT_CAKE, row(.12, .06, .28, .10));
        LEAN.put(EventTemplate.Kind.TREASURE_HUNT, row(.06, .30, .04, .04));
        LEAN.put(EventTemplate.Kind.HOT_SPOT, row(.30, .04, .06, .12));
        LEAN.put(EventTemplate.Kind.WANDERING_NPC, row(.04, .20, .10, .0));
        LEAN.put(EventTemplate.Kind.WEDDING, row(.0, .04, .25, .0));
    }

    /** One kind's pull per type: achiever, explorer, socializer, killer. */
    private static Map<SocialProfile.Type, Double> row(double achiever, double explorer, double socializer,
                                                      double killer) {
        Map<SocialProfile.Type, Double> m = new EnumMap<>(SocialProfile.Type.class);
        m.put(SocialProfile.Type.ACHIEVER, achiever);
        m.put(SocialProfile.Type.EXPLORER, explorer);
        m.put(SocialProfile.Type.SOCIALIZER, socializer);
        m.put(SocialProfile.Type.KILLER, killer);
        return m;
    }

    public static final WorldEventBias INSTANCE = new WorldEventBias();
    private static volatile boolean registered;

    private WorldEventBias() {
    }

    /** Adds the bias to the brain once (WorldEvents.start). */
    public static synchronized void register() {
        if (!registered) {
            LifePolicy.addBias(INSTANCE);
            registered = true;
        }
    }

    /** The lean of this point toward an event of this kind (no point: the plain base). Pure. */
    public static double lean(EventTemplate.Kind kind, SocialProfile social) {
        if (kind == null) {
            return 0;
        }
        if (social == null) {
            return BASE;
        }
        Map<SocialProfile.Type, Double> row = LEAN.getOrDefault(kind, Map.of());
        double sum = BASE;
        for (SocialProfile.Type type : SocialProfile.Type.values()) {
            sum += social.share(type) * row.getOrDefault(type, 0.0);
        }
        return sum;
    }

    /**
     * The chance a bot answers the call when the brain is off (the pre-brain path rolls once): the same lean,
     * scaled into [0.1, 0.9]. Pure.
     */
    public static double joinChance(EventTemplate.Kind kind, SocialProfile social) {
        double lean = lean(kind, social); // BASE .. BASE + .30
        return Math.max(0.1, Math.min(0.9, (lean - BASE) / .30 * 0.8 + 0.1));
    }

    @Override
    public double bonus(Activity activity, LifePolicy.Context context, LifePolicy.Situation situation) {
        if (activity != Activity.EVENT || situation == null || situation.botId() <= 0) {
            return 0;
        }
        WorldEventSteer.Offer offer = WorldEventSteer.offerFor(situation.botId(), System.currentTimeMillis());
        return offer == null ? 0 : lean(offer.kind(), situation.social());
    }
}
