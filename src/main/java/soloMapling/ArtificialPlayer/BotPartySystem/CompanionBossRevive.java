package soloMapling.ArtificialPlayer.BotPartySystem;

/*
 * Ported from Incluwin/BotStory src/main/java/server/living/roster/CompanionFieldBossRest.java@81a3b1df, changed: only
 * its rest rule was taken (a companion that died beside a boss stays off follow until its HP and MP are back to
 * REST_PERCENT of their maximum, or REST_MAX_MS has passed); the sit-out rule and its lines were not ported (the owner
 * declined it), the runtime lookups became ours ({@link ExpeditionHold}/{@link soloMapling.mechanics.MechanicsHold} for
 * "in a boss run", {@code getOwnedAltOwner}/{@code FollowerBot.leaderId} for the owner, a {@code -D} property instead of
 * LivingWorldConfig), and the rest clock starts at the revive instead of at the death, because the wait for the revive
 * is a rule of its own here.
 */

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.server.SoloMaplingUtilities;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.7 alt-revive-respawn: what a companion does when it dies in a boss run, for owned alts and (behind
 * {@link PartyCompanionPolicy.Feature#DEATH_RULES}) for party regulars. Outside a run nothing here answers: the caller
 * keeps its own path (an alt goes to town and home, as before).
 *
 * <p>Inside a run the companion stays where it fell, registered in its event, and gets itself up after a wait that
 * doubles with each death in the same run (10 s, 20 s, 40 s, 80 s, then 120 s), or at once if its owner pays
 * ({@code @botme revive <name>}: 10,000 x level / 10 mesos, doubling per paid revive in the run). The revive is the
 * one a Resurrection hands a player (full HP, MP as it was). After it, the companion rests: no follow and no fight
 * until HP and MP are back to {@code slums.companion.rest.percent} of their maximum, or
 * {@code slums.companion.rest.maxMs} has passed (the rule ported from BotStory, see the file header).
 *
 * <p>The counters belong to one run and are dropped when it ends ({@link #runEnded}, called from
 * {@code ExpeditionHold.release} and {@code MechanicsHold.release}); a run the companion left without a release is
 * noticed by the event instance changing. Memory only, any thread.
 */
public final class CompanionBossRevive {

    private CompanionBossRevive() {
    }

    private static final Logger log = LoggerFactory.getLogger(CompanionBossRevive.class);

    /** The wait after the first death in a run; each later death in the same run doubles it. */
    static final String WAIT_BASE_KEY = "slums.companion.revive.waitMs";
    static final long WAIT_BASE_DEFAULT = 10_000L;
    /** The longest wait, however many deaths the run has taken. */
    static final String WAIT_MAX_KEY = "slums.companion.revive.maxWaitMs";
    static final long WAIT_MAX_DEFAULT = 120_000L;
    /** The price of an instant revive: this much per level (10,000 x level / 10 = 1,000 x level). */
    static final String PRICE_KEY = "slums.companion.revive.pricePerLevel";
    static final long PRICE_DEFAULT = 1_000L;
    /** The ported rest rule: back on follow once HP and MP are this percent of their maximum ... */
    static final String REST_PERCENT_KEY = "slums.companion.rest.percent";
    static final int REST_PERCENT_DEFAULT = 100;
    /** ... or at the latest this long after the revive. */
    static final String REST_MAX_MS_KEY = "slums.companion.rest.maxMs";
    static final long REST_MAX_MS_DEFAULT = 120_000L;

    /** One companion in one boss run: how many deaths and paid revives, and when the next auto-revive falls due. */
    private static final class Run {
        /** The event instance the run is; a different one is a different run. */
        private final Object token;
        private int deaths;
        private int paid;
        /** 0 = not waiting; else the moment the automatic revive falls due. */
        private long reviveAtMs;

        private Run(Object token) {
            this.token = token;
        }
    }

    /** Companion id -> its state in the run it is in. */
    private static final Map<Integer, Run> runs = new ConcurrentHashMap<>();
    /** Companion id -> when it was revived and started resting (BotStory's diedAtMs, moved to the revive). */
    private static final Map<Integer, Long> restingSince = new ConcurrentHashMap<>();

    /**
     * One tick for a companion that is dead or newly revived. True when it answered the tick (the caller leaves the
     * companion alone): dead in a run it waits, then gets up; just revived it rests. False when the death rules do not
     * apply (the switch is off for this companion, or it died outside a run), and the caller's own path runs.
     */
    public static boolean gate(Character companion, long now) {
        if (companion == null || !PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.DEATH_RULES, companion)) {
            return false;
        }
        if (BotDeaths.isDead(companion.getId())) {
            return hold(companion, now);
        }
        return resting(companion, now);
    }

    /** True while this companion is dead inside a boss run and waiting for its revive. */
    public static boolean waiting(Character companion) {
        if (companion == null) {
            return false;
        }
        Run run = runs.get(companion.getId());
        return run != null && run.reviveAtMs != 0;
    }

    /**
     * The companion is dead: in a boss run it is registered (once), told to its owner, and revived when the wait is
     * up; returns true either way. Not in a run: false, and any state from a finished run is dropped.
     */
    private static boolean hold(Character companion, long now) {
        if (!inRun(companion)) {
            runs.remove(companion.getId()); // the run ended (or the owner left it): the old path takes over
            return false;
        }
        int id = companion.getId();
        Object token = companion.getEventInstance();
        Run run = runs.get(id);
        if (run == null || run.token != token) {
            run = new Run(token); // a run nothing released: the event it is in now is the run it is in now
            runs.put(id, run);
        }
        if (run.reviveAtMs == 0) {
            run.deaths++;
            run.reviveAtMs = now + waitMs(run.deaths);
            Character owner = leaderOf(companion);
            if (owner != null) {
                owner.yellowMessage(companion.getName() + " is down, back in " + waitMs(run.deaths) / 1_000 + "s (or @botme"
                        + " revive " + companion.getName() + " for " + money(price(companion, run)) + " mesos)");
            }
            log.info("Companion down in a boss run: {} death {} of the run, revive in {} ms, instant revive {} mesos",
                    companion.getName(), run.deaths, waitMs(run.deaths), price(companion, run));
        }
        if (now < run.reviveAtMs) {
            return true;
        }
        run.reviveAtMs = 0;
        Character owner = leaderOf(companion);
        String line = revive(companion, owner, now, 0);
        if (owner != null) {
            owner.yellowMessage(line);
        }
        return true;
    }

    /**
     * The owner buys a revive with {@code @botme revive <name>}. Returns what to tell the owner: the price line when it
     * was bought (the companion is up), the refusal when it could not be (nothing charged, nothing revived).
     */
    public static Outcome reviveNow(Character payer, Character companion, long now) {
        if (payer == null || companion == null) {
            return new Outcome(false, null);
        }
        // the run entry is the authority: it only exists while the companion is down inside a run (runEnded clears it)
        if (!PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.DEATH_RULES, companion)
                || !waiting(companion) || !BotDeaths.isDead(companion.getId())) {
            return new Outcome(false, companion.getName() + " is not down in a fight right now.");
        }
        Run run = runs.get(companion.getId());
        long price = price(companion, run);
        if (payer.getMeso() < price) {
            return new Outcome(false, companion.getName() + " is still down: reviving it now costs " + money(price)
                    + " mesos and you have " + money(payer.getMeso()) + ".");
        }
        payer.gainMeso(-(int) Math.min(Integer.MAX_VALUE, price), true, false, true); // the normal meso-change call
        run.paid++;
        run.reviveAtMs = 0; // paid for: the automatic revive it was waiting for is off the clock
        log.info("Instant revive paid: {} paid {} mesos for {} in a boss run (paid revive {} of the run)",
                payer.getName(), price, companion.getName(), run.paid);
        return new Outcome(true, revive(companion, leaderOf(companion), now, price));
    }

    /** What an instant revive did: whether the companion is up, and the line for the owner. */
    public record Outcome(boolean revived, String told) {
    }

    /**
     * Up again, as a Resurrection gets a player up (full HP, MP as it was, the standing stance broadcast), beside its
     * owner when the owner is on another map of the same instance. It then starts to rest, and the one line is said.
     * Returns that line.
     */
    private static String revive(Character companion, Character owner, long now, long paid) {
        if (owner != null && owner.getMap() != null && companion.getMap() != owner.getMap()) {
            companion.changeMap(owner.getMap(), owner.getPosition()); // the same instance, beside its owner
        }
        companion.applyHpMpChange(0, companion.getCurrentMaxHp(), 0);
        companion.broadcastStance(companion.isFacingLeft() ? 5 : 4);
        BotDeaths.revived(companion);
        restingSince.put(companion.getId(), now);
        log.info("Companion revived in a boss run: {} hp {} mp {} paid {} mesos", companion.getName(),
                companion.getHp(), companion.getMp(), paid);
        return companion.getName() + " is back up" + (paid > 0 ? " - " + money(paid) + " mesos." : "");
    }

    /**
     * True while a companion that was revived in a boss run must not follow or fight: until HP and MP are back to
     * {@code slums.companion.rest.percent} of their maximum, or {@code slums.companion.rest.maxMs} has passed since the
     * revive. The ported rule; the switch is read here so a regular's default costs one property read.
     */
    public static boolean resting(Character companion, long now) {
        if (companion == null) {
            return false;
        }
        int id = companion.getId();
        Long since = restingSince.get(id);
        if (since == null) {
            return false;
        }
        if (!PartyCompanionPolicy.on(PartyCompanionPolicy.Feature.DEATH_RULES, companion)) {
            restingSince.remove(id); // the switch went off: no rest to honour
            return false;
        }
        if (now - since >= restMaxMs()) {
            restingSince.remove(id, since);
            return false;
        }
        if (!companion.isAlive()) {
            return true; // it died again inside the rest: nothing is recovered yet
        }
        int percent = restPercent();
        boolean recovered = (long) companion.getHp() * 100 >= (long) companion.getCurrentMaxHp() * percent
                && (long) companion.getMp() * 100 >= (long) companion.getCurrentMaxMp() * percent;
        if (recovered) {
            restingSince.remove(id, since);
        }
        return !recovered;
    }

    /** The run this companion was in is over: its death count, its paid revives and its rest go with it. */
    /** Test seam: the companion is down and waiting, as the gate leaves it after the first tick inside a run. */
    static void downForTest(Character companion, long reviveAtMs) {
        Run run = new Run(companion.getEventInstance());
        run.deaths = 1;
        run.reviveAtMs = reviveAtMs;
        runs.put(companion.getId(), run);
    }

    public static void runEnded(int companionId) {
        runs.remove(companionId);
        restingSince.remove(companionId);
    }

    /** Test seam: forget everything about this companion (no run, no rest). */
    static void forget(int companionId) {
        runEnded(companionId);
    }

    /** The wait after the {@code deaths}-th death of a run: the base, doubled each death, capped. */
    public static long waitMs(int deaths) {
        long base = number(WAIT_BASE_KEY, WAIT_BASE_DEFAULT);
        long max = number(WAIT_MAX_KEY, WAIT_MAX_DEFAULT);
        int doubling = Math.max(0, Math.min(20, deaths - 1));
        return Math.min(max, base << doubling);
    }

    /** The price of the next instant revive in this run: the per-level price, doubled per revive already paid. */
    private static long price(Character companion, Run run) {
        int level = Math.max(0, companion.getLevel());
        int doubling = Math.max(0, Math.min(20, run.paid));
        return (number(PRICE_KEY, PRICE_DEFAULT) * level) << doubling;
    }

    /** The price of the next instant revive for this companion, or 0 when it is not waiting in a run. */
    public static long priceOf(Character companion) {
        Run run = companion == null ? null : runs.get(companion.getId());
        return run == null || run.reviveAtMs == 0 ? 0 : price(companion, run);
    }

    /**
     * A boss run as this layer means it: the companion is inside the instance of a boss expedition it registered for,
     * or a bots-play-the-mechanics run is driving it. A map with a boss on it is not a run (the run, not the map, is
     * what decides whether the companion may take its time getting up).
     */
    static boolean inRun(Character companion) {
        return ExpeditionHold.inRun(companion) || soloMapling.mechanics.MechanicsHold.committed(companion);
    }

    /** Its owner: the player who owns the alt, or the leader a regular follows; null when neither is online. */
    static Character leaderOf(Character companion) {
        if (companion == null) {
            return null;
        }
        if (companion.isOwnedAlt()) {
            return companion.getOwnedAltOwner();
        }
        BotSM sm = CharacterStorage.getBotById(companion.getId());
        if (!(sm instanceof FollowerBot follower) || follower.leaderId() <= 0) {
            return null;
        }
        try {
            var world = SoloMaplingUtilities.world();
            return world == null ? null : world.getPlayerStorage().getCharacterById(follower.leaderId());
        } catch (RuntimeException noWorld) {
            return null; // a bare test spawn has no world, and a runner without one has nothing to tell
        }
    }

    static int restPercent() {
        return (int) Math.max(0, Math.min(100, number(REST_PERCENT_KEY, REST_PERCENT_DEFAULT)));
    }

    static long restMaxMs() {
        return Math.max(0, number(REST_MAX_MS_KEY, REST_MAX_MS_DEFAULT));
    }

    private static long number(String key, long dflt) {
        try {
            String v = System.getProperty(key);
            return v == null ? dflt : Long.parseLong(v.trim());
        } catch (RuntimeException | LinkageError e) {
            return dflt;
        }
    }

    private static String money(long amount) {
        return String.format("%,d", amount);
    }
}
