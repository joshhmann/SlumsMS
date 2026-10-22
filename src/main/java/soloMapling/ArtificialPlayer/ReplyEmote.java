package soloMapling.ArtificialPlayer;

import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;

import java.util.Locale;
import java.util.Random;
import java.util.regex.Pattern;

/**
 * The face a bot makes with a chat reply (ticket 37): chosen from what is already known about the exchange - the
 * con flag, what the game did for the request, the line kind - and a cheap keyword pass over the bot's own reply.
 * Shown now and then, not on every line, at the moment the bubble appears.
 *
 * <p>Only the seven v83 faces every character has (the F1-F7 keys, ids 1-7; {@code FaceExpressionHandler} lets any
 * player use them). Ids 8 and up need a cash-shop item, so a bot never uses them.
 *
 * <p>System property: {@code solomapling.chat.replyEmoteChance} (0.55), the chance a reply that has a fitting face
 * shows it.
 */
public final class ReplyEmote {

    /** No face. */
    public static final int NONE = 0;
    public static final int HIT = 1;
    public static final int SMILE = 2;
    public static final int TROUBLED = 3;
    public static final int CRY = 4;
    public static final int ANGRY = 5;
    /** "bewildered" in the client data. */
    public static final int SURPRISED = 6;
    /** "stunned" in the client data. */
    public static final int SHOCKED = 7;

    private static final Pattern SAD = Pattern.compile("(:\\(|:'\\(|;\\(|t_t|;_;|\\bqq\\b|\\bsad\\b|\\brip\\b|\\bunlucky\\b"
            + "|\\bdied\\b|\\bnoo+\\b|\\bsob\\b)");
    private static final Pattern ANNOYED = Pattern.compile("(>:\\(|\\bugh+\\b|\\bwtf\\b|\\bannoying\\b|\\brude\\b"
            + "|\\bnice try\\b|\\bget ur own\\b|\\blame\\b)");
    private static final Pattern SHOCK = Pattern.compile("(\\bomg\\b|\\bomfg\\b|\\bno way\\b|\\bwhoa+\\b|\\bwo+w\\b|!\\?|\\?!"
            + "|\\bholy\\b|\\bwat\\b|\\bhuh\\b|\\bwhat\\?|\\?\\?|o_o|:o\\b)");
    private static final Pattern HAPPY = Pattern.compile("(\\blo+l\\b|\\blmf?ao+\\b|\\bha(ha)+h?\\b|\\bhehe\\b|\\bxd+\\b"
            + "|:\\)|:d\\b|\\^\\^|\\^_\\^|;\\)|:p\\b|\\bgl\\b|\\bgg\\b|\\bnice\\b|\\bcool\\b|\\bawesome\\b|\\byay\\b"
            + "|\\bnp\\b|<3|~$)");
    private static final Pattern UNSURE = Pattern.compile("(\\bidk\\b|\\bhm+\\b|\\bnot sure\\b|\\bdunno\\b|\\bsry\\b"
            + "|\\bsorry\\b|\\bcant\\b|\\bcan't\\b|\\bbusy\\b|\\.\\.\\.)");

    private ReplyEmote() {
    }

    /**
     * The face that fits a reply, or {@link #NONE}: the con flag first, then what the game did, then the reply's
     * own tone, then the kind of line it answered.
     *
     * @param triage  how the player's line was sorted (null when unknown)
     * @param outcome what the game did for the request (null or NONE when nothing)
     * @param asked   the player's line (null when unknown)
     * @param reply   the bot's reply
     */
    public static int face(ChatTriage.Result triage, BotChatActions.Outcome outcome, String asked, String reply) {
        if (triage != null) {
            switch (triage.con()) {
                case HARASSMENT, SCAM -> {
                    return ANGRY;
                }
                case RWT -> {
                    return TROUBLED;
                }
                case MANIPULATION -> {
                    return SURPRISED;
                }
                default -> {
                }
            }
        }
        if (outcome != null) {
            switch (outcome) {
                case PARTY_ACCEPTED, FOLLOW_ACCEPTED, TRADE_SHOP, TRADE_OFFER, WHERE_TRAIN, WHERE_AT -> {
                    return SMILE;
                }
                case PARTY_DECLINED, FOLLOW_DECLINED, TRADE_DECLINED, PARTY_ALREADY, WHERE_TRAIN_UNKNOWN -> {
                    return TROUBLED;
                }
                case PARTY_WARY -> {
                    return ANGRY;
                }
                default -> {
                }
            }
        }
        int tone = tone(reply);
        if (tone != NONE) {
            return tone;
        }
        if (triage != null) {
            switch (triage.kind()) {
                case GREETING, THANKS, BYE -> {
                    return SMILE;
                }
                default -> {
                }
            }
        }
        if (asked != null && asked.contains("?") && reply != null && !reply.isBlank()) {
            return TROUBLED; // a question answered plainly: a thinking face
        }
        return NONE;
    }

    /** The face a line's own words suggest, or {@link #NONE}. */
    public static int tone(String line) {
        if (line == null || line.isBlank()) {
            return NONE;
        }
        String text = line.toLowerCase(Locale.ROOT).trim();
        if (SAD.matcher(text).find()) {
            return CRY;
        }
        if (ANNOYED.matcher(text).find()) {
            return ANGRY;
        }
        if (SHOCK.matcher(text).find()) {
            return SURPRISED;
        }
        if (HAPPY.matcher(text).find()) {
            return SMILE;
        }
        if (UNSURE.matcher(text).find()) {
            return TROUBLED;
        }
        return NONE;
    }

    /** The face for a filler line the bot answers with a face alone ("lol", "wow", "t_t"): its tone, else a smile. */
    public static int forFiller(String line) {
        int tone = tone(line);
        return tone == NONE || tone == ANGRY ? SMILE : tone;
    }

    /** As {@link #face}, shown only with the configured chance; {@link #NONE} when not shown. */
    public static int pick(ChatTriage.Result triage, BotChatActions.Outcome outcome, String asked, String reply,
                           Random rng) {
        int face = face(triage, outcome, asked, reply);
        return face != NONE && rng.nextDouble() < chance() ? face : NONE;
    }

    static double chance() {
        try {
            return Double.parseDouble(System.getProperty("solomapling.chat.replyEmoteChance", "0.55"));
        } catch (NumberFormatException e) {
            return 0.55;
        }
    }
}
