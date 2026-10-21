package server.setitem;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.Test;
import soloMapling.DataFiles;

import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetItemCatalogTest {

    // Marx Von Leon (GMS set 6) cut to this server: helmet, suit, gloves, boots, cape, two of its weapons.
    static final String MARX = """
            sets:
              - id: 6
                name: "Marx Von Leon Set"
                complete: 6
                items:
                  - 1003154   # helmet
                  - 1052299   # battle suit
                  - 1082285   # gloves
                  - 1072471   # boots
                  - 1102262   # cape
                  - 1402091   # sword
                  - 1442113   # halberd
                tiers:
                  4: {incAllStat: 4}
                  5: {incAllStat: 6, incPAD: 10, incMAD: 10}
                  6: {incAllStat: 10, incSTR: 4, incDEX: 4}
              - id: 2
                name: "Goddess Set"
                items: [1072455, 1082232]
                tiers:
                  2: {incMHP: 300, incMMP: 200}
            """;

    static SetItemCatalog parse(String yaml, List<String> problems) throws Exception {
        return SetItemCatalog.parse(new YamlReader(new StringReader(yaml)).read(), id -> true, problems);
    }

    static SetItemCatalog marx() throws Exception {
        List<String> problems = new ArrayList<>();
        SetItemCatalog c = parse(MARX, problems);
        assertEquals(List.of(), problems);
        return c;
    }

    @Test
    void readsSetsTiersAndPieces() throws Exception {
        SetItemCatalog c = marx();
        ItemSetInfo set = c.setOf(1442113);
        assertNotNull(set);
        assertEquals(6, set.id());
        assertEquals("Marx Von Leon Set", set.name());
        assertEquals(6, set.completeCount());
        assertEquals(7, set.itemIds().size());
        assertEquals(Set.of(4, 5, 6), set.tiers().keySet());
        assertEquals(2, c.get(2).completeCount(), "complete defaults to the last tier");
        assertNull(c.setOf(1302000));
    }

    @Test
    void tiersStackAndStartAtTheFirstThreshold() throws Exception {
        ItemSetInfo set = marx().get(6);
        assertEquals(Map.of(), set.cumulativeEffectFor(3));
        assertEquals(Map.of("incAllStat", 4), set.cumulativeEffectFor(4));
        assertEquals(Map.of("incAllStat", 10, "incPAD", 10, "incMAD", 10), set.cumulativeEffectFor(5));
        assertEquals(Map.of("incAllStat", 20, "incPAD", 10, "incMAD", 10, "incSTR", 4, "incDEX", 4),
                set.cumulativeEffectFor(9));
    }

    @Test
    void bonusFoldsAllStatAndSumsSets() throws Exception {
        SetItemCatalog c = marx();
        // Five Marx pieces (tiers 4+5 = AllStat 10, PAD 10, MAD 10) plus both Goddess pieces, plus a non-set item.
        SetBonus b = c.bonusFor(List.of(1003154, 1052299, 1082285, 1072471, 1402091, 1072455, 1082232, 1302000));
        assertEquals(new SetBonus(10, 10, 10, 10, 300, 200, 10, 10), b);
        assertEquals(SetBonus.NONE, c.bonusFor(List.of(1003154, 1052299, 1082285)), "three pieces reach no tier");
        assertEquals(SetBonus.NONE, c.bonusFor(List.of()));
    }

    @Test
    void theSameItemTwiceIsOnePiece() throws Exception {
        SetItemCatalog c = marx();
        assertEquals(SetBonus.NONE, c.bonusFor(List.of(1003154, 1003154, 1052299, 1082285)));
        assertEquals(Map.of(6, List.of(1003154, 1052299, 1082285)), c.wornPieces(List.of(1003154, 1052299, 1003154, 1082285)));
    }

    @Test
    void aBadSetIsSkippedAndTheRestLoad() throws Exception {
        String yaml = MARX + """
                  - id: 9
                    name: "Bad stat"
                    items: [1003155]
                    tiers:
                      2: {incPDD: 100}
                  - id: 10
                    name: "Steals a Marx piece"
                    items: [1003154, 1003156]
                    tiers:
                      2: {incSTR: 1}
                  - id: 11
                    name: "Not an equip"
                    items: [2000000]
                    tiers:
                      2: {incSTR: 1}
                  - id: 12
                    name: "Complete below its tier"
                    complete: 1
                    items: [1003157, 1003158]
                    tiers:
                      2: {incSTR: 1}
                """;
        List<String> problems = new ArrayList<>();
        SetItemCatalog c = parse(yaml, problems);
        assertEquals(4, problems.size(), problems.toString());
        assertEquals(Set.of(6, 2), new HashSet<>(c.sets().stream().map(ItemSetInfo::id).toList()));
        assertEquals(6, c.setOf(1003154).id());
    }

    @Test
    void anItemTheServerDoesNotKnowSkipsTheSet() throws Exception {
        List<String> problems = new ArrayList<>();
        SetItemCatalog c = SetItemCatalog.parse(new YamlReader(new StringReader(MARX)).read(), id -> id != 1442113, problems);
        assertEquals(1, problems.size());
        assertNull(c.get(6));
        assertNotNull(c.get(2));
    }

    @Test
    void notASetsFileIsEmpty() {
        List<String> problems = new ArrayList<>();
        assertTrue(SetItemCatalog.parse(List.of(), id -> true, problems).isEmpty());
        assertEquals(1, problems.size());
    }

    /** The shipped table loads without a single skipped set (item ids checked by the generator against the WZ). */
    @Test
    void theShippedTableLoadsCleanly() throws Exception {
        Path file = DataFiles.path(SetItemCatalog.DATA_PATH);
        assertTrue(Files.isRegularFile(file), file.toString());
        List<String> problems = new ArrayList<>();
        SetItemCatalog c;
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            c = SetItemCatalog.parse(new YamlReader(in).read(), id -> true, problems);
        }
        assertEquals(List.of(), problems);
        assertTrue(c.sets().size() > 200, "sets: " + c.sets().size());
        ItemSetInfo marx = c.get(6);
        assertEquals("Marx Von Leon Set", marx.name());
        assertEquals(Map.of("incAllStat", 4), marx.cumulativeEffectFor(4));
        for (ItemSetInfo set : c.sets()) {
            assertTrue(set.completeCount() >= set.tiers().lastKey(), set.name());
            for (Map<String, Integer> stats : set.tiers().values()) {
                assertTrue(SetItemCatalog.APPLIED_KEYS.containsAll(stats.keySet()), set.name());
            }
        }
    }
}
