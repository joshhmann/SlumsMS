# Daily Check-In and Slums Stamps

## Purpose

A per-account 28-day login-streak reward system driven by a dedicated client window (`src/main/java/server/checkin/DailyCheckin.java:1`, `src/main/java/server/checkin/DailyCheckinPackets.java:7`). On login and on request the server pushes a snapshot of the cycle (current day, which days were claimed, 28 icons and tooltips); the player claims the one day that is currently unlocked and receives that day's items, NX Credit, mesos, inventory slots and Slums Stamps. Slums Stamps (`src/main/java/server/checkin/SlumsStamps.java:23-27`) is an account-wide, non-tradeable currency earned only on a claim and spent at Rooty's Donor sections (`scripts/npc/rooty.js:1225-1232`).

## Entry points

| Surface | Location | Notes |
|---|---|---|
| `CP 0x3732` window request | `src/main/java/net/server/channel/handlers/DailyCheckinHandler.java:15` | `byte action` 0 = open, 1 = claim + `byte day` (`:8`, `:19-20`); registered at `src/main/java/net/PacketProcessor.java:191` via `RecvOpcode.DAILY_CHECKIN(0x3732)` (`src/main/java/net/opcodes/RecvOpcode.java:26`) |
| `LP 0x3733` snapshot | `src/main/java/server/checkin/DailyCheckinPackets.java:20-35` | `SendOpcode.DAILY_CHECKIN_SYNC(0x3733)` (`src/main/java/net/opcodes/SendOpcode.java:28`) |
| Login push | `src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:418` | `DailyCheckin.onLogin(c)` after `showDueyNotification`, before the menu hooks (`:417-419`) |
| `@daily` / `@checkin` | `src/main/java/client/command/commands/gm0/DailyCommand.java:15`; registered `src/main/java/client/command/CommandsExecutor.java:236` | `DailyCheckin.open(c)` |
| `@stamps` | `src/main/java/client/command/commands/gm0/StampsCommand.java:17-27`; registered `CommandsExecutor.java:237` | balance + streak status lines |
| `@points` | `src/main/java/client/command/commands/gm0/ReadPointsCommand.java:23-29` | players see stamps, `@points rp` (GM) shows reward points |
| `!givestamps` / `!givevp` (GM 3) | `src/main/java/client/command/commands/gm3/GiveStampsCommand.java:33`; registered `CommandsExecutor.java:478` | `SlumsStamps.add` |
| Rooty's Donor purchase | `scripts/npc/rooty.js:1227` → `src/main/java/scripting/npc/NPCConversationManager.java:153-156` | `SlumsStamps.buy(...)` |
| Site `stamps.json` export | `src/main/java/soloMapling/Economy/board/SiteBoard.java:400-410` → `src/main/java/soloMapling/Economy/board/GameBoardWorld.java:144-146` | 15-minute board cycle, `-Dslums.board` gated |
| Achievement trigger | `src/main/java/soloMapling/achievements/Achievements.java:182-186` (`Trigger.CHECKIN`, `src/main/java/soloMapling/achievements/Trigger.java:23`) | medals `checkin-7`/`checkin-28` (`src/main/java/soloMapling/achievements/achievements.yaml:163-174`) |

## How it works

1. **Login.** `PlayerLoggedinHandler` calls `DailyCheckin.onLogin` (`src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:418`). `onLogin` returns unless `eligible(chr)` (`src/main/java/server/checkin/DailyCheckin.java:102`), loads the streak (`:106`), and sends the snapshot only when something is claimable (`:108-110`). A SQL failure is logged and swallowed (`:111-113`).
2. **Eligibility.** `eligible` = real player ∧ `DAILY_CHECKIN_ENABLED` ∧ level ≥ `DAILY_CHECKIN_MIN_LEVEL` (`DailyCheckin.java:63-65`). `isPlayer` excludes `BotHelpers.isBot`, ids ≥ `BOT_BASE_ID` and `CONSOLE_BOT_ID` (`:71-75`), so bots never get the window, the login push or a claim.
3. **Window open.** `CP 0x3732` action 0 (`DailyCheckinHandler.java:19-22`) or `@daily` (`DailyCommand.java:15`) reaches `DailyCheckin.open` (`DailyCheckin.java:117-142`): non-players return silently (`:119-121`), disabled → `"Daily Check-In is not available right now."` (`:122-125`), below level → the level/where line (`:126-129`), else load + `snapshot(...)` + the two rule lines and a lapsed-streak line (`:133-134`, `notes` at `:145-152`), then a cooldown message when `cooldownSeconds > 0` (`:135-138`).
4. **Request throttle.** `handleRequest` (`DailyCheckin.java:160`) records `lastRequestByAccount.put(accId, now)` and drops anything inside `MIN_REQUEST_INTERVAL_MS = 1_000` (`:29`, `:169-172`), so a held-down window button cannot generate claim attempts faster than one per second.
5. **Per-account serialisation.** Inside `synchronized (accountLocks.computeIfAbsent(accId, …))` (`:176`) the streak is re-read from the store (`:177`) and, for `claimDay > 0`, `claim(...)` runs (`:179`). Every path ends with a fresh snapshot (`:190`).
6. **Claim.** `claim` (`DailyCheckin.java:196-230`): non-player → `NOT_CLAIMABLE` (`:197-199`); `day` must be `1..28` and equal `streak.claimableDay(now)` else the refusal text is sent (`:200-204`, `refusal` at `:86-97`); `DailyCheckinRewards.canReceive` must pass or the day stays claimable (`:205-207`); the day's `Reward` is looked up and stamps are earned only when `SlumsStamps.earns(chr)` (`:208-209`).
7. **Record, then grant.** `store.recordClaim(...)` writes the new streak and credits stamps in one transaction (`:212-213`, implementation `:266-317`). A returned `-1` means another session won the compare-and-swap → `"that reward was just claimed (on another character?)"` (`:218-222`). Only after the commit does `DailyCheckinRewards.grant(chr, day)` hand out the reward (`:223`), the yellow stamp line is sent (`:224-226`) and `Achievements.onCheckin(chr, day)` fires (`:228`).
8. **The transaction.** `recordClaim` (`DailyCheckin.java:266-317`) turns autocommit off (`:270`), then either `INSERT IGNORE` for a first-ever claim (`:273-282`) or `UPDATE … WHERE account_id = ? AND last_claim = ?` — a compare-and-swap on `last_claim` (`:284-292`). `rows != 1` → rollback and `-1` (`:294-297`). Stamps are credited by `SlumsStamps.CREDIT_SQL` in the same transaction (`:298-307`), then commit (`:308`).
9. **Grant.** `DailyCheckinRewards.grant` (`src/main/java/server/checkin/DailyCheckinRewards.java:241-277`): slots are capped at `MAX_SLOTS = 96`; each slot that does not fit is paid as `NX_PER_MISSING_SLOT = 500` NX with a message (`:247-260`, constants `:38-40`); NX goes through `chr.getCashShop().gainCash(NX_CREDIT, nx)` with type 1 = NX Credit (`:36`, `:262`); days paying nothing NX still confirm without a "0 NX" line (`:264-268`); mesos via `chr.gainMeso(r.mesos(), true)` (`:269-271`); items via `InventoryManipulator.addById(client, id, qty, expiration)` where `expireDays > 0` means `now + days * ONE_DAY_MS` (`:272-275`); finally `chr.saveCharToDB()` so the account-row NX is persisted with the items (`:276`).
10. **Snapshot encoding.** `snapshot` normalises the streak and sends the mask (`DailyCheckin.java:232-235`); `DailyCheckinPackets.snapshot` writes `byte 1`, `byte currentDay`, `int claimedMask`, `byte justClaimed`, `byte 28`, then 28 icon item ids and 28 tooltip strings (`DailyCheckinPackets.java:20-35`).
11. **Streak arithmetic.** `DailyCheckinStreak` (`src/main/java/server/checkin/DailyCheckinStreak.java:13-64`): `CYCLE_DAYS = 28` (`:15`), `PERIOD_MS = 86_400_000L` (`:16`), `normalized` restarts a cycle that lapsed ≥ 48 h or had finished day 28 (`:21-30`), `lostToGap` is the same 48 h test for the message (`:33-35`), `claimableDay` returns 0 during the 24 h cooldown else `day + 1` (`:38-43`), `claimed` sets bit `d-1` in the mask (`:46-49`), `cooldownSeconds` rounds up (`:52-58`), `viewDay` shows the claimable day or, during a cooldown, the day just claimed (`:61-64`).
12. **Reset boundary (exact).** A claim becomes available exactly when `now - lastClaim >= PERIOD_MS`, i.e. a **rolling 24 hours from the stored epoch-millis `last_claim`, not a calendar midnight** (`DailyCheckinStreak.java:38-43`). The cycle restarts at day 1 when `now - lastClaim >= 48 h` or when day 28 was claimed and 24 h passed (`:26`). There is **no calendar, time-zone or midnight logic anywhere in the package** — the only `java.time` use is the ISO timestamp of the export (`src/main/java/server/checkin/SlumsStamps.java:194`). The server does set a JVM default zone at `src/main/java/net/server/Server.java:938` (`TimeZone.setDefault(... TIMEZONE)`, field `src/main/java/config/ServerConfig.java:399`, example default `GMT` at `config.example.yaml:305`) but the check-in never reads it, so **changing `TIMEZONE` does not move the check-in boundary**.
13. **Slums Stamps earning.** `SlumsStamps.earns` (`src/main/java/server/checkin/SlumsStamps.java:68-80`) refuses non-players, GM characters, `SyntheticClient`, and `autoqa*` character or account names; the claim transaction applies the same rule in SQL (`CREDIT_SQL` at `:256-257` skips any account owning a `gm > 0` character or named `autoqa%`). The auto-QA override is in-memory only (`:58`, `:262-268`).
14. **Spending.** `SlumsStamps.buy` (`:115-144`) checks room, then takes the price with `UPDATE … WHERE stamps >= ?` (`SPEND_SQL` at `:251`), then gives; a failed delivery refunds the exact price (`:138-141`).

## Data

| Store | Shape | Where |
|---|---|---|
| `daily_checkin` table | `account_id` PK, `day` 0..28, `claimed_mask` bit `d-1` = day d claimed this cycle, `last_claim` epoch millis (0 = never) | `database/migrations/004-daily-checkin.sql:10-17`, ledger insert `:19` |
| `accounts.stamps` | `INT NOT NULL DEFAULT 0`, one balance per account | `database/migrations/039-slums-stamps.sql:20-27`, ledger gate `:18`, ledger insert `:65` |
| 039 conversion + rollback tables | each Billion Coin (`4001253`) and vote point became 6 stamps (`:58-60`); `stamps_039_coins` / `stamps_039_accounts` back the rollback | `database/migrations/039-slums-stamps.sql:1-13`, `database/migrations/039-slums-stamps.rollback.sql:10-27` |
| Reward table (live-editable) | 28 ordered days, keys `day,icon,nx,stamps,mesos,slots,items` | `src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml:12-221`, parser keys `src/main/java/server/checkin/DailyCheckinRewardsYaml.java:38-39` |
| Built-in mirror table | `DEFAULT_DAYS`, used when the yaml is missing or invalid | `src/main/java/server/checkin/DailyCheckinRewards.java:118-146` |
| `stamps.json` | `{"generated": ISO-UTC, "accounts": {name: {id, stamps}}}`, only balances > 0, QA names skipped | `src/main/java/server/checkin/SlumsStamps.java:183-197`, source query `:200-213` |

The shipped table: 4,000 NX per cycle, 1,000 only on days 7/14/21/28 (`daily-checkin-rewards.yaml:56-59`, `:107-110`, `:158-161`, `:213-216`); +20 slots (+4 Equip, +4 Use, +4 Set-up, +8 Etc); 1 stamp a day and 6 on the four milestone days = 48 a cycle (`:11`, `src/main/java/server/checkin/DailyCheckinRewards.java:76-77`, `:94-100`). Day 28 is the White Scroll + 5 Gachapon Tickets + `etc 8` (`daily-checkin-rewards.yaml:212-221`). The `mesos` key is documented and parsed (`DailyCheckinRewardsYaml.java:79`) but **no day in the shipped yaml sets it** (only occurrence of `mesos` in the yaml is the header comment, `:7`).

## Switches

| Switch | Default | Effect | Live / TEST |
|---|---|---|---|
| `DAILY_CHECKIN_ENABLED` (`src/main/java/config/ServerConfig.java:45-46`) | `false` (`config.example.yaml:75`) | master gate; when off the window says "not available right now" and the login push is suppressed (`DailyCheckin.java:60`, `:102`, `:122-125`), and auto-QA t-ck-1/t-ss-1 SKIP (`src/main/java/soloMapling/autoqa/Probes160.java:1256-1258`) | live `true` since 1.6.0 (`docs/release/1.6.0.conf:34`); not restated in `docs/release/1.6.4.conf` SERVER_SET (`:201-214`) because it is already in the live unit. TEST value **unknown** (`start-test.sh` is on another host) |
| `DAILY_CHECKIN_MIN_LEVEL` (`ServerConfig.java:55`) | `10` (`config.example.yaml:80`) | minimum level for the window and the login push (`DailyCheckin.java:64`) | neither release conf sets it → 10 on live, inherited; TEST unknown |
| `-Dsolomapling.dataDir` (`src/main/java/soloMapling/DataFiles.java:48`) | unset | redirects the yaml lookup; the rewards file is a registered known path (`DataFiles.java:70`) and is read through `DataFiles.optional` (`DailyCheckinRewards.java:195`) | not set in `docs/release/1.6.4.conf` |
| `-Dslums.board=true` (`src/main/java/soloMapling/Economy/board/SiteBoard.java:54`) | off | enables the board outbox, which is what writes `stamps.json` (`SiteBoard.java:400-410`); not part of the check-in itself | live on (`docs/release/1.6.4.conf:230`) |
| `TIMEZONE` (`ServerConfig.java:399`) | `GMT` (`config.example.yaml:305`) | sets the JVM default zone at boot (`Server.java:938`); **no effect on the check-in boundary** | not set in the 1.6.4 SERVER_SET |

The package itself reads **no `System.getProperty`** — every behavioural knob is a `config.yaml` key.

## Invariants

- One claim per account per rolling 24 h, enforced in the database by the `last_claim` compare-and-swap (`DailyCheckin.java:284-297`), not only by the in-memory lock (`:176`) or the 1 s throttle (`:169-172`).
- A reward is granted only after the claim row commits; a failed or lost write grants nothing (`DailyCheckin.java:212-223`).
- Items and mesos are capacity-checked before the write, so a claim never half-delivers for lack of room (`DailyCheckin.java:205-207`, `DailyCheckinRewards.java:229-235`).
- One inventory tab never exceeds 96 slots; the excess is paid as 500 NX per slot (`DailyCheckinRewards.java:38-40`, `:247-260`).
- Stamps can never go negative (`SPEND_SQL` `… AND stamps >= ?`, `SlumsStamps.java:251`) and are taken before the item is given, refunded if delivery fails (`:124-141`).
- Staff accounts, auto-QA accounts and bots never earn stamps; bots never earn, claim or spend (`SlumsStamps.java:68-80`, `:256-257`, `DailyCheckin.java:71-75`).
- The reward table is all-or-nothing: 28 days in order, known item ids, no unknown keys — otherwise the built-in table is used and the error is logged (`DailyCheckinRewardsYaml.java:53-68`, `:107-109`, `:127-137`; `DailyCheckinRewards.java:194-208`).
- NX is minted only on days 7/14/21/28 in the shipped table (`DailyCheckinRewardsTest` `aFullCycleHandsOutExactly4000Nx`, `src/test/java/server/checkin/DailyCheckinRewardsTest.java:287`).

## Origin

**custom** for every file in the subsystem. Evidence:

| Path | Oldest commit | Upstream (`/root/workspace/cosmicms`) |
|---|---|---|
| `src/main/java/server/checkin/DailyCheckin.java` | `3f5b0e8ac` 2026-10-19 "Add the Daily Check-In window's server side (off by default)" | absent |
| `src/main/java/server/checkin/DailyCheckinStreak.java` | `3f5b0e8ac` 2026-10-19 | absent |
| `src/main/java/server/checkin/DailyCheckinPackets.java` | `3f5b0e8ac` 2026-10-19 | absent |
| `src/main/java/server/checkin/DailyCheckinRewards.java` | `3f5b0e8ac` 2026-10-19 | absent |
| `src/main/java/server/checkin/DailyCheckinRewardsYaml.java` | `6eb25e9bb` 2026-10-20 | absent |
| `src/main/java/server/checkin/SlumsStamps.java` | `e7b63a31b` 2026-10-27 "Add Slums Stamps…" | absent |
| `src/main/java/net/server/channel/handlers/DailyCheckinHandler.java` | `3f5b0e8ac` 2026-10-19 | absent |
| `src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml` | `6eb25e9bb` 2026-10-20 | absent |

The repack baseline `03d13cb21` (2026-10-18) contains no check-in or stamps path (`git ls-tree -r 03d13cb21 | grep -i 'checkin\|stamps'` is empty), and `tools/cosmicdiff/classifications.json` has **no entry** for any of these paths. Integration points that do exist upstream are modified by us: `src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java`, `src/main/java/net/PacketProcessor.java`, `src/main/java/config/ServerConfig.java`, `src/main/java/client/command/CommandsExecutor.java`.

## Tests and QA

| Test | Covers |
|---|---|
| `src/test/java/server/checkin/DailyCheckinStreakTest.java:13-40` | day 1 available at once, the 24 h cooldown, the 48 h lapse, the day-28 wrap |
| `src/test/java/server/checkin/DailyCheckinClaimTest.java:71,84,94,103` | claim recorded then rewarded (day 1 pays no NX), not-claimable grants nothing, no room keeps the day, a failed write grants nothing |
| `src/test/java/server/checkin/DailyCheckinMessagesTest.java:69-114` | the two rule lines, lapsed vs live streak, below-level from `@daily` and from the window, too-early and wrong-day refusals |
| `src/test/java/server/checkin/DailyCheckinRewindTest.java:38,49` | the QA rewind moves a within-24 h claim back and leaves fresh/lapsed accounts alone |
| `src/test/java/server/checkin/DailyCheckinRewardsTest.java:96-340` | NX/items/slots/mesos grants, the 96 cap and 500-NX conversion, no-room, bots, 48 h restart, day-28 wrap, tooltips, exactly 4,000 NX a cycle, **shipped yaml == `DEFAULT_DAYS`** (`:307-310`), yaml strictness (`:313`), 004 ledger idempotence (`:337`) |
| `src/test/java/server/checkin/SlumsStampsTest.java:155-378` | 1/day + 6 on milestone days = 48 a cycle, yaml parity, staff/QA/bot exclusion, GM claim pays items but no stamps, the QA earn override, purchase/refund, status lines, the site file |
| `src/test/java/server/checkin/SlumsStampsMigrationIT.java:84,100,114` | 039 converts at 6 once, rollback is repeatable, credit skips staff/QA and spending never goes negative |
| `src/test/java/server/checkin/SlumsStampsGiftsMtsMigrationIT.java:96,114` | gifts and MTS listings converted at 6, rollback restores them |
| `src/test/java/scripting/RootyShopSelectionTest.java:66-80` | Rooty's script against a fake `getStamps`/`buyWithStamps` |
| Auto-QA `t-ck-1` "Daily Check-In pays once per 24 h" | `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1262-1264` → `Probes160.dailyCheckin` (`src/main/java/soloMapling/autoqa/Probes160.java:1255-1335`): rewinds the QA account, claims through `CP 0x3732` (`:3579`), asserts day/items/NX (incl. the +500 per slot past 96)/mesos/slots, then that two more claims change nothing. SKIP when the switch is off or the QA player is below the minimum level (`:1256-1262`) |
| Auto-QA `t-ss-1` "Slums Stamps: claim, earn, buy" | `AutoQaCatalog.java:1272-1274` → `Probes160.slumsStamps` (`:1349-1441`): staff claim earns 0, the earn override credits exactly the day's stamps once, a Rooty purchase takes exactly 6 and gives one cap, one short returns `SHORT` and takes nothing |
| Docs | `docs/ops/qa-suite.md:1031` (t-ck-1), `:1033` (t-ss-1) with their SKIP conditions; PASS recorded at `docs/roadmap/1.6.1.md:122` (t-ck-1 and t-ss-1, 02:33 UTC) |

Not covered anywhere: no `!qa` scenario exists for the check-in or stamps (`src/main/java/soloMapling/qa/` has no match); no test asserts the `LP 0x3733` byte layout; no test drives `PlayerLoggedinHandler` → `DailyCheckin.onLogin`; no test covers `@daily`/`@stamps`/`@points` command wiring; no test covers `SiteBoard.writeStamps`/`GameBoardWorld.stamps`; no test covers the client window itself (that lives in the client repo: `src/dailycheckin.cpp`, `src/Custom.wz`, art at `UI/UIWindowEx.img/DailyCheckin/backgrnd`, described by `tools/clientcheck/clientcheck.py:33-34`, `:50`, `:53`, `:64`, `:1281`). `t-ck-1` asserts against whatever table is loaded, so a yaml edit changes what it proves (`Probes160.java:1263`, `:1274`).

## Known issues

No card in `/root/work/STATUS-1.6.4.md`, `/root/work/OWNER-DECISIONS.md` or `/root/work/PQ-UNFINISHED-REPORT.md` mentions the check-in or Slums Stamps (only the unrelated b052 match appears). Findings of this review:

- NEW-FINDING: `src/main/java/server/checkin/DailyCheckinRewards.java:169` ignores the boolean returned by `InventoryManipulator.addById` (`src/main/java/client/inventory/manipulator/InventoryManipulator.java:55-57`). Room is checked at `DailyCheckinRewards.java:229-235` before the claim commits, but if the delivery still fails (a race with another inventory change, or `chr == null` → `addById` returns false at `InventoryManipulator.java:77-79`) the item is silently lost while the claim and the stamps stay recorded (`DailyCheckin.java:212-223`).
- NEW-FINDING: `src/main/java/server/checkin/DailyCheckinRewards.java:262` credits NX through `CashShop.gainCash` (`src/main/java/server/CashShop.java:347-364`), which adds with no bound, while the ticket-113 path `giveNxCredit` explicitly refuses to pass `Integer.MAX_VALUE` (`CashShop.java:339-345`). A balance near the cap can wrap negative on a milestone claim.
- NEW-FINDING: `src/main/java/server/checkin/SlumsStamps.java:159-174` prints `"Check-In: day " + s.day()` using the *normalised* streak, so a player whose streak lapsed over 48 h is told "day 0/28" (`normalized` returns `NEW` at `DailyCheckinStreak.java:26-27`) even though the very next claim will be day 1.
- NEW-FINDING: `src/main/java/server/checkin/SlumsStamps.java:200-213` exports every account with `stamps > 0`, including accounts that own GM characters and which therefore can never earn more (`:68-80`); a GM-granted balance leaks into the board file that the site reads.
- NEW-FINDING: `src/main/java/server/checkin/DailyCheckin.java:52` (`lastRequestByAccount`) grows one entry per account that ever sent a request and is only cleared for a QA rewind (`:345`) or a test seam (`:352`); logout does not remove the entry.
- NEW-FINDING: `docs/tickets/17-daily-checkin-release.md:17` says the window art is built by `tools/wz/add_daily_checkin_art.py`, but `tools/wz/` does not exist in this repo (`ls tools/` has no `wz`), so that release step is not reproducible from here.
- NEW-FINDING: `src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml:7` documents a `mesos` key and the parser bounds it (`DailyCheckinRewardsYaml.java:79`), but no day in the shipped file sets it — the feature is dead config today, and `DailyCheckinRewardsTest.mesosStillWork` (`:186`) only proves the built-in path.

## State

**working** — for the server side. Evidence: eight test classes under `src/test/java/server/checkin/` cover the streak clock, the claim/record/grant order, capacity refusal, the slot cap, yaml parity and strictness, the migration and rollback; auto-QA `t-ck-1` and `t-ss-1` are catalogued (`AutoQaCatalog.java:1262`, `:1272`) and recorded as PASS (`docs/roadmap/1.6.1.md:122`); the feature is enabled on live since 1.6.0 (`docs/release/1.6.0.conf:34`) with migrations 004 and 039 on the live ledger (`docs/release/1.6.4.conf:182`). I did not run any of it in this review, and the client half (`src/dailycheckin.cpp`, `Custom.wz`) is out of this repo, so end-to-end delivery is unverified here.

## Open questions

- Is `DAILY_CHECKIN_ENABLED` on in TEST? Live's value is documented (`docs/release/1.6.0.conf:34`, `docs/tickets/17-daily-checkin-release.md:5`) but `start-test.sh` is on another host and unreachable.
- `docs/tickets/111-slums-stamps.md:48` recorded "on TEST; no release conf sets it" as a thing to check before 1.6.1; `docs/roadmap/1.6.1.md:122` later says it is on on TEST and live, but I could not read TEST's own `config.yaml` (on another host) to confirm the value behind that claim.
- Is `mesos` intended to stay unused in the shipped table, or is a day missing its value? Nothing in `docs/tickets/17-daily-checkin-release.md` mentions mesos rewards.
- Does the login push at `PlayerLoggedinHandler.java:418` ever race a client that has not yet built the window? The handler sends the packet inline with no delay or readiness check, and no test or doc records the ordering requirement.
- `SlumsStamps.PER_BILLION_COIN`/`PER_VOTE_POINT` (`:37-38`) are public constants but only the migration 039 uses the 6:1 rate (`database/migrations/039-slums-stamps.sql:58-60`); nothing in Java reads them, so a drift between the two would go unnoticed.
