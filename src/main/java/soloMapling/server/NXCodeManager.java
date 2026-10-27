package soloMapling.server;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.security.SecureRandom;

/**
 * Cash Shop coupon codes the bots hand out: the NX merchant's 10k code (sold for mesos), the tutorial bot's grants.
 * A code is one {@code nxcode} row (no retriever yet, a far expiry) plus its {@code nxcode_items} rows, which
 * {@link net.server.channel.handlers.CouponCodeHandler} credits when a player types the code into the Cash Shop's
 * coupon box. Type 4 is NX credit there ({@code gainCash(1, ...)}): the same NX the Cash Shop spends first.
 */
public class NXCodeManager {

    /** Far-future expiry (ms since epoch, year 2252): a sold code never runs out. */
    static final long NO_EXPIRY = 8901234567890L;
    /** The coupon item type CouponCodeHandler credits as NX credit (gainCash(1, ...)). */
    static final int TYPE_NX_CREDIT = 4;
    /** The code alphabet: no 0/O, 1/I/L, so a code read off a messenger window is typed back right. */
    static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    /** A code is worth real NX: not the shared java.util.Random, whose next values a player could work out. */
    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    /**
     * 1.6.0 nx-1: what a typed code is looked up as: upper case, dashes and spaces dropped. Codes are stored without
     * dashes; the bots show them as XXXXX-XXXXX-XXXXX, and a player who types the dashes (or a space) still redeems.
     */
    public static String normalize(String code) {
        if (code == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(code.length());
        for (int i = 0; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (ch != '-' && !Character.isWhitespace(ch)) {
                sb.append(Character.toUpperCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * Stores a code worth {@code quantity} NX credit: the code row and its NX row in one transaction, so a code is
     * never stored without its NX (a redeem that credits nothing) nor typed to a player when the store failed.
     *
     * @param code     the code, dashes optional (stored without)
     * @param quantity NX credit it is worth
     * @return the code's row id, or -1 when it could not be stored (duplicate code, database down)
     */
    public static int createCompleteNXCode(String code, int quantity) {
        try (Connection con = DatabaseConnection.getConnection()) {
            return createCompleteNXCode(con, code, quantity);
        } catch (SQLException e) {
            e.printStackTrace();
            return -1;
        }
    }

    /** Overload with the default 10k NX quantity. */
    public static int createCompleteNXCode(String code) {
        return createCompleteNXCode(code, 10000);
    }

    /**
     * The insert itself (package-private for the test). Ids come from AUTO_INCREMENT: the old SELECT MAX(id)+1 made
     * two bots minting at once collide on the primary key, and the loser's code was typed to its buyer anyway.
     */
    static int createCompleteNXCode(Connection con, String code, int quantity) throws SQLException {
        String stored = normalize(code);
        boolean auto = con.getAutoCommit();
        con.setAutoCommit(false);
        try {
            int codeId;
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO nxcode (code, retriever, expiration) VALUES (?, NULL, ?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, stored);
                ps.setLong(2, NO_EXPIRY);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("no id for nxcode " + stored);
                    }
                    codeId = keys.getInt(1);
                }
            }
            try (PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO nxcode_items (codeid, type, item, quantity) VALUES (?, ?, ?, ?)")) {
                ps.setInt(1, codeId);
                ps.setInt(2, TYPE_NX_CREDIT);
                ps.setInt(3, 0); // item id (not used for NX)
                ps.setInt(4, quantity);
                ps.executeUpdate();
            }
            con.commit();
            return codeId;
        } catch (SQLException e) {
            try {
                con.rollback();
            } catch (SQLException ignored) {
                // the connection is gone; nothing was committed
            }
            throw e;
        } finally {
            try {
                con.setAutoCommit(auto);
            } catch (SQLException ignored) {
                // returned to the pool either way
            }
        }
    }

    /**
     * 1.6.0 nx-1: a fresh code worth {@code quantity} NX, stored, in its display form (XXXXX-XXXXX-XXXXX); a second
     * try with a new code if the first one could not be stored (a duplicate). Null when none could be stored: the
     * caller must then make the buyer whole another way, never type a code that does not exist.
     */
    public static String mintCode(int quantity) {
        for (int attempt = 0; attempt < 2; attempt++) {
            String code = generateGiftCardCode();
            if (createCompleteNXCode(code, quantity) > 0) {
                return code;
            }
        }
        return null;
    }

    /**
     * Generates a random gift card code in the format XXXXX-XXXXX-XXXXX: 15 code characters from {@link #ALPHABET}
     * (no 0/O/1/I/L) in three groups. Stored and looked up without the dashes (15 characters; nxcode.code holds 17).
     */
    public static String generateGiftCardCode() {
        StringBuilder codeBuilder = new StringBuilder();
        for (int section = 0; section < 3; section++) {
            if (section > 0) {
                codeBuilder.append("-");
            }
            for (int i = 0; i < 5; i++) {
                codeBuilder.append(ALPHABET.charAt(CODE_RANDOM.nextInt(ALPHABET.length())));
            }
        }
        return codeBuilder.toString();
    }

}
