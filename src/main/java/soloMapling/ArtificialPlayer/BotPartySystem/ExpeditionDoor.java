package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import net.server.channel.Channel;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;

/**
 * 1.6.4: the two things the expedition door was missing, behind one switch - {@code -Dslums.exped.door},
 * default off.
 * <p>
 * (1) A bot registered by {@link ExpeditionBots#autoJoin} but not standing on the recruiting map when the leader
 * starts is dropped by {@code EventInstanceManager.registerExpeditionTeam} without a word. The leader is told which
 * bot that is ({@link ExpeditionBots#warnNotAtTheAltar}), and it is not warped: a bot walking to the altar is follower
 * travel, and a teleport would be a second, wrong answer to the same question.
 * <p>
 * (2) An owned alt that goes home stays in {@code Expedition.members} until someone disposes the expedition - a ghost
 * in the member list, and a boss-log entry spent in its name when the run starts ({@code Expedition.start} ->
 * {@code registerExpeditionAttempt}). {@link #leaveExpedition} takes it out on the way out.</p>
 */
public final class ExpeditionDoor {

    /** {@code -Dslums.exped.door}: names the bots a start leaves behind, and drops a dismissed alt out of its expedition. */
    public static final String PROPERTY = "slums.exped.door";

    private ExpeditionDoor() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Takes a bot or alt that is leaving the world out of any expedition it is still registered in on its channel. */
    public static void leaveExpedition(Character chr) {
        if (!enabled() || chr == null || chr.getClient() == null || chr.getClient().getChannelServer() == null) {
            return;
        }
        Channel channel = chr.getClient().getChannelServer();
        for (ExpeditionType type : ExpeditionType.values()) {
            Expedition exped = channel.getExpedition(type);
            if (exped != null && exped.contains(chr)) {
                exped.removeMember(chr);
            }
        }
    }
}
