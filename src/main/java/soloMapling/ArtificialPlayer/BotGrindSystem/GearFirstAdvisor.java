package soloMapling.ArtificialPlayer.BotGrindSystem;

import client.Character;
import client.inventory.Equip;
import server.ItemInformationProvider;
import server.life.MonsterDropEntry;
import server.life.MonsterInformationProvider;
import soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat;
import soloMapling.ArtificialPlayer.BotGearSystem.GearScoring;
import soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstPlanner.Candidate;
import soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstPlanner.GearProspect;
import soloMapling.ArtificialPlayer.BotGrindSystem.GearFirstPlanner.Scores;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/*
 * Ticket 162 part 2: the wiring of GearFirstPlanner (after nutnnut/Cosmic's BotGrindAdvisor, dev 2943481dd) into our
 * map choice. Off unless -Dslums.grind.gearFirst=true; then TrainingMapChooser:
 *  - weights its capacity- and distance-aware pick by the gear-first score of each eligible map (gear progression
 *    first, EXP the tiebreaker), so a bot with a drop worth chasing in reach drifts toward the map that drops it;
 *  - skips the EXP-first leveling path while gear is the bot's dominant need;
 *  - for a bot whose life goal is FARM_ITEM (LifeGoals), picks the eligible map with the most expected copies of
 *    that item an hour.
 * The most desirable drop of the last decision is kept per bot (want) and becomes the FARM_ITEM aspiration.
 *
 * Inputs: the bot's own kill time and EXP per map (OffscreenCombat.rateOn, the numbers its offscreen grind uses),
 * the map's spawns (MapMobIndex), the drop table (MonsterInformationProvider, no quest drops), and the DPS gain of a
 * drop from the shared gear scorer (GearScoring, a clean copy of the item: its WZ stats, the average roll).
 * Cost: one decision (a few minutes apart per bot) scores each eligible map once and each distinct equip drop once.
 */
public final class GearFirstAdvisor {

    private GearFirstAdvisor() {}

    public static boolean enabled() {
        return Boolean.getBoolean("slums.grind.gearFirst");
    }

    /* The drop the bot wanted most at its last decision. */
    public record Want(int itemId, String name, double desirability) {
    }

    private static final Map<Integer, Want> WANTS = new ConcurrentHashMap<>();

    public static Want want(int botId) {
        return WANTS.get(botId);
    }

    public static void forget(int botId) {
        WANTS.remove(botId);
    }

    /* What the advisor reads from the game; the live world, or a stub in tests. */
    interface Sources {
        /* Seconds to kill one mob and EXP per kill for this bot on this map, or null when it cannot grind there. */
        double[] pace(Character chr, int mapId);

        /* The map's mob spawns, one entry per spawn point. */
        List<Integer> spawns(int mapId);

        /* The mob's drops: item id to chance per kill (0..1), equips only, no quest drops. */
        Map<Integer, Double> equipDrops(int mobId);

        /* The DPS fraction a fresh copy of this item adds for the bot (0 when it would not wear it). */
        double gain(Character chr, int itemId);

        String name(int itemId);
    }

    static final Sources LIVE = new Sources() {
        @Override
        public double[] pace(Character chr, int mapId) {
            OffscreenCombat.Rate r = OffscreenCombat.rateOn(chr, mapId);
            if (r == null || r.killsPerMinute() <= 0) {
                return null;
            }
            return new double[]{60.0 / r.killsPerMinute(), r.expPerKill()};
        }

        @Override
        public List<Integer> spawns(int mapId) {
            return MapMobIndex.mobIds(mapId);
        }

        @Override
        public Map<Integer, Double> equipDrops(int mobId) {
            Map<Integer, Double> out = new HashMap<>();
            List<MonsterDropEntry> drops = MonsterInformationProvider.getInstance().retrieveEffectiveDrop(mobId);
            if (drops != null) {
                for (MonsterDropEntry d : drops) {
                    if (d.itemId / 1000000 == 1 && d.questid <= 0 && d.chance > 0) {
                        out.merge(d.itemId, d.chance / 1_000_000.0, Double::sum);
                    }
                }
            }
            return out;
        }

        @Override
        public double gain(Character chr, int itemId) {
            Equip fresh = ItemInformationProvider.getInstance().getEquipByIdAsEquip(itemId);
            if (fresh == null) {
                return 0;
            }
            return GearScoring.live().upgradeFraction(chr, fresh);
        }

        @Override
        public String name(int itemId) {
            String n = ItemInformationProvider.getInstance().getName(itemId);
            return n == null ? "item " + itemId : n;
        }
    };

    private static volatile Sources sources = LIVE;

    static void useForTest(Sources test) {
        sources = test == null ? LIVE : test;
        WANTS.clear();
    }

    /* The gear-first candidates for these maps (a map the bot cannot grind gets no candidate: null in the list). */
    static List<Candidate> candidates(Character chr, List<TrainingMap> maps, Map<Integer, Double> gains) {
        Sources src = sources;
        List<Candidate> out = new ArrayList<>(maps.size());
        for (TrainingMap m : maps) {
            double[] pace = src.pace(chr, m.mapId());
            List<Integer> spawns = src.spawns(m.mapId());
            if (pace == null || spawns == null || spawns.isEmpty()) {
                out.add(null);
                continue;
            }
            Map<Integer, Integer> perMob = new LinkedHashMap<>();
            for (int mob : spawns) {
                perMob.merge(mob, 1, Integer::sum);
            }
            Map<Integer, Double> chance = new LinkedHashMap<>();   // item -> chance per kill on this map
            for (Map.Entry<Integer, Integer> e : perMob.entrySet()) {
                double share = e.getValue() / (double) spawns.size();
                for (Map.Entry<Integer, Double> d : src.equipDrops(e.getKey()).entrySet()) {
                    chance.merge(d.getKey(), d.getValue() * share, Double::sum);
                }
            }
            List<GearProspect> prospects = new ArrayList<>();
            for (Map.Entry<Integer, Double> d : chance.entrySet()) {
                double gain = gains.computeIfAbsent(d.getKey(), id -> src.gain(chr, id));
                if (gain > 0) {
                    prospects.add(new GearProspect(d.getKey(), d.getValue(), gain));
                }
            }
            out.add(new Candidate(m.mapId(), pace[0], pace[1], spawns.size(), 0, prospects));
        }
        return out;
    }

    /* The result of one decision: per-map weight multipliers and the gear need. */
    public record Advice(double[] weights, double needGear) {
    }

    /*
     * Multipliers for the eligible maps' pick weights (0.1 + 0.9 x the gear-first score: no map is ruled out here,
     * capacity still decides), and the bot's gear need; null when off or nothing could be scored. Records the bot's
     * want.
     */
    public static Advice advise(Character chr, List<TrainingMap> maps) {
        if (!enabled() || chr == null || maps == null || maps.isEmpty()) {
            return null;
        }
        try {
            List<Candidate> cands = candidates(chr, maps, new HashMap<>());
            List<Candidate> scored = new ArrayList<>();
            for (Candidate c : cands) {
                if (c != null) {
                    scored.add(c);
                }
            }
            if (scored.isEmpty()) {
                return null;
            }
            Scores s = GearFirstPlanner.score(scored);
            double[] weights = new double[maps.size()];
            for (int i = 0, k = 0; i < cands.size(); i++) {
                weights[i] = cands.get(i) == null ? 0.1 : 0.1 + 0.9 * s.unified()[k++];
            }
            if (s.gearFirst() && s.wanted() != null) {
                WANTS.put(chr.getId(), new Want(s.wanted().itemId(), sources.name(s.wanted().itemId()),
                        s.wantedDesire()));
            } else {
                WANTS.remove(chr.getId());
            }
            return new Advice(weights, s.needGear());
        } catch (RuntimeException | LinkageError e) {
            return null;   // the map choice goes on as before
        }
    }

    /* The eligible map to farm this item on (near-best by expected copies an hour), or null when none drops it. */
    public static TrainingMap farmPick(Character chr, int itemId, List<TrainingMap> maps) {
        if (!enabled() || chr == null || maps == null || maps.isEmpty()) {
            return null;
        }
        try {
            List<Candidate> cands = candidates(chr, maps, new HashMap<>());
            List<Candidate> scored = new ArrayList<>();
            List<TrainingMap> scoredMaps = new ArrayList<>();
            for (int i = 0; i < cands.size(); i++) {
                if (cands.get(i) != null) {
                    scored.add(withAllDrops(cands.get(i), chr, maps.get(i), itemId));
                    scoredMaps.add(maps.get(i));
                }
            }
            double[] farm = GearFirstPlanner.farmScores(scored, itemId);
            List<Integer> near = GearFirstPlanner.nearBest(farm, 0.85);
            if (near.isEmpty()) {
                return null;
            }
            return scoredMaps.get(near.get(ThreadLocalRandom.current().nextInt(near.size())));
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    // A farm target counts on a map even when its gain is now 0 (the goal decides, not the scorer).
    private static Candidate withAllDrops(Candidate c, Character chr, TrainingMap m, int itemId) {
        for (GearProspect p : c.gearDrops()) {
            if (p.itemId() == itemId) {
                return c;
            }
        }
        Sources src = sources;
        List<Integer> spawns = src.spawns(m.mapId());
        double chance = 0;
        for (int mob : spawns) {
            chance += src.equipDrops(mob).getOrDefault(itemId, 0.0) / spawns.size();
        }
        if (chance <= 0) {
            return c;
        }
        List<GearProspect> drops = new ArrayList<>(c.gearDrops());
        drops.add(new GearProspect(itemId, chance, 0));
        return new Candidate(c.mapId(), c.killSeconds(), c.exp(), c.spawnPoints(), c.mapAreaPx(), drops);
    }
}
