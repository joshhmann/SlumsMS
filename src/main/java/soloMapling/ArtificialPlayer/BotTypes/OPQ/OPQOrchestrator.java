package soloMapling.ArtificialPlayer.BotTypes.OPQ;

import client.Character;
import server.maps.MapleMap;
import server.maps.Reactor;
import soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQSharedContext.OPQPhase;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotGeneration.warpBotToLocation;
import static soloMapling.ArtificialPlayer.BotTypes.OPQ.OPQConstants.STAGE_1_COMPLETE_TP;
import static soloMapling.BotLogger.log;

/**
 * Coordinator for a single OPQ run.
 * <p>
 * Responsibilities:
 * - Register / unregister OPQ bots
 * - Track the party leader and warp bots into Stage 1 when the leader enters
 * (bots have no real client, so the OPQ NPC script does not warp them — the
 * orchestrator must do it server-side)
 * - Hand out unique cloud reactor assignments (closest unclaimed live reactor)
 * - Poll stage completion and flip the shared flags
 * - Reset per-run state between runs
 * <p>
 * Non-responsibilities: ANY fine-grained bot behavior. The orchestrator never
 * tells a bot what state to be in — bots decide their own state from map + the
 * shared context. This is a pure blackboard pattern.
 */
public class OPQOrchestrator {

    private static final long TICK_PERIOD_MS = 1000; // 1 Hz is plenty

    private static final OPQOrchestrator INSTANCE = new OPQOrchestrator();

    public static OPQOrchestrator getInstance() {
        return INSTANCE;
    }

    private final OPQSharedContext context = new OPQSharedContext();
    private final List<OPQBot> registeredBots = new CopyOnWriteArrayList<>();

    // Stage 2: sorted box reactor index per map instance (oid -> sorted position 0-6, right-to-left).
    // Per instance, not per map id: two parties run two copies of 920010400 with different oids. Built lazily on
    // first Stage 2 assignment, cleared on reset.
    private final Map<MapleMap, Map<Integer, Integer>> boxIndexes = new java.util.concurrent.ConcurrentHashMap<>();

    private ScheduledFuture<?> tickHandle;

    private OPQOrchestrator() {
    }

    private static void opqLog(String msg) {
        log("[OPQOrchestrator] " + msg);
    }

    // =========================================================================
    // Public API
    // =========================================================================

    public OPQSharedContext getSharedContext() {
        return context;
    }

    public synchronized void registerBot(OPQBot bot) {
        if (bot == null) return;
        if (!registeredBots.contains(bot)) {
            registeredBots.add(bot);
            opqLog("Registered bot: " + bot.getChr().getName());
        }
        ensureTickRunning();
    }

    public synchronized void unregisterBot(OPQBot bot) {
        if (bot == null) return;
        registeredBots.remove(bot);
        int id = bot.getChr().getId();
        context.putCloudAssignment(id, null);
        context.putBoxAssignment(id, null);
        context.putPlatformAssignment(id, null);
        context.clearTaskComplete(id);
        opqLog("Unregistered bot: " + bot.getChr().getName());
        if (registeredBots.isEmpty()) {
            stopTick();
        }
    }

    /**
     * Bots call this when they find themselves in a party. Cheap; safe to call every tick.
     * <p>
     * There is deliberately no global leader id here any more: two parties can be running two OPQ instances at the
     * same time, and a single locked id warped the second party's bots into the first party's map.
     */
    public void notePartyJoined(OPQBot bot) {
        if (bot.getChr().getParty() == null) return;
        // Auto-activate the run once any bot is partied. Without this the
        // tick would no-op (pqActive=false). Calling !opq start manually still works and is idempotent.
        if (!context.isPqActive()) {
            context.setPqActive(true);
            Character leader = partyLeaderOf(bot.getChr());
            opqLog("Auto-activated run; party leader " + (leader == null ? "unknown" : leader.getName()));
        }
    }

    /**
     * The live leader of the given party member, or null when the member is not in a party or the leader's
     * PartyCharacter is offline (its Character is null then).
     */
    public static Character partyLeaderOf(Character member) {
        if (member == null || member.getParty() == null) return null;
        net.server.world.PartyCharacter leader = member.getParty().getLeader();
        return leader == null ? null : leader.getPlayer();
    }

    /**
     * Reserve a unique cloud reactor for this bot and return its oid.
     * Picks the closest live reactor not already assigned to another bot.
     * Returns null if there are no free live reactors.
     */
    public synchronized Integer assignCloudReactor(OPQBot bot) {
        int id = bot.getChr().getId();
        MapleMap map = bot.getChr().getMap();
        if (map == null) return null;

        // Re-validate any existing assignment first — if dead, drop it and re-pick.
        Integer existing = context.getMyCloudAssignment(id);
        if (existing != null) {
            Reactor r = map.getReactorByOid(existing);
            if (r != null && r.isAlive() && r.getState() < 4) {
                return existing;
            }
            opqLog("Dropping stale cloud assignment oid=" + existing + " for " + bot.getChr().getName());
            context.putCloudAssignment(id, null);
        }

        Collection<Integer> taken = context.getCloudAssignmentsView().values();
        Reactor best = null;
        double bestDist = Double.MAX_VALUE;
        Point botPos = bot.getChr().getPosition();
        for (Reactor r : map.getAllReactors()) {
            if (!isCloudReactor(r)) continue;
            if (!r.isAlive() || r.getState() >= 4) continue;
            if (taken.contains(r.getObjectId())) continue;
            double d = botPos.distance(r.getPosition());
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        if (best == null) {
            opqLog("No free live cloud reactor for " + bot.getChr().getName()
                    + " (alive on map=" + map.getAllReactors().size() + ", taken=" + taken.size() + ")");
            return null;
        }
        int pickedOid = best.getObjectId();
        context.putCloudAssignment(id, pickedOid);
        opqLog("Assigned cloud reactor oid=" + pickedOid + " (id=" + best.getId()
                + " pos=" + best.getPosition() + ") -> " + bot.getChr().getName());
        return pickedOid;
    }

    /**
     * Whether the given map still has at least one cloud reactor (data id in
     * the OPQ stage-1 cloud range) that is alive and not already claimed by
     * another bot. Used by bots to decide whether to loop back for another
     * cloud or transition out of stage-1 work.
     */
    public boolean hasUnclaimedLiveCloudReactor(MapleMap map, int askingBotId) {
        if (map == null) return false;
        Collection<Integer> taken = context.getCloudAssignmentsView().values();
        Integer mine = context.getMyCloudAssignment(askingBotId);
        for (Reactor r : map.getAllReactors()) {
            if (!isCloudReactor(r)) continue;
            if (!r.isAlive() || r.getState() >= 4) continue;
            int oid = r.getObjectId();
            // Don't count reactors taken by *other* bots; the asker's own
            // (already-broken) assignment will get cleared before re-querying.
            if (taken.contains(oid) && (mine == null || mine != oid)) continue;
            return true;
        }
        return false;
    }

    /**
     * Reserve a unique box reactor for this bot and return its oid.
     * Picks the closest live box reactor (state == 0, not claimed by another bot).
     * Skips reactors with state > 0 (may be hit by a real player).
     * Returns null if no free untouched box reactors remain.
     */
    public synchronized Integer assignBoxReactor(OPQBot bot) {
        int id = bot.getChr().getId();
        MapleMap map = bot.getChr().getMap();
        if (map == null) return null;

        buildBoxSortedIndex(map);

        Integer existing = context.getMyBoxAssignment(id);
        if (existing != null) {
            Reactor r = map.getReactorByOid(existing);
            if (r != null && r.isAlive() && r.getState() < 4) {
                return existing;
            }
            opqLog("Dropping stale box assignment oid=" + existing + " for " + bot.getChr().getName());
            context.putBoxAssignment(id, null);
        }

        Collection<Integer> taken = context.getBoxAssignmentsView().values();
        Reactor best = null;
        double bestDist = Double.MAX_VALUE;
        Point botPos = bot.getChr().getPosition();
        for (Reactor r : map.getAllReactors()) {
            if (!isBoxReactor(r)) continue;
            if (!r.isAlive() || r.getState() >= 4) continue;
            // Skip reactors partially hit by someone else (real player)
            if (r.getState() > 0 && !taken.contains(r.getObjectId())) continue;
            if (taken.contains(r.getObjectId())) continue;
            double d = botPos.distance(r.getPosition());
            if (d < bestDist) {
                bestDist = d;
                best = r;
            }
        }
        if (best == null) {
            opqLog("No free box reactor for " + bot.getChr().getName()
                    + " (alive on map=" + map.getAllReactors().size() + ", taken=" + taken.size() + ")");
            return null;
        }
        int pickedOid = best.getObjectId();
        context.putBoxAssignment(id, pickedOid);
        String ordinal = getBoxOrdinal(map, best.getId());
//        opqLog("Assigned box reactor oid=" + pickedOid + " (" + ordinal
//                + " box, id=" + best.getId() + " pos=" + best.getPosition()
//                + ") -> " + bot.getChr().getName());
        return pickedOid;
    }

    /**
     * Whether the given map still has at least one box reactor that is alive,
     * untouched (state == 0), and not claimed by another bot.
     */
    public boolean hasUnclaimedLiveBoxReactor(MapleMap map, int askingBotId) {
        if (map == null) return false;
        Collection<Integer> taken = context.getBoxAssignmentsView().values();
        for (Reactor r : map.getAllReactors()) {
            if (!isBoxReactor(r)) continue;
            if (!r.isAlive() || r.getState() >= 4) continue;
            if (r.getState() > 0 && !taken.contains(r.getObjectId())) continue;
            int oid = r.getObjectId();
            if (taken.contains(oid)) continue;
            return true;
        }
        return false;
    }

    /**
     * Reserve a unique stage-2 platform for this bot and return it.
     */
    public synchronized String assignPlatformTarget(OPQBot bot) {
        int id = bot.getChr().getId();
        String existing = context.getMyPlatformAssignment(id);
        if (existing != null) return existing;

        String pick = pickUnusedString(OPQConstants.STAGE_2_BOX_PLATFORMS,
                context.getPlatformAssignmentsView().values());
        if (pick != null) {
            context.putPlatformAssignment(id, pick);
            opqLog("Assigned box " + pick + " -> " + bot.getChr().getName());
        } else {
            opqLog("No free box platform for " + bot.getChr().getName()
                    + " (pool size=" + OPQConstants.STAGE_2_BOX_PLATFORMS.size()
                    + ", taken=" + context.getPlatformAssignmentsView().size() + ")");
        }
        return pick;
    }

    /**
     * Called externally (e.g. from a debug command) when the leader starts a new run.
     */
    public synchronized void resetForNewRun() {
        opqLog("Resetting for a new run.");
        context.reset();
        context.setPqActive(true);
        context.setCurrentPhase(OPQPhase.RECRUITMENT);
        boxIndexes.clear();
    }

    public void mirrorPhase(OPQPhase phase) {
        OPQPhase prev = context.getCurrentPhase();
        if (prev != phase) {
            context.setCurrentPhase(phase);
            opqLog("Phase mirrored: " + prev + " -> " + phase);
        }
    }

    /**
     * Hard stop — orchestrator idles, bots fall back to INACTIVE.
     */
    public synchronized void shutdownRun() {
        opqLog("Shutting down current run.");
        context.reset();
        boxIndexes.clear();
    }

    // =========================================================================
    // Internal tick
    // =========================================================================

    private void ensureTickRunning() {
        if (tickHandle != null && !tickHandle.isCancelled() && !tickHandle.isDone()) return;
        tickHandle = ExecutorServiceManager.getScheduledExecutorService()
                .scheduleWithFixedDelay(this::safeTick,
                        TICK_PERIOD_MS, TICK_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    private void stopTick() {
        if (tickHandle != null) {
            tickHandle.cancel(false);
            tickHandle = null;
        }
    }

    private void safeTick() {
        if (BotShutdown.isQuiesced()) {
            stopTick(); // Ticket 09: the run is over - cancel this instance's loop at the next tick
            return;
        }
        try {
            tick();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Tick responsibilities:
     * 1. Drag bots into Stage 1 when the leader's mapId reads OPQ_STAGE_1.
     * 2. Flip stage-complete flags based on the leader's observed state.
     * <p>
     * Stage 1 completion: bots are not the trigger — they only know they've
     * dropped their clouds. The real "done" signal is the NPC handin, which
     * teleports the leader up to the top platform. We poll the leader's
     * position and flip stage1Complete once they land in the tolerance box.
     */
    private void tick() {
        if (!context.isPqActive()) return;

        if (allBotsLostParty()) {
            opqLog("All registered bots lost their party — resetting run.");
            resetForNewRun();
            return;
        }

        OPQPhase phase = context.getCurrentPhase();
        switch (phase) {
            case STAGE_1 -> {
                for (Character leader : leadersOf(botCharacters())) {
                    MapleMap runMap = leader.getMap();
                    if (runMap == null || context.isStage1Complete(runMap)) continue;
                    if (!isAtStageOneExit(leader.getMapId(), leader.getPosition())) continue;
                    opqLog("Stage 1 complete in map " + leader.getMapId()
                            + " — leader " + leader.getName() + " reached the Chamberlain Eak top platform");
                    context.setStage1Complete(runMap, true);
                    // Each stage starts with a clean "my part is done" bit: stage 1 sets it when the bot drops
                    // its clouds, and a stale bit would let stage 2 look cleared before a single box was broken.
                    clearTaskBitsIn(runMap);
                }
            }
            case STAGE_2 -> {
                List<Character> members = botCharacters();
                for (Character leader : leadersOf(members)) {
                    MapleMap runMap = leader.getMap();
                    if (runMap == null || context.isStage2Complete(runMap)) continue;
                    if (!allCompleteIn(runMap, members, context::isMyTaskComplete)) continue;
                    opqLog("Stage 2 complete in map " + leader.getMapId()
                            + " — every bot in the instance reported done.");
                    context.setStage2Complete(runMap, true);
                }
            }
            default -> { /* no-op */ }
        }
    }

    /**
     * One party leader per party that has a member inside an OPQ map, in registration order. Every instance is
     * polled on its own: a party that finishes stage 1 must not decide anything for the party in the copy next
     * door, and a party that is stuck in the tower or the lobby is not polled at all.
     */
    static List<Character> leadersOf(Collection<Character> members) {
        List<Character> leaders = new ArrayList<>();
        for (Character member : members) {
            if (member == null || member.getMap() == null) continue;
            if (!OPQConstants.OPQ_INSIDE_MAPS.contains(member.getMapId())) continue;
            Character leader = partyLeaderOf(member);
            if (leader == null) continue;
            boolean seen = false;
            for (Character known : leaders) {
                if (known == leader) {
                    seen = true;
                    break;
                }
            }
            if (!seen) leaders.add(leader);
        }
        return leaders;
    }

    /**
     * Stage 1 is cleared when the leader has been teleported up to the top platform by Chamberlain Eak: that
     * platform is only reachable through the NPC handin. The old check asked whether the NPC was on the leader's
     * map - it always is - so "stage 1 complete" was true from the first second and bots abandoned the cloud work
     * the leader still needed.
     */
    static boolean isAtStageOneExit(int mapId, Point pos) {
        if (mapId != OPQConstants.OPQ_STAGE_1 || pos == null) return false;
        return Math.abs(pos.x - OPQConstants.STAGE_1_TOP_PLATFORM_X) <= OPQConstants.STAGE_1_TOP_PLATFORM_TOL_X_PX
                && Math.abs(pos.y - OPQConstants.STAGE_1_TOP_PLATFORM_Y) <= OPQConstants.STAGE_1_TOP_PLATFORM_TOL_Y_PX;
    }

    /**
     * Called by a bot that is about to warp into stage 1. The first bot of a run clears the previous run's stage
     * flags; later bots of the same run leave them alone.
     */
    public synchronized void noteEnteringStageOne(OPQBot bot) {
        if (anyBotInsidePq()) return;
        context.clearCompletionFlags();
        clearTaskBitsForRegistered();
        opqLog("New run: stage flags cleared for " + bot.getChr().getName());
    }

    private boolean anyBotInsidePq() {
        for (OPQBot bot : registeredBots) {
            Character member = bot.getChr();
            if (member.getMap() != null && OPQConstants.OPQ_INSIDE_MAPS.contains(member.getMapId())) return true;
        }
        return false;
    }


    /**
     * If the leader has entered Stage 1, drag every registered bot in with
     * 1. Leader entered via the OPQ NPC
     * 2. Leader completes stage 1 by talking to NPC, who teleports him to the tower
     */
    public void followLeaderWarp(Character fakechar, Point pt) {
        MapleMap target = leaderMapFor(fakechar);
        if (target == null) {
            opqLog("No live party leader for " + fakechar.getName() + " - warp to " + pt + " skipped");
            return;
        }
        try {
            warpBotToLocation(fakechar, pt, target);
        } catch (Exception e) {
            opqLog("Warp failed for " + fakechar.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * The map a follow-warp for this bot goes to: its own party leader's map. Never a global leader - two parties
     * can run two OPQ instances at once, and the old single locked leader id warped one party's bots into the other
     * party's instance. Null when the bot has no live leader (no party, or the leader went offline).
     */
    MapleMap leaderMapFor(Character member) {
        Character leader = partyLeaderOf(member);
        return leader == null ? null : leader.getMap();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean allBotsLostParty() {
        if (registeredBots.isEmpty()) return false;
        for (OPQBot bot : registeredBots) {
            if (bot.getChr().getParty() != null) return false;
        }
        return true;
    }

    /**
     * Stage 2 is done when every bot that is in this instance has reported its box. The old test wanted every
     * registered bot - the lobby's 10-15 idle recruiters included - so the flag never flipped, STAGE_2_WAIT ran
     * into its two-minute cap and bots walked out of a run that was still going.
     */
    static boolean allCompleteIn(MapleMap runMap, Collection<Character> members, java.util.function.IntPredicate done) {
        if (runMap == null) return false;
        int inInstance = 0;
        for (Character member : members) {
            if (member.getMap() != runMap) continue;
            inInstance++;
            if (!done.test(member.getId())) return false;
        }
        return inInstance > 0;
    }

    private List<Character> botCharacters() {
        List<Character> members = new ArrayList<>(registeredBots.size());
        for (OPQBot bot : registeredBots) {
            members.add(bot.getChr());
        }
        return members;
    }

    /** Party leaders of the runs inside an OPQ instance right now, one per party. Empty when nobody is in. */
    public List<Character> activeRunLeaders() {
        return leadersOf(botCharacters());
    }

    /** Bots standing in this map instance start the next stage with a clean "my part is done" bit. */
    private void clearTaskBitsIn(MapleMap runMap) {
        if (runMap == null) return;
        for (OPQBot bot : registeredBots) {
            if (bot.getChr().getMap() == runMap) {
                context.clearTaskComplete(bot.getChr().getId());
            }
        }
    }

    private void clearTaskBitsForRegistered() {
        for (OPQBot bot : registeredBots) {
            context.clearTaskComplete(bot.getChr().getId());
        }
    }

    private static boolean isCloudReactor(Reactor r) {
        return r.getId() == OPQConstants.STAGE_1_CLOUD_REACTOR_ID;
    }

    private static boolean isBoxReactor(Reactor r) {
        int id = r.getId();
        return id >= OPQConstants.STAGE_2_BOX_REACTOR_FIRST
                && id <= OPQConstants.STAGE_2_BOX_REACTOR_LAST;
    }

    /**
     * Build the sorted index for box reactors on the given map.
     * Sorts by x-position descending (rightmost = index 0 = "1st").
     * Called lazily on first Stage 2 assignment.
     */
    private Map<Integer, Integer> buildBoxSortedIndex(MapleMap map) {
        return boxIndexes.computeIfAbsent(map, m -> {
            Map<Integer, Integer> index = new HashMap<>();
            List<Reactor> boxes = new ArrayList<>();
            for (Reactor r : m.getAllReactors()) {
                if (isBoxReactor(r)) boxes.add(r);
            }
            boxes.sort(Comparator.comparingInt((Reactor r) -> r.getPosition().x).reversed());
            for (int i = 0; i < boxes.size(); i++) {
                index.put(boxes.get(i).getObjectId(), i);
                opqLog("Box sorted index: pos=" + i + " (" + OPQConstants.STAGE_2_BOX_ORDINALS[i]
                        + ") oid=" + boxes.get(i).getObjectId()
                        + " dataId=" + boxes.get(i).getId()
                        + " x=" + boxes.get(i).getPosition().x);
            }
            return index;
        });
    }

    /**
     * Get the ordinal label for a box reactor based on its sorted position (right-to-left).
     */
    public String getBoxOrdinal(MapleMap map, int reactorOid) {
        Integer idx = map == null ? null : buildBoxSortedIndex(map).get(reactorOid);
        if (idx != null && idx >= 0 && idx < OPQConstants.STAGE_2_BOX_ORDINALS.length) {
            return OPQConstants.STAGE_2_BOX_ORDINALS[idx];
        }
        return "?";
    }

    /**
     * Get the deterministic item ID that a box reactor should drop, based on sorted position.
     * Box at sorted index 0 (rightmost/"1st") drops STAGE_2_ITEMS[0] = 4001056, etc.
     */
    public int getBoxItemId(MapleMap map, int reactorOid) {
        Integer idx = map == null ? null : buildBoxSortedIndex(map).get(reactorOid);
        if (idx != null && idx >= 0 && idx < OPQConstants.STAGE_2_ITEMS.size()) {
            return OPQConstants.STAGE_2_ITEMS.get(idx);
        }
        return OPQConstants.STAGE_2_ITEMS.get(0);
    }

    /**
     * Find the music box reactor (central drop zone) on the given map and return its position.
     */
    public static Point getMusicBoxPosition(MapleMap map) {
        if (map == null) return null;
        for (Reactor r : map.getAllReactors()) {
            if (r.getId() == OPQConstants.STAGE_2_MUSIC_BOX_REACTOR_ID) {
                return r.getPosition();
            }
        }
        return null;
    }

    private static String pickUnusedString(List<String> pool, Iterable<String> used) {
        Deque<String> taken = new ArrayDeque<>();
        used.forEach(taken::add);
        for (String candidate : pool) {
            if (!taken.contains(candidate)) return candidate;
        }
        return null;
    }
}
