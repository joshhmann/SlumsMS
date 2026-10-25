package net.server.task;

import client.Character;
import config.YamlConfig;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.5.2 (ticket 80, ported from nutnnut/Cosmic e61767a42): the hourly autosave could start while the server was
 * shutting down and save characters at the same time as the shutdown's own final saves (two writers deleting and
 * re-inserting the same character's rows). A world that is shutting down is not autosaved.
 */
class AutosaveDuringShutdownTest {

    private boolean autosave;

    @BeforeEach
    void enableAutosave() {
        autosave = YamlConfig.config.server.USE_AUTOSAVE;
        YamlConfig.config.server.USE_AUTOSAVE = true;
    }

    @AfterEach
    void restore() {
        YamlConfig.config.server.USE_AUTOSAVE = autosave;
    }

    private static World worldWith(Character chr, boolean shuttingDown) {
        World world = mock(World.class);
        PlayerStorage storage = mock(PlayerStorage.class);
        when(storage.getAllCharacters()).thenReturn(List.of(chr));
        when(world.getPlayerStorage()).thenReturn(storage);
        when(world.isShuttingDown()).thenReturn(shuttingDown);
        return world;
    }

    private static Character player() {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(7);
        when(chr.isLoggedin()).thenReturn(true);
        return chr;
    }

    @Test
    void aWorldShuttingDownIsNotAutosaved() {
        Character chr = player();

        new CharacterAutosaverTask(worldWith(chr, true)).run();

        verify(chr, never()).saveCharToDB(anyBoolean());
    }

    @Test
    void aRunningWorldIsStillAutosaved() {
        Character chr = player();

        new CharacterAutosaverTask(worldWith(chr, false)).run();

        verify(chr).saveCharToDB(false);
    }
}
