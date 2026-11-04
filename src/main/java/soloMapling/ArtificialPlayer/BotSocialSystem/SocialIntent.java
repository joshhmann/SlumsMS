package soloMapling.ArtificialPlayer.BotSocialSystem;

/**
 * Ticket 181 stage 5: one thing a bot means to do for another character later, because it agreed to (a promise to
 * meet, help, come along, party, hang out). Immutable; the live ones are kept in the actor's {@link IntentBook}
 * (inside its life memory, saved with it).
 *
 * @param id        unique across bots: the actor's id in the high 32 bits, a per-actor sequence in the low 32
 * @param actor     the bot that must carry it out
 * @param target    who it was promised to (a bot or a real player)
 * @param kind      what the actor will do (a {@link SocialActKind})
 * @param createdAt when the promise was made (epoch ms, {@link SocialSim#now()})
 * @param dueAt     when the actor starts trying to carry it out
 * @param expiresAt when the promise counts as broken if it has not been carried out (always after {@code dueAt})
 * @param mapId     where it was made (0: unknown)
 * @param origin    short tag of where the promise came from ({@code talk:PARTY}, {@code chat:COME}), bounded text
 */
public record SocialIntent(long id, int actor, int target, SocialActKind kind, long createdAt, long dueAt,
                           long expiresAt, int mapId, String origin) {

    /** The longest {@code origin} kept. */
    public static final int ORIGIN_MAX = 40;

    public SocialIntent {
        origin = clip(origin);
    }

    /** Bounded, single-line text. */
    static String clip(String text) {
        if (text == null) {
            return "";
        }
        String clean = text.replaceAll("[\\r\\n\\t]+", " ").trim();
        return clean.length() <= ORIGIN_MAX ? clean : clean.substring(0, ORIGIN_MAX);
    }

    /** Whether it is time to carry it out (not yet past its deadline is the caller's check). */
    public boolean dueBy(long now) {
        return now >= dueAt;
    }

    /** Whether the deadline has passed. */
    public boolean expiredBy(long now) {
        return now >= expiresAt;
    }
}
