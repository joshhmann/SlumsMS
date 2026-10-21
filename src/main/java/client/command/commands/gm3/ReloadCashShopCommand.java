package client.command.commands.gm3;

import client.Client;
import client.command.Command;
import server.cashshop.CashShopCatalog;

/**
 * {@code !reloadcashshop}: re-reads {@code cashshop/catalog.tsv} without a restart.
 *
 * <p>The catalogue swaps in as one immutable snapshot, so a purchase in flight finishes against
 * the set it started with. A missing or unreadable file keeps the current catalogue. Windows
 * already open keep the category list they were sent until they are reopened; buying a row that
 * the reload removed is refused as an unknown item. Ran from the GM audit like every GM command.
 */
public class ReloadCashShopCommand extends Command {
    {
        setDescription("Reload the Cash Shop catalogue (cashshop/catalog.tsv).");
    }

    @Override
    public void execute(Client c, String[] params) {
        final int before = CashShopCatalog.size();
        final CashShopCatalog.LoadStats s = CashShopCatalog.load();
        if (!s.loaded()) {
            c.getPlayer().yellowMessage("Cash Shop: " + CashShopCatalog.file()
                    + " could not be read; still selling the " + before + " rows loaded before.");
            return;
        }
        c.getPlayer().yellowMessage("Cash Shop reloaded: " + s.rows() + " rows in " + s.categories()
                + " categories (was " + before + ")."
                + (s.rejected() > 0 ? " Rejected " + s.rejected() + " lines (" + s.unusable() + " unusable, "
                + s.duplicates() + " duplicate, " + s.malformed() + " malformed) -- see the server log." : ""));
    }
}
