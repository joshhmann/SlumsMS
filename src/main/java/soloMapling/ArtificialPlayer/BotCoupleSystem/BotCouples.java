package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair;

/**
 * Ticket 58, 1.5.0: bot couples. Rare - at most {@link #maxCouples} pairs in the world (1.6.0 S7: about one per 150
 * roster bots, ~20 at 3,000; 1.5.x: 4) - and only bot with bot: bots never court players.
 *
 * <p><b>How a couple forms.</b> 1.6.0 S7: a crush comes first ({@link BotCrushes}). Every
 * {@code coupleEveryHours} (3) the world rolls once ({@code formChance} 0.5): a ripe crush (12 h old, returned as
 * friendship) becomes a couple; with none ripe, two roster bots (training or social, level 15+, within 20 levels)
 * standing on the same map start a crush instead. Couples stay together across restarts ({@code botcouple}, migration
 * 012). <b>Drama:</b> a partner gets jealous of a bot with a crush on its partner standing close (a line, a spat on their
 * bond), sometimes the two fight over it, and a dating couple whose bond turned rival breaks up (a line and a
 * megaphone through the gate, at most {@link #BREAKUPS_PER_DAY} a day world-wide).
 *
 * <p><b>What a couple does</b> while both are online, checked once a minute (only couples, so a handful of bots): the
 * second one hops to the first one's channel; they party (bots only - never pulling a bot out of a real player's
 * party) and a training bot trains on its partner's map; on a map a real player can see, one now and then says
 * something to the other, and a couple that owns a couple chair (every married couple owns an Amorian Loveseat) sits on
 * it together; married couples wear wedding rings; on some days they wear matching hats; and every few days one sends
 * a love-note megaphone through the shared megaphone gate ({@code BotMegaphoneGate}, so the world's megaphone cap holds).
 * Their chat prompt knows about the partner.
 *
 * <p><b>Weddings.</b> A couple that has dated for {@code weddingAfterDays} (2) may marry: at most one wedding per
 * {@code weddingGapHours} (24), in the evening hours, as a world event in Amoria ({@link BotWeddings}).
 *
 * <p>System properties {@code solomapling.couples.*}: {@code enabled} (true), {@code maxCouples} (4),
 * {@code formEveryHours} (12), {@code formChance} (0.5), {@code weddingAfterDays} (2), {@code weddingGapHours} (24),
 * {@code loveNoteDays} (3), {@code mentionMinutes} (20), {@code chairMinutes} (15).
 */
public final class BotCouples {

    private static final Logger log = LoggerFactory.getLogger(BotCouples.class);

    /** DATING and MARRIED are couples; FRIENDS are best friends (never marry). */
    public enum Stage {
        DATING, MARRIED, FRIENDS;

        public boolean couple() {
            return this != FRIENDS;
        }
    }

    /** One couple. {@code a} and {@code b} are bot slots (character id minus {@link BotStore#BOT_ID_BASE}). */
    public record Couple(long id, int a, int b, Stage stage, long formedAt, long marriedAt, long lastNoteAt,
                         boolean staged) {
        public Couple(long id, int a, int b, Stage stage, long formedAt, long marriedAt, long lastNoteAt) {
            this(id, a, b, stage, formedAt, marriedAt, lastNoteAt, false);
        }

        Couple withId(long newId) {
            return new Couple(newId, a, b, stage, formedAt, marriedAt, lastNoteAt, staged);
        }

        Couple married(long now) {
            return new Couple(id, a, b, Stage.MARRIED, formedAt, now, lastNoteAt, staged);
        }

        Couple noted(long now) {
            return new Couple(id, a, b, stage, formedAt, marriedAt, now, staged);
        }

        public boolean has(int slot) {
            return a == slot || b == slot;
        }

        public int other(int slot) {
            return a == slot ? b : a;
        }

        public int charA() {
            return BotStore.BOT_ID_BASE + a;
        }

        public int charB() {
            return BotStore.BOT_ID_BASE + b;
        }
    }

    /** A bot that could start dating: what the pairing rule needs to know. */
    record Candidate(int charId, int gender, int level, int channel, int mapId) { // BotCrushes builds them too
    }

    /** Couple chairs a v83 client knows (BotCustomization's chair list): Amorian Loveseat and the two cake chairs. */
    public static final int[] COUPLE_CHAIRS = {3012005, 3012010, 3012011};
    /** The loveseat every married couple owns. */
    static final int LOVESEAT = 3012005;
    /** Wedding rings (Moonstone, Star Gem, Golden Heart, Silver Swan); a couple wears one kind, by its id. */
    static final int[] WEDDING_RINGS = {1112803, 1112806, 1112807, 1112809};
    /** Friendship rings (Clover, Flower Petal, Star): best friends wear one kind, by their id. */
    static final int[] FRIENDSHIP_RINGS = {1112800, 1112801, 1112802};
    /** The four ring slots; a couple ring takes the first free one and never replaces a ring the bot wears. */
    static final short[] RING_SLOTS = {-12, -13, -15, -16};
    static final short CASH_HAT_SLOT = -101;

    private static final Map<Long, Couple> couples = new ConcurrentHashMap<>();
    private static volatile CoupleStore store = new CoupleStore.Jdbc();
    /** Ids of couples that are not stored (staged, or no migration): negative, so they never meet a table id. */
    private static final java.util.concurrent.atomic.AtomicLong memoryIds = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean loaded;
    private static volatile long loadTriedAt;
    private static volatile long lastStepAt;
    private static volatile long lastFormRollAt;
    private static volatile long lastFriendRollAt;
    private static volatile long lastWeddingAt;
    private static final Map<String, Long> nextAt = new ConcurrentHashMap<>();
    private static volatile IntFunction<Character> online = BotCouples::liveBot;
    /** Whether a bot may be pulled into a wedding now (a seam for tests). */
    private static volatile java.util.function.Predicate<Character> weddingFree = BotCouples::freeForWedding;

    private BotCouples() {
    }

    // ── settings ──

    static double prop(String key, double fallback) {
        try {
            return Double.parseDouble(System.getProperty("solomapling.couples." + key, Double.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.couples.enabled", "true"));
    }

    /** Bots per couple slot (owner, 1.6.0 audit follow-up): about 1 couple per 150 bots, ~20 at 3,000. */
    static final int BOTS_PER_COUPLE = 150;

    /**
     * The world's couple cap: {@code solomapling.couples.maxCouples} when set, else one per {@link #BOTS_PER_COUPLE}
     * roster bots, never under the old 4 (1.6.0 S7; 1.5.x: 4).
     */
    static int maxCouples() {
        String set = System.getProperty("solomapling.couples.maxCouples");
        if (set != null) {
            return (int) prop("maxCouples", 4);
        }
        return capFor(rosterSize.getAsInt());
    }

    /** The couple cap now (GM / QA). */
    public static int coupleCap() {
        return maxCouples();
    }

    /** The default cap for a roster of {@code bots}: max(4, round(bots / 150)). */
    static int capFor(int bots) {
        return Math.max(4, Math.round(bots / (float) BOTS_PER_COUPLE));
    }

    private static volatile java.util.function.IntSupplier rosterSize = BotCouples::loadedRosterSize;

    private static int loadedRosterSize() {
        return BotRoster.loadedCount(); // memory only: 0 (the old cap of 4) until the roster is read
    }

    static long hours(String key, double fallback) {
        return (long) (prop(key, fallback) * TimeUnit.HOURS.toMillis(1));
    }

    static long weddingAfterMs() {
        return (long) (prop("weddingAfterDays", 2) * TimeUnit.DAYS.toMillis(1));
    }

    static long loveNoteMs() {
        return (long) (prop("loveNoteDays", 3) * TimeUnit.DAYS.toMillis(1));
    }

    // ── queries ──

    /**
     * Reads the stored couples once. A failed read is not "no couples": nothing is kept, no couple forms or changes
     * (a write now could pair a bot that is in a stored couple), and the read is tried again after a minute. A bot found
     * in two rows keeps the older one only.
     */
    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (BotCouples.class) {
            long now = System.currentTimeMillis();
            if (loaded || (loadTriedAt > 0 && now - loadTriedAt < TimeUnit.MINUTES.toMillis(1))) {
                return;
            }
            loadTriedAt = now;
            List<Couple> stored;
            try {
                stored = store.loadAll();
            } catch (RuntimeException e) {
                stored = null;
            }
            if (stored == null) {
                log.warn("Bot couples could not be read; trying again in a minute (no couple forms meanwhile)");
                return;
            }
            stored = new ArrayList<>(stored);
            stored.sort((x, y) -> Long.compare(x.id(), y.id()));
            Set<Integer> seen = new HashSet<>();
            for (Couple c : stored) {
                if (seen.contains(c.a()) || seen.contains(c.b())) {
                    log.warn("Bot couple #{} ({} + {}) names a bot that is in an older couple; ignored", c.id(),
                            c.charA(), c.charB());
                    continue;
                }
                seen.add(c.a());
                seen.add(c.b());
                couples.put(c.id(), c);
            }
            loaded = true;
        }
    }

    /** Whether the stored couples have been read (until then nothing forms, marries or breaks up). */
    static boolean loaded() {
        return loaded;
    }

    public static List<Couple> all() {
        ensureLoaded();
        List<Couple> out = new ArrayList<>(couples.values());
        out.sort((x, y) -> Long.compare(x.id(), y.id()));
        return out;
    }

    /**
     * The couple this bot is in, or null. Memory only, never the database: the table is read by the first minute
     * step (the chat path and the grind brain must not wait on it).
     */
    public static Couple coupleOf(int botCharId) {
        if (botCharId < BotStore.BOT_ID_BASE || !enabled()) {
            return null;
        }
        int slot = botCharId - BotStore.BOT_ID_BASE;
        for (Couple c : couples.values()) {
            if (c.has(slot)) {
                return c;
            }
        }
        return null;
    }

    /** The partner's character id, or -1. */
    public static int partnerOf(int botCharId) {
        Couple c = coupleOf(botCharId);
        return c == null ? -1 : BotStore.BOT_ID_BASE + c.other(botCharId - BotStore.BOT_ID_BASE);
    }

    // ── the rules (pure) ──

    /**
     * Two bots that may pair up: any genders (owner 2026-10-21: same-gender couples too), both level 15+, within 20
     * levels.
     */
    static boolean compatible(Candidate x, Candidate y) {
        return x.charId() != y.charId() && x.level() >= 15 && y.level() >= 15 && Math.abs(x.level() - y.level()) <= 20;
    }

    /** A random compatible pair standing on the same map of the same channel, none of them taken; or null. */
    static Candidate[] pickPair(List<Candidate> candidates, Set<Integer> taken, Random rng) {
        Map<Long, List<Candidate>> byPlace = new HashMap<>();
        for (Candidate c : candidates) {
            if (!taken.contains(c.charId())) {
                byPlace.computeIfAbsent(((long) c.channel() << 32) | c.mapId(), k -> new ArrayList<>()).add(c);
            }
        }
        List<Candidate[]> pairs = new ArrayList<>();
        for (List<Candidate> here : byPlace.values()) {
            for (int i = 0; i < here.size(); i++) {
                for (int j = i + 1; j < here.size(); j++) {
                    if (compatible(here.get(i), here.get(j))) {
                        pairs.add(new Candidate[]{here.get(i), here.get(j)});
                    }
                }
            }
        }
        return pairs.isEmpty() ? null : pairs.get(rng.nextInt(pairs.size()));
    }

    /** A dating couple that has been together long enough to marry. */
    static boolean readyToMarry(Couple c, long now) {
        return c.stage() == Stage.DATING && now - c.formedAt() >= weddingAfterMs();
    }

    static boolean noteDue(Couple c, long now) {
        return now - Math.max(c.lastNoteAt(), c.formedAt()) >= loveNoteMs();
    }

    /** About one day in four a couple wears matching hats (stable for the whole day). */
    static boolean matchingDay(Couple c, long now) {
        long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
        return Math.floorMod(Long.hashCode(c.id() * 31 + day * 0x9E3779B97F4A7C15L), 4) == 0;
    }

    static int ringOf(Couple c) {
        int[] kinds = c.stage() == Stage.FRIENDS ? FRIENDSHIP_RINGS : WEDDING_RINGS;
        return kinds[(int) Math.floorMod(c.id(), kinds.length)];
    }

    // ── forming and breaking ──

    /**
     * Two bots become a couple now. False when either is already in one. A staged (GM) couple of on-demand bots is
     * kept in memory only.
     */
    public static Couple form(int aCharId, int bCharId, boolean staged, long now) {
        return form(aCharId, bCharId, Stage.DATING, staged, now);
    }

    /** As {@link #form(int, int, boolean, long)}: a couple (DATING) or best friends (FRIENDS). One pair per bot. */
    public static synchronized Couple form(int aCharId, int bCharId, Stage stage, boolean staged, long now) {
        ensureLoaded();
        if (!loaded || aCharId == bCharId || coupleOf(aCharId) != null || coupleOf(bCharId) != null) {
            return null;
        }
        Couple c = new Couple(0, aCharId - BotStore.BOT_ID_BASE, bCharId - BotStore.BOT_ID_BASE,
                stage == Stage.FRIENDS ? Stage.FRIENDS : Stage.DATING, now, 0, 0, staged);
        long id = staged ? 0 : store.insert(c);
        if (id == CoupleStore.TAKEN) {
            log.warn("Bots {} and {}: one is in a stored couple already; no new couple", aCharId, bCharId);
            return null;
        }
        if (id <= 0) {
            id = memoryIds.decrementAndGet(); // staged, or not stored (no migration): a memory-only id
        }
        c = c.withId(id);
        couples.put(id, c);
        log.info("Bots {} and {} are {} now{}", aCharId, bCharId, stage == Stage.FRIENDS ? "best friends" : "a couple",
                staged ? " (staged)" : "");
        qa("FORMED " + (stage == Stage.FRIENDS ? "best friends" : "couple") + " #" + id + ": " + aCharId + " + " + bCharId + (staged ? " (staged)" : ""));
        return c;
    }

    public static boolean breakUp(long id) {
        if (!loaded) {
            return false;
        }
        Couple c = couples.remove(id);
        if (c == null) {
            return false;
        }
        if (!c.staged()) {
            store.delete(id);
        }
        for (int charId : new int[]{c.charA(), c.charB()}) { // their rings come off (offline ones: at the next sweep)
            Character chr = online.apply(charId);
            if (chr != null) {
                takeOffCoupleRings(chr, 0);
                try {
                    OutfitLoans.giveBack(chr, CASH_HAT_SLOT);
                } catch (RuntimeException e) {
                    log.debug("Could not give {} its hat back", chr.getName(), e);
                }
            }
        }
        qa("BROKE UP couple #" + id);
        return true;
    }

    /** The couple married now (the ceremony's "I now pronounce"). */
    static Couple marry(Couple c, long now) {
        Couple m = c.married(now);
        couples.put(m.id(), m);
        if (!c.staged()) {
            store.update(m);
        }
        lastWeddingAt = now;
        return m;
    }

    // ── the minute step ──

    /** From the population director's tick: at most once a minute. */
    public static void maybeStep(long now) {
        if (!enabled() || now - lastStepAt < TimeUnit.SECONDS.toMillis(60)) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> {
            try {
                step(now, ThreadLocalRandom.current());
            } catch (RuntimeException e) {
                log.warn("Bot couple step failed", e);
            }
        });
    }

    /** GM / !qa: the minute step now, on this thread (no rate limit). */
    public static void stepNow() {
        step(System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    static void step(long now, Random rng) {
        ensureLoaded();
        if (!loaded) {
            return;
        }
        BotCrushes.step(now, rng); // 1.6.0 S7: crushes come before couples
        maybeForm(now, rng);
        for (Couple c : all()) {
            if (soured(c) && breakupDrama(c, now) != null) {
                continue; // 1.6.0 S7: a soured dating couple broke up
            }
            Character a = online.apply(c.charA());
            Character b = online.apply(c.charB());
            if (a != null && b != null) {
                behave(c, a, b, now, rng);
            }
        }
        sweep();
        Couple ready = weddingCouple(all(), now);
        if (ready != null && now - lastWeddingAt >= hours("weddingGapHours", 24) && !BotWeddings.running()
                && soloMapling.worldevents.WorldEvents.curveFraction() >= 0.5 && rng.nextDouble() < 0.1) {
            lastWeddingAt = now;
            String problem = BotWeddings.start(ready, "couples");
            if (problem != null) {
                log.info("A bot wedding could not start: {}", problem);
            }
        }
    }

    /**
     * The couple to marry next: ready to marry, both online, and both free - not partied, following or grouped with a
     * real player, not in an event or PQ, not trading, not gathering for a guild boss run. A busy couple waits; the
     * next ready one goes instead. Null when none can.
     */
    static Couple weddingCouple(List<Couple> candidates, long now) {
        for (Couple c : candidates) {
            if (!readyToMarry(c, now)) {
                continue;
            }
            Character a = online.apply(c.charA());
            Character b = online.apply(c.charB());
            if (a != null && b != null && weddingFree.test(a) && weddingFree.test(b)) {
                return c;
            }
        }
        return null;
    }

    /**
     * A bot a wedding may take away now: a roster bot doing its own thing (training or social, never a follower or a
     * party bot), free of every player-facing commitment ({@link BotPopulationDirector#mayLogOut}: no open trade,
     * no party with a real player, not following anyone, not at a guild boss run), and in no event or PQ.
     */
    public static boolean freeForWedding(Character bot) {
        if (bot == null) {
            return false;
        }
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        return (sm instanceof TrainingBot || sm instanceof SocialBot) && free(bot) && !withRealPlayer(bot)
                && !BotWeddings.inCeremony(bot.getId());
    }

    /**
     * Once a minute, over the bots online: couple rings on a bot no longer in that couple come off, and wedding
     * clothes outside a wedding come off (both can be left over from a breakup while offline, or from an older build).
     */
    private static void sweep() {
        try {
            OutfitLoans.forgetStale(online);
            for (BotSM sm : CharacterStorage.getAllBots().values()) {
                Character chr = sm.getChr();
                if (chr == null) {
                    continue;
                }
                Couple c = coupleOf(chr.getId());
                int keep = c == null || c.stage() == Stage.DATING ? 0 : ringOf(c);
                if (keep == 0) { // 1.5.0 bot drama: a bot married to a player keeps that wedding ring on
                    keep = soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.ringWornBy(chr.getId());
                }
                takeOffCoupleRings(chr, keep);
                BotWeddings.takeOffWeddingClothes(chr);
            }
        } catch (RuntimeException e) {
            log.debug("Couple sweep failed", e);
        }
    }

    private static void maybeForm(long now, Random rng) {
        long couplesNow = couples.values().stream().filter(c -> !c.staged() && c.stage().couple()).count();
        long friendsNow = couples.values().stream().filter(c -> !c.staged() && !c.stage().couple()).count();
        boolean coupleRoll = couplesNow < maxCouples() && now - lastFormRollAt >= hours("coupleEveryHours", 3);
        boolean friendRoll = friendsNow < maxFriendships() && now - lastFriendRollAt >= hours("formEveryHours", 12);
        if (coupleRoll) {
            lastFormRollAt = now;
            coupleRoll = rng.nextDouble() < prop("formChance", 0.5);
        }
        if (friendRoll) {
            lastFriendRollAt = now;
            friendRoll = rng.nextDouble() < prop("formChance", 0.5);
        }
        if (coupleRoll) {
            formFromCrush(now, rng);
        }
        if (friendRoll) {
            formFromOnline(Stage.FRIENDS, now, rng);
        }
    }

    static int maxFriendships() {
        return (int) prop("maxFriendships", 4);
    }

    private static void formFromOnline(Stage stage, long now, Random rng) {
        Set<Integer> taken = new HashSet<>();
        for (Couple c : couples.values()) {
            taken.add(c.charA());
            taken.add(c.charB());
        }
        List<Candidate> candidates = new ArrayList<>();
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character chr = sm.getChr();
            if (chr == null || chr.getMap() == null || !(sm instanceof TrainingBot || sm instanceof SocialBot)
                    || BotRoster.member(chr.getId() - BotStore.BOT_ID_BASE) == null || withRealPlayer(chr)
                    || soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.ofBot(chr.getId()) != null) { // with a player
                continue;
            }
            candidates.add(new Candidate(chr.getId(), chr.getGender(), chr.getLevel(), channelOf(chr), chr.getMapId()));
        }
        Candidate[] pair = pickPair(candidates, taken, rng);
        if (pair == null) {
            return;
        }
        Couple c = form(pair[0].charId(), pair[1].charId(), stage, false, now);
        Character a = online.apply(pair[0].charId());
        Character b = online.apply(pair[1].charId());
        if (c != null && a != null && b != null) {
            say(a, stage == Stage.FRIENDS ? "FriendFormed" : "Formed", b.getName());
        }
    }

    /**
     * 1.6.0 S7: a ripe crush becomes a couple; with none ripe, two single bots standing together start a crush (every
     * couple passes through one).
     */
    private static void formFromCrush(long now, Random rng) {
        BotCrushes.Crush ripe = BotCrushes.ripeOnline(now);
        if (ripe != null) {
            formCrush(ripe, now);
            return;
        }
        if (!BotCrushes.enabled()) {
            formFromOnline(Stage.DATING, now, rng);
            return;
        }
        Candidate[] pair = pickPair(onlineCandidates(), taken(), rng);
        if (pair != null) {
            BotCrushes.start(pair[0].charId(), pair[1].charId(), false, now);
        }
    }

    /** A crush becomes a couple now (both online): the admirer says so. Null when it could not form. */
    static Couple formCrush(BotCrushes.Crush crush, long now) {
        Couple c = form(crush.admirer(), crush.target(), Stage.DATING, crush.staged(), now);
        if (c == null) {
            return null;
        }
        BotCrushes.end(crush.admirer(), "together");
        BotCrushes.end(crush.target(), "together");
        Character a = online.apply(crush.admirer());
        Character b = online.apply(crush.target());
        if (a != null && b != null) {
            say(a, "Formed", b.getName());
        }
        qa("CRUSH TO COUPLE #" + c.id() + ": " + BotCrushes.name(crush.admirer()) + " + " + BotCrushes.name(crush.target()));
        return c;
    }

    private static Set<Integer> taken() {
        Set<Integer> taken = new HashSet<>();
        for (Couple c : couples.values()) {
            taken.add(c.charA());
            taken.add(c.charB());
        }
        for (BotCrushes.Crush c : BotCrushes.all()) {
            taken.add(c.admirer());
        }
        return taken;
    }

    private static List<Candidate> onlineCandidates() {
        List<Candidate> candidates = new ArrayList<>();
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character chr = sm.getChr();
            if (chr == null || chr.getMap() == null || !(sm instanceof TrainingBot || sm instanceof SocialBot)
                    || BotRoster.loadedMember(chr.getId() - BotStore.BOT_ID_BASE) == null || withRealPlayer(chr)
                    || soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.ofBot(chr.getId()) != null) {
                continue;
            }
            candidates.add(new Candidate(chr.getId(), chr.getGender(), chr.getLevel(), channelOf(chr), chr.getMapId()));
        }
        return candidates;
    }

    // ── 1.6.0 S7: jealousy and breakups ──

    static final int BREAKUPS_PER_DAY = 1;
    static final long JEALOUS_GAP_MS = TimeUnit.MINUTES.toMillis(20);
    static final double FIGHT_CHANCE = 0.15;
    private static final Map<Long, Integer> breakupsByDay = new ConcurrentHashMap<>();
    /** The breakup megaphone through the shared gate (a seam for tests). */
    private static volatile java.util.function.BiPredicate<Character, String> breakupMegaphone = (speaker, other) ->
            WorldEventMegaphones.getInstance().offerEvent(speaker, "DramaCoupleBreakup", Map.of("OTHER", other),
                    "couple:breakup:" + speaker.getId() + ":" + other);

    /**
     * A partner sees a bot with a crush on its partner standing close: a jealous line at it and a spat on their bond;
     * now and then the couple fights about it (a spat both ways). What happened, or null.
     */
    static String jealousy(Couple c, Character a, Character b, long now, Random rng, boolean qaPair) {
        if (!c.stage().couple()) {
            return null;
        }
        for (Character[] pr : new Character[][]{{a, b}, {b, a}}) {
            Character partner = pr[0];
            Character loved = pr[1];
            for (int admirer : BotCrushes.admirersOf(loved.getId())) {
                Character other = online.apply(admirer);
                if (other == null || other.getMap() != loved.getMap() || other.getPosition() == null
                        || loved.getPosition() == null || other.getPosition().distance(loved.getPosition()) > 150
                        || !take("jealous:" + c.id(), now, qaPair ? 30_000 : JEALOUS_GAP_MS)) {
                    continue;
                }
                String line = BotCrushes.say(partner, "CoupleJealous", loved.getName(), other.getName());
                soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.record(partner.getId(), admirer,
                        soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent.SPAT, now, false);
                String out = "COUPLE JEALOUS " + partner.getName() + " at " + other.getName() + " (likes "
                        + loved.getName() + "): " + line;
                if (rng.nextDouble() < FIGHT_CHANCE) {
                    String fight = BotCrushes.say(partner, "CoupleFight", loved.getName(), other.getName());
                    soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.together(partner.getId(), loved.getId(),
                            soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent.SPAT);
                    out += "; FIGHT " + partner.getName() + " -> " + loved.getName() + ": " + fight;
                }
                qa(out);
                return out;
            }
        }
        return null;
    }

    /** Whether a dating couple's bond has turned rival (either way): it breaks up. */
    static boolean soured(Couple c) {
        return c.stage() == Stage.DATING
                && (soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.state(c.charA(), c.charB()).hostile()
                || soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds.state(c.charB(), c.charA()).hostile());
    }

    /**
     * A soured dating couple breaks up (one of them online says it, and a megaphone through the gate), at most
     * {@link #BREAKUPS_PER_DAY} a day world-wide unless staged. What happened, or null.
     */
    static String breakupDrama(Couple c, long now) {
        Character a = online.apply(c.charA());
        Character b = online.apply(c.charB());
        Character speaker = a != null ? a : b;
        if (speaker == null) {
            return null;
        }
        long day = Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
        if (!c.staged() && breakupsByDay.getOrDefault(day, 0) >= BREAKUPS_PER_DAY) {
            return null;
        }
        breakupsByDay.merge(day, 1, Integer::sum);
        breakupsByDay.keySet().removeIf(d -> d < day - 1);
        int otherId = speaker == a ? c.charB() : c.charA();
        String otherName = BotCrushes.name(otherId);
        String line = BotCrushes.say(speaker, "CoupleBreakup", otherName, null);
        boolean mega = breakupMegaphone.test(speaker, otherName);
        breakUp(c.id());
        soloMapling.ArtificialPlayer.BotDramaSystem.DramaNews.add(
                soloMapling.ArtificialPlayer.BotDramaSystem.DramaNews.Kind.BREAKUP, otherId, otherName,
                speaker.getName(), now);
        String out = "COUPLE BREAKUP " + speaker.getName() + " + " + otherName + ": " + line + " (megaphone "
                + (mega ? "queued" : "gated") + ")";
        qa(out);
        log.info("Bot couple #{} broke up: {}", c.id(), out);
        return out;
    }

    /** What a couple does this minute; both are online. */
    static void behave(Couple c, Character a, Character b, long now, Random rng) {
        if (c.stage() != Stage.DATING) { // married couples wear wedding rings, best friends friendship rings
            wearRing(a, ringOf(c));
            wearRing(b, ringOf(c));
        }
        if (c.stage().couple()) {
            matchHats(c, a, b, now);
        }
        if (BotWeddings.inCeremony(a.getId()) || BotWeddings.inCeremony(b.getId())) {
            return; // the wedding drives them now
        }
        if (channelOf(a) != channelOf(b)) {
            if (take("hop:" + c.id(), now, TimeUnit.MINUTES.toMillis(10)) && free(b)) {
                boolean moved = BotChannels.moveToChannel(b, channelOf(a));
                qa("HOP " + b.getName() + " -> ch" + channelOf(a) + " to be with " + a.getName() + ": " + moved);
            }
            return;
        }
        partyUp(c, a, b, now);
        // rc6 (QA t-cu-1/t-cu-2: "not really seeing any behavior"): a watched couple (or best friends) stays
        // together - one walks over to the other, they smile at each other, talk, and a couple sits down together.
        // A staged !qa pair does it within the first steps (seconds apart); live pairs at a calm pace.
        boolean qaPair = c.staged() && QaLog.on(QaLog.Topic.COUPLE);
        if (a.getMapId() == b.getMapId() && (qaPair || realPlayerSees(a))) {
            together(c, a, b, now, qaPair);
            long mentionGap = qaPair ? TimeUnit.SECONDS.toMillis(50) : TimeUnit.MINUTES.toMillis((long) prop("mentionMinutes", 20));
            if (take("mention:" + c.id(), now, mentionGap) && (qaPair || rng.nextDouble() < 0.5)) {
                Character speaker = rng.nextBoolean() ? a : b;
                say(speaker, c.stage().couple() ? "Mention" : "FriendMention", (speaker == a ? b : a).getName());
            }
            if (c.stage().couple()) {
                maybeSit(c, a, b, now, rng, qaPair);
                jealousy(c, a, b, now, rng, qaPair); // 1.6.0 S7
            }
        }
        if (c.stage().couple() && noteDue(c, now) && rng.nextDouble() < 0.05) {
            Character speaker = rng.nextBoolean() ? a : b;
            Character partner = speaker == a ? b : a;
            boolean offered = WorldEventMegaphones.getInstance().offerEvent(speaker, "CoupleLoveNote",
                    Map.of("PARTNER", partner.getName()), "couple:note:" + c.id());
            Couple noted = c.noted(now);
            couples.put(c.id(), noted);
            if (!c.staged()) {
                store.update(noted);
            }
            qa("LOVE NOTE " + speaker.getName() + " -> " + partner.getName() + ": " + (offered ? "queued" : "gate closed"));
        }
    }

    /** They party together, bots only: never pulls either out of a party with a real player. */
    private static void partyUp(Couple c, Character a, Character b, long now) {
        if (withRealPlayer(a) || withRealPlayer(b)) {
            return;
        }
        Party pa = a.getParty();
        Party pb = b.getParty();
        if (pa != null && pb != null && pa.getId() == pb.getId()) {
            return;
        }
        if (!take("party:" + c.id(), now, TimeUnit.MINUTES.toMillis(10)) || !free(b)) {
            return;
        }
        if (pb != null) {
            soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands.botLeaveParty(b);
        }
        if (pa == null) {
            if (!Party.createParty(a, true)) {
                return;
            }
            pa = a.getParty();
        }
        if (pa != null && pa.getLeaderId() == a.getId() && pa.getMembers().size() < 6) {
            boolean joined = Party.joinParty(b, pa.getId(), true);
            qa("PARTY " + a.getName() + " + " + b.getName() + ": " + joined);
        }
    }

    /**
     * Ticket 58: a training bot partied with its partner trains on the partner's map when the partner leads the party
     * and is on a training map. Called by TrainingBot; null when that does not apply.
     */
    public static Integer partnerTrainingMap(Character bot) {
        Couple c = coupleOf(bot.getId());
        Party party = bot.getParty();
        if (c == null || party == null) {
            return null;
        }
        int partner = BotStore.BOT_ID_BASE + c.other(bot.getId() - BotStore.BOT_ID_BASE);
        if (party.getLeaderId() != partner) {
            return null; // the leader decides for both
        }
        PartyCharacter pc = party.getMemberById(partner);
        if (pc == null || !pc.isOnline() || pc.getChannel() != channelOf(bot)) {
            return null;
        }
        int map = pc.getMapId();
        return soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex.level(map) >= 0 ? map : null;
    }

    /**
     * Two social bots of a pair stand together: the second walks over when they are apart, and when they are side by
     * side they smile at each other now and then. Logged as TOGETHER / SMILE for !qa cp-1.
     */
    static void together(Couple c, Character a, Character b, long now, boolean qaPair) {
        if (!(CharacterStorage.getBotById(a.getId()) instanceof SocialBot)
                || !(CharacterStorage.getBotById(b.getId()) instanceof SocialBot)
                || a.getPosition() == null || b.getPosition() == null || (a.getChair() > 0 && b.getChair() > 0)) {
            return;
        }
        java.awt.Point pa = a.getPosition();
        java.awt.Point pb = b.getPosition();
        if (apart(pa, pb)) {
            if (take("near:" + c.id(), now, qaPair ? 12_000 : TimeUnit.MINUTES.toMillis(2))) {
                java.awt.Point to = besideSpot(pa, pb);
                soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(b, to.x, to.y);
                qa("TOGETHER " + b.getName() + " walks over to " + a.getName());
            }
        } else if (take("smile:" + c.id(), now, qaPair ? 25_000 : TimeUnit.MINUTES.toMillis(5))) {
            soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint(a, pb);
            soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint(b, pa);
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote(a, soloMapling.ArtificialPlayer.ReplyEmote.SMILE);
            soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote(b, soloMapling.ArtificialPlayer.ReplyEmote.SMILE);
            qa("SMILE " + a.getName() + " + " + b.getName());
        }
    }

    /** Whether two of a pair stand apart (more than a step from each other, or on different platforms). */
    static boolean apart(java.awt.Point a, java.awt.Point b) {
        return Math.abs(a.x - b.x) > 80 || Math.abs(a.y - b.y) > 40;
    }

    /**
     * Where the second one stands: {@link #BESIDE_PX} from the first, on the side it comes from. rc6 personal space: a
     * couple stands side by side (15-30 px, closer than strangers' 25 px) but never on one pixel.
     */
    static java.awt.Point besideSpot(java.awt.Point first, java.awt.Point second) {
        return new java.awt.Point(first.x + (second.x < first.x ? -BESIDE_PX : BESIDE_PX), first.y);
    }

    static final int BESIDE_PX = 24;

    private static void maybeSit(Couple c, Character a, Character b, long now, Random rng, boolean qaPair) {
        int chair = coupleChair(c, a, b);
        long chairGap = qaPair ? TimeUnit.SECONDS.toMillis(90) : TimeUnit.MINUTES.toMillis((long) prop("chairMinutes", 15));
        if (chair == 0 || !(CharacterStorage.getBotById(a.getId()) instanceof SocialBot)
                || !(CharacterStorage.getBotById(b.getId()) instanceof SocialBot)
                || a.getPosition() == null || b.getPosition() == null
                || apart(a.getPosition(), b.getPosition()) // side by side first (together walks them over)
                || (a.getChair() > 0 && b.getChair() > 0)
                || !take("chair:" + c.id(), now, chairGap)
                || (!qaPair && rng.nextDouble() >= 0.5)) {
            return;
        }
        botSitChair(a, chair);
        botSitChair(b, chair);
        say(a, "Sit", b.getName());
        qa("SIT " + a.getName() + " + " + b.getName() + " on chair " + chair);
    }

    /** The couple chair they own: a married couple's loveseat, or one either keeps in their setup inventory; 0. */
    static int coupleChair(Couple c, Character a, Character b) {
        if (c.stage() == Stage.MARRIED) {
            return LOVESEAT;
        }
        for (Character chr : new Character[]{a, b}) {
            try {
                for (int id : COUPLE_CHAIRS) {
                    if (chr.getInventory(InventoryType.SETUP).findById(id) != null) {
                        return id;
                    }
                }
            } catch (RuntimeException e) {
                return 0;
            }
        }
        return 0;
    }

    /** Whether this item is a couple ring (wedding or friendship). */
    static boolean coupleRing(int itemId) {
        for (int r : WEDDING_RINGS) {
            if (r == itemId) {
                return true;
            }
        }
        for (int r : FRIENDSHIP_RINGS) {
            if (r == itemId) {
                return true;
            }
        }
        return false;
    }

    /**
     * Puts the couple's ring on, in the first free ring slot - never over a ring the bot already wears; with every
     * slot taken it goes without. Nothing changes when it wears that ring already (any other couple ring it wears is
     * swapped for it in place). True when the bot wears the ring after.
     */
    public static boolean wearRing(Character bot, int ringId) {
        try {
            short free = 0;
            for (short slot : RING_SLOTS) {
                Item worn = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
                if (worn == null) {
                    if (free == 0) {
                        free = slot;
                    }
                } else if (worn.getItemId() == ringId) {
                    return true; // on already: no re-equip
                } else if (coupleRing(worn.getItemId())) {
                    free = slot; // an old couple ring of its own: the new one takes its place
                    break;
                }
            }
            if (free == 0) {
                return false; // every ring slot holds a ring of its own
            }
            OutfitLoans.equip(bot, ringId, free);
            return true;
        } catch (RuntimeException e) {
            log.debug("Could not put a ring on {}", bot.getName(), e);
            return false;
        }
    }

    /** Takes every couple ring off except {@code keep} (0 = all of them). True when one came off. */
    public static boolean takeOffCoupleRings(Character bot, int keep) {
        boolean changed = false;
        try {
            for (short slot : RING_SLOTS) {
                Item worn = bot.getInventory(InventoryType.EQUIPPED).getItem(slot);
                if (worn != null && worn.getItemId() != keep && coupleRing(worn.getItemId())) {
                    bot.getInventory(InventoryType.EQUIPPED).removeSlot(slot);
                    changed = true;
                }
            }
            if (changed) {
                bot.equipChanged();
            }
        } catch (RuntimeException e) {
            log.debug("Could not take the rings off {}", bot.getName(), e);
        }
        return changed;
    }

    /**
     * On a matching day the second wears the first's cash hat; the next day it gets its own back. The hat is a loan
     * ({@link OutfitLoans}): the bot's stored row keeps its own hat all day, so a logout or restart never swaps it.
     */
    static void matchHats(Couple c, Character a, Character b, long now) {
        try {
            Item hatA = a.getInventory(InventoryType.EQUIPPED).getItem(CASH_HAT_SLOT);
            Item hatB = b.getInventory(InventoryType.EQUIPPED).getItem(CASH_HAT_SLOT);
            if (matchingDay(c, now) && hatA != null) {
                if (hatB == null || hatB.getItemId() != hatA.getItemId()) {
                    OutfitLoans.lend(b, CASH_HAT_SLOT, hatA.getItemId());
                    qa("MATCHING " + b.getName() + " wears " + a.getName() + "'s hat " + hatA.getItemId());
                }
            } else if (OutfitLoans.giveBack(b, CASH_HAT_SLOT)) {
                qa("MATCHING over: " + b.getName() + " has its own hat back");
            }
        } catch (RuntimeException e) {
            log.debug("Matching hats failed for couple {}", c.id(), e);
        }
    }

    /** What the chat prompt adds: who the bot is with. "" for a single bot. */
    public static String promptLine(int botCharId) {
        Couple c = coupleOf(botCharId);
        if (c == null) {
            return "";
        }
        String n = soloMapling.ArtificialPlayer.BotBuddies.nameOf(BotStore.BOT_ID_BASE
                + c.other(botCharId - BotStore.BOT_ID_BASE));
        String name = n == null ? "your partner" : n;
        if (c.stage() == Stage.FRIENDS) {
            return " Your best friend in the game is " + name + " (another player); you hang out and train together, and"
                    + " may mention them now and then.";
        }
        return c.stage() == Stage.MARRIED
                ? " You are married to " + name + " (another player); you may mention them now and then, sweetly."
                : " You are dating " + name + " (another player); you may mention them now and then. You are taken: if"
                + " anyone flirts with you, say so kindly.";
    }

    // ── GM ──

    public static List<String> describe(long now) {
        List<String> out = new ArrayList<>();
        for (Couple c : all()) {
            String na = soloMapling.ArtificialPlayer.BotBuddies.nameOf(c.charA());
            String nb = soloMapling.ArtificialPlayer.BotBuddies.nameOf(c.charB());
            boolean onA = online.apply(c.charA()) != null;
            boolean onB = online.apply(c.charB()) != null;
            out.add("#" + c.id() + " " + na + (onA ? "*" : "") + " + " + nb + (onB ? "*" : "") + ": "
                    + c.stage().name().toLowerCase(Locale.ROOT) + ", together "
                    + TimeUnit.MILLISECONDS.toHours(now - c.formedAt()) + " h"
                    + (readyToMarry(c, now) ? ", ready to marry" : "") + (c.staged() ? ", staged" : ""));
        }
        if (out.isEmpty()) {
            out.add("no couples yet");
        }
        out.add("(* = online) max " + maxCouples() + " couples and " + maxFriendships() + " best friends; next wedding allowed in "
                + Math.max(0, TimeUnit.MILLISECONDS.toMinutes(lastWeddingAt + hours("weddingGapHours", 24) - now)) + " min");
        return out;
    }

    public static Couple byId(long id) {
        ensureLoaded();
        return couples.get(id);
    }

    /** GM: send a love note now (through the megaphone gate). */
    public static String loveNoteNow(Couple c) {
        Character a = online.apply(c.charA());
        Character b = online.apply(c.charB());
        if (a == null || b == null) {
            return "both must be online";
        }
        boolean offered = WorldEventMegaphones.getInstance().offerEvent(a, "CoupleLoveNote",
                Map.of("PARTNER", b.getName()), "couple:note:" + c.id() + ":" + System.currentTimeMillis());
        return offered ? "love note sent (or waiting for the megaphone gate)" : "megaphones are off or gated";
    }

    // ── helpers ──

    private static boolean take(String key, long now, long gapMs) {
        Long next = nextAt.get(key);
        if (next != null && now < next) {
            return false;
        }
        nextAt.put(key, now + gapMs);
        return true;
    }

    /** Not busy with something a hop or a party change would break. */
    private static boolean free(Character bot) {
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        return sm != null && BotPopulationDirector.mayLogOut(sm) && bot.getEventInstance() == null
                && !soloMapling.ArtificialPlayer.BotConversation.keptByChat(bot); // 1.5.1: no hop off mid-chat
    }

    private static boolean withRealPlayer(Character bot) {
        Party p = bot.getParty();
        if (p == null) {
            return false;
        }
        for (PartyCharacter m : p.getMembers()) {
            if (m != null && !BotHelpers.isArtificial(m.getId())) {
                return true;
            }
        }
        return false;
    }

    private static boolean realPlayerSees(Character bot) {
        try {
            for (Character c : bot.getMap().getAllPlayers()) {
                if (!BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        return false;
    }

    static int channelOf(Character chr) {
        try {
            return chr.getClient().getChannel();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static Character liveBot(int charId) {
        BotSM sm = CharacterStorage.getBotById(charId);
        return sm == null ? null : sm.getChr();
    }

    static void say(Character bot, String node, String partner) {
        // rc6 say-do: "{PARTNER} brb pots" only from a bot that then goes for pots (else another line, else quiet)
        for (int i = 0; i < 4; i++) {
            String line = pick("Couple", node, Map.of("PARTNER", partner == null ? "" : partner));
            if (line == null) {
                return;
            }
            var claims = soloMapling.ArtificialPlayer.BotConvo.ClaimRules.classify(line);
            if (soloMapling.ArtificialPlayer.BotConvo.ClaimActions.canBack(bot, claims)) {
                BotSpeak(bot, line);
                soloMapling.ArtificialPlayer.BotConvo.ClaimActions.start(bot, claims);
                return;
            }
        }
    }

    static String pick(String root, String node, Map<String, String> tokens) {
        BotDialogueHandler.DialogueConstructor dialog = BotDialogueHandler.getDialogueCon("CoupleDialogue.yaml", root, node);
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        Collections.shuffle(lines);
        for (String line : lines) {
            String out = line;
            for (Map.Entry<String, String> t : tokens.entrySet()) {
                if (t.getValue() != null && !t.getValue().isBlank()) {
                    out = out.replace("{" + t.getKey() + "}", t.getValue());
                }
            }
            if (!out.matches(".*\\{[A-Z_]+}.*")) {
                return out;
            }
        }
        return null;
    }

    static void qa(String line) {
        if (QaLog.on(QaLog.Topic.COUPLE)) { // !qa cp-1, we-1
            QaLog.any(QaLog.Topic.COUPLE, line);
        }
    }

    // ── test seams ──

    /** Tests: the next load attempt runs at once (no wait after a failed one). */
    static void retryLoadForTest() {
        loadTriedAt = 0;
    }

    /** Tests: who counts as free for a wedding (null = the real check). */
    static void useWeddingFreeForTest(java.util.function.Predicate<Character> test) {
        weddingFree = test != null ? test : BotCouples::freeForWedding;
    }

    /** Tests: the roster size the default couple cap is computed from (null: the real roster). */
    static void useRosterSizeForTest(java.util.function.IntSupplier test) {
        rosterSize = test != null ? test : BotCouples::loadedRosterSize;
    }

    /** Tests: a store and an online lookup (nulls restore the real ones); forgets every couple and timer. */
    static void useForTest(CoupleStore testStore, IntFunction<Character> testOnline) {
        store = testStore != null ? testStore : new CoupleStore.Jdbc();
        online = testOnline != null ? testOnline : BotCouples::liveBot;
        couples.clear();
        nextAt.clear();
        OutfitLoans.clearForTest();
        loaded = false;
        loadTriedAt = 0;
        weddingFree = BotCouples::freeForWedding;
        lastStepAt = 0;
        lastFormRollAt = 0;
        lastFriendRollAt = 0;
        lastWeddingAt = 0;
        breakupsByDay.clear();
        breakupMegaphone = testOnline != null ? (speaker, other) -> true : (speaker, other) ->
                WorldEventMegaphones.getInstance().offerEvent(speaker, "DramaCoupleBreakup", Map.of("OTHER", other),
                        "couple:breakup:" + speaker.getId() + ":" + other);
    }
}
