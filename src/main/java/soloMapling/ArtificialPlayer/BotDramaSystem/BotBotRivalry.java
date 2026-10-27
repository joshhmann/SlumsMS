package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotGossip;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.Reputation;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 slice S7 "Beef": personal rivalries between two bots, on the RIVAL and NEMESIS bonds of slice S2
 * ({@link BotBonds}). Bot to bot, so free of the social gate; nothing here names or targets a real player.
 *
 * <ul>
 *   <li><b>Beef threads</b> ({@code BotThreads.Topic.RIVALRY}): on a map a real player watches, two idle rivals trade
 *   2-4 lines of trash talk about the game, grounded in their real bond - who holds the grudge, how many run-ins
 *   (the bond's hostile count) and whether it started with a KS ({@link #beefFact}). The thread tick looks for a rival
 *   pair among the idle bots first ({@link #rivalPair}); a Killer-leaning bot ({@code rivalPull}) starts one more
 *   often, and a bot that just turned villain more often still ({@link VillainArc#boost}).</li>
 *   <li><b>KS spats</b> ({@link #onBotKs}): a bot that takes another bot's mob on an observed map with a real player
 *   on it may get called out in map chat ("ks much?"), and a rival answers back. At most one spat per pair per
 *   {@link #SPAT_PAIR_GAP_MS} (10 min) and {@link #SPATS_PER_HOUR} (20) an hour world-wide.</li>
 *   <li><b>Call-outs</b> ({@link #maybeStep}): rarely, a bot calls its online nemesis out by megaphone, through the
 *   shared megaphone gate: every 5 minutes one roll, at most {@link #CALLOUTS_PER_HOUR} (2) an hour,
 *   {@link #CALLOUTS_PER_DAY} (6) a day and once a day per target.</li>
 * </ul>
 */
public final class BotBotRivalry {

    private static final Logger log = LoggerFactory.getLogger(BotBotRivalry.class);

    static final long SPAT_PAIR_GAP_MS = TimeUnit.MINUTES.toMillis(10);
    static final int SPATS_PER_HOUR = 20;
    static final long STEP_MS = TimeUnit.MINUTES.toMillis(5);
    static final int CALLOUTS_PER_HOUR = 2;
    static final int CALLOUTS_PER_DAY = 6;
    /** How many idle bots of a map the thread tick checks for a rival (each is at most K bond reads). */
    static final int RIVAL_SCAN_MAX = 24;
    /** Online bots one call-out step looks at. */
    static final int CALLOUT_SAMPLE = 60;

    /** How a call-out is shouted (a seam for tests): the shared megaphone gate. */
    @FunctionalInterface
    interface Megaphone {
        boolean offer(Character speaker, String node, Map<String, String> tokens, String key);
    }

    private static volatile Megaphone megaphone = (s, n, t, k) -> WorldEventMegaphones.getInstance().offerEvent(s, n, t, k);
    private static volatile long lastStepAt;
    /** UTC day -> call-outs that day. */
    private static final Map<Long, Integer> calloutsByDay = new java.util.concurrent.ConcurrentHashMap<>();

    private BotBotRivalry() {
    }

    // ── who has beef ──

    /** Whether {@code a} holds a grudge against {@code b} (a RIVAL or NEMESIS bond, hostility on). */
    public static boolean grudge(int a, int b) {
        return BotBonds.state(a, b).hostile();
    }

    /** Whether either bot holds a grudge against the other. */
    public static boolean rivals(int a, int b) {
        return a != b && (grudge(a, b) || grudge(b, a));
    }

    static boolean nemesis(int a, int b) {
        return BotBonds.state(a, b) == AffinityState.NEMESIS || BotBonds.state(b, a) == AffinityState.NEMESIS;
    }

    /**
     * The chance that {@code starter} picks a fight with its rival when they meet: 0.35, plus half its Killer pull,
     * plus its villain-arc boost, plus 0.15 for a nemesis; within 0.05..0.9.
     */
    public static double startChance(int starter, int other, long now) {
        double c = 0.35 + 0.5 * BeefKit.rivalPull(starter) + VillainArc.boost(starter, now)
                + (nemesis(starter, other) ? 0.15 : 0);
        return Math.max(0.05, Math.min(0.9, c));
    }

    /**
     * Among idle bots on one map, a rival pair to start a beef thread - the one holding the grudge first - or null (no
     * rivals here, Beef off, or the roll said no). Looks at the first {@link #RIVAL_SCAN_MAX} bots only, K bonds each.
     */
    public static List<Character> rivalPair(List<Character> idle, Random rng) {
        if (!BeefKit.enabled() || idle == null || idle.size() < 2) {
            return null;
        }
        Map<Integer, Character> byId = new HashMap<>();
        for (Character c : idle) {
            byId.put(c.getId(), c);
        }
        List<Character[]> pairs = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        long now = BeefKit.now();
        int scanned = 0;
        for (Character starter : idle) {
            if (scanned++ >= RIVAL_SCAN_MAX) {
                break;
            }
            for (int rival : BotBonds.rivalsOf(starter.getId())) {
                Character other = byId.get(rival);
                if (other != null) {
                    pairs.add(new Character[]{starter, other});
                    weights.add(0.2 + BeefKit.rivalPull(starter.getId()) + Math.max(0, VillainArc.boost(starter.getId(), now)));
                }
            }
        }
        if (pairs.isEmpty()) {
            return null;
        }
        double sum = 0;
        for (double w : weights) {
            sum += w;
        }
        double roll = rng.nextDouble() * sum;
        Character[] pick = pairs.getLast();
        for (int i = 0; i < pairs.size(); i++) {
            roll -= weights.get(i);
            if (roll < 0) {
                pick = pairs.get(i);
                break;
            }
        }
        if (rng.nextDouble() >= startChance(pick[0].getId(), pick[1].getId(), now)) {
            return null;
        }
        return List.of(pick[0], pick[1]);
    }

    /** A thread between these two (already picked) turns into beef: they are rivals and the starter's roll says so. */
    public static boolean beefFirst(Character a, Character b, Random rng) {
        if (!BeefKit.enabled() || a == null || b == null || !rivals(a.getId(), b.getId())) {
            return false;
        }
        Character starter = grudge(a.getId(), b.getId()) ? a : b;
        Character other = starter == a ? b : a;
        return rng.nextDouble() < startChance(starter.getId(), other.getId(), BeefKit.now());
    }

    /** The pair in speaking order for beef: the one holding the grudge opens. */
    public static List<Character> grudgeFirst(List<Character> pair) {
        Character a = pair.get(0);
        Character b = pair.get(1);
        return !grudge(a.getId(), b.getId()) && grudge(b.getId(), a.getId()) ? List.of(b, a) : pair;
    }

    /**
     * What a beef thread between {@code a} (holds the grudge) and {@code b} is about, from their real bond: how many
     * run-ins, whether it began with a KS, rival or nemesis. Names nobody but the two speakers.
     */
    public static BotGossip.Fact beefFact(Character a, Character b) {
        int hostile = 0;
        for (BotBonds.View v : BotBonds.bondsOf(a.getId())) {
            if (v.otherId() == b.getId()) {
                hostile = v.hostile();
                break;
            }
        }
        boolean ks = false;
        for (Reputation.Drama d : Reputation.dramaAbout(List.of(a.getId()), BeefKit.now())) {
            if (d.botId() == a.getId() && d.otherId() == b.getId()) {
                ks = d.cause() == BondEvent.KS;
                break;
            }
        }
        boolean nemesis = BotBonds.state(a.getId(), b.getId()) == AffinityState.NEMESIS;
        int count = Math.max(1, hostile);
        String node = nemesis ? "BeefOpenNemesis" : ks ? "BeefOpenKs" : "BeefOpen";
        String opener = DramaKit.pick(node, Map.of("OTHER", b.getName(), "COUNT", String.valueOf(count)));
        if (opener == null) {
            opener = b.getName() + " u again? ugh";
        }
        String about = a.getName() + " has beef with " + b.getName() + (ks ? " (" + b.getName() + " ksed "
                + a.getName() + ", " + count + " run-ins)" : hostile > 0 ? " (" + hostile + " run-ins)" : "")
                + (nemesis ? ", nemesis" : "");
        return new BotGossip.Fact("rivalry", about, opener, List.of(), b.getId(), true);
    }

    /** {@code b}'s comeback to {@code a}'s opener (the line bank), or null. */
    public static String beefReply(Character b, Character a) {
        return DramaKit.pick("BeefReply", Map.of("OTHER", a.getName()));
    }

    // ── KS spats ──

    /**
     * {@code ksr} just took {@code victim}'s mob on an observed map ({@code BotBonds.beforeBotHit}, after the KS bond
     * event). On a map a real player can see, the victim may call it out and a rival answers back. Memory work on the
     * caller's thread; the lines go off it.
     */
    public static void onBotKs(Character victim, Character ksr) {
        if (!BeefKit.enabled() || victim == null || ksr == null || victim.getMap() == null
                || !BotHelpers.isBot(victim) || !BotHelpers.isBot(ksr) || !DramaKit.realPlayerOnMap(victim)) {
            return;
        }
        long now = BeefKit.now();
        int lo = Math.min(victim.getId(), ksr.getId());
        int hi = Math.max(victim.getId(), ksr.getId());
        boolean rival = rivals(victim.getId(), ksr.getId());
        double chance = spatChance(victim.getId(), rival, now);
        Random rng = ThreadLocalRandom.current();
        if (!DramaKit.free("bb:spat:" + lo + ":" + hi, now) || rng.nextDouble() >= chance
                || !DramaKit.take("bb:spat:" + lo + ":" + hi, now, SPAT_PAIR_GAP_MS)
                || !DramaKit.hourly("bb-spat", SPATS_PER_HOUR, now)) {
            return;
        }
        boolean answer = rival || rng.nextBoolean();
        ExecutorServiceManager.runAsync(() -> spat(victim, ksr, rival, answer));
    }

    /** The chance a KS'd bot speaks up: a rival 0.6 + 0.4 x Killer pull, anyone else 0.15 + 0.5 x Killer pull. */
    static double spatChance(int victimId, boolean rival, long now) {
        double rp = BeefKit.rivalPull(victimId) + Math.max(0, VillainArc.boost(victimId, now));
        return Math.min(1, rival ? 0.6 + 0.4 * rp : 0.15 + 0.5 * rp);
    }

    static void spat(Character victim, Character ksr, boolean rival, boolean answer) {
        String said = DramaKit.say(victim, "KsSpatVictim", Map.of("OTHER", ksr.getName()));
        BeefKit.qa(victim.getMapId(), "KS SPAT " + victim.getName() + " -> " + ksr.getName() + (rival ? " (rivals)" : "")
                + ": " + said);
        if (said != null && answer) {
            BotTiming.after(1_500 + ThreadLocalRandom.current().nextInt(1_500), () -> {
                if (ksr.getMap() == null || ksr.getMapId() != victim.getMapId()) {
                    return;
                }
                String back = DramaKit.say(ksr, rival ? "KsSpatRival" : "KsSpatSorry", Map.of("OTHER", victim.getName()));
                BeefKit.qa(ksr.getMapId(), "KS SPAT BACK " + ksr.getName() + " -> " + victim.getName() + ": " + back);
            });
        }
    }

    // ── escalation after a beef thread (1.6.0 social trace: RIVALRY DECIDE / ACT) ──

    /** One escalation per pair per this long. */
    static final long ESCALATE_PAIR_GAP_MS = TimeUnit.HOURS.toMillis(6);
    /** Escalations world-wide per hour at most. */
    static final int ESCALATIONS_PER_HOUR = 3;
    /** When the grudge-holder goes after its rival: this long after the thread, give or take. */
    static final long ESCALATE_DELAY_MIN_MS = TimeUnit.MINUTES.toMillis(3);
    static final long ESCALATE_DELAY_MAX_MS = TimeUnit.MINUTES.toMillis(8);
    /** How long it stays on the rival's map before it goes back to its own day (where it stands, no warp). */
    static final long ESCALATE_STAY_MS = TimeUnit.MINUTES.toMillis(2);

    /** What the roll after a beef thread said: go (after {@code delayMs}) or not, and why. */
    public record Escalation(boolean go, String why, long delayMs) {
    }

    /**
     * The chance the grudge-holder takes a beef thread further: 0.08, plus a fifth of its Killer pull and villain
     * boost, plus 0.05 for a nemesis; at most 0.35.
     */
    static double escalateChance(int starter, int other, long now) {
        double c = 0.08 + 0.2 * (BeefKit.rivalPull(starter) + Math.max(0, VillainArc.boost(starter, now)))
                + (nemesis(starter, other) ? 0.05 : 0);
        return Math.max(0, Math.min(0.35, c));
    }

    /**
     * A beef thread between {@code a} (holds the grudge) and {@code b} just ended: a small chance, once per pair per
     * {@link #ESCALATE_PAIR_GAP_MS} and {@link #ESCALATIONS_PER_HOUR} an hour world-wide, that {@code a} goes after
     * {@code b} a few minutes later ({@link #escalate}). Bots only; memory work, the caps are taken only on a yes.
     */
    public static Escalation decideEscalation(Character a, Character b, long now, Random rng) {
        if (!BeefKit.enabled() || a == null || b == null || !BotHelpers.isBot(a) || !BotHelpers.isBot(b)) {
            return new Escalation(false, "beef off, or not two bots", 0);
        }
        if (!rivals(a.getId(), b.getId())) {
            return new Escalation(false, "no beef between them (any more)", 0);
        }
        String pair = "bb:escalate:" + Math.min(a.getId(), b.getId()) + ":" + Math.max(a.getId(), b.getId());
        if (!DramaKit.free(pair, now)) {
            return new Escalation(false, "pair cooldown (one escalation per " + ESCALATE_PAIR_GAP_MS / 3_600_000
                    + " h)", 0);
        }
        double chance = escalateChance(a.getId(), b.getId(), now);
        if (rng.nextDouble() >= chance) {
            return new Escalation(false, String.format(java.util.Locale.ROOT, "the roll said no (chance %.2f)", chance), 0);
        }
        if (!DramaKit.hourly("bb-escalate", ESCALATIONS_PER_HOUR, now)) {
            return new Escalation(false, "world cap (" + ESCALATIONS_PER_HOUR + " an hour)", 0);
        }
        DramaKit.take(pair, now, ESCALATE_PAIR_GAP_MS);
        long delay = ESCALATE_DELAY_MIN_MS + (long) (rng.nextDouble() * (ESCALATE_DELAY_MAX_MS - ESCALATE_DELAY_MIN_MS));
        return new Escalation(true, a.getName() + " goes after " + b.getName() + " in "
                + TimeUnit.MILLISECONDS.toSeconds(delay) + " s", delay);
    }

    /**
     * The escalation itself: {@code starterId} goes to its rival's map - only a map a real player can see, never a
     * boss, event or dungeon map - and starts on it again: on a map with mobs a training bot grinds right beside it
     * (taking its mobs starts a KS spat through the usual {@link #onBotKs} path), elsewhere it stands by it; either way
     * it says a beef opener and the rival answers back. After {@link #ESCALATE_STAY_MS} it goes back to its own day
     * where it stands. {@code done} hears true and what happened, or false and why not.
     */
    public static void escalate(int starterId, int targetId, java.util.function.BiConsumer<Boolean, String> done) {
        Character a = DramaKit.liveBot(starterId);
        Character b = DramaKit.liveBot(targetId);
        String no = escalateBlocked(a, b);
        if (no != null) {
            BeefKit.qaAny("RIVAL ESCALATE DROP " + starterId + " -> " + targetId + ": " + no);
            done.accept(false, no);
            return;
        }
        DramaKit.Home home = DramaKit.homeOf(a);
        boolean grind = home.training() && !b.getMap().isTown() && !b.getMap().getAllMonsters().isEmpty();
        boolean held = grind && a.getMapId() != b.getMapId(); // bringTo holds a grinding bot on the map
        if (a.getMapId() != b.getMapId() && !DramaKit.bringTo(a, b, 60, grind)) {
            if (held) {
                soloMapling.worldevents.WorldEventSteer.unhold(a.getId());
            }
            BeefKit.qaAny("RIVAL ESCALATE DROP " + a.getName() + " -> " + b.getName() + ": could not come to "
                    + b.getMapId());
            done.accept(false, "could not come to " + b.getMapId());
            return;
        }
        DramaKit.busy(a.getId(), "rival escalation");
        int map = b.getMapId();
        BotTiming.after(1_500 + ThreadLocalRandom.current().nextInt(1_500), () -> {
            if (a.getMapId() != map || b.getMapId() != map) {
                done.accept(false, "the rival left before a word was said");
                return;
            }
            String node = nemesis(a.getId(), b.getId()) ? "BeefOpenNemesis" : "BeefOpen";
            String said = DramaKit.say(a, node, Map.of("OTHER", b.getName(), "COUNT", "2"));
            String what = (grind ? "came to grind beside " : "came to smack-talk ") + b.getName() + " on " + map
                    + ": " + said;
            BeefKit.qa(map, "RIVAL ESCALATE " + a.getName() + " " + what);
            done.accept(said != null, said != null ? what : "no beef line fit");
            if (said != null) {
                BotTiming.after(1_500 + ThreadLocalRandom.current().nextInt(1_500), () -> {
                    if (b.getMapId() == map) {
                        String back = DramaKit.say(b, "BeefReply", Map.of("OTHER", a.getName()));
                        BeefKit.qa(map, "RIVAL ESCALATE BACK " + b.getName() + " -> " + a.getName() + ": " + back);
                    }
                });
            }
        });
        BotTiming.after(ESCALATE_STAY_MS, () -> {
            if (held) {
                soloMapling.worldevents.WorldEventSteer.unhold(a.getId());
            }
            DramaKit.unbusy(a.getId());
            DramaKit.settle(a, home);
        });
    }

    /** Why the escalation cannot happen now (gone, beef over, busy, a map nobody watches or a bad map), or null. */
    static String escalateBlocked(Character a, Character b) {
        if (a == null || b == null || a.getMap() == null || b.getMap() == null) {
            return "one of the two is gone";
        }
        if (!BeefKit.enabled() || !BotHelpers.isBot(a) || !BotHelpers.isBot(b) || !rivals(a.getId(), b.getId())) {
            return "no beef between two bots any more";
        }
        if (!DramaKit.freeForScene(a) || DramaKit.inScene(b.getId())) {
            return "busy";
        }
        server.maps.MapleMap m = b.getMap();
        if (m.getEventInstance() != null || m.isEventMap()
                || soloMapling.ArtificialPlayer.BotChatTuning.isBossMap(m.getId())
                || server.maps.MiniDungeonInfo.isDungeonMap(m.getId())) {
            return "the rival is on a boss, event or dungeon map";
        }
        if (!DramaKit.realPlayerOnMap(b)) {
            return "no real player can see the rival's map";
        }
        return null;
    }

    // ── call-outs ──

    /** From the drama tick: at most every {@link #STEP_MS}, one call-out roll. */
    static void maybeStep(long now) {
        if (!BeefKit.enabled() || now - lastStepAt < STEP_MS) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> {
            try {
                calloutStep(now, ThreadLocalRandom.current(), false);
            } catch (RuntimeException e) {
                log.debug("Bot call-out step failed", e);
            }
        });
    }

    /**
     * One call-out roll over a sample of online bots: a bot with its nemesis online may call it out by megaphone.
     * {@code force} (GM / QA) skips the roll and the caps, never the megaphone gate. What happened, or null.
     */
    static String calloutStep(long now, Random rng, boolean force) {
        List<BotSM> all = new ArrayList<>(CharacterStorage.getAllBots().values());
        if (all.isEmpty()) {
            return null;
        }
        Collections.shuffle(all, rng);
        List<Character[]> found = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        for (int i = 0; i < Math.min(CALLOUT_SAMPLE, all.size()); i++) {
            Character bot = all.get(i).getChr();
            if (bot == null || bot.getMap() == null) {
                continue;
            }
            for (BotBonds.View v : BotBonds.bondsOf(bot.getId())) {
                if (v.state() != AffinityState.NEMESIS) {
                    continue;
                }
                Character target = BeefKit.online(v.otherId());
                if (target != null && BotHelpers.isBot(target)) {
                    found.add(new Character[]{bot, target});
                    weights.add(BeefKit.rivalPull(bot.getId()) + Math.max(0, VillainArc.boost(bot.getId(), now)));
                }
                break;
            }
        }
        if (found.isEmpty()) {
            return null;
        }
        int k = rng.nextInt(found.size());
        Character bot = found.get(k)[0];
        Character target = found.get(k)[1];
        return callout(bot, target, calloutChance(weights.get(k)), now, rng, force);
    }

    /** The chance per step that a sampled bot with an online nemesis calls it out: 0.04 x (0.5 + 2 x Killer pull). */
    static double calloutChance(double killer) {
        return Math.min(0.5, 0.04 * (0.5 + 2 * killer));
    }

    static String callout(Character bot, Character target, double chance, long now, Random rng, boolean force) {
        if (!BotHelpers.isBot(target)) {
            return null; // a real player is called out only by BotRivalry, through the social gate
        }
        long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
        if (!force) {
            if (rng.nextDouble() >= chance || calloutsByDay.getOrDefault(day, 0) >= CALLOUTS_PER_DAY
                    || !DramaKit.free("bb:callout:" + target.getId(), now)
                    || !DramaKit.hourly("bb-callout", CALLOUTS_PER_HOUR, now)) {
                return null;
            }
            DramaKit.take("bb:callout:" + target.getId(), now, TimeUnit.DAYS.toMillis(1));
            calloutsByDay.merge(day, 1, Integer::sum);
            calloutsByDay.keySet().removeIf(d -> d < day - 1);
        }
        boolean sent = megaphone.offer(bot, "DramaBotCallout",
                Map.of("OTHER", target.getName()), "drama:botcallout:" + target.getId() + (force ? ":" + now : ""));
        if (sent) { // 1.6.0 Track D: the website's feed, bots only
            soloMapling.site.SiteFeed.drama("callout", bot.getName() + " called out " + target.getName() + " by megaphone.",
                    new int[]{bot.getId(), target.getId()}, new String[]{bot.getName(), target.getName()});
        }
        String line = "CALLOUT " + bot.getName() + " on " + target.getName() + " (nemesis): megaphone "
                + (sent ? "sent" : "queued/gated");
        BeefKit.qaAny(line);
        return line;
    }

    /** Call-outs counted today (tests, GM). */
    static int calloutsToday(long now) {
        return calloutsByDay.getOrDefault(Math.floorDiv(now, TimeUnit.DAYS.toMillis(1)), 0);
    }

    static void resetForTest(Megaphone testMegaphone) {
        lastStepAt = 0;
        calloutsByDay.clear();
        megaphone = testMegaphone != null ? testMegaphone
                : (s, n, t, k) -> WorldEventMegaphones.getInstance().offerEvent(s, n, t, k);
    }
}
