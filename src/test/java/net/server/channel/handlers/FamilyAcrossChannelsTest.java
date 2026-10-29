package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import client.Family;
import client.FamilyEntitlement;
import client.FamilyEntry;
import client.FamilyTravel;
import config.YamlConfig;
import constants.id.MapId;
import net.packet.InPacket;
import net.server.PlayerStorage;
import net.server.channel.Channel;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import tools.PacketCreator;

import java.awt.Point;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.1 promise audit: Family Reunion to a member on another channel (a regular, or a player) did nothing: the
 * handler only looked on the user's channel. It now changes the user's channel and lands them on the member's map,
 * as the original game's Family did (no "different channel" result code exists in the v83 Family results). An
 * accepted Summon from another channel does the same for the summoned player (stock warped them onto another channel's
 * map instance). A channel change that is refused gives everything back.
 */
class FamilyAcrossChannelsTest {

    private final boolean familyWas = YamlConfig.config.server.USE_FAMILY_SYSTEM;
    static final int ME = 101, BOT = 1_000_000_321;

    Family family;
    FamilyEntry entry;
    Character me;
    Client c;
    PlayerStorage channelStorage, worldStorage;

    @BeforeEach
    void setUp() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        family = mock(Family.class);
        entry = mock(FamilyEntry.class);
        when(entry.getReputation()).thenReturn(5_000);
        when(entry.useEntitlement(any())).thenReturn(true);
        MapleMap ownMap = mock(MapleMap.class);
        when(ownMap.getForcedReturnId()).thenReturn(MapId.NONE);
        me = mock(Character.class);
        when(me.getId()).thenReturn(ME);
        when(me.getFamilyEntry()).thenReturn(entry);
        when(me.getFamily()).thenReturn(family);
        when(me.getMap()).thenReturn(ownMap);
        when(me.getMapId()).thenReturn(100000000);
        when(me.isAlive()).thenReturn(true);
        when(entry.getChr()).thenReturn(me);
        channelStorage = mock(PlayerStorage.class);
        worldStorage = mock(PlayerStorage.class);
        Channel ch = mock(Channel.class);
        when(ch.getPlayerStorage()).thenReturn(channelStorage);
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(worldStorage);
        c = mock(Client.class);
        when(c.getPlayer()).thenReturn(me);
        when(c.getChannelServer()).thenReturn(ch);
        when(c.getWorldServer()).thenReturn(world);
        when(c.getChannel()).thenReturn(1);
        when(me.getClient()).thenReturn(c);
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = familyWas;
        FamilyTravel.cancel(ME);
        FamilyTravel.cancel(202);
        InviteCoordinator.removePlayerIncomingInvites(202);
    }

    Character regularOnChannel3() {
        Character bot = mock(Character.class);
        BotClient bc = mock(BotClient.class);
        when(bc.getChannel()).thenReturn(3);
        when(bot.getClient()).thenReturn(bc);
        when(bot.getFamily()).thenReturn(family);
        MapleMap botMap = mock(MapleMap.class);
        when(botMap.getId()).thenReturn(104040000);
        when(botMap.getForcedReturnId()).thenReturn(MapId.NONE);
        when(bot.getMap()).thenReturn(botMap);
        when(bot.getPosition()).thenReturn(new Point(300, 150));
        when(bot.getId()).thenReturn(BOT);
        when(bot.getName()).thenReturn("Reggie");
        when(worldStorage.getCharacterByName("Reggie")).thenReturn(bot);
        return bot;
    }

    void use(FamilyEntitlement e, String name) {
        InPacket p = mock(InPacket.class);
        when(p.readInt()).thenReturn(e.ordinal());
        when(p.readString()).thenReturn(name);
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class)) {
            new FamilyUseHandler().handlePacket(p, c);
        }
    }

    @Test
    void reunionToARegularOnAnotherChannelChangesChannelAndLandsOnItsMap() {
        regularOnChannel3();
        when(c.changeChannel(3)).thenReturn(true);
        use(FamilyEntitlement.FAMILY_REUINION, "Reggie");
        verify(c).changeChannel(3);
        verify(entry).useEntitlement(FamilyEntitlement.FAMILY_REUINION);
        verify(me, never()).changeMap(any(MapleMap.class), any(server.maps.Portal.class));
        FamilyTravel.Arrival a = FamilyTravel.take(ME, 3, System.currentTimeMillis());
        assertNotNull(a, "the new channel lands the player on the regular's map");
        assertEquals(104040000, a.mapId());
        assertEquals(new Point(300, 150), a.at());
        assertNull(FamilyTravel.take(ME, 3, System.currentTimeMillis()), "used once");
    }

    @Test
    void aRefusedChannelChangeGivesTheReputationBack() {
        regularOnChannel3();
        when(c.changeChannel(3)).thenReturn(false);
        use(FamilyEntitlement.FAMILY_REUINION, "Reggie");
        verify(entry).useEntitlement(FamilyEntitlement.FAMILY_REUINION);
        verify(entry).refundEntitlement(FamilyEntitlement.FAMILY_REUINION);
        verify(entry).gainReputation(FamilyEntitlement.FAMILY_REUINION.getRepCost(), false);
        verify(me).dropMessage(eq(5), contains("Nothing was spent"));
        assertNull(FamilyTravel.take(ME, 3, System.currentTimeMillis()));
    }

    @Test
    void aDeadPlayerIsRefusedBeforeItCosts() {
        regularOnChannel3();
        when(me.isAlive()).thenReturn(false);
        use(FamilyEntitlement.FAMILY_REUINION, "Reggie");
        verify(entry, never()).useEntitlement(any());
        verify(c, never()).changeChannel(anyInt());
        verify(me).dropMessage(eq(5), contains("can't change channel"));
    }

    @Test
    void anArrivalIsForTheChannelItWasMadeForAndExpires() {
        long now = 5_000_000L;
        FamilyTravel.expect(ME, 2, 910000000, new Point(1, 2), now);
        assertNull(FamilyTravel.take(ME, 3, now), "another channel");
        FamilyTravel.expect(ME, 2, 910000000, new Point(1, 2), now);
        assertNull(FamilyTravel.take(ME, 2, now + 61_000), "too late");
        FamilyTravel.expect(ME, 2, 910000000, null, now);
        assertEquals(910000000, FamilyTravel.take(ME, 2, now + 1_000).mapId());
    }

    @Test
    void anAcceptedSummonFromAnotherChannelChangesTheJuniorsChannel() {
        // the summoner (me, channel 1) asked Pal (channel 2); Pal clicks Yes
        MapleMap summonMap = me.getMap();
        when(summonMap.getId()).thenReturn(100000000);
        when(me.getPosition()).thenReturn(new Point(10, 20));
        when(me.isLoggedinWorld()).thenReturn(true);
        Character pal = mock(Character.class);
        Client pc = mock(Client.class);
        when(pc.getChannel()).thenReturn(2);
        when(pc.changeChannel(1)).thenReturn(true);
        when(pal.getClient()).thenReturn(pc);
        when(pal.getId()).thenReturn(202);
        when(pal.getName()).thenReturn("Pal");
        InviteCoordinator.createInvite(InviteType.FAMILY_SUMMON, me, pal, 202, summonMap);
        assertTrue(FamilySummonResponseHandler.answer(pal, true));
        verify(pc).changeChannel(1);
        verify(pal, never()).changeMap(any(MapleMap.class), any(server.maps.Portal.class));
        FamilyTravel.Arrival a = FamilyTravel.take(202, 1, System.currentTimeMillis());
        assertNotNull(a);
        assertEquals(100000000, a.mapId());
        verify(entry, never()).refundEntitlement(any());
    }
}
