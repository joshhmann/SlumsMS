package tools;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 b095: SQL must use the connection's schema. Leaderboard and transaction queries named {@code mapleroot.}
 * tables, so on TEST (schema mapleroot_persist) they read or wrote live's schema name and failed; live (schema
 * mapleroot) behaves the same either way.
 */
class NoSchemaPrefixSqlTest {

    private static final Pattern PREFIXED = Pattern.compile("(?i)\\b(FROM|JOIN|INTO|UPDATE|TABLE)\\s+`?mapleroot`?\\.");

    @Test
    void noSqlStringNamesTheLiveSchema() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
                List<String> lines = Files.readAllLines(p);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = PREFIXED.matcher(lines.get(i));
                    if (m.find()) {
                        hits.add(p + ":" + (i + 1) + ": " + lines.get(i).trim());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "schema-prefixed SQL:\n" + String.join("\n", hits));
    }
}
