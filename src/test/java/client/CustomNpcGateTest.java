package client;

import config.ServerConfig;
import io.netty.channel.embedded.EmbeddedChannel;
import net.opcodes.SendOpcode;
import net.packet.NpcOutPacket;
import net.packet.Packet;
import net.server.coordinator.session.ClientStampCarry;
import org.junit.jupiter.api.Test;
import server.life.CustomNpcGate;
import server.life.NPC;
import server.life.NPCStats;
import tools.PacketCreator;

import java.awt.Point;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.6.1, TEST 2026-10-28: a client without Npc/9990000.img (any 1.6.0.x) disconnected ~1.5 s after entering Henesys,
 * where Slums-chan stands (migration 042). Custom NPC packets must never reach a client without their data.
 */
class CustomNpcGateTest {

    private static final int SLUMS_CHAN = 9990000;
    private static final int PENGUIN = 2990;
    private static final int MAPLE_ADMIN = 9010000;

    private static NPC npc(int id) {
        NPC n = new NPC(id, new NPCStats("test"));
        n.setPosition(new Point(1215, 334));
        n.setCy(334);
        n.setFh(102);
        n.setRx0(1265);
        n.setRx1(1165);
        n.setObjectId(77);
        return n;
    }

    private static List<Packet> sent(int format, int build, int caps, Packet... packets) {
        Client c = Client.createMock();
        EmbeddedChannel ch = new EmbeddedChannel();
        c.useChannelForTest(ch);
        c.setClientStamp(format, build, "x", caps);
        for (Packet p : packets) {
            c.sendPacket(p);
        }
        List<Packet> out = new ArrayList<>();
        for (Object o; (o = ch.readOutbound()) != null; ) {
            out.add((Packet) o);
        }
        return out;
    }

    private static int intAt(byte[] b, int offset) {
        return ByteBuffer.wrap(b, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    @Test
    void anOldClientIsNeverSentACustomNpcSpawn() {
        NPC chan = npc(SLUMS_CHAN);
        assertTrue(sent(1, 10601, 0, PacketCreator.spawnNPC(chan), PacketCreator.spawnNPCRequestController(chan, true)).isEmpty());
        assertTrue(sent(0, 0, 0, PacketCreator.spawnNPC(npc(PENGUIN))).isEmpty());   // no stamp at all
        assertTrue(sent(2, 10650, 0, PacketCreator.spawnNPC(chan)).isEmpty());          // format 2 without caps bit 0
    }

    @Test
    void aClientWithTheDataSeesThem() {
        NPC chan = npc(SLUMS_CHAN);
        assertEquals(2, sent(1, 10610, 0, PacketCreator.spawnNPC(chan), PacketCreator.spawnNPCRequestController(chan, true)).size());
        assertEquals(1, sent(2, 10650, 1, PacketCreator.spawnNPC(npc(PENGUIN))).size());
        // format 2 trusts caps over the build number
        assertEquals(1, sent(2, 10601, 1, PacketCreator.spawnNPC(chan)).size());
    }

    @Test
    void ordinaryNpcsAreUnaffected() {
        Packet spawn = PacketCreator.spawnNPC(npc(MAPLE_ADMIN));
        List<Packet> out = sent(0, 0, 0, spawn, PacketCreator.spawnNPCRequestController(npc(MAPLE_ADMIN), true));
        assertEquals(2, out.size());
        assertSame(spawn, out.get(0));
    }

    @Test
    void spawnBytesAreUnchangedByTheTag() {
        byte[] b = PacketCreator.spawnNPC(npc(SLUMS_CHAN)).getBytes();
        assertEquals(SendOpcode.SPAWN_NPC.getValue(), (b[0] & 0xFF) | (b[1] & 0xFF) << 8);
        assertEquals(77, intAt(b, 2));
        assertEquals(SLUMS_CHAN, intAt(b, 6));
        assertEquals(2 + 4 + 4 + 2 + 2 + 1 + 2 + 2 + 2 + 1, b.length);
    }

    @Test
    void aTalkWindowFramedByACustomNpcIsFramedByCodyForAnOldClient() {
        Packet talk = PacketCreator.getNPCTalk(SLUMS_CHAN, (byte) 0, "hi", "00 00", (byte) 0);
        byte[] original = talk.getBytes();
        List<Packet> out = sent(1, 10601, 0, talk);
        assertEquals(1, out.size());
        byte[] b = out.get(0).getBytes();
        assertEquals(CustomNpcGate.STAND_IN_NPC, intAt(b, 3));
        assertEquals(original.length, b.length);
        original[3] = b[3]; original[4] = b[4]; original[5] = b[5]; original[6] = b[6];
        assertArrayEquals(original, b);                                   // only the template id changed

        assertSame(talk, sent(1, 10610, 0, talk).get(0));                 // a new client keeps her
        Packet quiz = PacketCreator.OnAskQuiz(4, SLUMS_CHAN, 0, "t", "p", "h", 1, 5, 30);
        assertEquals(CustomNpcGate.STAND_IN_NPC, intAt(sent(0, 0, 0, quiz).get(0).getBytes(), 3));
    }

    @Test
    void theIdListAndFallback() {
        ServerConfig config = new ServerConfig();
        assertTrue(CustomNpcGate.isCustom(9990000, config.CUSTOM_NPC_IDS));
        assertTrue(CustomNpcGate.isCustom(2985, config.CUSTOM_NPC_IDS));
        assertTrue(CustomNpcGate.isCustom(2999, config.CUSTOM_NPC_IDS));
        assertFalse(CustomNpcGate.isCustom(2984, config.CUSTOM_NPC_IDS));
        assertFalse(CustomNpcGate.isCustom(3000, config.CUSTOM_NPC_IDS));
        assertFalse(CustomNpcGate.isCustom(9200000, config.CUSTOM_NPC_IDS));
        assertFalse(CustomNpcGate.isCustom(9990000, ""));
        assertTrue(CustomNpcGate.isCustom(5, "x, 5"));                      // a typo gates nothing else
        assertEquals(10610, config.CUSTOM_NPC_MIN_BUILD);
        assertFalse(CustomNpcGate.hasCustomNpcData(1, 10609, 0, 10610));
        assertTrue(CustomNpcGate.hasCustomNpcData(1, 10610, 0, 10610));
        assertFalse(CustomNpcGate.hasCustomNpcData(2, 99999, 0, 10610));
        assertTrue(CustomNpcGate.hasCustomNpcData(2, 0, 1, 10610));
        assertNull(new NpcOutPacket(SendOpcode.SPAWN_NPC, SLUMS_CHAN, NpcOutPacket.NO_SUBSTITUTE).withTemplate(1));
    }

    @Test
    void theStampIsCarriedFromTheLoginSessionToTheChannelSession() {
        Client login = Client.createMock();
        login.setClientStamp(2, 10650, "1.6.1-rc5", 1);
        ClientStampCarry.remember(4242, login);
        Client channel = Client.createMock();
        ClientStampCarry.restore(4242, channel);
        assertEquals(2, channel.getClientStampFormat());
        assertEquals(10650, channel.getClientBuild());
        assertEquals("1.6.1-rc5", channel.getClientRelease());
        assertEquals(1, channel.getClientCaps());

        Client again = Client.createMock();
        ClientStampCarry.restore(4242, again);                            // used once
        assertEquals(0, again.getClientBuild());
    }
}
