package soloMapling.Economy.board;

import soloMapling.Economy.MegaBuyDesk;

import java.util.List;
import java.util.Map;

/**
 * The game as the board sees it, so the board's logic is tested without a server ({@link GameBoardWorld} is the real
 * one). Everything is called off the game thread.
 */
public interface BoardWorld extends BoardRules.Lookup {

    /** An online character of the poster's account, to whisper or message. {@code handle}: the game object. */
    record Recipient(int characterId, String name, Object handle) {
    }

    long now();

    /** A number in [0, 1). */
    double roll();

    /** A whole number in [lo, hi]. */
    int between(int lo, int hi);

    /** Runs {@code task} after {@code delayMs}, off the game thread. */
    void later(long delayMs, Runnable task);

    String itemName(int itemId);

    /** The cheapest unit price of each item on sale in an open FM shop now (the shops listings.json lists). */
    Map<Integer, Long> lowestListings();

    /**
     * What merchant and greedy bots online now hold of this item and would sell (a bag item, a merchant bot's item, a
     * bot shop's shelf: {@link soloMapling.Economy.MegaBuyGame#stock}), {@code fair} filled for bag items. Bots that
     * dislike the poster ({@code posterCharacterId}) are left out.
     */
    List<MegaBuyDesk.Stock> sellers(int itemId, int posterCharacterId);

    /** Merchant and greedy bots online now that would buy this item from a player, with the mesos they may spend. */
    List<BoardOffers.Buyer> buyers(int itemId, int qty, int posterCharacterId);

    /** Right before an offer goes out: the bot is still there with the item. */
    boolean stillHas(MegaBuyDesk.Stock stock);

    /** Right before an offer goes out: mesos this bot may still spend (0 when it is gone). */
    long spendable(int botId);

    /** A character of this account online now, {@code preferred} first; null when nobody on the account is on. */
    Recipient online(int account, String preferred);

    /**
     * Whether the site may name this character on the board: not a GM (gm level above 0) and not an auto-QA character.
     * Known from the last {@link #owner} lookup; true when unknown.
     */
    default boolean publicCharacter(int characterId) {
        return true;
    }

    /** The account's most recently played character's name, or null. */
    String lastPlayed(int account);

    /** A whisper from a bot; false when it could not go out. */
    boolean whisper(Recipient to, String botName, int channel, String line);

    /** A note (memo) waiting at login; false when it could not be saved. */
    boolean note(String from, String toCharacter, String text);

    /** A yellow system line to an online character. */
    void yellow(Recipient to, String text);

    /** A bag bot remembers the price it named, for the trade window that may follow (as the megaphone flow does). */
    void quote(MegaBuyDesk.Stock stock, Recipient to, long price, int rounds);

    /** What merchant and greedy bots online now could post, backed by what they hold. */
    List<BotBoardPosts.Candidate> botPostCandidates();

    /** A bot's post is still backed: the bot is online with the item (WTS) or the mesos (WTB). */
    boolean stillBacks(Board.Post botPost);
}
