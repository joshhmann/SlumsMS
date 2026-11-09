package soloMapling.ArtificialPlayer.BotSupplySystem;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.companions.CompanionSettings;
import soloMapling.companions.CompanionSettingsStore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-settings: the owner's potion lines replace the bot's own for a companion that set them, through the one
 * read the shared layer uses ({@link CompanionSettingsStore#hpPercent}); unset is today's line, boss run included.
 */
class BotVitalsOwnerLineTest {

    private static final int ALT = 1_000_000_601;

    @AfterEach
    void clean() {
        CompanionSettingsStore.useForTest(null, null);
    }

    @Test
    void theOwnersPercentagesMoveThePotionLines() {
        Character alt = alt();
        Character owner = mock(Character.class);
        when(owner.getId()).thenReturn(4_000_601);
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);

        double hpToday = BotVitals.hpPotAt(alt);
        double mpToday = BotVitals.mpPotAt(alt);
        assertEquals(BotVitals.HP_POT_AT, hpToday, 1e-9, "unset: today's line");
        assertEquals(BotVitals.MP_POT_AT, mpToday, 1e-9);

        CompanionSettingsStore.save(owner, alt,
                CompanionSettings.DEFAULT.withHpPercent(60).withMpPercent(40));
        assertEquals(0.60, BotVitals.hpPotAt(alt), 1e-9, "the owner's HP line replaces the bot's own");
        assertEquals(0.40, BotVitals.mpPotAt(alt), 1e-9);

        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT);
        assertEquals(hpToday, BotVitals.hpPotAt(alt), 1e-9, "back to the default: the bot's own line again");
        assertEquals(mpToday, BotVitals.mpPotAt(alt), 1e-9);
    }

    private static Character alt() {
        Character alt = mock(Character.class);
        when(alt.getId()).thenReturn(ALT);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        when(alt.isOwnedAlt()).thenReturn(true);
        return alt;
    }
}
