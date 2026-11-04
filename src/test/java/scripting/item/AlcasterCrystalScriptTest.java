package scripting.item;

import client.Character;
import client.Client;
import client.inventory.manipulator.InventoryManipulator;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCScriptManager;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import server.maps.Portal;
import server.quest.Quest;

import java.awt.Point;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** s-crystal: Alcaster's Crystal (2430159) must finish quest 3182 next to portal east00 of 211060400, and only there. */
class AlcasterCrystalScriptTest {

    private boolean run(int mapId, Point pos) {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Portal portal = mock(Portal.class);
        when(c.getPlayer()).thenReturn(chr);
        when(c.canClickNPC()).thenReturn(true);
        when(chr.getClient()).thenReturn(c);
        when(chr.getMap()).thenReturn(map);
        when(chr.getMapId()).thenReturn(mapId);
        when(map.getId()).thenReturn(mapId);
        when(chr.getPosition()).thenReturn(pos);
        when(map.getPortal("east00")).thenReturn(portal);
        when(portal.getPosition()).thenReturn(new Point(246, 164));
        var info = new ItemInformationProvider.ScriptedItem(2020005, "AlcasterCrystal", false);
        Quest quest = mock(Quest.class);
        try (var inv = mockStatic(InventoryManipulator.class); var q = mockStatic(Quest.class)) {
            q.when(() -> Quest.getInstance(3182)).thenReturn(quest);
            when(quest.forceComplete(any(), anyInt())).thenReturn(true);
            boolean started = NPCScriptManager.getInstance().start(c, info, null);
            org.junit.jupiter.api.Assertions.assertTrue(started);
            boolean took = false;
            try {
                inv.verify(() -> InventoryManipulator.removeById(any(Client.class), any(), eq(2430159), eq(1), anyBoolean(), anyBoolean()));
                took = true;
            } catch (Throwable ignored) {
            }
            boolean done = false;
            try {
                verify(quest).forceComplete(any(), anyInt());
                done = true;
            } catch (Throwable ignored) {
            }
            org.junit.jupiter.api.Assertions.assertEquals(took, done);
            return done;
        }
    }

    @Test
    void completesQuestNearEastPortal() {
        org.junit.jupiter.api.Assertions.assertTrue(run(211060400, new Point(100, 164)));
    }

    @Test
    void doesNothingFarAwayOrInOtherMap() {
        org.junit.jupiter.api.Assertions.assertFalse(run(211060400, new Point(-900, 164)));
        org.junit.jupiter.api.Assertions.assertFalse(run(100000000, new Point(100, 164)));
    }
}
