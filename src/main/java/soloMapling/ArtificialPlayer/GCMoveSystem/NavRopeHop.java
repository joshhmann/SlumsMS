package soloMapling.ArtificialPlayer.GCMoveSystem;

/**
 * v72 (b356/b360) fallback switch: {@code -Dslums.nav.ropehop=false} makes the planner skip the client-kick rope hops
 * (Edge.clientKickHop) and the executor fly the old rope jump only, with no graph rebuild: the edges stay in the
 * cached v72 graphs, unused. On by default. {@link #set} flips it at runtime (tests, a GM tool).
 */
public final class NavRopeHop {
    public static final String PROPERTY = "slums.nav.ropehop";

    private static volatile boolean on = !"false".equalsIgnoreCase(System.getProperty(PROPERTY));

    private NavRopeHop() {
    }

    public static boolean enabled() {
        return on;
    }

    public static void set(boolean enabled) {
        on = enabled;
    }
}
