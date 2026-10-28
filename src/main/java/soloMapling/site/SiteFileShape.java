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
            "bot_id", "bot", "is_bot", "isbot", "is_player", "isplayer", "player", "seller_kind", "kind_of_player",
            "email", "ip", "ip_address", "hwid", "mac", "password");

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
                if (!within(m.get("level"), SiteLeaderboards.LEVEL_TOP)) {
                    out.add("level missing or over " + SiteLeaderboards.LEVEL_TOP);
                }
                Map<?, ?> classes = map(m.get("levelByClass"));
                for (String c : SiteLeaderboards.CLASSES) {
                    if (classes == null || !within(classes.get(c), SiteLeaderboards.BRANCH_TOP)) {
                        out.add("levelByClass." + c + " missing or over " + SiteLeaderboards.BRANCH_TOP);
                    }
                }
                if (!within(m.get("fame"), SiteLeaderboards.FAME_TOP)) {
                    out.add("fame missing or over " + SiteLeaderboards.FAME_TOP);
                }
                if (!within(m.get("guilds"), SiteLeaderboards.GUILD_TOP)) {
                    out.add("guilds missing or over " + SiteLeaderboards.GUILD_TOP);
                }
                if (!within(m.get("traders"), SiteLeaderboards.TRADER_TOP)) {
                    out.add("traders missing or over " + SiteLeaderboards.TRADER_TOP);
                }
                Map<?, ?> bosses = map(m.get("bosses"));
                if (list(m.get("dojo")) == null || bosses == null) {
                    out.add("dojo or bosses missing");
                } else {
                    for (String bn : SiteLeaderboards.BOSS_NAMES) {
                        if (!within(bosses.get(bn), SiteLeaderboards.BOSS_TOP)) {
                            out.add("bosses." + bn + " missing or over " + SiteLeaderboards.BOSS_TOP);
                        }
                    }
                }
            }
            case "world.json" -> {
                if (!(m.get("online") instanceof Number) || list(m.get("channels")) == null
                        || !within(m.get("busiest_maps"), SiteWorld.BUSIEST) || list(m.get("running")) == null
                        || !m.containsKey("tonight") || !within(m.get("top_mobs"), SiteMobKills.TOP)
                        || !within(m.get("top_mobs_7d"), SiteMobKills.TOP)
                        || !(m.get("version") instanceof Map<?, ?> v && v.get("server") instanceof String)) {
                    out.add("online/channels/busiest_maps/top_mobs/top_mobs_7d/tonight/running/version missing or over limits");
                }
            }
            default -> {
            }
        }
        return out;
    }

    /** A key the site's build refuses: an id, account data or a bot/player flag. */
    public static boolean forbiddenKey(String key) {
        String k = key.toLowerCase(Locale.ROOT);
        return FORBIDDEN_KEYS.contains(k) || k.startsWith("account");
    }

    private static void forbidden(Object doc, String path, List<String> out) {
        if (doc instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (forbiddenKey(k)) {
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
