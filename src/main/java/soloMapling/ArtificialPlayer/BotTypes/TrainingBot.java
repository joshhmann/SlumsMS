package soloMapling.ArtificialPlayer.BotTypes;

import soloMapling.ArtificialPlayer.BotChatTuning;
import soloMapling.ArtificialPlayer.BotRoster;

import client.Character;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeActivityQueue;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeDecision;
import soloMapling.ArtificialPlayer.BotLifeSystem.RestockTripExecutor;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime;
import soloMapling.ArtificialPlayer.BotLifeSystem.LifeExecutor;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.id.MapId;
import net.server.Server;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import net.server.world.World;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotAttackDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffDriver;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotSwingClock;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotCombatTuning;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths;
import soloMapling.ArtificialPlayer.BotCombatSystem.BotHabits;
import soloMapling.ArtificialPlayer.BotGearSystem.BotGear;
import soloMapling.ArtificialPlayer.BotScrollSystem.BotScrolling;
import soloMapling.ArtificialPlayer.BotHoldings;
import soloMapling.ArtificialPlayer.BotYieldCaps;
import soloMapling.ArtificialPlayer.BotOptionMenu;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import server.life.Monster;
import server.maps.MapleMap;
import server.maps.Portal;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotGrindSystem.DeepHub;
import soloMapling.ArtificialPlayer.BotGrindSystem.GrindBrain;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;
import soloMapling.ArtificialPlayer.BotGrindSystem.OffscreenRewardBudget;
import soloMapling.ArtificialPlayer.BotAttackSystem.OffscreenCombat;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotShopping;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.BotVitals;
import soloMapling.ArtificialPlayer.BotSupplySystem.OffscreenSupplies;
import soloMapling.ArtificialPlayer.BotSupplySystem.ShopCatalog;
import soloMapling.ArtificialPlayer.BotSupplySystem.SupplyNeeds;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapChooser;
import soloMapling.ArtificialPlayer.BotGrindSystem.SpotFinder;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMap;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingMapFinder;
import soloMapling.ArtificialPlayer.BotTownSystem.TownLoiter;
import soloMapling.ArtificialPlayer.BotWanderSystem.BotWanderSystem;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.EventMessageSystem.EventBus;
import soloMapling.server.EventMessageSystem.EventFactory;
import soloMapling.server.BotShutdown;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotEmote;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotFullChat;
import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botCancelChair;
import static soloMapling.BotLogger.log;

// A roaming grinder. Spawns in a base town, travels out to a level-appropriate map with mobs, grinds
// for a session, returns to a town hub, and repeats — organically, forever. First production consumer
// of GCMovement (LOD-governed movement) and BotAttackDriver (combat).
//
// Two state machines, kept separate (the design's core split):
//  - Macro brain: updateState() on the slow BotSM tick (2-6s): INIT -> IN_TOWN -> DECIDE -> GO_TRAIN
//    -> GRIND -> GO_TOWN -> IN_TOWN ... Tier-agnostic; only issues movement intents and accrues abstract EXP.
//  - Combat tier: one shared 0.5s ticker (ensureCombatTicker) swings every grinding bot whose map is
//    observed (GCMovement.isMapObserved). The single shared task drives all bots' combat — no thread per bot.
//
// EXP: when unobserved (the common case) the bot levels by cheap arithmetic (kills/min x per-kill-exp),
// applied silently (no packets) via setExp/setLevel. When a real player is on its map, real kills (the
// combat ticker) feed EXP through the normal engine path. Bots are ephemeral decoration — no economy
// impact, wiped on restart.
//
// Map discovery is deterministic from WZ: the bot finds level-appropriate field maps near its spawn
// town by BFS over the portal graph (TrainingMapFinder + MapMobIndex), reading mob levels straight from
// Map.wz/Mob.wz. No hand-authored region table; town-locality emerges from hop distance.
public class TrainingBot extends BotSM {

    // ── Tunables (decoration, not balance — rough is fine) ───────────────────
    // REAL-tier swing/decision cadence (shared ticker). Only OBSERVED grinders do real work here — combatTick
    // early-returns for non-GRIND and grind.tick early-returns on an unobserved map — so raising the rate
    // spends LOD bandwidth on the handful of on-screen bots (snappier reacquire, tighter jump-attack/blink
    // timing, more responsive loot vacuum) while unobserved grinders only pay a doubled cheap early-return.
    // UNTESTED: validate the combat sweep cost with !env perf before/after; revert to 500 if it climbs.
    private static final long COMBAT_TICK_MS = 250;          // was 500; observation-tiered by the early-returns above
    private static final double KILLS_PER_MIN = 30.0;        // abstract grind speed, where OffscreenCombat has no mob
    private static final long GRIND_MIN_MS = 600_000;        // a grind session lasts 10–20 min
    private static final long GRIND_MAX_MS = 1_200_000;
    // A bot that DIDN'T migrate to its first grind (warped in, or already on the map at the first decision) is
    // meant to look like it's been grinding a while, not just arrived — so its FIRST session is partially
    // elapsed (caught at a random point in a normal session), floored so it doesn't instantly turn around.
    private static final long MID_SESSION_FLOOR_MS = 90_000; // min remaining on a partially-elapsed first session
    // Travel watchdog: abandon a trip only after this long with NO progress at all — no hop completed AND no
    // movement on the current map. The deadline is pushed out every time the bot advances a map or moves, so
    // big maps and long multi-hop routes are never cut off for merely taking a while; only a genuinely wedged
    // trip (stuck in one spot, not changing maps) trips it, and onFail re-plans from here (never teleports).
    private static final long TRAVEL_TIMEOUT_MS = 120_000;
    private static final int TRAVEL_PROGRESS_EPS_PX = 24;    // min position change between ticks that counts as "moving"
    private static final long TOWN_DWELL_MIN_MS = 4_000;     // linger in town (settle) 4–10 s before deciding
    private static final long TOWN_DWELL_MAX_MS = 10_000;
    private static final double SHOP_VISIT_CHANCE = 0.4;     // chance a town visit includes a shop trip (timing variety)
    private static final long SHOP_DWELL_MIN_MS = 30_000;    // browse a store 30–90 s
    private static final long SHOP_DWELL_MAX_MS = 90_000;
    private static final long RESUPPLY_WALK_MAX_MS = 25_000; // ticket 09: walk to the shop NPC; buy on arrival or after this
    private static final int RESUPPLY_STAND_OFF_PX = 25;     // stand beside the NPC, not on it (plus up to 40 px scatter)
    // Town map id -> {potion shop map, weapon/armor shop map}. A bot in a listed town may detour to one or
    // both stores (in either order) before heading out, for organic startup/return timing. GMS v83 store maps.
    private static final Map<Integer, int[]> TOWN_SHOPS = Map.of(
            100000000, new int[]{100000102, 100000101}, // Henesys
            101000000, new int[]{101000002, 101000001}, // Ellinia
            102000000, new int[]{102000002, 102000001}, // Perion
            103000000, new int[]{103000002, 103000001}, // Kerning City
            104000000, new int[]{104000002, 104000001}, // Lith Harbor
            200000000, new int[]{200000002, 200000001}, // Orbis
            220000000, new int[]{220000002, 220000001}, // Ludibrium
            211000000, new int[]{211000102, 211000101}  // El Nath (Department Store/potion, Weapon Store/equip — reached via El Nath Market hub)
    );
    // Sleepywood has no potion/equip stores; its town "errand" is a flavor trip to the hotel sauna instead.
    // The sauna entry is a non-portal NPC teleport (smoke-and-mirrors warp); the exit is a real one-way portal
    // back into the hotel. Only bots whose home town is Sleepywood ever run this (see doInTown / startSaunaTrip).
    private static final int SLEEPYWOOD_HOTEL = 105040400;   // hotel hub, reached by a portal from Sleepywood town
    private static final int SAUNA_REGULAR = 105040401;
    private static final int SAUNA_VIP = 105040402;
    private static final int SAUNA_DOOR_X = -19;             // the in-hotel "sauna door" spot (not a portal): walk here
    private static final int SAUNA_DOOR_Y = -208;            //   for the visual, then warp into the chosen sauna
    private static final double SAUNA_VIP_CHANCE = 0.40;     // 60% regular sauna / 40% VIP sauna
    private static final long DOOR_WALK_MAX_MS = 20_000;     // cap the door walk so a missed arrival can't stall the trip
    private static final long SAUNA_TRIP_TIMEOUT_MS = 300_000; // whole-trip watchdog → force-recover to town if it hangs
    private static final int LEVEL_CAP = 195;                // guard (no real cap intended — see spec)
    // Map-selection policy (bands, chill visits, wander radius, fit/distance/capacity weighting, the
    // occupancy registry) lives in BotGrindSystem/TrainingMapChooser — doDecide only choreographs.
    // First-trip warp: on the VERY FIRST decision to go train (mainly world startup), most bots teleport
    // straight to the picked grind map instead of walking there, so the world looks already-populated with
    // grinders rather than a town-emptying migration. One-time only (firstTrip) — every later trip travels
    // naturally. Gated on the town being unobserved so a watching player never sees a bot blink out.
    private static final double FIRST_TRIP_TELEPORT_CHANCE = 0.70;

    // ── Grind breaks (rest flavor) — all state, knobs, and behavior live in GrindBreakRoutine ──

    // ── Self-repair watchdog (macro tick) ────────────────────────────────────
    // If the bot is observed but lands no hit for this long it's genuinely stuck (wedged / boxed in / dead
    // map): teleport to a portal and re-grind; if that still doesn't help, leave the map. A landed hit (the
    // grind engine's combat heartbeat) resets the whole ladder. The roam/loot/climb tunables now live in GrindBrain.
    private static final long STUCK_TELEPORT_MS = 30_000;     // no landed hit this long (observed) → teleport to a portal, re-grind
    private static final long STUCK_BAIL_MS = 60_000;         // still no hit after a teleport → bail the map (re-DECIDE)
    private static final long REPAIR_COOLDOWN_MS = 12_000;    // min gap between repair actions, so each one gets a window to prove out

    // ── Map crowding balance (spread the cohort across maps, not just spots) ──────
    // A map's bot carrying capacity = its claimable-spot count (SpotFinder.mapBotCapacity), so "map at
    // capacity" IS "every spot claimed" by construction. Selection (weightedPick) hard-caps at this; the
    // crowd-bail relieves a map that fills up after bots commit by sending the surplus a hop deeper.
    private static final long MAP_SATURATED_DWELL_MS = 8_000; // saturation must persist this long before a crowd-bail (ride out arrival races)
    private static final long MAP_EXCLUDE_MS = 45_000;        // don't re-pick a map we just left for crowding
    private static final int MAX_MAP_HOPS_PER_EPISODE = 2;    // crowd-driven map changes before the bot settles & shares
    // 1.5.0 rc3 KS manners: map changes away from a real player's platforms. They bypass the crowd hop cap (the bot
    // must not settle on a player's platform); past this many the bot goes to town instead (which resets both).
    static final int MAX_YIELD_HOPS_PER_EPISODE = 4;
    // Crowd-bail runs unobserved too: an unobserved changeMap is invisible and near-free, and it levels
    // crowding BEFORE a player arrives — with the old observed-only gate, a stacked map showed its full
    // stack at the exact moment someone first walked in. Unobserved bots tick at 60-120s while grinding,
    // so unobserved relief paces itself in minutes, not seconds. Flip to false to restore the old gate.
    private static final boolean CROWD_BAIL_UNOBSERVED = true;

    // ── Ambient dialogue (context-token flavor; spoken only when observed, throttled) ──
    // Idle self-muttering only - reacting to nearby players is the movement driver's job
    // (BotPlayerReaction), so this stays deliberately sparse to avoid talkative spam.
    private static final long AMBIENT_MIN_MS = 120_000;      // min gap between idle grind lines (2 min)
    private static final long AMBIENT_MAX_MS = 240_000;      // max gap (4 min)
    // Self-buff cadence while grinding: re-show the bot's class buffs every 90-120s so a watched grinder
    // looks like it's keeping itself buffed (cosmetic - BotBuffDriver shows visuals only, no real stats).
    private static final long BUFF_MIN_MS = 90_000;
    private static final long BUFF_MAX_MS = 120_000;

    // ── Shared combat ticker (one task for ALL training bots) ────────────────
    private static final Set<TrainingBot> ACTIVE_GRINDERS = ConcurrentHashMap.newKeySet();
    private static volatile boolean combatTickerStarted = false;
    // Ticket 09: the shared ticker's future, kept so shutdown can cancel it (one task for all grinders).
    private static volatile ScheduledFuture<?> combatTicker;

    private static synchronized void ensureCombatTicker() {
        if (combatTickerStarted || BotShutdown.isQuiesced()) {
            return;
        }
        combatTickerStarted = true;
        soloMapling.ArtificialPlayer.BotGrindSystem.LevelingPathCensus.startReporterIfConfigured(); // ticket 31
        combatTicker = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(
                TrainingBot::combatTickAll, COMBAT_TICK_MS, COMBAT_TICK_MS, TimeUnit.MILLISECONDS);
        BotShutdown.onQuiesce(TrainingBot::stopCombatTicker);
    }

    /* Ticket 09: cancel the shared ticker. Combat is presentation - nothing needs draining for it. */
    private static void stopCombatTicker() {
        ScheduledFuture<?> ticker = combatTicker;
        if (ticker != null) {
            ticker.cancel(false);
        }
    }

    // Swing every grinding bot whose map a real player can see. One exception per bot never stops the ticker.
    private static void combatTickAll() {
        if (BotShutdown.isQuiesced()) {
            return; // Ticket 09: no combat sweep across maps that are being disposed
        }
        long sweepStart = System.currentTimeMillis();
        for (TrainingBot bot : ACTIVE_GRINDERS) {
            try {
                bot.sweepVisit(sweepStart);
            } catch (Exception e) {
                // a single bot's combat error must never kill the shared ticker
            }
        }
        soloMapling.server.BotPerfStats.recordCombatSweep(
                System.currentTimeMillis() - sweepStart, ACTIVE_GRINDERS.size());
    }

    // How many bots the shared combat ticker currently visits (for !env perf).
    public static int activeGrinderCount() {
        return ACTIVE_GRINDERS.size();
    }

    // Fable Phase 3: an unobserved GRINDING bot is the deep-background case - abstract
    // EXP accrues by elapsed time (doGrind) and the watchdog skips unobserved bots, so
    // a 60-120s heartbeat loses nothing and cuts the idle macro load ~10x. All other
    // phases (travel/decide/town/shop) keep the normal slow tick so the bot's life
    // keeps moving between grind sessions. A player entering the map still wakes the
    // bot instantly via the map-entry nudge.
    @Override
    protected long lowPriorityDelayMs() {
        if (phase == Phase.GRIND) {
            return 60_000 + rng.nextInt(60_000);
        }
        return super.lowPriorityDelayMs();
    }

    private void combatTick() {
        Character chr = getChr();
        if (chr == null || !getRunning() || phase != Phase.GRIND || BotDeaths.isDead(chr.getId())) {
            return; // gated; removal happens in leaveGrind()/stopScheduledTask() (and a dead bot does nothing)
        }
        grind.tick(chr); // observed → spot grind (FIGHT⇄WAIT); unobserved → no-op (the macro tick accrues abstract EXP)
    }

    // 1.5.0 attack pacing (BotSwingClock): the next swing goes out the moment the last one ends, not on the next
    // 250 ms sweep. The sweep skips a bot whose swing is still playing and whose due tick is armed.
    private final BotSwingClock swingClock = new BotSwingClock();

    private void sweepVisit(long now) {
        Character chr = getChr();
        if (chr != null && !swingClock.sweepVisits(now, BotAttackDriver.swingEndsAt(chr.getId()))) {
            return; // mid-swing: its due tick runs it when the pose ends
        }
        swingClock.trySweep(this::pacedCombatTick);
    }

    // A combat tick, then a due tick armed for the moment the bot's swing (or its turn to face a mob) ends - only on an
    // observed map, where somebody sees the cadence; unobserved bots keep the coarse tick.
    private void pacedCombatTick() {
        combatTick();
        Character chr = getChr();
        if (chr != null && getRunning() && phase == Phase.GRIND && GCMovement.isMapObserved(chr.getMapId())) {
            swingClock.armAt(BotAttackDriver.swingEndsAt(chr.getId()), System.currentTimeMillis(),
                    this::pacedCombatTick);
        }
    }

    // ── Macro brain state ────────────────────────────────────────────────────
    enum Phase { INIT, IN_TOWN, SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN, DECIDE, GO_TRAIN, GRIND, GO_TOWN,
        BREAK_TRAVEL, BREAK_REST, LIFE_TRAVEL, LIFE_DWELL }

    private volatile Phase phase = Phase.INIT;
    /** Read-only telemetry for the daily-life shadow inspector; not another owner of the FSM. */
    public record LifePhase(String name, long sinceMs) {}
    private volatile LifePhase lifePhase = new LifePhase("INIT", 0);
    private final LifeActivityQueue lifeActivities = new LifeActivityQueue();
    private final LifeRuntime dailyLife;
    private int lifeDestination = -1;
    private LifeDecision.Activity lifeVisit = LifeDecision.Activity.HANG_OUT;
    private boolean lifeFishingStarted;
    public LifeRuntime lifeRuntime() { return dailyLife; }

    public LifePhase lifePhase() { return lifePhase; }
    // Ticket 21: a world event called this bot (WorldEventReactions); the macro tick answers it.
    private volatile boolean eventCall = false;
    private volatile boolean restockCall = false; // rc6 say-do: it said "brb pots": go and buy them
    private boolean phaseEntered = false;     // has this phase run its one-time setup?
    private long phaseDeadlineMs = 0;         // travel timeout / town dwell

    // Async travel coordination — the callback fires on the GCMovement driver thread.
    private volatile boolean moveDone = false;
    private volatile boolean moveOk = false;
    // Travel progress watchdog: the trip's deadline resets whenever the bot advances a hop or moves, so the
    // 2-min budget means "no progress for 2 min", not "the whole trip within 2 min".
    private int travelLastMapId = -1;
    private Point travelLastPos = null;

    private int homeMapId = -1;           // the spawn town; GO_TOWN returns here
    private int pendingHomeMapId = -1;    // ticket 03: another region's hub, adopted as home once the bot arrives
    private boolean firstTrip = true;     // first decision to go train: maybe warp straight there (see FIRST_TRIP_TELEPORT_CHANCE)
    private boolean midSessionGrind = false; // next grind starts partially elapsed (warped/in-place first trip — see MID_SESSION_FLOOR_MS)
    private final List<Integer> shopQueue = new ArrayList<>(); // store maps still to visit this town stop
    private int shopTargetMapId = -1;     // the store currently being travelled to
    // Ticket 09: this town stop's restock - the shop NPC the bot walks to and buys at (null when none is pending).
    private volatile ShopCatalog.Site resupplySite = null;
    private boolean saunaResupply = false; // Sleepywood: the shop is in the sauna, so the sauna trip is the shop trip
    private volatile boolean resupplyArrived = false;
    private long resupplyWalkDeadlineMs = 0;
    private int currentTrainMapId = -1;   // the discovered map currently being trained on
    private int currentMobLevel = 0;      // its representative mob level (drives abstract EXP)
    private long grindUntilMs = 0;
    private long lastExpAccrualMs = 0;
    private long nextChatterMs = 0;        // throttle gate for ambient grind chatter
    private long nextBuffMs = 0;           // re-buff gate while grinding (only advances after an actual buff)
    private int lastKnownLevel = -1;       // tracks level to detect a level-up worth announcing

    // Sleepywood sauna flavor trip — an async errand that runs off the macro FSM; the IN_TOWN tick parks on it.
    private volatile boolean saunaTripActive = false; // a sauna trip's async chain is in flight
    private volatile boolean saunaTripDone = false;   // chain finished → macro tick advances to DECIDE
    private volatile long saunaTripDeadlineMs = 0;    // whole-trip watchdog deadline

    // ── Grind engine + macro watchdog state ──
    // The per-bot localized grind sub-FSM (SELECT_SPOT→TRAVEL→FIGHT⇄WAIT→RELOCATE). TrainingBot keeps the
    // macro brain and delegates each observed combat tick to it; it owns the combat heartbeat the watchdog reads.
    private final GrindBrain grind = new GrindBrain(this::debugChat);
    private boolean teleportedThisEpisode = false; // macro watchdog: a portal-teleport has already been tried this stuck episode
    private long lastRepairMs = 0L;             // macro watchdog: last self-repair action (cooldown gate)

    // ── Map crowding state (macro tick only) ──
    // Maps this bot recently left because they were saturated (mapId -> cooldown expiry), so DECIDE steers
    // away from them for a beat; the per-outing crowd-hop counter so a fully-packed region settles into
    // sharing instead of migrating forever; and the persist timer for the saturation dwell.
    private final Map<Integer, Long> mapCrowdCooldown = new HashMap<>();
    private int crowdHopsThisEpisode = 0;
    private int yieldHopsThisEpisode = 0;
    private long mapSaturatedSinceMs = 0L;       // when the current grind first read saturated (0 = not saturated)

    // ── Grind breaks (macro tick only; state + behavior in GrindBreakRoutine) ──
    private final GrindBreakRoutine breaks = new GrindBreakRoutine(this);

    private final Random rng = new Random();

    // Interactive menu (a player chats the bot's name; the Dispatcher routes it here via
    // displayCommands). Rides the shared inquirer/tertiary path - see BotOptionMenu. Two variants:
    // "Follow me!" only appears once the bot is actually in a party (asking a stranger to be
    // followed is meaningless), and "Wanna party up?" disappears once it is.
    // Keyword lists are matched by substring IN OPTION ORDER - keep the flavor-chat option's
    // keywords narrow so a typed sentence like "how about joining my party" can't hijack the
    // party roll (greedy "how"/"train" did exactly that in live test round 2).
    private final BotOptionMenu soloMenu = new BotOptionMenu(this,
            List.of("How's the training?", "Wanna party up?", "Goodbye"),
            List.of(List.of("hows", "how is", "how goes"),
                    List.of("party", "team", "join"),
                    List.of("bye", "goodbye", "cya", "later")),
            this::onSoloMenuSelect);
    private final BotOptionMenu partyMenu = new BotOptionMenu(this,
            List.of("How's the training?", "Follow me!", "Goodbye"),
            List.of(List.of("hows", "how is", "how goes"),
                    List.of("follow", "come", "lead"),
                    List.of("bye", "goodbye", "cya", "later")),
            this::onPartyMenuSelect);

    public TrainingBot(Character character) {
        super(character);
        dailyLife = new LifeRuntime(this);
        botType = "TrainingBot";
        dialoguePath = "TrainingBotDialogue.yaml";
    }

    @Override
    public void displayCommands(Character chr) {
        if (getChr().getParty() != null) {
            soloMenu.deactivate();
            partyMenu.show(chr);
        } else {
            partyMenu.deactivate();
            soloMenu.show(chr);
        }
    }

    /** Ticket 37: "follow me" from a party member does what the party menu's "Follow me!" does. */
    @Override
    public boolean naturalAction(Character player, String said) {
        String lower = said == null ? "" : said.toLowerCase(java.util.Locale.ROOT);
        if (inSameParty(player) && (lower.contains("follow") || lower.matches(".*\\b(come with me|come on)\\b.*"))) {
            optFollowMe(player);
            return true;
        }
        return false;
    }

    /**
     * Ticket 14: a party order to a partied trainer. "follow" makes it the player's follower again, as "Follow
     * me!" does; it is already stationed and grinding, so "stop" and "grind" only get a word back - and none when
     * the order went to every party bot.
     */
    public void order(soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser.Verb verb, Character player,
                      boolean everyone) {
        if (!inSameParty(player)) {
            return;
        }
        switch (verb) {
            case FOLLOW -> optFollowMe(player);
            case STAY -> {
                if (!everyone) {
                    sayRecruit("HoldSpot", player);
                }
            }
            case GRIND -> {
                if (!everyone) {
                    sayRecruit("AlreadyGrinding", player);
                }
            }
        }
    }

    private boolean menuActive() {
        return soloMenu.isActive() || partyMenu.isActive();
    }

    // Mid-recruit: a conversation is open or the bot said "invite me" and the window is live.
    // Grind self-repair (teleport/bail), crowd-bail, and the session-end walk-off all hold during
    // this - a bot that says yes and then blinks across the map breaks the exchange.
    boolean recruitingNow() { // package-visible: GrindBreakRoutine defers its stand-up on a live recruit
        return menuActive() || BotRecruitManager.isArmed(getChr().getId())
                || LifeRuntime.enabled() && soloMapling.ArtificialPlayer.BotConversation.hasAnyChat(getChr().getId());
    }

    // Conversing players expect snappy replies; and once the bot has said "invite me" it must keep
    // ticking fast through the whole armed window so pollRecruitInvite drains the incoming invite
    // promptly (not just while the menu is open). The armed window is covered once, for every type, by
    // the base class's commitment gate (BotCommitments) - which also outranks the 60-120s deep-grind
    // override below - so only the bot's own menu is left here.
    @Override
    public void checkPrioritySpeed() {
        if (menuActive()) {
            pinFastCadence();
            return;
        }
        super.checkPrioritySpeed();
    }

    private void enterPhase(Phase next) {
        if (phase == Phase.SHOP_DWELL) {
            BotWanderSystem.stop(getChr()); // leaving a shop: end the flavor wander before travelling out
        }
        if (phase == Phase.IN_TOWN || phase == Phase.LIFE_DWELL) {
            TownLoiter.stop(getChr()); // leaving town: end the loiter (stop fidget, free the ledge claim)
            if (lifeVisit == LifeDecision.Activity.FISH) {
                var activity = lifeActivities.state();
                if (activity.active() != 0) lifeActivities.finish(activity.active(), now() >= phaseDeadlineMs
                        ? LifeActivityQueue.End.COMPLETED : LifeActivityQueue.End.CANCELLED);
                soloMapling.fishing.GameFishing.cancel(getChr());
                if (getChr().getChair() == soloMapling.fishing.FishingPolicy.CHAIR) botCancelChair(getChr());
            }
        }
        phase = next;
        if (getChr() != null && next != Phase.LIFE_TRAVEL && next != Phase.GO_TRAIN) {
            soloMapling.ArtificialPlayer.BotLifeSystem.LifeTownSlots.release(getChr().getId());
        }
        lifePhase = new LifePhase(next.name(), now());
        if (dailyLife != null) dailyLife.phase(next.name(), now());
        lifeActivities.invalidateWaiting();
        if (next == Phase.GRIND) {
            var active = lifeActivities.state();
            if (active.active() != 0) {
                lifeActivities.finish(active.active(), LifeActivityQueue.End.COMPLETED);
                lifeNote("RETURNED request=" + active.active()); // returned to grinding, not proof of a purchase
            }
        }
        phaseEntered = false;
        moveDone = false;
        moveOk = false;
        debugChat("phase -> " + next);
    }

    @Override
    public void updateState() {
        super.updateState();
        if (checkIfNotRunningOrPaused()) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        getDebugger().debugLoggingFull(
                String.format("%s TrainingBot phase: %s", chr.getName(), phase), String.format("%s", phase));

        if (handleDeath(chr)) {
            cancelLifeActivity();
            return; // 1.5.0 rc3: lying dead, or just respawned in town
        }

        if (restockCall) {
            restockCall = false;
            if (phase == Phase.GRIND && !recruitingNow()) {
                leaveGrind();
                enterPhase(Phase.GO_TOWN);
                soloMapling.ArtificialPlayer.BotConvo.SayDo.event(chr, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.RESUPPLY,
                        homeMapId);
            }
        }
        if (eventCall) {
            eventCall = false;
            cancelLifeActivity();
            answerEventCall(chr);
        }

        if (pausedForChat(chr)) {
            pollRecruitInvite();
            soloMenu.poll();
            partyMenu.poll();
            return; // 1.5.1: a player is talking to it: no trip out of here until the chat hold ends
        }

        // Existing urgent work wins before a new life choice is accepted or announced.
        if (LifeRuntime.enabled()) dailyLife.tick(now());
        runLifeActivities();

        switch (phase) {
            case INIT -> doInit();
            case IN_TOWN -> doInTown();
            case SHOP_TRAVEL -> doTravel(shopTargetMapId, Phase.SHOP_DWELL, Phase.SHOP_RETURN);
            case SHOP_DWELL -> doShopDwell();
            case SHOP_RETURN -> doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS,
                    Phase.DECIDE, Phase.DECIDE);
            case DECIDE -> doDecide();
            case GO_TRAIN -> doTravel(currentTrainMapId, Phase.GRIND, Phase.DECIDE);
            case LIFE_TRAVEL -> doTravel(lifeDestination, Phase.LIFE_DWELL, Phase.DECIDE);
            case LIFE_DWELL -> doLifeDwell();
            case GRIND -> doGrind();
            case GO_TOWN -> doTravel(homeMapId >= 0 ? homeMapId : MapId.HENESYS,
                    Phase.IN_TOWN, Phase.IN_TOWN);
            case BREAK_TRAVEL -> doBreakTravel();
            case BREAK_REST -> doBreakRest();
        }

        pollRecruitInvite(); // gated party-invite drain (the "wanna party up?" flow)
        soloMenu.poll();
        partyMenu.poll();    // kept last: a "Follow me!" selection converts this bot away
    }

    // ── Conversation hold (1.5.1) ────────────────────────────────────────────

    /** True while a trip phase is paused because a player is talking to this bot; the trip restarts after. */
    private boolean chatPaused = false;

    /**
     * Phases that take the bot off the spot a player is talking to it at: a trip to a store, back home, out to
     * train or to town, and the decision that sets one off. A grind (and its rest break) stays on its map, and a
     * town stop already waits for the chat ({@link #doInTown}).
     */
    static boolean tripPhase(Phase p) {
        return p == Phase.SHOP_TRAVEL || p == Phase.SHOP_RETURN || p == Phase.DECIDE || p == Phase.GO_TRAIN
                || p == Phase.GO_TOWN;
    }

    /**
     * 1.5.1 (owner QA: a player talked to LilZakum in Henesys, and 3 s later it set off for Orbis - the hold was only
     * read by the town stop, not by the shop return or the decision after it). While the chat holds, a trip phase
     * waits where the bot stands: the trip in progress is stopped once, and restarts from here when the hold ends.
     * A world event's call, a ride it is queued for or aboard, and a party trip are not stopped. Cheap: a map read.
     */
    private boolean pausedForChat(Character chr) {
        boolean held = tripPhase(phase) && soloMapling.ArtificialPlayer.BotConversation.isHoldingChat(chr)
                && soloMapling.worldevents.WorldEventSteer.mapFor(chr.getId()) < 0 && partyTargetMapId() == null
                && !GCMovement.isInTransit(chr);
        if (!held) {
            if (chatPaused) {
                chatPaused = false;
                phaseEntered = false; // the stopped trip starts again from where the bot stands
                moveDone = false;
                moveOk = false;
                debugChat("chat hold over: " + phase + " resumes");
            }
            return false;
        }
        if (!chatPaused) {
            chatPaused = true;
            GCMovement.stop(chr); // cancels the trip (no callback) and the walk to its portal
            debugChat("chat hold: " + phase + " waits");
        }
        return true;
    }

    /** Tests: whether a trip phase is waiting for a chat. */
    boolean chatPausedForTest() {
        return chatPaused;
    }

    /** Tests: puts the bot in a phase as if it had just entered it. */
    void enterPhaseForTest(Phase next) {
        enterPhase(next);
    }

    /** Tests: runs only the chat-hold check of a tick. */
    boolean pausedForChatForTest() {
        return pausedForChat(getChr());
    }

    // ── Deaths (1.5.0 rc3, owner decision 5) ─────────────────────────────────

    private int walkBackMapId = -1; // the map it died on: after town it walks back there

    // A bot that died (BotDeaths) lies still for a few seconds, then respawns in the map's return town, where the town
    // stop rests and restocks it; its next DECIDE walks back to the map it died on. True while this owns the tick.
    private boolean handleDeath(Character chr) {
        if (!BotDeaths.isDead(chr.getId())) {
            return false;
        }
        if (!BotDeaths.readyToRespawn(chr.getId())) {
            return true; // still lying there
        }
        int diedOn = chr.getMapId();
        int back = currentTrainMapId >= 0 ? currentTrainMapId : diedOn;
        if (isBreak(phase)) {
            breaks.abandon(chr); // sign down, rest/idle cleared, no saved remainder (a fresh session after town)
        }
        if (phase == Phase.GRIND || isBreak(phase)) {
            leaveGrind(); // a break is still part of the grind: ACTIVE_GRINDERS, grinding nav, train slot
        }
        GCMovement.releaseCommitmentHold(chr);
        int town = chr.getMap() != null ? chr.getMap().getReturnMapId() : -1;
        if (town <= 0 || town == MapId.NONE || MapMobIndex.level(town) >= 0) {
            town = homeMapId >= 0 ? homeMapId : MapId.HENESYS;
        }
        chr.respawn(town);
        walkBackMapId = back;
        log("[TrainingBot] " + chr.getName() + " died on map " + diedOn + "; respawned in " + town
                + ", will walk back to " + back);
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS, "RESPAWN " + chr.getName() + " died on "
                    + diedOn + ", respawned in town " + town + ", will rest, restock and walk back to " + back);
        }
        enterPhase(Phase.IN_TOWN);
        return true;
    }

    /**
     * 1.5.0 rc3: a couple, guild or boss-run channel move takes this bot (BotChannels.moveToChannel). A grind or a
     * grind break is left cleanly first (no stale spot claim, train slot, break sign or remainder); it re-decides.
     */
    public void leaveForChannelMove() {
        cancelLifeActivity();
        Phase p = phase;
        if (p != Phase.GRIND && !isBreak(p)) {
            return;
        }
        Character chr = getChr();
        if (isBreak(p)) {
            breaks.abandon(chr);
        }
        leaveGrind();
        enterPhase(Phase.DECIDE);
    }

    static boolean isBreak(Phase p) {
        return p == Phase.BREAK_TRAVEL || p == Phase.BREAK_REST;
    }

    // ── World events (ticket 21) ─────────────────────────────────────────────

    /**
     * rc6 say-do: the bot just said "brb pots" (or another restock line): it leaves the grind for town at its next
     * tick and runs the normal resupply trip (GO_TOWN, shop, back). False when it is not grinding or is mid-recruit,
     * so the caller can pick a line without the claim instead.
     */
    public boolean requestRestockTrip() {
        if (phase != Phase.GRIND || recruitingNow()) {
            return false;
        }
        restockCall = true;
        nudgeSoon(300);
        return true;
    }

    /** Prepare once, then retry with the same request object. A newer plan makes older waiting plans stale. */
    public LifeActivityQueue.Request prepareLifeActivity(LifeDecision.Result decision) {
        return lifeActivities.prepare(decision, now(), LifeActivityQueue.MAX_WAIT_MS);
    }

    /** Safe to call from a command/worker: it queues work and never changes phase on the calling thread. */
    public LifeActivityQueue.Offer submitLifeActivity(LifeActivityQueue.Request request) {
        if (!lifeExecutionEnabled()) return LifeActivityQueue.Offer.DISABLED;
        var offered = lifeActivities.offer(request, now());
        if (offered == LifeActivityQueue.Offer.QUEUED || offered == LifeActivityQueue.Offer.REPLACED) {
            nudgeSoon(300); // outside the queue lock; the existing wheel still owns the actual update
        }
        return offered;
    }

    public LifeActivityQueue.State lifeActivityState() { return lifeActivities.state(); }
    public boolean isStartingLifeActivity() { return lifeActivities.isStartingHere(); }

    public boolean cancelLifeRequest(LifeActivityQueue.Request request) {
        return lifeActivities.cancelWaiting(request);
    }

    public static boolean lifeExecutionEnabled() {
        return (LifeRuntime.enabled() || soloMapling.qa.QaSuite.isEnabled())
                && Boolean.getBoolean("slums.botlife.execute");
    }

    private boolean freeForLifeActivity() {
        if (LifeRuntime.enabled()) return freeForAutomaticLifeActivity(now());
        return getRunning() && phase == Phase.GRIND && !recruitingNow()
                && getChr() != null && getChr().getMap() != null
                && !soloMapling.ArtificialPlayer.BotCommitments.inFlight(this)
                && !soloMapling.ArtificialPlayer.BotConversation.hasAnyChat(getChr().getId())
                && soloMapling.ArtificialPlayer.BotPopulationDirector.mayLogOut(this);
    }

    private void runLifeActivities() {
        if (!lifeActivities.hasWaiting()) return;
        var result = lifeActivities.drain(now(), lifeExecutionEnabled(), this::freeForLifeActivity,
                LifeRuntime.enabled() ? new LifeExecutor(this) : new RestockTripExecutor(this));
        if (result.attempt().isPresent()) {
            lifeNote("REQUEST request=" + result.number() + " outcome=" + result.attempt().get().status());
            if (LifeRuntime.enabled() && result.attempt().get().accepted().isPresent()) {
                dailyLife.accepted(result.attempt().get().accepted().get().activity(), now());
                var action = result.attempt().get().accepted().get().activity();
                if (action == LifeDecision.Activity.PARTY_QUEST
                        || action == LifeDecision.Activity.HELP_FRIEND && phase == Phase.GRIND) {
                    lifeActivities.finish(result.number(), LifeActivityQueue.End.COMPLETED);
                }
            }
        } else if (result.status() == LifeActivityQueue.RunStatus.STALE) {
            lifeNote("STALE request=" + result.number());
        }
    }

    /** Concrete start: only this bot's update may call it through the queue's executor callback. */
    public boolean startLifeRestockTrip() {
        if (!lifeActivities.isStartingHere() || !lifeExecutionEnabled() || !freeForLifeActivity()) return false;
        leaveGrind();
        enterPhase(Phase.GO_TOWN);
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(getChr(),
                soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.RESUPPLY, homeMapId);
        lifeNote("START activity=RESTOCK");
        return true;
    }

    private void cancelLifeActivity() {
        lifeActivities.invalidateWaiting();
        var active = lifeActivities.state();
        if (active.active() != 0) {
            lifeActivities.finish(active.active(), LifeActivityQueue.End.CANCELLED);
            lifeNote("CANCELLED request=" + active.active());
        }
    }

    public boolean freeForAutomaticLifeActivity(long at) {
        int requester = dailyLife == null ? -1 : dailyLife.requester(at);
        return getRunning() && getChr() != null && getChr().getMap() != null
                && (phase == Phase.GRIND || phase == Phase.IN_TOWN || phase == Phase.LIFE_DWELL)
                && !soloMapling.ArtificialPlayer.BotCommitments.inFlight(this)
                && !soloMapling.ArtificialPlayer.BotConversation.hasOtherChat(getChr().getId(), requester)
                && soloMapling.ArtificialPlayer.BotPopulationDirector.mayLogOut(this, requester)
                && (!recruitingNow() || requester >= 0);
    }

    public boolean canStartLifeActivity(LifeDecision.Activity activity, long at) {
        if (!LifeRuntime.enabled() || getChr() == null || getChr().getMap() == null) return false;
        boolean safePhase = phase == Phase.GRIND || phase == Phase.IN_TOWN || phase == Phase.LIFE_DWELL;
        if (!safePhase) return false;
        return switch (activity) {
            case GRIND -> true;
            case RESTOCK -> phase == Phase.GRIND && dailyLife.restockReady(at);
            case MOVE_MAP, BREAK -> phase == Phase.GRIND;
            case FISH -> soloMapling.fishing.FishingPolicy.rewardsEnabled()
                    && soloMapling.fishing.GameFishing.hasChair(getChr())
                    && lifeDestinationAvailable(soloMapling.fishing.GameFishing.destination(getChr()));
            case HANG_OUT, SHOW_OFF -> (activity != LifeDecision.Activity.SHOW_OFF || getChr().getLevel() >= 70)
                    && lifeDestinationAvailable(dailyLife.townDestination());
            case FREE_MARKET, SELL -> (activity == LifeDecision.Activity.FREE_MARKET
                    || soloMapling.Economy.BotShops.enabled() && soloMapling.Economy.BotShops.merchantMinded(getChr().getId()))
                    && lifeDestinationAvailable(910000000);
            case HELP_FRIEND -> dailyLife.friend(at) != null && !dailyLife.partyAsked()
                    && lifeDestinationAvailable(dailyLife.friend(at).getMapId());
            case PARTY_QUEST -> dailyLife.friend(at) != null && dailyLife.partyAsked();
            case LOG_OUT -> {
                var member = BotRoster.loadedMember(getChr().getId() - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE);
                if (member == null || member.lastLoginAt() <= 0 || member.sessionMinutes() <= 0
                        || at - member.lastLoginAt() < member.sessionMinutes() * 60_000L) yield false;
                // Guild departures stay with the existing director's batch plan. Two independent actors must
                // never both see "two online" and each remove the guild's last member.
                boolean enoughGuild = getChr().getGuildId() == 0;
                yield member != null && enoughGuild;
            }
        };
    }

    private boolean lifeDestinationAvailable(int mapId) {
        var factory = soloMapling.server.SoloMaplingUtilities.world()
                .getChannel(getChr().getMap().getChannelId()).getMapFactory();
        if (!factory.isMapLoaded(mapId)) return false;
        var map = factory.getMap(mapId);
        return mapId == getChr().getMapId() || soloMapling.ArtificialPlayer.BotMapCap.hasRoom(map);
    }

    public boolean startLifeActivity(LifeDecision.Activity activity) {
        long at = now();
        if (!lifeActivities.isStartingHere() || !lifeExecutionEnabled() || !freeForAutomaticLifeActivity(at)
                || !canStartLifeActivity(activity, at)) return false;
        if (activity == LifeDecision.Activity.RESTOCK) return startLifeRestockTrip();
        if (activity == LifeDecision.Activity.BREAK) {
            return breaks.begin(getChr(), grind, Math.max(MID_SESSION_FLOOR_MS, grindUntilMs - at));
        }
        if (activity == LifeDecision.Activity.LOG_OUT) {
            return soloMapling.ArtificialPlayer.BotPopulationDirector.logOut(getChr().getId()
                    - soloMapling.ArtificialPlayer.BotStore.BOT_ID_BASE);
        }
        if (activity == LifeDecision.Activity.PARTY_QUEST) {
            Character player = dailyLife.friend(at);
            var result = soloMapling.ArtificialPlayer.BotChatActions.act(this, player,
                    soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage.Intent.PARTY);
            return result.outcome() == soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ACCEPTED
                    || result.outcome() == soloMapling.ArtificialPlayer.BotChatActions.Outcome.PARTY_ALREADY;
        }
        Character requestedFriend = activity == LifeDecision.Activity.HELP_FRIEND ? dailyLife.friend(at) : null;
        if (activity == LifeDecision.Activity.HELP_FRIEND && requestedFriend == null) return false;
        int destination = activity == LifeDecision.Activity.FISH ? soloMapling.fishing.GameFishing.destination(getChr())
                : activity == LifeDecision.Activity.HELP_FRIEND ? requestedFriend.getMapId()
                : activity == LifeDecision.Activity.SELL || activity == LifeDecision.Activity.FREE_MARKET
                ? 910000000 : dailyLife.townDestination();
        if (java.util.Set.of(LifeDecision.Activity.HELP_FRIEND, LifeDecision.Activity.HANG_OUT,
                LifeDecision.Activity.SHOW_OFF, LifeDecision.Activity.SELL, LifeDecision.Activity.FREE_MARKET,
                LifeDecision.Activity.FISH).contains(activity)
                && destination != getChr().getMapId()) {
            var target = soloMapling.server.SoloMaplingUtilities.world().getChannel(getChr().getMap().getChannelId())
                    .getMapFactory().getMap(destination);
            if (!soloMapling.ArtificialPlayer.BotLifeSystem.LifeTownSlots.reserve(getChr().getId(), target)) return false;
        }
        if (phase == Phase.GRIND) leaveGrind();
        if (activity == LifeDecision.Activity.GRIND || activity == LifeDecision.Activity.MOVE_MAP) {
            if (activity == LifeDecision.Activity.MOVE_MAP) mapCrowdCooldown.put(getChr().getMapId(), at + MAP_EXCLUDE_MS);
            enterPhase(Phase.DECIDE);
        } else if (activity == LifeDecision.Activity.HELP_FRIEND) {
            Character player = requestedFriend;
            setTrainTarget(player.getMapId(), Math.max(1, MapMobIndex.level(player.getMapId())));
            enterPhase(getChr().getMapId() == player.getMapId() ? Phase.GRIND : Phase.GO_TRAIN);
        } else {
            lifeDestination = destination;
            lifeVisit = activity;
            enterPhase(getChr().getMapId() == lifeDestination ? Phase.LIFE_DWELL : Phase.LIFE_TRAVEL);
        }
        lifeNote("START activity=" + activity);
        return true;
    }

    /** A chosen friend interrupt ends the exact old dwell before another request can start. */
    public boolean interruptLifeDwell(long requestNumber) {
        return phase == Phase.LIFE_DWELL && lifeActivities.finish(requestNumber, LifeActivityQueue.End.CANCELLED);
    }

    public void keepLifeActivity(LifeDecision.Activity activity, long until) {
        if (activity == LifeDecision.Activity.GRIND) grindUntilMs = until;
        else phaseDeadlineMs = until;
    }

    public LifeDecision.Activity currentLifeVisit() { return lifeVisit; }

    private void doLifeDwell() {
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + dailyLife.memory().profile().townDurationMs();
            if (lifeVisit == LifeDecision.Activity.FISH) {
                lifeFishingStarted = false;
                var point = getChr().getPosition();
                TownLoiter.settle(getChr(), point.x, point.y, 200, false);
            } else TownLoiter.settle(getChr());
            if (lifeVisit == LifeDecision.Activity.SELL || lifeVisit == LifeDecision.Activity.FREE_MARKET) {
                if (soloMapling.Economy.BotShops.enabled() && soloMapling.Economy.BotShops.merchantMinded(getChr().getId())) {
                    soloMapling.Economy.BotShops.open(getChr(), getChr().getMap().getChannelId(), 0, null, false);
                }
            }
            lifeNote("ARRIVED activity=" + lifeVisit);
        }
        if (lifeVisit == LifeDecision.Activity.FISH) {
            if (!soloMapling.fishing.FishingPolicy.rewardsEnabled()) { enterPhase(Phase.DECIDE); return; }
            if (!soloMapling.fishing.GameFishing.active(getChr())) {
                if (lifeFishingStarted) {
                    enterPhase(Phase.DECIDE); return; // a cancelled session does not silently restart
                }
                if (GCMovement.isMoving(getChr())) return;
                soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botSitChair(getChr(),
                        soloMapling.fishing.FishingPolicy.CHAIR);
                if (!soloMapling.fishing.GameFishing.start(getChr())) {
                    botCancelChair(getChr()); enterPhase(Phase.DECIDE); return;
                }
                lifeFishingStarted = true;
                dailyLife.fishingStarted();
                lifeNote("FISHING_SEATED");
            }
        } else soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.maybeKeep(getChr());
        if (now() >= phaseDeadlineMs && freeForAutomaticLifeActivity(now())) enterPhase(Phase.DECIDE);
    }

    private void lifeNote(String text) {
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
            soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS,
                    "LIFE " + text + " bot=" + getChr().getName());
        }
    }

    /** rc6 say-do: grinding now (a "brb" line would be a lie: it keeps fighting). */
    public boolean isGrinding() {
        return phase == Phase.GRIND;
    }

    /** rc6 say-do: resting (a grind break, in town, or at a shop): a "brb"/"afk" line is true as it is. */
    public boolean isIdleNow() {
        Phase p = phase;
        return isBreak(p) || p == Phase.IN_TOWN || p == Phase.SHOP_DWELL || p == Phase.LIFE_DWELL;
    }

    /** A world event wants this bot: it drops what it is doing at its next tick (see answerEventCall). */
    public void callToEvent() {
        eventCall = true;
    }

    // Drop the current outing and re-decide, which goes to the event map (WorldEventSteer). A bot with a player
    // (party, live recruit) stays; a trip, shop visit or rest break finishes first and the next DECIDE goes.
    private void answerEventCall(Character chr) {
        int target = soloMapling.worldevents.WorldEventSteer.mapFor(chr.getId());
        if (target < 0 || recruitingNow() || partyTargetMapId() != null) {
            return;
        }
        if (chr.getMapId() == target && phase == Phase.GRIND) {
            return; // already training there
        }
        switch (phase) {
            case GRIND -> {
                sayContext("MapTransition", chr, null);
                leaveGrind();
                enterPhase(Phase.DECIDE);
            }
            case IN_TOWN, LIFE_DWELL -> {
                if (!saunaTripActive) {
                    enterPhase(Phase.DECIDE);
                }
            }
            case LIFE_TRAVEL -> {
                GCMovement.stop(chr);
                enterPhase(Phase.DECIDE);
            }
            default -> {
            }
        }
    }

    private boolean onEventMap(Character chr) {
        return chr != null && soloMapling.worldevents.WorldEventSteer.mapFor(chr.getId()) == chr.getMapId();
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        ensureCombatTicker();
        homeMapId = getChr().getMapId();
        if (resumeGrind(BotRoster.takeResume(getChr().getId()))) {
            return;
        }
        // No mobs here → it's a town: do the town beat first. Has mobs → a field: decide immediately.
        enterPhase(MapMobIndex.level(homeMapId) < 0 ? Phase.IN_TOWN : Phase.DECIDE);
    }

    /**
     * Picks up a roster member where it logged out (bot-persistence ticket 06): its home town comes back, and
     * a bot that logged out on its train map keeps grinding there instead of choosing a new map - DECIDE's
     * map search excludes the map it stands on, so without this every resumed grinder would wander off.
     */
    private boolean resumeGrind(BotRoster.Activity resume) {
        if (resume == null) {
            return false;
        }
        if (resume.homeMapId() > 0) {
            homeMapId = resume.homeMapId();
        }
        int here = getChr().getMapId();
        boolean grinding = resume.name().isEmpty() || resume.name().equals(GRIND_ACTIVITY);
        if (!grinding || resume.intState(BotRoster.TRAIN_STATE, -1) != here || MapMobIndex.level(here) < 0) {
            return false;
        }
        setTrainTarget(here, Math.max(1, MapMobIndex.level(here)));
        firstTrip = false;
        enterPhase(Phase.GRIND);
        return true;
    }

    private static final String GRIND_ACTIVITY = "grind";

    @Override
    public BotRoster.Activity activitySnapshot() {
        Map<String, Object> state = new java.util.LinkedHashMap<>();
        if (currentTrainMapId >= 0) {
            state.put(BotRoster.TRAIN_STATE, currentTrainMapId);
        }
        if (LifeRuntime.enabled() || dailyLife.restored()) state.put("life", dailyLife.memory().save());
        return new BotRoster.Activity(GRIND_ACTIVITY, Math.max(0, homeMapId), state);
    }

    // In a town hub: settle a beat, maybe plan a shop trip, then either head to the first store or decide
    // what to train on. Reached at spawn and after every grind, so shop trips add organic timing variety.
    private void doInTown() {
        // A Sleepywood sauna trip runs its own async chain off the FSM — park here while it's in flight (and
        // force-recover if it overruns). Gated by homeMapId below, so no other town ever enters this branch.
        if (saunaTripActive) {
            if (now() > saunaTripDeadlineMs) {
                abortSaunaTrip();
            }
            return;
        }
        if (saunaTripDone) {
            saunaTripDone = false;
            enterPhase(Phase.DECIDE); // trip finished — resume the grind loop
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            crowdHopsThisEpisode = 0; // back home -> fresh outing, reset the crowd-migration budget
            yieldHopsThisEpisode = 0;
            SupplyNeeds needs = BotSupplies.arriveInTown(getChr()); // real bots 08: rest to full, see what it needs
            buildShopPlan();
            // Ticket 09: a bot short of potions or ammo walks to a shop in town that sells them and buys there -
            // on this map right away, or as the first store of the itinerary.
            // Owner report 2026-10-24: a player talking to this bot keeps it here - no errand walk and no stroll
            // off the arrival spot while the chat holds (every later tick extends the hold as well).
            boolean chatting = soloMapling.ArtificialPlayer.BotConversation.isHoldingChat(getChr());
            boolean errand = !chatting && planResupply(needs);
            // A deep hub's restock is an ON-MAP food-cart / grocer (no separate store map), so walk to it in
            // place and linger like a shop browse instead of idling on the entry portal. Extends the dwell.
            errand = errand || (!chatting && maybeStartHubErrand());
            if (!errand && !chatting) {
                // Normal town return: walk off the arrival portal to a scattered spot and fidget there, so a
                // town of returning grinders reads as milling players instead of a statue clump on the portal.
                // A hub errand already walks the bot to its vendor, so it doesn't need this.
                TownLoiter.settle(getChr());
            }
            long townDwell = errand ? shopDwellMs() : LifeRuntime.enabled()
                    ? dailyLife.memory().profile().townDurationMs() : dwellMs();
            phaseDeadlineMs = now() + (chatting
                    ? Math.max(townDwell, soloMapling.ArtificialPlayer.BotConversation.holdMs(getChr().getId()))
                    : townDwell);
            if (rng.nextInt(3) == 0) {
                BotEmote(getChr(), 1 + rng.nextInt(7)); // a little life in town
            }
            return;
        }
        pollResupply();
        soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.maybeKeep(getChr()); // rc6: not on anyone's pixel
        BotScrolling.townTick(getChr());   // ticket 11: scroll its own gear while it stands in town
        soloMapling.ArtificialPlayer.BotWealth.townTick(getChr());   // 56B / 1.5.0: spend down, gachapon
        soloMapling.Economy.BotShops.townTick(getChr());   // 1.5.0 rc3: collect shop takings, maybe open a shop
        long holdMs = soloMapling.ArtificialPlayer.BotConversation.isHoldingChat(getChr())
                ? soloMapling.ArtificialPlayer.BotConversation.holdMs(getChr().getId()) : 0;
        if (holdMs > 0) {
            phaseDeadlineMs = Math.max(phaseDeadlineMs, now() + holdMs); // extend the hold without shortening a planned stay
            return;
        }
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (!shopQueue.isEmpty()) {
            shopTargetMapId = shopQueue.remove(0);
            enterPhase(Phase.SHOP_TRAVEL);
        } else if (homeMapId == MapId.SLEEPYWOOD && (saunaResupply || rng.nextDouble() < SHOP_VISIT_CHANCE)) {
            startSaunaTrip(); // Sleepywood has no stores — its errand is a trip to the hotel sauna
        } else {
            enterPhase(Phase.DECIDE);
        }
    }

    // Decide this town stop's store itinerary: nothing, just potions, just equips, or both in either order.
    private void buildShopPlan() {
        shopQueue.clear();
        int[] shops = TOWN_SHOPS.get(homeMapId);
        if (shops == null || rng.nextDouble() > SHOP_VISIT_CHANCE) {
            return; // unknown town, or skipping the shops this visit
        }
        int potion = shops[0];
        int equip = shops[1];
        switch (rng.nextInt(4)) {
            case 0 -> shopQueue.add(potion);
            case 1 -> shopQueue.add(equip);
            case 2 -> { shopQueue.add(potion); shopQueue.add(equip); }
            default -> { shopQueue.add(equip); shopQueue.add(potion); }
        }
    }

    // Deep-hub town errand: a hub sits inside a dungeon, so its "shop" is an on-map food-cart / grocer NPC
    // rather than a separate store map (TOWN_SHOPS). With the usual shop chance, walk to that vendor and
    // linger — reads as restocking pots — instead of idling on the entry portal. Returns true when started
    // (so IN_TOWN uses the longer shop-browse dwell). Only fires while actually standing on the home hub.
    private boolean maybeStartHubErrand() {
        Character chr = getChr();
        DeepHub.Info hub = DeepHub.of(homeMapId);
        if (hub == null || hub.potionNpc() == null || chr.getMapId() != homeMapId) {
            return false; // not a deep hub, no on-map vendor, or not currently at home
        }
        if (!shopQueue.isEmpty() || rng.nextDouble() >= SHOP_VISIT_CHANCE) {
            return false; // a store itinerary already covers this stop, or skipping the errand this visit
        }
        // rc6: every errand bot used to walk onto the vendor's own pixel; take a free spot in front of it
        Point front = soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.freeSpotNear(chr.getMap(),
                hub.potionNpc(), chr.getId());
        GCMovement.move(chr, front.x, front.y);
        sayContext("ShopRestock", chr, null);
        return true;
    }

    // Ticket 09: pick the town's shop that sells what the bot needs. On this map the walk starts now (true: the
    // longer shop dwell); elsewhere in town it becomes the first store of the itinerary. With nothing to buy, or no
    // shop for it in town, the town stop's restock runs where the bot stands (it buys nothing, and a ranged bot
    // that found no ammo settles for melee, as in 08).
    private boolean planResupply(SupplyNeeds needs) {
        Character chr = getChr();
        resupplySite = null;
        saunaResupply = false;
        ShopCatalog.Site site = BotShopping.siteFor(chr, homeMapId, needs);
        if (site == null) {
            BotSupplies.restock(chr);
            return false;
        }
        resupplySite = site;
        debugChat("RESUPPLY: npc " + site.npcId() + " on map " + site.mapId());
        BotShopping.headFor(chr, site);
        if (site.mapId() == ShopCatalog.SLEEPYWOOD_SAUNA) {
            saunaResupply = true; // no portal leads there: the sauna trip (hotel NPC's teleport) is the way in
            return false;
        }
        if (site.mapId() == chr.getMapId()) {
            startResupplyWalk();
            return true;
        }
        shopQueue.remove(Integer.valueOf(site.mapId()));
        shopQueue.add(0, site.mapId());
        return false;
    }

    // Walk up beside the shop NPC (GCMovement: visibly on screen, the coarse planner offscreen).
    private void startResupplyWalk() {
        Character chr = getChr();
        Point npc = resupplySite.npcPos();
        int side = rng.nextBoolean() ? 1 : -1;
        resupplyArrived = false;
        resupplyWalkDeadlineMs = now() + RESUPPLY_WALK_MAX_MS;
        Point front = soloMapling.ArtificialPlayer.BotTownSystem.PersonalSpace.freeSpotNear(chr.getMap(),
                new Point(npc.x + side * (RESUPPLY_STAND_OFF_PX + rng.nextInt(40)), npc.y), chr.getId()); // rc6
        GCMovement.move(chr, front.x, front.y, () -> resupplyArrived = true);
    }

    // Buys once the bot is at the shopkeeper (or the walk ran out of time on its map). True when it just shopped.
    private boolean pollResupply() {
        if (resupplySite == null || getChr().getMapId() != resupplySite.mapId()) {
            return false;
        }
        if (!resupplyArrived && now() < resupplyWalkDeadlineMs) {
            return false;
        }
        finishResupply();
        return true;
    }

    // The restock itself (BotSupplies.restock -> BotShopping at the shop it stands by). Also run when the town stop
    // ends with a restock still pending (the trip to the store failed), so the melee fallback is still settled.
    private void finishResupply() {
        synchronized (this) {   // the sauna's shop buys from a movement callback, the town stop from the macro tick
            if (resupplySite == null) {
                return;
            }
            resupplySite = null;
            saunaResupply = false;
        }
        BotSupplies.restock(getChr());
    }

    // Browse a store for a randomized beat, then move to the next store or head back out to train.
    private void doShopDwell() {
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + shopDwellMs();
            if (rng.nextInt(2) == 0) {
                BotEmote(getChr(), 1 + rng.nextInt(7));
            }
            sayContext("ShopRestock", getChr(), null);
            if (resupplySite != null && resupplySite.mapId() == getChr().getMapId()) {
                startResupplyWalk(); // ticket 09: up to the shopkeeper, then browse once it has bought
            } else {
                BotWanderSystem.start(getChr()); // browse the shop map (whole-map) instead of piling on the portal
            }
            return;
        }
        if (pollResupply()) {
            BotWanderSystem.start(getChr()); // bought: browse the rest of the visit
        }
        BotScrolling.townTick(getChr());   // ticket 11: buy scrolls at this store, scroll its gear while it browses
        soloMapling.ArtificialPlayer.BotWealth.townTick(getChr());   // 56B / 1.5.0: spend down, gachapon
        soloMapling.Economy.BotShops.townTick(getChr());   // 1.5.0 rc3: collect shop takings, maybe open a shop
        if (now() < phaseDeadlineMs) {
            return;
        }
        if (!shopQueue.isEmpty()) {
            shopTargetMapId = shopQueue.remove(0);
            enterPhase(Phase.SHOP_TRAVEL);
        } else {
            enterPhase(Phase.SHOP_RETURN);
        }
    }

    // ── Sleepywood flavor: hotel sauna trip (Sleepywood-home bots only) ────────
    // A self-contained async errand standing in for the shop trip Sleepywood can't offer (no potion/equip
    // stores). It runs entirely off the macro FSM via chained callbacks — no new Phase states: the IN_TOWN
    // tick parks on saunaTripActive while it runs and resumes at DECIDE when it finishes. Gated by
    // homeMapId == SLEEPYWOOD, so it can never leak into any other town's behavior.
    //   town -> hotel -> walk to the (non-portal) sauna door -> warp into a sauna -> wander -> exit -> town.
    private void startSaunaTrip() {
        Character chr = getChr();
        if (chr == null) {
            enterPhase(Phase.DECIDE);
            return;
        }
        TownLoiter.stop(chr); // this async errand leaves town without enterPhase - end the loiter first
        saunaTripActive = true;
        saunaTripDone = false;
        saunaTripDeadlineMs = now() + SAUNA_TRIP_TIMEOUT_MS;
        debugChat("Sleepywood errand -> heading to the sauna");
        if (rng.nextInt(2) == 0) {
            BotEmote(chr, 1 + rng.nextInt(7)); // a beat of life before heading off
        }
        GCMovement.travel(chr, SLEEPYWOOD_HOTEL, ok -> enterSaunaFromHotel(chr));
    }

    // In the hotel: walk to the sauna door for the visual, then warp into a sauna (the NPC-script teleport is
    // pure smoke-and-mirrors server-side). The warp fires on arrival, or after a cap so a missed/abandoned
    // door-walk can't stall the trip — whichever comes first, exactly once.
    private void enterSaunaFromHotel(Character chr) {
        if (!saunaTripActive) {
            return; // aborted while travelling to the hotel
        }
        AtomicBoolean entered = new AtomicBoolean(false);
        Runnable enter = () -> {
            // Ticket 09: quiescence ends the trip - a bot must not change map or start a wander on the way out.
            if (!saunaTripActive || BotShutdown.isQuiesced() || !entered.compareAndSet(false, true)) {
                return; // already entered (door-arrival vs cap race), or the trip was aborted
            }
            boolean shopping = saunaResupply;
            int sauna = shopping ? SAUNA_REGULAR : rng.nextDouble() < SAUNA_VIP_CHANCE ? SAUNA_VIP : SAUNA_REGULAR;
            chr.changeMap(sauna);
            if (shopping) {
                shopInSauna(chr); // ticket 09: up to the sauna's shopkeeper first, then relax
            } else {
                BotWanderSystem.start(chr); // relax in the sauna for a beat
            }
            ExecutorServiceManager.getScheduledExecutorService().schedule(
                    () -> {
                        if (BotShutdown.isQuiesced() || !saunaTripActive) {
                            return; // Ticket 09: no travel out of the sauna once the gate has closed (or the bot stopped)
                        }
                        leaveSauna(chr);
                    }, shopDwellMs(), TimeUnit.MILLISECONDS);
        };
        GCMovement.move(chr, SAUNA_DOOR_X, SAUNA_DOOR_Y, enter);
        ExecutorServiceManager.getScheduledExecutorService().schedule(enter, DOOR_WALK_MAX_MS, TimeUnit.MILLISECONDS);
    }

    // Ticket 09: in the regular sauna for supplies - walk to its shopkeeper and buy on arrival (or after the walk cap),
    // exactly once, then relax like any sauna visit.
    private void shopInSauna(Character chr) {
        ShopCatalog.Site site = resupplySite;
        if (site == null || chr.getMapId() != site.mapId()) {
            BotWanderSystem.start(chr);
            return;
        }
        AtomicBoolean bought = new AtomicBoolean(false);
        Runnable buy = () -> {
            if (!saunaTripActive || BotShutdown.isQuiesced() || !bought.compareAndSet(false, true)) {
                return;
            }
            finishResupply();
            BotWanderSystem.start(chr);
        };
        Point npc = site.npcPos();
        GCMovement.move(chr, npc.x + (rng.nextBoolean() ? 1 : -1) * (RESUPPLY_STAND_OFF_PX + rng.nextInt(40)), npc.y,
                buy);
        ExecutorServiceManager.getScheduledExecutorService().schedule(buy, RESUPPLY_WALK_MAX_MS, TimeUnit.MILLISECONDS);
    }

    // Done relaxing: the sauna's one-way exit portal drops the bot back into the hotel; from there take the
    // hotel's portal back down to Sleepywood town. Then hand control back to the macro tick (-> DECIDE).
    private void leaveSauna(Character chr) {
        if (!saunaTripActive) {
            return;
        }
        BotWanderSystem.stop(chr);
        GCMovement.travel(chr, SLEEPYWOOD_HOTEL, okHotel ->
                GCMovement.travel(chr, homeMapId, okTown -> finishSaunaTrip()));
    }

    private void finishSaunaTrip() {
        if (!saunaTripActive) {
            return; // already aborted/finished — don't re-arm the handoff
        }
        saunaTripActive = false;
        saunaTripDone = true; // doInTown advances to DECIDE on the macro thread
    }

    // Trip watchdog (macro thread): the chained callbacks can silently drop on an unreachable hop, so if the
    // whole errand overruns, cut it and hard-recover to town rather than leaving the bot parked forever.
    private void abortSaunaTrip() {
        Character chr = getChr();
        debugChat("sauna trip overran -> force-recover to town");
        saunaTripActive = false; // disarm any in-flight callbacks first
        if (chr != null) {
            BotWanderSystem.stop(chr);
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
            if (homeMapId >= 0 && chr.getMapId() != homeMapId) {
                chr.changeMap(homeMapId);
            }
        }
        saunaTripDone = true;
    }

    // Discover a field map near the current town (WZ-driven): level-scaled wander radius, weighted
    // toward level-appropriate + less-crowded maps, with a chance to chill at an easier map.
    private void doDecide() {
        Character chr = getChr();
        finishResupply(); // ticket 09: a town stop never leaves with its restock undone
        // Station-here handoff (FollowerBot's "Train here with me!"): grind the current map, skip
        // discovery entirely - the map BFS excludes its origin, so without this the bot always migrates.
        if (BotRecruitManager.consumeStationHere(chr.getId()) && MapMobIndex.level(chr.getMapId()) >= 0) {
            debugChat("DECIDE: station-here -> grind current map " + chr.getMapId());
            setTrainTarget(chr.getMapId(), Math.max(1, MapMobIndex.level(chr.getMapId())));
            firstTrip = false;
            enterPhase(Phase.GRIND);
            return;
        }
        // Party-aware: partied with a real player -> train on their map (or hold the current map
        // while they're parked somewhere mobless). Deliberately skips the capacity/crowd weighting -
        // the player chose this map, and party EXP needs same-map anyway.
        Integer partyMap = partyTargetMapId();
        if (partyMap != null) {
            debugChat("DECIDE: party target -> map " + partyMap);
            setTrainTarget(partyMap, Math.max(1, MapMobIndex.level(partyMap)));
            firstTrip = false;
            enterPhase(chr.getMapId() == partyMap ? Phase.GRIND : Phase.GO_TRAIN);
            return;
        }
        // Ticket 21: answering a world event - train on the event map instead of our own pick.
        int eventMap = soloMapling.worldevents.WorldEventSteer.decide(chr.getId());
        if (eventMap > 0 && BotMapGates.mayEnter(eventMap, BotMapGates.rebirthsOf(chr))
                && soloMapling.ArtificialPlayer.BotMapCap.admit(chr, eventMap)) { // 1.5.0: full -> a normal pick
            debugChat("DECIDE: world event -> map " + eventMap);
            setTrainTarget(eventMap, Math.max(1, MapMobIndex.level(eventMap)));
            firstTrip = false;
            enterPhase(chr.getMapId() == eventMap ? Phase.GRIND : Phase.GO_TRAIN);
            return;
        }
        // 1.5.0 rc3: back from a death in town - walk back to the map it died on, as a player does
        int back = walkBackMapId;
        walkBackMapId = -1;
        if (back > 0 && MapMobIndex.level(back) >= 0 && BotMapGates.mayEnter(back, BotMapGates.rebirthsOf(chr))
                && soloMapling.ArtificialPlayer.BotMapCap.admit(chr, back)) { // 1.5.0: full -> a normal pick
            debugChat("DECIDE: walking back to map " + back + " after a death");
            if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
                soloMapling.qa.QaLog.any(soloMapling.qa.QaLog.Topic.HABITS, "WALK BACK " + chr.getName()
                        + " heads back from " + chr.getMapId() + " to " + back);
            }
            setTrainTarget(back, Math.max(1, MapMobIndex.level(back)));
            firstTrip = false;
            enterPhase(chr.getMapId() == back ? Phase.GRIND : Phase.GO_TRAIN);
            return;
        }
        Set<Integer> excluded = crowdExcludedMaps(); // maps on crowd-cooldown (pruned) — steer away from them
        clearTrainTarget(); // release any stale reservation before choosing (choose() reserves the pick's slot)
        pendingHomeMapId = -1; // a relocation that never arrived is forgotten; the bot keeps its old home
        TrainingMap pick = TrainingMapChooser.choose(chr, homeMapId, excluded, this::debugChat);
        if (pick == null) {
            int here = chr.getMapId();
            if (MapMobIndex.mobCount(here) > 0) {
                // Nothing to travel to (no walkable exit, or every map on crowd cooldown) but mobs right
                // here: fight them rather than stand in the spawns. Cross-map travel (ticket 03) and the
                // level path (ticket 31) are what should eventually move such a bot somewhere better.
                debugChat("DECIDE: nothing reachable (lv " + chr.getLevel() + ") -> grind current map " + here);
                setTrainTarget(here, Math.max(1, MapMobIndex.level(here)));
                firstTrip = false;
                enterPhase(Phase.GRIND);
                return;
            }
            // Nothing reachable with mobs (or all on cooldown) — idle in town and try again later.
            debugChat("DECIDE: nothing reachable (lv " + chr.getLevel() + ") -> idle in town");
            enterPhase(Phase.IN_TOWN);
            return;
        }
        // choose() already holds the pick's occupancy slot — record it without re-reserving.
        currentTrainMapId = pick.mapId();
        currentMobLevel = Math.max(1, pick.mobLevel());
        debugChat("DECIDE: chose map " + pick.mapId() + " (mob lv " + pick.mobLevel() + ")");
        if (pick.relocates()) {
            // Ticket 03: training in another region. The bot rides there (cabs, boats, trains... paying its
            // fares) and makes the region's hub its home on arrival, so later town trips stay over there.
            pendingHomeMapId = pick.relocateHomeMapId();
            log("[TrainingBot] " + chr.getName() + " (lv " + chr.getLevel() + ") sets off from map " + chr.getMapId()
                    + " for map " + pick.mapId() + " (new home " + pendingHomeMapId + ", " + chr.getMeso() + " mesos)");
        }
        // First trip only (mainly startup): most bots warp straight to the grind map instead of walking, so
        // the world reads as already-populated. Skipped when the town is observed (don't blink out in front of
        // a player) or already on the picked map. doGrind's entry then runs the normal grind setup. Consumed
        // either way, so every subsequent trip travels naturally.
        boolean wasFirstTrip = firstTrip;
        firstTrip = false;
        boolean migrates = chr.getMapId() != pick.mapId();
        if (wasFirstTrip && migrates && !pick.relocates() // a move to another region always travels for real
                && !GCMovement.isMapObserved(chr.getMapId())
                && rng.nextDouble() < FIRST_TRIP_TELEPORT_CHANCE) {
            debugChat("DECIDE: first-trip warp -> map " + pick.mapId());
            midSessionGrind = true; // warped in -> reads as already-grinding -> shortened first session
            GCMovement.stop(chr); // clear any stale town move intent before the warp
            chr.changeMap(pick.mapId());
            enterPhase(Phase.GRIND);
            return;
        }
        if (wasFirstTrip && !migrates) {
            midSessionGrind = true; // already on the grind map (no visible travel) -> also a mid-session start
        }
        // First trip with a real cross-map walk (the ~30% that don't warp): we watch it travel, so it reads as
        // freshly heading out -> a full session is correct. midSessionGrind stays false.
        enterPhase(Phase.GO_TRAIN);
    }

    // Issue a one-shot travel to destMapId and advance on the async result. Already on the map -> straight
    // to onArrive. Timed out / failed -> onFail. Non-blocking: re-polls each macro tick while the trip
    // is in flight.
    private void doTravel(int destMapId, Phase onArrive, Phase onFail) {
        Character chr = getChr();
        if (destMapId < 0) {
            enterPhase(onFail);
            return;
        }
        if (chr.getMapId() == destMapId) {
            enterPhase(onArrive);
            return;
        }
        if (!phaseEntered) {
            phaseEntered = true;
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
            travelLastMapId = chr.getMapId();
            travelLastPos = chr.getPosition();
            debugChat("travel -> map " + destMapId);
            GCMovement.travel(chr, destMapId, ok -> {
                moveOk = (ok != null && ok);
                moveDone = true;
            });
            return;
        }
        if (moveDone) {
            if (!moveOk) {
                debugChat("travel reported FAIL -> " + onFail);
            }
            enterPhase(moveOk ? onArrive : onFail);
            return;
        }
        // Progress-aware watchdog: while the bot is advancing maps OR moving across the current one, it's
        // travelling fine (a big map / long route is expected) — keep pushing the deadline out. Only a trip
        // that makes no progress at all for the whole window is wedged; abandon it and re-plan (no teleport).
        if (madeTravelProgress(chr)) {
            phaseDeadlineMs = now() + TRAVEL_TIMEOUT_MS;
        } else if (now() > phaseDeadlineMs) {
            debugChat("travel STUCK: no progress for " + (TRAVEL_TIMEOUT_MS / 1000) + "s -> " + onFail);
            GCMovement.cancelTravel(chr);
            enterPhase(onFail);
        }
        // else still travelling — wait for the next tick
    }

    // True if the bot advanced a hop (its map changed) or moved on its current map since the last check. The
    // position anchor only advances when movement clears the epsilon, so slow steady travel still accumulates
    // into progress across ticks instead of reading as stuck.
    private boolean madeTravelProgress(Character chr) {
        if (GCMovement.isInTransit(chr)) {
            return true; // queued for a boat or aboard one: standing still is the point (ticket 03)
        }
        int curMap = chr.getMapId();
        if (curMap != travelLastMapId) {
            travelLastMapId = curMap;
            travelLastPos = chr.getPosition(); // hop completed — unambiguous progress; re-anchor on the new map
            return true;
        }
        Point pos = chr.getPosition();
        if (pos != null && (travelLastPos == null
                || Math.abs(pos.x - travelLastPos.x) + Math.abs(pos.y - travelLastPos.y) > TRAVEL_PROGRESS_EPS_PX)) {
            travelLastPos = pos;
            return true;
        }
        return false;
    }

    // At a training map: anchor on a platform, swing (when observed) via the shared ticker, accrue EXP.
    private void doGrind() {
        Character chr = getChr();
        if (!phaseEntered) {
            phaseEntered = true;
            teleportedThisEpisode = false;
            if (pendingHomeMapId > 0 && chr.getMapId() == currentTrainMapId) {
                log("[TrainingBot] " + chr.getName() + " arrived on map " + chr.getMapId() + "; home is now "
                        + pendingHomeMapId + " (was " + homeMapId + ")");
                homeMapId = pendingHomeMapId; // ticket 03: settled in the new region
            }
            pendingHomeMapId = -1;
            GCMovement.setGrinding(chr, true); // engage grind nav guards (no idle-hang on ropes)
            GCMovement.clearIdle(chr); // back to fighting: a break's idle declaration is over
            grind.start(chr); // fresh heartbeat, select a spot, and move into it (disperses cohorts even unobserved)
            boolean resumingFromBreak = breaks.resuming();
            grindUntilMs = now() + (resumingFromBreak ? breaks.consumeResumeRemaining() : grindSessionMs());
            breaks.schedule(grindUntilMs, resumingFromBreak);
            lastExpAccrualMs = now();
            nextBuffMs = now(); // buff up as soon as a player can see it (looks already-buffed on arrival)
            ACTIVE_GRINDERS.add(this);
            BotDeaths.setEligible(chr.getId(), partyTargetMapId() == null && !recruitingNow()); // 1.5.0 rc3
            hpPotsAtGrindStart = BotVitals.potionCount(chr, true);
            soloMapling.ArtificialPlayer.BotGrindSystem.LevelingPathCensus.grinding(chr.getId(), chr.getMapId());
            lastKnownLevel = chr.getLevel();
            if (!resumingFromBreak) {
                sayContext("GrindStart", chr, null); // a resumed session already spoke BreakOver
            }
            debugChat("GRIND on map " + chr.getMapId() + " " + grind.spotLabel());
            return;
        }

        // Abstract EXP only when unobserved (observed time is covered by real kills via the ticker).
        long nowMs = now();
        double elapsedSec = (nowMs - lastExpAccrualMs) / 1000.0;
        lastExpAccrualMs = nowMs;
        if (elapsedSec > 0 && !GCMovement.isMapObserved(chr.getMapId()) && currentMobLevel > 0) {
            // Real bots 08: the stretch uses up ammo, HP, MP and potions at the on-screen rates; only the share the
            // bot could afford is ground (EXP and loot); running out part way sends it to town just below.
            OffscreenCombat.Rate rate = OffscreenCombat.rate(chr, chr.getMapId());
            double fought = elapsedSec * OffscreenSupplies.accrue(chr, rate, elapsedSec);
            if (fought > 0) {
                accrueAbstractExp(rate, currentMobLevel, fought);
                accrueAbstractLoot(chr, nowMs, (long) (fought * 1000));
            }
        } else if (elapsedSec > 0) {
            BotVitals.regen(chr, (long) (elapsedSec * 1000)); // on screen: the 10-second recovery tick
        }

        if (supplyRetreat(chr)) {
            return; // out of supplies: headed to town (real bots 08)
        }

        // Announce a level-up the moment it becomes visible (real kills on an observed map).
        int lvl = chr.getLevel();
        if (lastKnownLevel >= 0 && lvl > lastKnownLevel) {
            sayContext("LevelUp", chr, null);
        }
        lastKnownLevel = lvl;

        maybeGrindChatter(chr);
        maybeSelfBuff(chr);

        if (grindWatchdog(chr)) {
            return; // self-repair left the map (re-DECIDE) — skip the normal grind-timer transition
        }

        if (grindCrowdBail(chr)) {
            return; // map too crowded — left for a deeper one (re-DECIDE); skip the grind-timer transition
        }

        if (breaks.due() && !recruitingNow()) {
            // begin() consumes the due-flag either way; false = no safe rest spot -> session continues.
            if (breaks.begin(chr, grind, Math.max(MID_SESSION_FLOOR_MS, grindUntilMs - nowMs))) {
                enterPhase(Phase.BREAK_TRAVEL);
            }
            return;
        }

        if (nowMs >= grindUntilMs && onEventMap(chr)) {
            grindUntilMs = nowMs + 60_000; // ticket 21: stay while the event runs; released when it ends
        }
        if (nowMs >= grindUntilMs && !recruitingNow()) { // don't walk off mid-conversation/invite window
            sayContext("MapTransition", chr, null); // "this spot's dead, moving on" — said while still on the map
            leaveGrind();
            enterPhase(Phase.GO_TOWN);
        }
    }

    // Macro self-repair watchdog (runs on the slow tick, observed only). The combat ticker keeps the
    // heartbeat (lastCombatProgressMs = last landed hit). If that goes stale the bot isn't engaging anything:
    //   ~30s + a reachable mob nearby → wedged/bad-pathing → teleport to the nearest portal and re-grind.
    //   ~30s + NO reachable mob, or ~60s after a teleport that didn't help → this map's a dead end → re-DECIDE.
    // A landed hit (heartbeat refresh) drops it back below the threshold and resets the ladder. Returns true
    // only when it changed phase (left the map), so doGrind skips its own grind-timer transition that tick.
    private boolean grindWatchdog(Character chr) {
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // unobserved bots don't fight (abstract EXP) — they can't be physically stuck
        }
        if (recruitingNow()) {
            return false; // mid-conversation / live invite window: never teleport or bail from under the player
        }
        long stuck = grind.msSinceProgress();
        if (stuck < STUCK_TELEPORT_MS) {
            teleportedThisEpisode = false; // making (or recently made) progress → reset the ladder
            return false;
        }
        if (now() - lastRepairMs < REPAIR_COOLDOWN_MS) {
            return false; // let the previous repair take effect before escalating
        }
        boolean mobReachable = isReachableHostileNearby(chr);
        boolean recovering = grind.isRecovering(chr);
        if (mobReachable && !teleportedThisEpisode && !recovering) {
            // A mob is reachable but we still can't land a hit (wedged / pathing failure) → reset position.
            debugChat("watchdog: no hit for " + (stuck / 1000) + "s, mob reachable -> teleport to portal & re-grind");
            teleportToNearestPortal(chr);
            grind.resetupAfterTeleport(chr); // fresh kill window + re-select a spot from the new position
            teleportedThisEpisode = true;
            lastRepairMs = now();
            return false;
        }
        if (!mobReachable || stuck >= STUCK_BAIL_MS) {
            // Nothing to fight here, or a teleport already failed to help → leave and pick a new map.
            debugChat("watchdog: stuck " + (stuck / 1000) + "s (" + (mobReachable ? "teleport didn't help" : "no reachable mob") + ") -> bail map, re-decide");
            leaveGrind();
            enterPhase(Phase.DECIDE);
            return true;
        }
        return false;
    }

    // Crowd-balance escalation (macro tick; runs unobserved too — see CROWD_BAIL_UNOBSERVED). The grind
    // sub-FSM reports the map saturated when every reachable spot is claimed and the bot is sharing; if
    // that persists past the dwell, leave for a
    // deeper, less-crowded map — capacity-aware DECIDE spills us outward. Three guards stop thrash: a persist
    // timer (ignore arrival-race blips), a per-map cooldown (no instant return), and a per-outing hop cap (a
    // fully packed region settles into sharing instead of migrating forever). Returns true if it left the map.
    private boolean grindCrowdBail(Character chr) {
        if (chr == null) {
            return false;
        }
        Integer partyMap = partyTargetMapId();
        if (partyMap != null && partyMap == chr.getMapId()) {
            return false; // never crowd-bail off the party's map - staying with the player wins
        }
        if (onEventMap(chr)) {
            return false; // ticket 21: a world event's crowd is the point
        }
        if (recruitingNow()) {
            return false; // mid-recruit: hold the map until the exchange resolves
        }
        if (!CROWD_BAIL_UNOBSERVED && !GCMovement.isMapObserved(chr.getMapId())) {
            return false; // old gate: only relieve crowding a player can actually see
        }
        boolean yielding = grind.yieldsMap(); // 1.5.0 rc3: only a real player's platforms are left here
        CrowdBail action = crowdBail(grind.mapSaturated(), yielding, crowdHopsThisEpisode, yieldHopsThisEpisode);
        if (action == CrowdBail.STAY) {
            mapSaturatedSinceMs = 0L; // not saturated, or out of hops -> reset the persist timer
            return false;
        }
        if (mapSaturatedSinceMs == 0L) {
            mapSaturatedSinceMs = now(); // first saturated tick -> start the dwell
            return false;
        }
        if (now() - mapSaturatedSinceMs < MAP_SATURATED_DWELL_MS) {
            return false; // saturation must persist (ride out a cohort-arrival race before bailing)
        }
        int leaving = chr.getMapId();
        debugChat("crowd: map " + leaving + " saturated -> bail to a deeper map");
        if (yielding) {
            sayCc(chr);
        }
        mapCrowdCooldown.put(leaving, now() + MAP_EXCLUDE_MS);
        mapSaturatedSinceMs = 0L;
        leaveGrind();
        if (action == CrowdBail.TOWN) {
            enterPhase(Phase.GO_TOWN); // players hold every map it tried: rest in town, then a fresh outing
            return true;
        }
        if (yielding) {
            yieldHopsThisEpisode++;
        } else {
            crowdHopsThisEpisode++;
        }
        enterPhase(Phase.DECIDE);
        return true;
    }

    enum CrowdBail { STAY, HOP, TOWN }

    /**
     * What a crowded grinder does: a saturated map is left for another while crowd hops remain (then it settles and
     * shares); a map where only a real player's platforms are left is always left, past the crowd hop cap, for
     * another map or, once the yield hops are used up, for town.
     */
    static CrowdBail crowdBail(boolean saturated, boolean yielding, int crowdHops, int yieldHops) {
        if (yielding) {
            return yieldHops < MAX_YIELD_HOPS_PER_EPISODE ? CrowdBail.HOP : CrowdBail.TOWN;
        }
        return saturated && crowdHops < MAX_MAP_HOPS_PER_EPISODE ? CrowdBail.HOP : CrowdBail.STAY;
    }

    private static final String[] CC_LINES = {"cc?", "cc", "ccing", "ok ill cc", "np cc", "kk cc"};

    // KS manners: moving on from a map a player has, a bot sometimes says so (its own habit).
    private void sayCc(Character chr) {
        boolean says = rng.nextDouble() < BotHabits.of(chr.getId()).ccChance();
        String line = CC_LINES[rng.nextInt(CC_LINES.length)];
        if (says && GCMovement.isMapObserved(chr.getMapId())) {
            BotSpeak(chr, line);
        }
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.MANNERS)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.MANNERS, chr.getMapId(), "MOVE ON " + chr.getName()
                    + " leaves map " + chr.getMapId() + " to the player(s)" + (says ? ", says \"" + line + "\"" : ""));
        }
    }

    // ── Grind breaks (rest flavor — behavior in GrindBreakRoutine; the phases just delegate) ──

    // Dev hook (!bot grindstyle): force this bot's grind archetype for testing (auto = back to the
    // profile-resolved natural style). A live grind swaps its strategy on the next combat tick; an
    // idle bot applies it when it next grinds. Returns a status line, or null on an unknown style.
    public String forceGrindStyle(String styleName) {
        soloMapling.ArtificialPlayer.BotGrindSystem.GrindStyle wanted = null;
        if (!styleName.equalsIgnoreCase("auto")) {
            try {
                wanted = soloMapling.ArtificialPlayer.BotGrindSystem.GrindStyle.valueOf(styleName.toUpperCase());
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        grind.forceStyle(wanted);
        nudgeSoon(300); // wake the macro brain so an idle bot re-decides promptly
        return getChr().getName() + ": grind style -> " + (wanted == null ? "AUTO" : wanted)
                + (phase == Phase.GRIND ? " (live swap on next combat tick)" : " (applies at next grind)");
    }

    // !qa sp-1 (rc6): a break now that hangs on a rope when the map has a safe rope band (else a ground rest).
    public boolean forceRopeBreakNow() {
        if (phase != Phase.GRIND) {
            return false;
        }
        breaks.forceRopeNow();
        nudgeSoon(300);
        return true;
    }

    // !qa cb-3 (QA staging only): put a bot a staging step left in town back to work on {@code mapId} now - the trip
    // its own restock ends with, on the map the check watches - by warping it there (as the first-trip warp does) and
    // running the normal GRIND entry, which re-claims a spot and makes it death-eligible again. True when it grinds.
    public boolean forceGrindOn(int mapId) {
        Character chr = getChr();
        if (chr == null || mapId <= 0 || BotDeaths.isDead(chr.getId()) || MapMobIndex.level(mapId) < 0) {
            return false;
        }
        if (isGrinding()) {
            return true; // already at work: a staging step never restarts a session it does not have to
        }
        if (chr.getMapId() != mapId) {
            GCMovement.cancelTravel(chr);
            GCMovement.stop(chr);
            chr.changeMap(mapId);
        }
        setTrainTarget(mapId, Math.max(1, MapMobIndex.level(mapId)));
        firstTrip = false;
        enterPhase(Phase.GRIND);
        doGrind(); // the real entry: spot claim, session timers, ACTIVE_GRINDERS, BotDeaths eligibility
        return isGrinding();
    }

    // Dev hook (!bot breaknow): pull a break to the next grind tick.
    public boolean forceBreakNow() {
        if (phase != Phase.GRIND) {
            return false;
        }
        breaks.forceNow();
        nudgeSoon(300);
        return true;
    }

    private void doBreakTravel() {
        phaseEntered = true; // the routine owns its own setup latches (armed in begin())
        if (breaks.tickTravel(getChr())) {
            enterPhase(Phase.BREAK_REST);
        }
    }

    private void doBreakRest() {
        phaseEntered = true;
        if (breaks.tickRest(getChr())) {
            enterPhase(Phase.GRIND); // re-enters with the saved remainder; grind.start re-picks a spot
        }
    }

    // Break-sign flavor line ("brb" chalkboard). Stays here for the dialogue plumbing (dialoguePath).
    String breakSignText() {
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, "BreakSign", getChr(), null);
            if (line != null) {
                return line;
            }
        } catch (Exception e) {
            // fall through to the default sign
        }
        return "brb";
    }

    // Is there a hostile mob the bot could actually walk/jump to from where it stands? Reachability-filtered
    // (a mob stranded on a platform the bot can't get to doesn't count) using the same foothold-snap the
    // approach uses, so a jumping mob still resolves to its real platform.
    private boolean isReachableHostileNearby(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return false;
        }
        Set<Integer> reach = GCMovement.reachableRegions(map, pos.x, pos.y);
        if (reach.isEmpty()) {
            return false;
        }
        for (Monster m : map.getAllMonsters()) {
            if (!SpotFinder.isHostile(m) || m.getPosition() == null) {
                continue;
            }
            Point g = GCMovement.groundPointBelow(map, m.getPosition().x, m.getPosition().y);
            if (g != null && reach.contains(GCMovement.regionIdAt(map, g.x, g.y))) {
                return true;
            }
        }
        return false;
    }

    // Hard-teleport to the nearest portal on the current map — a guaranteed-valid foothold — to break a
    // geometry wedge. The caller re-runs setupGrind from the new spot.
    private void teleportToNearestPortal(Character chr) {
        MapleMap map = chr.getMap();
        Point pos = chr.getPosition();
        if (map == null || pos == null) {
            return;
        }
        Point best = null;
        double bestSq = Double.MAX_VALUE;
        for (Portal p : map.getPortals()) {
            Point pp = p.getPosition();
            if (pp == null) {
                continue;
            }
            double dsq = pos.distanceSq(pp);
            if (dsq < bestSq) {
                bestSq = dsq;
                best = pp;
            }
        }
        if (best != null) {
            GCMovement.stop(chr); // drop the in-flight move so the driver re-acquires cleanly from the new spot
            GCMovement.teleportTo(chr, best.x, best.y);
        }
    }

    // Real bots 08: out of what it fights with (no potion left at a quarter HP, no MP potion for its attack, no ammo)
    // the bot leaves for town, where it rests and asks the resupply hook (BotSupplies.visitTown). Not mid-way
    // through talking to a player.
    private int hpPotsAtGrindStart = 0;

    private boolean supplyRetreat(Character chr) {
        BotSupplies.Shortage shortage = BotSupplies.shortage(chr);
        if (shortage == BotSupplies.Shortage.NONE && !recruitingNow() && runningLow(chr)) {
            return true; // 1.5.0 rc3: down to its own low-water mark of potions -> town to restock
        }
        if (shortage == BotSupplies.Shortage.NONE || recruitingNow()) {
            return false;
        }
        debugChat("supplies: out (" + shortage + ") -> town");
        BotSupplies.noteRetreat(chr, shortage);
        sayContext(switch (shortage) {
            case HP -> "NearDeath";
            case MP -> "OutOfMana";
            default -> "OutOfAmmo";
        }, chr, null);
        leaveGrind();
        enterPhase(Phase.GO_TOWN);
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(chr, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.RESUPPLY,
                homeMapId);
        return true;
    }

    // 1.5.0 rc3 (owner decision 5): some players head back for potions when they run low, not only when they run out.
    // A bot whose habit has a low-water mark, and that came out with more than that, leaves when it drinks down to it.
    private boolean runningLow(Character chr) {
        if (!BotCombatTuning.human()) {
            return false;
        }
        int mark = BotHabits.of(chr.getId()).restockBelow();
        if (mark <= 0 || hpPotsAtGrindStart <= mark) {
            return false;
        }
        int left = BotVitals.potionCount(chr, true);
        if (left >= mark) {
            return false;
        }
        debugChat("supplies: running low (" + left + " HP pots) -> town to restock");
        if (soloMapling.qa.QaLog.on(soloMapling.qa.QaLog.Topic.HABITS)) {
            soloMapling.qa.QaLog.map(soloMapling.qa.QaLog.Topic.HABITS, chr.getMapId(), "RESTOCK " + chr.getName()
                    + " is down to " + left + " HP pots (its mark " + mark + ", came out with " + hpPotsAtGrindStart
                    + ") -> heads to town");
        }
        sayContext("MapTransition", chr, null);
        leaveGrind();
        enterPhase(Phase.GO_TOWN);
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(chr, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.RESUPPLY,
                homeMapId);
        return true;
    }

    private void leaveGrind() {
        ACTIVE_GRINDERS.remove(this);
        swingClock.disarm();
        BotDeaths.setEligible(getChr().getId(), false);
        grind.release(getChr()); // drop the spot claim + reset combat state
        clearTrainTarget(); // release this map's occupancy slot
        GCMovement.setRestHold(getChr(), false); // belt-and-braces: never leave a rope rest hold set on bail
        GCMovement.clearIdle(getChr()); // nor a break's idle declaration
        GCMovement.setGrinding(getChr(), false); // back to normal nav for travel
        GCMovement.stop(getChr()); // clear the roam target before travelling
    }

    // ── Ambient dialogue (context-aware flavor) ───────────────────────────────

    // Occasionally mutters to itself while grinding (GrindAmbient). Reacting to nearby players is handled
    // by the movement driver's BotPlayerReaction layer, so this is idle-only and rarely fires. Throttled
    // by nextChatterMs (2-4 min); only speaks when a player is observing the map.
    private void maybeGrindChatter(Character chr) {
        long t = now();
        if (t < nextChatterMs) {
            return;
        }
        nextChatterMs = t + AMBIENT_MIN_MS + (long) (rng.nextDouble() * (AMBIENT_MAX_MS - AMBIENT_MIN_MS));
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // gate advanced so we don't re-roll every tick; nothing worth saying unobserved
        }
        if (rng.nextDouble() < Math.min(1, BotChatTuning.multiplier(chr.getMap()))) {
            ExecutorServiceManager.runAsync(() -> {
                String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, "GrindAmbient", chr, null);
                if (line != null) {
                    soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotAmbientSpeak(chr, line);
                }
            });
        }
    }

    // Re-show the bot's class buffs every 90-120s while grinding. Only fires when a player can see it and
    // the bot is grounded (no buffing from a rope); the timer advances only on an actual buff, so an
    // unobserved bot buffs the instant it's first observed and then keeps to the cadence. forceBuff
    // ignores BotBuffDriver's own duration timers so the visible re-buff stays on this fixed cadence.
    private void maybeSelfBuff(Character chr) {
        if (now() < nextBuffMs) {
            return;
        }
        if (!GCMovement.isMapObserved(chr.getMapId())) {
            return; // hold the timer until it's worth showing.
        }
        if (BotCombatTuning.human()) {
            // 1.5.0 rc3: each buff recast as it runs out, a little late by the bot's habit (party buffs on time)
            BotBuffDriver.maintain(chr, BotHabits.of(chr.getId()).rebuffLateNowMs());
            nextBuffMs = now() + 2_000;
            return;
        }
        BotBuffDriver.forceBuff(chr);
        nextBuffMs = now() + BUFF_MIN_MS + (long) (rng.nextDouble() * (BUFF_MAX_MS - BUFF_MIN_MS));
    }

    // Fires a context-token dialogue node, but only when a real player can see it (observed map), and
    // off the macro thread so the line's hold never blocks the tick. Biased toward plain lines over
    // {TOKEN} ones (CONTEXT_LINE_CHANCE). player may be null (self-only nodes).
    void sayContext(String node, Character chr, Character player) { // package-visible: GrindBreakRoutine speaks through the bot
        if (chr == null || !GCMovement.isMapObserved(chr.getMapId())) {
            return;
        }
        ExecutorServiceManager.runAsync(() ->
                getDialogueHandler().executeBotContextDialogue(node, this, player, BotDialogueHandler.CONTEXT_LINE_CHANCE));
    }

    // ── Party recruiting & interactive menu ─────────────────────────────────

    // The map a partied bot should train on: the first real (non-bot, online) party member's map if
    // it's grindable; else hold the current map if that's grindable (the player's in a town buying
    // pots - don't wander off); else null (normal weighted pick).
    private Integer partyTargetMapId() {
        Party party = getChr().getParty();
        if (party == null) {
            return null;
        }
        Character member = firstRealPartyMember(party);
        if (member == null) {
            // Ticket 58: a bot couple trains together - the partner who leads the party picks the map.
            Integer couple = soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples.partnerTrainingMap(getChr());
            // 1.5.0: a guild party grinds where its (bot) leader grinds
            return couple != null ? couple : soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds.leaderTrainingMap(getChr());
        }
        int memberMap = member.getMapId();
        if (MapMobIndex.level(memberMap) >= 0) {
            return memberMap;
        }
        if (MapMobIndex.level(getChr().getMapId()) >= 0) {
            return getChr().getMapId();
        }
        return null;
    }

    private Character firstRealPartyMember(Party party) {
        for (PartyCharacter pc : party.getMembers()) {
            Character p = pc == null ? null : pc.getPlayer();
            if (p != null && !isBot(p) && p.getMap() != null) {
                return p;
            }
        }
        return null;
    }

    // Answers any pending party invite every tick: accept only the player the dialogue armed
    // (rollPartyAsk), politely reject everything else so the first-wins queue never rots.
    private void pollRecruitInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        BotRecruitManager.armIfWelcome(chr); // 1.5.0: friends and guildmates take an unasked invite
        int recruiterId = BotRecruitManager.armedInviterId(chr.getId()); // read BEFORE poll - JOINED clears it
        if (BotRecruitManager.pollInvites(chr) == BotRecruitManager.InvitePoll.JOINED) {
            Character recruiter = chr.getClient().getChannelServer()
                    .getPlayerStorage().getCharacterById(recruiterId);
            sayRecruit("PartyJoined", recruiter);
            // No re-typing: a partied TrainingBot keeps grinding; DECIDE is now party-aware.
        }
    }

    private void onSoloMenuSelect(int idx, Character player) {
        switch (idx) {
            case 0 -> { // How's the training?
                sayRecruit("TrainingTalk", player);
                soloMenu.show(player);
            }
            case 1 -> optPartyAsk(player);
            default -> { // Goodbye
                sayRecruit("Goodbye", player);
                soloMenu.close(player);
            }
        }
    }

    private void onPartyMenuSelect(int idx, Character player) {
        switch (idx) {
            case 0 -> { // How's the training?
                sayRecruit("TrainingTalk", player);
                partyMenu.show(player);
            }
            case 1 -> optFollowMe(player);
            default -> { // Goodbye
                sayRecruit("Goodbye", player);
                partyMenu.close(player);
            }
        }
    }

    private void optPartyAsk(Character player) {
        Character chr = getChr();
        if (chr.getParty() != null) { // partied since the solo menu was shown
            sayRecruit(inSameParty(player) ? "AlreadyPartied" : "PartyDecline", player);
            soloMenu.close(player);
            return;
        }
        BotRecruitManager.RecruitAnswer ans = BotRecruitManager.rollPartyAsk(
                chr, player, BotRecruitManager.TRAINING_ACCEPT_CHANCE, false);
        if (ans == BotRecruitManager.RecruitAnswer.ACCEPTED) {
            sayRecruit("PartyAccept", player);
            soloMenu.close(player); // pollRecruitInvite now waits for this player's invite
        } else {
            sayRecruit("PartyDecline", player);
            soloMenu.show(player);
        }
    }

    // Follow me! -> become this player's FollowerBot (same-party gated; the shower only picked
    // the party menu, the ASKER may still be a stranger).
    private void optFollowMe(Character player) {
        Character chr = getChr();
        if (!inSameParty(player)) {
            sayRecruit("PartyFirst", player);
            partyMenu.close(player);
            return;
        }
        if (!config.YamlConfig.config.server.OWNED_ALTS_ENABLED
                && BotRecruitManager.activeFollowerCount() >= BotRecruitManager.FOLLOWER_CAP) {
            sayRecruit("PartyDecline", player);
            partyMenu.close(player);
            return;
        }
        boolean altCap = config.YamlConfig.config.server.OWNED_ALTS_ENABLED;
        if (altCap && !soloMapling.companions.OwnedAltService.getInstance().reserveCompanion(player.getId(), chr.getId())) {
            sayRecruit("PartyDecline", player);
            partyMenu.close(player);
            return;
        }
        try {
            sayRecruit("FollowAccept", player);
            partyMenu.close(player);
            BotRecruitManager.setPendingLeader(chr.getId(), player.getId());
            // stopScheduledTask (via convert) releases grind occupancy, combat and movement.
            BotTypeManager.convertBotType(chr, BotTypeManager.BotType.FOLLOWER_BOT);
        } finally {
            if (altCap) soloMapling.companions.OwnedAltService.getInstance().releaseCompanion(chr.getId());
        }
    }

    private boolean inSameParty(Character player) {
        return getChr().getParty() != null && player.getParty() != null
                && player.getParty().getId() == getChr().getParty().getId();
    }

    // Direct menu reply to the asking player (they're on the map by definition - no observation
    // gate, no context-chance bias like sayContext).
    private void sayRecruit(String node, Character player) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, node, chr, player);
            if (line != null) {
                BotSpeak(chr, line);
            }
        } catch (Exception e) {
            // a missing dialogue node must never break the tick
        }
    }

    // Debug narration (OFF by default). When enabled, the bot announces what it's doing — every phase change
    // and the notable fail/repair branches — as normal chat (speech bubble + chat box) so you can watch the
    // macro FSM live. The switch is a PLAIN LOCAL below, deliberately NOT static/final, so you can flip it to
    // true and HotSwap just this one method while the server is running (no restart; a static/final would be
    // constant-folded at the call sites or keep its old value across a reload).
    void debugChat(String message) { // package-visible: GrindBreakRoutine narrates through the bot
        boolean debugChatEnabled = false; // <-- flip to true + HotSwap this method to turn narration on
        if (!debugChatEnabled) {
            return;
        }
        Character chr = getChr();
        if (chr == null || chr.getMap() == null) {
            return;
        }
        BotFullChat(chr, message); // general chat: bubble above the head AND a line in the chat box
    }

    // Offscreen loot is not minted: it is drawn from the map instance's own hunting-capacity budget, shared
    // with every other bot grinding there, and credited through the same holdings a real drop would use. A
    // bot whose inventory cannot take its share forfeits it rather than banking it.
    private void accrueAbstractLoot(Character chr, long nowMs, long elapsedMs) {
        OffscreenRewardBudget.Reward reward = OffscreenRewardBudget.draw(chr.getMapId(),
                chr.getMap().getCharacters().size(), elapsedMs, nowMs, chr.getWorldServer());
        if (reward.isEmpty()) {
            return;
        }
        // Ticket 15: the share then passes the bot's map-instance yield cap and its daily meso cap. What a cap
        // refuses is forfeited with the rest of the window, never banked.
        long mesos = BotYieldCaps.takeMesos(chr, Math.min(reward.mesos(), Integer.MAX_VALUE));
        if (mesos > 0) {
            if (BotHoldings.grantMeso(chr, (int) mesos)) {
                soloMapling.Economy.stats.EconomyCounters.recordMobMesoDrop(true, mesos);   // 1.6.0 Track D
            } else {
                BotYieldCaps.returnMesos(chr, mesos);
            }
        }
        Item equip = null;
        for (Item item : reward.items()) {
            if (!BotYieldCaps.takeItem(chr, item)) {
                continue;
            }
            if (!BotHoldings.grantItem(chr, item)) {
                BotYieldCaps.returnItem(chr, item);
            } else if (item.getInventoryType() == InventoryType.EQUIP) {
                equip = item;
            }
        }
        BotGear.afterLoot(chr, equip);   // real bots 10: offscreen bots dress by the same rules
    }

    // Silent accrual: the kill rate and EXP a kill gives, from the same damage formula the bot hits with on screen
    // (OffscreenCombat: its stats, gear, skills and buffs against the map's representative mob). A map whose mobs
    // the data cannot build keeps the old abstract pace: kills/min x the map's mob level.
    // Applied through the character's own offscreen path so it serializes with observed gains and
    // re-derives stats; nothing is sent to the map (ticket 07 rule 2).
    private double abstractExpRemainder;

    private void accrueAbstractExp(OffscreenCombat.Rate rate, int mobLevel, double elapsedSec) {
        double perSecond = rate != null ? rate.expPerSecond() : (KILLS_PER_MIN / 60.0) * Math.max(1, mobLevel);
        double owed = perSecond * elapsedSec + abstractExpRemainder;   // a short tick's fraction carries over
        long gain = (long) Math.floor(owed);
        abstractExpRemainder = owed - gain;
        if (gain <= 0) {
            return;
        }
        Character chr = getChr();
        if (chr.applyOffscreenExp(gain, LEVEL_CAP) > 0) {
            // Announce the bot's own level-up so nearby bots can congratulate it (same event path as
            // real players). Once, at the final level. The abstract EXP loop can span several levels
            // when the bot ticks slowly while unobserved - we still fire a single event.
            EventBus.getInstance().publish(EventFactory.createLevelUpEvent(chr));
        }
    }

    // ── Map selection (level-scaled hops + weighted, occupancy-balanced, chill-aware pick) ──

    // Maps recently left because they were saturated, with expired entries pruned. Fed to the chooser
    // so a just-left packed map isn't immediately re-picked (map-scale analogue of the spot exclude cooldown).
    private Set<Integer> crowdExcludedMaps() {
        long t = now();
        mapCrowdCooldown.values().removeIf(until -> until <= t);
        return new HashSet<>(mapCrowdCooldown.keySet());
    }

    // Record a train target + reserve its world-wide occupancy slot (station-here / party paths; the
    // normal DECIDE path gets its slot from TrainingMapChooser.choose and records the fields directly).
    private void setTrainTarget(int mapId, int mobLevel) {
        clearTrainTarget();
        currentTrainMapId = mapId;
        currentMobLevel = mobLevel;
        TrainingMapChooser.reserve(mapId);
    }

    private void clearTrainTarget() {
        if (currentTrainMapId >= 0) {
            TrainingMapChooser.release(currentTrainMapId);
        }
        currentTrainMapId = -1;
        currentMobLevel = 0;
    }

    private long dwellMs() {
        return TOWN_DWELL_MIN_MS + (long) (rng.nextDouble() * (TOWN_DWELL_MAX_MS - TOWN_DWELL_MIN_MS));
    }

    // Length of the grind session about to start. Normally a fresh 10–20 min. But a bot flagged
    // midSessionGrind (warped in, or already on the map at its first decision) is meant to look like it's
    // been grinding a while: caught at a random point inside a normal session, floored so a just-arrived bot
    // doesn't instantly turn around. One-time — the flag is consumed here, so every later session is full.
    private long grindSessionMs() {
        long full = LifeRuntime.enabled() ? dailyLife.memory().profile().grindDurationMs()
                : GRIND_MIN_MS + (long) (rng.nextDouble() * (GRIND_MAX_MS - GRIND_MIN_MS));
        if (midSessionGrind) {
            midSessionGrind = false;
            return Math.max(MID_SESSION_FLOOR_MS, (long) (rng.nextDouble() * full));
        }
        return full;
    }

    private long shopDwellMs() {
        return SHOP_DWELL_MIN_MS + (long) (rng.nextDouble() * (SHOP_DWELL_MAX_MS - SHOP_DWELL_MIN_MS));
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    // ── Diagnostics: aggregate LOD/training overview (backs !gcmove lod train) ──

    // A snapshot for a GM: how many active training bots sit in each LOD tier (full/halo/dwell/coarse) and
    // each macro phase, plus the maps currently FULL and HALO with names, so it's quick to verify the right
    // maps are being observed/trained. Aggregate counts only (per-bot listing would be too noisy at scale).
    public static List<String> lodDiagnostic() {
        List<String> out = new ArrayList<>();

        // Name occupied maps without MapFactory plumbing: index every online character's live map once.
        Map<Integer, MapleMap> mapsById = new HashMap<>();
        Server server = Server.getInstance();
        if (server != null) {
            for (World w : server.getWorlds()) {
                if (w == null) {
                    continue;
                }
                for (Character c : w.getPlayerStorage().getAllCharacters()) {
                    if (c != null && c.getMap() != null) {
                        mapsById.putIfAbsent(c.getMapId(), c.getMap());
                    }
                }
            }
        }

        int total = 0, full = 0, halo = 0, dwell = 0, coarse = 0;
        int pInit = 0, pTown = 0, pShop = 0, pDecide = 0, pTrain = 0, pGrind = 0, pGoTown = 0, pBreak = 0;
        Map<Integer, Integer> trainByMap = new TreeMap<>();
        for (BotSM b : CharacterStorage.getAllBots().values()) {
            if (!(b instanceof TrainingBot tb)) {
                continue;
            }
            Character chr = tb.getChr();
            if (chr == null) {
                continue;
            }
            total++;
            int mapId = chr.getMapId();
            trainByMap.merge(mapId, 1, Integer::sum);
            switch (GCMovement.lodTier(mapId)) {
                case "full" -> full++;
                case "halo" -> halo++;
                case "dwell" -> dwell++;
                default -> coarse++;
            }
            switch (tb.phase) {
                case INIT -> pInit++;
                case IN_TOWN -> pTown++;
                case SHOP_TRAVEL, SHOP_DWELL, SHOP_RETURN -> pShop++;
                case DECIDE -> pDecide++;
                case GO_TRAIN -> pTrain++;
                case GRIND -> pGrind++;
                case GO_TOWN -> pGoTown++;
                case BREAK_TRAVEL, BREAK_REST -> pBreak++;
            }
        }

        out.add("=== TrainingBot LOD overview ===");
        out.add(String.format("training bots: %d   tiers: full=%d halo=%d dwell=%d coarse=%d",
                total, full, halo, dwell, coarse));
        out.add(String.format("phases: grind=%d goTrain=%d goTown=%d inTown=%d shop=%d decide=%d init=%d break=%d",
                pGrind, pTrain, pGoTown, pTown, pShop, pDecide, pInit, pBreak));
        out.add(String.format("-- FULL maps (real player present): %d --", GCMovement.observedFullMaps().size()));
        appendMapLines(out, GCMovement.observedFullMaps(), mapsById, trainByMap);
        out.add(String.format("-- HALO maps (portal-adjacent): %d --", GCMovement.observedHaloMaps().size()));
        appendMapLines(out, GCMovement.observedHaloMaps(), mapsById, trainByMap);
        return out;
    }

    private static void appendMapLines(List<String> out, Set<Integer> mapIds,
                                       Map<Integer, MapleMap> mapsById, Map<Integer, Integer> trainByMap) {
        if (mapIds.isEmpty()) {
            out.add("  (none)");
            return;
        }
        for (int mapId : new TreeSet<>(mapIds)) {
            MapleMap m = mapsById.get(mapId);
            String name = (m != null) ? (m.getStreetName() + " - " + m.getMapName()) : "?";
            int bots = trainByMap.getOrDefault(mapId, 0);
            out.add(String.format("  %d  %s  [%d training bot(s)]", mapId, name, bots));
        }
    }

    // Release combat + movement when the bot stops (FINISHED / converted / manually stopped).
    @Override
    public synchronized void stopScheduledTask() {
        soloMapling.ArtificialPlayer.BotLifeSystem.LifeTownSlots.release(getChr().getId());
        soloMapling.fishing.GameFishing.cancel(getChr());
        lifeActivities.close();
        ACTIVE_GRINDERS.remove(this);
        swingClock.disarm();
        // Disarm the Sleepywood sauna errand: its door-walk cap and the dwell that walks the bot out of the sauna are
        // one-shot tasks on the shared scheduler that outlive this FSM. Left armed, a bot logged out mid-sauna was
        // sent travelling (and warped) after its removal - the "got stuck when moving to map 10504040x" flood.
        saunaTripActive = false;
        Character chr = getChr();
        if (chr != null) {
            if (chr.getChair() > 0) {
                botCancelChair(chr); // mid-break conversion: don't leave the new type glued to a chair
            }
            grind.release(chr);
            clearTrainTarget(); // release this map's occupancy slot
            soloMapling.ArtificialPlayer.BotGrindSystem.LevelingPathCensus.forget(chr.getId());
            BotAttackDriver.clearBot(chr.getId());
            BotBuffDriver.clearBot(chr.getId());
            BotDeaths.clearBot(chr.getId());
            GCMovement.releaseCommitmentHold(chr);
            BotWanderSystem.stop(chr); // end any shop-dwell flavor wander
            TownLoiter.stop(chr); // free any town-loiter ledge claim + fidget before releasing movement
            GCMovement.disable(chr); // releases the shared movement lock the recorded engine needs
        }
        super.stopScheduledTask();
        log("[TrainingBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }

    /** Reads players' typed lines itself (menu, bets, script): free text stays with it (bot-persistence ticket 21). */
    @Override
    public boolean consumesFreeText() {
        return true;
    }
}
