package soloMapling.menus;

import client.Character;
import client.Job;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.companions.CompanionSettingsStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-settings (finished by Bots): one alt's card in the @settings window - level and job, hp/mp, its potion
 * counts and the three switches as clickable lines; a click saves the opposite and the redrawn card shows it.
 */
class CompanionCardMenuTest {

    private Character owner;
    private Character alt;
    private MockedStatic<BotVitals> vitals;

    @BeforeEach
    void setUp() {
        owner = mock(Character.class);
        when(owner.getId()).thenReturn(7);
        alt = mock(Character.class);
        when(alt.getId()).thenReturn(1_000_000_701);
        when(alt.getName()).thenReturn("Jane");
        when(alt.getLevel()).thenReturn(52);
        when(alt.getJob()).thenReturn(Job.CLERIC);
        when(alt.getHp()).thenReturn(900);
        when(alt.getMaxHp()).thenReturn(1_200);
        when(alt.getMp()).thenReturn(1_500);
        when(alt.getMaxMp()).thenReturn(2_000);
        when(alt.isOwnedAlt()).thenReturn(true);
        vitals = mockStatic(BotVitals.class);
        vitals.when(() -> BotVitals.potionCount(alt, true)).thenReturn(120);
        vitals.when(() -> BotVitals.potionCount(alt, false)).thenReturn(40);
        CompanionSettingsStore.useForTest(new CompanionSettingsStore.Memory(), Runnable::run);
    }

    @AfterEach
    void tearDown() {
        vitals.close();
        CompanionSettingsStore.useForTest(null, null);
    }

    @Test
    void theCardListsTheTogglesAndAClickFlipsOne() {
        MenuWindow.Page page = CompanionCardMenu.page(owner, alt);
        String text = page.text();
        assertTrue(text.contains("Jane") && text.contains("lv52"), text);
        assertTrue(text.contains("hp 900/1200") && text.contains("mp 1500/2000"), text);
        assertTrue(text.contains("120 hp, 40 mp"), text);
        List<String> labels = page.options().stream().map(MenuWindow.Option::label).toList();
        assertEquals(List.of("Party heals: #bON#k", "Party buffs: #bON#k", "Buff pots: #bON#k"), labels);
        assertTrue(page.options().stream().allMatch(MenuWindow.Option::clickable));

        MenuWindow.Menu next = page.options().get(0).pick().apply(owner); // click "Party heals: ON"

        assertFalse(CompanionSettingsStore.get(owner.getId(), alt.getId()).heals(), "the click saved heals off");
        MenuWindow.Page redrawn = next.page(owner);
        assertEquals("Party heals: #rOFF#k", redrawn.options().get(0).label());
        assertEquals("Party buffs: #bON#k", redrawn.options().get(1).label(), "the other switches are untouched");
    }
}
