package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.menus.SettingsMenu;

/**
 * 1.5.0 {@code @settings}: the player's bot settings (bot drama, romance, bot megaphones, bot chatter) in an NPC
 * window with their state; a click switches one. Each switch runs the setting's own command.
 */
public class SettingsCommand extends Command {
    {
        setDescription("Your bot settings in a window: bot drama, romance, bot megaphones and chatter (click to switch).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (!SettingsMenu.open(player)) {
            player.yellowMessage("Close the NPC window first, then @settings again. Or type: @botdrama on|off, "
                    + "@romance on|off, @quiet megaphones|bots|all|off.");
        }
    }
}
