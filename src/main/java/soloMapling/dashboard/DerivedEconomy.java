package soloMapling.dashboard;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The economy feed until ticket 15's ledger is merged: intake estimated from the online bots' meso balances. Each
 * snapshot, a bot's rise in mesos since the last one counts as intake on the map it stands on now, and a fall as
 * spending; a bot that just logged in starts a new baseline. Trades and shop sales show up as intake too, and
 * items are not counted, so it is a rough picture; the counts restart with the server and each local day.
 */
final class DerivedEconomy implements EconomyFeed {

    private final ZoneId zone;
    private LocalDate day;
    private final Map<Integer, Long> lastMeso = new HashMap<>();
    private final Map<Integer, long[]> byMap = new HashMap<>();      // mapId -> {gained, spent}
    private final Map<Integer, BotIntake> byBot = new HashMap<>();

    DerivedEconomy(ZoneId zone) {
        this.zone = zone;
    }

    @Override
    public String source() {
        return "derived";
    }

    @Override
    public synchronized void observe(List<LiveSnapshot.BotSample> bots, long now) {
        LocalDate today = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone);
        if (!today.equals(day)) {
            day = today;
            byMap.clear();
            byBot.clear();
        }
        Set<Integer> online = new HashSet<>();
        for (LiveSnapshot.BotSample b : bots) {
            online.add(b.id());
            Long before = lastMeso.put(b.id(), b.meso());
            if (before == null) {
                continue; // just logged in: a baseline, not intake
            }
            long delta = b.meso() - before;
            if (delta == 0) {
                continue;
            }
            long[] m = byMap.computeIfAbsent(b.mapId(), k -> new long[2]);
            if (delta > 0) {
                m[0] += delta;
                BotIntake old = byBot.get(b.id());
                byBot.put(b.id(), new BotIntake(b.id(), b.name(), b.level(), (old == null ? 0 : old.mesos()) + delta, -1, 0));
            } else {
                m[1] -= delta;
            }
        }
        lastMeso.keySet().retainAll(online);
    }

    @Override
    public synchronized List<MapIntake> mapsToday() {
        List<MapIntake> out = new ArrayList<>();
        byMap.forEach((mapId, m) -> out.add(new MapIntake(mapId, m[0], -1, -1, -1, 0)));
        return out;
    }

    /** Mesos the online bots spent today, per map (shops, fees). */
    synchronized Map<Integer, Long> spentToday() {
        Map<Integer, Long> out = new HashMap<>();
        byMap.forEach((mapId, m) -> {
            if (m[1] > 0) {
                out.put(mapId, m[1]);
            }
        });
        return out;
    }

    @Override
    public synchronized List<BotIntake> botsToday() {
        return new ArrayList<>(byBot.values());
    }
}
