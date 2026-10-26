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

    /** The next evening headline. {@code name} null when it is not picked yet; {@code status} as in the file. */
    public record Headline(String name, long startAt, String status) {
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

        if (headline == null) {
            m.put("headline", null);
        } else {
            Map<String, Object> h = new LinkedHashMap<>();
            h.put("name", headline.name());
            h.put("start", Instant.ofEpochMilli(headline.startAt()).toString());
            h.put("status", headline.status());
            m.put("headline", h);
        }

        List<Running> sorted = new ArrayList<>(running);
        sorted.sort(Comparator.comparingLong(Running::startedAt));
        List<Object> events = new ArrayList<>();
        for (Running r : sorted) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", r.name());
            e.put("kind", r.kind());
            e.put("map", r.mapId() > 0 ? r.mapId() : null);
            e.put("where", r.where() == null ? "" : r.where());
            e.put("channel", r.channel());
            e.put("started", Instant.ofEpochMilli(r.startedAt()).toString());
            e.put("ends", Instant.ofEpochMilli(r.endsAt()).toString());
            e.put("headline", r.headline());
            events.add(e);
        }
        m.put("running_events", events);
        return m;
    }
}
