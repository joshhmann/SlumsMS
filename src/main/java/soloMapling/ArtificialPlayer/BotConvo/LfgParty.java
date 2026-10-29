package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import net.server.world.Party;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePq;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 1.6.0 (owner 2026-10-26, social trace: no LFG thread ever led anywhere): an LFG thread ends in a real party and a PQ
 * trip when the other bot can come. The answer is decided before the lines are written, so the model and the line bank
 * are told it and the lines say it ("sure inv me" / "too low lol"); the say-do guard and {@link #truthful} keep them to
 * it.
 *
 * <p>The PQ is the recruiter's own: {@link LifePq#lobbyFor} of its level (the same bands LifePq trips use). The other
 * bot joins when it fits that band and is free - not partied, not holding a commitment or on another LFG trip, not AFK,
 * no shop open, not a player's companion or alt, and a life bot that can travel. Then the recruiter makes a party, the
 * other joins, and both take the PARTY_QUEST life trip to the lobby (TrainingBot's own path). Until the trip ends
 * ({@link #TRIP_MS}) it is a commitment: neither bot logs out ({@link #holds}).
 *
 * <p>At most {@code solomapling.convo.lfgPartiesPer10Min} (3) such parties world-wide per 10 minutes, so threads never
 * drain a town; with the cap used, the other bot says "maybe later".
 */
public final class LfgParty {

    private static final Logger log = LoggerFactory.getLogger(LfgParty.class);

    /** How long the trip holds the two (the commitment), and when their bot-only party breaks up again. */
    static final long TRIP_MS = TimeUnit.MINUTES.toMillis(15);
    static final long WINDOW_MS = TimeUnit.MINUTES.toMillis(10);

    /** Whether the other bot comes, or why not. */
    public enum Why {
        YES("joins"),
        NO_PQ("no PQ lobby takes the recruiter's level"),
        RECRUITER_BUSY("the recruiter is not free"),
        TOO_LOW("too low for the PQ"),
        TOO_HIGH("too high for the PQ"),
        PARTIED("already in a party"),
        OWNED("a player's companion or alt"),
        AFK("AFK"),
        SHOP("its shop is open"),
        BUSY("busy (a commitment, a trip, or logging off)"),
        NO_TRIP("not a bot that takes life trips"),
        BUDGET("the LFG party cap (per 10 min) is used up");

        public final String words;

        Why(String words) {
            this.words = words;
        }
    }

    /** One bot's free/busy facts (pure, for {@link #fit}). */
    record State(int level, boolean partied, boolean busy, boolean afk, boolean shop, boolean owned, boolean canTrip) {
    }

    /** The decision for one LFG thread. {@code reservedAt} > 0: a slot of the 10-minute cap is held for it. */
    public record Plan(int aId, String aName, int aLevel, int bId, String bName, int bLevel, LifePq.Lobby lobby, Why why,
                       long reservedAt, Why recruiterWhy) {
        public boolean yes() {
            return why == Why.YES;
        }

        /**
         * Why not, for the trace and QA: the recruiter's own reason when it is the one who cannot go (TEST trace
         * 2026-10-27 read "MapleOG the recruiter is not free" - the other bot's name on the recruiter's reason, and not
         * which reason), else the other's.
         */
        public String decline() {
            return why == Why.RECRUITER_BUSY && recruiterWhy != null
                    ? aName + " (the recruiter) " + recruiterWhy.words : bName + " " + why.words;
        }

        /** "party up for Kerning PQ" (the trace's DECIDE). */
        public String decision() {
            return "party up for " + (lobby == null ? "a PQ" : lobby.label());
        }

        String pq() {
            return lobby == null ? "pq" : lobby.name();
        }
    }

    /** {@code started}: the recruiter's trip started, the other's, and the pair reported (under the trip's lock). */
    record Trip(Plan plan, long until, int threadId, boolean[] started) {
    }

    private static final Map<Integer, Trip> trips = new ConcurrentHashMap<>();
    private static final Deque<Long> formed = new ArrayDeque<>();

    private LfgParty() {
    }

    // ── the rule ──

    /** Whether a bot in this state can join this lobby's PQ: YES, or the first reason it can't. */
    static Why fit(LifePq.Lobby lobby, State s) {
        if (lobby == null) {
            return Why.NO_PQ;
        }
        if (s.owned()) {
            return Why.OWNED;
        }
        if (s.partied()) {
            return Why.PARTIED;
        }
        if (s.afk()) {
            return Why.AFK;
        }
        if (s.shop()) {
            return Why.SHOP;
        }
        if (s.busy()) {
            return Why.BUSY;
        }
        if (!s.canTrip()) {
            return Why.NO_TRIP;
        }
        if (s.level() < lobby.minLevel()) {
            return Why.TOO_LOW;
        }
        if (s.level() > lobby.maxLevel()) {
            return Why.TOO_HIGH;
        }
        return Why.YES;
    }

    /** The PQ an LFG thread is about: the recruiter's level's lobby, or null (no LFG talk at that level). */
    static LifePq.Lobby lobbyFor(int recruiterLevel) {
        return LifePq.lobbyFor(recruiterLevel);
    }

    /** The answer, from the two bots' states: the recruiter's lobby; either side busy, or the cap used, is a no. */
    static Plan decide(int aId, String aName, State a, int bId, String bName, State b, long now) {
        LifePq.Lobby lobby = lobbyFor(a.level());
        Why recruiter = fit(lobby, a);
        Why why = recruiter;
        if (why != Why.YES) {
            why = lobby == null ? Why.NO_PQ : Why.RECRUITER_BUSY;
        } else {
            why = fit(lobby, b);
        }
        long reserved = 0;
        if (why == Why.YES) {
            if (take(now)) {
                reserved = now;
            } else {
                why = Why.BUDGET;
            }
        }
        return new Plan(aId, aName, a.level(), bId, bName, b.level(), lobby, why, reserved, recruiter);
    }

    /** The plan for two live bots (memory reads only; the thread's compose step calls it once). */
    public static Plan plan(Character a, Character b, long now) {
        return decide(a.getId(), a.getName(), stateOf(a, now, true), b.getId(), b.getName(), stateOf(b, now, true), now);
    }

    static State stateOf(Character c, long now) {
        return stateOf(c, now, false);
    }

    /**
     * {@code forTrip}: the state for a PQ trip, which the bot must be free to start now (1.6.1 say-do: its life can
     * drop what it is doing); a chat or a plain party needs no more than the plain state.
     */
    static State stateOf(Character c, long now, boolean forTrip) {
        int id = c.getId();
        try {
            BotSM sm = CharacterStorage.getBotById(id);
            boolean owned = c.isOwnedAlt() || sm instanceof soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
            boolean busy = sm == null || soloMapling.ArtificialPlayer.BotCommitments.inFlight(sm)
                    || !soloMapling.ArtificialPlayer.BotPopulationDirector.mayLogOut(sm)
                    || soloMapling.ArtificialPlayer.BotLogout.isLeaving(id) || holds(id, now);
            boolean canTrip = sm instanceof TrainingBot tb && tb.lifeRuntime() != null
                    && TrainingBot.lifeExecutionEnabled();
            // 1.6.1 say-do: a bot on its way somewhere, restocking or mid-activity cannot take a PQ trip now: it says
            // so, rather than a yes it then breaks (its trip would never start)
            if (forTrip && !busy && canTrip && soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled()
                    && ((TrainingBot) sm).lifeRuntime().commitBlocker(now) != null) {
                busy = true;
            }
            return new State(c.getLevel(), c.getParty() != null, busy,
                    soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers.isCamping(id),
                    soloMapling.Economy.BotShops.hasShop(id), owned, canTrip);
        } catch (RuntimeException | LinkageError e) {
            log.debug("LFG: could not read {}'s state", c.getName(), e);
            return new State(c.getLevel(), c.getParty() != null, true, false, false, false, false);
        }
    }

    // ── the cap ──

    static int cap() {
        return (int) ConvoSwitches.num("lfgPartiesPer10Min", 3);
    }

    /** Takes one slot of the world-wide cap; false when it is used up. */
    static synchronized boolean take(long now) {
        while (!formed.isEmpty() && now - formed.peekFirst() >= WINDOW_MS) {
            formed.pollFirst();
        }
        if (formed.size() >= cap()) {
            return false;
        }
        formed.addLast(now);
        return true;
    }

    /** Gives a reserved slot back (the party never formed). */
    static synchronized void release(Plan p) {
        if (p != null && p.reservedAt() > 0) {
            formed.removeFirstOccurrence(p.reservedAt());
        }
    }

    // ── what the lines say ──

    private static final String[] OPENERS = {"lf {pq} anyone?", "anyone wanna do {pq}? need ppl", "lfm {pq}",
            "{pq} anyone? need 1 more", "who wants {pq} lol"};
    private static final String[] YES = {"sure inv me", "ya im down", "ok im in", "down, inv me", "sure lets go"};
    private static final String[] LEAD = {"kk inv", "k sending inv", "nice, inv sent"};
    private static final String[] TOO_LOW = {"too low lol", "im too low for {pq} lol", "cant, not lv {min} yet"};
    private static final String[] TOO_HIGH = {"too high for {pq} lol", "lol im way past {pq}", "cant, im over lv {max}"};
    private static final String[] PARTIED = {"already in a party", "sry already got a pt", "already partied sry"};
    private static final String[] SHOP = {"cant, my shop is open", "nah watching my shop"};
    private static final String[] LATER = {"cant rn sry", "maybe later", "nah im good", "not rn"};

    /** The line bank's exchange for this plan: the recruiter's ask, the other's real answer, and an invite on a yes. */
    static List<String[]> lines(Plan p, Random rng) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{p.aName(), fill(pick(OPENERS, rng), p)});
        String[] answers = switch (p.why()) {
            case YES -> YES;
            case TOO_LOW -> TOO_LOW;
            case TOO_HIGH -> TOO_HIGH;
            case PARTIED -> PARTIED;
            case SHOP -> SHOP;
            default -> LATER;
        };
        out.add(new String[]{p.bName(), fill(pick(answers, rng), p)});
        if (p.yes() && rng.nextDouble() < 0.6) {
            out.add(new String[]{p.aName(), pick(LEAD, rng)});
        }
        return out;
    }

    private static String pick(String[] lines, Random rng) {
        return lines[rng.nextInt(lines.length)];
    }

    private static String fill(String line, Plan p) {
        return line.replace("{pq}", p.pq())
                .replace("{min}", p.lobby() == null ? "21" : String.valueOf(p.lobby().minLevel()))
                .replace("{max}", p.lobby() == null ? "70" : String.valueOf(p.lobby().maxLevel()));
    }

    /** The model's brief: who asks for which PQ, and the answer the game decided (the lines must say it). */
    static String promptTurn(Plan p) {
        if (p.lobby() == null) {
            return " " + p.aName() + " asks about party quests; " + p.bName() + " is not up for one right now and says"
                    + " so. Nobody invites anyone";
        }
        String ask = " " + p.aName() + " is looking for a party for " + p.lobby().label() + " (levels "
                + p.lobby().minLevel() + "-" + p.lobby().maxLevel() + "). ";
        if (p.yes()) {
            return ask + p.bName() + " agrees to come and asks for an invite (they party up right after)";
        }
        String why = switch (p.why()) {
            case TOO_LOW -> "is too low for it (level " + p.bLevel() + ", it needs " + p.lobby().minLevel() + ")";
            case TOO_HIGH -> "is too high for it (level " + p.bLevel() + ", max " + p.lobby().maxLevel() + ")";
            case PARTIED -> "is already in another party";
            case SHOP -> "has a shop open";
            default -> "can't right now";
        };
        return ask + p.bName() + " " + why + " and says no; nobody invites anyone or promises to come";
    }

    /** Kinds of promise a thread line may make: on a yes they party and travel, else only the ask itself. */
    static Set<ClaimRules.Kind> allowed(Plan p) {
        return p != null && p.yes() ? EnumSet.of(ClaimRules.Kind.PARTY_ASK, ClaimRules.Kind.PARTY_OFFER,
                ClaimRules.Kind.COME, ClaimRules.Kind.GO_TO) : EnumSet.of(ClaimRules.Kind.PARTY_ASK);
    }

    private static Pattern words(String alternatives) {
        return Pattern.compile("(?i)(?<![a-z0-9'])(" + alternatives + ")(?![a-z0-9'])");
    }

    static final Pattern AGREE = words("sure|ya|yah|yea|yeah|yes|yep|yup|ok|okay|kk|k|down|i'?m in|count me in|"
            + "inv me|invite me|let'?s go|lets do it|bet|deal|omw");
    static final Pattern DECLINE = words("no|nah|nope|cant|can'?t|cannot|too low|too high|already|busy|later|pass|"
            + "sry|sorry|not rn|nvm|maybe");

    /** Whether a line says yes (and not no). */
    static boolean agrees(String line) {
        return line != null && AGREE.matcher(line).find() && !DECLINE.matcher(line).find();
    }

    /**
     * Whether the model's lines say the decided answer: on a yes, the other bot says yes and never no; on a no, it
     * never says yes. Otherwise the line bank speaks (a "sure" with no party after it would be a lie).
     */
    static boolean truthful(Plan p, List<String[]> lines) {
        boolean yes = false;
        boolean no = false;
        for (String[] l : lines) {
            if (!l[0].equals(p.bName())) {
                continue;
            }
            if (agrees(l[1])) {
                yes = true;
            } else if (DECLINE.matcher(l[1]).find()) {
                no = true;
            }
        }
        return p.yes() ? yes && !no : !yes;
    }

    // ── the party and the trip ──

    /**
     * After the lines: the recruiter makes a party, the other joins, and both take the PQ trip. Checks again first
     * (both online, on the thread's map, still free and in the band); a failure gives the cap slot back.
     */
    static boolean form(Plan p, int mapId, int threadId) {
        long now = System.currentTimeMillis();
        String why = null;
        Character a = chrOf(p.aId());
        Character b = chrOf(p.bId());
        try {
            if (a == null || b == null) {
                why = "a bot went offline";
            } else if (a.getMapId() != mapId || b.getMapId() != mapId) {
                why = "the two are no longer on the thread's map";
            } else {
                Why wa = fit(p.lobby(), stateOf(a, now, true));
                Why wb = fit(p.lobby(), stateOf(b, now, true));
                why = wa != Why.YES ? p.aName() + " " + wa.words : wb != Why.YES ? p.bName() + " " + wb.words : null;
            }
            if (why == null && (!Party.createParty(a, true) || a.getParty() == null)) {
                why = "the party system refused " + p.aName() + "'s party";
            }
            if (why == null && !Party.joinParty(b, a.getParty().getId(), true)) {
                soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(a);
                why = "the party system refused " + p.bName() + "'s join";
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("LFG party failed for {} + {}", p.aName(), p.bName(), e);
            why = "error: " + e.getClass().getSimpleName();
        }
        if (why != null) {
            fail(p, mapId, threadId, why);
            return false;
        }
        // 1.6.1 say-do: the invite went out and was taken ("k sending inv" / "sure inv me" are backed; TEST
        // 2026-10-28: 1 of 21 such lines was, because this party was made without telling the ledger)
        SayDo.event(a, SayDo.Ev.PARTY, a.getMapId());
        SayDo.event(b, SayDo.Ev.PARTY, b.getMapId());
        Trip t = new Trip(p, now + TRIP_MS, threadId, new boolean[3]);
        trips.put(p.aId(), t);
        trips.put(p.bId(), t);
        if (!submit(a) || !submit(b)) {
            end(t);
            fail(p, mapId, threadId, "a PQ trip was not taken (life queue closed)");
            return false;
        }
        BotThreads.qa(mapId, "LFG PARTY " + p.aName() + " + " + p.bName() + " -> " + p.lobby().label()
                + " (lv " + p.aLevel() + "/" + p.bLevel() + ", band " + p.lobby().minLevel() + "-"
                + p.lobby().maxLevel() + ")");
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.lfgFormed(threadId, p.aId(), p.bName(), p.lobby().label());
        }
        try {
            ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
                try {
                    end(t);
                } catch (RuntimeException | LinkageError e) {
                    log.warn("LFG trip end failed", e);
                }
            }), TRIP_MS, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            log.debug("LFG: no scheduler for the trip's end", e);
        }
        return true;
    }

    static void fail(Plan p, int mapId, int threadId, String why) {
        release(p);
        BotThreads.qa(mapId, "LFG FAIL " + p.aName() + " + " + p.bName() + " -> "
                + (p.lobby() == null ? "?" : p.lobby().label()) + ": " + why);
        if (soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.lfgFailed(threadId, why);
        }
    }

    private static Character chrOf(int id) {
        BotSM sm = CharacterStorage.getBotById(id);
        return sm == null ? null : sm.getChr();
    }

    /** Hands the PARTY_QUEST trip to the bot's own life queue (its update starts it: TrainingBot's lobby path). */
    private static boolean submit(Character c) {
        if (!(CharacterStorage.getBotById(c.getId()) instanceof TrainingBot tb)) {
            return false;
        }
        if (tb.lifeRuntime() != null) {
            return tb.lifeRuntime().handOff(LifeDecision.Activity.PARTY_QUEST); // 1.6.1 say-do: ends a hang-out stay
        }
        var choice = new LifeDecision.Result(List.of(), Optional.of(LifeDecision.Activity.PARTY_QUEST),
                LifeDecision.Outcome.CHOOSE);
        var offer = tb.submitLifeActivity(tb.prepareLifeActivity(choice));
        return offer == soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue.Offer.QUEUED
                || offer == soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue.Offer.REPLACED;
    }

    /**
     * TrainingBot: this bot started its PARTY_QUEST trip. When both of an LFG pair have, still in one party, the trace's
     * ACT is carried out.
     */
    public static void started(int charId) {
        Trip t = trips.get(charId);
        if (t == null) {
            return;
        }
        boolean both;
        synchronized (t) {
            t.started()[charId == t.plan().aId() ? 0 : 1] = true;
            both = t.started()[0] && t.started()[1] && !t.started()[2];
            if (both) {
                t.started()[2] = true; // reported once
            }
        }
        if (!both) {
            return;
        }
        Character a = chrOf(t.plan().aId());
        Character b = chrOf(t.plan().bId());
        boolean oneParty = a != null && b != null && a.getParty() != null && b.getParty() != null
                && a.getParty().getId() == b.getParty().getId();
        String text = "LFG TRIP " + t.plan().aName() + " + " + t.plan().bName() + " -> " + t.plan().lobby().label()
                + (oneParty ? ": both on their way, one party" : ": both on their way, but not in one party");
        if (a != null) {
            BotThreads.qa(a.getMapId(), text);
        }
        if (oneParty && soloMapling.autoqa.SocialTrace.on()) {
            soloMapling.autoqa.SocialTrace.lfgTrip(t.plan().aId(), text);
        }
    }

    /** The lobby an LFG trip sends this bot to, or -1. */
    public static int lobbyMap(int charId) {
        Trip t = trips.get(charId);
        return t == null || System.currentTimeMillis() >= t.until() ? -1 : t.plan().lobby().mapId();
    }

    /** Whether this bot is on an LFG trip (a commitment: it does not log out until the trip ends). */
    public static boolean holds(int charId) {
        return holds(charId, System.currentTimeMillis());
    }

    static boolean holds(int charId, long now) {
        Trip t = trips.get(charId);
        return t != null && now < t.until();
    }

    /** The trip is over: the two leave their bot-only party (never one a real player joined). */
    static void end(Trip t) {
        trips.remove(t.plan().aId(), t);
        trips.remove(t.plan().bId(), t);
        Character a = chrOf(t.plan().aId());
        Character b = chrOf(t.plan().bId());
        if (a == null || b == null || a.getParty() == null || b.getParty() == null
                || a.getParty().getId() != b.getParty().getId()) {
            return;
        }
        for (var member : a.getParty().getMembers()) {
            if (member != null && !soloMapling.ArtificialPlayer.BotHelpers.isArtificial(member.getId())) {
                return; // a real player came along: the party is theirs now
            }
        }
        soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(b);
        soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(a);
        BotThreads.qa(a.getMapId(), "LFG END " + t.plan().aName() + " + " + t.plan().bName() + ": party broken up");
    }

    static void resetForTest() {
        trips.clear();
        synchronized (LfgParty.class) {
            formed.clear();
        }
    }

    /** Test seam: a trip held for these two. */
    static void holdForTest(Plan p, long until) {
        Trip t = new Trip(p, until, 0, new boolean[3]);
        trips.put(p.aId(), t);
        trips.put(p.bId(), t);
    }
}
