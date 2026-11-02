package service;

import database.note.NoteDao;
import model.Note;
import net.packet.out.ShowNotesPacket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import testutil.Mocks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
    private NoteService.CharacterNameLookup characterExists;

    private NoteService noteService;

    @BeforeEach
    void reset() {
        MockitoAnnotations.openMocks(this);
        when(characterExists.exists(any())).thenReturn(true);
        this.noteService = new NoteService(noteDao, characterExists);
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
        when(characterExists.exists("ghost")).thenReturn(false);

        NoteSendResult result = noteService.sendNormal(string(), "sender", "ghost");

        assertEquals(NoteSendResult.RECEIVER_NOT_FOUND, result);
        verify(noteDao, never()).save(any());
    }

    /** The negative above only bites when the lookup actually runs: it must be asked about the recipient. */
    @Test
    void theLookupIsAskedAboutTheRecipient() {
        noteService.sendNormal(string(), "sender", "to");

        verify(characterExists).exists("to");
    }

    @Test
    void anInboxAtTheCapIsRefusedWithoutWriting() {
        when(noteDao.findAllByTo("full")).thenReturn(notes(NoteService.MAX_NOTES_PER_RECIPIENT));

        NoteSendResult result = noteService.sendNormal(string(), "sender", "full");

        assertEquals(NoteSendResult.INBOX_FULL, result);
        verify(noteDao, never()).save(any());
    }

    /** One below the cap still goes through: the ceiling is a cap, not a fence at cap-minus-one. */
    @Test
    void anInboxBelowTheCapAcceptsTheNote() {
        when(noteDao.findAllByTo("busy")).thenReturn(notes(NoteService.MAX_NOTES_PER_RECIPIENT - 1));

        NoteSendResult result = noteService.sendNormal(string(), "sender", "busy");

        assertEquals(NoteSendResult.OK, result);
        verify(noteDao).save(any());
    }

    /** The gift note (fame +1) shares the send path, so the full-inbox refusal reaches it too. */
    @Test
    void aGiftNoteIsRefusedWhenTheInboxIsFull() {
        when(noteDao.findAllByTo("full")).thenReturn(notes(NoteService.MAX_NOTES_PER_RECIPIENT));

        NoteSendResult result = noteService.sendWithFame(string(), "sender", "full");

        assertEquals(NoteSendResult.INBOX_FULL, result);
        verify(noteDao, never()).save(any());
    }

    /** Counting the inbox means reading it; a refused read is a storage fault, not a full inbox. */
    @Test
    void anInboxThatCannotBeReadIsAStorageFault() {
        when(noteDao.findAllByTo(any())).thenThrow(daoException());

        NoteSendResult result = noteService.sendNormal(string(), "sender", "to");

        assertEquals(NoteSendResult.STORAGE_FAILED, result);
        verify(noteDao, never()).save(any());
    }

    private static List<Note> notes(int count) {
        List<Note> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(new Note(i, "message", "from", "to", 100200300400L, 0));
        }
        return list;
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
}