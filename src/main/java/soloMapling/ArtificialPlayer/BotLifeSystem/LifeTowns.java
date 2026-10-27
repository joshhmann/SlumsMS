package soloMapling.ArtificialPlayer.BotLifeSystem;

import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;

/**
 * 1.6.0 towns (owner 2026-10-27: "it feels like we're placing actors in towns, where we should let playerbots
 * naturally come and go"): the towns a brain bot hangs out in, with their level bands, map families and busyness
 * targets ({@link TownPulse}), and where a bot hangs out when no friend, plan, pull or fad says otherwise
 * ({@code BOT_LIFE_TOWN_WEIGHTS}: near, home, henesys). Pure.
 */
public final class LifeTowns {
    private LifeTowns() {}

    /**
     * One town: its main map, the level band a bot hangs out there in, its busyness target at the curve's peak on a
     * weight-1.0 channel (2.5x the owner's 2009 popularity list), whether it is one of the big eight (floor at the
     * trough), and its map family with each map's share of the target.
     */
    public record Town(String name, int mapId, int lo, int hi, int peak, boolean big, int[] family, double[] shares) {
        public boolean fits(int level) { return level >= lo && level <= hi; }
        /** Victoria 1, Ossyria and beyond 2; 0 = reached from anywhere (the Free Market's portals are in every town). */
        int continent() { return mapId == FREE_MARKET ? 0 : mapId / 100_000_000; }
    }

    private static Town town(String name, int mapId, int lo, int hi, int peak, boolean big) {
        return new Town(name, mapId, lo, hi, peak, big, new int[]{mapId}, new double[]{1});
    }

    public static final int FREE_MARKET = 910000000;

    /**
     * The Free Market (owner 2026-10-27: "2009 FM", about 100 on channel 1 at peak): the entrance and its 22 rooms
     * as one family. Its staff (FM bots, merchants, shops) count toward the target; visitors fill the rest.
     */
    private static Town freeMarket() {
        int[] family = new int[23];
        double[] shares = new double[23];
        family[0] = FREE_MARKET;
        shares[0] = .34;
        for (int room = 1; room <= 22; room++) {
            family[room] = FREE_MARKET + room;
            shares[room] = .03;
        }
        return new Town("Free Market", FREE_MARKET, 1, 200, 100, true, family, shares);
    }

    /** The towns (owner 2026-10-27 popularity x2.5) and the Free Market. */
    public static final List<Town> TOWNS = List.of(
            // Henesys's family: the town (most of it: a real client holds hundreds on one map), the market, the park
            // and the pet park (Potion Shop, Game Zone: set pieces)
            new Town("Henesys", 100000000, 1, 200, 63, true,
                    new int[]{100000000, 100000100, 100000200, 100000202}, new double[]{.7, .1, .1, .1}),
            town("Kerning City", 103000000, 1, 70, 35, true),
            town("Ellinia", 101000000, 1, 70, 35, true),
            town("Perion", 102000000, 1, 70, 30, true),
            town("Ludibrium", 220000000, 30, 200, 40, true),
            town("Orbis", 200000000, 25, 200, 30, true),
            town("El Nath", 211000000, 45, 200, 30, true),
            town("Leafre", 240000000, 75, 200, 25, true),
            town("Sleepywood", 105040300, 20, 90, 20, false),
            town("Lith Harbor", 104000000, 1, 70, 20, false),
            town("Nautilus", 120000000, 1, 70, 15, false),
            town("Aquarium", 230000000, 60, 200, 15, false),
            town("Mu Lung", 250000000, 55, 200, 20, false),
            town("Ariant", 260000000, 30, 200, 15, false),
            town("Magatia", 261000000, 65, 200, 15, false),
            town("Omega Sector", 221000000, 45, 200, 15, false),
            freeMarket());

    /** The town whose map family holds {@code mapId}, or null. */
    public static Town townOf(int mapId) {
        for (Town town : TOWNS) for (int id : town.family()) if (id == mapId) return town;
        return null;
    }

    public static boolean isTownMap(int mapId) { return townOf(mapId) != null; }

    /** Where a hang-out came from (telemetry). */
    public enum Source { FRIEND, PLAN, PULL, FAD, NEAR, HOME, HENESYS }

    /** {@code BOT_LIFE_TOWN_WEIGHTS} as near, home, henesys; a blank or broken spec is today's "henesys:100". */
    public static double[] weights(String spec) {
        double[] out = new double[3];
        if (spec == null || spec.isBlank()) return new double[]{0, 0, 1};
        for (String part : spec.split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length != 2) return new double[]{0, 0, 1};
            double w;
            try { w = Double.parseDouble(kv[1].trim()); } catch (NumberFormatException e) { return new double[]{0, 0, 1}; }
            if (!Double.isFinite(w) || w < 0) return new double[]{0, 0, 1};
            switch (kv[0].trim().toLowerCase(Locale.ROOT)) {
                case "near" -> out[0] = w;
                case "home" -> out[1] = w;
                case "henesys" -> out[2] = w;
                default -> { return new double[]{0, 0, 1}; }
            }
        }
        return out[0] + out[1] + out[2] > 0 ? out : new double[]{0, 0, 1};
    }

    /** A weighted pick's town and why. */
    public record Pick(int mapId, Source source) {}

    /**
     * The weighted pick among near (a town map id, or -1), home (-1 when it is not a town in the bot's band) and
     * Henesys. Stable for a bot over a 30-minute bucket. Missing choices drop out and the rest renormalise; with
     * nothing left it is Henesys.
     */
    public static Pick pick(double[] weights, int near, int home, int botId, long now) {
        double n = near > 0 ? weights[0] : 0, h = home > 0 ? weights[1] : 0, hs = weights[2];
        double sum = n + h + hs;
        if (sum <= 0) return new Pick(LifeRuntime.HOME_TOWN, Source.HENESYS);
        double roll = new SplittableRandom(botId * 31L + now / 1_800_000L).nextDouble() * sum;
        if (roll < n) return new Pick(near, Source.NEAR);
        if (roll < n + h) return new Pick(home, Source.HOME);
        return new Pick(LifeRuntime.HOME_TOWN, Source.HENESYS);
    }

    /**
     * The "near" town of a bot on {@code mapId} (whose return map is {@code returnMapId}): the town map it stands on,
     * else its return map's town; -1 when neither is a town or the bot is outside the town's band.
     */
    public static int near(int mapId, int returnMapId, int level) {
        Town here = townOf(mapId);
        if (here != null) return here.fits(level) ? mapId : -1;
        Town back = townOf(returnMapId);
        return back != null && back.fits(level) ? back.mapId() : -1;
    }

    /** The home town (its main map) when the roster home is in a town family and the bot is in its band; else -1. */
    public static int home(int homeMapId, int level) {
        Town town = homeMapId > 0 ? townOf(homeMapId) : null;
        return town != null && town.fits(level) ? town.mapId() : -1;
    }
}
