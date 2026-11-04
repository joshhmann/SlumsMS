package soloMapling.mechanics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntSupplier;

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
import soloMapling.mechanics.RealRunPool.Admission;
import soloMapling.mechanics.RealRunPool.Origin;

/**
 * The four places a bot-only run is made, and what the real-run pool does at each ({@code -Dslums.mechanics.realruns}).
 * Driven through the entry points that exist (the lobby ticks, the staging), so the assertions are on what is made:
 * <ul>
 *   <li>unset: every site reaches its staging exactly as before and <b>no lease exists</b>;</li>
 *   <li>a refused run is not made at all - no party, no Lakelis, no spawn - and says so in one line, once;</li>
 *   <li>a run that is admitted but never becomes a run gives its place back;</li>
 *   <li>a GM's or QA's staged run is never refused.</li>
 * </ul>
 */
class RealRunAdmissionSitesTest {

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("pool-capture", null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
        }

        @Override
        public void append(org.apache.logging.log4j.core.LogEvent event) {
            lines.add(event.getMessage().getFormattedMessage());
        }
    }

    private final Capture capture = new Capture();
    private final List<Integer> registered = new ArrayList<>();
    private Logger botLog;
    private Level level;

    @BeforeEach
    void setUp() {
        System.setProperty(Mechanics.FLAG, "true");
        System.setProperty(KpqLobby.FLAG, "true");
        botLog = (Logger) LogManager.getLogger("soloMapling.BotLog");
        level = botLog.getLevel();
        Configurator.setLevel(botLog.getName(), Level.INFO);
        capture.start();
        botLog.addAppender(capture);
        RealRunPool.get().clearForTest();
    }

    @AfterEach
    void tearDown() {
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
        RealRunPool.get().clearForTest();
        KpqLobby.clearForTest();
        ContentLobby.clearForTest();
    }

    private static Content kpq() {
        return KpqStageDataTest.kpq();
    }

    private static void quota(int n) {
        System.setProperty(Mechanics.REAL_RUNS_FLAG, String.valueOf(n));
    }

    /** The place another run holds; the test releases it when it wants the pool to have room again. */
    private static Admission someoneElsesPlace() {
        Admission a = RealRunPool.get().admit(Origin.CONTENT_LOBBY, kpq(), 0);
        assertTrue(a.granted() && a.leased(), "the test's own place");
        return a;
    }

    // ---- Kerning's bot lobby ------------------------------------------------------------------------------

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
        CharacterStorage.getAllBots().put(id, sm);
        registered.add(id);
        return c;
    }

    /** One channel whose Kerning recruit map holds three free bots and whose Kerning PQ is free. */
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
    void kerningsLobbyReachesItsPartyAsBeforeAndMakesNoLeaseWhileTheSwitchIsUnset() {
        Lobby lobby = kerningLobby(9_100);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(false);

            KpqLobby.tick();

            parties.verify(() -> Party.createParty(lobby.bots.get(0), true)); // phase 1: the lobby made its party
            assertEquals(0, RealRunPool.get().held(), "and nothing was leased for it");
            assertTrue(capture.lines.stream().noneMatch(l -> l.contains("real-run pool")), capture.lines.toString());
        }
    }

    @Test
    void aFullPoolMakesNoPartyAndSaysSoOncePerTenMinutes() {
        quota(0);
        Lobby lobby = kerningLobby(9_200);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);

            KpqLobby.tick();
            KpqLobby.tick(); // the next minute

            parties.verifyNoInteractions(); // no party, no word with Lakelis: the run was never made
            List<String> said = capture.lines.stream().filter(l -> l.contains("KPQ lobby: no run on channel 1")).toList();
            assertEquals(1, said.size(), "the lobby's own one-line shape, said once: " + capture.lines);
            assertTrue(said.get(0).startsWith("[Mechanics] KPQ lobby: no run on channel 1: the real-run pool admits none"),
                    said.get(0));
            assertEquals(0, KpqLobby.running());
        }
    }

    @Test
    void aPlaceTakenByAnotherRunRefusesTheLobbyAndFreeingItLetsTheNextTickThrough() {
        quota(1);
        Admission other = someoneElsesPlace();
        Lobby lobby = kerningLobby(9_300);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(false);

            KpqLobby.tick();
            parties.verifyNoInteractions();
            assertTrue(capture.lines.stream().anyMatch(l -> l.contains("1 of 1 held: kpq (CONTENT_LOBBY)")),
                    "the refusal names who holds the place: " + capture.lines);

            other.release();
            KpqLobby.tick();
            parties.verify(() -> Party.createParty(lobby.bots.get(0), true));
        }
    }

    @Test
    void anAdmittedLobbyRunThatNeverBecomesARunGivesItsPlaceBack() {
        quota(1);
        Lobby lobby = kerningLobby(9_400);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(false); // no party

            KpqLobby.tick();

            parties.verify(() -> Party.createParty(lobby.bots.get(0), true)); // admitted, and it tried
            assertEquals(0, RealRunPool.get().held(), "a party that could not be made holds no place");
        }
    }

    @Test
    void aThrowWhileTheLobbyPartyIsMadeGivesTheLobbyRunsPlaceBack() {
        quota(1);
        Lobby lobby = kerningLobby(9_500);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenThrow(new IllegalStateException("b156"));

            KpqLobby.tick();

            assertEquals(0, RealRunPool.get().held(), "the throw is the lobby's to survive and the lease's to give back");
        }
    }

    @Test
    void aQaStagedLobbyRunIsNeverRefused() {
        quota(0);
        Lobby lobby = kerningLobby(9_600);
        try (MockedStatic<Server> servers = mockStatic(Server.class); MockedStatic<Party> parties = mockStatic(Party.class)) {
            servers.when(Server::getInstance).thenReturn(lobby.server);
            parties.when(() -> Party.createParty(any(Character.class), anyBoolean())).thenReturn(false);

            assertNull(KpqLobby.startForQa(kpq(), lobby.bots), "the party could not be made in this mock");

            parties.verify(() -> Party.createParty(lobby.bots.get(0), true)); // but QA was not refused: it tried
            assertEquals(0, RealRunPool.get().held());
        }
    }

    // ---- the content lobbies -------------------------------------------------------------------------------

    private static final class Tower {
        final MapManager maps = mock(MapManager.class);
        final Server server = mock(Server.class);
        final Content content = MechanicsRuns.contentFor("OrbisPQ");

        Tower() {
            when(maps.isMapLoaded(content.recruitMap())).thenReturn(true);
            // getMap answers null: KpqStaging stops at "map ... did not load", which is as far as a test needs it
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
    void theContentLobbyReachesItsStagingAsBeforeAndMakesNoLeaseWhileTheSwitchIsUnset() {
        Tower tower = new Tower();
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L), "the staging found no map in this mock");

            verify(tower.maps).getMap(tower.content.recruitMap()); // it got as far as the staging
            assertEquals(0, RealRunPool.get().held(), "and nothing was leased");
            assertFalse(ContentLobby.mayTry(ContentLobby.key(tower.content, channelOne()), 1_000_001L),
                    "a staging that failed backs the channel off, as it always did");
        }
    }

    private static Channel channelOne() {
        Channel c = mock(Channel.class);
        when(c.getId()).thenReturn(1);
        return c;
    }

    @Test
    void aFullPoolSpawnsNoBotForAContentLobbyAndDoesNotBackTheChannelOff() {
        quota(0);
        Tower tower = new Tower();
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L));
            assertFalse(ContentLobby.tickRow(tower.content, 1_060_000L));

            verify(tower.maps, never()).getMap(any(Integer.class)); // KpqStaging was never entered: nothing spawned
            assertTrue(ContentLobby.mayTry(ContentLobby.key(tower.content, channelOne()), 1_060_001L),
                    "a refusal by the pool is not the channel's fault: no ten-minute back-off");
            List<String> said = capture.lines.stream().filter(l -> l.contains("lobby: no run on channel 1")).toList();
            assertEquals(1, said.size(), capture.lines.toString());
            assertTrue(said.get(0).startsWith("[Mechanics] " + tower.content.name()
                    + " lobby: no run on channel 1: the real-run pool admits none"), said.get(0));
        }
    }

    @Test
    void anAdmittedContentLobbyRunThatStagingCannotMakeGivesItsPlaceBack() {
        quota(1);
        Tower tower = new Tower();
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L));

            verify(tower.maps).getMap(tower.content.recruitMap()); // admitted: staging was entered
            assertEquals(0, RealRunPool.get().held(), "the run that was never made holds no place");
        }
    }

    @Test
    void aPlaceTakenElsewhereRefusesTheContentLobbyUntilItIsFreed() {
        quota(1);
        Admission other = someoneElsesPlace();
        Tower tower = new Tower();
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(tower.server);

            assertFalse(ContentLobby.tickRow(tower.content, 1_000_000L));
            verify(tower.maps, never()).getMap(any(Integer.class));

            other.release();
            assertFalse(ContentLobby.tickRow(tower.content, 1_060_000L));
            verify(tower.maps).getMap(tower.content.recruitMap()); // now it is admitted and staged
        }
    }

    // ---- the staging ---------------------------------------------------------------------------------------

    @Test
    void aGmsOrQasStagingIsNeverRefusedWhateverThePoolSays() {
        quota(0);
        Tower tower = new Tower();
        Channel ch = tower.server.getWorld(1).getChannels().get(0);

        KpqStaging.Staged s = KpqStaging.start(tower.content, ch, null, false, 5, true, 0, 1, 0, line -> { }, null);

        assertNull(s.active());
        assertTrue(s.error().contains("did not load"), "it ran, and failed on its own terms, not the pool's: " + s.error());
        verify(tower.maps).getMap(tower.content.recruitMap());
        assertEquals(0, RealRunPool.get().held());
    }

    @Test
    void aLeasedStagingGivesItsPlaceBackWhenItIsRefusedOrThrows() {
        RealRunPool mine = new RealRunPool(() -> 2, System::currentTimeMillis);
        Tower tower = new Tower();
        Channel ch = tower.server.getWorld(1).getChannels().get(0);

        // refused by the staging itself (mechanics off)
        System.clearProperty(Mechanics.FLAG);
        Admission off = mine.admit(Origin.CONTENT_LOBBY, tower.content, 0);
        assertEquals(1, mine.held());
        KpqStaging.Staged s = KpqStaging.start(tower.content, ch, null, false, 5, true, 0, 1, 0, line -> { }, null, off);
        assertNull(s.active());
        assertEquals(0, mine.held(), "mechanics off: the staging refused, the place is back");

        // a throw out of the staging
        System.setProperty(Mechanics.FLAG, "true");
        when(ch.getMapFactory()).thenThrow(new IllegalStateException("channel is going away"));
        Admission thrown = mine.admit(Origin.CONTENT_LOBBY, tower.content, 0);
        assertEquals(1, mine.held());
        assertThrows(IllegalStateException.class,
                () -> KpqStaging.start(tower.content, ch, null, false, 5, true, 0, 1, 0, line -> { }, null, thrown));
        assertEquals(0, mine.held(), "a throw: the place is back");
    }
}
