package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotConvo.BotThreads;
import soloMapling.ArtificialPlayer.BotConvo.NameGuard;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.Reputation;
import soloMapling.ArtificialPlayer.SocialGate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.AFFINITY;
import static soloMapling.qa.QaLog.Topic.CONVO;
import static soloMapling.qa.QaLog.Topic.SOCIAL_GATE;

/**
 * 1.6.0 slice S4, grounded gossip: gs-1. Three fresh bots by the GM: A and B are a crew (mutual friends), C is a
 * stranger. The GM "KSes" A (a staged KS through the real affinity path), so A's crew knows the GM as a KSer
 * ({@link Reputation}); then B tells C about it in a bot thread, with the real names (the GM's and A's), and every
 * line passes the name guard. The GM stands right there, so the gossip is aimed at them: the social gate lets it
 * through because the GM wronged B's crew mate ({@code SOCIAL_GATE YES gossip ... crew side-eye}).
 */
final class GossipScenarios {

    private GossipScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Gs1CrewGossip());
    }

    /** gs-1: a KS on bot A becomes gossip from A's crew mate B, names correct. */
    static final class Gs1CrewGossip implements QaScenario {
        private final Map<Integer, int[]> botsByGm = new ConcurrentHashMap<>();

        public String id() { return "gs-1"; }
        public String title() { return "A KS on a bot becomes true gossip from its crew mate (1.6.0 S4)"; }
        public String usage() { return "; then !qa do tell | llm | ks | check"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(CONVO, AFFINITY, SOCIAL_GATE); }
        @Override public int tickSeconds() { return 30; }
        @Override public int minutes(String[] args) { return 5; }

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            List<Integer> ids = w.spawnIdleBots(gm, gm.getPosition(), 3);
            for (int id : ids) {
                ctx.onStop("despawn bot " + id + " and forget its bonds", () -> {
                    BotBonds.forgetBot(id);
                    SocialGate.forgetBot(id);
                    w.despawnBot(id);
                });
            }
            if (ids.size() < 3) {
                throw new QaRefusal("Could not spawn three bots here.");
            }
            int a = ids.get(0), b = ids.get(1), c = ids.get(2);
            for (int id : ids) {
                BotBonds.forgetBot(id);
            }
            BotBonds.stage(a, b, AffinityState.FRIEND); // both directions: mutual friends, so one crew
            Reputation.forget(gm.getId()); // a fresh reputation: nothing from earlier runs
            SocialGate.forget(a, gm.getId());
            SocialGate.forget(b, gm.getId());
            int gmId = ctx.run().gmId();
            botsByGm.put(gmId, new int[]{a, b, c});
            ctx.onStop("forget the GM's reputation", () -> {
                Reputation.forget(gmId);
                botsByGm.remove(gmId);
            });
            ctx.run().note("GS1", "GS1 STAGED A=" + QaScenarios.who(ctx, a) + " B=" + QaScenarios.who(ctx, b) + " C="
                    + QaScenarios.who(ctx, c) + "; " + BotBonds.describePair(a, b));
            List<String> out = new ArrayList<>(ks(ctx));
            out.addAll(tell(ctx, true));
            out.add(0, QaScenarios.who(ctx, a) + " and " + QaScenarios.who(ctx, b) + " are a crew; "
                    + QaScenarios.who(ctx, c) + " is a stranger. You just KS'd " + QaScenarios.who(ctx, a) + " (staged).");
            out.add("Expect: GS1 KNOWS (B's circle counts your KS), then a THREAD LINE where B tells C that you KS'd A,"
                    + " with both names right, and GS1 PASS. No NAMEGUARD lines for it.");
            // auto-QA t-gs-1 judges the name guard on this thread's speakers only (other threads on the map are not ours)
            out.add("VAR gsbots=" + botName(a) + "|" + botName(b) + "|" + botName(c));
            out.add("!qa do tell runs the thread again from the bank; !qa do llm lets the model write it (the guard"
                    + " checks every name); !qa do ks KSes A again; !qa do check prints what each bot knows.");
            return out;
        }

        @Override
        public void tick(QaContext ctx) {
            check(ctx);
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            return switch (name) {
                case "tell" -> tell(ctx, true);
                case "llm" -> tell(ctx, false);
                case "ks" -> ks(ctx);
                case "check" -> check(ctx);
                default -> throw new QaRefusal("gs-1 steps: tell, llm, ks, check");
            };
        }

        private static String botName(int id) {
            Character c = BotHelpers.getCharFromChannelStorage(id);
            return c == null ? Integer.toString(id) : c.getName();
        }

        private int[] bots(QaContext ctx) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            if (ids == null) {
                throw new QaRefusal("gs-1: no staged bots");
            }
            return ids;
        }

        /** The GM KSes bot A, through the real affinity path (and the ledger, when the event is cooling down). */
        private List<String> ks(QaContext ctx) {
            int a = bots(ctx)[0];
            Character gm = ctx.gmNow() != null ? ctx.gmNow() : ctx.gm();
            BotAffinity.ensureLoaded(gm);
            boolean counted = BotAffinity.record(a, gm.getId(), AffinityEvent.KS, AffinityEvent.KS.delta(), gm.getName(),
                    "bot " + a, gm.getMapId());
            if (!counted) {
                Reputation.witnessed(a, gm.getId(), Reputation.Mark.KS, System.currentTimeMillis());
                SocialGate.playerStarted(a, gm.getId(), SocialGate.Provocation.KS);
            }
            String line = "GS1 KS " + gm.getName() + " -> bot " + a + (counted ? " (affinity event)"
                    : " (reputation and ledger only: the affinity event was cooling down)");
            ctx.run().note("GS1", line);
            List<String> out = new ArrayList<>(List.of(line));
            out.addAll(check(ctx));
            return out;
        }

        /** B tells C what its circle knows about the GM, in a GOSSIP thread on the GM's map. */
        private List<String> tell(QaContext ctx, boolean bankOnly) {
            int[] ids = bots(ctx);
            Character gm = ctx.gmNow();
            Character b = BotHelpers.getCharFromChannelStorage(ids[1]);
            Character c = BotHelpers.getCharFromChannelStorage(ids[2]);
            if (gm == null || b == null || c == null || b.getMap() == null) {
                return List.of("GS1 FAIL the staged bots are not in the world");
            }
            BotGossip.Fact fact = BotGossip.factAbout(b, c, b.getMap().getAllPlayers(), gm.getId());
            if (fact == null) {
                String fail = "GS1 FAIL bot " + b.getId() + " has nothing it may say about " + gm.getName() + " ("
                        + SocialGate.describe(b.getId(), gm.getId()) + ")";
                ctx.run().note("GS1", fail);
                return List.of(fail);
            }
            String a = BotHelpers.getCharFromChannelStorage(ids[0]) == null ? "?"
                    : BotHelpers.getCharFromChannelStorage(ids[0]).getName();
            boolean names = fact.names().contains(gm.getName()) && fact.names().contains(a);
            NameGuard.Verdict v = NameGuard.check(fact.opener(), BotGossip.allowedNames(fact, b.getName(), c.getName()));
            String line = "GS1 FACT " + fact.kind() + ": " + fact.about() + "; opener \"" + fact.opener() + "\"; names "
                    + fact.names() + "; guard " + (v.clean() ? "clean" : "caught " + v.unknown());
            ctx.run().note("GS1", line);
            int channel = gm.getClient() == null ? 1 : gm.getClient().getChannel();
            BotThreads.run(b.getMap(), channel, List.of(b, c), BotThreads.Topic.GOSSIP, fact, bankOnly,
                    System.currentTimeMillis());
            List<String> out = new ArrayList<>(List.of(line));
            String verdict = fact.kind().equals("ks") && names && v.clean()
                    ? "GS1 PASS " + b.getName() + " tells " + c.getName() + " that " + gm.getName() + " KS'd " + a
                    + (bankOnly ? " (bank)" : " (model)")
                    : "GS1 FAIL the fact is " + fact.kind() + " with names " + fact.names() + ", guard " + v.unknown();
            ctx.run().note("GS1", verdict);
            out.add(verdict);
            return out;
        }

        private List<String> check(QaContext ctx) {
            int[] ids = botsByGm.get(ctx.run().gmId());
            Character gm = ctx.gmNow();
            if (ids == null || gm == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            String[] role = {"A", "B", "C"};
            for (int i = 0; i < 3; i++) {
                Reputation.Known k = Reputation.known(ids[i], gm.getId());
                String line = "GS1 KNOWS " + role[i] + " bot " + ids[i] + " about " + gm.getName() + ": ks "
                        + String.format(java.util.Locale.ROOT, "%.1f", k.count(Reputation.Mark.KS)) + ", wary "
                        + (Reputation.wary(ids[i], gm.getId()) ? "yes" : "no") + ", gossip in front "
                        + (SocialGate.rules(ids[i], gm.getId(), SocialGate.Act.GOSSIP).ok() ? "yes" : "no");
                ctx.run().note("GS1", line);
                out.add(line);
            }
            return out;
        }
    }
}
