package soloMapling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.mechanics.Mechanics;
import soloMapling.mechanics.MechanicsModel;
import soloMapling.mechanics.MechanicsRuns;
import soloMapling.mechanics.StageData;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 130 phase 1 blocker: the mechanics content folder cannot be listed inside the jar (HANDOFF's jar-only data
 * gap), so the files are named in {@code content/index.txt} and each is read through {@link DataFiles}. These tests
 * load kpq.yaml from the packaged layout: a jar holding {@code soloMapling/mechanics/content/...} as maven packs it,
 * with no source tree on disk.
 */
class MechanicsDataFilesTest {

    private static final Path CONTENT = Path.of(Mechanics.DEFAULT_DIR);
    private static final String JAR_DIR = "soloMapling/mechanics/content/";

    @TempDir
    Path tmp;

    private Path packagedJar() throws IOException {
        Path jar = tmp.resolve("Cosmic.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar));
             Stream<Path> files = Files.list(CONTENT)) {
            out.putNextEntry(new JarEntry("soloMapling/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry("soloMapling/mechanics/"));
            out.closeEntry();
            out.putNextEntry(new JarEntry(JAR_DIR));
            out.closeEntry();
            for (Path f : files.sorted().toList()) {
                out.putNextEntry(new JarEntry(JAR_DIR + f.getFileName()));
                out.write(Files.readAllBytes(f));
                out.closeEntry();
            }
        }
        return jar;
    }

    @Test
    void theContentRowsLoadFromTheJarWithNoSourceTree() throws IOException {
        ClassLoader cl = new URLClassLoader(new URL[]{packagedJar().toUri().toURL()}, null);
        Path emptyWorkDir = Files.createDirectories(tmp.resolve("server"));
        DataFiles d = new DataFiles(null, emptyWorkDir, cl, tmp.resolve("extract"));
        List<DataFiles.Origin> origins = new ArrayList<>();

        StageData.Result r = StageData.loadIndexed(Mechanics.DEFAULT_DIR, rel -> {
            DataFiles.Resolved res = d.resolve(rel, true);
            origins.add(res.origin());
            return res.path();
        });

        assertEquals(List.of(), r.errors());
        assertEquals(List.of("kpq", "zakum", "horntail", "opq", "lpq", "hpq",
                "cafepq1", "cafepq2", "cafepq3", "cafepq4", "cafepq5", "cafepq6",
                "dunas", "aufheben", "oberon", "bergamot", "nibelung", "maha", "castellan",
                "papulatus", "krexel", "showa", "balrog", "scarga", "elemental", "pinkbean",
                "latanica", "magnus", "nwill", "nlucid", "nverus", "ndarknell",
                "guardiannex1", "guardiannex2", "guardiannex3", "guardiannex4", "guardiannex5",
                "guardiannex6", "zakum-chaos"),
                r.content().stream().map(c -> c.id()).toList());
        assertEquals(10, r.content().stream().filter(c -> "kpq".equals(c.id())).findFirst().orElseThrow()
                .stage(1).orElseThrow().answer(1).orElseThrow());
        assertTrue(origins.stream().allMatch(o -> o == DataFiles.Origin.CLASSPATH), origins.toString());
    }

    /**
     * Chaos Zakum (round 11, item 8): the row is opt-in twice over - the boss lane switch and its own
     * {@code flag: slums.chaos.zakum} - so a server that has the lane on but chaos off arms nothing, and a row without a
     * flag keeps arming exactly as before.
     */
    @Test
    void theChaosRowNamesItsOwnSwitchAndTheNormalAltarDoesNot() {
        MechanicsModel.Content chaos = MechanicsRuns.contentFor("ChaosZakumBattle");
        assertNotNull(chaos, "zakum-chaos.yaml must be listed in content/index.txt");
        assertEquals(MechanicsModel.Kind.BOSS, chaos.kind());
        assertEquals("slums.chaos.zakum", chaos.flag());
        assertEquals(280030001, chaos.stages().get(0).mapId());
        assertEquals(11, chaos.stages().get(0).mobs().size(), "eight chaos arms and the body's three forms");
        assertEquals(List.of(8800100, 8800101, 8800102),
                chaos.stages().get(0).mobs().subList(8, 11), "the chain is spelled out in the row, in wake order");

        MechanicsModel.Content normal = MechanicsRuns.contentFor("ZakumBattle");
        assertTrue(normal.flag() == null || normal.flag().isBlank(), "the normal altar has no switch of its own");

        System.clearProperty("slums.chaos.zakum");
        assertFalse(Mechanics.rowFlagOn(chaos), "off by default");
        assertTrue(Mechanics.rowFlagOn(normal), "a row with no flag is always allowed");
        System.setProperty("slums.chaos.zakum", "true");
        try {
            assertTrue(Mechanics.rowFlagOn(chaos));
        } finally {
            System.clearProperty("slums.chaos.zakum");
        }
    }

    /**
     * Round 11 item D: KPQ carries one proposed HARD tier as data (DIFFICULTY-CHAOS-PLAN.md section 9). The numbers are
     * the owner's to overwrite - this test only pins that they are read as written, that NORMAL is still the stock row,
     * and that no other tier is named.
     */
    @Test
    void theKpqRowCarriesOneProposedHardTier() {
        MechanicsModel.Content kpq = MechanicsRuns.contentFor("KerningPQ");
        MechanicsModel.TierRules hard = kpq.rules(MechanicsModel.Tier.HARD).orElseThrow();
        assertEquals(41, hard.minLevel());
        assertEquals(65, hard.maxLevel());
        assertEquals(20, hard.mobLevelBonus());
        assertEquals(3.0, hard.mobHpMultiplier());
        assertEquals(1.5, hard.mobDamageMultiplier());
        assertEquals(40, hard.timeLimitMinutes());
        assertEquals(1.25, hard.botMistakeFactor());

        MechanicsModel.TierRules normal = kpq.rules(MechanicsModel.Tier.NORMAL).orElseThrow();
        assertEquals(kpq.minLevel(), normal.minLevel(), "NORMAL is the row's own gate");
        assertEquals(kpq.timeLimitMinutes(), normal.timeLimitMinutes(), "and the script's own clock");
        assertEquals(1.0, normal.mobHpMultiplier());
        assertTrue(kpq.rules(MechanicsModel.Tier.EXPERT).isEmpty(), "no EXPERT number is settled");
        assertTrue(kpq.rules(MechanicsModel.Tier.MASTER).isEmpty(), "no MASTER number is settled");
    }

    @Test
    void aMissingIndexOrFileIsReportedNotThrown() throws IOException {
        DataFiles d = new DataFiles(null, Files.createDirectories(tmp.resolve("w")),
                new URLClassLoader(new URL[0], null), tmp.resolve("x"));
        StageData.Result r = StageData.loadIndexed(Mechanics.DEFAULT_DIR, rel -> d.resolve(rel, false).path());
        assertTrue(r.content().isEmpty());
        assertTrue(r.errors().get(0).contains("index.txt"), r.errors().toString());

        Path dir = Files.createDirectories(tmp.resolve("c"));
        Files.writeString(dir.resolve("index.txt"), "# a comment\n\nnope.yaml\nBad Name.yml\n");
        StageData.Result r2 = StageData.loadIndexed(dir.toString(), Path::of);
        assertEquals(2, r2.errors().size(), r2.errors().toString());
        assertTrue(r2.errors().get(0).startsWith("nope.yaml: cannot read"), r2.errors().toString());
        assertTrue(r2.errors().get(1).contains("is not a stage-data file name"), r2.errors().toString());
    }

    @Test
    void theIndexNamesEveryContentFile() throws IOException {
        List<String> onDisk;
        try (Stream<Path> files = Files.list(CONTENT)) {
            onDisk = files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".yaml")).sorted().toList();
        }
        List<String> indexed = new ArrayList<>();
        for (String line : Files.readAllLines(CONTENT.resolve(StageData.INDEX))) {
            String n = line.strip();
            if (!n.isEmpty() && !n.startsWith("#")) {
                indexed.add(n);
            }
        }
        assertEquals(onDisk, indexed.stream().sorted().toList(), "content/index.txt must list every content file");
        assertTrue(DataFiles.KNOWN.contains("soloMapling/mechanics/content/index.txt"));
    }

    @Test
    void theShippedLoadPathFindsKpqInTheSourceTree() {
        StageData.Result r = StageData.load();
        assertEquals(List.of(), r.errors());
        assertTrue(r.content().stream().anyMatch(c -> c.id().equals("kpq")));
    }
}
