package soloMapling.mechanics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 164, CWKPQ's sign-up: each member's job class, carried rather than inferred.
 *
 * <p>`scripts/event/CWKPQ.js:98-135` hands each member a reactor named {@code 4skill<jobClass><slot>} (or a
 * {@code 4fake*}), so the only thing a row needs from a member is the class - 0 adventurer, 1 warrior, 2 magician,
 * 3 bowman, 4 thief - and the sign-up reactor's own name carries the rest.
 */
class RunMemberJobTest {

    @Test
    void theJobClassIsTheFirstDigitOfTheJobId() {
        assertEquals(1, new RunMember(1, true, true, MechanicsPersona.PERFECT, 110).jobClass(), "fighter -> warrior");
        assertEquals(2, new RunMember(2, false, true, MechanicsPersona.PERFECT, 211).jobClass(), "F/P wizard");
        assertEquals(3, new RunMember(3, false, true, MechanicsPersona.PERFECT, 320).jobClass(), "hunter -> bowman");
        assertEquals(4, new RunMember(4, false, true, MechanicsPersona.PERFECT, 412).jobClass(), "chief bandit -> thief");
        assertEquals(5, new RunMember(5, false, true, MechanicsPersona.PERFECT, 512).jobClass(), "brawler -> pirate");
    }

    @Test
    void aMemberWithoutAJobIsDistinguishableFromABeginner() {
        RunMember unknown = new RunMember(1, true, true, MechanicsPersona.PERFECT);
        RunMember beginner = new RunMember(2, true, true, MechanicsPersona.PERFECT, 0);
        assertEquals(0, unknown.jobClass(), "class 0 is the default digit");
        assertEquals(0, beginner.jobClass(), "a beginner is class 0 too");
        assertFalse(unknown.hasJob(), "but nothing was carried for the first, so a sign-up must not place him");
        assertFalse(beginner.hasJob(), "and a beginner cannot sign up either: CWKPQ wants classes 1-4");
        assertTrue(new RunMember(3, true, true, MechanicsPersona.PERFECT, 110).hasJob(), "a warrior can");
    }
}
