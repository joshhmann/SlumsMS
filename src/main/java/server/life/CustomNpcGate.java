package server.life;

import config.ServerConfig;
import config.YamlConfig;
import net.packet.NpcOutPacket;
import net.packet.Packet;

/**
 * 1.6.1: custom NPCs (Slums-chan 9990000, the penguins 2985-2999) exist only in clients that carry their loose
 * Data/Npc files. A client without them that is sent their SPAWN_NPC hits STG_E_FILENOTFOUND and CWvsApp::Run ends:
 * an orderly disconnect about a second after entering the field (TEST, 2026-10-28, Henesys). So the session holds
 * those packets back from such a client (it never sees the NPC, so it can't click it either), and a talk window
 * framed by one is framed by Cody instead.
 * <p>
 * Who has the data: a client that sent build stamp format 2 or later says so in caps bit 0
 * ({@link #CAP_CUSTOM_NPC_DATA}); a format 1 stamp, or none, falls back to its build being at least
 * {@code CUSTOM_NPC_MIN_BUILD} (1.6.1-rc1 = 10610, the first client with the files).
 * This is the second safety net: on live, MIN_CLIENT_BUILD for 1.6.1 must be at least the 1.6.1 client's build anyway.
 */
public final class CustomNpcGate {

    /** Stamp caps bit 0: the client has the custom NPC data (9990000 and 2985-2999). */
    public static final int CAP_CUSTOM_NPC_DATA = 1;

    /** Frames a talk window for a client that can't show the custom NPC (every v83 client has him). */
    public static final int STAND_IN_NPC = 9200000;

    private CustomNpcGate() {
    }

    /** Whether the id is in CUSTOM_NPC_IDS ("9990000,2985-2999": ids and inclusive ranges, comma separated). */
    public static boolean isCustom(int npcId, String idList) {
        if (idList == null || idList.isBlank()) {
            return false;
        }
        for (String part : idList.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                int dash = s.indexOf('-', 1);
                if (dash > 0) {
                    int lo = Integer.parseInt(s.substring(0, dash).trim());
                    int hi = Integer.parseInt(s.substring(dash + 1).trim());
                    if (npcId >= lo && npcId <= hi) {
                        return true;
                    }
                } else if (Integer.parseInt(s) == npcId) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // a typo in the list gates nothing rather than everything
            }
        }
        return false;
    }

    /** Whether a client with this stamp has the custom NPC data. */
    public static boolean hasCustomNpcData(int stampFormat, int build, int caps, int minBuild) {
        if (stampFormat >= 2) {
            return (caps & CAP_CUSTOM_NPC_DATA) != 0;
        }
        return build >= minBuild;
    }

    /** Whether a client with this stamp may be sent this NPC template. */
    public static boolean visible(int npcId, int stampFormat, int build, int caps, ServerConfig config) {
        return !isCustom(npcId, config.CUSTOM_NPC_IDS)
                || hasCustomNpcData(stampFormat, build, caps, config.CUSTOM_NPC_MIN_BUILD);
    }

    /**
     * The packet to send this client in place of {@code packet}: the packet itself, a copy framed by
     * {@link #STAND_IN_NPC} (talk), or null to send nothing (spawn / controller).
     */
    public static Packet filter(Packet packet, int stampFormat, int build, int caps) {
        return filter(packet, stampFormat, build, caps, YamlConfig.config.server);
    }

    public static Packet filter(Packet packet, int stampFormat, int build, int caps, ServerConfig config) {
        if (!(packet instanceof NpcOutPacket npcPacket)
                || visible(npcPacket.npcTemplateId(), stampFormat, build, caps, config)) {
            return packet;
        }
        return npcPacket.withTemplate(STAND_IN_NPC);
    }
}
