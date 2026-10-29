package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import config.YamlConfig;
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
import soloMapling.ArtificialPlayer.BotFamily;
import soloMapling.ArtificialPlayer.BotFamilyTestSeams;
import tools.PacketCreator;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 1.6.1 promise audit, "now and then one asks to be yours": the regular whispers "add me as your junior" 30-120 s after
 * a chat ends, and the player then has to click Add Junior. Before, that only worked if the bot was still on the
 * player's map (stock "must be in the same map", and a channel-only lookup), and even then it said no one time in
 * five. Now an offer stands for 30 minutes from anywhere in the world, and the bot that asked says yes.
 */
class FamilyOfferFlowTest {

    private final boolean familyWas = YamlConfig.config.server.USE_FAMILY_SYSTEM;
    static final int ME = 101, BOT = 1_000_000_444;

    Character me, bot;
    Client c;
    PlayerStorage channelStorage, worldStorage;

    @BeforeEach
    void setUp() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = true;
        me = mock(Character.class);
        when(me.getId()).thenReturn(ME);
        when(me.getLevel()).thenReturn(40);
        when(me.getMap()).thenReturn(mock(MapleMap.class));
        bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getName()).thenReturn("Asker");
        when(bot.getLevel()).thenReturn(35);
        when(bot.isBot()).thenReturn(true);
        when(bot.getClient()).thenReturn(mock(BotClient.class));
        when(bot.getMap()).thenReturn(mock(MapleMap.class)); // it walked off to another map
        channelStorage = mock(PlayerStorage.class);
        worldStorage = mock(PlayerStorage.class);
        when(worldStorage.getCharacterByName("Asker")).thenReturn(bot);
        Channel ch = mock(Channel.class);
        when(ch.getPlayerStorage()).thenReturn(channelStorage);
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(worldStorage);
        c = mock(Client.class);
        when(c.getPlayer()).thenReturn(me);
        when(c.getChannelServer()).thenReturn(ch);
        when(c.getWorldServer()).thenReturn(world);
        BotFamilyTestSeams.install((b, p) -> null, (b, p) -> true);
        BotFamilyTestSeams.holdAnswers(); // the bot's answer is not part of this test
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_FAMILY_SYSTEM = familyWas;
        BotFamilyTestSeams.reset();
        BotFamilyTestSeams.forgetOffer(BOT, ME);
        InviteCoordinator.removePlayerIncomingInvites(BOT);
    }

    void addJunior(String name) {
        InPacket p = mock(InPacket.class);
        when(p.readString()).thenReturn(name);
        try (MockedStatic<PacketCreator> packets = mockStatic(PacketCreator.class)) {
            new FamilyAddHandler().handlePacket(p, c);
            packets.verify(() -> PacketCreator.sendFamilyMessage(anyInt(), anyInt()), atMost(1));
        }
    }

    @Test
    void aBotThatAskedCanBeAddedAfterItWalkedOff() {
        BotFamilyTestSeams.noteOffer(BOT, ME, System.currentTimeMillis() - 60_000);
        addJunior("Asker");
        assertTrue(InviteCoordinator.hasInvite(InviteType.FAMILY, BOT), "the invite reached the bot");
        verify(me).dropMessage("The invite has been sent.");
    }

    @Test
    void aBotThatDidNotAskStillNeedsTheSameMap() {
        addJunior("Asker");
        assertFalse(InviteCoordinator.hasInvite(InviteType.FAMILY, BOT));
    }

    @Test
    void anOfferLastsThirtyMinutes() {
        long now = System.currentTimeMillis();
        BotFamilyTestSeams.noteOffer(BOT, ME, now - 29 * 60_000L);
        assertTrue(BotFamily.offeredRecently(BOT, ME, now));
        BotFamilyTestSeams.noteOffer(BOT, ME, now - 31 * 60_000L);
        assertFalse(BotFamily.offeredRecently(BOT, ME, now));
        BotFamilyTestSeams.noteOffer(BOT, ME, now - 31 * 60_000L);
        addJunior("Asker");
        assertFalse(InviteCoordinator.hasInvite(InviteType.FAMILY, BOT), "an old offer is gone");
    }
}
