package soloMapling.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotClientHandler;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotShopping;
import soloMapling.Environment.EnvironmentManager;
import soloMapling.itemPool.DesirableEquipList;
import soloMapling.itemPool.EquipMetadataCache;

/** Explicit startup controls for the MapleRoot integration. Disabled by default. */
public final class MapleRootSolo {
    private static final Logger log = LoggerFactory.getLogger(MapleRootSolo.class);
    private MapleRootSolo() {}

    public static boolean isEnabled() {
        return Boolean.getBoolean("solomapling.enabled") && templateIdOrZero() > 0;
    }
    private static int templateIdOrZero() {
        return Integer.getInteger("solomapling.template-id", 0);
    }
    public static int templateId() {
        if (!isEnabled()) throw new IllegalStateException("SoloMapling needs an explicit dedicated bot template ID");
        return templateIdOrZero();
    }
    /** Bot observers must not interrupt the underlying player action. */
    public static void runHook(String name, Runnable action) {
        if (!isEnabled()) return;
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("SoloMapling hook {} failed; player action continues", name, e);
        }
    }

    public static void start() {
        if (!isEnabled()) {
            log.info("SoloMapling disabled; enable explicitly with a dedicated bot template ID before testing");
            return;
        }
        soloMapling.DataFiles.checkAtStartup(); // ticket 22: logs each missing data file and one summary line
        EquipMetadataCache.initialize();
        DesirableEquipList.load();
        BotClientHandler.initHeadlessBotClient();
        BotShopping.install();   // real bots 09: town stops buy potions and ammo at NPC shops
        soloMapling.ArtificialPlayer.SocialGate.startAtBoot(); // 1.6.0 S5: @quiet, @romance off, started-it ledger
        // 1.6.0 S9 -> S7: a bot crossing into another Bartle quadrant drives its villain/redemption arc (VillainArc)
        soloMapling.ArtificialPlayer.BotLifeSystem.LifeLearning.onCrossing(e ->
                soloMapling.ArtificialPlayer.BotDramaSystem.VillainArc.LISTENER.crossed(e.botId(), e.crossing().from(),
                        e.crossing().to(), e.seed(), e.crossing().at()));
        log.info("SoloMapling initialized; automatic population: {}", Boolean.getBoolean("solomapling.autopopulate"));
        if (Boolean.getBoolean("solomapling.autopopulate")) {
            MethodScheduler.runAfterDelay(EnvironmentManager::environmentLoadStartup, 1000);
        }
    }
}
