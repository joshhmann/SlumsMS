package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.qa.QaLog;

import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Ticket 59: the scripted bot-to-bot conversations of {@code ConversationDialogue.yaml}, as whole scripts (thread
 * fallback when the LLM has no spare slot) and as single lines (seed for the line bank).
 *
 * <p>A script is only ever played by bots that can back what it says: a script with an {@code S>} line the speaker
 * does not hold is not chosen at all, instead of being spoken and then reported unbacked by the say-do audit.
 */
public final class ConversationLines {

    static final String FILE = "src/main/java/soloMapling/ArtificialPlayer/BotDialoguePack/ConversationDialogue.yaml";

    /** A pick cannot flood the QA log: at most this many skipped-script lines per pick. */
    private static final int MAX_REFUSAL_LINES = 3;

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

    /** A script not chosen because one of its lines promises something its speaker would not do. */
    public record Refusal(String script, int mapId, String bot, String line, String claims) {
    }

    /** One line of a script that carries claims, with the speaker it is addressed to in the script's role letters. */
    private record ClaimLine(String role, String line, List<ClaimRules.Claim> claims) {
    }

    /** Classification is static per line; a pick runs it for every candidate, so it is done once per script. */
    private static final Map<String, List<ClaimLine>> CLAIMS = new ConcurrentHashMap<>();

    private static List<ClaimLine> claimsOf(Script script) {
        return CLAIMS.computeIfAbsent(script.name(), name -> {
            List<ClaimLine> out = new ArrayList<>();
            for (String[] l : script.lines()) {
                List<ClaimRules.Claim> claims = ClaimRules.classify(l[1]);
                if (!claims.isEmpty()) {
                    out.add(new ClaimLine(l[0], l[1], claims));
                }
            }
            return List.copyOf(out);
        });
    }

    /**
     * The first line of {@code script} that its speaker would not back, or null when every claim line is one the
     * participants will make true. Uses the same rule as every other spoken line ({@link ClaimActions#canBack}): an
     * {@code S>}/{@code B>} line needs the item, "brb" needs somewhere to restock, "trade me" needs a trade the bot
     * accepts, and so on. Claim-free scripts always pass.
     */
    public static Refusal unbackable(Script script, Function<String, Character> speakerFor) {
        for (ClaimLine c : claimsOf(script)) {
            Character who = speakerFor.apply(c.role());
            if (who == null) {
                continue;
            }
            if (!ClaimActions.canBack(who, c.claims())) {
                return new Refusal(script.name(), who.getMapId(), who.getName(), c.line(), c.claims().toString());
            }
        }
        return null;
    }

    /**
     * The first script of {@code candidates} that every participant can back, or null when none can. A script is
     * refused whole rather than cut, so a bot never says half of a conversation whose promise it cannot keep; the
     * caller then runs its unscripted path.
     */
    public static Script firstPlayable(List<Script> candidates, Function<String, Character> speakerFor,
                                       Consumer<Refusal> refused) {
        for (Script s : candidates) {
            Refusal r = unbackable(s, speakerFor);
            if (r == null) {
                return s;
            }
            if (refused != null) {
                refused.accept(r);
            }
        }
        return null;
    }

    /**
     * The QA log lines for the scripts a pick dropped, naming the bot and the line it could not back. Capped so a
     * pick on a busy town does not flood the CONVO topic.
     */
    public static void logRefusals(List<Refusal> refused) {
        if (refused == null || refused.isEmpty() || !QaLog.on(QaLog.Topic.CONVO)) {
            return;
        }
        int shown = Math.min(refused.size(), MAX_REFUSAL_LINES);
        for (int i = 0; i < shown; i++) {
            Refusal r = refused.get(i);
            QaLog.map(QaLog.Topic.CONVO, r.mapId(), "SCRIPT SKIP " + r.script() + ": " + r.bot()
                    + " would not back \"" + r.line() + "\" (" + r.claims() + ")");
        }
        if (refused.size() > shown) {
            QaLog.map(QaLog.Topic.CONVO, refused.get(0).mapId(), "SCRIPT SKIP " + (refused.size() - shown)
                    + " more script(s) not chosen: a participant would not back a line");
        }
    }
}
