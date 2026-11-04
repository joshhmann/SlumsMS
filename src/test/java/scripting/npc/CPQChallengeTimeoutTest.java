package scripting.npc;

import client.Character;
import client.Client;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.coordinator.matchchecker.MatchCheckerCoordinator;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Ticket 164: a CPQ challenge nobody answers is cancelled instead of keeping both party leaders out of every later
 * match, and a stale timeout leaves a later challenge alone.
 */
class CPQChallengeTimeoutTest {
    private static final int WORLD = 7;
    private static final int LOBBY_NPC = 2042000;   // Spiegelmann, the CPQ lobby NPC
    private static final int CHALLENGER = 1;
    private static final int OPPONENT = 2;
    private static final int ENGLISH = 2;

    private final MatchCheckerCoordinator coordinator = new MatchCheckerCoordinator();
    private final PlayerStorage storage = new PlayerStorage();
    private final World world = mock(World.class);

    private MockedStatic<Server> serverStatic;
    private MockedStatic<NPCScriptManager> scriptStatic;
    private MockedStatic<TimerManager> timerStatic;
    private TimerManager timer;
    private NPCConversationManager cm;

    @BeforeEach
    void setUp() {
        when(world.getMatchCheckerCoordinator()).thenReturn(coordinator);
        when(world.getPlayerStorage()).thenReturn(storage);
        Server server = mock(Server.class);
        when(server.getWorld(WORLD)).thenReturn(world);
        serverStatic = mockStatic(Server.class);
        serverStatic.when(Server::getInstance).thenReturn(server);

        scriptStatic = mockStatic(NPCScriptManager.class);
        scriptStatic.when(NPCScriptManager::getInstance).thenReturn(mock(NPCScriptManager.class));

        timer = mock(TimerManager.class);
        timerStatic = mockStatic(TimerManager.class);
        timerStatic.when(TimerManager::getInstance).thenReturn(timer);

        Character challenger = character(CHALLENGER, "Challenger");
        character(OPPONENT, "Opponent");
        cm = new NPCConversationManager(challenger.getClient(), LOBBY_NPC, "cpqchallenge");
        when(challenger.getClient().getCM()).thenReturn(cm);
    }

    @AfterEach
    void tearDown() {
        timerStatic.close();
        scriptStatic.close();
        serverStatic.close();
    }

    private Character character(int id, String name) {
        Character chr = mock(Character.class);
        Client client = mock(Client.class);
        PartyCharacter member = mock(PartyCharacter.class);
        Party party = mock(Party.class);
        when(member.isOnline()).thenReturn(true);
        when(party.getMembers()).thenReturn(List.of(member));
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        when(chr.getWorldServer()).thenReturn(world);
        when(chr.getLanguage()).thenReturn(ENGLISH);
        when(chr.getParty()).thenReturn(party);
        when(client.getPlayer()).thenReturn(chr);
        when(client.getWorldServer()).thenReturn(world);
        when(client.getWorld()).thenReturn(WORLD);
        storage.addPlayer(chr);
        return chr;
    }

    private Character challenger() {
        Character chr = storage.getCharacterById(CHALLENGER);
        when(chr.isLoggedinWorld()).thenReturn(true);
        return chr;
    }

    @Test
    void anUnansweredChallengeExpiresAndFreesBothLeaders() {
        Character challenger = challenger();

        assertTrue(cm.sendCPQChallenge("cpq1", OPPONENT));
        long matchId = coordinator.getMatchConfirmationId(CHALLENGER);
        assertNotEquals(-1, matchId);
        assertEquals(CHALLENGER, coordinator.getMatchConfirmationLeaderid(OPPONENT));   // the other leader is held by the challenge

        assertTrue(NPCConversationManager.expireCPQChallenge(coordinator, challenger, matchId));

        assertEquals(-1, coordinator.getMatchConfirmationLeaderid(CHALLENGER));
        assertEquals(-1, coordinator.getMatchConfirmationLeaderid(OPPONENT));
        verify(challenger).dropMessage(eq(1), contains("did not answer in time"));
        assertFalse(NPCConversationManager.expireCPQChallenge(coordinator, challenger, matchId));   // only once
    }

    @Test
    void aStaleTimeoutLeavesALaterChallengeAlone() {
        Character challenger = challenger();

        cm.sendCPQChallenge("cpq1", OPPONENT);
        long first = coordinator.getMatchConfirmationId(CHALLENGER);
        coordinator.answerMatchConfirmation(OPPONENT, false);   // declined: the first challenge is gone
        cm.sendCPQChallenge("cpq2", OPPONENT);

        assertFalse(NPCConversationManager.expireCPQChallenge(coordinator, challenger, first));
        assertEquals(CHALLENGER, coordinator.getMatchConfirmationLeaderid(OPPONENT));
        verify(challenger, never()).dropMessage(eq(1), anyString());
    }

    @Test
    void theChallengeArmsThatTimeoutAndTheTimeoutCancelsIt() {
        Character challenger = challenger();

        assertTrue(cm.sendCPQChallenge("cpq1", OPPONENT));

        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(timer).schedule(task.capture(), eq(NPCConversationManager.CPQ_CHALLENGE_TIMEOUT_MS));

        task.getValue().run();   // the timer fires once nobody answered

        assertEquals(-1, coordinator.getMatchConfirmationLeaderid(OPPONENT));
        verify(challenger).dropMessage(eq(1), contains("did not answer in time"));
    }

    @Test
    void theTimeoutLeavesAPlayerTimeToAnswer() {
        assertTrue(NPCConversationManager.CPQ_CHALLENGE_TIMEOUT_MS >= 30_000L, "a prompt must stay open long enough to answer");
    }
}
