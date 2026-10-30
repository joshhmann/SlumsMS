package soloMapling.ArtificialPlayer.BotTypes;

import client.Character;
import net.server.world.Party;
import net.server.world.PartyCharacter;
import soloMapling.ArtificialPlayer.BotDialogueHandler;
import soloMapling.ArtificialPlayer.BotOptionMenu;
import soloMapling.ArtificialPlayer.BotSM;
import soloMapling.ArtificialPlayer.BotTypeManager;
import soloMapling.ArtificialPlayer.BotGrindSystem.MapMobIndex;
import soloMapling.ArtificialPlayer.BotPartySystem.BotCommandParser;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyCommands;
import soloMapling.ArtificialPlayer.BotPartySystem.BotPartyQueue;
import soloMapling.ArtificialPlayer.BotPartySystem.BotRecruitManager;
import soloMapling.ArtificialPlayer.GCMoveSystem.GCMovement;
import soloMapling.server.BotTickService;

import java.util.List;

import static soloMapling.ArtificialPlayer.BotCommandsPack.SocialCommands.BotSpeak;
import static soloMapling.ArtificialPlayer.BotHelpers.isBot;
import static soloMapling.BotLogger.log;

// A recruited companion: follows one real player everywhere. The heavy lifting is the GC follow
// engine (50ms same-map tailing + 400ms cross-map session that travels portal chains and redirects
// mid-trip - see GCFollow); this FSM is only the supervisor: it tracks the leader BY ID so a relog
// re-attaches (a session holds a hard Character ref and dies with it), re-arms the session whenever
// it's down, watches party membership, and converts itself away when the ride ends.
//
// Cadence: pinned fast (~750ms) and governor-exempt - a follower is foreground content whether or
// not its current map is observed (it may be catching up through empty maps). The map it shares
// with its leader is FULL by definition, so this costs almost nothing in practice.
//
// Lifecycle in: SocialBot recruit (party join -> convert), TrainingBot "Follow me!", !bot followbot.
// Lifecycle out: "Train here with me!" -> TrainingBot (station-here handoff); leader gone past the
// grace / party dissolved -> TrainingBot on a mob map, SocialBot in a town.
public class FollowerBot extends BotSM {

    private static final long FOLLOW_TICK_MS = 750;
    private static final long LEADER_LOST_GRACE_MS = 90_000;

    private enum FollowPhase { INIT, FOLLOW, STAY, LEADER_LOST }

    // Written on the macro tick, read from menu callbacks - keep volatile.
    private volatile FollowPhase followPhase = FollowPhase.INIT;
    private volatile int leaderId = -1;
    private volatile long leaderLostSinceMs = 0;
    private volatile boolean wasPartied = false;
    private volatile boolean pausedForTrade = false;
    private volatile boolean fighting = false; // 1.5.5: swinging at a dojo floor's monster (FollowerFight)

    // NOTE: no "here" keyword - "there" contains "here", so a casual "hi there" would trigger it.
    private final BotOptionMenu menu = new BotOptionMenu(this,
            List.of("Train here with me!", "Nevermind"),
            List.of(List.of("train", "grind", "station"), List.of("nevermind", "bye", "nah", "nope")),
            this::onMenuSelect);

    public FollowerBot(Character character) {
        super(character);
        if (config.YamlConfig.config.server.OWNED_ALTS_ENABLED) {
            int pending = BotRecruitManager.consumePendingLeader(character.getId());
            leaderId = pending > 0 ? pending : leaderIdFromParty();
        }
        botType = "FollowerBot";
        dialoguePath = "FollowerBotDialogue.yaml";
    }

    // Pinned cadence, observed or not: supervision (re-arm/relog/party checks) must never sleep 9-12s.
    @Override
    public void checkPrioritySpeed() {
        updateScheduleDelay(fighting ? FollowerFight.FIGHT_TICK_MS : FOLLOW_TICK_MS);
    }

    @Override
    public synchronized void startScheduledTask(long initialDelayMs) {
        super.startScheduledTask(initialDelayMs);
        int id = getChr().getId();
        BotTickService.setNoThrottle(id, true);
        // Conversions inherit the spawn-choreography stagger, but a converted follower is already
        // live in-world - pull the first tick to the follow cadence so it starts moving immediately.
        BotTickService.reschedule(id, FOLLOW_TICK_MS);
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
        if (getState() == BotState.TRADING) {
            // Trades are sacred: end the follow/travel sessions for the duration of the window. The
            // commitment hold (BotCommitmentHold, armed at the TRADING entry in BotSM) freezes the
            // movement driver, but a frozen bot looks like a stalled hop to the pollers, which warp
            // (GCTravel.tripHopStuck -> changeMap) rather than wait - so the follow session has to end
            // here, not idle. The FSM re-arms it on the first tick after the window closes.
            if (!pausedForTrade) {
                pausedForTrade = true;
                GCMovement.stop(chr);
            }
            return;
        }
        pausedForTrade = false;
        if (followPhase != FollowPhase.FOLLOW) {
            fighting = false; // only a following bot fights (doFollow sets it each tick)
        }

        switch (followPhase) {
            case INIT -> doInit();
            case FOLLOW -> doFollow();
            case STAY -> doStay();
            case LEADER_LOST -> doLeaderLost();
        }

        if (!getRunning()) {
            return; // a phase converted this bot away mid-tick
        }
        pollLeaderInvite();
        menu.poll(); // last: a selection may also convert this bot away
    }

    // An unpartied follower (e.g. via !bot followbot) accepts a party invite from its OWN leader -
    // that's how the GM flow gets party EXP going - and politely rejects anyone else so the
    // first-wins-free queue never holds a rotting entry.
    private void pollLeaderInvite() {
        Character chr = getChr();
        if (!BotPartyQueue.getInstance().hasPendingInvite(chr)) {
            return;
        }
        BotPartyQueue.PartyInviteEntry entry = BotPartyQueue.getInstance().getPartyInvite(chr);
        Character inviter = entry == null ? null : entry.getInviter();
        if (inviter != null && inviter.getId() == leaderId && chr.getParty() == null) {
            if (BotPartyCommands.botAcceptPartyInvite(chr)) {
                wasPartied = true;
                sayNode("PartyJoined", inviter);
            }
            return;
        }
        BotPartyCommands.botRejectPartyInvite(chr);
    }

    // ── Phases ───────────────────────────────────────────────────────────────

    private void doInit() {
        Character chr = getChr();
        int pending = BotRecruitManager.consumePendingLeader(chr.getId());
        if (leaderId <= 0) leaderId = pending > 0 ? pending : leaderIdFromParty();
        if (leaderId <= 0) {
            fallbackConvert();
            return;
        }
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            return;
        }
        wasPartied = chr.getParty() != null;
        GCMovement.follow(chr, leader);
        sayNode("FollowStart", leader);
        followPhase = FollowPhase.FOLLOW;
    }

    private void doFollow() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader == null || leader.getMap() == null) {
            leaderLostSinceMs = now();
            followPhase = FollowPhase.LEADER_LOST;
            sayNode("LeaderLost", null);
            return;
        }
        if (chr.getParty() != null) {
            wasPartied = true;
        } else if (wasPartied) {
            // Kicked / disband: the ride is over.
            sayNode("PartyFarewell", leader);
            fallbackConvert();
            return;
        }
        // 1.5.5: on a dojo floor with its leader the follower fights the floor's monster (it has no other way to)
        fighting = FollowerFight.tick(chr, leader);
        if (fighting) {
            return;
        }
        if (!GCMovement.isFollowing(chr)) {
            // Initial arm, post-trade re-arm, and relog re-attach (a dead session cleared itself;
            // the freshly resolved leader Character makes the new session current).
            GCMovement.follow(chr, leader);
        }
    }

    // Ticket 14: told to stop - stand here until told to follow, still watching the party and the leader.
    private void doStay() {
        Character chr = getChr();
        if (GCMovement.isFollowing(chr)) {
            GCMovement.stop(chr);
        }
        if (chr.getParty() != null) {
            wasPartied = true;
        } else if (wasPartied) {
            sayNode("PartyFarewell", resolveLeader());
            fallbackConvert();
            return;
        }
        Character leader = resolveLeader();
        if (leader != null && leader.getMap() != null) {
            leaderLostSinceMs = 0;
        } else if (leaderLostSinceMs == 0) {
            leaderLostSinceMs = now();
        } else if (now() - leaderLostSinceMs > LEADER_LOST_GRACE_MS) {
            sayNode("PartyFarewell", null);
            fallbackConvert();
        }
    }

    private void doLeaderLost() {
        Character chr = getChr();
        Character leader = resolveLeader();
        if (leader != null && leader.getMap() != null) {
            followPhase = FollowPhase.FOLLOW;
            return;
        }
        if (wasPartied && chr.getParty() == null) {
            fallbackConvert();
            return;
        }
        if (now() - leaderLostSinceMs > LEADER_LOST_GRACE_MS) {
            sayNode("PartyFarewell", null);
            fallbackConvert();
        }
    }

    // ── Menu (Dispatcher routes a "botname" chat here via displayCommands) ───

    @Override
    public void displayCommands(Character chr) {
        menu.show(chr);
    }

    /**
     * Ticket 37: the leader's "train here" does what the menu's "Train here with me!" does; ticket 14 adds "wait
     * here" / "stop following" and "follow me" / "come here" in a sentence (the short orders come through
     * {@link #order}).
     */
    @Override
    public boolean naturalAction(Character player, String said) {
        String lower = said == null ? "" : said.toLowerCase(java.util.Locale.ROOT);
        if (player.getId() != leaderId) {
            return false;
        }
        if (lower.matches(".*\\b(train|grind|station)\\b.*")) {
            onMenuSelect(0, player);
            return true;
        }
        if (lower.matches(".*\\b((wait|stay|stop) (here|there)|stop following)\\b.*")) {
            return order(BotCommandParser.Verb.STAY, player, false);
        }
        if (followPhase == FollowPhase.STAY && lower.matches(".*\\b(follow me|come (here|back|with me|on))\\b.*")) {
            return order(BotCommandParser.Verb.FOLLOW, player, false);
        }
        return false;
    }

    /** True while it fights a dojo floor's monster (FollowerFight; !qa dj-1 reads it). */
    public boolean isFighting() {
        return fighting;
    }

    /** The leader this follower walks with (-1 before its first tick). */
    public int leaderId() {
        return leaderId;
    }

    /**
     * Ticket 14: a party order. Only the leader is obeyed; anyone else hears it is loyal (unless the order went to
     * every bot, which needs no answer from each). True when the bot answered.
     *
     * @param everyone the order went to every party bot: a bot it does not change stays quiet
     */
    public boolean order(BotCommandParser.Verb verb, Character player, boolean everyone) {
        if (leaderId <= 0 || player.getId() != leaderId) {
            if (!everyone) {
                sayNode("LoyalToLeader", player);
            }
            return !everyone;
        }
        switch (verb) {
            case GRIND -> onMenuSelect(0, player);
            case STAY -> {
                if (followPhase != FollowPhase.STAY) {
                    followPhase = FollowPhase.STAY;
                    leaderLostSinceMs = 0;
                    GCMovement.stop(getChr());
                    sayNode("Stay", player);
                } else if (!everyone) {
                    sayNode("Stay", player);
                }
            }
            case FOLLOW -> {
                if (followPhase == FollowPhase.STAY || followPhase == FollowPhase.LEADER_LOST) {
                    followPhase = FollowPhase.FOLLOW; // the next tick re-arms the follow session
                    sayNode("FollowAgain", player);
                } else if (!everyone) {
                    sayNode("FollowAgain", player);
                }
                nudgeSoon(0L);
            }
            // 1.6.2 ticket 99 (COMPANION_ORDERS_ENABLED): the companion movement set. A bot friend fights only on a
            // dojo floor, so a sentry holds like a stay; a patrol trains here as "grind" does.
            case MOVE_HERE -> {
                followPhase = FollowPhase.STAY;
                leaderLostSinceMs = 0;
                GCMovement.stop(getChr());
                java.awt.Point at = player.getPosition();
                if (at != null && player.getMap() == getChr().getMap()) {
                    GCMovement.move(getChr(), at.x, at.y);
                }
                sayNode("Stay", player);
            }
            case SENTRY -> {
                followPhase = FollowPhase.STAY;
                leaderLostSinceMs = 0;
                GCMovement.stop(getChr());
                sayNode("Stay", player);
            }
            case PATROL -> onMenuSelect(0, player);
            default -> {
            }
        }
        return true;
    }

    private void onMenuSelect(int idx, Character player) {
        Character chr = getChr();
        if (idx == 0) { // Train here with me!
            if (player.getId() != leaderId) {
                sayNode("LoyalToLeader", player);
                menu.close(player);
                return;
            }
            if (MapMobIndex.level(chr.getMapId()) < 0) {
                sayNode("NoMobsHere", player);
                menu.close(player);
                return;
            }
            sayNode("StationHere", player);
            menu.close(player);
            BotRecruitManager.markStationHere(chr.getId());
            GCMovement.stop(chr);
            BotTypeManager.convertBotType(chr, BotTypeManager.BotType.TRAINING_BOT);
            return;
        }
        sayNode("Goodbye", player);
        menu.close(player);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    // Resolve the live leader from the channel's player storage each use (OPQ pattern): an id
    // survives relog, a Character reference doesn't.
    private Character resolveLeader() {
        if (leaderId <= 0) {
            return null;
        }
        Character chr = getChr();
        Character here = chr.getClient().getChannelServer().getPlayerStorage().getCharacterById(leaderId);
        if (here != null) {
            return here;
        }
        // Ticket 57: the leader changed channel - follow, the way a party member would (same map, new channel).
        Character elsewhere = soloMapling.server.SoloMaplingUtilities.world().getPlayerStorage().getCharacterById(leaderId);
        if (elsewhere == null || elsewhere.getMap() == null || elsewhere.getClient() == null
                || isBot(elsewhere)) {
            return null;
        }
        int channel = elsewhere.getClient().getChannel();
        if (channel == chr.getClient().getChannel() || !soloMapling.ArtificialPlayer.BotChannels.moveToChannel(chr, channel)) {
            return null;
        }
        return elsewhere; // moveToChannel ended the old follow session; the caller arms a fresh one
    }

    private int leaderIdFromParty() {
        Party party = getChr().getParty();
        if (party == null) {
            return -1;
        }
        Character lead = party.getLeader() != null ? party.getLeader().getPlayer() : null;
        if (lead != null && !isBot(lead)) {
            return lead.getId();
        }
        Character member = firstRealMember(party);
        return member == null ? -1 : member.getId();
    }

    private Character firstRealMember(Party party) {
        for (PartyCharacter pc : party.getMembers()) {
            Character p = pc == null ? null : pc.getPlayer();
            if (p != null && !isBot(p) && p.getMap() != null) {
                return p;
            }
        }
        return null;
    }

    // The ride ended: become a TrainingBot on a mob map, a SocialBot in a town. Callers speak
    // their own farewell first; this only re-types.
    private void fallbackConvert() {
        Character chr = getChr();
        BotRecruitManager.clearArmed(chr.getId());
        GCMovement.stop(chr);
        boolean grindable = MapMobIndex.level(chr.getMapId()) >= 0;
        BotTypeManager.convertBotType(chr,
                grindable ? BotTypeManager.BotType.TRAINING_BOT : BotTypeManager.BotType.SOCIAL_BOT);
    }

    private void sayNode(String node, Character player) {
        Character chr = getChr();
        if (chr == null || chr.getMap() == null || !GCMovement.isMapObserved(chr)) {
            return;
        }
        try {
            String line = BotDialogueHandler.getRandomResolvedLine(dialoguePath, botType, node, chr, player);
            if (line != null) {
                BotSpeak(chr, line);
            }
        } catch (Exception e) {
            // a missing dialogue node must never break the follow loop
        }
    }

    private static long now() {
        return System.currentTimeMillis();
    }

    @Override
    public synchronized void stopScheduledTask() {
        Character chr = getChr();
        if (chr != null) {
            BotRecruitManager.clearArmed(chr.getId()); // pending station/leader handoffs survive on purpose
            GCMovement.disable(chr); // ends the follow session + releases the shared movement lock
        }
        super.stopScheduledTask();
        log("[FollowerBot] stopped: " + (chr != null ? chr.getName() : "?"));
    }

    /** Reads players' typed lines itself (menu, bets, script): free text stays with it (bot-persistence ticket 21). */
    @Override
    public boolean consumesFreeText() {
        return true;
    }
}
