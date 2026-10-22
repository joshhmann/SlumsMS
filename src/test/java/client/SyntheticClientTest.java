package client;

import net.opcodes.SendOpcode;
import net.packet.ByteBufOutPacket;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.autoqa.AutoQaLine;
import tools.PacketCreator;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyntheticClientTest {

    @Test
    void itIsARealPlayersClientNotABotClient() {
        SyntheticClient c = new SyntheticClient(1, 0, 1);
        assertFalse(BotClient.class.isAssignableFrom(c.getClass()));
        Character chr = Character.getDefault(c);
        assertFalse(BotHelpers.isBot(chr), "bots must treat a synthetic player as a person");
    }

    @Test
    void itHearsWhatATesterReads() {
        SyntheticClient c = new SyntheticClient(1, 0, 1);
        long t = System.currentTimeMillis();
        c.sendPacket(PacketCreator.getChatText(1000000042, "hey lol", false, 0));
        c.sendPacket(PacketCreator.serverNotice(5, "pink line"));
        c.sendPacket(PacketCreator.serverNotice(4, "ticker line"));
        c.sendPacket(PacketCreator.sendYellowTip("QA af-1 running: test"));
        c.sendPacket(PacketCreator.multiChat("Bob", "where r u", 0));
        c.sendPacket(PacketCreator.getWhisperReceive("Bob", 1, false, "psst"));
        List<AutoQaLine> h = c.heardSince(t);
        assertEquals(List.of("CHAT #1000000042: hey lol", "NOTICE [5] pink line", "NOTICE [4] ticker line",
                "YELLOW QA af-1 running: test", "MULTICHAT buddy Bob: where r u", "WHISPER Bob: psst"),
                h.stream().map(AutoQaLine::text).toList());
        assertTrue(h.stream().allMatch(AutoQaLine::isHeard));
    }

    @Test
    void itRemembersInvitesSoTheRunnerCanAnswerThem() {
        SyntheticClient c = new SyntheticClient(2, 0, 1);
        ByteBufOutPacket party = new ByteBufOutPacket(SendOpcode.PARTY_OPERATION);
        party.writeByte(4);
        party.writeInt(77);
        party.writeString("Bob");
        party.writeByte(0);
        c.sendPacket(party);
        ByteBufOutPacket guild = new ByteBufOutPacket(SendOpcode.GUILD_OPERATION);
        guild.writeByte(5);
        guild.writeInt(12);
        guild.writeString("Officer");
        c.sendPacket(guild);
        ByteBufOutPacket propose = new ByteBufOutPacket(SendOpcode.MARRIAGE_REQUEST);
        propose.writeByte(0);
        propose.writeString("AutoQaOne");
        propose.writeInt(5);
        c.sendPacket(propose);
        assertEquals(77, c.lastPartyInviteId());
        assertEquals(12, c.lastGuildInviteId());
        assertEquals("AutoQaOne", c.lastProposer());
        assertEquals(5, c.lastProposerId());
        assertEquals(List.of("PARTY_INVITE Bob", "GUILD_INVITE Officer", "PROPOSAL AutoQaOne"),
                c.heardSince(0).stream().map(AutoQaLine::text).toList());
    }

    @Test
    void spouseChatSkipsItsModeBytes() {
        SyntheticClient c = new SyntheticClient(1, 0, 1);
        c.sendPacket(PacketCreator.OnCoupleMessage("AutoQaOne", "hi love", true));
        assertEquals(List.of("SPOUSE_CHAT AutoQaOne: hi love"), c.heardSince(0).stream().map(AutoQaLine::text).toList());
    }

    @Test
    void npcTalkIsReadWithoutItsColourCodes() {
        SyntheticClient c = new SyntheticClient(1, 0, 1);
        c.sendPacket(PacketCreator.getNPCTalk(9010000, (byte) 0, "The #bHenesys#k Gachapon:\r\n#rCOMMON#k 70%", "00 00", (byte) 0));
        assertEquals(List.of("NPC_TALK 9010000: The Henesys Gachapon: COMMON 70%"),
                c.heardSince(0).stream().map(AutoQaLine::text).toList());
    }

    @Test
    void otherPacketsAndGarbageAreIgnored() {
        SyntheticClient c = new SyntheticClient(1, 0, 1);
        c.sendPacket(() -> new byte[]{(byte) 0xA2, 0, 1}); // a truncated chat packet
        c.sendPacket(() -> new byte[]{0x10, 0x00, 1, 2, 3});
        c.sendPacket(null);
        assertTrue(c.heardSince(0).isEmpty());
        c.close();
        c.sendPacket(PacketCreator.serverNotice(5, "after logout"));
        assertTrue(c.heardSince(0).isEmpty());
    }
}
