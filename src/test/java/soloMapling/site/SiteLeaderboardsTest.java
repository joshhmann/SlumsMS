package soloMapling.site;

import org.junit.jupiter.api.Test;
import soloMapling.server.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** leaderboards.json: who is left out, ordering, the limits, guilds, richest, bosses, and no ids. */
class SiteLeaderboardsTest {

    static final long NOW = 1_793_000_000_000L;

    static SiteLeaderboards.PlayerRow player(int id, int account, String name, int level, int gm, String accountName) {
        return new SiteLeaderboards.PlayerRow(id, account, name, level, 0, 110, 0, 5, 1000, 0, 0, 0, gm, accountName, false);
    }

    static SiteLeaderboards.Person person(int id, String name, int level, long exp, int job, int rebirths, int fame,
                                          long mesos, int guild) {
        return new SiteLeaderboards.Person(id, name, level, exp, job, rebirths, fame, mesos, guild);
    }

    @Test
    void staffQaBannedAndTheTemplateAreLeftOut() {
        List<SiteLeaderboards.PlayerRow> rows = List.of(
                player(1, 10, "Admin", 200, 6, "admin"),
                player(2, 10, "AdminsAlt", 120, 0, "admin"),          // same account as a GM: staff
                player(3, 11, "AutoQaOne", 70, 0, "autoqa1"),
                player(4, 12, "AqClient", 200, 0, "autoqaclient"),     // a QA account, whatever the name
                player(5, 13, "SMTemplate", 1, 0, "SMBotBase"),        // the bot clone template
                new SiteLeaderboards.PlayerRow(6, 14, "Banned", 90, 0, 110, 0, 0, 0, 0, 0, 0, 0, "cheater", true),
                player(7, 15, "Josh", 30, 0, "josh"));
        List<SiteLeaderboards.Person> kept = SiteLeaderboards.players(rows, Map.of(), 5);
        assertEquals(List.of("Josh"), kept.stream().map(SiteLeaderboards.Person::name).toList());
    }

    @Test
    void storageCountsOncePerAccountWithItsRichestCharacter() {
        List<SiteLeaderboards.PlayerRow> rows = List.of(
                new SiteLeaderboards.PlayerRow(1, 10, "Main", 80, 0, 110, 0, 0, 5_000, 2_000, 1_000, 0, 0, "josh", false),
                new SiteLeaderboards.PlayerRow(2, 10, "Mule", 10, 0, 0, 0, 0, 100, 0, 0, 0, 0, "josh", false));
        List<SiteLeaderboards.Person> kept = SiteLeaderboards.players(rows, Map.of(10, 1_000_000L), 0);
        assertEquals(1_008_000L, kept.get(0).mesos(), "carried + merchant + bank + the account's storage");
        assertEquals(100L, kept.get(1).mesos(), "the storage is not counted twice");
    }

    @Test
    void levelOrderLimitsAndBranches() {
        List<SiteLeaderboards.Person> all = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            all.add(person(1_000_000_100 + i, "Bot" + i, 10 + i % 100, i, 100 * (1 + i % 5) + 10, 0, i % 60, i * 1000L, 0));
        }
        all.add(person(7, "Reborn", 50, 0, 0, 2, 0, 0, 0));        // a rebirth beats any level
        all.add(person(8, "Beginner", 8, 0, 0, 0, 0, 0, 0));
        Map<?, ?> f = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), List.of(), NOW));
        Map<?, ?> level = (Map<?, ?>) f.get("level");
        List<?> overall = (List<?>) level.get("overall");
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
        Map<?, ?> branches = (Map<?, ?>) level.get("by_branch");
        assertEquals(SiteLeaderboards.BRANCHES, branches.keySet().stream().map(String::valueOf).toList());
        for (String b : List.of("warrior", "magician", "bowman", "thief", "pirate")) {
            List<?> rows = (List<?>) branches.get(b);
            assertEquals(SiteLeaderboards.BRANCH_TOP, rows.size(), b);
            assertTrue(rows.stream().allMatch(r -> b.equals(((Map<?, ?>) r).get("branch"))), b);
        }
        assertEquals(2, ((List<?>) branches.get("beginner")).size());
        assertEquals(SiteLeaderboards.FAME_TOP, ((List<?>) f.get("fame")).size());
        assertEquals(59L, ((Number) ((Map<?, ?>) ((List<?>) f.get("fame")).getFirst()).get("fame")).longValue());
        List<?> rich = (List<?>) f.get("richest");
        assertEquals(SiteLeaderboards.RICH_TOP, rich.size());
        assertEquals("Bot149", ((Map<?, ?>) rich.getFirst()).get("name"));
        assertEquals(149_000L, ((Number) ((Map<?, ?>) rich.getFirst()).get("mesos")).longValue());
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
        Map<?, ?> f = parse(SiteLeaderboards.file(all, guilds, Map.of(1, 20, 2, 35, 3, 1), Set.of(1), List.of(), NOW));
        Map<?, ?> g = (Map<?, ?>) f.get("guilds");
        assertEquals(List.of("Legendz", "MoonLiteX"), names(g.get("by_points")));
        assertEquals(List.of("MoonLiteX", "Legendz"), names(g.get("by_members")));
        Map<?, ?> top = (Map<?, ?>) ((List<?>) g.get("by_points")).getFirst();
        assertEquals("Leader", top.get("leader"));
        assertEquals(20L, ((Number) top.get("members")).longValue());
        Map<?, ?> overall = (Map<?, ?>) ((List<?>) ((Map<?, ?>) f.get("level")).get("overall")).getFirst();
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
        Map<?, ?> f = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), bosses, NOW));
        Map<?, ?> b = (Map<?, ?>) f.get("bosses");
        assertEquals(List.of("zakum", "horntail", "pinkbean"), b.keySet().stream().map(String::valueOf).toList());
        Map<?, ?> zakum = (Map<?, ?>) b.get("zakum");
        assertEquals(true, zakum.get("recorded"));
        assertEquals(List.of("Kim", "Josh"), names(zakum.get("entries")), "same clears: faster first");
        Map<?, ?> ht = (Map<?, ?>) ((List<?>) ((Map<?, ?>) b.get("horntail")).get("entries")).getFirst();
        assertNull(ht.get("fastest_ms"));
        assertEquals(false, ((Map<?, ?>) f.get("dojo")).get("recorded"));

        Map<?, ?> unreadable = parse(SiteLeaderboards.file(all, List.of(), Map.of(), Set.of(), null, NOW));
        assertEquals(false, ((Map<?, ?>) ((Map<?, ?>) unreadable.get("bosses")).get("zakum")).get("recorded"));
    }

    @Test
    void noIdsAndNoBotFlagAnywhere() {
        List<SiteLeaderboards.Person> all = List.of(person(1_000_000_101, "ABot", 100, 0, 110, 0, 3, 50, 1),
                person(7, "APlayer", 100, 0, 110, 0, 3, 50, 1));
        String json = Json.write(SiteLeaderboards.file(all, List.of(new SiteLeaderboards.GuildRow(1, "G", 1, 10, 7)),
                Map.of(1, 2), Set.of(), List.of(), NOW));
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
