package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialContext.Company;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialContext.Recollection;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialContext.Self;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions.Scored;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 271 stage 4: the utility model and the choice. Pure over a {@link SocialContext}: traits, emotions, needs, the
 * actor's own edge and stage, memory, the target's company and the norms decide which act scores best; different
 * personalities choose different acts from the same situation; the same bot in the same state is stable.
 */
class SocialUtilityTest extends SocialActTestBase {

    // ── the brief's example: Alice sees Bob with Clara ──

    static Axes edge(double fam, double trust, double aff, double resp, double att, double riv, double ren, double obl) {
        return Axes.of(new double[]{fam, trust, aff, resp, att, riv, ren, obl});
    }

    /** Alice trusts Bob strongly and likes him. */
    static final Axes TO_BOB = edge(0.7, 0.8, 0.6, 0.4, 0, 0, 0, 0);
    /** Alice distrusts Clara, resents her, and they are rivals. */
    static final Axes TO_CLARA = edge(0.5, -0.6, -0.2, 0, 0, 0.45, 0.35, 0);

    SocialWorld.Body alice, bob, clara;

    void scene() {
        alice = world.put(ALICE, "Alice", 0, 0);
        bob = world.put(BOB, "Bob", 120, 0);
        clara = world.put(CLARA, "Clara", 170, 0);
    }

    SocialContext ctx(Self self, SocialWorld.Body target, Axes edge, Stage stage, Recollection memory, List<Company> company) {
        return new SocialContext(now(), alice, target, self, edge, stage, memory, company, true, true, true, true, false, null);
    }

    /** What Alice sees: [Bob, with Clara beside him] and [Clara, with Bob beside her]. */
    List<SocialContext> aliceSees(Self self) {
        SocialContext withBob = ctx(self, bob, TO_BOB, Stage.CLOSE_FRIEND, Recollection.NONE,
                List.of(new Company(CLARA, TO_CLARA, Stage.RIVAL)));
        SocialContext withClara = ctx(self, clara, TO_CLARA, Stage.RIVAL, Recollection.NONE,
                List.of(new Company(BOB, TO_BOB, Stage.CLOSE_FRIEND)));
        return List.of(withBob, withClara);
    }

    static Self mind(Map<Trait, Double> traits, Map<Emotion, Double> emotions, Map<Need, Double> needs) {
        return Self.flat(0.5, 0.3).with(traits, emotions, needs);
    }

    static Self sociable() {
        return mind(Map.of(Trait.SOCIABILITY, 0.9, Trait.AGREEABLENESS, 0.8, Trait.EMPATHY, 0.7, Trait.IMPULSIVENESS, 0.2,
                Trait.PATIENCE, 0.7, Trait.CONFIDENCE, 0.6, Trait.JEALOUSY, 0.1, Trait.HONESTY, 0.5),
                Map.of(Emotion.LONELINESS, 0.5), Map.of(Need.COMPANIONSHIP, 0.8, Need.BELONGING, 0.6));
    }

    static Self hothead() {
        return mind(Map.of(Trait.IMPULSIVENESS, 0.9, Trait.CONFIDENCE, 0.8, Trait.AGREEABLENESS, 0.2, Trait.PATIENCE, 0.2,
                Trait.JEALOUSY, 0.7, Trait.SOCIABILITY, 0.45, Trait.EMPATHY, 0.3),
                Map.of(Emotion.ANGER, 0.5), Map.of(Need.COMPANIONSHIP, 0.25));
    }

    static Self timid() {
        return mind(Map.of(Trait.CONFIDENCE, 0.1, Trait.SOCIABILITY, 0.3, Trait.IMPULSIVENESS, 0.1, Trait.AGREEABLENESS, 0.6,
                Trait.PATIENCE, 0.6, Trait.JEALOUSY, 0.2),
                Map.of(Emotion.FEAR, 0.5, Emotion.ANXIETY, 0.5), Map.of(Need.COMPANIONSHIP, 0.25));
    }

    static Self loner() {
        return mind(Map.of(Trait.SOCIABILITY, 0.1, Trait.CURIOSITY, 0.2, Trait.JEALOUSY, 0.1, Trait.IMPULSIVENESS, 0.2,
                Trait.PATIENCE, 0.7),
                Map.of(), Map.of(Need.COMPANIONSHIP, 0.05, Need.BELONGING, 0.05, Need.RECOGNITION, 0.05,
                        Need.ACHIEVEMENT, 0.05, Need.SECURITY, 0.05));
    }

    static String show(Optional<Scored> s) {
        return s.map(x -> x.kind() + "->" + (x.targetId() == BOB ? "Bob" : "Clara")).orElse("nothing");
    }

    @Test
    void differentPersonalitiesChooseDifferentActsInTheSameScene() {
        scene();
        Map<String, Optional<Scored>> chosen = new LinkedHashMap<>();
        Map<String, Self> minds = new LinkedHashMap<>();
        minds.put("sociable", sociable());
        minds.put("hothead", hothead());
        minds.put("timid", timid());
        minds.put("loner", loner());
        for (Map.Entry<String, Self> m : minds.entrySet()) {
            chosen.put(m.getKey(), SocialActions.bestOf(aliceSees(m.getValue()), new Random(7)));
            System.out.println("UTILITY alice(" + m.getKey() + ") -> " + show(chosen.get(m.getKey())));
            for (SocialContext c : aliceSees(m.getValue())) {
                StringBuilder row = new StringBuilder("UTILITY   toward " + c.target().name() + ":");
                for (SocialActKind k : SocialActKind.values()) {
                    if (SocialActions.action(k).preconditions(c) == null) {
                        row.append(String.format(" %s=%.2f", k, SocialActions.action(k).utility(c)));
                    }
                }
                System.out.println(row);
            }
        }
        // she approaches Bob (a friendly act toward him), confronts Clara, withdraws from Clara, or ignores both
        Scored s = chosen.get("sociable").orElseThrow();
        assertEquals(BOB, s.targetId());
        assertTrue(List.of(SocialActKind.CONVERSE, SocialActKind.GREET, SocialActKind.SPEND_TIME, SocialActKind.COMPLIMENT,
                SocialActKind.INVITE, SocialActKind.GIVE_GIFT).contains(s.kind()), "sociable: " + show(chosen.get("sociable")));
        Scored h = chosen.get("hothead").orElseThrow();
        assertEquals(SocialActKind.ARGUE, h.kind(), "hothead: " + show(chosen.get("hothead")));
        assertEquals(CLARA, h.targetId());
        Scored t = chosen.get("timid").orElseThrow();
        assertEquals(SocialActKind.AVOID, t.kind(), "timid: " + show(chosen.get("timid")));
        assertEquals(CLARA, t.targetId());
        assertTrue(chosen.get("loner").isEmpty(), "a content loner ignores them: " + show(chosen.get("loner")));
        assertEquals(4, chosen.values().stream().map(o -> o.map(x -> x.kind().name()).orElse("none")).distinct().count(),
                "four personalities, four different choices");
    }

    @Test
    void theSameBotInTheSameStateIsStable() {
        scene();
        for (Self self : List.of(sociable(), hothead(), timid(), loner())) {
            // the same stream gives exactly the same answer
            assertEquals(SocialActions.bestOf(aliceSees(self), new Random(99)),
                    SocialActions.bestOf(aliceSees(self), new Random(99)));
            // a long run of draws: one act clearly leads (the noise only moves a score a little)
            Map<String, Integer> tally = new LinkedHashMap<>();
            Random rng = new Random(5);
            for (int i = 0; i < 300; i++) {
                tally.merge(show(SocialActions.bestOf(aliceSees(self), rng)), 1, Integer::sum);
            }
            int top = tally.values().stream().mapToInt(Integer::intValue).max().orElse(0);
            System.out.println("UTILITY stability " + tally);
            assertTrue(top >= 240, "one choice in at least 80% of 300 draws: " + tally);
        }
    }

    @Test
    void impulsiveBotsAreNoisierThanPatientOnes() {
        assertTrue(SocialActions.noiseAmplitude(hothead()) > SocialActions.noiseAmplitude(sociable()));
        assertTrue(SocialActions.noiseAmplitude(sociable()) >= 0.02);
    }

    @Test
    void identicalGoalsCanDifferOnlyThroughNoiseOnABorderlineCase() {
        scene();
        // two bots with the same mind and the same two near-equal options: the draws decide, per bot stream
        Self even = mind(Map.of(Trait.IMPULSIVENESS, 0.9, Trait.PATIENCE, 0.1), Map.of(), Map.of(Need.COMPANIONSHIP, 0.9));
        SocialContext bobCtx = ctx(even, bob, edge(0.5, 0.3, 0.3, 0.1, 0, 0, 0, 0), Stage.FRIEND, Recollection.NONE, List.of());
        SocialContext claraCtx = ctx(even, clara, edge(0.5, 0.3, 0.3, 0.1, 0, 0, 0, 0), Stage.FRIEND, Recollection.NONE, List.of());
        Map<Integer, Integer> picks = new LinkedHashMap<>();
        for (int bot = 0; bot < 40; bot++) {
            Random rng = SocialSim.random(SocialTestKit.BASE + 100 + bot, "social-act");
            picks.merge(SocialActions.bestOf(List.of(bobCtx, claraCtx), rng).orElseThrow().targetId(), 1, Integer::sum);
        }
        assertEquals(2, picks.size(), "with equal goals and equal edges both targets get picked by someone: " + picks);
    }

    // ── what moves the utility ──

    private double u(SocialActKind k, SocialContext c) {
        assertEquals(null, SocialActions.action(k).preconditions(c), k + " must be legal here");
        return SocialActions.action(k).utility(c);
    }

    private SocialContext friendCtx(Self self) {
        scene();
        return ctx(self, bob, edge(0.6, 0.4, 0.4, 0.3, 0, 0, 0, 0), Stage.FRIEND, Recollection.NONE, List.of());
    }

    @Test
    void needsEmotionsAndTraitsMoveTheActsTheyShould() {
        Self base = Self.flat(0.5, 0.3);
        SocialContext c0 = friendCtx(base);
        // companionship need -> CONVERSE, GREET, SPEND_TIME
        for (SocialActKind k : List.of(SocialActKind.CONVERSE, SocialActKind.GREET, SocialActKind.SPEND_TIME)) {
            SocialContext starved = friendCtx(base.with(Map.of(), Map.of(), Map.of(Need.COMPANIONSHIP, 0.95)));
            assertTrue(u(k, starved) > u(k, c0), k + " rises with the companionship need");
        }
        // loneliness -> CONVERSE
        assertTrue(u(SocialActKind.CONVERSE, friendCtx(base.with(Map.of(), Map.of(Emotion.LONELINESS, 0.8), Map.of())))
                > u(SocialActKind.CONVERSE, c0));
        // agreeableness and empathy -> COMPLIMENT
        assertTrue(u(SocialActKind.COMPLIMENT, friendCtx(base.with(Map.of(Trait.AGREEABLENESS, 0.95, Trait.EMPATHY, 0.9), Map.of(), Map.of())))
                > u(SocialActKind.COMPLIMENT, friendCtx(base.with(Map.of(Trait.AGREEABLENESS, 0.05, Trait.EMPATHY, 0.1), Map.of(), Map.of()))));
        // sociability -> GREET
        assertTrue(u(SocialActKind.GREET, friendCtx(base.with(Map.of(Trait.SOCIABILITY, 0.95), Map.of(), Map.of())))
                > u(SocialActKind.GREET, friendCtx(base.with(Map.of(Trait.SOCIABILITY, 0.05), Map.of(), Map.of()))));
        // loyalty and empathy -> GIVE_GIFT
        assertTrue(u(SocialActKind.GIVE_GIFT, friendCtx(base.with(Map.of(Trait.LOYALTY, 0.95, Trait.EMPATHY, 0.9), Map.of(), Map.of())))
                > u(SocialActKind.GIVE_GIFT, friendCtx(base.with(Map.of(Trait.LOYALTY, 0.05, Trait.EMPATHY, 0.1), Map.of(), Map.of()))));
        // achievement need and ambition -> ASK_HELP; confidence lowers it
        assertTrue(u(SocialActKind.ASK_HELP, friendCtx(base.with(Map.of(Trait.AMBITION, 0.9, Trait.CONFIDENCE, 0.2), Map.of(),
                Map.of(Need.ACHIEVEMENT, 0.9)))) > u(SocialActKind.ASK_HELP, friendCtx(base.with(Map.of(Trait.CONFIDENCE, 0.9), Map.of(), Map.of()))));
        // low honesty and jealousy -> SPREAD_RUMOR (legal only for a not-too-honest bot that trusts the listener)
        assertTrue(u(SocialActKind.SPREAD_RUMOR, friendCtx(base.with(Map.of(Trait.JEALOUSY, 0.9, Trait.HONESTY, 0.1, Trait.AGREEABLENESS, 0.2), Map.of(), Map.of())))
                > u(SocialActKind.SPREAD_RUMOR, friendCtx(base.with(Map.of(Trait.JEALOUSY, 0.1, Trait.HONESTY, 0.7, Trait.AGREEABLENESS, 0.8), Map.of(), Map.of()))));
    }

    @Test
    void grudgesAndFeelingsMoveArgueAvoidApologizeAndReconcile() {
        scene();
        Axes sore = edge(0.5, -0.2, -0.1, 0, 0, 0.3, 0.4, 0);
        Self base = Self.flat(0.5, 0.3);
        SocialContext calm = ctx(base, clara, sore, Stage.RIVAL, Recollection.NONE, List.of());
        SocialContext angry = ctx(base.with(Map.of(Trait.IMPULSIVENESS, 0.9), Map.of(Emotion.ANGER, 0.7), Map.of()), clara, sore, Stage.RIVAL,
                Recollection.NONE, List.of());
        assertTrue(u(SocialActKind.ARGUE, angry) > u(SocialActKind.ARGUE, calm), "anger and impulsiveness feed an argument");
        SocialContext patient = ctx(base.with(Map.of(Trait.AGREEABLENESS, 0.95, Trait.PATIENCE, 0.95), Map.of(), Map.of()), clara, sore,
                Stage.RIVAL, Recollection.NONE, List.of());
        assertTrue(u(SocialActKind.ARGUE, patient) < u(SocialActKind.ARGUE, calm), "agreeable and patient bots argue less");
        SocialContext afraid = ctx(base.with(Map.of(Trait.CONFIDENCE, 0.1), Map.of(Emotion.FEAR, 0.7), Map.of()), clara, sore, Stage.RIVAL,
                Recollection.NONE, List.of());
        assertTrue(u(SocialActKind.AVOID, afraid) > u(SocialActKind.AVOID, calm), "fear feeds withdrawing");
        assertTrue(u(SocialActKind.AVOID, angry) < u(SocialActKind.AVOID, afraid), "anger confronts instead");
        // memory: an open fight the actor started makes an apology likelier than the same bot with no such memory
        Recollection fight = new Recollection(true, true, false, 0, 1);
        Self kind = base.with(Map.of(Trait.EMPATHY, 0.8, Trait.AGREEABLENESS, 0.8), Map.of(Emotion.EMBARRASSMENT, 0.4), Map.of());
        Axes cooled = edge(0.5, 0, 0.1, 0, 0, 0.1, 0.2, 0);
        SocialContext started = ctx(kind, bob, cooled, Stage.ESTRANGED, fight, List.of());
        SocialContext theirs = ctx(kind, bob, cooled, Stage.ESTRANGED, new Recollection(true, false, false, 0, 1), List.of());
        assertTrue(u(SocialActKind.APOLOGIZE, started) > u(SocialActKind.APOLOGIZE, theirs), "owning the fight raises the apology");
        Self proud = base.with(Map.of(Trait.CONFIDENCE, 0.95, Trait.EMPATHY, 0.1, Trait.AGREEABLENESS, 0.2), Map.of(Emotion.PRIDE, 0.8), Map.of());
        assertTrue(u(SocialActKind.APOLOGIZE, ctx(proud, bob, cooled, Stage.ESTRANGED, fight, List.of()))
                < u(SocialActKind.APOLOGIZE, started), "pride holds an apology back");
        Recollection sorry = new Recollection(true, true, true, 0, 1);
        Self lonelyKind = kind.with(Map.of(Trait.LOYALTY, 0.9), Map.of(Emotion.LONELINESS, 0.7), Map.of(Need.COMPANIONSHIP, 0.8));
        assertTrue(u(SocialActKind.RECONCILE, ctx(lonelyKind, bob, cooled, Stage.ESTRANGED, sorry, List.of()))
                > u(SocialActKind.RECONCILE, ctx(proud, bob, cooled, Stage.ESTRANGED, sorry, List.of())));
    }

    @Test
    void theTargetsCompanyChangesWhatTheActorDoes() {
        scene();
        Self jealous = mind(Map.of(Trait.JEALOUSY, 0.9), Map.of(), Map.of(Need.COMPANIONSHIP, 0.7));
        Axes warm = edge(0.6, 0.5, 0.5, 0.3, 0, 0, 0, 0);
        SocialContext alone = ctx(jealous, bob, warm, Stage.FRIEND, Recollection.NONE, List.of());
        SocialContext withRival = ctx(jealous, bob, warm, Stage.FRIEND, Recollection.NONE,
                List.of(new Company(CLARA, TO_CLARA, Stage.RIVAL)));
        assertTrue(u(SocialActKind.CONVERSE, withRival) < u(SocialActKind.CONVERSE, alone), "a jealous bot hesitates");
        assertTrue(u(SocialActKind.SPEND_TIME, withRival) < u(SocialActKind.SPEND_TIME, alone));
        // and the target standing with someone the actor trusts makes confronting it likelier (Alice sees Clara with Bob)
        SocialContext clarasAlone = ctx(hothead(), clara, TO_CLARA, Stage.RIVAL, Recollection.NONE, List.of());
        SocialContext clarasWithBob = ctx(hothead(), clara, TO_CLARA, Stage.RIVAL, Recollection.NONE,
                List.of(new Company(BOB, TO_BOB, Stage.CLOSE_FRIEND)));
        assertTrue(u(SocialActKind.ARGUE, clarasWithBob) > u(SocialActKind.ARGUE, clarasAlone));
    }

    // ── norms ──

    @Test
    void normsCloseAFewActsRegardlessOfUtility() {
        scene();
        Self hot = hothead();
        // never argue with a close friend unless resentment is high
        SocialContext close = ctx(hot, bob, edge(0.7, 0.7, 0.6, 0.4, 0, 0.3, 0.2, 0), Stage.CLOSE_FRIEND, Recollection.NONE, List.of());
        assertNotNull(SocialActions.action(SocialActKind.ARGUE).preconditions(close));
        SocialContext closeSore = ctx(hot, bob, edge(0.7, 0.2, 0.2, 0.2, 0, 0.3, 0.6, 0), Stage.CLOSE_FRIEND, Recollection.NONE, List.of());
        assertEquals(null, SocialActions.action(SocialActKind.ARGUE).preconditions(closeSore), "high resentment lifts the norm");
        // an honest bot does not spread a rumor; a stranger is not greeted or complimented
        assertNotNull(SocialActions.action(SocialActKind.SPREAD_RUMOR).preconditions(
                ctx(Self.flat(0.5, 0.3).with(Map.of(Trait.HONESTY, 0.95), Map.of(), Map.of()), bob, TO_BOB, Stage.FRIEND, Recollection.NONE, List.of())));
        SocialContext stranger = ctx(Self.flat(0.5, 0.3), bob, Axes.NONE, Stage.STRANGER, Recollection.NONE, List.of());
        for (SocialActKind k : List.of(SocialActKind.GREET, SocialActKind.COMPLIMENT, SocialActKind.ARGUE, SocialActKind.AVOID,
                SocialActKind.GIVE_GIFT, SocialActKind.INVITE)) {
            assertNotNull(SocialActions.action(k).preconditions(stranger), k + " needs an acquaintance");
        }
        // out of earshot: nothing that talks
        alice = world.put(ALICE, "Alice", 0, 0);
        SocialWorld.Body far = world.put(BOB, "Bob", 5000, 0);
        SocialContext away = new SocialContext(now(), alice, far, Self.flat(0.5, 0.3), TO_BOB, Stage.FRIEND, Recollection.NONE, List.of(),
                true, true, true, true, false, null);
        assertNotNull(SocialActions.action(SocialActKind.CONVERSE).preconditions(away));
        assertNotNull(SocialActions.action(SocialActKind.ARGUE).preconditions(away));
    }

    @Test
    void theChoiceUsesOnlyWhatTheBotKnowsAndNeverAPlayer() {
        scene();
        mem(ALICE);
        // a stranger beside it and a real player are never candidates; and a bot with no bond to anyone has no pick
        assertTrue(SocialActions.best(ALICE, List.of(BOB, CLARA, 7), now()).isEmpty(), "strangers: nothing to act on");
        SocialTestKit.befriend(ALICE, BOB, false, now());
        mem(ALICE).mind(m -> { m.feel(Emotion.LONELINESS, 0.8, now()); m.satisfy(Need.COMPANIONSHIP, -1, now()); return null; });
        Optional<Scored> pick = SocialActions.best(ALICE, List.of(BOB, CLARA, 7), now());
        assertTrue(pick.isPresent() && pick.get().targetId() == BOB, "the friend it knows: " + show(pick));
    }

    @Test
    void rankListsOnlyLegalActsInEnumOrder() {
        scene();
        SocialContext c = friendCtx(sociable());
        List<Scored> ranked = SocialActions.rank(c, new Random(1));
        List<SocialActKind> kinds = new ArrayList<>();
        ranked.forEach(r -> kinds.add(r.kind()));
        assertTrue(kinds.contains(SocialActKind.CONVERSE) && kinds.contains(SocialActKind.COMPLIMENT));
        assertTrue(!kinds.contains(SocialActKind.APOLOGIZE) && !kinds.contains(SocialActKind.ACCEPT), "needs a fight / an invite");
        EnumMap<SocialActKind, Integer> pos = new EnumMap<>(SocialActKind.class);
        for (int i = 0; i < kinds.size(); i++) {
            pos.put(kinds.get(i), i);
        }
        for (int i = 1; i < kinds.size(); i++) {
            assertTrue(kinds.get(i - 1).ordinal() < kinds.get(i).ordinal());
        }
    }
}
