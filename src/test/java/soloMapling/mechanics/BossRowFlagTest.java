package soloMapling.mechanics;

import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import testutil.PropsRestored;

/**
 * A boss row can be switched off on its own, on top of the boss lane's own switch.
 *
 * <p>The lane has {@code -Dslums.mechanics.boss}; the four Normal-tier fights are four rows of one shape, and an operator
 * may want three of them. {@link Mechanics#bossEnabledFor} is the second key, and it must not be looser than
 * {@code bossesEnabled()}: a row flag can only ever narrow, never open a lane.
 */
@ExtendWith(PropsRestored.class)
class BossRowFlagTest {

    @AfterEach
    void clearSwitches() {
        System.clearProperty(Mechanics.BOSS_FLAG);
    }

    private static MechanicsModel.Content row(String id) {
        StageData.Result r = StageData.loadDir(Path.of(Mechanics.DEFAULT_DIR));
        assertTrue(r.errors().isEmpty(), "the data folder parses: " + r.errors());
        return r.content().stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void theLaneSwitchIsStillTheFirstKey() {
        MechanicsModel.Content will = row("nwill");
        assertFalse(Mechanics.bossEnabledFor(will), "lane off: the row cannot open it");
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        assertFalse(Mechanics.bossEnabledFor(will), "lane on, the row's own flag off: still shut");
    }

    @Test
    void aRowWithItsOwnFlagNeedsBothKeys() {
        MechanicsModel.Content will = row("nwill");
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        System.setProperty(will.flag(), "true");
        assertTrue(Mechanics.bossEnabledFor(will), "both keys: the row may arm");
        System.setProperty(will.flag(), "false");
        assertFalse(Mechanics.bossEnabledFor(will), "and the row's key alone closes it again");
    }

    @Test
    void aRowThatNamesNoFlagIsGatedByTheLaneOnly() {
        MechanicsModel.Content zakum = row("zakum");
        assertTrue(zakum.flag() == null || zakum.flag().isBlank(), "zakum.yaml predates per-row boss flags");
        System.setProperty(Mechanics.BOSS_FLAG, "true");
        assertTrue(Mechanics.bossEnabledFor(zakum), "the lane alone is enough for a row that names no flag");
    }
}
