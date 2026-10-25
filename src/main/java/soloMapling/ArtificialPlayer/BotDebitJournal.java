package soloMapling.ArtificialPlayer;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ticket 63: a durable note of the debits a bot still owed its stored row when the shutdown save budget ran out.
 *
 * <p>The row a bot comes back from still claims value it has already paid to someone else, and the recipient's
 * save keeps the payment, so restoring it mints the value twice. The budgeted shutdown sweep cannot always write
 * every bot's row in time, so for each bot it did not finish (not started, still in flight, or whose write failed)
 * and that still has an unpersisted debit it writes one line here instead: a local file write of a few hundred
 * bytes, no database, outside the budget's concern.
 *
 * <p>The line stores <em>absolute caps</em>, not deltas: the meso the bot held, and the count it held of each item
 * it holds less of. On the next boot {@link #applyTo(Character)} lowers the restored bot to those caps. A cap is
 * idempotent: if the "unsaved" write did land after all (it was in flight), the bot is already at or under the cap
 * and nothing happens, so the debit can never be taken twice. The entry is dropped once a row write for the bot
 * commits ({@link #committed(int)}), because a stale cap would later eat honest earnings. If the file cannot be
 * written the sweep says so loudly; that is the one remaining failure (disk full or unwritable).
 */
public final class BotDebitJournal {
    private static final Logger log = LoggerFactory.getLogger(BotDebitJournal.class);

    /** One bot's caps: meso (null = no cap) and item id to count held. */
    record Entry(Long meso, Map<Integer, Integer> items) {
    }

    private static volatile Path file;
    private static Map<Integer, Entry> loaded;   // guarded by BotDebitJournal.class

    private BotDebitJournal() {
    }

    static Path path() {
        Path f = file;
        return f != null ? f : Path.of(System.getProperty("solomapling.botDebitJournal", "data/bot-debit-journal.tsv"));
    }

    /** Test seam: a path to use, or null for the default. Forgets what was loaded. */
    static synchronized void useFileForTest(Path p) {
        file = p;
        loaded = null;
    }

    /** Whether this bot's held value is below what its stored row claims, so a shutdown must note it. */
    static boolean owes(Character bot) {
        return BotHoldings.hasUnpersistedDebit(bot);
    }

    /**
     * Notes the debits of these bots (those that still have one) and writes the file. Entries already in the file
     * for other bots are kept.
     *
     * @return how many bots were noted, or -1 when the file could not be written
     */
    static synchronized int record(List<Character> bots) {
        Map<Integer, Entry> all = new TreeMap<>(entries());
        int noted = 0;
        for (Character bot : bots) {
            int id = BotStore.botId(bot);
            if (id < 1 || !owes(bot)) {
                continue;
            }
            Map<Integer, Integer> items = new LinkedHashMap<>();
            for (int itemId : BotHoldings.debitedItemIds(bot)) {
                items.put(itemId, bot.getItemQuantity(itemId, true));
            }
            Long meso = BotHoldings.hasUnpersistedMesoDebit(bot) ? Long.valueOf(bot.getMeso()) : null;
            all.put(id, new Entry(meso, items));
            noted++;
        }
        if (noted == 0) {
            return 0;
        }
        try {
            write(all);
            loaded = all;
            return noted;
        } catch (IOException | RuntimeException e) {
            log.error("Shutdown: could not write the bot debit journal {}; the bots that did not save keep their"
                    + " old rows and may regain value they paid away", path(), e);
            return -1;
        }
    }

    /** Lowers a freshly restored bot to the caps noted for it at the last shutdown. Call after its holdings load. */
    static synchronized void applyTo(Character bot) {
        Entry entry = entries().get(BotStore.botId(bot));
        if (entry == null) {
            return;
        }
        int removed = 0;
        long spentMeso = 0;
        if (entry.meso() != null && bot.getMeso() > entry.meso()) {
            int over = (int) Math.min(Integer.MAX_VALUE, bot.getMeso() - entry.meso());
            if (BotHoldings.spendMeso(bot, over)) {
                spentMeso = over;
            }
        }
        for (Map.Entry<Integer, Integer> cap : entry.items().entrySet()) {
            removed += trimTo(bot, cap.getKey(), cap.getValue());
        }
        if (spentMeso > 0 || removed > 0) {
            log.warn("Bot {} ({}) came back with value it paid away before the last shutdown's save landed:"
                    + " took {} mesos and {} items back off it", bot.getId(), bot.getName(), spentMeso, removed);
        }
    }

    private static int trimTo(Character bot, int itemId, int cap) {
        int excess = bot.getItemQuantity(itemId, true) - cap;
        int removed = 0;
        List<InventoryType> types = List.of(ItemConstants.getInventoryType(itemId), InventoryType.EQUIPPED);
        for (InventoryType type : types) {
            Inventory inv = bot.getInventory(type);
            if (inv == null) {
                continue;
            }
            for (Item item : new ArrayList<>(inv.list())) {
                if (excess <= 0) {
                    return removed;
                }
                if (item.getItemId() != itemId) {
                    continue;
                }
                if (type == InventoryType.EQUIPPED) {
                    if (BotHoldings.destroyHeldItem(bot, item)) {
                        excess -= 1;
                        removed += 1;
                    }
                } else {
                    int n = BotHoldings.consumeHeld(bot, item, Math.min(excess, item.getQuantity()));
                    excess -= n;
                    removed += n;
                }
            }
        }
        return removed;
    }

    /** A row write for this bot has committed, so its note (if any) is no longer needed. */
    static synchronized void committed(int botId) {
        Map<Integer, Entry> all = entries();
        if (all.isEmpty() || !all.containsKey(botId)) {
            return;
        }
        Map<Integer, Entry> rest = new TreeMap<>(all);
        rest.remove(botId);
        try {
            write(rest);
            loaded = rest;
        } catch (IOException | RuntimeException e) {
            log.warn("Could not update the bot debit journal after bot {} saved", botId, e);
        }
    }

    private static Map<Integer, Entry> entries() {
        if (loaded != null) {
            return loaded;
        }
        Map<Integer, Entry> out = new TreeMap<>();
        Path f = path();
        if (Files.isRegularFile(f)) {
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] c = line.split("\t", -1);
                    if (c.length < 3 || line.startsWith("#")) {
                        continue;
                    }
                    Map<Integer, Integer> items = new LinkedHashMap<>();
                    if (!c[2].isEmpty()) {
                        for (String kv : c[2].split(",")) {
                            String[] p = kv.split(":");
                            items.put(Integer.parseInt(p[0]), Integer.parseInt(p[1]));
                        }
                    }
                    out.put(Integer.parseInt(c[0]), new Entry(c[1].isEmpty() ? null : Long.parseLong(c[1]), items));
                }
            } catch (IOException | RuntimeException e) {
                log.error("Could not read the bot debit journal {}; its notes are ignored", f, e);
            }
        }
        loaded = out;
        return out;
    }

    private static void write(Map<Integer, Entry> all) throws IOException {
        Path f = path();
        Path parent = f.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (all.isEmpty()) {
            Files.deleteIfExists(f);
            return;
        }
        StringBuilder sb = new StringBuilder("# botId\tmesoCap\titemId:countCap,...\n");
        for (Map.Entry<Integer, Entry> e : all.entrySet()) {
            sb.append(e.getKey()).append('\t').append(e.getValue().meso() == null ? "" : e.getValue().meso()).append('\t');
            boolean first = true;
            for (Map.Entry<Integer, Integer> it : e.getValue().items().entrySet()) {
                sb.append(first ? "" : ",").append(it.getKey()).append(':').append(it.getValue());
                first = false;
            }
            sb.append('\n');
        }
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
