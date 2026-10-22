package soloMapling.autoqa;

/**
 * One thing the runner saw: a line of the synthetic GM's {@code !qa} run ({@code source} "qa", {@code kind} the
 * topic or note kind: AFFINITY, STAGE, STEP ...) or something a synthetic player's client received ({@code source}
 * "heard:1" / "heard:2", {@code kind} CHAT, WHISPER, NOTICE, YELLOW, MULTICHAT, PARTY_INVITE, BUDDY_REQUEST,
 * TRADE_INVITE, GUILD_INVITE). Expectations match {@link #text()} as {@code "KIND message"}.
 */
public record AutoQaLine(long timeMs, String source, String kind, String message) {

    public boolean isQa() {
        return "qa".equals(source);
    }

    public boolean isHeard() {
        return source.startsWith("heard");
    }

    /** "KIND message": what a pattern is matched against. */
    public String text() {
        return kind + " " + message;
    }

    @Override
    public String toString() {
        return source + " " + text();
    }
}
