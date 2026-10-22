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
 * items or mesos. A trade request is answered with the bot's shop (merchants) or, since 1.5.0 rc3, a price for
 * something it carries; the window opens only when the player invites the bot ({@code BotDirectTrade}).
 */
public final class BotChatActions {

    /** What happened. */
    public enum Outcome {
        PARTY_ACCEPTED, PARTY_DECLINED, PARTY_ALREADY, PARTY_WARY,
        FOLLOW_ACCEPTED, FOLLOW_DECLINED,
        TRADE_DECLINED, TRADE_SHOP,
        /** 1.5.0 rc3: a bot that trades in person named a price or asked to see the item (direct trades). */
        TRADE_OFFER,
        WHERE_TRAIN, WHERE_TRAIN_UNKNOWN,
        WHERE_AT,
        /** Ticket 59: the conversation layer's actions (BotConvo.ConvoActions): yes = done, no = refused. */
        COME_YES, COME_NO, HELP_YES, HELP_NO, GUIDE_YES, GUIDE_NO, BUFF_YES, BUFF_NO, BUDDY_YES, BUDDY_NO,
        /** Words that move feelings: a grudge eased, an apology taken, game-only trash talk back, a flirt back. */
        MAKEUP_YES, MOOD,
        /** A mood the rules did not allow (no flirting without romance on, no insult without a grudge): stay plain. */
        MOOD_NO,
        NONE
    }

    /**
     * @param fact     what the bot's line must say, as an instruction for the model's prompt (null for none)
     * @param scripted the line to say when the model does not answer
     */
    public record Result(Outcome outcome, String fact, String scripted, String mustMention) {
        public static final Result NONE = new Result(Outcome.NONE, null, null, null);

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
                case COME_YES, HELP_YES, GUIDE_YES, BUFF_YES, BUDDY_YES -> !no || yes;
                case PARTY_DECLINED, PARTY_WARY, FOLLOW_DECLINED, TRADE_DECLINED, COME_NO, HELP_NO, GUIDE_NO, BUFF_NO,
                     BUDDY_NO -> !yes || no;
                case MAKEUP_YES, MOOD -> true;
                // a refused mood: no flirt and no trash talk in the words either
                case MOOD_NO -> !soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard.flirty(reply)
                        && soloMapling.ArtificialPlayer.BotAffinitySystem.ChatTone.rule(reply, null).orElse(null)
                        != soloMapling.ArtificialPlayer.BotAffinitySystem.ChatTone.Tone.RUDE;
                case PARTY_ALREADY -> text.contains("party") || text.contains(" pt") || no;
                case TRADE_SHOP -> mustMention != null ? text.contains(mustMention)
                        : text.contains("shop") || text.contains("store");
                // the price the code decided must be in the line, written the same way ("1.2m")
                case TRADE_OFFER -> mustMention == null
                        ? soloMapling.Economy.HaggleBrain.price(reply) < 0
                        : reply.toLowerCase(Locale.ROOT).contains(mustMention.toLowerCase(Locale.ROOT));
                case WHERE_TRAIN, WHERE_AT -> mustMention == null || text.contains(mustMention);
                case WHERE_TRAIN_UNKNOWN, NONE -> true;
            };
        }
    }

    private static final java.util.regex.Pattern YES = java.util.regex.Pattern.compile(
            " (sure|yes|yea|yeah|yep|yup|ok|okay|kk|k|lets go|omw|otw|coming|alright|bet|ofc|why not|on my way) ");
    private static final java.util.regex.Pattern NO = java.util.regex.Pattern.compile(
            " (no|nah|nope|cant|can't|cannot|not|busy|later|nvm|pass|sry|sorry) ");

    /** Memory notes that make a bot wary of a player: its own flag notes, or gossip that carries them. */
    static final List<String> WARY_MARKERS = List.of("tried to scam me", "tried to mess with my head", "was rude to me",
            "tried to buy or sell for real money");

    private BotChatActions() {
    }

    /** Decides and does what the player asked. Runs off the game thread; touches the party system on the way. */
    public static Result act(BotSM bot, Character player, ChatTriage.Intent intent) {
        return act(bot, player, intent, null);
    }

    /** As above, with the player's line (1.5.0 rc3: a bot that trades in person reads what is asked for). */
    public static Result act(BotSM bot, Character player, ChatTriage.Intent intent, String line) {
        Character chr = bot.getChr();
        if (chr == null || player == null) {
            return Result.NONE;
        }
        Result result = switch (intent) {
            case PARTY -> party(bot, chr, player, false);
            case FOLLOW, COME_WITH_ME -> party(bot, chr, player, true);
            case TRADE -> trade(bot, player, line);
            case WHERE_TRAIN -> whereTrain(bot, chr);
            case WHERE_AT -> whereAt(chr);
            case NONE -> Result.NONE;
        };
        if (result.outcome() != Outcome.NONE) {
            ChatStats.action(intent.name().toLowerCase(Locale.ROOT) + ":" + result.outcome().name().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /** Whether this bot holds a note that makes it wary of this player. */
    public static boolean wary(int botSlot, int playerId) {
        // Ticket 58: a rival or nemesis is wary of the player too (no parties, no buddy, no trades). Memory only.
        if (soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity.hostile(BotStore.BOT_ID_BASE + botSlot, playerId)) {
            return true;
        }
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

    private static Result trade(BotSM bot, Character player, String line) {
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
        // 1.5.0 rc3 (owner decision 3): other bots trade in person - they name a price for what they carry, or ask
        // to see what the player sells. The price is decided in code (soloMapling.Economy.PriceBand).
        soloMapling.Economy.BotDirectTrade.Answer answer = line == null ? null
                : soloMapling.Economy.BotDirectTrade.chat(bot, bot.getChr(), player, line);
        if (answer != null) {
            return answer.yes() ? new Result(Outcome.TRADE_OFFER, answer.fact(), answer.scripted(), answer.mustMention())
                    : new Result(Outcome.TRADE_DECLINED, answer.fact(), ScriptedReplies.pick(ScriptedReplies.TRADE_NO));
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

    // ── where are you / meet up (ticket 49) ──

    /** Where the bot really is: the map it stands on, named as players name it, with an open invite to come by. */
    static Result whereAt(Character chr) {
        String map;
        try {
            map = mapName(chr.getMapId());
        } catch (RuntimeException e) {
            map = null;
        }
        return whereAt(map);
    }

    static Result whereAt(String map) {
        if (map == null) {
            return Result.NONE;
        }
        return new Result(Outcome.WHERE_AT, "Tell them you are at " + map + " right now; they can come by if they want.",
                String.format(ScriptedReplies.pick(ScriptedReplies.WHERE_AT), map.toLowerCase(Locale.ROOT)),
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
