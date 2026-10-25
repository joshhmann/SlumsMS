package scripting;

import constants.game.FourthJobMasteries;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 1.5.2: the Job Master's 4th-job advancement gave no master levels; the Leafre instructors give them. */
class FourthJobMasteriesTest {

    @Test
    void tableMatchesWhatTheLeafreScriptsTeach() throws Exception {
        Pattern job = Pattern.compile("getJobId\\(\\) == (\\d+)\\) \\{((?:\\s*cm\\.teachSkill\\(\\d+, 0, 10, -1\\);)+)");
        Pattern skill = Pattern.compile("teachSkill\\((\\d+)");
        int seen = 0;
        for (String npc : new String[]{"2081100", "2081200", "2081300", "2081400", "2081500"}) {
            String src = Files.readString(Path.of("scripts/npc/" + npc + ".js"));
            String afterChange = src.substring(src.indexOf("cm.changeJobById"));
            Matcher m = job.matcher(afterChange.substring(0, 1500));
            while (m.find()) {
                List<Integer> expected = new ArrayList<>();
                Matcher sm = skill.matcher(m.group(2));
                while (sm.find()) expected.add(Integer.parseInt(sm.group(1)));
                int[] actual = FourthJobMasteries.forJob(Integer.parseInt(m.group(1)));
                assertEquals(expected, java.util.Arrays.stream(actual).boxed().toList(), "job " + m.group(1) + " in " + npc);
                seen++;
            }
        }
        assertEquals(12, seen);
    }

    @Test
    void jobMasterScriptAsksForTheMasteries() throws Exception {
        assertTrue(Files.readString(Path.of("scripts/npc/9010003.js")).contains("cm.teachFourthJobMasteries(job)"));
    }

    @Test
    void teachesMasterLevelTenAndNothingForOtherJobs() {
        NPCConversationManager cm = mock(NPCConversationManager.class, CALLS_REAL_METHODS);
        doNothing().when(cm).teachSkill(anyInt(), anyByte(), anyByte(), anyLong());
        cm.teachFourthJobMasteries(512);
        for (int id : new int[]{5121001, 5121002, 5121007, 5121009}) {
            verify(cm).teachSkill(id, (byte) 0, (byte) 10, -1L);
        }
        clearInvocations(cm);
        cm.teachFourthJobMasteries(1112);
        cm.teachFourthJobMasteries(111);
        verify(cm, never()).teachSkill(anyInt(), anyByte(), anyByte(), anyLong());
    }
}
