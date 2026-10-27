package soloMapling.qa;

import org.junit.jupiter.api.Test;
import soloMapling.autoqa.AutoQaCatalog;
import soloMapling.autoqa.Check;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Owner 2026-10-26: t-ev-2 passes on bots that died by themselves too, and keeps the forced death as a fallback. */
class WorldEventScenariosTest {

    private static List<Pattern> patterns(Check c, boolean steps) {
        List<Pattern> out = new ArrayList<>();
        List<String> described = new ArrayList<>();
        if (steps) {
            c.steps().forEach(s -> described.add(s.describe()));
        } else {
            c.expects().forEach(e -> described.add(e.describe()));
        }
        for (String d : described) {
            Matcher m = Pattern.compile("/(.*)/").matcher(d);
            if (m.find()) {
                out.add(Pattern.compile(m.group(1)));
            }
        }
        return out;
    }

    private static boolean anyMatches(List<Pattern> ps, String line) {
        return ps.stream().anyMatch(p -> p.matcher(line).find());
    }

    @Test
    void theDieStepIsOnlyNeededWhenNobodyDiedByThemselves() {
        assertNull(WorldEventScenarios.Ev2Invasion.naturalDeaths(0));
        assertEquals("EV2 DIE not needed: 1 bot already died on their own",
                WorldEventScenarios.Ev2Invasion.naturalDeaths(1));
        assertEquals("EV2 DIE not needed: 4 bots already died on their own",
                WorldEventScenarios.Ev2Invasion.naturalDeaths(4));
    }

    @Test
    void tEv2PassesOnANaturalDeathOrTheForcedOne() {
        Check ev2 = AutoQaCatalog.find("t-ev-2");
        List<Pattern> expects = patterns(ev2, false);
        assertTrue(anyMatches(expects, WorldEventScenarios.Ev2Invasion.naturalDeaths(3)));
        assertTrue(anyMatches(expects, "EV2 DIE next lethal hit certain for 3 staged bots (HP 1, nearest a mob: [])"));
        // the start line names the lv 1-40 band (owner 2026-10-27: low levels get in; a lv 70 still does not)
        assertTrue(anyMatches(expects, "WEVENT START #4 henesys_invasion (invasion) on 100000000 Henesys ch1, bots lv"
                + " 1-40, cap 150, by !qa ev-2"));
        assertFalse(anyMatches(expects, "WEVENT START #4 henesys_invasion (invasion) on 100000000 Henesys ch1, bots lv"
                + " 15-40, cap 40, by !qa ev-2"));
        // and the crowd really came (madness: most called bots go at once)
        assertTrue(anyMatches(expects, "WEVENT RECRUIT #4 31 bots head to 100000000, 9 offered it (31/150, lv 1-40)"));
        assertFalse(anyMatches(expects, "WEVENT RECRUIT #4 0 bots head to 100000000, 76 offered it (0/40, lv 1-40)"));
        assertFalse(anyMatches(expects, "WEVENT START #4 henesys_invasion (invasion) on 100000000 Henesys ch1, bots lv"
                + " 17-70, cap 40, by !qa ev-2"));
    }

    @Test
    void theScenarioLinesNeverSatisfyTheChecksOwnWaits() {
        // a GM hint or a note that matched a wait would end it before the death it waits for
        List<Pattern> waits = patterns(AutoQaCatalog.find("t-ev-2"), true);
        assertFalse(waits.isEmpty());
        for (String hint : WorldEventScenarios.Ev2Invasion.hints()) {
            assertFalse(anyMatches(waits, hint), hint);
        }
        assertFalse(anyMatches(waits, WorldEventScenarios.Ev2Invasion.naturalDeaths(2)));
        assertFalse(anyMatches(waits, "EV2 DIE next lethal hit certain for 3 staged bots (HP 1, nearest a mob: [])"));
    }
}
