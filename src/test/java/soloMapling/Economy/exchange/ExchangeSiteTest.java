package soloMapling.Economy.exchange;

import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.OrderBook.Side;
import soloMapling.server.Json;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ticket 113: the site's request/response contract. */
class ExchangeSiteTest {

    static final long NOW = 1_800_000_000_000L;
    static final String T = java.time.Instant.ofEpochMilli(NOW - 5000).toString();

    @Test
    void parsesEveryRequestTypeAndFlagsBadRows() {
        String json = "{\"schema\":1,\"requests\":["
                + "{\"id\":\"a\",\"account\":1,\"type\":\"place\",\"character\":11,\"side\":\"SELL\",\"price\":1000000,\"nx\":1000,\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"b\",\"account\":\"1\",\"type\":\"cancel\",\"orderId\":7,\"createdAt\":" + NOW + "},"
                + "{\"id\":\"c\",\"account\":1,\"type\":\"mine\",\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"d\",\"account\":1,\"type\":\"book\",\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"e\",\"account\":1,\"type\":\"trades\",\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"f\",\"account\":1,\"type\":\"place\",\"character\":11,\"side\":\"HOLD\",\"price\":1,\"nx\":1,\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"g\",\"account\":1,\"type\":\"launder\",\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"h\",\"type\":\"book\",\"createdAt\":\"" + T + "\"},"
                + "{\"account\":1,\"type\":\"book\"}, 5]}";
        List<ExchangeSite.Request> r = ExchangeSite.requests(json);
        assertEquals(8, r.size(), "rows without an id cannot be answered and are skipped");
        assertEquals(ExchangeSite.Type.PLACE, r.get(0).type());
        assertEquals(Side.SELL, r.get(0).side());
        assertEquals(1000, r.get(0).nx());
        assertNull(r.get(0).error());
        assertEquals(7, r.get(1).orderId());
        assertEquals(NOW, r.get(1).createdAt());
        assertEquals("side", r.get(5).error());
        assertEquals("type", r.get(6).error());
        assertEquals("account", r.get(7).error());
    }

    @Test
    void answersAgainstTheExchange() throws Exception {
        MemoryExchangeStore store = new MemoryExchangeStore();
        store.addAccount(1, 10_000);
        store.addCharacter(11, 1, 10_000_000);
        store.addAccount(2, 0);
        store.addCharacter(12, 2, 10_000_000);
        Exchange ex = new Exchange(store, ExchangeTest.open(), store::online, () -> NOW);
        ex.recover();
        String json = "[{\"id\":\"a\",\"account\":1,\"type\":\"place\",\"character\":11,\"side\":\"SELL\",\"price\":1000000,\"nx\":1000,\"createdAt\":\"" + T + "\"},"
                + "{\"id\":\"old\",\"account\":2,\"type\":\"preview\",\"character\":12,\"side\":\"BUY\",\"price\":1000000,\"nx\":500,\"createdAt\":\"2020-01-01T00:00:00Z\"}]";
        List<ExchangeSite.Request> reqs = ExchangeSite.requests(json);
        assertEquals("BAD_REQUEST", ExchangeSite.answer(ex, reqs.get(0), NOW).code(), "a bare place needs a confirm now");
        Exchange.Response a = viaSite(ex, "a", 1, 11, "SELL", 1_000_000, 1000);
        Exchange.Response b = viaSite(ex, "b", 2, 12, "BUY", 1_000_000, 500);
        Exchange.Response old = ExchangeSite.answer(ex, reqs.get(1), NOW);
        assertTrue(a.ok() && b.ok(), a + " " + b);
        assertEquals("STALE", old.code());
        assertEquals(1, ex.lastTrades(10).size(), "the stale order was not placed");

        ExchangeSite.Request mine = new ExchangeSite.Request("m", 1, ExchangeSite.Type.MINE, 0, null, 0, 0, 0, NOW, null);
        Exchange.Response m = ExchangeSite.answer(ex, mine, NOW);
        List<?> orders = (List<?>) m.data().get("orders");
        assertEquals(1, orders.size());
        assertEquals(500L, ((Map<?, ?>) orders.get(0)).get("nxLeft"));

        ExchangeSite.Request cancel = new ExchangeSite.Request("c", 1, ExchangeSite.Type.CANCEL, 0, null, 0, 0,
                ((Number) a.data().get("orderId")).longValue(), NOW, null);
        assertTrue(ExchangeSite.answer(ex, cancel, NOW).ok());
        assertEquals("NOT_FOUND", ExchangeSite.answer(ex, cancel, NOW).code());

        Map<String, Object> book = ExchangeSite.book(ex, NOW);
        String text = Json.write(book);
        assertFalse(text.contains("account"), "the public book names no accounts");
        assertEquals(1, ((List<?>) book.get("trades")).size());
        assertEquals(1_000_000L, ((Map<?, ?>) book.get("rate")).get("mesos_per_1000nx"));

        Map<String, Object> resp = ExchangeSite.responsesFile(List.of(ExchangeSite.responseJson(a, 1)), NOW);
        Map<?, ?> row = (Map<?, ?>) ((List<?>) resp.get("responses")).get(0);
        assertEquals("a", row.get("id"));
        assertEquals("OK", row.get("code"));
        assertEquals(1, resp.get("schema"));

        Exchange.Response bad = ExchangeSite.answer(ex, ExchangeSite.requests(
                "[{\"id\":\"z\",\"account\":1,\"type\":\"cancel\",\"createdAt\":\"" + T + "\"}]").get(0), NOW);
        assertEquals("BAD_REQUEST", bad.code());
    }

    /** The site's place: preview, then confirm with the token. */
    static Exchange.Response viaSite(Exchange ex, String id, long account, long character, String side, long price,
                                     long nx) {
        Exchange.Response p = ExchangeSite.answer(ex, ExchangeSite.requests("[{\"id\":\"" + id + "-p\",\"account\":"
                + account + ",\"type\":\"preview\",\"character\":" + character + ",\"side\":\"" + side
                + "\",\"price\":" + price + ",\"nx\":" + nx + ",\"createdAt\":\"" + T + "\"}]").get(0), NOW);
        if (!p.ok()) {
            return p;
        }
        return ExchangeSite.answer(ex, ExchangeSite.requests("[{\"id\":\"" + id + "\",\"account\":" + account
                + ",\"type\":\"confirm\",\"token\":\"" + p.data().get("token") + "\",\"createdAt\":\"" + T
                + "\"}]").get(0), NOW);
    }

    @Test
    void theRunnerAnswersEachRequestOnceAndWritesTheOutbox(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        MemoryExchangeStore store = new MemoryExchangeStore();
        store.addAccount(1, 10_000);
        store.addCharacter(11, 1, 10_000_000);
        Exchange ex = new Exchange(store, ExchangeTest.open(), store::online, () -> NOW);
        ex.recover();
        java.nio.file.Path in = dir.resolve("in"), out = dir.resolve("out");
        java.nio.file.Files.createDirectories(in);
        String preview = "{\"id\":\"p\",\"account\":1,\"type\":\"preview\",\"character\":11,\"side\":\"SELL\",\"price\":1000000,\"nx\":1000,\"createdAt\":\"" + T + "\"}";
        java.nio.file.Files.writeString(in.resolve(ExchangeSite.REQUESTS), "[" + preview + "]");
        ExchangeService.Runner r = new ExchangeService.Runner(ex, in, out, () -> NOW);
        r.tick();
        assertEquals(0, ex.openOrders(), "a preview places nothing");
        Map<?, ?> resp = (Map<?, ?>) Json.parse(java.nio.file.Files.readString(out.resolve(ExchangeSite.RESPONSES)));
        String token = (String) ((Map<?, ?>) ((Map<?, ?>) ((List<?>) resp.get("responses")).get(0)).get("data")).get("token");
        String confirm = "{\"id\":\"a\",\"account\":1,\"type\":\"confirm\",\"token\":\"" + token + "\",\"createdAt\":\"" + T + "\"}";
        java.nio.file.Files.writeString(in.resolve(ExchangeSite.REQUESTS), "[" + preview + "," + confirm + "]");
        java.nio.file.Files.setLastModifiedTime(in.resolve(ExchangeSite.REQUESTS),
                java.nio.file.attribute.FileTime.fromMillis(NOW + 1));
        r.tick();
        java.nio.file.Files.setLastModifiedTime(in.resolve(ExchangeSite.REQUESTS),
                java.nio.file.attribute.FileTime.fromMillis(NOW + 2));
        r.tick(); // same requests again: not placed twice
        assertEquals(1, ex.openOrders());
        assertEquals(10_000 - 1000, store.nx(1));
        resp = (Map<?, ?>) Json.parse(java.nio.file.Files.readString(out.resolve(ExchangeSite.RESPONSES)));
        assertEquals(2, ((List<?>) resp.get("responses")).size());
        assertTrue(java.nio.file.Files.exists(out.resolve(ExchangeSite.BOOK)));
    }
}
