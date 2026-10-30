package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.GCMoveSystem.NavProbe;
import soloMapling.server.ExecutorServiceManager;

import java.util.List;

/**
 * Ticket 157 (TEST only, {@link soloMapling.server.DebugTools}): can a bot reach a point on its map, and how - or why
 * not. Read-only: it asks the bots' planner over the cached nav graph and moves nobody.
 * <pre>
 *   !navprobe &lt;botName|me&gt; &lt;x&gt; &lt;y&gt;   the verdict and the edge path
 *   !navprobe map                    every portal and NPC on your map from its spawn point; lists what nothing reaches
 * </pre>
 */
public class NavProbeCommand extends Command {
    {
        setDescription("Can a bot reach (x,y) on its map: !navprobe <botName|me> <x> <y> | !navprobe map");
    }

    static final int MAX_LINES = 12;

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length == 1 && params[0].equalsIgnoreCase("map")) {
            ExecutorServiceManager.getExecutorService().execute(() -> {
                List<String> failures = NavProbe.probeMap(gm.getMap());
                int spots = NavProbe.spots(gm.getMap()).size();
                gm.yellowMessage("navprobe map " + gm.getMapId() + ": " + spots + " portals/NPCs, "
                        + failures.size() + " unreachable from the spawn point");
                failures.stream().limit(MAX_LINES).forEach(gm::dropMessage);
            });
            return;
        }
        if (params.length != 3) {
            gm.yellowMessage("Syntax: !navprobe <botName|me> <x> <y>  |  !navprobe map");
            return;
        }
        int x;
        int y;
        try {
            x = Integer.parseInt(params[1]);
            y = Integer.parseInt(params[2]);
        } catch (NumberFormatException e) {
            gm.yellowMessage("x and y are numbers: !navprobe <botName|me> <x> <y>");
            return;
        }
        Character who = params[0].equalsIgnoreCase("me") ? gm
                : c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (who == null || who.getMap() == null) {
            gm.yellowMessage("No one online named " + params[0] + " in this world.");
            return;
        }
        ExecutorServiceManager.getExecutorService().execute(() -> {
            NavProbe.Result r = NavProbe.probe(who, x, y);
            gm.yellowMessage("navprobe " + who.getName() + " on map " + who.getMapId() + " from ("
                    + who.getPosition().x + "," + who.getPosition().y + ") to (" + x + "," + y + ")");
            r.lines().stream().limit(MAX_LINES).forEach(gm::dropMessage);
        });
    }
}
