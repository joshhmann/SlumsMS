package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import client.QuestStatus;
import config.YamlConfig;
import net.server.channel.Channel;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scripting.event.EventManager;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionType;
import server.maps.MapleMap;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotProgressionSystem.RebirthBosses;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;
import soloMapling.ArtificialPlayer.BotTypes.TrainingBot;
import soloMapling.qa.QaLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage.getBotById;

/**
 * 1.6.0 (owner, 2026-10-26): the bots in a real player's party join that player's boss expedition with him. Before
 * this no bot could join any expedition (Zakum, Horntail, Scarga, Pink Bean, Balrog, Showa, Papulatus, CWKPQ...):
 * each member registers by talking to the recruiter NPC ({@code expedition.addMember(cm.getPlayer())}), which a bot
 * never does. Party quests already take bots through {@code Party.getEligibleMembers()}.
 *
 * <p>When the player creates the expedition, talks to the recruiter again (every NPC step reads
 * {@code cm.getExpedition}, so "Start the fight!" sees them before its minimum-size check) and when it starts, each
 * party bot that is his (his companion or his own {@code @botme} alt), stands on the recruiting map, fits the
 * expedition's level band and member quest and has a boss-log entry left is registered, up to the maximum size. A bot
 * of another player in the same party, or one outside the party, never joins. Joined bots stay committed
 * ({@link soloMapling.ArtificialPlayer.BotCommitments}) until the run ends, follow the player in and fight next to him
 * ({@code FollowerFight}); the boss log, rewards and the lacking-team rules treat them as any member.
 */
public final class ExpeditionBots {

    private static final Logger log = LoggerFactory.getLogger(ExpeditionBots.class);

    private ExpeditionBots() {
    }

    /**
     * 1.6.4 (owner, 2026-11-03): a bot may captain an expedition. ON by default - the owner said yes - and with it on
     * the only thing that changes is the refusal below: a bot-led expedition's party bots now register, and a
     * bot-led run can be started by {@link #startAsCaptain} through the same {@code EventManager.startInstance} the
     * recruiter NPC uses. A real player still outranks a bot ({@link #preferredLeader}). With it off the door is
     * exactly what it was: a bot leader registers nobody.
     */
    public static final String CAPTAIN_SWITCH = "slums.exped.botcaptain";

    /**
     * 1.6.4 (owner, 2026-11-03): an owned alt follows its owner into any expedition the owner is a member of, whoever
     * leads it. ON by default. With it off an alt is admitted only by the 1.6.0 rule (its owner leads the
     * expedition). An alt counts as a party member once admitted: {@code Expedition.members} is what every size gate
     * and display reads, and {@link #skip} is the only place that decided otherwise.
     */
    public static final String ALT_FOLLOW_SWITCH = "slums.exped.altfollow";

    public static boolean captainsAllowed() {
        return !"false".equalsIgnoreCase(System.getProperty(CAPTAIN_SWITCH, "true").trim());
    }

    public static boolean altFollowAllowed() {
        return !"false".equalsIgnoreCase(System.getProperty(ALT_FOLLOW_SWITCH, "true").trim());
    }

    /** Bot id -> the expedition whose boss-log quota it had used up: asked once, not on every NPC step. */
    private static final Map<Integer, Expedition> quotaFull = new ConcurrentHashMap<>();
    /** Bot id -> the expedition it could not be made to follow for (mid-trade, companion cap): told once. */
    private static final Map<Integer, Expedition> busy = new ConcurrentHashMap<>();

    /** Boss-log quota of a bot for this expedition (a DB read: only after every cheap check passed). */
    interface Quota {
        boolean hasEntry(Character bot, Expedition exped);
    }

    static volatile Quota quota = (bot, exped) -> ExpeditionBossLog.attemptBoss(bot.getId(),
            exped.getRecruitingMap().getChannelServer().getId(), exped, false);

    /** Expeditions whose leader was already told the Chaos bot quota is spent (once each, not on every NPC step). */
    private static final java.util.Set<Expedition> quotaTold = ConcurrentHashMap.newKeySet();

    /** What decides whether one party bot may join: read off the live characters by {@link #factsOf}. */
    record Facts(boolean bot, boolean member, boolean banned, boolean dead, boolean sameMap, int level, int ownerId,
                 boolean soleRealPlayer, boolean questOk, boolean alt, boolean ownerMember) {
    }

    /**
     * Registers the leader's eligible party bots in his expedition (registration open only). Returns the names that
     * joined now; each is told to the leader: "[Expedition] Clock4 joined with you."
     */
    public static List<String> autoJoin(Character leader, Expedition exped) {
        if (leader == null || exped == null || !exped.isRegistering() || !exped.isLeader(leader)
                || (!captainsAllowed() && BotHelpers.isBot(leader)) || excluded(exped.getType())) {
            return List.of();
        }
        Party party = leader.getParty();
        if (party == null) {
            return List.of();
        }
        List<Character> picked = new ArrayList<>();
        for (PartyCharacter pc : party.getMembers()) {
            Character bot = pc == null || pc.getId() == leader.getId() ? null : pc.getPlayer();
            if (bot == null || !BotHelpers.isBot(bot)) {
                continue; // offline, or a real player (he registers himself)
            }
            String why = skip(exped.getType(), leader.getId(), factsOf(leader, exped, party, bot));
            if (why == null && quotaFull.get(bot.getId()) == exped) {
                why = "quota";
            }
            if (why == null && !quota.hasEntry(bot, exped)) {
                quotaFull.put(bot.getId(), exped);
                why = "quota";
            }
            if (why == null && busy.get(bot.getId()) == exped) {
                why = "busy";
            }
            if (why == null && !follows(bot, leader)) {
                busy.put(bot.getId(), exped);
                why = "busy";
            }
            if (why != null) {
                if (!"member".equals(why) && QaLog.on(QaLog.Topic.PQ)
                        && QaLog.every("exped-skip:" + bot.getId() + ":" + why, 30_000)) { // every NPC step asks
                    qa(exped, "EXPED BOT SKIP " + bot.getName() + " -> " + exped.getType() + ": " + why);
                }
                continue;
            }
            picked.add(bot);
        }
        boolean quotaRefused = capChaosBots(exped, picked);
        List<String> joined = register(leader, exped, picked);
        if (quotaRefused && quotaTold.add(exped)) {
            leader.dropMessage(6, "[Expedition] " + CHAOS_QUOTA_TEXT);
        }
        return joined;
    }

    /** What the leader reads when the bots' shared daily Chaos Zakum runs are gone. */
    static final String CHAOS_QUOTA_TEXT = "The regulars have used all " + ExpeditionBossLog.CHAOS_ZAKUM_BOT_DAILY_RUNS
            + " of today's Chaos Zakum runs, so no more regulars will join you.";

    /**
     * Chaos Zakum lets roster bots in {@link ExpeditionBossLog#CHAOS_ZAKUM_BOT_DAILY_RUNS} times a day, all together,
     * and the log row is written when the run starts. So bots already in this expedition count against what is left,
     * and the bots picked now are cut to the rest. True when the quota refused any bot (cut now, or spent already).
     */
    static boolean capChaosBots(Expedition exped, List<Character> picked) {
        if (exped.getType() != ExpeditionType.CHAOS_ZAKUM || !YamlConfig.config.server.USE_ENABLE_DAILY_EXPEDITIONS) {
            return quotaFull.containsValue(exped);
        }
        int in = 0;
        for (Integer id : exped.getMembers().keySet()) {
            if (BotHelpers.isArtificial(id)) {
                in++;
            }
        }
        int left = Math.max(0, ExpeditionBossLog.chaosBotEntriesLeft() - in);
        boolean cut = false;
        while (picked.size() > left) {
            picked.remove(picked.size() - 1);
            cut = true;
        }
        return cut || quotaFull.containsValue(exped);
    }

    /**
     * 1.6.4 (-Dslums.exped.botcaptain): who leads the run, and why. A real player always outranks a bot, including at
     * start time - a bot may never take leadership from a human. Called by {@code EventManager.startInstance} before
     * it reads the leader, because the instance's leader, the channel the setup script runs on and every later
     * {@code expedition.getLeader()} come from that one read. Only expedition members can lead: a human in the bot's
     * party who never registered for the expedition has nothing to lead, and the log says so.
     */
    public static Character preferredLeader(Expedition exped, Character leader) {
        if (exped == null || leader == null || !BotHelpers.isBot(leader) || !captainsAllowed()) {
            return leader;
        }
        Character human = null;
        for (Integer id : exped.getMembers().keySet()) {
            Character chr = memberById(exped, id);
            if (chr != null && chr.getId() != leader.getId() && !BotHelpers.isBot(chr)) {
                human = chr;
                break;
            }
        }
        if (human == null) {
            return leader;
        }
        log.info("[Expedition] {} run: a real player outranks a bot, so {} leads instead of {}",
                exped.getType(), human.getName(), leader.getName());
        exped.promote(human);
        human.dropMessage(6, "[Expedition] You lead this " + exped.getType() + " run; " + leader.getName()
                + " only opened it.");
        return human;
    }

    /** The run's door: what "Start the fight!" does in {@code scripts/npc/2030013.js:150-178}, for a bot captain. */
    public enum Door {
        STARTED, NOT_A_CAPTAIN, SWITCH_OFF, NO_SCRIPT, ROOM_TAKEN, HUMAN_LEADS, TOO_FEW, REFUSED
    }

    /**
     * 1.6.4 (-Dslums.exped.botcaptain): a bot captain opens and starts its expedition without anyone talking to the
     * recruiter. Nothing else in the tree can: {@code AbstractPlayerInteraction.createExpedition} is reached only from
     * an NPC script, and a bot never runs one. This is the same three steps the recruiter takes - create the
     * expedition, register the party's bots, set the event's {@code leader}/{@code channel} properties and call
     * {@code EventManager.startInstance} - so the run that starts here is the run a player would have started.
     * The script name comes from {@link RebirthBosses.Boss}, the tree's own type-to-script table; a type that has no
     * entry there cannot be started this way and says so.
     */
    public static Door startAsCaptain(Character captain, ExpeditionType type) {
        if (!captainsAllowed()) {
            return Door.SWITCH_OFF;
        }
        if (captain == null || type == null || !BotHelpers.isBot(captain) || captain.getClient() == null
                || captain.getClient().getChannelServer() == null || excluded(type)) {
            return Door.NOT_A_CAPTAIN;
        }
        Channel channel = captain.getClient().getChannelServer();
        if (channel.getExpedition(type) != null) {
            return Door.ROOM_TAKEN;
        }
        String script = RebirthBosses.scriptFor(type);
        EventManager em = script == null ? null : channel.getEventSM().getEventManager(script);
        if (em == null) {
            log.warn("[Expedition] {} has no event script in this tree, so a bot captain cannot start it", type);
            return Door.NO_SCRIPT;
        }
        if (humanInParty(captain) != null) {
            return Door.HUMAN_LEADS; // a real player outranks the bot: he talks to the recruiter himself
        }
        Expedition exped = new Expedition(captain, type, false, 0, 0);
        if (!ExpeditionBossLog.attemptBoss(captain.getId(), channel.getId(), exped, false)) {
            return Door.REFUSED;
        }
        if (!exped.addChannelExpedition(channel)) {
            return Door.ROOM_TAKEN;
        }
        autoJoin(captain, exped);
        if (exped.getMembers().size() < exped.getMinSize()) {
            exped.dispose(true);
            exped.removeChannelExpedition(channel);
            log.info("[Expedition] bot captain {} found {} of {} members for {} and gave the room back",
                    captain.getName(), exped.getMembers().size(), exped.getMinSize(), type);
            return Door.TOO_FEW;
        }
        em.setProperty("leader", captain.getName());
        em.setProperty("channel", String.valueOf(channel.getId()));
        boolean started = em.startInstance(exped);
        log.info("[Expedition] bot captain {} {} the {} run with {} members", captain.getName(),
                started ? "started" : "could not start", type, exped.getMembers().size());
        return started ? Door.STARTED : Door.REFUSED;
    }

    /** A real player in the bot's party (-Dslums.exped.botcaptain: a bot never captains over a human). */
    static Character humanInParty(Character captain) {
        Party party = captain.getParty();
        if (party == null) {
            return null;
        }
        for (PartyCharacter pc : party.getMembers()) {
            if (pc == null || pc.getId() == captain.getId()) {
                continue;
            }
            Character chr = pc.getPlayer();
            if (chr != null && !BotHelpers.isBot(chr)) {
                return chr;
            }
        }
        return null;
    }

    /** A member of the expedition by id, online or not (the recruiting map's storage knows the online ones). */
    private static Character memberById(Expedition exped, int id) {
        MapleMap map = exped.getRecruitingMap();
        if (map == null) {
            return null;
        }
        return map.getWorldServer().getPlayerStorage().getCharacterById(id);
    }

    /**
     * b075 (live report #5): "Start the fight!" with too few members only said "You need at least 6 players
     * registered", so a player whose party bots were too low, elsewhere or out of entries could not tell why they
     * never joined. This is what the recruiter says instead: the count, each party bot that is not in and why, and
     * how the rest can join. Reads only what {@link #autoJoin} (run on the same NPC step) already decided: no
     * boss-log read, no order to a bot.
     */
    public static String shortfall(Character leader, Expedition exped) {
        int have = exped.getMembers().size();
        int need = exped.getMinSize();
        StringBuilder sb = new StringBuilder("You need at least " + need + " members registered in your expedition;"
                + " you have " + have + ".");
        Party party = leader == null ? null : leader.getParty();
        List<String> out = new ArrayList<>();
        List<String> shortOfSupplies = new ArrayList<>();
        if (party != null && !BotHelpers.isBot(leader)) {
            for (PartyCharacter pc : party.getMembers()) {
                if (pc == null || pc.getId() == leader.getId()) {
                    continue;
                }
                Character bot = pc.getPlayer();
                if (bot == null) {
                    if (BotHelpers.isArtificial(pc.getId())) {
                        out.add(pc.getName() + " is not online.");
                    }
                    continue;
                }
                if (!BotHelpers.isBot(bot)) {
                    continue; // a real player registers himself (said below)
                }
                String why = skip(exped.getType(), leader.getId(), factsOf(leader, exped, party, bot));
                if (why == null && quotaFull.get(bot.getId()) == exped) {
                    why = "quota";
                } else if (why == null && busy.get(bot.getId()) == exped) {
                    why = "busy";
                }
                if (why != null && !"member".equals(why)) {
                    out.add(bot.getName() + " " + describe(why, exped.getType(), bot.getLevel()));
                } else {
                    String line = suppliesLine(bot);
                    if (line != null) {
                        shortOfSupplies.add(line);
                    }
                }
            }
        }
        if (!out.isEmpty()) {
            sb.append("\r\n\r\nNot joining:");
            for (String line : out) {
                sb.append("\r\n - ").append(line);
            }
        }
        // 1.6.7: a member that will fight anyway, but cannot last the run (Horntail, 8 Nov 2026: two Hero alts ran
        // out of MP on the way in). Only told, never blocked.
        if (!shortOfSupplies.isEmpty()) {
            sb.append("\r\n\r\nJoining, but short of supplies:");
            for (String line : shortOfSupplies) {
                sb.append("\r\n - ").append(line);
            }
        }
        ExpeditionType type = exped.getType();
        sb.append("\r\n\r\nYour party's regulars and alts join you on their own when they stand here and are level ")
                .append(type.getMinLevel()).append(type.getMaxLevel() < 255 ? "-" + type.getMaxLevel() : "+")
                .append(" (a party holds 6). Other players join by talking to me.");
        return sb.toString();
    }

    /** The recruiter's line for one member that will join but is short of supplies, or null when it is ready. */
    private static String suppliesLine(Character bot) {
        CompanionSupplies.ShortOf shortOf = CompanionSupplies.shortOf(bot);
        return shortOf == null ? null : shortOf.line(bot.getName());
    }

    /** The player-facing words for a {@link #skip} reason. */
    static String describe(String why, ExpeditionType type, int level) {
        return switch (why) {
            case "owner" -> "is another player's regular, so it cannot come with you.";
            case "banned" -> "was expelled from this expedition.";
            case "dead" -> "is dead.";
            case "map" -> "is not on this map. Bring it here first.";
            case "level" -> "is level " + level + "; this expedition needs level " + type.getMinLevel()
                    + (type.getMaxLevel() < 255 ? " to " + type.getMaxLevel() : " or higher") + ".";
            case "quest" -> "has not done the quest this expedition needs.";
            case "quota" -> type == ExpeditionType.CHAOS_ZAKUM
                    ? "cannot come: the regulars have used all " + ExpeditionBossLog.CHAOS_ZAKUM_BOT_DAILY_RUNS
                            + " of today's Chaos Zakum runs."
                    : "has no entries left for this boss today.";
            case "busy" -> "is busy and cannot follow you right now.";
            default -> "cannot join (" + why + ").";
        };
    }

    /** Adds the picked bots up to the expedition's maximum size and tells the leader who came. */
    static List<String> register(Character leader, Expedition exped, List<Character> bots) {
        List<String> names = new ArrayList<>();
        for (Character bot : bots) {
            if (exped.getMembers().size() >= exped.getMaxSize()) {
                qa(exped, "EXPED BOT SKIP " + bot.getName() + " -> " + exped.getType() + ": full");
                break;
            }
            if (exped.addMemberInt(bot) != 0) {
                continue;
            }
            ExpeditionHold.hold(bot.getId(), exped);
            names.add(bot.getName());
            leader.dropMessage(6, "[Expedition] " + bot.getName() + " joined with you.");
            qa(exped, "EXPED BOT JOIN " + bot.getName() + " -> " + exped.getType());
        }
        return names;
    }

    /** Why this bot does not join (null = it may, quota still to check). Pure: the rules, nothing read. */
    static String skip(ExpeditionType type, int leaderId, Facts f) {
        if (!f.bot()) return "not a bot";
        if (f.member()) return "member";
        if (f.banned()) return "banned";
        if (!ownerAdmits(leaderId, f)) return "owner";
        if (f.dead()) return "dead";
        if (!f.sameMap()) return "map";
        if (f.level() < type.getMinLevel() || f.level() > type.getMaxLevel()) return "level";
        if (!f.questOk()) return "quest";
        return null;
    }

    /**
     * Whose bot it must be. 1.6.0: the leader's companion or alt, or a bot nobody leads when the leader is the one
     * real player in the party. 1.6.4 (owner, -Dslums.exped.altfollow): an owned alt also comes when its owner is a
     * member of that expedition, whoever leads it - "it should count as party".
     */
    static boolean ownerAdmits(int leaderId, Facts f) {
        if (f.ownerId() == leaderId) {
            return true;
        }
        if (f.alt() && f.ownerMember() && altFollowAllowed()) {
            return true;
        }
        return f.ownerId() == -1 && f.soleRealPlayer();
    }


    /** Ariant is a PvP arena with its own sign-up; bots stay out. */
    static boolean excluded(ExpeditionType type) {
        return type == ExpeditionType.ARIANT || type == ExpeditionType.ARIANT1 || type == ExpeditionType.ARIANT2;
    }

    /**
     * The member quest some recruiters check before they talk to anyone (scripts/npc 2161000, 3000131, 9906602..05,
     * 9010100, 3003560, 3003773, 3003806): {questId, 1 if started is enough}, or null when there is none.
     */
    static int[] memberQuest(ExpeditionType type) {
        return switch (type) {
            case VONLEON -> new int[]{3171, 1};
            case MAGNUS -> new int[]{31166, 0};
            case LUCID, WILLSPIDER, VERUS, DARKNELL -> new int[]{31165, 0};
            case NLUCID -> new int[]{31161, 0};
            case NWILLSPIDER -> new int[]{31162, 0};
            case NDARKNELL -> new int[]{31163, 0};
            case NVERUS -> new int[]{31164, 0};
            default -> null;
        };
    }

    /** True while this bot is registered for an expedition that is still registering or that it is inside. */
    public static boolean committed(Character bot) {
        return ExpeditionHold.committed(bot);
    }

    /** A joined bot inside its expedition's instance: it fights next to its leader ({@code FollowerFight}). */
    public static boolean inRun(Character bot) {
        return ExpeditionHold.inRun(bot);
    }

    /** The expedition is over (disposed): its bots are free again. */
    public static void release(Expedition exped) {
        if (exped == null) {
            return;
        }
        ExpeditionHold.release(exped);
        quotaFull.values().removeIf(e -> e == exped);
        quotaTold.remove(exped);
        busy.values().removeIf(e -> e == exped);
    }

    /**
     * 1.6.2 (b120, {@code -Dslums.exped.door}): the leader pressed "Start the fight!" and some of the bots he took on
     * are no longer standing at the altar, so {@code EventInstanceManager.registerExpeditionTeam} is about to leave
     * them out. Say so once each, by name - silence is what makes it look like the bots ignored him. Nothing is
     * warped: a bot walking to the altar is follower travel, and a teleport would be a second, wrong answer.
     */
    public static void warnNotAtTheAltar(Expedition exped) {
        if (!ExpeditionDoor.enabled() || exped == null || exped.getLeader() == null) {
            return;
        }
        MapleMap altar = exped.getRecruitingMap();
        if (altar == null) {
            return;
        }
        for (int id : ExpeditionHold.botsOf(exped)) {
            String name = exped.getMembers().get(id);
            if (name == null) {
                // 1.6.5 exped-msgs: no longer a member (an owned alt dismissed - exped.door took it off the list - or a
                // bot that left): nobody is being left behind. TEST 6 Nov: the dismissed AqClientAlt2 drew "A bot is
                // not at the altar" on a start where every member came.
                continue;
            }
            Character bot = altar.getWorldServer().getPlayerStorage().getCharacterById(id);
            if (bot != null && bot.getMap() != null && bot.getMap().getId() == altar.getId()) {
                continue; // it is coming
            }
            exped.getLeader().dropMessage(6, notAtTheAltarLine(name));
        }
    }

    /** The leader's line for a member left behind, by name (the name rule: no "bot"). */
    static String notAtTheAltarLine(String name) {
        return "[Expedition] " + name + " isn't at the altar and won't join.";
    }

    // ── live reads ───────────────────────────────────────────────────────────

    static Facts factsOf(Character leader, Expedition exped, Party party, Character bot) {
        int id = bot.getId();
        int ownerId = ownerOf(bot);
        return new Facts(BotHelpers.isBot(bot), exped.hasMember(id), exped.isBanned(id),
                soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.isDead(id) || !bot.isAlive(),
                bot.getMap() != null && bot.getMap() == exped.getRecruitingMap(), bot.getLevel(), ownerId,
                soleRealPlayer(party, leader.getId()), questOk(exped.getType(), bot),
                bot.isOwnedAlt(), bot.isOwnedAlt() && ownerId > 0 && exped.hasMember(ownerId));
    }

    /** The real player this bot belongs to: its alt owner, or the player it follows; -1 = nobody's. */
    static int ownerOf(Character bot) {
        if (bot.isOwnedAlt()) {
            Character owner = bot.getOwnedAltOwner();
            return owner == null ? -2 : owner.getId(); // -2: an alt whose owner is gone is nobody's to take
        }
        BotSM sm = getBotById(bot.getId());
        return sm instanceof FollowerBot follower ? follower.leaderId() : -1;
    }

    private static boolean soleRealPlayer(Party party, int leaderId) {
        for (PartyCharacter pc : party.getMembers()) {
            if (pc == null || pc.getId() == leaderId) {
                continue;
            }
            Character p = pc.getPlayer();
            if (p != null ? !BotHelpers.isBot(p) : !BotHelpers.isArtificial(pc.getId())) {
                return false;
            }
        }
        return true;
    }

    private static boolean questOk(ExpeditionType type, Character bot) {
        int[] q = memberQuest(type);
        if (q == null) {
            return true;
        }
        QuestStatus.Status s = bot.getQuest(q[0]).getStatus();
        return s == QuestStatus.Status.COMPLETED || q[1] == 1 && s == QuestStatus.Status.STARTED;
    }

    /**
     * A joined bot must walk in with the player, not grind off on its own: an alt or a follower already does; a
     * stationed trainer is told "follow" (the party order, ticket 14), which makes it his follower again.
     */
    private static boolean follows(Character bot, Character leader) {
        if (bot.isOwnedAlt()) {
            return true;
        }
        BotSM sm = getBotById(bot.getId());
        if (sm instanceof FollowerBot) {
            return true;
        }
        if (sm instanceof TrainingBot trainer) {
            trainer.order(BotCommandParser.Verb.FOLLOW, leader, true);
            return getBotById(bot.getId()) instanceof FollowerBot;
        }
        return false;
    }

    private static void qa(Expedition exped, String line) {
        if (QaLog.on(QaLog.Topic.PQ)) {
            QaLog.map(QaLog.Topic.PQ, exped.getRecruitingMap() == null ? 0 : exped.getRecruitingMap().getId(), line);
        }
    }
}
