package soloMapling.qa;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QaRegistryTest {

    /* slumsms.service on 192.168.0.164 as of 2026-10-21 (live). */
    static final String LIVE = "/usr/bin/java -Xmx4G -Dmapleroot.login-port=8485 -Dmapleroot.channel-base-port=7585"
            + " -Dcashshop.window.enabled=true -Dweather.enabled=true -Dsolomapling.enabled=true"
            + " -Dsolomapling.template-id=6 -Dsolomapling.autopopulate=true -Dsolomapling.tutorial-admin=false"
            + " -jar target/Cosmic.jar";
    /* /opt/mapleroot-dev/persistence-test/start-test.sh (test server) as of 2026-10-21. */
    static final String TEST = "/usr/bin/java -Xmx4G -Dmapleroot.login-port=8489 -Dmapleroot.channel-base-port=7985"
            + " -Dcashshop.window.enabled=true -Dweather.enabled=true -Dsolomapling.enabled=true"
            + " -Dsolomapling.template-id=5 -Dsolomapling.autopopulate=true -Dslums.attackcapture=true"
            + " -jar target/Cosmic.jar";

    static Map<String, String> props(String commandLine) {
        Map<String, String> out = new HashMap<>();
        Matcher m = Pattern.compile("-D([^=\\s]+)=(\\S*)").matcher(commandLine);
        while (m.find()) {
            out.put(m.group(1), m.group(2));
        }
        return out;
    }

    @Test
    void offOnLiveOnOnTheTestServer() {
        assertFalse(QaSuite.enabledFor(props(LIVE)::get));
        assertTrue(QaSuite.enabledFor(props(TEST)::get));
        assertTrue(QaSuite.enabledFor(props("java -Dslums.qa=true -jar x.jar")::get));
        assertFalse(QaSuite.enabledFor(props("java -Dslums.qa=false -jar x.jar")::get));
    }

    @Test
    void idsAreTestIdsAndUnique() {
        Set<String> seen = new HashSet<>();
        for (QaScenario s : QaScenarios.all()) {
            for (String id : concat(s.id(), s.aliases())) {
                assertTrue(QaSuite.ID.matcher(id).matches(), id);
                assertTrue(seen.add(id), "duplicate " + id);
            }
            assertFalse(s.title().isBlank());
            assertNotNull(s.topics());
            assertTrue(s.minutes(new String[0]) >= 1 && s.minutes(new String[0]) <= 31, s.id());
        }
        // the open 1.4.1 tests the coordinator asked for
        for (String id : List.of("bk-1", "fm-1", "fm-2", "np-3", "gh-1", "gh-2", "ld-1", "wd-1", "wd-2", "gn-2")) {
            assertTrue(seen.contains(id), id);
        }
    }

    @Test
    void aBadOrDuplicateIdIsRefusedAtStartup() {
        FakeQaWorld w = new FakeQaWorld();
        assertThrows(IllegalArgumentException.class, () -> QaTestSupport.suite(
                List.of(QaSuiteTest.scenario("Fredrick", null)), w, null, new QaTestSupport.ManualScheduler()));
        assertThrows(IllegalArgumentException.class, () -> QaTestSupport.suite(
                List.of(QaSuiteTest.scenario("np-2", null), QaSuiteTest.scenario("np-2", null)), w, null,
                new QaTestSupport.ManualScheduler()));
    }

    @Test
    void listShowsEveryScenarioAndThePlannedOnes() {
        QaSuite suite = QaTestSupport.suite(QaScenarios.all(), new FakeQaWorld(), null, new QaTestSupport.ManualScheduler());
        List<String> lines = suite.list();
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("fm-1 (also fm-2) - ")));
        assertTrue(lines.get(lines.size() - 1).startsWith("Planned for 1.4.2"));
    }

    private static List<String> concat(String id, List<String> more) {
        List<String> all = new java.util.ArrayList<>(more);
        all.add(0, id);
        return all;
    }
}
