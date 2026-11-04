package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import net.server.Server;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.World;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import scripting.event.EventManager;
import scripting.event.EventScriptManager;
import server.maps.MapManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.RealRunPool.Admission;
import soloMapling.mechanics.RealRunPool.Origin;
import soloMapling.mechanics.RunOutcome.Mode;
import soloMapling.mechanics.RunOutcome.Participant;
import soloMapling.mechanics.RunOutcome.Result;

/**
 * What the two lobbies do when the real-run pool refuses them: a refused run is simulated (no party, no Lakelis, no
 * spawned bot, no instance, no tick) when its content has real runs to be sampled from, and waits when it has not. With
 * the switch unset nothing is ever refused, so nothing is ever simulated - pinned here with warm samples in hand, which
 * is the case where a bug would show.
 */
class SimulatedRunSitesTest {

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("sim-capture", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private final Capture capture = new Capture();
    private final List<Integer> registered = new ArrayList<>();
    private final java.util.Map<Integer, soloMapling.ArtificialPlayer.BotRunOutcome.Slot> slots = new java.util.HashMap<>();
    private final List<Runnable> scheduled = new ArrayList<>();
    private final List<RunOutcome> heard = new CopyOnWriteArrayList<>();
    private final Consumer<RunOutcome> ear = heard::add;
    private Logger botLog;
    private Level level;
    private java.util.function.Supplier<ScheduledExecutorService> wasScheduler;

    @BeforeEach
    void setUp() {
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(KpqLobby.FLAG, "true");
        botLog = (Logger) LogManager.getLogger("soloMapling.BotLog");
        level = botLog.getLevel();
        Configurator.setLevel(botLog.getName(), Level.INFO);
        capture.start();
        botLog.addAppender(capture);
        reset();
        RunOutcomes.listen(ear);
        wasScheduler = MechanicsRuns.scheduler;
        ScheduledExecutorService mine = mock(ScheduledExecutorService.class);
        when(mine.schedule(any(Runnable.class), anyLong(), any(TimeUnit.class))).thenAnswer(inv -> {
            scheduled.add(inv.getArgument(0));
            return mock(ScheduledFuture.class);
        });
        MechanicsRuns.scheduler = () -> mine;
    }

    @AfterEach
    void tearDown() {
        RunOutcomes.unlisten(ear);
        MechanicsRuns.scheduler = wasScheduler;
        botLog.removeAppender(capture);
        capture.stop();
        Configurator.setLevel(botLog.getName(), level);
        System.clearProperty(Mechanics.FLAG);
        System.clearProperty(KpqLobby.FLAG);
        System.clearProperty(Mechanics.REAL_RUNS_FLAG);
        for (int id : registered) {
            CharacterStorage.getAllBots().remove(id);
        }
        registered.clear();
        reset();
    }

    private static void reset() {
        RealRunPool.get().clearForTest();
        RunSamples.get().clearForTest();
        SimulatedRuns.get().clearForTest();
        KpqLobby.clearForTest();
        ContentLobby.clearForTest();
    }

    private static void quota(int n) {
        System.setProperty(Mechanics.REAL_RUNS_FLAG, String.valueOf(n));
    }

    private static void warm(String contentId, int wins, int losses) {
        for (int i = 0; i < wins; i++) {
            RunSamples.get().onOutcome(RunSamplesTest.real(contentId, Tier.NORMAL, Result.WON, 600_000 + i, Origin.CONTENT_LOBBY,
                    Mode.REAL, false));
        }
        for (int i = 0; i < losses; i++) {
            RunSamples.get().onOutcome(RunSamplesTest.real(contentId, Tier.NORMAL, Result.LOST, 1_800_000 + i,
                    Origin.CONTENT_LOBBY, Mode.REAL, false));
        }
    }

    private Character bot(int id, int level) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn("m" + id);
        when(c.isBot()).thenReturn(true);
        when(c.getLevel()).thenReturn(level);
        when(c.getHp()).thenReturn(500);
        Inventory etc = mock(Inventory.class);
        when(etc.getNumFreeSlot()).thenReturn((short) 5);
        when(c.getInventory(InventoryType.ETC)).thenReturn(etc);
        BotSM sm = mock(BotSM.class);
        when(sm.getRunning()).thenReturn(true);
        when(sm.getChr()).thenReturn(c);
        soloMapling.ArtificialPlayer.BotRunOutcome.Slot slot = new soloMapling.ArtificialPlayer.BotRunOutcome.Slot();
        when(sm.runOutcome()).thenReturn(slot);
        slots.put(id, slot);
        CharacterStorage.getAllBots().put(id, sm);
        registered.add(id);
        return c;
    }

    // ---- Kerning's lobby ----------------------------------------------------------------------------------------

    private static final class Lobby {
        final List<Character> bots;
        final Server server = mock(Server.class);

        Lobby(List<Character> bots) {
            this.bots = bots;
            MapManager maps = mock(MapManager.class);
            MapleMap recruit = mock(MapleMap.class);
            when(maps.isMapLoaded(KpqStaging.RECRUIT_MAP)).thenReturn(true);
            when(maps.getMap(KpqStaging.RECRUIT_MAP)).thenReturn(recruit);
            when(recruit.getAllPlayers()).thenReturn(new ArrayList<>(bots));
            EventManager kpq = mock(EventManager.class);
            when(kpq.getInstances()).thenReturn(List.of());
            EventScriptManager scripts = mock(EventScriptManager.class);
            when(scripts.getEventManager(KpqStaging.EVENT)).thenReturn(kpq);
            Channel channel = mock(Channel.class);
            when(channel.getId()).thenReturn(1);
            when(channel.getWorld()).thenReturn(1);
            when(channel.getMapFactory()).thenReturn(maps);
            when(channel.getEventSM()).thenReturn(scripts);
            World world = mock(World.class);
            when(world.getChannels()).thenReturn(List.of(channel));
            when(server.getWorld(anyInt())).thenReturn(world);
        }
    }

    private Lobby kerningLobby(int from) {
        return new Lobby(List.of(bot(from, 30), bot(from + 1, 25), bot(from + 2, 22)));
    }

    @Test
    void withTheSwitchUnsetNothingIsRefusedSoNothingIsEverSimulatedEvenWithWarmSamples() {
        warm("kpq", 10, 10);
        Lobby lobby = kerningLobby(3_100);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(false);

            KpqLobby.tick();

            parties.verify(() -> Party.createParty(lobby.bots.get(0), true)); // phase 1: the lobby made its real party
        }
        assertEquals(0, SimulatedRuns.get().inFlight());
        assertTrue(scheduled.isEmpty(), "no simulated completion was scheduled");
        assertTrue(heard.isEmpty());
        assertTrue(capture.lines.stream().noneMatch(l -> l.contains("simulated")), capture.lines.toString());
        assertTrue(MechanicsRuns.get().status().stream().noneMatch(l -> l.startsWith("simulated")));
    }

    @Test
    void aRefusedKerningRunIsSimulatedWithNoPartyNoLakelisAndNoHeldBot() {
        quota(0);
        warm("kpq", 5, 0);
        Lobby lobby = kerningLobby(3_200);
        int held = MechanicsHold.count();
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);

            KpqLobby.tick();

            parties.verifyNoInteractions(); // no party: the run was never made
        }
        assertEquals(1, SimulatedRuns.get().inFlight());
        assertEquals(1, scheduled.size());
        assertEquals(held, MechanicsHold.count(), "no bot is held");
        assertEquals(0, KpqLobby.running(), "and no lobby run exists");
        assertEquals(0, RealRunPool.get().held());
        assertTrue(capture.lines.stream().anyMatch(l -> l.startsWith("[Mechanics] simulated run ")
                && l.contains("of kpq (KPQ_LOBBY): 3 bot(s) will report WON") && l.contains("the real-run pool refused: "
                + "the real-run pool admits none")), capture.lines.toString());
        assertTrue(MechanicsRuns.get().status().stream().anyMatch(l -> l.startsWith("simulated kpq (KPQ_LOBBY): 3 bot(s)")),
                MechanicsRuns.get().status().toString());

        scheduled.get(0).run();

        assertEquals(1, heard.size());
        RunOutcome o = heard.get(0);
        assertEquals(Mode.SIMULATED, o.mode());
        assertEquals(Result.WON, o.result());
        assertEquals(List.of(3_200, 3_201, 3_202), o.participants().stream().map(Participant::characterId).toList(),
                "the three bots the lobby picked, the highest level first");
        assertEquals(3_200, o.leaderId());
        assertTrue(slots.get(3_200).get() != null, "and the leader has its win line to say, from the record alone");
    }

    @Test
    void aColdKerningWaitsAndSaysSoOnceWithoutMakingAnything() {
        quota(0);
        warm("kpq", 3, 1);
        Lobby lobby = kerningLobby(3_300);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);

            KpqLobby.tick();
            KpqLobby.tick();

            parties.verifyNoInteractions();
        }
        assertEquals(0, SimulatedRuns.get().inFlight());
        assertTrue(scheduled.isEmpty());
        List<String> said = capture.lines.stream().filter(l -> l.contains("KPQ lobby: no run on channel 1")).toList();
        assertEquals(1, said.size(), capture.lines.toString());
        assertTrue(said.get(0).contains("; not simulated: kpq has 4 of 5 real runs to be sampled from"), said.get(0));
    }

    @Test
    void theSimulatedRunKeepsItsBotsOutOfAnotherRunAndIsNeverPromotedWhenAPlaceFrees() {
        quota(1);
        warm("kpq", 5, 0);
        Admission other = RealRunPool.get().admit(Origin.CONTENT_LOBBY, MechanicsRuns.contentFor("OrbisPQ"), 0);
        assertTrue(other.leased());
        Lobby lobby = kerningLobby(3_400);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(false);

            KpqLobby.tick();
            assertEquals(1, SimulatedRuns.get().inFlight());
            other.release(); // a place frees while the simulated run is still going

            KpqLobby.tick();

            parties.verifyNoInteractions(); // its bots are named by the simulated run: no second run, real or not
        }
        assertEquals(0, RealRunPool.get().held(), "it never took the place");
        assertTrue(KpqLobby.free(lobby.bots.get(0)) == false, "a bot of a simulated run in flight is not free");

        scheduled.get(0).run();

        assertEquals(Mode.SIMULATED, heard.get(0).mode(), "simulated from its start to its end");
        assertTrue(KpqLobby.free(lobby.bots.get(0)), "and free again once it ended");
    }

    @Test
    void aFreePlaceKeptForAStalerContentMakesTheKerningRunSimulatedNotReal() {
        quota(1);
        warm("kpq", 5, 0);
        Content opq = MechanicsRuns.contentFor("OrbisPQ");
        Admission holder = RealRunPool.get().admit(Origin.KPQ_LOBBY, KpqStageDataTest.kpq(), 0); // kpq is real, then ends
        assertTrue(holder.leased());
        assertFalse(RealRunPool.get().admit(Origin.CONTENT_LOBBY, opq, 0).granted(), "opq asks and is refused: full");
        holder.release();
        Lobby lobby = kerningLobby(3_500);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);

            KpqLobby.tick();

            parties.verifyNoInteractions();
        }
        assertEquals(1, SimulatedRuns.get().inFlight(), "kpq was real a moment ago: opq is owed the place, kpq simulates");
        assertTrue(capture.lines.stream().anyMatch(l -> l.contains("opq has waited longer for a real run")),
                capture.lines.toString());
        Admission opqTurn = RealRunPool.get().admit(Origin.CONTENT_LOBBY, opq, 0);
        assertTrue(opqTurn.granted() && opqTurn.leased(), "and opq gets it when it asks");
    }

    // ---- the content lobbies ------------------------------------------------------------------------------------

    private static final class Tower {
        final MapManager maps = mock(MapManager.class);
        final Server server = mock(Server.class);
        final Content content = MechanicsRuns.contentFor("OrbisPQ");

        Tower() {
            when(maps.isMapLoaded(content.recruitMap())).thenReturn(true);
            EventManager em = mock(EventManager.class);
            when(em.getInstances()).thenReturn(List.of());
            EventScriptManager scripts = mock(EventScriptManager.class);
            when(scripts.getEventManager(content.eventScript())).thenReturn(em);
            Channel channel = mock(Channel.class);
            when(channel.getId()).thenReturn(1);
            when(channel.getWorld()).thenReturn(1);
            when(channel.getMapFactory()).thenReturn(maps);
            when(channel.getEventSM()).thenReturn(scripts);
            World world = mock(World.class);
            when(world.getChannels()).thenReturn(List.of(channel));
            when(server.getWorld(anyInt())).thenReturn(world);
        }
    }

    @Test
    void aRefusedTowerRunBorrowsIdlePopulationBotsOfItsBandAndSpawnsNobody() {
        quota(0);
        Tower tower = new Tower();
        warm("opq", 5, 0);
        int inBand = tower.content.minLevel() + 1;
        for (int i = 0; i < 7; i++) {
            bot(3_600 + i, inBand);
        }
        bot(3_650, tower.content.minLevel() - 1); // too low for the tower
        Character busy = bot(3_651, inBand);
        when(busy.getParty()).thenReturn(mock(Party.class)); // in a party already
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertTrue(ContentLobby.tickRow(tower.content, 1_000_000L), "a simulated start is the tick's one start");

            verify(tower.maps, never()).getMap(anyInt()); // KpqStaging was never entered: nothing spawned
        }
        assertEquals(1, SimulatedRuns.get().inFlight());
        scheduled.get(0).run();
        RunOutcome o = heard.get(0);
        assertEquals(Mode.SIMULATED, o.mode());
        assertEquals("opq", o.contentId());
        assertEquals(tower.content.minPlayers(), o.participants().size());
        assertTrue(o.participants().stream().noneMatch(p -> p.characterId() == 3_650 || p.characterId() == 3_651),
                "never a bot out of the band, nor one in a party: " + o.participants());
        assertTrue(ContentLobby.mayTry(ContentLobby.key(tower.content, channelOne()), 1_000_001L),
                "a pool refusal is not the channel's fault: no back-off");
    }

    @Test
    void aTowerWithNoIdleBotToReportDoesNotSimulateAndSaysWhy() {
        quota(0);
        Tower tower = new Tower();
        warm("opq", 5, 0);
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L));
        }
        assertEquals(0, SimulatedRuns.get().inFlight());
        assertTrue(capture.lines.stream().anyMatch(l -> l.contains("; not simulated: no idle bot to report opq")),
                capture.lines.toString());
    }

    @Test
    void aColdTowerWaitsAndSpawnsNobody() {
        quota(0);
        Tower tower = new Tower();
        warm("opq", 4, 0);
        for (int i = 0; i < 6; i++) {
            bot(3_700 + i, tower.content.minLevel() + 1);
        }
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L));
            verify(tower.maps, never()).getMap(anyInt());
        }
        assertEquals(0, SimulatedRuns.get().inFlight());
        assertTrue(capture.lines.stream().anyMatch(l -> l.contains("not simulated: opq has 4 of 5 real runs")),
                capture.lines.toString());
    }

    @Test
    void withTheSwitchUnsetATowerRunStagesAsBeforeAndNeverSimulates() {
        Tower tower = new Tower();
        warm("opq", 10, 0);
        for (int i = 0; i < 6; i++) {
            bot(3_800 + i, tower.content.minLevel() + 1);
        }
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L), "the staging found no map in this mock");

            verify(tower.maps).getMap(tower.content.recruitMap()); // phase 1: it went to the staging
        }
        assertEquals(0, SimulatedRuns.get().inFlight());
        assertTrue(scheduled.isEmpty());
    }

    private static Channel channelOne() {
        Channel c = mock(Channel.class);
        when(c.getId()).thenReturn(1);
        return c;
    }
}
