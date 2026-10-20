package soloMapling.dashboard;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 35: the grind bot's phase is read reflectively; this breaks loudly if it is renamed. */
class ServerSourcesTest {

    @Test
    void everyGrindPhaseMapsToAnActivity() {
        Field phase = ServerSources.phaseField();
        assertNotNull(phase, "TrainingBot.phase was renamed: update ServerSources.activityOf");
        Set<String> known = Set.of("grind", "travel", "town", "break", "idle");
        for (Object constant : phase.getType().getEnumConstants()) {
            String activity = ServerSources.activityOfPhase(constant.toString());
            assertTrue(known.contains(activity), constant + " -> " + activity);
        }
    }
}
