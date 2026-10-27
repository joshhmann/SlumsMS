package soloMapling.ArtificialPlayer.BotPartySystem;

import client.Character;
import client.QuestStatus;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import server.expeditions.Expedition;
import server.expeditions.ExpeditionBossLog;
import server.expeditions.ExpeditionType;
import soloMapling.ArtificialPlayer.BotHelpers;
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

    private ExpeditionBots() {
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

    /** What decides whether one party bot may join: read off the live characters by {@link #factsOf}. */
    record Facts(boolean bot, boolean member, boolean banned, boolean dead, boolean sameMap, int level, int ownerId,
                 boolean soleRealPlayer, boolean questOk) {
    }

    /**
     * Registers the leader's eligible party bots in his expedition (registration open only). Returns the names that
     * joined now; each is told to the leader: "[Expedition] Clock4 joined with you."
     */
    public static List<String> autoJoin(Character leader, Expedition exped) {
        if (leader == null || exped == null || !exped.isRegistering() || !exped.isLeader(leader)
                || BotHelpers.isBot(leader) || excluded(exped.getType())) {
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
        return register(leader, exped, picked);
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
        // His companion or his alt; a bot nobody leads only when he is the one real player in the party.
        if (f.ownerId() != leaderId && !(f.ownerId() == -1 && f.soleRealPlayer())) return "owner";
        if (f.dead()) return "dead";
        if (!f.sameMap()) return "map";
        if (f.level() < type.getMinLevel() || f.level() > type.getMaxLevel()) return "level";
        if (!f.questOk()) return "quest";
        return null;
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
        busy.values().removeIf(e -> e == exped);
    }

    // ── live reads ───────────────────────────────────────────────────────────

    static Facts factsOf(Character leader, Expedition exped, Party party, Character bot) {
        int id = bot.getId();
        return new Facts(BotHelpers.isBot(bot), exped.hasMember(id), exped.isBanned(id),
                soloMapling.ArtificialPlayer.BotCombatSystem.BotDeaths.isDead(id) || !bot.isAlive(),
                bot.getMap() != null && bot.getMap() == exped.getRecruitingMap(), bot.getLevel(), ownerOf(bot),
                soleRealPlayer(party, leader.getId()), questOk(exped.getType(), bot));
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
