package client;

import config.YamlConfig;
import tools.BCrypt;
import tools.HexTool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * The account password hash rules, shared by the login screen ({@link Client#login}, automatic registration) and the
 * site/launcher sign-up and sign-in (ticket 105), so both check and store passwords the same way.
 */
public final class AccountPasswords {

    /** How a typed password matched the stored value. */
    public enum Match {
        /** A bcrypt hash matched: nothing to upgrade. */
        BCRYPT,
        /** A legacy value matched (plain text, SHA-1 or SHA-512): upgraded to bcrypt when BCRYPT_MIGRATION is on. */
        LEGACY,
        NONE
    }

    private AccountPasswords() {
    }

    /** The login screen's check: bcrypt, or a legacy plain-text, SHA-1 or SHA-512 value. */
    public static Match check(String pwd, String passhash) {
        return check(pwd, passhash, true);
    }

    /**
     * As {@link #check} but never a plain-text equality: bcrypt, SHA-1 and SHA-512 only. The site uses this; some
     * stored values are plain text on purpose (the bot template's "no login" marker).
     */
    public static Match checkHashedOnly(String pwd, String passhash) {
        return check(pwd, passhash, false);
    }

    private static Match check(String pwd, String passhash, boolean allowPlain) {
        if (pwd == null || passhash == null || passhash.isEmpty()) {
            return Match.NONE;
        }
        if (passhash.length() > 1 && passhash.charAt(0) == '$' && passhash.charAt(1) == '2') {
            try {
                if (BCrypt.checkpw(pwd, passhash)) {
                    return Match.BCRYPT;
                }
            } catch (RuntimeException e) {
                return Match.NONE;   // a malformed bcrypt value matches nothing
            }
        }
        // thanks GabrielSin for detecting some no-bcrypt inconsistencies here
        if ((allowPlain && pwd.equals(passhash)) || checkHash(passhash, "SHA-1", pwd) || checkHash(passhash, "SHA-512", pwd)) {
            return Match.LEGACY;
        }
        return Match.NONE;
    }

    /** The stored value for a new account's password: bcrypt (cost 12) when BCRYPT_MIGRATION is on, else SHA-512. */
    public static String hashForNewAccount(String pwd) {
        return YamlConfig.config.server.BCRYPT_MIGRATION ? bcrypt(pwd) : sha512(pwd);
    }

    public static String bcrypt(String pwd) {
        return BCrypt.hashpw(pwd, BCrypt.gensalt(12));
    }

    static String sha512(String pwd) {
        return digest("SHA-512", pwd);
    }

    private static boolean checkHash(String hash, String type, String password) {
        return digest(type, password).equals(hash);
    }

    // Hashes the first length() bytes of the UTF-8 form, as the original code did (identical for ASCII passwords).
    private static String digest(String type, String password) {
        try {
            MessageDigest digester = MessageDigest.getInstance(type);
            digester.update(password.getBytes(StandardCharsets.UTF_8), 0, password.length());
            return HexTool.toHexString(digester.digest()).replace(" ", "").toLowerCase();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Encoding the string failed", e);
        }
    }
}
