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
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotFamilyTestSeams;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.1 promise audit, "every perk works": Summon Family on a regular (or an owned alt) junior. Before, the request
 * went to the bot, which can never answer it (then commit 1c8e03a81 refused it outright). Now the bot answers like a
 * player who clicks Yes: it comes to the summoner and the reputation stays spent; a bot in a commitment it can't drop
 * is refused before anything is spent; one that got busy in the moment before answering declines and the summoner
 * gets the reputation back. An owned alt comes to its owner and to nobody else. Drives the real handler packet.
 */
class FamilySummonRegularTest {

    private final boolean familyWas = YamlConfig.config.server.USE_FAMILY_SYSTEM;

    Family family;
    FamilyEntry entry;
    MapleMap ownMap;
    Character me;
    Client c;
    PlayerStorage channelStorage, worldStorage;
    final List<Character> cameTo = new ArrayList<>();
    String busy;

    @BeforeEach
    void setUp() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        family = mock(Family.class);
        entry = mock(FamilyEntry.class);
        when(entry.getReputation()).thenReturn(5_000);
        when(entry.isEntitlementUsed(any())).thenReturn(false);
        when(entry.useEntitlement(any())).thenReturn(true);

        ownMap = mock(MapleMap.class);
        when(ownMap.getForcedReturnId()).thenReturn(MapId.NONE);
        when(ownMap.getId()).thenReturn(100000000);

        me = mock(Character.class);
        when(me.getId()).thenReturn(101);
        when(me.getFamilyEntry()).thenReturn(entry);
        when(me.getFamily()).thenReturn(family);
        when(me.getMap()).thenReturn(ownMap);
        when(me.getName()).thenReturn("Summoner");
        when(me.isLoggedinWorld()).thenReturn(true);
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

        busy = null;
        BotFamilyTestSeams.install((bot, player) -> busy, (bot, player) -> {
            cameTo.add(bot);
            return true;
        });
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = familyWas;
        BotFamilyTestSeams.reset();
        InviteCoordinator.removePlayerIncomingInvites(1_000_000_123);
        InviteCoordinator.removePlayerIncomingInvites(202);
    }

    Character regular(int channel) {
        Character regular = mock(Character.class);
        BotClient bc = mock(BotClient.class);
        when(bc.getChannel()).thenReturn(channel);
        when(regular.getClient()).thenReturn(bc);
        when(regular.getFamily()).thenReturn(family);
        when(regular.getMap()).thenReturn(mock(MapleMap.class));
        when(regular.getName()).thenReturn("Reggie");
        when(regular.getId()).thenReturn(1_000_000_123);
        when(regular.isBot()).thenReturn(true);
        return regular;
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
    void aRegularJuniorAcceptsTheSummonAndComes() {
        Character reggie = regular(1);
        when(channelStorage.getCharacterByName("Reggie")).thenReturn(reggie);
        use(FamilyEntitlement.SUMMON_FAMILY, "Reggie");
        verify(entry).useEntitlement(FamilyEntitlement.SUMMON_FAMILY);
        verify(entry).gainReputation(-FamilyEntitlement.SUMMON_FAMILY.getRepCost(), false);
        assertEquals(List.of(reggie), cameTo, "the regular came to the summoner");
        verify(entry, never()).refundEntitlement(any());
        verify(me, never()).dropMessage(eq(5), contains("can't"));
        assertFalse(InviteCoordinator.hasInvite(InviteType.FAMILY_SUMMON, reggie.getId()), "answered");
    }

    @Test
    void aRegularOnAnotherChannelIsFoundAndComes() {
        Character reggie = regular(3);
        when(worldStorage.getCharacterByName("Reggie")).thenReturn(reggie);
        use(FamilyEntitlement.SUMMON_FAMILY, "Reggie");
        assertEquals(List.of(reggie), cameTo);
        verify(entry).useEntitlement(FamilyEntitlement.SUMMON_FAMILY);
    }

    @Test
    void aCommittedRegularIsRefusedBeforeItCosts() {
        busy = "in a party quest";
        Character reggie = regular(1);
        when(channelStorage.getCharacterByName("Reggie")).thenReturn(reggie);
        use(FamilyEntitlement.SUMMON_FAMILY, "Reggie");
        verify(entry, never()).useEntitlement(any());
        verify(entry, never()).gainReputation(anyInt(), anyBoolean());
        verify(me).dropMessage(eq(5), contains("Reggie can't come right now (in a party quest)"));
        assertTrue(cameTo.isEmpty());
        assertFalse(InviteCoordinator.hasInvite(InviteType.FAMILY_SUMMON, reggie.getId()));
    }

    @Test
    void aRegularThatGotBusyBeforeAnsweringDeclinesAndTheRepComesBack() {
        Character reggie = regular(1);
        when(channelStorage.getCharacterByName("Reggie")).thenReturn(reggie);
        BotFamilyTestSeams.holdAnswers();
        use(FamilyEntitlement.SUMMON_FAMILY, "Reggie");
        verify(entry).useEntitlement(FamilyEntitlement.SUMMON_FAMILY);
        busy = "in a scene";
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class)) {
            BotFamilyTestSeams.runHeldAnswers();
        }
        assertTrue(cameTo.isEmpty());
        verify(entry).refundEntitlement(FamilyEntitlement.SUMMON_FAMILY);
        verify(entry).gainReputation(FamilyEntitlement.SUMMON_FAMILY.getRepCost(), false);
        verify(me).dropMessage(eq(5), contains("Reggie has denied the summon request"));
    }

    @Test
    void anOwnedAltComesToItsOwnerOnlyAndNobodyElse() {
        Character alt = regular(1);
        when(alt.isBot()).thenReturn(false);
        when(alt.isOwnedAlt()).thenReturn(true);
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(555);
        when(owner.getName()).thenReturn("Owner");
        when(alt.getOwnedAltOwner()).thenReturn(owner);
        when(channelStorage.getCharacterByName("Reggie")).thenReturn(alt);
        use(FamilyEntitlement.SUMMON_FAMILY, "Reggie");
        verify(entry, never()).useEntitlement(any());
        verify(me).dropMessage(eq(5), contains("traveling with Owner"));

        assertNull(soloMapling.ArtificialPlayer.BotFamily.summonRefusal(alt, owner), "its owner may call it");
    }

    @Test
    void aPlayerJuniorStillGetsTheRequestInTheirClient() {
        Character pal = mock(Character.class);
        Client pc = mock(Client.class);
        when(pc.getChannel()).thenReturn(1);
        when(pal.getClient()).thenReturn(pc);
        when(pal.getFamily()).thenReturn(family);
        when(pal.getMap()).thenReturn(mock(MapleMap.class));
        when(pal.getId()).thenReturn(202);
        when(pal.isLoggedinWorld()).thenReturn(true);
        when(channelStorage.getCharacterByName("Pal")).thenReturn(pal);
        use(FamilyEntitlement.SUMMON_FAMILY, "Pal");
        verify(pal).sendPacket(any());
        verify(entry).useEntitlement(FamilyEntitlement.SUMMON_FAMILY);
        assertTrue(InviteCoordinator.hasInvite(InviteType.FAMILY_SUMMON, 202), "waits for their answer");
        assertTrue(cameTo.isEmpty());
    }
}
