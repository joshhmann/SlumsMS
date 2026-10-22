package soloMapling.ArtificialPlayer;

import client.Character;
import constants.game.GameConstants;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;

import java.util.ArrayList;
import java.util.List;

/**
 * The conversation a bot sends to its brain (bot-persistence ticket 13): who it is (persona), where it is and
 * what it is doing (live state), who it is talking to, a few hard facts about the 2006 game world so it does not
 * invent towns and routes, and the conversation so far.
 */
public final class BotChatPrompt {

    /** Facts the model is unreliable about; kept short, because every line costs prompt time on a 2-slot server. */
    static final String WORLD_FACTS = String.join(" ",
            "World facts (GMS v83):",
            "Victoria Island towns: Henesys (bowmen), Ellinia (magicians), Perion (warriors), Kerning City (thieves),",
            "Nautilus (pirates), Lith Harbor (port), Sleepywood (dungeon town below the island, Ant Tunnel).",
            "Any Victoria Island town is a short ride on the regular cab from another (no ship needed).",
            "The ship to Orbis leaves from Ellinia station. From Orbis: El Nath down Orbis Tower, Ludibrium by",
            "train, Leafre by ship.",
            "Job advances: 1st at lv10 (magician lv8) and 2nd at lv30 from your class's instructor in your class's",
            "home town (bowmen Henesys, magicians Ellinia, warriors Perion, thieves Kerning City, pirates Nautilus);",
            "3rd at lv70 in El Nath; 4th at lv120 in Leafre.",
            "Party quests: Kerning PQ lv21-30, Ludi PQ lv35-50, Orbis PQ lv51-70.",
            "The Free Market is reached from the FM entrance in towns.");

    private BotChatPrompt() {
    }

    /** What the prompt is built from: who and where the bot is, who it talks to, and what it knows. */
    public record Context(String botName, int level, String job, String map, String activity, String party, String mood,
                          String playerName, int playerLevel, String playerJob, List<String> memories,
                          List<String> recentChat, String outcome, String stock, boolean recognise) {

        public Context(String botName, int level, String job, String map, String activity, String party, String mood,
                       String playerName, int playerLevel, String playerJob, List<String> memories,
                       List<String> recentChat, String outcome) {
            this(botName, level, job, map, activity, party, mood, playerName, playerLevel, playerJob, memories,
                    recentChat, outcome, null, false);
        }

        /**
         * This context with the bot's stock (ticket 26: a merchant knows what it sells) and whether the bot should
         * greet the player as someone it knows (the chat's first reply to a player it remembers and does not
         * distrust).
         */
        public Context with(String stock, boolean recognise) {
            return new Context(botName, level, job, map, activity, party, mood, playerName, playerLevel, playerJob,
                    memories, recentChat, outcome, stock, recognise);
        }
    }

    /**
     * Ticket 26: how to greet a player the bot remembers - like an acquaintance, not like someone keeping a file.
     */
    static String recognitionHint(String playerName) {
        return "You know " + playerName + " from before and this is the start of the chat: greet them like someone "
                + "you recognise (\"oh hey\", \"yo again\"). You may bring up ONE thing you remember, only if it is "
                + "light and fits (a goal, an item, their level or job), as a casual question; never list what you "
                + "know, never bring up anything bad, and do not sound like you have been keeping tabs on them. ";
    }

    /** How a 2008 player types; shown to the model as a few lines of its own voice. */
    static final String VOICE = String.join(" ",
            "How you type: like a teen or twenty-something in 2008 MapleStory chat. One short line, under 60",
            "characters. Mostly lowercase, era slang (lol, ya, u, ur, idk, tbh, ty, np, gg, brb, pt, lf, kk), typos and",
            "missing apostrophes are fine. No emojis, no markdown or asterisks, no lists, no quotes, no name prefix.",
            "Examples of the voice: \"lol ya pigs drop nothing\" / \"idk maybe ant tunnel?\" / \"brb pots\" /",
            "\"ya im almost 30 finally\".");

    static final String NEVER = String.join(" ",
            "Never: say you are an AI, a bot, a program or a model (if someone asks, shrug it off like a player would,",
            "\"lol what\"); sound like a helpful assistant (no \"I'd love to\", \"feel free\", \"great question\",",
            "\"happy to help\", \"as a\"); mention real places, venues, brands, news or anything after 2008; promise",
            "items or mesos. Talk about the game or casual life only. If you do not know something, say idk.");

    /** Everything above the conversation itself. */
    public static String system(Character bot, Character player, BotPersona.Persona persona, double chattiness,
                                String activity, List<String> memories) {
        return system(context(bot, player, activity, null, memories, List.of(), null), persona, chattiness);
    }

    /** The context of a live bot and player. */
    public static Context context(Character bot, Character player, String activity, String mood, List<String> memories,
                                  List<String> recentChat, String outcome) {
        return new Context(bot.getName(), bot.getLevel(), jobName(bot.getJob().getId()), mapName(bot), activity,
                partyPhrase(bot, player), mood, player.getName(), player.getLevel(), jobName(player.getJob().getId()),
                memories, recentChat, outcome);
    }

    /** Everything above the conversation itself, from its parts. */
    public static String system(Context c, BotPersona.Persona persona, double chattiness) {
        return system(c, persona, chattiness, null, null);
    }

    /** The voice rules when the persona's own voice line (ticket 59) says how to type. */
    static final String VOICE_BASE = String.join(" ",
            "How you type: a player in 2008 MapleStory chat. One short line, under 60 characters. Era slang is fine.",
            "No emojis, no markdown or asterisks, no lists, no quotes, no name prefix.");

    /**
     * Ticket 59: as above, with the relationship memory card in place of the plain memory list (null = the list) and
     * the persona's own voice line in place of the shared voice examples (null = the shared ones).
     */
    public static String system(Context c, BotPersona.Persona persona, double chattiness, String card, String voiceLine) {
        return system(c, persona, chattiness, card, voiceLine, true);
    }

    /**
     * As above; {@code worldFacts} false leaves the world facts out (owner report 2026-10-22: a small model reads a
     * shorter prompt better, and the facts only matter when the line asks about the game world - see
     * {@link #asksAboutTheWorld}). The recent map chat is not in here: it goes last, in the final turn
     * ({@link #finalTurn}).
     */
    public static String system(Context c, BotPersona.Persona persona, double chattiness, String card, String voiceLine,
                                boolean worldFacts) {
        StringBuilder s = new StringBuilder();
        s.append("You are ").append(c.botName()).append(", a real person playing MapleStory in 2008. ");
        s.append("Right now you are a level ").append(c.level()).append(' ').append(c.job());
        if (c.map() != null) {
            s.append(" in ").append(c.map());
        }
        if (c.activity() != null && !c.activity().isEmpty()) {
            s.append(", currently ").append(c.activity());
        }
        if (c.party() != null) {
            s.append(", ").append(c.party());
        }
        s.append(". ");
        if (c.mood() != null && !c.mood().isEmpty()) {
            s.append("Mood: ").append(c.mood()).append(". ");
        }
        s.append(BotPersona.describe(persona, chattiness)).append(' ');
        s.append("You are talking to ").append(c.playerName()).append(", a level ").append(c.playerLevel())
                .append(' ').append(c.playerJob()).append(". ");
        List<String> memories = c.memories();
        if (card != null) {
            s.append(card).append(' ');
            if (c.recognise()) {
                s.append(recognitionHint(c.playerName()));
            }
        } else if (memories != null && !memories.isEmpty()) {
            s.append("What you remember about ").append(c.playerName()).append(": ")
                    .append(String.join("; ", memories)).append(". ");
            if (memories.stream().anyMatch(m -> m.startsWith("heard from "))) {
                s.append("Things you only heard from someone else, you did not see yourself: if you bring one up, say ")
                        .append("you heard it (and who from), like gossip. ");
            }
            if (c.recognise()) {
                s.append(recognitionHint(c.playerName()));
            }
        } else {
            s.append("You have not talked to ").append(c.playerName()).append(" before. ");
        }
        if (c.stock() != null && !c.stock().isEmpty()) {
            s.append(c.stock()).append(' ');
        }
        if (worldFacts) {
            s.append(WORLD_FACTS).append(' ');
        }
        if (voiceLine != null) {
            s.append(VOICE_BASE).append(' ').append(voiceLine).append(' ');
        } else {
            s.append(VOICE).append(' ');
        }
        s.append(NEVER).append(' ');
        s.append("Do not bring up your map or what you are doing unless it fits the chat.");
        if (c.outcome() != null && !c.outcome().isEmpty()) {
            // Last, where a small model pays the most attention: the words must match what the game did.
            s.append(" The game already did this, and your reply must say so: ").append(c.outcome());
        }
        return s.toString();
    }

    /** "in a party with them", "in a party of 3", or null. */
    static String partyPhrase(Character bot, Character player) {
        try {
            net.server.world.Party party = bot.getParty();
            if (party == null) {
                return null;
            }
            if (player != null && player.getParty() != null && player.getParty().getId() == party.getId()) {
                return "in a party with them";
            }
            return "in a party of " + party.getMembers().size();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A mood for the prompt: wary of a player it flagged, focused while grinding, else one per bot per hour. */
    public static String mood(int botSlot, String activity, boolean wary, long now) {
        if (wary) {
            return "wary of this player after how they acted before, short with them";
        }
        if ("grinding".equals(activity)) {
            return "focused on grinding, a bit distracted";
        }
        String[] moods = {"in a good mood", "a bit bored", "tired but ok", "chill", "hyped about the game today",
                "lazy, half afk"};
        long hour = now / 3_600_000L;
        return moods[(int) Math.floorMod(botSlot * 31L + hour, moods.length)];
    }

    private static final java.util.regex.Pattern WORLD_WORDS = java.util.regex.Pattern.compile(
            "(?i).*\\b(where|how do|how to|how 2|town|henesys|ellinia|perion|kerning|nautilus|lith|sleepywood|orbis|"
                    + "el ?nath|ludi|ludibrium|leafre|ship|boat|cab|taxi|job|adv|advance|advancement|instructor|2nd|3rd|"
                    + "4th|pq|party quest|train|lvl|level|lv|map|fm|free market|ant tunnel)\\b.*");

    /** Whether a line asks about the game world (towns, routes, jobs, training), so the world facts help. */
    public static boolean asksAboutTheWorld(String line) {
        return line != null && WORLD_WORDS.matcher(line).matches();
    }

    /**
     * Owner report 2026-10-22: the last turn of a reply prompt - the map's public chat as a transcript (oldest first,
     * the bot's own lines marked "(you)"), what the bot just said when the player is answering it, the player's
     * line, and the one instruction a small model needs most, placed right before it answers. With no transcript
     * and nothing just said (a whisper) the turn is the player's line alone.
     */
    public static String finalTurn(String transcript, String playerName, String justSaid, String line) {
        boolean hasTranscript = transcript != null && !transcript.isBlank();
        if (!hasTranscript && justSaid == null) {
            return line;
        }
        StringBuilder s = new StringBuilder();
        if (hasTranscript) {
            s.append("Public chat on this map just now, oldest first:\n").append(transcript.trim()).append("\n\n");
        }
        if (justSaid != null) {
            s.append("You just said: \"").append(justSaid).append("\". ").append(playerName).append(" answered: \"")
                    .append(line).append("\".\n");
        } else {
            s.append(playerName).append(" says to you: \"").append(line).append("\"\n");
        }
        s.append("Reply to what ").append(playerName).append(" actually said, stay on the same topic. If they asked you ")
                .append("something, answer it. One short line, only your own words.");
        return s.toString();
    }

    /**
     * The full message list: the system prompt, the conversation so far as user/assistant turns, and the player's
     * latest line as {@link #finalTurn} (the history's last turn is that line).
     */
    public static List<BotBrain.Turn> conversation(String system, List<BotBrain.Turn> history, String transcript,
                                                   String playerName, String justSaid) {
        if (history.isEmpty() || history.get(history.size() - 1).role() != BotBrain.Role.USER) {
            return conversation(system, history);
        }
        List<BotBrain.Turn> turns = new ArrayList<>(history.size() + 1);
        turns.add(BotBrain.Turn.system(system));
        turns.addAll(history.subList(0, history.size() - 1));
        String line = history.get(history.size() - 1).content();
        turns.add(BotBrain.Turn.user(finalTurn(transcript, playerName, justSaid, line)));
        return turns;
    }

    /** The full message list: the system prompt, then the conversation so far, ending with the player's line. */
    public static List<BotBrain.Turn> conversation(String system, List<BotBrain.Turn> history) {
        List<BotBrain.Turn> turns = new ArrayList<>(history.size() + 1);
        turns.add(BotBrain.Turn.system(system));
        turns.addAll(history);
        return turns;
    }

    /** How a bot describes what it is doing, from its recorded activity name. */
    public static String activityPhrase(String recorded) {
        if (recorded == null) {
            return null;
        }
        return switch (recorded) {
            case "grind" -> "grinding";
            case "social" -> "hanging out in town";
            case "townwanderer", "henesys" -> "wandering around town";
            case "sellingmerchant", "nxmerchant", "scrolling" -> "running a shop in the Free Market";
            case "buyingmerchant" -> "buying items in the Free Market";
            case "gacha" -> "trying the gachapon";
            case "dropgame" -> "hosting a drop game";
            case "opq" -> "waiting to do Orbis PQ";
            default -> null;
        };
    }

    private static String jobName(int jobId) {
        String name = GameConstants.getJobName(jobId);
        return name == null || name.isBlank() ? "beginner" : name.toLowerCase();
    }

    private static String mapName(Character bot) {
        try {
            return bot.getMap() == null ? null : bot.getMap().getMapName();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
