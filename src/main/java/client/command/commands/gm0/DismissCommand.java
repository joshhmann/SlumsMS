package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.companions.OwnedAltService;

import java.util.List;

public class DismissCommand extends Command {
    { setDescription("Save and send your alts home: @dismiss [name | all] (no name: your only alt)."); }
    @Override public void execute(Client c, String[] params) {
        Character owner = c.getPlayer();
        if (params.length > 1) {
            owner.yellowMessage("Usage: @dismiss <name> | all");
            return;
        }
        OwnedAltService alts = OwnedAltService.getInstance();
        if (params.length == 0) {
            // TEST 10-30: a bare @dismiss only printed the usage and the tester believed the alt had gone home.
            alts.runAsync(() -> {
                try {
                    List<String> out = alts.outNames(owner);
                    if (out.isEmpty()) {
                        owner.yellowMessage("You have no alts out. Usage: @dismiss <name> | all");
                    } else if (out.size() > 1) {
                        owner.yellowMessage("Which one? @dismiss <name> or @dismiss all. Out with you: " + String.join(", ", out) + ".");
                    } else if (alts.dismiss(owner, out.get(0))) {
                        owner.yellowMessage(out.get(0) + " has been saved and dismissed.");
                    } else {
                        owner.yellowMessage(out.get(0) + " is already on its way home.");
                    }
                } catch (IllegalStateException e) { owner.yellowMessage(e.getMessage()); }
            });
            return;
        }
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
