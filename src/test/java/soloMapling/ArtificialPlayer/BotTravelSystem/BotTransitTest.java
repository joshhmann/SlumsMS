package soloMapling.ArtificialPlayer.BotTravelSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.FileReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Ticket 03: the transit table is data, so these tests hold it to the server scripts it claims to mirror - every
// fare, every boarding NPC and every event map is checked against scripts/npc and scripts/event - and pin the
// shape every scheduled ride must have (board -> waiting room -> deck -> arrival).
class BotTransitTest {

    private static final String YAML = "src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.yaml";

    private static List<BotTransit.Edge> table() throws Exception {
        try (FileReader in = new FileReader(YAML)) {
            return BotTransit.parse(in);
        }
    }

    @AfterEach
    void restore() {
        BotTransit.reload();
    }

    @Test
    void theShippedTableParsesWithoutDroppingARow() throws Exception {
        List<BotTransit.Edge> edges = table();
        long rows = Files.readAllLines(Path.of(YAML)).stream().filter(l -> l.trim().startsWith("- {")).count();

        assertEquals(rows, edges.size(), "a malformed row was skipped");
        assertTrue(edges.size() > 90);
    }

    @Test
    void everyBoardingNpcExistsAndChargesTheFareItsScriptCharges() throws Exception {
        for (BotTransit.Edge e : table()) {
            if (e.via() != BotTransit.Via.NPC) {
                continue;
            }
            String script = npcScript(e.npcId());
            assertNotNull(script, "no script for npc " + e.npcId() + " (" + e.name() + ")");
            if (e.fare() > 0) {
                // The fare is either charged by the boarding NPC itself or by the ticket seller it reads.
                String seller = e.sellerNpcId() > 0 ? npcScript(e.sellerNpcId()) : null;
                String fare = String.valueOf(e.fare());
                boolean charged = script.contains(fare) || (seller != null && seller.contains(fare))
                        || orbisSellerCharges(e, fare) || stationSellerCharges(e, fare);
                assertTrue(charged, e.name() + ": fare " + fare + " not found in the scripts of npc " + e.npcId());
            }
        }
    }

    @Test
    void everyScheduledRideUsesTheEventsOwnMaps() throws Exception {
        Map<Integer, BotTransit.Edge> rides = new HashMap<>();
        for (BotTransit.Edge e : table()) {
            if (e.isRide()) {
                rides.put(e.fromMapId(), e);
            }
        }
        for (BotTransit.Edge e : table()) {
            if (e.event() == null) {
                continue;
            }
            String event = Files.readString(Path.of("scripts/event/" + e.event() + ".js"));
            BotTransit.Edge waiting = rides.get(e.toMapId());
            assertNotNull(waiting, e.name() + ": boarding leads to " + e.toMapId() + " but no ride waits there");
            BotTransit.Edge deck = rides.get(waiting.toMapId());
            assertNotNull(deck, e.name() + ": the waiting room leads to " + waiting.toMapId() + " which is no ride");
            assertTrue(waiting.serverDriven() && deck.serverDriven(), e.name() + ": an event ride is server-driven");
            for (int map : new int[]{e.toMapId(), waiting.toMapId(), deck.toMapId()}) {
                assertTrue(event.contains(String.valueOf(map)), e.name() + ": map " + map + " not in " + e.event() + ".js");
            }
            assertTrue(npcScript(e.npcId()).contains("\"" + e.event() + "\""),
                    e.name() + ": npc " + e.npcId() + " doesn't check event " + e.event());
        }
    }

    @Test
    void cabsChargeBeginnersATenth() throws Exception {
        BotTransit.Edge cab = table().stream()
                .filter(e -> e.fromMapId() == 100000000 && e.toMapId() == 101000000 && e.name().equals("cab"))
                .findFirst().orElseThrow();

        assertEquals(800, cab.fareFor(100));
        assertEquals(80, cab.fareFor(0));
    }

    @Test
    void rideTimesScaleWithTheWorldsTravelRate() {
        BotTransit.Edge boat = new BotTransit.Edge("boat", 1, 2, BotTransit.Via.RIDE, -1, -1, null, 0, -1, null,
                true, 600, -1, null, null, null);

        assertEquals(600_000L, boat.scaledMs(1));
        assertEquals(200_000L, boat.scaledMs(3));
    }

    @Test
    void edgesTouchingAGatedMapAreDropped() throws Exception {
        List<BotTransit.Edge> edges = BotTransit.parse(new StringReader("""
                edges:
                  - {name: open, from: 1, to: 2, via: npc, npc: 9, fare: 10}
                  - {name: gated, from: 1, to: 3, via: npc, npc: 9, fare: 10}
                  - {name: ride, from: 2, to: 1, via: ride, server: false, sec: 60}
                """));

        Map<Integer, List<BotTransit.Edge>> index = BotTransit.index(edges, id -> id != 3);

        assertEquals(1, index.get(1).size());
        assertEquals("open", index.get(1).get(0).name());
        assertFalse(index.get(2).get(0).serverDriven());
    }

    @Test
    void lookupsFindBoardingAndRideEdges() throws Exception {
        BotTransit.install(table());

        assertNotNull(BotTransit.edge(101000300, 101000301));
        assertTrue(BotTransit.isRideMap(200090010));
        assertEquals(200000100, BotTransit.rideOn(200090010).toMapId());
        assertNull(BotTransit.rideOn(100000000));
        assertNull(BotTransit.edge(100000000, 200000000), "no single hop crosses the ocean");
    }

    @Test
    void malformedRowsAreSkipped() throws Exception {
        List<BotTransit.Edge> edges = BotTransit.parse(new StringReader("""
                edges:
                  - {name: no npc, from: 1, to: 2, via: npc}
                  - {name: no via, from: 1, to: 2}
                  - {name: ok, from: 1, to: 2, via: portal, portal: out00, to_portal: in00}
                """));

        assertEquals(1, edges.size());
        assertEquals("in00", edges.get(0).toPortalName());
        assertEquals(-1, edges.get(0).toPortalId());
    }

    // Orbis platforms: the ticket comes from 2012000 in Orbis Station (cost[] = 5000, 6000, 30000, 6000).
    private static boolean orbisSellerCharges(BotTransit.Edge e, String fare) throws IOException {
        return e.fromMapId() / 1000 == 200000 && npcScript(2012000).contains(fare);
    }

    // Ludibrium and Leafre: the ticket comes from the seller in the station next to the platform.
    private static boolean stationSellerCharges(BotTransit.Edge e, String fare) throws IOException {
        int seller = switch (e.fromMapId()) {
            case 220000110 -> 2040000;
            case 240000110 -> 2082000;
            default -> -1;
        };
        return seller > 0 && npcScript(seller).contains(fare);
    }

    private static String npcScript(int npcId) throws IOException {
        Path p = Path.of("scripts/npc/" + npcId + ".js");
        return Files.exists(p) ? Files.readString(p) : null;
    }
}
