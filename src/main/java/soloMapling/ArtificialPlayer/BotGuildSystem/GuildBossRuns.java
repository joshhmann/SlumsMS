package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import net.server.Server;
import net.server.guild.Guild;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotLogout;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildRules.Boss;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;
import soloMapling.server.ExecutorServiceManager;

import java.awt.Point;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Staged guild boss runs and Guild PQs (1.5.0; real boss AI comes in 1.5.x, owner 2026-10-21).
 *
 * <ol>
 *   <li><b>Call:</b> a guild with enough members online of the boss's level calls a run in guild chat and by
 *   megaphone (through the gate).</li>
 *   <li><b>Gather</b> ({@code gatherMinutes}, 4): up to six members come to the boss entrance on one channel and
 *   stand there - a visible gathering - saying the things people say before a run.</li>
 *   <li><b>Inside</b> ({@code runMinutes}, 25): they go in: they leave the world (logged out where they stood, so
 *   nothing of them runs) and the director leaves them out.</li>
 *   <li><b>Back:</b> they log back in at the entrance; one posts how it went in guild chat and by megaphone. The
 *   loot is only named ("zhelm"): no item is made, so nothing reaches the economy. On a clear the guild gains a
 *   little GP.</li>
 * </ol>
 * A player can join for real: while they gather, a crew member takes a party invite from a real player on the same
 * map (always), becomes a follower and goes in with them, fighting as a follower does (no boss AI). Those bots stay
 * out of the staged part. At most one run at a time in the world.
 */
public final class GuildBossRuns {

    private static final Logger log = LoggerFactory.getLogger(GuildBossRuns.class);

    enum Phase { GATHER, INSIDE, BACK, DONE }

    static final class Run {
        final int guildId;
        final String guildName;
        final Boss boss;
        final int channel;
        final List<Integer> crew;
        final Set<Integer> withPlayers = ConcurrentHashMap.newKeySet();
        final Set<Integer> away = ConcurrentHashMap.newKeySet();
        final long gatherMs;
        final long insideMs;
        final String by;
        volatile Phase phase = Phase.GATHER;
        volatile long phaseAt;
        volatile long lastChatAt;
        volatile boolean cleared;
        ScheduledFuture<?> beat;

        Run(int guildId, String guildName, Boss boss, int channel, List<Integer> crew, long gatherMs, long insideMs,
            String by, long now) {
            this.guildId = guildId;
            this.guildName = guildName;
            this.boss = boss;
            this.channel = channel;
            this.crew = crew;
            this.gatherMs = gatherMs;
            this.insideMs = insideMs;
            this.by = by;
            this.phaseAt = now;
        }
    }

    private static volatile Run current;
    /** How a bot leaves its party (a seam for tests). */
    private static volatile java.util.function.Consumer<Character> partyLeaver =
            soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands::botLeaveParty;
    private static volatile long lastRunAt;
    private static final Map<Integer, Long> lastGuildRun = new ConcurrentHashMap<>();

    private GuildBossRuns() {
    }

    // ── queries ──

    /**
     * A bot gathering at a boss entrance now: it stays put (the director does not log it out) and takes a real
     * player's party invite.
     */
    public static boolean gathering(int charId) {
        Run r = current;
        return r != null && r.phase == Phase.GATHER && r.crew.contains(charId) && !r.withPlayers.contains(charId);
    }

    /**
     * rc6 (owner QA 2026-10-23, gu-5: "some are in a party already"): a crew member gathering for a run that sits in a
     * party of bots leaves it, so a real player's party invite reaches it instead of "already in a party". A party with
     * a real player in it is kept. True when it left one.
     */
    public static boolean freeForInvite(Character bot) {
        if (bot == null || !gathering(bot.getId())) {
            return false;
        }
        return leaveBotParty(bot);
    }

    static boolean leaveBotParty(Character bot) {
        if (bot == null || bot.getParty() == null || withRealPlayer(bot)) {
            return false;
        }
        try {
            partyLeaver.accept(bot);
        } catch (RuntimeException e) {
            log.debug("boss run crew {} could not leave its party", bot.getName(), e);
            return false;
        }
        BotGuilds.qa(bot.getMapId(), "PARTY LEFT " + bot.getName() + " left its bot party to take a player's invite");
        return true;
    }

    /** The director must not log this roster bot in: it is "inside" a staged run. */
    public static boolean away(int botId) {
        Run r = current;
        return r != null && r.away.contains(BotStore.BOT_ID_BASE + botId);
    }

    /** The entrance map of the run on now, or 0. */
    public static int entranceMap() {
        Run r = current;
        return r == null || r.phase == Phase.DONE ? 0 : r.boss.entranceMap();
    }

    public static boolean running() {
        Run r = current;
        return r != null && r.phase != Phase.DONE;
    }

    static List<String> describe() {
        Run r = current;
        if (r == null || r.phase == Phase.DONE) {
            return List.of();
        }
        return List.of("boss run: " + r.guildName + " -> " + r.boss.name() + " ch" + r.channel + ", " + r.phase
                + " for " + (System.currentTimeMillis() - r.phaseAt) / 1000 + " s, crew " + r.crew.size()
                + ", with players " + r.withPlayers.size() + ", inside " + r.away.size());
    }

    // ── starting ──

    /** The {@code by} the guild step starts runs with; every other starter is a GM or an !qa check. */
    static final String BOT_STARTER = "guilds";

    /**
     * A run a GM or !qa asked for takes over the one on: the QA checks stage a run and must get it (TEST 2026-10-24,
     * t-gw-4 logged no run and t-gu-5 skipped with "a boss run is already on (SlumLords)"). Bot-run guilds never
     * preempt each other.
     */
    static boolean mayPreempt(String by) {
        return !BOT_STARTER.equals(by);
    }

    /** From the guild step: now and then a guild with enough members online calls a run (evenings, one at a time). */
    static void maybeStart(GuildRow g, long now, Random rng) {
        if (running() || now - lastRunAt < GuildRules.hours("bossGapHours", 2)
                || now - lastGuildRun.getOrDefault(g.guildId(), 0L) < GuildRules.hours("bossEveryHours", 8)
                || !BotGuilds.take("bossroll:" + g.guildId(), now, TimeUnit.MINUTES.toMillis(30))
                || rng.nextDouble() >= GuildRules.prop("bossChance", 0.25)) {
            return;
        }
        double curve;
        try {
            curve = soloMapling.worldevents.WorldEvents.curveFraction();
        } catch (RuntimeException | LinkageError e) {
            curve = 1;
        }
        if (curve < 0.4) {
            return;
        }
        String problem = start(g, null, false, BOT_STARTER, 0,
                soloMapling.ArtificialPlayer.BotDramaSystem.GuildWars.alliesOf(g.guildId())); // 1.5.0: allies join
        if (problem != null) {
            log.debug("No boss run for {}: {}", g.name(), problem);
        }
    }

    /**
     * Starts a run for the guild now: the boss (null = the hardest its online members can take). {@code fast}: a
     * 60 s gathering and a 2 minute run (GM and !qa). The problem, or null when it started.
     */
    public static synchronized String start(GuildRow g, Boss wanted, boolean fast, String by) {
        return start(g, wanted, fast, by, 0);
    }

    /** As {@link #start(GuildRow, Boss, boolean, String)} on a given channel (0 = where a member plays). */
    public static synchronized String start(GuildRow g, Boss wanted, boolean fast, String by, int onChannel) {
        return start(g, wanted, fast, by, onChannel, List.of());
    }

    /**
     * As {@link #start(GuildRow, Boss, boolean, String, int)}, a joint run with the members of allied guilds too
     * (1.5.0 bot drama: alliances).
     */
    public static synchronized String start(GuildRow g, Boss wanted, boolean fast, String by, int onChannel,
                                            List<Integer> allies) {
        if (running()) {
            if (!mayPreempt(by)) {
                return "a boss run is already on (" + current.guildName + ")";
            }
            stop(by); // GM / !qa: the run replaces the one on - its crew goes home (no result) and the log says so
        }
        List<Character> members = new ArrayList<>(BotGuilds.onlineMembers(g.guildId()));
        StringBuilder joint = new StringBuilder(g.name());
        for (int ally : allies == null ? List.<Integer>of() : allies) {
            List<Character> theirs = BotGuilds.onlineMembers(ally);
            if (!theirs.isEmpty()) {
                members.addAll(theirs);
                joint.append(" + ").append(BotGuilds.guildName(ally));
            }
        }
        List<Character> able = new ArrayList<>();
        for (Character c : members) {
            BotSM sm = CharacterStorage.getBotById(c.getId());
            if (sm != null && BotGuilds.free(c) && BotRoster.member(c.getId() - BotStore.BOT_ID_BASE) != null
                    && !withRealPlayer(c)) {
                able.add(c);
            }
        }
        int minGroup = fast ? 2 : (int) GuildRules.prop("bossMinGroup", 3);
        Boss boss = wanted;
        if (boss == null) {
            int[] levels = able.stream().mapToInt(Character::getLevel).toArray();
            boss = GuildRules.pickBoss(levels, minGroup);
            if (boss == null) {
                return "too few members online at a boss's level (" + able.size() + " free roster members online)";
            }
        }
        Boss b = boss;
        able.removeIf(c -> c.getLevel() < b.minLevel());
        able.sort(Comparator.comparingInt(Character::getGuildRank).thenComparing(Comparator.comparingInt(Character::getLevel).reversed()));
        if (able.size() < minGroup) {
            return able.size() + " free members online at level " + boss.minLevel() + "+ (need " + minGroup + ")";
        }
        List<Character> crew = able.subList(0, Math.min(6, able.size()));
        int channel = onChannel > 0 ? onChannel : runChannel(g.guildId(), crew);
        long now = System.currentTimeMillis();
        // {fast} is the QA staging (soloMapling.qa.GameQaWorld's "bossrun" step), never a real player's run: the
        // windows a check then waits out are the QA clock's, and the QA lines below print the seconds it used.
        long gather = fast ? soloMapling.qa.QaTiming.ms(TimeUnit.SECONDS.toMillis(60)) : GuildRules.minutes("gatherMinutes", 4);
        long inside = fast ? soloMapling.qa.QaTiming.ms(TimeUnit.MINUTES.toMillis(2)) : GuildRules.minutes("runMinutes", 25);
        List<Integer> ids = new ArrayList<>();
        for (Character c : crew) {
            ids.add(c.getId());
        }
        Run run = new Run(g.guildId(), joint.toString(), boss, channel, ids, gather, inside, by, now);
        MapleMap map = BotChannels.map(channel, boss.entranceMap());
        if (map == null) {
            return "no map " + boss.entranceMap() + " on channel " + channel;
        }
        current = run;
        lastRunAt = now;
        lastGuildRun.put(g.guildId(), now);
        Character caller = crew.get(0);
        Map<String, String> tokens = Map.of("BOSS", boss.name(), "GUILD", g.name(), "CH", Integer.toString(channel));
        BotGuilds.sayInGuild(caller, GuildLines.pick("BossCall", tokens));
        WorldEventMegaphones.getInstance().offerEvent(caller, "GuildBossCall", tokens, "guild:boss:" + g.guildId() + ":" + now);
        int i = 0;
        for (Character c : new ArrayList<>(crew)) {
            gatherAt(c, map, channel, i++);
        }
        BotGuilds.qa(map.getId(), "BOSS RUN " + g.name() + " -> " + boss.name() + " ch" + channel + " (" + by + "): crew "
                + names(ids) + "; gathering " + gather / 1000 + " s at " + boss.entranceMap());
        run.beat = ExecutorServiceManager.getScheduledExecutorService().scheduleAtFixedRate(() -> {
            try {
                beat(run, System.currentTimeMillis(), ThreadLocalRandom.current());
            } catch (RuntimeException e) {
                log.warn("Guild boss run beat failed", e);
            }
        }, 5, 5, TimeUnit.SECONDS);
        return null;
    }

    /** The channel: one where a real member of the guild plays, else the caller's. */
    private static int runChannel(int guildId, List<Character> crew) {
        for (Character p : BotGuilds.realPlayers()) {
            if (p.getGuildId() == guildId) {
                return p.getClient().getChannel();
            }
        }
        return BotGuilds.channelOf(crew.get(0));
    }

    private static void gatherAt(Character c, MapleMap map, int channel, int index) {
        try {
            if (BotGuilds.channelOf(c) != channel) {
                BotChannels.moveToChannel(c, channel);
            }
            soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement.stop(c);
            leaveBotParty(c); // a partied bot cannot take a player's invite at the entrance
            if (CharacterStorage.getBotById(c.getId()) instanceof TrainingBot) {
                BotTypeManager.convertBotType(c, BotTypeManager.BotType.SOCIAL_BOT); // stand and wait, not grind
            }
            Point at = map.getPortal(0) != null ? new Point(map.getPortal(0).getPosition()) : new Point(0, 0);
            at.translate(-120 + index * 45, 0);
            Point ground = map.getPointBelow(new Point(at.x, at.y - 40));
            c.changeMap(map, ground != null ? ground : at);
        } catch (RuntimeException e) {
            log.debug("gather {}", c.getName(), e);
        }
    }

    // ── the beat ──

    static void beat(Run run, long now, Random rng) {
        if (current != run) {
            cancel(run);
            return;
        }
        switch (run.phase) {
            case GATHER -> {
                noteJoins(run);
                for (int id : run.crew) { // one that got pulled into a bot party while it waits leaves it again
                    if (!run.withPlayers.contains(id)) {
                        leaveBotParty(BotGuilds.live(id));
                    }
                }
                if (now - run.lastChatAt > 35_000) {
                    run.lastChatAt = now;
                    Character c = liveCrew(run, rng);
                    if (c != null && BotGuilds.realPlayerSees(c)) {
                        String line = GuildLines.pick("BossWait", Map.of("BOSS", run.boss.name()));
                        if (line != null) {
                            BotSpeak(c, line);
                        }
                    }
                }
                if (now - run.phaseAt >= run.gatherMs) {
                    goInside(run, now);
                }
            }
            case INSIDE -> {
                if (now - run.phaseAt >= run.insideMs) {
                    comeBack(run, now);
                }
            }
            case BACK -> {
                if (now - run.phaseAt >= 15_000) {
                    results(run, rng);
                }
            }
            case DONE -> cancel(run);
        }
    }

    /** Crew members a real player on the entrance map partied with go in for real with that player. */
    private static void noteJoins(Run run) {
        for (int id : run.crew) {
            Character c = BotGuilds.live(id);
            if (c != null && withRealPlayer(c) && run.withPlayers.add(id)) {
                BotGuilds.qa(c.getMapId(), "REAL " + c.getName() + " joins a real player's party for " + run.boss.name());
            }
        }
    }

    private static void goInside(Run run, long now) {
        noteJoins(run);
        run.phase = Phase.INSIDE;
        run.phaseAt = now;
        List<Integer> staged = new ArrayList<>();
        for (int id : run.crew) {
            if (!run.withPlayers.contains(id) && BotGuilds.live(id) != null) {
                staged.add(id);
            }
        }
        if (staged.isEmpty()) {
            Character any = liveCrew(run, ThreadLocalRandom.current());
            BotGuilds.sayInGuild(any, GuildLines.pick("BossReal", Map.of("BOSS", run.boss.name())));
            BotGuilds.qa(0, "BOSS RUN " + run.guildName + ": every crew member went with a real player - nothing staged");
            finish(run);
            return;
        }
        Character first = BotGuilds.live(staged.get(0));
        BotGuilds.sayInGuild(first, GuildLines.pick("BossGo", Map.of("BOSS", run.boss.name())));
        for (int id : staged) {
            run.away.add(id);
            BotLogout.requestOwn(id, now); // into the boss run: a chat does not keep it
        }
        BotGuilds.qa(0, "INSIDE " + run.guildName + " -> " + run.boss.name() + ": " + names(staged) + " left the world for "
                + run.insideMs / 1000 + " s" + (run.withPlayers.isEmpty() ? "" : "; with players: " + names(new ArrayList<>(run.withPlayers))));
    }

    private static void comeBack(Run run, long now) {
        run.phase = Phase.BACK;
        run.phaseAt = now;
        List<Integer> back = new ArrayList<>(run.away);
        run.away.clear();
        for (int id : back) {
            int botId = id - BotStore.BOT_ID_BASE;
            ExecutorServiceManager.runAsync(() -> {
                try {
                    if (BotGuilds.live(id) == null) {
                        BotPopulationDirector.logInBounded(botId); // through the director's login slots
                    }
                    Character c = BotGuilds.live(id); // the director picks its own channel: come back on the run's
                    if (c != null && BotGuilds.channelOf(c) != run.channel) {
                        BotChannels.moveToChannel(c, run.channel);
                    }
                    MapleMap entrance = BotChannels.map(run.channel, run.boss.entranceMap());
                    if (c != null && entrance != null && c.getMapId() != entrance.getId()) {
                        gatherAt(c, entrance, run.channel, run.crew.indexOf(id)); // a forced-return map put it elsewhere
                    }
                } catch (RuntimeException e) {
                    log.debug("boss run login {}", botId, e);
                }
            });
        }
        BotGuilds.qa(0, "BACK " + run.guildName + " from " + run.boss.name() + ": " + names(back) + " log back in at the entrance");
    }

    private static void results(Run run, Random rng) {
        List<Integer> crew = new ArrayList<>(run.crew);
        crew.removeAll(run.withPlayers);
        List<Character> here = new ArrayList<>();
        long sum = 0;
        for (int id : crew) {
            Character c = BotGuilds.live(id);
            if (c != null) {
                here.add(c);
                sum += c.getLevel();
            }
        }
        if (here.isEmpty()) {
            BotGuilds.qa(0, "RESULT " + run.guildName + ": nobody came back online (the director may log them in later)");
            finish(run);
            return;
        }
        int avg = (int) (sum / here.size());
        run.cleared = GuildRules.runClears(avg, run.boss, rng.nextDouble());
        Character speaker = here.get(0);
        Character looter = here.get(rng.nextInt(here.size()));
        String loot = run.boss.loot()[rng.nextInt(run.boss.loot().length)];
        Map<String, String> tokens = Map.of("BOSS", run.boss.name(), "LOOTER", looter.getName(), "LOOT", loot,
                "GUILD", run.guildName);
        BotGuilds.sayInGuild(speaker, GuildLines.pick(run.cleared ? "BossWin" : "BossFail", tokens));
        WorldEventMegaphones.getInstance().offerEvent(speaker, run.cleared ? "GuildBossWin" : "GuildBossFail", tokens,
                "guild:bossresult:" + run.guildId + ":" + run.phaseAt);
        if (run.cleared) {
            try {
                Guild g = Server.getInstance().getGuild(run.guildId);
                if (g != null) {
                    g.gainGP(GuildRules.bossGp()); // small: players' real GPQs must be able to pass bot guilds
                }
            } catch (RuntimeException e) {
                log.debug("guild gp", e);
            }
        }
        BotGuilds.qa(speaker.getMapId(), "RESULT " + run.guildName + " " + run.boss.name() + ": "
                + (run.cleared ? "CLEARED, " + looter.getName() + " got " + loot + " (named only, no item)" : "WIPED")
                + ", avg lv " + avg);
        finish(run);
    }

    private static void finish(Run run) {
        run.phase = Phase.DONE;
        cancel(run);
    }

    private static void cancel(Run run) {
        ScheduledFuture<?> f = run.beat;
        if (f != null) {
            f.cancel(false);
        }
    }

    /** GM / !qa stop: ends a run now; bots inside come back at once (no result). How many runs stopped. */
    public static synchronized int stop(String by) {
        Run run = current;
        if (run == null || run.phase == Phase.DONE) {
            return 0;
        }
        if (run.phase == Phase.INSIDE) {
            comeBack(run, System.currentTimeMillis());
        }
        finish(run);
        BotGuilds.qa(0, "BOSS RUN " + run.guildName + " stopped by " + by);
        return 1;
    }

    // ── helpers ──

    private static Character liveCrew(Run run, Random rng) {
        List<Character> live = new ArrayList<>();
        for (int id : run.crew) {
            Character c = BotGuilds.live(id);
            if (c != null) {
                live.add(c);
            }
        }
        return live.isEmpty() ? null : live.get(rng.nextInt(live.size()));
    }

    static boolean withRealPlayer(Character bot) {
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

    private static String names(List<Integer> ids) {
        List<String> out = new ArrayList<>();
        for (int id : ids) {
            MemberRow m = BotGuilds.membership(id);
            out.add(m == null ? Integer.toString(id) : m.name());
        }
        return String.join(", ", new LinkedHashSet<>(out));
    }

    /** Tests. */
    static void resetForTest() {
        current = null;
        lastRunAt = 0;
        lastGuildRun.clear();
    }

    static Run currentForTest() {
        return current;
    }

    static void setForTest(Run run) {
        current = run;
    }

    static void usePartyLeaverForTest(java.util.function.Consumer<Character> leaver) {
        partyLeaver = leaver != null ? leaver : soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands::botLeaveParty;
    }
}
