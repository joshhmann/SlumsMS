package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotDramaSystem.DramaGm;
import soloMapling.server.ExecutorServiceManager;

/**
 * 1.5.0 bot drama GM tools for rivals (GM 4): stage a rival or nemesis and trigger a roast, a KS war, a callout or a
 * revenge follow on demand. The {@code !qa} scenarios rv-1..rv-4 use the same actions.
 */
public class BotRivalCommand extends Command {
    {
        setDescription("Bot rivals (GM): !botrival status | stage <rival|nemesis> [social] | roast | kswar [2-4] | endwar | callout | revenge | die | unstage");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                DramaGm.run(gm, "rival", params).forEach(gm::yellowMessage);
            } catch (RuntimeException e) {
                gm.yellowMessage("!botrival failed: " + e);
            }
        });
    }
}
