package soloMapling.mechanics;

/**
 * {@code opq-lobby} (Orbis PQ stage 4, the lobby 920010400): fetch the day's Ludibrium pass, put it on the music player,
 * and let Eak open the box that holds the third statue piece.
 *
 * <p>Our scripts are the truth:
 * <ul>
 *   <li>{@code scripts/event/OrbisPQ.js:162-163} arms the reactor named {@code music} with {@code new Date().getDay()},
 *       and {@code Reactor.getReactItem(byte)} reads that as a slot index, so the item the music player accepts is
 *       {@code 4001056 + day} - Sunday's pass for a Sunday run.</li>
 *   <li>{@code scripts/reactor/2008006.js} is the whole body {@code act() { rm.getEventInstance().setProperty(
 *       "statusStg3", "0"); }}: the pass has to be <b>dropped</b> on the reactor, which is what
 *       {@link SolverStep.PlaceItem} is for, and {@code MapleMap.activateItemReactors} only arms it when the item id,
 *       the whole quantity and the drop's position all match.</li>
 *   <li>{@code scripts/npc/2013001.js:920010400} answers "find the LP and place it on the music player" while
 *       {@code statusStg3} is -1 and, once it is 0, force-hits {@code stone3}, clears stage 3 and sets the property to
 *       "2" - so {@code statusStg3 == 2} is this stage's clear.</li>
 * </ul>
 *
 * <p>The passes come out of the seven boxes named {@code lp} (reactor ids 2002004..2002010, {@code act() {
 * rm.dropItems(); }}), and which box drops which day is a {@code reactordrops} row nothing in the tree can read. So the
 * party does not guess: it cycles the seven boxes a hit at a time and loots the pass it needs when it lands, which is
 * also what a player does when a box gives the wrong day.
 */
public final class OpqLobbySolver implements StageSolver {

    public static final String ID = "opq-lobby";

    /** 4001056 is Sunday's pass, 4001057 Monday's, ... 4001062 Saturday's: {@code getReactItem(day)} picks one. */
    public static final int SUNDAY_PASS = 4001056;
    public static final int DAYS = 7;
    public static final String MUSIC_REACTOR = "music";
    /** The seven {@code lp} boxes, by reactor id; a hit on any of them runs {@code rm.dropItems()}. */
    public static final int FIRST_BOX = 2002004;
    public static final String STAGE_PROPERTY = "statusStg3";
    static final int EAK_NPC = 2013001;
    /** The script's own gap between talks that is not the party's problem. */
    static final long TALK_GAP_MS = 1_500;
    /** Matches the executor's own reactor gap, so a bot's turn changes only after it has had its hit. */
    static final long BOX_ROTATE_MS = 3_000;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        if (view.cleared()) {
            return new SolverStep.Done();
        }
        int id = member.characterId();
        if (view.mapOf(id) != view.stage().mapId()) {
            return new SolverStep.WarpTo(view.stage().mapId(), "into the Orbis PQ lobby");
        }
        if ("0".equals(view.eventProperty(STAGE_PROPERTY).orElse(null))) {
            // the music player has its pass: the talk is what opens the third-piece box and clears the stage
            return member.leader() ? new SolverStep.TalkNpc(EAK_NPC, 0, 0)
                    : new SolverStep.Wait(TALK_GAP_MS, "Eak is checking the music player");
        }
        int pass = passItem(view);
        if (view.itemCount(id, pass) > 0) {
            return new SolverStep.PlaceItem(pass, 1, MUSIC_REACTOR, 0, 0,
                    "put the day's Ludibrium pass on the music player");
        }
        if (view.floorItems(pass, id) > 0) {
            return new SolverStep.Loot(pass);
        }
        return new SolverStep.HitReactor(box(view, id));
    }

    /**
     * The pass this instance wants: the music reactor's armed slot ({@code 4001056 + getEventState()}). Falls back to
     * the server's own calendar when the reactor is not on the map yet, which is the same clock
     * {@code scripts/event/OrbisPQ.js:162} used to arm it.
     */
    static int passItem(StageView view) {
        int day = view.reactorEventState(MUSIC_REACTOR);
        if (day < 0 || day >= DAYS) {
            day = Math.floorMod(java.time.LocalDate.now().getDayOfWeek().getValue(), DAYS);
        }
        return SUNDAY_PASS + day;
    }

    /**
     * Which {@code lp} box this member hits next: the seven are shared out over the bots in character-id order and the
     * whole handout moves one box every {@link OpqLobbySolver#BOX_ROTATE_MS}, so between them the party opens all seven
     * rather than one bot hammering a box that holds the wrong day.
     */
    static int box(StageView view, int characterId) {
        java.util.List<Integer> bots = new java.util.ArrayList<>();
        for (RunMember m : view.members()) {
            if (m.bot()) {
                bots.add(m.characterId());
            }
        }
        bots.sort(Integer::compareTo);
        int slot = Math.max(0, bots.indexOf(characterId));
        long turn = Math.max(0L, view.nowMs()) / BOX_ROTATE_MS;
        return FIRST_BOX + (int) ((slot + turn) % DAYS);
    }

}
