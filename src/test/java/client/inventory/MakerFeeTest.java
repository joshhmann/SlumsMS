package client.inventory;

import client.Character;
import client.Client;
import client.Skill;
import client.SkillFactory;
import config.YamlConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import server.ItemInformationProvider;
import server.MakerItemFactory;
import server.MakerItemFactory.MakerItemCreateEntry;
import tools.Pair;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * Ticket 112 follow-up (Client lane, real v83 client on TEST 2026-10-30): the server must charge exactly the Maker fee
 * the client shows, because the v83 client computes and prints the fee itself.
 * <ul>
 * <li>Silver Crusader Helm (1002028, ItemMake.img meso 50,000) showed 55,000, and 80,000 with its Armor Production
 * Stimulator; the server took 61,000 (the DB's pre-marked-up 55,000 plus Cosmic's curve-fit stimulator fee).</li>
 * <li>Disassembling it showed 7,000 and the server took nothing (the disassembly recipe never committed its cost).</li>
 * </ul>
 * The client's formulas (read from the v83 MapleStory.exe, CUIItemMaker at 0x824e8d and 0x5ca659) are pinned here.
 */
class MakerFeeTest {

    private static final int HELM = 1002028;             // Silver Crusader Helm, reqLevel 50
    private static final int HELM_STIMULATOR = 4130018;  // Armor Production Stimulator
    private static final int HELM_WZ_MESO = 50000;       // Etc/ItemMake.img/0/01002028/meso
    private static final int HELM_DB_REQ_MESO = 55000;   // makercreatedata.req_meso (Cosmic's marked-up copy)

    private static final int KATAR = 1302010;            // Jeweled Katar
    private static final int KATAR_STIMULATOR = 4130002;
    private static final int KATAR_WZ_MESO = 60000;      // Etc/ItemMake.img/0/01302010/meso
    private static final int KATAR_DB_REQ_MESO = 66000;  // makercreatedata.req_meso

    private final boolean heuristicsWere = YamlConfig.config.server.USE_MAKER_FEE_HEURISTICS;
    private Map<Integer, Skill> skillsWere;

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
        // What TEST's DB and WZ hold for the helm.
        MakerItemCreateEntry helm = new MakerItemCreateEntry(HELM_DB_REQ_MESO, 45, 1);
        helm.getGainItems().add(new Pair<>(HELM, 1));
        cache("makerItemCache").put(HELM, helm);
        cache("makerCatalystCache").put(HELM, HELM_STIMULATOR);
        cache("equipLevelReqCache").put(HELM, 50);
        cache("makerMesoCache").put(HELM, HELM_WZ_MESO);
        Map<String, Integer> base = new LinkedHashMap<>();
        base.put("PDD", 54);
        base.put("MDD", 24);
        base.put("STR", 2);
        base.put("cash", 0);
        cache("equipStatsCache").put(HELM, base);

        MakerItemCreateEntry katar = new MakerItemCreateEntry(KATAR_DB_REQ_MESO, 45, 1);
        katar.getGainItems().add(new Pair<>(KATAR, 1));
        cache("makerItemCache").put(KATAR, katar);
        cache("makerCatalystCache").put(KATAR, KATAR_STIMULATOR);
        cache("equipLevelReqCache").put(KATAR, 50);
        cache("makerMesoCache").put(KATAR, KATAR_WZ_MESO);
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<Integer, V> cache(String name) throws Exception {
        Field f = ItemInformationProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<Integer, V>) f.get(ItemInformationProvider.getInstance());
    }

    @BeforeEach
    void setUp() throws Exception {
        Map<Integer, Skill> skills = new HashMap<>();
        skills.put(1007, new Skill(1007));
        skillsWere = MakerServiceTest.swapSkills(skills);
    }

    @AfterEach
    void tearDown() throws Exception {
        MakerServiceTest.swapSkills(skillsWere);
        YamlConfig.config.server.USE_MAKER_FEE_HEURISTICS = heuristicsWere;
    }

    private static Character maker() {
        Character chr = Character.getDefault(mock(Client.class));
        chr.setName("Maker");
        chr.setJob(client.Job.FIGHTER);
        chr.setLevel(60);
        chr.changeSkillLevel(SkillFactory.getSkill(1007), (byte) 1, 3, -1);
        chr.gainMeso(570000, false);
        return chr;
    }

    private static Map<Integer, Short> gems(int... ids) {
        Map<Integer, Short> m = new LinkedHashMap<>();
        for (int id : ids) {
            m.merge(id, (short) 1, (a, b) -> (short) (a + b));
        }
        return m;
    }

    // --- crafting: what the window shows is what the server takes -----------------------------------------------

    @Test
    void theHelmCostsWhatTheWindowShowsWithAndWithoutTheStimulator() {
        for (boolean heuristics : new boolean[]{true, false}) {
            YamlConfig.config.server.USE_MAKER_FEE_HEURISTICS = heuristics;
            assertEquals(55000, MakerItemFactory.getItemCreateEntry(HELM, -1, gems()).getCost(),
                    "plain craft, heuristics " + heuristics);
            assertEquals(80000, MakerItemFactory.getItemCreateEntry(HELM, HELM_STIMULATOR, gems()).getCost(),
                    "with the stimulator (the server took 61,000), heuristics " + heuristics);
        }
    }

    @Test
    void theKatarWithStimulatorAndGemCostsWhatTheWindowShows() {
        // Client lane, real client: the window showed 114,000 and the server took 115,000.
        for (boolean heuristics : new boolean[]{true, false}) {
            YamlConfig.config.server.USE_MAKER_FEE_HEURISTICS = heuristics;
            assertEquals(114000, MakerItemFactory.getItemCreateEntry(KATAR, KATAR_STIMULATOR, gems(4250000)).getCost(),
                    "heuristics " + heuristics);
        }
        assertEquals(66000, MakerItemFactory.getItemCreateEntry(KATAR, -1, gems()).getCost(), "plain craft");
    }

    @Test
    void theHelmWithGemsCostsWhatTheWindowShows() {
        // One gem of grade g adds 3 * g tenths of the recipe meso (basic g=1, intermediate 2, advanced 3).
        assertEquals(70000, MakerItemFactory.getItemCreateEntry(HELM, -1, gems(4250600)).getCost(), "a basic gem");
        assertEquals(85000, MakerItemFactory.getItemCreateEntry(HELM, -1, gems(4250601)).getCost(), "an intermediate gem");
        assertEquals(100000, MakerItemFactory.getItemCreateEntry(HELM, -1, gems(4250602)).getCost(), "an advanced gem");
        assertEquals(110000, MakerItemFactory.getItemCreateEntry(HELM, HELM_STIMULATOR, gems(4250601)).getCost(),
                "the stimulator and an intermediate gem");
    }

    @Test
    void theCraftPathChargesTheShownFee() {
        Character chr = maker();
        MakerServiceTest.FakeHands h = new MakerServiceTest.FakeHands(chr);
        MakerItemCreateEntry recipe = MakerItemFactory.getItemCreateEntry(HELM, -1, gems());
        // materials are not modelled by the fake; the fee is what this pins
        MakerService.applyMakerResult(h, (short) 0, HELM, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 1);
        assertEquals(55000, h.mesoPaid);
    }

    @Test
    void theClientCraftFormula() {
        // fee = meso + (meso / 10) * (1 + 5 * stimulator + 3 * sum(gem grade)), cut down to a whole 1,000
        assertEquals(55000, MakerItemFactory.clientCraftFee(50000, false, List.of()));
        assertEquals(80000, MakerItemFactory.clientCraftFee(50000, true, List.of()));
        assertEquals(41000, MakerItemFactory.clientCraftFee(37500, false, List.of()), "41,250 cut to 41,000");
        assertEquals(1000, MakerItemFactory.clientCraftFee(1000, false, List.of()), "1,100 cut to 1,000");
        assertEquals(0, MakerItemFactory.clientCraftFee(0, false, List.of()), "a recipe without meso (Gold Anvil) stays free");
        assertEquals(0, MakerItemFactory.clientCraftFee(0, true, List.of(4250002)));
        assertEquals(50000 + 5000 * (1 + 5 + 3 * (1 + 3)), MakerItemFactory.clientCraftFee(50000, true, List.of(4250000, 4251002)));
    }

    // --- disassembly ---------------------------------------------------------------------------------------------

    @Test
    void aDisassemblyRecipeKeepsItsFee() {
        MakerItemCreateEntry recipe = MakerItemFactory.generateDisassemblyCrystalEntry(HELM, 7000, List.of(new Pair<>(4260000, 2)));
        assertEquals(7000, recipe.getCost(), "the disassembly fee was never committed, so nothing was charged");
    }

    @Test
    void disassemblingTheHelmChargesTheShownFee() {
        Character chr = maker();
        MakerServiceTest.FakeHands h = new MakerServiceTest.FakeHands(chr);
        MakerItemCreateEntry recipe = MakerItemFactory.generateDisassemblyCrystalEntry(HELM, 7000, List.of(new Pair<>(4260000, 2)));
        MakerService.applyMakerResult(h, (short) 0, HELM, recipe, HELM, (short) 1, -1, new LinkedHashMap<>(), true, 4);
        assertEquals(7000, h.mesoPaid);
        assertEquals(Map.of(4260000, 2), h.given);
    }

    @Test
    void theDisassemblyFeeFollowsTheItemsQuality() {
        // (meso / 10) * {100, 150, 200, 250, 300}% by the item's quality grade (-1 below its base stats, then 0..3),
        // cut down to a whole 1,000.
        Equip plain = helm(54, 24, 2);
        assertEquals(7000, MakerItemFactory.getDisassemblyFee(plain), "an average helm: 7,500 cut to 7,000 (as shown)");
        assertEquals(5000, MakerItemFactory.getDisassemblyFee(helm(53, 24, 2)), "below its base: 100%");
        assertEquals(7000, MakerItemFactory.getDisassemblyFee(helm(59, 24, 2)), "+5: still grade 0");
        assertEquals(10000, MakerItemFactory.getDisassemblyFee(helm(60, 24, 2)), "+6: grade 1, 200%");
        assertEquals(12000, MakerItemFactory.getDisassemblyFee(helm(60, 41, 2)), "+23: grade 2, 250% (12,500)");
        assertEquals(15000, MakerItemFactory.getDisassemblyFee(helm(60, 58, 2)), "+40: grade 3, 300%");
        Equip hp = helm(54, 24, 2);
        hp.setHp((short) 59);
        assertEquals(7000, MakerItemFactory.getDisassemblyFee(hp), "HP and MP count a tenth (59 / 10 = 5)");
        hp.setHp((short) 60);
        assertEquals(10000, MakerItemFactory.getDisassemblyFee(hp));
    }

    @Test
    void theClientDisassemblyFormula() {
        assertEquals(5000, MakerItemFactory.clientDisassemblyFee(50000, -1));
        assertEquals(7000, MakerItemFactory.clientDisassemblyFee(50000, 0));
        assertEquals(10000, MakerItemFactory.clientDisassemblyFee(50000, 1));
        assertEquals(12000, MakerItemFactory.clientDisassemblyFee(50000, 2));
        assertEquals(15000, MakerItemFactory.clientDisassemblyFee(50000, 3));
        assertEquals(0, MakerItemFactory.clientDisassemblyFee(6000, 0), "900 cut to 0");
    }

    private static Equip helm(int pdd, int mdd, int str) {
        Equip e = new Equip(HELM, (short) 1);
        e.setWdef((short) pdd);
        e.setMdef((short) mdd);
        e.setStr((short) str);
        return e;
    }
}
