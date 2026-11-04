package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Everything ONE bot knows when it weighs one social act toward one other bot, as plain values: its own mind (traits,
 * emotions, needs), its OWN edge to the target (axes and stage; never the target's edge back), what it remembers of
 * the pair, who it sees standing next to the target (and its own edge to each of them), and the pair's recent history.
 * A bot acts only on this: no global state, no omniscience. Built by {@link #build} from the live stores, or by hand in
 * a test. Immutable.
 */
public record SocialContext(long now, SocialWorld.Body actor, SocialWorld.Body target, Self self, Axes edge, Stage stage,
                            Recollection memory, List<Company> company, boolean actorFree, boolean targetFree,
                            boolean actorIdle, boolean targetIdle, boolean avoiding, SocialActions.Invite invite) {

    /** Within this horizontal and vertical distance (px) two bots are within earshot for a greeting or a talk. */
    public static final int EAR_X = 600, EAR_Y = 250;
    /** Another bot this close to the target is "with" it. */
    public static final int WITH_X = 220, WITH_Y = 120;

    /** The mind at one instant: all twelve traits, nine emotions, eight needs and the mood. */
    public record Self(Map<Trait, Double> traits, Map<Emotion, Double> emotions, Map<Need, Double> needs, double mood) {
        public Self {
            traits = Map.copyOf(traits);
            emotions = Map.copyOf(emotions);
            needs = Map.copyOf(needs);
        }

        public double t(Trait t) {
            return traits.getOrDefault(t, 0.5);
        }

        public double e(Emotion e) {
            return emotions.getOrDefault(e, e.baseline);
        }

        public double n(Need n) {
            return needs.getOrDefault(n, 0.3);
        }

        /** A mind with every trait at {@code trait}, emotions at rest, needs at {@code need}. */
        public static Self flat(double trait, double need) {
            return new Self(fill(trait), restEmotions(), fillNeeds(need), 0);
        }

        private static Map<Trait, Double> fill(double v) {
            Map<Trait, Double> m = new EnumMap<>(Trait.class);
            for (Trait t : Trait.values()) m.put(t, v);
            return m;
        }

        private static Map<Need, Double> fillNeeds(double v) {
            Map<Need, Double> m = new EnumMap<>(Need.class);
            for (Need n : Need.values()) m.put(n, v);
            return m;
        }

        private static Map<Emotion, Double> restEmotions() {
            Map<Emotion, Double> m = new EnumMap<>(Emotion.class);
            for (Emotion e : Emotion.values()) m.put(e, e.baseline);
            return m;
        }

        /** This mind with some traits, emotions or needs replaced. */
        public Self with(Map<Trait, Double> traitChanges, Map<Emotion, Double> emotionChanges,
                         Map<Need, Double> needChanges) {
            Map<Trait, Double> t = new EnumMap<>(Trait.class);
            t.putAll(traits);
            t.putAll(traitChanges);
            Map<Emotion, Double> e = new EnumMap<>(Emotion.class);
            e.putAll(emotions);
            e.putAll(emotionChanges);
            Map<Need, Double> n = new EnumMap<>(Need.class);
            n.putAll(needs);
            n.putAll(needChanges);
            return new Self(t, e, n, mood);
        }

        /** A snapshot of {@code m} at {@code now}. */
        public static Self of(SocialMind m, long now) {
            Map<Trait, Double> t = new EnumMap<>(Trait.class);
            for (Trait x : Trait.values()) t.put(x, m.trait(x));
            Map<Emotion, Double> e = new EnumMap<>(Emotion.class);
            for (Emotion x : Emotion.values()) e.put(x, m.emotion(x, now));
            Map<Need, Double> n = new EnumMap<>(Need.class);
            for (Need x : Need.values()) n.put(x, m.need(x, now));
            return new Self(t, e, n, m.mood(now));
        }
    }

    /**
     * What the actor remembers about the pair, read from its own notes about the target: {@code openFight} (a fight
     * with no making-up after it), {@code iStartedIt} (the actor lost its temper first), {@code apologized} (an apology,
     * given or taken, came after the fight), {@code warm} (how many good shared memories it holds), {@code grievances}
     * (how many bad ones).
     */
    public record Recollection(boolean openFight, boolean iStartedIt, boolean apologized, int warm, int grievances) {
        public static final Recollection NONE = new Recollection(false, false, false, 0, 0);
    }

    /** Someone standing next to the target whom the actor knows: the actor's own edge to them. */
    public record Company(int id, Axes edge, Stage stage) {
        /** How much the actor distrusts or resents this one: 0 for a friend, up to about 1 for an enemy. */
        public double distrust() {
            return Math.min(1, Math.max(0, -edge.trust()) + edge.resentment() + 0.5 * edge.rivalry());
        }

        /** How much the actor trusts and likes this one, 0..1. */
        public double trusted() {
            return Math.max(0, Math.min(1, 0.6 * Math.max(0, edge.trust()) + 0.4 * Math.max(0, edge.affection())));
        }
    }

    /** Milliseconds since the actor last did {@code kind} to the target (Long.MAX_VALUE: never). */
    public long since(SocialActKind kind) {
        return SocialActions.sinceLast(actor.id(), target.id(), kind, now);
    }

    public boolean sameMap() {
        return target != null && actor.mapKey() == target.mapKey();
    }

    public boolean withinEar() {
        return sameMap() && actor.dx(target) <= EAR_X && actor.dy(target) <= EAR_Y;
    }

    /** Pixel distance between the two (horizontal, large when on another map). */
    public int distance() {
        return sameMap() ? actor.dx(target) : Integer.MAX_VALUE;
    }

    /** How much of the target's company the actor distrusts (sum, capped at 1.5). */
    public double distrustedCompany() {
        double s = 0;
        for (Company c : company) s += c.distrust();
        return Math.min(1.5, s);
    }

    /** How much of the target's company the actor trusts (sum, capped at 1.5). */
    public double trustedCompany() {
        double s = 0;
        for (Company c : company) s += c.trusted();
        return Math.min(1.5, s);
    }

    /** True when the pair is a deliberate bond the norms protect (CLOSE_FRIEND or ROMANTIC). */
    public boolean closeBond() {
        return stage == Stage.CLOSE_FRIEND || stage == Stage.ROMANTIC;
    }

    /** True when the actor knows the target at all: it has an edge to it. */
    public boolean knows() {
        return stage != Stage.STRANGER || edge.familiarity() >= 0.04;
    }

    public boolean sourRelation() {
        return stage == Stage.RIVAL || stage == Stage.ESTRANGED;
    }

    /** Warmth of the actor's own edge, -1..1. */
    public double warmth() {
        return edge.warmth();
    }
}
