package soloMapling.ArtificialPlayer;

import client.Character;
import tools.PacketCreator;

import java.util.HashMap;
import java.util.Map;

/**
 * One unsolicited whisper budget per recipient across all bots/channels; replies spend no budget and ignore busy.
 * 1.6.0 (S5): an unsolicited whisper goes through {@link SocialGate} first - {@code @quiet bots} and a busy player hold
 * it, and a bot never whispers the same line to the same player twice - and the budget is the gate's
 * ({@link SocialGate#whisperGapMs}: one per 200 s per player; 1.5.x: one per 10 min).
 */
public final class BotWhispers {
    private static final Map<Integer, Long> lastSent = new HashMap<>();

    private BotWhispers() { }

    public static boolean send(Character player, String sender, int channel, String line, boolean reply) {
        return send(player, sender, channel, line, reply ? null : SocialGate.Act.WHISPER);
    }

    /**
     * A whisper from a bot. {@code act} null = a reply to the player's own whisper (always goes through, no budget);
     * otherwise the unprompted act it is ({@link SocialGate.Act#WHISPER}, or {@link SocialGate.Act#TRADE_OFFER} for an
     * answer to the player's own ask, which {@code @quiet} does not hold).
     */
    public static boolean send(Character player, String sender, int channel, String line, SocialGate.Act act) {
        if (line == null || player == null) {
            return false;
        }
        boolean reply = act == null;
        Character bot = senderOf(player, sender);
        int botKey = SocialGate.botKey(bot, sender);
        if (!reply) { // a reply to the player's own whisper always goes through
            SocialGate.Verdict v = SocialGate.check(bot, player, act);
            if (v.ok() && SocialGate.seen(botKey, player.getId(), line)) {
                v = new SocialGate.Verdict(false, "repeat line");
            }
            SocialGate.note(act, bot == null ? 0 : bot.getId(), sender, player, v);
            if (!v.ok()) {
                return false;
            }
        }
        if (!take(player.getId(), reply || act.asked, System.currentTimeMillis())) { // an answer to their ask: free
            return false;
        }
        if (!reply) {
            SocialGate.fresh(botKey, player.getId(), line);
        }
        player.sendPacket(PacketCreator.getWhisperReceive(sender, channel, false,
                soloMapling.ArtificialPlayer.BotConvo.MessyChat.forWhisper(bot, sender, player, line)));
        return true;
    }

    // 1.6.0 messy chat: the whispering bot, for its typing style (null when it is not online: seeded by its name)
    private static Character senderOf(Character player, String sender) {
        try {
            return player.getWorldServer() == null ? null : player.getWorldServer().getPlayerStorage().getCharacterByName(sender);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static synchronized boolean take(int playerId, boolean reply, long now) {
        if (reply) {
            return true;
        }
        long gap = SocialGate.whisperGapMs();
        lastSent.entrySet().removeIf(e -> now - e.getValue() >= gap);
        Long last = lastSent.get(playerId);
        if (last != null && now - last < gap) {
            return false;
        }
        lastSent.put(playerId, now);
        return true;
    }

    /** QA ({@code !qa in-1}): this player's unsolicited-whisper gap starts over. */
    static synchronized void forgetPlayer(int playerId) {
        lastSent.remove(playerId);
    }

    static synchronized void resetForTest() {
        lastSent.clear();
    }
}
