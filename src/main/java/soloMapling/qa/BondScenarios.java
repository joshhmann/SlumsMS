package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.AFFINITY;

/**
 * 1.6.0 slice S2, bot-to-bot bonds: bb-3 (two bots partied together become friends and show as a crew). The bonds
 * log every state change as {@code AFFINITY BONDS <a> -> <b> neutral -> FRIEND (...)}; the scenario reads the pair out
 * every 30 s ({@code BB3 STATE}) and says {@code BB3 PASS} once both count each other as friends and as crew.
 */
final class BondScenarios {

    private BondScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Bb3PartyFriends());
    }

    /** bb-3: two fresh bots in one party next to the GM; party time makes them friends and a crew. */
    static final class Bb3PartyFriends implements QaScenario {
        private final Map<Integer, int[]> pairByGm = new ConcurrentHashMap<>();
        private final Map<Integer, Long> stagedAt = new ConcurrentHashMap<>();
        private final Set<Integer> passed = ConcurrentHashMap.newKeySet();

        public String id() { return "bb-3"; }
        public String title() { return "Two bots partied together become friends and a crew (1.6.0 bonds)"; }
        public String usage() { return "; then !qa do fast [minutes] | check"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(AFFINITY); }
        @Override public int tickSeconds() { return 30; }
        @Override public int minutes(String[] args) { return 31; } // real party time: FRIEND at about 30 min

        public List<String> stage(QaContext ctx) {
            QaWorld w = ctx.world();
            Character gm = ctx.gm();
            List<Integer> ids = w.spawnIdleBots(gm, gm.getPosition(), 2);
            for (int id : ids) {
                ctx.onStop("despawn bot " + id + " and forget its bonds", () -> {
                    BotBonds.forgetBot(id);
                    w.despawnBot(id);
                });
            }
            if (ids.size() < 2) {
                throw new QaRefusal("Could not spawn two bots here.");
            }
            int a = ids.get(0);
            int b = ids.get(1);
            BotBonds.forgetBot(a); // fresh slots: no bonds from an earlier run
            BotBonds.forgetBot(b);
            String party = w.partyBots(List.of(a, b));
            int gmId = ctx.run().gmId();
            pairByGm.put(gmId, new int[]{a, b});
            stagedAt.put(gmId, System.currentTimeMillis());
            passed.remove(gmId);
            ctx.onStop("final read-out", () -> {
                check(ctx, "FINAL");
                pairByGm.remove(gmId);
                stagedAt.remove(gmId);
                passed.remove(gmId);
            });
            ctx.run().note("BB3", "BB3 STAGED " + QaScenarios.who(ctx, a) + " + " + QaScenarios.who(ctx, b) + ": "
                    + party);
            check(ctx, "START");
            return List.of(QaScenarios.who(ctx, a) + " and " + QaScenarios.who(ctx, b) + " are fresh bots in one party ("
                            + party + ").",
                    "Joining gave them a PARTY bond; every 5 minutes together adds PARTY_TIME (FRIEND after 30-35 minutes,"
                            + " right at the end of this 31-minute run: use !qa do fast to see it now).",
                    "Expect: AFFINITY BONDS ... -> FRIEND lines for both, then BB3 PASS (mutual friends, crew yes).",
                    "!qa do fast applies party time until both are friends (slow bonders take longer); fast <minutes>"
                            + " applies exactly that; !qa do check reads them out.",
                    "!botwhy <name> on either shows the bond and the crew.");
        }

        @Override
        public void tick(QaContext ctx) {
            check(ctx, "STATE");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            int[] pair = pairByGm.get(ctx.run().gmId());
            if (pair == null) {
                throw new QaRefusal("bb-3: no staged pair");
            }
            return switch (name) {
                case "check" -> check(ctx, "CHECK");
                case "fast" -> {
                    // no minutes: party time until both are friends (a slow bonder, Socializer scale x0.6, needs up
                    // to ~70 min), so the check is deterministic; with minutes: exactly that much party time
                    String line;
                    if (args.length == 0) {
                        int took = BotBonds.accelerateUntilFriends(pair[0], pair[1], 240);
                        line = "BB3 FAST until friends: " + (took < 0 ? "not friends after 240 min" : took + " min of party time");
                    } else {
                        int minutes;
                        try {
                            minutes = Math.max(5, Math.min(240, Integer.parseInt(args[0])));
                        } catch (NumberFormatException e) {
                            throw new QaRefusal("bb-3: fast takes minutes, e.g. !qa do fast 45 (none: until friends)");
                        }
                        int counted = BotBonds.accelerateParty(pair[0], pair[1], minutes);
                        line = "BB3 FAST " + minutes + " min of party time (" + counted + " ticks)";
                    }
                    ctx.run().note("STEP", line);
                    List<String> out = new ArrayList<>(List.of(line));
                    out.addAll(check(ctx, "CHECK"));
                    yield out;
                }
                default -> throw new QaRefusal("bb-3 steps: fast, check");
            };
        }

        List<String> check(QaContext ctx, String tag) {
            int gmId = ctx.run().gmId();
            int[] pair = pairByGm.get(gmId);
            if (pair == null) {
                return List.of();
            }
            int a = pair[0];
            int b = pair[1];
            List<Integer> crew = BotBonds.crewOf(a);
            String line = "BB3 " + tag + " " + BotBonds.describePair(a, b) + "; crew of " + a + " " + crew;
            ctx.run().note("BB3", line);
            List<String> out = new ArrayList<>(List.of(line));
            boolean friends = BotBonds.mutualFriends(a, b);
            boolean asCrew = BotBonds.CREW.crew(a, b) && BotBonds.CREW.crew(b, a) && crew.contains(b);
            if (friends && asCrew && passed.add(gmId)) {
                long since = System.currentTimeMillis() - stagedAt.getOrDefault(gmId, System.currentTimeMillis());
                String pass = "BB3 PASS bots " + a + " and " + b + " are mutual friends after " + since / 60_000
                        + " min, crew yes";
                ctx.run().note("BB3", pass);
                out.add(pass);
            } else if ("FINAL".equals(tag) && !passed.contains(gmId)) {
                String fail = "BB3 FAIL bots " + a + " and " + b + " are not friends and crew at the end (friends "
                        + friends + ", crew " + asCrew + ")";
                ctx.run().note("BB3", fail);
                out.add(fail);
            }
            return out;
        }
    }
}
