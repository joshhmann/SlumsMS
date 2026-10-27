package constants.game;

/**
 * What MapleRoot's Job Master (scripts/npc/9010003.js) charges for skipping a job advancement's quest or test
 * (owner, 2026-10-25; ticket 88). The stock routes (instructors, quests, the Leafre tests) stay free.
 * 1st job and beginner switches are free, 2nd 4m, 3rd 75m, 4th 500m (x5 after the Maple Leaf went back to 500k, owner 2026-10-25); MapleRoot's 5th tier keeps its own
 * rebirth-3 + item rule, and Super Beginner / Synth Master stay free.
 */
public final class JobMasterFees {
    public static final int SECOND = 4_000_000;
    public static final int THIRD = 75_000_000;
    public static final int FOURTH = 500_000_000;

    private JobMasterFees() {
    }

    /** The meso fee for advancing to {@code targetJob} through the Job Master. */
    public static int forTarget(int targetJob) {
        if (targetJob < 0 || targetJob >= 700 && targetJob < 1000) {
            return 0; // Super Beginner 700, Synth Master 710
        }
        int branch = targetJob % 100;
        if (branch == 0) {
            return 0; // beginners and 1st jobs (100, 1100, 2100 ...)
        }
        return switch (branch % 10) {
            case 0 -> SECOND;  // 110, 1110, 2110
            case 1 -> THIRD;   // 111, 1111, 2111
            case 2 -> FOURTH;  // 112, 1112, 2112
            default -> 0;      // MapleRoot's 5th tier (113 ...): rebirth 3 + item 4036541
        };
    }

    /** Records a paid fee to the economy telemetry counters. */
    public static void recordFee(int fee) {
        if (fee > 0) {
            soloMapling.Economy.stats.EconomyCounters.recordJobMasterFee(fee);
        }
    }
}
