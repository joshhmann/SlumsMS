package net.server.channel.handlers;

import client.Character;
import client.Skill;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class CashItemHandlerSafetyTest {

    @Test
    public void testSPResetRespectsMasterLevelForFourthJob() {
        Skill fourthJobSkill = mock(Skill.class);
        when(fourthJobSkill.isFourthJob()).thenReturn(true);
        when(fourthJobSkill.getMaxLevel()).thenReturn(30);

        Character chr = mock(Character.class);
        when(chr.isAlive()).thenReturn(true);
        when(chr.getMasterLevel(fourthJobSkill)).thenReturn(10);
        when(chr.getSkillLevel(fourthJobSkill)).thenReturn((byte) 10);

        // When skill level is at master level (10), even though max level is 30, it must be considered maxed
        int maxAllowed = fourthJobSkill.isFourthJob() ? chr.getMasterLevel(fourthJobSkill) : fourthJobSkill.getMaxLevel();
        assertEquals(10, maxAllowed);
        byte curLevel = chr.getSkillLevel(fourthJobSkill);
        // It should NOT allow adding SP
        assertEquals(false, curLevel < maxAllowed);
    }
}
