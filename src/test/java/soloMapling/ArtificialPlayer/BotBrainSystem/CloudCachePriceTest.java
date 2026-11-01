package soloMapling.ArtificialPlayer.BotBrainSystem;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import config.ServerConfig;
import soloMapling.ArtificialPlayer.BotConvo.ConvoContract;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * s-llmcache, owner 2026-10-31 ("we need to also support caching for deepseek"): a prompt prefix DeepSeek has
 * already seen comes back as {@code prompt_cache_hit_tokens} and is billed at the cache-hit price, which is a fraction
 * of the input price. Unset cached price = hits billed like any other input token, i.e. the numbers of 1.6.2-rc.
 */
class CloudCachePriceTest {

    private static final ZoneId ZONE = ZoneId.of("America/Los_Angeles");
    private final AtomicLong now = new AtomicLong(
            LocalDateTime.of(2026, 10, 31, 12, 0).atZone(ZONE).toInstant().toEpochMilli());

    @AfterEach
    void reset() {
        ChatStats.resetForTest();
    }

    private CloudBudget budget(double priceIn, double priceInCached, double priceOut) {
        return new CloudBudget(10.0, priceIn, priceInCached, priceOut, now::get, ZONE, null);
    }

    @Test
    void aHitTokenCostsLessThanTheSameTokenMissing() {
        CloudBudget allMiss = budget(0.28, -1, 1.10);
        CloudBudget mostlyHit = budget(0.28, 0.028, 1.10);
        allMiss.settle(0, 100_000, 0, 0);
        mostlyHit.settle(0, 100_000, 90_000, 0);
        // 10k miss at 0.28 + 90k hit at 0.028 (per million) = 0.0028 + 0.00252
        assertEquals(0.00532, mostlyHit.spentToday(), 1e-12);
        assertTrue(mostlyHit.spentToday() < allMiss.spentToday(), "the same prompt cached costs less");
        assertEquals(90_000, mostlyHit.cacheHitTokens());
        assertEquals(10_000, mostlyHit.cacheMissTokens());
        assertEquals(0.9, mostlyHit.cacheHitRatio(), 1e-12);
    }

    /** Deploying without the price key must not change what a call costs: hits count as ordinary input. */
    @Test
    void anUnsetCachedPriceChargesTodaysNumbers() {
        CloudBudget plain = new CloudBudget(10.0, 0.28, 1.10, now::get, ZONE, null);
        CloudBudget unset = budget(0.28, -1, 1.10);
        plain.settle(0, 100_000, 0, 0);
        unset.settle(0, 100_000, 90_000, 0);
        assertEquals(plain.spentToday(), unset.spentToday(), 1e-12);
        assertEquals(90_000, unset.cacheHitTokens(), "the hit tokens are still counted for the ratio");
    }

    /** The key has to reach the server's one budget, or the owner sets it and the spend never moves. */
    @Test
    void theConfiguredCachedPriceReachesTheServerBudget() {
        ServerConfig plain = new ServerConfig();
        plain.BOT_LLM_CLOUD_BASE_URL = "http://127.0.0.1:9/v1";   // built, never called
        ServerConfig priced = new ServerConfig();
        priced.BOT_LLM_CLOUD_BASE_URL = "http://127.0.0.1:9/v1";
        priced.BOT_LLM_CLOUD_PRICE_IN_PER_MTOK = 1.0;
        priced.BOT_LLM_CLOUD_PRICE_IN_CACHED_PER_MTOK = 0.1;
        try {
            BotBrains.useForTest(null);
            CloudBudget unset = BotBrains.cloudBudget(plain);
            assertEquals(unset.cost(1_000, 0, 0), unset.cost(1_000, 900, 0), 1e-12,
                    "no cached price configured: hits cost the same as a miss");
            BotBrains.useForTest(null);
            CloudBudget cached = BotBrains.cloudBudget(priced);
            assertEquals(900 * 0.1e-6 + 100 * 1e-6, cached.cost(1_000, 900, 0), 1e-12);
        } finally {
            BotBrains.useForTest(null);
        }
    }

    /** The conf line the owner edits must be a key the server's strict YAML loader knows, or boot aborts. */
    @Test
    void theConfKeyForTheCachedPriceIsReadFromYaml() throws Exception {
        ServerConfig parsed = new com.esotericsoftware.yamlbeans.YamlReader(
                "BOT_LLM_CLOUD_PRICE_IN_CACHED_PER_MTOK: 0.03\nBOT_LLM_CLOUD_PRICE_IN_PER_MTOK: 0.30\n")
                .read(ServerConfig.class);
        assertEquals(0.03, parsed.BOT_LLM_CLOUD_PRICE_IN_CACHED_PER_MTOK, 1e-12);
        assertEquals(0.30, parsed.BOT_LLM_CLOUD_PRICE_IN_PER_MTOK, 1e-12);
    }

    @Test
    void theDayFileRemembersTheHitAndMissTotals() throws Exception {
        Path file = Files.createTempDirectory("budget").resolve("cloud-budget.json");
        CloudBudget a = new CloudBudget(10.0, 0.28, 0.028, 1.10, now::get, ZONE, file);
        a.settle(0, 1_000_000, 750_000, 1_000);
        CloudBudget b = new CloudBudget(10.0, 0.28, 0.028, 1.10, now::get, ZONE, file);
        assertEquals(750_000, b.cacheHitTokens());
        assertEquals(250_000, b.cacheMissTokens());
        assertEquals(0.75, b.cacheHitRatio(), 1e-12);
        assertTrue(b.describe().contains("75%"), b.describe());
    }

    @Test
    void aCloudCallIsChargedItsCachedShareAndShowsInTheChatMetrics() {
        ChatRouterTest.FakeLocal local = new ChatRouterTest.FakeLocal(now);
        local.free = 0;
        CloudBudgetChargeTest.MeteredCloud cloud = new CloudBudgetChargeTest.MeteredCloud();
        CloudBudget budget = new CloudBudget(1_000, 1_000_000, 100_000, 1_000_000, now::get, ZONE, null);
        ChatRouter r = new ChatRouter(local, cloud, budget, 2500, now::get, 64);

        CompletableFuture<Optional<ConvoContract.Reply>> call = r.reply(Priority.PLAYER, 1,
                List.of(BotBrain.Turn.system("x".repeat(400)), BotBrain.Turn.user("wanna party?")));
        cloud.pending.get(0).complete(Optional.of(ConvoContract.Reply.plain("ok")),
                new long[]{1_000, 10, 900});
        call.join();

        // prices are per million tokens, so the dollars are the token counts in units of the price: 100 miss + 900
        // hit at a tenth + 10 out = 200 USD at these (absurd, but readable) test prices
        assertEquals(100 + 900 * 0.1 + 10, budget.spentToday(), 1e-9);
        assertEquals(900, budget.cacheHitTokens());
        Map<?, ?> stats = (Map<?, ?>) ChatStats.snapshot().get("cloud");
        assertEquals(900L, stats.get("cacheHitTokens"));
        assertEquals(100L, stats.get("cacheMissTokens"));
        assertEquals(90.0, ((Number) stats.get("cacheHitPercent")).doubleValue(), 1e-9);
        assertTrue(r.usageLine(0.5).contains("cache hit 90%"), r.usageLine(0.5));
    }

    /** A brain that reports no usage at all (the local qwen, or a reply that came from the scripted bank). */
    @Test
    void aCallWithNoUsageBlockHasNoCachedTokens() {
        assertEquals(0, ChatStats.cloudCacheHitPercent(), 1e-12);
        ChatStats.cloudTokens(0, 0);
        assertEquals(0, ChatStats.cloudCacheHitPercent(), 1e-12);
        assertFalse(ChatStats.summaryLine().contains("cache hit"), ChatStats.summaryLine());
    }

    /** The hourly line is what the lane session reads off TEST to compare before and after. */
    @Test
    void theHourlyLineCarriesTheCacheHitShare() {
        ChatStats.cloudTokens(750, 250);
        String line = ChatStats.summaryLine();
        assertTrue(line.contains("cloud calls=0"), line);
        assertTrue(line.contains("cache hit=75% of 1000 in tokens"), line);
    }
}
