package soloMapling.ArtificialPlayer;

import java.util.function.IntFunction;

/** Lets tests outside the package give {@link BotBuddies} a fake bot-name lookup (null restores the real one). */
public final class BotBuddiesTestAccess {

    private BotBuddiesTestAccess() {
    }

    public static void names(IntFunction<String> names) {
        BotBuddies.useForTest(null, names, null, null, null);
    }
}
