package soloMapling.companions;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Owner, 8 Nov 2026: "@botme quests on" survives a restart, kept with the alt's other settings (companionsettings,
 * migration 069) instead of in memory only.
 */
class CompanionQuestOptInPersistTest {

    private CompanionSettingsStore.Memory store;
    private Character owner;
    private Character alt;

    @BeforeEach
    void setUp() {
        store = new CompanionSettingsStore.Memory();
        CompanionSettingsStore.useForTest(store, Runnable::run);
        CompanionQuestMirror.clearForTest();
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(4_000_101);
        alt = mock(Character.class);
        when(alt.getId()).thenReturn(1_000_000_901);
        when(alt.getClient()).thenReturn(mock(BotClient.class));
        when(alt.isOwnedAlt()).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        CompanionQuestMirror.clearForTest();
        CompanionSettingsStore.useForTest(null, null);
    }

    /** A restart: the mirror's memory and the store's memory are both gone, the table is read again. */
    private static void restart() {
        CompanionQuestMirror.clearForTest();
        CompanionSettingsStore.reloadForTest();
    }

    @Test
    void questsOnIsStillOnAfterARestart() {
        CompanionQuestMirror.setOptIn(owner, alt, true);
        assertTrue(CompanionQuestMirror.isOptedIn(owner.getId(), alt.getId()));
        assertEquals(1, store.rows.size(), "kept as the alt's settings row");

        restart();

        assertTrue(CompanionQuestMirror.isOptedIn(owner.getId(), alt.getId()), "on after a restart, untyped");
        assertFalse(CompanionQuestMirror.isOptedIn(4_000_999, alt.getId()), "for its own owner only");
    }

    @Test
    void questsOffIsSavedAndAnAllDefaultRowGoesAway() {
        CompanionQuestMirror.setOptIn(owner, alt, true);
        CompanionQuestMirror.setOptIn(owner, alt, false);
        assertTrue(store.rows.isEmpty(), "off with every other setting at its default: no row");

        restart();

        assertFalse(CompanionQuestMirror.isOptedIn(owner.getId(), alt.getId()));
    }

    @Test
    void questsOnKeepsTheOtherSettings() {
        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withHeals(false));
        CompanionQuestMirror.setOptIn(owner, alt, true);
        restart();
        CompanionSettings now = CompanionSettingsStore.get(owner.getId(), alt.getId());
        assertFalse(now.heals(), "heals off is kept");
        assertTrue(now.quests());
    }
}
