package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 163: a SAID line is delivered only to the run whose GM is the player the bot answered (QaRun.accepts: a non-zero
 * owner id must be this run's gmId). So a check that grades SAID may only speak as its own GM; if one of them ever
 * talks through the lane's second player ({p2} = AutoQaTwo) the reply would be addressed to that character and the
 * check would time out on a line it never receives.
 *
 * <p>Evidence that the catalogue is safe today (cap-1 run 2026-10-31 17:17, acb4e916f): every cv-1..cv-7 log in
 * TEST's {@code logs/qa} holds SAID lines for exactly one player hash, 6a5903f9 = ConvoQuality.hash(44) = AutoQaOne,
 * the run's own GM.
 */
class SaidScopeCatalogTest {

    @Test
    void checksThatGradeSaidOnlySpeakAsTheirOwnGm() {
        List<String> watchers = new ArrayList<>();
        for (Check c : AutoQaCatalog.all()) {
            boolean gradesSaid = c.expects().stream().anyMatch(e -> e.describe().contains("CONVO SAID"));
            if (!gradesSaid) {
                continue;
            }
            watchers.add(c.id());
            for (Check.Step s : c.steps()) {
                String d = s.describe();
                assertFalse(d.contains("{p2}"), c.id() + " grades CONVO SAID but speaks through {p2} (the lane's second "
                        + "player): the reply is addressed to that character and this run would never receive it: " + d);
                assertFalse(d.contains("AutoQaTwo"), c.id() + " grades CONVO SAID but names AutoQaTwo: " + d);
            }
        }
        assertTrue(watchers.containsAll(List.of("t-cv-1", "t-cv-2", "t-cv-3", "t-cv-4", "t-cv-5", "t-cv-7")),
                "the catalogue still grades CONVO SAID: " + watchers);
    }
}
