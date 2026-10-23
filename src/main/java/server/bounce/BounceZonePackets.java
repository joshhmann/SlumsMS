package server.bounce;

import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;

import java.util.List;

/**
 * LP {@code BOUNCE_ZONES_SYNC} (0x3737), read by slums.dll (src/bounce_rules.h DecodeZonesSync):
 *
 * <pre>
 * byte  version = 1
 * int   nativeBounce        the Ninja Bounce of the character's job family (1050 / 10001050 / 20001050)
 * short n, n x (int first, int last)   maps where the universal bounces are refused (inclusive ranges)
 * short m, m x (int first, int last)   maps where Flash Jump 4111006 / 14101004 is refused too
 * </pre>
 *
 * The ranges are the effective (enabled, merged) set of {@link BounceZones}; it replaces the client's built-in
 * jump-quest list. Little-endian like every v83 packet.
 *
 * <p>A second packet on the same opcode, {@link #impulses} (version 2), carries the bounce strengths
 * ({@link BounceImpulses}). It is separate rather than a trailer because the version 1 decoder in shipped clients
 * (1.5.0-rc4 and older) rejects a packet with trailing bytes outright, which would cost those clients their zones;
 * they reject the version 2 packet the same way and keep their zones and today's strengths.
 */
public final class BounceZonePackets {

    static final byte VERSION = 1;
    static final byte IMPULSES_VERSION = 2;
    /** The client refuses more; far beyond what the yaml needs (all four categories are ~400 ranges). */
    static final int MAX_RANGES = 4096;

    private BounceZonePackets() {
    }

    public static Packet sync(BounceZones zones, int nativeBounce) {
        OutPacket p = OutPacket.create(SendOpcode.BOUNCE_ZONES_SYNC);
        p.writeByte(VERSION);
        p.writeInt(nativeBounce);
        ranges(p, zones.bounceRanges());
        ranges(p, zones.flashJumpRanges());
        return p;
    }

    /**
     * <pre>
     * byte  version = 2
     * byte  count = 4
     * 4 x (int leftVx, int rightVx, int vy)   ninja native, ninja other, vertical native, vertical other
     * </pre>
     */
    public static Packet impulses(BounceImpulses imp) {
        OutPacket p = OutPacket.create(SendOpcode.BOUNCE_ZONES_SYNC);
        p.writeByte(IMPULSES_VERSION);
        p.writeByte(4);
        for (BounceImpulses.Impulse i : List.of(imp.ninjaNative(), imp.ninjaOther(), imp.verticalNative(), imp.verticalOther())) {
            p.writeInt(i.leftVx());
            p.writeInt(i.rightVx());
            p.writeInt(i.vy());
        }
        return p;
    }

    private static void ranges(OutPacket p, List<BounceZones.MapRange> ranges) {
        int n = Math.min(ranges.size(), MAX_RANGES);
        p.writeShort(n);
        for (int i = 0; i < n; i++) {
            p.writeInt(ranges.get(i).first());
            p.writeInt(ranges.get(i).last());
        }
    }
}
