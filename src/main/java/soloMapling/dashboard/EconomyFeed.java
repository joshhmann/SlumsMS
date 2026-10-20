package soloMapling.dashboard;

import java.util.List;
import java.util.Map;

/**
 * Where the dashboard's economy panel gets bot intake from. This is the adapter point for ticket 15: until its
 * ledger is merged, {@link DerivedEconomy} estimates intake from the bots' meso balances; once
 * {@code BotYieldCaps} is in, an implementation that reads its meters and daily counts is installed with
 * {@link DashboardExporter#useEconomyFeed} and the panel shows real intake against the caps. Every method is
 * called on the exporter's own thread, once per snapshot, and must not block.
 */
public interface EconomyFeed {

    /** One map's intake today. A cap of -1 means none or unknown; items -1 means not counted. */
    record MapIntake(int mapId, long mesos, long items, long mesoCap, long itemCap, long refused) {
    }

    /** One bot's intake today. A cap of -1 means none or unknown. */
    record BotIntake(int charId, String name, int level, long mesos, long cap, long refused) {
    }

    /** Short name of the source, shown on the panel: "derived" or "ledger". */
    String source();

    /** Called once per snapshot before the reads, with the online bots, so a feed can keep its own counts. */
    default void observe(List<LiveSnapshot.BotSample> bots, long now) {
    }

    /** Maps with intake today, any order. */
    List<MapIntake> mapsToday();

    /** Bots with intake today, any order. */
    List<BotIntake> botsToday();

    /** The caps in force, as label to value, for the panel's header (empty when there are none). */
    default Map<String, Object> caps() {
        return Map.of();
    }
}
