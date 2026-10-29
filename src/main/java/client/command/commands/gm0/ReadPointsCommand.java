package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.checkin.SlumsStamps;

/** @points: your Slums Stamps (vote points became stamps in 1.6.1, ticket 111); staff can add {@code rp} for reward points. */
public class ReadPointsCommand extends Command {
    {
        setDescription("Show point total.");
    }

    @Override
    public void execute(Client client, String[] params) {
        Character player = client.getPlayer();
        if (params.length > 1) {
            player.yellowMessage("Syntax: @points");
            return;
        }
        String which = params.length == 0 ? "" : params[0];
        // reward points are a staff view; players see their stamps only (1.6.1 notes: stamps instead of vote points)
        boolean rp = player.isGM() && which.equals("rp");
        player.yellowMessage(line(rp, SlumsStamps.balance(client.getAccID()), player.getRewardPoints()));
    }

    /** The one line @points prints: stamps, or (staff, {@code @points rp}) reward points. */
    public static String line(boolean staffRewardPoints, int stamps, int rewardPoints) {
        return staffRewardPoints ? "RewardPoints: " + rewardPoints : "Slums Stamps: " + stamps;
    }
}
