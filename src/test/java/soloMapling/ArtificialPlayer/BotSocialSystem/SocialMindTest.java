package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SocialMindTest {

    private static final long H = 3_600_000L;

    private static SocialMind mind(int slot, double social, double rival, double explorer, double achiever) {
        return SocialMind.seeded(slot, social, rival, explorer, achiever, 0);
    }

    private static SocialMind plain() {
        return mind(7, 0.25, 0.25, 0.25, 0.25);
    }

    @Test
    void traitsAreBoundedStableAndSeedDriven() {
        SocialMind a = mind(42, 0.5, 0.1, 0.2, 0.2), b = mind(42, 0.5, 0.1, 0.2, 0.2);
        assertEquals(a.traits(), b.traits(), "same inputs, same mind");
        assertEquals(Trait.values().length, a.traits().size());
        a.traits().values().forEach(v -> assertTrue(v >= 0 && v <= 1));
        double socialist = 0, killer = 0;
        double agreeSocial = 0, agreeKiller = 0;
        for (int slot = 0; slot < 400; slot++) {
            SocialMind s = mind(slot, 0.8, 0.05, 0.05, 0.1), k = mind(slot, 0.05, 0.8, 0.05, 0.1);
            socialist += s.trait(Trait.SOCIABILITY);
            killer += k.trait(Trait.SOCIABILITY);
            agreeSocial += s.trait(Trait.AGREEABLENESS);
            agreeKiller += k.trait(Trait.AGREEABLENESS);
        }
        assertTrue(socialist > killer + 400 * 0.2, "socializers are more sociable on average");
        assertTrue(agreeSocial > agreeKiller + 400 * 0.2, "killers are less agreeable on average");
    }

    @Test
    void anEmotionFadesToItsBaselineByItsHalfLife() {
        SocialMind m = plain();
        m.feel(Emotion.FEAR, 1.0, 0);
        double start = m.emotion(Emotion.FEAR, 0);
        assertTrue(start > 0.6);
        assertEquals(start / 2, m.emotion(Emotion.FEAR, Emotion.FEAR.halfLifeMs), 1e-9);
        assertEquals(start / 4, m.emotion(Emotion.FEAR, 2 * Emotion.FEAR.halfLifeMs), 1e-9);
        assertEquals(Emotion.HAPPINESS.baseline, m.emotion(Emotion.HAPPINESS, 1000 * H), 1e-9, "settles at its baseline, not at zero");
        m.feel(Emotion.HAPPINESS, -0.4, 0);
        assertTrue(m.emotion(Emotion.HAPPINESS, 0) < Emotion.HAPPINESS.baseline);
        assertTrue(m.emotion(Emotion.HAPPINESS, 24 * H) > m.emotion(Emotion.HAPPINESS, 0), "recovers upward");
    }

    @Test
    void eventsStackOnWhatIsLeftNotWhatWas() {
        SocialMind m = plain();
        m.feel(Emotion.ANGER, 0.5, 0);
        long later = Emotion.ANGER.halfLifeMs;
        double left = m.emotion(Emotion.ANGER, later);
        m.feel(Emotion.ANGER, 0.2, later);
        double expected = Math.min(1, left + 0.2 * m.reactivity(Emotion.ANGER));
        assertEquals(expected, m.emotion(Emotion.ANGER, later), 1e-9);
        for (int i = 0; i < 20; i++) m.feel(Emotion.ANGER, 0.5, later);
        assertEquals(1.0, m.emotion(Emotion.ANGER, later), 1e-9, "capped at 1");
    }

    @Test
    void impulsiveBotsAngerHarderAndConfidentOnesFearLess() {
        double hot = 0, calm = 0, bold = 0, shy = 0;
        for (int slot = 0; slot < 300; slot++) {
            SocialMind impulsive = mind(slot, 0.25, 0.9, 0.0, 0.0), patient = mind(slot, 0.9, 0.0, 0.0, 0.0);
            impulsive.feel(Emotion.ANGER, 0.2, 0);
            patient.feel(Emotion.ANGER, 0.2, 0);
            hot += impulsive.emotion(Emotion.ANGER, 0);
            calm += patient.emotion(Emotion.ANGER, 0);
            SocialMind confident = mind(slot, 0.2, 0.6, 0.0, 0.9), timid = mind(slot, 0.2, 0.0, 0.0, 0.0);
            confident.feel(Emotion.FEAR, 0.2, 0);
            timid.feel(Emotion.FEAR, 0.2, 0);
            bold += confident.emotion(Emotion.FEAR, 0);
            shy += timid.emotion(Emotion.FEAR, 0);
        }
        assertTrue(hot > calm, "same event, different reaction: " + hot + " vs " + calm);
        assertTrue(shy > bold, "confidence blunts fear: " + shy + " vs " + bold);
    }

    @Test
    void moodFallsWithNegativeEmotionAndRisesWithHappiness() {
        SocialMind m = plain();
        double rest = m.mood(0);
        m.feel(Emotion.SADNESS, 0.8, 0);
        m.feel(Emotion.LONELINESS, 0.8, 0);
        assertTrue(m.mood(0) < rest);
        SocialMind glad = plain();
        glad.feel(Emotion.HAPPINESS, 0.4, 0);
        assertTrue(glad.mood(0) > rest);
        assertTrue(m.mood(0) >= -1 && glad.mood(0) <= 1);
    }

    @Test
    void needsGrowWithTimeFasterInTheBotsThatCareAndFallWhenMet() {
        SocialMind social = mind(3, 0.9, 0.0, 0.0, 0.0), loner = mind(3, 0.0, 0.7, 0.0, 0.0);
        double s0 = social.need(Need.COMPANIONSHIP, 0), l0 = loner.need(Need.COMPANIONSHIP, 0);
        assertEquals(s0, l0, 1e-9, "same slot starts alike");
        double s = social.need(Need.COMPANIONSHIP, 3 * H) - s0, l = loner.need(Need.COMPANIONSHIP, 3 * H) - l0;
        assertTrue(s > l && l >= 0, "sociable bots get lonely sooner: " + s + " vs " + l);
        assertEquals(1.0, social.need(Need.COMPANIONSHIP, 1000 * H), 1e-9, "capped at 1");
        social.satisfy(Need.COMPANIONSHIP, 5.0, 10 * H);
        assertEquals(0.0, social.need(Need.COMPANIONSHIP, 10 * H), 1e-9);
        assertTrue(social.need(Need.COMPANIONSHIP, 12 * H) > 0, "it grows back");
    }

    @Test
    void anIsolatedBotReachesForCompanyAnAmbitiousOneForWork() {
        SocialMind m = mind(5, 0.9, 0.0, 0.0, 0.0);
        for (Need n : Need.values()) m.satisfy(n, 1.0, 0);
        assertEquals(0.0, m.need(Need.COMPANIONSHIP, 0), 1e-9);
        assertEquals(Need.COMPANIONSHIP, strongestAfter(mind(5, 0.9, 0.0, 0.0, 0.0), Need.COMPANIONSHIP, 20 * H));
    }

    private static Need strongestAfter(SocialMind m, Need keepUnmet, long dt) {
        for (Need n : Need.values()) if (n != keepUnmet) m.satisfy(n, 1.0, 0);
        return m.strongest(dt);
    }

    @Test
    void saveAndRestoreKeepValuesAndTheirTimestamps() {
        SocialMind a = plain();
        a.feel(Emotion.SADNESS, 0.6, 1000);
        a.satisfy(Need.REST, 0.3, 2000);
        Map<String, Object> saved = a.save();

        SocialMind b = plain();
        b.restore(saved);
        for (Emotion e : Emotion.values()) assertEquals(a.emotion(e, 9 * H), b.emotion(e, 9 * H), 1e-9, e.name());
        for (Need n : Need.values()) assertEquals(a.need(n, 9 * H), b.need(n, 9 * H), 1e-9, n.name());
    }

    @Test
    void timeAwayDecaysARestoredMindBecauseTheStampIsKept() {
        SocialMind a = plain();
        a.feel(Emotion.ANGER, 1.0, 0);
        SocialMind b = plain();
        b.restore(a.save());
        assertTrue(b.emotion(Emotion.ANGER, 10 * Emotion.ANGER.halfLifeMs) < 0.01, "a long restart is a long cool-down");
    }

    @Test
    void badRowsAndUnknownNamesAreSkippedNeverFatal() {
        SocialMind m = plain();
        Map<String, Object> bad = Map.of("emotions", Map.of("ANGER", List.of(0.5, 10L), "NOPE", List.of(1, 2),
                        "FEAR", "x", "SADNESS", List.of(Double.NaN, 5L)),
                "needs", Map.of("REST", List.of(0.4, 0L), "GHOST", List.of(0.1, 0L)));
        m.restore(bad);
        assertEquals(0.5, m.emotion(Emotion.ANGER, 10), 1e-9);
        assertEquals(Emotion.FEAR.baseline, m.emotion(Emotion.FEAR, 10));
        assertEquals(Emotion.SADNESS.baseline, m.emotion(Emotion.SADNESS, 10), "NaN row skipped");
        m.restore("not a map");
        m.restore(null);
        assertFalse(m.save().isEmpty());
    }
}
