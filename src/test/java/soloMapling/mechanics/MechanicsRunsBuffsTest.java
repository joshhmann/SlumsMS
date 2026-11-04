package soloMapling.mechanics;

import client.Character;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/*
 * 1.6.4 (bot audit, item 1): a run stops the bot's brain, which stops the self-buff loop its own type runs
 * (FollowerBot.supportParty -> BotBuffDriver.selfBuff); nothing else casts buffs for a held bot, so it fought the whole
 * boss with nothing up. MechanicsRuns.upkeep restores exactly that loop, per held bot, behind -Dslums.mechanics.boss.
 */
class MechanicsRunsBuffsTest {

    private static Character bot(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    @Test
    void aRunKeepsEachHeldBotsOwnBuffsUpWhileTheBossSwitchIsOn() {
        Character a = bot(11);
        Character b = bot(12);
        System.setProperty("slums.mechanics.boss", "true");
        try (MockedStatic<BotBuffDriver> buffs = mockStatic(BotBuffDriver.class)) {
            buffs.when(() -> BotBuffDriver.selfBuff(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn(9_999L);

            MechanicsRuns.upkeep(List.of(a, b), 1_000L);

            buffs.verify(() -> BotBuffDriver.selfBuff(eq(a), eq(1_000L), anyLong(), any(),
                    eq(BotBuffDriver.Pay.PARTY_BUFFS), any()));
            buffs.verify(() -> BotBuffDriver.selfBuff(eq(b), eq(1_000L), anyLong(), any(),
                    eq(BotBuffDriver.Pay.PARTY_BUFFS), any()));
        } finally {
            System.clearProperty("slums.mechanics.boss");
        }
    }

    @Test
    void withTheBossSwitchOffARunCastsNothing() {
        Character a = bot(13);
        try (MockedStatic<BotBuffDriver> buffs = mockStatic(BotBuffDriver.class)) {
            MechanicsRuns.upkeep(List.of(a), 1_000L);

            buffs.verifyNoInteractions();
        }
    }

    @Test
    void theBuffLoopIsHandedBackItsOwnTimerSoItDoesNotCastEveryTick() {
        Character a = bot(14);
        System.setProperty("slums.mechanics.boss", "true");
        try (MockedStatic<BotBuffDriver> buffs = mockStatic(BotBuffDriver.class)) {
            buffs.when(() -> BotBuffDriver.selfBuff(any(), anyLong(), anyLong(), any(), any(), any())).thenReturn(7_000L);

            MechanicsRuns.upkeep(List.of(a), 1_000L);
            MechanicsRuns.upkeep(List.of(a), 1_500L);

            buffs.verify(() -> BotBuffDriver.selfBuff(eq(a), eq(1_000L), eq(0L), any(), any(), any()));
            buffs.verify(() -> BotBuffDriver.selfBuff(eq(a), eq(1_500L), eq(7_000L), any(), any(), any()));
        } finally {
            System.clearProperty("slums.mechanics.boss");
        }
    }
}
