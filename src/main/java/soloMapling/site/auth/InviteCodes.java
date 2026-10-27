package soloMapling.site.auth;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Ticket 105: invite codes for site/launcher sign-up. GMs make them with {@code !invite}; each one creates exactly one
 * account, and stays valid for the time its maker chose (1 day, 7 days, 30 days or forever) unless revoked.
 * <p>
 * A code is 10 characters from a 32-letter alphabet with no look-alikes (no 0/O, 1/I), drawn with SecureRandom:
 * 50 bits, shown as {@code ABCDE-FGHJK}. Input ignores case, dashes and spaces.
 */
public final class InviteCodes {

    static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    static final int LENGTH = 10;
    static final int NOTE_MAX = 100;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter PACIFIC =
            DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ROOT).withZone(ZoneId.of("America/Los_Angeles"));

    /** How long a new code stays valid; {@code !invite <1d|7d|30d|forever>}, 7d when not given. */
    public enum Validity {
        ONE_DAY("1d", TimeUnit.DAYS.toMillis(1)),
        SEVEN_DAYS("7d", TimeUnit.DAYS.toMillis(7)),
        THIRTY_DAYS("30d", TimeUnit.DAYS.toMillis(30)),
        FOREVER("forever", 0);

        public static final Validity DEFAULT = SEVEN_DAYS;

        public final String word;
        final long millis;

        Validity(String word, long millis) {
            this.word = word;
            this.millis = millis;
        }

        /** Null when the word is not one of 1d, 7d, 30d, forever. */
        public static Validity parse(String word) {
            for (Validity v : values()) {
                if (v.word.equalsIgnoreCase(word)) {
                    return v;
                }
            }
            return null;
        }

        /** The expiry for a code made at {@code now}: epoch ms, 0 for never. */
        public long expiresAt(long now) {
            return millis == 0 ? 0 : now + millis;
        }
    }

    public enum Status { OPEN, USED, EXPIRED, REVOKED }

    /**
     * One row of {@code invite_codes}. Times are epoch ms; 0 means not set (expiresAt 0: never expires).
     */
    public record Invite(String code, long createdAt, int createdByAccount, String createdByName, String note,
                         long expiresAt, int usedByAccount, String usedByName, long usedAt, long revokedAt,
                         String revokedBy) {

        public static Invite fresh(String code, long now, int byAccount, String byName, String note, long expiresAt) {
            return new Invite(code, now, byAccount, byName, note, expiresAt, 0, "", 0, 0, "");
        }

        public Status status(long now) {
            if (revokedAt > 0) {
                return Status.REVOKED;
            }
            if (usedAt > 0) {
                return Status.USED;
            }
            if (expiresAt > 0 && now >= expiresAt) {
                return Status.EXPIRED;
            }
            return Status.OPEN;
        }
    }

    private InviteCodes() {
    }

    /** A new random code (canonical form, no dash). */
    public static String newCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** The canonical form of a typed code, or null when it cannot be one. */
    public static String normalize(String typed) {
        if (typed == null || typed.length() > 32) {
            return null;
        }
        String s = typed.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (s.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET.indexOf(s.charAt(i)) < 0) {
                return null;
            }
        }
        return s;
    }

    /** {@code ABCDE-FGHJK}. */
    public static String display(String code) {
        return code.length() == LENGTH ? code.substring(0, 5) + "-" + code.substring(5) : code;
    }

    /** A GM's note, kept short and printable. */
    public static String cleanNote(String note) {
        if (note == null) {
            return "";
        }
        String s = note.replaceAll("\\p{Cntrl}", " ").trim();
        return s.length() > NOTE_MAX ? s.substring(0, NOTE_MAX) : s;
    }

    /** One line of {@code !invite list}. */
    public static String describe(Invite i, long now) {
        StringBuilder sb = new StringBuilder(display(i.code())).append("  ");
        switch (i.status(now)) {
            case OPEN -> sb.append(i.expiresAt() == 0 ? "open, never expires" : "open, expires " + pacific(i.expiresAt()));
            case USED -> sb.append("used by ").append(i.usedByName() == null || i.usedByName().isEmpty()
                    ? "account " + i.usedByAccount() : i.usedByName()).append(" ").append(pacific(i.usedAt()));
            case EXPIRED -> sb.append("expired ").append(pacific(i.expiresAt()));
            case REVOKED -> sb.append("revoked ").append(pacific(i.revokedAt()));
        }
        sb.append("  by ").append(i.createdByName());
        if (i.note() != null && !i.note().isEmpty()) {
            sb.append("  \"").append(i.note()).append('"');
        }
        return sb.toString();
    }

    static String pacific(long ms) {
        return PACIFIC.format(Instant.ofEpochMilli(ms)) + " PT";
    }
}
