package scripting;

import client.Character;
import client.Client;
import client.Job;
import client.Skill;
import client.SkillFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import server.quest.HiddenQuests;

import javax.script.Invocable;
import javax.script.ScriptEngine;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Ticket 112 (owner 2026-10-30): Moren's Maker chain (6029-6036) is open again and teaches each job family its own
 * Maker skill. The 6029 act (Quest.wz) teaches Maker 1 through SkillAction; 6033.js teaches Maker 2 and 6036.js
 * Maker 3 through teachSkill, which now teaches once the skill is in Skill.wz.
 */
class MakerQuestChainTest {

    private static final class Loader extends AbstractScriptManager {
        ScriptEngine load(String path) {
            return getInvocableScriptEngine(path);
        }
    }

    /** The parts of QuestActionManager 6033.js and 6036.js use. */
    public static final class FakeQm {
        final int makerSkillId;
        int progress6033;
        boolean holdsCrystal;
        boolean holdsAnvil;
        boolean completed;
        boolean disposed;
        int exp;
        int taughtSkill = -1;
        int taughtLevel = -1;
        int taughtMaster = -1;
        final Map<Integer, Integer> gained = new HashMap<>();

        FakeQm(int makerSkillId) {
            this.makerSkillId = makerSkillId;
        }

        public int getMakerSkillId() {
            return makerSkillId;
        }

        public int getQuestProgressInt(int quest) {
            return quest == 6033 ? progress6033 : 0;
        }

        public boolean haveItem(int itemId, int quantity) {
            return itemId == 4260003 ? holdsCrystal : itemId == 4031980 && holdsAnvil;
        }

        public void sendNext(String text) {
        }

        public void sendNextPrev(String text) {
        }

        public boolean forceCompleteQuest() {
            completed = true;
            return true;
        }

        public void teachSkill(int skillId, int level, int masterLevel, long expiration) {
            taughtSkill = skillId;
            taughtLevel = level;
            taughtMaster = masterLevel;
        }

        public void gainExp(int gain) {
            exp += gain;
        }

        public void gainItem(int itemId, int quantity) {
            gained.merge(itemId, quantity, Integer::sum);
        }

        public void dispose() {
            disposed = true;
        }
    }

    private static Invocable load(int quest, FakeQm qm) {
        ScriptEngine engine = new Loader().load("quest/" + quest + ".js");
        assertNotNull(engine, "quest/" + quest + ".js exists and evaluates");
        engine.put("qm", qm);
        return (Invocable) engine;
    }

    private static void clickThrough(Invocable script) throws Exception {
        // the next click only while the dialog is open (a disposed script gets no more clicks from the client)
        FakeQm qm = (FakeQm) ((ScriptEngine) script).get("qm");
        for (int i = 0; i < 3 && !qm.disposed; i++) {
            script.invokeFunction("end", 1, 0, 0);
        }
    }

    static Stream<Arguments> families() {
        return Stream.of(Arguments.of("Explorer", 1007), Arguments.of("Cygnus", 10001007),
                Arguments.of("Aran", 20001007), Arguments.of("Evan", 20011007));
    }

    @ParameterizedTest(name = "{0}: 6033 teaches Maker 2 of skill {1}")
    @MethodSource("families")
    void maker2TeachesTheFamilysOwnSkill(String family, int skill) throws Exception {
        FakeQm qm = new FakeQm(skill);
        qm.progress6033 = 1;
        qm.holdsCrystal = true;
        clickThrough(load(6033, qm));
        assertTrue(qm.completed);
        assertEquals(skill, qm.taughtSkill);
        assertEquals(2, qm.taughtLevel);
        assertEquals(3, qm.taughtMaster);
        assertEquals(230000, qm.exp);
    }

    @Test
    void maker2RefusesACrystalMadeBeforeTheTest() throws Exception {
        FakeQm qm = new FakeQm(1007);
        qm.holdsCrystal = true;   // bought, or crafted before starting: no progress
        clickThrough(load(6033, qm));
        assertFalse(qm.completed);
        assertEquals(-1, qm.taughtSkill);
    }

    @ParameterizedTest(name = "{0}: 6036 teaches Maker 3 of skill {1}")
    @MethodSource("families")
    void maker3TeachesTheFamilysOwnSkill(String family, int skill) throws Exception {
        FakeQm qm = new FakeQm(skill);
        qm.holdsAnvil = true;
        clickThrough(load(6036, qm));
        assertTrue(qm.completed);
        assertEquals(skill, qm.taughtSkill);
        assertEquals(3, qm.taughtLevel);
        assertEquals(-1, qm.gained.get(4031980), "the Gold Anvil is handed in");
    }

    @Test
    void theChainIsNotHiddenAnyMore() {
        for (int quest = 6029; quest <= 6036; quest++) {
            for (int job : new int[]{100, 1100, 2100, 2200}) {
                assertFalse(HiddenQuests.isHidden(quest, job), quest + " for job " + job);
            }
        }
    }

    // --- teachSkill and the 6029 act --------------------------------------------------------------------------

    private Map<Integer, Skill> skillsWere;

    @BeforeEach
    void loadMakerSkills() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (int id : new int[]{1007, 10001007, 20001007, 20011007}) {
            skills.put(id, new Skill(id));
        }
        skillsWere = swap(skills);
    }

    @AfterEach
    void restoreSkills() throws Exception {
        swap(skillsWere);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swap(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> was = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return was;
    }

    private static Character character(Job job) {
        Client c = mock(Client.class);
        Character chr = Character.getDefault(c);
        chr.setName("Learner");
        chr.setJob(job);
        when(c.getPlayer()).thenReturn(chr);
        return chr;
    }

    @Test
    void teachSkillTeachesMakerOnceItIsInSkillWz() {
        Character chr = character(Job.DAWNWARRIOR2);
        AbstractPlayerInteraction api = new AbstractPlayerInteraction(chr.getClient());
        assertEquals(10001007, api.getMakerSkillId());
        api.teachSkill(api.getMakerSkillId(), (byte) 2, (byte) 3, -1);
        assertEquals(2, chr.getSkillLevel(10001007));
        assertEquals(3, chr.getMasterLevel(SkillFactory.getSkill(10001007)));
        api.teachSkill(api.getMakerSkillId(), (byte) 1, (byte) 3, -1);
        assertEquals(2, chr.getSkillLevel(10001007), "never taught down");
    }

    @Test
    void teachSkillStillSkipsASkillNotInSkillWz() {
        Character chr = character(Job.FIGHTER);
        new AbstractPlayerInteraction(chr.getClient()).teachSkill(1121006, (byte) 1, (byte) 10, -1);
        assertTrue(chr.getSkills().isEmpty());
    }

    @Test
    void the6029ActTeachesEachFamilyOnlyItsOwnMaker() {
        // Quest.wz Act 6029: four entries; Explorer/Cygnus/Aran list their jobs, Evan's lists none
        List<Integer> explorers = List.of(100, 110, 111, 112, 200, 522);
        List<Integer> cygnus = List.of(1100, 1512);
        List<Integer> aran = List.of(2100, 2112);
        List<Integer> none = List.of();
        for (int job : new int[]{110, 1110, 2110, 2210, 9910}) {
            int want = client.inventory.MakerService.makerSkillId(job);
            int learnt = 0;
            for (Object[] entry : new Object[][]{{1007, explorers}, {10001007, cygnus}, {20001007, aran},
                    {20011007, none}}) {
                @SuppressWarnings("unchecked")
                List<Integer> jobs = (List<Integer>) entry[1];
                if (server.quest.actions.SkillAction.shouldLearn((Integer) entry[0], jobs, job)) {
                    assertEquals(want, entry[0], "job " + job + " learns only its own Maker");
                    learnt++;
                }
            }
            assertEquals(1, learnt, "job " + job + " learns exactly one Maker");
        }
    }
}
