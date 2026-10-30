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

    /**
     * The first roster slot that rolls an AP variant; unset = none (only reborn bots roll one). Owner 2026-10-30:
     * {@code auto} = the first roster slot created after this boot (every slot above the highest the roster held when
     * it loaded), so new bots roll variants and every existing bot keeps its build, with no number to look up in the
     * live database. Until the roster has loaded, auto lets no slot roll. A number that does not parse counts as unset.
     */
    public static int apVariantFromSlot() {
        String v = System.getProperty("slums.bots.apvariants.fromSlot");
        if (v == null || v.isBlank()) {
            return Integer.MAX_VALUE;
        }
        v = v.trim();
        if ("auto".equalsIgnoreCase(v)) {
            int highest = BotRoster.bootHighestSlot();
            return highest < 0 ? Integer.MAX_VALUE : highest + 1;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
