package client.inventory;

import client.Character;
import client.Client;
import client.QuestStatus;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import net.packet.Packet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.MakerItemFactory;
import server.MakerItemFactory.MakerItemCreateEntry;
import server.quest.Quest;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ticket 112 (owner 2026-10-30): the real Maker is back with stock rules. This pins the shared Maker path that the
 * Maker window and the bot batch both use, the Maker skill id per job family, and the economy rules: stock recipes and
 * rates, USE_MAKER_PERMISSIVE_ATKUP off, and free recipes (monster crystals) actually giving their item.
 */
class MakerServiceTest {

    private static final int LEFTOVER = 4000000;
    private static final int CRYSTAL = 4260000;   // Basic Monster Crystal 1

    private Map<Integer, Skill> skillsWere;
    private final boolean permissiveWas = YamlConfig.config.server.USE_MAKER_PERMISSIVE_ATKUP;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(java.sql.Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
            Class.forName("server.MakerItemFactory");
            Class.forName("client.inventory.MakerService");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        for (int id : new int[]{1007, 10001007, 20001007, 20011007}) {
            skills.put(id, new Skill(id));
        }
        skillsWere = swapSkills(skills);
    }

    @AfterEach
    void tearDown() throws Exception {
        swapSkills(skillsWere);
        YamlConfig.config.server.USE_MAKER_PERMISSIVE_ATKUP = permissiveWas;
    }

    @SuppressWarnings("unchecked")
    static Map<Integer, Skill> swapSkills(Map<Integer, Skill> skills) throws Exception {
        Field field = SkillFactory.class.getDeclaredField("skills");
        field.setAccessible(true);
        Map<Integer, Skill> was = (Map<Integer, Skill>) field.get(null);
        field.set(null, skills);
        return was;
    }

    /** A character's inventory as plain counts: what the Maker path took and gave. */
    static final class FakeHands implements MakerService.Hands {
        final Character chr;
        final Map<Integer, Integer> held = new HashMap<>();
        final Map<Integer, Integer> given = new LinkedHashMap<>();
        final List<Packet> sent = new ArrayList<>();
        boolean roomy = true;
        int mesoPaid;

        FakeHands(Character chr) {
            this.chr = chr;
        }

        @Override
        public Character chr() {
            return chr;
        }

        @Override
        public int count(int itemId) {
            return held.getOrDefault(itemId, 0);
        }

        @Override
        public boolean canHold(List<Integer> addIds, List<Integer> addQty, List<Integer> removeIds, List<Integer> removeQty) {
            return roomy;
        }

        @Override
        public void take(int itemId, int quantity) {
            held.merge(itemId, -quantity, Integer::sum);
        }

        @Override
        public void give(int itemId, int quantity) {
            given.merge(itemId, quantity, Integer::sum);
            held.merge(itemId, quantity, Integer::sum);
        }

        @Override
        public void giveEquip(Item item) {
            give(item.getItemId(), 1);
        }

        @Override
        public void removeEquipAt(short pos) {
        }

        @Override
        public boolean payMeso(int amount) {
            mesoPaid += amount;
            return true;
        }

        @Override
        public void send(Packet packet) {
            sent.add(packet);
        }
    }

    private static Character explorer(int makerLevel) {
        Character chr = Character.getDefault(mock(Client.class));
        chr.setName("Maker");
        chr.setJob(client.Job.FIGHTER);
        chr.setLevel(50);
        if (makerLevel > 0) {
            chr.changeSkillLevel(SkillFactory.getSkill(1007), (byte) makerLevel, 3, -1);
        }
        return chr;
    }

    // --- the Maker skill per family ------------------------------------------------------------------------------

    @Test
    void eachJobFamilyHasItsOwnMakerSkill() {
        for (int job : new int[]{0, 100, 112, 232, 522, 900, 910}) {
            assertEquals(1007, MakerService.makerSkillId(job), "job " + job);
        }
        for (int job : new int[]{1000, 1100, 1512}) {
            assertEquals(10001007, MakerService.makerSkillId(job), "job " + job);
        }
        for (int job : new int[]{2000, 2100, 2112}) {
            assertEquals(20001007, MakerService.makerSkillId(job), "job " + job);
        }
        // Evan: the stock ids and this fork's 99xx ids (the old job / 1000 formula gave Aran's 20001007, or 90001007)
        for (int job : new int[]{2001, 2200, 2218, 9900, 9918}) {
            assertEquals(20011007, MakerService.makerSkillId(job), "job " + job);
        }
        assertEquals(constants.skills.Evan.MAKER, MakerService.makerSkillId(2210));
        assertEquals(constants.skills.Noblesse.MAKER, MakerService.makerSkillId(1110));
        assertEquals(constants.skills.Legend.MAKER, MakerService.makerSkillId(2110));
    }

    @Test
    void theMakerLevelReadsTheCharactersOwnFamily() {
        Character chr = explorer(2);
        assertEquals(2, MakerService.getMakerSkillLevel(chr));
        chr.setJob(client.Job.DAWNWARRIOR2);
        assertEquals(0, MakerService.getMakerSkillLevel(chr), "an Explorer's Maker is not a Cygnus Knight's");
    }

    // --- the shared path: monster crystals -----------------------------------------------------------------------

    @Test
    void aMonsterCrystalTakesOneHundredLeftoversAndGivesTheCrystal() {
        // Stock recipe: 100 leftovers, no fee, Maker 1. MapleRoot gave the items only when the fee was above 0, so a
        // crystal used to eat the leftovers and give nothing (and Moren's Maker 2 test could never be passed).
        MakerItemCreateEntry recipe = MakerItemFactory.generateLeftoverCrystalEntry(LEFTOVER, CRYSTAL);
        assertEquals(0, recipe.getCost());
        assertEquals(1, recipe.getReqSkillLevel());
        assertEquals(List.of(new tools.Pair<>(LEFTOVER, MakerService.LEFTOVERS_PER_CRYSTAL)).toString(),
                recipe.getReqItems().toString());
        assertEquals(100, MakerService.LEFTOVERS_PER_CRYSTAL);

        FakeHands h = new FakeHands(explorer(1));
        h.held.put(LEFTOVER, 130);
        short status = MakerService.getCreateStatus(h, recipe);
        assertEquals(0, status);
        MakerService.applyMakerResult(h, status, CRYSTAL, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 3);
        assertEquals(30, h.count(LEFTOVER));
        assertEquals(Map.of(CRYSTAL, 1), h.given, "a monster crystal is never upgraded by MapleRoot's gem luck");
        assertEquals(0, h.mesoPaid);
    }

    @Test
    void withoutTheSkillOrTheMaterialsNothingIsTakenOrGiven() {
        MakerItemCreateEntry recipe = MakerItemFactory.generateLeftoverCrystalEntry(LEFTOVER, CRYSTAL);
        FakeHands noSkill = new FakeHands(explorer(0));
        noSkill.held.put(LEFTOVER, 100);
        short status = MakerService.getCreateStatus(noSkill, recipe);
        assertEquals(4, status, "no Maker skill");
        MakerService.applyMakerResult(noSkill, status, CRYSTAL, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 3);
        assertEquals(100, noSkill.count(LEFTOVER));
        assertTrue(noSkill.given.isEmpty());

        FakeHands few = new FakeHands(explorer(1));
        few.held.put(LEFTOVER, 99);
        assertEquals(1, MakerService.getCreateStatus(few, recipe), "99 leftovers");

        FakeHands full = new FakeHands(explorer(1));
        full.held.put(LEFTOVER, 100);
        full.roomy = false;
        assertEquals(5, MakerService.getCreateStatus(full, recipe), "no room");
    }

    @Test
    void gemLuckNeverTouchesMonsterCrystals() {
        for (int i = 0; i < 2000; i++) {
            assertEquals(0, MakerService.gemLuck(CRYSTAL));
            assertEquals(0, MakerService.gemLuck(4260003));
            int luck = MakerService.gemLuck(4250000);
            assertTrue(luck >= 0 && luck <= 2);
            assertTrue(MakerService.gemLuck(4250001) <= 1);
        }
    }

    // --- Moren's Maker 2 test (quest 6033): the crystal counts from a disassembly too ------------------------------
    // Owner, 2026-11-01: a 4260003 the Maker skill made counts whether it came from monster leftovers (type 3) or out
    // of a disassembled equip (type 4), as long as 6033 was started first. A disassembly that never set the progress
    // sent players to 6033.js holding a crystal Moren then refused ("go craft me one").

    private static final int QUEST_6033 = 6033;
    private static final int CRYSTAL_BELOW_TEST = 4260000;   // Basic Monster Crystal 1: a different tier
    private static final int JUNK_EQUIP = 1002000;

    /** Quest 6033's status and progress live on the character; its WZ data is not needed for either. */
    private static Quest stubQuest() {
        Quest quest = mock(Quest.class);
        when(quest.getId()).thenReturn((short) QUEST_6033);
        return quest;
    }

    private static Character explorerOn6033(Quest quest, QuestStatus.Status status) {
        Character chr = explorer(1);
        chr.gainMeso(10_000, false);
        chr.updateQuestStatus(new QuestStatus(quest, status));
        return chr;
    }

    /** The disassembly of one equipped junk item, breaking into these crystals. */
    private static MakerItemCreateEntry junkDisassembly(int fee, int... crystals) {
        List<tools.Pair<Integer, Integer>> gains = new ArrayList<>();
        for (int crystal : crystals) {
            gains.add(new tools.Pair<>(crystal, 1));
        }
        return MakerItemFactory.generateDisassemblyCrystalEntry(JUNK_EQUIP, fee, gains);
    }

    private static void disassemble(FakeHands h, MakerItemCreateEntry recipe) {
        h.held.put(JUNK_EQUIP, 1);
        short status = MakerService.getCreateStatus(h, recipe);
        assertEquals(0, status, "the disassembly was refused");
        MakerService.applyMakerResult(h, status, JUNK_EQUIP, recipe, JUNK_EQUIP, (short) 0, -1, new LinkedHashMap<>(), true, 4);
    }

    @Test
    void aDisassemblyThatYieldsTheTestCrystalCountsWhileTheTestRuns() {
        try (var quests = mockStatic(Quest.class)) {
            Quest quest = stubQuest();
            quests.when(() -> Quest.getInstance(anyInt())).thenReturn(quest);

            FakeHands h = new FakeHands(explorerOn6033(quest, QuestStatus.Status.STARTED));
            disassemble(h, junkDisassembly(200, CRYSTAL_BELOW_TEST, MakerService.MAKER_2_TEST_CRYSTAL));

            assertEquals(1, h.given.get(MakerService.MAKER_2_TEST_CRYSTAL), "the disassembly gave the crystal");
            assertEquals("1", h.chr.getQuest(quest).getProgress(0), "the crystal a disassembly gave passes Moren's test");
        }
    }

    @Test
    void aDisassemblyBeforeTheTestStartedDoesNotCount() {
        try (var quests = mockStatic(Quest.class)) {
            Quest quest = stubQuest();
            quests.when(() -> Quest.getInstance(anyInt())).thenReturn(quest);

            FakeHands h = new FakeHands(explorerOn6033(quest, QuestStatus.Status.NOT_STARTED));
            disassemble(h, junkDisassembly(200, MakerService.MAKER_2_TEST_CRYSTAL));

            assertEquals(1, h.given.get(MakerService.MAKER_2_TEST_CRYSTAL));
            assertNotEquals("1", h.chr.getQuest(quest).getProgress(0), "a crystal from before the test proves nothing");
        }
    }

    @Test
    void aDisassemblyOfOtherCrystalsDoesNotCount() {
        try (var quests = mockStatic(Quest.class)) {
            Quest quest = stubQuest();
            quests.when(() -> Quest.getInstance(anyInt())).thenReturn(quest);

            FakeHands h = new FakeHands(explorerOn6033(quest, QuestStatus.Status.STARTED));
            disassemble(h, junkDisassembly(200, CRYSTAL_BELOW_TEST));

            assertEquals(1, h.given.get(CRYSTAL_BELOW_TEST));
            assertNotEquals("1", h.chr.getQuest(quest).getProgress(0), "only 4260003 is Moren's test crystal");
        }
    }

    @Test
    void aLeftoverCrystalStillCountsForTheTest() {
        try (var quests = mockStatic(Quest.class)) {
            Quest quest = stubQuest();
            quests.when(() -> Quest.getInstance(anyInt())).thenReturn(quest);

            FakeHands h = new FakeHands(explorerOn6033(quest, QuestStatus.Status.STARTED));
            h.held.put(LEFTOVER, MakerService.LEFTOVERS_PER_CRYSTAL);
            MakerItemCreateEntry recipe = MakerItemFactory.generateLeftoverCrystalEntry(LEFTOVER, MakerService.MAKER_2_TEST_CRYSTAL);
            short status = MakerService.getCreateStatus(h, recipe);
            assertEquals(0, status);
            MakerService.applyMakerResult(h, status, MakerService.MAKER_2_TEST_CRYSTAL, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 3);

            assertEquals("1", h.chr.getQuest(quest).getProgress(0), "the type 3 path is unchanged");

            FakeHands early = new FakeHands(explorerOn6033(quest, QuestStatus.Status.NOT_STARTED));
            early.held.put(LEFTOVER, MakerService.LEFTOVERS_PER_CRYSTAL);
            short earlyStatus = MakerService.getCreateStatus(early, recipe);
            MakerService.applyMakerResult(early, earlyStatus, MakerService.MAKER_2_TEST_CRYSTAL, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 3);
            assertEquals(1, early.given.get(MakerService.MAKER_2_TEST_CRYSTAL));
            assertNotEquals("1", early.chr.getQuest(quest).getProgress(0), "a crystal from before the test proves nothing");
        }
    }

    // --- economy pins ----------------------------------------------------------------------------------------------

    @Test
    void attackGemsGoOnWeaponsOnlyWithThePermissiveSwitchOff() {
        assertFalse(new config.ServerConfig().USE_MAKER_PERMISSIVE_ATKUP, "the code default is off");
        YamlConfig.config.server.USE_MAKER_PERMISSIVE_ATKUP = false;
        Map<Integer, Short> attackGem = new LinkedHashMap<>(Map.of(4250000, (short) 1));   // a W.ATT gem (type 42500)
        assertFalse(MakerService.removeOddMakerReagents(1002000, attackGem), "refused on a hat");
        Map<Integer, Short> onWeapon = new LinkedHashMap<>(Map.of(4250000, (short) 1));
        assertTrue(MakerService.removeOddMakerReagents(1302000, onWeapon), "allowed on a sword");
        Map<Integer, Short> twoOfAKind = new LinkedHashMap<>();
        twoOfAKind.put(4250600, (short) 2);
        twoOfAKind.put(4250601, (short) 1);
        assertTrue(MakerService.removeOddMakerReagents(1002000, twoOfAKind));
        assertEquals(Map.of(4250601, (short) 1), twoOfAKind, "the best of a kind, one of it");
    }

    @Test
    void noShippedConfigTurnsThePermissiveSwitchOn() throws Exception {
        Pattern on = Pattern.compile("USE_MAKER_PERMISSIVE_ATKUP\\W+(true|1)", Pattern.CASE_INSENSITIVE);
        List<Path> files = new ArrayList<>(List.of(Path.of("config.example.yaml")));
        try (Stream<Path> confs = Files.list(Path.of("docs/release"))) {
            confs.filter(p -> p.toString().endsWith(".conf")).forEach(files::add);
        }
        for (Path f : files) {
            Matcher m = on.matcher(Files.readString(f));
            assertFalse(m.find(), f + " turns USE_MAKER_PERMISSIVE_ATKUP on");
        }
    }

    @Test
    void stockRates() {
        assertEquals(90.0, MakerService.STIMULANT_SUCCESS_PERCENT, "a stimulant craft succeeds 90% (stock)");
        assertEquals(4260003, MakerService.MAKER_2_TEST_CRYSTAL, "Moren's Maker 2 test crystal");
    }
}
