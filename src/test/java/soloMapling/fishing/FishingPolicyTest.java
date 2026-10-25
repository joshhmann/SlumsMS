package soloMapling.fishing;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FishingPolicyTest {
    @Test void everyPercentagePointMapsToExactlyOneApprovedDraftReward() {
        Map<Integer, Integer> observed = new HashMap<>();
        for (int roll = 0; roll < 100; roll++) {
            final int value = roll;
            var actor = new FishingSessionsTest.Fisher(1) {
                public boolean give(FishingSessions.Reward reward) { observed.merge(reward.itemId(), 1, Integer::sum); return true; }
            };
            var service = new FishingSessions(1, FishingPolicy.REWARDS, bound -> { assertEquals(100, bound); return value; });
            service.start(actor, 0);
            assertEquals(FishingSessions.Tick.CAUGHT, service.tick(1, 90_000));
        }
        assertEquals(Map.of(4031627, 35, 4031630, 30, 4000000, 25, 2000000, 5, 2000003, 5), observed);
    }
}
