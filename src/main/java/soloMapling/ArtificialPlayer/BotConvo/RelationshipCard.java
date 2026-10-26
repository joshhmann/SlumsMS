package soloMapling.ArtificialPlayer.BotConvo;

import client.Character;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance;
import soloMapling.ArtificialPlayer.BotDramaSystem.Romance;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotStore;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ticket 59, owner decision 2: the relationship memory card in every chat prompt. In words only, never a number:
 * how the bot feels about the player, the best 1-3 things the two did together and what they last talked about (1.6.0
 * S3), 2-4 things it remembers (with when: "yesterday", "on Tuesday"), an inside joke, the romance
 * stage and the guild relation, and the situation (map, mobs around, time of day, a world event, what it is doing).
 * Compact: at most {@link #MAX_CHARS} characters. {@link #build} is pure; {@link #of} reads the game.
 */
public final class RelationshipCard {

    static final int MAX_CHARS = 520;
    static final int FACTS = 4;
    /** 1.6.0 S3: the best 1-3 things the two did together get their own line. */
    static final int EPISODES = 3;

    /** One remembered thing: the note, whether the bot only heard it (and from whom), and when. */
    public record Fact(String text, String heardFrom, long at) {
    }

    /**
     * Everything the card is built from. Strings may be null (left out). {@code episodes} are the best things the two
     * did together (1.6.0 S3, at most {@link #EPISODES}); {@code lastTopic} and {@code insideJoke} the pair's slots.
     */
    public record Data(String playerName, AffinityState feeling, double score, List<Fact> facts, String romance,
                       String guild, String map, List<String> mobs, String timeOfDay, String worldEvent,
                       String activity, String thread, List<Fact> episodes, Fact lastTopic, String insideJoke) {

        /** The card as before 1.6.0: no episodes, topic or joke. */
        public Data(String playerName, AffinityState feeling, double score, List<Fact> facts, String romance,
                    String guild, String map, List<String> mobs, String timeOfDay, String worldEvent,
                    String activity, String thread) {
            this(playerName, feeling, score, facts, romance, guild, map, mobs, timeOfDay, worldEvent, activity, thread,
                    List.of(), null, null);
        }
    }

    private RelationshipCard() {
    }

    /** The feeling in words (never the score). */
    static String feeling(AffinityState state, double score, boolean anyMemory) {
        if (state == null) {
            state = AffinityState.NEUTRAL;
        }
        return switch (state) {
            case FRIEND -> score >= 60 ? "you're close friends" : "you're friends";
            case RIVAL -> "you have beef with them";
            case NEMESIS -> "they're your nemesis, you can't stand them";
            case NEUTRAL -> score >= 8 ? "you like them ok" : score <= -8 ? "they annoy you a bit"
                    : anyMemory ? "you've met before" : "you don't really know them";
        };
    }

    /** When, in words, as a player says it. */
    static String when(long at, long now, ZoneId zone) {
        if (at <= 0) {
            return null;
        }
        LocalDate then = Instant.ofEpochMilli(at).atZone(zone).toLocalDate();
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        long days = today.toEpochDay() - then.toEpochDay();
        if (days <= 0) {
            return now - at < 2 * 3_600_000L ? "just now" : "earlier today";
        }
        if (days == 1) {
            return "yesterday";
        }
        if (days < 7) {
            DayOfWeek d = then.getDayOfWeek();
            return "on " + d.getDisplayName(TextStyle.FULL, Locale.ENGLISH);
        }
        return days < 14 ? "last week" : "a while ago";
    }

    /** "morning", "afternoon", "evening", "late night". */
    static String timeOfDay(long now, ZoneId zone) {
        int h = LocalTime.ofInstant(Instant.ofEpochMilli(now), zone).getHour();
        return h < 5 ? "late night" : h < 12 ? "morning" : h < 17 ? "afternoon" : h < 22 ? "evening" : "late night";
    }

    /** The card's text, from its parts. */
    public static String build(Data d, long now, ZoneId zone) {
        StringBuilder s = new StringBuilder();
        List<Fact> facts = d.facts() == null ? List.of() : d.facts();
        List<Fact> episodes = d.episodes() == null ? List.of() : d.episodes();
        s.append("About ").append(d.playerName()).append(": ").append(feeling(d.feeling(), d.score(),
                !facts.isEmpty() || !episodes.isEmpty())).append(". ");
        if (!episodes.isEmpty()) {
            List<String> parts = new ArrayList<>();
            for (Fact f : episodes) {
                if (parts.size() == EPISODES) {
                    break;
                }
                String w = when(f.at(), now, zone);
                parts.add(w == null ? f.text() : f.text() + " (" + w + ")");
            }
            s.append("Between you two: ").append(String.join("; ", parts)).append(". ");
        }
        if (d.lastTopic() != null) {
            String w = when(d.lastTopic().at(), now, zone);
            s.append("Last time you talked about ").append(d.lastTopic().text()).append(w == null ? "" : " (" + w + ")")
                    .append(". ");
        }
        if (d.insideJoke() != null) {
            s.append("Inside joke: \"").append(d.insideJoke()).append("\". ");
        }
        if (!facts.isEmpty()) {
            List<String> parts = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (Fact f : facts) {
                if (parts.size() == FACTS || !seen.add(f.text().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                String w = when(f.at(), now, zone);
                String text = f.heardFrom() != null ? "heard from " + f.heardFrom() + ": " + f.text() : f.text();
                parts.add(w == null ? text : text + " (" + w + ")");
            }
            s.append("You remember: ").append(String.join("; ", parts)).append(". ");
            if (facts.stream().anyMatch(f -> f.heardFrom() != null)) {
                s.append("Hearsay is gossip: say who told you if you bring it up. ");
            }
        }
        if (d.romance() != null) {
            s.append(d.romance()).append(". ");
        }
        if (d.guild() != null) {
            s.append(d.guild()).append(". ");
        }
        if (d.thread() != null) {
            s.append(d.thread()).append(". ");
        }
        StringBuilder now_ = new StringBuilder();
        if (d.map() != null) {
            now_.append(d.map());
        }
        if (d.mobs() != null && !d.mobs().isEmpty()) {
            now_.append(now_.length() == 0 ? "" : ", ").append(String.join(" and ", d.mobs())).append(" around");
        }
        String tod = d.timeOfDay() != null ? d.timeOfDay() : timeOfDay(now, zone);
        now_.append(now_.length() == 0 ? "" : ", ").append(tod);
        if (d.activity() != null) {
            now_.append(", ").append(d.activity());
        }
        s.append("Now: ").append(now_).append('.');
        if (d.worldEvent() != null) {
            s.append(" World event: ").append(d.worldEvent()).append('.');
        }
        String out = s.toString();
        return out.length() <= MAX_CHARS ? out : out.substring(0, out.lastIndexOf(' ', MAX_CHARS)) + ".";
    }

    /** The card for a live bot and player. Never throws: a part it cannot read is left out. */
    public static String of(Character bot, Character player, String activity, String thread, long now) {
        int slot = bot.getId() - BotStore.BOT_ID_BASE;
        List<Fact> facts = new ArrayList<>();
        List<Fact> episodes = new ArrayList<>();
        Fact topic = null;
        String joke = null;
        try {
            for (BotMemory.Note n : BotMemory.bestEpisodes(slot, player.getId(), now, EPISODES)) {
                episodes.add(new Fact(n.text(), null, n.createdAt()));
            }
            for (BotMemory.Note n : BotMemory.recallFacts(slot, player.getId(), now,
                    FACTS - Math.min(2, episodes.size()))) {
                facts.add(new Fact(n.text(), n.source() == BotMemory.Source.HEARSAY ? BotMemory.tellerName(n.fromBotId())
                        : null, n.createdAt()));
            }
            topic = BotMemory.lastTopic(slot, player.getId()).map(n -> new Fact(n.text(), null, n.createdAt()))
                    .orElse(null);
            joke = BotMemory.insideJoke(slot, player.getId()).map(BotMemory.Note::text).orElse(null);
        } catch (RuntimeException e) {
            // memory unreadable: no facts
        }
        AffinityState state = AffinityState.NEUTRAL;
        double score = 0;
        try {
            state = BotAffinity.state(bot.getId(), player.getId());
            score = BotAffinity.score(bot.getId(), player.getId());
        } catch (RuntimeException e) {
            // no feelings loaded
        }
        return build(new Data(player.getName(), state, score, facts, romance(bot, player), guild(bot, player),
                mapName(bot), mobs(bot), null, worldEvent(bot), activity, thread, episodes, topic, joke), now,
                ZoneId.systemDefault());
    }

    static String romance(Character bot, Character player) {
        try {
            Romance r = BotRomance.with(player.getId(), bot.getId());
            if (r != null) {
                return switch (r.stage()) {
                    case DATING -> "You're dating them";
                    case ENGAGED -> "You're engaged to them";
                    case MARRIED -> "You're married to them (in game)";
                    case EX -> "They're your ex";
                };
            }
            Romance mine = BotRomance.ofBot(bot.getId());
            if (mine != null) {
                return "You're taken (with someone else)";
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }

    static String guild(Character bot, Character player) {
        try {
            int mine = bot.getGuildId();
            int theirs = player.getGuildId();
            if (mine <= 0) {
                return null;
            }
            String name = soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.guildName(mine);
            if (theirs == mine) {
                return "Same guild" + (name == null ? "" : " (" + name + ")");
            }
            String rel = soloMapling.ArtificialPlayer.BotDramaSystem.GuildWars.relation(mine, theirs);
            if ("war".equals(rel)) {
                return "Your guild" + (name == null ? "" : " " + name) + " is at war with theirs";
            }
            if ("allied".equals(rel)) {
                return "Your guilds are allies";
            }
            return name == null ? null : "You're in the guild " + name;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static String mapName(Character bot) {
        try {
            return bot.getMap() == null ? null : bot.getMap().getMapName();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Up to two kinds of mob on the bot's map, by name. */
    public static List<String> mobs(Character bot) {
        List<String> out = new ArrayList<>();
        try {
            Set<String> names = new LinkedHashSet<>();
            for (server.life.Monster m : bot.getMap().getAllMonsters()) {
                if (m != null && m.getName() != null && !m.getName().isBlank()) {
                    names.add(m.getName().toLowerCase(Locale.ROOT));
                }
                if (names.size() == 2) {
                    break;
                }
            }
            out.addAll(names);
        } catch (RuntimeException e) {
            // no map
        }
        return out;
    }

    static String worldEvent(Character bot) {
        try {
            for (soloMapling.worldevents.ActiveEvent e : soloMapling.worldevents.WorldEvents.getInstance().active()) {
                if (!e.finished()) {
                    return e.template.name() + " at " + e.mapName();
                }
            }
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
        return null;
    }
}
