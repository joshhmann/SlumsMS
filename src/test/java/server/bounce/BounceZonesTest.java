package server.bounce;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BounceZonesTest {

    @AfterEach
    void reset() {
        BounceZones.useForTest(null);
    }

    static BounceZones parse(String yaml, List<String> problems) throws Exception {
        return BounceZones.parse(new YamlReader(new StringReader(yaml)).read(), problems);
    }

    static final String SAMPLE = """
            categories:
              - name: jq
                description: "jump quests"
                enabled: true
                block_flash_jump: true
                maps:
                  - 101000100-101000104   # Forest of Patience
                  - 280020000
              - name: region
                description: "a region"
                enabled: true
                maps:
                  - 2400xxxxx
              - name: off
                description: "disabled"
                enabled: false
                maps:
                  - 100000000
            """;

    @Test
    void entriesAreIdsRangesAndRegions() {
        assertEquals(new BounceZones.MapRange(280020000, 280020000), BounceZones.entry("280020000"));
        assertEquals(new BounceZones.MapRange(280020000, 280020001), BounceZones.entry("280020000-280020001"));
        assertEquals(new BounceZones.MapRange(280020000, 280020001), BounceZones.entry(" 280020000 - 280020001 "));
        assertEquals(new BounceZones.MapRange(280000000, 280099999), BounceZones.entry("2800xxxxx"));
        assertEquals(new BounceZones.MapRange(100000000, 199999999), BounceZones.entry("1xxxxxxxx"));
        assertEquals(new BounceZones.MapRange(101000100, 101000109), BounceZones.entry("10100010X"));
        for (String bad : List.of("abc", "2800xxxx", "2800xxxxxx", "5-3", "1000000000", "-5", "28x0xxxxx", "")) {
            assertThrows(IllegalArgumentException.class, () -> BounceZones.entry(bad), bad);
        }
    }

    @Test
    void matchingCoversRangeEndsRegionsAndSkipsDisabledCategories() throws Exception {
        List<String> problems = new ArrayList<>();
        BounceZones z = parse(SAMPLE, problems);
        assertEquals(List.of(), problems);
        assertEquals(3, z.categories().size());
        assertFalse(z.blocksBounce(101000099));
        assertTrue(z.blocksBounce(101000100));
        assertTrue(z.blocksBounce(101000104));
        assertFalse(z.blocksBounce(101000105));
        assertTrue(z.blocksBounce(280020000));
        assertFalse(z.blocksBounce(280020001));
        assertTrue(z.blocksBounce(240000000));
        assertTrue(z.blocksBounce(240099999));
        assertFalse(z.blocksBounce(240100000));
        assertFalse(z.blocksBounce(239999999));
        assertFalse(z.blocksBounce(100000000), "the disabled category blocks nothing");
        assertEquals("off", z.listing(100000000).get(0).name());
        assertEquals("region", z.blockedBy(240060000).orElseThrow().name());
        assertTrue(z.blockedBy(100000000).isEmpty());
        assertEquals(100_000, z.categories().get(1).idCount());
    }

    @Test
    void flashJumpOnlyWhereTheCategorySaysSo() throws Exception {
        BounceZones z = parse(SAMPLE, new ArrayList<>());
        for (int bounce : new int[]{1050, 10001050, 20001050, 1054, 10001054, 20001054}) {
            assertTrue(z.refuses(bounce, 101000102));
            assertTrue(z.refuses(bounce, 240010000));
            assertFalse(z.refuses(bounce, 100000000));
        }
        assertTrue(z.refuses(BounceZones.FLASH_JUMP_HERMIT, 101000102));
        assertTrue(z.refuses(BounceZones.FLASH_JUMP_NIGHT_WALKER, 280020000));
        assertFalse(z.refuses(BounceZones.FLASH_JUMP_HERMIT, 240010000), "region has no block_flash_jump");
        assertFalse(z.refuses(2001002, 101000102), "other skills are never refused");
        assertEquals(List.of(new BounceZones.MapRange(101000100, 101000104), new BounceZones.MapRange(280020000, 280020000)),
                z.flashJumpRanges());
    }

    @Test
    void rangesMergeAcrossCategories() {
        List<BounceZones.MapRange> merged = BounceZones.merge(List.of(new BounceZones.MapRange(10, 20),
                new BounceZones.MapRange(21, 25), new BounceZones.MapRange(5, 12), new BounceZones.MapRange(40, 40)));
        assertEquals(List.of(new BounceZones.MapRange(5, 25), new BounceZones.MapRange(40, 40)), merged);
    }

    @Test
    void badCategoriesAndEntriesAreSkippedAndReported() throws Exception {
        List<String> problems = new ArrayList<>();
        BounceZones z = parse("""
                categories:
                  - name: a
                    enabled: true
                    maps: [101000100, nonsense, 5-3]
                  - name: a
                    enabled: true
                  - name: b
                    enabled: maybe
                  - name: c
                    enabled: true
                    colour: red
                """, problems);
        assertEquals(1, z.categories().size());
        assertTrue(z.blocksBounce(101000100));
        assertEquals(5, problems.size(), problems.toString());
        assertTrue(parse("nothing: here", problems).categories().isEmpty());
    }

    @Test
    void theShippedFileLoadsCleanlyWithOnlyJumpQuestsOn() throws Exception {
        List<String> problems = new ArrayList<>();
        BounceZones z = BounceZones.parse(new YamlReader(java.nio.file.Files.newBufferedReader(
                Path.of(BounceZones.DATA_PATH))).read(), problems);
        assertEquals(List.of(), problems);
        assertEquals(List.of("jump_quests", "party_quests", "boss_arenas", "towns"),
                z.categories().stream().map(BounceZones.Category::name).toList());
        assertEquals(List.of(true, false, false, false), z.categories().stream().map(BounceZones.Category::enabled).toList());
        // one stage of every jump-quest family
        for (int jq : new int[]{100000202, 220000006, 101000100, 101000104, 105040310, 105040316, 103000900, 103000909,
                280020000, 280020001, 922000000, 910510100, 109030001, 109030403, 109040004, 980041000, 980044104,
                922240019, 993910008, 993910009}) {
            assertTrue(z.blocksBounce(jq), "JQ " + jq);
            assertTrue(z.blocksFlashJump(jq), "JQ " + jq);
            assertEquals("jump_quests", z.blockedBy(jq).orElseThrow().name());
        }
        // towns, the Zakum prequest's other maps, a party quest and a boss are not blocked by default
        for (int open : new int[]{100000000, 101000000, 103000000, 105040300, 280010000, 280030000, 922010100,
                103000800, 100000201, 922240100, 980041200}) {
            assertFalse(z.blocksBounce(open), "map " + open);
        }
        assertTrue(z.listing(100000000).stream().anyMatch(c -> c.name().equals("towns")));
        assertTrue(z.listing(280030000).stream().anyMatch(c -> c.name().equals("boss_arenas")));
        assertTrue(z.listing(922010100).stream().anyMatch(c -> c.name().equals("party_quests")));
    }

    @Test
    void reloadReadsTheFileAgain(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path f = dir.resolve("zones.yaml");
        java.nio.file.Files.writeString(f, SAMPLE);
        assertTrue(BounceZones.reload(f).blocksBounce(101000100));
        java.nio.file.Files.writeString(f, SAMPLE.replace("enabled: true\n    block_flash_jump: true", "enabled: false\n    block_flash_jump: true"));
        BounceZones z = BounceZones.reload(f);
        assertFalse(z.blocksBounce(101000100));
        assertTrue(BounceZones.getInstance() == z);
        assertTrue(BounceZones.reload(dir.resolve("missing.yaml")).categories().isEmpty());
    }
}
