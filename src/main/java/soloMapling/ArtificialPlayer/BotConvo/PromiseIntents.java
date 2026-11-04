package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotConvo.TalkIntent.Ask;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActKind;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntent;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialIntents;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialSim;

import java.util.concurrent.TimeUnit;

/**
 * Ticket 181 stage 5: where an agreement becomes an intention. The two places a bot's yes already turns into an action
 * - a bot-to-bot yes in {@link BotTalk} ({@link TalkPromises#promised}) and a player's request the gates said yes to
 * ({@link ConvoActions}) - also record a {@link SocialIntent}, so the promise to meet, help, come along or party outlives the
 * lines and the immediate act and is checked at its deadline. Nothing is removed: the act that ran before still runs.
 *
 * <p>Only these server paths call this, after their own validation. A model's line, a {@code [do:x]} tag that
 * {@link ConvoGates} refused, and a {@link SayDo} claim reach nothing here. A real player is only ever the target of an
 * intent when {@code SocialGate} and the opt-outs (checked in {@link SocialIntents#create}) allow it.
 *
 * <p>A bot-to-bot yes is already judged by {@link TalkPromises} at its own deadline; its intent falls due a grace after
 * that deadline ({@link #GRACE_MS}), so a promise the talk's act kept is ended as kept by that verdict
 * ({@link #onBacked}) and only one that was not kept gets the intent's second try and, failing that, its consequences.
 */
final class PromiseIntents {

    /** After {@link TalkPromises#windowMs}: the intent of a bot-bot yes falls due this much later. */
    static final long GRACE_MS = TimeUnit.MINUTES.toMillis(2);
    /** A player's "come" or "help" that sends the bot to another map: when it should have arrived and turned up. */
    static final long COME_DELAY_MS = TimeUnit.MINUTES.toMillis(4);
    static final long HELP_DELAY_MS = TimeUnit.MINUTES.toMillis(5);

    private PromiseIntents() {
    }

    /** What a bot-to-bot ask means as an act of the one who said yes; null for the asks that promise nothing later. */
    static SocialActKind kindOf(Ask ask) {
        if (ask == null) {
            return null;
        }
        return switch (ask) {
            case PARTY -> SocialActKind.INVITE;
            case HELP -> SocialActKind.OFFER_HELP;
            case COME_ALONG -> SocialActKind.SPEND_TIME;
            default -> null; // a deal or a story is done in the talk or not at all
        };
    }

    /** {@code bot} said yes to {@code asker}'s {@code ask} in a bot talk: it has promised. Never throws. */
    static void onYes(Ask ask, Character bot, Character asker, long now) {
        SocialActKind kind = kindOf(ask);
        if (kind == null || bot == null || asker == null || !SocialIntents.enabled()) {
            return;
        }
        try {
            SocialIntents.promise(bot.getId(), asker.getId(), kind, TalkPromises.windowMs(ask) + GRACE_MS, bot.getMapId(),
                    "talk:" + ask.name(), now);
        } catch (RuntimeException | LinkageError e) {
            // a promise that cannot be recorded changes nothing else
        }
    }

    /** The talk's own verdict said the yes was carried out: the intent is kept, now. */
    static void onBacked(Ask ask, int botId, int askerId, long now) {
        SocialActKind kind = kindOf(ask);
        if (kind == null || !SocialIntents.enabled()) {
            return;
        }
        try {
            SocialIntents.fulfil(botId, askerId, kind, now);
        } catch (RuntimeException | LinkageError e) {
            // nothing else depends on it
        }
    }

    /**
     * A player's request ({@code come}, {@code help}) passed the gates and the bot is on its way to the player's map:
     * the bot has promised to be there. Not for a bot already on the player's map (nothing to promise) or any other
     * action. The player is the intent's target only if {@code SocialGate} and the opt-outs allow. Returns the intent or null.
     */
    static SocialIntent onPlayerYes(ConvoAction action, Character bot, Character player, boolean alreadyThere) {
        if (bot == null || player == null || alreadyThere || !SocialIntents.enabled()) {
            return null;
        }
        SocialActKind kind;
        long delay;
        switch (action) {
            case COME -> {
                kind = SocialActKind.SPEND_TIME;
                delay = COME_DELAY_MS;
            }
            case HELP -> {
                kind = SocialActKind.OFFER_HELP;
                delay = HELP_DELAY_MS;
            }
            default -> {
                return null;
            }
        }
        try {
            return SocialIntents.promise(bot.getId(), player.getId(), kind, delay, player.getMapId(),
                    "chat:" + action.name(), SocialSim.now()).intent();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }
}
