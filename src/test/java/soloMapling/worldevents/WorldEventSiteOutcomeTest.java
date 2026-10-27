package soloMapling.worldevents;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 1.6.0 Track D: how an ended world event reads in the website's feed. */
class WorldEventSiteOutcomeTest {

    @Test
    void wonLostOrEnded() {
        assertEquals("won", WorldEvents.siteOutcome(EventTemplate.Kind.GIANT_CAKE, "won", "Josh", "Josh"));
        assertEquals("won", WorldEvents.siteOutcome(EventTemplate.Kind.BOSS_SIGHTING, "boss killed", null, "SomeBot"),
                "bots beating the boss is a win for the server");
        assertEquals("won", WorldEvents.siteOutcome(EventTemplate.Kind.TREASURE_HUNT, "won", "Josh", null));
        assertEquals("lost", WorldEvents.siteOutcome(EventTemplate.Kind.INVASION, "time up", null, null));
        assertEquals("ended", WorldEvents.siteOutcome(EventTemplate.Kind.HOT_SPOT, "time up", null, null));
        assertEquals("ended", WorldEvents.siteOutcome(EventTemplate.Kind.WEDDING, "time up", null, null));
        assertEquals("ended", WorldEvents.siteOutcome(EventTemplate.Kind.WANDERING_NPC, "time up", null, null));
        assertEquals("ended", WorldEvents.siteOutcome(EventTemplate.Kind.GIANT_CAKE, "stopped by Admin", null, null));
    }

    @Test
    void aGmOrQaWinnerIsNotNamedWhenThereIsNoTopDamage() {
        assertEquals("TopBot", WorldEvents.siteName("TopBot", "AutoQaOne", false));
        assertEquals("Josh", WorldEvents.siteName(null, "Josh", true));
        assertNull(WorldEvents.siteName(null, "AutoQaOne", false), "a treasure found by a GM or a pretend player");
    }

    private static ActiveEvent event(EventTemplate.Kind kind) {
        EventTemplate t = new EventTemplate(kind.name().toLowerCase(), kind, "Test " + kind.label(), 1, 30, 0, 0, 0, 4,
                java.util.List.of(EventTemplate.Spot.map(100000000)), java.util.List.of(), 0, 0, 1.5,
                java.util.List.of(), true);
        return new ActiveEvent(7, t, t.spots().get(0), 1, 1_760_000_000_000L, "scheduler", 1, 255);
    }

    private static client.Character chr(String name, int gmLevel, boolean synthetic) {
        client.Character c = org.mockito.Mockito.mock(client.Character.class);
        org.mockito.Mockito.when(c.getId()).thenReturn(42);
        org.mockito.Mockito.when(c.getName()).thenReturn(name);
        org.mockito.Mockito.when(c.gmLevel()).thenReturn(gmLevel);
        if (synthetic) {
            org.mockito.Mockito.when(c.getClient()).thenReturn(org.mockito.Mockito.mock(client.SyntheticClient.class));
        }
        return c;
    }

    @Test
    void noEventKindNamesAGmOrAutoQaWinnerOnTheSite() {
        for (EventTemplate.Kind kind : EventTemplate.Kind.values()) {
            for (client.Character hidden : java.util.List.of(chr("Admin", 3, false), chr("AutoQaOne", 0, false),
                    chr("Pretend", 0, true))) {
                ActiveEvent e = event(kind);
                WorldEvents.setWinner(e, hidden);   // treasure found / quest turned in / boss down, no top damage
                assertEquals(hidden.getName(), e.winnerName, "the game still knows who won");
                String outcome = WorldEvents.siteOutcome(kind, "won", e.winnerName, e.topDamageName);
                assertNull(WorldEvents.siteEndName(e, outcome), kind + " must not name " + hidden.getName());
            }
            ActiveEvent e = event(kind);
            WorldEvents.setWinner(e, chr("Josh", 0, false));
            String outcome = WorldEvents.siteOutcome(kind, "won", e.winnerName, e.topDamageName);
            assertEquals("won".equals(outcome) ? "Josh" : null, WorldEvents.siteEndName(e, outcome),
                    kind + ": a real player is named only on a win");
        }
    }
}
