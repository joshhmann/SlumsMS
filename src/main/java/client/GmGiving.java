package client;

/**
 * GM characters do not hand things to players (ticket 43): no trades between a GM and a non-GM, and no item or meso
 * drops by a GM, unless the server runs with {@code -Dslumsms.gm.allowGiving=true}. This sits on top of the stock
 * {@code MINIMUM_GM_LEVEL_TO_TRADE/DROP} config keys, which let high GM levels through; this rule covers every GM
 * level (a character is a GM at level 2 and up, {@link Character#isGM()}).
 */
public final class GmGiving {

    public static final String ALLOW_PROPERTY = "slumsms.gm.allowGiving";

    private GmGiving() {
    }

    public static boolean allowed() {
        return Boolean.getBoolean(ALLOW_PROPERTY);
    }

    /** True when this trade must not happen: exactly one side is a GM and giving is not allowed. */
    public static boolean blocksTrade(boolean firstIsGm, boolean secondIsGm) {
        return firstIsGm != secondIsGm && !allowed();
    }

    /** True when a GM may not drop items or mesos. */
    public static boolean blocksDrop(boolean isGm) {
        return isGm && !allowed();
    }
}
