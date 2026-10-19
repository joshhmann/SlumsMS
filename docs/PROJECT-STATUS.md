# MapleRoot project record

Updated: October 18, 2026 (America/Los_Angeles).

This is the current status summary. PLAN.md retains the chronological client-port record; Evidence/ contains the supporting results. TASKS.md tracks remaining work. A successful build is not an in-game pass.

## Current core checkpoint

The core candidate is `Releases/Core-20261018-r1/`, with a matching client overlay and exact previously tested server JAR. Its frozen source passes 2,037 tests; all 22,690 rebuilt class entries match the tested JAR. A separate database/port smoke passed full startup with bots disabled, all 46 map loads and both migration records, with no logged exceptions. Native staged-binary verification, 20 verifier tests, UI asset checks, four UI tests and seven map tests pass. Historical gameplay evidence remains scoped to the recorded user checks.

The candidate excludes the deferred MR-020 drop fix and all new addons. Bots stay disabled in the proposed core service profile. The active town-pilot session remains separate and was not restarted. Live deployment, exact original-service/Windows recovery rehearsal, and outstanding manual checks remain open. INSTALL-AND-ROLLBACK.md explains the file pairing, fresh backup requirement, migration order and recovery steps. This is a core freeze for the next addon work, not a claim that every gameplay path or the live switch is complete.

## Agreed direction

- MapleRoot is the baseline server; Kaentake plus the MapleRoot companion is the working client.
- Preserve MapleRoot custom skills, equipment, rebirth and 64-bit EXP behavior.
- Integrate SoloMapling into a separate working copy before deployment.
- Keep the Arcane-style area as the main hub, with separate access to classic Free Market.
- Add a windowed cash shop, then weather/time. Other addons need individual review.
- Leave the above-level-200 large-EXP gameplay test for later, as requested.

## Working and tested client baseline

Kaentake handles startup, networking, resolution and Custom.wz. The MapleRoot companion retains gameplay extensions through bridge ABI 0x00010001. Character-entry crashes and cleanup/exit crashes were corrected. The blue-and-cream UI covers the bottom bar, quickslots, inventory, equipment, stats and minimap.

User-confirmed checks: character entry; 800x600, 1366x768 and 1920x1080; 26 quickslot hit targets; UI appearance; ordinary combat, EXP and earned leveling; portals; chairs; NPC shopping; channel changes; existing cash-shop entry/return; normal exit; Hero attacks/buffs and shoulder equipment; Herald custom skills and cooldowns; Night Lord Shadow Partner and Quadruple Throw; Corsair Battleship skills; selected custom magic skills; Papulatus HP display at two resolutions; character-selection re-entry.

Evidence: `Evidence/*-49748.json`, `Evidence/*-42452.json`, `Evidence/cash-shop-clean-exit-36268.json`, `Checkpoints/Clean-Exit-Validated/`, `feature-ownership.json`. Exact tested binary hashes are recorded with those results. The working client is `Client/`; supplied `Game Files/` and `DLL Source/` were preserved.

Still unproven: large EXP above signed 32-bit, remaining custom classes, numerical damage parity, broader cosmetic/chair/boss coverage, and full logout to login. These limits do not erase the specific checks that passed.

## SoloMapling and map work: isolated only

Upstream: https://github.com/MadaraGameDev/SoloMapling at `47b61b5381118ebdc1064ddbd0b1c32f75778871`.

`SoloMapling/LiveReference/` holds read-only snapshots of the actual live source and selected scripts/maps. `WorldExpansion/ServerWork/` contains the ongoing merge. Additive APIs, a headless bot client, explicit template/startup controls, and an attack-packet adapter compile against MapleRoot. Twenty Java tests now pass: the original compatibility/interaction cases plus merchant visitor-wrapper conversion and execution of the actual portal scripts in GraalJS, including dedicated return bookmarks and failed-warp handling. Bots are disabled by default; automatic population and tutorial admin grants each require separate flags.

Party invites, shop chat/welcomes, map-entry/level-up reactions, GM messenger console and GM movement recording are wired through guarded hooks. Artificial merchants skip player ownership/database updates, use IDs allocated without random collisions and do not persist inventories. Bot character saves explicitly return early even if the player login flag is accidentally set. The player's PacketCreator and AbstractDealDamageHandler remain byte-identical to the live source snapshot; hashes are recorded with the test evidence. Active scheduler/movement cleanup and both trade-queue sides were implemented and tested during the town pilot. Template/startup failure review and broader real-player trade acceptance remain deferred with playerbot work.

`WorldExpansion/Prototype/` restores all 23 classic FM maps at 910000000–910000022 and preserves the 23 current custom maps at 993910000–993910022. The main hub routes to the preserved area; a portal connects to classic FM and back. Seventeen private scenery resources avoid overwriting shared Arcane art. Client/server asset checks passed for 46 maps, 243 portal targets and 162 NPC/mob resources. Seven map regression tests pass. The separate maps-only source compiles. These assets are installed only in the separate test installation/client described below.

## Isolated test installation

Created under `/opt/mapleroot-dev/solomapling-test/`, using a separate `mysql:8.4` container named `mapleroot-integration-db`, bound locally to port 3307. The backup restored all 93 original tables successfully. A dedicated banned account and non-GM template character (ID 5 in this clone) avoid reusing an active player. Database credentials stay in protected files; they are not recorded in these documents.

Forward and rollback scripts for seven map-bearing tables are in `WorldExpansion/Migrations/`. On the clone, forward application preserved map offsets, a second application changed nothing, rollback restored complete table contents, a second rollback changed nothing, and forward application succeeded again. MyISAM tables mean any future live migration must run with the game stopped and a fresh backup; SQL transaction rollback alone is insufficient. Live aggregate preflight found one character on an old custom map and no destination-range rows.

The combined JAR passed an isolated headless smoke run: full startup, 46 map loads, two independently loaded/decorated bots, long EXP plus bot-save suppression against the clone, generated merchant registration, completed arrival choreography and removal from map/channel storage. This does not prove visual appearance, real-client interactions, active bot scheduler cleanup or high-population performance.

The current visual test service is `mapleroot-integration-town-test.service`, with login port 8486 and channel ports 7685–7692; the earlier FM-only profile is stopped. It seeds two FM bots and a merchant in classic room 1; automatic bulk population is off. `WorldExpansion/TestClient/` is separate from the working baseline client and uses the same validated DLLs with private map/UI overlays. Root launcher: `Start MapleRoot SoloMapling Test.cmd`. Initial FM, portal, merchant, town movement, buff and party/hunting checks have since been confirmed in the evidence below; deeper playerbot checks remain deferred. Progress in this cloned database is disposable and will not be merged into live player data.

Evidence and exact artifact hashes: `WorldExpansion/isolated-runtime-evidence.json`, `WorldExpansion/database-map-preflight.json`, `WorldExpansion/ServerWork/target/surefire-reports/`, `WorldExpansion/TestClient/world-test-manifest.json`. Test-only launchers/probes are under `WorldExpansion/Smoke/` and are compiled separately from the production server JAR. See `WorldExpansion/TESTING.md` for the user checks and operator controls.

## Addons: preparation only

The windowed cash-shop module was extracted and compiled in an experimental client build. Production integration is off. Packet routing, lifecycle cleanup, server purchase consistency and catalog integration remain open. Weather/time is queued next; other files under `Z:/MSv83/addons` are not considered installed.

## Live environment

- SSH: `root@SERVER_HOST`.
- Server root: `/opt/mapleroot/MapleRoot Full Repack/Server`.
- Service: `mapleroot-control.service`, currently a transient systemd unit.
- The source and running installation share that directory. Treat edits there as live changes.
- No SoloMapling/map/addon deployment or server restart has been performed during this work.

## Recovery backups

Created and integrity-checked October 18, 2026, before live SoloMapling deployment:

- LXC: `/opt/mapleroot/backups/pre-solomapling-20261018T225200Z/`.
- Windows: `Z:/MSv83/backups/pre-solomapling-20261018T225229Z/`.

The server backup contains the complete installation, a fresh database dump covering InnoDB and MyISAM tables with table locks, and service settings. The Windows folder holds a hash-verified off-server copy plus the full physical Windows workspace ZIP. All ZIP entries passed CRC checks; directory junctions are recorded for restoration and their target data is included once. See that folder's README.txt and manifests.

These are application backups, not a whole-LXC/OS snapshot. The server installation and all 93 database tables have since been restored successfully into the isolated test environment; an exact Windows recovery and original service restoration have not been rehearsed. Archives contain sensitive database/configuration information and must stay private.

## Deployment gate

Before a live switch: complete the integration review and meaningful tests; prepare reversible map/database migration; build an isolated test installation and matching client; verify runtime behavior there; record artifact hashes and rollback steps. Live restart/deployment is a separate step after the concrete result is ready for review.

## Town FM routing and merchant follow-up

The user confirmed classic FM entry, exit back to Arcane and roaming FM bots in the initial isolated client. They reported the added Arcane portal did not activate and the generated merchant could not be entered. These are partial results, recorded in `Evidence/solomapling-initial-manual-results.json`.

The updated isolated build uses a visible scripted portal (type 7) for Arcane-to-FM access. All 27 town FM portals found in a read-only live map inventory now have classic FM entry scripts. Three previously missing scripts—market34, market110 and market201—were supplied. Town entrances and the Arcane link use a new CLASSIC_FREE_MARKET saved-location slot, leaving the main hub's FREE_MARKET bookmark intact. Classic FM exits return to their entry location, with Arcane as fallback. The main hub button continues to select Arcane. Migration 002 appends the new database enum value without reordering existing values; forward/rollback and bookmark preservation passed in a separate rehearsal schema.

The inherited merchant visitor code attempted to copy Visitor wrappers directly into a Character array. It now unwraps each visitor and preserves empty slots. A regression test covers sparse visitor arrays and snapshot independence. An updated headless smoke run successfully entered and left a generated merchant with a bot.

This update is installed only in the test server/client. Test files and database have pre-update recovery copies recorded in `WorldExpansion/town-portals-install.json` and the client manifest. Exact new JAR hash and check counts are in `WorldExpansion/town-portals-update.json`; smoke evidence is in `WorldExpansion/town-portals-runtime.json`. Human retesting of the new portal, town round trips and merchant entry remains pending.

## Confirmed portal and merchant retest

The user confirmed Henesys → classic FM → Henesys, the Arcane-to-FM portal and generated merchant entry all work in the updated isolated client. Evidence/solomapling-town-portals-merchant-validated.json ties those results to the server JAR and client asset manifest. MR-017 and MR-018 are complete for the isolated-test scope. This does not establish every town at runtime, purchase/meso correctness, the main hub button in this retest, town population or readiness for live deployment.

## Confirmed merchant purchase

The user bought a Doombringer from the generated merchant and confirmed it appeared in equipment inventory and the listed mesos were deducted. Evidence/solomapling-merchant-purchase-validated.json records the tested JAR and limited scope. One ordinary purchase is validated; insufficient funds, full inventory, concurrent purchases and restart persistence remain untested.

## Small town pilot

The updated isolated build adds explicit cleanup of active brains, wandering/dynamic movement, party/trade queues and recruiting handoffs before bot removal. Clearing a bot's trade clears its queued invite on either side. SocialBot has an explicit variant constructor for the test cohort; its original default 30% interactive choice remains unchanged. Log review also found two inherited/compatibility null cases: optional login equipment refresh now skips absent items, and spawning a hidden player into a headless bot client no longer dereferences a nonexistent player connection.

Twenty Java checks pass. A new headless smoke ran seven actual town bot brains/wander workers for 30 seconds, then removed them and verified no bot scheduler entries, movement state or registered brains remained. No exceptions/errors were logged in that smoke invocation. This is observed lifecycle coverage, not a guarantee of every possible concurrent teardown path or real-client interaction.

The explicit pilot contains seven town bots: Henesys has two wanderers and one interactive Priest; Orbis and Arcane each have one wanderer and one interactive Priest. Two FM bots and the room-1 merchant remain. Bulk population, tutorial admin grants and training population remain off. The current visual test profile is mapleroot-integration-town-test.service on the same test ports; mapleroot-integration-test.service is the stopped FM-only profile. Do not start both profiles together. The live mapleroot-control.service remains unchanged.

See WorldExpansion/TOWN-PILOT.md for movement, hs pls, chat and party test instructions. Exact build, recovery checkpoint and automated evidence are in WorldExpansion/town-pilot-update.json, town-pilot-install.json, town-pilot-runtime.json and town-pilot-java-checks.json. Human town movement, buffs, chat and party behavior are pending confirmation.

## Confirmed town movement and support buff

The user confirmed town bots are visible and move normally in Orbis, and that xGachAddict responded to hs pls and granted Holy Symbol. Evidence/solomapling-town-movement-holy-symbol-validated.json records the build and current server health inspection. This validates the tested Orbis movement and support-buff path; other towns, party/following, trading and training still need checks.

## Confirmed bot party joining

The user confirmed bot party joining works in the town pilot. Evidence/solomapling-party-join-validated.json records the exact JAR and current log inspection. Following, map transitions and leave/disband cleanup remain unconfirmed. The earlier They seem busy message was traced to the existing five-minute social interaction cooldown; it was not a server crash and the cooldown was not modified.

User reported taking the party to Henesys Hunting Grounds and starting to grind. Evidence/solomapling-party-hunting-session.json records this observation and log inspection. Bot attacks, shared EXP and normal-portal following still need explicit confirmation.

## Hunting confirmation and deferred drop fix

The user confirmed the party bot attacks monsters and their EXP bar increases. This establishes those observed behaviors, not the precise shared-EXP calculation. Hunting logs also revealed item/meso drop packet construction dereferencing a nonexistent player on headless bot connections. Candidate guards and regression tests exist in the local ServerWork copy only; they have not been deployed or runtime-validated. At the user's request, MR-020 is deferred for coordination with another SoloMapling repository where further changes are underway. No restart or deployment is part of this deferral.

Parallel SoloMapling development repository: [joshhmann/SoloMapling](https://github.com/joshhmann/SoloMapling). Coordinate MR-020 and subsequent SoloMapling changes with this repository before applying overlapping changes.

MR-020 follow-up: user authorized implementation on a branch. Pushed [fix/headless-drop-delivery](https://github.com/joshhmann/SoloMapling/tree/fix/headless-drop-delivery) at commit 185bb2340. BotClient recipients are skipped before constructing ranged spawn packets; object placement and MapItem spawn delivery also guard headless clients. Existing map registration/visibility code and real-player packet formats remain unchanged. Full repository Maven suite passed: 1,919 tests, zero failures/errors/skips, including five new regression tests. This supersedes the earlier deferral for branch implementation only; in-game validation, merging and deployment remain outstanding. No running server was changed.

Core candidate packaging complete: every distributed file hash and ZIP entry passed verification; read-only live file/destination preflight passed after separately verifying two resource baselines omitted from the original snapshot. Final archive hash and scope are in Evidence/core-candidate-20261018.json. The candidate is staged outside the live installation; no live deployment or active-session restart occurred. Next development item is MR-011, the windowed cash shop, followed by MR-012 weather/time.

## Addon inventory review

Inspected all eight addons and loose guides; see Addons/REVIEW.md and Addons/Review-20261018/inventory.json. This was static inspection only. Cash shop then weather/time remain the proposed first integrations. Movement keys and mount-info fixes are smaller follow-ups, Monster Book needs Root-specific data generation, check-in/sets need reward and balance work, prism needs its base integration/assets, and crit/range is missing framework headers. The separate damage-width guide changes attack contracts and is deferred from this batch. No addon or playerbot change was applied.

## Windowed cash shop initial test

CashShopWork now provides matching client/server button routing on a third isolated profile (8487). The button opens a field window in place of Rooty when enabled; both production-default feature flags remain off. Cart delivery failures restore inventory and do not charge; generated pet rows are cleaned up on rollback. Full Java suite passed 2,045 tests, three native checks pass, and a real Root inventory probe passed purchase/refusal cases. All 1,839 accepted catalogue entries exist in both client and server data. The test client is open for user confirmation; visuals, special items, relog persistence and quitting with the window open remain unproven. No live deployment, town-session restart, damage-width work or deferred bot fix integration occurred.

Cash-shop manual confirmation: user reports the new window, preview, purchase and movement after closing all work. Recorded in Evidence/cashshop-window-manual-20261018.json; this does not yet establish relog persistence, map/channel cleanup, special items or quitting with the window open.

Cash-shop relog persistence is user-confirmed for the tested items and NX. The user reported normal quit with the shop open, but final exit is not yet verified: PID 396/launcher 43108 remained running and the cleanup markers were absent at inspection. Evidence/cashshop-window-persistence-exit-20261018.json distinguishes that report from automated observation; shutdown verification is still pending.

Shutdown clarification resolved: after returning to login, the user closed the entire game. PID 396 exited with code 0; CASHSHOP_RESOURCES_RELEASED, core resource release and SESSION_CLEANUP_COMPLETE all appeared. This validates clean shutdown after returning to login. Direct quit while the shop is still open, resolution/map/channel behavior and special-item checks remain separate outstanding cases.


## Weather/time test preparation

MR-012 is now in progress. WeatherWork builds on the manually checked windowed cash shop; 2,053 Java tests and three native checks pass. This profile includes visual clock/weather/lamps only, with no combat modifiers, debuffs or weather physics. Separate database and client use port 8488. Outdoor/classic-FM classification includes scenery aliases; unknown Arcane backdrop remains unchanged. Manual visuals, transitions and native shutdown are pending. See Evidence/weather-time-initial-20261018.json. Live server and baseline client remain unchanged.


## Weather visual acceptance complete

User confirmed all planned visual checks: day/night, rain, snow, indoor suppression/restoration, resolution and channel changes, classic FM, SHOP preview/close and movement. Normal game exit after opening SHOP released weather/shop resources and completed native cleanup with exit code 0. MR-012 is complete for this isolated visual scope; remaining lamp polish, additional profiles and live rollout are separate. Evidence/weather-time-acceptance-20261018.json records the result. The live installation and baseline Windows client remain unchanged.


## Movement keys in testing

MR-025 adds four draggable direction actions to keyboard settings in a separate client build, retaining weather and SHOP. Nine binary anchors and four native tests pass against actual themed UI files. Server already accepts/stores these keymap entries, so no server update was needed. Manual assignment, movement, text-focus, persistence and shutdown are pending. Baseline/live remain unchanged.


## Movement keys accepted

MR-025 passed the planned isolated checks: assignment and movement, physical arrows, chat and SHOP typing, relog persistence, and clean final native exit. The weather FM flicker follow-up is also resolved: door opacity animations retain native control and user confirmed rain/night/snow look normal. Five native tests pass. Evidence/movement-keys-acceptance-20261018.json records the final DLL and session hashes. No server update or live deployment occurred. Next proposed client polish is the mount-window sizing/name fixes.

## Mount info in testing

MR-026 combines preview fitting and long-name clipping in one Draw hook, preserving full item names outside that draw and Root equipment routing. Six native tests pass; separate test client uses existing isolated server on 8488. Manual appearance and shutdown remain pending. See Evidence/mount-info-initial-20261018.json.


## Mount-info acceptance complete

MR-026 passed on the verified navel-composed-v3 renderer: Red Draco remains assembled and inside the box during animation; long-name clipping preserves full inventory names; pages/reopening work. Seven native tests pass and final normal exit released mount/weather/shop/Canvas resources with exit code zero. Earlier stale-DLL test mistake is corrected and documented in evidence. No server changes or live deployment. Next proposed addon is Monster Book with MapleRoot-specific data.
