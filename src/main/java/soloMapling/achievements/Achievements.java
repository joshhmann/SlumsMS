package soloMapling.achievements;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 1.6.1 (ticket 93): achievements and medals. The hooks in the game's own code paths call the static methods here;
 * with {@code -Dslums.achievements=true} off each one is a single boolean read. A hook never throws into the game and
 * never waits for the database (events run on one background thread). Player and bot events take the same path.
 */
public final class Achievements {
    private static final Logger log = LoggerFactory.getLogger(Achievements.class);
    public static final String FLAG = "slums.achievements";

    private static volatile AchievementService service;
    private static volatile boolean loadFailed;
    private static volatile Boolean enabledForTest;
    private static ExecutorService executor;

    private Achievements() {
    }

    public static boolean enabled() {
        Boolean t = enabledForTest;
        return t != null ? t : Boolean.getBoolean(FLAG);
    }

    static void setEnabledForTest(Boolean on) {
        enabledForTest = on;
    }

    /** Replaces the service (tests, and the QA suite's isolated runs). */
    public static void useForTest(AchievementService s) {
        service = s;
        loadFailed = false;
    }

    /** The running service, or null when the feature is off or its yaml could not be read. */
    public static AchievementService service() {
        if (!enabled()) {
            return null;
        }
        AchievementService s = service;
        if (s == null && !loadFailed) {
            synchronized (Achievements.class) {
                s = service;
                if (s == null && !loadFailed) {
                    s = build();
                    service = s;
                    loadFailed = s == null;
                }
            }
        }
        return s;
    }

    private static AchievementService build() {
        File file = soloMapling.DataFiles.optional(GoalCatalog.DATA_PATH);
        try {
            if (!file.isFile()) {
                throw new IllegalStateException(GoalCatalog.DATA_PATH + " not found");
            }
            GoalCatalog catalog = GoalCatalog.read(file.toPath(), id -> {
                String name = server.ItemInformationProvider.getInstance().getName(id);
                return name != null && !name.isBlank();
            });
            executor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "achievements");
                t.setDaemon(true);
                return t;
            });
            log.info("Achievements: {} goals loaded from {}", catalog.goals().size(), file);
            return new AchievementService(catalog, new JdbcAchievementStore(), new GameHands(), System::currentTimeMillis,
                    executor);
        } catch (Exception e) {
            log.error("Achievements are off: {} is invalid ({})", file, e.getMessage());
            return null;
        }
    }

    private static void fire(Character chr, Event e) {
        try {
            if (chr == null || !enabled()) {
                return;
            }
            AchievementService s = service();
            if (s != null) {
                s.submit(Subject.of(chr), e);
            }
        } catch (RuntimeException | LinkageError ex) {
            log.warn("achievement hook {} failed", e, ex);
        }
    }

    // ── the hooks ───────────────────────────────────────────────────────────────────────────────

    /** Character.changeJob / the bots' own advance. */
    public static void onJob(Character chr, int newJobId) {
        if (enabled()) {
            fire(chr, new Event(Trigger.JOB, jobTier(newJobId)));
        }
    }

    /** Character level-ups (online and the bots' offscreen gains). */
    public static void onLevel(Character chr) {
        if (!enabled() || chr == null) {
            return;
        }
        int level = chr.getLevel();
        fire(chr, new Event(Trigger.LEVEL, level));
        fire(chr, new Event(Trigger.LEVEL_CAP, level >= chr.getMaxLevel() ? 1 : 0));
    }

    /** The credited killer of a mob. */
    public static void onMobKill(Character chr, int mobId) {
        if (enabled()) {
            fire(chr, new Event(Trigger.MOB_KILL, mobId));
        }
    }

    /** A PQ or boss instance was cleared; everyone inside. */
    public static void onClear(String scriptName, java.util.Collection<Character> members) {
        if (!enabled() || scriptName == null || members == null) {
            return;
        }
        for (Character c : members) {
            fire(c, new Event(Trigger.CLEAR, 1, scriptName));
        }
    }

    public static void onFish(Character chr) {
        simple(chr, Trigger.FISH);
    }

    public static void onGuildJoin(Character chr) {
        simple(chr, Trigger.GUILD);
    }

    public static void onWedding(Character chr) {
        simple(chr, Trigger.WEDDING);
    }

    /** A bot couple's wedding: the bot may be anywhere (or not in the world just now); its row is kept either way. */
    public static void onBotWedding(int botId) {
        try {
            if (!enabled()) {
                return;
            }
            AchievementService s = service();
            var bot = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(botId);
            Character chr = bot == null ? null : bot.getChr();
            if (s != null) {
                s.submit(chr != null ? Subject.of(chr) : new Subject(botId, true, null), Event.of(Trigger.WEDDING));
            }
        } catch (RuntimeException | LinkageError ex) {
            log.warn("achievement wedding hook failed", ex);
        }
    }

    public static void onRebirth(Character chr) {
        simple(chr, Trigger.REBIRTH);
    }

    public static void onQuestDone(Character chr) {
        simple(chr, Trigger.QUEST);
    }

    public static void onTrade(Character chr) {
        simple(chr, Trigger.TRADE);
    }

    public static void onMarketBuy(Character chr) {
        simple(chr, Trigger.MARKET);
    }

    /** A Daily Check-In day was claimed (players only). */
    public static void onCheckin(Character chr, int day) {
        if (enabled()) {
            fire(chr, new Event(Trigger.CHECKIN, day));
        }
    }

    public static void onMap(Character chr, int mapId) {
        if (enabled()) {
            fire(chr, new Event(Trigger.VISIT, mapId));
        }
    }

    private static void simple(Character chr, Trigger t) {
        if (enabled()) {
            fire(chr, Event.of(t));
        }
    }

    /**
     * Login of a real player: facts that are already true (job tier, level) count, quietly, and any medal owed from
     * an earlier full bag or a GM grant while offline is handed over. Runs on the achievements thread.
     */
    public static void onLogin(Character chr) {
        try {
            if (!enabled() || chr == null || soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
                return;
            }
            AchievementService s = service();
            if (s == null) {
                return;
            }
            Subject who = Subject.of(chr);
            java.util.concurrent.Executor run = executor != null ? executor : Runnable::run;
            run.execute(() -> {
                try {
                    s.forget(who.id());
                    s.handle(who, new Event(Trigger.JOB, jobTier(chr.getJob().getId())), true);
                    s.handle(who, new Event(Trigger.LEVEL, chr.getLevel()), true);
                    s.handle(who, new Event(Trigger.LEVEL_CAP, chr.getLevel() >= chr.getMaxLevel() ? 1 : 0), true);
                    // 1.6.1 promise audit: a guild, a marriage and rebirths from before achievements count too.
                    for (Event fact : loginFacts(chr)) {
                        s.handle(who, fact, true);
                    }
                    s.settle(who);
                } catch (RuntimeException | LinkageError ex) {
                    log.warn("achievement login for {} failed", who.id(), ex);
                }
            });
        } catch (RuntimeException | LinkageError ex) {
            log.warn("achievement login hook failed", ex);
        }
    }

    /**
     * What a character already is that a goal rewards, read from its loaded state (no database): in a guild, married,
     * and its rebirth count. Facts, so a later login never counts them twice.
     */
    static java.util.List<Event> loginFacts(Character chr) {
        java.util.List<Event> out = new java.util.ArrayList<>();
        if (chr.getGuildId() > 0) {
            out.add(Event.fact(Trigger.GUILD, 1));
        }
        if (chr.isMarried()) {
            out.add(Event.fact(Trigger.WEDDING, 1));
        }
        int reborns = 0;
        try {
            reborns = config.YamlConfig.config.server.USE_REBIRTH_SYSTEM ? chr.getReborns() : 0;
        } catch (RuntimeException ignored) {
            // rebirths switched off on this server
        }
        if (reborns > 0) {
            out.add(Event.fact(Trigger.REBIRTH, reborns));
        }
        return out;
    }

    public static void onLogout(Character chr) {
        AchievementService s = service;
        if (s != null && chr != null) {
            s.forget(chr.getId());
        }
    }

    /** 0 beginner, 1..4 the job advancement a job id stands for (Evan's later stages count as the 4th). */
    public static int jobTier(int jobId) {
        if (jobId == 2001 || jobId % 1000 == 0) {
            return 0;
        }
        if (jobId % 100 == 0) {
            return 1;
        }
        return Math.min(4, 2 + jobId % 10);
    }
}
