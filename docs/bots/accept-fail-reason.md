# accept-fail-reason (1.6.6, step 1): expected outcome

Card: accept-fail-reason (t-sa-1 "Social acts: ACCEPT refused or held in 70/133 attempts on TEST at 3182 bots").

Written before the code (1.6.6 pilot rule).

## Why

On TEST (c614ea6f7, release-1.6.5 code) the social-act counters read ACCEPT 74 ok, 77 failed, and every failure is
counted under one key, `ACCEPT.failed: could not go through with it`. The real reason - what
`GameSocialFlows.accept` / `BotTalk.plainParty` said - is cut off by `SocialMetrics.shortReason`, which keeps only the
text before the first ':'. So nobody can tell whether 1.6.5 broke something (b350's map entry, e08's population) or
the party flow is simply busy at 3,000 regulars.

## Expected outcome

1. A failed ACCEPT is counted under its flow's reason, as one of a fixed set of name-free keys:
   `ACCEPT.failed: <key>` (the reason now leads, because the counter keeps at most 40 characters before the first
   ':'), where `<key>` is one of
   `one of them left` (the two were on different maps - b350's signature),
   `went offline`, `the party system refused`, `nobody could join` (the joiner had a party or another channel),
   `LFG rules said no`, `the LFG party did not form`, `not an invitation this knows`, or `other`.
2. No character name ever reaches a counter key (the flow reasons carry names: "Bob went offline").
3. `ACCEPT.failed` (the total) still counts once per failure; t-sa-1's verdict is unchanged (the breakdown keys carry
   a ':' and are skipped by its per-kind sums).
4. Nothing a player sees changes; the regular's line and the release of the invite are as before.

## Proof

- SocialActsTest: one failed ACCEPT per flow reason lands on its key, a name in the reason does not reach any key,
  and an unknown reason is `other`. Fails before (every failure is keyed `could not go through with it`), passes after.
- After the post-GP6 TEST deploy: TEST's `/srv/slums-dashboard-test/data/live.json` `social.acts.counters` shows the
  ACCEPT breakdown; `one of them left` dominating points at b350, `nobody could join` at party load (step 2 card).

# accept-fail-reason, step 2 (1.6.6, S): expected outcome

Written before the code. Coordinator's go 2026-11-07 (after v72 and the oid floor). Base 997af2bf6.

## Why

Step 1's split came in on TEST (c42a85edf, up since 21:49 UTC 2026-11-07, about 3,172 online): ACCEPT 16 ok, 28
failed, and all 28 failures are `LFG rules said no`. The cause is in `GameSocialFlows.propose`: when the two fit a
PQ lobby, `WorldSense.offers` puts the LFG plan in as the PARTY offer even when the LFG rules already said no
(`BotTalk.pqOffer` marks it `Feasible.no(<decline>)`), and propose sends it anyway. The invite can only fail at
ACCEPT. That is about 17 dead invites an hour at that population.

## Expected outcome

1. **propose never sends an offer that is already decided no.** It sends the PARTY offer if its `decided` is null
   (a plain duo) or ok; otherwise the COME_ALONG offer if there is one and it is not decided no; otherwise no
   invite (`INVITE.missed: nothing to invite them to`, as today when there is nothing). A PQ slot reserved for an
   offer that is not the one sent is released, as today. The choice is a pure method,
   `GameSocialFlows.invitation(offers)`.
2. **accept keeps its `lfg.yes()` guard**, so an invite opened before the change (or any other route to a declined
   plan) still fails cleanly with the same reason.
3. **Policy declines are not breakage.** `SocialActions.POLICY_DECLINES` (public; `SocialActs` is package-private and
   the probe lives in autoqa) lists `LFG rules said no`. `Probes164Social.socialActsProblem` takes a kind's policy
   declines (`ACCEPT.failed: LFG rules said no`) out of that kind's refused count before the refused-share check.
   The counter itself still shows on the dashboard.
4. **What players see.** Social acts only pair two regulars (`SocialDecider.candidates` skips real players), so this
   is chat between regulars that a nearby player can read. Today each dead invite is a regular saying
   "<name> wanna party?" and then nothing happening. After the change, each of those (at most about 17 an hour
   server-wide at about 3,172 online, so about 1 an hour per 190 online, on whichever map the pair stands) either
   becomes a come-along invite (the same "wanna party?" line, then "sure <name>" and the two leave for the town),
   or produces no line at all. The share that falls back is not known before TEST: it needs the inviter to have a
   town trip it can start now. The fallback replaces an invite that was already being sent, so invite lines can
   only drop, never grow.
5. **The chat cap.** The one-regular-chat-per-player-hour cap (`SocialGate.botChatAllowed`) is applied only in
   `BotConversation` and `BotRecognition`, to a regular starting a chat with a real player. INVITE is gated by
   `SocialIntents.mayAim` (PARTY_INVITE, FRIENDLY tier), which does not apply that cap, and an INVITE's target is
   never a real player. So the fallback does not touch the cap, and nothing in it needs the cap. Whether INVITE's
   public line should also count against nearby players' caps is a question for the Coordinator, not changed here.

## Proof

- GameSocialFlowsInvitationTest: a declined PQ offer with a come-along available invites to the come-along; a
  declined PQ offer alone invites nothing; an ok PQ offer and a plain duo are sent as before; a come-along alone is
  sent as before. Written against the extracted method with today's behaviour first (the first two fail), then the
  behaviour changes.
- Probes164SocialTest: counters whose ACCEPT refusals are all `LFG rules said no` pass the refused-share check;
  the same counters with an ordinary reason still fail it. Fails before (policy declines count as refusals).
- After the TEST deploy: `ACCEPT.failed: LFG rules said no` stops growing; `INVITE.missed: nothing to invite them to`
  grows by the invites that had no fallback.
