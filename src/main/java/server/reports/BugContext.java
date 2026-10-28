package server.reports;

import client.Character;
import client.Client;
import client.QuestStatus;
import constants.net.BuildInfo;
import constants.net.ServerConstants;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.life.Monster;
import server.life.NPC;
import server.maps.MapObject;
import server.maps.MapObjectType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.server.Json;

import java.awt.Point;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ticket 131: everything an @bug report carries besides the player's words, as one JSON object (schema below, also in
 * docs/tickets/131-bug-reports.md). Every section is captured on its own: one that throws becomes
 * {@code {"error": "..."}} and the rest still goes in. The whole object is capped at {@link #MAX_BYTES} by
 * {@link #cap}, which halves the longest lists (server log first, then events, surroundings, quests) and lists what it
 * cut under "truncated".
 * <p>
 * Privacy: only the reporter's own character in detail (their mesos exactly, it is their own); other characters by
 * name (and level/job for party members) with a bot flag; no account name, IP, e-mail or chat. Server log lines go
 * through {@link BugPrivacy}.
 */
public final class BugContext {
    public static final int SCHEMA = 1;
    public static final int MAX_BYTES = 64 * 1024;
    public static final int MAX_QUESTS = 40;
    public static final int MAX_LOG_LINES = 60;
    public static final long LOG_WINDOW_MS = 10 * 60_000L;
    public static final int MAX_PLAYERS = 60;
    public static final int MAX_NPCS = 40;
    public static final int MAX_MOB_KINDS = 40;

    /** Lists {@link #cap} may shorten, most expendable first (dotted paths into the context). */
    static final List<String> TRIMMABLE = List.of("serverLog", "trail.events", "surroundings.players",
            "surroundings.npcs", "surroundings.mobs", "quests.started", "trail.npcs", "trail.maps", "buffs", "party.members");

    /** Name lookups (game data); tests pass a stub so no WZ is read. Each may return null. */
    public interface Names {
        String skill(int id);

        String item(int id);

        String quest(int id);

        Names GAME = new Names() {
            @Override
            public String skill(int id) {
                return client.SkillFactory.getSkillName(id);
            }

            @Override
            public String item(int id) {
                return server.ItemInformationProvider.getInstance().getName(id);
            }

            @Override
            public String quest(int id) {
                return server.quest.Quest.getInstance(id).getName();
            }
        };

        Names NONE = new Names() {
            @Override
            public String skill(int id) {
                return null;
            }

            @Override
            public String item(int id) {
                return null;
            }

            @Override
            public String quest(int id) {
                return null;
            }
        };
    }

    private BugContext() {
    }

    /** Builds the context for {@code chr} (the reporter) now. Never throws. */
    public static Map<String, Object> capture(Client c, Character chr, long now, Names names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("schema", SCHEMA);
        m.put("capturedAt", Instant.ofEpochMilli(now).truncatedTo(ChronoUnit.SECONDS).toString());
        section(m, "versions", () -> versions(c));
        section(m, "character", () -> character(c, chr));
        section(m, "party", () -> party(chr));
        section(m, "buffs", () -> buffs(chr, names));
        section(m, "quests", () -> quests(chr, names));
        section(m, "trail", () -> trail(chr, names));
        section(m, "surroundings", () -> surroundings(chr));
        section(m, "serverLog", () -> WarnLogRing.get().linesFor(chr.getName(), chr.getId(), now - LOG_WINDOW_MS, MAX_LOG_LINES));
        return m;
    }

    private interface Part {
        Object get() throws Exception;
    }

    private static void section(Map<String, Object> m, String key, Part p) {
        try {
            m.put(key, p.get());
        } catch (Exception | LinkageError e) {
            m.put(key, Map.of("error", e.getClass().getSimpleName()));
        }
    }

    static Map<String, Object> versions(Client c) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("server", ServerConstants.SLUMSMS_VERSION);
        v.put("commit", BuildInfo.commit());
        v.put("dirty", BuildInfo.dirty());
        v.put("jarSha256", BuildInfo.jarSha256());
        Map<String, Object> cl = new LinkedHashMap<>();
        cl.put("build", c.getClientBuild());
        cl.put("release", c.getClientRelease());
        cl.put("stampFormat", c.getClientStampFormat());
        cl.put("caps", c.getClientCaps());
        cl.put("capsNames", capsNames(c.getClientCaps()));
        v.put("client", cl);
        v.put("world", c.getWorld());
        v.put("channel", c.getChannel());
        return v;
    }

    /** Known stamp caps bits (ClientBuildStampHandler, ticket 131). */
    static List<String> capsNames(int caps) {
        List<String> out = new ArrayList<>();
        if ((caps & 1) != 0) {
            out.add("customNpcData");
        }
        if ((caps & BugClientLogs.CAPS_LOG_UPLOAD) != 0) {
            out.add("logUpload");
        }
        for (int bit = 2; bit < 31; bit++) {
            if ((caps & (1 << bit)) != 0) {
                out.add("bit" + bit);
            }
        }
        return out;
    }

    static Map<String, Object> character(Client c, Character chr) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", chr.getName());
        m.put("id", chr.getId());
        Map<String, Object> job = new LinkedHashMap<>();
        job.put("id", chr.getJob() == null ? null : chr.getJob().getId());
        job.put("name", chr.getJob() == null ? null : chr.getJob().name());
        m.put("job", job);
        m.put("level", chr.getLevel());
        m.put("gmLevel", chr.gmLevel());
        MapleMap map = chr.getMap();
        Map<String, Object> mm = new LinkedHashMap<>();
        mm.put("id", map == null ? chr.getMapId() : map.getId());
        mm.put("name", map == null ? null : map.getMapName());
        mm.put("street", map == null ? null : map.getStreetName());
        m.put("map", mm);
        Point pos = chr.getPosition();
        m.put("x", pos == null ? null : pos.x);
        m.put("y", pos == null ? null : pos.y);
        m.put("foothold", chr.getFh());
        m.put("hp", chr.getHp());
        m.put("maxHp", chr.getCurrentMaxHp());
        m.put("mp", chr.getMp());
        m.put("maxMp", chr.getCurrentMaxMp());
        m.put("meso", chr.getMeso());
        m.put("alive", chr.isAlive());
        return m;
    }

    static Object party(Character chr) {
        Party p = chr.getParty();
        if (p == null) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        List<Object> members = new ArrayList<>();
        for (PartyCharacter pc : p.getMembers()) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", pc.getName());
            r.put("level", pc.getLevel());
            r.put("job", pc.getJobId());
            r.put("mapId", pc.getMapId());
            r.put("online", pc.isOnline());
            r.put("bot", BotHelpers.isArtificial(pc.getId()));
            r.put("leader", pc.getId() == p.getLeaderId());
            members.add(r);
        }
        m.put("members", members);
        return m;
    }

    static List<Object> buffs(Character chr, Names names) {
        List<Object> out = new ArrayList<>();
        for (Integer src : new java.util.TreeSet<>(chr.getAvailableBuffs())) {
            Map<String, Object> r = new LinkedHashMap<>();
            if (src < 0) { // an item's effect
                r.put("item", -src);
                r.put("name", name(() -> names.item(-src)));
            } else {
                r.put("skill", src);
                r.put("name", name(() -> names.skill(src)));
            }
            out.add(r);
        }
        return out;
    }

    static Map<String, Object> quests(Character chr, Names names) {
        List<QuestStatus> started = chr.getStartedQuests();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", started.size());
        List<Object> rows = new ArrayList<>();
        for (QuestStatus q : started) {
            if (rows.size() >= MAX_QUESTS) {
                break;
            }
            int id = q.getQuestID();
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", id);
            r.put("name", name(() -> names.quest(id)));
            rows.add(r);
        }
        m.put("started", rows);
        return m;
    }

    static Map<String, Object> trail(Character chr, Names names) {
        PlayerTrail t = PlayerTrail.of(chr);
        return trailMap(t, names);
    }

    static Map<String, Object> trailMap(PlayerTrail t, Names names) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (t == null) {
            m.put("maps", List.of());
            m.put("npcs", List.of());
            m.put("events", List.of());
            return m;
        }
        List<Object> maps = new ArrayList<>();
        for (PlayerTrail.Entry e : t.maps()) {
            maps.add(row(e.time(), "id", e.id(), "name", e.note()));
        }
        List<Object> npcs = new ArrayList<>();
        for (PlayerTrail.Entry e : t.npcs()) {
            npcs.add(row(e.time(), "id", e.id(), "name", e.note()));
        }
        List<Object> events = new ArrayList<>();
        for (PlayerTrail.Entry e : t.events()) {
            Map<String, Object> r = row(e.time(), "kind", e.kind(), "id", e.id());
            String n = switch (e.kind()) {
                case "skill", "attack" -> e.id() == 0 ? "basic attack" : name(() -> names.skill(e.id()));
                case "use", "shop" -> name(() -> names.item(e.id()));
                case "pickup" -> e.note() != null && e.note().startsWith("meso") ? null : name(() -> names.item(e.id()));
                default -> null;
            };
            if (n != null) {
                r.put("name", n);
            }
            if (e.note() != null) {
                r.put("note", e.note());
            }
            if (e.count() > 1) {
                r.put("count", e.count());
            }
            events.add(r);
        }
        m.put("maps", maps);
        m.put("npcs", npcs);
        m.put("events", events);
        return m;
    }

    private static Map<String, Object> row(long t, String k1, Object v1, String k2, Object v2) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("t", Instant.ofEpochMilli(t).truncatedTo(ChronoUnit.SECONDS).toString());
        r.put(k1, v1);
        r.put(k2, v2);
        return r;
    }

    private interface Lookup {
        String get();
    }

    private static String name(Lookup l) {
        try {
            String s = l.get();
            return s == null || s.isBlank() ? null : s;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    static Map<String, Object> surroundings(Character chr) {
        MapleMap map = chr.getMap();
        Map<String, Object> m = new LinkedHashMap<>();
        if (map == null) {
            return m;
        }
        Map<Integer, Integer> mobCounts = new TreeMap<>();
        Map<Integer, String> mobNames = new LinkedHashMap<>();
        for (Monster mob : map.getAllMonsters()) {
            mobCounts.merge(mob.getId(), 1, Integer::sum);
            mobNames.putIfAbsent(mob.getId(), name(mob::getName));
        }
        List<Object> mobs = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : mobCounts.entrySet()) {
            if (mobs.size() >= MAX_MOB_KINDS) {
                break;
            }
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("id", e.getKey());
            r.put("name", mobNames.get(e.getKey()));
            r.put("count", e.getValue());
            mobs.add(r);
        }
        m.put("mobs", mobs);
        List<Object> npcs = new ArrayList<>();
        for (MapObject o : map.getMapObjectsInRange(new Point(0, 0), Double.POSITIVE_INFINITY, Arrays.asList(MapObjectType.NPC))) {
            if (npcs.size() >= MAX_NPCS) {
                break;
            }
            if (o instanceof NPC npc) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", npc.getId());
                r.put("name", name(npc::getName));
                npcs.add(r);
            }
        }
        m.put("npcs", npcs);
        List<Object> players = new ArrayList<>();
        int total = 0;
        for (Character other : map.getAllPlayers()) {
            if (other == null || other.getId() == chr.getId()) {
                continue;
            }
            total++;
            if (players.size() < MAX_PLAYERS) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("name", other.getName());
                r.put("bot", other.isBot() || BotHelpers.isArtificial(other.getId()));
                players.add(r);
            }
        }
        m.put("playerCount", total);
        m.put("players", players);
        return m;
    }

    /**
     * Shortens {@code ctx} in place until its JSON is at most {@code maxBytes} (UTF-8): halves the first list in
     * {@link #TRIMMABLE} that still has more than one entry (keeping the newest end for time-ordered lists), then
     * empties lists, and as a last resort keeps only versions and character. Records what it cut under "truncated".
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> cap(Map<String, Object> ctx, int maxBytes) {
        List<String> cut = new ArrayList<>();
        int guard = 0;
        while (bytes(ctx) > maxBytes && guard++ < 200) {
            boolean did = false;
            for (int pass = 0; pass < 2 && !did; pass++) {
                for (String path : TRIMMABLE) {
                    List<Object> l = listAt(ctx, path);
                    if (l == null || l.isEmpty() || (pass == 0 && l.size() <= 1)) {
                        continue;
                    }
                    int keep = pass == 0 ? l.size() / 2 : 0;
                    List<Object> kept = timeOrdered(path) ? new ArrayList<>(l.subList(l.size() - keep, l.size()))
                            : new ArrayList<>(l.subList(0, keep));
                    l.clear();
                    l.addAll(kept);
                    if (!cut.contains(path)) {
                        cut.add(path);
                    }
                    did = true;
                    break;
                }
            }
            if (!did) {
                Map<String, Object> min = new LinkedHashMap<>();
                for (String k : List.of("schema", "capturedAt", "versions", "character")) {
                    if (ctx.containsKey(k)) {
                        min.put(k, ctx.get(k));
                    }
                }
                ctx.clear();
                ctx.putAll(min);
                cut.add("everything else");
                if (bytes(ctx) > maxBytes) {
                    ctx.remove("character");
                }
                break;
            }
        }
        if (!cut.isEmpty()) {
            ctx.put("truncated", cut);
        }
        return ctx;
    }

    private static boolean timeOrdered(String path) {
        return path.equals("serverLog") || path.startsWith("trail.");
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listAt(Map<String, Object> ctx, String path) {
        Object cur = ctx;
        for (String part : path.split("\\.")) {
            if (!(cur instanceof Map<?, ?> m)) {
                return null;
            }
            cur = m.get(part);
        }
        if (cur instanceof List<?> l) {
            try {
                ((List<Object>) l).addAll(List.of()); // probe: immutable lists cannot be trimmed in place
                return (List<Object>) l;
            } catch (UnsupportedOperationException e) {
                return null;
            }
        }
        return null;
    }

    public static int bytes(Object o) {
        return Json.write(o).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * The short version for the reports.jsonl line (the full context goes in its own file): versions, level/job,
     * map and position, how many events and log lines the full file has.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> summary(Map<String, Object> ctx, String contextFile) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (ctx.get("versions") instanceof Map<?, ?> v) {
            s.put("server", v.get("server"));
            s.put("commit", v.get("commit"));
            if (v.get("client") instanceof Map<?, ?> cl) {
                s.put("clientBuild", cl.get("build"));
                s.put("clientRelease", cl.get("release"));
                s.put("clientCaps", cl.get("caps"));
            }
            s.put("world", v.get("world"));
            s.put("channel", v.get("channel"));
        }
        if (ctx.get("character") instanceof Map<?, ?> c) {
            s.put("characterId", c.get("id"));
            s.put("level", c.get("level"));
            if (c.get("job") instanceof Map<?, ?> j) {
                s.put("job", j.get("name"));
            }
            if (c.get("map") instanceof Map<?, ?> mm) {
                s.put("mapId", mm.get("id"));
            }
            s.put("x", c.get("x"));
            s.put("y", c.get("y"));
        }
        if (ctx.get("trail") instanceof Map<?, ?> t && t.get("events") instanceof List<?> ev) {
            s.put("events", ev.size());
        }
        if (ctx.get("serverLog") instanceof List<?> logs) {
            s.put("logLines", logs.size());
        }
        s.put("file", contextFile);
        return s;
    }
}
