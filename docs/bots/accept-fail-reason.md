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
