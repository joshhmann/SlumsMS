package scripting;

import client.Character;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 1.6.4 ticket 164: the Job Master (scripts/npc/9010003.js) is driven here through its real Graal script and a fake
 * {@code cm}, so what players are actually offered is asserted, not the source text.
 *
 * <p>Its level-1 branch offered Beginner / Noblesse / Legend to anyone standing in Ellinia at level 1, not only to the
 * characters a rebirth had just rewound to level 1 (Character.executeRebornAs does setLevel(0) then levelUp).
 */
class JobMasterOffersTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    private Character chr;
    private NPCConversationManager cm;
    /** Every "Choose your Job" list start() sent. */
    private final List<String> lists = new ArrayList<>();

    /** Loads scripts/npc/9010003.js against a character with that job, level, rebirth count and item 4036541 flag. */
    private Invocable jobMaster(int jobId, int level, int reborns, boolean hasItem) {
        chr = mock(Character.class);
        when(chr.getLevel()).thenReturn(level);
        when(chr.getReborns()).thenReturn(reborns);
        when(chr.isBeginnerJob()).thenReturn(jobId == 0 || jobId == 1000 || jobId == 2000);

        cm = mock(NPCConversationManager.class, CALLS_REAL_METHODS);
        // doReturn, not when(...): the mock runs real methods, and the real getPlayer() dereferences a null Client
        doReturn(chr).when(cm).getPlayer();
        doReturn(chr).when(cm).getChar();
        doReturn(jobId).when(cm).getJobId();
        doReturn(hasItem).when(cm).hasItem(anyInt());
        doReturn(1_000_000_000).when(cm).getMeso();
        doAnswer(call -> String.valueOf(call.getArgument(0, Integer.class))).when(cm).getJobName(anyInt());
        doAnswer(call -> lists.add(call.getArgument(0, String.class))).when(cm).sendSimple(anyString());
        doNothing().when(cm).sendOk(anyString());
        doNothing().when(cm).sendYesNo(anyString());
        doNothing().when(cm).dispose();

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
    void aLevelOneCharacterWhoNeverRebirthedIsNotOfferedAPath() throws Exception {
        Invocable script = jobMaster(0, 1, 0, false);
        script.invokeFunction("start");
        assertEquals(List.of(), lists, "a fresh level-1 Beginner in Ellinia must not be switched to Noblesse/Legend");
        verify(cm).sendOk(contains("not eligible"));
    }

    @Test
    void aRebirthedLevelOneCharacterStillPicksItsPath() throws Exception {
        Invocable script = jobMaster(0, 1, 1, false);
        script.invokeFunction("start");
        assertEquals(List.of(0, 1000, 2000), offered(),
                "a rebirth rewinds the character to level 1 to pick its path again: that is what the branch is for");
    }
}
