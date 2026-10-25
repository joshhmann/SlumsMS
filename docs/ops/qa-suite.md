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
| `!qa` | Opens the QA menu in the NPC window (see below) |
| `!qa list` | Lists every scenario with its id, aliases, one-line description and arguments |
| `!qa run <id> [args]` | Stages the scenario for you and starts its log |
| `!qa do <step>` | Runs one of the scenario's steps (np-3: `!qa do close`) |
| `!qa where` | Shows the running scenario, how long it has run, its log path, and other GMs' runs |
| `!qa stop` | Ends the run, undoes the staging and closes the log |

You can run one scenario at a time. Scenarios that claim a map (bk, gd, fm, gh, tr-1) refuse to start while another GM's
scenario holds that map. A run stops on its own after 15 minutes. ld-1, mv-1, pe-1, ga-2, tr-4 and tr-6 stop after 10, ga-1, tr-1, pr-1 and pr-2 after 20, tr-5 and pq-1 after 30, wd-2 after 5, and bk-1 after
the showcase's own minutes plus one.

### The `!qa` menu (1.5.0)

`!qa` with no arguments opens a menu in the stock NPC talk window (Maple Administrator's head; no NPC needs to be on
the map). Nothing in it is new behaviour: every click types the matching command for you through the normal command
path, so the GM audit, refusals and the QA log are exactly as if you typed it.

- **First page:** the three evenings of the release QA page (1 bots social, 2 economy, 3 combat/client) with their
  scenario counts, and **Auto-QA** when auto-QA is on. While you have a run going, the page starts with
  `Running <id>`, that scenario's `!qa do` steps as clickable lines (`Do: step` = `!qa do step`), steps that need
  typed text as hints (`type: !qa do sim <player line>`), and **Stop**.
- **An evening:** its scenarios under the page's section headings, 9 per page with Next/Previous. 1.4.x scenarios
  that are not on the 1.5.0 page sit in the closest evening (np-* and fm-1 in economy; gh-1, ld-1, wd-2, pe-1 under
  "Older checks" in evening 3).
- **A scenario:** its full title, arguments and steps, then `Run <id>` and one line per variant (we-1 same/mixed,
  rv-2 small, cp-1 married/friends/married same/married mixed, af-1 social/training, ...). Running closes the window
  (staging may warp you); its instructions are in chat as usual. A step keeps the window on the steps unless it
  moved you to another map.
- **Auto-QA:** the latest report's day and build, PASS/FAIL/SKIP/NOT_RUN counts, and each failing check with its
  one-line reason (click: run it again). **Run the smoke set** runs `!autoqa run bg-1,vg-1,ch-1`; **Run one check**
  lists every check with its last status, paged.

Where each scenario sits, its variants and its clickable steps are in `soloMapling/menus/QaMenuCatalog.java`.
`QaMenuTest` fails when a registered scenario is not placed there, or has `!qa do` steps the menu does not list, so
add the new id to the catalog when you add a scenario. The typed commands all keep working.

### Megaphones in a QA run (rc6)

While a run logs `GUILD_WAR` (gw-*) or `BOT_GUILD` (gu-*), the guild and war megaphones (war declared, taunt, result,
peace, poach, alliance, betrayal, recruit, jab, boss call and result) skip the bot megaphone gate and its cooldown, so
they go out the moment the step runs; the next ambient bot megaphone still waits a full gap. Each one that goes out
is logged as `MEGAPHONE <bot> ch<n>: <line>`. Live has no `!qa`, so its gate is unchanged. Outside a run, an OPQ lobby
call no longer pushes a waiting guild or drama megaphone out of the single pending slot.

## Scenarios (1.4.1)

| Id | Stages | Logs |
|---|---|---|
| `bk-1` (`bk-2`) `[4th\|pirates\|pacing\|<job>…] [min]` | `!botshowcase` (default `4th 10`) on your map. `pacing` spawns the 1.5.0 attack-pacing set (Bowmaster, Corsair, F/P, Blaze Wizard, Bishop, Hero, Aran, Thunder Breaker). Each bot's line lists its attacks with the cadence a player gets | Every attack a bot sends: skill, level, route, action/pose, facing, speed, prepare action, weapon id and type, projectile, mobs, lines, damage, `busy` (how long the swing keeps the bot) and `gap` (time since that bot's last swing: the cadence a viewer sees). Attacks the showcase could not send, with the reason |
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
| `ga-2` `[town] [x ceiling]` (1.5.0, 56B) | Warps you to the town's machine, spawns a lv 100 bot next to you and gives it mesos up to x times its wealth ceiling (default henesys 3: about a dozen steps, a couple of minutes; rc6). Every ~8 s it takes a spending step; `!qa do step` steps now. Runs 10 minutes or until the bot is under its ceiling | Each step: the ticket it bought with its own mesos and the prize (and whether it was announced), the upkeep it paid, and its wealth line (held, ceiling, hard line, intake %). DONE when it is under its ceiling |
| `af-1` `[social\|training]` (1.5.0) | Spawns a bot next to you that already counts you as a friend (social by default; `training` holds a training bot on your map) | Every affinity event on your map (`EVENT party/buff/heal/rescue/trade/gift/fame/kind_chat ...` with the score and state), every action a bot takes because of it (`ACT greet/buddy/invite/help`), bot chat, and the bot's state every 30 s (`START`/`STATE`/`FINAL`) |
| `af-2` `[rival\|nemesis]` (1.5.0) | Warps you to 104040000 if your map has no mobs, spawns a training bot held on the map that counts you as its rival (or nemesis). No rival calls a KS war on you by itself during the run (rv-2 tests those). `!qa do grudge` forces a fresh grudge and a KS spree now | As af-1, plus `ACT taunt/ks/defame`, each mob a KS spree takes (`KS ... takes <mob> from <you>`), and refused trades (`REFUSE`) |
| `af-3` (1.5.0) | Turns `@botdrama` off for you and spawns a nemesis training bot. `!qa do on` / `!qa do off` flip it; stop puts it back | As af-2. With drama off: the state reads neutral, and there are no `taunt`/`ks`/`defame` ACT lines and no `EVENT ks` |
| `cp-1` `[married\|friends] [same\|mixed]` (1.5.0) | Spawns two bots next to you that are a couple (dating, or married with rings and the loveseat) or best friends (friendship rings), any genders unless `same`/`mixed` is given; memory only. Runs the pair's step every 20 s (rc6); `!qa do step` runs it now. The staged pair stays together within the first minute | Each pair action: `PARTY`, `TOGETHER` (one walks over to the other), `SMILE`, `HOP` (channel), `SIT` (couple chair, side by side), `MATCHING` (hat), `LOVE NOTE`, plus bot chat on the map |
| `we-1` `[same\|mixed]` (1.5.0) | Needs channel 1. Warps you to Amoria's chapel (the White Wedding Altar, 680000110; rc6), stages a couple there (any genders, or `same`/`mixed`) and starts the `amoria_wedding` event at once | The ceremony: `WEDDING ... begins`, every line said (`SAY`), `BLESS` (your EXP blessing at "i do", once), `RINGS`, `MARRIED`, `WEDDING ... ends`, and bot chat. Stop ends the event if it still runs and removes the couple |
| `pq-1` (ticket 75, real client, two players) | Warps you to the Amoria PQ recruit map (670010100; the run refuses when the host Amos, NPC 9201048, does not stand there), gives you an Entrance Ticket (4031592) and checks the stage 1 map (670010200): live mobs, Glimmer Man (9201047) and the five portals `male00`, `female00`, `go00`, `go01`, `go02`. `!qa do check` re-checks that map, `!qa do where` says where you stand. Runs 30 minutes | `STAGE` lines: the host and where you are, the stage 1 mob count, each portal found or missing (the `PQ` topic). A single-gender party of 2+ has to be able to start it and clear both stage 1 rooms: the doors accept anyone now |
| `gu-1` `[guild]` (1.5.0, bot guilds) | You must be guildless. Stages an officer (rank 2) of a bot guild (default the biggest) next to you that counts you as a friend; its guild name shows over its head. Ask it "<name> can i join ur guild?": it answers, and a real guild invite follows in a few seconds. Accept, then talk in guild chat. Stop takes the bot away and makes you leave the guild if you joined | `GUILDS` (every bot guild at start), `APPLY` (who asked, feelings, level, YES or the refusal), `INVITE SENT`, `PLAYER JOINED`, `GUILD CHAT` (your line and which bot answers, at most one), `GUILD SAY` (every bot guild chat line), bot chat |
| `gu-2` `[friends 1-4]` (1.5.0) | You must be in a guild. Stages 2 (or n) friend bots and 1 neutral bot next to you, guildless. Invite each with `/ginvite`. `!qa do join` adds one more friend that is a member at once | `INVITE <you> -> <bot>: friend, bots x/y, chance n% -> JOINED/declined` (friends join after 4 s here; past half bots the chance drops), `JOIN`, the thank-you `GUILD SAY` |
| `gu-3` `[guild]` (1.5.0) | You must be guildless. Warps you to Henesys and stages an officer of the guild that counts you as a friend, then runs a recruit round. `!qa do recruit` runs another (megaphones still go through the gate) | `RECRUIT MEGAPHONE` (sent or queued/gated), the line said out loud, `RECRUIT WHISPER` and `INVITE SENT` to you |
| `gu-4` `[guild]` (1.5.0) | Warps you to the FM entrance and stages a member of a guild with a rival (default the biggest) and one of its rival, both neutral to you; jabs at once. `!qa do jab` again | `JAB` (who, which guild, the line) and a jab back, `MEGA JAB` (sent or gated), `CLAIM` (a party leader goes to grind where the rival grinds; needs both guilds partied) |
| `gu-5` `[guild] [gpq\|zakum\|pap\|horntail]` (1.5.0) | A fast staged boss run on your channel: 60 s gathering at the entrance (you are warped there), 2 min inside, then back. Needs 2+ online roster members of the guild at the boss's level. Party a crew member while they gather to join for real (it always accepts and follows you). Runs 8 minutes | `BOSS RUN` (crew, channel, map), `PARTY INVITE` / `REAL` (a member went with you), `INSIDE` (who left the world), `BACK`, `RESULT` (cleared or wiped; loot named only, no item; GP on a clear) |
| `gu-6` (1.5.0) | You must be in a guild. Stages a friend bot that is a member of it. `!qa do rival` makes it your rival and runs the quit check; `!qa do ignore` backdates it 30 days and runs the check (player-led guilds). A step on a member that already left stages a fresh one and runs on it (rc6) | `JOIN`, the step's lines, the quit line in guild chat (`GUILD SAY`) and `LEAVE ... its guild's players are its rivals` / `ignored for weeks` |
| `gu-7` (1.5.0) | Stages a friend, a neutral and a rival bot next to you, guildless. Invite each to a party without asking first | `PARTY INVITE <you> -> <bot>: friend/neutral/rival, chance 100%/30%/0%`; the friend joins and follows you, the neutral one about 1 time in 3 (a no holds 3 minutes), the rival never |
| `gu-8` `[guild]` (1.5.0) | You must be guildless. Puts you in the bot guild (default the biggest) and stages a member that likes you. `!qa do kick`: the leader turns into your rival and the kick check runs; `!qa do vote`: two rival members join, so most members with feelings are rivals, and the check runs. Rate limits skipped here. Stop makes you leave the guild if still in it | `KICK <leader> kicked <you> from <guild> (the leader's rival / rival of n/m members), whisper: ...` (the whisper arrives first, then the stock "expelled"), `KICK HELD` when rate-limited on live rules |
| `cb-1` `[job ...]` (1.5.0 rc3) | Stages one grinder per job (default Crusader, F/P Mage, Ranger, Hermit; that job's check level, its line's skills maxed, starter pots and ammo) in your map's densest mob pack, held on the map. No mobs here: warps you to 104040000 | `CHOICE` per swing: mobs in AoE reach, `AOE`/`SINGLE` and why (`PACK` 3+, `FEW`, `BOSS`, `NO_AOE`, `SLOPPY_SINGLE`/`SLOPPY_AOE`); `BUFF` per cast with when it is next due (after it runs out, a little late; on time for a party buff when partied with you); every attack (`ATTACK`); `BUFFS` timers every 15 s |
| `cb-2` `[job ...]` (1.5.0 rc3) | As cb-1 | `FACE` (turned before a swing), `TOUCH` (stepped out of a mob's body), `KNOCKBACK` (stepped back after a hit), `KITE` (archer/mage backed off), `PACE` (walked its platform in a lull); `POSITION` every 15 s: each bot's spot, facing and nearest mob, flagged `INSIDE its body` / `FACING AWAY` |
| `cb-3` `[bots 1-6]` (1.5.0 rc3) | Stages 4 (or n) grinders of random classes 5 levels under your map (a harder map), not held. Their habits go to the log first. `!qa do hurt` hits each to ~45%, `!qa do lowpots` drains their HP pots to their restock mark, `!qa do die` kills one now | `HABITS` (loot style, potion line, restock mark, rebuff lateness, sloppiness), `LOOT` (sweep / instant), `POT` (drank at what HP, its line), `RESTOCK` (down to its mark -> town), `DEATH`, `RESPAWN` (town), `WALK BACK`; `STATE` every 15 s |
| `cb-4` `[bots 1-6]` (1.5.0 rc3) | Stages 3 (or n) neutral grinders at your map's level right next to you, on your platform, not held | `YIELD` (gave up its spot on your platform), `LEAVE` (left a mob you fight or stand by), `CROWDED` (only your platform left here), `MOVE ON` (left the map, with the "cc?" line if it said one), bot chat |
| `sp-1` `[bots 2-12]` (1.5.0 rc3) | Stages 8 (or n) grinders at your map's level in one clump next to you, not held. About 30 s in two of them take a break hanging on a rope (when the map has a safe rope above the mobs); `!qa do rope` does it again (rc6) | `SPREAD` tally at start and every 15 s (bots, 100 px spots covered, pairs closer than 34 px, bots per rope or ladder), `SPACE` (stepped off another bot), `ROPE` (waited for a rope another bot was on, and when it gave up waiting), `ROPE REST` (sent to hang on a rope) |
| `ps-1` `[bots 2-8]` (rc6) | In a town or hangout map (Henesys): spawns 4 (or n) social bots on your own pixel, on top of you and each other. Each walks 40-120 px to a free spot on its own ground within a few seconds (a recorded small move, drawn as a walk). Walk onto one and stand still: it steps away within about 5 s. Runs 5 minutes | `PERSONAL SPACE <bot> stood n px from <who> (player / pair): steps to x ... (small move / walk)`, a `TALLY` every 10 s: overlapping pairs (\|dx\| < 12, \|dy\| < 5) and pairs closer than 25 px, both should reach 0 |
| `ps-2` (1.5.1) | In a town with NPCs (Henesys): places one bot directly on a grounded NPC. No FSM or fidget moves it; the normal personal-space check runs every 2 seconds. Runs 2 minutes; `!qa stop` removes it. | `PERSONAL SPACE ... an NPC` followed by `NPC CLEARANCE bot ... dx=... clear=true`. Auto-QA `t-ps-2` checks the walk and clearance; check NPC name readability and clicking in a real client. |
| `pt-1` `[bots 1-6] [noloot]` (1.5.0 rc4) | Stages 3 (or n) grinders at your map's level next to you, held on the map, each with a pet (its own draw, else one from the pool) wearing a Meso Magnet and an Item Pouch (`noloot`: no loot equips), so the pet loots for it. No mobs here: warps you to 104040000. `!qa do drop` puts 77 display mesos of each bot's own and 66 of yours next to it (credited to nobody) | `PET` per bot at start (pet id, name, `+magnet`/`+pouch`, loot style), `PET LOOT` (the pet picked up mesos / an item, its own drop or a free one), `PETS` every 30 s. Real client: the pet shows behind its bot and follows it, pickups play the pet animation; the 77 go in ~2 s, the 66 stay 15 s, an item you drop yourself is never taken |
| `tr-1` `[FM room 1-12]` (1.5.0 rc3) | Needs channel 1. Warps you to the FM room (default 1), spawns a bot next to you holding 3 Sword ATT 60%, 2 Claw ATT 60%, 10 Power Crystal Ore, a sword and a Chaos Scroll, and opens that bot's own hired merchant where you stand. `!qa do collect` pays the bot its takings, `!qa do close` closes the shop | `SHOP` lines: what was listed at what price (and the board's median or "usual value"), sales (`SHOP SALE` with the fee/tax, `SHOP SOLD ... owed to the bot`), collection, closing. `PRICE sample` lines. The Chaos Scroll must not be listed |
| `tr-2` (1.5.0 rc3) | Spawns a bot with 10m next to you and gives you 2 Gloves ATT 60% and 2 Sword ATT 60% (taken back at stop). Invite the bot to trade, put a scroll in, haggle in the window chat, lock. `!qa do sim [line]` runs the haggle headless (nothing changes hands) | `OPEN`, `TRADE ... BUY offer` with the band, each `CHAT` line's move (rule or decide), `HAGGLE` answers, `CONFIRM` or `CANCEL` (a swapped item, a locked window with nothing it buys), `BOUGHT`, `RESALE`, `PRICE sample` |
| `tr-3` (1.5.0 rc3) | Spawns a bot holding a sword and 2 Sword ATT 60% next to you. Ask it in chat for the sword (or `!qa do quote sword`); open a trade and it puts the sword in the window at its price | `QUOTE` (price and band), `QUOTE haggle`, `OPEN ... BOT_SELLS`, `SELL ask`, `CONFIRM`/`CANCEL`, `SOLD`, bot chat |
| `tr-4` (1.5.0 rc3) | Stages a social bot that counts you as a friend, with 5m and a 60% scroll; for the run a friend bot that sees you gives a gift for sure (the weekly limit forgotten for this pair). `!qa do gift [potions\|scroll\|mesos]` forces one | `GIFT` lines (what, bought for how much), affinity events |
| `tr-5` `[item id]` (1.5.0 rc3) | Nothing: logs the price board's detail for the item (default 2040804) and the FM shop tax at 100k, 1m, 5m, 25m and 100m. `!qa do price <id>`, `!qa do tax <price>` | Every `PRICE sample` and `SHOP SALE` fee while it runs, server-wide |
| `tr-6` `[fair, e.g. 1m]` (1.5.0 rc3) | Nothing: runs 11 player lines (prompt injections included) through the haggle brain and the price band, the bot buying and selling. `!qa do line <text>` tries another | Each line's move and the bot's answer marked `inside the band` or `OUTSIDE THE BAND`; `SUMMARY` with the count outside (must be 0) |
| `tr-7` (1.5.0 rc3) | Warps you to the FM entrance by the cosmetics merchant (Hanako) and gives you 50m (taken back at stop, up to what is left). `!qa do meters` | `SINK` lines: cosmetics merchant purchases; the economy meters at start |
| `pr-1` `<partner> [ring id]` (1.5.0, real client, two players) | Needs a second, non-bot character online (a second account; bots are refused as ring partners): name it. Gives you 12,000 NX credit and pulls the partner next to you (same channel; otherwise move it by hand). Then by hand: (1) Cash Shop, Ring category, **Friendship Ring : Clover** (1112800, 3600 NX; or the id given), press **Buy** (Buy Cart refuses partner rings). (2) The Maple Administrator prompt asks for the partner's name: type it. (3) Both get the ring in the Equip inventory, the partner with a popup; NX goes down once. (4) Both equip it and **change channel once**, come back together: the friendship ring effect shows on both screens (and on a third client if you have one). (5) Esc on the prompt, a wrong name and an offline name: popup, nothing charged. (6) Buy the same ring with the same partner again: refused. `!qa do rings` lists both characters' ring records | `RING`: `PROMPT`, `REFUSED` (code and reason), `CANCELLED`, `NOTHING WAITING`, `BOUGHT` (both ring row ids and partner ids); `EQUIP` lines when either equips the ring. `before:` lines with both characters' ring records at stage |
| `pr-2` `<partner> [ring id]` (1.5.0, real client, two players) | As pr-1 with a **couple** ring (default Crush Ring 1112001, 4800 NX; any of 1112001-1112003, 1112005-1112007, 1112012). Use two characters of the **same** gender at least once: there is no gender check (owner decision) | As pr-1 |
| `rm-1` (1.5.0 bot drama) | Turns `@romance on` for you (put back at stop) and stages a bot with a crush on you next to you. Stand near it: it flirts and asks you out - answer yes/no in map chat (`!qa do ask` asks now; or ask it: "<name> wanna go out with me?"). `!qa do date` stages a partner at once. Dating: change map (it follows, every time in a QA run), sit on a couple chair (it sits with you), `!qa do whisper`, `!qa do jealous` (a rival of yours shows up), `!qa do off` (romance off: no more flirting). The romance step runs every 30 s | `FLIRT`, `ASK OUT` (waiting for yes/no), `DATING`, `REJECTED`, `FOLLOW`, `CHAIR`, `WHISPER` (or "held: you are busy"), `JEALOUS`, `SHOW UP`, affinity `EVENT romance`, bot chat |
| `rm-2` (1.5.0) | Needs level 50 (the stock proposal rule). Turns `@romance on`, stages a partner (lv 60) and gives you a Moonstone engagement ring box. Use the box on the bot (stock window): it says yes in a few seconds. Or `!qa do propose`: it proposes, accept in the window. `!qa do wedding` warps you to Amoria and starts the ceremony (say "i do"). `!qa do divorce` (ring in the equip inventory). Runs 20 minutes | `PROPOSAL` (declined, with why) / `ENGAGED` (marriage id, token, ring), `BOT PROPOSAL`, `WEDDING begins` (friend bots, new guests), `I DO`, `MARRIED` (rings pair ids, marriage id), `WEDDING ends`, `SPOUSE CHAT`, `DIVORCE` |
| `rm-3` (1.5.0) | Turns `@romance on` and stages a partner. `!qa do neglect` (5 days back: a sulky whisper), `!qa do breakup` (the full breakup), `!qa do second` (a second partner) then `!qa do confront`, `!qa do flame` (your ex as an old flame), `!qa do gossip` | `NEGLECT warning`, `BREAKUP ... dumps <you> (neglect): megaphone sent/queued`, `REBOUND` (~20 s later), `TWO-TIMING ... confrontation`, `OLD FLAME`, `GOSSIP`; with `@botdrama off`: `BREAKUP quiet` |
| `rm-4` (1.5.0) | Needs level 50. Turns `@romance on`, stages an ex of yours and a partner (lv 60), and forces an objection and a crashed reception for the next wedding. `!qa do propose` (accept), then `!qa do wedding`. `!qa do runaway` before the wedding forces a runaway instead. Runs 20 minutes | `OBJECTION <ex> objects`, `CRASH`, `RUNAWAY ... no marriage, still engaged`, the wedding lines as rm-2. With `@botdrama off` the wedding has no drama |
| `rv-1` (1.5.0 bot drama) | Warps you to 104040000 if your map has no mobs, stages a rival training bot held on the map, and has it roast you at once. `!qa do roast` (another), `!qa do die` (counts a death today), `!qa do nemesis` (stages a nemesis) | `ROAST facts for <you>: level, job, map (mob level), deaths today, weapon (level), KS count`, then `ROAST <node> ...` (scripted) or `ROAST (model) ...` (the chat model, checked by the content guard); taunts on sight are roasts too (`ACT taunt`) |
| `rv-2` `[small]` (1.5.0) | As rv-1 with a nemesis (`small`: on the Rain-Forest East of Henesys, 100020000, a field with few lanes; rc6). Fight, then `!qa do kswar [2-4]` (caps skipped; live once per player per day, 3 an hour). `!qa do endwar` ends it; stop ends it too | `KS WAR <rival> calls n bot(s) on <you> (who) for 180 s`, each mob taken (`KS <bot> takes <mob> from <you>, n left`), `KS WAR over ... went back` |
| `rv-3` (1.5.0) | As rv-1 with a nemesis, which posts a callout megaphone at once. `!qa do callout` again (caps skipped) | `CALLOUT <bot> on <you>: megaphone sent/queued/gated` |
| `rv-4` (1.5.0) | As rv-1, and arms the rival to follow you once. Take any portal: it shows up 5-8 s later and demands an apology (said and whispered a beat after it lands, asked again at 30 s and 60 s; walk off and it comes after you once; rc6). Say "sorry", mock it ("lol cry more"), or say nothing for 90 s. `!qa do revenge` arms it again | `REVENGE <bot> followed <you> to <map>`, `REVENGE apology (rules/decide): ... calms down (+30)`, `REVENGE mocked ...: NEMESIS now`, `REVENGE ... asks again`, `REVENGE ... chased <you> to <map>`, `REVENGE no answer ... gives up`. A staged rival stays where it is afterwards |
| `gw-1` `[guild]` (1.5.0) | Picks a bot guild with a rival (default the biggest), warps you to 104040000 if your map has no mobs, stages two members of it and one of its rival as grinders held on your map (rc6: they fight, not stand), declares the war (a war the pair already fights ends first, so the declaration is fresh), has the sides trade jabs at once and scores a minute of turf. `!qa do turf`, `!qa do taunt` (war megaphone + a turf fight), `!qa do skirmish` (jabs again), `!qa do score`, `!qa do end` (the result megaphone). Stop ends wars started here (no result megaphone) | `WAR DECLARED a vs b ...; megaphone sent`, `MEGAPHONE <bot> ch<n>: <line>`, `JAB`, `TURF #id a +n / b +n -> score; a holds <maps>`, `WAR TAUNT`, `TURF <bot> goes to contest map`, `WAR OVER ... score` |
| `gw-2` `[guild]` (1.5.0) | You must be in a player guild. Stages a member of a bot guild that is your rival next to you (on a grind map) and runs the guild's player-war check: half or more of your guild's online members are its rivals, so it declares war on your guild. `@guildwar` shows it; `!qa do turf`, `!qa do taunt` (a grinder comes to your map and jabs), `@guildwar peace` (master / jr. master) | `<guild> declared war on <yours> (1 of 1 online members are its rivals)`, the `[Guild War]` notice, `TURF`, `WAR OVER ... peace` |
| `gw-3` `[guild]` (1.5.0) | Picks a bot guild with a rival and poaches a member from the rival at once. In a player guild it also stages a member that likes you: `!qa do player` whispers you an offer (leave your guild within 15 min for a real invite). `!qa do poach` again. Note: a poached roster bot stays in its new guild | `POACH <bot>: <old> -> <new> (megaphone)`, the guild chat lines (`GUILD SAY`), `POACH WHISPER`, `POACH INVITE` |
| `gw-4` `[guild]` (1.5.0) | Allies the bot guild (default the biggest) with another (not its rival). `!qa do chat` (a shout-out in guild chat), `!qa do bossrun` (a joint staged run of both guilds; you are warped to the entrance), `!qa do betray` (a backstab, then war). Stop ends the alliance and the war | `ALLIANCE a + b`, `BOSS RUN a + b -> <boss>`, `BETRAYAL a betrayed b: war #id` |

What `!qa stop` undoes:

- Spawned bots leave.
- Items the scenario gave you are taken back, but only what is still in your inventory. Equipped or sold items stay.
- fm-1 closes your shop first, so unsold items come back before they are taken.
- gd-1 takes back the mesos it gave you, up to what you still have.
- A summoned test pet stays with you.
- af-1/af-2/af-3: the staged bot leaves and every tie it had is forgotten; af-3 puts your `@botdrama` back.
- cp-1/we-1: the staged couple is broken up and both bots leave; we-1 also stops the wedding event if it still runs.
- ps-1: the stacked town bots leave.
- cb-1..cb-4, sp-1, pt-1: the staged grinders leave (pt-1: their pets with them) (a cb-3 bot that died loses the EXP, as any death does, then leaves).
- gu-1..gu-8: staged guild bots leave their guild and the world; gu-1/gu-3/gu-8 make you leave a guild you joined during the
  run; gu-5 ends the boss run (bots inside log back in).
- pq-1: the Entrance Ticket it gave you is taken back (only what is left of it).
- pr-1/pr-2: the NX given is taken back, up to what is left of it. Rings bought and their ring records stay
  (they are real purchases; delete a test pair with the GM tools if needed). The partner is not warped back.
- rv-1..rv-4: the staged rivals leave; rv-2 ends the KS war (the called bots go back where they were).
- gw-1..gw-4: staged guild bots leave; wars and alliances started in the run end without a result megaphone; gw-4
  ends the joint boss run. A bot poached in gw-3 stays in its new guild.
- rm-1..rm-4: every staged bot leaves; an engagement or marriage to a staged bot is undone first (marriages row, rings,
  ring and tokens, partner fields); your `@romance` setting goes back to what it was; rm-2 takes back the ring box.
- tr-1: the bot's shop closes (unsold items back to it) and the bot leaves. tr-4: the gift chance goes back to
  economy.yaml and the friend bot leaves. tr-7: the 50m are taken back, up to what you still have.

tr-2 and tr-3 open a trade window with a bot. Ticket 43 blocks trades between a GM and a non-GM (the owner keeps
it), so use a non-GM test character for the window (the GM runs `!qa`), or the headless `!qa do sim` /
`!qa do quote` steps. Details and the owner's veto tables: docs/release/1.5.0-economy-trade.md.

You are not warped back. Mesos you earned in fm-1 stay with you.

Planned for 1.4.2 (`QaScenarios.PLANNED` lists them, with TODO notes in that file): bot buddies, multi-channel follow,
and 5-channel population. Their branches are not merged into rc1 yet.

## 1.5.0 rc3: bots fight like people, spread out (cb-1 .. cb-4, sp-1)

Only maps a real player can see run any of this; offscreen grinders keep the cheap path. Switches in `config.yaml`
(`server:`), all on by default:

| Key | Default | Meaning |
|---|---|---|
| `BOT_COMBAT_HUMAN` | true | The whole human combat layer (skill choice, facing first, touch steps, pacing, habits). false = rc2 behaviour |
| `BOT_COMBAT_AOE_MIN_MOBS` | 3 | Mobs in reach before the AoE; bosses and lone mobs get the single-target skill |
| `BOT_COMBAT_SLOPPINESS` | 1.0 | Scales each bot's own suboptimal-cast and late-rebuff chances (0 = textbook) |
| `BOT_COMBAT_DEATHS` | true | Rare deaths on maps 3+ levels above the bot (then town, rest, restock, walk back) |
| `BOT_KS_MANNERS` | true | Neutral bots stay off a real player's platform and mobs and move on from a crowded map |
| `BOT_SPREAD` | true | Spot claims per channel, squatters get their own band, personal space, one bot per rope |

`!botcombat` (GM 3, test server only, registered with `!qa`) does the same by hand: `!botcombat` shows the switches,
`!botcombat set <human|aoe|sloppy|deaths|manners|spread> <value|clear>` overrides one until restart,
`!botcombat habits <bot>` prints a bot's habits, `!botcombat stage [job ...]` holds grinders next to you,
`!botcombat clear` removes them, `!botcombat spread` prints the spread tally of your map. 1.5.0 rc4:
`!botcombat pets [n]` holds n (default 3) grinders with looting pets next to you (as pt-1), `!botcombat pet <bot>`
prints a bot's pet, pet equips and loot style.

## Ticket 59: natural conversations (cv-1 .. cv-9)

Every bot is an on-demand bot staged next to you (ticket 58's staging: it counts you as the given state), memory only,
and it leaves at stop. The `CONVO` topic logs how each of your lines was read (`INTENT ... -> action: YES/NO (reason)
-> outcome`, `TAG [do:x]` for a model's proposal), every bot line (`SAID bot -> you [tier, source#bank id, voice, ms]`),
its timing plan (`TIMING`), threads (`THREAD`, `THREAD LINE`, `JOIN`) and ratings (`RATE`). `AFFINITY` and `BOT_CHAT`
run alongside.

| Id | Stages | Check |
|---|---|---|
| `cv-1` `[social\|training]` | A friend bot (social by default). `!qa do sense <line>` shows how any line is read | Name it and ask in plain words: "wanna party?", "come to henesys", "meet me at the fm", "show me the way to perion" (social), "help me kill these", "can u buff me" (training). The action happens before the words, and the words match it (a refusal never says yes) |
| `cv-2` `[item id]` | A neutral social bot carrying Work Gloves (or the item) | "how much for the work gloves" -> `QUOTE`; "would u do 300k" / "thats too much" -> the band's answer; the price is always `PriceBand`'s |
| `cv-3` `[rival\|nemesis\|friend]` | A training bot that counts you as its rival | "sorry for ksing u" -> `makeup: YES` and `EVENT apology`; "ur trash noob" -> `EVENT rude_chat` and game-only trash talk back (only with `@botdrama on`); "ur cute" -> a flirt back only with `@romance on` and a bot that may flirt |
| `cv-4` | Four bots, one per voice: lowercase, proper typer, xX leet kid, Korean-flavoured (`PERSONA` lines). `!qa do persona` | Say the same line to each: casing, abbreviations and laughs differ, a catchphrase now and then. `TIMING`: 1-6 s by length, sometimes split, late (15-60 s) or "brb" first; never faster than a person types |
| `cv-5` | Warps you to Henesys, stages two bots and starts a thread now (LFG). `!qa do thread [beef\|gossip\|lfg\|trade\|training]` | `THREAD` / `THREAD LINE` (llm or bank). Within 45 s of the last line, say something without a name that answers it (auto-QA says "{thread}? i can come too", the subject the staging hands over as `VAR thread=Ludi PQ`; a thread's subject counts as its words for routing): `JOIN`, and the last speaker answers you with the thread in mind. `ROUTE CANDIDATES` shows every candidate and the step that chose (`3-reply-thread` or `3-thread-recent`; ticket 59 section 5a) |
| `cv-6` | A friend bot. `!qa do last` | Talk, then `!rate good` / `!rate bad too formal "certainly"`: `RATE` with what changed (a bank line's weight, or the tune file's examples and don't-say list). `!rate last` shows the line and its source |
| `cv-7` | Bank mode ON for the whole server while it runs (`-Dsolomapling.convo.forceBank`), and a friend bot. `!qa do llm` / `!qa do bank` | Every reply is `SAID ... bank#id` or scripted, in the bot's voice, not repeating; flip to the LLM and back to compare. Stop turns bank mode off |
| `cv-8` `[question...]` | A stranger bot next to you asks the map a scripted question (default "anyone from the east coast?"), like an ambient pack line. `!qa do ask [question]` asks again | Answer within 60 s WITHOUT its name ("im from the east"): `ROUTE` shows it went to the asker, `PROMPT` the exact prompt (system \|\| history with its question as its own turn \|\| the map transcript, its lines marked `(you)`, then `You just said: ... answered: ...`), and `SAID` follows up on its own question |
| `cv-9` `[line...]` | A friend bot next to you. `!qa do hold` says a line to it, unpins it and starts the wander a town wanderer's `ensureRoaming` starts (the walk that used to carry a chatting bot off). `!qa do sample` logs the distance again | `HOLD <bot>: <px> px from you, <s> s of hold left, with you\|WANDERED OFF (150 px)`. Every line must read "with you" for the 45 s after the last exchange: the conversation hold keeps it there (a third character does not push it away, personal space keeps the pair together). Auto-QA runs it as `t-cv-8` (five samples 5 s apart) |

**Hanging around:** a bot in a conversation is held in place for 45 s after the last exchange (`BotConversation.HOLD_MS`, from the player's line, its reply, the typing time and a player who names the bot). Every ambient walk skips while `BotConversation.isHoldingChat` is true: the town wander (`BotWanderSystem`: an in-flight stroll is stopped and the dwell runs to the end of the hold), loiter (`TownLoiter`), fidget (`GCFidget`), `SocialBot`'s relocate, `TownWandererBot`/`HenesysBot` roaming, the training bot's town dwells, and personal space (`PersonalSpace.keepNow`: a partner keeps the pair 18-27 px apart, a third character no longer displaces the bot). Grinding combat is not held. It yields to a real reason to go: a shutdown quiesce, the logout curve, an event instance, or a drama scene (`heldByChat`).

**`!rate`** (GM 1+, every server): `!rate good|bad [note]` rates the last line a bot said to you (10 min; a thread line
on your map: 2 min), `!rate last`, `!rate reload` (re-reads `data/convo/tune.json`), `!rate score [yyyy-mm-dd]` writes
`data/convo/quality/<day>.json` and `latest.json` now.

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

## `!botground`: bots off their foothold (1.5.0 rc5)

Owner report (rc4, Henesys): some bots stood half inside the ground. `!botground` (GM 3, every server) lists the bots
on your map whose server y is not their foothold's y:

```
NachoHat08 at (1148, 363): 29 px below its foothold (y 334), stance 4
```

`!botground fix` also puts the sunk ones back on their foothold (viewers see them pop up). `!botground channel` counts
sunk and airborne bots on every map of your channel. A bot mid-jump shows as "above its foothold"; that is fine.

Cause: the spawn drop (`portalenterdrop`) is replayed relative to the bot and ends 1 px lower than it starts, and
`BotStore.restorePlacement` put the stored point back as is. Each boot sank a restored town bot (no physics) 1 px; ~30
test-server boots left the Henesys, Pet Park and Game Zone townspeople up to 30 px inside the ground. rc5 settles the
stored point on its foothold (`BotGround.settle`) and lands the drop on the floor under the bot
(`MovementCommands.BotMoveStreamOffsetLanding`), which also lands bots that warped in at a portal instead of leaving
them hanging at the portal's height. After an rc5 boot `!botground channel` should report no sunk bots.

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
5. Place it in the `!qa` menu: an `add(...)` line in `soloMapling/menus/QaMenuCatalog.java` under its evening and
   section, with its run variants and clickable `!qa do` steps (`QaMenuTest` fails until you do).

## Auto-QA: health sweeps and checks that run themselves

Auto-QA runs most of the release checklist without a person: periodic health sweeps over the live bots, and a
runner that plays the checklist with two headless synthetic players. It is off by default and meant for the test
server only. Code: `soloMapling.autoqa`.

### Switching it on

Pass either of these (both work):

- `-Dslums.autoqa=true` on the java command line, or
- `AUTOQA_ENABLED: true` under `server:` in config.yaml.

The runner drives `!qa`, so it also needs the `!qa` suite: `-Dslums.qa=true`, or attack capture, which start-test.sh
already passes. The health sweeps work without it. Live passes neither switch, so nothing registers there.

| Key (`server:`) | Default | Meaning |
|---|---|---|
| `AUTOQA_ENABLED` | false | Switch (or `-Dslums.autoqa=true`) |
| `AUTOQA_SWEEP_MINUTES` | 5 | Minutes between health sweeps |
| `AUTOQA_SWEEP_MAX_BOTS` | 3000 | Bots looked at per sweep; past this an even sample is taken |
| `AUTOQA_STUCK_MINUTES` | 3 | A moving bot in one spot this long counts as stuck |
| `AUTOQA_CLUMP_RADIUS` / `AUTOQA_CLUMP_MIN_BOTS` | 30 / 5 | This many driven bots within this many px make a clump |
| `AUTOQA_NIGHTLY_UTC_HOUR` | 10 | The nightly `all` run (10 UTC = 3 AM PDT); -1 = no nightly run |
| `AUTOQA_JUDGE_MAX_CALLS` | 40 | LLM judge calls per run, at most |

### Commands (GM 3)

| Command | What it does |
|---|---|
| `!autoqa run <check>` | Runs checks in the background. `<check>` can be a page id (`t-af-1`), a short id (`af-1`), a group (`af`), a comma list (`bg-1,vg-1,ch-1`), `smoke` (that quick set) or `all`. Each result is posted to you as it finishes. The `!qa` menu's Auto-QA page runs these for you |
| `!autoqa status` | The runner's state, check and step |
| `!autoqa stop` | Stops after the current step. Cleanup still runs |
| `!autoqa report` | Today's totals, the last health sweep, grounding (server and sent), the chat-quality headline, say-do since boot, then every FAIL and SKIP with its reason |
| `!autoqa health` | Runs a health sweep now and prints its summary |
| `!autoqa list` | Every check, marked automated, half (logic only) or manual. `*` = left out of the nightly run |

### Health sweeps

Every `AUTOQA_SWEEP_MINUTES` the sweep runs on the shared scheduled executor, never on a game or tick thread. It
reads map player lists (copies taken under the map lock) and never loads a map. It checks:

| Check | Rule | Status |
|---|---|---|
| (a) `foothold` | Every grounded bot stands within 2 px of the nearest foothold line spanning its x, above or below. Bots in the air, on a rope or ladder, swimming (every bot on a swim map), part-way along an unobserved analytic move (`COARSE`: its position is interpolated along a nav edge, so it is in the air or inside a platform by design), or dead are skipped, and counted by pose in `skippedByPose`. The pose comes from the movement driver (`GCMovement.sweepPose`) or, for town dressing, from the stance. Each problem is reported as `SUNK` (under the line, with its depth `dy`), `FLOATING` or `NO_GROUND`, with the bot's `pose` and `driven` | FAIL on any SUNK, WARN on the others |
| (a2) `foothold (sent)` | rc6: the same rule on what clients were TOLD. For every character (bot, real player or synthetic player) on a map where a real or synthetic player is, the y of the last spawn (`SPAWN_PLAYER`) or move (`MOVE_PLAYER`) packet built for it (`SentPositions`, recorded in `PacketCreator` only while auto-QA is on) is checked against the foothold under that x. Same 2 px tolerance and skip rules: the pose comes from the sent stance (a spawn's drop, stance 6, is airborne; a "standing" fragment with foothold 0 is airborne), a swim map is SWIMMING, and a bot the driver says is `COARSE` is skipped. A character's record is dropped when it enters a map, so a move on the old map never counts on the new one. Each problem lists `who` (bot/synthetic/player), the `packet` (spawn/move) and its `ageSeconds` | FAIL on any SUNK, WARN on the others |
| (b) `stuck` | A bot the driver says is moving (`isMoving`, not resting) that has not left an 8 px box for `AUTOQA_STUCK_MINUTES` | WARN, FAIL at 10+ |
| (c) `clumps` | `AUTOQA_CLUMP_MIN_BOTS` driven bots within `AUTOQA_CLUMP_RADIUS` px on one map. rc6: on a town or hangout map (`PersonalSpace.appliesOn`) every grounded idle bot counts, recorded-path town bots included, and 3 within 20 px is a clump (town bots keep 25 px apart, partners 15). FM rooms are left out | WARN |
| (h) `overlap` | rc6 personal space: two grounded, idle characters (bots, synthetic and real players) on one map instance with \|dx\| < 12 px and \|dy\| < 5 px: their name tags overlap. Ropes, ladders, jumps, coarse unwatched moves and walkers (a recorded-path walk or a walking stance) are left out, and so are deliberate pairs (same pair key: a player and the bot it talks with, a couple, a drama scene, a cluster talk, ambient chatter, shop keepers). Lists the pairs, closest first, and `worstMaps` (pairs per map instance) | WARN at any, FAIL at 5+ |
| (d) `channelSplit` | Bots per channel against `BOT_CHANNEL_WEIGHTS` (`BotChannels.shares`): more than 15 points off, a channel weighted 0 that has bots, or a weighted channel with none. Not judged under 40 bots | WARN |
| (e) `invalidMaps` | A bot on map 999999999 or a GM, jail or developer map, standing in a map other than its character's map id, or outside the map's foothold bounds by 200+ px | FAIL |
| (f) `errors` | Lines in `logs/cosmic-log.log` containing ` ERROR ` (or an uncaught exception) since the last sweep, against the median of the last 12 windows: 20+ and over 3x the median is a spike | FAIL |
| (g) `mapCap` | 1.5.0: a map instance (one map on one channel) holding more bots than its per-map bot cap (`BOT_MAP_CAP`, default 50, or its `BOT_MAP_CAP_OVERRIDES` entry; `soloMapling.ArtificialPlayer.BotMapCap`). Reads each instance's bot counter, not the bot sample; lists the instances over their cap, worst first, plus how many are full and the busiest count. The cap only stops additions and some arrivals are exempt (set pieces, followers, story scenes), so this is a warning to look at with `!botmapcap`, not a failure. In the sweep summary and the `AUTOQA HEALTH` QaLog line as `mapCap WARN: n map instance(s) over the cap, worst map ...` | WARN |

The sweep is the periodic, server-wide form of `!botground channel` (rc5 ground fix): `!botground` uses a 1 px
tolerance on demand, the sweep 2 px so slope rounding never pages anyone. `HenesysGroundWzTest` also checks the three
townspeople of the rc5 report (NachoHat08 at (1148, 363) on y 334, MochiUltra, CandyClock: 28-29 px down) come out
SUNK.

Why the foothold check looks both ways: `FootholdTree.findBelow` only looks at or below a point. A bot sunk into
the ground finds the next platform down, or nothing, and every server lookup thinks it is fine.
`HenesysGroundWzTest` shows a real case on rc5.

rc6: the synthetic players are in the `foothold` check too (`syntheticChecked`, `"synthetic": true` on a problem),
but not in the bot-only checks (stuck, clumps, invalid maps). They send no movement packets, so when one enters a
map (`MapleMap.addPlayer`) it is put on its foothold with `BotGround.settle`, the way a client falls onto the floor
under a portal; before rc6 they floated at the spawn point.

The server check reads the server's copy of each position; `foothold (sent)` reads what a viewer's client draws. A
bot whose server y is right but whose last move packet said otherwise (or the other way round) shows up in only one
of them. The Henesys filler range "left side top plat", (-286, 101) to
(7, 94), puts every bot at y=101. Where the platform rises to 94 that is 7 px inside the ground, and the sweep
reports those bots as SUNK.

Output:

- `data/autoqa/health-latest.json` holds the last sweep: `time`, `build`, `durationMs`, `status`, `summary`,
  `botsSeen`, `botsSampled`, `mapsSeen`, and `checks.<name>` with `status`, `summary` and the worst cases (bot id,
  name, channel, map, x, y, and the check's own fields).
- `data/autoqa/health/<yyyy-MM-dd>.jsonl` gets one line per sweep (UTC day).
- An `AUTOQA HEALTH <status>: <summary>` line goes to any running `!qa` log that listens to the `AUTOQA` topic,
  and to the server log (info when OK, warn otherwise).

### Synthetic players

There are two real characters on their own accounts. `AutoQaOne` (account `autoqa1`) is GM level 3 and runs
`!qa`. `AutoQaTwo` (account `autoqa2`) is a plain player for the two-player checks. They are made on first use as
level 70 Warriors in Henesys, with normal database ids far below the bot id base, and log in on channel 1.

- They log in through the real `PlayerLoggedinHandler`, on a `client.SyntheticClient`.
  `SessionCoordinator.primeSyntheticLogin` stands in for the login server's part.
- They log out the way a closed client does (`Client.disconnect`), at the end of every run.
- `SyntheticClient` is a Client with no socket and is deliberately not a `BotClient`. `BotHelpers.isBot` is false
  for them, so affinity, chat replies, trades, parties, guilds and drama treat them as people.
- Everything they do is a packet body handed to the channel's handler, the same bytes a v83 client sends: map chat
  and commands, whispers, party invite and accept, guild invite and accept, buddy add, buddy, party and guild chat,
  proposals and their answer, spouse chat.
- What their client receives is kept as lines: map chat, whispers, notices, the yellow command answers, buddy,
  party and guild chat, invites, proposals and NPC talk. Other packets are dropped after reading their opcode.

They are not counted as connected players: `PlayerStorage.getConnectedPlayerCount` leaves them out as it leaves out
bots, so a run never fills a world, trips the world-full check or raises a channel's real-player count. In the load
bar they weigh like a bot (a quarter of a player, two of them at most). They are not bots either, so the bot counts
(`BotChannels`, the population director) never see them. Login limits do not apply: they have their own accounts and
skip the login server.

They cannot move by input (a move is a server warp or `!warp`), attack, open the Cash Shop, buy from NPC or player
shops, or use a trade window yet. Checks that need those are half or manual, below.

### The runner and its checks

`AutoQaCatalog` holds every check of the 1.5.0 notes page in page order.

- A check's steps follow the page's Steps line: `!qa run <scenario>`, `!qa do <step>`, the chat lines (with
  `{bot}` = the staged bot's name), and waits.
- Its expectations follow the Expect line, read from four places:
  - the `!qa` log lines of its run, through `QaLog.listen`;
  - what the players heard;
  - server-side probes (party, guild and buddy membership, maps, mesos, odds, prices, the version gate);
  - for chat, an LLM judge.

`AutoQaRunner` is a state machine: `IDLE -> PREPARING -> (per check: STEPPING -> ASSERTING -> CLEANING) ->
FINISHED | ABORTED -> IDLE`. It runs on its own daemon thread. Each check gets one of three results:

- **PASS**: every expectation held. SKIP expectations, such as when no judge is configured, do not fail a check.
- **FAIL**: an expectation failed, a step failed (`!qa run` refused for a reason that is not a precondition, a
  `{bot}` that never appeared), or the check ran past its minute budget. The evidence lists the lines that matched
  or what was missing.
- **SKIP**: a manual check, a precondition refusal (no bot guilds, wrong channel, guild state ...), a check left out
  of the nightly run, or the run was stopped.

Cleanup always sends `!qa stop`. Guild state is set up by fixtures:

- "be guildless" leaves the guild, and disbands the runner's own test guild so nightly runs do not pile them up.
- "be in a player guild" makes `AutoQa<nnnnn>`.

Checks that leave a lasting change run only when asked by id, never nightly: t-gw-3 (the poached bot stays), t-cv-6
(a rating changes the bank weights), t-mr-1, t-rm-2 and t-rm-4 (engagements and marriages).

| Kind | Checks |
|---|---|
| Automated | af-1/2/3, rv-1..4, bb-1/2, bg-1, gu-1/2/3/4/5/6/8, gw-1..4, pb-1, cu-1/2, we-1, mr-1, rm-1..4, cv-1/2/3/5/6/7, ga-1/2, wc-1, bs-1, td-3, gf-1, pr-1, sk-1/2, sc-1/2, cb-1/3/4, sp-1, id-1, vg-1, ch-1 |
| Half (logic here, look by eye) | cv-4 (the voices), cb-2 (swings on screen), pt-1 (pets following), bk-1 and at-1 (swing pacing on screen), td-1 and td-2 (the trade window: the haggle runs headless with `!qa do sim` / `quote`), ga-3 (the client's label for the last remote slot) |
| Manual | ga-4 (a real client's pick-up), rg-1/rg-2 (the Cash Shop purchase flow is not driven yet), mv-1 (needs the tester's own movement packets), jq-1, cl-1/2/3, cc-1, mr-2, la-1 |

Notes on what some checks prove:

- **vg-1** runs `ClientVersionGate.verdictFor` on a scratch config: build 10500 against a minimum of 10500 is
  allowed, 10410 is refused with the update message. No restart, and the live gate is untouched.
- **ga-2** checks each new machine NPC with the foothold rule and keeps it 40+ px from a portal.
- **ch-1** reads `BotChannels.botsPerChannel()`: 5 channels, ch1-4 have bots, ch1 the busiest and ch5 the quietest, each within max(10, 25%) of its `BOT_CHANNEL_WEIGHTS` share.

### The LLM judge

`LlmJudge` grades bot lines for the conversation, drama and guild checks. It uses the cloud provider when
`BOT_LLM_CLOUD_BASE_URL` is set (key from `BOT_LLM_CLOUD_API_KEY` or `SOLOMAPLING_LLM_CLOUD_API_KEY`), else the
local model (`BOT_LLM_*`), else none.

- **Rubric:** on-topic, coherent with the line before it, in character for a 2008 player, PG-13 with game-only
  insults, and never says it is an AI or a bot.
- **Answer:** the model returns JSON. An unreadable answer is undecided, never a FAIL.
- **Model-free checks:** a line that says "as an AI" or carries an emoji fails without asking the model.
- **Limits:** at most `AUTOQA_JUDGE_MAX_CALLS` calls per run, each with the provider's timeout. The key is never
  logged or put in evidence.

### The report

`data/autoqa/report-<yyyy-MM-dd>.json` (UTC day) and `data/autoqa/latest.json`:

```json
{ "build": "1.5.0-rc5", "day": "2026-10-23", "updated": "2026-10-23T11:42:07Z",
  "summary": {"PASS": 41, "FAIL": 3, "SKIP": 20, "NOT_RUN": 9, "total": 73},
  "health": {"status": "FAIL", "summary": "foothold FAIL: 6 of 900 grounded bots off ...", "time": "...",
             "foothold": "FAIL: 6 of 900 grounded bots off ...", "foothold (sent)": "OK: 212 sent grounded positions on their footholds"},
  "chatQuality": {"headline": "chat 24h: ...", "relevance": 0.82, "stiffness": 0.04, "repetition": 0.06,
                  "unanswered": 3, "sayDo": 0.64, "problems": [], "file": "chat-quality-2026-10-23.json"},
  "sayDo": {"claims": 45, "backed": 29, "rate": 0.644, "bySource": {...}, "byKind": {...}, "open": 3},
  "runs": [{"trigger": "nightly by the schedule", "started": "...", "finished": "...", "checks": 73,
            "PASS": 41, "FAIL": 3, "SKIP": 29}],
  "checks": {"t-af-1": {"status": "PASS", "title": "A friend bot acts like a friend", "kind": "AUTO",
                        "reason": null, "evidence": ["started: QA af-1 running: ...", "ok: at least 1 x /AFFINITY ACT .../", "  qa AFFINITY ACT greet ..."],
                        "started": "...", "finished": "...", "seconds": 214, "build": "1.5.0-rc5"}}}
```

- A day's runs merge: a check run again replaces its entry, and the others stay.
- A new day's report lists every page check: NOT_RUN, or SKIP with the reason for manual ones.
- `!autoqa report` prints the short version.

### Inference checks (rc6): things we used to think needed a person

#### Chat quality, daily (`ChatQuality`, check `t-cq-1`)

After every auto-QA run and once a day (half an hour before `AUTOQA_NIGHTLY_UTC_HOUR`, 09:30 UTC by default) the
last 24 h of bot chat is scored and written to `data/autoqa/chat-quality-<UTC date>.json`. Input: the ConvoQuality
transcripts (`logs/convo/transcript-<date>.jsonl`: bot replies to players, and bot thread lines) and the say-do
verdicts (`data/autoqa/saydo/<UTC day>.jsonl`, below).

| Score | How | Threshold (t-cq-1 FAILs) |
|---|---|---|
| `relevance` | The LLM judge, 20 reply pairs per call, at most 5 calls (100 pairs, spread over the day), charged to the server's cloud budget; when the budget is spent the local model (`BOT_LLM_*`) answers; with neither, `score` is null and not judged. A reply is paired with the player's line; a thread line with the other bot's line before it on that map (2 min) | under 70% on topic, with 10+ judged |
| `stiffness` | Per line: assistant phrasing ("feel free", "let me know", "certainly" ...), over 90 characters or 18 words, or, for a bot whose voice is not `proper`, a proper-case full sentence with no slang. `byVoice` shows stiff/total per voice | over 25%, with 20+ lines |
| `repetition` | A line that repeats an earlier one in the window, exactly or near (80%+ of the words of a 3+ word line), from any bot to any player; `mostRepeated` lists the top 5 | reported only |
| `unanswered` | A player's question (ends in ?, or starts with who/what/where/how ...) the reply did not answer: the judge's `answers`, or without a judge a one- or two-word dodge | reported only |
| `sayDo` | Strong claims backed / made, per source and kind, with the top unbacked kinds and examples (below) | under 50% backed, with 20+ claims |

The file also has `worst`: the 15 worst lines (off topic, stiff, repeated, unanswered, or a claim not kept) with
the bot, its voice, the source, the player as the transcript's hash, and the flags. Player names are never written:
the transcript already has `{player}` and a hash, and say-do lines have the names of the real players on the map
replaced by `{player}`. `latest.json` gets `chatQuality` (the headline, the scores, the problems, the file name) and
`sayDo` (counts since boot). Example headline:

```
chat 24h: 129 lines; relevance 100% (60 judged); stiff 0%; repeats 0%; unanswered 0/6 questions; say-do n/a backed (0 claims)
```

#### Say-do: a bot does what it says (`BotConvo.ClaimRules`, `SayDo`, `ClaimActions`)

The owner's goal. Every bot line (map chat and bubbles via `SocialCommands.BotFullChat`/`BotChatbubble`, reply
whispers, drama whispers, megaphones) is read by `ClaimRules`, cheap first-person rules that ignore negation ("no
buff for u"), third person ("she quit the guild") and quoted speech:

| Claim | Examples | Backed by | Window |
|---|---|---|---|
| `come` | omw, otw, be there in a sec, ill come | a walk the convo layer started, a trip, a map change, or ending up within 400 px of the player | 120 s |
| `go_to` | going to orbis, heading to the fm, omw to fm | a map change or trip into that town's area (map id / 1,000,000; FM rooms are the FM), or already there | 120 s |
| `follow` | follow me, this way (weak), ill show u | a walk, trip or map change, or 150+ px moved | 60 s |
| `party_offer` | ill invite u, inv sent | a party invite, create or join | 90 s |
| `party_ask` | inv me, lf party, LFG, J> | a party join, or really being partyless when it asked | 90 s |
| `buff` | here buff, healing u, buffed u | a buff or heal cast (`BotBuffEffects`) | 30 s |
| `sell` / `buy` | S> ilbis, selling X / B> X, wtb X | the item in its bag or trade window (sell) or on its wish list (buy), an open shop, or a trade | 120 s |
| `trade` | ill trade u, sending a trade | a trade window | 120 s |
| `trade_ask` | trade me, just open a trade with me | a trade window, or the bot takes a trade when one is opened (a merchant in trade mode, a drop game taking bets, a bot that trades in person and does not refuse that player); rc6: "trade me" asks the reader, it promises no window | 120 s |
| `resupply` | brb pots, gotta restock, out of pots | a resupply trip started (TrainingBot GO_TOWN for supplies) | 180 s |
| `away` | brb, afk, mom's calling, brb dinner | going idle or sitting, logging out, or staying put and quiet for the window | 60 s |
| `leaving` | gtg, night all, logging off | logging out | 180 s |
| `train_at` | training at ant tunnel | being in that area, or a trip there | 120 s |
| `gift` | here take this, free for u | a gift (BotGifts, a payout, a trade where only the bot gave) | 60 s |
| `leave_group` | leaving pt, gquit, im out (weak) | leaving a guild or party, a map change or logout | 120 s |

A weak claim (words that often mean something else: "coming" in a question, "this way", "here you go", "im out")
is recorded and listed but never fails anything. Events come from cheap hooks that return at once unless the bot has
an open claim (map change, `GCMovement.travel`, the convo walk, party commands, buffs, trades, shops, gifts, guild
leave, kills, chairs, logout); a trip that starts up to 10 s before its line still counts. At most 2,000 claims are
open. All of this is in memory everywhere, live included (off with `-Dslums.saydo=false`); only with auto-QA on is
each verdict appended to `data/autoqa/saydo/<UTC day>.jsonl`, and each is a `SAYDO CLAIM` / `SAYDO BACKED` /
`SAYDO UNBACKED` line on the CONVO topic. Sources: `reply-llm`, `reply-bank`, `reply-scripted`, `thread-llm`,
`thread-bank`, `pack:SocialHotPotato/<category>`, `ad` (merchant ads, and scripted S>/B>/LFG lines), `megaphone`,
`drama-whisper`, `scripted` (any other dialogue).

Claims are also made true or not said:

- **Ambient chatter** (`BotAmbientSpeak`, the SocialHotPotato picker): a line with a strong claim is said only when
  `ClaimActions.canBack` says the bot will do it, and `ClaimActions.start` then does it: "brb pots" makes a grinding
  TrainingBot leave for its resupply trip (`TrainingBot.requestRestockTrip`); "brb"/"afk"/"mom's calling" makes a
  town bot stop and maybe sit for 30-90 s (a TrainingBot only when it is already resting; a grinder mid-fight never
  takes the line); "gtg"/"night all" logs a roster bot out through `BotLogout.request` within 10-40 s if the
  director's `mayLogOut` allows; "going to <town>" sends a town wanderer there. Come, follow, invite, buff, trade and
  gift claims have no one to be for in ambient chatter and are never said ("trade me" only from a bot that takes
  trades). The picker tries up to 6 lines, then a line with no claim. The same pick guards megaphone lines
  (SocialHotPotato), merchants' scam/begging/RWT/funny flavor (`executeBotFlavorDialogueBacked`) and couple
  mentions ("{PARTNER} brb pots"). A TrainingBot's grind break counts as its "brb bathroom" (an idle event).
- **The line bank:** `ChatBankGen pack` writes each line's strong claims into its tags field (`claim:resupply`),
  under the header `#chatbank v2 claims` (an older pack is tagged at load). A reply only picks a line whose claims its
  decided action backs (`ConvoLines.claimsFor`: come_yes/help_yes may say omw, party_yes inv me, buff_yes buffed u,
  trade_offer S>/trade me, thread_lfg LFG; nothing else may claim).
- **Replies** (`BotConversation.deliver`): `ClaimActions.guardReply` takes out a clause whose claim the decided
  outcome does not back ("omw" with the walk refused, "inv me" with no party, a tacked-on "brb"); with nothing left
  the bot says a short neutral word. The timing layer's "brb" is only used when the bot can step away, and then it
  does. Thread lines get the same guard (an LFG ask may stay).
- **Ads:** a SellingMerchantBot advertises "S> item" only for the item in its trade window or bag, a BuyingMerchantBot
  "B> item" only for an item on its wish list.

Every `t-cv-*` check that chats (1-5, 7) ends with `Expects.sayDo(130)`: it waits up to 130 s for the check's open
claims to be judged, then FAILs on any strong `SAYDO UNBACKED` line, with the line and why as evidence.

#### Scene focus (`SceneFocus`, in t-we-1, t-gw-1, t-rv-2)

| Check | Measures | FAIL when |
|---|---|---|
| t-we-1 | From `WEDDING #n begins` to `ends`: the guest lines (what player 1 heard on the ceremony map, else the run's `BOT_CHAT`; not the couple's or the officiant's) that are about the wedding (keyword set: wedding, rings, vows, cake, bouquet, cute, congrats, gz, <3 ... or the couple's names); the guests still on the map at the end (`WEDDING #n guests at the end: k of n`, new in rc6, with the officiant and guests named in `WEDDING #n cast`); whether officiant -> groom's vow -> bride's vow -> rings -> married came in order; the gaps between those beats | under 60% of 5+ guest lines on topic, more than half the guests gone early, a beat missing or out of order, or a gap over 60 s |
| t-gw-1 | Kills per side (`GUILD_WAR KILL <bot> [guild] on <map>`, new in rc6, logged while a run listens), jabs and taunts per side, and war talk (a member's line with a war word or the other guild's name). The check now waits 40 s after a skirmish so both sides can fight | a side never says a word about the war, or (with kills logged) a side kills nothing |
| t-rv-2 | The bots the KS war called, and the mobs each took (`AFFINITY KS <bot> takes`) | fewer than half the called bots took a mob |

`SceneFocus.parseQaLog` reads a `logs/qa/<id>-<UTC>.log` file, so the same measures run on old logs (as the tests do,
with lines from the test server's 2026-10-23 runs).

### Adding a check

1. Add it to `AutoQaCatalog` where it sits on the page. Use:
   - `Check.auto(id, title)`, or `half(id, title, what still needs eyes)`, or `manual(id, title, why)`;
   - `.step(...)` from `Steps`: `run`, `qaDo`, `say`, `waitFor`, `mark`, `capture`, `fixture`, invites and chat;
   - `.expect(...)` from `Expects`: `has`, `atLeast`, `lacks`, `hasAfter`/`lacksBefore` around a mark, `inOrder`,
     `probe`, `rule`, `numbersWithin`, `judged`, `noEmoji`;
   - `.minutes(n)` for the budget, and `.notNightly()` if a run leaves something behind.
2. A server fact the lines do not carry becomes a probe in `GameAutoQaWorld.probe`. Its first line is
   PASS/FAIL/SKIP, and `VAR name=value` lines set variables.
3. `AutoQaCatalogTest` keeps the page order, checks that every `!qa run` scenario exists, and compiles every
   pattern. `AutoQaRunnerTest` drives the runner against a scripted world.

Patterns must match what the hook really writes (case, order, topic and note kind). `AutoQaCatalogPatternTest` feeds
checks lines copied from the game's own format strings. Add a case there when you add or change a pattern. A
placeholder that never gets filled (a staged bot that did not show up) fails the expectation. It never passes a
`lacks` and never throws.

Tests: `GroundingAsSentTest` (synthetic grounding, sent positions), `ChatQualityTest`, `SceneFocusTest`,
`ClaimRulesTest`, `SayDoTest`, `BankClaimsTest`, `MerchantAdHonestyTest`, `FootholdCheckTest`, `SweepMathTest`, `HealthSweepTest`, `CoarseGroundTest`, `HenesysGroundWzTest` (needs wz/), `AutoQaRunnerTest`,
`AutoQaReportTest`, `AutoQaCatalogTest`, `AutoQaCatalogPatternTest`, `LlmJudgeTest`, `SyntheticClientTest`,
`PlayerStorageTest`.
