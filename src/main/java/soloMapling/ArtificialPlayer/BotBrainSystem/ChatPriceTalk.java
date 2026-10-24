package soloMapling.ArtificialPlayer.BotBrainSystem;

import java.util.regex.Pattern;

/** Fair price discussion is not alone evidence of a scam. Strong abuse rules still run first. */
final class ChatPriceTalk {
    private static final Pattern OFFER = Pattern.compile("\\b(?:(?:could|would|can|will) (?:u|you) (?:take|do|accept) "
            + "[0-9]+[kmb]?|knock [0-9]+[kmb]? off|(?:lowest|best|last) price|any (?:lower|discount)|"
            + "selling [a-z -]{1,40} for mesos)\\b");
    private static final Pattern RISK = Pattern.compile("\\b(first|later|after|free|borrow|lend|loan|give|double|account|"
            + "password|paypal|usd|dollars|cashapp|venmo)\\b");
    private ChatPriceTalk() { }
    static boolean fair(String text) { return OFFER.matcher(text).find() && !RISK.matcher(text).find(); }
}
