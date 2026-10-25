package constants.game;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Which jobs' skills belong to a character of a given job: the job itself and its earlier advancements within the same
 * branch (112 Hero: 112, 111, 110, 100), plus the family's beginner job (0, 1000 or 2000). A skill id's owner job is its
 * id divided by 10000 (4th-job Hero skill 1121010 belongs to 112, Echo of Hero 1005 to 0, 10001005 to 1000).
 */
public final class SkillLines {
    private SkillLines() {
    }

    public static int jobOfSkill(int skillId) {
        return skillId / 10000;
    }

    /** The jobs whose skills a character of {@code jobId} may hold. */
    public static Set<Integer> jobsOnLine(int jobId) {
        Set<Integer> jobs = new LinkedHashSet<>();
        if (jobId < 0) {
            return jobs;
        }
        jobs.add(jobId);
        int primary = jobId / 100 * 100;
        int branch = jobId / 10 % 10 * 10;
        for (int j = jobId; j >= primary; j--) {
            if (j == primary || j >= primary + branch) {
                jobs.add(j);
            }
        }
        jobs.add(jobId >= 2000 ? 2000 : jobId >= 1000 ? 1000 : 0);
        return jobs;
    }

    public static boolean isOnLine(int skillId, Set<Integer> jobsOnLine) {
        return jobsOnLine.contains(jobOfSkill(skillId));
    }
}
