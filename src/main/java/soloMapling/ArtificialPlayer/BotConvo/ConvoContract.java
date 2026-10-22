package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ticket 59, owner decision 1: the reply contract. A model reply is one chat line plus at most one proposed action,
 * written as a tag at the end ({@code "ya omw [do:come]"}). The tag only proposes: {@link ConvoGates} decides.
 * Every tag is stripped from the text, also unknown ones and extra ones; an unknown name is no action.
 */
public final class ConvoContract {

    /** One parsed reply: the line without tags, the proposed action (NONE for none) and its short argument. */
    public record Reply(String text, ConvoAction action, String arg) {
        public static Reply plain(String text) {
            return new Reply(text, ConvoAction.NONE, null);
        }

        public Reply withText(String t) {
            return new Reply(t, action, arg);
        }
    }

    private static final Pattern TAG = Pattern.compile("(?i)[\\[(<{]\\s*do\\s*[:=]\\s*([a-z][a-z0-9_-]{0,23})"
            + "(?:[\\s:,|]+([^\\]\\)>}]{1,40}?))?\\s*[\\])>}]");
    /** Loose leftovers: "[action: none]", "(none)" lines. */
    private static final Pattern LOOSE = Pattern.compile("(?i)[\\[(]\\s*(action|tag|do)\\b[^\\])]*[\\])]");

    /** The few lines the system prompt adds so the model knows the contract. */
    public static String instructions() {
        List<String> tags = java.util.Arrays.stream(ConvoAction.values()).filter(a -> a.postHoc)
                .map(a -> "[do:" + a.tag() + "]").collect(Collectors.toList());
        return "Only if your line itself says you will DO something for them right now, end it with one tag: "
                + String.join(" ", tags) + " (like \"omw [do:come]\" or \"here, haste [do:buff]\"). Almost every line has"
                + " no tag. The tag is hidden from them.";
    }

    private ConvoContract() {
    }

    /** Reads a raw model reply. Null in, null out. */
    public static Reply parse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        Reply json = fromJson(text);
        if (json != null) {
            return json;
        }
        ConvoAction action = ConvoAction.NONE;
        String arg = null;
        Matcher m = TAG.matcher(text);
        if (m.find()) { // only the first tag counts, known or not
            action = ConvoAction.parse(m.group(1));
            arg = m.group(2) == null || m.group(2).isBlank() ? null : m.group(2).trim();
        }
        text = TAG.matcher(text).replaceAll(" ");
        text = LOOSE.matcher(text).replaceAll(" ");
        text = text.replaceAll("[ \\t]{2,}", " ").replaceAll("\\s+([,.!?])", "$1").trim();
        return new Reply(text, action, action == ConvoAction.NONE ? null : arg);
    }

    /** {@code {"say": "...", "do": "come"}} (small models sometimes answer in JSON). */
    private static Reply fromJson(String text) {
        if (!text.startsWith("{") || !text.endsWith("}")) {
            return null;
        }
        Object parsed;
        try {
            parsed = Json.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
        if (!(parsed instanceof Map<?, ?> map)) {
            return null;
        }
        Object say = map.containsKey("say") ? map.get("say") : map.containsKey("text") ? map.get("text") : map.get("reply");
        if (!(say instanceof String line)) {
            return null;
        }
        Object act = map.containsKey("do") ? map.get("do") : map.get("action");
        ConvoAction action = act instanceof String s ? ConvoAction.parse(s) : ConvoAction.NONE;
        Object arg = map.get("arg");
        return new Reply(line.trim(), action, action == ConvoAction.NONE || !(arg instanceof String a) || a.isBlank()
                ? null : a.trim());
    }
}
