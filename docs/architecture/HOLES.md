# HOLES — everything that is not plainly working

Read at 2026-11-05 against branch `164-archreview` (from `origin/release-1.6.4`, HEAD `a26fdb6ce`) by reading code,
never by running a server. Each entry names the subsystem file that carries the evidence; the file, not this page, is
the citation of record.

State words, used exactly as the subsystem files use them:

| State | Means |
|---|---|
| **broken** | a path players or operators use does not do what the code says it does, or does damage |
| **partial** | the mechanism works, but a named half is missing, unverified, or reachable only through a switch nobody sets |
| **unknown** | the code cannot answer it from this checkout (another host holds the config, or the behaviour depends on a client build not in the repo) |

## 1. Broken

| # | Where | What breaks | Evidence |
|---|---|---|---|
| 1 | Guild expel from a guildless player | `mc.getGuild().getAllianceId()` runs before the rank/guild guard, so packet 0x08 from a player with no guild throws instead of being refused | `src/main/java/net/server/channel/handlers/GuildOperationHandler.java:232` vs the guard at `:236-240`; `server/handlers-party-guild-alliance-family.md` |
| 2 | Party leader change with an offline leader | `party.getLeader().getPlayer()` is dereferenced where `Party.getLeader()` can return null | `src/main/java/net/server/world/World.java:1055`, `src/main/java/net/server/world/Party.java:203` |
| 3 | Incubator cash item | `if (i == id)` compares an array index to an item id, so no incubated item is ever found, the amount stays 0, and both the incubator and the target item are still consumed | `src/main/java/net/server/channel/handlers/UseCashItemHandler.java:771-778`, consume at `:274-277` |
| 4 | GM buyback (`TransactionService`) | the buyback flag is committed before the items are granted and the `catch` cannot revert it, so a partial failure burns the buyback | `src/main/java/server/transactions/TransactionService.java:108-120` |
| 5 | `!enableauth` | `unregisterLoginBypassEntry` builds a `Pair<String,Integer>` while the map is keyed `Pair<Hwid,Integer>`; `Pair.equals` has a `getClass` guard, so the removal never matches | `src/main/java/net/server/coordinator/login/LoginBypassCoordinator.java:77-81` vs `:49`, `src/main/java/tools/Pair.java:100-110` |
| 6 | Failed boot | a boot exception logs "Syntax error in 'world.ini'." (a file this repo does not have) and calls `System.exit(0)`, so systemd sees success | `src/main/java/net/server/Server.java:1003-1006` |
| 7 | Cash-shop gift stage | `CashShop.open(true)` is never called (only `false`, from map change) and `CashShopButtonTest` asserts that, so `isOpened()` is always false, `CashOperationHandler` always refuses, and `loadGifts` has no caller — the `gifts` table is orphaned | `src/main/java/server/CashShop.java:377`, `src/main/java/net/server/channel/handlers/ChangeMapHandler.java:194`, `src/main/java/net/server/channel/handlers/CashOperationHandler.java:68`, `src/test/java/server/cashshop/CashShopButtonTest.java:33` |
| 8 | Client QA signals | the QA client is built from branch `client-170-qa-signals` (`9bc1458`), not from the 1.6.4 client line: `src-164-data` has no `src/qamodule*` and no `QaModule_` call site, and `scripts/build.ps1:11` never passes `-DSLUMS_QA_MODULE` | `/root/work/client/src-164-data` grep, `/root/work/client/src-170-qasignals` tip; `client/qa-signals.md` |
| 9 | QA worker batch (b286) | the event cursor advances over the whole fetched batch while `wait_signal` scans only that batch, so the second signal of a two-signal batch is missed even though it arrived | `tools/qa-runner/qarunner.py:382-389` vs `:476-494` |

## 2. Partial

| Subsystem | What is missing or unproven | Evidence |
|---|---|---|
| server/boot-and-shutdown | shutdown sweeps login clients (ticket 62 unfixed); no test drives `init()` or an abort path; `Channel` swallows its own constructor exception | `server/boot-and-shutdown.md` |
| server/handlers-movement | no anti-cheat on `MOVE_PLAYER` (autoban distances exist, nobody adds a point); pet loot ignores ownership; `CHANGE_MAP` body catches everything and can leave the client without `enableActions` | `server/handlers-movement.md` |
| server/handlers-npc-shops | shop sell/rebuy edge paths; b289-class pricing fixed on this branch but not what live runs | `server/handlers-npc-shops.md` |
| server/handlers-inventory-items | pickup exists twice and the `@backpack` copy lost the self-loot, godly-drop and Ariant gates; incubator (broken above); keymap packet-edit defence commented out | `server/handlers-inventory-items.md` |
| server/handlers-quests | hidden quest chains unfinished by design; Maker quest accounting (b279) open; bot questing off (`BotQuests.java:63`) | `server/handlers-quests.md` |
| server/handlers-trade-storage-merchants | merchant close is two non-atomic writes and swallows SQL errors; `HiredMerchant.buy`/`PlayerShop.buy` index listings unguarded; `DueyHandler` tests `TOSERVER_CLAIM_PACKAGE` twice | `server/handlers-trade-storage-merchants.md` |
| server/handlers-cashshop | window path dropped every base entry gate (GM block, event, MiniDungeon); `CashShop` factory NPE for a jobType outside 0/1/2; no test parses the window packet | `server/handlers-cashshop.md` |
| server/cashshop | client-facing flow unproven; gift delivery dead (broken above); live flag by inference only | `server/cashshop.md` |
| server/world-and-maps | 49 maps blocked, 247 map ids carry fatal-class findings, map life NPE kills a whole map load | `server/world-and-maps.md` |
| server/wz-data | provider layer works; the data does not (9,528-NPC/3,456-mob gap, 270 mob ids without templates) | `server/wz-data.md` |
| server/scripting | per-client engine cache never hits or shrinks (`"scripts/" + path` vs `path`); `scripting/field/*` unreachable; two reload methods have no callers | `server/scripting.md` |
| server/persistence | `saveCharToDB` returns void and logs failures only; cooldowns/pets save on their own connections outside the character transaction; `!saveall` always says success | `server/persistence.md` |
| server/mechanics | no live evidence: nothing 1.6.4-shaped has run on TEST; boss/Chaos Zakum wait on the owner's click-through | `server/mechanics.md` |
| server/economy | ticket 270 catalog is on TEST but the ticket header still says "not merged"; `economy.yaml` miss falls back to code defaults with an info line only | `server/economy.md` |
| server/companions | the master switch is absent from 1.6.4's SERVER_SET while the code default is false, so `@botme` is dead on live | `server/companions.md`, `server/config.md` |
| server/families | `family_character.lastresettime` is filtered on but never written; pedigree reads one channel only; buff clamp on write path only | `server/families.md` |
| server/qa-and-autoqa | multi-lane runs still produce b284-class false failures; an infrastructure skip is reported as a normal skip | `server/qa-and-autoqa.md` |
| server/site-and-dashboard | `SiteMobKills.restore`/`flush` share the single site-export executor with `world.json`; per-map export skips existing files | `server/site-and-dashboard.md` |
| bots/decision-core | feasibility is checked before scoring but refused starts never feed back, so the mix drifts toward what the scorer likes and the host refuses; refusal counts exist nowhere | `bots/decision-core.md` |
| client/crash-guards | guards ship and are tested; the crash *report* half is empty (`InstallCrashReport()` is a no-op) | `client/crash-guards.md` |
| client/crash-report | the delivery link is off in shipped builds (`MAPLEROOT_CRASH_REPORT` OFF, absent from `scripts/build.ps1`) and `crash_diagnostics` writes the full module path without scrubbing | `client/crash-report.md` |
| client/resolution-and-d3d9 | 2 of 5 resolution modes never report; the Parsec `E_FAIL` the D3D9 host exists for is still unexplained; `GetAddressByPattern` null goes into `PatchMemory` | `client/resolution-and-d3d9.md` |
| client/exe-and-starter | exe resource is a hardcoded `1.2.0.0`; exit code collapses to 1; no test touches the starter | `client/exe-and-starter.md` |

## 3. Unknown

| Question | Why it cannot be answered here |
|---|---|
| What TEST actually passes to the JVM | `start-test.sh` lives on `mapletest`; this review may not reach it. Every "TEST" cell in the subsystem files says unknown for that reason. |
| What live's unit passes | the unit and `/etc/slums-*` are live's; `docs/release/1.6.4.conf` states SERVER_SET and UNIT_EDITS and `deploy/slumsms.service.example` is a template — where the two disagree the files say so |
| Whether live runs the cash-shop window path | `-Dcashshop.window.enabled` appears in no release conf; the example unit has it. Marked `[INFERENCE]` in `server/handlers-cashshop.md` |
| Whether `family_character.lastresettime` is NULL-able | the column is created by the stock schema, which is not in this repo; if it is NULL the daily DB reset never matches a row |
| Whether the fishing flags are on TEST | `tools/ops/feature-health.tsv:46` says on, `docs/HANDOFF.md:30` says off |
| Whether any client build in use matches the QA branch | the Windows boxes are out of scope; only the source branches are readable |

## 4. Working

`server/network-and-packets`, `server/handlers-login`, `server/handlers-combat-damage` (main paths),
`server/commands`, `server/achievements`, `server/checkin` (server side), `server/families` (main paths),
`server/wz-data` (provider layer), `client/hpmp`, `client/injector-and-hooks` (infrastructure), `client/ui-tweaks`,
`client/config-ini`, `client/build-stamp-and-version`.

## 5. Documentation and card drift

These are not code bugs; they are the reason a reader of the existing docs would be misled.

| # | Drift | Evidence |
|---|---|---|
| 1 | `/root/work/STATUS-1.6.4.md` lists cards as open that are fixed at HEAD: b289, b290, b292, b293, b294, b295, b296, b297, b298, b300 | each has a `git merge-base --is-ancestor`-verified fix commit; see the subsystem files |
| 2 | `MIN_CLIENT_BUILD` is stated twice, differently | `docs/release/1.6.4.conf:10` says 10721, `:284` sets 10740; `/root/work/OWNER-DECISIONS.md:43` still 10721 |
| 3 | `docs/ops/qa-suite.md:949` tells operators to set `AUTOQA_PARALLEL` in a release conf | no such `ServerConfig` field exists (`src/main/java/config/ServerConfig.java:204-213`); the switch is the JVM property read at `src/main/java/soloMapling/autoqa/AutoQaScheduler.java:40-42`, and a SERVER_SET entry would trip the strict unknown-key abort |
| 4 | `docs/tickets/270-exchange-item-catalog.md:3` says "not merged, not deployed" | `docs/release/1.6.4.conf:257-262` records it on TEST since 2026-11-05 09:16 UTC (`bcc2513e3`, 706 tickers) |
| 5 | Test guard reads the wrong release conf | `src/test/java/config/OwnedAltSwitchLineTest.java:44` hardcodes `docs/release/1.6.3.conf`, so the 1.6.4 regression it exists for cannot fail it |
| 6 | `src/buildstamp_proto.h:11` documents the old build formula | `/root/work/client/src-164-data/cmake/BuildStamp.cmake:25` computes `major*10000+(minor+1)*100+patch*10` |

## 6. Verified in code by this review

The entries above and below were read in the tree, not taken from an agent's summary. The ones whose *severity* an
agent overstated are noted here so nobody chases the wrong thing:

- **Backpack pickup**: the quest gate is **not** lost. `MapItem.canBePickedBy` re-checks `needQuestItem`
  (`src/main/java/server/maps/MapItem.java:145`) and the fork calls it (`src/main/java/client/Character.java:2664`).
  What is lost is self-loot, godly-drop credit and Ariant score.
- **`MobDamageMobFriendlyHandler` double removal**: harmless — `MapleMap.removeMapObject` is an idempotent set remove
  (`src/main/java/server/maps/MapleMap.java:483-490`). The real smell is `killFriendlies` doing
  `(Character) getPlayers().get(0)` unguarded (`:1832-1834`).
- **`ItemId.isChair`**: not broken. The repack made the alternative body live (`itemId / 10000 == 301`), which is legal
  Java and returns true for 3010000-3019999 — wider than upstream's 3010000-3011000. No player-visible break.
- **`Character.saveCharToDB` swallow**: deliberate and commented (`src/main/java/client/Character.java:10324-10327`);
  the residual gap is that `!saveall` then claims success unconditionally
  (`src/main/java/client/command/commands/gm6/SaveAllCommand.java:40-48`).
- **"HANG_OUT picked 2,734 vs run 172"**: partly arithmetic. `selected` counts every decision pass including
  `KEEP_CURRENT` (`src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:528`), and one town stay
  is 5-20 minutes at a 30 s cadence (`LifeProfile.java:35`) — the comparable metric is `chosen` (`:876-880`) vs
  `accepted` (`:798`).

## 7. Coverage gaps in this review

- Nothing was run: no test suite, no server, no client, no launcher, no site. Every "works" is a reading of code plus
  whatever unit test the subsystem file names.
- TEST and live configuration could not be read (another host, and live is out of bounds for this review).
- The client, launcher and website halves were read for their own repos; nothing was built on the Windows box.
- WZ data was checked for its readers, not its contents (no `wz/` in this checkout).

## NEW-FINDING index

Every NEW-FINDING the subsystem files record, one line each, grouped by subsystem. `file:line` is
the citation from the subsystem file; open that file for the full reasoning. Nothing here is a fix
instruction — these are leads for Server Dev to verify.

### server

**achievements.md** (3)

- `src/main/java/soloMapling/achievements/AchievementService.java:144-146` — The bot branch of deliver updates cache.getOrDefault(id, new HashMap<>())…computeIfPresent(…). When the id is not cached (the normal case after forget, or after the cache.clear() above) the write lands on a throwaway map and …
- `src/main/java/soloMapling/achievements/Achievements.java:259-264` — Achievements.onLogout has no caller anywhere in src/main/java (grep -rn 'Achievements.onLogout' finds only the declaration), so the per-character cache entry is only dropped by the login path (forget at …
- `src/main/java/soloMapling/achievements/AchievementService.java:134-136` — deliver announces before the medal is actually granted, so the site feed can show an achievement whose medal is still owed (NO_ROOM).

**boot-and-shutdown.md** (5)

- `src/main/java/net/server/Server.java:1003-1006` — The world-start catch logs "[SEVERE] Syntax error in 'world.ini'." and calls System.exit(0). There is no world.ini in this repo (worlds are config.yaml), and exit code 0 tells systemd the run succeeded, so a world that fails …
- `src/main/java/net/server/Server.java:2192-2208` — A commented-out shutdown block is left in the method, including while (ch.getConnectedClients() > 0) and a System.err.println("FUCK MY LIFE").
- `src/main/java/net/server/channel/Channel.java:167-169` — A channel constructor failure is swallowed with log.warn; the server reports "now online" with a missing channel and a port that is not listening, with no boot-failure signal own reading
- `src/main/java/net/server/Server.java:1025` — The online message still reads "Cosmic is now online after {} ms." while the boot banner says SlumsMS — the release-parity check in docs/release/1.6.4.conf:295 greps for "Starting world 0" in FORBID_LOG and "SoloMapling …
- `src/main/java/net/server/Server.java:1103-1129` — The JQ-door broadcaster is a raw new Thread with no reference kept, so shutdown cannot interrupt it; it survives into the restart path (instance = null; getInstance().init(), Server.java:2250-2252) and a second copy starts own …

**cashshop.md** (5)

- `src/main/java/server/CashShop.java:377` — CashShop.open(boolean) () is called once, always with false (src/main/java/net/server/channel/handlers/ChangeMapHandler.java:194), and CashShopButtonTest.java:33 asserts open(true) is never called.
- 443-455), which depends on the physical column order of the live table; any column added out of order silently shifts sn/ringid.
- `src/main/java/net/server/world/World.java:1409` — 28-40), so the "suggested items" counters () never see window sales; the comment says this is on purpose, but no switch exposes it.
- 30) has no production caller — only the Python tool mirrors its rules, so a WZ-only schedule change cannot reach the server without an nxcoupons row.
- which is dead code today (NEW-FINDING above).

**checkin.md** (7)

- `src/main/java/server/checkin/DailyCheckinRewards.java:169` — ignores the boolean returned by InventoryManipulator.addById (src/main/java/client/inventory/manipulator/InventoryManipulator.java:55-57).
- `src/main/java/server/checkin/DailyCheckinRewards.java:262` — credits NX through CashShop.gainCash (src/main/java/server/CashShop.java:347-364), which adds with no bound, while the ticket-113 path giveNxCredit explicitly refuses to pass Integer.MAX_VALUE (CashShop.java:339-345).
- `src/main/java/server/checkin/SlumsStamps.java:159-174` — prints "Check-In: day " + s.day() using the *normalised* streak, so a player whose streak lapsed over 48 h is told "day 0/28" (normalized returns NEW at DailyCheckinStreak.java:26-27) even though the very next claim will be day 1.
- `src/main/java/server/checkin/SlumsStamps.java:200-213` — exports every account with stamps > 0, including accounts that own GM characters and which therefore can never earn more (:68-80); a GM-granted balance leaks into the board file that the site reads.
- `src/main/java/server/checkin/DailyCheckin.java:52` — (lastRequestByAccount) grows one entry per account that ever sent a request and is only cleared for a QA rewind (:345) or a test seam (:352); logout does not remove the entry.
- `docs/tickets/17-daily-checkin-release.md:17` — says the window art is built by tools/wz/add_daily_checkin_art.py, but tools/wz/ does not exist in this repo (ls tools/ has no wz), so that release step is not reproducible from here.
- `src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml:7` — documents a mesos key and the parser bounds it (DailyCheckinRewardsYaml.java:79), but no day in the shipped file sets it — the feature is dead config today, and DailyCheckinRewardsTest.mesosStillWork (:186) only proves the …

**commands.md** (6)

- `src/main/java/net/server/channel/handlers/AdminCommandHandler.java:67-75` — case 0x01 (/d, clear inventory) has return; **inside** the slot loop, after the first iteration — it clears at most slot 1 and leaves the packet handler; the //TODO What is the point of this loop? next to it suggests this was …
- `src/main/java/net/server/channel/handlers/AdminCommandHandler.java:136-145` — The /kill mode iterates for (int x = 0; x < amount; x++) monsterx.get(x) over the map's monster list without bounding amount; asking for more than exist throws IndexOutOfBoundsException, swallowed by …
- `src/main/java/client/command/CommandsExecutor.java:368` — gachalist is registered with rank 0 inside registerLv2Commands, while its class sits in commands/gm2/ — so @help lists it under "Common" and players keep it after a rank change.
- `src/main/java/client/command/CommandsExecutor.java:444-445` — gacharoll / gachasim (classes in commands/gm4/) are registered at rank 3 inside registerLv3Commands, so GM 3 can roll and simulate the gachapon; the directory convention says 4.
- `src/main/java/client/command/CommandsExecutor.java:242` — invite is a rank-0 registration of a gm3 class; the GM branch is decided *inside* InviteCommand.execute by player.gmLevel() >= GM_LEVEL (src/main/java/client/command/commands/gm3/InviteCommand.java:33 and :46, GM_LEVEL = 3 at …
- `src/main/java/client/command/CommandsExecutor.java:373-505` — Feature-gated registrations read system properties at **construction** time (the singleton is built at src/main/java/net/server/Server.java:1028), so flipping -Dslums.qa/-Dslums.autoqa/-Dweather.enabled requires a restart; …

**companions.md** (3)

- `src/main/java/config/ServerConfig.java:103` — OWNED_ALTS_ENABLED (config.yaml) false () **absent from SERVER_SET** — see NEW-FINDING 1 the master switch; gates @botme entirely (requireEnabled, src/main/java/soloMapling/companions/OwnedAltService.java:144-146) and the …
- `docs/release/1.6.4.conf:201-214` — (SERVER_SET) no longer sets OWNED_ALTS_ENABLED, while the six companion switches beside it are true. The 1.6.3 conf did set it (docs/release/1.6.3.conf:113; docs/release/1.6.0.conf:34), and ServerConfig.ownedAltSwitchesLine's …
- `src/main/java/soloMapling/companions/OwnedAltWedding.java:21` — still says "No bonus of any kind", which stopped being true when stage 3 added MarriageExpBonus (same release), and docs/release/1.6.4.conf:253-256 still says marry/expbonus are "NOT in UNIT_EDITS" while line 271's UNIT_EDITS …

**config.md** (6)

- `docs/release/1.6.4.conf:201-215` — 1.6.4's SERVER_SET turns on all six companion/summon switches but drops OWNED_ALTS_ENABLED, which 1.6.3's set to true (docs/release/1.6.3.conf:113).
- `src/main/java/config/ServerConfig.java:98` — SPAWN_BOTS_ON_STARTUP is declared, documented in config.example.yaml:108, described as unused in docs/release/1.3.0.md:156, and read by nothing in src/main/java.
- `src/main/java/config/WorldConfigs.java:55-81` — validate checks ids and nothing else. channels: 0 passes and produces a world that starts, logs "Finished loading world", and can never accept a player; channels: -3 likewise. Only id is range-checked own reading
- `src/main/java/config/ServerConfig.java:416` — WORLD_TRANSFER_COOLDOWN = NAME_CHANGE_COOLDOWN is evaluated once at construction, so a file setting only NAME_CHANGE_COOLDOWN silently moves the world-transfer cooldown with it, while a file setting both is order-independent …
- `src/main/java/constants/string/CharsetConstants.java:65` — The charset bootstrap read sets setIgnoreUnknownProperties(true), so it cannot serve as a strictness pre-check: a config.yaml with a typo'd key passes this read and only fails the later strict one, which means the failure …
- `src/main/java/config/YamlConfig.java:22` — The file is opened relative to the process working directory with no search path and no -D override, so a unit that starts with the wrong WorkingDirectory fails with "Could not read config file config.yaml" and no hint of …

**economy.md** (6)

- `src/main/java/soloMapling/Economy/exchange/Exchange.java:669-671` — refuses NX orders *before* the idempotency lookup at :677. Once the desk is switched on, a retried request id for an NX order placed before the cutover returns BAD_REQUEST ("the NX book is off") instead of the recorded order …
- `src/main/java/soloMapling/Economy/exchange/Exchange.java:1493-1496` — logs "desk step not stored" and keeps the new R in memory only. The stored R then diverges from the live R for an unbounded time, and a restart silently reloads the older value (:562-570) with no halt — unlike an unreadable …
- `src/main/java/soloMapling/Economy/exchange/Exchange.java:2051-2066` — swallows StoreException from store.accountsWithPayout() and returns the count so far with no log line, so a store failure during the payout sweep is silent; the same class of failure in tickDesk (:1486-1490) is logged.
- `src/main/java/soloMapling/Economy/exchange/ExchangeSite.java:474` — the site is shown two different fee numbers for the same NX trade. ExchangeSite.tickers publishes fee_bps_each_side from ExchangeConfig (, default 100 at src/main/java/soloMapling/Economy/exchange/ExchangeConfig.java:59-63), …
- `src/main/java/soloMapling/Economy/exchange/ExchangeService.java:76` — , :192 rebuild exchange-tickers.json on every poll (15 s by default) by serialising every open ticker with 10 levels and 20 trades (ExchangeSite.java:487-489).
- `src/main/java/soloMapling/Economy/EconomyConfig.java:29` — the numbers that steer bot pricing can silently disappear.  names src/main/java/soloMapling/Economy/economy.yaml and :57-68 catches every failure of that read, so a resolve miss leaves every price band, shop markup and sink at …

**families.md** (4)

- `src/main/java/net/server/task/FamilyDailyResetTask.java:38` — ** family_character.lastresettime is filtered on but never written.  does UPDATE family_character SET todaysrep = 0, reptosenior = 0 WHERE lastresettime <= ?, and the only other mentions of the column in the whole tree are the …
- `src/main/java/net/server/channel/handlers/OpenFamilyPedigreeHandler.java:37-40` — ** the pedigree window only sees one channel. OpenFamilyPedigreeHandler.handlePacket resolves the name with c.getChannelServer().getPlayerStorage().getCharacterByName (), so a family member standing in another channel is …
- `src/main/java/net/server/channel/handlers/FamilyPreceptsHandler.java:24` — ** the family message never reaches the other members. FamilyPreceptsHandler writes the new text () but the broadcast is commented out (:25), so only the leader's own window updates until someone reopens it (getFamilyInfo …
- `src/main/java/client/Family.java:373-388` — ** Family.loadAllFamilies links a junior to a senior only within the same load pass; a row whose senior has already been loaded but whose family had no leader is repaired memory-only () and logged, so the repair is lost on the …

**fishing.md** (4)

- `src/main/java/constants/id/ItemId.java:65-68` — The repack flipped isChair to the alternative body: the range check is commented out at :66 and the labelled statement alt: return itemId / 10000 == 301; at :67 is the live one.
- `src/main/java/soloMapling/fishing/GameFishing.java:113` — Achievements.onFish is called for bots too (the counter branch above it distinguishes bot from player, this line does not), and Achievements.onLogin explicitly skips bots …
- `src/main/java/soloMapling/fishing/FishingSessions.java:52` — start rejects only now < 0 and overflow; a clock that goes backwards between start and the first tick leaves s.last ahead, and the tick at src/main/java/soloMapling/fishing/FishingSessions.java:101 cancels the session.
- `src/main/java/soloMapling/fishing/GameFishing.java:16` — The 256-session cap is a single process-wide static shared by players and bots; 104 of the first 3,000 bot slots own a chair (docs/tickets/92-fishing.md:62) and the bot roster is ~3,000, so a bot-heavy fishing hour plus real …

**handlers-cashshop.md** (7)

- NEW-FINDING: EnterCashShopHandler.java:41-45 The window path skips every stocking gate the stock handler had — no cannotEnterCashShop, event, or MiniDungeon check, and no isOpened() check.
- NEW-FINDING: CashShopSurpriseHandler.java:38 Guarded by cs.isOpened(), which the window path never sets, so the packet is dead on live.
- NEW-FINDING: MTSHandler.java:58-65 852 lines of unreachable MTS code retained in the tree with DB writes (mts_items INSERT/UPDATE/DELETE at :135, :151, :253, :323).
- NEW-FINDING: CashShopWindowHandler.java:121-123 A short p.available() < 4 single buy returns **silently** — no buyResult, no enableActions.
- NEW-FINDING: CouponCodeHandler.java:260-268 The 255 cash-item cap silently drops items beyond 255 (cashItems = Arrays.asList(new Item[255]) discards the tail) *after* the code has been claimed, so a big reward is unrecoverable.
- NEW-FINDING: CashOperationHandler.java:114 //TODO check for gender on the gift action 0x04 — a gift can be sent for a wearable the recipient cannot wear, unlike the window path which refuses with BUY_CANNOT_WEAR …
- `src/main/java/client/Character.java:6403-6405` — NEW-FINDING: CashShop.java:269-283 factory stays null for any jobType outside 0/1/2 when USE_JOINT_CASHSHOP_INVENTORY is false, and factory.loadItems(...) at :311 then NPEs — so the character cannot load.

**handlers-combat-damage.md** (4)

- MobDamageMobFriendlyHandler.java:79-91 kills the friendly twice.** map.killFriendlies(monster)
- MonsterBombHandler.java:41-52 never checks HP or ownership.** Any client can send MONSTER_BOMB
- the damage-line-count check is dead.**
- MagicDamageHandler.java:63 reads + +YamlConfig.config.server.DOJO_ENERGY_ATK** — a doubled unary

**handlers-inventory-items.md** (6)

- 771 if (i == id) compares the **array index** with the **item id**, so getIncubatedItem(c, 5060002) (:274) never matches its 20-entry table and amount stays 0 (:769-774); addById(c, id, (short) 0) (:778) then grants a …
- 571 a second else if (itemType == 552) { //DS EGG THING is unreachable: 552 is already handled at :558, so the "DS EGG THING" branch is dead code and no DS-egg behaviour exists.
- `src/main/java/client/command/CommandsExecutor.java:246` — 2656-2827 modifiedPickupItem (reached by any player through @backpack on,  → BackPackToggleCommand.java:41) is a hand-copied fork of originalPickupItem that dropped three gates: MapId.isSelfLootableOnly …
- 605-608 refusing a Vega cast answers getInventoryFull() — a wrong packet for "the equip has no upgrade slots", which the client renders as a full inventory.
- 50-56 a banned / GM-only / off-job skill bind hits the combined condition at :50 and is silently continued at :56 with the autoban, the FilePrinter line, the disconnect and the return all commented out (:51-54), so a …
- path exists twice and the second copy has lost the quest/self-loot/godly-drop gates (NEW-FINDING above), reachable by

**handlers-login.md** (4)

- `src/main/java/net/server/coordinator/login/LoginBypassCoordinator.java:77-81` — builds the removal key as
- `src/main/java/net/server/Server.java:355` — calls getIP(world, channel).split(":") *outside* the
- `src/main/java/net/server/coordinator/session/SessionCoordinator.java:278-281` — makes closeSession
- `src/main/java/client/Client.java:1385-1394` — still selects autologin/autoch/autochar into fields that

**handlers-movement.md** (8)

- 181 — inRangeInclusive is return !(pVal < pMin) \ \ (pVal > pMax);, which reduces to pVal >= pMin — the upper bound pMax is dead.
- 49-66 — pet movement is used as an auto-loot trigger with a 60000 (1.2M with the VIP rock) unit radius and no ownership check (isOwnershipRestricted is only consulted from the two damage handlers), so a pet can strip a leased …
- 59 — EXCLUDED_ITEM_IDS is a hard-coded pair with the comment "change this to map later"; it is the only protection against a pet picking up quest items
- `src/main/java/client/autoban/AutobanFactory.java:48` — AutobanFactory.PORTAL_DISTANCE () and DISTANCE_HACK (:47) exist but no movement handler calls addPoint/alert, so a client can report any position and only MovePlayerHandler's silence difference is observable
- 153-155 — generateFloatMovementCommand is an empty stub with no callers; dead API surface
- 183 vs /root/workspace/cosmicms/...:174 — our parser skips 4 bytes (wobble) then reads a foothold; upstream skipped 6. Both agree on the total stride only if the foothold is genuinely present in this client build; a wrong …
- 46 — the VIP rock resolves its target with getMapFactory().getMap(map_id) and never calls getWarpMap/MapRouting, so a blocked map id that the table would reroute is instead refused as "Map is invalid." (safe, but inconsistent …
- 176-178 — the whole body catches Exception and prints a stack trace, so a malformed CHANGE_MAP leaves the client with no enableActions and a player can appear frozen until the client times out

**handlers-npc-shops.md** (9)

- 85-88 a scriptless NPC with no shop logs and **returns without enableActions**, leaving the client frozen. classifications.json records this exact hazard for scripts/npc/2110000.js; the return is what makes it lethal
- 155-171 the pitch branch has no else: a player short of Perfect Pitch Leaves gets **no packet at all** and the shop window waits
- 184-195 the token branch sends (byte) 3 (inventory full) inside the else and then (byte) 0 unconditionally at :195, so a failed token purchase also reports success. Identical in base Shop.java:139-158 — inherited
- 359-361 createFromDB swallows SQLException with printStackTrace and returns the half-built shop, so a failing shopitems query yields a valid shop with zero stock (and negative-caches it)
- `src/main/java/scripting/npc/NPCConversationManager.java:2451-2462` — 96-100 negative caching means a shop row added while the server runs (e.g. addShopToDb, ) is invisible until reloadShops
- 53,60,66 no null check on getPlayer().getShop(): a client that sends NPC_SHOP without an open shop NPEs the handler. Base-identical
- 65 (byte) p.readShort() truncates the recharge slot; slot 128+ becomes negative. Base-identical
- 240-242 Math.random() < (dropRate / (double) mde.chance) treats chance as a denominator, but the SQL filter is only chance >= 0, so a reactordrops row with chance = 0 always drops
- 17-57 sanitizeReagentQuantities is entirely commented out, so Maker reagent packet-edit checks are dormant

**handlers-party-guild-alliance-family.md** (7)

- 232 reads mc.getGuild().getAllianceId() *before* the rank/guild guard at :236, so a crafted 0x08 from a sender with no resolvable guild throws NPE instead of being refused.
- 49-152 has no default branch — an unknown operation byte is dropped with no enableActions, which leaves the client waiting. The b298 refusals do send it (:139).
- 66-67 returns silently when the sender is not the party leader (or has no party) and sends no enableActions, unlike every other refusal in this subsystem.
- `src/main/java/net/server/world/Party.java:192-205` — 1055 Character mc = party.getLeader().getPlayer(); is null-checked at :1056, but Party.getLeader() itself returns null when leaderId matches no member (), and that call is not guarded — a party whose leader row is missing NPEs …
- 72 builds "SELECT * FROM guilds WHERE guildid = " + guildid by concatenation while every other statement in the class is parameterised; the id is an int so it is not injectable today, but the pattern invites the next copy to be.
- 105 setLeader takes neither the party lock nor a membership check; only World.updateParty's CHANGE_LEADER branch calls it, so the safety is entirely in the caller.
- Guild expel (0x08) from a guildless sender broken NEW-FINDING at GuildOperationHandler.java:232

**handlers-quests.md** (6)

- `src/main/java/server/quest/Quest.java:169-179` — 34-37, 
- `src/main/java/server/quest/actions/QuestAction.java:58-62` — 54-93, 
- `src/main/java/server/quest/Quest.java:609` — 45-46, :87-89, 
- 49-50
- 88-92, :121-123
- `src/main/java/server/quest/requirements/InfoExRequirement.java:58-60` — 49-51, , src/main/java/server/quest/requirements/ScriptRequirement.java:45-46, src/main/java/server/quest/Quest.java:513-516, :559

**handlers-trade-storage-merchants.md** (6)

- `src/main/java/server/Storage.java:256-258` — Storage.sendStorage fills the per-type view with the *whole* item list for every InventoryType — for (InventoryType type : InventoryType.values()) typeItems.put(type, new ArrayList<>(storageItems)); …
- `src/main/java/server/maps/HiredMerchant.java:328` — HiredMerchant.buy reads PlayerShopItem pItem = items.get(item) with no bounds check [](src/main/java/server/maps/HiredMerchant.java:328); a stale or crafted slot index would surface as an IndexOutOfBoundsException in the …
- `src/main/java/net/server/channel/handlers/DueyHandler.java:58` — DueyHandler repeats the TOSERVER_CLAIM_PACKAGE test after the remove branch, so the second else if at [](src/main/java/net/server/channel/handlers/DueyHandler.java:58) can never execute — dead branch that looks like a lost …
- `src/main/java/server/transactions/TransactionService.java:108` — TransactionService.processTransaction commits the buyback flag before granting anything — if (updateTransactionBuybackStatus(transaction.getTransactionId(), true)) …
- `src/main/java/net/server/channel/handlers/WeddingTalkMoreHandler.java:36` — WeddingTalkMoreHandler performs its effect with no groom/bride or state guard at all — it writes the grid slot and sends OnWeddingProgress(true, 0, 0, (byte) 3) whenever the sender is not the couple …
- `src/main/java/client/processor/npc/StorageProcessor.java:50-53` — the ore-storage branch in StorageProcessor.storageAction returns immediately on getUsingOreStorage() [](src/main/java/client/processor/npc/StorageProcessor.java:50), but no OreBag class exists in the tree — the path is inert …

**mechanics.md** (2)

- src/main/java/soloMapling/mechanics/QueueStaging.java (331 lines, added 1f7271226 2026-11-03) has no
- runChatter's outcome ring is process-lifetime only — RunOutcomes javadoc says "Not durable yet. The ring

**network-and-packets.md** (7)

- `src/main/java/net/packet/logging/OutPacketLogger.java:25` — ctx.write(msg) drops the caller's ChannelPromise, so Client.sendPacket's writeAndFlush (src/main/java/client/Client.java:1646) never completes and outbound failures are invisible while logging is on.
- `src/main/java/client/autoban/AutobanFactory.java:96` — , :109 With USE_AUTOBAN false (the shipped default, config.example.yaml:230) every alert/autoban call is a no-op and only USE_AUTOBAN_LOG lines remain, so packet-edit detection produces log noise and nothing else.
- `src/main/java/net/PacketProcessor.java:227` — MTS_OPERATION is unregistered while RecvOpcode.MTS_OPERATION is still declared and is listed in Client.PLAYER_ACTIONS (src/main/java/client/Client.java:330); a client still sending it is silently dropped.
- `src/main/java/net/encryption/MapleAESOFB.java:155` — getPacketLength has no production caller — PacketDecoder uses its own decodePacketLength (src/main/java/net/encryption/PacketDecoder.java:43-47), so the header-length rule exists twice and only one copy is tested.
- `src/main/java/client/Client.java:1301` — copyClientStampFrom has no caller; it was superseded by ClientStampCarry (src/main/java/net/server/coordinator/session/ClientStampCarry.java:37). Dead twin of the live carry path.
- `docs/release/1.6.4.conf:10` — vs :284 The header still says "No new client: MIN_CLIENT_BUILD stays 10721" while :284 assigns 10740 and :282-283 records the owner decision behind it. The file contradicts itself; treat :284 as current.
- `src/main/java/net/netty/ServerChannelInitializer.java:33` — sessionId is a single static AtomicLong shared by the login server and every channel, so channel session ids are not comparable across restarts and are only monotonic inside one JVM.

**persistence.md** (8)

- `src/main/java/net/server/guild/Guild.java:198` — NEW-FINDING Guild.writeToDB catches SQLException and only prints, so a failed guild save is invisible to the caller — the same swallow b294 fixed on the storage side.
- `src/main/java/client/Character.java:10324-10328` — NEW-FINDING Character.saveCharToDB(boolean) returns void and swallows every error, so !saveall reports "All players saved successfully." without proof. ; src/main/java/client/command/commands/gm6/SaveAllCommand.java:40-48.
- `src/main/java/client/Character.java:9625-9670` — NEW-FINDING The logout writes are split across connections: saveCooldowns (cooldowns + playerdiseases) and Pet.saveToDb each take their own connection and swallow errors, so a successful character commit does not imply the …
- `database/migrations/002-classic-fm-return.sql:2-3` — NEW-FINDING 002-classic-fm-return.sql is the one forward migration that does not create the ledger table, so it cannot be applied to a database where 001 has not run (or the ledger was rebuilt): its INSERT IGNORE fails and the …
- `src/main/java/net/server/PlayerStorage.java:113-131` — NEW-FINDING PlayerStorage.disconnectAll logs a throwing disconnect and then still clears the registry, so a character whose teardown failed leaves the world with no record that it was not saved — nothing retries it. .
- `src/main/java/server/maps/HiredMerchant.java:761-781` — NEW-FINDING Merchant close is two non-atomic writes on two connections: the items (ItemFactory.MERCHANT.saveItems), then fredstorage; a crash between them leaves items saved with no Fredrick log.
- partial** for the swallow-class writers listed as NEW-FINDING (guild, cooldowns, diseases, pets, merchant close) — they
- Whether any of the swallow sites in the NEW-FINDING rows has a real player-visible incident. Nothing in STATUS, the

**qa-and-autoqa.md** (6)

- `src/main/java/soloMapling/qa/QaLog.java:76` — The comment // a long: 34 topics no longer fit an int is stale: the enum holds 45 constants (OWNED_ALT at :28 … PETPOT at :73); the mask is still a single long, so this is documentation drift only.
- `src/test/java/soloMapling/autoqa/AutoQaSchedulerTest.java:251-253` — The guard that catches undeclared shared state knows only five literal patterns (MANNERS TALLY WEALTH TRADE SHOP clumpsOnMyMap) and matches them against step *descriptions*; a new probe that reads map-wide lines under another …
- `src/main/java/soloMapling/autoqa/AutoQaRunner.java:211-247` — + AutoQaScheduler.java:86-115 — A WORLD check runs only when busy is empty (canStart, AutoQaScheduler.java:74-79) while pick always takes the first pending check that *can* start (:86-101), so with parallel>1 a world check …
- `docs/ops/qa-suite.md:949` — The doc says the lane switch can be set as AUTOQA_PARALLEL "in a release conf", but no such ServerConfig field exists (src/main/java/config/ServerConfig.java:204-213 lists every AUTOQA_* key) and AutoQaScheduler.parallel() …
- `docs/tickets/167-parallel-autoqa.md:43` — The ticket records the cap as "clamped to 1..4"; the code clamps to SyntheticPlayers.MAX_LANES = 10 since 1.6.3 (src/main/java/soloMapling/autoqa/AutoQaScheduler.java:41, …
- `src/main/java/soloMapling/autoqa/AutoQaRunner.java:355-400` — A check whose expectations all SKIP is reported SKIP with reason nothing could be judged here, and a release run then fails it unless it is on the allow-list — an infrastructure skip (server not fully up, probe disabled) is …

**scripting.md** (9)

- `src/main/java/scripting/AbstractScriptManager.java:72-75` — NEW-FINDING:  — the cache read key is "scripts/" + path but the store key is path, so Client.getScriptEngine always misses and every interaction re-evaluates the script; resetContext (:91-93) removes a key that was never …
- `src/main/java/scripting/portal/PortalScriptManager.java:46-59` — NEW-FINDING:  — isScriptAvailable evaluates the whole file (no cache) and is called once per candidate portal while PortalFactory builds a map (src/main/java/server/maps/PortalFactory.java:72-79); a map with WZ-scripted …
- `src/main/java/scripting/portal/PortalScriptManager.java:40` — NEW-FINDING:  vs :108 — portal engines are cached in a static HashMap keyed only by script path and shared by every client, but portal scripts are *not* wrapped in SynchronizedInvocable, unlike event scripts …
- `src/main/java/net/server/channel/Channel.java:80` — NEW-FINDING: src/main/java/scripting/field/ — the whole package is dead: FieldScriptManager's constructor is empty (:51-53), nothing constructs FieldScriptManager or FieldInstanceManager, Channel.fieldScriptManager () is never …
- `src/main/java/scripting/npc/NPCScriptManager.java:54` — NEW-FINDING:  — ess is constructed and never used; :59-60 — spawnItemEventNpc(int) has an empty body and no callers.
- `src/main/java/scripting/map/MapScriptMethods.java:183-192` — NEW-FINDING:  — registerPlayerForMonsterWeakeningEventInstance asks for an event manager named "MonsterWeakening", but no scripts/event/MonsterWeakening.js exists and no script calls this method or …
- `src/main/java/scripting/item/ItemScriptMethods.java:30` — NEW-FINDING:  — no src/main code references the class (item scripts get a NPCConversationManager as im); only src/test/java/scripting/OreBagScriptTest.java:34 uses it.
- `src/main/java/scripting/map/MapScriptManager.java:45-47` — NEW-FINDING:  and src/main/java/scripting/quest/QuestScriptManager.java:208-211 — the reload methods have no callers (only reloadEventScriptManager, EventScriptManager.reloadScripts and reloadPortalScripts are reachable), so a …
- `src/main/java/scripting/event/EventScriptManager.java:69-75` — NEW-FINDING:  — an unknown event name returns fallback.em, and fallback is null when 0_EXAMPLE.js failed to load or was not in the list (the offline branch at Channel.java:150-153 loads only that file), so a script typo …

**site-and-dashboard.md** (3)

- `src/main/java/soloMapling/dashboard/DashboardExporter.java:202-203` — **  writes calibration.json into the dashboard data folder only when KillCalibration.enabled(), while deploy/dashboard/slums-dashboard-extras@.service and the web app never reference that file (tools/dashboard/web/app.js has …
- `src/main/java/soloMapling/dashboard/DashboardMapExport.java:306` — **  skips a map whose maps/<id>.json already exists, and nothing in the file re-exports it when the nav graph version changes unless --force is passed; the map signature at :165 covers graphs only, so stale maps/*.json survive …
- `src/main/java/soloMapling/site/PublicSiteExport.java:98-102` — **  restores and flushes site_mob_kills on the same single-thread executor that writes the three JSON files; a slow or blocked MySQL round trip inside SiteMobKills.restore/flush delays world.json/feed.json, and the 60 s world …

**world-and-maps.md** (5)

- `src/main/java/server/maps/MapFactory.java:391-403` — 391-393 uses LifeFactory.getLife(id, type) without a null check and immediately calls myLife.setCy(cy); an unknown life type (LifeFactory logs "Unknown Life type") or a mob with no Mob.wz image NPEs the whole map load instead …
- `src/main/java/net/server/channel/Channel.java:205-210` — 207 nulls mapManager during shutdown while getMapFactory() at :265 returns it with no null guard, so any task or script that runs between "dispose its maps" and "stop its network service" dereferences null instead of failing …
- `src/main/java/server/maps/MapleMap.java:3192-3194` — 3193 starts removePlayer with chr.getClient().getChannelServer(); a character whose client is already detached (logout/DC path) NPEs inside map removal. 
- 4413-4417, :4446-4450
- loads) or to fail the whole map: the code does neither by design — it NPEs (see NEW-FINDING above).

**wz-data.md** (5)

- `src/main/java/provider/wz/WZFiles.java:41-45` — a typo in -Dwz-path (or a non-directory) is ignored and "wz" is used instead (), so the server can boot against a different data tree with no log line
- `src/main/java/client/SkillFactory.java:342-343` — 66-83): SkillFactory.getSkillName re-parses the whole String.wz/Skill.img.xml on every call (), and GameConstants.getMaxObstacleMobDamageFromWz parses every Obj image at class-init …
- `src/main/java/server/maps/MapFactory.java:156-161` — getData returns null for an absent file (:70-72) but throws RuntimeException if the same file disappears between the check and the open (:77) — callers such as  only handle null
- `src/main/java/client/DamageSkin.java:17-19` — the only reader is DamageSkin.loadDamageSkins () and its call is commented out (src/main/java/net/server/Server.java:1050, src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:239); WZFiles.LIST has no …
- 379-382), so a malformed back node costs visual layers with no log entry

### bots

**decision-core.md** (4)

- ** LifeTownSlots.reserve counts bots *in flight* (incoming, LifeTownSlots.java:12, :26) while the
- ** the classifier between them is only explained in a source comment, and bot-life.json writes both
- ** no counter for gate-B refusals exists anywhere (LifeAcceptance.Status has 7 values,
- ** a fifth of the catalogue is unreachable through the scorer; harmless, but it makes the option list

**training-bot.md** (8)

- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/GrindBreakRoutine.java:118-158` — begin() consumes
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2035` — doDecide can enter
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1560-1564` — the town deadline
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1288-1296` — the SELL-with-no-shop
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2221-2263` — vs
- `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:487-489` — getRandomDelay() returns
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1237-1240` — keepLifeActivity
- See the NEW-FINDING at :1288-1296.

### client

**build-stamp-and-version.md** (8)

- 24 reads CP 0x3738 (format 1, int build, string release), but src/buildstamp_proto.h:24 sets kBuildStampFormat = 2 and caps are always written (src/bypass.cpp:435).
- `tools/release/README.md:45-47` — the shape short format=1, int build, string release (:45-46) and the pre-b205 formula major*10000 + minor*100 + patch (:47).
- "build is monotonic (VERSION major*10000 + minor*100 + patch ...)" — the code has used (minor+1)*100 + patch*10 since b205, so the comment describes numbers nobody has built since 1.6.1 src/buildstamp_proto.h:11 vs …
- `src/main/java/server/reports/BugClientLogs.java:40` — the server asks a "supporting" client for logs after @bug (, :67-69; src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:14-16) but no client file sets that bit, and git log -S kCapLogUpload / -S 0x373B over the …
- `docs/qa/client-scripts/README.md:63-64` — the gate refuses a build nothing produces. It lives only on client-170-qa-module/client-162-* (2f17894, not an ancestor of client-164-rc3), while  and tools/qa-scripts/test_qascript.py:222-225 treat it as present git …
- `docs/release/1.6.4.conf:284` — 91-93 prints the stamp and only warns when release's major.minor.patch differs from the version being staged; a conf/gate mismatch (e.g.
- `docs/release/1.6.1.conf:258-259` — tags v1.6.0.2 and v1.6.1-rc1..rc9 all carry VERSION = 1.6.0, so the shipped numbers 10601/10680/10690/10700/10710 exist only in slums-build.txt, the release docs and PE resources.
- 18 still says "based on Kaentake" in FileDescription and :25 splits the copyright, while docs/PROVENANCE.md records a MapleRoot Full Repack baseline plus Kaentake — the resource names one upstream of two src/slums.rc.in:18, …

**crash-guards.md** (7)

- src/crash_report.cpp:167** — InstallCrashReport(){} is an empty no-op with no caller; the
- src/crash_scrub.h:5** — the header says "tests/crash_scrub_test.cpp checks it"; there is no
- src/crash_diagnostics.cpp:37-38, :67-68** — the "last C++ error" note accepts only
- src/mapobj_guard.h:22-24 / src/lamps.cpp:1496-1497** — the map-object guard is compiled
- src/hook.h:282-283** — AttachMobAttackFixMod() is the only guard install without a
- src/attackdiag.cpp:212** — [debug] attackdiag is absent from config.example.ini, so the
- src/crash_classify.h:86** — GraphicsDriver requires ExceptionCode == 0xC0000005; a driver

**crash-report.md** (9)

- NEW-FINDING: src/crash_report.cpp:167 — InstallCrashReport() is an empty stub with no caller. No report path
- NEW-FINDING: src/crash_report.cpp:53 — PutLogTail and the write at :145 use CWD-relative paths, so a client
- NEW-FINDING: src/crash_diagnostics.cpp:23-24 — crash.log records the full module path
- NEW-FINDING: src/crash_scrub.h:49-53 — the dialog rule is a strstr over the whole line, so any integration.log
- NEW-FINDING: src/crash_scrub.h:5 and src/crash_classify.h:5 — both header comments point at
- `src/main/java/server/reports/BugClientLogs.java:40` — NEW-FINDING:  — client and server disagree about caps bit 1: BugClientLogs.CAPS_LOG_UPLOAD
- `docs/tickets/131-bug-reports.md:184-187` — NEW-FINDING:  — the server expects a [crash] section carrying the tail of
- NEW-FINDING: src/crash_report.cpp:10 -- the header promises "the launcher shows the file and sends it
- NEW-FINDING: CMakeLists.txt:17 vs src/crash_diagnostics.cpp:32 — the VEH's NoteCxx and resman/resolution

**exe-and-starter.md** (8)

- NEW-FINDING: src/launcher.rc:10-11,24,28 — the exe's FILEVERSION/FileVersion/ProductVersion are hardcoded
- NEW-FINDING: src/launcher.cpp:14 — lpCmdLine (which WinMain gives **without** argv[0]) is passed verbatim as
- NEW-FINDING: src/launcher.cpp:35 — the exit code is discarded except under MAPLEROOT_INTEGRATION
- NEW-FINDING: src/debug.cpp:16-18 — integration.log is opened relative to the CWD, appended forever, with no size
- NEW-FINDING: src/launcher.cpp:14-20 — Detours terminates a half-created child (external/Detours/src/creatwth.cpp:1581-1584)
- NEW-FINDING: src/launcher.manifest:8 — elevation is unconditional, though the documented need was Kaentake's
- NEW-FINDING: SlumsMS-164-archreview:tools/release/client-allow.txt:121 — Start SlumsMS.cmd is an allow-listed
- Starter version resource **partial** (broken metadata) hardcoded 1.2.0.0, src/launcher.rc:10-11,24,28; see NEW-FINDING

**hpmp.md** (7)

- src/hpmp_patch.h:44 constexpr int kStockCap = 30000; is declared and never referenced anywhere in the client repo (grep -rn kStockCap src tests → one hit).
- `docs/release/1.6.0-hpmp-4byte.md:11-13` — src/hpmp.cpp:53 / src/hpmp_patch.h:60 The patch is unconditional: no config.ini key, no CMake option (src/CMakeLists.txt:21), no check of the connected server's version.
- `src/main/java/config/ServerConfig.java:61` — MIN_CLIENT_BUILD = 0 (gate off) is the default and MIN_CLIENT_BUILD_GM_BYPASS = false (:64) is the only brake. A 1.6.x jar on a server whose config.yaml does not set the key serves old clients misaligned packets; the memo …
- `src/main/java/client/Character.java:7791-7793` — The GM job branch of level-up still adds exactly 30000 HP and 30000 MP per level (Job.GM at :7791), which was the stock 30,000 cap expressed as a gain.
- `src/main/java/tools/PacketCreator.java:119` — showHpHealed(int cid, int amount) writes the heal amount with p.writeByte(amount), so any heal over 255 (map recovery 77 * map.getRecovery() * 1.5, src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:54) shows a …
- src/buildstamp_proto.h:11 The comment states the build is "VERSION major*10000 + minor*100 + patch", but cmake/BuildStamp.cmake:25 computes major*10000 + (minor+1)*100 + patch*10.
- `docs/release/1.6.4.conf:10` — vs :284 The 1.6.4 conf contradicts itself on the gate: the header says "No new client: MIN_CLIENT_BUILD stays 10721 (nothing on this branch changes what the client reads)" (:10), while MIN_CLIENT_BUILD=10740 at :284 was raised …

**injector-and-hooks.md** (6)

- NEW-FINDING: no runtime exe verification exists.** grep for sha256/ImageBase across src/ hits comments
- NEW-FINDING: VMTHook is dead code** — declared src/hook.h:322, defined src/hook.cpp:102-107, and no caller
- NEW-FINDING: src/midair_patch.h:1-10 still says "SCAFFOLD … nothing patches them yet"** while
- NEW-FINDING: two independent config.ini readers with different parsers.** src/ uses
- NEW-FINDING: AttachClientBypass patches are not covered by VerifyKaentakeHooks groups of their own** — the
- NEW-FINDING: UnhandledExceptionFilter is rewritten at 0x00797074 to 0xEB ("fix process hang",

**qa-signals.md** (7)

- `tools/qa-runner/qarunner.py:348-350` — the comment and NO_PAYLOAD still say field.ready carries no map id, while the client branch sends one (/root/work/client/src-170-qasignals/src/qamodule.cpp:522-524) and scripts wait on map_id (qa-scripts/b140-bot-heal-column.yaml:27).
- `tools/ops/client-mcp-win/src/driver.py:33-35` — qa_wait's tool schema enumerates only the four ready signals, while qapipe forwards any name and the runner waits on seven.
- scripts/build.ps1:11 — the shipped build script never passes -DSLUMS_QA_MODULE, so the QA client every qa-scripts/*.yaml needs cannot be produced by the documented build; it is a manual CMake configure today.
- the QA client is built from client-170-qa-signals (2026-10-31), which is not a descendant of client-164-rc3 and lacks its guards (git diff --stat = 39 files, +1379/−2819).
- config.example.ini has no [debug] section although src/uilayout.cpp:368 reads debug/uistate (and src/attackdiag.cpp:212 reads debug/attackdiag). The driver's self-heal depends on a key with no example entry and no doc.
- /root/work/client/src-170-qasignals/src/qamodule.cpp:226-228 — if the CUIWnd class test fails, window.open/window.closed are off and the only notice is an error event after 600 frames (10 s).
- /root/work/client/src-170-qasignals/src/qamodule_patch.h:24-39 — both sites are raw VAs tied to one MapleStory.exe sha256; only qamodule_sites (which needs QAMODULE_TEST_EXE) catches a drifted exe before a live run.

**resolution-and-d3d9.md** (8)

- NEW-FINDING: src/resolution.cpp:807-810 — a failed module pattern is written through as null.** GetAddressByPattern returns nullptr when GR2D_DX8.DLL is absent, unloaded or the pattern is not found (src/hook.cpp:149-153), and …
- NEW-FINDING: config.example.ini:2 ships [general] resolution=5, a key nothing reads in this build** (the reader is compiled out at mapleroot/ijl15/YourStuff.h:607), and its 1..5 scale would mean 1920x1080 while the live scale …
- NEW-FINDING: the two mode tables disagree on 1280x720.** The companion's dead table has it (mapleroot/ijl15/YourStuff.h:479-500, index 2), the live client's does not (src/resolution.cpp:661-676) — so any future re-enable of …
- `docs/PROJECT-STATUS.md:26` — NEW-FINDING: 1024x768 and 1600x900 have never been reported working.**  confirms only 800x600, 1366x768 and 1920x1080, and nothing in the patch table is mode-specific, so the risk is in the layout rather than the device path.
- NEW-FINDING: resolution has zero automated coverage** (see Tests and QA). 42 byte patches and 22 hooks in src/resolution.cpp are validated only by the fact that the client starts.
- NEW-FINDING: src/display.cpp:41-47 latches the pref once.** g_loaded makes LoadPrefs a no-op after the first call, so a registry edit made by anything other than this DLL (including a second client instance) is not seen until …
- NEW-FINDING: src/d3d9host.cpp:305 keeps the host alive only through the forced import.** g_pForceD3D9Import (:35-37) is the only reason d3d9.dll is loaded at DllMain; the guard at :304-309 degrades to "d3d9.dll is not loaded; …
- NEW-FINDING: the overlay's catch-all leaves ImGui mid-frame.** src/d3d9host.cpp:178-181 closes the window on an exception after NewFrame may already have run, and only logs once, so a recurring failure is invisible after the first line.

**ui-tweaks.md** (6)

- config.example.ini documents none of [clienthax] nativehud (src/hud_native.cpp:390, default **1**), the registry value damage_short (src/damagefmt.cpp:61), [debug] uistate (src/uilayout.cpp:368) or [debug] attackdiag …
- src/CMakeLists.txt:102-103 compiles weathermove_disabled.cpp, so the entire 278-line footing implementation in src/weathermove.cpp is dead code with no config key to reach it — the feature is neither "off by config" nor …
- src/dailycheckin.cpp:26 instructs "AttachDailyCheckinMod() is the single install entry — call it from your hook installer", but that symbol does not exist and no src/hook.h entry calls it; the window is created lazily by …
- src/invresize.cpp:675-706 forces narrow mode by patching the only m_bFull read (0x0081C4BE → xor eax,eax), so the stock 4-tab "full" inventory view is unreachable whenever resize is on — a deliberate-looking trade-off that is …
- MAPLEROOT_HUD_METERS and MAPLEROOT_DAMAGE_FORMAT are absent from scripts/build.ps1:14, so the EXP/DPS widgets and the short damage numbers — both shipped, both documented in README.md — are compiled out of the release build …
- src/hud_meters.cpp:34 keeps g_levelCounter as a stand-in for the real level, and :86 infers a level-up from next != g_lastNext && exp < g_lastExp; a GM level change or an EXP-table change without an EXP drop will be …

_229 findings indexed._
