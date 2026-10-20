package soloMapling.ArtificialPlayer.BotTravelSystem;

import com.esotericsoftware.yamlbeans.YamlReader;
import server.life.NPC;
import server.maps.MapleMap;
import server.maps.Portal;

import java.awt.Point;
import java.io.FileReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;

import static soloMapling.server.MapleVersionManager.isPortalinCurrentVersion;

// Ticket 03: the paid and scheduled hops between maps that no walkable portal joins - Victoria cabs, the
// Orbis boats/trains/genie/cabin, Hak, the dolphin taxi, the airplane, the NLC subway, the Ereve ferries,
// the Temple of Time dragon and the World Tour. Loaded from BotTransit.yaml, where each row cites the
// server script it mirrors, so bots pay the scripts' fares and ride the events' real schedules.
//
// GCWorldGraph routes over these edges (with `sec` as the cost) and GCTravel runs them: an NPC/portal edge
// walks to the NPC, waits for the event's boarding window, pays and goes; a ride edge waits aboard until the
// server (or, for per-player instances, the bot itself) moves it on. Replaces GCTaxi's fare-free cab table.
// Our own creation.
public final class BotTransit {

    private BotTransit() {
    }

    private static final String YAML_PATH = "src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml";

    public enum Via { NPC, PORTAL, RIDE }

    // One transit hop. For NPC/PORTAL edges the bot walks to npcId (or portalName) on fromMapId; a RIDE edge
    // means the bot is aboard fromMapId and waits up to sec. toPortalId/toPortalName pick the arrival portal
    // (-1/null = the map's default). beginnerFare < 0 means a Beginner pays the normal fare.
    public record Edge(String name, int fromMapId, int toMapId, Via via, int npcId, int sellerNpcId,
                       String portalName, int fare, int beginnerFare, String event, boolean serverDriven,
                       int sec, int toPortalId, String toPortalName, String saveLocation, String returnToSaved) {

        public boolean isRide() {
            return via == Via.RIDE;
        }

        public boolean isBoard() {
            return via != Via.RIDE;
        }

        // The fare this bot's job pays (the Victoria cabs charge Beginners a tenth).
        public int fareFor(int jobId) {
            return jobId == 0 && beginnerFare >= 0 ? beginnerFare : fare;
        }

        // The edge's time at the world's travel rate, as the event scripts scale theirs (getTransportationTime).
        public long scaledMs(int travelRate) {
            return (long) Math.ceil(sec * 1000.0 / Math.max(1, travelRate));
        }
    }

    private static volatile Map<Integer, List<Edge>> byFrom;

    private static Map<Integer, List<Edge>> index() {
        Map<Integer, List<Edge>> local = byFrom;
        if (local == null) {
            synchronized (BotTransit.class) {
                if (byFrom == null) {
                    byFrom = index(loadFile(), id -> isPortalinCurrentVersion(id));
                }
                local = byFrom;
            }
        }
        return local;
    }

    // Swap in a table (tests, live reload). Null drops back to lazily reading the YAML.
    public static synchronized void install(List<Edge> edges) {
        byFrom = edges == null ? null : index(edges, id -> true);
    }

    public static synchronized void reload() {
        byFrom = null;
    }

    static Map<Integer, List<Edge>> index(List<Edge> edges, IntPredicate mapAllowed) {
        Map<Integer, List<Edge>> out = new HashMap<>();
        for (Edge e : edges) {
            if (!mapAllowed.test(e.fromMapId()) || !mapAllowed.test(e.toMapId())) {
                continue; // a map gated out of this version: never route a bot there (as GCTaxi did)
            }
            out.computeIfAbsent(e.fromMapId(), k -> new ArrayList<>()).add(e);
        }
        Map<Integer, List<Edge>> frozen = new HashMap<>();
        for (Map.Entry<Integer, List<Edge>> en : out.entrySet()) {
            frozen.put(en.getKey(), List.copyOf(en.getValue()));
        }
        return Map.copyOf(frozen);
    }

    public static List<Edge> from(int mapId) {
        return index().getOrDefault(mapId, List.of());
    }

    // The transit edge from one map to another, or null if none.
    public static Edge edge(int fromMapId, int toMapId) {
        for (Edge e : from(fromMapId)) {
            if (e.toMapId() == toMapId) {
                return e;
            }
        }
        return null;
    }

    // The ride a bot standing on mapId is aboard, or null when mapId is not a waiting room / ride map.
    public static Edge rideOn(int mapId) {
        for (Edge e : from(mapId)) {
            if (e.isRide()) {
                return e;
            }
        }
        return null;
    }

    public static boolean isRideMap(int mapId) {
        return rideOn(mapId) != null;
    }

    public static int edgeCount() {
        int n = 0;
        for (List<Edge> l : index().values()) {
            n += l.size();
        }
        return n;
    }

    // Live position of an NPC on map, or null if it isn't there.
    public static Point npcPos(MapleMap map, int npcId) {
        if (map == null || npcId <= 0) {
            return null;
        }
        NPC npc = map.getNPCById(npcId);
        return npc == null ? null : npc.getPosition();
    }

    // Live position of a named portal on map, or null if it isn't there.
    public static Point portalPos(MapleMap map, String portalName) {
        if (map == null || portalName == null) {
            return null;
        }
        Portal p = map.getPortal(portalName);
        return p == null ? null : p.getPosition();
    }

    // ── Loading ──

    private static List<Edge> loadFile() {
        try (FileReader in = new FileReader(soloMapling.DataFiles.file(YAML_PATH))) {
            return parse(in);
        } catch (Exception e) {
            System.out.println("[BotTransit] failed to load " + YAML_PATH + ": " + e.getMessage()
                    + " - bots will warp across water instead of riding");
            return List.of();
        }
    }

    // Parses the YAML's `edges` list. A malformed row is skipped (and logged) rather than failing the table.
    @SuppressWarnings("unchecked")
    public static List<Edge> parse(Reader in) throws Exception {
        Object root = new YamlReader(in).read();
        List<Edge> out = new ArrayList<>();
        if (!(root instanceof Map<?, ?> rootMap) || !(rootMap.get("edges") instanceof List<?> rows)) {
            return out;
        }
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> r)) {
                continue;
            }
            Edge e = toEdge((Map<String, Object>) r);
            if (e == null) {
                System.out.println("[BotTransit] skipped malformed edge " + r);
                continue;
            }
            out.add(e);
        }
        return out;
    }

    private static Edge toEdge(Map<String, Object> r) {
        int from = toInt(r.get("from"), -1);
        int to = toInt(r.get("to"), -1);
        Via via = switch (str(r.get("via"), "")) {
            case "npc" -> Via.NPC;
            case "portal" -> Via.PORTAL;
            case "ride" -> Via.RIDE;
            default -> null;
        };
        int npc = toInt(r.get("npc"), -1);
        String portal = str(r.get("portal"), null);
        if (from <= 0 || to <= 0 || via == null
                || (via == Via.NPC && npc <= 0) || (via == Via.PORTAL && portal == null)) {
            return null;
        }
        String toPortal = str(r.get("to_portal"), null);
        int toPortalId = -1;
        String toPortalName = null;
        if (toPortal != null) {
            try {
                toPortalId = Integer.parseInt(toPortal.trim());
            } catch (NumberFormatException notANumber) {
                toPortalName = toPortal.trim();
            }
        }
        return new Edge(str(r.get("name"), "transit"), from, to, via, npc, toInt(r.get("seller"), -1), portal,
                Math.max(0, toInt(r.get("fare"), 0)), toInt(r.get("beginner_fare"), -1), str(r.get("event"), null),
                Boolean.parseBoolean(str(r.get("server"), "true")), Math.max(1, toInt(r.get("sec"), 20)),
                toPortalId, toPortalName, str(r.get("save_location"), null), str(r.get("return_to_saved"), null));
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o != null) {
            try {
                return Integer.parseInt(o.toString().trim());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static String str(Object o, String fallback) {
        return o != null ? o.toString() : fallback;
    }
}
