package soloMapling.inspect;

import client.Character;
import client.command.commands.gm3.BotWhyCommand;
import server.life.Monster;
import server.maps.MapObject;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeLearning;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeProfile;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotLifeSystem.SocialSelf;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.MapChatLog;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Ticket 157, live bot inspect: the JSON documents {@link BotInspectApi} serves, built from the game's own state on
 * the endpoint's thread (plain reads; no game lock beyond a map's object list, no database, no packet). One code path
 * for every bot character - roster bots and owned alts alike (both are characters on a {@code BotClient}); a field
 * a bot has no data for is null. Each section is read on its own, so one failing read leaves the rest of the
 * document. Field contract: docs/design/inspect-api.md.
 */
public final class InspectViews {

    /** Bots listed per map document, and mobs per map document. */
    static final int MAX_MAP_BOTS = 300;
    static final int MAX_MAP_MOBS = 200;

    private InspectViews() {
    }

    // ── /bots/{cid} ─────────────────────────────────────────────────────────────────────────────

    /** One bot: who and where, what its brain wants (goals, plan step), who it is (Bartle), and the debug read-outs. */
    public static Map<String, Object> bot(Character chr, BotSM sm, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("at", now);
        m.put("cid", chr.getId());
        m.put("name", chr.getName());
        m.put("level", chr.getLevel());
        m.put("job", chr.getJob() == null ? null : chr.getJob().name());
        m.put("jobId", chr.getJob() == null ? null : chr.getJob().getId());
        m.put("kind", kind(chr, sm));
        m.put("ownedAlt", safe(chr::isOwnedAlt));
        m.put("doing", sm instanceof TrainingBot tb ? tb.phaseName().toLowerCase(Locale.ROOT) : null);
        m.put("lifePhase", sm instanceof TrainingBot tb && tb.lifePhase() != null ? tb.lifePhase().name() : null);
        m.put("map", chr.getMapId());
        m.put("channel", chr.getMap() == null ? null : MapChatLog.channelOf(chr.getMap()));
        m.put("x", chr.getPosition() == null ? null : chr.getPosition().x);
        m.put("y", chr.getPosition() == null ? null : chr.getPosition().y);
        m.put("lod", safe(() -> GCMovement.lodTier(chr.getMapId())));
        m.put("tick", sm == null ? null : tick(sm));
        m.put("nav", safe(() -> GCMovement.navDecision(chr)));
        m.put("grinding", safe(() -> GCMovement.isGrinding(chr)));
        m.put("gear", safe(() -> soloMapling.ArtificialPlayer.BotGearSystem.BotGearWhy.describe(chr.getId(), now)));
        m.put("bartle", safe(() -> bartle(SocialSelf.of(chr.getId()))));
        LifeRuntime runtime = sm == null || !LifeRuntime.enabled() ? null : safe(() -> BotWhyCommand.runtimeOf(sm));
        m.put("goals", runtime == null ? null : safe(() -> goals(runtime)));
        m.put("plan", runtime == null ? null : safe(runtime::describePlan));
        m.put("memory", safe(() -> memory(chr.getId(), runtime)));
        m.put("learning", safe(() -> LifeLearning.describe(chr.getId(), chr.getLevel(), now)));
        m.put("why", sm == null || !BotWhyCommand.isEnabled() ? null
                : safe(() -> BotWhyCommand.inspectionLines(sm, now)));
        return m;
    }

    static String kind(Character chr, BotSM sm) {
        if (Boolean.TRUE.equals(safe(chr::isOwnedAlt))) {
            return "alt";
        }
        if (sm == null) {
            return "bot";
        }
        String name = sm.getClass().getSimpleName().replace("Bot", "").toLowerCase(Locale.ROOT);
        return name.isEmpty() ? "bot" : name;
    }

    static Map<String, Object> tick(BotSM sm) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("lastMs", micros(sm.lastTickNanos()));
        t.put("avgMs", micros(sm.avgTickNanos()));
        return t;
    }

    private static double micros(long nanos) {
        return Math.round(nanos / 1_000.0) / 1_000.0;   // ms with 3 decimals
    }

    static Map<String, Object> bartle(SocialSelf self) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("leading", self.leading().name());
        b.put("flipped", self.leadingFlipped());
        b.put("x", round(self.social().x()));
        b.put("y", round(self.social().y()));
        b.put("seedX", round(self.socialSeed().x()));
        b.put("seedY", round(self.socialSeed().y()));
        b.put("plasticity", round(self.plasticity()));
        b.put("rivalPull", round(self.rivalPull()));
        b.put("socialPull", round(self.socialPull()));
        Map<String, Object> blend = new LinkedHashMap<>();
        self.social().blend().forEach((type, share) -> blend.put(type.name(), round(share)));
        b.put("blend", blend);
        Map<String, Object> channels = new LinkedHashMap<>();
        for (LifeProfile.Archetype a : LifeProfile.Archetype.values()) {
            channels.put(a.name(), round(self.channel(a)));
        }
        b.put("channels", channels);
        return b;
    }

    static List<Object> goals(LifeRuntime runtime) {
        List<Object> out = new ArrayList<>();
        for (var goal : runtime.memory().goals()) {
            Map<String, Object> g = new LinkedHashMap<>();
            g.put("kind", goal.kind().name());
            g.put("description", goal.description());
            g.put("pressure", round(goal.pressure()));
            g.put("targetLevel", goal.targetLevel());
            out.add(g);
        }
        return out;
    }

    static Map<String, Object> memory(int cid, LifeRuntime runtime) {
        Map<String, Object> mem = new LinkedHashMap<>();
        int[] notes = BotMemory.cachedNotesBy(cid);
        mem.put("notesCached", notes[0]);
        mem.put("playersCached", notes[1]);
        mem.put("met", runtime == null ? null : runtime.memory().met().size());
        return mem;
    }

    // ── /maps/{id} ──────────────────────────────────────────────────────────────────────────────

    /**
     * One map: the bots on it (every channel) with their positions, the mobs on each loaded instance, and how many
     * public chat lines were said there in the last three minutes. Real players are counted, never listed.
     */
    public static Map<String, Object> map(int mapId, Collection<Character> everyone, Collection<MapleMap> instances,
                                          long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("at", now);
        m.put("map", mapId);
        m.put("lod", safe(() -> GCMovement.lodTier(mapId)));
        List<Character> bots = new ArrayList<>();
        int players = 0;
        for (Character chr : everyone) {
            if (chr == null || chr.getMapId() != mapId) {
                continue;
            }
            if (BotHelpers.isBot(chr)) {
                bots.add(chr);
            } else {
                players++;
            }
        }
        bots.sort(Comparator.comparingInt(Character::getId));
        m.put("players", players);
        m.put("botCount", bots.size());
        List<Object> botRows = new ArrayList<>();
        for (Character chr : bots.subList(0, Math.min(MAX_MAP_BOTS, bots.size()))) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("cid", chr.getId());
            b.put("name", chr.getName());
            b.put("level", chr.getLevel());
            b.put("job", chr.getJob() == null ? null : chr.getJob().name());
            b.put("channel", chr.getMap() == null ? null : MapChatLog.channelOf(chr.getMap()));
            b.put("x", chr.getPosition() == null ? null : chr.getPosition().x);
            b.put("y", chr.getPosition() == null ? null : chr.getPosition().y);
            b.put("nav", safe(() -> GCMovement.navDecision(chr)));
            botRows.add(b);
        }
        m.put("bots", botRows);
        List<Object> mobs = new ArrayList<>();
        int mobCount = 0;
        int chat = 0;
        for (MapleMap map : instances) {
            int channel = MapChatLog.channelOf(map);
            chat += MapChatLog.recent(channel, mapId, now).size();
            List<MapObject> objects = safe(map::getMonsters);
            if (objects == null) {
                continue;
            }
            for (MapObject o : objects) {
                if (!(o instanceof Monster mob)) {
                    continue;
                }
                mobCount++;
                if (mobs.size() >= MAX_MAP_MOBS) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", mob.getId());
                row.put("channel", channel);
                row.put("x", mob.getPosition() == null ? null : mob.getPosition().x);
                row.put("y", mob.getPosition() == null ? null : mob.getPosition().y);
                row.put("hp", mob.getHp());
                row.put("maxHp", mob.getMaxHp());
                mobs.add(row);
            }
        }
        m.put("mobCount", mobCount);
        m.put("mobs", mobs);
        m.put("chatLines", chat);
        return m;
    }

    // ── /positions ──────────────────────────────────────────────────────────────────────────────

    /**
     * Every online bot as {@code [cid, map, x, y]} (compact: about 30 bytes a bot, 3,000 bots under 100 KB), sorted
     * by character id, plus counts per map.
     */
    public static Map<String, Object> positions(Collection<Character> everyone, long now) {
        List<int[]> rows = new ArrayList<>();
        Map<Integer, Integer> perMap = new java.util.TreeMap<>();
        for (Character chr : everyone) {
            if (chr == null || !BotHelpers.isBot(chr) || chr.getPosition() == null) {
                continue;
            }
            rows.add(new int[]{chr.getId(), chr.getMapId(), chr.getPosition().x, chr.getPosition().y});
            perMap.merge(chr.getMapId(), 1, Integer::sum);
        }
        rows.sort(Comparator.comparingInt(r -> r[0]));
        List<Object> bots = new ArrayList<>(rows.size());
        for (int[] r : rows) {
            bots.add(List.of(r[0], r[1], r[2], r[3]));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("at", now);
        m.put("fields", List.of("cid", "map", "x", "y"));
        m.put("count", bots.size());
        m.put("bots", bots);
        Map<String, Object> maps = new LinkedHashMap<>();
        perMap.forEach((id, n) -> maps.put(String.valueOf(id), n));
        m.put("maps", maps);
        return m;
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────

    /** A read that may fail on a bot logging out or a system that is off: null instead of an exception. */
    static <T> T safe(Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
