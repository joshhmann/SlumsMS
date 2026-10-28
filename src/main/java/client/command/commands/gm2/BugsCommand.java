package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import server.reports.ReportStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ticket 131: {@code !bugs [n]} lists the newest in-game reports (@bug and @idea, default 10, at most 30) with their
 * refs; {@code !bug <ref>} (the @bug command, for GMs) shows one report's summary and the key parts of its context.
 */
public class BugsCommand extends Command {
    {
        setDescription("Newest in-game bug reports and ideas: !bugs [n]; one report: !bug B-12.");
    }

    public static final int DEFAULT = 10;
    public static final int MAX = 30;

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        int n = DEFAULT;
        if (params.length >= 1) {
            try {
                n = Math.max(1, Math.min(MAX, Integer.parseInt(params[0])));
            } catch (NumberFormatException e) {
                gm.yellowMessage("Usage: !bugs [how many, up to " + MAX + "]   one report: !bug B-12");
                return;
            }
        }
        for (String line : list(ReportStore.getInstance(), n)) {
            gm.yellowMessage(line);
        }
    }

    public static List<String> list(ReportStore store, int n) {
        List<Map<String, Object>> rows = store.recentInGame(n);
        List<String> out = new ArrayList<>();
        if (rows.isEmpty()) {
            out.add("No in-game reports yet.");
            return out;
        }
        out.add("Newest " + rows.size() + " in-game reports (!bug <ref> for one):");
        for (Map<String, Object> r : rows) {
            out.add(str(r.get("ref"), "#" + str(r.get("id"), "?")) + " " + str(r.get("time"), "").replace("+00:00", "Z")
                    + " " + str(r.get("character"), "?") + " @ " + str(r.get("mapName"), str(r.get("where"), "?"))
                    + ": " + str(r.get("title"), ""));
        }
        return out;
    }

    /** The lines !bug B-12 shows: the report and the highlights of its context file. */
    public static List<String> describe(ReportStore store, String ref) {
        List<String> out = new ArrayList<>();
        Map<String, Object> r = store.find(ref);
        if (r == null) {
            out.add("No in-game report " + ReportStore.normalizeRef(ref) + ".");
            return out;
        }
        out.add(str(r.get("ref"), "?") + " (" + str(r.get("type"), "?") + ") " + str(r.get("time"), "") + " by "
                + str(r.get("character"), "?") + " at " + str(r.get("where"), "?"));
        out.add("Says: " + str(r.get("details"), ""));
        Map<String, Object> ctx = store.readContext(ref);
        if (ctx == null) {
            out.add("No context file.");
            return out;
        }
        if (ctx.get("versions") instanceof Map<?, ?> v) {
            String client = v.get("client") instanceof Map<?, ?> cl
                    ? str(cl.get("build"), "?") + " " + str(cl.get("release"), "") + " caps " + str(cl.get("caps"), "0") : "?";
            out.add("Server " + str(v.get("server"), "?") + " " + str(v.get("commit"), "") + ", client " + client);
        }
        if (ctx.get("character") instanceof Map<?, ?> ch) {
            String job = ch.get("job") instanceof Map<?, ?> j ? str(j.get("name"), "?") : "?";
            out.add("Lv " + str(ch.get("level"), "?") + " " + job + ", HP " + str(ch.get("hp"), "?") + "/"
                    + str(ch.get("maxHp"), "?") + ", MP " + str(ch.get("mp"), "?") + "/" + str(ch.get("maxMp"), "?")
                    + ", at " + str(ch.get("x"), "?") + "," + str(ch.get("y"), "?") + " fh " + str(ch.get("foothold"), "?"));
        }
        if (ctx.get("trail") instanceof Map<?, ?> t) {
            out.add("Last maps: " + names(t.get("maps"), 5) + "; last NPCs: " + names(t.get("npcs"), 3));
            if (t.get("events") instanceof List<?> ev) {
                out.add("Events: " + ev.size() + " (last: " + lastEvents(ev, 5) + ")");
            }
        }
        if (ctx.get("serverLog") instanceof List<?> logs) {
            out.add("Server WARN/ERROR lines about them: " + logs.size()
                    + (logs.isEmpty() ? "" : " (last: " + str(logs.getLast(), "") + ")"));
        }
        if (ctx.get("clientLogs") instanceof Map<?, ?> cl) {
            out.add("Client logs: " + str(cl.get("bytes"), "0") + " bytes" + (Boolean.TRUE.equals(cl.get("truncated")) ? ", cut" : ""));
        }
        if (ctx.get("truncated") instanceof List<?> cut) {
            out.add("Context was cut to fit: " + cut);
        }
        out.add("Full context: " + store.contextDir().resolve(str(r.get("ref"), "?") + ".json"));
        return out;
    }

    private static String names(Object rows, int max) {
        if (!(rows instanceof List<?> l) || l.isEmpty()) {
            return "none";
        }
        List<String> out = new ArrayList<>();
        for (int i = l.size() - 1; i >= 0 && out.size() < max; i--) {
            if (l.get(i) instanceof Map<?, ?> m) {
                out.add(str(m.get("name"), str(m.get("id"), "?")));
            }
        }
        return String.join(" <- ", out);
    }

    private static String lastEvents(List<?> ev, int max) {
        List<String> out = new ArrayList<>();
        for (int i = ev.size() - 1; i >= 0 && out.size() < max; i--) {
            if (ev.get(i) instanceof Map<?, ?> m) {
                out.add(str(m.get("kind"), "?") + " " + str(m.get("name"), str(m.get("id"), ""))
                        + (m.get("count") != null ? " x" + m.get("count") : ""));
            }
        }
        return String.join(", ", out);
    }

    private static String str(Object o, String dflt) {
        return o == null ? dflt : String.valueOf(o);
    }
}
