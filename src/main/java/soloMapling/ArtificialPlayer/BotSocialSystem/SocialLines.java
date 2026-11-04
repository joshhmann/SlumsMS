package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotDialogueHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The words of the social acts: a line of a node of the existing drama bank ({@code DramaDialogue.yaml}, the
 * {@code Social*} nodes and the {@code Roast*} nodes for a spat), every token filled, {@link ContentGuard} on every
 * line, the draw from {@link SocialSim} so a test is exact. When the bank cannot be read (a unit test without the
 * pack, a missing node) the node's built-in fallback line is used, so an act never goes quiet because of a bank. The
 * model never writes these: the act is decided by the server and only voiced here.
 */
final class SocialLines {

    static final String PACK = "DramaDialogue.yaml";
    static final String ROOT = "Drama";

    private SocialLines() {
    }

    /** A line of {@code node} with {@code tokens} filled, or {@code fallback} filled when the bank gives none. */
    static String pick(String node, Map<String, String> tokens, Random rng, String fallback) {
        List<String> ok = new ArrayList<>();
        try {
            BotDialogueHandler.DialogueConstructor d = BotDialogueHandler.getDialogueCon(PACK, ROOT, node);
            if (d != null && d.getDialogue() != null) {
                for (String line : d.getDialogue()) {
                    String filled = fill(line, tokens);
                    if (filled != null && ContentGuard.ok(filled)) {
                        ok.add(filled);
                    }
                }
            }
        } catch (RuntimeException | LinkageError e) {
            ok.clear();
        }
        if (!ok.isEmpty()) {
            return ok.get(rng.nextInt(ok.size()));
        }
        String filled = fill(fallback, tokens);
        return filled != null ? filled : fill("hey {PLAYER}", tokens);
    }

    /** The line with every {TOKEN} replaced; null when one is left unfilled. */
    static String fill(String line, Map<String, String> tokens) {
        String out = line;
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            if (t.getValue() != null && !t.getValue().isBlank()) {
                out = out.replace("{" + t.getKey() + "}", t.getValue());
            }
        }
        return out.matches(".*\\{[A-Z_]+}.*") ? null : out;
    }
}
