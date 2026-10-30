package soloMapling.ArtificialPlayer.BotProgressionSystem;

/** Test access to BotProgression's package-private AP-variant rule from other test packages. */
public final class BotProgressionAccess {
    private BotProgressionAccess() {
    }

    public static boolean variantsFor(int botId, int fromSlot) {
        return BotProgression.variantsFor(true, botId, 0, fromSlot);
    }
}
