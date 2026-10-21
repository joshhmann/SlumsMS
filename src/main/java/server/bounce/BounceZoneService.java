package server.bounce;

import client.Character;
import net.packet.Packet;
import net.server.Server;
import net.server.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.qa.QaLog;
import soloMapling.server.SoloMaplingConstants;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server half of the bounce block: the login push of {@link BounceZonePackets}, the {@code !bounce reload} push to
 * everyone online, and the backstop in {@code SpecialMoveHandler} for a client that sends a refused skill anyway.
 */
public final class BounceZoneService {

    private static final Logger log = LoggerFactory.getLogger(BounceZoneService.class);

    /** Characters (GMs) that turned the server backstop off for themselves with {@code !bounce bypass}. */
    private static final Set<Integer> bypass = ConcurrentHashMap.newKeySet();

    /** Where a zone packet goes: a real player's client. */
    public interface Recipient {
        int jobType();

        void send(Packet packet);
    }

    private BounceZoneService() {
    }

    /** Only real players have slums.dll; bots and the console character get nothing. */
    static boolean hasClient(Character chr) {
        return chr != null && chr.getClient() != null && !BotHelpers.isBot(chr)
                && chr.getId() < SoloMaplingConstants.GameConstants.BOT_BASE_ID
                && chr.getId() != SoloMaplingConstants.GameConstants.CONSOLE_BOT_ID;
    }

    static Recipient of(Character chr) {
        return new Recipient() {
            public int jobType() {
                return chr.getJobType();
            }

            public void send(Packet packet) {
                chr.sendPacket(packet);
            }
        };
    }

    /** Login (and every channel change): the current blocked set. */
    public static void onLogin(Character chr) {
        if (hasClient(chr)) {
            pushTo(BounceZones.getInstance(), List.of(of(chr)));
        }
    }

    /** Sends the zones to each recipient (its own native bounce). Returns how many were sent. */
    static int pushTo(BounceZones zones, Iterable<? extends Recipient> recipients) {
        int sent = 0;
        for (Recipient r : recipients) {
            r.send(BounceZonePackets.sync(zones, BounceZones.nativeBounce(r.jobType())));
            sent++;
        }
        return sent;
    }

    /** {@code !bounce reload}: re-reads the yaml, then pushes it to every online player. Returns the player count. */
    public static int reloadAndPushAll() {
        return reloadAndPush(BounceZones::reload, onlinePlayers());
    }

    static int reloadAndPush(java.util.function.Supplier<BounceZones> reload, Iterable<? extends Recipient> recipients) {
        return pushTo(reload.get(), recipients);
    }

    static List<Recipient> onlinePlayers() {
        List<Recipient> out = new ArrayList<>();
        for (World world : Server.getInstance().getWorlds()) {
            if (world == null) {
                continue;
            }
            for (Character chr : world.getPlayerStorage().getAllCharacters()) {
                if (hasClient(chr)) {
                    out.add(of(chr));
                }
            }
        }
        return out;
    }

    /** {@code !bounce bypass}: toggles the backstop for this GM. Returns whether it is now bypassed. */
    public static boolean toggleBypass(int characterId) {
        if (bypass.remove(characterId)) {
            return false;
        }
        bypass.add(characterId);
        return true;
    }

    public static boolean isBypassed(int characterId) {
        return bypass.contains(characterId);
    }

    /** jq-1 staging: the GM tests the real block. */
    public static void clearBypass(int characterId) {
        bypass.remove(characterId);
    }

    /**
     * SpecialMoveHandler backstop: true when the skill must have no effect here (the handler then returns before
     * cooldown, MP and the effect). Logs every refusal; jq-1 also sees the bounces and Flash Jumps that pass.
     */
    public static boolean refuse(Character chr, int skillId) {
        if (!BounceZones.isUniversalBounce(skillId) && !BounceZones.isFlashJump(skillId)) {
            return false;
        }
        int mapId = chr.getMapId();
        BounceZones zones = BounceZones.getInstance();
        boolean refused = zones.refuses(skillId, mapId);
        String category = zones.blockedBy(mapId).map(BounceZones.Category::name).orElse("-");
        if (refused && isBypassed(chr.getId())) {
            log.info("Bounce zones: {} used skill {} on blocked map {} ({}), GM bypass", chr.getName(), skillId, mapId, category);
            qa(mapId, "BYPASS " + chr.getName() + " skill " + skillId + " map " + mapId + " (" + category + ")");
            return false;
        }
        if (refused) {
            log.info("Bounce zones: refused skill {} for {} on map {} ({})", skillId, chr.getName(), mapId, category);
            qa(mapId, "REFUSED " + chr.getName() + " skill " + skillId + " map " + mapId + " (" + category
                    + "): the client sent it, so its own block did not stop it");
        } else {
            qa(mapId, "ALLOWED " + chr.getName() + " skill " + skillId + " map " + mapId
                    + (zones.blocksBounce(mapId) ? " (" + category + ": Flash Jump not blocked here)" : ""));
        }
        return refused;
    }

    private static void qa(int mapId, String line) {
        if (QaLog.on(QaLog.Topic.BOUNCE)) {
            QaLog.map(QaLog.Topic.BOUNCE, mapId, line);
        }
    }
}
