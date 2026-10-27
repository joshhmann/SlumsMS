package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.0 t-ds-1: the in-process self-check of the decide shadow, with a fake model and no network. */
class DecideShadowSelfCheckTest {

    @AfterEach
    void off() {
        DecideShadow.install(null);
    }

    @Test
    void rowsAreWrittenAndWithoutTextOnlyTheLengthIsKept(@TempDir Path dir) throws Exception {
        List<String> out = DecideShadow.selfCheck(dir);
        assertTrue(out.get(0).startsWith("PASS"), out.toString());
        assertTrue(out.stream().anyMatch(l -> l.startsWith("text=false: 1 row") && l.contains("len=33")), out.toString());
        assertTrue(out.stream().anyMatch(l -> l.contains("shadow is off")), "the server's shadow is reported, not built");
        try (Stream<Path> left = Files.walk(dir)) {
            assertEquals(0, left.filter(Files::isRegularFile).count(), "the scratch rows are removed");
        }
    }

    @Test
    void theServersOwnShadowIsReportedAndLeftAlone(@TempDir Path dir) {
        DecideShadow mine = new DecideShadow(List.of(), 2, 1000, 400, "live", "gliner", r -> { }).keepText(false);
        DecideShadow.install(mine);
        List<String> out = DecideShadow.selfCheck(dir);
        assertTrue(out.get(0).startsWith("PASS"), out.toString());
        assertTrue(out.stream().anyMatch(l -> l.contains("text=false") && l.startsWith("the server's shadow")), out.toString());
        assertEquals(mine, DecideShadow.running());
    }
}
