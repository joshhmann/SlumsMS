package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import soloMapling.qa.QaLog;
import soloMapling.qa.QaLogTestAccess;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/*
 * TEST 2026-10-30: `!qa run dj-1` (a COMBAT run watching the dojo) heard no follower line from the Zakum altar - the
 * line went by map. A real player's follower now logs to the run that player started, on any map.
 */
class FollowerFightLogTest {

    private static final int DOJO = 925020100;
    private static final int ZAKUM_ALTAR = 280030000;

    @TempDir
    Path dir;

    @AfterEach
    void clean() {
        QaLogTestAccess.reset();
    }

    private static Character character(int id, String name) {
        Character c = mock(Character.class);
        when(c.getId()).thenReturn(id);
        when(c.getName()).thenReturn(name);
        when(c.getMapId()).thenReturn(ZAKUM_ALTAR);
        return c;
    }

    @Test
    void aPlayersFollowerLineReachesThePlayersRunOnAMapItDoesNotWatch() throws Exception {
        List<String> lines = new ArrayList<>();
        Character gm = character(5, "Owner");
        QaLogTestAccess.listen(5, Set.of(DOJO), EnumSet.of(QaLog.Topic.COMBAT), dir, lines);
        FollowerFight.log(character(1_000_000_123, "Follower"), gm, "hits Zakum for 900 skill 1001004");
        assertEquals(List.of("COMBAT FOLLOWER FIGHT Follower hits Zakum for 900 skill 1001004"), lines.stream()
                .filter(l -> l.contains("FOLLOWER FIGHT")).toList());
    }

    @Test
    void anotherGmsRunDoesNotHearIt() throws Exception {
        List<String> lines = new ArrayList<>();
        QaLogTestAccess.listen(6, Set.of(), EnumSet.of(QaLog.Topic.COMBAT), dir, lines);
        FollowerFight.log(character(1_000_000_124, "Follower"), character(5, "Owner"), "walks to Zakum (300 px away)");
        assertTrue(lines.stream().noneMatch(l -> l.contains("FOLLOWER FIGHT")));
    }
}
