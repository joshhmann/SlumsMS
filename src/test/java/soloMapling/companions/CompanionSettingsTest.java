package soloMapling.companions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.7 alt-settings: one companion's owner choices as a record, and its JSON (the store's one column). */
class CompanionSettingsTest {

    @Test
    void settingsRoundTripThroughJson() {
        CompanionSettings chosen = new CompanionSettings(false, true, false, 60, 40, "own-kills");
        CompanionSettings back = CompanionSettings.parse(chosen.toJson());
        assertEquals(chosen, back);
        assertEquals(chosen.heals(), back.heals());
        assertEquals(chosen.support(), back.support());
        assertEquals(chosen.buffPots(), back.buffPots());
        assertEquals(60, back.hpPercent());
        assertEquals(40, back.mpPercent());
        assertEquals("own-kills", back.lootMode());

        assertEquals(CompanionSettings.DEFAULT, CompanionSettings.parse(CompanionSettings.DEFAULT.toJson()),
                "an untouched companion's JSON is the defaults again");
    }

    @Test
    void clampsThePercentagesToTenToNinety() {
        CompanionSettings tooLow = new CompanionSettings(true, true, true, 5, 5, "").clamped();
        assertEquals(CompanionSettings.MIN_PERCENT, tooLow.hpPercent());
        assertEquals(CompanionSettings.MIN_PERCENT, tooLow.mpPercent());

        CompanionSettings tooHigh = new CompanionSettings(true, true, true, 95, 200, "").clamped();
        assertEquals(CompanionSettings.MAX_PERCENT, tooHigh.hpPercent());
        assertEquals(CompanionSettings.MAX_PERCENT, tooHigh.mpPercent());

        assertEquals(CompanionSettings.AUTO, new CompanionSettings(true, true, true, 0, 0, "").hpPercent(),
                "0 means the companion's own line");
        assertEquals(10, CompanionSettings.parse("{\"hpPercent\":9}").hpPercent(),
                "a stored 9 is read as the lowest the owner may set");

        assertEquals(0.6, new CompanionSettings(true, true, true, 60, 0, "").hpLine(), 1e-9);
        assertEquals(0.0, CompanionSettings.DEFAULT.hpLine(), 1e-9);
    }

    @Test
    void anUnreadableBlobOrABadFieldFallsBackToTheDefault() {
        assertEquals(CompanionSettings.DEFAULT, CompanionSettings.parse(null));
        assertEquals(CompanionSettings.DEFAULT, CompanionSettings.parse(""));
        assertEquals(CompanionSettings.DEFAULT, CompanionSettings.parse("not json"));
        assertEquals(CompanionSettings.DEFAULT, CompanionSettings.parse("{\"heals\":"));
        CompanionSettings odd = CompanionSettings.parse("{\"heals\":\"maybe\",\"hpPercent\":\"a lot\",\"loot\":\"all\"}");
        assertEquals(CompanionSettings.DEFAULT, odd, "a bad value takes the default, an unknown key is ignored");
    }

    @Test
    void aDefaultRecordKnowsItIsOneAndTheTogglesChangeIt() {
        assertTrue(CompanionSettings.DEFAULT.isDefault());
        assertFalse(CompanionSettings.DEFAULT.withHeals(false).isDefault());
        assertFalse(CompanionSettings.DEFAULT.withSupport(false).isDefault());
        assertFalse(CompanionSettings.DEFAULT.withBuffPots(false).isDefault());
        assertFalse(CompanionSettings.DEFAULT.withHpPercent(60).isDefault());
        assertFalse(CompanionSettings.DEFAULT.withMpPercent(40).isDefault());
        assertEquals(CompanionSettings.DEFAULT, CompanionSettings.DEFAULT.withHeals(false).withHeals(true),
                "flipping back is the default again, so the store deletes the row");
        assertEquals("own-kills", CompanionSettings.DEFAULT.withLootMode(" Own-Kills ").lootMode());
    }
}
