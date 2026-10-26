package soloMapling.site;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.0 Track D: {@code leaderboards.json}. Pure: {@link PublicSiteExport} reads the rows off the game thread and hands
 * them in. Bots and players are one server here: one list, no bot flag (owner, 2026-10-26). GM/staff accounts, the
 * auto-QA accounts, banned accounts and the bot clone template never appear. Names only: no ids, no account data.
 */
public final class SiteLeaderboards {

    public static final int LEVEL_TOP = 100;
    public static final int BRANCH_TOP = 20;
    public static final int FAME_TOP = 50;
    public static final int GUILD_TOP = 30;
    public static final int TRADER_TOP = 50;
    /** Trades (completed + fell through) a character needs to be on the traders board. */
    public static final int TRADER_MIN = 5;
    public static final int BOSS_TOP = 20;
    public static final List<String> BRANCHES = List.of("beginner", "warrior", "magician", "bowman", "thief", "pirate");
    /** The expedition bosses on the board, as {@code bosslog_leaderboard_*.bosstype}. */
    public static final List<String> BOSSES = List.of("ZAKUM", "HORNTAIL", "PINKBEAN");

    private SiteLeaderboards() {
    }

    /** One character on the boards. {@code id}: only to join rows here; never written. */
    public record Person(int id, String name, int level, long exp, int job, int rebirths, int fame,
                         int guildId) {
    }

    /** A player character as the database has it. */
    public record PlayerRow(int id, int accountId, String name, int level, long exp, int job, int rebirths, int fame,
                            int guildId, int gm, String accountName,
                            boolean banned) {
    }

    /** A bot as {@code botstore} has it (fame is derived from id and level, as in game). */
    public record BotRow(int charId, String name, int level, long exp, int job, int fame, int guildId) {
    }

    public record GuildRow(int id, String name, long points, int capacity, int leaderId) {
    }

    /** One character's clears of one boss: {@code fastestMs} 0 when no time was recorded. */
    public record BossRow(int charId, String boss, int clears, long fastestMs) {
    }

    // ── who is on the boards ────────────────────────────────────────────────────────────────────

    /**
     * The players who may be ranked: no character of an account that has any GM character (gm > 0), no auto-QA
     * account or character, no banned account, not the bot clone template. Pure.
     */
    public static List<Person> players(List<PlayerRow> rows, int templateId) {
        Set<Integer> staff = new HashSet<>();
        for (PlayerRow r : rows) {
            if (r.gm() > 0) {
                staff.add(r.accountId());
            }
        }
        List<Person> out = new ArrayList<>();
        for (PlayerRow r : rows) {
            if (staff.contains(r.accountId()) || r.banned() || r.id() == templateId || r.name() == null
                    || r.name().isBlank() || SiteFeed.isQaName(r.name()) || SiteFeed.isQaName(r.accountName())) {
                continue;
            }
            out.add(new Person(r.id(), r.name(), r.level(), r.exp(), r.job(), r.rebirths(), r.fame(), r.guildId()));
        }
        return out;
    }

    public static List<Person> bots(List<BotRow> rows) {
        List<Person> out = new ArrayList<>();
        for (BotRow r : rows) {
            if (r.name() != null && !r.name().isBlank()) {
                out.add(new Person(r.charId(), r.name(), r.level(), r.exp(), r.job(), 0, r.fame(), r.guildId()));
            }
        }
        return out;
    }

    // ── the file ────────────────────────────────────────────────────────────────────────────────

    /** The class names {@code levelByClass} is keyed by, in branch order (Cygnus, Aran and Evan by archetype). */
    public static final List<String> CLASSES = List.of("Beginner", "Warrior", "Magician", "Bowman", "Thief", "Pirate");
    /** The boss names {@code bosses} is keyed by, for {@link #BOSSES}. */
    public static final List<String> BOSS_NAMES = List.of("Zakum", "Horntail", "Pink Bean");

    /**
     * leaderboards.json, in the field names the site's pages read (site session, 2026-10-26).
     * {@code everyone}: players (already filtered by {@link #players}) and bots. {@code excludedIds}: characters that
     * must not lead a listed guild (staff, QA, template). {@code bosses} null: the boss tables could not be read
     * (every boss list is empty then, as when nobody has cleared it). {@code trades}: the stored trade ratings (null:
     * unreadable, e.g. no migration 035 yet; the list is empty then).
     */
    public static Map<String, Object> file(List<Person> everyone, List<GuildRow> guilds, Map<Integer, Integer> members,
                                           Set<Integer> excludedIds, List<BossRow> bosses,
                                           List<TradeRatings.Row> trades, long now) {
        Map<Integer, Person> byId = new HashMap<>();
        for (Person p : everyone) {
            byId.put(p.id(), p);
        }
        Map<Integer, String> guildNames = new HashMap<>();
        List<GuildRow> listed = new ArrayList<>();
        for (GuildRow g : guilds) {
            if (g.name() == null || g.name().isBlank() || SiteFeed.isQaName(g.name()) || excludedIds.contains(g.leaderId())) {
                continue;
            }
            listed.add(g);
            guildNames.put(g.id(), g.name());
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", PublicSiteExport.SCHEMA);
        m.put("generated_at", java.time.Instant.ofEpochMilli(now).toString());

        List<Person> byLevel = new ArrayList<>(everyone);
        byLevel.sort(LEVEL_ORDER);
        m.put("level", levelRows(byLevel, LEVEL_TOP, guildNames));
        Map<String, Object> classes = new LinkedHashMap<>();
        for (int b = 0; b < BRANCHES.size(); b++) {
            String branch = BRANCHES.get(b);
            classes.put(CLASSES.get(b), levelRows(byLevel.stream().filter(p -> branch.equals(branch(p.job()))).toList(),
                    BRANCH_TOP, guildNames));
        }
        m.put("levelByClass", classes);

        List<Person> byFame = new ArrayList<>(everyone);
        byFame.sort(Comparator.comparingInt(Person::fame).reversed().thenComparing(LEVEL_ORDER));
        List<Object> fame = new ArrayList<>();
        for (Person p : byFame.subList(0, Math.min(FAME_TOP, byFame.size()))) {
            Map<String, Object> r = row(fame.size() + 1, p);
            r.put("fame", p.fame());
            r.put("job", jobName(p.job()));
            fame.add(r);
        }
        m.put("fame", fame);

        List<GuildRow> sorted = new ArrayList<>(listed);
        sorted.sort(Comparator.comparingLong(GuildRow::points).reversed()
                .thenComparing(x -> -members.getOrDefault(x.id(), 0)).thenComparing(GuildRow::name));
        List<Object> g = new ArrayList<>();
        for (GuildRow x : sorted.subList(0, Math.min(GUILD_TOP, sorted.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rank", g.size() + 1);
            r.put("name", x.name());
            r.put("level", null); // v83 guilds have no level; GP is their ranking (points)
            r.put("members", members.getOrDefault(x.id(), 0));
            r.put("points", x.points());
            Person leader = byId.get(x.leaderId());
            r.put("leader", leader == null ? null : leader.name());
            g.add(r);
        }
        m.put("guilds", g);

        // Trade ratings (owner 2026-10-26, replacing "richest"): at least TRADER_MIN trades, most completed first,
        // then fewer fell through, then name.
        List<TradeRatings.Row> rated = new ArrayList<>();
        if (trades != null) {
            for (TradeRatings.Row t : trades) {
                if (byId.containsKey(t.charId()) && t.completed() + t.fellThrough() >= TRADER_MIN) {
                    rated.add(t);
                }
            }
        }
        rated.sort(Comparator.comparingLong(TradeRatings.Row::completed).reversed()
                .thenComparingLong(TradeRatings.Row::fellThrough).thenComparing(t -> byId.get(t.charId()).name()));
        List<Object> traders = new ArrayList<>();
        for (TradeRatings.Row t : rated.subList(0, Math.min(TRADER_TOP, rated.size()))) {
            Map<String, Object> r = row(traders.size() + 1, byId.get(t.charId()));
            r.put("completed", t.completed());
            r.put("fellThrough", t.fellThrough());
            traders.add(r);
        }
        m.put("traders", traders);

        // The server keeps no Mu Lung Dojo best floor or time (lastDojoStage is a resume slot, dojoPoints a currency):
        // rows {name, floor, points, time_s} once it does.
        m.put("dojo", List.of());

        Map<String, Object> bossOut = new LinkedHashMap<>();
        for (int k = 0; k < BOSSES.size(); k++) {
            String boss = BOSSES.get(k);
            List<Object> entries = new ArrayList<>();
            if (bosses != null) {
                List<BossRow> rows = new ArrayList<>();
                for (BossRow r : bosses) {
                    if (boss.equalsIgnoreCase(r.boss()) && byId.containsKey(r.charId()) && r.clears() > 0) {
                        rows.add(r);
                    }
                }
                rows.sort(Comparator.comparingInt(BossRow::clears).reversed()
                        .thenComparingLong(r -> r.fastestMs() > 0 ? r.fastestMs() : Long.MAX_VALUE)
                        .thenComparing(r -> byId.get(r.charId()).name()));
                for (BossRow r : rows.subList(0, Math.min(BOSS_TOP, rows.size()))) {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("rank", entries.size() + 1);
                    e.put("name", byId.get(r.charId()).name());
                    e.put("clears", r.clears());
                    entries.add(e);
                }
            }
            bossOut.put(BOSS_NAMES.get(k), entries);
        }
        m.put("bosses", bossOut);
        return m;
    }

    /** Rebirths first, then level, then EXP; ties by name. */
    static final Comparator<Person> LEVEL_ORDER = Comparator.comparingInt(Person::rebirths).reversed()
            .thenComparing(Comparator.comparingInt(Person::level).reversed())
            .thenComparing(Comparator.comparingLong(Person::exp).reversed())
            .thenComparing(Person::name);

    private static List<Object> levelRows(List<Person> sorted, int top, Map<Integer, String> guildNames) {
        List<Object> out = new ArrayList<>();
        for (Person p : sorted.subList(0, Math.min(top, sorted.size()))) {
            Map<String, Object> r = row(out.size() + 1, p);
            r.put("level", p.level());
            r.put("rebirths", p.rebirths());
            r.put("job", jobName(p.job()));
            r.put("guild", guildNames.get(p.guildId()));
            out.add(r);
        }
        return out;
    }

    private static Map<String, Object> row(int rank, Person p) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("rank", rank);
        r.put("name", p.name());
        return r;
    }

    /** beginner, warrior, magician, bowman, thief, pirate (Cygnus, Aran and Evan by their archetype), else other. */
    static String branch(int job) {
        int b = (job % 1000) / 100;
        return b < BRANCHES.size() ? BRANCHES.get(b) : "other";
    }

    /**
     * The job as players say it ("Arch Mage (F/P)", "Night Lord", "Dawn Warrior"): the site contract asks for display
     * names, and the enum names TEST sent ("Fp_archmage", "Darkknight") were not (site session, 2026-10-26).
     */
    static String jobName(int job) {
        String n = DISPLAY.get(job);
        if (n != null) {
            return n;
        }
        int cygnus = job / 100 * 100; // Cygnus and Aran keep one name across their advancements (1110, 1111, 2112, ...)
        n = (job >= 1000 && job < 3000) ? DISPLAY.get(cygnus) : null;
        return n == null ? "" : n;
    }

    private static final Map<Integer, String> DISPLAY = Map.ofEntries(
            Map.entry(0, "Beginner"), Map.entry(100, "Warrior"), Map.entry(110, "Fighter"), Map.entry(111, "Crusader"),
            Map.entry(112, "Hero"), Map.entry(120, "Page"), Map.entry(121, "White Knight"), Map.entry(122, "Paladin"),
            Map.entry(130, "Spearman"), Map.entry(131, "Dragon Knight"), Map.entry(132, "Dark Knight"),
            Map.entry(200, "Magician"), Map.entry(210, "Wizard (F/P)"), Map.entry(211, "Mage (F/P)"),
            Map.entry(212, "Arch Mage (F/P)"), Map.entry(220, "Wizard (I/L)"), Map.entry(221, "Mage (I/L)"),
            Map.entry(222, "Arch Mage (I/L)"), Map.entry(230, "Cleric"), Map.entry(231, "Priest"),
            Map.entry(232, "Bishop"), Map.entry(300, "Bowman"), Map.entry(310, "Hunter"), Map.entry(311, "Ranger"),
            Map.entry(312, "Bowmaster"), Map.entry(320, "Crossbowman"), Map.entry(321, "Sniper"),
            Map.entry(322, "Marksman"), Map.entry(400, "Thief"), Map.entry(410, "Assassin"), Map.entry(411, "Hermit"),
            Map.entry(412, "Night Lord"), Map.entry(420, "Bandit"), Map.entry(421, "Chief Bandit"),
            Map.entry(422, "Shadower"), Map.entry(500, "Pirate"), Map.entry(510, "Brawler"), Map.entry(511, "Marauder"),
            Map.entry(512, "Buccaneer"), Map.entry(520, "Gunslinger"), Map.entry(521, "Outlaw"),
            Map.entry(522, "Corsair"), Map.entry(1000, "Noblesse"), Map.entry(1100, "Dawn Warrior"),
            Map.entry(1200, "Blaze Wizard"), Map.entry(1300, "Wind Archer"), Map.entry(1400, "Night Walker"),
            Map.entry(1500, "Thunder Breaker"), Map.entry(2000, "Legend"), Map.entry(2100, "Aran"));
}
