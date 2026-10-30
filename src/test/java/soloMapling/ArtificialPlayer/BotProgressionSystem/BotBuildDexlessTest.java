package soloMapling.ArtificialPlayer.BotProgressionSystem;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

// Ticket 162 part 1 (dexless build): a gear-aware cap on the secondary stat, never below the class's floor.
class BotBuildDexlessTest {

    private static BotBuild.State warrior(int ap) {
        BotBuild.State s = new BotBuild.State(110, 30);
        s.str = 60;
        s.dex = 10;
        s.remainingAp = ap;
        return s;
    }

    @Test
    void withoutACapTheLevelRuleRaisesDex() {
        BotBuild.State s = warrior(20);
        BotBuild.spendAp(s, BotBuild.Choice.of(1));
        assertEquals(19, s.dex);   // 4 + 30/2
        assertEquals(71, s.str);
    }

    @Test
    void aDexlessCapPutsTheRestInStr() {
        BotBuild.State s = warrior(20);
        s.secondaryCap = 10;
        BotBuild.spendAp(s, BotBuild.Choice.of(1));
        assertEquals(10, s.dex);
        assertEquals(80, s.str);
    }

    @Test
    void theCapNeverGoesUnderTheClassFloorAndSkipsMagicians() {
        assertEquals(25, BotBuild.secondaryFloor(410, BotBuild.Choice.of(1)));
        assertEquals(Integer.MAX_VALUE, BotBuild.secondaryFloor(210, BotBuild.Choice.of(1)));
        BotBuild.State thief = new BotBuild.State(410, 30);
        thief.luk = 60;
        thief.dex = 10;
        thief.remainingAp = 20;
        thief.secondaryCap = 0;
        BotBuild.spendAp(thief, BotBuild.Choice.of(1));
        assertEquals(25, thief.dex);
    }
}
