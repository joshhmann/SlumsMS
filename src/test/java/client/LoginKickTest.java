package client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 1.5.0 review: Client.login answered 7 ("already logged in") before checking the password, and LoginPasswordHandler
 * kicks the live session on 7, so a wrong password was enough to disconnect any online account.
 */
class LoginKickTest {

    @Test
    void aWrongPasswordOnAnOnlineAccountIsJustAWrongPassword() {
        assertEquals(4, Client.alreadyLoggedInCheck(4, () -> Client.LOGIN_LOGGEDIN));
        assertEquals(4, Client.alreadyLoggedInCheck(4, () -> {
            throw new AssertionError("no login-state lookup for a wrong password");
        }));
    }

    @Test
    void theRightPasswordOnAnOnlineAccountIsSeven() {
        assertEquals(7, Client.alreadyLoggedInCheck(0, () -> Client.LOGIN_LOGGEDIN));
        assertEquals(7, Client.alreadyLoggedInCheck(-10, () -> Client.LOGIN_SERVER_TRANSITION));
        assertEquals(7, Client.alreadyLoggedInCheck(23, () -> Client.LOGIN_LOGGEDIN));
    }

    @Test
    void theRightPasswordOnAnOfflineAccountKeepsItsResult() {
        assertEquals(0, Client.alreadyLoggedInCheck(0, () -> Client.LOGIN_NOTLOGGEDIN));
        assertEquals(23, Client.alreadyLoggedInCheck(23, () -> Client.LOGIN_NOTLOGGEDIN));
        assertEquals(-10, Client.alreadyLoggedInCheck(-10, () -> Client.LOGIN_NOTLOGGEDIN));
    }
}
