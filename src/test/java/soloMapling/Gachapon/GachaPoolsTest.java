package soloMapling.Gachapon;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GachaPoolsTest {

    static final String YAML = """
            tiers:
              common:
                weight: 70
              uncommon:
                weight: 25
              rare:
                weight: 4
                announce: true
              jackpot:
                weight: 1
                announce: true
            shared:
              common:
                - 2000004 x20    # Elixir
              jackpot:
                - 2049100 w3     # Chaos
                - 2340000        # White
            towns:
              henesys:
                name: Henesys
                npc: 9100100
                maps:
                  - 100000000
                  - 100000100
                common:
                  - 2040001
                  - 2040002 x2
                uncommon:
                  - 2040804
                rare:
                  - 2040805
              perion:
                name: Perion
                npc: 9100102
                maps:
                  - 102000000
                common:
                  - 2043001
            """;

    static GachaPools parse(String yaml, java.util.function.IntPredicate known) throws Exception {
        return GachaPools.parse(new StringReader(yaml), known);
    }

    @Test
    void readsTownsTiersAndEntries() throws Exception {
        GachaPools p = parse(YAML, id -> true);
        assertEquals(List.of(), p.problems());
        assertEquals(Set.of("henesys", "perion"), p.towns().keySet());
        GachaPools.Town h = p.town("henesys");
        assertEquals(9100100, h.npcId());
        assertEquals(List.of(100000000, 100000100), h.maps());
        assertEquals(new GachaPools.Entry(2040002, 2, 1), h.lists().get(GachaPools.Tier.COMMON).get(1));
        assertEquals(3, p.machine(h, GachaPools.Tier.COMMON).size(), "own list plus the shared one");
        assertTrue(p.announces(GachaPools.Tier.RARE));
        assertEquals(h, p.byNpc(9100100));
        assertEquals(h, p.byMap(100000100));
        assertNull(p.byMap(101000000));
    }

    @Test
    void findsTownsByKeyNameOrStart() throws Exception {
        GachaPools p = parse(YAML, id -> true);
        assertEquals("henesys", p.find("Henesys").key());
        assertEquals("henesys", p.find("hene").key());
        assertEquals("perion", p.find("PERION").key());
        assertNull(p.find("leafre"));
    }

    @Test
    void oddsSkipEmptyTiersAndSumToOne() throws Exception {
        GachaPools p = parse(YAML, id -> true);
        // Perion has no uncommon or rare: common 70 and jackpot 1 share the whole ticket.
        List<GachaPools.TierOdds> odds = p.odds(p.town("perion"));
        assertEquals(2, odds.size());
        assertEquals(70.0 / 71, odds.get(0).chance(), 1e-12);
        double total = 0;
        for (GachaPools.TierOdds t : p.odds(p.town("henesys"))) {
            for (GachaPools.ItemOdds i : t.items()) {
                total += i.chance();
            }
        }
        assertEquals(1.0, total, 1e-12);
        GachaPools.TierOdds jackpot = p.odds(p.town("henesys")).get(3);
        assertEquals(0.01 * 3 / 4, jackpot.items().get(0).chance(), 1e-12, "item weight w3 of 4 in the tier");
    }

    @Test
    void rollsFollowTheOdds() throws Exception {
        GachaPools p = parse(YAML, id -> true);
        GachaPools.Simulation sim = p.simulate(p.town("henesys"), 200_000, new Random(7));
        assertEquals(0.70, sim.tiers().get(GachaPools.Tier.COMMON) / 200_000.0, 0.01);
        assertEquals(0.25, sim.tiers().get(GachaPools.Tier.UNCOMMON) / 200_000.0, 0.01);
        assertEquals(0.04, sim.tiers().get(GachaPools.Tier.RARE) / 200_000.0, 0.003);
        assertEquals(0.01, sim.tiers().get(GachaPools.Tier.JACKPOT) / 200_000.0, 0.002);
        double chaos = sim.items().get(2049100) / 200_000.0;
        assertEquals(0.0075, chaos, 0.0015);
        GachaPools.Prize prize = p.roll(p.town("henesys"), new Random(1));
        assertNotNull(prize);
        assertEquals("henesys", prize.town());
        assertEquals(prize.tier() == GachaPools.Tier.RARE || prize.tier() == GachaPools.Tier.JACKPOT, prize.announce());
    }

    @Test
    void forcedTierRollsOnlyThatTier() throws Exception {
        GachaPools p = parse(YAML, id -> true);
        GachaPools.Prize rare = p.rollTier(p.town("henesys"), GachaPools.Tier.RARE, new Random(3));
        assertEquals(2040805, rare.itemId());
        assertTrue(rare.announce());
        assertNull(p.rollTier(p.town("perion"), GachaPools.Tier.RARE, new Random(3)));
    }

    @Test
    void brokenEntriesAreSkippedAndReported() throws Exception {
        String yaml = YAML.replace("      - 2040001\n", "      - 2040001\n      - 12345\n      - 2040001\n      - 9999999\n")
                .replace("  perion:\n    name: Perion\n    npc: 9100102", "  perion:\n    name: Perion\n    npc: 9100100");
        GachaPools p = parse(yaml, id -> id != 9999999);
        List<String> problems = p.problems();
        assertTrue(problems.stream().anyMatch(s -> s.contains("bad entry '12345'")), problems.toString());
        assertTrue(problems.stream().anyMatch(s -> s.contains("2040001 is listed twice")), problems.toString());
        assertTrue(problems.stream().anyMatch(s -> s.contains("9999999 is not a known item")), problems.toString());
        assertTrue(problems.stream().anyMatch(s -> s.contains("towns.perion: missing or repeated npc")), problems.toString());
        assertEquals(Set.of("henesys"), p.towns().keySet());
        assertEquals(2, p.town("henesys").lists().get(GachaPools.Tier.COMMON).size());
    }

    @Test
    void aTownRepeatingASharedItemIsReported() throws Exception {
        String yaml = YAML.replace("      - 2043001\n", "      - 2043001\n      - 2049100\n");
        GachaPools p = parse(yaml, id -> true);
        assertTrue(p.problems().stream().anyMatch(s -> s.contains("towns.perion.common: 2049100 is listed twice")),
                p.problems().toString());
    }
}
