package soloMapling.mechanics;

import java.util.List;

/**
 * The Pre-Zakum mines: thirty rooms on a hub, a chest in most of them, and one hand-in at the end.
 *
 * <p>Everything the solver decides is read from the row, because the mines are data: a room's chest is the stage's
 * {@link MechanicsModel.Stage#hitReactors() hitReactors}, the big chest and the keys it wants are constants of the chest
 * that exists once in the whole maze ({@code 2112014} on {@code 280011005}), and <b>where the party goes next is the
 * stage's own clear</b> - {@code clear.partyOnMap} names the map the party walks into, so the solver leaves through
 * whichever door on this map leads there ({@link SolverStep.EnterPortalTo}). That is why a maze of a hub and fifteen
 * numbered doors needs no route in Java: the row is the route.
 *
 * <p>The chest arithmetic is settled offline and is worth stating where the code that relies on it lives:
 * {@code reactordrops} has {@code 2112012}/{@code 2112005} -> {@code 4001015} (Paper Document), {@code 2112004}/
 * {@code 2112011} -> {@code 4001016} (The Key), {@code 2112014} -> {@code 4001018} (Fire Ore), all at chance 1 - the
 * names are String.wz's (1.6.6 zpq-fire-ore-name: the code had them as key, ore and document). A chest yields its
 * item on the fourth and fifth hit and then goes permanently inert ({@code Reactor.java:246-315}: the end-state branch
 * and the following {@code -1}-type branch are the two hits, and state 5 is not active), which is why the solver keeps
 * hitting until the reactor answers {@code -1} rather than counting hits.
 *
 * <p>Nothing here checks damage, DPS or the party's size: the script has no such check either -
 * {@code scripts/npc/2032002.js:71-127} asks for {@code 4001018}, and the thirty Paper Documents are optional and only
 * change the message and the reward.
 */
public final class ZakumMinesSolver implements StageSolver {

    /** The Fire Ore Aura takes; it comes out of the one big chest, {@code 2112014} on {@code 280011005}. */
    static final int FIRE_ORE = 4001018;
    /** The Key the big chest takes: {@code Reactor.wz/2112014} event 0 is {@code type=100, 0=4001016, 1=7}. */
    static final int KEY = 4001016;
    static final int KEYS_NEEDED = 7;
    static final int BIG_CHEST = 2112014;
    /** Adobis's hand-in clerk, standing on the hub. */
    static final int CLERK = 2032002;
    static final int HUB = 280010000;
    /** b344e: what a real leader is told when a regular drops the Fire Ore for them ({@code {name}}: the regular). */
    static final String HAND_IN_NOTE = "[Zakum] {name} dropped the Fire Ore at your feet. Pick it up and bring it to Aura.";
    /**
     * b344e: how long keys lying in front of a closed big chest are left for the server to take. It takes an item
     * reactor's drop 5 s after it lands ({@code MapleMap.activateItemReactors}); past this window the drop did not take.
     */
    static final long KEY_TAKE_WINDOW_MS = 10_000;
    /**
     * zpq-stage33-bigchest (1.6.6): how long the party may wait at a closed big chest for keys before the stage is given
     * up and left to the players. The same 3 minutes as the no-progress guard ({@code Orchestrator.NO_PROGRESS_MS}),
     * which never counts a waiting bot: QA 8 Nov waited 270 s on keys split 0/3/0/2/2 and only the PQ's 30 min ended it.
     */
    static final long KEY_WAIT_MAX_MS = 180_000;
    private static final String WAIT_SINCE = "bigChestWaitSinceMs";
    private static final String OPENED_SAID = "bigChestOpenedSaid";

    @Override
    public String id() {
        return "zakum-mines";
    }

    @Override
    public SolverStep next(StageView view, RunMember member) {
        int mapId = view.stage().mapId();
        int mine = view.mapOf(member.characterId());
        boolean leader = view.leader().map(l -> l.characterId() == member.characterId()).orElse(false);

        if (view.cleared()) {
            return new SolverStep.Done();
        }
        if (mine != mapId) {
            // the party is between two rooms; the previous stage's clear put it here, so this is a moment, not a fault
            return new SolverStep.Wait(1_000, "the door has not finished taking me to the mine");
        }
        if (isHandIn(view.stage())) {
            int held = view.itemCount(member.characterId(), FIRE_ORE);
            // b344e: Aura takes the fire ore only from the event leader (2032002.js:67-71). When that is a real player,
            // the regular holding it walks it over and drops it at his feet, as a party member would.
            RunMember lead = view.leader().orElse(null);
            boolean realLeader = lead != null && !lead.bot();
            if (held <= 0) {
                if (realLeader) {
                    return new SolverStep.Wait(2_000, "the fire ore is the leader's to bring to Aura");
                }
                return new SolverStep.Abandon("no Fire Ore: the big chest on 280011005 was never opened");
            }
            if (!leader) {
                if (realLeader && view.mapOf(lead.characterId()) == mapId) {
                    return new SolverStep.DropFor(FIRE_ORE, held, lead.characterId(), HAND_IN_NOTE);
                }
                return new SolverStep.Wait(1_500, "the hand-in is the leader's");
            }
            return new SolverStep.TalkNpc(CLERK, FIRE_ORE, 1);
        }

        // a chest that has been hit five times answers -1 for ever; one that was never here also answers -1
        List<Integer> chests = view.stage().hitReactors();
        int chest = chests.isEmpty() ? 0 : chests.get(0);
        // 1.6.5 b331: and one in a state no client can hit is spent too (2112007's state 4); hitting it crashed clients
        boolean chestLive = chest > 0 && view.reactorHittable(chest);
        int floor = view.floorItems(0, member.characterId());

        // b344e: only a closed chest (state 0) wants keys. Once the seven took (state 1, the Fire Ore dropped) or it is
        // gone (-1), the room is an ordinary one: loot the floor, then the door. TEST 7 Nov stage 33 sat after its
        // drop saying "I have 0" for good, because this wait came before everything else.
        if (chest == BIG_CHEST && view.reactorState(BIG_CHEST) == 0) {
            int keys = view.itemCount(member.characterId(), KEY);
            String key = "bigChestPlacedMs:" + member.characterId();
            if (keys >= KEYS_NEEDED) {
                view.stageMemory().put(key, view.nowMs());
                return new SolverStep.PlaceItemOn(BIG_CHEST, KEY, KEYS_NEEDED,
                        "the seven keys the big chest wants");
            }
            Object placed = view.stageMemory().get(key);
            if (floor > 0 && placed instanceof Long at && view.nowMs() - at < KEY_TAKE_WINDOW_MS) {
                return new SolverStep.Wait(1_500, "the chest takes the keys a few seconds after they land");
            }
            // zpq-stage33-bigchest: the chest takes ONE drop of exactly seven, so keys split across regulars' bags are
            // pooled on the one holding most (ties: lowest id), through the same hand-over KPQ passes use
            // (PassHandover.mayTake keeps it the carrier's). A real player's keys are never asked for.
            if (keys > 0 && floor <= 0) {
                int carrier = carrier(view, mapId);
                if (carrier > 0 && carrier != member.characterId()) {
                    return new SolverStep.DropFor(KEY, keys, carrier, null);
                }
            }
            if (floor <= 0) {
                Object since = view.stageMemory().putIfAbsent(WAIT_SINCE, view.nowMs());
                long from = since instanceof Long at ? at : view.nowMs();
                if (view.nowMs() - from >= KEY_WAIT_MAX_MS) {
                    return new SolverStep.Abandon("the big chest on 280011005 still wants keys after "
                            + KEY_WAIT_MAX_MS / 1000 + " s: the party has " + partyKeys(view) + " of the "
                            + KEYS_NEEDED + " keys the big chest wants");
                }
                return new SolverStep.Wait(1_500, "this chest wants seven keys and I have " + keys
                        + ": the keys are in the rooms we have already walked");
            }
            // keys on the floor past the window did not take: pick them up and place again (PLACE_ON_TRIES bounds it)
            return new SolverStep.Loot(0);
        }
        if (floor > 0) {
            return new SolverStep.Loot(0);
        }
        if (chestLive) {
            return new SolverStep.HitReactor(chest);
        }
        int next = view.stage().clear().partyOnMap();
        if (view.stage().clear().onInstance() || next <= 0) {
            return new SolverStep.Wait(2_000, "the room is spent: waiting at the door");
        }
        return new SolverStep.EnterPortalTo(next);
    }

    /** The regular on this map holding the most keys (ties: the lowest id); 0 when no regular here holds one. */
    static int carrier(StageView view, int mapId) {
        int best = 0;
        int bestKeys = 0;
        for (RunMember m : view.members()) {
            if (!m.bot() || view.mapOf(m.characterId()) != mapId) {
                continue;
            }
            int k = view.itemCount(m.characterId(), KEY);
            if (k > bestKeys || k == bestKeys && k > 0 && m.characterId() < best) {
                best = m.characterId();
                bestKeys = k;
            }
        }
        return best;
    }

    /** Keys the run's regulars hold between them (a real player's are his own and not counted). */
    static int partyKeys(StageView view) {
        int n = 0;
        for (RunMember m : view.members()) {
            if (m.bot()) {
                n += view.itemCount(m.characterId(), KEY);
            }
        }
        return n;
    }

    /**
     * Observe-only feed line, in the wording of Server Dev's boss-feed-lines plan (one line per event, matched by
     * work-62's stage-33 checker): the big chest left state 0, so the seven keys took and the Fire Ore dropped. Said
     * once per stage; null otherwise and for every other room. {@code by}: who placed the keys; {@code afterS}: seconds
     * since the run started; {@code tag}: the run tag, " [zakumpq, leader X]".
     */
    static String chestOpenedLine(StageView view, String by, long afterS, String tag) {
        if (view == null || view.stage() == null || !view.stage().hitReactors().contains(BIG_CHEST)
                || view.reactorState(BIG_CHEST) < 1
                || view.stageMemory().putIfAbsent(OPENED_SAID, Boolean.TRUE) != null) {
            return null;
        }
        return "RUN zakumpq big chest " + BIG_CHEST + " opened on " + view.stage().mapId() + " by " + by + " after "
                + afterS + " s" + tag;
    }

    /** Who placed the keys on the big chest this stage (the latest placer), or 0. */
    static int placerId(StageView view) {
        int who = 0;
        long latest = Long.MIN_VALUE;
        for (java.util.Map.Entry<String, Object> e : view.stageMemory().entrySet()) {
            if (e.getKey().startsWith("bigChestPlacedMs:") && e.getValue() instanceof Long at && at > latest) {
                latest = at;
                who = Integer.parseInt(e.getKey().substring("bigChestPlacedMs:".length()));
            }
        }
        return who;
    }

    /** The hand-in stage is the one whose clear is the instance's, not a map's. */
    static boolean isHandIn(MechanicsModel.Stage stage) {
        return stage.clear() != null && stage.clear().onInstance();
    }
}
