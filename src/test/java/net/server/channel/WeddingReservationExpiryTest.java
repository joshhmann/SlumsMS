package net.server.channel;

import client.Character;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.processor.npc.DueyProcessor;
import config.YamlConfig;
import net.server.PlayerStorage;
import net.server.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import server.TimerManager;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Ticket 188 (F3): a couple that booked a wedding and did not start the ceremony within WEDDING_RESERVATION_TIMEOUT lost
 * the booking ticket - the timeout task only closed the venue. It now gives the ticket back, once, to the partner who
 * booked, and the real timeout task is driven here (the timer is a capture, nothing sleeps for ten minutes).
 */
class WeddingReservationExpiryTest {

    private static final int BOOKER = 11;
    private static final int PARTNER = 12;
    private static final int WEDDING = 7;
    private static final int NEXT_WEDDING = 8;

    private final int timeoutWas = YamlConfig.config.server.WEDDING_RESERVATION_TIMEOUT;
    private final int intervalWas = YamlConfig.config.server.WEDDING_RESERVATION_INTERVAL;

    private final List<Runnable> timeoutTasks = new ArrayList<>();
    private final List<ScheduledFuture<?>> timeoutFutures = new ArrayList<>();
    private final List<Long> timeoutDelays = new ArrayList<>();

    private MockedStatic<TimerManager> timers;
    private MockedStatic<InventoryManipulator> inventory;
    private MockedStatic<DueyProcessor> duey;

    private Channel channel;
    private PlayerStorage players;
    private Character booker;
    private Character partner;

    @BeforeEach
    void setUp() throws Exception {
        YamlConfig.config.server.WEDDING_RESERVATION_TIMEOUT = 10;
        YamlConfig.config.server.WEDDING_RESERVATION_INTERVAL = 60;

        TimerManager timer = mock(TimerManager.class);
        when(timer.schedule(any(Runnable.class), anyLong())).thenAnswer(inv -> {
            ScheduledFuture<?> future = mock(ScheduledFuture.class);
            timeoutTasks.add(inv.getArgument(0));
            timeoutDelays.add(inv.getArgument(1));
            timeoutFutures.add(future);
            return future;
        });
        timers = org.mockito.Mockito.mockStatic(TimerManager.class);
        timers.when(TimerManager::getInstance).thenReturn(timer);

        inventory = org.mockito.Mockito.mockStatic(InventoryManipulator.class);
        duey = org.mockito.Mockito.mockStatic(DueyProcessor.class);

        booker = mock(Character.class);
        partner = mock(Character.class);
        players = mock(PlayerStorage.class);
        when(players.getCharacterById(BOOKER)).thenReturn(booker);
        when(players.getCharacterById(PARTNER)).thenReturn(partner);
        World world = mock(World.class);
        when(world.getPlayerStorage()).thenReturn(players);

        // Channel's constructor opens a listening port, so the real methods run on a mock with only the fields they touch
        channel = mock(Channel.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));
        setField("lock", new ReentrantLock(true));
        doReturn(world).when(channel).getWorldServer();
    }

    @AfterEach
    void tearDown() {
        timers.close();
        inventory.close();
        duey.close();
        YamlConfig.config.server.WEDDING_RESERVATION_TIMEOUT = timeoutWas;
        YamlConfig.config.server.WEDDING_RESERVATION_INTERVAL = intervalWas;
    }

    private void setField(String name, Object value) throws Exception {
        Field f = Channel.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(channel, value);
    }

    private static BookedWedding wedding(int id, boolean premium) {
        return new BookedWedding(premium, id, new HashSet<>(), BOOKER, PARTNER);
    }

    private void bookerHasRoom(int ticketId) {
        inventory.when(() -> InventoryManipulator.checkSpace(booker, ticketId, 1, "")).thenReturn(true);
        inventory.when(() -> InventoryManipulator.addById(booker, ticketId, (short) 1)).thenReturn(true);
    }

    private void fireTimeout(int index) {
        timeoutTasks.get(index).run();
    }

    @ParameterizedTest(name = "cathedral={0} premium={1} -> ticket {2}")
    @CsvSource({"true,false,5251000", "true,true,5251003", "false,false,5251001", "false,true,5251002"})
    void aCoupleThatNeverStartsTheCeremonyGetsTheTicketForThatVenueBack(boolean cathedral, boolean premium, int ticketId) {
        bookerHasRoom(ticketId);
        channel.setOngoingWedding(cathedral, wedding(WEDDING, premium));
        assertEquals(WEDDING, channel.getOngoingWedding(cathedral));
        assertEquals(TimeUnit.MINUTES.toMillis(10), timeoutDelays.get(0));

        fireTimeout(0);

        assertNull(channel.getOngoingWedding(cathedral), "the venue is closed for them");
        inventory.verify(() -> InventoryManipulator.addById(booker, ticketId, (short) 1));
        duey.verify(() -> DueyProcessor.dueyCreatePackage(any(Item.class), anyInt(), anyString(), anyInt()), never());
        verify(booker).dropMessage(eq(6), contains("ticket"));
        verify(partner).dropMessage(eq(6), contains("ticket"));
    }

    @Test
    void theTicketIsReturnedOnce() {
        bookerHasRoom(5251000);
        channel.setOngoingWedding(true, wedding(WEDDING, false));

        fireTimeout(0);
        fireTimeout(0);

        inventory.verify(() -> InventoryManipulator.addById(booker, 5251000, (short) 1), times(1));
    }

    @Test
    void anOfflineBookerGetsTheTicketFromDuey() {
        when(players.getCharacterById(BOOKER)).thenReturn(null);
        duey.when(() -> DueyProcessor.dueyCreatePackage(any(Item.class), anyInt(), anyString(), eq(BOOKER))).thenReturn(true);
        channel.setOngoingWedding(false, wedding(WEDDING, true));

        fireTimeout(0);

        ArgumentCaptor<Item> parcel = ArgumentCaptor.forClass(Item.class);
        duey.verify(() -> DueyProcessor.dueyCreatePackage(parcel.capture(), eq(0), eq("Wedding Desk"), eq(BOOKER)));
        assertEquals(5251002, parcel.getValue().getItemId());
        assertEquals(1, parcel.getValue().getQuantity());
        verify(partner).dropMessage(eq(6), contains("ticket"));
    }

    @Test
    void aFullCashInventoryGetsTheTicketFromDueyInsteadOfLosingIt() {
        inventory.when(() -> InventoryManipulator.checkSpace(booker, 5251003, 1, "")).thenReturn(false);
        duey.when(() -> DueyProcessor.dueyCreatePackage(any(Item.class), anyInt(), anyString(), eq(BOOKER))).thenReturn(true);
        channel.setOngoingWedding(true, wedding(WEDDING, true));

        fireTimeout(0);

        inventory.verify(() -> InventoryManipulator.addById(any(Character.class), anyInt(), anyShort()), never());
        ArgumentCaptor<Item> parcel = ArgumentCaptor.forClass(Item.class);
        duey.verify(() -> DueyProcessor.dueyCreatePackage(parcel.capture(), eq(0), anyString(), eq(BOOKER)));
        assertEquals(5251003, parcel.getValue().getItemId());
        verify(booker).dropMessage(eq(6), contains("Duey"));
    }

    @Test
    void aCoupleThatStartedTheCeremonyInTimeKeepsTheirTicketSpent() {
        channel.setOngoingWedding(true, wedding(WEDDING, false));

        assertTrue(channel.acceptOngoingWedding(true));
        verify(timeoutFutures.get(0)).cancel(false);
        fireTimeout(0);     // the timer lost the race against the couple

        assertEquals(WEDDING, channel.getOngoingWedding(true), "the ceremony keeps its venue");
        duey.verifyNoInteractions();
        inventory.verifyNoInteractions();
    }

    @Test
    void aWeddingThatTimedOutCannotBeStartedAfterwards() {
        bookerHasRoom(5251000);
        channel.setOngoingWedding(true, wedding(WEDDING, false));
        fireTimeout(0);

        assertFalse(channel.acceptOngoingWedding(true));
    }

    @Test
    void whenTheNextSlotIsDueTheCoupleThatNeverCameIsRefundedAndTheNewOneKeepsItsVenue() {
        bookerHasRoom(5251000);
        channel.setOngoingWedding(true, wedding(WEDDING, false));

        channel.setOngoingWedding(true, wedding(NEXT_WEDDING, false));

        verify(timeoutFutures.get(0)).cancel(false);
        inventory.verify(() -> InventoryManipulator.addById(booker, 5251000, (short) 1), times(1));
        assertEquals(NEXT_WEDDING, channel.getOngoingWedding(true));

        fireTimeout(0);     // a stale timeout must neither refund again nor close the new couple's venue
        inventory.verify(() -> InventoryManipulator.addById(booker, 5251000, (short) 1), times(1));
        assertEquals(NEXT_WEDDING, channel.getOngoingWedding(true));
    }

    @Test
    void anEmptySlotWithNobodyWaitingRefundsNothing() {
        channel.setOngoingWedding(true, null);

        assertNull(channel.getOngoingWedding(true));
        assertTrue(timeoutTasks.isEmpty());
        duey.verifyNoInteractions();
        inventory.verifyNoInteractions();
    }

    @Test
    void aReservationWhoseStartTimePassedReadsAsVerySoonNotNull() throws Exception {
        List<Integer> queue = new LinkedList<>();
        queue.add(null);
        queue.add(WEDDING);
        setField("cathedralReservationQueue", queue);
        setField("chapelReservationQueue", new LinkedList<Integer>());
        setField("ongoingStartTime", 0L);   // the last task tick was long ago, so booked time + 2 hours is in the past
        YamlConfig.config.server.WEDDING_RESERVATION_INTERVAL = 1;

        String text = channel.getWeddingReservationTimeLeft(WEDDING);

        assertEquals("Cathedral - very soon", text);
    }

    @Test
    void aReservationStillAheadKeepsItsCountdown() throws Exception {
        List<Integer> queue = new LinkedList<>();
        queue.add(null);
        queue.add(WEDDING);
        setField("cathedralReservationQueue", queue);
        setField("chapelReservationQueue", new LinkedList<Integer>());
        setField("ongoingStartTime", System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(30));   // booked 2 x 60 minutes after it

        String text = channel.getWeddingReservationTimeLeft(WEDDING);

        assertTrue(text.startsWith("Cathedral - 1 hours, ") && text.endsWith(" seconds from now"), text);
    }

    @Test
    void noReservationAtAllStillReadsAsNull() throws Exception {
        setField("cathedralReservationQueue", new LinkedList<Integer>());
        setField("chapelReservationQueue", new LinkedList<Integer>());

        assertNull(channel.getWeddingReservationTimeLeft(WEDDING));
    }
}
