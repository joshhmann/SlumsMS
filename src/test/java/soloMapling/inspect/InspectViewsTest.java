package soloMapling.inspect;

import client.BotClient;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.server.Json;

import java.awt.Point;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 157: the inspect endpoint's documents keep the shapes docs/design/inspect-api.md promises. */
class InspectViewsTest {

    private static final int MAP = 910000962;

    private static Character bot(int id, String name, int x, int y) {
        Character c = Character.getDefault(new BotClient(0, 0));
        c.setID(1_000_000_000 + id);
        c.setName(name);
        c.setJob(Job.HUNTER);
        c.setLevel(34);
        c.setMapId(MAP);
        c.setPosition(new Point(x, y));
        return c;
    }

    private static Character player() {
        Character p = Mockito.mock(Character.class);   // a real player's client is no BotClient
        Mockito.when(p.getMapId()).thenReturn(MAP);
        Mockito.when(p.getName()).thenReturn("Owner");
        return p;
    }

    @Test
    void positionsAreCompactRowsOfBotsOnly() {
        Map<String, Object> doc = InspectViews.positions(List.of(bot(9, "zed", 5, -7), player(), bot(2, "amy", 1, 2)), 42);
        assertEquals(List.of("ok", "at", "fields", "count", "bots", "maps"), List.copyOf(doc.keySet()));
        assertEquals(List.of("cid", "map", "x", "y"), doc.get("fields"));
        assertEquals(2, doc.get("count"));
        assertEquals(List.of(List.of(1_000_000_002, MAP, 1, 2), List.of(1_000_000_009, MAP, 5, -7)), doc.get("bots"));
        assertEquals(Map.of(String.valueOf(MAP), 2), doc.get("maps"));
        assertTrue(Json.write(doc).contains("\"bots\":[[1000000002," + MAP + ",1,2]"));
    }

    @Test
    void aMapListsItsBotsAndCountsItsPlayers() {
        Map<String, Object> doc = InspectViews.map(MAP, List.of(bot(3, "bo", 10, 20), player()), List.of(), 42);
        assertEquals(List.of("ok", "at", "map", "lod", "players", "botCount", "bots", "mobCount", "mobs", "chatLines"),
                List.copyOf(doc.keySet()));
        assertEquals(1, doc.get("players"));
        assertEquals(1, doc.get("botCount"));
        Map<?, ?> row = (Map<?, ?>) ((List<?>) doc.get("bots")).getFirst();
        assertEquals(List.of("cid", "name", "level", "job", "channel", "x", "y", "nav"), List.copyOf(row.keySet()));
        assertEquals("bo", row.get("name"));
        assertEquals(10, row.get("x"));
        assertEquals(0, doc.get("mobCount"));
        assertEquals(0, doc.get("chatLines"));
        assertTrue(Json.write(doc).startsWith("{\"ok\":true"));
    }

    @Test
    void aBotDocumentHasEveryFieldEvenWithoutABrain() {
        Map<String, Object> doc = InspectViews.bot(bot(4, "cy", 7, 8), null, 42);
        assertEquals(List.of("ok", "at", "cid", "name", "level", "job", "jobId", "kind", "ownedAlt", "doing",
                "lifePhase", "map", "channel", "x", "y", "lod", "tick", "nav", "grinding", "gear", "bartle", "goals",
                "plan", "memory", "mode", "mind", "bonds", "learning", "why"), List.copyOf(doc.keySet()));
        assertEquals(1_000_000_004, doc.get("cid"));
        assertEquals("HUNTER", doc.get("job"));
        assertEquals("bot", doc.get("kind"));
        assertEquals("off", doc.get("nav"), "not on dynamic movement");
        Map<?, ?> bartle = (Map<?, ?>) doc.get("bartle");
        assertTrue(bartle != null && bartle.containsKey("leading") && bartle.containsKey("blend")
                && bartle.containsKey("channels"), String.valueOf(bartle));
        Map<?, ?> memory = (Map<?, ?>) doc.get("memory");
        assertEquals(0, memory.get("notesCached"));
        assertNull(doc.get("mode"), "no brain, no mode");
        assertTrue(doc.get("mind") instanceof Map<?, ?> m && m.containsKey("strongestNeed"),
                "the bartle read above loaded this bot's memory, so its mind is shown");
        Json.write(doc);   // writable
    }

    @Test
    void aBotWithALoadedMemoryShowsItsModeMindAndBonds() {
        Character c = bot(77, "dee", 3, 4);
        LifeMemoryStore.of(c.getId()).mind(m -> {
            m.feel(SocialMind.Emotion.SADNESS, 0.8, 1_000);
            return null;
        });
        Map<String, Object> doc = InspectViews.bot(c, Mockito.mock(SocialBot.class), 1_000);
        assertEquals("social", doc.get("mode"));
        Map<?, ?> mind = (Map<?, ?>) doc.get("mind");
        assertEquals(List.of("mood", "moodWord", "traits", "emotions", "topEmotions", "needs", "strongestNeed"),
                List.copyOf(mind.keySet()));
        Map<?, ?> top = (Map<?, ?>) ((List<?>) mind.get("topEmotions")).getFirst();
        assertEquals("sadness", top.get("name"));
        Map<?, ?> bonds = (Map<?, ?>) doc.get("bonds");
        assertEquals(0, bonds.get("count"));
        assertEquals(List.of(), bonds.get("top"));
        String json = Json.write(doc);
        assertTrue(json.contains("\"mind\":{\"mood\":") && json.contains("\"mode\":\"social\""), json);
    }
}
