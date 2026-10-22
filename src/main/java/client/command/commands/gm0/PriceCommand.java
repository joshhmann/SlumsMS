package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.ItemInformationProvider;
import soloMapling.Economy.PriceBook;
import tools.Pair;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1.5.0 rc3, owner decision 5: {@code @price <item>} - the price board. The recent median of real trades (player and
 * bot shop sales, trade-window sales), its range and the sample count, over the last 7 days.
 */
public class PriceCommand extends Command {
    {
        setDescription("Price board: @price <item name or id> - recent median, range and number of sales.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            player.yellowMessage("Usage: @price <item name or id>, e.g. @price work gloves");
            return;
        }
        List<Pair<Integer, String>> found = find(String.join(" ", params));
        if (found.isEmpty()) {
            player.yellowMessage("No item called '" + String.join(" ", params) + "'.");
            return;
        }
        for (Pair<Integer, String> p : found.subList(0, Math.min(3, found.size()))) {
            long fallback = staticValue(p.getLeft());
            player.yellowMessage(PriceBook.quote(p.getLeft(), fallback).describe(p.getRight()));
        }
        if (found.size() > 3) {
            player.yellowMessage("(" + (found.size() - 3) + " more items match; be more specific)");
        }
    }

    /** Items whose id is the text, or whose name is it exactly, or contains it (exact names first). */
    static List<Pair<Integer, String>> find(String text) {
        String q = text.trim().toLowerCase(Locale.ROOT);
        List<Pair<Integer, String>> exact = new ArrayList<>();
        List<Pair<Integer, String>> partial = new ArrayList<>();
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (q.matches("\\d{7}")) {
            String name = ii.getName(Integer.parseInt(q));
            if (name != null) {
                exact.add(new Pair<>(Integer.parseInt(q), name));
            }
            return exact;
        }
        for (Pair<Integer, String> item : ii.getAllItems()) {
            String n = item.getRight() == null ? "" : item.getRight().toLowerCase(Locale.ROOT);
            if (n.equals(q)) {
                exact.add(item);
            } else if (n.contains(q) && partial.size() < 50) {
                partial.add(item);
            }
        }
        exact.addAll(partial);
        return exact;
    }

    private static long staticValue(int itemId) {
        try {
            Integer v = soloMapling.itemPool.ItemDatabase.getInstance().getItemPrice(itemId);
            return v == null ? 0 : v;
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }
}
