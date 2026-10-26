package soloMapling.qa;

import soloMapling.ArtificialPlayer.BotLifeSystem.LifeAcceptance;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifePolicy;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** Deterministic policy/acceptance smoke inside a running feature jar. Does not stage players or alter holdings. */
final class BotLifeScenarios {
    private BotLifeScenarios() {}
    static List<QaScenario> all() {
        return List.of(new GoldenPolicy(), new OneRestockTrip(false), new OneRestockTrip(true), new ProfileSurvives(),
                new PlanShown(), new ChannelChange());
    }

    /**
     * bl-5 (1.6.0 S8): a staged goal turns into plan steps that {@code !botwhy} shows. One training bot is staged on a
     * map with mobs; once it grinds it gets a life goal (get married; join a guild if it is married) and the planner's
     * steps appear in its {@code !botwhy} lines, logged as LIFE WHY.
     */
    static final class PlanShown implements QaScenario {
        private final Map<Integer, Integer> bots = new java.util.concurrent.ConcurrentHashMap<>();
        private final Set<Integer> done = java.util.concurrent.ConcurrentHashMap.newKeySet();

        public String id() { return "bl-5"; }
        public String title() { return "Bot brain: a staged goal shows its plan steps in !botwhy"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.HABITS); }
        public int minutes(String[] args) { return 3; }
        public int tickSeconds() { return 3; }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().botLifeAutomaticEnabled()) throw new QaRefusal("Needs the bot brain on (botlife enabled+execute).");
            if (ctx.world().mobLevel(ctx.gm()) < 0) throw new QaRefusal("Needs a map with live mobs, such as HHG1.");
            List<Integer> ids = ctx.world().stageGrinders(ctx.gm(), List.of("WARRIOR:40"), 1, 0, true, true);
            if (ids.isEmpty()) throw new QaRefusal("No bot could be staged here.");
            int gm = ctx.run().gmId();
            bots.put(gm, ids.getFirst());
            done.remove(gm);
            ctx.onStop("remove the plan fixture", () -> { ctx.world().unstageGrinders(ids); bots.remove(gm); });
            ctx.run().note("LIFE", "PLAN START bot=" + ids.getFirst());
            return List.of("One training bot appears and starts grinding; it then gets a life goal (get married).",
                    "The log shows LIFE PLAN steps and the bot's !botwhy lines (LIFE WHY Plan ...), then PASS.",
                    "!botwhy <name> in game shows the same goal and steps. Nothing is granted: the plan only leans the bot.");
        }

        public void tick(QaContext ctx) {
            int gm = ctx.run().gmId();
            Integer bot = bots.get(gm);
            if (bot == null || done.contains(gm)) return;
            List<String> lines = ctx.world().lifePlanStage(bot);
            if (lines.isEmpty()) return; // not grinding yet
            done.add(gm);
            lines.forEach(line -> ctx.run().note("LIFE", line));
        }
    }

    /**
     * bc-1, also {@code !qa cc-1} (1.6.0 S8, the owner's idea): a staged crowded map makes a bot say "cc" (or "too many
     * ppl") and appear on the same map on a quieter channel. One training bot is staged; its map instance is made to read
     * 120% full; the brain sees CHANGE_CHANNEL eligible with a target, and {@code !qa do cc} (the auto-QA step) queues
     * the request through the bot's own queue, as the brain would. The log shows LIFE CC with the two channels.
     */
    static final class ChannelChange implements QaScenario {
        private final Map<Integer, Integer> bots = new java.util.concurrent.ConcurrentHashMap<>();
        private final Map<Integer, String> where = new java.util.concurrent.ConcurrentHashMap<>();

        public String id() { return "bc-1"; }
        public List<String> aliases() { return List.of("cc-1"); }
        public String title() { return "Bot brain: a crowded map makes a bot say cc and change channel"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.HABITS); }
        public int minutes(String[] args) { return 3; }
        public int tickSeconds() { return 3; }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().botLifeAutomaticEnabled()) throw new QaRefusal("Needs the bot brain on (botlife enabled+execute).");
            if (ctx.world().mobLevel(ctx.gm()) < 0) throw new QaRefusal("Needs a map with live mobs, such as HHG1.");
            List<Integer> ids = ctx.world().stageGrinders(ctx.gm(), List.of("WARRIOR:30"), 1, 0, true, true);
            if (ids.isEmpty()) throw new QaRefusal("No bot could be staged here.");
            int gm = ctx.run().gmId();
            int bot = ids.getFirst();
            bots.put(gm, bot);
            where.put(gm, ctx.world().botChannelAndMap(bot));
            ctx.onStop("clear the staged crowd and remove the bot", () -> {
                ctx.world().lifeCrowdStage(bot, null);
                ctx.world().unstageGrinders(ids);
                bots.remove(gm);
                where.remove(gm);
            });
            ctx.world().lifeCrowdStage(bot, 1.2).forEach(line -> ctx.run().note("LIFE", line));
            ctx.run().note("LIFE", "CC START bot=" + bot + " at=" + where.get(gm));
            return List.of("One training bot appears; its map now reads as 120% full (staged, no extra bots).",
                    "Wait for LIFE READY, then !qa do cc: the bot says \"cc\" or \"too many ppl\" and leaves for a quieter "
                            + "channel, same map (LIFE CC chA->chB). Change channel yourself to see it there.",
                    "It never goes mid-trade, in a party or inside a PQ; the target channel must be under its map cap.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Integer bot = bots.get(ctx.run().gmId());
            if (bot == null) throw new QaRefusal("No staged bot remains.");
            if (!name.equals("cc")) throw new QaRefusal("Use cc.");
            ctx.world().lifeCrowdStage(bot, 1.2).forEach(line -> ctx.run().note("LIFE", line));
            String offered = ctx.world().forceLifeChannel(bot);
            ctx.run().note("LIFE", "CC QUEUED bot=" + bot + " result=" + offered);
            if (!offered.equals("QUEUED") && !offered.equals("REPLACED")) {
                throw new QaRefusal("The channel change was refused: " + offered);
            }
            return List.of("Queued. The bot changes channel on its next step.");
        }

        public void tick(QaContext ctx) {
            int gm = ctx.run().gmId();
            Integer bot = bots.get(gm);
            if (bot == null) return;
            ctx.world().botLifeReport(bot).forEach(line -> ctx.run().note("LIFE", line));
            String now = ctx.world().botChannelAndMap(bot);
            String before = where.get(gm);
            if (before != null && !now.isEmpty() && !now.equals(before)) {
                String[] a = before.split(" "), b = now.split(" ");
                ctx.run().note("LIFE", "CC MOVED bot=" + bot + " from ch" + a[0] + " to ch" + b[0]
                        + " sameMap=" + a[1].equals(b[1]));
                where.put(gm, now);
            }
        }
    }

    /**
     * bl-4 (1.6.0 S1): a bot's learned life profile survives a non-training session and a reload from the database.
     * One real roster bot gets one ordinary life nudge (0.5%, within its daily cap); the change stays.
     */
    static final class ProfileSurvives implements QaScenario {
        public String id() { return "bl-4"; }
        public String title() { return "Bot life: learned profile survives a social session and a reload"; }
        public String usage() { return "[bot name]"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 2; }

        /**
         * Where the round trip runs: never on the thread that typed {@code !qa run}. It writes and reads the database
         * through the bots' share of the pool, which a boot fill can hold for 15 s or more (TEST, 2026-10-26: the
         * answer came after 23 s and auto-QA had already given up). Tests run it inline.
         */
        static volatile java.util.concurrent.Executor proofs = soloMapling.server.ExecutorServiceManager::runAsync;

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().lifeProfileProofAvailable()) {
                throw new QaRefusal("This world can't run the life profile round trip.");
            }
            String name = ctx.args().length > 0 ? ctx.args()[0] : null;
            var gm = ctx.gm();
            var run = ctx.run();
            run.note("LIFE", "PROFILE START bot=" + (name == null ? "(any online roster bot)" : name));
            proofs.execute(() -> {
                try {
                    List<String> lines = ctx.world().lifeProfileProof(gm, name);
                    if (lines.isEmpty()) run.note("LIFE", "PROFILE FAIL the world returned nothing");
                    lines.forEach(line -> run.note("LIFE", line.startsWith("LIFE ") ? line.substring(5) : line));
                } catch (QaRefusal refused) {
                    run.note("LIFE", "PROFILE FAIL " + refused.getMessage());
                } catch (RuntimeException failed) {
                    run.note("LIFE", "PROFILE FAIL " + failed);
                }
            });
            return List.of("The round trip runs in the background; the log shows LIFE PROFILE PASS (or FAIL) in a few"
                            + " seconds, longer while bots are logging in.",
                    "The bot gets one ordinary life nudge (0.5%) and keeps it. !botwhy <name> shows its Bartle point.",
                    "This proves the database round trip on this server, not a full restart; restart TEST and"
                            + " !botwhy the same bot to see the same point.");
        }
    }

    static final class OneRestockTrip implements QaScenario {
        private final boolean automatic;
        OneRestockTrip(boolean automatic) { this.automatic = automatic; }
        private record Fixture(int botId,
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue.Request request) {}
        private final Map<Integer, Fixture> fixtures = new java.util.concurrent.ConcurrentHashMap<>();

        public String id() { return automatic ? "bl-3" : "bl-2"; }
        public String title() { return "Bot life: one request starts one restock trip"; }
        public Set<QaLog.Topic> topics() { return Set.of(QaLog.Topic.HABITS); }
        public Set<String> claims(client.Character gm, String[] args) { return Set.of("bot-life-execution"); }
        public int minutes(String[] args) { return 3; }
        public int tickSeconds() { return 2; }

        public List<String> stage(QaContext ctx) {
            if (!ctx.world().botLifeExecutionEnabled()) {
                throw new QaRefusal("Needs QA and -Dslums.botlife.execute=true on the feature test instance.");
            }
            if (automatic && !ctx.world().botLifeAutomaticEnabled()) throw new QaRefusal("Needs automatic bot life enabled.");
            if (ctx.world().mobLevel(ctx.gm()) < 0) throw new QaRefusal("Needs a map with live mobs, such as HHG1.");
            List<Integer> ids = ctx.world().stageGrinders(ctx.gm(), List.of("WARRIOR:30"), 1, 0, true, true);
            if (ids.isEmpty()) throw new QaRefusal("No bot could be staged here.");
            ctx.log("staged " + ids.size() + " grinder(s): " + ids);
            ctx.onStop("remove life execution fixture", () -> {
                ctx.world().unstageGrinders(ids);
                fixtures.remove(ctx.run().gmId());
            });
            fixtures.put(ctx.run().gmId(), new Fixture(ids.getFirst(), null));
            tick(ctx);
            return List.of("One training bot appears. Wait for LIFE READY, then !qa do " + (automatic ? "need" : "restock") + ".",
                    "One request is sent twice. Only one restock trip may start. !qa do repeat retries it later.",
                    "Watch the bot leave for town and return. The log does not claim that a purchase succeeded.");
        }

        public List<String> action(QaContext ctx, String name, String[] args) {
            Fixture fixture = fixtures.get(ctx.run().gmId());
            if (fixture == null) throw new QaRefusal("No staged bot remains.");
            if (automatic && name.equals("need")) {
                if (!ctx.world().triggerLifeNeed(fixture.botId())) throw new QaRefusal("Wait for the bot to start grinding.");
                ctx.run().note("LIFE", "NEED bot=" + fixture.botId() + " HP-potions=0; no trip was directly requested");
                return List.of("HP potions removed. The bot must choose and start restocking itself.");
            }
            if (name.equals("restock")) {
                if (fixture.request() != null) throw new QaRefusal("Already requested; use repeat to retry that request.");
                var request = ctx.world().prepareLifeRestock(fixture.botId());
                if (request == null) throw new QaRefusal("Wait for the bot to start grinding.");
                fixtures.put(ctx.run().gmId(), new Fixture(fixture.botId(), request));
                String offered = ctx.world().submitLifeRestock(fixture.botId(), request);
                ctx.run().note("LIFE", "QUEUED request=" + request.number() + " result=" + offered);
                if (!offered.equals("QUEUED")) throw new QaRefusal("Trip request refused: " + offered);
                return replay(ctx, fixture.botId(), request);
            }
            if (name.equals("repeat") && fixture.request() != null) return replay(ctx, fixture.botId(), fixture.request());
            throw new QaRefusal("Use restock, then repeat.");
        }

        private List<String> replay(QaContext ctx, int botId,
                soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue.Request request) {
            String result = ctx.world().submitLifeRestock(botId, request);
            if (!result.equals("DUPLICATE") && !result.equals("STALE")) {
                throw new QaRefusal("The same request was not rejected: " + result);
            }
            ctx.run().note("LIFE", "REPLAY request=" + request.number() + " result=" + result);
            return List.of("Same request rejected: " + result + ". It must not start another trip.");
        }

        public void tick(QaContext ctx) {
            Fixture fixture = fixtures.get(ctx.run().gmId());
            if (fixture != null) ctx.world().botLifeReport(fixture.botId())
                    .forEach(line -> ctx.run().note("LIFE", line));
        }
    }

    static final class GoldenPolicy implements QaScenario {
        public String id() { return "bl-1"; }
        public String title() { return "Bot life: provisional policy and rejected-action safeguards"; }
        public Set<QaLog.Topic> topics() { return Set.of(); }
        public int minutes(String[] args) { return 1; }

        public List<String> stage(QaContext ctx) {
            check(ctx, "socialite", Map.of(Need.SOCIAL, .8, Need.PROGRESS, .4), 2, false,
                    LifeProfile.Archetype.SOCIALITE, Activity.HANG_OUT);
            Result restock = check(ctx, "empty-potions", Map.of(Need.SUPPLIES, 1.0, Need.PROGRESS, 1.0),
                    0, false, LifeProfile.Archetype.GRINDER, Activity.RESTOCK);
            check(ctx, "helper", Map.of(Need.SOCIAL, .8, Need.PROGRESS, .3), 1, true,
                    LifeProfile.Archetype.HELPER, Activity.HELP_FRIEND);
            var refusal = LifeAcceptance.tryStart(restock, true, () -> true, new LifeAcceptance.Executor() {
                public boolean supports(Activity activity) { return true; }
                public Optional<LifeAcceptance.Accepted> start(Activity activity) { return Optional.empty(); }
            });
            if (refusal.status() != LifeAcceptance.Status.REJECTED || refusal.accepted().isPresent()) {
                throw new QaRefusal("Rejected executor produced an action fact");
            }
            ctx.run().note("LIFE", "REFUSAL accepted=false actionFact=false");
            return List.of("Golden shadow policy cases and executor refusal were checked in this jar.",
                    "No actual bot, inventory or mesos were changed. Use !botwhy <name> for real context inspection.",
                    "This scenario does not prove travel, purchases, population mix or a 3,000-bot soak.");
        }

        private Result check(QaContext ctx, String name, Map<Need, Double> needs, int friends, boolean asked,
                             LifeProfile.Archetype primary, Activity expected) {
            var blend = new EnumMap<LifeProfile.Archetype, Double>(LifeProfile.Archetype.class);
            for (var type : LifeProfile.Archetype.values()) blend.put(type, type == primary ? .7 : .05);
            var context = new LifePolicy.Context(new Needs(needs), Map.of(Activity.GRIND, "fixture field",
                    Activity.HANG_OUT, "fixture town", Activity.RESTOCK, "fixture trip",
                    Activity.HELP_FRIEND, "fixture friend destination"), friends, 0, 0, asked,
                    false, false, null, List.of(), List.of());
            Result result = LifePolicy.preview(context, new LifeProfile(blend, 40 * 60_000L, 10 * 60_000L), 0);
            if (result.selected().orElse(null) != expected) {
                throw new QaRefusal("Life policy fixture " + name + " chose " + result.selected());
            }
            ctx.run().note("LIFE", "SHADOW case=" + name + " selected=" + expected);
            return result;
        }
    }
}
