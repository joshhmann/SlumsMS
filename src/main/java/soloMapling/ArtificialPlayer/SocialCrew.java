package soloMapling.ArtificialPlayer;

/**
 * 1.6.0 (S5): who counts as a bot's crew for {@link SocialGate}'s side-eye rule. When a real player wrongs bot A, A's
 * crew (its close friends and guild mates) may side-eye, gossip about and lightly tease the player, but never call a KS
 * war or a call-out on them: that stays with A.
 *
 * <p>The gate asks every registered source ({@link SocialGate#addCrewSource}) and counts a pair as crew when any says
 * so. Built in: {@link #GUILD} (both bots in one guild). Slice S2 (bot-to-bot bonds) adds a source for close FRIEND
 * bonds. A source must be cheap and memory-only: it is asked on a bot's sighting of a player.
 */
@FunctionalInterface
public interface SocialCrew {

    /** Whether {@code botA} and {@code botB} (character ids, both bots, never equal) are crew. */
    boolean crew(int botA, int botB);

    /** Guild mates: both bots are members of the same guild (bot guild or player guild), from the in-memory roster. */
    SocialCrew GUILD = (a, b) -> {
        soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow ma =
                soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membership(a);
        if (ma == null) {
            return false;
        }
        soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow mb =
                soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.membership(b);
        return mb != null && mb.guildId() == ma.guildId();
    };
}
