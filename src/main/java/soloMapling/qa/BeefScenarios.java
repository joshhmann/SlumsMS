package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotConvo.BotThreads;
import soloMapling.ArtificialPlayer.BotConvo.NameGuard;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCrushes;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotBotRivalry;
import soloMapling.ArtificialPlayer.BotDramaSystem.DramaNews;
import soloMapling.ArtificialPlayer.BotDramaSystem.RageQuit;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.CONVO;
import static soloMapling.qa.QaLog.Topic.COUPLE;
import static soloMapling.qa.QaLog.Topic.RIVALRY;

/**
 * 1.6.0 slice S7 "Beef": rv-5 (two rival bots trash-talk in a thread on the GM's map, grounded in their bond, every
 * name guarded), dr-6 (a forced boom: the bot rage quits, its friend's gossip knows, and it comes back, accelerated to
 * seconds) and cr-1 (a crush between two staged bots: signs, jealousy at a rival, then a couple).
 */
final class BeefScenarios {

    private BeefScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Rv5BotRivalry(), new Dr6RageQuit(), new Cr1Crush());
    }

    /** Spawns {@code n} idle bots by the GM, forgets their bonds and gate state at stop, refuses with fewer. */
    static List<Integer> spawn(QaContext ctx, int n, String what) {
        QaWorld w = ctx.world();
        Character gm = ctx.gm();
        List<Integer> ids = w.spawnIdleBots(gm, gm.getPosition(), n);
        for (int id : ids) {
            BotBonds.forgetBot(id);
            ctx.onStop("despawn bot " + id + " and forget its bonds", () -> {
                BotBonds.forgetBot(id);
                SocialGate.forgetBot(id);
                BotCrushes.forgetBot(id);
                RageQuit.forget(id);
                w.despawnBot(id);
            });
        }
        if (ids.size() < n) {
            throw new QaRefusal("Could not spawn " + n + " bots here for " + what + ".");
        }
        return ids;
    }

    static Character bot(int id) {
        return BotHelpers.getCharFromChannelStorage(id);
    }

    static String name(int id) {
        Character c = bot(id);
        return c == null ? Integer.toString(id) : c.getName();
    }

    // ── rv-5 ──

    /** rv-5: two rival bots trade trash talk in a bot thread on the GM's map, about their real bond. */
    static final class Rv5BotRivalry implements QaScenario {
        private final Map<Integer, int[]> botsByGm = new ConcurrentHashMap<>();

        public String id() { return "rv-5"; }
        public String title() { return "Two rival bots trash-talk each other, grounded in their bond (1.6.0 S7 Beef)"; }
        public String usage() { return "; then !qa do beef | llm | check"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(CONVO, RIVALRY, AFFINITY); }
        @Override public int minutes(String[] args) { return 4; }

        public List<String> stage(QaContext ctx) {
            List<Integer> ids = spawn(ctx, 2, "rv-5");
            int a = ids.get(0), b = ids.get(1);
            BotBonds.stage(a, b, AffinityState.RIVAL); // both ways: rivals with 3 run-ins
            int gmId = ctx.run().gmId();
            botsByGm.put(gmId, new int[]{a, b});
            ctx.onStop("forget the rv-5 pair", () -> botsByGm.remove(gmId));
            ctx.run().note("RV5", "RV5 STAGED A=" + QaScenarios.who(ctx, a) + " B=" + QaScenarios.who(ctx, b) + "; "
                    + BotBonds.describePair(a, b));
            List<String> out = new ArrayList<>(beef(ctx, true));
            out.add(0, QaScenarios.who(ctx, a) + " and " + QaScenarios.who(ctx, b) + " are rivals (staged bond).");
            out.add("Expect: a THREAD on your map about rival beef, its FACT (rivalry, no other people), THREAD LINE"
                    + " [bank] lines of trash talk about the game, RV5 PASS, and no NAMEGUARD line.");
            // auto-QA t-rv-5 judges the name guard on this thread's speakers only (other threads on the map are not ours)
            out.add("VAR rvbots=" + name(a) + "|" + name(b));
            out.add("!qa do beef runs it again from the bank; !qa do llm lets the model write it (the guard checks"
                    + " every line); !qa do check prints the bond.");
            return out;
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "beef" -> beef(ctx, true);
                case "llm" -> beef(ctx, false);
                case "check" -> {
                    int[] ids = ids(ctx);
                    String line = "RV5 CHECK " + BotBonds.describePair(ids[0], ids[1]);
                    ctx.run().note("RV5", line);
                    yield List.of(line);
                }
                default -> throw new QaRefusal("rv-5 steps: beef, llm, check");
            };
        }

        private int[] ids(QaContext ctx) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            if (ids == null) {
                throw new QaRefusal("rv-5: no staged bots");
            }
            return ids;
        }

        private List<String> beef(QaContext ctx, boolean bankOnly) {
            int[] ids = ids(ctx);
            Character gm = ctx.gmNow();
            Character a = bot(ids[0]);
            Character b = bot(ids[1]);
            if (gm == null || a == null || b == null || a.getMap() == null) {
                return List.of("RV5 FAIL the staged bots are not in the world");
            }
            List<Character> pair = BotBotRivalry.grudgeFirst(List.of(a, b));
            BotGossip.Fact fact = BotBotRivalry.beefFact(pair.get(0), pair.get(1));
            NameGuard.Verdict v = NameGuard.check(fact.opener(), BotGossip.allowedNames(fact, a.getName(), b.getName()));
            String line = "RV5 FACT " + fact.kind() + ": " + fact.about() + "; opener \"" + fact.opener() + "\"; guard "
                    + (v.clean() ? "clean" : "caught " + v.unknown());
            ctx.run().note("RV5", line);
            int channel = gm.getClient() == null ? 1 : gm.getClient().getChannel();
            BotThreads.run(a.getMap(), channel, pair, BotThreads.Topic.RIVALRY, fact, bankOnly,
                    System.currentTimeMillis());
            boolean ok = "rivalry".equals(fact.kind()) && v.clean() && fact.names().isEmpty()
                    && fact.opener().toLowerCase(java.util.Locale.ROOT).contains(pair.get(1).getName()
                    .toLowerCase(java.util.Locale.ROOT));
            String verdict = ok ? "RV5 PASS " + pair.get(0).getName() + " starts beef with " + pair.get(1).getName()
                    + (bankOnly ? " (bank)" : " (model)")
                    : "RV5 FAIL fact " + fact.kind() + ", names " + fact.names() + ", guard " + v.unknown();
            ctx.run().note("RV5", verdict);
            return List.of(line, verdict);
        }
    }

    // ── dr-6 ──

    /** dr-6: a forced boom; the bot rage quits, its friend knows, and it comes back (seconds, not hours). */
    static final class Dr6RageQuit implements QaScenario {
        private final Map<Integer, int[]> botsByGm = new ConcurrentHashMap<>();
        static final int ITEM = 1302020; // Maple Sword
        static final long AWAY_MS = 25_000;

        public String id() { return "dr-6"; }
        public String title() { return "A bot booms a scroll, rage quits and comes back (1.6.0 S7 Beef, accelerated)"; }
        public String usage() { return "; then !qa do boom | back | gossip"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(RIVALRY); }
        @Override public int tickSeconds() { return 10; }
        @Override public int minutes(String[] args) { return 3; }

        public List<String> stage(QaContext ctx) {
            List<Integer> ids = spawn(ctx, 2, "dr-6");
            int a = ids.get(0), friend = ids.get(1);
            BotBonds.stage(a, friend, AffinityState.FRIEND);
            int gmId = ctx.run().gmId();
            botsByGm.put(gmId, new int[]{a, friend});
            ctx.onStop("forget the dr-6 bots", () -> botsByGm.remove(gmId));
            ctx.run().note("DR6", "DR6 STAGED A=" + QaScenarios.who(ctx, a) + " friend=" + QaScenarios.who(ctx, friend)
                    + "; " + BotBonds.describePair(a, friend));
            List<String> out = new ArrayList<>(boom(ctx));
            out.add("Expect: RAGEQUIT (a rage line), DR6 GONE (it left your channel), DR6 GOSSIP (its friend's thread"
                    + " opener about it), then about 25 s later RAGEQUIT BACK with a sheepish line, and DR6 PASS.");
            return out;
        }

        @Override
        public void tick(QaContext ctx) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            if (ids == null) {
                return;
            }
            for (String name : RageQuit.returnsNow()) {
                ctx.run().note("DR6", "DR6 RETURNING " + name);
            }
            Character a = bot(ids[0]);
            Character gm = ctx.gmNow();
            if (a != null && gm != null && gm.getClient() != null && a.getClient() != null
                    && a.getClient().getChannel() != gm.getClient().getChannel()
                    && QaLog.every("dr6-gone-" + ids[0], 60_000)) {
                ctx.run().note("DR6", "DR6 GONE " + a.getName() + " is away (channel " + a.getClient().getChannel() + ")");
            }
            if (a != null && gm != null && a.getClient() != null && gm.getClient() != null
                    && a.getClient().getChannel() == gm.getClient().getChannel()
                    && DramaNews.latest(ids[0], DramaNews.Kind.BACK, System.currentTimeMillis()) != null
                    && QaLog.every("dr6-pass-" + ids[0], 600_000)) {
                ctx.run().note("DR6", "DR6 PASS " + a.getName() + " rage quit and came back");
            }
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "boom" -> boom(ctx);
                case "back" -> {
                    List<String> back = RageQuit.returnsNow();
                    yield List.of("DR6 BACK NOW " + back);
                }
                case "gossip" -> gossip(ctx);
                default -> throw new QaRefusal("dr-6 steps: boom, back, gossip");
            };
        }

        private List<String> boom(QaContext ctx) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            Character a = ids == null ? null : bot(ids[0]);
            if (a == null) {
                return List.of("DR6 FAIL the staged bot is not in the world");
            }
            String quit = RageQuit.forced(a, ITEM, AWAY_MS);
            ctx.run().note("DR6", "DR6 QUIT " + quit);
            List<String> out = new ArrayList<>(List.of("DR6 QUIT " + quit));
            out.addAll(gossip(ctx));
            return out;
        }

        private List<String> gossip(QaContext ctx) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            Character friend = ids == null ? null : bot(ids[1]);
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            if (friend == null) {
                return List.of("DR6 FAIL the staged friend is not in the world");
            }
            BotGossip.Fact fact = DramaNews.factFor(friend, gm, System.currentTimeMillis(), ThreadLocalRandom.current());
            String line = fact == null ? "DR6 FAIL the friend knows nothing about it"
                    : "DR6 GOSSIP " + friend.getName() + ": \"" + fact.opener() + "\" (" + fact.about() + ")";
            ctx.run().note("DR6", line);
            return List.of(line);
        }
    }

    // ── cr-1 ──

    /** cr-1: a crush between two staged bots - signs, jealousy at a rival standing close, then a couple. */
    static final class Cr1Crush implements QaScenario {
        private final Map<Integer, int[]> botsByGm = new ConcurrentHashMap<>();
        private final Set<Integer> jealousSeen = ConcurrentHashMap.newKeySet();

        public String id() { return "cr-1"; }
        public String title() { return "A bot's crush on a bot friend: signs, jealousy, then a couple (1.6.0 S7)"; }
        public String usage() { return "; then !qa do signs | jealous | ripen | check"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(COUPLE, AFFINITY); }
        @Override public int tickSeconds() { return 10; }
        @Override public int minutes(String[] args) { return 5; }

        public List<String> stage(QaContext ctx) {
            List<Integer> ids = spawn(ctx, 3, "cr-1");
            int a = ids.get(0), b = ids.get(1), c = ids.get(2);
            BotBonds.stage(a, b, AffinityState.FRIEND); // mutual friends: the crush is returned
            BotBonds.stage(a, c, AffinityState.RIVAL);
            int gmId = ctx.run().gmId();
            botsByGm.put(gmId, new int[]{a, b, c});
            ctx.onStop("break up the staged cr-1 couple and forget the crush", () -> {
                BotCouples.Couple k = BotCouples.coupleOf(a);
                if (k != null && k.staged()) {
                    BotCouples.breakUp(k.id());
                }
                BotCrushes.forgetBot(a);
                jealousSeen.remove(a);
                botsByGm.remove(gmId);
            });
            BotCrushes.Crush crush = BotCrushes.start(a, b, true, System.currentTimeMillis());
            String line = "CR1 STAGED A=" + QaScenarios.who(ctx, a) + " likes B=" + QaScenarios.who(ctx, b) + ", C="
                    + QaScenarios.who(ctx, c) + " is A's rival; crush " + (crush != null ? "on" : "FAILED")
                    + "; couple cap " + BotCouples.coupleCap() + " for this population";
            ctx.run().note("CR1", line);
            List<String> out = new ArrayList<>(List.of(line));
            out.add("Expect: CRUSH START, then CRUSH NEAR / CRUSH LINE signs. !qa do jealous walks C up to B: CRUSH"
                    + " JEALOUS. !qa do ripen: CRUSH TO COUPLE and CR1 COUPLE.");
            return out;
        }

        @Override
        public void tick(QaContext ctx) {
            if (botsByGm.containsKey(ctx.run().gmId())) {
                BotCrushes.signsNow(System.currentTimeMillis());
            }
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            if (ids == null) {
                throw new QaRefusal("cr-1: no staged bots");
            }
            return switch (name) {
                case "signs" -> BotCrushes.signsNow(System.currentTimeMillis());
                case "jealous" -> {
                    Character b = bot(ids[1]);
                    Character c = bot(ids[2]);
                    if (b == null || c == null || b.getPosition() == null) {
                        yield List.of("CR1 FAIL the staged bots are not in the world");
                    }
                    soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(c, b.getPosition().x + 30, b.getPosition().y);
                    ctx.run().note("CR1", "CR1 RIVAL " + c.getName() + " walks up to " + b.getName());
                    // the jealousy check once the walk is over, without the sign gap (TEST: the 30 s gap and the walk
                    // made the next sign tick miss it); a second look in case the walk was slow
                    int admirer = ids[0];
                    for (long wait : new long[]{4_000, 10_000}) {
                        soloMapling.server.BotTiming.after(wait, () -> {
                            if (BotCouples.coupleOf(admirer) == null && BotCrushes.crushOf(admirer) != null
                                    && !jealousSeen.contains(admirer)) {
                                String r = BotCrushes.jealousNow(admirer, System.currentTimeMillis());
                                if ("jealous".equals(r)) {
                                    jealousSeen.add(admirer);
                                }
                                ctx.run().note("CR1", "CR1 JEALOUS CHECK " + r);
                            }
                        });
                    }
                    yield List.of("CR1 RIVAL " + c.getName() + " walks up to " + b.getName()
                            + " (jealousy is checked in 4 s and 10 s)");
                }
                case "ripen" -> {
                    BotCrushes.ripenNow(ids[0]);
                    String made = BotCrushes.coupleNow(ids[0], System.currentTimeMillis());
                    String line = made.startsWith("couple #") ? "CR1 COUPLE " + made : "CR1 FAIL " + made;
                    ctx.run().note("CR1", line);
                    yield List.of(line);
                }
                case "check" -> {
                    List<String> out = new ArrayList<>(BotCrushes.describe(System.currentTimeMillis()));
                    out.add(BotBonds.describePair(ids[0], ids[1]));
                    yield out;
                }
                default -> throw new QaRefusal("cr-1 steps: signs, jealous, ripen, check");
            };
        }
    }
}
