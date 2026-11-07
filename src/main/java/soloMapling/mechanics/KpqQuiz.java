package soloMapling.mechanics;

import net.server.Server;
import net.server.world.World;

/**
 * 1.6.6 kpq1-questions (owner, 7 Nov): Cloto's stage 1 asks about our own world, and two of the answers are the world's
 * settings, read live so they stay true when a setting changes: how many channels the world has, and its EXP rate.
 * Cloto's script ({@code scripts/npc/9020001.js}) and the regulars' row ({@code kpq.yaml}, {@code live:}) both ask here.
 */
public final class KpqQuiz {

    public static final String CHANNELS = "channels";
    public static final String EXP_RATE = "expRate";

    private KpqQuiz() {
    }

    /** The live answer for {@code key} in this world, or -1 when the key or the world is unknown. */
    public static int live(String key, int worldId) {
        World world = Server.getInstance().getWorld(worldId);
        if (world == null || key == null) {
            return -1;
        }
        return switch (key) {
            case CHANNELS -> world.getChannelsSize();
            case EXP_RATE -> world.getExpRate();
            default -> -1;
        };
    }

    /** Whether {@code key} is an answer this class can read live. */
    public static boolean known(String key) {
        return CHANNELS.equals(key) || EXP_RATE.equals(key);
    }
}
