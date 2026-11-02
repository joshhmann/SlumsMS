package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.Packet;
import net.packet.out.SendNoteSuccessPacket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import server.ItemInformationProvider;
import service.NoteSendResult;
import service.NoteService;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The note cash item (509) used to say nothing when the send failed: no packet, item kept, client sitting there.
 * These tests drive the handler's own seam with a refused send and check both halves of the fix -- a
 * {@code MEMO_RESULT} error packet reaches the player, and the cash item is still in the inventory afterwards
 * (memo economy: a refused note is not a consumed note).
 */
class UseCashItemNoteTest {

    private static final int NOTE_ITEM = 5090000;
    private static final int MEMO_RESULT = SendOpcode.MEMO_RESULT.getValue();
    /** {@code PacketCreator#noteError} always carries mode 5 before the error code. */
    private static final int MEMO_ERROR_MODE = 5;

    @BeforeAll
    static void initializeProviderWithoutGameData() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    private Client client;
    private Character player;
    private Inventory cashInventory;
    private NoteService noteService;

    @BeforeEach
    void setUp() {
        client = mock(Client.class);
        player = mock(Character.class);
        cashInventory = mock(Inventory.class);
        noteService = mock(NoteService.class);

        when(client.getPlayer()).thenReturn(player);
        when(player.getName()).thenReturn("sender");
        when(player.getInventory(InventoryType.CASH)).thenReturn(cashInventory);
        when(player.getInventory(InventoryType.EQUIPPED)).thenReturn(mock(Inventory.class));
        when(cashInventory.getItem((short) 1)).thenReturn(new Item(NOTE_ITEM, (short) 1, (short) 1));
        when(cashInventory.findById(NOTE_ITEM)).thenReturn(new Item(NOTE_ITEM, (short) 1, (short) 1));
        // The handler refuses a cash item used within 3s of the last one. Server time is 0 without a running
        // server, so the last use is stamped well before it.
        when(player.getLastUsedCashItem()).thenReturn(-60_000L);
    }

    @Test
    void aDeliveredNoteConsumesTheItemAndSaysSo() {
        when(noteService.sendNormal("hello", "sender", "receiver")).thenReturn(NoteSendResult.OK);

        try (var iip = mockStatic(ItemInformationProvider.class);
             var im = mockStatic(InventoryManipulator.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
            new UseCashItemHandler(noteService).handlePacket(notePacket("receiver", "hello"), client);

            im.verify(() -> InventoryManipulator.removeFromSlot(client, InventoryType.CASH, (short) 1, (short) 1, true, false));
        }

        verify(client).sendPacket(any(SendNoteSuccessPacket.class));
    }

    @Test
    void aNoteToANameThatDoesNotExistAnswersCodeOneAndKeepsTheItem() {
        assertRefusedWithCode(NoteSendResult.RECEIVER_NOT_FOUND, 1);
    }

    @Test
    void aNoteToAFullInboxAnswersCodeTwoAndKeepsTheItem() {
        assertRefusedWithCode(NoteSendResult.INBOX_FULL, 2);
    }

    /**
     * A database fault has no code of its own; the handler picks 1 (see {@link UseCashItemHandler#noteFailurePacket})
     * and adds a neutral chat line so the player is not left blaming the name.
     */
    @Test
    void aStorageFailureAnswersTheLeastWrongCodeAndSaysNothingWasTheirFault() {
        assertRefusedWithCode(NoteSendResult.STORAGE_FAILED, 1);

        verify(player).dropMessage(eq(1), contains("try again"));
    }

    private void assertRefusedWithCode(NoteSendResult refusal, int expectedCode) {
        when(noteService.sendNormal("hello", "sender", "receiver")).thenReturn(refusal);

        try (var iip = mockStatic(ItemInformationProvider.class);
             var im = mockStatic(InventoryManipulator.class)) {
            iip.when(ItemInformationProvider::getInstance).thenReturn(mock(ItemInformationProvider.class));
            new UseCashItemHandler(noteService).handlePacket(notePacket("receiver", "hello"), client);

            // nothing was taken out of the CASH inventory: a refused note is not a used cash item
            im.verifyNoInteractions();
        }
        verify(cashInventory, never()).lockInventory();

        ArgumentCaptor<Packet> sent = ArgumentCaptor.forClass(Packet.class);
        verify(client).sendPacket(sent.capture());
        assertEquals(MEMO_RESULT, errorPacketCode(sent.getValue(), expectedCode),
                "a refused note must answer with MEMO_RESULT carrying the documented error code");
        verify(client, never()).sendPacket(any(SendNoteSuccessPacket.class));
    }

    /** Asserts the packet is {@code MEMO_RESULT, mode 5, code} and returns the decoded opcode. */
    private static int errorPacketCode(Packet packet, int expectedCode) {
        byte[] b = packet.getBytes();
        assertEquals(4, b.length, "MEMO_RESULT error carries exactly mode + code");
        assertEquals(MEMO_ERROR_MODE, b[2]);
        assertEquals(expectedCode, b[3]);
        return b[0] & 0xff | (b[1] & 0xff) << 8;   // the opcode is a little-endian short
    }

    private static InPacket notePacket(String to, String message) {
        InPacket p = mock(InPacket.class);
        when(p.readShort()).thenReturn((short) 1);
        when(p.readInt()).thenReturn(NOTE_ITEM);
        when(p.readString()).thenReturn(to, message);
        return p;
    }
}
