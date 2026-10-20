package soloMapling.ArtificialPlayer.BotAttackSystem;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * The bots' attack export an attack capture is diffed against. Run with -Dbot.attack.export=<file> to write it:
 *   ./mvnw -q test -Dtest=BotAttackExportTest -Dbot.attack.export=target/bot-attacks.tsv
 */
class BotAttackExportTest {

    @Test
    void everyRowHasEveryColumn() {
        List<String> lines = BotAttackExport.lines();
        assertEquals(String.join("\t", BotAttackExport.COLUMNS), lines.get(0));
        assertTrue(lines.size() > 50, "rows for every job and weapon");
        for (String line : lines) {
            assertEquals(BotAttackExport.COLUMNS.size(), line.split("\t", -1).length, line);
        }
    }

    @Test
    void aRowNamesTheHeaderTheBotSends() {
        // A Hermit with a claw throws Avenger in its Skill.wz pose, a star flying.
        String avenger = BotAttackExport.lines().stream()
                .filter(l -> l.startsWith("411\tHERMIT\taoe\tCLAW\t"))
                .findFirst().orElseThrow();
        String[] f = avenger.split("\t", -1);
        assertEquals("ranged", f[5]);
        assertEquals("4111005", f[6]);
        assertEquals("0,128", f[11]);
        assertEquals("2070000", f[16]);
    }

    @Test
    void writesTheExportWhenAsked() throws IOException {
        String target = System.getProperty("bot.attack.export");
        if (target == null || target.isBlank()) {
            return;
        }
        Path file = Path.of(target);
        if (file.toAbsolutePath().getParent() != null) {
            Files.createDirectories(file.toAbsolutePath().getParent());
        }
        Files.write(file, BotAttackExport.lines(), StandardCharsets.UTF_8);
    }
}
