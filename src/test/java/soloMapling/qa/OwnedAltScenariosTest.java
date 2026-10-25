package soloMapling.qa;

import config.YamlConfig;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OwnedAltScenariosTest {
    @Test void refusesDisabledFeatureAndExplainsRealCommandAndSaveChecks() {
        boolean enabled = YamlConfig.config.server.OWNED_ALTS_ENABLED;
        var scenario = new OwnedAltScenarios.Lifecycle();
        try {
            YamlConfig.config.server.OWNED_ALTS_ENABLED = false;
            assertThrows(QaRefusal.class, () -> scenario.stage(null));
            YamlConfig.config.server.OWNED_ALTS_ENABLED = true;
            String instructions = String.join("\n", scenario.stage(null));
            assertTrue(instructions.contains("@botme")); assertTrue(instructions.contains("@dismiss all"));
            assertTrue(instructions.contains("50 times")); assertTrue(instructions.contains("save committed"));
            assertEquals(java.util.Set.of(QaLog.Topic.OWNED_ALT),scenario.topics());
        } finally { YamlConfig.config.server.OWNED_ALTS_ENABLED = enabled; }
    }
}
