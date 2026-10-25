package client;

import net.packet.Packet;
import org.junit.jupiter.api.Test;
import scripting.AbstractPlayerInteraction;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * 1.5.2: MapleRoot's Skill.wz has no Big Bang, Barrage, Ninja Ambush, Polearm Booster and 60 more stock skills, but
 * stock scripts still teach them (Leafre 4th-job instructors, Aran quests 21101/21720, Kyrin's test). The skill
 * came back null, changeSkillLevel put a null key into the skills map and then threw, and every later save of the
 * character failed on that key and rolled back.
 */
class UnknownSkillTest {

    private static Character playerWithSkills(Map<Skill, Character.SkillEntry> skills) throws Exception {
        Character chr = mock(Character.class, CALLS_REAL_METHODS);
        doNothing().when(chr).sendPacket(any(Packet.class));
        Field f = Character.class.getDeclaredField("skills");
        f.setAccessible(true);
        f.set(chr, skills);
        return chr;
    }

    @Test
    void aSkillMissingFromSkillWzIsIgnoredAndNeverStored() throws Exception {
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        Character chr = playerWithSkills(skills);

        assertDoesNotThrow(() -> chr.changeSkillLevel(null, (byte) 0, 10, -1));
        assertFalse(skills.containsKey(null), "a null skill must not reach the skills map (saveToDB fails on it)");
        assertTrue(skills.isEmpty());
    }

    @Test
    void scriptsTeachingARemovedSkillCarryOn() throws Exception {
        Map<Skill, Character.SkillEntry> skills = new LinkedHashMap<>();
        Character chr = playerWithSkills(skills);
        AbstractPlayerInteraction cm = mock(AbstractPlayerInteraction.class, CALLS_REAL_METHODS);
        doReturn(chr).when(cm).getPlayer();

        // 2121001 Big Bang: taught by Gritto (2081200) right after the 4th-job change; not in our Skill.wz.
        assertDoesNotThrow(() -> cm.teachSkill(2121001, (byte) 0, (byte) 10, -1));
        assertFalse(skills.containsKey(null));
    }
}
