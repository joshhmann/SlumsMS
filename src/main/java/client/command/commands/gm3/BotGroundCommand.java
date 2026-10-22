package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMovementSystem.BotGround;
import soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1.5.0 rc5: which bots do not stand on a foothold. {@code !botground} lists the bots on your map whose server y is
 * not their foothold's y (sunk into the ground, or in the air); {@code !botground fix} also puts the sunk ones back on
 * their foothold; {@code !botground channel} counts them on every map of your channel.
 */
public class BotGroundCommand extends Command {
    {
        setDescription("Bots not standing on a foothold: !botground [fix | channel]");
    }

    /** A bot within this many px of its foothold counts as standing on it (slopes round to the pixel). */
    static final int TOLERANCE_PX = 1;

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        String mode = params.length > 0 ? params[0].toLowerCase() : "";
        if (mode.equals("channel")) {
            Map<Integer, int[]> byMap = new TreeMap<>();
            for (MapleMap map : c.getChannelServer().getMapFactory().getMaps().values()) {
                int[] counts = byMap.computeIfAbsent(map.getId(), k -> new int[3]);
                for (Character chr : map.getAllPlayers()) {
                    if (BotHelpers.isBot(chr)) {
                        counts[bucket(BotGround.offset(map, chr.getPosition()))]++;
                    }
                }
            }
            int sunk = 0;
            int air = 0;
            for (Map.Entry<Integer, int[]> e : byMap.entrySet()) {
                int[] n = e.getValue();
                if (n[1] + n[2] > 0) {
                    gm.yellowMessage(e.getKey() + ": " + n[1] + " sunk, " + n[2] + " in the air, " + n[0] + " on ground");
                }
                sunk += n[1];
                air += n[2];
            }
            gm.yellowMessage("Channel " + c.getChannel() + ": " + sunk + " bots sunk, " + air + " in the air.");
            return;
        }
        MapleMap map = gm.getMap();
        List<String> lines = new ArrayList<>();
        int fixed = 0;
        for (Character bot : map.getAllPlayers()) {
            if (!BotHelpers.isBot(bot)) {
                continue;
            }
            Point p = bot.getPosition();
            String line = describe(map.getFootholds(), bot.getName(), p, bot.getStance(), bot.getChair());
            if (line == null) {
                continue;
            }
            lines.add(line);
            if (mode.equals("fix") && BotGround.offset(map, p) > TOLERANCE_PX) {
                Point ground = BotGround.settle(map, p);
                bot.setPosition(ground);
                MovementCommands.BotIdleStandingUpdateForced(bot);
                fixed++;
            }
        }
        lines.forEach(gm::yellowMessage);
        gm.yellowMessage(lines.isEmpty() ? "Every bot on map " + map.getId() + " stands on a foothold."
                : lines.size() + " bots off their foothold on map " + map.getId()
                + (mode.equals("fix") ? "; put " + fixed + " sunk ones back on it." : ". !botground fix lifts the sunk ones."));
    }

    /** 0 = on its foothold, 1 = sunk into it, 2 = above it (or no foothold under it). */
    static int bucket(int offset) {
        if (offset == Integer.MIN_VALUE || offset < -TOLERANCE_PX) {
            return 2;
        }
        return offset > TOLERANCE_PX ? 1 : 0;
    }

    /** The report line for a bot off its foothold, or null when it stands on it. */
    static String describe(FootholdTree footholds, String name, Point p, int stance, int chair) {
        int offset = BotGround.offset(footholds, p);
        if (bucket(offset) == 0) {
            return null;
        }
        String where = offset == Integer.MIN_VALUE ? "no foothold under it"
                : offset > 0 ? offset + " px below its foothold (y " + (p.y - offset) + ")"
                : (-offset) + " px above its foothold (y " + (p.y - offset) + ")";
        return name + " at (" + p.x + ", " + p.y + "): " + where + ", stance " + stance
                + (chair > 0 ? ", chair " + chair : "");
    }
}
