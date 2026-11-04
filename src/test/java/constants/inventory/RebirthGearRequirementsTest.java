package constants.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class RebirthGearRequirementsTest {
    @Test void allSixteenClassBossWeaponsUnlockAtSecondRebirth() throws Exception {
        JsonNode data=new ObjectMapper().readTree(Path.of("scripts/json/weapons.json").toFile());
        int checked=0;
        for (JsonNode groups:data) for (JsonNode group:groups) if (group.get("name").asText().equals("Lionheart")) {
            for (JsonNode recipe:group.get("recipes")) {
                int id=recipe.get("itemId").asInt();
                assertEquals(2,ItemConstants.getItemRebirthRequirement(id),"item "+id);
                assertTrue(ItemConstants.getRestrictedItemsForRebirth(1).contains(id));
                assertFalse(ItemConstants.getRestrictedItemsForRebirth(2).contains(id));
                checked++;
            }
        }
        assertEquals(16,checked);
    }
    @Test void normalGearStillWorksBeforeAnyRebirth() {
        for(int id:new int[]{1402236,1402222,1402220,1003976,1052669,1032191,1132230,1113038}) {
            assertEquals(0,ItemConstants.getItemRebirthRequirement(id));
            assertFalse(ItemConstants.getRestrictedItemsForRebirth(0).contains(id));
        }
    }
}
