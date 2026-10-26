package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.itemPool.EquipMetadataCache;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** A bounded, memory-only context adapter. Does not load roster rows, item data, shops or other maps. */
public final class LifeObservation {
    private LifeObservation() {}
    public static final int LOCAL_SAMPLE_LIMIT = 64;

    public record Report(LifeInspection.Snapshot observed, LifeProfile profile, LifePolicy.Context context,
                         Result result, LifeActivityQueue.State execution) {}

    public static Report capture(BotSM bot, long nowMs) {
        LifeInspection.Snapshot observed = LifeInspection.capture(bot);
        Character chr = bot.getChr();
        LifeMemory memory = bot instanceof TrainingBot training && training.lifeRuntime() != null
                ? training.lifeRuntime().memory() : LifeMemoryStore.peek(chr.getId());
        LifeProfile profile = memory == null ? LifeProfile.forBot(chr.getId()) : memory.profile();
        List<String> unknown = new ArrayList<>(List.of("MP/ammo pressure", "time since social activity",
                "other-map friends/destinations", "PQ/events", "sellable contents and shop route"));
        OptionalInt weaponLevel = weaponLevel(chr);
        if (weaponLevel.isEmpty()) unknown.add("weapon requirements");
        List<LifeGoals.Goal> goals = memory != null && !memory.goals().isEmpty() ? memory.goals()
                : LifeGoals.seed(chr.getLevel(), chr.getJob().getId(), weaponLevel);
        Map<Need, Double> needs = new EnumMap<>(Need.class);
        needs.putAll(observed.previewNeeds().values());
        needs.put(Need.PROGRESS, goals.stream().mapToDouble(LifeGoals.Goal::pressure).max().orElse(0));

        BotRoster.Member member = BotRoster.loadedMember(chr.getId() - BotStore.BOT_ID_BASE);
        if (member != null && member.lastLoginAt() > 0 && member.lastLoginAt() <= nowMs
                && member.sessionMinutes() > 0 && (double) nowMs - member.lastLoginAt() <= 24 * 60 * 60_000L) {
            needs.put(Need.FATIGUE, Math.min(1, ((double) nowMs - member.lastLoginAt())
                    / (member.sessionMinutes() * 60_000.0)));
        } else {
            unknown.add("valid loaded session timing");
        }

        Map<Activity, String> eligible = new EnumMap<>(Activity.class);
        Current current = null;
        boolean busy = observed.commitment() || observed.conversation() || !BotPopulationDirector.mayLogOut(bot);
        if (bot instanceof TrainingBot training) {
            TrainingBot.LifePhase phase = training.lifePhase();
            boolean grinding = training.isGrinding();
            if (grinding) {
                eligible.put(Activity.GRIND, "continue current grind");
                eligible.put(Activity.RESTOCK, "request the existing town restock trip; purchase not guaranteed");
                eligible.put(Activity.BREAK, "grind break candidate; execution adapter not installed");
            }
            Activity activity = grinding ? Activity.GRIND
                    : phase != null && (phase.name().equals("IN_TOWN") || phase.name().equals("LIFE_DWELL"))
                    ? phase.name().equals("LIFE_DWELL") ? training.currentLifeVisit() : Activity.HANG_OUT : null;
            if (activity != null && phase != null && phase.sinceMs() > 0 && phase.sinceMs() <= nowMs) {
                long duration = grinding ? profile.grindDurationMs() : profile.townDurationMs();
                current = new Current(activity, phase.sinceMs(), duration, LifePolicy.STICKINESS);
                needs.put(Need.BOREDOM, Math.min(1, ((double) nowMs - phase.sinceMs()) / duration));
            } else {
                unknown.add("activity start timing");
            }
            if (activity == null) busy = true; // travel, shopping and recruiting keep their existing owner
        } else {
            unknown.add("activity start timing");
        }
        if (chr.getMap() != null && chr.getMap().isTown()) {
            eligible.put(Activity.HANG_OUT, "current town only; no travel implied");
            if (chr.getLevel() >= 70) eligible.put(Activity.SHOW_OFF, "high-level presence in current town");
        }
        int[] people = people(chr, unknown);
        LifePolicy.Context context = new LifePolicy.Context(new Needs(needs), eligible, people[0], people[1],
                people[2], false, false, busy, current, goals, unknown);
        return new Report(observed, profile, context, LifePolicy.preview(context, profile, nowMs),
                bot instanceof TrainingBot training ? training.lifeActivityState() : null);
    }

    public static OptionalInt weaponLevel(Character chr) {
        // Unlike item-data lookups, get() on an uninitialised cache refuses to load anything.
        if (!EquipMetadataCache.isInitialized()) return OptionalInt.empty();
        var inventory = chr.getInventory(InventoryType.EQUIPPED);
        inventory.lockInventory();
        try {
            Item weapon = inventory.getItem((short) -11);
            if (weapon == null) return OptionalInt.empty();
            var metadata = EquipMetadataCache.get().find(weapon.getItemId());
            return metadata == null ? OptionalInt.empty() : OptionalInt.of(metadata.reqLevel);
        } finally {
            inventory.unlockInventory();
        }
    }

    private static int[] people(Character chr, List<String> unknown) {
        int[] counts = new int[3];
        if (chr.getMap() == null) {
            unknown.add("local people");
            return counts;
        }
        int sampled = 0;
        boolean unloaded = false;
        for (Character other : chr.getMap().getAllPlayers()) {
            if (++sampled > LOCAL_SAMPLE_LIMIT) {
                unknown.add("local sample truncated at " + LOCAL_SAMPLE_LIMIT);
                break;
            }
            if (other != chr && other.getId() >= BotStore.BOT_ID_BASE) {
                // 1.6.0 S2: bots this bot has bonded with count too (audit finding 4); other bots are not strangers
                AffinityState bond = BotBonds.state(chr.getId(), other.getId());
                if (bond == AffinityState.FRIEND) counts[0]++;
                else if (bond.hostile()) counts[2]++;
                continue;
            }
            if (!BotHelpers.isVisibleRealPlayer(other)) continue;
            if (!BotAffinity.loaded(other.getId())) {
                unloaded = true;
                continue;
            }
            AffinityState state = BotAffinity.state(chr.getId(), other.getId());
            if (state == AffinityState.FRIEND) counts[0]++;
            else if (state.hostile() && !BotAffinity.optedOut(other.getId())) counts[2]++;
            else counts[1]++;
        }
        if (unloaded) unknown.add("some local affinity not loaded");
        return counts;
    }

    public static List<String> describe(Report report) {
        return describe(report, false);
    }

    public static List<String> describe(Report report, boolean actualDecision) {
        List<String> lines = new ArrayList<>();
        var observed = report.observed();
        var context = report.context();
        lines.add("Bot life " + (actualDecision ? "DECISION v" : "SHADOW v") + LifeProfile.VERSION + ": " + observed.name() + " lv " + observed.level()
                + " map " + observed.mapId() + " " + observed.role() + "/" + observed.state());
        lines.add("Provisional personality " + report.profile().primary() + ": " + report.profile().blend());
        lines.add("Goals: " + context.goals().stream().map(LifeGoals.Goal::description).toList());
        lines.add("Measured needs: " + context.needs().values() + "; local friends/strangers/rivals "
                + context.friends() + "/" + context.strangers() + "/" + context.rivals());
        lines.add("Unknown: " + String.join(", ", context.unknown()));
        lines.add("Outcome " + report.result().outcome() + ", selected " + report.result().selected()
                + (actualDecision ? "; recorded decision. This command starts no work."
                : "; preview only. This command starts no work."));
        if (report.execution() != null) {
            var execution = report.execution();
            lines.add("Activity requests: waiting=" + execution.waiting() + ", running=" + execution.active()
                    + ", activity=" + execution.activity() + ", last ended=" + execution.lastEnded()
                    + "/" + execution.end() + ", closed=" + execution.closed());
        }
        for (Score score : report.result().topThree()) {
            lines.add(String.format(java.util.Locale.ROOT, "%s %.3f needs=%s context=%.3f personality=%.3f sticky=%.3f: %s",
                    score.activity(), score.total(), score.contributions(), score.contextBonus(),
                    score.personalityBonus(), score.stickiness(), score.reason()));
        }
        lines.add("Unavailable: " + java.util.Arrays.stream(Activity.values())
                .filter(a -> !context.eligible().containsKey(a)).toList());
        return List.copyOf(lines);
    }
}
