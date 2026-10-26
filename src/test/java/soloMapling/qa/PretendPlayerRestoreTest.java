package soloMapling.qa;

import client.Character;
import client.Job;
import client.Skill;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Auto-QA 1.5.2: the vb-* resets left AutoQaOne at level 25-30 (it was 70), so t-mr-1 could not propose the next run
 * (level 50 needed). The state before the first reset is kept and put back at the run's stop.
 */
class PretendPlayerRestoreTest {

    @Test
    void theSnapshotKeepsJobLevelMesosAndSkillsAsTheyWere() {
        Skill rage = mock(Skill.class);
        Map<Skill, Character.SkillEntry> skills = new HashMap<>();
        skills.put(rage, new Character.SkillEntry((byte) 20, 20, -1));
        Character chr = mock(Character.class);
        when(chr.getJob()).thenReturn(Job.FIGHTER);
        when(chr.getLevel()).thenReturn(70);
        when(chr.getMeso()).thenReturn(5_000_000);
        when(chr.getSkills()).thenReturn(skills);

        GameQaWorld.PretendSnapshot s = GameQaWorld.snapshot(chr);
        skills.get(rage).skillevel = 0; // the reset changes the live entry; the snapshot must not follow
        assertEquals(110, s.job());
        assertEquals(70, s.level());
        assertEquals(5_000_000, s.meso());
        assertEquals(20, s.skills().get(rage).skillevel);
    }

    @Test
    void restorePutsLevelJobMesosAndSkillsBack() {
        Skill rage = mock(Skill.class);
        Skill fromTheReset = mock(Skill.class);
        GameQaWorld.PretendSnapshot before = new GameQaWorld.PretendSnapshot(110, 70, 5_000_000,
                Map.of(rage, new Character.SkillEntry((byte) 20, 20, -1)));
        Character chr = mock(Character.class);
        when(chr.getJob()).thenReturn(Job.WARRIOR);
        when(chr.getMeso()).thenReturn(1_000_000);
        Map<Skill, Character.SkillEntry> now = new HashMap<>();
        now.put(fromTheReset, new Character.SkillEntry((byte) 1, 0, -1));
        when(chr.getSkills()).thenReturn(now);

        GameQaWorld.restore(chr, before);

        verify(chr).changeJob(Job.FIGHTER);
        verify(chr).setLevel(70);
        verify(chr).gainMeso(4_000_000, false);
        verify(chr).changeSkillLevel(fromTheReset, (byte) -1, 0, -1);
        verify(chr).changeSkillLevel(rage, (byte) 20, 20, -1);
    }

    @Test
    void closeWindowsShutsTheStorageASyntheticClientLeftOpen() {
        // TEST 2026-10-26: vb-7 left AutoQaOne's storage open, PlayerBusy held it busy and t-mb-1 lost its offer
        server.Storage storage = mock(server.Storage.class);
        when(storage.isOpen()).thenReturn(true);
        Character chr = mock(Character.class);
        when(chr.getStorage()).thenReturn(storage);

        GameQaWorld.closeWindows(chr);

        verify(storage).close();
    }

    @Test
    void closeWindowsLeavesAClosedOrMissingStorageAlone() {
        server.Storage storage = mock(server.Storage.class);
        Character chr = mock(Character.class);
        when(chr.getStorage()).thenReturn(storage);
        GameQaWorld.closeWindows(chr);
        verify(storage, org.mockito.Mockito.never()).close();
        GameQaWorld.closeWindows(mock(Character.class));
    }
}
