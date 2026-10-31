package soloMapling.qa;

import client.Character;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 1.5.0 rc3: the economy lane's tr-* scenarios stage, step and clean up through their worlds. */
class QaTradeScenarioTest {
    @TempDir
    Path dir;

    final FakeQaWorld world = new FakeQaWorld();
    final FakeTrade trade = new FakeTrade();
    final QaTestSupport.ManualScheduler scheduler = new QaTestSupport.ManualScheduler();
    QaSuite suite;

    @BeforeEach
    void setUp() {
        QaTradeScenarios.trade = trade;
    }

    @AfterEach
    void reset() {
        QaTradeScenarios.trade = new GameQaTradeWorld();
        QaLog.reset();
    }

    private Character start(String id, int mapId, String... args) {
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", mapId);
        world.online = gm;
        suite.start(gm, id, args);
        return gm;
    }

    @Test
    void theEconomyScenariosAreRegistered() {
        List<String> ids = new ArrayList<>();
        for (QaScenario s : QaScenarios.all()) {
            ids.add(s.id());
        }
        for (String id : List.of("tr-1", "tr-2", "tr-3", "tr-4", "tr-5", "tr-6", "tr-7", "tr-8", "tr-9")) {
            assertTrue(ids.contains(id), id);
        }
    }

    @Test
    void tr1OpensABotShopInTheFmRoomAndClosesItAtStop() throws Exception {
        Character gm = start("tr-1", 100000000, "2");
        assertTrue(world.calls.contains("warp 910000002"));
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("spawn idle")));
        assertTrue(trade.calls.contains("open"));
        List<String> step = suite.action(gm, "collect", new String[0]);
        assertTrue(step.get(0).startsWith("collected"), step.toString());
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertTrue(trade.calls.contains("close"));
        String text = Files.readString(log);
        assertTrue(text.contains("2049100"), "the chaos scroll was given, to be kept back");
    }

    @Test
    void tr1RefusesOffChannelOne() {
        world.eventChannel = false;
        suite = QaTestSupport.suite(QaScenarios.all(), world, dir, scheduler);
        Character gm = QaTestSupport.gm(1, "Admin", 910000001);
        world.online = gm;
        assertThrows(RuntimeException.class, () -> suite.start(gm, "tr-1", new String[0]));
    }

    @Test
    void tr4StagesAFriendMakesGiftsCertainAndPutsItBack() {
        Character gm = start("tr-4", 100000000);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage friend social")));
        assertTrue(trade.certain);
        assertEquals("gift potions", suite.action(gm, "gift", new String[]{"potions"}).get(0));
        suite.stop(1, "!qa stop");
        assertFalse(trade.certain);
        assertFalse(world.unstaged.isEmpty());
    }

    @Test
    void tr3StagesASellerThatHearsChatAndUnstagesItAtStop() throws Exception {
        // 1.6.2 (t-td-2): an idle spawn has no bot brain, so chat routing took it for a player and it never answered
        Character gm = start("tr-3", 910000001);
        assertTrue(world.calls.stream().anyMatch(c -> c.startsWith("stage neutral social ")), world.calls.toString());
        assertFalse(world.calls.stream().anyMatch(c -> c.startsWith("spawn idle")), world.calls.toString());
        suite.stop(1, "!qa stop");
        assertEquals(1, world.unstaged.size(), "the seller goes at stop");
    }

    @Test
    void tr4GivesTheBotEnoughThatAMesoGiftAlwaysFits() {
        // rc6 triage (t-gf-1): a meso gift keeps half the bot's wealth ceiling; a flat 5m left high-ceiling bots short
        assertEquals(5_000_000, QaTradeScenarios.giftPurse(2_000_000));
        assertEquals(31_000_000, QaTradeScenarios.giftPurse(60_000_000));
        Character gm = start("tr-4", 100000000);
        assertTrue(world.calls.stream().anyMatch(c -> c.contains("meso 5000000")), world.calls.toString());
        suite.stop(1, "!qa stop");
    }

    @Test
    void tr4StagesAScrollCheapEnoughToGive() {
        // t-gf-1 (a917ef1c9): weapon attack scrolls got an item-pool price (2043001 = 1,300,000), so the one 60% scroll
        // tr-4 put in the bot's bag was over gifts.scrollMaxValue and "!qa do gift scroll" had nothing to give
        long cap = soloMapling.Economy.EconomyConfig.getLong("gifts.scrollMaxValue", 500_000);
        double band = soloMapling.Economy.EconomyConfig.getDouble("priceBook.bandHigh", 4.0);
        start("tr-4", 100000000);
        List<String> giftable = new ArrayList<>();
        for (String c : world.calls) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("give (\\d{7}) x").matcher(c);
            if (!m.find()) {
                continue;
            }
            int id = Integer.parseInt(m.group(1));
            Integer price = soloMapling.itemPool.ItemDatabase.getInstance().getItemPrice(id);
            if (soloMapling.ArtificialPlayer.BotScrollSystem.ScrollRules.isPlainScroll(id) && price != null
                    && price * band <= cap) {
                giftable.add(c);
            }
        }
        assertFalse(giftable.isEmpty(), "no staged scroll is worth at most " + cap + ": " + world.calls);
        suite.stop(1, "!qa stop");
    }

    @Test
    void tr6CountsAnswersOutsideTheBand() throws Exception {
        start("tr-6", 100000000, "2m");
        assertEquals(QaTradeScenarios.HAGGLE_LINES.size(), trade.haggled.size());
        assertEquals(2_000_000L, trade.fair);
        Path log = suite.runOf(1).logPath();
        suite.stop(1, "!qa stop");
        assertTrue(Files.readString(log).contains("0 answers outside the band"));
    }

    @Test
    void tr7TakesBackTheMesosItGave() {
        world.meso = 1_000;
        start("tr-7", 100000000);
        assertTrue(world.calls.contains("warp 910000000"));
        assertEquals(50_001_000, world.meso);
        suite.stop(1, "!qa stop");
        assertEquals(1_000, world.meso);
    }

    static final class FakeTrade implements QaTradeWorld {
        final List<String> calls = new ArrayList<>();
        final List<String> haggled = new ArrayList<>();
        boolean certain;
        long fair;

        public List<String> openBotShop(int botId, Character gm) {
            calls.add("open");
            return new ArrayList<>(List.of("Bot opened a shop in FM room 2 (4 items, closes in 6 h)"));
        }

        public String closeBotShop(int botId) {
            calls.add("close");
            return "closed";
        }

        public String collectBotShop(int botId) {
            return "collected 1,000";
        }

        public String gift(int botId, Character gm, String kind) {
            return "gift " + kind;
        }

        public void certainGifts(boolean on) {
            certain = on;
        }

        public void forgetGift(int botId, int playerId) {
            calls.add("forget");
        }

        public String quote(int botId, Character gm, String words) {
            return "quote " + words;
        }

        public List<String> haggle(long fair, String line) {
            this.fair = fair;
            haggled.add(line);
            return List.of("  bot buys ... (inside the band)");
        }

        public List<String> price(int itemId) {
            return List.of("price " + itemId);
        }

        public List<String> tax(long price) {
            return List.of("tax " + price, "today");
        }

        public String meters() {
            return "meters";
        }

        public List<String> simulateSale(int botId, int itemId, int quantity, List<String> lines) {
            return List.of("sim");
        }
    }
}
