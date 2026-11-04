package soloMapling.ArtificialPlayer.BotSocialSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondStore;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotConvo.RelationshipCard;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch;
import soloMapling.ArtificialPlayer.BotLifeSystem.ModeSwitch.Mode;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions.ActEvent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialTestKit.MemoryWorld;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialTestKit.SimFlows;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.FakeMemoryTable;
import soloMapling.server.Json;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 271 stage 7: the Henesys pilot's simulated week. Twenty roster bots chosen by the real {@link SocialPilot#select}
 * live {@code days} simulated days on the {@link SocialSim} clock in a fake Henesys (four maps), with every class of the
 * social layer real: {@link SocialMind} (through {@code LifeMemory}), {@code ModeSwitch.decide} (with the real
 * {@link SocialRoutine} bias), {@link SocialRoutine#spotFor} (through {@link SocialPilot#destination}),
 * {@link SocialDecider}, {@link SocialActions}, {@link SocialBudget}, {@link SocialGroups}, {@code BotBonds} (axes, stages)
 * over a memory store, the real {@code BotMemory} over {@code FakeMemoryTable}, the intent book, and {@code BotAffinity} for
 * the visiting players. What is faked is the game: positions, who is busy, the sampling of "who stood near whom"
 * ({@code BotBonds}' own private pair sampling is replayed over ids with the same caps), a chat flow that leaves a THREAD
 * bond, the needs the grind and the hang-out satisfy, and the mapping from needs to {@code ModeSwitch.Inputs}.
 *
 * <p>One minute per step. A simulated server restart (optional) saves every bot's {@code LifeMemory}, the bonds through
 * their real flush, drops all RAM, rebuilds from the saved rows and continues.
 */
final class SocialPilotSim {

    static final long MIN = 60_000L, HOUR = 3_600_000L, DAY = 86_400_000L;
    static final long T0 = 100L * DAY; // midnight UTC
    static final int FAMILIAR = 5001, STRANGER = 5002;
    static final int BOTS = 20;

    // ── the run's configuration ──
    final long seed;
    final int days;
    final boolean pilotOn;
    final boolean restart;
    /** Day index (0-based) and hour of the simulated restart. */
    static final int RESTART_DAY = 3, RESTART_HOUR = 12;

    // ── the fake game ──
    final AtomicLong clock = new AtomicLong(T0);
    MemoryWorld world;
    SimFlows flows;
    FakeMemoryTable memoryTable;
    BondStore.Memory bondStore;
    AffinityStore.Memory affinityStore;
    final Map<String, String> savedRows = new LinkedHashMap<>(); // the botlifeprofile rows: slot -> JSON
    final Map<Integer, Mode> savedModes = new HashMap<>();       // BotRoster.resumeChoice: what mode a bot logs back in as
    int[] ids;
    int[] maps;
    final Map<Integer, Bot> bots = new LinkedHashMap<>();
    Random env;

    static final class Bot {
        int id, slot, windowStart, windowLen;
        Mode mode = Mode.TRAINING;
        long modeSince;
        final long[] stamps = new long[ModeSwitch.MAX_PER_HOUR];
        int next;
        boolean online;
        long loginAt, logoutAt;
        long placedBucket = -1;
        int mapId;

        int lastHour(long now) {
            int n = 0;
            for (long t : stamps) {
                if (t != 0 && now - t < HOUR) n++;
            }
            return n;
        }
    }

    // ── what the run records ──
    final List<ActEvent> acts = new ArrayList<>();
    final int[] landedByDay, carriedByDay;
    final boolean[] playerOnDay;
    final int[][] place; // [bot][day*24+hour]: 0 offline, 1 training, 2+ town map index
    final Map<Long, int[]> pairLedger = new HashMap<>(); // unordered pair -> {near, spend, converse, other}
    final Map<Long, long[]> firstFriend = new TreeMap<>(); // directed edge -> {minute, near, spend, converse}
    final List<String> meaningful = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final Map<String, Object> restartReport = new LinkedHashMap<>();
    final List<Visit> visits = new ArrayList<>();
    int modeSwitches;
    int holdsApplied;
    int groupsPeak;
    final List<int[]> failures = new ArrayList<>(); // not-landed carried-out acts: {actor, target, kind, minute}

    record Visit(int day, int startHour, int minutes, boolean familiar, boolean stranger) {
    }

    static final List<Visit> VISITS = List.of(new Visit(1, 14, 40, true, false), new Visit(3, 20, 30, true, true),
            new Visit(5, 13, 45, true, true));

    /** What one bot did with one visiting player at one moment: the greeting inputs and what they produced. */
    record Reaction(int visit, int bot, int player, AffinityState state, double score, String prompt, String card) {
    }

    final List<Reaction> reactions = new ArrayList<>();
    final Map<Integer, int[]> friendedByPlayer = new LinkedHashMap<>(); // bot -> {events it took to reach FRIEND}

    SocialPilotSim(long seed, int days, boolean pilotOn, boolean restart) {
        this.seed = seed;
        this.days = days;
        this.pilotOn = pilotOn;
        this.restart = restart && days > RESTART_DAY;
        this.landedByDay = new int[days];
        this.carriedByDay = new int[days];
        this.playerOnDay = new boolean[days];
        this.place = new int[BOTS][days * 24];
        for (Visit v : VISITS) {
            if (v.day() < days) {
                playerOnDay[v.day()] = true;
            }
        }
    }

    // ── set up and tear down ──

    void start() {
        System.setProperty(SocialPilot.SWITCH, String.valueOf(pilotOn));
        SocialSim.useForTest(clock::get, seed);
        SocialRoutine.resetForTest();
        SocialRoutine.useZoneForTest(ZoneOffset.UTC);
        SocialPilot.resetForTest();
        SocialGroups.useForTest(null);
        SocialBudget.resetForTest();
        SocialMetrics.resetActCountersForTest();
        SocialDecider.resetForTest();
        LifeMemoryStore.useConnectionSourceForTest(null);
        bondStore = new BondStore.Memory();
        BotBonds.useForTest(bondStore, Runnable::run, clock::get, null, (id, p) -> { }, id -> null);
        memoryTable = new FakeMemoryTable(false);
        memoryTable.install();
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        affinityStore = new AffinityStore.Memory();
        BotAffinity.useForTest(affinityStore, Runnable::run, clock::get, id -> true);
        world = new MemoryWorld(clock::get);
        flows = new SimFlows(world);
        SocialActions.useForTest(world, flows, GiftSeam.AFFINITY_ONLY);
        SocialActions.observer = this::onAct;
        wireIntents();
        env = new Random(seed * 7919 + 13);
        maps = SocialPilot.maps();
        List<Integer> everyone = new ArrayList<>();
        for (int s = 1; s <= 200; s++) {
            everyone.add(s);
        }
        List<Integer> chosen = SocialPilot.select(everyone, BOTS, List.of());
        ids = new int[BOTS];
        for (int i = 0; i < BOTS; i++) {
            Bot b = new Bot();
            b.slot = chosen.get(i);
            b.id = BotStore.BOT_ID_BASE + b.slot;
            ids[i] = b.id;
            b.windowStart = (8 + (int) (6 * SocialRoutine.unit(b.slot, 1))) * 60;
            b.windowLen = (11 + (int) (4 * SocialRoutine.unit(b.slot, 2))) * 60;
            bots.put(b.id, b);
            LifeMemoryStore.of(b.id); // its life memory is in use: the mind exists
        }
        if (pilotOn) {
            SocialPilot.setMembersForTest(Arrays.stream(ids).boxed().toList());
        }
        SocialPilot.useProbeForTest(new SocialPilot.Probe() {
            @Override
            public boolean online(int charId) {
                Bot b = bots.get(charId);
                return b != null && b.online;
            }

            @Override
            public String mode(int charId) {
                Bot b = bots.get(charId);
                return b == null ? null : b.mode.name().toLowerCase(java.util.Locale.ROOT);
            }
        });
    }

    void stop() {
        System.clearProperty(SocialPilot.SWITCH);
        SocialActions.useForTest(null, null, null);
        SocialIntents.resetForTest();
        SocialGroups.useForTest(null);
        SocialRoutine.resetForTest();
        SocialPilot.resetForTest();
        SocialSim.reset();
        BotBonds.useForTest(null, null, null, null, null, null);
        BotAffinity.useForTest(null, null, null, null);
        BotMemory.useForTest(null, null, 0);
        FakeMemoryTable.uninstall();
        LifeMemoryStore.useConnectionSourceForTest(null);
        SocialBudget.resetForTest();
        SocialMetrics.resetActCountersForTest();
    }

    private void wireIntents() {
        SocialIntents.resetForTest();
        SocialIntents.useForTest(new SocialIntents.World() {
            @Override
            public SocialIntents.Presence presence(int charId) {
                SocialWorld.Body b = world.body(charId);
                return b == null ? null : new SocialIntents.Presence(b.mapId(), 1);
            }

            @Override
            public boolean reachable(int actorId, int mapId) {
                return world.body(actorId) != null;
            }

            @Override
            public boolean free(int actorId, long now) {
                return world.free(actorId);
            }

            @Override
            public boolean mayAim(int actorId, int targetId, SocialActKind kind) {
                return targetId >= BotStore.BOT_ID_BASE;
            }

            @Override
            public String name(int charId) {
                return "Bot" + (charId - BotStore.BOT_ID_BASE);
            }
        }, new SocialIntents.Consequences() {
            @Override
            public void kept(SocialIntent in, long now) {
                BotBonds.promise(in.target(), in.actor(), true, now);
                SocialActions.mind(in.actor(), m -> {
                    m.feel(Emotion.PRIDE, 0.12, now);
                    m.feel(Emotion.HAPPINESS, 0.05, now);
                });
            }

            @Override
            public void broken(SocialIntent in, long now) {
                BotBonds.promise(in.target(), in.actor(), false, now);
                world.remember(in.target(), in.actor(), BotMemory.Kind.BROKEN, "said they would hang out but never did", -0.6,
                        in.mapId(), now);
                SocialActions.mind(in.actor(), m -> {
                    m.feel(Emotion.ANXIETY, 0.15, now);
                    m.feel(Emotion.EMBARRASSMENT, 0.2, now);
                });
            }
        }, null);
        SocialActions.wireIntents();
    }

    // ── the clock ──

    long now() {
        return clock.get();
    }

    int day() {
        return (int) ((now() - T0) / DAY);
    }

    int minuteOfDay() {
        return (int) (((now() - T0) % DAY) / MIN);
    }

    // ── the run ──

    void run() {
        int steps = days * 24 * 60;
        for (int step = 0; step < steps; step++) {
            clock.addAndGet(MIN);
            checkUp(now());
            if (restart && day() == RESTART_DAY && minuteOfDay() == RESTART_HOUR * 60) {
                doRestart();
            }
            minute();
        }
    }

    private boolean downForRestart; // while true nobody is online (the server is restarting)

    private void minute() {
        long now = now();
        int minuteOfDay = minuteOfDay();
        updateSessions(now, minuteOfDay);
        for (Bot b : bots.values()) {
            if (!b.online) {
                continue;
            }
            if ((now / MIN + b.slot) % 5 == 0) {
                maybeSwitchMode(b, now);
            }
            living(b, now);
            placeInTown(b, now);
        }
        visits(now);
        world.busy.clear();
        for (Bot b : bots.values()) {
            if (b.online && env.nextDouble() < 0.10) {
                world.busy.add(b.id);
            }
        }
        sampleBonds(now);
        // events that are not decisions: one-off happenings the bots react to
        scriptedEvents(now);
        friction(now);
        SocialDecider.upkeep(now);
        Map<Integer, List<Integer>> byMap = new TreeMap<>();
        for (Bot b : bots.values()) {
            SocialWorld.Body body = world.body(b.id);
            if (b.online && body != null) {
                byMap.computeIfAbsent(body.mapId(), k -> new ArrayList<>()).add(b.id);
            }
        }
        for (List<Integer> here : byMap.values()) {
            if (here.size() >= 2) {
                SocialDecider.onMap(here.stream().mapToInt(Integer::intValue).toArray(), now);
            }
        }
        for (Bot b : bots.values()) {
            if (b.online) {
                SocialIntents.tick(b.id, now);
            }
        }
        int hour = Math.min(days * 24 - 1, (int) ((now - T0) / HOUR));
        if (minuteOfDay % 60 == 30) {
            for (int i = 0; i < BOTS; i++) {
                Bot b = bots.get(ids[i]);
                place[i][hour] = !b.online ? 0 : b.mode == Mode.TRAINING ? 1 : 2 + mapIndex(b.mapId);
            }
        }
        if (minuteOfDay % 10 == 0) {
            watchFriendships(now);
            if (pilotOn) {
                SocialPilot.scan(now);
                groupsPeak = Math.max(groupsPeak, SocialGroups.current(now).groups().size());
            }
        }
    }

    private int mapIndex(int mapId) {
        for (int i = 0; i < maps.length; i++) {
            if (maps[i] == mapId) return i;
        }
        return 0;
    }

    // ── sessions (the population director's side) ──

    /**
     * Who is online. Pilot on: a pilot bot is online across its whole play window (the director's hold). Pilot off: a
     * bot plays sessions of {@code 150} minutes inside its window and rests {@code 90} between them (an ordinary
     * roster bot's session length, the way the director ends sessions).
     */
    private void updateSessions(long now, int minuteOfDay) {
        for (Bot b : bots.values()) {
            boolean inWindow = ((minuteOfDay - b.windowStart) % 1440 + 1440) % 1440 < b.windowLen;
            boolean want;
            if (downForRestart) {
                want = false;
            } else if (pilotOn) {
                want = SocialPilot.holds(b.id, b.windowStart, b.windowLen, minuteOfDay); // the director's hold: the whole window
                if (want) {
                    holdsApplied++;
                }
            } else if (b.online) {
                want = inWindow && now - b.loginAt < 150 * MIN;
            } else {
                want = inWindow && now - b.logoutAt >= 90 * MIN;
            }
            if (want && !b.online) {
                login(b, now);
            } else if (!want && b.online) {
                logout(b, now);
            }
        }
    }

    private void login(Bot b, long now) {
        b.online = true;
        b.loginAt = now;
        b.mode = savedModes.getOrDefault(b.id, Mode.TRAINING); // BotRoster.resumeChoice: a switched mode survives a restart
        savedModes.remove(b.id);
        b.modeSince = now;
        b.placedBucket = -1;
        if (b.mode == Mode.TRAINING) {
            world.bodies.remove(b.id);
        }
    }

    private void logout(Bot b, long now) {
        b.online = false;
        b.logoutAt = now;
        world.bodies.remove(b.id);
        world.busy.remove(b.id);
    }

    // ── the daily life ──

    /** The fake of the brain's needs: what the grind and the hang-out do to the mind. */
    private void living(Bot b, long now) {
        if (b.mode == Mode.TRAINING) {
            SocialActions.mind(b.id, m -> m.satisfy(Need.ACHIEVEMENT, 0.010, now));
        } else {
            SocialActions.mind(b.id, m -> m.satisfy(Need.REST, 0.004, now));
        }
    }

    private void maybeSwitchMode(Bot b, long now) {
        SocialContext.Self self = SocialActions.selfOf(b.id, now);
        double ambition = self.t(Trait.AMBITION);
        ModeSwitch.Inputs in = new ModeSwitch.Inputs(b.mode, now - b.modeSince, dwell(b.id), b.lastHour(now),
                self.n(Need.COMPANIONSHIP), 0.7 * self.n(Need.ACHIEVEMENT), self.n(Need.REST),
                Math.min(1, (now - b.modeSince) / (2.0 * HOUR)), 0.2 + 0.8 * ambition, self.t(Trait.SOCIABILITY),
                self.t(Trait.CURIOSITY), SocialDecider.socialPull(b.id, now));
        Random rng = SocialSim.random(b.id, "mode-switch");
        Mode target = ModeSwitch.decide(in, rng.nextDouble(), rng.nextDouble(), SocialRoutine.modeBias(b.id, now));
        if (target == null) {
            return;
        }
        b.stamps[b.next] = now;
        b.next = (b.next + 1) % b.stamps.length;
        b.mode = target;
        b.modeSince = now;
        b.placedBucket = -1;
        modeSwitches++;
        if (target == Mode.TRAINING) {
            world.bodies.remove(b.id);
        }
    }

    /** The per-bot minimum stay of {@code ModeSwitch.dwellFor} (the same formula; that method is package-private). */
    private static long dwell(int botId) {
        long h = botId * 0x9E3779B97F4A7C15L;
        double unit = ((h ^ (h >>> 29)) & 0xFFFF) / 65536.0;
        return (long) (ModeSwitch.MIN_DWELL_MS * (0.75 + 0.75 * unit));
    }

    /** A bot in a social or town mode stands at its preferred gathering place, picked afresh every 30 minutes. */
    private void placeInTown(Bot b, long now) {
        if (b.mode == Mode.TRAINING) {
            return;
        }
        long bucket = now / SocialRoutine.BUCKET_MS;
        if (b.placedBucket == bucket && world.body(b.id) != null) {
            return;
        }
        b.placedBucket = bucket;
        int dest = pilotOn ? SocialPilot.destination(b.id, now) : 0;
        if (dest <= 0) {
            dest = maps[0]; // the generic town pick: "henesys:100", the main map
        }
        b.mapId = dest;
        int x = 150 + (int) (2100 * SocialRoutine.unit(b.id * 131L + bucket, 99));
        SocialWorld.Body now0 = world.body(b.id);
        if (now0 == null) {
            world.put(b.id, "Bot" + b.slot, dest, x, 0);
        } else if (now0.mapId() != dest) {
            world.moveTo(b.id, dest, x, 0);
        }
    }

    // ── who stood near whom ──

    private boolean mapObserved(int mapId, long now) {
        for (Visit v : VISITS) {
            if (v.day() < days && now >= startOf(v) && now < startOf(v) + v.minutes() * MIN && mapId == visitMap[VISITS.indexOf(v)]) {
                return true;
            }
        }
        return false;
    }

    /** The map each visit is on (chosen when it starts: where the people the player came to see are). */
    final int[] visitMap = new int[VISITS.size()];

    /** The map holding most of the {@code preferred} bots (else most of the online town bots) now. */
    private int busiestMap(Set<Integer> preferred) {
        int[] count = new int[maps.length];
        int[] any = new int[maps.length];
        for (Bot b : bots.values()) {
            SocialWorld.Body body = world.body(b.id);
            if (b.online && body != null) {
                any[mapIndex(body.mapId())]++;
                if (preferred.contains(b.id)) count[mapIndex(body.mapId())]++;
            }
        }
        int best = 0;
        for (int i = 1; i < maps.length; i++) {
            if (count[i] > count[best] || count[i] == count[best] && any[i] > any[best]) best = i;
        }
        return maps[best];
    }

    private static long startOf(Visit v) {
        return T0 + v.day() * DAY + v.startHour() * HOUR;
    }

    /**
     * What {@code BotBonds.step} gives: each observed map with two or more bots gets {@link BotBonds#pairsPerMap()} sampled
     * NEAR pairs a minute; of the unobserved ones, {@link BotBonds#backgroundMaps()} (reservoir-drawn) get
     * {@link BotBonds#backgroundPairsPerMap()} each. The same caps, over ids instead of characters.
     */
    private void sampleBonds(long now) {
        Map<Integer, List<Integer>> here = new TreeMap<>();
        for (Bot b : bots.values()) {
            SocialWorld.Body body = world.body(b.id);
            if (b.online && body != null) {
                here.computeIfAbsent(body.mapId(), k -> new ArrayList<>()).add(b.id);
            }
        }
        Random rng = SocialSim.random(0, "bonds-step");
        List<List<Integer>> quiet = new ArrayList<>();
        for (Map.Entry<Integer, List<Integer>> e : here.entrySet()) {
            if (e.getValue().size() < 2) {
                continue;
            }
            if (mapObserved(e.getKey(), now)) {
                BotBonds.samplePairs(e.getValue(), BotBonds.pairsPerMap(), rng, this::near);
            } else {
                quiet.add(e.getValue());
            }
        }
        // the background tier draws its maps from every populated unobserved map in the world, not only the pilot's: the other
        // maps are placeholders that take their share of the draws (an assumption, printed in the report)
        List<List<Integer>> pool = new ArrayList<>(quiet);
        for (int i = 0; i < OTHER_QUIET_MAPS; i++) {
            pool.add(null);
        }
        List<List<Integer>> chosen = new ArrayList<>();
        int seen = 0;
        for (List<Integer> q : pool) {
            seen++;
            if (chosen.size() < BotBonds.backgroundMaps()) {
                chosen.add(q);
            } else {
                int slot = rng.nextInt(seen);
                if (slot < BotBonds.backgroundMaps()) {
                    chosen.set(slot, q);
                }
            }
        }
        for (List<Integer> q : chosen) {
            if (q != null) {
                BotBonds.samplePairs(q, BotBonds.backgroundPairsPerMap(), rng, this::near);
            }
        }
    }

    /** Unobserved maps elsewhere in the world that compete for the background tier's two draws a minute (an assumption). */
    static final int OTHER_QUIET_MAPS = 12;

    private void near(int a, int b) {
        BotBonds.together(a, b, BondEvent.NEAR);
        ledger(a, b)[0]++;
    }

    int[] ledger(int a, int b) {
        return pairLedger.computeIfAbsent(SocialGroups.pairKey(a, b), k -> new int[4]);
    }

    // ── acts ──

    /** For each apology: the event and the earlier fight note the actor held about the target at that moment (or null). */
    final List<Object[]> apologyBacking = new ArrayList<>();

    private void onAct(ActEvent e) {
        acts.add(e);
        if (e.kind() == SocialActKind.APOLOGIZE) {
            BotMemory.Note earlier = null;
            for (BotMemory.Note n : BotMemory.about(e.actor() - BotStore.BOT_ID_BASE, e.target())) {
                if (n.text().toLowerCase(java.util.Locale.ROOT).contains("temper") && n.createdAt() <= e.now()) {
                    earlier = n;
                }
            }
            apologyBacking.add(new Object[]{e, earlier});
        }
        int d = Math.min(days - 1, (int) ((e.now() - T0) / DAY));
        carriedByDay[d]++;
        if (e.outcome().landed()) {
            landedByDay[d]++;
            int[] l = ledger(e.actor(), e.target());
            switch (e.kind()) {
                case SPEND_TIME -> l[1]++;
                case CONVERSE -> {
                    l[2]++;
                    maybePromise(e);
                }
                default -> l[3]++;
            }
        } else {
            failures.add(new int[]{e.actor(), e.target(), e.kind().ordinal(), (int) ((e.now() - T0) / MIN)});
        }
    }

    /** Friends who talk sometimes say "let's meet again": the bot-bot yes of {@code TalkPromises}, here a seeded draw. */
    private void maybePromise(ActEvent e) {
        Random r = SocialSim.random(e.actor(), "pilot-promise");
        SocialContext.Self self = SocialActions.selfOf(e.actor(), e.now());
        if (BotBonds.stage(e.actor(), e.target()).ordinal() >= Stage.ACQUAINTANCE.ordinal()
                && r.nextDouble() < 0.10 + 0.15 * self.t(Trait.LOYALTY)) {
            SocialIntents.promise(e.actor(), e.target(), SocialActKind.SPEND_TIME, (20 + r.nextInt(60)) * MIN,
                    world.body(e.actor()) == null ? 0 : world.body(e.actor()).mapId(), "pilot:chat", e.now());
        }
    }

    // ── what happens to the pilot that is not a decision ──

    private void scriptedEvents(long now) {
        int d = day(), mod = minuteOfDay();
        if (d == 0 && mod == 15 * 60) {
            meaningfulEvent(0, 1, BondEvent.GIFT, "a gift", now);
        }
        if (d == 1 && mod == 16 * 60) {
            meaningfulEvent(2, 3, BondEvent.KS, "a stolen mob", now);
        }
        if (d == 2 && mod == 15 * 60) {
            meaningfulEvent(4, 5, BondEvent.PQ, "a party quest cleared together", now);
        }
        if (d == 4 && mod == 17 * 60) {
            meaningfulEvent(6, 7, BondEvent.KS, "a stolen mob", now);
        }
    }

    int frictionEvents;

    /**
     * What a town does to its people that is not a decision: once an hour, on a map with two or more bots, a mob gets
     * stolen with some chance (the thief likelier among the impulsive and the disagreeable); news and rumors turn up for the
     * share and spread acts to tell. The fake's only source of grudges besides the scripted events.
     */
    private void friction(long now) {
        int mod = minuteOfDay();
        if (mod % 180 == 0) {
            flows.news = Math.min(2, flows.news + 1);
        }
        if (mod % 60 != 7) {
            return;
        }
        Random r = SocialSim.random(0, "pilot-friction");
        Map<Integer, List<Integer>> here = new TreeMap<>();
        for (Bot b : bots.values()) {
            SocialWorld.Body body = world.body(b.id);
            if (b.online && body != null) {
                here.computeIfAbsent(body.mapId(), k -> new ArrayList<>()).add(b.id);
            }
        }
        for (List<Integer> group : here.values()) {
            if (group.size() < 2 || r.nextDouble() >= 0.18) {
                continue;
            }
            double total = 0;
            double[] w = new double[group.size()];
            for (int i = 0; i < w.length; i++) {
                SocialContext.Self self = SocialActions.selfOf(group.get(i), now);
                w[i] = 0.2 + self.t(Trait.IMPULSIVENESS) + (1 - self.t(Trait.AGREEABLENESS));
                total += w[i];
            }
            double roll = r.nextDouble() * total;
            int thief = 0;
            for (int i = 0; i < w.length; i++) {
                roll -= w[i];
                if (roll < 0) {
                    thief = i;
                    break;
                }
            }
            int victim = r.nextInt(group.size() - 1);
            if (victim >= thief) {
                victim++;
            }
            BotBonds.record(group.get(victim), group.get(thief), BondEvent.KS, now, false);
            flows.rumors = Math.min(2, flows.rumors + 1);
            frictionEvents++;
        }
    }

    /** A meaningful event between pilot bots {@code i} and {@code j}: the victim's (or receiver's) edge, before and after. */
    private void meaningfulEvent(int i, int j, BondEvent event, String what, long now) {
        int a = ids[i], b = ids[j];
        Axes before = BotBonds.axes(b, a);
        Stage stageBefore = BotBonds.stage(b, a);
        if (event == BondEvent.KS) { // b was hit: the thief a took b's mobs
            for (int n = 0; n < 4; n++) {
                BotBonds.record(b, a, event, now + n, true);
            }
        } else if (event == BondEvent.GIFT) { // a gave b a gift
            for (int n = 0; n < 3; n++) {
                BotBonds.record(b, a, event, now + n, true);
            }
        } else {
            BotBonds.together(a, b, event);
        }
        Axes after = BotBonds.axes(b, a);
        Stage stageAfter = BotBonds.stage(b, a);
        meaningful.add(String.format("day %d %02d:00 %s: bot %d's edge to bot %d: affection %.2f->%.2f trust %.2f->%.2f resentment %.2f->%.2f stage %s->%s",
                day() + 1, minuteOfDay() / 60, what, b - BotStore.BOT_ID_BASE, a - BotStore.BOT_ID_BASE, before.affection(),
                after.affection(), before.trust(), after.trust(), before.resentment(), after.resentment(), stageBefore, stageAfter));
        axesBeforeAfter.add(new Object[]{event, before, after, stageBefore, stageAfter, b, a});
    }

    final List<Object[]> axesBeforeAfter = new ArrayList<>();

    // ── the friendships ──

    private void watchFriendships(long now) {
        for (int a : ids) {
            for (int b : ids) {
                if (a == b) continue;
                long key = ((long) a << 32) | (b & 0xffffffffL);
                if (firstFriend.containsKey(key)) continue;
                if (SocialPilot.isFriendStage(BotBonds.stage(a, b))) {
                    int[] l = ledger(a, b);
                    firstFriend.put(key, new long[]{(now - T0) / MIN, l[0], l[1], l[2]});
                }
            }
        }
    }

    // ── the real players ──

    private Character mockPlayer(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(maps[0]); // (the map id is not read by the code under test)
        return c;
    }

    private void visits(long now) {
        for (int v = 0; v < VISITS.size(); v++) {
            Visit visit = VISITS.get(v);
            if (visit.day() >= days) continue;
            long start = startOf(visit), end = start + visit.minutes() * MIN;
            if (now == start) {
                visitMap[v] = busiestMap(friendedByPlayer.keySet());
                if (visit.familiar()) world.put(FAMILIAR, "Wanderer", visitMap[v], 1200, 0);
                if (visit.stranger()) world.put(STRANGER, "Newcomer", visitMap[v], 1400, 0);
            }
            if (now == start + 10 * MIN) {
                if (v == 0) {
                    befriendByPlayer(now, visitMap[v]);
                } else {
                    react(v, now, visitMap[v]);
                }
            }
            if (now == end) {
                world.bodies.remove(FAMILIAR);
                world.bodies.remove(STRANGER);
            }
        }
    }

    /** The first visit: the familiar player parties and helps the (up to three) pilot bots on the map until they are friends. */
    private void befriendByPlayer(long now, int mapId) {
        Character player = mockPlayer(FAMILIAR, "Wanderer");
        BotAffinity.ensureLoaded(player);
        List<Integer> here = new ArrayList<>();
        for (Bot b : bots.values()) {
            SocialWorld.Body body = world.body(b.id);
            if (b.online && body != null && body.mapId() == mapId) here.add(b.id);
        }
        here.sort(Integer::compare);
        AffinityEvent[] sequence = {AffinityEvent.PARTY, AffinityEvent.BUFF, AffinityEvent.HEAL, AffinityEvent.RESCUE,
                AffinityEvent.GIFT, AffinityEvent.KIND_CHAT, AffinityEvent.FAME, AffinityEvent.FAME, AffinityEvent.FAME};
        for (int id : here.subList(0, Math.min(3, here.size()))) {
            int events = 0;
            for (AffinityEvent e : sequence) {
                BotAffinity.record(id, FAMILIAR, e, e.delta(), "Wanderer", "Bot" + (id - BotStore.BOT_ID_BASE), mapId);
                events++;
                if (BotAffinity.state(id, FAMILIAR) == AffinityState.FRIEND) break;
            }
            friendedByPlayer.put(id, new int[]{events});
            BotMemory.remember(id - BotStore.BOT_ID_BASE, FAMILIAR, BotMemory.Kind.PARTY, "partied together and they helped me out",
                    0.7, mapId, BotMemory.Provenance.WITNESSED, Double.NaN, 0, now);
        }
    }

    /** A later visit: every pilot bot that can see the players reads its own ties to each of them. */
    private void react(int visit, long now, int mapId) {
        Character familiar = mockPlayer(FAMILIAR, "Wanderer");
        Character stranger = mockPlayer(STRANGER, "Newcomer");
        BotAffinity.ensureLoaded(familiar);
        BotAffinity.ensureLoaded(stranger);
        for (Bot b : bots.values()) {
            SocialWorld.Body body = world.body(b.id);
            if (!b.online || body == null || body.mapId() != mapId) continue;
            for (Character p : List.of(familiar, stranger)) {
                if (world.body(p.getId()) == null) continue;
                AffinityState state = BotAffinity.state(b.id, p.getId());
                double score = BotAffinity.score(b.id, p.getId());
                String prompt = AffinityReactions.promptLine(b.id, p);
                List<RelationshipCard.Fact> facts = new ArrayList<>();
                for (BotMemory.Note n : BotMemory.recallNotes(b.slot, p.getId(), now, 4)) {
                    facts.add(new RelationshipCard.Fact(n.text(), null, n.createdAt()));
                }
                String card = RelationshipCard.build(new RelationshipCard.Data(p.getName(), state, score, facts, null, null,
                        "Henesys", List.of(), "evening", null, "hanging out", null), now, ZoneOffset.UTC);
                reactions.add(new Reaction(visit, b.id, p.getId(), state, score, prompt, card));
            }
        }
    }

    // ── the restart ──

    /** What a snapshot compares: the mind, the directed edges, the notes, the intents, the mode and the RAM-only state. */
    static final class Snap {
        final Map<Integer, double[]> mind = new TreeMap<>();
        final Map<Long, double[]> axes = new TreeMap<>();
        final Map<Long, Stage> stages = new TreeMap<>();
        final Map<Long, List<String>> notes = new TreeMap<>();
        final Map<Integer, List<SocialIntent>> intents = new TreeMap<>();
        final Map<Integer, List<String>> recentIntents = new TreeMap<>();
        final Map<Integer, Mode> modes = new TreeMap<>();
        int avoids, invites, together, groups;
        Set<Integer> pilotMembers = new TreeSet<>();
        Map<String, Long> budget = new TreeMap<>();
        Map<String, Long> pilotStats = new TreeMap<>();
    }

    Snap snapshot() {
        long now = now();
        Snap s = new Snap();
        for (Bot b : bots.values()) {
            SocialContext.Self self = SocialActions.selfOf(b.id, now);
            double[] v = new double[Trait.values().length + Emotion.values().length + Need.values().length];
            int i = 0;
            for (Trait t : Trait.values()) v[i++] = self.t(t);
            for (Emotion e : Emotion.values()) v[i++] = self.e(e);
            for (Need n : Need.values()) v[i++] = self.n(n);
            s.mind.put(b.id, v);
            s.modes.put(b.id, b.mode);
            s.intents.put(b.id, SocialIntents.live(b.id));
            LifeMemory mem = LifeMemoryStore.peek(b.id);
            List<String> recent = new ArrayList<>();
            if (mem != null) {
                mem.intents(book -> {
                    book.recent().forEach(d -> recent.add(d.kind() + ":" + d.end() + ":" + d.target()));
                    return null;
                });
            }
            s.recentIntents.put(b.id, recent);
            for (Bot o : bots.values()) {
                if (o == b) continue;
                long key = ((long) b.id << 32) | (o.id & 0xffffffffL);
                s.axes.put(key, BotBonds.axes(b.id, o.id).toArray());
                s.stages.put(key, BotBonds.stage(b.id, o.id));
                List<String> ns = new ArrayList<>();
                for (BotMemory.Note n : BotMemory.about(b.slot, o.id)) {
                    ns.add(n.kind() + "|" + n.text() + "|" + n.weight() + "|" + n.createdAt() + "|" + n.locationMapId() + "|"
                            + String.format("%.3f", n.valence()) + "|" + n.subjectType() + "|" + n.source());
                }
                java.util.Collections.sort(ns);
                if (!ns.isEmpty()) s.notes.put(key, ns);
            }
        }
        s.avoids = SocialActions.allAvoids(now).size();
        s.invites = SocialActions.openState().get("invites");
        s.together = SocialActions.openState().get("together");
        s.groups = pilotOn ? SocialGroups.current(now).groups().size() : 0;
        s.pilotMembers.addAll(SocialPilot.members());
        s.budget.putAll(SocialBudget.counters());
        s.pilotStats.putAll(SocialPilot.stats());
        return s;
    }

    Snap beforeRestart, afterRestart;
    final Set<Integer> onlineAtRestart = new TreeSet<>();

    /** The instant of the simulated restart. */
    long beforeRestartAt() {
        return T0 + RESTART_DAY * DAY + RESTART_HOUR * HOUR;
    }
    /** Every field a restart leaves behind in RAM only (name -> before, after). */
    final Map<String, String> lost = new LinkedHashMap<>();

    /**
     * Saves what production saves (every bot's {@code LifeMemory}, the bonds through their real flush, the mode a swapped bot
     * resumes as; the notes are already rows in the memory table), drops every bit of RAM state, rebuilds from the rows
     * and reads the same instant again. The server is down for ten minutes after.
     */
    private void doRestart() {
        long now = now();
        for (Bot b : bots.values()) {
            if (b.online) onlineAtRestart.add(b.id);
        }
        // what the RAM holds at this moment: one avoid record, one open invitation, one spend-time pair (all RAM-only state)
        List<Integer> upNow = new ArrayList<>(onlineAtRestart);
        List<Integer> inTown = new ArrayList<>();
        for (int id : upNow) {
            if (world.body(id) != null) inTown.add(id);
        }
        if (inTown.size() >= 3) {
            int x = inTown.get(0), y = inTown.get(1), z = inTown.get(2);
            SocialActions.startAvoid(new SocialActions.Avoid(x, y, now, now + 10 * MIN, SocialActions.AVOID_KEEP_PX, "staged for the restart check"));
            SocialActions.openInvite(y, z, "a duo party", maps[0], "payload", now);
            SocialActions.startTogether(new SocialActions.Together(x, z, world.body(x).mapKey(), now, now + 8 * MIN));
        }
        beforeRestart = snapshot();
        // ── save ──
        for (Bot b : bots.values()) {
            LifeMemory mem = LifeMemoryStore.peek(b.id);
            savedRows.put(String.valueOf(b.id), Json.write(mem.save(now)));
            if (b.online) {
                savedModes.put(b.id, b.mode);
            }
        }
        int written = BotBonds.flushAtShutdown();
        restartReport.put("bondRowsWritten", written);
        restartReport.put("lifeProfileRows", savedRows.size());
        // ── everything in RAM goes ──
        for (Bot b : bots.values()) {
            b.online = false;
            world.bodies.remove(b.id);
            b.mode = Mode.TRAINING;
        }
        world.bodies.clear();
        world.busy.clear();
        LifeMemoryStore.useConnectionSourceForTest(null);
        SocialActions.useForTest(world, flows, GiftSeam.AFFINITY_ONLY);
        SocialActions.observer = this::onAct;
        SocialBudget.resetForTest();
        SocialDecider.resetForTest();
        SocialGroups.useForTest(null);
        SocialRoutine.resetForTest();
        SocialRoutine.useZoneForTest(ZoneOffset.UTC);
        SocialPilot.resetForTest();
        SocialMetrics.resetActCountersForTest();
        wireIntents();
        BotBonds.useForTest(bondStore, Runnable::run, clock::get, null, (id, p) -> { }, id -> null);
        memoryTable.install(); // the cache is gone, the rows stay
        BotMemory.useForTest(Runnable::run, clock::get, 0);
        BotAffinity.useForTest(affinityStore, Runnable::run, clock::get, id -> true);
        // ── boot: read the rows back ──
        BotBonds.maybeStep(now); // reads the bond table (the first step does)
        for (Bot b : bots.values()) {
            LifeMemory restored = new LifeMemory(b.id, Json.parse(savedRows.get(String.valueOf(b.id))));
            LifeMemoryStore.putForTest(b.id, restored);
        }
        if (pilotOn) {
            SocialPilot.setMembersForTest(Arrays.stream(ids).boxed().toList());
        }
        SocialPilot.bootNowForTest(now + 1);
        SocialPilot.useProbeForTest(new SocialPilot.Probe() {
            @Override
            public boolean online(int charId) {
                Bot b = bots.get(charId);
                return b != null && b.online;
            }

            @Override
            public String mode(int charId) {
                Bot b = bots.get(charId);
                return b == null ? null : b.mode.name().toLowerCase(java.util.Locale.ROOT);
            }
        });
        for (Bot b : bots.values()) { // the mode a swapped bot resumes as is read back at login; show it in the snapshot
            b.mode = savedModes.getOrDefault(b.id, Mode.TRAINING);
        }
        afterRestart = snapshot();
        // what is gone with the RAM
        lost.put("open avoid records", beforeRestart.avoids + " -> " + afterRestart.avoids);
        lost.put("open invitations", beforeRestart.invites + " -> " + afterRestart.invites);
        lost.put("spend-time pairs in progress", beforeRestart.together + " -> " + afterRestart.together);
        lost.put("SocialBudget history (pair cooldowns, rates)", beforeRestart.budget.getOrDefault("trackedPairs", 0L) + " pairs tracked -> "
                + afterRestart.budget.getOrDefault("trackedPairs", 0L));
        lost.put("act and decision counters", "reset (SocialMetrics, SocialPilot.stats, SocialIntents.counters are per run)");
        lost.put("decision pace (SocialDecider)", "reset: every bot is due at once after boot");
        lost.put("act timestamps (habituation, argue-per-day)", "reset");
        lost.put("group snapshot", "rebuilt from the surviving bonds: " + beforeRestart.groups + " -> "
                + (pilotOn ? SocialGroups.current(now).groups().size() : 0) + " groups");
        downForRestart = true;
        pendingUp = now + 10 * MIN; // the server is back ten minutes later
    }

    private long pendingUp;

    /** Called by {@link #minute()} through {@code updateSessions}: the server stays down until {@code pendingUp}. */
    void checkUp(long now) {
        if (downForRestart && now >= pendingUp) {
            downForRestart = false;
        }
    }

    // ── printing ──

    static String pad(String s, int n) {
        return String.format("%-" + n + "s", s);
    }

    static EnumMap<SocialActKind, Integer> landedByKind(List<ActEvent> log) {
        EnumMap<SocialActKind, Integer> m = new EnumMap<>(SocialActKind.class);
        for (ActEvent e : log) {
            if (e.outcome().landed()) m.merge(e.kind(), 1, Integer::sum);
        }
        return m;
    }
}
