package soloMapling;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataFilesTest {
    private static final String REL = "soloMapling/Test/Pack.yaml";
    private static final String SRC = DataFiles.SOURCE_PREFIX + REL;

    @TempDir
    Path tmp;

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private Path jarWith(String... entriesAndContents) throws IOException {
        Path jar = tmp.resolve("data.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar))) {
            for (int i = 0; i < entriesAndContents.length; i += 2) {
                out.putNextEntry(new JarEntry(entriesAndContents[i]));
                if (entriesAndContents[i + 1] != null) {
                    out.write(entriesAndContents[i + 1].getBytes(StandardCharsets.UTF_8));
                }
                out.closeEntry();
            }
        }
        return jar;
    }

    private DataFiles resolver(Path override, Path jar) throws IOException {
        ClassLoader cl = new URLClassLoader(jar == null ? new URL[0] : new URL[]{jar.toUri().toURL()}, null);
        return new DataFiles(override, tmp.resolve("work"), cl, tmp.resolve("extract"));
    }

    private static String read(DataFiles d, String path) throws IOException {
        return Files.readString(d.resolve(path, true).path());
    }

    @Test
    void overrideWinsOverSourceAndClasspath() throws IOException {
        write(tmp.resolve("override").resolve(REL), "override");
        write(tmp.resolve("work").resolve(SRC), "source");
        DataFiles d = resolver(tmp.resolve("override"), jarWith(REL, "jar"));

        assertEquals(DataFiles.Origin.OVERRIDE, d.resolve(SRC, true).origin());
        assertEquals("override", read(d, SRC));
    }

    @Test
    void sourceTreeWinsOverClasspathAndKeepsTheSamePath() throws IOException {
        write(tmp.resolve("work").resolve(SRC), "source");
        DataFiles d = resolver(tmp.resolve("no-such-override"), jarWith(REL, "jar"));

        DataFiles.Resolved r = d.resolve(SRC, true);
        assertEquals(DataFiles.Origin.SOURCE, r.origin());
        assertEquals(tmp.resolve("work").resolve(SRC), r.path());
        assertEquals("source", read(d, SRC));
    }

    @Test
    void classpathJarIsUsedWhenNothingIsOnDisk() throws IOException {
        DataFiles d = resolver(null, jarWith(REL, "jar"));

        assertEquals(DataFiles.Origin.CLASSPATH, d.resolve(SRC, true).origin());
        assertEquals("jar", read(d, SRC));
    }

    @Test
    void classpathFolderIsExtractedWhole() throws IOException {
        DataFiles d = resolver(null, jarWith(
                "soloMapling/", null,
                "soloMapling/Test/", null,
                "soloMapling/Test/map1/", null,
                "soloMapling/Test/map1/m1.csv", "a",
                "soloMapling/Test/map1/c1-2.bin", "b",
                "soloMapling/Test/map10/m1.csv", "other map"));

        DataFiles.Resolved r = d.resolve(DataFiles.SOURCE_PREFIX + "soloMapling/Test/map1/", true);
        assertEquals(DataFiles.Origin.CLASSPATH, r.origin());
        assertTrue(Files.isDirectory(r.path()));
        assertEquals(2, r.path().toFile().list().length);
        assertEquals("a", Files.readString(r.path().resolve("m1.csv")));
    }

    @Test
    void missingFallsBackToTheSourcePathAndIsCounted() throws IOException {
        DataFiles d = resolver(null, null);

        DataFiles.Resolved r = d.resolve(SRC, true);
        assertFalse(r.found());
        assertEquals(tmp.resolve("work").resolve(SRC), r.path());
        assertEquals(DataFiles.KNOWN.size(), d.check());
    }

    @Test
    void pathsOutsideTheSourceTreePassThrough() throws IOException {
        Path own = tmp.resolve("own.yaml");
        write(own, "x");
        DataFiles d = resolver(null, jarWith(REL, "jar"));

        DataFiles.Resolved r = d.resolve(own.toString(), true);
        assertEquals(DataFiles.Origin.PASSTHROUGH, r.origin());
        assertEquals(own, r.path());
    }

    @Test
    void everyKnownDataPathResolvesInThisCheckout() {
        DataFiles d = new DataFiles(null, Path.of(""), DataFiles.class.getClassLoader(), tmp.resolve("x"));
        assertEquals(0, d.check());
    }
}
