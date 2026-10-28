package server.checkin;

import client.BotClient;
import client.Character;
import client.Client;
import client.SyntheticClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.CashShop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ticket 111: Slums Stamps, earned in the Daily Check-In and spent at Rooty. */
class SlumsStampsTest {

    private static final long DAY = DailyCheckinStreak.PERIOD_MS;
    private static final long T0 = 1_800_000_000_000L;
    private static final int ACCOUNT = 77;

    /** Balances per account, with the same conditional spend as the database. */
    static final class FakeBalances implements SlumsStamps.Store {
        final Map<Integer, AtomicInteger> bal = new java.util.concurrent.ConcurrentHashMap<>();

        AtomicInteger of(int acc) {
            return bal.computeIfAbsent(acc, k -> new AtomicInteger());
        }

        @Override
        public int balance(int accountId) {
            return of(accountId).get();
        }

        @Override
        public void add(int accountId, int n) {
            of(accountId).addAndGet(n);
        }

        @Override
        public boolean spend(int accountId, int n) {
            AtomicInteger b = of(accountId);
            while (true) {
                int v = b.get();
                if (v < n) {
                    return false;
                }
                if (b.compareAndSet(v, v - n)) {
                    return true;
                }
            }
        }
    }

    /**
     * One account's stored streak, shared by every channel: a claim is written only if the streak is still the one
     * the claimer read (the database store's conditional update), and the stamps land with it.
     */
    static final class SharedStreak implements DailyCheckin.Store {
        DailyCheckinStreak stored = DailyCheckinStreak.NEW;
        final FakeBalances balances;

        SharedStreak(FakeBalances balances) {
            this.balances = balances;
        }

        @Override
        public synchronized DailyCheckinStreak load(int accountId) {
            return stored;
        }

        @Override
        public synchronized void save(int accountId, DailyCheckinStreak streak) {
            stored = streak;
        }

        @Override
        public synchronized int recordClaim(int accountId, DailyCheckinStreak before, DailyCheckinStreak after,
                                            int stamps, boolean skipStaffCheck) {
            if (!stored.equals(before)) {
                return -1;
            }
            stored = after;
            balances.add(accountId, stamps);
            return stamps;
        }
    }

    private final FakeBalances balances = new FakeBalances();
    private final List<DailyCheckinRewards.Grant> given = new ArrayList<>();

    @BeforeEach
    void setUp() {
        SlumsStamps.useForTest(balances);
        DailyCheckinRewards.useForTest(new DailyCheckinRewards.Hands() {
            @Override
            public boolean canHold(Character chr, List<DailyCheckinRewards.Grant> grants) {
                return true;
            }

            @Override
            public void give(Character chr, DailyCheckinRewards.Grant grant, long expiration) {
                given.add(grant);
            }
        }, DailyCheckinRewards.DEFAULT_DAYS);
    }

    @AfterEach
    void tearDown() {
        SlumsStamps.useForTest(null);
        DailyCheckin.useForTest(null);
        DailyCheckinRewards.useForTest(null, null);
    }

    private static Character player(int id, int gm, Client client, String name) {
        Character chr = mock(Character.class);
        when(chr.getId()).thenReturn(id);
        when(chr.getName()).thenReturn(name);
        when(chr.gmLevel()).thenReturn(gm);
        when(chr.canHoldMeso(anyInt())).thenReturn(true);
        when(chr.getSlots(anyInt())).thenReturn((byte) 24);
        when(chr.getCashShop()).thenReturn(mock(CashShop.class));
        when(chr.getClient()).thenReturn(client);
        return chr;
    }

    private static Client client(int acc, String accountName) {
        Client c = mock(Client.class);
        when(c.getAccID()).thenReturn(acc);
        when(c.getAccountName()).thenReturn(accountName);
        return c;
    }

    private Character plainPlayer() {
        return player(5, 0, client(ACCOUNT, "friend"), "Friend");
    }

    // ── the table ──

    @Test
    void everyDayPaysOneStampAndTheWeekDaysSixFortyEightACycle() {
        int total = 0;
        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++) {
            int s = DailyCheckinRewards.DEFAULT_DAYS.get(d - 1).stamps();
            assertEquals(d % 7 == 0 ? 6 : 1, s, "day " + d);
            total += s;
        }
        assertEquals(48, total);
    }

    @Test
    void theShippedYamlCarriesTheSameStamps() throws Exception {
        List<DailyCheckinRewards.Reward> yaml = DailyCheckinRewardsYaml.read(
                java.nio.file.Path.of(DailyCheckinRewards.DATA_PATH), id -> true);
        int total = 0;
        for (DailyCheckinRewards.Reward r : yaml) {
            total += r.stamps();
        }
        assertEquals(48, total);
        assertEquals(DailyCheckinRewards.DEFAULT_DAYS, yaml);
    }

    @Test
    void aFullCycleOfClaimsEarns48Stamps() {
        SharedStreak store = new SharedStreak(balances);
        DailyCheckin.useForTest(store);
        Character chr = plainPlayer();
        long t = T0;
        for (int d = 1; d <= DailyCheckinStreak.CYCLE_DAYS; d++, t += DAY) {
            assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(chr, ACCOUNT, store.stored, d, t));
        }
        assertEquals(48, balances.balance(ACCOUNT));
        verify(chr).yellowMessage("+1 Slums Stamp, day 4/28");
        verify(chr).yellowMessage("+6 Slums Stamps, day 7/28");
    }

    // ── who earns ──

    @Test
    void staffQaAndBotsNeverEarn() {
        Character gm = player(5, 1, client(ACCOUNT, "owner"), "Owner");
        Character synthetic = player(6, 0, mock(SyntheticClient.class), "Pretend");
        Character qaName = player(7, 0, client(ACCOUNT, "friend"), "autoqa1");
        Character qaAccount = player(8, 0, client(ACCOUNT, "AutoQA2"), "Someone");
        Character botClient = player(9, 0, mock(BotClient.class), "Bot");
        Character botId = player(1_000_000_123, 0, client(ACCOUNT, "x"), "Bot2");
        for (Character c : List.of(gm, synthetic, qaName, qaAccount, botClient, botId)) {
            assertFalse(SlumsStamps.earns(c), c.getName());
        }
        assertTrue(SlumsStamps.earns(plainPlayer()));
    }

    @Test
    void aGmClaimGetsTheDaysItemsButNoStamps() {
        SharedStreak store = new SharedStreak(balances);
        DailyCheckin.useForTest(store);
        Character gm = player(5, 1, client(ACCOUNT, "owner"), "Owner");

        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(gm, ACCOUNT, store.stored, 1, T0));
        assertEquals(0, balances.balance(ACCOUNT));
        assertFalse(given.isEmpty(), "the day's items still land");
        verify(gm, never()).yellowMessage(anyString());
    }

    @Test
    void theAutoQaCheckCanLetItsOwnAccountEarnWhileItRuns() {
        Character qa = player(6, 6, client(ACCOUNT, "autoqa1"), "autoqa1");
        assertFalse(SlumsStamps.earns(qa));
        SlumsStamps.qaEarnerForCheck(ACCOUNT, true);
        assertTrue(SlumsStamps.earns(qa));
        assertTrue(SlumsStamps.qaEarner(ACCOUNT));
        SlumsStamps.qaEarnerForCheck(ACCOUNT, false);
        assertFalse(SlumsStamps.earns(qa));
    }

    // ── once per claim, across channels ──

    @Test
    void twoChannelsClaimingTheSameDayPayOnce() {
        SharedStreak store = new SharedStreak(balances);
        DailyCheckin.useForTest(store);
        Character ch1 = plainPlayer();
        Character ch2 = player(6, 0, client(ACCOUNT, "friend"), "FriendAlt");   // the same account's alt

        DailyCheckinStreak readByBoth = store.load(ACCOUNT);   // both channels read the streak before either writes
        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(ch1, ACCOUNT, readByBoth, 1, T0));
        assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE, DailyCheckin.claim(ch2, ACCOUNT, readByBoth, 1, T0 + 5));
        assertEquals(1, balances.balance(ACCOUNT));
        assertEquals(DailyCheckinRewards.of(1).grants(), given, "the items once");

        // a day later, both again with a stale read
        DailyCheckinStreak stale = store.load(ACCOUNT);
        assertEquals(DailyCheckin.ClaimResult.CLAIMED, DailyCheckin.claim(ch2, ACCOUNT, stale, 2, T0 + DAY));
        assertEquals(DailyCheckin.ClaimResult.NOT_CLAIMABLE, DailyCheckin.claim(ch1, ACCOUNT, stale, 2, T0 + DAY));
        assertEquals(2, balances.balance(ACCOUNT));
    }

    @Test
    void racingClaimsOnManyThreadsPayOnce() throws Exception {
        SharedStreak store = new SharedStreak(balances);
        DailyCheckin.useForTest(store);
        DailyCheckinStreak read = store.load(ACCOUNT);
        CountDownLatch go = new CountDownLatch(1);
        AtomicInteger claimed = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Character chr = player(10 + i, 0, client(ACCOUNT, "friend"), "C" + i);
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                if (DailyCheckin.claim(chr, ACCOUNT, read, 1, T0) == DailyCheckin.ClaimResult.CLAIMED) {
                    claimed.incrementAndGet();
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(1, claimed.get());
        assertEquals(1, balances.balance(ACCOUNT));
    }

    // ── spending ──

    @Test
    void aPurchaseTakesExactlyThePriceThenGives() {
        balances.add(ACCOUNT, 7);
        List<String> order = new ArrayList<>();
        SlumsStamps.Buy r = SlumsStamps.buy(plainPlayer(), 6, () -> true, () -> {
            order.add("give at " + balances.balance(ACCOUNT));
            return true;
        });
        assertEquals(SlumsStamps.Buy.BOUGHT, r);
        assertEquals(1, balances.balance(ACCOUNT));
        assertEquals(List.of("give at 1"), order, "taken before given");
    }

    @Test
    void shortOfStampsBuysNothingAndTakesNothing() {
        balances.add(ACCOUNT, 39);
        AtomicInteger gives = new AtomicInteger();
        assertEquals(SlumsStamps.Buy.SHORT, SlumsStamps.buy(plainPlayer(), 40, () -> true, () -> {
            gives.incrementAndGet();
            return true;
        }));
        assertEquals(39, balances.balance(ACCOUNT));
        assertEquals(0, gives.get());
    }

    @Test
    void noRoomTakesNothingAndAFailedDeliveryIsRefunded() {
        balances.add(ACCOUNT, 10);
        assertEquals(SlumsStamps.Buy.NO_ROOM, SlumsStamps.buy(plainPlayer(), 6, () -> false, () -> true));
        assertEquals(10, balances.balance(ACCOUNT));
        assertEquals(SlumsStamps.Buy.NO_ROOM, SlumsStamps.buy(plainPlayer(), 6, () -> true, () -> false));
        assertEquals(10, balances.balance(ACCOUNT), "the stamps came back");
        assertEquals(SlumsStamps.Buy.NO_ROOM, SlumsStamps.buy(plainPlayer(), 6, () -> true, () -> {
            throw new IllegalStateException("inventory blew up");
        }));
        assertEquals(10, balances.balance(ACCOUNT));
    }

    @Test
    void racingPurchasesNeverOverspendOrDuplicate() throws Exception {
        balances.add(ACCOUNT, 12);   // two purchases' worth
        AtomicInteger items = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Character chr = player(20 + i, 0, client(ACCOUNT, "friend"), "B" + i);
            Thread t = new Thread(() -> {
                try {
                    go.await();
                } catch (InterruptedException e) {
                    return;
                }
                SlumsStamps.buy(chr, 6, () -> true, () -> {
                    items.incrementAndGet();
                    return true;
                });
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join(10_000);
        }
        assertEquals(2, items.get());
        assertEquals(0, balances.balance(ACCOUNT));
    }

    @Test
    void botsNeverSpend() {
        balances.add(ACCOUNT, 100);
        Character bot = player(1_000_000_001, 0, client(ACCOUNT, "x"), "Bot");
        assertEquals(SlumsStamps.Buy.REFUSED, SlumsStamps.buy(bot, 6, () -> true, () -> true));
        assertEquals(100, balances.balance(ACCOUNT));
    }

    // ── shown ──

    @Test
    void theStatusShowsBalanceDayAndNextBonus() {
        balances.add(ACCOUNT, 14);
        DailyCheckinStreak s = DailyCheckinStreak.NEW.claimed(1, T0).claimed(2, T0 + DAY).claimed(3, T0 + 2 * DAY)
                .claimed(4, T0 + 3 * DAY);
        String[] lines = SlumsStamps.statusLines(plainPlayer(), s, T0 + 3 * DAY + 3_600_000);
        assertEquals("Slums Stamps: 14", lines[0]);
        assertEquals("Check-In: day 4/28, next claim in 23h 0m. Next bonus: day 7 (+6).", lines[1]);
        assertEquals(7, SlumsStamps.nextBonusDay(0));
        assertEquals(14, SlumsStamps.nextBonusDay(7));
        assertEquals(28, SlumsStamps.nextBonusDay(27));
        assertEquals(7, SlumsStamps.nextBonusDay(28));
    }

    @Test
    void theSiteFileIsPerAccountAndLeavesOutEmptyAndQaAccounts() {
        Map<String, int[]> rows = new java.util.LinkedHashMap<>();
        rows.put("friend", new int[]{3, 18});
        rows.put("autoqa1", new int[]{4, 6});
        rows.put("broke", new int[]{5, 0});
        Map<String, Object> f = SlumsStamps.privateFile(rows, T0);
        assertEquals("2027-01-15T08:00:00Z", f.get("generated"));
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) f.get("accounts");
        assertEquals(Map.of("friend", Map.of("id", 3, "stamps", 18)), accounts);
        assertNull(accounts.get("autoqa1"));
    }
}
