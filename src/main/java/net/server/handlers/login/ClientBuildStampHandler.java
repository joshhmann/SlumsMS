package net.server.handlers.login;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;

/**
 * CP 0x3738 CLIENT_BUILD_STAMP: slums.dll reports its build right before every CP_CheckPassword (same connection, so
 * it is always handled first). Layout: short format (1 or 2), int build (monotonic, e.g. 10402 for 1.4.2), string
 * release (e.g. "1.4.2"); format 2 (1.6.1) adds int caps (bit 0: the client has the custom NPC data, see
 * {@link server.life.CustomNpcGate}). No reply. A client without the stamp (1.4.1 and older) never sends it and stays at build 0.
 * Malformed or unknown formats are ignored rather than trusted.
 */
public final class ClientBuildStampHandler extends AbstractPacketHandler {
    static final int FORMAT = 1;
    static final int FORMAT_CAPS = 2;
    static final int MAX_RELEASE_LENGTH = 40;

    @Override
    public boolean validateState(Client c) {
        return !c.isLoggedIn();
    }

    @Override
    public void handlePacket(InPacket p, Client c) {
        Stamp stamp = parse(p);
        if (stamp != null) {
            c.setClientStamp(stamp.format(), stamp.build(), stamp.release(), stamp.caps());
        }
    }

    record Stamp(int format, int build, String release, int caps) {
        Stamp(int build, String release) {
            this(FORMAT, build, release, 0);
        }
    }

    static Stamp parse(InPacket p) {
        if (p.available() < 2 + 4 + 2) {
            return null;
        }
        int format = p.readShort();
        if (format != FORMAT && format != FORMAT_CAPS) {
            return null;
        }
        int build = p.readInt();
        if (build < 0) {
            return null;
        }
        if (p.available() < 2) {
            return null;
        }
        int start = p.getPosition();
        int length = p.readShort();
        p.seek(start);
        if (length < 0 || length > MAX_RELEASE_LENGTH || p.available() < 2 + length) {
            return null;
        }
        String release = p.readString().replaceAll("[^0-9A-Za-z._+-]", "");
        int caps = 0;
        if (format == FORMAT_CAPS) {
            if (p.available() < 4) {
                return null;
            }
            caps = p.readInt();
        }
        return new Stamp(format, build, release, caps);
    }
}
