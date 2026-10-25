package client;

import org.junit.jupiter.api.Test;
import tools.Pair;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.5.1: MapleRoot cut the max HP a 1st-job Bowman, Thief or Pirate (and their Cygnus versions) gets on advancing
 * from Cosmic's 100-150 to exactly 1. Warriors and mages were untouched.
 */
class JobAdvanceHpGainTest {

    private static final int ROLLS = 200;

    @Test
    void firstJobBowmanThiefAndPirateGain100To150Hp() {
        for (Job job : new Job[] {Job.BOWMAN, Job.THIEF, Job.PIRATE, Job.WINDARCHER1, Job.NIGHTWALKER1, Job.THUNDERBREAKER1}) {
            for (int i = 0; i < ROLLS; i++) {
                Pair<Integer, Integer> gain = Character.jobAdvanceHpMpGain(job);
                assertTrue(gain.getLeft() >= 100 && gain.getLeft() <= 150, job + " gained " + gain.getLeft() + " HP");
                assertTrue(gain.getRight() >= 25 && gain.getRight() <= 50, job + " gained " + gain.getRight() + " MP");
            }
        }
    }

    @Test
    void firstJobWarriorAndMageGainsAreUnchanged() {
        for (Job job : new Job[] {Job.WARRIOR, Job.DAWNWARRIOR1}) {
            for (int i = 0; i < ROLLS; i++) {
                Pair<Integer, Integer> gain = Character.jobAdvanceHpMpGain(job);
                assertTrue(gain.getLeft() >= 200 && gain.getLeft() <= 250, job + " gained " + gain.getLeft() + " HP");
                assertTrue(gain.getRight() == 0, job + " gained " + gain.getRight() + " MP");
            }
        }
        for (Job job : new Job[] {Job.MAGICIAN, Job.BLAZEWIZARD1}) {
            for (int i = 0; i < ROLLS; i++) {
                Pair<Integer, Integer> gain = Character.jobAdvanceHpMpGain(job);
                assertTrue(gain.getLeft() == 0, job + " gained " + gain.getLeft() + " HP");
                assertTrue(gain.getRight() >= 100 && gain.getRight() <= 150, job + " gained " + gain.getRight() + " MP");
            }
        }
    }
}
