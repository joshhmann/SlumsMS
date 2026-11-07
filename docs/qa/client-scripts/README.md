# Client scripts: real-client QA the machine plays (ticket 166, server side ticket 170)

Real-client QA is no longer an agent steering one client live. An agent **writes a script**, the **runner plays it**
on the QA client build against TEST, and the run **records frames, chat, packets, samples and the server's own lines**
into a run folder. An agent reviews the folder and marks PASS or FAIL with the evidence. The owner only sees FAILs and
the checks that need human judgment (feel, art, fun): `owner-vs-machine.md`.

| File | What |
|---|---|
| `qa-scripts/<id>.yaml` | one script per check (the id is the file name) |
| `docs/qa/client-scripts/schema.json` | the format (JSON Schema, draft 2020-12) |
| `docs/qa/client-scripts/anchors.yaml` | the named click targets and window names a script may use |
| `docs/qa/client-scripts/run-folder.md` | what a run leaves behind: `manifest.json`, frames, server slices, findings |
| `docs/qa/client-scripts/owner-vs-machine.md` | every hand check the owner does today: script or judgment |
| `tools/qa-scripts/validate` | the validator (`tools/qa-scripts/validate [files]`; all scripts when none given) |
| `tools/ops/qa-run-collect` | pulls the TEST side of a run (journal slice, `logs/qa`, `latest.json`) into the run folder |
| `tools/qa-runner/run` | the 187 orchestrator: `tools/qa-runner/run qa-scripts/<id>.yaml [--dry-run]` |
| `tools/qa-runner/verdict` | judges a run folder into per-t-id records: `tools/qa-runner/verdict <run folder> [--out DIR]` |

## Owner decisions (2026-10-31, ticket 166)

- The runner lives in **Silvia's QA folder** first (`Z:\MSv83\SlumsMS-dev\` only), the Windows VM later.
- A **separate QA client install** next to the normal client; never the owner's game install or the launcher.
- **Screenshot bursts + short clips** only (clips are assembled on 187 from bursts); no full-run video.
- Runs **nightly and after every TEST deploy**. If Silvia is busy (the owner playing or a game in front) a scheduled
  run **waits and retries every 30 min**; it never steals the screen.
- **An agent reviews every run folder** and marks PASS/FAIL with evidence. Odd but not FAIL: the reviewer **files an
  S4 "unconfirmed" bug** with the frames attached (the `findings` section of `manifest.json`); triage raises it once
  confirmed.
- **Every new player-visible feature ships with a client script that passes on TEST.** For bug fixes it is optional
  (use one when the bug was client-visible).
- **From 1.7.0 client scripts gate releases**: a release needs them PASS (or known-accepted) on the release candidate.
  1.6.2 ships on the hand checks.

## How a run works (the split runner)

```
187 orchestrator (tools/qa-runner, Server Dev)        Silvia worker (client-mcp run_script, Client lane)
  reads + validates the YAML                           launches the QA client, keeps the pipe to its QA module
  arms the feed topics, long-polls /qa/events  <-ssh-> sends keys, clicks, chat; relays pipe signals
  sends one step at a time  ------------------------>  runs it, answers when it was sent
  waits on client signals (relayed) and server lines   burst <fps> <ms> <label> -> qa-captures\<label>\*.bmp + frames.tsv
  writes manifest.json, pulls the folder from Silvia   
```

- The orchestrator (`tools/qa-runner/qarunner.py`) is built and tested against a fake feed and a fake worker
  (40 tests: the b140 script end to end through `SilviaWorker` with a fake transport, the transport's own remote
  program played by a thread against a temp folder, the manifest against its schema, the lost world click resent, a
  missing signal failing the run at its step, a server line from before the arming step not counting). Its
  `SilviaWorker` (ticket 170, 2026-10-31) talks to the worker's file queue over ssh
  one call per step: `status`, `stop_client`/`start_client` for a fresh client, `login_credentials {vault}`,
  `select_world`, `select_channel`, `select_character {slot}`, `click_anchor {name}`, `command` for `gm`/`chat`,
  `press_key` for `key`/`move`, `qa_burst`, and `qa_events {since}` for every wait. A wait never sleeps: it reads
  the QA pipe's event log by position, so a signal the client does not send fails its step at once with
  "client signal <name> not available in this client build" instead of timing out. The whole path is offline-tested;
  the first real run is the lane session's to make.
- The QA event feed and its secret never leave 164 and 187: the orchestrator calls the feed with `curl` **on 164 over
  ssh**, reading the secret there from `/etc/slums-inspect-test/secret` by path (never printed, never copied).
- 187 **pulls** the run folder from Silvia over ssh, then runs `tools/ops/qa-run-collect` for the server side.
- The orchestrator then judges the folder (`tools/qa-runner/verdict`, the same module the reviewer runs) and writes
  one record per t-id into the folder's `verdicts/`; `--publish <dir>` also copies them to TEST's
  `data/autoqa/client-verdicts/`, where the next auto-QA report write picks them up.
- The QA client build stamps caps bit 31 (`0x80000000`, client `kCapQaBuild`) and a release `<version>-qa`. Live
  refuses it at login; TEST and RC accept it only with `-Dslums.client.allowQa=true` (`ClientVersionGate`).

## How to run a script (187)

```bash
tools/qa-runner/run qa-scripts/b140-bot-heal-column.yaml --dry-run   # what it will do, no feed, no worker, no ssh
tools/qa-runner/run qa-scripts/b140-bot-heal-column.yaml             # the real run
CLIENT_MCP_TARGET=vm tools/qa-runner/run qa-scripts/<id>.yaml        # the Windows VM worker instead of Silvia
```

- Prerequisites: a running client-mcp worker on the QA box (`tools/ops/client-mcp heartbeat` says ALIVE), the vault
  file for each `account.vault` (`secrets/<vault>.txt`: account, password, PIC on one line each), and TEST serving
  the run's commit (`server.commit` in the manifest comes from `Server/DEPLOYED_COMMIT` on 164).
- One run stops a client the worker left running and starts a fresh QA client, so the run's result cannot be a
  half-played leftover. The worker's idle guard still applies: while the owner is using the PC the calls queue, and
  the run simply takes longer.
- The run folder lands in `/root/work/qa-runs/<date>/<id>-<stamp>/`; see `run-folder.md`. The manifest says
  `runner.worker` (`client-mcp silvia`) and, when the copy back failed, `runner.pull`.
- Exit code 0 = PASS, 1 = FAIL or ERROR (the manifest and the last line say which step), 2 = the script does not
  validate. The verdict is the script's own; a runner failure (no worker, ssh) ends the run as ERROR.

## The script

```yaml
id: b140-bot-heal-column            # = the file name
title: A bot Bishop's party Heal shows its column on a real client
covers: [b140, q48, t-su-1]         # bug ids, auto-QA check ids, release-book Test rows, ticket-N, feature-x
status: draft                        # draft (never passed on TEST) | ready | retired
server: test                         # always TEST
needs: [gm, proposed_anchors]        # staging the runner/vault must provide; see "needs" below
clients:
  me:                                # an alias; ${me.name} and ${me.id} are filled in after login
    account: {vault: aq-gm-1}        # an alias in the runner's vault on Silvia. NEVER a name or password
    character: {slot: 0}
    world: Bera
    channel: 3
    gm: true
steps: [...]
captures: [...]
expect: {...}
```

At most two clients (`two_clients` in `needs`); with two, every step, capture and check names its `client`.

### Steps (one action each, `do:`)

| do | fields | notes |
|---|---|---|
| `login` | | types the vault account and password (the worker reads them; the script never sees them) |
| `select_world` | `world: Bera` | |
| `select_channel` | `channel: 1-5` | must come after a `channels.ready` wait (validator rule: the "empty channel list" race) |
| `select_character` | | the client's `character.slot` |
| `chat` | `text` | map chat or an `@` command. Never starts with `!` |
| `gm` | `line` | a `!` command |
| `key` | `key`, `hold_ms` | A-Z, 0-9, F1-F12, arrows, ENTER, ESCAPE, SPACE, CTRL, ALT, SHIFT, CTRL+F10, CTRL+F11 ... |
| `move` | `dir`, `ms` | holds an arrow key |
| `jump` | `dir`, `catch`, `run_ms`, `hold_ms` | a jump with the game's jump key (Alt); `dir` left/right runs for `run_ms` first; `catch: true` holds Up through it to grab a rope or ladder; all keys released after `hold_ms` (default 800). `double`/`down` are refused (b360, 1.6.6) |
| `click` | `anchor`, `button` | a NAMED target from `anchors.yaml`, never x,y |
| `wait` | `signal`, `timeout_ms`, `armed_at`, `bind`, `retry` | see Waits |
| `sleep` | `ms`, `reason` | discouraged; see Timers |
| `mark` | `id` | a named point for captures and spans |
| `click_npc` | `npc` | clicks an NPC by id where the QA client draws it; it never walks (`out_of_view`: move, then again) |
| `drop_item` | `item`, `count` | drops `count` (default 1) of the item's first stack through the client's own sender (QA build 3) |
| `pickup_item` | `item` | picks up the newest drop of the item on the field through the client's own pickup sender; passes only when the server gives it to this character (QA build 15) |
| `shop_buy` | `item`, `quantity` | (an NPC shop open, e.g. after `click_npc`) buys `quantity` of `item` with the packet the shop's Buy button sends; passes only on the server's answer 0. Refusals: `not_enough_mesos`, `inventory_full`, `not_in_shop`, `no_shop`, `timeout` (QA build 16) |
| `shop_sell` | `item`, `quantity` | sells by item id; no `quantity` (or 0) sells every stack of it, one request per stack, each answered by the server (8). Refusals: `not_in_inventory`, `not_enough_items (sold n)`, `not_sold`, `no_shop`, `timeout` (QA build 16) |
| `shop_close` | | closes the open shop as its close button does (the server gets NPC_SHOP leave). Refusal: `no_shop` (QA build 16) |
| `use_item` | `item` | uses the item's first USE stack (drinks a potion) with USE_ITEM; passes when the server took one. Refusals: `not_in_inventory`, `not_used` (QA build 17) |
| `trade_invite` | `name` | opens a trade window and invites that player (on this field). Their accepting shows as the `trade.joined` client signal. Refusal: `not_on_map` (QA build 17) |
| `trade_accept` | | accepts the last trade invite (`trade.invited` signal). Refusal: `no_invite` |
| `trade_add_item` | `item`, `quantity` | puts the item's first stack in the trade (no `quantity` = the whole stack); passes once the server shows it. Refusals: `no_trade`, `not_in_inventory`, `not_enough_items`, `trade_full` |
| `trade_add_meso` | `meso` | offers mesos; passes once the server shows them. Refusals: `no_trade`, `timeout` (amount refused) |
| `trade_confirm` | `timeout_ms` | presses Trade and waits (default 120 s, at most 300 s) for the partner and the result; passes on done (7). Refusals: `partner_cancelled`, `unsuccessful`, `unique_item_limit`, `different_maps`, `timeout` |
| `trade_cancel` | | closes the open trade |
| `party_create` | | creates a party through the client's own sender, as the party window's Create does |
| `merchant_open` | `item`, `quantity`, `price`, `title` | sets up a hired merchant selling `quantity` of `item` (Fredrick check, CREATE, item, Open), as a player does; needs a 503xxxx item and an FM room (P0-2 GP8) |
| `merchant_close` | | closes this character's hired merchant as its owner does (visit, then close); the rest goes to Fredrick |
| `fredrick_retrieve` | | Fredrick's Retrieve button (click Fredrick 9030000 first): takes what a closed merchant left; fails with the server's code |
| `use_cash_item` | `item`, `target` | the Incubator (5060002 only) on `target`: an item id (its first stack) or `inv:slot` (P0-6) |
| `party_invite` | `name` | invites a character by name the same way; `!bot <cid> acceptinv` answers it for a bot |
| `wheel`* | `anchor`, `notches` | mouse wheel over a target |
| `type`* | `text` | types into the focused box (no Enter) |
| `key_down`* / `key_up`* | `key` | hold a key across steps; every `key_down` needs its `key_up` |

\* proposed: the worker does not do it yet; a script using one lists `proposed_steps` in `needs`.

`${me.name}`, `${me.id}` (any client alias) and `${var}` (bound by an earlier wait) may appear in `text`, `line` and
regexes; in a regex the value is inserted escaped. A plain `{2}` stays a regex quantifier.

### Waits: signals only, every one with a timeout that fails the run

```yaml
- {id: channels, do: wait, signal: {type: client_signal, name: channels.ready},
   retry: {step: pick-world, every_ms: 4000, times: 4}, timeout_ms: 30000}
- {id: heal, do: wait, armed_at: hurt, timeout_ms: 30000,
   signal: {type: server_line, topic: COMBAT, regex: "^PARTY HEAL \\S+ -> ${me.name} ", char: me}}
```

- **Client signals** (`type: client_signal`) come from the QA module's pipe, relayed by the worker:
  `worldselect.ready` (world list handled and drawn), `channels.ready` (SERVERSTATUS 0x03, the reply to clicking a
  world), `charselect.ready`, `field.ready` (`map_id` narrows it), each sent 2 frames after the packet is handled.
  Proposed (list `proposed_signals`): `chat.received` (`regex`), `window.open` / `window.closed` (`window`, a name
  from `anchors.yaml`).
- **Server lines** (`type: server_line`) come from the QA event feed (below): `topic`, `regex`, and `char` (a client
  alias, or `any`).
- `timeout_ms` is required (500 ms to 5 min). A wait that runs out **fails the run** (the b222 rule: nothing goes green
  untested).
- `armed_at: <step>`: the wait counts lines and signals from the moment that step was sent, so a signal that comes
  before the wait step is reached still counts (the orchestrator remembers the feed's sequence number at each step).
  Default: the step just before the wait.
- `bind: {var: group}` takes a named group `(?P<group>...)` of the matching line into `${var}` (a spawned bot's id).
- `retry` (client signals only): when the signal has not come within `every_ms`, send that earlier step again, up to
  `times` (the lost world click; only `select_world`, `select_channel`, `click`, `key` may be resent).

### Timers

`do: sleep` exists for the few moments with no signal at all (a player's own Heal cast has no server line yet). It
needs a `reason` of at least 15 characters saying what has no signal; vague reasons ("wait", "settle") are refused,
and a script may sleep 30 s at most in total. Add a signal (a QaLog line or a pipe signal) instead whenever you can.

### Captures

| kind | fields | |
|---|---|---|
| `burst` | `fps` 1-30, `ms` 100-5000, `at`, `delay_ms` | in-process from the client's Present hook, 800x600 BMP + `frames.tsv` |
| `clip` | as burst | a burst the orchestrator turns into a short mp4 on 187 (ffmpeg) |
| `chat_log` | `span` | the client's chat between two steps |
| `packet_trace` | `opcodes`, `direction`, `span` | the client's own packet log for those opcodes (names or `0x..`) |
| `samples` | `fields` (hp, mp, max_hp, max_mp, pos, map), `hz`, `span` | |

`at: {step: X}` starts when step X is sent; `at: {signal: W}` when wait W's signal arrives. A client signal arrives
within a frame or two; **a server line arrives ~100-300 ms late** (the feed long-poll and the ssh hop). So a short
effect (the Heal column lasts about a second) bursts **on the step before the action** (or a client signal), and the
server line is kept as an `expect` check, not as the trigger. The key `at` is used instead of `on` because YAML reads
a bare `on` as `true`.

### Expect

```yaml
expect:
  reviewer:                 # what the reviewing agent must see, and in which captures
    - {see: "the vertical Heal column over the Bishop", in: [column-early]}
  checks:                   # machine checks over the recorded data
    - {check: server_line, topic: COMBAT, regex: "PARTY HEAL", char: me, min: 1}
    - {check: chat_line, regex: "already online", min: 1}
    - {check: packet, opcode: SHOW_FOREIGN_EFFECT, min: 1, span: {from: hurt, to: cleanup}}
    - {check: sample, field: hp, op: rises, span: {from: hurt, to: cleanup}}
    - {check: client_alive}
  judgment: "What the owner still judges by eye, if anything."
```

### needs

`gm`, `two_clients`, `owned_alt` (the account has an offline second character), `new_character`, `kpq_free_channel`,
`cash_shop`, `female_character`, `pet`, `mechanics` (`-Dslums.mechanics=true` on TEST), and `proposed_anchors` /
`proposed_signals` / `proposed_steps` while a script uses something the client side has not built yet.

## Validator rules (`tools/qa-scripts/validate`)

Schema first, then: the id is the file name; no credential-looking line anywhere (`password:`, `pic:` ...); each
client its own vault alias; a channel pick only after `channels.ready`; every wait has a timeout; every sleep has a
real reason and 30 s at most in total; anchors and windows exist in `anchors.yaml`; proposed anchors, signals and
steps are declared in `needs`; ids are unique and every `armed_at`, `retry.step`, `at`, `span` and `in` points at
something that exists; placeholders resolve and binds name a regex group; held keys are released; `!` lines are `gm`
steps. Tests: `python3 -m unittest discover -s tools/qa-scripts -p 'test_*.py'` (also pins the feed topics to
`QaLog.Topic`, the calibrated anchors to the client-mcp driver and the QA caps bit to the login gate).

## The QA event feed (server, TEST only)

`GET /qa/events` on the bot inspect listener of TEST (`127.0.0.1:8198`, header `X-Slums-Inspect: <secret>`, the
secret in `/etc/slums-inspect-test/secret` on 164, read by path only). Class: `soloMapling.inspect.QaEventFeed`.

- **Off unless `-Dslums.qa.feed=true`** is on TEST's command line (no "on where QA runs" default). It **refuses to
  start** when the server runs from the live folder, on login port 8485 (also the default when none is set) or
  against database port 3309. Live never has it.
- Query: `since=<seq>` (lines after it), `chars=<id,id>` (at most 16), `topics=<T,T>` (QaLog topic names or
  `QA_RUN`; default `MAP_CHANGE,CHAT,COMBAT,MECHANICS,QA_RUN`), `match=<regex>` (Java regex, 300 chars max),
  `wait=<0-20 s>` (default 20), `limit=<1-1000>` (default 500). Unknown parameters: 400.
- Answer: `{"ok":true,"last":<newest seq>,"next":<since for the next poll>,"gap":<lines after since fell off the
  ring>,"lines":[{"seq","utc","topic","kind","scenario"?,"map","chars":[ids],"line"}]}`, oldest first. With nothing
  yet it waits until a matching line arrives or `wait` runs out (then `lines` is empty).
- 403 without the secret or from a non-loopback address, 405 for anything but GET, 404 for another path, 429 when 4
  long-polls are already running.
- **What it feeds**: QaLog lines only. A poll **arms** its topics for 2 minutes (they are on as if a `!qa` scenario
  listened) and **watches** its chars for 2 minutes. A line named for characters (`QaLog.owner`, `QaLog.chars`) is
  kept; a map line is kept only when a watched character stands on that map (any channel: map lines carry no
  channel) and is tagged with them; a line with neither (`QaLog.any`) is server-wide and reaches every char filter.
  Running `!qa` scenarios' own lines (STAGE, STEP, notes, verdicts) come as topic `QA_RUN` with the scenario id.
  20,000 lines are kept.
- **Markers**: `MAP_CHANGE` `MAP <name> -> <mapId> ch <n>` (a real player entered a map); `CHAT` `CHAT <name>: <text>`
  (map chat by a player or a bot) and `WHISPER <from> -> <to>: <text>`; `MECHANICS` every `RUN ...` line of a KPQ
  run, whoever started it, about its real players; `COMBAT` `PARTY HEAL <bot> -> <target> ...` and
  `FOLLOWER FIGHT <bot> ...` (existing lines).
  `STAGING` `STAGE FOLLOWER cid=<bot> job=<JOB> lv=<n> party=<id> gm=<name> id=<gmId> map=<mapId>` (a `!qa`
  scenario staged a follower for the caller; the line's chars are the GM's id, so `char: me` matches).
- The orchestrator arms every topic a script uses with a `wait=0` poll before step 1, then long-polls.
- `!hurt <name> [percent]` (GM 3) drops a player to that share of max HP (the b140 script uses 40).

To switch it on, TEST's `start-test.sh` gets `-Dslums.qa.feed=true -Dslums.client.allowQa=true` (Server Dev, with a
backup of the file, at the next TEST restart). Not done in ticket 170: TEST was read-only for this work.

## Writing a script

1. Copy the closest script in `qa-scripts/`; give it a new id (the file name).
2. Use signals for every wait. Missing one? Add a QaLog line on the server (TEST only, free when off) or ask the
   Client lane for a pipe signal; do not sleep.
3. Burst before short effects; keep server lines as checks.
4. `tools/qa-scripts/validate qa-scripts/<id>.yaml` must print `ok`. Commit it as `status: draft`; it becomes `ready`
   after its first PASS on TEST.

## How a script becomes a release check

A run folder is not a verdict by itself (b222: nothing goes green untested). Three steps turn it into one:

1. **Judge the folder.** `tools/qa-runner/verdict <run folder>` writes one record per t-id the script `covers`
   (`tools/qa-runner/qaverdict.py` holds the rules, so the reviewer, the orchestrator's next run and the server read
   the same thing):
   - `result: FAIL` with a step that `timeout`s or is refused → **FAIL**, naming the step and its detail.
   - `ERROR`, `ABORTED`, still `RUNNING`, or a `script.yaml` whose sha256 is not the one in the manifest → **SKIP**;
     nothing is judged.
   - a machine `checks[]` entry that FAILed → **FAIL**.
   - `review.verdict: FAIL` → **FAIL**; `INCONCLUSIVE`, or a reviewer item not `seen` → **SKIP**.
   - **PASS** needs either a machine check that passed (`server_line`, `packet`, `sample`, ...) or a recorded
     `review` whose every reviewer item is `seen`. Frames without a review prove nothing.
   Record shape: `{id, status, reason, fresh, commit, deployed_commit, script{id,sha256,status}, run{folder,run_id,
   result,started_utc,finished_utc,trigger}, review, evidence[]}` - the run folder first, then the frames, client logs,
   server slice and `script.yaml` that exist.
2. **Publish the records to TEST.** `--out` defaults to `<auto-QA data dir>/client-verdicts/`, i.e.
   `/opt/mapleroot-dev/persistence-test/Server/data/autoqa/client-verdicts/<t-id>.json`. TEST's auto-QA reads that
   directory on **every report write** (`ClientScriptVerdicts.apply`), so the next `!autoqa` run - nightly, after a
   deploy, or `tools/ops/autoqa-run smoke` - copies each verdict under its t-id into `latest.json`:
   - fresh (same `server.commit` as the run) PASS → that check reads **PASS**, `mode`/`source` `client-script`, the
     run folder and its evidence as the row's evidence;
   - fresh FAIL → **FAIL**;
   - another build → **PASS(old, <run>, <commit9>)**, counted as `OLD`, never as a fresh pass;
   - a check the headless run measured itself keeps its own result: a script never overwrites a real PASS or FAIL.
   `tools/ops/test-status` shows these rows like any other check.
3. **Retire the allow-list entry.** A MANUAL check always SKIPs headless, which is why it sits in
   `src/main/resources/soloMapling/autoqa-release-skips.tsv`. Once its script is `ready` (it passed on TEST), the id
   comes off that list **in the same commit**, and a release run (`!autoqa all --release`) then counts the script's
   fresh verdict: PASS covers it, FAIL fails the release, an old verdict is an unexpected SKIP. `AutoQaReleaseScriptsTest`
   fails if an id is both allow-listed and covered by a `ready` script, and refuses a `ready` script that covers no
   t-id - so the list can only shrink one id at a time, with proof.

`t-la-1` has no script (the launcher is not the game client) and stays a hand check; its allow-list line says so.

### Staging scenes server-side

Some scenes a client cannot make on its own yet: the driver has no chat anchors (`chat.received` is still proposed)
and no party-invite window, so a script that spawns a bot, waits for its chat line and accepts its invite stops at
the first wait. Stage those with a `!qa` scenario and let the script do only what a client really does:

- The scenario runs the steps a GM would (`!warp`, `!bot trainhere <job> <level> 1`, `!bot <cid> followbot`, the party
  join, `!hurt`) through `QaWorld` (`stageFollowerBot`, `hurtTo`, `stageDojoFollowers`, ...). Every method returns what
  it really did, so the undo carries the exact bot id.
- It says what it staged in `STAGING` lines that carry the caller's char id in the line's `chars`
  (`QaLog.chars(STAGING, map, new int[]{gm.getId()}, "STAGE FOLLOWER cid=... job=BISHOP ...")`), so the feed keeps them
  for `char: me`. Put everything the runner cannot fill - a character name above all - in the line and in the staging,
  not in the script.
- The script sends `!qa run <id>`, waits on those `STAGING` lines (and `MAP_CHANGE` for the warp) with a name-free
  regex plus `char: me`, then does the client parts: the capture bursts, checks on the server's own lines.
- Today's QA build records screenshot `burst` and `chat_log` captures only (no `samples`, no `packet_trace`): prove
  HP, position or packets from the server's lines instead of from a capture.
- `!qa stop` runs the scenario's undos: the bot leaves the party and the world, and a party the staging made is
  disbanded. Never leave a staged bot for the next player.

`b140-bot-heal-column-staged` is the worked example (`!qa run hc-1`, scenario `HealColumnScenarios`).
`b140-bot-heal-column` keeps the by-hand version of the same scene for the day the driver can do the chat and invite
steps itself.

## Scripts so far

| script | covers | state |
|---|---|---|
| `b140-bot-heal-column` | b140, q48, t-su-1 | draft: needs `chat.received`, `window.*`, `party_invite.accept` |
| `b140-bot-heal-column-staged` | b140, q48, t-su-1 | draft: staged server-side by `!qa run hc-1` (warp, follower, party, hurt), so built client signals, server lines and screenshot/chat captures only |
| `b233-player-heal-column` | b233 | draft: two clients, the healer's Heal on key A |
| `kpq-stage1-leader` | q45, t-mx-1 | draft: `click_npc` for Cloto, dialog anchors |
| `botme-alt-basics` | q23-q25 | draft: `chat.received` |
| `t-cl-1-rope-no-box` | t-cl-1 | draft: built pieces only |
| `t-cl-2-cash-shop-wheel` | t-cl-2 | draft: `wheel`, Cash Shop anchors |
| `t-cl-3-pet-equip` | t-cl-3 | draft: inventory anchors; item ids to confirm |
| `t-cc-1-create-character` | t-cc-1 | draft: `character.create` |
| `t-mv-1-attack-stops` | t-mv-1 | draft: `key_down`/`key_up`; the per-attack line text to confirm |
| `t-jq-1-no-bounce` | t-jq-1 | draft: built pieces only |
| `t-ga-4-show-rolls-vanish` | t-ga-4 | draft: `chat.received`; one justified sleep (the 12 s sweep has no signal) |
| `t-mr-2-woman-proposes` | t-mr-2 | draft: two clients, `type`, inventory anchors |
| `t-rg-1-friendship-ring` | t-rg-1 | draft: two clients, Cash Shop anchors, `type` |
| `t-rg-2-couple-ring` | t-rg-2 | draft: as t-rg-1, same-gender partner |

No script for `t-la-1` (the Tauri launcher's checklist): the launcher is a separate desktop app, not the game client;
the runner drives the QA client only. It stays a hand check with its own page until the launcher gets its own harness.
