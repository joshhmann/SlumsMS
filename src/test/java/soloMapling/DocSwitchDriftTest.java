package soloMapling;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.2 bot audit (doc drift): {@code slums.inspect}, {@code slums.killcalib} and {@code slums.navTrace} shipped
 * {@code true} in {@code docs/release/1.6.2.conf} while their javadocs and design docs still told the reader they were
 * off on live. The switches are on now, so no line about one of them may still say it is off on live.
 */
class DocSwitchDriftTest {

    private static final Path CONF = Path.of("docs", "release", "1.6.2.conf");

    /** The diagnostics this release turns on for live (owner 2026-10-30). */
    private static final List<String> SHIPPED_ON = List.of("slums.inspect", "slums.killcalib", "slums.navTrace");

    /** Every file that describes one of them. */
    private static final List<Path> DOCS = List.of(
            Path.of("src/main/java/soloMapling/inspect/BotInspectApi.java"),
            Path.of("src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java"),
            Path.of("src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/NavTrace.java"),
            Path.of("src/main/java/soloMapling/server/DebugTools.java"),
            Path.of("src/main/java/client/command/commands/gm3/KillCalibCommand.java"),
            Path.of("src/main/java/net/server/Server.java"),
            Path.of("src/main/java/server/maps/MapleMap.java"),
            Path.of("src/main/java/client/command/CommandsExecutor.java"),
            Path.of("src/main/java/soloMapling/dashboard/DashboardExporter.java"),
            Path.of("docs", "design", "inspect-api.md"),
            Path.of("docs", "PROVENANCE.md"),
            // audit 163 (LOW, doc drift): the two lines the 11-file list missed
            Path.of("docs", "tickets", "157-nutnnut-tools.md"),
            Path.of("src/test/java/soloMapling/inspect/BotInspectApiTest.java"));

    @Test
    void theDiagnosticsThisReleaseTurnsOnAreNotDocumentedOffOnLive() throws IOException {
        String conf = Files.readString(CONF, StandardCharsets.UTF_8);
        for (String flag : SHIPPED_ON) {
            assertTrue(conf.contains("-D" + flag + "=true"), flag + " is not on in " + CONF + " any more: update "
                    + "this test and the docs together");
        }
        for (Path doc : DOCS) {
            assertTrue(Files.isRegularFile(doc), "missing doc: " + doc);
            for (String line : Files.readString(doc, StandardCharsets.UTF_8).split("\n")) {
                boolean names = SHIPPED_ON.stream().anyMatch(line::contains);
                assertFalse(names && line.contains("off on live"),
                        doc + " still calls a shipped-on diagnostic off on live: " + line.trim());
            }
        }
    }
}
