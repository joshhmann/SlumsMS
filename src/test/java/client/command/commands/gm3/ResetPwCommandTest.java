package client.command.commands.gm3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.site.auth.MemoryResetStore;
import soloMapling.site.auth.ResetStore;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 107: !resetpw list, !resetpw approve <account>, !resetpw deny <account>. */
class ResetPwCommandTest {

    static final long NOW = 1_800_000_000_000L;
    final MemoryResetStore store = new MemoryResetStore();

    @BeforeEach
    void setUp() {
        ResetPwCommand.useStoreForTest(store);
        store.insertReplacing(ResetStore.Request.fresh("a".repeat(32), 7, "Forgetful", "h".repeat(64), "bob#1234",
                "abcdef123456", NOW));
    }

    @AfterEach
    void tearDown() {
        ResetPwCommand.useStoreForTest(null);
    }

    @Test
    void listShowsThePendingRequests() {
        List<String> out = ResetPwCommand.run(new String[]{"LIST"}, "Gm", NOW + 1000);
        assertEquals(1, out.size());
        assertTrue(out.get(0).startsWith("Forgetful") && out.get(0).contains("bob#1234") && out.get(0).contains("abcdef"),
                out.get(0));
        assertFalse(out.get(0).contains("abcdef1"), "only the first characters of the ipHash");
        assertFalse(out.get(0).contains("hhhh"), "never the secret hash");
    }

    @Test
    void approveAndDenyNameTheAccountAndRecordTheGm() {
        assertTrue(ResetPwCommand.run(new String[]{"approve", "forgetful"}, "Gm", NOW).get(0).startsWith("Approved"));
        assertEquals(ResetStore.APPROVED, store.find("a".repeat(32)).state());
        assertEquals("Gm", store.find("a".repeat(32)).decidedBy());
        assertTrue(ResetPwCommand.run(new String[]{"deny", "Forgetful"}, "Gm2", NOW).get(0).startsWith("Denied"));
        assertEquals("Gm2", store.find("a".repeat(32)).decidedBy());
        assertTrue(ResetPwCommand.run(new String[]{"approve", "Forgetful"}, "Gm", NOW).get(0).startsWith("No pending"));
        assertTrue(ResetPwCommand.run(new String[]{"deny", "Someoneelse"}, "Gm", NOW).get(0).startsWith("No open"));
    }

    @Test
    void badUseShowsTheSyntax() {
        assertEquals(ResetPwCommand.SYNTAX, ResetPwCommand.run(new String[0], "Gm", NOW).get(0));
        assertEquals(ResetPwCommand.SYNTAX, ResetPwCommand.run(new String[]{"reset", "Forgetful"}, "Gm", NOW).get(0));
        assertTrue(ResetPwCommand.run(new String[]{"approve"}, "Gm", NOW).get(0).startsWith("Syntax: !resetpw approve"));
        assertTrue(ResetPwCommand.run(new String[]{"deny"}, "Gm", NOW).get(0).startsWith("Syntax: !resetpw deny"));
        assertEquals(ResetStore.PENDING, store.find("a".repeat(32)).state());
    }

    @Test
    void aMissingTableSaysSoInsteadOfThrowing() {
        ResetPwCommand.useStoreForTest(new MemoryResetStore() {
            @Override
            public synchronized List<Request> withState(String state, long since) {
                throw new IllegalStateException("no table");
            }
        });
        assertTrue(ResetPwCommand.run(new String[]{"list"}, "Gm", NOW).get(0).contains("migration 037"));
    }
}
