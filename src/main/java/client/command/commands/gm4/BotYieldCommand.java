package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotShopping;
import soloMapling.ArtificialPlayer.BotYieldCaps;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * Bot economy read-out (ticket 15): what bots took out of the world against the per-map yield caps and their
 * daily meso caps.
 * !botyield            - the caps, the 10 maps bots took most from, the 10 bots that took most mesos today, and
 *                        what bots spent at NPC shops (ticket 09) and on scrolls, with the scroll results
 *                        (ticket 11)
 * !botyield top <n>    - the same with n rows each
 * !botyield <name|id>  - one online bot's take today against its cap
 */
public class BotYieldCommand extends Command {
    {
        setDescription("Bot yield caps: !botyield [top <n>] | <name|id>");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            BotYieldCaps.report(10).forEach(player::yellowMessage);
            player.yellowMessage(BotShopping.report());   // ticket 09: what bots spent at NPC shops
            soloMapling.ArtificialPlayer.BotScrollSystem.ScrollLedger.report().forEach(player::yellowMessage);   // ticket 11
            return;
        }
        if (params[0].equalsIgnoreCase("top")) {
            int top = params.length > 1 && isInteger(params[1]) ? Math.max(1, Integer.parseInt(params[1])) : 10;
            BotYieldCaps.report(Math.min(top, 50)).forEach(player::yellowMessage);
            return;
        }
        boolean found = false;
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null) {
                continue;
            }
            boolean match = isInteger(params[0]) ? chr.getId() == Integer.parseInt(params[0])
                    : chr.getName().equalsIgnoreCase(params[0]);
            if (match) {
                player.yellowMessage(BotYieldCaps.report(chr));
                found = true;
            }
        }
        if (!found) {
            player.yellowMessage("No online bot " + params[0]);
        }
    }
}
