package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;

/**
 * The 1.5.0 bot drama package's one door for the game code: every hook calls in here, and here it fans out to the
 * romance ({@link BotRomance}, {@link RomanceWedding}), rivalry ({@link BotRivalry}) and guild war ({@link GuildWars})
 * parts. Each hook returns at once when the package is off ({@code -Dsolomapling.drama=false}) or the character is a
 * bot, and does only memory work on the calling thread.
 */
public final class BotDrama {

    private BotDrama() {
    }

    /** A roaming bot noticed a real player near it (driver thread). True when the bot did something about it. */
    public static boolean onSight(BotSM sm, Character player) {
        if (!DramaKit.enabled() || sm == null || player == null) {
            return false;
        }
        return BotRivalry.onSight(sm, player) || BotRomance.onSight(sm, player);
    }

    /**
     * A real player's map chat line (the general chat hook, packet thread). True when it answered a scene (a bot's
     * question, the wedding vows, a demanded apology): then the line is not also routed to the bots' chat.
     */
    public static boolean onPlayerChat(Character player, String line) {
        if (!DramaKit.enabled() || player == null || line == null || BotHelpers.isBot(player)) {
            return false;
        }
        return RomanceWedding.onPlayerChat(player, line) || BotRivalry.onPlayerChat(player, line)
                || BotRomance.onPlayerChat(player, line);
    }

    /**
     * A player's line to one bot through the chat brain (map chat or whisper): a scripted answer when it is about
     * drama (asking the bot out, an apology it demanded), else null and the brain answers.
     */
    public static String onPlayerLine(Character bot, Character player, String line) {
        if (!DramaKit.enabled() || bot == null || player == null || BotHelpers.isBot(player)) {
            return null;
        }
        String revenge = BotRivalry.onPlayerLine(bot, player, line);
        return revenge != null ? revenge : BotRomance.onPlayerLine(bot, player, line);
    }

    /** A real player entered a map (packet thread): a partner may follow, a rival may follow once for revenge. */
    public static void onPlayerEnteredMap(Character player) {
        if (!DramaKit.enabled() || player == null || BotHelpers.isBot(player)) {
            return;
        }
        int from = BotRomance.movedFrom(player);
        if (from < 0) {
            return;
        }
        BotRomance.onMapChanged(player, from);
        BotRivalry.onMapChanged(player, from);
    }

    /** A real player died (stock death, packet thread): rivals remember it for their roasts. */
    public static void onPlayerDeath(Character player) {
        if (DramaKit.enabled() && player != null && !BotHelpers.isBot(player)) {
            BotRivalry.onDeath(player);
        }
    }

    /** The population director's tick. */
    public static void maybeStep(long now) {
        if (!DramaKit.enabled()) {
            return;
        }
        BotRomance.maybeStep(now);
        BotRivalry.maybeStep(now);
        GuildWars.maybeStep(now);
    }

    /** A bot joined the world (before it shows on the map). */
    public static void onBotLogin(Character bot) {
        if (DramaKit.enabled()) {
            BotRomance.onBotLogin(bot);
        }
    }

    /** What the chat prompt adds about drama with this player. */
    public static String promptLine(int botCharId, Character player) {
        if (!DramaKit.enabled()) {
            return "";
        }
        return BotRomance.promptLine(botCharId, player) + BotRivalry.promptLine(botCharId, player);
    }

    /** Whether a model reply may go to this player (romance opt-in, content rules). */
    public static boolean replyAllowed(Character bot, Character player, String reply) {
        return !DramaKit.enabled() || BotRomance.replyAllowed(bot, player, reply);
    }

    /** Whether a drama scene holds this bot (the director keeps it in the world). */
    public static boolean inScene(int botCharId) {
        return DramaKit.inScene(botCharId);
    }
}
