package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import soloMapling.ArtificialPlayer.*;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;

import java.util.*;
import java.util.concurrent.atomic.LongAdder;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** Event/completion driven decisions, with a cheap 30-second interrupt check, on the existing bot update. */
public final class LifeRuntime {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(LifeRuntime.class);
    private static final LongAdder decisions = new LongAdder(), accepted = new LongAdder(), cpuNanos = new LongAdder();
    private static final java.util.concurrent.atomic.AtomicLong nextReport = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.ConcurrentHashMap<Activity, LongAdder> activityCounts = new java.util.concurrent.ConcurrentHashMap<>();
    public record DecisionSnapshot(long at, LifeObservation.Report report) {}
    private volatile DecisionSnapshot lastDecision;
    public DecisionSnapshot lastDecision() { return lastDecision; }
    private final TrainingBot bot;
    private final LifeMemory memory;
    private final long bornAt;
    private final boolean restored;
    private volatile int townDestination = 100000000;
    private volatile boolean dirty = true;
    private long nextCheck, dueAt, lastAnnouncement, lastNeedSignature = Long.MIN_VALUE;
    private long lastRestock;
    public boolean restockReady(long now) { return lastRestock == 0 || now - lastRestock >= 300_000; }
    private volatile String phase = "INIT", fact = "no daily-life action has started";
    private volatile int friendId;
    private volatile long friendUntil;
    private volatile boolean partyAsk;

    public LifeRuntime(TrainingBot bot) {
        this.bot = bot;
        bornAt = System.currentTimeMillis();
        var member = BotRoster.loadedMember(bot.getChr().getId() - BotStore.BOT_ID_BASE);
        var previous = member == null ? null : member.lastActivity();
        Object saved = previous == null ? null : previous.state().get("life");
        restored = saved instanceof Map<?, ?>;
        memory = new LifeMemory(bot.getChr().getId(), saved);
    }

    public static boolean enabled() {
        return Boolean.getBoolean("slums.botlife.enabled") && Boolean.getBoolean("slums.botlife.execute");
    }
    public LifeMemory memory() { return memory; }
    public boolean restored() { return restored; }
    public int townDestination() {
        Character friend = friend(System.currentTimeMillis());
        return friend != null && friend.getMap().isTown() ? friend.getMapId() : townDestination;
    }
    public static void event(Character chr, LifeMemory.Event event) {
        if (enabled() && chr != null && soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage
                .getBotById(chr.getId()) instanceof TrainingBot training) {
            if (training.lifeRuntime().memory().event(event, System.currentTimeMillis())) training.lifeRuntime().changed();
        }
    }
    public void changed() { dirty = true; }
    public synchronized void phase(String value, long now) {
        phase = value;
        dirty = true;
        dueAt = now + (value.equals("GRIND") ? memory.profile().grindDurationMs() : memory.profile().townDurationMs());
        fact = switch (value) {
            case "GRIND" -> "grinding on the current map";
            case "GO_TOWN" -> "travelling toward town; arrival and purchases are not confirmed";
            case "LIFE_TRAVEL" -> "travelling to the chosen destination; not there yet";
            case "LIFE_DWELL", "IN_TOWN" -> "staying on the current map";
            case "SHOP_DWELL" -> "visiting the current shop; do not claim a purchase unless separately confirmed";
            case "BREAK_REST", "BREAK_TRAVEL" -> "taking a grind break";
            default -> "following the existing " + value.toLowerCase(Locale.ROOT).replace('_', ' ') + " routine";
        };
    }

    public boolean friendAsk(Character player, boolean party, long now) {
        if (!enabled() || player == null || !BotAffinity.friend(bot.getChr().getId(), player.getId())) return false;
        friendId = player.getId(); friendUntil = now + 60_000; partyAsk = party; dirty = true;
        memory.talked(now);
        bot.nudgeSoon(300);
        return true;
    }

    public Character friend(long now) {
        int id = friendId;
        if (id == 0 || now >= friendUntil) return null;
        var chr = bot.getChr();
        var player = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(id);
        return player != null && player.getMap() != null && chr.getMap() != null
                && player.getMap().getChannelId() == chr.getMap().getChannelId()
                && BotAffinity.friend(chr.getId(), id) ? player : null;
    }
    public int requester(long now) { Character player = friend(now); return player == null ? -1 : player.getId(); }
    public boolean partyAsked() { return partyAsk; }

    public void tick(long now) {
        if (!enabled() || !bot.getRunning()) return;
        if (!dirty && now < nextCheck && now < dueAt) return;
        nextCheck = now + 30_000;
        if (!Set.of("GRIND", "IN_TOWN", "LIFE_DWELL").contains(phase)) { dirty = false; return; }
        var running = bot.lifeActivityState();
        if (running.waiting() != 0) return;
        // A friend can interrupt a safe town stay. Trips and other active work retain their owner.
        if (running.active() != 0 && !(phase.equals("LIFE_DWELL") && friend(now) != null)) return;
        long began = System.nanoTime();
        try {
            Character chr = bot.getChr();
            townDestination = 100000000;
            int sampled = 0;
            for (int playerId : BotAffinity.knownPlayers()) {
                if (++sampled > 32) break;
                if (!BotAffinity.friend(chr.getId(), playerId)) continue;
                var player = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(playerId);
                if (player != null && player.getMap() != null && player.getMap().isTown()
                        && player.getMap().getChannelId() == chr.getMap().getChannelId()) {
                    townDestination = player.getMapId(); break;
                }
            }
            memory.refresh(chr.getLevel(), chr.getJob().getId(), LifeObservation.weaponLevel(chr), now);
            var report = LifeObservation.capture(bot, now);
            var original = report.context();
            Map<Need, Double> needs = new EnumMap<>(Need.class);
            needs.putAll(original.needs().values());
            long socialSince = memory.lastSocial() > 0 ? memory.lastSocial() : bornAt;
            needs.put(Need.SOCIAL, Math.min(1, Math.max(0, now - socialSince)
                    / (double) memory.profile().townDurationMs()));
            List<String> unknown = new ArrayList<>(original.unknown());
            unknown.remove("time since social activity");
            // Read-only checks: do not call shortage(), which changes the ammo-fallback state.
            try {
                double pressure = needs.getOrDefault(Need.SUPPLIES, 0.0);
                var attack = BotAttackDriver.primaryAttack(chr);
                if (attack != null && BotVitals.mpCost(chr, attack.skillId, BotAttackDriver.packetLevel(chr, attack)) > 0) {
                    pressure = Math.max(pressure, Math.max(0, 1 - BotVitals.potionCount(chr, false) / 20.0));
                }
                if (BotSupplies.shouldRetryAmmoTrip(chr.getId()) && BotSupplies.outOfAmmo(chr)) pressure = 1;
                needs.put(Need.SUPPLIES, pressure);
                unknown.remove("MP/ammo pressure");
            } catch (RuntimeException unavailable) { /* the shadow report continues to mark unavailable supply data */ }
            Map<Activity, String> eligible = new EnumMap<>(Activity.class);
            for (Activity activity : Activity.values()) if (bot.canStartLifeActivity(activity, now)) {
                eligible.put(activity, "existing " + activity.name().toLowerCase(Locale.ROOT) + " routine");
            }
            Character friend = friend(now);
            boolean free = bot.freeForAutomaticLifeActivity(now);
            var context = new LifePolicy.Context(new Needs(needs), eligible, original.friends(), original.strangers(),
                    original.rivals(), friend != null, false, !free, original.current(), memory.goals(), unknown);
            long signature = Objects.hash(phase, free, eligible.keySet(), original.friends(), original.rivals(),
                    friend == null ? 0 : friend.getId(), needs.entrySet().stream()
                            .map(e -> e.getKey() + ":" + (int) (e.getValue() * 4)).toList());
            if (!dirty && now < dueAt && signature == lastNeedSignature) return;
            dirty = false; lastNeedSignature = signature;
            var current = now >= dueAt ? null : context.current();
            double townBias = chr.getLevel() <= 30 ? .4 : chr.getLevel() <= 70 ? .25 : .05;
            var options = LifePolicy.options(context, memory.profile()).stream().map(o ->
                    o.activity() == Activity.HANG_OUT || o.activity() == Activity.SHOW_OFF
                    ? new Option(o.activity(), o.weights(), o.contextBonus() + townBias
                            + (townDestination != 100000000 ? .6 : 0), o.personalityBonus(), o.available(), o.reason()) : o).toList();
            var result = LifeDecision.evaluate(context.needs(), options, current,
                    now, LifePolicy.INTERRUPT_MARGIN, .02,
                    a -> new SplittableRandom(chr.getId() * 31L + a.ordinal() * 101L + now / 30_000).nextDouble(-1, 1),
                    context.commitment());
            lastDecision = new DecisionSnapshot(now, new LifeObservation.Report(report.observed(),
                    memory.profile(), context, result, bot.lifeActivityState()));
            decisions.increment();
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
                soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS,
                        "LIFE DECISION bot=" + chr.getName() + " selected=" + result.selected().map(Enum::name).orElse("NONE"));
            }
            log.info("[BotLife] DECISION bot={} phase={} selected={} outcome={} top={}", chr.getId(), phase,
                    result.selected(), result.outcome(), result.topThree().stream()
                            .map(s -> s.activity() + ":" + String.format(Locale.ROOT, "%.2f", s.total())).toList());
            if (result.selected().isPresent() && now >= dueAt
                    && (phase.equals("GRIND") && result.selected().get() == Activity.GRIND
                    || phase.equals("LIFE_DWELL") && result.selected().get() == Activity.HANG_OUT)) {
                dueAt = now + (phase.equals("GRIND") ? memory.profile().grindDurationMs() : memory.profile().townDurationMs());
                bot.keepLifeActivity(result.selected().get(), dueAt);
            }
            if (result.outcome() == Outcome.CHOOSE && result.selected().isPresent()
                    && !(phase.equals("GRIND") && result.selected().get() == Activity.GRIND)
                    && !(phase.equals("LIFE_DWELL") && result.selected().get() == Activity.HANG_OUT)) {
                if (running.active() != 0 && (!Set.of(Activity.HELP_FRIEND, Activity.PARTY_QUEST)
                        .contains(result.selected().get()) || !bot.interruptLifeDwell(running.active()))) return;
                bot.submitLifeActivity(bot.prepareLifeActivity(result));
            }
        } finally { cpuNanos.add(System.nanoTime() - began); reportIfDue(now); }
    }

    public synchronized void accepted(Activity activity, long now) {
        Character requestPlayer = activity == Activity.HELP_FRIEND || activity == Activity.PARTY_QUEST ? friend(now) : null;
        accepted.increment();
        if (activity == Activity.RESTOCK) lastRestock = now;
        activityCounts.computeIfAbsent(activity, a -> new LongAdder()).increment();
        if (activity == Activity.HELP_FRIEND || activity == Activity.PARTY_QUEST) { friendId = 0; friendUntil = 0; }
        String line = switch (activity) {
            case RESTOCK -> "brb, restocking";
            case HANG_OUT, SHOW_OFF -> "gonna hang around town for a bit";
            case FREE_MARKET, SELL -> "heading to the market for a bit";
            case MOVE_MAP -> "gonna try another training spot";
            case HELP_FRIEND -> "omw to help";
            case BREAK -> "taking a quick break";
            case PARTY_QUEST -> "ready when you are";
            default -> null;
        };
        if (requestPlayer != null && line != null) {
            BotWhispers.send(requestPlayer, bot.getChr().getName(), bot.getChr().getMap().getChannelId() - 1, line, true);
            return;
        }
        if (now - lastAnnouncement < 300_000 || !soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.isMapObserved(bot.getChr().getMapId())) return;
        if (line != null) {
            lastAnnouncement = now;
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat(bot.getChr(), line);
        }
    }

    public synchronized String prompt() {
        return enabled() ? "\nDaily-life facts: " + fact + ". Goals: "
                + memory.goals().stream().map(LifeGoals.Goal::description).toList()
                + ". These are goals, not completed achievements. Never claim arrival, buying, selling or rewards without a confirmed event.\n" : "";
    }
    public static Map<String, Long> metrics() {
        return Map.of("decisions", decisions.sum(), "accepted", accepted.sum(), "cpuNanos", cpuNanos.sum());
    }
    private static void reportIfDue(long now) {
        if ("false".equalsIgnoreCase(System.getProperty("slums.botlife.metrics", "true"))) return;
        long due = nextReport.get();
        if (now < due || !nextReport.compareAndSet(due, now + 60_000)) return;
        soloMapling.server.ExecutorServiceManager.runAsync(() -> {
            try {
                Map<String, Object> data = new LinkedHashMap<>(metrics());
                data.put("at", now);
                data.put("onlineBots", soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().size());
                Map<String, Long> activities = new TreeMap<>();
                activityCounts.forEach((activity, count) -> activities.put(activity.name(), count.sum()));
                data.put("activities", activities);
                int[] bands = new int[3];
                for (var channel : soloMapling.server.SoloMaplingUtilities.world().getChannels()) {
                    var factory = channel.getMapFactory();
                    if (!factory.isMapLoaded(100000000)) continue;
                    for (Character chr : factory.getMap(100000000).getAllPlayers()) if (BotHelpers.isBot(chr)) {
                        bands[chr.getLevel() <= 30 ? 0 : chr.getLevel() <= 70 ? 1 : 2]++;
                    }
                }
                data.put("henesysLevels", Map.of("1-30", bands[0], "31-70", bands[1], "71+", bands[2]));
                data.put("sayDo", soloMapling.ArtificialPlayer.BotConvo.SayDo.stats());
                var file = java.nio.file.Path.of("data/autoqa/bot-life.json");
                java.nio.file.Files.createDirectories(file.getParent());
                var temporary = file.resolveSibling("bot-life.json.tmp");
                java.nio.file.Files.writeString(temporary, soloMapling.server.Json.write(data));
                java.nio.file.Files.move(temporary, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (RuntimeException | java.io.IOException failure) { log.warn("Bot life report unavailable: {}", failure.getClass().getSimpleName()); }
        });
    }
}
