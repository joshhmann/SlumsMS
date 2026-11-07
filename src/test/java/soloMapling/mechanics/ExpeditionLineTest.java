package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Round 9, item 6: the one line a human gets when bots run a boss fight for them. "They just stand there" was the
 * owner's own bug report, and from a player's chair a bot that is driving and a bot whose run never started look the
 * same. So: one line, to the leader, once per run, behind {@code -Dslums.mechanics.boss} - and nothing at all when the
 * switch is off or the row is not a boss.
 */
class ExpeditionLineTest {

    private Supplier<ScheduledExecutorService> wasScheduler;

    @AfterEach
    void tearDown() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        System.clearProperty(Mechanics.BOSS_FLAG);
    }

    static Character member(int id, boolean bot) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(bot);
        when(c.getName()).thenReturn("m" + id);
        return c;
    }

    static EventInstanceManager bossRoom(List<Character> players, int leaderId) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        EventManager em = mock(EventManager.class);
        Channel ch = mock(Channel.class);
        when(ch.getId()).thenReturn(3);
        when(ch.getWorld()).thenReturn(1);
        when(em.getChannelServer()).thenReturn(ch);
        when(em.getName()).thenReturn("ZakumBattle");
        when(eim.getEm()).thenReturn(em);
        when(eim.getName()).thenReturn("Zakum0");
        when(eim.getPlayers()).thenReturn(new ArrayList<>(players));
        when(eim.getLeaderId()).thenReturn(leaderId);
        return eim;
    }

    /** The tick never runs: this is about the line at start, not about the fight. */
    private void quietScheduler() {
        ScheduledExecutorService quiet = mock(ScheduledExecutorService.class);
        when(quiet.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenReturn(mock(ScheduledFuture.class));
        wasScheduler = MechanicsRuns.scheduler;
        MechanicsRuns.scheduler = () -> quiet;
    }

    private MechanicsRuns.Active start(EventInstanceManager eim) {
        quietScheduler();
        return MechanicsRuns.get().start(eim, MechanicsRuns.contentFor("ZakumBattle"), true, 0, 1, 0, true,
                line -> { }, null);
    }

    @Test
    void theLeaderIsToldOnceHowManyBotsAreDrivingTheFight() {
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        Character leader = member(9_700, false);
        List<Character> players = new ArrayList<>();
        players.add(leader);
        for (int i = 0; i < 5; i++) {
            players.add(member(9_701 + i, true));
        }
        EventInstanceManager eim = bossRoom(players, 9_700);
        when(eim.getPlayerById(9_700)).thenReturn(leader);

        start(eim);

        verify(leader, times(1)).dropMessage(6, "[Expedition] 5 regulars are running this fight with you (Zakum).");
    }

    @Test
    void theCountIsTheHeldBotsAndNotThePartySize() {
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        Character leader = member(9_800, false);
        Character friend = member(9_801, false);
        List<Character> players = new ArrayList<>(List.of(leader, friend, member(9_804, false), member(9_805, false)));
        players.add(member(9_802, true));
        players.add(member(9_803, true));
        EventInstanceManager eim = bossRoom(players, 9_800);
        when(eim.getPlayerById(9_800)).thenReturn(leader);

        start(eim);

        verify(leader, times(1)).dropMessage(6, "[Expedition] 2 regulars are running this fight with you (Zakum).");
        verify(friend, never()).dropMessage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void withTheBossSwitchOffTheLeaderIsToldNothing() {
        Character leader = member(9_900, false);
        List<Character> players = new ArrayList<>();
        players.add(leader);
        players.add(member(9_901, true));
        for (int i = 0; i < 4; i++) {
            players.add(member(9_910 + i, false));
        }
        EventInstanceManager eim = bossRoom(players, 9_900);
        when(eim.getPlayerById(9_900)).thenReturn(leader);

        start(eim);

        verify(leader, never()).dropMessage(org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void aRowsQualifierIsNotReadOutLoud() {
        assertEquals("Zakum", MechanicsRuns.shortName("Zakum (expedition)"));
        assertEquals("Dunas", MechanicsRuns.shortName("Dunas (TD Battle 2)"));
        assertEquals("Capt. Latanica", MechanicsRuns.shortName("Capt. Latanica"));
        assertEquals("", MechanicsRuns.shortName(""));
    }
}
