package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotDramaSystem.DramaGm;
import soloMapling.server.ExecutorServiceManager;

/**
 * 1.5.0 bot drama GM tools for romance (GM 4): stage a crush, a partner or an ex next to you and trigger every scene
 * on demand. {@code !botromance help} lists the actions; the {@code !qa} scenarios rm-1..rm-4 use the same ones.
 */
public class BotRomanceCommand extends Command {
    {
        setDescription("Bot romance (GM): !botromance status | stage <crush|dating|ex> | ask | propose | follow | whisper | jealous | confront | neglect | breakup | flame | wedding | object | runaway | crash | divorce | gossip | step | unstage");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        ExecutorServiceManager.getExecutorService().execute(() -> {
            try {
                DramaGm.run(gm, "romance", params).forEach(gm::yellowMessage);
            } catch (RuntimeException e) {
                gm.yellowMessage("!botromance failed: " + e);
            }
        });
    }
}
