package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import net.server.Server;
import soloMapling.autoqa.BotWatch;

import java.util.List;

/*
 * 1.6.0 (owner 2026-10-26), test server only (registered with !qa or auto-QA on):
 *   !botwatch [start [n] [minutes] | status | stop]
 * Follows n online bots (default 40, stratified by what they are doing) for the given minutes (default 30), a look
 * every 30 s from memory, then judges whether each one did what it decided (OK, IDLE, MISMATCH, TELEPORTY,
 * LOGGED_OUT) and writes data/autoqa/botwatch-latest.json. The GM hears the summary when it ends.
 */
public class BotWatchCommand extends Command {
    {
        setDescription("Test server: follow bots for a while and check they do what they decided (!botwatch start).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String sub = params.length == 0 ? "status" : params[0];
        switch (sub) {
            case "start" -> {
                int[] nm = parse(params);
                if (nm == null) {
                    gm.yellowMessage("Syntax: !botwatch start [bots 1-" + BotWatch.MAX_BOTS + "] [minutes 1-"
                            + BotWatch.MAX_MINUTES + "]   e.g. !botwatch start 40 30");
                    return;
                }
                int gmId = gm.getId(), world = gm.getWorld();
                show(gm, BotWatch.get().start(nm[0], nm[1], "!botwatch " + gm.getName(), r -> {
                    Character now = Server.getInstance().getWorld(world).getPlayerStorage().getCharacterById(gmId);
                    if (now != null) {
                        now.yellowMessage("Bot watch done: " + r.line());
                        now.yellowMessage("Details: data/autoqa/botwatch-latest.json");
                    }
                }));
            }
            case "status" -> show(gm, BotWatch.get().status());
            case "stop" -> show(gm, BotWatch.get().stop());
            default -> gm.yellowMessage("!botwatch [start [bots] [minutes] | status | stop]");
        }
    }

    /** {bots, minutes} from "start [n] [minutes]", defaults 40 and 30; null when an argument is not a number. */
    static int[] parse(String[] params) {
        int n = BotWatch.DEFAULT_BOTS, minutes = BotWatch.DEFAULT_MINUTES;
        try {
            if (params.length > 1) {
                n = Integer.parseInt(params[1]);
            }
            if (params.length > 2) {
                minutes = Integer.parseInt(params[2]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        if (n < 1 || n > BotWatch.MAX_BOTS || minutes < 1 || minutes > BotWatch.MAX_MINUTES) {
            return null;
        }
        return new int[]{n, minutes};
    }

    private static void show(Character gm, List<String> lines) {
        for (String l : lines) {
            gm.yellowMessage(l);
        }
    }
}
