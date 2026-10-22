package soloMapling.ArtificialPlayer.BotConvo;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 59, owner decision 2: the relationship memory card. */
class RelationshipCardTest {

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");
    // Thursday 2026-10-22, 20:00
    private static final long NOW = LocalDateTime.of(2026, 10, 22, 20, 0).atZone(ZONE).toInstant().toEpochMilli();
    private static final long DAY = 86_400_000L;

    @Test
    void wordsOnlyNeverNumbers() {
        String card = RelationshipCard.build(new RelationshipCard.Data("Pinky", AffinityState.FRIEND, 72,
                List.of(new RelationshipCard.Fact("did LPQ together", null, NOW - 2 * DAY),
                        new RelationshipCard.Fact("ks'd me in ant tunnel", null, NOW - DAY)),
                "You're dating them", "Same guild (Maplers)", "Ant Tunnel I", List.of("zombie mushroom", "horny mushroom"),
                null, null, "grinding", null), NOW, ZONE);
        assertTrue(card.contains("you're close friends"), card);
        assertTrue(card.contains("did LPQ together (on Tuesday)") && card.contains("ks'd me in ant tunnel (yesterday)"), card);
        assertTrue(card.contains("You're dating them") && card.contains("Same guild (Maplers)"), card);
        assertTrue(card.contains("Now: Ant Tunnel I, zombie mushroom and horny mushroom around, evening, grinding."), card);
        assertFalse(card.replace("Ant Tunnel I", "").matches(".*\\d.*"), "no numbers: " + card);
        assertTrue(card.length() <= RelationshipCard.MAX_CHARS);
    }

    @Test
    void feelingsInWords() {
        assertEquals("you don't really know them", RelationshipCard.feeling(AffinityState.NEUTRAL, 0, false));
        assertEquals("you've met before", RelationshipCard.feeling(AffinityState.NEUTRAL, 0, true));
        assertEquals("you have beef with them", RelationshipCard.feeling(AffinityState.RIVAL, -40, true));
        assertEquals("they're your nemesis, you can't stand them", RelationshipCard.feeling(AffinityState.NEMESIS, -90, true));
        assertEquals("you're friends", RelationshipCard.feeling(AffinityState.FRIEND, 30, true));
    }

    @Test
    void whenInWords() {
        assertEquals("just now", RelationshipCard.when(NOW - 600_000, NOW, ZONE));
        assertEquals("earlier today", RelationshipCard.when(NOW - 5 * 3_600_000L, NOW, ZONE));
        assertEquals("yesterday", RelationshipCard.when(NOW - DAY, NOW, ZONE));
        assertEquals("on Tuesday", RelationshipCard.when(NOW - 2 * DAY, NOW, ZONE));
        assertEquals("last week", RelationshipCard.when(NOW - 9 * DAY, NOW, ZONE));
        assertEquals("a while ago", RelationshipCard.when(NOW - 30 * DAY, NOW, ZONE));
    }

    @Test
    void atMostFourFactsHearsayMarkedAndAWorldEvent() {
        List<RelationshipCard.Fact> facts = List.of(
                new RelationshipCard.Fact("a", null, NOW), new RelationshipCard.Fact("b", null, NOW),
                new RelationshipCard.Fact("c", null, NOW), new RelationshipCard.Fact("they KS a lot", "xKaiz", NOW - DAY),
                new RelationshipCard.Fact("e", null, NOW));
        String card = RelationshipCard.build(new RelationshipCard.Data("Bob", AffinityState.NEUTRAL, 0, facts, null, null,
                "Henesys", List.of(), null, "Mushroom Invasion at Henesys", null, null), NOW, ZONE);
        assertTrue(card.contains("heard from xKaiz: they KS a lot (yesterday)"), card);
        assertFalse(card.contains("; e"), "five facts trimmed to four: " + card);
        assertTrue(card.contains("Hearsay is gossip"), card);
        assertTrue(card.contains("World event: Mushroom Invasion at Henesys."), card);
    }

    @Test
    void threadContextShows() {
        String card = RelationshipCard.build(new RelationshipCard.Data("Bob", AffinityState.NEUTRAL, 0, List.of(), null,
                null, "Henesys", List.of(), "morning", null, null, "You were just talking with Kimchi about LPQ"), NOW, ZONE);
        assertTrue(card.contains("You were just talking with Kimchi about LPQ."), card);
        assertTrue(card.contains("morning"), card);
    }
}
