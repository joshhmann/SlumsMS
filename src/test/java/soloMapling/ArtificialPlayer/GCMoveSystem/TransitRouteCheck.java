package soloMapling.ArtificialPlayer.GCMoveSystem;

import soloMapling.ArtificialPlayer.BotTravelSystem.BotTransit;

import java.util.List;

/*
 * Dev tool, not a unit test (ticket 03): prints the planner's route, fares and planned time for the trips bots
 * make between continents, against a real WZ tree. Each argument is from:to; with none, a standard set runs.
 *
 *   java -cp <jar>:<test-classes> -Dwz-path=wz soloMapling.ArtificialPlayer.GCMoveSystem.TransitRouteCheck
 */
public final class TransitRouteCheck {

    private TransitRouteCheck() {
    }

    private static final String[] TRIPS = {
            "100000000:200000000", // Henesys -> Orbis (Ellinia boat)
            "100000000:220000000", // Henesys -> Ludibrium (boat + train)
            "200000000:101000000", // Orbis -> Ellinia
            "220000000:100000000", // Ludibrium -> Henesys
            "100000000:240000000", // Henesys -> Leafre
            "240000000:220000000", // Leafre -> Ludibrium
            "200000000:250000000", // Orbis -> Mu Lung (crane)
            "250000000:200000000", // Mu Lung -> Orbis
            "200000000:260000000", // Orbis -> Ariant (genie)
            "100000000:261000000", // Henesys -> Magatia
            "211000000:230000000", // El Nath -> Aquarium (walk down the Orbis tower)
            "230000000:251000000", // Aquarium -> Herb Town (dolphin)
            "103000000:540000000", // Kerning -> Singapore (airplane)
            "540000000:100000000", // Singapore -> Henesys
            "103000000:600000000", // Kerning -> New Leaf City (subway)
            "100000000:800000000", // Henesys -> Mushroom Shrine (World Tour)
            "100000000:801000000", // Henesys -> Showa
            "101000000:130000210", // Ellinia -> Ereve's ferry dock
            "240000000:270000100", // Leafre -> Temple of Time gate (dragon)
            "211060100:100000000", // Lion King's Castle -> Henesys (gate check, rebirth 0)
            // Ticket 03 follow-up: the high-level and MapleRoot areas.
            "211000000:211040400", // El Nath -> Sharp Cliff II (Jeff)
            "100000000:211060100:1", // Henesys -> LKC Under the Castle Walls 1 (in_lkc, rebirth 1)
            "100000000:270010100", // Henesys -> Memory Lane 1 (Temple of Time, version gate lifted)
            "100000000:273040100:2", // Henesys -> Forsaken Excavation Site 2 (Gate to the Future, rebirth 2)
            "100000000:273040100:3", // ... at rebirth 3 (the Henesys teleporter)
            "100000000:450001011:3", // Henesys -> Arcane River (teleporter)
            "100000000:401040700:3", // Henesys -> Heliseum
            "100000000:410000102:3", // Henesys -> Fox Tree Midway 2 (fox god's lift)
            "100000000:310070480", // Henesys -> Black Heaven Maze 6 (hyper rock)
            "310070120:100000000", // Scrapyard -> Henesys (hyper rock home)
            "100000000:140020100", // Henesys -> Rien (whale)
            "100000000:110030000", // Henesys -> Florina Beach
            "100000000:682000800", // Henesys -> Haunted House Library (NLC taxi)
            "100000000:610020002", // Henesys -> Crimsonwood Lower Ascent
            "100000000:551030100", // Henesys -> Spooky World (Malaysia tour)
            "100000000:801040001", // Henesys -> Showa hideout
            "100000000:130010100", // Henesys -> Ereve Training Forest II
            "100000000:221030000", // Henesys -> Omega Sector Off-Limits (past the old 60-hop cap)
    };

    public static void main(String[] args) {
        String[] trips = args.length > 0 ? args : TRIPS;
        long t0 = System.nanoTime();
        int maps = GCWorldGraph.mapCount();
        System.out.printf("world graph: %d maps, %d transit edges (%d ms)%n", maps, BotTransit.edgeCount(),
                (System.nanoTime() - t0) / 1_000_000);
        for (String trip : trips) {
            String[] p = trip.split(":");
            int from = Integer.parseInt(p[0]);
            int to = Integer.parseInt(p[1]);
            int rebirths = p.length > 2 ? Integer.parseInt(p[2]) : 0;
            long s = System.nanoTime();
            List<Integer> route = GCWorldGraph.route(from, to, GCWorldGraph.MAX_ROUTE_HOPS, rebirths, true,
                    GCWorldGraph.NO_SAVED_RETURN);
            long ms = (System.nanoTime() - s) / 1_000_000;
            if (route == null) {
                System.out.printf("%d -> %d r%d: NO ROUTE (%d ms)%n", from, to, rebirths, ms);
                continue;
            }
            int fare = GCMovement.routeFare(from, route, 100);
            int sec = 0;
            StringBuilder legs = new StringBuilder();
            int at = from;
            for (int next : route) {
                BotTransit.Edge e = BotTransit.edge(at, next);
                boolean walked = e == null || contains(GCWorldGraph.portalNeighbors(at), next);
                sec += walked ? GCWorldGraph.WALK_SEC : e.sec();
                if (!walked) {
                    legs.append(String.format(" [%s %d->%d%s]", e.name(), at, next,
                            e.isBoard() && e.fare() > 0 ? " " + e.fare() + "m" : ""));
                }
                at = next;
            }
            System.out.printf("%d -> %d r%d: %d hops, %d mesos, ~%d min planned (%d ms)%s%n", from, to, rebirths,
                    route.size(), fare, sec / 60, ms, legs);
        }
    }

    private static boolean contains(int[] a, int v) {
        for (int x : a) {
            if (x == v) {
                return true;
            }
        }
        return false;
    }
}
