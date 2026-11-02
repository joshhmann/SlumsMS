package service;

import client.Character;
import database.DaoException;
import database.note.NoteDao;
import model.Note;
import net.packet.out.ShowNotesPacket;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

public class NoteService {
    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    /**
     * Unread notes one inbox will hold before further notes are refused.
     * <p>
     * The notes table had no ceiling at all: every note is a cash item (or a bot/script write), any character name
     * can be written to while its owner is offline, and deleting a note only flips {@code deleted = 1}, so the rows
     * stay. Nothing pruned them: one name could be flooded forever. The stock client does show an inbox of bounded
     * size (it pages a fixed memo list, and its own error code 2 says "Receiver inbox full"), but the exact capacity
     * is client-side and not observable from here, so 100 is a chosen ceiling: far above what a player who reads and
     * discards their notes accumulates, and low enough that a flooder cannot grow one inbox without bound. Counted
     * over {@code deleted = 0} rows only, which is the inbox {@link NoteDao#findAllByTo(String)} hands back.
     */
    public static final int MAX_NOTES_PER_RECIPIENT = 100;

    /** Answers "is this a character name the game knows". A seam so tests of the service never touch the database. */
    @FunctionalInterface
    public interface CharacterNameLookup {
        boolean exists(String name);
    }

    /**
     * The production lookup. {@link Character#getIdByName(String)} is the game's own name-to-id query (character
     * creation uses it to refuse a taken name), so notes ask it instead of carrying SQL of their own. Caveat it
     * inherits: it answers -1 both for "no such name" and for a database fault, which is not visible here, so a dead
     * database turns a note into {@link NoteSendResult#RECEIVER_NOT_FOUND} rather than
     * {@link NoteSendResult#STORAGE_FAILED}. The write that would follow fails too, so the sender is asked to check
     * the name while the server is unavailable -- the least wrong of the two, and the reason is logged by the write.
     */
    public static final CharacterNameLookup CHARACTER_ROWS = name -> Character.getIdByName(name) >= 0;

    private final NoteDao noteDao;
    private final CharacterNameLookup characterExists;

    public NoteService(NoteDao noteDao) {
        this(noteDao, CHARACTER_ROWS);
    }

    public NoteService(NoteDao noteDao, CharacterNameLookup characterExists) {
        this.noteDao = noteDao;
        this.characterExists = characterExists;
    }

    /**
     * Send normal note from one character to another
     *
     * @return what became of the note; see {@link NoteSendResult}
     */
    public NoteSendResult sendNormal(String message, String senderName, String receiverName) {
        Note normalNote = Note.createNormal(message, senderName, receiverName, Server.getInstance().getCurrentTime());
        return send(normalNote);
    }

    /**
     * Send note which will increase the receiver's fame by one.
     *
     * @return what became of the note; see {@link NoteSendResult}
     */
    public NoteSendResult sendWithFame(String message, String senderName, String receiverName) {
        Note noteWithFame = Note.createGift(message, senderName, receiverName, Server.getInstance().getCurrentTime());
        return send(noteWithFame);
    }

    /**
     * Two of the refusals PacketCreator#noteError carries can be decided before the write -- is the recipient a
     * real character, does their inbox have room -- followed by the write itself, whose failure is the third
     * outcome. "Player online, use whisper" (client code 0) is none of them: the recipient being online or not never
     * stopped a note from being stored and shown.
     */
    private NoteSendResult send(Note note) {
        try {
            if (!characterExists.exists(note.to())) {
                return NoteSendResult.RECEIVER_NOT_FOUND;
            }
            if (noteDao.findAllByTo(note.to()).size() >= MAX_NOTES_PER_RECIPIENT) {
                return NoteSendResult.INBOX_FULL;
            }
            noteDao.save(note);
            return NoteSendResult.OK;
        } catch (DaoException e) {
            log.error("Failed to send note {}", note, e);
            return NoteSendResult.STORAGE_FAILED;
        }
    }

    /**
     * Show unread notes
     *
     * @param chr Note recipient
     */
    public void show(Character chr) {
        if (chr == null) {
            throw new IllegalArgumentException("Unable to show notes - chr is null");
        }

        List<Note> notes = getNotes(chr.getName());
        if (notes.isEmpty()) {
            return;
        }

        chr.sendPacket(new ShowNotesPacket(notes));
    }

    private List<Note> getNotes(String to) {
        final List<Note> notes;
        try {
            notes = noteDao.findAllByTo(to);
        } catch (DaoException e) {
            log.error("Failed to find notes sent to chr name {}", to, e);
            return Collections.emptyList();
        }

        if (notes == null || notes.isEmpty()) {
            return Collections.emptyList();
        }

        return notes;
    }

    /**
     * Delete a read note
     *
     * @param noteId Id of note to discard
     * @return Discarded note. Empty optional if failed to discard.
     */
    public Optional<Note> delete(int noteId) {
        try {
            return noteDao.delete(noteId);
        } catch (DaoException e) {
            log.error("Failed to discard note with id {}", noteId, e);
            return Optional.empty();
        }
    }

}
