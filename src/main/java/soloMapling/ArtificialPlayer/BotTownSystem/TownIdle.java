package soloMapling.ArtificialPlayer.BotTownSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.GCMoveSystem.WatchGate;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 1.6.0 player likeness: the idle beat of a bot that stays a while in a town where a player can see it.
 *
 * <p>TEST bot watch 2026-10-26 (ItsBucc, Henesys channel 1, a player there): a training bot in town stood on one pixel
 * for 10+ minutes, not sitting, no chat. Town fidget (GCFidget: turn, duck, a small step) is only switched on when the
 * bot first settles in town (TownLoiter.settle, on entering the town phase). Anything that switched it off later left
 * the bot a statue for the rest of its stay: an AFK camp (AfkCampers.camp turns fidget off and nothing turned it back
 * on when the camp ended), a walk to the shopkeeper or the hub's vendor (those town stops skip the settle), or a chat
 * with a player when it arrived. And even a fidgeting bot never left its spot for a stay of 5 to 20 minutes.
 *
 * <p>Called once per macro tick of a bot in its town stop: on a watched map, a bot that is free (not moving, not
 * talking, not AFK, not seated, no shop open) fidgets again if its fidget was off, and every 2 to 5 minutes it either
 * strolls to another spot in town (a walk, TownLoiter.settle), emotes, or just goes on standing about. AFK campers keep
 * their stillness: that is the realistic AFK, and AfkCampers decides how long it lasts.
 *
 * <p>Cost: on a map nobody watches it is one set lookup and nothing else, so the 3,000-bot load is unchanged. On a
 * watched map, a few flag reads per tick, and the per-instance player check only when the bot is free.
 */
public final class TownIdle {

    private TownIdle() {
    }

    static final long BEAT_MIN_MS = TimeUnit.MINUTES.toMillis(2);
    static final long BEAT_MAX_MS = TimeUnit.MINUTES.toMillis(5);
    static final double STROLL_CHANCE = 0.5;
    static final double EMOTE_CHANCE = 0.3; // the rest of the beats: it just keeps standing about

    enum Act { NONE, FIDGET, STROLL, EMOTE }

    /** One bot's beat clock; a fresh one (or {@link #reset}) per town stop. */
    public static final class Beat {
        long nextAt;

        public void reset() {
            nextAt = 0L;
        }
    }

    /** What the bot does this tick. Pure: the inputs are read by {@link #tick}. */
    static Act decide(boolean watched, boolean busy, boolean fidgeting, long now, Beat beat, Random rng) {
        if (!watched || busy) {
            return Act.NONE;
        }
        if (beat.nextAt == 0L) {
            beat.nextAt = now + gap(rng); // the first beat a few minutes after it came to rest
        }
        if (!fidgeting) {
            return Act.FIDGET;
        }
        if (now < beat.nextAt) {
            return Act.NONE;
        }
        beat.nextAt = now + gap(rng);
        double r = rng.nextDouble();
        return r < STROLL_CHANCE ? Act.STROLL : r < STROLL_CHANCE + EMOTE_CHANCE ? Act.EMOTE : Act.NONE;
    }

    static long gap(Random rng) {
        return BEAT_MIN_MS + (long) (rng.nextDouble() * (BEAT_MAX_MS - BEAT_MIN_MS));
    }

    /** One macro tick of a bot standing about in a town. */
    public static void tick(Character chr, Beat beat, long now) {
        if (chr == null || beat == null || chr.getMap() == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return; // nobody on this map id on any channel: nothing extra
        }
        boolean busy = chr.getChair() > 0 || AfkCampers.isCamping(chr.getId())
                || soloMapling.ArtificialPlayer.BotConversation.isHoldingChat(chr)
                || GCMovement.isMoving(chr) || GCMovement.isTraveling(chr) || GCMovement.isFollowing(chr)
                || soloMapling.Economy.BotShops.hasShop(chr.getId());
        if (busy) {
            return;
        }
        Random rng = ThreadLocalRandom.current();
        switch (decide(WatchGate.watched(chr), false, GCMovement.isFidgeting(chr), now, beat, rng)) {
            case FIDGET -> GCMovement.setFidget(chr, true);
            case STROLL -> TownLoiter.settle(chr); // another spot in town, walked (watched), then fidget there
            case EMOTE -> SocialCommands.BotEmote(chr, 1 + rng.nextInt(7));
            case NONE -> {
            }
        }
    }
}
