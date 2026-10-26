package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.qa.QaLog;

import java.util.function.IntFunction;
import java.util.function.IntToDoubleFunction;
import java.util.function.LongSupplier;

/**
 * 1.6.0 slice S7 "Beef": what the Beef parts share - the switch, the clock, a bot's Killer and Socializer pull (Bartle,
 * S1) read from memory only, who is online, and the QA log ({@code RIVALRY} topic).
 *
 * <p>The Beef layer ({@link Teasing}, {@link BotBotRivalry}, {@link RageQuit}, {@link GuildFeuds},
 * {@link VillainArc}, {@link DramaNews}; crushes live in {@code BotCoupleSystem.BotCrushes}): PG-13, insults about
 * the game only, never "I'm an AI" - every scripted line goes through {@link DramaKit#pick} (ContentGuard), every model
 * line through ContentGuard too. Bot to bot is free; anything aimed at a real player goes through
 * {@link soloMapling.ArtificialPlayer.SocialGate} (teasing is open to all; beef needs started-it).
 *
 * <p>{@code -Dsolomapling.drama.beef=false} turns the S7 layer off (the 1.5.x drama keeps running).
 */
public final class BeefKit {

    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile IntToDoubleFunction rivalPull = BeefKit::seededRivalPull;
    private static volatile IntToDoubleFunction socialPull = BeefKit::seededSocialPull;
    private static volatile IntFunction<Character> online = DramaKit::liveBot;

    private BeefKit() {
    }

    public static boolean enabled() {
        return DramaKit.enabled() && !"false".equalsIgnoreCase(System.getProperty("solomapling.drama.beef", "true"));
    }

    static long now() {
        return clock.getAsLong();
    }

    /** The Killer pull 0..1 (rivalry, KS spats, trash talk), from the bot's loaded life memory or its seeded point. */
    static double rivalPull(int charId) {
        try {
            return Math.max(0, Math.min(1, rivalPull.applyAsDouble(charId)));
        } catch (RuntimeException e) {
            return 0.1;
        }
    }

    /** The Socializer pull 0..1 (crushes, crews). */
    static double socialPull(int charId) {
        try {
            return Math.max(0, Math.min(1, socialPull.applyAsDouble(charId)));
        } catch (RuntimeException e) {
            return 0.25;
        }
    }

    static Character online(int charId) {
        return online.apply(charId);
    }

    static String name(int charId) {
        Character c = online(charId);
        return c == null ? null : c.getName();
    }

    private static SocialProfile profile(int charId) {
        LifeMemory m = LifeMemoryStore.peek(charId); // memory only: never a table read on a hot path
        return m != null ? m.social() : SocialProfile.forCharacter(charId);
    }

    private static double seededRivalPull(int charId) {
        return profile(charId).rivalPull();
    }

    private static double seededSocialPull(int charId) {
        return profile(charId).socialPull();
    }

    static void qa(int mapId, String line) {
        DramaKit.qa(QaLog.Topic.RIVALRY, mapId, line);
    }

    static void qaAny(String line) {
        if (QaLog.on(QaLog.Topic.RIVALRY)) {
            QaLog.any(QaLog.Topic.RIVALRY, line);
        }
    }

    // ── test seams ──

    /** Tests: a clock, the two pulls and who is online (nulls restore the real ones). */
    static void useForTest(LongSupplier testClock, IntToDoubleFunction testRival, IntToDoubleFunction testSocial,
                           IntFunction<Character> testOnline) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        rivalPull = testRival != null ? testRival : BeefKit::seededRivalPull;
        socialPull = testSocial != null ? testSocial : BeefKit::seededSocialPull;
        online = testOnline != null ? testOnline : DramaKit::liveBot;
    }

    /** Public seam for tests in other packages (BotThreads, couples): the clock and the pulls. */
    public static void usePullsForTest(IntToDoubleFunction testRival, IntToDoubleFunction testSocial) {
        rivalPull = testRival != null ? testRival : BeefKit::seededRivalPull;
        socialPull = testSocial != null ? testSocial : BeefKit::seededSocialPull;
    }
}
