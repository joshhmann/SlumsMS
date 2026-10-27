package client;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Ticket 105: the login screen's password rules, shared with the site; the site's check never takes plain text. */
class AccountPasswordsTest {

    static String hex(String type, String pwd) throws Exception {
        byte[] d = MessageDigest.getInstance(type).digest(pwd.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (byte b : d) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void bcryptAndLegacyHashesMatchAsOnTheLoginScreen() throws Exception {
        assertEquals(AccountPasswords.Match.BCRYPT, AccountPasswords.check("hunter22", AccountPasswords.bcrypt("hunter22")));
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("hunter23", AccountPasswords.bcrypt("hunter22")));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", hex("SHA-512", "hunter22")));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", hex("SHA-1", "hunter22")));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.check("hunter22", "hunter22"), "the login screen still takes plain text");
        assertEquals(hex("SHA-512", "hunter22"), AccountPasswords.sha512("hunter22"));
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("x", "$2"));
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.check("x", ""));
    }

    @Test
    void theSiteCheckNeverTakesPlainText() throws Exception {
        assertEquals(AccountPasswords.Match.NONE, AccountPasswords.checkHashedOnly("!bot-template-no-login", "!bot-template-no-login"));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.checkHashedOnly("hunter22", hex("SHA-512", "hunter22")));
        assertEquals(AccountPasswords.Match.LEGACY, AccountPasswords.checkHashedOnly("hunter22", hex("SHA-1", "hunter22")));
        assertEquals(AccountPasswords.Match.BCRYPT, AccountPasswords.checkHashedOnly("hunter22", AccountPasswords.bcrypt("hunter22")));
    }
}
