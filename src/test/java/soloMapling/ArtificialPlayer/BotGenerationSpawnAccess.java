package soloMapling.ArtificialPlayer;

import client.Character;
import server.maps.MapleMap;

import java.awt.Point;
import java.util.function.Consumer;

/**
 * Lets tests outside the package put a bot on a map exactly the way {@link BotGeneration} does: dressed first, so the
 * spawn packet the map broadcasts carries the dressed look rather than the source character's.
 */
public final class BotGenerationSpawnAccess {

    private BotGenerationSpawnAccess() {
    }

    public static void joinMap(Character bot, Point pos, MapleMap map, Consumer<Character> dressing) {
        BotGeneration.joinMap(bot, pos, map, dressing::accept);
    }
}
