package soloMapling.qa;

import soloMapling.fishing.FishingPolicy;
import soloMapling.fishing.FishingSessions;
import soloMapling.fishing.GameFishing;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Policy replay plus an actual seated player using the unchanged ninety-second timer. */
final class FishingScenarios {
    private FishingScenarios() { }
    static List<QaScenario> all() { return List.of(new TimerPolicy(), new RealPlayer()); }

    static final class TimerPolicy implements QaScenario {
        public String id() { return "fh-1"; }
        public String title() { return "Fishing timer, duplicate calls and cancellation"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.HABITS); }
        public int minutes(String[] args) { return 1; }
        public List<String> stage(QaContext ctx) {
            var service = new FishingSessions(1, FishingPolicy.REWARDS, bound -> 0);
            class Actor implements FishingSessions.Actor {
                final Object map = new Object(); int x, grants;
                public int id() { return 1; }
                public FishingSessions.Position position() { return new FishingSessions.Position(1, 1, map, x, 120, 3011000, true); }
                public boolean give(FishingSessions.Reward r) { grants++; return true; }
                public void caught(FishingSessions.Reward r, long sequence) { }
            }
            var actor = new Actor();
            if (service.start(actor, 0) != FishingSessions.Start.STARTED
                    || service.tick(1, 89_999) != FishingSessions.Tick.WAIT
                    || service.tick(1, 90_000) != FishingSessions.Tick.CAUGHT
                    || service.tick(1, 90_000) != FishingSessions.Tick.WAIT) throw new QaRefusal("Fishing timer replay failed");
            actor.x++;
            if (service.tick(1, 180_000) != FishingSessions.Tick.CANCELLED || actor.grants != 1)
                throw new QaRefusal("Movement failed to cancel a fishing payout");
            ctx.run().note("FISHING", "POLICY PASS grants=1 duplicate=blocked movement=cancelled");
            return List.of("Timer and cancellation passed using in-memory actors; no real items were granted.",
                    "Use fh-2 for the real player timer and the owner checklist for actual bot travel.");
        }
    }

    static final class RealPlayer implements QaScenario {
        private record Fixture(Map<Integer, Integer> before, int oldMap) { }
        private final Map<Integer, Fixture> fixtures = new ConcurrentHashMap<>();
        public String id() { return "fh-2"; }
        public String title() { return "Fish on a real chair and stop when standing"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.HABITS); }
        public int minutes(String[] args) { return 3; }
        public int tickSeconds() { return 2; }
        public Set<String> claims(client.Character gm, String[] args) { return Set.of("fishing:" + gm.getId()); }
        public List<String> stage(QaContext ctx) {
            if (!FishingPolicy.rewardsEnabled()) throw new QaRefusal("Needs both fishing flags on the isolated TEST copy.");
            var gm = ctx.gm(); var world = ctx.world();
            if (GameFishing.active(gm) || gm.getChair() > 0) throw new QaRefusal("Stand up before staging fishing.");
            Map<Integer, Integer> before = new java.util.LinkedHashMap<>();
            for (var reward : FishingPolicy.REWARDS) before.put(reward.itemId(), world.countItem(gm, reward.itemId()));
            int oldMap = gm.getMapId();
            fixtures.put(gm.getId(), new Fixture(before, oldMap));
            ctx.onStop("stop fishing, restore items and return", () -> {
                Fixture fixture = fixtures.remove(gm.getId());
                GameFishing.cancel(gm);
                if (gm.getChair() > 0) gm.sitChair(-1);
                if (fixture != null) {
                    fixture.before().forEach((item, count) -> {
                        int extra = world.countItem(gm, item) - count;
                        if (extra > 0) world.takeItem(gm, item, extra);
                    });
                    if (world.online(gm.getId()) != null) world.warp(gm, fixture.oldMap());
                }
            });
            if (world.countItem(gm, FishingPolicy.CHAIR) == 0) {
                int given = world.giveItem(gm, FishingPolicy.CHAIR, 1);
                if (given != 1) throw new QaRefusal("No room for the fixture chair.");
                ctx.onStop("remove only the fixture chair", () -> world.takeItem(gm, FishingPolicy.CHAIR, 1));
            }
            if (!world.warp(gm, 120010000)) throw new QaRefusal("Fishing map unavailable.");
            gm.sitChair(FishingPolicy.CHAIR);
            if (!GameFishing.active(gm)) throw new QaRefusal("The actual fishing service refused the seated player.");
            ctx.run().note("FISHING", "REAL STARTED; first catch waits 90 seconds plus the world tick");
            return List.of("Wait seated for the first catch (90–100 seconds), then !qa do stop.",
                    "The scenario removes its temporary chair and extra catches when stopped.");
        }
        public void tick(QaContext ctx) {
            Fixture f = fixtures.get(ctx.run().gmId());
            if (f == null) return;
            int catches = f.before().entrySet().stream().mapToInt(e -> Math.max(0,
                    ctx.world().countItem(ctx.gm(), e.getKey()) - e.getValue())).sum();
            if (catches > 0) ctx.run().note("FISHING", "REAL catches=" + catches + " active=" + GameFishing.active(ctx.gm()));
        }
        public List<String> action(QaContext ctx, String name, String[] args) {
            if (!name.equals("stop")) throw new QaRefusal("Use stop after the first catch.");
            ctx.gm().sitChair(-1);
            if (GameFishing.active(ctx.gm())) throw new QaRefusal("Standing did not stop fishing.");
            ctx.run().note("FISHING", "STOPPED active=false");
            return List.of("Standing stopped fishing. No further catch can be paid from this session.");
        }
    }
}
