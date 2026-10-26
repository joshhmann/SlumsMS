package soloMapling.ArtificialPlayer;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.server.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 1.6.0 S9: bot-made fads. Now and then a bot starts something and its friends pick it up, the way a 2009 server
 * suddenly all sat on the same chair or hung out in Ellinia for a week:
 * <ul>
 *   <li><b>SPOT</b>: a hangout spot (a Victoria town, Henesys Park, Sleepywood). A follower's HANG_OUT goes there
 *   ({@link #spotFor}, read by the brain's town destination) and the brain leans a little toward hanging out.</li>
 *   <li><b>CHAIR</b>: a chair to sit on (a cosmetic, from the v83 chairs bots already use). A follower who sits shows that
 *   chair ({@link #chairFor}); nothing is created or given.</li>
 *   <li><b>PHRASE</b>: a meme line from the 2009 bank in this class ({@link #PHRASES}), never from players. S4/S7 voice it
 *   ({@link #phraseFor}); this class says nothing.</li>
 * </ul>
 *
 * <p>Spreading: only along S2 bonds and crews ({@code BotBonds.crewOf} / {@code friendsOf}), only between online bots on
 * the fad's channel. Each step every sampled follower's friends may join with a chance that grows with the fad's heat
 * and the friend's Socializer pull. Heat starts at 1, rises with each join and with real players taking part (a real
 * player standing at the SPOT, or sitting on the CHAIR, on that channel: players join a fad just by doing it), and
 * halves every {@link #HALF_LIFE_DAYS} days. A cooling fad loses followers; one under {@link #DEAD_HEAT}, or older than
 * {@link #MAX_AGE_DAYS} days, is over.
 *
 * <p>Bounds: at most {@link #MAX_PER_CHANNEL} fads a channel (one of each kind), {@link #MAX_FOLLOWERS} followers each,
 * about one new fad per channel a day. Memory only plus a small file ({@code reports/bot-fads.json}) so a restart
 * keeps them. Stepped by {@code LifeLearning}'s sweep (every 5 minutes). Knob: {@code -Dsolomapling.fads=false}.
 *
 * <p>For S4 gossip and S7 spice: {@link #listen} gets STARTED / JOINED / ENDED; {@link #active()} lists them.
 */
public final class Fads {

    private static final Logger log = LoggerFactory.getLogger(Fads.class);

    public enum Kind { SPOT, CHAIR, PHRASE }

    public enum Change { STARTED, JOINED, LEFT, ENDED }

    /** A fad as others see it. {@code value}: the map id (SPOT), chair id (CHAIR) or phrase index (PHRASE). */
    public record View(int id, Kind kind, int channel, int value, String label, int starter, long startedAt,
                       int followers, double heat, int playersSeen) {
    }

    /** One change to a fad (who joined or left; the starter for STARTED; 0 for ENDED). */
    public record Event(Change change, View fad, int botId) {
    }

    @FunctionalInterface
    public interface Listener {
        void onFad(Event event);
    }

    /** What the fads read from the game (a seam for tests). */
    public interface World {
        /** Online bot ids on a channel. */
        List<Integer> onlineBots(int channel);

        /** The channels with bots on. */
        List<Integer> channels();

        /** The channel a bot is on, -1 when offline. */
        int channelOf(int botId);

        /** A bot's map id, -1 when offline. */
        int mapOf(int botId);

        /** The bots this bot is close to: its crew and FRIEND bonds. */
        List<Integer> friendsOf(int botId);

        /** The bot's Socializer pull, 0..1. */
        double socialPull(int botId);

        /** Real players on that map on that channel. */
        int playersAt(int channel, int mapId);

        /** Real players on that channel sitting on that chair. */
        int playersOnChair(int channel, int chairId);
    }

    public static final int MAX_PER_CHANNEL = 3, MAX_FOLLOWERS = 40;
    public static final double HALF_LIFE_DAYS = 1.5, MAX_AGE_DAYS = 5;
    /** A fad under this heat is over; under {@link #COOLING} its followers start to drift off. */
    static final double DEAD_HEAT = .1, COOLING = .5, MAX_HEAT = 1.5;
    static final double JOIN_HEAT = .08, PLAYER_HEAT = .04;
    /** Per step and channel: about one new fad a channel a day at a 5-minute step. */
    static final double START_CHANCE = .004;
    /** A friend's chance to join per step, times heat and (0.5 + its Socializer pull). */
    static final double JOIN_CHANCE = .05, LEAVE_CHANCE = .15;
    static final int SPREADERS_PER_STEP = 8, FRIENDS_PER_SPREADER = 4;
    /** The brain's pull toward hanging out for a SPOT follower (LifeLearning's bias). */
    public static final double HANGOUT_PULL = .06;

    /** Victoria hangouts (all reachable by any bot on the island; a SPOT fad only spreads to bots there). */
    static final int[] SPOTS = {100000000, 100000200, 101000000, 102000000, 103000000, 104000000, 105040300};
    static final String[] SPOT_NAMES = {"henesys", "henesys park", "ellinia", "perion", "kerning", "lith harbor",
            "sleepywood"};
    /** v83 chairs bots already sit on (BotCustomization's list), never the fishing chair. */
    static final int[] CHAIRS = {3010000, 3010001, 3010002, 3010003, 3010004, 3010005, 3010006, 3010007, 3010008,
            3010009, 3010010, 3010011, 3010012, 3010013, 3010014, 3010015, 3010016, 3010017, 3010018, 3010019};
    /** The 2009 bank of meme lines a PHRASE fad picks from: game talk only, PG, nothing from players. */
    public static final List<String> PHRASES = List.of("zhelm or bust", "lf kpq", "gogo", "1 more lvl then sleep",
            "dont boom pls", "scroll or die", "4th job soon", "who wants to pq", "hp washing is life",
            "pink bean when", "free buffs at the fm", "ellinia hangout anyone", "omok anyone", "brb pots",
            "henesys park is the spot", "selling stuff cheap at fm");

    private static final class Fad {
        final int id;
        final Kind kind;
        final int channel, value, starter;
        final long startedAt;
        final LinkedHashSet<Integer> followers = new LinkedHashSet<>();
        double heat = 1;
        long steppedAt;
        int playersSeen;

        Fad(int id, Kind kind, int channel, int value, int starter, long startedAt) {
            this.id = id;
            this.kind = kind;
            this.channel = channel;
            this.value = value;
            this.starter = starter;
            this.startedAt = startedAt;
            this.steppedAt = startedAt;
        }

        View view() {
            return new View(id, kind, channel, value, label(kind, value), starter, startedAt, followers.size(), heat,
                    playersSeen);
        }
    }

    private static final Object lock = new Object();
    private static final Map<Integer, Fad> fads = new LinkedHashMap<>();
    /** bot id -> the fads it follows (ids). */
    private static final Map<Integer, Set<Integer>> following = new HashMap<>();
    private static final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private static int nextId = 1;
    private static long started, ended, joins;
    private static boolean loaded, dirty;
    private static volatile World world = new GameWorld();
    private static volatile Random random = new Random();

    private Fads() {
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.fads", "true"));
    }

    public static void listen(Listener listener) {
        listeners.add(Objects.requireNonNull(listener));
    }

    public static void remove(Listener listener) {
        listeners.remove(listener);
    }

    static String label(Kind kind, int value) {
        return switch (kind) {
            case SPOT -> {
                for (int i = 0; i < SPOTS.length; i++) if (SPOTS[i] == value) yield SPOT_NAMES[i];
                yield String.valueOf(value);
            }
            case CHAIR -> "chair " + value;
            case PHRASE -> value >= 0 && value < PHRASES.size() ? PHRASES.get(value) : "";
        };
    }

    // ── reads (memory only, cheap) ──

    /** The SPOT a bot's fad sends it to on this channel (only while the bot is on the spot's island), or -1. */
    public static int spotFor(int botId, int channel) {
        Fad f = followed(botId, Kind.SPOT);
        return f != null && f.channel == channel && sameIsland(world.mapOf(botId), f.value) ? f.value : -1;
    }

    /** The chair a bot sits on: its CHAIR fad's, else {@code fallback}. The fishing chair is never replaced. */
    public static int chairFor(int botId, int fallback) {
        if (!enabled() || fallback == 3011000) return fallback;
        Fad f = followed(botId, Kind.CHAIR);
        return f == null ? fallback : f.value;
    }

    /** The meme line a bot's PHRASE fad has it saying lately, or null (S4/S7 decide when to say it). */
    public static String phraseFor(int botId) {
        Fad f = followed(botId, Kind.PHRASE);
        return f == null ? null : label(Kind.PHRASE, f.value);
    }

    /** Whether a map is a SPOT fad's spot right now (the brain may send a bot there before the map loads). */
    public static boolean isSpot(int mapId) {
        synchronized (lock) {
            for (Fad f : fads.values()) if (f.kind == Kind.SPOT && f.value == mapId) return true;
        }
        return false;
    }

    private static Fad followed(int botId, Kind kind) {
        if (!enabled()) return null;
        synchronized (lock) {
            Set<Integer> mine = following.get(botId);
            if (mine == null) return null;
            for (int id : mine) {
                Fad f = fads.get(id);
                if (f != null && f.kind == kind) return f;
            }
        }
        return null;
    }

    /** The fads a bot follows. */
    public static List<View> follows(int botId) {
        synchronized (lock) {
            Set<Integer> mine = following.get(botId);
            if (mine == null) return List.of();
            List<View> out = new ArrayList<>();
            for (int id : mine) if (fads.containsKey(id)) out.add(fads.get(id).view());
            return out;
        }
    }

    public static List<View> active() {
        synchronized (lock) {
            ensureLoaded();
            return fads.values().stream().map(Fad::view).toList();
        }
    }

    public static List<View> active(int channel) {
        return active().stream().filter(v -> v.channel() == channel).toList();
    }

    // ── the step ──

    /** One step at {@code now}: start a fad now and then, spread, count players, cool, end. */
    public static void step(long now) {
        if (!enabled()) return;
        List<Event> events = new ArrayList<>();
        World w = world;
        synchronized (lock) {
            ensureLoaded();
            for (int channel : w.channels()) maybeStart(w, channel, now, events);
            for (Fad f : List.copyOf(fads.values())) stepOne(w, f, now, events);
        }
        for (Event e : events) tell(e);
        save();
    }

    private static void maybeStart(World w, int channel, long now, List<Event> events) {
        List<Fad> here = fads.values().stream().filter(f -> f.channel == channel).toList();
        if (here.size() >= MAX_PER_CHANNEL || random.nextDouble() >= START_CHANCE) return;
        List<Integer> bots = w.onlineBots(channel);
        if (bots.isEmpty()) return;
        int starter = -1;
        for (int tries = 0; tries < 5 && starter < 0; tries++) {
            int pick = bots.get(random.nextInt(bots.size()));
            // Socializers start fads more often; anyone can.
            if (random.nextDouble() < .3 + w.socialPull(pick)) starter = pick;
        }
        if (starter < 0) return;
        EnumSet<Kind> free = EnumSet.allOf(Kind.class);
        here.forEach(f -> free.remove(f.kind));
        List<Kind> kinds = new ArrayList<>(free);
        Kind kind = kinds.get(random.nextInt(kinds.size()));
        int value = switch (kind) {
            case SPOT -> SPOTS[random.nextInt(SPOTS.length)];
            case CHAIR -> CHAIRS[random.nextInt(CHAIRS.length)];
            case PHRASE -> random.nextInt(PHRASES.size());
        };
        if (kind == Kind.SPOT && !sameIsland(w.mapOf(starter), value)) return; // the starter must be able to go
        events.add(start(kind, channel, value, starter, now));
    }

    /** Starts a fad with its starter as the first follower (callers hold the lock). */
    private static Event start(Kind kind, int channel, int value, int starter, long now) {
        Fad f = new Fad(nextId++, kind, channel, value, starter, now);
        fads.put(f.id, f);
        follow(f, starter);
        started++;
        dirty = true;
        return new Event(Change.STARTED, f.view(), starter);
    }

    private static void follow(Fad f, int botId) {
        // one fad of each kind per bot: a new one replaces the old
        Set<Integer> mine = following.computeIfAbsent(botId, b -> new LinkedHashSet<>());
        mine.removeIf(id -> {
            Fad other = fads.get(id);
            if (other == null) return true;
            if (other.kind == f.kind && other.id != f.id) {
                other.followers.remove(botId);
                return true;
            }
            return false;
        });
        mine.add(f.id);
        f.followers.add(botId);
    }

    private static void unfollow(Fad f, int botId) {
        f.followers.remove(botId);
        Set<Integer> mine = following.get(botId);
        if (mine != null) {
            mine.remove(f.id);
            if (mine.isEmpty()) following.remove(botId);
        }
    }

    private static void stepOne(World w, Fad f, long now, List<Event> events) {
        double days = Math.max(0, now - f.steppedAt) / 86_400_000.0;
        f.steppedAt = now;
        f.heat *= Math.pow(.5, days / HALF_LIFE_DAYS);
        // Real players take part just by doing it: standing at the spot, sitting on the chair, on this channel.
        int players = switch (f.kind) {
            case SPOT -> w.playersAt(f.channel, f.value);
            case CHAIR -> w.playersOnChair(f.channel, f.value);
            case PHRASE -> 0;
        };
        if (players > 0) {
            f.playersSeen = Math.max(f.playersSeen, players);
            f.heat = Math.min(MAX_HEAT, f.heat + PLAYER_HEAT * Math.min(5, players));
        }
        // Spread along bonds: a few followers on the channel each tell a few friends.
        List<Integer> spreaders = new ArrayList<>();
        for (int b : f.followers) if (w.channelOf(b) == f.channel) spreaders.add(b);
        Collections.shuffle(spreaders, random);
        double heat = Math.min(1, f.heat);
        for (int s : spreaders.subList(0, Math.min(SPREADERS_PER_STEP, spreaders.size()))) {
            int told = 0;
            for (int friend : w.friendsOf(s)) {
                if (f.followers.size() >= MAX_FOLLOWERS || told >= FRIENDS_PER_SPREADER) break;
                if (friend == s || f.followers.contains(friend) || w.channelOf(friend) != f.channel) continue;
                told++;
                if (f.kind == Kind.SPOT && !sameIsland(w.mapOf(friend), f.value)) continue;
                if (random.nextDouble() < JOIN_CHANCE * heat * (.5 + w.socialPull(friend))) {
                    follow(f, friend);
                    joins++;
                    f.heat = Math.min(MAX_HEAT, f.heat + JOIN_HEAT);
                    dirty = true;
                    events.add(new Event(Change.JOINED, f.view(), friend));
                }
            }
        }
        if (f.heat < COOLING) {
            for (int b : List.copyOf(f.followers)) if (random.nextDouble() < LEAVE_CHANCE) {
                unfollow(f, b);
                dirty = true;
                events.add(new Event(Change.LEFT, f.view(), b));
            }
        }
        if (f.heat < DEAD_HEAT || f.followers.isEmpty() || now - f.startedAt > MAX_AGE_DAYS * 86_400_000L) {
            for (int b : List.copyOf(f.followers)) unfollow(f, b);
            fads.remove(f.id);
            ended++;
            dirty = true;
            events.add(new Event(Change.ENDED, f.view(), 0));
        }
    }

    /** Victoria (map ids 1xxxxxxxx) and the like: a spot is only a fad for bots on its own island. */
    static boolean sameIsland(int mapId, int spot) {
        return mapId > 0 && mapId / 100_000_000 == spot / 100_000_000;
    }

    private static void tell(Event e) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS, "FAD " + e.change() + " " + e.fad().kind()
                    + " '" + e.fad().label() + "' ch" + e.fad().channel() + " bot=" + e.botId() + " followers="
                    + e.fad().followers());
        }
        if (e.change() == Change.STARTED || e.change() == Change.ENDED) {
            log.info("[Fads] {} {} '{}' on ch{} (followers {}, players seen {})", e.change(), e.fad().kind(),
                    e.fad().label(), e.fad().channel(), e.fad().followers(), e.fad().playersSeen());
        }
        for (Listener l : listeners) {
            try {
                l.onFad(e);
            } catch (RuntimeException ignored) {
                // a listener never stops the step
            }
        }
    }

    public static Map<String, Object> report() {
        Map<String, Object> out = new LinkedHashMap<>();
        synchronized (lock) {
            ensureLoaded();
            out.put("enabled", enabled());
            out.put("active", fads.size());
            out.put("startedThisRun", started);
            out.put("endedThisRun", ended);
            out.put("joinsThisRun", joins);
            out.put("fads", fads.values().stream().map(f -> f.kind + " '" + label(f.kind, f.value) + "' ch" + f.channel
                    + " followers " + f.followers.size() + " heat " + Math.round(f.heat * 100) / 100.0
                    + " players " + f.playersSeen).toList());
        }
        return out;
    }

    // ── persistence ──

    private static Path file() {
        return Path.of(System.getProperty("solomapling.fadsFile", "reports/bot-fads.json"));
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        Path f = file();
        if (!Files.isRegularFile(f)) return;
        try {
            if (!(Json.parse(Files.readString(f)) instanceof Map<?, ?> m)) return;
            nextId = m.get("nextId") instanceof Number n ? Math.max(1, n.intValue()) : 1;
            if (m.get("fads") instanceof List<?> rows) for (Object row : rows) {
                if (!(row instanceof Map<?, ?> r)) continue;
                Kind kind = Kind.valueOf((String) r.get("kind"));
                Fad fad = new Fad(((Number) r.get("id")).intValue(), kind, ((Number) r.get("channel")).intValue(),
                        ((Number) r.get("value")).intValue(), ((Number) r.get("starter")).intValue(),
                        ((Number) r.get("startedAt")).longValue());
                if (!valid(kind, fad.value) || fads.size() >= 64) continue;
                fad.heat = Math.max(0, Math.min(MAX_HEAT, ((Number) r.get("heat")).doubleValue()));
                fad.steppedAt = ((Number) r.get("steppedAt")).longValue();
                fad.playersSeen = r.get("players") instanceof Number n ? n.intValue() : 0;
                fads.put(fad.id, fad);
                if (r.get("followers") instanceof List<?> ids) for (Object id : ids) {
                    if (id instanceof Number n && fad.followers.size() < MAX_FOLLOWERS) follow(fad, n.intValue());
                }
                nextId = Math.max(nextId, fad.id + 1);
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[Fads] could not read {}; starting with none", f, e);
            fads.clear();
            following.clear();
        }
    }

    static boolean valid(Kind kind, int value) {
        return switch (kind) {
            case SPOT -> Arrays.stream(SPOTS).anyMatch(s -> s == value);
            case CHAIR -> Arrays.stream(CHAIRS).anyMatch(c -> c == value);
            case PHRASE -> value >= 0 && value < PHRASES.size();
        };
    }

    private static void save() {
        String json;
        synchronized (lock) {
            if (!dirty) return;
            dirty = false;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("nextId", nextId);
            out.put("fads", fads.values().stream().map(f -> {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("id", f.id); r.put("kind", f.kind.name()); r.put("channel", f.channel); r.put("value", f.value);
                r.put("starter", f.starter); r.put("startedAt", f.startedAt); r.put("heat", f.heat);
                r.put("steppedAt", f.steppedAt); r.put("players", f.playersSeen);
                r.put("followers", List.copyOf(f.followers));
                return r;
            }).toList());
            json = Json.write(out);
        }
        if (Boolean.getBoolean("solomapling.fads.noFile")) return;
        Path f = file();
        try {
            if (f.getParent() != null) Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, json);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            log.warn("[Fads] could not save {}", f, e);
        }
    }

    // ── the game ──

    /** The real world: online bots by channel, S2 bonds and crews, real players at a spot or on a chair. */
    static final class GameWorld implements World {
        public List<Integer> onlineBots(int channel) {
            List<Integer> out = new ArrayList<>();
            for (BotSM bot : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
                Character c = bot.getChr();
                if (c != null && c.getMap() != null && c.getMap().getChannelId() == channel && !c.isOwnedAlt()) {
                    out.add(c.getId());
                }
            }
            return out;
        }

        public List<Integer> channels() {
            Set<Integer> out = new TreeSet<>();
            for (BotSM bot : soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getAllBots().values()) {
                Character c = bot.getChr();
                if (c != null && c.getMap() != null) out.add(c.getMap().getChannelId());
            }
            return List.copyOf(out);
        }

        private static Character chr(int botId) {
            BotSM bot = soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById(botId);
            return bot == null ? null : bot.getChr();
        }

        public int channelOf(int botId) {
            Character c = chr(botId);
            return c == null || c.getMap() == null ? -1 : c.getMap().getChannelId();
        }

        public int mapOf(int botId) {
            Character c = chr(botId);
            return c == null || c.getMap() == null ? -1 : c.getMapId();
        }

        public List<Integer> friendsOf(int botId) {
            LinkedHashSet<Integer> out = new LinkedHashSet<>(
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.crewOf(botId));
            out.addAll(soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.friendsOf(botId));
            out.remove(botId);
            return List.copyOf(out);
        }

        public double socialPull(int botId) {
            var memory = soloMapling.ArtificialPlayer.BotLifeSystem.LifeMemoryStore.peek(botId);
            return memory == null ? soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.forCharacter(botId)
                    .socialPull() : memory.social().socialPull();
        }

        public int playersAt(int channel, int mapId) {
            try {
                var ch = soloMapling.server.SoloMaplingUtilities.world().getChannel(channel);
                if (ch == null || !ch.getMapFactory().isMapLoaded(mapId)) return 0;
                int n = 0;
                for (Character c : ch.getMapFactory().getMap(mapId).getAllPlayers()) {
                    if (BotHelpers.isVisibleRealPlayer(c)) n++;
                }
                return n;
            } catch (RuntimeException | LinkageError e) {
                return 0;
            }
        }

        public int playersOnChair(int channel, int chairId) {
            try {
                int n = 0;
                for (Character c : soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getAllCharacters()) {
                    if (BotHelpers.isVisibleRealPlayer(c) && c.getChair() == chairId && c.getMap() != null
                            && c.getMap().getChannelId() == channel) n++;
                }
                return n;
            } catch (RuntimeException | LinkageError e) {
                return 0;
            }
        }
    }

    // ── test seams ──

    /** Test seam: this world and random (nulls restore the game's), no fads, no file. */
    public static void useForTest(World testWorld, Random testRandom) {
        synchronized (lock) {
            world = testWorld != null ? testWorld : new GameWorld();
            random = testRandom != null ? testRandom : new Random();
            fads.clear();
            following.clear();
            nextId = 1;
            started = ended = joins = 0;
            loaded = true;
            dirty = false;
        }
        System.setProperty("solomapling.fads.noFile", String.valueOf(testWorld != null));
    }

    /** Test and QA seam: starts a fad now (true when it started: the channel had room for that kind). */
    public static boolean startForTest(Kind kind, int channel, int value, int starter, long now) {
        Event e;
        synchronized (lock) {
            ensureLoaded();
            if (!valid(kind, value) || fads.values().stream().anyMatch(f -> f.channel == channel && f.kind == kind)
                    || fads.values().stream().filter(f -> f.channel == channel).count() >= MAX_PER_CHANNEL) {
                return false;
            }
            e = start(kind, channel, value, starter, now);
        }
        tell(e);
        return true;
    }
}
