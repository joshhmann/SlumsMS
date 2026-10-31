# QA guide: auto-QA on the test server, client visual QA on the owner's PC, the owner's checklist

Three layers, cheapest first. All of it runs against the TEST server (164, port 8489), so no go is needed, but tell
the owner before anything that restarts the test server or starts the game on their PC.

## 1. Auto-QA (server side, "did it happen")

Pretend players (SyntheticClient) stage scenes with `!qa` and assert results; health sweeps run every 5 minutes.

```
tools/ops/autoqa-run smoke          # quick set; also: all | <id> | <family> | id,id,...
tools/ops/autoqa-run --release all  # the launch check: FAILs on any FAIL or any SKIP off the allow-list
tools/ops/test-status               # last run summary, health sweep, deployed commit, errors (--checks: every row)
ssh root@192.168.0.164 cat /opt/mapleroot-dev/persistence-test/Server/data/autoqa/latest.json   # full results
ssh root@192.168.0.164 'ls -t /opt/mapleroot-dev/persistence-test/Server/logs/qa/ | head'      # per-check logs
```
- Scheduled: nightly 10:00 UTC (3 AM PDT) in the server, and 22:00 UTC (3 PM PDT) by cron on 164.
- A request is picked up within 30 s; a run in progress is not interrupted. `all` takes about 20-30 minutes.
- Checks live in the server code under the auto-QA package (search `AutoQa` / `QaScenario`); each has an id like
  `rv-2`, `gw-4`. Add a check for every bug you fix when it can be staged.
- **A check never goes green without testing (1.6.2, ticket 164 b222).** A wait that runs out FAILs its check ("timed
  out waiting for /x/ after N s"). A `--failed` rerun's carried passes read `PASS(old, <run>, <commit>)` and count as
  old, never as fresh evidence. Every run lists what it skipped and why (`latest.json` run line `skipped`, its
  `summary`, test-status, `!autoqa report`), and every result cites the deployed commit, its QA log(s) and the time.
- **Release run** (`tools/ops/autoqa-run --release all` or `!autoqa run all release`): every check fresh at the normal
  clock; it FAILs on any FAIL, any check not run, or any SKIP not on
  `src/main/resources/soloMapling/autoqa-release-skips.tsv` (the 11 client-only checks: t-ga-4, t-rg-1, t-rg-2,
  t-mv-1, t-jq-1, t-cl-1, t-cl-2, t-cl-3, t-cc-1, t-mr-2, t-la-1). The summary says `release: PASS` or
  `release: FAIL (unexpected SKIP: ids)`. A switch that is off on TEST SKIPs its check, so a release PASS also means
  TEST runs every shipped switch. Details: `docs/ops/qa-suite.md`, "The report".
- Triage every failure before fixing: staging problem (the `!qa` setup), real bug, working as designed but hard to
  see, or needs the owner. Read that check's log and the journal around it first
  (`.claude/skills/qa-triage/SKILL.md`). Known flakes are listed in `docs/HANDOFF.md` section 3.
- In game (GM on the test server): `!qa list`, `!qa run <id>`, `!qa stop`, `!botwhy`, `!botground`, `!botshowcase`,
  `!setmaxhp <hp> [<mp>]` (1.6.0: MaxHP/MaxMP past 32,767 for the 4-byte client, see `docs/release/1.6.0-hpmp-4byte.md`).

## 2. Client visual QA ("does it look right"), the client MCP on Silvia

A Codex-built driver (`Z:\MSv83\SlumsMS-dev\client-mcp\`, ticket 61) runs a COPY of the RC client against the test
server as `AqClient` (account `autoqaclient`, GM 3, test only; the password is only in its `secrets\` file, never
read or print it). It can start the client, log in, type commands, warp, screenshot and read client logs.
To frame a spot on the current map (NPC screenshots), type `!tp <x> <y>` (GM 3, 1.6.1): a same-map warp to the
point, snapped onto the foothold below it when there is one; `!pos` shows where you stand.

**Limits that make it fail if ignored:** it only works in the owner's logged-in desktop, as admin, with the
controller window in front, the PC idle for 10 minutes, and the real monitor as main display (Parsec as main display
= startup error -2147467259). It never runs from ssh alone.

**Starting it (the owner does this, then leaves the PC alone for 10 minutes):**
1. Empty the old queue: `type nul > Z:\MSv83\SlumsMS-dev\client-mcp\logs\worker-inbox.jsonl` (the worker replays every
   line in it at start; keep the file, the worker fails if it's missing).
2. Admin PowerShell: `Start-Process pythonw.exe -ArgumentList '-B','Z:\MSv83\SlumsMS-dev\client-mcp\scripts\focus-controller.py'`
   and leave the "SlumsMS client-driver controller" window in front.
3. Admin PowerShell: `python -B Z:\MSv83\SlumsMS-dev\client-mcp\scripts\elevated-worker.py` (lasts one hour).

**Driving it from 187** (`tools/ops/client-mcp`, which writes to the worker's queue over ssh):
```
tools/ops/client-mcp status                                   # idle time, display, PIDs, DLL hash
tools/ops/client-mcp start_client
tools/ops/client-mcp login '{"channel":1}'
tools/ops/client-mcp go_to '{"map_id":100000000}'
tools/ops/client-mcp command '{"line":"!botground"}'
tools/ops/client-mcp screenshot '{"label":"henesys-ground"}'
tools/ops/client-mcp read_logs '{"lines":100}'
tools/ops/client-mcp stop_client                              # always end with this
```
Screenshots land in `Z:\MSv83\SlumsMS-dev\client-mcp\shots\`; copy them back with
`scp -i ~/.ssh/id_rsprox_desktop 'crims@192.168.0.186:Z:/MSv83/SlumsMS-dev/client-mcp/shots/<file>.png' .` and look at
them. A Codex agent started on Silvia in `client-mcp\` can call the MCP server directly instead (see its README).

**A typical pass:** pair a server scene with screenshots. Start the scene from 187 (`tools/ops/client-mcp command
'{"line":"!qa run we-1"}'` or `tools/ops/autoqa-run we-1`), warp the client there, screenshot at the moments that
matter, read the client logs, stop. Write the results in `Z:\MSv83\SlumsMS-dev\client-mcp\qa-<version>.md` (step,
result, screenshot path), as `qa-1.5.0.md` does.

**When it refuses:** "owner is using the PC" = wait; "found 0 owned game windows" = startup failed, read the logs and
report; virtual display = tell the owner. Don't edit `local-config.json` (`force`), don't kill other processes, don't
work around a refusal.

### Client scripts (ticket 166/170): replacing live driving

Real-client checks are moving from an agent steering the client to scripts a runner plays and records:
`docs/qa/client-scripts/README.md` (format, validator, the TEST QA event feed), `run-folder.md` (what a run leaves
behind) and `owner-vs-machine.md` (which hand checks a script takes over). Scripts live in `qa-scripts/`.

## 3. The owner's checklist (what only a person can judge)

For each release write `docs/release/<version>-qa.md`: one line per check, `id | what to do | what you should see`,
grouped by area, reusing ids (`t-xx-n`). Mark which checks auto-QA or the client MCP already cover, so the owner only
does the rest. The owner writes results back in chat or in that file (`PASS` / `FAIL: note` after each line); turn
fails into fixes with the triage steps above and say what to retest by id.

The claude.ai notes/QA pages from 1.5.0 can only be opened by Claude; repo files are the source of truth from now on.

## Ticket 69: ordinary v83 features (`vb-*`)

Auto-QA checks `t-vb-1`..`t-vb-8` (scenario ids `vb-1`..`vb-8`) exercise the everyday paths a real player uses,
one check per feature. Each check runs `!qa run vb-N` and then its steps; every outcome is asserted, and any NPC
dialog that refuses a step the check expects to succeed fails it (`Expects.noRefusal`, diagnostic token
`NPC_REFUSAL`).

| id | what it does | the line that proves it worked |
|---|---|---|
| `t-vb-1` | hair, hair colour, face, lenses and skin at the Henesys salons, each with its coupon | `VB STYLE <step> <id> -> <id> changed` and `VB COUPON <id> 1 -> 0` |
| `t-vb-2` | creates a scratch character and deletes it through the login handler with PIC | `VB DELETE state=<cid> state 0 gone=true` (wrong PIC: `state 20 stillThere=true`) |
| `t-vb-3` | starts a Kerning PQ instance, clears it, takes the reward, exits | `VB KPQ cleared=true` then `VB KPQ reward given=true ... map=103000805` |
| `t-vb-4` | 3rd-job mount chain: the 6002 room and hand-in, Tatamo's 20m bottle (fee and refusal), the 6003 perfume, and catching the hog | `VB MOUNT bottle canComplete=true ... fee=20000000`, `VB MOUNT perfume ...` |
| `t-vb-5` | buys and sells at Henesys NPC 1012004, then makes the Cash Shop EXP coupon take effect | `VB SHOP buy ... owned 0 -> 1`, `VB CASH card=5211048 given=true rate 1 -> 2` |
| `t-vb-6` | Dances with Balrog promotes 100 -> 110, then a real skill point is spent | `VB JOB advance 100 -> 110`, `VB JOB sp spend ... level 0 -> 1` |
| `t-vb-7` | storage round trip, Duey to the other pretend player, and a trade between the two | `VB STORAGE tookOut bagDelta=0`, `VB DUEY claimed recipientDelta=1`, `VB TRADE offered=true itemDelta=1 mesoDelta=5000` |
| `t-vb-8` | (1.6.2, ticket 112) Moren's 6029 chain: start, the three 10,000-meso lessons through their end scripts, the hand-in teaching Maker 1; then one monster crystal from 100 leftovers through the Maker window's packet (`MakerSkillHandler`). Needs Maker in the server's Skill.wz | `VB MAKER chain canComplete=true state=2 lessons=3 maker=1 guide=1 otherMakers=0`, `VB MAKER crystal handler=true leftover=<id> 100 -> 0 crystal=<id> 0 -> 1` |

`t-vb-3` and the KPQ mechanics checks (`t-mx-1`..`t-mx-6`) share each channel's one Kerning PQ with the bot lobby
(`-Dslums.mechanics.lobby`). Since 1.6.2 a lobby run yields at once to a party led by a real player, the pretend
player included, and to a GM's or check's staged run (`[Mechanics] lobby yields ch N to <name>'s party` in BotLog);
`t-mx-6` proves it: a lobby run holds the channel, the pretend player's party talks to Lakelis and gets the instance
on that click (`MX6 PASS ...`). A player-led or staged run never yields.

`t-vb-4` also drives the catch: it spawns the tamable hog if the hourly spawn is not up, drops it to 30% HP and sends
the real `USE_CATCH_ITEM` packet with the perfume, so `VB MOUNT hog ... handler=true ... perfumeUsed=1` is the catch. `t-vb-5` stages the coupon by toggling it in the server's active set and always puts the
toggle back.

## Ticket 163: 1.6.2 bot switches (`bq`, `tm`, `gb`, `su`, `jb`, `nn`)

The 1.6.2 bot audit (`docs/tickets/163-bot-audit-1-6-2.md` section 4) found these switches with no auto-QA proof. Each
check is one probe (`Probes162`) that spawns fresh throwaway bots next to the pretend player, drives the switch's real
entry point on them, judges, and despawns them (and undoes any map pin or property it set) itself. A switch that is
off SKIPs and names its `-D` flag. The probe's first line is the verdict; the evidence lines name the step that went
wrong. Run them with `tools/ops/autoqa-run bq,tm,gb,su,jb,nn`.

| id | switch | what it does | PASS means |
|---|---|---|---|
| `t-bq-1` | `slums.botquests.enabled` | a level-20 training bot gets the best stageable mob quest (at most 40 kills) on a Victoria grind map from the real ranking, takes it by the start NPC with the maps pinned as watched, its own hits kill the quest mobs, it turns the quest in by the end NPC | `take: ... visit=STARTED real status STARTED`, `log hunted=true`, `turn-in: ... visit=COMPLETED real status COMPLETED` |
| `t-tm-1` | `slums.temple.enabled` | a fresh level-126 training bot is asked by `TempleOfTime.consider`, takes and turns in 3500 by the Temple Keeper with the hub pinned as watched, and is asked again | `offer 1: ... active 3500`, `templeDone 1`, `offer 2: ... active 3501 hunt map 270010100, lane registered 270010100`, `270020100 open=false` |
| `t-gb-1` | `slums.gear.optimizer=pareto` | a fresh bot made a level-50 Fighter on the player's (active) map wears a real sword, gloves and shoes; its bag holds a real sword needing 8 more STR (WATK 40) and real gloves and shoes with +5 STR each; `BotGear.review` runs | `after review: moved=true worn sword <the bag sword>, gloves <bag gloves>, shoes <bag shoes>` |
| `t-gb-2` | `slums.gear.dexless` | a fresh level-50 bot (not a magician) keeps its job and weapon; its secondary stat is moved down to the class floor, it gets 25 AP and is settled as a level-up (`BotProgression.afterLevelUp`) | the gear cap is under the class rule; `after the settle:` the secondary stands at the cap and the primary got the rest |
| `t-gb-3` | `slums.grind.gearFirst` | a fresh level-22 training bot gets a FARM_ITEM goal for the equip that drops on the fewest in-band maps the finder offers from its spot; `TrainingMapChooser.choose` runs (up to 3 times) | a `farming its wanted drop` decision to a map listed under `drops on`, inside the band |
| `t-gb-4` | `slums.junk.sort` | a fresh bot's equip bag is emptied and cut to 24 slots, then holds 14 scrolled and 2 plain off-class pieces (over half full; the sale wants to free 4, more than the junk); `BotBagRoom.sellJunk` runs (the town stop's call; the few mesos are credited through the yield caps as usual) | `kept pieces sold 0, junk left 0` and the sort kept every scrolled piece |
| `t-gb-5` | `slums.scroll.ev` | a fresh level-50 bot, gear and scrolls taken away, wears a one-handed sword with 7 slots; A: it holds only a plain 100% ATT scroll; B (while long shots are allowed today): only a dark ATT scroll. Read-only through `ScrollEvQa` (the EV planner and the appetite rule side by side); nothing is rolled | `A ... EV careful 0` (while the appetite rule would use it), `B ... EV careful 0, EV gambler <the dark scroll>`; SKIP when part B cannot be judged today |
| `t-gb-6` | `slums.bots.apvariants` + `fromSlot=auto` | reads only: the slot the build makes of the property, whether the slot before it and the slot itself may roll (`ApVariantQa`), the variants the first 200 new slots of five classes roll, and the online roster bots past it with their build names | `slot <n-1> rolls=false, slot <n> rolls=true` and some non-standard rolls. FAILs on a build that reads `auto` as unset (fixed by 162-auditfix `b526a8132`) |
| `t-su-1` | `slums.bots.partysupport` | a fresh level-40 bot made a Cleric with Heal 10 and full MP forms a party with the pretend player, who is dropped to 40% HP; one `BotPartySupport.tick` | `tick acted=true; player HP <40%> -> <more>`. Server side only: the cast pose still needs a real client (audit B24) |
| `t-su-2` | `slums.bots.famerequest` | a fresh level-20 bot next to the pretend player is asked "fame me pls" through `BotFameRequest.tryHandle` (the chat path's call): first as a stranger, then staged a FRIEND, then again; the fame point, the bot's `famelog` row and the tie are taken back | `fame F -> stranger ask (handled=false) F -> friend ask (handled=true) F+1 -> again (handled=true) F+1` |
| `t-su-3` | `slums.bots.magicguard` | a fresh level-30 bot made a Magician with Magic Guard 20 takes a tenth of its HP through `BotVitals.hurt`, first with no guard cast, then after `BotBuffDriver.markCastForTest` | `no guard: -D HP, -0 MP; guard up:` the HP/MP split `BotVitals.magicGuardSplit` gives for the skill's x |
| `t-jb-1` | `solomapling.jobErrand` | a fresh level-9 beginner (its tick running, the pretend player on its map) gains the EXP for level 10 the observed way; the errand's timeout is set to 240 s for the check and put back | `errand held=true`, then `ended=true arrived +1` (on the instructor's map) or `fell back +1`, and a 1st job |
| `t-jb-2` | `solomapling.botSkillBooks` | a fresh level-135 4th-job bot: the first mastery book it wants and can read now is found; it must be kept from sale, then 3 copies are given and `BotSkillBooks.useAll` runs; another job's book is offered to `sellRefusal` | `before reading: kept for its skill plan`, `useAll: read >= 1`, the spare book `may be sold` (or only a SaleRules reason) |
| `t-nn-1` (HALF) | `bot.nav.swimWallJump` | the server's own navigation and swim physics replay `SwimWallJumpBurstTest`'s scene (a detached swimmer pressed against a 600 px rock) on made-up map 990340299 with the live switches; nothing on a real map | `highest y on the rock's west face` under -600 and `furthest x` past 100; the look on a real client still needs eyes |
| `t-nn-2` (HALF) | `bot.nav.blockedPosWatchdog` | replays `BlockedPosWatchdogTest`'s scene: the department store's (100000102) geometry loaded as made-up map 990340298 (its graph is built and cached once under that id; the real map's graph and learning are untouched), a bot parked at a launch gate, the nav cleared after every AI tick | `launch gate refused 40, gave up >= 3`; today's real GAVE_UP spots are listed for context |

## Ticket 164 b224: checks tickets 156 and 157 promised (`gr`, `nv-2`, `nv-3`)

Probes in `Probes164` on fresh throwaway bots next to the pretend player; details in `docs/ops/qa-suite.md`, "Checks two
tickets promised". None may SKIP in a release run. Run them with `tools/ops/autoqa-run gr,nv-2,nv-3`.

| id | ticket | what it does | PASS means |
|---|---|---|---|
| `t-gr-1` | 156 | a fresh lv-35 bot's state-of-mind snapshot vs the truth; the real checker on two true and three untrue claims; the real delivery path on an untrue scripted line. No chat model | the snapshot holds its level, map and the player; untrue claims rejected and counted; the scripted line swapped for a safe one |
| `t-gr-2` | 156 | asked its level through the real chat path, a fresh bot answers with the model; then a forced untrue model draft goes through the reject and the retry. SKIPs without a live model (a release FAIL) | the reply states its real level and is grounded; the draft was rejected, retried and never said |
| `t-nv-2` | 157 item 3 | one-way-in traps of the bots' world graph from Lith Harbor vs the committed baseline (`nav-trap-maps.txt`, 485 of 1,471 maps on 10-30) | no trap the baseline does not know |
| `t-nv-3` | 157 tools | the inspect API without the secret, its view of a fresh bot, kill calibration's report, a watched bot's nav trace ring | 403 without the secret, the view is the bot, calibration on and report-only, a ring of the bot's own ticks |
