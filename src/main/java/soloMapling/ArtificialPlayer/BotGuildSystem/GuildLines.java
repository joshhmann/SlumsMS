package soloMapling.ArtificialPlayer.BotGuildSystem;

import soloMapling.ArtificialPlayer.BotDialogueHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Scripted bot guild lines: {@code BotDialoguePack/GuildDialogue.yaml}, root {@code Guild}. */
final class GuildLines {

    static final String PACK = "GuildDialogue.yaml";

    private GuildLines() {
    }

    /** A random line of the node with every {TOKEN} filled; null when none fits (or the node is missing). */
    static String pick(String node, Map<String, String> tokens) {
        return pick(node, tokens, null);
    }

    /** As above, only a line {@code ok} accepts (null: any); null when none does. */
    static String pick(String node, Map<String, String> tokens, java.util.function.Predicate<String> ok) {
        BotDialogueHandler.DialogueConstructor dialog;
        try {
            dialog = BotDialogueHandler.getDialogueCon(PACK, "Guild", node);
        } catch (RuntimeException e) {
            return null;
        }
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        Collections.shuffle(lines);
        for (String line : lines) {
            String out = line;
            for (Map.Entry<String, String> t : tokens.entrySet()) {
                if (t.getValue() != null && !t.getValue().isBlank()) {
                    out = out.replace("{" + t.getKey() + "}", t.getValue());
                }
            }
            if (!out.matches(".*\\{[A-Z_]+}.*") && (ok == null || ok.test(out))) {
                return out;
            }
        }
        return null;
    }

    /** The vibe's own node first ("HardcoreChatter"), else the shared one ("Chatter"). */
    static String pickFor(GuildRules.Vibe vibe, String node, Map<String, String> tokens) {
        return pickFor(vibe, node, tokens, null);
    }

    /** As above, only a line {@code ok} accepts (null: any). */
    static String pickFor(GuildRules.Vibe vibe, String node, Map<String, String> tokens,
                          java.util.function.Predicate<String> ok) {
        String own = vibe == null ? null : pick(vibe.key() + node, tokens, ok);
        return own != null ? own : pick(node, tokens, ok);
    }
}
