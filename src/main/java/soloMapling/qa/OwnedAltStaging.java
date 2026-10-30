package soloMapling.qa;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code !qa do alts} (oa-2): makes the GM's account up to two extra characters through the real create path, gives them
 * starter supplies, and remembers what it made so {@code !qa do alts remove} deletes only those. {@code !qa stop} leaves
 * them alone (the tester calls them afterwards). TEST/QA only: the step exists only inside a running !qa scenario.
 */
final class OwnedAltStaging {
    static final int LEVEL = 30;
    static final Path DEFAULT_MARKER = Path.of("logs", "qa", "qa-alts.tsv");

    /** What each staged alt gets: item id, quantity, display name. */
    enum Role {
        ALT1("Alt1", new int[][]{{2000000, 100}, {2000003, 50}, {1002002, 1}, {4000000, 20}, {1302007, 1}},
                "100 Red Potion, 50 Blue Potion, a spare hat (1002002), 20 ETC (4000000), a sword (1302007)"),
        ALT2("Alt2", new int[][]{{2000000, 5}}, "5 Red Potion");
        final String suffix;
        final int[][] items;
        final String text;
        Role(String suffix, int[][] items, String text) { this.suffix = suffix; this.items = items; this.text = text; }
    }

    /** One alt this feature made. */
    record Made(int accountId, int charId, String name, Role role) {}

    /** The server-side actions, so the rules can be tested without a database. */
    interface Host {
        int accountId();
        String gmName();
        /** Characters other than the GM on the GM's account and world. */
        int otherCharacters();
        /** 0 on success; -1 = name refused/taken; anything else = cannot create at all (e.g. no free slot). */
        int create(String name);
        int idByName(String name);
        /** Level and items brought up to the role's amounts; returns a text of what it now holds. */
        String supply(int charId, Role role);
        boolean online(int charId);
        boolean delete(int charId);
    }

    private final Path marker;

    OwnedAltStaging(Path marker) { this.marker = marker; }

    /** The names to try, in order: prefix (GM name's first 8 letters/digits) + AltN, then AltN + a digit; always 3-12 chars. */
    static List<String> candidates(String gmName, Role role) {
        StringBuilder p = new StringBuilder();
        for (char ch : gmName.toCharArray()) {
            if (java.lang.Character.isLetterOrDigit(ch) && ch < 128 && p.length() < 8) p.append(ch);
        }
        List<String> out = new ArrayList<>();
        out.add(fit(p.toString(), role.suffix));
        for (int d = 1; d <= 9; d++) out.add(fit(p.toString(), role.suffix + d));
        return out;
    }

    private static String fit(String prefix, String suffix) {
        int room = 12 - suffix.length();
        return (prefix.length() > room ? prefix.substring(0, room) : prefix) + suffix;
    }

    List<String> make(Host host) {
        List<Made> mine = read(host.accountId());
        List<String> out = new ArrayList<>();
        int missing = Math.max(0, 2 - host.otherCharacters());
        for (Role role : Role.values()) {
            if (missing == 0) break;
            if (mine.stream().anyMatch(m -> m.role() == role)) continue; // already made (it counts in otherCharacters)
            Made made = null;
            int status = -1;
            for (String name : candidates(host.gmName(), role)) {
                status = host.create(name);
                if (status == 0) {
                    made = new Made(host.accountId(), host.idByName(name), name, role);
                    break;
                }
                if (status != -1) break;
            }
            if (made == null) {
                out.add("QA ALTS could not create " + role.suffix + " (status " + status + (status == -3 ? ": no free character slot" : "") + ")");
                continue;
            }
            append(made);
            mine.add(made);
            missing--;
        }
        for (Made m : mine) {
            if (host.idByName(m.name()) != m.charId()) continue;
            if (host.online(m.charId())) {
                out.add("QA ALTS " + m.name() + " is called right now: @dismiss it, then run !qa do alts again to top it up.");
                continue;
            }
            out.add("QA ALTS made " + m.name() + " (" + m.role().suffix + "): " + host.supply(m.charId(), m.role()));
        }
        if (mine.isEmpty()) {
            out.add("QA ALTS this account already has " + host.otherCharacters()
                    + " other characters; none were made by this step, so nothing was changed.");
        }
        return out;
    }

    List<String> remove(Host host) {
        List<String> out = new ArrayList<>();
        List<Made> all = readAll();
        List<Made> keep = new ArrayList<>();
        for (Made m : all) {
            if (m.accountId() != host.accountId()) { keep.add(m); continue; }
            if (host.idByName(m.name()) != m.charId()) { out.add("QA ALTS " + m.name() + " is already gone."); continue; }
            if (host.online(m.charId())) { out.add("QA ALTS " + m.name() + " is called: @dismiss it first."); keep.add(m); continue; }
            if (host.delete(m.charId())) out.add("QA ALTS removed " + m.name());
            else { out.add("QA ALTS could not delete " + m.name() + " (guild leader or married?)."); keep.add(m); }
        }
        write(keep);
        if (out.isEmpty()) out.add("QA ALTS nothing to remove: this step has made no characters on this account.");
        return out;
    }

    // ── marker file: accountId \t charId \t name \t role ──

    private List<Made> read(int accountId) {
        List<Made> out = new ArrayList<>();
        for (Made m : readAll()) if (m.accountId() == accountId) out.add(m);
        return out;
    }

    private List<Made> readAll() {
        List<Made> out = new ArrayList<>();
        try {
            if (!Files.exists(marker)) return out;
            for (String line : Files.readAllLines(marker, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t");
                if (f.length != 4) continue;
                try { out.add(new Made(Integer.parseInt(f[0]), Integer.parseInt(f[1]), f[2], Role.valueOf(f[3]))); }
                catch (IllegalArgumentException ignored) { }
            }
        } catch (IOException e) { throw new QaRefusal("Cannot read " + marker + ": " + e.getMessage()); }
        return out;
    }

    private void append(Made m) {
        try {
            if (marker.getParent() != null) Files.createDirectories(marker.getParent());
            Files.writeString(marker, line(m) + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) { throw new QaRefusal("Cannot write " + marker + ": " + e.getMessage()); }
    }

    private void write(List<Made> all) {
        StringBuilder sb = new StringBuilder();
        for (Made m : all) sb.append(line(m)).append('\n');
        try { Files.writeString(marker, sb.toString(), StandardCharsets.UTF_8); }
        catch (IOException e) { throw new QaRefusal("Cannot write " + marker + ": " + e.getMessage()); }
    }

    private static String line(Made m) { return m.accountId() + "\t" + m.charId() + "\t" + m.name() + "\t" + m.role().name(); }

    // ── the real server ──

    static Host liveHost(Character gm) {
        return new Host() {
            final Client c = gm.getClient();
            public int accountId() { return gm.getAccountID(); }
            public String gmName() { return gm.getName(); }
            public int otherCharacters() {
                try (java.sql.Connection con = tools.DatabaseConnection.getConnection();
                     java.sql.PreparedStatement ps = con.prepareStatement(
                             "SELECT COUNT(*) FROM characters WHERE accountid = ? AND world = ? AND id <> ?")) {
                    ps.setInt(1, gm.getAccountID());
                    ps.setInt(2, gm.getWorld());
                    ps.setInt(3, gm.getId());
                    try (java.sql.ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
                } catch (java.sql.SQLException e) { throw new QaRefusal("Cannot count your characters: " + e.getMessage()); }
            }
            public int create(String name) {
                // the server's own create path (slot check, name rules, starter gear), never raw INSERTs
                return client.creator.novice.BeginnerCreator.createCharacter(c, name, 20000, 30000, 0, 0, 0, 0, 0, 0);
            }
            public int idByName(String name) { return Character.getIdByName(name); }
            public boolean online(int charId) {
                return net.server.Server.getInstance().getWorlds().stream()
                        .anyMatch(w -> w.getPlayerStorage().getCharacterById(charId) != null);
            }
            public boolean delete(int charId) { return c.deleteCharacter(charId, gm.getAccountID()); }
            public String supply(int charId, Role role) {
                BotClient bc = new BotClient(gm.getWorld(), c.getChannel());
                bc.setAccID(gm.getAccountID());
                bc.setAccountName(c.getAccountName());
                try {
                    Character alt = Character.loadCharFromDB(charId, bc, true);
                    bc.setPlayer(alt);
                    alt.setParty(null);
                    alt.setMPC(null);
                    StringBuilder what = new StringBuilder();
                    if (alt.getLevel() < LEVEL) {
                        alt.setExp(0);
                        alt.setLevel(LEVEL - 1);
                        try { alt.levelUp(false); } catch (RuntimeException e) { alt.setLevel(LEVEL); }
                    }
                    what.append("level ").append(alt.getLevel());
                    for (int[] it : role.items) {
                        int have = alt.getInventory(constants.inventory.ItemConstants.getInventoryType(it[0])).countById(it[0]);
                        int add = it[1] - have;
                        if (add > 0) InventoryManipulator.addById(bc, it[0], (short) add, "qa", -1);
                        what.append(", ").append(it[1]).append(" x ")
                                .append(server.ItemInformationProvider.getInstance().getName(it[0]));
                    }
                    alt.saveCharToDB(true);
                    return what.toString();
                } catch (java.sql.SQLException e) { throw new QaRefusal("Cannot load " + charId + ": " + e.getMessage()); }
            }
        };
    }
}
