# Client QA signals

## Purpose

The game client has no test harness of its own: everything the client can prove must leave the process. Two channels do that, and both are QA-build only.

| Channel | Direction | Shape | Who reads it |
|---|---|---|---|
| QA pipe `\\.\pipe\slums-qa-<pid>-events` / `-commands` | client ⇄ QA worker on the Windows box | JSON lines, one event per line; plain-text commands | `tools/ops/client-mcp-win/src/qapipe.py`, then `tools/qa-runner/qarunner.py` |
| `slums-ui-state.json` next to the exe | client → driver | JSON rewritten twice a second | `tools/ops/client-mcp-win/src/driver.py`, `src/worker.py` |

A third, server-side feed (`GET /qa/events`) is the *other* end of the same script: it is what a `server_line` wait and an `expect.checks: server_line` read, and it is recorded in the same run folder (`docs/qa/client-scripts/README.md:213-240`). This document covers the wire contract of all three from both ends, because a QA script only works when the client signal and the server line agree.

The QA client is deliberately a *different binary*: the release string gets `-qa` and the build stamp carries caps bit 31, and the server refuses such a client unless `-Dslums.client.allowQa=true` (`docs/qa/client-scripts/README.md:63-64`, `src/main/java/net/server/handlers/login/ClientVersionGate.java:37-41`).

## Entry points


> **Source tree of the client-side half.** Every client file below was read from the QA branch worktree
> `/root/work/client/src-170-qasignals` (branch `client-170-qa-signals` = `client-170-qa-module`, tip `9bc1458`,
> 2026-10-31). The 1.6.4 client line this review covers (`/root/work/client/src-164-data`, `VERSION` 1.6.4-rc3)
> carries none of it: `grep -rn 'QaModule_\|SLUMS_QA_MODULE' src/` returns nothing there, so `src/system.cpp`,
> `src/packetdispatcher.cpp` and `src/d3d9host.cpp` have no call sites and no `src/qamodule*` files exist
> (`ls src/qamodule*` is empty). The QA client in use on the Windows box is therefore built from the QA branch,
> not from the line this review documents — see Known issues.

Client side:

| Call site | File | What it does |
|---|---|---|
| `QaModule_Start()` | `/root/work/client/src-170-qasignals/src/system.cpp:324` | starts the three threads, after the hook sites are checked |
| `QaModule_OnPacket(opcode, buf, len)` | `/root/work/client/src-170-qasignals/src/packetdispatcher.cpp:101` | per server packet: dialogs, `chat.received`, arms ready signals |
| `QaModule_OnFrame(dev)` | `/root/work/client/src-170-qasignals/src/d3d9host.cpp:188` | per drawn frame (EndScene): settled signals, window polling, burst frames |
| `QaModule_OnDeviceReset()` | `/root/work/client/src-170-qasignals/src/d3d9host.cpp:195` | frees the staging surface |

All four are empty inline no-ops when `SLUMS_QA_MODULE` is off, so the call sites carry no `#ifdef` (`/root/work/client/src-170-qasignals/src/qamodule.h:17-20`).

Tool side (server repo `/root/work/SlumsMS-164-archreview`):

| Entry point | File:line | Role |
|---|---|---|
| `tools/ops/client-mcp <tool> <json>` | `tools/ops/client-mcp:32-33` | ssh wrapper, name whitelist, queue protocol |
| MCP tools `qa_wait`, `qa_burst`, `qa_events`, `run_script` | `tools/ops/client-mcp-win/src/driver.py:33-47` | worker-side implementation |
| `tools/ops/qa-run <script>` | `tools/ops/qa-run:7-9,26` | copies the script to the box, calls `run_script`, pulls the run folder |
| `tools/qa-runner/run <script>` | `tools/qa-runner/run:1` | orchestrator: drives the worker, waits on signals and feed lines, judges |
| `GET /qa/events` | `src/main/java/soloMapling/inspect/QaEventFeed.java:61` | server-side lines for the same run |

## How it works

1. **Start.** `QaModule_Start` runs `CheckHookSites()` first (`/root/work/client/src-170-qasignals/src/qamodule.cpp:484`). The two sites it stands on are fixed VAs — `0x004965F1` (`CClientSocket::ProcessPacket` prologue) and `0x00A1238B` (`CWvsContext::GetCurFieldID`) — and their stock bytes are in `/root/work/client/src-170-qasignals/src/qamodule_patch.h:24-39`. A mismatch does not crash the client: the part switches off and an `error` event names the site (`/root/work/client/src-170-qasignals/src/qamodule.cpp:76-89`).
2. **Connect.** Three detached threads start (`/root/work/client/src-170-qasignals/src/qamodule.cpp:485-487`). The events thread owns `CreateNamedPipeW` for `...-events` (`:282`) and greets a connecting runner with `hello` carrying `pid`, `release`, `build` (`:290-294`).
3. **Arm.** `QaModule_OnPacket` maps the opcode to a signal name (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:27-30`, `SignalForOpcode` `:35-43`) and pushes `(name, kSettleFrames)` onto `g_armed` (`/root/work/client/src-170-qasignals/src/qamodule.cpp:504-510`). `kSettleFrames = 2` (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:32`): a signal means "the client handled that packet **and drew two more frames**", which is why a `wait` is never a sleep.
4. **Fire.** `QaModule_OnFrame` decrements the counter per frame and emits the event when it reaches zero (`/root/work/client/src-170-qasignals/src/qamodule.cpp:517-531`). `field.ready` adds `map_id` from `CurMapId()` (`:522-524`, `:93-97`). Only one instance of a name is armed at a time; re-arming resets the count (`:508`).
5. **Chat.** `chat.received` is decoded from the server packets themselves (`ParseChatPacket`, `/root/work/client/src-170-qasignals/src/qamodule_policy.h:173-237`; emit at `/root/work/client/src-170-qasignals/src/qamodule.cpp:499-503`) rather than hooked from the chat log, because the log append has no pinnable address (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:105-114`). Text is cut to 200 bytes on a UTF-8 boundary (`:122`, `:131-135`).
6. **Windows.** `PollWindows` walks `CWndMan::ms_lpWindow` each frame and reports windows by `CUIWnd` class plus a create serial, so reopening a window is a new event (`/root/work/client/src-170-qasignals/src/qamodule.cpp:179-260`, emit `:252`, close `:268`). Names come from the UOL (`WindowNameFromUol`, `/root/work/client/src-170-qasignals/src/qamodule_policy.h:271-310`, deepest folder wins) or, for the one-class dialogs (party invite, proposal, NPC talk, notice), from the packet that asked for the dialog via a 3 s queue (`DialogForPacket` `:312-321`, `QueueDialog` `/root/work/client/src-170-qasignals/src/qamodule.cpp:139-149`). Unknown windows still report, with `vtable`/`uol` extras (`:244-250`).
7. **Burst.** A `burst <fps> <ms> <label>` command from the commands pipe (`/root/work/client/src-170-qasignals/src/qamodule.cpp:369-400`, `ParseCommand` `/root/work/client/src-170-qasignals/src/qamodule_policy.h:68-86`) is queued; the render thread emits `burst.start` (`:541-542`), grabs the back buffer with `GetRenderTargetData` at the requested cadence, and the writer thread saves `qa-captures\<label>\NNNN.bmp` plus `frames.tsv` (`:333-367`), then `burst.done` with the frame count (`:364`).
8. **Relay.** The driver holds a `QaPipe` and reads events as they arrive (`tools/ops/client-mcp-win/src/qapipe.py:57-82`); `qa_events` returns them by *position* (`:121-128`, `driver.py:552-563`). The orchestrator calls `qa_events {since}` per alias and advances its own cursor (`tools/qa-runner/qarunner.py:382-389`), then matches a wait against the freshly fetched batch (`:476-494`, match rules `:404-412`).
9. **Server lines.** A wait of `type: server_line` long-polls the feed with the run's character ids and topics (`tools/qa-runner/qarunner.py:105-131`, `docs/qa/client-scripts/README.md:213-228`); hits land in `server/feed.jsonl` and back the `server_line` checks (`qarunner.py:905-926`).
10. **Verdict.** The folder is judged by `tools/qa-runner/verdict` (`qaverdict.py:168-187`), and a fresh verdict is published to `Server/data/autoqa/client-verdicts/<t-id>.json`, which auto-QA imports on its next report write instead of the runner's SKIP (`src/main/java/soloMapling/autoqa/ClientScriptVerdicts.java:146-163`).

### Signal table

| Signal | Writer (client) | Reader (tool / server) | Used by which QA check |
|---|---|---|---|
| `hello {pid,release,build}` | `/root/work/client/src-170-qasignals/src/qamodule.cpp:290-294` | `qapipe.py:34` (connect waits 5 s), `qarunner.py:509-532` (manifest client entry; run fails without it) | every client-script run: build/release must be in the manifest (`qarunner.py:529-531`) |
| `worldselect.ready` (op 0x1B) | `/root/work/client/src-170-qasignals/src/qamodule_policy.h:27,35-43` → `/root/work/client/src-170-qasignals/src/qamodule.cpp:517-525` | `qapipe.py:17`, `driver.py:33-35` `qa_wait`, `qarunner.py:344` | first wait of all 29 `qa-scripts/*.yaml`; t-cc-1 (`qa-scripts/t-cc-1-create-character.yaml:17`) |
| `channels.ready` (op 0x03) | same path | same + `qascript.py:315` (must precede `select_channel`), `retry` target `pick-world` | t-cc-1 lost-world-click retry (`qa-scripts/t-cc-1-create-character.yaml:19`); b140, kpq-stage1-leader, botme-alt-basics |
| `charselect.ready` (op 0x0B) | same path | `qarunner.py:344` | t-cc-1:21, t-mr-2, t-rg-1, t-rg-2 |
| `field.ready` + `map_id` (op 0x7D) | `/root/work/client/src-170-qasignals/src/qamodule.cpp:522-524` | `qarunner.py:346` filter `map_id`, `qascript.py:326-327` (map_id only here), `learn_character` `:846-847` | 28 of 29 scripts; e.g. `qa-scripts/b140-bot-heal-column.yaml:27` (104040000), `qa-scripts/b233-player-heal-column.yaml:40,42` (100000000), `qa-scripts/kpq-stage1-leader.yaml:27,29` (103000000 then 103000800) |
| `chat.received {kind,text}` | `/root/work/client/src-170-qasignals/src/qamodule_policy.h:173-237` → `/root/work/client/src-170-qasignals/src/qamodule.cpp:499-503` | `qarunner.py:344-347` (regex + kind), reachable in the driver only via `qa_events` (`driver.py:552`) | 5 scripts: b140-bot-heal-column, botme-alt-basics, kpq-stage1-leader, t-ga-4-show-rolls-vanish, t-mr-2-woman-proposes; plus `expect.checks: chat_line` (t-cc-1:33) |
| `window.open {window[,vtable,uol]}` | `/root/work/client/src-170-qasignals/src/qamodule.cpp:252` | `qarunner.py:346`, window name validated against `docs/qa/client-scripts/anchors.yaml:56-74` (`qascript.py:317-319`) | 8 scripts: b140, kpq-stage1-leader, t-cc-1 (character_create), t-cl-2, t-cl-3, t-mr-2, t-rg-1, t-rg-2 |
| `window.closed {window}` | `/root/work/client/src-170-qasignals/src/qamodule.cpp:268` | same | b140-bot-heal-column, kpq-stage1-leader |
| `burst.start {label,fps,ms}` / `burst.done {label,frames}` | `/root/work/client/src-170-qasignals/src/qamodule.cpp:541-542`, `:364` | `driver.py:541-550` (`qa_burst` waits for `burst.done`, 10 s grace) | every `captures: burst` entry (e.g. `qa-scripts/t-cc-1-create-character.yaml:26-27`), and the frames the reviewer and `clip` use |
| `error {msg}` | `/root/work/client/src-170-qasignals/src/qamodule.cpp:66` (hook site, window list, back buffer, bad command) | `qapipe.py` log, `qarunner.py:191-192`/`:500-503` payload | triage of a run that produced no windows or no signals: the message names the site |
| `pong` | `/root/work/client/src-170-qasignals/src/qamodule.cpp:391` (commands thread) | `qapipe.send` caller only; no driver tool sends `ping` | liveness probe only — not part of any script |
| `_closed` (pipe EOF) | `qapipe.py:82` | `qarunner.py:488-490`, raising "the QA pipe closed while waiting for \<signal\>: the client exited or disconnected" | fails the run at the step instead of burning its timeout; the only evidence a crash left |
| `slums-ui-state.json` (file, not pipe) | `src/uilayout.cpp:379-437` (500 ms) | `driver.py:312-320` (trusted < 2 s, owned pid), `worker.py:258` (stage `login` ⇒ self-heal) | driver answers stage/chat_open without a screen template; worker recovery |
| server `MAP_CHANGE`, `CHAT`, `COMBAT`, `MECHANICS`, `STAGING`, `QA_RUN` | `QaEventFeed` sink over `QaLog` (`QaEventFeed.java:202-218`, defaults `:74`) | `qarunner.py:105-131`, `:905-926` | `expect.checks: server_line` and every `wait {type: server_line}`; e.g. t-cc-1 disallowed `disabled` text (`qa-scripts/t-cc-1-create-character.yaml:33`) |
| `packet.login {step,op,size,hex,summary}` (b249, branch `client-162-qa` only) | `/root/work/client/src-170-qasignals/src/qamodule.cpp:69-107` (`client-162-qa`), policy `:239-330` | no consumer in the runner yet | b249: the black channel panel; not wired into any script |

## Data

**Event line** — `{"ev":"<name>","t":<ms since client start>,"<extra>":"..."}\n` (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:101-103`). Extras: `field.ready`→`map_id` (int), `chat.received`→`kind` (int, the packet's own type/mode byte) + `text`, `window.*`→`window` (+`vtable`,`uol` when unnamed), `burst.*`→`label`,`fps`,`ms`/`frames`, `hello`→`pid`,`release`,`build`, `error`→`msg`. Strings are JSON-escaped (`:89-98`).

**Commands** (runner→client, one per line, ASCII): `ping`; `burst <fps> <ms> <label>`. Limits fp 1-30, ms 100-5000, label 1-40 of `A-Za-z0-9-_`; anything else is answered with an `error` event echoing the first 80 chars (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:45-86`, `/root/work/client/src-170-qasignals/src/qamodule.cpp:396`).

**Burst artefacts**: `qa-captures\<label>\NNNN.bmp` (54-byte bottom-up 32-bit header, `/root/work/client/src-170-qasignals/src/qamodule_policy.h:333+`) and `frames.tsv` rows `index\tatMs` (`/root/work/client/src-170-qasignals/src/qamodule.cpp:359`). The driver renames the folder under the run as `burst-<label>` and converts to PNG on 187 (`tools/ops/client-mcp-win/src/driver.py:912-970`, `docs/qa/client-scripts/run-folder.md:26-28`).

**UI state file** — one line: `{"pid","t_ms","stage":"field"|"login"|"other","screen":[w,h],"chat_open":bool,"status_bar"/"chat_input"/"quick_slot"/"buttons"/"icon_buttons":[l,t,w,h]|null}` written temp-then-rename (`src/uilayout.cpp:422-436`).

**Run folder** (per script run): `manifest.json` (`format: 1`, `run_id`, `script{id,sha256,covers}`, `server{host,commit}`, `clients{alias{build,release,caps,dll_sha256,char_id,char_name}}`, `runner`, `trigger`, `result PASS|FAIL|ERROR|ABORTED`, `steps[]` with `signal{type,name,matched_utc,detail}`, `captures[]`, `checks[]`, `review`, `findings[]`), `server/feed.jsonl` (`qarunner.py:931-933`), `frames/<capture>/`, `client/<alias>/pipe.log`, `verdicts/<t-id>.json`, `review.md` (`docs/qa/client-scripts/run-folder.md:8-68`).

**Server feed answer**: `{"ok","last","next","gap","lines":[{"seq","utc","topic","kind","scenario"?,"map","chars":[],"line"}]}` — 20 000-line ring, `wait` ≤20 s, `limit` ≤1000, `chars` ≤16, `match` ≤300 chars (`QaEventFeed.java:64-72`, `:398-434`, `:473-477`).

## Switches

| Switch | Where read | Default | Effect |
|---|---|---|---|
| `SLUMS_QA_MODULE` (CMake) | `CMakeLists.txt:19` | OFF | compiles `qamodule.cpp`, defines `SLUMS_QA_MODULE`, appends `-qa` to the release and sets caps bit 31 (`/root/work/client/src-170-qasignals/src/CMakeLists.txt:32-38`, `cmake/BuildStamp.cmake:38-42`, `src/buildstamp_proto.h:27`) |
| `MAPLEROOT_INTEGRATION` | `/root/work/client/src-170-qasignals/src/CMakeLists.txt:32-34` | ON via `scripts/build.ps1:11` | required; the QA module needs the D3D9 host, otherwise `FATAL_ERROR` |
| `[debug] uistate` | `src/uilayout.cpp:368` (`GetPrivateProfileIntA`) | 0 | writes `slums-ui-state.json`; documented in the source comment `:358-360` but **absent from `/root/work/client/src-164-data/config.example.ini`** |
| `[clienthax] settingswindow` | `/root/work/client/src-170-qasignals/src/d3d9host.cpp:302` (`ReadIniBool`) | true | gates Ctrl+F10 (HUD window) and Ctrl+F11 (freeze/Edit UI) at `:270-280`; with it off the `hud_settings` and `edit_ui` anchors (`docs/qa/client-scripts/anchors.yaml:68-69`) and the q26 Edit UI check cannot be driven |
| `[clienthax] uitour` | `/root/work/client/src-170-qasignals/src/d3d9host.cpp:303` | true | first-run tour cards can cover the field a burst is capturing; `uitour_policy` has its own ctest (`tests/CMakeLists.txt:88`) |
| `[clienthax] nativehud` | `src/hud_native.cpp:390` | 1 | native HUD modules instead of the legacy overlay drawing — changes what a burst frame looks like, not the signals |
| `-Dslums.client.allowQa` | `ClientVersionGate.java:39`, `:81-82`, `:100-108` | false | lets a caps-bit-31 client past the gate on TEST/RC; the message is `QA_BUILD_MESSAGE` `:40-41` |
| `-Dslums.qa.feed` | `QaEventFeed.java:60`, `:115` | false | serves `/qa/events`; never on live (`:123-136`, live guards `:75-77`) |
| `slums.inspect.secretFile` | `BotInspectApi.java:62` | — | secret file for `X-Slums-Inspect` (path only: `/etc/slums-inspect-test/secret`, README `:213-216`) |
| `CLIENT_MCP_TARGET` | `tools/ops/client-mcp:36-43` | `vm` | `vm` = TEST VM 192.168.0.175, `silvia` = owner PC 192.168.0.186 |
| `CLIENT_MCP_WAIT` / `CLIENT_MCP_QUEUE_WAIT` | `tools/ops/client-mcp:35` | 120 / 3600 | per-call wait; queue wait resets on the `queued` event |
| `worker-config.json` | `tools/ops/client-mcp-win/worker-config.json` (`CLIENT-MCP.md:42-44`) | 12 h / 10 min / 120 min / true / 3 / 15 s / 20 s | worker lifetime, owner-idle guard, auto-recover budget, heartbeat |
| `proposed_signals` in `needs` | `tools/qa-scripts/qascript.py:352-355` | — | a script may wait on a name the client does not send yet only if it says so; validator warns, run then fails fast at that step (`qarunner.py:393-401`) |

Not a switch, but a build-input: `QAMODULE_TEST_EXE` (`tests/CMakeLists.txt:80-81`, default `Z:/MSv83/SlumsMS-dev/client-1.5.0-fixes2/exe-copy/MapleStory.exe`) selects the exe the byte check reads.

## Invariants

- **A signal means drawn, not received.** `kSettleFrames = 2` (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:32`); a wait that fires on packet receipt would race the window it is about to be screenshotted with.
- **Only four names are "built".** `BUILT_SIGNALS` in the validator is exactly `worldselect.ready`, `channels.ready`, `charselect.ready`, `field.ready` (`tools/qa-scripts/qascript.py:31`); everything else is a *proposed* signal and must be declared in `needs.proposed_signals`.
- **Position, not time.** Consumers advance by cursor (`qapipe.py:121-128`, `driver.py:552-563`, `qarunner.py:382-389`); `events_since` exists but the orchestrated path does not use it.
- **A missing signal fails at once**, not after the timeout: `missing_signal` runs before waiting (`qarunner.py:393-401`) and the text is "client signal <name> not available in this client build[ with <filter>]".
- **Timeouts are bounded.** Wait `timeout_ms` 500 ms … 5 min, `retry` only on client signals and only onto an earlier `select_world|select_channel|click|key` step, `every_ms × times < timeout_ms` (`qascript.py:25-32`, `:299-309`); `sleep` needs a ≥15-char reason and ≤30 s total (`qascript.py:25-26`).
- **Windows are named by UOL first, dialog queue second, never guessed.** An unnamed window reports as `unknown` with its vtable and UOL (`/root/work/client/src-170-qasignals/src/qamodule.cpp:242-252`).
- **No chat text is invented**: truncation happens on a UTF-8 boundary (`/root/work/client/src-170-qasignals/src/qamodule_policy.h:131-135`) and a short payload yields no line (`/root/work/client/src-170-qasignals/tests/qamodule_policy_test.cpp:134-138`).
- **A QA client cannot play live.** Caps bit 31 + `-qa` release + the gate (`ClientVersionGate.java:37-41`, `:81-82`), and the feed refuses the live folder/port/database (`QaEventFeed.java:75-77`, `:123-136`).
- **The client never writes a verdict.** The runner records; an agent or `tools/qa-runner/verdict` judges (`docs/qa/client-scripts/run-folder.md:3`, `tools/ops/qa-run:9`), and a client-script verdict can only replace a SKIP (`ClientScriptVerdicts.java:146-163`).

## Origin

| Part | Verdict | Evidence |
|---|---|---|
| `/root/work/client/src-170-qasignals/src/qamodule.cpp`, `qamodule.h`, `qamodule_policy.h`, `qamodule_patch.h` | **custom** | `git log --all --follow`: born in `502f9f5` 2026-10-30 "QA client runner channel: ready signals and back-buffer bursts over named pipes (ticket 166, WIP)", then `ffb0b61` (channels.ready after SERVERSTATUS), `24c056c` (chat lines, window events, map id), `c9296e0` (window poll fix), `9bc1458` (name a window by any folder in its UOL). Nothing in `docs/KAENTAKE-UPSTREAM.md` or `docs/PROVENANCE.md` mentions QA. |
| QA build switch | **custom** | `2f17894` "QA client build switch: SLUMS_QA_MODULE stamps <release>-qa and caps bit 31" — same series |
| b249 login capture | **custom** (branch only) | `115b2fb` on `client-162-qa`; `/root/work/client/src-170-qasignals/src/qamodule_policy.h:239-330` there |
| field.ready `char_id`/`char_name` | **custom** (branch only) | `3c40dc3` on `client-162-qachar`; not on `client-170-qa-signals` |
| `[debug] uistate` writer | **custom** | `4dcce60` 2026-10-25 "WIP: QA UI state file ([debug] uistate=1), quieter grips" (`src/uilayout.cpp`) |
| Tool side (`client-mcp`, `qa-runner`, `qa-scripts`) | **custom** | no upstream; ticket 61 (driver), 166/170 (runner), 179 (the b286 defect) |
| Server feed / gate | **custom** | `QaEventFeed`, `ClientVersionGate.CAP_QA_BUILD` — this repo's own code |

The client base is the MapleRoot/Kaentake stack; QA is a layer bolted on top, and the branch containing it is **older than the release branch**: `git diff --stat client-164-rc3 client-170-qa-signals` = 39 files, +1379/−2819, and the QA branch lacks `src/mapobj_guard.h`, the mobattackfix work and the in-link guard that rc3 ships.

## Tests and QA

Client (`ctest`, client repo):

| Test | Registration | Covers |
|---|---|---|
| `qamodule_runner_channel` | `tests/CMakeLists.txt:76-77` → `/root/work/client/src-170-qasignals/tests/qamodule_policy_test.cpp:16` | opcode→signal map incl. "0x0A is not ready yet" (`:19-23`), command refusals (`:25-35`), `EventLine`/`JsonEscape` (`:37-39`), BMP header (`:41-45`), 12 chat packet shapes incl. ticker flag, whisper `0x12`, spouse mode 5, notice type 1, yellow tip (`:47-138`), UTF-8 truncation (`:140-143`) |
| `qamodule_sites` | `tests/CMakeLists.txt:79-86` → `/root/work/client/src-170-qasignals/tests/qamodule_patch_test.cpp:44` | stock bytes of both sites; every single flipped byte must fail the guard (`:59-70`); with an exe argument, the real file is compared (`:72-84`) |

Server / tools:

| Test | File:line | Covers |
|---|---|---|
| `QaEventFeedTest` | `src/test/java/soloMapling/inspect/QaEventFeedTest.java:69-239` | secret, 405/404/400, off by default and never on live, char/topic/regex filters, ring gap, long poll, 429, arming |
| `QaBuildGateTest` | `src/test/java/net/server/handlers/login/QaBuildGateTest.java:43-66` | bit 31, switch name, TEST/RC accept with the switch, ordinary client unaffected |
| `ClientScriptVerdicts` / `AutoQaReleaseScriptsTest` | `src/main/java/soloMapling/autoqa/ClientScriptVerdicts.java:146-163`, `src/test/java/soloMapling/autoqa/AutoQaReleaseScriptsTest.java:58-71` | a verdict replaces only a SKIP; no allow-listed id may have a ready script |
| `test_qapipe.py` | `tools/ops/client-mcp-win/tests/test_qapipe.py:45-101` | connect waits for `hello`, player build returns None, partial JSON, drain, `events_from`, closed pipe, burst validation |
| `test_qatools.py` | `.../test_qatools.py:34-270` | `qa_wait`/`qa_burst`/`qa_events`, endpoint guard |
| `test_qarunner.py` / `test_qaworker.py` / `test_qaverdict.py` | `tools/qa-runner/` | 21 / 32 / 21 tests: fake transport, lost world click resent (`:150`), missing signal fails at its step (`:156`), topics armed before the first step (`:146`) |
| `test_qascript.py` | `tools/qa-scripts/test_qascript.py` | 20 tests of the schema rules above |

Command lines: `python3 -m unittest discover -s tools/qa-runner` (73 tests on 2026-11-01) and `-s tools/qa-scripts` (20) per `docs/qa/audits/2026-11-01-promises.md:113-114`; `python3 -m unittest discover -s tools/qa-scripts -p 'test_*.py'` per `docs/qa/client-scripts/README.md:210`.

**What a real client must confirm by hand** (nothing below is covered by any test): that `hello` arrives on a genuinely started QA build; that each ready signal fires within a frame or two of the screen appearing; that `field.ready` carries the right `map_id` on warp, channel change and login; that every window in `anchors.yaml:56-74` opens a correctly named event; that a burst yields the frames count `burst.done` claims; and the whole `run_script` → run folder → `verdict` path (`docs/qa/client-scripts/README.md:55-56`: "The whole path is offline-tested; the first real run is the lane session's to make").

**Not covered**: `qamodule.cpp` itself has no test — the threads, `PollWindows`, `QueueDialog`, the writer and the burst cadence are exercised only by a live client run. There is no test of the pipe protocol across the two languages. `scripts/build.ps1` never passes `-DSLUMS_QA_MODULE`, so CI cannot produce the binary the tests would need.

## Known issues

| Ref | Issue |
|---|---|
| b286 (`/root/work/STATUS-1.6.4.md:82`) | The worker loses batched ready events: a batch feeding two waits lets wait 1 pass and wait 2 time out although the event is retained. Cause per `docs/qa/audits/2026-11-01-promises.md:36-44`: `events` advances the whole batch cursor (`qarunner.py:382-389`) while `wait_signal` reads only newly fetched events (`:476-494`); 73 runner tests pass and miss it. A fix must also stop a stale signal from satisfying a newly armed wait. |
| b276 (`/root/work/STATUS-1.6.4.md:78`) | QA feed: a bot's map-scoped PLAN/ROUTE lines never reach the GM's feed — a `server_line` check over those topics would silently see nothing. |
| b284 (`/root/work/STATUS-1.6.4.md:80`) | Auto-QA at 10 lanes gives false failures; client-script runs interleave with them, so a FAIL that is also b286 must be rerun serially (`/root/work/STATUS-1.6.4.md:82`). |
| b251 (`/root/work/STATUS-1.6.4.md:72`) | Crash entering Rose Garden (211080100). No pipe signal can see a crash: the only evidence is the unexpected `_closed` (`qarunner.py:488-490`) plus `client_alive`. |
| b274 (`/root/work/STATUS-1.6.4.md:76`) | Will's/Magnus's boss portals never send a portal-enter. There is no client signal for "a portal was entered" — `field.ready` is the only witness, so the check cannot distinguish "no packet" from "not there yet". |
| b270 (`/root/work/STATUS-1.6.4.md:75`) | Launcher news crashed on a manifest without `news[]`; the launcher is outside the client, and `t-la-1` is the one check no script covers (`docs/qa/client-scripts/README.md:336`). |
| L15 (`/root/work/STATUS-1.6.4.md:106`), e14 (`/root/work/STATUS-1.6.4.md:124`) | Client-side fixes and the bonds window, and the in-game Slums Exchange window — both future client work; neither has a signal yet, so any script for them is `needs.proposed_signals`. |
| b256 (`/root/work/STATUS-1.6.4.md:73`) | b166 follow-up: real-client frames for the rest of the alt extras — exactly what a burst is for, and still open. |

NEW-FINDING: `tools/qa-runner/qarunner.py:348-350` — the comment and `NO_PAYLOAD` still say `field.ready` carries no map id, while the client branch sends one (`/root/work/client/src-170-qasignals/src/qamodule.cpp:522-524`) and scripts wait on `map_id` (`qa-scripts/b140-bot-heal-column.yaml:27`). Stale doc-drift in the only place a maintainer would look for the truth.
NEW-FINDING: `tools/ops/client-mcp-win/src/driver.py:33-35` — `qa_wait`'s tool schema enumerates only the four ready signals, while `qapipe` forwards any name and the runner waits on seven. `chat.received` / `window.*` are therefore reachable through `qa_events` only, so a hand-run `qa_wait` on them is rejected by the schema even though the client sends them.
NEW-FINDING: `scripts/build.ps1:11` — the shipped build script never passes `-DSLUMS_QA_MODULE`, so the QA client every `qa-scripts/*.yaml` needs cannot be produced by the documented build; it is a manual CMake configure today.
NEW-FINDING: the QA client is built from `client-170-qa-signals` (2026-10-31), which is not a descendant of `client-164-rc3` and lacks its guards (`git diff --stat` = 39 files, +1379/−2819). A script PASS therefore proves an older client, which is why `qaverdict.py:157-187` records `fresh` and why `qarunner.py:529-531` refuses a manifest without build/release/caps.
NEW-FINDING: `/root/work/client/src-164-data/config.example.ini` has no `[debug]` section although `src/uilayout.cpp:368` reads `debug/uistate` (and `src/attackdiag.cpp:212` reads `debug/attackdiag`). The driver's self-heal depends on a key with no example entry and no doc.
NEW-FINDING: `/root/work/client/src-170-qasignals/src/qamodule.cpp:226-228` — if the `CUIWnd` class test fails, `window.open`/`window.closed` are off and the only notice is an `error` event after 600 frames (10 s). A runner that is not draining events sees a silent "no window" and burns a 10 s wait before failing.
NEW-FINDING: `/root/work/client/src-170-qasignals/src/qamodule_patch.h:24-39` — both sites are raw VAs tied to one `MapleStory.exe` sha256; only `qamodule_sites` (which needs `QAMODULE_TEST_EXE`) catches a drifted exe before a live run.

## State

**partial.**

| Piece | State | Evidence |
|---|---|---|
| QA pipe (ready signals, chat, windows, bursts) | works on the branch, unverified against a real runner | code + 2 ctest suites on `client-170-qa-signals`; `docs/qa/client-scripts/README.md:55-56` says the first real run is still to be made |
| QA build stamp + server gate | working | `ClientVersionGate.java:37-41`, `QaBuildGateTest`, `buildstamp_wire_format` |
| `[debug] uistate` | working, undocumented | `src/uilayout.cpp:379-437`; consumed at `driver.py:312-320`, `worker.py:258` |
| Tool side contract | working (offline) | 73 + 20 + 51 + 22 unittest tests |
| 29 client scripts | **draft only** | every `qa-scripts/*.yaml` has `status: draft`; `docs/qa/client-scripts/README.md:320-331` |
| Verdict publishing into auto-QA | working path, no fresh run yet | `ClientScriptVerdicts.java:146-163`, `qaverdict.py:157-187` |
| b249 login capture | branch only (`client-162-qa`), no consumer | `/root/work/client/src-170-qasignals/src/qamodule.cpp:69-107` on that branch |

## Open questions

1. Will the QA module be rebased onto `client-164-rc3` (or `1.7.0`) before the first real script run, or is a run on the older tree accepted as evidence? The branch divergence makes every "fresh PASS" ambiguous.
2. After b286's fix, does the corrected `wait_signal` still need `NO_PAYLOAD`/the `armed_at` re-arm semantics, or can `qa_wait` simply read from the retained log?
3. Who owns the `char_id`/`char_name` extension (`client-162-qachar` `3c40dc3`)? `qarunner.py:887-897` already consumes them "only when the build sends them", but the QA branch that ships the signals does not include that commit.
4. Should `qa_wait`'s driver schema be widened to the runner's seven signals (the `qa_events` route works but is undocumented as the substitute)?
5. Does the QA client hold the login pipe long enough that the server's `MIN_CLIENT_BUILD` check can ever see a QA stamp without `-Dslums.client.allowQa` — i.e. is the QA refusal path ever exercised on TEST by accident, and would a run notice?
6. Multiple runners on one client: `CreateNamedPipeW` serves one instance at a time (`/root/work/client/src-170-qasignals/src/qamodule.cpp:282`), so a second `run_script` while the first holds the pipe is undefined. No test or doc states the behaviour.
