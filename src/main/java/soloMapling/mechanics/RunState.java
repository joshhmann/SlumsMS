package soloMapling.mechanics;

import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * One run's blackboard (ticket 130): who is in it, the current stage and its role claims, each bot's planned wrong
 * tries, what happened. Keyed per run (per event instance), never per map id: two parties in two copies of the same
 * map are two runs. Written by the {@link Orchestrator} on the run's tick thread, read by views and status commands.
 */
public final class RunState implements RunOrchestrator.Run {

    /** One thing that happened, for the GM status line and the QA log. */
    public record Event(long atMs, String line) {
    }

    private final Content content;
    private final Tier tier;
    private final long budgetMs;
    private final int lastStage;
    private final List<RunMember> members;
    private final Map<Integer, List<Integer>> wrongTries = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> wrongShown = new ConcurrentHashMap<>();
    private final List<Event> events = Collections.synchronizedList(new ArrayList<>());
    private volatile int stage = 1;
    private volatile RoleClaims.Claims claims;
    private volatile long startedMs;
    private volatile long stageStartedMs;
    private volatile long unfilledSinceMs;
    private volatile String endedBecause;
    private volatile boolean cleared;
    private volatile long clearedAfterMs = -1;

    RunState(Content content, Tier tier, List<RunMember> members, long budgetMs, int lastStage) {
        this.content = content;
        this.tier = tier;
        this.members = new CopyOnWriteArrayList<>(members);
        this.budgetMs = budgetMs;
        this.lastStage = lastStage;
    }

    @Override
    public Content content() {
        return content;
    }

    @Override
    public Tier tier() {
        return tier;
    }

    @Override
    public int currentStage() {
        return stage;
    }

    public Stage stageData() {
        return content.stage(stage).orElseThrow();
    }

    @Override
    public List<RunMember> members() {
        return List.copyOf(members);
    }

    @Override
    public String endedBecause() {
        return endedBecause;
    }

    /** True once the run's last stage to play was cleared (the run then ended with "cleared"). */
    public boolean cleared() {
        return cleared;
    }

    /** How long the clear took from the run's first tick, or -1. */
    public long clearedAfterMs() {
        return clearedAfterMs;
    }

    public long budgetMs() {
        return budgetMs;
    }

    /** The last stage this run plays (phase 1: KPQ stage 1 only); the run ends cleared after it. */
    public int lastStage() {
        return lastStage;
    }

    public long startedMs() {
        return startedMs;
    }

    public String roleOf(int characterId) {
        RoleClaims.Claims c = claims;
        return c == null ? RoleClaims.SUPPORT : c.roleOf().getOrDefault(characterId, RoleClaims.SUPPORT);
    }

    public RoleClaims.Claims claims() {
        return claims;
    }

    public List<Integer> wrongTriesLeft(int characterId) {
        List<Integer> left = wrongTries.get(characterId);
        return left == null ? List.of() : List.copyOf(left);
    }

    /** True once tries are planned for this member on the current stage (planned when its answer is known). */
    boolean triesPlanned(int characterId) {
        return wrongTries.containsKey(characterId);
    }

    /** Wrong tries this member has shown on this run. */
    public int wrongShown(int characterId) {
        return wrongShown.getOrDefault(characterId, 0);
    }

    public int wrongShownTotal() {
        return wrongShown.values().stream().mapToInt(Integer::intValue).sum();
    }

    public List<Event> events() {
        synchronized (events) {
            return List.copyOf(events);
        }
    }

    public boolean ended() {
        return endedBecause != null;
    }

    public RunMember member(int characterId) {
        for (RunMember m : members) {
            if (m.characterId() == characterId) {
                return m;
            }
        }
        return null;
    }

    // ---- written by the Orchestrator ----

    void note(long now, String line) {
        events.add(new Event(now, line));
        if (events.size() > 200) {
            events.remove(0);
        }
    }

    void setClaims(RoleClaims.Claims c) {
        claims = c;
    }

    void planTries(int characterId, List<Integer> wrong) {
        wrongTries.put(characterId, new CopyOnWriteArrayList<>(wrong));
    }

    /** True when {@code count} was one of the member's planned wrong tries (it is used up then). */
    boolean useWrongTry(int characterId, int count) {
        List<Integer> left = wrongTries.get(characterId);
        if (left != null && left.remove(Integer.valueOf(count))) {
            wrongShown.merge(characterId, 1, Integer::sum);
            return true;
        }
        return false;
    }

    void clearTries() {
        wrongTries.clear();
    }

    boolean removeMember(int characterId) {
        return members.removeIf(m -> m.characterId() == characterId);
    }

    void setStage(int s, long now) {
        stage = s;
        stageStartedMs = now;
    }

    long stageStartedMs() {
        return stageStartedMs;
    }

    void markStarted(long now) {
        if (startedMs == 0) {
            startedMs = now;
            stageStartedMs = now;
        }
    }

    long unfilledSinceMs() {
        return unfilledSinceMs;
    }

    void setUnfilledSinceMs(long ms) {
        unfilledSinceMs = ms;
    }

    void end(String why) {
        if (endedBecause == null) {
            endedBecause = why;
        }
    }

    void markCleared(long afterMs) {
        cleared = true;
        clearedAfterMs = afterMs;
    }
}
