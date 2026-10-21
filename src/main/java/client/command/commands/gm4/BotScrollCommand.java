package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotConsignment;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollLedger;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules;
import soloMapling.ArtificialPlayer.BotScrollSystem.ScrollWants;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * Ticket 11: bots scrolling their own gear.
 * !botscroll                          - the scroll ledger, open FM wants and listings
 * !botscroll <name|id>                - one online bot: appetite, scroll budget today, what it would scroll next
 * !botscroll <name|id> now [appetite] - that bot makes one real roll now (careful | normal | gambler)
 */
public class BotScrollCommand extends Command {
    {
        setDescription("Bot scrolling: !botscroll [<name|id> [now [careful|normal|gambler]]]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (params.length == 0) {
            ScrollLedger.report().forEach(player::yellowMessage);
            player.yellowMessage("Open FM scroll wants: " + ScrollWants.open() + ", bot items listed in FM shops: "
                    + BotConsignment.open());
            return;
        }
        Character bot = null;
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character chr = sm.getChr();
            if (chr != null && (isInteger(params[0]) ? chr.getId() == Integer.parseInt(params[0])
                    : chr.getName().equalsIgnoreCase(params[0]))) {
                bot = chr;
                break;
            }
        }
        if (bot == null) {
            player.yellowMessage("No online bot " + params[0]);
            return;
        }
        if (params.length > 1 && params[1].equalsIgnoreCase("now")) {
            ScrollRules.Appetite appetite = ScrollRules.Appetite.parse(params.length > 2 ? params[2] : null,
                    ScrollRules.Appetite.of(bot.getId()));
            BotScrolling.stepNow(bot, appetite).forEach(player::yellowMessage);
            return;
        }
        player.yellowMessage(BotScrolling.describe(bot));
    }
}
