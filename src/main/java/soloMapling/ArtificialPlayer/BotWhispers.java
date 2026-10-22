package soloMapling.ArtificialPlayer;

import client.Character;
import tools.PacketCreator;

import java.util.HashMap;
import java.util.Map;

/** One unsolicited whisper budget per recipient across all bots/channels; replies spend no budget and ignore busy. */
public final class BotWhispers {
    private static final Map<Integer, Long> lastSent = new HashMap<>();

    private BotWhispers() { }

    public static boolean send(Character player, String sender, int channel, String line, boolean reply) {
        if (line == null || !reply && PlayerBusy.isBusy(player)) { // a reply to the player's own whisper always goes through
            return false;
        }
        if (!take(player.getId(), reply, System.currentTimeMillis())) {
            return false;
        }
        player.sendPacket(PacketCreator.getWhisperReceive(sender, channel, false, line));
        return true;
    }

    static synchronized boolean take(int playerId, boolean reply, long now) {
        if (reply) {
            return true;
        }
        long gap = Math.max(0, Long.getLong("solomapling.chat.whisperPlayerGapMs", 600_000));
        lastSent.entrySet().removeIf(e -> now - e.getValue() >= gap);
        Long last = lastSent.get(playerId);
        if (last != null && now - last < gap) {
            return false;
        }
        lastSent.put(playerId, now);
        return true;
    }

    static synchronized void resetForTest() {
        lastSent.clear();
    }
}
