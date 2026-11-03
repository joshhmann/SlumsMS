package soloMapling.mechanics;

import com.esotericsoftware.yamlbeans.YamlReader;
import soloMapling.mechanics.MechanicsModel.Answer;
import soloMapling.mechanics.MechanicsModel.BossPhase;
import soloMapling.mechanics.MechanicsModel.Clear;
import soloMapling.mechanics.MechanicsModel.Content;
import soloMapling.mechanics.MechanicsModel.Count;
import soloMapling.mechanics.MechanicsModel.ItemUse;
import soloMapling.mechanics.MechanicsModel.Kind;
import soloMapling.mechanics.MechanicsModel.Objective;
import soloMapling.mechanics.MechanicsModel.ObjectiveKind;
import soloMapling.mechanics.MechanicsModel.Position;
import soloMapling.mechanics.MechanicsModel.Role;
import soloMapling.mechanics.MechanicsModel.Stage;
import soloMapling.mechanics.MechanicsModel.Tier;
import soloMapling.mechanics.MechanicsModel.TierRules;
import soloMapling.mechanics.MechanicsModel.Who;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads and checks ticket 130's stage-data YAML (one file per PQ/boss in {@link Mechanics#dataDir()}). Strict like
 * {@code EventTemplates}: a file with a mistake is skipped and reported, never half-loaded; other files still load.
 * Format: {@code docs/tickets/130-bots-play-mechanics.md}, "Stage data format", and the KPQ example in
 * {@code content/kpq.yaml}.
 */
public final class StageData {

    /** Role ids every file may use without declaring them. */
    static final Set<String> IMPLICIT_ROLES = Set.of("all");

    public record Result(List<Content> content, List<String> errors) {
    }

    private StageData() {
    }

    /** The index file in the content folder: one stage-data file name per line ({@code #} comments allowed). */
    public static final String INDEX = "index.txt";

    private static final java.util.regex.Pattern FILE_NAME = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]*\\.yaml");

    /**
     * The shipped stage data, wherever this server keeps it: {@code -Dslums.mechanics.dir} (a folder, listed) when it
     * is set, else the files named in {@value #INDEX}, each read through {@link soloMapling.DataFiles} so a jar-only
     * install works (a folder cannot be listed inside the jar: HANDOFF's jar-only data gap).
     */
    public static Result load() {
        String override = System.getProperty(Mechanics.DIR_PROPERTY);
        if (override != null && !override.isBlank()) {
            return loadDir(Path.of(override.trim()));
        }
        return loadIndexed(Mechanics.DEFAULT_DIR, soloMapling.DataFiles::path);
    }

    /**
     * Loads the files the folder's {@value #INDEX} names. {@code resolve} turns a {@code src/main/java/...} data path
     * into a readable file ({@code DataFiles::path} at run time: override dir, source tree, or the jar).
     */
    public static Result loadIndexed(String dir, java.util.function.Function<String, Path> resolve) {
        List<Content> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        String base = dir.endsWith("/") ? dir : dir + "/";
        List<String> names;
        try {
            names = indexNames(Files.readAllLines(resolve.apply(base + INDEX)));
        } catch (IOException | RuntimeException e) {
            return new Result(List.of(), List.of("cannot read " + base + INDEX + ": " + e.getMessage()));
        }
        for (String name : names) {
            if (!FILE_NAME.matcher(name).matches()) {
                errors.add(INDEX + ": '" + name + "' is not a stage-data file name like kpq.yaml");
                continue;
            }
            try (Reader in = Files.newBufferedReader(resolve.apply(base + name))) {
                Result r = parse(in);
                out.addAll(r.content());
                r.errors().forEach(e -> errors.add(name + ": " + e));
            } catch (IOException | RuntimeException e) {
                errors.add(name + ": cannot read (" + e.getMessage() + ")");
            }
        }
        return dedupe(out, errors);
    }

    /** The file names an index lists: trimmed, blank lines and {@code #} comments skipped, each once. */
    static List<String> indexNames(List<String> lines) {
        List<String> names = new ArrayList<>();
        for (String line : lines) {
            String n = line.strip();
            if (!n.isEmpty() && !n.startsWith("#") && !names.contains(n)) {
                names.add(n);
            }
        }
        return names;
    }

    /** Loads every {@code *.yaml} in the folder (a folder on disk; the shipped data goes through {@link #load()}). */
    public static Result loadDir(Path dir) {
        List<Content> out = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        List<Path> files;
        try (java.util.stream.Stream<Path> listing = Files.list(dir)) {
            // sorted by name: a directory listing has no order, and "the first content row" must mean the same thing
            // in every run (ClotoScriptForBotsTest reads kpq's first row that way)
            files = listing.filter(p -> p.getFileName().toString().endsWith(".yaml")).sorted().toList();
        } catch (IOException e) {
            errors.add("cannot read " + dir + ": " + e.getMessage());
            return new Result(List.copyOf(out), List.copyOf(errors));
        }
        try {
            for (Path f : files) {
                try (Reader in = Files.newBufferedReader(f)) {
                    Result r = parse(in);
                    out.addAll(r.content());
                    r.errors().forEach(e -> errors.add(f.getFileName() + ": " + e));
                }
            }
        } catch (IOException e) {
            errors.add("cannot read " + dir + ": " + e.getMessage());
        }
        return dedupe(out, errors);
    }

    private static Result dedupe(List<Content> out, List<String> errors) {
        Set<String> ids = new HashSet<>();
        out.removeIf(c -> {
            if (!ids.add(c.id())) {
                errors.add(c.id() + ": duplicate content id across files");
                return true;
            }
            return false;
        });
        return new Result(List.copyOf(out), List.copyOf(errors));
    }

    public static Result parse(Reader in) throws IOException {
        Object root = new YamlReader(in).read();
        if (!(root instanceof Map<?, ?> m)) {
            return new Result(List.of(), List.of("the file is not a map"));
        }
        List<String> problems = new ArrayList<>();
        Content c = readContent(m, problems);
        if (!problems.isEmpty() || c == null) {
            String label = String.valueOf(m.get("id"));
            return new Result(List.of(), problems.stream().map(p -> label + ": " + p).toList());
        }
        return new Result(List.of(c), List.of());
    }

    private static Content readContent(Map<?, ?> m, List<String> p) {
        String id = str(m, "id", p, true);
        String name = str(m, "name", p, true);
        Kind kind = enumOf(Kind.class, str(m, "kind", p, true), "kind", p);
        String script = str(m, "eventScript", p, false);
        int recruit = num(m, "recruitMap", 0, p);
        int exit = num(m, "exitMap", 0, p);
        int minP = num(m, "minPlayers", 1, p);
        int maxP = num(m, "maxPlayers", 6, p);
        int minL = num(m, "minLevel", 1, p);
        int maxL = num(m, "maxLevel", 200, p);
        int time = num(m, "timeLimitMinutes", 0, p);
        if (minP < 1 || maxP < minP) {
            p.add("players: need 1 <= minPlayers <= maxPlayers");
        }
        if (minL < 1 || maxL < minL) {
            p.add("levels: need 1 <= minLevel <= maxLevel");
        }
        List<String> sources = new ArrayList<>();
        for (Object o : list(m, "sources")) {
            sources.add(String.valueOf(o));
        }
        if (sources.isEmpty()) {
            p.add("sources: say where the stage data was checked from (our script, and credit any outside source)");
        }
        List<Stage> stages = new ArrayList<>();
        int expected = 1;
        for (Object o : list(m, "stages")) {
            if (!(o instanceof Map<?, ?> sm)) {
                p.add("a stage that is not a map");
                continue;
            }
            Stage s = readStage(sm, maxP, p);
            if (s != null) {
                if (s.index() != expected) {
                    p.add("stage " + s.index() + ": stages must be numbered 1, 2, 3... in order (expected " + expected + ")");
                }
                expected = s.index() + 1;
                stages.add(s);
            }
        }
        if (stages.isEmpty()) {
            p.add("no stages");
        }
        Map<Tier, TierRules> tiers = new EnumMap<>(Tier.class);
        Object tierObj = m.get("tiers");
        if (tierObj instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                Optional<Tier> t = Tier.parse(String.valueOf(e.getKey()));
                if (t.isEmpty() || !(e.getValue() instanceof Map<?, ?> rm)) {
                    p.add("tiers: unknown tier or not a map: " + e.getKey());
                    continue;
                }
                tiers.put(t.get(), readTier(t.get(), rm, minL, maxL, time, p));
            }
        } else if (tierObj != null) {
            p.add("tiers: not a map");
        }
        MechanicsModel.AfterClear after = null;
        Object afterObj = m.get("afterClear");
        if (afterObj instanceof Map<?, ?> am) {
            after = new MechanicsModel.AfterClear(num(am, "rewardNpc", 0, p), num(am, "rewardMap", 0, p),
                    num(am, "bonusMap", 0, p), num(am, "exitNpc", 0, p), num(am, "bonusMinSeconds", 60, p),
                    num(am, "bonusMaxSeconds", 120, p));
            if (after.rewardNpc() <= 0 || after.rewardMap() <= 0) {
                p.add("afterClear: needs rewardNpc and rewardMap");
            }
            if (after.bonusMap() > 0 && after.exitNpc() <= 0) {
                p.add("afterClear: a bonusMap needs the exitNpc that sends members out");
            }
            if (after.bonusMinSeconds() < 0 || after.bonusMaxSeconds() < after.bonusMinSeconds()) {
                p.add("afterClear: need 0 <= bonusMinSeconds <= bonusMaxSeconds");
            }
        } else if (afterObj != null) {
            p.add("afterClear: not a map");
        }
        if (id == null || name == null || kind == null) {
            return null;
        }
        return new Content(id, name, kind, script, recruit, exit, minP, maxP, minL, maxL, time, List.copyOf(sources),
                List.copyOf(stages), Map.copyOf(tiers), after, opt(m, "flag"), num(m, "entryNpc", 0, p));
    }

    private static TierRules readTier(Tier t, Map<?, ?> m, int minL, int maxL, int time, List<String> p) {
        String at = "tier " + t + ": ";
        TierRules r = new TierRules(t, num(m, "minLevel", minL, p), num(m, "maxLevel", maxL, p),
                num(m, "mobLevelBonus", 0, p), dbl(m, "mobHpMultiplier", 1.0, p),
                dbl(m, "mobDamageMultiplier", 1.0, p), num(m, "timeLimitMinutes", time, p),
                dbl(m, "botMistakeFactor", 1.0, p));
        if (r.mobHpMultiplier() <= 0 || r.mobDamageMultiplier() <= 0 || r.botMistakeFactor() < 0) {
            p.add(at + "multipliers must be positive");
        }
        if (r.maxLevel() < r.minLevel()) {
            p.add(at + "maxLevel below minLevel");
        }
        return r;
    }

    private static Stage readStage(Map<?, ?> m, int maxPlayers, List<String> p) {
        int index = num(m, "stage", 0, p);
        String at = "stage " + index + ": ";
        int mapId = num(m, "map", 0, p);
        if (index < 1 || mapId <= 0) {
            p.add(at + "needs stage >= 1 and a map id");
            return null;
        }
        String name = String.valueOf(m.get("name") == null ? "Stage " + index : m.get("name"));
        int npc = num(m, "npc", 0, p);
        String solver = m.get("solver") == null ? null : String.valueOf(m.get("solver")).trim();
        if (solver == null || solver.isEmpty()) {
            p.add(at + "no solver");
        }

        List<Role> roles = new ArrayList<>();
        Set<String> roleIds = new HashSet<>(IMPLICIT_ROLES);
        int minSum = 0;
        for (Object o : list(m, "roles")) {
            if (!(o instanceof Map<?, ?> rm)) {
                p.add(at + "a role that is not a map");
                continue;
            }
            String rid = str(rm, "id", p, true);
            Who who = enumOf(Who.class, rm.get("who") == null ? "ANY" : String.valueOf(rm.get("who")), at + "who", p);
            Count min = Count.parse(rm.get("min") == null ? "1" : String.valueOf(rm.get("min"))).orElse(null);
            Count max = Count.parse(rm.get("max") == null ? String.valueOf(maxPlayers) : String.valueOf(rm.get("max"))).orElse(null);
            if (rid == null || who == null || min == null || max == null || min.needsAnswer() || max.needsAnswer()) {
                p.add(at + "role " + rid + ": needs id, who and numeric (or party / party-1) min and max");
                continue;
            }
            Role role = new Role(rid, who, min, max, opt(rm, "note"));
            int lo = role.minFor(maxPlayers);
            int hi = role.maxFor(maxPlayers);
            if (hi < lo) {
                p.add(at + "role " + rid + ": max below min");
            }
            if (who == Who.LEADER && hi > 1) {
                p.add(at + "role " + rid + ": only one member is the leader");
            }
            if (!roleIds.add(rid)) {
                p.add(at + "duplicate role " + rid);
            }
            minSum += lo;
            roles.add(role);
        }
        if (minSum > maxPlayers) {
            p.add(at + "roles need " + minSum + " members, more than maxPlayers " + maxPlayers);
        }

        List<Answer> answers = new ArrayList<>();
        Set<Integer> answerIds = new HashSet<>();
        for (Object o : list(m, "answers")) {
            if (!(o instanceof Map<?, ?> am)) {
                p.add(at + "an answer that is not a map");
                continue;
            }
            int aid = num(am, "id", -1, p);
            int value = num(am, "answer", -1, p);
            if (aid < 0 || value < 0 || !answerIds.add(aid)) {
                p.add(at + "answer " + aid + ": needs a unique id and a non-negative answer");
                continue;
            }
            answers.add(new Answer(aid, opt(am, "question"), value));
        }

        List<Objective> objectives = new ArrayList<>();
        Set<String> objIds = new HashSet<>();
        for (Object o : list(m, "objectives")) {
            if (!(o instanceof Map<?, ?> om)) {
                p.add(at + "an objective that is not a map");
                continue;
            }
            String oid = str(om, "id", p, true);
            ObjectiveKind kind = enumOf(ObjectiveKind.class, String.valueOf(om.get("kind")), at + "objective kind", p);
            String role = om.get("role") == null ? "all" : String.valueOf(om.get("role"));
            Optional<Count> count = Optional.empty();
            if (om.get("count") != null) {
                count = Count.parse(String.valueOf(om.get("count")));
                if (count.isEmpty()) {
                    p.add(at + "objective " + oid + ": bad count " + om.get("count"));
                } else if (count.get().needsAnswer() && answers.isEmpty()) {
                    p.add(at + "objective " + oid + ": count is 'answer' but the stage has no answers");
                }
            }
            if (!roleIds.contains(role)) {
                p.add(at + "objective " + oid + ": unknown role " + role);
            }
            if (oid != null && !objIds.add(oid)) {
                p.add(at + "duplicate objective " + oid);
            }
            int item = num(om, "item", 0, p);
            int mob = num(om, "mob", 0, p);
            int onpc = num(om, "npc", 0, p);
            if (kind != null) {
                switch (kind) {
                    case KILL_FOR_ITEM -> require(item > 0 && mob > 0 && count.isPresent(), p,
                            at + "objective " + oid + ": KILL_FOR_ITEM needs item, mob and count");
                    case HAND_IN, GIVE_TO_LEADER, COLLECT -> require(item > 0 && count.isPresent(), p,
                            at + "objective " + oid + ": " + kind + " needs item and count");
                    case PLACE_ITEM -> require(item > 0 && count.isPresent()
                                    && (om.get("reactor") != null || om.get("at") != null), p,
                            at + "objective " + oid + ": PLACE_ITEM needs item, count and a reactor or an at point");
                    case TALK_NPC -> require(onpc > 0 || npc > 0, p, at + "objective " + oid + ": TALK_NPC needs an npc");
                    default -> {
                    }
                }
                if (kind == ObjectiveKind.HAND_IN && onpc <= 0 && npc <= 0) {
                    p.add(at + "objective " + oid + ": HAND_IN needs an npc to hand in to");
                }
            }
            if (oid != null && kind != null) {
                int[] spot = atPoint(om.get("at"));
                objectives.add(new Objective(oid, kind, role, count, item, mob, onpc > 0 ? onpc : npc, opt(om, "note"),
                        spot[0], spot[1], opt(om, "reactor")));
            }
        }
        if (objectives.isEmpty()) {
            p.add(at + "no objectives");
        }

        List<Position> positions = new ArrayList<>();
        for (Object o : list(m, "positions")) {
            if (!(o instanceof Map<?, ?> pm)) {
                p.add(at + "a position that is not a map");
                continue;
            }
            Position pos = new Position(String.valueOf(pm.get("id")), num(pm, "x", 0, p), num(pm, "y", 0, p),
                    num(pm, "w", 0, p), num(pm, "h", 0, p), Boolean.parseBoolean(String.valueOf(pm.get("rope"))));
            if (pos.w() <= 0 || pos.h() <= 0) {
                p.add(at + "position " + pos.id() + ": w and h must be positive");
            }
            positions.add(pos);
        }

        List<ItemUse> items = new ArrayList<>();
        for (Object o : list(m, "items")) {
            if (!(o instanceof Map<?, ?> im)) {
                p.add(at + "an item that is not a map");
                continue;
            }
            int iid = num(im, "id", 0, p);
            if (iid <= 0) {
                p.add(at + "item without an id");
                continue;
            }
            items.add(new ItemUse(iid, opt(im, "name"), Boolean.parseBoolean(String.valueOf(im.get("cleanup")))));
        }
        Set<Integer> declared = new HashSet<>();
        items.forEach(i -> declared.add(i.itemId()));
        for (Objective o : objectives) {
            if (o.itemId() > 0 && !declared.contains(o.itemId())) {
                p.add(at + "objective " + o.id() + " uses item " + o.itemId() + " that is not under items:");
            }
        }

        List<BossPhase> phases = new ArrayList<>();
        int lastTo = 101;
        for (Object o : list(m, "phases")) {
            if (!(o instanceof Map<?, ?> bm)) {
                p.add(at + "a phase that is not a map");
                continue;
            }
            int from = num(bm, "hpFromPct", 100, p);
            int to = num(bm, "hpToPct", 0, p);
            if (from > 100 || to < 0 || to >= from || from >= lastTo) {
                p.add(at + "phase " + bm.get("id") + ": HP bands must run down from 100 to 0 without overlap");
            }
            lastTo = to + 1;
            List<String> actions = new ArrayList<>();
            list(bm, "actions").forEach(a -> actions.add(String.valueOf(a)));
            phases.add(new BossPhase(String.valueOf(bm.get("id")), from, to, List.copyOf(actions)));
        }

        Clear clear = null;
        if (m.get("clear") instanceof Map<?, ?> cm && cm.get("eventProperty") != null) {
            String prop = String.valueOf(cm.get("eventProperty")).trim();
            String eq = cm.get("equals") == null ? null : String.valueOf(cm.get("equals")).trim();
            if (!prop.isEmpty()) {
                clear = new Clear(prop, eq == null || eq.isEmpty() ? null : eq);
            }
        } else if (m.get("clear") instanceof Map<?, ?> cm && "true".equalsIgnoreCase(String.valueOf(cm.get("instance")))) {
            // a stage whose script writes no property and only calls eim.clearPQ(): the instance's own flag is the clear
            clear = Clear.instance();
        } else if (m.get("clear") instanceof Map<?, ?> cm && "true".equalsIgnoreCase(String.valueOf(cm.get("mapEmpty")))) {
            // a stage whose script never says "done": it is over when the map has nothing left to kill (Ellin PQ stage 1)
            clear = Clear.emptyMap();
        } else if (m.get("clear") instanceof Map<?, ?> cm && cm.get("reactorState") instanceof Map<?, ?> rs
                && rs.get("id") != null && rs.get("state") != null) {
            // the reactor is the authority: Ellin PQ stage 2 clears when the spine reactor reaches state 4
            int rid = num(rs, "id", -1, p);
            int rstate = num(rs, "state", -1, p);
            if (rid > 0 && rstate >= 0) {
                clear = Clear.ofReactorState(rid, rstate);
            }
        }
        if (clear == null && m.get("clear") instanceof Map<?, ?> pm && pm.get("partyOnMap") != null) {
            // the party's arrival is the clear: the script warps the team and writes nothing (Ellin PQ's marble stage)
            int target = num(pm, "partyOnMap", -1, p);
            if (target > 0) {
                clear = Clear.ofPartyOnMap(target);
            } else {
                p.add(at + "clear: partyOnMap must be a map id");
            }
        }
        if (clear == null) {
            p.add(at + "no clear: { eventProperty: ... } (+ optional equals: value), or { instance: true },"
                    + " or { mapEmpty: true }, or { reactorState: { id: N, state: M } },"
                    + " or { partyOnMap: mapId }");
        }
        String answerProperty = m.get("answerProperty") == null ? null : String.valueOf(m.get("answerProperty")).trim();
        List<List<Integer>> combos = new ArrayList<>();
        int ones = -1;
        for (Object o : list(m, "combos")) {
            if (!(o instanceof List<?> cl)) {
                p.add(at + "a combo that is not a list of 0/1");
                continue;
            }
            List<Integer> mask = new ArrayList<>();
            for (Object v : cl) {
                String t = String.valueOf(v).trim();
                if (!t.equals("0") && !t.equals("1")) {
                    p.add(at + "combo " + combos.size() + ": only 0 and 1, not " + t);
                }
                mask.add(t.equals("1") ? 1 : 0);
            }
            if (mask.size() != positions.size()) {
                p.add(at + "combo " + combos.size() + ": " + mask.size() + " entries for " + positions.size()
                        + " positions");
            }
            int n = (int) mask.stream().filter(v -> v == 1).count();
            if (ones >= 0 && n != ones) {
                p.add(at + "combo " + combos.size() + ": every combo occupies the same number of positions");
            }
            ones = n;
            combos.add(List.copyOf(mask));
        }
        if (!combos.isEmpty() && (answerProperty == null || answerProperty.isEmpty())) {
            p.add(at + "combos need the answerProperty the stage script keeps the answer in");
        }
        if ("combination".equals(solver) && combos.isEmpty()) {
            p.add(at + "the combination solver needs combos and positions");
        }
        List<Integer> mobs = new ArrayList<>();
        for (Object o : list(m, "mobs")) {
            try {
                mobs.add(Integer.parseInt(String.valueOf(o).trim()));
            } catch (NumberFormatException e) {
                p.add(at + "mobs: not a mob id: " + o);
            }
        }
        String nextPortal = m.get("nextPortal") == null ? null : String.valueOf(m.get("nextPortal")).trim();
        String summonProperty = m.get("summonProperty") == null ? null
                : String.valueOf(m.get("summonProperty")).trim();
        int killTarget = 0;
        if (m.get("killTarget") != null) {
            try {
                killTarget = Integer.parseInt(String.valueOf(m.get("killTarget")).trim());
            } catch (NumberFormatException e) {
                p.add(at + "killTarget: not a mob id: " + m.get("killTarget"));
            }
        }
        // 1.6.4 boss lane: a boss room's own clock. 0 = the row's timeLimitMinutes counts from the run's start.
        int stageMinutes = num(m, "timeLimitMinutes", 0, p);
        // a room whose fight is behind a reactor nobody has to click by hand (Horntail's 2401000); 0 = no reactor
        int hitReactor = num(m, "hitReactor", 0, p);
        // the other maps this stage legitimately spans (LudiMaze PQ: fifteen, one of which each member starts on)
        List<Integer> maps = new ArrayList<>();
        for (Object o : list(m, "maps")) {
            try {
                int other = Integer.parseInt(String.valueOf(o).trim());
                if (other > 0 && other != mapId) {
                    maps.add(other);
                } else if (other == mapId) {
                    p.add(at + "maps: names the stage's own map " + mapId + " again (drop it)");
                }
            } catch (NumberFormatException e) {
                p.add(at + "maps: \"" + o + "\" is not a map id");
            }
        }
        // P15 (ticket 164): how long a boss room may sit with nothing to hit before the run gives up on it. 0 = the
        // solver's own default (30 s), which is what every row that does not name one keeps.
        long graceMs = num(m, "graceMs", 0, p);
        if (graceMs < 0) {
            p.add(at + "graceMs: must not be negative");
            graceMs = 0;
        }
        // a stage that sends the run back instead of forward: BossRush's waves repeat until the row's clock runs out
        int loopTo = num(m, "loopTo", 0, p);
        if (loopTo < 0 || loopTo > index) {
            p.add(at + "loopTo: must be a stage index at or before this one (" + index + ")");
            loopTo = 0;
        }
        return new Stage(index, mapId, name, npc, solver, List.copyOf(roles), List.copyOf(objectives),
                List.copyOf(positions), List.copyOf(items), List.copyOf(answers), List.copyOf(phases), clear,
                answerProperty, List.copyOf(combos), List.copyOf(mobs), nextPortal,
                summonProperty == null || summonProperty.isEmpty() ? null : summonProperty, killTarget, stageMinutes,
                hitReactor, List.copyOf(maps), graceMs, loopTo);
    }

    // ---- small readers (yamlbeans gives every scalar as a String) ----

    private static void require(boolean ok, List<String> p, String msg) {
        if (!ok) {
            p.add(msg);
        }
    }

    private static List<?> list(Map<?, ?> m, String key) {
        Object o = m.get(key);
        return o instanceof List<?> l ? l : List.of();
    }

    private static String opt(Map<?, ?> m, String key) {
        Object o = m.get(key);
        return o == null ? "" : String.valueOf(o);
    }

    private static String str(Map<?, ?> m, String key, List<String> p, boolean required) {
        Object o = m.get(key);
        if (o == null || String.valueOf(o).isBlank()) {
            if (required) {
                p.add("missing " + key);
            }
            return null;
        }
        return String.valueOf(o).trim();
    }

    /** {@code at: [x, y]} or {@code at: "x, y"}: a stand spot; {0, 0} when it is not there or unreadable. */
    private static int[] atPoint(Object o) {
        if (o instanceof List<?> l && l.size() >= 2) {
            return new int[]{parseInt(String.valueOf(l.get(0)), 0), parseInt(String.valueOf(l.get(1)), 0)};
        }
        if (o instanceof String str) {
            String[] parts = str.split("[,;]");
            if (parts.length >= 2) {
                return new int[]{parseInt(parts[0], 0), parseInt(parts[1], 0)};
            }
        }
        return new int[]{0, 0};
    }

    private static int parseInt(String s, int dflt) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static int num(Map<?, ?> m, String key, int dflt, List<String> p) {
        Object o = m.get(key);
        if (o == null) {
            return dflt;
        }
        try {
            return Integer.parseInt(String.valueOf(o).trim().replace("_", ""));
        } catch (NumberFormatException e) {
            p.add(key + ": not a whole number: " + o);
            return dflt;
        }
    }

    private static double dbl(Map<?, ?> m, String key, double dflt, List<String> p) {
        Object o = m.get(key);
        if (o == null) {
            return dflt;
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            p.add(key + ": not a number: " + o);
            return dflt;
        }
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String s, String what, List<String> p) {
        if (s == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            p.add(what + ": unknown value " + s);
            return null;
        }
    }
}
