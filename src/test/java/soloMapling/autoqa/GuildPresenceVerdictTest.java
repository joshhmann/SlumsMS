package soloMapling.autoqa;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** rc6 (owner): the director keeps one member of every bot guild online, so t-bg-1 wants no empty guild again. */
class GuildPresenceVerdictTest {

    static final long TEN_MIN = TimeUnit.MINUTES.toMillis(10);

    @Test
    void oneEmptyGuildFailsOnceTheDirectorHasRunTenMinutes() {
        String v = GameAutoQaWorld.emptyGuildsVerdict(1, TEN_MIN);
        assertNotNull(v);
        assertTrue(v.contains("1 guild(s) with nobody online"), v);
        assertNotNull(GameAutoQaWorld.emptyGuildsVerdict(2, TimeUnit.HOURS.toMillis(3)),
                "the triage branch allowed two; the owner wants none");
    }

    @Test
    void everyGuildWithSomeoneOnPasses() {
        assertNull(GameAutoQaWorld.emptyGuildsVerdict(0, TimeUnit.HOURS.toMillis(3)));
    }

    @Test
    void justAfterBootOrWithTheDirectorOffEmptyGuildsAreNotJudged() {
        assertNull(GameAutoQaWorld.emptyGuildsVerdict(3, TEN_MIN - 1));
        assertNull(GameAutoQaWorld.emptyGuildsVerdict(3, 0));
    }
}
