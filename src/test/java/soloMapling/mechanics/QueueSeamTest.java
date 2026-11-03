package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import client.Character;
import net.server.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import soloMapling.mechanics.MechanicsModel.Content;

/**
 * Guild Quest is the one party quest nobody enters through {@code EventManager.startInstance(party, map)}: its door NPC
 * registers the <em>guild</em> with the world queue ({@code scripts/npc/9040000.js:70-91}: {@code em.isQueueFull()},
 * {@code getQueueSize()}, {@code addGuildToQueue}), and when its turn comes the world starts the instance with the guild's
 * master alone inside ({@code EventManager.java:959-976}: {@code attemptStartGuildInstance} -> {@code startInstance(chr)}),
 * after which each other member talks to the same NPC to be registered, within the strategy time
 * ({@code 9040000.js:54-65}, {@code GuildQuest.js:203-222}). The run engine only ever armed a run for an instance a party
 * started, so an instance the queue started was never seen by it. These tests hold the seam: a row that says
 * {@code queue:} is watched from the moment the world starts its instance, and armed when the join window closes - for
 * the party the guild brought, not the one master it began with.
 */
class QueueSeamTest {

    @TempDir
    Path dataDir;

    private Supplier<ScheduledExecutorService> wasScheduler;
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> watching;

    private static String row(String queueBlock) {
        return """
                id: guildtest
                name: Guild Test
                kind: PQ
                eventScript: GuildTestEvent
                entryNpc: 9040000
                recruitMap: 101030104
                exitMap: 990001100
                minPlayers: 6
                maxPlayers: 30
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 90
                flag: slums.mechanics.guildtest
                sources:
                  - "scripts/npc/9040000.js:54-91 and scripts/event/GuildQuest.js:203-222"
                %sstages:
                  - stage: 1
                    map: 990000000
                    name: The waiting room
                    solver: hold
                    objectives:
                      - id: wait
                        kind: TALK_NPC
                        npc: 9040011
                    clear:
                      partyOnMap: 990000100
                """.formatted(queueBlock);
    }

    private static final String QUEUE = """
            queue:
              register: [0, 0]
              join: [1]
              waitMinutes: 3
              closedProperty: canJoin
              closedEquals: "0"
            """;

    private void install(String queueBlock) throws IOException {
        Files.writeString(dataDir.resolve("index.txt"), "guildtest.yaml\n");
        Files.writeString(dataDir.resolve("guildtest.yaml"), row(queueBlock));
        System.setProperty(Mechanics.DIR_PROPERTY, dataDir.toString());
    }

    @BeforeEach
    @SuppressWarnings("unchecked")
    void quietScheduler() {
        scheduler = mock(ScheduledExecutorService.class);
        watching = mock(ScheduledFuture.class);
        when(scheduler.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any()))
                .thenAnswer(inv -> watching);
        wasScheduler = MechanicsRuns.scheduler;
        MechanicsRuns.scheduler = () -> scheduler;
    }

    @AfterEach
    void tearDown() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        System.clearProperty(Mechanics.DIR_PROPERTY);
        System.clearProperty(Mechanics.FLAG);
        System.clearProperty("slums.mechanics.guildtest");
    }

    private static void switchOn() {
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty("slums.mechanics.guildtest", "true");
    }

    private static Character member(int id, boolean bot) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(bot);
        when(c.getName()).thenReturn("g" + id);
        when(c.getLevel()).thenReturn(50);
        return c;
    }

    private static EventInstanceManager queued(List<Character> players, int leader, String canJoin) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        EventManager em = mock(EventManager.class);
        Channel ch = mock(Channel.class);
        when(ch.getId()).thenReturn(1);
        when(ch.getWorld()).thenReturn(1);
        when(em.getChannelServer()).thenReturn(ch);
        when(em.getName()).thenReturn("GuildTestEvent");
        when(eim.getEm()).thenReturn(em);
        when(eim.getName()).thenReturn("Guild0");
        when(eim.getPlayers()).thenReturn(new ArrayList<>(players));
        when(eim.getLeaderId()).thenReturn(leader);
        when(eim.getProperty("canJoin")).thenReturn(canJoin);
        return eim;
    }

    private static List<Character> guild(int firstId, int humans, int bots) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < humans; i++) {
            out.add(member(firstId + i, false));
        }
        for (int i = 0; i < bots; i++) {
            out.add(member(firstId + humans + i, true));
        }
        return out;
    }

    // ---- the row fact --------------------------------------------------------------------------------

    @Test
    void theQueueIsARowFact() throws IOException {
        install(QUEUE);
        Content c = MechanicsRuns.contentFor("GuildTestEvent");

        assertNotNull(c, "the row loads");
        MechanicsModel.Queue q = c.queue();
        assertEquals(List.of(0, 0), q.register());
        assertEquals(List.of(1), q.join());
        assertEquals("canJoin", q.closedProperty());
        assertEquals(3, q.waitMinutes());
        assertTrue(q.closed("0") && q.closed(" 0 "));
        assertFalse(q.closed("1") || q.closed(null));
    }

    @Test
    void aRowWithoutAQueueHasNone() throws IOException {
        install("");

        assertNull(MechanicsRuns.contentFor("GuildTestEvent").queue());
    }

    @Test
    void aQueueThatCouldNeverWorkIsRefusedAtLoad() throws IOException {
        String[][] bad = {
                {"queue:\n  join: [1]\n  closedProperty: canJoin\n  closedEquals: \"0\"\n", "register"},
                {"queue:\n  register: [0]\n  closedProperty: canJoin\n  closedEquals: \"0\"\n", "join"},
                {"queue:\n  register: [0]\n  join: [1]\n", "closedProperty"},
                {"queue:\n  register: [0, x]\n  join: [1]\n  closedProperty: a\n  closedEquals: b\n", "not a number"},
                {"queue:\n  register: [0]\n  join: [1]\n  closedProperty: a\n  closedEquals: b\n  waitMinutes: -1\n", "waitMinutes"},
                {"queue: yes\n", "not a map"},
        };
        for (int i = 0; i < bad.length; i++) {
            Path d = dataDir.resolve("bad" + i);
            Files.createDirectories(d);
            Files.writeString(d.resolve("index.txt"), "guildtest.yaml\n");
            Files.writeString(d.resolve("guildtest.yaml"), row(bad[i][0]));
            List<String> errors = StageData.loadIndexed(d + "/", Path::of).errors();
            String must = bad[i][1];
            assertTrue(errors.stream().anyMatch(e -> e.contains(must)), "case " + i + " (" + must + "): " + errors);
        }
    }

    // ---- the seam ------------------------------------------------------------------------------------

    @Test
    void anInstanceTheQueueStartedIsWatchedFromTheMomentItStarts() throws IOException {
        install(QUEUE);
        switchOn();
        EventInstanceManager eim = queued(guild(100, 1, 0), 100, "1"); // the guild master alone, as the world starts it

        assertTrue(MechanicsRuns.onQueueInstanceStarted(eim), "a queue row with its switch on is watched");

        verify(scheduler, times(1)).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
        assertNull(MechanicsRuns.get().runFor(eim), "no run yet: one member is not the party the guild is bringing");
    }

    @Test
    void theRunIsArmedWhenTheJoinWindowClosesForThePartyTheGuildBrought() throws IOException {
        install(QUEUE);
        switchOn();
        List<Character> players = new ArrayList<>(guild(200, 1, 0));
        EventInstanceManager eim = queued(players, 200, "1");
        MechanicsRuns.onQueueInstanceStarted(eim);
        Runnable watch = capturedWatch();

        watch.run();
        assertNull(MechanicsRuns.get().runFor(eim), "the window is still open");
        verify(watching, never()).cancel(false);

        players.addAll(guild(201, 0, 5)); // five bots talked to the NPC meanwhile
        when(eim.getPlayers()).thenReturn(new ArrayList<>(players));
        when(eim.getProperty("canJoin")).thenReturn("0"); // GuildQuest.js:206-207
        watch.run();

        MechanicsRuns.Active run = MechanicsRuns.get().runFor(eim);
        assertNotNull(run, "armed");
        assertEquals(6, run.run().members().size(), "the whole party, not the master it began with");
        verify(watching, times(1)).cancel(false);
        assertFalse(run.run().member(200).bot(), "the human master is planned around, never steered");
    }

    @Test
    void aGuildOfOnlyHumansGetsNoRun() throws IOException {
        install(QUEUE);
        switchOn();
        EventInstanceManager eim = queued(guild(300, 6, 0), 300, "0");
        MechanicsRuns.onQueueInstanceStarted(eim);

        capturedWatch().run();

        assertNull(MechanicsRuns.get().runFor(eim), "nothing to drive");
    }

    @Test
    void anInstanceThatEndedFirstIsGivenUpOn() throws IOException {
        install(QUEUE);
        switchOn();
        EventInstanceManager eim = queued(guild(400, 1, 5), 400, "1");
        MechanicsRuns.onQueueInstanceStarted(eim);
        when(eim.isEventDisposed()).thenReturn(true);

        capturedWatch().run();

        assertNull(MechanicsRuns.get().runFor(eim));
        verify(watching, times(1)).cancel(false);
    }

    @Test
    void theSwitchOffAndANonQueueInstanceAreLeftAlone() throws IOException {
        install(QUEUE);
        EventInstanceManager off = queued(guild(500, 1, 5), 500, "0");
        assertFalse(MechanicsRuns.onQueueInstanceStarted(off), "default off: today's behaviour, byte for byte");

        switchOn();
        EventInstanceManager other = queued(guild(510, 1, 5), 510, "0");
        when(other.getEm().getName()).thenReturn("SoloQuestEvent");
        assertFalse(MechanicsRuns.onQueueInstanceStarted(other), "a solo event has no queue row");

        EventInstanceManager nobody = mock(EventInstanceManager.class);
        assertFalse(MechanicsRuns.onQueueInstanceStarted(nobody), "an instance with no event manager");
        assertFalse(MechanicsRuns.onQueueInstanceStarted(null));
        verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }

    @Test
    void aRowThatIsNotQueueEnteredIsNotWatchedEvenWithItsSwitchOn() throws IOException {
        install("");
        switchOn();

        assertFalse(MechanicsRuns.onQueueInstanceStarted(queued(guild(520, 1, 5), 520, "0")));
        verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }

    @Test
    void aStagingCallThatRunsTheInstanceItselfHoldsTheWatchOff() throws IOException {
        install(QUEUE);
        switchOn();
        EventInstanceManager eim = queued(guild(600, 1, 5), 600, "0");

        boolean watched = MechanicsRuns.staging(() -> MechanicsRuns.onQueueInstanceStarted(eim));

        assertFalse(watched, "staging arms its own run; a second one on the same instance would be refused anyway");
        verify(scheduler, never()).scheduleWithFixedDelay(any(), anyLong(), anyLong(), any());
    }

    @Test
    void theWatchWaitsOutTheWindowThenGivesUp() {
        long patience = 3 * 60_000L + LobbyWatch.GRACE_MS;

        assertEquals(LobbyWatch.Verdict.WAIT, LobbyWatch.verdict(false, false, 10_000, patience));
        assertEquals(LobbyWatch.Verdict.ARM, LobbyWatch.verdict(false, true, 10_000, patience));
        assertEquals(LobbyWatch.Verdict.GIVE_UP, LobbyWatch.verdict(false, false, patience + 1, patience));
        assertEquals(LobbyWatch.Verdict.GIVE_UP, LobbyWatch.verdict(true, true, 10_000, patience), "an ended instance never arms");
        assertEquals(LobbyWatch.Verdict.ARM, LobbyWatch.verdict(false, true, patience + 1, patience),
                "a window that closes at the last moment still arms");
    }

    private Runnable capturedWatch() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).scheduleWithFixedDelay(task.capture(), anyLong(), anyLong(), any());
        return task.getValue();
    }
}
