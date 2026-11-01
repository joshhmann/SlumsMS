package soloMapling.ArtificialPlayer;

import java.sql.Connection;
import java.util.function.Supplier;

/** Lets tests outside the package point the roster at a mock pool (null restores the real one, and forgets the load). */
public final class BotRosterTestAccess {

    private BotRosterTestAccess() {
    }

    public static void use(Supplier<Connection> source) {
        BotRoster.useConnectionSourceForTest(source == null ? null : source::get);
    }

    public static void forget() {
        BotRoster.useConnectionSourceForTest(null);
    }
}
