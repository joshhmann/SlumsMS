package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;

import java.util.Arrays;
import java.util.List;

/**
 * {@code !killmob <mobId>}: kills every monster with that id on the caller's map, as the caller, through the same
 * {@link MapleMap#damageMonster} path a player's hit takes, so event scripts see a real kill (Horntail's
 * {@code defeatedHead}, PQ stage counters). {@code !killall} skips Horntail's parts on purpose; QA's scripted boss runs
 * need to open the rooms they seal (1.6.5, TEST QA tooling).
 */
public class KillMobCommand extends Command {
    {
        setDescription("Kill every monster with the given id in the map, counted as your kill.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int mobId;
        try {
            mobId = params.length == 1 ? Integer.parseInt(params[0]) : -1;
        } catch (NumberFormatException e) {
            mobId = -1;
        }
        if (mobId <= 0) {
            player.yellowMessage("Syntax: !killmob <mobId>");
            return;
        }
        player.dropMessage(5, "Killed " + killAll(player, player.getMap(), mobId) + " x mob " + mobId + ".");
    }

    static int killAll(Character player, MapleMap map, int mobId) {
        List<MapObject> monsters = map.getMapObjectsInRange(player.getPosition(), Double.POSITIVE_INFINITY,
                Arrays.asList(MapObjectType.MONSTER));
        int count = 0;
        for (MapObject mo : monsters) {
            Monster monster = (Monster) mo;
            if (monster.getId() == mobId && monster.isAlive() && !monster.getStats().isFriendly()) {
                map.damageMonster(player, monster, monster.getMaxHp());
                count++;
            }
        }
        return count;
    }
}
