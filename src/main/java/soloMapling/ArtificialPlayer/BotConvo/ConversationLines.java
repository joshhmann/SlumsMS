package soloMapling.ArtificialPlayer.BotConvo;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ticket 59: the scripted bot-to-bot conversations of {@code ConversationDialogue.yaml}, as whole scripts (thread
 * fallback when the LLM has no spare slot) and as single lines (seed for the line bank).
 */
public final class ConversationLines {

    static final String FILE = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/ConversationDialogue.yaml";

    /** One script: its name and its lines, each with the speaker (A, B, ...). */
    public record Script(String name, List<String[]> lines) {
    }

    private static volatile List<Script> scripts;

    private ConversationLines() {
    }

    @SuppressWarnings("unchecked")
    public static List<Script> scripts() {
        List<Script> s = scripts;
        if (s != null) {
            return s;
        }
        List<Script> out = new ArrayList<>();
        try (FileReader in = new FileReader(soloMapling.DataFiles.file(FILE))) {
            Object root = new YamlReader(in).read();
            if (root instanceof Map<?, ?> map && map.get("conversations") instanceof Map<?, ?> convs) {
                for (Map.Entry<?, ?> e : convs.entrySet()) {
                    if (!(e.getValue() instanceof Map<?, ?> conv) || !(conv.get("lines") instanceof List<?> lines)) {
                        continue;
                    }
                    List<String[]> ls = new ArrayList<>();
                    for (Object l : lines) {
                        if (l instanceof Map<?, ?> lm && lm.get("text") != null) {
                            ls.add(new String[]{String.valueOf(lm.get("speaker")), String.valueOf(lm.get("text"))});
                        }
                    }
                    if (!ls.isEmpty()) {
                        out.add(new Script(String.valueOf(e.getKey()), List.copyOf(ls)));
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // no scripts: threads use the bank and the LLM only
        }
        scripts = List.copyOf(out);
        return scripts;
    }

    /** Every line of every script. */
    public static List<String> all() {
        List<String> out = new ArrayList<>();
        for (Script s : scripts()) {
            for (String[] l : s.lines()) {
                out.add(l[1]);
            }
        }
        return out;
    }
}
