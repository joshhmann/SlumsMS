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
import soloMapling.mechanics.MechanicsModel.OnTimeLimit;
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
        } else if (stages.get(stages.size() - 1).onTimeLimit() == OnTimeLimit.ADVANCE) {
            p.add("stage " + stages.get(stages.size() - 1).index()
                    + ": onTimeLimit: advance on the last stage has no next stage to go on to");
        }
        checkBranchTargets(stages, p);
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
        List<MechanicsModel.Seat> composition = readComposition(m, minP, maxP, minL, maxL, p);
        MechanicsModel.Queue queue = readQueue(m, script, num(m, "entryNpc", 0, p), p);
        return new Content(id, name, kind, script, recruit, exit, minP, maxP, minL, maxL, time, List.copyOf(sources),
                List.copyOf(stages), Map.copyOf(tiers), after, opt(m, "flag"), num(m, "entryNpc", 0, p),
                List.copyOf(composition), queue);
    }

    /**
     * {@code queue:} - the content is entered through the event's world queue: {@code {register: [answers], join:
     * [answers], closedProperty, closedEquals, waitMinutes}}. It needs the NPC that answers them ({@code entryNpc}) and
     * the event script whose queue it is, so a row that names a queue and not those is refused here.
     */
    private static MechanicsModel.Queue readQueue(Map<?, ?> m, String script, int entryNpc, List<String> p) {
        Object raw = m.get("queue");
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> qm)) {
            p.add("queue: not a map");
            return null;
        }
        List<Integer> register = answers(qm.get("register"), "queue: register", p);
        List<Integer> join = answers(qm.get("join"), "queue: join", p);
        String property = opt(qm, "closedProperty").trim();
        String equals = opt(qm, "closedEquals").trim();
        int wait = num(qm, "waitMinutes", 0, p);
        if (register.isEmpty() || join.isEmpty()) {
            p.add("queue: needs register and join, each the answers that take that way through the entry NPC");
        }
        if (property.isEmpty() || equals.isEmpty()) {
            p.add("queue: needs closedProperty and closedEquals, the event property that says the join window is over");
        }
        if (wait < 0) {
            p.add("queue: waitMinutes must not be negative");
        }
        if (entryNpc <= 0) {
            p.add("queue: needs the entryNpc that answers register and join");
        }
        if (script == null || script.isBlank()) {
            p.add("queue: needs the eventScript whose world queue it is");
        }
        return new MechanicsModel.Queue(List.copyOf(register), List.copyOf(join), property, equals, wait);
    }

    private static List<Integer> answers(Object raw, String what, List<String> p) {
        List<Integer> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> l)) {
            p.add(what + ": not a list of numbers");
            return out;
        }
        for (Object o : l) {
            try {
                out.add(Integer.parseInt(String.valueOf(o).trim()));
            } catch (NumberFormatException e) {
                p.add(what + ": \"" + o + "\" is not a number");
            }
        }
        return out;
    }

    /**
     * {@code composition:} - the seats the party must fill: {@code {id, min, minLevel, maxLevel, note}}. A seat nobody
     * the row admits could sit in, or more members than the party holds, is a row that could never start: refused here.
     */
    private static List<MechanicsModel.Seat> readComposition(Map<?, ?> m, int minP, int maxP, int minL, int maxL,
                                                             List<String> p) {
        List<MechanicsModel.Seat> out = new ArrayList<>();
        Object raw = m.get("composition");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> seats)) {
            p.add("composition: not a list");
            return out;
        }
        Set<String> ids = new HashSet<>();
        int needed = 0;
        for (Object o : seats) {
            if (!(o instanceof Map<?, ?> sm)) {
                p.add("composition: a seat that is not a map");
                continue;
            }
            String sid = str(sm, "id", p, true);
            int min = num(sm, "min", 1, p);
            int lo = num(sm, "minLevel", 1, p);
            int hi = num(sm, "maxLevel", 0, p);
            String at = "composition seat " + sid + ": ";
            if (sid == null) {
                continue;
            }
            if (!ids.add(sid)) {
                p.add(at + "duplicate seat");
            }
            if (min < 1) {
                p.add(at + "min must be 1 or more");
            }
            if (lo < 1 || (hi != 0 && hi < lo)) {
                p.add(at + "levels: need 1 <= minLevel and (maxLevel 0, or at least minLevel)");
            }
            if ((hi != 0 && hi < minL) || lo > maxL) {
                p.add(at + "no level the row admits (" + minL + "-" + maxL + ") fits this seat");
            }
            needed += Math.max(min, 0);
            out.add(new MechanicsModel.Seat(sid, lo, hi, min, opt(sm, "note")));
        }
        if (needed > maxP) {
            p.add("composition: the seats need " + needed + " members, more than maxPlayers " + maxP);
        }
        return out;
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
        // 1.6.4 boss rows: a room whose targets are the map's own life may stand empty while they arrive, and the row
        // says how long that is; 0 = no expectation (an empty room is the solver's 30 s grace and then an abandon).
        int spawnWait = num(m, "spawnWaitSeconds", 0, p);
        require(spawnWait >= 0, p, at + "spawnWaitSeconds must not be negative: " + spawnWait);
        require(spawnWait == 0 || !mobs.isEmpty(), p,
                at + "spawnWaitSeconds needs mobs: it is a statement about when the room's targets appear");
        // 1.6.4 boss lane: a boss room's own clock. 0 = the row's timeLimitMinutes counts from the run's start.
        // onTimeLimit (below) says what the run does when this clock runs out.
        int stageMinutes = num(m, "timeLimitMinutes", 0, p);
        // a room whose fight is behind reactors nobody has to click by hand (Horntail's 2401000, Scarga's two), in the
        // order they must be clicked. A list, not a scalar: a room with two of them must have both opened before the
        // fight it is about starts, and one step per reactor is what an ordered list says.
        List<Integer> hitReactors = new ArrayList<>();
        for (Object o : list(m, "hitReactors")) {
            try {
                hitReactors.add(Integer.parseInt(String.valueOf(o).trim()));
            } catch (NumberFormatException e) {
                p.add(at + "hitReactors: not a reactor id: " + o);
            }
        }
        require(hitReactors.stream().distinct().count() == hitReactors.size(), p,
                at + "hitReactors: a reactor is clicked once per stage, so listing it twice says nothing new");
        // 1.6.4 boss lane, round 3: what the run must have in hand before this stage's first step (Latanica's door,
        // scripts/portal/captinsg00.js:4, refuses anyone without 4000381).
        List<Integer> entryItems = new ArrayList<>();
        for (Object o : list(m, "entryItems")) {
            try {
                entryItems.add(Integer.parseInt(String.valueOf(o).trim()));
            } catch (NumberFormatException e) {
                p.add(at + "entryItems: not an item id: " + o);
            }
        }
        require(entryItems.stream().allMatch(id -> id > 0), p, at + "entryItems: item ids are positive");
        require(entryItems.stream().distinct().count() == entryItems.size(), p,
                at + "entryItems: one of each is enough, so listing an item twice says nothing new");
        // the party-quest rows name the same list with one entry (Ellin PQ's spine and its stone, Magatia's beaker);
        // there is one spelling of the fact, so a row that still says hitReactor: is told where it went
        if (m.get("hitReactor") != null) {
            p.add(at + "hitReactor: the scalar spelling is gone; write hitReactors: [<id>]");
        }
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
        // realruns: a stage that sends the run back instead of forward - BossRush's waves repeat until the
        // row's clock runs out. 0 = advance, which is every other row on this branch.
        int loopTo = num(m, "loopTo", 0, p);
        if (loopTo < 0 || loopTo > index) {
            p.add(at + "loopTo: must be a stage index at or before this one (" + index + ")");
            loopTo = 0;
        }
        // what the run does when this stage's own clock runs out: end it (the scripts' end(eim), the default) or go on
        OnTimeLimit onTimeLimit = OnTimeLimit.END;
        if (m.get("onTimeLimit") != null) {
            OnTimeLimit outcome = enumOf(OnTimeLimit.class, String.valueOf(m.get("onTimeLimit")), at + "onTimeLimit", p);
            if (outcome != null) {
                onTimeLimit = outcome;
                if (outcome == OnTimeLimit.ADVANCE && stageMinutes <= 0) {
                    p.add(at + "onTimeLimit: advance needs the stage's own timeLimitMinutes (there is no clock to run out)");
                }
                if (outcome == OnTimeLimit.ADVANCE && loopTo > 0) {
                    p.add(at + "onTimeLimit: advance and loopTo both say where the run goes next: pick one");
                }
            }
        }
        // the conditional ways on from this stage: the first whose condition holds when the stage clears wins
        List<MechanicsModel.Branch> branches = readBranches(m, at, p);
        return new Stage(index, mapId, name, npc, solver, List.copyOf(roles), List.copyOf(objectives),
                List.copyOf(positions), List.copyOf(items), List.copyOf(answers), List.copyOf(phases), clear,
                answerProperty, List.copyOf(combos), List.copyOf(mobs), nextPortal,
                summonProperty == null || summonProperty.isEmpty() ? null : summonProperty, spawnWait, stageMinutes,
                List.copyOf(hitReactors), List.copyOf(entryItems), List.copyOf(maps), loopTo, onTimeLimit,
                List.copyOf(branches));
    }

    /**
     * {@code branches:} on a stage: a list of {@code {when: {...}, goto: N | end}}. {@code when:} is {@code
     * {eventProperty: P}} (present), {@code {eventProperty: P, equals: V}} or {@code {reactor: name | id, state: N}}, and
     * may be left out (always holds, so it must be the last entry). Whether {@code goto:} names a stage the row has is
     * checked once every stage is read ({@link #checkBranchTargets}).
     */
    private static List<MechanicsModel.Branch> readBranches(Map<?, ?> m, String at, List<String> p) {
        List<MechanicsModel.Branch> out = new ArrayList<>();
        Object raw = m.get("branches");
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> entries)) {
            p.add(at + "branches: not a list");
            return out;
        }
        boolean unconditionalSeen = false;
        for (Object o : entries) {
            if (!(o instanceof Map<?, ?> bm)) {
                p.add(at + "branches: an entry that is not a map");
                continue;
            }
            String property = null;
            String equals = null;
            String reactor = null;
            int state = -1;
            boolean ok = true;
            if (bm.get("when") != null) {
                if (!(bm.get("when") instanceof Map<?, ?> wm)) {
                    p.add(at + "branches: when is not a map");
                    continue;
                }
                property = opt(wm, "eventProperty").trim();
                reactor = opt(wm, "reactor").trim();
                property = property.isEmpty() ? null : property;
                reactor = reactor.isEmpty() ? null : reactor;
                if (property == null && reactor == null) {
                    p.add(at + "branches: when needs an eventProperty or a reactor with a state");
                    ok = false;
                } else if (property != null && reactor != null) {
                    p.add(at + "branches: when: pick one of eventProperty and reactor, not both");
                    ok = false;
                } else if (property != null) {
                    String eq = opt(wm, "equals").trim();
                    equals = eq.isEmpty() ? null : eq;
                } else if (wm.get("state") == null) {
                    p.add(at + "branches: when: a reactor needs its state");
                    ok = false;
                } else {
                    state = num(wm, "state", -1, p);
                    if (state < 0) {
                        p.add(at + "branches: when: state must be 0 or more");
                        ok = false;
                    }
                }
            }
            String target = opt(bm, "goto").trim();
            int stage;
            if (target.isEmpty()) {
                p.add(at + "branches: an entry needs goto (a stage number, or end)");
                continue;
            } else if (target.equalsIgnoreCase("end")) {
                stage = MechanicsModel.Branch.END;
            } else {
                try {
                    stage = Integer.parseInt(target);
                } catch (NumberFormatException e) {
                    p.add(at + "branches: goto " + target + " is neither a stage number nor end");
                    continue;
                }
                if (stage < 1) {
                    p.add(at + "branches: goto " + target + " is not a stage number");
                    continue;
                }
            }
            if (unconditionalSeen) {
                p.add(at + "branches: an entry after the one with no when is unreachable");
            }
            if (!ok) {
                continue;
            }
            MechanicsModel.Branch b = new MechanicsModel.Branch(property, equals, reactor, state, stage);
            unconditionalSeen |= b.unconditional();
            out.add(b);
        }
        return out;
    }

    /** Every {@code goto:} names a stage the row has, and none names its own stage. */
    private static void checkBranchTargets(List<Stage> stages, List<String> p) {
        Set<Integer> have = new HashSet<>();
        stages.forEach(s -> have.add(s.index()));
        for (Stage s : stages) {
            for (MechanicsModel.Branch b : s.branches()) {
                if (b.end()) {
                    continue;
                }
                if (b.stage() == s.index()) {
                    p.add("stage " + s.index() + ": branches: goto " + b.stage() + " is the stage itself");
                } else if (!have.contains(b.stage())) {
                    p.add("stage " + s.index() + ": branches: goto " + b.stage() + " is not a stage of this row (1-"
                            + stages.size() + ")");
                }
            }
        }
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
