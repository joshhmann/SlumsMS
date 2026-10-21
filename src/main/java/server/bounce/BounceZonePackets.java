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
 */
public final class BounceZonePackets {

    static final byte VERSION = 1;
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

    private static void ranges(OutPacket p, List<BounceZones.MapRange> ranges) {
        int n = Math.min(ranges.size(), MAX_RANGES);
        p.writeShort(n);
        for (int i = 0; i < n; i++) {
            p.writeInt(ranges.get(i).first());
            p.writeInt(ranges.get(i).last());
        }
    }
}
