package soloMapling.ArtificialPlayer;

import client.BuddylistEntry;
import client.Character;
import net.server.guild.Guild;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.PartyOperation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.Activity;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeHost;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeOutcomes;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.DoubleSupplier;
import java.util.function.IntFunction;
import java.util.function.LongSupplier;

/**
 * 1.6.0 slice S6, initiative: bots that start things with real players, as people on a busy 2009 server did. Four
 * paths, every unprompted act through {@link SocialGate} ({@code @quiet bots}, {@link PlayerBusy}, no repeats, tiers,
 * the busy-server budget), scripted lines with slots (no LLM call), styled in the bot's own voice:
 *
 * <ol>
 *   <li><b>Greet at login</b> ({@link #onPlayerLogin}): online bots that count the player as a FRIEND (affinity) or are
 *   on their buddy list say hi 10-60 s after a real login (not a channel change), staggered, 1-3 of them, by buddy
 *   chat when they are buddies, else a whisper. Not every login: the chance grows with closeness and the bot's
 *   chattiness and Socializer share ({@link #planGreetings}); each bot greets one player at login at most every
 *   {@link #LOGIN_GREET_PAIR_MS}; a relog within {@link #RELOG_GUARD_MS} greets nobody.</li>
 *   <li><b>Interest-matched invites</b>: a bot standing in a PQ lobby (S8's PARTY_QUEST trip, {@link LifePq}) invites a
 *   real player of that PQ's level band on its map, or a friend online on its channel ({@link #lobbyTick}, the
 *   {@link Activity#INVITE_PQ} activity): "lf kpq? u in" and a real party invite. A friend bot in a bot guild asks a
 *   guildless player in after its login greeting ({@link Activity#INVITE_GUILD}). How the player answered (accepted,
 *   or declined / no answer within {@link #ANSWER_MS}) goes to {@link LifeOutcomes} for S9's learning. Trade offers
 *   stay with MegaBuy.</li>
 *   <li><b>Buddy requests after play together</b> ({@link #maybeBuddyAfterPlay}): at least {@link #TOGETHER_EPISODES}
 *   PARTY / PQ / BOSS / HELP episodes in {@link #TOGETHER_WINDOW_MS} (S3's {@link BotMemory} episodes), or a party of
 *   {@link #LONG_PARTY_MIN} minutes ({@link #partyStep}), through {@link BotBuddies#offerAfterPlay} (its caps and
 *   the gate).</li>
 *   <li><b>Day-later follow-ups</b>: a tagged note at least 20 h old ({@link BotMemory#dueFollowUps}) is asked about
 *   when the bot greets the player at login or sees them ({@link #followUpOnSight}): "did u ever get that zhelm?",
 *   "u 4th job yet?"; then {@link BotMemory#markFollowedUp}.</li>
 * </ol>
 *
 * <p><b>Bartle</b> ({@link #weight}): Socializers initiate most and Killers least (rivalry is S7's); Achievers lean to
 * PQ invites; Explorers add a map tip to their hello.
 *
 * <p><b>Cost:</b> nothing runs per tick. A login reads the player's (5-15 players) ties and buddy list once; a lobby
 * scan is at most one map's players and the online real players, every 20-45 s per lobby bot; the party step walks
 * the online real players' parties once a minute. Database reads ({@link BotMemory#about} on a cache miss) only on
 * worker threads. {@code -Dsolomapling.initiative=false} turns it all off.
 */
public final class SocialInitiative {

    private static final Logger log = LoggerFactory.getLogger(SocialInitiative.class);

    // ── timing and budgets ──

    /** The login plan is made this long after the login (ties are loading meanwhile). */
    static final long PLAN_DELAY_MS = 8_000;
    /** The first greeting lands this long after the login, then one every gap; never later than {@link #LATEST_MS}. */
    static final long FIRST_MIN_MS = 10_000, FIRST_MAX_MS = 22_000, GAP_MIN_MS = 8_000, GAP_MAX_MS = 18_000,
            LATEST_MS = 60_000;
    static final int MAX_GREETERS = 3;
    /** A relog (or a crash and back) this soon after the last login greets nobody. */
    static final long RELOG_GUARD_MS = TimeUnit.MINUTES.toMillis(20);
    /** One bot greets one player at login at most this often. */
    static final long LOGIN_GREET_PAIR_MS = TimeUnit.HOURS.toMillis(6);
    /** At most one follow-up question per player (all bots) per this time. */
    static final long FOLLOW_UP_PLAYER_GAP_MS = TimeUnit.MINUTES.toMillis(30);
    /** PQ invites: per pair, per bot, per player (all bots). */
    static final long PQ_PAIR_MS = TimeUnit.MINUTES.toMillis(30), PQ_BOT_GAP_MS = TimeUnit.MINUTES.toMillis(2),
            PQ_PLAYER_GAP_MS = TimeUnit.MINUTES.toMillis(10);
    /** A lobby bot looks around this often. */
    static final long LOBBY_SCAN_MIN_MS = 20_000, LOBBY_SCAN_MAX_MS = 45_000;
    /** Guild invites: per pair, per player (all bots). */
    static final long GUILD_PAIR_MS = TimeUnit.DAYS.toMillis(7), GUILD_PLAYER_MS = TimeUnit.DAYS.toMillis(1);
    /** How long an invite waits for the player's answer. */
    static final long ANSWER_MS = 60_000, ANSWER_POLL_MS = 5_000;
    /** Buddy requests after play: the episodes, the window, the party length; one look per pair per this time. */
    static final int TOGETHER_EPISODES = 2;
    static final long TOGETHER_WINDOW_MS = TimeUnit.DAYS.toMillis(7);
    static final int LONG_PARTY_MIN = 30;
    static final long BUDDY_LOOK_PAIR_MS = TimeUnit.HOURS.toMillis(6);

    // ── seams ──

    /** Where the lines and invites go (a recorder in tests). Each call already passed the gate. */
    interface Out {
        boolean whisper(Character bot, Character player, String line, SocialGate.Act act);

        boolean buddyChat(Character bot, Character player, String line);

        void mapChat(Character bot, String line);

        boolean partyInvite(Character bot, Character player);

        boolean guildInvite(Character bot, Character player);

        /** The bot leaves the party it made for an invite nobody took. */
        void leaveParty(Character bot);
    }

    interface Scheduler {
        void after(long delayMs, Runnable task);
    }

    static final Out GAME = new Out() {
        @Override
        public boolean whisper(Character bot, Character player, String line, SocialGate.Act act) {
            return BotWhispers.send(player, bot.getName(), channelIndex(bot), line, act);
        }

        @Override
        public boolean buddyChat(Character bot, Character player, String line) {
            if (!SocialGate.freshLine(bot, player, line)) {
                return false;
            }
            player.sendPacket(tools.PacketCreator.multiChat(bot.getName(),
                    soloMapling.ArtificialPlayer.BotConvo.MessyChat.forWhisper(bot, bot.getName(), player, line), 0));
            return true;
        }

        @Override
        public void mapChat(Character bot, String line) {
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot, line);
        }

        @Override
        public boolean partyInvite(Character bot, Character player) {
            return soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botInvitePlayer(bot, player);
        }

        @Override
        public boolean guildInvite(Character bot, Character player) {
            Guild g = bot.getGuild();
            return g != null && soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.sendInvite(bot, g, player);
        }

        @Override
        public void leaveParty(Character bot) {
            soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(bot);
        }
    };

    private static volatile Out out = GAME;
    private static volatile Scheduler scheduler = (ms, task) -> ExecutorServiceManager.getScheduledExecutorService()
            .schedule(() -> ExecutorServiceManager.runAsync(task), Math.max(0, ms), TimeUnit.MILLISECONDS);
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile DoubleSupplier rolls = () -> ThreadLocalRandom.current().nextDouble();
    /** An online character (bot or player) by id, or null. */
    private static volatile IntFunction<Character> online = SocialInitiative::worldCharacter;
    /** QA ({@code !qa in-2}): the initiative's clock runs this far ahead ("a day later"). */
    private static volatile long qaShiftMs;

    // ── state (memory only, bounded) ──

    private static final Map<Integer, Long> lastLogin = new ConcurrentHashMap<>();
    private static final Map<Long, Long> loginGreeted = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> lastFollowUp = new ConcurrentHashMap<>();
    private static final Map<Long, Long> pqPair = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> pqBot = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> pqPlayer = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> nextLobbyScan = new ConcurrentHashMap<>();
    private static final Map<Long, Long> guildPair = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> guildPlayer = new ConcurrentHashMap<>();
    private static final Map<Long, Long> buddyLooked = new ConcurrentHashMap<>();
    /** (bot, player) -> minutes in one party (the party step). */
    private static final Map<Long, Integer> partyMinutes = new ConcurrentHashMap<>();
    /** QA ({@code !qa in-3}): bot -> the lobby it counts as standing in, wherever it is. */
    private static final Map<Integer, LifePq.Lobby> qaLobby = new ConcurrentHashMap<>();
    private static final AtomicBoolean stepping = new AtomicBoolean();

    private SocialInitiative() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.initiative", "true"));
    }

    static long now() {
        return clock.getAsLong() + qaShiftMs;
    }

    static long pair(int botId, int playerId) {
        return ((long) botId << 32) | (playerId & 0xffffffffL);
    }

    private static int slot(int botCharId) {
        return botCharId - BotStore.BOT_ID_BASE;
    }

    static int channelIndex(Character bot) {
        try {
            return Math.max(0, bot.getClient().getChannel() - 1);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    // ── Bartle ──

    /** What a bot starts. */
    enum Kind { GREET, PQ_INVITE, GUILD_INVITE, BUDDY, FOLLOW_UP }

    /**
     * How much this bot's point on Bartle's chart makes it start this kind of thing: about 1 for an average blend,
     * up to ~1.6 for a pure Socializer greeting, ~0.6 for a pure Killer. Achievers lean to PQ invites.
     */
    static double weight(SocialProfile p, Kind kind) {
        if (p == null) {
            return 1;
        }
        double s = p.share(SocialProfile.Type.SOCIALIZER), a = p.share(SocialProfile.Type.ACHIEVER),
                e = p.share(SocialProfile.Type.EXPLORER), k = p.share(SocialProfile.Type.KILLER);
        return switch (kind) {
            case GREET, FOLLOW_UP, BUDDY -> .55 + 1.05 * s + .4 * a + .45 * e + .05 * k;
            case PQ_INVITE -> .45 + .8 * s + 1.15 * a + .35 * e + .15 * k;
            case GUILD_INVITE -> .4 + 1.2 * s + .5 * a + .3 * e + .1 * k;
        };
    }

    static SocialProfile profileOf(int botCharId) {
        try {
            LifeMemory m = LifeMemoryStore.peek(botCharId);
            return m != null ? m.social() : SocialProfile.forCharacter(botCharId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static double chattiness(int botCharId) {
        try {
            BotRoster.Member m = BotRoster.loadedMember(slot(botCharId));
            BotPersonality.Traits t = m == null ? null : m.traits();
            return t == null ? .4 : Math.max(0, Math.min(1, t.chattiness()));
        } catch (RuntimeException e) {
            return .4;
        }
    }

    // ── 1. greet at login ──

    /** A bot that may greet at login: how close it is (0..1), how chatty, whether it is a buddy, its Bartle pull. */
    record Candidate(int botId, double closeness, double chatty, boolean buddy, double bartle) {
    }

    /** A chosen greeter and when it speaks, in ms after the login. */
    record Planned(int botId, long atMs, boolean buddy) {
    }

    /** The chance one candidate greets at this login. */
    static double greetChance(Candidate c) {
        double p = (.12 + .45 * c.closeness()) * (.5 + .6 * c.chatty()) * c.bartle();
        return Math.max(0, Math.min(.9, p));
    }

    /**
     * Who greets at this login and when: each candidate (closest first) by its chance, at most {@link #MAX_GREETERS};
     * the first between {@link #FIRST_MIN_MS} and {@link #FIRST_MAX_MS} after the login, each next one
     * {@link #GAP_MIN_MS}-{@link #GAP_MAX_MS} later, none after {@link #LATEST_MS}. {@code force} (QA): every
     * candidate rolls yes.
     */
    static List<Planned> planGreetings(List<Candidate> candidates, DoubleSupplier rng, boolean force) {
        List<Candidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble((Candidate c) -> -(c.closeness() + (c.buddy() ? .2 : 0))));
        List<Planned> out = new ArrayList<>();
        long at = FIRST_MIN_MS + (long) (rng.getAsDouble() * (FIRST_MAX_MS - FIRST_MIN_MS));
        for (Candidate c : sorted) {
            if (out.size() >= MAX_GREETERS || at > LATEST_MS) {
                break;
            }
            if (!force && rng.getAsDouble() >= greetChance(c)) {
                continue;
            }
            out.add(new Planned(c.botId(), at, c.buddy()));
            at += GAP_MIN_MS + (long) (rng.getAsDouble() * (GAP_MAX_MS - GAP_MIN_MS));
        }
        return out;
    }

    /**
     * A real player logged in (PlayerLoggedinHandler, a fresh login only, not a channel change): their friend and
     * buddy bots may greet them in 10-60 s. Never blocks: the plan is made later on a worker thread.
     */
    public static void onPlayerLogin(Character player) {
        if (!enabled() || player == null || BotHelpers.isBot(player)) {
            return;
        }
        try {
            long now = now();
            int pid = player.getId();
            Long last = lastLogin.put(pid, now);
            soloMapling.server.BoundedMaps.prune(lastLogin, 2_000, 4_000, at -> now - at >= RELOG_GUARD_MS);
            startStep();
            if (last != null && now - last < RELOG_GUARD_MS) {
                qa(player, "LOGIN " + player.getName() + ": relog " + (now - last) / 1000 + " s after the last: no greetings");
                return;
            }
            BotAffinity.ensureLoaded(player); // usually already loading from the map entry
            scheduler.after(PLAN_DELAY_MS, () -> planLogin(pid, false));
        } catch (RuntimeException e) {
            log.debug("Login initiative failed for {}", player.getName(), e);
        }
    }

    /** Makes the login plan and schedules each greeting. QA: {@code force} picks every candidate. What was planned. */
    static List<Planned> planLogin(int playerId, boolean force) {
        return planLogin(playerId, force, 0);
    }

    /** As {@link #planLogin(int, boolean)}; {@code onlyBot} > 0 (QA): that bot alone is a candidate. */
    static List<Planned> planLogin(int playerId, boolean force, int onlyBot) {
        Character player = online.apply(playerId);
        if (player == null || !BotHelpers.isVisibleRealPlayer(player)) {
            return List.of();
        }
        long now = now();
        List<Candidate> candidates = candidates(player, now, force);
        if (onlyBot > 0) {
            candidates.removeIf(c -> c.botId() != onlyBot);
        }
        List<Planned> plan = planGreetings(candidates, rolls, force);
        qa(player, "LOGIN PLAN " + player.getName() + ": " + candidates.size() + " friend/buddy bot(s) online, "
                + plan.size() + " greet" + (plan.isEmpty() ? "" : " at " + plan.stream()
                .map(p -> p.atMs() / 1000 + "s").toList()));
        for (Planned p : plan) {
            scheduler.after(p.atMs() - (force ? 0 : PLAN_DELAY_MS), () -> greetAtLogin(p.botId(), playerId, force));
        }
        return plan;
    }

    /** Online FRIEND bots (affinity) and online bot buddies of this player, at most 24, closest first. */
    static List<Candidate> candidates(Character player, long now, boolean force) {
        Map<Integer, Candidate> out = new LinkedHashMap<>();
        int pid = player.getId();
        for (int botId : BotAffinity.botsFeeling(pid, AffinityState.FRIEND)) {
            Character bot = online.apply(botId);
            if (bot == null || !BotHelpers.isBot(bot)) {
                continue;
            }
            double score = BotAffinity.score(botId, pid);
            double closeness = Math.max(.3, Math.min(1, .3 + .7 * (score - 25) / 75));
            out.put(botId, new Candidate(botId, closeness, chattiness(botId), false,
                    weight(profileOf(botId), Kind.GREET)));
        }
        Collection<BuddylistEntry> buddies = player.getBuddylist() == null ? List.of() : player.getBuddylist().getBuddies();
        for (BuddylistEntry e : buddies) {
            int botId = e.getCharacterId();
            if (!BotHelpers.isArtificial(botId) || !e.isVisible()) {
                continue;
            }
            Character bot = online.apply(botId);
            if (bot == null || !BotHelpers.isBot(bot) || bot.getBuddylist() == null
                    || !bot.getBuddylist().containsVisible(pid)) {
                continue;
            }
            Candidate known = out.get(botId);
            double closeness = Math.min(1, Math.max(.6, known == null ? 0 : known.closeness() + .2));
            out.put(botId, new Candidate(botId, closeness, chattiness(botId), true, weight(profileOf(botId), Kind.GREET)));
        }
        List<Candidate> list = new ArrayList<>();
        for (Candidate c : out.values()) {
            Long greeted = loginGreeted.get(pair(c.botId(), pid));
            if (!force && greeted != null && now - greeted < LOGIN_GREET_PAIR_MS) {
                continue;
            }
            if (BotConversation.isTalking(c.botId(), pid)) {
                continue;
            }
            list.add(c);
        }
        list.sort(Comparator.comparingDouble((Candidate c) -> -c.closeness()));
        return list.size() > 24 ? new ArrayList<>(list.subList(0, 24)) : list;
    }

    /** One planned greeting (a worker thread): the gate, the line (a due follow-up folded in), buddy chat or whisper. */
    static boolean greetAtLogin(int botId, int playerId) {
        return greetAtLogin(botId, playerId, false);
    }

    /** {@code force} (QA): a hello the staged bot gave on sight a moment ago does not hold this one. */
    static boolean greetAtLogin(int botId, int playerId, boolean force) {
        Character bot = online.apply(botId);
        Character player = online.apply(playerId);
        if (bot == null || player == null || !BotHelpers.isVisibleRealPlayer(player)) {
            return false;
        }
        long now = now();
        if (!SocialGate.allow(bot, player, SocialGate.Act.GREET)) { // @quiet bots, busy: logged by the gate
            return false;
        }
        if (force) {
            SocialGate.startCooldown(SocialGate.Act.GREET, botId, playerId, now);
        } else if (!SocialGate.take(SocialGate.Act.GREET, botId, playerId, now)) {
            qa(player, "GREET held " + bot.getName() + " -> " + player.getName() + ": greeted lately (budget)");
            return false;
        }
        boolean buddy = buddies(bot, player);
        BotMemory.Note follow = dueFollowUp(botId, playerId, now);
        SocialProfile profile = profileOf(botId);
        String line = loginLine(player.getName(), profile, player.getLevel(), follow, rolls);
        line = styled(botId, line, player.getName(), follow == null ? null : itemOf(follow.tag()));
        boolean sent = buddy ? out.buddyChat(bot, player, line) : out.whisper(bot, player, line, SocialGate.Act.GREET);
        if (!sent) {
            qa(player, "GREET held " + bot.getName() + " -> " + player.getName() + ": "
                    + (buddy ? "repeat line" : "whisper budget or repeat line"));
            return false;
        }
        loginGreeted.put(pair(botId, playerId), now);
        soloMapling.server.BoundedMaps.prune(loginGreeted, 8_000, 16_000, at -> now - at >= LOGIN_GREET_PAIR_MS);
        if (follow != null) {
            BotMemory.markFollowedUp(slot(botId), playerId, follow.tag(), now);
            lastFollowUp.put(playerId, now);
        }
        qa(player, "GREET " + bot.getName() + " -> " + player.getName() + " at login by " + (buddy ? "buddy chat" : "whisper")
                + (follow == null ? "" : ", follow-up " + follow.tag()) + ": \"" + line + "\"");
        // what may follow a hello: a guild invite from a friend in a bot guild, a buddy request after play together
        scheduler.after(4_000 + (long) (rolls.getAsDouble() * 8_000), () -> {
            if (!maybeGuildInvite(botId, playerId, false)) {
                maybeBuddyAfterPlay(botId, playerId, 0);
            }
        });
        return true;
    }

    static boolean buddies(Character bot, Character player) {
        try {
            return player.getBuddylist() != null && player.getBuddylist().containsVisible(bot.getId())
                    && bot.getBuddylist() != null && bot.getBuddylist().containsVisible(player.getId());
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ── lines ──

    static final List<String> HELLO = List.of("hey {name}", "yo {name} wb", "heyy {name}", "wb {name}", "o hey ur on",
            "sup {name}", "hey hey {name}", "{name}! wb", "yo whats up", "hiii {name}", "ayy {name}", "wb wb",
            "hey {name} :)", "oh hey {name}");
    static final List<String> HELLO_SOCIAL = List.of("{name}!! wb, how u been", "yo {name} missed u lol",
            "heyyy {name} wb!!", "{name} ur on!! hows it going");
    static final List<String> HELLO_ACHIEVER = List.of("wb {name}, wanna pq later?", "yo {name} im grinding, u?",
            "hey {name} hows the leveling going");
    static final List<String> HELLO_EXPLORER = List.of("wb {name}! btw {map} is pretty empty rn",
            "hey {name}, {map} was dead earlier if u need a spot", "yo {name} try {map} its empty rn");
    static final List<String> HELLO_KILLER = List.of("oh its {name}", "sup {name}", "{name}. wb i guess lol");

    /** The hello one bot sends at login: its type's flavour now and then, a due follow-up folded in. */
    static String loginLine(String playerName, SocialProfile p, int playerLevel, BotMemory.Note follow, DoubleSupplier rng) {
        String name = rng.getAsDouble() < .5 ? playerName.toLowerCase(Locale.ROOT) : playerName;
        List<String> bank = HELLO;
        SocialProfile.Type lead = p == null ? null : p.leading();
        if (follow == null && lead != null && rng.getAsDouble() < .45) {
            bank = switch (lead) {
                case SOCIALIZER -> HELLO_SOCIAL;
                case ACHIEVER -> HELLO_ACHIEVER;
                case EXPLORER -> HELLO_EXPLORER;
                case KILLER -> HELLO_KILLER;
            };
        }
        String hello = pick(bank, rng).replace("{name}", name).replace("{map}", mapTip(playerLevel));
        if (follow == null) {
            return hello;
        }
        String ask = followUpLine(follow.tag(), rng);
        return ask == null ? hello : pick(List.of("wb", "hey", "yo", "hey " + name), rng) + " " + ask;
    }

    /** Explorers' map tip for this level (2009 spots). */
    static String mapTip(int level) {
        if (level <= 20) {
            return "pig beach";
        }
        if (level <= 30) {
            return "ant tunnel";
        }
        if (level <= 45) {
            return "the toy factory in ludi";
        }
        if (level <= 60) {
            return "the drakes in sleepywood";
        }
        if (level <= 80) {
            return "el nath dungeon";
        }
        return "leafre";
    }

    static final List<String> ASK_ITEM = List.of("did u ever get {item}?", "u get {item} yet?", "any luck on {item}?",
            "still farming for {item}?", "so did u get {item} lol");
    static final List<String> ASK_JOB = List.of("u {goal} yet?", "did u get {goal}?", "hows the {goal} grind going",
            "u make {goal} yet?");
    static final List<String> ASK_LEVEL = List.of("u hit {n} yet?", "u lvl {n} yet?", "did u make {n}?");
    static final List<String> ASK_BOSS = List.of("did u ever get to do {goal}?", "u do {goal} yet?",
            "how was {goal}, did u go?");

    /** The casual question a tag asks a day later, or null for a tag it cannot ask about. */
    static String followUpLine(String tag, DoubleSupplier rng) {
        if (tag == null || tag.isEmpty()) {
            return null;
        }
        int colon = tag.indexOf(':');
        String kind = colon < 0 ? "" : tag.substring(0, colon);
        String what = colon < 0 ? tag : tag.substring(colon + 1).trim();
        if (what.isEmpty()) {
            return null;
        }
        if ("item".equals(kind)) {
            return pick(ASK_ITEM, rng).replace("{item}", itemOf(tag));
        }
        if (what.startsWith("level ")) {
            return pick(ASK_LEVEL, rng).replace("{n}", what.substring(6).trim());
        }
        if (what.endsWith(" job") || what.equals("a job advance")) {
            return pick(ASK_JOB, rng).replace("{goal}", what);
        }
        return pick(ASK_BOSS, rng).replace("{goal}", what);
    }

    /** "item:zhelm" -> "that zhelm"; "item:a mount" -> "a mount"; "item:ilbis" -> "ur ilbis". */
    static String itemOf(String tag) {
        if (tag == null) {
            return null;
        }
        String what = tag.startsWith("item:") ? tag.substring(5).trim() : tag;
        if (what.startsWith("a ") || what.startsWith("an ")) {
            return what;
        }
        return what.endsWith("s") ? "ur " + what : "that " + what;
    }

    static String pick(List<String> bank, DoubleSupplier rng) {
        int i = (int) (rng.getAsDouble() * bank.size());
        return bank.get(Math.max(0, Math.min(bank.size() - 1, i)));
    }

    /** The line in the bot's voice (casing, abbreviations, typos); the player's name and the item stay as they are. */
    static String styled(int botId, String line, String playerName, String item) {
        try {
            int s = slot(botId);
            List<String> keep = new ArrayList<>();
            keep.add(playerName);
            if (item != null) {
                keep.addAll(List.of(item.split(" ")));
            }
            String v = soloMapling.ArtificialPlayer.BotConvo.VoiceStyler.style(line,
                    soloMapling.ArtificialPlayer.BotConvo.ConvoPersona.of(s, chattiness(botId)), s, keep);
            return v == null || v.isBlank() ? line : v;
        } catch (RuntimeException | LinkageError e) {
            return line;
        }
    }

    // ── 4. day-later follow-ups ──

    /** This bot's newest due follow-up about the player (S3's tagged notes, 20 h+ old, not asked yet), or null. */
    static BotMemory.Note dueFollowUp(int botId, int playerId, long now) {
        try {
            for (BotMemory.Note n : BotMemory.dueFollowUps(slot(botId), playerId, now)) {
                if (followUpLine(n.tag(), () -> 0) != null) {
                    return n;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not read bot {}'s follow-ups about {}", botId, playerId, e);
        }
        return null;
    }

    /**
     * A bot just noticed a player it knows (BotRecognition, a worker thread): if it holds a due follow-up about them it
     * asks it now, in map chat by name, instead of a plain hello. At most one follow-up per player every
     * {@link #FOLLOW_UP_PLAYER_GAP_MS} from all bots; Socializers ask more often than Killers. True when it asked.
     */
    public static boolean followUpOnSight(BotSM botSm, Character player) {
        if (!enabled() || botSm == null || botSm.getChr() == null || player == null) {
            return false;
        }
        return followUp(botSm.getChr(), player, false, true, botSm);
    }

    /** {@code force} (QA): no roll, no per-player gap. {@code onMap}: said in map chat (else whispered). */
    static boolean followUp(Character bot, Character player, boolean force, boolean onMap, BotSM botSm) {
        long now = now();
        Long last = lastFollowUp.get(player.getId());
        if (!force && last != null && now - last < FOLLOW_UP_PLAYER_GAP_MS) {
            return false;
        }
        BotMemory.Note due = dueFollowUp(bot.getId(), player.getId(), now);
        if (due == null) {
            return false;
        }
        double chance = Math.min(.95, .6 * weight(profileOf(bot.getId()), Kind.FOLLOW_UP));
        if (!force && rolls.getAsDouble() >= chance) {
            return false;
        }
        if (!SocialGate.allow(bot, player, SocialGate.Act.FOLLOW_UP)) {
            return false;
        }
        String ask = followUpLine(due.tag(), rolls);
        String name = rolls.getAsDouble() < .5 ? player.getName().toLowerCase(Locale.ROOT) : player.getName();
        String line = styled(bot.getId(), onMap ? pick(List.of("yo {name} ", "{name} ", "hey {name} ", "oh {name} "),
                rolls).replace("{name}", name) + ask : ask, player.getName(), itemOf(due.tag()));
        boolean sameMap = onMap && bot.getMapId() == player.getMapId();
        boolean sent;
        if (sameMap) {
            sent = SocialGate.freshLine(bot, player, line);
            if (sent) {
                out.mapChat(bot, line);
            }
        } else {
            sent = out.whisper(bot, player, line, SocialGate.Act.FOLLOW_UP);
        }
        if (!sent) {
            return false;
        }
        BotMemory.markFollowedUp(slot(bot.getId()), player.getId(), due.tag(), now);
        lastFollowUp.put(player.getId(), now);
        soloMapling.server.BoundedMaps.prune(lastFollowUp, 2_000, 4_000, at -> now - at >= FOLLOW_UP_PLAYER_GAP_MS);
        if (botSm != null && sameMap) {
            BotConversation.open(botSm, player); // their answer reaches this bot
        }
        qa(player, "FOLLOW_UP " + bot.getName() + " -> " + player.getName() + " " + due.tag() + " (note "
                + TimeUnit.MILLISECONDS.toHours(now - due.createdAt()) + " h old, " + (sameMap ? "map chat" : "whisper")
                + "): \"" + line + "\"");
        return true;
    }

    // ── 2. interest-matched invites: a PQ lobby ──

    /** The lobby this bot stands in (its level's PQ, on that lobby's map), or null. QA may stage one. */
    static LifePq.Lobby lobbyOf(Character bot) {
        LifePq.Lobby staged = qaLobby.get(bot.getId());
        if (staged != null) {
            return inBand(staged, bot.getLevel()) ? staged : null; // the inviter fits the PQ too, staged or not
        }
        LifePq.Lobby lobby = LifePq.lobbyFor(bot.getLevel());
        return lobby != null && bot.getMapId() == lobby.mapId() ? lobby : null;
    }

    /** Whether a level fits a PQ lobby's band. */
    public static boolean inBand(LifePq.Lobby lobby, int level) {
        return lobby != null && level >= lobby.minLevel() && level <= lobby.maxLevel();
    }

    /** Whether the bot can invite at all: no party, or its own party with room. */
    static boolean canLead(Character bot) {
        Party p = bot.getParty();
        return p == null || p.getLeaderId() == bot.getId() && p.getMembers().size() < 6;
    }

    /**
     * Whom a bot in a PQ lobby would invite now: a real player of the band on its map, else a friend of the band
     * online on its channel; none in a party, busy, quiet, or asked lately. Null when nobody. Memory only.
     */
    public static Character pqTarget(Character bot, long now) {
        if (!enabled() || bot == null || bot.getMap() == null) {
            return null;
        }
        LifePq.Lobby lobby = lobbyOf(bot);
        if (lobby == null || !canLead(bot)) {
            return null;
        }
        Long botLast = pqBot.get(bot.getId());
        if (botLast != null && now - botLast < PQ_BOT_GAP_MS) {
            return null;
        }
        for (Character p : bot.getMap().getAllPlayers()) {
            if (p != null && p.getId() != bot.getId() && pqOk(bot, p, lobby, now)) {
                return p;
            }
        }
        int channel = bot.getMap().getChannelId();
        for (int pid : BotAffinity.knownPlayers()) {
            if (!BotAffinity.friend(bot.getId(), pid)) {
                continue;
            }
            Character p = online.apply(pid);
            if (p != null && p.getMap() != null && p.getMap().getChannelId() == channel && pqOk(bot, p, lobby, now)) {
                return p;
            }
        }
        return null;
    }

    private static boolean pqOk(Character bot, Character p, LifePq.Lobby lobby, long now) {
        if (BotHelpers.isBot(p) || !BotHelpers.isVisibleRealPlayer(p) || p.getParty() != null
                || !inBand(lobby, p.getLevel()) || SocialGate.quietBots(p.getId())) {
            return false;
        }
        Long pairAt = pqPair.get(pair(bot.getId(), p.getId()));
        Long playerAt = pqPlayer.get(p.getId());
        return (pairAt == null || now - pairAt >= PQ_PAIR_MS) && (playerAt == null || now - playerAt >= PQ_PLAYER_GAP_MS)
                && !PlayerBusy.isBusy(p);
    }

    /**
     * Called from a bot's PQ-lobby stay (TrainingBot's dwell, every update): every 20-45 s it looks around and, when
     * its host says INVITE_PQ can start ({@code canStartLifeActivity}) and its Bartle roll says so, invites. The answer
     * goes to {@link LifeOutcomes}. True when it invited.
     */
    public static boolean lobbyTick(LifeHost host, long now) {
        if (!enabled() || host == null || host.getChr() == null) {
            return false;
        }
        Character bot = host.getChr();
        Long next = nextLobbyScan.get(bot.getId());
        if (next != null && now < next) {
            return false;
        }
        nextLobbyScan.put(bot.getId(), now + LOBBY_SCAN_MIN_MS
                + (long) (rolls.getAsDouble() * (LOBBY_SCAN_MAX_MS - LOBBY_SCAN_MIN_MS)));
        soloMapling.server.BoundedMaps.prune(nextLobbyScan, 4_000, 8_000, at -> now >= at);
        if (!host.canStartLifeActivity(Activity.INVITE_PQ, now)) {
            return false;
        }
        Character target = pqTarget(bot, now);
        if (target == null) {
            return false;
        }
        double chance = Math.min(.9, .55 * weight(profileOf(bot.getId()), Kind.PQ_INVITE));
        if (rolls.getAsDouble() >= chance) {
            pqBot.put(bot.getId(), now); // it looked and let it be: not again for a couple of minutes
            return false;
        }
        int botId = bot.getId();
        return invitePq(bot, target, now, (ok, why) -> LifeOutcomes.report(botId, Activity.INVITE_PQ, ok, now(), why));
    }

    /** Where an invite's answer goes: accepted or not, and a short reason. */
    public interface Answer {
        void answered(boolean accepted, String why);
    }

    /** The bot invites the player to its lobby's PQ: the gate, a line, a real party invite, then it waits for the answer. */
    public static boolean invitePq(Character bot, Character player, long now, Answer answer) {
        LifePq.Lobby lobby = bot == null ? null : lobbyOf(bot);
        if (lobby == null || player == null) {
            return false;
        }
        if (!SocialGate.allow(bot, player, SocialGate.Act.PQ_INVITE)) {
            return false;
        }
        if (!SocialGate.take(SocialGate.Act.PQ_INVITE, bot.getId(), player.getId(), now)) {
            return false;
        }
        boolean sameMap = bot.getMapId() == player.getMapId();
        boolean friend = BotAffinity.friend(bot.getId(), player.getId());
        String line = pqLine(lobby.name(), player.getName(), sameMap, friend, bot.getLevel(), rolls);
        line = styled(bot.getId(), line, player.getName(), lobby.name());
        boolean hadParty = bot.getParty() != null;
        boolean said;
        if (sameMap) {
            said = SocialGate.freshLine(bot, player, line);
            if (said) {
                out.mapChat(bot, line);
            }
        } else {
            said = out.whisper(bot, player, line, SocialGate.Act.PQ_INVITE);
        }
        if (!said && !sameMap) {
            return false; // a friend elsewhere gets no bare invite without a word
        }
        pqPair.put(pair(bot.getId(), player.getId()), now);
        pqBot.put(bot.getId(), now);
        pqPlayer.put(player.getId(), now);
        soloMapling.server.BoundedMaps.prune(pqPair, 4_000, 8_000, at -> now - at >= PQ_PAIR_MS);
        soloMapling.server.BoundedMaps.prune(pqPlayer, 2_000, 4_000, at -> now - at >= PQ_PLAYER_GAP_MS);
        if (!out.partyInvite(bot, player)) {
            qa(player, "PQ_INVITE " + bot.getName() + " -> " + player.getName() + " " + lobby.name()
                    + ": the party system refused the invite (" + partyRefusal(bot, player) + ")");
            return false;
        }
        qa(player, "PQ_INVITE " + bot.getName() + " (lv " + bot.getLevel() + ") -> " + player.getName() + " (lv "
                + player.getLevel() + ") " + lobby.name() + " band " + lobby.minLevel() + "-" + lobby.maxLevel() + ", "
                + (sameMap ? "same map" : "friend on ch" + (channelIndex(bot) + 1)) + ": \"" + line + "\"");
        watchParty(bot, player, hadParty, 0, answer);
        return true;
    }

    static final List<String> PQ_HERE = List.of("lf {pq}? u in", "{pq}? need 1 more", "{name} wanna {pq}?",
            "{pq} anyone? {name} u in?", "lf {pq}, {name} come", "{name} {pq}? im lv {lvl}");
    static final List<String> PQ_FRIEND = List.of("doing {pq} rn, u in?", "{pq}? we need 1 more",
            "yo wanna {pq}? im at the lobby", "come {pq} w me");

    static String pqLine(String pq, String playerName, boolean sameMap, boolean friend, int level, DoubleSupplier rng) {
        String name = rng.getAsDouble() < .6 ? playerName.toLowerCase(Locale.ROOT) : playerName;
        String line = pick(sameMap ? PQ_HERE : PQ_FRIEND, rng);
        return line.replace("{pq}", pq).replace("{name}", name).replace("{lvl}", Integer.toString(level));
    }

    /** Why BotPartyCommands.botInvitePlayer said no, as far as can be told after the fact (its own checks, in order). */
    static String partyRefusal(Character bot, Character player) {
        try {
            String busy = PlayerBusy.reason(player, System.currentTimeMillis());
            if (busy != null) {
                return "the player is busy: " + busy;
            }
            if (player.getParty() != null) {
                return "the player is in a party";
            }
            Party p = bot.getParty();
            if (p != null && p.getLeaderId() != bot.getId()) {
                return "the bot is in a party it does not lead";
            }
            if (p != null && p.getMembers().size() >= 6) {
                return "the bot's party is full";
            }
            if (p == null && bot.getLevel() < 10) {
                return "the bot is under level 10 and cannot make a party";
            }
            if (net.server.coordinator.world.InviteCoordinator.hasInvite(
                    net.server.coordinator.world.InviteCoordinator.InviteType.PARTY, player.getId())) {
                return "the player already has a pending party invite";
            }
        } catch (RuntimeException | LinkageError e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
        return "unknown";
    }

    /** Polls the party every {@link #ANSWER_POLL_MS}: the player joined (accepted), or not by {@link #ANSWER_MS}. */
    private static void watchParty(Character bot, Character player, boolean hadParty, long waited, Answer answer) {
        scheduler.after(ANSWER_POLL_MS, () -> {
            long w = waited + ANSWER_POLL_MS;
            Party mine = bot.getParty();
            Party theirs = player.getParty();
            boolean joined = mine != null && theirs != null && mine.getId() == theirs.getId();
            if (!joined && w < ANSWER_MS && player.isLoggedinWorld() && theirs == null && mine != null) {
                watchParty(bot, player, hadParty, w, answer);
                return;
            }
            String why = joined ? "accepted" : theirs != null ? "joined another party"
                    : mine == null ? "the bot's party broke up" : "declined or no answer";
            qa(player, "PQ_INVITE ANSWER " + player.getName() + " -> " + bot.getName() + ": " + why);
            try {
                answer.answered(joined, why);
            } catch (RuntimeException e) {
                log.debug("invite answer", e);
            }
            if (joined) {
                handLead(bot, player);
            } else if (!hadParty && mine != null && mine.getMembers().size() <= 1) {
                try {
                    out.leaveParty(bot);
                } catch (RuntimeException e) {
                    log.debug("Could not leave the invite's party", e);
                }
            }
        });
    }

    /** The player joined: the bot hands them the lead (only a leader can talk to the PQ's NPC) and says so. */
    private static void handLead(Character bot, Character player) {
        try {
            Party p = bot.getParty();
            PartyCharacter them = p == null ? null : p.getMemberById(player.getId());
            if (them != null && p.getLeaderId() == bot.getId() && bot.getWorldServer() != null) {
                bot.getWorldServer().updateParty(p.getId(), PartyOperation.CHANGE_LEADER, them);
                if (bot.getMapId() == player.getMapId()) {
                    out.mapChat(bot, pick(List.of("ty! u lead", "u lead, talk to the npc", "gl, ur lead"), rolls));
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not hand the party lead to {}", player.getName(), e);
        }
    }

    // ── 2b. guild invites (after a login greeting) ──

    /**
     * A FRIEND bot in a bot guild with room asks a guildless player in (after its login hello). Socializers ask most.
     * At most one ask per pair a week and per player a day. {@code force} (QA): no roll, no cooldown.
     */
    static boolean maybeGuildInvite(int botId, int playerId, boolean force) {
        Character bot = online.apply(botId);
        Character player = online.apply(playerId);
        if (bot == null || player == null || player.getGuildId() > 0 || bot.getGuildId() <= 0
                || !BotAffinity.friend(botId, playerId)) {
            return false;
        }
        long now = now();
        Long pairAt = guildPair.get(pair(botId, playerId));
        Long playerAt = guildPlayer.get(playerId);
        if (!force && (pairAt != null && now - pairAt < GUILD_PAIR_MS || playerAt != null && now - playerAt < GUILD_PLAYER_MS)) {
            return false;
        }
        Guild g;
        try {
            g = bot.getGuild();
            if (g == null || !soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.isBotGuild(g.getId())
                    || g.getMembers().size() >= g.getCapacity()) {
                return false;
            }
        } catch (RuntimeException e) {
            return false;
        }
        double chance = Math.min(.6, .12 * weight(profileOf(botId), Kind.GUILD_INVITE));
        if (!force && rolls.getAsDouble() >= chance) {
            return false;
        }
        guildPair.put(pair(botId, playerId), now);
        guildPlayer.put(playerId, now);
        if (!out.guildInvite(bot, player)) { // BotGuilds.sendInvite: the gate (GUILD_INVITE), busy, the stock invite
            return false;
        }
        String line = styled(botId, pick(List.of("u still guildless? we got room in " + g.getName(),
                "join " + g.getName() + "? we need ppl lol", "sent u a guild inv, " + g.getName() + " is chill"), rolls),
                player.getName(), g.getName());
        if (buddies(bot, player)) {
            out.buddyChat(bot, player, line);
        }
        int gid = g.getId();
        qa(player, "GUILD_INVITE " + bot.getName() + " -> " + player.getName() + " for " + g.getName());
        watchGuild(botId, player, gid, 0);
        return true;
    }

    private static void watchGuild(int botId, Character player, int guildId, long waited) {
        scheduler.after(ANSWER_POLL_MS, () -> {
            long w = waited + ANSWER_POLL_MS;
            boolean joined = player.getGuildId() == guildId;
            if (!joined && w < ANSWER_MS && player.getGuildId() <= 0 && player.isLoggedinWorld()) {
                watchGuild(botId, player, guildId, w);
                return;
            }
            String why = joined ? "accepted" : "declined or no answer";
            qa(player, "GUILD_INVITE ANSWER " + player.getName() + ": " + why);
            LifeOutcomes.report(botId, Activity.INVITE_GUILD, joined, now(), why);
        });
    }

    // ── 3. buddy requests after play together ──

    /** PARTY / PQ / BOSS / HELP episodes the bot holds about the player within {@link #TOGETHER_WINDOW_MS}. */
    static int togetherEpisodes(List<BotMemory.Note> notes, long now) {
        int n = 0;
        for (BotMemory.Note note : notes) {
            BotMemory.Kind k = note.kind();
            if ((k == BotMemory.Kind.PARTY || k == BotMemory.Kind.PQ || k == BotMemory.Kind.BOSS || k == BotMemory.Kind.HELP)
                    && note.source() == BotMemory.Source.DIRECT && now - note.createdAt() <= TOGETHER_WINDOW_MS) {
                n++;
            }
        }
        return n;
    }

    /** Whether they played together enough for a buddy request: episodes, or one long party. */
    static boolean playedEnough(int episodes, int partyMinutes) {
        return episodes >= TOGETHER_EPISODES || partyMinutes >= LONG_PARTY_MIN;
    }

    /**
     * After play together (episodes, or {@code minutesTogether} in one party), the bot may send a buddy request through
     * {@link BotBuddies#offerAfterPlay} (its caps, the gate, the roll by Socializer share). One look per pair per
     * {@link #BUDDY_LOOK_PAIR_MS}. Reads the bot's notes (a worker thread). True when the request went out.
     */
    static boolean maybeBuddyAfterPlay(int botId, int playerId, int minutesTogether) {
        Character bot = online.apply(botId);
        Character player = online.apply(playerId);
        if (bot == null || player == null || !BotHelpers.isVisibleRealPlayer(player)) {
            return false;
        }
        try {
            if (player.getBuddylist() != null && player.getBuddylist().contains(botId)) {
                return false;
            }
        } catch (RuntimeException ignored) {
            // an unreadable list: the buddy code checks it again
        }
        long now = now();
        int episodes = togetherEpisodes(safeNotes(botId, playerId), now);
        if (!playedEnough(episodes, minutesTogether)) {
            return false;
        }
        Long looked = buddyLooked.get(pair(botId, playerId));
        if (looked != null && now - looked < BUDDY_LOOK_PAIR_MS) {
            return false;
        }
        buddyLooked.put(pair(botId, playerId), now);
        soloMapling.server.BoundedMaps.prune(buddyLooked, 8_000, 16_000, at -> now - at >= BUDDY_LOOK_PAIR_MS);
        int together = Math.max(episodes, minutesTogether >= LONG_PARTY_MIN ? TOGETHER_EPISODES : 0);
        String gate = BotBuddies.offerAfterPlay(botId, playerId, together,
                weight(profileOf(botId), Kind.BUDDY));
        qa(player, "BUDDY_AFTER_PLAY " + bot.getName() + " -> " + player.getName() + ": " + episodes
                + " episode(s) in 7 days, " + minutesTogether + " min in a party now -> " + gate);
        return gate.endsWith(": ok");
    }

    private static List<BotMemory.Note> safeNotes(int botId, int playerId) {
        try {
            return BotMemory.about(slot(botId), playerId);
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** The party step runs once a minute from the first real login on. */
    private static void startStep() {
        if (stepping.compareAndSet(false, true)) {
            try {
                ExecutorServiceManager.scheduleAtFixedRate(SocialInitiative::partyStepSafe, 60, 60, TimeUnit.SECONDS);
            } catch (RuntimeException e) {
                stepping.set(false);
            }
        }
    }

    private static void partyStepSafe() {
        try {
            partyStep(now());
        } catch (RuntimeException | LinkageError e) {
            log.debug("Initiative party step failed", e);
        }
    }

    /**
     * Once a minute: every online real player's party, every bot in it gets a minute together; at
     * {@link #LONG_PARTY_MIN} (and then every half hour) it may ask to be buddies. Pairs no longer partied are
     * forgotten. At most 15 players x 5 bots.
     */
    static void partyStep(long now) {
        if (!enabled()) {
            return;
        }
        Map<Long, Integer> seen = new LinkedHashMap<>();
        for (int pid : BotAffinity.knownPlayers()) {
            Character player = online.apply(pid);
            Party party = player == null ? null : player.getParty();
            if (party == null) {
                continue;
            }
            for (PartyCharacter m : party.getMembers()) {
                if (m == null || !BotHelpers.isArtificial(m.getId())) {
                    continue;
                }
                long k = pair(m.getId(), pid);
                seen.put(k, partyMinutes.getOrDefault(k, 0) + 1);
            }
        }
        partyMinutes.keySet().retainAll(seen.keySet());
        partyMinutes.putAll(seen);
        for (Map.Entry<Long, Integer> e : seen.entrySet()) {
            int minutes = e.getValue();
            if (minutes >= LONG_PARTY_MIN && (minutes - LONG_PARTY_MIN) % 30 == 0) {
                int botId = (int) (e.getKey() >>> 32);
                int pid = (int) (long) e.getKey();
                ExecutorServiceManager.runAsync(() -> maybeBuddyAfterPlay(botId, pid, minutes));
            }
        }
    }

    // ── QA and GM ──

    private static void qa(Character player, String line) {
        if (QaLog.on(QaLog.Topic.SOCIAL_GATE)) {
            QaLog.map(QaLog.Topic.SOCIAL_GATE, player == null ? 0 : player.getMapId(), "INIT " + line);
        }
        log.debug("Initiative {}", line);
    }

    private static Character worldCharacter(int id) {
        if (BotHelpers.isArtificial(id)) {
            BotSM sm = CharacterStorage.getBotById(id);
            return sm == null ? null : sm.getChr();
        }
        net.server.world.World w = soloMapling.server.SoloMaplingUtilities.world();
        Character c = w == null ? null : w.getPlayerStorage().getCharacterById(id);
        return c == null || BotHelpers.isBot(c) || !c.isLoggedinWorld() ? null : c;
    }

    /** {@code !qa in-1}: plans the player's login greetings now, every friend/buddy bot rolling yes. */
    public static List<String> qaLogin(Character player) {
        return qaLogin(player, 0);
    }

    /** As {@link #qaLogin(Character)}; {@code onlyBot} > 0: only that (staged) bot is a candidate, so it greets first. */
    public static List<String> qaLogin(Character player, int onlyBot) {
        loginGreeted.keySet().removeIf(k -> (int) (long) k == player.getId());
        lastLogin.remove(player.getId());
        List<Planned> plan = planLogin(player.getId(), true, onlyBot);
        List<String> out = new ArrayList<>();
        for (Planned p : plan) {
            out.add("bot " + p.botId() + " greets at " + p.atMs() / 1000 + " s by " + (p.buddy() ? "buddy chat" : "whisper"));
        }
        return out;
    }

    /** {@code !qa in-2}: the initiative's clock runs {@code ms} ahead (0 = real time). */
    public static void qaShift(long ms) {
        qaShiftMs = Math.max(0, ms);
    }

    public static long qaShift() {
        return qaShiftMs;
    }

    /** {@code !qa in-2}: the bot asks its due follow-up now (no roll, no gap): whispered. True when it asked. */
    public static boolean qaFollowUp(Character bot, Character player) {
        lastFollowUp.remove(player.getId());
        return followUp(bot, player, true, false, null);
    }

    /** {@code !qa in-3}: the bot counts as standing in this lobby (null: back to its real map), cooldowns forgotten. */
    public static void qaLobby(Character bot, LifePq.Lobby lobby, Character player) {
        if (lobby == null) {
            qaLobby.remove(bot.getId());
        } else {
            qaLobby.put(bot.getId(), lobby);
        }
        nextLobbyScan.remove(bot.getId());
        pqBot.remove(bot.getId());
        if (player != null) {
            pqPair.remove(pair(bot.getId(), player.getId()));
            pqPlayer.remove(player.getId());
            SocialGate.forgetBotBudget(bot.getId());
        }
    }

    /** {@code !qa in-3}: the staged lobby bot invites now if the target fits (no roll). What happened. */
    public static String qaInvite(Character bot, Character player) {
        LifePq.Lobby lobby = qaLobby.getOrDefault(bot.getId(), lobbyOf(bot));
        if (lobby == null || !inBand(lobby, bot.getLevel()) || !canLead(bot) || !pqOk(bot, player, lobby, now())) {
            return "no invite: " + (lobby == null ? "the bot is in no lobby"
                    : !inBand(lobby, bot.getLevel()) ? "the bot's level " + bot.getLevel() + " is outside the band"
                    : !canLead(bot) ? "the bot cannot lead a party"
                    : "you do not fit (band, party, quiet, busy, cooldown)");
        }
        int botId = bot.getId();
        boolean sent = invitePq(bot, player, now(), (ok, why) -> {
            LifeOutcomes.report(botId, Activity.INVITE_PQ, ok, now(), why);
            qa(player, "OUTCOME invite_pq bot " + botId + ": " + (ok ? "success" : "failure") + " (" + why + ")");
        });
        return sent ? "invited" : "held by the gate or the party system";
    }

    // ── test seams ──

    static void useForTest(Out testOut, Scheduler testScheduler, LongSupplier testClock, DoubleSupplier testRolls,
                           IntFunction<Character> testOnline) {
        out = testOut != null ? testOut : GAME;
        scheduler = testScheduler != null ? testScheduler : (ms, task) -> ExecutorServiceManager
                .getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(task), Math.max(0, ms),
                        TimeUnit.MILLISECONDS);
        clock = testClock != null ? testClock : System::currentTimeMillis;
        rolls = testRolls != null ? testRolls : () -> ThreadLocalRandom.current().nextDouble();
        online = testOnline != null ? testOnline : SocialInitiative::worldCharacter;
        qaShiftMs = 0;
        lastLogin.clear();
        loginGreeted.clear();
        lastFollowUp.clear();
        pqPair.clear();
        pqBot.clear();
        pqPlayer.clear();
        nextLobbyScan.clear();
        guildPair.clear();
        guildPlayer.clear();
        buddyLooked.clear();
        partyMinutes.clear();
        qaLobby.clear();
        stepping.set(true); // tests never start the real minute step
    }

    static Map<Long, Integer> partyMinutesForTest() {
        return partyMinutes;
    }
}
