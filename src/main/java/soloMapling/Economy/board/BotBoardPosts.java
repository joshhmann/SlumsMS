package soloMapling.Economy.board;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * A few merchant and greedy bots post on the board too (owner, 2026-10-26): real offers backed by what they hold (the
 * item for a WTS, the mesos for a WTB), priced inside the bots' bands. Between {@link #MIN} and {@link #MAX} are open
 * server-wide (a new target every 6 hours); each lives 6 to 48 hours, goes when the bot logs off or no longer has the
 * item or the mesos, and at most {@link #PER_REFRESH} new ones appear per 15-minute run, so they trickle in. They are
 * listed with the players' posts, under the bot's name, with nothing marking them as bots. Not saved: after a restart
 * the bots post afresh.
 */
public final class BotBoardPosts {

    public static final int MIN = 5;
    public static final int MAX = 15;
    static final int PER_REFRESH = 3;
    static final long TARGET_MS = TimeUnit.HOURS.toMillis(6);
    static final long LIFE_MIN_MS = TimeUnit.HOURS.toMillis(6);
    static final long LIFE_MAX_MS = TimeUnit.HOURS.toMillis(48);
    private static final char[] ID_CHARS = "0123456789abcdef".toCharArray();

    /** Something a bot could post: {@code unitPrice} already inside its band. */
    public record Candidate(int botId, String botName, Board.Kind kind, int itemId, int qty, long unitPrice) {
    }

    private final List<Board.Post> posts = new ArrayList<>();
    private int target;
    private long targetUntil;

    /** Drops expired, removed and no-longer-backed posts and tops the board up. Returns the open bot posts. */
    public synchronized List<Board.Post> refresh(BoardWorld world, Set<String> removed) {
        long now = world.now();
        posts.removeIf(p -> p.expiresAt() <= now || removed.contains(p.id()) || !backed(world, p));
        if (now >= targetUntil) {
            target = world.between(MIN, MAX);
            targetUntil = now + TARGET_MS;
        }
        if (posts.size() < target) {
            List<Candidate> cands = new ArrayList<>(world.botPostCandidates());
            Collections.shuffle(cands, new Random((long) (world.roll() * Long.MAX_VALUE)));
            Set<Integer> bots = new HashSet<>();
            Set<String> items = new HashSet<>();
            for (Board.Post p : posts) {
                bots.add(p.characterId());
                items.add(p.kind() + ":" + p.itemId());
            }
            int added = 0;
            // alternate WTS and WTB so the board is a mix
            Board.Kind want = posts.stream().filter(p -> p.kind() == Board.Kind.WTS).count() * 2 > posts.size()
                    ? Board.Kind.WTB : Board.Kind.WTS;
            for (int pass = 0; pass < 2 && posts.size() < target && added < PER_REFRESH; pass++) {
                for (Candidate c : cands) {
                    if (posts.size() >= target || added >= PER_REFRESH) {
                        break;
                    }
                    if (pass == 0 && c.kind() != want || c.unitPrice() <= 0 || c.qty() <= 0
                            || bots.contains(c.botId()) || !items.add(c.kind() + ":" + c.itemId())) {
                        continue;
                    }
                    long life = world.between((int) (LIFE_MIN_MS / 60_000), (int) (LIFE_MAX_MS / 60_000)) * 60_000L;
                    Board.Post post = new Board.Post(newId(world), 0, c.botName(), c.botId(), c.kind(), c.itemId(),
                            c.qty(), c.unitPrice(), now, now + life, true);
                    if (!backed(world, post)) {
                        continue;
                    }
                    bots.add(c.botId());
                    posts.add(post);
                    added++;
                    want = want == Board.Kind.WTS ? Board.Kind.WTB : Board.Kind.WTS;
                }
            }
        }
        return List.copyOf(posts);
    }

    public synchronized List<Board.Post> open() {
        return List.copyOf(posts);
    }

    public synchronized boolean remove(String id) {
        return posts.removeIf(p -> p.id().equals(id));
    }

    private static boolean backed(BoardWorld world, Board.Post p) {
        try {
            return world.stillBacks(p);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** An id of the site's own form: "p" and 12 hex digits. */
    static String newId(BoardWorld world) {
        StringBuilder sb = new StringBuilder(13).append('p');
        for (int i = 0; i < 12; i++) {
            sb.append(ID_CHARS[world.between(0, ID_CHARS.length - 1)]);
        }
        return sb.toString();
    }
}
