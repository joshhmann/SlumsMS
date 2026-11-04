package soloMapling.ArtificialPlayer.BotLifeSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The mind lives in the bot's life memory: events move it, it saves and restores, and a read costs no write. */
class LifeMemoryMindTest {

    private static final int ID = 1_000_000_077;
    private final AtomicLong clock = new AtomicLong(1_000_000L);

    @AfterEach
    void restore() {
        SocialSim.reset();
    }

    private LifeMemory fresh() {
        SocialSim.useForTest(clock::get, 5);
        return new LifeMemory(ID, null);
    }

    private static double emotion(LifeMemory m, Emotion e, long at) {
        return m.mind(mind -> mind.emotion(e, at));
    }

    @Test
    void aSharedPartyLiftsHappinessAndMeetsTheNeedForCompany() {
        LifeMemory m = fresh();
        double lonelyBefore = m.mind(x -> x.need(Need.COMPANIONSHIP, clock.get()));
        double gladBefore = emotion(m, Emotion.HAPPINESS, clock.get());
        m.push(SocialProfile.Push.PARTY);
        assertTrue(emotion(m, Emotion.HAPPINESS, clock.get()) > gladBefore);
        assertTrue(m.mind(x -> x.need(Need.COMPANIONSHIP, clock.get())) < lonelyBefore);
    }

    @Test
    void aKillStealMakesTheBotAngryAndTheAngerFades() {
        LifeMemory m = fresh();
        m.push(SocialProfile.Push.KSED);
        double now = emotion(m, Emotion.ANGER, clock.get());
        assertTrue(now > 0.15);
        long later = clock.get() + 12 * Emotion.ANGER.halfLifeMs;
        assertTrue(emotion(m, Emotion.ANGER, later) < now / 100);
    }

    @Test
    void theMindSurvivesASaveAndARestart() {
        LifeMemory m = fresh();
        m.push(SocialProfile.Push.RIVALRY);
        m.push(SocialProfile.Push.FAMED);
        long at = clock.get();
        Map<String, Object> saved = m.save(at);
        assertNotNull(saved.get("mind"));

        LifeMemory reborn = new LifeMemory(ID, saved);
        for (Emotion e : Emotion.values()) {
            assertEquals(emotion(m, e, at + 3_600_000L), emotion(reborn, e, at + 3_600_000L), 1e-9, e.name());
        }
        for (Need n : Need.values()) {
            assertEquals(m.mind(x -> x.need(n, at + 3_600_000L)), reborn.mind(x -> x.need(n, at + 3_600_000L)), 1e-9);
        }
    }

    @Test
    void aMindNobodyAskedAboutIsWrittenBackAsRead() {
        LifeMemory m = fresh();
        m.push(SocialProfile.Push.SCAMMED);
        Map<String, Object> first = m.save(clock.get());
        LifeMemory reborn = new LifeMemory(ID, first);
        Map<String, Object> second = reborn.save(clock.get()); // never touched: must not drop the mind
        assertEquals(first.get("mind"), second.get("mind"));
        assertTrue(emotion(new LifeMemory(ID, second), Emotion.SADNESS, clock.get()) > 0.1);
    }

    @Test
    void readingTheMindDoesNotMarkTheMemoryDirtyButChangingItDoes() {
        LifeMemory m = fresh();
        m.takeDirty(clock.get()); // clear the fresh memory's flag
        assertFalse(m.dirty());
        double ignored = emotion(m, Emotion.HAPPINESS, clock.get());
        assertFalse(m.dirty(), "a read costs no write");
        m.mind(x -> {
            x.feel(Emotion.PRIDE, 0.3, clock.get());
            return null;
        });
        assertTrue(m.dirty());
    }

    @Test
    void anUnreadableSavedMindIsIgnoredAndTheRestOfTheMemoryLoads() {
        LifeMemory m = fresh();
        Map<String, Object> saved = new java.util.LinkedHashMap<>(m.save(clock.get()));
        saved.put("mind", "garbage");
        LifeMemory reborn = new LifeMemory(ID, saved);
        assertTrue(reborn.restored());
        SocialMind probe = reborn.mind(x -> x);
        assertEquals(Emotion.ANGER.baseline, probe.emotion(Emotion.ANGER, clock.get()));
    }
}
