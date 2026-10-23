package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import soloMapling.autoqa.AutoQaCatalog;
import soloMapling.autoqa.AutoQaService;
import soloMapling.autoqa.Check;
import soloMapling.autoqa.HealthSweeper;

import java.util.List;
import java.util.Map;

/*
 * Test server only (registered when AutoQa.isEnabled(): -Dslums.autoqa=true or AUTOQA_ENABLED):
 *   !autoqa run <check-id|group|all> | !autoqa status | !autoqa stop | !autoqa report | !autoqa health | !autoqa list
 * Runs release-notes checks with headless synthetic players and writes data/autoqa/ (docs/ops/qa-suite.md "Auto-QA").
 */
public class AutoQaCommand extends Command {
    {
        setDescription("Test server: run release checks with synthetic players, health sweeps (!autoqa report).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String sub = params.length == 0 ? "help" : params[0];
        AutoQaService svc = AutoQaService.get();
        switch (sub) {
            case "run" -> {
                if (params.length < 2) {
                    gm.yellowMessage("Syntax: !autoqa run <t-af-1 | af-1 | af | bg-1,vg-1,ch-1 | smoke | all>");
                    return;
                }
                int gmId = gm.getId();
                int world = gm.getWorld();
                show(gm, svc.start(params[1], gm.getName(), false, line -> {
                    Character now = Server.getInstance().getWorld(world).getPlayerStorage().getCharacterById(gmId);
                    if (now != null) {
                        now.yellowMessage(line);
                    }
                }));
            }
            case "status" -> show(gm, svc.status());
            case "stop" -> show(gm, svc.stop());
            case "report" -> show(gm, svc.summary());
            case "health" -> {
                Map<String, Object> r = HealthSweeper.get().sweepNow();
                gm.yellowMessage("Health " + r.get("status") + ": " + r.get("summary") + " (" + r.get("botsSampled")
                        + " bots, " + r.get("durationMs") + " ms). data/autoqa/health-latest.json");
            }
            case "list" -> {
                int auto = 0, half = 0, manual = 0;
                for (Check ch : AutoQaCatalog.all()) {
                    switch (ch.kind()) {
                        case AUTO -> auto++;
                        case HALF -> half++;
                        case MANUAL -> manual++;
                    }
                }
                gm.yellowMessage(auto + " automated, " + half + " half (logic only), " + manual + " manual checks:");
                StringBuilder line = new StringBuilder();
                for (Check ch : AutoQaCatalog.all()) {
                    String tag = ch.id() + (ch.kind() == Check.Kind.AUTO ? "" : ch.kind() == Check.Kind.HALF ? "(half)" : "(manual)")
                            + (ch.nightly() ? "" : "*");
                    if (line.length() + tag.length() > 90) {
                        gm.yellowMessage(line.toString());
                        line.setLength(0);
                    }
                    line.append(line.length() == 0 ? "" : " ").append(tag);
                }
                if (line.length() > 0) {
                    gm.yellowMessage(line.toString());
                }
                gm.yellowMessage("* = not in the nightly run (leaves a lasting change).");
            }
            default -> gm.yellowMessage("!autoqa run <check|a,b,c|smoke|all> | status | stop | report | health | list (!qa: menu)");
        }
    }

    private static void show(Character gm, List<String> lines) {
        for (String l : lines) {
            gm.yellowMessage(l);
        }
    }
}
