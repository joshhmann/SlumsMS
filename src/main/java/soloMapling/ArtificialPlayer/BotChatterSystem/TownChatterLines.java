package soloMapling.ArtificialPlayer.BotChatterSystem;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

// Loads TownChatterDialogue.yaml: ordered bot-to-bot small-talk exchanges. Unlike the single-line dialogue
// nodes served by BotDialogueHandler (one random line from a node), an exchange is an ORDERED list of
// alternating turns (A,B,A,B...), which is exactly what two-party chatter needs. YAML-first with a
// defensive fail-soft parse (bad edit -> empty list, BotChatter then falls back to its hardcoded exchange),
// same pattern as TownPresenceConfig. Our own creation (not a GreenCat extraction).
public final class TownChatterLines {

    private TownChatterLines() {
    }

    private static final String YAML_PATH =
            "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/TownChatterDialogue.yaml";

    private static volatile List<List<String>> cached;
    private static final Random RANDOM = new Random();

    // Parsed exchanges (cached after first load). Empty list on any parse/IO failure.
    public static List<List<String>> exchanges() {
        List<List<String>> local = cached;
        if (local == null) {
            local = load();
            cached = local;
        }
        return local;
    }

    // Force a re-read from disk (used by the live-tuning !env chatter command so edits apply without a restart).
    public static List<List<String>> reload() {
        cached = load();
        return cached;
    }

    // Ticket 47: exchanges are dealt like a shuffled deck, so every one is used once before any repeats (a plain
    // random pick replayed the same exchange minutes apart in a busy town).
    private static final java.util.ArrayDeque<List<String>> deck = new java.util.ArrayDeque<>();
    private static List<List<String>> dealtFrom;

    // A random ordered exchange (>= 2 turns), or null if none are loaded.
    public static synchronized List<String> randomExchange() {
        List<List<String>> all = exchanges();
        if (all.isEmpty()) {
            return null;
        }
        if (deck.isEmpty() || dealtFrom != all) {
            List<List<String>> shuffled = new ArrayList<>(all);
            java.util.Collections.shuffle(shuffled, RANDOM);
            deck.clear();
            deck.addAll(shuffled);
            dealtFrom = all;
        }
        return deck.poll();
    }

    @SuppressWarnings("unchecked")
    private static List<List<String>> load() {
        List<List<String>> out = new ArrayList<>();
        try {
            YamlReader reader = new YamlReader(new FileReader(soloMapling.DataFiles.file(YAML_PATH)));
            Map<String, Object> root = (Map<String, Object>) reader.read();
            if (root == null) {
                return out;
            }
            Object node = root.get("exchanges");
            if (!(node instanceof List<?> list)) {
                return out;
            }
            for (Object ex : list) {
                if (!(ex instanceof List<?> turns)) {
                    continue;
                }
                List<String> lines = new ArrayList<>();
                for (Object t : turns) {
                    if (t != null) {
                        String s = t.toString().trim();
                        if (!s.isEmpty()) {
                            lines.add(s);
                        }
                    }
                }
                if (lines.size() >= 2) {
                    out.add(lines);
                }
            }
        } catch (Exception e) {
            System.out.println("[TownChatterLines] failed to load " + YAML_PATH + ": " + e.getMessage());
            return new ArrayList<>();
        }
        return out;
    }
}
