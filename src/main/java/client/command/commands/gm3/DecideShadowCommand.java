package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import soloMapling.ArtificialPlayer.BotConvo.DecideShadow;
import soloMapling.ArtificialPlayer.BotConvo.DecideShadowReport;
import soloMapling.autoqa.AutoQa;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/*
 * 1.6.0 chat-model shadow run (owner 2026-10-27), test server only (registered with auto-QA or -Dslums.decide.shadow):
 *   !decideshadow status [yyyy-mm-dd | all]      per model: agreement with the current model, p50/p95, errors
 *   !decideshadow disagree [model] [n]           the latest n lines (default 10) a model reads differently
 * Reads data/autoqa/decide-shadow/ off the game thread; today (UTC) by default.
 */
public class DecideShadowCommand extends Command {
    {
        setDescription("Test server: the chat-model shadow run's scorecard (!decideshadow status).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String sub = params.length == 0 ? "status" : params[0];
        int gmId = gm.getId(), world = gm.getWorld();
        Path dir = DecideShadow.dir(AutoQa.dataDir());
        switch (sub) {
            case "status" -> {
                String day = params.length > 1 ? params[1] : null;
                Thread.ofVirtual().start(() -> tell(world, gmId, status(dir, day)));
            }
            case "disagree" -> {
                String model = params.length > 1 && !params[1].matches("\\d+") ? params[1] : null;
                int n = 10;
                try {
                    n = Integer.parseInt(params[params.length - 1]);
                } catch (NumberFormatException ignored) {
                    // default
                }
                int max = Math.max(1, Math.min(30, n));
                Thread.ofVirtual().start(() -> tell(world, gmId, disagree(dir, model, max)));
            }
            default -> gm.yellowMessage("!decideshadow [status [yyyy-mm-dd|all] | disagree [model] [n]]");
        }
    }

    static List<String> status(Path dir, String day) {
        List<String> out = new ArrayList<>();
        DecideShadow s = DecideShadow.get();
        out.add(s == null ? "Shadow is off (-D" + DecideShadow.PROPERTY + "=gliclass,onejev turns it on)."
                : "Shadow on: " + s.counters());
        try {
            List<Map<String, Object>> rows = DecideShadowReport.load(List.of(pick(dir, day)));
            out.addAll(DecideShadowReport.lines(rows, 0));
        } catch (Exception e) {
            out.add("Could not read " + dir + ": " + e.getMessage());
        }
        return out;
    }

    static List<String> disagree(Path dir, String model, int n) {
        List<String> out = new ArrayList<>();
        try {
            var all = DecideShadowReport.disagreements(DecideShadowReport.load(List.of(pick(dir, null))), model);
            out.add(all.size() + " disagreements today" + (model == null ? "" : " for " + model) + ", latest " + Math.min(n, all.size()) + ":");
            for (var d : all.subList(Math.max(0, all.size() - n), all.size())) {
                out.add(d.line());
            }
        } catch (Exception e) {
            out.add("Could not read " + dir + ": " + e.getMessage());
        }
        return out;
    }

    /** Today's file, a named day's, or the whole folder ("all"). */
    private static Path pick(Path dir, String day) {
        if ("all".equalsIgnoreCase(day)) {
            return dir;
        }
        return day != null && day.matches("\\d{4}-\\d{2}-\\d{2}") ? dir.resolve(day + ".jsonl")
                : DecideShadow.file(AutoQa.dataDir(), System.currentTimeMillis());
    }

    private static void tell(int world, int gmId, List<String> lines) {
        Character gm = Server.getInstance().getWorld(world).getPlayerStorage().getCharacterById(gmId);
        if (gm != null) {
            lines.forEach(gm::yellowMessage);
        }
    }
}
