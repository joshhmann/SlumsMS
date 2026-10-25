package server.quest;

import client.BotClient;
import client.Character;
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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

// Quest 6007 (Chief Tatamo, the mount quest) charges 20,000,000 mesos through Act.img.xml `money = -20000000`,
// but the completion-side price lives in Check.img.xml as `endmeso`. That WZ key was never mapped to a
// requirement, so nothing checked it before the action ran, and Character.gainMeso floors a negative gain at
// zero: a player holding 3M paid 3M and still got the Dragon's Perfume Bottle. These tests pin the gate on a real
// Quest loaded from a WZ fixture, so they fail if `endmeso` stops being parsed or stops being checked.
class EndMesoRequirementTest {
    private static final int QUEST_ID = 6007;
    private static final int TATAMO = 2081000;
    private static final int PRICE = 20_000_000;

    private static Path wzPath;

    @BeforeAll
    static void writeWzFixture() throws IOException {
        wzPath = Files.createTempDirectory("endmeso-wz");
        Path questWz = Files.createDirectories(wzPath.resolve("Quest.wz"));
        Files.writeString(questWz.resolve("Check.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="Check.img">
                  <imgdir name="6007">
                    <imgdir name="0">
                      <int name="npc" value="2081000"/>
                    </imgdir>
                    <imgdir name="1">
                      <int name="npc" value="2081000"/>
                      <int name="endmeso" value="20000000"/>
                    </imgdir>
                  </imgdir>
                </imgdir>
                """);
        Files.writeString(questWz.resolve("Act.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="Act.img">
                  <imgdir name="6007">
                    <imgdir name="1">
                      <int name="money" value="-20000000"/>
                    </imgdir>
                  </imgdir>
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
    void endmesoIsItsOwnRequirementAndMoneyStaysTheStartPrice() {
        assertSame(QuestRequirementType.END_MESO, QuestRequirementType.getByWZName("endmeso"));
        assertSame(QuestRequirementType.MESO, QuestRequirementType.getByWZName("money"));
    }

    @Test
    void aPlayerWithoutTheCompletionPriceCannotCompleteAndKeepsTheirMesos() {
        Character chr = characterWith(3_000_000);
        Quest quest = Quest.getInstance(QUEST_ID);

        assertFalse(quest.canComplete(chr, TATAMO), "3M must not satisfy the 20M completion price");

        quest.complete(chr, TATAMO);

        assertEquals(QuestStatus.Status.STARTED, chr.getQuest(quest).getStatus(), "the quest must stay open");
        assertEquals(3_000_000, chr.getMeso(), "a refused completion must not charge the player");
    }

    @Test
    void aPlayerWithTheCompletionPriceCompletesAndPaysIt() {
        Character chr = characterWith(PRICE);
        Quest quest = Quest.getInstance(QUEST_ID);

        assertTrue(quest.canComplete(chr, TATAMO), "20M must satisfy the 20M completion price");

        quest.complete(chr, TATAMO);

        assertEquals(QuestStatus.Status.COMPLETED, chr.getQuest(quest).getStatus());
        assertEquals(0, chr.getMeso(), "the 20M completion price must be paid");
    }

    private static Character characterWith(int mesos) {
        Character chr = Character.getDefault(new BotClient(0, 0));
        chr.setMap(mock(MapleMap.class));   // forceComplete broadcasts the quest effect on the map
        chr.gainMeso(mesos, false, false, false);
        chr.updateQuestStatus(new QuestStatus(Quest.getInstance(QUEST_ID), QuestStatus.Status.STARTED));
        return chr;
    }
}
