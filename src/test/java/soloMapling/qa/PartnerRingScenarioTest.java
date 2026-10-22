package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 1.5.0: pr-1 / pr-2 (Cash Shop friendship / couple rings) stage, step and give the NX back. */
class PartnerRingScenarioTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    final FakeRings fake = new FakeRings();
    QaSuite suite;

    static final class FakeRings implements PartnerRingScenarios.RingWorld {
        final Map<String, Character> players = new HashMap<>();
        final Map<Integer, Integer> nx = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean sameChannel = true;

        public Character player(String name) {
            return players.get(name.toLowerCase());
        }

        public int nx(Character chr) {
            return nx.getOrDefault(chr.getId(), 0);
        }

        public void gainNx(Character chr, int delta) {
            nx.merge(chr.getId(), delta, Integer::sum);
            calls.add("nx " + delta);
        }

        public boolean summon(Character who, Character gm) {
            calls.add("summon " + who.getName());
            return sameChannel;
        }

        public List<String> rings(Character chr) {
            return List.of("friendship ring 1112800 #1 pair #2 with X (9)");
        }
    }

    @BeforeEach
    void setUp() {
        PartnerRingScenarios.rings = fake;
    }

    @AfterEach
    void reset() {
        PartnerRingScenarios.rings = new PartnerRingScenarios.GameRingWorld();
        QaLog.reset();
    }

    private Character start(String id, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", 100000000);
        world.online = gm;
        suite.start(gm, id, args);
        return gm;
    }

    @Test
    void registered() {
        List<String> ids = new ArrayList<>();
        for (QaScenario s : QaScenarios.all()) ids.add(s.id());
        assertTrue(ids.contains("pr-1"));
        assertTrue(ids.contains("pr-2"));
    }

    @Test
    void pr1GivesNxSummonsThePartnerAndTakesTheNxBack() throws Exception {
        fake.players.put("juliet", QaTestSupport.gm(2, "Juliet", 100000000));
        fake.nx.put(1, 500);
        Character gm = start("pr-1", "Juliet");
        assertEquals(500 + PartnerRingScenarios.NX_GIVEN, fake.nx.get(1));
        assertTrue(fake.calls.contains("summon Juliet"));
        List<String> step = suite.action(gm, "rings", new String[0]);
        assertTrue(step.stream().anyMatch(l -> l.startsWith("Juliet friendship ring 1112800")), step.toString());

        fake.nx.put(1, 500 + PartnerRingScenarios.NX_GIVEN - 3600);      // one ring bought
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertEquals(0, fake.nx.get(1), "the NX left of what was given is taken back, never more");
        assertTrue(Files.readString(log).contains("summoned Juliet"));
    }

    @Test
    void refusesWithoutAPartnerOrWithTheWrongKindOfRing() {
        assertThrows(RuntimeException.class, () -> start("pr-1"));
        fake.players.put("juliet", QaTestSupport.gm(2, "Juliet", 100000000));
        assertThrows(RuntimeException.class, () -> start("pr-2", "Nobody"));
        assertThrows(RuntimeException.class, () -> start("pr-2", "Juliet", "1112800"));  // friendship id on pr-2
        assertThrows(RuntimeException.class, () -> start("pr-1", "Admin"));
        assertTrue(fake.calls.stream().noneMatch(c -> c.startsWith("nx")), "nothing given on a refusal");
    }

    @Test
    void pr2TakesACoupleRingId() {
        fake.players.put("juliet", QaTestSupport.gm(2, "Juliet", 100000000));
        start("pr-2", "Juliet", "1112012");
        assertEquals(PartnerRingScenarios.NX_GIVEN, fake.nx.get(1));
        suite.stop(1, "!qa stop");
    }
}
