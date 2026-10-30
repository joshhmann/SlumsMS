package soloMapling.ArtificialPlayer.GCMoveSystem;

import server.maps.MapleMap;

import java.awt.Rectangle;
import java.util.BitSet;

/**
 * Ticket 157 follow-up (the Website lane's dead-end check, TEST 2026-10-30: 20 "pits" over 960 maps, e.g. 100040001
 * r33 at y 632 under a view bottom of 450, 101020003, 100040104): some tree and dungeon maps keep a hidden catch floor
 * below the bottom of their view bounds. The graph builder makes it a region and gives it a JUMP or DROP edge in and
 * nothing out, so a planner that ever routes through it strands the bot where no player can see or follow it, and
 * stuck events pile up there. A pit here is a ground region whose top lies below the map's view bottom. The planner
 * never takes an edge into a pit from outside one; a bot that is already in one (it fell) may still use any edge out.
 *
 * <p>Applied when planning, not in the graph builder, so the cached graphs, their version and the nav learning books
 * stay as they are. The pit set is worked out once per graph instance (a few compares per region) and kept beside
 * it; a map without view bounds has no pits.
 */
final class NavPits {

    private NavPits() {
    }

    /** No pits: the shared empty answer. */
    private static final BitSet NONE = new BitSet(0);

    /** Whether this edge drops a bot from outside a pit into one. */
    static boolean into(BotNavigationGraph graph, MapleMap map, BotNavigationGraph.Edge edge) {
        BitSet pits = pits(graph, map);
        return pits != NONE && edge.toRegionId >= 0 && pits.get(edge.toRegionId)
                && !(edge.fromRegionId >= 0 && pits.get(edge.fromRegionId));
    }

    /** Whether the region is a pit on this map. */
    static boolean isPit(BotNavigationGraph graph, MapleMap map, int regionId) {
        BitSet pits = pits(graph, map);
        return pits != NONE && regionId >= 0 && pits.get(regionId);
    }

    static BitSet pits(BotNavigationGraph graph, MapleMap map) {
        BitSet pits = graph.pitRegions;
        if (pits == null) {
            pits = compute(graph, map == null ? null : map.getMapArea());
            graph.pitRegions = pits;
        }
        return pits;
    }

    static BitSet compute(BotNavigationGraph graph, Rectangle view) {
        if (view == null || view.width <= 0 || view.height <= 0) {
            return NONE;
        }
        int bottom = view.y + view.height;
        BitSet out = null;
        for (BotNavigationGraph.Region region : graph.regions) {
            if (isPitRegion(region.isRopeRegion, region.id, region.minY, bottom)) {
                if (out == null) {
                    out = new BitSet();
                }
                out.set(region.id);
            }
        }
        return out == null ? NONE : out;
    }

    /** The pit rule for one region (shared with the dashboard's {@code nav/pits.json}): ground whose top is below the view. */
    static boolean isPitRegion(boolean rope, int regionId, int minY, int viewBottom) {
        return !rope && regionId >= 0 && minY > viewBottom;
    }
}
