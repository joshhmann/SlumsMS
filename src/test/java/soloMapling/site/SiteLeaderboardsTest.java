package soloMapling.site;

import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** leaderboards.json: who is left out, ordering, the limits, guilds, traders, bosses, and no ids. */
class SiteLeaderboardsTest {

    static final long NOW = 1_793_000_000_000L;

    static SiteLeaderboards.PlayerRow player(int id, int account, String name, int level, int gm, String accountName) {
        return new SiteLeaderboards.PlayerRow(id, account, name, level, 0, 110, 0, 5, 0, gm, accountName, false);
    }

    static SiteLeaderboards.Person person(int id, String name, int level, long exp, int job, int rebirths, int fame,
                                          long unused, int guild) {
        return new SiteLeaderboards.Person(id, name, level, exp, job, rebirths, fame, guild);
    }

    @Test
    void staffQaBannedAndTheTemplateAreLeftOut() {
        List<SiteLeaderboards.PlayerRow> rows = List.of(
                player(1, 10, "Admin", 200, 6, "admin"),
                player(2, 10, "AdminsAlt", 120, 0, "admin"),          // same account as a GM: staff
                player(3, 11, "AutoQaOne", 70, 0, "autoqa1"),
                player(4, 12, "AqClient", 200, 0, "autoqaclient"),     // a QA account, whatever the name
                player(5, 13, "SMTemplate", 1, 0, "SMBotBase"),        // the bot clone template
                new SiteLeaderboards.PlayerRow(6, 14, "Banned", 90, 0, 110, 0, 0, 0, 0, "cheater", true),
                player(7, 15, "Josh", 30, 0, "josh"));
        List<SiteLeaderboards.Person> kept = SiteLeaderboards.players(rows, 5);
        assertEquals(List.of("Josh"), kept.stream().map(SiteLeaderboards.Person::name).toList());
    }

    @Test
    void tradersNeedFiveTradesAndRankByCompleted() {
        List<SiteLeaderboards.Person> all = new ArrayList<>();
        List<TradeRatings.Row> trades = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            all.add(person(1_000_000_100 + i, "Bot" + i, 50, 0, 110, 0, 0, 0, 0));
            trades.add(new TradeRatings.Row(1_000_000_100 + i, 10 + i, i % 3));
        }
        all.add(person(7, "Josh", 50, 0, 110, 0, 0, 0, 0));
        trades.add(new TradeRatings.Row(7, 2, 3));              // 5 trades, 2 completed: listed
        all.add(person(8, "Kim", 50, 0, 110, 0, 0, 0, 0));
        trades.add(new TradeRatings.Row(8, 3, 1));              // 4 trades: not listed
        trades.add(new TradeRatings.Row(1, 500, 0));            // a GM (not on the boards): never listed
        Map<?, ?> f = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), List.of(), trades, NOW));
        List<?> traders = (List<?>) f.get("traders");
        assertEquals(SiteLeaderboards.TRADER_TOP, traders.size());
        Map<?, ?> first = (Map<?, ?>) traders.getFirst();
        assertEquals(Set.of("rank", "name", "completed", "fellThrough"), first.keySet());
        assertEquals("Bot59", first.get("name"));
        assertEquals(69L, ((Number) first.get("completed")).longValue());
        assertFalse(f.containsKey("richest"), "the richest board is gone (owner 2026-10-26)");

        Map<?, ?> few = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), List.of(),
                List.of(new TradeRatings.Row(7, 2, 3), new TradeRatings.Row(8, 3, 1)), NOW));
        assertEquals(List.of("Josh"), names(few.get("traders")));
        Map<?, ?> unreadable = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), List.of(), null, NOW));
        assertEquals(List.of(), unreadable.get("traders"));
    }

    @Test
    void levelOrderLimitsAndBranches() {
        List<SiteLeaderboards.Person> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            all.add(person(1_000_000_100 + i, "Bot" + i, 10 + i % 100, i, 100 * (1 + i % 5) + 10, 0, i % 60, i * 1000L, 0));
        }
        all.add(person(7, "Reborn", 50, 0, 0, 2, 0, 0, 0));        // a rebirth beats any level
        all.add(person(8, "Beginner", 8, 0, 0, 0, 0, 0, 0));
        Map<?, ?> f = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), List.of(), List.of(), NOW));
        List<?> overall = (List<?>) f.get("level");
        assertEquals(SiteLeaderboards.LEVEL_TOP, overall.size());
        Map<?, ?> first = (Map<?, ?>) overall.getFirst();
        assertEquals("Reborn", first.get("name"));
        assertEquals(1L, ((Number) first.get("rank")).longValue());
        assertEquals(2L, ((Number) first.get("rebirths")).longValue());
        long prev = Long.MAX_VALUE;
        for (Object o : overall.subList(1, overall.size())) {
            long l = ((Number) ((Map<?, ?>) o).get("level")).longValue();
            assertTrue(l <= prev, "level order");
            prev = l;
        }
        assertEquals(Set.of("rank", "name", "level", "rebirths", "job", "guild"), ((Map<?, ?>) overall.get(1)).keySet());
        Map<?, ?> classes = (Map<?, ?>) f.get("levelByClass");
        assertEquals(SiteLeaderboards.CLASSES, classes.keySet().stream().map(String::valueOf).toList());
        for (String c : List.of("Warrior", "Magician", "Bowman", "Thief", "Pirate")) {
            List<?> rows = (List<?>) classes.get(c);
            assertEquals(SiteLeaderboards.BRANCH_TOP, rows.size(), c);
        }
        assertTrue(((List<?>) classes.get("Warrior")).stream()
                .allMatch(r -> SiteLeaderboards.jobName(110).equals(((Map<?, ?>) r).get("job"))));
        assertEquals(2, ((List<?>) classes.get("Beginner")).size());
        assertEquals(SiteLeaderboards.FAME_TOP, ((List<?>) f.get("fame")).size());
        assertEquals(59L, ((Number) ((Map<?, ?>) ((List<?>) f.get("fame")).getFirst()).get("fame")).longValue());
        assertEquals(Set.of("rank", "name", "fame", "job"), ((Map<?, ?>) ((List<?>) f.get("fame")).getFirst()).keySet());
    }

    @Test
    void guildsLeaveOutQaAndStaffLedOnesAndRankBothWays() {
        List<SiteLeaderboards.Person> all = List.of(person(1_000_000_101, "Leader", 100, 0, 110, 0, 0, 0, 1),
                person(1_000_000_102, "Member", 90, 0, 110, 0, 0, 0, 2));
        List<SiteLeaderboards.GuildRow> guilds = List.of(
                new SiteLeaderboards.GuildRow(1, "Legendz", 2290, 50, 1_000_000_101),
                new SiteLeaderboards.GuildRow(2, "MoonLiteX", 1760, 45, 1_000_000_102),
                new SiteLeaderboards.GuildRow(3, "jimbobs", 9999, 10, 1),          // led by a GM
                new SiteLeaderboards.GuildRow(4, "AutoQa8538", 0, 10, 44));
        Map<?, ?> f = parse(SiteLeaderboards.file(all, guilds, Map.of(1, 20, 2, 35, 3, 1), Set.of(1), List.of(), List.of(), NOW));
        assertEquals(List.of("Legendz", "MoonLiteX"), names(f.get("guilds")));
        Map<?, ?> top = (Map<?, ?>) ((List<?>) f.get("guilds")).getFirst();
        assertEquals(Set.of("rank", "name", "level", "members", "points", "leader"), top.keySet());
        assertEquals("Leader", top.get("leader"));
        assertEquals(20L, ((Number) top.get("members")).longValue());
        assertEquals(2290L, ((Number) top.get("points")).longValue());
        assertNull(top.get("level"), "v83 guilds have no level");
        Map<?, ?> overall = (Map<?, ?>) ((List<?>) f.get("level")).getFirst();
        assertEquals("Legendz", overall.get("guild"));
    }

    @Test
    void bossesAndDojo() {
        List<SiteLeaderboards.Person> all = List.of(person(7, "Josh", 120, 0, 112, 0, 0, 0, 0),
                person(8, "Kim", 120, 0, 112, 0, 0, 0, 0));
        List<SiteLeaderboards.BossRow> bosses = List.of(new SiteLeaderboards.BossRow(7, "ZAKUM", 3, 600_000),
                new SiteLeaderboards.BossRow(8, "ZAKUM", 3, 500_000),
                new SiteLeaderboards.BossRow(99, "ZAKUM", 9, 1), // not on the boards (staff): left out
                new SiteLeaderboards.BossRow(7, "HORNTAIL", 1, 0));
        Map<?, ?> f = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), bosses, List.of(), NOW));
        Map<?, ?> b = (Map<?, ?>) f.get("bosses");
        assertEquals(List.of("Zakum", "Horntail", "Pink Bean"), b.keySet().stream().map(String::valueOf).toList());
        assertEquals(List.of("Kim", "Josh"), names(b.get("Zakum")), "same clears: faster first");
        Map<?, ?> ht = (Map<?, ?>) ((List<?>) b.get("Horntail")).getFirst();
        assertEquals(Set.of("rank", "name", "clears"), ht.keySet());
        assertEquals(List.of(), f.get("dojo"));

        Map<?, ?> unreadable = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), null, List.of(), NOW));
        assertEquals(List.of(), ((Map<?, ?>) unreadable.get("bosses")).get("Zakum"));
    }

    @Test
    void noIdsAndNoBotFlagAnywhere() {
        List<SiteLeaderboards.Person> all = List.of(person(1_000_000_101, "ABot", 100, 0, 110, 0, 3, 50, 1),
                person(7, "APlayer", 100, 0, 110, 0, 3, 50, 1));
        String json = Json.write(SiteLeaderboards.file(all, List.of(new SiteLeaderboards.GuildRow(1, "G", 1, 10, 7)),
                Map.of(1, 2), Set.of(), List.of(), List.of(), NOW));
        assertFalse(json.contains("1000000101"), json);
        assertFalse(json.contains("\"id\""), json);
        assertFalse(json.contains("\"bot\":") || json.contains("\"is_bot\"") || json.contains("\"kind\""), json);
        assertTrue(json.startsWith("{\"schema\":1,\"generated_at\":\""), json);
    }

    private static Map<?, ?> parse(Map<String, Object> doc) {
        return (Map<?, ?>) Json.parse(Json.write(doc));
    }

    private static List<String> names(Object rows) {
        return ((List<?>) rows).stream().map(r -> String.valueOf(((Map<?, ?>) r).get("name"))).toList();
    }
}
