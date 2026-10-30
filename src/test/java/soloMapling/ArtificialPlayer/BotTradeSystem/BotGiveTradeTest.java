package soloMapling.ArtificialPlayer.BotTradeSystem;

import client.BotClient;
import client.Character;
import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.Trade;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.End;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Give;
import soloMapling.ArtificialPlayer.BotTradeSystem.BotGiveTrade.Plan;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 1.6.2 ticket 99: a companion hands things over through a normal trade window at a person's pace (nutnnut/Cosmic
 * BotTradePacing): invite, wait for the accept, one item a beat, the window line, wait for Trade, a longer beat,
 * confirm. A headless recipient (a sibling alt) has its accept and Trade pressed with the same beats.
 */
class BotGiveTradeTest {

    /** A hand-cranked clock: steps run in time order; {@link #now} is the time of the running step. */
    static final class FakeClock implements BotGiveTrade.Clock {
        record Step(long at, long seq, Runnable run) {
        }

        final PriorityQueue<Step> queue = new PriorityQueue<>((a, b) -> a.at != b.at ? Long.compare(a.at, b.at)
                : Long.compare(a.seq, b.seq));
        long now;
        long seq;

        @Override
        public void after(long ms, Runnable step) {
            queue.add(new Step(now + ms, seq++, step));
        }

        void runFor(long ms) {
            long until = now + ms;
            while (!queue.isEmpty() && queue.peek().at <= until) {
                Step s = queue.poll();
                now = s.at;
                s.run.run();
            }
            now = until;
        }
    }

    /** A window that records what was done and when. */
    final class FakeWindow implements BotGiveTrade.Window {
        final List<String> log = new ArrayList<>();
        final List<Long> putAt = new ArrayList<>();
        boolean open;
        boolean accepted;
        boolean partnerLocked;
        boolean recipientAccepts = true;
        Runnable onTraded;

        @Override
        public boolean invite(Character giver, Character recipient) {
            open = true;
            log.add("invite " + recipient.getName());
            return true;
        }

        @Override
        public boolean open(Character giver) {
            return open;
        }

        @Override
        public boolean accepted(Character giver) {
            return accepted;
        }

        @Override
        public void accept(Character recipient, Character giver) {
            log.add("accept for " + recipient.getName());
            accepted = true;
        }

        @Override
        public Trade.PutResult put(Character giver, InventoryType type, short pos, short quantity, byte slot) {
            log.add("put " + pos + " x" + quantity + " in " + slot);
            putAt.add(clock.now);
            return Trade.PutResult.ADDED;
        }

        @Override
        public void mesos(Character giver, int mesos) {
            log.add("mesos " + mesos);
        }

        @Override
        public void chat(Character giver, String line) {
            log.add("chat " + line);
        }

        @Override
        public boolean partnerLocked(Character giver) {
            return partnerLocked;
        }

        @Override
        public void confirm(Character who) {
            log.add("confirm " + who.getName() + " at " + clock.now);
            if (who.getName().equals("Sib")) {
                partnerLocked = true;
            } else if (partnerLocked) {
                open = false;
                onTraded.run();
            }
        }

        @Override
        public void cancel(Character giver) {
            log.add("cancel");
            open = false;
        }

        @Override
        public void onTraded(Character giver, Runnable onTraded) {
            this.onTraded = onTraded;
        }
    }

    final FakeClock clock = new FakeClock();
    final FakeWindow window = new FakeWindow();
    final MapleMap map = mock(MapleMap.class);
    final List<End> ends = new ArrayList<>();
    final List<String> said = new ArrayList<>();

    @BeforeEach
    void setUp() {
        System.setProperty("slums.tradePacing.scale", "1.0");
        BotGiveTrade.useForTest(window, clock);
    }

    @AfterEach
    void tearDown() {
        BotGiveTrade.useForTest(null, null);
    }

    private Character chr(int id, String name, boolean alt) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMap()).thenReturn(map);
        when(c.getMeso()).thenReturn(1_000_000);
        Client client = alt ? mock(BotClient.class) : mock(Client.class);
        when(c.getClient()).thenReturn(client);
        when(c.isOwnedAlt()).thenReturn(alt);
        return c;
    }

    private static Give give(int pos, int qty) {
        Item item = new Item(2000000, (short) pos, (short) qty);
        return new Give(InventoryType.USE, item, (short) qty);
    }

    private Plan plan(Character giver, Character to, List<Give> items, int mesos) {
        return new Plan(giver, to, items, mesos, "there u go", said::add, ends::add);
    }

    @Test
    void handsOverAtAPersonsPaceAndConfirmsAfterThePlayerPressesTrade() {
        Character alt = chr(1, "Cleo", true);
        Character owner = chr(2, "Owner", false);
        assertTrue(BotGiveTrade.start(plan(alt, owner, List.of(give(3, 50), give(4, 20)), 100_000)));
        assertTrue(BotGiveTrade.isBusy(alt));
        clock.runFor(TradePacing.STEP_MAX_MS);
        assertEquals(List.of("invite Owner"), window.log);
        clock.runFor(10_000);
        assertEquals(1, window.log.size(), "nothing goes in before the player accepts");
        window.accepted = true;
        clock.runFor(30_000);
        assertEquals(List.of("invite Owner", "put 3 x50 in 1", "put 4 x20 in 2", "mesos 100000", "chat there u go"),
                window.log);
        assertTrue(window.putAt.get(1) - window.putAt.get(0) >= TradePacing.STEP_MIN_MS, "one item a beat");
        assertFalse(window.log.stream().anyMatch(l -> l.startsWith("confirm")), "waits for the player's Trade");
        window.partnerLocked = true;
        long lockedAt = clock.now;
        clock.runFor(10_000);
        String confirm = window.log.get(window.log.size() - 1);
        assertTrue(confirm.startsWith("confirm Cleo"));
        long confirmedAt = Long.parseLong(confirm.substring(confirm.lastIndexOf(' ') + 1));
        assertTrue(confirmedAt - lockedAt >= TradePacing.CONFIRM_MIN_MS, "a re-read-the-window beat before Trade");
        assertEquals(List.of(End.TRADED), ends);
        assertFalse(BotGiveTrade.isBusy(alt));
        assertTrue(said.isEmpty(), "a finished trade needs no extra line");
    }

    @Test
    void aSiblingAltHasItsAcceptAndTradePressedForIt() {
        Character alt = chr(1, "Cleo", true);
        Character sib = chr(3, "Sib", true);
        assertTrue(BotGiveTrade.start(plan(alt, sib, List.of(give(3, 50)), 0)));
        clock.runFor(60_000);
        assertEquals("invite Sib", window.log.get(0));
        assertEquals("accept for Sib", window.log.get(1));
        assertTrue(window.log.stream().anyMatch(l -> l.startsWith("confirm Sib")));
        assertTrue(window.log.get(window.log.size() - 1).startsWith("confirm Cleo"));
        assertEquals(List.of(End.TRADED), ends);
    }

    @Test
    void anUnansweredInviteIsWithdrawnAndSaidSo() {
        Character alt = chr(1, "Cleo", true);
        Character owner = chr(2, "Owner", false);
        BotGiveTrade.start(plan(alt, owner, List.of(give(3, 50)), 0));
        clock.runFor(BotGiveTrade.ACCEPT_TIMEOUT_MS + 5_000);
        assertTrue(window.log.contains("cancel"));
        assertEquals(List.of(End.TIMED_OUT), ends);
        assertEquals(1, said.size());
        assertFalse(BotGiveTrade.isBusy(alt));
    }

    @Test
    void aDeclineEndsIt() {
        Character alt = chr(1, "Cleo", true);
        Character owner = chr(2, "Owner", false);
        BotGiveTrade.start(plan(alt, owner, List.of(give(3, 50)), 0));
        clock.runFor(TradePacing.STEP_MAX_MS);
        window.open = false; // the player pressed decline
        clock.runFor(2_000);
        assertEquals(List.of(End.DECLINED), ends);
    }

    @Test
    void refusesWhatAPlayerCouldNotDoEither() {
        Character alt = chr(1, "Cleo", true);
        Character owner = chr(2, "Owner", false);
        Character elsewhere = chr(4, "Far", false);
        when(elsewhere.getMap()).thenReturn(mock(MapleMap.class));
        assertFalse(BotGiveTrade.start(plan(alt, elsewhere, List.of(give(3, 1)), 0)), "another map");
        assertFalse(BotGiveTrade.start(plan(alt, owner, List.of(), 0)), "nothing to give");
        when(owner.getTrade()).thenReturn(mock(Trade.class));
        assertFalse(BotGiveTrade.start(plan(alt, owner, List.of(give(3, 1)), 0)), "already trading");
        Character rosterBot = chr(1_000_000_005, "Pop", true);
        when(rosterBot.isOwnedAlt()).thenReturn(false);
        assertFalse(BotGiveTrade.canGive(rosterBot), "population holdings cannot go through a window yet");
        assertTrue(BotGiveTrade.canGive(alt));
    }

    @Test
    void oneHandoverAtATimePerGiverAndNineItemsAWindow() {
        Character alt = chr(1, "Cleo", true);
        Character owner = chr(2, "Owner", false);
        List<Give> twelve = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            twelve.add(give(i, 1));
        }
        assertTrue(BotGiveTrade.start(plan(alt, owner, twelve, 0)));
        assertFalse(BotGiveTrade.start(plan(alt, owner, List.of(give(20, 1)), 0)), "busy");
        window.accepted = true;
        clock.runFor(60_000);
        assertEquals(BotGiveTrade.WINDOW_SLOTS, window.putAt.size());
    }
}
