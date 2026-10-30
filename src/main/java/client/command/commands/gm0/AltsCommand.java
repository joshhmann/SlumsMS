package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionSwitches;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyBotOrders;

/**
 * 1.6.2 ticket 99: the companion orders as a command - the same orders said in chat ("spread", "jason trade pots",
 * "any upgrades?"), for a player who would rather type a command than say it. @alts alone lists them.
 */
public class AltsCommand extends Command {
    { setDescription("Tell your companions what to do: @alts <order> (e.g. @alts spread, @alts trade pots). @alts lists the orders."); }

    static final String HELP = "Orders (say them in party chat or after @alts; a name or slot number first for one "
            + "companion): follow, stay, move here, sentry, patrol, formation split/spread/stack/left/right/random"
            + " [px|tight|loose], tight, loose, snap; trade pots/hp pots/scrolls/use/etc/equips/ammo/rocks/100k/"
            + "<item>/<slot>, show me your hat, drop <what>; any upgrades?, request?, trade upgrades; need pots/hp/mp/"
            + "ammo/rocks.";

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (!CompanionSwitches.any()) {
            player.yellowMessage("Companion orders aren't open yet.");
            return;
        }
        if (params.length == 0) {
            player.yellowMessage(HELP);
            return;
        }
        String line = String.join(" ", params);
        var order = PartyBotOrders.read(player, line);
        if (order.isEmpty()) {
            player.yellowMessage(PartyBotOrders.slots(player).isEmpty()
                    ? "You have no companions in your party." : "Your companions didn't get that. " + HELP);
            return;
        }
        soloMapling.server.ExecutorServiceManager.runAsync(() -> PartyBotOrders.carryOut(player, order.get()));
    }
}
