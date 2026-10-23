package soloMapling.ArtificialPlayer.BotGuildSystem;

import client.Character;
import net.server.Server;
import net.server.coordinator.world.InviteCoordinator;
import net.server.coordinator.world.InviteCoordinator.InviteType;
import net.server.guild.Guild;
import net.server.guild.GuildCharacter;
import net.server.guild.GuildPackets;
import net.server.guild.GuildResponse;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.BotWhispers;
import soloMapling.ArtificialPlayer.PlayerBusy;
import soloMapling.ArtificialPlayer.BotAffinitySystem.AffinityState;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotAffinity;
import soloMapling.ArtificialPlayer.BotChannels;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMegaphoneSystem.WorldEventMegaphones;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPopulationDirector;
import soloMapling.ArtificialPlayer.BotRoster;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.GuildRow;
import soloMapling.ArtificialPlayer.BotGuildSystem.GuildStore.MemberRow;
import soloMapling.qa.QaLog;
import soloMapling.server.ExecutorServiceManager;
import soloMapling.server.SoloMaplingUtilities;
import tools.PacketCreator;

import java.util.ArrayList;
import java.util.Comparator;
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
import java.util.regex.Pattern;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * 1.5.0 bot guilds and bots in players' guilds (owner grill 2026-10-21; ticket 50 B/C).
 *
 * <p><b>Bot guilds.</b> About ten guilds of roster bots ({@link GuildPlan}) are real stock guilds: a {@code guilds}
 * row, the name and emblem over a member's head, the guild list. The server founds them itself the first time it runs
 * with migration 014 and none exist. Bot membership is {@code botguildmember}; {@code Guild}'s member load adds it
 * ({@link #guildCharacters}), and a bot that logs in takes its guild ({@link #onBotLogin}).
 *
 * <p><b>What they do</b>, from the population director's minute step and only where it shows (a guild with a real
 * player online, a map a real player sees): members chat in guild chat (scripted, one line per guild every
 * {@code chatterMinutes}; a reply to a player's guild line goes through the chat brain, one bot per line); training
 * members party up to grind ({@link #leaderTrainingMap}); leaders recruit with megaphones (through the megaphone gate)
 * and whisper players they like with a real guild invite; two pairs of rival guilds trash talk where both stand, post
 * megaphone jabs and now and then go for the same grind map; and a guild sometimes stages a boss run
 * ({@link GuildBossRuns}).
 *
 * <p><b>Players.</b> A player asks a bot guild's member to join ("can i join ur guild?"): the leader decides by its
 * feelings and the player's level ({@link GuildRules#application}) and an invite comes through the stock flow. A
 * player invites a bot into their guild: only a friend says yes, pickier past half bots
 * ({@link GuildRules#inviteAcceptChance}). A bot quits a guild whose players became its rivals, or a player guild
 * that ignored it for weeks ({@link GuildRules#quit}).
 *
 * <p>Cheap for unseen bots: everything is memory lookups over a few hundred member rows once a minute; nothing runs
 * for a guild no real player can see.
 */
public final class BotGuilds {

    private static final Logger log = LoggerFactory.getLogger(BotGuilds.class);

    private static volatile GuildStore store = new GuildStore.Jdbc();
    private static final Map<Integer, GuildRow> guilds = new ConcurrentHashMap<>();
    private static final Map<Integer, MemberRow> members = new ConcurrentHashMap<>();
    /** Members never stored: on-demand (staged) bots, which never log back in. */
    private static final Set<Integer> memoryOnly = ConcurrentHashMap.newKeySet();
    private static final Map<String, Long> nextAt = new ConcurrentHashMap<>();
    /** Players a bot guild already whispered (player id -> when), so a player is asked at most every few days. */
    private static final Map<Integer, Long> whispered = new ConcurrentHashMap<>();
    private static final java.util.Deque<Long> worldWhispers = new java.util.ArrayDeque<>();
    private static final Set<Long> deciding = ConcurrentHashMap.newKeySet();
    private static volatile boolean loaded;
    private static volatile long loadTriedAt;
    private static volatile boolean seedTried;
    private static volatile boolean gpRescaled;
    /**
     * Membership writes: queued off the caller's thread (stock guild code calls in from packet handlers, some holding
     * the guild's member lock, and a bot's database work can wait on the bots' share of the pool), in order per bot.
     */
    private static volatile java.util.concurrent.Executor writer = ExecutorServiceManager::runAsync;
    private static final soloMapling.ArtificialPlayer.SerialWrites memberWrites =
            new soloMapling.ArtificialPlayer.SerialWrites(r -> writer.execute(r));
    private static volatile long lastStepAt;
    private static volatile IntFunction<Character> online = BotGuilds::liveBot;
    /** Active roster members' bot ids (a seam: tests give their own). */
    private static volatile java.util.function.Supplier<List<Integer>> rosterIds = BotGuilds::activeRoster;
    private static volatile Map<Integer, GuildStore.StoredBot> storedBots = Map.of();

    static final Pattern APPLY = Pattern.compile(
            "(?i).*\\b(join|joining|apply|recruit\\w*|inv|invite|accept me|let me in|room)\\b.*\\bguild\\b.*"
                    + "|(?i).*\\bguild\\b.*\\b(join|inv|invite|recruit\\w*|room|spot|open)\\b.*"
                    + "|(?i).*\\bcan i join\\b.*");

    private BotGuilds() {
    }

    // ── loading ──

    /**
     * Reads the bot guilds and memberships once. A failed read is not "nothing stored": nothing is kept, the guilds
     * stay unloaded (no step, no founding, no membership write) and the read is tried again after half a minute.
     */
    static void ensureLoaded() {
        if (loaded) {
            return;
        }
        synchronized (BotGuilds.class) {
            long now = System.currentTimeMillis();
            if (loaded || (loadTriedAt > 0 && now - loadTriedAt < TimeUnit.SECONDS.toMillis(30))) {
                return;
            }
            loadTriedAt = now;
            List<GuildRow> g;
            List<MemberRow> m;
            try {
                g = store.loadGuilds();
                m = g == null ? null : store.loadMembers();
            } catch (RuntimeException e) {
                g = null;
                m = null;
            }
            if (g == null || m == null) {
                log.warn("Bot guilds could not be read; trying again later (nothing is founded or written meanwhile)");
                return;
            }
            for (GuildRow row : g) {
                guilds.put(row.guildId(), row);
            }
            for (MemberRow row : m) {
                members.putIfAbsent(row.charId(), row);
            }
            loaded = true;
            if (!guilds.isEmpty() || !members.isEmpty()) {
                log.info("Bot guilds: {} bot-run guilds, {} bot members", guilds.size(), members.size());
            }
        }
    }

    /** Whether the stored guilds have been read (until then nothing is founded or written). */
    static boolean loaded() {
        return loaded;
    }

    private static void saveMemberLater(MemberRow row) {
        memberWrites.submit(row.charId(), () -> store.saveMember(row));
    }

    private static void deleteMemberLater(int charId) {
        memberWrites.submit(charId, () -> store.deleteMember(charId));
    }

    public static boolean enabled() {
        return GuildRules.enabled();
    }

    // ── queries (memory only) ──

    /** Whether this guild is run by bots (founded by the server). */
    public static boolean isBotGuild(int guildId) {
        return guilds.containsKey(guildId);
    }

    public static GuildRow guild(int guildId) {
        return guilds.get(guildId);
    }

    public static List<GuildRow> botGuilds() {
        ensureLoaded();
        List<GuildRow> out = new ArrayList<>(guilds.values());
        out.sort(Comparator.comparingInt(GuildRow::guildId));
        return out;
    }

    public static GuildRow byName(String name) {
        ensureLoaded();
        for (GuildRow g : guilds.values()) {
            if (g.name().equalsIgnoreCase(name)) {
                return g;
            }
        }
        return null;
    }

    public static MemberRow membership(int botCharId) {
        return members.get(botCharId);
    }

    /** Bot members of a guild (any guild). */
    public static List<MemberRow> membersOf(int guildId) {
        List<MemberRow> out = new ArrayList<>();
        for (MemberRow m : members.values()) {
            if (m.guildId() == guildId) {
                out.add(m);
            }
        }
        out.sort(Comparator.comparingInt(MemberRow::rank).thenComparing(MemberRow::name));
        return out;
    }

    /** Bot members of the guild that are online now. */
    static List<Character> onlineMembers(int guildId) {
        List<Character> out = new ArrayList<>();
        for (MemberRow m : members.values()) {
            if (m.guildId() == guildId) {
                Character c = online.apply(m.charId());
                if (c != null && c.getGuildId() == guildId) {
                    out.add(c);
                }
            }
        }
        return out;
    }

    /**
     * The bot members {@code Guild} adds to the {@code characters} it loads, offline (a bot that logs in is marked
     * online by {@link #onBotLogin}).
     */
    public static List<GuildCharacter> guildCharacters(int guildId, int world) {
        if (!enabled()) {
            return List.of();
        }
        ensureLoaded();
        List<GuildCharacter> out = new ArrayList<>();
        for (MemberRow m : membersOf(guildId)) {
            out.add(new GuildCharacter(null, m.charId(), m.level(), m.name(), (byte) -1, world, m.job(), m.rank(),
                    guildId, false, 5));
        }
        return out;
    }

    // ── a bot logs in or out ──

    /** A bot joined the world: it takes its guild (name over its head, an online dot on the guild list). */
    public static void onBotLogin(Character bot) {
        if (bot == null || !enabled() || !BotHelpers.isArtificial(bot.getId())) {
            return;
        }
        try {
            ensureLoaded();
            MemberRow m = members.get(bot.getId());
            if (m == null) {
                if (bot.getGuildId() != 0) { // a template's guild is not this bot's
                    bot.setGuildId(0);
                    bot.setGuildRank(5);
                }
                return;
            }
            attach(bot, m);
        } catch (RuntimeException e) {
            log.warn("Bot {} could not take its guild", bot.getName(), e);
        }
    }

    /** Puts a live bot in its guild's member list as online. False when the guild is gone. */
    private static boolean attach(Character bot, MemberRow m) {
        bot.setGuildId(m.guildId());
        bot.setGuildRank(m.rank());
        bot.setAllianceRank(5);
        Guild g = Server.getInstance().getGuild(m.guildId(), bot.getWorld(), bot);
        if (g == null) {
            log.info("Bot {}'s guild {} is gone: it leaves it", bot.getName(), m.guildId());
            members.remove(bot.getId());
            if (!memoryOnly.remove(bot.getId())) {
                deleteMemberLater(bot.getId());
            }
            bot.setGuildId(0);
            bot.setGuildRank(5);
            return false;
        }
        GuildCharacter mgc = g.getMGC(bot.getId());
        if (mgc == null) {
            mgc = new GuildCharacter(bot);
            g.addGuildMember(mgc, bot);
        }
        mgc.setCharacter(bot);
        mgc.setLevel(bot.getLevel());
        mgc.setJobId(bot.getJob().getId());
        bot.setMGC(mgc);
        g.setOnline(bot.getId(), true, channelOf(bot));
        return true;
    }

    /** A bot is leaving the world: an offline dot on its guild's list. */
    public static void onBotLogout(Character bot) {
        if (bot == null || bot.getGuildId() <= 0 || !BotHelpers.isArtificial(bot.getId())) {
            return;
        }
        try {
            Guild g = Server.getInstance().getGuild(bot.getGuildId());
            if (g != null) {
                g.setOnline(bot.getId(), false, -1);
                GuildCharacter mgc = g.getMGC(bot.getId());
                if (mgc != null) {
                    mgc.setCharacter(null);
                }
            }
        } catch (RuntimeException e) {
            log.debug("Bot {} could not go offline in its guild", bot.getName(), e);
        }
    }

    // ── joining and leaving ──

    /**
     * A live bot joins a guild now (rank 5 unless given). Stored unless the bot is not a roster member (an
     * on-demand bot never logs back in). False when it is in a guild already or the guild is full or gone.
     */
    public static boolean join(Character bot, int guildId, int rank, long now) {
        ensureLoaded();
        if (!loaded || bot == null || bot.getGuildId() > 0 || members.containsKey(bot.getId())) {
            return false; // unloaded: a write now could clash with the stored rows still to be read
        }
        Guild g = Server.getInstance().getGuild(guildId, bot.getWorld(), null);
        if (g == null || g.getMembers().size() >= g.getCapacity()) {
            return false;
        }
        MemberRow row = new MemberRow(bot.getId(), guildId, rank, now, now, bot.getName(), bot.getLevel(),
                bot.getJob().getId());
        bot.setGuildId(guildId);
        bot.setGuildRank(rank);
        bot.setAllianceRank(5);
        GuildCharacter mgc = new GuildCharacter(bot);
        if (g.addGuildMember(mgc, bot) == 0) {
            bot.setGuildId(0);
            bot.setGuildRank(5);
            return false;
        }
        bot.setMGC(mgc);
        members.put(bot.getId(), row);
        if (BotRoster.isMember(bot.getId() - BotStore.BOT_ID_BASE)) {
            saveMemberLater(row);
        } else {
            memoryOnly.add(bot.getId());
        }
        showGuildOverHead(bot, g);
        qa(bot.getMapId(), "JOIN " + bot.getName() + " -> " + g.getName() + " (rank " + rank + ")"
                + (memoryOnly.contains(bot.getId()) ? " [memory only]" : ""));
        return true;
    }

    /** The bot leaves its guild now (it quit, or a GM made it). False when it is in none. */
    public static boolean leave(int botCharId, String why) {
        soloMapling.ArtificialPlayer.BotConvo.SayDo.event(botCharId, soloMapling.ArtificialPlayer.BotConvo.SayDo.Ev.LEAVE_GROUP, 0); // rc6 say-do
        MemberRow m = members.remove(botCharId);
        if (m == null) {
            return false;
        }
        if (!memoryOnly.remove(botCharId)) {
            deleteMemberLater(botCharId); // queued: the stock expel calls in holding the guild's member lock
        }
        Guild g = Server.getInstance().getGuild(m.guildId());
        if (g != null) {
            GuildCharacter mgc = g.getMGC(botCharId);
            if (mgc != null) {
                g.leaveGuild(mgc);
            }
        }
        Character bot = online.apply(botCharId);
        if (bot != null) {
            bot.setGuildId(0);
            bot.setGuildRank(5);
            if (bot.getMGC() != null && bot.getMGC().getId() == botCharId) {
                bot.getMGC().setOfflineGuildRank(5);
            }
            try {
                if (bot.getMap() != null) {
                    bot.getMap().broadcastPacket(bot, GuildPackets.guildNameChanged(botCharId, ""));
                }
            } catch (RuntimeException e) {
                log.debug("guild tag", e);
            }
        }
        qa(bot == null ? 0 : bot.getMapId(), "LEAVE " + m.name() + " left " + (g == null ? m.guildId() : g.getName())
                + ": " + why);
        return true;
    }

    private static void showGuildOverHead(Character bot, Guild g) {
        try {
            if (bot.getMap() != null) {
                bot.getMap().broadcastPacket(bot, GuildPackets.guildNameChanged(bot.getId(), g.getName()));
                bot.getMap().broadcastPacket(bot, GuildPackets.guildMarkChanged(bot.getId(), g));
            }
        } catch (RuntimeException e) {
            log.debug("guild tag", e);
        }
    }

    // ── hooks from the stock guild code ──

    /** {@code Guild.expelMember}: a player officer expelled a bot. */
    public static void onExpelled(int charId) {
        if (BotHelpers.isArtificial(charId) && members.containsKey(charId)) {
            leave(charId, "expelled");
        }
    }

    /** {@code Guild.disbandGuild}: every bot member is guildless. */
    public static void onDisband(int guildId) {
        for (MemberRow m : membersOf(guildId)) {
            leave(m.charId(), "the guild disbanded");
        }
        guilds.remove(guildId);
    }

    /** {@code Guild.changeRank}: a bot's new rank is kept. */
    public static void onRankChanged(int charId, int rank) {
        MemberRow m = members.get(charId);
        if (m != null && m.rank() != rank) {
            MemberRow r = m.withRank(rank);
            members.put(charId, r);
            if (!memoryOnly.contains(charId)) {
                saveMemberLater(r); // queued: never database work on the packet thread
            }
        }
    }

    /**
     * {@code Guild.sendInvitation} to a bot: it answers after a human delay (5-40 s) - yes only to a friend, and
     * pickier past half bots. Returns null: the inviter sees nothing until the answer (as with a player).
     */
    public static GuildResponse playerInvitesBot(Character player, Character bot) {
        if (player == null || bot == null || !enabled()) {
            return GuildResponse.NOT_IN_CHANNEL;
        }
        long pair = ((long) bot.getId() << 32) | (player.getId() & 0xffffffffL);
        if (!deciding.add(pair)) {
            return GuildResponse.MANAGING_INVITE;
        }
        long delay = ThreadLocalRandom.current().nextLong(5, 40);
        if (QaLog.on(QaLog.Topic.BOT_GUILD)) {
            delay = 4; // a staged test does not wait
        }
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                answerGuildInvite(player, bot, ThreadLocalRandom.current().nextDouble());
            } catch (RuntimeException e) {
                log.warn("Bot {} could not answer {}'s guild invite", bot.getName(), player.getName(), e);
            } finally {
                deciding.remove(pair);
            }
        }), delay, TimeUnit.SECONDS);
        return null;
    }

    static boolean answerGuildInvite(Character player, Character bot, double roll) {
        Guild g = player.getGuild();
        if (g == null || bot.getGuildId() > 0 || online.apply(bot.getId()) == null) {
            return false;
        }
        AffinityState feels = BotAffinity.state(bot.getId(), player.getId());
        int size = g.getMembers().size();
        int bots = 0;
        for (GuildCharacter m : g.getMembers()) {
            if (BotHelpers.isArtificial(m.getId())) {
                bots++;
            }
        }
        double chance = GuildRules.inviteAcceptChance(feels, bots + 1, size + 1);
        boolean yes = roll < chance && join(bot, g.getId(), 5, System.currentTimeMillis());
        qa(bot.getMapId(), "INVITE " + player.getName() + " -> " + bot.getName() + ": " + feels.label() + ", bots "
                + bots + "/" + size + ", chance " + Math.round(chance * 100) + "% -> " + (yes ? "JOINED" : "declined"));
        if (yes) {
            sayInGuild(bot, GuildLines.pick("JoinThanks", Map.of("PLAYER", player.getName(), "GUILD", g.getName())));
        } else {
            player.sendPacket(GuildResponse.DENIED_INVITE.getPacket(bot.getName()));
            sayNo(bot, player, declineLine(feels, player.getName(), g.getName()));
        }
        return yes;
    }

    /**
     * rc6 (owner QA 2026-10-23, gu-2): a bot that turns down a guild invite (the guild window, right-click invite or
     * /ginvite: all the same packet) says why in a line, not only the stock "denied" notice.
     */
    static String declineLine(AffinityState feels, String player, String guild) {
        String node = feels != null && feels.hostile() ? "InviteNoHostile" : "InviteNo";
        return GuildLines.pick(node, Map.of("PLAYER", player == null ? "" : player, "GUILD", guild == null ? "" : guild));
    }

    /** Out loud when the two stand on one map, else a whisper. */
    private static void sayNo(Character bot, Character player, String line) {
        if (line == null) {
            return;
        }
        if (bot.getMapId() == player.getMapId() && channelOf(bot) == channelOf(player)) {
            BotSpeak(bot, line);
        } else {
            BotWhispers.send(player, bot.getName(), Math.max(0, channelOf(bot) - 1), line, true);
        }
    }

    // ── a player asks to join a bot guild ──

    /**
     * Chat to a bot (map chat or whisper, from the chat brain's first step): a request to join its guild gets an
     * answer here, and on a yes a real guild invite (from the leader, an officer, or this bot) a few seconds later.
     * Null when the line is no such request or the bot is in no bot guild.
     */
    public static String answerApplication(Character bot, Character player, String line) {
        if (!enabled() || bot == null || player == null || line == null || !APPLY.matcher(line).matches()) {
            return null;
        }
        MemberRow m = members.get(bot.getId());
        if (m == null) {
            return null;
        }
        GuildRow gr = guilds.get(m.guildId());
        Guild g = Server.getInstance().getGuild(m.guildId());
        if (g == null) {
            return null;
        }
        if (gr == null) { // a bot in a player's guild: the player leader decides
            return GuildLines.pick("AppAskLeader", Map.of("GUILD", g.getName(), "LEADER", leaderName(g)));
        }
        Character leader = online.apply(g.getLeaderId());
        int deciderId = leader != null ? leader.getId() : bot.getId();
        AffinityState feels = BotAffinity.state(deciderId, player.getId());
        if (feels == AffinityState.NEUTRAL && deciderId != bot.getId()) {
            feels = BotAffinity.state(bot.getId(), player.getId()); // the member who was asked vouches
        }
        int minLevel = GuildPlan.SPECS.stream().filter(s -> s.name().equalsIgnoreCase(gr.name()))
                .mapToInt(GuildPlan.Spec::minLevel).findFirst().orElse(1);
        GuildRules.Refusal no = GuildRules.application(feels, player.getLevel(), player.getGuildId(), minLevel,
                gr.vibe(), g.getMembers().size(), g.getCapacity(), ThreadLocalRandom.current().nextDouble());
        qa(bot.getMapId(), "APPLY " + player.getName() + " -> " + g.getName() + " (asked " + bot.getName() + ", "
                + feels.label() + ", lv " + player.getLevel() + "): " + (no == null ? "YES" : no.name()));
        Map<String, String> tokens = Map.of("GUILD", g.getName(), "LEADER", leaderName(g),
                "LEVEL", Integer.toString(minLevel), "PLAYER", player.getName());
        if (no != null) {
            return GuildLines.pick("AppNo" + no.name().charAt(0) + no.name().substring(1).toLowerCase(Locale.ROOT)
                    .replace("_", ""), tokens);
        }
        Character inviter = leader != null ? leader : officerOnline(g.getId(), bot);
        Character from = inviter;
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> sendInvite(from, g, player),
                3 + ThreadLocalRandom.current().nextInt(8), TimeUnit.SECONDS);
        return from == bot ? GuildLines.pick("AppYes", tokens)
                : GuildLines.pick("AppYesLeader", Map.of("LEADER", from.getName(), "PLAYER", player.getName()));
    }

    private static Character officerOnline(int guildId, Character fallback) {
        for (Character c : onlineMembers(guildId)) {
            if (c.getGuildRank() <= 2) {
                return c;
            }
        }
        return fallback;
    }

    private static String leaderName(Guild g) {
        GuildCharacter l = g.getMGC(g.getLeaderId());
        return l == null ? "our leader" : l.getName();
    }

    /** A real guild invite from a bot, through the stock invite flow (the player clicks accept as usual). */
    public static boolean sendInvite(Character fromBot, Guild g, Character player) {
        try {
            if (PlayerBusy.isBusy(player) || player.getGuildId() > 0 || !player.isLoggedinWorld()) {
                return false;
            }
            if (InviteCoordinator.createInvite(InviteType.GUILD, fromBot, g.getId(), player.getId())) {
                player.sendPacket(GuildPackets.guildInvite(g.getId(), fromBot.getName()));
                qa(player.getMapId(), "INVITE SENT " + fromBot.getName() + " -> " + player.getName() + " for " + g.getName());
                return true;
            }
        } catch (RuntimeException e) {
            log.debug("bot guild invite", e);
        }
        return false;
    }

    // ── guild chat ──

    private static final Map<Integer, Long> lastAnswer = new ConcurrentHashMap<>();

    /**
     * A player's guild chat line ({@code MultiChatHandler} type 2): at most one online bot member answers, through
     * the chat brain - the one named, else now and then one of them (a question or a greeting more often).
     */
    public static void onGuildChat(Character player, String line) {
        if (!enabled() || player == null || line == null || BotHelpers.isBot(player) || player.getGuildId() <= 0) {
            return;
        }
        int gid = player.getGuildId();
        List<Character> bots = onlineMembers(gid);
        if (bots.isEmpty()) {
            return;
        }
        String text = " " + line.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9? ]", " ") + " ";
        Character to = null;
        for (Character b : bots) {
            if (text.contains(" " + b.getName().toLowerCase(Locale.ROOT) + " ")) {
                to = b;
                break;
            }
        }
        long now = System.currentTimeMillis();
        if (to == null) {
            Long last = lastAnswer.get(gid);
            if (last != null && now - last < GuildRules.answerGapMs()) {
                return;
            }
            boolean inviting = text.contains("?") || text.matches(".* (hi|hey|yo|sup|hello|anyone|any1|gm|gn|guys|all) .*");
            double chance = inviting ? GuildRules.prop("answerChance", 0.6) : GuildRules.prop("chatChance", 0.25);
            if (ThreadLocalRandom.current().nextDouble() >= chance) {
                return;
            }
            to = bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
        }
        BotSM brain = CharacterStorage.getBotById(to.getId());
        if (brain == null) {
            return;
        }
        lastAnswer.put(gid, now);
        noteAttention(to.getId(), player.getId());
        Character speaker = to;
        qa(player.getMapId(), "GUILD CHAT " + player.getName() + ": \"" + line + "\" -> " + speaker.getName() + " answers");
        BotConversation.talk(brain, player, line, reply -> sayInGuild(speaker, reply));
    }

    /** A bot says a line in its guild's chat (every online member sees it). */
    public static void sayInGuild(Character bot, String line) {
        if (bot == null || line == null || line.isBlank() || bot.getGuildId() <= 0) {
            return;
        }
        Server.getInstance().guildChat(bot.getGuildId(), bot.getName(), bot.getId(), line);
        qa(bot.getMapId(), "GUILD SAY " + bot.getName() + " [" + guildName(bot.getGuildId()) + "]: " + line);
    }

    public static String guildName(int gid) {
        GuildRow r = guilds.get(gid);
        if (r != null) {
            return r.name();
        }
        Guild g = Server.getInstance().getGuild(gid);
        return g == null ? Integer.toString(gid) : g.getName();
    }

    /** {@code GuildOperationHandler} join: a player joined a guild with bots in it; one online bot says welcome. */
    public static void onPlayerJoined(Character player) {
        if (!enabled() || player == null || BotHelpers.isBot(player) || player.getGuildId() <= 0) {
            return;
        }
        List<Character> bots = onlineMembers(player.getGuildId());
        if (bots.isEmpty()) {
            return;
        }
        Character bot = bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
        String line = GuildLines.pick("Welcome", Map.of("PLAYER", player.getName(), "GUILD", guildName(player.getGuildId())));
        qa(player.getMapId(), "PLAYER JOINED " + player.getName() + " -> " + guildName(player.getGuildId()));
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> sayInGuild(bot, line),
                2 + ThreadLocalRandom.current().nextInt(4), TimeUnit.SECONDS);
    }

    /** {@code Character.levelUp}: a real member levelled; an online bot guildmate may say gz. */
    public static void onMemberLevelUp(Character player) {
        if (!enabled() || player == null || BotHelpers.isBot(player) || player.getGuildId() <= 0
                || ThreadLocalRandom.current().nextDouble() >= GuildRules.prop("gzChance", 0.5)) {
            return;
        }
        List<Character> bots = onlineMembers(player.getGuildId());
        if (bots.isEmpty()) {
            return;
        }
        Character bot = bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
        String line = GuildLines.pick("Gz", Map.of("PLAYER", player.getName()));
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> sayInGuild(bot, line),
                2 + ThreadLocalRandom.current().nextInt(6), TimeUnit.SECONDS);
    }

    /**
     * {@code Guild.setOnline}: a real member came online. A bot guildmate says hi now and then; in a player-led
     * guild with no bot member online, up to two log in ("show up") and come to the player's channel.
     */
    public static void onRealMemberOnline(int guildId, int playerId, int channel) {
        if (!enabled() || BotHelpers.isArtificial(playerId) || members.isEmpty()) {
            return;
        }
        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> ExecutorServiceManager.runAsync(() -> {
            try {
                showUp(guildId, playerId, channel, System.currentTimeMillis());
            } catch (RuntimeException e) {
                log.debug("guild show-up", e);
            }
        }), 20 + ThreadLocalRandom.current().nextInt(40), TimeUnit.SECONDS);
    }

    static void showUp(int guildId, int playerId, int channel, long now) {
        Character player = onlinePlayer(playerId);
        if (player == null || player.getGuildId() != guildId) {
            return;
        }
        List<Character> bots = onlineMembers(guildId);
        if (!bots.isEmpty()) {
            if (take("greet:" + playerId, now, TimeUnit.HOURS.toMillis(2))) {
                Character bot = bots.get(ThreadLocalRandom.current().nextInt(bots.size()));
                sayInGuild(bot, GuildLines.pick("Greet", Map.of("PLAYER", player.getName())));
            }
            return;
        }
        if (isBotGuild(guildId) || !take("showup:" + guildId, now, TimeUnit.HOURS.toMillis(2))) {
            return;
        }
        int n = 0;
        for (MemberRow m : membersOf(guildId)) {
            if (n >= 2 || memoryOnly.contains(m.charId())) {
                continue;
            }
            int botId = m.charId() - BotStore.BOT_ID_BASE;
            if (BotRoster.member(botId) != null && BotPopulationDirector.logInBounded(botId)) {
                n++;
                Character bot = online.apply(m.charId());
                if (bot != null && channelOf(bot) != channel && free(bot)) {
                    BotChannels.moveToChannel(bot, channel);
                }
                qa(player.getMapId(), "SHOW UP " + m.name() + " logs in for " + player.getName());
            }
        }
    }

    /**
     * Something friendly happened between a bot and a real player (an affinity event): if they share a guild, the
     * bot is not ignored. Written at most every 6 hours per bot.
     */
    public static void noteAttention(int botCharId, int playerId) {
        MemberRow m = members.get(botCharId);
        if (m == null || BotHelpers.isArtificial(playerId)) {
            return;
        }
        Character player = onlinePlayer(playerId);
        if (player == null || player.getGuildId() != m.guildId()) {
            return;
        }
        long now = System.currentTimeMillis();
        MemberRow r = m.attended(now);
        members.put(botCharId, r);
        if (!memoryOnly.contains(botCharId) && now - m.attentionAt() > TimeUnit.HOURS.toMillis(6)) {
            saveMemberLater(r); // queued: affinity hooks run on packet threads
        }
    }

    // ── parties ──

    /**
     * {@code BotRecruitManager}: the chance a bot takes a real player's party invite it was not asked for in chat
     * first (owner: friends accept, neutral sometimes, rivals never; guildmates like friends).
     */
    public static double partyChance(Character bot, Character inviter) {
        AffinityState feels = BotAffinity.state(bot.getId(), inviter.getId());
        boolean guildmate = bot.getGuildId() > 0 && bot.getGuildId() == inviter.getGuildId();
        boolean run = GuildBossRuns.gathering(bot.getId()) && inviter.getMapId() == bot.getMapId();
        double chance = GuildRules.partyAcceptChance(feels, guildmate, run);
        if (QaLog.on(QaLog.Topic.BOT_GUILD)) {
            QaLog.map(QaLog.Topic.BOT_GUILD, bot.getMapId(), "PARTY INVITE " + inviter.getName() + " -> " + bot.getName()
                    + ": " + feels.label() + (guildmate ? ", guildmate" : "") + (run ? ", boss-run crew" : "")
                    + ", chance " + Math.round(chance * 100) + "%");
        }
        return chance;
    }

    /**
     * {@code TrainingBot}: a bot partied with a guildmate bot that leads the party trains on the leader's map (same
     * channel, a map with mobs). Null when that does not apply.
     */
    public static Integer leaderTrainingMap(Character bot) {
        if (bot.getGuildId() <= 0) {
            return null;
        }
        Party party = bot.getParty();
        if (party == null || party.getLeaderId() == bot.getId()) {
            return null;
        }
        MemberRow leader = members.get(party.getLeaderId());
        if (leader == null || leader.guildId() != bot.getGuildId()) {
            return null;
        }
        PartyCharacter pc = party.getMemberById(party.getLeaderId());
        if (pc == null || !pc.isOnline() || pc.getChannel() != channelOf(bot)) {
            return null;
        }
        int map = pc.getMapId();
        return MapMobIndex.level(map) >= 0 ? map : null;
    }

    /** Guild grinding parties: two or three training members on one channel, within 20 levels, led by the strongest. */
    static void partyUp(int guildId, long now) {
        if (!take("party:" + guildId, now, GuildRules.minutes("partyMinutes", 10))) {
            return;
        }
        Map<Integer, List<Character>> byChannel = new HashMap<>();
        for (Character c : onlineMembers(guildId)) {
            BotSM sm = CharacterStorage.getBotById(c.getId());
            if (sm instanceof TrainingBot && c.getParty() == null && free(c) && c.getLevel() >= 10) {
                byChannel.computeIfAbsent(channelOf(c), k -> new ArrayList<>()).add(c);
            }
        }
        for (List<Character> here : byChannel.values()) {
            if (here.size() < 2) {
                continue;
            }
            here.sort(Comparator.comparingInt(Character::getLevel).reversed());
            Character lead = here.get(0);
            List<Character> crew = new ArrayList<>();
            for (Character c : here.subList(1, here.size())) {
                if (crew.size() < 2 && lead.getLevel() - c.getLevel() <= 20) {
                    crew.add(c);
                }
            }
            if (crew.isEmpty() || !Party.createParty(lead, true) || lead.getParty() == null) {
                continue;
            }
            int pid = lead.getParty().getId();
            StringBuilder names = new StringBuilder(lead.getName());
            for (Character c : crew) {
                if (Party.joinParty(c, pid, true)) {
                    names.append(" + ").append(c.getName());
                }
            }
            qa(lead.getMapId(), "PARTY " + guildName(guildId) + ": " + names);
            return; // one new party per guild per step
        }
    }

    // ── the minute step ──

    /** From the population director's tick: at most once a minute, off the tick's thread. */
    public static void maybeStep(long now) {
        if (!enabled() || now - lastStepAt < TimeUnit.SECONDS.toMillis(60)) {
            return;
        }
        lastStepAt = now;
        ExecutorServiceManager.runAsync(() -> {
            try {
                step(now, ThreadLocalRandom.current());
            } catch (RuntimeException e) {
                log.warn("Bot guild step failed", e);
            }
        });
    }

    /** GM / !qa: the step now, on this thread. */
    public static void stepNow() {
        step(System.currentTimeMillis(), ThreadLocalRandom.current());
    }

    static void step(long now, Random rng) {
        ensureLoaded();
        if (!loaded) {
            return; // the stored guilds could not be read yet: founding now would found them twice
        }
        if (!gpRescaled) {
            gpRescaled = true;
            rescaleOldGp();
        }
        if (!seedTried && GuildRules.seedEnabled()) {
            seedTried = seed(now, rng) >= 0;
        }
        Set<Integer> withPlayers = guildsWithPlayersOnline();
        double curve = curve();
        for (GuildRow g : botGuilds()) {
            if (!onlineMembers(g.guildId()).isEmpty()) {
                partyUp(g.guildId(), now);
                maybeRecruit(g, now, rng, false);
                GuildBossRuns.maybeStart(g, now, rng);
            }
            if (withPlayers.contains(g.guildId()) && curve >= 0.2) {
                maybeChatter(g.guildId(), g.vibe(), now, rng);
            }
        }
        for (int gid : withPlayers) {
            if (!isBotGuild(gid)) {
                maybeChatter(gid, null, now, rng);
            }
            if (take("quit:" + gid, now, GuildRules.minutes("quitCheckMinutes", 10))) {
                checkQuits(gid, now);
            }
            if (isBotGuild(gid) && take("kickcheck:" + gid, now, GuildRules.minutes("quitCheckMinutes", 10))) {
                checkKicks(gid, now, false);
            }
        }
        refill(now, rng);
        rivalry(now, rng);
    }

    /**
     * Once per boot: bot guilds founded by an rc build with its large GP ({@link GuildRules#rescaledGp}) get the GP
     * the current rules give, so players can compete for the top of the guild ranking. A guild whose GP does not match
     * that build's pattern (players earned some) is left alone. What changed.
     */
    static List<String> rescaleOldGp() {
        List<String> out = new ArrayList<>();
        for (GuildRow g : botGuilds()) {
            try {
                Guild live = Server.getInstance().getGuild(g.guildId()); // loaded: change it there, it writes itself
                int gp = live != null ? live.getGP() : store.gp(g.guildId());
                int now = gp < 0 ? -1 : GuildRules.rescaledGp(gp, g.targetSize());
                if (now < 0) {
                    continue;
                }
                if (live != null) {
                    live.removeGP(gp - now);
                } else if (!store.replaceGp(g.guildId(), gp, now)) {
                    continue;
                }
                out.add(g.name() + ": " + gp + " -> " + now + " GP");
                log.info("Bot guild {}: GP {} -> {} (rc founding GP rescaled)", g.name(), gp, now);
            } catch (RuntimeException e) {
                log.debug("guild gp rescale {}", g.name(), e);
            }
        }
        return out;
    }

    // ── refilling ──

    /**
     * Bot guilds lose members (quits, a GM, a bot retired from the roster) and nothing else adds any, so a guild below
     * its target size takes in an online, free, guildless roster bot of its level band now and then: at most one per
     * guild per {@code refillHours} (8), so a few a day. What joined.
     */
    static List<String> refill(long now, Random rng) {
        List<String> out = new ArrayList<>();
        List<Character> pool = null;
        for (GuildRow g : botGuilds()) {
            GuildPlan.Spec spec = specOf(g.name());
            if (spec == null || !GuildRules.refillDue(membersOf(g.guildId()).size(), g.targetSize())
                    || !take("refill:" + g.guildId(), now, GuildRules.hours("refillHours", 8))) {
                continue;
            }
            if (pool == null) {
                pool = refillPool();
            }
            Character pick = pickRefill(pool, spec, rng);
            if (pick != null && join(pick, g.guildId(), 5, now)) {
                pool.remove(pick);
                out.add(pick.getName() + " joined " + g.name());
                qa(pick.getMapId(), "REFILL " + pick.getName() + " (lv " + pick.getLevel() + ") joins " + g.name()
                        + " (" + membersOf(g.guildId()).size() + "/" + g.targetSize() + ")");
            }
        }
        return out;
    }

    /** One of these bots in the guild's level band, at random; null when none fits. */
    static Character pickRefill(List<Character> pool, GuildPlan.Spec spec, Random rng) {
        List<Character> fit = new ArrayList<>();
        for (Character c : pool) {
            if (c.getLevel() >= spec.minLevel() && c.getLevel() <= spec.maxLevel()) {
                fit.add(c);
            }
        }
        return fit.isEmpty() ? null : fit.get(rng.nextInt(fit.size()));
    }

    /** Online roster bots (training or social) in no guild and free to join one. */
    private static List<Character> refillPool() {
        List<Character> out = new ArrayList<>();
        for (BotSM sm : CharacterStorage.getAllBots().values()) {
            Character c = sm.getChr();
            if (c != null && c.getGuildId() <= 0 && !members.containsKey(c.getId())
                    && BotRoster.isMember(c.getId() - BotStore.BOT_ID_BASE) && free(c)) {
                out.add(c);
            }
        }
        return out;
    }

    static GuildPlan.Spec specOf(String guildName) {
        for (GuildPlan.Spec s : GuildPlan.SPECS) {
            if (s.name().equalsIgnoreCase(guildName)) {
                return s;
            }
        }
        return null;
    }

    private static double curve() {
        try {
            return soloMapling.worldevents.WorldEvents.curveFraction();
        } catch (RuntimeException | LinkageError e) {
            return 1;
        }
    }

    /** Guilds (with bot members) that have a real, visible player online now. */
    static Set<Integer> guildsWithPlayersOnline() {
        Set<Integer> withBots = new HashSet<>();
        for (MemberRow m : members.values()) {
            withBots.add(m.guildId());
        }
        Set<Integer> out = new HashSet<>();
        if (withBots.isEmpty()) {
            return out;
        }
        for (Character p : realPlayers()) {
            if (withBots.contains(p.getGuildId())) {
                out.add(p.getGuildId());
            }
        }
        return out;
    }

    private static void maybeChatter(int guildId, GuildRules.Vibe vibe, long now, Random rng) {
        if (!take("chatter:" + guildId, now, GuildRules.chatterGapMs())) {
            return;
        }
        if (rng.nextDouble() >= GuildRules.prop("chatterChance", 0.6)) {
            return;
        }
        List<Character> bots = onlineMembers(guildId);
        if (bots.isEmpty()) {
            return;
        }
        Character bot = bots.get(rng.nextInt(bots.size()));
        Map<String, String> tokens = new HashMap<>();
        tokens.put("GUILD", guildName(guildId));
        if (bots.size() > 1) {
            Character other = bots.get(rng.nextInt(bots.size()));
            if (other != bot) {
                tokens.put("MEMBER", other.getName());
            }
        }
        sayInGuild(bot, GuildLines.pickFor(vibe, "Chatter", tokens));
    }

    // ── recruiting ──

    /**
     * A bot guild with room recruits: its leader (or an officer) posts a megaphone through the megaphone gate, a
     * member standing in Henesys or the FM says it out loud where a player sees, and members whisper guildless
     * players they count as friends - with a real guild invite. {@code force} (GM) skips the timers, not the gate.
     */
    static List<String> maybeRecruit(GuildRow gr, long now, Random rng, boolean force) {
        List<String> out = new ArrayList<>();
        Guild g = Server.getInstance().getGuild(gr.guildId());
        if (g == null || g.getMembers().size() >= g.getCapacity()) {
            out.add(gr.name() + " is full");
            return out;
        }
        if (!force && (!take("recruit:" + gr.guildId(), now, GuildRules.hours("recruitHours", 6))
                || rng.nextDouble() >= 0.5)) {
            return out;
        }
        List<Character> bots = onlineMembers(gr.guildId());
        if (bots.isEmpty()) {
            out.add("no member of " + gr.name() + " is online");
            return out;
        }
        Character speaker = null;
        for (Character c : bots) {
            if (c.getGuildRank() <= 2 && (speaker == null || c.getGuildRank() < speaker.getGuildRank())) {
                speaker = c;
            }
        }
        if (speaker != null) {
            int minLevel = GuildPlan.SPECS.stream().filter(s -> s.name().equalsIgnoreCase(gr.name()))
                    .mapToInt(GuildPlan.Spec::minLevel).findFirst().orElse(1);
            boolean offered = WorldEventMegaphones.getInstance().offerEvent(speaker, "GuildRecruitCall",
                    Map.of("GUILD", gr.name(), "LEVEL", Integer.toString(Math.max(1, minLevel)),
                            "WHERE", rng.nextBoolean() ? "fm" : "henesys", "STYLE", gr.vibe().label()),
                    "guild:recruit:" + gr.guildId() + (force ? ":" + now : ""));
            out.add("megaphone by " + speaker.getName() + ": " + (offered ? "sent" : "waiting for the megaphone gate"));
            qa(speaker.getMapId(), "RECRUIT MEGAPHONE " + speaker.getName() + " for " + gr.name() + ": "
                    + (offered ? "sent" : "queued/gated"));
        }
        for (Character c : bots) { // out loud in Henesys or the FM, where a player sees
            if (inRecruitTown(c.getMapId()) && realPlayerSees(c)) {
                String line = GuildLines.pickFor(gr.vibe(), "Recruit", Map.of("GUILD", gr.name()));
                if (line != null) {
                    BotSpeak(c, line);
                    out.add(c.getName() + " says it in map " + c.getMapId());
                }
                break;
            }
        }
        out.addAll(whisperFriends(gr, g, bots, now, force));
        return out;
    }

    static boolean inRecruitTown(int mapId) {
        return mapId == 100000000 || mapId == 100000100 || (mapId >= 910000000 && mapId <= 910000022);
    }

    private static List<String> whisperFriends(GuildRow gr, Guild g, List<Character> bots, long now, boolean force) {
        List<String> out = new ArrayList<>();
        for (Character p : realPlayers()) {
            if (p.getGuildId() > 0) {
                continue;
            }
            Long last = whispered.get(p.getId());
            if (!force && last != null && now - last < TimeUnit.DAYS.toMillis(3)) {
                continue;
            }
            if (!force && worldWhispersLastHour(now) >= GuildRules.prop("whispersPerHour", 4)) {
                break;
            }
            for (Character bot : bots) {
                if (!BotAffinity.friend(bot.getId(), p.getId())) {
                    continue;
                }
                String line = GuildLines.pick("WhisperInvite", Map.of("PLAYER", p.getName(), "GUILD", gr.name()));
                if (line == null) {
                    break;
                }
                if (!BotWhispers.send(p, bot.getName(),
                        channelOf(bot) - 1, line, false)) {
                    break;
                }
                whispered.put(p.getId(), now);
                noteWorldWhisper(now);
                soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.onBotWhispered(bot.getId(), p); // a jealous partner
                Character from = bot;
                ExecutorServiceManager.getScheduledExecutorService().schedule(() -> sendInvite(from, g, p),
                        6, TimeUnit.SECONDS);
                out.add(bot.getName() + " whispers " + p.getName() + ": " + line);
                qa(p.getMapId(), "RECRUIT WHISPER " + bot.getName() + " -> " + p.getName() + " (friend): " + line);
                break;
            }
        }
        return out;
    }

    private static synchronized int worldWhispersLastHour(long now) {
        while (!worldWhispers.isEmpty() && now - worldWhispers.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
            worldWhispers.pollFirst();
        }
        return worldWhispers.size();
    }

    private static synchronized void noteWorldWhisper(long now) {
        worldWhispers.addLast(now);
    }

    // ── rivalry ──

    /**
     * Rival bot guilds: where members of both stand on a map a player sees, one jabs (and maybe one jabs back),
     * every 15 minutes per pair at most; now and then a megaphone jab (through the gate), and now and then one
     * party leader goes to grind where the rival's party grinds. Light: no wars, no KS on players.
     */
    static void rivalry(long now, Random rng) {
        Set<Integer> seen = new HashSet<>();
        for (GuildRow g : guilds.values()) {
            if (g.rivalId() <= 0 || !guilds.containsKey(g.rivalId()) || !seen.add(Math.min(g.guildId(), g.rivalId()))) {
                continue;
            }
            GuildRow r = guilds.get(g.rivalId());
            List<Character> mine = onlineMembers(g.guildId());
            List<Character> theirs = onlineMembers(r.guildId());
            if (mine.isEmpty() || theirs.isEmpty()) {
                continue;
            }
            long pair = Math.min(g.guildId(), r.guildId());
            if (take("jab:" + pair, now, GuildRules.minutes("jabMinutes", 15))) {
                if (!mapJab(g, mine, r, theirs, rng)) {
                    nextAt.remove("jab:" + pair); // nobody met: try again next minute
                }
            }
            if (take("megajab:" + pair, now, GuildRules.hours("megaJabHours", 8)) && rng.nextDouble() < 0.5) {
                megaJab(rng.nextBoolean() ? g : r, rng);
            }
            if (take("claim:" + pair, now, GuildRules.minutes("claimMinutes", 45)) && rng.nextDouble() < 0.3) {
                claim(rng.nextBoolean() ? g : r, rng);
            }
        }
    }

    /** One jab where both guilds have someone on a map a real player sees; false when they never meet. */
    static boolean mapJab(GuildRow g, List<Character> mine, GuildRow r, List<Character> theirs, Random rng) {
        for (Character a : mine) {
            for (Character b : theirs) {
                if (a.getMapId() == b.getMapId() && channelOf(a) == channelOf(b) && realPlayerSees(a)) {
                    boolean flip = rng.nextBoolean();
                    Character speaker = flip ? b : a;
                    Character target = flip ? a : b;
                    GuildRow sg = flip ? r : g;
                    GuildRow tg = flip ? g : r;
                    String line = GuildLines.pickFor(sg.vibe(), "Jab", Map.of("RIVAL", tg.name(), "GUILD", sg.name(),
                            "MEMBER", target.getName()));
                    if (line == null) {
                        return false;
                    }
                    BotSpeak(speaker, line);
                    qa(speaker.getMapId(), "JAB " + speaker.getName() + " [" + sg.name() + "] -> [" + tg.name() + "]: " + line);
                    String back = GuildLines.pick("JabBack", Map.of("RIVAL", sg.name(), "MEMBER", speaker.getName()));
                    if (back != null && rng.nextDouble() < 0.6) {
                        ExecutorServiceManager.getScheduledExecutorService().schedule(() -> BotSpeak(target, back),
                                3 + rng.nextInt(4), TimeUnit.SECONDS);
                    }
                    return true;
                }
            }
        }
        return false;
    }

    /** A megaphone jab at the rival guild, from a member of {@code g}, through the megaphone gate. */
    static String megaJab(GuildRow g, Random rng) {
        GuildRow r = guilds.get(g.rivalId());
        List<Character> mine = onlineMembers(g.guildId());
        if (r == null || mine.isEmpty()) {
            return "no rival or no member online";
        }
        Character speaker = mine.get(rng.nextInt(mine.size()));
        boolean offered = WorldEventMegaphones.getInstance().offerEvent(speaker, "GuildJab",
                Map.of("GUILD", g.name(), "RIVAL", r.name()), "guild:jab:" + g.guildId() + ":" + System.currentTimeMillis());
        qa(speaker.getMapId(), "MEGA JAB " + speaker.getName() + " [" + g.name() + "] at " + r.name() + ": "
                + (offered ? "sent" : "queued/gated"));
        return speaker.getName() + " jabs " + r.name() + " by megaphone: " + (offered ? "sent" : "waiting for the gate");
    }

    /** One guild's party leader goes to grind where the rival guild's party grinds (if the map suits its level). */
    static String claim(GuildRow g, Random rng) {
        GuildRow r = guilds.get(g.rivalId());
        if (r == null) {
            return "no rival";
        }
        Character theirLead = partyLeader(r.guildId());
        Character myLead = partyLeader(g.guildId());
        if (theirLead == null || myLead == null) {
            return "both guilds need a party grinding";
        }
        int map = theirLead.getMapId();
        int mobLevel = MapMobIndex.level(map);
        if (mobLevel < 0 || Math.abs(mobLevel - myLead.getLevel()) > 20 || myLead.getMapId() == map) {
            return "the rival's map does not suit " + myLead.getName();
        }
        soloMapling.worldevents.WorldEventSteer.hold(myLead.getId(), map);
        int id = myLead.getId();
        ExecutorServiceManager.getScheduledExecutorService().schedule(
                () -> soloMapling.worldevents.WorldEventSteer.unhold(id), 25, TimeUnit.MINUTES);
        qa(myLead.getMapId(), "CLAIM " + myLead.getName() + " [" + g.name() + "] goes to grind on " + map
                + " where " + r.name() + " grinds");
        return myLead.getName() + " heads to " + map + " to contest " + r.name();
    }

    private static Character partyLeader(int guildId) {
        for (Character c : onlineMembers(guildId)) {
            Party p = c.getParty();
            if (p != null && p.getLeaderId() == c.getId() && p.getMembers().size() > 1
                    && CharacterStorage.getBotById(c.getId()) instanceof TrainingBot) {
                return c;
            }
        }
        return null;
    }

    // ── quitting ──

    /** Bots of a guild with players online check whether to quit (rivals, or ignored for weeks). What quit. */
    public static List<String> checkQuits(int guildId, long now) {
        List<String> out = new ArrayList<>();
        Guild g = Server.getInstance().getGuild(guildId);
        if (g == null) {
            return out;
        }
        int leaderId = g.getLeaderId();
        boolean playerLed = !BotHelpers.isArtificial(leaderId);
        List<Integer> players = new ArrayList<>();
        for (GuildCharacter mc : g.getMembers()) {
            if (!BotHelpers.isArtificial(mc.getId()) && BotAffinity.loaded(mc.getId())) {
                players.add(mc.getId());
            }
        }
        for (MemberRow m : membersOf(guildId)) {
            if (m.rank() == 1) {
                continue; // a bot guild's leader keeps its guild
            }
            int hostile = 0;
            int known = 0;
            String rivalName = null;
            for (int pid : players) {
                AffinityState s = BotAffinity.state(m.charId(), pid);
                if (s != AffinityState.NEUTRAL) {
                    known++;
                }
                if (s.hostile()) {
                    hostile++;
                    GuildCharacter pc = g.getMGC(pid);
                    rivalName = pc == null ? rivalName : pc.getName();
                }
            }
            boolean hostileToLeader = playerLed && BotAffinity.hostile(m.charId(), leaderId);
            GuildRules.QuitReason why = GuildRules.quit(playerLed, hostileToLeader, hostile, known, m.joinedAt(),
                    m.attentionAt(), now);
            if (why != null) {
                out.add(quit(m.charId(), why, rivalName));
            }
        }
        return out;
    }

    /**
     * GM / !qa: the bot joined and was last given attention {@code days} ago (memory only), so the "ignored" rule
     * can be seen without waiting weeks.
     */
    public static boolean backdate(int botCharId, int days) {
        MemberRow m = members.get(botCharId);
        if (m == null) {
            return false;
        }
        long then = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days);
        members.put(botCharId, new MemberRow(m.charId(), m.guildId(), m.rank(), then, then, m.name(), m.level(), m.job()));
        return true;
    }

    /** The bot quits now with a line in guild chat (when online). */
    public static String quit(int botCharId, GuildRules.QuitReason why, String rivalName) {
        MemberRow m = members.get(botCharId);
        if (m == null) {
            return "not in a guild";
        }
        Character bot = online.apply(botCharId);
        if (bot != null) {
            Map<String, String> tokens = new HashMap<>();
            tokens.put("GUILD", guildName(m.guildId()));
            if (rivalName != null) {
                tokens.put("PLAYER", rivalName);
            }
            sayInGuild(bot, GuildLines.pick(why == GuildRules.QuitReason.RIVALS ? "QuitRivals" : "QuitIgnored", tokens));
        }
        leave(botCharId, why == GuildRules.QuitReason.RIVALS ? "its guild's players are its rivals"
                : "ignored for weeks");
        return m.name() + " quit " + guildName(m.guildId()) + " (" + why.name().toLowerCase(Locale.ROOT) + ")";
    }

    // ── a bot guild's leader kicks a rival player ──

    private static final java.util.Deque<Long> worldKicks = new java.util.ArrayDeque<>();

    /**
     * In a bot-run guild, the leader expels an online player member who is its nemesis, or a rival of most of at least
     * three bot members with feelings about them ({@link GuildRules#kick}), after a whisper saying why. Through the stock expel
     * (the player gets the usual "expelled" result). One kick per guild per {@code kickMinutes} (30) and
     * {@code kicksPerHour} (3) world-wide; {@code force} (GM / !qa) skips both. What happened.
     */
    public static List<String> checkKicks(int guildId, long now, boolean force) {
        List<String> out = new ArrayList<>();
        GuildRow gr = guilds.get(guildId);
        Guild g = gr == null ? null : Server.getInstance().getGuild(guildId);
        if (g == null) {
            out.add("not a bot guild (or not loaded)");
            return out;
        }
        int leaderId = g.getLeaderId();
        GuildCharacter leader = g.getMGC(leaderId);
        if (leader == null || !BotHelpers.isArtificial(leaderId)) {
            out.add("the guild has no bot leader");
            return out;
        }
        List<MemberRow> bots = membersOf(guildId);
        for (GuildCharacter mc : new ArrayList<>(g.getMembers())) {
            int pid = mc.getId();
            if (BotHelpers.isArtificial(pid) || !mc.isOnline() || !BotAffinity.loaded(pid)) {
                continue;
            }
            boolean leaderNemesis = BotAffinity.state(leaderId, pid) == AffinityState.NEMESIS;
            int hostile = 0;
            int known = 0;
            for (MemberRow m : bots) {
                AffinityState st = BotAffinity.state(m.charId(), pid);
                if (st != AffinityState.NEUTRAL) {
                    known++;
                }
                if (st.hostile()) {
                    hostile++;
                }
            }
            if (!GuildRules.kick(leaderNemesis, hostile, known)) {
                continue;
            }
            if (!force && (worldKicksLastHour(now) >= GuildRules.prop("kicksPerHour", 3)
                    || !take("kick:" + guildId, now, GuildRules.kickGapMs()))) {
                out.add("rate-limited: " + mc.getName() + " stays for now");
                qa(0, "KICK HELD " + mc.getName() + " from " + gr.name() + " (rate limit)");
                break;
            }
            noteWorldKick(now);
            Character player = onlinePlayer(pid);
            String why = leaderNemesis ? "KickLeader" : "KickMembers";
            String line = GuildLines.pick(why, Map.of("PLAYER", mc.getName(), "GUILD", gr.name()));
            if (player != null && line != null) {
                Character live = online.apply(leaderId);
                int ch = live != null ? channelOf(live) : channelOf(player);
                BotWhispers.send(player, leader.getName(), ch - 1, line, false);
            }
            Server.getInstance().expelMember(leader, mc.getName(), pid);
            String what = leader.getName() + " kicked " + mc.getName() + " from " + gr.name() + " ("
                    + (leaderNemesis ? "the leader's nemesis" : "rival of " + hostile + "/" + known + " members") + ")";
            out.add(what + (line == null ? "" : ": \"" + line + "\""));
            qa(player == null ? 0 : player.getMapId(), "KICK " + what + (line == null ? "" : ", whisper: " + line));
        }
        if (out.isEmpty()) {
            out.add("nobody to kick in " + gr.name());
        }
        return out;
    }

    private static synchronized int worldKicksLastHour(long now) {
        while (!worldKicks.isEmpty() && now - worldKicks.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
            worldKicks.pollFirst();
        }
        return worldKicks.size();
    }

    private static synchronized void noteWorldKick(long now) {
        worldKicks.addLast(now);
    }

    /**
     * GM / !qa: a player joins a guild now, as the stock accept does (rank 5, saved, tag over the head). False when
     * they are in one, or it is full or gone.
     */
    public static boolean joinPlayer(Character player, int guildId) {
        if (player == null || player.getGuildId() > 0 || player.getMGC() == null) {
            return false;
        }
        Guild g = Server.getInstance().getGuild(guildId, player.getWorld(), null);
        if (g == null) {
            return false;
        }
        player.getMGC().setGuildId(guildId);
        player.getMGC().setGuildRank(5);
        player.getMGC().setAllianceRank(5);
        if (Server.getInstance().addGuildMember(player.getMGC(), player) == 0) {
            player.getMGC().setGuildId(0);
            return false;
        }
        player.sendPacket(GuildPackets.showGuildInfo(player));
        player.saveGuildStatus();
        showGuildOverHead(player, g);
        return true;
    }

    // ── ambient recruiting ──

    /**
     * The ambient megaphone ticker's GuildRecruitment pick: a real bot guild with room recruits, through an online
     * member (the given bot if it is one). {speaker, line}, or null when there is no bot guild to recruit for (then
     * no guild recruitment megaphone goes out at all: never a made-up guild).
     */
    public static Object[] ambientRecruit(Character fallback, Random rng) {
        if (!enabled()) {
            return null;
        }
        List<GuildRow> open = new ArrayList<>();
        for (GuildRow g : guilds.values()) {
            Guild live = Server.getInstance().getGuild(g.guildId());
            if (live == null || live.getMembers().size() < live.getCapacity()) {
                open.add(g);
            }
        }
        if (open.isEmpty()) {
            return null;
        }
        GuildRow pick = null;
        Character speaker = null;
        MemberRow own = fallback == null ? null : members.get(fallback.getId());
        if (own != null && guilds.containsKey(own.guildId()) && open.contains(guilds.get(own.guildId()))) {
            pick = guilds.get(own.guildId());
            speaker = fallback;
        } else {
            java.util.Collections.shuffle(open, rng);
            for (GuildRow g : open) {
                List<Character> on = onlineMembers(g.guildId());
                if (!on.isEmpty()) {
                    pick = g;
                    speaker = on.get(rng.nextInt(on.size()));
                    break;
                }
            }
        }
        if (pick == null) {
            return null;
        }
        GuildRow chosen = pick;
        int minLevel = GuildPlan.SPECS.stream().filter(s -> s.name().equalsIgnoreCase(chosen.name()))
                .mapToInt(GuildPlan.Spec::minLevel).findFirst().orElse(1);
        String line = GuildLines.pick("AmbientRecruit", Map.of("GUILD", pick.name(), "LEVEL",
                Integer.toString(Math.max(1, minLevel)), "STYLE", pick.vibe().label(),
                "WHERE", rng.nextBoolean() ? "fm" : "henesys"));
        return line == null ? null : new Object[]{speaker, line};
    }

    // ── founding ──

    /**
     * Founds the bot guilds when there are none: the roster's bots are assigned ({@link GuildPlan#assign}), each
     * guild gets its stock row and its members, rivals are paired, and online members take their guild at once.
     * The number founded; -1 when it could not run yet (no migration, or the roster is not loaded).
     */
    static int seed(long now, Random rng) {
        if (!store.available()) {
            return 0; // no migration: never seeds (and never tries again this boot)
        }
        if (!guilds.isEmpty()) {
            return 0;
        }
        List<GuildPlan.Candidate> roster = candidates();
        if (roster.size() < 40) {
            return -1;
        }
        return found(GuildPlan.assign(roster, GuildPlan.SPECS, rng), now);
    }

    /** Guildless active roster bots with their stored levels. */
    static List<GuildPlan.Candidate> candidates() {
        Map<Integer, GuildStore.StoredBot> stored = new HashMap<>();
        for (GuildStore.StoredBot b : store.loadBots()) {
            stored.put(b.botId(), b);
        }
        storedBots = stored;
        List<GuildPlan.Candidate> out = new ArrayList<>();
        for (int botId : rosterIds.get()) {
            GuildStore.StoredBot b = stored.get(botId);
            if (b != null && !members.containsKey(BotStore.BOT_ID_BASE + botId)) {
                out.add(new GuildPlan.Candidate(botId, b.level()));
            }
        }
        return out;
    }

    /** Writes the guilds and their members; pairs rivals by name. How many guilds were founded. */
    static int found(List<GuildPlan.Founding> plan, long now) {
        Map<String, Integer> idByName = new HashMap<>();
        int founded = 0;
        for (GuildPlan.Founding f : plan) {
            int leaderChar = BotStore.BOT_ID_BASE + f.leader().botId();
            int gid = store.found(f.spec(), leaderChar, GuildPlan.capacityFor(f.members().size()), now);
            if (gid <= 0) {
                continue;
            }
            founded++;
            idByName.put(f.spec().name(), gid);
            guilds.put(gid, new GuildRow(gid, f.spec().name(), leaderChar, f.spec().vibe(), 0, f.spec().size(), now));
            for (int i = 0; i < f.members().size(); i++) {
                GuildPlan.Candidate c = f.members().get(i);
                int charId = BotStore.BOT_ID_BASE + c.botId();
                GuildStore.StoredBot b = storedBots.get(c.botId());
                MemberRow row = new MemberRow(charId, gid, f.rankAt(i), now - (long) (i * 3_600_000L), now,
                        b == null || b.name() == null ? "bot" + c.botId() : b.name(), c.level(), b == null ? 0 : b.job());
                members.put(charId, row);
                saveMemberLater(row);
            }
            log.info("Founded bot guild {} ({}, {} members)", f.spec().name(), f.spec().vibe().label(), f.members().size());
            qa(0, "FOUNDED " + f.spec().name() + " #" + gid + " (" + f.spec().vibe().label() + ", " + f.members().size() + " members)");
        }
        for (GuildPlan.Founding f : plan) {
            Integer gid = idByName.get(f.spec().name());
            Integer rival = f.spec().rival() == null ? null : idByName.get(f.spec().rival());
            if (gid != null && rival != null) {
                store.setRival(gid, rival);
                guilds.computeIfPresent(gid, (k, g) -> g.withRival(rival));
            }
        }
        for (Integer gid : idByName.values()) { // members online now take their guild
            for (MemberRow m : membersOf(gid)) {
                Character bot = online.apply(m.charId());
                if (bot != null) {
                    try {
                        if (attach(bot, m)) {
                            Guild g = Server.getInstance().getGuild(gid);
                            if (g != null) {
                                showGuildOverHead(bot, g);
                            }
                        }
                    } catch (RuntimeException e) {
                        log.debug("attach {}", bot.getName(), e);
                    }
                }
            }
        }
        return founded;
    }

    /**
     * GM: founds one more bot guild now from guildless roster bots: a name (letters only, 3-12), a vibe, a size.
     * What happened.
     */
    public static String formNow(String name, GuildRules.Vibe vibe, int size, long now) {
        ensureLoaded();
        if (!store.available()) {
            return "migration 014-bot-guilds is not applied: bot guilds cannot be stored";
        }
        if (name == null || !name.matches("[A-Za-z]{3,12}")) {
            return "a guild name is 3-12 letters";
        }
        GuildPlan.Spec spec = new GuildPlan.Spec(name, vibe, Math.max(3, Math.min(40, size)), 1, 200,
                1000 + ThreadLocalRandom.current().nextInt(18), 1 + ThreadLocalRandom.current().nextInt(13),
                2000 + ThreadLocalRandom.current().nextInt(20), 1 + ThreadLocalRandom.current().nextInt(13), null,
                "new guild!");
        List<GuildPlan.Founding> plan = GuildPlan.assign(candidates(), List.of(spec), ThreadLocalRandom.current());
        if (plan.isEmpty()) {
            return "not enough guildless roster bots";
        }
        int n = found(plan, now);
        return n == 0 ? "could not found " + name + " (name taken?)" : "founded " + name + " with "
                + plan.get(0).members().size() + " members";
    }

    // ── chat prompt ──

    /** What the chat prompt adds: the bot's guild. "" when it is in none. */
    public static String promptLine(int botCharId) {
        MemberRow m = members.get(botCharId);
        if (m == null) {
            return "";
        }
        GuildRow g = guilds.get(m.guildId());
        String name = guildName(m.guildId());
        String role = m.rank() == 1 ? "the leader" : m.rank() == 2 ? "an officer" : "a member";
        return " You are " + role + " of the guild " + name + (g == null ? " (a player's guild)."
                : " (a " + g.vibe().label() + " guild" + (g.rivalId() > 0 && guilds.containsKey(g.rivalId())
                ? ", with a friendly rivalry with " + guilds.get(g.rivalId()).name() : "") + ").")
                + " If someone asks to join, the leader decides.";
    }

    // ── GM read-outs ──

    public static List<String> describe() {
        ensureLoaded();
        List<String> out = new ArrayList<>();
        for (GuildRow g : botGuilds()) {
            List<MemberRow> ms = membersOf(g.guildId());
            int on = onlineMembers(g.guildId()).size();
            Guild live = Server.getInstance().getGuild(g.guildId());
            int players = 0;
            if (live != null) {
                for (GuildCharacter mc : live.getMembers()) {
                    if (!BotHelpers.isArtificial(mc.getId())) {
                        players++;
                    }
                }
            }
            String leader = ms.stream().filter(m -> m.rank() == 1).map(MemberRow::name).findFirst().orElse("?");
            out.add("#" + g.guildId() + " " + g.name() + " (" + g.vibe().label() + "): " + ms.size() + " bots ("
                    + on + " online), " + players + " players, leader " + leader
                    + (g.rivalId() > 0 && guilds.containsKey(g.rivalId()) ? ", rival " + guilds.get(g.rivalId()).name() : ""));
        }
        Map<Integer, Integer> playerGuilds = new HashMap<>();
        for (MemberRow m : members.values()) {
            if (!guilds.containsKey(m.guildId())) {
                playerGuilds.merge(m.guildId(), 1, Integer::sum);
            }
        }
        for (Map.Entry<Integer, Integer> e : playerGuilds.entrySet()) {
            out.add("player guild " + guildName(e.getKey()) + " #" + e.getKey() + ": " + e.getValue() + " bot member(s)");
        }
        if (out.isEmpty()) {
            out.add(store.available() ? "no bot guilds yet (founded at the first step with 40+ roster bots)"
                    : "no bot guilds: migration 014-bot-guilds is not applied");
        }
        GuildBossRuns.describe().forEach(out::add);
        return out;
    }

    public static List<String> describeGuild(int guildId) {
        List<String> out = new ArrayList<>();
        for (MemberRow m : membersOf(guildId)) {
            Character c = online.apply(m.charId());
            out.add("r" + m.rank() + " " + m.name() + " lv" + (c != null ? c.getLevel() : m.level())
                    + (c != null ? " online ch" + channelOf(c) + " map " + c.getMapId() : "")
                    + ", joined " + TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - m.joinedAt()) + " d ago"
                    + (memoryOnly.contains(m.charId()) ? " [memory only]" : ""));
        }
        return out;
    }

    /** GM: a recruit round for the guild now (the gate still holds megaphones). */
    public static List<String> recruitNow(GuildRow g) {
        return maybeRecruit(g, System.currentTimeMillis(), ThreadLocalRandom.current(), true);
    }

    /** GM: a jab now: on a shared map if both guilds stand on one, and a megaphone jab. */
    public static List<String> jabNow(GuildRow g) {
        List<String> out = new ArrayList<>();
        GuildRow r = guilds.get(g.rivalId());
        if (r == null) {
            out.add(g.name() + " has no rival");
            return out;
        }
        boolean met = mapJab(g, onlineMembers(g.guildId()), r, onlineMembers(r.guildId()), ThreadLocalRandom.current());
        out.add(met ? "map jab said (see the map)" : "no member of " + g.name() + " and " + r.name()
                + " share a map a player sees");
        out.add(megaJab(g, ThreadLocalRandom.current()));
        out.add(claim(g, ThreadLocalRandom.current()));
        return out;
    }

    /**
     * GM / !qa gw-1: members of two guilds standing on one map trade jabs now (a war's skirmish). What happened.
     * Works for player guilds too when bots of both stand together; the rival link is not needed.
     */
    public static String mapJabNow(int guildA, int guildB) {
        GuildRow a = guilds.get(guildA);
        GuildRow b = guilds.get(guildB);
        if (a == null || b == null) {
            return "skirmish: both sides must be bot guilds";
        }
        boolean met = mapJab(a, onlineMembers(guildA), b, onlineMembers(guildB), ThreadLocalRandom.current());
        return met ? "skirmish: " + a.name() + " and " + b.name() + " trade jabs on the map"
                : "skirmish: no member of " + a.name() + " and " + b.name() + " share a map a player sees";
    }

    // ── helpers ──

    static boolean take(String key, long now, long gapMs) {
        Long next = nextAt.get(key);
        if (next != null && now < next) {
            return false;
        }
        nextAt.put(key, now + gapMs);
        return true;
    }

    /** Not busy with something a party or channel change would break. */
    static boolean free(Character bot) {
        BotSM sm = CharacterStorage.getBotById(bot.getId());
        return sm != null && BotPopulationDirector.mayLogOut(sm) && bot.getEventInstance() == null
                && (sm instanceof TrainingBot || sm instanceof SocialBot);
    }

    static boolean realPlayerSees(Character bot) {
        try {
            if (!GCMovement.isMapObserved(bot.getMapId())) {
                return false;
            }
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

    static List<Character> realPlayers() {
        List<Character> out = new ArrayList<>();
        try {
            net.server.world.World w = SoloMaplingUtilities.world();
            if (w == null) {
                return out;
            }
            for (Character c : w.getPlayerStorage().getAllCharacters()) {
                if (!BotHelpers.isBot(c) && c.isLoggedinWorld() && BotHelpers.isVisibleRealPlayer(c)) {
                    out.add(c);
                }
            }
        } catch (RuntimeException e) {
            log.debug("players", e);
        }
        return out;
    }

    static Character onlinePlayer(int playerId) {
        try {
            net.server.world.World w = SoloMaplingUtilities.world();
            Character c = w == null ? null : w.getPlayerStorage().getCharacterById(playerId);
            return c == null || BotHelpers.isBot(c) ? null : c;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static int channelOf(Character chr) {
        try {
            return chr.getClient().getChannel();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static List<Integer> activeRoster() {
        List<Integer> out = new ArrayList<>();
        for (BotRoster.Member m : BotRoster.members()) {
            if (m.status() == BotRoster.Status.ACTIVE) {
                out.add(m.botId());
            }
        }
        return out;
    }

    private static Character liveBot(int charId) {
        BotSM sm = CharacterStorage.getBotById(charId);
        return sm == null ? null : sm.getChr();
    }

    static Character live(int charId) {
        return online.apply(charId);
    }

    static void qa(int mapId, String line) {
        if (QaLog.on(QaLog.Topic.BOT_GUILD)) { // !qa gu-1 .. gu-7
            QaLog.map(QaLog.Topic.BOT_GUILD, mapId, line);
        }
    }

    // ── test seams ──

    /** Tests: a store and an online lookup (nulls restore the real ones); forgets everything. */
    static void useForTest(GuildStore testStore, IntFunction<Character> testOnline,
                           java.util.function.Supplier<List<Integer>> testRoster) {
        store = testStore != null ? testStore : new GuildStore.Jdbc();
        rosterIds = testRoster != null ? testRoster : BotGuilds::activeRoster;
        storedBots = Map.of();
        online = testOnline != null ? testOnline : BotGuilds::liveBot;
        guilds.clear();
        members.clear();
        memoryOnly.clear();
        nextAt.clear();
        whispered.clear();
        lastAnswer.clear();
        deciding.clear();
        synchronized (BotGuilds.class) {
            worldWhispers.clear();
            worldKicks.clear();
        }
        loaded = false;
        loadTriedAt = 0;
        seedTried = false;
        gpRescaled = false;
        lastStepAt = 0;
        writer = testStore != null ? Runnable::run : ExecutorServiceManager::runAsync;
    }

    /** Tests: the next load attempt runs at once (no wait after a failed one). */
    static void retryLoadForTest() {
        loadTriedAt = 0;
    }

    /** Tests: where membership writes run (null = a worker thread). */
    static void useWriterForTest(java.util.concurrent.Executor testWriter) {
        writer = testWriter != null ? testWriter : ExecutorServiceManager::runAsync;
    }

    /** Tests: put a member row in memory. */
    static void putForTest(MemberRow row) {
        members.put(row.charId(), row);
    }
}
