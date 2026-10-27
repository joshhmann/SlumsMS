package soloMapling.worldevents;

import client.Character;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPersonality;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands;
import soloMapling.ArtificialPlayer.BotGrindSystem.TrainingRegions;
import soloMapling.ArtificialPlayer.BotTravelSystem.BotMapGates;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.ConversationManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static soloMapling.BotLogger.log;

/**
 * How the bot population reacts to a world event (ticket 21, spec "Bot reactions").
 * <ul>
 *   <li><b>Gather:</b> training bots in the event's region whose level fits and whose personality leans to grinding
 *   drop what they are doing and head to the map (up to the event's crowd cap). Committed bots stay put: the same
 *   test the population director uses before a logout (open trade or invite, a conversation, a party with a real
 *   player, following someone). Bots never win anything and never get event rewards.</li>
 *   <li><b>Chatter:</b> while it runs, now and then a bot on a map a real player can see says a line about it; after
 *   it ends, a few more lines for a while. All of it is bot map chat, so {@code @quiet bots} hides it.</li>
 *   <li><b>Megaphones:</b> sometimes one bot shouts about the event when it starts, and congratulates a real player
 *   who won it - through {@link WorldEventMegaphones}, so through {@code BotMegaphoneGate}, its cooldowns and
 *   {@code @quiet megaphones}.</li>
 *   <li><b>Memory:</b> a real player who wins becomes a note for the bots that were there; {@code BotGossip} spreads it.</li>
 * </ul>
 * When the event ends the bots are released and carry on with their session.
 * <p>
 * 1.6.0: with the bot brain on, a fitting bot is <i>offered</i> the event (the brain's EVENT activity, leaned by its
 * Bartle type through {@link WorldEventBias}: Killers and Achievers rush invasions and bosses, Socializers come to the
 * cake, Explorers hunt treasure) and goes only if its brain picks it; without the brain it rolls the same lean once.
 * The real player who wins (most damage on the boss) becomes an S3 memory episode for the bots that were there
 * ({@code BotEpisodes}); the event itself becomes S4 world news ({@code BotWorldGossip}: "remember the cake lol");
 * bots that died grumble after they respawn and brag about their death count afterwards.
 */
public final class WorldEventReactions {

    static final String PACK = "WorldEventDialogue.yaml";
    static final String ROOT = "WorldEvent";
    /** How long bots keep talking about an event after it ends. */
    static final long AFTERMATH_MS = 20 * 60_000L;
    static final double START_MEGAPHONE_CHANCE = 0.6;
    static final int MEMORY_WITNESSES = 8;

    private WorldEventReactions() {
    }

    // ── who goes ─────────────────────────────────────────────────────────────────────────────────

    /**
     * The chance a free, fitting bot answers the call, from its personality: grinders mostly go, social players
     * sometimes, town loiterers rarely. A bot without stored traits goes half the time.
     */
    static double joinChance(BotPersonality.Traits traits) {
        if (traits == null) {
            return 0.5;
        }
        double total = traits.grind() + traits.town() + traits.social();
        if (total <= 0) {
            return 0.5;
        }
        double lean = (traits.grind() + 0.5 * traits.social()) / total;
        return Math.max(0.1, Math.min(0.9, 0.15 + 0.75 * lean));
    }

    static boolean levelFits(int level, int min, int max) {
        return level >= min && level <= max;
    }

    /** A region key for a map: its TrainingRegions region, else its continent-sized id block. */
    static String regionKey(int mapId) {
        TrainingRegions.Region r = TrainingRegions.regionOf(mapId);
        return r != null ? r.name() : "block" + (mapId / 10_000_000);
    }

    static String regionName(int mapId) {
        TrainingRegions.Region r = TrainingRegions.regionOf(mapId);
        return r != null ? r.name() : "the world";
    }

    /**
     * 1.6.0 (owner, TEST 2026-10-27: "there should be madness"): the share of the training bots an invasion calls that
     * go at once instead of being offered it (the brain usually turned an invasion down: 0 of 76 at the start).
     */
    static final double INVASION_DIRECT_SHARE = 0.75;
    /** Arrivals stop this many bots below the map's cap (an invasion's: WORLD_EVENT_MAP_MAX, 200), leaving room. */
    static final int MAP_RESERVE = 5;
    /** How far (portal hops) "close by" reaches: a bot on the event map goes first, then the next maps, then the rest. */
    static final int NEAR_HOPS = 2;
    static final int FAR_TIER = NEAR_HOPS + 1;

    /** Test seam: whether this training bot's brain decides for itself (the brain on, the bot running it). */
    static java.util.function.Predicate<TrainingBot> brain = WorldEventReactions::brainDecides;

    /** Pure: whether a called bot goes at once (an invasion's share) rather than through its brain's offer. */
    static boolean goesDirectly(EventTemplate.Kind kind, double roll) {
        return kind == EventTemplate.Kind.INVASION && roll < INVASION_DIRECT_SHARE;
    }

    /** Pure: how close a bot is to the event: 0 on its map, 1..{@link #NEAR_HOPS} portal hops, else far. */
    static int tier(int botMapId, int eventMapId, Map<Integer, Integer> hops) {
        if (botMapId == eventMapId) {
            return 0;
        }
        Integer h = hops == null ? null : hops.get(botMapId);
        return h != null && h > 0 && h <= NEAR_HOPS ? h : FAR_TIER;
    }

    /**
     * Pure: how many more bots may walk onto the event map: the map's cap less {@link #MAP_RESERVE}, less the bots
     * on it and those already on their way. A cap of 0 or less is no cap.
     */
    static int mapRoom(int mapCap, int botsOnMap, int enRoute) {
        if (mapCap <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, mapCap - MAP_RESERVE - botsOnMap - enRoute);
    }

    /**
     * Calls fitting bots to the event, up to its cap. Returns how many new bots answered (or were offered it).
     * <p>
     * 1.6.0 madness: an invasion calls the bots closest to it first (on the map, then the next maps), sends
     * {@link #INVASION_DIRECT_SHARE} of them at once and offers the rest; arrivals stop {@link #MAP_RESERVE} under
     * the map's bot cap. The town bots already standing on the map are {@link InvasionTownCrowd}'s.
     */
    static int recruit(ActiveEvent e, Random rng) {
        if (!e.template.kind().botsGather() || e.finished) {
            return 0;
        }
        int cap = WorldEvents.crowdCap(e.template);
        int room = cap - e.joinedBots.size();
        if (room <= 0) {
            return 0;
        }
        String region = regionKey(e.mapId());
        long now = System.currentTimeMillis();
        List<TrainingBot> pool = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            if (!(bot instanceof TrainingBot tb)) {
                continue;
            }
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null || e.joinedBots.contains(chr.getId())
                    || WorldEventSteer.isSteered(chr.getId()) || WorldEventSteer.offerFor(chr.getId(), now) != null
                    || CharacterStorage.checkIfInvisibleBot(chr.getId())
                    || chr.getClient().getChannel() != e.channel) { // ticket 57: only bots on the event's channel
                continue;
            }
            // owner QA 2026-10-27 ("all bots should participate in the event in the town"): a grinder already
            // standing on an invaded map joins whatever its level
            boolean here = e.template.kind() == EventTemplate.Kind.INVASION && chr.getMapId() == e.mapId();
            if (!here && !levelFits(chr.getLevel(), e.minLevel, e.maxLevel) || !region.equals(regionKey(chr.getMapId()))
                    || !BotMapGates.mayEnter(e.mapId(), BotMapGates.rebirthsOf(chr))) {
                continue;
            }
            if (!BotPopulationDirector.mayLogOut(bot)) {
                continue; // committed to a player: stays put
            }
            pool.add(tb);
        }
        Map<Integer, Integer> hops = Map.of();
        if (e.template.kind() == EventTemplate.Kind.INVASION) {
            try {
                hops = GCMovement.mapsWithinHopsByDepth(e.mapId(), NEAR_HOPS);
            } catch (RuntimeException | LinkageError ex) {
                hops = Map.of(); // no world graph (tests): everyone is far
            }
        }
        int[] took = take(e, pool, rng, room, mapRoomNow(e), hops, now);
        int joined = took[0];
        int offered = took[1];
        if (joined > 0 || offered > 0) {
            log("[WorldEvents] #" + e.id + " " + joined + " bots head to " + e.mapName + ", " + offered
                    + " offered it (" + e.joinedBots.size() + "/" + cap + ")");
            WorldEvents.qa("WEVENT RECRUIT #" + e.id + " " + joined + " bots head to " + e.mapId() + ", " + offered
                    + " offered it (" + e.joinedBots.size() + "/" + cap + ", lv " + e.minLevel + "-" + e.maxLevel
                    + ")");
        }
        return joined + offered;
    }

    /** The event map's room for arrivals now ({@link #mapRoom}); unlimited when the map is not loaded. */
    private static int mapRoomNow(ActiveEvent e) {
        try {
            server.maps.MapleMap map = WorldEvents.mapOf(e.mapId());
            if (map == null) {
                return Integer.MAX_VALUE;
            }
            int enRoute = 0;
            for (int id : e.joinedBots) {
                BotSM bot = CharacterStorage.getBotById(id);
                if (bot != null && bot.getChr() != null && bot.getChr().getMapId() != e.mapId()) {
                    enRoute++;
                }
            }
            return mapRoom(soloMapling.ArtificialPlayer.BotMapCap.capOn(e.channel, e.mapId()), map.getBotCount(),
                    enRoute);
        } catch (RuntimeException | LinkageError ex) {
            return Integer.MAX_VALUE;
        }
    }

    /**
     * The calling itself, over a pool of free fitting bots: closest first (shuffled within a tier), at most
     * {@code room} joins and {@code mapRoom} walk-ons. Returns {joined, offered}.
     */
    static int[] take(ActiveEvent e, List<TrainingBot> pool, Random rng, int room, int mapRoom,
                      Map<Integer, Integer> hops, long now) {
        Collections.shuffle(pool, rng);
        pool.sort(java.util.Comparator.comparingInt(tb -> tier(tb.getChr().getMapId(), e.mapId(), hops)));
        int joined = 0;
        int offered = 0;
        for (TrainingBot tb : pool) {
            if (joined >= room || offered >= 2 * room) {
                break;
            }
            Character chr = tb.getChr();
            boolean walksOn = chr.getMapId() != e.mapId();
            if (walksOn && mapRoom <= 0) {
                continue; // the map is as full as it may get: only bots already there may still join
            }
            boolean direct = goesDirectly(e.template.kind(), rng.nextDouble());
            if (!direct && brain.test(tb)) {
                // 1.6.0: the bot's brain weighs EVENT against what it is doing; it goes only if it picks it
                WorldEventSteer.offer(chr.getId(), e.id, e.mapId(), e.template.kind(), now);
                tb.lifeRuntime().changed();
                tb.nudgeSoon(500 + rng.nextInt(4_000));
                offered++;
                continue;
            }
            if (!direct && rng.nextDouble() >= WorldEventBias.joinChance(e.template.kind(), socialOf(chr.getId()))) {
                continue;
            }
            soloMapling.ArtificialPlayer.BotTownSystem.AfkCampers.endCamp(chr.getId(), "world event");
            WorldEventSteer.steer(chr.getId(), e.id, e.mapId());
            e.joinedBots.add(chr.getId());
            tb.callToEvent();
            tb.nudgeSoon(300 + rng.nextInt(2_500)); // a grinder offscreen ticks every 60-120 s: answer now
            joined++;
            if (walksOn) {
                mapRoom--;
            }
        }
        return new int[]{joined, offered};
    }

    /** With the brain on, a training bot decides for itself (the EVENT activity). */
    private static boolean brainDecides(TrainingBot tb) {
        return soloMapling.ArtificialPlayer.BotLifeSystem.LifeRuntime.enabled() && tb.lifeRuntime() != null
                && TrainingBot.lifeExecutionEnabled();
    }

    /** The bot's point on Bartle's chart: its brain's (drifted) one, else its seed. */
    static soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile socialOf(int charId) {
        try {
            if (CharacterStorage.getBotById(charId) instanceof soloMapling.ArtificialPlayer.BotLifeSystem.LifeHost host
                    && host.lifeRuntime() != null) {
                return host.lifeRuntime().memory().social();
            }
            return soloMapling.ArtificialPlayer.BotLifeSystem.SocialProfile.forCharacter(charId);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** "achiever" etc., for logs. */
    static String typeOf(int charId) {
        var social = socialOf(charId);
        return social == null ? "?" : social.leading().name().toLowerCase(java.util.Locale.ROOT);
    }

    static void release(ActiveEvent e) {
        WorldEventSteer.release(e.id);
        for (int id : e.joinedBots) {
            if (CharacterStorage.getBotById(id) instanceof TrainingBot tb) {
                tb.eventOver(e.mapId()); // a town with no mobs of its own: they settle in town and decide
            }
        }
    }

    /** What a bot says as it heads off to an event its brain picked ("omw to the cake!!"); null to stay quiet. */
    public static String omwLine(Character chr) {
        if (chr == null) {
            return null;
        }
        int map = WorldEventSteer.mapFor(chr.getId());
        ActiveEvent e = null;
        for (ActiveEvent a : WorldEvents.getInstance().active()) {
            if (a.mapId() == map) {
                e = a;
            }
        }
        if (e == null) {
            return "omw";
        }
        String[] lines = switch (e.template.kind()) {
            case GIANT_CAKE -> new String[]{"omw to the cake!!", "cake in " + e.mapName.toLowerCase() + "?? omw",
                    "brb getting cake"};
            case INVASION -> new String[]{"omw to " + e.mapName.toLowerCase() + ", invasion", "brb gotta defend "
                    + e.mapName.toLowerCase(), "invasion?? omw"};
            case BOSS_SIGHTING -> new String[]{"going for " + e.subject.toLowerCase(), "omw to " + e.subject.toLowerCase()};
            case TREASURE_HUNT -> new String[]{"gonna look for the treasure", "brb treasure hunting"};
            case HOT_SPOT -> new String[]{"bonus exp? omw", "omw to the hot spot"};
            default -> new String[]{"omw"};
        };
        return lines[java.util.concurrent.ThreadLocalRandom.current().nextInt(lines.length)];
    }

    /** A map's name, or its id. */
    static String mapName(int mapId) {
        try {
            String n = server.maps.MapFactory.loadPlaceName(mapId);
            if (n == null || n.isBlank()) {
                n = server.maps.MapFactory.loadStreetName(mapId);
            }
            return n == null || n.isBlank() ? String.valueOf(mapId) : n;
        } catch (RuntimeException | LinkageError e) {
            return String.valueOf(mapId);
        }
    }

    // ── what they say ────────────────────────────────────────────────────────────────────────────

    static String nodePrefix(EventTemplate.Kind kind) {
        return switch (kind) {
            case INVASION -> "Invasion";
            case BOSS_SIGHTING -> "Boss";
            case TREASURE_HUNT -> "Treasure";
            case HOT_SPOT -> "HotSpot";
            case WANDERING_NPC -> "Wanderer";
            case WEDDING -> "Wedding";
            case GIANT_CAKE -> "Cake";
        };
    }

    static Map<String, String> tokens(ActiveEvent e) { // mutable: callers may add {N}
        Map<String, String> t = new HashMap<>();
        t.put("MAP", e.mapName);
        t.put("EVENT", e.template.name());
        t.put("CH", String.valueOf(e.channel));
        t.put("REGION", e.regionName);
        if (e.subject != null && !e.subject.isBlank()) {
            t.put("BOSS", e.subject);
            t.put("ITEM", e.subject);
        }
        if (e.npcName != null && !e.npcName.isBlank()) {
            t.put("NPC", e.npcName);
        }
        if (e.winnerName != null) {
            t.put("PLAYER", e.winnerName);
        }
        return t;
    }

    /** One line about a running event from a bot a real player can see; at most one per event per chatter gap. */
    static void chatter(ActiveEvent e, long now, Random rng, boolean aftermath) {
        long gap = Math.max(20, Long.getLong("solomapling.worldEvents.chatterSeconds", 90)) * 1000L;
        if (aftermath) {
            gap *= 3;
        }
        if (now - e.lastChatterAt < gap) {
            return;
        }
        e.lastChatterAt = now - (long) (rng.nextDouble() * gap * 0.3); // a little jitter
        Character speaker = pickSpeaker(e, rng, !aftermath);
        if (speaker == null) {
            return;
        }
        String node = nodePrefix(e.template.kind()) + (aftermath ? "After" : "During");
        Map<String, String> tokens = tokens(e);
        Integer died = e.deaths.get(speaker.getId());
        if (aftermath && died != null && died > 1 && rng.nextDouble() < 0.6) {
            node = "DeathsAfter"; // 1.6.0: "died 6 times to that boss lol"
            tokens.put("N", String.valueOf(died));
        }
        String line = pickLine(node, tokens, rng);
        if (line == null) {
            return;
        }
        String said = line;
        WorldEvents.qa("WEVENT CHATTER #" + e.id + " " + speaker.getName() + " (" + node + "): " + said);
        ExecutorServiceManager.runAsync(() -> SocialCommands.BotSpeak(speaker, said));
    }

    /**
     * 1.6.0: a bot that died at an event says something once it is back on its feet ("ugh died again"), now and then
     * and at most once every 15 s per event, where a real player can hear it.
     */
    static void grumble(ActiveEvent e, Character bot, int deaths, long now, Random rng) {
        if (now - e.lastGrumbleAt < 15_000L || rng.nextDouble() >= 0.55) {
            return;
        }
        e.lastGrumbleAt = now;
        Map<String, String> tokens = tokens(e);
        tokens.put("N", String.valueOf(deaths));
        String line = pickLine(deaths > 1 ? "DiedAgain" : "Died", tokens, rng);
        if (line == null) {
            return;
        }
        long delay = 12_000 + rng.nextInt(6_000);
        WorldEvents.qa("WEVENT GRUMBLE #" + e.id + " " + bot.getName() + " (death " + deaths + "): " + line);
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> {
            if (bot.getMap() != null && GCMovement.isMapObserved(bot.getMapId())) {
                SocialCommands.BotSpeak(bot, line);
            }
        }, delay, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * {@code !qa ev-1}: right now, a bot on the GM's map (one that was at the event when it can) says one after-line
     * about it and one world-news line ("remember the cake lol"). Returns what was said.
     */
    public static List<String> qaAftermath(ActiveEvent e, Character near, Random rng) {
        List<Character> here = new ArrayList<>();
        List<Character> were = new ArrayList<>();
        if (near == null || near.getMap() == null) {
            return List.of();
        }
        for (Character chr : near.getMap().getAllPlayers()) {
            if (BotHelpers.isBot(chr) && free(chr)) {
                here.add(chr);
                if (e.joinedBots.contains(chr.getId())) {
                    were.add(chr);
                }
            }
        }
        List<Character> from = were.isEmpty() ? here : were;
        if (from.isEmpty()) {
            return List.of();
        }
        Character speaker = from.get(rng.nextInt(from.size()));
        List<String> out = new ArrayList<>();
        Map<String, String> tokens = tokens(e);
        Integer died = e.deaths.get(speaker.getId());
        String node = died != null && died > 1 ? "DeathsAfter" : nodePrefix(e.template.kind()) + "After";
        if (died != null) {
            tokens.put("N", String.valueOf(died));
        }
        String after = pickLine(node, tokens, rng);
        if (after != null) {
            out.add(speaker.getName() + " (" + node + "): " + after);
            SocialCommands.BotSpeak(speaker, after);
        }
        long now = System.currentTimeMillis();
        for (var n : soloMapling.ArtificialPlayer.BotWorldGossip.recent(now)) {
            if (n.kind == soloMapling.ArtificialPlayer.BotWorldGossip.Kind.EVENT && n.what.equals(newsName(e))) {
                String line = soloMapling.ArtificialPlayer.BotWorldGossip.line(n, n.witnessedBy(speaker.getId())
                        || e.joinedBots.contains(speaker.getId()), "", rng);
                if (line != null) {
                    out.add(speaker.getName() + " (news): " + line);
                    String said = line;
                    ExecutorServiceManager.getScheduledExecutorService().schedule(
                            () -> SocialCommands.BotSpeak(speaker, said), 3, java.util.concurrent.TimeUnit.SECONDS);
                }
                break;
            }
        }
        return out;
    }

    /** 1.6.0: the headline's warning reaches the bots: a megaphone (through the gate) and a line where players are. */
    static void warned(EventTemplate t, int mapId, String mapName, int minutesLeft, Random rng) {
        Map<String, String> tokens = new HashMap<>();
        tokens.put("EVENT", t.name());
        tokens.put("MAP", mapName);
        tokens.put("MIN", String.valueOf(minutesLeft));
        tokens.put("CH", String.valueOf(soloMapling.server.SoloMaplingConstants.mainChannel().getId()));
        List<Character> heard = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr != null && chr.getMap() != null && free(chr) && GCMovement.isMapObserved(chr.getMapId())) {
                heard.add(chr);
            }
        }
        if (!heard.isEmpty()) {
            Character speaker = heard.get(rng.nextInt(heard.size()));
            String line = pickLine("Soon", tokens, rng);
            if (line != null) {
                WorldEvents.qa("WEVENT CHATTER warn " + speaker.getName() + ": " + line);
                ExecutorServiceManager.runAsync(() -> SocialCommands.BotSpeak(speaker, line));
            }
            if (minutesLeft >= 5 && rng.nextDouble() < START_MEGAPHONE_CHANCE) {
                WorldEventMegaphones.getInstance().offerEvent(heard.get(rng.nextInt(heard.size())), "WorldEventSoon",
                        tokens, "wevent:soon:" + t.id() + ":" + mapId);
            }
        }
    }

    /** A free bot on a map a real player is on: on the event map first, else anywhere in the region. */
    private static Character pickSpeaker(ActiveEvent e, Random rng, boolean preferEventMap) {
        String region = regionKey(e.mapId());
        List<Character> onMap = new ArrayList<>();
        List<Character> inRegion = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null || !free(chr) || !GCMovement.isMapObserved(chr.getMapId())
                    || chr.getClient().getChannel() != e.channel) { // ticket 57: speak where the event is
                continue;
            }
            if (chr.getMapId() == e.mapId()) {
                onMap.add(chr);
            } else if (region.equals(regionKey(chr.getMapId()))) {
                inRegion.add(chr);
            }
        }
        List<Character> from = preferEventMap && !onMap.isEmpty() ? onMap : inRegion.isEmpty() ? onMap : inRegion;
        return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
    }

    private static boolean free(Character chr) {
        return BotHelpers.isBot(chr) && !CharacterStorage.checkIfInvisibleBot(chr.getId())
                && !ConversationManager.getInstance().isInConversation(chr.getId());
    }

    static String pickLine(String node, Map<String, String> tokens, Random rng) {
        BotDialogueHandler.DialogueConstructor dialog = BotDialogueHandler.getDialogueCon(PACK, ROOT, node);
        if (dialog == null || dialog.getDialogue() == null || dialog.getDialogue().isEmpty()) {
            return null;
        }
        List<String> lines = new ArrayList<>(dialog.getDialogue());
        Collections.shuffle(lines, rng);
        for (String line : lines) {
            String filled = fillAll(line, tokens);
            if (filled != null) {
                return filled;
            }
        }
        return null;
    }

    /** Fills every {TOKEN}; null when a token has no value (that line is skipped). */
    static String fillAll(String line, Map<String, String> tokens) {
        String out = line;
        for (Map.Entry<String, String> t : tokens.entrySet()) {
            if (t.getValue() != null && !t.getValue().isBlank()) {
                out = out.replace("{" + t.getKey() + "}", t.getValue());
            }
        }
        return out.matches(".*\\{[A-Z_]+}.*") ? null : out;
    }

    // ── megaphones ───────────────────────────────────────────────────────────────────────────────

    static void megaphoneStart(ActiveEvent e, Random rng) {
        if (rng.nextDouble() >= START_MEGAPHONE_CHANCE) {
            return;
        }
        Character speaker = anyFreeBot(e, rng);
        if (speaker != null) {
            WorldEventMegaphones.getInstance().offerEvent(speaker, "WorldEvent" + nodePrefix(e.template.kind()),
                    tokens(e), "wevent:start:" + e.id);
        }
    }

    static void megaphoneWon(ActiveEvent e, Random rng) {
        if (e.winnerName == null) {
            return;
        }
        Character speaker = null;
        List<Character> here = new ArrayList<>();
        for (int id : e.joinedBots) {
            BotSM bot = CharacterStorage.getBotById(id);
            if (bot != null && bot.getChr() != null && bot.getChr().getMapId() == e.mapId() && free(bot.getChr())) {
                here.add(bot.getChr());
            }
        }
        if (!here.isEmpty()) {
            speaker = here.get(rng.nextInt(here.size()));
        }
        if (speaker == null) {
            speaker = anyFreeBot(e, rng);
        }
        if (speaker != null) {
            WorldEventMegaphones.getInstance().offerEvent(speaker, "WorldEventWon", tokens(e), "wevent:won:" + e.id);
        }
    }

    private static Character anyFreeBot(ActiveEvent e, Random rng) {
        String region = regionKey(e.mapId());
        List<Character> near = new ArrayList<>();
        List<Character> any = new ArrayList<>();
        for (BotSM bot : CharacterStorage.getAllBots().values()) {
            Character chr = bot.getChr();
            if (chr == null || chr.getMap() == null || !free(chr) || CharacterStorage.getBotById(chr.getId()) == null) {
                continue;
            }
            any.add(chr);
            if (region.equals(regionKey(chr.getMapId()))) {
                near.add(chr);
            }
        }
        List<Character> from = near.isEmpty() ? any : near;
        return from.isEmpty() ? null : from.get(rng.nextInt(from.size()));
    }

    // ── memory ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The bots that were there remember the real player who won; gossip carries it further. 1.6.0 S3: written as an
     * episode ({@code BotEpisodes.worldEvent}, kind BOSS), so the memory card and follow-ups see it.
     */
    static void rememberWinner(ActiveEvent e, server.maps.MapleMap map, long now) {
        if (e.winnerId <= 0 || e.winnerName == null) {
            return;
        }
        List<Integer> witnesses = new ArrayList<>();
        for (int id : e.joinedBots) {
            BotSM bot = CharacterStorage.getBotById(id);
            if (bot != null && bot.getChr() != null && bot.getChr().getMapId() == e.mapId()
                    && bot.getChr().getClient().getChannel() == e.channel) {
                witnesses.add(id);
            }
        }
        if (map != null) {
            for (Character chr : map.getAllPlayers()) {
                if (BotHelpers.isBot(chr) && !witnesses.contains(chr.getId())) {
                    witnesses.add(chr.getId());
                }
            }
        }
        if (witnesses.isEmpty()) {
            return;
        }
        String note = noteText(e);
        List<Integer> chosen = witnesses.subList(0, Math.min(MEMORY_WITNESSES, witnesses.size()));
        int weight = e.headline ? 7 : 5;
        for (int charId : List.copyOf(chosen)) {
            soloMapling.ArtificialPlayer.BotEpisodes.worldEvent(charId, e.winnerId, note, weight, now);
        }
        WorldEvents.qa("WEVENT MEMORY #" + e.id + " " + chosen.size() + " bots remember " + e.winnerName + ": \""
                + note + "\"");
    }

    /** 1.6.0 S4: the event goes into the world news, so bots bring it up for hours ("remember the cake lol"). */
    static void news(ActiveEvent e, server.maps.MapleMap map) {
        if (!(e.template.bossFight() || e.headline) || e.template.kind() == EventTemplate.Kind.WEDDING) {
            return;
        }
        List<Integer> witnesses = new ArrayList<>();
        for (int id : e.joinedBots) {
            if (witnesses.size() >= 6) {
                break;
            }
            witnesses.add(id);
        }
        String what = newsName(e);
        soloMapling.ArtificialPlayer.BotWorldGossip.onWorldEvent(what, e.winnerName, e.winnerId, e.botDeaths(),
                witnesses, e.endedAt);
        WorldEvents.qa("WEVENT NEWS #" + e.id + " \"" + what + "\"" + (e.winnerName != null ? " won by " + e.winnerName
                : "") + ", " + witnesses.size() + " witnesses");
    }

    /** "the giant cake in henesys", "the henesys invasion". */
    static String newsName(ActiveEvent e) {
        String map = e.mapName == null ? "" : e.mapName.toLowerCase(java.util.Locale.ROOT);
        return switch (e.template.kind()) {
            case GIANT_CAKE -> "the giant cake in " + map;
            case INVASION -> "the invasion at " + map;
            case BOSS_SIGHTING -> (e.subject == null || e.subject.isBlank() ? "the boss" : e.subject) + " at " + map;
            default -> e.template.name().toLowerCase(java.util.Locale.ROOT) + " at " + map;
        };
    }

    static String noteText(ActiveEvent e) {
        return switch (e.template.kind()) {
            case INVASION -> "beat " + e.subject + " in the " + e.template.name().toLowerCase() + " at " + e.mapName;
            case BOSS_SIGHTING -> "killed " + e.subject + " at " + e.mapName + " when it showed up";
            case TREASURE_HUNT -> "found the hidden treasure chest at " + e.mapName + " first";
            case WANDERING_NPC -> "helped " + e.npcName + " in " + e.mapName + " first";
            case HOT_SPOT -> "trained at the hot spot on " + e.mapName;
            case WEDDING -> "came to the wedding of " + e.subject + " in " + e.mapName;
            case GIANT_CAKE -> "got the biggest slice of the giant cake in " + e.mapName;
        };
    }
}
