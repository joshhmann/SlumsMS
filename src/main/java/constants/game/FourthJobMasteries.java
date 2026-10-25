package constants.game;

import java.util.Map;

/**
 * The skills the stock Leafre 4th-job instructors (scripts/npc/2081100-2081500) give master level 10 when they advance
 * a character, keyed by the 4th job id. The Job Master (9010003) advances the same jobs without going through Leafre,
 * so it grants the same list. MapleRoot removed Big Bang and Barrage, so neither list has them (1.5.2).
 */
public final class FourthJobMasteries {
    private static final Map<Integer, int[]> BY_JOB = Map.ofEntries(
            Map.entry(112, new int[]{1121001, 1120004, 1121008}),
            Map.entry(122, new int[]{1221001, 1220005, 1221009}),
            Map.entry(132, new int[]{1321001, 1320005, 1321007}),
            Map.entry(212, new int[]{2121002, 2121006}),
            Map.entry(222, new int[]{2221002, 2221006}),
            Map.entry(232, new int[]{2321002, 2321005}),
            Map.entry(312, new int[]{3121002, 3120005, 3121007}),
            Map.entry(322, new int[]{3221002, 3220004, 3221006}),
            Map.entry(412, new int[]{4120002, 4120005, 4121006}),
            Map.entry(422, new int[]{4220002, 4220005, 4221007}),
            Map.entry(512, new int[]{5121001, 5121002, 5121009}),
            Map.entry(522, new int[]{5220001, 5220002, 5221004, 5220011}));

    private FourthJobMasteries() {
    }

    /** The master-level-10 skills for a 4th job, or an empty array for any other job. */
    public static int[] forJob(int jobId) {
        int[] skills = BY_JOB.get(jobId);
        return skills == null ? new int[0] : skills.clone();
    }
}
