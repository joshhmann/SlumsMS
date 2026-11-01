package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 163: a character name is global in the game, so t-oa-1's fixed "AutoQaAlt" was created once by the first lane and
 * then looked up by name by every other lane ({@code QaAlts} uses {@code Character.getIdByName}), which got a character
 * its account did not own: {@code @botme} refused and the probe failed at cap 4. One pair of alts per account, and the
 * pair mx-5 leads is the pair this path makes (TEST 2026-11-01 19:14 UTC: mx-5 could not stage for the same reason).
 */
class QaAltsTest {

    /** The server steps without the database or a client: names in, ids out. */
    private static final class FakeHost implements QaAlts.Host {
        final Map<String, Integer> ids = new LinkedHashMap<>();
        final List<String> asked = new ArrayList<>();
        int next = 500;
        int status;
        boolean slotsRefused;

        public void roomForCharacters() {
            if (slotsRefused) {
                throw new IllegalStateException("no account row");
            }
        }

        public int idByName(String name) {
            Integer id = ids.get(name);
            return id == null ? -1 : id;
        }

        public int make(String name) {
            asked.add(name);
            if (status != 0) {
                return status;
            }
            ids.put(name, next++);
            return 0;
        }
    }

    @Test
    void eachAccountGetsItsOwnAlts() {
        String a1 = QaAlts.altName(1, 'A');
        String b1 = QaAlts.altName(1, 'B');
        String a11 = QaAlts.altName(11, 'A');
        assertNotEquals(a1, a11, "two lanes' alts must not be the same character");
        assertNotEquals(a1, b1, "the two alts of one account must differ");
        assertEquals("Qa11A", a11);
        for (String name : new String[] {a1, b1, a11}) {
            assertTrue(name.matches("[A-Za-z][A-Za-z0-9]{3,11}"), name + " is not a character name the client accepts");
        }
    }

    @Test
    void missingAltsAreMadeAndFoundAltsAreNot() {
        FakeHost host = new FakeHost();
        List<String> out = new ArrayList<>();
        int[] ids = QaAlts.ensure(host, 46, "AqClient", out);
        assertEquals(List.of("Qa46A", "Qa46B"), host.asked, "the account gets exactly its own pair");
        assertTrue(ids[0] > 0 && ids[1] > 0, "both alts came back with an id");
        assertNotEquals(ids[0], ids[1]);
        assertEquals(List.of("made Qa46A (" + ids[0] + ") on AqClient's account",
                "made Qa46B (" + ids[1] + ") on AqClient's account"), out);

        FakeHost again = new FakeHost();
        again.ids.put("Qa46A", ids[0]);
        again.ids.put("Qa46B", ids[1]);
        List<String> quiet = new ArrayList<>();
        int[] same = QaAlts.ensure(again, 46, "AqClient", quiet);
        assertEquals(ids[0], same[0]);
        assertEquals(ids[1], same[1]);
        assertTrue(again.asked.isEmpty(), "alts that exist are not made again: " + again.asked);
        assertTrue(quiet.isEmpty(), "nothing to report: " + quiet);
    }

    @Test
    void anAltThatCannotBeMadeIsReportedNotHidden() {
        FakeHost host = new FakeHost();
        host.status = 7;
        List<String> out = new ArrayList<>();
        int[] ids = QaAlts.ensure(host, 46, "AqClient", out);
        assertEquals(-1, ids[0]);
        assertEquals(-1, ids[1]);
        assertEquals(List.of("could not make Qa46A (status 7)", "could not make Qa46B (status 7)"), out);
    }

    @Test
    void aRefusedSlotFixIsReportedAndDoesNotStopTheAlts() {
        FakeHost host = new FakeHost();
        host.slotsRefused = true;
        List<String> out = new ArrayList<>();
        int[] ids = QaAlts.ensure(host, 46, "AqClient", out);
        assertTrue(ids[0] > 0 && ids[1] > 0, "the slots are the account row's problem, the alts still get made");
        assertEquals("could not give AqClient's account its slots: no account row", out.get(0));
    }
}
