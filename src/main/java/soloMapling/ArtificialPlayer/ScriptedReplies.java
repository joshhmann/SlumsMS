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
    static final List<String> TRAIN_UNKNOWN = List.of("idk i mostly hang in town", "wherever isnt crowded lol", "idk tbh");

    private ScriptedReplies() {
    }

    static String pick(List<String> lines) {
        return lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
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
