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
                          List<String> recentChat, String outcome) {
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
        if (memories != null && !memories.isEmpty()) {
            s.append("What you remember about ").append(c.playerName()).append(": ")
                    .append(String.join("; ", memories)).append(". ");
            if (memories.stream().anyMatch(m -> m.startsWith("heard from "))) {
                s.append("Things you only heard from someone else, you did not see yourself: if you bring one up, say ")
                        .append("you heard it (and who from), like gossip. ");
            }
        } else {
            s.append("You have not talked to ").append(c.playerName()).append(" before. ");
        }
        if (c.recentChat() != null && !c.recentChat().isEmpty()) {
            s.append("Recent chat around you: ").append(String.join(" / ", c.recentChat())).append(". ");
        }
        s.append(WORLD_FACTS).append(' ');
        s.append(VOICE).append(' ').append(NEVER).append(' ');
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
