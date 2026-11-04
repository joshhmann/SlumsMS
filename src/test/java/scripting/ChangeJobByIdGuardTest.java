package scripting;

import client.Client;
import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: quest scripts advance the player with qm.changeJobById, and two of them ask for jobs this server has no
 * {@link Job} entry for - 501 Cannoneer (scripts/quest/2570.js:33) and 3200 Battle Mage
 * (scripts/quest/23011.js:22). Job.getById hands back null for those, and Character.changeJob(null) turns out to be
 * a silent return, so the script finished the quest, granted its items and completed it while the job never changed
 * and nothing was logged. Guarding on the manager keeps a wrong id from passing for success.
 */
class ChangeJobByIdGuardTest {
    private final Character player = mock(Character.class);
    private final NPCConversationManager cm = new NPCConversationManager(mock(Client.class), 1012100, "1012100") {
        @Override
        public Character getPlayer() {
            return player;
        }
    };

    @Test
    void aJobIdTheServerDoesNotHaveChangesNothing() {
        assertFalse(cm.changeJobById(501), "Cannoneer is not a job this server has");
        assertFalse(cm.changeJobById(3200), "Battle Mage is not a job this server has");
        verify(player, never()).changeJob(any());
    }

    @Test
    void aKnownJobIdStillChangesTheJob() {
        assertTrue(cm.changeJobById(Job.WARRIOR.getId()), "the normal job advance must keep working");
        verify(player).changeJob(Job.WARRIOR);
    }
}
