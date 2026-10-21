package server.colorprism;

import client.Character;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.string.CharsetConstants;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Coloring Prism wire format, byte for byte what slums.dll {@code weapontint.cpp} sends and reads.
 * <p>
 * Opcodes: {@code WEAPON_TINT_ACTION} 0x372E (client to server), {@code WEAPON_TINT_SYNC} 0x372F (server
 * to client). Every action starts with an action byte:
 * <pre>
 * 0 request snapshot
 * 1 apply        invType(1) invPos(2) itemId(4) hue(2) chroma(1) bright(1) prismPos(2) layer(1)
 * 2 restore      invType(1) invPos(2) itemId(4) prismPos(2) layer(1)
 * 3 apply look   kind(1) hue(2) chroma(1) bright(1) prismPos(2)
 * 4 restore look kind(1) prismPos(2)
 * </pre>
 * Every reply starts with a subtype byte:
 * <pre>
 * 1 snapshot     count(1) count x entry                       the receiving player's own tints
 * 2 result       code(1)                                      answer to an apply / restore
 * 3 map table    players(1) players x { name(2+n) count(1) count x entry }   everyone in the map, by name
 * entry = key(4) hue(2, signed) chroma(1, signed) bright(1, signed)
 * </pre>
 * A KEY is an equip id (body tint), equip id + {@link #EFFECT_KEY_BIAS} (effect-layer tint), {@link #TINT_KEY_HAIR}
 * or {@link #TINT_KEY_FACE}. Only tinted keys are sent; the client treats anything absent as untinted, and each
 * snapshot / table replaces the previous one whole. Hues are absolute, -360..-1 ({@link Tint}).
 */
public final class ColorPrismPackets {
    public static final byte ACTION_REQUEST_SNAPSHOT = 0;
    public static final byte ACTION_APPLY = 1;
    public static final byte ACTION_RESTORE = 2;
    public static final byte ACTION_APPLY_LOOK = 3;
    public static final byte ACTION_RESTORE_LOOK = 4;

    public static final byte SUB_SNAPSHOT = 1;
    public static final byte SUB_RESULT = 2;
    public static final byte SUB_MAP_TABLE = 3;

    /** WeaponTintResult in weapontint.h; 0 is the client's "none" and is never sent. */
    public static final byte RESULT_OK = 1;
    public static final byte RESULT_NO_CASH_WEAPON = 2;
    public static final byte RESULT_NO_ITEM = 3;
    public static final byte RESULT_NOT_TINTED = 4;
    public static final byte RESULT_FAILED = 5;

    public static final int TINT_KEY_HAIR = 1;
    public static final int TINT_KEY_FACE = 2;
    public static final int EFFECT_KEY_BIAS = 10_000_000;

    public static final int LAYER_BODY = 0;
    public static final int LAYER_EFFECTS = 1;

    /** Counts are one byte on the wire. */
    static final int MAX_COUNT = 255;
    /** The client drops the whole table on a name outside 1..13 bytes, so such a player is left out. */
    static final int MAX_NAME_BYTES = 13;

    private ColorPrismPackets() {
    }

    /** One tinted key. */
    public record Entry(int key, short hue, byte chroma, byte bright) {
    }

    public static Packet snapshot(Character chr) {
        List<Entry> entries = ownEntries(chr);
        OutPacket p = OutPacket.create(SendOpcode.WEAPON_TINT_SYNC);
        p.writeByte(SUB_SNAPSHOT);
        writeEntries(p, entries);
        return p;
    }

    public static Packet result(byte code) {
        OutPacket p = OutPacket.create(SendOpcode.WEAPON_TINT_SYNC);
        p.writeByte(SUB_RESULT);
        p.writeByte(code);
        return p;
    }

    /** The table for every given player; players with nothing visible tinted, or an unusable name, are left out. */
    public static Packet mapTable(List<Character> players) {
        List<byte[]> names = new ArrayList<>();
        List<List<Entry>> tints = new ArrayList<>();
        for (Character chr : players) {
            if (names.size() == MAX_COUNT) {
                break;
            }
            if (!ColorPrism.isPlayer(chr)) {
                continue;
            }
            List<Entry> entries = visibleEntries(chr);
            byte[] name = chr.getName() == null ? new byte[0] : chr.getName().getBytes(CharsetConstants.CHARSET);
            if (entries.isEmpty() || name.length < 1 || name.length > MAX_NAME_BYTES) {
                continue;
            }
            names.add(name);
            tints.add(entries);
        }
        OutPacket p = OutPacket.create(SendOpcode.WEAPON_TINT_SYNC);
        p.writeByte(SUB_MAP_TABLE);
        p.writeByte(names.size());
        for (int i = 0; i < names.size(); i++) {
            p.writeShort(names.get(i).length);
            p.writeBytes(names.get(i));
            writeEntries(p, tints.get(i));
        }
        return p;
    }

    /**
     * Everything the player owns: worn equips first, then the Equip tab, then hair and face. The client keys by item
     * id, so when two copies of one id differ, the worn one wins (an untinted worn copy hides a tinted spare).
     */
    static List<Entry> ownEntries(Character chr) {
        Map<Integer, Entry> out = new LinkedHashMap<>();
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        addEquips(chr.getInventory(InventoryType.EQUIPPED), out, seen);
        addEquips(chr.getInventory(InventoryType.EQUIP), out, seen);
        addLook(chr, out);
        return new ArrayList<>(out.values());
    }

    /** What other players render: worn equips, hair and face. */
    static List<Entry> visibleEntries(Character chr) {
        Map<Integer, Entry> out = new LinkedHashMap<>();
        addEquips(chr.getInventory(InventoryType.EQUIPPED), out, new java.util.HashSet<>());
        addLook(chr, out);
        return new ArrayList<>(out.values());
    }

    private static void addEquips(Inventory inv, Map<Integer, Entry> out, java.util.Set<Integer> seen) {
        if (inv == null) {
            return;
        }
        List<Item> items;
        inv.lockInventory();
        try {
            items = new ArrayList<>(inv.list());   // a copy: other players' inventories are read from this thread too
        } finally {
            inv.unlockInventory();
        }
        for (Item item : items) {
            if (!(item instanceof Equip eq) || !seen.add(eq.getItemId())) {
                continue;
            }
            if (eq.isTinted()) {
                out.put(eq.getItemId(), new Entry(eq.getItemId(), eq.getTintHue(), eq.getTintChroma(), eq.getTintBright()));
            }
            if (eq.isFxTinted()) {
                int key = eq.getItemId() + EFFECT_KEY_BIAS;
                out.put(key, new Entry(key, eq.getTintFxHue(), eq.getTintFxChroma(), eq.getTintFxBright()));
            }
        }
    }

    private static void addLook(Character chr, Map<Integer, Entry> out) {
        if (chr.isHairTinted()) {
            out.put(TINT_KEY_HAIR, new Entry(TINT_KEY_HAIR, chr.getHairTintHue(), chr.getHairTintChroma(), chr.getHairTintBright()));
        }
        if (chr.isFaceTinted()) {
            out.put(TINT_KEY_FACE, new Entry(TINT_KEY_FACE, chr.getFaceTintHue(), chr.getFaceTintChroma(), chr.getFaceTintBright()));
        }
    }

    private static void writeEntries(OutPacket p, List<Entry> entries) {
        int n = Math.min(entries.size(), MAX_COUNT);
        p.writeByte(n);
        for (int i = 0; i < n; i++) {
            Entry e = entries.get(i);
            p.writeInt(e.key());
            p.writeShort(e.hue());
            p.writeByte(e.chroma());
            p.writeByte(e.bright());
        }
    }
}
