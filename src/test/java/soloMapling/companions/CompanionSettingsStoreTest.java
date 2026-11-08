package soloMapling.companions;

import client.BotClient;
import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-settings: the store - one row per (owner, companion), read once, written only on a change, nothing for an
 * untouched companion, and a choice that is still there after the memory is read back from the table again.
 */
class CompanionSettingsStoreTest {

    private static final String ALTS = "slums.companion.settings.alts";
    private static final String REGULARS = "slums.companion.settings.regulars";

    private CompanionSettingsStore.Memory store;
    private Character owner;

    @BeforeEach
    void setUp() {
        owner = player(4_000_001);
        store = new CompanionSettingsStore.Memory();
        CompanionSettingsStore.useForTest(store, Runnable::run);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(ALTS);
        System.clearProperty(REGULARS);
        CompanionSettingsStore.useForTest(null, null);
    }

    @Test
    void anUntouchedCompanionUsesTheDefaultsAndWritesNoRow() {
        Character alt = alt(1_000_000_501);

        assertEquals(CompanionSettings.DEFAULT, CompanionSettingsStore.get(alt));
        assertTrue(CompanionSettingsStore.heals(alt));
        assertTrue(CompanionSettingsStore.support(alt));
        assertTrue(CompanionSettingsStore.buffPots(alt));
        assertEquals(0, CompanionSettingsStore.hpPercent(alt));
        assertTrue(store.rows.isEmpty(), "reading an untouched companion writes nothing");
    }

    @Test
    void aChangeIsStoredAndPutBackToTheDefaultsDeletesTheRow() {
        Character alt = alt(1_000_000_502);

        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withHeals(false));
        assertEquals(1, store.rows.size());
        assertFalse(CompanionSettingsStore.heals(alt));
        assertEquals(CompanionSettings.DEFAULT.withHeals(false),
                store.rows.values().iterator().next().settings());

        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withHeals(true));
        assertTrue(store.rows.isEmpty(), "back to its default: the row goes, so an untouched companion has none");
        assertTrue(CompanionSettingsStore.heals(alt));
    }

    @Test
    void aChosenSettingSurvivesAReload() {
        Character alt = alt(1_000_000_503);
        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withHeals(false).withHpPercent(60).withSupport(false));
        assertEquals(0.60, CompanionSettingsStore.of(alt).hpLine(), 1e-9);

        CompanionSettingsStore.reloadForTest(); // a restart: memory forgotten, read from the table again

        assertFalse(CompanionSettingsStore.heals(alt), "heals off is still off after a reload");
        assertFalse(CompanionSettingsStore.support(alt));
        assertEquals(60, CompanionSettingsStore.hpPercent(alt));
        assertEquals(0, CompanionSettingsStore.mpPercent(alt), "an unset line stays the companion's own");
    }

    /**
     * Bots' audit of the helper's store: after a real restart nothing called the load until the owner used @alts or
     * a toggle, so a bot's tick read the defaults and "heals off" quietly came back on. The bots' own read now starts
     * the load (off the tick's thread; this test's executor runs it at once).
     */
    @Test
    void aRestartedServerReadsTheStoredSettingsWithoutTheOwnerDoingAnything() {
        Character alt = alt(1_000_000_505);
        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withHeals(false));

        CompanionSettingsStore.forgetForTest(); // a restart: nothing loaded, and nobody has typed anything yet

        CompanionSettingsStore.heals(alt);      // the bot's first tick asks
        assertFalse(CompanionSettingsStore.heals(alt), "heals off stays off after a restart");
    }

    @Test
    void anotherOwnersRowForTheSameCompanionIsNotOurs() {
        Character alt = alt(1_000_000_504);
        CompanionSettingsStore.save(player(4_000_009), alt, CompanionSettings.DEFAULT.withHeals(false));

        assertEquals(CompanionSettings.DEFAULT, CompanionSettingsStore.get(owner.getId(), alt.getId()),
                "the card of an owner that never set anything shows the defaults");
        assertEquals(CompanionSettings.DEFAULT.withHeals(false),
                CompanionSettingsStore.get(4_000_009, alt.getId()));
    }

    @Test
    void aRegularIgnoresALegacyRowWithTheDefaults() {
        Character regular = regular(1_000_000_505);
        CompanionSettingsStore.save(owner, regular, CompanionSettings.DEFAULT.withSupport(false));
        assertTrue(CompanionSettingsStore.support(regular), "the settings switch is off for party regulars");

        System.setProperty(REGULARS, "true");
        assertFalse(CompanionSettingsStore.support(regular),
                "with -Dslums.companion.settings.regulars=true a regular keeps what its owner chose");
    }

    @Test
    void aStoreThatCannotBeReadKeepsTheSettingsInMemory() {
        Character alt = alt(1_000_000_506);
        store.failing = true;
        CompanionSettingsStore.reloadForTest();

        CompanionSettingsStore.save(owner, alt, CompanionSettings.DEFAULT.withBuffPots(false));
        assertFalse(CompanionSettingsStore.buffPots(alt), "the change still applies for this session");
    }

    // ── helpers ──

    private static Character player(int id) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        return c;
    }

    private static Character alt(int id) {
        Character c = player(id);
        when(c.getClient()).thenReturn(mock(BotClient.class));
        when(c.isOwnedAlt()).thenReturn(true);
        return c;
    }

    private static Character regular(int id) {
        Character c = player(id);
        when(c.getClient()).thenReturn(mock(BotClient.class));
        when(c.isOwnedAlt()).thenReturn(false);
        return c;
    }
}
