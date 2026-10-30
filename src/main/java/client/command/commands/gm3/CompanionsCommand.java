package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionStance;
import soloMapling.ArtificialPlayer.BotPartySystem.CompanionSwitches;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyBotOrders;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.companions.OwnedAltService;

/**
 * 1.6.2 ticket 99 (GM, read-only, audited by the command executor): a player's companions as the orders see them -
 * the switches, the formation, each companion's slot, stance, formation place and whether it is mid-handover.
 * {@code !companions [player]}.
 */
public class CompanionsCommand extends Command {
    { setDescription("Show a player's companions: switches, formation, stances. !companions [player]"); }

    @Override
    public void execute(Client c, String[] params) {
        Character gm = c.getPlayer();
        Character who = params.length > 0 ? c.getWorldServer().getPlayerStorage().getCharacterByName(params[0]) : gm;
        if (who == null) {
            gm.yellowMessage("No one online named " + params[0] + ".");
            return;
        }
        gm.yellowMessage("Switches: orders " + CompanionSwitches.orders() + ", trades " + CompanionSwitches.trades()
                + ", upgrades " + CompanionSwitches.upgrades() + ", supplies " + CompanionSwitches.supplies()
                + ", invite summon " + CompanionSwitches.inviteSummon());
        gm.yellowMessage(who.getName() + "'s formation: " + CompanionStance.formation(who.getId()).said());
        var slots = PartyBotOrders.slots(who);
        if (slots.isEmpty()) {
            gm.yellowMessage("No companions in " + who.getName() + "'s party (alts count with a switch on).");
        }
        for (int i = 0; i < slots.size(); i++) {
            var t = slots.get(i);
            Character chr = null;
            for (var a : OwnedAltService.getInstance().active()) {
                if (a.alt().getId() == t.id()) {
                    chr = a.alt();
                }
            }
            String kind = chr != null ? "alt" : "bot friend";
            String stance = chr != null ? CompanionStance.of(t.id(), chr.getMapId()).mode.name() : "-";
            gm.yellowMessage((i + 1) + " " + t.name() + " (" + kind + "): stance " + stance + ", place "
                    + GCMovement.followOffset(t.id()) + " px"
                    + (chr != null && BotGiveTrade.isBusy(chr) ? ", handing something over" : ""));
        }
    }
}
