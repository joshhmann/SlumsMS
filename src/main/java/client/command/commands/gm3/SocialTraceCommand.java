package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.autoqa.SocialTrace;

import java.util.List;

/*
 * 1.6.0 (owner 2026-10-26), test server (registered with auto-QA or -Dslums.socialtrace):
 *   !socialtrace start [hours] | status | stop
 * Follows every bot<->bot thread and drama event through trigger, compose, deliver, sense, decide, act, remember and
 * spread, with pretend observers standing in town; writes data/autoqa/social-trace/<date>.jsonl and
 * data/autoqa/social-trace-latest.json.
 */
public class SocialTraceCommand extends Command {
    {
        setDescription("Test server: trace bot<->bot social life (threads, drama) stage by stage.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String sub = params.length == 0 ? "help" : params[0];
        switch (sub) {
            case "start" -> {
                double hours = SocialTrace.DEFAULT_HOURS;
                if (params.length > 1) {
                    try {
                        hours = Double.parseDouble(params[1]);
                    } catch (NumberFormatException e) {
                        gm.yellowMessage("Syntax: !socialtrace start [hours] (default " + SocialTrace.DEFAULT_HOURS + ")");
                        return;
                    }
                    if (hours <= 0 || hours > 72) {
                        gm.yellowMessage("Hours must be above 0 and at most 72.");
                        return;
                    }
                }
                show(gm, SocialTrace.start(hours, gm.getName(), 4));
            }
            case "status" -> show(gm, SocialTrace.status());
            case "stop" -> show(gm, SocialTrace.stop("stopped by " + gm.getName()));
            default -> gm.yellowMessage("!socialtrace start [hours] | status | stop");
        }
    }

    private static void show(Character gm, List<String> lines) {
        for (String l : lines) {
            gm.yellowMessage(l);
        }
    }
}
