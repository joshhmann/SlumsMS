package soloMapling.Economy.market;

import java.util.function.IntFunction;

/** Item names for the export: the WZ name, or "" when the item has none (never throws). */
final class MarketNames {

    private MarketNames() {
    }

    static String of(IntFunction<String> names, int itemId) {
        try {
            String n = names == null ? null : names.apply(itemId);
            return n == null ? "" : n;
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** The server's own item names. */
    static String wz(int itemId) {
        return server.ItemInformationProvider.getInstance().getName(itemId);
    }
}
