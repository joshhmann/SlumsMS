package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import io.netty.buffer.Unpooled;
import net.opcodes.RecvOpcode;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.monsterbook.DropSearchService;
import tools.Pair;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ticket 19: the Monster Book query handler's wire format, its bot exclusion and its flood guard. */
class MonsterBookQueryHandlerTest {
    private static final int SNAIL = 100100;
    private static final int SHELL = 4000019;

    private static DropSearchService service() {
        return new DropSearchService(new DropSearchService.Source() {
            @Override
            public List<DropSearchService.DropRow> drops() {
                return List.of(new DropSearchService.DropRow(SNAIL, SHELL, 400_000));
            }

            @Override
            public Map<Integer, Integer> cardByMob() {
                return Map.of(SNAIL, 2380000);
            }

            @Override
            public List<Pair<Integer, String>> itemNames() {
                return List.of(new Pair<>(SHELL, "Snail Shell"));
            }

            @Override
            public boolean isBoss(int mobId) {
                return false;
            }
        });
    }

    private static Character player() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(7);
        when(chr.getDropRate()).thenReturn(2);
        when(chr.getBossDropRate()).thenReturn(1);
        when(chr.getCardRate(anyInt())).thenReturn(1.0f);
        return chr;
    }

    private static Client clientOf(Character chr) {
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        return c;
    }

    private static InPacket query(int type, Object arg) {
        OutPacket out = new ByteBufOutPacket();
        out.writeByte(type);
        if (arg instanceof String s) {
            byte[] ascii = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            out.writeShort(ascii.length);
            out.writeBytes(ascii);
        } else {
            out.writeInt((Integer) arg);
        }
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    private static InPacket reply(Client c) {
        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(c).sendPacket(sent.capture());
        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(sent.getValue().getBytes()));
        assertEquals(SendOpcode.MONSTER_BOOK_RESULT.getValue(), in.readShort());
        return in;
    }

    @Test
    void opcodesAreThePairClaimedForTheMonsterBook() {
        assertEquals(0x3734, RecvOpcode.MONSTER_BOOK_QUERY.getValue());
        assertEquals(0x3735, SendOpcode.MONSTER_BOOK_RESULT.getValue());
    }

    @Test
    void dropTableReplyCarriesEachItemWithItsLiveChance() {
        Client c = clientOf(player());
        new MonsterBookQueryHandler(service()).handlePacket(query(0, SNAIL), c);
        InPacket in = reply(c);
        assertEquals(0, in.readByte());
        assertEquals(SNAIL, in.readInt());
        assertEquals(1, in.readShort());
        assertEquals(SHELL, in.readInt());
        assertEquals(800_000, in.readInt());   // 400k x drop rate 2
        assertEquals(0, in.available());
    }

    @Test
    void itemSearchReplyEchoesTheTrimmedQuery() {
        Client c = clientOf(player());
        new MonsterBookQueryHandler(service()).handlePacket(query(1, "  shell "), c);
        InPacket in = reply(c);
        assertEquals(1, in.readByte());
        assertEquals("shell", in.readString());
        assertEquals(1, in.readShort());
        assertEquals(SHELL, in.readInt());
        assertEquals(0, in.available());
    }

    @Test
    void droppersReplyCarriesMobAndChancePairs() {
        Client c = clientOf(player());
        new MonsterBookQueryHandler(service()).handlePacket(query(2, SHELL), c);
        InPacket in = reply(c);
        assertEquals(2, in.readByte());
        assertEquals(SHELL, in.readInt());
        assertEquals(1, in.readShort());
        assertEquals(SNAIL, in.readInt());
        assertEquals(800_000, in.readInt());
        assertEquals(0, in.available());
    }

    @Test
    void botsAndCharacterlessClientsGetNoReply() {
        Character bot = player();
        BotClient botClient = mock(BotClient.class);
        when(bot.getClient()).thenReturn(botClient);
        Client c = clientOf(bot);
        new MonsterBookQueryHandler(service()).handlePacket(query(0, SNAIL), c);
        verify(c, never()).sendPacket(any());

        Client empty = clientOf(null);
        new MonsterBookQueryHandler(service()).handlePacket(query(0, SNAIL), empty);
        verify(empty, never()).sendPacket(any());
    }

    @Test
    void unknownTypesAreIgnored() {
        Client c = clientOf(player());
        new MonsterBookQueryHandler(service()).handlePacket(query(9, SNAIL), c);
        verify(c, never()).sendPacket(any());
    }

    @Test
    void aFloodIsCutAtTheLimitAndTheNextSecondIsServedAgain() {
        MonsterBookQueryHandler h = new MonsterBookQueryHandler(service());
        for (int i = 0; i < MonsterBookQueryHandler.MAX_QUERIES_PER_SECOND; i++) {
            assertTrue(h.allow(7, 1_000 + i));
        }
        assertFalse(h.allow(7, 1_500));
        assertTrue(h.allow(8, 1_500));     // another player is unaffected
        assertTrue(h.allow(7, 2_100));     // a new window
    }

    @Test
    void repliesAreSentOncePerQuery() {
        Client c = clientOf(player());
        MonsterBookQueryHandler h = new MonsterBookQueryHandler(service());
        h.handlePacket(query(0, SNAIL), c);
        h.handlePacket(query(2, SHELL), c);
        verify(c, times(2)).sendPacket(any());
    }
}
