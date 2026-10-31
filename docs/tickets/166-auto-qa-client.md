# Ticket 166: the auto-QA client (scripted runs that record, instead of an agent driving)

Owner, 2026-10-31 (UTC; Fri 2026-10-30 evening PT): "instead of the agent controlling the player, the agent can create a
set of instructions and records data and images" ... "We can start it now. but we will create a auto QA client".

## Why

Real-client QA today is an agent steering one client live (client MCP, `tools/ops/client-mcp-win`). The 1.6.2 rechecks
on 2026-10-30 showed what that costs:

- fixed-timing clicks race the client: the world-select "empty channel list" (the click on Bera lands before the
  channel list is processed; the server sent it every time, packet capture 23:22 and 23:24 UTC), 4-9 tries per login;
- 1 frame a second misses short effects (the Heal column, b140: frames 0.27 s after each heal, unproven);
- one typo changes the test (`@dismiss` sent with no name, 23:29 UTC, read as a dismiss bug);
- one client, one channel, one tester at a time; every step costs model time; nothing is reproducible.

## What we build

An **auto-QA client**: our own QA build of the game client plus a runner that plays a written script and records
everything. The agent writes scripts and reviews recordings; it never steers live.

1. **Script** (`qa-scripts/<id>.yaml`, in the repo): one file per check.
   - `steps`: login (account from the QA vault, never in the file), pick world/channel, chat line, GM command, key or
     key-hold for N ms, move/jump, click a NAMED UI target (anchor table, not raw x,y), wait N ms (discouraged).
   - `waits`: every wait is on a signal, never a timer: a client event (channel list drawn, map loaded, chat line
     matching a regex, UI window open) or a server event (a QA log/journal line matching a regex, via the server's QA
     event feed below), each with a timeout that FAILS the run (same rule as b222).
   - `capture`: screenshot burst (fps, duration) or video clip around a step or a signal; the chat log; the client's
     own packet trace for chosen opcodes; HP/MP/position samples.
   - `expect`: what the reviewer must see (text for the reviewer and, where possible, machine checks on recorded data).
2. **Runner** (Windows, the VM or Silvia's QA folder only): launches the QA client, plays the script exactly,
   writes one **run folder**: `manifest.json` (script id + hash, client build, server commit from DEPLOYED_COMMIT,
   start/end times UTC, per-step result and timing), `frames/` and `clips/`, `chat.log`, `packets.log`, `client.log`,
   and the server side pulled for the same window (journal slice, `logs/qa/*`, latest.json entry).
   Run folders go to 187 (`/root/work/qa-runs/<date>/<id>-<UTC>/`), then the release book links them.
3. **Review**: an agent (or the owner) reads the run folder and marks PASS/FAIL with the evidence path. That is the
   evidence triage needs to close a card (commit + log + time + screenshots), and auto-QA's release run can list
   client checks next to the server checks.
4. **QA client build** (Client lane): the normal client plus a QA module that (a) exposes ready signals the runner
   waits on (channel list drawn, map loaded, window open, chat received), (b) takes in-process screenshots/clips at a
   real frame rate, (c) logs chosen packets, (d) can run several instances side by side (two clients on one map:
   b233's real-player Heal). It never ships to players: TEST/RC only, separate build id, refused by live's
   MIN_CLIENT_BUILD gate rules for QA builds.

## Server side (Server Dev)

- A **QA event feed**: TEST's server streams matching QA-log/journal lines for a run (filtered by the run's character
  ids and topics) so script waits can say "wait for `PARTY HEAL .* -> {me}`". TEST only, behind the staff/QA secret
  path, never live.
- `!qa` hooks the scripts need (staging that really saves, b228-style; deterministic channel/map placement).
- Each server auto-QA check that is HALF/MANUAL today gets a client script where it can (t-cl-*, t-rg-*, t-mv-1,
  t-jq-1, t-ga-4, t-mr-2, t-cc-1, and the b140/b233/b167 visual checks).

## Lanes

- Client lane: the QA client build, the runner, the recorder, the anchor table.
- Server Dev: this spec, the QA event feed, staging hooks, script format checks, the first scripts for the 1.6.2
  rechecks (b140 Heal column, b233 two-client Heal, KPQ stage-1 with a real leader, @botme alt playtest).
- Coordinator: release book links to run folders; triage closes cards from them.

## First milestone

One script end to end on TEST: log in on ch 3 (waiting on "channel list drawn", no timer), party a Bishop follower,
`!hurt` to 40%, burst-capture 10 fps for 2 s on the server's `PARTY HEAL` line, save the run folder to 187. That one
script settles b140 and shows the whole loop.

## Owner decisions (grill 2026-10-31, ~03:00 UTC)

- Runner home: **Silvia's QA folder** first (under Z:\MSv83\SlumsMS-dev only); the VM later once its display works.
- Images: **screenshot bursts + short clips** only for checks that need motion; no full-run video.
- Schedule: **nightly + after every TEST deploy**; results land in the release book next to the server checks.
- Review: **an agent reviews every run folder** and marks PASS/FAIL with evidence; the owner sees FAILs.
- Install: **separate QA client install** next to the normal client (own folder, QA module built in, TEST/RC only,
  refused by live); never touches the owner's game install or the launcher.
- Silvia busy (owner playing/active, or a game in the foreground): a scheduled run **waits and retries every 30 min**;
  it never steals the screen.
- First scripts cover: **visual/effect checks, login + channel + basics, @botme alt flows, party quests with bots,
  and any feature or change**.
- Rule: **every new player-visible feature ships with a client script** that passes on TEST; for bug fixes it's
  optional (use it when the bug was client-visible).
- Odd-but-not-FAIL runs: the reviewing agent **files a bug**, starting at **S4 "unconfirmed"** with the frames
  attached; triage raises it once confirmed.
- Gate: **from 1.7.0**, a release needs the client scripts PASS (or known-accepted) on the release candidate; 1.6.2
  ships on the hand checks.
