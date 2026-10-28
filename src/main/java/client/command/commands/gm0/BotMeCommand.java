package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.companions.OwnedAltService;

public class BotMeCommand extends Command {
    { setDescription("Call one of your other characters: @botme <name>, @botme to list them, @botme here to bring them to you."); }
    @Override public void execute(Client c, String[] params) {
        Character owner = c.getPlayer();
        OwnedAltService alts = OwnedAltService.getInstance();
        if (params.length > 1) {
            owner.yellowMessage("Usage: @botme <name> | @botme here | @botme");
            return;
        }
        if (!alts.summoningOpen()) { // 1.6.0.4: summoning switched off (OWNED_ALTS_SUMMON_ENABLED); @dismiss still works
            owner.yellowMessage(OwnedAltService.RESTING);
            return;
        }
        // 1.6.0.4 (M1): listing, loading and placing read the database; off the packet thread, reply when done.
        alts.runAsync(() -> {
            try {
                if (params.length == 0) {
                    var eligible = alts.list(owner);
                    owner.yellowMessage(eligible.isEmpty() ? "No eligible offline characters on this account and world."
                            : "Available characters: " + String.join(", ", eligible.stream().map(OwnedAltService.Candidate::name).toList())
                            + ". Use @botme <name> to call one.");
                } else if (params[0].equalsIgnoreCase("here")) {
                    var moved = alts.bringHere(owner);
                    if (!moved.isEmpty()) owner.yellowMessage(String.join(", ", moved) + (moved.size() == 1 ? " is" : " are") + " here with you.");
                } else {
                    boolean hadParty = owner.getParty() != null;
                    var alt = alts.summon(owner, params[0]);
                    owner.yellowMessage(alt.getName() + " has joined you and follows you."
                            + (hadParty ? "" : " A party was created for you.")
                            + " Changing channel sends your alts home.");
                }
            } catch (IllegalStateException e) { owner.yellowMessage(e.getMessage()); }
        });
    }
}
