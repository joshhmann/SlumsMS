package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.maps.MapleMap;

import java.awt.Point;

/**
 * {@code !tp <x> <y>} (GM 3, 1.6.1, for the client's NPC screenshot pass): moves the GM within the current map to the
 * point, snapped down onto the nearest foothold below it when there is one (else the point as given). It is a
 * same-map warp, as the QA world's GM teleports do ({@code changeMap(map, point)}): the client reloads the map and
 * the GM appears at the point. {@code !pos} shows where you stand.
 */
public class TpCommand extends Command {
    {
        setDescription("Move to a point on this map: !tp <x> <y> (snapped onto the ground below).");
    }

    /** The point in {@code !tp <x> <y>}, or null when the arguments are not two whole numbers. */
    static Point parse(String[] params) {
        if (params == null || params.length != 2) {
            return null;
        }
        try {
            return new Point(Integer.parseInt(params[0].trim()), Integer.parseInt(params[1].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Point to = parse(params);
        if (to == null) {
            player.yellowMessage("Syntax: !tp <x> <y>   (whole numbers; !pos shows where you are)");
            return;
        }
        MapleMap map = player.getMap();
        Point ground = map.getPointBelow(new Point(to.x, to.y - 1));
        Point spot = ground == null ? to : new Point(ground.x, ground.y - 1);
        player.changeMap(map, spot);
        player.dropMessage(6, "Moved to (" + spot.x + ", " + spot.y + ")"
                + (ground == null ? ", no ground below that point" : ", on the ground below (" + to.x + ", " + to.y + ")") + ".");
    }
}
