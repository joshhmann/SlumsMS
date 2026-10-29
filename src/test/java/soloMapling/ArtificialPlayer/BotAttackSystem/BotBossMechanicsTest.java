package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.Test;
import server.life.Monster;
import server.maps.MapleMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.1 ticket 30: a bot's hit on a boss whose map runs its phases goes through the map's boss rules, as a player's
 * does (MapleMap.damageMonster). Before, a bot's killing blow on Von Leon killed him outright: his last form (8847777)
 * never came, the event's monsterKilled never saw it and the run never cleared; Cygnus' guards and second form the
 * same; and a bot could hit Zakum's body through his arms.
 */
class BotBossMechanicsTest {

    private static Character bot(MapleMap map) {
        Character bot = Character.getDefault(new BotClient(0, 0));
        bot.setID(1_000_000_000 + 8840);
        bot.setMap(map);
        return bot;
    }

    private static Monster mob(int id) {
        Monster m = mock(Monster.class);
        when(m.getId()).thenReturn(id);
        return m;
    }

    @Test
    void vonLeonsDeathByABotBringsHisLastForm() {
        MapleMap map = mock(MapleMap.class);
        Character bot = bot(map);
        Monster vl = mob(8840000);
        when(vl.damage(bot, 1000, false)).thenReturn(true);
        when(map.afterBossKilled(bot, vl)).thenReturn(true);

        assertTrue(BotAttackEffects.applyDamageAndLoot(bot, vl, 1000, (short) 0));
        verify(map).beforeBossDamage(bot, vl);
        verify(map).afterBossKilled(bot, vl);
        verify(map, never()).killMonster(any(Monster.class), any(Character.class), anyBoolean(), anyInt());
    }

    @Test
    void zakumsBodyTakesNoBotHitWhileAnArmIsUp() {
        MapleMap map = mock(MapleMap.class);
        Character bot = bot(map);
        Monster body = mob(8800000);
        when(map.bossDamageBlocked(body)).thenReturn(true);

        assertFalse(BotAttackEffects.applyDamageAndLoot(bot, body, 1000, (short) 0));
        verify(body, never()).damage(any(), anyLong(), anyBoolean());
    }

    @Test
    void anOrdinaryMobKeepsTheBotPath() {
        MapleMap map = mock(MapleMap.class);
        Character bot = bot(map);
        Monster snail = mob(100100);
        when(snail.damage(bot, 10, false)).thenReturn(false);

        assertFalse(BotAttackEffects.applyDamageAndLoot(bot, snail, 10, (short) 0));
        verify(map, never()).beforeBossDamage(any(), any());
        verify(map, never()).bossDamageBlocked(any());
        assertTrue(MapleMap.hasBossMechanics(8850011) && MapleMap.hasBossMechanics(8850004)
                && MapleMap.hasBossMechanics(8800000) && !MapleMap.hasBossMechanics(8800002)
                && !MapleMap.hasBossMechanics(100100));
    }
}
