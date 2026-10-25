package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** gl-1 drops a godly equip at the GM's feet (default a Sword, or the given id) and refuses a non-equip. */
class GodlyDropScenariosTest {
    @TempDir Path directory;

    @Test void dropsTheDefaultSwordOrTheGivenItem() throws Exception {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.dropGodly(any(), anyInt())).thenReturn("Sword (1302000): STR +3");
        when(world.online(1)).thenReturn(gm);
        var suite = QaTestSupport.suite(GodlyDropScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        suite.start(gm, "gl-1", new String[0]);
        verify(world).dropGodly(gm, 1302000);
        suite.stop(1, "done");
        suite.start(gm, "gl-1", new String[]{"1040002"});
        verify(world).dropGodly(gm, 1040002);
    }

    @Test void refusesAnItemWithoutStats() {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.dropGodly(any(), anyInt())).thenReturn(null);
        var suite = QaTestSupport.suite(GodlyDropScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        assertThrows(QaRefusal.class, () -> suite.start(gm, "gl-1", new String[]{"2000000"}));
    }
}
