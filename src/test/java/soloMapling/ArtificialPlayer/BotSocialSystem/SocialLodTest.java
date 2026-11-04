package soloMapling.ArtificialPlayer.BotSocialSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Stage 6b of ticket 181: the social level of detail. The tier is a pure function of (observed, online,
 * lastActiveAt, now); the hold keeps a bot ACTIVE for 30 s after the last observer left; a BACKGROUND bot gets its
 * maintenance at most once per five minutes; a DORMANT one costs nothing and catches up once at login.
 */
class SocialLodTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long MINUTE = 60_000L;
    private static final long HOUR = 3_600_000L;
    private static final int BOT_ID = soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE + 123_456;

    private final AtomicLong clock = new AtomicLong(T0);

    @BeforeEach
    void manualClock() {
        clock.set(T0);
        SocialSim.useForTest(clock::get, 11);
    }

    @AfterEach
    void reset() {
        SocialSim.reset();
        SocialLod.reset();
        LifeMemoryStore.useConnectionSourceForTest(null);
        System.clearProperty("slums.sociallod");
    }

    // ── the pure tier function and its hold ──

    @Test
    void aBotSomebodyWatchesIsActive() {
        assertEquals(SocialLod.Tier.ACTIVE, SocialLod.tier(true, true, 0, T0));
    }

    @Test
    void aBotNobodyWatchedForAWhileIsBackground() {
        assertEquals(SocialLod.Tier.BACKGROUND, SocialLod.tier(false, true, 0, T0));
        assertEquals(SocialLod.Tier.BACKGROUND, SocialLod.tier(false, true, T0 - SocialLod.ACTIVE_HOLD_MS, T0));
    }

    @Test
    void theHoldKeepsTheLastObserverOnTheBotForHalfAMinute() {
        long lastSeen = T0 - SocialLod.ACTIVE_HOLD_MS + 1;
        assertEquals(SocialLod.Tier.ACTIVE, SocialLod.tier(false, true, lastSeen, T0));
        assertEquals(SocialLod.Tier.BACKGROUND, SocialLod.tier(false, true, T0 - SocialLod.ACTIVE_HOLD_MS - 1, T0));
    }

    @Test
    void anOfflineBotIsDormantEvenWhereSomebodyStands() {
        assertEquals(SocialLod.Tier.DORMANT, SocialLod.tier(true, false, T0, T0));
        assertEquals(SocialLod.Tier.DORMANT, SocialLod.tier(false, false, 0, T0));
    }

    // ── the catch-up relief is bounded ──

    @Test
    void reliefGrowsWithTheTimeAwayAndStopsAtADay() {
        assertEquals(0, SocialLod.relief(-5 * HOUR).companionship());
        assertEquals(0, SocialLod.relief(0).belonging());
        assertEquals(0.03, SocialLod.relief(HOUR).companionship(), 1e-9);
        assertEquals(0.012, SocialLod.relief(HOUR).belonging(), 1e-9);
        assertEquals(SocialLod.relief(SocialLod.CATCH_UP_CAP_MS).companionship(),
                SocialLod.relief(7 * 24 * HOUR).companionship(), 1e-9);
        assertEquals(0.72, SocialLod.relief(SocialLod.CATCH_UP_CAP_MS).companionship(), 1e-9);
    }

    @Test
    void aCatchUpNeverDrivesANeedBelowZero() {
        LifeMemory memory = LifeMemoryStore.of(BOT_ID);
        long now = clock.get();
        double before = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));
        SocialLod.catchUpOnLogin(BOT_ID, now, now - 24 * HOUR, now);
        double after = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));
        assertEquals(Math.max(0, before - 0.72), after, 1e-9);
        assertTrue(after >= 0 && after < before);
    }

    @Test
    void aCatchUpLandsOnlyOncePerLogin() {
        LifeMemory memory = LifeMemoryStore.of(BOT_ID);
        long now = clock.get();
        long since = now - 6 * HOUR;
        double before = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));
        SocialLod.catchUpOnLogin(BOT_ID, now, since, now);
        double once = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));
        assertEquals(before - 0.18, once, 1e-9);
        SocialLod.catchUpOnLogin(BOT_ID, now, since, now);
        assertEquals(once, memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now)), 1e-9);
        assertEquals(Long.valueOf(1), SocialLod.stats().get("catch-ups"));
    }

    @Test
    void aShortOrUnknownTimeAwayCatchesUpNothing() {
        LifeMemory memory = LifeMemoryStore.of(BOT_ID);
        long now = clock.get();
        double before = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now));
        SocialLod.catchUpOnLogin(BOT_ID, now, now - SocialLod.CATCH_UP_FLOOR_MS + 1, now);
        SocialLod.catchUpOnLogin(BOT_ID, now + 1, 0, now + 1);
        assertEquals(before, memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, now)), 1e-9);
        assertEquals(Long.valueOf(0), SocialLod.stats().get("catch-ups"));
    }

    // ── the director's sweep ──

    @Test
    void theSweepMaintainsABackgroundBotOnceEveryFiveMinutesAndNeverAnActiveOne() {
        LifeMemory memory = LifeMemoryStore.of(BOT_ID);
        long start = clock.get();
        BotSM bot = bot(BOT_ID);
        try (var movement = mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            movement.when(() -> soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(anyCharacter()))
                    .thenReturn(true);
            SocialLod.maybeStep(start, List.of(bot));
            assertEquals(1, count(SocialLod.Tier.ACTIVE));
            double watched = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, start));

            movement.when(() -> soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(anyCharacter()))
                    .thenReturn(false);
            clock.addAndGet(20_000);
            SocialLod.maybeStep(clock.get(), List.of(bot));            // still inside the hold
            assertEquals(1, count(SocialLod.Tier.ACTIVE));
            assertEquals(Long.valueOf(0), SocialLod.stats().get("maintained"));

            clock.addAndGet(11_000);                                   // 31 s since the last observer

            SocialLod.maybeStep(clock.get(), List.of(bot));            // the hold ran out: BACKGROUND
            assertEquals(1, count(SocialLod.Tier.BACKGROUND));
            double atBackground = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, clock.get()));
            assertTrue(atBackground > watched, "the need kept growing while only a watcher was there");

            clock.addAndGet(5 * MINUTE + 1);
            long pass = clock.get();
            double before = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, pass));
            SocialLod.maybeStep(pass, List.of(bot));
            double afterMaintenance = memory.mind(m -> m.need(SocialMind.Need.COMPANIONSHIP, pass));
            assertEquals(SocialLod.relief(pass - (start + 31_000)).companionship(),
                    before - afterMaintenance, 1e-9);
            assertEquals(Long.valueOf(1), SocialLod.stats().get("maintained"));

            clock.addAndGet(MINUTE);
            SocialLod.maybeStep(clock.get(), List.of(bot));            // too soon again
            assertEquals(Long.valueOf(1), SocialLod.stats().get("maintained"));
        }
    }

    @Test
    void aBotThatLeftTheWorldIsCountedDormant() {
        BotSM bot = bot(BOT_ID);
        try (var movement = mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            movement.when(() -> soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(anyCharacter()))
                    .thenReturn(true);
            SocialLod.maybeStep(clock.get(), List.of(bot));
            assertEquals(1, count(SocialLod.Tier.ACTIVE));
            SocialLod.maybeStep(clock.get() + 1, List.of());
            assertEquals(0, count(SocialLod.Tier.ACTIVE));
            assertEquals(1, count(SocialLod.Tier.DORMANT));
        }
    }

    @Test
    void withTheFlagOffEverybodyIsActiveAndTheSweepDoesNothing() {
        System.setProperty("slums.sociallod", "false");
        assertFalse(SocialLod.enabled());
        LifeMemoryStore.of(BOT_ID);
        BotSM bot = bot(BOT_ID);
        try (var movement = mockStatic(soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.class)) {
            movement.when(() -> soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(anyCharacter()))
                    .thenReturn(false);
            assertEquals(SocialLod.Tier.ACTIVE, SocialLod.tierOf(bot));
            clock.addAndGet(10 * MINUTE);
            SocialLod.maybeStep(clock.get(), List.of(bot));
            assertEquals(SocialLod.Tier.ACTIVE, SocialLod.tierOf(bot));
            assertEquals(Long.valueOf(0), SocialLod.stats().get("maintained"));
            assertEquals(Long.valueOf(0), SocialLod.stats().get("tier changes"));
            assertEquals(0, count(SocialLod.Tier.BACKGROUND) + count(SocialLod.Tier.DORMANT));
            SocialLod.catchUpOnLogin(BOT_ID, clock.get(), clock.get() - 6 * HOUR, clock.get());
            assertEquals(Long.valueOf(0), SocialLod.stats().get("catch-ups"));
        }
    }

    @Test
    void theFlagIsOnByDefault() {
        assertTrue(SocialLod.enabled());
    }

    // ── helpers ──

    private static int count(SocialLod.Tier tier) {
        return SocialLod.census().getOrDefault(tier, 0);
    }

    private static BotSM bot(int charId) {
        BotSM bot = mock(TrainingBot.class);
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(charId);
        when(bot.getChr()).thenReturn(chr);
        return bot;
    }

    private static Character anyCharacter() {
        return org.mockito.ArgumentMatchers.any();
    }
}
