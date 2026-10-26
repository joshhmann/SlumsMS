package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotWorldGossip.Kind;
import soloMapling.ArtificialPlayer.BotWorldGossip.News;
import soloMapling.server.EventMessageSystem.EventFactory;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Ticket 85: boss kills, drops and weddings reach the gossip feed and fade. */
class BotWorldGossipTest {

    @BeforeEach
    void reset() {
        BotWorldGossip.resetForTest();
    }

    private static Character player(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        MapleMap map = mock(MapleMap.class);
        when(map.getChannelServer()).thenReturn(mock(net.server.channel.Channel.class));
        when(map.getAllPlayers()).thenReturn(List.of());
        when(c.getMap()).thenReturn(map);
        return c;
    }

    @Test
    void aRealBossKillEventLandsInTheFeed() {
        Character josh = player(42, "Josh");
        BotWorldGossip.register();
        BotWorldGossip.getInstanceForTest().onEvent(EventFactory.createBossKillEvent(josh, "Zakum", 140));
        List<News> feed = BotWorldGossip.feedForTest();
        assertEquals(1, feed.size());
        assertEquals(Kind.BOSS, feed.get(0).kind);
        assertEquals("Josh", feed.get(0).who);
        assertEquals("Zakum", feed.get(0).what);
    }

    @Test
    void theSameKillTwiceInOneFightIsOneItem() {
        Character josh = player(42, "Josh");
        assertNotNull(BotWorldGossip.record(Kind.BOSS, josh, "Zakum", null, 1_000));
        assertNull(BotWorldGossip.record(Kind.BOSS, josh, "Zakum", null, 2_000));
        assertNotNull(BotWorldGossip.record(Kind.DROP, josh, "Chaos Scroll 60%", "Zakum", 3_000), "a different deed");
        assertEquals(2, BotWorldGossip.feedForTest().size());
    }

    @Test
    void aWeddingBecomesOneItemWithBothNames() {
        BotWorldGossip.onWedding(player(42, "Josh"), player(43, "Amy"));
        List<News> feed = BotWorldGossip.feedForTest();
        assertEquals(1, feed.size());
        assertEquals(Kind.WEDDING, feed.get(0).kind);
        String line = BotWorldGossip.line(feed.get(0), true, "", new Random(1));
        assertTrue(line.contains("Josh") && line.contains("Amy"), line);
    }

    @Test
    void newsFadesByHalfEveryTwoHoursAndIsGoneAfterEight() {
        assertEquals(1.0, BotWorldGossip.freshness(0), 1e-9);
        assertEquals(0.5, BotWorldGossip.freshness(BotWorldGossip.HALF_LIFE_MS), 1e-9);
        assertEquals(0.25, BotWorldGossip.freshness(2 * BotWorldGossip.HALF_LIFE_MS), 1e-9);
        assertEquals(0.0, BotWorldGossip.freshness(BotWorldGossip.TTL_MS + 1));
    }

    @Test
    void staleOrOverToldNewsIsNotBroughtUp() {
        long now = TimeUnit.HOURS.toMillis(20);
        News old = BotWorldGossip.newsForTest(Kind.BOSS, "Josh", 42, "Zakum", null, now - BotWorldGossip.TTL_MS - 1);
        News told = BotWorldGossip.newsForTest(Kind.BOSS, "Amy", 43, "Pap", null, now);
        for (int i = 0; i < BotWorldGossip.MAX_MENTIONS; i++) {
            told.mentionedForTest();
        }
        Random rng = new Random(4);
        for (int i = 0; i < 500; i++) {
            assertNull(BotWorldGossip.choose(List.of(old, told), now, rng));
        }
    }

    @Test
    void freshNewsIsPickedMoreOftenThanOldNews() {
        long now = TimeUnit.HOURS.toMillis(20);
        int fresh = 0;
        int older = 0;
        Random rng = new Random(8);
        for (int i = 0; i < 4000; i++) {
            News a = BotWorldGossip.newsForTest(Kind.BOSS, "Josh", 42, "Zakum", null, now - TimeUnit.MINUTES.toMillis(10));
            News b = BotWorldGossip.newsForTest(Kind.BOSS, "Amy", 43, "Pap", null, now - TimeUnit.HOURS.toMillis(6));
            News n = BotWorldGossip.choose(List.of(a, b), now, rng);
            if (n == a) {
                fresh++;
            } else if (n == b) {
                older++;
            }
        }
        assertTrue(fresh > older * 3, fresh + " vs " + older);
        assertTrue(fresh + older > 0);
    }

    @Test
    void spokenLinesNameTheEventAndNeverSayWhatABotIs() {
        Pattern disclosure = Pattern.compile("(?i)\\b(ai|bot|bots|robot|npc|llm|assistant|program|script)\\b");
        Random rng = new Random(6);
        for (Kind kind : Kind.values()) {
            News n = BotWorldGossip.newsForTest(kind, "Josh", 42, kind == Kind.WEDDING ? "Amy" : "Zakum", "Zakum", 0);
            for (int i = 0; i < 60; i++) {
                String line = BotWorldGossip.line(n, i % 2 == 0, BotWorldGossip.ageText(i * 1_000_000L), rng);
                assertNotNull(line);
                // 1.6.0: an EVENT item names the event; its winner only in some lines ("remember the cake lol")
                assertTrue(line.contains(kind == Kind.EVENT ? "Zakum" : "Josh"), line);
                assertFalse(line.contains("{"), line);
                assertFalse(disclosure.matcher(line).find(), line);
            }
        }
    }
}
