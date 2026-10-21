package soloMapling.ArtificialPlayer;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.maps.MapObject;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.4.1: bots kept noticing a GM in /hide (greetings, replies, attention, gathering). */
class BotHiddenGmTest {

    private static Character person(int id, boolean hidden) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("p" + id);
        when(chr.getClient()).thenReturn(mock(Client.class));
        when(chr.isHidden()).thenReturn(hidden);
        return chr;
    }

    @Test
    void aHiddenGmIsNotAVisibleRealPlayer() {
        assertTrue(BotHelpers.isVisibleRealPlayer(person(1, false)));
        assertFalse(BotHelpers.isVisibleRealPlayer(person(2, true)));
        Character bot = mock(Character.class);
        when(bot.getClient()).thenReturn(new BotClient(0, 0));
        assertFalse(BotHelpers.isVisibleRealPlayer(bot));
        assertFalse(BotHelpers.isVisibleRealPlayer(null));
    }

    @Test
    void botsLookingAroundDoNotSeeAHiddenGm() {
        Character visible = person(1, false);
        Character gm = person(2, true);
        Character me = mock(Character.class);
        when(me.getId()).thenReturn(99);
        when(me.getClient()).thenReturn(new BotClient(0, 0));
        when(me.getPosition()).thenReturn(new Point(0, 0));
        MapleMap map = mock(MapleMap.class);
        when(me.getMap()).thenReturn(map);
        when(map.getMapObjectsInRect(any(), any())).thenReturn(List.<MapObject>of(gm, visible, me));

        assertEquals(List.of(visible), BotLogic.getRealPlayersInRange(me, 400, 200));
        assertEquals(visible, BotLogic.waitForPlayerInRange(me, 400, 200));

        when(map.getMapObjectsInRect(any(), any())).thenReturn(List.<MapObject>of(gm, me));
        assertTrue(BotLogic.getRealPlayersInRange(me, 400, 200).isEmpty());
        assertNull(BotLogic.waitForPlayerInRange(me, 400, 200));
    }
}
