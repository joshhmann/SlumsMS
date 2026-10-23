package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotChatActions;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotSM;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 1.5.0 rc6 (QA t-af-1, owner: "deepen the chat depending on level like where they grind, whats new"): one short
 * prompt line so a bot's chat fits its level - where it stands in the job ladder and its next goal, where it grinds
 * (mob level), and one or two things that are new on the server (a world event on now, a new feature of this
 * release). The model is told to use them only when they fit; it never lists them.
 */
public final class LevelTalk {

    /** New in this release, as a player would mention it; one is offered a day, in turn. */
    static final List<String> NEW_FEATURES = List.of(
            "bots get married in amoria now, there are real weddings",
            "bot guilds started guild wars on each other",
            "you can date a bot with @romance, some even propose",
            "rivals KS you now if you KS them, some call their friends",
            "best friends wear friendship rings now");

    static final int MAX_CHARS = 330;

    private LevelTalk() {
    }

    /** Where a level stands in the job ladder: "still a beginner", "early 2nd job", ... */
    static String stage(int level) {
        if (level < 10) {
            return "still a beginner";
        }
        if (level < 30) {
            return level < 20 ? "early 1st job" : "late 1st job";
        }
        if (level < 70) {
            return level < 50 ? "early 2nd job" : "late 2nd job";
        }
        if (level < 120) {
            return level < 95 ? "3rd job" : "late 3rd job";
        }
        return level < 200 ? "4th job" : "max level";
    }

    /** The goal a player at this level talks about. */
    static String nextGoal(int level) {
        if (level < 10) {
            return "1st job at 10";
        }
        if (level < 30) {
            return "2nd job at 30";
        }
        if (level < 70) {
            return "3rd job at 70";
        }
        if (level < 120) {
            return "4th job at 120";
        }
        return level < 200 ? "the long grind to 200" : "helping friends level";
    }

    /** The day's new feature (one a day, in turn). */
    static String featureOfTheDay(long now, ZoneId zone) {
        long day = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toEpochDay();
        return NEW_FEATURES.get((int) Math.floorMod(day, (long) NEW_FEATURES.size()));
    }

    /** The line, from its parts; {@code trainingMap} null = not known, {@code mobLevel} below 0 = not known. */
    static String line(int level, String trainingMap, int mobLevel, List<String> news) {
        StringBuilder s = new StringBuilder("Level talk: you are ").append(stage(level)).append(", next goal ")
                .append(nextGoal(level));
        if (trainingMap != null && !trainingMap.isBlank()) {
            s.append("; you grind at ").append(trainingMap.toLowerCase(java.util.Locale.ROOT));
            if (mobLevel >= 0) {
                s.append(" (mobs lv ").append(mobLevel).append(')');
            }
        }
        s.append('.');
        if (news != null && !news.isEmpty()) {
            s.append(" New on the server: ").append(String.join("; ", news)).append('.');
        }
        s.append(" Talk like someone at your level (where you grind, your next goal, what's new) only when it fits.");
        String out = s.toString();
        return out.length() <= MAX_CHARS ? out : out.substring(0, out.lastIndexOf(' ', MAX_CHARS)) + ".";
    }

    /** What is new now: a world event that is on (at most one) and the day's new feature. */
    static List<String> whatsNew(long now) {
        List<String> out = new ArrayList<>();
        try {
            for (soloMapling.worldevents.ActiveEvent e : soloMapling.worldevents.WorldEvents.getInstance().active()) {
                if (!e.finished()) {
                    out.add((e.template.name() + " going on at " + e.mapName()).toLowerCase(java.util.Locale.ROOT));
                    break;
                }
            }
        } catch (RuntimeException | LinkageError e) {
            // no world events here
        }
        out.add(featureOfTheDay(now, ZoneId.systemDefault()));
        return out;
    }

    /** The line for a live bot (never throws: a part it cannot read is left out). Empty when there is no bot. */
    public static String promptLine(BotSM bot, long now) {
        Character chr = bot == null ? null : bot.getChr();
        if (chr == null) {
            return "";
        }
        try {
            String map = null;
            int mobLevel = -1;
            try {
                map = BotChatActions.trainingMapName(bot, chr);
                if (MapMobIndex.level(chr.getMapId()) >= 0) {
                    mobLevel = MapMobIndex.level(chr.getMapId());
                }
            } catch (RuntimeException | LinkageError e) {
                map = null;
            }
            return " " + line(chr.getLevel(), map, mobLevel, whatsNew(now));
        } catch (RuntimeException e) {
            return "";
        }
    }
}
