package client.command.commands.gm6;

import client.BotClient;
import client.Character;
import client.Character.SaveResult;
import client.Client;
import net.server.PlayerStorage;
import net.server.Server;
import net.server.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 1.6.5 P0-5: !saveall tells the GM what really happened instead of "All players saved successfully." */
class SaveAllCommandTest {

    private static Character chr(String name, SaveResult result) {
        Character c = mock(Character.class);
        when(c.getName()).thenReturn(name);
        when(c.saveCharToDBResult(true, null)).thenReturn(result);
        // the pre-P0-5 entry point: a mock does nothing, as the old code ignored what a save did
        return c;
    }

    private static String runAndGetMessage(List<Character> everyone) {
        Character gm = mock(Character.class);
        when(gm.getName()).thenReturn("Boss");
        Client client = mock(Client.class);
        when(client.getPlayer()).thenReturn(gm);
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(storage.getAllCharacters()).thenReturn(everyone);
        when(world.getPlayerStorage()).thenReturn(storage);
        Server server = mock(Server.class);
        when(server.getWorlds()).thenReturn(List.of(world));
        try (MockedStatic<Server> servers = mockStatic(Server.class)) {
            servers.when(Server::getInstance).thenReturn(server);
            new SaveAllCommand().execute(client, new String[0]);
        }
        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(gm).message(msg.capture());
        return msg.getValue();
    }

    @Test
    void oneFailedSaveIsReportedByNameAndTheOthersAreCountedSaved() {
        String message = runAndGetMessage(List.of(chr("Ann", SaveResult.SAVED), chr("Bob", SaveResult.FAILED),
                chr("Cy", SaveResult.SAVED)));
        assertTrue(message.contains("2 saved, 1 failed (Bob)"), message);
        assertFalse(message.contains("successfully"), message);
    }

    @Test
    void skippedCharactersAreNotFailures() {
        String message = runAndGetMessage(List.of(chr("Ann", SaveResult.SAVED), chr("Rosie", SaveResult.SKIPPED)));
        assertTrue(message.contains("1 saved, 0 failed"), message);
        assertTrue(message.contains("1 skipped"), message);
    }

    @Test
    void aRealRosterBotAndALoggedOutCharacterAreSkippedNotFailed() {
        Character roster = Character.getDefault(new BotClient(1, 1));
        roster.setID(1_000_000_100);   // a roster bot
        Character offline = Character.getDefault(new BotClient(1, 1));
        offline.setID(5);              // owned by nobody, never logged in
        var report = SaveAllCommand.saveAll(List.of(roster, offline));
        assertEquals(0, report.saved());
        assertEquals(2, report.skipped());
        assertTrue(report.failed().isEmpty());
    }

    @Test
    void theFailedListIsCappedAtTwenty() {
        List<Character> many = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(chr("P" + i, SaveResult.FAILED));
        }
        String message = SaveAllCommand.saveAll(many).message();
        assertTrue(message.contains("0 saved, 25 failed"), message);
        assertTrue(message.contains("and 5 more"), message);
        assertFalse(message.contains("P20"), message);
    }
}
