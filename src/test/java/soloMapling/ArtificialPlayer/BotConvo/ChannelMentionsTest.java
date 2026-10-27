package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ChannelMentionsTest {
    private static String fix(String line, int ch, int mine) {
        return ChannelMentions.fix(line, ch, mine, bound -> 0); // always the lowest choice
    }

    @Test
    void rewritesEveryFormAboveTheCount() {
        assertEquals("ch1 is empty af", ChannelMentions.fix("ch8 is empty af", 1, 1, b -> 0));
        for (String form : new String[]{"ch8", "ch 8", "CH8", "channel 8", "ch.8", "Ch. 8", "chan 8"}) {
            String out = fix(form + " is dead", 5, 1);
            assertNotEquals(form + " is dead", out, form);
            assertTrue(out.endsWith(" is dead") && out.matches("(?i).*\\b2$|.*2 is dead"), out);
        }
    }

    @Test
    void quietLinesNeverPointAtChannelOne() {
        for (int r = 0; r < 4; r++) {
            final int k = r;
            int n = Integer.parseInt(ChannelMentions.fix("ch8 is empty af come train in peace", 5, 1, b -> Math.min(k, b - 1)).replaceAll("\\D", ""));
            assertTrue(n >= 2 && n <= 5, "picked " + n);
        }
    }

    @Test
    void hereMeansTheSpeakersChannel() {
        assertEquals("come to ch 4 its quiet here", ChannelMentions.fix("come to ch 9 its quiet here", 5, 4, b -> 0));
    }

    @Test
    void validMentionsAndNonChannelsAreUntouched() {
        for (String s : new String[]{"ch5 fm", "ch 1", "channel 3", "lv 8 mage", "need 8m for it", "each8 time", "touch8", "ch8x",
                "ch 12abc", "hello", "", "c8 is a cool name", "party at 8"}) {
            assertSame(s, ChannelMentions.fix(s, 5, 1), s);
        }
        assertSame(null, ChannelMentions.fix(null, 5, 1));
        assertEquals("ch 9 x", ChannelMentions.fix("ch 9 x", 0, 1), "world not up: no change");
    }

    @Test
    void shortFormOnlyWhenClearlyAChannel() {
        assertEquals("cc to c1 for the boss", fix("cc to c8 for the boss", 5, 1));
        assertEquals("c8 for me", fix("c8 for me", 5, 1));
    }

    @Test
    void sameWrongNumberGetsSameReplacementAndOthersKeep() {
        assertEquals("ch2 or ch 3, ch2 again", ChannelMentions.fix("ch7 or ch 3, ch7 again", 5, 1, b -> 1 % b)
                .replace("ch 3", "ch 3"));
        assertEquals("ch 1 and ch 1", ChannelMentions.fix("ch 6 and ch 6", 1, 1));
    }

    @Test
    void shipDialoguePacksNameNoChannelAboveFive() throws IOException {
        Pattern p = Pattern.compile("(?<![\\p{L}\\p{N}])(?:ch\\.?|chan(?:nel)?\\.?)\\s?(\\d{1,3})(?![\\p{L}\\p{N}])", Pattern.CASE_INSENSITIVE);
        List<String> bad = new ArrayList<>();
        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(Path.of("src/main"))) {
            s.filter(f -> f.toString().endsWith(".yaml") && f.toString().contains("Dialogue")).forEach(files::add);
        }
        files.add(Path.of("tools/chatbank/state/lines.tsv"));
        assertTrue(files.size() > 5, "found the packs");
        for (Path f : files) {
            int ln = 0;
            for (String line : Files.readAllLines(f)) {
                ln++;
                Matcher m = p.matcher(line);
                while (m.find()) {
                    if (Integer.parseInt(m.group(1)) > 5) {
                        bad.add(f + ":" + ln + " " + line.trim());
                    }
                }
            }
        }
        assertTrue(bad.isEmpty(), "the server has 5 channels; fix: " + bad);
    }
}
