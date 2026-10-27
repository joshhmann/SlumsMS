package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** The labelled chat lines under {@code src/test/resources/chat/} (ticket 36): the tuning set and the held-out set. */
final class ChatLabelledSet {

    record Line(String text, String tier, String intent, String con) {

        /** The binary tier the model is asked: everything but real talk is filler. */
        String fillerOrReply() {
            return tier.equals("llm") ? "reply" : "filler";
        }
    }

    private ChatLabelledSet() {
    }

    static final String TUNING = "labelled-lines.tsv";
    static final String HOLDOUT = "holdout-lines.tsv";

    /**
     * Frozen lines whose con label the owner's later definitions changed, read with the label they have now. The held-out
     * file itself is frozen (tools/chatbank/HOLDOUT-SHA256SUMS) and keeps its original label. Owner 2026-10-27: asking
     * for a password or login is scam, not rwt.
     */
    static final java.util.Map<String, String> CON_NOW = java.util.Map.of("tell me ur pw and ill give u nx", "scam");

    static List<Line> load() {
        return load(TUNING);
    }

    static List<Line> load(String file) {
        try (InputStream in = ChatLabelledSet.class.getResourceAsStream("/chat/" + file)) {
            if (in == null) {
                throw new IllegalStateException(file + " is missing");
            }
            List<Line> lines = new ArrayList<>();
            for (String row : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
                if (row.isBlank() || row.startsWith("#")) {
                    continue;
                }
                String[] f = row.split("\t");
                lines.add(new Line(f[0], f[1], f[2], CON_NOW.getOrDefault(f[0], f[3])));
            }
            return lines;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The tier the rules give a line kind. */
    static String tierOf(ChatTriage.Kind kind) {
        return switch (kind) {
            case IGNORE -> "ignore";
            case EMOTE -> "emote";
            case GREETING, THANKS, BYE -> "scripted";
            case TALK -> "llm";
        };
    }
}
