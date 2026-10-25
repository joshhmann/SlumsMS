package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.GodlyDrops;

/*
 * Test server only (registered with the !qa suite, -Dslums.qa=true; 1.5.2 godly drops):
 *   !godlydrop [equip item id]
 * drops a godly-rolled equip at the GM's feet the way a monster drop would get it: the normal stat roll, then
 * GODLY_DROP_STATS of its stats +GODLY_DROP_MIN_BONUS..GODLY_DROP_MAX_BONUS, marked godly. Loot it to see the
 * [Godly] note; bots will not sell it or list it in their shops. Default item: a Sword (1302000).
 */
public class GodlyDropCommand extends Command {
    {
        setDescription("Test server: drop a godly-rolled equip at your feet (as a rare mob drop would roll).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int itemId = GodlyDrops.STAGE_DEFAULT_ITEM;
        if (params.length > 0) {
            try {
                itemId = Integer.parseInt(params[0]);
            } catch (NumberFormatException e) {
                player.yellowMessage("Syntax: !godlydrop [equip item id]   e.g. !godlydrop 1302000");
                return;
            }
        }
        String dropped = GodlyDrops.stageDrop(player, itemId);
        if (dropped == null) {
            player.yellowMessage(itemId + " is not an equip with stats to bump.");
            return;
        }
        player.yellowMessage("Godly drop: " + dropped + ". Loot it: you should see a [Godly] note.");
    }
}
