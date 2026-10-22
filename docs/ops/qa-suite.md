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
scenario holds that map. A run stops on its own after 15 minutes. ld-1, mv-1, pe-1 and ga-2 stop after 10, ga-1 after 20, wd-2 after 5, and bk-1 after
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
| `id-1` `[off] [bots 1-6] [map]` (1.4.2) | Warps you to a busy map (your own if it has mobs, else 104040000), spawns 4 idle bots in its densest mob pack. `off` switches idle safety off for the before/after comparison; stop puts it back | Every contact hit a bot takes while idle (chat, rest, reaction stop, standing), every move to a safe spot (from, to, ledge / rope top / portal, clearance), "no safe spot" when none is in reach, and a per-bot tally of hits and moves every 15 s and at stop |
| `mv-1` `[attacks 1-200] [window ms 100-3000]` | Nothing: arms a meter on your own character (default 20 attacks, 600 ms window) | Each of your attacks (close, ranged, magic, touch): where you stood, the movement fragments you sent, and how far you moved during the attack. One yellow line per attack and a summary. See [mv-1](#mv-1-attack-slide) |
| `pe-1` `[mapA] [mapB] [seconds]` (1.4.2) | Warps you to mapA, then back and forth between mapA and mapB every few seconds (default 100000000 100000100 8). Stops after 10 minutes | One `MAP_ENTRY` line per entry: server time from the warp packet to the end of the placement, packets and bytes in that part, what was sent at once and what was deferred (bots and drops outside the view), when the last deferred spawn went out, packets and bytes in the 3 s after the warp with the top opcodes, and the map's objects by type. `!qa stop` writes a `SUMMARY` with p50/p95 over the entries |
| `jq-1` `[map]` (1.4.2) | Warps you to a map blocked by `bounce-zones.yaml` (default 101000100, Forest of Patience step 1) and turns your `!bounce bypass` off. `!qa do push` re-sends the zone list to your client | Each map you land on with its block status (category, Flash Jump), and every bounce or Flash Jump the server receives: `ALLOWED`, `BYPASS`, or `REFUSED` (the client sent a blocked bounce: its own block failed) |
| `ga-1` `[town]` (1.5.0) | Warps you to the town's Gachapon machine (default Henesys, in the Henesys Market 100000100) and gives you 3 Gachapon Tickets. Logs the machine's odds and a 10,000-roll simulation. `!qa do roll [common\|uncommon\|rare\|jackpot]` is a free roll now (rare/jackpot shows the server notice); `!qa do sim [n]` simulates again | Every gachapon roll by anyone, player or bot (who, town, tier, item, quantity, source: ticket / remote ticket / gm roll / qa roll / bot ticket) |
| `ga-3` (1.5.0, checklist only, real client) | Nothing: walk it by hand. (1) `!warp` to Ellinia 101000000, Perion 102000000, Kerning City 103000000, Sleepywood 105040300, Mushroom Shrine 800000000 and New Leaf City 600000000: a Gachapon machine stands on the ground (spots in `docs/release/1.5.0-gachapon-machines-client.md`) and opens that town's dialog. (2) Use a Remote Gachapon Ticket (5451000) and pick the **last** machine in the client's list (slot 11): the prize line in `logs/gachapon.log` must say **Ludibrium** (`[remote ticket]`); note the client's label for that slot. (3) `!gacharoll <town> <bot>` next to a show bot in the Henesys Market: its pop's items vanish within 12 s and cannot be picked up | Run `!qa run ga-1 <town>` alongside to get the rolls in the QA log |
| `ga-2` `[town] [x ceiling]` (1.5.0, 56B) | Warps you to the town's machine, spawns a bot next to you and gives it mesos up to x times its wealth ceiling (default henesys 2.5). Every ~8 s it takes a spending step; `!qa do step` steps now. Runs 10 minutes or until the bot is under its ceiling | Each step: the ticket it bought with its own mesos and the prize (and whether it was announced), the upkeep it paid, and its wealth line (held, ceiling, hard line, intake %). DONE when it is under its ceiling |
| `af-1` `[social\|training]` (1.5.0) | Spawns a bot next to you that already counts you as a friend (social by default; `training` holds a training bot on your map) | Every affinity event on your map (`EVENT party/buff/heal/rescue/trade/gift/fame/kind_chat ...` with the score and state), every action a bot takes because of it (`ACT greet/buddy/invite/help`), bot chat, and the bot's state every 30 s (`START`/`STATE`/`FINAL`) |
| `af-2` `[rival\|nemesis]` (1.5.0) | Warps you to 104040000 if your map has no mobs, spawns a training bot held on the map that counts you as its rival (or nemesis). `!qa do grudge` forces a fresh grudge and a KS spree now | As af-1, plus `ACT taunt/ks/defame`, each mob a KS spree takes (`KS ... takes <mob> from <you>`), and refused trades (`REFUSE`) |
| `af-3` (1.5.0) | Turns `@botdrama` off for you and spawns a nemesis training bot. `!qa do on` / `!qa do off` flip it; stop puts it back | As af-2. With drama off: the state reads neutral, and there are no `taunt`/`ks`/`defame` ACT lines and no `EVENT ks` |
| `cp-1` `[married\|friends] [same\|mixed]` (1.5.0) | Spawns two bots next to you that are a couple (dating, or married with rings and the loveseat) or best friends (friendship rings), any genders unless `same`/`mixed` is given; memory only. Runs the pair's step every minute; `!qa do step` runs it now | Each pair action: `PARTY`, `HOP` (channel), `SIT` (couple chair), `MATCHING` (hat), `LOVE NOTE`, plus bot chat on the map |
| `we-1` `[same\|mixed]` (1.5.0) | Needs channel 1. Warps you to Amoria (680000000), stages a couple there (any genders, or `same`/`mixed`) and starts the `amoria_wedding` event at once | The ceremony: `WEDDING ... begins`, every line said (`SAY`), `BLESS` (your EXP blessing at "i do", once), `RINGS`, `MARRIED`, `WEDDING ... ends`, and bot chat. Stop ends the event if it still runs and removes the couple |
| `gu-1` `[guild]` (1.5.0, bot guilds) | You must be guildless. Stages an officer (rank 2) of a bot guild (default the biggest) next to you that counts you as a friend; its guild name shows over its head. Ask it "<name> can i join ur guild?": it answers, and a real guild invite follows in a few seconds. Accept, then talk in guild chat. Stop takes the bot away and makes you leave the guild if you joined | `GUILDS` (every bot guild at start), `APPLY` (who asked, feelings, level, YES or the refusal), `INVITE SENT`, `PLAYER JOINED`, `GUILD CHAT` (your line and which bot answers, at most one), `GUILD SAY` (every bot guild chat line), bot chat |
| `gu-2` `[friends 1-4]` (1.5.0) | You must be in a guild. Stages 2 (or n) friend bots and 1 neutral bot next to you, guildless. Invite each with `/ginvite`. `!qa do join` adds one more friend that is a member at once | `INVITE <you> -> <bot>: friend, bots x/y, chance n% -> JOINED/declined` (friends join after 4 s here; past half bots the chance drops), `JOIN`, the thank-you `GUILD SAY` |
| `gu-3` `[guild]` (1.5.0) | You must be guildless. Warps you to Henesys and stages an officer of the guild that counts you as a friend, then runs a recruit round. `!qa do recruit` runs another (megaphones still go through the gate) | `RECRUIT MEGAPHONE` (sent or queued/gated), the line said out loud, `RECRUIT WHISPER` and `INVITE SENT` to you |
| `gu-4` `[guild]` (1.5.0) | Warps you to the FM entrance and stages a member of a guild with a rival (default the biggest) and one of its rival, both neutral to you; jabs at once. `!qa do jab` again | `JAB` (who, which guild, the line) and a jab back, `MEGA JAB` (sent or gated), `CLAIM` (a party leader goes to grind where the rival grinds; needs both guilds partied) |
| `gu-5` `[guild] [gpq\|zakum\|pap\|horntail]` (1.5.0) | A fast staged boss run on your channel: 60 s gathering at the entrance (you are warped there), 2 min inside, then back. Needs 2+ online roster members of the guild at the boss's level. Party a crew member while they gather to join for real (it always accepts and follows you). Runs 8 minutes | `BOSS RUN` (crew, channel, map), `PARTY INVITE` / `REAL` (a member went with you), `INSIDE` (who left the world), `BACK`, `RESULT` (cleared or wiped; loot named only, no item; GP on a clear) |
| `gu-6` (1.5.0) | You must be in a guild. Stages a friend bot that is a member of it. `!qa do rival` makes it your rival and runs the quit check; `!qa do ignore` backdates it 30 days and runs the check (player-led guilds) | `JOIN`, the step's lines, the quit line in guild chat (`GUILD SAY`) and `LEAVE ... its guild's players are its rivals` / `ignored for weeks` |
| `gu-7` (1.5.0) | Stages a friend, a neutral and a rival bot next to you, guildless. Invite each to a party without asking first | `PARTY INVITE <you> -> <bot>: friend/neutral/rival, chance 100%/30%/0%`; the friend joins and follows you, the neutral one about 1 time in 3 (a no holds 3 minutes), the rival never |

What `!qa stop` undoes:

- Spawned bots leave.
- Items the scenario gave you are taken back, but only what is still in your inventory. Equipped or sold items stay.
- fm-1 closes your shop first, so unsold items come back before they are taken.
- gd-1 takes back the mesos it gave you, up to what you still have.
- A summoned test pet stays with you.
- af-1/af-2/af-3: the staged bot leaves and every tie it had is forgotten; af-3 puts your `@botdrama` back.
- cp-1/we-1: the staged couple is broken up and both bots leave; we-1 also stops the wedding event if it still runs.
- gu-1..gu-7: staged guild bots leave their guild and the world; gu-1/gu-3 make you leave a guild you joined during the
  run; gu-5 ends the boss run (bots inside log back in).

You are not warped back. Mesos you earned in fm-1 stay with you.

Planned for 1.4.2 (`QaScenarios.PLANNED` lists them, with TODO notes in that file): bot buddies, multi-channel follow,
and 5-channel population. Their branches are not merged into rc1 yet.

## mv-1: attack slide

Owner report: holding left/right while attacking on the ground keeps the character sliding. In stock v83 an attack
on the ground stops you. `!qa run mv-1 [attacks] [window ms]` measures it on your own character. It changes nothing
in the game; it only reads your packets.

1. Stand on flat ground by a mob. `!qa run mv-1` (or `!qa run mv-1 30 800` for 30 attacks with an 800 ms window).
2. Attack a few times standing still, then a few times while holding left or right.
3. About 1.6 s after each attack (window + 1 s for the client's batched movement) you get one yellow line:

```
#3 skill 1001004 close: slide 42px (net +40), dy 0, xv max 125, speed 130, fh 12, held yes, 4 frags in 600ms
```

| Field | Meaning |
|---|---|
| `skill`, route | Skill id (0 = basic attack) and handler: `close`, `ranged`, `magic`, `touch` (energy/body pressure) |
| `slide` | Largest horizontal distance from where the attack started, over the movement fragments that ended inside the window |
| `net` | x at the last fragment in the window minus x at the start |
| `dy` | y change over the same fragments |
| `xv max` | Largest horizontal velocity (px/s) the client reported in the window |
| `speed` | Your total speed stat, equips and buffs included (`getTotalMoveSpeedStat`, 100 = base) |
| `fh` | Foothold of your last movement before the attack. `fh 0 (air)` = airborne, `fh ?` = no movement seen yet. `not grounded` marks attacks that do not count as ground attacks |
| `jumped/air` | A jump, knockback, down-jump or fall stance showed up in the window |
| `held` | A walk stance (move action 1) at the attack or in the window: the client says a direction key was held |
| `frags` | Movement fragments in the window. `no movement sent (stood still)` = the client sent none, so you did not move |

After the last attack (or at `!qa stop`), a summary: attack count and how many were grounded, average and max
slide, the grounded average, how many had a direction held, and how many were grounded, held and sliding.

How to read it: on vanilla behaviour a grounded attack has `slide` ~0 px whether or not a direction is held (a few
px of deceleration at most). A grounded attack with `held yes` and a large `slide` (tens of px, growing with
`speed`) is the momentum bug. Airborne attacks move anyway and are not evidence either way.

The log (`logs/qa/mv-1-*.log`) also has every raw fragment (`MOVE`: type, position, xv, fh, stance, duration) and
every attack (`ATTACK`: position, stance, speed) for a closer look.

How it is measured: `AttackCapture.attack` (called by the close, ranged, magic and touch handlers) and
`MovePlayerHandler` call `soloMapling.qa.MoveTrace`, which does nothing unless an mv-1 run armed that character
(one volatile read and a map lookup). Fragment times count back from the packet's arrival by the fragments'
durations. The analysis is `soloMapling.qa.SlideMeter` (pure, tested in `SlideMeterTest`).

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
