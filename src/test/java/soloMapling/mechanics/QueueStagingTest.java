package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import scripting.event.EventInstanceManager;
import scripting.event.EventManager;
import server.maps.MapleMap;
import soloMapling.mechanics.MechanicsModel.Content;
import testutil.PropsRestored;

/**
 * The staging half of the queue seam: a guild goes through Guild Quest's door the way {@code scripts/npc/9040000.js} does
 * it, in the script's order, with the script's own checks, and a row's composition is settled <em>before</em> the guild is
 * registered (registering starts an instance that would have to be ended again). The door and the seater are fakes here;
 * the NPC's own conversation is {@link QueueStaging.NpcDoor}, which runs the shipped script for a bot.
 */
@ExtendWith(PropsRestored.class)
class QueueStagingTest {

    @TempDir
    Path dataDir;

    private Supplier<ScheduledExecutorService> wasScheduler;
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    @SuppressWarnings("unchecked")
    void quietScheduler() throws IOException {
        ScheduledExecutorService quiet = mock(ScheduledExecutorService.class);
        when(quiet.scheduleWithFixedDelay(any(), anyLong(), anyLong(), any())).thenReturn(mock(ScheduledFuture.class));
        wasScheduler = MechanicsRuns.scheduler;
        MechanicsRuns.scheduler = () -> quiet;
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty("slums.mechanics.guildstage", "true");
    }

    @AfterEach
    void tearDown() {
        MechanicsRuns.get().stopAll("test over");
        MechanicsRuns.scheduler = wasScheduler;
        System.clearProperty(Mechanics.FLAG);
        System.clearProperty("slums.mechanics.guildstage");
    }

    private Content row(boolean withJunior) throws IOException {
        Files.writeString(dataDir.resolve("index.txt"), "guildstage.yaml\n");
        Files.writeString(dataDir.resolve("guildstage.yaml"), """
                id: guildstage
                name: Guild Stage
                kind: PQ
                eventScript: GuildStageEvent
                entryNpc: 9040000
                recruitMap: 101030104
                exitMap: 990001100
                minPlayers: 6
                maxPlayers: 30
                minLevel: 1
                maxLevel: 255
                timeLimitMinutes: 90
                flag: slums.mechanics.guildstage
                sources:
                  - "scripts/npc/9040000.js:54-91"
                queue:
                  register: [0, 0]
                  join: [1]
                  waitMinutes: 3
                  closedProperty: canJoin
                  closedEquals: "0"
                """ + (withJunior ? """
                composition:
                  - id: junior
                    minLevel: 10
                    maxLevel: 30
                    min: 1
                """ : "") + """
                stages:
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
                """);
        System.setProperty(Mechanics.DIR_PROPERTY, dataDir.toString());
        return MechanicsRuns.contentFor("GuildStageEvent");
    }

    private static Character chr(int id, int level, int guild, boolean master) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("g" + id);
        when(c.getLevel()).thenReturn(level);
        when(c.getGuildId()).thenReturn(guild);
        when(c.isGuildLeader()).thenReturn(master);
        when(c.isBot()).thenReturn(true);
        return c;
    }

    private static List<Character> botsOfLevel(int firstId, int guild, int... levels) {
        List<Character> out = new ArrayList<>();
        for (int i = 0; i < levels.length; i++) {
            out.add(chr(firstId + i, levels[i], guild, false));
        }
        return out;
    }

    /** The world's queue as the script sees it: an event manager whose instances the fake door fills. */
    private static final class World {
        final EventManager em = mock(EventManager.class);
        final EventInstanceManager eim = mock(EventInstanceManager.class);
        final List<Character> inside = new ArrayList<>();
        final List<String> calls = new ArrayList<>();
        String registerRefusal;
        boolean startInstance = true;
        List<Integer> refuseJoin = new ArrayList<>();

        World(int queueSize, int leaderId) {
            when(em.getQueueSize()).thenReturn(queueSize);
            when(em.isQueueFull()).thenReturn(queueSize >= 5);
            when(eim.getLeaderId()).thenReturn(leaderId);
            when(eim.getProperty("canJoin")).thenReturn("1");
            when(eim.getName()).thenReturn("Guild0");
            when(eim.getPlayers()).thenAnswer(inv -> new ArrayList<>(inside));
            EventManager mgr = em;
            Channel ch = mock(Channel.class);
            when(ch.getId()).thenReturn(1);
            when(ch.getWorld()).thenReturn(1);
            when(mgr.getChannelServer()).thenReturn(ch);
            when(mgr.getName()).thenReturn("GuildStageEvent");
            when(eim.getEm()).thenReturn(mgr);
        }

        QueueStaging.Door door(Character master) {
            return new QueueStaging.Door() {
                @Override
                public String register(Character m) {
                    calls.add("register " + m.getId());
                    // the world starts the instance synchronously when nobody is ahead (EventManager.java:959-976);
                    // a start inside staging must never be watched by the lobby watch
                    assertFalse(MechanicsRuns.onQueueInstanceStarted(eim), "held off while staging runs");
                    if (registerRefusal != null) {
                        return registerRefusal;
                    }
                    if (startInstance) {
                        inside.add(master);
                        when(em.getInstances()).thenReturn(List.of(eim));
                    }
                    return null;
                }

                @Override
                public String join(Character member) {
                    calls.add("join " + member.getId());
                    if (!refuseJoin.contains(member.getId())) {
                        inside.add(member);
                    }
                    return null;
                }
            };
        }
    }

    private static final class FakeSeater implements QueueStaging.Seater {
        final List<String> calls = new ArrayList<>();
        int levelToMake = 20;
        boolean canSeat = true;

        @Override
        public List<Character> seat(MapleMap recruit, int count, int minLevel, int maxLevel, int guildId,
                                    List<Integer> spawned) {
            calls.add("seat " + count + " at " + minLevel + "-" + maxLevel + " in " + guildId);
            if (!canSeat) {
                return List.of();
            }
            List<Character> made = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int id = 9_000 + i;
                spawned.add(id);
                made.add(chr(id, levelToMake, guildId, false));
            }
            return made;
        }

        @Override
        public void cleanup(List<Integer> spawned) {
            calls.add("cleanup " + spawned);
        }
    }

    private QueueStaging.Staged run(Content c, World w, Character master, List<Character> members, FakeSeater seater) {
        return QueueStaging.start(c, mock(MapleMap.class), w.em, master, members, true, 0, said::add, null, w.door(master),
                seater);
    }

    // ---- the script's order ----------------------------------------------------------------------------

    @Test
    void theMasterRegistersTheGuildThenEachMemberJoinsThenTheRunStarts() throws IOException {
        Content c = row(false);
        Character master = chr(100, 55, 7, true);
        World w = new World(0, 100);
        FakeSeater seater = new FakeSeater();

        QueueStaging.Staged staged = run(c, w, master, botsOfLevel(101, 7, 50, 52, 54, 56, 58), seater);

        assertTrue(staged.ok(), staged.error());
        assertEquals(List.of("register 100", "join 101", "join 102", "join 103", "join 104", "join 105"), w.calls,
                "the master first (9040000.js:70-91), then each member into the guild's instance (:54-65)");
        assertEquals(6, staged.active().run().members().size(), "the whole guild is the run's party");
        assertEquals(w.eim, staged.active().instance());
        assertTrue(seater.calls.isEmpty(), "a row with no composition seats nobody");
        assertTrue(said.stream().anyMatch(l -> l.contains("guild 7") && l.contains("through the queue")), said.toString());
    }

    @Test
    void aSeatTheMembersLeaveEmptyIsFilledBeforeAnythingIsRegistered() throws IOException {
        Content c = row(true);
        Character master = chr(100, 55, 7, true);
        World w = new World(0, 100);
        FakeSeater seater = new FakeSeater();

        // the master and four members are a party of five: no junior among them, and one short of the six the script wants
        QueueStaging.Staged staged = run(c, w, master, botsOfLevel(101, 7, 50, 52, 54, 56), seater);

        assertTrue(staged.ok(), staged.error());
        assertEquals(List.of("seat 1 at 10-30 in 7"), seater.calls, "one bot at the seat's own band, in the master's guild");
        assertEquals(List.of("register 100", "join 101", "join 102", "join 103", "join 104", "join 9000"), w.calls,
                "the seated bot joins like any member, after the guild is registered");
        assertEquals(List.of(9000), staged.seated());
    }

    @Test
    void ifNoBotOfThatLevelCanBeSeatedTheGuildIsNeverRegistered() throws IOException {
        Content c = row(true);
        Character master = chr(100, 55, 7, true);
        World w = new World(0, 100);
        FakeSeater seater = new FakeSeater();
        seater.canSeat = false;

        QueueStaging.Staged staged = run(c, w, master, botsOfLevel(101, 7, 50, 52, 54, 56, 58), seater);

        assertFalse(staged.ok());
        assertTrue(staged.error().contains("seat \"junior\"") && staged.error().contains("level 10-30")
                && staged.error().contains("could be seated"), staged.error());
        assertTrue(w.calls.isEmpty(), "nothing was registered, so no instance was started: " + w.calls);
        assertTrue(seater.calls.get(seater.calls.size() - 1).startsWith("cleanup"), seater.calls.toString());
    }

    @Test
    void aSeatedBotThatIsStillTooHighIsNotCountedForTheSeat() throws IOException {
        Content c = row(true);
        FakeSeater seater = new FakeSeater();
        seater.levelToMake = 45; // a spawner that ignored the band: no level is waived

        QueueStaging.Staged staged = run(c, new World(0, 100), chr(100, 55, 7, true),
                botsOfLevel(101, 7, 50, 52, 54, 56, 58), seater);

        assertFalse(staged.ok());
        assertTrue(staged.error().contains("seat \"junior\""), staged.error());
    }

    @Test
    void aMemberWhoAlreadyFitsTheSeatNeedsNoSeating() throws IOException {
        Content c = row(true);
        FakeSeater seater = new FakeSeater();

        QueueStaging.Staged staged = run(c, new World(0, 100), chr(100, 55, 7, true),
                botsOfLevel(101, 7, 50, 52, 54, 56, 25), seater);

        assertTrue(staged.ok(), staged.error());
        assertTrue(seater.calls.isEmpty(), "the level-25 member is the junior: " + seater.calls);
    }

    // ---- the script's checks -------------------------------------------------------------------------------

    @Test
    void onlyAGuildMasterInAGuildMayRegisterAndOnlyHisOwnGuildMayJoin() throws IOException {
        Content c = row(false);
        World w = new World(0, 100);

        assertTrue(run(c, w, chr(100, 55, 0, true), List.of(), new FakeSeater()).error().contains("in no guild"));
        assertTrue(run(c, w, chr(100, 55, 7, false), List.of(), new FakeSeater()).error().contains("not the guild's master"));
        String strangers = run(c, w, chr(100, 55, 7, true), botsOfLevel(101, 8, 50, 52), new FakeSeater()).error();
        assertTrue(strangers.contains("g101") && strangers.contains("g102") && strangers.contains("same guild"), strangers);
        assertTrue(w.calls.isEmpty(), "none of those got as far as the queue");
    }

    @Test
    void aFullQueueAndAQueueWithAGuildAheadAreRefusedBeforeRegistering() throws IOException {
        Content c = row(false);
        Character master = chr(100, 55, 7, true);

        World full = new World(5, 100);
        assertTrue(run(c, full, master, botsOfLevel(101, 7, 50, 52, 54, 56, 58), new FakeSeater()).error().contains("full"));
        World ahead = new World(2, 100);
        assertTrue(run(c, ahead, master, botsOfLevel(101, 7, 50, 52, 54, 56, 58), new FakeSeater()).error()
                .contains("ahead in the queue"));
        assertTrue(full.calls.isEmpty() && ahead.calls.isEmpty(), "nothing registered");
    }

    @Test
    void aPartyOutsideTheRowsSizeIsRefused() throws IOException {
        Content c = row(false);

        QueueStaging.Staged small = run(c, new World(0, 100), chr(100, 55, 7, true), botsOfLevel(101, 7, 50, 52),
                new FakeSeater());

        assertTrue(small.error().contains("6-30") && small.error().contains("not 3"), small.error());
    }

    @Test
    void theSwitchOffRefusesWithTheSwitchesNamed() throws IOException {
        Content c = row(false);
        System.clearProperty("slums.mechanics.guildstage");

        QueueStaging.Staged off = run(c, new World(0, 100), chr(100, 55, 7, true), botsOfLevel(101, 7, 50, 52, 54, 56, 58),
                new FakeSeater());

        assertTrue(off.error().contains("slums.mechanics.guildstage"), off.error());
    }

    // ---- when the world does not play along -----------------------------------------------------------------

    @Test
    void aRefusalFromTheDoorIsPassedOnAndTheSeatedBotsGoHome() throws IOException {
        Content c = row(true);
        World w = new World(0, 100);
        w.registerRefusal = "the door NPC said: Your guild is already queued on a channel.";
        FakeSeater seater = new FakeSeater();

        QueueStaging.Staged staged = run(c, w, chr(100, 55, 7, true), botsOfLevel(101, 7, 50, 52, 54, 56), seater);

        assertTrue(staged.error().contains("already queued"), staged.error());
        assertEquals("cleanup [9000]", seater.calls.get(seater.calls.size() - 1));
    }

    @Test
    void aGuildTheWorldStartedNoInstanceForIsAnErrorNotARun() throws IOException {
        Content c = row(false);
        World w = new World(0, 100);
        w.startInstance = false;

        QueueStaging.Staged staged = run(c, w, chr(100, 55, 7, true), botsOfLevel(101, 7, 50, 52, 54, 56, 58),
                new FakeSeater());

        assertTrue(staged.error().contains("started no instance"), staged.error());
        assertNotNull(w.calls);
        assertEquals(List.of("register 100"), w.calls, "no member was sent to a lobby that does not exist");
    }

    @Test
    void aMemberTheInstanceDoesNotTakeEndsTheInstanceAndIsNamed() throws IOException {
        Content c = row(false);
        World w = new World(0, 100);
        w.refuseJoin = List.of(103);

        QueueStaging.Staged staged = run(c, w, chr(100, 55, 7, true), botsOfLevel(101, 7, 50, 52, 54, 56, 58),
                new FakeSeater());

        assertFalse(staged.ok());
        assertTrue(staged.error().contains("g103") && staged.error().contains("did not take"), staged.error());
        assertTrue(w.calls.contains("join 105"), "the others were still tried: " + w.calls);
    }

    // ---- which door ----------------------------------------------------------------------------------------

    @Test
    void aBotMasterGoesThroughTheNpcAndAGmThroughTheScriptsOwnCalls() throws IOException {
        Content c = row(false);
        World w = new World(0, 100);
        Character bot = chr(100, 55, 7, true);
        Character gm = chr(101, 55, 7, true);
        when(gm.isBot()).thenReturn(false);

        assertTrue(QueueStaging.doorFor(bot, w.em, c) instanceof QueueStaging.NpcDoor, "a bot enters the way a player does");
        assertTrue(QueueStaging.doorFor(gm, w.em, c) instanceof QueueStaging.DirectDoor, "a GM is let through the same calls");
    }

    @Test
    void theDirectDoorReadsTheWorldsAnswersAsTheScriptDoes() throws IOException {
        Content c = row(false);
        World w = new World(0, 100);
        Character gm = chr(100, 55, 7, true);
        QueueStaging.DirectDoor door = new QueueStaging.DirectDoor(w.em);

        when(w.em.addGuildToQueue(7, 100)).thenReturn((byte) 2);
        assertEquals(null, door.register(gm), "2: started at once");
        when(w.em.addGuildToQueue(7, 100)).thenReturn((byte) 1);
        assertTrue(door.register(gm).contains("behind another"));
        when(w.em.addGuildToQueue(7, 100)).thenReturn((byte) 0);
        assertTrue(door.register(gm).contains("full"));
        when(w.em.addGuildToQueue(7, 100)).thenReturn((byte) -1);
        assertTrue(door.register(gm).contains("already queued"));
    }
}
