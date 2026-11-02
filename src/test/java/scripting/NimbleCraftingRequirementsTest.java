package scripting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.script.ScriptEngine;
import javax.script.ScriptException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Exercise the actual shared forge handler before any inventory-consuming crafter is constructed. */
class NimbleCraftingRequirementsTest {
    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load() { return getInvocableScriptEngine("npc/interactions/shared/MakerCrafting.js"); }
    }
    public static final class Input {
        public int id = 1003605;
        public int getItemId() { return id; }
        public short getQuantity() { return 1; }
    }
    public static final class Player {
        public int rebirths = 3;
        public int getReborns() { return rebirths; }
    }
    public static final class Cm {
        public int level = 160, crafts;
        public final Player player = new Player();
        public final Input input = new Input();
        public String message = "";
        public int getLevel() { return level; }
        public Player getPlayer() { return player; }
        public int getItemQuantity(int id) { return 999; }
        public boolean hasItem(int id, int amount) { return true; }
        public int getMeso() { return Integer.MAX_VALUE; }
        public boolean canHold(int id, int amount) { return true; }
        public Input getStarForceEquip(int slot) { return input; }
        public String numberWithCommas(int n) { return Integer.toString(n); }
        public void sendOk(String s) { message = s; }
        public void crafted() { crafts++; }
    }
    private ScriptEngine flow(Cm cm, int maker) throws Exception {
        ScriptEngine e = new Loader().load();
        assertNotNull(e);
        e.put("cm", cm);
        e.put("maker", maker);
        e.eval("""
            var flow = Object.create(MakerCraftingMenuSubInteraction.prototype);
            flow.cm = cm;
            flow.selectedRecipe = {itemId:1004426,quantity:1,cost:100,requiredLevel:150,requiredMakerLevel:3,
                materials:[{itemId:1003605,amount:1,processStarforce:true}]};
            flow.selectedRecipeAmount = 1;
            flow.starForceItemTargetSlot = 1;
            flow.availableStimulant = null;
            flow.isUsingStimulant = false;
            flow.getReagentItemIds = function(){return [];};
            flow.getCraftingFee = function(){return 100;};
            flow.goToMainMenu = function(){return 77;};
            flow.NpcMakerCraftingService = {getMakerSkillLevel:function(p){return maker;}};
            flow.ItemConstants = {getItemRebirthRequirement:function(id){return 3;}};
            ItemCrafter = class {constructor(){cm.crafted();throw new Error('CRAFT_ALLOWED');}};
            """);
        return e;
    }
    private void blocked(Cm cm, int maker, String expected) throws Exception {
        ScriptEngine e = flow(cm, maker);
        assertDoesNotThrow(() -> e.eval("flow.tryCraftingRecipe(1,0,0,1)"));
        assertEquals(0, cm.crafts);
        assertTrue(cm.message.contains(expected), cm.message);
    }
    @Test void levelIsRequiredEvenWithEveryMaterial() throws Exception {
        Cm cm = new Cm(); cm.level = 149; blocked(cm, 3, "150");
    }
    @Test void makerLevelIsEnforced() throws Exception { blocked(new Cm(), 2, "Maker"); }
    @Test void rebirthGateIsCheckedBeforeCrafting() throws Exception {
        Cm cm = new Cm(); cm.player.rebirths = 2; blocked(cm, 3, "rebirth");
    }
    @Test void selectedTransferItemMustMatchRecipe() throws Exception {
        Cm cm = new Cm(); cm.input.id = 1003601; blocked(cm, 3, "equipment");
    }
    @Test void validBoundaryCanReachCrafter() throws Exception {
        Cm cm = new Cm(); cm.level = 150;
        ScriptEngine e = flow(cm, 3);
        ScriptException failure = assertThrows(ScriptException.class, () -> e.eval("flow.tryCraftingRecipe(1,0,0,1)"));
        assertTrue(failure.getMessage().contains("CRAFT_ALLOWED"));
        assertEquals(1, cm.crafts);
    }
    @Test void zeroMakerRecipeDoesNotRequireMakerSkill() throws Exception {
        Cm cm = new Cm(); ScriptEngine e = flow(cm, 0);
        e.eval("flow.selectedRecipe.requiredMakerLevel=0");
        assertTrue(assertThrows(ScriptException.class, () -> e.eval("flow.tryCraftingRecipe(1,0,0,1)")).getMessage().contains("CRAFT_ALLOWED"));
    }
    @Test void pirateAbsolabConsumesMatchingSusanooSlot() throws Exception {
        JsonNode all = new ObjectMapper().readTree(Path.of("scripts/json/gearSets.json").toFile());
        int[] expected = {1003605,1052513,1072715,1082476,1102460};
        int checked = 0;
        for (JsonNode group : all.get("Pirate")) if (group.get("name").asText().equals("Absolab Set")) {
            for (JsonNode recipe : group.get("recipes")) {
                assertEquals(expected[checked++], recipe.get("materials").get(0).get("itemId").asInt());
                assertTrue(recipe.get("materials").get(0).get("processStarforce").asBoolean());
            }
        }
        assertEquals(5, checked);
    }
}
