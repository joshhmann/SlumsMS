package soloMapling.ArtificialPlayer.BotMakerSystem;

import client.BotClient;
import client.Character;
import client.Skill;
import client.SkillFactory;
import client.inventory.Equip;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.MakerService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;
import server.MakerItemFactory;
import server.MakerItemFactory.MakerItemCreateEntry;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.Economy.EconomyConfig;
import soloMapling.Economy.EconomyMeters;
import soloMapling.Economy.EconomyStore;
import tools.Pair;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

/**
 * Ticket 112 follow-up (after 162-makerfee made disassembly cost mesos): a population bot's Maker fee comes out of its
 * real wallet (the one BotHoldings spends from), a bot that cannot pay gets nothing and loses nothing, a bot that can
 * pays exactly the client's fee, and the daily caps count only operations that happened.
 */
class BotMakerFeeTest {

    private static final int HELM = 1002028;       // Silver Crusader Helm: ItemMake.img meso 50,000
    private static final int GEM = 4250000;        // a fee recipe whose result is a plain item
    private static final int GEM_ORE = 4007000;
    private static final int CRYSTAL = 4260000;

    private Map<Integer, Skill> skillsWere;
    private MockedStatic<tools.DatabaseConnection> db;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection).thenReturn(mock(Connection.class, RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(any(provider.wz.WZFiles.class)))
                    .thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
            Class.forName("server.MakerItemFactory");
        }
        cache("makerMesoCache").put(HELM, 50000);
        Map<String, Integer> base = new LinkedHashMap<>();
        base.put("PDD", 54);
        base.put("MDD", 24);
        base.put("STR", 2);
        base.put("cash", 0);
        cache("equipStatsCache").put(HELM, base);

        MakerItemCreateEntry gem = new MakerItemCreateEntry(11000, 0, 1);
        gem.getGainItems().add(new Pair<>(GEM, 1));
        gem.getReqItems().add(new Pair<>(GEM_ORE, 10));
        cache("makerItemCache").put(GEM, gem);
        cache("makerMesoCache").put(GEM, 10000);   // the window shows 11,000
    }

    @SuppressWarnings("unchecked")
    private static <V> Map<Integer, V> cache(String name) throws Exception {
        Field f = ItemInformationProvider.class.getDeclaredField(name);
        f.setAccessible(true);
        return (Map<Integer, V>) f.get(ItemInformationProvider.getInstance());
    }

    @BeforeEach
    void setUp() throws Exception {
        skillsWere = swap(new HashMap<>(Map.of(1007, new Skill(1007))));
        LocalDate today = LocalDate.ofEpochDay(300);
        EconomyMeters.useForTest(new EconomyStore.Memory(), Runnable::run, () -> today);
        BotMaker.useForTest(() -> today);
        EconomyConfig.useForTest(Map.of("maker.botBatch", "true", "maker.perBotDaily", "10", "maker.worldDaily", "100"));
        // makercreatedata / makerrecipedata for the helm: req_meso 55,000 and 5 Basic Monster Crystal 1
        Connection con = mock(Connection.class);
        when(con.prepareStatement(anyString())).thenAnswer(inv -> {
            PreparedStatement ps = mock(PreparedStatement.class);
            when(ps.executeQuery()).thenAnswer(q -> {
                ResultSet rs = mock(ResultSet.class);
                when(rs.next()).thenReturn(true, false);
                when(rs.getInt("req_meso")).thenReturn(55000);
                when(rs.getInt("req_item")).thenReturn(CRYSTAL);
                when(rs.getInt("count")).thenReturn(5);
                return rs;
            });
            return ps;
        });
        db = mockStatic(tools.DatabaseConnection.class);
        db.when(tools.DatabaseConnection::getConnection).thenReturn(con);
    }

    @AfterEach
    void tearDown() throws Exception {
        db.close();
        swap(skillsWere);
        EconomyConfig.useForTest(null);
        EconomyMeters.useForTest(null, null, null);
        BotMaker.useForTest(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer, Skill> swap(Map<Integer, Skill> skills) throws Exception {
        Field f = SkillFactory.class.getDeclaredField("skills");
        f.setAccessible(true);
        Map<Integer, Skill> was = (Map<Integer, Skill>) f.get(null);
        f.set(null, skills);
        return was;
    }

    private static final AtomicInteger ids = new AtomicInteger(1_000_100_000);

    private static Character bot(int meso) {
        Character chr = Character.getDefault(new BotClient(1, 1));
        try {
            Field f = Character.class.getDeclaredField("id");
            f.setAccessible(true);
            f.setInt(chr, ids.incrementAndGet());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        chr.setName("FeeBot");
        chr.setJob(client.Job.FIGHTER);
        chr.setLevel(60);
        chr.changeSkillLevel(SkillFactory.getSkill(1007), (byte) 1, 3, -1);
        if (meso > 0) {
            chr.gainMeso(meso, false, false, false);
        }
        Equip helm = new Equip(HELM, (short) 0);
        helm.setWdef((short) 54);
        helm.setMdef((short) 24);
        helm.setStr((short) 2);
        chr.getInventory(InventoryType.EQUIP).addItem(helm);
        chr.getInventory(InventoryType.ETC).addItem(new Item(GEM_ORE, (short) 0, (short) 10));
        assertTrue(BotHelpers.isRosterBot(chr), "a population bot");
        return chr;
    }

    private static short helmPos(Character chr) {
        for (Item it : chr.getInventory(InventoryType.EQUIP).list()) {
            if (it.getItemId() == HELM) {
                return it.getPosition();
            }
        }
        return -1;
    }

    private static BotMaker.Result disassemble(Character chr) {
        MakerService.Hands h = BotMaker.hands(chr);
        assertTrue(h instanceof BotMakerHands);
        short pos = helmPos(chr);
        return BotMaker.run(chr, List.of(() -> MakerService.disassemble(h, pos, HELM)), 99);
    }

    private static short craftGem(MakerService.Hands h) {
        MakerItemCreateEntry recipe = MakerItemFactory.getItemCreateEntry(GEM, -1, new LinkedHashMap<>());
        short status = MakerService.getCreateStatus(h, recipe);
        MakerService.applyMakerResult(h, status, GEM, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 1);
        return status;
    }

    private static int gems(Character chr) {
        int n = 0;
        for (int id = GEM; id <= GEM + 2; id++) {
            n += chr.getInventory(InventoryType.ETC).countById(id);
        }
        return n;
    }

    @Test
    void aBotWithoutMesosCannotDisassemble() {
        Character chr = bot(0);
        BotMaker.Result r = disassemble(chr);
        assertEquals(0, r.made());
        assertEquals("out of mesos", r.stoppedBy());
        assertNotNull(chr.getInventory(InventoryType.EQUIP).getItem(helmPos(chr)), "the helm is kept");
        assertEquals(0, chr.getInventory(InventoryType.ETC).countById(CRYSTAL), "no free crystals");
        assertEquals(0, chr.getMeso());
        assertEquals(0, BotMaker.doneToday(chr.getId()), "not counted toward the bot's cap");
        assertEquals(0, EconomyMeters.get(BotMaker.WORLD_METER), "nor the world's");
    }

    @Test
    void aBotWithEnoughPaysTheClientsDisassemblyFee() {
        Character chr = bot(10000);
        short pos = helmPos(chr);
        BotMaker.Result r = disassemble(chr);
        assertEquals(1, r.made(), r.stoppedBy());
        assertEquals(3000, chr.getMeso(), "the window's 7,000");
        assertNull(chr.getInventory(InventoryType.EQUIP).getItem(pos), "the helm is gone");
        assertEquals(2, chr.getInventory(InventoryType.ETC).countById(CRYSTAL), "half the recipe's 5 crystals");
        // the caps count operations: one disassembly is one, though it gave two crystals (economy.yaml maker.*)
        assertEquals(1, BotMaker.doneToday(chr.getId()));
        assertEquals(1, EconomyMeters.get(BotMaker.WORLD_METER));
    }

    @Test
    void aBotWithoutMesosCannotCraftAFeeRecipe() {
        Character chr = bot(10999);
        AtomicInteger status = new AtomicInteger();
        BotMaker.Result r = BotMaker.run(chr, List.of(() -> {
            short s = craftGem(BotMaker.hands(chr));
            status.set(s);
            return s;
        }), 99);
        assertEquals(2, status.get(), "out of mesos");
        assertEquals(0, r.made());
        assertEquals(10, chr.getInventory(InventoryType.ETC).countById(GEM_ORE), "materials kept");
        assertEquals(0, gems(chr), "no free gem");
        assertEquals(10999, chr.getMeso());
        assertEquals(0, BotMaker.doneToday(chr.getId()));
    }

    @Test
    void aBotWithEnoughPaysTheClientsCraftFee() {
        Character chr = bot(20000);
        BotMaker.Result r = BotMaker.run(chr, List.of(() -> craftGem(BotMaker.hands(chr))), 99);
        assertEquals(1, r.made(), r.stoppedBy());
        assertEquals(9000, chr.getMeso(), "the window's 11,000");
        assertEquals(0, chr.getInventory(InventoryType.ETC).countById(GEM_ORE));
        assertEquals(1, gems(chr));
        assertEquals(1, BotMaker.doneToday(chr.getId()));
    }

    @Test
    void aWalletEmptiedAfterTheCheckStillGetsNothingForFree() {
        // The status was worked out while the bot could pay; its mesos left (a trade, a shop) before the result.
        Character chr = bot(20000);
        MakerService.Hands h = BotMaker.hands(chr);
        MakerItemCreateEntry recipe = MakerItemFactory.getItemCreateEntry(GEM, -1, new LinkedHashMap<>());
        short status = MakerService.getCreateStatus(h, recipe);
        assertEquals(0, status);
        chr.gainMeso(-20000, false, false, false);
        MakerService.applyMakerResult(h, status, GEM, recipe, -1, (short) -1, -1, new LinkedHashMap<>(), true, 1);
        assertEquals(0, chr.getMeso(), "never negative");
        assertEquals(10, chr.getInventory(InventoryType.ETC).countById(GEM_ORE), "materials kept");
        assertEquals(0, gems(chr), "no free gem");
    }
}
