package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.menus.GuideMenu;

/**
 * 1.5.0 {@code @guide} / {@code @tutorial}: the beginner's guide in an NPC window (what SlumsMS is, the buttons, where
 * things are, bots, commands, trading). It also opens by itself once, at a character's first login on 1.5.0.
 * {@code @help} stays the full command list. GMs: {@code @guide reset} shows it again at the next login (QA).
 */
public class GuideCommand extends Command {
    {
        setDescription("The beginner's guide: buttons, where things are, bots, commands and trading.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length > 0 && params[0].equalsIgnoreCase("reset") && player.isGM()) {
            // QA staging: the guide shows again at this character's next login
            GuideMenu.forget(player);
            player.yellowMessage("The guide will open by itself at your next login.");
            return;
        }
        if (!GuideMenu.open(player)) {
            player.yellowMessage("Close the NPC window first, then @guide again.");
        }
    }
}
