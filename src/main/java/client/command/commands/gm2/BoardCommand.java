package client.command.commands.gm2;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.Economy.board.SiteBoard;

import java.util.Arrays;

/**
 * 1.6.0 site board (owner, 2026-10-26: "GMs remove anything"). {@code !board} lists the newest open posts with their
 * ids; {@code !board remove <id> [reason]} takes one down: it leaves the in-game board at once and goes into the
 * outbox's removed.json, which the site honours.
 */
public class BoardCommand extends Command {
    {
        setDescription("Site Buy/Sell board: !board, !board remove <post id> [reason].");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        if (params.length >= 2 && "remove".equalsIgnoreCase(params[0])) {
            String reason = params.length > 2 ? String.join(" ", Arrays.copyOfRange(params, 2, params.length)) : "";
            gm.yellowMessage(SiteBoard.gmRemove(params[1], gm.getName(), reason));
            return;
        }
        if (params.length >= 1 && "remove".equalsIgnoreCase(params[0])) {
            gm.yellowMessage("Usage: !board remove <post id> [reason]");
            return;
        }
        for (String line : SiteBoard.status()) {
            gm.yellowMessage(line);
        }
    }
}
