package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotDramaSystem.DramaGm;
import soloMapling.server.ExecutorServiceManager;

/**
 * 1.5.0 bot drama GM tools for guild wars (GM 4): declare wars (bot vs bot, on a player guild), turf, poaching,
 * alliances and betrayals on demand. The {@code !qa} scenarios gw-1..gw-4 use the same actions.
 */
public class BotWarCommand extends Command {
    {
        setDescription("Guild wars (GM): !botwar list | declare <guild> [<guild>|player] | check <guild> | turf | taunt | score | peace | end <id> | poach <guild> [<guild>|player] | ally <a> <b> | betray [id] | bossrun <guild> | step");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                DramaGm.run(gm, "war", params).forEach(gm::yellowMessage);
            } catch (RuntimeException e) {
                gm.yellowMessage("!botwar failed: " + e);
            }
        });
    }
}
