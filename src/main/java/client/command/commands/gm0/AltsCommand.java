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
    { setDescription("Tell your companions what to do: @alts <order> (e.g. @alts spread, @alts trade pots). @alts lists the orders. GMs: !alts clear <id> clears a failed save."); }

    /** The GM rank `!alts clear <id>` needs: @alts is a player command, so the clear branch checks for itself. */
    static final int CLEAR_GM_LEVEL = 3;

    static final String HELP = "Orders (say them in party chat or after @alts; a name or slot number first for one "
            + "companion): follow, stay, move here, sentry, patrol, lanes [map|screen] (each companion takes a spot of "
            + "its own, on this screen first and across the map only when there are too many for one screen), "
            + "formation split/spread/stack/left/right/random"
            + " [px|tight|loose], tight, loose, snap; trade pots/hp pots/scrolls/use/etc/equips/ammo/rocks/100k/"
            + "<item>/<slot>, show me your hat, drop <what> (it asks first, say yes); any upgrades?, request?, trade upgrades; "
            + "need pots/hp/mp/ammo/rocks. Reports: pots, stats, slots. A mistyped order to a companion gets "
            + "\"did you mean 'grind'?\".";

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length > 0 && params[0].equalsIgnoreCase("clear")) {
            clear(player, params);
            return;
        }
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
            if (PartyBotOrders.typoHint(player, line, true)) {
                return; // the companion said "did you mean 'grind'?" in party chat
            }
            player.yellowMessage(PartyBotOrders.slots(player).isEmpty()
                    ? "You have no companions in your party." : "Your companions didn't get that. " + HELP);
            return;
        }
        soloMapling.server.ExecutorServiceManager.runAsync(() -> PartyBotOrders.carryOut(player, order.get()));
    }

    /**
     * AL-1 (ticket 183): `!alts clear <id>` - a save that failed three times locks that character out of login and
     * @botme; this drops the mark now, without waiting for the restart or the player's next login attempt.
     */
    private void clear(Character player, String[] params) {
        if (player.gmLevel() < CLEAR_GM_LEVEL) {
            player.yellowMessage("You do not have permission to use this command.");
            return;
        }
        if (params.length < 2) {
            player.yellowMessage("Which character? !alts clear <id>.");
            return;
        }
        int id;
        try {
            id = Integer.parseInt(params[1]);
        } catch (NumberFormatException e) {
            player.yellowMessage("!alts clear <id>: the id is a number, like !alts clear 30000.");
            return;
        }
        player.yellowMessage(soloMapling.companions.OwnedAltService.getInstance().clearFailed(id));
    }
}
