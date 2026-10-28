package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.checkin.SlumsStamps;

/** @points: reward points and Slums Stamps (vote points became stamps in 1.6.1, ticket 111). */
public class ReadPointsCommand extends Command {
    {
        setDescription("Show point total.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        if (params.length > 1) {
            player.yellowMessage("Syntax: @points (rp|stamps|all)");
            return;
        }
        String which = params.length == 0 ? "all" : params[0];
        switch (which) {
            case "rp" -> player.yellowMessage("RewardPoints: " + player.getRewardPoints());
            case "stamps", "vp" -> player.yellowMessage("Slums Stamps: " + SlumsStamps.balance(client.getAccID()));
            default -> player.yellowMessage("RewardPoints: " + player.getRewardPoints() + " | "
                    + "Slums Stamps: " + SlumsStamps.balance(client.getAccID()));
        }
    }
}
