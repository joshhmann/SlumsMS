package soloMapling.ArtificialPlayer.BotAttackSystem;

import client.Character;
import client.Job;
import client.inventory.WeaponType;
import constants.skills.Aran;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BotAranComboTest {

    private static Character aran(int id) {
        Character bot = mock(Character.class);
        when(bot.getId()).thenReturn(id);
        when(bot.getJob()).thenReturn(Job.ARAN4);
        when(bot.getSkillLevel(Aran.COMBO_ABILITY)).thenReturn(10);
        return bot;
    }

    @Test
    void hitsBuildTheComboAndAFinisherSpendsIt() {
        Character bot = aran(91);
        BotAranCombo.afterStrike(bot, Aran.FINAL_BLOW, 8);
        BotAranCombo.afterStrike(bot, Aran.FINAL_BLOW, 8);
        long now = System.currentTimeMillis();
        assertEquals(16, BotAranCombo.combo(bot, now));
        BotAranCombo.spend(bot, 10);
        assertEquals(6, BotAranCombo.combo(bot, now));
        assertEquals(0, BotAranCombo.combo(bot, now + BotAranCombo.DECAY_MS + 1), "gone after 6 s without a hit");
        BotAranCombo.clearBot(91);
    }

    @Test
    void anAranWithoutComboAbilityKeepsNone() {
        Character bot = aran(92);
        when(bot.getSkillLevel(Aran.COMBO_ABILITY)).thenReturn(0);
        BotAranCombo.afterStrike(bot, Aran.FINAL_BLOW, 8);
        assertEquals(0, BotAranCombo.combo(bot, System.currentTimeMillis()));
    }

    @Test
    void theFourthJobFinishersAreTheCombosByCost() {
        List<BotAttackConfig.Extra> extras = BotAttackConfig.resolve(Job.ARAN4, WeaponType.POLE_ARM_SWING).extras();
        assertEquals(Aran.COMBO_TEMPEST, extras.get(0).profile().skillId);
        assertEquals(200, extras.get(0).comboCost());
        assertTrue(extras.stream().allMatch(e -> e.comboCost() == 0
                || e.profile().route == BotAttackProfile.Route.RANGED), "finishers leave as shots");
    }
}
