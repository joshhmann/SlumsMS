package client.command.commands.gm3;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.site.auth.AuthStore;
import soloMapling.site.auth.InviteCodes;
import soloMapling.site.auth.MemoryAuthStore;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Ticket 105: !invite [1d|7d|30d|forever] [note], !invite list, !invite revoke. */
class InviteCommandTest {

    static final long NOW = 1_800_000_000_000L;
    static final long DAY = TimeUnit.DAYS.toMillis(1);
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

    // ---- @invite (players, 1.6.1) ----

    static final long WEEK = TimeUnit.DAYS.toMillis(7);

    List<String> player(long now, String... params) {
        return InviteCommand.runPlayer(params, 7, 70, "Pal", 30, now);
    }

    @Test
    void aLevel30CharacterMakesAOneUseNeverExpiringCodeRecordingAccountAndCharacter() {
        List<String> out = player(NOW);
        InviteCodes.Invite i = only();
        assertEquals(0, i.expiresAt());
        assertEquals(7, i.createdByAccount());
        assertEquals(70, i.createdByChar());
        assertEquals("Pal", i.createdByName());
        assertTrue(i.selfServe());
        assertTrue(out.get(0).contains(InviteCodes.display(i.code())) && out.get(0).contains("never expires"), out.get(0));
        assertTrue(out.get(1).contains("1 left"), out.get(1));
    }

    @Test
    void underLevel30IsRefusedUnlessAnotherCharacterOnTheAccountIsHighEnough() {
        List<String> out = InviteCommand.runPlayer(new String[0], 7, 70, "Pal", 29, NOW);
        assertTrue(out.get(0).contains("level 30"), out.get(0));
        assertTrue(store.invites.isEmpty());
        store.levels.put(7, 45);   // an alt on the account at 45
        InviteCommand.runPlayer(new String[0], 7, 70, "Pal", 29, NOW);
        assertEquals(1, store.invites.size());
        assertEquals(30, InviteCodes.PLAYER_MIN_LEVEL);
    }

    @Test
    void twoCodesPerRollingWeekThenRefusedPerAccount() {
        player(NOW);
        player(NOW + 1000);
        assertEquals(2, store.invites.size());
        List<String> third = player(NOW + 2000);
        assertTrue(third.get(0).contains("2 invite codes in the last 7 days"), third.get(0));
        assertEquals(2, store.invites.size());
        // Another account has its own allowance.
        InviteCommand.runPlayer(new String[0], 8, 80, "Other", 40, NOW + 2000);
        assertEquals(3, store.invites.size());
    }

    @Test
    void theWeekRollsFromEachCodeNotFromAFixedDay() {
        player(NOW);
        player(NOW + 3 * DAY);
        assertEquals(2, store.invites.size());
        assertTrue(player(NOW + 6 * DAY).get(0).contains("last 7 days"), "both still inside the week");
        assertEquals(2, store.invites.size());
        // 7 days after the first one only that code has rolled off: one slot is free, not two.
        player(NOW + WEEK);
        assertEquals(3, store.invites.size());
        assertTrue(player(NOW + WEEK + 1).get(0).contains("last 7 days"));
        assertEquals(3, store.invites.size());
        // A cancelled code still counts toward the week.
        store.invites.clear();
        player(NOW);
        String code = only().code();
        store.revokeInvite(code, "Gm", NOW);
        player(NOW + 1);
        assertTrue(player(NOW + 2).get(0).contains("last 7 days"));
    }

    @Test
    void gmCodesAreUnlimitedAndDoNotUseUpAPlayersWeek() {
        for (int n = 0; n < 5; n++) {
            InviteCommand.run(new String[]{"forever"}, 7, 70, "Pal", NOW + n);
        }
        assertEquals(5, store.invites.size());
        assertTrue(store.invites.values().stream().noneMatch(InviteCodes.Invite::selfServe));
        assertTrue(store.invites.values().stream().allMatch(i -> i.createdByChar() == 70));
        // The same account as a player still has both weekly codes.
        player(NOW + 10);
        player(NOW + 11);
        assertEquals(7, store.invites.size());
    }

    @Test
    void listShowsOnlyYourOwnCodesAsUsedOrUnusedNeverWho() {
        player(NOW);
        player(NOW + 1000);
        InviteCommand.runPlayer(new String[0], 8, 80, "Other", 40, NOW);
        String used = store.invitesMadeBy(7, 5).get(1).code();
        store.createAccountWithInvite(used, "Newbie", "x", "iph", NOW + 2000);
        List<String> list = player(NOW + 3000, "list");
        assertEquals(2, list.size());
        assertTrue(list.stream().anyMatch(l -> l.equals(InviteCodes.display(used) + "  used")), list.toString());
        assertTrue(list.stream().anyMatch(l -> l.endsWith("  unused")), list.toString());
        assertTrue(list.stream().noneMatch(l -> l.contains("Newbie") || l.contains("Other")), list.toString());
        assertEquals(List.of("You haven't made any invite codes. @invite makes one."),
                InviteCommand.runPlayer(new String[]{"list"}, 99, 1, "Nobody", 50, NOW));
    }

    @Test
    void aCodeIsOneUseAndTheNewAccountRecordsWhoInvitedIt() {
        player(NOW);
        String code = only().code();
        int id = store.createAccountWithInvite(code, "Friend", "h", "iph", NOW + 1);
        assertTrue(id > 0);
        assertArrayEquals(new int[]{7, 70}, store.invitedBy.get(id));
        assertEquals(AuthStore.CODE_GONE, store.createAccountWithInvite(code, "Second", "h", "iph", NOW + 2));
        assertNull(store.invitedBy.get(id + 1));
    }

    @Test
    void unknownWordsGetSyntaxHelp() {
        assertTrue(player(NOW, "forever").get(0).startsWith("Syntax"));
        assertTrue(store.invites.isEmpty());
    }
}
