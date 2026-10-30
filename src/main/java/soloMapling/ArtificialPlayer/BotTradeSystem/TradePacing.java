package soloMapling.ArtificialPlayer.BotTradeSystem;

import java.util.Random;

/**
 * 1.6.2 ticket 99: human beats inside a trade window, ported from nutnnut/Cosmic {@code BotTradePacing} (dev
 * 77e0c5bc9): noticing the popup and clicking accept, finding and dragging an item in, typing a meso amount - each is
 * one step beat; the final confirm gets a longer re-read-the-window beat. Every bot-driven window step a companion
 * takes ({@link BotGiveTrade}) draws its delay here, so none of them acts faster than a person could.
 *
 * <p>Ours: {@code -Dslums.tradePacing.scale} (default 1.0) scales every beat, for tests only. BotTradeSM (the
 * merchants' buy/sell window) keeps its own state-tick timing; HumanTiming paces chat lines, not window steps.
 */
public final class TradePacing {

    /** One window step: see it, move the mouse, do it. */
    public static final int STEP_MIN_MS = 900;
    public static final int STEP_MAX_MS = 2_200;
    /** Final confirm: a person re-reads the window before hitting trade. */
    public static final int CONFIRM_MIN_MS = 1_500;
    public static final int CONFIRM_MAX_MS = 3_000;

    private TradePacing() {
    }

    public static long stepMs(Random rng) {
        return scaled(between(rng, STEP_MIN_MS, STEP_MAX_MS));
    }

    public static long confirmMs(Random rng) {
        return scaled(between(rng, CONFIRM_MIN_MS, CONFIRM_MAX_MS));
    }

    private static long between(Random rng, int lo, int hi) {
        return lo + rng.nextInt(hi - lo + 1);
    }

    private static long scaled(long ms) {
        double scale;
        try {
            scale = Double.parseDouble(System.getProperty("slums.tradePacing.scale", "1.0"));
        } catch (NumberFormatException e) {
            scale = 1.0;
        }
        return Math.max(0, Math.round(ms * Math.max(0.0, scale)));
    }
}
