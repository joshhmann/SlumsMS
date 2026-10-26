package soloMapling.worldevents;

import java.util.Locale;
import java.util.Map;

/**
 * The scripted words of world events (ticket 21): server notices in the plain old-GMS notice style, and the NPC lines.
 * No model writes any of this; LLM flavor text (spec ticket 6) is not wired, so these are what players see.
 */
public final class WorldEventText {

    private WorldEventText() {
    }

    public static String start(EventTemplate.Kind kind) {
        return switch (kind) {
            case INVASION -> "[Monster Invasion] Monsters are pouring into {MAP}! Drive them back before their boss"
                    + " arrives. (Ch {CH}, {MIN} min)";
            case BOSS_SIGHTING -> "[Sighting] {BOSS} has been seen at {MAP}! (Ch {CH})";
            case TREASURE_HUNT -> "[Treasure Hunt] A treasure chest is hidden somewhere in {REGION}! First to find it"
                    + " keeps what's inside. (Ch {CH}, {MIN} min)";
            case HOT_SPOT -> "[Hot Spot] +{BONUS}% EXP at {MAP} for the next {MIN} minutes! (Ch {CH})";
            case WANDERING_NPC -> "[Traveler] {NPC} has wandered into {MAP} and is asking adventurers for help."
                    + " (Ch {CH}, {MIN} min)";
            case WEDDING -> "[Wedding] {BOSS} are getting married in {MAP}! Everyone is welcome. (Ch {CH})";
            case GIANT_CAKE -> "[Giant Cake] A Giant Cake has appeared in {MAP}! Everyone come and take a slice."
                    + " (Ch {CH}, {MIN} min)";
        };
    }

    /**
     * 1.6.0: the headline's warning, {MIN} minutes before it starts ({EVENT}, {MAP}, {CH}). One minute reads "in 1
     * minute".
     */
    public static String warning(EventTemplate.Kind kind, int minutes) {
        String when = minutes <= 1 ? "in 1 minute" : "in {MIN} minutes";
        return switch (kind) {
            case GIANT_CAKE -> "[World Event] A Giant Cake will appear in {MAP} " + when + "! (Ch {CH})";
            case INVASION -> "[World Event] Monsters are marching on {MAP}! They arrive " + when + ". (Ch {CH})";
            case BOSS_SIGHTING -> "[World Event] Something big is stirring near {MAP}... " + when + ". (Ch {CH})";
            case TREASURE_HUNT -> "[World Event] A treasure hunt starts " + when + "! (Ch {CH})";
            case HOT_SPOT -> "[World Event] Bonus EXP at {MAP} starts " + when + "! (Ch {CH})";
            default -> "[World Event] {EVENT} at {MAP} starts " + when + ". (Ch {CH})";
        };
    }

    public static String won(EventTemplate.Kind kind) {
        return switch (kind) {
            case INVASION -> "[Monster Invasion] {PLAYER} defeated {BOSS} and drove the monsters out of {MAP}!";
            case BOSS_SIGHTING -> "[Sighting] {PLAYER} has defeated {BOSS} at {MAP}!";
            case TREASURE_HUNT -> "[Treasure Hunt] {PLAYER} found the treasure in {MAP}!";
            case HOT_SPOT -> "[Hot Spot] The bonus at {MAP} has ended.";
            case WANDERING_NPC -> "[Traveler] {NPC} thanks everyone who helped, and moves on from {MAP}.";
            case WEDDING -> "[Wedding] {BOSS} are now married. Congratulations!";
            case GIANT_CAKE -> "[Giant Cake] The Giant Cake in {MAP} is gone! {PLAYER} got the biggest slice.";
        };
    }

    public static String timedOut(EventTemplate.Kind kind) {
        return switch (kind) {
            case INVASION -> "[Monster Invasion] The monsters have pulled back from {MAP}... for now.";
            case BOSS_SIGHTING -> "[Sighting] {BOSS} has vanished from {MAP}.";
            case TREASURE_HUNT -> "[Treasure Hunt] Nobody found the treasure. It was hidden in {MAP}.";
            case HOT_SPOT -> "[Hot Spot] The bonus at {MAP} has ended.";
            case WANDERING_NPC -> "[Traveler] {NPC} has packed up and left {MAP}.";
            case WEDDING -> "[Wedding] The wedding of {BOSS} in {MAP} is over. Congratulations to the couple!";
            case GIANT_CAKE -> "[Giant Cake] The party in {MAP} is over and the Giant Cake is gone. See you next time!";
        };
    }

    public static String wave(int wave, int waves) {
        return wave + 1 < waves ? "[Monster Invasion] Another wave is coming at {MAP}! (" + (wave + 1) + "/" + waves + ")"
                : "[Monster Invasion] The last wave hits {MAP}!";
    }

    public static String bossArrives() {
        return "[Monster Invasion] {BOSS} has appeared at {MAP}!";
    }

    /** Treasure hints after the opening one: the map's first letter, then the map itself. */
    public static String hint(int n) {
        return n <= 1 ? "[Treasure Hunt] Hint: the map's name starts with \"{INITIAL}\". ({REGION}, Ch {CH})"
                : "[Treasure Hunt] Last hint: the chest is in {MAP}!";
    }

    /** The first letter of a map name, skipping a leading "The". */
    public static String initial(String mapName) {
        if (mapName == null || mapName.isBlank()) {
            return "?";
        }
        String n = mapName.trim();
        if (n.toLowerCase(Locale.ROOT).startsWith("the ") && n.length() > 4) {
            n = n.substring(4).trim();
        }
        return n.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    /** Fills {TOKEN}s; an unknown or empty token is left as a sensible blank rather than shown raw. */
    public static String fill(String text, Map<String, String> tokens) {
        String out = text;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            out = out.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return out.replaceAll("\\{[A-Z_]+}", "").replaceAll(" {2,}", " ").trim();
    }
}
