package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import client.Family;
import client.FamilyEntitlement;
import client.FamilyEntry;
import config.YamlConfig;
import constants.id.MapId;
import net.packet.InPacket;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.1 promise audit: Summon Family on a regular junior spent 500 reputation and did nothing (the bot can never answer
 * the request). It is refused, with a line, before anything is spent.
 */
class FamilySummonRegularTest {

    private final boolean familyWas = YamlConfig.config.server.USE_FAMILY_SYSTEM;

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = familyWas;
    }

    @Test
    void summoningARegularJuniorIsRefusedBeforeItCosts() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        Family family = mock(Family.class);
        FamilyEntry entry = mock(FamilyEntry.class);
        when(entry.getReputation()).thenReturn(5_000);
        when(entry.isEntitlementUsed(any())).thenReturn(false);
        when(entry.useEntitlement(any())).thenReturn(true);

        MapleMap ownMap = mock(MapleMap.class);
        when(ownMap.getForcedReturnId()).thenReturn(MapId.NONE);
        MapleMap botMap = mock(MapleMap.class);

        Character me = mock(Character.class);
        when(me.getFamilyEntry()).thenReturn(entry);
        when(me.getFamily()).thenReturn(family);
        when(me.getMap()).thenReturn(ownMap);
        when(me.getName()).thenReturn("Summoner");
        when(entry.getChr()).thenReturn(me);

        Character regular = mock(Character.class);
        when(regular.getClient()).thenReturn(mock(BotClient.class));
        when(regular.getFamily()).thenReturn(family);
        when(regular.getMap()).thenReturn(botMap);
        when(regular.getName()).thenReturn("Reggie");
        when(regular.getId()).thenReturn(1_000_000_123);

        PlayerStorage storage = mock(PlayerStorage.class);
        when(storage.getCharacterByName("Reggie")).thenReturn(regular);
        Channel ch = mock(Channel.class);
        when(ch.getPlayerStorage()).thenReturn(storage);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(me);
        when(c.getChannelServer()).thenReturn(ch);

        InPacket p = mock(InPacket.class);
        when(p.readInt()).thenReturn(FamilyEntitlement.SUMMON_FAMILY.ordinal());
        when(p.readString()).thenReturn("Reggie");

        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class)) {
            new FamilyUseHandler().handlePacket(p, c);
        }
        verify(entry, never()).useEntitlement(any());
        verify(entry, never()).gainReputation(anyInt(), anyBoolean());
        verify(regular, never()).sendPacket(any());
        verify(me).dropMessage(eq(5), contains("Reggie can't answer a Family summon"));
        assertNull(FamilyUseHandler.summonRefusal(me), "a player with a real client can answer");
    }
}
