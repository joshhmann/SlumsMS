package soloMapling.ArtificialPlayer.BotProgressionSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionBossLog.BossLogEntry;
import server.life.LifeFactory;
import server.life.Monster;
import server.life.MonsterStats;
import soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses.Boss;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.DoubleSupplier;

/**
 * 1.6.1 ticket 30 (promise audit): a regular's boss run where nobody watches, for the bosses bots cannot run on
 * screen on their own (Zakum, Horntail, Pink Bean: see {@link RebirthBosses}) when no player's expedition took it
 * along. It is held to what a player is held to and decided by what the bot can really do:
 * <ul>
 *   <li><b>Entry limits:</b> the boss's level floor, and the same daily clear limit in {@code bosslog_daily}
 *   ({@link ExpeditionBossLog#hasEntryLeft}); a clear writes the same row a player's clear writes, so it counts.</li>
 *   <li><b>Outcome:</b> a pickup group of {@link Boss#partySize()} regulars of its strength (its single-target damage a
 *   second from {@link OffscreenCombat#bossDamagePerSecond}, the grind's averaged swing) against the boss's real HP
 *   (every mob of the fight, LifeFactory's numbers with the server's overrides), inside the event's time limit:
 *   {@link #clearChance}. A group that cannot kill it in time never clears; one that kills it in half the time clears
 *   nine runs in ten (a wipe, a disconnect).</li>
 *   <li><b>The item:</b> on a clear, the boss's real {@code drop_data} rows for it are rolled first (at the world's
 *   boss drop rate: the floor drop it loots); otherwise the clear reward every member is handed (Plunk, or the Pink
 *   Bean exit NPC).</li>
 * </ul>
 * The Cygnus Knight Emblem is a hunt, not a boss: a {@link #HUNT_MINUTES} session at its droppers' kill rate rolls
 * their real drop rows per kill; with no rows, quest 31126's 80 kills give a new one.
 */
public final class OffscreenBossRun {

    private static final Logger log = LoggerFactory.getLogger(OffscreenBossRun.class);

    /** The best a group does at a boss it outclasses: one run in ten still goes wrong. */
    static final double BEST_CLEAR = 0.9;
    /** An emblem hunt lasts this long. */
    static final int HUNT_MINUTES = 30;

    private OffscreenBossRun() {
    }

    /** What the live game answers (a seam: tests hand in their own). */
    interface Live {
        boolean entryLeft(Character bot, Boss boss);

        void logClear(Character bot, Boss boss);

        /** The bot's single-target damage a second against this boss. */
        double botDps(Character bot, Boss boss);

        /** Every mob of the fight's HP, added up. */
        long bossHp(Boss boss);

        /** Per-kill chances (0..1, the world's rate in) of the drop_data rows dropping this item, by droppers given. */
        List<Double> dropChances(Character bot, int itemId, int[] droppers);

        /** Kills a minute at this mob. */
        double killsPerMinute(Character bot, int mobId);

        /** The droppers drop_data lists for this item (empty: none). */
        int[] droppers(int itemId);
    }

    static volatile Live live = new LiveGame();

    /** What one run did. */
    public record Outcome(boolean attempted, boolean cleared, boolean gotItem, String text) {
    }

    /**
     * The chance a group clears a boss it kills in {@code minutesToKill} with {@code limitMinutes} on the clock:
     * {@link #BEST_CLEAR} up to half the limit, falling straight to 0 at the limit, 0 past it.
     */
    static double clearChance(double minutesToKill, int limitMinutes) {
        if (!(minutesToKill > 0) || Double.isInfinite(minutesToKill) || limitMinutes <= 0) {
            return 0;
        }
        double half = limitMinutes / 2.0;
        if (minutesToKill <= half) {
            return BEST_CLEAR;
        }
        if (minutesToKill >= limitMinutes) {
            return 0;
        }
        return BEST_CLEAR * (limitMinutes - minutesToKill) / half;
    }

    /** One run at this boss for its item. */
    static Outcome bossRun(Character bot, Boss boss, DoubleSupplier rng) {
        if (bot.getLevel() < boss.minLevel()) {
            return new Outcome(false, false, false, boss + " needs level " + boss.minLevel());
        }
        if (!live.entryLeft(bot, boss)) {
            BossLogEntry e = boss.logEntry();
            return new Outcome(false, false, false, "no " + boss + " clears left today (" + (e == null ? "?"
                    : e.getEntries()) + " a day)");
        }
        double groupDps = live.botDps(bot, boss) * boss.partySize();
        long hp = live.bossHp(boss);
        double minutes = groupDps > 0 && hp > 0 ? hp / groupDps / 60.0 : Double.POSITIVE_INFINITY;
        double p = clearChance(minutes, boss.timeLimitMinutes);
        String fight = String.format("a group of %d at %,.0f dmg/s each vs %,d HP: %s min of %d, %.0f%% to clear",
                boss.partySize(), groupDps / boss.partySize(), hp,
                Double.isInfinite(minutes) ? "never" : String.format("%.1f", minutes), boss.timeLimitMinutes, p * 100);
        if (rng.getAsDouble() >= p) {
            return new Outcome(true, false, false, boss + " not cleared (" + fight + ")");
        }
        live.logClear(bot, boss);
        for (double c : live.dropChances(bot, boss.clearItem, boss.fightMobs())) {
            if (rng.getAsDouble() < c) {
                return new Outcome(true, true, true, boss + " cleared, " + boss.clearItem + " looted off the floor"
                        + " (drop_data " + String.format("%.2f%%", c * 100) + "; " + fight + ")");
            }
        }
        return new Outcome(true, true, true, boss + " cleared, " + boss.clearItem + " from the clear reward ("
                + fight + ")");
    }

    /** One hunting session for the Cygnus Knight Emblem: its droppers' real rows, else quest 31126's kills. */
    static Outcome emblemHunt(Character bot, DoubleSupplier rng) {
        int item = RebirthBosses.CYGNUS_EMBLEM;
        if (bot.getLevel() < RebirthBosses.EMBLEM_QUEST_LEVEL) {
            return new Outcome(false, false, false, "the emblem's area needs level " + RebirthBosses.EMBLEM_QUEST_LEVEL);
        }
        int[] droppers = live.droppers(item);
        if (droppers.length > 0) {
            List<Double> rows = live.dropChances(bot, item, droppers);
            double best = 0;
            int bestMob = droppers[0];
            for (int i = 0; i < rows.size() && i < droppers.length; i++) {
                if (rows.get(i) > best) {
                    best = rows.get(i);
                    bestMob = droppers[i];
                }
            }
            double kills = live.killsPerMinute(bot, bestMob) * HUNT_MINUTES;
            double pAny = best <= 0 ? 0 : 1 - Math.pow(1 - Math.min(1, best), kills);
            String how = String.format("%.0f kills of %d at %.3f%% a kill: %.0f%%", kills, bestMob, best * 100,
                    pAny * 100);
            boolean got = rng.getAsDouble() < pAny;
            return new Outcome(true, got, got, (got ? "emblem dropped (" : "no emblem (") + how + ")");
        }
        double kills = live.killsPerMinute(bot, RebirthBosses.EMBLEM_QUEST_MOBS[0]) * HUNT_MINUTES;
        boolean got = kills >= RebirthBosses.EMBLEM_QUEST_KILLS;
        return new Outcome(true, got, got, String.format("quest 31126: %.0f of %d kills in %d min%s", kills,
                RebirthBosses.EMBLEM_QUEST_KILLS, HUNT_MINUTES, got ? ", Chief Alex's new emblem" : ""));
    }

    // ---------------------------------------------------------------- the live game

    static final class LiveGame implements Live {
        private final Map<Boss, Long> hp = new ConcurrentHashMap<>();
        private final Map<Integer, List<int[]>> rows = new ConcurrentHashMap<>();

        @Override
        public boolean entryLeft(Character bot, Boss boss) {
            return ExpeditionBossLog.hasEntryLeft(bot.getId(), boss.logEntry());
        }

        @Override
        public void logClear(Character bot, Boss boss) {
            BossLogEntry e = boss.logEntry();
            if (e != null) {
                ExpeditionBossLog.insertPlayerEntry(bot.getId(), e);
            }
        }

        @Override
        public double botDps(Character bot, Boss boss) {
            MonsterStats stats = stats(boss.fightMobs()[0]);
            return stats == null ? 0 : OffscreenCombat.bossDamagePerSecond(bot, stats);
        }

        @Override
        public long bossHp(Boss boss) {
            return hp.computeIfAbsent(boss, b -> {
                long sum = 0;
                for (int id : b.fightMobs()) {
                    try {
                        Monster m = LifeFactory.getMonster(id);
                        if (m != null) sum += Math.max(0, m.getMaxHp());
                    } catch (RuntimeException e) {
                        // a mob the data cannot build adds nothing
                    }
                }
                return sum;
            });
        }

        @Override
        public List<Double> dropChances(Character bot, int itemId, int[] droppers) {
            int dropRate = 1;
            int bossRate = 1;
            try {
                var world = bot.getWorldServer();
                if (world != null) {
                    dropRate = Math.max(1, world.getDropRate());
                    bossRate = Math.max(1, world.getBossDropRate());
                }
            } catch (RuntimeException e) {
                // no world (tests): rate 1
            }
            List<Double> out = new ArrayList<>();
            for (int dropper : droppers) {
                for (int[] row : rowsFor(itemId)) {
                    if (row[0] == dropper && row[1] > 0) {
                        int rate = isBoss(dropper) ? bossRate : dropRate;
                        out.add(Math.min(1.0, (double) row[1] * rate / 1_000_000.0));
                    }
                }
            }
            return out;
        }

        @Override
        public double killsPerMinute(Character bot, int mobId) {
            MonsterStats stats = stats(mobId);
            if (stats == null || stats.getHp() == null) return 0;
            double dps = OffscreenCombat.bossDamagePerSecond(bot, stats);
            if (dps <= 0) return 0;
            return Math.min(30.0, 60.0 / (Math.max(1, stats.getHp()) / dps + 1.5)); // OffscreenCombat's walk and cap
        }

        @Override
        public int[] droppers(int itemId) {
            return rowsFor(itemId).stream().filter(r -> r[1] > 0).mapToInt(r -> r[0]).distinct().toArray();
        }

        private static boolean isBoss(int mobId) {
            MonsterStats s = stats(mobId);
            return s != null && s.isBoss();
        }

        private static MonsterStats stats(int mobId) {
            try {
                Monster m = LifeFactory.getMonster(mobId);
                return m == null ? null : m.getStats();
            } catch (RuntimeException e) {
                return null;
            }
        }

        /** {dropperid, chance} rows of drop_data for this item, read once per item per boot (a failed read is retried). */
        private List<int[]> rowsFor(int itemId) {
            List<int[]> cached = rows.get(itemId);
            if (cached != null) {
                return cached;
            }
            List<int[]> read = new ArrayList<>();
            try (Connection con = DatabaseConnection.getConnection();
                 PreparedStatement ps = con.prepareStatement(
                         "SELECT dropperid, chance FROM drop_data WHERE itemid = ? AND chance > 0")) {
                ps.setInt(1, itemId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) read.add(new int[]{rs.getInt(1), rs.getInt(2)});
                }
            } catch (Exception e) {
                log.warn("Could not read the drop_data rows of item {}", itemId, e);
                return List.of();
            }
            rows.put(itemId, read);
            return read;
        }
    }
}
