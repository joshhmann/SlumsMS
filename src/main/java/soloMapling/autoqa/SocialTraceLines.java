package soloMapling.autoqa;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.0 (owner 2026-10-26): reads the drama systems' own QA lines (the text {@code DramaKit.qa}, {@code BeefKit},
 * {@code BotCouples.qa} and {@code BotGuilds.qa} build for {@code !qa}) into social-trace facts. Pure; the line formats
 * are pinned by {@code SocialTraceJudgeTest}, so a format change there breaks a test rather than the trace.
 *
 * <p>A {@link Role#START} line opens a drama entry between two bots; an {@link Role#ANSWER} is the follow-up a START
 * waits for (the KSer answering back, a crush becoming a couple); an {@link Role#EVENT} is something a bot did that
 * any open entry about it records (a party, a channel hop, a guild switch); {@link Role#NEWS} is drama news a third
 * bot can later repeat.
 */
public final class SocialTraceLines {

    public enum Role { START, ANSWER, EVENT, NEWS }

    /**
     * One read line. {@code kind}: the drama kind (KS_SPAT ...) for START and ANSWER, the event kind (party, hop,
     * leave, news) for EVENT and NEWS; an ANSWER's {@code why} is the event kind it records (answer, couple). {@code names}: the bots (or guilds, in brackets) it is about,
     * the actor first. {@code ids}: character ids when the line has ids instead of names.
     */
    public record Parsed(Role role, String kind, List<String> names, List<Integer> ids, String text, String why) {
    }

    private SocialTraceLines() {
    }

    private record Rule(Pattern p, java.util.function.Function<Matcher, Parsed> f) {
    }

    private static final List<Rule> RULES = new ArrayList<>();

    private static void rule(String regex, java.util.function.Function<Matcher, Parsed> f) {
        RULES.add(new Rule(Pattern.compile(regex), f));
    }

    private static Parsed p(Role role, String kind, String text, String why, String... names) {
        return new Parsed(role, kind, List.of(names), List.of(), text, why);
    }

    static {
        // BotBotRivalry: a KS'd bot speaks up, the KSer may answer
        rule("^KS SPAT BACK (\\S+) -> (\\S+): (.*)$", m -> p(Role.ANSWER, "KS_SPAT", m.group(3),
                "answer", m.group(2), m.group(1)));
        rule("^KS SPAT (\\S+) -> (\\S+)( \\(rivals\\))?: (.*)$", m -> p(Role.START, "KS_SPAT", m.group(4),
                m.group(3) != null ? "a rival KSed it" : "another bot KSed it", m.group(1), m.group(2)));
        rule("^CALLOUT (\\S+) on (\\S+) \\(nemesis\\): megaphone (.*)$", m -> p(Role.START, "CALLOUT", null,
                "nemesis bond (megaphone " + m.group(3) + ")", m.group(1), m.group(2)));
        rule("^TEASE boom friend (\\S+) -> (\\S+): (.*)$", m -> p(Role.START, "TEASE", m.group(3),
                "a friend's scroll boomed", m.group(1), m.group(2)));
        rule("^VILLAIN NOTICE (\\S+) -> (\\S+): (.*)$", m -> p(Role.START, "VILLAIN_NOTICE", m.group(3),
                "a friend turned villain", m.group(1), m.group(2)));
        rule("^GUILD FEUD LEAVE (.*)$", m -> p(Role.EVENT, "leave", m.group(1), null, firstWord(m.group(1))));
        rule("^GUILD FEUD (.+?): (\\S+) vs (\\S+) \\((.*)\\)$", m -> p(Role.START, "GUILD_FEUD", null,
                "grudge inside " + m.group(1) + " (" + m.group(4) + ")", m.group(2), m.group(3)));
        // GuildWars / BotGuilds
        rule("^POACH (\\S+): (.+?) -> (.+?)( \\(megaphone\\))?$", m -> p(Role.START, "POACH", null,
                "guild war poach from " + m.group(2), m.group(1), "[" + m.group(3) + "]"));
        rule("^JAB (\\S+) \\[(.+?)\\] -> \\[(.+?)\\]: (.*)$", m -> p(Role.START, "JAB", m.group(4),
                "guilds at war, a player watching", m.group(1), "[" + m.group(3) + "]"));
        rule("^PARTY ([^:+\\[]+?): (\\S+(?: \\+ \\S+)+)$", m -> new Parsed(Role.EVENT, "party",
                List.of(m.group(2).split(" \\+ ")), List.of(), "guild party " + m.group(1), null));
        // couples
        rule("^PARTY (\\S+) \\+ (\\S+): (true|false)$", m -> "true".equals(m.group(3))
                ? p(Role.EVENT, "party", "couple party " + m.group(1) + " + " + m.group(2), null, m.group(1), m.group(2))
                : null);
        rule("^HOP (\\S+) -> ch(\\d+) to be with (\\S+): (.*)$", m -> p(Role.EVENT, "hop",
                "hop to ch" + m.group(2) + ": " + m.group(4), null, m.group(1), m.group(3)));
        rule("^CRUSH START (\\S+) -> (\\S+)( \\(staged\\))?$", m -> p(Role.START, "CRUSH", null,
                "a friendship ripened into a crush", m.group(1), m.group(2)));
        rule("^CRUSH LINE (\\S+) -> (\\S+): (.*)$", m -> p(Role.START, "FLIRT", m.group(3), "a crush near its target",
                m.group(1), m.group(2)));
        rule("^CRUSH TO COUPLE #\\d+: (\\S+) \\+ (\\S+)$", m -> p(Role.ANSWER, "CRUSH", "they are a couple now",
                "couple", m.group(1), m.group(2)));
        rule("^FORMED couple #\\d+: (\\d+) \\+ (\\d+)( \\(staged\\))?$", m -> new Parsed(Role.START, "COUPLE", List.of(),
                List.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))), null, "the couple roll"));
        rule("^COUPLE JEALOUS (\\S+) at (\\S+) \\(likes (\\S+)\\): (.*?)(?:; FIGHT \\S+ -> \\S+: (.*))?$",
                m -> p(Role.START, "JEALOUS", m.group(4) + (m.group(5) == null ? "" : " / FIGHT: " + m.group(5)),
                        "a bot likes their partner " + m.group(3), m.group(1), m.group(2)));
        rule("^COUPLE BREAKUP (\\S+) \\+ (\\S+): (.*) \\(megaphone \\w+\\)$", m -> p(Role.START, "BREAKUP", m.group(3),
                "their bond turned hostile", m.group(1), m.group(2)));
        rule("^REBOUND (\\S+) is dating (\\S+) now$", m -> p(Role.START, "REBOUND", null,
                "dumped by a player, picked a bot", m.group(1), m.group(2)));
        // DramaNews: what friends can later bring up in a FRIEND_DRAMA thread
        rule("^NEWS (\\S+) (\\S+)(?: \\((.*)\\))?$", m -> p(Role.NEWS, "news", m.group(1) + " " + m.group(2)
                + (m.group(3) == null ? "" : " (" + m.group(3) + ")"), null, m.group(2)));
    }

    private static String firstWord(String s) {
        int sp = s.indexOf(' ');
        return sp < 0 ? s : s.substring(0, sp);
    }

    /** The line read, or null when it is nothing the trace follows. */
    public static Parsed read(String line) {
        if (line == null || line.isEmpty()) {
            return null;
        }
        for (Rule r : RULES) {
            Matcher m = r.p().matcher(line);
            if (m.matches()) {
                return r.f().apply(m);
            }
        }
        return null;
    }
}
