package server.quest;

import client.BotClient;
import client.Character;
import client.Job;
import client.QuestStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import server.maps.MapleMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// 1.5.2 owner decision: quests that can never be finished here (skill-book quests for skills MapleRoot removed,
// MapleRoot's unfinished Von Leon / Henesys Ruins steps) must not be startable by any route, while a player who
// already has one open can still complete or forfeit it. Real Quest objects from a small WZ fixture.
class HiddenQuestsTest {
    private static final int NIGHT_WARRIOR = 6141;      // hidden for everyone (Ninja Ambush is gone)
    private static final int DARK_LORD = 6140;          // its lead-in, works and stays
    private static final int SAYRAM = 6150;             // hidden for Paladins only (Heaven's Hammer is gone)
    private static final int NPC = 1052001;

    private static Path wzPath;

    @BeforeAll
    static void writeWzFixture() throws IOException {
        wzPath = Files.createTempDirectory("hidden-quests-wz");
        Path questWz = Files.createDirectories(wzPath.resolve("Quest.wz"));
        StringBuilder check = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<imgdir name=\"Check.img\">\n");
        for (int id : new int[]{NIGHT_WARRIOR, DARK_LORD, SAYRAM}) {
            check.append("<imgdir name=\"").append(id).append("\"><imgdir name=\"0\"><int name=\"npc\" value=\"")
                    .append(NPC).append("\"/></imgdir><imgdir name=\"1\"><int name=\"npc\" value=\"").append(NPC)
                    .append("\"/></imgdir></imgdir>\n");
        }
        check.append("</imgdir>\n");
        Files.writeString(questWz.resolve("Check.img.xml"), check.toString());
        Files.writeString(questWz.resolve("Act.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="Act.img">
                </imgdir>
                """);
        // Quest resolves the WZ directory once, in a static initializer, so it must be set before Quest loads.
        System.setProperty("wz-path", wzPath.toString());
    }

    @AfterAll
    static void deleteWzFixture() throws IOException {
        System.clearProperty("wz-path");
        if (wzPath == null || !Files.exists(wzPath)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(wzPath)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    @Test
    void theOwnersListIsHidden() {
        for (int id : new int[]{6141, 6150, 6153, 6200, 6202, 6330, 6340, 6350, 6410, 6029, 6034,
                3164, 3182, 31110, 31160}) {
            assertTrue(HiddenQuests.ids().contains(id), id + " must be hidden");
        }
        // these work and must stay: lead-ins, Holy Charge (6280 still teaches 1221003), the Arcane Path to 5th job,
        // the Rebirth Path, Henesys Ruins steps whose mobs and NPCs exist
        for (int id : new int[]{6140, 6280, 6281, 3163, 3172, 31100, 31104, 31108, 31149, 31153, 31161, 31167,
                31168, 31170}) {
            assertFalse(HiddenQuests.ids().contains(id), id + " must stay startable");
        }
    }

    @Test
    void aHiddenQuestCannotBeStartedFromTheQuestWindowOrAScript() {
        Character chr = character(Job.NIGHTLORD);
        Quest quest = Quest.getInstance(NIGHT_WARRIOR);

        assertFalse(quest.canStart(chr, NPC));
        quest.start(chr, NPC);
        assertEquals(QuestStatus.Status.NOT_STARTED, chr.getQuest(quest).getStatus());
        assertFalse(quest.forceStart(chr, NPC), "qm.forceStartQuest / cm.startQuest must be refused too");
        assertEquals(QuestStatus.Status.NOT_STARTED, chr.getQuest(quest).getStatus());
    }

    @Test
    void aQuestThatWorksStillStarts() {
        Character chr = character(Job.NIGHTLORD);
        Quest quest = Quest.getInstance(DARK_LORD);

        assertTrue(quest.canStart(chr, NPC));
        quest.start(chr, NPC);
        assertEquals(QuestStatus.Status.STARTED, chr.getQuest(quest).getStatus());
    }

    @Test
    void sayramIsHiddenForPaladinsOnly() {
        Quest quest = Quest.getInstance(SAYRAM);
        assertFalse(quest.canStart(character(Job.PALADIN), NPC));
        assertTrue(quest.canStart(character(Job.HERO), NPC));
        assertTrue(quest.canStart(character(Job.DARKKNIGHT), NPC));
    }

    @Test
    void aPlayerAlreadyOnAHiddenQuestCanFinishOrForfeitIt() {
        Quest quest = Quest.getInstance(NIGHT_WARRIOR);

        Character finisher = character(Job.NIGHTLORD);
        finisher.updateQuestStatus(new QuestStatus(quest, QuestStatus.Status.STARTED));
        assertTrue(quest.canComplete(finisher, NPC));
        quest.complete(finisher, NPC);
        assertEquals(QuestStatus.Status.COMPLETED, finisher.getQuest(quest).getStatus());

        Character quitter = character(Job.NIGHTLORD);
        quitter.updateQuestStatus(new QuestStatus(quest, QuestStatus.Status.STARTED));
        assertTrue(quest.forfeit(quitter));
        assertEquals(QuestStatus.Status.NOT_STARTED, quitter.getQuest(quest).getStatus());
    }

    private static Character character(Job job) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setMap(mock(MapleMap.class));   // forceComplete broadcasts the quest effect on the map
        chr.setJob(job);
        return chr;
    }
}
