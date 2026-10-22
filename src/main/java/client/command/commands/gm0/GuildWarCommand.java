package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotDramaSystem.GuildWars;
import soloMapling.server.ExecutorServiceManager;

/**
 * 1.5.0 bot drama: {@code @guildwar} shows your guild's war (the weekly turf score, days left, the maps each side
 * holds) or the wars and alliances on now; {@code @guildwar peace} (guild master or jr. master) ends a bot guild's war
 * on your guild with a peace offer.
 */
public class GuildWarCommand extends Command {
    {
        setDescription("Guild wars: @guildwar (the score) | @guildwar peace (master / jr. master).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        boolean peace = params.length > 0 && "peace".equalsIgnoreCase(params[0]);
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                if (peace) {
                    player.yellowMessage(GuildWars.peace(player));
                } else {
                    GuildWars.describeFor(player).forEach(player::yellowMessage);
                }
            } catch (RuntimeException e) {
                player.yellowMessage("@guildwar failed: " + e.getMessage());
            }
        });
    }
}
