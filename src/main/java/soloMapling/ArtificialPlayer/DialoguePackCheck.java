package soloMapling.ArtificialPlayer;

import com.esotericsoftware.yamlbeans.YamlReader;

import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static soloMapling.BotLogger.log;

/**
 * Ticket 47: reads every bot dialogue pack the way the game does (yamlbeans) and reports what would go wrong at
 * speaking time - a pack that does not parse, a node with no lines, an empty line, a non-ASCII character the v83
 * client cannot show, or a stray "{{" that silently drops a line's emote. Logged once when SoloMapling starts.
 */
public final class DialoguePackCheck {

    public static final String PACK_DIR = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/";

    public record Result(int packs, int lines, List<String> errors) {
    }

    private DialoguePackCheck() {
    }

    public static Result run() {
        return run(soloMapling.DataFiles.file(PACK_DIR));
    }

    public static Result run(File dir) {
        List<String> errors = new ArrayList<>();
        int packs = 0;
        int[] lines = {0};
        File[] files = dir.listFiles((d, name) -> name.endsWith(".yaml"));
        if (files == null) {
            errors.add("no pack folder at " + dir.getAbsolutePath());
            return new Result(0, 0, errors);
        }
        java.util.Arrays.sort(files);
        for (File file : files) {
            packs++;
            try {
                String raw = java.nio.file.Files.readString(file.toPath());
                int n = 0;
                for (String line : raw.split("\n", -1)) {
                    n++;
                    String code = line.strip();
                    if (code.startsWith("#")) {
                        continue;
                    }
                    if (code.startsWith("- {{")) {
                        errors.add(file.getName() + ":" + n + " double braces");
                    }
                    if (!code.chars().allMatch(c -> c < 128)) {
                        errors.add(file.getName() + ":" + n + " non-ASCII");
                    }
                }
                Object root;
                try (FileReader reader = new FileReader(file)) {
                    root = new YamlReader(reader).read();
                }
                walk(file.getName(), root, "", errors, lines);
            } catch (IOException | RuntimeException e) {
                errors.add(file.getName() + " does not load: " + e.getMessage());
            }
        }
        return new Result(packs, lines[0], errors);
    }

    private static void walk(String pack, Object node, String path, List<String> errors, int[] lines) {
        if (node instanceof Map<?, ?> map) {
            Object text = map.get("text");
            if (text instanceof List<?> list && !(path.endsWith("/lines"))) {
                if (list.isEmpty()) {
                    errors.add(pack + path + " has no lines");
                }
            }
            for (Map.Entry<?, ?> e : map.entrySet()) {
                walk(pack, e.getValue(), path + "/" + e.getKey(), errors, lines);
            }
        } else if (node instanceof List<?> list) {
            for (Object o : list) {
                walk(pack, o, path, errors, lines);
            }
        } else if (node instanceof String s && (path.endsWith("/text") || path.endsWith("/line")
                || path.endsWith("/lines") || path.endsWith("/exchanges") || isTopLevelList(path))) {
            lines[0]++;
            if (s.isBlank()) {
                errors.add(pack + path + " has an empty line");
            }
        }
    }

    private static boolean isTopLevelList(String path) {
        return path.chars().filter(c -> c == '/').count() == 1;
    }

    /** Logs the result; called when SoloMapling starts. */
    public static void logOnce() {
        Result r = run();
        log("[DialoguePacks] " + r.packs() + " packs, " + r.lines() + " lines, " + r.errors().size() + " errors");
        for (String e : r.errors()) {
            log("[DialoguePacks] " + e);
        }
    }
}
