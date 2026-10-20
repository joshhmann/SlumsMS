package soloMapling.ArtificialPlayer;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DialoguePackCheckTest {

    @Test
    void everyPackLoadsCleanly() {
        DialoguePackCheck.Result r = DialoguePackCheck.run();
        assertTrue(r.packs() >= 20, "packs found: " + r.packs());
        assertTrue(r.lines() > 5_000, "lines found: " + r.lines());
        assertEquals(List.of(), r.errors());
    }

    /** Ticket 47 review: post-2009 slang and stack names stay out of bot chat (comments are fine). */
    @Test
    void reviewedPacksStayFreeOfModernSlang() throws Exception {
        Pattern banned = Pattern.compile("\\b(vibes?|vibing|lowkey|no cap|fr fr|bussin|rizz|simp(ing)?|goated|"
                + "the drip|built different|its giving|rent free|touch grass|let him cook|skill issue|bestie|periodt|"
                + "hits different|discord|twitch|tiktok|venmo|crypto|go brr+|chef's kiss|20(1|2)\\d)\\b");
        File[] packs = new File(DialoguePackCheck.PACK_DIR).listFiles((d, n) -> n.endsWith(".yaml"));
        StringBuilder hits = new StringBuilder();
        for (File f : packs) {
            int n = 0;
            for (String line : Files.readAllLines(f.toPath())) {
                n++;
                String code = line.strip();
                if (code.startsWith("#") || !code.startsWith("-") && !code.contains("text:") && !code.contains("line:")) {
                    continue;
                }
                if (banned.matcher(code.toLowerCase(Locale.ROOT)).find()) {
                    hits.append(f.getName()).append(':').append(n).append(' ').append(code).append('\n');
                }
            }
        }
        assertEquals("", hits.toString());
    }
}
