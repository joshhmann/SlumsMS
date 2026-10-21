package server.life;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 1.4.1: mobs froze on a map whose only player was a GM in /hide - nobody was allowed to control them. */
class MonsterControllerHiddenGmTest {

    private static Character player(boolean hidden, boolean alive, int controlling) {
        Character chr = mock(Character.class);
        when(chr.getClient()).thenReturn(mock(Client.class));
        when(chr.isHidden()).thenReturn(hidden);
        when(chr.isAlive()).thenReturn(alive);
        when(chr.getNumControlledMonsters()).thenReturn(controlling);
        return chr;
    }

    private static Character bot() {
        Character chr = mock(Character.class);
        when(chr.getClient()).thenReturn(new BotClient(0, 0));
        when(chr.isAlive()).thenReturn(true);
        return chr;
    }

    @Test
    void aHiddenGmAloneWithBotsControlsTheMobsSoTheyKeepMoving() {
        Character gm = player(true, true, 0);
        assertSame(gm, Monster.pickControllerCandidate(List.of(bot(), gm, bot()), c -> false));
    }

    @Test
    void anyVisiblePlayerWinsOverAHiddenGmEvenADeadOne() {
        Character gm = player(true, true, 0);
        Character visible = player(false, true, 30);
        assertSame(visible, Monster.pickControllerCandidate(List.of(gm, visible), c -> false));
        Character dead = player(false, false, 30);
        assertSame(dead, Monster.pickControllerCandidate(List.of(gm, dead), c -> false));
    }

    @Test
    void botsAndDeadHiddenGmsNeverControl() {
        assertNull(Monster.pickControllerCandidate(List.of(bot(), bot()), c -> false));
        assertNull(Monster.pickControllerCandidate(List.of(player(true, false, 0)), c -> false));
    }

    @Test
    void aPuppetStillWinsAmongVisiblePlayers() {
        Character puppeteer = player(false, true, 50);
        Character other = player(false, true, 0);
        assertSame(puppeteer, Monster.pickControllerCandidate(List.of(other, puppeteer), c -> c == puppeteer));
    }

    @Test
    void aHiddenControllerKeepsTheMobUntilAVisiblePlayerCanTakeIt() {
        Character gm = player(true, true, 3);
        Character otherGm = player(true, true, 0);
        Character visible = player(false, true, 0);
        assertTrue(Monster.keepsCurrentController(gm, gm));
        assertTrue(Monster.keepsCurrentController(gm, otherGm), "no churn between hidden GMs");
        assertFalse(Monster.keepsCurrentController(gm, visible), "a visible player takes over");
        assertFalse(Monster.keepsCurrentController(null, gm));
        assertFalse(Monster.keepsCurrentController(visible, gm));
    }
}
