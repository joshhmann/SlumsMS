package soloMapling.site;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 1.6.0 Track D: what a site file must look like ({@code !qa sf-1} and the tests): schema 1, a UTC generated_at, the
 * lists within their limits, the feed newest first, and no key that would leak ids or account data.
 */
public final class SiteFileShape {

    /** Keys that would leak ids or account data; none may appear anywhere in a site file. */
    public static final Set<String> FORBIDDEN_KEYS = Set.of("characterid", "character_id", "charid", "char_id", "botid",
            "bot_id", "account", "accountid", "account_id", "accountname", "email", "ip", "hwid");

    private SiteFileShape() {
    }

    /** What is wrong with a file's shape (empty: nothing). */
    public static List<String> shape(String name, Object doc) {
        List<String> out = new ArrayList<>();
        if (!(doc instanceof Map<?, ?> m)) {
            out.add("not an object");
            return out;
        }
        if (!(m.get("schema") instanceof Number n) || n.intValue() != PublicSiteExport.SCHEMA) {
            out.add("schema is not " + PublicSiteExport.SCHEMA);
        }
        if (!(m.get("generated_at") instanceof String g) || !isTime(g)) {
            out.add("generated_at is not a UTC time");
        }
        forbidden(doc, "", out);
        switch (name) {
            case "feed.json" -> {
                List<?> items = list(m.get("items"));
                if (items == null) {
                    out.add("items missing");
                    break;
                }
                if (items.size() > SiteFeed.LIMIT) {
                    out.add("items " + items.size() + " > " + SiteFeed.LIMIT);
                }
                Instant last = null;
                for (Object o : items) {
                    if (!(o instanceof Map<?, ?> i) || !(i.get("id") instanceof String) || !(i.get("text") instanceof String)
                            || !(i.get("t") instanceof String t) || !isTime(t) || list(i.get("names")) == null
                            || !Set.of("event", "milestone", "megaphone", "drama").contains(i.get("kind"))) {
                        out.add("an item without id/t/kind/text/names");
                        break;
                    }
                    Instant at = Instant.parse(t);
                    if (last != null && at.isAfter(last)) {
                        out.add("items not newest first");
                        break;
                    }
                    last = at;
                }
            }
            case "leaderboards.json" -> {
                Map<?, ?> level = map(m.get("level"));
                if (level == null || !within(level.get("overall"), SiteLeaderboards.LEVEL_TOP)) {
                    out.add("level.overall missing or over " + SiteLeaderboards.LEVEL_TOP);
                }
                Map<?, ?> branches = level == null ? null : map(level.get("by_branch"));
                for (String b : SiteLeaderboards.BRANCHES) {
                    if (branches == null || !within(branches.get(b), SiteLeaderboards.BRANCH_TOP)) {
                        out.add("level.by_branch." + b + " missing or over " + SiteLeaderboards.BRANCH_TOP);
                    }
                }
                if (!within(m.get("fame"), SiteLeaderboards.FAME_TOP)) {
                    out.add("fame missing or over " + SiteLeaderboards.FAME_TOP);
                }
                Map<?, ?> guilds = map(m.get("guilds"));
                if (guilds == null || !within(guilds.get("by_points"), SiteLeaderboards.GUILD_TOP)
                        || !within(guilds.get("by_members"), SiteLeaderboards.GUILD_TOP)) {
                    out.add("guilds missing or over " + SiteLeaderboards.GUILD_TOP);
                }
                if (!within(m.get("richest"), SiteLeaderboards.RICH_TOP)) {
                    out.add("richest missing or over " + SiteLeaderboards.RICH_TOP);
                }
                if (map(m.get("dojo")) == null || map(m.get("bosses")) == null) {
                    out.add("dojo or bosses missing");
                }
            }
            case "world.json" -> {
                if (!(m.get("online") instanceof Number) || list(m.get("channels")) == null
                        || !within(m.get("busiest_maps"), SiteWorld.BUSIEST) || list(m.get("running_events")) == null
                        || !m.containsKey("headline")) {
                    out.add("online/channels/busiest_maps/headline/running_events missing or over limits");
                }
            }
            default -> {
            }
        }
        return out;
    }

    private static void forbidden(Object doc, String path, List<String> out) {
        if (doc instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (FORBIDDEN_KEYS.contains(k.toLowerCase(Locale.ROOT))) {
                    out.add("forbidden key " + path + k);
                }
                forbidden(e.getValue(), path + k + ".", out);
            }
        } else if (doc instanceof List<?> l) {
            for (Object o : l) {
                forbidden(o, path, out);
            }
        }
    }

    private static boolean isTime(String s) {
        try {
            Instant.parse(s);
            return s.endsWith("Z");
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : null;
    }

    private static Map<?, ?> map(Object o) {
        return o instanceof Map<?, ?> m ? m : null;
    }

    private static boolean within(Object o, int max) {
        return o instanceof List<?> l && l.size() <= max;
    }

}
