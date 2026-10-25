package soloMapling.Economy;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static soloMapling.Economy.MegaBuyMatcher.Candidate;

class MegaBuyMatcherTest {

    private static final List<Candidate> STOCK = List.of(
            new Candidate(1372005, "Red Whip"),
            new Candidate(1332006, "Ilbis"),
            new Candidate(1082002, "Brown Work Glove"),
            new Candidate(2040804, "Scroll for Gloves for DEX 60%"),
            new Candidate(2040805, "Scroll for Gloves for DEX 10%"),
            new Candidate(1472030, "Claw for ATT 60%"),
            new Candidate(1472031, "Claw for ATT 10%"));

    private static MegaBuyMatcher.Result m(String... words) {
        return MegaBuyMatcher.match(List.of(words), STOCK);
    }

    @Test
    void exactNamesMatch() {
        assertEquals(1372005, m("red", "whip").itemId());
        assertEquals(1332006, m("ilbis").itemId());
        assertEquals(1082002, m("brown", "work", "gloves").itemId(), "plural endings are ignored");
    }

    @Test
    void fuzzyNeedsEveryWordAndEnoughOfTheName() {
        MegaBuyMatcher.Result r = m("60%", "glove", "dex");
        assertTrue(r.matched());
        assertEquals(2040804, r.itemId());
        assertFalse(m("60%", "glove", "luk").matched(), "a word the name lacks");
        assertFalse(m("claw").matched(), "one word of a long name is too little");
    }

    @Test
    void ambiguousAndMissingNeverMatch() {
        assertEquals(MegaBuyMatcher.Outcome.AMBIGUOUS, m("claw", "att").outcome(), "60% and 10% both fit");
        assertEquals(MegaBuyMatcher.Outcome.AMBIGUOUS, m("gloves", "dex").outcome());
        assertEquals(MegaBuyMatcher.Outcome.NONE, m("zakum", "helmet").outcome());
        assertEquals(MegaBuyMatcher.Outcome.NONE, MegaBuyMatcher.match(List.of(), STOCK).outcome());
        assertEquals(MegaBuyMatcher.Outcome.NONE, MegaBuyMatcher.match(List.of("ilbis"), List.of()).outcome());
    }

    @Test
    void anExactNameBeatsLongerNamesThatContainIt() {
        List<Candidate> c = List.of(new Candidate(1, "Glove"), new Candidate(2, "Brown Glove"), new Candidate(3, "Red Glove"));
        MegaBuyMatcher.Result r = MegaBuyMatcher.match(List.of("glove"), c);
        assertEquals(1, r.itemId());
    }
}
