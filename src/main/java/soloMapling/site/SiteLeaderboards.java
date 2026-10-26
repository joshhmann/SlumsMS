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
    public static final int RICH_TOP = 10;
    public static final int BOSS_TOP = 20;
    public static final List<String> BRANCHES = List.of("beginner", "warrior", "magician", "bowman", "thief", "pirate");
    /** The expedition bosses on the board, as {@code bosslog_leaderboard_*.bosstype}. */
    public static final List<String> BOSSES = List.of("ZAKUM", "HORNTAIL", "PINKBEAN");

    private SiteLeaderboards() {
    }

    /** One character on the boards. {@code id}: only to join rows here; never written. */
    public record Person(int id, String name, int level, long exp, int job, int rebirths, int fame, long mesos,
                         int guildId) {
    }

    /** A player character as the database has it. */
    public record PlayerRow(int id, int accountId, String name, int level, long exp, int job, int rebirths, int fame,
                            long meso, long merchantMeso, long bank, int guildId, int gm, String accountName,
                            boolean banned) {
    }

    /** A bot as {@code botstore} has it (fame is derived from id and level, as in game). */
    public record BotRow(int charId, String name, int level, long exp, int job, long meso, int fame, int guildId) {
    }

    public record GuildRow(int id, String name, long points, int capacity, int leaderId) {
    }

    /** One character's clears of one boss: {@code fastestMs} 0 when no time was recorded. */
    public record BossRow(int charId, String boss, int clears, long fastestMs) {
    }

    // ── who is on the boards ────────────────────────────────────────────────────────────────────

    /**
     * The players who may be ranked: no character of an account that has any GM character (gm > 0), no auto-QA
     * account or character, no banned account, not the bot clone template. Each account's storage mesos (and nothing
     * twice) go to that account's richest remaining character. Pure.
     */
    public static List<Person> players(List<PlayerRow> rows, Map<Integer, Long> storageByAccount, int templateId) {
        Set<Integer> staff = new HashSet<>();
        for (PlayerRow r : rows) {
            if (r.gm() > 0) {
                staff.add(r.accountId());
            }
        }
        List<PlayerRow> kept = new ArrayList<>();
        for (PlayerRow r : rows) {
            if (staff.contains(r.accountId()) || r.banned() || r.id() == templateId || r.name() == null
                    || r.name().isBlank() || SiteFeed.isQaName(r.name()) || SiteFeed.isQaName(r.accountName())) {
                continue;
            }
            kept.add(r);
        }
        Map<Integer, Integer> richestOfAccount = new HashMap<>();
        Map<Integer, Long> best = new HashMap<>();
        for (PlayerRow r : kept) {
            long own = own(r);
            Long b = best.get(r.accountId());
            if (b == null || own > b || (own == b && r.id() < richestOfAccount.get(r.accountId()))) {
                best.put(r.accountId(), own);
                richestOfAccount.put(r.accountId(), r.id());
            }
        }
        List<Person> out = new ArrayList<>();
        for (PlayerRow r : kept) {
            long mesos = own(r);
            if (richestOfAccount.get(r.accountId()) == r.id()) {
                mesos += Math.max(0, storageByAccount.getOrDefault(r.accountId(), 0L));
            }
            out.add(new Person(r.id(), r.name(), r.level(), r.exp(), r.job(), r.rebirths(), r.fame(), mesos, r.guildId()));
        }
        return out;
    }

    /** A character's own mesos: carried, waiting at Fredrick (hired merchant) and in the bank. */
    static long own(PlayerRow r) {
        return Math.max(0, r.meso()) + Math.max(0, r.merchantMeso()) + Math.max(0, r.bank());
    }

    public static List<Person> bots(List<BotRow> rows) {
        List<Person> out = new ArrayList<>();
        for (BotRow r : rows) {
            if (r.name() != null && !r.name().isBlank()) {
                out.add(new Person(r.charId(), r.name(), r.level(), r.exp(), r.job(), 0, r.fame(), Math.max(0, r.meso()),
                        r.guildId()));
            }
        }
        return out;
    }

    // ── the file ────────────────────────────────────────────────────────────────────────────────

    /**
     * leaderboards.json. {@code everyone}: players (already filtered by {@link #players}) and bots.
     * {@code excludedIds}: characters that must not lead a listed guild (staff, QA, template).
     * {@code bosses} null: the boss tables could not be read.
     */
    public static Map<String, Object> file(List<Person> everyone, List<GuildRow> guilds, Map<Integer, Integer> members,
                                           Set<Integer> excludedIds, List<BossRow> bosses, long now) {
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
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("overall", levelRows(byLevel, LEVEL_TOP, guildNames));
        Map<String, Object> branches = new LinkedHashMap<>();
        for (String b : BRANCHES) {
            branches.put(b, levelRows(byLevel.stream().filter(p -> b.equals(branch(p.job()))).toList(), BRANCH_TOP,
                    guildNames));
        }
        level.put("by_branch", branches);
        m.put("level", level);

        List<Person> byFame = new ArrayList<>(everyone);
        byFame.sort(Comparator.comparingInt(Person::fame).reversed().thenComparing(LEVEL_ORDER));
        List<Object> fame = new ArrayList<>();
        for (Person p : byFame.subList(0, Math.min(FAME_TOP, byFame.size()))) {
            Map<String, Object> r = row(fame.size() + 1, p);
            r.put("fame", p.fame());
            r.put("level", p.level());
            r.put("job", jobName(p.job()));
            fame.add(r);
        }
        m.put("fame", fame);

        Map<String, Object> g = new LinkedHashMap<>();
        Comparator<GuildRow> byPoints = Comparator.comparingLong(GuildRow::points).reversed()
                .thenComparing(x -> -members.getOrDefault(x.id(), 0)).thenComparing(GuildRow::name);
        Comparator<GuildRow> byMembers = Comparator.<GuildRow>comparingInt(x -> -members.getOrDefault(x.id(), 0))
                .thenComparing(Comparator.comparingLong(GuildRow::points).reversed()).thenComparing(GuildRow::name);
        g.put("by_points", guildRows(listed, byPoints, members, byId));
        g.put("by_members", guildRows(listed, byMembers, members, byId));
        m.put("guilds", g);

        List<Person> byMesos = new ArrayList<>(everyone);
        byMesos.sort(Comparator.comparingLong(Person::mesos).reversed().thenComparing(LEVEL_ORDER));
        List<Object> rich = new ArrayList<>();
        for (Person p : byMesos.subList(0, Math.min(RICH_TOP, byMesos.size()))) {
            Map<String, Object> r = row(rich.size() + 1, p);
            r.put("mesos", p.mesos());
            r.put("level", p.level());
            r.put("job", jobName(p.job()));
            rich.add(r);
        }
        m.put("richest", rich);
        m.put("richest_counts", "carried + storage (once per account) + hired merchant (Fredrick) + bank; bots: carried");

        Map<String, Object> dojo = new LinkedHashMap<>();
        dojo.put("recorded", false);
        dojo.put("note", "The server keeps no Mu Lung Dojo best floor or time yet.");
        dojo.put("entries", List.of());
        m.put("dojo", dojo);

        Map<String, Object> bossOut = new LinkedHashMap<>();
        for (String boss : BOSSES) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("recorded", bosses != null);
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
                    e.put("fastest_ms", r.fastestMs() > 0 ? r.fastestMs() : null);
                    entries.add(e);
                }
            }
            b.put("entries", entries);
            bossOut.put(boss.toLowerCase(java.util.Locale.ROOT), b);
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
            r.put("branch", branch(p.job()));
            r.put("guild", guildNames.get(p.guildId()));
            out.add(r);
        }
        return out;
    }

    private static List<Object> guildRows(List<GuildRow> guilds, Comparator<GuildRow> order, Map<Integer, Integer> members,
                                          Map<Integer, Person> byId) {
        List<GuildRow> sorted = new ArrayList<>(guilds);
        sorted.sort(order);
        List<Object> out = new ArrayList<>();
        for (GuildRow g : sorted.subList(0, Math.min(GUILD_TOP, sorted.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("rank", out.size() + 1);
            r.put("name", g.name());
            r.put("points", g.points());
            r.put("members", members.getOrDefault(g.id(), 0));
            r.put("capacity", g.capacity());
            Person leader = byId.get(g.leaderId());
            r.put("leader", leader == null ? null : leader.name());
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

    static String jobName(int job) {
        try {
            String n = constants.game.GameConstants.getJobName(job);
            return n == null ? "" : n;
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }
}
