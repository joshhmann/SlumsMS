package client.command.commands.gm0;

import client.Client;
import client.command.Command;
import server.checkin.DailyCheckin;

/** @daily / @checkin: opens the Daily Check-In window, and says how long until the next reward. */
public class DailyCommand extends Command {
    {
        setDescription("Open the Daily Check-In window.");
    }

    @Override
    public void execute(Client c, String[] params) {
        DailyCheckin.open(c);
    }
}
