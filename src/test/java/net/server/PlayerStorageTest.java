package net.server;

import client.BotClient;
import client.Character;
import client.Client;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerStorageTest {

    @Test
    void botsAreStoredButNotCountedAsConnectedPlayers() {
        PlayerStorage storage = new PlayerStorage();
        storage.addPlayer(character(1, "Player", mock(Client.class)));
        storage.addPlayer(character(1_000_000_001, "BotOne", mock(BotClient.class)));
        storage.addPlayer(character(1_000_000_002, "BotTwo", mock(BotClient.class)));

        assertEquals(3, storage.getSize());
        assertEquals(1, storage.getConnectedPlayerCount());
    }

    @Test
    void autoQaSyntheticPlayersNeverFillAWorld() {
        PlayerStorage storage = new PlayerStorage();
        storage.addPlayer(character(1, "Player", mock(Client.class)));
        storage.addPlayer(character(2, "AutoQaOne", new client.SyntheticClient(1, 0, 1)));
        storage.addPlayer(character(3, "AutoQaTwo", new client.SyntheticClient(2, 0, 1)));

        assertEquals(3, storage.getSize());
        assertEquals(1, storage.getConnectedPlayerCount());
    }

    private static Character character(int id, String name, Client client) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.getClient()).thenReturn(client);
        return chr;
    }
}
