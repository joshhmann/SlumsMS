package constants.skills;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic 6d68a5d77): the first-job Warrior's active skills are 1001xxx in
 * Skill.wz/100.img (1000000-1000002 are the passives). Power Strike, Slash Blast and Iron Body were 1000xxx here,
 * ids no skill has.
 */
class WarriorSkillIdsTest {

    @Test
    void theActiveSkillsHaveTheirWzIds() {
        assertEquals(1001003, Warrior.IRON_BODY);
        assertEquals(1001004, Warrior.POWER_STRIKE);
        assertEquals(1001005, Warrior.SLASH_BLAST);
    }

    @Test
    void thePassivesAreUnchanged() {
        assertEquals(1000000, Warrior.IMPROVED_HPREC);
        assertEquals(1000001, Warrior.IMPROVED_MAXHP);
        assertEquals(1000002, Warrior.ENDURE);
    }
}
