package soloMapling.ArtificialPlayer.BotDialoguePack;

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
 * A bot never says the brand (audit 163 #10): the shipped dialogue packs are said verbatim, so the check has to be on
 * the files, not only on model replies ({@code ReplyCleaner}'s brand rule covers those).
 */
class DialogueBrandTest {

    private static final Path PACK = Path.of("src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack");
    /** What a player line must never contain; comments are exempt (they talk about the client, nobody sees them). */
    private static final Pattern BRAND = Pattern.compile("(?i)\\b(maple\\s?story|maple\\s?root|nexon|wizet|gms|v83)\\b");

    /** The canned lines (no model involved) are player text too: a bot says them verbatim when the fast path answers. */
    private static final Path CANNED = Path.of("src/main/java/soloMapling/ArtificialPlayer/ScriptedReplies.java");

    @Test
    void noCannedReplyNamesTheBrand() throws IOException {
        List<String> lines = Files.readAllLines(CANNED);
        List<String> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.strip().startsWith("*") || line.strip().startsWith("//")) {
                continue;
            }
            Matcher m = BRAND.matcher(line);
            if (m.find()) {
                hits.add(CANNED.getFileName() + ":" + (i + 1) + " " + m.group());
            }
        }
        assertTrue(hits.isEmpty(), "canned replies that name the brand:\n  " + String.join("\n  ", hits));
    }

    @Test
    void noShippedDialogueLineNamesTheBrand() throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.list(PACK)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    Matcher m = BRAND.matcher(line);
                    if (m.find()) {
                        hits.add(PACK.getFileName() + "/" + file.getFileName() + ":" + (i + 1) + " " + m.group());
                    }
                }
            }
        }
        assertTrue(hits.isEmpty(), "dialogue lines that name the brand:\n  " + String.join("\n  ", hits));
    }
}
