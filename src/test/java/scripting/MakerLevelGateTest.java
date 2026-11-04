package scripting;

import org.junit.jupiter.api.Test;

import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Owner decision (c), card b292: every Maker recipe is gated at the character level stored in its
 * {@code requiredLevel}. The gate sits where the craft is executed (ItemCrafter.tryCrafting), so a fully stocked
 * low-level character gets nothing and loses nothing however the craft is reached.
 */
class MakerLevelGateTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of NPCConversationManager that ItemCrafter touches for a plain (no reagent, no stimulant) craft. */
    public static final class FakeCm {
        final int level;
        final List<String> log = new ArrayList<>();

        FakeCm(int level) {
            this.level = level;
        }

        public int getLevel() {
            return level;
        }

        public int getItemQuantity(int itemId) {
            return 100;
        }

        public void gainItem(int itemId, int quantity, boolean show) {
            log.add("item " + itemId + " " + quantity);
        }

        public void gainMeso(int amount, boolean show) {
            log.add("meso " + amount);
        }
    }

    private static List<String> craft(int characterLevel, int recipeLevel) throws Exception {
        ScriptEngine engine = new Loader().load("lib.js");
        assertNotNull(engine);
        FakeCm cm = new FakeCm(characterLevel);
        engine.put("cm", cm);
        engine.eval("load('scripts/npc/lib/Crafting.js'); load('scripts/npc/lib/ItemCrafter.js');");
        engine.put("recipeLevel", recipeLevel);
        engine.eval("""
                const recipe = { itemId: 1302000, quantity: 1, cost: 5000, stimulant: 0, requiredLevel: recipeLevel,
                                 materials: [{ itemId: 4011000, amount: 3, processStarforce: false }] };
                // the constructor reads item data (WZ), which a checkout may not have; the craft path does not need it here
                const crafter = Object.create(ItemCrafter.prototype);
                Object.assign(crafter, { cm: cm, selectedRecipe: recipe, selectedRecipeAmount: 1, reagentItems: [],
                                         starForceTargetItemSlot: null, isUsingStimulant: false,
                                         ItemConstants: { isWeapon: id => true }, NpcMakerCraftingService: { useMakerPermissionAtkUp: () => false } });
                crafter.getRecipeCraftingFee = () => recipe.cost;
                globalThis.crafted = crafter.tryCrafting().map(r => r[0]).join(',');
                """);
        cm.log.add("results " + engine.get("crafted"));
        return cm.log;
    }

    @Test
    void aLevelOneCharacterIsRefusedALevel190RecipeAndNothingIsTaken() throws Exception {
        List<String> log = craft(1, 190);
        // CraftingResult.LevelTooLow (4); no item given, no meso, no material taken
        assertEquals(List.of("results 4"), log);
    }

    @Test
    void oneLevelShortIsStillRefused() throws Exception {
        assertEquals(List.of("results 4"), craft(189, 190));
    }

    @Test
    void aCharacterAtTheRecipeLevelCraftsIt() throws Exception {
        List<String> log = craft(190, 190);
        assertTrue(log.contains("item 4011000 -3"), log.toString());
        assertTrue(log.contains("meso -5000"), log.toString());
        assertTrue(log.contains("item 1302000 1"), log.toString());
        assertEquals("results 0", log.get(log.size() - 1));
    }
}
