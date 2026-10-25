package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import soloMapling.companions.OwnedAltService;

public class DismissCommand extends Command {
    { setDescription("Save and send your alts home: @dismiss <name> | all."); }
    @Override public void execute(Client c, String[] params) {
        if (params.length != 1) {
            c.getPlayer().yellowMessage("Usage: @dismiss <name> | all");
            return;
        }
        try {
            OwnedAltService alts = OwnedAltService.getInstance();
            if (params[0].equalsIgnoreCase("all")) {
                int count = alts.dismissAll(c.getPlayer(), false);
                c.getPlayer().yellowMessage("Saved and dismissed " + count + " of your alts.");
            } else {
                boolean dismissed = alts.dismiss(c.getPlayer(), params[0]);
                c.getPlayer().yellowMessage(dismissed ? params[0] + " has been saved and dismissed."
                        : "That character is not with you.");
            }
        } catch (IllegalStateException e) { c.getPlayer().yellowMessage(e.getMessage()); }
    }
}
