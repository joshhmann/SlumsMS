package soloMapling.qa;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * {@code !qa run mv-1}: how far a character moves while one of its attacks plays. Pure (no game classes), fed by
 * {@link MoveTrace} with the GM's movement fragments and attacks, so the tests drive it with plain numbers.
 * <p>
 * Timing: a movement packet arrives at {@code now} and carries fragments that each lasted {@code durMs}; fragment
 * {@code i} ended at {@code now - (durations of the fragments after i)}, and its x/y are where it ended. An attack at
 * time {@code t0} from position {@code (x0, y0)} looks at the fragments that ended in {@code (t0, t0 + window]}. Its
 * result is ready once {@code window + GRACE_MS} has passed (the client sends movement in batches), or at stop.
 * <p>
 * Stance byte (v83): {@code stance >> 1} is the move action (1 walk, 2 stand, 3 jump/fall, 4 alert, 5 prone,
 * 6 fly, 7 ladder, 8 rope), bit 0 is facing left. A walk stance is the client saying a direction key is held.
 */
public final class SlideMeter {

    public static final int DEFAULT_WINDOW_MS = 600;
    public static final int DEFAULT_ATTACKS = 20;
    public static final long GRACE_MS = 1000;
    static final int MAX_SAMPLES = 4000;
    static final long KEEP_MS = 10_000;

    /** Movement fragment types (the command byte) that are a jump or a knockback, not ground movement. */
    static final int TYPE_JUMP = 1, TYPE_KNOCKBACK = 2, TYPE_FLASH_JUMP = 6, TYPE_JUMP_DOWN = 15;

    /** One movement fragment as the packet carries it. Relative fragments (jumps) have no x/y/fh: absolute=false. */
    public record Frag(int type, int durMs, boolean absolute, int x, int y, int xv, int yv, int fh, int stance) {
        public static Frag abs(int type, int durMs, int x, int y, int xv, int yv, int fh, int stance) {
            return new Frag(type, durMs, true, x, y, xv, yv, fh, stance);
        }

        public static Frag rel(int type, int durMs, int stance) {
            return new Frag(type, durMs, false, 0, 0, 0, 0, -1, stance);
        }
    }

    /** A fragment on the timeline: {@code t} is when it ended (server clock, ms). */
    public record Sample(long t, Frag f) {
    }

    /**
     * One attack: when, which skill and route, where the character stood (last known position), its stance and
     * speed, and the foothold of the last absolute fragment before it (-1 = none seen yet, 0 = airborne).
     */
    public record Attack(int n, long t, int skill, String route, int x0, int y0, int stance0, int speed, int fh0,
                         int windowMs) {
    }

    /** What one attack's window showed. */
    public record Result(Attack attack, int samples, int maxSlide, int netDx, int dy, int maxAbsXv,
                         boolean walkSeen, boolean airborne, boolean directionHeld, boolean grounded) {
    }

    /** All attacks so far. */
    public record Summary(int count, int grounded, double avgSlide, int maxSlide, double avgGroundedSlide,
                          int held, int heldGroundedSliding) {
    }

    private final int windowMs;
    private final int maxAttacks;
    private final Deque<Sample> timeline = new ArrayDeque<>();
    private final List<Attack> pending = new ArrayList<>();
    private final List<Result> results = new ArrayList<>();
    private Sample lastAbsolute;
    private int attacks;
    private boolean summarized;

    public SlideMeter(int windowMs, int maxAttacks) {
        this.windowMs = Math.max(1, windowMs);
        this.maxAttacks = Math.max(1, maxAttacks);
    }

    public int windowMs() {
        return windowMs;
    }

    public int maxAttacks() {
        return maxAttacks;
    }

    /** A movement packet that arrived at {@code now}. */
    public synchronized void move(long now, List<Frag> frags) {
        long total = 0;
        for (Frag f : frags) {
            total += Math.max(0, f.durMs());
        }
        long t = now - total;
        for (Frag f : frags) {
            t += Math.max(0, f.durMs());
            Sample s = new Sample(t, f);
            timeline.addLast(s);
            if (f.absolute()) {
                lastAbsolute = s;
            }
        }
        long keepFrom = (pending.isEmpty() ? now : Math.min(now, pending.get(0).t())) - KEEP_MS;
        while (!timeline.isEmpty() && (timeline.peekFirst().t() < keepFrom || timeline.size() > MAX_SAMPLES)) {
            timeline.removeFirst();
        }
    }

    /** An attack at {@code now} from the character's last known position; false once the meter has all it wants. */
    public synchronized boolean attack(long now, int skill, String route, int x, int y, int stance, int speed) {
        if (attacks >= maxAttacks) {
            return false;
        }
        int fh0 = lastAbsolute == null ? -1 : lastAbsolute.f().fh();
        pending.add(new Attack(++attacks, now, skill, route, x, y, stance, speed, fh0, windowMs));
        return true;
    }

    /** Results whose window (plus the grace for late movement packets) has passed, oldest first. */
    public synchronized List<Result> due(long now) {
        List<Result> out = new ArrayList<>();
        Iterator<Attack> it = pending.iterator();
        while (it.hasNext()) {
            Attack a = it.next();
            if (a.t() + a.windowMs() + GRACE_MS <= now) {
                it.remove();
                Result r = analyze(a, timeline);
                results.add(r);
                out.add(r);
            }
        }
        return out;
    }

    /** Every pending result now (at stop), whatever its window. */
    public synchronized List<Result> flush() {
        return due(Long.MAX_VALUE / 2);
    }

    /** True once the meter took all its attacks and reported them. */
    public synchronized boolean finished() {
        return attacks >= maxAttacks && pending.isEmpty();
    }

    /** True the first time only: so the summary is shown once (when the attacks run out, or at stop). */
    public synchronized boolean takeSummary() {
        if (summarized) {
            return false;
        }
        summarized = true;
        return true;
    }

    public synchronized List<Result> results() {
        return List.copyOf(results);
    }

    public synchronized Summary summary() {
        return summarize(results);
    }

    // ---- the analysis ----

    static boolean isWalk(int stance) {
        return (stance >> 1) == 1;
    }

    static boolean isJumpStance(int stance) {
        return (stance >> 1) == 3;
    }

    /** One attack's window on a timeline of fragments. */
    public static Result analyze(Attack a, Collection<Sample> timeline) {
        long from = a.t(), to = a.t() + a.windowMs();
        int samples = 0, maxSlide = 0, maxAbsXv = 0;
        Sample lastAbs = null;
        boolean walk = isWalk(a.stance0());
        boolean air = a.fh0() == 0 || isJumpStance(a.stance0());
        for (Sample s : timeline) {
            if (s.t() <= from || s.t() > to) {
                continue;
            }
            Frag f = s.f();
            samples++;
            walk |= isWalk(f.stance());
            air |= isJumpStance(f.stance()) || f.type() == TYPE_JUMP || f.type() == TYPE_KNOCKBACK
                    || f.type() == TYPE_FLASH_JUMP || f.type() == TYPE_JUMP_DOWN;
            if (f.absolute()) {
                maxSlide = Math.max(maxSlide, Math.abs(f.x() - a.x0()));
                maxAbsXv = Math.max(maxAbsXv, Math.abs(f.xv()));
                air |= f.fh() == 0;
                lastAbs = s;
            }
        }
        int netDx = lastAbs == null ? 0 : lastAbs.f().x() - a.x0();
        int dy = lastAbs == null ? 0 : lastAbs.f().y() - a.y0();
        boolean grounded = a.fh0() > 0 && !isJumpStance(a.stance0());
        return new Result(a, samples, maxSlide, netDx, dy, maxAbsXv, walk, air, walk, grounded);
    }

    public static Summary summarize(List<Result> results) {
        int n = results.size(), grounded = 0, max = 0, held = 0, heldSliding = 0;
        long sum = 0, groundedSum = 0;
        for (Result r : results) {
            sum += r.maxSlide();
            max = Math.max(max, r.maxSlide());
            if (r.directionHeld()) {
                held++;
            }
            if (r.grounded()) {
                grounded++;
                groundedSum += r.maxSlide();
                if (r.directionHeld() && r.maxSlide() > 0) {
                    heldSliding++;
                }
            }
        }
        return new Summary(n, grounded, n == 0 ? 0 : (double) sum / n, max,
                grounded == 0 ? 0 : (double) groundedSum / grounded, held, heldSliding);
    }

    /** The GM's line for one attack. */
    public static String format(Result r) {
        Attack a = r.attack();
        String fh = a.fh0() < 0 ? "fh ?" : a.fh0() == 0 ? "fh 0 (air)" : "fh " + a.fh0();
        String moves = r.samples() == 0 ? "no movement sent (stood still)" : r.samples() + " frags";
        return "#" + a.n() + " skill " + a.skill() + " " + a.route() + ": slide " + r.maxSlide() + "px (net "
                + signed(r.netDx()) + "), dy " + signed(r.dy()) + ", xv max " + r.maxAbsXv() + ", speed " + a.speed()
                + ", " + fh + (r.grounded() ? "" : " not grounded") + (r.airborne() ? ", jumped/air" : "")
                + ", held " + (r.directionHeld() ? "yes" : "no") + ", " + moves + " in " + a.windowMs() + "ms";
    }

    /** The GM's lines for the summary. */
    public static List<String> format(Summary s) {
        if (s.count() == 0) {
            return List.of("mv-1 summary: no attacks measured.");
        }
        return List.of(
                String.format(Locale.ROOT, "mv-1 summary: %d attacks (%d grounded): slide avg %.1fpx, max %dpx; "
                        + "grounded avg %.1fpx", s.count(), s.grounded(), s.avgSlide(), s.maxSlide(),
                        s.avgGroundedSlide()),
                String.format(Locale.ROOT, "direction held in %d/%d (%.0f%%); grounded, held and sliding: %d",
                        s.held(), s.count(), 100.0 * s.held() / s.count(), s.heldGroundedSliding()));
    }

    private static String signed(int v) {
        return v > 0 ? "+" + v : String.valueOf(v);
    }
}
