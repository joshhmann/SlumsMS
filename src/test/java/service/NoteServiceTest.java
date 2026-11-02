package service;

import database.note.NoteDao;
import model.Note;
import net.packet.out.ShowNotesPacket;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configurator;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import testutil.Mocks;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static testutil.AnyValues.daoException;
import static testutil.AnyValues.string;

class NoteServiceTest {

    @Mock
    private NoteDao noteDao;

    /** Stands in for the character rows: the service's own tests must not reach the database. */
    @Mock
    private NoteService.CharacterNameLookup characterRows;

    private NoteService noteService;

    @BeforeEach
    void reset() {
        MockitoAnnotations.openMocks(this);
        when(characterRows.lookup(any())).thenReturn(NoteService.CharacterNameLookup.Status.PRESENT);
        this.noteService = new NoteService(noteDao, characterRows);
    }

    @Test
    void sendNormalSuccess() {
        String message = "message";
        String from = "from";
        String to = "to";

        NoteSendResult result = noteService.sendNormal(message, from, to);

        assertEquals(NoteSendResult.OK, result);
        var noteCaptor = ArgumentCaptor.forClass(Note.class);
        verify(noteDao).save(noteCaptor.capture());
        var note = noteCaptor.getValue();
        assertEquals(message, note.message());
        assertEquals(from, note.from());
        assertEquals(to, note.to());
        assertEquals(0, note.fame());
    }

    @Test
    void sendWithFameSuccess() {
        String message = "fameMessage";
        String from = "fameFrom";
        String to = "fameTo";

        NoteSendResult result = noteService.sendWithFame(message, from, to);

        assertEquals(NoteSendResult.OK, result);
        var noteCaptor = ArgumentCaptor.forClass(Note.class);
        verify(noteDao).save(noteCaptor.capture());
        var note = noteCaptor.getValue();
        assertEquals(message, note.message());
        assertEquals(from, note.from());
        assertEquals(to, note.to());
        assertEquals(1, note.fame());
    }

    @Test
    void sendFailure() {
        doThrow(daoException()).when(noteDao).save(any());

        NoteSendResult result = noteService.sendNormal(string(), string(), string());

        assertEquals(NoteSendResult.STORAGE_FAILED, result);
        verify(noteDao).save(any());
    }

    /** A name no character has: the note must be refused before it is written, not after. */
    @Test
    void aRecipientThatDoesNotExistIsRefusedWithoutWriting() {
        when(characterRows.lookup("ghost")).thenReturn(NoteService.CharacterNameLookup.Status.ABSENT);

        NoteSendResult result = noteService.sendNormal(string(), "sender", "ghost");

        assertEquals(NoteSendResult.RECEIVER_NOT_FOUND, result);
        verify(noteDao, never()).save(any());
    }

    /** The negative above only bites when the lookup actually runs: it must be asked about the recipient. */
    @Test
    void theLookupIsAskedAboutTheRecipient() {
        noteService.sendNormal(string(), "sender", "to");

        verify(characterRows).lookup("to");
    }

    @Test
    void anInboxAtTheCapIsRefusedWithoutWriting() {
        when(noteDao.countInbox("full")).thenReturn(NoteService.MAX_NOTES_PER_RECIPIENT);

        NoteSendResult result = noteService.sendNormal(string(), "sender", "full");

        assertEquals(NoteSendResult.INBOX_FULL, result);
        verify(noteDao, never()).save(any());
    }

    /** One below the cap still goes through: the ceiling is a cap, not a fence at cap-minus-one. */
    @Test
    void anInboxBelowTheCapAcceptsTheNote() {
        when(noteDao.countInbox("busy")).thenReturn(NoteService.MAX_NOTES_PER_RECIPIENT - 1);

        NoteSendResult result = noteService.sendNormal(string(), "sender", "busy");

        assertEquals(NoteSendResult.OK, result);
        verify(noteDao).save(any());
    }

    /** The gift note (fame +1) shares the send path, so the full-inbox refusal reaches it too. */
    @Test
    void aGiftNoteIsRefusedWhenTheInboxIsFull() {
        when(noteDao.countInbox("full")).thenReturn(NoteService.MAX_NOTES_PER_RECIPIENT);

        NoteSendResult result = noteService.sendWithFame(string(), "sender", "full");

        assertEquals(NoteSendResult.INBOX_FULL, result);
        verify(noteDao, never()).save(any());
    }

    /** Counting the inbox is a read of its own; a refused count is a storage fault, not a full inbox. */
    @Test
    void anInboxThatCannotBeReadIsAStorageFault() {
        when(noteDao.countInbox(any())).thenThrow(daoException());

        NoteSendResult result = noteService.sendNormal(string(), "sender", "to");

        assertEquals(NoteSendResult.STORAGE_FAILED, result);
        verify(noteDao, never()).save(any());
    }

    /**
     * A database that cannot answer the name lookup is not a bad name: the note must be refused as a storage fault,
     * nothing may be written, and the reason must reach the application log rather than the player's typing.
     */
    @Test
    void aRecipientThatCouldNotBeCheckedIsAStorageFault() {
        when(characterRows.lookup("unreachable")).thenReturn(NoteService.CharacterNameLookup.Status.UNKNOWN);

        NoteSendResult result = noteService.sendNormal(string(), "sender", "unreachable");

        assertEquals(NoteSendResult.STORAGE_FAILED, result);
        verify(noteDao, never()).save(any());
        verify(noteDao, never()).countInbox(any());
    }

    /** A refused lookup must not be mistaken for a missing name: the wrong code would blame the sender. */
    @Test
    void aRecipientThatCouldNotBeCheckedIsNotReportedAsAMissingName() {
        when(characterRows.lookup(any())).thenReturn(NoteService.CharacterNameLookup.Status.UNKNOWN);

        NoteSendResult result = noteService.sendWithFame(string(), "sender", "unreachable");

        assertNotEquals(NoteSendResult.RECEIVER_NOT_FOUND, result);
        assertEquals(NoteSendResult.STORAGE_FAILED, result);
    }

    /** Only PRESENT lets the send proceed; the check is asked once, about the name the note is addressed to. */
    @Test
    void onlyAKnownNameReachesTheWrite() {
        noteService.sendNormal(string(), "sender", "to");

        verify(characterRows).lookup("to");
        verify(noteDao).countInbox("to");
        verify(noteDao).save(any());
    }

    @Test
    void showRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> noteService.show(null));
    }

    @Test
    void showOneNote() {
        String chrName = "showMeNotes";
        var chr = Mocks.chr(chrName);
        when(noteDao.findAllByTo(chrName)).thenReturn(List.of(anyNote()));

        noteService.show(chr);

        verify(chr).sendPacket(any(ShowNotesPacket.class));
    }

    private Note anyNote() {
        return new Note(1, "message", "from", "to", 100200300400L, 0);
    }

    @Test
    void showZeroNotes_shouldNotSendPacket() {
        var chr = Mocks.chr("mockChr");
        when(noteDao.findAllByTo(any())).thenReturn(Collections.emptyList());

        noteService.show(chr);

        verify(chr, never()).sendPacket(any());
    }

    @Test
    void showNotesFailure_shouldNotSendPacket() {
        var chr = Mocks.chr("mockChr");
        when(noteDao.findAllByTo(any())).thenThrow(daoException());

        noteService.show(chr);

        verify(chr, never()).sendPacket(any());
    }

    @Test
    void deleteNoteSuccess() {
        int noteId = 1056;
        var note = anyNote();
        when(noteDao.delete(noteId)).thenReturn(Optional.of(note));

        Optional<Note> deletedNote = noteDao.delete(noteId);

        assertTrue(deletedNote.isPresent());
        assertEquals(note, deletedNote.get());
    }

    @Test
    void deleteNoteFailure() {
        when(noteDao.delete(anyInt())).thenThrow(daoException());

        Optional<Note> deletedNote = noteService.delete(4382);

        assertTrue(deletedNote.isEmpty());
    }

    /**
     * The refusal has to leave a trace: six call sites (cash-shop gifts, couple/friendship ring, wedding invitation,
     * Fredrick reminders, guild expulsion) send notes as a side effect and have no memo window to answer, so the log
     * line is the only place a refused note shows up there. Silence is the behaviour this whole change is against.
     */
    @Test
    void aRefusedNoteIsRecordedAndADeliveredOneIsNot() {
        List<String> lines = captureNoteServiceLog(() -> {
            NoteService.warnIfNotDelivered("cash shop gift", "sender", "receiver", NoteSendResult.INBOX_FULL);
            NoteService.warnIfNotDelivered("cash shop gift", "sender", "receiver", NoteSendResult.OK);
        });

        assertEquals(List.of("WARN Note not delivered for cash shop gift: sender -> receiver (INBOX_FULL)"), lines);
    }

    /**
     * The lookup that could not be answered must be logged where the exception is, not only turned into a code:
     * before this, a database outage produced no slf4j line at all for the case it introduced.
     */
    @Test
    void aLookupThatCouldNotBeAnsweredIsLogged() {
        when(characterRows.lookup("unreachable")).thenReturn(NoteService.CharacterNameLookup.Status.UNKNOWN);

        List<String> lines = captureNoteServiceLog(() -> noteService.sendNormal(string(), "sender", "unreachable"));

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("ERROR Refusing note "), lines.get(0));
        assertTrue(lines.get(0).endsWith("so unreachable was never checked"), lines.get(0));
    }

    /** Run the actions with the service's own logger captured as {@code LEVEL message} lines. */
    private static List<String> captureNoteServiceLog(Runnable actions) {
        Logger logger = (Logger) LogManager.getLogger(NoteService.class);
        Level oldLevel = logger.getLevel();
        Capture capture = new Capture();
        Configurator.setLevel(logger.getName(), Level.WARN); // the test config turns the root logger off
        capture.start();
        logger.addAppender(capture);
        try {
            actions.run();
        } finally {
            logger.removeAppender(capture);
            capture.stop();
            Configurator.setLevel(logger.getName(), oldLevel);
        }
        return capture.lines;
    }

    private static final class Capture extends AbstractAppender {
        final List<String> lines = new CopyOnWriteArrayList<>();

        Capture() {
            super("note-service-capture", null, null, true, Property.EMPTY_ARRAY);
        }

        @Override
        public void append(LogEvent event) {
            lines.add(event.getLevel() + " " + event.getMessage().getFormattedMessage());
        }
    }
}
