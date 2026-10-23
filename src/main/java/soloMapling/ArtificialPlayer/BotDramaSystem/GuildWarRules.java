package soloMapling.ArtificialPlayer.BotDramaSystem;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The arithmetic of guild wars (1.5.0 bot drama), pure and tested.
 *
 * <ul>
 *   <li><b>Turf:</b> once a minute, on every grind map where either side has members standing, the side with more of
 *   them there holds it and scores a point (a tie scores nothing). A week of this is the war score.</li>
 *   <li><b>War on a player guild:</b> a bot guild declares war on a player guild when at least half of that guild's
 *   online members (those with {@code @botdrama} on) are rivals of the bot guild, and at least one is.</li>
 * </ul>
 */
public final class GuildWarRules {

    private GuildWarRules() {
    }

    static long warMs() {
        return DramaKit.days("wars.warDays", 7);
    }

    static long cooldownMs() {
        return DramaKit.days("wars.cooldownDays", 3);
    }

    /** The minute's turf: points for each side and who holds each contested map. */
    record Turf(int pointsA, int pointsB, Map<Integer, Integer> holder) {
    }

    /**
     * Scores one minute. {@code presence}: map id -> {members of A there, members of B there}. {@code a}/{@code b}: the
     * guild ids written into {@link Turf#holder}.
     */
    static Turf turf(Map<Integer, int[]> presence, int a, int b) {
        int pa = 0;
        int pb = 0;
        Map<Integer, Integer> held = new HashMap<>();
        for (Map.Entry<Integer, int[]> e : presence.entrySet()) {
            int na = e.getValue()[0];
            int nb = e.getValue()[1];
            if (na > nb) {
                pa++;
                held.put(e.getKey(), a);
            } else if (nb > na) {
                pb++;
                held.put(e.getKey(), b);
            }
        }
        return new Turf(pa, pb, held);
    }

    /** Whether a bot guild declares war on a player guild: half or more of its online members are its rivals. */
    static boolean declareOnPlayers(int membersOnline, int rivalsOfUs) {
        return rivalsOfUs >= 1 && membersOnline >= 1 && rivalsOfUs * 2 >= membersOnline;
    }

    /**
     * Whether a player may offer peace ({@code @guildwar peace}): the guild's master (by the guild's own leader id,
     * whatever rank the character carries) or a jr. master (rank 2).
     */
    static boolean mayOfferPeace(int guildRank, boolean isMaster) {
        return isMaster || guildRank == 1 || guildRank == 2;
    }

    /** The winner's guild id, or 0 for a draw. */
    static int winner(int a, int scoreA, int b, int scoreB) {
        return scoreA > scoreB ? a : scoreB > scoreA ? b : 0;
    }

    /** Days (rounded up) until a war ends. */
    static long daysLeft(long endsAt, long now) {
        long ms = Math.max(0, endsAt - now);
        return (ms + TimeUnit.DAYS.toMillis(1) - 1) / TimeUnit.DAYS.toMillis(1);
    }

    /** The chance per minute step that a rival pair with no war declares one ({@code warsPerDay}, 0.5, per pair). */
    static double declareChancePerMinute() {
        return DramaKit.prop("wars.warsPerDay", 0.5) / (24 * 60);
    }
}
