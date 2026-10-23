package server.bounce;

import com.esotericsoftware.yamlbeans.YamlReader;
import io.netty.buffer.Unpooled;
import net.opcodes.SendOpcode;
import net.packet.ByteBufInPacket;
import net.packet.InPacket;
import net.packet.Packet;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Golden bytes of LP BOUNCE_ZONES_SYNC. The client's tests/bounce_rules_test.cpp decodes the same hex strings:
 * change both together (tools/bounce/gen_client_zones.py --golden prints the shipped one).
 */
class BounceZonePacketsTest {

    /** The shipped bounce-zones.yaml (only jump_quests on), native bounce 1050. */
    static final String SHIPPED = "3737011a0400001000cae1f505cae1f505a4230506a823050644ab23064dab2306b6c94206bcc9420671aa7f0603ac7f0680d17f0684d17f0606ef1c0d06ef1c0d20c4b01021c4b0101448453614484536809af436809af4360044f8361344f836283d6a3a953d6a3a10416a3a7d416a3af8446a3a65456a3ae0486a3a48496a3af8dc3d3bf9dc3d3b1000cae1f505cae1f505a4230506a823050644ab23064dab2306b6c94206bcc9420671aa7f0603ac7f0680d17f0684d17f0606ef1c0d06ef1c0d20c4b01021c4b0101448453614484536809af436809af4360044f8361344f836283d6a3a953d6a3a10416a3a7d416a3af8446a3a65456a3ae0486a3a48496a3af8dc3d3bf9dc3d3b";

    /** Two bounce ranges, one Flash Jump range, a Cygnus (10001050). */
    static final String SMALL = "3737019a9a98000200a4230506a823050620c4b01021c4b0100100a4230506a8230506";

    /** The default bounce strengths (and the shipped yaml's): ninja -683/606/-688 and -350/350/-250, vertical -1/0/-1200 and -1/0/-750. */
    static final String IMPULSES_DEFAULT = "3737020455fdffff5e02000050fdffffa2feffff5e01000006ffffffffffffff0000000050fbffffffffffff0000000012fdffff";

    /** ninja -700/600/-700 and -300/300/-200, vertical -2/0/-1100 and 0/0/-500. */
    static final String IMPULSES_CUSTOM = "3737020444fdffff5802000044fdffffd4feffff2c01000038fffffffeffffff00000000b4fbffff00000000000000000cfeffff";

    private static String hex(Packet p) {
        return HexFormat.of().formatHex(p.getBytes());
    }

    @Test
    void shippedFile() throws Exception {
        BounceZones z = BounceZones.parse(new YamlReader(Files.newBufferedReader(Path.of(BounceZones.DATA_PATH))).read(),
                new ArrayList<>());
        assertEquals(SHIPPED, hex(BounceZonePackets.sync(z, 1050)));
    }

    @Test
    void smallSetGoldenAndLayout() throws Exception {
        BounceZones z = BounceZones.parse(new YamlReader(new StringReader("""
                categories:
                  - name: jq
                    enabled: true
                    block_flash_jump: true
                    maps: [101000100-101000104]
                  - name: lava
                    enabled: true
                    maps: [280020000-280020001]
                """)).read(), new ArrayList<>());
        Packet p = BounceZonePackets.sync(z, BounceZones.nativeBounce(1));
        assertEquals(SMALL, hex(p));

        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        assertEquals(0x3737, in.readShort());
        assertEquals(0x3737, SendOpcode.BOUNCE_ZONES_SYNC.getValue());
        assertEquals(1, in.readByte());
        assertEquals(10001050, in.readInt());
        assertEquals(2, in.readShort());
        assertEquals(List.of(101000100, 101000104, 280020000, 280020001),
                List.of(in.readInt(), in.readInt(), in.readInt(), in.readInt()));
        assertEquals(1, in.readShort());
        assertEquals(List.of(101000100, 101000104), List.of(in.readInt(), in.readInt()));
        assertEquals(0, in.available());
    }

    @Test
    void shippedImpulsesAreTheDefaults() throws Exception {
        List<String> problems = new ArrayList<>();
        BounceZones z = BounceZones.parse(new YamlReader(Files.newBufferedReader(Path.of(BounceZones.DATA_PATH))).read(),
                problems);
        assertEquals(List.of(), problems);
        assertEquals(BounceImpulses.DEFAULT, z.impulses());
        assertEquals(IMPULSES_DEFAULT, hex(BounceZonePackets.impulses(z.impulses())));
    }

    @Test
    void impulsesGoldenAndLayout() throws Exception {
        List<String> problems = new ArrayList<>();
        BounceZones z = BounceZones.parse(new YamlReader(new StringReader("""
                impulses:
                  ninja_bounce:
                    native: {left_vx: -700, right_vx: 600, vy: -700}
                    other: {left_vx: -300, right_vx: 300, vy: -200}
                  vertical_bounce:
                    native: {left_vx: -2, right_vx: 0, vy: -1100}
                    other: {left_vx: 0, right_vx: 0, vy: -500}
                categories: []
                """)).read(), problems);
        assertEquals(List.of(), problems);
        Packet p = BounceZonePackets.impulses(z.impulses());
        assertEquals(IMPULSES_CUSTOM, hex(p));

        InPacket in = new ByteBufInPacket(Unpooled.wrappedBuffer(p.getBytes()));
        assertEquals(0x3737, in.readShort());
        assertEquals(2, in.readByte());
        assertEquals(4, in.readByte());
        assertEquals(List.of(-700, 600, -700), List.of(in.readInt(), in.readInt(), in.readInt()));
        in.skip(36);
        assertEquals(0, in.available());
    }

    @Test
    void missingOrBadImpulsesKeepTheirDefaults() throws Exception {
        List<String> problems = new ArrayList<>();
        BounceZones z = BounceZones.parse(new YamlReader(new StringReader("""
                impulses:
                  ninja_bounce:
                    other: {vy: -300}
                  vertical_bounce:
                    other: {left_vx: 0, right_vx: 0, vy: -99999}
                categories: []
                """)).read(), problems);
        assertEquals(new BounceImpulses.Impulse(-350, 350, -300), z.impulses().ninjaOther());
        assertEquals(BounceImpulses.MAPLEROOT_NINJA, z.impulses().ninjaNative());
        assertEquals(BounceImpulses.SHORT_VERTICAL, z.impulses().verticalOther());
        assertEquals(1, problems.size(), problems.toString());

        assertEquals(BounceImpulses.DEFAULT, BounceZones.EMPTY.impulses());
        assertEquals(BounceImpulses.DEFAULT, BounceZones.parse(new YamlReader(new StringReader("categories: []")).read(),
                new ArrayList<>()).impulses());
    }

    @Test
    void nativeBounceFollowsTheJobFamily() {
        assertEquals(1050, BounceZones.nativeBounce(0));
        assertEquals(10001050, BounceZones.nativeBounce(1));
        assertEquals(20001050, BounceZones.nativeBounce(2));
        assertEquals(1050, BounceZones.nativeBounce(7));
    }

    @Test
    void emptyZonesStillMakeAPacket() {
        assertEquals("373701" + "1a040000" + "0000" + "0000", hex(BounceZonePackets.sync(BounceZones.EMPTY, 1050)));
    }
}
