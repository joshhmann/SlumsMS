package soloMapling.ArtificialPlayer;

/**
 * Ticket 161 (1.6.2): the switches of bot build variety and party support, all OFF unless the server is started with
 * the system property set to {@code true}. Read on every call so a test can flip one.
 *
 * <ul>
 *   <li>{@code slums.bots.apvariants} - new and reborn bots may roll a dexless/lukless/strless/pure/"25" AP build
 *       ({@code slums.bots.apvariants.fromSlot}: the first roster slot that rolls one; older slots keep the standard
 *       build, so nothing is migrated).</li>
 *   <li>{@code slums.bots.partysupport} - bots heal their party and drink buff pots while partied with a real player.</li>
 *   <li>{@code slums.bots.famerequest} - a friendly bot may fame a player who asks.</li>
 *   <li>{@code slums.bots.magicguard} - a bot with Magic Guard up splits the damage it takes between HP and MP.</li>
 * </ul>
 */
public final class BotSupportSwitches {

    private BotSupportSwitches() {
    }

    public static boolean apVariants() {
        return Boolean.getBoolean("slums.bots.apvariants");
    }

    public static boolean partySupport() {
        return Boolean.getBoolean("slums.bots.partysupport");
    }

    public static boolean fameRequest() {
        return Boolean.getBoolean("slums.bots.famerequest");
    }

    public static boolean magicGuard() {
        return Boolean.getBoolean("slums.bots.magicguard");
    }

    /** The first roster slot that rolls an AP variant; unset = none (only reborn bots roll one). */
    public static int apVariantFromSlot() {
        return Integer.getInteger("slums.bots.apvariants.fromSlot", Integer.MAX_VALUE);
    }
}
