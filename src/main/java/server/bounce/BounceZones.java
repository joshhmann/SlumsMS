package server.bounce;

import com.esotericsoftware.yamlbeans.YamlReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where MapleRoot's universal bounce skills are blocked (and, per category, the thieves' real Flash Jump).
 *
 * <p>Every character gets Ninja Bounce 1050 / 10001050 / 20001050 and Vertical Bounce 1054 / 10001054 / 20001054 at
 * login ({@code PlayerLoggedinHandler}). The owner keeps them everywhere except the maps listed here, in
 * {@value #DATA_PATH} (resolved by {@link soloMapling.DataFiles}): named categories, each with a description, an
 * {@code enabled} flag, {@code block_flash_jump} and map entries -- single ids, inclusive {@code a-b} ranges, or a
 * region written as a 9-character id with trailing x's ({@code 2800xxxxx}).
 *
 * <p>The client enforces it (slums.dll refuses the skills in its DoActiveSkill hook) from the set this server sends
 * at login ({@link BounceZonePackets}); {@link BounceZoneService} is the server backstop and the push. An unreadable
 * file gives {@link #EMPTY} (nothing blocked), never a failed login; a bad entry or category is skipped and logged.
 */
public final class BounceZones {

    private static final Logger log = LoggerFactory.getLogger(BounceZones.class);

    static final String DATA_PATH = "src/main/java/soloMapling/BounceZones/bounce-zones.yaml";

    public static final int FLASH_JUMP_HERMIT = 4111006;
    public static final int FLASH_JUMP_NIGHT_WALKER = 14101004;
    public static final int[] NINJA_BOUNCE = {1050, 10001050, 20001050};
    public static final int[] VERTICAL_BOUNCE = {1054, 10001054, 20001054};

    public static final BounceZones EMPTY = new BounceZones(List.of());

    private static final Set<String> CATEGORY_KEYS = Set.of("name", "description", "enabled", "block_flash_jump", "maps");
    private static final Pattern SINGLE = Pattern.compile("\\d{1,9}");
    private static final Pattern RANGE = Pattern.compile("(\\d{1,9})\\s*-\\s*(\\d{1,9})");
    private static final Pattern REGION = Pattern.compile("(\\d+)([xX]+)");

    /** Inclusive map-id range. */
    public record MapRange(int first, int last) {
        public MapRange {
            if (first < 0 || last < first) {
                throw new IllegalArgumentException("bad range " + first + "-" + last);
            }
        }

        public boolean contains(int mapId) {
            return mapId >= first && mapId <= last;
        }

        public long size() {
            return (long) last - first + 1;
        }

        @Override
        public String toString() {
            return first == last ? Integer.toString(first) : first + "-" + last;
        }
    }

    public record Category(String name, String description, boolean enabled, boolean blockFlashJump,
                           List<MapRange> ranges) {
        public Category {
            ranges = List.copyOf(ranges);
        }

        public boolean contains(int mapId) {
            for (MapRange r : ranges) {
                if (r.contains(mapId)) {
                    return true;
                }
            }
            return false;
        }

        /** How many map ids the entries cover (a region counts every id in it). */
        public long idCount() {
            long n = 0;
            for (MapRange r : merge(ranges)) {
                n += r.size();
            }
            return n;
        }
    }

    private static volatile BounceZones instance;

    private final List<Category> categories;
    private final BounceImpulses impulses;
    private final List<MapRange> bounceRanges;
    private final List<MapRange> flashJumpRanges;
    private final int[] bounceFirst;
    private final int[] flashJumpFirst;

    BounceZones(List<Category> categories) {
        this(categories, BounceImpulses.DEFAULT);
    }

    BounceZones(List<Category> categories, BounceImpulses impulses) {
        this.categories = List.copyOf(categories);
        this.impulses = impulses;
        List<MapRange> bounce = new ArrayList<>();
        List<MapRange> fj = new ArrayList<>();
        for (Category c : this.categories) {
            if (c.enabled()) {
                bounce.addAll(c.ranges());
                if (c.blockFlashJump()) {
                    fj.addAll(c.ranges());
                }
            }
        }
        this.bounceRanges = merge(bounce);
        this.flashJumpRanges = merge(fj);
        this.bounceFirst = bounceRanges.stream().mapToInt(MapRange::first).toArray();
        this.flashJumpFirst = flashJumpRanges.stream().mapToInt(MapRange::first).toArray();
    }

    // ---- skills ----

    public static boolean isUniversalBounce(int skillId) {
        return contains(NINJA_BOUNCE, skillId) || contains(VERTICAL_BOUNCE, skillId);
    }

    public static boolean isFlashJump(int skillId) {
        return skillId == FLASH_JUMP_HERMIT || skillId == FLASH_JUMP_NIGHT_WALKER;
    }

    /** The Ninja Bounce of a job family ({@code Character.getJobType()}: 0 adventurer, 1 Cygnus, 2 Aran). */
    public static int nativeBounce(int jobType) {
        return jobType >= 0 && jobType < NINJA_BOUNCE.length ? NINJA_BOUNCE[jobType] : NINJA_BOUNCE[0];
    }

    private static boolean contains(int[] ids, int id) {
        for (int i : ids) {
            if (i == id) {
                return true;
            }
        }
        return false;
    }

    // ---- queries ----

    public List<Category> categories() {
        return categories;
    }

    /** The bounce strengths from the {@code impulses:} section (defaults when it is absent). */
    public BounceImpulses impulses() {
        return impulses;
    }

    public boolean blocksBounce(int mapId) {
        return find(bounceFirst, bounceRanges, mapId);
    }

    public boolean blocksFlashJump(int mapId) {
        return find(flashJumpFirst, flashJumpRanges, mapId);
    }

    /** Whether this skill is refused on this map: a universal bounce, or Flash Jump where its category says so. */
    public boolean refuses(int skillId, int mapId) {
        if (isUniversalBounce(skillId)) {
            return blocksBounce(mapId);
        }
        return isFlashJump(skillId) && blocksFlashJump(mapId);
    }

    /** The enabled category that blocks the map, if any (the first in file order). */
    public Optional<Category> blockedBy(int mapId) {
        return categories.stream().filter(c -> c.enabled() && c.contains(mapId)).findFirst();
    }

    /** Every category (enabled or not) that lists the map. */
    public List<Category> listing(int mapId) {
        return categories.stream().filter(c -> c.contains(mapId)).toList();
    }

    /** The effective bounce block: merged, sorted ranges of every enabled category. */
    public List<MapRange> bounceRanges() {
        return bounceRanges;
    }

    /** The effective Flash Jump block: enabled categories with {@code block_flash_jump}. */
    public List<MapRange> flashJumpRanges() {
        return flashJumpRanges;
    }

    private static boolean find(int[] firsts, List<MapRange> ranges, int mapId) {
        int i = Arrays.binarySearch(firsts, mapId);
        if (i < 0) {
            i = -i - 2;
        }
        return i >= 0 && ranges.get(i).contains(mapId);
    }

    static List<MapRange> merge(List<MapRange> in) {
        List<MapRange> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingInt(MapRange::first));
        List<MapRange> out = new ArrayList<>();
        for (MapRange r : sorted) {
            if (!out.isEmpty() && (long) r.first() <= (long) out.get(out.size() - 1).last() + 1) {
                MapRange last = out.remove(out.size() - 1);
                out.add(new MapRange(last.first(), Math.max(last.last(), r.last())));
            } else {
                out.add(r);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // ---- loading ----

    public static BounceZones getInstance() {
        BounceZones z = instance;
        if (z == null) {
            synchronized (BounceZones.class) {
                z = instance;
                if (z == null) {
                    z = load(soloMapling.DataFiles.path(DATA_PATH));
                    instance = z;
                }
            }
        }
        return z;
    }

    /** Re-reads the file and makes it current. Returns the new zones. */
    public static BounceZones reload() {
        return reload(soloMapling.DataFiles.path(DATA_PATH));
    }

    static BounceZones reload(Path file) {
        BounceZones z = load(file);
        instance = z;
        return z;
    }

    /** Tests: use {@code zones} instead of the file ({@code null} reloads it on next use). */
    public static void useForTest(BounceZones zones) {
        instance = zones;
    }

    static BounceZones load(Path file) {
        if (!Files.isRegularFile(file)) {
            log.error("Bounce zones: {} not found, bounces are allowed everywhere", file);
            return EMPTY;
        }
        try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<String> problems = new ArrayList<>();
            BounceZones zones = parse(new YamlReader(in).read(), problems);
            problems.forEach(p -> log.warn("Bounce zones: {}", p));
            log.info("Bounce zones: {} categories from {}; blocked now: {} bounce ranges, {} Flash Jump ranges ({})",
                    zones.categories.size(), file, zones.bounceRanges.size(), zones.flashJumpRanges.size(),
                    zones.categories.stream().map(c -> c.name() + (c.enabled() ? "" : " (off)")).toList());
            log.info("Bounce impulses: ninja {} (other jobs {}), vertical {} (other jobs {})",
                    zones.impulses.ninjaNative(), zones.impulses.ninjaOther(),
                    zones.impulses.verticalNative(), zones.impulses.verticalOther());
            return zones;
        } catch (Exception e) {
            log.error("Bounce zones: could not read {}, bounces are allowed everywhere", file, e);
            return EMPTY;
        }
    }

    /** Builds the zones from the parsed yaml; a bad category or entry is left out and described in problems. */
    static BounceZones parse(Object root, List<String> problems) {
        if (!(root instanceof Map<?, ?> top) || !(top.get("categories") instanceof List<?> list)) {
            problems.add("expected a top-level 'categories:' list");
            return root instanceof Map<?, ?> m ? new BounceZones(List.of(), BounceImpulses.parse(m.get("impulses"), problems)) : EMPTY;
        }
        BounceImpulses impulses = BounceImpulses.parse(top.get("impulses"), problems);
        List<Category> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            try {
                Category c = category(list.get(i), problems);
                if (!names.add(c.name())) {
                    throw new IllegalArgumentException("category '" + c.name() + "' is listed twice");
                }
                out.add(c);
            } catch (IllegalArgumentException e) {
                problems.add("category #" + (i + 1) + " skipped: " + e.getMessage());
            }
        }
        return new BounceZones(out, impulses);
    }

    private static Category category(Object o, List<String> problems) {
        if (!(o instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("not a map");
        }
        for (Object k : m.keySet()) {
            if (!CATEGORY_KEYS.contains(String.valueOf(k))) {
                throw new IllegalArgumentException("unknown key '" + k + "'");
            }
        }
        String name = text(m.get("name"));
        if (name.isEmpty()) {
            throw new IllegalArgumentException("no name");
        }
        boolean enabled = bool(m.get("enabled"), "enabled");
        boolean fj = m.containsKey("block_flash_jump") && bool(m.get("block_flash_jump"), "block_flash_jump");
        List<MapRange> ranges = new ArrayList<>();
        if (m.get("maps") instanceof List<?> maps) {
            for (Object e : maps) {
                try {
                    ranges.add(entry(e));
                } catch (IllegalArgumentException ex) {
                    problems.add(name + ": map entry '" + e + "' skipped: " + ex.getMessage());
                }
            }
        } else if (m.get("maps") != null) {
            throw new IllegalArgumentException(name + ": 'maps' is not a list");
        }
        return new Category(name, text(m.get("description")), enabled, fj, ranges);
    }

    /** One map entry: {@code 280020000}, {@code 280020000-280020001} or {@code 2800xxxxx}. */
    static MapRange entry(Object o) {
        String s = text(o);
        Matcher m;
        if (SINGLE.matcher(s).matches()) {
            int id = Integer.parseInt(s);
            return new MapRange(id, id);
        }
        if ((m = RANGE.matcher(s)).matches()) {
            int a = Integer.parseInt(m.group(1));
            int b = Integer.parseInt(m.group(2));
            if (b < a) {
                throw new IllegalArgumentException("range ends before it starts");
            }
            return new MapRange(a, b);
        }
        if ((m = REGION.matcher(s)).matches()) {
            if (s.length() != 9) {
                throw new IllegalArgumentException("a region is 9 characters, like 2800xxxxx");
            }
            int scale = (int) Math.pow(10, m.group(2).length());
            long first = Long.parseLong(m.group(1)) * scale;
            return new MapRange((int) first, (int) (first + scale - 1));
        }
        throw new IllegalArgumentException("not a map id, a-b range or 9-character region like 2800xxxxx");
    }

    private static String text(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static boolean bool(Object o, String key) {
        String s = text(o).toLowerCase();
        if (s.equals("true")) {
            return true;
        }
        if (s.equals("false")) {
            return false;
        }
        throw new IllegalArgumentException("'" + key + "' must be true or false");
    }
}
