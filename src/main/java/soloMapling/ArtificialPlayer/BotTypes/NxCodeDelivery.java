package soloMapling.ArtificialPlayer.BotTypes;

/**
 * 1.6.0 nx-1: how an NX merchant hands over the 10k code a player just paid 50m for. The mesos have already moved
 * (the trade completed), so every path ends with the buyer holding the NX one way or another:
 * <ol>
 *   <li>The code is minted (stored with its NX) first. If it cannot be stored, the NX goes straight onto the buyer's
 *       Cash Shop balance instead, and no code is typed.</li>
 *   <li>A copy goes into the buyer's notes (the in-game note inbox, shown at the next login), so a closed messenger,
 *       a lost window or a disconnect never loses it.</li>
 *   <li>The bot invites the buyer to Maple Messenger and types the code there, as before.</li>
 *   <li>If the invite is not accepted within {@link #MESSENGER_WINDOW_MS}, the bot pops the note open now and says so;
 *       if the note could not be stored either, it whispers the code.</li>
 * </ol>
 * Game-free: {@link Port} is the bot's side (the test drives a fake).
 */
final class NxCodeDelivery {

    /** NX credit a code is worth (the price stays the bot's 50m; see NXMerchantBot). */
    static final int NX = 10_000;
    /** How long the buyer has to accept the messenger invite before the note is shown instead. */
    static final long MESSENGER_WINDOW_MS = 30_000;

    /** What the delivery needs from the game. */
    interface Port {
        /** A stored code worth {@code nx} NX credit, in display form, or null when it could not be stored. */
        String mint(int nx);

        /** Saves a note to the buyer; false when it could not be stored. */
        boolean saveNote(String buyerName, String text);

        /** Shows the buyer their unread notes now (the note window). */
        void showNotes(int buyerId);

        boolean online(int buyerId);

        void invite(int buyerId);

        boolean accepted(int buyerId);

        /** Types the code into the messenger (the bot's usual lines) and leaves it. */
        void typeCode(String code);

        void leaveMessenger();

        /** Adds NX credit to the buyer's Cash Shop balance; false when the buyer is not online. */
        boolean creditDirect(int buyerId, int nx);

        void whisper(int buyerId, String line);

        /** Map chat by the bot. */
        void say(String line);

        /** A QaLog TRADE line (and the server log for the failure paths). */
        void qa(String line, boolean problem);

        long now();
    }

    private final Port port;
    private final int buyerId;
    private final String buyerName;
    private String code;
    private boolean noted;
    private long deadline;
    private boolean done;

    NxCodeDelivery(Port port, int buyerId, String buyerName) {
        this.port = port;
        this.buyerId = buyerId;
        this.buyerName = buyerName;
    }

    /** The note's text: the code and how to use it (notes hold 200 characters). */
    static String noteText(String code) {
        return "ur 10k nx code: " + code + " - cash shop > coupon, type it without the dashes. keep this note!";
    }

    /** Right after the trade completed. True when a messenger wait follows ({@link #poll} until it returns true). */
    boolean start() {
        code = port.mint(NX);
        if (code == null) {
            boolean credited = port.creditDirect(buyerId, NX);
            port.qa("NXCODE mint failed for " + buyerName + ": " + (credited ? "credited " + NX + " NX directly"
                    : "buyer offline, OWED " + NX + " NX"), true);
            if (credited) {
                port.say("code thing bugged.. put the nx straight on ur account, check cash shop");
            }
            done = true;
            return false;
        }
        noted = port.saveNote(buyerName, noteText(code));
        port.qa("NXCODE minted for " + buyerName + ", note " + (noted ? "saved" : "NOT saved"), !noted);
        port.say("messaging you.");
        port.invite(buyerId);
        deadline = port.now() + MESSENGER_WINDOW_MS;
        return true;
    }

    /** One look at the messenger; true when the delivery is over. */
    boolean poll() {
        if (done) {
            return true;
        }
        boolean online = port.online(buyerId);
        if (online && port.accepted(buyerId)) {
            port.typeCode(code);
            port.qa("NXCODE typed in messenger for " + buyerName, false);
            done = true;
            return true;
        }
        if (online && port.now() < deadline) {
            return false;
        }
        port.leaveMessenger();
        if (!online) {
            port.qa("NXCODE buyer " + buyerName + " went offline: " + (noted ? "code waits in their notes"
                    : "note NOT saved, code " + code + " undelivered"), !noted);
        } else if (noted) {
            port.showNotes(buyerId);
            port.say("u didnt take the messenger.. sent u a note with the code instead");
            port.qa("NXCODE messenger not accepted by " + buyerName + ": note shown", false);
        } else {
            port.whisper(buyerId, "ur nx code: " + code + " (no dashes in the coupon box)");
            port.qa("NXCODE messenger not accepted by " + buyerName + ": code whispered", false);
        }
        done = true;
        return true;
    }

    String code() {
        return code;
    }
}
