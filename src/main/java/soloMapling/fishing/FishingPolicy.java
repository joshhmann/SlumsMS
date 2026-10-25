package soloMapling.fishing;

import java.util.List;

/** Draft v1 table: existing low-value collectibles/consumables; no mesos, EXP, gear, scrolls or rare pool. */
public final class FishingPolicy {
    public static final int CHAIR = 3011000;
    public static final List<FishingSessions.Reward> REWARDS = List.of(
            new FishingSessions.Reward(4031627, "Whitebait (3cm)", 35),
            new FishingSessions.Reward(4031630, "Carp (30cm)", 30),
            new FishingSessions.Reward(4000000, "Snail Shell", 25),
            new FishingSessions.Reward(2000000, "Red Potion", 5),
            new FishingSessions.Reward(2000003, "Blue Potion", 5));
    private FishingPolicy() { }
    public static boolean enabled() { return Boolean.getBoolean("slums.fishing.enabled"); }
    public static boolean rewardsEnabled() { return enabled() && Boolean.getBoolean("slums.fishing.rewards"); }
}
