package net.server.handlers.login;

import config.ServerConfig;
import io.netty.buffer.Unpooled;
import net.packet.ByteBufInPacket;
import net.packet.ByteBufOutPacket;
import net.packet.InPacket;
import net.packet.OutPacket;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientVersionGateTest {

    private static ServerConfig config(int minimum, boolean gmBypass) {
        ServerConfig config = new ServerConfig();
        config.MIN_CLIENT_BUILD = minimum;
        config.MIN_CLIENT_BUILD_GM_BYPASS = gmBypass;
        return config;
    }

    /** The body slums.dll sends after the opcode. */
    private static InPacket stamp(int format, int build, String release) {
        OutPacket out = new ByteBufOutPacket();
        out.writeShort(format);
        out.writeInt(build);
        out.writeString(release);
        return new ByteBufInPacket(Unpooled.wrappedBuffer(out.getBytes()));
    }

    @Test
    void offByDefault() {
        ServerConfig defaults = new ServerConfig();
        assertEquals(0, defaults.MIN_CLIENT_BUILD);
        assertFalse(ClientVersionGate.outdated(0, 0, defaults));
        assertEquals("Your game is out of date: open the SlumsMS launcher to update.", ClientVersionGate.message(defaults));
    }

    @Test
    void aClientBelowTheMinimumOrWithoutAStampIsRefused() {
        ServerConfig config = config(10402, false);
        assertTrue(ClientVersionGate.outdated(0, 0, config));        // 1.4.1 and older: no stamp
        assertTrue(ClientVersionGate.outdated(10401, 0, config));
        assertFalse(ClientVersionGate.outdated(10402, 0, config));
        assertFalse(ClientVersionGate.outdated(10500, 0, config));
    }

    @Test
    void gmBypassOnlyWhenEnabled() {
        assertTrue(ClientVersionGate.outdated(0, 6, config(10402, false)));
        assertFalse(ClientVersionGate.outdated(0, 6, config(10402, true)));
        assertTrue(ClientVersionGate.outdated(0, 0, config(10402, true)));
    }

    @Test
    void blankMessageFallsBackToTheDefault() {
        ServerConfig config = config(1, false);
        config.CLIENT_UPDATE_MESSAGE = " ";
        assertEquals("Your game is out of date: open the SlumsMS launcher to update.", ClientVersionGate.message(config));
    }

    @Test
    void stampParses() {
        ClientBuildStampHandler.Stamp s = ClientBuildStampHandler.parse(stamp(1, 10402, "1.4.2"));
        assertEquals(10402, s.build());
        assertEquals("1.4.2", s.release());
        assertEquals("1.4.2-rc1", ClientBuildStampHandler.parse(stamp(1, 10402, "1.4.2-rc1")).release());
    }

    /** slums.dll's tests/buildstamp_proto_test.cpp writes exactly these bytes: change both together. */
    @Test
    void goldenBytesFromTheClient() {
        byte[] golden = java.util.HexFormat.of().parseHex("0100a22800000500312e342e32");
        ClientBuildStampHandler.Stamp s = ClientBuildStampHandler.parse(new ByteBufInPacket(Unpooled.wrappedBuffer(golden)));
        assertEquals(10402, s.build());
        assertEquals("1.4.2", s.release());
    }

    @Test
    void malformedOrUnknownStampsAreIgnored() {
        assertNull(ClientBuildStampHandler.parse(stamp(3, 10402, "1.4.2")));                    // unknown format
        assertNull(ClientBuildStampHandler.parse(stamp(2, 10402, "1.4.2")));                    // format 2 without its caps
        assertNull(ClientBuildStampHandler.parse(stamp(1, -5, "1.4.2")));                       // negative build
        assertNull(ClientBuildStampHandler.parse(stamp(1, 10402, "x".repeat(41))));             // release too long
        assertNull(ClientBuildStampHandler.parse(new ByteBufInPacket(Unpooled.wrappedBuffer(new byte[]{1, 0, 2}))));
        byte[] truncated = stamp(1, 10402, "1.4.2").getBytes();
        byte[] cut = java.util.Arrays.copyOf(truncated, truncated.length - 2);
        assertNull(ClientBuildStampHandler.parse(new ByteBufInPacket(Unpooled.wrappedBuffer(cut))));
        // Control characters never reach the log.
        assertEquals("1.4.2", ClientBuildStampHandler.parse(stamp(1, 10402, "1.4.2\n")).release());
    }

    @Test
    void formatTwoCarriesCaps() {
        // slums.dll 1.6.1-rc5 (10650): short 2, int build, short len + ASCII release, int caps (bit 0: custom NPC data)
        byte[] golden = java.util.HexFormat.of().parseHex("0200a22800000500312e342e3201000000");
        ClientBuildStampHandler.Stamp s = ClientBuildStampHandler.parse(new ByteBufInPacket(Unpooled.wrappedBuffer(golden)));
        assertEquals(2, s.format());
        assertEquals(10402, s.build());
        assertEquals("1.4.2", s.release());
        assertEquals(1, s.caps());

        byte[] noCaps = java.util.HexFormat.of().parseHex("0200a22800000500312e342e3200000000");
        assertEquals(0, ClientBuildStampHandler.parse(new ByteBufInPacket(Unpooled.wrappedBuffer(noCaps))).caps());
    }

    @Test
    void formatOneStillParsesWithNoCaps() {
        ClientBuildStampHandler.Stamp s = ClientBuildStampHandler.parse(stamp(1, 10601, "1.6.0.2"));
        assertEquals(1, s.format());
        assertEquals(0, s.caps());
    }
}
