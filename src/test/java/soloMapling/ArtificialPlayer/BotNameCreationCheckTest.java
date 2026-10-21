package soloMapling.ArtificialPlayer;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// The name check behind character creation, the login name check and cash-shop name changes refuses a name a stored
// roster bot holds (SoloMapling 4fc53172, carried over in the persistence-fork merge). Bots have no characters rows,
// so the check goes through BotNames, which is loaded from the botstore roster.
class BotNameCreationCheckTest {

    private String enabled;
    private String templateId;

    @BeforeEach
    void setUp() {
        enabled = System.getProperty("solomapling.enabled");
        templateId = System.getProperty("solomapling.template-id");
        System.setProperty("solomapling.enabled", "true");
        System.setProperty("solomapling.template-id", "1");
        BotNames.useSourcesForTest(new BotNames.Sources(() -> List.of("Spare"), List::of,
                () -> List.of(new BotNames.StoredName(BotStore.BOT_ID_BASE + 42, "Celare", 1)), new Random(7)));
    }

    @AfterEach
    void tearDown() {
        restore("solomapling.enabled", enabled);
        restore("solomapling.template-id", templateId);
        BotNames.useSourcesForTest(null);
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }

    @Test
    void aPlayerCannotCreateACharacterWithAStoredBotsName() {
        assertFalse(Character.canCreateChar("Celare"));
        assertFalse(Character.canCreateChar("cELARE"), "ignoring case");
        assertTrue(Character.canCreateChar("Wanted"));
        assertTrue(BotNames.isTaken("wanted"), "a name a player passed is kept from bots");
    }
}
