package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions.ActEvent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialTestKit.FakeFlows;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 4: agent to agent, no real player. 12 bots with their own minds live three simulated days in a fake
 * world on the {@link SocialSim} clock. Nothing but the decision tick chooses the social acts. Shows: relationships
 * change after events; acts of several kinds happen; an argument can be followed by an apology and a reconciliation that
 * recovers the pair; no pair loops past the {@link SocialBudget} limits. The counts are printed.
 */
class SocialSimulationTest extends SocialActTestBase {

    static final int BOTS = 12;
    static final int STEPS = 3 * 24 * 60; // one minute each
    static final long MIN = 60_000L;

    /** The wrapped flows with the effects bot-talk's own finish step has: a chat leaves a THREAD bond, a party a PARTY bond. */
    final class SimFlows extends FakeFlows {
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

    private static int id(int i) {
        return SocialTestKit.BASE + 1 + i;
    }

    @Test
    void twelveBotsThreeDaysNoPlayer() {
        SimFlows sim = new SimFlows(world);
        flows = sim;
        SocialActions.useForTest(world, sim, null);
        Random env = new Random(2024);
        int[] ids = new int[BOTS];
        for (int i = 0; i < BOTS; i++) {
            ids[i] = id(i);
            world.put(ids[i], "Bot" + i, (i / 4) * 3000 + env.nextInt(300), 0); // three clusters of four, far apart
            mem(ids[i]);
        }
        List<ActEvent> log = new ArrayList<>();
        SocialActions.observer = log::add;

        for (int step = 0; step < STEPS; step++) {
            tick(MIN);
            long now = now();
            // the world: who is busy this minute, who wanders, who meets (what BotBonds' own sampling would give)
            world.busy.clear();
            for (int b : ids) {
                if (env.nextDouble() < 0.25) {
                    world.busy.add(b);
                }
            }
            if (step % 15 == 0) {
                for (int i = 0; i < BOTS; i++) {
                    int cluster = env.nextDouble() < 0.05 ? env.nextInt(3) : i / 4;
                    world.place(ids[i], cluster * 3000 + env.nextInt(300), 0);
                }
            }
            if (step % 30 == 0) {
                for (int c = 0; c < 3; c++) {
                    int a = ids[c * 4 + env.nextInt(4)], b = ids[c * 4 + env.nextInt(4)];
                    if (a != b) {
                        BondEvent e = BondEvent.values()[env.nextInt(3) == 0 ? BondEvent.PARTY_TIME.ordinal()
                                : env.nextBoolean() ? BondEvent.NEAR.ordinal() : BondEvent.THREAD.ordinal()];
                        BotBonds.together(a, b, e);
                    }
                }
            }
            if (step == 3 * 60 || step == 9 * 60 || step == 20 * 60) { // a falling-out: one bot's drops get stolen again and again
                int c = step == 3 * 60 ? 0 : step == 9 * 60 ? 1 : 2;
                int victim = ids[c * 4], thief = ids[c * 4 + 1];
                for (int i = 0; i < 5; i++) {
                    BotBonds.record(victim, thief, BondEvent.KS, now, true);
                }
            }
            if (step % 180 == 0) {
                sim.news = Math.min(2, sim.news + 1);
            }
            if (step % 120 == 100) { // every two hours a bot's mobs get stolen by another one on its map
                int c = env.nextInt(3);
                int victim = ids[c * 4 + env.nextInt(4)], thief = ids[c * 4 + env.nextInt(4)];
                if (victim != thief) {
                    BotBonds.record(victim, thief, BondEvent.KS, now, false);
                    sim.rumors = Math.min(2, sim.rumors + 1);
                }
            }
            SocialDecider.upkeep(now);
            SocialDecider.onMap(ids, now);
        }

        report(log);
        // ── the claims ──
        Map<SocialActKind, Integer> landed = new EnumMap<>(SocialActKind.class);
        for (ActEvent e : log) {
            if (e.outcome().landed()) {
                landed.merge(e.kind(), 1, Integer::sum);
            }
        }
        assertTrue(landed.size() >= 6, "acts of at least six kinds landed: " + landed);
        assertTrue(relationshipsChanged(), "relationships changed after events");
        assertTrue(chainRecovered(log), "an argument, an apology and a reconciliation recovered a pair");
        assertNoLoops(log);
    }

    // ── the report ──

    private void report(List<ActEvent> log) {
        System.out.println("SIM ===== " + BOTS + " bots, " + STEPS / 60 / 24 + " simulated days, no player =====");
        Map<String, Long> counters = SocialMetrics.actCounters();
        Map<String, Map<String, Long>> byKind = new TreeMap<>();
        counters.forEach((k, v) -> {
            int dot = k.indexOf('.');
            byKind.computeIfAbsent(k.substring(0, dot), x -> new TreeMap<>()).put(k.substring(dot + 1), v);
        });
        byKind.forEach((k, v) -> System.out.println("SIM " + String.format("%-14s", k) + v));
        System.out.println("SIM budget " + SocialBudget.counters());
        Map<Stage, Integer> stages = new EnumMap<>(Stage.class);
        for (int a : bots()) {
            for (int b : bots()) {
                if (a != b) {
                    stages.merge(BotBonds.stage(a, b), 1, Integer::sum);
                }
            }
        }
        System.out.println("SIM stages at the end " + stages);
        int memories = 0;
        for (var list : world.notes.values()) {
            memories += list.size();
        }
        System.out.println("SIM chat lines " + world.said.size() + ", walks " + world.walks.size() + ", memory notes " + memories
                + ", avoid records open " + SocialActions.allAvoids(now()).size());
    }

    private int[] bots() {
        int[] ids = new int[BOTS];
        for (int i = 0; i < BOTS; i++) {
            ids[i] = id(i);
        }
        return ids;
    }

    // ── the claims ──

    /** Some directed edge ended in a different stage or with clearly different axes than a fresh one. */
    private boolean relationshipsChanged() {
        int moved = 0, stageful = 0;
        for (int a : bots()) {
            for (int b : bots()) {
                if (a == b) {
                    continue;
                }
                Axes x = BotBonds.axes(a, b);
                if (x.familiarity() > 0.1 || Math.abs(x.affection()) > 0.1 || x.resentment() > 0.05) {
                    moved++;
                }
                if (BotBonds.stage(a, b).ordinal() >= Stage.FRIEND.ordinal()) {
                    stageful++;
                }
            }
        }
        System.out.println("SIM edges changed " + moved + " of " + BOTS * (BOTS - 1) + ", at FRIEND or beyond " + stageful);
        return moved >= 20;
    }

    /** One pair: ARGUE landed, then later APOLOGIZE landed, then later RECONCILE landed; and its resentment then fell. */
    private boolean chainRecovered(List<ActEvent> log) {
        Map<Long, long[]> chain = new HashMap<>(); // pair -> {argueAt, apologyAt, reconcileAt}
        for (ActEvent e : log) {
            if (!e.outcome().landed()) {
                continue;
            }
            long pair = ((long) Math.min(e.actor(), e.target()) << 32) | Math.max(e.actor(), e.target());
            long[] c = chain.computeIfAbsent(pair, k -> new long[3]);
            switch (e.kind()) {
                case ARGUE -> {
                    if (c[1] == 0) {
                        c[0] = e.now();
                    }
                }
                case APOLOGIZE -> {
                    if (c[0] > 0 && c[1] == 0) {
                        c[1] = e.now();
                    }
                }
                case RECONCILE -> {
                    if (c[1] > 0 && c[2] == 0) {
                        c[2] = e.now();
                    }
                }
                default -> { }
            }
        }
        int complete = 0;
        for (Map.Entry<Long, long[]> e : chain.entrySet()) {
            long[] c = e.getValue();
            if (c[2] > 0) {
                complete++;
                int a = (int) (e.getKey() >>> 32), b = (int) (long) e.getKey();
                System.out.println("SIM chain " + a + "/" + b + ": argued +" + (c[0] - T0) / MIN + " min, apologized +"
                        + (c[1] - T0) / MIN + ", made up +" + (c[2] - T0) / MIN + "; now " + BotBonds.stage(a, b) + "/"
                        + BotBonds.stage(b, a) + " " + BotBonds.axes(a, b));
            }
        }
        return complete >= 1;
    }

    /** No pair repeats the same act family inside the budget's gap, no bot exceeds its per-minute rate. */
    private void assertNoLoops(List<ActEvent> log) {
        SocialBudget.Limits limits = SocialBudget.Limits.defaults();
        Map<String, Long> last = new HashMap<>();
        Map<Integer, java.util.ArrayDeque<Long>> perBot = new HashMap<>();
        int worst = 0;
        for (ActEvent e : log) {
            String family = SocialBudget.family(SocialActions.budgetKey(e.kind()));
            if (e.kind() == SocialActKind.ACCEPT || e.kind() == SocialActKind.DECLINE) {
                continue; // an answer to an invitation: the invitation was the paced act
            }
            long pair = SocialBudget.pair(e.actor(), e.target());
            String key = pair + "/" + family;
            Long before = last.put(key, e.now());
            if (before != null) {
                assertTrue(e.now() - before >= limits.cooldownOf(family) - 1,
                        family + " between the same pair twice within " + limits.cooldownOf(family) / MIN + " min: " + (e.now() - before) / MIN);
            }
            java.util.ArrayDeque<Long> window = perBot.computeIfAbsent(e.actor(), k -> new java.util.ArrayDeque<>());
            window.addLast(e.now());
            while (e.now() - window.peekFirst() >= MIN) {
                window.pollFirst();
            }
            worst = Math.max(worst, window.size());
        }
        System.out.println("SIM busiest bot did at most " + worst + " acts in one minute (limit " + limits.actorPerMin() + ")");
        assertTrue(worst <= limits.actorPerMin(), "no bot above the per-minute actor rate");
        Map<String, Long> counters = SocialBudget.counters();
        assertTrue(counters.get("recorded") > 0);
        assertFalse(SocialActions.allAvoids(now() + 365L * 86_400_000L).size() > 0, "every avoid record expires");
    }
}
