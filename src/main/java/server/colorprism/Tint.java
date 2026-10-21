package server.colorprism;

/**
 * The Coloring Prism tint model, shared by {@code Equip} (body and effect layer) and {@code Character} (hair and
 * face). A tint is a hue, a chroma and a brightness:
 * <ul>
 *   <li>hue: an ABSOLUTE target hue h in 0..359, stored as the signed short {@code -(h + 1)}, so -1 is 0 degrees and
 *       -360 is 359 degrees;</li>
 *   <li>chroma and bright: -100..100, percent toward 0 or 1 of saturation / value;</li>
 *   <li>0/0/0 is the one untinted (identity) value.</li>
 * </ul>
 * Setters accept only a hue in -360..-1 and clear the tint otherwise; chroma and bright are clamped. The client
 * (slums.dll weapontint.h) uses the same encoding, and the old relative encoding (positive hues) is not accepted.
 */
public final class Tint {
    public static final int HUE_MIN = -360;
    public static final int HUE_MAX = -1;
    public static final int DELTA_MIN = -100;
    public static final int DELTA_MAX = 100;

    private Tint() {
    }

    public static boolean isAbsoluteHue(int hue) {
        return hue >= HUE_MIN && hue <= HUE_MAX;
    }

    public static byte clampDelta(int delta) {
        return (byte) Math.max(DELTA_MIN, Math.min(DELTA_MAX, delta));
    }

    /** What the handler accepts from a client: a valid hue and both deltas in range (no clamping on input). */
    public static boolean isValid(int hue, int chroma, int bright) {
        return isAbsoluteHue(hue)
                && chroma >= DELTA_MIN && chroma <= DELTA_MAX
                && bright >= DELTA_MIN && bright <= DELTA_MAX;
    }

    /** Wire/DB value of absolute hue degrees 0..359. */
    public static short encodeHue(int degrees) {
        return (short) -(Math.max(0, Math.min(359, degrees)) + 1);
    }
}
