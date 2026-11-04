package server.quest;

import client.BotClient;
import client.Character;
import client.Job;
import client.QuestStatus;
import client.inventory.InventoryType;
import client.inventory.Item;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

// 1.5.2 owner decision: quests that can never be finished here (skill-book quests for skills MapleRoot removed,
// MapleRoot's unfinished Von Leon / Henesys Ruins steps) must not be startable by any route, while a player who
// already has one open can still complete or forfeit it. Real Quest objects from a small WZ fixture.
class HiddenQuestsTest {
    private static final int NIGHT_WARRIOR = 6141;      // hidden for everyone (Ninja Ambush is gone)
    private static final int DARK_LORD = 6140;          // its lead-in, works and stays
    private static final int SAYRAM = 6150;             // hidden for Paladins only (Heaven's Hammer is gone)
    private static final int NPC = 1052001;

    // 31110 is on the hidden list; the fixture marks it autoStart and gives it the start item act
    // that 3181's block carries, so the ordering inside Quest.start is observable
    private static final int HIDDEN_AUTO_START = 31110;
    private static final int OPEN_AUTO_START = 3140;    // not hidden, same autoStart and same granting act
    private static final int GRANTED_ITEM = 4032859;

    // medal quests whose medal item exists nowhere obtainable, and whose act blocks grant nothing
    private static final int MEDAL_29500 = 29500, MEDAL_1142003 = 1142003;
    private static final int MEDAL_29915 = 29915, MEDAL_1142064 = 1142064;
    private static final int MEDAL_29916 = 29916, MEDAL_1142014 = 1142014;
    private static final int MEDAL_29917 = 29917, MEDAL_1142015 = 1142015;
    private static final int MEDAL_29918 = 29918, MEDAL_1142016 = 1142016;
    private static final int MEDAL_29919 = 29919, MEDAL_1142017 = 1142017;
    private static final int MEDAL_29920 = 29920, MEDAL_1142018 = 1142018;
    private static final int MEDAL_29921 = 29921, MEDAL_1142019 = 1142019;
    private static final int MEDAL_29933 = 29933, MEDAL_1142030 = 1142030;

    private static final int OPEN_MEDAL_QUEST = 3141;   // not hidden, same "own medal 1142064" requirement

    private static Path wzPath;

    @BeforeAll
    static void writeWzFixture() throws IOException, ClassNotFoundException {
        wzPath = Files.createTempDirectory("hidden-quests-wz");
        Path questWz = Files.createDirectories(wzPath.resolve("Quest.wz"));
        StringBuilder check = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<imgdir name=\"Check.img\">\n");
        for (int id : new int[]{NIGHT_WARRIOR, DARK_LORD, SAYRAM, HIDDEN_AUTO_START, OPEN_AUTO_START}) {
            check.append("<imgdir name=\"").append(id).append("\"><imgdir name=\"0\"><int name=\"npc\" value=\"")
                    .append(NPC).append("\"/></imgdir><imgdir name=\"1\"><int name=\"npc\" value=\"").append(NPC)
                    .append("\"/></imgdir></imgdir>\n");
        }
        // medal quests ask only to talk to the NPC and to carry the medal
        for (int[] medalQuest : new int[][]{{MEDAL_29915, MEDAL_1142064}, {MEDAL_29933, MEDAL_1142030},
                {OPEN_MEDAL_QUEST, MEDAL_1142064}}) {
            check.append("<imgdir name=\"").append(medalQuest[0])
                    .append("\"><imgdir name=\"0\"><int name=\"npc\" value=\"").append(NPC).append("\"/>")
                    .append("<imgdir name=\"item\"><imgdir name=\"0\"><int name=\"id\" value=\"").append(medalQuest[1])
                    .append("\"/><int name=\"count\" value=\"1\"/></imgdir></imgdir></imgdir>")
                    .append("<imgdir name=\"1\"><int name=\"npc\" value=\"").append(NPC)
                    .append("\"/></imgdir></imgdir>\n");
        }
        check.append("</imgdir>\n");
        Files.writeString(questWz.resolve("Check.img.xml"), check.toString());
        Files.writeString(questWz.resolve("QuestInfo.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="QuestInfo.img">
                <imgdir name="%d"><int name="autoStart" value="1"/></imgdir>
                <imgdir name="%d"><int name="autoStart" value="1"/></imgdir>
                </imgdir>
                """.formatted(HIDDEN_AUTO_START, OPEN_AUTO_START));
        Files.writeString(questWz.resolve("Act.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="Act.img">
                <imgdir name="%d"><imgdir name="0"><imgdir name="item"><imgdir name="0"><int name="id" value="%d"/>\
                <int name="count" value="1"/></imgdir></imgdir></imgdir><imgdir name="1"/></imgdir>
                <imgdir name="%d"><imgdir name="0"><imgdir name="item"><imgdir name="0"><int name="id" value="%d"/>\
                <int name="count" value="1"/></imgdir></imgdir></imgdir><imgdir name="1"/></imgdir>
                </imgdir>
                """.formatted(HIDDEN_AUTO_START, GRANTED_ITEM, OPEN_AUTO_START, GRANTED_ITEM));
        // Quest resolves the WZ directory once, in a static initializer, so it must be set before Quest loads.
        System.setProperty("wz-path", wzPath.toString());
        loadItemDataClass();
    }

    // ItemInformationProvider's static initializer reads the DB and the WZ (a quest item act reads stack sizes), so
    // load that class once against mocks; the item-granting tests then swap getInstance() for a stub.
    private static void loadItemDataClass() throws ClassNotFoundException {
        try (MockedStatic<DatabaseConnection> db = mockStatic(DatabaseConnection.class);
             MockedStatic<DataProviderFactory> providers = mockStatic(DataProviderFactory.class)) {
            db.when(DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> DataProviderFactory.getDataProvider(any(WZFiles.class)))
                    .thenReturn(mock(DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    /** Stack sizes and pickup flags a quest item act asks for; the fixture WZ has no Item.wz. */
    private static MockedStatic<ItemInformationProvider> stubItemData() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 32000);
        when(ii.isPickupRestricted(anyInt())).thenReturn(false);
        when(ii.isCash(anyInt())).thenReturn(false);
        when(ii.getName(anyInt())).thenReturn("item");
        MockedStatic<ItemInformationProvider> stubbed = mockStatic(ItemInformationProvider.class);
        stubbed.when(ItemInformationProvider::getInstance).thenReturn(ii);
        return stubbed;
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
        for (int id : new int[]{6141, 6150, 6153, 6200, 6202, 6330, 6340, 6350, 6410,
                31110, 31160}) {
            assertTrue(HiddenQuests.ids().contains(id), id + " must be hidden");
        }
        // these work and must stay: lead-ins, Holy Charge (6280 still teaches 1221003), the Arcane Path to 5th job,
        // the Rebirth Path, Henesys Ruins steps whose mobs and NPCs exist
        // Moren's Maker chain 6029-6036 came back in 1.6.2 (ticket 112)
        for (int id : new int[]{6029, 6030, 6031, 6032, 6033, 6034, 6035, 6036, 6140, 6280, 6281, 3163, 3172, 31100, 31104, 31108, 31149, 31153, 31161, 31167,
                3164, 3165, 3166, 3167, 3168, 3169, 3170, 3171, 3173, 3174, 3175, 3176, 3177, 3181, 3182,
                31168, 31170}) {
            assertFalse(HiddenQuests.ids().contains(id), id + " must stay startable");
        }
    }

    @Test
    void theUnwinnableMedalQuestsAreHidden() {
        int[][] medalQuests = {
                {MEDAL_29500, MEDAL_1142003}, {MEDAL_29915, MEDAL_1142064}, {MEDAL_29916, MEDAL_1142014},
                {MEDAL_29917, MEDAL_1142015}, {MEDAL_29918, MEDAL_1142016}, {MEDAL_29919, MEDAL_1142017},
                {MEDAL_29920, MEDAL_1142018}, {MEDAL_29921, MEDAL_1142019}, {MEDAL_29933, MEDAL_1142030}};
        Character chr = character(Job.NIGHTLORD);
        for (int[] medalQuest : medalQuests) {
            int id = medalQuest[0];
            assertTrue(HiddenQuests.ids().contains(id), id + " asks for medal " + medalQuest[1]
                    + ", which nothing in the server hands out, so it must be hidden");
            assertTrue(Quest.getInstance(id).isHiddenFor(chr), id + " must be hidden for every job");
        }
    }

    @Test
    void owningTheMedalDoesNotUnhideTheMedalQuest() {
        Character chr = character(Job.NIGHTLORD);
        // hand the character the medal itself: without the hide, the medal is the only thing canStart is missing
        assertTrue(chr.getInventory(InventoryType.EQUIP).addItem(new Item(MEDAL_1142064, (short) 0, (short) 1)) >= 0,
                "the fixture must be able to put the medal in the character's equip inventory");
        assertEquals(1, chr.getInventory(InventoryType.EQUIP).countById(MEDAL_1142064));

        Quest quest = Quest.getInstance(MEDAL_29915);
        assertTrue(quest.isHiddenFor(chr));
        assertFalse(quest.canStart(chr, NPC), "the hide, not the missing medal, must refuse this start");

        // control: a quest with the same medal requirement that is not hidden starts for the same character
        Quest open = Quest.getInstance(OPEN_MEDAL_QUEST);
        assertTrue(open.canStart(chr, NPC), "owning the medal must be enough for a quest that is not hidden");
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

    // autoStart short-circuits canStart, so before the fix a hidden autoStart quest ran its start acts (here: an item
    // grant) and only forceStart, at the end, refused the status write
    @Test
    void aHiddenAutoStartQuestDoesNotRunItsStartActs() {
        Character chr = character(Job.NIGHTLORD);
        Quest quest = Quest.getInstance(HIDDEN_AUTO_START);

        assertTrue(quest.isAutoStart());
        assertTrue(quest.isHiddenFor(chr));

        try (MockedStatic<ItemInformationProvider> ignored = stubItemData()) {
            quest.start(chr, NPC);
        }

        assertEquals(0, chr.getInventory(InventoryType.ETC).countById(GRANTED_ITEM),
                "a hidden quest's start act must not hand out items");
        assertEquals(QuestStatus.Status.NOT_STARTED, chr.getQuest(quest).getStatus());
    }

    // the control: the very same autoStart quest and start act, only not hidden, does grant the item
    @Test
    void theSameStartActGrantsTheItemWhenTheQuestIsNotHidden() {
        Character chr = character(Job.NIGHTLORD);
        Quest quest = Quest.getInstance(OPEN_AUTO_START);

        assertTrue(quest.isAutoStart());
        assertFalse(quest.isHiddenFor(chr));

        try (MockedStatic<ItemInformationProvider> ignored = stubItemData()) {
            quest.start(chr, NPC);
        }

        assertEquals(1, chr.getInventory(InventoryType.ETC).countById(GRANTED_ITEM));
        assertEquals(QuestStatus.Status.STARTED, chr.getQuest(quest).getStatus());
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

    // A quest item act holds its items through the client's interaction, which reads client.getPlayer(); a bot's
    // client has no player (AGENTS.md), so the fixture client answers with the character it was built for.
    private static Character character(Job job) {
        BotClient client = spy(new BotClient(0, 0));
        Character chr = Character.getDefault(client);
        doReturn(chr).when(client).getPlayer();
        chr.setMap(mock(MapleMap.class));   // forceComplete broadcasts the quest effect on the map
        chr.setJob(job);
        return chr;
    }
}
