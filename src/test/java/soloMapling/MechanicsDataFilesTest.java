package soloMapling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.mechanics.Mechanics;
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
    void kpqLoadsFromTheJarWithNoSourceTree() throws IOException {
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
        assertEquals(List.of("kpq"), r.content().stream().map(c -> c.id()).toList());
        assertEquals(10, r.content().get(0).stage(1).orElseThrow().answer(1).orElseThrow());
        assertTrue(origins.stream().allMatch(o -> o == DataFiles.Origin.CLASSPATH), origins.toString());
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
