package soloMapling.Gachapon;

import org.junit.jupiter.api.Test;
import soloMapling.WzTestData;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The shipped gachapon-pools.yaml: it loads clean, its odds are the release notes' odds, and every id is in the WZ. */
class GachaPoolsFileTest {

    static GachaPools shipped(java.util.function.IntPredicate known) throws Exception {
        try (FileReader in = new FileReader(GachaPools.FILE)) {
            return GachaPools.parse(in, known);
        }
    }

    @Test
    void theShippedFileLoadsWithoutProblems() throws Exception {
        GachaPools p = shipped(id -> true);
        assertEquals(List.of(), p.problems());
        assertEquals(12, p.towns().size());
        for (GachaPools.Town t : p.towns().values()) {
            assertTrue(t.npcId() >= 9100100 && t.npcId() <= 9100117, t.key());
            assertTrue(!t.maps().isEmpty(), t.key() + " has maps a bot can play from");
            double sum = p.odds(t).stream().mapToDouble(GachaPools.TierOdds::chance).sum();
            assertEquals(1.0, sum, 1e-9, t.key());
        }
    }

    @Test
    void releaseNotesOdds() throws Exception {
        GachaPools p = shipped(id -> true);
        List<GachaPools.TierOdds> h = p.odds(p.town("henesys"));
        assertEquals(4, h.size());
        assertEquals(0.70, h.get(0).chance(), 1e-9);
        assertEquals(0.245, h.get(1).chance(), 1e-9);
        assertEquals(0.05, h.get(2).chance(), 1e-9);
        assertEquals(0.005, h.get(3).chance(), 1e-9);
        assertTrue(p.announces(GachaPools.Tier.RARE) && p.announces(GachaPools.Tier.JACKPOT));
        assertTrue(!p.announces(GachaPools.Tier.COMMON) && !p.announces(GachaPools.Tier.UNCOMMON));
        // Chaos Scroll and White Scroll are each 40 of the jackpot tier's weight, at every machine.
        for (GachaPools.Town t : p.towns().values()) {
            GachaPools.TierOdds jackpot = p.odds(t).get(p.odds(t).size() - 1);
            assertEquals(GachaPools.Tier.JACKPOT, jackpot.tier());
            double chaos = jackpot.items().stream().filter(i -> i.itemId() == 2049100).findFirst().orElseThrow().chance();
            assertTrue(chaos > 0.0008 && chaos < 0.0011, t.key() + " chaos " + chaos);
        }
        GachaPools.Simulation sim = p.simulate(p.town("ellinia"), 100_000, new Random(11));
        assertEquals(0.05, sim.tiers().get(GachaPools.Tier.RARE) / 100_000.0, 0.004);
    }

    /** Every id has a name in String.wz and data in Item.wz/Character.wz (needs -Dwz-path). */
    @Test
    void everyIdIsInTheWz() throws Exception {
        WzTestData.assumePresent("String.wz");
        Map<Integer, String> names = names(WzTestData.dir());
        GachaPools p = shipped(id -> true);
        List<String> bad = new ArrayList<>();
        for (GachaPools.Town t : p.towns().values()) {
            for (GachaPools.Tier tier : GachaPools.Tier.values()) {
                for (GachaPools.Entry e : p.machine(t, tier)) {
                    String n = names.get(e.itemId());
                    if (n == null || n.isBlank()) {
                        bad.add(t.key() + "." + tier.key() + " " + e.itemId());
                    }
                }
            }
        }
        assertEquals(List.of(), bad);
        GachaPools strict = shipped(id -> names.containsKey(id));
        assertEquals(List.of(), strict.problems());
    }

    static Map<Integer, String> names(Path wz) throws Exception {
        Map<Integer, String> out = new HashMap<>();
        for (String f : new String[]{"Consume", "Eqp", "Etc", "Ins", "Cash"}) {
            Path file = wz.resolve("String.wz").resolve(f + ".img.xml");
            if (!Files.exists(file)) {
                continue;
            }
            var doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
            var dirs = doc.getElementsByTagName("imgdir");
            for (int i = 0; i < dirs.getLength(); i++) {
                var el = (org.w3c.dom.Element) dirs.item(i);
                String id = el.getAttribute("name");
                if (!id.matches("\\d+")) {
                    continue;
                }
                var kids = el.getChildNodes();
                for (int k = 0; k < kids.getLength(); k++) {
                    if (kids.item(k) instanceof org.w3c.dom.Element c && "name".equals(c.getAttribute("name"))) {
                        out.put(Integer.parseInt(id), c.getAttribute("value"));
                    }
                }
            }
        }
        return out;
    }
}
