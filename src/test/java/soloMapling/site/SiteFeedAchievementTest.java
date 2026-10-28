package soloMapling.site;

import client.Character;
import client.Client;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 93: earned achievements are public site news, for public characters only. */
class SiteFeedAchievementTest {
    @BeforeEach
    void setUp() {
        SiteFeed.resetForTest(() -> 1_800_000_000_000L);
        SiteFeed.setRecording(true);
    }

    @AfterEach
    void tearDown() {
        SiteFeed.setRecording(false);
        SiteFeed.resetForTest(null);
    }

    static Character character(String name, int gm, boolean synthetic) {
        Character c = mock(Character.class);
        Client client = synthetic ? mock(client.SyntheticClient.class) : mock(Client.class);
        when(c.getName()).thenReturn(name);
        when(c.gmLevel()).thenReturn(gm);
        when(c.getClient()).thenReturn(client);
        return c;
    }

    @Test
    void anEarnedAchievementReachesTheSiteFeedForPublicCharactersOnly() {
        SiteFeed.achievement(character("Bobbins", 0, false), "Fishing Hobby", "Fishing Master Medal");
        SiteFeed.achievement(character("StaffGuy", 3, false), "Fishing Hobby", "Fishing Master Medal");
        SiteFeed.achievement(character("AutoQaOne", 0, true), "Fishing Hobby", "Fishing Master Medal");
        List<SiteFeed.Item> items = SiteFeed.snapshot(1_800_000_000_000L);
        assertEquals(1, items.size());
        SiteFeed.Item i = items.get(0);
        assertEquals(SiteFeed.Kind.MILESTONE, i.kind());
        assertEquals("achievement", i.tag());
        assertEquals(List.of("Bobbins"), i.names());
        assertEquals("Bobbins earned the achievement \"Fishing Hobby\" (Fishing Master Medal).", i.text());
    }
}
