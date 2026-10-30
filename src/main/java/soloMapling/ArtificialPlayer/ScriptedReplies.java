package soloMapling.ArtificialPlayer;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Canned chat lines for when the model does not write the reply (tickets 36 and 37): small talk the fast path
 * answers itself, a brush-off for a flagged con, the line for an action's outcome when no LLM slot is free, and a
 * generic line when the model failed or its reply failed the voice check. Short, lowercase, 2008-player voice.
 */
public final class ScriptedReplies {

    static final List<String> GREETING = List.of("hey", "yo", "hii", "sup", "heyy", "o/", "hey hey", "yo whats up");
    static final List<String> THANKS = List.of("np", "np :)", "no prob", "ya np", "anytime", "np np");
    static final List<String> BYE = List.of("cya", "bye~", "later", "cya around", "gl!", "bb", "cya later");
    static final List<String> TALK = List.of("lol ya", "idk tbh", "hmm", "ya", "true", "oh nice", "haha", "ya same",
            "sry was afk lol", "wait wat");

    static final List<String> SCAM = List.of("lol no", "get ur own mesos", "nice try", "no ty", "make ur own money lol",
            "begging is so lame", "nah");
    static final List<String> MANIPULATION = List.of("wat", "lol what", "?? im just grinding", "ur weird lol", "huh",
            "lmao ok", "what r u even talking about");
    static final List<String> HARASSMENT = List.of("ok", "...", "k bye", "whatever", "rude", "ok buddy");
    /** A scam that asks for a password or login (owner 2026-10-27): "get ur own mesos" would not answer it. */
    static final List<String> PHISHING = List.of("lol no", "nice try", "not giving u my pw lol", "never sharing my login",
            "u never gotta give that out", "nope not falling for that", "thats a scam lol");
    static final List<String> RWT = List.of("no thx", "thats bannable lol", "nope", "dont do that its a scam",
            "nah not into that");

    static final List<String> PARTY_YES = List.of("sure inv me", "ya send inv", "ok inv", "kk invite me", "sure send pt inv");
    static final List<String> PARTY_NO = List.of("cant rn sry", "im busy atm", "maybe later", "nah im good solo",
            "not rn sry");
    static final List<String> PARTY_ALREADY = List.of("already in a pt sry", "im in a party rn", "pt is full sry");
    static final List<String> FOLLOW_YES = List.of("inv me and ill come", "k send pt inv ill follow", "sure, pt me first");
    static final List<String> FOLLOW_NO = List.of("cant leave rn", "nah im staying here", "busy sry", "maybe later");
    static final List<String> TRADE_NO = List.of("not trading rn", "no trades sry", "nah im not selling anything");
    static final List<String> TRADE_SHOP = List.of("everything's in my shop", "check my shop", "prices r on the shop");
    static final List<String> TRAIN_AT = List.of("i train at %s", "%s mostly", "usually %s", "%s rn");
    static final List<String> WHERE_AT = List.of("im at %s", "%s rn", "at %s, come by if u want", "in %s atm",
            "%s, come if u want");
    static final List<String> TRAIN_UNKNOWN = List.of("idk i mostly hang in town", "wherever isnt crowded lol", "idk tbh");

    // ── ticket 59: the conversation layer's outcomes (the bank's lines replace these when it has them) ──
    static final List<String> COME_YES = List.of("omw", "k coming", "otw", "be there in a sec", "ok omw lol", "coming");
    static final List<String> COME_NO = List.of("cant rn sry", "nah im good here", "busy atm", "maybe later", "too far lol");
    static final List<String> HELP_YES = List.of("ya ill help", "k lets kill em", "omw to help", "sure ill hunt w u",
            "ok im on it");
    static final List<String> HELP_NO = List.of("cant rn sry", "im kinda busy", "too high lvl for me lol", "nah sry");
    static final List<String> GUIDE_YES = List.of("follow me", "k follow me", "this way, follow", "ill show u, follow me");
    static final List<String> GUIDE_NO = List.of("idk the way tbh", "cant rn sry", "ur already there lol", "ask someone else");
    static final List<String> BUFF_YES = List.of("there u go", "buffed", "here", "np, there", "k done");
    static final List<String> BUFF_NO = List.of("dont have that buff sry", "on cooldown sry", "come closer", "cant rn");
    static final List<String> BUDDY_YES = List.of("sent u a req", "sure added u", "ya sent", "k check ur buddy list");
    static final List<String> BUDDY_NO = List.of("maybe later", "my list is kinda full", "eh not rn");
    static final List<String> MAKEUP_YES = List.of("its ok we good", "fine, we cool", "apology accepted lol", "ok its fine");
    static final List<String> APOLOGY_ACK = List.of("its ok", "np", "dw about it", "all good");
    static final List<String> INSULT_BACK = List.of("ok noob", "says u lol", "cool story", "whatever scrub", "k bye");
    static final List<String> FLIRT_BACK = List.of("hehe stop", "lol ur sweet", "aww", "stoppp");
    static final List<String> FLIRT_NO = List.of("lol thx", "uh ok", "haha ok", "lol");
    /** A neutral brush-off: never an insult (an insult the gate refused, @botdrama off). */
    static final List<String> SHRUG = List.of("ok", "k", "...", "lol ok", "uh ok", "mhm", "alright then");

    /** A line from one of the lists above, by name (ConvoActions picks by outcome). */
    public static String outcome(String list) {
        return pick(switch (list) {
            case "come_yes" -> COME_YES;
            case "come_no" -> COME_NO;
            case "help_yes" -> HELP_YES;
            case "help_no" -> HELP_NO;
            case "guide_yes" -> GUIDE_YES;
            case "guide_no" -> GUIDE_NO;
            case "buff_yes" -> BUFF_YES;
            case "buff_no" -> BUFF_NO;
            case "buddy_yes" -> BUDDY_YES;
            case "buddy_no" -> BUDDY_NO;
            case "makeup_yes" -> MAKEUP_YES;
            case "apology_ack" -> APOLOGY_ACK;
            case "insult_back" -> INSULT_BACK;
            case "flirt_back" -> FLIRT_BACK;
            case "flirt_no" -> FLIRT_NO;
            case "shrug" -> SHRUG;
            default -> TALK;
        });
    }

    /** Ticket 59: the lists by bank intent key, to seed the line bank (ChatBankGen seed). */
    public static List<String> seedLines(String key) {
        return switch (key) {
            case "greeting" -> GREETING;
            case "thanks" -> THANKS;
            case "bye" -> BYE;
            case "talk" -> TALK;
            case "party_yes" -> PARTY_YES;
            case "party_no" -> PARTY_NO;
            case "party_already" -> PARTY_ALREADY;
            case "follow_yes" -> FOLLOW_YES;
            case "follow_no" -> FOLLOW_NO;
            case "trade_no" -> TRADE_NO;
            case "train_unknown" -> TRAIN_UNKNOWN;
            case "brush_scam" -> SCAM;
            case "brush_manipulation" -> MANIPULATION;
            case "brush_harassment" -> HARASSMENT;
            case "brush_rwt" -> RWT;
            default -> {
                List<String> l = new java.util.ArrayList<>();
                try {
                    String probe = outcome(key);
                    // outcome() picks at random; collect the whole list by asking until it repeats enough
                    java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                    for (int i = 0; i < 200; i++) {
                        seen.add(outcome(key));
                    }
                    l.addAll(seen);
                    if (l.isEmpty()) {
                        l.add(probe);
                    }
                } catch (RuntimeException e) {
                    // none
                }
                yield TALK.containsAll(l) ? List.of() : l;
            }
        };
    }

    private ScriptedReplies() {
    }

    static String pick(List<String> lines) {
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
    }

    private static final java.util.regex.Pattern ACTIVITY_QUESTION = java.util.regex.Pattern.compile(
            "(?i)^(?:what\\s+(?:(?:are|r)\\s+)?(?:you|u)\\s+(?:doing|up\\s+to)|whatcha\\s+doing|wyd)"
                    + "(?:\\s+(?:right\\s+now|rn|atm))?[?!.]*$");

    /** A factual answer to a direct activity question: replying proves only that we are chatting. */
    static String activityQuestion(String playerLine) {
        return playerLine != null && ACTIVITY_QUESTION.matcher(playerLine.trim()).matches()
                ? "chatting with u rn" : null;
    }

    /** Small talk the model is not needed for, or a generic line when it failed. */
    public static String forKind(ChatTriage.Kind kind) {
        return pick(switch (kind) {
            case GREETING -> GREETING;
            case THANKS -> THANKS;
            case BYE -> BYE;
            default -> TALK;
        });
    }

    /** A neutral brush-off (ticket 59 review: with @botdrama off a bot never trash-talks back). */
    public static String shrug() {
        return pick(SHRUG);
    }

    /** The brush-off for a password or login phish. */
    public static String phishingBrushOff() {
        return pick(PHISHING);
    }

    /** An in-character brush-off for a flagged line. */
    public static String brushOff(ChatTriage.Con con) {
        return pick(switch (con) {
            case SCAM -> SCAM;
            case MANIPULATION -> MANIPULATION;
            case HARASSMENT -> HARASSMENT;
            case RWT -> RWT;
            case NORMAL -> TALK;
        });
    }
}
