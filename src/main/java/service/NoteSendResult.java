package service;

/**
 * What became of a note the player asked to send.
 * <p>
 * The send used to report a bare boolean, so "the recipient does not exist", "the recipient's inbox is full" and
 * "the database refused the write" were all the same {@code false} and the caller could not tell the client
 * anything. Each value here is something the server can genuinely determine; nothing else is claimed.
 */
public enum NoteSendResult {
    /** The note row is stored: the recipient will see it in their inbox. */
    OK,
    /** No character with the recipient's name exists, so the note would land where nobody can read it. */
    RECEIVER_NOT_FOUND,
    /** The recipient already holds {@link NoteService#MAX_NOTES_PER_RECIPIENT} unread notes. */
    INBOX_FULL,
    /** The database threw: the note is not stored and nothing is known about the recipient. */
    STORAGE_FAILED;

    public boolean isOk() {
        return this == OK;
    }
}
