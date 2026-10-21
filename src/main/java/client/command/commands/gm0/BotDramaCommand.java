package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;

/**
 * Ticket 58: {@code @botdrama off|on}. Off: no bot ever treats this character as a rival - no trash talk, no KSing,
 * no defames, no refusals out of spite - and hostile things they do stop counting. Friendly bots stay friendly.
 * Stored per character (table {@code botdrama}, migration 011), so it survives relogs and restarts.
 */
public class BotDramaCommand extends Command {
    {
        setDescription("Bot drama: @botdrama off (bots never hold a grudge against you) | on.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        String arg = params.length == 0 ? "" : params[0].toLowerCase();
        switch (arg) {
            case "off", "no", "0", "false" -> {
                BotAffinity.setOptedOut(player, true);
                player.yellowMessage("Bot drama is off: bots will stay neutral or friendly toward you.");
            }
            case "on", "yes", "1", "true" -> {
                BotAffinity.setOptedOut(player, false);
                player.yellowMessage("Bot drama is on: bots remember how you treat them, good and bad.");
            }
            default -> {
                player.yellowMessage("Usage: @botdrama off | @botdrama on");
                player.yellowMessage("Now: " + (BotAffinity.optedOut(player.getId()) ? "off (bots never hold a grudge)"
                        : "on (bots remember how you treat them, good and bad)") + ".");
            }
        }
    }
}
