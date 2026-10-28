package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.companions.OwnedAltService;

public class DismissCommand extends Command {
    { setDescription("Save and send your alts home: @dismiss <name> | all."); }
    @Override public void execute(Client c, String[] params) {
        Character owner = c.getPlayer();
        if (params.length != 1) {
            owner.yellowMessage("Usage: @dismiss <name> | all");
            return;
        }
        OwnedAltService alts = OwnedAltService.getInstance();
        // 1.6.0.4 (M1): the save is database I/O; off the packet thread, reply when done.
        alts.runAsync(() -> {
            try {
                if (params[0].equalsIgnoreCase("all")) {
                    int count = alts.dismissAll(owner, false);
                    owner.yellowMessage("Saved and dismissed " + count + " of your alts.");
                } else if (alts.dismiss(owner, params[0])) {
                    owner.yellowMessage(params[0] + " has been saved and dismissed.");
                } else if (alts.isBotFriend(owner, params[0])) {
                    owner.yellowMessage(params[0] + " is a bot friend, not one of your alts. Expel it from your party to send it off.");
                } else {
                    owner.yellowMessage("That character is not with you. @dismiss works on alts you called with @botme.");
                }
            } catch (IllegalStateException e) { owner.yellowMessage(e.getMessage()); }
        });
    }
}
