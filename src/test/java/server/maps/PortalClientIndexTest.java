package server.maps;

import org.junit.jupiter.api.Test;
import provider.Data;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.DataTool;
import provider.wz.WZFiles;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * b339 (1.6.8): SET_FIELD's spawn byte is the portal's index in the client's list, which leaves out the type-6
 * town-portal points. Client's sweep on TEST-B (9 Nov) sent the WZ index 10 for Orbis's portal1 and landed on another
 * portal; the client's list for 270000000 is [sp, sp, out00, in00, portal1, portal2].
 */
class PortalClientIndexTest {

    private static GenericPortal portal(int wzIndex, int type) {
        GenericPortal p = new GenericPortal(type);
        p.setWzIndex(wzIndex);
        p.setId(type == Portal.DOOR_PORTAL ? 0x80 + wzIndex : wzIndex);
        return p;
    }

    @Test
    void portalsAfterTownPortalPointsShiftDownByTheirCount() {
        List<Portal> all = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            all.add(portal(i, i == 0 || i == 1 ? 0 : i >= 4 && i <= 9 ? Portal.DOOR_PORTAL : Portal.MAP_PORTAL));
        }
        PortalFactory.assignClientIndexes(all);
        assertEquals(0, all.get(0).getClientIndex());
        assertEquals(2, all.get(2).getClientIndex(), "before the tp points nothing moves");
        assertEquals(3, all.get(3).getClientIndex());
        assertEquals(4, all.get(10).getClientIndex(), "portal1: WZ 10 minus six tp points");
        assertEquals(5, all.get(11).getClientIndex(), "portal2");
        assertEquals(0x80 + 4, all.get(4).getClientIndex(), "a tp point keeps its 0x80+ id");
    }

    @Test
    void aMapWithoutTownPortalPointsIsUnchanged() {
        List<Portal> all = List.of(portal(0, 0), portal(1, Portal.MAP_PORTAL), portal(2, Portal.MAP_PORTAL));
        PortalFactory.assignClientIndexes(all);
        for (Portal p : all) {
            assertEquals(p.getId(), p.getClientIndex());
        }
    }

    @Test
    void theWarpAndTheLoginSendTheClientIndex() throws Exception {
        String chr = Files.readString(Path.of("src/main/java/client/Character.java"));
        assertEquals(2, chr.split("getWarpToMap\\(to, pto\\.getClientIndex\\(\\), this\\)", -1).length - 1,
                "both portal warps write the client index");
        assertEquals(1, chr.split("getWarpToMap\\(to, pto\\.getId\\(\\)", -1).length, "no warp writes the server id");
        String packets = Files.readString(Path.of("src/main/java/tools/PacketCreator.java"));
        assertTrue(packets.contains("p.writeByte(clientSpawnIndex(chr));"), "the login's spawn point too");
    }

    @Test
    void ordinaryMapsSendTheSameByteAsBefore() {
        // Coordinator (1.6.7 cherry-pick): only the 9 maps with a tp point before another portal change; a sample of
        // ordinary towns must keep every portal's byte exactly as it was (client index == server id)
        String wz = System.getProperty("wz-path");
        assumeTrue(wz != null && Files.isDirectory(Path.of(wz, "Map.wz")), "needs -Dwz-path");
        DataProvider source = DataProviderFactory.getDataProvider(WZFiles.MAP);
        for (int mapId : new int[]{100000000, 200000000, 211000000}) {
            Data map = source.getData("Map/Map" + (mapId / 100000000) + "/" + mapId + ".img");
            PortalFactory factory = new PortalFactory();
            List<Portal> all = new ArrayList<>();
            for (Data node : map.getChildByPath("portal")) {
                all.add(factory.makePortal(DataTool.getInt(node.getChildByPath("pt")), mapId, node));
            }
            PortalFactory.assignClientIndexes(all);
            assertTrue(all.size() > 3, mapId + " has its portals");
            for (Portal p : all) {
                assertEquals(p.getId(), p.getClientIndex(), mapId + " portal " + p.getName() + " keeps its byte");
            }
        }
    }

    @Test
    void orbisTowerFromTheRealMapData() {
        String wz = System.getProperty("wz-path");
        assumeTrue(wz != null && Files.isDirectory(Path.of(wz, "Map.wz")), "needs -Dwz-path");
        DataProvider source = DataProviderFactory.getDataProvider(WZFiles.MAP);
        Data map = source.getData("Map/Map2/270000000.img");
        PortalFactory factory = new PortalFactory();
        Map<String, Portal> byName = new HashMap<>();
        List<Portal> all = new ArrayList<>();
        for (Data node : map.getChildByPath("portal")) {
            Portal p = factory.makePortal(DataTool.getInt(node.getChildByPath("pt")), 270000000, node);
            all.add(p);
            byName.putIfAbsent(p.getName(), p);
        }
        PortalFactory.assignClientIndexes(all);
        assertEquals(10, byName.get("portal1").getId(), "the server still knows portal1 by its WZ index");
        assertEquals(4, byName.get("portal1").getClientIndex());
        assertEquals(5, byName.get("portal2").getClientIndex());
        assertEquals(2, byName.get("out00").getClientIndex());
    }
}
