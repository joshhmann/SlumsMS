package soloMapling.Gachapon;

import client.BotClient;
import client.Character;
import client.inventory.InventoryType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.io.StringReader;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/** 1.5.0: bots buy gachapon tickets with their own mesos, within their limits, and the prize lands in their bag. */
class BotGachaTest {

    static final String POOL = """
            tiers:
              common:
                weight: 1
              rare:
                weight: 1
                announce: true
            towns:
              henesys:
                name: Henesys
                npc: 9100100
                maps:
                  - 100000100
                common:
                  - 2000004 x20
                rare:
                  - 2049100
            """;

    final AtomicLong now = new AtomicLong(10_000_000L);
    final AtomicLong day = new AtomicLong(20_000);
    final List<String> notices = new ArrayList<>();
    MockedStatic<server.ItemInformationProvider> itemData;
    GachaPools pools;
    int nextId = 700_000;

    @BeforeAll
    static void initItemProvider() throws Exception {
        try (var db = mockStatic(tools.DatabaseConnection.class);
             var providers = mockStatic(provider.DataProviderFactory.class)) {
            db.when(tools.DatabaseConnection::getConnection)
                    .thenReturn(mock(java.sql.Connection.class, org.mockito.Mockito.RETURNS_DEEP_STUBS));
            providers.when(() -> provider.DataProviderFactory.getDataProvider(
                    org.mockito.ArgumentMatchers.any(provider.wz.WZFiles.class))).thenReturn(mock(provider.DataProvider.class));
            Class.forName("server.ItemInformationProvider");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        itemData = mockStatic(server.ItemInformationProvider.class);
        itemData.when(server.ItemInformationProvider::getInstance).thenReturn(mock(server.ItemInformationProvider.class));
        pools = GachaPools.parse(new StringReader(POOL), id -> true);
        GachaPools.setForTest(pools);
        BotGacha.resetForTest(now::get, day::get);
        soloMapling.ArtificialPlayer.BotWealthTestAccess.reset();
        GachaAnnouncer.get().useForTest(now::get, () -> true, () -> { }, n -> notices.add(n.winner().getName()));
    }

    @AfterEach
    void tearDown() {
        itemData.close();
        GachaPools.setForTest(null);
        BotGacha.resetForTest(null, null);
        GachaAnnouncer.get().useForTest(null, null, null, null);
        System.clearProperty("solomapling.botGachaPerHour");
    }

    Character bot(int level, int meso) throws Exception {
        Character b = Character.getDefault(new BotClient(0, 0));
        Field f = Character.class.getDeclaredField("id");
        f.setAccessible(true);
        f.setInt(b, nextId++);
        b.setName("gambler" + b.getId());
        b.setLevel(level);
        b.gainMeso(meso - b.getMeso(), false, false, false);
        return b;
    }

    GachaPools.Town henesys() {
        return pools.town("henesys");
    }

    @Test
    void aRichBotPaysForATicketAndGetsThePrize() throws Exception {
        Character b = bot(50, 5_000_000);   // rate 1 in a test: ceiling 300,000
        String line = BotGacha.play(b, henesys(), true, new Random(2), false);
        assertTrue(line.contains("bought a 1,000,000 ticket at Henesys"), line);
        assertEquals(4_000_000, b.getMeso());
        int items = b.getInventory(InventoryType.USE).countById(2000004) + b.getInventory(InventoryType.USE).countById(2049100);
        assertTrue(items >= 1, "the prize is in its bag");
        assertEquals(1, BotGacha.ticketsToday(b.getId()));
    }

    @Test
    void anOverCeilingBotStopsAt11TicketsADay() throws Exception {
        Character b = bot(50, 50_000_000);
        for (int i = 0; i < 11; i++) {
            assertTrue(BotGacha.play(b, henesys(), true, new Random(i), false).contains("bought"));
        }
        String line = BotGacha.play(b, henesys(), true, new Random(99), false);
        assertTrue(line.contains("has played its 11 tickets today"), line);
        assertEquals(39_000_000, b.getMeso());
        day.incrementAndGet();
        assertTrue(BotGacha.play(b, henesys(), true, new Random(1), false).contains("bought"), "a new day");
    }

    @Test
    void aBotUnderItsCeilingKeepsHalfOfIt() throws Exception {
        Character b = bot(120, 1_500_000);   // ceiling 1,728,000 at rate 1: it would keep 864,000
        String line = BotGacha.play(b, henesys(), false, new Random(1), false);
        assertTrue(line.contains("would not keep 864,000"), line);
        assertEquals(1_500_000, b.getMeso());
    }

    @Test
    void theWorldHourlyLimitHolds() throws Exception {
        System.setProperty("solomapling.botGachaPerHour", "2");
        Character a = bot(50, 50_000_000), b = bot(50, 50_000_000);
        assertTrue(BotGacha.play(a, henesys(), true, new Random(1), false).contains("bought"));
        assertTrue(BotGacha.play(b, henesys(), true, new Random(1), false).contains("bought"));
        assertTrue(BotGacha.play(a, henesys(), true, new Random(1), false).contains("limit for this hour"));
        now.addAndGet(3_600_000);
        assertTrue(BotGacha.play(a, henesys(), true, new Random(1), false).contains("bought"));
    }

    @Test
    void aGmRollIsFreeAndOutsideTheLimits() throws Exception {
        Character b = bot(10, 0);
        String line = BotGacha.play(b, henesys(), false, new Random(1), true);
        assertTrue(line.contains("rolled (GM, free)"), line);
        assertEquals(0, b.getMeso());
    }

    @Test
    void aBotRareCanBeAnnounced() throws Exception {
        Character b = bot(50, 50_000_000);
        int rares = 0;
        for (int i = 0; i < 11; i++) {
            String line = BotGacha.play(b, henesys(), true, new Random(i * 31L), false);
            rares += line.contains(" rare ") ? 1 : 0;
        }
        assertTrue(rares > 0);
        assertEquals(1, notices.size(), "only one bot notice per 15 minutes");
    }

    @Test
    void aboutAThirdOfBotsAreFans() {
        int fans = 0;
        for (int id = 500_000; id < 510_000; id++) {
            fans += BotGacha.isFan(id) ? 1 : 0;
        }
        assertEquals(3_500, fans, 300);
    }
}
