package soloMapling.ArtificialPlayer.BotAffinitySystem;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soloMapling.ArtificialPlayer.PlayerBusy;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatDecide;
import soloMapling.ArtificialPlayer.BotBrainSystem.ChatTriage;
import soloMapling.ArtificialPlayer.BotBuddies;
import soloMapling.ArtificialPlayer.BotConversation;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotAttackSystem.BotBuffEffects;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotStore;
import soloMapling.ArtificialPlayer.BotTypes.SocialBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;
import soloMapling.server.BotTiming;
import soloMapling.server.ExecutorServiceManager;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;

/**
 * Ticket 58: how a bot's feelings show. Only through behaviour - never a number, never a label.
 *
 * <p><b>Friends</b> greet the player, now and then send a buddy request or a party invite, and help (a buff they
 * really have). <b>Rivals and nemeses</b> trash talk, KS the player's mobs for a little while (only mobs within their
 * own reach on the map they are already on), refuse trades, parties and buddy requests, and a nemesis may defame.
 *
 * <p><b>Never harassment.</b> Everything happens only when the bot already stands near the player (the roaming bots'
 * player scan, observed maps only); a bot never changes map for a player. Each action has a per-pair cooldown and a
 * world-wide hourly cap, at most one action per sighting, a defame at most once a day per player across all bots.
 * {@code @botdrama off} removes every hostile action for that player, {@code -Dsolomapling.affinity.hostility=false}
 * for everyone.
 *
 * <p>Cooldowns ({@code -Dsolomapling.affinity.<key>Min}): greet 120, invite 180, buddy 1440, help 15, taunt 20,
 * ks 15, defame 1440 minutes. World caps per hour ({@code ...<key>PerHour}): taunts 20, KS sprees 10, invites 6.
 */
public final class AffinityReactions {

    private static final Logger log = LoggerFactory.getLogger(AffinityReactions.class);

    static final String PACK = "AffinityDialogue.yaml";
    static final String ROOT = "Affinity";
    /** How long a KS spree lasts, and how many of the player's mobs it takes at most. */
    static final long KS_SPREE_MS = TimeUnit.SECONDS.toMillis(90);
    static final int KS_SPREE_MOBS = 3;
    /** A KS target must be this close to the bot (it never walks across the map for it). */
    static final int KS_REACH_X = 350, KS_REACH_Y = 90;

    /** Party buffs a friendly bot may give, when it really holds the skill. */
    static final int[] HELP_BUFFS = {2301004 /* Bless */, 2311003 /* Holy Symbol */, 4101004, 4201003 /* Haste */,
            1301007 /* Hyper Body */, 2101001, 2201001 /* Meditation */, 3121002, 3221002 /* Sharp Eyes */};

    enum Action { GREET, INVITE, BUDDY, HELP, TAUNT, KS, DEFAME }

    private static final Map<Long, Long> nextAt = new ConcurrentHashMap<>();
    private static final Map<Action, Deque<Long>> worldRecent = new ConcurrentHashMap<>();
    /** Per player: the day of the last bot defame they got. */
    private static final Map<Integer, Long> defamedDay = new ConcurrentHashMap<>();
    /** Per bot: the KS spree it is on. */
    private static final Map<Integer, KsSpree> sprees = new ConcurrentHashMap<>();
    private static final Map<Long, Long> lastRefuseLine = new ConcurrentHashMap<>();
    private static volatile long lastGossipLine;

    /** A KS spree; {@code war}: called in by a rival for a KS war (1.5.0 bot drama), not the bot's own grudge. */
    record KsSpree(int playerId, int mapId, long until, int[] left, boolean war) {
        KsSpree(int playerId, int mapId, long until, int[] left) {
            this(playerId, mapId, until, left, false);
        }
    }

    private AffinityReactions() {
    }

    // ── settings ──

    static long cooldownMs(Action a) {
        long min = switch (a) {
            case GREET -> 120;
            case INVITE -> 180;
            case BUDDY -> 1440;
            case HELP -> 15;
            case TAUNT -> 20;
            case KS -> 15;
            case DEFAME -> 1440;
        };
        return TimeUnit.MINUTES.toMillis(Long.getLong("solomapling.affinity." + a.name().toLowerCase() + "Min", min));
    }

    static int perHour(Action a) {
        int cap = switch (a) {
            case TAUNT -> 20;
            case KS -> 10;
            case INVITE -> 6;
            default -> 1000;
        };
        return Integer.getInteger("solomapling.affinity." + a.name().toLowerCase() + "PerHour", cap);
    }

    private static long key(Action a, int botCharId, int playerId) {
        return ((long) a.ordinal() << 58) ^ ((long) (botCharId - BotStore.BOT_ID_BASE) << 30) ^ playerId;
    }

    /** True (and the cooldown starts) when this pair may do this now and the world cap has room. */
    static boolean take(Action a, int botCharId, int playerId, long now) {
        long k = key(a, botCharId, playerId);
        Long next = nextAt.get(k);
        if (next != null && now < next) {
            return false;
        }
        Deque<Long> recent = worldRecent.computeIfAbsent(a, x -> new ArrayDeque<>());
        synchronized (recent) {
            while (!recent.isEmpty() && now - recent.peekFirst() > TimeUnit.HOURS.toMillis(1)) {
                recent.pollFirst();
            }
            if (recent.size() >= perHour(a)) {
                return false;
            }
            recent.addLast(now);
        }
        nextAt.put(k, now + cooldownMs(a));
        if (nextAt.size() > 20_000) {
            nextAt.values().removeIf(t -> t < now);
        }
        return true;
    }

    // ── on sight ──

    /**
     * A roaming bot noticed a real player nearby (driver thread; memory only). If it feels something about them it
     * may do one thing about it, off the driver thread. True when it reacted (the caller skips its usual line).
     */
    public static boolean onSight(BotSM botSM, Character player) {
        if (botSM == null || player == null || botSM.getChr() == null || !BotAffinity.hasTies(player.getId())
                || !BotHelpers.isVisibleRealPlayer(player)) {
            return false;
        }
        Character bot = botSM.getChr();
        AffinityState state = BotAffinity.state(bot.getId(), player.getId());
        if (state == AffinityState.NEUTRAL || bot.getMapId() != player.getMapId()) {
            return false;
        }
        long now = BotAffinity.now();
        Action action = state == AffinityState.FRIEND ? pickFriendly(botSM, bot, player, now)
                : pickHostile(botSM, bot, player, state, now);
        if (action == null) {
            return false;
        }
        ExecutorServiceManager.runAsync(() -> {
            try {
                act(action, botSM, bot, player, state);
            } catch (RuntimeException e) {
                log.debug("Affinity action {} failed for {}", action, bot.getName(), e);
            }
        });
        return true;
    }

    /** The one friendly thing to do now, or null. The order is the rarest first. */
    static Action pickFriendly(BotSM botSM, Character bot, Character player, long now) {
        double roll = ThreadLocalRandom.current().nextDouble();
        boolean buddies = player.getBuddylist() != null && player.getBuddylist().contains(bot.getId());
        if (!buddies && roll < 0.35 && take(Action.BUDDY, bot.getId(), player.getId(), now)) {
            return Action.BUDDY;
        }
        if (canInvite(botSM, bot, player) && roll < 0.5 && take(Action.INVITE, bot.getId(), player.getId(), now)) {
            return Action.INVITE;
        }
        if (helpBuff(bot) != 0 && take(Action.HELP, bot.getId(), player.getId(), now)) {
            return Action.HELP;
        }
        if (!BotConversation.isTalking(bot.getId(), player.getId())
                && take(Action.GREET, bot.getId(), player.getId(), now)) {
            return Action.GREET;
        }
        return null;
    }

    /** The one petty thing to do now, or null. */
    static Action pickHostile(BotSM botSM, Character bot, Character player, AffinityState state, long now) {
        if (!BotAffinity.hostilityEnabled() || BotAffinity.optedOut(player.getId())) {
            return null;
        }
        double roll = ThreadLocalRandom.current().nextDouble();
        if (state == AffinityState.NEMESIS && roll < 0.25 && mayDefame(player.getId(), now)
                && take(Action.DEFAME, bot.getId(), player.getId(), now)) {
            return Action.DEFAME;
        }
        if (botSM instanceof TrainingBot && roll < 0.6 && take(Action.KS, bot.getId(), player.getId(), now)) {
            return Action.KS;
        }
        if (take(Action.TAUNT, bot.getId(), player.getId(), now)) {
            return Action.TAUNT;
        }
        return null;
    }

    private static boolean canInvite(BotSM botSM, Character bot, Character player) {
        return (botSM instanceof TrainingBot || botSM instanceof SocialBot) && player.getParty() == null
                && (bot.getParty() == null || bot.getParty().getLeaderId() == bot.getId()
                && bot.getParty().getMembers().size() < 6);
    }

    private static void act(Action action, BotSM botSM, Character bot, Character player, AffinityState state) {
        if (PlayerBusy.isBusy(player)) {
            return;
        }
        String detail = switch (action) {
            case GREET -> greet(botSM, bot, player);
            case BUDDY -> BotBuddies.offerAsFriend(bot.getId(), player.getId());
            case INVITE -> invite(bot, player);
            case HELP -> help(bot, player);
            case TAUNT -> { // 1.5.0 bot drama: a roast from the player's real game facts (game only)
                String roast = soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.enabled()
                        ? soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.roast(bot, player, state) : null;
                yield roast != null ? roast : say(bot, state == AffinityState.NEMESIS ? "NemesisTaunt" : "RivalTaunt",
                        player.getName());
            }
            case KS -> startSpree(bot, player);
            case DEFAME -> defame(bot, player);
        };
        if (QaLog.on(QaLog.Topic.AFFINITY)) { // !qa af-1, af-2
            QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "ACT " + action.name().toLowerCase() + " " + bot.getName()
                    + " (" + state.label() + ") -> " + player.getName() + ": " + detail);
        }
    }

    private static String greet(BotSM botSM, Character bot, Character player) {
        if (BotConversation.greet(botSM, player)) { // through the chat brain: the player's answer reaches the bot
            return "greeted (chat opened)";
        }
        return say(bot, "FriendHello", player.getName());
    }

    private static String invite(Character bot, Character player) {
        boolean hadParty = bot.getParty() != null;
        if (!BotPartyCommands.botInvitePlayer(bot, player)) {
            return "invite refused by the party system";
        }
        say(bot, "FriendInvite", player.getName());
        if (!hadParty) {
            // The bot made a party just to invite them: if they never join, it leaves it again.
            BotTiming.after(TimeUnit.SECONDS.toMillis(75), () -> {
                if (bot.getParty() != null && bot.getParty().getMembers().size() <= 1) {
                    BotPartyCommands.botLeaveParty(bot);
                }
            });
        }
        return "invited to a party";
    }

    /** A party buff the bot really holds, or 0. */
    static int helpBuff(Character bot) {
        try {
            for (int id : HELP_BUFFS) {
                if (bot.getSkillLevel(id) > 0) {
                    return id;
                }
            }
        } catch (RuntimeException e) {
            return 0;
        }
        return 0;
    }

    private static String help(Character bot, Character player) {
        int skill = helpBuff(bot);
        if (skill == 0) {
            return "no buff to give";
        }
        BotBuffEffects.givePartyBuff(bot, skill, List.of(player));
        if (ThreadLocalRandom.current().nextBoolean()) {
            say(bot, "FriendHelp", player.getName());
        }
        return "buffed with skill " + skill;
    }

    // ── KS sprees ──

    private static String startSpree(Character bot, Character player) {
        sprees.put(bot.getId(), new KsSpree(player.getId(), bot.getMapId(), BotAffinity.now() + KS_SPREE_MS,
                new int[]{KS_SPREE_MOBS}));
        soloMapling.ArtificialPlayer.BotDramaSystem.BotRivalry.arm(bot, player); // 1.5.0: it may follow once for revenge
        if (ThreadLocalRandom.current().nextDouble() < 0.5) {
            say(bot, "RivalTaunt", player.getName());
        }
        return "ks spree for " + TimeUnit.MILLISECONDS.toSeconds(KS_SPREE_MS) + " s, up to " + KS_SPREE_MOBS + " mobs";
    }

    /**
     * The grind brain picked {@code picked}; a bot on a KS spree takes a mob its rival is fighting instead, when one
     * is within its own short reach on the same map (it never chases across the map, and never another map).
     * Combat thread; memory only. Returns the mob to fight.
     */
    public static server.life.Monster ksPick(Character bot, server.life.Monster picked) {
        if (sprees.isEmpty() || bot == null) {
            return picked;
        }
        KsSpree spree = sprees.get(bot.getId());
        if (spree == null) {
            return picked;
        }
        long now = BotAffinity.now();
        boolean still = spree.war() ? BotAffinity.hostilityEnabled() && !BotAffinity.optedOut(spree.playerId())
                : BotAffinity.hostile(bot.getId(), spree.playerId());
        if (now > spree.until() || spree.left()[0] <= 0 || bot.getMapId() != spree.mapId() || !still) {
            sprees.remove(bot.getId());
            return picked;
        }
        Character rival = bot.getMap() == null ? null : bot.getMap().getCharacterById(spree.playerId());
        java.awt.Point pos = bot.getPosition();
        if (rival == null || pos == null) {
            return picked;
        }
        server.life.Monster best = null;
        double bestSq = Double.MAX_VALUE;
        for (server.maps.MapObject mo : bot.getMap().getMapObjectsInRange(pos, (double) KS_REACH_X * KS_REACH_X,
                List.of(server.maps.MapObjectType.MONSTER))) {
            server.life.Monster m = (server.life.Monster) mo;
            java.awt.Point mp = m.getPosition();
            if (!m.isAlive() || mp == null || Math.abs(mp.y - pos.y) > KS_REACH_Y || !m.isAttackedBy(rival)
                    || m.isAttackedBy(bot)) {
                continue;
            }
            double d = pos.distanceSq(mp);
            if (d < bestSq) {
                bestSq = d;
                best = m;
            }
        }
        if (best == null || (picked != null && picked.getObjectId() == best.getObjectId())) {
            return picked;
        }
        spree.left()[0]--;
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "KS " + bot.getName() + " takes " + best.getName()
                    + " (oid " + best.getObjectId() + ") from " + rival.getName() + ", " + spree.left()[0] + " left");
        }
        if (spree.left()[0] == 0 && ThreadLocalRandom.current().nextDouble() < 0.4) {
            String name = rival.getName();
            ExecutorServiceManager.runAsync(() -> say(bot, "KsAnnounce", name));
        }
        return best;
    }

    // ── defame ──

    /** The lowest fame a defame takes a player to (v83's own floor; the game clamps there too). */
    static final int FAME_FLOOR = -30000;

    /** The server's zone for "a day" (the roster's, America/Los_Angeles by default). */
    static java.time.ZoneId zone() {
        try {
            String z = config.YamlConfig.config.server.BOT_ROSTER_TIMEZONE;
            return z == null || z.isBlank() ? java.time.ZoneId.of("America/Los_Angeles") : java.time.ZoneId.of(z);
        } catch (RuntimeException | LinkageError e) { // no config (tests, tools): the roster's default
            return java.time.ZoneId.of("America/Los_Angeles");
        }
    }

    /** When the server's local day holding {@code now} began (epoch ms). */
    static long dayStart(long now, java.time.ZoneId zone) {
        return java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli();
    }

    /** Cheap pre-check on the driver thread (memory only); {@link #defame} makes the durable claim. */
    static boolean mayDefame(int playerId, long now) {
        Long day = defamedDay.get(playerId);
        return day == null || day < dayStart(now, zone());
    }

    /**
     * A nemesis defames: at most once per player per server day across all bots (claimed durably and atomically in
     * the store, so a restart or two bots at once cannot double it). Fame may go negative, as in v83, down to the
     * game's own floor ({@link #FAME_FLOOR}).
     */
    static String defame(Character bot, Character player) {
        long now = BotAffinity.now();
        if (!mayDefame(player.getId(), now) || player.getMapId() != bot.getMapId()) {
            return "skipped";
        }
        if (player.getFame() <= FAME_FLOOR) {
            return "fame at its floor: no defame";
        }
        long start = dayStart(now, zone());
        if (!BotAffinity.claimDefame(player.getId(), start, now)) {
            defamedDay.put(player.getId(), start);
            return "a bot defamed them today already";
        }
        defamedDay.put(player.getId(), start);
        if (!player.gainFame(-1, bot, 0)) {
            return "fame already at its floor";
        }
        if (ThreadLocalRandom.current().nextBoolean()) {
            say(bot, "Defame", player.getName());
        }
        return "defamed (-1)";
    }

    // ── refusals ──

    /** A bot weighing a trade request: a rival or nemesis says no (a curt line, at most once per 10 min). */
    public static boolean refusesTrade(Character bot, Character partner) {
        if (bot == null || partner == null || !BotAffinity.hostile(bot.getId(), partner.getId())) {
            return false;
        }
        long now = BotAffinity.now();
        long k = ((long) bot.getId() << 32) ^ partner.getId();
        Long last = lastRefuseLine.get(k);
        if (last == null || now - last > TimeUnit.MINUTES.toMillis(10)) {
            lastRefuseLine.put(k, now);
            soloMapling.server.BoundedMaps.prune(lastRefuseLine, 1_000, 5_000,
                    at -> now - at > TimeUnit.MINUTES.toMillis(10));
            ExecutorServiceManager.runAsync(() -> say(bot, "Refuse", partner.getName()));
        }
        if (QaLog.on(QaLog.Topic.AFFINITY)) {
            QaLog.map(QaLog.Topic.AFFINITY, bot.getMapId(), "REFUSE trade " + bot.getName() + " -> " + partner.getName());
        }
        return true;
    }

    // ── chat ──

    /** A player's line to a bot: its tone moves the bot's feelings (off the game thread, never blocks the reply). */
    public static void judgeLine(Character bot, Character player, String line, ChatTriage.Result triage) {
        if (bot == null || player == null || !BotAffinity.loaded(player.getId())) {
            return;
        }
        ChatTone.of(line, triage, ChatDecide.get()).thenAccept(tone -> {
            switch (tone) {
                case KIND -> BotAffinity.record(bot, player, AffinityEvent.KIND_CHAT);
                case RUDE -> {
                    int delta = AffinityEvent.RUDE_CHAT.delta();
                    if (triage != null && triage.con() == ChatTriage.Con.HARASSMENT) {
                        delta *= 2;
                    }
                    BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.RUDE_CHAT, delta, player.getName(),
                            bot.getName(), player.getMapId());
                }
                case PLAIN -> {
                }
            }
        });
    }

    /**
     * Ticket 59: the same, with the tone ticket 59's one Decide call already read (no second model call). A flirty
     * line counts as kind only from a player the bot may flirt with (romance on); otherwise it moves nothing.
     */
    public static void judgeTone(Character bot, Character player, ChatTone.Tone tone, boolean flirty,
                                 ChatTriage.Result triage) {
        if (bot == null || player == null || !BotAffinity.loaded(player.getId())) {
            return;
        }
        if (flirty) {
            if (soloMapling.ArtificialPlayer.BotDramaSystem.BotRomance.mayFlirtWith(bot.getId(), player.getId())) {
                BotAffinity.record(bot, player, AffinityEvent.KIND_CHAT);
            }
            return;
        }
        switch (tone) {
            case KIND -> BotAffinity.record(bot, player, AffinityEvent.KIND_CHAT);
            case RUDE -> {
                int delta = AffinityEvent.RUDE_CHAT.delta();
                if (triage != null && triage.con() == ChatTriage.Con.HARASSMENT) {
                    delta *= 2;
                }
                BotAffinity.record(bot.getId(), player.getId(), AffinityEvent.RUDE_CHAT, delta, player.getName(),
                        bot.getName(), player.getMapId());
            }
            case PLAIN -> {
            }
        }
    }

    /** What the chat prompt adds about the bot's feelings: a sentence, or "". Never a number. */
    public static String promptLine(int botCharId, Character player) {
        if (player == null) {
            return "";
        }
        return switch (BotAffinity.state(botCharId, player.getId())) {
            case FRIEND -> " You and " + player.getName() + " are friends: you have partied and helped each other, so"
                    + " be warm and glad to see them.";
            case RIVAL -> " You have some beef with " + player.getName() + " (they KS'd you or took your stuff): be"
                    + " curt and a bit salty, light teasing is fine, never insults about real life, no threats.";
            case NEMESIS -> " " + player.getName() + " is your nemesis in the game (they keep KSing you): be cold and"
                    + " petty, a short jab is fine, never insults about real life, no threats, no slurs.";
            case NEUTRAL -> "";
        };
    }

    // ── gossip ──

    /**
     * A bot just passed a note about a player to another bot. If it is an affinity note and a real player (not the
     * one it is about) is on the map to overhear, the teller sometimes says it out loud. At most once per
     * {@code solomapling.affinity.gossipLineMin} (10) minutes world-wide.
     */
    public static void maybeMentionGossip(Character teller, Character listener, int aboutCharId, String text, Random rng) {
        AffinityEvent kind = BotAffinity.hearsayOf(text);
        if (kind == null || teller == null || teller.getMap() == null || rng.nextDouble() > 0.5) {
            return;
        }
        long now = BotAffinity.now();
        if (now - lastGossipLine < TimeUnit.MINUTES.toMillis(Long.getLong("solomapling.affinity.gossipLineMin", 10))) {
            return;
        }
        boolean audience = false;
        for (Character c : teller.getMap().getAllPlayers()) {
            if (c.getId() == aboutCharId) {
                return; // talk behind their back, not in front of them
            }
            if (!BotHelpers.isBot(c) && BotHelpers.isVisibleRealPlayer(c)) {
                audience = true;
            }
        }
        if (!audience || (kind == AffinityEvent.HEARSAY_BAD && BotAffinity.optedOut(aboutCharId))) {
            return;
        }
        String name = BotBuddies.nameOf(aboutCharId);
        if (name == null) {
            return;
        }
        lastGossipLine = now;
        String line = pick(kind == AffinityEvent.HEARSAY_BAD ? "GossipBad" : "GossipGood", Map.of("OTHER", name));
        if (line != null) {
            ExecutorServiceManager.runAsync(() -> BotSpeak(teller, line));
            if (QaLog.on(QaLog.Topic.AFFINITY)) {
                QaLog.map(QaLog.Topic.AFFINITY, teller.getMapId(), "GOSSIP " + teller.getName() + " to "
                        + (listener == null ? "?" : listener.getName()) + " about " + name + ": " + line);
            }
        }
    }

    // ── lines ──

    static String say(Character bot, String node, String playerName) {
        String line = pick(node, Map.of("PLAYER", playerName == null ? "" : playerName));
        if (line == null || bot == null) {
            return "no line";
        }
        BotSpeak(bot, line);
        return "said \"" + line + "\"";
    }

    static String pick(String node, Map<String, String> tokens) {
        BotDialogueHandler.DialogueConstructor dialog = BotDialogueHandler.getDialogueCon(PACK, ROOT, node);
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

    /**
     * 1.5.0 bot drama: a bot called into a KS war takes up to {@code mobs} of the player's mobs within its own reach for
     * {@code ms} (the same rules as a grudge spree, but it needs no grudge of its own; {@code @botdrama off} still ends
     * it).
     */
    public static void startWarSpree(Character bot, Character player, long ms, int mobs) {
        sprees.put(bot.getId(), new KsSpree(player.getId(), player.getMapId(), BotAffinity.now() + ms, new int[]{mobs},
                true));
    }

    /** 1.5.0 bot drama: the bot's KS spree ends now. */
    public static void stopSpree(int botCharId) {
        sprees.remove(botCharId);
    }

    /** GM: put a bot on a KS spree against a player now (no cooldown). */
    public static void forceSpree(Character bot, Character player) {
        startSpree(bot, player);
    }

    /** Whether a bot is on a KS spree right now (GM display). */
    public static String spreeOf(int botCharId) {
        KsSpree s = sprees.get(botCharId);
        if (s == null || BotAffinity.now() > s.until()) {
            return null;
        }
        return "on a KS spree against " + s.playerId() + " (" + s.left()[0] + " mobs left, "
                + TimeUnit.MILLISECONDS.toSeconds(s.until() - BotAffinity.now()) + " s)";
    }

    /** Tests: forget every cooldown, cap and spree. */
    static void resetForTest() {
        nextAt.clear();
        worldRecent.clear();
        defamedDay.clear();
        sprees.clear();
        lastRefuseLine.clear();
        lastGossipLine = 0;
    }
}
