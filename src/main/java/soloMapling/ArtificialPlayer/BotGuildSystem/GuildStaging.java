package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStaging;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotHelpers;

/**
 * GM and {@code !qa} staging for bot guilds: a fresh on-demand bot next to the GM that feels a chosen way about them
 * (ticket 58's {@link AffinityStaging}), optionally a member of a guild at once (memory only: on-demand bots are never
 * stored). {@link #unstage} takes it out of its guild and the world.
 */
public final class GuildStaging {

    private GuildStaging() {
    }

    /**
     * The staged bot's id, or -1. {@code guildId} &gt; 0 makes it a member of that guild now at {@code rank}
     * (bot guilds and the GM's own guild alike).
     */
    public static int stage(Character gm, AffinityState state, int guildId, int rank) {
        int id = AffinityStaging.stage(gm, state, state.hostile());
        if (id < 0) {
            return -1;
        }
        if (guildId > 0) {
            Character bot = BotHelpers.getCharFromChannelStorage(id);
            if (bot == null || !BotGuilds.join(bot, guildId, rank, System.currentTimeMillis())) {
                AffinityStaging.unstage(id);
                return -1;
            }
        }
        return id;
    }

    public static void unstage(int botId) {
        BotGuilds.leave(botId, "unstaged");
        AffinityStaging.unstage(botId);
    }
}
