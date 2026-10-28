package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Manner;

import java.util.EnumMap;
import java.util.Map;
import java.util.Random;

/**
 * 1.6.1 (ticket 114): the decisions of a bot-to-bot conversation, pure. Who greets, what the opener asks for, and
 * how the other answers - yes, no or a counter, warm, curt or rude - from each bot's own goals, mood, bond, Bartle
 * point (persona) and current plan. The game's own rules say what is possible first ({@link Feasible}: a PQ party
 * the LFG rules allow, a deal the trade rules agreed, a town with room); this decides what the bot wants.
 */
public final class TalkBrain {

    private TalkBrain() {
    }

    /** What a bot is after right now (its life goals and plan). */
    public enum Goal { LEVEL, MESOS, COMPANY, NONE }

    /**
     * One bot's mind for a conversation. {@code social}/{@code killer} are its Bartle Socializer and Killer shares
     * (0..1); {@code mood} -1 (worn out, fed up) .. 1 (content); {@code pressure} 0..1 how hard its goal pushes;
     * {@code planHangOut}/{@code planGrind}/{@code planPq} its plan's next step; {@code town} the town it means to hang
     * out in (-1: none); {@code busy} a commitment it must keep.
     */
    public record Mind(int id, String name, int level, double social, double killer, double mood, Goal goal,
                       double pressure, boolean planHangOut, boolean planGrind, boolean planPq, int town,
                       boolean busy) {
        public Mind {
            social = clamp01(social);
            killer = clamp01(killer);
            mood = Math.max(-1, Math.min(1, mood));
            pressure = clamp01(pressure);
            goal = goal == null ? Goal.NONE : goal;
        }
    }

    /** One bot's bond to another: {@code state} friend / neutral / rival / nemesis, {@code score} -1..1, met before. */
    public record Relation(String state, double score, boolean knows) {
        public static final Relation STRANGER = new Relation("neutral", 0, false);

        public boolean friend() {
            return "friend".equals(state);
        }

        public boolean hostile() {
            return "rival".equals(state) || "nemesis".equals(state);
        }
    }

    /** Whether the game allows an ask at all, and if not, why (the "no" the bot then says is the true reason). */
    public record Feasible(boolean ok, String why) {
        public static final Feasible YES = new Feasible(true, null);

        public static Feasible no(String why) {
            return new Feasible(false, why);
        }
    }

    /** The listener's decision: its answer, its manner and why (for the social trace). */
    public record Decision(Answer answer, Manner manner, String why) {
        public boolean yes() {
            return answer == Answer.YES;
        }
    }

    // ── greet ──

    /** A bot greets another it knows (a bond, or a note from an earlier chat); strangers are left alone. */
    public static boolean greets(Mind me, Relation rel, Random rng) {
        if (me.busy() || !rel.knows()) {
            return false;
        }
        if (rel.hostile()) {
            return rng.nextDouble() < 0.15 + 0.5 * me.killer(); // a Killer can't resist a jab at a rival
        }
        double p = 0.45 + 0.4 * me.social() + (rel.friend() ? 0.25 : 0) + 0.1 * me.mood();
        return rng.nextDouble() < Math.min(0.95, p);
    }

    /** Whether a greeting goes on into small talk and an ask (else it stays a hello). */
    public static boolean chats(Mind me, Mind other, Relation rel, Random rng) {
        if (me.busy() || other.busy()) {
            return false;
        }
        double p = 0.3 + 0.35 * me.social() + (rel.friend() ? 0.2 : 0) - (rel.hostile() ? 0.2 : 0) + 0.1 * me.mood();
        return rng.nextDouble() < Math.max(0.05, Math.min(0.9, p));
    }

    /** How this bot speaks to that one: its bond first, then its Bartle point and its mood. */
    public static Manner manner(Mind me, Relation rel) {
        if (rel.hostile()) {
            return me.killer() >= 0.3 || me.mood() < 0 ? Manner.RUDE : Manner.CURT;
        }
        double s = 0.6 * rel.score() + (rel.friend() ? 0.25 : 0) + 0.45 * me.social() - 0.55 * me.killer()
                + 0.3 * me.mood();
        if (s >= 0.2) {
            return Manner.WARM;
        }
        return s <= -0.3 ? Manner.RUDE : Manner.CURT;
    }

    // ── the ask ──

    /**
     * What the opener asks for, from its goals and plan, among the asks the game allows ({@code allowed}); null when
     * it wants nothing from this bot (rivals ask no favours: small talk and a goodbye).
     */
    public static Ask chooseAsk(Mind me, Mind other, Relation rel, Map<Ask, Feasible> allowed, Random rng) {
        if (rel.hostile() || me.busy()) {
            return null;
        }
        Map<Ask, Double> w = askWeights(me, other, rel, allowed);
        double sum = 0;
        for (double v : w.values()) {
            sum += v;
        }
        if (sum <= 0) {
            return null;
        }
        double r = rng.nextDouble() * sum;
        for (Map.Entry<Ask, Double> e : w.entrySet()) {
            r -= e.getValue();
            if (r < 0) {
                return e.getKey();
            }
        }
        return w.keySet().iterator().next();
    }

    /** Each allowed ask's pull for this bot (the goal and plan behind it), for {@link #chooseAsk} and the tests. */
    static Map<Ask, Double> askWeights(Mind me, Mind other, Relation rel, Map<Ask, Feasible> allowed) {
        Map<Ask, Double> w = new EnumMap<>(Ask.class);
        for (Ask ask : Ask.values()) {
            Feasible f = allowed.get(ask);
            if (f == null || !f.ok()) {
                continue;
            }
            double v = switch (ask) {
                case PARTY -> 0.25 + (me.goal() == Goal.LEVEL ? 0.5 * me.pressure() : 0) + (me.planPq() ? 0.6 : 0)
                        + 0.2 * me.social();
                case HELP -> other.level() >= me.level() + 5
                        ? 0.15 + (me.goal() == Goal.LEVEL ? 0.6 * me.pressure() : 0) + (rel.friend() ? 0.2 : 0) : 0;
                case COME_ALONG -> 0.2 + 0.45 * me.social() + (me.planHangOut() ? 0.5 : 0)
                        + (me.goal() == Goal.COMPANY ? 0.3 * me.pressure() : 0);
                case TRADE -> 0.15 + (me.goal() == Goal.MESOS ? 0.5 * me.pressure() : 0) + 0.15 * me.killer();
                case GOSSIP -> 0.15 + 0.5 * me.social();
            };
            if (rel.friend()) {
                v *= 1.25;
            }
            if (v > 0) {
                w.put(ask, v);
            }
        }
        return w;
    }

    // ── the answer ──

    /**
     * How {@code me} answers {@code asker}'s ask. The game's rule ({@code feasible}) decides whether a yes is possible
     * at all; then the bot's own will: its bond, Bartle point, mood, and whether the ask fits its goal and plan. A
     * come-along it does not want may be countered with the town it means to go to ({@code me.town}).
     */
    public static Decision answer(Mind me, Mind asker, Relation rel, Ask ask, Feasible feasible, int askedTown,
                                  Random rng) {
        Manner manner = manner(me, rel);
        if (feasible != null && !feasible.ok()) {
            return new Decision(Answer.NO, manner, feasible.why());
        }
        double w = willingness(me, asker, rel, ask);
        boolean yes = rng.nextDouble() < w;
        String why = String.format(java.util.Locale.ROOT, "willing %.2f (%s)", w, reasons(me, rel, ask));
        if (yes) {
            return new Decision(Answer.YES, manner, why);
        }
        if (ask == Ask.COME_ALONG && me.town() > 0 && me.town() != askedTown && !rel.hostile() && w >= 0.15) {
            return new Decision(Answer.COUNTER, manner, why + "; would rather go to its own town " + me.town());
        }
        return new Decision(Answer.NO, manner, why);
    }

    /** 0..1: how much this bot wants to say yes to this ask from this asker. */
    static double willingness(Mind me, Mind asker, Relation rel, Ask ask) {
        double w = 0.45 + 0.35 * Math.max(0, rel.score()) + (rel.friend() ? 0.15 : 0) - (rel.hostile() ? 0.55 : 0)
                + 0.2 * me.social() - 0.15 * me.killer() + 0.15 * me.mood() - (me.busy() ? 0.6 : 0);
        w += switch (ask) {
            case PARTY -> (me.goal() == Goal.LEVEL ? 0.2 * me.pressure() : 0) + (me.planPq() ? 0.2 : 0);
            case HELP -> -0.25 * (me.goal() == Goal.LEVEL ? me.pressure() : 0) + 0.15 * me.social();
            case COME_ALONG -> (me.planHangOut() ? 0.2 : 0)
                    - (me.planGrind() && me.goal() == Goal.LEVEL ? 0.3 * me.pressure() : 0)
                    + (me.goal() == Goal.COMPANY ? 0.2 * me.pressure() : 0);
            case TRADE -> me.goal() == Goal.MESOS ? 0.15 : 0.05;
            case GOSSIP -> 0.15 + 0.25 * me.social();
        };
        return clamp01(w);
    }

    private static String reasons(Mind me, Relation rel, Ask ask) {
        StringBuilder b = new StringBuilder(rel.state());
        b.append(", goal ").append(me.goal().name().toLowerCase(java.util.Locale.ROOT));
        if (me.planGrind()) {
            b.append(", plan grind");
        } else if (me.planHangOut()) {
            b.append(", plan hang out");
        } else if (me.planPq()) {
            b.append(", plan pq");
        }
        b.append(String.format(java.util.Locale.ROOT, ", mood %.1f, social %.2f, killer %.2f", me.mood(), me.social(),
                me.killer()));
        return b.toString();
    }

    /** The opener's answer to a counter (go to the other's town instead): the same will, as a come-along. */
    public static Decision takeCounter(Mind me, Mind other, Relation rel, Feasible feasible, Random rng) {
        Decision d = answer(new Mind(me.id(), me.name(), me.level(), me.social(), me.killer(), me.mood(), me.goal(),
                me.pressure(), me.planHangOut(), me.planGrind(), me.planPq(), -1, me.busy()), other, rel,
                Ask.COME_ALONG, feasible, -1, rng);
        return d.answer() == Answer.COUNTER ? new Decision(Answer.NO, d.manner(), d.why()) : d;
    }

    // ── groups ──

    /**
     * Who speaks next in a group: never the one who just spoke, the one addressed first when they are here, else a
     * draw weighted by chattiness (Socializer share). {@code chat[i]} is participant i's pull; returns an index.
     */
    public static int nextSpeaker(double[] chat, int last, int addressed, Random rng) {
        if (addressed >= 0 && addressed < chat.length && addressed != last) {
            return addressed;
        }
        double sum = 0;
        for (int i = 0; i < chat.length; i++) {
            if (i != last) {
                sum += 0.2 + chat[i];
            }
        }
        double r = rng.nextDouble() * sum;
        for (int i = 0; i < chat.length; i++) {
            if (i == last) {
                continue;
            }
            r -= 0.2 + chat[i];
            if (r < 0) {
                return i;
            }
        }
        return last == 0 && chat.length > 1 ? 1 : 0;
    }

    static double clamp01(double v) {
        return Double.isFinite(v) ? Math.max(0, Math.min(1, v)) : 0;
    }
}
