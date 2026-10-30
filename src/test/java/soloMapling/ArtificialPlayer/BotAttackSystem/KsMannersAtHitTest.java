package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.KsManners;

import java.awt.Point;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Audit 163 (B20): KS manners were applied when a population bot picked a target, never when it hit, so a player who
 * walked up to the bot's mob (or whose mob an area attack caught) was KS'd. The attack driver's own filter now asks
 * the same KsManners rule.
 */
class KsMannersAtHitTest {

    private static final int BOT = 1_000_000_000 + 4242, PLAYER = 4242;

    @AfterEach
    void clear() {
        KsManners.forget(BOT);
    }

    @Test
    void aPopulationBotLeavesAPlayersMobAtTheSwing() {
        MapleMap map = mock(MapleMap.class);
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(BOT);
        when(bot.getMap()).thenReturn(map);
        when(bot.getPosition()).thenReturn(new Point(0, 0));
        Character player = mock(Character.class);
        when(player.getId()).thenReturn(PLAYER);
        when(player.getMap()).thenReturn(map);
        when(player.getPosition()).thenReturn(new Point(120, 0)); // walked up next to the bot's mob
        when(map.getCharacters()).thenReturn(List.of(bot, player));
        Monster theirs = mock(Monster.class);
        when(theirs.getPosition()).thenReturn(new Point(100, 0));
        Monster free = mock(Monster.class);
        when(free.getPosition()).thenReturn(new Point(-600, 0));
        assertFalse(BotAttackDriver.mayTarget(bot, theirs), "the player stands by it now: not the bot's to hit");
        assertTrue(BotAttackDriver.mayTarget(bot, free));
    }
}
