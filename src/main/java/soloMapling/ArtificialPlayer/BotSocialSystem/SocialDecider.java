package soloMapling.ArtificialPlayer.BotSocialSystem;

import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemory;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ticket 181 stage 4: the social decision tick. {@code BotTalk.tick} hands it each map's bots; a bot that is due (its own
 * pace: 45 s to 3 minutes, shorter for the sociable and the lonely) and free looks at the bots it can see and knows,
 * lets {@link SocialActions#best} pick the best (act, target), and {@link SocialActions#perform} does it through the
 * {@link SocialBudget}. Cheap: a bot is looked at once per pace, over at most {@link #CANDIDATES} candidates and 16
 * acts, from memory only; at most {@link #PER_MAP} bots decide per map per tick; no model call, no database read.
 *
 * <p>This supplements the existing bot-talk tick (arrival greetings and idle chats stay as they are); both go through the
 * same free checks and the same {@link SocialBudget}, so they cannot talk over each other. The bot's mind (its needs
 * and emotions) steers WHICH act it takes and toward whom, not only what it says. Switch: {@link SocialActions#enabled}.
 */
public final class SocialDecider {

    /** What a bot can see: this far horizontally and vertically (px), nearest first. */
    static final int SIGHT_X = 900, SIGHT_Y = 300;
    static final int CANDIDATES = 6;
    static final int PER_MAP = 2;
    static final long PACE_MIN_MS = 45_000L, PACE_MAX_MS = 180_000L;

    private static final Map<Integer, Long> nextAt = new ConcurrentHashMap<>();

    private SocialDecider() {
    }

    /**
     * One map's turn: up to {@link #PER_MAP} due bots among {@code botIds} decide and act. {@code botIds} are the bots on
     * one map (any order). Returns how many acts happened.
     */
    public static int onMap(int[] botIds, long now) {
        if (!SocialActions.enabled() || botIds.length < 2) {
            return 0;
        }
        int decided = 0, acted = 0;
        SocialWorld world = SocialActions.world();
        for (int pass = 0; pass < 2 && decided < PER_MAP; pass++) { // pass 0: bots with an open invitation to answer
            for (int id : botIds) {
                if (decided >= PER_MAP) {
                    break;
                }
                if ((pass == 0) != SocialActions.hasInviteFor(id, now)) {
                    continue;
                }
                Long due = nextAt.get(id);
                if (due != null && due > now) {
                    continue;
                }
                nextAt.put(id, now + pace(id, now));
                decided++;
                SocialMetrics.countDecision("decisions");
                if (decideOne(id, world, now)) {
                    acted++;
                }
            }
        }
        return acted;
    }

    /** One bot's decision; true when it did something. */
    private static boolean decideOne(int id, SocialWorld world, long now) {
        if (LifeMemoryStore.peek(id) == null) {
            SocialMetrics.countDecision("skipped-no-mind");
            return false;
        }
        if (!world.free(id)) {
            SocialMetrics.countDecision("skipped-busy");
            return false;
        }
        List<Integer> candidates = candidates(id, world, now);
        if (candidates.isEmpty()) {
            SocialMetrics.countDecision("idle-nobody-known");
            return false;
        }
        var best = SocialActions.best(id, candidates, now);
        if (best.isEmpty()) {
            SocialMetrics.countDecision("idle");
            return false;
        }
        SocialActions.Scored pick = best.get();
        SocialActions.Outcome out = SocialActions.perform(id, pick.targetId(), pick.kind(), now);
        SocialMetrics.countDecision(out.happened() ? "acted" : "act-refused");
        return out.happened();
    }

    /**
     * Makes {@code id} due again within 3-15 seconds: an invitation was just made to it and it should answer while the
     * invitation is open (it lapses after {@link SocialActions#INVITE_TTL_MS}).
     */
    public static void expedite(int id, long now) {
        nextAt.put(id, now + 3_000L + (long) (SocialSim.random(id, "social-pace").nextDouble() * 12_000L));
    }

    /** Whether this bot's next decision is due (a bot never looked at is due). */
    public static boolean due(int id, long now) {
        Long due = nextAt.get(id);
        return due == null || due <= now;
    }

    /** Upkeep of what acts leave running (see {@link SocialActions#tick}). */
    public static void upkeep(long now) {
        if (SocialActions.enabled()) {
            SocialActions.tick(now);
        }
    }

    /**
     * The bots {@code id} can see and knows: nearest first, at most {@link #CANDIDATES}; a bot that holds an open
     * invitation from {@code id}'s side or sent one to it is always included. A stranger is not a candidate: a bot acts
     * only on what it knows.
     */
    static List<Integer> candidates(int id, SocialWorld world, long now) {
        List<SocialWorld.Body> near = world.nearby(id, SIGHT_X, SIGHT_Y);
        SocialWorld.Body me = world.body(id);
        if (me == null || near.isEmpty()) {
            return List.of();
        }
        List<SocialWorld.Body> sorted = new ArrayList<>(near);
        sorted.sort((a, b) -> Integer.compare(me.dx(a), me.dx(b)));
        List<Integer> out = new ArrayList<>(CANDIDATES);
        for (SocialWorld.Body o : sorted) {
            if (o.player() || o.id() == id) {
                continue;
            }
            boolean invited = SocialActions.pendingInvite(id, o.id(), now) != null;
            if (invited || known(id, o.id())) {
                out.add(o.id());
                if (out.size() >= CANDIDATES) {
                    break;
                }
            }
        }
        return out;
    }

    /** Whether {@code id} knows {@code other}: it holds an edge to it (memory only). */
    static boolean known(int id, int other) {
        Stage s = BotBonds.stage(id, other);
        if (s != Stage.STRANGER) {
            return true;
        }
        Axes a = BotBonds.axes(id, other);
        return a.familiarity() >= 0.04;
    }

    /** How long this bot waits before it looks around again: the sociable and the lonely sooner. */
    static long pace(int id, long now) {
        double span = PACE_MAX_MS - PACE_MIN_MS;
        double draw = SocialSim.random(id, "social-pace").nextDouble();
        double eager = 0.5;
        LifeMemory mem = LifeMemoryStore.peek(id);
        if (mem != null) {
            eager = mem.mind(m -> 0.5 * m.trait(Trait.SOCIABILITY) + 0.5 * m.need(Need.COMPANIONSHIP, now));
        }
        double detail = SocialActions.world().paceFactor(id); // the social LOD: unwatched bots decide less often
        long base = (long) (PACE_MIN_MS + span * draw * (1.3 - 0.8 * eager));
        return Double.isInfinite(detail) ? Long.MAX_VALUE / 4 : (long) (base * Math.max(1.0, detail));
    }

    /**
     * What this bot's mind adds to the daily-life brain's SOCIAL need: the part of companionship above its comfortable
     * level and its loneliness, at most about 0.4. 0 when its memory is not loaded or social acts are off. A small
     * context bonus, so a lonely bot is a little more likely to pick a hang-out, never a different brain.
     */
    public static double socialPull(int botId, long now) {
        if (!SocialActions.enabled()) {
            return 0;
        }
        LifeMemory mem = LifeMemoryStore.peek(botId);
        if (mem == null) {
            return 0;
        }
        return mem.mind(m -> 0.25 * Math.max(0, m.need(Need.COMPANIONSHIP, now) - 0.3)
                + 0.2 * m.emotion(Emotion.LONELINESS, now));
    }

    /**
     * What a bot's mind says to the talk decisions ({@code BotTalk.WorldSense.mind}): mood (-1..1), the companionship
     * need, loneliness, anger and the sociability trait (0..1 each, mood aside). {@code TalkBrain}'s willingness and
     * manner read mood; the greeting, chat and ask choices read the sociable and killer shares and the COMPANY goal, all
     * of which WorldSense now nudges from these. Memory only.
     */
    public record TalkInputs(double mood, double companionship, double loneliness, double anger, double sociability) {
    }

    public static TalkInputs talkInputs(LifeMemory mem, long now) {
        return mem.mind(m -> new TalkInputs(m.mood(now), m.need(Need.COMPANIONSHIP, now),
                m.emotion(Emotion.LONELINESS, now), m.emotion(Emotion.ANGER, now), m.trait(Trait.SOCIABILITY)));
    }

    /** Test seam: forgets every bot's pace. */
    public static void resetForTest() {
        nextAt.clear();
    }
}
