package soloMapling.ArtificialPlayer.BotConvo;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotChatActions;

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
 * <p>Coverage (owner, 2026-10-31, stage B): greetings and small talk, training spots, level talk, the busy short line,
 * and a party or party-quest ask the gates already answered ({@link #partyMove}: the move words their verdict, in their
 * own words, so the line cannot say yes to a party that was refused). Anything else is free chat, and {@link #plan}
 * returns null so the reply takes the prompt it took before this ticket.
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
        BUSY("One very short line: you are in the middle of something"),
        PARTY_YES("Answer their party ask with a yes"),
        PARTY_NO("Answer their party ask with a no");

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
                       String romance, String note, String shared, int lastLevel,
                       List<String> playerSpots, String mySpot,
                       boolean botHasSpoken, String asked, ChatTriage.Kind kind, ChatTriage.Intent intent,
                       String voice,
                       BotChatActions.Outcome outcome, String outcomeFact) {

        /** A view with no action behind it: plain talk. */
        public View(String botName, int botLevel, String botJob, String place, boolean town, List<String> mobsHere,
                    String activity, boolean inPq, boolean busy, String playerName, int playerLevel, String playerJob,
                    boolean playerIsBot, Bond bond, String romance, String note, String shared, int lastLevel,
                    List<String> playerSpots, String mySpot, boolean botHasSpoken, String asked, ChatTriage.Kind kind,
                    ChatTriage.Intent intent, String voice) {
            this(botName, botLevel, botJob, place, town, mobsHere, activity, inPq, busy, playerName, playerLevel,
                    playerJob, playerIsBot, bond, romance, note, shared, lastLevel, playerSpots, mySpot, botHasSpoken,
                    asked, kind, intent, voice, BotChatActions.Outcome.NONE, null);
        }

        public View {
            mobsHere = mobsHere == null ? List.of() : List.copyOf(mobsHere);
            playerSpots = playerSpots == null ? List.of() : List.copyOf(playerSpots);
            bond = bond == null ? Bond.STRANGER : bond;
            kind = kind == null ? ChatTriage.Kind.TALK : kind;
            outcome = outcome == null ? BotChatActions.Outcome.NONE : outcome;
        }
    }

    /**
     * The decided move, the facts it may state, how brief the phrasing has to be and, for a party ask, what the
     * gates said the answer is (an instruction, not a fact: it goes after the move's own outline).
     */
    public record Plan(Move move, List<String> facts, boolean brief, String instruction) {
        public Plan {
            facts = List.copyOf(facts);
        }

        public Plan(Move move, List<String> facts, boolean brief) {
            this(move, facts, brief, null);
        }
    }

    private ReplyPlanner() {
    }

    // ── the same move without a model: the scripts the bank and the fallback path say ──

    /**
     * One line for this move, typed from the same facts; null when the move has nothing to say here. The scripts are
     * shuffled, so one move is not always one line. Repetition is not the planner's bookkeeping: a line a bot already
     * said to this player is refused at the delivery point ({@code SocialGate.fresh}), and every line a bot says to a
     * player passes the slot net there too ({@code SpeechGuard}, b247), so the planner keeps no memory of its own.
     */
    public static String line(View v, Plan p, java.util.Random rng) {
        return line(v, p, rng, text -> true);
    }

    /**
     * The same, but only a text the delivery point's own net accepts ({@code SocialGate.fresh}: this bot has not said
     * it to this player lately). The scripts are shuffled, so a refused one costs variety, not the line: null only when
     * every script is refused, and the caller falls back to what it would have said without a plan.
     */
    public static String line(View v, Plan p, java.util.Random rng, java.util.function.Predicate<String> acceptable) {
        List<String> options = scripts(v, p.move());
        if (options.isEmpty()) {
            return null;
        }
        List<String> shuffled = new ArrayList<>(options);
        java.util.Collections.shuffle(shuffled, rng);
        for (String candidate : shuffled) {
            if (acceptable.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    static List<String> scripts(View v, Move move) {
        List<String> out = new ArrayList<>(8);
        String place = v.place() == null ? null : v.place().toLowerCase(Locale.ROOT);
        switch (move) {
            case HELLO -> {
                out.addAll(List.of("hey", "yo", "hii", "sup", "hey hey", "o hey", "heya", "hi hi", "hey u", "hii there",
                        "sup man", "hey there", "oh hi", "hiya", "yo whats up", "hello", "hi", "yo there"));
                if (v.bond() == Bond.FRIEND || v.bond() == Bond.DATING) {
                    out.add("hey again man");
                    out.add("oh hey, good to see u");
                }
                out.add("hey " + name(v));
                greetingNow(v, out);
                leveledUp(v, out);
            }
            case HELLO_BACK -> {
                out.addAll(List.of("oh hey again", "yo long time", "heyy", "oh hey", "hey u back", "oh its u",
                        "yo again lol", "hii again", "hey hey long time", "oh hey man", "yo sup again", "hey stranger"));
                out.add("o hey " + name(v));
                greetingNow(v, out);
                leveledUp(v, out);
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
                String goal = LevelTalk.nextGoal(v.botLevel());
                out.add("im lvl " + v.botLevel());
                out.add("lvl " + v.botLevel() + " here lol");
                out.add("im " + v.botLevel() + " lol");
                out.add(v.botLevel() + " atm");
                out.add("im " + v.botLevel() + ", next is " + goal);
                out.add("lvl " + v.botLevel() + ", going for " + goal);
                int gap = v.botLevel() - v.playerLevel();
                if (gap >= 15) {
                    out.add("been here a while lol");
                } else if (gap <= -5) {
                    out.add("ur way ahead of me lol");
                }
                if (v.mySpot() != null && !v.mySpot().isBlank()) {
                    out.add("im " + v.botLevel() + ", i train at " + v.mySpot());
                }
            }
            case BUSY -> {
                // owner's answer 6: a busy bot says what it is doing, and a PQ line is shorter than a grind line
                String mob = v.mobsHere().isEmpty() ? null : v.mobsHere().get(0).toLowerCase(Locale.ROOT);
                if (v.inPq()) {
                    out.addAll(List.of("in pq", "doing pq rn", "pq rn sry", "in a pq rn"));
                } else if (mob != null && mob.length() <= 8) {
                    out.add("on " + mob + "s rn, sec");
                    out.add("killing " + mob + "s rn");
                    out.add(mob + "s rn, sec");
                    out.add("busy on " + mob + "s sry");
                } else if (v.town()) {
                    // a town has no monsters here: name what the bot is doing, never "grinding" (b240, owner's 6)
                    String act = v.activity() == null || v.activity().isBlank()
                            || StateOfMind.claimsGrindingInTown(v.activity())
                            ? null : v.activity().toLowerCase(Locale.ROOT);
                    if (act != null) {
                        out.add("busy rn, " + act);
                        out.add(act + " rn sry");
                    } else if (place != null) {
                        out.add("in " + place + " rn, sec");
                        out.add("busy in " + place + " rn");
                    } else {
                        out.add("busy rn sry");
                    }
                } else if (place != null) {
                    out.add("grinding " + place + " rn");
                    out.add("on " + place + " rn, sec");
                    out.add("busy in " + place + " rn");
                } else if (mob != null) {
                    out.add("on " + mob + "s rn");
                } else if (v.activity() != null && !v.activity().isBlank()) {
                    out.add("busy " + v.activity() + " rn");
                    out.add(v.activity() + " rn sry");
                } else {
                    out.add("busy rn sry");
                }
            }
            case PARTY_YES -> {
                out.addAll(List.of("sure, send the inv", "ya im down, inv me", "ok sure, inv me", "yea inv me",
                        "sure thing, inv me", "im in, send inv"));
            }
            case PARTY_NO -> {
                if (v.outcome() == BotChatActions.Outcome.PARTY_ALREADY) {
                    out.addAll(List.of("in a pt already sry", "got a pt rn sry", "already in a pt lol"));
                } else {
                    out.addAll(List.of("nah sry", "cant rn sry", "not rn sry", "no thx sry", "maybe later sry"));
                }
            }
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
        if (v.outcome() != null && v.outcome() != BotChatActions.Outcome.NONE) {
            // m02 stage B: the gates (BotChatActions, the LFG rules) decided the answer and did it; the move only
            // words their verdict from their own words, so the line cannot say yes to a party that was refused
            Move move = partyMove(v.outcome());
            return move == null ? null
                    : new Plan(move, facts(v, move), false, v.outcomeFact());
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

    /**
     * The move for a party or follow ask the action layer already answered, or null for every other action (a trade,
     * an invite the bot cannot answer in one planned line): those keep the caller's prompt.
     */
    public static Move partyMove(BotChatActions.Outcome outcome) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome) {
            case PARTY_ACCEPTED, FOLLOW_ACCEPTED -> Move.PARTY_YES;
            case PARTY_DECLINED, PARTY_WARY, PARTY_ALREADY, FOLLOW_DECLINED -> Move.PARTY_NO;
            default -> null;
        };
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
                f.add("your next job goal is " + LevelTalk.nextGoal(v.botLevel()));
                if (v.mySpot() != null && !v.mySpot().isBlank()) {
                    f.add("you train at " + v.mySpot());
                }
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

    /**
     * Greeting scripts that say what is true now - where the bot stands and what it is killing - and, when memory has
     * one, that the two have met before (owner's answer 8: a hello uses what is true now, not a stock greeting).
     */
    private static void greetingNow(View v, List<String> out) {
        String place = v.place() == null ? null : v.place().toLowerCase(Locale.ROOT);
        if (v.town() && place != null) {
            out.add("hey, just chillin in " + place);
            out.add("yo, in " + place + " rn");
        } else if (!v.mobsHere().isEmpty()) {
            String mob = v.mobsHere().get(0).toLowerCase(Locale.ROOT);
            out.add("hey, killing " + mob + "s rn");
            out.add("yo, on " + mob + "s rn");
        }
        if (v.shared() != null && !v.shared().isBlank()) {
            out.add("oh hey, remember me lol");
            out.add("hey, we know each other right");
        }
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
            f.add("what you two did before: " + v.shared().trim());
        }
        if (v.bond() != Bond.STRANGER && v.lastLevel() > 0) {
            f.add("when you last talked they were level " + v.lastLevel());
            if (v.playerLevel() > v.lastLevel()) {
                f.add("they are level " + v.playerLevel() + " now: they leveled up since then, and you may say so");
            } else {
                f.add("they are still level " + v.playerLevel() + ": do not say they leveled up");
            }
        } else if (v.bond() != Bond.STRANGER && (v.note() == null || v.note().isBlank())) {
            f.add("you do not know what they have done since then: do not say they leveled up or that they are back "
                    + "after a break");
        }
    }

    /** What the player's progress since the last talk earns them: a grats, and nothing at all when there is none. */
    private static void leveledUp(View v, List<String> out) {
        if (v.lastLevel() <= 0 || v.playerLevel() <= v.lastLevel()) {
            return;
        }
        out.add("oh u leveled, grats");
        out.add("grats on " + v.playerLevel());
        out.add("yo u hit " + v.playerLevel() + "?");
        out.add("nice, " + v.playerLevel() + " now");
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
        // stable first (who the bot is, how it talks, the rules), then what is true now, then what to do: the small
        // model reads the instruction last, right before it writes
        b.append(RULES).append(' ');
        b.append("True right now, and the only things you may claim: ").append(String.join("; ", p.facts()))
                .append(". ");
        b.append(p.move().outline).append(". ");
        if (p.instruction() != null && !p.instruction().isBlank()) {
            b.append(p.instruction().trim()).append(' ');
        }
        if (p.brief()) {
            b.append("You are busy, so keep it tiny. ");
        }
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
