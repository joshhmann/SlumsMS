package soloMapling.qa;

import client.Character;

import java.util.List;

/**
 * 1.5.0 rc3 economy lane: what the {@code tr-*} scenarios do to the game beyond {@link QaWorld}: bot shops, gifts,
 * quotes, the price board, the tax and haggle tests. {@link GameQaTradeWorld} on the server; the staging tests use a
 * recording fake. Kept apart from QaWorld so the economy lane does not touch every other scenario's world.
 */
public interface QaTradeWorld {

    /** Opens a shop for the bot where the GM stands; the lines saying what was listed. */
    List<String> openBotShop(int botId, Character gm);

    String closeBotShop(int botId);

    /** Pays the bot what its shop sold; a line. */
    String collectBotShop(int botId);

    /** The bot gives the GM a gift now ({@code kind} potions | scroll | mesos | null = by weight); a line. */
    String gift(int botId, Character gm, String kind);

    /** Gifts on sight become certain (true) for the run, or back to economy.yaml's chance (false). */
    void certainGifts(boolean on);

    /** Forgets when this bot last gave this player a gift (in memory), so the weekly limit does not block a re-test. */
    void forgetGift(int botId, int playerId);

    /**
     * 1.6.2: forgets this bot's and this player's asked-for gifts today and the pair's last gift, so the daily and
     * weekly caps do not block a re-test (the world's count stays).
     */
    default void forgetGiftAsks(int botId, Character player) {
    }

    /** The bot quotes the GM a price for something in its bag, as if asked in chat; a line. */
    String quote(int botId, Character gm, String words);

    List<String> haggle(long fair, String line);

    List<String> price(int itemId);

    List<String> tax(long price);

    /** The world's economy meters, one line. */
    String meters();

    /**
     * A headless direct trade: the bot buys {@code itemId} x{@code quantity} from a scripted player who says
     * {@code lines} in turn and then locks. What the bot did, line by line (nothing changes hands).
     */
    List<String> simulateSale(int botId, int itemId, int quantity, List<String> lines);
}
