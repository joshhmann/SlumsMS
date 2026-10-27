package soloMapling.site;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 1.6.0 Track D: {@code world.json}, the site's "live world" page. Pure: {@link PublicSiteExport} counts the channels
 * and reads the events off the game thread and hands them in. Bots and players are counted together, no split.
 */
public final class SiteWorld {

    public static final int BUSIEST = 10;

    private SiteWorld() {
    }

    /** Characters online now: per channel, per map id (all channels together), and the maps' names. */
    public record Online(Map<Integer, Integer> perChannel, Map<Integer, Integer> perMap, Map<Integer, String> mapNames) {
    }

    /**
     * The next evening headline. {@code name} null when it is not picked yet; {@code map} the planned map's name (null
     * when unknown, or hidden: a treasure hunt); {@code status} as in the file.
     */
    public record Headline(String name, long startAt, String status, String map) {
        public Headline(String name, long startAt, String status) {
            this(name, startAt, status, null);
        }

        Headline withMap(String m) {
            return new Headline(name, startAt, status, m);
        }
    }

    /** A running world event. {@code mapId} 0 and {@code where} a region while a treasure hunt hides its map. */
    public record Running(String name, String kind, int mapId, String where, int channel, long startedAt, long endsAt,
                          boolean headline) {
    }

    /**
     * The next headline from the event director's plan. {@code planned}: today's plan exists (its day is today);
     * {@code template}: its template's display name or null; {@code status}: the plan's status ("" pending,
     * "started", "missed", "failed", "none"); {@code running}: a headline event runs now. {@code todayAt} and
     * {@code tomorrowAt}: the headline time (8 PM in the server's zone) today and tomorrow. Pure.
     */
    static Headline nextHeadline(boolean planned, String template, long plannedAt, String status, boolean running,
                                 long todayAt, long tomorrowAt, long graceMs, long now) {
        if (planned && template != null && !template.isBlank()) {
            if (running && "started".equals(status)) {
                return new Headline(template, plannedAt, "running");
            }
            if (status == null || status.isEmpty()) {
                if (now <= plannedAt + graceMs) {
                    return new Headline(template, plannedAt, "scheduled");
                }
            }
        }
        if (now < todayAt && !(planned && status != null && !status.isEmpty())) {
            return new Headline(null, todayAt, "scheduled");
        }
        return new Headline(null, tomorrowAt, "scheduled");
    }

    public static Map<String, Object> file(Online online, Headline headline, List<Running> running, long now) {
        return file(online, headline, running, now, List.of(), List.of());
    }

    /** {@code topMobs}: the most killed mobs of the last 24 h, {@code topMobs7d} of the last 7 days (see {@link SiteMobKills}). */
    public static Map<String, Object> file(Online online, Headline headline, List<Running> running, long now,
                                           List<SiteMobKills.Row> topMobs, List<SiteMobKills.Row> topMobs7d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", PublicSiteExport.SCHEMA);
        m.put("generated_at", Instant.ofEpochMilli(now).toString());
        int total = 0;
        List<Object> channels = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : new TreeMap<>(online.perChannel()).entrySet()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("channel", e.getKey());
            c.put("online", e.getValue());
            channels.add(c);
            total += e.getValue();
        }
        m.put("online", total);
        m.put("channels", channels);

        List<Map.Entry<Integer, Integer>> maps = new ArrayList<>(online.perMap().entrySet());
        maps.sort(Map.Entry.<Integer, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
        List<Object> busiest = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : maps.subList(0, Math.min(BUSIEST, maps.size()))) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("map", e.getKey());
            String name = online.mapNames().get(e.getKey());
            r.put("name", name == null ? "" : name);
            r.put("count", e.getValue());
            busiest.add(r);
        }
        m.put("busiest_maps", busiest);
        m.put("top_mobs", mobRows(topMobs));
        m.put("top_mobs_7d", mobRows(topMobs7d));

        if (headline == null) {
            m.put("tonight", null);
        } else {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", headline.name());
            h.put("start", Instant.ofEpochMilli(headline.startAt()).toString());
            if (headline.map() != null && !headline.map().isBlank()) {
                h.put("map", headline.map());
            }
            h.put("status", headline.status());
            m.put("tonight", h);
        }

        List<Running> sorted = new ArrayList<>(running);
        sorted.sort(Comparator.comparingLong(Running::startedAt));
        List<Object> events = new ArrayList<>();
        for (Running r : sorted) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.name());
            if (r.where() != null && !r.where().isBlank()) {
                e.put("map", r.where()); // the map's name; a treasure hunt shows its region only
            }
            if (r.mapId() > 0) {
                e.put("map_id", r.mapId());
            }
            if (r.channel() > 0) {
                e.put("channel", r.channel());
            }
            e.put("endsAt", Instant.ofEpochMilli(r.endsAt()).toString());
            e.put("kind", r.kind());
            e.put("startedAt", Instant.ofEpochMilli(r.startedAt()).toString());
            e.put("headline", r.headline());
            events.add(e);
        }
        m.put("running", events);
        return m;
    }

    private static List<Object> mobRows(List<SiteMobKills.Row> rows) {
        List<Object> out = new ArrayList<>();
        for (SiteMobKills.Row r : rows.subList(0, Math.min(SiteMobKills.TOP, rows.size()))) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("mob", r.mob());
            e.put("name", r.name());
            e.put("kills", r.kills());
            out.add(e);
        }
        return out;
    }
}
