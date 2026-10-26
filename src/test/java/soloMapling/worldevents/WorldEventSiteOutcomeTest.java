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
}
