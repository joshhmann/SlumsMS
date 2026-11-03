package soloMapling.Economy.exchange;

import soloMapling.Economy.exchange.AssetRegistry.Asset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.Exchange.Place;
import soloMapling.Economy.exchange.Exchange.Response;
import soloMapling.Economy.exchange.OrderBook.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 270: the item catalog. The scrolls, use items and ETC in {@code exchangeTickers.yaml} register as assets and
 * become tickers behind {@code -Dslums.exchange.catalog}; a legacy item never gets a second book; the owner's scroll
 * caps (1 per order, 3 open, 10 a day) are what the rows carry; and one account cannot take more than
 * {@code -Dslums.exchange.items.sellCapMesosPerDay} mesos out of item sells in a day.
 */
class ExchangeCatalogTest {

    static final String CATALOG = "slums.exchange.catalog";
    static final String TICKERS = "slums.exchange.tickers";
    static final String CAP = "slums.exchange.items.sellCapMesosPerDay";

    @BeforeEach
    void setUp() {
        ExchangeCatalog.reset();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(CATALOG);
        System.clearProperty(TICKERS);
        System.clearProperty(CAP);
        ExchangeCatalog.reset();
    }

    static List<String> ids(List<Ticker> ts) {
        List<String> out = new ArrayList<>();
        for (Ticker t : ts) {
            out.add(t.id());
        }
        return out;
    }

    static Ticker find(List<Ticker> ts, String id) {
        for (Ticker t : ts) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }

    @Test
    void theFileRegistersTheCatalogWithoutASecondBookForALegacyItem() {
        Map<String, Ticker> t = ExchangeCatalog.templates();
        assertFalse(t.isEmpty(), "exchangeTickers.yaml is read");
        // the file's 709 rows, less the three that fold into a 1.6.3 book instead of opening a twin
        // (S2049100 Chaos, U2000005 Power Elixir, E4001126 Maple Leaf)
        assertEquals(706, t.size(), "518/27/164 in the file, minus three legacy rows");
        assertEquals(517, t.values().stream().filter(x -> x.category() == AssetRegistry.Category.SCROLL).count());
        assertEquals(26, t.values().stream().filter(x -> x.category() == AssetRegistry.Category.USE).count());
        assertEquals(163, t.values().stream().filter(x -> x.category() == AssetRegistry.Category.ETC).count());
        for (Map.Entry<String, Ticker> e : t.entrySet()) {
            assertTrue(e.getKey().length() <= 8, e.getKey() + " fits varchar(8)");
            assertTrue(e.getValue().anchor() >= 0);
            assertTrue(e.getValue().item());
        }
        // CHAOS, WS, ELIXIR and LEAF keep their 1.6.3 ids: one book per item.
        assertNull(t.get("S2049100"), "CHAOS keeps its id");
        assertNull(t.get("S2340000"), "WS keeps its id");
        assertNull(t.get("U2000005"), "ELIXIR keeps its id");
        assertNull(t.get("E4001126"), "LEAF keeps its id");
        assertEquals(Asset.CHAOS, AssetRegistry.byItem(2049100));
        assertEquals(Asset.LEAF, AssetRegistry.byItem(4001126));
        // and the reverse lookup the fill hooks use works for a catalog row
        Asset scroll = AssetRegistry.of("S2040805");
        assertNotNull(scroll);
        assertEquals(2040805, scroll.itemId());
        assertNull(AssetRegistry.of("S204080500"), "an id is compared whole, not by prefix");
        assertNull(AssetRegistry.of("NX2"));
    }

    @Test
    void scrollsArriveWithTheOwnersCapsAndNothingElse() {
        System.setProperty(CATALOG, "scrolls");
        List<Ticker> ts = Ticker.fromSystem(ExchangeLiveTest.cfg(100, 0));
        List<String> ids = ids(ts);
        assertTrue(ids.contains(Ticker.NX));
        assertTrue(ids.contains("LEAF"), "the 1.6.3 item tickers stay on");
        Ticker glove = find(ts, "S2040805");
        assertNotNull(glove, "Glove for ATT 10% is in the catalog");
        assertEquals(1, glove.maxQty(), "one scroll per order (owner 2026-11-03)");
        assertEquals(3, glove.maxOpen());
        assertEquals(10, glove.perDay());
        assertEquals(1_200_000, glove.anchor(), "the item pool's price");
        assertNotNull(find(ts, "S2040804"));
        assertNull(find(ts, "U2000004"), "use items are a separate category");
        assertNull(find(ts, "E4006001"), "ETC is a separate category");
    }

    @Test
    void etcIsItsOwnCategoryAndAnUnpricedRowStillTrades() {
        System.setProperty(CATALOG, "etc");
        List<Ticker> ts = Ticker.fromSystem(ExchangeLiveTest.cfg(100, 0));
        Ticker rock = find(ts, "E4006001");
        assertNotNull(rock, "Summoning Rock: pool 10,000");
        assertEquals(10_000, rock.anchor());
        assertEquals(10_000, rock.maxQty(), "the ETC cap");
        assertNull(find(ts, "S2040805"), "no scrolls under etc");
        // a row the pool does not price is registered with anchor 0: no quote, but a player may still place an order
        Ticker powder = find(ts, "E4007001");
        assertNotNull(powder, "Magic Powder (White) is listed for its holders");
        assertEquals(0, powder.anchor());
        assertEquals(1, powder.minPrice());
    }

    @Test
    void theTickerListStillNarrowsTheCatalog() {
        System.setProperty(CATALOG, "scrolls,use");
        System.setProperty(TICKERS, "NX,S2040805");
        List<String> ids = ids(Ticker.fromSystem(ExchangeLiveTest.cfg(100, 0)));
        assertEquals(List.of(Ticker.NX, "S2040805"), ids, "the named tickers only");
    }

    @Test
    void theCategoryPropertiesAreReadEveryCall() {
        System.clearProperty(CATALOG);
        assertFalse(ExchangeLimits.catalogOn());
        List<String> ids = ids(Ticker.fromSystem(ExchangeLiveTest.cfg(100, 0)));
        assertEquals(List.of(Ticker.NX, "LEAF", "CHAOS", "WS", "ELIXIR"), ids, "the 1.6.3 set, unchanged");
        assertEquals(0, ExchangeLimits.sellCapMesosPerDay(), "the cap is off unless it is set");
        assertEquals(86_400_000L * 20_000, ExchangeLimits.dayStart(86_400_000L * 20_000 + 12_345));
    }

    @Test
    void theSitePayloadCarriesTheCategory() throws Exception {
        System.setProperty(CATALOG, "scrolls,etc");
        start();
        Ticker glove = find(ex.tickers(), "S2040805");
        assertNotNull(glove, "the catalog is on in the engine too");
        Map<String, Object> json = ExchangeSite.tickerJson(ex, glove, 0, 0);
        assertEquals("scroll", json.get("category"));
        assertEquals(2040805, json.get("item_id"));
        assertEquals("items", json.get("qty_label"));
        assertEquals("etc", ExchangeSite.tickerJson(ex, find(ex.tickers(), "E4006001"), 0, 0).get("category"));
        assertEquals("nx", ExchangeSite.tickerJson(ex, find(ex.tickers(), Ticker.NX), 0, 0).get("category"));
        assertEquals("leaf", ExchangeSite.tickerJson(ex, find(ex.tickers(), "LEAF"), 0, 0).get("category"));
        assertEquals("scroll", ExchangeSite.tickerJson(ex, find(ex.tickers(), "CHAOS"), 0, 0).get("category"));
        assertEquals("use", ExchangeSite.tickerJson(ex, find(ex.tickers(), "ELIXIR"), 0, 0).get("category"));
    }

    // ---------------------------------------------------------------- the sell cap

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange ex;

    void start() throws Exception {
        store = new MemoryExchangeStore();
        for (long a = 1; a <= 2; a++) {
            store.addAccount(a, 100_000);
            store.addCharacter(10 + a, a, 20_000_000);
            store.addItems(10 + a, Asset.LEAF, 100);
        }
        store.login(1, 11);
        store.login(2, 12);
        now = new AtomicLong(1_800_000_000_000L);
        ExchangeConfig cfg = ExchangeLiveTest.cfg(100, 0);
        ex = new Exchange(store, cfg, store::online, store.finder(), now::get, Ticker.fromSystem(cfg));
        ex.guard(ExchangeTest.engineGuard());
        ex.siteReauth(SiteReauth.OFF);
        assertTrue(ex.recover().isEmpty());
    }

    Response sell(long account, long price, long qty, String ticker) {
        return ex.place(new Place("c" + System.nanoTime(), account, 10 + account, Side.SELL, price, qty, ticker));
    }

    @Test
    void anAccountCannotTakeMoreThanTheDailyItemCeiling() throws Exception {
        System.setProperty(CAP, "20000000");
        start();
        Response big = sell(1, 600_000, 100, "LEAF");
        assertEquals("ITEMS_CAP", big.code(), "100 leaves at 600k is 60m, over the 20m ceiling");

        Response ok = sell(1, 600_000, 30, "LEAF");
        assertTrue(ok.ok(), ok.code() + " " + ok.message());

        Response again = sell(1, 600_000, 10, "LEAF");
        assertEquals("ITEMS_CAP", again.code(), "18m resting plus 6m more is over the ceiling");
        assertTrue(again.message().contains("20,000,000"), again.message());

        // the ceiling is per account: the other account is untouched
        assertTrue(sell(2, 600_000, 30, "LEAF").ok());
        // and NX is not an item sell
        assertTrue(sell(1, 5_000_000, 100, "NX").ok());
    }

    @Test
    void whatFilledTodayCountsAgainstTheCeilingToo() throws Exception {
        System.setProperty(CAP, "20000000");
        start();
        assertTrue(sell(2, 600_000, 10, "LEAF").ok());
        now.addAndGet(11_000);
        assertTrue(ex.place(new Place("buy", 1, 11, Side.BUY, 600_000, 10, "LEAF")).ok(), "the fill");
        long used = store.itemExposure(2, ExchangeLimits.dayStart(now.get()));
        assertEquals(6_000_000, used, "10 leaves at 600k filled");
        // 6m filled + 20 more at 600k = 18m: still under; 4 more would cross it
        assertTrue(sell(2, 600_000, 20, "LEAF").ok());
        assertEquals("ITEMS_CAP", sell(2, 600_000, 4, "LEAF").code());
    }
}
