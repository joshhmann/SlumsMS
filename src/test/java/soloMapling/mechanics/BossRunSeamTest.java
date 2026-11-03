package soloMapling.mechanics;

import client.Character;
import net.server.channel.Channel;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 130 phase 5 phase A1: {@code EventManager.startInstance(int, Expedition, Character)} arms a Mechanics run for
 * a real boss expedition, behind {@code -Dslums.mechanics.boss} (default off). Before this, the expedition path never
 * called {@link MechanicsRuns#onInstanceStarted}: a Zakum party with bots in it started a fight no bot joined.
 */
class BossRunSeamTest {

    /** The bot log, captured: the no-data case is one line and no run. */
    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("mechanics-capture", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    @TempDir
    Path dataDir;

    private final Capture capture = new Capture();
    private Logger botLog;
    private Level level;
    private Supplier<ScheduledExecutorService> wasScheduler;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(dataDir.resolve("index.txt"), "zakum.yaml\ndunas.yaml\n");
        Files.writeString(dataDir.resolve("dunas.yaml"), """
                id: dunas
                name: Dunas
                kind: BOSS
                eventScript: TD_Battle2
                recruitMap: 240070302
                exitMap: 240070302
                minPlayers: 1
                maxPlayers: 6
                minLevel: 70
                maxLevel: 255
                timeLimitMinutes: 10
                sources:
                  - "scripts/event/TD_Battle2.js (ours: the party instance, minPlayers 1, no property on clear)"
                stages:
                  - stage: 1
                    map: 240070303
                    name: Dunas
                    solver: boss-phases
                    spawnWaitSeconds: 120
                    mobs: [ 8220010 ]
                    objectives:
                      - id: boss
                        kind: DEFEAT
                        mob: 8220010
                    clear:
                      instance: true
                """);
        Files.writeString(dataDir.resolve("zakum.yaml"), """
                id: zakum
                name: Zakum
                kind: BOSS
                eventScript: ZakumBattle
                recruitMap: 211042400
                exitMap: 280030000
                minPlayers: 1
                maxPlayers: 30
                minLevel: 50
                maxLevel: 255
                timeLimitMinutes: 120
                sources:
                  - "scripts/event/ZakumBattle.js (ours: the altar, the 120-minute timer, defeatedBoss)"
                stages:
                  - stage: 1
                    map: 280030000
                    name: Zakum
                    solver: kill-collect-hand-in
                    objectives:
                      - id: zakum
                        kind: DEFEAT
                        mob: 8800002
                    clear:
                      eventProperty: defeatedBoss
                """);
        System.setProperty(Mechanics.DIR_PROPERTY, dataDir.toString());

        // the tick never runs: this test is about the seam, not about the fight
        ScheduledExecutorService quiet = mock(ScheduledExecutorService.class);
        when(quiet.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenReturn(mock(ScheduledFuture.class));
        wasScheduler = MechanicsRuns.scheduler;
        MechanicsRuns.scheduler = () -> quiet;

        botLog = (Logger) LogManager.getLogger("soloMapling.BotLog");
        level = botLog.getLevel();
        Configurator.setLevel(botLog.getName(), Level.INFO);
        capture.start();
        botLog.addAppender(capture);
    }

    @AfterEach
    void tearDown() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        botLog.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(botLog.getName(), level);
        System.clearProperty(Mechanics.DIR_PROPERTY);
        System.clearProperty(Mechanics.BOSS_FLAG);
    }

    static Character member(int id, boolean bot) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.isBot()).thenReturn(bot);
        when(c.getName()).thenReturn("m" + id);
        return c;
    }

    static EventInstanceManager expedition(String eventScript, List<Character> players, int leaderId) {
        EventInstanceManager eim = mock(EventInstanceManager.class);
        EventManager em = mock(EventManager.class);
        Channel ch = mock(Channel.class);
        when(ch.getId()).thenReturn(3);
        when(ch.getWorld()).thenReturn(1);
        when(em.getChannelServer()).thenReturn(ch);
        when(em.getName()).thenReturn(eventScript);
        when(eim.getEm()).thenReturn(em);
        when(eim.getName()).thenReturn("Zakum0");
        when(eim.getPlayers()).thenReturn(new ArrayList<>(players));
        when(eim.getLeaderId()).thenReturn(leaderId);
        return eim;
    }

    @Test
    void aZakumExpeditionArmsARunForItsBots() {
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        Character leader = member(9_600, false);
        Character a = member(9_601, true);
        Character b = member(9_602, true);
        EventInstanceManager eim = expedition("ZakumBattle", List.of(leader, a, b), 9_600);

        MechanicsRuns.Active run = MechanicsRuns.onInstanceStarted(eim, true);

        assertNotNull(run, "a BOSS row and the switch on: the expedition gets a run");
        assertEquals(run, MechanicsRuns.get().runFor(eim));
        assertFalse(run.run().ended(), run.run().endedBecause());
        assertEquals(3, run.run().members().size(), "the human leader and both bots, nobody else");
        assertTrue(run.run().member(9_600).leader(), "the run is the leader's");
        assertFalse(run.run().member(9_600).bot(), "a human is planned around, never driven");
        assertTrue(run.run().member(9_601).bot());
        assertTrue(run.run().member(9_602).bot());
        assertEquals(List.of(a, b), run.bots(), "the run holds exactly the two bots");
    }

    @Test
    void theSwitchOffLeavesTheExpeditionAlone() {
        Character leader = member(9_610, false);
        EventInstanceManager eim = expedition("ZakumBattle", List.of(leader, member(9_611, true)), 9_610);

        assertNull(MechanicsRuns.onInstanceStarted(eim, true));
        assertNull(MechanicsRuns.get().runFor(eim), "default off: today's behaviour, byte for byte");
        assertTrue(capture.lines.isEmpty(), capture.lines.toString());
    }

    @Test
    void aTdBattleInstanceStaysInertWhileTheBossSwitchIsOff() {
        EventInstanceManager eim = expedition("TD_Battle2",
                List.of(member(9_630, false), member(9_631, true)), 9_630);

        assertNull(MechanicsRuns.onInstanceStarted(eim, false),
                "the switch is unset: a portal-entered party instance arms nothing");
        assertNull(MechanicsRuns.get().runFor(eim), "default off: today's behaviour, byte for byte");
        assertTrue(capture.lines.isEmpty(), capture.lines.toString());
    }

    @Test
    void aTdBattleInstanceArmsARunForItsBotsWhenTheSwitchIsOn() {
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        Character leader = member(9_640, false);
        Character bot = member(9_641, true);
        EventInstanceManager eim = expedition("TD_Battle2", List.of(leader, bot), 9_640);

        MechanicsRuns.Active run = MechanicsRuns.onInstanceStarted(eim, false);

        assertNotNull(run, "a party instance of a BOSS row is a run too: the kind decides, not the caller");
        assertEquals(List.of(bot), run.bots());
        assertEquals("dunas", MechanicsRuns.contentFor("TD_Battle2").id(), "the row the instance matched");
        assertEquals(240070303, run.run().stageData().mapId());
    }

    @Test
    void aBossWithoutContentIsANoOpWithOneLine() {
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        Character leader = member(9_620, false);
        EventInstanceManager eim = expedition("HorntailBattle", List.of(leader, member(9_621, true)), 9_620);

        assertNull(MechanicsRuns.onInstanceStarted(eim, true));
        assertNull(MechanicsRuns.get().runFor(eim), "no row, no run: the humans fight it");
        assertEquals(1, capture.lines.size(), capture.lines.toString());
        assertTrue(capture.lines.get(0).contains("no boss content"), capture.lines.toString());
        assertTrue(capture.lines.get(0).contains("HorntailBattle"), capture.lines.toString());
    }
}
