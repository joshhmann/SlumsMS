package server.monsterbook;

import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;

import java.util.Map;

/**
 * LP 0x3735 MONSTER_BOOK_RESULT, the reply to CP 0x3734 MONSTER_BOOK_QUERY. A leading type byte says which
 * query it answers; chances are parts per million after the asking player's rates, which the client prints as
 * {@code ppm / 10000.0} percent.
 *
 * <pre>
 * 0  int mobId  | short n | n x { int itemId, int ppm }   one mob's drop table, best first
 * 1  str query  | short n | n x { int itemId }            item-name search hits
 * 2  int itemId | short n | n x { int mobId, int ppm }    carded mobs that drop the item, best first
 * </pre>
 */
public final class MonsterBookPackets {
    public static final int TYPE_DROP_TABLE = 0;
    public static final int TYPE_ITEM_HITS = 1;
    public static final int TYPE_ITEM_DROPPERS = 2;

    private MonsterBookPackets() {
    }

    public static Packet dropTable(int mobId, Map<Integer, Integer> chancesPpm) {
        OutPacket p = OutPacket.create(SendOpcode.MONSTER_BOOK_RESULT);
        p.writeByte(TYPE_DROP_TABLE);
        p.writeInt(mobId);
        writePairs(p, chancesPpm);
        return p;
    }

    public static Packet itemHits(String query, int[] itemIds) {
        OutPacket p = OutPacket.create(SendOpcode.MONSTER_BOOK_RESULT);
        p.writeByte(TYPE_ITEM_HITS);
        p.writeString(query);
        p.writeShort(itemIds.length);
        for (int itemId : itemIds) {
            p.writeInt(itemId);
        }
        return p;
    }

    public static Packet itemDroppers(int itemId, Map<Integer, Integer> droppersPpm) {
        OutPacket p = OutPacket.create(SendOpcode.MONSTER_BOOK_RESULT);
        p.writeByte(TYPE_ITEM_DROPPERS);
        p.writeInt(itemId);
        writePairs(p, droppersPpm);
        return p;
    }

    private static void writePairs(OutPacket p, Map<Integer, Integer> pairs) {
        p.writeShort(pairs.size());
        for (Map.Entry<Integer, Integer> e : pairs.entrySet()) {
            p.writeInt(e.getKey());
            p.writeInt(e.getValue());
        }
    }
}
