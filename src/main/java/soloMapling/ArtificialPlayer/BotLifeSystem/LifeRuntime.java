package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import soloMapling.ArtificialPlayer.*;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/**
 * Event/completion driven decisions, with a cheap 30-second interrupt check, on the existing bot update.
 *
 * <p>1.6.0 S8: any {@link LifeHost} runs it (the TrainingBot, and the town and social bots through
 * {@link LifeTownLife}). Each decision: goals are refreshed from game facts, {@link LifePlanner} re-plans only when a
 * goal or fact changed, and the plan's next step, the bot's Bartle point, an unasked friend to help, a PQ that fits
 * and a crowded map or KS war ({@link LifePolicy.Situation}) join the needs in the utility scorer. A tick whose
 * signature (needs, people, eligibility, plan step, crowd) has not changed is skipped, as before.
 */
public final class LifeRuntime {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LifeRuntime.class);
    private static final LongAdder decisions = new LongAdder(), accepted = new LongAdder(), cpuNanos = new LongAdder();
    private static final java.util.concurrent.atomic.AtomicLong nextReport = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<Activity, LongAdder> activityCounts = new java.util.concurrent.ConcurrentHashMap<>();
    /** What decisions selected (kept or newly chosen), the audit's mix; accepted starts are {@link #activityCounts}. */
    private static final java.util.concurrent.ConcurrentHashMap<Activity, LongAdder> selectedCounts = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * Choices only: a decision that picks something other than what the bot is doing (a transition). "selected" also
     * counts every re-check that keeps the current activity, so it measures time spent, not choices (S8 follow-up).
     */
    private static final java.util.concurrent.ConcurrentHashMap<Activity, LongAdder> chosenCounts = new java.util.concurrent.ConcurrentHashMap<>();
    /** Audit finding 5: one decision in this many is logged at INFO (with the running mix); the rest at DEBUG. */
    static final int LOG_SAMPLE = Math.max(1, Integer.getInteger("slums.botlife.logEvery", 500));
    /** The SOCIAL need fills over this many town stays since the bot last spent time with people. */
    static final int SOCIAL_PERIOD_FACTOR = 3;
    static final int HOME_TOWN = 100000000;
    public record DecisionSnapshot(long at, LifeObservation.Report report) {}
    private volatile DecisionSnapshot lastDecision;
    public DecisionSnapshot lastDecision() { return lastDecision; }
    private final BotSM botSm;
    private final LifeHost bot;
    private final LifeMemory memory;
    private final long bornAt;
    private final boolean restored;
    private volatile int townDestination = HOME_TOWN;
    private volatile boolean dirty = true;
    private long nextCheck, dueAt, lastAnnouncement, lastNeedSignature = Long.MIN_VALUE;
    private long lastRestock;
    public boolean restockReady(long now) { return lastRestock == 0 || now - lastRestock >= 300_000; }
    private volatile String phase = "INIT", fact = "no daily-life action has started";
    private volatile int friendId;
    private volatile long friendUntil;
    private volatile boolean partyAsk;
    /** 1.6.0 S8: an unasked friend (a real FRIEND or a guild mate) grinding a map that fits; 0 = none. */
    private volatile int helpId;
    /** 1.6.0 S8: the plan's next step and where it goes (-1: nowhere in particular). */
    private volatile LifePlanner.Next planNext;
    private volatile int planDestination = -1;
    private volatile int channelTarget = -1;
    private volatile boolean channelForKs;
    /** 1.6.0 towns: the town map a free bot is pulled to at this decision ({@link TownPulse}); -1 = none. */
    private volatile int pullTown = -1;
    /** 1.6.1 (ticket 114): a town another bot asked this one to come along to (and it said yes); -1 = none. */
    private volatile int inviteMap = -1;
    private volatile long inviteUntil;
    /** 1.6.1 (ticket 114): a bot that asked this one for help training (and it said yes); 0 = none. */
    private volatile int botHelpId;
    private volatile long botHelpUntil;
    /** 1.6.0 towns: where the last {@link #townDestination()} came from (bot-life.json). */
    private volatile LifeTowns.Source townSource = LifeTowns.Source.HENESYS;

    public <B extends BotSM & LifeHost> LifeRuntime(B bot) {
        this(bot, bot);
    }

    /** 1.6.0 S8: a brain for {@code sm} acting through {@code host} (a light host is a helper the bot owns). */
    public LifeRuntime(BotSM sm, LifeHost host) {
        this.botSm = sm;
        this.bot = host;
        bornAt = System.currentTimeMillis();
        // 1.6.0 S1: one memory per bot for the whole run, whatever bot type it plays; saved by the roster sweep.
        memory = LifeMemoryStore.of(bot.getChr().getId());
        restored = memory.restored();
    }

    public static boolean enabled() {
        return Boolean.getBoolean("slums.botlife.enabled") && Boolean.getBoolean("slums.botlife.execute");
    }

    /**
     * 1.6.0 S8 (brain reach): whether town and social bots run the brain too. On with the brain unless
     * {@code -Dslums.botlife.reach=training} (the 1.6.0-sprint behaviour: training bots only, every director login a
     * training session).
     */
    public static boolean reachesAllBots() {
        return enabled() && !"training".equalsIgnoreCase(System.getProperty("slums.botlife.reach", "all"));
    }
    public LifeMemory memory() { return memory; }

    /** The host the brain acts through (the bot itself, or its light host). */
    public LifeHost host() { return bot; }
    public boolean restored() { return restored; }
    /**
     * Where a HANG_OUT/SHOW_OFF goes: a friend's town, the plan's, a town short of bots ({@link TownPulse}, 1.6.0
     * towns), a hangout fad, else the {@code BOT_LIFE_TOWN_WEIGHTS} pick (near, home, Henesys; shipped: Henesys).
     */
    public int townDestination() {
        long now = System.currentTimeMillis();
        Character friend = friend(now);
        if (friend != null && friend.getMap() != null && friend.getMap().isTown()) return source(friend.getMapId(), LifeTowns.Source.FRIEND);
        // 1.6.1 (ticket 114): a come-along it agreed to, then its crew's pick (friends move towns together)
        int invited = invitedTown(now);
        if (invited > 0) return source(invited, LifeTowns.Source.CREW);
        Character me = bot.getChr();
        int crew = me == null ? -1 : safe(() -> soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews.follow(me, now), -1);
        if (crew > 0) return source(crew, LifeTowns.Source.CREW);
        var next = planNext;
        if (next != null && next.step().activity == Activity.HANG_OUT && planDestination > 0) return source(planDestination, LifeTowns.Source.PLAN);
        if (townDestination != HOME_TOWN) return source(townDestination, LifeTowns.Source.FRIEND);
        if (pullTown > 0) return source(pullTown, LifeTowns.Source.PULL);
        Character chr = bot.getChr();
        // 1.6.0 S9: a hangout fad this bot follows on its channel (friends first, then the plan, then the fad)
        if (chr != null && chr.getMap() != null) {
            int spot = soloMapling.ArtificialPlayer.Fads.spotFor(chr.getId(), chr.getMap().getChannelId());
            if (spot > 0) return source(spot, LifeTowns.Source.FAD);
        }
        LifeTowns.Pick pick = townPick(chr, now);
        return source(pick.mapId(), pick.source());
    }

    private int source(int mapId, LifeTowns.Source source) {
        townSource = source;
        return mapId;
    }

    /** 1.6.0 towns: the {@code BOT_LIFE_TOWN_WEIGHTS} pick; shipped "henesys:100" is Henesys, as before. */
    private static LifeTowns.Pick townPick(Character chr, long now) {
        var server = config.YamlConfig.config == null ? null : config.YamlConfig.config.server;
        double[] weights = weights(server == null ? null : server.BOT_LIFE_TOWN_WEIGHTS);
        if (chr == null || weights[0] <= 0 && weights[1] <= 0) return new LifeTowns.Pick(HOME_TOWN, LifeTowns.Source.HENESYS);
        int level = chr.getLevel();
        int near = weights[0] <= 0 || chr.getMap() == null ? -1
                : safe(() -> LifeTowns.near(chr.getMapId(), chr.getMap().getReturnMapId(), level), -1);
        int home = weights[1] <= 0 ? -1 : safe(() -> {
            var member = BotRoster.loadedMember(chr.getId() - BotStore.BOT_ID_BASE);
            return member == null ? -1 : LifeTowns.home(member.homeMapId(), level);
        }, -1);
        return LifeTowns.pick(weights, near, home, chr.getId(), now);
    }

    private static volatile String weightSpec;
    private static volatile double[] weightCache = {0, 0, 1};

    private static double[] weights(String spec) {
        if (!Objects.equals(spec, weightSpec)) {
            weightCache = LifeTowns.weights(spec);
            weightSpec = spec;
        }
        return weightCache;
    }

    /** Where a chosen PARTY_QUEST goes: the plan's boss/PQ town when that is the next step, else the PQ's lobby. */
    public int pqDestination(int level) {
        var next = planNext;
        if (next != null && next.step().activity == Activity.PARTY_QUEST && planDestination > 0) return planDestination;
        LifePq.Lobby lobby = LifePq.lobbyFor(level);
        return lobby == null ? -1 : lobby.mapId();
    }

    /** Destinations the brain may send the bot to before the map is loaded (it loads on arrival, as for a player). */
    public boolean mayLoad(int mapId) {
        if (mapId <= 0) return false;
        if (mapId == planDestination) return true;
        if (soloMapling.ArtificialPlayer.Fads.isSpot(mapId)) return true; // 1.6.0 S9: a hangout fad's spot
        if (LifeTowns.isTownMap(mapId)) return true; // 1.6.0 towns: a near/home town, or one short of bots
        for (LifePq.Lobby lobby : LifePq.LOBBIES) if (lobby.mapId() == mapId) return true;
        // 1.6.0 fishing: a fishing map is a quiet field nobody stands on, so it is almost never loaded; without this
        // no bot could ever pick FISH (TEST 2026-10-27: 0 of 13,700 life decisions, t-fh-2 saw no bot fishing)
        if (constants.id.MapId.isFishingArea(mapId)) return true;
        return false;
    }

    public static void event(Character chr, LifeMemory.Event event) {
        if (!enabled() || chr == null || !BotHelpers.isBot(chr)) return;
        // Any bot type drifts now (a social bot's wedding counts too); the memory is the same one TrainingBot uses.
        if (LifeMemoryStore.of(chr.getId()).event(event, System.currentTimeMillis())
                && soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                .getBotById(chr.getId()) instanceof LifeHost host && host.lifeRuntime() != null) {
            host.lifeRuntime().changed();
        }
    }
    public void changed() { dirty = true; }
    public synchronized void phase(String value, long now) {
        phase = value;
        dirty = true;
        dueAt = now + (value.equals("GRIND") ? memory.profile().grindDurationMs() : memory.profile().townDurationMs());
        fact = switch (value) {
            case "GRIND" -> "grinding on the current map";
            case "GO_TOWN" -> "travelling toward town; arrival and purchases are not confirmed";
            case "LIFE_TRAVEL" -> "travelling to the chosen destination; not there yet";
            case "LIFE_DWELL", "IN_TOWN" -> "staying on the current map";
            case "SHOP_DWELL" -> "visiting the current shop; do not claim a purchase unless separately confirmed";
            case "BREAK_REST", "BREAK_TRAVEL" -> "taking a grind break";
            default -> "following the existing " + value.toLowerCase(Locale.ROOT).replace('_', ' ') + " routine";
        };
    }

    /**
     * 1.6.1 (ticket 114): this bot agreed to come along to {@code mapId} (a town) with another bot, or its crew is
     * heading there: its next hang-out goes there, and the decision comes soon. Map caps still apply on the way.
     */
    public void invite(int mapId, long until) {
        if (mapId <= 0) return;
        inviteMap = mapId;
        inviteUntil = until;
        dirty = true;
    }

    /** The town a come-along this bot agreed to goes to, or -1 (none, expired, or it is already there). */
    public int invitedTown(long now) {
        int map = inviteMap;
        if (map <= 0 || now >= inviteUntil) return -1;
        Character chr = bot.getChr();
        return chr != null && chr.getMapId() == map ? -1 : map;
    }

    /** Activities a stay that another bot's ask may end (a plain hang-out); anything else is a commitment. */
    private static final Set<Activity> DROPPABLE = EnumSet.of(Activity.HANG_OUT, Activity.SHOW_OFF);
    private static final Set<String> FREE_PHASES = Set.of("GRIND", "IN_TOWN", "LIFE_DWELL");

    /**
     * 1.6.1 say-do (ticket 114: "then they really do it"): why this bot cannot drop what it is doing for something
     * another bot asks of it now (a trip to a town, help on this map), or null when it can. Memory reads only (the talk
     * tick asks it before a bot answers, so a bot that cannot go says no instead of a yes it then breaks): it must be
     * grinding, in town or hanging out (not on a trip, a restock, a PQ or an event), held by nothing (a trade, a
     * player's chat, a recruit, a log-off), with nothing else about to start.
     */
    public String commitBlocker(long now) {
        if (!enabled() || !bot.getRunning()) return "no daily life running";
        Character chr = bot.getChr();
        if (chr == null || chr.getMap() == null) return "not on a map";
        String p = phase;
        if (!FREE_PHASES.contains(p)) return "on its way somewhere (" + p.toLowerCase(Locale.ROOT) + ")";
        var state = bot.lifeActivityState();
        if (state.waiting() != 0) return "about to start something else";
        if (state.active() != 0 && !(p.equals("LIFE_DWELL") && DROPPABLE.contains(state.activity()))) {
            return "in the middle of " + (state.activity() == null ? "something"
                    : state.activity().name().toLowerCase(Locale.ROOT));
        }
        if (safe(() -> soloMapling.ArtificialPlayer.BotConvo.LfgParty.holds(chr.getId()), false)) return "on a PQ trip";
        if (safe(() -> soloMapling.worldevents.WorldEventSteer.mapFor(chr.getId()) > 0, false)) return "at an event";
        if (!bot.freeForAutomaticLifeActivity(now)) return "busy (a commitment, a chat or logging off)";
        return null;
    }

    /**
     * 1.6.1 say-do: this bot said yes to going to {@code town} with another bot (or its crew's leader picked it): the
     * hang-out there is handed to its own life queue now (its update starts the trip), ending a plain hang-out stay
     * first. Before this a yes only tilted the next decision toward the town, and a bot already hanging out kept its
     * stay (TEST 2026-10-28: 7 of 178 come-alongs arrived). False when it cannot go now ({@link #commitBlocker}); the
     * pull stays either way, so a later decision may still take it there.
     */
    public boolean goTogether(int town, long until, long now) {
        if (town <= 0) return false;
        invite(town, until);
        if (commitBlocker(now) != null) return false;
        Character chr = bot.getChr();
        if (chr.getMapId() == town) return true; // already there: it stays with them
        // the host's own start rule (the town has room, the bot may hang out now): checked before the stay is ended
        if (!bot.canStartLifeActivity(Activity.HANG_OUT, now)) return false;
        return handOff(Activity.HANG_OUT);
    }

    /** Until when this bot keeps itself free for something it said yes to (0: nothing promised). */
    private volatile long promisedUntil;

    /**
     * 1.6.1 say-do (b-t-bt-3, TEST 2026-10-29: SilentLuv said yes to a come-along, then its own brain chose GRIND 15 s
     * before the act, so the trip was refused as "on its way somewhere"): this bot just said yes to a friend's ask
     * that its act will carry out in a few seconds (a trip, help, a PQ). Until {@code until} its brain makes no new
     * choice and its stay or grind does not run out ({@link LifeHost#holdLifeActivity}), so it is still free when the
     * act comes. The act clears it; it lapses by itself.
     */
    public void promise(long until) {
        promisedUntil = Math.max(promisedUntil, until);
        bot.holdLifeActivity(until);
    }

    /** Whether a promise holds this bot's brain now. */
    public boolean promised(long now) { return now < promisedUntil; }

    /**
     * 1.6.1 say-do: starts {@code activity} because another bot was promised it (a come-along, an LFG party's PQ trip):
     * a plain hang-out stay is ended first, since the queue starts nothing while an activity is active (TEST
     * 2026-10-28: an LFG pair's second bot, hanging out, never took its PQ trip). False when something else holds it.
     */
    public boolean handOff(Activity activity) {
        var state = bot.lifeActivityState();
        if (state.active() != 0 && !(phase.equals("LIFE_DWELL") && DROPPABLE.contains(state.activity())
                && bot.interruptLifeDwell(state.active()))) return false;
        boolean handed = submit(activity);
        if (handed) promisedUntil = 0; // the promised act is queued: the brain may decide again after it
        return handed;
    }

    /**
     * 1.6.1 say-do: this bot said yes to helping {@code askerId} train on the map they share: it keeps grinding here for
     * the help's length (HELP_FRIEND follows the asker if it moves on). False when it is not grinding.
     */
    public boolean helpHere(int askerId, long until, long now) {
        botHelp(askerId, until);
        if (!phase.equals("GRIND") || commitBlocker(now) != null) return false;
        synchronized (this) {
            dueAt = Math.max(dueAt, until);
        }
        bot.keepLifeActivity(Activity.GRIND, until);
        promisedUntil = 0;
        return true;
    }

    /** The phase the host last reported (GRIND, LIFE_DWELL, LIFE_TRAVEL ...). */
    public String phase() { return phase; }

    private boolean submit(Activity activity) {
        var choice = new Result(List.of(), Optional.of(activity), Outcome.CHOOSE);
        var offer = bot.submitLifeActivity(bot.prepareLifeActivity(choice));
        return offer == LifeActivityQueue.Offer.QUEUED || offer == LifeActivityQueue.Offer.REPLACED;
    }

    /** 1.6.1 (ticket 114): this bot agreed to help {@code askerId} train; HELP_FRIEND goes to it while it lasts. */
    public void botHelp(int askerId, long until) {
        botHelpId = askerId;
        botHelpUntil = until;
        dirty = true;
    }

    /** The bot this one agreed to help, online on its channel on a map that fits, or null. */
    Character botHelpTarget(long now) {
        int id = botHelpId;
        if (id == 0 || now >= botHelpUntil) return null;
        Character chr = bot.getChr();
        Character other = safe(() -> soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage()
                .getCharacterById(id), null);
        return other != null && chr != null && chr.getMap() != null && other.getMap() != null
                && other.getMap().getChannelId() == chr.getMap().getChannelId()
                && other.getMapId() != chr.getMapId() && fits(chr, other.getMapId()) ? other : null;
    }

    public boolean friendAsk(Character player, boolean party, long now) {
        if (!enabled() || player == null || !BotAffinity.friend(bot.getChr().getId(), player.getId())) return false;
        friendId = player.getId(); friendUntil = now + 60_000; partyAsk = party; dirty = true;
        memory.talked(now);
        bot.nudgeSoon(300);
        return true;
    }

    public Character friend(long now) {
        int id = friendId;
        if (id == 0 || now >= friendUntil) return null;
        var chr = bot.getChr();
        var player = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(id);
        return player != null && player.getMap() != null && chr.getMap() != null
                && player.getMap().getChannelId() == chr.getMap().getChannelId()
                && BotAffinity.friend(chr.getId(), id) ? player : null;
    }

    /**
     * 1.6.0 S8: who HELP_FRIEND goes to: the friend who asked, else an unasked friend (a real player the bot is a
     * FRIEND of, or a guild mate bot) grinding a map that fits on this channel. Null when there is nobody.
     */
    public Character helpTarget(long now) {
        Character asked = friend(now);
        if (asked != null) return asked;
        Character askedByBot = botHelpTarget(now); // 1.6.1 (ticket 114)
        if (askedByBot != null) return askedByBot;
        int id = helpId;
        if (id == 0) return null;
        Character chr = bot.getChr();
        Character other = safe(() -> soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage()
                .getCharacterById(id), null);
        return other != null && chr.getMap() != null && other.getMap() != null
                && other.getMap().getChannelId() == chr.getMap().getChannelId()
                && other.getMapId() != chr.getMapId() && fits(chr, other.getMapId()) ? other : null;
    }
    public int requester(long now) { Character player = friend(now); return player == null ? -1 : player.getId(); }
    public boolean partyAsked() { return partyAsk; }

    /** 1.6.0 S8: the channel a chosen CHANGE_CHANNEL moves to (-1: none), and whether it flees a KS war. */
    public int channelTarget() { return channelTarget; }
    public boolean channelForKs() { return channelForKs; }

    public void tick(long now) {
        if (!enabled() || !bot.getRunning()) return;
        if (now < promisedUntil) return; // 1.6.1 say-do: it said yes to a friend; that act decides what is next
        if (!dirty && now < nextCheck && now < dueAt) return;
        nextCheck = now + 30_000;
        if (!Set.of("GRIND", "IN_TOWN", "LIFE_DWELL").contains(phase)) { dirty = false; return; }
        var running = bot.lifeActivityState();
        if (running.waiting() != 0) return;
        // A friend can interrupt a safe town stay, and so can a world event the bot was offered (1.6.0). Trips and
        // other active work retain their owner.
        boolean eventOffer = soloMapling.worldevents.WorldEventSteer.offerFor(bot.getChr().getId(), now) != null;
        if (running.active() != 0 && !(phase.equals("LIFE_DWELL") && (friend(now) != null || eventOffer))) return;
        long began = System.nanoTime();
        try {
            Character chr = bot.getChr();
            scanFriends(chr, now);
            // 1.6.0 S8: goals from game facts (a Bartle aspiration among them), then a plan only if something changed.
            LifeGoals.Facts facts = facts(chr, memory.social());
            questTick(chr, phase.equals("GRIND"), now); // ticket 158: maybe pick a quest for this grind map
            memory.refresh(facts, now);
            memory.plan(world(chr, facts, memory.softFacts(), questFacts()));
            var next = LifePlanner.next(memory.goals(), memory.plans());
            planNext = next;
            planDestination = destination(next, facts);
            var report = LifeObservation.capture(botSm, now);
            var original = report.context();
            Map<Need, Double> needs = new EnumMap<>(Need.class);
            needs.putAll(original.needs().values());
            long socialSince = memory.lastSocial() > 0 ? memory.lastSocial() : bornAt;
            needs.put(Need.SOCIAL, Math.min(1, Math.max(0, now - socialSince)
                    / (double) (SOCIAL_PERIOD_FACTOR * memory.profile().townDurationMs())));
            needs.put(Need.PROGRESS, memory.goals().stream().filter(g -> g.kind().progression())
                    .mapToDouble(LifeGoals.Goal::pressure).max().orElse(0));
            List<String> unknown = new ArrayList<>(original.unknown());
            unknown.remove("time since social activity");
            // Read-only checks: do not call shortage(), which changes the ammo-fallback state.
            try {
                double pressure = needs.getOrDefault(Need.SUPPLIES, 0.0);
                var attack = BotAttackDriver.primaryAttack(chr);
                if (attack != null && BotVitals.mpCost(chr, attack.skillId, BotAttackDriver.packetLevel(chr, attack)) > 0) {
                    pressure = Math.max(pressure, Math.max(0, 1 - BotVitals.potionCount(chr, false) / 20.0));
                }
                if (BotSupplies.shouldRetryAmmoTrip(chr.getId()) && BotSupplies.outOfAmmo(chr)) pressure = 1;
                needs.put(Need.SUPPLIES, pressure);
                unknown.remove("MP/ammo pressure");
            } catch (RuntimeException unavailable) { /* the shadow report continues to mark unavailable supply data */ }
            Current current = bot.lifeCurrent(memory.profile(), now);
            if (current == null) current = original.current();
            if (current != null && !needs.containsKey(Need.BOREDOM)) {
                needs.put(Need.BOREDOM, Math.min(1, Math.max(0, (double) now - current.startedAtMs())
                        / current.expectedDurationMs()));
            }
            bot.lifeNeeds(needs, now); // a light host starts from its own needs
            needs.replaceAll((need, value) -> Math.max(0, Math.min(1, value)));
            // 1.6.0 S8: a crowded map or a KS war here: is there a quieter channel? (Only then are channels read.)
            double crowd = safe(() -> LifeChannelMove.crowdOf(chr), 0.0);
            boolean ksWar = chr.getMap() != null && safe(() -> LifeChannelMove.ksWarHere(chr), false);
            channelForKs = ksWar;
            channelTarget = crowd >= LifePolicy.CROWDED || ksWar ? safe(() -> LifeChannelMove.target(chr), -1) : -1;
            // 1.6.0 towns: at a normal decision point only (the current stay or grind has run its course, nothing in
            // flight, nothing promised) a town short of bots may pull (TownPulse); decided before HANG_OUT's
            // destination is checked for eligibility below.
            TownPulse.Pull pulled = null;
            if (now >= dueAt && running.active() == 0 && TownPulse.on() && chr.getMap() != null
                    && bot.freeForAutomaticLifeActivity(now)) {
                pulled = safe(() -> TownPulse.pullFor(chr.getId(), chr.getMap().getChannelId(), chr.getLevel(),
                        chr.getMapId(), now), null);
            }
            pullTown = pulled == null ? -1 : pulled.mapId();
            Map<Activity, String> eligible = new EnumMap<>(Activity.class);
            for (Activity activity : Activity.values()) if (bot.canStartLifeActivity(activity, now)) {
                eligible.put(activity, "existing " + activity.name().toLowerCase(Locale.ROOT) + " routine");
            }
            if (channelTarget < 0) eligible.remove(Activity.CHANGE_CHANNEL);
            Character friend = friend(now);
            boolean botAsked = botHelpTarget(now) != null; // 1.6.1 (ticket 114): a bot it said yes to
            boolean free = bot.freeForAutomaticLifeActivity(now);
            var context = new LifePolicy.Context(new Needs(needs), eligible, original.friends(), original.strangers(),
                    original.rivals(), friend != null || botAsked, false, !free, current, memory.goals(), unknown);
            var situation = new LifePolicy.Situation(memory.social(),
                    friend == null && eligible.containsKey(Activity.HELP_FRIEND),
                    friend == null && eligible.containsKey(Activity.PARTY_QUEST), crowd, ksWar,
                    planStep(next), next == null ? 0 : next.bonus(), chr.getId());
            long signature = Objects.hash(phase, free, eligible.keySet(), original.friends(), original.rivals(),
                    friend == null ? 0 : friend.getId(), helpId, next == null ? null : next.step(),
                    (int) (crowd * 4), ksWar, needs.entrySet().stream()
                            .map(e -> e.getKey() + ":" + (int) (e.getValue() * 4)).toList());
            if (!dirty && now < dueAt && signature == lastNeedSignature) return;
            dirty = false; lastNeedSignature = signature;
            var kept = now >= dueAt ? null : current;
            double townBias = townBias(chr.getLevel());
            // 1.6.1 (ticket 114): a come-along it agreed to, or its crew heading to a town, pulls like a friend's town
            double crewPull = invitedTown(now) > 0 || townSource == LifeTowns.Source.CREW
                    && safe(() -> soloMapling.ArtificialPlayer.BotAffinitySystem.BotCrews.follow(chr, now), -1) > 0
                    ? CREW_PULL : 0;
            TownPulse.offer(chr.getId(), pulled); // read by TownPulse.BIAS in the scorer
            List<Option> options;
            try {
                options = LifePolicy.options(context, memory.profile(), situation).stream().map(o ->
                        o.activity() == Activity.HANG_OUT || o.activity() == Activity.SHOW_OFF
                        ? new Option(o.activity(), o.weights(), o.contextBonus() + townBias
                                + (townDestination != HOME_TOWN ? .6 : 0) + crewPull, o.personalityBonus(), o.available(),
                                o.reason()) : o).toList();
            } finally {
                TownPulse.offer(chr.getId(), null);
            }
            var result = LifeDecision.evaluate(context.needs(), options, kept,
                    now, LifePolicy.INTERRUPT_MARGIN, .02,
                    a -> new SplittableRandom(chr.getId() * 31L + a.ordinal() * 101L + now / 30_000).nextDouble(-1, 1),
                    context.commitment());
            lastDecision = new DecisionSnapshot(now, new LifeObservation.Report(report.observed(),
                    memory.profile(), context, result, bot.lifeActivityState()));
            decisions.increment();
            result.selected().ifPresent(a -> selectedCounts.computeIfAbsent(a, k -> new LongAdder()).increment());
            Activity doing = current != null ? current.activity() : phase.equals("GRIND") ? Activity.GRIND
                    : phase.equals("IN_TOWN") || phase.equals("LIFE_DWELL") ? Activity.HANG_OUT : null;
            if (result.outcome() == Outcome.CHOOSE && result.selected().isPresent() && result.selected().get() != doing) {
                chosenCounts.computeIfAbsent(result.selected().get(), k -> new LongAdder()).increment();
            }
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
                soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS,
                        "LIFE DECISION bot=" + chr.getName() + " selected=" + result.selected().map(Enum::name).orElse("NONE"));
            }
            if (soloMapling.autoqa.SocialTrace.on()) { // 1.6.0 social trace: what a traced bot decides next
                soloMapling.autoqa.SocialTrace.life(chr.getId(), "DECISION selected="
                        + result.selected().map(Enum::name).orElse("NONE") + " (" + result.outcome() + ")");
            }
            logDecision(chr, result);
            if (result.selected().isPresent() && now >= dueAt
                    && (phase.equals("GRIND") && result.selected().get() == Activity.GRIND
                    || phase.equals("LIFE_DWELL") && result.selected().get() == Activity.HANG_OUT)) {
                dueAt = now + (phase.equals("GRIND") ? memory.profile().grindDurationMs() : memory.profile().townDurationMs());
                bot.keepLifeActivity(result.selected().get(), dueAt);
            }
            if (result.outcome() == Outcome.CHOOSE && result.selected().isPresent()
                    && !(phase.equals("GRIND") && result.selected().get() == Activity.GRIND)
                    && !(phase.equals("LIFE_DWELL") && result.selected().get() == Activity.HANG_OUT)) {
                if (running.active() != 0 && (!Set.of(Activity.HELP_FRIEND, Activity.PARTY_QUEST, Activity.EVENT)
                        .contains(result.selected().get()) || !bot.interruptLifeDwell(running.active()))) return;
                bot.submitLifeActivity(bot.prepareLifeActivity(result));
            }
        } finally { cpuNanos.add(System.nanoTime() - began); reportIfDue(now); }
    }

    /** The activity the plan's next step leans toward; none for a town plan step on a town-only host (it is there). */
    private Activity planStep(LifePlanner.Next next) {
        if (next == null) return null;
        return bot.townOnly() && next.step().activity == Activity.HANG_OUT ? null : next.step().activity;
    }

    /** 1.6.1 (ticket 114): the hang-out pull of an agreed come-along or the crew's town (a friend's town is .6). */
    static final double CREW_PULL = .7;

    /** Low levels hang around town more (2009 Henesys); 1.6.0 S8 halved the pull (audit finding 4). */
    static double townBias(int level) {
        return level <= 30 ? .25 : level <= 70 ? .15 : .05;
    }

    /** Audit finding 5: DEBUG for every decision, INFO for one in {@link #LOG_SAMPLE} with the running mix. */
    private void logDecision(Character chr, Result result) {
        if (log.isDebugEnabled()) {
            log.debug("[BotLife] DECISION bot={} phase={} selected={} outcome={} top={}", chr.getId(), phase,
                    result.selected(), result.outcome(), top(result));
        }
        if (decisions.sum() % LOG_SAMPLE == 0 && log.isInfoEnabled()) {
            Map<String, Long> mix = new TreeMap<>();
            selectedCounts.forEach((activity, count) -> mix.put(activity.name(), count.sum()));
            log.info("[BotLife] DECISION sample 1/{} bot={} phase={} selected={} outcome={} top={} mix={} chosen={}",
                    LOG_SAMPLE, chr.getId(), phase, result.selected(), result.outcome(), top(result), mix, chosen());
        }
    }

    private static List<String> top(Result result) {
        return result.topThree().stream()
                .map(s -> s.activity() + ":" + String.format(Locale.ROOT, "%.2f", s.total())).toList();
    }

    /**
     * Friends nearby, at most 32 known players and 24 guild mates looked at: a friend in a town on this channel makes
     * that town the destination; an unasked friend grinding a map that fits is someone to help (1.6.0 S8).
     */
    private void scanFriends(Character chr, long now) {
        townDestination = HOME_TOWN;
        int help = 0;
        int sampled = 0;
        for (int playerId : BotAffinity.knownPlayers()) {
            if (++sampled > 32) break;
            if (!BotAffinity.friend(chr.getId(), playerId)) continue;
            var player = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(playerId);
            if (player == null || player.getMap() == null || chr.getMap() == null
                    || player.getMap().getChannelId() != chr.getMap().getChannelId()) continue;
            if (player.getMap().isTown()) {
                if (townDestination == HOME_TOWN) townDestination = player.getMapId();
            } else if (help == 0 && player.getMapId() != chr.getMapId() && fits(chr, player.getMapId())) {
                help = player.getId();
            }
        }
        if (help == 0 && chr.getGuildId() > 0 && chr.getMap() != null) {
            help = safe(() -> guildMateToHelp(chr), 0);
        }
        helpId = help;
    }

    private static int guildMateToHelp(Character chr) {
        int seen = 0;
        for (Character mate : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.onlineMembers(chr.getGuildId())) {
            if (++seen > 24) break;
            if (mate.getId() == chr.getId() || mate.getMap() == null
                    || mate.getMap().getChannelId() != chr.getMap().getChannelId()
                    || mate.getMapId() == chr.getMapId() || Math.abs(mate.getLevel() - chr.getLevel()) > 20) continue;
            if (soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(mate.getId())
                    instanceof soloMapling.ArtificialPlayer.BotTypes.TrainingBot grinder && grinder.isGrinding()
                    && fits(chr, mate.getMapId())) {
                return mate.getId();
            }
        }
        return 0;
    }

    /** A field whose mobs suit this bot (within 20 levels). */
    static boolean fits(Character chr, int mapId) {
        int mob = safe(() -> soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.level(mapId), -1);
        return mob >= 0 && Math.abs(mob - chr.getLevel()) <= 20;
    }

    /** The goal facts, memory-only; anything the game can't answer right now stays at its quiet default. */
    static LifeGoals.Facts facts(Character chr, SocialProfile social) {
        OptionalInt weapon = safe(() -> LifeObservation.weaponLevel(chr), OptionalInt.empty());
        if (weapon == null) weapon = OptionalInt.empty();
        int level = Math.max(1, Math.min(200, chr.getLevel()));
        int job = chr.getJob() == null ? 0 : Math.max(0, chr.getJob().getId());
        long meso = safe(() -> (long) chr.getMeso(), 0L);
        boolean guild = safe(() -> chr.getGuildId() > 0, false);
        var couple = safe(() -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.coupleOf(chr.getId()), null);
        boolean married = safe(chr::isMarried, false) || couple != null
                && couple.stage() == soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Stage.MARRIED;
        boolean zhelm = safe(() -> wears(chr, LifeGoals.ZHELM), false);
        LifeGoals.FarmWant farm = null;
        if (soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstAdvisor.enabled()) {
            // Ticket 162 part 2: the drop its last gear-first map choice wanted most becomes a FARM_ITEM aspiration.
            var want = soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstAdvisor.want(chr.getId());
            if (want != null) farm = new LifeGoals.FarmWant(want.itemId(), want.name(), want.desirability());
        }
        return new LifeGoals.Facts(level, job, weapon, meso, guild, married, zhelm, social, farm);
    }

    static LifePlanner.World world(Character chr, LifeGoals.Facts facts, Set<LifePlanner.Fact> soft) {
        return world(chr, facts, soft, Set.of());
    }

    static LifePlanner.World world(Character chr, LifeGoals.Facts facts, Set<LifePlanner.Fact> soft,
                                   Set<LifePlanner.Fact> quest) {
        boolean friend = safe(() -> chr.getBuddylist() != null && !chr.getBuddylist().getBuddies().isEmpty(), false);
        var couple = safe(() -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.coupleOf(chr.getId()), null);
        boolean crush = couple != null && couple.stage().couple() || safe(() -> chr.getPartnerId() > 0, false)
                || safe(() -> soloMapling.ArtificialPlayer.BotCoupleSystem.BotCrushes.hasCrush(chr.getId()), false); // S7
        boolean ring = couple != null && couple.stage()
                == soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.Stage.MARRIED
                || safe(() -> chr.getMarriageRing() != null, false);
        return new LifePlanner.World(facts, friend, crush, ring, soft, quest);
    }

    private static boolean wears(Character chr, int itemId) {
        var inventory = chr.getInventory(client.inventory.InventoryType.EQUIPPED);
        if (inventory == null) return false;
        inventory.lockInventory();
        try {
            var hat = inventory.getItem((short) -1);
            return hat != null && hat.getItemId() == itemId;
        } finally {
            inventory.unlockInventory();
        }
    }

    /** Game reads that may be missing in a test or before a system is up never stop a decision. */
    static <T> T safe(Supplier<T> read, T fallback) {
        try {
            T value = read.get();
            return value == null ? fallback : value;
        } catch (RuntimeException | LinkageError unavailable) {
            return fallback;
        }
    }

    /**
     * 1.6.0 S8: the host's activity queue reports how a started activity ended. A COMPLETED step of the current plan
     * makes its soft fact true; every outcome goes to {@link LifeOutcomes} (S9 learning, S6 initiative).
     */
    public void ended(Activity activity, LifeActivityQueue.End end) {
        if (activity == null || end == null || end == LifeActivityQueue.End.NONE) return;
        long now = System.currentTimeMillis();
        boolean success = end == LifeActivityQueue.End.COMPLETED;
        var next = planNext;
        if (success && next != null && next.step().activity == activity
                && LifePlanner.SOFT.contains(next.step().effect) && memory.soften(next.step().effect)) {
            dirty = true; // the plan moves on at the next decision
        }
        Character chr = bot.getChr();
        LifeOutcomes.report(chr == null ? 0 : chr.getId(), activity, success, now, end.name().toLowerCase(Locale.ROOT));
    }

    /**
     * {@code !qa bl-11}: refreshes the goals and the plan now (off the bot's tick: the memory is synchronized), so a
     * staged goal shows in {@code !botwhy} at once. The next decision uses it as any plan.
     */
    public void planNow(long now) {
        Character chr = bot.getChr();
        LifeGoals.Facts facts = facts(chr, memory.social());
        memory.refresh(facts, now);
        memory.plan(world(chr, facts, memory.softFacts(), questFacts()));
        var next = LifePlanner.next(memory.goals(), memory.plans());
        planNext = next;
        planDestination = destination(next, facts);
        dirty = true;
    }

    /** Where the plan's next step goes: a quest step to its NPC's or hunt map (ticket 158), else the planner's town. */
    private int destination(LifePlanner.Next next, LifeGoals.Facts facts) {
        if (next == null) return -1;
        if (soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests.questStep(next.step())) {
            return memory.quests(log -> soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests.destination(log, next.step()));
        }
        return LifePlanner.destination(next.step(), facts, next.plan().target());
    }

    /** Ticket 158: the quest facts for the planner (none with the switch off). */
    private Set<LifePlanner.Fact> questFacts() {
        if (!soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests.enabled()) return Set.of();
        return memory.quests(soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests::facts);
    }

    /** Ticket 158: real kill progress into the log, and while grinding maybe a quest for this map. */
    private void questTick(Character chr, boolean grinding, long now) {
        if (!bot.runsQuests() || !soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests.enabled()) return;
        safe(() -> memory.quests(log -> {
            soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests.syncReal(chr, log);
            return soloMapling.ArtificialPlayer.BotQuestSystem.BotQuests.consider(chr, log, grinding,
                    bot.questHomeMap(), now);
        }), false);
    }

    /** The plan's next step, or null (for {@code !botwhy} and the hosts). */
    public LifePlanner.Next planNext() { return planNext; }
    public int planDestination() { return planDestination; }

    /** The lines {@code !botwhy} shows for the goals and their plans. */
    public List<String> describePlan() {
        List<String> lines = new ArrayList<>();
        var goals = memory.goals();
        var plans = memory.plans();
        for (int i = 0; i < goals.size(); i++) {
            var goal = goals.get(i);
            String plan = i < plans.size() ? plans.get(i).describe() : goal.kind() + ": not planned yet";
            lines.add(String.format(Locale.ROOT, "Plan %s (pressure %.2f): %s", goal.description(), goal.pressure(), plan));
        }
        var next = planNext;
        lines.add(next == null ? "Next step: none (the brain follows its needs)"
                : String.format(Locale.ROOT, "Next step: %s -> %s, bonus %.2f%s", next.step().describe(next.plan().target()),
                next.step().activity, next.bonus(), planDestination > 0 ? ", destination map " + planDestination : ""));
        if (!memory.softFacts().isEmpty()) lines.add("Done steps the game can't see: " + memory.softFacts());
        String quest = soloMapling.ArtificialPlayer.BotQuestSystem.BotQuestChat.describe(memory);
        if (quest != null) lines.add(quest); // ticket 158
        return List.copyOf(lines);
    }

    /** QA seam: the channel a staged CHANGE_CHANNEL goes to. */
    public void channelTarget(int channel) { channelTarget = channel; }

    public void accepted(Activity activity, long now) {
        accepted(activity, now, true);
    }

    /** As {@link #accepted(Activity, long)}; {@code announce} false keeps the bot quiet (a light host's switches). */
    public synchronized void accepted(Activity activity, long now, boolean announce) {
        Character requestPlayer = activity == Activity.HELP_FRIEND || activity == Activity.PARTY_QUEST ? friend(now) : null;
        accepted.increment();
        if (activity == Activity.RESTOCK) lastRestock = now;
        activityCounts.computeIfAbsent(activity, a -> new LongAdder()).increment();
        if (activity == Activity.HELP_FRIEND || activity == Activity.PARTY_QUEST) { friendId = 0; friendUntil = 0; }
        // 1.6.0 S8: time with people fills the SOCIAL need (before, only a friend's ask did, so HANG_OUT always won).
        if (Set.of(Activity.HANG_OUT, Activity.SHOW_OFF, Activity.HELP_FRIEND, Activity.PARTY_QUEST, Activity.FISH,
                Activity.FREE_MARKET).contains(activity)) memory.talked(now);
        if (activity == Activity.HANG_OUT || activity == Activity.SHOW_OFF) townAccepted(now);
        if ((activity == Activity.FREE_MARKET || activity == Activity.SELL) && pullTown > 0 && bot.getChr() != null
                && bot.getChr().getMap() != null && LifeTowns.townOf(pullTown) != null
                && LifeTowns.townOf(pullTown).mapId() == LifeTowns.FREE_MARKET) { // a visitor the FM pulled
            TownPulse.consume(bot.getChr().getMap().getChannelId(), LifeTowns.FREE_MARKET, now);
        }
        String line = switch (activity) {
            case RESTOCK -> "brb, restocking";
            case HANG_OUT, SHOW_OFF -> "gonna hang around town for a bit";
            case FREE_MARKET, SELL -> "heading to the market for a bit";
            case MOVE_MAP -> "gonna try another training spot";
            case HELP_FRIEND -> "omw to help";
            case BREAK -> "taking a quick break";
            case FISH -> "gonna try fishing for a bit";
            case PARTY_QUEST -> requestPlayer != null ? "ready when you are" : "gonna go find a pq";
            case EVENT -> soloMapling.worldevents.WorldEventReactions.omwLine(bot.getChr()); // 1.6.0 world events
            default -> null; // CHANGE_CHANNEL says its own "cc" (LifeChannelMove)
        };
        if (!announce) return;
        if (requestPlayer != null && line != null) {
            BotWhispers.send(requestPlayer, bot.getChr().getName(), bot.getChr().getMap().getChannelId() - 1, line, true);
            return;
        }
        if (now - lastAnnouncement < 300_000 || !soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(bot.getChr().getMapId())) return;
        if (line != null) {
            lastAnnouncement = now;
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat(bot.getChr(), line);
        }
    }

    /** 1.6.0 towns: a hang-out started: where it came from, and a pulled one spends its town's budget. */
    private void townAccepted(long now) {
        int destination = townDestination();
        LifeTowns.Source source = townSource;
        TownPulse.hangOut(source);
        Character chr = bot.getChr();
        if (source == LifeTowns.Source.PULL && chr != null && chr.getMap() != null) {
            TownPulse.consume(chr.getMap().getChannelId(), destination, now);
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
                soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS,
                        "TOWN PULL bot=" + chr.getName() + " town=" + destination);
            }
        }
    }

    /** QA/test seam: the town a decision was pulled to. */
    void pullTown(int mapId) { pullTown = mapId; }

    public synchronized void fishingStarted() {
        fact = "seated on a fishing chair at the water; no catch or reward is confirmed by seating";
    }

    public synchronized String prompt() {
        return enabled() ? "\nDaily-life facts: " + fact + ". Goals: "
                + memory.goals().stream().map(LifeGoals.Goal::description).toList()
                + ". These are goals, not completed achievements. Never claim arrival, buying, selling or rewards without a confirmed event.\n"
                + soloMapling.ArtificialPlayer.BotQuestSystem.BotQuestChat.promptFact(memory) : "";
    }
    public static Map<String, Long> metrics() {
        return Map.of("decisions", decisions.sum(), "accepted", accepted.sum(), "cpuNanos", cpuNanos.sum(),
                "replans", LifePlanner.replans());
    }

    /** What decisions selected per activity this run (kept or chosen). */
    public static Map<String, Long> selected() {
        Map<String, Long> out = new TreeMap<>();
        selectedCounts.forEach((activity, count) -> out.put(activity.name(), count.sum()));
        return out;
    }

    /** Transitions per activity this run (see {@link #chosenCounts}). */
    public static Map<String, Long> chosen() {
        Map<String, Long> out = new TreeMap<>();
        chosenCounts.forEach((activity, count) -> out.put(activity.name(), count.sum()));
        return out;
    }

    private static void reportIfDue(long now) {
        if ("false".equalsIgnoreCase(System.getProperty("slums.botlife.metrics", "true"))) return;
        long due = nextReport.get();
        if (now < due || !nextReport.compareAndSet(due, now + 60_000)) return;
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            try {
                Map<String, Object> data = new LinkedHashMap<>(metrics());
                data.put("at", now);
                data.put("onlineBots", soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().size());
                Map<String, Long> activities = new TreeMap<>();
                activityCounts.forEach((activity, count) -> activities.put(activity.name(), count.sum()));
                data.put("activities", activities);
                data.put("selected", selected());
                data.put("chosen", chosen());
                int[] bands = new int[3];
                for (var channel : soloMapling.server.SoloMaplingUtilities.world().getChannels()) {
                    var factory = channel.getMapFactory();
                    if (!factory.isMapLoaded(100000000)) continue;
                    for (Character chr : factory.getMap(100000000).getAllPlayers()) if (BotHelpers.isBot(chr)) {
                        bands[chr.getLevel() <= 30 ? 0 : chr.getLevel() <= 70 ? 1 : 2]++;
                    }
                }
                data.put("henesysLevels", Map.of("1-30", bands[0], "31-70", bands[1], "71+", bands[2]));
                data.put("sayDo", soloMapling.ArtificialPlayer.BotConvo.SayDo.stats());
                data.put("nameGuard", soloMapling.ArtificialPlayer.BotConvo.NameGuard.counts()); // S4: made-up names caught
                data.put("learning", LifeLearning.report(now)); // 1.6.0 S9: drift, crossings, fads, trends
                data.put("towns", TownPulse.report()); // 1.6.0 towns: pull counters, bots vs targets
                data.put("botTalk", soloMapling.ArtificialPlayer.BotConvo.BotTalk.stats()); // 1.6.1 ticket 114, cpuNanos
                var file = java.nio.file.Path.of("data/autoqa/bot-life.json");
                java.nio.file.Files.createDirectories(file.getParent());
                var temporary = file.resolveSibling("bot-life.json.tmp");
                java.nio.file.Files.writeString(temporary, soloMapling.server.Json.write(data));
                java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (RuntimeException | java.io.IOException failure) { log.warn("Bot life report unavailable: {}", failure.getClass().getSimpleName()); }
        });
    }
}
