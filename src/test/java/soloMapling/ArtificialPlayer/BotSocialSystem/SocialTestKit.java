package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Fakes and helpers for the stage 4 tests: a recording world, recording flows, bond staging. */
final class SocialTestKit {

    static final int BASE = BotStore.BOT_ID_BASE;
    static final long MAP = 1L << 32 | 100000000L;

    private SocialTestKit() {
    }

    /** A world that records what the acts do to it: chat lines, emotes, walks, memories. Memory dedupes like the real one. */
    static class FakeWorld implements SocialWorld {
        record Said(int id, String text) {
        }

        record Walk(int id, int x, int y) {
        }

        final Map<Integer, Body> bodies = new LinkedHashMap<>();
        final Set<Integer> busy = new HashSet<>();
        final Set<Integer> notIdle = new HashSet<>();
        final Set<Long> sameParty = new HashSet<>();
        final List<Said> said = new ArrayList<>();
        final List<int[]> emotes = new ArrayList<>();
        final List<Walk> walks = new ArrayList<>();
        final Map<Long, List<BotMemory.Note>> notes = new LinkedHashMap<>();
        final AtomicLong noteIds = new AtomicLong(1);
        boolean sayOk = true;
        double pace = 1.0;
        final LongSupplier clock;

        FakeWorld(LongSupplier clock) {
            this.clock = clock;
        }

        Body put(int id, String name, int x, int y) {
            Body b = new Body(id, name, MAP, 100000000, x, y, 30, id < BASE);
            bodies.put(id, b);
            return b;
        }

        /** A bot (or player, below {@link #BASE}) on one of several maps, as a stationary body. */
        Body put(int id, String name, int mapId, int x, int y) {
            Body b = new Body(id, name, 1L << 32 | mapId, mapId, x, y, 30, id < BASE);
            bodies.put(id, b);
            return b;
        }

        /** Moves a body to another map (the same body, new place); false when it is not in the world. */
        boolean moveTo(int id, int mapId, int x, int y) {
            Body b = bodies.get(id);
            if (b == null) {
                return false;
            }
            bodies.put(id, new Body(b.id(), b.name(), 1L << 32 | mapId, mapId, x, y, b.level(), b.player()));
            return true;
        }

        void place(int id, int x, int y) {
            Body b = bodies.get(id);
            bodies.put(id, new Body(b.id(), b.name(), b.mapKey(), b.mapId(), x, y, b.level(), b.player()));
        }

        @Override
        public Body body(int charId) {
            return bodies.get(charId);
        }

        @Override
        public List<Body> nearby(int charId, int rx, int ry) {
            Body me = bodies.get(charId);
            return me == null ? List.of() : box(me.mapKey(), me.x(), me.y(), rx, ry, charId);
        }

        @Override
        public List<Body> around(int viewerCharId, int x, int y, int rx, int ry) {
            Body me = bodies.get(viewerCharId);
            return me == null ? List.of() : box(me.mapKey(), x, y, rx, ry, viewerCharId);
        }

        private List<Body> box(long mapKey, int x, int y, int rx, int ry, int except) {
            List<Body> out = new ArrayList<>();
            for (Body b : bodies.values()) {
                if (b.id() != except && b.mapKey() == mapKey && Math.abs(b.x() - x) <= rx && Math.abs(b.y() - y) <= ry) {
                    out.add(b);
                }
            }
            return out;
        }

        @Override
        public boolean free(int charId) {
            return bodies.containsKey(charId) && !busy.contains(charId);
        }

        @Override
        public boolean idle(int charId) {
            return free(charId) && !notIdle.contains(charId);
        }

        @Override
        public boolean say(int charId, String text) {
            if (!sayOk) {
                return false;
            }
            said.add(new Said(charId, text));
            return true;
        }

        @Override
        public void emote(int charId, int emote) {
            emotes.add(new int[]{charId, emote});
        }

        @Override
        public void walkTo(int charId, int x, int y) {
            walks.add(new Walk(charId, x, y));
            if (bodies.containsKey(charId)) {
                place(charId, x, bodies.get(charId).y());
            }
        }

        @Override
        public double paceFactor(int charId) {
            return pace;
        }

        @Override
        public boolean sameParty(int a, int b) {
            return sameParty.contains(((long) Math.min(a, b) << 32) | Math.max(a, b));
        }

        private static long key(int bot, int subject) {
            return ((long) bot << 32) | (subject & 0xffffffffL);
        }

        @Override
        public List<BotMemory.Note> recall(int botCharId, int subjectId, BotMemory.Query q, long now, int limit) {
            List<BotMemory.Note> all = notes.getOrDefault(key(botCharId, subjectId), List.of());
            List<BotMemory.Note> out = new ArrayList<>(all);
            out.sort((x, y) -> Long.compare(y.createdAt(), x.createdAt()));
            return out.subList(0, Math.min(limit, out.size()));
        }

        @Override
        public void remember(int botCharId, int subjectId, BotMemory.Kind kind, String text, double valence, int mapId,
                             long now) {
            List<BotMemory.Note> list = notes.computeIfAbsent(key(botCharId, subjectId), k -> new ArrayList<>());
            for (BotMemory.Note n : list) { // the real memory drops the same text again within 20 hours
                if (n.kind() == kind && n.text().equalsIgnoreCase(text) && now - n.createdAt() < 20L * 3_600_000) {
                    return;
                }
            }
            list.add(new BotMemory.Note(noteIds.getAndIncrement(), botCharId - BASE, subjectId, text,
                    BotMemory.Source.DIRECT, 0, 0, now, 0, kind, 3, "", 0, null, mapId, Double.NaN, valence, null, 0));
        }

        /** The notes of {@code bot} about {@code subject} whose text contains {@code part}. */
        List<BotMemory.Note> noteWith(int bot, int subject, String part) {
            List<BotMemory.Note> out = new ArrayList<>();
            for (BotMemory.Note n : notes.getOrDefault(key(bot, subject), List.of())) {
                if (n.text().contains(part)) {
                    out.add(n);
                }
            }
            return out;
        }
    }

    /** Flows that record what they were asked and answer from a script; they count in the budget like BotTalk's do. */
    static class FakeFlows implements SocialFlows {
        record Call(SocialActKind kind, int actor, int target) {
        }

        final List<Call> calls = new ArrayList<>();
        final Map<SocialActKind, String> blockers = new java.util.EnumMap<>(SocialActKind.class);
        final Set<SocialActKind> failing = new HashSet<>();
        final List<SocialActions.Invite> accepted = new ArrayList<>();
        final List<SocialActions.Invite> released = new ArrayList<>();
        boolean canInvite = true;
        final FakeWorld world;

        FakeFlows(FakeWorld world) {
            this.world = world;
        }

        @Override
        public String blocker(SocialActKind kind, int actorId, int targetId, long now) {
            return blockers.get(kind);
        }

        @Override
        public Flow run(SocialActKind kind, int actorId, int targetId, long now) {
            calls.add(new Call(kind, actorId, targetId));
            if (failing.contains(kind)) {
                return Flow.no("the flow failed");
            }
            // the wrapped bot-talk flows ask and record the budget themselves
            String key = SocialActions.budgetKey(kind);
            if (kind == SocialActKind.GREET || kind == SocialActKind.CONVERSE || kind == SocialActKind.ASK_HELP) {
                SocialBudget.record(new SocialBudget.Outcome(actorId, targetId, MAP, key, now, true));
            }
            return Flow.yes(kind.name().toLowerCase() + " done");
        }

        @Override
        public SocialActions.Invite propose(int actorId, int targetId, long now) {
            return canInvite ? new SocialActions.Invite(0, actorId, targetId, "a duo party", 100000000, now, 0, "payload") : null;
        }

        @Override
        public Flow accept(SocialActions.Invite invite, long now) {
            accepted.add(invite);
            return failing.contains(SocialActKind.ACCEPT) ? Flow.no("the party did not form") : Flow.yes("duo party formed");
        }

        @Override
        public void release(SocialActions.Invite invite) {
            released.add(invite);
        }
    }

    /** The wrapped flows with the effects bot-talk's own finish step has: a chat leaves a THREAD bond, a party a PARTY bond. */
    static final class SimFlows extends FakeFlows {
        /** Stories there are to tell: news turns up every few hours, a rumor with every stolen mob; each is told once. */
        int news, rumors;

        SimFlows(SocialTestKit.FakeWorld world) {
            super(world);
            blockers.put(SocialActKind.ASK_HELP, "nobody is grinding in this fake world");
            blockers.put(SocialActKind.OFFER_HELP, "nobody is grinding in this fake world");
        }

        @Override
        public String blocker(SocialActKind kind, int actorId, int targetId, long now) {
            if (kind == SocialActKind.SHARE_INFO) {
                return news > 0 ? null : "nothing to tell";
            }
            if (kind == SocialActKind.SPREAD_RUMOR) {
                return rumors > 0 ? null : "no rumor to pass on";
            }
            return super.blocker(kind, actorId, targetId, now);
        }

        @Override
        public Flow run(SocialActKind kind, int actorId, int targetId, long now) {
            if (kind == SocialActKind.SHARE_INFO && news > 0) {
                news--;
            } else if (kind == SocialActKind.SPREAD_RUMOR && rumors > 0) {
                rumors--;
            }
            Flow f = super.run(kind, actorId, targetId, now);
            if (f.ok() && kind == SocialActKind.CONVERSE) {
                BotBonds.together(actorId, targetId, BondEvent.THREAD);
            }
            return f;
        }

        @Override
        public Flow accept(SocialActions.Invite invite, long now) {
            BotBonds.together(invite.from(), invite.to(), BondEvent.PARTY);
            return super.accept(invite, now);
        }
    }

    /**
     * A world whose memory is the REAL {@link BotMemory} (over a {@code FakeMemoryTable} the test installs): the notes the
     * acts write and the recall the decisions read go through the production code, rows and all.
     */
    static final class MemoryWorld extends FakeWorld {
        MemoryWorld(LongSupplier clock) {
            super(clock);
        }

        @Override
        public List<BotMemory.Note> recall(int botCharId, int subjectId, BotMemory.Query q, long now, int limit) {
            return BotMemory.recallIfCached(botCharId - BASE, subjectId, q, now, limit);
        }

        @Override
        public void remember(int botCharId, int subjectId, BotMemory.Kind kind, String text, double valence, int mapId,
                             long now) {
            BotMemory.remember(botCharId - BASE, subjectId, kind, text, valence, mapId, BotMemory.Provenance.WITNESSED,
                    Double.NaN, 0, now);
        }
    }

    /**
     * The budget's limits as the log of carried-out acts shows them: no pair repeats an act family inside its gap and no
     * bot goes above the per-minute actor rate. Fails with a message on the first breach; returns the most acts any bot
     * did inside one minute.
     */
    static int assertWithinBudget(List<SocialActions.ActEvent> log) {
        SocialBudget.Limits limits = SocialBudget.Limits.defaults();
        Map<String, Long> last = new java.util.HashMap<>();
        Map<Integer, java.util.ArrayDeque<Long>> perBot = new java.util.HashMap<>();
        int worst = 0;
        for (SocialActions.ActEvent e : log) {
            String family = SocialBudget.family(SocialActions.budgetKey(e.kind()));
            if (e.kind() == SocialActKind.ACCEPT || e.kind() == SocialActKind.DECLINE) {
                continue; // an answer to an invitation: the invitation was the paced act
            }
            long pair = SocialBudget.pair(e.actor(), e.target());
            Long before = last.put(pair + "/" + family, e.now());
            if (before != null && e.now() - before < limits.cooldownOf(family) - 1) {
                throw new AssertionError(family + " between the same pair twice within " + limits.cooldownOf(family) / 60_000L
                        + " min: " + (e.now() - before) / 60_000L);
            }
            java.util.ArrayDeque<Long> window = perBot.computeIfAbsent(e.actor(), x -> new java.util.ArrayDeque<>());
            window.addLast(e.now());
            while (e.now() - window.peekFirst() >= 60_000L) {
                window.pollFirst();
            }
            worst = Math.max(worst, window.size());
        }
        if (worst > limits.actorPerMin()) {
            throw new AssertionError("a bot above the per-minute actor rate: " + worst);
        }
        return worst;
    }

    /** {@code a}'s edge to {@code b} pushed up to FRIEND (or beyond with {@code close}) by shared events. */
    static void befriend(int a, int b, boolean close, long now) {
        for (int i = 0; i < (close ? 40 : 14); i++) {
            BotBonds.record(a, b, BondEvent.PQ, now, true);
            BotBonds.record(a, b, BondEvent.PARTY, now, true);
            BotBonds.record(a, b, BondEvent.GIFT, now, true);
            if (BotBonds.stage(a, b) == (close ? Stage.CLOSE_FRIEND : Stage.FRIEND)) {
                break;
            }
        }
    }

    /**
     * {@code a}'s edge to {@code b} soured by {@code n} arguments (resentment and rivalry up). Not KS events: those
     * also push both bots' minds to full anger, which would hide what the acts under test do to emotions.
     */
    static void sour(int a, int b, int n, long now) {
        for (int i = 0; i < n; i++) {
            BotBonds.record(a, b, BondEvent.ARGUE, now, true);
        }
    }

    /** {@code a} has met {@code b} a few times: an ACQUAINTANCE edge. */
    static void meet(int a, int b, long now) {
        for (int i = 0; i < 4; i++) {
            BotBonds.record(a, b, BondEvent.NEAR, now, true);
            BotBonds.record(a, b, BondEvent.THREAD, now, true);
        }
    }
}
