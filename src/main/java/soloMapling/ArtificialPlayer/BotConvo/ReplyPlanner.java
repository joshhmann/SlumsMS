package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 1.6.2 m02 (chat-model lane): a reply to a player, decided before it is worded. The game picks the move - greet,
 * greet back, answer what it is doing, name a training spot, talk about levels, or one short line while busy - and
 * the facts that are true right now. The model only phrases that, from a prompt that holds nothing else, and the
 * line bank and scripts say the same move when the model is not there.
 *
 * <p>Why: the TEST trace of 2026-10-28..31 had 19 of 325 replies to players on a town map say the bot was grinding
 * there, every greeting came out as one shape ("yo again lol just chillin in henesys"), and all 98 prompts carried two
 * typing styles (the persona's "Chat style:" line and {@code BotChatPrompt.VOICE_BASE}) plus, now and then, a romance
 * or mood the state contradicted. One planner, one voice, one set of facts.
 *
 * <p>Coverage (owner, 2026-10-31): greetings and small talk, training spots, level talk, and the busy short line.
 * Party and PQ asks are stage B; anything else is free chat, and {@link #plan} returns null so the reply takes the
 * prompt it took before this ticket.
 */
public final class ReplyPlanner {

    /** What the reply has to do. {@code outline} is the one instruction the phrasing prompt adds. */
    public enum Move {
        HELLO("Say hi: you are starting this chat"),
        HELLO_BACK("Greet them like someone you know, as this chat opens"),
        SMALL_TALK("They greeted you again mid-chat: answer it without starting the greeting over"),
        ACTIVITY("Answer what you are doing right now"),
        TRAIN_SPOT("Answer where they should train, naming that spot"),
        LEVEL_TALK("Answer about your two levels, using only the numbers above"),
        BUSY("One very short line: you are in the middle of something");

        public final String outline;

        Move(String outline) {
            this.outline = outline;
        }
    }

    /** How the bot stands with the player: the one relationship the reply may show. */
    public enum Bond { STRANGER, KNOWN, FRIEND, WARY, DATING }

    /**
     * Everything the move may be decided from: the bot's state, the player's state, the place, and the read of the
     * player's line. Nulls are allowed (a piece the game could not read); they leave their fact out.
     */
    public record View(String botName, int botLevel, String botJob,
                       String place, boolean town, List<String> mobsHere, String activity,
                       boolean inPq, boolean busy,
                       String playerName, int playerLevel, String playerJob, boolean playerIsBot, Bond bond,
                       String romance, String note, String shared,
                       List<String> playerSpots, String mySpot,
                       boolean botHasSpoken, String asked, ChatTriage.Kind kind, ChatTriage.Intent intent,
                       String voice) {

        public View {
            mobsHere = mobsHere == null ? List.of() : List.copyOf(mobsHere);
            playerSpots = playerSpots == null ? List.of() : List.copyOf(playerSpots);
            bond = bond == null ? Bond.STRANGER : bond;
            kind = kind == null ? ChatTriage.Kind.TALK : kind;
        }
    }

    /** The decided move, the facts it may state, and how brief the phrasing has to be. */
    public record Plan(Move move, List<String> facts, boolean brief) {
        public Plan {
            facts = List.copyOf(facts);
        }
    }

    private ReplyPlanner() {
    }

    // ── the same move without a model: the scripts the bank and the fallback path say ──

    /**
     * One line for this move, typed from the same facts; null when the move has nothing to say here. A line two other
     * bots said in the last ten minutes is passed over while another fits (m02: no reply repeated word for word by
     * three bots; the shared delivery guard is s-chatguards' job, this is the planner's own scripts).
     */
    public static String line(int botId, View v, Plan p, java.util.Random rng) {
        return line(botId, v, p, rng, System.currentTimeMillis());
    }

    /** The same, at a given time (the replay walks recorded traffic in its own clock). */
    static String line(int botId, View v, Plan p, java.util.Random rng, long now) {
        List<String> options = scripts(v, p.move());
        if (options.isEmpty()) {
            return null;
        }
        if (options.size() > 1) {
            List<String> shuffled = new ArrayList<>(options);
            java.util.Collections.shuffle(shuffled, rng);
            for (String candidate : shuffled) {
                if (fresh(botId, candidate, now)) {
                    said(botId, candidate, now);
                    return candidate;
                }
            }
        }
        String pick = options.get(rng.nextInt(options.size()));
        said(botId, pick, now);
        return pick;
    }

    /** Scripted texts recently said, with the bots that said them and when. */
    private static final java.util.Map<String, Recent> RECENT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int REPEAT_BOTS = 2; // at most two bots, so a third never says it word for word
    private static final long REPEAT_MS = 10 * 60_000L;
    private static final int RECENT_CAP = 512;

    private static final class Recent {
        final java.util.Map<Integer, Long> bots = new java.util.concurrent.ConcurrentHashMap<>();
    }

    /** Whether this bot may say this line: fewer than two other bots said it in the last ten minutes. */
    static boolean fresh(int botId, String text, long now) {
        Recent r = RECENT.get(StateOfMind.words(text));
        if (r == null) {
            return true;
        }
        r.bots.values().removeIf(at -> now - at > REPEAT_MS);
        return r.bots.size() < REPEAT_BOTS || r.bots.containsKey(botId);
    }

    static void said(int botId, String text, long now) {
        if (RECENT.size() > RECENT_CAP) {
            RECENT.clear(); // unbounded growth is worse than forgetting: the window is short anyway
        }
        RECENT.computeIfAbsent(StateOfMind.words(text), k -> new Recent()).bots.put(botId, now);
    }

    /** Tests only. */
    static void clearForTest() {
        RECENT.clear();
    }

    static List<String> scripts(View v, Move move) {
        List<String> out = new ArrayList<>(8);
        String place = v.place() == null ? null : v.place().toLowerCase(Locale.ROOT);
        switch (move) {
            case HELLO -> {
                out.addAll(List.of("hey", "yo", "hii", "sup", "hey hey", "o hey", "heya", "hi hi", "hey u", "hii there",
                        "sup man", "hey there", "oh hi", "hiya", "yo whats up", "hello", "hi", "yo there"));
                out.add("hey " + name(v));
            }
            case HELLO_BACK -> {
                out.addAll(List.of("oh hey again", "yo long time", "heyy", "oh hey", "hey u back", "oh its u",
                        "yo again lol", "hii again", "hey hey long time", "oh hey man", "yo sup again", "hey stranger"));
                out.add("o hey " + name(v));
            }
            case SMALL_TALK -> out.addAll(List.of("lol ya", "same as always lol", "not much tbh", "ya just here lol",
                    "hm same", "yea lol same", "still here lol", "aw nothing much", "same lol", "ye just chillin",
                    "lol yup", "nm u"));
            case ACTIVITY -> {
                if (v.town() && place != null) {
                    out.add("just chillin in " + place + " rn");
                    out.add("hanging in " + place + " lol");
                    out.add("not much just in town");
                    out.add("kicking it in " + place);
                    out.add("just walking around " + place);
                    out.add("nothing much, in " + place);
                    out.add("chillin here in " + place + " lol");
                    out.add("just afk in " + place + " tbh");
                } else if (!v.mobsHere().isEmpty()) {
                    String mob = v.mobsHere().get(0).toLowerCase(Locale.ROOT);
                    out.add("killing " + mob + "s here");
                    out.add("grinding " + mob + "s rn");
                    out.add("farming " + mob + "s lol");
                    out.add("just " + mob + " farming tbh");
                    out.add(mob + " farm rn");
                    out.add("on " + mob + "s lol");
                } else if (v.activity() != null && !v.activity().isBlank()) {
                    out.add("just " + v.activity());
                    out.add("im " + v.activity() + " lol");
                }
            }
            case TRAIN_SPOT -> {
                String spot = v.playerSpots().isEmpty() ? null : v.playerSpots().get(0);
                if (spot != null) {
                    out.add("try " + spot);
                    out.add(spot + " is good for ur lvl");
                    out.add("go " + spot);
                    out.add(spot + " works for ur lvl");
                    out.add("try " + spot + " for a bit");
                    out.add(spot + " is nice xp");
                    out.add("u could try " + spot);
                    out.add(spot + " imo");
                }
                if (v.mySpot() != null) {
                    out.add("i train at " + v.mySpot());
                }
            }
            case LEVEL_TALK -> {
                out.add("im lvl " + v.botLevel());
                out.add("lvl " + v.botLevel() + " here lol");
                out.add("im " + v.botLevel() + " lol");
                out.add("just hit " + v.botLevel());
                out.add(v.botLevel() + " atm");
                int gap = v.botLevel() - v.playerLevel();
                if (gap >= 15) {
                    out.add("been here a while lol");
                } else if (gap <= -5) {
                    out.add("u gotta carry me lol");
                }
            }
            case BUSY -> out.addAll(List.of("1 sec", "brb", "busy rn sry", "gimme a sec", "2 min", "sec sry",
                    "kinda busy rn", "one min"));
        }
        return out;
    }

    private static String name(View v) {
        String n = v.playerName() == null ? "" : v.playerName().toLowerCase(Locale.ROOT);
        return n.isEmpty() ? "u" : n;
    }

    /**
     * The move for this reply, or null when the planner has nothing to add (free chat: the reply keeps the prompt it
     * had before this ticket). No randomness: the same state and line always plan the same way.
     */
    public static Plan plan(View v) {
        if (v == null || v.playerIsBot()) {
            return null; // bots talking to bots have their own decisions (BotTalk)
        }
        if (v.intent() != null && v.intent() != ChatTriage.Intent.NONE) {
            return null; // an ask: the action layer decides and does it, and its outcome words the line
        }
        if (v.kind() == ChatTriage.Kind.GREETING || (v.kind() == ChatTriage.Kind.TALK && greets(v.asked()))) {
            if (v.botHasSpoken()) {
                return new Plan(Move.SMALL_TALK, facts(v, Move.SMALL_TALK), v.busy());
            }
            return new Plan(v.bond() == Bond.STRANGER ? Move.HELLO : Move.HELLO_BACK,
                    facts(v, v.bond() == Bond.STRANGER ? Move.HELLO : Move.HELLO_BACK), v.busy());
        }
        if (trainingTalk(v.asked()) && !v.playerSpots().isEmpty() && !levelOnly(v.asked())) {
            return new Plan(Move.TRAIN_SPOT, facts(v, Move.TRAIN_SPOT), v.busy());
        }
        if (levelTalk(v.asked())) {
            return new Plan(Move.LEVEL_TALK, facts(v, Move.LEVEL_TALK), v.busy());
        }
        if (asksDoing(v.asked())) { // after the two above: "what lvl r u" is also a "what ... r u" to that pattern
            return new Plan(Move.ACTIVITY, facts(v, Move.ACTIVITY), v.busy());
        }
        if (v.busy()) {
            return new Plan(Move.BUSY, facts(v, Move.BUSY), true);
        }
        return null;
    }

    // ── the facts: what is true, in the words the model may repeat ──

    static List<String> facts(View v, Move move) {
        List<String> f = new ArrayList<>(6);
        f.add("you are level " + v.botLevel() + " " + job(v.botJob()));
        place(v, f);
        activity(v, f);
        player(v, f);
        bond(v, f);
        switch (move) {
            case TRAIN_SPOT -> {
                f.add("a real training spot for their level is " + v.playerSpots().get(0));
                if (v.mySpot() != null && !v.mySpot().isBlank()) {
                    f.add("you train at " + v.mySpot());
                }
            }
            case LEVEL_TALK -> {
                int gap = v.botLevel() - v.playerLevel();
                if (gap >= 5) {
                    f.add("you are " + gap + " levels above them");
                } else if (gap <= -5) {
                    f.add("they are " + Math.abs(gap) + " levels above you");
                }
            }
            case BUSY -> {
                if (v.inPq()) {
                    f.add("you are in a party quest right now");
                } else if (!v.mobsHere().isEmpty()) {
                    f.add("you are in the middle of grinding here");
                }
            }
            default -> {
            }
        }
        return f;
    }

    /** Where the bot stands - and never "grinding" in a town (the b240 rule, carried into the planner's facts). */
    private static void place(View v, List<String> f) {
        if (v.place() == null || v.place().isBlank()) {
            return;
        }
        if (v.town()) {
            f.add("you are in " + v.place() + ", a town: no monsters here and you are not grinding");
        } else if (!v.mobsHere().isEmpty()) {
            f.add("you are on " + v.place() + ", killing " + String.join(", ", head(v.mobsHere(), 2)));
        } else {
            f.add("you are on " + v.place());
        }
    }

    private static void activity(View v, List<String> f) {
        String a = v.activity();
        if (a == null || a.isBlank()) {
            return;
        }
        if ("grinding".equals(a) && v.town()) {
            return; // the place fact already says what is true here
        }
        if (a.equals("grinding")) {
            return; // place() says it with the monsters
        }
        f.add("right now you are " + a);
    }

    private static void player(View v, List<String> f) {
        if (v.playerName() == null || v.playerName().isBlank()) {
            return;
        }
        f.add("they are " + v.playerName() + ", level " + v.playerLevel() + " " + job(v.playerJob()));
    }

    private static void bond(View v, List<String> f) {
        if (v.bond() == Bond.DATING) {
            String romance = v.romance() == null ? null : v.romance().trim();
            f.add(romance == null || romance.isEmpty() ? "you are dating them" : lowerFirst(romance));
        } else if (v.bond() == Bond.WARY) {
            f.add("you are short with them after how they acted before");
        } else if (v.bond() == Bond.FRIEND) {
            f.add("they are your friend");
        } else if (v.bond() == Bond.KNOWN) {
            f.add("you know them from before");
        } else {
            f.add("you have never talked to them before");
        }
        if (v.note() != null && !v.note().isBlank()) {
            f.add("what you remember about them: " + v.note().trim());
        }
        if (v.shared() != null && !v.shared().isBlank()) {
            f.add("just now: " + v.shared().trim());
        }
        if (v.bond() != Bond.STRANGER && (v.note() == null || v.note().isBlank())) {
            f.add("you do not know what they have done since then: do not say they leveled up or that they are back "
                    + "after a break");
        }
    }

    // ── the phrasing prompt ──

    /**
     * The whole prompt a planned reply is asked with: one voice, the facts, one instruction. Deliberately short (the
     * TEST prompts before this ticket ran about 3,800 characters; the local model reads a short one better), and it
     * carries no second source of style, mood or relationship, so nothing can contradict anything.
     */
    public static String prompt(View v, Plan p) {
        StringBuilder b = new StringBuilder(700);
        b.append("You are ").append(v.botName()).append(", a real person playing this game in 2008, level ")
                .append(v.botLevel()).append(' ').append(job(v.botJob())).append(". ");
        String voice = v.voice() == null || v.voice().isBlank() ? DEFAULT_VOICE : v.voice().trim();
        b.append(voice).append(' ');
        b.append("True right now, and the only things you may claim: ").append(String.join("; ", p.facts()))
                .append(". ");
        b.append(p.move().outline).append(". ");
        if (p.brief()) {
            b.append("You are busy, so keep it tiny. ");
        }
        b.append(RULES);
        return b.toString();
    }

    /** One style line, used only when the bot has no persona voice line. */
    static final String DEFAULT_VOICE = "Your typing: like a 2008 player, one short line, mostly lowercase, era slang "
            + "(lol, u, ur, idk, tbh, brb)";

    /** The rules that keep every planned line in voice and out of trouble, in one sentence. */
    static final String RULES = "One short line under 60 characters, mostly lowercase, no emojis, no markdown, no name "
            + "prefix. Never say you are an AI or a bot, never promise items or mesos, nothing after 2008, and do not "
            + "bring up places, levels or history that are not above.";

    // ── reading the player's line (the planner's own, after the triage) ──

    /**
     * A greeting the rule triage alone does not call one: "hey hows it going", "hi guys", "sup". The decide model
     * reads most of these as greetings; this keeps the reply planned when it is not there. A greeting word followed by
     * something unrelated ("yo i saw a cool hat") is not a greeting, and that reply keeps the free chat.
     */
    static boolean greets(String line) {
        if (line == null) {
            return false;
        }
        String said = StateOfMind.words(line);
        Matcher head = GREET_HEAD.matcher(said);
        if (head.matches()) {
            String rest = head.group(1);
            return rest == null || rest.isBlank() || GREET_TAIL.matcher(rest).matches();
        }
        return GREET_PHRASE.matcher(said).find();
    }

    private static final Pattern GREET_HEAD = Pattern.compile("^(?:hey|hi|hiya|hello|yo|sup|heya|howdy)(?: (.*))?$");
    private static final Pattern GREET_TAIL = Pattern.compile(
            "^(?:guys|all|there|everyone|again|how|hows|whats|sup|waddup|wassup|dood|dude|man|bro|buddy|friend)\\b.*");
    private static final Pattern GREET_PHRASE = Pattern.compile(
            "\\b(?:hows it going|how is it going|how are you|how are u|how r you|how r u|whats up|wassup|waddup|"
                    + "how you doin|nice to meet)\\b");
    /** A question about what the bot is doing or where it is. */
    static boolean asksDoing(String line) {
        return line != null && StateOfMind.ASKS_DOING.matcher(StateOfMind.words(line)).find();
    }

    private static final Pattern TRAIN_ASK = Pattern.compile(
            "\\b(where|wat|what|which|wut)\\b[^?]{0,20}\\b(train|trainin|training|grind|grindin|grinding|level|levelin|"
                    + "lvlin|lvl|spots?|maps?|pq)\\b|\\b(training spot|train spot|good spot|best spot)\\b|"
                    + "\\b(where (?:should|do|can) i (?:go|train|grind))\\b|\\b(kpq|lpq|opq|cpq|ppq|hpq)\\b");

    /** Where to train: a real spot the planner can name, or a party quest it would have to talk about. */
    static boolean trainingTalk(String line) {
        return line != null && TRAIN_ASK.matcher(StateOfMind.words(line)).find();
    }

    private static final Pattern LEVEL_TALK = Pattern.compile(
            "\\b(lvl|level|lv)\\s*\\d+|\\b(what|which|wat) (?:lvl|level|lv)\\b|"
                    + "\\b(grats|gratz|congrats|gz) (?:on )?(?:lvl|level|lv)\\b|"
                    + "\\b(too high|too low|so high|so low|higher level|lower level|same level|level gap)\\b|"
                    + "\\bim (?:only )?(?:lvl|level|lv)\\b|\\bur (?:lvl|level|lv) (?:is )?\\d+");

    /** The train-question pattern is greedy enough to catch "what lvl r u": that line is only about levels. */
    static boolean levelOnly(String line) {
        return line != null && levelTalk(line) && !TRAIN_VERB.matcher(StateOfMind.words(line)).find();
    }

    private static final Pattern TRAIN_VERB = Pattern.compile(
            "\\b(train|trainin|training|grind|grindin|grinding|spots?|map|maps|go|pq)\\b");

    /** The player talks about levels: theirs, the bot's, or the gap. */
    static boolean levelTalk(String line) {
        return line != null && LEVEL_TALK.matcher(StateOfMind.words(line)).find();
    }

    private static String job(String job) {
        return job == null || job.isBlank() ? "beginner" : job;
    }

    private static <T> List<T> head(List<T> l, int n) {
        return l.size() <= n ? l : l.subList(0, n);
    }

    private static String lowerFirst(String s) {
        if (s.isEmpty() || Character.isLowerCase(s.charAt(0))) {
            return s;
        }
        return s.substring(0, 1).toLowerCase(Locale.ROOT) + s.substring(1);
    }
}
