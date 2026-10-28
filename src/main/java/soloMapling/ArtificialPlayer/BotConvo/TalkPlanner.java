package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Decision;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Feasible;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Mind;
import soloMapling.ArtificialPlayer.BotConvo.TalkBrain.Relation;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Answer;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Manner;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Move;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 1.6.1 (ticket 114): turns one bot-to-bot conversation into its moves, all decided before a word is said (as the
 * 1.6.0 threads decide an LFG answer or a deal first): the greeting, small talk on one topic carried through, the
 * opener's ask, each listener's answer from its own mind ({@link TalkBrain}), a counter and its answer, the acts that
 * follow a yes, and a goodbye. Pairs and groups (3+) use the same tree; in a group every listener answers for itself
 * and turns go round by {@link TalkBrain#nextSpeaker}. The world comes in through {@link Sense}, so this is testable.
 */
public final class TalkPlanner {

    private TalkPlanner() {
    }

    /** What the planner reads from the world (the real one is {@code BotTalk.WorldSense}). */
    public interface Sense {
        Mind mind(Character c, long now);

        Relation relation(Character from, Character to);

        /** The asks the game allows {@code asker} to make of {@code to} now, each with what it is about. */
        Map<Ask, Offer> offers(Character asker, Character to, boolean group, long now, Random rng);

        /** Whether the game lets {@code listener} say yes to this offer (its own state: partied, busy, too low...). */
        Feasible canAccept(Character listener, Character asker, Offer offer, long now);

        /** The newest note {@code a} keeps about a chat with {@code b}, or null. */
        String met(Character a, Character b);

        /** Words that fill the lines' slots (map name, level). */
        Map<String, String> slots(Character speaker, Character to);
    }

    /**
     * An ask the game allows: what it is about in words, the slot values for its lines, the prepared payload the act
     * needs (an LFG plan, a deal, a story, a town id, a map id), and the game's own answer when it decided one already
     * (an LFG plan's or a deal's), else null (the listener's state decides via {@link Sense#canAccept}).
     */
    public record Offer(Ask ask, String about, Map<String, String> slots, Object payload, Feasible decided) {
    }

    /** One line of the plan: who says it to whom, what it means, its words, and the act that follows it. */
    public static final class Step {
        public final Character speaker;
        public final Character to;
        public final TalkIntent intent;
        public String text;
        public String source;
        /** The act after this line (a yes's party, trip, help, trade, kept story), or null. */
        public Act act;

        Step(Character speaker, Character to, TalkIntent intent, TalkLines.Worded worded) {
            this.speaker = speaker;
            this.to = to;
            this.intent = intent;
            this.text = worded.text();
            this.source = worded.source();
        }
    }

    /** An act a yes leads to: which ask, between whom, on what offer; {@code to} null = the whole group. */
    public record Act(Ask ask, Character asker, List<Character> yes, Offer offer, String counterTown) {
    }

    /** A planned conversation. */
    public static final class Plan {
        public final List<Character> who;
        public final boolean group;
        public final boolean greetOnly;
        public final List<Step> steps = new ArrayList<>();
        /** The topic carried through the small talk. */
        public String topic;
        public Ask ask;
        public Offer offer;
        /** Each listener's decision (by character id). */
        public final Map<Integer, Decision> decisions = new LinkedHashMap<>();
        /** The opener's answer to a counter, when there was one. */
        public Decision counterTaken;
        /** Why the talk asked nothing (rivals, nothing allowed, a greeting only). */
        public String noAskWhy;
        /** Set once its last line and act are done (BotTalk.finished): QA judges only then. */
        public volatile boolean done;

        Plan(List<Character> who, boolean group, boolean greetOnly) {
            this.who = List.copyOf(who);
            this.group = group;
            this.greetOnly = greetOnly;
        }

        /** "PARTY: Bob yes warm, Ann no rude" (trace, QA). */
        public String summary() {
            if (ask == null) {
                return noAskWhy == null ? "no ask" : "no ask: " + noAskWhy;
            }
            StringBuilder b = new StringBuilder(ask.name()).append(':');
            for (Character c : who) {
                Decision d = decisions.get(c.getId());
                if (d != null) {
                    b.append(' ').append(c.getName()).append(' ').append(d.answer().name().toLowerCase(java.util.Locale.ROOT))
                            .append(' ').append(d.manner().name().toLowerCase(java.util.Locale.ROOT)).append(',');
                }
            }
            if (counterTaken != null) {
                b.append(" counter ").append(counterTaken.answer().name().toLowerCase(java.util.Locale.ROOT)).append(',');
            }
            return b.charAt(b.length() - 1) == ',' ? b.substring(0, b.length() - 1) : b.toString();
        }
    }

    /**
     * Plans a conversation among {@code who} (opener first; two for a pair, three or more for a group). {@code
     * greetOnly}: a hello and its answer, nothing more. {@code forced}: the ask to make (QA), else the opener's
     * choice. {@code bankFirst}: words from the line bank where it has them.
     */
    public static Plan plan(List<Character> who, boolean greetOnly, Ask forced, Sense sense, boolean bankFirst,
                            long now, Random rng) {
        boolean group = who.size() >= 3;
        Plan p = new Plan(who, group, greetOnly);
        Character a = who.get(0);
        List<Character> rest = who.subList(1, who.size());
        Map<Integer, Mind> minds = new HashMap<>();
        for (Character c : who) {
            minds.put(c.getId(), sense.mind(c, now));
        }
        Mind ma = minds.get(a.getId());
        Character first = rest.get(0);
        Relation ra = sense.relation(a, first);
        Manner openManner = group ? groupManner(ma, a, rest, sense) : TalkBrain.manner(ma, ra);
        String met = group ? null : sense.met(a, first);

        // 1. greet; the others greet back (in a group not everyone does, and never two in a row from one bot)
        add(p, a, group ? null : first, TalkIntent.greet(openManner, group ? 0 : first.getId()), sense, bankFirst,
                group, false, false, rng);
        double[] chat = chattiness(who, minds);
        int last = 0;
        int backs = 0;
        for (int k = 0; k < rest.size(); k++) {
            Character c = rest.get(k);
            if (group && backs > 0 && rng.nextDouble() > 0.6) {
                continue;
            }
            Manner m = TalkBrain.manner(minds.get(c.getId()), sense.relation(c, a));
            add(p, c, a, TalkIntent.greet(m, a.getId()), sense, bankFirst, false, false, true, rng);
            last = who.indexOf(c);
            backs++;
        }
        if (greetOnly) {
            p.noAskWhy = "a greeting only";
            p.topic = met;
            return p;
        }

        // 2. small talk on one topic, carried over: the opener's note about the other, else where they are
        p.topic = met;
        int smalls = group ? 2 + rng.nextInt(2) : 1 + (rng.nextDouble() < 0.5 ? 1 : 0);
        int speaker = 0;
        for (int i = 0; i < smalls; i++) {
            speaker = i == 0 ? 0 : TalkBrain.nextSpeaker(chat, last, -1, rng);
            Character s = who.get(speaker);
            Character to = speaker == 0 ? (group ? null : first) : a;
            Manner m = TalkBrain.manner(minds.get(s.getId()), sense.relation(s, to == null ? first : to));
            Map<String, String> slots = new HashMap<>(sense.slots(s, to));
            if (p.topic != null) {
                slots.put("about", p.topic);
            }
            add(p, s, to, TalkIntent.smallTalk(m, to == null ? 0 : to.getId(), p.topic), slots, bankFirst, group,
                    false, p.topic != null, rng);
            last = speaker;
        }

        // 3. the ask: the opener's goals pick it among what the game allows (of the first listener; a group ask is a
        // come-along or a party, put to everyone)
        Map<Ask, Offer> offers = sense.offers(a, first, group, now, rng);
        Map<Ask, Feasible> allowed = new EnumMap<>(Ask.class);
        offers.forEach((k, v) -> allowed.put(k, Feasible.YES));
        Ask ask = forced != null ? (offers.containsKey(forced) ? forced : null)
                : TalkBrain.chooseAsk(ma, minds.get(first.getId()), ra, allowed, rng);
        if (ask == null) {
            p.noAskWhy = forced != null ? "the game does not allow " + forced + " here"
                    : ra.hostile() ? "they are rivals: no favours asked" : offers.isEmpty() ? "nothing to ask for here"
                    : "wants nothing from them now";
            byeLine(p, who, minds, sense, bankFirst, chat, last, rng);
            return p;
        }
        Offer offer = offers.get(ask);
        p.ask = ask;
        p.offer = offer;
        Map<String, String> askSlots = new HashMap<>(sense.slots(a, group ? null : first));
        if (offer.slots() != null) {
            askSlots.putAll(offer.slots());
        }
        boolean pq = ask == Ask.PARTY && offer.slots() != null && offer.slots().containsKey("pq");
        add(p, a, group ? null : first, TalkIntent.ask(ask, openManner, group ? 0 : first.getId(),
                offer.about()), askSlots, bankFirst, group, pq, false, rng);
        last = 0;

        // 4. each listener answers for itself (group: in turn), from its own mind and the game's rule
        List<Character> yes = new ArrayList<>();
        List<Character> order = new ArrayList<>(rest);
        if (group) {
            java.util.Collections.shuffle(order, rng);
            order.sort(java.util.Comparator.comparingDouble(c -> -chat[who.indexOf(c)])); // the chattiest answer first
        }
        int askedTown = offer.payload() instanceof Integer town && ask == Ask.COME_ALONG ? town : -1;
        Character counterBy = null;
        Decision counter = null;
        for (Character c : order) {
            Mind mc = minds.get(c.getId());
            Relation rc = sense.relation(c, a);
            Feasible f = offer.decided() != null && c == first ? offer.decided() : sense.canAccept(c, a, offer, now);
            Decision d = TalkBrain.answer(mc, ma, rc, ask, f, askedTown, rng);
            if (group && d.answer() == Answer.COUNTER) {
                d = new Decision(Answer.NO, d.manner(), d.why()); // a group goes where the opener says, or not at all
            }
            p.decisions.put(c.getId(), d);
            Map<String, String> s = new HashMap<>(sense.slots(c, a));
            s.putAll(askSlots);
            if (d.answer() == Answer.COUNTER) {
                s.put("town", townName(mc.town()));
                counterBy = c;
                counter = d;
            }
            add(p, c, a, TalkIntent.answer(ask, d.answer(), d.manner(), a.getId(), offer.about()), s, bankFirst, group,
                    pq, false, rng);
            if (d.yes()) {
                yes.add(c);
            }
            last = who.indexOf(c);
        }

        // 5. a counter: the opener takes it or not; then the act after the line that settles it
        if (counter != null) {
            Mind mo = new Mind(ma.id(), ma.name(), ma.level(), ma.social(), ma.killer(), ma.mood(), ma.goal(),
                    ma.pressure(), ma.planHangOut(), ma.planGrind(), ma.planPq(), -1, ma.busy());
            Offer counterOffer = new Offer(Ask.COME_ALONG, townName(minds.get(counterBy.getId()).town()),
                    Map.of("town", townName(minds.get(counterBy.getId()).town())),
                    minds.get(counterBy.getId()).town(), null);
            Feasible f = sense.canAccept(a, counterBy, counterOffer, now);
            Decision taken = TalkBrain.takeCounter(mo, minds.get(counterBy.getId()), ra, f, rng);
            p.counterTaken = taken;
            Step reply = add(p, a, counterBy, new TalkIntent(Move.REPLY, Ask.COME_ALONG, taken.answer(), taken.manner(),
                    counterBy.getId(), counterOffer.about()), Map.of(), bankFirst, false, false, false, rng);
            if (taken.yes()) {
                reply.act = new Act(Ask.COME_ALONG, a, List.of(counterBy), counterOffer, counterOffer.about());
            }
            last = 0;
        } else {
            Step settle = p.steps.get(p.steps.size() - 1);
            if (!yes.isEmpty()) {
                settle.act = new Act(ask, a, List.copyOf(yes), offer, null);
            }
            if (!group && rng.nextDouble() < 0.6 || group && yes.isEmpty() && rng.nextDouble() < 0.5) {
                Decision d = p.decisions.get(first.getId());
                boolean any = !yes.isEmpty();
                add(p, a, group ? null : first, new TalkIntent(Move.REPLY, ask, any ? Answer.YES : Answer.NO,
                        any || d == null ? openManner : TalkBrain.manner(ma, ra), group ? 0 : first.getId(), offer.about()),
                        Map.of(), bankFirst, group, false, false, rng);
                last = 0;
            }
        }
        byeLine(p, who, minds, sense, bankFirst, chat, last, rng);
        return p;
    }

    /** Most of the time someone says bye: in a pair the one who did not just speak, in a group whoever's turn it is. */
    private static void byeLine(Plan p, List<Character> who, Map<Integer, Mind> minds, Sense sense, boolean bankFirst,
                                double[] chat, int last, Random rng) {
        if (rng.nextDouble() >= 0.65) {
            return;
        }
        int i = who.size() == 2 ? 1 - Math.min(1, last) : TalkBrain.nextSpeaker(chat, last, -1, rng);
        Character s = who.get(i);
        Character to = i == 0 ? who.get(1) : who.get(0);
        Manner m = TalkBrain.manner(minds.get(s.getId()), sense.relation(s, to));
        add(p, s, to, TalkIntent.bye(m, to.getId()), sense.slots(s, to), bankFirst, false, false, false, rng);
    }

    /** The opener's manner to a group: the coolest it feels toward any of them (one rival there sours it). */
    static Manner groupManner(Mind me, Character a, List<Character> rest, Sense sense) {
        Manner worst = Manner.WARM;
        for (Character c : rest) {
            Manner m = TalkBrain.manner(me, sense.relation(a, c));
            if (m.ordinal() > worst.ordinal()) {
                worst = m;
            }
        }
        return worst;
    }

    static double[] chattiness(List<Character> who, Map<Integer, Mind> minds) {
        double[] out = new double[who.size()];
        for (int i = 0; i < who.size(); i++) {
            out[i] = minds.get(who.get(i).getId()).social();
        }
        return out;
    }

    private static Step add(Plan p, Character speaker, Character to, TalkIntent intent, Sense sense, boolean bankFirst,
                            boolean group, boolean pq, boolean answering, Random rng) {
        return add(p, speaker, to, intent, sense.slots(speaker, to), bankFirst, group, pq, false, answering, rng);
    }

    private static Step add(Plan p, Character speaker, Character to, TalkIntent intent, Map<String, String> slots,
                            boolean bankFirst, boolean group, boolean pq, boolean met, Random rng) {
        return add(p, speaker, to, intent, slots, bankFirst, group, pq, met, false, rng);
    }

    private static Step add(Plan p, Character speaker, Character to, TalkIntent intent, Map<String, String> slots,
                            boolean bankFirst, boolean group, boolean pq, boolean met, boolean answering, Random rng) {
        Map<String, String> s = new HashMap<>(slots == null ? Map.of() : slots);
        if (to != null) {
            s.putIfAbsent("name", to.getName());
        }
        TalkLines.Worded w = TalkLines.line(speaker, to, intent, s, group && intent.move() != Move.ANSWER, pq, met,
                answering, bankFirst, rng);
        Step step = new Step(speaker, to, intent, w);
        p.steps.add(step);
        return step;
    }

    /** A town's name for the lines ("Ellinia"), or "town". */
    static String townName(int mapId) {
        soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns.Town t =
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns.townOf(mapId);
        return t == null ? "town" : t.mapId() == soloMapling.ArtificialPlayer.BotLifeSystem.LifeTowns.FREE_MARKET ? "fm"
                : t.name();
    }
}
