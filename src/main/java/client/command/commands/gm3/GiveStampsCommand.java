package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.checkin.SlumsStamps;

/**
 * !givestamps (also !givevp: vote points became Slums Stamps in 1.6.1, ticket 111): adds stamps to an online player's
 * account.
 */
public class GiveStampsCommand extends Command {
    {
        setDescription("Give Slums Stamps to a player's account.");
    }

    static final int MAX = 1_000;

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int n;
        try {
            n = params.length < 2 ? 0 : Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            n = 0;
        }
        if (n < 1 || n > MAX) {
            player.yellowMessage("Syntax: !givestamps <playername> <1-" + MAX + ">");
            return;
        }
        Character victim = c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]);
        if (victim == null || victim.getClient() == null || !SlumsStamps.add(victim.getClient().getAccID(), n)) {
            player.message("Player '" + params[0] + "' could not be found or given stamps.");
            return;
        }
        victim.yellowMessage("+" + n + " Slums Stamp" + (n == 1 ? "" : "s") + " from " + player.getName() + ".");
        player.message("Gave " + n + " Slums Stamps to " + victim.getName() + "'s account.");
    }
}
