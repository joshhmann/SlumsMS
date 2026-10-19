# MapleRoot task list

Updated: October 18, 2026. These are local project tickets, not published GitHub issues. Status meanings: DONE = evidence exists for the stated scope; IN PROGRESS = working-copy implementation; OPEN = outstanding; DEFERRED = user chose later.

| ID | Status | Task / acceptance |
| --- | --- | --- |
| MR-001 | DONE | Kaentake/MapleRoot bridge, avatar fix, resolution and cleanup. Retain session logs, native checks and tested binary hashes. |
| MR-002 | DONE | Classic blue/cream UI. User confirmed appearance and control alignment, including expanded quickslots. |
| MR-003 | DONE | Representative gameplay baseline. See PROJECT-STATUS.md and individual Evidence records for exact coverage. |
| MR-004 | DEFERRED | Test earned EXP above signed 32-bit on a suitable rebirthed character; verify display, threshold and stability. |
| MR-005 | OPEN | Broader custom class/damage, cosmetics, special chair, boss and full-login-return coverage. Record actual actions/builds. |
| MR-006 | DONE | Pre-deployment application backup: full live installation, consistent database dump, service settings and Windows workspace. Archive integrity and off-server hashes passed. Restore rehearsal remains MR-013. |
| MR-007 | IN PROGRESS | Combined build and 20 compatibility/interaction/portal/lifecycle tests passed. Event, party, shop and messenger hooks added with failure isolation. Bot/merchant save boundaries tested. Player PacketCreator and damage handler still match live source. Active scheduler/movement cleanup and clearing either bot trade are implemented with tests and a real seven-bot lifecycle smoke; template/startup failure review and full real-player trade acceptance remain open. |
| MR-008 | IN PROGRESS | All 46 maps pass asset checks and load in isolated server. Seven-table migration passed offset, idempotence and complete rollback checks on clone. Await real-client routes, NPC services, DK events and merchant checks; no live migration. |
| MR-009 | DONE | Dedicated banned template account, non-GM template ID 5, isolated MySQL clone on 3307, test server on 8486/7685–7692 and separate TestClient created. Two FM bots and one room-1 merchant seeded; bulk population remains off. Scope is test infrastructure, not production readiness. |
| MR-010 | IN PROGRESS | User confirmed classic FM entry/exit, bot roaming, town/Arcane routes, merchant entry and a Doombringer purchase with correct meso deduction. Seven town bot workers and cleanup now pass headless smoke. Real-client town movement, chat/buffs, party/trade, training and purchase edge cases remain pending. |
| MR-011 | IN PROGRESS | Windowed cash shop. Experimental module compiles; production flag off. Finish routing/tick/cleanup/assets/server integration and prevent partial purchases granting free items. Test purchases, failures and normal exit. |
| MR-012 | DONE (isolated visual scope) | Weather acceptance passed; follow-up FM door opacity conflict fixed and confirmed under rain/night/snow. Corrected combined client exits cleanly. Combat/debuff/physics excluded; live rollout remains separate. |
| MR-013 | IN PROGRESS | Full installation and all 93 original database tables restored into isolated target; map migration reversal rehearsed. Exact Windows recovery, live-service recreation and final executable deployment rollback remain pending. |
| MR-014 | OPEN | Prepare reviewed deployment bundle with exact hashes, matching client/server assets, database migration, backup precheck and rollback instructions. Schedule any live restart with the user after isolated testing. |
| MR-015 | OPEN | Review remaining addon candidates individually: prism, crit/range, check-in, movement keys, Monster Book, sets and mount UI. Preserve current baseline and avoid overlapping hooks. |
| MR-016 | OPEN | SoloMapling casino/event extras: audit chip item IDs, NPC 9000055, shop 9999001 and WZ patcher against Root data before enabling. Lossless chip exchange is not wired. Intentionally retain Root NPC/portal availability instead of upstream old-version filters. |
| MR-017 | DONE | Town FM routing implemented in isolated test. Inventory covers 27 portals; three missing scripts supplied. Dedicated return bookmarks, enum migration rehearsal and GraalJS route tests pass. User confirmed Henesys → classic FM → Henesys and the Arcane-to-FM portal. Other town runtime coverage and hub-button retest remain MR-008; live deployment remains MR-014. |
| MR-018 | DONE | Merchant visitor ArrayStoreException fixed in isolated test. Sparse-array regression and bot enter/leave smoke pass; user confirmed merchant entry works. Purchase/meso correctness remains MR-010; live deployment remains MR-014. |
| MR-019 | IN PROGRESS | Small town pilot: three bots in Henesys, two in Orbis, two in Arcane, plus the two existing FM bots. Explicit interactive support Priests and wanderers only; bulk population off. Twenty Java checks and real worker/despawn smoke pass. User confirmed Orbis visibility/movement and Holy Symbol response/grant on channel 1. Other towns, party recruitment/following and broader interaction checks remain pending. |
| MR-020 | IN PROGRESS | Headless bot drop delivery guards pushed to [fix/headless-drop-delivery](https://github.com/joshhmann/SoloMapling/tree/fix/headless-drop-delivery) in joshhmann/SoloMapling, commit 185bb2340. Full repository suite passed: 1,919 tests, zero failures/errors/skips, including five new regressions. Preserve real-player packet/quest behavior. In-game item and immediate/delayed meso drop validation and MapleRoot integration deployment remain pending. No running server changed. |
| MR-021 | DONE | Freeze core candidate for addon development: 2,037 Java tests pass, 22,690 rebuilt class entries match the tested JAR, independent bots-disabled startup/map smoke passes with no logged exceptions, and native/UI/map checks pass. This ticket covers preparation only, not live installation or complete gameplay parity. |
| MR-023 | DONE | Inspect all eight addon archives and loose guides without installing. Record hashes, compatibility/dependency findings, message values and proposed order in Addons/REVIEW.md. This is static review, not build or runtime validation. |
| MR-024 | DEFERRED | 64-bit damage guide and attack-packet width changes; user explicitly deferred while cash shop and other addons proceed. Separate from long EXP. |
| MR-025 | DONE (isolated test scope) | Direction bindings, physical arrows, chat/SHOP text focus and relog persistence passed. FM weather fade conflict corrected and confirmed. Five native tests pass; complete cleanup and exit code 0 verified. No server update or live deployment. |

| MR-026 | DONE (isolated test scope) | Combined mount preview fitting and long-name clipping accepted on verified navel-composed-v3 build. Multipart origins/anchors and whole-frame rendering fixed. Seven native checks pass; page/reopen/tooltip checks and complete cleanup/exit 0 verified. No server update or live deployment. |

## Change log



- October 18: validated Kaentake/Root client and classic UI through the user checks recorded in Evidence/; explicitly deferred the large-EXP test.

- October 18: captured actual live source/scripts/maps read-only; pinned SoloMapling upstream and prepared separate map-only and full-integration workspaces.

- October 18: compiled the map prototype and ongoing SoloMapling merge; added map preservation checks and four Java compatibility tests. No live deployment.

- October 18: created and verified complete application/workspace backups after the user asked about recovery coverage; added an off-server copy.

- October 18: created this task list and PROJECT-STATUS.md at the user's request for a paper trail. Continuing MR-007 and MR-008.

- October 18: guarded interaction hooks and persistence boundaries compile; eight Java tests pass. Preserved player packet/damage source hashes against live snapshot.

- October 18: restored installation/database into a separate test environment, created a dedicated template, rehearsed migration/reversal and passed combined headless smoke checks. Started a small visual test server and opened its matching client; awaiting user map checks.



- October 18: user confirmed classic FM entry, exit to Arcane and roaming FM bots. User reported broken Arcane access portal and merchant entry; recorded failures alongside successful checks.

- October 18: restored 27 town FM portal routes, supplied three missing scripts, added a separate persistent return bookmark and corrected the Arcane portal type. Fifteen Java tests and seven map tests pass; migration 002 rollback/idempotence rehearsed. Fixed merchant visitor conversion and passed a bot enter/leave smoke. Installed only in isolated environment after user closed the test client; visual retest pending.



- October 18: user confirmed Henesys → classic FM → Henesys, Arcane-to-FM portal and merchant entry all work in the updated isolated client. Recorded exact build/asset references in Evidence/solomapling-town-portals-merchant-validated.json. Closed MR-017/MR-018 for their isolated-test scope; purchases, broader interactions/population and live deployment remain open.



- October 18: user bought a Doombringer and confirmed equipment delivery plus correct listed meso deduction. Recorded in Evidence/solomapling-merchant-purchase-validated.json as partial MR-010 purchase coverage; failure/concurrency/persistence cases remain open.





- October 18: prepared a seven-bot town pilot and fixed active bot cleanup, queued trade cleanup, hidden-player spawning to bot clients and absent optional login equipment. Twenty Java checks passed. Actual town macro/wander workers ran and all seven despawned with no scheduler/movement leftovers or logged exceptions. Current town-profile test service is mapleroot-integration-town-test.service; the earlier FM-only unit is stopped. No live deployment.



- October 18: user confirmed normal Orbis bot visibility/movement and that xGachAddict responded to hs pls and granted Holy Symbol. Evidence/solomapling-town-movement-holy-symbol-validated.json records the tested JAR and server log inspection. Party/trade/training and other towns remain pending.



- October 18: user confirmed bot party joining works after the chat/recruitment test. Evidence/solomapling-party-join-validated.json records the tested JAR and log inspection. Following, map transitions, leaving/disbanding, trades and training remain separate checks. SocialBot busy text was traced to its five-minute interaction cooldown; no cooldown behavior was changed.



- October 18: User reported taking the party to Henesys Hunting Grounds and starting to grind. Evidence/solomapling-party-hunting-session.json records this observation and log inspection. Bot attacks, shared EXP and normal-portal following still need explicit confirmation.



- October 18: user confirmed the party bot attacks and EXP increases during hunting. Drop-delivery exceptions remain a separate issue. At the user's request, deferred MR-020 pending coordination with their other SoloMapling repository; no deployment or restart.



- October 18: user supplied [joshhmann/SoloMapling](https://github.com/joshhmann/SoloMapling) as the parallel development repository; linked it to MR-020 for coordination. This is a reference in the local task list, not a published GitHub issue.



- October 18: attempted to publish MR-020 in joshhmann/SoloMapling at the user's request. GitHub returned HTTP 410: Issues has been disabled in this repository. No GitHub issue was created; local MR-020 remains the tracking record.



- October 18: at the user's request, implemented MR-020 on a separate SoloMapling branch. Adapted guards to this repository's existing bot exclusion rather than copying MapleRoot files. Full Maven suite passed (1,919 tests). Branch pushed; no merge, server update or restart.



- October 18: user chose to finish core integration before further addons and defer deeper playerbot testing. Prepared Core-20261018-r1 with bots disabled, explicit deferred-fix exclusion, matching assets, reproducible source, migration/rollback plan and a separate review database on ports 8487/7785–7792. No running game was restarted.



- October 18: verified all 5,880 release-manifest entries and exact ZIP contents. Native client hashes match the validated baseline. Live preflight passed with zero file drift and no destination-range database rows. Staged the archive under /opt/mapleroot-dev only; live and town-test services remain active and untouched.



- October 18: reviewed all addons at the user's request before implementation. Kept cash shop then weather/time as priority; separated weather gameplay modifiers from visuals. Identified missing crit headers, prism base-system requirements and partial-delivery risks in shop/check-in source. No addon enabled or deployed.



- October 18: prepared CashShopWork from the frozen core, added button/window routing and cart rollback, and launched its matching private test client on 8487. Live and town-test services remain active and unchanged. Evidence/cashshop-window-initial-20261018.json and Addons/CashShopWork/TESTING.md record exact scope and hashes.



- October 18: user confirmed SHOP opens the new field window, item preview and purchase work, and closing restores movement. Evidence/cashshop-window-manual-20261018.json records matched hashes and log review. Relog persistence and quit with the shop open remain pending.



- October 18: user confirmed purchased items and NX balance survive relog. User also reported normal quit with the window open, but game PID 396 and launcher 43108 were still running and no cleanup/exit markers had appeared at inspection. Recorded the distinction and requested clarification; no process was terminated.



- October 18: user clarified the earlier quit meant returning to login, then closed the game. Verified PID 396 exit code 0, cash-shop resource release and complete native cleanup. Updated evidence; direct quit with SHOP still open remains a distinct untested case.


- October 18: weather visual build passed 2,053 Java tests and initial native checks. Isolated profile on 8488 keeps bots and gameplay weather modifiers disabled. User confirmed lighting, rain, snow, indoor suppression/restoration, resolution/channel/FM transitions and SHOP coexistence. Cleanup completed with exit zero. See Evidence/weather-time-acceptance-20261018.json.
- October 18: movement keys built in a separate client. Nine binary anchors verified; actual themed UI icons and mapping tests pass. User confirmed assignments, arrows, chat focus and saved bindings across relog. Prior movement build also completed cleanup with exit zero.
- October 18: user reported FM door flicker during weather. First portal-effect tint exclusion did not resolve it. Asset inspection found ten backing frames fading alpha between 15 and 255 over 500 ms. Extended exclusion to that backing; five native checks pass. Second visual retest pending. Evidence/weather-fm-portal-fix-20261018.json records both attempts.

- October 18: user confirmed the second FM portal fix removes flickering under the requested rain/night/snow retest. Native logs verify door-fade layers remain under native animation control. Final SHOP search typing and corrected-build shutdown remain pending for MR-025.

- October 18: user confirmed SHOP search typing and normal movement with WASD assigned, then closed the game. Verified weather/shop/Canvas release, complete native cleanup, PID 59984 exit code zero and both processes exited. Closed MR-025 for isolated tested scope; weather FM follow-up resolved. See Evidence/movement-keys-acceptance-20261018.json.

- October 18: user confirmed King Cro and its matching saddle both equip, riding works and character-info preview stays inside its box. Initial Carnival test used an unsuitable standard saddle; corrected to 1912002. Long-name/page-switch/shutdown checks remain pending for MR-026.

- October 18: long-name/tooltip/reopen checks passed. Larger Red Draco test exposed missing body: guide-based loader used only part 0 of each frame. Actual asset has 12 frames with three artwork parts each. Loader now includes all parts in draw order and fits their union; six native checks pass. Asset contact sheet rendered; installation/retest pending. Parsec freeze report remains a separate unproven cause; final clean shutdown still pending.

- October 18: multipart Red Draco build still flickers/pulses by user report. Reworked rendering to precompose all artwork and the native preview background per frame, then use one whole-frame blit. New standalone PCOM test loads actual 12-frame/36-part asset and confirms reused destination equals each complete frame pixel-for-pixel. Seven native tests pass; visual retest pending.

- October 18: composed-frame preview still failed flap motion by user report; screenshot showed detached head/body. Actual WZ map/navel anchors differ per part. Added attachment-anchor alignment before shared fitting/composition and regression assertions for actual Red Draco anchors. Seven tests pass. Rendered all-frame contact sheet and verified native PCOM output show joined body; corrected build awaits installation/manual retest.

- October 18 CORRECTION: shared render header lacked its own WZ include. DLL compilation failed while standalone tests passed; prior frame-cache/anchor installations incorrectly reused the older multipart DLL. Those retests do not validate the intended fixes. Fixed header dependency, require checked full build exit, and verify renderer marker inside DLL. Actual complete DLL now builds; seven checks pass. First actual aligned/composed renderer test remains pending.

- October 18: verified startup marker navel-composed-v3 in the actual game, then user confirmed full Red Draco animation looks correct without clipping/flicker. This is the first actual aligned/composed renderer acceptance. Final name/reopen regression and corrected-build shutdown remain pending for MR-026.

- October 18: final verified mount-info build passed name/tooltip/page/reopen retest. User closed normally; log confirms mount/weather/shop/Canvas release, complete cleanup and PID 18424 exit zero, with both processes gone. MR-026 complete for isolated scope. See Evidence/mount-info-acceptance-20261018.json.
