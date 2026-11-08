package soloMapling.companions;

import client.Character;
import client.QuestStatus;
import constants.id.MobId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.quest.Quest;
import soloMapling.ArtificialPlayer.BotHelpers;
import soloMapling.ArtificialPlayer.BotMessagingSystem.CharacterStorage;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy;
import soloMapling.ArtificialPlayer.BotPartySystem.PartyCompanionPolicy.Feature;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypes.FollowerBot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 1.6.7 alt-qol-batch E: a companion quests along with the player it is with.
 *
 * <p>Ported from Kiwiism/cosmic_agents, {@code src/main/java/server/agents/capabilities/quest/
 * AgentPartyQuestSyncService.java}@3c7e094, AGPL-3.0 (see docs/PROVENANCE.md). Taken: the shape of the three hooks
 * ({@code syncPartyAgentsQuestStart}, {@code syncPartyAgentsQuestComplete}, the party-members loop in
 * {@code partyAgents} that skips the source and non-bot members) and the idea of the leader passing its own NPC on to
 * the companion, although ours comes from the {@link Quest} call itself.
 *
 * <p>Changed: they force a quest on every party agent ({@code forceStartWithActions} /
 * {@code forceCompleteWithActions}); here a companion only takes a quest it can start and only turns in one it meets
 * the requirements for ({@code canStart} / {@code canComplete}), and a reward-choice quest is skipped instead of being
 * forced with the leader's choice. Their {@code setQuestProgress} progress mirroring is not ported: our kill credit is
 * the ordinary {@link Character#raiseQuestMobCount(int)} path, called for a companion standing beside the kill, so the
 * counts, the merged-mob aliases and the progress format stay the server's own. Their gateway runtime is replaced by
 * the party and the bot registry we already have: an owned alt through {@link Character#getOwnedAltOwner()} and a
 * regular through {@link FollowerBot#leaderId()}. Part of theirs to note: their {@code syncKingPepeYetiPartyProgress}
 * exists because their parties award quest kills only to the highest damager - our kill credit has no such sharing
 * path, so there was nothing to reuse and the companion simply gets the same call a party member's kill would.
 *
 * <p>Off for every owned alt until its owner types {@code @botme quests on <name>}: the opt-in is a set of character
 * ids in memory, so a restart only turns mirroring off again. Party regulars are governed by
 * {@link PartyCompanionPolicy} alone ({@code -Dslums.companion.quests.regulars}, off by default), so with the defaults
 * they behave exactly as before.
 */
public final class CompanionQuestMirror {
    private static final Logger log = LoggerFactory.getLogger(CompanionQuestMirror.class);

    /** Alt ids whose owner typed {@code @botme quests on <name>}. Memory only: a restart turns mirroring off. */
    private static final Set<Integer> OPTED_IN = ConcurrentHashMap.newKeySet();

    private CompanionQuestMirror() {
    }

    /** Turns the mirror on or off for one owned alt; called by the {@code @botme quests} command. */
    public static void setOptIn(int altId, boolean on) {
        if (on) {
            OPTED_IN.add(altId);
            log.info("quest mirror: on for character {}", altId);
        } else if (OPTED_IN.remove(altId)) {
            log.info("quest mirror: off for character {}", altId);
        }
    }

    public static boolean isOptedIn(int altId) {
        return OPTED_IN.contains(altId);
    }

    public static void clearForTest() {
        OPTED_IN.clear();
    }

    /** {@code leader} started {@code quest} with this NPC: companions in the party start it too when they can. */
    public static void onQuestStarted(Character leader, Quest quest, int npc) {
        if (!mirrorsFrom(leader) || quest == null) {
            return;
        }
        for (Character companion : companions(leader, false)) {
            try {
                if (!quest.canStart(companion, npc)) {
                    log.debug("quest mirror: {} cannot start {} (requirements or status), skipped",
                            companion.getName(), quest.getId());
                    continue;
                }
                quest.start(companion, npc);
                log.debug("quest mirror: {} started {} with {}", companion.getName(), quest.getId(), leader.getName());
            } catch (RuntimeException e) {
                log.warn("quest mirror: could not start {} for {}", quest.getId(), companion.getName(), e);
            }
        }
    }

    /**
     * {@code leader} turned {@code quest} in: companions that have it started and meet its requirements themselves turn
     * it in too, and the rewards are theirs. A quest whose reward is a pick ({@link Quest#hasRewardChoice()}) is skipped,
     * because a companion cannot make the choice; {@code selection} is only what the packet carried.
     */
    public static void onQuestCompleted(Character leader, Quest quest, int npc, Integer selection) {
        if (!mirrorsFrom(leader) || quest == null) {
            return;
        }
        if (leader.getQuest(quest).getStatus() != QuestStatus.Status.COMPLETED) {
            return; // not a turn-in (a forfeit, or a failed check)
        }
        if (quest.hasRewardChoice()) {
            // the quest's own data decides, not whether the packet carried a selection short
            log.info("quest mirror: {} picks a reward item, left for the companions (reward-choice policy not built yet)",
                    quest.getId());
            return;
        }
        for (Character companion : companions(leader, false)) {
            try {
                QuestStatus status = companion.getQuest(quest);
                if (status.getStatus() != QuestStatus.Status.STARTED) {
                    log.debug("quest mirror: {} has not started {}, skipped", companion.getName(), quest.getId());
                    continue;
                }
                if (!quest.canComplete(companion, npc)) {
                    log.debug("quest mirror: {} does not meet the requirements of {} itself, stays started",
                            companion.getName(), quest.getId());
                    continue;
                }
                quest.complete(companion, npc);
                log.debug("quest mirror: {} completed {} with {}", companion.getName(), quest.getId(), leader.getName());
            } catch (RuntimeException e) {
                log.warn("quest mirror: could not complete {} for {}", quest.getId(), companion.getName(), e);
            }
        }
    }

    /**
     * {@code leader} killed {@code mobId}: the kill counts for a companion standing on the same map, through the
     * server's own quest kill path. Only a real player's kills mirror; a bot's kill is its own.
     */
    public static void onLeaderKill(Character leader, int mobId) {
        if (leader == null || BotHelpers.isBot(leader)) {
            return;
        }
        if (mobId == MobId.GREEN_MUSHROOM_QUEST || mobId == MobId.ZOMBIE_MUSHROOM_QUEST
                || mobId == MobId.GHOST_STUMP_QUEST) {
            return; // the companion's own aliasing inside raiseQuestMobCount covers these
        }
        if (leader.getParty() == null) {
            return;
        }
        for (Character companion : companions(leader, true)) {
            try {
                companion.raiseQuestMobCount(mobId);
            } catch (RuntimeException e) {
                log.warn("quest mirror: could not count mob {} for {}", mobId, companion.getName(), e);
            }
        }
    }

    /** Only a real player's own actions mirror: the companions' own actions must never echo back. */
    private static boolean mirrorsFrom(Character leader) {
        return leader != null && !BotHelpers.isBot(leader);
    }

    /**
     * {@code leader}'s party companions that the mirror is on for, on the same channel ({@code sameMap} false) or on
     * the leader's map ({@code sameMap} true).
     */
    private static List<Character> companions(Character leader, boolean sameMap) {
        if (leader.getParty() == null) {
            return List.of();
        }
        List<Character> companions = new ArrayList<>(1);
        for (Character member : leader.getPartyMembersOnline()) {
            if (member == null || member.getId() == leader.getId() || !BotHelpers.isBot(member)) {
                continue;
            }
            if (!PartyCompanionPolicy.on(Feature.QUEST_MIRROR, member)) {
                continue;
            }
            if (!isWith(leader, member)) {
                continue;
            }
            if (member.isOwnedAlt() && !isOptedIn(member.getId())) {
                continue;
            }
            if (!member.isPresentInWorld()) {
                continue;
            }
            if (sameMap ? member.getMap() != leader.getMap()
                    : member.getClient().getChannel() != leader.getClient().getChannel()) {
                continue;
            }
            companions.add(member);
        }
        return companions;
    }

    /** An owned alt of this player, or a party regular that follows this player. */
    private static boolean isWith(Character leader, Character companion) {
        if (companion.isOwnedAlt()) {
            Character owner = companion.getOwnedAltOwner();
            return owner != null && owner.getId() == leader.getId();
        }
        BotSM sm = CharacterStorage.getBotById(companion.getId());
        return sm instanceof FollowerBot follower && follower.leaderId() == leader.getId();
    }
}
