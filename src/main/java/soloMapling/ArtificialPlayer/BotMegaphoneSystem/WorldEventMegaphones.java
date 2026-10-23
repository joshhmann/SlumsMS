package soloMapling.ArtificialPlayer.BotMegaphoneSystem;

import client.Character;
import client.inventory.Item;
import server.ItemInformationProvider;
import soloMapling.ArtificialPlayer.BotCommandsPack.BotMegaphoneGate;
import soloMapling.ArtificialPlayer.BotCommandsPack.MegaphoneCommands;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.server.BotShutdown;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventSubscriber;
import soloMapling.server.EventMessageSystem.EventType;
import soloMapling.server.EventMessageSystem.GameEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

import static soloMapling.BotLogger.log;

/**
 * Ticket 47: bot megaphones tied to things that really happened - a boss kill, a rare drop, a party quest that is
 * actually forming - instead of only random lines from {@code MegaphoneDialogue.yaml}.
 * <ul>
 *   <li>The speaker is the bot it happened to (a bot's own kill or drop, an OPQ lobby bot that can be invited), or
 *   a bot standing on the same map as the real player it happened to. A headline boss (level
 *   {@code -Dsolomapling.worldMegaphoneHeadlineBossLevel}, default 100) killed by real players may also be
 *   congratulated by any online bot.</li>
 *   <li>Every shout still goes through {@link BotMegaphoneGate}. When the gate is closed the newest event waits in
 *   one pending slot (up to {@code -Dsolomapling.worldMegaphoneMaxDelaySeconds}, default 240) and the ambient
 *   megaphone ticker posts it before any random line.</li>
 *   <li>The same boss, item or recruiter is not shouted about again within
 *   {@code -Dsolomapling.worldMegaphoneCooldownSeconds} (default 600).</li>
 *   <li>{@code -Dsolomapling.worldMegaphones=false} turns the whole thing off.</li>
 * </ul>
 */
public final class WorldEventMegaphones implements EventSubscriber {

    static final String PACK = "MegaphoneDialogue.yaml";
    static final String PACK_ROOT = "MegaphoneBroadcast";
    static final long DEFAULT_COOLDOWN_SECONDS = 600;
    static final long DEFAULT_MAX_DELAY_SECONDS = 240;
    static final int DEFAULT_HEADLINE_BOSS_LEVEL = 100;
    static final int DEFAULT_RARE_DROP_MAX_CHANCE = 200;   // of 1,000,000: chaos scrolls are 150, most equips 700
    static final int MIN_BOSS_LEVEL = 20;

    /** One megaphone ready to go out: who says it, which line node, the tokens to fill, and a de-dup key. */
    public record Shout(Character speaker, String node, Map<String, String> tokens, String key, long createdAt) {
    }

    private static final WorldEventMegaphones INSTANCE = new WorldEventMegaphones();

    private final Map<String, Long> lastShoutAt = new ConcurrentHashMap<>();
    private final AtomicReference<Shout> pending = new AtomicReference<>();
    private volatile LongSupplier clock = System::currentTimeMillis;
    private volatile BooleanSupplier gate = BotMegaphoneGate::tryAcquire;
    private volatile Consumer<Shout> poster = WorldEventMegaphones::post;
    /** rc6: shouts a running !qa scenario is watching go out at once (see {@link #qaWatches}). */
    private volatile java.util.function.Predicate<String> forced = WorldEventMegaphones::qaWatches;
    private volatile Runnable gateNote = BotMegaphoneGate::note;
    private volatile java.util.function.BiConsumer<Runnable, Long> retryScheduler = (task, delayMs) ->
            soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                    .schedule(task, delayMs, TimeUnit.MILLISECONDS);
    private volatile boolean registered;

    private WorldEventMegaphones() {
    }

    public static WorldEventMegaphones getInstance() {
        return INSTANCE;
    }

    /** Subscribes to the world events once (called when SoloMapling starts). */
    public synchronized void register() {
        if (registered) {
            return;
        }
        EventBus bus = EventBus.getInstance();
        bus.subscribe(EventType.BOSS_KILL, this);
        bus.subscribe(EventType.RARE_DROP, this);
        bus.subscribe(EventType.PQ_FORMING, this);
        registered = true;
        log("[WorldEventMegaphones] Listening for boss kills, rare drops and forming PQs.");
    }

    public static boolean enabled() {
        return !"false".equalsIgnoreCase(System.getProperty("solomapling.worldMegaphones", "true"));
    }

    @Override
    public boolean matchesFilter(GameEvent event) {
        return enabled();
    }

    @Override
    public void onEvent(GameEvent event) {
        if (BotShutdown.isQuiesced()) {
            return;
        }
        Shout shout = switch (event.getType()) {
            case BOSS_KILL -> planBossKill(event);
            case RARE_DROP -> planRareDrop(event);
            case PQ_FORMING -> planPqForming(event);
            default -> null;
        };
        if (shout != null) {
            offer(shout);
        }
    }

    // ── what counts ────────────────────────────────────────────────────────────────────────────

    /**
     * A boss worth a megaphone: flagged boss, not a trivial one, and not a body part (Zakum's arms, Horntail's heads,
     * which die one by one before the boss itself).
     */
    public static boolean isNotableBoss(boolean isBoss, String name, int level) {
        if (!isBoss || name == null || name.isBlank() || level < MIN_BOSS_LEVEL) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT);
        return !(n.contains("'s ") || n.contains(" arm") || n.contains(" head") || n.contains(" tail")
                || n.contains(" wing") || n.contains(" leg") || n.contains(" hand") || n.contains("clone")
                || n.contains("summon"));
    }

    /**
     * A drop worth a megaphone: a scroll, white scroll or mastery book with a base chance of at most
     * {@code -Dsolomapling.rareDropMaxChance} (per million; default 200), or any equipment a boss dropped.
     */
    public static boolean isRareDrop(int itemId, int baseChance, boolean fromBoss, short questId) {
        if (questId > 0 || itemId <= 0 || baseChance <= 0) {
            return false;
        }
        int type = itemId / 1_000_000;
        int sub = itemId / 10_000;
        if (type == 1) {
            return fromBoss;
        }
        boolean scrollOrBook = sub == 204 || sub == 234 || sub == 228 || sub == 229;
        return type == 2 && scrollOrBook
                && baseChance <= Integer.getInteger("solomapling.rareDropMaxChance", DEFAULT_RARE_DROP_MAX_CHANCE);
    }

    // ── who says it ────────────────────────────────────────────────────────────────────────────

    Shout planBossKill(GameEvent event) {
        Character killer = event.getMapleCharacter();
        String boss = event.getMessage();
        Map<String, String> tokens = baseTokens(event);
        tokens.put("BOSS", boss);
        String key = "boss:" + boss.toLowerCase(Locale.ROOT) + ":" + event.getChannel();
        if (isSpeakableBot(killer)) {
            return shout(killer, "WorldBossKillSelf", tokens, key);
        }
        tokens.put("PLAYER", killer.getName());
        Character witness = pickBotOnMap(killer);
        if (witness != null) {
            return shout(witness, "WorldBossKillWitness", tokens, key);
        }
        if (event.getDetail() >= Integer.getInteger("solomapling.worldMegaphoneHeadlineBossLevel",
                DEFAULT_HEADLINE_BOSS_LEVEL)) {
            Character anyone = pickAnyBot();
            if (anyone != null) {
                return shout(anyone, "WorldBossKillHeard", tokens, key);
            }
        }
        return null;
    }

    Shout planRareDrop(GameEvent event) {
        Character owner = event.getMapleCharacter();
        Item item = event.getItem();
        if (item == null) {
            return null;
        }
        String itemName = ItemInformationProvider.getInstance().getName(item.getItemId());
        if (itemName == null || itemName.isBlank()) {
            return null;
        }
        Map<String, String> tokens = baseTokens(event);
        tokens.put("ITEM", itemName);
        tokens.put("MOB", event.getMessage());
        String key = "drop:" + owner.getId() + ":" + item.getItemId();
        if (isSpeakableBot(owner)) {
            return shout(owner, "WorldRareDropSelf", tokens, key);
        }
        tokens.put("PLAYER", owner.getName());
        Character witness = pickBotOnMap(owner);
        return witness == null ? null : shout(witness, "WorldRareDropWitness", tokens, key);
    }

    Shout planPqForming(GameEvent event) {
        Character recruiter = event.getMapleCharacter();
        if (!isSpeakableBot(recruiter)) {
            return null;
        }
        Map<String, String> tokens = baseTokens(event);
        tokens.put("PQ", event.getMessage());
        tokens.put("LEVEL", String.valueOf(recruiter.getLevel()));
        tokens.put("JOB", jobName(recruiter));
        // One call per PQ per cooldown, however many lobby bots are recruiting.
        return shout(recruiter, "WorldPQForming", tokens, "pq:" + event.getMessage().toLowerCase(Locale.ROOT));
    }

    private Shout shout(Character speaker, String node, Map<String, String> tokens, String key) {
        return new Shout(speaker, node, tokens, key, clock.getAsLong());
    }

    private static Map<String, String> baseTokens(GameEvent event) {
        Map<String, String> tokens = new ConcurrentHashMap<>();
        tokens.put("CH", String.valueOf(event.getChannel()));
        if (event.getMap() != null && event.getMap().getMapName() != null) {
            tokens.put("MAP", event.getMap().getMapName());
        }
        return tokens;
    }

    static String jobName(Character chr) {
        try {
            return chr.getJob().name().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("[0-9]", "").trim();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static boolean isSpeakableBot(Character chr) {
        if (chr == null || !BotHelpers.isBot(chr) || CharacterStorage.checkIfInvisibleBot(chr.getId())) {
            return false;
        }
        return CharacterStorage.getBotById(chr.getId()) != null;
    }

    private static boolean freeToTalk(Character chr) {
        return isSpeakableBot(chr) && !ConversationManager.getInstance().isInConversation(chr.getId());
    }

    /** A bot on the same map as {@code who}; a bot in their party first. */
    private static Character pickBotOnMap(Character who) {
        if (who.getMap() == null) {
            return null;
        }
        List<Character> bots = new ArrayList<>();
        for (Character chr : who.getMap().getAllPlayers()) {
            if (chr != who && freeToTalk(chr)) {
                if (who.getPartyId() > 0 && chr.getPartyId() == who.getPartyId()) {
                    return chr;
                }
                bots.add(chr);
            }
        }
        return bots.isEmpty() ? null : bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
    }

    private static Character pickAnyBot() {
        List<Character> bots = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr != null && chr.getMap() != null && freeToTalk(chr)) {
                bots.add(chr);
            }
        }
        return bots.isEmpty() ? null : bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
    }

    // ── when it goes out ───────────────────────────────────────────────────────────────────────

    /**
     * Posts the shout now if the gate allows, else keeps it as the pending one (a newer event replaces an older one).
     * Returns true when it went out.
     */
    public boolean offer(Shout shout) {
        long now = clock.getAsLong();
        if (forced.test(shout.key())) {
            // !qa (test server only): the scenario the tester runs is about this very megaphone. It skips the gate
            // and the cooldown, and the next bot megaphone still waits a full gap after it (owner QA 2026-10-23:
            // war declarations and recruit calls sat behind OPQ megaphones and never showed).
            gateNote.run();
            send(shout, now);
            return true;
        }
        Long last = lastShoutAt.get(shout.key());
        if (last != null && now - last < TimeUnit.SECONDS.toMillis(cooldownSeconds())) {
            return false;
        }
        if (gate.getAsBoolean()) {
            send(shout, now);
            return true;
        }
        Shout waiting = pending.get();
        if (waiting != null && routine(shout) && !routine(waiting)) {
            return false; // a routine PQ call never pushes a waiting guild or drama event out of the slot
        }
        if (pending.getAndSet(shout) == null) {
            // Try again just after the gate's gap, so the event does not wait for the next ambient tick.
            long gapMs = TimeUnit.SECONDS.toMillis(Math.max(1, Long.getLong("solomapling.botMegaphoneGapSeconds", 120)));
            retryScheduler.accept(() -> {
                try {
                    flushPending();
                } catch (RuntimeException e) {
                    log("[WorldEventMegaphones] retry failed: " + e);
                }
            }, gapMs + 1_000);
        }
        return false;
    }

    /**
     * Ticket 21: a scripted world event (invasion, boss sighting, treasure hunt...) that a bot shouts about. Same
     * gate, pending slot and cooldown as the events above.
     */
    public boolean offerEvent(Character speaker, String node, Map<String, String> tokens, String key) {
        if (!enabled() || speaker == null || BotShutdown.isQuiesced()) {
            return false;
        }
        Map<String, String> filled = new ConcurrentHashMap<>();
        tokens.forEach((k, v) -> {
            if (k != null && v != null) {
                filled.put(k, v);
            }
        });
        return offer(new Shout(speaker, node, filled, key, clock.getAsLong()));
    }

    /** Called by the ambient megaphone ticker: posts the pending event if there is one and the gate allows. */
    public boolean flushPending() {
        Shout shout = pending.get();
        if (shout == null) {
            return false;
        }
        long now = clock.getAsLong();
        if (now - shout.createdAt() > TimeUnit.SECONDS.toMillis(maxDelaySeconds())) {
            pending.compareAndSet(shout, null);
            return false;
        }
        if (!gate.getAsBoolean()) {
            return false;
        }
        if (!pending.compareAndSet(shout, null)) {
            return false;
        }
        send(shout, now);
        return true;
    }

    private void send(Shout shout, long now) {
        lastShoutAt.put(shout.key(), now);
        if (lastShoutAt.size() > 2_000) {
            long cutoff = now - TimeUnit.SECONDS.toMillis(cooldownSeconds());
            lastShoutAt.values().removeIf(at -> at < cutoff);
        }
        poster.accept(shout);
    }

    private static void post(Shout shout) {
        Character speaker = shout.speaker();
        if (speaker == null || speaker.getMap() == null) {
            return;
        }
        String line = pickLine(shout.node(), shout.tokens());
        if (line == null) {
            return;
        }
        MegaphoneCommands.BotSuperMegaphone(speaker, line);
        log("[WorldEventMegaphones] " + speaker.getName() + " : " + line);
        soloMapling.qa.QaLog.Topic topic = qaTopic(shout.key());
        if (topic != null && soloMapling.qa.QaLog.on(topic)) { // the line itself, so the QA log shows what went out
            soloMapling.qa.QaLog.any(topic, "MEGAPHONE " + speaker.getName() + " ch" + speaker.getClient().getChannel()
                    + ": " + line);
        }
    }

    /** PQ lobby calls: frequent and replaceable, unlike a guild or drama event. */
    static boolean routine(Shout shout) {
        return shout != null && "WorldPQForming".equals(shout.node());
    }

    /** The QA topic a megaphone key belongs to: guild wars and their drama, or bot guilds; null for the rest. */
    static soloMapling.qa.QaLog.Topic qaTopic(String key) {
        if (key == null) {
            return null;
        }
        if (key.startsWith("drama:war") || key.startsWith("drama:peace") || key.startsWith("drama:poach")
                || key.startsWith("drama:ally") || key.startsWith("drama:betray")) {
            return soloMapling.qa.QaLog.Topic.GUILD_WAR;
        }
        if (key.startsWith("guild:")) {
            return soloMapling.qa.QaLog.Topic.BOT_GUILD;
        }
        return null;
    }

    /** A !qa run is logging this megaphone's topic (only ever on the test server: live has no !qa). */
    static boolean qaWatches(String key) {
        soloMapling.qa.QaLog.Topic topic = qaTopic(key);
        return topic != null && soloMapling.qa.QaLog.on(topic);
    }

    /** A random line of the node with every {TOKEN} filled; lines with a token we cannot fill are skipped. */
    static String pickLine(String node, Map<String, String> tokens) {
        BotDialogueHandler.DialogueConstructor dialog = BotDialogueHandler.getDialogueCon(PACK, PACK_ROOT, node);
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        java.util.Collections.shuffle(lines);
        for (String line : lines) {
            String filled = fill(line, tokens);
            if (filled != null) {
                return filled;
            }
        }
        return null;
    }

    static String fill(String line, Map<String, String> tokens) {
        String out = line;
        for (Map.Entry<String, String> e : tokens.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                out = out.replace("{" + e.getKey() + "}", e.getValue());
            }
        }
        return out.matches(".*\\{[A-Z_]+}.*") ? null : out;
    }

    private static long cooldownSeconds() {
        return Math.max(0, Long.getLong("solomapling.worldMegaphoneCooldownSeconds", DEFAULT_COOLDOWN_SECONDS));
    }

    private static long maxDelaySeconds() {
        return Math.max(0, Long.getLong("solomapling.worldMegaphoneMaxDelaySeconds", DEFAULT_MAX_DELAY_SECONDS));
    }

    /** Test seam: which keys a QA run forces through, and what noting the gate does. */
    void forceForTest(java.util.function.Predicate<String> testForced, Runnable testGateNote) {
        forced = testForced == null ? key -> false : testForced;
        gateNote = testGateNote == null ? () -> { } : testGateNote;
    }

    Shout pendingShout() {
        return pending.get();
    }

    /** Test seam: clock, gate and poster (null restores the real one), and a clean slate. */
    void useForTest(LongSupplier testClock, BooleanSupplier testGate, Consumer<Shout> testPoster) {
        retryScheduler = testClock == null
                ? (task, delayMs) -> soloMapling.server.ExecutorServiceManager.getScheduledExecutorService()
                        .schedule(task, delayMs, TimeUnit.MILLISECONDS)
                : (task, delayMs) -> { };
        clock = testClock == null ? System::currentTimeMillis : testClock;
        gate = testGate == null ? BotMegaphoneGate::tryAcquire : testGate;
        poster = testPoster == null ? WorldEventMegaphones::post : testPoster;
        forced = testClock == null ? WorldEventMegaphones::qaWatches : key -> false;
        gateNote = testClock == null ? BotMegaphoneGate::note : () -> { };
        lastShoutAt.clear();
        pending.set(null);
    }
}
