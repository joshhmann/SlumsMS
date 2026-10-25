package scripting;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Many scripts describe rewards and recipes as parallel arrays: {@code itemSet[i]} is given {@code itemQty[i]} times
 * (EventInstanceManager.giveEventReward, the Boss Rush / Kerning reward NPCs), and a crafting NPC builds
 * {@code itemSet[i]} from {@code matSet[i]} x {@code matQtySet[i]} for {@code costSet[i]} mesos. MapleRoot removed
 * scrolls from several {@code itemSet} lists but left the quantity lists alone, so every item after a removal was
 * paired with another item's count (Kerning PQ potions gave 1 instead of 80, ores 80 instead of 15).
 *
 * This test walks every script, finds each array literal assigned to one of those names, and asserts the partner
 * arrays have the same number of entries (and, for recipes, that each material list has a matching quantity list).
 * Arrays that are built at run time ({@code []}) are skipped.
 */
class ScriptRewardArraysTest {

    private static final Pattern ASSIGN = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*=\\s*\\[");
    private static final Pattern LEVELLED_QTY = Pattern.compile("itemQty_lv(\\d+)");

    /** Partner array name -> the array it runs parallel to. */
    private static final Map<String, String> PARTNERS = Map.of(
            "itemQty", "itemSet",
            "matSet", "itemSet",
            "matQtySet", "itemSet",
            "costSet", "itemSet",
            "matQtyMeso", "itemSet",
            "wishPrizesQty", "wishPrizes",
            "wishPrizesCst", "wishPrizes",
            "cd_matQty", "cd_mats",
            "rockFees", "rockItems",
            "minRocks", "rockItems");

    /** Element-wise parallel arrays: each entry of the second has the shape of the matching entry of the first. */
    private static final Map<String, String> SHAPES = Map.of("matQtySet", "matSet", "cd_matQty", "cd_mats");

    /**
     * Known mismatches that are not MapleRoot's scroll removals and need a game-data decision, not a mechanical fix.
     * The first three are in stock Cosmic as well (extra or missing recipe entries); 2090004 is MapleRoot's stub of
     * the Mu Lung scroll recipes (one item for sixteen menu entries).
     */
    private static final Set<String> KNOWN = Set.of(
            "npc/1061000.js: costSet has 23 entries, itemSet has 22",
            "npc/2010003.js: matSet has 7 entries, itemSet has 8",
            "npc/2010003.js: matQtySet has 7 entries, itemSet has 8",
            "npc/2010003.js: costSet has 7 entries, itemSet has 8",
            "npc/2040021.js: costSet has 17 entries, itemSet has 16",
            "npc/2040021.js: matSet has 15 entries, itemSet has 19",
            "npc/2040021.js: matQtySet has 15 entries, itemSet has 19",
            "npc/2040021.js: costSet has 15 entries, itemSet has 19",
            "npc/2090004.js: matSet has 16 entries, itemSet has 1",
            "npc/2090004.js: matQtySet has 16 entries, itemSet has 1");

    @Test
    void parallelRewardAndRecipeArraysHaveMatchingLengths() throws IOException {
        Path root = Path.of("scripts");
        Assumptions.assumeTrue(Files.isDirectory(root), "run from the repository root");

        List<String> scripts;
        try (Stream<Path> walk = Files.walk(root)) {
            scripts = walk.filter(p -> p.toString().endsWith(".js")).sorted()
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).toList();
        }
        Assumptions.assumeFalse(scripts.isEmpty(), "no scripts to scan");

        List<String> broken = new ArrayList<>();
        for (String script : scripts) {
            for (String problem : check(Files.readString(root.resolve(script), StandardCharsets.UTF_8))) {
                String key = script + ": " + problem.replaceFirst(" \\(line \\d+\\)$", "");
                if (!KNOWN.contains(key)) {
                    broken.add(script + ": " + problem);
                }
            }
        }
        assertEquals(List.of(), broken, "parallel arrays out of step (item i would get another item's amount)");
    }

    @Test
    void checkerSeesTheKnownPairsAndCatchesAMisalignedList() {
        String ok = "itemSet = [2000003, 4010000];\n itemQty = [80, 15]; // comment, with, commas\n"
                + "var itemSet_lv1 = [1, 2, 3];\nvar itemQty_lv1 = [1, 1, 1];\n"
                + "var matSet = [[4000021, 4011000], 4003000];\nvar matQtySet = [[35, 3], 10];";
        assertEquals(List.of(), check("itemSet = [7, 8];\n" + ok));

        String scrollRemoved = "itemSet = [1032009, 2000003];\n itemQty = [1, 1, 1, 80];\n"
                + "var itemSet_lv2 = [1, 2];\nvar itemQty_lv2 = [1, 1, 1];\n"
                + "itemSet = [1, 2];\nvar matSet = [[4000021, 4011000], 4003000];\nvar matQtySet = [35, 10];";
        List<String> problems = check(scrollRemoved);
        assertEquals(3, problems.size(), problems.toString());
        assertTrue(problems.get(0).startsWith("itemQty has 4 entries, itemSet has 2"), problems.toString());
        assertTrue(problems.get(1).startsWith("itemQty_lv2 has 3 entries, itemSet_lv2 has 2"), problems.toString());
        assertTrue(problems.get(2).startsWith("matQtySet[0] is a single amount, matSet[0] lists 2"),
                problems.toString());
    }

    /** One problem per mismatched pair, each ending in " (line N)". */
    static List<String> check(String source) {
        String s = stripComments(source);
        Map<String, List<String>> latest = new HashMap<>();
        List<String> problems = new ArrayList<>();
        Matcher m = ASSIGN.matcher(s);
        while (m.find()) {
            String name = m.group(1);
            String literal = literal(s, m.end() - 1);
            if (literal == null) {
                continue;
            }
            int line = lineOf(s, m.start());
            List<String> elems = elements(literal);

            Matcher lv = LEVELLED_QTY.matcher(name);
            String anchor = lv.matches() ? "itemSet_lv" + lv.group(1) : PARTNERS.get(name);
            List<String> anchorElems = anchor == null ? null : latest.get(anchor);
            if (anchorElems != null && !anchorElems.isEmpty() && !elems.isEmpty()
                    && anchorElems.size() != elems.size()) {
                problems.add(name + " has " + elems.size() + " entries, " + anchor + " has " + anchorElems.size()
                        + " (line " + line + ")");
            }

            String shapeOf = SHAPES.get(name);
            List<String> first = shapeOf == null ? null : latest.get(shapeOf);
            if (first != null && first.size() == elems.size()) {
                for (int i = 0; i < elems.size(); i++) {
                    int a = shape(first.get(i));
                    int b = shape(elems.get(i));
                    if (a != b) {
                        problems.add(name + "[" + i + "] " + describe(b) + ", " + shapeOf + "[" + i + "] "
                                + (a < 0 ? "is a single item" : "lists " + a) + " (line " + line + ")");
                    }
                }
            }
            latest.put(name, elems);
        }
        return problems;
    }

    private static String describe(int shape) {
        return shape < 0 ? "is a single amount" : "lists " + shape;
    }

    /** -1 for a scalar, otherwise the number of entries of a nested array. */
    private static int shape(String element) {
        return element.startsWith("[") ? elements(element).size() : -1;
    }

    private static int lineOf(String s, int index) {
        int line = 1;
        for (int i = 0; i < index; i++) {
            if (s.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Removes // and block comments (keeping newlines so line numbers hold), leaving string literals alone. */
    static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                out.append(c);
                if (c == '\\' && i + 1 < s.length()) {
                    out.append(s.charAt(++i));
                } else if (c == quote || c == '\n') {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                out.append(c);
            } else if (s.startsWith("//", i)) {
                int end = s.indexOf('\n', i);
                i = (end < 0 ? s.length() : end) - 1;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2);
                int stop = end < 0 ? s.length() : end + 2;
                for (int k = i; k < stop; k++) {
                    if (s.charAt(k) == '\n') {
                        out.append('\n');
                    }
                }
                i = stop - 1;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** The bracketed literal starting at {@code open} (which is '['), or null if it never closes. */
    private static String literal(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int j = open; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quote != 0) {
                if (c == '\\') {
                    j++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '(') {
                depth++;
            } else if (c == ']' || c == ')') {
                if (--depth == 0) {
                    return s.substring(open, j + 1);
                }
            }
        }
        return null;
    }

    /** Top-level comma-separated entries of an array literal; a trailing comma adds nothing. */
    private static List<String> elements(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        List<String> parts = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int k = 0; k < body.length(); k++) {
            char c = body.charAt(k);
            if (quote != 0) {
                cur.append(c);
                if (c == '\\' && k + 1 < body.length()) {
                    cur.append(body.charAt(++k));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[' || c == '(' || c == '{') {
                depth++;
            } else if (c == ']' || c == ')' || c == '}') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                parts.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            parts.add(cur.toString().trim());
        }
        return parts;
    }
}
