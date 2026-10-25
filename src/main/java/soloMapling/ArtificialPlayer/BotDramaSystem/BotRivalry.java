package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityReactions;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotAffinitySystem.ChatTone;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrain;
import soloMapling.ArtificialPlayer.BotBrainSystem.BotBrains;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ContentGuard;
import soloMapling.ArtificialPlayer.BotChatPrompt;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotCoupleSystem.BotCouples;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 1.5.0 bot drama, part 2: spicy rivals. Builds on ticket 58's rivals and nemeses ({@code AffinityReactions}), only
 * for players with bot drama on ({@code @botdrama off} removes every part of this for them):
 *
 * <ul>
 *   <li><b>Roasts</b> ({@link RivalRoasts}): a rival's taunt uses real facts about the player's game (level against the
 *   map, job, deaths today, a weapon far below their level, KS habit), scripted or - when the chat model is live - one
 *   short model line with the facts and the rules (game only), checked by {@link ContentGuard}.</li>
 *   <li><b>KS wars</b>: a rival training bot calls 2-4 guildmates or friends to the player's map; for a few minutes
 *   ({@code ksWarMinutes}, 3) each takes a few of the player's mobs (its own reach only), then they all go back. At
 *   most once per player per day, {@code ksWarsPerHour} (3) world-wide.</li>
 *   <li><b>Callouts</b>: a nemesis (or a rival the player KS'd a lot) posts "X is a KSer, dont party with them" by
 *   megaphone - through the megaphone gate, once per player per day, 4 an hour world-wide.</li>
 *   <li><b>Revenge arcs</b>: a rival that just taunted or KS'd the player follows them once to the NEXT map they enter
 *   and demands an apology. "sorry" (rules, else the Decide model) calms it with an affinity boost; mocking makes it a
 *   nemesis; silence for 90 s and it gives up. Then it goes back where it was. Once per pair per day.</li>
 * </ul>
 * Never blocks play: nobody is locked out of a map, KS sprees take only a few mobs in a bot's own reach, every scene
 * ends by itself, and nothing else follows a player across maps.
 */
public final class BotRivalry {

    private static final Logger log = LoggerFactory.getLogger(BotRivalry.class);

    /** A KS war on a player: the bots that came, where they came from, and when it ends. */
    static final class KsWar {
        final int playerId;
        final int callerId;
        final int mapId;
        final long until;
        final Map<Integer, DramaKit.Home> helpers = new LinkedHashMap<>();
        volatile boolean over;

        KsWar(int playerId, int callerId, int mapId, long until) {
            this.playerId = playerId;
            this.callerId = callerId;
            this.mapId = mapId;
            this.until = until;
        }
    }

    /** A rival armed to follow the player once (it just taunted or KS'd them on {@code mapId}). */
    record Arm(int botId, int mapId, long until) {
    }

    /** A revenge follow in progress: the bot waits on the player's new map for an apology. */
    static final class Arc {
        final int botId;
        final int playerId;
        final DramaKit.Home home;
        /** When it gives up; a chase to the player's next map starts the wait again. */
        volatile long until;
        volatile boolean over;
        /** How often it chased the player to another map in this arc (rc6: at most once). */
        volatile int chases;
        /** The map it waits on now. */
        volatile int mapId;

        Arc(int botId, int playerId, DramaKit.Home home, long until) {
            this.botId = botId;
            this.playerId = playerId;
            this.home = home;
            this.until = until;
        }
    }

    private static final Map<Integer, long[]> deaths = new ConcurrentHashMap<>();
    private static final Map<Integer, KsWar> wars = new ConcurrentHashMap<>();
    private static final Map<Integer, Arm> armed = new ConcurrentHashMap<>();
    private static final Map<Integer, Arc> arcs = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;
    private static volatile long lastStepAt;

    static final ChatDecide.Question APOLOGY = new ChatDecide.Question("apology",
            "The bot demanded an apology from the player for KSing it. How does the player answer?", labels(
            "apology", "says sorry, apologises, admits fault or makes peace",
            "mocking", "mocks, laughs at, insults or refuses",
            "other", "something unrelated or unclear"));

    private BotRivalry() {
    }

    private static Map<String, String> labels(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return m;
    }

    public static boolean enabled() {
        return DramaKit.enabled() && !"false".equalsIgnoreCase(System.getProperty("solomapling.drama.rivals", "true"));
    }

    static long now() {
        return clock.getAsLong();
    }

    static long day(long now) {
        return Math.floorDiv(now, TimeUnit.DAYS.toMillis(1));
    }

    /** Whether drama aimed at this player may happen: the package and hostility on, the player did not opt out. */
    static boolean allowed(int playerId) {
        return enabled() && BotAffinity.hostilityEnabled() && !BotAffinity.optedOut(playerId);
    }

    // ── deaths (for roasts) ──

    /** A real player died: counted for today. */
    static void onDeath(Character player) {
        long d = day(now());
        deaths.compute(player.getId(), (k, v) -> v == null || v[0] != d ? new long[]{d, 1} : new long[]{d, v[1] + 1});
    }

    static int deathsToday(int playerId) {
        long[] v = deaths.get(playerId);
        return v == null || v[0] != day(now()) ? 0 : (int) v[1];
    }

    // ── roasts ──

    /**
     * A rival's taunt: a roast from the player's real game facts (a model line when the chat model is live, half the
     * time, else scripted). What was said, for the QA log. Off the driver thread.
     */
    public static String roast(Character bot, Character player, AffinityState state) {
        if (!allowed(player.getId())) {
            return "skipped (@botdrama off)";
        }
        arm(bot, player);
        RivalRoasts.Facts facts = RivalRoasts.facts(player, BotAffinity.hostileCount(bot.getId(), player.getId()),
                deathsToday(player.getId()));
        qa(player.getMapId(), "ROAST facts for " + player.getName() + ": " + facts.describe());
        RivalRoasts.Roast scripted = RivalRoasts.pick(facts, ThreadLocalRandom.current());
        BotBrain brain = BotBrains.get();
        if (brain.isLive() && ThreadLocalRandom.current().nextDouble() < DramaKit.prop("rivals.llmRoastChance", 0.5)) {
            List<BotBrain.Turn> prompt = BotChatPrompt.conversation(RivalRoasts.prompt(bot.getName(), facts,
                    state == AffinityState.NEMESIS), List.of(BotBrain.Turn.user("(" + player.getName()
                    + " is standing near you)")));
            brain.complete(player.getId(), prompt).thenAccept(line -> {
                String said = line.filter(ContentGuard::ok).orElse(null);
                if (said != null) {
                    soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak(bot, said);
                    qa(bot.getMapId(), "ROAST (model) " + bot.getName() + " -> " + player.getName() + ": " + said);
                } else {
                    String l = DramaKit.say(bot, scripted.node(), scripted.tokens());
                    qa(bot.getMapId(), "ROAST (scripted, model fell back) " + bot.getName() + " -> " + player.getName()
                            + ": " + l);
                }
            });
            return "roast through the chat model (facts: " + facts.describe() + ")";
        }
        String line = DramaKit.say(bot, scripted.node(), scripted.tokens());
        if (line == null) {
            line = DramaKit.say(bot, state == AffinityState.NEMESIS ? "RoastLevel" : "RoastJob", scripted.tokens());
        }
        qa(bot.getMapId(), "ROAST " + scripted.node() + " " + bot.getName() + " -> " + player.getName() + ": " + line);
        return "said \"" + line + "\"";
    }

    // ── on sight ──

    /**
     * A roaming bot noticed a real player (driver thread, memory only): a rival or nemesis may call a KS war or a
     * callout megaphone. True when it did.
     */
    public static boolean onSight(BotSM sm, Character player) {
        if (!enabled() || sm == null || sm.getChr() == null || player == null || !BotAffinity.hasTies(player.getId())) {
            return false;
        }
        Character bot = sm.getChr();
        AffinityState state = BotAffinity.state(bot.getId(), player.getId());
        if (!state.hostile() || !allowed(player.getId()) || bot.getMapId() != player.getMapId()) {
            return false;
        }
        long now = now();
        Random rng = ThreadLocalRandom.current();
        boolean nemesis = state == AffinityState.NEMESIS;
        if (sm instanceof TrainingBot && !wars.containsKey(player.getId()) && warRollDue(bot.getId(), player.getId(), now)
                && rng.nextDouble() < DramaKit.prop(nemesis ? "rivals.ksWarNemesis" : "rivals.ksWarRival", nemesis ? 0.15 : 0.05)
                && warAllowedToday(player.getId(), now) && MapMobIndex.level(player.getMapId()) >= 0
                && player.getEventInstance() == null && DramaKit.hourly("rv-kswar",
                (int) DramaKit.prop("rivals.ksWarsPerHour", 3), now)) {
            ExecutorServiceManager.runAsync(() -> startKsWar(bot, player, 2 + rng.nextInt(3), false));
            return true;
        }
        if ((nemesis || BotAffinity.hostileCount(bot.getId(), player.getId()) >= 4) && rng.nextDouble() < 0.1
                && DramaKit.free("rv:callout:" + player.getId(), now)) {
            ExecutorServiceManager.runAsync(() -> callout(bot, player, false));
            return true;
        }
        return false;
    }

    // ── KS wars ──

    static boolean warAllowedToday(int playerId, long now) {
        return !warsHeld.contains(playerId) && DramaKit.free("rv:kswar:" + playerId, now);
    }

    /**
     * 1.5.0 rc6 (QA t-af-2): the war chance is rolled once per rival and player every {@code ksWarRollMinutes} (10),
     * not on every sighting. Sightings come every 1.5 s while the two stand together, so a 5% roll per sighting
     * called a KS war within about half a minute of meeting any rival ("two random bots just spawn and started going
     * full ks mode").
     */
    static boolean warRollDue(int botId, int playerId, long now) {
        return DramaKit.take("rv:kswar-roll:" + botId + ":" + playerId, now, DramaKit.minutes("rivals.ksWarRollMinutes", 10));
    }

    /** Players no rival may call a KS war on by itself now (a !qa af-2/af-3 run: its KS sprees are the test). */
    private static final java.util.Set<Integer> warsHeld = ConcurrentHashMap.newKeySet();

    public static void holdWars(int playerId, boolean hold) {
        if (hold) {
            warsHeld.add(playerId);
        } else {
            warsHeld.remove(playerId);
        }
    }

    /** {@code @botdrama off}: a KS war on the player ends now (its bots go back), as a spree already stops. */
    public static void onDramaOff(int playerId) {
        KsWar w = wars.get(playerId);
        if (w != null) {
            endKsWar(w, "@botdrama off");
        }
    }

    /**
     * The rival calls {@code n} (2-4) guildmates or friends to the player's map: each takes a few of the player's mobs
     * for {@code ksWarMinutes}, then they all go back. {@code force} (GM / !qa) skips the daily and hourly caps, never
     * {@code @botdrama off}. What happened.
     */
    public static String startKsWar(Character rival, Character player, int n, boolean force) {
        long now = now();
        if (!allowed(player.getId())) {
            return "no KS war: " + player.getName() + " has @botdrama off (or hostility is off)";
        }
        if (wars.containsKey(player.getId())) {
            return "a KS war on " + player.getName() + " is already on";
        }
        if (rival.getMapId() != player.getMapId() || MapMobIndex.level(player.getMapId()) < 0) {
            return "the rival must stand with the player on a map with mobs";
        }
        if (!force && !DramaKit.take("rv:kswar:" + player.getId(), now, TimeUnit.DAYS.toMillis(1))) {
            return "already a KS war on " + player.getName() + " today";
        }
        if (force) {
            DramaKit.take("rv:kswar:" + player.getId(), now, TimeUnit.DAYS.toMillis(1));
        }
        long ms = DramaKit.minutes("rivals.ksWarMinutes", 3);
        KsWar war = new KsWar(player.getId(), rival.getId(), player.getMapId(), now + ms);
        wars.put(player.getId(), war);
        List<Character> crew = recruit(rival, player, Math.max(2, Math.min(4, n)));
        Map<String, String> t = new HashMap<>();
        t.put("PLAYER", player.getName());
        t.put("MAP", DramaKit.mapName(player));
        t.put("CH", Integer.toString(DramaKit.channelOf(player)));
        GuildStore.MemberRow g = BotGuilds.membership(rival.getId());
        String call = DramaKit.pick("KsWarCall", t);
        if (g != null && call != null) {
            BotGuilds.sayInGuild(rival, call);
        }
        DramaKit.say(rival, "KsWarRally", t);
        AffinityReactions.startWarSpree(rival, player, ms, 5);
        int i = 0;
        for (Character helper : crew) {
            DramaKit.Home home = DramaKit.homeOf(helper);
            int offset = (i % 2 == 0 ? 1 : -1) * (60 + 40 * (i / 2));
            if (!DramaKit.bringTo(helper, player, offset, true)) {
                continue;
            }
            DramaKit.busy(helper.getId(), "ks war");
            war.helpers.put(helper.getId(), home);
            AffinityReactions.startWarSpree(helper, player, ms, 5);
            Character h = helper;
            BotTiming.after(1_500L + 1_200L * i, () -> DramaKit.say(h, "KsWarArrive", t));
            i++;
        }
        BotTiming.after(ms, () -> endKsWar(war, "time"));
        BotTiming.after(RALLY_CHECK_MS, () -> rallyStrays(war, 1));
        qa(player.getMapId(), "KS WAR " + rival.getName() + " calls " + war.helpers.size() + " bot(s) on "
                + player.getName() + " (" + names(war.helpers.keySet()) + ") for " + TimeUnit.MILLISECONDS.toSeconds(ms)
                + " s" + (force ? " [forced: caps skipped]" : ""));
        return rival.getName() + " called " + war.helpers.size() + " bot(s) to KS " + player.getName() + " for "
                + TimeUnit.MILLISECONDS.toSeconds(ms) + " s: " + names(war.helpers.keySet());
    }

    /** Ticket 66: how often a war looks for helpers that wandered off the player's platform, and how many times. */
    static final long RALLY_CHECK_MS = 6_000L;
    static final int RALLY_ROUNDS = 8;
    /** A helper this far above/below the player is on another ledge; this far along x, out of the take reach. */
    static final int STRAY_DY = 60, STRAY_DX = 400;

    /** Helpers (by id) whose spot is on another ledge than the player's or too far along it to take their mobs. */
    static List<Integer> strays(Map<Integer, Point> helperSpots, Point player) {
        List<Integer> out = new ArrayList<>();
        if (player == null) {
            return out;
        }
        for (Map.Entry<Integer, Point> e : helperSpots.entrySet()) {
            Point p = e.getValue();
            if (p != null && (Math.abs(p.y - player.y) > STRAY_DY || Math.abs(p.x - player.x) > STRAY_DX)) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    /**
     * Ticket 66: after landing, the training brain picks its own grind spot and can walk off to another ledge, where
     * nothing it fights is the player's mob. While the war lasts, a helper found off the player's platform is walked
     * back (a bounded walk, then it fights from where it stands; never a warp, never a held follow, which made helpers
     * stop fighting). Checked every {@value #RALLY_CHECK_MS} ms, at most {@value #RALLY_ROUNDS} rounds.
     */
    static void rallyStrays(KsWar war, int round) {
        if (war.over || round > RALLY_ROUNDS || now() >= war.until) {
            return;
        }
        try {
            Character player = DramaKit.onlinePlayer(war.playerId);
            if (player != null && player.getMapId() == war.mapId && player.getPosition() != null) {
                Map<Integer, Point> spots = new LinkedHashMap<>();
                for (Integer id : war.helpers.keySet()) {
                    Character h = DramaKit.liveBot(id);
                    if (h != null && h.getMapId() == war.mapId) {
                        spots.put(id, h.getPosition());
                    }
                }
                int i = 0;
                for (int id : strays(spots, player.getPosition())) {
                    Character h = DramaKit.liveBot(id);
                    Point at = new Point(player.getPosition());
                    at.translate((i++ % 2 == 0 ? 1 : -1) * 60, 0);
                    qa(war.mapId, "RALLY STRAY " + h.getName() + " at " + h.getPosition() + " walks back to "
                            + player.getPosition() + " (round " + round + ")");
                    DramaKit.walkTo(h, at, war.mapId, null);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Could not rally the strays of the KS war on {}", war.playerId, e);
        }
        BotTiming.after(RALLY_CHECK_MS, () -> rallyStrays(war, round + 1));
    }

    /** Guildmates of the rival first, then its partner, then any free training bot on the player's channel. */
    static List<Character> recruit(Character rival, Character player, int n) {
        List<Character> out = new ArrayList<>();
        int ch = DramaKit.channelOf(player);
        GuildStore.MemberRow m = BotGuilds.membership(rival.getId());
        if (m != null) {
            for (GuildStore.MemberRow mate : BotGuilds.membersOf(m.guildId())) {
                Character c = DramaKit.liveBot(mate.charId());
                if (out.size() < n && c != null && c.getId() != rival.getId() && fit(c)) {
                    out.add(c);
                }
            }
        }
        int partner = BotCouples.partnerOf(rival.getId());
        Character p = partner > 0 ? DramaKit.liveBot(partner) : null;
        if (out.size() < n && p != null && fit(p) && !out.contains(p)) {
            out.add(p);
        }
        int mobLevel = MapMobIndex.level(player.getMapId());
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character c = sm.getChr();
            if (out.size() >= n) {
                break;
            }
            if (c != null && c.getId() != rival.getId() && !out.contains(c) && sm instanceof TrainingBot
                    && DramaKit.channelOf(c) == ch && fit(c) && Math.abs(c.getLevel() - mobLevel) <= 30
                    && !BotAffinity.friend(c.getId(), player.getId())) {
                out.add(c);
            }
        }
        return out;
    }

    private static boolean fit(Character c) {
        return DramaKit.freeForScene(c) && !BotRomanceFacade.partnerOfAnyone(c.getId());
    }

    /** The war is over: the helpers say something smug and go back where they were. */
    static void endKsWar(KsWar war, String why) {
        if (war.over) {
            return;
        }
        war.over = true;
        wars.remove(war.playerId, war);
        Character player = DramaKit.onlinePlayer(war.playerId);
        Map<String, String> t = Map.of("PLAYER", player == null ? "" : player.getName());
        int i = 0;
        for (Map.Entry<Integer, DramaKit.Home> e : war.helpers.entrySet()) {
            Character h = DramaKit.liveBot(e.getKey());
            AffinityReactions.stopSpree(e.getKey());
            DramaKit.unbusy(e.getKey());
            soloMapling.worldevents.WorldEventSteer.unhold(e.getKey()); // the war held it on the player's map
            if (h == null) {
                continue;
            }
            if (i++ == 0) {
                DramaKit.say(h, "KsWarLeave", t);
            }
            DramaKit.Home home = e.getValue();
            BotTiming.after(2_500L + 700L * i, () -> DramaKit.sendHome(h, home));
        }
        AffinityReactions.stopSpree(war.callerId);
        qa(war.mapId, "KS WAR over on " + war.playerId + " (" + why + "): " + war.helpers.size() + " bot(s) went back");
    }

    // ── callouts ──

    /** A nemesis calls the player out by megaphone (through the gate). {@code force} skips the daily and hourly caps. */
    public static String callout(Character bot, Character player, boolean force) {
        long now = now();
        if (!allowed(player.getId())) {
            return "no callout: " + player.getName() + " has @botdrama off";
        }
        if (!force && (!DramaKit.take("rv:callout:" + player.getId(), now, TimeUnit.DAYS.toMillis(1))
                || !DramaKit.hourly("rv-callout", (int) DramaKit.prop("rivals.calloutsPerHour", 4), now))) {
            return "callout capped (once per player per day, 4 an hour)";
        }
        boolean sent = WorldEventMegaphones.getInstance().offerEvent(bot, "DramaCallout", Map.of("PLAYER",
                player.getName(), "MAP", DramaKit.mapName(player)), "drama:callout:" + player.getId()
                + (force ? ":" + now : ""));
        qa(player.getMapId(), "CALLOUT " + bot.getName() + " on " + player.getName() + ": megaphone "
                + (sent ? "sent" : "queued/gated"));
        return bot.getName() + " calls " + player.getName() + " out: " + (sent ? "megaphone sent"
                : "waiting for the megaphone gate");
    }

    // ── revenge arcs ──

    /** A rival just did something petty to the player on this map: it may follow them once to their next map. */
    public static void arm(Character bot, Character player) {
        if (!enabled() || bot == null || player == null || !allowed(player.getId())
                || warsHeld.contains(player.getId())) { // rc6 triage (!qa af-2): its rival "never follows you"
            return;
        }
        armed.put(player.getId(), new Arm(bot.getId(), player.getMapId(), now() + DramaKit.minutes("rivals.armMinutes", 10)));
    }

    /** The player changed maps: an armed rival on the map they left may follow them, once. */
    static void onMapChanged(Character player, int from) {
        if (armed.isEmpty() && arcs.isEmpty()) {
            return;
        }
        Arc open = arcs.get(player.getId());
        if (open != null) {
            chase(open, player, from);
            return;
        }
        Arm arm = armed.get(player.getId());
        long now = now();
        if (arm == null || arm.mapId() != from || now > arm.until()) {
            return;
        }
        armed.remove(player.getId());
        boolean qa = QaLog.on(QaLog.Topic.RIVALRY);
        long pair = ((long) player.getId() << 32) | (arm.botId() & 0xffffffffL);
        if (!allowed(player.getId()) || player.getEventInstance() != null
                || (!qa && ThreadLocalRandom.current().nextDouble() >= DramaKit.prop("rivals.revengeChance", 0.5))
                || !DramaKit.take("rv:arc:" + pair, now, qa ? TimeUnit.SECONDS.toMillis(30) : TimeUnit.DAYS.toMillis(1))
                || !DramaKit.hourly("rv-arc", 6, now)) {
            return;
        }
        int map = player.getMapId();
        BotTiming.after(5_000 + ThreadLocalRandom.current().nextInt(3_000), () -> follow(arm.botId(), player, map));
    }

    /** The one revenge follow: the rival comes to the player's new map and demands an apology. */
    static String follow(int botId, Character player, int map) {
        Character bot = DramaKit.liveBot(botId);
        if (bot == null || player.getMapId() != map || !BotAffinity.hostile(botId, player.getId())
                || !DramaKit.freeForScene(bot)) {
            return "the rival could not follow (gone, busy, or no longer a rival)";
        }
        DramaKit.Home home = DramaKit.homeOf(bot);
        if (!DramaKit.bringTo(bot, player, 60, false)) {
            return "the rival could not come to " + map;
        }
        DramaKit.busy(botId, "revenge follow");
        Arc arc = new Arc(botId, player.getId(), home, now() + waitMs());
        arc.mapId = map;
        arcs.put(player.getId(), arc);
        BotSM sm = CharacterStorage.getBotById(botId);
        if (sm != null) {
            BotConversation.open(sm, player); // the player's answer reaches it
        }
        String line = demand(arc, bot, player, "RevengeDemand");
        qa(map, "REVENGE " + bot.getName() + " followed " + player.getName() + " to " + map + ": " + line);
        scheduleTimeout(arc, player);
        return bot.getName() + " followed " + player.getName() + " and wants an apology";
    }

    static long waitMs() {
        return DramaKit.minutes("rivals.apologyWaitMinutes", 1.5);
    }

    /**
     * rc6 (QA t-rv-4: "bot followed me once, but didnt really ask for apology till way later"): the demand is said a
     * beat after the bot lands (its spawn is drawn first, so the line is not lost), whispered too (a busy town's chat
     * scrolls it away), and repeated at 30 s and 60 s while no answer came, the bot walking up to the player.
     */
    static String demand(Arc arc, Character bot, Character player, String node) {
        String line = DramaKit.pick(node, Map.of("PLAYER", player.getName()));
        if (line == null) {
            return null;
        }
        BotTiming.after(1_200, () -> {
            Character b = DramaKit.liveBot(arc.botId);
            if (!arc.over && b != null && b.getMapId() == player.getMapId()) {
                soloMapling.ArtificialPlayer.BotMovementSystem.MovementCommands.botFaceTowardsPoint(b, player.getPosition());
                DramaKit.speak(b, line);
                DramaKit.reply(b, player, line);
            }
        });
        int chases = arc.chases;
        for (long at : new long[]{30_000, 60_000}) {
            BotTiming.after(at, () -> remind(arc, player, chases));
        }
        return line;
    }

    /** No answer yet: it walks up to the player and asks again (not after a chase moved the arc on). */
    static void remind(Arc arc, Character player, int chases) {
        Character b = DramaKit.liveBot(arc.botId);
        if (arc.over || arc.chases != chases || b == null || player.getMapId() != b.getMapId()) {
            return;
        }
        java.awt.Point p = player.getPosition();
        if (p != null && b.getPosition() != null && Math.abs(b.getPosition().x - p.x) > 70) {
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.move(b, p.x + (b.getPosition().x < p.x ? -45 : 45), p.y);
        }
        String line = DramaKit.say(b, "RevengeRemind", Map.of("PLAYER", player.getName()));
        qa(b.getMapId(), "REVENGE " + b.getName() + " asks again: " + line);
    }

    /** The arc gives up at its time; a chase pushed the time on, so a check before then waits again. */
    static void scheduleTimeout(Arc arc, Character player) {
        BotTiming.after(Math.max(1_000, arc.until - now() + 1_000), () -> {
            if (arc.over) {
                return;
            }
            if (now() < arc.until) {
                scheduleTimeout(arc, player);
                return;
            }
            Character b = DramaKit.liveBot(arc.botId);
            DramaKit.say(b, "RevengeTimeout", Map.of("PLAYER", player.getName()));
            qa(arc.mapId, "REVENGE no answer from " + player.getName() + ": " + (b == null ? arc.botId : b.getName())
                    + " gives up");
            leave(arc, 2_500);
        });
    }

    /**
     * rc6 (QA t-rv-4): the player walked off before answering. The rival comes after them once more (a few seconds
     * later, by the portal as a player would) and asks again; it used to stay behind with the arc open, then pop back
     * to its grind spot in front of the player when it gave up.
     */
    static void chase(Arc arc, Character player, int from) {
        Character bot = DramaKit.liveBot(arc.botId);
        if (arc.over || bot == null || bot.getMapId() != from || player.getEventInstance() != null) {
            return;
        }
        if (arc.chases >= 1) {
            qa(from, "REVENGE " + bot.getName() + " lets " + player.getName() + " go (already chased once)");
            leave(arc, 1_000);
            return;
        }
        arc.chases++;
        int map = player.getMapId();
        BotTiming.after(3_000 + ThreadLocalRandom.current().nextInt(2_000), () -> {
            Character b = DramaKit.liveBot(arc.botId);
            if (arc.over || b == null || player.getMapId() != map) {
                return;
            }
            if (!DramaKit.bringTo(b, player, 60, false)) {
                leave(arc, 1_000);
                return;
            }
            arc.mapId = map;
            arc.until = now() + waitMs();
            String line = demand(arc, b, player, "RevengeChase");
            qa(map, "REVENGE " + b.getName() + " chased " + player.getName() + " to " + map + ": " + line);
        });
    }

    /** The player's map chat during a revenge follow. True when it was the answer (not routed on to the bots). */
    static boolean onPlayerChat(Character player, String line) {
        if (arcs.isEmpty()) {
            return false;
        }
        Arc arc = arcs.get(player.getId());
        Character bot = arc == null ? null : DramaKit.liveBot(arc.botId);
        if (arc == null || arc.over || bot == null || bot.getMapId() != player.getMapId()) {
            return false;
        }
        return answer(arc, bot, player, line);
    }

    /** A line the player said to the bot itself (chat brain path): the answer to its demand, or null. */
    static String onPlayerLine(Character bot, Character player, String line) {
        if (arcs.isEmpty()) {
            return null;
        }
        Arc arc = arcs.get(player.getId());
        if (arc == null || arc.over || arc.botId != bot.getId()) {
            return null;
        }
        return answer(arc, bot, player, line) ? "" : null;
    }

    /** Judges an answer: rules first, else the Decide model (no answer = still waiting). True when it was judged. */
    static boolean answer(Arc arc, Character bot, Character player, String line) {
        if (RomanceRules.apology(line)) {
            calm(arc, bot, player, "rules");
            return true;
        }
        if (RomanceRules.mocking(line) || ChatTone.rule(line, null).orElse(null) == ChatTone.Tone.RUDE) {
            escalate(arc, bot, player, "rules");
            return true;
        }
        ChatDecide decide = ChatDecide.get();
        if (decide == null) {
            return false;
        }
        decide.ask(line, List.of(APOLOGY)).thenAccept(a -> a.map(m -> m.get(APOLOGY.name())).ifPresent(ans -> {
            if (arc.over || ans.confidence() < DramaKit.prop("rivals.decideMin", 0.6)) {
                return;
            }
            if ("apology".equals(ans.choice())) {
                calm(arc, bot, player, "decide");
            } else if ("mocking".equals(ans.choice())) {
                escalate(arc, bot, player, "decide");
            }
        }));
        return false;
    }

    private static void calm(Arc arc, Character bot, Character player, String how) {
        if (arc.over) {
            return;
        }
        BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.APOLOGY, AffinityEvent.APOLOGY.delta(),
                player.getName(), bot.getName(), player.getMapId());
        String line = DramaKit.say(bot, "RevengeAccepted", Map.of("PLAYER", player.getName()));
        qa(player.getMapId(), "REVENGE apology (" + how + "): " + bot.getName() + " calms down (+"
                + AffinityEvent.APOLOGY.delta() + "): " + line);
        leave(arc, 4_000);
    }

    private static void escalate(Arc arc, Character bot, Character player, String how) {
        if (arc.over) {
            return;
        }
        BotAffinity.stage(bot.getId(), player.getId(), AffinityState.NEMESIS);
        String line = DramaKit.say(bot, "RevengeEscalate", Map.of("PLAYER", player.getName()));
        qa(player.getMapId(), "REVENGE mocked (" + how + "): " + bot.getName() + " is " + player.getName()
                + "'s NEMESIS now: " + line);
        leave(arc, 4_000);
    }

    /**
     * The arc is over: the bot goes back where it was (it never follows again). rc6 (QA t-rv-4: "then tp'd back to
     * where he spawned"): a bot staged for a test stays where it is, and no bot pops back onto its old spot in front of
     * the player (when the player stands on the bot's home map it stays on the map it is on).
     */
    private static void leave(Arc arc, long afterMs) {
        if (arc.over) {
            return;
        }
        arc.over = true;
        arcs.remove(arc.playerId, arc);
        BotTiming.after(afterMs, () -> {
            DramaKit.unbusy(arc.botId);
            Character b = DramaKit.liveBot(arc.botId);
            Character p = DramaKit.onlinePlayer(arc.playerId);
            if (b == null) {
                return;
            }
            if (staysPut(DramaGm.isStaged(arc.botId), b.getMapId(), arc.home.mapId(), p == null ? -1 : p.getMapId())) {
                DramaKit.settle(b, arc.home);
            } else {
                DramaKit.sendHome(b, arc.home);
            }
        });
    }

    /** Whether a bot leaving an arc stays on its map instead of warping home. */
    static boolean staysPut(boolean staged, int botMap, int homeMap, int playerMap) {
        return staged || (botMap != homeMap && playerMap == homeMap);
    }

    // ── the chat prompt ──

    static String promptLine(int botCharId, Character player) {
        if (player == null || arcs.isEmpty()) {
            return "";
        }
        Arc arc = arcs.get(player.getId());
        if (arc == null || arc.botId != botCharId) {
            return "";
        }
        return " You followed " + player.getName() + " here because they KS'd you, and you want an apology. Be petty"
                + " about the game only.";
    }

    // ── the step ──

    /** A safety net from the director's tick: wars and arcs past their time end. */
    static void maybeStep(long now) {
        if (now - lastStepAt < TimeUnit.SECONDS.toMillis(30)) {
            return;
        }
        lastStepAt = now;
        for (KsWar w : new ArrayList<>(wars.values())) {
            if (now > w.until + TimeUnit.SECONDS.toMillis(30)) {
                endKsWar(w, "late");
            }
        }
        for (Arc a : new ArrayList<>(arcs.values())) {
            if (now > a.until + TimeUnit.SECONDS.toMillis(30)) {
                leave(a, 0);
            }
        }
        armed.values().removeIf(a -> now > a.until());
    }

    // ── GM ──

    public static List<String> describe(int playerId) {
        List<String> out = new ArrayList<>();
        out.add("@botdrama " + (BotAffinity.optedOut(playerId) ? "OFF (no rival drama)" : "on") + ", deaths today "
                + deathsToday(playerId));
        KsWar w = wars.get(playerId);
        out.add(w == null ? "no KS war" : "KS war by " + w.callerId + " with " + names(w.helpers.keySet()) + ", "
                + Math.max(0, (w.until - now()) / 1000) + " s left");
        Arm a = armed.get(playerId);
        out.add(a == null ? "no rival armed to follow" : "armed: bot " + a.botId() + " follows from map " + a.mapId());
        Arc arc = arcs.get(playerId);
        out.add(arc == null ? "no revenge follow" : "revenge follow by " + arc.botId + ", waiting for an apology");
        return out;
    }

    /** GM / !qa: arm a rival to follow the player's next map change now (the daily cap for the pair is cleared). */
    public static String armNow(Character bot, Character player) {
        // rc6 auto-QA triage (t-rv-4, 2026-10-23): the check apologises first (+30: rival -> neutral), then re-arms;
        // a neutral bot never follows, so the second and third follows never came. Re-arming makes it a rival again.
        String again = "";
        if (!BotAffinity.hostile(bot.getId(), player.getId())
                && BotAffinity.stage(bot.getId(), player.getId(), AffinityState.RIVAL)) {
            again = " (your rival again)";
        }
        long pair = ((long) player.getId() << 32) | (bot.getId() & 0xffffffffL);
        DramaKit.release("rv:arc:" + pair);
        armed.put(player.getId(), new Arm(bot.getId(), player.getMapId(), now() + TimeUnit.MINUTES.toMillis(10)));
        return bot.getName() + again + " will follow you once to the next map you enter (it must still be your rival)";
    }

    /** GM / !qa: end the KS war on the player now. */
    public static String endWarNow(int playerId) {
        KsWar w = wars.get(playerId);
        if (w == null) {
            return "no KS war";
        }
        endKsWar(w, "stopped");
        return "KS war ended";
    }

    static String names(java.util.Collection<Integer> ids) {
        List<String> out = new ArrayList<>();
        for (int id : ids) {
            Character c = DramaKit.liveBot(id);
            out.add(c == null ? "#" + id : c.getName());
        }
        return String.join(", ", out);
    }

    static void qa(int mapId, String line) {
        DramaKit.qa(QaLog.Topic.RIVALRY, mapId, line);
    }

    /** Tests. */
    static void resetForTest(LongSupplier testClock) {
        clock = testClock != null ? testClock : System::currentTimeMillis;
        deaths.clear();
        wars.clear();
        armed.clear();
        arcs.clear();
        warsHeld.clear();
        lastStepAt = 0;
    }

    static Arm armedFor(int playerId) {
        return armed.get(playerId);
    }

    /** Tests: a revenge follow waiting for an answer. */
    static Arc arcForTest(int botId, int playerId, long until) {
        Arc a = new Arc(botId, playerId, null, until);
        arcs.put(playerId, a);
        return a;
    }

    static boolean arcOpen(int playerId) {
        return arcs.containsKey(playerId);
    }

    /** Whether a bot is someone's partner (romance) - a partner is never pulled into a KS war. */
    static final class BotRomanceFacade {
        static boolean partnerOfAnyone(int botCharId) {
            return BotRomance.ofBot(botCharId) != null;
        }
    }

    static void logDebug(String what, Throwable e) {
        log.debug(what, e);
    }
}
