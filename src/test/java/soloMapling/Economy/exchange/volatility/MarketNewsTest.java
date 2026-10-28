package soloMapling.Economy.exchange.volatility;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import soloMapling.Economy.exchange.volatility.MarketNews.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.6.1 volatility: guild wars and guild prep as market news: roles, pressure, Cody, the site's banner. */
class MarketNewsTest {

    final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    final List<String> notices = new ArrayList<>();

    @AfterEach
    void tearDown() {
        MarketNews.useForTest(null);
        MarketEvents.useForTest(null);
    }

    @Test
    void aWarMakesBothGuildsStockUpThenWinnersAndLosers() {
        MarketNews n = MarketNews.forTest(now::get, notices::add, true);
        n.declared(7, 11, "Hawks", 22, "Owls", now.get() + 7 * 86_400_000L);
        assertEquals(Role.AT_WAR_FRESH, n.role(11, now.get()));
        assertEquals(Role.AT_WAR_FRESH, n.role(22, now.get()));
        assertEquals(Role.NONE, n.role(33, now.get()));
        assertEquals(Role.NONE, n.role(0, now.get()), "no guild");
        assertEquals(1.3, n.pressure("ELIXIR", now.get()), 1e-9, "a fresh declaration + a running war");
        assertEquals(0, n.pressure("LEAF", now.get()));
        assertTrue(notices.get(0).contains("Hawks declared war on Owls"), notices.toString());
        now.addAndGet(3 * 3_600_000L);
        assertEquals(Role.AT_WAR, n.role(11, now.get()), "steady after 2 h");
        assertEquals(0.3, n.pressure("ELIXIR", now.get()), 1e-9, "steady demand while it lasts");
        n.ended(7, 22);
        assertEquals(Role.WINNER, n.role(22, now.get()));
        assertEquals(Role.LOSER, n.role(11, now.get()));
        assertTrue(n.pressure("ELIXIR", now.get()) < 0, "the elixir buyers stand down");
        assertTrue(notices.get(1).contains("Owls won the war against Hawks"), notices.toString());
        now.addAndGet(6 * 3_600_000L);
        assertEquals(Role.NONE, n.role(22, now.get()), "the news is old after 6 h");
    }

    @Test
    void aGuildGearingUpForZakumIsAnElixirShockSizedByTheContent() {
        MarketNews n = MarketNews.forTest(now::get, notices::add, true);
        n.prep(5, "Hawks", "zakum", "zakum", 6);
        assertEquals(Role.PREP, n.role(5, now.get()));
        assertEquals(1.0, n.pressure("ELIXIR", now.get()), 1e-9);
        n.prep(6, "Owls", "gpq", "guild pq", 3);
        assertEquals(1.2, n.pressure("ELIXIR", now.get()), 1e-9, "a half-crew guild PQ adds 0.2");
        assertTrue(notices.get(0).contains("Hawks is gearing up for zakum"));
        assertEquals(1, notices.size(), "Cody: one market notice per 10 minutes");
        assertTrue(MarketNews.contentWeight("horntail") > MarketNews.contentWeight("zakum"));
        n.finished(5, true);
        assertEquals(Role.CLEARED, n.role(5, now.get()));
        assertTrue(n.pressure("CHAOS", now.get()) < 0 && n.pressure("WS", now.get()) < 0, "a clear floods scrolls");
        n.finished(6, false);
        assertEquals(Role.WIPED, n.role(6, now.get()));
        assertEquals(0.1, n.pressure("ELIXIR", now.get()), 1e-9, "a wipe: more elixirs for the retry");
        assertEquals(null, n.finished(9, true), "no prep, no news");
    }

    @Test
    void onlyAnnouncedNewsAndEventsReachTheSiteBanner() {
        MarketNews quiet = MarketNews.forTest(now::get, notices::add, false);
        quiet.declared(1, 1, "A", 2, "B", 0);
        assertTrue(quiet.announcedEvents(now.get()).isEmpty(), "not announced: not on the banner");
        assertTrue(notices.isEmpty());
        MarketNews n = MarketNews.forTest(now::get, notices::add, true);
        MarketNews.useForTest(n);
        n.declared(8, 1, "Hawks", 2, "Owls", now.get() + 3_600_000L);
        MarketEvents ev = MarketEvents.forTest(MarketConfig.allOn(), now::get, notices::add);
        MarketEvents.useForTest(ev);
        ev.start(MarketEvents.Kind.LEAF_BLIGHT, 0, false, now.get());   // secret
        ev.start(MarketEvents.Kind.ROOTY_BONUS, 0, true, now.get());
        List<Map<String, Object>> banner = new ArrayList<>(MarketEvents.announcedNow(now.get()));
        banner.addAll(MarketNews.announcedNow(now.get()));
        assertEquals(2, banner.size(), banner.toString());
        assertEquals("Rooty's Bonus", banner.get(0).get("name"));
        assertEquals("LEAF", banner.get(0).get("ticker"));
        assertEquals((now.get() + 24 * 3_600_000L) / 1000, banner.get(0).get("ends_at"), "unix seconds");
        assertEquals("Guild war: Hawks vs Owls", banner.get(1).get("name"));
        assertEquals("ELIXIR", banner.get(1).get("ticker"));
        assertEquals("war-8", banner.get(1).get("id"));
        assertTrue(banner.stream().noneMatch(m -> String.valueOf(m.get("name")).contains("Blight")), "secret: never");
    }
}
