package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotStore;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ticket 59: the one door to the line bank for the chat code. It turns a live bot, player and situation into a
 * {@link ChatBank.Query} - the bank intent key, the bot's feelings, its voice, the situation tags and the slot values
 * ({player} {map} {mob} {level} {job} {guild} {partner}, plus the caller's {price} or {item}) - and falls back to the
 * caller's scripted line when the bank is off, missing or has nothing fitting.
 */
public final class ConvoLines {

    /** A line to say, and where it came from (bank line id, or -1). */
    public record Line(String text, int bankId, String source) {
        public boolean fromBank() {
            return bankId >= 0;
        }
    }

    private static volatile ChatBank.Weights weights = ChatBank.Weights.NONE;

    private ConvoLines() {
    }

    /** The {@code !rate} weights (ConvoQuality installs them at boot). */
    public static void useWeights(ChatBank.Weights w) {
        weights = w == null ? ChatBank.Weights.NONE : w;
    }

    /** The bank key for an action's outcome; null when the outcome's own line must stand (prices, shops). */
    public static String key(BotChatActions.Outcome outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case PARTY_ACCEPTED -> "party_yes";
            case PARTY_DECLINED, PARTY_WARY -> "party_no";
            case PARTY_ALREADY -> "party_already";
            case FOLLOW_ACCEPTED -> "follow_yes";
            case FOLLOW_DECLINED -> "follow_no";
            case TRADE_DECLINED -> "trade_no";
            case WHERE_TRAIN_UNKNOWN -> "train_unknown";
            case COME_YES -> "come_yes";
            case COME_NO -> "come_no";
            case HELP_YES -> "help_yes";
            case HELP_NO -> "help_no";
            case GUIDE_YES -> "guide_yes";
            case GUIDE_NO -> "guide_no";
            case BUFF_YES -> "buff_yes";
            case BUFF_NO -> "buff_no";
            case BUDDY_YES -> "buddy_yes";
            case BUDDY_NO -> "buddy_no";
            case MAKEUP_YES -> "makeup_yes";
            // the outcome's own line names the real map, shop or price: it stands
            case WHERE_TRAIN, WHERE_AT, TRADE_SHOP, TRADE_OFFER, MOOD, MOOD_NO, NONE -> null;
        };
    }

    /**
     * The bank key for a line kind with no action (greeting, thanks, bye, talk, a question). An invite the game did
     * not act on ("wanna grind together") gets a decline of its own: the generic talk line asserts things about some
     * other topic and reads as ignoring the player (TEST cv-7-20261024T222923Z: "that drop rate is awful").
     */
    public static String key(ChatTriage.Kind kind, ChatTriage.Intent intent, String playerLine) {
        return switch (kind) {
            case GREETING -> "greeting";
            case THANKS -> "thanks";
            case BYE -> "bye";
            default -> switch (intent == null ? ChatTriage.Intent.NONE : intent) {
                case COME_WITH_ME -> "come_no";
                case PARTY -> "party_no";
                default -> playerLine != null && playerLine.contains("?") ? "talk_q" : "talk";
            };
        };
    }

    /** The brush-off key for a con flag. */
    public static String key(ChatTriage.Con con) {
        return "brush_" + con.name().toLowerCase(Locale.ROOT);
    }

    /** The keys with nothing to say but "hm": the generic talk fallbacks (never an action's own outcome). */
    private static boolean generic(String key) {
        return "talk".equals(key) || "talk_q".equals(key);
    }

    /**
     * The generic fallback line has to answer the player's line, not start a topic of its own ("that drop rate is
     * awful" to "wanna grind together"): an acknowledgement is preferred, and the caller's neutral script answers when
     * the bank has no acknowledgement left.
     */
    static ChatBank.Pick pickAcknowledgement(ChatBank bank, ChatBank.Query q, RecentLines.Window window, java.util.Random rng) {
        return bank.pick(q, window, weights, rng, ConvoLines::acknowledgement);
    }

    /** Words a neutral acknowledgement may carry: it agrees, laughs or hesitates, and claims nothing about a topic. */
    private static final java.util.Set<String> ACK = java.util.Set.of("same", "true", "haha", "hehe", "lol", "lmao",
            "okay", "hmm", "mhm", "nice", "cool", "damn", "wow", "sure", "right", "agreed", "fair", "sorry", "maybe",
            "later", "afk", "brb", "wait", "what", "really", "tbh", "thanks", "thx", "omg", "meh", "gg", "respect",
            "ikr", "sadly", "unlucky", "idk", "dunno", "yes", "yep", "nope", "nah", "yeah", "kekeke", "hahaha",
            "xd", "ugh", "alright", "word", "trueee", "sameee");

    /**
     * Whether a bank line for a generic key is a neutral acknowledgement: at most four words, and every meaning word
     * one of the {@link #ACK} few. "ur right about hnesys being crowded tbh" claims a topic; "ya same" does not.
     */
    static boolean acknowledgement(String text) {
        if (text == null) {
            return false;
        }
        String[] words = text.trim().split("\\s+");
        if (words.length > 4) {
            return false;
        }
        for (String word : soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog.words(text)) {
            if (!ACK.contains(word)) {
                return false;
            }
        }
        return true;
    }

    /**
     * A line for this key: from the bank when it can, else {@code fallback}. {@code extra} adds or overrides slot
     * values (e.g. {@code map} for a training answer). Never null when {@code fallback} is not.
     */
    public static Line line(String key, Character bot, Character player, String tone, Map<String, String> extra,
                            String fallback, boolean whisper) {
        ChatBank bank = key == null || !ConvoSwitches.on("bank") ? null : ChatBank.get();
        if (bank != null && bot != null) {
            try {
                int slot = bot.getId() - BotStore.BOT_ID_BASE;
                ConvoPersona persona = ConvoPersona.of(slot);
                Map<String, String> slots = slots(bot, player);
                if (extra != null) {
                    slots.putAll(extra);
                }
                ChatBank.Query q = new ChatBank.Query(key, tone, affinity(bot, player), persona.voice(),
                        situation(bot, whisper), slots, ChatBank.claimMask(claimsFor(key)));
                RecentLines.Window window = new RecentLines.Window(player == null ? 0 : player.getId(), bot.getId());
                ChatBank.Pick pick = generic(key)
                        ? pickAcknowledgement(bank, q, window, ThreadLocalRandom.current())
                        : bank.pick(q, window, weights, ThreadLocalRandom.current());
                if (pick != null) {
                    RecentLines.used(player == null ? 0 : player.getId(), bot.getId(), pick.id());
                    return new Line(pick.text(), pick.id(), "bank");
                }
            } catch (RuntimeException e) {
                // a slot read failed: the scripted line answers
            }
        }
        return fallback == null ? null : new Line(fallback, -1, "scripted");
    }

    /**
     * rc6 say-do: the claims a bank line for this key may carry - what the decided action does ("come_yes": omw;
     * "party_yes": inv me; "buff_yes": buffed u). Anything else ("brb pots", "gtg", "omw" in a refusal) is skipped for a
     * line without it: the reply says nothing the bot is not doing.
     */
    static java.util.Set<ClaimRules.Kind> claimsFor(String key) {
        String k = key == null ? "" : key;
        return switch (k) {
            case "come_yes", "help_yes" -> java.util.EnumSet.of(ClaimRules.Kind.COME, ClaimRules.Kind.GO_TO,
                    ClaimRules.Kind.TRAIN_AT, ClaimRules.Kind.FOLLOW);
            case "guide_yes", "follow_yes" -> java.util.EnumSet.of(ClaimRules.Kind.FOLLOW, ClaimRules.Kind.COME,
                    ClaimRules.Kind.GO_TO);
            case "party_yes" -> java.util.EnumSet.of(ClaimRules.Kind.PARTY_OFFER, ClaimRules.Kind.PARTY_ASK);
            case "buff_yes" -> java.util.EnumSet.of(ClaimRules.Kind.BUFF, ClaimRules.Kind.GIFT);
            case "trade_shop", "trade_offer" -> java.util.EnumSet.of(ClaimRules.Kind.TRADE, ClaimRules.Kind.TRADE_ASK,
                    ClaimRules.Kind.SELL, ClaimRules.Kind.BUY);
            case "thread_lfg" -> java.util.EnumSet.of(ClaimRules.Kind.PARTY_ASK);
            default -> java.util.EnumSet.noneOf(ClaimRules.Kind.class);
        };
    }

    /** "friend", "rival", "nemesis", "partner", "ex", "neutral". */
    static String affinity(Character bot, Character player) {
        if (player == null) {
            return "neutral";
        }
        try {
            String romance = RelationshipCard.romance(bot, player);
            if (romance != null && romance.startsWith("You're dating") || romance != null && romance.startsWith("You're engaged")
                    || romance != null && romance.startsWith("You're married")) {
                return "partner";
            }
            if (romance != null && romance.startsWith("They're your ex")) {
                return "ex";
            }
            AffinityState s = BotAffinity.state(bot.getId(), player.getId());
            return s == null ? "neutral" : s.label();
        } catch (RuntimeException | LinkageError e) {
            return "neutral";
        }
    }

    static long situation(Character bot, boolean whisper) {
        long m = 0;
        try {
            int mapId = bot.getMapId();
            List<String> mobs = RelationshipCard.mobs(bot);
            if (mapId >= 910000000 && mapId <= 910000022) {
                m |= ChatBank.Tag.FM.bit;
            } else if (mobs.isEmpty()) {
                m |= ChatBank.Tag.TOWN.bit;
            } else {
                m |= ChatBank.Tag.GRIND.bit;
            }
            if (soloMapling.ArtificialPlayer.BotChatTuning.isBossMap(mapId)) {
                m |= ChatBank.Tag.BOSS.bit;
            }
            if (bot.getEventInstance() != null) {
                m |= ChatBank.Tag.PQ.bit;
            }
            if (bot.getParty() != null) {
                m |= ChatBank.Tag.PARTY.bit;
            }
            if (bot.getGuildId() > 0) {
                m |= ChatBank.Tag.GUILD.bit;
            }
            if (RelationshipCard.worldEvent(bot) != null) {
                m |= ChatBank.Tag.EVENT.bit;
            }
            if ("late night".equals(RelationshipCard.timeOfDay(System.currentTimeMillis(), java.time.ZoneId.systemDefault()))) {
                m |= ChatBank.Tag.NIGHT.bit;
            }
        } catch (RuntimeException | LinkageError e) {
            // what could be read stands
        }
        if (whisper) {
            m |= ChatBank.Tag.WHISPER.bit;
        }
        return m | ChatBank.Tag.TRADE.bit | ChatBank.Tag.HAGGLE.bit; // trade talk fits anywhere a trade intent arrives
    }

    static Map<String, String> slots(Character bot, Character player) {
        Map<String, String> s = new HashMap<>();
        if (player != null) {
            s.put("player", player.getName());
        }
        try {
            String map = bot.getMap() == null ? null : bot.getMap().getMapName();
            if (map != null && !map.isBlank()) {
                s.put("map", map.toLowerCase(Locale.ROOT));
            }
            List<String> mobs = RelationshipCard.mobs(bot);
            if (!mobs.isEmpty()) {
                s.put("mob", mobs.get(0));
            }
            s.put("level", String.valueOf(bot.getLevel()));
            String job = constants.game.GameConstants.getJobName(bot.getJob().getId());
            if (job != null && !job.isBlank()) {
                s.put("job", job.toLowerCase(Locale.ROOT));
            }
            if (bot.getGuildId() > 0) {
                String g = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guildName(bot.getGuildId());
                if (g != null) {
                    s.put("guild", g);
                }
            }
            var couple = soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.ofBot(bot.getId());
            if (couple != null) {
                String name = soloMapling.ArtificialPlayer.BotBuddies.nameOf(couple.charId());
                if (name != null) {
                    s.put("partner", name);
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // fewer slots: lines that need them are skipped
        }
        return s;
    }
}
