package soloMapling.ArtificialPlayer;

import client.Character;
import config.ServerConfig;
import config.YamlConfig;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The population director (bot-persistence ticket 08): roster bots log in and out on a daily curve, the way
 * a real server's players do. Every tick it compares the bots online with the curve's target and logs some in or
 * out; besides that, a bot whose session has run its length logs out and someone else takes its place, so the
 * faces change even while the count holds. Logins prefer bots whose usual play window covers now, so the same
 * regulars show up at similar hours. A bot holding a player-facing commitment is never logged out. While there is room
 * under the target, every bot guild keeps at least one member online (rc6).
 *
 * <p>Replaces boot waves 8 and 9 once the roster holds members; until then those waves seed it.
 */
public final class BotPopulationDirector {

    private static final Logger log = LoggerFactory.getLogger(BotPopulationDirector.class);

    /** A logged-out bot waits this long before it can log in again, so a logout is not undone a tick later. */
    static final long RELOG_COOLDOWN_MS = TimeUnit.MINUTES.toMillis(20);
    /** Off-window players exist, but rarely: their login weight against an in-window player's 1.0. */
    static final double OFF_WINDOW_WEIGHT = 0.08;
    /** Hysteresis around the target, as a fraction of it, so the count does not flap by one bot. */
    static final double SLACK = 0.02;

    /** One roster member as the planner sees it. */
    record Candidate(int botId, boolean online, boolean active, long loginAt, long logoutAt, int windowStart,
                     int windowLen, int sessionMinutes, boolean mayLogOut, boolean endedOnPurpose) {

        Candidate(int botId, boolean online, boolean active, long loginAt, long logoutAt, int windowStart,
                  int windowLen, int sessionMinutes, boolean mayLogOut) {
            this(botId, online, active, loginAt, logoutAt, windowStart, windowLen, sessionMinutes, mayLogOut, true);
        }

        boolean inWindow(int minuteOfDay) {
            return windowLen > 0 && ((minuteOfDay - windowStart) % 1440 + 1440) % 1440 < windowLen;
        }

        /** Whether this session has run its length; each session's length varies around the bot's habit. */
        boolean sessionDone(long now) {
            if (sessionMinutes <= 0 || loginAt <= 0) {
                return false;
            }
            double jitter = 0.75 + new Random(botId * 31L + loginAt).nextDouble() * 0.6;
            return now - loginAt >= TimeUnit.MINUTES.toMillis(Math.round(sessionMinutes * jitter));
        }
    }

    /** What one tick does; {@code guildLogins} of the logins are there to keep a bot guild from standing empty. */
    record Plan(List<Integer> logins, List<Integer> logouts, int target, int online, int guildLogins) {
        Plan(List<Integer> logins, List<Integer> logouts, int target, int online) {
            this(logins, logouts, target, online, 0);
        }
    }

    /**
     * The pure decision of one tick.
     *
     * @param maxLogins how many may log in this tick (the boot fill passes the whole target)
     */
    static Plan plan(List<Candidate> roster, int target, int minuteOfDay, long now, int maxLogins, int maxLogouts,
                     Random rng) {
        return plan(roster, Map.of(), target, minuteOfDay, now, maxLogins, maxLogouts, rng);
    }

    /**
     * As above, with the bot guild of each roster bot ({@code guildOf}: roster bot id to bot guild id; bots in no bot
     * guild are left out). rc6 (owner): every bot guild keeps at least one member online. While the count is under
     * the target, a bot guild with nobody online (after this tick's logouts) gets one of its members logged in ahead
     * of everyone else, picked the same way as any login (usually someone whose play window is now). Never past the
     * target, one login per empty guild, the same cooldown; the per-map cap still applies at login. Above the curve,
     * the last online member of a guild is logged out only after everyone else who could go.
     */
    static Plan plan(List<Candidate> roster, Map<Integer, Integer> guildOf, int target, int minuteOfDay, long now,
                     int maxLogins, int maxLogouts, Random rng) {
        List<Candidate> online = new ArrayList<>();
        List<Candidate> offline = new ArrayList<>();
        for (Candidate c : roster) {
            (c.online() ? online : offline).add(c);
        }
        List<Integer> logouts = new ArrayList<>();
        // 1. Sessions that ran their length end, longest overstay first.
        online.stream()
                .filter(c -> c.mayLogOut() && c.sessionDone(now))
                .sorted(Comparator.comparingLong(Candidate::loginAt))
                .limit(maxLogouts)
                .forEach(c -> logouts.add(c.botId()));
        int projected = online.size() - logouts.size();
        int slack = (int) Math.ceil(target * SLACK);
        // 2. Above the curve: players outside their window leave first, then the longest sessions; a guild's last
        // member online goes only when no one else can.
        if (projected > target + slack && logouts.size() < maxLogouts) {
            int excess = Math.min(projected - target, maxLogouts - logouts.size());
            Map<Integer, Integer> guildOnline = guildsOnline(online, logouts, guildOf);
            List<Candidate> lastOfGuild = new ArrayList<>();
            List<Candidate> order = online.stream()
                    .filter(c -> c.mayLogOut() && !logouts.contains(c.botId()))
                    .sorted(Comparator.comparing((Candidate c) -> c.inWindow(minuteOfDay))
                            .thenComparingLong(Candidate::loginAt))
                    .toList();
            int picked = 0;
            for (Candidate c : order) {
                if (picked >= excess) {
                    break;
                }
                Integer g = guildOf.get(c.botId());
                if (g != null && guildOnline.getOrDefault(g, 0) <= 1) {
                    lastOfGuild.add(c);
                    continue;
                }
                logouts.add(c.botId());
                picked++;
                if (g != null) {
                    guildOnline.merge(g, -1, Integer::sum);
                }
            }
            for (int i = 0; picked < excess && i < lastOfGuild.size(); i++, picked++) {
                logouts.add(lastOfGuild.get(i).botId());
            }
            projected = online.size() - logouts.size();
        }
        List<Candidate> eligible = new ArrayList<>();
        for (Candidate c : offline) {
            // The cooldown is for sessions the director ended: a server restart logs everyone out at once,
            // and those players must be able to come straight back.
            if (!c.active() || (c.endedOnPurpose() && c.logoutAt() > 0 && now - c.logoutAt() < RELOG_COOLDOWN_MS)) {
                continue;
            }
            eligible.add(c);
        }
        List<Integer> logins = new ArrayList<>();
        // 3. Room under the target: one member of each bot guild with nobody online.
        int room = Math.min(target - projected, maxLogins);
        if (room > 0 && !guildOf.isEmpty()) {
            Map<Integer, Integer> guildOnline = guildsOnline(online, logouts, guildOf);
            Map<Integer, double[]> best = new java.util.TreeMap<>(); // guild -> {key, botId}
            for (Candidate c : eligible) {
                Integer g = guildOf.get(c.botId());
                if (g == null || guildOnline.getOrDefault(g, 0) > 0) {
                    continue;
                }
                double key = loginKey(c, minuteOfDay, rng);
                double[] cur = best.get(g);
                if (cur == null || key > cur[0]) {
                    best.put(g, new double[]{key, c.botId()});
                }
            }
            List<double[]> picks = new ArrayList<>(best.values());
            picks.sort((a, b) -> Double.compare(b[0], a[0]));
            for (int i = 0; i < room && i < picks.size(); i++) {
                logins.add((int) picks.get(i)[1]);
            }
        }
        int guildLogins = logins.size();
        // 4. Below the curve: log players in, weighted toward those whose usual window is now.
        if (projected < target - slack || (projected < target && !logouts.isEmpty())) {
            int needed = Math.min(target - projected, maxLogins) - logins.size();
            List<double[]> keyed = new ArrayList<>(); // {key, botId}: weighted sampling without replacement
            for (Candidate c : eligible) {
                if (!logins.contains(c.botId())) {
                    keyed.add(new double[]{loginKey(c, minuteOfDay, rng), c.botId()});
                }
            }
            keyed.sort((a, b) -> Double.compare(b[0], a[0]));
            for (int i = 0; i < needed && i < keyed.size(); i++) {
                logins.add((int) keyed.get(i)[1]);
            }
        }
        return new Plan(logins, logouts, target, online.size(), guildLogins);
    }

    /** A weighted-sampling key: the highest keys log in, in-window players far more often than off-window ones. */
    private static double loginKey(Candidate c, int minuteOfDay, Random rng) {
        double weight = c.inWindow(minuteOfDay) ? 1.0 : OFF_WINDOW_WEIGHT;
        return Math.pow(rng.nextDouble(), 1.0 / weight);
    }

    /** Online members per bot guild, not counting this tick's logouts. */
    private static Map<Integer, Integer> guildsOnline(List<Candidate> online, List<Integer> logouts,
                                                      Map<Integer, Integer> guildOf) {
        Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (Candidate c : online) {
            Integer g = guildOf.get(c.botId());
            if (g != null && !logouts.contains(c.botId())) {
                counts.merge(g, 1, Integer::sum);
            }
        }
        return counts;
    }

    // ── live wiring ──────────────────────────────────────────────────────────────────────────────

    /**
     * Logins in flight at once. Each one loads a bot from the database, and the pool is small (52 connections on
     * the default config): an unbounded boot fill of a thousand logins starved it and every login timed out.
     */
    private static final java.util.concurrent.Semaphore LOGIN_SLOTS = new java.util.concurrent.Semaphore(8);

    /**
     * Bots with a login in progress: claimed before the online check and released when the login ends, so two
     * logins of one bot (the director's, a guild show-up, a boss run coming back, a GM) can never both run.
     */
    private static final java.util.Set<Integer> loggingIn = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Logouts planned but not yet started (spread across the tick): already gone as far as planning is concerned. */
    private static final java.util.Set<Integer> scheduledLogouts = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static volatile ScheduledFuture<?> ticker;
    private static volatile boolean paused;
    private static volatile Plan lastPlan;
    private static volatile long lastTickAt;
    private static volatile long startedAt;

    private BotPopulationDirector() {
    }

    public static boolean enabled() {
        ServerConfig server = YamlConfig.config.server;
        return server != null && server.BOT_DIRECTOR_ENABLED;
    }

    /** Whether the roster is big enough to run on, rather than be seeded by the boot waves first. */
    public static boolean rosterReady() {
        return BotRoster.active().size() >= Math.max(1, YamlConfig.config.server.BOT_ROSTER_SEED_MIN);
    }

    /** Fills the world to the current target at once (a server coming back up has people online), then ticks. */
    public static synchronized void start() {
        if (ticker != null) {
            return;
        }
        ServerConfig server = YamlConfig.config.server;
        startedAt = System.currentTimeMillis();
        tick(Integer.MAX_VALUE);
        long period = Math.max(5, server.BOT_DIRECTOR_TICK_SECONDS);
        ticker = ExecutorServiceManager.getScheduledExecutorService()
                .scheduleAtFixedRate(() -> tick(server.BOT_DIRECTOR_MAX_LOGINS_PER_TICK), period, period, TimeUnit.SECONDS);
        BotShutdown.onQuiesce(BotPopulationDirector::stop);
        log.info("Population director started: peak {} (roster peak {}, population cap {}), curve {}, tick {}s, channel"
                        + " weights {}", directorPeak(), server.BOT_ROSTER_PEAK_ONLINE, server.BOT_POPULATION_PEAK,
                server.BOT_ROSTER_CURVE, period, server.BOT_CHANNEL_WEIGHTS);
    }

    /** The proof harness (ticket 11): fill to the current target now, without the per-tick login cap. */
    static void fillNow() {
        tick(Integer.MAX_VALUE);
    }

    public static synchronized void stop() {
        if (ticker != null) {
            ticker.cancel(false);
            ticker = null;
        }
        startedAt = 0;
    }

    /** How long the director has been ticking (0 when it is off). Auto-QA t-bg-1 judges bot guilds after ~10 min. */
    public static long runningForMs() {
        long at = startedAt;
        return ticker == null || at == 0 ? 0 : Math.max(0, System.currentTimeMillis() - at);
    }

    /** GM: hold the population where it is (no logins or logouts) or let the director run again. */
    public static void setPaused(boolean value) {
        paused = value;
    }

    public static boolean isRunning() {
        return ticker != null && !paused;
    }

    public static BotPopulationCurve curve() {
        ServerConfig server = YamlConfig.config.server;
        return BotPopulationCurve.parse(server.BOT_ROSTER_CURVE, server.BOT_ROSTER_WEEKEND_LIFT);
    }

    public static ZonedDateTime localNow() {
        String zone = YamlConfig.config.server.BOT_ROSTER_TIMEZONE;
        return ZonedDateTime.now(zone == null || zone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(zone));
    }

    public static int targetNow() {
        return curve().target(directorPeak(), localNow());
    }

    /**
     * Ticket 57: the director's peak - {@code BOT_ROSTER_PEAK_ONLINE}, capped so the director and the environment
     * together stay within {@code BOT_POPULATION_PEAK} at the curve's top.
     */
    public static int directorPeak() {
        ServerConfig server = YamlConfig.config.server;
        return BotChannels.directorPeak(server.BOT_ROSTER_PEAK_ONLINE, server.BOT_POPULATION_PEAK,
                server.BOT_POPULATION_PEAK > 0 ? BotChannels.environmentOnline() : 0);
    }

    private static void tick(int maxLogins) {
        if (paused || BotShutdown.isQuiesced()) {
            return;
        }
        try {
            ServerConfig server = YamlConfig.config.server;
            ZonedDateTime local = localNow();
            long now = System.currentTimeMillis();
            List<Candidate> roster = candidates();
            int target = curve().target(directorPeak(), local);
            Plan plan = plan(roster, guildOfRoster(), target, local.getHour() * 60 + local.getMinute(), now, maxLogins,
                    server.BOT_DIRECTOR_MAX_LOGOUTS_PER_TICK, ThreadLocalRandom.current());
            lastPlan = plan;
            lastTickAt = now;
            if (maxLogins == Integer.MAX_VALUE) {
                System.out.println("[Director] boot fill: target " + target + ", roster " + roster.size() + " ("
                        + plan.online() + " online), logging in " + plan.logins().size());
            }
            // Ticket 09: a logout is a request - a free bot says bye and goes, a committed one waits (up to a cap).
            // Spread across the tick like logins: each logout writes the bot's row, items and session, and a whole
            // tick's worth landing at once contends for the connection pool (ticket 11 measured ~284 ms a logout in
            // a burst of 200).
            long logoutSpreadMs = TimeUnit.SECONDS.toMillis(server.BOT_DIRECTOR_TICK_SECONDS);
            for (int botId : plan.logouts()) {
                long delay = ThreadLocalRandom.current().nextLong(Math.max(1, logoutSpreadMs));
                scheduledLogouts.add(botId);
                ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
                    scheduledLogouts.remove(botId);
                    BotLogout.request(BotStore.BOT_ID_BASE + botId, System.currentTimeMillis());
                }, delay, TimeUnit.MILLISECONDS);
            }
            for (Candidate c : roster) {
                int charId = BotStore.BOT_ID_BASE + c.botId();
                if (c.online() && !c.mayLogOut() && c.sessionDone(now) && !BotLogout.isLeaving(charId)) {
                    BotLogout.request(charId, now);
                }
            }
            BotLogout.process(now);
            java.util.Set<Integer> onlineIds = new java.util.HashSet<>();
            for (Candidate c : roster) {
                if (c.online()) {
                    onlineIds.add(c.botId());
                }
            }
            BotRosterLifecycle.maybeStep(now, onlineIds);
            BotGossip.maybeStep(now);
            soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.maybeStep(now); // ticket 58: a handful of couples
            soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.maybeStep(now); // 1.5.0: bot guilds
            soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.maybeStep(now); // 1.5.0: bot drama (romance, rivals, wars)
            // Logins trickle in across the tick instead of landing together (the boot fill lands at once).
            long spreadMs = maxLogins == Integer.MAX_VALUE ? 0 : TimeUnit.SECONDS.toMillis(server.BOT_DIRECTOR_TICK_SECONDS);
            for (int botId : plan.logins()) {
                long delay = spreadMs == 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadMs);
                // A login loads the bot from the database: run it on a virtual thread, not the shared scheduler.
                ExecutorServiceManager.getScheduledExecutorService()
                        .schedule(() -> ExecutorServiceManager.runAsync(() -> boundedLogIn(botId)), delay, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            log.warn("Population director tick failed", e);
            System.out.println("[Director] tick failed: " + e);
        }
    }

    /** Roster bot id to bot guild id, for every roster bot in a bot guild (empty until the guilds are loaded). */
    private static Map<Integer, Integer> guildOfRoster() {
        Map<Integer, Integer> out = new java.util.HashMap<>();
        soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.botGuildMembers().forEach((charId, guildId) -> {
            if (charId >= BotStore.BOT_ID_BASE) {
                out.put(charId - BotStore.BOT_ID_BASE, guildId);
            }
        });
        return out;
    }

    private static List<Candidate> candidates() {
        List<Candidate> roster = new ArrayList<>();
        for (BotRoster.Member m : BotRoster.members()) {
            BotSM live = CharacterStorage.getBotById(m.charId());
            BotPersonality.Traits traits = m.traits();
            int windowStart = traits != null ? traits.playWindowStart() : m.playWindowStart();
            int windowLen = traits != null ? traits.playWindowLen() : m.playWindowLen();
            int session = traits != null ? traits.sessionMinutes() : m.sessionMinutes();
            BotRoster.Activity last = m.lastActivity();
            boolean ended = last != null && last.intState(BotRoster.ENDED_STATE, 0) == 1;
            boolean online = live != null && !scheduledLogouts.contains(m.botId());
            roster.add(new Candidate(m.botId(), online, m.status() == BotRoster.Status.ACTIVE, m.lastLoginAt(),
                    m.lastLogoutAt(), windowStart, windowLen, session, online && mayLogOut(live), ended));
        }
        return roster;
    }

    /**
     * Whether a bot can leave now without breaking a promise to a player: no open trade or armed invite, no
     * conversation in progress, no party with a real player, not following anyone.
     */
    public static boolean mayLogOut(BotSM bot) {
        return mayLogOut(bot, -1);
    }

    /**
     * As {@link #mayLogOut(BotSM)}, except that talking with, or being partied with, this one real player does not
     * count as a commitment (1.5.0 rc6, QA t-rm-1: a romance partner chatting with the player it dates is free to
     * follow that player; any other player still holds it). {@code exceptPlayerId} below 0 = no exception.
     */
    public static boolean mayLogOut(BotSM bot, int exceptPlayerId) {
        if (BotCommitments.inFlight(bot) || bot instanceof FollowerBot) {
            return false;
        }
        if (bot.getChr() != null && soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.isDead(bot.getChr().getId())) {
            return false; // 1.5.0 rc3: lying dead (it respawns first; a save now would store 0 HP)
        }
        if (bot.getChr() != null && soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns.gathering(bot.getChr().getId())) {
            return false; // 1.5.0: waiting at a boss entrance with its guild
        }
        if (bot.getChr() != null && soloMapling.ArtificialPlayer.BotCoupleSystem.BotWeddings.inCeremony(bot.getChr().getId())) {
            return false; // 1.5.0: getting married (and wearing the wedding clothes over its own)
        }
        if (bot.getChr() != null && soloMapling.ArtificialPlayer.BotDramaSystem.BotDrama.inScene(bot.getChr().getId())) {
            return false; // 1.5.0: in a drama scene (a wedding, a KS war, a revenge follow, a confrontation)
        }
        if (bot instanceof SocialBot social && social.hasActiveRespondant()
                && (exceptPlayerId < 0 || !social.isTalkingTo(exceptPlayerId))) {
            return false;
        }
        Character chr = bot.getChr();
        Party party = chr == null ? null : chr.getParty();
        if (party != null) {
            for (PartyCharacter member : party.getMembers()) {
                if (member != null && !BotHelpers.isArtificial(member.getId()) && member.getId() != exceptPlayerId) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void boundedLogIn(int botId) {
        if (soloMapling.ArtificialPlayer.BotGuildSystem.GuildBossRuns.away(botId)) {
            return; // 1.5.0: "inside" a staged guild boss run; the run logs it back in
        }
        logInBounded(botId);
    }

    /**
     * Logs one roster member in through the director's login slots (at most a few loads at once, so the bots never
     * take the whole connection pool). Every login outside a GM command goes through here. Blocks the caller while it
     * waits for a slot; true when the bot logged in.
     */
    public static boolean logInBounded(int botId) {
        if (BotShutdown.isQuiesced()) {
            return false; // 1.5.0 rehearsal: a boot fill queued behind the slots must not log in during a shutdown
        }
        try {
            LOGIN_SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        long started = System.nanoTime();
        try {
            if (logIn(botId)) {
                soloMapling.ArtificialPlayer.GCMoveSystem.BotPerformanceMonitor.recordRosterLogin(System.nanoTime() - started);
                return true;
            }
            return false;
        } catch (Exception e) {
            log.warn("Login of roster bot {} failed", botId, e);
            return false;
        } finally {
            LOGIN_SLOTS.release();
        }
    }

    /** Claims a bot's login: false when another login of it is in progress. Release with {@link #releaseLogin}. */
    static boolean claimLogin(int botId) {
        return loggingIn.add(botId);
    }

    static void releaseLogin(int botId) {
        loggingIn.remove(botId);
    }

    /**
     * Logs one roster member in, where it logged out, as the activity its session calls for. Atomic per bot: a login
     * that finds another one of the same bot in progress, or the bot online, does nothing (false).
     */
    public static boolean logIn(int botId) {
        if (!claimLogin(botId)) {
            return false; // another login of this bot is running right now
        }
        try {
            return logInClaimed(botId);
        } finally {
            releaseLogin(botId);
        }
    }

    private static boolean logInClaimed(int botId) {
        BotRoster.Member member = BotRoster.member(botId);
        if (member == null || CharacterStorage.botLoggedIn(member.charId()) || BotShutdown.isQuiesced()) {
            return false;
        }
        // Ticket 57: the channel furthest below its share of the bots online; held until the bot is in it.
        int channel = BotChannels.reserve();
        int charId;
        try {
            MapleMap home = homeMap(member, channel);
            // 1.5.0 (3k bots): the per-map bot cap - a full home map sends the bot to the same map on another
            // channel, else to the map's return town, else the login waits for a later tick.
            if (home != null && !BotMapCap.hasRoom(home)) {
                MapleMap placed = placeUnderCap(home, channel);
                if (placed == null) {
                    BotMapCap.noteTurnedAway();
                    log.debug("Roster bot {} login deferred: map {} full on every channel", botId, home.getId());
                    return false;
                }
                BotMapCap.noteTurnedAway();
                if (placed.getChannelId() != channel) {
                    BotChannels.transfer(channel, placed.getChannelId());
                    channel = placed.getChannelId();
                }
                home = placed;
            }
            if (home == null || home.getPortal(0) == null) {
                log.warn("Roster bot {} has no map to log in to", botId);
                return false;
            }
            Point spawn = home.getPortal(0).getPosition();
            charId = BotGeneration.createBot(botId, spawn, home, 0, 0, 0, 0);
        } finally {
            BotChannels.release(channel);
        }
        if (charId <= 0) {
            System.out.println("[Director] login of roster bot " + botId + " failed: createBot returned " + charId);
            return false;
        }
        BotPersonality.Traits traits = BotRoster.member(botId).traits();
        BotPersonality.Activity fresh = traits == null ? BotPersonality.Activity.GRIND
                : BotPersonality.choose(traits, ThreadLocalRandom.current());
        // A resumed session keeps its activity (setAndStartBots prefers it); a fresh one is drawn from the personality.
        BotTypeManager.setAndStartBots(List.of(charId),
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()
                        ? BotTypeManager.BotType.TRAINING_BOT : fresh.type);
        return true;
    }

    /**
     * Asks one roster member to log out, ending its session (the next login starts a fresh one). A bot holding a
     * commitment leaves once it ends, or at the cap (ticket 09). Returns false when the bot is not online.
     */
    public static boolean logOut(int botId) {
        int charId = BotStore.BOT_ID_BASE + botId;
        BotSM bot = CharacterStorage.getBotById(charId);
        if (bot == null || bot.getChr() == null) {
            return false;
        }
        BotLogout.request(charId, System.currentTimeMillis());
        return true;
    }

    /**
     * Where a login goes when its home map is full on the reserved channel (the per-map bot cap): the same map on the
     * channel with room holding the fewest bots on it, else the map's return town on the reserved channel, else that
     * town on another channel. Null when all of those are full.
     */
    static MapleMap placeUnderCap(MapleMap home, int channel) {
        MapleMap other = leastLoaded(home.getId(), channel);
        if (other != null) {
            return other;
        }
        int town = home.getReturnMapId();
        if (town <= 0 || town == constants.id.MapId.NONE || town == home.getId()) {
            return null;
        }
        return leastLoaded(town, channel);
    }

    /** This map on {@code preferred} if it has room, else on the channel with room and the fewest bots on it. */
    private static MapleMap leastLoaded(int mapId, int preferred) {
        List<net.server.channel.Channel> chs = BotChannels.channels();
        int[] counts = new int[chs.size()];
        for (int i = 0; i < chs.size(); i++) {
            net.server.channel.Channel c = chs.get(i);
            counts[i] = c.getMapFactory().isMapLoaded(mapId) ? c.getMapFactory().getMap(mapId).getBotCount() : 0;
        }
        int pick = BotMapCap.pickChannel(preferred, counts, BotMapCap.cap(mapId));
        return pick < 0 ? null : BotChannels.map(pick, mapId);
    }

    private static MapleMap homeMap(BotRoster.Member member, int channel) {
        int id = member.homeMapId() > 0 ? member.homeMapId() : constants.id.MapId.HENESYS;
        MapleMap map = BotChannels.map(channel, id);
        if (map == null) {
            map = soloMapling.server.SoloMaplingUtilities.getMapleMapById(id); // channel 1
        }
        return map != null ? map : soloMapling.server.SoloMaplingUtilities.getMapleMapById(constants.id.MapId.HENESYS);
    }

    /** For the GM read-out: the last tick's numbers. */
    public static String describe() {
        Plan plan = lastPlan;
        long online = BotRoster.members().stream().filter(m -> CharacterStorage.botLoggedIn(m.charId())).count();
        ServerConfig server = YamlConfig.config.server;
        int environment = BotChannels.environmentOnline();
        return String.format("director %s: %d roster bots online, target now %d (peak %d = min(roster peak %d,"
                        + " cap %d - %d environment)), %d waiting to leave; last tick %s: +%d (%d for empty guilds) / -%d",
                ticker == null ? "off" : paused ? "paused" : "on", online, targetNow(), directorPeak(),
                server.BOT_ROSTER_PEAK_ONLINE, server.BOT_POPULATION_PEAK, environment, BotLogout.waiting(),
                lastTickAt == 0 ? "never" : ((System.currentTimeMillis() - lastTickAt) / 1000) + "s ago",
                plan == null ? 0 : plan.logins().size(), plan == null ? 0 : plan.guildLogins(),
                plan == null ? 0 : plan.logouts().size());
    }

    /** For the GM preview: the target at each hour today. */
    public static Map<Integer, Integer> todaysTargets() {
        Map<Integer, Integer> targets = new java.util.LinkedHashMap<>();
        ZonedDateTime today = localNow().withMinute(0);
        int peak = directorPeak();
        for (int h = 0; h < 24; h++) {
            targets.put(h, curve().target(peak, today.withHour(h)));
        }
        return targets;
    }
}
