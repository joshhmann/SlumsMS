package soloMapling.ArtificialPlayer.BotPartySystem;

import java.util.Locale;
import java.util.Set;

/**
 * 1.6.2 ticket 99: where each companion stands next to the player it follows - ported from nutnnut/Cosmic 8fd725404
 * ("Add formation command; stagger bots to avoid stacking on owner", {@code BotManager.FormationState}): an index
 * based offset per follower instead of everyone on the player's feet. Types: split (stagger, the default), spread,
 * stack, random, left, right; tight and loose are the split at 30 and 120 px; a number sets the spacing.
 *
 * <p>Ours: the spacing is capped ({@link #MAX_PX}) so a follower never stands a screen away, and an offset is
 * capped at {@link #MAX_OFFSET} so the fifth companion of a "right 120" line is still beside you. Pure.
 */
public record Formation(Type type, int px) {

    public enum Type { SPLIT, SPREAD, STACK, RANDOM, LEFT, RIGHT }

    /** nutnnut's FOLLOW_STAGGER default. */
    public static final int DEFAULT_PX = 60;
    public static final int TIGHT_PX = 30;
    public static final int LOOSE_PX = 120;
    public static final int MAX_PX = 150;
    public static final int MAX_OFFSET = 360;
    /** Arg of the "snap" order: take formation places again now. */
    public static final String SNAP = "snap";
    /** Formation words read without the word "formation" ("spread out", "stack up", "tight"). */
    static final Set<String> BARE_WORDS = Set.of("split", "spread", "stack", "tight", "loose", "snap", "stagger");

    public static final Formation DEFAULT = new Formation(Type.SPLIT, DEFAULT_PX);

    /** The offset (px, + is right) of the follower at {@code idx} (0 = first companion). */
    public int offsetFor(int idx) {
        int i = Math.max(0, idx);
        int dx = switch (type) {
            case SPLIT -> (i % 2 == 0 ? 1 : -1) * (i / 2 + 1) * px;
            // ours: a fixed scatter per place (it is re-read every tick, so a fresh roll would jitter)
            case RANDOM -> px <= 0 ? 0 : new java.util.Random(31L * i + 17).nextInt(2 * px + 1) - px;
            case STACK -> 0;
            // nutnnut: idx 0 = on the owner, then alternating: 0, +px, -px, +2px, -2px ...
            case SPREAD -> i == 0 ? 0 : (i % 2 == 1 ? 1 : -1) * ((i + 1) / 2) * px;
            case LEFT -> -(i + 1) * px;
            case RIGHT -> (i + 1) * px;
        };
        return Math.max(-MAX_OFFSET, Math.min(MAX_OFFSET, dx));
    }

    /**
     * A formation from its words: {@code type} (split/stagger, spread, stack, random, left, right, tight, loose) and an
     * optional size (a number of px, "tight" or "loose"). Null when the words are not a formation.
     */
    public static Formation parse(String type, String size) {
        if (type == null) {
            return null;
        }
        Integer px = DEFAULT_PX;
        if (size != null) {
            px = sizePx(size);
            if (px == null) {
                return null;
            }
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "split", "stagger" -> new Formation(Type.SPLIT, px);
            case "tight" -> size == null ? new Formation(Type.SPLIT, TIGHT_PX) : null;
            case "loose" -> size == null ? new Formation(Type.SPLIT, LOOSE_PX) : null;
            case "stack" -> size == null ? new Formation(Type.STACK, 0) : null;
            case "spread" -> new Formation(Type.SPREAD, px);
            case "random" -> new Formation(Type.RANDOM, px);
            case "left" -> new Formation(Type.LEFT, px);
            case "right" -> new Formation(Type.RIGHT, px);
            default -> null;
        };
    }

    private static Integer sizePx(String size) {
        String s = size.toLowerCase(Locale.ROOT);
        if (s.equals("tight")) {
            return TIGHT_PX;
        }
        if (s.equals("loose")) {
            return LOOSE_PX;
        }
        if (s.matches("\\d{1,4}")) {
            return Math.max(10, Math.min(MAX_PX, Integer.parseInt(s)));
        }
        return null;
    }

    /** "SPREAD:60" - the order's arg. */
    public String toArg() {
        return type.name() + ":" + px;
    }

    /** Back from {@link #toArg}; null for "snap" or anything else. */
    public static Formation fromArg(String arg) {
        if (arg == null || arg.equals(SNAP)) {
            return null;
        }
        int colon = arg.indexOf(':');
        try {
            return new Formation(Type.valueOf(arg.substring(0, colon)), Integer.parseInt(arg.substring(colon + 1)));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** How a companion says it in chat: "spread, 60 apart". */
    public String said() {
        String name = type == Type.SPLIT ? (px == TIGHT_PX ? "tight" : px == LOOSE_PX ? "loose" : "split")
                : type.name().toLowerCase(Locale.ROOT);
        return type == Type.STACK ? "stacking up" : name + (type == Type.SPLIT && (px == TIGHT_PX || px == LOOSE_PX)
                ? "" : ", " + px + " apart");
    }
}
