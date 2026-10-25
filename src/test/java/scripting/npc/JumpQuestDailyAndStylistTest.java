package scripting.npc;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.2 NPC catalogue fixes: Jump Quest chests pay their custom reward once a day; the stylist's hidden catalogs are shut. */
class JumpQuestDailyAndStylistTest {

    @Test
    void eachChestPaysOncePerDay() {
        String first = NPCConversationManager.jumpQuestClaim("", "2026-10-25", 1063000);
        assertEquals("2026-10-25:1063000", first);
        assertNull(NPCConversationManager.jumpQuestClaim(first, "2026-10-25", 1063000), "a second run the same day pays nothing");
        String other = NPCConversationManager.jumpQuestClaim(first, "2026-10-25", 2032003);
        assertEquals("2026-10-25:1063000,2032003", other, "another chest still pays");
        assertEquals("2026-10-26:1063000", NPCConversationManager.jumpQuestClaim(other, "2026-10-26", 1063000), "a new day resets");
        assertEquals("2026-10-25:1063001", NPCConversationManager.jumpQuestClaim(null, "2026-10-25", 1063001));
    }

    @Test
    void everyChestScriptAsksForTheDailyClaim() throws Exception {
        for (int chest : new int[]{1063000, 1063001, 1063002, 2032003}) {
            String js = Files.readString(Path.of("scripts/npc/" + chest + ".js"));
            assertTrue(js.contains("cm.claimJumpQuestReward(" + chest + ")"), chest + " pays without the daily claim");
        }
    }

    @Test
    void stylistRefusesSelectionsTheMenuDoesNotShow() throws Exception {
        String js = Files.readString(Path.of("scripts/npc/STYLIST.js"));
        assertTrue(js.contains("[0, 1, 2, 3, 4, 5, 6].indexOf(selected) < 0"), "hidden catalogs must be refused");
    }
}
