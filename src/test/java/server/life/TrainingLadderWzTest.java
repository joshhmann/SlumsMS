package server.life;

import org.junit.jupiter.api.Test;
import server.maps.AreaGates;
import soloMapling.WzTestData;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntPredicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 120+ ladder's promises, checked against the real Map.wz / Mob.wz and the shipped mob-stat-overrides.tsv (skips
 * without wz/). The model is tools/economy/ladder_table.py's (and gen_bb_drops.py's): 141M damage an hour (1,000
 * Chief Oblivion Guardians), at most 1,500 kills an hour on a map, world EXP 3x; a map pays the mean over its mob
 * placements, an area the median over its training maps (strongest non-boss mob lv 90+).
 * <ul>
 *   <li>1.6.1 b040: "Leafre and Minar Forest, the Temple of Time and Ulu City give 40% more EXP": every EXP-giving
 *       mob placed there has a x1.4 row, not only the 25 of lv 95+.</li>
 *   <li>1.6.1 b040: lower Grandis "pays roughly twice what the Temple of Time does": about 2x the buffed Temple's
 *       Road to Oblivion (it was 1.3x).</li>
 * </ul>
 */
class TrainingLadderWzTest {

    private static final double DAMAGE_PER_HOUR = 141_000.0 * 1000;
    private static final double KILL_CAP = 1500;
    private static final int TRAINING_MIN = 90;
    private static final Pattern ENTRY = Pattern.compile("<imgdir name=\"\\d+\">(.*?)</imgdir>", Pattern.DOTALL);

    private record MobInfo(int level, long hp, int exp, boolean boss) {
    }

    private static final Map<Integer, MobInfo> MOBS = new HashMap<>();

    private static MobInfo mob(int id) throws IOException {
        if (MOBS.containsKey(id)) {
            return MOBS.get(id);
        }
        Path f = WzTestData.dir().resolve(String.format("Mob.wz/%07d.img.xml", id));
        MobInfo info = null;
        if (Files.exists(f)) {
            String xml = Files.readString(f);
            String head = infoText(xml);
            String link = field(head, "link");
            Integer ownExp = field(head, "exp") == null ? null : Integer.parseInt(field(head, "exp"));
            if (link != null) {
                MobInfo linked = mob(Integer.parseInt(link));
                info = linked == null ? null : new MobInfo(linked.level(), linked.hp(),
                        ownExp != null ? ownExp : linked.exp(), linked.boss());
            }
            if (info == null) {
                info = new MobInfo(intField(head, "level"), intField(head, "maxHP"),
                        ownExp == null ? 0 : ownExp, "1".equals(field(head, "boss")));
            }
        }
        MOBS.put(id, info);
        return info;
    }

    private static final Pattern TAG = Pattern.compile("<imgdir\\b[^>]*?(/?)>|</imgdir>");

    /** The info node's own fields, without its attack/skill blocks (a skill's level is not the mob's). */
    static String infoText(String xml) {
        int start = xml.indexOf("<imgdir name=\"info\">");
        if (start < 0) {
            return xml;
        }
        int pos = start + "<imgdir name=\"info\">".length();
        int depth = 0;
        StringBuilder out = new StringBuilder();
        Matcher m = TAG.matcher(xml);
        m.region(pos, xml.length());
        while (m.find()) {
            if (depth == 0) {
                out.append(xml, pos, m.start());
            }
            if (m.group().equals("</imgdir>")) {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (m.group(1).isEmpty()) {
                depth++;
            }
            pos = m.end();
        }
        return out.toString();
    }

    private static String field(String xml, String name) {
        Matcher m = Pattern.compile("name=\"" + name + "\" value=\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    private static int intField(String xml, String name) {
        String v = field(xml, name);
        return v == null ? 0 : Integer.parseInt(v);
    }

    /** map id -> placed mob ids, for the Map.wz maps the filter takes. */
    private static Map<Integer, List<Integer>> placements(String folder, IntPredicate take) throws IOException {
        Map<Integer, List<Integer>> out = new HashMap<>();
        try (Stream<Path> files = Files.list(WzTestData.dir().resolve("Map.wz/Map/" + folder))) {
            for (Path f : (Iterable<Path>) files::iterator) {
                String n = f.getFileName().toString();
                if (!n.endsWith(".img.xml") || !n.substring(0, 9).chars().allMatch(Character::isDigit)) {
                    continue;
                }
                int map = Integer.parseInt(n.substring(0, 9));
                if (!take.test(map)) {
                    continue;
                }
                String xml = Files.readString(f);
                int life = xml.indexOf("<imgdir name=\"life\">");
                List<Integer> mobs = new ArrayList<>();
                Matcher m = ENTRY.matcher(life < 0 ? "" : xml.substring(life));
                while (m.find()) {
                    String id = field(m.group(1), "id");
                    if ("m".equals(field(m.group(1), "type")) && id != null) {
                        mobs.add(Integer.parseInt(id));
                    }
                }
                if (!mobs.isEmpty()) {
                    out.put(map, mobs);
                }
            }
        }
        return out;
    }

    /** EXP an hour at the reference damage, with the shipped stat table on top of the WZ values. */
    private static double expPerHour(int mobId, MobInfo info) {
        MobStatOverrides.Row row = MobStatOverrides.table().get(mobId);
        long hp = row != null && row.hp() != null ? row.hp() : info.hp(); // LifeFactory's lv 160+ halving: n/a here
        int exp = row != null && row.exp() != null ? row.exp() : info.exp();
        return Math.min(DAMAGE_PER_HOUR / hp, KILL_CAP) * exp * 3;
    }

    private static double areaMedian(Map<Integer, List<Integer>> maps) throws IOException {
        List<Double> perMap = new ArrayList<>();
        for (List<Integer> mobs : maps.values()) {
            double sum = 0;
            int n = 0;
            int top = 0;
            for (int id : mobs) {
                MobInfo info = mob(id);
                if (info == null || info.boss() || info.hp() <= 0) {
                    continue;
                }
                sum += expPerHour(id, info);
                n++;
                top = Math.max(top, info.level());
            }
            if (n > 0 && top >= TRAINING_MIN) {
                perMap.add(sum / n);
            }
        }
        assertTrue(perMap.size() >= 5, "training maps: " + perMap.size());
        Collections.sort(perMap);
        int k = perMap.size();
        return k % 2 == 1 ? perMap.get(k / 2) : (perMap.get(k / 2 - 1) + perMap.get(k / 2)) / 2;
    }

    /** The ticket-81 target mobs (they get their own new-area EXP, not the classic x1.4). */
    private static Set<Integer> targets() throws IOException {
        Set<Integer> out = new HashSet<>();
        List<String> lines = Files.readAllLines(Path.of("docs/economy/bb-drops/stats.csv"));
        for (String line : lines.subList(1, lines.size())) {
            String[] f = line.split(",", -1);
            if (!line.contains(",classic: ")) {
                out.add(Integer.parseInt(f[0]));
            }
        }
        return out;
    }

    @Test
    void everyMobOfTheBuffedClassicSpotsGivesFortyPercentMore() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        Set<Integer> targets = targets();
        Map<Integer, List<Integer>> maps = new HashMap<>();
        maps.putAll(placements("Map2", m -> (m >= 240010000 && m <= 240049999) || (m >= 270010000 && m <= 270039999)));
        maps.putAll(placements("Map5", m -> m >= 541020000 && m <= 541029999));
        Set<Integer> checked = new HashSet<>();
        for (List<Integer> mobs : maps.values()) {
            for (int id : mobs) {
                MobInfo info = mob(id);
                if (info == null || info.exp() <= 0 || (id >= 9300000 && id < 9400000) || targets.contains(id)
                        || !checked.add(id)) {
                    continue;
                }
                MobStatOverrides.Row row = MobStatOverrides.table().get(id);
                assertNotNull(row, "mob " + id + " (lv " + info.level() + ") on the buffed classic maps has no row");
                assertEquals(Math.round(info.exp() * 1.4), (long) row.exp(), "mob " + id + " EXP x1.4");
                assertEquals(null, row.hp(), "mob " + id + " keeps its HP");
            }
        }
        assertTrue(checked.size() >= 50, "mobs checked: " + checked.size());
        assertTrue(checked.contains(7130002) && checked.contains(8200000) && checked.contains(9420514),
                "Beetle, Eye of Time and Berserkie are among them");
    }

    /** Owner 2026-10-29: the Future / Twilight Perion (Leafre of the Past aside) trimmed to about 3x the buffed Temple. */
    @Test
    void theFuturePaysAboutThreeTimesTheBuffedTempleOfTime() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        double temple = areaMedian(placements("Map2", m -> m >= 270030000 && m <= 270039999));
        double future = areaMedian(placements("Map2", m -> AreaGates.isFuture(m) && !(m >= 272000100 && m <= 272000600)));
        double ratio = future / temple;
        assertTrue(ratio >= 2.85 && ratio <= 3.15, String.format("the Future %.1fM an hour = %.2fx the buffed Temple",
                future / 1e6, ratio));
    }

    /**
     * Owner 2026-10-29: Leafre of the Past opens (Gate of Time) as a lv 124 spot; its Corrupted mobs pay what lower
     * Grandis pays, not the trimmed Future rate (nor round 4's 36M, which was below lower Grandis at the same level).
     */
    @Test
    void leafreOfThePastPaysLikeLowerGrandis() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        double past = areaMedian(placements("Map2", m -> m >= 272000100 && m <= 272000600));
        double lower = areaMedian(placements("Map4", AreaGates::isLowerGrandis));
        double ratio = past / lower;
        assertTrue(ratio >= 0.9 && ratio <= 1.2, String.format("Leafre of the Past %.1fM an hour = %.2fx lower Grandis",
                past / 1e6, ratio));
        for (int corrupted = 8220016; corrupted <= 8220019; corrupted++) {
            assertEquals(124, mob(corrupted).level(), "the Corrupted mob's info level, not its skill's");
        }
    }

    @Test
    void lowerGrandisPaysAboutTwiceTheBuffedTempleOfTime() throws IOException {
        WzTestData.assumePresent("Map.wz");
        WzTestData.assumePresent("Mob.wz");
        double temple = areaMedian(placements("Map2", m -> m >= 270030000 && m <= 270039999)); // Road to Oblivion
        double lower = areaMedian(placements("Map4", AreaGates::isLowerGrandis));
        double ratio = lower / temple;
        assertTrue(temple > 25e6 && temple < 28e6, "buffed Road to Oblivion: " + temple);
        assertTrue(ratio >= 1.9 && ratio <= 2.2, String.format("lower Grandis %.1fM an hour = %.2fx the buffed "
                + "Temple of Time (%.1fM)", lower / 1e6, ratio, temple / 1e6));
    }
}
