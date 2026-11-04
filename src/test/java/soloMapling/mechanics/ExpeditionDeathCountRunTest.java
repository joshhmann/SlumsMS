package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import net.server.world.World;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;

import javax.script.Invocable;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The death count of a real expedition instance (ticket 130 phase 5, phase 1): humans spend the party's budget, bots
 * never do, and the death that spends the last one ends the run with everyone out and the instance disposed.
 *
 * <p>A real run's death path is {@code Character.playerDead() -> EventInstanceManager.playerKilled(chr)}; the script
 * ({@code scripts/event/ZakumBattle.js}) ends a run through {@code scheduledTimeout(eim) -> end(eim) -> eim.dispose()},
 * which is what the Invocable here stands in for.
 */
class ExpeditionDeathCountRunTest {

    private static final String FLAG = ExpeditionDeathCount.FLAG;
    private static final String CAP = ExpeditionDeathCount.CAP_PROPERTY;

    private EventInstanceManager eim;
    private Invocable iv;

    /** {@code playerKilled} hands the script call to the shared task pool and the run end rides the timer. */
    @BeforeAll
    static void startPools() {
        server.ThreadManager.getInstance().start();
        server.TimerManager.getInstance().start();
    }

    @AfterAll
    static void stopPools() {
        server.ThreadManager.getInstance().stop();
        server.TimerManager.getInstance().stop();
    }

    @AfterEach
    void switchesOff() {
        System.clearProperty(FLAG);
        System.clearProperty(CAP);
        System.clearProperty(CAP + ".zakum");
    }

    /** A Zakum run with the death count armed for {@code deaths}. */
    private EventInstanceManager zakumRun(int deaths) throws Exception {
        System.setProperty(FLAG, "true");
        System.setProperty(CAP, String.valueOf(deaths));
        return buildRun();
    }

    /** A Zakum run with the switch off, as every server runs today. */
    private EventInstanceManager runWithTheSwitchOff() throws Exception {
        System.setProperty(CAP, "1"); // set, so the run is armed only if the switch decides it
        return buildRun();
    }

    private EventInstanceManager buildRun() throws Exception {
        EventManager em = mock(EventManager.class);
        when(em.getName()).thenReturn("ZakumBattle");
        when(em.getWorldServer()).thenReturn(mock(World.class));
        when(em.getChannelServer()).thenReturn(mock(Channel.class));
        iv = mock(Invocable.class);
        when(em.getIv()).thenReturn(iv);

        eim = new EventInstanceManager(em, "Zakum1");
        // ZakumBattle.js: scheduledTimeout(eim) { end(eim); } and end() rides dispose() out
        doAnswer(invocation -> {
            eim.dispose();
            return null;
        }).when(iv).invokeFunction(eq("scheduledTimeout"), any());

        Expedition exped = mock(Expedition.class);
        when(exped.getType()).thenReturn(ExpeditionType.ZAKUM);
        when(exped.getRecruitingMap()).thenReturn(mock(MapleMap.class));
        when(exped.getActiveMembers()).thenReturn(List.of());
        eim.registerExpedition(exped);
        return eim;
    }

    private Character member(String name) {
        return register(name, false, false);
    }

    private Character botMember(String name) {
        return register(name, true, false);
    }

    private Character register(String name, boolean bot, boolean ownedAlt) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(name.hashCode());
        when(chr.getName()).thenReturn(name);
        when(chr.isLoggedinWorld()).thenReturn(true);
        when(chr.isBot()).thenReturn(bot);
        when(chr.isOwnedAlt()).thenReturn(ownedAlt);
        when(chr.getEventInstance()).thenReturn(eim);
        eim.registerPlayer(chr);
        return chr;
    }

    @Test
    void theLastDeathOfTheBudgetEndsTheRun() throws Exception {
        zakumRun(2);
        Character human = member("Human1");

        eim.playerKilled(human); // 1 left
        eim.playerKilled(human); // none left, still inside
        assertFalse(eim.isEventDisposed(), "the run continues while the budget is spent but not empty");

        eim.playerKilled(human); // no budget: the run ends
        verify(human, timeout(4000)).dropMessage(eq(5), contains("no deaths left"));
        assertTrue(waitForDispose(4000), "the instance is disposed once the death count runs out");
    }

    /** The end rides DEATH_COUNT_END_DELAY_MS, so wait for the dispose instead of racing it. */
    private boolean waitForDispose(long millis) throws InterruptedException {
        long until = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < until) {
            if (eim.isEventDisposed()) {
                return true;
            }
            Thread.sleep(25);
        }
        return eim.isEventDisposed();
    }

    @Test
    void humansAndBotsSpendTheBudgetButNotAnOwnedAlt() throws Exception {
        zakumRun(10);
        Character human = member("Human1");
        Character bot = botMember("Bot1");
        Character alt = register("Alt1", false, true);

        eim.playerKilled(human);
        assertEquals(9, eim.getDeathCount().left());

        // 1.6.4: bots are mortal, so their deaths are the party's loss - the whole point of the counter
        eim.botKilled(bot);
        eim.botKilled(bot);
        assertEquals(7, eim.getDeathCount().left(), "each bot death spends one");

        eim.playerKilled(alt);
        assertEquals(7, eim.getDeathCount().left(), "an owned alt's death is its player's own business");
        assertFalse(eim.isEventDisposed());
    }

    @Test
    void aBotPartyWipingEndsAnArmedRunLikeAnyOtherParty() throws Exception {
        zakumRun(1);
        Character bot = botMember("Bot1");

        for (int i = 0; i < 5; i++) {
            eim.botKilled(bot);
        }

        assertEquals(0, eim.getDeathCount().left(), "the first bot death spends the only one");
    }

    @Test
    void theSwitchOffLeavesTheDeathPathAlone() throws Exception {
        runWithTheSwitchOff();
        Character human = member("Human1");

        for (int i = 0; i < 4; i++) {
            eim.playerKilled(human);
        }

        assertEquals(-1, eim.getDeathCount().left(), "nothing is armed while the switch is off");
        assertFalse(eim.isEventDisposed());
        verify(human, after(400).never()).dropMessage(eq(5), anyString());
    }
}
