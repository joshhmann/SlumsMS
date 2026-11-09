package client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * legacy-sha-multibyte: {@code AccountPasswords.digest} fed {@code password.length()} (a CHARACTER count) to
 * {@code MessageDigest.update} as the number of BYTES, so a password with non-ASCII letters was hashed only in part.
 * New hashes cover every byte; a legacy check still accepts the old partial value, because every SHA-1/SHA-512 value
 * of a non-ASCII password on the servers was made that way and those players log in with it today.
 */
class AccountPasswordsMultibyteTest {

    /** The password used throughout: 8 characters, 10 UTF-8 bytes (ä and ö are two bytes each). */
    private static final String PASSWORD = "pässwörd";

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** The digest of every byte: what a hash made today must be. */
    static String full(String type, String pwd) throws NoSuchAlgorithmException {
        return hex(MessageDigest.getInstance(type).digest(pwd.getBytes(StandardCharsets.UTF_8)));
    }

    /** The digest the buggy code made: the first {@code pwd.length()} (characters, used as bytes) of the UTF-8 form. */
    static String legacy(String type, String pwd) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(type);
        byte[] bytes = pwd.getBytes(StandardCharsets.UTF_8);
        md.update(bytes, 0, pwd.length());
        return hex(md.digest());
    }

    /** The digest of the first {@code n} bytes of the UTF-8 form (what the old code fed the digester). */
    static String prefix(String type, String pwd, int n) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(type);
        md.update(pwd.getBytes(StandardCharsets.UTF_8), 0, n);
        return hex(md.digest());
    }

    /** The bug is real for this password: the two computations are not the same. */
    @Test
    void theOldComputationCoveredOnlyPartOfANonAsciiPassword() throws Exception {
        assertEquals(8, PASSWORD.length(), "8 characters");
        assertEquals(10, PASSWORD.getBytes(StandardCharsets.UTF_8).length, "10 bytes");
        assertEquals(prefix("SHA-512", PASSWORD, 8), legacy("SHA-512", PASSWORD),
                "the old digest hashed the first 8 bytes only");
    }

    /** (a) the no-loss guard: a stored value made the old way still logs the player in. */
    @Test
    void aValueMadeTheOldWayStillMatches() throws Exception {
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check(PASSWORD, legacy("SHA-512", PASSWORD)),
                "every non-ASCII SHA-512 account on the servers was stored this way");
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check(PASSWORD, legacy("SHA-1", PASSWORD)));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.checkHashedOnly(PASSWORD, legacy("SHA-512", PASSWORD)),
                "the site's check reads the same stored values");
    }

    /** (b) a value made from all the bytes matches, and the hash written for a new account uses all the bytes. */
    @Test
    void everyByteOfThePasswordCounts() throws Exception {
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check(PASSWORD, full("SHA-512", PASSWORD)));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check(PASSWORD, full("SHA-1", PASSWORD)));
        assertEquals(full("SHA-512", PASSWORD), AccountPasswords.sha512(PASSWORD),
                "a legacy hash made today covers every byte");
    }

    /**
     * (c) the old partial value is a hash of the password's byte PREFIX, so it matches a password that shares that
     * prefix exactly and nothing else: {@code pässwöX} (7 characters) truncates at 7 bytes, inside ö, and must not
     * match. (A 9-character prefix on the same bytes does match by construction - that ambiguity is what "the old
     * values keep working" buys, and it is the only way to keep those accounts able to log in.)
     */
    @Test
    void aDifferentPasswordDoesNotRideOnTheOldValue() throws Exception {
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("pässwöX", legacy("SHA-512", PASSWORD)));
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("pässwöX", legacy("SHA-1", PASSWORD)));
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("passwörd", legacy("SHA-512", PASSWORD)));
    }

    /** (d) ASCII passwords are untouched: full and old digests are the same value. */
    @Test
    void asciiPasswordsAreUnchanged() throws Exception {
        assertEquals(full("SHA-512", "hunter22"), legacy("SHA-512", "hunter22"), "the two computations agree on ASCII");
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", full("SHA-512", "hunter22")));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", legacy("SHA-1", "hunter22")));
        assertEquals(full("SHA-512", "hunter22"), AccountPasswords.sha512("hunter22"));
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("hunter23", legacy("SHA-512", "hunter22")));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", "hunter22"),
                "plain-text legacy values are still taken on the login screen");
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.checkHashedOnly("hunter22", "hunter22"));
    }
}
