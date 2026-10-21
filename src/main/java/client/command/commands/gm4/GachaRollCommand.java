package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.Gachapon.BotGacha;
import soloMapling.Gachapon.GachaMachine;
import soloMapling.Gachapon.GachaPools;

import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.server.SoloMaplingUtilities.isInteger;

/**
 * 1.5.0 gachapon, GM test: a forced roll (no ticket, the item is really given and a rare or jackpot goes through the
 * real notice limits).
 * !gacharoll <town>                      - one roll for you
 * !gacharoll <town> <count>              - up to 11 rolls for you
 * !gacharoll <town> <bot name|id> [count] - free rolls for an online bot (its prize goes into its holdings)
 */
public class GachaRollCommand extends Command {
    {
        setDescription("Force a gachapon roll: !gacharoll <town> [me|<bot>] [count<=11]");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        GachaPools pools = GachaPools.get();
        GachaPools.Town town = params.length == 0 ? null : pools.find(params[0]);
        if (town == null) {
            player.yellowMessage("Use !gacharoll <town> [me|<bot name|id>] [count<=11]. Towns: "
                    + String.join(", ", pools.towns().keySet()));
            return;
        }
        Character target = player;
        int count = 1;
        for (int i = 1; i < params.length; i++) {
            String a = params[i];
            if (isInteger(a) && Integer.parseInt(a) <= 11) {   // a count (bot ids are far bigger)
                count = Math.max(1, Integer.parseInt(a));
            } else if (!a.equalsIgnoreCase("me")) {
                target = findBot(a);
                if (target == null) {
                    player.yellowMessage("No online bot " + a);
                    return;
                }
            }
        }
        for (int i = 0; i < count; i++) {
            if (target == player) {
                GachaMachine.Result r = GachaMachine.playForPlayer(player, town, 0, "gm roll");
                if (!r.ok()) {
                    player.yellowMessage(r.refusal());
                    return;
                }
                player.yellowMessage(String.format("%s: %s %s x%d%s", town.name(), r.prize().tier().key(),
                        r.item() == null ? r.prize().itemId() : server.ItemInformationProvider.getInstance()
                                .getName(r.prize().itemId()), r.prize().quantity(), r.announced() ? " (announced)"
                                : r.prize().announce() ? " (notice held back by the rate limit)" : ""));
            } else {
                player.yellowMessage(BotGacha.play(target, town, false, ThreadLocalRandom.current(), true));
            }
        }
    }

    static Character findBot(String key) {
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character chr = sm.getChr();
            if (chr != null && (isInteger(key) ? chr.getId() == Integer.parseInt(key) : chr.getName().equalsIgnoreCase(key))) {
                return chr;
            }
        }
        return null;
    }
}
