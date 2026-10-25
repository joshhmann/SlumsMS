package net.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * An account with no character view loaded used to throw a NullPointerException from haveCharacterEntry and
 * getAccountCharacterCount. Ported from nutnnut/Cosmic aa04eabef (nutnnut), guards only.
 */
class ServerAccountViewGuardTest {
    private static final int UNKNOWN_ACCOUNT = 2_000_000_123;

    @Test
    void unknownAccountHasNoCharacterEntry() {
        assertFalse(Server.getInstance().haveCharacterEntry(UNKNOWN_ACCOUNT, 1));
    }

    @Test
    void unknownAccountHasZeroCharacters() {
        assertEquals(0, Server.getInstance().getAccountCharacterCount(UNKNOWN_ACCOUNT));
    }
}
