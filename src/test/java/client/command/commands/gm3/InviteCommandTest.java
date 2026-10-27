package client.command.commands.gm3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.site.auth.InviteCodes;
import soloMapling.site.auth.MemoryAuthStore;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 105: !invite [1d|7d|30d|forever] [note], !invite list, !invite revoke. */
class InviteCommandTest {

    static final long NOW = 1_800_000_000_000L;
    final MemoryAuthStore store = new MemoryAuthStore();

    @BeforeEach
    void setUp() {
        InviteCommand.useStoreForTest(store);
    }

    @AfterEach
    void tearDown() {
        InviteCommand.useStoreForTest(null);
    }

    InviteCodes.Invite only() {
        assertEquals(1, store.invites.size());
        return store.invites.values().iterator().next();
    }

    @Test
    void defaultIsSevenDaysAndTheRestIsTheNote() {
        List<String> out = InviteCommand.run(new String[]{"for", "bob"}, 1, "Gm", NOW);
        InviteCodes.Invite i = only();
        assertEquals(NOW + TimeUnit.DAYS.toMillis(7), i.expiresAt());
        assertEquals("for bob", i.note());
        assertEquals(1, i.createdByAccount());
        assertTrue(out.get(0).contains(InviteCodes.display(i.code())) && out.get(0).contains("valid 7d"), out.get(0));
    }

    @Test
    void durationsOneDayThirtyDaysAndForever() {
        InviteCommand.run(new String[]{"1d"}, 1, "Gm", NOW);
        assertEquals(NOW + TimeUnit.DAYS.toMillis(1), only().expiresAt());
        store.invites.clear();
        InviteCommand.run(new String[]{"30d", "alt"}, 1, "Gm", NOW);
        assertEquals(NOW + TimeUnit.DAYS.toMillis(30), only().expiresAt());
        assertEquals("alt", only().note());
        store.invites.clear();
        List<String> out = InviteCommand.run(new String[]{"forever"}, 1, "Gm", NOW);
        assertEquals(0, only().expiresAt());
        assertTrue(out.get(0).contains("never expires"));
    }

    @Test
    void listShowsExpiryAndRevokeCancelsAnUnusedCode() {
        InviteCommand.run(new String[]{"1d", "pal"}, 1, "Gm", NOW);
        String code = only().code();
        List<String> list = InviteCommand.run(new String[]{"list"}, 1, "Gm", NOW);
        assertEquals(1, list.size());
        assertTrue(list.get(0).contains("open, expires"), list.get(0));

        List<String> revoked = InviteCommand.run(new String[]{"revoke", InviteCodes.display(code).toLowerCase()}, 1, "Gm", NOW);
        assertEquals("Revoked " + InviteCodes.display(code) + ".", revoked.get(0));
        assertEquals(InviteCodes.Status.REVOKED, only().status(NOW));
        assertTrue(InviteCommand.run(new String[]{"revoke", code}, 1, "Gm", NOW).get(0).startsWith("No unused invite"));
        assertTrue(InviteCommand.run(new String[]{"revoke"}, 1, "Gm", NOW).get(0).startsWith("Syntax"));
        assertTrue(InviteCommand.run(new String[]{"list"}, 1, "Gm", NOW).get(0).contains("revoked"));
    }

    @Test
    void anEmptyListSaysSo() {
        assertEquals(List.of("No invite codes yet."), InviteCommand.run(new String[]{"list"}, 1, "Gm", NOW));
    }
}
