package soloMapling.ArtificialPlayer;

/** Lets tests in other packages reset BotWealth's pace and ledger. */
public final class BotWealthTestAccess {
    private BotWealthTestAccess() {
    }

    public static void reset() {
        BotWealth.resetForTest(() -> 0L);
    }
}
