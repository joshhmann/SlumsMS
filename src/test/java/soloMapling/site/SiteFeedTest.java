package soloMapling.site;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The website feed's rings: the bot-only drama rule, ordering, limits, expiry, text and restore after a restart. */
class SiteFeedTest {

    static final long T0 = 1_793_000_000_000L;
    static final int BOT = 1_000_500_000;
    static final int BOT2 = 1_000_500_001;
    static final int PLAYER = 42;

    final AtomicLong now = new AtomicLong(T0);

    @BeforeEach
    void setUp() {
        SiteFeed.resetForTest(now::get);
    }

    @AfterEach
    void tearDown() {
        SiteFeed.resetForTest(null);
    }

    @Test
    void dramaNeedsEveryPartyToBeABot() {
        assertTrue(SiteFeed.drama("rivalry", "A has a new nemesis: B.", new int[]{BOT, BOT2}, new String[]{"A", "B"}));
        assertFalse(SiteFeed.drama("rivalry", "A has a new nemesis: Josh.", new int[]{BOT, PLAYER}, new String[]{"A", "Josh"}));
        assertFalse(SiteFeed.drama("rage_quit", "Josh rage quit.", new int[]{PLAYER}, new String[]{"Josh"}));
        assertFalse(SiteFeed.drama("couple", "x", new int[]{BOT}, new String[]{"A", "B"}), "ids and names must pair up");
        assertFalse(SiteFeed.drama("couple", "x", new int[]{}, new String[]{}), "no party");
        assertFalse(SiteFeed.drama("couple", "x", new int[]{BOT, BOT2}, new String[]{"A", "#1000500001"}), "unresolved name");
        assertFalse(SiteFeed.guildDrama("guild_war", "A declared war on B.", new String[]{"A", "B"}, false),
                "a war on a player guild is never fed");
        assertTrue(SiteFeed.guildDrama("guild_war", "A declared war on B.", new String[]{"A", "B"}, true));
        List<SiteFeed.Item> items = SiteFeed.snapshot(now.get());
        assertEquals(2, items.size());
        assertTrue(items.stream().noneMatch(i -> i.names().contains("Josh")));
    }

    @Test
    void newestFirstAndAtMost200WithMegaphonesCapped() {
        for (int i = 0; i < 150; i++) {
            now.addAndGet(1000);
            SiteFeed.add(SiteFeed.Kind.MEGAPHONE, "megaphone", "selling stuff " + i, List.of("Seller"), null, 1);
        }
        for (int i = 0; i < 250; i++) {
            now.addAndGet(1000);
            SiteFeed.add(SiteFeed.Kind.MILESTONE, "level", "Bob reached level 70.", List.of("Bob"), null, null);
        }
        List<SiteFeed.Item> items = SiteFeed.snapshot(now.get());
        assertEquals(SiteFeed.LIMIT, items.size());
        for (int i = 1; i < items.size(); i++) {
            assertTrue(items.get(i - 1).t() >= items.get(i).t(), "newest first");
        }
        // the 250 milestones are newer than every megaphone: 200 milestones, no megaphone
        assertEquals(0, items.stream().filter(i -> i.kind() == SiteFeed.Kind.MEGAPHONE).count());

        SiteFeed.resetForTest(now::get);
        for (int i = 0; i < 150; i++) {
            now.addAndGet(1000);
            SiteFeed.add(SiteFeed.Kind.MEGAPHONE, "megaphone", "buying " + i, List.of("Buyer"), null, 1);
        }
        items = SiteFeed.snapshot(now.get());
        assertEquals(SiteFeed.MEGAPHONE_MAX, items.size(), "a megaphone storm keeps at most 80");
        assertEquals("buying 149", items.getFirst().text());
    }

    @Test
    void megaphonesExpireAfterADayEverythingElseAfterAWeek() {
        SiteFeed.add(SiteFeed.Kind.MEGAPHONE, "megaphone", "old smega", List.of("A"), null, 1);
        SiteFeed.add(SiteFeed.Kind.EVENT, "start", "Giant Cake began in Henesys (Ch 1).", List.of(), 100000000, 1);
        now.addAndGet(TimeUnit.HOURS.toMillis(25));
        List<SiteFeed.Item> items = SiteFeed.snapshot(now.get());
        assertEquals(1, items.size());
        assertEquals(SiteFeed.Kind.EVENT, items.getFirst().kind());
        now.addAndGet(TimeUnit.DAYS.toMillis(7));
        assertTrue(SiteFeed.snapshot(now.get()).isEmpty());
    }

    @Test
    void ringsAreBounded() {
        for (int i = 0; i < SiteFeed.RING + 50; i++) {
            SiteFeed.add(SiteFeed.Kind.DRAMA, "x", "line " + i, List.of(), null, null);
        }
        assertEquals(SiteFeed.LIMIT, SiteFeed.snapshot(now.get()).size());
    }

    @Test
    void nothingIsRecordedWhileTheExportIsOff() {
        SiteFeed.setRecording(false);
        SiteFeed.eventStarted("Giant Cake", "Henesys", 100000000, 1);
        SiteFeed.wedding("A", "B");
        assertFalse(SiteFeed.drama("couple", "A and B are a couple now.", new int[]{BOT, BOT2}, new String[]{"A", "B"}));
        assertTrue(SiteFeed.snapshot(now.get()).isEmpty());
    }

    @Test
    void eventLinesAndShape() {
        SiteFeed.eventStarted("Treasure Hunt", "Victoria Island", 0, 3);
        now.addAndGet(1000);
        SiteFeed.eventEnded("Giant Cake", "Henesys", 100000000, 2, "won", "Bobbins");
        now.addAndGet(1000);
        SiteFeed.eventEnded("Monster Invasion", "Ellinia", 101000000, 1, "lost", null);
        List<SiteFeed.Item> items = SiteFeed.snapshot(now.get());
        assertEquals("Monster Invasion in Ellinia ended with no winner.", items.get(0).text());
        assertEquals("lost", items.get(0).tag());
        assertEquals("Giant Cake in Henesys was won. Top damage: Bobbins.", items.get(1).text());
        assertEquals(List.of("Bobbins"), items.get(1).names());
        assertNull(items.get(2).map(), "a treasure hunt hides its map");

        Map<?, ?> file = (Map<?, ?>) Json.parse(Json.write(SiteFeed.file(items, now.get())));
        assertEquals(1L, ((Number) file.get("schema")).longValue());
        assertEquals(3L, ((Number) file.get("count")).longValue());
        Map<?, ?> first = (Map<?, ?>) ((List<?>) file.get("items")).getFirst();
        assertEquals(List.of("id", "t", "kind", "tag", "text", "names", "map", "channel"), List.copyOf(first.keySet()));
        assertEquals("event", first.get("kind"));
        assertTrue(((String) first.get("t")).endsWith("Z"));
        Map<?, ?> hunt = (Map<?, ?>) ((List<?>) file.get("items")).get(2);
        assertFalse(hunt.containsKey("map"), "no map key when there is none");
    }

    @Test
    void anEventCarriesItsTopThreeDamageDealers() {
        SiteFeed.eventEnded("Giant Cake", "Henesys", 100000000, 1, "won", "A", List.of(new SiteFeed.Top("A", 900),
                new SiteFeed.Top("B", 500), new SiteFeed.Top("C", 100), new SiteFeed.Top("D", 50)));
        Map<?, ?> file = (Map<?, ?>) Json.parse(Json.write(SiteFeed.file(SiteFeed.snapshot(now.get()), now.get())));
        Map<?, ?> item = (Map<?, ?>) ((List<?>) file.get("items")).getFirst();
        List<?> top = (List<?>) item.get("top");
        assertEquals(3, top.size());
        assertEquals("A", ((Map<?, ?>) top.getFirst()).get("name"));
        assertEquals(900L, ((Number) ((Map<?, ?>) top.getFirst()).get("damage")).longValue());
        SiteFeed.resetForTest(now::get);
        assertEquals(1, SiteFeed.seed(file));
        assertEquals(3, SiteFeed.snapshot(now.get()).getFirst().top().size(), "a restart keeps the top damage");
    }

    @Test
    void textIsPlainCappedAndPg13() {
        assertEquals("hi there", SiteFeed.plain("hi\u0000 ❤️ 😀 there"));
        assertTrue(SiteFeed.plain("x".repeat(500)).length() <= 160);
        assertNull(SiteFeed.megaphoneText("selling nudes 5m"));
        assertNull(SiteFeed.megaphoneText("   "));
        assertEquals("S> Red Potion 50 ea", SiteFeed.megaphoneText("S> Red Potion 50 ea 😀"));
    }

    @Test
    void whoIsPublic() {
        assertTrue(SiteFeed.isPublic("Bobbins", 0, false));
        assertFalse(SiteFeed.isPublic("Admin", 6, false), "GMs never appear");
        assertFalse(SiteFeed.isPublic("AutoQaOne", 0, false), "auto-QA characters never appear");
        assertFalse(SiteFeed.isPublic("Someone", 0, true), "pretend players never appear");
        assertFalse(SiteFeed.isPublic(" ", 0, false));
    }

    @Test
    void jobAdvancementsAndLevelMilestones() {
        assertEquals(2, SiteFeed.advancement(100, 110));
        assertEquals(3, SiteFeed.advancement(110, 111));
        assertEquals(4, SiteFeed.advancement(111, 112));
        assertEquals(2, SiteFeed.advancement(1100, 1110), "Cygnus");
        assertEquals(4, SiteFeed.advancement(2111, 2112), "Aran");
        assertEquals(0, SiteFeed.advancement(0, 100), "1st job is not a milestone");
        assertEquals(0, SiteFeed.advancement(112, 0), "rebirth back to beginner");
        assertEquals(0, SiteFeed.advancement(110, 121), "another line");
        assertEquals(0, SiteFeed.advancement(100, 111), "skipped a job");
        assertEquals(0, SiteFeed.advancement(2210, 2211), "Evan");
        assertEquals(List.of(70), SiteFeed.crossed(69, 70));
        assertEquals(List.of(70, 120), SiteFeed.crossed(60, 125));
        assertEquals(List.of(), SiteFeed.crossed(70, 71));
        assertEquals(List.of(200), SiteFeed.crossed(199, 200));
    }

    @Test
    void aRestartGetsTheLastFileBack() {
        SiteFeed.add(SiteFeed.Kind.EVENT, "start", "Giant Cake began in Henesys (Ch 1).", List.of(), 100000000, 1);
        now.addAndGet(1000);
        SiteFeed.add(SiteFeed.Kind.MILESTONE, "wedding", "A and B got married.", List.of("A", "B"), null, null);
        Object doc = Json.parse(Json.write(SiteFeed.file(SiteFeed.snapshot(now.get()), now.get())));

        SiteFeed.resetForTest(now::get);
        now.addAndGet(1000);
        SiteFeed.add(SiteFeed.Kind.DRAMA, "couple", "C and D are a couple now.", List.of("C", "D"), null, null);
        assertEquals(2, SiteFeed.seed(doc));
        assertEquals(0, SiteFeed.seed(doc), "nothing twice");
        List<SiteFeed.Item> items = SiteFeed.snapshot(now.get());
        assertEquals(List.of("C and D are a couple now.", "A and B got married.", "Giant Cake began in Henesys (Ch 1)."),
                items.stream().map(SiteFeed.Item::text).toList());
        assertEquals(100000000, items.get(2).map());
        assertEquals(0, SiteFeed.seed(Map.of("items", List.of(Map.of("kind", "nope")))));
    }
}
