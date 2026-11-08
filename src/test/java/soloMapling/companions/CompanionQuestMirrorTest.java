package soloMapling.companions;

import client.BotClient;
import client.Character;
import client.Client;
import client.Job;
import client.QuestStatus;
import client.command.commands.gm0.BotMeCommand;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import provider.DataProvider;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.ItemInformationProvider;
import server.maps.MapleMap;
import server.quest.Quest;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import tools.DatabaseConnection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * 1.6.7 alt-qol-batch E, quest mirror: a companion in its leader's party takes the leader's quests along.
 *
 * <p>Real {@link Quest} objects from a small WZ fixture (the {@code HiddenQuestsTest} style), so the start, the kill
 * count, the completion and its item requirement run through the server's own path. The world is not involved: the
 * party hands back its members' characters, which is what the server's own party code reads.
 */
class CompanionQuestMirrorTest {
    private static final int NPC = 9000000;

    private static final int Q_PLAIN = 10100;   // talk to the NPC to start, talk again to finish, nothing else
    private static final int Q_LEVEL = 10101;   // starting it needs level 50
    private static final int Q_ITEM = 10102;    // finishing it needs item 4000001; finishing gives 4000002
    private static final int Q_MOB = 10103;     // finishing it needs 5 kills of mob 1210102
    private static final int Q_CHOICE = 10104;  // finishing it gives one of the items the client picks

    private static final int NEEDED_ITEM = 4000001;
    private static final int REWARD_ITEM = 4000002;
    private static final int CHOICE_ITEM = 4000003;
    private static final int MOB = 1210102;

    private static Path wzPath;
    private static int nextId = 100;

    private MockedStatic<ItemInformationProvider> itemData;
    private MockedStatic<CharacterStorage> bots;

    @BeforeAll
    static void writeWzFixture() throws IOException, ClassNotFoundException {
        wzPath = Files.createTempDirectory("quest-mirror-wz");
        Path questWz = Files.createDirectories(wzPath.resolve("Quest.wz"));
        Files.writeString(questWz.resolve("Check.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="Check.img">
                <imgdir name="%d"><imgdir name="0"><int name="npc" value="%d"/></imgdir>\
                <imgdir name="1"><int name="npc" value="%d"/></imgdir></imgdir>
                <imgdir name="%d"><imgdir name="0"><int name="npc" value="%d"/><int name="lvmin" value="50"/></imgdir>\
                <imgdir name="1"><int name="npc" value="%d"/></imgdir></imgdir>
                <imgdir name="%d"><imgdir name="0"><int name="npc" value="%d"/></imgdir><imgdir name="1">\
                <int name="npc" value="%d"/><imgdir name="item"><imgdir name="0"><int name="id" value="%d"/>\
                <int name="count" value="1"/></imgdir></imgdir></imgdir></imgdir>
                <imgdir name="%d"><imgdir name="0"><int name="npc" value="%d"/></imgdir><imgdir name="1">\
                <int name="npc" value="%d"/><imgdir name="mob"><imgdir name="0"><int name="id" value="%d"/>\
                <int name="count" value="5"/></imgdir></imgdir></imgdir></imgdir>
                <imgdir name="%d"><imgdir name="0"><int name="npc" value="%d"/></imgdir>\
                <imgdir name="1"><int name="npc" value="%d"/></imgdir></imgdir>
                </imgdir>
                """.formatted(
                Q_PLAIN, NPC, NPC,
                Q_LEVEL, NPC, NPC,
                Q_ITEM, NPC, NPC, NEEDED_ITEM,
                Q_MOB, NPC, NPC, MOB,
                Q_CHOICE, NPC, NPC));
        Files.writeString(questWz.resolve("QuestInfo.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="QuestInfo.img"/>
                """);
        Files.writeString(questWz.resolve("Act.img.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <imgdir name="Act.img">
                <imgdir name="%d"><imgdir name="0"/><imgdir name="1"/></imgdir>
                <imgdir name="%d"><imgdir name="0"/><imgdir name="1"/></imgdir>
                <imgdir name="%d"><imgdir name="0"/><imgdir name="1"><imgdir name="item"><imgdir name="0">\
                <int name="id" value="%d"/><int name="count" value="1"/></imgdir></imgdir></imgdir></imgdir>
                <imgdir name="%d"><imgdir name="0"/><imgdir name="1"/></imgdir>
                <imgdir name="%d"><imgdir name="0"/><imgdir name="1"><imgdir name="item"><imgdir name="0">\
                <int name="id" value="%d"/><int name="count" value="1"/><int name="prop" value="-1"/></imgdir>\
                </imgdir></imgdir></imgdir>
                </imgdir>
                """.formatted(Q_PLAIN, Q_LEVEL, Q_ITEM, REWARD_ITEM, Q_MOB, Q_CHOICE, CHOICE_ITEM));
        // Quest resolves the WZ directory once, in a static initializer, so it must be set before Quest loads.
        System.setProperty("wz-path", wzPath.toString());
        loadItemDataClass();
    }

    // ItemInformationProvider's static initializer reads the DB and the WZ; load that class once against mocks.
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

    @BeforeEach
    void resetState() {
        CompanionQuestMirror.clearForTest();
        itemData = itemInformationProvider();
        bots = mockStatic(CharacterStorage.class);
    }

    @AfterEach
    void clearSwitches() {
        System.clearProperty("slums.companion.quests.regulars");
        System.clearProperty("slums.companion.quests.alts");
        bots.close();
        itemData.close();
    }

    private static MockedStatic<ItemInformationProvider> itemInformationProvider() {
        ItemInformationProvider ii = mock(ItemInformationProvider.class);
        when(ii.getSlotMax(any(Character.class), anyInt())).thenReturn((short) 32000);
        when(ii.isPickupRestricted(anyInt())).thenReturn(false);
        when(ii.isCash(anyInt())).thenReturn(false);
        when(ii.getName(anyInt())).thenReturn("item");
        MockedStatic<ItemInformationProvider> stubbed = mockStatic(ItemInformationProvider.class);
        stubbed.when(ItemInformationProvider::getInstance).thenReturn(ii);
        return stubbed;
    }

    // ---------------------------------------------------------------- the player's alt, opted in by the command

    @Test
    void altOptedInStartsWhatThePlayerStarts() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);

        new BotMeCommand().execute(player.getClient(), new String[]{"quests", "on", "Alt1"});

        Quest quest = Quest.getInstance(Q_PLAIN);
        quest.start(player, NPC);

        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(quest).getStatus(),
                "the alt opted in with @botme quests on must start what its player starts");
        assertEquals(QuestStatus.Status.STARTED, player.getQuest(quest).getStatus());
    }

    @Test
    void altWithoutTheCommandIsLeftAlone() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);

        Quest quest = Quest.getInstance(Q_PLAIN);
        quest.start(player, NPC);

        assertEquals(QuestStatus.Status.NOT_STARTED, alt.getQuest(quest).getStatus(),
                "mirroring is off for every alt until its owner types @botme quests on <name>");

        new BotMeCommand().execute(player.getClient(), new String[]{"quests", "on", "Alt1"});
        Quest next = Quest.getInstance(Q_LEVEL);   // the alt is level 1 and the quest wants 50: still nothing
        next.start(player, NPC);
        assertEquals(QuestStatus.Status.NOT_STARTED, alt.getQuest(next).getStatus());

        Quest third = Quest.getInstance(Q_MOB);
        third.start(player, NPC);
        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(third).getStatus());
    }

    @Test
    void altToldToStopIsLeftAloneAgain() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);

        new BotMeCommand().execute(player.getClient(), new String[]{"quests", "on", "Alt1"});
        new BotMeCommand().execute(player.getClient(), new String[]{"quests", "off", "Alt1"});

        Quest quest = Quest.getInstance(Q_PLAIN);
        quest.start(player, NPC);
        assertEquals(QuestStatus.Status.NOT_STARTED, alt.getQuest(quest).getStatus());
    }

    // ---------------------------------------------------------------- who is a companion worth mirroring to

    @Test
    void altOutsideThePartyIsNotStarted() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_PLAIN);
        quest.start(player, NPC);

        assertEquals(QuestStatus.Status.NOT_STARTED, alt.getQuest(quest).getStatus(),
                "an alt that is not in the party does not quest along");
    }

    @Test
    void altOnAnotherChannelIsNotStarted() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 5);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_PLAIN);
        quest.start(player, NPC);

        assertEquals(QuestStatus.Status.NOT_STARTED, alt.getQuest(quest).getStatus(),
                "a companion on another channel is not there to quest along");
    }

    @Test
    void aRealPlayerInThePartyIsNeverMirroredTo() {
        Character player = player("Leader");
        Character friend = player("Friend");
        party(player, friend);

        Quest quest = Quest.getInstance(Q_PLAIN);
        quest.start(player, NPC);

        assertEquals(QuestStatus.Status.NOT_STARTED, friend.getQuest(quest).getStatus());
    }

    @Test
    void aCompanionThatCannotStartItIsSkippedSilently() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_LEVEL);   // the player is level 60, the alt level 1
        quest.start(player, NPC);

        assertEquals(QuestStatus.Status.STARTED, player.getQuest(quest).getStatus());
        assertEquals(QuestStatus.Status.NOT_STARTED, alt.getQuest(quest).getStatus(),
                "a companion that does not meet the requirements is skipped, not force-started");
    }

    // ---------------------------------------------------------------- completion, from the companion's own bag

    @Test
    void altWithTheItemsCompletesOnceAndKeepsTheReward() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);
        give(player, NEEDED_ITEM);
        give(alt, NEEDED_ITEM);

        Quest quest = Quest.getInstance(Q_ITEM);
        quest.start(player, NPC);
        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(quest).getStatus());

        quest.complete(player, NPC);

        assertEquals(QuestStatus.Status.COMPLETED, alt.getQuest(quest).getStatus(),
                "an alt holding the items itself turns the quest in with its player");
        assertEquals(QuestStatus.Status.COMPLETED, player.getQuest(quest).getStatus());
        assertEquals(1, alt.getInventory(InventoryType.ETC).countById(REWARD_ITEM),
                "the reward goes to the alt, once");

        // a second turn-in attempt changes nothing: the alt has nothing left to complete
        quest.complete(alt, NPC);
        assertEquals(1, alt.getInventory(InventoryType.ETC).countById(REWARD_ITEM),
                "the alt must never be granted the reward twice");
    }

    @Test
    void altWithoutTheItemsStaysStarted() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);
        give(player, NEEDED_ITEM);

        Quest quest = Quest.getInstance(Q_ITEM);
        quest.start(player, NPC);
        quest.complete(player, NPC);

        assertEquals(QuestStatus.Status.COMPLETED, player.getQuest(quest).getStatus());
        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(quest).getStatus(),
                "the item requirement is read from the companion's own bag, never the player's");
        assertEquals(0, alt.getInventory(InventoryType.ETC).countById(REWARD_ITEM));
    }

    /**
     * Wave 2 audit: QuestActionHandler passes the client's selection short whenever the packet carries one, which is
     * not the same as the quest offering a choice. Only a quest whose reward really is a pick (an item with prop -1)
     * is left for the companion; any other turn-in mirrors whatever number came with it.
     */
    @Test
    void aTurnInThatCarriesASelectionButOffersNoChoiceStillMirrors() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);
        give(player, NEEDED_ITEM);
        give(alt, NEEDED_ITEM);

        Quest quest = Quest.getInstance(Q_ITEM);
        quest.start(player, NPC);
        quest.complete(player, NPC, 0);   // the packet carried a selection, the quest has no choice

        assertEquals(QuestStatus.Status.COMPLETED, alt.getQuest(quest).getStatus(),
                "no reward choice in this quest, so the alt turns it in with its player");
        assertEquals(1, alt.getInventory(InventoryType.ETC).countById(REWARD_ITEM));
    }

    @Test
    void aRewardChoiceQuestIsNotMirrored() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_CHOICE);
        quest.start(player, NPC);
        quest.complete(player, NPC, 0);   // the player picked the reward

        assertEquals(QuestStatus.Status.COMPLETED, player.getQuest(quest).getStatus());
        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(quest).getStatus(),
                "a quest that takes the player's reward choice is logged and left for the alt");
    }

    // ---------------------------------------------------------------- kill credit

    @Test
    void theLeadersKillsCountForTheAltOnTheSameMap() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_MOB);
        quest.start(player, NPC);
        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(quest).getStatus());

        player.raiseQuestMobCount(MOB);

        assertEquals("001", alt.getQuest(quest).getProgress(MOB),
                "a mob the leader kills beside the alt counts for the alt's matching quest");
        assertEquals("001", player.getQuest(quest).getProgress(MOB));
    }

    @Test
    void theLeadersKillsDoNotCountForAnAltOnAnotherMap() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        alt.setMap(mock(MapleMap.class));
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_MOB);
        quest.start(player, NPC);
        assertEquals(QuestStatus.Status.STARTED, alt.getQuest(quest).getStatus());

        player.raiseQuestMobCount(MOB);

        assertEquals("000", alt.getQuest(quest).getProgress(MOB),
                "the credit comes from a kill beside the alt, not across the map");
    }

    // ---------------------------------------------------------------- the regular, both sides of its switch

    @Test
    void aRegularCompanionFollowsTheSwitch() {
        Character player = player("Leader");
        Character regular = regular(player, "Regular1", 2);
        party(player, regular);

        Quest off = Quest.getInstance(Q_PLAIN);
        off.start(player, NPC);
        assertEquals(QuestStatus.Status.NOT_STARTED, regular.getQuest(off).getStatus(),
                "a party regular is off by default: it does exactly what it did before");

        System.setProperty("slums.companion.quests.regulars", "true");
        Quest on = Quest.getInstance(Q_MOB);
        on.start(player, NPC);
        assertEquals(QuestStatus.Status.STARTED, regular.getQuest(on).getStatus(),
                "with -Dslums.companion.quests.regulars=true the regular quests along");
    }

    // ---------------------------------------------------------------- nothing existing is lost

    @Test
    void anAltThatAlreadyHasTheQuestKeepsWhatItHad() {
        Character player = player("Leader");
        Character alt = alt(player, "Alt1", 2);
        party(player, alt);
        CompanionQuestMirror.setOptIn(alt.getId(), true);

        Quest quest = Quest.getInstance(Q_MOB);
        QuestStatus done = new QuestStatus(quest, QuestStatus.Status.COMPLETED);
        done.setProgress(MOB, "005");
        alt.updateQuestStatus(done);
        int levelBefore = alt.getLevel();
        long expBefore = alt.getExp();

        quest.start(player, NPC);
        player.raiseQuestMobCount(MOB);

        assertEquals(QuestStatus.Status.COMPLETED, alt.getQuest(quest).getStatus(),
                "a quest the alt already completed is not reopened by the mirror");
        assertEquals("005", alt.getQuest(quest).getProgress(MOB), "its saved progress is left alone");
        assertEquals(levelBefore, alt.getLevel());
        assertEquals(expBefore, alt.getExp());
    }

    // ---------------------------------------------------------------- helpers

    /** A real player: a character whose client is not a bot client. */
    private static Character player(String name) {
        Client client = mock(Client.class);
        Character chr = Character.getDefault(client);
        chr.setID(nextId++);
        chr.setName(name);
        chr.setWorld(1);
        chr.setLevel(60);
        chr.setMap(mock(MapleMap.class));
        when(client.getPlayer()).thenReturn(chr);
        when(client.getAccID()).thenReturn(0);
        when(client.getChannel()).thenReturn(2);
        return chr;
    }

    /** A real character behind a bot client: an owned alt of {@code owner}. */
    private static Character alt(Character owner, String name, int channel) {
        BotClient client = new BotClient(1, channel);
        client.setAccID(owner.getAccountID());
        Character chr = Character.getDefault(client);
        chr.setID(nextId++);
        chr.setName(name);
        chr.setWorld(1);
        chr.setLevel(1);
        client.setPlayer(chr);
        chr.setMap(owner.getMap());
        chr.markOwnedAlt(owner);
        return chr;
    }

    /** A regular party bot: no owner, just a follower of {@code leader} by the bot registry. */
    private Character regular(Character leader, String name, int channel) {
        BotClient client = new BotClient(1, channel);
        Character chr = Character.getDefault(client);
        chr.setID(nextId++);
        chr.setName(name);
        chr.setWorld(1);
        chr.setLevel(60);
        client.setPlayer(chr);
        chr.setMap(leader.getMap());
        FollowerBot follower = mock(FollowerBot.class);
        when(follower.leaderId()).thenReturn(leader.getId());
        bots.when(() -> CharacterStorage.getBotById(chr.getId())).thenReturn(follower);
        return chr;
    }

    private static void party(Character... members) {
        Party party = mock(Party.class);
        List<PartyCharacter> characters = new ArrayList<>();
        for (Character member : members) {
            PartyCharacter pc = mock(PartyCharacter.class);
            when(pc.getId()).thenReturn(member.getId());
            when(pc.getPlayer()).thenReturn(member);
            characters.add(pc);
        }
        when(party.getMembers()).thenReturn(characters);
        for (Character member : members) {
            member.setParty(party);
        }
    }

    private static void give(Character chr, int itemId) {
        if (chr.getInventory(InventoryType.ETC).addItem(new Item(itemId, (short) 0, (short) 1)) < 0) {
            throw new IllegalStateException("the fixture could not put item " + itemId + " in the bag");
        }
    }
}
