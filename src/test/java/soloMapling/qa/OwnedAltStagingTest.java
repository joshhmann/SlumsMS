package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class OwnedAltStagingTest {
    @TempDir Path dir;

    static final class Fake implements OwnedAltStaging.Host {
        final Map<String, Integer> chars = new LinkedHashMap<>();
        final Set<String> refused = new HashSet<>();
        final Set<Integer> online = new HashSet<>();
        final List<String> supplied = new ArrayList<>();
        int next = 100;
        int others;
        public int accountId() { return 7; }
        public String gmName() { return "Silvia_Long"; }
        public int otherCharacters() { return others + chars.size(); }
        public int create(String name) {
            if (refused.contains(name) || chars.containsKey(name)) return -1;
            chars.put(name, next++);
            return 0;
        }
        public int idByName(String n) { return chars.getOrDefault(n, -1); }
        public String supply(int id, OwnedAltStaging.Role r) { supplied.add(id + ":" + r); return "ok"; }
        public boolean online(int id) { return online.contains(id); }
        public boolean delete(int id) { chars.values().remove(id); return true; }
    }

    @Test
    void namesUseEightLettersAndStayWithinTwelve() {
        var c = OwnedAltStaging.candidates("Silvia_Long", OwnedAltStaging.Role.ALT1);
        assertEquals("SilviaLoAlt1", c.get(0));
        assertEquals("SilviaLAlt11", c.get(1));
        assertTrue(c.stream().allMatch(n -> n.length() >= 3 && n.length() <= 12 && n.matches("[a-zA-Z0-9]+")));
        assertEquals("AbcAlt2", OwnedAltStaging.candidates("A-b c", OwnedAltStaging.Role.ALT2).get(0));
    }

    @Test
    void takenNameGetsADigitAndSecondRunOnlyTopsUp() {
        Fake f = new Fake();
        f.refused.add("SilviaLoAlt1");
        OwnedAltStaging s = new OwnedAltStaging(dir.resolve("m.tsv"));
        var first = s.make(f);
        assertTrue(f.chars.containsKey("SilviaLAlt11"), f.chars.toString());
        assertTrue(f.chars.containsKey("SilviaLoAlt2"));
        assertEquals(2, f.chars.size());
        assertTrue(first.stream().anyMatch(l -> l.startsWith("QA ALTS made SilviaLAlt11")), first.toString());
        f.supplied.clear();
        s.make(f);
        assertEquals(2, f.chars.size(), "no new characters on the second run");
        assertEquals(2, f.supplied.size());
    }

    @Test
    void existingRealCharactersAreNeverTouchedOrRemoved() {
        Fake f = new Fake();
        f.others = 2; // two real characters already
        OwnedAltStaging s = new OwnedAltStaging(dir.resolve("m.tsv"));
        s.make(f);
        assertTrue(f.chars.isEmpty());
        assertTrue(f.supplied.isEmpty());
        f.chars.put("SilviaLoAlt1", 55); // a real player's character with the same pattern, not made by us
        assertTrue(s.remove(f).get(0).contains("nothing to remove"));
        assertEquals(55, f.chars.get("SilviaLoAlt1"));
    }

    @Test
    void removeDeletesOnlyWhatItMadeAndSkipsCalledOnes() {
        Fake f = new Fake();
        OwnedAltStaging s = new OwnedAltStaging(dir.resolve("m.tsv"));
        s.make(f);
        f.chars.put("SilviaLoAlt9", 999); // same pattern, made by someone else
        int alt2 = f.chars.get("SilviaLoAlt2");
        f.online.add(alt2);
        s.remove(f);
        assertFalse(f.chars.containsKey("SilviaLoAlt1"));
        assertTrue(f.chars.containsKey("SilviaLoAlt2"), "a called alt is kept");
        assertTrue(f.chars.containsKey("SilviaLoAlt9"));
        f.online.clear();
        s.remove(f);
        assertEquals(Set.of("SilviaLoAlt9"), f.chars.keySet());
    }
}
