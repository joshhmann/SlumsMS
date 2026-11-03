package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.AssetRegistry.Asset;
import soloMapling.Economy.exchange.Exchange;
import soloMapling.Economy.exchange.ExchangeCatalog;
import soloMapling.Economy.exchange.ExchangeChat;
import soloMapling.Economy.exchange.ExchangeConfig;
import soloMapling.Economy.exchange.ExchangeStore;
import soloMapling.Economy.exchange.Ledger;
import soloMapling.Economy.exchange.LiveWallet;
import soloMapling.Economy.exchange.MemoryExchangeStore;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.Economy.exchange.Ticker;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 270 (owner 2026-11-03: "sell from site item wallet"): a character that is not in the game sells ticker items
 * from its own bags, so the site's item sell places instead of answering LOG_IN_FIRST. The bags are read from the
 * character's row, the escrow comes out of them, and a fill's mesos are swept into the character at once (it may not
 * log in again soon).
 */
class SiteItemSellTest {

    static final String CATALOG = "slums.exchange.catalog";
    static final String MAKER = "slums.exchange.itemMaker";
    static final String SITE_ITEMS = "slums.exchange.siteItems";
    static final long NOW = 1_800_000_000_000L;
    static final long HOUSE = 9, HOUSE_CHAR = 19, PLAYER = 4, PLAYER_CHAR = 44;

    MemoryExchangeStore store;
    AtomicLong now;
    Exchange engine;
    Ticker rock;

    static ExchangeConfig cfg() {
        ExchangeConfig d = ExchangeConfig.defaults();
        return new ExchangeConfig(true, d.lotNx(), d.minNx(), d.maxNx(), d.minPrice(), d.maxPrice(), 100, 1_000,
                100_000, 0, TimeUnit.HOURS.toMillis(72), 3, d.regularSpreadBps(), d.regularNx(), d.rateWindowMs(), 1,
                d.pollSeconds(), 9, 60_000);
    }

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty(CATALOG, "etc");
        System.setProperty(MAKER, "true");
        ExchangeCatalog.reset();
        store = new MemoryExchangeStore();
        store.addAccount(HOUSE, 0);
        store.addCharacter(HOUSE_CHAR, HOUSE, 30_000_000);
        store.addAccount(PLAYER, 0);
        store.addCharacter(PLAYER_CHAR, PLAYER, 0);
        now = new AtomicLong(NOW);
        ExchangeConfig c = cfg();
        engine = new Exchange(store, c, store::online, store.finder(), now::get, Ticker.fromSystem(c));
        engine.recover();
        rock = engine.ticker("E4006001");
        store.addItems(PLAYER_CHAR, rock.asset(), 10);
        store.addItems(HOUSE_CHAR, rock.asset(), 60);   // the maker funds its asks from the house character
        engine.bags((account, character) -> account == PLAYER && character == PLAYER_CHAR ? bag() : null);
        store.login(HOUSE, HOUSE_CHAR);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty(CATALOG);
        System.clearProperty(MAKER);
        System.clearProperty(SITE_ITEMS);
        FairValue.setCurrent(null);
        ExchangeCatalog.reset();
    }

    /** The player's bags as the memory store models them: the store moves the items, so taking only has to check. */
    LiveWallet bag() {
        Asset rockAsset = rock.asset();
        return new LiveWallet() {
            public long account() {
                return PLAYER;
            }

            public long character() {
                return PLAYER_CHAR;
            }

            public boolean offline() {
                return true;
            }

            public <T> T locked(Supplier<T> body) {
                return body.get();
            }

            public boolean usable() {
                return true;
            }

            public boolean saveNow() {
                return true;
            }

            public long have(Asset a) {
                return a == rockAsset ? store.items(PLAYER_CHAR, a) : 0;
            }

            public boolean take(Asset a, long amount) {
                return amount <= have(a);
            }

            public boolean give(Asset a, long amount) {
                return false;
            }

            public ExchangeStore.InventoryWriter inventoryWriter() {
                return con -> {
                };
            }
        };
    }

    Exchange.Place sell(long qty, long price) {
        return new Exchange.Place("s-" + qty + "-" + price, PLAYER, PLAYER_CHAR, Side.SELL, price, qty, rock.id());
    }

    @Test
    void theSiteSellsFromTheOfflineCharactersBags() throws Exception {
        Exchange.Response preview = engine.preview(sell(4, 10_000), true);
        assertTrue(preview.ok(), preview.code() + " " + preview.message());

        Exchange.Response placed = engine.place(sell(4, 10_000));
        assertTrue(placed.ok(), placed.code() + " " + placed.message());
        assertEquals(6, store.items(PLAYER_CHAR, rock.asset()), "the four leaves the bags for the escrow");
        assertEquals(4, engine.myOrders(PLAYER, 5).get(0).nxLeft(), "and rest on the book");

        Exchange.Response tooMany = engine.place(sell(7, 10_000));
        assertFalse(tooMany.ok());
        assertEquals("INSUFFICIENT_FUNDS", tooMany.code());
        assertEquals(6, store.items(PLAYER_CHAR, rock.asset()), "a refused sell takes nothing");
        assertEquals(1, engine.myOrders(PLAYER, 5).size());
    }

    @Test
    void theSwitchClosesTheSiteSellAgain() {
        System.setProperty(SITE_ITEMS, "false");
        Exchange.Response preview = engine.preview(sell(1, 10_000), true);
        assertEquals("LOG_IN_FIRST", preview.code());
        assertEquals("LOG_IN_FIRST", engine.place(sell(1, 10_000)).code());
        assertEquals(10, store.items(PLAYER_CHAR, rock.asset()));
    }

    @Test
    void aFillPaysTheOfflineSellerInTheSamePlace() throws Exception {
        fund(20, rock.asset());
        fund(5_000_000, Asset.MESO);
        seedFairValue(10_000);
        assertTrue(engine.tickRegulars() >= 2, "the maker's bids and asks rest");
        long bid = engine.depth(rock.id(), Side.BUY, 1).get(0).price();
        assertEquals(9_700, bid);

        Exchange.Response placed = engine.place(sell(4, 9_000));
        assertTrue(placed.ok(), placed.code() + " " + placed.message());
        long mesos = 4 * bid;
        assertEquals(mesos - mesos * cfg().feeBps() / 10_000, store.meso(PLAYER_CHAR),
                "the seller's mesos are in its row, not waiting in PAYOUT for a login");
        assertEquals(0, store.balance(Ledger.Bucket.payout(rock.asset(), PLAYER)));
    }

    @Test
    void aDumpSellsIntoTheBidsAtTheirOwnPrices() throws Exception {
        for (int r = 1; r <= cfg().regulars(); r++) {
            fund(r, 20, rock.asset());
            fund(r, 5_000_000, Asset.MESO);
        }
        seedFairValue(10_000);
        engine.tickRegulars();
        store.login(PLAYER, PLAYER_CHAR);

        List<String> lines = ExchangeChat.handle(engine, store.finder().find(PLAYER),
                new String[]{"e4006001", "dump", "7"});
        assertTrue(lines.get(0).startsWith("Dumped"), lines.toString());
        long mesos = 5 * 9_700 + 2 * 9_400;
        assertEquals(mesos - mesos * cfg().feeBps() / 10_000, store.meso(PLAYER_CHAR),
                "five fill at the 9,700 bid, two at 9,400: a market sell reaches the deepest level it needs");
        assertEquals(3, store.items(PLAYER_CHAR, rock.asset()));
        assertEquals("FILLED", engine.myOrders(PLAYER, 5).get(0).status(), "all seven found a bid");
        assertEquals(List.of(9_400L, 9_100L), engine.depth(rock.id(), Side.BUY, 5).stream().map(l -> l.price()).toList(),
                "the two rungs it crossed are gone down to what it left");
    }

    @Test
    void aDumpWithoutBidsPlacesNothing() throws Exception {
        store.login(PLAYER, PLAYER_CHAR);
        List<String> lines = ExchangeChat.handle(engine, store.finder().find(PLAYER),
                new String[]{"e4006001", "dump", "1"});
        assertTrue(lines.get(0).startsWith("No bids"), lines.toString());
        assertEquals(10, store.items(PLAYER_CHAR, rock.asset()));
        assertEquals(0, store.meso(PLAYER_CHAR));
    }

    void fund(long amount, Asset asset) {
        fund(1, amount, asset);
    }

    void fund(int regular, long amount, Asset asset) {
        var r = engine.fundHouseLive(regular, store.finder().find(HOUSE), asset, amount, false);
        assertTrue(r.ok(), r.code() + " " + r.message());
    }

    private void seedFairValue(long fair) {
        FairValue f = new FairValue(0.5, 0.3);
        FairValue.Sources src = new FairValue.Sources() {
            public long floor(String t) {
                return fair;
            }

            public long ceiling(String t, Map<String, Long> fairs) {
                return 0;
            }

            public long anchor(String t) {
                return fair;
            }
        };
        f.maybeUpdate(NOW, new MarketFlows(), src, List.of(rock.id()));
        assertEquals(fair, f.fair(rock.id()));
        FairValue.setCurrent(f);
    }
}
