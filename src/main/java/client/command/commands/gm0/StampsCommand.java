package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import server.checkin.DailyCheckin;
import server.checkin.DailyCheckinStreak;
import server.checkin.SlumsStamps;

/** @stamps: the account's Slums Stamps, its Daily Check-In day and the next bonus day (ticket 111). */
public class StampsCommand extends Command {
    {
        setDescription("Show your Slums Stamps and Daily Check-In day.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        DailyCheckinStreak streak = DailyCheckin.streakOf(c.getAccID());
        if (streak == null) {
            player.yellowMessage("Slums Stamps: " + SlumsStamps.balance(c.getAccID()));
            return;
        }
        for (String line : SlumsStamps.statusLines(player, streak, System.currentTimeMillis())) {
            player.yellowMessage(line);
        }
    }
}
