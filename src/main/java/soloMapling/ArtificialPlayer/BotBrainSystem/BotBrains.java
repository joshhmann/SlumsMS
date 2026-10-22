package soloMapling.ArtificialPlayer.BotBrainSystem;

import config.ServerConfig;
import config.YamlConfig;

import java.util.Map;

/**
 * The brain the server's bots use, built once from {@code config.yaml}: an {@link OpenAICompatibleBrain} when
 * {@code BOT_LLM_BASE_URL} is set, the scripted fallback otherwise.
 */
public final class BotBrains {

    private static volatile BotBrain brain;

    private BotBrains() {
    }

    public static BotBrain get() {
        BotBrain current = brain;
        if (current == null) {
            synchronized (BotBrains.class) {
                if (brain == null) {
                    brain = fromConfig(YamlConfig.config.server);
                }
                current = brain;
            }
        }
        return current;
    }

    /** The brain if it was already built, without building it (the dashboard must not trigger a config read). */
    static BotBrain peek() {
        return brain;
    }

    /** Test seam: a stand-in brain (null rebuilds from config on next use). */
    public static void useForTest(BotBrain testBrain) {
        brain = testBrain;
    }

    static BotBrain fromConfig(ServerConfig server) {
        if (server == null || server.BOT_LLM_BASE_URL == null || server.BOT_LLM_BASE_URL.isBlank()) {
            return BotBrain.SCRIPTED;
        }
        OpenAICompatibleBrain local = localFrom(server);
        // Ticket 59: the router - local first, the cloud for players' overflow only, inside the daily budget
        OpenAICompatibleBrain cloud = cloudFrom(server);
        CloudBudget budget = cloud == null ? null : new CloudBudget(server.BOT_LLM_CLOUD_DAILY_BUDGET_USD,
                server.BOT_LLM_CLOUD_PRICE_IN_PER_MTOK, server.BOT_LLM_CLOUD_PRICE_OUT_PER_MTOK, System::currentTimeMillis,
                java.time.ZoneId.systemDefault(), java.nio.file.Path.of(
                        soloMapling.ArtificialPlayer.BotConvo.ConvoSwitches.str("dataDir", "data/convo"), "cloud-budget.json"));
        return new ChatRouter(local, cloud, budget, server.BOT_LLM_LOCAL_SLOW_MS, System::currentTimeMillis,
                server.BOT_LLM_MAX_TOKENS);
    }

    /** The cloud overflow brain, or null when no cloud URL is configured. Its key: config, else the env. */
    static OpenAICompatibleBrain cloudFrom(ServerConfig server) {
        if (server.BOT_LLM_CLOUD_BASE_URL == null || server.BOT_LLM_CLOUD_BASE_URL.isBlank()) {
            return null;
        }
        String key = orEmpty(server.BOT_LLM_CLOUD_API_KEY);
        if (key.isEmpty()) {
            String env = System.getenv("SOLOMAPLING_LLM_CLOUD_API_KEY");
            key = env == null ? "" : env.trim();
        }
        return new OpenAICompatibleBrain(new OpenAICompatibleBrain.Settings(
                server.BOT_LLM_CLOUD_BASE_URL.trim(), key, orEmpty(server.BOT_LLM_CLOUD_MODEL), server.BOT_LLM_MAX_TOKENS,
                server.BOT_LLM_TEMPERATURE, server.BOT_LLM_CLOUD_TIMEOUT_MS, 4, server.BOT_LLM_PLAYER_MIN_INTERVAL_MS,
                Map.of(), Map.of(), ""));
    }

    static OpenAICompatibleBrain localFrom(ServerConfig server) {
        return new OpenAICompatibleBrain(new OpenAICompatibleBrain.Settings(
                server.BOT_LLM_BASE_URL.trim(),
                orEmpty(server.BOT_LLM_API_KEY),
                orEmpty(server.BOT_LLM_MODEL),
                server.BOT_LLM_MAX_TOKENS,
                server.BOT_LLM_TEMPERATURE,
                server.BOT_LLM_TIMEOUT_MS,
                server.BOT_LLM_MAX_CONCURRENT,
                server.BOT_LLM_PLAYER_MIN_INTERVAL_MS,
                orEmpty(server.BOT_LLM_EXTRA_BODY),
                orEmpty(server.BOT_LLM_EXTRA_HEADERS),
                orEmpty(server.BOT_LLM_NOTES_MODEL)));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static Map<String, String> orEmpty(Map<String, String> value) {
        return value == null ? Map.of() : value;
    }
}
