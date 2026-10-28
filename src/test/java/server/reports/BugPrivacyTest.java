package server.reports;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BugPrivacyTest {

    @Test
    void sensitiveServerLinesAreDropped() {
        for (String s : List.of(
                "LoginPasswordHandler - Wrong password for Bob",
                "Account Bob tried PIN 1234",
                "PIC mismatch for chr Bob",
                "Chr Bob connected from 192.168.0.44",
                "session from 2001:db8:85a3:0:0:8a2e:370:7334 for Bob",
                "reset mail sent to bob@example.com",
                "GM audit: Bob (account bobby, gm 6) ok !warp",
                "token=abc for Bob",
                "Bob whispers to Alice: hey",
                "Bob hash 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "remote address of Bob changed")) {
            assertFalse(BugPrivacy.serverLineAllowed(s), s);
        }
    }

    @Test
    void ordinaryErrorsPass() {
        for (String s : List.of(
                "NPC Bob (9000) is not coded",
                "Chr Bob logged out mid-warp to map 100000000: unwinding its map entry",
                "Could not apply skill 1001004 for chr 123 [java.util.ConcurrentModificationException: HashMap]",
                "Wrong slot number in shop 1012004 for Bob",
                "Bob account 42 has no storage row")) {
            assertTrue(BugPrivacy.serverLineAllowed(s), s);
        }
        assertFalse(BugPrivacy.serverLineAllowed(null));
    }

    @Test
    void clientTextIsRedactedNotDropped() {
        String in = String.join("\n",
                "[crash]",
                "Exception 0xC0000005 at slums.dll+0x1A2B3C",
                "Module C:\\Users\\JoshDesktop\\Games\\SlumsMS\\slums.dll",
                "COMPUTERNAME=SILVIA-PC",
                "UserName: josh",
                "connected to 192.168.0.164:8485",
                "contact me@mail.com",
                "MAC 00:1A:2B:3C:4D:5E",
                "login password typed: hunter2",
                "account myaccount logged in",
                "slums.dll sha256 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
                "\u0007bell");
        String out = BugPrivacy.scrubClientText(in, "myaccount");
        assertTrue(out.contains("C:\\Users\\<user>\\Games\\SlumsMS\\slums.dll"), out);
        assertFalse(out.contains("JoshDesktop"));
        assertFalse(out.contains("SILVIA-PC"));
        assertFalse(out.contains("josh\n") || out.endsWith("josh"));
        assertTrue(out.contains("connected to <ip>"), out);
        assertTrue(out.contains("contact <email>"));
        assertTrue(out.contains("MAC <mac>"));
        assertFalse(out.contains("hunter2"));
        assertTrue(out.contains("[line removed]"));
        assertTrue(out.contains("account <account> logged in"), out);
        assertTrue(out.contains("9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"), "the dll hash stays");
        assertTrue(out.contains("slums.dll+0x1A2B3C"));
        assertTrue(out.contains("bell") && !out.contains("\u0007"));
        assertEquals("", BugPrivacy.scrubClientText(null, null));
    }
}
