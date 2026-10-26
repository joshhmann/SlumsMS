package soloMapling.ArtificialPlayer.GCMoveSystem;

import client.Character;
import server.TimerManager;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf;
import soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers;
import soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace;
import soloMapling.server.BotShutdown;

import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * 1.6.0 attention hops (owner, FM on TEST): "if the jump is intended to catch attention I'd say 3-4 jumps right after
 * a message is sent". A bot that posts a public map-chat line while standing idle in a town or the Free Market hops
 * 3-4 times in place right after it, while the chat bubble is still up, the way a player mashes the jump key to get
 * noticed. Before this the hops were the idle fidget's lone random jumps, with no link to chat.
 *
 * <p>Hooked from {@code SocialCommands.fullChat}, which every public bot line goes through (FM shouts, LFG, idle
 * chatter, thread and bank lines). Not every line: about two in three, more for Killer/Socializer-leaning bots,
 * fewer for Achievers; never twice within {@link #COOLDOWN_MS} for one bot; only on maps a real player can see.
 * Never while the bot is in a conversation with a player, walking, airborne, on a chair, AFK camping, trading, in a
 * shop or mini game. One timer task in flight per burst (each hop schedules the next), no thread per hop.
 */
public final class AttentionHops {
    private AttentionHops() {
    }

    static final long COOLDOWN_MS = 20_000;
    static final int FIRST_MIN_MS = 60;
    static final int FIRST_MAX_MS = 300;
    static final int GAP_MIN_MS = 350;
    static final int GAP_MAX_MS = 600;
    /** A hop due while the bot is still in the air waits for the landing in steps of this, like a held key. */
    static final int LANDING_RETRY_MS = 40;
    static final int LANDING_RETRIES = 12;

    /** Delayed work: the server's TimerManager; tests swap in a manual queue. */
    interface Scheduler {
        void schedule(Runnable task, long delayMs);
    }

    static volatile Scheduler scheduler = (task, delayMs) -> TimerManager.getInstance().schedule(task, delayMs);
    static volatile IntPredicate observed = GCMovement::isMapObserved;
    static volatile Predicate<Character> hopper = GCMovement::tryJumpInPlace;
    static volatile Predicate<Character> airborne = GCMovement::isAirborne;
    static volatile ToDoubleFunction<Character> chance = AttentionHops::chanceOf;
    /** Walking, airborne, climbing, following or travelling (GCMovement's own idea of "busy"). */
    static volatile Predicate<Character> walking = AttentionHops::walkingNow;

    private static final Map<Integer, Long> LAST_BURST_AT = new ConcurrentHashMap<>();

    /**
     * The bot just said a line in public map chat. Maybe starts a burst; cheap and never throws (the chat path
     * must not break over a hop).
     */
    public static void onPublicLine(Character bot) {
        try {
            maybeBurst(bot, System.currentTimeMillis(), ThreadLocalRandom.current());
        } catch (RuntimeException ignored) {
            // a hop is decoration; the line itself already went out
        }
    }

    /** True when a burst was scheduled. */
    static boolean maybeBurst(Character bot, long now, Random rng) {
        if (bot == null || !BotHelpers.isArtificial(bot.getId()) || BotShutdown.isQuiesced()) {
            return false;
        }
        if (!eligible(bot)) {
            return false;
        }
        Long last = LAST_BURST_AT.get(bot.getId());
        if (last != null && now - last < COOLDOWN_MS) {
            return false;
        }
        if (rng.nextDouble() >= chance.applyAsDouble(bot)) {
            return false;
        }
        LAST_BURST_AT.put(bot.getId(), now);
        int hops = 3 + rng.nextInt(2);
        MapleMap map = bot.getMap();
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.BOT_CHAT)) { // a !qa chat run shows the bursts too
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.BOT_CHAT, map.getId(),
                    bot.getName() + " hops x" + hops + " after its line");
        }
        scheduler.schedule(new Burst(bot, map, hops, rng.nextLong()), rng.nextInt(FIRST_MIN_MS, FIRST_MAX_MS + 1));
        return true;
    }

    /** Idle in a town or the FM, on a map a real player can see, and doing nothing else. */
    static boolean eligible(Character bot) {
        MapleMap map = bot.getMap();
        if (map == null || !PersonalSpace.appliesOn(map) || !observed.test(map.getId())) {
            return false;
        }
        return standingIdle(bot);
    }

    /** The bot's own state, rechecked before every hop: anything that started since the line ends the burst. */
    static boolean standingIdle(Character bot) {
        if (BotConversation.isHoldingChat(bot.getId())) {
            return false; // a reply in a chat with a player, or standing with one
        }
        if (walking.test(bot)) {
            return false;
        }
        if (bot.getChair() > 0 || AfkCampers.isCamping(bot.getId())) {
            return false;
        }
        return bot.getTrade() == null && bot.getShop() == null && bot.getPlayerShop() == null
                && bot.getHiredMerchant() == null && bot.getMiniGame() == null;
    }

    private static boolean walkingNow(Character bot) {
        return GCMovement.isMoving(bot) || GCMovement.isTraveling(bot) || GCMovement.isFollowing(bot);
    }

    /**
     * About two in three lines: Socializers and Killers (show-offs, hecklers) more, Achievers (head down, grinding)
     * less. 0.40 .. 0.85.
     */
    static double chanceOf(Character bot) {
        SocialSelf self = SocialSelf.of(bot.getId());
        double loud = self.socialPull() + self.rivalPull();
        double achiever = self.social().share(SocialProfile.Type.ACHIEVER);
        return chanceFor(loud, achiever);
    }

    static double chanceFor(double socialPlusRival, double achieverShare) {
        double p = 0.62 + 0.30 * (socialPlusRival - 0.5) - 0.25 * (achieverShare - 0.25);
        return Math.max(0.40, Math.min(0.85, p));
    }

    /** The gap before hop {@code n} (n >= 1): mashing, never metronomic. */
    static int nextGap(Random rng) {
        return rng.nextInt(GAP_MIN_MS, GAP_MAX_MS + 1);
    }

    private static final class Burst implements Runnable {
        final Character bot;
        final MapleMap map;
        final Random rng;
        int left;
        int retries;

        Burst(Character bot, MapleMap map, int hops, long seed) {
            this.bot = bot;
            this.map = map;
            this.left = hops;
            this.rng = new Random(seed);
        }

        @Override
        public void run() {
            try {
                step();
            } catch (RuntimeException ignored) {
                // the burst just ends
            }
        }

        private void step() {
            if (left <= 0 || BotShutdown.isQuiesced() || bot.getMap() != map || !observed.test(map.getId())) {
                return;
            }
            if (airborne.test(bot)) {
                // still coming down from the last hop (a hop is ~0.55 s in the air): jump again right on landing,
                // like a held key. Checked before standingIdle, whose "moving" counts the air time.
                if (retries++ < LANDING_RETRIES) {
                    scheduler.schedule(this, LANDING_RETRY_MS);
                }
                return;
            }
            retries = 0;
            if (!standingIdle(bot)) {
                return;
            }
            if (!hopper.test(bot)) {
                return; // on a rope or not driven: no burst
            }
            left--;
            if (left > 0) {
                scheduler.schedule(this, nextGap(rng));
            }
        }
    }

    // ── test seams ────────────────────────────────────────────────────────────────────────────────────────────

    static void resetForTest() {
        LAST_BURST_AT.clear();
        scheduler = (task, delayMs) -> TimerManager.getInstance().schedule(task, delayMs);
        observed = GCMovement::isMapObserved;
        hopper = GCMovement::tryJumpInPlace;
        airborne = GCMovement::isAirborne;
        chance = AttentionHops::chanceOf;
        walking = AttentionHops::walkingNow;
    }
}
