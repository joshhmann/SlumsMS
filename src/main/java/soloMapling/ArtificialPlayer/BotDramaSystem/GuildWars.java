package soloMapling.ArtificialPlayer.BotDramaSystem;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGuildSystem.BotGuilds;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.BotDramaSystem.GuildWarStore.Kind;
import soloMapling.ArtificialPlayer.BotDramaSystem.GuildWarStore.State;
import soloMapling.ArtificialPlayer.BotDramaSystem.GuildWarStore.War;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * 1.5.0 bot drama, part 3: spicy guilds - wars, turf, poaching, alliances and betrayals. Built on the bot guilds
 * ({@code BotGuilds}); everything world-level is rare and megaphones go through the megaphone gate.
 *
 * <ul>
 *   <li><b>Bot guild wars:</b> a rival pair of bot guilds declares war now and then (about {@code warsPerDay} 0.5 per
 *   pair, two at a time) with war megaphones. For a week ({@code warDays}) every minute counts turf: on each grind map
 *   the side with more members standing there holds it and scores. A side sends a grinding member to a map the other
 *   holds now and then (turf fights), and posts a taunt megaphone every few hours. At the end the winner gloats.</li>
 *   <li><b>War on a player guild:</b> a bot guild whose rivals are half or more of a player guild's online members
 *   declares war on it (players get a notice; {@code @guildwar} shows the score). It ends after a week, or earlier with
 *   {@code @guildwar peace} by the guild's master or a jr. master. Players with {@code @botdrama off} are never counted,
 *   named, jabbed or notified, and a guild whose online members all opted out is never declared on.</li>
 *   <li><b>Poaching:</b> a bot guild lures a member from its rival (drama lines in both guild chats, sometimes a
 *   megaphone), and now and then whispers a player in a player guild who is a friend of one of its members (a real
 *   invite follows if the player leaves their guild within 15 minutes).</li>
 *   <li><b>Alliances and betrayals:</b> two friendly bot guilds ally (shout-outs in guild chat, joint staged boss runs);
 *   now and then one betrays the other (a betrayal megaphone) and they go to war.</li>
 * </ul>
 */
public final class GuildWars {

    private static final Logger log = LoggerFactory.getLogger(GuildWars.class);

    private static volatile GuildWarStore store = new GuildWarStore.Jdbc();
    private static final Map<Long, War> wars = new ConcurrentHashMap<>();
    /** War id -> map id -> the guild holding it (this minute). */
    private static final Map<Long, Map<Integer, Integer>> held = new ConcurrentHashMap<>();
    /** A player whispered a poach offer: player id -> {guild id, bot id, until}. */
    private static final Map<Integer, long[]> poachOffers = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong memoryIds = new java.util.concurrent.atomic.AtomicLong();
    private static volatile boolean loaded;
    private static volatile long loadTriedAt;
    private static volatile long lastStepAt;
    private static volatile long lastSaveAt;
    private static volatile LongSupplier clock = System::currentTimeMillis;

    private GuildWars() {
    }

    public static boolean enabled() {
        return DramaKit.enabled() && !"false".equalsIgnoreCase(System.getProperty("solomapling.drama.wars", "true"))
                && BotGuilds.enabled();
    }

    static long now() {
        return clock.getAsLong();
    }

    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (GuildWars.class) {
            long now = now();
            if (loaded || (loadTriedAt > 0 && now - loadTriedAt < TimeUnit.MINUTES.toMillis(1))) {
                return;
            }
            loadTriedAt = now;
            List<War> all;
            try {
                all = store.loadRecent(now - TimeUnit.DAYS.toMillis(14));
            } catch (RuntimeException e) {
                all = null;
            }
            if (all == null) {
                log.warn("Guild wars could not be read; trying again in a minute");
                return;
            }
            for (War w : all) {
                wars.put(w.id(), w);
            }
            loaded = true;
        }
    }

    // ── queries ──

    public static List<War> active() {
        ensureLoaded();
        List<War> out = new ArrayList<>();
        for (War w : wars.values()) {
            if (w.active()) {
                out.add(w);
            }
        }
        out.sort((x, y) -> Long.compare(x.startedAt(), y.startedAt()));
        return out;
    }

    static War activeWar(int guildA, int guildB) {
        for (War w : wars.values()) {
            if (w.active() && w.kind() == Kind.WAR && w.involves(guildA) && w.involves(guildB)) {
                return w;
            }
        }
        return null;
    }

    static List<War> warsOf(int guildId) {
        List<War> out = new ArrayList<>();
        for (War w : wars.values()) {
            if (w.active() && w.kind() == Kind.WAR && w.involves(guildId)) {
                out.add(w);
            }
        }
        return out;
    }

    /** The bot guilds allied with this one now (joint boss runs). */
    public static List<Integer> alliesOf(int guildId) {
        List<Integer> out = new ArrayList<>();
        if (!enabled()) {
            return out;
        }
        for (War w : wars.values()) {
            if (w.active() && w.kind() == Kind.ALLIANCE && w.involves(guildId)) {
                out.add(w.other(guildId));
            }
        }
        return out;
    }

    /** When the last war or alliance between the two ended (0 = never). */
    private static long lastEnded(int a, int b, Kind kind) {
        long last = 0;
        for (War w : wars.values()) {
            if (w.kind() == kind && w.involves(a) && w.involves(b) && !w.active()) {
                last = Math.max(last, w.endedAt());
            }
        }
        return last;
    }

    // ── starting and ending ──

    private static War open(Kind kind, int a, int b, boolean bPlayer, boolean staged) {
        long now = now();
        War w = new War(0, kind, a, b, bPlayer, State.ACTIVE, now, kind == Kind.WAR ? now + GuildWarRules.warMs() : 0,
                0, 0, 0, staged);
        long id = staged ? 0 : store.insert(w);
        if (id <= 0) {
            id = memoryIds.decrementAndGet();
        }
        w = w.withId(id);
        wars.put(id, w);
        return w;
    }

    private static void save(War w) {
        wars.put(w.id(), w);
        if (!w.staged() && w.id() > 0) {
            War copy = w;
            ExecutorServiceManager.runAsync(() -> store.update(copy));
        }
    }

    /** Bot guild {@code a} declares war on bot guild {@code b}: megaphones and guild chat. The war, or null. */
    public static War declare(GuildRow a, GuildRow b, String by) {
        ensureLoaded();
        if (a == null || b == null || a.guildId() == b.guildId() || activeWar(a.guildId(), b.guildId()) != null) {
            return null;
        }
        endAlliance(a.guildId(), b.guildId(), State.ENDED);
        War w = open(Kind.WAR, a.guildId(), b.guildId(), false, false);
        Character speaker = speaker(a.guildId());
        Map<String, String> t = Map.of("GUILD", a.name(), "RIVAL", b.name());
        String mega = "no member online to megaphone";
        if (speaker != null) {
            mega = WorldEventMegaphones.getInstance().offerEvent(speaker, "GuildWarDeclare", t, "drama:war:" + w.id())
                    ? "megaphone sent" : "megaphone queued/gated";
            BotGuilds.sayInGuild(speaker, DramaKit.pick("WarDeclaredOurs", t));
        }
        Character theirs = speaker(b.guildId());
        if (theirs != null) {
            BotGuilds.sayInGuild(theirs, DramaKit.pick("WarDeclaredTheirs", Map.of("GUILD", b.name(), "RIVAL", a.name())));
        }
        qa(0, "WAR DECLARED " + a.name() + " vs " + b.name() + " (#" + w.id() + ", " + by + ") for "
                + TimeUnit.MILLISECONDS.toDays(GuildWarRules.warMs()) + " days; " + mega);
        return w;
    }

    /**
     * Bot guild {@code a} declares war on a player guild: a megaphone, guild chat, and a notice to the player guild's
     * online members (never those with {@code @botdrama off}). The war, or null.
     */
    public static War declareOnPlayers(GuildRow a, int playerGuildId, String by) {
        ensureLoaded();
        if (a == null || playerGuildId <= 0 || BotGuilds.isBotGuild(playerGuildId)
                || activeWar(a.guildId(), playerGuildId) != null) {
            return null;
        }
        String target = BotGuilds.guildName(playerGuildId);
        War w = open(Kind.WAR, a.guildId(), playerGuildId, true, false);
        Character speaker = speaker(a.guildId());
        Map<String, String> t = Map.of("GUILD", a.name(), "RIVAL", target);
        String mega = "no member online to megaphone";
        if (speaker != null) {
            mega = WorldEventMegaphones.getInstance().offerEvent(speaker, "GuildWarOnPlayers", t, "drama:war:" + w.id())
                    ? "megaphone sent" : "megaphone queued/gated";
            BotGuilds.sayInGuild(speaker, DramaKit.pick("WarDeclaredOurs", t));
        }
        for (Character p : playersOf(playerGuildId)) {
            p.dropMessage(6, "[Guild War] " + a.name() + " declared war on " + target + "! It lasts a week: hold the grind"
                    + " maps with your guildmates. @guildwar shows the score; @guildwar peace (master or jr. master) offers peace.");
        }
        qa(0, "WAR DECLARED " + a.name() + " on player guild " + target + " (#" + w.id() + ", " + by + "); " + mega);
        return w;
    }

    /** A war ends: by time (the winner gloats), by peace, or by betrayal. */
    static War end(War w, State how, String by) {
        if (w == null || !w.active()) {
            return w;
        }
        War done = w.ended(how, now());
        save(done);
        held.remove(w.id());
        String an = BotGuilds.guildName(w.a());
        String bn = BotGuilds.guildName(w.b());
        if (w.kind() == Kind.WAR && how == State.ENDED) {
            int win = GuildWarRules.winner(w.a(), w.scoreA(), w.b(), w.scoreB());
            String score = Math.max(w.scoreA(), w.scoreB()) + "-" + Math.min(w.scoreA(), w.scoreB());
            Character speaker = win != 0 && BotGuilds.isBotGuild(win) ? speaker(win) : speaker(w.a());
            if (speaker != null) {
                Map<String, String> t = Map.of("GUILD", win == 0 ? an : BotGuilds.guildName(win), "RIVAL",
                        win == 0 ? bn : BotGuilds.guildName(win == w.a() ? w.b() : w.a()), "SCORE", score);
                WorldEventMegaphones.getInstance().offerEvent(speaker, win == 0 ? "GuildWarDraw" : "GuildWarWon", t,
                        "drama:warend:" + w.id());
            }
            if (w.bPlayer()) {
                for (Character p : playersOf(w.b())) {
                    p.dropMessage(6, "[Guild War] The war with " + an + " is over: " + an + " " + w.scoreA() + " - "
                            + w.scoreB() + " " + bn + (win == w.b() ? ". Your guild won!" : win == 0 ? ". A draw." : "."));
                }
            }
        }
        qa(0, "WAR OVER " + an + " vs " + bn + " #" + w.id() + ": " + how.label() + " (" + by + "), score "
                + w.scoreA() + "-" + w.scoreB());
        return done;
    }

    /**
     * {@code @guildwar peace}: the master or a jr. master of a player guild at war with a bot guild offers peace; the
     * bot guild takes it (with a salty line) and the war ends now. What happened.
     */
    public static String peace(Character player) {
        ensureLoaded();
        int gid = player.getGuildId();
        if (gid <= 0) {
            return "You are not in a guild.";
        }
        List<War> mine = warsOf(gid);
        if (mine.isEmpty()) {
            return "Your guild is not at war.";
        }
        Guild guild = Server.getInstance().getGuild(gid);
        boolean master = guild != null && guild.getLeaderId() == player.getId();
        if (!GuildWarRules.mayOfferPeace(player.getGuildRank(), master)) {
            return "Only your guild master or a jr. master can offer peace.";
        }
        War w = mine.get(0);
        int botGuild = w.other(gid);
        end(w, State.PEACE, player.getName());
        Character speaker = speaker(botGuild);
        String us = BotGuilds.guildName(botGuild);
        Map<String, String> t = Map.of("GUILD", us, "RIVAL", BotGuilds.guildName(gid), "PLAYER", player.getName());
        if (speaker != null) {
            DramaKit.reply(speaker, player, DramaKit.pick("WarPeaceReply", t)); // answers @guildwar peace
            WorldEventMegaphones.getInstance().offerEvent(speaker, "GuildWarPeace", t, "drama:peace:" + w.id());
        }
        for (Character p : playersOf(gid)) {
            p.dropMessage(6, "[Guild War] " + player.getName() + " made peace with " + us + ". The war is over ("
                    + w.scoreB() + " - " + w.scoreA() + ").");
        }
        return "Peace with " + us + ".";
    }

    // ── the step ──

    /** From the director's tick: at most once a minute, off the tick's thread. */
    static void maybeStep(long now) {
        if (!enabled() || now - lastStepAt < TimeUnit.SECONDS.toMillis(60)) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> {
            try {
                step(now(), ThreadLocalRandom.current());
            } catch (RuntimeException e) {
                log.warn("Guild war step failed", e);
            }
        });
    }

    public static void stepNow() {
        step(now(), ThreadLocalRandom.current());
    }

    static void step(long now, Random rng) {
        ensureLoaded();
        if (!loaded || BotGuilds.botGuilds().isEmpty()) {
            return;
        }
        for (War w : active()) {
            if (w.kind() != Kind.WAR) {
                continue;
            }
            if (now >= w.endsAt()) {
                end(w, State.ENDED, "a week passed");
                continue;
            }
            scoreTurf(w);
            ambience(wars.get(w.id()), now, rng);
        }
        if (now - lastSaveAt > TimeUnit.MINUTES.toMillis(10)) {
            lastSaveAt = now;
            for (War w : active()) {
                save(w);
            }
        }
        maybeDeclare(now, rng);
        maybeDeclareOnPlayers(now, rng);
        maybePoach(now, rng);
        poachInvites(now);
        maybeAlly(now, rng);
        alliances(now, rng);
    }

    /** One minute of turf: who stands where on grind maps; the side with more members on a map holds it. */
    static GuildWarRules.Turf scoreTurf(War w) {
        Map<Integer, int[]> presence = new HashMap<>();
        for (Character c : side(w.a(), false)) {
            count(presence, c, 0);
        }
        for (Character c : side(w.b(), w.bPlayer())) {
            count(presence, c, 1);
        }
        GuildWarRules.Turf turf = GuildWarRules.turf(presence, w.a(), w.b());
        held.put(w.id(), turf.holder());
        if (turf.pointsA() > 0 || turf.pointsB() > 0) {
            wars.put(w.id(), w.scored(w.scoreA() + turf.pointsA(), w.scoreB() + turf.pointsB()));
        }
        return turf;
    }

    private static void count(Map<Integer, int[]> presence, Character c, int side) {
        int map = c.getMapId();
        if (map > 0 && MapMobIndex.level(map) >= 0) {
            presence.computeIfAbsent(map, k -> new int[2])[side]++;
        }
    }

    /** The members of one side standing in the world: bot members, plus real players for a player guild. */
    static List<Character> side(int guildId, boolean playerGuild) {
        List<Character> out = new ArrayList<>(onlineBots(guildId));
        if (playerGuild) {
            out.addAll(playersOf(guildId));
        }
        return out;
    }

    static List<Character> onlineBots(int guildId) {
        List<Character> out = new ArrayList<>();
        for (MemberRow m : BotGuilds.membersOf(guildId)) {
            Character c = DramaKit.liveBot(m.charId());
            if (c != null && c.getGuildId() == guildId) {
                out.add(c);
            }
        }
        return out;
    }

    /** Real players of a guild online now, without those who turned bot drama off. */
    static List<Character> playersOf(int guildId) {
        List<Character> out = new ArrayList<>();
        for (Character p : DramaKit.realPlayers()) {
            if (p.getGuildId() == guildId && !BotAffinity.optedOut(p.getId())) {
                out.add(p);
            }
        }
        return out;
    }

    /** A guild's officer (or any member) online to speak for it. */
    static Character speaker(int guildId) {
        Character best = null;
        for (Character c : onlineBots(guildId)) {
            if (best == null || c.getGuildRank() < best.getGuildRank()) {
                best = c;
            }
        }
        return best;
    }

    /** War megaphone taunts every few hours, and turf fights: a grinding member goes to a map the other side holds. */
    private static void ambience(War w, long now, Random rng) {
        if (w == null) {
            return;
        }
        if (DramaKit.take("gw:taunt:" + w.id(), now, DramaKit.hours("wars.tauntHours", 3)) && rng.nextDouble() < 0.6) {
            taunt(w, rng);
        }
        if (DramaKit.take("gw:turf:" + w.id(), now, DramaKit.minutes("wars.turfMinutes", 30)) && rng.nextDouble() < 0.4) {
            turfFight(w, rng.nextBoolean() || w.bPlayer() ? w.a() : w.b(), rng);
        }
    }

    /** A war megaphone: who holds what, the score. */
    static String taunt(War w, Random rng) {
        boolean bSpeaks = !w.bPlayer() && rng.nextBoolean();
        int us = bSpeaks ? w.b() : w.a();
        int them = bSpeaks ? w.a() : w.b();
        Character speaker = speaker(us);
        if (speaker == null) {
            return "nobody of " + BotGuilds.guildName(us) + " is online";
        }
        String map = null;
        Map<Integer, Integer> h = held.get(w.id());
        if (h != null) {
            for (Map.Entry<Integer, Integer> e : h.entrySet()) {
                if (e.getValue() == us) {
                    map = mapName(e.getKey());
                    break;
                }
            }
        }
        int mine = us == w.a() ? w.scoreA() : w.scoreB();
        int theirs = us == w.a() ? w.scoreB() : w.scoreA();
        Map<String, String> t = new HashMap<>();
        t.put("GUILD", BotGuilds.guildName(us));
        t.put("RIVAL", BotGuilds.guildName(them));
        t.put("SCORE", mine + "-" + theirs);
        if (map != null) {
            t.put("MAP", map);
        }
        boolean sent = WorldEventMegaphones.getInstance().offerEvent(speaker, "GuildWarTaunt", t,
                "drama:wartaunt:" + w.id() + ":" + now());
        qa(speaker.getMapId(), "WAR TAUNT " + speaker.getName() + " [" + t.get("GUILD") + "] " + t.get("SCORE")
                + (map == null ? "" : " holds " + map) + ": " + (sent ? "sent" : "queued/gated"));
        return speaker.getName() + " taunts " + t.get("RIVAL") + " (" + t.get("SCORE") + "): " + (sent ? "sent"
                : "waiting for the gate");
    }

    /**
     * Turf fight: a grinding member of {@code us} goes to grind on a map the other side holds (or, against a player
     * guild, where one of its players grinds), for 20 minutes, and says so where a player sees.
     */
    static String turfFight(War w, int us, Random rng) {
        int them = w.other(us);
        Integer target = null;
        Character targetPlayer = null;
        Map<Integer, Integer> h = held.get(w.id());
        if (h != null) {
            List<Integer> theirs = new ArrayList<>();
            h.forEach((map, holder) -> {
                if (holder == them) {
                    theirs.add(map);
                }
            });
            if (!theirs.isEmpty()) {
                target = theirs.get(rng.nextInt(theirs.size()));
            }
        }
        if (w.bPlayer() && us == w.a()) {
            List<Character> players = playersOf(them);
            players.removeIf(p -> MapMobIndex.level(p.getMapId()) < 0 || p.getEventInstance() != null);
            if (!players.isEmpty()) {
                targetPlayer = players.get(rng.nextInt(players.size()));
                target = targetPlayer.getMapId();
            }
        }
        if (target == null) {
            return "no map held by " + BotGuilds.guildName(them) + " to contest";
        }
        int mobLevel = MapMobIndex.level(target);
        Character fighter = null;
        for (Character c : onlineBots(us)) {
            if (CharacterStorage.getBotById(c.getId()) instanceof TrainingBot && DramaKit.freeForScene(c)
                    && Math.abs(c.getLevel() - mobLevel) <= 25 && c.getMapId() != target) {
                fighter = c;
                break;
            }
        }
        if (fighter == null) {
            return "no free grinder of " + BotGuilds.guildName(us) + " fits map " + target;
        }
        int id = fighter.getId();
        soloMapling.worldevents.WorldEventSteer.hold(id, target);
        BotTiming.after(DramaKit.minutes("wars.turfHoldMinutes", 20), () -> soloMapling.worldevents.WorldEventSteer.unhold(id));
        Character who = targetPlayer;
        if (who != null && !BotAffinity.optedOut(who.getId())) {
            Character f = fighter;
            BotTiming.after(TimeUnit.SECONDS.toMillis(90), () -> {
                if (f.getMapId() == who.getMapId()) {
                    DramaKit.say(f, "WarJab", Map.of("PLAYER", who.getName(), "GUILD", BotGuilds.guildName(us),
                            "MAP", DramaKit.mapName(who)));
                }
            });
        }
        qa(fighter.getMapId(), "TURF " + fighter.getName() + " [" + BotGuilds.guildName(us) + "] goes to contest map "
                + target + " (held by " + BotGuilds.guildName(them) + ")");
        return fighter.getName() + " goes to contest map " + target;
    }

    // ── declaring ──

    private static void maybeDeclare(long now, Random rng) {
        long botWars = active().stream().filter(w -> w.kind() == Kind.WAR && !w.bPlayer()).count();
        if (botWars >= (long) DramaKit.prop("wars.maxBotWars", 2)) {
            return;
        }
        for (GuildRow g : BotGuilds.botGuilds()) {
            GuildRow r = g.rivalId() > 0 ? BotGuilds.guild(g.rivalId()) : null;
            if (r == null || g.guildId() > r.guildId() || activeWar(g.guildId(), r.guildId()) != null
                    || now - lastEnded(g.guildId(), r.guildId(), Kind.WAR) < GuildWarRules.cooldownMs()
                    || onlineBots(g.guildId()).isEmpty() || rng.nextDouble() >= GuildWarRules.declareChancePerMinute()) {
                continue;
            }
            declare(rng.nextBoolean() ? g : r, rng.nextBoolean() ? r : g, "rivals");
            return;
        }
    }

    /**
     * Every half hour per bot guild: a player guild whose online members are mostly this guild's rivals gets a war.
     * {@code force} (GM / !qa) checks now, skipping the half hour and the chance.
     */
    static List<String> maybeDeclareOnPlayers(long now, Random rng) {
        List<String> out = new ArrayList<>();
        for (GuildRow g : BotGuilds.botGuilds()) {
            if (!DramaKit.take("gw:pcheck:" + g.guildId(), now, DramaKit.minutes("wars.playerCheckMinutes", 30))) {
                continue;
            }
            out.addAll(checkPlayerGuilds(g, now, rng, false));
        }
        return out;
    }

    /** One bot guild weighs every player guild with members online. What it decided. */
    public static List<String> checkPlayerGuilds(GuildRow g, long now, Random rng, boolean force) {
        List<String> out = new ArrayList<>();
        Map<Integer, List<Character>> byGuild = new HashMap<>();
        for (Character p : DramaKit.realPlayers()) {
            if (p.getGuildId() > 0 && !BotGuilds.isBotGuild(p.getGuildId()) && !BotAffinity.optedOut(p.getId())) {
                byGuild.computeIfAbsent(p.getGuildId(), k -> new ArrayList<>()).add(p);
            }
        }
        List<MemberRow> ours = BotGuilds.membersOf(g.guildId());
        for (Map.Entry<Integer, List<Character>> e : byGuild.entrySet()) {
            int rivals = 0;
            for (Character p : e.getValue()) {
                for (MemberRow m : ours) {
                    if (BotAffinity.hostile(m.charId(), p.getId())) {
                        rivals++;
                        break;
                    }
                }
            }
            String name = BotGuilds.guildName(e.getKey());
            if (!GuildWarRules.declareOnPlayers(e.getValue().size(), rivals)) {
                out.add(g.name() + " vs " + name + ": no war (" + rivals + " of " + e.getValue().size()
                        + " online members are its rivals)");
                continue;
            }
            if (activeWar(g.guildId(), e.getKey()) != null) {
                out.add(g.name() + " is already at war with " + name);
                continue;
            }
            if (!force && (now - lastEnded(g.guildId(), e.getKey(), Kind.WAR) < GuildWarRules.cooldownMs()
                    || rng.nextDouble() >= DramaKit.prop("wars.playerWarChance", 0.5)
                    || !DramaKit.hourly("gw-playerwar", 2, now))) {
                out.add(g.name() + " vs " + name + ": rivals, but not now (cooldown or chance)");
                continue;
            }
            War w = declareOnPlayers(g, e.getKey(), force ? "gm" : "rivals");
            out.add(w == null ? "no war" : g.name() + " declared war on " + name + " (" + rivals + " of "
                    + e.getValue().size() + " online members are its rivals)");
        }
        if (out.isEmpty()) {
            out.add("no player guild with members online (with @botdrama on)");
        }
        return out;
    }

    // ── poaching ──

    private static void maybePoach(long now, Random rng) {
        for (GuildRow g : BotGuilds.botGuilds()) {
            if (g.rivalId() <= 0 || !DramaKit.take("gw:poach:" + g.guildId(), now, DramaKit.hours("wars.poachHours", 6))
                    || rng.nextDouble() >= DramaKit.prop("wars.poachChance", 0.3)) {
                continue;
            }
            poachBot(g, BotGuilds.guild(g.rivalId()), rng);
            poachPlayer(g, now, false);
        }
    }

    /**
     * Guild {@code g} lures a member (rank 3+) from {@code from}: it leaves with a line, the old guild calls it a
     * traitor, the new one welcomes it, and sometimes a megaphone. What happened.
     */
    public static String poachBot(GuildRow g, GuildRow from, Random rng) {
        if (g == null || from == null) {
            return "no such guild";
        }
        Guild live = Server.getInstance().getGuild(g.guildId());
        if (live != null && live.getMembers().size() >= live.getCapacity()) {
            return g.name() + " is full";
        }
        List<Character> candidates = new ArrayList<>();
        for (Character c : onlineBots(from.guildId())) {
            MemberRow m = BotGuilds.membership(c.getId());
            if (m != null && m.rank() >= 3 && DramaKit.freeForScene(c)) {
                candidates.add(c);
            }
        }
        if (candidates.isEmpty()) {
            return "nobody of " + from.name() + " online to poach";
        }
        Character bot = candidates.get(rng.nextInt(candidates.size()));
        Map<String, String> t = Map.of("MEMBER", bot.getName(), "OLD", from.name(), "GUILD", g.name());
        BotGuilds.sayInGuild(bot, DramaKit.pick("PoachLeaveOld", t));
        BotGuilds.leave(bot.getId(), "poached by " + g.name());
        Character oldLead = speaker(from.guildId());
        if (oldLead != null) {
            BotTiming.after(3_000, () -> BotGuilds.sayInGuild(oldLead, DramaKit.pick("PoachTraitor", t)));
        }
        if (!BotGuilds.join(bot, g.guildId(), 5, now())) {
            return bot.getName() + " left " + from.name() + " but could not join " + g.name();
        }
        Character newMate = speaker(g.guildId());
        if (newMate != null && newMate.getId() != bot.getId()) {
            BotTiming.after(5_000, () -> BotGuilds.sayInGuild(newMate, DramaKit.pick("PoachWelcome", t)));
        }
        boolean mega = rng.nextDouble() < 0.25 || QaLog.on(QaLog.Topic.GUILD_WAR);
        if (mega) {
            WorldEventMegaphones.getInstance().offerEvent(bot, "GuildPoach", t, "drama:poach:" + bot.getId());
        }
        qa(bot.getMapId(), "POACH " + bot.getName() + ": " + from.name() + " -> " + g.name() + (mega ? " (megaphone)" : ""));
        return bot.getName() + " left " + from.name() + " for " + g.name();
    }

    /**
     * Guild {@code g} whispers a player in a player guild who is a friend of one of its online members (never one with
     * {@code @botdrama off}; at most every 7 days per player, 2 an hour world-wide). {@code force} skips the caps.
     */
    public static String poachPlayer(GuildRow g, long now, boolean force) {
        List<Character> ours = onlineBots(g.guildId());
        for (Character p : DramaKit.realPlayers()) {
            if (p.getGuildId() <= 0 || p.getGuildId() == g.guildId() || BotGuilds.isBotGuild(p.getGuildId())
                    || BotAffinity.optedOut(p.getId())) {
                continue;
            }
            for (Character bot : ours) {
                if (!BotAffinity.friend(bot.getId(), p.getId())) {
                    continue;
                }
                if (!force && (!DramaKit.take("gw:poachp:" + p.getId(), now, TimeUnit.DAYS.toMillis(7))
                        || !DramaKit.hourly("gw-poachp", 2, now))) {
                    return "poach whisper capped for " + p.getName();
                }
                String line = DramaKit.pick("PoachWhisper", Map.of("PLAYER", p.getName(), "GUILD", g.name(), "OLD",
                        BotGuilds.guildName(p.getGuildId())));
                DramaKit.whisper(bot, p, line);
                BotRomance.onBotWhispered(bot.getId(), p);
                poachOffers.put(p.getId(), new long[]{g.guildId(), bot.getId(), now + TimeUnit.MINUTES.toMillis(15)});
                qa(p.getMapId(), "POACH WHISPER " + bot.getName() + " [" + g.name() + "] -> " + p.getName() + ": " + line);
                return bot.getName() + " whispers " + p.getName() + ": " + line;
            }
        }
        return "no friend of " + g.name() + "'s online members is in a player guild";
    }

    /** A player who took a poach offer (left their guild within 15 minutes) gets the real invite. */
    private static void poachInvites(long now) {
        for (Map.Entry<Integer, long[]> e : new ArrayList<>(poachOffers.entrySet())) {
            long[] o = e.getValue();
            if (now > o[2]) {
                poachOffers.remove(e.getKey());
                continue;
            }
            Character p = DramaKit.onlinePlayer(e.getKey());
            if (p == null || p.getGuildId() != 0) {
                continue;
            }
            poachOffers.remove(e.getKey());
            Character bot = DramaKit.liveBot((int) o[1]);
            Guild g = Server.getInstance().getGuild((int) o[0]);
            if (bot != null && g != null) {
                BotGuilds.sendInvite(bot, g, p);
                qa(p.getMapId(), "POACH INVITE " + bot.getName() + " -> " + p.getName() + " (" + g.getName() + ")");
            }
        }
    }

    // ── alliances and betrayals ──

    private static void maybeAlly(long now, Random rng) {
        long alliances = active().stream().filter(w -> w.kind() == Kind.ALLIANCE).count();
        if (alliances >= (long) DramaKit.prop("wars.maxAlliances", 2)
                || !DramaKit.take("gw:allyroll", now, DramaKit.hours("wars.allyHours", 12))
                || rng.nextDouble() >= DramaKit.prop("wars.allyChance", 0.3)) {
            return;
        }
        List<GuildRow> all = new ArrayList<>(BotGuilds.botGuilds());
        Collections.shuffle(all, rng);
        for (GuildRow a : all) {
            for (GuildRow b : all) {
                if (a.guildId() < b.guildId() && a.rivalId() != b.guildId() && b.rivalId() != a.guildId()
                        && allyAllowed(a, b, now)) {
                    ally(a, b, "chance");
                    return;
                }
            }
        }
    }

    private static boolean allyAllowed(GuildRow a, GuildRow b, long now) {
        return activeWar(a.guildId(), b.guildId()) == null && !allied(a.guildId(), b.guildId())
                && now - lastEnded(a.guildId(), b.guildId(), Kind.ALLIANCE) >= GuildWarRules.cooldownMs()
                && !onlineBots(a.guildId()).isEmpty() && !onlineBots(b.guildId()).isEmpty();
    }

    static boolean allied(int a, int b) {
        for (War w : wars.values()) {
            if (w.active() && w.kind() == Kind.ALLIANCE && w.involves(a) && w.involves(b)) {
                return true;
            }
        }
        return false;
    }

    /** Two bot guilds ally: a megaphone and a line in each guild chat. The alliance, or null. */
    public static War ally(GuildRow a, GuildRow b, String by) {
        ensureLoaded();
        if (a == null || b == null || a.guildId() == b.guildId() || allied(a.guildId(), b.guildId())
                || activeWar(a.guildId(), b.guildId()) != null) {
            return null;
        }
        War w = open(Kind.ALLIANCE, a.guildId(), b.guildId(), false, false);
        Map<String, String> t = Map.of("GUILD", a.name(), "RIVAL", b.name());
        Character sa = speaker(a.guildId());
        Character sb = speaker(b.guildId());
        if (sa != null) {
            WorldEventMegaphones.getInstance().offerEvent(sa, "GuildAlliance", t, "drama:ally:" + w.id());
            BotGuilds.sayInGuild(sa, DramaKit.pick("AllyFormed", Map.of("GUILD", a.name(), "RIVAL", b.name())));
        }
        if (sb != null) {
            BotGuilds.sayInGuild(sb, DramaKit.pick("AllyFormed", Map.of("GUILD", b.name(), "RIVAL", a.name())));
        }
        qa(0, "ALLIANCE " + a.name() + " + " + b.name() + " (#" + w.id() + ", " + by + ")");
        return w;
    }

    private static void endAlliance(int a, int b, State how) {
        for (War w : new ArrayList<>(wars.values())) {
            if (w.active() && w.kind() == Kind.ALLIANCE && w.involves(a) && w.involves(b)) {
                end(w, how, "war");
            }
        }
    }

    /** Alliance life: shout-outs in guild chat now and then; and now and then one betrays the other. */
    private static void alliances(long now, Random rng) {
        for (War w : active()) {
            if (w.kind() != Kind.ALLIANCE) {
                continue;
            }
            if (DramaKit.take("gw:allychat:" + w.id(), now, DramaKit.hours("wars.allyChatHours", 3)) && rng.nextDouble() < 0.5) {
                int us = rng.nextBoolean() ? w.a() : w.b();
                Character s = speaker(us);
                if (s != null && !playersOfAny(us).isEmpty()) {
                    BotGuilds.sayInGuild(s, DramaKit.pick("AllyShoutout", Map.of("GUILD", BotGuilds.guildName(us),
                            "RIVAL", BotGuilds.guildName(w.other(us)))));
                }
            }
            if (DramaKit.take("gw:betrayroll:" + w.id(), now, TimeUnit.DAYS.toMillis(1))
                    && rng.nextDouble() < DramaKit.prop("wars.betrayChancePerDay", 0.1)) {
                betray(w, rng.nextBoolean() ? w.a() : w.b(), "chance");
            }
        }
    }

    private static List<Character> playersOfAny(int guildId) {
        List<Character> out = new ArrayList<>();
        for (Character p : DramaKit.realPlayers()) {
            if (p.getGuildId() == guildId) {
                out.add(p);
            }
        }
        return out;
    }

    /** {@code traitor} betrays the alliance: a betrayal megaphone and guild chat, then war. What happened. */
    public static String betray(War alliance, int traitor, String by) {
        if (alliance == null || alliance.kind() != Kind.ALLIANCE || !alliance.active()) {
            return "no such alliance";
        }
        int victim = alliance.other(traitor);
        end(alliance, State.BETRAYED, by);
        Map<String, String> t = Map.of("GUILD", BotGuilds.guildName(traitor), "RIVAL", BotGuilds.guildName(victim));
        Character st = speaker(traitor);
        Character sv = speaker(victim);
        if (sv != null) {
            WorldEventMegaphones.getInstance().offerEvent(sv, "GuildBetrayal", t, "drama:betray:" + alliance.id());
            BotGuilds.sayInGuild(sv, DramaKit.pick("AllyBetrayedChat", t));
        }
        if (st != null) {
            BotGuilds.sayInGuild(st, DramaKit.pick("AllyBetrayerChat", t));
        }
        War w = declare(BotGuilds.guild(victim), BotGuilds.guild(traitor), "betrayal");
        qa(0, "BETRAYAL " + t.get("GUILD") + " betrayed " + t.get("RIVAL") + (w != null ? ": war #" + w.id() : ""));
        return t.get("GUILD") + " betrayed " + t.get("RIVAL") + (w != null ? " - war declared" : "");
    }

    // ── @guildwar ──

    /** What {@code @guildwar} shows a player: their guild's wars with the score, else the wars and alliances on now. */
    public static List<String> describeFor(Character player) {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        long now = now();
        int gid = player.getGuildId();
        List<War> mine = gid > 0 ? warsOf(gid) : List.of();
        for (War w : mine) {
            boolean weAreA = w.a() == gid;
            out.add("[Guild War] " + BotGuilds.guildName(gid) + " " + (weAreA ? w.scoreA() : w.scoreB()) + " - "
                    + (weAreA ? w.scoreB() : w.scoreA()) + " " + BotGuilds.guildName(w.other(gid)) + ", "
                    + GuildWarRules.daysLeft(w.endsAt(), now) + " day(s) left");
            out.add("  holding: " + heldList(w, gid) + "; they hold: " + heldList(w, w.other(gid)));
        }
        if (mine.isEmpty()) {
            for (War w : active()) {
                if (w.kind() == Kind.WAR) {
                    out.add("[Guild War] " + BotGuilds.guildName(w.a()) + " " + w.scoreA() + " - " + w.scoreB() + " "
                            + BotGuilds.guildName(w.b()) + ", " + GuildWarRules.daysLeft(w.endsAt(), now) + " day(s) left");
                } else {
                    out.add("[Alliance] " + BotGuilds.guildName(w.a()) + " + " + BotGuilds.guildName(w.b()));
                }
            }
            if (out.isEmpty()) {
                out.add("No guild wars right now.");
            }
        }
        return out;
    }

    private static String heldList(War w, int guildId) {
        Map<Integer, Integer> h = held.get(w.id());
        List<String> maps = new ArrayList<>();
        if (h != null) {
            h.forEach((map, holder) -> {
                if (holder == guildId && maps.size() < 4) {
                    maps.add(mapName(map));
                }
            });
        }
        return maps.isEmpty() ? "nothing" : String.join(", ", maps);
    }

    static String mapName(int mapId) {
        try {
            var map = soloMapling.ArtificialPlayer.BotChannels.map(1, mapId);
            String n = map == null ? null : map.getMapName();
            return n == null || n.isBlank() ? Integer.toString(mapId) : n.toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException e) {
            return Integer.toString(mapId);
        }
    }

    // ── GM ──

    /** Ticket 59's memory card: "war", "allied" or null between two guilds (bot or player guilds). */
    public static String relation(int guildA, int guildB) {
        if (!enabled() || guildA <= 0 || guildB <= 0 || guildA == guildB) {
            return null;
        }
        if (activeWar(guildA, guildB) != null) {
            return "war";
        }
        return alliesOf(guildA).contains(guildB) ? "allied" : null;
    }

    /** The active war between two guilds, or null. */
    public static War warBetween(int a, int b) {
        return activeWar(a, b);
    }

    /** A bot guild that may ally with {@code a} now (not its rival, no war, not allied), or null. */
    public static GuildRow allyFor(GuildRow a) {
        if (a == null) {
            return null;
        }
        GuildRow best = null;
        int bestOnline = -1;
        for (GuildRow g : BotGuilds.botGuilds()) {
            if (g.guildId() == a.guildId() || g.guildId() == a.rivalId() || g.rivalId() == a.guildId()
                    || activeWar(a.guildId(), g.guildId()) != null || allied(a.guildId(), g.guildId())) {
                continue;
            }
            int on = onlineBots(g.guildId()).size();
            if (on > bestOnline) {
                best = g;
                bestOnline = on;
            }
        }
        return best;
    }

    /** GM / !qa: an alliance shout-out in guild chat now (the first alliance). */
    public static String allyChatNow() {
        for (War w : active()) {
            if (w.kind() == Kind.ALLIANCE) {
                Character s = speaker(w.a());
                if (s == null) {
                    return "nobody of " + BotGuilds.guildName(w.a()) + " online";
                }
                String line = DramaKit.pick("AllyShoutout", Map.of("GUILD", BotGuilds.guildName(w.a()), "RIVAL",
                        BotGuilds.guildName(w.b())));
                BotGuilds.sayInGuild(s, line);
                return s.getName() + " [" + BotGuilds.guildName(w.a()) + "]: " + line;
            }
        }
        return "no alliance";
    }

    /** GM / !qa cleanup: a war or alliance ends with no result megaphone (a test is over). */
    public static void endQuietly(War w) {
        if (w != null && w.active()) {
            save(w.ended(State.ENDED, now()));
            held.remove(w.id());
            qa(0, "ENDED (test cleanup) #" + w.id());
        }
    }

    public static List<String> describe() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        long now = now();
        for (War w : wars.values()) {
            out.add("#" + w.id() + " " + w.kind().name().toLowerCase() + " " + BotGuilds.guildName(w.a()) + " vs "
                    + BotGuilds.guildName(w.b()) + (w.bPlayer() ? " (player guild)" : "") + ": " + w.state().label()
                    + (w.kind() == Kind.WAR ? ", score " + w.scoreA() + "-" + w.scoreB() + (w.active() ? ", "
                    + GuildWarRules.daysLeft(w.endsAt(), now) + " d left" : "") : ""));
        }
        if (out.isEmpty()) {
            out.add("no guild wars or alliances");
        }
        return out;
    }

    public static War byId(long id) {
        ensureLoaded();
        return wars.get(id);
    }

    /** GM: end a war or alliance now (a war by time: the result is posted). */
    public static String endNow(long id) {
        War w = id != 0 ? byId(id) : active().stream().filter(x -> x.kind() == Kind.WAR).findFirst().orElse(null);
        if (w == null || !w.active()) {
            return "no active war or alliance #" + id;
        }
        end(w, State.ENDED, "gm");
        return "ended #" + w.id() + " (" + w.scoreA() + "-" + w.scoreB() + ")";
    }

    /** GM / !qa: one turf minute now for every war; what each holds. */
    public static List<String> turfNow() {
        List<String> out = new ArrayList<>();
        for (War w : active()) {
            if (w.kind() != Kind.WAR) {
                continue;
            }
            GuildWarRules.Turf t = scoreTurf(w);
            War now = wars.get(w.id());
            out.add("#" + w.id() + " " + BotGuilds.guildName(w.a()) + " +" + t.pointsA() + " / "
                    + BotGuilds.guildName(w.b()) + " +" + t.pointsB() + " -> " + now.scoreA() + "-" + now.scoreB()
                    + "; " + BotGuilds.guildName(w.a()) + " holds " + heldList(now, w.a()) + "; "
                    + BotGuilds.guildName(w.b()) + " holds " + heldList(now, w.b()));
        }
        if (out.isEmpty()) {
            out.add("no active war");
        }
        qa(0, "TURF " + String.join(" | ", out));
        return out;
    }

    /** GM / !qa: a war taunt and a turf fight now for the war (first active one when id is 0). */
    public static List<String> tauntNow(long id) {
        List<String> out = new ArrayList<>();
        War w = id != 0 ? byId(id) : active().stream().filter(x -> x.kind() == Kind.WAR).findFirst().orElse(null);
        if (w == null) {
            out.add("no war");
            return out;
        }
        out.add(taunt(w, ThreadLocalRandom.current()));
        out.add(turfFight(w, w.a(), ThreadLocalRandom.current()));
        return out;
    }

    static void qa(int mapId, String line) {
        DramaKit.qa(QaLog.Topic.GUILD_WAR, mapId, line);
    }

    /**
     * rc6 scene focus (!qa gw-1, auto-QA t-gw-1): a member of a bot guild at war killed a mob, so the check can count
     * the kills per side ("KILL name [guild] on map"). Only called while a run listens to GUILD_WAR.
     */
    public static void onKill(Character chr, int mapId) {
        if (!soloMapling.ArtificialPlayer.BotHelpers.isBot(chr)) {
            return;
        }
        var m = BotGuilds.membership(chr.getId());
        if (m == null) {
            return;
        }
        for (War w : active()) {
            if (w.kind() == Kind.WAR && (w.a() == m.guildId() || w.b() == m.guildId())) {
                qa(mapId, "KILL " + chr.getName() + " [" + BotGuilds.guildName(m.guildId()) + "] on " + mapId);
                return;
            }
        }
    }

    /** Tests. */
    static void useForTest(GuildWarStore testStore, LongSupplier testClock) {
        store = testStore != null ? testStore : new GuildWarStore.Jdbc();
        clock = testClock != null ? testClock : System::currentTimeMillis;
        wars.clear();
        held.clear();
        poachOffers.clear();
        loaded = false;
        loadTriedAt = 0;
        lastStepAt = 0;
        lastSaveAt = 0;
    }

    static War openForTest(Kind kind, int a, int b, boolean bPlayer) {
        ensureLoaded();
        return open(kind, a, b, bPlayer, false);
    }

    static boolean onRoster(int botId) {
        return BotRoster.isMember(botId - BotStore.BOT_ID_BASE) && BotHelpers.isArtificial(botId);
    }
}
