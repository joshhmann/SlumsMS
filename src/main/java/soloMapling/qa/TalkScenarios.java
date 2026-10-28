package soloMapling.qa;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotConvo.BotTalk;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkPlanner.Plan;
import soloMapling.ArtificialPlayer.BotConvo.TalkQa;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.qa.QaLog.Topic.CONVO;

/**
 * 1.6.1 ticket 114: bt-1, bot-to-bot talk as a decision tree. Stages five fresh bots next to the GM: Ann and Bob
 * (friends), and Cat, Dan and Eve (friends with each other, Eve a rival of Ann). {@code !qa do pair}: Ann greets Bob,
 * small talk, asks to party, Bob answers from his own goals, mood and bond, and a yes is a real party. {@code rival}:
 * Ann asks Eve (a rival answers curt or rude). {@code group}: Cat, Dan and Eve talk as a group - turns, one topic, each
 * answers the ask for itself. {@code check}: the counters (no bot line went to the classifier). Every line shows in
 * the QA log ({@code TALK LINE ... [bank] Ann (GREET warm): ...}) and in the social trace.
 */
final class TalkScenarios {

    private TalkScenarios() {
    }

    static List<QaScenario> all() {
        return List.of(new Bt1());
    }

    static final class Bt1 implements QaScenario {
        private final Map<Integer, List<Character>> staged = new ConcurrentHashMap<>();

        public String id() { return "bt-1"; }
        public String title() { return "Bots talk to bots: greet, chat, ask, answer by their goals, act; groups (1.6.1)"; }
        public String usage() { return "; then !qa do pair | rival | group | check"; }
        @Override public Set<QaLog.Topic> topics() { return EnumSet.of(CONVO); }
        @Override public int minutes(String[] args) { return 20; }

        public List<String> stage(QaContext ctx) {
            if (!BotTalk.on()) {
                throw new QaRefusal("Bot talk is off on this server (-Dslums.bottalk=true).");
            }
            Character gm = ctx.gm();
            List<Character> bots = TalkQa.spawn(gm, 5, false, Math.max(10, Math.min(120, gm.getLevel())));
            if (bots.size() < 5) {
                TalkQa.teardown(bots);
                throw new QaRefusal("Could not spawn five bots here (" + bots.size() + ").");
            }
            int gmId = ctx.run().gmId();
            staged.put(gmId, bots);
            ctx.onStop("despawn the talk bots", () -> {
                List<Character> s = staged.remove(gmId);
                if (s != null) {
                    ctx.run().note("CLEANUP", TalkQa.teardown(s));
                }
            });
            BotBonds.stage(bots.get(0).getId(), bots.get(1).getId(), AffinityState.FRIEND);
            TalkQa.friends(bots.subList(2, 5));
            BotBonds.stage(bots.get(0).getId(), bots.get(4).getId(), AffinityState.RIVAL);
            ctx.run().note("BT1", "BT1 STAGED " + TalkQa.where(bots));
            return List.of(names(bots) + " stand next to you: " + bots.get(0).getName() + " and " + bots.get(1).getName()
                            + " are friends; " + bots.get(2).getName() + ", " + bots.get(3).getName() + " and "
                            + bots.get(4).getName() + " are a crew; " + bots.get(4).getName() + " is "
                            + bots.get(0).getName() + "'s rival.",
                    "!qa do pair: " + bots.get(0).getName() + " greets " + bots.get(1).getName() + ", chats and asks to"
                            + " party; the answer comes from " + bots.get(1).getName() + "'s goals, mood and bond; a yes"
                            + " is a real party (their party icons show it).",
                    "!qa do rival: " + bots.get(0).getName() + " asks " + bots.get(4).getName() + " (expect curt or rude).",
                    "!qa do group: the three take turns on one topic and each answers the ask for itself.",
                    "Talk near them to join in: the bot that answers you uses the normal player chat path.",
                    "!qa do check: the counters (talks, lines, classified bot lines must stay 0).");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            List<Character> b = staged.get(ctx.run().gmId());
            if (b == null) {
                throw new QaRefusal("bt-1: nothing staged");
            }
            Plan p = switch (name) {
                case "pair" -> TalkQa.talk(List.of(b.get(0), b.get(1)), Ask.PARTY, "!qa bt-1 pair");
                case "rival" -> TalkQa.talk(List.of(b.get(0), b.get(4)), Ask.PARTY, "!qa bt-1 rival");
                case "group" -> TalkQa.talk(List.of(b.get(2), b.get(3), b.get(4)), null, "!qa bt-1 group");
                case "check" -> null;
                default -> throw new QaRefusal("bt-1 steps: pair, rival, group, check");
            };
            List<String> out = new ArrayList<>();
            if ("check".equals(name)) {
                String line = "BT1 CHECK " + TalkQa.stats();
                ctx.run().note("BT1", line);
                out.add(line);
                return out;
            }
            for (String line : TalkQa.describe("BT1 " + name.toUpperCase(), p)) {
                ctx.run().note("BT1", line);
                out.add(line);
            }
            if (p == null) {
                return out;
            }
            long since = System.currentTimeMillis();
            long wait = TalkQa.sayMs(p);
            soloMapling.server.ExecutorServiceManager.runAsync(() -> {
                try {
                    TalkQa.awaitDone(List.of(p), 120_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                List<String> bad = TalkQa.judge(p, since);
                ctx.run().note("BT1", "BT1 " + (bad.isEmpty() ? "PASS " : "FAIL ") + name + (bad.isEmpty()
                        ? ": every line said with its intent, the act matches the answers" : ": " + String.join("; ", bad)));
            });
            out.add("The verdict (BT1 PASS / FAIL " + name + ") lands in the QA log in about " + wait / 1000 + " s.");
            return out;
        }

        private static String names(List<Character> bots) {
            StringBuilder s = new StringBuilder();
            for (Character c : bots) {
                s.append(s.length() == 0 ? "" : ", ").append(c.getName());
            }
            return s.toString();
        }
    }
}
