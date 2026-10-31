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
scenario holds that map. A run stops on its own after 15 minutes. ld-1, mv-1, pe-1, ga-2, tr-4, tr-6 and tr-9 stop after 10, ga-1, tr-1, pr-1 and pr-2 after 20, tr-5, pq-1 and pq-2 after 30, wd-2 after 5, and bk-1 after
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
| `bk-1` (`bk-2`) `[4th\|pirates\|pacing\|<job>…] [min]` | `!botshowcase` (default `4th 10`) on your map. `pacing` spawns the 1.5.0 attack-pacing set (Bowmaster, Corsair, F/P, Blaze Wizard, Bishop, Hero, Aran, Thunder Breaker). Each bot's line lists its attacks with the cadence a player gets | Every attack a bot sends: skill, level, route, action/pose, facing, speed, prepare action, weapon id and type, projectile, mobs, lines, damage, `busy` (how long the swing keeps the bot) and `gap` (time since that bot's last swing: the cadence a viewer sees). Attacks the showcase could not send, with the reason. 1.6.0: `pirates` adds Gunslinger and Brawler; `GUARD melee without a range` means a bot built a melee attack a viewer's client would have closed on (the packet guard sent a plain swing instead); `SHOWOFF` is an idle bot's show-off attack. Auto-QA t-bk-3 runs `bk-1 pirates` and fails on either a GUARD line or a melee packet without a range |
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
| `tr-3` (1.5.0 rc3) | Stages a neutral social bot (1.6.2: an idle spawn never heard chat) holding a sword and 2 Sword ATT 60% next to you. Ask it in chat for the sword (or `!qa do quote sword`); open a trade and it puts the sword in the window at its price | `QUOTE` (price and band), `QUOTE asked again` (a re-ask names the open quote), `QUOTE haggle`, `OPEN ... BOT_SELLS`, `SELL ask`, `CONFIRM`/`CANCEL`, `SOLD`, bot chat |
| `tr-4` (1.5.0 rc3) | Stages a social bot that counts you as a friend, with 5m and a 60% scroll; for the run a friend bot that sees you gives a gift for sure (the weekly limit forgotten for this pair). `!qa do gift [potions\|scroll\|mesos]` forces one | `GIFT` lines (what, bought for how much), affinity events |
| `tr-5` `[item id]` (1.5.0 rc3) | Nothing: logs the price board's detail for the item (default 2040804) and the FM shop tax at 100k, 1m, 5m, 25m and 100m. `!qa do price <id>`, `!qa do tax <price>` | Every `PRICE sample` and `SHOP SALE` fee while it runs, server-wide |
| `tr-6` `[fair, e.g. 1m]` (1.5.0 rc3) | Nothing: runs 11 player lines (prompt injections included) through the haggle brain and the price band, the bot buying and selling. `!qa do line <text>` tries another | Each line's move and the bot's answer marked `inside the band` or `OUTSIDE THE BAND`; `SUMMARY` with the count outside (must be 0) |
| `tr-7` (1.5.0 rc3) | Warps you to the FM entrance by the cosmetics merchant (Hanako) and gives you 50m (taken back at stop, up to what is left). `!qa do meters` | `SINK` lines: cosmetics merchant purchases; the economy meters at start |
| `tr-8` (1.6.0, ticket 86) | Spawns a bot next to you holding Gloves ATT 60% and a sword; the megaphone desk's delay is cut to 1-3 s for the run. `!qa do smega <text>` runs the text as your megaphone: `B> glove att 60% 3m` gets one whisper from the bot; `B> zakum helmet 1m` (no stock), `S> ...` (a sell) and `B> glove att 60% 1k` (far below) get none. `!qa do react <text>` sends the text through the real hook (default delays, limits and chat reactions) | `MEGABUY` lines: `CLAIM` (the one bot, delay, price), `OFFER` (the whisper), `NOSTOCK`, `IGNORE` (a sell), `FAR`, `LIMIT`, `DROP`. Price inside the bot's sell band. Auto-QA `t-mb-1`; the whisper's look and a real client's smega need a person |
| `tr-9` (1.6.2) | Stages a social bot that counts you as a friend, with 200 White Potions; today's asked-for gift caps of that bot and your account, and the pair's weekly gift, are forgotten. Say "<bot> got any pots?": up to 20 drop at your feet, yours alone; ask again and it says no (one gift a week per bot and player) | `GIFT-ASK` lines (who asked whom for what, the outcome: GAVE, NOT_A_FRIEND, ALREADY_THIS_WEEK, NOTHING_TO_SPARE, NO_DROPS, CAPPED) and the `GIFT` line of what landed. Auto-QA `t-gf-2` |
| `pr-1` `<partner> [ring id]` (1.5.0, real client, two players) | Needs a second, non-bot character online (a second account; bots are refused as ring partners): name it. Gives you 12,000 NX credit and pulls the partner next to you (same channel; otherwise move it by hand). Then by hand: (1) Cash Shop, Ring category, **Friendship Ring : Clover** (1112800, 3600 NX; or the id given), press **Buy** (Buy Cart refuses partner rings). (2) The Maple Administrator prompt asks for the partner's name: type it. (3) Both get the ring in the Equip inventory, the partner with a popup; NX goes down once. (4) Both equip it and **change channel once**, come back together: the friendship ring effect shows on both screens (and on a third client if you have one). (5) Esc on the prompt, a wrong name and an offline name: popup, nothing charged. (6) Buy the same ring with the same partner again: refused. `!qa do rings` lists both characters' ring records | `RING`: `PROMPT`, `REFUSED` (code and reason), `CANCELLED`, `NOTHING WAITING`, `BOUGHT` (both ring row ids and partner ids); `EQUIP` lines when either equips the ring. `before:` lines with both characters' ring records at stage |
| `pr-2` `<partner> [ring id]` (1.5.0, real client, two players) | As pr-1 with a **couple** ring (default Crush Ring 1112001, 4800 NX; any of 1112001-1112003, 1112005-1112007, 1112012). Use two characters of the **same** gender at least once: there is no gender check (owner decision) | As pr-1 |
| `rm-1` (1.5.0 bot drama) | Turns `@romance on` for you (put back at stop) and stages a bot with a crush on you next to you. Stand near it: it flirts and asks you out - answer yes/no in map chat (`!qa do ask` asks now; or ask it: "<name> wanna go out with me?"). `!qa do date` stages a partner at once. Dating: change map (it follows, every time in a QA run), sit on a couple chair (it sits with you), `!qa do whisper`, `!qa do jealous` (a rival of yours shows up), `!qa do off` (romance off: no more flirting). The romance step runs every 30 s | `FLIRT`, `ASK OUT` (waiting for yes/no), `DATING`, `REJECTED`, `FOLLOW`, `CHAIR`, `WHISPER` (or "held: you are busy"), `JEALOUS`, `SHOW UP`, affinity `EVENT romance`, bot chat |
| `rm-2 [same\|mixed]` (1.5.0; gender 1.6.0) | Needs level 50 (the stock proposal rule). Turns `@romance on`, stages a partner (lv 60; `same` = your gender, `mixed` = the other, nothing = whichever comes; the stage line names its gender) and gives you a Moonstone engagement ring box. Use the box on the bot (stock window): it says yes in a few seconds. Or `!qa do propose`: it proposes, accept in the window. `!qa do wedding` warps you to Amoria and starts the ceremony (say "i do"). `!qa do divorce` (ring in the equip inventory). Runs 20 minutes | `PROPOSAL` (declined, with why) / `ENGAGED` (marriage id, token, ring), `BOT PROPOSAL`, `WEDDING begins` (friend bots, new guests), `I DO`, `MARRIED` (rings pair ids, marriage id), `WEDDING ends`, `SPOUSE CHAT`, `DIVORCE` |
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
| `cv-3` `[rival\|nemesis\|friend]` | A training bot that counts you as its rival | "sorry for ksing u" -> `makeup: YES` and `EVENT apology`; "ur trash noob" once -> a brush-off only (no `EVENT`, no note, no angry face), the same line again within 10 minutes -> `EVENT rude_chat` and the "was rude to me" note (death wishes, slurs and threats count from the first line); "ur cute" -> a flirt back only with `@romance on` and a bot that may flirt |
| `cv-4` | Four bots, one per voice: lowercase, proper typer, xX leet kid, Korean-flavoured (`PERSONA` lines). `!qa do persona` | Say the same line to each: casing, abbreviations and laughs differ, a catchphrase now and then. `TIMING`: 1-6 s by length, sometimes split, late (15-60 s) or "brb" first; never faster than a person types. Auto-QA `t-cv-12` also uses these four bots: "ur trash, uninstall" to three of them within 10 minutes -> `AFFINITY RUDE-SPREAD <you> heard by N bots` (every bot on the map cools a little and may tease you about it for an hour; nothing with `@botdrama off`) |
| `cv-5` | Warps you to Henesys, stages two bots and starts a thread now (LFG). `!qa do thread [beef\|gossip\|lfg\|trade\|training]` | `THREAD` / `THREAD LINE` (llm or bank). Within 45 s of the last line, say something without a name that answers it (auto-QA says "{thread}? i can come too", the subject the staging hands over as `VAR thread=Ludi PQ`; a thread's subject counts as its words for routing): `JOIN`, and the last speaker answers you with the thread in mind. `ROUTE CANDIDATES` shows every candidate and the step that chose (`3-reply-thread` or `3-thread-recent`; ticket 59 section 5a) |
| `cv-6` | A friend bot. `!qa do last` | Talk, then `!rate good` / `!rate bad too formal "certainly"`: `RATE` with what changed (a bank line's weight, or the tune file's examples and don't-say list). `!rate last` shows the line and its source |
| `cv-7` | Bank mode ON for the whole server while it runs (`-Dsolomapling.convo.forceBank`), and a friend bot. `!qa do llm` / `!qa do bank` | Every reply is `SAID ... bank#id` or scripted, in the bot's voice, not repeating; flip to the LLM and back to compare. Stop turns bank mode off |
| `cv-8` `[question...]` | A stranger bot next to you asks the map a scripted question (default "anyone from the east coast?"), like an ambient pack line. `!qa do ask [question]` asks again | Answer within 60 s WITHOUT its name ("im from the east"): `ROUTE` shows it went to the asker, `PROMPT` the exact prompt (system \|\| history with its question as its own turn \|\| the map transcript, its lines marked `(you)`, then `You just said: ... answered: ...`), and `SAID` follows up on its own question |
| `cv-9` `[line...]` | A friend bot next to you. `!qa do hold` says a line to it, unpins it and starts the wander a town wanderer's `ensureRoaming` starts (the walk that used to carry a chatting bot off). `!qa do sample` logs the distance again | `HOLD <bot>: <px> px from you, <s> s of hold left, with you\|WANDERED OFF (150 px)`. Every line must read "with you" for the 45 s after the last exchange: the conversation hold keeps it there (a third character does not push it away, personal space keeps the pair together). Auto-QA runs it as `t-cv-8` (five samples 5 s apart) |
| `cv-10` | A friend bot in a party with you (ticket 85). Type in PARTY chat, or `!qa do say <line>` sends one for you | `CONVO PARTY CHAT <you>: "<line>" -> <bot> answers [1-name\|2-reply\|3-closest\|3-any]` then `CONVO PARTY SAY <bot>: <reply>` (the reply shows in party chat). A line naming the bot, or a reply to its last line, always gets an answer; a question or "guys" nearly always; plain chatter sometimes. One bot answers; at most 8 bot lines a minute. Auto-QA runs it as `t-cv-10` |

**Hanging around:** a bot in a conversation is held in place for 45 s after the last exchange (`BotConversation.HOLD_MS`, from the player's line, its reply, the typing time and a player who names the bot). Every ambient walk skips while `BotConversation.isHoldingChat` is true: the town wander (`BotWanderSystem`: an in-flight stroll is stopped and the dwell runs to the end of the hold), loiter (`TownLoiter`), fidget (`GCFidget`), `SocialBot`'s relocate, `TownWandererBot`/`HenesysBot` roaming, the training bot's town dwells, and personal space (`PersonalSpace.keepNow`: a partner keeps the pair 18-27 px apart, a third character no longer displaces the bot). Grinding combat is not held. It yields to a real reason to go: a shutdown quiesce, the logout curve, an event instance, or a drama scene (`heldByChat`).

**`!rate`** (GM 1+, every server): `!rate good|bad [note]` rates the last line a bot said to you (10 min; a thread line
on your map: 2 min), `!rate last`, `!rate reload` (re-reads `data/convo/tune.json`), `!rate score [yyyy-mm-dd]` writes
`data/convo/quality/<day>.json` and `latest.json` now.

## dj-1: party bots in the Mu Lung Dojo (1.5.5)

| Test | What it stages | What to look for |
|---|---|---|
| `dj-1 [bots]` | Warps you (level 25+, no party or leading one) to the dojo hall (925020001) and puts 1-5 follower bots (default 2) of your level in your party, following you. They leave the party and the world at stop. Runs 30 minutes. `!qa do check`: each bot's map and whether it is following or fighting | `FOLLOWER FIGHT <bot> walks to <mob>` / `hits <mob> for <n>` on each floor |

The report: "Mu Lung Dojo - bots don't register enemies on party climb so they just watch". A follower only follows;
on other maps "train here" makes it a grinder, but a dojo floor has no Map.wz mobs (its monster comes from the map
script), so the grinding code calls it mobless. Since 1.5.5 a follower on a dojo floor with its leader fights the
floor's monster itself (`FollowerFight`) and follows again when the floor is clear or the leader goes up. Talk to So
Gong, pick the party challenge, and watch the bots swing on floor 1, then on the next floor after the portal.

## hc-1: a staged Bishop follower heals you, for the b140 Heal-column script (1.6.2)

| Test | What it stages | What to look for |
|---|---|---|
| `hc-1 [level]` | Warps you to the Henesys hunting ground (104040000, a map with mobs), spawns one Bishop (level 120 unless given, 1-200) next to you as a follower, and puts it in your party - the spawn `!bot trainhere bishop 120 1`, the follow and the party join a script cannot make without chat waits and an invite window. Refused when your party is full (6), the bot does not spawn, or the server runs without `-Dslums.bots.partysupport=true` (TEST and 1.6.2.conf set it). The feed gets one `STAGING` line naming it. `!qa stop` takes the bot out of the party and the world and disbands a party the run made. Runs 15 minutes | `STAGE FOLLOWER cid=<bot> job=BISHOP lv=<n> party=<id> gm=<you> id=<your id> map=104040000`; then `!hurt <you> 40` and the Bishop's `PARTY HEAL <bot> -> <you> ... sent SHOW_FOREIGN_EFFECT/1>map`, with the Heal column over the bot and your HP bar rising |

b140: a bot Bishop's party Heal must show the Heal column on a real client (1.6.2 sends the effect with the column's
art). `qa-scripts/b140-bot-heal-column-staged.yaml` drives it: `!qa run hc-1`, waits for `MAP_CHANGE` and the
`STAGING` line, hurts you, films from the hurt (10 fps for 5 s) and once more on the server's heal line, and checks
the `SHOW_FOREIGN_EFFECT` packet and the rising HP samples. `qa-scripts/b140-bot-heal-column.yaml` is the by-hand
version of the same scene (spawn, chat waits, invite window), kept for the day the client driver has chat anchors.
Scenario class: `soloMapling.qa.HealColumnScenarios`.

## ex-1: party bots join your boss expedition (1.6.0)

| Test | What it stages | What to look for |
|---|---|---|
| `ex-1 [bots]` | Makes you (level 50+, no party or leading one) the captain of a Zakum expedition on the map you stand on (no NPC, no warp), with 1-5 follower bots (default 5) in your party and one bot outside it. The expedition ends and the bots leave at stop. Runs 3 minutes | `PQ EXPED BOT JOIN <bot> -> ZAKUM` per party bot, `EX1 RESULT members=<n> min=<m> joined=<k>/<k> outsider=out again=0`, `EX1 PASS` |

Owner 2026-10-26: party bots auto-join boss expeditions. `ExpeditionBots.autoJoin` runs when the captain makes the
expedition (`cm.createExpedition`), on every recruiter step he takes (`cm.getExpedition`, so "Start the fight!" counts
them against the minimum) and at the start. A bot joins when it is his (his companion, his `@botme` alt, or a party bot
nobody leads while he is the party's one real player), stands on the recruiting map, fits the level band and the
recruiter's member quest, and has a boss-log entry left; never past the maximum. Skips log `EXPED BOT SKIP <bot> ->
<type>: owner|map|level|quest|quota|busy|full`. In game: party with your bots, talk to Adobis, make the expedition,
and the yellow lines say `[Expedition] <bot> joined with you.`; inside, the followers fight next to you.

## nx-1: buy a 10k NX code for 50m (1.6.0)

| Test | What it stages | What to look for |
|---|---|---|
| `nx-1 [buyer]` | Warps you to the FM entrance, spawns an NX merchant bot next to the buyer (default you; a GM cannot trade players, so name a non-GM there) that keeps selling for the run, and tops the buyer up to 50m (the unspent top-up is taken back at stop). `!qa do wallet` prints the buyer's mesos and NX credit. Runs 10 minutes | `TRADE NXCODE minted for <buyer>, note saved`, then `TRADE NXCODE typed in messenger for <buyer>` or `TRADE NXCODE messenger not accepted by <buyer>: note shown`; a failed store logs `TRADE NXCODE mint failed ... credited 10000 NX directly` |

The path: trade the bot, put 50,000,000 in, it locks, press Trade (the mesos move; the bot's 100 NX card filler comes
with it). The bot stores a code worth 10,000 NX credit (`nxcode` + `nxcode_items` type 4, one transaction), saves a copy
in the buyer's notes, and invites them to Maple Messenger, where it types the code (XXXXX-XXXXX-XXXXX). Ignore the invite
for 30 s and the note window pops instead. Cash Shop > coupon: the code with or without dashes adds 10,000 NX credit
once; the same code again says it was used. Auto-QA t-nx-1 (messenger) and t-nx-2 (note) drive it with AutoQaTwo.

## mx-1: bots clear Kerning PQ stage 1 on their own (1.7.0, ticket 130)

| Test | What it stages | What to look for |
|---|---|---|
| `mx-1 [perfect]` | Needs `-Dslums.mechanics=true` (refused, and t-mx-1 SKIPs, without it). Spawns four level 30-35 bots next to Lakelis on Kerning City (103000000) of your channel, makes them a party (the first bot leads), starts the Kerning PQ as Lakelis does (same eligibility check; refused while another party is in KPQ on the channel) and runs the mechanics orchestrator on the instance. Stage 1 must clear within 15 minutes. `perfect`: no wrong tries. The bots leave the party (the PQ ends) and the world 20 s after the run, or at stop. Runs 18 minutes | `MX1 START kpq stage 1 with 4 bots`, `RUN <bot id> got answer <n>, will try [...] first`, `NPC <bot> -> 9020001 with <n> x 4001007: "..."` per hand-in (a wrong try gets Cloto's "not the right answer"), `DROP <bot> drops 1 x 4001008 for <leader>`, `RUN stage 1 cleared in <s> s`, `MX1 PASS cleared stage 1 in <s> s (limit 900 s)`; a script that fails for a bot logs `FAILED:` |

Ticket 130 phase 1. The answerers ask Cloto through our own `scripts/npc/9020001.js` (run for the bot with the bot as
the player), kill Ligators with the attacks FollowerFight already uses, collect exactly their answer in coupons (a
persona shows 0-2 wrong hand-ins on the way up: a mixed-up answer or one short), trade them for a pass and drop it by
the leader, who waits by Cloto and hands in one pass per other member. In game: `!mechanics kpq me` puts you in the
party as leader with three bots: pick up the passes they drop at your feet and talk to Cloto. Auto-QA: t-mx-1.

## mx-3 .. mx-6: stops, channels, a player-led party, the lobby yields (1.6.2, bot audit)

| Test | What it stages | What to look for |
|---|---|---|
| `mx-3` | Four bots start KPQ on your channel; the run is stopped 30 s in, as `!mechanics stop` does. Runs 3 minutes. Auto-QA t-mx-3 | `MX3 START kpq with 4 bots`, `RUN instance <name> ended with the run: N member(s) sent out`, `MX3 PASS stopped run left nobody inside` (instance disposed, nobody held or registered) |
| `mx-4` | Three bots start KPQ on your channel and three more on another channel at the same time (every channel's first KPQ is named Kerning0). 20 s in both must be running; then both are stopped. Runs 3 minutes. Auto-QA t-mx-4 | `MX4 START <name> w1 ch A and <name> w1 ch B`, `MX4 PASS two channels ran KPQ at once` |
| `mx-5` | Needs owned alts on (and the QA alts t-oa-1 makes). You (21+) lead a party of your two alts (raised to level 30 for the check, put back after) and one bot into KPQ; you do nothing. The alts must get Cloto questions like the bot; once the passes are in a bot must remind you (up to 6 minutes); then the run is stopped. Runs 9 minutes. Auto-QA t-mx-5 | `MX5 START kpq led by <you> with 3 driven (2 alts)`, `RUN <alt id> got answer <n>`, `RUN reminded the leader: @<you> ...`, `MX5 PASS alts driven, ..., nobody left inside after the stop` |
| `mx-6` | Bots yield (owner 2026-10-30). Uses the bot lobby run that holds your channel's KPQ, or forces one with three fresh bots (the lobby's own start: party, the leader's talk to Lakelis). Refused when a player's or staged run holds it. You (raised to level 30 if below 21, put back after) lead two fresh bots to Lakelis and pick "I want to participate". On that one talk the lobby run must end ("yielded to <you>'s party"), its instance go, and your party get a new KPQ instance; the lobby must keep off the channel. Then your PQ is ended and the spawned bots leave. Runs 3 minutes. Auto-QA t-mx-6 | `MX6 START lobby run <name> with N bots holds channel C's KPQ`, `[Mechanics] lobby yields ch C to <you>'s party`, `MX6 player instance started <name>`, `MX6 PASS the player's party took KPQ from the bot lobby run ...` |

## mx-2: bots play all of Kerning PQ on their own (1.6.1, KPQ automation, ticket 130)

| Test | What it stages | What to look for |
|---|---|---|
| `mx-2 [perfect]` | Needs `-Dslums.mechanics=true` (refused, and t-mx-2 SKIPs, without it). Four level 30-35 bots next to Lakelis on Kerning City (103000000) of your channel, a party (the first bot leads); the leader talks to Lakelis through his own script (`scripts/npc/9020000.js`, "I want to participate"). The bots play stages 1-5 (coupons; ropes, platforms and barrels: the leader opens each stage with Cloto and checks every formation; the bosses' passes), walk into each opened `next00` portal, take their prize from Cloto (who sends them to the bonus), hunt the bonus for 60-150 s and leave through Nella. The PQ must clear within 25 minutes (KPQ's timer is 30) and every bot must reach the bonus. Runs 31 minutes | `MX2 START kpq with 4 bots`, `RUN stage <n>: ...` role lines, `RUN <leader id> got answer <n>, will try [...] first` (combination stages), `NPC <bot> -> 9020001 (check <n>)` per check (a wrong formation shows the wrong effect), `RUN stage <n> cleared in <s> s`, `NPC <bot> -> 9020001 (claim the prize)`, `RUN <bot id> hunts the bonus for <s> s`, `NPC <bot> -> 9020002 (leave the bonus)`, `MX2 PASS cleared kpq in <s> s (limit 1500 s) ... 4 prizes taken`; a script that fails for a bot logs `FAILED:`, a shut portal `portal next00 on <map> is still shut` |

In game: `!mechanics kpq me` puts you in the party as leader with three bots for the whole PQ: stage 1 passes and stage 5
passes are dropped at your feet; on stages 2-4 talk to Cloto once to open the stage, the bots stand on the answer (a
bot says when it is ready) and you check it. With your owned alts in your party (summon them, go to Lakelis, start
KPQ), the alts play the stages the same way (one PQ logic for bots and alts); each alt gets one prize whether it
claims first or you do. **The leader's part is always yours** (1.6.2, bot audit K4): only the event leader can hand
in passes or check a formation, so alts and bots never finish a run you lead on their own; when it is your turn a bot
says so in party chat (20 s after, then every 40 s). If you die the bots stop and wait; reviving outside the PQ ends
the run, and an instance with only bots and alts left in it ends with it. `!mechanics kpq to3` stops a staged run after stage 3; `!mechanics kpq stage4` starts it on stage 4 (1.6.2: stages before it marked cleared through the event's state, no rewards, everyone walks through the opened portals; with `me` you go through the same gates, each portal's own script moving you). `hold` (1.6.2, with `stage2`-`stage4`, implies `perfect`): the bots take that stage's positions (ropes, platforms, barrels) and wait on them without checking, for screenshots; `!mechanics release` lets them clear it, `!mechanics stop` ends the run. Passes to a real leader (1.6.2): a bot walks up to you (60 px) and drops the pass at your feet; if you leave it lying 20 s and walk off, the bot picks its own pass back up and brings it again (`DROP` / `TAKE BACK` lines in BotLog). What to watch on a real client:
the ropes (do the bots hang still inside the rope's middle?), the barrels (5 px tall: do they stand on top?), and
the attacks on stage 1's Ligators and stage 5's bosses (the client drops remote attacks with a pose it does not
expect).

## xc-1: the NX exchange in game (1.6.1, ticket 113)

| Test | What it stages | What to look for |
|---|---|---|
| `xc-1` | Needs `-Dslums.exchange=true` and 100 NX credit on your account. Funds a test regular (regular 9) from your own mesos (nothing minted) and rests its bid for 100 NX just above the book's best bid. Stop cancels it and pays its whole house back to you. `!qa do check` logs `XC1 CHECK nx a -> b, meso c -> d, open orders n` | `@nx sell 100 <bid>` + `@nx confirm`: `Order N placed ... status FILLED`, then `NX exchange: <mesos> arrived.` at once (no logout), 100 NX gone. Wait 10 s, `@nx buy 100 1000` + `@nx confirm`: it rests and holds 101 mesos; `@nx cancel N` gives them back. After stop: the same NX, mesos down by exactly the two 1% fees |

Auto-QA t-xc-1 runs the same with AutoQaOne through the real `@nx` command (an NX top-up for the sale is taken back).

## xc-2: the LEAF ticker in game (1.6.1, ticket 113 tickers)

| Test | What it stages | What to look for |
|---|---|---|
| `xc-2` | Needs `-Dslums.exchange=true`, the LEAF ticker open and 20 tradeable Maple Leaves (`!item 4001126 20`). Funds the test regular (regular 9) from your mesos and rests its bid for 10 leaves one meso above the LEAF book's best bid (at least 1,000 each). Stop takes it out: its 10 bought leaves and its mesos come back. `!qa do check` logs `XC2 CHECK leaves a -> b, meso c -> d, open orders n` | `@nx leaf sell 10 <bid>` + `@nx confirm`: 10 leaves leave your bags, `Order N placed ... status FILLED`, the mesos less 1% arrive at once. Wait 10 s, `@nx leaf sell 10 1000000000` + confirm: it rests and 10 more leaves leave; `@nx cancel N` puts them straight back. After stop: the same leaves, mesos down by exactly the two 1% fees |

Auto-QA t-xc-2 runs the same with AutoQaOne through the real `@nx leaf` command (leaves topped up for the check are taken back).

## xc-3: market volatility (1.6.1, ticket 113 "Volatility")

| Test | What it stages | What to look for |
|---|---|---|
| `xc-3` | Needs `-Dslums.exchange=true`, `-Dslums.exchange.traders=true`, `-Dslums.market.events=true` and a trader bot online. Starts a secret 2-minute Leaf Rush and ends it; an online trader bot bids 1 leaf from its own mesos below the LEAF best bid, then cancels. Logs `XC3 PASS|FAIL ...`; `!qa do check` runs it again | the leaf chance reads 500 -> 1,500 -> 500 per million; the bot's order is on the book as the bot's, its mesos go down by the bid plus 1% and come back exactly on the cancel. Then `!market event rush 10`: a blue `[Cody] Leaf Rush!` notice (and `... is over.` after); `!market traders` lists the traders and their last orders; `!market act` makes one act; `!market rumour` has a bot near you say a rumour |

Auto-QA t-xc-3 runs the same check (no player needed); t-xc-4 checks the guild-war news path the same way. The request named it t-mk-1, but mk-1 / t-mk-1 already belong
to the 1.6.0 Market export.

## pq-2: OPQ bots (1.5.2)

| Test | What it stages | What to look for |
|---|---|---|
| `pq-2` | Warps you to the Orbis PQ lobby (200080101) and spawns 6 live OPQ bots (the real bot brain: they accept an invite and run the PQ) around you. They leave at stop. Runs 30 minutes | `PQ <bot>: <state> -> <state> \| <reason>` for every transition (lobby recruit, party, stage 1, tower, stage 2, exit) and `PQ <bot> recruit: <line>` for lobby chat |

The friend's report, "PQ bots are not great", covered four things this run is meant to show:

- **Lobby spam.** Each bot used to speak every 12 s regardless of how many bots were there, so 10-15 bots made
  50-75 lines a minute. The lobby is now capped as a whole (`-Dsolomapling.opq.lobbyRecruitPerMinute`, default 3 a
  minute, `OPQLobbyChat`) and each bot waits 45-90 s between its own lines. Count the `recruit:` lines: no more than
  the cap per minute, and none at all when no player is on the map.
- **Joining and doing nothing.** Invite a bot, then stand still. After 5 minutes in the lobby with the party formed
  and no stage 1 it says one line, leaves the party and goes back to recruiting (`OPQPartyDuty`). The same happens
  after 15 s when the leader's character is offline and after 90 s when the leader is on another map.
- **Idle bots in the stages.** Stage 1 used to count as done the moment the leader entered (the exit NPC is part of
  the map), so bots jumped to the top platform. They now stay with you until you stand on the top platform, then
  follow you to the tower and stage 2.
- **Leaving mid-run.** Stage 2 used to wait for every registered bot - the lobby's recruiters included - and gave up
  after two minutes. It now finishes when the bots in your instance are done.

## lk-1: player likeness (1.6.0)

| Test | What it stages | What to look for |
|---|---|---|
| `lk-1` | Nothing. Logs a status every 30 s for 20 minutes. `!qa do camp` runs the AFK-camper pass now; `!qa do sample` styles five fixed lines for four bots | `LIKENESS afkCampers=on campers=N last pass ...: ch1 6/8 (bots 320, free in town 41) ...` (campers per channel against the target), `LIKENESS CAMP START/END <bot> ...` (who parks where, for how long, on a chair, and why it ended), `LIKENESS WATCHED WALK <bot> <site>` (a snap or warp turned into a walk because a player was on the map), `LIKENESS WATCHED SNAP` (one that still snapped: should not appear), `LIKENESS MESSY <bot>: "before" -> "after"` (one sample per bot per 20 s) |

Switches, all on by default: `-Dsolomapling.afkCampers=false`, `-Dsolomapling.walkWhenWatched=false`,
`-Dsolomapling.messyChat=false`. Auto-QA `t-lk-1` (HALF) runs `lk-1`, `camp` and `sample` and checks the status
lines and that item names and prices survive the chat pass; how it looks still needs a client.

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

### fy-1: Family (1.6.1, ticket 53)

`!qa run fy-1` (needs `USE_FAMILY_SYSTEM`, the GM in no family): an online bot accepts being the GM's junior, its
level-up gives the GM rep, and the GM gets 20,000 rep to click every entitlement once in the Family window.
`!qa do check` prints rep and the Family buffs; stop dissolves the family (relog to clear the window). Auto-QA:
t-fy-1 (a bot junior, rep, two perks), t-fy-2 (every buff perk through the Family window packet, party to the second
pretend player, Bonding refused below its minimum then given with the minimum lowered to 3 for the check, real kills
paying the buffed EXP, the family_buffs row, a bot junior's real kills stopping at the 300 a day cap, the daily reset in
memory for the QA family), t-fy-3 (Reunion to a regular junior on the pretend player's channel, Summon brings it; a
pretend player can't change channel, so cross-channel is unit-tested only) and t-fy-4 (a regular's offer whisper, then
Add Junior from another map).

### ti-1: Maple Island opt-in (1.6.1, ticket 29)

`!qa run ti-1` opens the "Start on Maple Island?" question for the GM (the real one only asks a brand-new Explorer).
Yes lands in Mushroom Town (map 10000); `!qa do ask` asks again, `!qa do back` returns to Base of Roots. Auto-QA: t-ti-1.

### rb-1: Bot rebirth (1.6.1, ticket 30)

`!qa run rb-1` (needs `BOT_REBIRTH_ENABLED` and `USE_REBIRTH_SYSTEM`): an online trainer bot (lv 30-199, a build
job, no party, no rebirths) is raised to 200 on its build, then walks its earned path with the pace forced: Zakum and
Horntail run off screen on the players' rules (their daily clear limits, its real damage against their real HP inside
the time limit; each outcome is real, so some decisions do not clear), the key crafted, the trip granted, the rebirth on
rebirth.js's rules. The answer lists what changed and what must not have (only level-1 gear worn and every old piece
kept, mesos, cash items, past skills). `!qa do check` prints the bot; stop puts it back exactly as it was, its boss-log
rows included. Auto-QA: t-rb-1.

### rb-2: Bot rebirth key from a real boss kill (1.6.1, ticket 30 promise audit)

`!qa run rb-2` (as rb-1, and `-Dslums.botRebirth.liveRuns` not false): the same kind of bot, raised to 200 and staged at
rebirth 1 holding Pink Bean's item (bots cannot run Pink Bean's statue stage on their own), goes into a real Von Leon
run: a real expedition with up to five free strong regulars on its channel, the event started as the recruiter does,
Von Leon summoned as his NPC does (at 1% of his HP so it ends in minutes; the run's first note gives the real fight's
expected length), fought with the bot attack driver through the map's boss rules. `!warpto` the bot to watch the
recruit map (211070000) before they go in. `!qa do check` shows the run; once it is DONE it crafts the 2nd key and is
reborn. Stop puts the bot back (items, rebirths, the run's Von Leon clears in bosslog_daily). Auto-QA: t-rb-2.

### ac-1: Achievements and medals (1.6.1, ticket 93)

`!qa run ac-1` (needs `-Dslums.achievements=true`): clears the GM's Mano and Fishing Hobby goals and medals, then
`!qa do mano` (a credited Mano kill) and `!qa do fish` (ten kept catches) earn them through the real event path, each
handing over one zero-stat medal with a yellow notice; repeating gives nothing. `!qa do list` prints what `@achievements`
shows. To see the full-bag notice fill the Equip tab first, then free a slot and type `@achievements`. Stop clears both
goals and takes the medals back. Auto-QA: t-ac-1, and t-ac-2 (below) for a clear through the real event path.

### Auto-QA probes with no `!qa` scenario (1.6.1)

- **t-iv-1 (`@invite`, ticket 105 part 2):** calls the command's player path for a made-up account (id -900101, never a
  real account) with level 29, then level 30 three times. Wants: level 29 refused, two codes, the third refused (2 a
  week), `@invite list` showing two unused codes, one code signing an account up through the site auth API (skipped
  when the API is not listening) and reading used, the same code refused a second time. Removes its invite rows and
  the sign-up account in a `finally`; touches no real player's codes.
- **t-iv-2 (site invite page, ticket 105):** the `/invite/create`, `/invite/list` and `/invite/cancel` routes for
  made-up accounts (ids -900201 player level 40, -900202 level 29, -900203 banned, -900204 GM level 3; only the QA
  path accepts negative ids, the real routes refuse them) against the real `invite_codes` table. Wants: the running
  API answers REFUSED for an unknown account (proves the routes are deployed; skipped when it is not listening); a code
  from `@invite` plus one from the site, then WEEKLY_LIMIT; level 29 NOT_ELIGIBLE, banned REFUSED; the player list
  shows exactly their 2 codes with no owner/note and `usedBy` a boolean; the player can't cancel the GM's code
  (NOT_FOUND), can cancel their own, which still counts; the GM's 3-day code has the right expiry and shows open with
  owner and note in the GM list; the GM cancels the player's in-game code. Removes every code those ids made, before
  and after (`finally`); never touches a real account's codes.
- **t-sn-1 (Slums-chan, NPC 9990000, migration 042):** read-only. Her name is in String.wz, one of her stands on Base of
  Roots (993910000) and one in Henesys (100000000) as loaded from `plife`, and the first-login guide and `@settings`
  use her as the NPC. (`t-sc-1` is the 1.5.0 gambler-bot check, so her id is `t-sn-1`.)

- **t-ac-2 (achievements, a real PQ clear; 1.6.1 promise audit):** on the pretend player's channel, a real
  `KerningPQ` instance named as the script names them (`Kerning9xx`), the player registered without the entry warp, and
  `EventInstanceManager.clearPQ` (the script's `clearPQ`, then `setEventCleared` and the achievement hook). Wants
  `pq-kerning` earned and its medal handed over within 15 s. Resets the goal, takes the medal back and disposes the
  instance in a `finally`. (t-ac-1 feeds events straight to the service, so it could not see that clears were keyed on
  the instance name.) Side effects: a few quest points for the QA character and a PQ_CLEAR on the event bus.
- **t-pg-1 (Lazy Penguins, migrations 046/048/049/051):** read-only apart from opening each NPC's chat. Each of the 15
  penguins stands once on its town map on the player's channel, is named "Lazy Penguin" (host WZ step), and a click
  shows its line; none is left in Mushroom Shrine (800000000). The town table is `Probes160.PENGUINS`, kept equal to
  `tools/data-align/lazy-penguins/penguins.tsv` by a test.

- **t-bs-2 (bot shops never list boss-only drops, 1.6.1):** read-only. Walks every Free Market room (910000001-22) on
  every channel and checks each item in every bot shop (generated hired merchants, bots' own hired merchants, bots'
  player shops; real players' shops are skipped) with `SaleRules.whyNotListOwn` (a bot may list its own godly gear; boss-only, Chaos/White, cash and untradeable stay refused); FAILs naming shop owner, channel and map,
  item id and the rule that refuses it. Then forces the equip generator 40 times for each tier (S, A, B, C) and class
  (the live desirable list holds the Zakum Helmet 1002357) and FAILs if any refused item comes out (also godly gear while `botShops.generatedStockExcludesGodly` is on), then the same for generated scrolls and dark scrolls, and for the generated potion, throwing-star, ETC, mastery-book and chair lines (1.6.1: TEST 10-29 found 141 such listings, e.g. Gelt Chocolate 2022121, boss-only mastery books). It also asserts SaleRules still refuses 1002357, 1002390, 1002430 (Zakum Helmets), 2049100 (Chaos) and 2340000 (White). PASS says how many
  shops, listed items and generated items were checked. (Commit d8f108325: stock used to skip SaleRules.)

- **t-br-1 (`@bug` with context, ticket 131):** logs two WARN lines naming the QA character (one says "password"), then
  the pretend player types `@bug autoqa t-br-1 <marker> ...`. Wants the `Thanks! Bug #B-.. sent.` reply, the line in
  the reports file (`-Dsolomapling.reportsFile`, TEST `/var/lib/slums-report-test/reports.jsonl`) with source
  `ingame`, the text and `context.file`, and `context/<ref>.json` under 64 KB with versions, character, trail,
  surroundings, quests, buffs, the first WARN line and not the password one. Removes exactly that line (under the
  file lock) and its context file in a `finally`, and resets the QA account's report limit.

### Checks two tickets promised (1.6.2, ticket 164 b224)

Probes in `Probes164`, no `!qa` scenario; each stages fresh throwaway bots next to the pretend player and despawns
them (and undoes any LOD pin) itself. None of them is on the release allow-list.

- **t-gr-1 (ticket 156, chat grounding without the chat model):** a fresh level-35 training bot. Its state-of-mind
  snapshot (`BotConversation.qaSnapshot`, the same `BotStateOfMind.capture` every reply uses) must hold its real name,
  level and map and the pretend player's name and level (not a bot). The real checker (`StateOfMind.judge`) must pass
  "im lvl <its level> rn" and "hey whats up", and reject "im lvl <level+37> rn" (LEVEL), "im grinding in <a far town>
  rn" (LOCATION) and, when they never met, "hey again! good to see u" (FAMILIAR); the checked/rejected counters must
  grow by as much. Then the real delivery path (`BotConversation.qaGroundScripted` -> `groundForDelivery`) must swap
  an untrue scripted answer for a safe line that passes, and count the fallback. SKIPs only when
  `-Dsolomapling.convo.grounding=false`.
- **t-gr-2 (ticket 156 through the chat model):** a fresh level-35 social bot is asked "what level are you? just the
  number pls" through the real chat path (`BotConversation.talk`); its reply (heard in map chat, up to 90 s) must state
  its real level and pass the checker. Then its next model reply is replaced once by an untrue draft
  (`BotConversation.qaNextDraft`: "lol im lvl <level+40> grinding in leafre, good to see u again") and the player says
  "hows ur day going": the draft must reach the model path, be rejected, retried with the model, and the retry pass or
  fall back (StateOfMind counters), and the untrue line is never said. SKIPs when the chat model is not live
  (`BotBrains`), which fails a release run: live answers with the model.
- **t-nv-2 (ticket 157 item 3, "no trap maps"):** `NavTrapQa` searches the bots' world graph from Lith Harbor for
  one-way-in traps: maps a bot can reach with no route back. It steps the way the planner does for some bot
  (`GCWorldGraph.waysOn`: walkable portals, curated scripted warps, BotTransit rides, the tours' way home to whichever
  town was saved, and the Hyper Teleport Rock's jump home out of its `out_of` ranges; not the rock's jump in). FAILs on
  any trap not in the committed baseline `src/main/resources/soloMapling/nav-trap-maps.txt` (the accepted real traps,
  one reason each), or when the graph did not load; lists baseline maps that now have a way back. b230 (10-30) cut the
  baseline from 485 to 0: 469 were intended one-way rides (Grandis and Arcane River, left by the rock; Malaysia, by the
  tour home), 15 lacked a scripted way back the graph now has (Rien, Korean Folk Town's Black Mountain, Leafre's Cave of
  Life) and one was a portal to a map with no Map.wz image (now no edge). `NavTrapQaTest` checks the baseline against
  the WZ data where a checkout has it; after a deliberate data change regenerate it with
  `mvn -o test -Dtest=NavTrapQaTest -Dwz-path=<wz> -DnavTrap.write=<baseline file>` and give every new map a reason.
- **t-nv-3 (ticket 157's debug tools answer):** a fresh bot on HHG1 (pinned to full simulation for the check). The
  bot inspect API (`-Dslums.inspect`, port and secret file) must answer 403 to a request with no secret and one with a
  wrong one (the check never reads the secret), its `/bots/{cid}` view must be the bot (name, level, map), the pretend
  player is never served, `/positions` lists the bot; kill calibration (`-Dslums.killcalib`) reports version 1,
  report-only, with totals and buckets, and `!killcalib`'s header says on; the nav path trace (`-Dslums.navTrace`)
  holds a ring of the bot's own ticks on its map (`NavTrace.qaRing`, which spends none of the hourly copy budget). A
  tool that is off SKIPs the check naming its flag.

### bt-1: bots talk to bots (1.6.1, ticket 114)

`!qa run bt-1` (needs `-Dslums.bottalk=true`): five fresh bots next to the GM, Ann and Bob friends, Cat, Dan and Eve a
crew, Eve Ann's rival. `!qa do pair`: Ann greets Bob, small talk, asks to party; Bob answers from his goals, mood and
bond, and a yes is a real party. `!qa do rival`: Ann asks Eve (curt or rude). `!qa do group`: the three take turns
and each answers the ask itself. Each step prints the plan (every line with its intent, the decisions and why) and a
`BT1 PASS / FAIL <step>` verdict lands in the QA log once the lines are out. `!qa do check`: the counters (talks,
lines by source, acts, classified bot lines = 0, tick CPU). Auto-QA: t-bt-1 (pair, group, classifier count, tick
cost, and the tick starting talks by itself after 5 min with 20+ bots online; since the 1.6.1 audit Bob's answer is a
forced yes whenever the game allows one, and the check fails unless Ann and Bob end up in one real party) and t-bt-2
(friend crews: the leader starts its hang-out through its own life, the town becomes the followers' next hang-out,
and since 161-saydo every member, leader and followers, must stand in the town within 240 s) and t-bt-3 (say-do for
bot talk: two fresh training bots, a forced come-along yes, both must reach the town within 240 s; and of the yeses
bots gave each other since boot, `botTalk.promises` in bot-life.json, at least 80% carried out once 10 are judged).

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
| `!autoqa run <check> [--fast] [--failed] [release]` | Runs checks in the background. `<check>` can be a page id (`t-af-1`), a short id (`af-1`), a group (`af`), a comma list (`bg-1,vg-1,ch-1`), `smoke` (that quick set) or `all`. Each result is posted to you as it finishes. The `!qa` menu's Auto-QA page runs these for you. `!autoqa run all release` is a release run (below) |
| `!autoqa status` | The runner's state, check and step |
| `!autoqa stop` | Stops after the current step. Cleanup still runs |
| `!autoqa report` | Today's totals (carried passes counted as old, not pass), the last health sweep, grounding (server and sent), the chat-quality headline, say-do since boot, the last run's summary line (what it skipped and why, a release run's verdict), then every FAIL and SKIP with its reason and the carried (old) rows |
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
| (h) `overlap` | rc6 personal space: two grounded, idle characters (bots, synthetic and real players) on one map instance with \|dx\| < 12 px and \|dy\| < 5 px: their name tags overlap. Ropes, ladders, jumps, coarse unwatched moves and walkers (a recorded-path walk or a walking stance) are left out, and so are deliberate pairs (same pair key: a player and the bot it talks with, a couple, a drama scene, a cluster talk, ambient chatter, shop keepers). Since 1.6.0 (owner, 2026-10-26) only map instances with a real player (a hidden GM counts) count toward the status; `allMapsPairs`, `allMapsWorst` and the summary keep the all-maps count as information. Lists the counted pairs, closest first, and `worstMaps` (pairs per map instance) | WARN at any, FAIL at 5+ (counted pairs) |
| (d) `channelSplit` | Bots per channel against `BOT_CHANNEL_WEIGHTS` (`BotChannels.shares`): more than 15 points off, a channel weighted 0 that has bots, or a weighted channel with none. Not judged under 40 bots | WARN |
| (e) `invalidMaps` | A bot on map 999999999 or a GM, jail or developer map, standing in a map other than its character's map id, or outside the map's foothold bounds by 200+ px | FAIL |
| (f) `errors` | Lines in `logs/cosmic-log.log` containing ` ERROR ` (or an uncaught exception) since the last sweep, against the median of the last 12 windows: 20+ and over 3x the median is a spike | FAIL |
| (g) `mapCap` | 1.5.0: a map instance (one map on one channel) holding more bots than its per-map bot cap (`BOT_MAP_CAP`, default 50, or its `BOT_MAP_CAP_OVERRIDES` entry; `soloMapling.ArtificialPlayer.BotMapCap`). Reads each instance's bot counter, not the bot sample; lists the instances over their cap, worst first, plus how many are full and the busiest count. The cap only stops additions and some arrivals are exempt (set pieces, followers, story scenes), so this is a warning to look at with `!botmapcap`, not a failure. In the sweep summary and the `AUTOQA HEALTH` QaLog line as `mapCap WARN: n map instance(s) over the cap, worst map ...` | WARN |
| (h) `chatModel` | 1.6.1: each chat-model Decide endpoint (`intent+con@...:8913`, `tone@...:8910`) since the last sweep, from `ChatStats.endpointTotals()`. An endpoint with 3+ calls and none answered is down: bots read chat by `ChatTriage` rules alone (the .17 units do not start at boot). Under half answered (timeouts) is slow. Only judges endpoints that got calls; a quiet server shows `no chat-model calls yet`. Runs wherever auto-QA runs (TEST), not on live | FAIL down, WARN slow |

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

### Bot watch: bots do what they decided (1.6.0)

1.6.1: wealth upkeep (a bot over its ceiling burns mesos at a town stop) is netted out of the watch's meso reading
(`BotWealth.upkeepPaid`), so it no longer counts as "spent mesos after a town visit" or hides a real sale.

`data/autoqa/bot-life.json` counts what the bots decide; the bot watch follows a few of them to see a decision turn
into action. `!botwatch start [bots] [minutes]` (GM 3, test server; default 40 bots for 30 min), `!botwatch status`,
`!botwatch stop`. Auto-QA `t-bw-1` (`!qa run bw-1`) watches 20 bots for 10 min.

- It picks online bots stratified by what they are doing now (the brain's active activity, else the one the
  training bot's phase stands for), rarest activity first, so a lone PARTY_QUEST bot is not crowded out by 1,500
  grinders. Bots without a brain (merchants, gacha, showcase) are not picked.
- Every 30 s per bot, from memory only: channel, map, x/y, activity and its target map, level, EXP, mesos, kills
  (counted from the attack driver while watched; off-screen grinders level by arithmetic, so EXP is the proxy),
  potions, ETC/EQUIP slots used, its last chat line, party, chair, whether a player watches the map. A 2-second
  position tick catches same-map jumps a walk cannot explain.
- Verdicts (`BotWatchJudge`): OK, MISMATCH (it said X but nothing X-like happened), IDLE (nothing at all changed for
  10 min, or 80% of a shorter watch, while not resting; a chair, a BREAK and a town stay off-screen are resting),
  TELEPORTY (a jump on a watched map), LOGGED_OUT (logged out mid-trip, mid-party or mid-channel-move).
- Output: `data/autoqa/botwatch-latest.json` and `data/autoqa/botwatch/botwatch-<UTC stamp>.json`: run meta,
  `summary` (one line), `totals`, `byActivity`, `check` (t-bw-1's bar) and `bots` (worst first) with `verdict`,
  `reason`, `segments` and the `track`. A `BOTWATCH` line goes to the server log and any `!qa` log on `AUTOQA`.

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
  `{bot}` that never appeared, a wait that ran out), or the check ran past its minute budget. The evidence lists the
  lines that matched or what was missing.
- **SKIP**: a manual check, a precondition refusal (no bot guilds, wrong channel, guild state ...), a check left out
  of the nightly run, or the run was stopped.

**Waits fail when they run out (1.6.2, ticket 164 b222).** A `waitFor`, `until`, `untilProbe` or
`acceptGuildInvite` that does not get what it waits for ends the check as FAIL with "timed out waiting for /x/ after
N s" (also an evidence line). Before, it left a note and an expectation that matched an earlier line or another
alternative passed the check without the step ever having happened (t-td-2 passed for a week on the staged quote while
the bot never answered the chat ask). The explicit forms for the windows that are allowed to stay empty:

- `quietFor(regex, s)`: the window must stay empty; FAILs the moment the line shows up.
- `optionalWait(regex, s, why)`: a branch (t-ev-2's natural-death window before the forced death); never fails, notes
  the outcome. `AutoQaCatalogTest` pins the checks allowed to use it.
- `waitForSince(mark, regex, s)`: a line that may have come before the previous step (a decision logged with the
  request, a death during the staging); `null` = since the check started.

The checks converted with the rule (each timed out in every TEST run of 10-23..30 while still passing): t-af-3 (the
wait now ends on what its expectation accepts: an act, a KS or the nemesis state), t-gu-8 (a held kick is a branch:
up to 15 s, else `!qa do vote`), t-td-2 (waits for the bot's chat answer to the ask and then to the 1k offer, up to 75 s each, with
`QUOTE asked again` and `QUOTE haggle 1000 -> COUNTER|FINAL`; 162-haggle), t-cb-4 (the 120 s fight soak that saw 0 MANNERS lines is gone; the step-on's YIELD/CROWDED is the
proof), t-bl-3 and t-ev-2 (`waitForSince`), t-cv-1/2/3/7 (60 s for a human-timed reply, was 12-15 s).

Cleanup always sends `!qa stop`. Guild state is set up by fixtures:

- "be guildless" leaves the guild, and disbands the runner's own test guild so nightly runs do not pile them up.
- "be in a player guild" makes `AutoQa<nnnnn>`.

Checks that leave a lasting change run only when asked by id, never nightly: t-gw-3 (the poached bot stays), t-cv-6
(a rating changes the bank weights), t-mr-1, t-rm-2 and t-rm-4 (engagements and marriages).

| Kind | Checks |
|---|---|
| Automated | af-1/2/3, rv-1..4, bb-1/2, bg-1, gu-1/2/3/4/5/6/8, gw-1..4, pb-1, cu-1/2, we-1, mr-1, rm-1..4, cv-1/2/3/5/6/7, ga-1/2, wc-1, bs-1, td-3, gf-1, pr-1, sk-1/2, sc-1/2, cb-1/3/4, sp-1, id-1, vg-1, ch-1; 1.6.0: oa-1, tw-1, hp-1, au-1, ds-1, np-4, ck-1, sc-3 (below) |
| Half (logic here, look by eye) | fh-2 (the chair and catch effects), cv-4 (the voices), cb-2 (swings on screen), pt-1 (pets following), bk-1 and at-1 (swing pacing on screen), td-1 and td-2 (the trade window: the haggle runs headless with `!qa do sim` / `quote`), ga-3 (the client's label for the last remote slot) |
| Manual | ga-4 (a real client's pick-up), rg-1/rg-2 (the Cash Shop purchase flow is not driven yet), mv-1 (needs the tester's own movement packets), jq-1, cl-1/2/3, cc-1, mr-2, la-1 |

Notes on what some checks prove:

- **vg-1** runs `ClientVersionGate.verdictFor` on a scratch config against the server's own `MIN_CLIENT_BUILD`
  (10600 on TEST and in 1.6.0): the build at the gate and one above are allowed, the gate minus one is refused with
  the update message. A gate of 0 (off) is a FAIL: 1.6.0's 4-byte HP/MP needs it. No restart, and the live gate is
  untouched. (Before 1.6.0 it hardcoded 10500.)
- **fh-2** (fishing flags on): the GM sits on the Fishing Chair at the waterside spawn of 120010000 (`FISHING REAL
  STARTED map=... area=true chair=3011000`), a catch lands in the bag (`REAL catches=1`), standing stops it, and bots
  fish too: `FISHING BOTS active=<now> started=<since boot> caught=<since boot>` must show a bot fishing now or a bot
  catch since boot. Half: the chair and catch effects need a real client.
- **ga-2** checks each new machine NPC with the foothold rule and keeps it 40+ px from a portal.
- **ch-1** reads `BotChannels.botsPerChannel()`: 5 channels, ch1-4 have bots, ch1 the busiest and ch5 the quietest, each within max(10, 25%) of its `BOT_CHANNEL_WEIGHTS` share.

### 1.6.0 checks for features that shipped without one (owner 2026-10-27)

Each is one probe in `soloMapling.autoqa.Probes160` (t-tw-1 two). A probe stages on the pretend players' own
accounts, judges and undoes its staging in a `finally` (the runner's cleanup only sends `!qa stop`). Commands go
through AutoQaOne's chat, so a FAIL carries the server's answers ("gm heard ..."). A SKIP's reason is the probe's own
line (`nothing could be judged here: SKIP OWNED_ALTS_ENABLED is off ...`). All run nightly. Run one:
`tools/ops/autoqa-run oa-1` (or `!autoqa run t-oa-1`); all eight: `tools/ops/autoqa-run oa-1,tw-1,hp-1,au-1,ds-1,np-4,ck-1,sc-3`.

| Check | Stages | PASS when | SKIP when |
|---|---|---|---|
| t-oa-1 companions | Makes `AutoQaAlt` and `AutoQaAltB` on AutoQaOne's account once (kept). `@botme`, `@botme AutoQaAlt`, again, 4 companion places reserved with ids that are never characters (released in `finally`), `@botme AutoQaAltB`, a marker amount of mesos on the alt, `@dismiss AutoQaAlt`; `@dismiss all` and leaving a party it made in `finally` | the list names the alt; it joins on the GM's map and in the GM's party; the repeat is refused ("already online"); the 6th place and `@botme AutoQaAltB` are refused ("up to five"); after `@dismiss` it is out of the world and the party and the database holds the marker mesos; 1.6.1 (full alts): on Henesys Hunting Ground I (104040000, `@botme here`; a Sword is given once if the alt holds none) a Blue Snail spawned on the alt damages it, a Blue Snail the owner hits is damaged by the alt, a staged party Snail Shell drop lands in the alt's own ETC bag, and with the owner idle (`OwnedAltGate.forceIdleForQa`) the gate reads OWNER_IDLE and the alt does not swing, then fights again once the owner acts | `OWNED_ALTS_ENABLED` or `OWNED_ALTS_SUMMON_ENABLED` off (live from 1.6.0.4: @botme rests) |
| t-tw-1 busy towns | nothing; two samples 300 s apart of `TownPulse.index()` and every town map's bot count per channel | fill (bots/target, capped per map) reaches 90 %, rises 2 points, or holds at 60 %+ (down at most 5); no town map instance over `BOT_TOWN_MAP_MAX`; ch1 has the most town bots; the boot's Henesys dressing spawned the `BOT_TOWN_DRESSING_SCALE` share of what it asked for (within one bot per cohort) | the brain off (`slums.botlife.enabled/execute`) or `BOT_TOWN_PULL` 0; the dressing part when no dressing spawned |
| t-hp-1 4-byte HP/MP | `!setmaxhp 50000 45000`, then `!setmaxhp 150000` on AutoQaOne; MaxHP/MaxMP/HP/MP restored in `finally` | a built STAT packet for 50,000/45,000 reads back as 4 bytes; the STAT packet AutoQaOne's client got carries MAXHP/MAXMP (with gear) over 32,767; 150,000 stops at 99,999 (base, shown and packet); values restored | never |
| t-au-1 site auth API | `!invite 1d`, then in-process on the running API (after its secret check; the secret is never read): sign-up `qaau<6 digits>`, sign-in, reset request, status, complete before approval, `!resetpw approve`, status, complete, sign-in new and old password, complete again; account, reset rows and invite deleted in `finally` | HTTP to 127.0.0.1:`slums.auth.port` without the secret gets 401; every step answers as the contract says (pending, NOT_APPROVED, approved, ok, WRONG_LOGIN for the old password, USED) | the API not listening (`-Dslums.auth.port` unset or failed to start; TEST: 8196) |
| t-ds-1 decide shadow | nothing on the server: a shadow with a fake model, in-process, writes to `data/autoqa/decide-shadow-selfcheck/` (removed after) | one row per line; with text a row has `text` and no `len`; with `text=false` it has `len` and neither `text` nor the words. Also reports the server's own shadow (on/off, counters, text) | never |
| t-np-4 custom NPC caps | talks to Coco (9000017) twice, Dalair (9000040) once, Maple TV (`mapleTV` -> scroll generator) twice with AutoQaOne's quest 32010 set to today:5 and today:4 (restored); Dalair's gate and cap on a scratch character never in the world or saved | Coco's number box max is 5, 6 is refused, 5 is taken; Dalair turns a level-70 character away with the level 160 line (no Maker, owner 2026-10-27); the gate passes only Hero 160 and Cygnus 110 (159 and 109 refused) with no Maker skill needed; an item takes merges 1-3 and the 4th is refused with the source kept and the count hidden from the client; Maple TV refuses at 5 today and opens at 4 | `USE_ENABLE_CUSTOM_NPC_SCRIPT` off |
| t-ck-1 Daily Check-In | if AutoQaOne's account claimed within 24 h its last claim is moved back past the cooldown (day and mask kept); claims the claimable day with the window's packet, then that day and the next again; the day's items are taken back (NX, mesos and slots stay) | the claim is recorded and its items, NX (plus 500 per slot past 96), mesos and slots land; the second claims change nothing. Prints the loaded table (which days give NX); asserts against whatever table is loaded | `DAILY_CHECKIN_ENABLED` off, or AutoQaOne under the minimum level |
| t-sc-3 starter chairs | makes a throwaway `QaChair<5 digits>` on AutoQaOne's account the normal way (BeginnerCreator), loads it from the database onto a plain client, runs the login grant on that copy, deletes it | it owns exactly one Relaxer (3010000) and one Fishing Chair (3011000); two logins add nothing and mark it; with both chairs and the mark taken away (a pre-1.6.0 character) the grant adds both once | never |
| t-ss-1 Slums Stamps | AutoQaOne's account (staff) claims the claimable day (its last claim moved back past the cooldown first), then with the check's own earn override claims the next day and again; topped up to 6 if needed, Rooty's purchase call (`cm.buyWithStamps`) buys the first Donor Cap, then again with 5 stamps; balance, claimed items and the cap are put back | the staff claim lands with no stamps; the earning claim credits exactly that day's `stamps` (1, or 6 on days 7/14/21/28) once; the purchase takes exactly 6 and gives one cap; one short is SHORT and takes nothing | `DAILY_CHECKIN_ENABLED` off, or AutoQaOne under the minimum level |
| t-xc-1 NX exchange | AutoQaOne (topped up to 100 NX if short, taken back): a test regular funded from its own mesos bids for 100 NX; `@nx sell 100 <bid>` + `@nx confirm`; after the 10 s cooldown `@nx buy 100 1000` + confirm, then `@nx cancel`; the regular is taken out | the sale fills, its mesos less 1% land on the live character at once and 100 NX leave; the resting buy holds exactly 101 mesos and the cancel gives them back; at the end the same NX and exactly the two fees fewer mesos; the exchange never halts | `-Dslums.exchange` off |
| t-xc-2 LEAF ticker | AutoQaOne (topped up to 20 tradeable Maple Leaves if short, taken back): the test regular, funded from its own mesos, bids for 10 leaves; `@nx leaf sell 10 <bid>` + confirm; after the cooldown `@nx leaf sell 10 <max>` + confirm, then `@nx cancel`; the regular is taken out | the sale takes exactly 10 leaves and fills, its mesos less 1% land at once; the resting sell takes 10 more and the cancel returns exactly those; the regular's 10 leaves come back; at the end the same leaves and exactly the two 1% fees fewer mesos; the exchange never halts | `-Dslums.exchange` off or LEAF closed |
| t-xc-3 Market volatility | a secret 2-minute Leaf Rush, then an online trader bot's 1-leaf LEAF bid from its own mesos, cancelled | the leaf chance x3 during the Rush and back after; the bot's order rests on the book as the bot's; its mesos come back exactly | `-Dslums.market.events`, `-Dslums.exchange.traders` or `-Dslums.exchange` off |
| t-xc-4 Guild war market news | war news on a private desk (no Cody notice, no banner) for an online trader's guild, then a resting 1-elixir bid from its own mesos, cancelled; the trader is one outside the 10 s order cooldown (the check waits it out once if every one is in it), and one short of 100,000 mesos is topped up for the check and exactly that is taken back; the evidence lists every trader's holdings | the trader's role reads AT_WAR_FRESH, ELIXIR pressure is above 0, its news action is an elixir buy; the bid rests on the book and the cancel succeeds | `-Dslums.exchange.traders` or `-Dslums.exchange` off, or ELIXIR closed |

Not automated: how an owned alt's fight looks in a real client (its attack poses, the mob hit numbers, knockback),
an alt's death in a real client, a boat catch-up (t-oa-1 follows through one walkable portal only),
the real login handler's chair grant for a pretend player (pretend players are excluded from it like bots, so t-sc-3
runs the grant on a database copy), and the claim window's look in a real client.

### The LLM judge

`LlmJudge` grades bot lines for the conversation, drama and guild checks. It uses the cloud provider when
`BOT_LLM_CLOUD_BASE_URL` is set (key from `BOT_LLM_CLOUD_API_KEY` or `SOLOMAPLING_LLM_CLOUD_API_KEY`), else the
local model (`BOT_LLM_*`), else none.

- **Rubric:** on-topic, coherent with the line before it, in character for a 2008 player, PG-13 with game-only
  insults, and never says it is an AI or a bot.
- **Answer:** the model returns JSON. An unreadable answer is undecided, never a FAIL.
- **Model-free checks:** a line that says "as an AI" or carries an emoji fails without asking the model.
- **Tolerance (owner, 2026-10-27):** a judged expectation passes with one bad grade when it judged 3 or more
  replies; 2+ bad, or 1 bad of fewer than 3, FAIL. Undecided grades (outage, unreadable) don't count either way.
  A tolerated line is the first evidence line ("tolerated 1 bad of N judged: ..."). An "as an AI" line is never
  tolerated.
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

1.6.2 (ticket 164 b222) adds:

- **Evidence on every result**: `"commit"` (the commit TEST was deployed from, `Server/DEPLOYED_COMMIT`), `"logs"`
  (the QA log file(s) its scenarios wrote, from the suite's "Logging to ..." answer; empty for a probe-only check),
  `"finished"` (the time) and `"fresh": true`. `tools/ops/test-status` prints them for every FAIL and carried row
  (`--checks`: every row).
- **Carried rows are old**: a `--failed` rerun's carried pass reads `"status": "PASS(old, <run started>, <commit>)"`,
  `"fresh": false`, with `carriedFrom` `{run, build, mode, commit, status}`. It counts as `OLD` in `summary`, never as
  `PASS`, and shows as old in `!autoqa report`, the `!qa` menu and test-status: a carried row is never fresh evidence.
- **Skips listed**: every run line has `"skipped": {"<id>": "<reason>"}` and a one-line `"summary"`
  (`"<trigger> at <started>: 120 pass, 2 fail, 13 skip; skipped: t-cl-1, t-cl-2 (client art: eyes only) | ...;
  release: PASS"`), which is also logged ("Auto-QA run done: ...") and told to the GM who started it.
- **Release runs** (`tools/ops/autoqa-run --release all`, `!autoqa run all release`): every check, fresh, at the
  normal clock (refused with `--fast`, `--failed` or a selector other than `all`). The run FAILs overall on any FAIL,
  any check not run, or any SKIP not on the allow-list `src/main/resources/soloMapling/autoqa-release-skips.tsv`
  (initially the 11 client-only MANUAL checks: t-ga-4, t-rg-1, t-rg-2, t-mv-1, t-jq-1, t-cl-1, t-cl-2, t-cl-3,
  t-cc-1, t-mr-2, t-la-1). The run line carries `"release": {"verdict", "line", "fails", "unexpectedSkips",
  "allowedSkips", "missing"}`; the line reads `release: PASS` or `release: FAIL (unexpected SKIP: t-mx-1)`. A switch
  that is off on TEST makes its check SKIP, so a release run also proves TEST runs with every shipped switch on.
  Adding an id to the allow-list is the owner's call.

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
   - `.step(...)` from `Steps`: `run`, `qaDo`, `say`, `waitFor` / `waitForSince` / `until` / `untilProbe` (each
     FAILs when it runs out), `quietFor` (must stay empty), `mark`, `capture`, `fixture`, invites and chat;
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
`Probes160Test`, `Probes164Test`, `AutoQaWaitTimeoutTest`, `AutoQaReleaseTest`, `NavTrapQaTest`, `SyntheticClientStatTest`, `DecideShadowSelfCheckTest`, `DailyCheckinRewindTest`, `SiteAuthQaTest`,
`PlayerStorageTest`.

- **t-mk-2 (ticket 112, the bots' Maker batch):** `ProbesMaker.makerBatch` stages one fresh lv-60 training bot with an empty bag
  apart from 100 of a monster leftover, two cheap Maker-made junk equips and, first in the bag, a pricey one (fee more than
  twice the cheap one); the wallet is exactly the two cheap fees. It calls `BotMaker.townBatch` once and checks the
  made count, crystals, the gone equips, the meso drop (exactly the sum of `MakerItemFactory.getDisassemblyFee`), the pricey
  equip kept (out of mesos, skipped) and both caps (`BotMaker.opsToday`, meter `makerbatch`) up by operations. SKIPs with
  the flag name when `maker.botBatch` is off, and when the world cap has under 3 operations left. Cleanup despawns the bot, forgets its day and
  gives the world meter's units back.
