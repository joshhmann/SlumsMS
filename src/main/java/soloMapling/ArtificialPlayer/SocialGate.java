package soloMapling.ArtificialPlayer;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.BotNoiseFilter;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntPredicate;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * 1.6.0 (slice S5): the one gate every unprompted bot -> real player act goes through - whispers, party/guild/PQ
 * invites, buddy requests, greetings, teasing, taunts and roasts, KS sprees and wars, call-outs, defames, revenge
 * follows, flirts, gifts and follow-ups. Replies to something the player just did are not unprompted and do not come
 * here. Bot -> bot acts are free (a bot "player" always passes).
 *
 * <h2>The owner's rules (1.6.0 social grill and audit follow-up)</h2>
 * <ol>
 *   <li><b>Started it.</b> Spice ({@link Tier#SPICE}: rivalry, trash talk, roasts, KS sprees and wars, call-outs,
 *   defames, revenge) toward a real player only after that player started it with that very bot: KS'd it, stole its
 *   drop, scammed it, roasted or insulted it, defamed it ({@link #playerStarted}). The ledger ({@link SocialLedger})
 *   halves every {@code solomapling.social.startedItHalfLifeDays} (14) days. Friendly teasing and banter
 *   ({@link Tier#TEASE}) are open to everyone.</li>
 *   <li><b>Crew side-eye.</b> When a player wronged bot A, A's crew ({@link SocialCrew}: guild mates now, close bonds
 *   from S2) may side-eye, gossip and lightly tease ({@link Tier#CREW}), never KS wars or call-outs.</li>
 *   <li><b>Romance.</b> {@code @romance on} still opens flirting; and a player who flirts with a bot first unlocks
 *   flirting for that one bot toward them ({@link #playerFlirted}). {@code @romance off} shuts everything, also the
 *   flirted-first unlock.</li>
 *   <li><b>Guardrails.</b> Nothing unprompted while the player is busy ({@link PlayerBusy}: an event or PQ instance,
 *   a dungeon, a boss map, a trade, shop or storage, an NPC chat, AFK). No line repeated by one bot to the same player
 *   ({@link #fresh}, LLM lines included). {@code @quiet bots} also blocks unsolicited whispers, invites and buddy
 *   requests, and it persists (migration 031). One "busy server" budget ({@link #take}) holds the per-pair cooldowns
 *   and world caps, about 3x the 1.5.x ones; unprompted LLM lines have their own hourly budget ({@link #llmOk}) so
 *   the extra volume comes from the line bank and scripts, not the shared chat model.</li>
 * </ol>
 *
 * <p>Every decision is logged at DEBUG and, while a {@code !qa} run listens, on the {@code SOCIAL_GATE} topic
 * ({@code YES|NO <act> <bot> -> <player>: <why>}; a NO at most once a minute per pair and act). Cheap: a call is a
 * few map reads; nothing here runs per tick. {@code -Dsolomapling.socialGate=false} turns the new rules off (the
 * 1.5.x behaviour; {@code @quiet} and busy still hold).
 */
public final class SocialGate {

    private static final Logger log = LoggerFactory.getLogger(SocialGate.class);

    /** How far an act may go, and who may receive it. */
    public enum Tier {
        /** Greetings, invites, buddy requests, gifts, buffs, whispers, follow-ups: open to everyone. */
        FRIENDLY,
        /** Friendly teasing and banter: open to everyone. */
        TEASE,
        /** Side-eye, gossip and light teasing about a wrong: the wronged bot, or its crew. */
        CREW,
        /** Rivalry and trash talk, KS, call-outs, defames, revenge: only the bot the player started it with. */
        SPICE,
        /** Flirting: {@code @romance on}, or the player flirted with this bot first; never with {@code @romance off}. */
        ROMANCE
    }

    /** How an act reaches the player; {@code @quiet bots} blocks the unsolicited whisper, invite and buddy kinds. */
    public enum Channel { CHAT, WHISPER, INVITE, BUDDY, MEGAPHONE, ACTION }

    public enum Act {
        GREET(Tier.FRIENDLY, Channel.CHAT),
        WHISPER(Tier.FRIENDLY, Channel.WHISPER),
        FOLLOW_UP(Tier.FRIENDLY, Channel.WHISPER),
        PARTY_INVITE(Tier.FRIENDLY, Channel.INVITE),
        GUILD_INVITE(Tier.FRIENDLY, Channel.INVITE),
        PQ_INVITE(Tier.FRIENDLY, Channel.INVITE),
        BUDDY_REQUEST(Tier.FRIENDLY, Channel.BUDDY),
        GIFT(Tier.FRIENDLY, Channel.ACTION),
        HELP(Tier.FRIENDLY, Channel.ACTION),
        /** A bot's whispered answer to a public ask (a buy megaphone): asked for, so {@code @quiet} lets it through. */
        TRADE_OFFER(Tier.FRIENDLY, Channel.WHISPER, true),
        TEASE(Tier.TEASE, Channel.CHAT),
        SIDE_EYE(Tier.CREW, Channel.CHAT),
        GOSSIP(Tier.CREW, Channel.CHAT),
        TAUNT(Tier.SPICE, Channel.CHAT),
        ROAST(Tier.SPICE, Channel.CHAT),
        KS_SPREE(Tier.SPICE, Channel.ACTION),
        KS_WAR(Tier.SPICE, Channel.ACTION),
        CALL_OUT(Tier.SPICE, Channel.MEGAPHONE),
        DEFAME(Tier.SPICE, Channel.ACTION),
        REVENGE(Tier.SPICE, Channel.ACTION),
        FLIRT(Tier.ROMANCE, Channel.CHAT);

        public final Tier tier;
        public final Channel channel;
        /** The player asked for it (an answer to their public ask): {@code @quiet bots} does not block it. */
        public final boolean asked;

        Act(Tier tier, Channel channel) {
            this(tier, channel, false);
        }

        Act(Tier tier, Channel channel, boolean asked) {
            this.tier = tier;
            this.channel = channel;
            this.asked = asked;
        }

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** What a player did that "starts it" with a bot. */
    public enum Provocation {
        KS, STEAL, SCAM, ROAST, DEFAME,
        /** A GM or {@code !qa} staged a grudge, or a drama scene made the bot a rival (a breakup, a mocked apology). */
        STAGED;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public record Verdict(boolean ok, String reason) {
        static Verdict yes(String why) {
            return new Verdict(true, why);
        }

        static Verdict no(String why) {
            return new Verdict(false, why);
        }
    }

    // ── state ──

    private static final SocialLedger ledger = new SocialLedger();
    /** Players who typed {@code @romance off} (and not {@code @romance on} since). */
    private static final Set<Integer> romanceOff = ConcurrentHashMap.newKeySet();
    /** Players whose choices changed in memory before the boot read landed (memory wins over the stored row). */
    private static final Set<Integer> touchedBeforeLoad = ConcurrentHashMap.newKeySet();
    private static final List<SocialCrew> crewSources = new CopyOnWriteArrayList<>(List.of(SocialCrew.GUILD));

    private static volatile SocialGateStore store = new SocialGateStore.Jdbc();
    private static volatile Executor writer = ExecutorServiceManager::runAsync;
    private static final SerialWrites writes = new SerialWrites(r -> writer.execute(r));
    private static volatile LongSupplier clock = System::currentTimeMillis;
    /** Whether choices and ledger rows are written: on after the boot read started (or a test store), off without 031. */
    private static volatile boolean storeOn;
    private static volatile boolean tablesMissing;
    private static volatile boolean loaded;
    private static volatile long loadTriedAt;

    private static volatile IntPredicate romanceOn = id ->
            soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.romanceOn(id);
    private static volatile Predicate<Character> busy = PlayerBusy::isBusy;

    private SocialGate() {
    }

    /** {@code -Dsolomapling.socialGate=false}: the 1.5.x rules (no started-it, crew or flirt-first checks). */
    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.socialGate", "true"));
    }

    static long now() {
        return clock.getAsLong();
    }

    /** How long the started-it and flirted-first weights take to halve. */
    static long halfLifeMs() {
        double days;
        try {
            days = Double.parseDouble(System.getProperty("solomapling.social.startedItHalfLifeDays", "14"));
        } catch (NumberFormatException e) {
            days = 14;
        }
        return (long) (Math.max(0, days) * TimeUnit.DAYS.toMillis(1));
    }

    // ── the decision ──

    /**
     * Whether this bot may do this unprompted act to this player now, and why. No side effects (no budget is taken,
     * nothing is logged): {@link #allow} logs, {@link #take} spends the budget.
     */
    public static Verdict check(Character bot, Character player, Act act) {
        if (player == null || act == null) {
            return Verdict.no("no player");
        }
        retryLoad();
        if (BotHelpers.isBot(player)) {
            return Verdict.yes("bot to bot: free");
        }
        if (!act.asked && quietBlocks(player.getId(), act)) {
            return Verdict.no("@quiet bots (no unsolicited " + act.channel.name().toLowerCase(Locale.ROOT) + ")");
        }
        if (busy.test(player)) {
            return Verdict.no("player busy (PlayerBusy)");
        }
        return rules(bot == null ? 0 : bot.getId(), player.getId(), act);
    }

    /** {@link #check}, logged. True when the act may go ahead. */
    public static boolean allow(Character bot, Character player, Act act) {
        Verdict v = check(bot, player, act);
        note(act, bot == null ? 0 : bot.getId(), bot == null ? "?" : bot.getName(), player, v);
        return v.ok();
    }

    /** Whether {@code @quiet bots} blocks this act for this player (whispers, invites and buddy requests). */
    static boolean quietBlocks(int playerId, Act act) {
        return (act.channel == Channel.WHISPER || act.channel == Channel.INVITE || act.channel == Channel.BUDDY)
                && BotNoiseFilter.get(playerId).bots();
    }

    /** Whether this player turned bot chatter off ({@code @quiet bots}). */
    public static boolean quietBots(int playerId) {
        return BotNoiseFilter.get(playerId).bots();
    }

    /** The tier rules alone (no busy or {@code @quiet} check): started it, crew, romance. */
    public static Verdict rules(int botId, int playerId, Act act) {
        long now = now();
        return switch (act.tier) {
            case FRIENDLY -> Verdict.yes("friendly: open to all");
            case TEASE -> Verdict.yes("friendly teasing: open to all");
            case CREW -> {
                if (!enabled()) {
                    yield Verdict.yes("gate rules off");
                }
                if (dramaOff(playerId)) {
                    yield Verdict.no("@botdrama off");
                }
                if (startedIt(botId, playerId, now)) {
                    yield Verdict.yes("they started it (" + ledger.lastKind(botId, playerId) + ")");
                }
                int mate = crewWronged(botId, playerId, now);
                yield mate != 0 ? Verdict.yes("crew side-eye: they wronged crew mate " + mate)
                        : Verdict.no("they did not start it with this bot or its crew");
            }
            case SPICE -> {
                if (!enabled()) {
                    yield Verdict.yes("gate rules off");
                }
                if (dramaOff(playerId)) {
                    yield Verdict.no("@botdrama off");
                }
                if (startedIt(botId, playerId, now)) {
                    yield Verdict.yes("they started it (" + ledger.lastKind(botId, playerId) + ")");
                }
                int mate = crewWronged(botId, playerId, now);
                yield mate != 0 ? Verdict.no("crew only side-eyes (they wronged crew mate " + mate + ", not this bot)")
                        : Verdict.no("they did not start it");
            }
            case ROMANCE -> {
                if (!enabled()) {
                    yield romanceOn.test(playerId) ? Verdict.yes("@romance on") : Verdict.no("no @romance on");
                }
                if (romanceOff.contains(playerId)) {
                    yield Verdict.no("@romance off");
                }
                if (romanceOn.test(playerId)) {
                    yield Verdict.yes("@romance on");
                }
                yield flirtedFirst(botId, playerId, now) ? Verdict.yes("they flirted with this bot first")
                        : Verdict.no("no @romance on and no flirt first");
            }
        };
    }

    private static boolean dramaOff(int playerId) {
        return !soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.hostilityEnabled()
                || soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.optedOut(playerId);
    }

    /** Logs a decision (a NO at most once a minute per act and pair). */
    public static void note(Act act, int botId, String botName, Character player, Verdict v) {
        boolean qa = QaLog.on(QaLog.Topic.SOCIAL_GATE);
        if (!qa && !log.isDebugEnabled()) {
            return;
        }
        if (!v.ok() && !throttle(act, botId, player.getId())) {
            return;
        }
        String line = (v.ok() ? "YES " : "NO ") + act.key() + " " + botName + " -> " + player.getName() + ": "
                + v.reason();
        if (qa) { // !qa sg-1, sg-2
            QaLog.map(QaLog.Topic.SOCIAL_GATE, player.getMapId(), line);
        }
        log.debug("Social gate {}", line);
    }

    private static final Map<Long, Long> lastNo = new ConcurrentHashMap<>();

    private static boolean throttle(Act act, int botId, int playerId) {
        long now = System.currentTimeMillis();
        long k = pairKey(act.ordinal(), botId, playerId);
        Long last = lastNo.get(k);
        if (last != null && now - last < 60_000) {
            return false;
        }
        lastNo.put(k, now);
        soloMapling.server.BoundedMaps.prune(lastNo, 2_000, 10_000, at -> now - at >= 60_000);
        return true;
    }

    // ── the started-it ledger ──

    /** A real player provoked a bot (KS, stolen drop, scam, roast, defame, or a staged grudge): spice is allowed back. */
    public static void playerStarted(int botId, int playerId, Provocation what) {
        if (!BotHelpers.isArtificial(botId) || BotHelpers.isArtificial(playerId) || what == null) {
            return;
        }
        SocialLedger.Row row = ledger.provoked(botId, playerId, what.key(), now(), halfLifeMs());
        persist(row);
        if (QaLog.on(QaLog.Topic.SOCIAL_GATE)) {
            QaLog.any(QaLog.Topic.SOCIAL_GATE, "STARTED " + what.key() + " player " + playerId + " -> bot " + botId
                    + ": weight " + String.format(Locale.ROOT, "%.2f", row.spice()));
        }
    }

    /** A real player flirted with a bot first: that one bot may flirt back (unless {@code @romance off}). */
    public static void playerFlirted(int botId, int playerId) {
        if (!BotHelpers.isArtificial(botId) || BotHelpers.isArtificial(playerId)) {
            return;
        }
        SocialLedger.Row row = ledger.flirted(botId, playerId, now(), halfLifeMs());
        persist(row);
        if (QaLog.on(QaLog.Topic.SOCIAL_GATE)) {
            QaLog.any(QaLog.Topic.SOCIAL_GATE, "FLIRTED player " + playerId + " -> bot " + botId + ": weight "
                    + String.format(Locale.ROOT, "%.2f", row.flirt()));
        }
    }

    /** The ledger's provocation for an affinity event the player caused, or null when it starts nothing. */
    public static Provocation provocationOf(soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent e) {
        if (e == null) {
            return null;
        }
        return switch (e) {
            case KS -> Provocation.KS;
            case DROP_STEAL -> Provocation.STEAL;
            case DEFAME -> Provocation.DEFAME;
            case RUDE_CHAT -> Provocation.ROAST;
            default -> null;
        };
    }

    public static boolean startedIt(int botId, int playerId) {
        return startedIt(botId, playerId, now());
    }

    static boolean startedIt(int botId, int playerId, long now) {
        return ledger.spice(botId, playerId, now, halfLifeMs()) >= SocialLedger.ACTIVE;
    }

    public static boolean flirtedFirst(int botId, int playerId) {
        return flirtedFirst(botId, playerId, now());
    }

    static boolean flirtedFirst(int botId, int playerId, long now) {
        return ledger.flirt(botId, playerId, now, halfLifeMs()) >= SocialLedger.ACTIVE;
    }

    /** Whether the player started it with any bot lately (a cheap pre-check before a crew lookup). */
    public static boolean wrongedAnyBot(int playerId) {
        return !ledger.wronged(playerId, now(), halfLifeMs()).isEmpty();
    }

    /** A crew mate of {@code botId} the player wronged (a bot character id), or 0. */
    static int crewWronged(int botId, int playerId, long now) {
        for (int wronged : ledger.wronged(playerId, now, halfLifeMs())) {
            if (wronged == botId) {
                continue;
            }
            for (SocialCrew source : crewSources) {
                try {
                    if (source.crew(botId, wronged)) {
                        return wronged;
                    }
                } catch (RuntimeException e) {
                    log.debug("crew source failed", e);
                }
            }
        }
        return 0;
    }

    /** Slice S2 (bot bonds): adds a crew source (close FRIEND bonds). Sources are asked in order; any yes counts. */
    public static void addCrewSource(SocialCrew source) {
        if (source != null && !crewSources.contains(source)) {
            crewSources.add(source);
        }
    }

    /** Forgets the pair (a GM / {@code !qa sg-2} "fresh player"). */
    public static void forget(int botId, int playerId) {
        if (ledger.forget(botId, playerId) && storeOn && !tablesMissing) {
            SocialGateStore s = store;
            writes.submit(playerId, () -> s.deleteLedger(playerId, botId));
        }
    }

    /** A staged bot leaves: its ledger rows, cooldowns and remembered lines go (its id comes back for the next run). */
    public static void forgetBot(int botId) {
        for (int playerId : ledger.forgetBot(botId)) {
            if (storeOn && !tablesMissing) {
                SocialGateStore s = store;
                writes.submit(playerId, () -> s.deleteLedger(playerId, botId));
            }
        }
        forgetBotBudget(botId);
        synchronized (recentLines) {
            recentLines.keySet().removeIf(k -> (int) (k >>> 32) == botId);
        }
    }

    private static void persist(SocialLedger.Row row) {
        if (storeOn && !tablesMissing) {
            SocialGateStore s = store;
            writes.submit(row.playerId(), () -> s.saveLedger(row));
        }
    }

    // ── romance ──

    /** Whether this bot may flirt with this player at all (the opt-in part; the romance rules decide the rest). */
    public static boolean romanceAllowed(int botId, int playerId) {
        return rules(botId, playerId, Act.FLIRT).ok();
    }

    public static boolean romanceOff(int playerId) {
        return romanceOff.contains(playerId);
    }

    /** {@code @romance on|off}: off shuts every flirt, the flirted-first unlock included; kept across restarts. */
    public static void setRomanceOff(int playerId, boolean off) {
        boolean changed = off ? romanceOff.add(playerId) : romanceOff.remove(playerId);
        if (changed) {
            saveOpt(playerId);
        }
    }

    // ── @quiet ──

    /** {@code @quiet} changed ({@link BotNoiseFilter#set}): kept across restarts. */
    public static void quietChanged(int playerId) {
        saveOpt(playerId);
    }

    private static void saveOpt(int playerId) {
        if (!loaded) {
            touchedBeforeLoad.add(playerId);
        }
        if (!storeOn || tablesMissing) {
            return;
        }
        BotNoiseFilter.Setting q = BotNoiseFilter.get(playerId);
        SocialGateStore.Opt opt = new SocialGateStore.Opt(playerId, q.megaphones(), q.bots(), romanceOff.contains(playerId));
        SocialGateStore s = store;
        long now = now();
        writes.submit(playerId, () -> s.saveOpt(opt, now));
    }

    // ── loading ──

    /** Boot ({@code MapleRootSolo.start}): reads the stored choices and ledger off the boot thread. */
    public static void startAtBoot() {
        storeOn = true;
        ExecutorServiceManager.runAsync(SocialGate::ensureLoaded);
    }

    /** Reads everything once; a failed read is tried again (at most once a minute) on the next decision. */
    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (SocialGate.class) {
            long now = now();
            if (loaded || (loadTriedAt > 0 && now - loadTriedAt < TimeUnit.MINUTES.toMillis(1))) {
                return;
            }
            loadTriedAt = now;
            SocialGateStore.Snapshot snap;
            try {
                snap = store.loadAll(now - 10 * Math.max(1, halfLifeMs()));
            } catch (RuntimeException e) {
                snap = null;
            }
            if (snap == null) {
                log.warn("Could not read the social gate's stored choices; @quiet and @romance off from before the"
                        + " restart hold again once the read works (tried again in a minute)");
                return;
            }
            tablesMissing = snap.missing();
            for (SocialGateStore.Opt o : snap.opts()) {
                if (touchedBeforeLoad.contains(o.charId())) {
                    continue; // changed since the boot: memory wins
                }
                if (o.quietMegaphones() || o.quietBots()) {
                    BotNoiseFilter.restore(o.charId(), new BotNoiseFilter.Setting(o.quietMegaphones(), o.quietBots()));
                }
                if (o.romanceOff()) {
                    romanceOff.add(o.charId());
                }
            }
            for (SocialLedger.Row r : snap.ledger()) {
                ledger.restore(r);
            }
            loaded = true;
            touchedBeforeLoad.clear();
            log.info("Social gate: {} stored choice(s), {} started-it row(s){}", snap.opts().size(), snap.ledger().size(),
                    snap.missing() ? " (migration 031 not applied: memory only)" : "");
        }
    }

    /** A decision found the boot read missing: try again in the background (never on the caller's thread). */
    private static void retryLoad() {
        if (!loaded && storeOn && now() - loadTriedAt >= TimeUnit.MINUTES.toMillis(1)) {
            ExecutorServiceManager.runAsync(SocialGate::ensureLoaded);
        }
    }

    // ── the busy-server budget ──

    /*
     * One table for the per-pair cooldowns and world-wide hourly caps (1.6.0, about 3x the 1.5.x ones; the old system
     * properties still tune them). Before -> after:
     *   per pair (minutes): greet 120 -> 40, party invite 180 -> 60, buddy 1440 -> 480, taunt 20 -> 7, KS spree 15 -> 5,
     *     help 15 (unchanged), defame 1440 (unchanged: it changes fame, and stays once a day per player world-wide)
     *   world per hour: taunts 20 -> 60, KS sprees 10 -> 30, party invites 6 -> 18
     *   unsolicited whisper per player (all bots): one per 10 min -> one per 200 s
     *   bot buddy requests (BotBuddies): 1 a day -> 3 a day per player, 6 -> 18 an hour world-wide
     *   unprompted LLM lines (greet openers, model roasts): new, 30 an hour world-wide; over it they use the bank
     */

    /** Per-pair cooldown, in minutes. */
    public static long pairMinutes(Act a) {
        long min = switch (a) {
            case GREET -> 40;
            case PARTY_INVITE -> 60;
            case BUDDY_REQUEST -> 480;
            case HELP -> 15;
            case TAUNT -> 7;
            case KS_SPREE -> 5;
            case DEFAME -> 1440;
            case SIDE_EYE -> 60;
            default -> 0;
        };
        return Long.getLong("solomapling.affinity." + legacyKey(a) + "Min", min);
    }

    /** World-wide cap per hour. */
    public static int perHour(Act a) {
        int cap = switch (a) {
            case TAUNT -> 60;
            case KS_SPREE -> 30;
            case PARTY_INVITE -> 18;
            case SIDE_EYE -> 30;
            default -> 1000;
        };
        return Integer.getInteger("solomapling.affinity." + legacyKey(a) + "PerHour", cap);
    }

    /** The 1.5.x property name of an act's cooldown/cap ({@code solomapling.affinity.<key>Min|PerHour}). */
    static String legacyKey(Act a) {
        return switch (a) {
            case PARTY_INVITE -> "invite";
            case BUDDY_REQUEST -> "buddy";
            case KS_SPREE -> "ks";
            case SIDE_EYE -> "sideEye";
            default -> a.key();
        };
    }

    /** One unsolicited whisper per player (all bots together) per this many ms. */
    public static long whisperGapMs() {
        return Math.max(0, Long.getLong("solomapling.chat.whisperPlayerGapMs", 200_000));
    }

    /** Bot buddy requests per player per day (all bots), and per hour world-wide. */
    public static final int BUDDY_OFFERS_PER_PLAYER_DAY = 3;
    public static final int BUDDY_OFFERS_PER_HOUR = 18;

    /** Unprompted LLM lines (greet openers, model roasts) per hour world-wide. */
    static int llmPerHour() {
        return Integer.getInteger("solomapling.social.llmPerHour", 30);
    }

    private static final Map<Long, Long> nextAt = new ConcurrentHashMap<>();
    private static final Map<Act, Deque<Long>> worldRecent = new ConcurrentHashMap<>();
    private static final Deque<Long> llmRecent = new ArrayDeque<>();

    static long pairKey(int act, int botCharId, int playerId) {
        return ((long) act << 58) ^ ((long) (botCharId - BotStore.BOT_ID_BASE) << 30) ^ (playerId & 0x3fffffffL);
    }

    /** True (and the pair's cooldown starts, the world cap counts one) when this pair may do this now. */
    public static boolean take(Act a, int botCharId, int playerId, long now) {
        long k = pairKey(a.ordinal(), botCharId, playerId);
        Long next = nextAt.get(k);
        if (next != null && now < next) {
            return false;
        }
        Deque<Long> recent = worldRecent.computeIfAbsent(a, x -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
                recent.pollFirst();
            }
            if (recent.size() >= perHour(a)) {
                return false;
            }
            recent.addLast(now);
        }
        startCooldown(a, botCharId, playerId, now);
        return true;
    }

    /** Starts the pair's cooldown without the world cap (a GM-forced act). */
    public static void startCooldown(Act a, int botCharId, int playerId, long now) {
        nextAt.put(pairKey(a.ordinal(), botCharId, playerId), now + TimeUnit.MINUTES.toMillis(pairMinutes(a)));
        if (nextAt.size() > 20_000) {
            nextAt.values().removeIf(t -> t < now);
        }
    }

    public static void forgetBotBudget(int botCharId) {
        long slot = botCharId - BotStore.BOT_ID_BASE;
        nextAt.keySet().removeIf(k -> ((k >>> 30) & 0x0FFFFFFFL) == slot);
    }

    /**
     * An unprompted line may use the chat model (one is counted): under {@code solomapling.social.llmPerHour} (30) an
     * hour world-wide. Otherwise the caller uses its line bank or script.
     */
    public static boolean llmOk() {
        long now = now();
        synchronized (llmRecent) {
            while (!llmRecent.isEmpty() && now - llmRecent.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
                llmRecent.pollFirst();
            }
            if (llmRecent.size() >= llmPerHour()) {
                return false;
            }
            llmRecent.addLast(now);
            return true;
        }
    }

    // ── no repeats ──

    /** Lines remembered per bot and player, and for how long. */
    static final int LINES_PER_PAIR = 32;
    static final long LINE_MEMORY_MS = TimeUnit.DAYS.toMillis(7);
    static final int MAX_PAIRS = 20_000;

    /** (bot key, player) -> its last lines' hashes and times, least recently used first (bounded). */
    private static final LinkedHashMap<Long, long[]> recentLines = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, long[]> eldest) {
            return size() > MAX_PAIRS;
        }
    };

    /** The same line, whatever its case, spacing and punctuation. */
    static int lineHash(String line) {
        StringBuilder b = new StringBuilder(line.length());
        boolean space = false;
        for (int i = 0; i < line.length(); i++) {
            char c = java.lang.Character.toLowerCase(line.charAt(i));
            if (java.lang.Character.isLetterOrDigit(c)) {
                if (space && b.length() > 0) {
                    b.append(' ');
                }
                b.append(c);
                space = false;
            } else {
                space = true;
            }
        }
        return b.toString().hashCode();
    }

    /** A key for a bot known only by name (a whisper sender): its character id when online, else its name. */
    public static int botKey(Character bot, String name) {
        if (bot != null) {
            return bot.getId();
        }
        return name == null ? 0 : name.toLowerCase(Locale.ROOT).hashCode() | 0x40000000;
    }

    /** Whether this bot said this line to this player lately (nothing is remembered). */
    public static boolean seen(int botKey, int playerId, String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        int h = lineHash(line);
        long nowMin = now() / 60_000;
        long maxAgeMin = LINE_MEMORY_MS / 60_000;
        long k = ((long) botKey << 32) | (playerId & 0xffffffffL);
        synchronized (recentLines) {
            long[] ring = recentLines.get(k);
            if (ring == null) {
                return false;
            }
            for (int i = 1; i < ring.length; i++) {
                long e = ring[i];
                if (e != 0 && (int) (e >>> 32) == h && nowMin - (e & 0xffffffffL) < maxAgeMin) {
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * True (and the line is remembered) when this bot has not said this line to this player lately - LLM lines too.
     * False for a repeat: the caller picks another line or says nothing.
     */
    public static boolean fresh(int botKey, int playerId, String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        int h = lineHash(line);
        long now = now();
        long k = ((long) botKey << 32) | (playerId & 0xffffffffL);
        synchronized (recentLines) {
            long[] ring = recentLines.get(k);
            if (ring == null) {
                ring = new long[LINES_PER_PAIR + 1]; // [0] = next slot; then (hash << 32 | minutes since epoch)
                recentLines.put(k, ring);
            }
            long nowMin = now / 60_000;
            long maxAgeMin = LINE_MEMORY_MS / 60_000;
            for (int i = 1; i < ring.length; i++) {
                long e = ring[i];
                if (e != 0 && (int) (e >>> 32) == h && nowMin - (e & 0xffffffffL) < maxAgeMin) {
                    return false;
                }
            }
            int slot = 1 + (int) (ring[0] % LINES_PER_PAIR);
            ring[slot] = ((long) h << 32) | (nowMin & 0xffffffffL);
            ring[0] = ring[0] + 1;
            return true;
        }
    }

    /** {@link #fresh} for a line a bot is about to say to a player, logged when it is a repeat. */
    public static boolean freshLine(Character bot, Character player, String line) {
        if (bot == null || player == null || BotHelpers.isBot(player)) {
            return line != null;
        }
        boolean ok = fresh(bot.getId(), player.getId(), line);
        if (!ok && line != null) {
            note(Act.WHISPER, bot.getId(), bot.getName(), player, Verdict.no("repeat line \"" + line + "\""));
        }
        return ok;
    }

    // ── GM read-out ──

    /** What the gate holds about a player, for GMs and {@code !qa}. */
    public static List<String> describe(int playerId) {
        List<String> out = new ArrayList<>();
        long now = now();
        BotNoiseFilter.Setting q = BotNoiseFilter.get(playerId);
        out.add("quiet: megaphones " + (q.megaphones() ? "hidden" : "shown") + ", bots " + (q.bots() ? "hidden" : "shown")
                + "; romance " + (romanceOff.contains(playerId) ? "off" : romanceOn.test(playerId) ? "on" : "default")
                + (loaded ? "" : " (stored choices not read yet)"));
        List<Integer> wronged = ledger.wronged(playerId, now, halfLifeMs());
        out.add(wronged.isEmpty() ? "started it with no bot" : "started it with bot(s) " + wronged);
        return out;
    }

    /** One pair, for GMs and {@code !qa}. */
    public static String describe(int botId, int playerId) {
        long now = now();
        long h = halfLifeMs();
        return String.format(Locale.ROOT, "started-it %.2f (%s), flirted-first %.2f; spice %s, flirt %s",
                ledger.spice(botId, playerId, now, h), ledger.lastKind(botId, playerId).isEmpty() ? "-"
                        : ledger.lastKind(botId, playerId), ledger.flirt(botId, playerId, now, h),
                rules(botId, playerId, Act.TAUNT).ok() ? "yes" : "no", rules(botId, playerId, Act.FLIRT).ok() ? "yes" : "no");
    }

    // ── test seams ──

    /** Tests: a store, a writer and a clock (nulls restore the real ones); forgets everything in memory. */
    static void useForTest(SocialGateStore testStore, Executor testWriter, LongSupplier testClock) {
        store = testStore != null ? testStore : new SocialGateStore.Jdbc();
        writer = testWriter != null ? testWriter : ExecutorServiceManager::runAsync;
        clock = testClock != null ? testClock : System::currentTimeMillis;
        storeOn = testStore != null;
        resetForTest();
    }

    /** Tests: a restart - memory is forgotten and read back from the store. */
    static void reloadForTest() {
        ledger.clear();
        romanceOff.clear();
        BotNoiseFilter.forgetAllForTest();
        loaded = false;
        loadTriedAt = 0;
        ensureLoaded();
    }

    static void useRomanceOnForTest(IntPredicate test) {
        romanceOn = test != null ? test : id -> soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.romanceOn(id);
    }

    static void useBusyForTest(Predicate<Character> test) {
        busy = test != null ? test : PlayerBusy::isBusy;
    }

    /** Tests: forgets the ledger, choices, budgets and remembered lines. */
    public static void resetForTest() {
        ledger.clear();
        romanceOff.clear();
        touchedBeforeLoad.clear();
        resetBudgetForTest();
        synchronized (recentLines) {
            recentLines.clear();
        }
        lastNo.clear();
        tablesMissing = false;
        loaded = false;
        loadTriedAt = 0;
        crewSources.retainAll(List.of(SocialCrew.GUILD));
    }

    /** Tests: forgets every cooldown and cap (AffinityReactions' reset uses it). */
    public static void resetBudgetForTest() {
        nextAt.clear();
        worldRecent.clear();
        synchronized (llmRecent) {
            llmRecent.clear();
        }
    }
}
