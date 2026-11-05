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

## 0. Where the verdicts land

85 subsystem files, each with its own `## State` section. 62 of them name at least one `broken`, `partial` or
`unknown` area; the other 23 are working end to end as far as reading can tell. Counting a file once however many rooms
it has:

| System | files | files naming a non-working area |
|---|---|---|
| server | 27 | 24 |
| bots | 19 | 11 |
| client | 12 | 10 |
| launcher | 9 | 7 |
| website | 9 | 4 |
| ops | 9 | 6 |

"Working" is never runtime proof: nothing in this review started a server, client, launcher or site. It means the code
path is present, agrees with its own tests, and no reader found a break in it.

## 1. Broken

Paths that are reachable today and do the wrong thing.

| # | Where | What breaks | Evidence |
|---|---|---|---|
| 1 | Guild expel from a guildless player | `mc.getGuild().getAllianceId()` runs before the rank/guild guard, so packet 0x08 from a player with no guild throws instead of being refused | `src/main/java/net/server/channel/handlers/GuildOperationHandler.java:232` vs the guard at `:236-240`; `server/handlers-party-guild-alliance-family.md` |
| 2 | Party leader change with an offline leader | `party.getLeader().getPlayer()` is dereferenced where `Party.getLeader()` can return null | `src/main/java/net/server/world/World.java:1055`, `src/main/java/net/server/world/Party.java:203` |
| 3 | Incubator cash item | `if (i == id)` compares an array index to an item id, so no incubated item is ever found, the amount stays 0, and both the incubator and the target item are still consumed | `src/main/java/net/server/channel/handlers/UseCashItemHandler.java:771-778`, consume at `:274-277` |
| 4 | GM buyback (`TransactionService`) | the buyback flag is committed before the items are granted and the `catch` cannot revert it, so a partial failure burns the buyback | `src/main/java/server/transactions/TransactionService.java:108-120` |
| 5 | `!enableauth` | `unregisterLoginBypassEntry` builds a `Pair<String,Integer>` while the map is keyed `Pair<Hwid,Integer>`; `Pair.equals` has a `getClass` guard, so the removal never matches | `src/main/java/net/server/coordinator/login/LoginBypassCoordinator.java:77-81` vs `:49`, `src/main/java/tools/Pair.java:100-110` |
| 6 | Failed boot | a boot exception logs "Syntax error in 'world.ini'." (a file this repo does not have) and calls `System.exit(0)`, so systemd sees success | `src/main/java/net/server/Server.java:1003-1006` |
| 7 | Cash-shop gift stage | `CashShop.open(true)` is never called (only `false`, from map change) and `CashShopButtonTest` asserts that, so `isOpened()` is always false, `CashOperationHandler` always refuses, and `loadGifts` has no caller — the `gifts` table is orphaned | `src/main/java/server/CashShop.java:377`, `src/main/java/net/server/channel/handlers/ChangeMapHandler.java:194`, `src/main/java/net/server/channel/handlers/CashOperationHandler.java:68`, `src/test/java/server/cashshop/CashShopButtonTest.java:33` |
| 8 | Bot town "grinding" (b240) | `HELP_FRIEND` sets a training target from the player's map and coerces the mobless sentinel level `-1` to `1`, so the abstract-EXP guard `currentMobLevel > 0` passes in a town and the bot draws EXP for standing there; `OffscreenSupplies.accrue` then returns `1.0` on a null rate | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1085`, `:2141`, `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/MapMobIndex.java:43`, `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/OffscreenSupplies.java:84-90`; `bots/grind.md` |
| 9 | Client QA signals | the QA client is built from branch `client-170-qa-signals` (`9bc1458`), not from the 1.6.4 client line: `src-164-data` has no `src/qamodule*` and no `QaModule_` call site, and `scripts/build.ps1:11` never passes `-DSLUMS_QA_MODULE` | `/root/work/client/src-164-data` grep, `/root/work/client/src-170-qasignals` tip; `client/qa-signals.md` |
| 10 | QA worker batch (b286) | the event cursor advances over the whole fetched batch while `wait_signal` scans only that batch, so the second signal of a two-signal batch is missed even though it arrived | `tools/qa-runner/qarunner.py:382-389` vs `:476-494` |
| 11 | Site sessions outlive a ban | banning writes `accounts.banned` and the game refuses the login, but nothing writes `revoked.txt` (both trees only read it), so an issued site cookie keeps working for its 30-day life | `src/main/java/soloMapling/site/auth/SiteAccounts.java:116-131` (readers), `/root/work/SlumsMS-site-164-street/tools/site/login_service.py:40,74,811` (reader), `/root/work/SlumsMS-site-164-street/docs/site/LOGIN.md:38` claims a writer; `website/login-service.md` |

## 2. Partial

| Subsystem | What is missing or unproven | Evidence |
|---|---|---|
| server/boot-and-shutdown | shutdown sweeps login clients (ticket 62 unfixed); no test drives `init()` or an abort path; `Channel` swallows its own constructor exception | `server/boot-and-shutdown.md` |
| server/handlers-movement | no anti-cheat on `MOVE_PLAYER` (autoban distances exist, nobody adds a point); pet loot ignores ownership; `CHANGE_MAP` body catches everything and can leave the client without `enableActions` | `server/handlers-movement.md` |
| server/handlers-npc-shops | shop sell/rebuy edge paths; b289-class pricing fixed on this branch but not what live runs | `server/handlers-npc-shops.md` |
| server/handlers-inventory-items | pickup exists twice and the `@backpack` copy lost the self-loot, godly-drop and Ariant gates; incubator (broken above); keymap packet-edit defence commented out | `server/handlers-inventory-items.md` |
| server/handlers-quests | hidden quest chains unfinished by design; Maker quest accounting (b279) open; bot questing off by default | `server/handlers-quests.md` |
| server/handlers-trade-storage-merchants | merchant close is two non-atomic writes and swallows SQL errors; `HiredMerchant.buy`/`PlayerShop.buy` index listings unguarded; `DueyHandler` tests `TOSERVER_CLAIM_PACKAGE` twice so the second branch is dead | `server/handlers-trade-storage-merchants.md` |
| server/handlers-cashshop | window path dropped every base entry gate (GM block, event, MiniDungeon); `CashShop` factory NPE for a jobType outside 0/1/2; no test parses the window packet | `server/handlers-cashshop.md` |
| server/cashshop | client-facing flow unproven; gift delivery dead (broken above); live flag by inference only | `server/cashshop.md` |
| server/world-and-maps | 49 maps blocked, 247 map ids carry fatal-class findings, map life NPE kills a whole map load | `server/world-and-maps.md` |
| server/wz-data | provider layer works; the data does not (9,528-NPC/3,456-mob gap, 270 mob ids without templates) | `server/wz-data.md` |
| server/scripting | per-client engine cache never hits or shrinks (`"scripts/" + path` vs `path`); `scripting/field/*` unreachable; two reload methods have no callers | `server/scripting.md` |
| server/persistence | `saveCharToDB` returns void and logs failures only; cooldowns/pets save on their own connections outside the character transaction; `!saveall` always says success | `server/persistence.md` |
| server/mechanics | no live evidence: nothing 1.6.4-shaped has run on TEST; boss/Chaos Zakum wait on the owner's click-through | `server/mechanics.md` |
| server/economy | ticket 270 catalog is on TEST but the ticket header still says "not merged"; `economy.yaml` miss falls back to code defaults with an info line only | `server/economy.md` |
| server/companions | `OWNED_ALTS_ENABLED` is absent from 1.6.4's SERVER_SET while the code default is false, so `@botme` is dead on live; the conf contradicts itself on marriage EXP | `server/companions.md`, `server/config.md` |
| server/families | `family_character.lastresettime` is filtered on but never written; pedigree reads one channel only; buff clamp on the write path only | `server/families.md` |
| server/qa-and-autoqa | multi-lane runs still produce b284-class false failures; an infrastructure skip is reported as a normal skip | `server/qa-and-autoqa.md` |
| server/site-and-dashboard | `SiteMobKills.restore`/`flush` share the single site-export executor with `world.json`; per-map export skips existing files | `server/site-and-dashboard.md` |
| server/network-and-packets | the dispatch table is hand-written (177 registrations) and nothing cross-checks it against the opcode enum | `server/network-and-packets.md` |
| bots/decision-core | feasibility is checked before scoring but refused starts never feed back, so the mix drifts toward what the scorer likes and the host refuses; refusal counts exist nowhere | `bots/decision-core.md` |
| bots/tick-engine | the 100 ms driver and shutdown drain are tested, but watchdog/stuck-bot paths are not, and per-tick work is unbounded | `bots/tick-engine.md` |
| bots/training-bot | a town stay has no cap while a player keeps talking (`phaseDeadlineMs` is re-armed every tick and `BotConversation.holds` has no ceiling) | `bots/training-bot.md` |
| bots/chat | the model still contradicts its own state in production (b099, b101, b240 shipped-waiting); the decide cache is 30 min keyed on the normalised line only; `typingDelay` is dead; the "late" timing tail can exceed the QA cap | `bots/chat.md` |
| bots/decision-memory-learning | no test drives the memory store against a real MySQL; durability rests on a documented fallback | `bots/decision-memory-learning.md` |
| bots/observability | `!botwhy`'s preview gate is unreachable while the brain is on, and `bot-life.json` publishes `selected` and `chosen` without explaining the difference | `bots/observability.md` |
| bots/owned-alts | the master switch is off live (see `server/companions`); `OwnedAltWedding`'s "no bonus" comment is contradicted by `MarriageExpBonus` | `bots/owned-alts.md` |
| bots/party-and-expeditions | the general PQ lobby is off live; the party GRIND path enters the map without `BotMapCap.admit` | `bots/party-and-expeditions.md` |
| bots/quests-and-progression | refused turn-ins spend the day's budget (`recordRefused` increments `doneToday`); `RECOMMEND_MIN_SCORE` has no production caller | `bots/quests-and-progression.md` |
| bots/supply | `BotSupplies.shortage()` mutates state and a drifting copy lives in `LifeRuntime`; `SupplyNeeds.LOW_POTIONS` is disconnected from `BotShopping.hpGoal` | `bots/supply.md` |
| bots/affinity-bonds-gossip-memory | the boot-time reputation rebuild keeps only SCAM/GIFT/FAVOR/PQ/BOSS and KS notes, so FEELING grudges from drop steals, defamation and rude chat survive a restart only as lost data | `bots/affinity-bonds-gossip-memory.md`, `src/main/java/soloMapling/ArtificialPlayer/Reputation.java:660-664` |
| client/crash-guards | guards ship and are tested; the crash *report* half is empty (`InstallCrashReport()` is a no-op) | `client/crash-guards.md` |
| client/crash-report | the delivery link is off in shipped builds (`MAPLEROOT_CRASH_REPORT` OFF, absent from `scripts/build.ps1`) and `crash_diagnostics` writes the full module path without scrubbing | `client/crash-report.md` |
| client/resolution-and-d3d9 | 2 of 5 resolution modes never report; the Parsec `E_FAIL` the D3D9 host exists for is still unexplained; a null `GetAddressByPattern` goes into `PatchMemory` | `client/resolution-and-d3d9.md` |
| client/exe-and-starter | exe resource is a hardcoded `1.2.0.0`; the child's exit code collapses to 1; no test touches the starter | `client/exe-and-starter.md` |
| client/injector-and-hooks | every patch is a blind write — no exe hash or byte check before `PatchMemory`; four bypass writes are unconditional and have no ini key | `client/injector-and-hooks.md` |
| client/custom-wz-and-data-tools | `add_monster_book.py` drops every `Mob/` image it did not itself want, so running it after `add_mob_attack_type.py` erases that tool's leaves and the b251 fix; no doc states a run order; `resman` opens `Custom.wz` with no existence check | `client/custom-wz-and-data-tools.md` |
| client/build-and-tests | CI uploads paths from before the rename (`Kaentake.dll`/`.exe` under `build-baseline/Release`) while the build writes `slums.dll`/`SlumsMS.exe` under `build/`; `__TIME__` seeds make rebuilds non-reproducible | `client/build-and-tests.md` |
| client/ui-tweaks | weather footing is compiled out (`weathermove_disabled.cpp`), the EXP/DPS meters are not in `scripts/build.ps1`, and `[clienthax] nativehud` is undocumented | `client/ui-tweaks.md` |
| client/config-ini | one malformed line in `config.ini` reverts nine switches (the whole block is behind `ParseError() == 0`); `ChairOpacity`/`SkillOpacity` and `[general] resolution` have no live reader | `client/config-ini.md` |
| launcher/core-manifest-and-release | the launcher's build number (`major*10000+minor*100+patch`, so 1.6.4 = 10604) disagrees with the client's (`10740`); nothing authenticates a layer beyond its shape | `launcher/core-manifest-and-release.md` |
| launcher/game-config | a hand-edited `config.ini` keeps its own server (the merge is skipped), so a stale TEST origin survives a channel change; nothing refuses an RC layer reaching Live | `launcher/game-config.md`, `/root/work/launcher-165-server/tauri/core/src/install.rs:568-569` |
| launcher/install-and-packs | `PackSource.sha256` is shape-validated only; per-entry digests are the real check and are not pinned per channel | `launcher/install-and-packs.md` |
| launcher/play-and-launch | no game-exit detection; the single-instance lock covers the launcher only (the game's multi-client comes from the client's mutex rename) | `launcher/play-and-launch.md` |
| launcher/publishing | the publisher never writes `minClientBuild`; the only writer is a devtools fixture | `launcher/publishing.md` |
| launcher/site-and-account | `TEST_SITE` still points at the old live box (`http://192.168.0.164:8192`) while TEST moved to `192.168.0.111` | `launcher/site-and-account.md`, `/root/work/launcher-165-server/tauri/core/src/site.rs:18` |
| launcher/ui | renderer-level only; no automated UI test drives a real window | `launcher/ui.md` |
| website/board | the write limiter is in-process memory (lost on restart), a frozen board shows no freshness cue, and the in-game NPC path interpolates unescaped | `website/board.md` |
| website/deploy | `deploy-build-164.sh` copies trees and writes `DEPLOYED_COMMIT` — it does not build, publish or reload units; a `pages` rebuild renders in place | `website/deploy.md` |
| website/login-service | secret rotation is half-applied (game reads once, site per call) and a wrong secret surfaces as "Wrong username or password."; a site clock more than 5 min ahead gives a permanent `REAUTH` | `website/login-service.md` |
| website/market-service | health returns `{"ok": true}` and nothing checks it, and a stopped exporter still renders old prices as "as of …" with no staleness warning | `website/market-service.md` |
| website/report-service | no rotation on either side; the id scan reads a 256 KB tail so ids can repeat; `report_export` emits `accountId` while the staff reader hides it | `website/report-service.md` |
| ops/backups-and-restore | off-box copy is off by default and rotation defaults to dry-run; rotate and drill timers are unmonitored; `deploy-test`'s own per-stamp backups are never pruned | `ops/backups-and-restore.md` |
| ops/monitoring | `health-latest.json` is written and read by `tools/ops/test-status` but no monitor check reads it; `targets/live.conf` still advertises `VERSION=1.4.0` | `ops/monitoring.md` |
| ops/qa-runner | the b286 batch loss (broken above) plus no test that runs two signals in one batch | `ops/qa-runner.md` |
| ops/safety-guards | `hotfix-parity` is invoked by nothing; the `REHEARSAL.md` gate matches only the `smoke PASS` substring; `RELEASE_HOST` defaults to live | `ops/safety-guards.md` |
| ops/test-deploy | the deploy marker is written before a verified boot, the boot wait cannot fail, and nothing rolls back; `grep -q` with `pipefail` can false-negative | `ops/test-deploy.md` |
| ops/release-pipeline | the 1.6.4 conf is still a draft, WZ and `extra-steps.sh` are never rehearsed, and `release` never checks `REHEARSAL.md` | `ops/release-pipeline.md` |
| ops/client-and-launcher-publishing | the server-repo side is readable, but what each channel actually serves is on 164 (`/srv/slums-patch/*`), which this review may not read | `ops/client-and-launcher-publishing.md` |
| ops/hosts | the docs disagree with each other and with the scripts on the TEST dashboard host, the channel range, live's disk size and which compose file owns the live DB | `ops/hosts.md` |

## 3. Unknown

| Question | Why it cannot be answered here |
|---|---|
| What TEST actually passes to the JVM | `start-test.sh` lives on `mapletest`; this review may not reach it. Every "TEST" cell in the subsystem files says unknown for that reason. |
| What live's unit passes | the unit and `/etc/slums-*` are live's; `docs/release/1.6.4.conf` states SERVER_SET and UNIT_EDITS and `deploy/slumsms.service.example` is a template — where the two disagree the files say so |
| Whether live runs the cash-shop window path | `-Dcashshop.window.enabled` appears in no release conf; the example unit has it. Marked `[INFERENCE]` in `server/handlers-cashshop.md` |
| Whether `family_character.lastresettime` is NULL-able | the column is created by the stock schema, which is not in this repo; if it is NULL the daily DB reset never matches a row |
| Whether the fishing flags are on TEST | `tools/ops/feature-health.tsv:46` says on, `docs/HANDOFF.md:30` says off |
| Whether any client build in use matches the QA branch | the Windows boxes are out of scope; only the source branches are readable |
| What each patch channel serves | `/srv/slums-patch/{layers,tauri,launcher-rc,launcher-test}` is on 164; the tools that write it are readable, the content is not |
| How a human restore from backup is actually done | `deploy/backup/README.md:42-44` gives partial steps and `/root/work/PROJECT-STATUS.md:64-75` calls the drill unrehearsed for a real restore |
| Whether the monitor, backup and dashboard units are installed | the files are in `deploy/*/`, but every install is a host action nobody may run here |

## 4. Working

Not "verified in a game" — "read end to end and found consistent with its own tests". A file can appear here and in §2
for a different area of it; this list names the areas, not the files.

- **server**: `handlers-login`, `handlers-combat-damage` (the two main attack
  paths), `commands`, `achievements`, `checkin` (server side), `families` (main paths), `wz-data` (provider layer),
  `fishing` (server side; TEST unknown), `handlers-party-guild-alliance-family` (everything but the two broken rows).
- **bots**: `population`, `training-bot` (phase machine), `combat`, `grind`, `movement-and-travel`, `social`,
  `drama-guilds-couples`, `trade-and-merchants`, `gear-and-scrolls`, `quests-and-progression` (driver), `supply`
  (path), `affinity-bonds-gossip-memory` (live behaviour, minus the restart loss).
- **client**: `hpmp`, `config-ini`, `build-stamp-and-version`, `injector-and-hooks` (the patching itself),
  `ui-tweaks` (HUD, overlay, uilayout, damage format, invresize, bounce, weather sway, mob damage).
- **launcher**: `channels-and-auth`, `self-update-and-signing`.
- **website**: `build-and-checks`, `sections`, `market-service` (the page pipeline), `privacy-rules` (build-side
  scrubbing), `exchange-desk` (the file contract).
- **ops**: `hosts` (the map, minus its doc conflicts), `migrations-and-rollback`, `release-pipeline` (the tooling).

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
| 7 | The client README and `tools/release/README.md` still describe build-stamp format 1 | the proto header is format 2 (`/root/work/client/src-164-data/src/buildstamp_proto.h:24`) |
| 8 | `docs/site/LOGIN.md:38` says "the game server can write banned accounts there too" | no writer exists in either tree; only readers (see broken row 11) |
| 9 | The website build comments say "the market every 15 minutes" | the timers are 2 min (`/root/work/SlumsMS-site-164-street/tools/site/slums-site-market.timer:10`) |
| 10 | `deploy-test` derives the ledger id from the filename while migration 001 records a longer id | `tools/ops/deploy-test:108-110` vs `database/migrations/001-preserve-arcane.sql:9`, so 001's SQL re-runs every deploy (harmless: it self-checks) |
| 11 | `AGENTS.md:244` makes `hotfix-parity` mandatory before staging | nothing calls it (`ops/safety-guards.md`) |
| 12 | `docs/architecture/website/README.md:50` says the board has "posts and replies" | there are no replies in any table or view (`website/board.md`) |

## 6. Checked by this review, and what an agent got wrong

Everything above was read in the tree by the reviewer, not taken from an agent's summary. The entries here are the
claims whose *severity* an agent overstated, or which do not hold at all — recorded so nobody chases them:

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
- **Hired-merchant bot share**: an agent claimed the bot's cut is never paid in production because `BotShops.collect`
  has no caller. It does — `BotShops.townTick` calls it (`src/main/java/soloMapling/Economy/BotShops.java:140`) from the
  town stop (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1574`, `:1731`). What remains true is
  that `owed` is memory-only (`BotShops.java:59`, `:103`), so a restart before a town tick loses it.
- **Public board text scrubbing**: an agent reported unscrubbed titles. There is no free text on the board —
  `add_post` accepts only `kind`, `itemId`, `qty`, `price` and a session character name
  (`/root/work/SlumsMS-site-164-street/tools/site/market_service.py:434-453`). Nothing to scrub.
- **`GrindBreakRoutine.begin()` consuming the due flag on a skipped break**: deliberate, commented
  (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/GrindBreakRoutine.java:112-126`).
- **`SocialActions` actor/target guard**: `!ctx.actorFree() || !ctx.targetFree() && kind != ACCEPT && kind != DECLINE`
  (`src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialActions.java:258`) does what the flow says: the
  actor must be free, the target is exempt for accept/decline. An agent read it as inverted.
- **`MoveLifeHandler.inRangeInclusive`**: `!(pVal < pMin) || (pVal > pMax)` reduces to `pVal >= pMin`, so the upper
  bound is dead — but the call site guards the max separately (`src/main/java/net/server/channel/handlers/MoveLifeHandler.java:181`, `:83-84`), so no tolerant input is rejected.
- **`release-live.sh` rehearsal gate**: it is not blind — it matches the `smoke PASS` substring of the `Checks` line
  (`tools/release/rehearse-host.sh:262`); it just never checks the `**Result: PASS**` line the runbook means.
- **Website section rebuilds**: staged. A full build renders into `<out>.new-<pid>` and swaps
  (`/root/work/SlumsMS-site-164-street/tools/site/build.py:146-168`), `rebuild_section` stages and rolls back
  (`:178-212`), so a `world.build()` `rmtree` never touches the served folder; only the `pages` name renders in place.

## 7. Coverage gaps in this review

- Nothing was run: no test suite, no server, no client, no launcher, no site build. Every "works" is a reading of code
  plus whatever unit test the subsystem file names.
- TEST and live configuration could not be read (another host, and live is out of bounds for this review).
- Nothing was built on the Windows box, so no client DLL, launcher exe or WZ change was produced or checked.
- WZ data was checked for its readers, not its contents (no `wz/` in this checkout).
- The five `external/Detours/src/*.cpp` citations in `client/exe-and-starter.md` were read from the pinned submodule
  commit `9764cebcb1a75940e68fa83d6730ffaf0f669401`; the submodule is not populated in the client checkout.
- The mechanically generated index below quotes one sentence per finding. Where a finding needs its context, open the
  subsystem file it names.

## NEW-FINDING index

Every NEW-FINDING the subsystem files record, one line each, grouped by subsystem. `file:line` is
the citation from the subsystem file; open that file for the full reasoning. Nothing here is a fix
instruction — these are leads for Server Dev to verify.

### server

**achievements.md** (3)

- `src/main/java/soloMapling/achievements/AchievementService.java:144-146` — The bot branch of deliver updates cache.getOrDefault(id, new HashMap<>)…computeIfPresent(…). When the id is not cached (the normal case after forget, or after the cache.clear above) the write lands on a throwaway map and is …
- `src/main/java/soloMapling/achievements/Achievements.java:259-264` — Achievements.onLogout has no caller anywhere in src/main/java (grep -rn 'Achievements.onLogout' finds only the declaration), so the per-character cache entry is only dropped by the login path (forget at …
- `src/main/java/soloMapling/achievements/AchievementService.java:134-136` — deliver announces before the medal is actually granted, so the site feed can show an achievement whose medal is still owed (NO_ROOM).

**boot-and-shutdown.md** (5)

- `src/main/java/net/server/Server.java:1003-1006` — The world-start catch logs "[SEVERE] Syntax error in 'world.ini'." and calls System.exit(0). There is no world.ini in this repo (worlds are config.yaml), and exit code 0 tells systemd the run succeeded, so a world that fails …
- `src/main/java/net/server/Server.java:2192-2208` — A commented-out shutdown block is left in the method, including while (ch.getConnectedClients > 0) and a System.err.println("FUCK MY LIFE").
- `src/main/java/net/server/channel/Channel.java:167-169` — A channel constructor failure is swallowed with log.warn; the server reports "now online" with a missing channel and a port that is not listening, with no boot-failure signal own reading
- `src/main/java/net/server/Server.java:1025` — The online message still reads "Cosmic is now online after {} ms." while the boot banner says SlumsMS — the release-parity check in docs/release/1.6.4.conf:295 greps for "Starting world 0" in FORBID_LOG and "SoloMapling …
- `src/main/java/net/server/Server.java:1103-1129` — The JQ-door broadcaster is a raw new Thread with no reference kept, so shutdown cannot interrupt it; it survives into the restart path (instance = null; getInstance.init, src/main/java/net/server/Server.java:2250-2252) and a …

**cashshop.md** (5)

- `src/main/java/server/CashShop.java:377` — CashShop.open(boolean) is called once, always with false (src/main/java/net/server/channel/handlers/ChangeMapHandler.java:194), and src/test/java/server/cashshop/CashShopButtonTest.java:33 asserts open(true) is never called.
- 443-455), which depends on the physical column order of the live table; any column added out of order silently shifts sn/ringid.
- `src/main/java/net/server/world/World.java:1409` — 28-40), so the "suggested items" counters never see window sales; the comment says this is on purpose, but no switch exposes it.
- 30) has no production caller — only the Python tool mirrors its rules, so a WZ-only schedule change cannot reach the server without an nxcoupons row.
- which is dead code today (NEW-FINDING above).

**checkin.md** (7)

- `src/main/java/server/checkin/DailyCheckinRewards.java:169` — ignores the boolean returned by InventoryManipulator.addById (src/main/java/client/inventory/manipulator/InventoryManipulator.java:55-57).
- `src/main/java/server/checkin/DailyCheckinRewards.java:262` — credits NX through CashShop.gainCash (src/main/java/server/CashShop.java:347-364), which adds with no bound, while the ticket-113 path giveNxCredit explicitly refuses to pass Integer.MAX_VALUE (src/main/java/server/CashShop.java:339-345).
- `src/main/java/server/checkin/SlumsStamps.java:159-174` — prints "Check-In: day " + s.day using the *normalised* streak, so a player whose streak lapsed over 48 h is told "day 0/28" (normalized returns NEW at src/main/java/server/checkin/DailyCheckinStreak.java:26-27) even though the …
- `src/main/java/server/checkin/SlumsStamps.java:200-213` — exports every account with stamps > 0, including accounts that own GM characters and which therefore can never earn more (:68-80); a GM-granted balance leaks into the board file that the site reads.
- `src/main/java/server/checkin/DailyCheckin.java:52` — (lastRequestByAccount) grows one entry per account that ever sent a request and is only cleared for a QA rewind (:345) or a test seam (:352); logout does not remove the entry.
- `docs/tickets/17-daily-checkin-release.md:17` — says the window art is built by tools/wz/add_daily_checkin_art.py, but tools/wz/ does not exist in this repo (ls tools/ has no wz), so that release step is not reproducible from here.
- `src/main/java/soloMapling/DailyCheckin/daily-checkin-rewards.yaml:7` — documents a mesos key and the parser bounds it (src/main/java/server/checkin/DailyCheckinRewardsYaml.java:79), but no day in the shipped file sets it — the feature is dead config today, and …

**commands.md** (6)

- `src/main/java/net/server/channel/handlers/AdminCommandHandler.java:67-75` — case 0x01 (/d, clear inventory) has return; inside the slot loop, after the first iteration — it clears at most slot 1 and leaves the packet handler; the //TODO What is the point of this loop? next to it suggests this was never resolved.
- `src/main/java/net/server/channel/handlers/AdminCommandHandler.java:136-145` — The /kill mode iterates for (int x = 0; x < amount; x++) monsterx.get(x) over the map's monster list without bounding amount; asking for more than exist throws IndexOutOfBoundsException, swallowed by …
- `src/main/java/client/command/CommandsExecutor.java:368` — gachalist is registered with rank 0 inside registerLv2Commands, while its class sits in commands/gm2/ — so @help lists it under "Common" and players keep it after a rank change.
- `src/main/java/client/command/CommandsExecutor.java:444-445` — gacharoll / gachasim (classes in commands/gm4/) are registered at rank 3 inside registerLv3Commands, so GM 3 can roll and simulate the gachapon; the directory convention says 4.
- `src/main/java/client/command/CommandsExecutor.java:242` — invite is a rank-0 registration of a gm3 class; the GM branch is decided *inside* InviteCommand.execute by player.gmLevel >= GM_LEVEL (src/main/java/client/command/commands/gm3/InviteCommand.java:33 and:46, GM_LEVEL = 3 at …
- `src/main/java/client/command/CommandsExecutor.java:373-505` — Feature-gated registrations read system properties at construction time (the singleton is built at src/main/java/net/server/Server.java:1028), so flipping -Dslums.qa/-Dslums.autoqa/-Dweather.enabled requires a restart; …

**companions.md** (3)

- `src/main/java/config/ServerConfig.java:103` — OWNED_ALTS_ENABLED (config.yaml) false absent from SERVER_SET — see NEW-FINDING 1 the master switch; gates @botme entirely (requireEnabled, src/main/java/soloMapling/companions/OwnedAltService.java:144-146) and the bot-friend …
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

- `src/main/java/soloMapling/Economy/exchange/Exchange.java:669-671` — refuses NX orders *before* the idempotency lookup at:677. Once the desk is switched on, a retried request id for an NX order placed before the cutover returns BAD_REQUEST ("the NX book is off") instead of the recorded order …
- `src/main/java/soloMapling/Economy/exchange/Exchange.java:1493-1496` — logs "desk step not stored" and keeps the new R in memory only. The stored R then diverges from the live R for an unbounded time, and a restart silently reloads the older value (:562-570) with no halt — unlike an unreadable …
- `src/main/java/soloMapling/Economy/exchange/Exchange.java:2051-2066` — swallows StoreException from store.accountsWithPayout and returns the count so far with no log line, so a store failure during the payout sweep is silent; the same class of failure in tickDesk (:1486-1490) is logged.
- `src/main/java/soloMapling/Economy/exchange/ExchangeSite.java:474` — the site is shown two different fee numbers for the same NX trade. ExchangeSite.tickers publishes fee_bps_each_side from ExchangeConfig (, default 100 at src/main/java/soloMapling/Economy/exchange/ExchangeConfig.java:59-63), …
- `src/main/java/soloMapling/Economy/exchange/ExchangeService.java:76` — ,:192 rebuild exchange-tickers.json on every poll (15 s by default) by serialising every open ticker with 10 levels and 20 trades (src/main/java/soloMapling/Economy/exchange/ExchangeSite.java:487-489).
- `src/main/java/soloMapling/Economy/EconomyConfig.java:29` — the numbers that steer bot pricing can silently disappear. names src/main/java/soloMapling/Economy/economy.yaml and:57-68 catches every failure of that read, so a resolve miss leaves every price band, shop markup and sink at …

**families.md** (4)

- `src/main/java/net/server/task/FamilyDailyResetTask.java:38` — family_character.lastresettime is filtered on but never written. does UPDATE family_character SET todaysrep = 0, reptosenior = 0 WHERE lastresettime <= ?, and the only other mentions of the column in the whole tree are the …
- `src/main/java/net/server/channel/handlers/OpenFamilyPedigreeHandler.java:37-40` — the pedigree window only sees one channel. OpenFamilyPedigreeHandler.handlePacket resolves the name with c.getChannelServer.getPlayerStorage.getCharacterByName , so a family member standing in another channel is reported as …
- `src/main/java/net/server/channel/handlers/FamilyPreceptsHandler.java:24` — the family message never reaches the other members. FamilyPreceptsHandler writes the new text but the broadcast is commented out (:25), so only the leader's own window updates until someone reopens it (getFamilyInfo carries …
- `src/main/java/client/Family.java:373-388` — Family.loadAllFamilies links a junior to a senior only within the same load pass; a row whose senior has already been loaded but whose family had no leader is repaired memory-only and logged, so the repair is lost on the next …

**fishing.md** (4)

- `src/main/java/constants/id/ItemId.java:65-68` — The repack flipped isChair to the alternative body: the range check is commented out at:66 and the labelled statement alt: return itemId / 10000 == 301; at:67 is the live one.
- `src/main/java/soloMapling/fishing/GameFishing.java:113` — Achievements.onFish is called for bots too (the counter branch above it distinguishes bot from player, this line does not), and Achievements.onLogin explicitly skips bots …
- `src/main/java/soloMapling/fishing/FishingSessions.java:52` — start rejects only now < 0 and overflow; a clock that goes backwards between start and the first tick leaves s.last ahead, and the tick at src/main/java/soloMapling/fishing/FishingSessions.java:101 cancels the session.
- `src/main/java/soloMapling/fishing/GameFishing.java:16` — The 256-session cap is a single process-wide static shared by players and bots; 104 of the first 3,000 bot slots own a chair (docs/tickets/92-fishing.md:62) and the bot roster is ~3,000, so a bot-heavy fishing hour plus real …

**handlers-cashshop.md** (7)

- `src/main/java/net/server/channel/handlers/EnterCashShopHandler.java:41-45` — NEW-FINDING: The window path skips every stocking gate the stock handler had — no cannotEnterCashShop, event, or MiniDungeon check, and no isOpened check.
- `src/main/java/net/server/channel/handlers/CashShopSurpriseHandler.java:38` — NEW-FINDING: Guarded by cs.isOpened, which the window path never sets, so the packet is dead on live. CashShop.openCashShopSurprise (src/main/java/server/CashShop.java:560) is reachable only through the dead stage, and item …
- `src/main/java/net/server/channel/handlers/MTSHandler.java:58-65` — NEW-FINDING: 852 lines of unreachable MTS code retained in the tree with DB writes (mts_items INSERT/UPDATE/DELETE at:135,:151,:253,:323).
- `src/main/java/net/server/channel/handlers/CashShopWindowHandler.java:121-123` — NEW-FINDING: A short p.available < 4 single buy returns silently — no buyResult, no enableActions. The cart path answers BUY_BAD_CART for the equivalent malformed case (:145-150), so the two disagree; a crafted short packet …
- `src/main/java/net/server/channel/handlers/CouponCodeHandler.java:260-268` — NEW-FINDING: The 255 cash-item cap silently drops items beyond 255 (cashItems = Arrays.asList(new Item[255]) discards the tail) *after* the code has been claimed, so a big reward is unrecoverable.
- `src/main/java/net/server/channel/handlers/CashOperationHandler.java:114` — NEW-FINDING: //TODO check for gender on the gift action 0x04 — a gift can be sent for a wearable the recipient cannot wear, unlike the window path which refuses with BUY_CANNOT_WEAR (CashShopServices.check).
- `src/main/java/server/CashShop.java:269-283` — NEW-FINDING: factory stays null for any jobType outside 0/1/2 when USE_JOINT_CASHSHOP_INVENTORY is false, and factory.loadItems(...) at:311 then NPEs — so the character cannot load.

**handlers-combat-damage.md** (4)

- `src/main/java/net/server/channel/handlers/MobDamageMobFriendlyHandler.java:79-91` — kills the friendly twice. map.killFriendlies(monster)
- `src/main/java/net/server/channel/handlers/MonsterBombHandler.java:41-52` — never checks HP or ownership. Any client can send MONSTER_BOMB
- the damage-line-count check is dead.
- `src/main/java/net/server/channel/handlers/MagicDamageHandler.java:63` — reads + +YamlConfig.config.server.DOJO_ENERGY_ATK — a doubled unary

**handlers-inventory-items.md** (6)

- 771 if (i == id) compares the array index with the item id, so getIncubatedItem(c, 5060002) (:274) never matches its 20-entry table and amount stays 0 (:769-774); addById(c, id, (short) 0) (:778) then grants a zero-quantity …
- 571 a second else if (itemType == 552) { //DS EGG THING is unreachable: 552 is already handled at:558, so the "DS EGG THING" branch is dead code and no DS-egg behaviour exists.
- `src/main/java/client/command/CommandsExecutor.java:246` — 2656-2827 modifiedPickupItem (reached by any player through @backpack on, → src/main/java/client/command/commands/gm0/BackPackToggleCommand.java:41) is a hand-copied fork of originalPickupItem that dropped three gates: …
- 605-608 refusing a Vega cast answers getInventoryFull — a wrong packet for "the equip has no upgrade slots", which the client renders as a full inventory.
- 50-56 a banned / GM-only / off-job skill bind hits the combined condition at:50 and is silently continued at:56 with the autoban, the FilePrinter line, the disconnect and the return all commented out (:51-54), so a …
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
- `src/main/java/client/autoban/AutobanFactory.java:48` — AutobanFactory.PORTAL_DISTANCE and DISTANCE_HACK (:47) exist but no movement handler calls addPoint/alert, so a client can report any position and only MovePlayerHandler's silence difference is observable
- 153-155 — generateFloatMovementCommand is an empty stub with no callers; dead API surface
- 183 vs /root/workspace/cosmicms/...:174 — our parser skips 4 bytes (wobble) then reads a foothold; upstream skipped 6. Both agree on the total stride only if the foothold is genuinely present in this client build; a wrong …
- 46 — the VIP rock resolves its target with getMapFactory.getMap(map_id) and never calls getWarpMap/MapRouting, so a blocked map id that the table would reroute is instead refused as "Map is invalid." (safe, but inconsistent …
- 176-178 — the whole body catches Exception and prints a stack trace, so a malformed CHANGE_MAP leaves the client with no enableActions and a player can appear frozen until the client times out

**handlers-npc-shops.md** (9)

- 85-88 a scriptless NPC with no shop logs and returns without enableActions, leaving the client frozen. classifications.json records this exact hazard for scripts/npc/2110000.js; the return is what makes it lethal
- 155-171 the pitch branch has no else: a player short of Perfect Pitch Leaves gets no packet at all and the shop window waits
- `src/main/java/server/Shop.java:139-158` — 184-195 the token branch sends (byte) 3 (inventory full) inside the else and then (byte) 0 unconditionally at:195, so a failed token purchase also reports success. Identical in base — inherited
- 359-361 createFromDB swallows SQLException with printStackTrace and returns the half-built shop, so a failing shopitems query yields a valid shop with zero stock (and negative-caches it)
- `src/main/java/scripting/npc/NPCConversationManager.java:2451-2462` — 96-100 negative caching means a shop row added while the server runs (e.g. addShopToDb, ) is invisible until reloadShops
- 53,60,66 no null check on getPlayer.getShop: a client that sends NPC_SHOP without an open shop NPEs the handler. Base-identical
- 65 (byte) p.readShort truncates the recharge slot; slot 128+ becomes negative. Base-identical
- 240-242 Math.random < (dropRate / (double) mde.chance) treats chance as a denominator, but the SQL filter is only chance >= 0, so a reactordrops row with chance = 0 always drops
- 17-57 sanitizeReagentQuantities is entirely commented out, so Maker reagent packet-edit checks are dormant

**handlers-party-guild-alliance-family.md** (7)

- 232 reads mc.getGuild.getAllianceId *before* the rank/guild guard at:236, so a crafted 0x08 from a sender with no resolvable guild throws NPE instead of being refused. 0x07 was given the guard first (:211-216) and 0x08 was not.
- 49-152 has no default branch — an unknown operation byte is dropped with no enableActions, which leaves the client waiting. The b298 refusals do send it (:139).
- 66-67 returns silently when the sender is not the party leader (or has no party) and sends no enableActions, unlike every other refusal in this subsystem.
- `src/main/java/net/server/world/Party.java:192-205` — 1055 Character mc = party.getLeader.getPlayer; is null-checked at:1056, but Party.getLeader itself returns null when leaderId matches no member , and that call is not guarded — a party whose leader row is missing NPEs inside …
- 72 builds "SELECT * FROM guilds WHERE guildid = " + guildid by concatenation while every other statement in the class is parameterised; the id is an int so it is not injectable today, but the pattern invites the next copy to be.
- 105 setLeader takes neither the party lock nor a membership check; only World.updateParty's CHANGE_LEADER branch calls it, so the safety is entirely in the caller.
- `src/main/java/net/server/channel/handlers/GuildOperationHandler.java:232` — Guild expel (0x08) from a guildless sender broken NEW-FINDING at 

**handlers-quests.md** (6)

- `src/main/java/server/quest/Quest.java:169-179` — 34-37, 
- `src/main/java/server/quest/actions/QuestAction.java:58-62` — 54-93, 
- `src/main/java/server/quest/Quest.java:609` — 45-46,:87-89, 
- 49-50
- 88-92,:121-123
- `src/main/java/server/quest/requirements/InfoExRequirement.java:58-60` — 49-51, , src/main/java/server/quest/requirements/ScriptRequirement.java:45-46, src/main/java/server/quest/Quest.java:513-516,:559

**handlers-trade-storage-merchants.md** (6)

- `src/main/java/server/Storage.java:256-258` — Storage.sendStorage fills the per-type view with the *whole* item list for every InventoryType — for (InventoryType type: InventoryType.values) typeItems.put(type, new ArrayList<>(storageItems)); …
- `src/main/java/server/maps/HiredMerchant.java:328` — HiredMerchant.buy reads PlayerShopItem pItem = items.get(item) with no bounds check [](src/main/java/server/maps/HiredMerchant.java:328); a stale or crafted slot index would surface as an IndexOutOfBoundsException in the …
- `src/main/java/net/server/channel/handlers/DueyHandler.java:58` — DueyHandler repeats the TOSERVER_CLAIM_PACKAGE test after the remove branch, so the second else if at [](src/main/java/net/server/channel/handlers/DueyHandler.java:58) can never execute — dead branch that looks like a lost …
- `src/main/java/server/transactions/TransactionService.java:108` — TransactionService.processTransaction commits the buyback flag before granting anything — if (updateTransactionBuybackStatus(transaction.getTransactionId, true)) …
- `src/main/java/net/server/channel/handlers/WeddingTalkMoreHandler.java:36` — WeddingTalkMoreHandler performs its effect with no groom/bride or state guard at all — it writes the grid slot and sends OnWeddingProgress(true, 0, 0, (byte) 3) whenever the sender is not the couple …
- `src/main/java/client/processor/npc/StorageProcessor.java:50-53` — the ore-storage branch in StorageProcessor.storageAction returns immediately on getUsingOreStorage [](src/main/java/client/processor/npc/StorageProcessor.java:50), but no OreBag class exists in the tree — the path is inert and …

**mechanics.md** (2)

- src/main/java/soloMapling/mechanics/QueueStaging.java (331 lines, added 1f7271226 2026-11-03) has no
- runChatter's outcome ring is process-lifetime only — RunOutcomes javadoc says "Not durable yet. The ring

**network-and-packets.md** (7)

- `src/main/java/net/packet/logging/OutPacketLogger.java:25` — ctx.write(msg) drops the caller's ChannelPromise, so Client.sendPacket's writeAndFlush (src/main/java/client/Client.java:1646) never completes and outbound failures are invisible while logging is on.
- `src/main/java/client/autoban/AutobanFactory.java:96` — ,:109 With USE_AUTOBAN false (the shipped default, config.example.yaml:230) every alert/autoban call is a no-op and only USE_AUTOBAN_LOG lines remain, so packet-edit detection produces log noise and nothing else.
- `src/main/java/net/PacketProcessor.java:227` — MTS_OPERATION is unregistered while RecvOpcode.MTS_OPERATION is still declared and is listed in Client.PLAYER_ACTIONS (src/main/java/client/Client.java:330); a client still sending it is silently dropped.
- `src/main/java/net/encryption/MapleAESOFB.java:155` — getPacketLength has no production caller — PacketDecoder uses its own decodePacketLength (src/main/java/net/encryption/PacketDecoder.java:43-47), so the header-length rule exists twice and only one copy is tested.
- `src/main/java/client/Client.java:1301` — copyClientStampFrom has no caller; it was superseded by ClientStampCarry (src/main/java/net/server/coordinator/session/ClientStampCarry.java:37). Dead twin of the live carry path.
- `docs/release/1.6.4.conf:10` — vs:284 The header still says "No new client: MIN_CLIENT_BUILD stays 10721" while:284 assigns 10740 and:282-283 records the owner decision behind it. The file contradicts itself; treat:284 as current.
- `src/main/java/net/netty/ServerChannelInitializer.java:33` — sessionId is a single static AtomicLong shared by the login server and every channel, so channel session ids are not comparable across restarts and are only monotonic inside one JVM.

**persistence.md** (8)

- `src/main/java/net/server/guild/Guild.java:198` — NEW-FINDING Guild.writeToDB catches SQLException and only prints, so a failed guild save is invisible to the caller — the same swallow b294 fixed on the storage side.
- `src/main/java/client/Character.java:10324-10328` — NEW-FINDING Character.saveCharToDB(boolean) returns void and swallows every error, so !saveall reports "All players saved successfully." without proof. ; src/main/java/client/command/commands/gm6/SaveAllCommand.java:40-48.
- `src/main/java/client/Character.java:9625-9670` — NEW-FINDING The logout writes are split across connections: saveCooldowns (cooldowns + playerdiseases) and Pet.saveToDb each take their own connection and swallow errors, so a successful character commit does not imply the …
- `database/migrations/002-classic-fm-return.sql:2-3` — NEW-FINDING 002-classic-fm-return.sql is the one forward migration that does not create the ledger table, so it cannot be applied to a database where 001 has not run (or the ledger was rebuilt): its INSERT IGNORE fails and the …
- `src/main/java/net/server/PlayerStorage.java:113-131` — NEW-FINDING PlayerStorage.disconnectAll logs a throwing disconnect and then still clears the registry, so a character whose teardown failed leaves the world with no record that it was not saved — nothing retries it. .
- `src/main/java/server/maps/HiredMerchant.java:761-781` — NEW-FINDING Merchant close is two non-atomic writes on two connections: the items (ItemFactory.MERCHANT.saveItems), then fredstorage; a crash between them leaves items saved with no Fredrick log.
- partial for the swallow-class writers listed as NEW-FINDING (guild, cooldowns, diseases, pets, merchant close) — they
- Whether any of the swallow sites in the NEW-FINDING rows has a real player-visible incident. Nothing in STATUS, the

**qa-and-autoqa.md** (6)

- `src/main/java/soloMapling/qa/QaLog.java:76` — The comment // a long: 34 topics no longer fit an int is stale: the enum holds 45 constants (OWNED_ALT at:28 … PETPOT at:73); the mask is still a single long, so this is documentation drift only.
- `src/test/java/soloMapling/autoqa/AutoQaSchedulerTest.java:251-253` — The guard that catches undeclared shared state knows only five literal patterns (MANNERS TALLY WEALTH TRADE SHOP clumpsOnMyMap) and matches them against step *descriptions*; a new probe that reads map-wide lines under another …
- `src/main/java/soloMapling/autoqa/AutoQaRunner.java:211-247` — + src/main/java/soloMapling/autoqa/AutoQaScheduler.java:86-115 — A WORLD check runs only when busy is empty (canStart, src/main/java/soloMapling/autoqa/AutoQaScheduler.java:74-79) while pick always takes the first pending …
- `docs/ops/qa-suite.md:949` — The doc says the lane switch can be set as AUTOQA_PARALLEL "in a release conf", but no such ServerConfig field exists (src/main/java/config/ServerConfig.java:204-213 lists every AUTOQA_* key) and AutoQaScheduler.parallel reads …
- `docs/tickets/167-parallel-autoqa.md:43` — The ticket records the cap as "clamped to 1..4"; the code clamps to SyntheticPlayers.MAX_LANES = 10 since 1.6.3 (src/main/java/soloMapling/autoqa/AutoQaScheduler.java:41, …
- `src/main/java/soloMapling/autoqa/AutoQaRunner.java:355-400` — A check whose expectations all SKIP is reported SKIP with reason nothing could be judged here, and a release run then fails it unless it is on the allow-list — an infrastructure skip (server not fully up, probe disabled) is …

**scripting.md** (9)

- `src/main/java/scripting/AbstractScriptManager.java:72-75` — NEW-FINDING: — the cache read key is "scripts/" + path but the store key is path, so Client.getScriptEngine always misses and every interaction re-evaluates the script; resetContext (:91-93) removes a key that was never …
- `src/main/java/scripting/portal/PortalScriptManager.java:46-59` — NEW-FINDING: — isScriptAvailable evaluates the whole file (no cache) and is called once per candidate portal while PortalFactory builds a map (src/main/java/server/maps/PortalFactory.java:72-79); a map with WZ-scripted portals …
- `src/main/java/scripting/portal/PortalScriptManager.java:40` — NEW-FINDING: vs:108 — portal engines are cached in a static HashMap keyed only by script path and shared by every client, but portal scripts are *not* wrapped in SynchronizedInvocable, unlike event scripts …
- `src/main/java/net/server/channel/Channel.java:80` — NEW-FINDING: src/main/java/scripting/field/ — the whole package is dead: FieldScriptManager's constructor is empty (:51-53), nothing constructs FieldScriptManager or FieldInstanceManager, Channel.fieldScriptManager is never …
- `src/main/java/scripting/npc/NPCScriptManager.java:54` — NEW-FINDING: — ess is constructed and never used;:59-60 — spawnItemEventNpc(int) has an empty body and no callers.
- `src/main/java/scripting/map/MapScriptMethods.java:183-192` — NEW-FINDING: — registerPlayerForMonsterWeakeningEventInstance asks for an event manager named "MonsterWeakening", but no scripts/event/MonsterWeakening.js exists and no script calls this method or weakenAllMonstersOnCurrentMap …
- `src/main/java/scripting/item/ItemScriptMethods.java:30` — NEW-FINDING: — no src/main code references the class (item scripts get a NPCConversationManager as im); only src/test/java/scripting/OreBagScriptTest.java:34 uses it.
- `src/main/java/scripting/map/MapScriptManager.java:45-47` — NEW-FINDING: and src/main/java/scripting/quest/QuestScriptManager.java:208-211 — the reload methods have no callers (only reloadEventScriptManager, EventScriptManager.reloadScripts and reloadPortalScripts are reachable), so a …
- `src/main/java/scripting/event/EventScriptManager.java:69-75` — NEW-FINDING: — an unknown event name returns fallback.em, and fallback is null when 0_EXAMPLE.js failed to load or was not in the list (the offline branch at src/main/java/net/server/channel/Channel.java:150-153 loads only …

**site-and-dashboard.md** (3)

- `src/main/java/soloMapling/dashboard/DashboardExporter.java:202-203` — writes calibration.json into the dashboard data folder only when KillCalibration.enabled, while deploy/dashboard/slums-dashboard-extras@.service and the web app never reference that file (tools/dashboard/web/app.js has no …
- `src/main/java/soloMapling/dashboard/DashboardMapExport.java:306` — skips a map whose maps/<id>.json already exists, and nothing in the file re-exports it when the nav graph version changes unless --force is passed; the map signature at:165 covers graphs only, so stale maps/*.json survive a WZ …
- `src/main/java/soloMapling/site/PublicSiteExport.java:98-102` — restores and flushes site_mob_kills on the same single-thread executor that writes the three JSON files; a slow or blocked MySQL round trip inside SiteMobKills.restore/flush delays world.json/feed.json, and the 60 s world …

**world-and-maps.md** (5)

- `src/main/java/server/maps/MapFactory.java:391-403` — 391-393 uses LifeFactory.getLife(id, type) without a null check and immediately calls myLife.setCy(cy); an unknown life type (LifeFactory logs "Unknown Life type") or a mob with no Mob.wz image NPEs the whole map load instead …
- `src/main/java/net/server/channel/Channel.java:205-210` — 207 nulls mapManager during shutdown while getMapFactory at:265 returns it with no null guard, so any task or script that runs between "dispose its maps" and "stop its network service" dereferences null instead of failing with …
- `src/main/java/server/maps/MapleMap.java:3192-3194` — 3193 starts removePlayer with chr.getClient.getChannelServer; a character whose client is already detached (logout/DC path) NPEs inside map removal. 
- 4413-4417,:4446-4450
- loads) or to fail the whole map: the code does neither by design — it NPEs (see NEW-FINDING above).

**wz-data.md** (5)

- `src/main/java/provider/wz/WZFiles.java:41-45` — a typo in -Dwz-path (or a non-directory) is ignored and "wz" is used instead , so the server can boot against a different data tree with no log line
- `src/main/java/client/SkillFactory.java:342-343` — 66-83): SkillFactory.getSkillName re-parses the whole String.wz/Skill.img.xml on every call , and GameConstants.getMaxObstacleMobDamageFromWz parses every Obj image at class-init …
- `src/main/java/server/maps/MapFactory.java:156-161` — getData returns null for an absent file (:70-72) but throws RuntimeException if the same file disappears between the check and the open (:77) — callers such as only handle null
- `src/main/java/client/DamageSkin.java:17-19` — the only reader is DamageSkin.loadDamageSkins and its call is commented out (src/main/java/net/server/Server.java:1050, src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:239); WZFiles.LIST has no reference …
- 379-382), so a malformed back node costs visual layers with no log entry

### bots

**affinity-bonds-gossip-memory.md** (7)

- `docs/release/1.6.4.conf:36-38` — still says -Dslums.bonds=true is "OFF by default … TEST first, owner turns on", while UNIT_EDITS at:271 already ships it on.
- `src/main/java/soloMapling/ArtificialPlayer/Reputation.java:660-665` — Reputation.LOAD rebuilds boot-time marks from Kind IN ('SCAM','GIFT','FAVOR','PQ','BOSS') OR Note = NOTE_KS. A defame, a stolen drop or a rude-chat grudge writes NOTE_DEFAME/NOTE_STEAL/NOTE_RUDE …
- `src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/AffinityRules.java:87` — AffinityEvent.APOLOGY has delta 30 and cooldown 0 (BotAffinitySystem/AffinityEvent.java:24), and FRIEND needs only score ≥ 25 with kind ≥ 3 .
- `src/main/java/soloMapling/ArtificialPlayer/BotGossip.java:344` — the minute gossip round (BotGossip.step:95-176) passes notes along bonds without consulting SocialGate.Act.GOSSIP, while the story path does consult it (,695 via the gossipInFront seam:61).
- `src/main/java/soloMapling/ArtificialPlayer/BotGossip.java:196-209` — BotGossip.warn always nudges the listener with NOTE_KS whatever the subject actually did; a scam and a KS produce the same hearsay record, so the listener's score is indistinguishable and BotMemory never learns which …
- `src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/BotAffinity.java:407-410` — BotAffinity.record silently drops every event for a player whose ties are not loaded (, comment "they just logged in"). The only load triggers are map entry (src/main/java/server/maps/MapleMap.java:2856) and login …
- AxisColumns.Availability caches a "no" for 10 minutes (BotAffinitySystem/AxisColumns.java:37,99-130). Between applying 062 and the next probe, bond writes go through the legacy UPSERT and the new axes of those rows are written …

**chat.md** (5)

- `src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/OpenAICompatibleBrain.java:62` — lastCallByPlayer
- `src/main/java/soloMapling/ArtificialPlayer/BotChatActions.java:125-131` — the life-runtime party deferral
- `src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/ChatDecide.java:416-421` — the decision cache key is
- `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1574` — typingDelay(...) is defined but has
- `src/main/java/soloMapling/ArtificialPlayer/BotConvo/HumanTiming.java:84-87` — the "late" plan picks

**combat.md** (5)

- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:456-458` — the QA
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackDriver.java:472` — the gate is
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotMeleeRange.java:55-58` — hasRange
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotAttackTiming.java:41-49` — the delay
- `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/BotClientPoses.java:20` — the header

**decision-core.md** (4)

- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeTownSlots.java:12` — LifeTownSlots.reserve counts bots *in flight* (incoming, ,:26) while the
- the classifier between them is only explained in a source comment, and bot-life.json writes both
- no counter for gate-B refusals exists anywhere (LifeAcceptance.Status has 7 values,
- a fifth of the catalogue is unreachable through the scorer; harmless, but it makes the option list

**decision-memory-learning.md** (6)

- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemoryStore.java:206-238` — write returns
- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/ModeSwitch.java:301` — switchesLastHour rings
- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:173-197` — readLearning caps the
- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeLearning.java:409-449` — sweep reads
- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/TownPulse.java:48-56` — every town counter is a
- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeTownLife.java:111-115` — an accepted switch

**drama-guilds-couples.md** (4)

- `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/RomanceWedding.java:293-296` — an unanswered proposal is auto-accepted after 40 s (WeddingTakenAsYes), so a player who walks away from the altar marries the bot without ever saying "i do"; the runaway beat is the only escape and it is rolled at 8%.
- `src/main/java/soloMapling/ArtificialPlayer/BotCoupleSystem/BotCouples.java:449-474` — the wedding window is only reachable from the 60 s couples step and requires curveFraction >= 0.5, so bot weddings silently stop happening whenever the world event curve is below half, with no switch to override it.
- `src/main/java/soloMapling/ArtificialPlayer/BotDramaSystem/BotDrama.java:134-137` — replyAllowed returns true whenever the drama package is disabled, so -Dsolomapling.drama=false also removes the @romance off content filter from model replies.
- `src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:110-127` — the comment records that the old slums.family.acceptChance 80% roll is gone and a familiar player is now always accepted, so the documented accept-chance knob in AGENTS.md-adjacent docs no longer has any effect if still set.

**gear-and-scrolls.md** (7)

- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/ScrollingBot.java:90-95` — the regeneration loop for (int tries = 0; itemsToScroll.isEmpty; tries++) removes godly pieces only while tries < 20; if every generated piece stays godly the loop never terminates (unbounded generation on the bot thread).
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/ScrollingBot.java:32` — ,37,97,166 — listIndex is initialised to 0 and never incremented anywhere in the file, so a ScrollingBot scrolls and advertises itemsToScroll.get(0) forever; the other generated items are dead weight.
- `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:602-655` — buyAtNpc breaks only the per-listing purchase loop when buyOne fails (inventory full or budget refused) and then keeps scanning the remaining listings of the same shop, recomputing need from the same unchanged inventory each …
- `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:692-750` — nextStepAt is advanced to now + 4–8 s at the top of the try block (:703-707), but the catch (RuntimeException) at:747 only logs: a bot whose scroll path throws every time retries at the 4–8 s pace instead of the IDLE_MS=60_000 …
- `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:326-350` — curveFor calls curveSpecs(e, fx) (which resolves a market price per candidate scroll via ScrollMarketPrice) *before* the curves.get(k) cache lookup, so a cache hit still pays the full price resolution and key building; the …
- `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGear.java:576-600` — dexlessSecondaryTarget returns -1 ("no cap") for magicians and on *any* error, so an item-data miss silently disables the AP cap rather than keeping the previous one; the failure mode is invisible except in the log.
- `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGearPlanner.java:167-179` — the pool is sorted by required level then raw stat sum and truncated at MAX_PER_SLOT=6, before the weapon/skill rule or the Valuer runs, so a genuinely better high-requirement piece can be dropped from consideration purely on …

**grind.md** (5)

- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1085` — NEW-FINDING: — setTrainTarget(player.getMapId, Math.max(1, MapMobIndex.level(...)))
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1917-1923` — NEW-FINDING: +:1866-1872 — the party map reaches enterPhase without any
- `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapChooser.java:134` — NEW-FINDING: ,:155,:187 vs:340 — three different comparisons against the
- `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/SpotFinder.java:135-150` — NEW-FINDING: — mapBotCapacity mixes two estimators: a live profile's snapped spot
- `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenRewardBudget.java:37-39` — NEW-FINDING: — the comment claims WINDOW_MS = 60_000 "matches the floor of

**movement-and-travel.md** (5)

- `src/main/java/soloMapling/ArtificialPlayer/BotTravelSystem/BotTransit.java:306-318` — loads the hyper-rock block with new FileReader(YAML_PATH) while loadFile (:361-366) resolves through soloMapling.DataFiles.
- `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/MovementPlan.java:25` — declares Kind.CROSS_MAP, referenced by nothing (only IN_MAP is produced at:47/:65), so the enum advertises a plan shape the executor never sees.
- `src/main/java/soloMapling/server/portal_versions.yaml:6-8` — gates maps at version 99, but src/main/java/soloMapling/server/MapleVersionManager.java:10 initializes version = 55 and no production assignment was found, so every row whose value is ≤ 55 permits entry by default while 99 …
- `src/main/java/soloMapling/ArtificialPlayer/GCMoveSystem/GCMovementDriver.java:49` — hardcodes ENABLE_UNSTUCK = true although every neighbouring recovery behaviour is switchable (NavPortSwitches, WatchGate, IdleSafety), so on a live shard the random-jump unstuck cannot be disabled to diagnose a suspected …
- `docs/release/1.6.4.conf:271` — Evidence: 68 engine tests + 4 travel-data tests + 2 ground tests exist and encode the hard-won regressions (MoveStallGiveUpTest, GCTravelMarketDoorTest, NavPitsTest, RopeTopStallTest, SwimWallJumpBurstTest, NavGraphCacheTest); …

**observability.md** (6)

- `src/main/java/client/command/commands/gm3/BotWhyCommand.java:23` — BotWhyCommand.isEnabled at is LifeRuntime.enabled QaSuite.isEnabled && Boolean.getBoolean(...), so on any server with the brain on (which is 1.6.4's default) the -Dslums.botlife.preview half is dead: !botwhy is registered …
- `src/main/java/soloMapling/qa/QaLog.java:225-233` — the b276 root cause is structural, not a tagging bug: QaLog.map forwards NO_CHARS to sinks while QaRun fills the map filter in from the scenario (src/main/java/soloMapling/qa/QaRun.java:124-132), so a map-scoped line can only …
- `src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:203-232` — SocialMetrics.whyLines reports "Mind: not loaded (no social state in memory for this bot yet)" whenever LifeMemoryStore.peek misses — on a live server with thousands of bots and partial residence this reads as "the bot has no …
- `src/main/java/soloMapling/server/BotPerfStats.java:49-61` — BotPerfStats.report is synchronized and computes deltas against static prev* fields , so two GMs running !env perf interleaved split the same window between them and each sees roughly half the true rate.
- `src/main/java/client/command/commands/gm3/BotStatusCommand.java:29` — !botstatus truncates at 40 bots with only a, first 40 suffix (, 47-49) and emits via dropMessage(5,...) (:66-67), i.e. one chat packet per bot — on a full map that is 41 packets, and the header's, first N wording is easy to …
- `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:884` — data/autoqa/bot-life.json is written whenever -Dslums.botlife.metrics is not false , with no QA/auto-QA guard, so a live server writes it into data/autoqa/ even though live runs neither the QA suite nor auto-QA.

**owned-alts.md** (6)

- `src/main/java/config/ServerConfig.java:103` — OWNED_ALTS_ENABLED (config.yaml) false not in SERVER_SET (docs/release/1.6.4.conf:207-214); set by live config.yaml (docs/release/1.6.0.conf:34) — see NEW-FINDING
- `docs/release/1.6.4.conf:207-214` — turns on all six companion switches but does not set OWNED_ALTS_ENABLED, whose code default is false (src/main/java/config/ServerConfig.java:103).
- `docs/release/1.6.4.conf:253-255` — states -Dslums.ownedalts.marry and -Dslums.marriage.expbonus are "NOT in UNIT_EDITS", but docs/release/1.6.4.conf:271's UNIT_EDITS list contains both at tokens 172 and 173, and the owner note at:268 says both are ON.
- `src/main/java/soloMapling/companions/OwnedAltWedding.java:21` — (class comment) and:70-73 describe the marriage as fee-only with "no bonus of any kind", but stage 3 in the same release added MarriageExpBonus (+5 % altbot spouse, src/main/java/soloMapling/companions/MarriageExpBonus.java:14,39-41).
- `src/main/java/soloMapling/companions/OwnedAltGate.java:117-123` — treats last == 0 (no player action ever recorded) as not idle, so a client that never sends a recorded action keeps its alts fighting indefinitely; the idle gate is only reachable after the first action.
- `src/main/java/soloMapling/companions/OwnedAltOrders.java:62-64` — refuses LANES while OwnedAltCompanion.tickAll assigns lane offsets from PartyBotOrders.slots (src/main/java/soloMapling/companions/OwnedAltCompanion.java:237-247) only when CompanionSwitches.orders is true — so with …

**party-and-expeditions.md** (6)

- `src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotPartyLogic.java:9-14` — checkPartyQueue
- `src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/BotRecruitManager.java:176-186` — accepting on
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQBot.java:385` — blockingSleep(3000) runs on the
- `src/main/java/soloMapling/ArtificialPlayer/BotConvo/LfgParty.java:213-226` — the cap is a static
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/OPQ/OPQOrchestrator.java:56-58` — the stage-2 box
- `src/main/java/soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:127` — follows converts a

**population.md** (4)

- `src/main/java/soloMapling/ArtificialPlayer/BotChannels.java:320` — calls getPlayerStorage.getAllCharacters per channel, and src/main/java/net/server/PlayerStorage.java:91-98 returns new ArrayList<>(storage.values) — every login check (BotChannels.onlineBots,:336-342) and every …
- `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:484-487` — guards the tick only on paused/isQuiesced, and start schedules it with scheduleAtFixedRate on a 10-thread pool (src/main/java/soloMapling/server/ExecutorServiceManager.java:16) — a tick that runs longer than …
- `src/main/java/soloMapling/ArtificialPlayer/BotPopulationDirector.java:225` — sizes LOGIN_SLOTS at 8 while src/main/java/soloMapling/ArtificialPlayer/BotDbGate.java:92 grants the bots 6 of 10 pooled connections, so up to 8 logins can be resident and refused by the gate on a single pooled borrow; …
- `src/main/java/config/ServerConfig.java:98` — declares SPAWN_BOTS_ON_STARTUP and nothing reads it; src/main/java/soloMapling/server/MapleRootSolo.java:52-53 keys off -Dsolomapling.autopopulate instead.

**quests-and-progression.md** (6)

- RECOMMEND_MIN_SCORE is declared and unit-tested but has no production caller, so the chat "recommend a
- BotQuests.ranked filters to quests whose every required mob spawns on the grind map
- the Temple lane picker is consulted only inside TempleOfTime.consider
- a quest taken while nobody watched can never be upgraded. atNpc records the mode at take time
- `src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestLog.java:27` — the BotQuestLog class doc links {@link BotQuests#LEVEL_WINDOW} 
- a refused turn-in burns a slot of the daily cap. recordRefused calls log.complete(false,...)

**social.md** (4)

- `docs/release/1.6.4-social-conf-notes.md:59-63` — claims t-pi-1 is "deliberately not in the
- `src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialDecider.java:165-180` — multiplies the
- `src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialLod.java:157-176` — marks every bot
- `src/main/java/soloMapling/ArtificialPlayer/BotSocialSystem/SocialMetrics.java:28` — keeps

**supply.md** (5)

- `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:45` — mutates
- `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/SupplyNeeds.java:34` — LOW_POTIONS = 20 is a
- `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:208-212` — removes the
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1592` — sends a Sleepywood bot to the
- `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:547` — reports "since boot"

**tick-engine.md** (10)

- `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:487-489` — getRandomDelay returns 2000 + random.nextInt(4000) = 2000–5999 ms but the comment says // 2000 to 3000 ms; the two other comments in the same file correctly say 2–6 s (src/main/java/soloMapling/ArtificialPlayer/BotSM.java:386, …
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:95` — Header still says "one shared 0.5s ticker" while COMBAT_TICK_MS is 250 (src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:114) — the header describes the pre-change cadence.
- `src/main/java/soloMapling/server/BotTickService.java:242-244` — No watchdog: a tick that blocks forever leaves ticking = true permanently, so that bot never runs again and nothing logs it.
- `src/main/java/soloMapling/server/BotTickService.java:238-256` — No dispatch cap per pass and no semaphore: one pass can submit thousands of virtual threads at once after a stall, which is exactly when the machine is already behind. The governor reacts only after 5 s of lag.
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:218-224` — stopCombatTicker cancels the future but never clears combatTickerStarted, so the sweep can never be restarted in the same JVM (matters for the resetForTest path and for tests).
- `src/main/java/soloMapling/server/BotPerfStats.java:26` — , src/main/java/soloMapling/server/BotPerfStats.java:28-34, src/main/java/soloMapling/server/BotPerfStats.java:90 wheelLagMaxMs is never reset, so !env perf's "lag max" is a lifetime maximum, not the window the other numbers …
- `src/main/java/soloMapling/server/ExecutorServiceManager.java:140-145` — shutdown has no caller anywhere in src/main/java; the shared pools (including the wheel's driver thread) are only stopped by System.exit.
- `src/main/java/soloMapling/server/MethodScheduler.java:36-38` — getPendingTaskCount returns a hardcoded 0 and has no callers — a leftover from the pre-F12 pool.
- `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/CharacterStorage.java:58-62` — updateAllBots still loops active bots calling bot.updateState directly, bypassing the wheel's no-overlap guard. No callers today, but it is a second, unrouted tick path left in the tree.
- `src/main/java/soloMapling/server/BotTickService.java:258-281` — The governor's "avg lag" is dominated by whichever cohort just came due, not by real load: a single 3500-bot pass that happens to coincide inflates the 5 s window and stretches everyone (including bots that were never late).

**trade-and-merchants.md** (7)

- `src/main/java/server/maps/HiredMerchant.java:356` — vs src/main/java/soloMapling/Economy/BotShops.java:520 — the buyer's mesos are destroyed at the purchase and the bot's share is only minted later by collect; if the bot is offline for the rest of its life, or the JVM restarts …
- `src/main/java/soloMapling/Economy/BotShops.java:44-46` — the class docblock still says a shop opens "on channel 1" while the code spreads shops across channels 1-3 by channelTargets (:244-245,:404-419) and EconomyReviewFixesTest:288 pins the spread; the docblock is stale from rc3.
- `src/main/java/soloMapling/ArtificialPlayer/BotTradeSystem/BotTradeWants.java:94-121` — verifySufficientMeso accepts a shortfall ("allows for a small shortfall based on the magnitude"), so a merchant-type bot can complete a window for slightly less than mesoWanted, while the direct-trade path requires partnerMeso …
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/GachaBot.java:110-119` — processReward returns immediately with its whole body commented out, so reactToReward (:121-129) and the rewardQueue it would poll are dead code; the state chain still walks STAND_BY_3 → STAND_BY_4 (:212) → PROCESS_REWARD …
- `src/main/java/soloMapling/Economy/BotShops.java:607-619` — an offline bot's own-loot item (Kind.LOOT) never enters the resale pool; it goes to PendingGrants and is held until the bot has room, which for a deleted or permanently offline bot is forever.
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBotWallet.java:29-54` — the FM shopper allowance is per JVM (usedToday in memory) while its intent is per UTC day; a restart mid-day re-arms 10m of purchasing per bot, which the class docblock (:14) does not mention as a cost.
- `src/main/java/soloMapling/Economy/BotShops.java:131-153` — townTick throttles on a shared nextTryAt map but keys only by bot id and never removes the entry, and shops/nextTryAt grow for every bot that ever stops in town; no eviction exists.

**training-bot.md** (7)

- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2003-2035` — doDecide records
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1560-1564` — the town deadline
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1283-1290` — the SELL-with-no-shop
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:2222` — vs
- `src/main/java/soloMapling/ArtificialPlayer/BotSM.java:487-489` — getRandomDelay returns
- `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1237-1240` — keepLifeActivity
- See the NEW-FINDING at:1288-1296.

### client

**build-and-tests.md** (10)

- NEW-FINDING:.github/workflows/build.yml:18-21 still uploads build-baseline/Release/Kaentake.dll,
- NEW-FINDING: CMakeLists.txt:1 declares cmake_minimum_required(VERSION 3.14), but scripts/build.ps1:17 uses
- NEW-FINDING: /root/work/client/src-164-data/dependencies.json:2-5 pins and /root/work/client/src-164-data/scripts/prepare-dependencies.ps1:9-13 extracts the CppWinRT nupkg, but no
- `tools/package_runtime.py:7` — NEW-FINDING: asserts a.runtime/'Kaentake.dll' against
- NEW-FINDING: src/launcher.rc:10-11,24,28 hard-codes FILEVERSION 1,2,0,0 / "1.2.0.0", while slums.dll's
- NEW-FINDING: tests/CMakeLists.txt:90 defaults MOVEMENTKEYS_TEST_CLIENT to
- NEW-FINDING: reproducibility is structurally impossible for the current build: mapleroot/ijl15/theactualstuff/xorstr.h:58
- NEW-FINDING: release/baseline.json:12-18 records client_flags as five options, but scripts/build.ps1:11 now
- NEW-FINDING: tests/CMakeLists.txt:159-168 registers hud_meters_exp_dps, display_toggle_rules and
- NEW-FINDING: there is no install rule and no packaging step in this repo (grep -rn "install(" *.txt: 0 hits);

**build-stamp-and-version.md** (8)

- `/root/work/client/src-164-data/README.md:24` — 24 reads CP 0x3738 (format 1, int build, string release), but src/buildstamp_proto.h:24 sets kBuildStampFormat = 2 and caps are always written (src/bypass.cpp:435).
- `tools/release/README.md:45-47` — the shape short format=1, int build, string release (:45-46) and the pre-b205 formula major*10000 + minor*100 + patch (:47).
- "build is monotonic (VERSION major*10000 + minor*100 + patch...)" — the code has used (minor+1)*100 + patch*10 since b205, so the comment describes numbers nobody has built since 1.6.1 src/buildstamp_proto.h:11 vs …
- `src/main/java/server/reports/BugClientLogs.java:40` — the server asks a "supporting" client for logs after @bug (,:67-69; src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:14-16) but no client file sets that bit, and git log -S kCapLogUpload / -S 0x373B over the …
- `docs/qa/client-scripts/README.md:63-64` — the gate refuses a build nothing produces. It lives only on client-170-qa-module/client-162-* (2f17894, not an ancestor of client-164-rc3), while and tools/qa-scripts/test_qascript.py:222-225 treat it as present git merge-base …
- `docs/release/1.6.4.conf:284` — 91-93 prints the stamp and only warns when release's major.minor.patch differs from the version being staged; a conf/gate mismatch (e.g.
- `docs/release/1.6.1.conf:258-259` — tags v1.6.0.2 and v1.6.1-rc1..rc9 all carry VERSION = 1.6.0, so the shipped numbers 10601/10680/10690/10700/10710 exist only in slums-build.txt, the release docs and PE resources.
- 18 still says "based on Kaentake" in FileDescription and:25 splits the copyright, while docs/PROVENANCE.md records a MapleRoot Full Repack baseline plus Kaentake — the resource names one upstream of two src/slums.rc.in:18,:25; …

**config-ini.md** (8)

- NEW-FINDING: /root/work/client/src-164-data/config.example.ini:57-58 ships ChairOpacity=255 and SkillOpacity=255 with no reader anywhere in
- NEW-FINDING: /root/work/client/src-164-data/config.example.ini:2 ships resolution=5 for a key this build cannot read: the read is compiled out
- NEW-FINDING: src/hud_native.cpp:390 reads [clienthax] nativehud (default on) and the key appears in neither
- NEW-FINDING: /root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:603 makes every inih key hostage to the whole file's syntax. A single
- NEW-FINDING: src/midair.cpp:17 has its own boolean parser: yes, on, TRUE — _stricmp accepts TRUE and
- NEW-FINDING: src/injector.cpp:34-40 reads port only after host succeeds, and src/system.cpp:269 skips the port
- NEW-FINDING: src/injector.cpp:23 parses argv[1]/argv[2] as host/port unconditionally. The starter passes its
- `/root/work/launcher-165-server/README.md:253-264` — NEW-FINDING: the launcher README's Game options table lists 10

**crash-guards.md** (7)

- src/crash_report.cpp:167 — InstallCrashReport{} is an empty no-op with no caller; the
- src/crash_scrub.h:5 — the header says "tests/crash_scrub_test.cpp checks it"; there is no
- src/crash_diagnostics.cpp:37-38,:67-68 — the "last C++ error" note accepts only
- src/mapobj_guard.h:22-24 / src/lamps.cpp:1496-1497 — the map-object guard is compiled
- src/hook.h:282-283 — AttachMobAttackFixMod is the only guard install without a
- src/attackdiag.cpp:212 — [debug] attackdiag is absent from /root/work/client/src-164-data/config.example.ini, so the
- src/crash_classify.h:86 — GraphicsDriver requires ExceptionCode == 0xC0000005; a driver

**crash-report.md** (9)

- NEW-FINDING: src/crash_report.cpp:167 — InstallCrashReport is an empty stub with no caller. No report path
- NEW-FINDING: src/crash_report.cpp:53 — PutLogTail and the write at:145 use CWD-relative paths, so a client
- NEW-FINDING: src/crash_diagnostics.cpp:23-24 — crash.log records the full module path
- NEW-FINDING: src/crash_scrub.h:49-53 — the dialog rule is a strstr over the whole line, so any integration.log
- NEW-FINDING: src/crash_scrub.h:5 and src/crash_classify.h:5 — both header comments point at
- `src/main/java/server/reports/BugClientLogs.java:40` — NEW-FINDING: — client and server disagree about caps bit 1: BugClientLogs.CAPS_LOG_UPLOAD
- `docs/tickets/131-bug-reports.md:184-187` — NEW-FINDING: — the server expects a [crash] section carrying the tail of
- NEW-FINDING: src/crash_report.cpp:10 -- the header promises "the launcher shows the file and sends it
- NEW-FINDING: CMakeLists.txt:17 vs src/crash_diagnostics.cpp:32 — the VEH's NoteCxx and resman/resolution

**custom-wz-and-data-tools.md** (7)

- NEW-FINDING: src/resman.cpp:140 — fs->item[L"Custom.wz"] is used with no existence check; a missing or renamed
- `tools/wz/add_damage_glyphs.py:157-171` — NEW-FINDING: — this is the one writer that does not run the
- NEW-FINDING: tools/wz/add_damage_glyphs.py needs --stock-basiceff on every run, including --check, and
- NEW-FINDING: tools/wz/test_add_mob_attack_type.py is not registered in tests/CMakeLists.txt and no script runs
- `tools/package_runtime.py:7-10` — NEW-FINDING: release/baseline.json does not list Custom.wz, so cannot detect a
- NEW-FINDING: tools/wz/add_mob_attack_type.py removes every Mob/ image that is not in the wanted set
- `docs/research-119-120.md:327` — NEW-FINDING: still lists "whether the merge reaches Effect/BasicEff.img/No*

**exe-and-starter.md** (8)

- NEW-FINDING: src/launcher.rc:10-11,24,28 — the exe's FILEVERSION/FileVersion/ProductVersion are hardcoded
- NEW-FINDING: src/launcher.cpp:14 — lpCmdLine (which WinMain gives without argv[0]) is passed verbatim as
- NEW-FINDING: src/launcher.cpp:35 — the exit code is discarded except under MAPLEROOT_INTEGRATION
- NEW-FINDING: src/debug.cpp:16-18 — integration.log is opened relative to the CWD, appended forever, with no size
- NEW-FINDING: src/launcher.cpp:14-20 — Detours terminates a half-created child (external/Detours/src/creatwth.cpp:1581-1584)
- NEW-FINDING: src/launcher.manifest:8 — elevation is unconditional, though the documented need was Kaentake's
- NEW-FINDING: SlumsMS-164-archreview:tools/release/client-allow.txt:121 — Start SlumsMS.cmd is an allow-listed
- Starter version resource partial (broken metadata) hardcoded 1.2.0.0, src/launcher.rc:10-11,24,28; see NEW-FINDING

**hpmp.md** (7)

- src/hpmp_patch.h:44 constexpr int kStockCap = 30000; is declared and never referenced anywhere in the client repo (grep -rn kStockCap src tests → one hit).
- `docs/release/1.6.0-hpmp-4byte.md:11-13` — src/hpmp.cpp:53 / src/hpmp_patch.h:60 The patch is unconditional: no config.ini key, no CMake option (src/CMakeLists.txt:21), no check of the connected server's version.
- `src/main/java/config/ServerConfig.java:61` — MIN_CLIENT_BUILD = 0 (gate off) is the default and MIN_CLIENT_BUILD_GM_BYPASS = false (:64) is the only brake. A 1.6.x jar on a server whose config.yaml does not set the key serves old clients misaligned packets; the memo …
- `src/main/java/client/Character.java:7791-7793` — The GM job branch of level-up still adds exactly 30000 HP and 30000 MP per level (Job.GM at:7791), which was the stock 30,000 cap expressed as a gain.
- `src/main/java/tools/PacketCreator.java:119` — showHpHealed(int cid, int amount) writes the heal amount with p.writeByte(amount), so any heal over 255 (map recovery 77 * map.getRecovery * 1.5, src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:54) shows a …
- src/buildstamp_proto.h:11 The comment states the build is "VERSION major*10000 + minor*100 + patch", but cmake/BuildStamp.cmake:25 computes major*10000 + (minor+1)*100 + patch*10.
- `docs/release/1.6.4.conf:10` — vs:284 The 1.6.4 conf contradicts itself on the gate: the header says "No new client: MIN_CLIENT_BUILD stays 10721 (nothing on this branch changes what the client reads)" (:10), while MIN_CLIENT_BUILD=10740 at:284 was raised …

**injector-and-hooks.md** (6)

- NEW-FINDING: no runtime exe verification exists. grep for sha256/ImageBase across src/ hits comments
- NEW-FINDING: VMTHook is dead code — declared src/hook.h:322, defined src/hook.cpp:102-107, and no caller
- NEW-FINDING: src/midair_patch.h:1-10 still says "SCAFFOLD … nothing patches them yet" while
- NEW-FINDING: two independent config.ini readers with different parsers. src/ uses
- NEW-FINDING: AttachClientBypass patches are not covered by VerifyKaentakeHooks groups of their own — the
- NEW-FINDING: UnhandledExceptionFilter is rewritten at 0x00797074 to 0xEB ("fix process hang",

**qa-signals.md** (7)

- `tools/qa-runner/qarunner.py:348-350` — the comment and NO_PAYLOAD still say field.ready carries no map id, while the client branch sends one (/root/work/client/src-170-qasignals/src/qamodule.cpp:522-524) and scripts wait on map_id (qa-scripts/b140-bot-heal-column.yaml:27).
- `tools/ops/client-mcp-win/src/driver.py:33-35` — qa_wait's tool schema enumerates only the four ready signals, while qapipe forwards any name and the runner waits on seven.
- scripts/build.ps1:11 — the shipped build script never passes -DSLUMS_QA_MODULE, so the QA client every qa-scripts/*.yaml needs cannot be produced by the documented build; it is a manual CMake configure today.
- `tools/qa-runner/qaverdict.py:157-187` — the QA client is built from client-170-qa-signals (2026-10-31), which is not a descendant of client-164-rc3 and lacks its guards (git diff --stat = 39 files, +1379/−2819).
- /root/work/client/src-164-data/config.example.ini has no [debug] section although src/uilayout.cpp:368 reads debug/uistate (and src/attackdiag.cpp:212 reads debug/attackdiag).
- /root/work/client/src-170-qasignals/src/qamodule.cpp:226-228 — if the CUIWnd class test fails, window.open/window.closed are off and the only notice is an error event after 600 frames (10 s).
- /root/work/client/src-170-qasignals/src/qamodule_patch.h:24-39 — both sites are raw VAs tied to one MapleStory.exe sha256; only qamodule_sites (which needs QAMODULE_TEST_EXE) catches a drifted exe before a live run.

**resolution-and-d3d9.md** (8)

- NEW-FINDING: src/resolution.cpp:807-810 — a failed module pattern is written through as null. GetAddressByPattern returns nullptr when GR2D_DX8.DLL is absent, unloaded or the pattern is not found (src/hook.cpp:149-153), and …
- NEW-FINDING: /root/work/client/src-164-data/config.example.ini:2 ships [general] resolution=5, a key nothing reads in this build (the reader is compiled out at /root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:607), …
- NEW-FINDING: the two mode tables disagree on 1280x720. The companion's dead table has it (/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:479-500, index 2), the live client's does not (src/resolution.cpp:661-676) — …
- `docs/PROJECT-STATUS.md:26` — NEW-FINDING: 1024x768 and 1600x900 have never been reported working. confirms only 800x600, 1366x768 and 1920x1080, and nothing in the patch table is mode-specific, so the risk is in the layout rather than the device path.
- NEW-FINDING: resolution has zero automated coverage (see Tests and QA). 42 byte patches and 22 hooks in src/resolution.cpp are validated only by the fact that the client starts.
- NEW-FINDING: src/display.cpp:41-47 latches the pref once. g_loaded makes LoadPrefs a no-op after the first call, so a registry edit made by anything other than this DLL (including a second client instance) is not seen until …
- NEW-FINDING: src/d3d9host.cpp:305 keeps the host alive only through the forced import. g_pForceD3D9Import (:35-37) is the only reason d3d9.dll is loaded at DllMain; the guard at:304-309 degrades to "d3d9.dll is not loaded; …
- NEW-FINDING: the overlay's catch-all leaves ImGui mid-frame. src/d3d9host.cpp:178-181 closes the window on an exception after NewFrame may already have run, and only logs once, so a recurring failure is invisible after the first line.

**ui-tweaks.md** (6)

- /root/work/client/src-164-data/config.example.ini documents none of [clienthax] nativehud (src/hud_native.cpp:390, default 1), the registry value damage_short (src/damagefmt.cpp:61), [debug] uistate (src/uilayout.cpp:368) or …
- src/CMakeLists.txt:102-103 compiles weathermove_disabled.cpp, so the entire 278-line footing implementation in src/weathermove.cpp is dead code with no config key to reach it — the feature is neither "off by config" nor …
- src/dailycheckin.cpp:26 instructs "AttachDailyCheckinMod is the single install entry — call it from your hook installer", but that symbol does not exist and no src/hook.h entry calls it; the window is created lazily by …
- src/invresize.cpp:675-706 forces narrow mode by patching the only m_bFull read (0x0081C4BE → xor eax,eax), so the stock 4-tab "full" inventory view is unreachable whenever resize is on — a deliberate-looking trade-off that is …
- MAPLEROOT_HUD_METERS and MAPLEROOT_DAMAGE_FORMAT are absent from scripts/build.ps1:14, so the EXP/DPS widgets and the short damage numbers — both shipped, both documented in README.md — are compiled out of the release build …
- src/hud_meters.cpp:34 keeps g_levelCounter as a stand-in for the real level, and:86 infers a level-up from next != g_lastNext && exp < g_lastExp; a GM level change or an EXP-table change without an EXP drop will be miscounted, …

### launcher

**channels-and-auth.md** (5)

- channel_feed (/root/work/launcher-165-server/tauri/core/src/channels.rs:49-56) rebuilds the path with Url::join, so a Live feed URL carrying a
- folder_key (/root/work/launcher-165-server/tauri/core/src/channels.rs:188-196) compares folders as strings; /a/b and /a/../b or a symlinked
- site::TEST_SITE is plain HTTP on a LAN address (/root/work/launcher-165-server/tauri/core/src/site.rs:18) and Test/RC sign-in sends the session
- `docs/nginx-dev-tier.md:16` — tauri/ still says "Nothing below has been applied", while RUNBOOK.md
- `docs/architecture/launcher/README.md:68` — points at docs/architecture/ops/hosts.md, which does not

**core-manifest-and-release.md** (5)

- build_number computes a number the client no longer emits. tauri/core/src/client_build.rs:30-41 implements
- nothing in the publish path writes minClientBuild. tools/publish.mjs:357-400 builds {schemaVersion, version, launch, layers, news,
- a refused client is only a label. With the manifest unchanged and the installed build below the minimum, play_plan
- PackSource.sha256 is validated as a digest but never compared to the downloaded archive (tauri/core/src/packs.rs:22-29,:84; install
- Partial for the client-build story: the metadata is read and displayed correctly, but the decision it feeds is advisory only (three NEW-FINDINGs).

**game-config.md** (4)

- `/root/work/launcher-165-server/README.md:58` — NEW-FINDING: and:230 in the launcher repo still say a player-owned file is "installed once and
- `/root/work/launcher-165-server/README.md:251-264` — NEW-FINDING: lists 10 options and misses borderless/dpiaware (/root/work/launcher-165-server/tauri/core/src/game_options.rs:90-93),
- NEW-FINDING: the hand-edit branch drops the whole layer copy (/root/work/launcher-165-server/tauri/core/src/install.rs:569), so a folder whose player once set
- NEW-FINDING: only one pending change set is kept (Option<PendingGameOptions>, /root/work/launcher-165-server/tauri/core/src/settings.rs:16-19) and it is matched

**install-and-packs.md** (4)

- the pack-level sha256 from the index is never verified. validate_pack_index stores it (/root/work/launcher-165-server/tauri/core/src/packs.rs:84)
- no "game is running" guard. GAME_RUNNING (/root/work/launcher-165-server/tauri/core/src/error.rs:10) only guards writes of game options
- a hand-set server is recognised only from state.json's server field, so a player editing
- BACKLOG-1.6.0.md:83 still says ticket 20 is "not started no chunk/pack code in server repo" — stale,

**play-and-launch.md** (4)

- `/root/work/launcher-165-server/README.md:59` — says Play "does not rewrite client INIs", but play_flow writes config.ini before
- the launcher cannot see the game end, so a crashed client looks identical to a running one —
- test/play-gate.test.mjs:3 tests the Electron copy src/version-status.mjs, while
- nothing in the launcher caps game instances and no release doc mentions multi-client, yet

**publishing.md** (5)

- publish-client:149-150 mirrors /launcher-test/manifest.json only when layers there is a symlink and
- `tools/release/verify-client.py:96` — the endpoint check reads only host and port ; a layer keeping them but changing
- `tools/release/publish-lib.sh:20-21` — check_publisher hashes tools/ src/ package.json , no lockfile and no
- restore-manifest checks the named layers exist but never re-runs the endpoint check
- tools/release/routing-required.txt:5 (map 211080100 routed until client fix 3e640f1 is live) is

**self-update-and-signing.md** (4)

- the 60 s timeout applies to check only. update_from_release builds the Update with
- the download is buffered with no size cap (/root/.cargo/registry/src/index.crates.io-1949cf8c6b5b557f/tauri-plugin-updater-2.14.0/src/updater.rs:881-888, buffer.extend(chunk)), where the
- self-update-failed= is parsed and honoured (/root/work/launcher-165-server/tauri/app/src/launcher.rs:70,:802) but this launcher never
- a window exists with no launcher exe. swap_in renames the running exe away and only then renames

**site-and-account.md** (3)

- Test/RC launcher builds sign in to a host the TEST site left at 1.6.3. TEST_SITE is still
- a secret mismatch is reported as a wrong password. 401 UNAUTHORIZED from the game is absent from
- docs/architecture/switches.md has no slums.auth.* row even though the three flags are the

**ui.md** (7)

- NEW-FINDING: /root/work/launcher-165-server/tauri/ui/renderer.mjs:104 decides whether to hide the byte/speed line by testing the message text
- NEW-FINDING: /root/work/launcher-165-server/tauri/ui/renderer.mjs:393,397 index phaseNames[phase] / phaseHeadings[phase] with no fallback, so a
- NEW-FINDING: /root/work/launcher-165-server/tauri/ui/renderer.mjs:57-59 copies a settings key only when typeof matches the default; if Rust ever sends
- NEW-FINDING: /root/work/launcher-165-server/tauri/ui/renderer.mjs:511-527 re-implements feed validation but omits the userinfo rejection of
- NEW-FINDING: three payload fields are written and never read: version in the release patches
- NEW-FINDING: available_json drops manifestSha256 from Available::Ok (/root/work/launcher-165-server/tauri/app/src/launcher.rs:1124-1129 vs
- be dropped with NEW-FINDING above?

### website

**board.md** (5)

- barters) — none implemented. - NEW-FINDING: the write limiter is memory-only and reads are not limited in-app. Store.rate
- `tools/site/slums-login.nginx.conf:5` — get only nginx's 60 r/m (,:19-20). - NEW-FINDING: with the game server off the board silently freezes,
- tell "nobody has posted" from "the game server has been down for two days". - NEW-FINDING: name blanking applies to board.json but not
- named autoqa…, is anonymous on the site and named at the FM entrance. - NEW-FINDING: the NPC interpolates the character name into client
- enforced elsewhere. - NEW-FINDING: /market/ sends no Content-Security-Policy and no X-Frame-Options

**build-and-checks.md** (5)

- rebuild_section("pages", …) writes into the live folder before checking and does not roll back
- the preview cannot serve clean URLs: /guide/money.html is 200, /guide/money is 404, since
- `tools/site/check.py:162-196` — check_links reads only href/src , so srcset, <style> url
- `docs/wiki/build.py:179` — doc drift — and the comment at:192-193 say "every 15 minutes"; both timers run
- `tools/site/check.py:275` — check_noindex leaves robots.txt unclosed , a ResourceWarning in the

**deploy.md** (4)

- `docs/site/LAUNCH.md:168` — says "cache/site-icons is not needed", while deploy-build-164.sh:14-15,27
- the rebuild is called "15-minute" in fc3daae81's message, test164/README.md:17 and build.py:193,
- two tickets share number 162 (docs/tickets/162-nx-forex.md, 162-gear-optimizer-full.md) while
- `deploy/dashboard/slums-dashboard-live.nginx.conf:19` — authenticates the *live* dashboard with

**exchange-desk.md** (6)

- the request file is unsigned. The game trusts the account field it reads
- the site ingests answers only inside an HTTP request (market_app.py:133,:139,:181; no
- the answered set is in memory (ExchangeService.java:132) and tokens live in memory too
- the halt that a post-commit desk failure raises (Exchange.java:1238,:1243-1247) is not
- once answered passes 20,000 it is cleared wholesale (ExchangeService.java:231-233). Only
- the responses file keeps 500 answers (ExchangeSite.java:69). A site outage long enough to

**login-service.md** (6)

- `docs/site/LOGIN.md:38` — ("The game server can write banned accounts there too"), LAUNCH.md:182 and
- a banned player keeps a working site session for up to 30 days. The cookie is self-contained and only
- rotating the shared secret is a two-step change with a silent failure mode. The game reads it once at
- a site clock more than 5 minutes ahead of the game makes every exchange request answer REAUTH
- Sessions.revoked/before/ended re-read and re-parse their files on every call with no caching
- (NEW-FINDING above). No evidence in this review that any of it has been exercised against live.

**market-service.md** (3)

- nothing in the market notices staleness except the stamps page and the NX desk
- a missing or broken board.json and an empty board are the same answer — {"posts": [],
- `tools/site/slums-market.service:25` — points SLUMS_MARKET_ITEMS at street/search-index.json while

**privacy-rules.md** (5)

- `tools/site/sections/market.py:105-108` — account name / account_id / accountId no ,:153-158;.../world.py:37,:130-133; tools/site/report_service.py:113-114 new key named exactly as a private field → build refused; a *renamed* key (e.g. seller_email) passes (NEW-FINDING)
- `tools/site/sections/world.py:37` — bot/player labels (seller_kind, is_bot, player_kind, …) no ,:130-133; src/main/java/soloMapling/Economy/market/MarketListings.java:80-81 build refused (World); market.validate only refuses the exact name (NEW-FINDING)
- the private-field lint is an exact-name match (market.py:155-157), so seller_email passes; it walks
- `docs/design/track-d-plan.md:216-226` — promises "sales delayed at least 1 h" and a plan-derived privacy
- 4. Should market_service apply check.scrub to board text (NEW-FINDING above), or is display-side safety enough?

**report-service.md** (6)

- GET /api/report/health returns store.count, which is the highest id, not a count
- report_export.py removes only ip_hash (:23) while the staff reader deliberately withholds
- the site reads with no lock at all (report_service.py:138), so a staff list during
- nothing rotates or prunes the reports file, and the reader sees only the newest 16 MB
- the id scan reads only the last 256 KB (report_service.py:70-79, ReportStore.java:367-393), so a
- free text is never scrubbed. title/details from the form and the @bug text go into the file

**sections.md** (2)

- sections/guide.py:54 still says "update wiki_body in tools/site/build.py" — the function lives in
- sections/admin.py:223-225 copies exactly the names in SCRIPTS (:27); a dashboard that adds a

### ops

**backups-and-restore.md** (4)

- test-deploy backups are never rotated. tools/ops/deploy-test:73 writes $BASE/deploys/<stamp>/,
- `deploy/monitor/targets/live.conf:9` — the rotate and drill timers are unmonitored. lists only
- `deploy/v1.4.1/deploy.sh:14` — the release template lost the WZ snapshot. ,193,199-204 captured
- the drill's TEST-DB coordinates no longer match the documented host split. It uses 127.0.0.1:3307

**client-and-launcher-publishing.md** (4)

- the rehearsal guard compares PATCH_DIR as a raw string (publish-client:39) while live-feed detection
- the RC channel named by AGENTS.md:189 has no code path — nothing defaults to or creates launcher-rc,
- client-allow.txt goes to one fixed host path, /tmp/slums-client-allow.txt (publish-client:103,117), so
- restore-manifest checks that layers exist but not the endpoint (:47-57); a manifest naming a client

**hosts.md** (6)

- TEST's documented facts disagree, sometimes within one file. The TEST dashboard at:8091 sits on
- `tools/release/lib.sh:4` — defaults every release hop to live — stage, rehearse, release all use
- `docs/ops/lxc125-architecture.md:25` — two docs disagree on the live DB compose file. says
- 164's size and live's folder are stated against stale values. 12 cores / 48 GB (AGENTS.md:52) vs
- live monitoring status is contradictory, and its target is stale.
- `docs/architecture/website/deploy.md:14-15` — the TEST site installer defaults to the live box. claims

**migrations-and-rollback.md** (5)

- NEW-FINDING The rollback never removes the.migration-<id>.attempted markers (no rm in rollback.body.sh), so a second rollback.sh after a successful one re-runs the same rollback SQL.
- 98 only warns when the ledger differs from LEDGER_BEFORE — the unit still starts and players log in. The deploy path dies on the same mismatch (deploy.body.sh:151,:224).
- 108-110 builds the id as basename "$m".sql. On TEST, 001 is therefore never seen as applied and is re-run every deploy (a no-op thanks to @apply, but the seven UPDATEs and the INSERT still execute).
- NEW-FINDING 062/063/023 issue ALTER TABLE … ADD INDEX/ADD COLUMN with no ALGORITHM=INPLACE, LOCK=NONE; grep -l "ALGORITHM\ LOCK=" over database/migrations/ returns nothing. Lock duration is unverified — see Open questions.
- 187-192 before applying at:212; tools/ops/deploy-test stops the unit at:94 and only then dumps and applies (:105-111), so the ordering holds there too — but it keeps no.attempted markers and has no rollback, so a failed …

**monitoring.md** (6)

- 1189), so a masked/failing timer or a crashed run produces nothing but an amber strip nobody is watching.
- an unexpected exception (e.g. int(t.num("LOGIN_PORT")) on a bad conf,:790) skips the status.json write at:1188, so both the strip and the alert path go quiet without a log line.
- nothing in deploy/ mentions logrotate, and logged-only alerts grow the file indefinitely (b299 shows disk pressure is real).
- 216-220) renames without fsync, so a host crash can lose the last alert state — the next run re-alerts, which is benign, but cloud.json's once-a-day flags can repeat.
- 1062) with no per-check truncation; a journal-burst detail plus ten checks can silently drop the tail of a message.
- 40). It is the one server-side health signal that could survive a hung world loop, and it is unused.

**qa-runner.md** (4)

- `tools/ops/client-mcp-win/src/testhost.py:4` — NEW-FINDING: — the docstring still says "DEFAULT_HOST is 164 today and
- `docs/qa/client-scripts/run-folder.md:13-20` — NEW-FINDING: tools/ops/qa-run-collect:15-20 vs — the run-folder doc lists
- NEW-FINDING: tools/ops/qa-run:9 — the tool's own header says it "only RECORDS (result RECORDED or ERROR)" and that "an
- NEW-FINDING: tools/ops/autoqa-proof:238-243 — is_docs treats any.md anywhere as docs, so a change to a

**release-pipeline.md** (4)

- no template has a WZ step since 1.4.1, so the b300 portal fix is a hand step on release night
- stage copies tools/ wholesale (stage:61), yet RUNBOOK.md:40 tells the operator to "stage
- release never checks REHEARSAL.md — only release-live.sh:27 greps it for rehearsal PASS smoke PASS;
- `docs/release/1.6.4.conf:10` — says "MIN_CLIENT_BUILD stays 10721" and:297 says "build 10721 or later",

**safety-guards.md** (4)

- the channel key rule — "Live /srv/slums-patch/ (player key), Test /launcher-test/ and
- `docs/release/RUNBOOK.md:27` — hotfix-parity is invoked by nothing. AGENTS.md:244 and 
- "rehearse must PASS" exists as runbook prose (RUNBOOK.md:30,
- `deploy/v1.4.1/rehearse.sh:44` — the port blacklist lost an entry. guards

**test-deploy.md** (5)

- jar and DEPLOYED_COMMIT are written before the restart (:114-115), so a failed boot still
- the boot wait cannot fail (gate table); success needs only an active unit and no
- journalctl grep -q under pipefail (:118) is the SIGPIPE false negative the release template
- data.before.tgz pipes tar tzf into xargs (:100), which splits on whitespace, so the backup
- deploys/<stamp>/ is never pruned (Data) and can hold a DB dump per deploy; S=$(date -u...)

_476 findings indexed._
