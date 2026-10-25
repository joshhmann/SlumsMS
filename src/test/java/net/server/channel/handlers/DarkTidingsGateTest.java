package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.autoban.AutobanManager;
import client.status.MonsterStatusEffect;
import net.packet.InPacket;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2: SpecialMoveHandler froze every mob on the map for Dark Tidings (1321016, "za warudo") before it checked
 * that the caster has the skill at the level sent, so a packet from anyone froze the map.
 */
class DarkTidingsGateTest {

    @Test
    void aCasterWithoutTheSkillFreezesNothing() {
        Client c = mock(Client.class);
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Monster mob = mock(Monster.class);
        when(c.getPlayer()).thenReturn(chr);
        when(chr.getAutobanManager()).thenReturn(mock(AutobanManager.class));
        when(chr.getMap()).thenReturn(map);
        when(map.getAllMonsters()).thenReturn(List.of(mob));
        // no skill level: getSkillLevel answers 0

        InPacket p = mock(InPacket.class);
        when(p.readInt()).thenReturn(0, SpecialMoveHandler.DARK_TIDINGS);
        when(p.readByte()).thenReturn((byte) 10);

        new SpecialMoveHandler().handlePacket(p, c);

        verify(mob, never()).applyStatus(any(), any(MonsterStatusEffect.class), anyBoolean(), anyLong(), anyBoolean(), anyBoolean());
    }

    @Test
    void theFreezeStillReachesEveryMobOnTheMap() {
        Character chr = mock(Character.class);
        MapleMap map = mock(MapleMap.class);
        Monster a = mock(Monster.class), b = mock(Monster.class);
        when(chr.getMap()).thenReturn(map);
        when(map.getAllMonsters()).thenReturn(List.of(a, b));

        SpecialMoveHandler.freezeMapForDarkTidings(chr);

        verify(a, times(1)).applyStatus(any(), any(MonsterStatusEffect.class), anyBoolean(), anyLong(), anyBoolean(), anyBoolean());
        verify(b, times(1)).applyStatus(any(), any(MonsterStatusEffect.class), anyBoolean(), anyLong(), anyBoolean(), anyBoolean());
    }
}
