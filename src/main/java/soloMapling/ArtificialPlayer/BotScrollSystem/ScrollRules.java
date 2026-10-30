package soloMapling.ArtificialPlayer.BotScrollSystem;

import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.server.channel.handlers.ScrollHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Ticket 11: the decisions a scrolling bot makes, as plain functions over plain facts so they can be tested without
 * the item data.
 * <ul>
 *   <li><b>Which scroll fits which equip:</b> the server's own rules ({@link ScrollHandler#canScroll} and the
 *   scroll's {@code req} list), and a slot left. Bots use plain stat scrolls only: no chaos, clean slate, spikes,
 *   cold protection or white scroll as the scroll itself.</li>
 *   <li><b>Which scroll a bot reaches for,</b> by its {@link Appetite}: a careful bot takes the surest scroll, a
 *   normal one the best expected gain, a gambler the biggest stats. 10% scrolls and dark scrolls are rare outside
 *   the gambler; a dark scroll never goes on a careful bot's gear or anyone's worn gear but a gambler's.</li>
 *   <li><b>White scroll:</b> spent on a 10%/30% scroll by a careful or normal bot that owns one.</li>
 *   <li><b>Megaphones:</b> only for a real, notable outcome: a perfect item of 5+ slots, or a boom of an item that
 *   had passed 3+ scrolls.</li>
 * </ul>
 */
public final class ScrollRules {

    private ScrollRules() {
    }

    /** How much risk a bot takes with its scrolls; fixed per bot (from its id), so a bot is consistent. */
    public enum Appetite {
        CAREFUL, NORMAL, GAMBLER;

        /**
         * 40% careful, 50% normal, 10% gamblers, spread evenly over bot ids (1.5.0 rc3: gamblers 15% -> 10% to keep
         * bot-made godlies rare; economy.yaml {@code scrolling.carefulPercent} / {@code scrolling.gamblerPercent}).
         */
        public static Appetite of(int botId) {
            int h = Math.floorMod(Integer.rotateLeft(botId * 0x9E3779B9, 7) ^ botId, 100);
            int careful = soloMapling.Economy.EconomyConfig.getInt("scrolling.carefulPercent", 40);
            int gambler = soloMapling.Economy.EconomyConfig.getInt("scrolling.gamblerPercent", 10);
            return h < careful ? CAREFUL : h < 100 - gambler ? NORMAL : GAMBLER;
        }

        public static Appetite parse(String s, Appetite fallback) {
            if (s != null) {
                for (Appetite a : values()) {
                    if (a.name().equalsIgnoreCase(s.trim())) {
                        return a;
                    }
                }
            }
            return fallback;
        }
    }

    /** One scroll as the item data describes it: odds in percent, and a weight of what a pass adds. */
    public record ScrollFacts(int scrollId, int success, int curse, int gainWeight) {
        public boolean dark() {
            return curse > 0;
        }

        /** A 10% or 30% scroll: the long shots. */
        public boolean longShot() {
            return success <= 30;
        }
    }

    /** The equip a bot considers scrolling. */
    public record Target(int itemId, int slotsLeft, boolean worn) {
    }

    /** What the bot does next: this scroll, with or without a white scroll. */
    public record Choice(int scrollId, boolean whiteScroll) {
    }

    /** A plain stat scroll a bot may use (it is a scroll, and not one of the special ones). */
    public static boolean isPlainScroll(int scrollId) {
        return scrollId / 10000 == 204 && scrollId / 1000 != 2049 && !ItemConstants.isCleanSlate(scrollId)
                && !ItemConstants.isChaosScroll(scrollId) && !ItemConstants.isModifierScroll(scrollId);
    }

    /**
     * Whether the scroll can go on the equip, by the rules ScrollHandler applies to a player: a slot left, the
     * scroll's {@code req} list (empty = any) and its category ({@link ScrollHandler#canScroll}).
     */
    public static boolean fits(int scrollId, int equipItemId, int slotsLeft, List<Integer> scrollReqs) {
        if (!isPlainScroll(scrollId) || slotsLeft < 1) {
            return false;
        }
        if (scrollReqs != null && !scrollReqs.isEmpty() && !scrollReqs.contains(equipItemId)) {
            return false;
        }
        return ScrollHandler.canScroll(scrollId, equipItemId);
    }

    /** Never scrolled by a bot: wedding rings (a curse would not destroy them, and they are not the bot's to risk). */
    public static boolean scrollableEquip(int equipItemId) {
        return equipItemId / 1000000 == 1 && !ItemId.isWeddingRing(equipItemId);
    }

    /** What one pass adds, as one number: attack counts most, then main stats, then the rest. */
    public static int gainWeight(Map<String, Integer> scrollStats) {
        if (scrollStats == null) {
            return 0;
        }
        int w = 0;
        w += 10 * (scrollStats.getOrDefault("PAD", 0) + scrollStats.getOrDefault("MAD", 0));
        w += 3 * (scrollStats.getOrDefault("STR", 0) + scrollStats.getOrDefault("DEX", 0)
                + scrollStats.getOrDefault("INT", 0) + scrollStats.getOrDefault("LUK", 0));
        w += scrollStats.getOrDefault("PDD", 0) + scrollStats.getOrDefault("MDD", 0)
                + scrollStats.getOrDefault("ACC", 0) + scrollStats.getOrDefault("EVA", 0)
                + scrollStats.getOrDefault("Speed", 0) + scrollStats.getOrDefault("Jump", 0)
                + (scrollStats.getOrDefault("MHP", 0) + scrollStats.getOrDefault("MMP", 0)) / 5;
        return Math.max(1, w);
    }

    /**
     * The scroll a bot of this appetite uses on the target next, from the scrolls it holds that fit it; null when it
     * would rather not (no slot left, nothing fits, or only scrolls it does not take that risk with).
     *
     * @param fitting  the held scrolls that fit the target ({@link #fits})
     * @param hasWhite whether the bot holds a white scroll
     */
    /**
     * Whether this appetite may ever use this scroll on this target at all: the persona rules of {@link #choose} as one
     * predicate, so a search that picks by expected value (the {@code -Dslums.scroll.ev} planner) never reaches for a
     * scroll the plain rules would refuse - a dark scroll on a careful bot's gear or on anyone's worn gear but a
     * gambler's, a 10%/30% scroll while the world's godly cap is spent or, for a careful bot, without a white scroll
     * and never on gear it wears.
     *
     * @param whiteOk true when the bot holds a white scroll and {@code scrolling.whiteOnLongShots} lets it be spent
     */
    public static boolean allows(Appetite appetite, ScrollFacts f, Target target, boolean whiteOk,
                                 boolean longShotsAllowed) {
        if (appetite == null || f == null || target == null || f.success() <= 0) {
            return false;
        }
        if (f.dark()) {
            return longShotsAllowed && (appetite == Appetite.GAMBLER
                    || (appetite == Appetite.NORMAL && !target.worn()));
        }
        if (f.longShot()) {
            return longShotsAllowed && (appetite != Appetite.CAREFUL || (whiteOk && !target.worn()));
        }
        return true;
    }

    public static Choice choose(Appetite appetite, Target target, List<ScrollFacts> fitting, boolean hasWhite,
                                Random rng) {
        return choose(appetite, target, fitting, hasWhite, rng, true);
    }

    /**
     * As above; {@code longShotsAllowed} false (the world's daily cap of bot-made godlies is reached,
     * {@code scrolling.godliesPerDay}) leaves only the 60%+ non-dark scrolls on the table for everyone.
     */
    public static Choice choose(Appetite appetite, Target target, List<ScrollFacts> fitting, boolean hasWhite,
                                Random rng, boolean longShotsAllowed) {
        if (target == null || target.slotsLeft() < 1 || fitting == null || fitting.isEmpty()) {
            return null;
        }
        List<ScrollFacts> safe = new ArrayList<>();
        List<ScrollFacts> longShots = new ArrayList<>();
        List<ScrollFacts> dark = new ArrayList<>();
        for (ScrollFacts f : fitting) {
            if (f.success() <= 0) {
                continue;
            }
            if (f.dark()) {
                dark.add(f);
            } else if (f.longShot()) {
                longShots.add(f);
            } else {
                safe.add(f);
            }
        }
        if (!longShotsAllowed) {
            dark.clear();
            longShots.clear();
        }
        // 1.5.0 rc3 (gear audit): white scrolls on long shots are what pushes godlies past the target; off by default
        boolean whiteOk = hasWhite && soloMapling.Economy.EconomyConfig.getBool("scrolling.whiteOnLongShots", false);
        double longShotChance = soloMapling.Economy.EconomyConfig.getDouble("scrolling.normalLongShotChance", 0.10);
        double normalDark = soloMapling.Economy.EconomyConfig.getDouble("scrolling.normalDarkChance", 0.03);
        double gamblerDark = soloMapling.Economy.EconomyConfig.getDouble("scrolling.gamblerDarkChance", 0.10);
        ScrollFacts pick = null;
        switch (appetite) {
            case CAREFUL -> {
                // the surest scroll; a 10% only under a white scroll, and only on gear it does not wear
                pick = best(safe, Comparator.comparingInt(ScrollFacts::success)
                        .thenComparingInt(ScrollFacts::gainWeight));
                if (pick == null && whiteOk && !target.worn()) {
                    pick = best(longShots, Comparator.comparingInt(ScrollFacts::gainWeight));
                }
            }
            case NORMAL -> {
                // best expected gain; now and then a 10% for the big stats; a dark scroll rarely, never on worn gear
                if (!longShots.isEmpty() && (safe.isEmpty() || rng.nextDouble() < longShotChance)) {
                    pick = best(longShots, Comparator.comparingInt(ScrollFacts::gainWeight));
                } else {
                    pick = best(safe, Comparator.comparingInt((ScrollFacts f) -> f.success() * f.gainWeight()));
                }
                if (!dark.isEmpty() && !target.worn() && rng.nextDouble() < normalDark) {
                    pick = best(dark, Comparator.comparingInt(ScrollFacts::success));
                }
            }
            case GAMBLER -> {
                // the biggest stats; dark scrolls now and then, worn gear or not
                List<ScrollFacts> all = new ArrayList<>(safe);
                all.addAll(longShots);
                pick = best(all, Comparator.comparingInt(ScrollFacts::gainWeight)
                        .thenComparingInt(f -> -f.success()));
                if (!dark.isEmpty() && (pick == null || rng.nextDouble() < gamblerDark)) {
                    pick = best(dark, Comparator.comparingInt(ScrollFacts::gainWeight));
                }
            }
        }
        if (pick == null) {
            return null;
        }
        boolean white = whiteOk && pick.longShot() && appetite != Appetite.GAMBLER;
        return new Choice(pick.scrollId(), white);
    }

    private static ScrollFacts best(List<ScrollFacts> list, Comparator<ScrollFacts> by) {
        return list.stream().max(by).orElse(null);
    }

    /** The megaphone node a real outcome earns, or null: a perfect 5+ slot item, or a boom after 3+ passes. */
    public static String megaphoneFor(BotScrolling.Outcome outcome, int passed, int totalSlots, int slotsLeft) {
        if (outcome == BotScrolling.Outcome.CURSE) {
            return passed >= 3 ? "ScrollBoom" : null;
        }
        if (outcome == BotScrolling.Outcome.SUCCESS && slotsLeft == 0 && totalSlots >= 5 && passed >= totalSlots) {
            return "ScrollPerfect";
        }
        return null;
    }
}
