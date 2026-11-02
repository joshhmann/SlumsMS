package scripting;

import client.Character;
import client.Job;
import org.junit.jupiter.api.Test;
import scripting.npc.NPCConversationManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 ticket 164: the Job Master (scripts/npc/9010003.js) is driven here through its real Graal script and a fake
 * {@code cm}, so what players are actually offered is asserted, not the source text.
 *
 * <p>What went wrong: the "next job" branches build their id as {@code currentJobId + 1}, and for a job whose line ends
 * there that still produces one. A SUPERGM (910) at level 70 was offered 911 and an EVAN (2001) at level 120 was offered
 * 2002, neither of which is a {@link Job} this server has, so the advancement failed while the script took the fee,
 * handed out the rewards and closed without a word.
 *
 * <p>What did not: the level-1 offer of Beginner / Noblesse / Legend is a recorded owner decision (divergence-ledger
 * SK-035 and SK-044, both "keep"). A rebirth rewinds a character to level 1 (Character.executeRebornAs does
 * setLevel(0) then levelUp) so it can re-pick its path, and the offer is deliberately open to every level-1 character.
 * {@link #aLevelOneCharacterIsStillOfferedBeginnerNoblesseAndLegend()} pins that.
 */
class JobMasterOffersTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private Character chr;
    private NPCConversationManager cm;
    /** Every "Choose your Job" list start() sent, in order. */
    private final List<String> lists = new ArrayList<>();

    /** Loads scripts/npc/9010003.js against a character with that job, level, rebirth count and item 4036541 flag. */
    private Invocable jobMaster(int jobId, int level, int reborns, boolean hasItem) {
        chr = mock(Character.class);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getReborns()).thenReturn(reborns);
        when(chr.isBeginnerJob()).thenReturn(jobId == 0 || jobId == 1000 || jobId == 2000);

        cm = mock(NPCConversationManager.class, CALLS_REAL_METHODS);
        // doReturn, not when(...): the mock calls real methods, and the real getPlayer() dereferences a null Client
        doReturn(chr).when(cm).getPlayer();
        doReturn(chr).when(cm).getChar();
        doReturn(jobId).when(cm).getJobId();
        doReturn(hasItem).when(cm).hasItem(anyInt());
        doReturn(1_000_000_000).when(cm).getMeso();
        doAnswer(call -> String.valueOf(call.getArgument(0, Integer.class))).when(cm).getJobName(anyInt());
        doAnswer(call -> lists.add(call.getArgument(0, String.class))).when(cm).sendSimple(anyString());
        doNothing().when(cm).sendOk(anyString());
        doNothing().when(cm).dispose();
        doNothing().when(cm).sendYesNo(anyString());
        doNothing().when(cm).gainItem(anyInt(), anyShort());
        doNothing().when(cm).gainItem(anyInt(), anyShort(), anyBoolean());
        doNothing().when(cm).teachSkill(anyInt(), anyByte(), anyByte(), anyLong());

        ScriptEngine engine = new Loader().load("npc/9010003.js");
        assertNotNull(engine, "scripts/npc/9010003.js evaluates");
        engine.put("cm", cm);
        return (Invocable) engine;
    }

    /** The job ids of the single list start() built (the fake getJobName is the id itself). */
    private List<Integer> offered() {
        assertEquals(1, lists.size(), "start() offered one list: " + lists);
        List<Integer> ids = new ArrayList<>();
        Matcher m = Pattern.compile("#L\\d+#(\\d+)#l").matcher(lists.get(0));
        while (m.find()) {
            ids.add(Integer.parseInt(m.group(1)));
        }
        return ids;
    }

    @Test
    void aLevelOneCharacterIsStillOfferedBeginnerNoblesseAndLegend() throws Exception {
        // Kept divergence: divergence-ledger SK-035 ("offers every advancement from level 1 to 5th job with no tests or
        // items", keep) and SK-044 ("Job Master lets a level-1 character swap Beginner / Noblesse / Legend", keep).
        // Deliberate, so this test pins it: do not add a rebirth condition or a level gate here.
        Invocable script = jobMaster(0, 1, 0, false);
        script.invokeFunction("start");
        assertEquals(List.of(0, 1000, 2000), offered(),
                "the broad level-1 offer is a recorded owner decision, not a defect");
    }

    @Test
    void everyOfferedJobIsAJobThisServerHas() throws Exception {
        int[][] cases = {{0, 10}, {100, 30}, {110, 30}, {110, 70}, {111, 120}, {112, 120},
                {1111, 120}, {1112, 120}, {2111, 120}, {1000, 10}, {2000, 10}, {700, 120},
                {910, 70}, {910, 200}, {2001, 120}};
        for (int[] c : cases) {
            int jobId = c[0];
            int level = c[1];
            lists.clear();
            Invocable script = jobMaster(jobId, level, 3, true);
            script.invokeFunction("start");
            List<Integer> ids = lists.isEmpty() ? List.of() : offered();
            for (int id : ids) {
                assertNotNull(Job.getById(id), "job " + jobId + " at level " + level + " was offered " + id
                        + ", which Job.java does not declare");
            }
            if (jobId == 910 || jobId == 2001) {
                assertEquals(List.of(), ids, "job " + jobId + " has no next job this server can give");
            }
        }
    }

    @Test
    void aRefusedAdvancementIsReportedAndPaysAndGivesNothing() throws Exception {
        Invocable script = jobMaster(110, 70, 0, false);
        script.invokeFunction("start");            // offers 111, whose test costs 75m to skip
        script.invokeFunction("action", 1, 0, 0);
        when(cm.changeJobById(111)).thenReturn(false); // the manager refused the id: nothing changed
        script.invokeFunction("action", 1, 1, 0);

        verify(cm).sendOk(contains("failed"));
        verify(cm, never()).gainItem(anyInt(), anyShort());
        verify(cm, never()).gainItem(anyInt(), anyShort(), anyBoolean());
        verify(cm, never()).teachFourthJobMasteries(anyInt());
    }

    @Test
    void aSuccessfulAdvancementStillChargesTheFeeAndRewards() throws Exception {
        Invocable script = jobMaster(110, 70, 0, false);
        script.invokeFunction("start");
        script.invokeFunction("action", 1, 0, 0);
        script.invokeFunction("action", 1, 1, 0);

        verify(cm).changeJobById(111);
        verify(chr).gainMeso(-75_000_000);
        verify(cm, atLeastOnce()).gainItem(eq(2000005), anyShort());
    }
}
