package soloMapling.ArtificialPlayer.BotGrindSystem;

import java.util.ArrayList;
import java.util.List;

/*
 * Ticket 162 part 2: "where should I grind?" with gear progression first and EXP as the tiebreaker. A port of
 * nutnnut/Cosmic's BotGrindPlanner (GreenCatMS, https://github.com/nutnnut/Cosmic, AGPL-3.0,
 * src/main/java/server/bots/BotGrindPlanner.java at dev ad57bf2c3): seekSeconds, killsPerHour (demand capped by the
 * respawn supply), desirability (DPS gain discounted by attainability within a 2-hour horizon), the gear need that
 * blends the EXP and gear lenses into one score, and the farm-an-item pick by expected items an hour.
 *
 * Pure: GearFirstAdvisor builds the candidates from our map index, the offscreen kill rate and the drop table, and
 * TrainingMapChooser uses the scores. Ours: the scores come back for every candidate (they weight our capacity- and
 * distance-aware weighted pick instead of upstream's near-best draw), and there is no party planning (our parties
 * pick maps elsewhere).
 */
public final class GearFirstPlanner {

    private GearFirstPlanner() {}

    static final double SEEK_BASE_SECONDS = 3.0;
    static final double TYPICAL_SPAWN_POINTS = 8.0;
    static final double TYPICAL_AREA_PER_SPAWN = 250_000.0;
    static final double SEEK_MIN_SECONDS = 0.5;
    static final double SEEK_MAX_SECONDS = 15.0;
    static final double RESPAWN_PERIOD_SECONDS = 10.0;
    /* A best attainable upgrade of this DPS fraction (or more) makes gear the dominant need. */
    public static final double GEAR_DOMINANT_DPS_GAIN = 0.30;
    static final double ATTAINABILITY_HORIZON_HOURS = 2.0;
    /* Below this, the bot is not gear-driven (upstream MEANINGFUL_GEAR_DESIRE). */
    public static final double MEANINGFUL_GEAR_DESIRE = 0.02;

    /* A drop that would improve the bot's gear: chance per kill (map-diluted), and the DPS fraction it adds. */
    public record GearProspect(int itemId, double chancePerKill, double dpsGainFraction) {
    }

    /* One map: the bot's time to kill one of its mobs, EXP per kill, spawn points, area (0 = unknown), drops. */
    public record Candidate(int mapId, double killSeconds, double exp, int spawnPoints, int mapAreaPx,
                            List<GearProspect> gearDrops) {
    }

    /* Every candidate's score on one [0,1] scale, the gear need, and the most desirable drop anywhere. */
    public record Scores(double[] unified, double needGear, GearProspect wanted, double wantedDesire) {
        public boolean gearFirst() {
            return wantedDesire >= MEANINGFUL_GEAR_DESIRE;
        }
    }

    static double seekSeconds(int mapAreaPx, int spawnPoints) {
        double sparseness = mapAreaPx > 0
                ? (mapAreaPx / (double) Math.max(1, spawnPoints)) / TYPICAL_AREA_PER_SPAWN
                : TYPICAL_SPAWN_POINTS / Math.max(1, spawnPoints);
        return Math.clamp(SEEK_BASE_SECONDS * sparseness, SEEK_MIN_SECONDS, SEEK_MAX_SECONDS);
    }

    static double killsPerHour(Candidate c) {
        double demand = 3600.0 / (Math.max(0.1, c.killSeconds()) + seekSeconds(c.mapAreaPx(), c.spawnPoints()));
        double supply = c.spawnPoints() * 3600.0 / RESPAWN_PERIOD_SECONDS;
        return Math.min(demand, supply);
    }

    static double desirability(GearProspect g, double killsPerHour) {
        double expectedCopies = g.chancePerKill() * killsPerHour * ATTAINABILITY_HORIZON_HOURS;
        return g.dpsGainFraction() * Math.min(1.0, expectedCopies);
    }

    /*
     * Upstream computeLenses + unifiedScores: EXP an hour and gear value an hour, each over the best candidate's, blended
     * by how gear-hungry the bot is (no meaningful upgrade anywhere = pure EXP; a dominant one = gear-led).
     */
    public static Scores score(List<Candidate> candidates) {
        int n = candidates.size();
        double bestDesire = 0;
        GearProspect wanted = null;
        double[] exp = new double[n];
        double[] gear = new double[n];
        for (int i = 0; i < n; i++) {
            Candidate c = candidates.get(i);
            double kph = killsPerHour(c);
            exp[i] = c.exp() * kph;
            double g = 0;
            for (GearProspect p : c.gearDrops()) {
                double d = desirability(p, kph);
                if (d > bestDesire) {
                    bestDesire = d;
                    wanted = p;
                }
                g += d * kph * p.chancePerKill();
            }
            gear[i] = g;
        }
        double need = Math.min(1.0, bestDesire / GEAR_DOMINANT_DPS_GAIN);
        double bestExp = max(exp);
        double bestGear = max(gear);
        double[] unified = new double[n];
        for (int i = 0; i < n; i++) {
            double e = bestExp > 0 ? exp[i] / bestExp : 0;
            double g = bestGear > 0 ? gear[i] / bestGear : 0;
            unified[i] = (1.0 - need) * e + need * g;
        }
        return new Scores(unified, need, wanted, bestDesire);
    }

    /* Upstream planFarmBest's score: expected copies of the item an hour on each candidate (0 where it never drops). */
    public static double[] farmScores(List<Candidate> candidates, int itemId) {
        double[] out = new double[candidates.size()];
        for (int i = 0; i < out.length; i++) {
            Candidate c = candidates.get(i);
            double chance = 0;
            for (GearProspect p : c.gearDrops()) {
                if (p.itemId() == itemId) {
                    chance += p.chancePerKill();
                }
            }
            out[i] = chance * killsPerHour(c);
        }
        return out;
    }

    private static double max(double[] values) {
        double best = 0;
        for (double v : values) {
            best = Math.max(best, v);
        }
        return best;
    }

    /* The candidates' indices that score within this fraction of the best (upstream's near-best set). */
    static List<Integer> nearBest(double[] scores, double fraction) {
        double best = max(scores);
        List<Integer> out = new ArrayList<>();
        if (best <= 0) {
            return out;
        }
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] >= best * fraction) {
                out.add(i);
            }
        }
        return out;
    }
}
