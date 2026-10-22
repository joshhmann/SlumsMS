package soloMapling.ArtificialPlayer.BotConvo;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Ticket 59, owner decision 4: when and how a bot's line appears. A typing delay of 1-6 s by length minus what the
 * brain already took - never faster than a person could type it from the moment the player's line arrived -, now and
 * then the line split in two, a late answer up to a minute later or a "brb" first, and a fighting bot that skips a
 * follow-up line. Pure: the caller sends the parts.
 */
public final class HumanTiming {

    /** One message to send {@code delayMs} after the previous part (the first: after the player's line). */
    public record Part(long delayMs, String text) {
    }

    /** What to do with one reply. Empty parts = ignore the line (fighting). */
    public record Plan(List<Part> parts, String kind) {
        public boolean ignored() {
            return parts.isEmpty();
        }

        public long totalMs() {
            return parts.stream().mapToLong(Part::delayMs).sum();
        }
    }

    /**
     * The situation of the reply.
     *
     * @param action    the line narrates a game action (it stays one prompt line, on time)
     * @param whisper   whispers and buddy chat (no late answers, no splitting)
     * @param fighting  a grinding bot with mobs in reach
     * @param firstLine the bot's first line in this chat (never ignored)
     */
    public record Context(boolean action, boolean whisper, boolean fighting, boolean firstLine) {
    }

    static final long MIN_MS = 1000;
    static final long MAX_MS = 6000;

    private HumanTiming() {
    }

    /** The fastest a person types {@code chars} characters, from the moment the line they answer arrived. */
    public static long humanFloorMs(int chars) {
        return 600 + 45L * chars;
    }

    /** The typing delay for a line, given the time the brain already took. */
    public static long typingMs(String line, long alreadyMs, Random rng) {
        int len = line == null ? 0 : line.length();
        long ms = Math.max(MIN_MS, Math.min(MAX_MS, MIN_MS + (long) (len * (55 + rng.nextInt(31)))));
        if (rng.nextDouble() < 0.08) {
            ms = Math.min(MAX_MS, ms + 800 + rng.nextInt(1500)); // looked away for a second
        }
        double scale = ConvoSwitches.prop("solomapling.chat.typingScale", 1.0);
        long wait = (long) (scale * ms) - alreadyMs;
        // the floor scales with the test knob only (production runs at 1.0)
        return Math.max((long) (150 * scale), Math.max(wait, (long) (scale * humanFloorMs(len)) - alreadyMs));
    }

    public static Plan plan(String line, long alreadyMs, ConvoPersona persona, Context ctx, Random rng) {
        if (line == null || line.isBlank()) {
            return new Plan(List.of(), "empty");
        }
        if (!ConvoSwitches.on("timing")) {
            return new Plan(List.of(new Part(typingMs(line, alreadyMs, rng), line)), "plain");
        }
        double chat = persona == null ? 0.4 : persona.chattiness();
        if (ctx.fighting() && !ctx.firstLine() && !ctx.action() && !ctx.whisper()
                && rng.nextDouble() < ConvoSwitches.num("ignoreFighting", 0.15)) {
            return new Plan(List.of(), "ignored while fighting");
        }
        boolean free = !ctx.action() && !ctx.whisper();
        if (free && !ctx.firstLine() && rng.nextDouble() < ConvoSwitches.num("brbChance", 0.02)) {
            long back = 20_000 + rng.nextInt(40_001);
            String later = (rng.nextBoolean() ? "back, " : "sry, ") + line;
            return new Plan(List.of(new Part(typingMs("brb", alreadyMs, rng), "brb"),
                    new Part(back, later.length() <= VoiceStyler.MAX ? later : line)), "brb");
        }
        if (free && rng.nextDouble() < ConvoSwitches.num("lateChance", 0.04)) {
            long late = 15_000 + rng.nextInt(45_001);
            return new Plan(List.of(new Part(Math.max(late, typingMs(line, alreadyMs, rng)), line)), "late");
        }
        if (free && line.length() > 28 && rng.nextDouble() < 0.08 + chat * 0.15) {
            int cut = splitPoint(line);
            if (cut > 0) {
                String a = line.substring(0, cut).trim();
                String b = line.substring(cut).replaceFirst("^[,;]\\s*", "").trim();
                if (!a.isEmpty() && !b.isEmpty()) {
                    List<Part> parts = new ArrayList<>();
                    parts.add(new Part(typingMs(a, alreadyMs, rng), a));
                    parts.add(new Part(typingMs(b, 0, rng), b));
                    return new Plan(parts, "split");
                }
            }
        }
        return new Plan(List.of(new Part(typingMs(line, alreadyMs, rng), line)), "typed");
    }

    /** Where a line breaks naturally in two: after a comma, before "and"/"but"/"lol", near the middle. 0 = nowhere. */
    static int splitPoint(String line) {
        int mid = line.length() / 2;
        int best = 0;
        int bestDist = Integer.MAX_VALUE;
        String lower = line.toLowerCase(java.util.Locale.ROOT);
        for (String mark : List.of(", ", " and ", " but ", " lol ", " tho ", "? ", "! ")) {
            int from = 0;
            int at;
            while ((at = lower.indexOf(mark, from)) > 0) {
                int cut = mark.startsWith(",") || mark.startsWith("?") || mark.startsWith("!") ? at + 1 : at;
                if (cut >= 8 && line.length() - cut >= 6 && Math.abs(cut - mid) < bestDist) {
                    best = cut;
                    bestDist = Math.abs(cut - mid);
                }
                from = at + 1;
            }
        }
        return best;
    }
}
