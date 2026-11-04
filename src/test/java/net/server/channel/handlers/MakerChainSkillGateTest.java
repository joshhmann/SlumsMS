package net.server.channel.handlers;

import client.BotClient;
import client.Character;
import client.Job;
import client.QuestStatus;
import client.Skill;
import client.SkillFactory;
import client.inventory.MakerService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;
import server.quest.HiddenQuests;
import server.quest.Quest;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * Ticket 112/164: Moren's Maker chain (6029-6036) only teaches while the server's Skill.wz has the Maker family,
 * and that data is a release step, not code (tools/data-align/add_maker_skill.py --apply, then a restart). On a
 * server where nobody ran it the chain is a dead end that costs 10,000 mesos a lesson: SkillAction and teachSkill
 * skip an unknown skill without telling the player. So the chain must be hidden per job family while the family's
 * Maker skill is missing, and open by itself once the data is there.
 */
class MakerChainSkillGateTest {
    private static final int[] CHAIN = {6029, 6030, 6031, 6032, 6033, 6034, 6035, 6036};
    private static final int OPEN_QUEST = 6140;     // same fixture shape, outside the chain: must stay startable
    private static final int NPC = 2110004;         // Moren, the chain's NPC
    private static final int LEVEL = 45;            // the chain's level floor
    private static final String REFUSAL = "This quest can't be finished on SlumsMS yet, so it can't be started.";

    private static Path wzPath;
    private static Field skillsField;
    private static Map<Integer, Skill> originalSkills;

    @BeforeAll
    static void writeWzFixture() throws Exception {
        wzPath = Files.createTempDirectory("maker-gate-wz");
        Path questWz = Files.createDirectories(wzPath.resolve("Quest.wz"));
        StringBuilder check = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<imgdir name=\"Check.img\">\n");
        for (int id : new int[]{6029, 6030, 6031, 6032, 6033, 6034, 6035, 6036, OPEN_QUEST}) {
            check.append("<imgdir name=\"").append(id).append("\"><imgdir name=\"0\"><int name=\"npc\" value=\"")
                    .append(NPC).append("\"/><int name=\"lvmin\" value=\"").append(LEVEL)
                    .append("\"/></imgdir><imgdir name=\"1\"><int name=\"npc\" value=\"").append(NPC)
                    .append("\"/></imgdir></imgdir>\n");
        }
        check.append("</imgdir>\n");
        Files.writeString(questWz.resolve("Check.img.xml"), check.toString());
        Files.writeString(questWz.resolve("Act.img.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<imgdir name=\"Act.img\">\n</imgdir>\n");
        Files.writeString(questWz.resolve("QuestInfo.img.xml"),
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<imgdir name=\"QuestInfo.img\">\n</imgdir>\n");
        // Quest resolves the WZ directory once, in a static initializer, so it must be set before Quest loads.
        System.setProperty("wz-path", wzPath.toString());

        skillsField = SkillFactory.class.getDeclaredField("skills");
        skillsField.setAccessible(true);
        originalSkills = rawSkills();
    }

    // every test starts from the tool-not-run server: Skill.wz has no Maker node
    @BeforeEach
    void noMakerSkillLoaded() {
        useSkills(new HashMap<>());
    }

    @AfterAll
    static void deleteWzFixture() throws IOException {
        try {
            if (originalSkills != null) {
                useSkills(originalSkills);
            }
        } finally {
            System.clearProperty("wz-path");
        }
        if (wzPath == null || !Files.exists(wzPath)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(wzPath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    // the real test: a player walking Moren's chain on a server whose Skill.wz has no Maker node
    @Test
    void theChainIsRefusedWithAWordToThePlayerWhenSkillWzHasNoMaker() {
        Character chr = character(Job.HERO, 50_000);
        int mesoBefore = chr.getMeso();

        for (int id : CHAIN) {
            Quest quest = Quest.getInstance(id);
            assertTrue(HiddenQuests.isHidden(id, Job.HERO.getId()), id + " must be hidden while Skill.wz has no Maker");
            assertTrue(quest.isHiddenFor(chr), id + " must be hidden for a Hero");
            assertTrue(QuestActionHandler.refuseHidden(chr, quest),
                    id + " must be refused in the quest window, not left to fail silently");
            assertFalse(quest.canStart(chr, NPC), id + " must not be startable");
            quest.start(chr, NPC);
            assertEquals(QuestStatus.Status.NOT_STARTED, chr.getQuest(quest).getStatus(),
                    id + " must not start, so the player cannot be charged for a lesson that teaches nothing");
            assertFalse(quest.forceStart(chr, NPC), id + " must not be force-startable from a script either");
            assertEquals(QuestStatus.Status.NOT_STARTED, chr.getQuest(quest).getStatus());
        }

        verify(chr, atLeast(CHAIN.length)).dropMessage(5, REFUSAL);
        assertEquals(mesoBefore, chr.getMeso(), "nothing the player owns may be spent on the chain");
    }

    // the control: only the chain is gated, not the fixture or the quest system
    @Test
    void aQuestOutsideTheChainStillStartsWithoutMaker() {
        Character chr = character(Job.HERO, 0);
        Quest quest = Quest.getInstance(OPEN_QUEST);

        assertFalse(quest.isHiddenFor(chr));
        assertTrue(quest.canStart(chr, NPC));
        quest.start(chr, NPC);
        assertEquals(QuestStatus.Status.STARTED, chr.getQuest(quest).getStatus());
    }

    // the release step is the WZ data, not a code change: with Maker in Skill.wz the chain opens on its own
    @Test
    void theChainOpensOnceSkillWzHasTheCharactersMaker() {
        useSkills(makerSkills(1007, 10001007, 20001007, 20011007));

        Character hero = character(Job.HERO, 0);
        for (int id : CHAIN) {
            Quest quest = Quest.getInstance(id);
            assertFalse(HiddenQuests.isHidden(id, Job.HERO.getId()), id + " must be startable once Skill.wz has Maker");
            assertFalse(quest.isHiddenFor(hero), id + " must not be hidden for a Hero");
            assertTrue(quest.canStart(hero, NPC), id + " must be startable for a Hero");
            quest.start(hero, NPC);
            assertEquals(QuestStatus.Status.STARTED, hero.getQuest(quest).getStatus(), id + " must start");
        }
    }

    // each family needs its own beginner job's Maker: an explorer's Maker must not open the chain for a Cygnus
    @Test
    void theGateFollowsTheCharactersJobFamily() {
        useSkills(makerSkills(1007));

        Character hero = character(Job.HERO, 0);
        Character cygnus = character(Job.DAWNWARRIOR1, 0);
        assertEquals(1007, MakerService.makerSkillId(Job.HERO.getId()));
        assertEquals(10001007, MakerService.makerSkillId(Job.DAWNWARRIOR1.getId()));

        assertFalse(Quest.getInstance(6029).isHiddenFor(hero), "a Hero has Maker 1007, so the chain is open");
        assertTrue(Quest.getInstance(6029).isHiddenFor(cygnus), "a Cygnus has no Maker 10001007 yet");
        assertTrue(QuestActionHandler.refuseHidden(cygnus, Quest.getInstance(6029)));
        verify(cygnus, atLeast(1)).dropMessage(5, REFUSAL);
    }

    private static Map<Integer, Skill> makerSkills(int... ids) {
        Map<Integer, Skill> map = new HashMap<>();
        for (int id : ids) {
            map.put(id, new Skill(id));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> rawSkills() {
        try {
            return (Map<Integer, Skill>) skillsField.get(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SkillFactory.skills must be reachable for this test", e);
        }
    }

    private static void useSkills(Map<Integer, Skill> skills) {
        try {
            skillsField.set(null, skills);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("SkillFactory.skills must be writable for this test", e);
        }
    }

    private static Character character(Job job, int meso) {
        BotClient client = spy(new BotClient(0, 0));
        Character chr = spy(Character.getDefault(client));
        doReturn(chr).when(client).getPlayer();
        chr.setMap(mock(MapleMap.class));
        chr.setJob(job);
        chr.setLevel(LEVEL);
        chr.gainMeso(meso);
        return chr;
    }
}
