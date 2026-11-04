package soloMapling.ArtificialPlayer.BotSocialSystem;

import org.junit.jupiter.api.Test;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Axes;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BondEvent;
import soloMapling.ArtificialPlayer.BotAffinitySystem.BotBonds;
import soloMapling.ArtificialPlayer.BotAffinitySystem.Stage;
import soloMapling.ArtificialPlayer.BotMemory;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialActions.Outcome;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Emotion;
import soloMapling.ArtificialPlayer.BotSocialSystem.SocialMind.Need;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ticket 181 stage 4: each of the 16 social acts, through {@link SocialActions#perform}, with the observable state
 * change that makes it "implemented": bond axes and stage, a memory note, an emotion or need, a chat bubble through a
 * recording world, a movement call. Plus each act's precondition refusal and its failure path.
 */
class SocialActsTest extends SocialActTestBase {

    private void alone() {
        world.put(ALICE, "Alice", 0, 0);
        world.put(BOB, "Bob", 100, 0);
        mem(ALICE);
        mem(BOB);
    }

    private double mind(int id, Emotion e) {
        return mem(id).mind(m -> m.emotion(e, now()));
    }

    private double need(int id, Need n) {
        return mem(id).mind(m -> m.need(n, now()));
    }

    // ── 1. GREET and 2. CONVERSE (wrapped) ──

    @Test
    void greetRunsTheExistingFlowAndMeetsTheNeed() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        double before = need(ALICE, Need.COMPANIONSHIP);
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.GREET, now());
        assertTrue(o.landed(), o.reason());
        assertEquals(new SocialTestKit.FakeFlows.Call(SocialActKind.GREET, ALICE, BOB), flows.calls.get(0));
        assertTrue(need(ALICE, Need.COMPANIONSHIP) < before, "greeting meets a little of the companionship need");
        assertEquals(1L, SocialMetrics.actCounters().get("GREET.ok"));
        assertEquals(1L, SocialBudget.counters().get("recorded"), "bot-talk's flow records the budget once; the registry adds no second record");
    }

    @Test
    void greetRefusalsAreReasonedNotSilent() {
        alone();
        Outcome stranger = SocialActions.perform(ALICE, BOB, SocialActKind.GREET, now());
        assertFalse(stranger.happened());
        assertTrue(stranger.reason().contains("does not know"), stranger.reason());
        SocialTestKit.meet(ALICE, BOB, now());
        world.place(BOB, 3000, 0);
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.GREET, now()).reason().contains("earshot"));
        world.place(BOB, 100, 0);
        world.busy.add(BOB);
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.GREET, now()).reason().contains("free"));
        assertTrue(flows.calls.isEmpty(), "no flow ran on a refusal");
        assertEquals(3L, SocialMetrics.actCounters().get("GREET.refused"));
    }

    @Test
    void converseNeedsIdleBotsAndAnAcquaintance() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        world.notIdle.add(ALICE);
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.CONVERSE, now()).reason().contains("idle"));
        world.notIdle.clear();
        flows.blockers.put(SocialActKind.CONVERSE, "no chatting on this kind of map");
        assertEquals("no chatting on this kind of map", SocialActions.perform(ALICE, BOB, SocialActKind.CONVERSE, now()).reason());
        flows.blockers.clear();
        double lonely = mind(ALICE, Emotion.LONELINESS);
        mem(ALICE).mind(m -> { m.feel(Emotion.LONELINESS, 0.5, now()); return null; });
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.CONVERSE, now());
        assertTrue(o.landed(), o.reason());
        assertTrue(mind(ALICE, Emotion.LONELINESS) < lonely + 0.5, "a chat eases loneliness");
        flows.failing.add(SocialActKind.CONVERSE);
        tick(10 * 60_000);
        Outcome failed = SocialActions.perform(ALICE, BOB, SocialActKind.CONVERSE, now());
        assertTrue(failed.happened() && !failed.landed(), failed.reason());
        assertEquals(1L, SocialMetrics.actCounters().get("CONVERSE.failed"));
    }

    // ── 3. COMPLIMENT ──

    @Test
    void complimentWarmsTheTargetsEdgeAndPridesTheTarget() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.meet(BOB, ALICE, now());
        Axes before = BotBonds.axes(BOB, ALICE);
        double pride = mind(BOB, Emotion.PRIDE);
        double recog = need(BOB, Need.RECOGNITION);
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.COMPLIMENT, now());
        assertTrue(o.landed(), o.reason());
        // a chat bubble went out through the world, game-only and in the bank's style
        assertEquals(1, world.said.size());
        assertEquals(ALICE, world.said.get(0).id());
        assertTrue(world.said.get(0).text().chars().allMatch(c -> c < 128), world.said.get(0).text());
        assertEquals(1, world.emotes.size());
        // the target's edge to the actor warmed
        Axes after = BotBonds.axes(BOB, ALICE);
        assertTrue(after.affection() > before.affection(), "affection up");
        assertTrue(after.respect() > before.respect(), "respect up");
        // the target felt it
        assertTrue(mind(BOB, Emotion.PRIDE) > pride);
        assertTrue(need(BOB, Need.RECOGNITION) < recog);
        // both remember it
        assertFalse(world.noteWith(ALICE, BOB, "compliment").isEmpty());
        assertFalse(world.noteWith(BOB, ALICE, "compliment").isEmpty());
        // not again at once
        assertFalse(SocialActions.perform(ALICE, BOB, SocialActKind.COMPLIMENT, now() + 1000).happened());
    }

    @Test
    void complimentFailsWhenTheLineCannotBeSentAndChangesNothing() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        world.sayOk = false;
        Axes before = BotBonds.axes(BOB, ALICE);
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.COMPLIMENT, now());
        assertTrue(o.happened() && !o.landed(), o.reason());
        assertEquals(before, BotBonds.axes(BOB, ALICE));
        assertTrue(world.notes.isEmpty());
    }

    @Test
    void complimentIsRefusedOnSourTermsOrInAFight() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.sour(ALICE, BOB, 3, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.COMPLIMENT, now()).reason().contains("sour"));
    }

    // ── 4./5. help ──

    @Test
    void askHelpIsTheExistingAskAndOnlyWhenAllowed() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        flows.blockers.put(SocialActKind.ASK_HELP, "no help to ask for here");
        assertFalse(SocialActions.perform(ALICE, BOB, SocialActKind.ASK_HELP, now()).happened());
        flows.blockers.clear();
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.ASK_HELP, now());
        assertTrue(o.landed(), o.reason());
        assertEquals(SocialActKind.ASK_HELP, flows.calls.get(0).kind());
    }

    @Test
    void offerHelpMakesTheHelpedOneOweAndRemember() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.meet(BOB, ALICE, now());
        double owes = BotBonds.axes(BOB, ALICE).obligation();
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.OFFER_HELP, now());
        assertTrue(o.landed(), o.reason());
        assertTrue(BotBonds.axes(BOB, ALICE).obligation() > owes, "the helped one owes the helper");
        assertFalse(world.noteWith(BOB, ALICE, "help").isEmpty());
        flows.failing.add(SocialActKind.OFFER_HELP);
        tick(30 * 60_000);
        assertFalse(SocialActions.perform(ALICE, BOB, SocialActKind.OFFER_HELP, now()).landed());
    }

    // ── 6. GIVE_GIFT through the seam ──

    @Test
    void giftDefaultsToAnAffinityOnlyGestureAndNeedsAFriend() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.GIVE_GIFT, now()).reason().contains("close enough"));
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.meet(BOB, ALICE, now());
        Axes receiver = BotBonds.axes(BOB, ALICE);
        Axes giver = BotBonds.axes(ALICE, BOB);
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.GIVE_GIFT, now());
        assertTrue(o.landed(), o.reason());
        assertTrue(BotBonds.axes(BOB, ALICE).obligation() > receiver.obligation(), "the receiver owes the giver");
        assertTrue(BotBonds.axes(BOB, ALICE).affection() > receiver.affection());
        assertTrue(BotBonds.axes(ALICE, BOB).obligation() < giver.obligation(), "the giver is owed");
        assertFalse(world.noteWith(BOB, ALICE, "gave me").isEmpty());
        assertFalse(world.noteWith(ALICE, BOB, "gave them").isEmpty());
    }

    @Test
    void aPluggedInGiftSeamIsWhatGetsCalledAndItsRefusalIsAFailure() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        java.util.List<String> given = new java.util.ArrayList<>();
        SocialActions.giftSeam = new GiftSeam() {
            @Override
            public Result give(int giverId, int receiverId, long now) {
                given.add(giverId + ">" + receiverId);
                return new Result(true, "a pair of earrings", null);
            }
        };
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.GIVE_GIFT, now()).landed());
        assertEquals(java.util.List.of(ALICE + ">" + BOB), given);
        assertFalse(world.noteWith(BOB, ALICE, "earrings").isEmpty(), "the seam's description is what is remembered");
        SocialActions.giftSeam = (g, r, n) -> GiftSeam.Result.no("nothing that fits");
        tick(7 * 3_600_000L);
        Outcome refused = SocialActions.perform(ALICE, BOB, SocialActKind.GIVE_GIFT, now());
        assertTrue(refused.happened() && !refused.landed());
        assertEquals("nothing that fits", refused.reason());
        SocialActions.giftSeam = new GiftSeam() {
            @Override
            public String blocker(int giverId, int receiverId, long now) {
                return "has nothing to give";
            }

            @Override
            public Result give(int giverId, int receiverId, long now) {
                throw new AssertionError("blocked before it is called");
            }
        };
        tick(7 * 3_600_000L);
        assertEquals("has nothing to give", SocialActions.perform(ALICE, BOB, SocialActKind.GIVE_GIFT, now()).reason());
    }

    // ── 7./8./9. INVITE, ACCEPT, DECLINE ──

    @Test
    void inviteOpensAnInvitationThatTheOtherAnswers() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.meet(BOB, ALICE, now());
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.INVITE, now());
        assertTrue(o.landed(), o.reason());
        SocialActions.Invite open = SocialActions.pendingInvite(BOB, ALICE, now());
        assertNotNull(open);
        assertEquals("a duo party", open.what());
        assertFalse(world.said.isEmpty(), "the invitation was voiced");
        assertEquals("already invited them", SocialActions.perform(ALICE, BOB, SocialActKind.INVITE, now() + 1_000).reason());
        // Bob accepts: the flow carries it out
        tick(10_000);
        Outcome yes = SocialActions.perform(BOB, ALICE, SocialActKind.ACCEPT, now());
        assertTrue(yes.landed(), yes.reason());
        assertEquals(1, flows.accepted.size());
        assertNull(SocialActions.pendingInvite(BOB, ALICE, now()), "answered: closed");
        assertFalse(world.noteWith(ALICE, BOB, "yes to my invite").isEmpty());
        // there is nothing left to answer
        assertTrue(SocialActions.perform(BOB, ALICE, SocialActKind.DECLINE, now()).reason().contains("no open invitation"));
    }

    @Test
    void declineSaysNoAndStingsTheInviter() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.meet(BOB, ALICE, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.INVITE, now()).landed());
        double sad = mind(ALICE, Emotion.SADNESS);
        tick(10_000);
        Outcome no = SocialActions.perform(BOB, ALICE, SocialActKind.DECLINE, now());
        assertTrue(no.landed(), no.reason());
        assertTrue(mind(ALICE, Emotion.SADNESS) > sad, "being turned down stings");
        assertFalse(world.noteWith(ALICE, BOB, "turned down").isEmpty());
        assertEquals(1, flows.released.size(), "what the invitation reserved went back");
        assertTrue(flows.accepted.isEmpty());
    }

    @Test
    void anInvitationThatNobodyAnswersLapsesAndIsGivenBack() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.meet(BOB, ALICE, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.INVITE, now()).landed());
        tick(SocialActions.INVITE_TTL_MS + 1);
        SocialActions.tick(now());
        assertNull(SocialActions.pendingInvite(BOB, ALICE, now()));
        assertEquals(1, flows.released.size());
        assertEquals(1L, SocialMetrics.actCounters().get("INVITE.expired"));
    }

    @Test
    void anAcceptThatCannotBeCarriedOutIsAFailureAndReleases() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.meet(BOB, ALICE, now());
        SocialActions.perform(ALICE, BOB, SocialActKind.INVITE, now());
        flows.failing.add(SocialActKind.ACCEPT);
        Outcome o = SocialActions.perform(BOB, ALICE, SocialActKind.ACCEPT, now());
        assertTrue(o.happened() && !o.landed(), o.reason());
        assertEquals(1, flows.released.size());
    }

    // ── 10./11./12. fights and repair ──

    /** Gives Alice a grudge against Bob (not a fight yet) so ARGUE is legal. */
    private void grudge() {
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.befriend(BOB, ALICE, false, now());
        SocialTestKit.sour(ALICE, BOB, 2, now());
        tick(30 * 60_000L); // the ARGUE bond event has a 20 minute cooldown per pair: the act must not hit it
    }

    @Test
    void arguingSoursBothEdgesAngersBothAndLeavesAFightInMemory() {
        alone();
        grudge();
        Axes aBefore = BotBonds.axes(ALICE, BOB);
        Axes bBefore = BotBonds.axes(BOB, ALICE);
        double angerB = mind(BOB, Emotion.ANGER);
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now());
        assertTrue(o.landed(), o.reason());
        Axes aAfter = BotBonds.axes(ALICE, BOB);
        Axes bAfter = BotBonds.axes(BOB, ALICE);
        assertTrue(bAfter.resentment() > bBefore.resentment() && bAfter.rivalry() > bBefore.rivalry(), "the target's edge");
        assertTrue(aAfter.resentment() > aBefore.resentment() && aAfter.rivalry() > aBefore.rivalry(), "the actor's edge");
        assertTrue(bAfter.affection() < bBefore.affection());
        assertTrue(mind(BOB, Emotion.ANGER) > angerB + 0.1, "the one argued with is angry");
        // an insult line from the roast/argue bank and the other's answer, game only
        assertEquals(2, world.said.size());
        assertEquals(ALICE, world.said.get(0).id());
        assertEquals(BOB, world.said.get(1).id());
        assertFalse(world.noteWith(ALICE, BOB, "my temper").isEmpty());
        assertFalse(world.noteWith(BOB, ALICE, "their temper").isEmpty());
        SocialContext c = SocialActions.build(ALICE, BOB, now());
        assertTrue(c.memory().openFight() && c.memory().iStartedIt(), "Alice remembers she started it");
    }

    @Test
    void neverArguesWithACloseFriendOverNothingNorWithoutAReason() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, true, now());
        assertEquals(Stage.CLOSE_FRIEND, BotBonds.stage(ALICE, BOB));
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now()).reason().contains("nothing to argue"));
        mem(ALICE).mind(m -> { m.feel(Emotion.ANGER, 0.6, now()); return null; });
        SocialTestKit.sour(ALICE, BOB, 1, now());
        String why = SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now()).reason();
        assertTrue(why.contains("close friend") || why.contains("nothing to argue"), why);
    }

    @Test
    void arguingIsBoundedPerPairPerDay() {
        alone();
        grudge();
        int landed = 0;
        for (int i = 0; i < 10; i++) {
            SocialTestKit.sour(ALICE, BOB, 1, now()); // the grudge keeps being fed so only the bounds can stop it
            if (SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now()).landed()) {
                landed++;
            }
            tick(40 * 60_000);
        }
        assertTrue(landed >= 1 && landed <= SocialActions.ARGUES_PER_DAY + 1, "arguments in 6h40: " + landed);
        assertTrue(SocialMetrics.actCounters().getOrDefault("ARGUE.refused", 0L) >= 1);
    }

    @Test
    void apologizeThenReconcileRecoverAnEstrangedPairGraduallyAndEndTheFight() {
        alone();
        grudge();
        // both edges end up ESTRANGED by repeated spats
        for (int i = 0; i < 2; i++) {
            BotBonds.record(ALICE, BOB, BondEvent.ARGUE, now(), true);
            BotBonds.record(BOB, ALICE, BondEvent.ARGUE, now(), true);
        }
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now()).landed());
        Stage rift = BotBonds.stage(BOB, ALICE);
        assertTrue(rift == Stage.ESTRANGED || rift == Stage.RIVAL, "the rift: " + rift);
        // no apology yet, so no reconciliation
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.RECONCILE, now() + 1).reason().contains("sorry"));
        // time cools both; Alice apologizes (find a seed where Bob takes it)
        tick(3 * 86_400_000L);
        Axes bobBefore = BotBonds.axes(BOB, ALICE);
        SocialSim.useForTest(clock::get, seedWith(BOB, "social-accept", true));
        Outcome taken = SocialActions.perform(ALICE, BOB, SocialActKind.APOLOGIZE, now());
        assertTrue(taken.landed(), "Bob takes the apology: " + taken.reason() + " " + BotBonds.axes(ALICE, BOB));
        Axes bobAfter = BotBonds.axes(BOB, ALICE);
        assertTrue(bobAfter.resentment() < bobBefore.resentment(), "resentment down");
        assertTrue(bobAfter.trust() > bobBefore.trust(), "trust up");
        assertFalse(world.noteWith(BOB, ALICE, "said sorry").isEmpty());
        assertFalse(world.said.isEmpty());
        // the stage climbs back at most one rung per event; it is not a jump to FRIEND
        Stage afterApology = BotBonds.stage(BOB, ALICE);
        assertTrue(afterApology.ordinal() <= Stage.FRIEND.ordinal() || afterApology == Stage.ESTRANGED
                || afterApology == Stage.RIVAL, "after the apology: " + afterApology);
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.APOLOGIZE, now() + 60_000).reason().contains("already"));
        // days later, make up
        tick(3 * 86_400_000L);
        Axes beforeUp = BotBonds.axes(BOB, ALICE);
        Stage stageBeforeUp = BotBonds.stage(BOB, ALICE);
        SocialSim.useForTest(clock::get, seedWith(BOB, "social-accept", true));
        Outcome madeUp = SocialActions.perform(ALICE, BOB, SocialActKind.RECONCILE, now());
        assertTrue(madeUp.landed(), "Bob makes up: " + madeUp.reason() + " " + BotBonds.axes(ALICE, BOB));
        assertTrue(BotBonds.axes(BOB, ALICE).resentment() < beforeUp.resentment(), "resentment fell again");
        Stage firstRung = BotBonds.stage(BOB, ALICE);
        if (stageBeforeUp == Stage.ESTRANGED || stageBeforeUp == Stage.RIVAL) {
            assertTrue(firstRung != Stage.FRIEND && firstRung != Stage.CLOSE_FRIEND,
                    "one event moves a stage at most one rung: " + stageBeforeUp + " -> " + firstRung);
        } else {
            assertEquals(Stage.ACQUAINTANCE, stageBeforeUp, "time alone had healed the rift to ACQUAINTANCE");
            assertTrue(firstRung == Stage.ACQUAINTANCE || firstRung == Stage.FRIEND, firstRung.name());
        }
        // life goes on: shared time rebuilds the friendship
        for (int i = 0; i < 6; i++) {
            tick(40 * 60_000L);
            BotBonds.together(ALICE, BOB, BondEvent.PARTY);
            BotBonds.together(ALICE, BOB, BondEvent.PQ);
        }
        Stage healed = BotBonds.stage(BOB, ALICE);
        assertTrue(healed == Stage.FRIEND || healed == Stage.CLOSE_FRIEND, "back to friends, gradually: " + healed);
        SocialContext c = SocialActions.build(ALICE, BOB, now());
        assertFalse(c.memory().openFight(), "making up ends the fight in memory");
    }

    @Test
    void anApologyCanBeRefusedAndThenItAngersAndEmbarrasses() {
        alone();
        grudge();
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now()).landed());
        tick(2 * 3_600_000L);
        SocialSim.useForTest(clock::get, seedWith(BOB, "social-accept", false));
        Outcome refused = SocialActions.perform(ALICE, BOB, SocialActKind.APOLOGIZE, now());
        assertTrue(refused.reason().startsWith("rejected"), "Bob refuses: " + refused.reason());
        assertTrue(refused.happened() && !refused.landed());
        assertFalse(world.noteWith(ALICE, BOB, "turned away").isEmpty());
        assertTrue(SocialMetrics.actCounters().getOrDefault("APOLOGIZE.rejected", 0L) >= 1);
        SocialContext c = SocialActions.build(ALICE, BOB, now());
        assertFalse(c.memory().apologized(), "a refused apology is not an apology made");
    }

    @Test
    void apologizeIsRefusedWithNoFightAndWhenTooAngry() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.APOLOGIZE, now()).reason().contains("no fight"));
        grudge();
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.ARGUE, now()).landed());
        mem(ALICE).mind(m -> { m.feel(Emotion.ANGER, 1.0, now()); return null; });
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.APOLOGIZE, now() + 3_600_000L).reason().contains("too angry"));
    }

    // ── 13./14. information ──

    @Test
    void shareInfoAndRumorRunTheGossipFlows() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.meet(BOB, ALICE, now());
        mem(BOB);
        Outcome info = SocialActions.perform(ALICE, BOB, SocialActKind.SHARE_INFO, now());
        assertTrue(info.landed(), info.reason());
        assertEquals(SocialActKind.SHARE_INFO, flows.calls.get(0).kind());
        // a rumor needs a listener who trusts the teller: strangers' edges do not carry one
        Outcome rumor = SocialActions.perform(ALICE, BOB, SocialActKind.SPREAD_RUMOR, now());
        assertFalse(rumor.happened(), "no trust yet: " + rumor.reason());
        SocialTestKit.befriend(ALICE, BOB, false, now());
        flows.blockers.put(SocialActKind.SPREAD_RUMOR, "no rumor to pass on");
        assertEquals("no rumor to pass on", SocialActions.perform(ALICE, BOB, SocialActKind.SPREAD_RUMOR, now()).reason());
    }

    // ── 15. AVOID ──

    @Test
    void avoidWalksAwayKeepsTheDistanceAndExpires() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        SocialTestKit.sour(ALICE, BOB, 3, now());
        world.place(BOB, 120, 0);
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.AVOID, now());
        assertTrue(o.landed(), o.reason());
        // a movement call away from Bob, at least the keep distance
        assertEquals(1, world.walks.size());
        assertEquals(ALICE, world.walks.get(0).id());
        assertTrue(Math.abs(world.walks.get(0).x() - 120) >= SocialActions.AVOID_KEEP_PX, "walked to " + world.walks.get(0));
        // an avoid record other stages can read
        assertTrue(SocialActions.avoiding(ALICE, BOB, now()));
        assertFalse(SocialActions.avoiding(BOB, ALICE, now()), "directed");
        assertEquals(1, SocialActions.avoidRecords(ALICE, now()).size());
        // while it holds, Alice does not greet or chat with Bob
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.GREET, now() + 1).reason().contains("avoiding"));
        // Bob walks up to her: the upkeep walks her off again (at most every 20 s)
        world.place(BOB, world.body(ALICE).x() + 100, 0);
        tick(25_000);
        SocialActions.tick(now());
        assertEquals(2, world.walks.size(), "walked off again");
        // it expires
        tick(20 * 60_000L);
        SocialActions.tick(now());
        assertFalse(SocialActions.avoiding(ALICE, BOB, now()));
        assertTrue(SocialActions.avoidRecords(ALICE, now()).isEmpty());
    }

    @Test
    void avoidNeedsAReasonAndAnIdleBotAndIsClearedByMakingUp() {
        alone();
        SocialTestKit.meet(ALICE, BOB, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.AVOID, now()).reason().contains("no reason"));
        SocialTestKit.sour(ALICE, BOB, 3, now());
        world.notIdle.add(ALICE);
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.AVOID, now()).reason().contains("idle"));
        assertTrue(world.walks.isEmpty());
    }

    // ── 16. SPEND_TIME ──

    @Test
    void spendTimeStandsNearThenMeetsNeedsAndRecordsNearWhenTheTimeIsUp() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.befriend(BOB, ALICE, false, now());
        mem(ALICE).mind(m -> { m.feel(Emotion.LONELINESS, 0.6, now()); return null; });
        world.place(BOB, 400, 0);
        double need = need(ALICE, Need.COMPANIONSHIP);
        double fam = BotBonds.axes(ALICE, BOB).familiarity();
        Outcome o = SocialActions.perform(ALICE, BOB, SocialActKind.SPEND_TIME, now());
        assertTrue(o.landed(), o.reason());
        assertEquals(1, world.walks.size(), "walked to stand beside Bob");
        assertTrue(Math.abs(world.walks.get(0).x() - 400) <= 60, "stands next to Bob: " + world.walks.get(0));
        assertEquals("already together", SocialActions.perform(ALICE, BOB, SocialActKind.SPEND_TIME, now() + 1).reason());
        double during = need(ALICE, Need.COMPANIONSHIP);
        assertTrue(during < need);
        tick(13 * 60_000L);
        SocialActions.tick(now());
        assertEquals(1L, SocialMetrics.actCounters().get("SPEND_TIME.completed"));
        assertTrue(need(ALICE, Need.COMPANIONSHIP) < during - 0.2 || need(ALICE, Need.COMPANIONSHIP) == 0.0, "needs met");
        assertTrue(need(BOB, Need.BELONGING) < 0.5);
        assertTrue(BotBonds.axes(ALICE, BOB).familiarity() > fam, "NEAR bond events counted");
        assertFalse(world.noteWith(ALICE, BOB, "hung out").isEmpty());
        assertFalse(world.noteWith(BOB, ALICE, "hung out").isEmpty());
    }

    @Test
    void spendTimeCountsAsPartyTimeInOnePartyAndIsAbandonedWhenTheyDrift() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        SocialTestKit.befriend(BOB, ALICE, false, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.SPEND_TIME, now()).landed());
        world.place(BOB, 5000, 0); // Bob wandered off
        tick(13 * 60_000L);
        SocialActions.tick(now());
        assertEquals(1L, SocialMetrics.actCounters().get("SPEND_TIME.abandoned"));
        assertTrue(world.noteWith(ALICE, BOB, "hung out").isEmpty(), "nothing was shared, nothing is remembered");
    }

    // ── the registry itself ──

    @Test
    void everyKindHasAnActionAndANeverThrowingPerform() {
        for (SocialActKind k : SocialActKind.values()) {
            assertNotNull(SocialActions.action(k), k.name());
            assertEquals(k, SocialActions.action(k).kind());
        }
        assertEquals(16, SocialActKind.values().length);
        alone();
        // garbage in: a player target, itself, an offline bot: reasoned refusals, never an exception
        for (SocialActKind k : SocialActKind.values()) {
            assertFalse(SocialActions.perform(ALICE, 7, k, now()).happened(), k + " to a real player");
            assertFalse(SocialActions.perform(ALICE, ALICE, k, now()).happened(), k + " to itself");
            assertFalse(SocialActions.perform(ALICE, SocialTestKit.BASE + 999, k, now()).happened(), k + " to nobody");
            assertFalse(SocialActions.perform(5, BOB, k, now()).happened(), k + " by a real player");
        }
        assertTrue(SocialActions.perform(ALICE, 7, SocialActKind.GREET, now()).reason().contains("SocialGate"));
    }

    @Test
    void flagOffRefusesEverythingAndChangesNothing() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        System.setProperty(SocialActions.SWITCH, "false");
        for (SocialActKind k : SocialActKind.values()) {
            Outcome o = SocialActions.perform(ALICE, BOB, k, now());
            assertFalse(o.happened(), k.name());
            assertEquals("social acts are off", o.reason());
        }
        assertTrue(SocialActions.best(ALICE, java.util.List.of(BOB), now()).isEmpty());
        assertTrue(world.said.isEmpty() && world.walks.isEmpty() && flows.calls.isEmpty() && world.notes.isEmpty());
        assertTrue(SocialMetrics.actCounters().isEmpty());
    }

    @Test
    void theBudgetStopsAnActAndRecordsOneThatHappened() {
        alone();
        SocialTestKit.befriend(ALICE, BOB, false, now());
        assertTrue(SocialActions.perform(ALICE, BOB, SocialActKind.COMPLIMENT, now()).landed());
        assertEquals(1L, SocialBudget.counters().get("recorded"));
        // the same pair, same act family, still inside the budget's gap: held back by the budget, not by the act
        SocialActions.resetForTest(); // forget the act's own 45-minute memory
        Outcome again = SocialActions.perform(ALICE, BOB, SocialActKind.COMPLIMENT, now() + 60_000);
        assertFalse(again.happened());
        assertTrue(again.reason().contains("social budget"), again.reason());
        assertEquals(1L, SocialMetrics.actCounters().get("COMPLIMENT.budget"));
    }
}
