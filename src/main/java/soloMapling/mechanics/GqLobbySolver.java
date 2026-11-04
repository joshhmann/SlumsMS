package soloMapling.mechanics;

/**
 * {@code gq-lobby} (Guild Quest, the waiting room {@code 990000000}): wait out the strategy time, then go in.
 *
 * <p>The guild's members arrive here one by one while {@code scripts/npc/9040000.js} registers them, and the way on is
 * the portal {@code join00}, whose script refuses until the instance's {@code entryTimestamp} has passed
 * ({@code scripts/portal/guildwaitingenter.js:25-36}: "The portal will open in about N seconds."). The timestamp is set
 * once at setup to {@code now + waitTime} ({@code scripts/event/GuildQuest.js:155-156}, three minutes) and is an event
 * property, so a bot reads it instead of guessing the three minutes, and walks in the moment the portal would let it
 * ({@link SolverStep.ScriptedPortal}: the portal's own script, which does the warp to {@code 990000100}).
 *
 * <p>The stage is cleared when the whole party stands on {@code 990000100} (the row's {@code partyOnMap} clear), so a
 * member that is already through waits there for the rest.
 */
public final class GqLobbySolver implements StageSolver {

    public static final String ID = "gq-lobby";

    /** The portal of the waiting room whose script is {@code guildwaitingenter} (Map9/990000000.img.xml, portal 5). */
    static final String DOOR = "join00";
    /** The event property the script stamps with the moment the strategy time ends, in epoch ms. */
    static final String OPENS = "entryTimestamp";

    @Override
    public String id() {
        return ID;
    }

    /** When the door opens (epoch ms), or -1 when the instance has not written it (or not as a number). */
    static long opensAt(StageView view) {
        return view.eventProperty(OPENS).map(s -> {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }).orElse(-1L);
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int on = view.mapOf(member.characterId());
        if (on != view.stage().mapId()) {
            return new SolverStep.Wait(1_500, "through the door: waiting on the party");
        }
        long opens = opensAt(view);
        if (opens < 0) {
            return new SolverStep.Wait(2_000, "the instance has not written " + OPENS + " yet (scripts/event/GuildQuest.js:155-156)");
        }
        long left = opens - view.nowMs();
        if (left > 0) {
            return new SolverStep.Wait(Math.min(left + 250, 5_000), "the strategy time is not over: the door opens in "
                    + (left + 999) / 1_000 + " s (guildwaitingenter.js:25-36)");
        }
        return new SolverStep.ScriptedPortal(DOOR);
    }
}
