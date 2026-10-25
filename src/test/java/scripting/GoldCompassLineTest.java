package scripting;

import client.Character;
import client.Client;
import client.Job;
import constants.game.SkillLines;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 1.5.2: the Gold Compass (2084000) sold every class's skills; it now sells only the player's own line. */
class GoldCompassLineTest {

    private static NPCConversationManager npc(Job job, String rebornJson) {
        Character chr = mock(Character.class);
        when(chr.getJob()).thenReturn(job);
        when(chr.getAllRebornDataCerezeth()).thenReturn(rebornJson);
        Client c = mock(Client.class);
        when(c.getPlayer()).thenReturn(chr);
        return new NPCConversationManager(c, 2084000, "2084000");
    }

    @Test
    void lineHoldsEarlierAdvancementsOfTheSameBranchOnly() {
        assertEquals(java.util.Set.of(112, 111, 110, 100, 0), SkillLines.jobsOnLine(112));
        assertEquals(java.util.Set.of(1112, 1111, 1110, 1100, 1000), SkillLines.jobsOnLine(1112));
        assertEquals(java.util.Set.of(0), SkillLines.jobsOnLine(0));
    }

    @Test
    void aHeroGetsHeroSkillsButNotBishopOrCygnusOnes() {
        NPCConversationManager cm = npc(Job.HERO, "[]");
        assertTrue(cm.isSkillOnOwnLine(1121010)); // Enrage (Hero)
        assertTrue(cm.isSkillOnOwnLine(1005));    // Echo of Hero
        assertFalse(cm.isSkillOnOwnLine(2321003)); // Bahamut (Bishop)
        assertFalse(cm.isSkillOnOwnLine(11121204)); // Cygnus
        assertFalse(cm.isSkillOnOwnLine(1221002)); // Paladin: other branch of the same family
    }

    @Test
    void previousRebirthJobsKeepTheirSkillsToo() {
        NPCConversationManager cm = npc(Job.BEGINNER, "[{\"job\":232,\"reborns\":1}]");
        assertTrue(cm.isSkillOnOwnLine(2321003));
        assertFalse(cm.isSkillOnOwnLine(1121010));
    }

    @Test
    void theScriptRoutesEveryTeachThroughTheFilter() throws Exception {
        String src = Files.readString(Path.of("scripts/npc/2084000.js"));
        assertEquals(1, src.split("cm\\.teachSkill\\(", -1).length - 1, "only the filter itself may call cm.teachSkill");
        assertTrue(src.contains("cm.isSkillOnOwnLine(skillId)"));
    }
}
