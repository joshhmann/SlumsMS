package soloMapling.ArtificialPlayer.BotCoupleSystem;

import client.Character;
import client.inventory.InventoryType;
import client.inventory.Item;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotCustomization;
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
 * Ticket 58, 1.5.0: bot couples. Rare - at most {@code maxCouples} (4) pairs in the world - and only bot with bot:
 * bots never court players.
 *
 * <p><b>How a couple forms.</b> About twice a day the world rolls once: two roster bots (training or social, level 15+,
 * a man and a woman, within 20 levels) standing on the same map may get together. They stay together across restarts
 * ({@code botcouple}, migration 012).
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

    public enum Stage { DATING, MARRIED }

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
    record Candidate(int charId, int gender, int level, int channel, int mapId) {
    }

    /** Couple chairs a v83 client knows (BotCustomization's chair list): Amorian Loveseat and the two cake chairs. */
    static final int[] COUPLE_CHAIRS = {3012005, 3012010, 3012011};
    /** The loveseat every married couple owns. */
    static final int LOVESEAT = 3012005;
    /** Wedding rings (Moonstone, Star Gem, Golden Heart, Silver Swan); a couple wears one kind, by its id. */
    static final int[] WEDDING_RINGS = {1112803, 1112806, 1112807, 1112809};
    static final short RING_SLOT = -12;
    static final short CASH_HAT_SLOT = -101;

    private static final Map<Long, Couple> couples = new ConcurrentHashMap<>();
    private static volatile CoupleStore store = new CoupleStore.Jdbc();
    /** Ids of couples that are not stored (staged, or no migration): negative, so they never meet a table id. */
    private static final java.util.concurrent.atomic.AtomicLong memoryIds = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean loaded;
    private static volatile long lastStepAt;
    private static volatile long lastFormRollAt;
    private static volatile long lastWeddingAt;
    private static final Map<String, Long> nextAt = new ConcurrentHashMap<>();
    /** Matching-hat days: the bot's own hat to put back (0 = none). */
    private static final Map<Integer, Integer> ownHat = new ConcurrentHashMap<>();
    private static volatile IntFunction<Character> online = BotCouples::liveBot;

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

    static int maxCouples() {
        return (int) prop("maxCouples", 4);
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

    private static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (BotCouples.class) {
            if (loaded) {
                return;
            }
            for (Couple c : store.loadAll()) {
                couples.put(c.id(), c);
            }
            loaded = true;
        }
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

    /** Two bots that may become a couple: a man and a woman within 20 levels, both level 15+. */
    static boolean compatible(Candidate x, Candidate y) {
        return x.charId() != y.charId() && x.gender() != y.gender() && x.gender() <= 1 && y.gender() <= 1
                && x.level() >= 15 && y.level() >= 15 && Math.abs(x.level() - y.level()) <= 20;
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
        return WEDDING_RINGS[(int) Math.floorMod(c.id(), WEDDING_RINGS.length)];
    }

    // ── forming and breaking ──

    /**
     * Two bots become a couple now. False when either is already in one. A staged (GM) couple of on-demand bots is
     * kept in memory only.
     */
    public static Couple form(int aCharId, int bCharId, boolean staged, long now) {
        ensureLoaded();
        if (aCharId == bCharId || coupleOf(aCharId) != null || coupleOf(bCharId) != null) {
            return null;
        }
        Couple c = new Couple(0, aCharId - BotStore.BOT_ID_BASE, bCharId - BotStore.BOT_ID_BASE, Stage.DATING, now, 0,
                0, staged);
        long id = staged ? 0 : store.insert(c);
        if (id <= 0) {
            id = memoryIds.decrementAndGet(); // staged, or not stored (no migration): a memory-only id
        }
        c = c.withId(id);
        couples.put(id, c);
        log.info("Bots {} and {} are a couple now{}", aCharId, bCharId, staged ? " (staged)" : "");
        qa("FORMED couple #" + id + ": " + aCharId + " + " + bCharId + (staged ? " (staged)" : ""));
        return c;
    }

    public static boolean breakUp(long id) {
        Couple c = couples.remove(id);
        if (c == null) {
            return false;
        }
        if (!c.staged()) {
            store.delete(id);
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
        maybeForm(now, rng);
        Couple ready = null;
        for (Couple c : all()) {
            Character a = online.apply(c.charA());
            Character b = online.apply(c.charB());
            if (a == null || b == null) {
                continue;
            }
            behave(c, a, b, now, rng);
            if (ready == null && readyToMarry(c, now)) {
                ready = c;
            }
        }
        if (ready != null && now - lastWeddingAt >= hours("weddingGapHours", 24) && !BotWeddings.running()
                && soloMapling.worldevents.WorldEvents.curveFraction() >= 0.5 && rng.nextDouble() < 0.1) {
            lastWeddingAt = now;
            String problem = BotWeddings.start(ready, "couples");
            if (problem != null) {
                log.info("A bot wedding could not start: {}", problem);
            }
        }
    }

    private static void maybeForm(long now, Random rng) {
        if (couples.values().stream().filter(c -> !c.staged()).count() >= maxCouples()
                || now - lastFormRollAt < hours("formEveryHours", 12)) {
            return;
        }
        lastFormRollAt = now;
        if (rng.nextDouble() >= prop("formChance", 0.5)) {
            return;
        }
        Set<Integer> taken = new HashSet<>();
        for (Couple c : couples.values()) {
            taken.add(c.charA());
            taken.add(c.charB());
        }
        List<Candidate> candidates = new ArrayList<>();
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character chr = sm.getChr();
            if (chr == null || chr.getMap() == null || !(sm instanceof TrainingBot || sm instanceof SocialBot)
                    || BotRoster.member(chr.getId() - BotStore.BOT_ID_BASE) == null || withRealPlayer(chr)) {
                continue;
            }
            candidates.add(new Candidate(chr.getId(), chr.getGender(), chr.getLevel(), channelOf(chr), chr.getMapId()));
        }
        Candidate[] pair = pickPair(candidates, taken, rng);
        if (pair == null) {
            return;
        }
        Couple c = form(pair[0].charId(), pair[1].charId(), false, now);
        Character a = online.apply(pair[0].charId());
        Character b = online.apply(pair[1].charId());
        if (c != null && a != null && b != null) {
            say(a, "Formed", b.getName());
        }
    }

    /** What a couple does this minute; both are online. */
    static void behave(Couple c, Character a, Character b, long now, Random rng) {
        if (c.stage() == Stage.MARRIED) {
            wearRing(a, ringOf(c));
            wearRing(b, ringOf(c));
        }
        matchHats(c, a, b, now);
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
        if (a.getMapId() == b.getMapId() && realPlayerSees(a)) {
            if (take("mention:" + c.id(), now, TimeUnit.MINUTES.toMillis((long) prop("mentionMinutes", 20)))
                    && rng.nextDouble() < 0.5) {
                Character speaker = rng.nextBoolean() ? a : b;
                say(speaker, "Mention", (speaker == a ? b : a).getName());
            }
            maybeSit(c, a, b, now, rng);
        }
        if (noteDue(c, now) && rng.nextDouble() < 0.05) {
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

    private static void maybeSit(Couple c, Character a, Character b, long now, Random rng) {
        int chair = coupleChair(c, a, b);
        if (chair == 0 || !(CharacterStorage.getBotById(a.getId()) instanceof SocialBot)
                || !(CharacterStorage.getBotById(b.getId()) instanceof SocialBot)
                || a.getPosition() == null || b.getPosition() == null
                || a.getPosition().distance(b.getPosition()) > 250
                || !take("chair:" + c.id(), now, TimeUnit.MINUTES.toMillis((long) prop("chairMinutes", 15)))
                || rng.nextDouble() >= 0.5) {
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

    static void wearRing(Character bot, int ringId) {
        try {
            Item worn = bot.getInventory(InventoryType.EQUIPPED).getItem(RING_SLOT);
            if (worn == null || worn.getItemId() != ringId) {
                BotCustomization.EquipItem(bot, ringId, RING_SLOT);
            }
        } catch (RuntimeException e) {
            log.debug("Could not put a ring on {}", bot.getName(), e);
        }
    }

    /** On a matching day the second wears the first's cash hat; the next day it gets its own back. */
    private static void matchHats(Couple c, Character a, Character b, long now) {
        try {
            Item hatA = a.getInventory(InventoryType.EQUIPPED).getItem(CASH_HAT_SLOT);
            Item hatB = b.getInventory(InventoryType.EQUIPPED).getItem(CASH_HAT_SLOT);
            if (matchingDay(c, now) && hatA != null) {
                if (hatB == null || hatB.getItemId() != hatA.getItemId()) {
                    ownHat.putIfAbsent(b.getId(), hatB == null ? 0 : hatB.getItemId());
                    BotCustomization.EquipItem(b, hatA.getItemId(), CASH_HAT_SLOT);
                    qa("MATCHING " + b.getName() + " wears " + a.getName() + "'s hat " + hatA.getItemId());
                }
            } else {
                Integer own = ownHat.remove(b.getId());
                if (own != null) {
                    if (own > 0) {
                        BotCustomization.EquipItem(b, own, CASH_HAT_SLOT);
                    } else {
                        b.getInventory(InventoryType.EQUIPPED).removeSlot(CASH_HAT_SLOT);
                        b.equipChanged();
                    }
                }
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
        out.add("(* = online) max " + maxCouples() + " couples; next wedding allowed in "
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
        return sm != null && BotPopulationDirector.mayLogOut(sm) && bot.getEventInstance() == null;
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
        String line = pick("Couple", node, Map.of("PARTNER", partner == null ? "" : partner));
        if (line != null) {
            BotSpeak(bot, line);
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

    /** Tests: a store and an online lookup (nulls restore the real ones); forgets every couple and timer. */
    static void useForTest(CoupleStore testStore, IntFunction<Character> testOnline) {
        store = testStore != null ? testStore : new CoupleStore.Jdbc();
        online = testOnline != null ? testOnline : BotCouples::liveBot;
        couples.clear();
        nextAt.clear();
        ownHat.clear();
        loaded = false;
        lastStepAt = 0;
        lastFormRollAt = 0;
        lastWeddingAt = 0;
    }
}
