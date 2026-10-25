package soloMapling.qa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** co-1 gives an all-day and an old timed EXP card, takes them back at stop, and refuses when they do not fit. */
class CouponScenariosTest {
    @TempDir Path directory;

    @Test void givesTwoCouponsAndTakesThemBack() throws Exception {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.giveItem(any(), anyInt(), eq(1))).thenReturn(1);
        when(world.itemName(anyInt())).thenReturn("coupon");
        when(world.takeItem(any(), anyInt(), eq(1))).thenReturn(1);
        when(world.online(1)).thenReturn(gm);
        var suite = QaTestSupport.suite(CouponScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        suite.start(gm, "co-1", new String[0]);
        verify(world).giveItem(gm, CouponScenarios.EXP_WEEKDAYS_7_11, 1);
        verify(world).giveItem(gm, CouponScenarios.EXP_2X_ALL_DAY, 1);
        suite.stop(1, "done");
        verify(world).takeItem(any(), eq(CouponScenarios.EXP_WEEKDAYS_7_11), eq(1));
        verify(world).takeItem(any(), eq(CouponScenarios.EXP_2X_ALL_DAY), eq(1));
    }

    @Test void refusesWhenTheCashInventoryIsFull() {
        var world = mock(QaWorld.class);
        var gm = QaTestSupport.gm(1, "Admin", 100000000);
        when(world.giveItem(any(), anyInt(), anyInt())).thenReturn(0);
        var suite = QaTestSupport.suite(CouponScenarios.all(), world, directory, new QaTestSupport.ManualScheduler());
        assertThrows(QaRefusal.class, () -> suite.start(gm, "co-1", new String[0]));
    }
}
