package service;

import client.Character;
import database.DaoException;
import database.note.NoteDao;
import model.Note;
import net.packet.out.ShowNotesPacket;
import net.server.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
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

    /**
     * What the characters table says about a name. Three answers, not two: a name the game knows, a name it does not,
     * and "the table could not be read". The third is not a detail: a database fault must not be reported to the
     * player as a misspelled name, and must not vanish either, so it leaves the service as
     * {@link NoteSendResult#STORAGE_FAILED}. A seam, so tests of the service never touch the database.
     */
    @FunctionalInterface
    public interface CharacterNameLookup {
        Status lookup(String name);

        enum Status { PRESENT, ABSENT, UNKNOWN }
    }

    /**
     * The production lookup: the game's own name-to-id query, {@code SELECT id FROM characters WHERE name = ?}, the
     * same one character creation uses to refuse a taken name. It runs here rather than through
     * {@link Character#getIdByName(String)} because that method answers -1 for "no such name" and for a failed
     * connection alike (its catch prints the stack trace and returns -1), which would report a dead database as a
     * bad name. Here the two are told apart: the exception is logged with the name, and the caller hears
     * {@link CharacterNameLookup.Status#UNKNOWN}.
     */
    public static final CharacterNameLookup CHARACTER_ROWS = name -> {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT id FROM characters WHERE name = ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? CharacterNameLookup.Status.PRESENT : CharacterNameLookup.Status.ABSENT;
            }
        } catch (SQLException e) {
            log.error("Failed to look up note recipient {} in the characters table", name, e);
            return CharacterNameLookup.Status.UNKNOWN;
        }
    };

    private final NoteDao noteDao;
    private final CharacterNameLookup characterRows;

    public NoteService(NoteDao noteDao) {
        this(noteDao, CHARACTER_ROWS);
    }

    public NoteService(NoteDao noteDao, CharacterNameLookup characterRows) {
        this.noteDao = Objects.requireNonNull(noteDao, "noteDao");
        this.characterRows = Objects.requireNonNull(characterRows, "characterRows");
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
     * <p>
     * A lookup that could not be answered is the fourth case and belongs with the third: a note is not written to a
     * name nobody managed to check, the fault reaches the application log here, and the player is told nothing was
     * their fault.
     */
    private NoteSendResult send(Note note) {
        try {
            switch (characterRows.lookup(note.to())) {
                case ABSENT -> {
                    return NoteSendResult.RECEIVER_NOT_FOUND;
                }
                case UNKNOWN -> {
                    log.error("Refusing note {}: the characters table could not be read, so {} was never checked",
                            note, note.to());
                    return NoteSendResult.STORAGE_FAILED;
                }
                case PRESENT -> {
                }
            }
            if (noteDao.countInbox(note.to()) >= MAX_NOTES_PER_RECIPIENT) {
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
