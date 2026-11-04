package scripting;

import client.Character;
import client.FamilyEntry;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationOnMock;
import scripting.quest.QuestActionManager;

import javax.script.Invocable;
import javax.script.ScriptEngine;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * 1.6.4: quests 2233 ("Raise the Rep!") and 2234 ("Enjoy the Entitlement!") are script-ended on our data
 * (Quest.wz/Check.img.xml carries an {@code endscript} node in block "1" for both) but shipped no
 * scripts/quest/2233.js or scripts/quest/2234.js. QuestScriptManager.end therefore logged
 * "END Quest X is uncoded.", disposed, and left the quest STARTED for good; 2234 also requires 2233 COMPLETED
 * (Check.img.xml block "0"), so the whole Captain Al chain dead-ended at 2233.
 *
 * <p>Their conditions live in QuestInfo.img (2233: current Rep &gt;= 1,000; 2234: total Rep &gt;= 2,000 and current
 * Rep &lt; 500), which Check.img does not model, so the script is the only enforcement point. Act.img grants these
 * quests nothing, so the scripts must not pay out exp, meso or items either - that is what the reward assertions
 * below pin down.
 *
 * <p>Runs the real script files through the server's own Graal setup against a mock {@link QuestActionManager}.
 */
class FamilyQuestScriptsTest {
    private static final String Q2233 = "quest/2233.js";
    private static final String Q2234 = "quest/2234.js";

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** A {@code qm} whose player sits in a family with the given current and total reputation. */
    private static QuestActionManager familyQm(int reputation, int totalReputation) {
        QuestActionManager qm = mock(QuestActionManager.class);
        Character player = mock(Character.class);
        FamilyEntry entry = mock(FamilyEntry.class);
        when(qm.getPlayer()).thenReturn(player);
        when(player.getFamilyEntry()).thenReturn(entry);
        when(entry.getReputation()).thenReturn(reputation);
        when(entry.getTotalReputation()).thenReturn(totalReputation);
        return qm;
    }

    private static void runStart(String path, QuestActionManager qm) throws Exception {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " did not evaluate (the quest is uncoded and QuestScriptManager.end gives up)");
        engine.put("qm", qm);
        ((Invocable) engine).invokeFunction("start", 1, 0, 0);
    }

    private static void runEnd(String path, QuestActionManager qm) throws Exception {
        ScriptEngine engine = new Loader().load(path);
        assertNotNull(engine, path + " did not evaluate (the quest is uncoded and QuestScriptManager.end gives up)");
        engine.put("qm", qm);
        ((Invocable) engine).invokeFunction("end", 1, 0, 0);
    }

    /**
     * Neither quest pays anything (Act.img blocks "0" and "1" are empty), so no {@code gain*} call may appear.
     * Checked over every recorded invocation so an added overload cannot slip past.
     */
    private static void assertNoReward(QuestActionManager qm) {
        for (Invocation invocation : mockingDetails(qm).getInvocations()) {
            String name = invocation.getMethod().getName();
            assertTrue(!name.startsWith("gain"),
                    "these quests grant nothing in Act.img, but the script called " + name + "(" + invocation.getArguments().length + " args)");
        }
    }

    private static String lastDialog(QuestActionManager qm) {
        InvocationOnMock last = null;
        for (Invocation invocation : mockingDetails(qm).getInvocations()) {
            String name = invocation.getMethod().getName();
            if (name.equals("sendNext") || name.equals("sendOk")) {
                last = invocation;
            }
        }
        assertNotNull(last, "the player was told nothing");
        Object text = last.getArgument(0);
        assertTrue(text instanceof String, "the dialog was not text: " + text);
        return (String) text;
    }

    // ---- 2233: current reputation >= 1,000 ----

    @Test
    void raiseTheRepCompletesFromStartWhenThePlayerHasOneThousandRep() throws Exception {
        QuestActionManager qm = familyQm(1000, 1000);
        runStart(Q2233, qm);

        verify(qm, times(1)).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertNoReward(qm);
    }

    @Test
    void raiseTheRepCompletesFromEndWhenThePlayerHasOneThousandRep() throws Exception {
        QuestActionManager qm = familyQm(1234, 1234);
        runEnd(Q2233, qm);

        verify(qm, times(1)).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertNoReward(qm);
    }

    @Test
    void raiseTheRepStaysOpenBelowOneThousandRep() throws Exception {
        QuestActionManager qm = familyQm(999, 999);
        runEnd(Q2233, qm);

        verify(qm, never()).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertTrue(lastDialog(qm).contains("1,000"), lastDialog(qm));
        assertNoReward(qm);
    }

    @Test
    void raiseTheRepStaysOpenForACharacterWithoutAFamily() throws Exception {
        QuestActionManager qm = mock(QuestActionManager.class);
        Character player = mock(Character.class);
        when(qm.getPlayer()).thenReturn(player);
        when(player.getFamilyEntry()).thenReturn(null);

        runStart(Q2233, qm);

        verify(qm, never()).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertTrue(lastDialog(qm).contains("Family"), lastDialog(qm));
        assertNoReward(qm);
    }

    // ---- 2234: total reputation >= 2,000 and current reputation < 500 ----

    @Test
    void enjoyTheEntitlementCompletesFromStartAtTwoThousandTotalUnderFiveHundredCurrent() throws Exception {
        QuestActionManager qm = familyQm(300, 2000);
        runStart(Q2234, qm);

        verify(qm, times(1)).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertNoReward(qm);
    }

    @Test
    void enjoyTheEntitlementCompletesFromEndAtTwoThousandTotalUnderFiveHundredCurrent() throws Exception {
        QuestActionManager qm = familyQm(0, 5000);
        runEnd(Q2234, qm);

        verify(qm, times(1)).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertNoReward(qm);
    }

    @Test
    void enjoyTheEntitlementStaysOpenBelowTwoThousandTotal() throws Exception {
        QuestActionManager qm = familyQm(100, 1999);
        runEnd(Q2234, qm);

        verify(qm, never()).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertTrue(lastDialog(qm).contains("2,000"), lastDialog(qm));
        assertNoReward(qm);
    }

    /** 500 is not "under 500" - the player still has to spend rep on entitlements. */
    @Test
    void enjoyTheEntitlementStaysOpenAtFiveHundredCurrentRep() throws Exception {
        QuestActionManager qm = familyQm(500, 2500);
        runEnd(Q2234, qm);

        verify(qm, never()).forceCompleteQuest();
        verify(qm, times(1)).dispose();
        assertTrue(lastDialog(qm).contains("500"), lastDialog(qm));
        assertNoReward(qm);
    }
}
