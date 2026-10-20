package soloMapling.ArtificialPlayer;

import client.Character;
import server.maps.MapFactory;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatStats;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapFinder;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotTypes.BuyingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.FMBot;
import soloMapling.ArtificialPlayer.BotTypes.NXMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SellingMerchantBot;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ticket 37's actions: the decision model picked what the player wants ({@link ChatTriage.Intent}); here the rules
 * decide yes or no and the server does it for real with the existing systems, and the result says what happened so
 * the model can phrase it (or a scripted line can, with no LLM slot free). The chat model never decides or does
 * anything itself.
 *
 * <p>Rules: commitments and current activity, party space, what the bot remembers about the player (a player it
 * has flagged gets a no), and personality (its social trait shifts the party roll). Never on the menu: giving
 * items or mesos - a trade request is answered with words or the bot's shop, never a trade window (ticket 38).
 */
public final class BotChatActions {

    /** What happened. */
    public enum Outcome {
        PARTY_ACCEPTED, PARTY_DECLINED, PARTY_ALREADY, PARTY_WARY,
        FOLLOW_ACCEPTED, FOLLOW_DECLINED,
        TRADE_DECLINED, TRADE_SHOP,
        WHERE_TRAIN, WHERE_TRAIN_UNKNOWN,
        NONE
    }

    /**
     * @param fact     what the bot's line must say, as an instruction for the model's prompt (null for none)
     * @param scripted the line to say when the model does not answer
     */
    public record Result(Outcome outcome, String fact, String scripted, String mustMention) {
        static final Result NONE = new Result(Outcome.NONE, null, null, null);

        Result(Outcome outcome, String fact, String scripted) {
            this(outcome, fact, scripted, null);
        }

        /**
         * Whether a model's reply fits what the game did: a yes says yes, a no does not, a training spot is named.
         * Small models sometimes ignore the instruction; a reply that does not fit is replaced by the scripted line,
         * so words and actions always match.
         */
        public boolean fits(String reply) {
            String text = " " + reply.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9' ]", " ") + " ";
            boolean yes = YES.matcher(text).find();
            boolean no = NO.matcher(text).find();
            return switch (outcome) {
                case PARTY_ACCEPTED, FOLLOW_ACCEPTED -> (yes || text.contains(" inv")) && !no;
                case PARTY_DECLINED, PARTY_WARY, FOLLOW_DECLINED, TRADE_DECLINED -> !yes || no;
                case PARTY_ALREADY -> text.contains("party") || text.contains(" pt") || no;
                case TRADE_SHOP -> mustMention != null ? text.contains(mustMention)
                        : text.contains("shop") || text.contains("store");
                case WHERE_TRAIN -> mustMention == null || text.contains(mustMention);
                case WHERE_TRAIN_UNKNOWN, NONE -> true;
            };
        }
    }

    private static final java.util.regex.Pattern YES = java.util.regex.Pattern.compile(
            " (sure|yes|yea|yeah|yep|yup|ok|okay|kk|k|lets go|omw|alright|bet|ofc|why not) ");
    private static final java.util.regex.Pattern NO = java.util.regex.Pattern.compile(
            " (no|nah|nope|cant|can't|cannot|not|busy|later|nvm|pass|sry|sorry) ");

    /** Memory notes that make a bot wary of a player: its own flag notes, or gossip that carries them. */
    static final List<String> WARY_MARKERS = List.of("tried to scam me", "tried to mess with my head", "was rude to me",
            "tried to buy or sell for real money");

    private BotChatActions() {
    }

    /** Decides and does what the player asked. Runs off the game thread; touches the party system on the way. */
    public static Result act(BotSM bot, Character player, ChatTriage.Intent intent) {
        Character chr = bot.getChr();
        if (chr == null || player == null) {
            return Result.NONE;
        }
        Result result = switch (intent) {
            case PARTY -> party(bot, chr, player, false);
            case FOLLOW, COME_WITH_ME -> party(bot, chr, player, true);
            case TRADE -> trade(bot);
            case WHERE_TRAIN -> whereTrain(bot, chr);
            case NONE -> Result.NONE;
        };
        if (result.outcome() != Outcome.NONE) {
            ChatStats.action(intent.name().toLowerCase(Locale.ROOT) + ":" + result.outcome().name().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** Whether this bot holds a note that makes it wary of this player. */
    static boolean wary(int botSlot, int playerId) {
        try {
            for (BotMemory.Note note : BotMemory.about(botSlot, playerId)) {
                String text = note.text().toLowerCase(Locale.ROOT);
                for (String marker : WARY_MARKERS) {
                    if (text.contains(marker)) {
                        return true;
                    }
                }
            }
        } catch (RuntimeException e) {
            return false; // memory unreadable: no grudge
        }
        return false;
    }

    // ── party, follow, come along ──

    private static Result party(BotSM bot, Character chr, Character player, boolean follow) {
        boolean social = bot instanceof SocialBot;
        // Following a player is only built as "join their party and become a follower" (SocialBot -> FollowerBot);
        // there is no separate companion follow yet (ticket 14), so other bots decline a follow.
        if (follow && !social) {
            return declineFollow();
        }
        if (!social && !(bot instanceof TrainingBot)) {
            // Merchants, hosts and wanderers do not answer party invites at all: saying yes would be a lie.
            return follow ? declineFollow() : new Result(Outcome.PARTY_DECLINED,
                    "Say no to the party: you are busy with what you are doing.",
                    ScriptedReplies.pick(ScriptedReplies.PARTY_NO));
        }
        if (chr.getParty() != null) {
            boolean together = player.getParty() != null && player.getParty().getId() == chr.getParty().getId();
            return new Result(Outcome.PARTY_ALREADY, together
                    ? "Tell them you are already in their party."
                    : "Say no: you are already in another party.", ScriptedReplies.pick(ScriptedReplies.PARTY_ALREADY));
        }
        if (bot.getState() == BotSM.BotState.TRADING) {
            return busy(follow);
        }
        int slot = chr.getId() - BotStore.BOT_ID_BASE;
        if (wary(slot, player.getId())) {
            return new Result(Outcome.PARTY_WARY, "Say no to the party, a bit coldly: you do not trust them after how they acted before.",
                    ScriptedReplies.pick(ScriptedReplies.PARTY_NO));
        }
        double base = social ? BotRecruitManager.SOCIAL_ACCEPT_CHANCE : BotRecruitManager.TRAINING_ACCEPT_CHANCE;
        BotRoster.Member member = BotRoster.member(slot);
        BotPersonality.Traits traits = member == null ? null : member.traits();
        double chance = traits == null ? base : Math.min(0.95, base * (0.75 + traits.social() + traits.chattiness() * 0.3));
        BotRecruitManager.RecruitAnswer answer = BotRecruitManager.rollPartyAsk(chr, player, chance, social);
        if (answer != BotRecruitManager.RecruitAnswer.ACCEPTED) {
            return busy(follow);
        }
        // The invite window is armed: this player's party invite will be accepted by the bot's own tick, and a
        // social bot then follows its leader as a FollowerBot.
        return follow
                ? new Result(Outcome.FOLLOW_ACCEPTED, "Say yes, you will come along: tell them to send you a party "
                + "invite first and you will follow them.", ScriptedReplies.pick(ScriptedReplies.FOLLOW_YES))
                : new Result(Outcome.PARTY_ACCEPTED, "Say yes to the party and tell them to send you the party "
                + "invite.", ScriptedReplies.pick(ScriptedReplies.PARTY_YES));
    }

    private static Result busy(boolean follow) {
        return follow ? declineFollow() : new Result(Outcome.PARTY_DECLINED,
                "Say no to the party for now (busy, or just not feeling it).",
                ScriptedReplies.pick(ScriptedReplies.PARTY_NO));
    }

    private static Result declineFollow() {
        return new Result(Outcome.FOLLOW_DECLINED, "Say no to going with them: you are staying where you are.",
                ScriptedReplies.pick(ScriptedReplies.FOLLOW_NO));
    }

    // ── trade ──

    private static Result trade(BotSM bot) {
        MerchantStock.Stock stock = MerchantStock.of(bot);
        if (stock != null) {
            // Ticket 26: a merchant names what it has; the player trades it to buy (no window opens from chat).
            return new Result(Outcome.TRADE_SHOP, stock.answer(), stock.scripted(java.util.concurrent.ThreadLocalRandom.current()),
                    stock.keyWord());
        }
        if (bot instanceof SellingMerchantBot || bot instanceof NXMerchantBot || bot instanceof BuyingMerchantBot
                || bot instanceof FMBot) {
            return new Result(Outcome.TRADE_SHOP, "Say you do not trade in person: everything you sell or buy is in "
                    + "your shop at its posted price.", ScriptedReplies.pick(ScriptedReplies.TRADE_SHOP));
        }
        return new Result(Outcome.TRADE_DECLINED, "Say no to trading: you are not selling or giving anything right "
                + "now.", ScriptedReplies.pick(ScriptedReplies.TRADE_NO));
    }

    // ── where do you train ──

    private static Result whereTrain(BotSM bot, Character chr) {
        String map = trainingMapName(bot, chr);
        if (map == null) {
            return new Result(Outcome.WHERE_TRAIN_UNKNOWN, "Say you do not really have a training spot right now.",
                    ScriptedReplies.pick(ScriptedReplies.TRAIN_UNKNOWN));
        }
        return new Result(Outcome.WHERE_TRAIN, "Tell them you train at " + map + ".",
                String.format(ScriptedReplies.pick(ScriptedReplies.TRAIN_AT), map.toLowerCase(Locale.ROOT)),
                keyWord(map));
    }

    /** The bot's real training map: where a grinder trains, the field it stands on, or a fitting map for its level. */
    static String trainingMapName(BotSM bot, Character chr) {
        try {
            BotRoster.Activity doing = bot.activitySnapshot();
            int train = doing == null ? -1 : doing.intState(BotRoster.TRAIN_STATE, -1);
            if (train < 0 && MapMobIndex.level(chr.getMapId()) >= 0) {
                train = chr.getMapId();
            }
            if (train < 0) {
                int level = chr.getLevel();
                List<TrainingMap> maps = TrainingMapFinder.findTrainingMaps(chr.getMapId(), level, Math.max(1, level - 10),
                        level + 5, 6, Set.of());
                train = maps.isEmpty() ? -1 : maps.get(0).mapId();
            }
            return train < 0 ? null : mapName(train);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The word of a map name a reply must contain to count as naming it ("ant" for "Ant Tunnel I"). */
    static String keyWord(String map) {
        for (String word : map.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", " ").split("\\s+")) {
            if (word.length() >= 3 && !Set.of("the", "and", "field", "map", "road", "way", "path").contains(word)) {
                return word;
            }
        }
        return null;
    }

    private static String mapName(int mapId) {
        String place = MapFactory.loadPlaceName(mapId);
        String street = MapFactory.loadStreetName(mapId);
        String name = place == null || place.isBlank() ? street : place;
        return name == null || name.isBlank() ? null : name;
    }

    // ── cons ──

    /** The memory note a flag leaves, from the bot's point of view. */
    static String flagNote(ChatTriage.Con con) {
        return switch (con) {
            case SCAM -> "tried to scam me (begged for mesos or items)";
            case MANIPULATION -> "tried to mess with my head (said weird stuff about bots or instructions)";
            case HARASSMENT -> "was rude to me";
            case RWT -> "tried to buy or sell for real money";
            case NORMAL -> null;
        };
    }
}
