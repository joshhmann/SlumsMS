package soloMapling.autoqa;

import client.Character;
import config.YamlConfig;
import constants.id.MapId;
import net.server.channel.Channel;
import server.maps.Foothold;
import server.maps.FootholdTree;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.awt.Point;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Takes a {@link HealthSweep.Snapshot} of the live server for the health sweep. Runs on the sweep's own thread: it
 * only reads (map player lists are copied under the map's lock, positions are copies), never loads a map, and caps
 * the number of bots it looks at.
 */
final class GameHealthSource {

    /** The main server log (log4j2.xml's RollingFile). Lines with " ERROR " count as errors. */
    static final Path SERVER_LOG = Path.of("logs", "cosmic-log.log");

    private final Map<Integer, List<FootholdCheck.Seg>> footholdCache = new HashMap<>();
    private final Map<Integer, int[]> boundsCache = new HashMap<>();
    private long logOffset = -1;

    HealthSweep.Snapshot take(int maxBots) {
        long now = System.currentTimeMillis();
        List<Channel> channels = BotChannels.channels();
        List<HealthSweep.BotSample> all = new ArrayList<>();
        int maps = 0;
        for (Channel ch : channels) {
            for (MapleMap map : ch.getMapFactory().getMaps().values()) {
                List<Character> chars = map.getAllPlayers();
                boolean any = false;
                for (Character c : chars) {
                    if (!BotHelpers.isBot(c)) {
                        continue;
                    }
                    if (!any) {
                        any = true;
                        maps++;
                        cacheMap(map);
                    }
                    all.add(sample(ch.getId(), map, c));
                }
            }
        }
        int seen = all.size();
        List<HealthSweep.BotSample> sampled = SweepMath.sample(all, maxBots);
        int[] perChannel = BotChannels.botsPerChannel();
        double[] expected = BotChannels.shares(YamlConfig.config.server.BOT_CHANNEL_WEIGHTS, perChannel.length);
        int errors = countNewErrorLines();
        return new HealthSweep.Snapshot(now, sampled, seen, maps, perChannel, expected, errors, SERVER_LOG.toString());
    }

    List<FootholdCheck.Seg> footholds(int mapId) {
        synchronized (footholdCache) {
            return footholdCache.getOrDefault(mapId, List.of());
        }
    }

    private void cacheMap(MapleMap map) {
        synchronized (footholdCache) {
            if (footholdCache.containsKey(map.getId())) {
                return;
            }
            FootholdTree tree = map.getFootholds();
            List<FootholdCheck.Seg> segs = new ArrayList<>();
            if (tree != null) {
                for (Foothold f : tree.getAllFootholds()) {
                    segs.add(new FootholdCheck.Seg(f.getX1(), f.getY1(), f.getX2(), f.getY2()));
                }
                boundsCache.put(map.getId(), new int[]{tree.getX1(), tree.getY1(), tree.getX2(), tree.getY2()});
            }
            footholdCache.put(map.getId(), List.copyOf(segs));
        }
    }

    private HealthSweep.BotSample sample(int channel, MapleMap map, Character bot) {
        Point p = bot.getPosition();
        String gc = GCMovement.sweepPose(bot);
        FootholdCheck.Pose pose = pose(gc, bot.getStance(), map.isSwim());
        boolean moving = gc != null && GCMovement.isMoving(bot) && !GCMovement.isResting(bot);
        return new HealthSweep.BotSample(bot.getId(), bot.getName(), channel, map.getId(), p.x, p.y, pose, moving,
                gc != null, mapProblem(map, bot, p));
    }

    /**
     * The pose for the foothold check: on a swim map always SWIMMING (an unobserved driven bot moving over the water
     * graph never sets the driver's swimming flag, so the rc5 sweep flagged Aquarium swimmers as floating); else the
     * movement driver's pose, or for a bot the driver does not run ({@code gc} null) the pose its stance shows.
     */
    static FootholdCheck.Pose pose(String gc, int stance, boolean swimMap) {
        if (swimMap) {
            return FootholdCheck.Pose.SWIMMING;
        }
        return gc != null ? FootholdCheck.Pose.valueOf(gc) : poseFromStance(stance, false);
    }

    /**
     * The pose of a bot the movement driver does not run (town dressing), from its stance: v83 stances are
     * (pose << 1) | facing, with 3 = jump, 6 = fly, 7 = ladder, 8 = rope, 9 = dead.
     */
    static FootholdCheck.Pose poseFromStance(int stance, boolean swimMap) {
        if (swimMap) {
            return FootholdCheck.Pose.SWIMMING;
        }
        return switch ((stance & 0xFF) >> 1) {
            case 3, 6 -> FootholdCheck.Pose.AIRBORNE;
            case 7, 8 -> FootholdCheck.Pose.CLIMBING;
            case 9 -> FootholdCheck.Pose.UNKNOWN;
            default -> FootholdCheck.Pose.GROUNDED;
        };
    }

    private String mapProblem(MapleMap map, Character bot, Point p) {
        int id = map.getId();
        String special = specialMap(id);
        if (special != null) {
            return special;
        }
        if (bot.getMapId() != id) {
            return "stands in map " + id + " but its character says " + bot.getMapId();
        }
        int[] b;
        synchronized (footholdCache) {
            b = boundsCache.get(id);
        }
        if (b != null && (p.x < b[0] - 200 || p.x > b[2] + 200 || p.y > b[3] + 200 || p.y < b[1] - 1000)) {
            return "out of the map's bounds (" + b[0] + ".." + b[2] + ", " + b[1] + ".." + b[3] + ")";
        }
        return null;
    }

    /** A map no bot belongs on, or null. */
    static String specialMap(int mapId) {
        if (mapId == MapId.NONE || mapId <= 0) {
            return "no map (" + mapId + ")";
        }
        if (mapId == MapId.GM_MAP || mapId == MapId.DEVELOPERS_HQ) {
            return "GM map " + mapId;
        }
        if (mapId == MapId.JAIL) {
            return "jail " + mapId;
        }
        return null;
    }

    /** Error lines added to the server log since the last call (the whole file after a roll); -1 = unreadable. */
    private int countNewErrorLines() {
        if (!Files.isRegularFile(SERVER_LOG)) {
            return -1;
        }
        try (RandomAccessFile f = new RandomAccessFile(SERVER_LOG.toFile(), "r")) {
            long len = f.length();
            if (logOffset < 0) {
                logOffset = len; // first sweep: start counting from now
                return 0;
            }
            if (len < logOffset) {
                logOffset = 0; // rolled
            }
            long from = Math.max(logOffset, len - 8L * 1024 * 1024); // at most 8 MB per window
            f.seek(from);
            byte[] buf = new byte[(int) (len - from)];
            f.readFully(buf);
            logOffset = len;
            int n = 0;
            for (String line : new String(buf, StandardCharsets.UTF_8).split("\n")) {
                if (SweepMath.isErrorLine(line)) {
                    n++;
                }
            }
            return n;
        } catch (IOException e) {
            return -1;
        }
    }
}
