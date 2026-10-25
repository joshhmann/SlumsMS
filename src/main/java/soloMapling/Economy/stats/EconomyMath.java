package soloMapling.Economy.stats;

import java.util.Arrays;

/**
 * Mathematical calculations for economy telemetry: Gini coefficient, percentiles, top-tier shares,
 * and standard level bands.
 */
public final class EconomyMath {

    private EconomyMath() {
    }

    /**
     * Calculates the Gini coefficient for a collection of non-negative values.
     * 0.0 denotes perfect equality, and values approaching 1.0 denote maximum inequality.
     *
     * <p>Standard discrete formula for sorted values:
     * G = (2 * sum(i * y_i) / (n * sum(y_i))) - ((n + 1) / n)
     * where i is 1-based index (1 to n).
     */
    public static double gini(long[] values) {
        if (values == null || values.length <= 1) {
            return 0.0;
        }
        long[] sorted = values.clone();
        Arrays.sort(sorted);

        int n = sorted.length;
        double sum = 0.0;
        double weightedSum = 0.0;

        for (int i = 0; i < n; i++) {
            long y = Math.max(0, sorted[i]);
            sum += y;
            weightedSum += (double) (i + 1) * y;
        }

        if (sum <= 0.0) {
            return 0.0;
        }

        double g = (2.0 * weightedSum) / ((double) n * sum) - (double) (n + 1) / (double) n;
        return Math.max(0.0, Math.min(1.0, g));
    }

    /**
     * Calculates the share of total value held by the top {@code topPercent} (e.g. 0.01 for top 1%, 0.10 for top 10%).
     * Returns a percentage value between 0.0 and 100.0.
     *
     * @param sortedAsc values sorted in ascending order (or unsorted, which will be cloned and sorted)
     * @param topPercent fraction between 0.0 and 1.0
     */
    public static double topShare(long[] sortedAsc, double topPercent) {
        if (sortedAsc == null || sortedAsc.length == 0 || topPercent <= 0.0) {
            return 0.0;
        }
        long[] sorted = sortedAsc.clone();
        Arrays.sort(sorted);

        int n = sorted.length;
        double total = 0.0;
        for (long v : sorted) {
            total += Math.max(0, v);
        }
        if (total <= 0.0) {
            return 0.0;
        }

        // Count of people in the top bracket (at least 1 if topPercent > 0)
        int topCount = Math.max(1, (int) Math.round(n * Math.min(1.0, topPercent)));
        int startIndex = Math.max(0, n - topCount);

        double topSum = 0.0;
        for (int i = startIndex; i < n; i++) {
            topSum += Math.max(0, sorted[i]);
        }

        return (topSum / total) * 100.0;
    }

    /**
     * Calculates the percentile value for a sorted array using nearest-rank.
     * Always returns an actual observed value from the distribution.
     *
     * @param sortedAsc values sorted in ascending order
     * @param p percentile between 0.0 and 1.0 (e.g. 0.5 for median)
     */
    public static long percentile(long[] sortedAsc, double p) {
        if (sortedAsc == null || sortedAsc.length == 0) {
            return 0L;
        }
        long[] sorted = sortedAsc.clone();
        Arrays.sort(sorted);

        if (sorted.length == 1) {
            return sorted[0];
        }
        double clamped = Math.max(0.0, Math.min(1.0, p));
        int index = (int) Math.round(clamped * (sorted.length - 1));
        return sorted[index];
    }

    /**
     * Maps character level to the standard 4 economy level bands:
     * "1-30", "31-70", "71-120", "121-200".
     */
    public static String levelBand(int level) {
        if (level <= 30) {
            return "1-30";
        }
        if (level <= 70) {
            return "31-70";
        }
        if (level <= 120) {
            return "71-120";
        }
        return "121-200";
    }
}
