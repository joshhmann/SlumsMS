package soloMapling.ArtificialPlayer.BotLifeSystem;

import client.Character;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.BotCommitments;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision.*;

/** On-demand read-only inspection of one bot. No roster scan, scheduler, executor, database or model calls. */
public final class LifeInspection {
    private LifeInspection() {}

    public record Snapshot(String name, int level, int mapId, String role, String state,
                           int hpPotions, int restockBelow, double bagPressure, boolean commitment,
                           boolean conversation) {
        public Needs previewNeeds() {
            // Diagnostic calibration only: empty HP pots = 1; at the bot's existing threshold = 0.
            // MP/ammo requirements and time-based needs have not been adapted and remain unknown.
            double supplies = Math.max(0, 1 - hpPotions / (double) Math.max(1, restockBelow));
            return new Needs(Map.of(Need.SUPPLIES, supplies, Need.BAG_SPACE, bagPressure));
        }
    }

    public static Snapshot capture(BotSM bot) {
        Character chr = bot.getChr();
        if (!BotHelpers.isBot(chr)) {
            throw new IllegalArgumentException("inspection requires a bot");
        }
        double bagPressure = 0;
        for (InventoryType type : List.of(InventoryType.EQUIP, InventoryType.USE, InventoryType.SETUP,
                InventoryType.ETC)) {
            Inventory inventory = chr.getInventory(type);
            inventory.lockInventory();
            try {
                int capacity = Byte.toUnsignedInt(inventory.getSlotLimit());
                if (capacity > 0) {
                    bagPressure = Math.max(bagPressure, 1 - inventory.getNumFreeSlot() / (double) capacity);
                }
            } finally {
                inventory.unlockInventory();
            }
        }
        return new Snapshot(chr.getName(), chr.getLevel(), chr.getMapId(), bot.getBotType(),
                String.valueOf(bot.getState()), BotVitals.potionCount(chr, true),
                BotHabits.of(chr.getId()).restockBelow(), bagPressure, BotCommitments.inFlight(bot),
                BotConversation.hasAnyChat(chr.getId()));
    }

    /** Two measured candidates only. This is not a personality assignment or an executable plan. */
    public static Result preview(Snapshot snapshot) {
        return LifeDecision.evaluate(snapshot.previewNeeds(), List.of(
                new Option(Activity.RESTOCK, Map.of(Need.SUPPLIES, 1.0), 0, 0, true,
                        "HP potion pressure only; MP/ammo and shop reachability unknown"),
                new Option(Activity.SELL, Map.of(Need.BAG_SPACE, 1.0), 0, 0, true,
                        "fullest carried bag; sellable items and shop reachability unknown")),
                null, 0, 0, 0, activity -> 0, snapshot.commitment() || snapshot.conversation());
    }

    public static List<String> describe(Snapshot snapshot, Result result) {
        List<String> lines = new ArrayList<>();
        lines.add("Bot life PREVIEW ONLY: " + snapshot.name() + " lv " + snapshot.level() + ", map "
                + snapshot.mapId() + ", " + snapshot.role() + "/" + snapshot.state());
        lines.add("HP pots " + snapshot.hpPotions() + ", existing restock threshold " + snapshot.restockBelow()
                + "; diagnostic needs " + snapshot.previewNeeds().values());
        lines.add("Unknown: MP/ammo, boredom, social, fatigue, progress, destinations and activity duration.");
        lines.add("Commitment " + snapshot.commitment() + ", conversation " + snapshot.conversation()
                + "; " + result.outcome() + ". No activity is executed; no production personality is assigned.");
        for (Score score : result.topThree()) {
            lines.add(String.format(Locale.ROOT, "%s %.3f: %s", score.activity(), score.total(), score.reason()));
        }
        return List.copyOf(lines);
    }
}
