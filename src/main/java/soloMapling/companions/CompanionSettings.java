package soloMapling.companions;

// Ported from https://github.com/Incluwin/BotStory.git
//   src/main/java/server/living/roster/CompanionSettings.java @ 81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364
// changed: one record for the owner's switches and potion lines, without their formation/loot-policy types - the loot
// mode is a plain string this branch only stores for group D; the percentage bounds and the "not written at its
// default" rule are theirs; the JSON is hand-rolled (no Jackson), and 0 means "the bot's own line" so an untouched
// companion keeps today's behaviour exactly (habit line, boss-run line).

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 1.6.7 alt-qol-batch group B: what an owner chose for one companion - the three support switches, the HP and MP
 * potion percentages, and (stored only, for group D) the loot mode. Kept as one JSON blob by
 * {@link CompanionSettingsStore}.
 *
 * <p>{@link #DEFAULT} is today's behaviour: the companion heals, gives its party buffs and drinks its buff potions,
 * and its recovery potions follow {@code BotVitals}' own line (habit 40-60%, 50% offscreen, 70/50% in a boss run).
 * A percentage of {@link #AUTO} (0) means "its own line"; a chosen one is clamped to 10..90 and then replaces the line.
 */
public record CompanionSettings(boolean heals, boolean support, boolean buffPots, int hpPercent, int mpPercent,
                                String lootMode) {

    /** The lowest and highest potion percentage an owner may set. */
    public static final int MIN_PERCENT = 10;
    public static final int MAX_PERCENT = 90;

    /** "The companion's own line" (BotStory's {@code potionsAsConfigured}). */
    public static final int AUTO = 0;

    /** What an untouched companion runs on, and every field's default. */
    public static final CompanionSettings DEFAULT = new CompanionSettings(true, true, true, AUTO, AUTO, "");

    public CompanionSettings {
        lootMode = lootMode == null ? "" : lootMode.trim().toLowerCase(Locale.ROOT);
    }

    /** The same settings with the percentages inside 10..90 (0 stays {@link #AUTO}). */
    public CompanionSettings clamped() {
        return new CompanionSettings(heals, support, buffPots, percent(hpPercent), percent(mpPercent), lootMode);
    }

    /** A percentage inside 10..90, or {@link #AUTO}. */
    public static int percent(int percent) {
        if (percent <= AUTO) {
            return AUTO;
        }
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, percent));
    }

    /** True when nothing was chosen: the row is deleted rather than written (BotStory's "default not written"). */
    public boolean isDefault() {
        return equals(DEFAULT);
    }

    public CompanionSettings withHeals(boolean on) {
        return new CompanionSettings(on, support, buffPots, hpPercent, mpPercent, lootMode);
    }

    public CompanionSettings withSupport(boolean on) {
        return new CompanionSettings(heals, on, buffPots, hpPercent, mpPercent, lootMode);
    }

    public CompanionSettings withBuffPots(boolean on) {
        return new CompanionSettings(heals, support, on, hpPercent, mpPercent, lootMode);
    }

    public CompanionSettings withHpPercent(int percent) {
        return new CompanionSettings(heals, support, buffPots, percent, mpPercent, lootMode).clamped();
    }

    public CompanionSettings withMpPercent(int percent) {
        return new CompanionSettings(heals, support, buffPots, hpPercent, percent, lootMode).clamped();
    }

    public CompanionSettings withLootMode(String mode) {
        return new CompanionSettings(heals, support, buffPots, hpPercent, mpPercent, mode);
    }

    /** The HP line as a fraction of max HP, or 0 when the companion keeps its own. */
    public double hpLine() {
        return hpPercent == AUTO ? 0 : hpPercent / 100.0;
    }

    /** The MP line as a fraction of max MP, or 0 when the companion keeps its own. */
    public double mpLine() {
        return mpPercent == AUTO ? 0 : mpPercent / 100.0;
    }

    /** The settings as JSON: every field, so a reader never has to know the defaults. */
    public String toJson() {
        StringBuilder out = new StringBuilder("{");
        out.append("\"heals\":").append(heals);
        out.append(",\"support\":").append(support);
        out.append(",\"buffPots\":").append(buffPots);
        out.append(",\"hpPercent\":").append(hpPercent);
        out.append(",\"mpPercent\":").append(mpPercent);
        out.append(",\"lootMode\":\"").append(escape(lootMode)).append('"');
        return out.append('}').toString();
    }

    /**
     * The settings in {@code json}: {@link #DEFAULT} for null, blank or unreadable JSON, and the default for each
     * missing or bad field (unknown keys are ignored, so a later branch may add one).
     */
    public static CompanionSettings parse(String json) {
        if (json == null || json.isBlank()) {
            return DEFAULT;
        }
        Map<String, String> fields;
        try {
            fields = fields(json);
        } catch (RuntimeException e) {
            return DEFAULT;
        }
        return new CompanionSettings(
                flag(fields.get("heals"), DEFAULT.heals()),
                flag(fields.get("support"), DEFAULT.support()),
                flag(fields.get("buffPots"), DEFAULT.buffPots()),
                percent(number(fields.get("hpPercent"))),
                percent(number(fields.get("mpPercent"))),
                unescape(fields.getOrDefault("lootMode", ""))).clamped();
    }

    private static Map<String, String> fields(String json) {
        String body = json.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) {
            throw new IllegalArgumentException("not an object: " + json);
        }
        Map<String, String> out = new LinkedHashMap<>();
        int i = 1;
        int end = body.length() - 1;
        while (i < end) {
            while (i < end && (body.charAt(i) == ',' || body.charAt(i) == ' ')) {
                i++;
            }
            if (i >= end) {
                break;
            }
            if (body.charAt(i) != '"') {
                throw new IllegalArgumentException("expected a key at " + i + " in " + json);
            }
            int keyEnd = body.indexOf('"', i + 1);
            if (keyEnd < 0) {
                throw new IllegalArgumentException("unterminated key in " + json);
            }
            String key = body.substring(i + 1, keyEnd);
            i = keyEnd + 1;
            while (i < end && (body.charAt(i) == ' ' || body.charAt(i) == ':')) {
                i++;
            }
            String value;
            if (i < end && body.charAt(i) == '"') {
                StringBuilder text = new StringBuilder();
                i++;
                while (i < end && body.charAt(i) != '"') {
                    char ch = body.charAt(i);
                    if (ch == '\\' && i + 1 < end) {
                        text.append(body.charAt(i + 1));
                        i += 2;
                        continue;
                    }
                    text.append(ch);
                    i++;
                }
                i++; // closing quote
                value = "\"" + text; // marked so unescape() knows it was a string
            } else {
                int start = i;
                while (i < end && body.charAt(i) != ',') {
                    i++;
                }
                value = body.substring(start, i).trim();
            }
            out.put(key, value);
        }
        return out;
    }

    private static boolean flag(String value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        return fallback;
    }

    private static int number(String value) {
        if (value == null) {
            return AUTO;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return AUTO;
        }
    }

    /** A string field as it was written ("loot mode names are stored, not read"). */
    private static String unescape(String value) {
        return value.startsWith("\"") ? value.substring(1) : "";
    }

    private static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '"' || ch == '\\') {
                out.append('\\');
            }
            if (ch < ' ') {
                continue;
            }
            out.append(ch);
        }
        return out.toString();
    }
}
