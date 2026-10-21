package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Client;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.coordinator.matchchecker.MatchCheckerCoordinator;
import net.server.coordinator.matchchecker.MatchCheckerListenerFactory.MatchCheckerType;
import net.server.guild.Guild;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.maps.MapleMap;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Ticket 50A: bots are never guild cofounders, and a creation nobody finishes answering times out cleanly. */
class GuildCreationTest {
    private static final int WORLD = 7;

    private final MatchCheckerCoordinator coordinator = new MatchCheckerCoordinator();
    private final PlayerStorage storage = new PlayerStorage();
    private final World world = mock(World.class);
    private final MapleMap map = mock(MapleMap.class);
    private MockedStatic<Server> serverStatic;

    @BeforeEach
    void setUp() {
        when(world.getMatchCheckerCoordinator()).thenReturn(coordinator);
        when(world.getPlayerStorage()).thenReturn(storage);
        Server server = mock(Server.class);
        when(server.getWorld(WORLD)).thenReturn(world);
        serverStatic = mockStatic(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);
    }

    @AfterEach
    void tearDown() {
        serverStatic.close();
    }

    private Character character(int id, String name, Client client) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        when(chr.getWorldServer()).thenReturn(world);
        when(chr.getMap()).thenReturn(map);
        when(client.getPlayer()).thenReturn(chr);
        storage.addPlayer(chr);
        return chr;
    }

    @Test
    void botsOnTheGuildHqMapAreNotEligibleCofounders() {
        Character leader = character(1, "Founder", mock(Client.class));
        Character friend = character(2, "Friend", mock(Client.class));
        Character bot = character(1_000_000_005, "Botty", mock(BotClient.class));
        when(map.getAllPlayers()).thenReturn(List.of(leader, friend, bot));

        Set<Integer> ids = Guild.getEligiblePlayersForGuild(leader).stream().map(Character::getId).collect(Collectors.toSet());

        assertEquals(Set.of(1, 2), ids);
    }

    @Test
    void anUnansweredCreationExpiresAndFreesEveryone() {
        Character leader = character(1, "Founder", mock(Client.class));
        Character idle = character(2, "Idle", mock(Client.class));
        when(leader.isLoggedinWorld()).thenReturn(true);

        assertTrue(coordinator.createMatchConfirmation(MatchCheckerType.GUILD_CREATION, WORLD, 1, Set.of(1, 2), "Slums"));
        long matchId = coordinator.getMatchConfirmationId(1);
        assertNotEquals(-1, matchId);
        assertEquals(1, coordinator.getMatchConfirmationLeaderid(2));   // the idle player is held by the match

        assertTrue(GuildOperationHandler.expireGuildCreation(coordinator, leader, matchId));

        assertEquals(-1, coordinator.getMatchConfirmationLeaderid(1));
        assertEquals(-1, coordinator.getMatchConfirmationLeaderid(2));
        verify(leader).dropMessage(eq(1), contains("cancelled"));
        assertFalse(GuildOperationHandler.expireGuildCreation(coordinator, leader, matchId));   // only once
    }

    @Test
    void aStaleTimeoutLeavesALaterCreationAlone() {
        Character leader = character(1, "Founder", mock(Client.class));
        character(2, "Idle", mock(Client.class));

        coordinator.createMatchConfirmation(MatchCheckerType.GUILD_CREATION, WORLD, 1, Set.of(1, 2), "First");
        long first = coordinator.getMatchConfirmationId(1);
        coordinator.answerMatchConfirmation(2, false);   // declined: the first match is gone
        coordinator.createMatchConfirmation(MatchCheckerType.GUILD_CREATION, WORLD, 1, Set.of(1, 2), "Second");

        assertFalse(GuildOperationHandler.expireGuildCreation(coordinator, leader, first));
        assertEquals(1, coordinator.getMatchConfirmationLeaderid(2));
        verify(leader, never()).dropMessage(anyInt(), anyString());
    }

    @Test
    void aLeaderOnlyMatchIsAcceptedRightAway() {
        Character leader = character(1, "Solo", mock(Client.class));
        // the listener bails out early (no party), but it must be reached: stock never dispatched the result
        when(leader.isLoggedinWorld()).thenReturn(true);
        when(leader.getPartyId()).thenReturn(-1);

        assertTrue(coordinator.createMatchConfirmation(MatchCheckerType.GUILD_CREATION, WORLD, 1, Set.of(1), "Solo"));

        verify(leader).dropMessage(eq(1), contains("without leading a party"));
        assertEquals(-1, coordinator.getMatchConfirmationId(1));
    }
}
