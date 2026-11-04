package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions.Outcome;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The 16 social acts (ticket 271 stage 4), one nested class each. "Wrapped" acts run an existing flow through
 * {@link SocialFlows} (the bot-talk greeting and chat, the help ask and trip, the party invitation, the gossip pass);
 * "built" acts have their own outcome here. U = the utility terms (all from {@link SocialContext}: needs, emotions,
 * traits, the actor's OWN edge, memory, the target's company, norms); {@code ~} marks a subtracted term.
 *
 * <table>
 *   <caption>the acts</caption>
 *   <tr><th>act</th><th>legal when (preconditions)</th><th>U drivers</th><th>executes / lands when</th><th>state it changes</th></tr>
 *   <tr><td>GREET (wrapped)</td><td>in earshot, knows the target, both free, not avoiding, not greeted in 10 min</td>
 *       <td>sociability, warmth, companionship need, loneliness; ~resentment, rivalry, embarrassment, a distrusted company</td>
 *       <td>bot-talk greeting plan; lands when the plan starts</td><td>companionship need (+bot-talk's own notes and bond)</td></tr>
 *   <tr><td>CONVERSE (wrapped)</td><td>in earshot, ACQUAINTANCE or better and not RIVAL/ESTRANGED, both idle</td>
 *       <td>companionship + belonging needs, sociability, warmth, loneliness; ~resentment, distrust</td>
 *       <td>bot-talk chat plan (may end in an ask); lands when the plan starts</td>
 *       <td>needs, loneliness (+bot-talk's THREAD bond and notes)</td></tr>
 *   <tr><td>COMPLIMENT (built)</td><td>in earshot, knows, not sour, no open fight, honest bots need real admiration, 45 min gap</td>
 *       <td>agreeableness, empathy, affection, respect, belonging need; ~jealousy x rivalry, resentment</td>
 *       <td>a bank line as a bubble + emote; lands when the line was sent</td>
 *       <td>target's edge: COMPLIMENT bond event; target pride/happiness up, recognition need met; memory both sides</td></tr>
 *   <tr><td>ASK_HELP (wrapped)</td><td>the help ask is allowed (a stronger grinder here), ACQUAINTANCE+, not sour, trust above -0.2</td>
 *       <td>achievement + security needs, ambition, trust, respect; ~confidence, owing the other</td>
 *       <td>bot-talk forced HELP ask (the other's yes/no is TalkBrain's); lands when the ask was made</td>
 *       <td>bot-talk's: help trip, notes, ask memory</td></tr>
 *   <tr><td>OFFER_HELP (wrapped)</td><td>actor 5-20 levels above the target, both on a grind map, actor can drop what it does</td>
 *       <td>empathy, loyalty, affection, owing, recognition need; ~its own achievement drive</td>
 *       <td>the help trip (party + keeps grinding there); lands when it formed</td>
 *       <td>target's edge: BUFF event; pride, recognition; HELP memory both sides</td></tr>
 *   <tr><td>GIVE_GIFT (built, seam)</td><td>FRIEND or affection 0.3+, not sour, the {@link GiftSeam} can give, 6 h gap</td>
 *       <td>affection, loyalty, empathy, attraction, owing; ~(none)</td>
 *       <td>{@link GiftSeam#give}; lands when it was done</td>
 *       <td>default: GIFT on the receiver's edge, GIFT_GIVEN on the giver's, GIFTED push; both GIFT memory, affection and pride</td></tr>
 *   <tr><td>INVITE (wrapped)</td><td>in earshot, ACQUAINTANCE+, not sour, no open fight, the party or come-along is possible, none open</td>
 *       <td>companionship and belonging needs, sociability, warmth, curiosity, loneliness; ~resentment</td>
 *       <td>posts an invitation (a bubble) the other answers on its own tick; lands when posted</td><td>an open {@link SocialActions.Invite}</td></tr>
 *   <tr><td>ACCEPT / DECLINE (wrapped)</td><td>an open invitation from the target</td>
 *       <td>willingness: warmth, sociability, companionship need; ~resentment, distrust (ACCEPT; DECLINE is its mirror)</td>
 *       <td>ACCEPT carries the invitation out (the duo or PQ party, the trips); DECLINE says no politely</td>
 *       <td>ACCEPT: party/trips, needs, PARTY memory; DECLINE: the inviter's sadness and embarrassment, ASKED memory</td></tr>
 *   <tr><td>APOLOGIZE (built)</td><td>in earshot, an open fight the actor remembers, no apology since, actor not too angry (anger under 0.55) or bitter (resentment under 0.85)</td>
 *       <td>empathy, agreeableness, embarrassment (guilt), starting the fight; ~pride x confidence, resentment, anger</td>
 *       <td>sorry line; the target takes it by its own agreeableness, resentment and pride; lands when taken</td>
 *       <td>taken: APOLOGY bond event both edges (resentment down, trust up), anger down, memory; refused: anger/embarrassment, memory</td></tr>
 *   <tr><td>RECONCILE (built)</td><td>an open fight that was apologized for, edge RIVAL/ESTRANGED or resentment 0.15+, actor cooled (resentment under 0.45)</td>
 *       <td>agreeableness, loneliness, companionship need, loyalty; ~pride, resentment, anger</td>
 *       <td>truce line; the target takes it by agreeableness, resentment, loyalty; lands when taken</td>
 *       <td>taken: RECONCILE bond event both edges (stage climbs one rung per event), mood up, avoid records cleared, memory ends the fight</td></tr>
 *   <tr><td>ARGUE (built)</td><td>in earshot, resentment 0.15+ or rivalry 0.25+ or RIVAL/ESTRANGED or angry and not fond; never a CLOSE_FRIEND or ROMANTIC unless resentment 0.5+; 3 a day per pair</td>
 *       <td>resentment, rivalry, anger, impulsiveness, confidence; ~agreeableness, patience, affection</td>
 *       <td>a game-only roast from the bank, the target answers; lands when the line was sent</td>
 *       <td>ARGUE bond event on both edges (resentment, rivalry up), both angrier, memory (who lost their temper)</td></tr>
 *   <tr><td>SHARE_INFO / SPREAD_RUMOR (wrapped)</td><td>in earshot, ACQUAINTANCE+, not sour; a true story the actor knows (a negative one, to a bot that trusts it, for the rumor; honest bots do not)</td>
 *       <td>sociability, curiosity, warmth (rumor: jealousy, low honesty and agreeableness)</td>
 *       <td>the {@code BotWorldGossip} / {@code BotGossip} pass; lands when the listener kept it</td><td>the listener's stories and REPORTED memory</td></tr>
 *   <tr><td>AVOID (built)</td><td>target within 450 px, actor idle, not already avoiding, resentment 0.2+ or fear 0.25+ of someone it is not warm with, or RIVAL/ESTRANGED or an open fight with anger or anxiety</td>
 *       <td>resentment, fear, anxiety, an open fight, embarrassment; ~anger, impulsiveness, affection, companionship need</td>
 *       <td>walks away at once; an avoid record keeps it 450 px off for 5-13 minutes then expires</td>
 *       <td>{@link SocialActions.Avoid} (readable by the other stages); anxiety eased; memory</td></tr>
 *   <tr><td>SPEND_TIME (built)</td><td>both idle and close, warmth 0.25+ or ROMANTIC, not sour, no open fight, not already together</td>
 *       <td>companionship + belonging needs, warmth, attraction, loneliness; ~resentment, its own drive, a distrusted company</td>
 *       <td>stands beside the target for 6-12 minutes; judged when the time is up (still near each other)</td>
 *       <td>judged ok: companionship and belonging met, loneliness down, NEAR (PARTY_TIME in one party) bond events, memory</td></tr>
 * </table>
 * Utilities are about 0..1; {@link SocialActions#bar} is what a score must clear for the bot to act at all.
 */
final class SocialActs {

    private SocialActs() {
    }

    /** The wording of the notes the acts write (they name nobody) and the words {@link #read} classifies them by. */
    static final class Notes {
        /**
         * Notes of a fight, an apology and a making-up come in three wordings, rotated by the minute: the memory drops the
         * same text written again within 20 hours, and a second fight the same day must still be remembered.
         */
        static final String[] FIGHT_MINE = {"lost my temper at them", "lost my temper over it with them",
                "lost my temper again with them"};
        static final String[] FIGHT_THEIRS = {"they lost their temper at me", "they lost their temper over it with me",
                "they lost their temper again with me"};
        static final String[] SORRY_GIVEN = {"said sorry to them", "said sorry to them again", "said sorry to them, meant it"};
        static final String[] SORRY_TAKEN = {"they said sorry to me", "they said sorry to me again",
                "they said sorry to me, meant it"};
        static final String SORRY_REFUSED = "was turned away when i tried to make peace";
        static final String[] MADE_UP = {"made up after a fight", "made up after another fight",
                "made up with them after the fight"};

        static String of(String[] wordings, long now) {
            return wordings[(int) ((now / 60_000L) % wordings.length)];
        }

        static final String COMPLIMENT_GIVEN = "paid them a compliment";
        static final String COMPLIMENT_TAKEN = "they paid me a compliment";
        static final String HUNG_OUT = "hung out together for a while";
        static final String KEPT_AWAY = "kept my distance from them";
        static final String TURNED_DOWN = "turned down my invite";
        static final String ACCEPTED = "said yes to my invite";
        /** The words a recall is biased toward. */
        static final String TOPIC = "temper sorry made fight peace compliment";

        private Notes() {
        }
    }

    static List<SocialAction> all() {
        return List.of(new Greet(), new Converse(), new Compliment(), new AskHelp(), new OfferHelp(), new GiveGift(),
                new Invite(), new Accept(), new Decline(), new Apologize(), new Argue(), new Reconcile(),
                new ShareInfo(), new SpreadRumor(), new Avoid(), new SpendTime());
    }

    // ── what the actor remembers ──

    /** Classifies the recalled notes (newest wins) into the pair's recollection. */
    static SocialContext.Recollection read(List<BotMemory.Note> notes, long now) {
        long fightAt = 0, sorryAt = 0, madeUpAt = 0;
        boolean mine = false;
        int warm = 0, bad = 0;
        for (BotMemory.Note n : notes) {
            String t = n.text().toLowerCase(java.util.Locale.ROOT);
            long at = n.createdAt();
            if (t.contains("temper")) {
                if (at >= fightAt) {
                    fightAt = at;
                    mine = t.contains("my temper");
                }
                bad++;
            } else if (t.contains("said sorry")) {
                sorryAt = Math.max(sorryAt, at);
                warm++;
            } else if (t.contains("made up")) {
                madeUpAt = Math.max(madeUpAt, at);
                warm++;
            } else if (n.valence() >= 0.3) {
                warm++;
            } else if (n.valence() <= -0.3) {
                bad++;
            }
        }
        boolean open = fightAt > 0 && fightAt > madeUpAt && now - fightAt < 30L * 24 * 3_600_000;
        return new SocialContext.Recollection(open, open && mine, open && sorryAt >= fightAt && sorryAt > 0, warm, bad);
    }

    // ── helpers ──

    static double pos(double v) {
        return Math.max(0, v);
    }

    static double clamp01(double v) {
        return Math.max(0, Math.min(1, v));
    }

    static Random linesRng(int botId) {
        return SocialSim.random(botId, "social-line");
    }

    static String lineTo(String node, SocialWorld.Body to, int speaker, String fallback) {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("PLAYER", to.name());
        tokens.put("LEVEL", Integer.toString(to.level()));
        return SocialLines.pick(node, tokens, linesRng(speaker), fallback);
    }

    static boolean say(int speaker, String line) {
        return line != null && SocialActions.world().say(speaker, line);
    }

    static void remember(int actor, int subject, BotMemory.Kind kind, String text, double valence, int mapId, long now) {
        SocialPilot.onMemory(actor);
        SocialActions.world().remember(actor, subject, kind, text, valence, mapId, now);
    }

    /**
     * How much social drive the bot has now, 0.15..1: mostly its most pressing unmet need for company, a little its sociability
     * (companionship, belonging, loneliness). The friendly acts scale by it, so a bot whose needs are met does little even
     * with a close friend beside it while a starved socializer acts on weak ties; meeting the need (talking, hanging out)
     * is what quietens it. Hostile and withdrawing acts are driven by
     * anger, fear and grudges instead and are not scaled.
     */
    static double energy(SocialContext.Self s) {
        double pressure = Math.max(s.n(Need.COMPANIONSHIP), Math.max(0.8 * s.n(Need.BELONGING), s.e(Emotion.LONELINESS)));
        return 0.15 + 0.85 * clamp01(0.3 * s.t(Trait.SOCIABILITY) + 0.7 * pressure);
    }

    /**
     * Habituation: a repeat of the same act to the same bot soon after the last one is worth less (up to 0.4 less right
     * after, fading to nothing over four of the act's own gaps), so a bot does not run one gesture on a loop.
     */
    static double stale(SocialContext c, SocialActKind kind) {
        long gap = SocialBudget.cooldownMs(SocialActions.budgetKey(kind));
        long since = c.since(kind);
        return since == Long.MAX_VALUE ? 0 : 0.4 * Math.max(0, 1 - since / (4.0 * Math.max(1, gap)));
    }

    /** The edge-and-mind based answer to "does this bot want it": the base of ACCEPT and DECLINE. */
    static double willingness(SocialContext c) {
        SocialContext.Self s = c.self();
        Axes e = c.edge();
        return clamp01(0.40 + 0.35 * pos(c.warmth()) - 0.5 * e.resentment() - 0.3 * pos(-e.trust())
                + 0.25 * (s.t(Trait.SOCIABILITY) - 0.5) + 0.35 * (s.n(Need.COMPANIONSHIP) - 0.3)
                + 0.1 * (s.t(Trait.CURIOSITY) - 0.5) - 0.2 * s.e(Emotion.ANGER));
    }

    private abstract static class Base implements SocialAction {
        private final SocialActKind kind;

        Base(SocialActKind kind) {
            this.kind = kind;
        }

        @Override
        public SocialActKind kind() {
            return kind;
        }

        /** The gate every talking act shares: in earshot and not avoiding. Null when it holds. */
        String talkGate(SocialContext c) {
            if (!c.sameMap()) {
                return "not on the same map";
            }
            if (!c.withinEar()) {
                return "out of earshot";
            }
            if (c.avoiding()) {
                return "is avoiding them";
            }
            return null;
        }

        /** Knows them, is not on sour terms, and no fight is open: the gate of the friendly acts. */
        String friendlyGate(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            if (!c.knows()) {
                return "does not know them";
            }
            if (c.sourRelation()) {
                return "on sour terms";
            }
            if (c.memory().openFight()) {
                return "a fight is still open";
            }
            return null;
        }

        /** ACQUAINTANCE or better, the floor of the acts that ask something of a bot. */
        String acquainted(SocialContext c) {
            return c.stage() == Stage.STRANGER ? "only a stranger" : null;
        }
    }

    // ── 1. GREET (wrapped) ──

    static final class Greet extends Base {
        Greet() {
            super(SocialActKind.GREET);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            if (!c.knows()) {
                return "does not know them";
            }
            if (c.since(kind()) < 10 * 60_000L) {
                return "greeted them a moment ago";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            double u = energy(s) * (0.18 + 0.30 * s.t(Trait.SOCIABILITY) + 0.35 * pos(c.warmth()) + 0.25 * s.n(Need.COMPANIONSHIP)
                    + 0.15 * s.e(Emotion.LONELINESS) + 0.10 * e.familiarity())
                    - 0.45 * e.resentment() - 0.30 * e.rivalry() - 0.25 * s.e(Emotion.EMBARRASSMENT)
                    - 0.20 * s.e(Emotion.ANXIETY) * (1 - s.t(Trait.CONFIDENCE))
                    - 0.35 * s.t(Trait.JEALOUSY) * c.distrustedCompany();
            return c.memory().openFight() && !c.memory().apologized() ? u - 0.3 : u;
        }

        @Override
        public boolean recordsItself() {
            return true;
        }

        @Override
        public Outcome execute(SocialContext c) {
            SocialFlows.Flow f = SocialActions.flows().run(kind(), c.actor().id(), c.target().id(), c.now());
            if (!f.ok()) {
                return Outcome.missed(kind(), 0, f.reason());
            }
            SocialActions.mind(c.actor().id(), m -> m.satisfy(Need.COMPANIONSHIP, 0.05, c.now()));
            SocialActions.mind(c.target().id(), m -> m.satisfy(Need.COMPANIONSHIP, 0.05, c.now()));
            return Outcome.ok(kind(), 0, f.reason());
        }
    }

    // ── 2. CONVERSE (wrapped) ──

    static final class Converse extends Base {
        Converse() {
            super(SocialActKind.CONVERSE);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            if (c.stage() == Stage.STRANGER || c.stage() == Stage.RIVAL || c.stage() == Stage.ESTRANGED
                    || c.edge().familiarity() < 0.1) {
                return "not acquainted enough for a chat";
            }
            if (!c.targetIdle()) {
                return "the other is not free to chat";
            }
            if (c.since(kind()) < 8 * 60_000L) {
                return "chatted with them a moment ago";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return energy(s) * (0.12 + 0.35 * s.n(Need.COMPANIONSHIP) + 0.20 * s.n(Need.BELONGING) + 0.30 * s.t(Trait.SOCIABILITY)
                    + 0.45 * pos(c.warmth()) + 0.25 * s.e(Emotion.LONELINESS) + 0.10 * s.t(Trait.CURIOSITY)
                    + 0.10 * s.t(Trait.EMPATHY)) - 0.40 * e.resentment() - 0.20 * pos(-e.trust())
                    - 0.3 * s.t(Trait.JEALOUSY) * c.distrustedCompany() - 0.5 * stale(c, kind());
        }

        @Override
        public boolean needsIdle() {
            return true;
        }

        @Override
        public boolean recordsItself() {
            return true;
        }

        @Override
        public Outcome execute(SocialContext c) {
            SocialFlows.Flow f = SocialActions.flows().run(kind(), c.actor().id(), c.target().id(), c.now());
            if (!f.ok()) {
                return Outcome.missed(kind(), 0, f.reason());
            }
            for (int id : new int[]{c.actor().id(), c.target().id()}) {
                SocialActions.mind(id, m -> {
                    m.satisfy(Need.COMPANIONSHIP, 0.15, c.now());
                    m.satisfy(Need.BELONGING, 0.05, c.now());
                    m.feel(Emotion.LONELINESS, -0.10, c.now());
                    m.feel(Emotion.HAPPINESS, 0.05, c.now());
                });
            }
            return Outcome.ok(kind(), 0, f.reason());
        }
    }

    // ── 3. COMPLIMENT (built) ──

    static final class Compliment extends Base {
        Compliment() {
            super(SocialActKind.COMPLIMENT);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (c.edge().familiarity() < 0.08) {
                return "barely knows them";
            }
            if (c.since(kind()) < 45 * 60_000L) {
                return "paid them a compliment lately";
            }
            if (c.self().t(Trait.HONESTY) > 0.6 && Math.max(c.edge().affection(), c.edge().respect()) < 0.15) {
                return "nothing honest to say";
            }
            return null;
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return energy(s) * (0.08 + 0.35 * s.t(Trait.AGREEABLENESS) + 0.25 * s.t(Trait.EMPATHY) + 0.40 * pos(e.affection())
                    + 0.30 * pos(e.respect()) + 0.20 * s.n(Need.BELONGING) + 0.15 * e.attraction()
                    + 0.10 * s.t(Trait.SOCIABILITY)) - 0.30 * s.t(Trait.JEALOUSY) * e.rivalry() - 0.20 * e.resentment()
                    - 0.25 * s.t(Trait.JEALOUSY) * c.distrustedCompany() - stale(c, kind());
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            boolean said = say(a, lineTo("SocialCompliment", c.target(), a, "nice one {PLAYER}"));
            if (!said) {
                return Outcome.missed(kind(), 0, "the line could not be sent");
            }
            SocialActions.world().emote(a, 2);
            BotBonds.record(t, a, BondEvent.COMPLIMENT, now, false); // the target's own edge to the actor warms
            SocialActions.mind(t, m -> {
                m.feel(Emotion.PRIDE, 0.15, now);
                m.feel(Emotion.HAPPINESS, 0.10, now);
                m.satisfy(Need.RECOGNITION, 0.25, now);
                m.satisfy(Need.BELONGING, 0.05, now);
            });
            SocialActions.mind(a, m -> {
                m.satisfy(Need.BELONGING, 0.05, now);
                m.feel(Emotion.AFFECTION, 0.03, now);
            });
            remember(a, t, BotMemory.Kind.CHAT, Notes.COMPLIMENT_GIVEN, 0.3, c.actor().mapId(), now);
            remember(t, a, BotMemory.Kind.CHAT, Notes.COMPLIMENT_TAKEN, 0.5, c.actor().mapId(), now);
            return Outcome.ok(kind(), 0, "complimented " + c.target().name());
        }
    }

    // ── 4. ASK_HELP (wrapped) ──

    static final class AskHelp extends Base {
        AskHelp() {
            super(SocialActKind.ASK_HELP);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            if (c.stage() == Stage.STRANGER) {
                return "only a stranger";
            }
            if (c.sourRelation()) {
                return "on sour terms";
            }
            if (c.edge().trust() < -0.2) {
                return "does not trust them";
            }
            if (c.since(kind()) < 10 * 60_000L) {
                return "asked them a moment ago";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return 0.05 + 0.50 * s.n(Need.ACHIEVEMENT) * (0.6 + 0.6 * s.t(Trait.AMBITION)) + 0.20 * s.n(Need.SECURITY)
                    + 0.30 * pos(e.trust()) + 0.20 * pos(e.respect()) + 0.10 * s.t(Trait.SOCIABILITY)
                    - 0.25 * s.t(Trait.CONFIDENCE) - 0.30 * pos(e.obligation()) * s.t(Trait.LOYALTY);
        }

        @Override
        public boolean recordsItself() {
            return true;
        }

        @Override
        public Outcome execute(SocialContext c) {
            SocialFlows.Flow f = SocialActions.flows().run(kind(), c.actor().id(), c.target().id(), c.now());
            return f.ok() ? Outcome.ok(kind(), 0, f.reason()) : Outcome.missed(kind(), 0, f.reason());
        }
    }

    // ── 5. OFFER_HELP (wrapped) ──

    static final class OfferHelp extends Base {
        OfferHelp() {
            super(SocialActKind.OFFER_HELP);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (c.stage() == Stage.STRANGER) {
                return "only a stranger";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return energy(s) * (0.05 + 0.40 * s.t(Trait.EMPATHY) + 0.25 * s.t(Trait.LOYALTY) + 0.35 * pos(e.affection())
                    + 0.25 * pos(e.obligation()) + 0.20 * s.n(Need.RECOGNITION) + 0.10 * s.t(Trait.AGREEABLENESS))
                    - 0.30 * s.n(Need.ACHIEVEMENT) * s.t(Trait.AMBITION);
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            SocialFlows.Flow f = SocialActions.flows().run(kind(), a, t, now);
            if (!f.ok()) {
                return Outcome.missed(kind(), 0, f.reason());
            }
            BotBonds.record(t, a, BondEvent.BUFF, now, false); // the helped one is a little fonder and owes it
            SocialActions.mind(a, m -> {
                m.satisfy(Need.RECOGNITION, 0.15, now);
                m.feel(Emotion.PRIDE, 0.10, now);
            });
            SocialActions.mind(t, m -> {
                m.feel(Emotion.AFFECTION, 0.10, now);
                m.satisfy(Need.SECURITY, 0.10, now);
            });
            remember(a, t, BotMemory.Kind.HELP, "went to help them train", 0.4, c.actor().mapId(), now);
            remember(t, a, BotMemory.Kind.HELP, "came to help me train", 0.5, c.actor().mapId(), now);
            return Outcome.ok(kind(), 0, f.reason());
        }
    }

    // ── 6. GIVE_GIFT (built, through the seam) ──

    static final class GiveGift extends Base {
        GiveGift() {
            super(SocialActKind.GIVE_GIFT);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (!(c.stage() == Stage.FRIEND || c.closeBond() || c.edge().affection() >= 0.3)) {
                return "not close enough for a gift";
            }
            if (c.edge().familiarity() < 0.2) {
                return "does not know them well enough";
            }
            if (c.since(kind()) < 6 * 60 * 60_000L) {
                return "gave them something lately";
            }
            return SocialActions.giftSeam.blocker(c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return energy(s) * (0.04 + 0.30 * pos(e.affection()) + 0.20 * s.t(Trait.LOYALTY) + 0.20 * s.t(Trait.EMPATHY)
                    + 0.30 * e.attraction() + 0.35 * pos(e.obligation()) + 0.10 * s.t(Trait.AGREEABLENESS))
                    - stale(c, kind());
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            GiftSeam.Result r = SocialActions.giftSeam.give(a, t, now);
            if (r == null || !r.done()) {
                return Outcome.missed(kind(), 0, r == null ? "the gift seam gave nothing" : r.reason());
            }
            BotBonds.record(a, t, BondEvent.GIFT_GIVEN, now, false); // the giver's own edge: a little fonder, the other owes it
            SocialActions.mind(t, m -> {
                m.feel(Emotion.AFFECTION, 0.15, now);
                m.feel(Emotion.HAPPINESS, 0.10, now);
            });
            SocialActions.mind(a, m -> {
                m.feel(Emotion.PRIDE, 0.05, now);
                m.satisfy(Need.BELONGING, 0.10, now);
            });
            remember(a, t, BotMemory.Kind.GIFT, "gave them " + r.what(), 0.4, c.actor().mapId(), now);
            remember(t, a, BotMemory.Kind.GIFT, "gave me " + r.what(), 0.7, c.actor().mapId(), now);
            return Outcome.ok(kind(), 0, r.what());
        }
    }

    // ── 7. INVITE (wrapped) ──

    static final class Invite extends Base {
        Invite() {
            super(SocialActKind.INVITE);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (c.stage() == Stage.STRANGER) {
                return "only a stranger";
            }
            if (SocialActions.pendingInvite(c.target().id(), c.actor().id(), c.now()) != null) {
                return "already invited them";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return energy(s) * (0.08 + 0.35 * s.n(Need.COMPANIONSHIP) + 0.15 * s.n(Need.BELONGING) + 0.25 * s.t(Trait.SOCIABILITY)
                    + 0.35 * pos(c.warmth()) + 0.20 * s.t(Trait.CURIOSITY) + 0.15 * s.e(Emotion.LONELINESS))
                    - 0.30 * e.resentment() - 0.20 * pos(-e.trust());
        }

        @Override
        public boolean recordsItself() {
            return false;
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            SocialActions.Invite offer = SocialActions.flows().propose(a, t, now);
            if (offer == null) {
                return Outcome.missed(kind(), 0, "nothing to invite them to");
            }
            SocialActions.Invite open = SocialActions.openInvite(a, t, offer.what(), offer.mapId(), offer.payload(), now);
            say(a, lineTo("SocialInvite", c.target(), a, "{PLAYER} wanna party?"));
            SocialDecider.expedite(t, now); // the invited bot answers while the invitation is open
            return Outcome.ok(kind(), 0, "invited " + c.target().name() + " to " + open.what());
        }
    }

    // ── 8. ACCEPT and 9. DECLINE (wrapped) ──

    static final class Accept extends Base {
        Accept() {
            super(SocialActKind.ACCEPT);
        }

        @Override
        public String preconditions(SocialContext c) {
            return c.invite() == null ? "no open invitation from them" : c.sameMap() ? null : "not on the same map";
        }

        @Override
        public double utility(SocialContext c) {
            return 0.6 + 1.2 * (willingness(c) - 0.5);
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int me = c.actor().id(), inviter = c.target().id();
            SocialActions.Invite inv = c.invite();
            SocialActions.closeInvite(inv);
            SocialFlows.Flow f = SocialActions.flows().accept(inv, now);
            if (!f.ok()) {
                SocialActions.flows().release(inv);
                return Outcome.missed(kind(), 0, "could not go through with it: " + f.reason());
            }
            say(me, lineTo("SocialInviteAccept", c.target(), me, "sure {PLAYER}"));
            SocialActions.mind(inviter, m -> m.satisfy(Need.COMPANIONSHIP, 0.20, now));
            SocialActions.mind(me, m -> m.satisfy(Need.COMPANIONSHIP, 0.10, now));
            remember(inviter, me, BotMemory.Kind.PARTY, Notes.ACCEPTED, 0.4, c.actor().mapId(), now);
            remember(me, inviter, BotMemory.Kind.PARTY, "went along with their invite", 0.3, c.actor().mapId(), now);
            return Outcome.ok(kind(), 0, f.reason());
        }
    }

    static final class Decline extends Base {
        Decline() {
            super(SocialActKind.DECLINE);
        }

        @Override
        public String preconditions(SocialContext c) {
            return c.invite() == null ? "no open invitation from them" : c.sameMap() ? null : "not on the same map";
        }

        @Override
        public double utility(SocialContext c) {
            return 0.6 + 1.2 * (0.5 - willingness(c)) + 0.3 * c.edge().resentment();
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int me = c.actor().id(), inviter = c.target().id();
            SocialActions.Invite inv = c.invite();
            SocialActions.closeInvite(inv);
            SocialActions.flows().release(inv);
            say(me, lineTo("SocialInviteDecline", c.target(), me, "cant right now {PLAYER}"));
            SocialActions.mind(inviter, m -> {
                m.feel(Emotion.SADNESS, 0.05, now);
                m.feel(Emotion.EMBARRASSMENT, 0.04, now);
            });
            remember(inviter, me, BotMemory.Kind.ASKED, Notes.TURNED_DOWN, -0.2, c.actor().mapId(), now);
            return Outcome.ok(kind(), 0, "turned the invite down");
        }
    }

    // ── 10. APOLOGIZE (built) ──

    static final class Apologize extends Base {
        Apologize() {
            super(SocialActKind.APOLOGIZE);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            SocialContext.Recollection m = c.memory();
            if (!m.openFight()) {
                return "no fight to apologize for";
            }
            if (m.apologized()) {
                return "already said sorry for it";
            }
            if (c.self().e(Emotion.ANGER) >= 0.55) {
                return "still too angry to say sorry"; // hot: wait for the anger to fade (half-life 3 h)
            }
            if (c.edge().resentment() >= 0.85) {
                return "too bitter to say sorry";
            }
            if (c.since(kind()) < 20 * 60_000L) {
                return "tried a moment ago";
            }
            return null;
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return 0.10 + 0.40 * s.t(Trait.EMPATHY) + 0.30 * s.t(Trait.AGREEABLENESS) + 0.35 * s.e(Emotion.EMBARRASSMENT)
                    + (c.memory().iStartedIt() ? 0.25 : 0) + 0.20 * s.n(Need.BELONGING) + 0.15 * s.e(Emotion.LONELINESS)
                    + 0.15 * e.familiarity() - 0.45 * s.e(Emotion.PRIDE) * s.t(Trait.CONFIDENCE)
                    - 0.25 * e.resentment() - 0.30 * s.e(Emotion.ANGER);
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            int mapId = c.actor().mapId();
            if (!say(a, lineTo("SocialApology", c.target(), a, "{PLAYER} sorry about earlier"))) {
                return Outcome.missed(kind(), 0, "the line could not be sent");
            }
            SocialContext.Self theirs = SocialActions.selfOf(t, now);
            Axes theirEdge = BotBonds.axes(t, a); // server side: how the target really feels decides if it takes it
            double p = clamp01(0.30 + 0.45 * theirs.t(Trait.AGREEABLENESS) + 0.35 * (1 - theirEdge.resentment())
                    + 0.10 * theirs.t(Trait.EMPATHY) - 0.25 * theirs.e(Emotion.PRIDE) - 0.15 * theirs.e(Emotion.ANGER));
            boolean taken = SocialSim.random(t, "social-accept").nextDouble() < p;
            if (taken) {
                BotBonds.record(t, a, BondEvent.APOLOGY, now, false);
                BotBonds.record(a, t, BondEvent.APOLOGY, now, false);
                say(t, lineTo("SocialApologyAccepted", c.actor(), t, "its ok {PLAYER}"));
                SocialActions.mind(a, m -> {
                    m.feel(Emotion.EMBARRASSMENT, -0.15, now);
                    m.satisfy(Need.BELONGING, 0.10, now);
                });
                SocialActions.mind(t, m -> {
                    m.feel(Emotion.ANGER, -0.20, now);
                    m.feel(Emotion.AFFECTION, 0.05, now);
                    m.feel(Emotion.SADNESS, -0.05, now);
                });
                remember(a, t, BotMemory.Kind.CHAT, Notes.of(Notes.SORRY_GIVEN, now), 0.2, mapId, now);
                remember(t, a, BotMemory.Kind.CHAT, Notes.of(Notes.SORRY_TAKEN, now), 0.4, mapId, now);
                return Outcome.ok(kind(), 0, "apology taken by " + c.target().name());
            }
            say(t, lineTo("SocialApologyRefused", c.actor(), t, "not yet {PLAYER}"));
            SocialActions.mind(a, m -> {
                m.feel(Emotion.EMBARRASSMENT, 0.10, now);
                m.feel(Emotion.SADNESS, 0.05, now);
            });
            SocialActions.mind(t, m -> m.feel(Emotion.ANGER, 0.05, now));
            remember(a, t, BotMemory.Kind.CHAT, Notes.SORRY_REFUSED, -0.2, mapId, now);
            return Outcome.missed(kind(), 0, "rejected: " + c.target().name() + " would not hear it yet");
        }
    }

    // ── 11. ARGUE (built) ──

    static final class Argue extends Base {
        Argue() {
            super(SocialActKind.ARGUE);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            if (!c.knows()) {
                return "does not know them";
            }
            Axes e = c.edge();
            boolean grudge = e.resentment() >= 0.15 || e.rivalry() >= 0.25 || c.sourRelation()
                    || c.self().e(Emotion.ANGER) >= 0.35 && e.affection() < 0.1;
            if (!grudge) {
                return "nothing to argue about";
            }
            if (c.closeBond() && e.resentment() < 0.5) {
                return "never argues with a close friend over nothing";
            }
            if (c.memory().openFight() && c.memory().apologized()) {
                return "they already said sorry";
            }
            if (c.since(kind()) < 30 * 60_000L) {
                return "argued with them a moment ago";
            }
            if (!SocialActions.argueRoom(c.actor().id(), c.target().id(), c.now())) {
                return "enough arguing for one day";
            }
            return null;
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return 0.04 + 0.55 * e.resentment() + 0.30 * e.rivalry() + 0.45 * s.e(Emotion.ANGER)
                    + 0.25 * s.t(Trait.IMPULSIVENESS) + 0.15 * s.t(Trait.CONFIDENCE) + 0.10 * s.t(Trait.RISK_TOLERANCE)
                    + 0.30 * c.trustedCompany() * (e.resentment() > 0.1 ? 1 : 0)
                    - 0.45 * s.t(Trait.AGREEABLENESS) - 0.30 * s.t(Trait.PATIENCE) - 0.45 * pos(e.affection())
                    - 0.20 * s.e(Emotion.EMBARRASSMENT);
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            int mapId = c.actor().mapId();
            if (!say(a, lineTo("SocialArgue", c.target(), a, "{PLAYER} stay out of my mobs"))) {
                return Outcome.missed(kind(), 0, "the line could not be sent");
            }
            SocialActions.argued(a, t, now);
            SocialActions.world().emote(a, 5);
            say(t, lineTo("SocialArgueBack", c.actor(), t, "whatever {PLAYER}"));
            BotBonds.record(t, a, BondEvent.ARGUE, now, false);
            BotBonds.record(a, t, BondEvent.ARGUE, now, false);
            SocialActions.mind(a, m -> {
                m.feel(Emotion.ANGER, 0.15, now);
                m.feel(Emotion.HAPPINESS, -0.05, now);
                m.feel(Emotion.EMBARRASSMENT, 0.05, now);
            });
            SocialActions.mind(t, m -> {
                m.feel(Emotion.ANGER, 0.30, now);
                m.feel(Emotion.SADNESS, 0.05, now);
                m.feel(Emotion.HAPPINESS, -0.10, now);
            });
            remember(a, t, BotMemory.Kind.CHAT, Notes.of(Notes.FIGHT_MINE, now), -0.5, mapId, now);
            remember(t, a, BotMemory.Kind.CHAT, Notes.of(Notes.FIGHT_THEIRS, now), -0.7, mapId, now);
            return Outcome.ok(kind(), 0, "argued with " + c.target().name());
        }
    }

    // ── 12. RECONCILE (built) ──

    static final class Reconcile extends Base {
        Reconcile() {
            super(SocialActKind.RECONCILE);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = talkGate(c);
            if (g != null) {
                return g;
            }
            SocialContext.Recollection m = c.memory();
            if (!m.openFight()) {
                return "no fight to end";
            }
            if (!m.apologized()) {
                return "nobody said sorry yet";
            }
            Axes e = c.edge();
            if (!(c.sourRelation() || e.resentment() >= 0.15 || e.affection() < 0.1)) {
                return "nothing left to mend";
            }
            if (e.resentment() >= 0.45) {
                return "still too sore to make up";
            }
            if (c.since(kind()) < 30 * 60_000L) {
                return "tried a moment ago";
            }
            return null;
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return 0.12 + 0.35 * s.t(Trait.AGREEABLENESS) + 0.30 * s.e(Emotion.LONELINESS)
                    + 0.25 * s.n(Need.COMPANIONSHIP) + 0.20 * s.t(Trait.EMPATHY) + 0.25 * s.t(Trait.LOYALTY)
                    + 0.20 * e.familiarity() - 0.35 * s.e(Emotion.PRIDE) * s.t(Trait.CONFIDENCE)
                    - 0.40 * e.resentment() - 0.30 * s.e(Emotion.ANGER);
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            int mapId = c.actor().mapId();
            if (!say(a, lineTo("SocialReconcile", c.target(), a, "{PLAYER} lets start over"))) {
                return Outcome.missed(kind(), 0, "the line could not be sent");
            }
            SocialContext.Self theirs = SocialActions.selfOf(t, now);
            Axes theirEdge = BotBonds.axes(t, a);
            double p = clamp01(0.35 + 0.35 * theirs.t(Trait.AGREEABLENESS) + 0.40 * (1 - theirEdge.resentment())
                    + 0.15 * theirs.t(Trait.LOYALTY) - 0.15 * theirs.e(Emotion.ANGER));
            boolean taken = SocialSim.random(t, "social-accept").nextDouble() < p;
            if (!taken) {
                SocialActions.mind(a, m -> m.feel(Emotion.SADNESS, 0.05, now));
                return Outcome.missed(kind(), 0, "rejected: " + c.target().name() + " is not ready to make up");
            }
            BotBonds.record(t, a, BondEvent.RECONCILE, now, false);
            BotBonds.record(a, t, BondEvent.RECONCILE, now, false);
            SocialActions.clearAvoid(a, t);
            say(t, lineTo("SocialReconcileAccepted", c.actor(), t, "truce {PLAYER}"));
            for (int id : new int[]{a, t}) {
                SocialActions.mind(id, m -> {
                    m.feel(Emotion.HAPPINESS, 0.15, now);
                    m.feel(Emotion.ANGER, -0.20, now);
                    m.feel(Emotion.LONELINESS, -0.10, now);
                    m.satisfy(Need.BELONGING, 0.15, now);
                });
            }
            remember(a, t, BotMemory.Kind.CHAT, Notes.of(Notes.MADE_UP, now), 0.5, mapId, now);
            remember(t, a, BotMemory.Kind.CHAT, Notes.of(Notes.MADE_UP, now), 0.5, mapId, now);
            return Outcome.ok(kind(), 0, "made up with " + c.target().name());
        }
    }

    // ── 13. SHARE_INFO (wrapped) and 14. SPREAD_RUMOR (wrapped) ──

    static final class ShareInfo extends Base {
        ShareInfo() {
            super(SocialActKind.SHARE_INFO);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (c.stage() == Stage.STRANGER) {
                return "only a stranger";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            return energy(s) * (0.06 + 0.35 * s.t(Trait.SOCIABILITY) + 0.25 * s.t(Trait.CURIOSITY) + 0.25 * pos(c.warmth())
                    + 0.25 * s.n(Need.COMPANIONSHIP) + 0.15 * s.n(Need.RECOGNITION) + 0.10 * s.t(Trait.HONESTY));
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            SocialFlows.Flow f = SocialActions.flows().run(kind(), c.actor().id(), c.target().id(), now);
            if (!f.ok()) {
                return Outcome.missed(kind(), 0, f.reason());
            }
            SocialActions.mind(c.actor().id(), m -> m.satisfy(Need.COMPANIONSHIP, 0.08, now));
            SocialActions.mind(c.target().id(), m -> m.satisfy(Need.COMPANIONSHIP, 0.08, now));
            return Outcome.ok(kind(), 0, f.reason());
        }
    }

    static final class SpreadRumor extends Base {
        SpreadRumor() {
            super(SocialActKind.SPREAD_RUMOR);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (c.stage() == Stage.STRANGER) {
                return "only a stranger";
            }
            if (c.self().t(Trait.HONESTY) >= 0.75) {
                return "too honest to pass on a rumor";
            }
            if (c.edge().trust() < 0.1) {
                return "does not trust the listener enough";
            }
            return SocialActions.flows().blocker(kind(), c.actor().id(), c.target().id(), c.now());
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            return energy(s) * (0.03 + 0.30 * s.t(Trait.JEALOUSY) + 0.25 * (1 - s.t(Trait.AGREEABLENESS))
                    + 0.25 * (1 - s.t(Trait.HONESTY)) + 0.25 * s.t(Trait.SOCIABILITY) + 0.30 * pos(c.edge().trust()));
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            SocialFlows.Flow f = SocialActions.flows().run(kind(), c.actor().id(), c.target().id(), now);
            if (!f.ok()) {
                return Outcome.missed(kind(), 0, f.reason());
            }
            SocialActions.mind(c.actor().id(), m -> m.satisfy(Need.COMPANIONSHIP, 0.05, now));
            return Outcome.ok(kind(), 0, f.reason());
        }
    }

    // ── 15. AVOID (built) ──

    static final class Avoid extends Base {
        Avoid() {
            super(SocialActKind.AVOID);
        }

        @Override
        public String preconditions(SocialContext c) {
            if (!c.sameMap()) {
                return "not on the same map";
            }
            if (c.avoiding()) {
                return "already avoiding them";
            }
            if (c.distance() >= SocialActions.AVOID_KEEP_PX) {
                return "far enough away already";
            }
            if (!c.knows()) {
                return "does not know them";
            }
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            boolean reason = e.resentment() >= 0.2 || s.e(Emotion.FEAR) >= 0.25 && c.warmth() < 0.2 || c.sourRelation()
                    || c.memory().openFight() && (s.e(Emotion.ANGER) >= 0.2 || s.e(Emotion.ANXIETY) >= 0.2);
            return reason ? null : "no reason to keep away";
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            double unease = 1 - clamp01(c.warmth()); // fear and anxiety are felt toward someone it is not warm with
            return 0.05 + 0.45 * e.resentment() + 0.40 * s.e(Emotion.FEAR) * unease + 0.30 * s.e(Emotion.ANXIETY) * unease
                    + 0.25 * (1 - s.t(Trait.CONFIDENCE)) * unease + 0.20 * pos(-e.affection())
                    + (c.memory().openFight() ? 0.25 : 0) + 0.15 * s.e(Emotion.EMBARRASSMENT)
                    - 0.35 * s.e(Emotion.ANGER) - 0.30 * s.t(Trait.IMPULSIVENESS) - 0.25 * pos(e.affection())
                    - 0.20 * s.n(Need.COMPANIONSHIP) * s.t(Trait.SOCIABILITY) - stale(c, kind());
        }

        @Override
        public boolean needsIdle() {
            return true;
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            long span = (long) ((5 + 8 * (1 - c.self().t(Trait.PATIENCE))) * 60_000L);
            SocialActions.Avoid rec = new SocialActions.Avoid(a, t, now, now + span, SocialActions.AVOID_KEEP_PX,
                    c.memory().openFight() ? "after a fight" : c.edge().resentment() >= 0.2 ? "a grudge" : "uneasy");
            SocialActions.startAvoid(rec);
            SocialWorld w = SocialActions.world();
            w.walkTo(a, w.awayX(c.actor(), c.target().x(), SocialActions.AVOID_KEEP_PX + 50), c.actor().y());
            SocialActions.mind(a, m -> {
                m.feel(Emotion.ANXIETY, -0.05, now);
                m.feel(Emotion.SADNESS, 0.02, now);
            });
            remember(a, t, BotMemory.Kind.CHAT, Notes.KEPT_AWAY, -0.2, c.actor().mapId(), now);
            return Outcome.ok(kind(), 0, "keeping " + rec.keepPx() + " px from " + c.target().name() + " for "
                    + span / 60_000L + " min");
        }
    }

    // ── 16. SPEND_TIME (built) ──

    static final class SpendTime extends Base {
        SpendTime() {
            super(SocialActKind.SPEND_TIME);
        }

        @Override
        public String preconditions(SocialContext c) {
            String g = friendlyGate(c);
            if (g != null) {
                return g;
            }
            if (!(c.warmth() >= 0.25 || c.stage() == Stage.ROMANTIC)) {
                return "not close enough to just hang out";
            }
            if (!c.targetIdle()) {
                return "the other is busy";
            }
            if (SocialActions.together(c.actor().id(), c.target().id())) {
                return "already together";
            }
            if (c.since(kind()) < 20 * 60_000L) {
                return "spent time with them a moment ago";
            }
            return null;
        }

        @Override
        public double utility(SocialContext c) {
            SocialContext.Self s = c.self();
            Axes e = c.edge();
            return energy(s) * (0.06 + 0.40 * s.n(Need.COMPANIONSHIP) + 0.25 * s.n(Need.BELONGING) + 0.35 * pos(c.warmth())
                    + 0.25 * e.attraction() + 0.25 * s.e(Emotion.LONELINESS) + 0.15 * s.t(Trait.SOCIABILITY))
                    - 0.20 * s.n(Need.ACHIEVEMENT) * s.t(Trait.AMBITION) - 0.35 * e.resentment()
                    - 0.30 * s.t(Trait.JEALOUSY) * c.distrustedCompany();
        }

        @Override
        public boolean needsIdle() {
            return true;
        }

        @Override
        public Outcome execute(SocialContext c) {
            long now = c.now();
            int a = c.actor().id(), t = c.target().id();
            SocialWorld w = SocialActions.world();
            int side = c.actor().x() >= c.target().x() ? 1 : -1;
            w.walkTo(a, c.target().x() + side * 40, c.target().y());
            long span = (long) ((6 + 6 * SocialSim.random(a, "social-span").nextDouble()) * 60_000L);
            SocialActions.startTogether(new SocialActions.Together(a, t, c.actor().mapKey(), now, now + span));
            say(a, lineTo("SocialSpendTime", c.target(), a, "{PLAYER} lets just chill for a bit"));
            SocialActions.mind(a, m -> m.satisfy(Need.COMPANIONSHIP, 0.10, now));
            SocialActions.mind(t, m -> m.satisfy(Need.COMPANIONSHIP, 0.10, now));
            return Outcome.ok(kind(), 0, "staying near " + c.target().name() + " for " + span / 60_000L + " min");
        }
    }

    /**
     * A spend-time pair whose time is up: if both are still on one map and near each other it counts (needs met, bond
     * events, memory); else it was abandoned.
     */
    static void finishTogether(SocialActions.Together t, long now) {
        SocialWorld w = SocialActions.world();
        SocialWorld.Body x = w.body(t.a());
        SocialWorld.Body y = w.body(t.b());
        boolean still = x != null && y != null && x.mapKey() == y.mapKey() && x.dx(y) <= 350 && x.dy(y) <= 250;
        if (!still) {
            SocialMetrics.countAct(SocialActKind.SPEND_TIME, "abandoned");
            return;
        }
        SocialMetrics.countAct(SocialActKind.SPEND_TIME, "completed");
        for (int id : new int[]{t.a(), t.b()}) {
            SocialActions.mind(id, m -> {
                m.satisfy(Need.COMPANIONSHIP, 0.35, now);
                m.satisfy(Need.BELONGING, 0.20, now);
                m.feel(Emotion.LONELINESS, -0.25, now);
                m.feel(Emotion.AFFECTION, 0.05, now);
                m.feel(Emotion.HAPPINESS, 0.10, now);
            });
        }
        BondEvent event = w.sameParty(t.a(), t.b()) ? BondEvent.PARTY_TIME : BondEvent.NEAR;
        BotBonds.record(t.a(), t.b(), event, now, true);
        BotBonds.record(t.b(), t.a(), event, now, true);
        remember(t.a(), t.b(), BotMemory.Kind.CHAT, Notes.HUNG_OUT, 0.4, x.mapId(), now);
        remember(t.b(), t.a(), BotMemory.Kind.CHAT, Notes.HUNG_OUT, 0.4, x.mapId(), now);
    }
}
