package soloMapling.mechanics;

import client.Character;
import server.expeditions.ExpeditionType;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.BotLogger.log;

/**
 * The death budget of one event instance (ticket 130, phase 5, phase 1), ported from BotStory
 * {@code server/expeditions/ExpeditionDeathCount.java} (reviewed at sha 81a3b1df).
 *
 * <p>One counter per instance, charged on every death, and <b>only humans are charged</b>: a population bot
 * ({@link Character#isBot()}) or an owned alt ({@link Character#isOwnedAlt()}) dying costs the party nothing, because
 * a bot party that wipes is not the run's loss and would otherwise end a human's expedition. When the budget runs out
 * the instance ends: the party hears the line, everyone is taken out and the instance is disposed (see
 * {@code EventInstanceManager#endForDeathCount}).
 *
 * <p>Armed from a switch, off by default: {@code -Dslums.bossfight.deathcount=true}, with the cap per run from
 * {@code -Dslums.bossfight.deathcount.cap} (default {@value #DEFAULT_CAP}); one boss type may override it with
 * {@code -Dslums.bossfight.deathcount.cap.<type>} (for example {@code -Dslums.bossfight.deathcount.cap.zakum=5}).
 * With the switch off nothing is armed and the death path behaves exactly as it did before this class existed.
 *
 * <p>This serves the real fight only. {@code soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns} is the other,
 * unchanged path (a roll with the bots logged out) and never reaches this counter.
 */
public final class ExpeditionDeathCount {

    /** The switch that arms the count for expedition runs. Off by default. */
    public static final String FLAG = "slums.bossfight.deathcount";
    /** The deaths a party gets per run, when the switch is on. */
    public static final String CAP_PROPERTY = "slums.bossfight.deathcount.cap";
    public static final int DEFAULT_CAP = 10;

    private static final int NOT_ARMED = -1;

    /** What one death did to the budget. */
    public enum Charge {
        /** Not armed, or the run already ended: nothing was spent. */
        OFF,
        /** One death spent, the party is still fighting. */
        SPENT,
        /** The death that spent the last one: the run ends. */
        EXHAUSTED
    }

    private final AtomicInteger left = new AtomicInteger(NOT_ARMED);
    private final AtomicBoolean ended = new AtomicBoolean();

    /**
     * The deaths a run of this boss type hands its party, or 0 when the count is not armed. Read once per run, when
     * the instance registers its expedition.
     */
    public static int configuredFor(ExpeditionType type) {
        if (type == null || !Boolean.getBoolean(FLAG)) {
            return 0;
        }
        String perBoss = CAP_PROPERTY + "." + type.name().toLowerCase(Locale.ROOT);
        return Math.max(0, intProperty(perBoss, intProperty(CAP_PROPERTY, DEFAULT_CAP)));
    }

    private static int intProperty(String key, int fallback) {
        String value = System.getProperty(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }

        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            log("[BossFight] " + key + "=" + value + " is not a number, using " + fallback);
            return fallback;
        }
    }

    /** Arms the counter for one run. A non-positive cap leaves it unarmed, which is what the switch off means. */
    public boolean arm(int deaths) {
        if (deaths <= 0) {
            return false;
        }

        left.set(deaths);
        return true;
    }

    /** Whether this instance has a budget at all. */
    public boolean isOn() {
        return left.get() >= 0;
    }

    /** Deaths the party still has, or -1 when the count is not armed. */
    public int left() {
        return left.get();
    }

    /**
     * Spends one death. The first death with nothing left spends the run once ({@link Charge#EXHAUSTED}); every death
     * after that, and every death while unarmed, does nothing, so a run cannot end twice.
     */
    public Charge charge() {
        int before = left.getAndUpdate(v -> v > 0 ? v - 1 : v);
        if (before > 0) {
            return Charge.SPENT;
        }
        if (before == 0 && ended.compareAndSet(false, true)) {
            return Charge.EXHAUSTED;
        }
        return Charge.OFF;
    }

    /** Only humans are charged: a bot or an owned alt dying is not the party's loss. */
    public static boolean counts(Character chr) {
        return chr != null && !chr.isBot() && !chr.isOwnedAlt();
    }
}
