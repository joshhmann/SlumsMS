package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import soloMapling.companions.OwnedAltService;

public class BotMeCommand extends Command {
    { setDescription("Call one of your other characters: @botme <name>, or @botme to list them."); }
    @Override public void execute(Client c, String[] params) {
        try {
            OwnedAltService alts = OwnedAltService.getInstance();
            if (params.length == 0) {
                var eligible = alts.list(c.getPlayer());
                c.getPlayer().yellowMessage(eligible.isEmpty() ? "No eligible offline characters on this account and world."
                        : "Available characters: " + String.join(", ", eligible.stream().map(OwnedAltService.Candidate::name).toList())
                        + ". Use @botme <name> to call one.");
            } else if (params.length == 1) {
                var alt = alts.summon(c.getPlayer(), params[0]);
                c.getPlayer().yellowMessage(alt.getName() + " has joined you.");
            } else c.getPlayer().yellowMessage("Usage: @botme <name>");
        } catch (IllegalStateException e) { c.getPlayer().yellowMessage(e.getMessage()); }
    }
}
