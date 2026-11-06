package soloMapling.mechanics;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;
import server.maps.Reactor;
import server.maps.ReactorStats;

import java.awt.Point;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 1.6.5 b331 (Server Dev, packets + WZ + code, 2026-11-06): ZPQ chest 2112012 links to 2112007, states 0-4, and
 * state 4 has no hit (type -1). A real client never hits a reactor in such a state; the solver's server-side hit
 * did, Reactor.hitReactor's else-branch (:302-314) moved it to state 5, broadcast REACTOR_HIT state 5 and ran act
 * again (an extra drop), and the players' clients terminated on the state they do not have.
 * A bot hits only a reactor whose current state a client could hit.
 */
class ReactorClientHitRuleTest {

    /**
     * 2112007's shape as ReactorFactory loads it: four hittable states, then the WZ state with no event, which
     * addInertState records as type 999 (INERT_STATE), next state -1 (Server Dev review; ReactorLastStateTest.rock()).
     */
    static Reactor chest(byte state) {
        ReactorStats stats = new ReactorStats();
        for (byte s = 0; s < 4; s++) {
            stats.addState(s, 0, null, (byte) (s + 1), -1, (byte) 0);
        }
        stats.addState((byte) 4, 999, null, (byte) -1, 0, (byte) 0); // what addInertState records
        Reactor r = spy(new Reactor(stats, 2_112_012));
        r.setState(state);
        r.setPosition(new Point(100, 0));
        doNothing().when(r).hitReactor(any(Client.class));
        return r;
    }

    static Character botAtTheChest() {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(1_000_331_002);
        c.setPosition(new Point(100, 0));
        return c;
    }

    static void hitAt(Character bot, Reactor reactor) throws Exception {
        Method m = StepExecutor.class.getDeclaredMethod("hitReactorAt", Character.class, StepExecutor.Beat.class,
                Reactor.class, String.class, long.class, Consumer.class);
        m.setAccessible(true);
        List<String> log = new ArrayList<>();
        m.invoke(null, bot, new StepExecutor.Beat(), reactor, "2112012", System.currentTimeMillis(),
                (Consumer<String>) log::add);
    }

    @Test
    void aBotDoesNotHitAChestInAStateNoClientCanHit() throws Exception {
        Reactor spent = chest((byte) 4);
        hitAt(botAtTheChest(), spent);
        verify(spent, never()).hitReactor(any(Client.class));
    }

    @Test
    void aBotStillHitsAChestInAHittableState() throws Exception {
        Reactor live = chest((byte) 3);
        hitAt(botAtTheChest(), live);
        verify(live, times(1)).hitReactor(any(Client.class));
    }
}
