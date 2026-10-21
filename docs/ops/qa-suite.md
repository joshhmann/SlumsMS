# `!qa`: test scenarios with a debug log (test server only)

`!qa` stages a release-notes test for the GM who runs it. It warps you, hands out the items, spawns the bots and
switches on that test's debug log. `!qa stop`, or the timeout, undoes all of that and switches the log off. The ids
are the test ids on the release notes page (`t-fm-1` on the page is `!qa run fm-1`).

## Where it exists

Only on a server started with `-Dslums.qa=true` or `-Dslums.attackcapture=true`. The test server's `start-test.sh`
passes `-Dslums.attackcapture=true`, so it has `!qa`. Live's unit (`slumsms.service`) passes neither, so the command
is never registered there and every log hook stays off. `QaCommandGatingTest` and `QaRegistryTest` check this rule,
using live's and the test server's real command lines. The command needs GM level 3.

## Commands

| Command | What it does |
|---|---|
| `!qa list` | Lists every scenario with its id, aliases, one-line description and arguments |
| `!qa run <id> [args]` | Stages the scenario for you and starts its log |
| `!qa do <step>` | Runs one of the scenario's steps (np-3: `!qa do close`) |
| `!qa where` | Shows the running scenario, how long it has run, its log path, and other GMs' runs |
| `!qa stop` | Ends the run, undoes the staging and closes the log |

You can run one scenario at a time. Scenarios that claim a map (bk, gd, fm, gh) refuse to start while another GM's
scenario holds that map. A run stops on its own after 15 minutes. ld-1 stops after 10, wd-2 after 5, and bk-1 after
the showcase's own minutes plus one.

## Scenarios (1.4.1)

| Id | Stages | Logs |
|---|---|---|
| `bk-1` (`bk-2`) `[4th\|pirates\|<job>…] [min]` | `!botshowcase` (default `4th 10`) on your map | Every attack a bot sends: skill, level, route, action/pose, facing, speed, prepare action, weapon id and type, projectile, mobs, lines, damage. Attacks the showcase could not send, with the reason |
| `fm-1` (`fm-2`) `[room 1-12]` | Warps you to the FM room and gives you a shop permit. Opens a player shop for you with scrolls worth more than 5.5m at normal value, plus one item priced at twice its value (for fm-2). Spawns 4 FM bots that stay in that room | Every buy and every skip, with the reason (price roll, over 1.2x, above the player-shop ceiling, daily cap reached, seller unknown). Each cap reservation or refusal with the running total, and the cap status at start and stop |
| `np-3` `[room]` | Warps you to an FM room and gives you a hired merchant permit | Merchant force-close, Fredrick retrievals. `!qa do close` closes your merchant the same way a server restart does (`forceClose`, as in `Channel.closeAllMerchants`) |
| `np-1` | Warps you to Magatia and gives you a Blue Snail Shell to store | Storage opened, stored, taken out |
| `np-2` | Warps you to the FM entrance | Fredrick retrievals |
| `gh-1` (`gh-2`) | Warps you to 104040000 | Hide toggles, bots skipping you while hidden (which code path, at most once per 10 s), every bot chat line on the map, and every mob controller change (marked `[hidden]` / `[bot]`) |
| `ld-1` `[map]` | Warps you to Ellinia (or Perion/Kerning) | Bots still on a rope after 2 s, and each rope stall watchdog firing (climbed off or jumped off) in all three towns |
| `wd-1` | Nothing | Per-channel players, bots and bar value, once a minute. Every channel list the login server sends, with its bars |
| `wd-2` | Puts you on the `north00` portal of 271010400 | Each map you land on, with your position and the nearest portal |
| `gn-2` (`sc-1`) `[n]` | Gives you a one-handed sword and n (default 3) 2043000 scrolls | Every scroll result, with ATT/MATT, slots left and level |
| `sc-2` `[careful\|normal\|gambler] [equip id]` | Spawns a bot next to you holding a one-handed sword (or the equip id), 4x 60% and 2x 10% sword ATT scrolls, a dark 70% and a white scroll. Every ~6 s it scrolls its own gear with the server's real roll, by the appetite given (default its own); `!qa do roll` rolls once now. Runs 5 minutes | Every roll: bot, scroll, odds, white scroll, SUCCESS/FAIL/CURSE, the equip's stats before and after; DONE when it has nothing left it would scroll. Its FM listing or toss of the finished sword, if any |
| `gd-1` (`gd-2`) | Warps you to the Guild HQ, tops your mesos up to the fee, and spawns 3 idle bots by Heracle | Guild creation requests (eligible count, min partners, party, mesos), created guilds, and bot chat |
| `cs-2` | Gives you pet 5000000 (7 days) and pet equip 1802085 | Every equip you put on, with its slot |

What `!qa stop` undoes:

- Spawned bots leave.
- Items the scenario gave you are taken back, but only what is still in your inventory. Equipped or sold items stay.
- fm-1 closes your shop first, so unsold items come back before they are taken.
- gd-1 takes back the mesos it gave you, up to what you still have.
- A summoned test pet stays with you.

You are not warped back. Mesos you earned in fm-1 stay with you.

Planned for 1.4.2 (`QaScenarios.PLANNED` lists them, with TODO notes in that file): bot buddies, multi-channel follow,
and 5-channel population. Their branches are not merged into rc1 yet.

## Logs

Each run writes `logs/qa/<id>-<UTC yyyyMMddTHHmmssZ>.log` under the server's working directory, for example
`logs/qa/fm-1-20261021T183005Z.log`. The file holds:

- A `#` header: the scenario, the GM's name, id and GM level, the map, position and channel, the arguments, the
  topics, the watched maps and the timeout.
- `STAGE` lines: what was staged and what the GM was told.
- Event lines: a time, the topic, then the message.
- `STEP` lines for `!qa do`.
- At the end, `STOP`, one `CLEANUP` line per undo, and a `SUMMARY` with the event count per topic.

Claude reads them on the test server:

```sh
ssh root@192.168.0.164 'ls -t /opt/mapleroot-dev/persistence-test/Server/logs/qa/'
ssh root@192.168.0.164 'cat /opt/mapleroot-dev/persistence-test/Server/logs/qa/fm-1-20261021T183005Z.log'
```

## How it works

- `soloMapling.qa.QaLog` is the hook API. Game code checks `QaLog.on(Topic.X)` first: one volatile read and a bit
  test, false unless a running scenario listens to that topic. Only after that does the hook build its message and
  call `QaLog.map(topic, mapId, msg)` (runs watching that map), `QaLog.owner(topic, charId, msg)` (only that GM's
  run) or `QaLog.any(topic, msg)`. `QaLog.every(key, ms)` rate-limits hooks that would fire every tick. Global log
  levels are never touched.
- `QaRun`: one run's file, the topics and maps it listens to, its claims, and its undo stack, which runs last-in
  first-out. One failing undo does not stop the rest.
- `QaSuite`: the registry (ids must match `[a-z]{2}-\d+` and be unique), one run per GM, claims, timeout and ticks.
- `QaWorld` / `GameQaWorld`: everything a scenario may do to the game. Tests use `FakeQaWorld`.
- `QaScenarios`: the scenarios.
- `client.command.commands.gm3.QaCommand`: the command.

## Adding a scenario

1. Pick the test id from the release notes page. A second test that needs the same staging becomes an alias.
2. In `QaScenarios`, write a `static final class` that implements `QaScenario`:
   - Give it `id`, `title` and `topics`.
   - Add `watchedMaps` when its events are about one map, and `claims` when two GMs must not share it.
   - Override `minutes`, `tickSeconds`/`tick`, or `action` for `!qa do` steps as needed.
   - In `stage`, use `ctx.world()` for every game change. Register an undo right after each one: `ctx.giveBack`
     gives an item and registers its take-back, `ctx.despawnAtStop` does the same for bots, and `ctx.onStop` covers
     anything else. Throw `QaRefusal` to refuse. The undos registered so far still run.
   - Add it to `QaScenarios.all()`.
3. If the game code has no hook yet, add a `Topic` and put a guarded `QaLog` call where the event happens, with a
   `// !qa <id>` comment.
4. Add a staging test to `QaScenarioStagingTest`, then add the `!qa run <id>` line to the test's `.how` text on the
   notes page (`tools/release/notes-page/README.md`).
