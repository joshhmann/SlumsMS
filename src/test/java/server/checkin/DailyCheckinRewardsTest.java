package server.checkin;

import client.BotClient;
import client.Character;
import com.esotericsoftware.yamlbeans.YamlReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import server.CashShop;
import server.checkin.DailyCheckinRewards.Grant;
import server.checkin.DailyCheckinRewards.Reward;

import java.io.StringReader;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DailyCheckinRewardsTest {

    private static final long DAY = DailyCheckinStreak.PERIOD_MS;
    private static final long T0 = 1_800_000_000_000L;
    private static final int ACCOUNT = 7;

    /** Records deliveries; {@code room} answers canHold. */
    private static final class FakeHands implements DailyCheckinRewards.Hands {
        final List<Grant> given = new ArrayList<>();
        final List<Long> expirations = new ArrayList<>();
        boolean room = true;

        @Override
        public boolean canHold(Character chr, List<Grant> grants) {
            return room;
        }

        @Override
        public void give(Character chr, Grant grant, long expiration) {
            given.add(grant);
            expirations.add(expiration);
        }
    }

    private final FakeHands hands = new FakeHands();
    private final List<DailyCheckinStreak> saved = new ArrayList<>();

    private void use(List<Reward> table) {
        DailyCheckinRewards.useForTest(hands, table);
        DailyCheckin.useForTest(new DailyCheckin.Store() {
            @Override
            public DailyCheckinStreak load(int accountId) {
                return DailyCheckinStreak.NEW;
            }

            @Override
            public void save(int accountId, DailyCheckinStreak streak) throws SQLException {
                saved.add(streak);
            }
        });
    }

    private static Character player(int id, int slotsInTab) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn("Claimer");
        when(chr.canHoldMeso(anyInt())).thenReturn(true);
        when(chr.getSlots(anyInt())).thenReturn((byte) slotsInTab);
        when(chr.getCashShop()).thenReturn(mock(CashShop.class));
        return chr;
    }

    private static List<Reward> tableWith(int day, Reward r) {
        List<Reward> t = new ArrayList<>(DailyCheckinRewards.DEFAULT_DAYS);
        t.set(day - 1, r);
        return t;
    }

    @AfterEach
    void tearDown() {
        DailyCheckinRewards.useForTest(null, null);
        DailyCheckin.useForTest(null);
    }

    @Test
    void aDayWithoutNxStillGrantsItsItemsAndNeverSays0Nx() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 24);

        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(chr, ACCOUNT, DailyCheckinStreak.NEW, 1, T0));

        Reward day1 = DailyCheckinRewards.of(1);
        assertEquals(0, day1.nxCredit());
        verify(chr.getCashShop(), never()).gainCash(anyInt(), anyInt());
        assertEquals(day1.grants(), hands.given);
        assertEquals(List.of(-1L), hands.expirations, "permanent items get no expiration");
        verify(chr, never()).gainMeso(anyInt(), anyBoolean());
        verify(chr, never()).gainSlots(anyInt(), anyInt(), anyBoolean());
        verify(chr).dropMessage(6, "Daily Check-In: day 1 claimed, today's items are in your inventory.");
        verify(chr, never()).dropMessage(anyInt(), contains("NX"));
        verify(chr).saveCharToDB();
    }

    @Test
    void aMilestoneClaimGrantsNxCreditAndTheItems() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 24);

        DailyCheckinRewards.grant(chr, 14);

        verify(chr.getCashShop()).gainCash(1, 1_000);   // cash type 1 = NX Credit
        verify(chr).dropMessage(6, "Daily Check-In: you received 1,000 NX Credit.");
        assertEquals(DailyCheckinRewards.of(14).grants(), hands.given);
        verify(chr).gainSlots(DailyCheckinRewards.USE, 4, true);
    }

    @Test
    void timedItemsExpireTheirDaysAfterTheClaim() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 24);
        long before = System.currentTimeMillis();

        DailyCheckinRewards.grant(chr, 18);   // the 1-day 2x EXP coupon

        assertEquals(DailyCheckinRewards.EXP_COUPON_2X, hands.given.get(0).itemId());
        long exp = hands.expirations.get(0);
        assertTrue(exp >= before + DAY && exp <= System.currentTimeMillis() + DAY);
    }

    @Test
    void slotsAreAddedBelowTheCap() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 24);

        DailyCheckinRewards.grant(chr, 28);   // +8 Etc

        verify(chr).gainSlots(DailyCheckinRewards.ETC, 8, true);
        verify(chr.getCashShop()).gainCash(1, 1_000);
    }

    @Test
    void slotsStopAt96AndTheRestBecomesNx() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 92);

        DailyCheckinRewards.grant(chr, 28);   // +8 Etc with room for 4

        verify(chr).gainSlots(DailyCheckinRewards.ETC, 4, true);
        verify(chr.getCashShop()).gainCash(1, 1_000 + 4 * DailyCheckinRewards.NX_PER_MISSING_SLOT);
        verify(chr).dropMessage(eq(6), contains("96-slot maximum, so 4 slots became 2,000 NX"));
    }

    @Test
    void aFullTabGetsNoSlotsOnlyNx() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 96);

        DailyCheckinRewards.grant(chr, 7);   // +4 Equip

        verify(chr, never()).gainSlots(anyInt(), anyInt(), anyBoolean());
        verify(chr.getCashShop()).gainCash(1, 1_000 + 4 * DailyCheckinRewards.NX_PER_MISSING_SLOT);
    }

    @Test
    void noRoomForTheItemsKeepsTheDay() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        hands.room = false;
        Character chr = player(1, 24);

        assertEquals(DailyCheckin.ClaimResult.NO_ROOM, DailyCheckin.claim(chr, ACCOUNT, DailyCheckinStreak.NEW, 1, T0));
        assertTrue(saved.isEmpty());
        verify(chr.getCashShop(), never()).gainCash(anyInt(), anyInt());
    }

    @Test
    void mesosStillWork() {
        use(tableWith(1, new Reward(DailyCheckinRewards.POWER_ELIXIR, 0, 50_000, List.of(), 0, 0)));
        Character chr = player(1, 24);

        DailyCheckinRewards.grant(chr, 1);

        verify(chr).gainMeso(50_000, true);
        verify(chr.getCashShop(), never()).gainCash(anyInt(), anyInt());
    }

    @Test
    void botsNeverClaimOrGetAnything() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character artificial = player(1_000_000_000, 24);
        Character aboveBase = player(1_000_000_123, 24);
        Character console = player(999_999_999, 24);
        Character botClient = player(5, 24);
        when(botClient.getClient()).thenReturn(mock(BotClient.class));

        for (Character bot : List.of(artificial, aboveBase, console, botClient)) {
            assertFalse(DailyCheckin.isPlayer(bot));
            assertFalse(DailyCheckin.eligible(bot), "no login push, window or request handling");
            assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE,
                    DailyCheckin.claim(bot, ACCOUNT, DailyCheckinStreak.NEW, 1, T0));
            verify(bot.getCashShop(), never()).gainCash(anyInt(), anyInt());
        }
        assertTrue(saved.isEmpty());
        assertTrue(hands.given.isEmpty());
        assertTrue(DailyCheckin.isPlayer(player(1, 24)));
    }

    @Test
    void aStreakLapsedOver48HoursRestartsAtDayOneAndPaysDayOne() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 24);
        DailyCheckinStreak s = DailyCheckinStreak.NEW.claimed(1, T0).claimed(2, T0 + DAY).claimed(3, T0 + 2 * DAY);

        long late = T0 + 2 * DAY + 2 * DAY;   // 48h after the last claim
        assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE, DailyCheckin.claim(chr, ACCOUNT, s, 4, late));
        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(chr, ACCOUNT, s, 1, late));

        assertEquals(1, saved.get(0).day());
        assertEquals(0b1, saved.get(0).claimedMask(), "the old cycle's claims are gone");
        assertEquals(DailyCheckinRewards.of(1).grants(), hands.given, "day one's items again");
    }

    @Test
    void afterDay28TheCycleWrapsToDayOne() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        Character chr = player(1, 24);
        DailyCheckinStreak s = DailyCheckinStreak.NEW;
        long t = T0;
        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++, t += DAY) {
            assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(chr, ACCOUNT, s, d, t));
            s = s.claimed(d, t);
        }
        assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE, DailyCheckin.claim(chr, ACCOUNT, s, 29, t));
        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(chr, ACCOUNT, s, 1, t));
        assertEquals(0b1, saved.get(saved.size() - 1).claimedMask());
    }

    @Test
    void theTooltipLeadsWithNx() {
        Reward r = DailyCheckinRewards.DEFAULT_DAYS.get(6);
        String tip = DailyCheckinRewards.tooltip(7, r, id -> id == DailyCheckinRewards.EXP_COUPON_2X
                ? "1day 2x EXP Special Coupon " : null);

        assertEquals("Day 7\n- 1,000 NX\n- 6 Slums Stamps\n- 1day 2x EXP Special Coupon (1 day)\n- +4 Equip slots", tip);
    }

    @Test
    void aDayWithoutNxHasNoNxLineInItsTooltip() {
        assertEquals("Day 3\n- 1 Slums Stamp\n- Item 2000004 x50",
                DailyCheckinRewards.tooltip(3, DailyCheckinRewards.DEFAULT_DAYS.get(2), id -> null));
        for (int d = 1; d <= DailyCheckinRewards.DEFAULT_DAYS.size(); d++) {
            String tip = DailyCheckinRewards.tooltip(d, DailyCheckinRewards.DEFAULT_DAYS.get(d - 1), id -> "Thing");
            assertEquals(d % 7 == 0, tip.contains("NX"), "day " + d + ": " + tip);
            assertFalse(tip.contains("- 0 NX"), "no \"0 NX\" line, day " + d);
        }
    }

    @Test
    void theTableHasItemsEveryDayAndNxOnlyOnTheWeekDays() {
        List<Reward> t = DailyCheckinRewards.DEFAULT_DAYS;
        assertEquals(DailyCheckinStreak.CYCLE_DAYS, t.size());
        int nx = 0;
        int slots = 0;
        for (int d = 1; d <= t.size(); d++) {
            Reward r = t.get(d - 1);
            boolean milestone = d % 7 == 0;
            assertEquals(milestone ? 1_000 : 0, r.nxCredit(), "1,000 NX on the week days only, day " + d);
            assertFalse(r.grants().isEmpty(), "an item every day, day " + d);
            assertEquals(milestone, r.slotCount() > 0, "slots exactly on the weekly milestones, day " + d);
            nx += r.nxCredit();
            slots += r.slotCount();
        }
        assertEquals(4_000, nx);
        assertEquals(20, slots);
    }

    @Test
    void aFullCycleHandsOutExactly4000Nx() {
        use(DailyCheckinRewards.DEFAULT_DAYS);
        CashShop cash = mock(CashShop.class);
        Character chr = player(1, 24);
        when(chr.getCashShop()).thenReturn(cash);
        int[] total = {0};
        org.mockito.Mockito.doAnswer(inv -> {
            total[0] += (Integer) inv.getArgument(1);
            return null;
        }).when(cash).gainCash(eq(1), anyInt());

        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++) {
            DailyCheckinRewards.grant(chr, d);
        }

        assertEquals(4_000, total[0]);
        verify(cash, org.mockito.Mockito.times(4)).gainCash(eq(1), anyInt());
    }

    @Test
    void theShippedYamlIsTheBuiltInTable() throws Exception {
        List<Reward> fromYaml = DailyCheckinRewardsYaml.read(Path.of(DailyCheckinRewards.DATA_PATH), id -> true);
        assertEquals(DailyCheckinRewards.DEFAULT_DAYS, fromYaml);
    }

    @Test
    void theYamlIsStrict() {
        String oneDay = "days:\n  - day: 1\n    nx: 1,500\n    slots: etc 4\n    items:\n      - id: 2000005\n        qty: 2\n";
        assertThrows(IllegalArgumentException.class, () -> parse(oneDay, id -> true), "all 28 days are required");

        StringBuilder full = new StringBuilder("days:\n");
        for (int d = 1; d <= 28; d++) {
            full.append("  - day: ").append(d).append("\n    nx: 1,500\n    items:\n      - id: 2000005\n");
        }
        List<Reward> ok = parse(full.toString(), id -> true);
        assertEquals(1_500, ok.get(0).nxCredit());
        assertEquals(2000005, ok.get(27).iconItemId(), "icon defaults to the first item");
        assertEquals(1, ok.get(27).grants().get(0).qty());

        assertThrows(IllegalArgumentException.class, () -> parse(full.toString(), id -> id != 2000005),
                "an item the server does not know rejects the file");
        assertThrows(IllegalArgumentException.class,
                () -> parse(full.toString().replaceFirst("nx:", "nxx:"), id -> true), "unknown key");
        assertThrows(IllegalArgumentException.class,
                () -> parse(full.toString().replaceFirst("day: 2\n", "day: 3\n"), id -> true), "days out of order");
        assertThrows(IllegalArgumentException.class,
                () -> parse(full.toString().replaceFirst("nx: 1,500", "nx: 1,500\n    slots: cash 4"), id -> true));
    }

    @Test
    void migration004IsLedgerGatedAndIdempotent() throws Exception {
        String apply = java.nio.file.Files.readString(Path.of("database/migrations/004-daily-checkin.sql"));
        String rollback = java.nio.file.Files.readString(Path.of("database/migrations/004-daily-checkin.rollback.sql"));
        assertTrue(apply.contains("CREATE TABLE IF NOT EXISTS daily_checkin"));
        assertTrue(apply.contains("INSERT IGNORE INTO mapleroot_integration_migrations(migration_id) VALUES ('004-daily-checkin')"));
        assertFalse(apply.toUpperCase().contains("ALTER TABLE"), "touches no existing table");
        assertTrue(rollback.contains("DROP TABLE IF EXISTS daily_checkin"));
        assertTrue(rollback.contains("migration_id='004-daily-checkin'"));
    }

    private static List<Reward> parse(String yaml, java.util.function.IntPredicate known) {
        try {
            return DailyCheckinRewardsYaml.parse(new YamlReader(new StringReader(yaml)).read(), known);
        } catch (com.esotericsoftware.yamlbeans.YamlException e) {
            throw new IllegalArgumentException(e);
        }
    }
}
