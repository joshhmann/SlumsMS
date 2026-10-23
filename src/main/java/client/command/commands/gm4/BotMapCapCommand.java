package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotMapCap;

import java.util.List;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * 1.5.0 (3k bots): the per-map bot cap read-out.
 * !botmapcap          - the cap (BOT_MAP_CAP) and its overrides, how many picks it turned away since boot, and the
 *                       5 busiest map instances of each channel as "mapId bots/cap" ("!" = over the cap)
 * !botmapcap <n>      - the n busiest per channel (at most 30)
 * !botmapcap <mapId>  - that map on every channel where it is loaded
 */
public class BotMapCapCommand extends Command {
    {
        setDescription("Per-map bot cap: !botmapcap [n | mapId] - busiest maps per channel and their caps");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        List<BotMapCap.Load> loads = BotMapCap.loads();
        int perChannel = 5;
        if (params.length > 0 && isInteger(params[0])) {
            int n = Integer.parseInt(params[0]);
            if (n > 1000) { // a map id
                player.yellowMessage("Map " + n + ": cap " + (BotMapCap.cap(n) > 0 ? BotMapCap.cap(n) : "off"));
                boolean any = false;
                for (BotMapCap.Load l : loads) {
                    if (l.mapId() == n) {
                        player.yellowMessage("  ch" + l.channel() + ": " + l.bots() + " bots" + (l.over() ? " (over the cap)"
                                : l.full() ? " (full)" : ""));
                        any = true;
                    }
                }
                if (!any) {
                    player.yellowMessage("  no bots on it on any channel");
                }
                return;
            }
            perChannel = Math.max(1, Math.min(30, n));
        } else if (params.length > 0) {
            player.yellowMessage("Syntax: !botmapcap [n | mapId]");
            return;
        }
        int defaultCap = config.YamlConfig.config.server.BOT_MAP_CAP;
        BotMapCap.describe(loads, perChannel, BotMapCap.overrides(), defaultCap, BotMapCap.turnedAway())
                .forEach(player::yellowMessage);
    }
}
