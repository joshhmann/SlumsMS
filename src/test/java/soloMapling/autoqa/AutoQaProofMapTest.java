package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the auto-QA coverage map ({@code src/main/resources/soloMapling/autoqa-coverage.tsv}) that
 * {@code tools/ops/autoqa-proof} reads: every check in the catalogue is in the map, the map names no check that is
 * not in the catalogue, every path glob still matches a tracked file, and the id list the tool prints is a selector
 * {@link AutoQaService} understands.
 * <p>
 * The map is how an operator gets from a merge to the checks that prove it (docs/ops/qa-suite.md, "Proving one fix
 * between merge times"). It rots silently when a check is added or a package moves, so it is pinned here.
 */
class AutoQaProofMapTest {

    static final Path MAP = Path.of("src/main/resources/soloMapling/autoqa-coverage.tsv");
    /** The one reserved id: a row for a runner/platform file that every check needs. */
    static final String EVERYTHING = "all";

    /** One map row: a check id list, a path glob, a {@code -D} switch, or "-" for no file at all, and a note. */
    record Row(List<String> checks, String key, String note) {
        boolean isSwitch() {
            return key.startsWith("-D");
        }

        /** True when the key is a path glob (so it must match a file and a path can match it). */
        boolean isPath() {
            return !isSwitch() && !key.equals("-");
        }
    }

    static List<Row> map() throws IOException {
        List<Row> rows = new ArrayList<>();
        int lineNo = 0;
        for (String line : Files.readAllLines(MAP)) {
            lineNo++;
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t", -1);
            assertEquals(3, f.length, MAP + ":" + lineNo + ": three TAB-separated columns, got " + f.length);
            assertFalse(f[0].isBlank(), MAP + ":" + lineNo + ": check id");
            assertFalse(f[1].isBlank(), MAP + ":" + lineNo + ": key");
            assertFalse(f[2].isBlank(), MAP + ":" + lineNo + ": note (say why)");
            rows.add(new Row(List.of(f[0].split(",")), f[1], f[2]));
        }
        return rows;
    }

    /** {@code *} is one path segment, {@code **} crosses segments, {@code ?} is one character; same as the tool. */
    static Pattern glob(String g) {
        StringBuilder out = new StringBuilder("^");
        for (int i = 0; i < g.length(); i++) {
            if (g.startsWith("**/", i)) {
                out.append("(?:[^/]+/)*");
                i += 2;
            } else if (g.startsWith("**", i)) {
                out.append(".*");
                i += 1;
            } else if (g.charAt(i) == '*') {
                out.append("[^/]*");
            } else if (g.charAt(i) == '?') {
                out.append("[^/]");
            } else {
                out.append(Pattern.quote(String.valueOf(g.charAt(i))));
            }
        }
        return Pattern.compile(out.append('$').toString());
    }

    @Test
    void theMapAndTheCatalogueNameTheSameChecks() throws IOException {
        Set<String> mapped = new TreeSet<>();
        boolean everything = false;
        for (Row r : map()) {
            for (String id : r.checks()) {
                if (EVERYTHING.equals(id)) {
                    everything = true;
                } else {
                    mapped.add(id);
                }
            }
        }
        Set<String> catalogue = AutoQaCatalog.all().stream().map(Check::id).collect(Collectors.toCollection(TreeSet::new));
        assertTrue(everything, "a row must use the reserved id " + EVERYTHING + " for runner/platform files");
        assertEquals(new TreeSet<>(catalogue), mapped,
                "the map must cover every catalogue check and name no other id");
    }

    /** Union of the check ids of every path row whose glob matches {@code path}. */
    static Set<String> checksFor(List<Row> rows, String path) {
        Set<String> out = new TreeSet<>();
        for (Row r : rows) {
            if (r.isPath() && glob(r.key()).matcher(path).matches()) {
                out.addAll(r.checks());
            }
        }
        return out;
    }

    @Test
    void everyGlobMatchesATrackedFile() throws IOException {
        List<String> tracked;
        // walkFileTree, not Files.walk: with parallel surefire forks, files such as another JVM's .attach_pid* appear
        // and vanish mid-walk, and Files.walk throws on them.
        List<String> found = new ArrayList<>();
        Files.walkFileTree(Path.of("."), java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 32,
                new java.nio.file.SimpleFileVisitor<>() {
                    @Override
                    public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                        if (attrs.isRegularFile()) found.add(file.toString().replace('\\', '/'));
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }

                    @Override
                    public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
        tracked = found.stream()
                .filter(f -> f.startsWith("./"))
                .map(f -> f.substring(2))
                .filter(f -> !f.startsWith(".git/") && !f.startsWith("target/"))
                .collect(Collectors.toList());
        assertTrue(tracked.size() > 1000, "the worktree is there (" + tracked.size() + " files)");
        List<String> broken = new ArrayList<>();
        for (Row r : map()) {
            if (!r.isPath()) {
                continue;
            }
            Pattern p = glob(r.key());
            if (tracked.stream().noneMatch(f -> p.matcher(f).matches())) {
                broken.add(r.key());
            }
        }
        assertEquals(List.of(), broken, "these globs match no file under src/ any more");
    }

    @Test
    void switchesAreWellFormedAndSayWhatTheyDo() throws IOException {
        List<Row> switches = map().stream().filter(Row::isSwitch).toList();
        assertFalse(switches.isEmpty(), "a check that refuses without a flag must say so here");
        for (Row r : switches) {
            assertTrue(r.key().matches("^-D[a-z][a-z0-9.]*=[A-Za-z0-9]+$"),
                    "switch row " + r.key() + " must read -Dproperty=value");
        }
        Set<String> onMechanics = switches.stream().filter(r -> r.key().equals("-Dslums.mechanics=true"))
                .flatMap(r -> r.checks().stream()).collect(Collectors.toCollection(TreeSet::new));
        // The KPQ checks refuse (and SKIP) unless -Dslums.mechanics=true is on the server.
        for (String id : List.of("t-mx-1", "t-mx-2", "t-mx-3", "t-mx-4", "t-mx-5", "t-mx-6")) {
            assertTrue(onMechanics.contains(id), id + " needs -Dslums.mechanics=true and the map says so");
        }
    }

    @Test
    void theMapKnowsTheKerningKpqFilesAndNotTheReleaseTools() throws IOException {
        List<Row> rows = map();
        Set<String> kpq = checksFor(rows, "src/main/java/soloMapling/mechanics/KpqStaging.java");
        assertTrue(kpq.contains("t-mx-1"), "KpqStaging is what t-mx-1 proves: " + kpq);
        assertTrue(kpq.contains("t-mx-2"), "KpqStaging is what t-mx-2 proves: " + kpq);

        for (Row r : rows) {
            if (!r.isPath()) {
                continue;
            }
            Pattern p = glob(r.key());
            assertFalse(p.matcher("tools/release/stage").matches(), r.key() + " must not claim tools/release");
            assertFalse(p.matcher("docs/ops/qa-suite.md").matches(), r.key() + " must not claim docs");
        }
    }

    @Test
    void theIdListTheToolPrintsIsASelectorTheRunnerUnderstands() throws IOException {
        Set<String> ids = new LinkedHashSet<>();
        for (Row r : map()) {
            for (String id : r.checks()) {
                if (!EVERYTHING.equals(id)) {
                    ids.add(id);
                }
            }
        }
        List<Check> catalogue = AutoQaCatalog.all();
        String selector = String.join(",", ids);
        assertTrue(selector.matches("^[A-Za-z0-9,_-]+$"), "autoqa-run only takes ids, commas and dashes: " + selector);
        assertEquals(catalogue.stream().map(Check::id).collect(Collectors.toList()),
                AutoQaService.select(catalogue, selector).stream().map(Check::id).collect(Collectors.toList()),
                "every id in the map, as one comma list, selects the whole catalogue");
        assertEquals(catalogue.size(), AutoQaService.select(catalogue, EVERYTHING).size(),
                "the reserved id selects everything");
    }

    @Test
    void theChecksNoFileCanTriggerAreExactlyTheReleaseSkipList() throws IOException {
        Set<String> noFile = map().stream().filter(r -> r.key().equals("-"))
                .flatMap(r -> r.checks().stream()).collect(Collectors.toCollection(TreeSet::new));
        Set<String> skips = new TreeSet<>();
        Path skipsFile = Path.of("src/main/resources/soloMapling/autoqa-release-skips.tsv");
        for (String line : Files.readAllLines(skipsFile)) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            skips.add(line.split("\t", -1)[0].trim());
        }
        assertFalse(skips.isEmpty(), "the release skip list is there");
        assertEquals(skips, noFile,
                "a check that only a person can prove says so in both the map (key \"-\") and the release skip list");
    }
}
