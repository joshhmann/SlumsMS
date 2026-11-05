# Build, packaging and tests

## Purpose

One CMake project (`CMakeLists.txt`) builds three things on a Windows/MSVC x86 toolchain: `slums.dll` (the injector,
target `injector`, `src/CMakeLists.txt:2`), `SlumsMS.exe` (the starter, target `launcher`, `src/CMakeLists.txt:150`), and
— only when `MAPLEROOT_INTEGRATION=ON` — the `mapleroot_companion` static library that is linked *into* `slums.dll`
(`mapleroot/CMakeLists.txt:4`, `src/CMakeLists.txt:124`). Alongside them it builds 40 native test executables
(`tests/CMakeLists.txt`) and registers them with CTest.

Everything that makes the library a *SlumsMS* build rather than an upstream Kaentake build is a configure-time option
(all of them `OFF` by default, `CMakeLists.txt:3-18`); the release path is `scripts/build.ps1`, which turns eleven of
them on and runs CTest (`scripts/build.ps1:11-18`). There is no `install()` rule anywhere in the tree, so "packaging"
is the POST_BUILD copies plus the release tooling that lives in the *server* repo, not here.

## Entry points

| Entry | Where | What it does |
|---|---|---|
| CMake project | `CMakeLists.txt:1-40` | `project(SlumsMS)`, C++17, includes `cmake/BuildStamp.cmake`, `add_subdirectory(external)`, optional `mapleroot`, `src`, and `tests` |
| Release build script | `scripts/build.ps1:1-19` | deps verify → submodules → VS detection → configure → build → ctest |
| Dependency verifier | `scripts/prepare-dependencies.ps1:1-13` | SHA-256 of the four `dependencies.json` files; extracts the CppWinRT nupkg |
| Presets | `/root/work/client/src-164-data/CMakePresets.json:8-35` | `debug-win32` / `release-win32`, both `Visual Studio 17 2022` + `Win32`, both `binaryDir ${sourceDir}/build` |
| Build-stamp module | `cmake/BuildStamp.cmake:1-49` | reads `VERSION`, computes `SLUMS_BUILD_NUMBER`, validates overrides |
| CI | `.github/workflows/build.yml:1-21` | `windows-2022`, runs `./scripts/build.ps1`, uploads artifacts (paths stale, see Known issues) |
| External deps | `external/CMakeLists.txt:1-24` | Detours (static), WzLib (`add_subdirectory`), FreeType 2.13.3 (`EXCLUDE_FROM_ALL`) |
| Companion | `mapleroot/CMakeLists.txt:1-59` | 42 sources incl. Dear ImGui 1.91.4 DX9/Win32 + `imgui_freetype` |
| Tests | `tests/CMakeLists.txt:1-179` | 40 `add_executable`, 40 unique `add_test` names (47 `add_test` calls; 7 are if/else pairs) |

The *authoritative* configure flags are **not in this repo**. `AGENTS.md:171` (server repo) and
`docs/sessions/EXTERNAL-AGENTS.md:194` tell helpers to take them from
`client-1.6.2-rc1\prep-build-rc1.ps1`. That file does not exist in `/root/work/client/src-164-data`
(`grep -rn prep-build` over the tree: 0 hits; `scripts/` holds only `build.ps1` and `prepare-dependencies.ps1`). It lives
on the owner's Windows box; the in-repo equivalent is `scripts/build.ps1:11`.

## How it works

`scripts/build.ps1` in order:

1. **Dependencies.** `/root/work/client/src-164-data/scripts/prepare-dependencies.ps1:3-8` reads `dependencies.json`, `throw`s `Missing dependency: <name>` or
   `Dependency hash mismatch: <name>` (lower-cased SHA-256 compare at `:7`), then `:9-13` extracts
   `vendor/Microsoft.Windows.CppWinRT.2.0.210806.1.nupkg` into `vendor/packages/…` when that folder is absent.
2. **Submodules.** `git submodule update --init --recursive` (`scripts/build.ps1:5`); failure throws
   `Submodule setup failed` (`:6`). This is mandatory: the three submodules are empty in a fresh clone
   (`git submodule status` shows `-9764ceb Detours`, `-098795e WzLib`, `-42608f7 freetype`) and `CMakeLists.txt:29`
   would fail immediately.
3. **Toolchain check.** `vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64`
   (`:7-9`), else `throw 'Visual Studio C++ x86 tools are required'`.
4. **Configure.** `-S <root> -B <root>/build -G "Visual Studio 17 2022" -A Win32` plus eleven `-DMAPLEROOT_*=ON` and
   `-DMAPLEROOT_SHUTDOWN_SMOKE_TEST=OFF` (`:11`). With `-TestClient <dir>` it appends
   `-DMOVEMENTKEYS_TEST_CLIENT=<dir>` (`:12`). A non-zero exit throws `Client configuration failed` (`:14`).
5. **Build.** `cmake --build <root>/build --config Release --parallel 4` (`:15`), else `Client build failed`.
6. **Test.** `ctest --test-dir <root>/build -C Release --output-on-failure` (`:17`), else `Native tests failed`.
7. **Report.** Prints the expected outputs (`:19`): `SlumsMS.exe`, `slums.dll`, `ijl15.dll`, `discord_game_sdk.dll`,
   `Custom.wz` in `build/Release`.

Inside configure, `cmake/BuildStamp.cmake` computes the login stamp:

8. `file(READ VERSION)` + `string(STRIP)` (`:14-15`); regex `^([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.]+)?$` else
   `FATAL_ERROR "VERSION must look like 1.4.2 or 1.4.2-rc1, not '…'"` (`:16-18`).
9. Guard minor > 98 or patch > 9 (`:22-24`), then
   `math(EXPR _slums_build "${major} * 10000 + (${minor}+1) * 100 + ${patch} * 10")` (`:25`). For the current
   `VERSION` `1.6.4-rc3` that is `10000 + 700 + 40 = 10740`, matching commit `9b5dcda`. The comment at `:6-9` records
   the pre-1.6.1 formula and why it changed (b205).
10. `SLUMS_VER_MAJOR/MINOR/PATCH`, `SLUMS_RELEASE`, `SLUMS_BUILD_NUMBER` (`:27-31`); overrides
    `SLUMS_RELEASE_OVERRIDE` / `SLUMS_BUILD_NUMBER_OVERRIDE` (`:32-37`, cached until `-U`); `CMAKE_CONFIGURE_DEPENDS`
    on `VERSION` (`:38`) so a reused build folder re-stamps; build must be a non-negative 32-bit int (`:39-41`),
    release charset `[0-9A-Za-z._+-]` (`:42-44`), length ≤ 40 ("the server ignores longer stamps", `:45-48`).
11. `src/CMakeLists.txt:33` broadcasts the two values as `SLUMS_BUILD_NUMBER` / `SLUMS_RELEASE` defines;
    `:34-35` `file(GENERATE)` writes `slums-build.txt` (`release=…\nbuild=…`) into `${CMAKE_BINARY_DIR}/$<CONFIG>/`;
    `:38-40` refuses a build > 65535 because VERSIONINFO is 16-bit; `:41-42` `configure_file(slums.rc.in)` → `slums.rc`
    and adds it to the target.
12. **Option blocks** add sources and the matching `MAPLEROOT_*` define to `injector` (see the option table). The
    shared `packetdispatcher.cpp` is pulled in when *any* packet-carrying option is on (`src/CMakeLists.txt:97-99`).
13. **Output naming.** `OUTPUT_NAME slums` (`:138-140`), `OUTPUT_NAME SlumsMS` (`:163-165`); both land in
    `${CMAKE_BINARY_DIR}/$<CONFIGURATION>` (`CMakeLists.txt:25-26`).
14. **POST_BUILD copies.** `Custom.wz` from `src/` (`:142-146`); with `MAPLEROOT_INTEGRATION` also
    `vendor/ijl15/ijl15.dll` and `mapleroot/ijl15/discord_game_sdk.dll` (`:125-130`) — the game loads the real Intel
    `ijl15.dll`, the MapleRoot proxy is not built (`mapleroot/CMakeLists.txt:2-3`).
15. **CTest registration.** `include(CTest)` + `if(BUILD_TESTING)` (`CMakeLists.txt:37-39`). `BUILD_TESTING` defaults
    `ON` (CTest module), and `CMakePresets.json` sets no `cacheVariables`, so a preset build also compiles all tests.

`VERSION` is `1.6.4-rc3` at HEAD `9b5dcda` (branch `client-164-rc3`).

## Data

| Item | Pin / value | Where |
|---|---|---|
| `vendor/Microsoft.Windows.CppWinRT.2.0.210806.1.nupkg` | sha256 `6b6f3b24…f16a0`, 632773 B | `/root/work/client/src-164-data/dependencies.json:2-5` |
| `mapleroot/ijl15/discord_game_sdk.dll.lib` | sha256 `e7b4529a…f025fc`, 2358 B | `/root/work/client/src-164-data/dependencies.json:6-9` |
| `mapleroot/ijl15/discord_game_sdk.dll` | sha256 `d8e7d9fe…d30cdf`, 3154744 B | `/root/work/client/src-164-data/dependencies.json:10-13` |
| `vendor/ijl15/ijl15.dll` | sha256 `41d37bfb…a5f3a1`, 352256 B | `/root/work/client/src-164-data/dependencies.json:14-17` |
| `external/Detours` | commit `9764cebcb1a75940e68fa83d6730ffaf0f669401`, `https://github.com/microsoft/Detours.git` | `.gitmodules:1-3` |
| `external/WzLib` | commit `098795e27ea388beff147c5f89c7bff09af398ea`, `https://github.com/iw2d/WzLib.git`, **branch `v83_DX8to9`** | `.gitmodules:4-7` |
| `external/freetype` | commit `42608f77f20749dd6ddc9e0536788eaad70ea4b5` (tag `VER-2-13-3`), FTL/GPLv2 | `.gitmodules:8-10`, `docs/PROVENANCE.md:19` |
| `src/Custom.wz` | tracked, 925487 B, copied beside the DLL | `src/Custom.wz` |
| `VERSION` | `1.6.4-rc3` → build `10740` | `VERSION`, `cmake/BuildStamp.cmake:25` |
| `release/baseline.json` | accepted baseline `baseline-20261018-r1`, 5 client flags, `Kaentake.dll` sha256 `a6961058…92d839`, login port 8485 | `release/baseline.json:2` (name), `:12-18` (flags), `:19` (port), `:22-25` (artifacts) |
| `release/build-verification.json` | `client_tests: 7`, `client_source_only_tests: 5`, server 2055 tests / 0 failures | `release/build-verification.json` |
| `assets/` | 9.2 MB, 24 tracked files, **not referenced by any CMakeLists** | `grep -rn assets --include=*.txt --include=*.cmake` hits only the option string at `CMakeLists.txt:6` |

`assets/brand` (5 files, 8.8 M), `assets/colorprism` (12, 75 K), `assets/dailycheckin` (1, 29 K),
`assets/monsterbook` (5, 295 K), `assets/worldmap` (1, 5.0 K) are inputs to the `tools/wz/*.py` packers (14 scripts),
never to the compiler. `tools/wz/test_add_mob_attack_type.py` is a `unittest` that no ctest and no CI step runs.

`tests/` holds exactly `CMakeLists.txt` + 40 `.cpp` files (`ls tests | wc -l` = 41). No test links the `injector` or
`launcher` target; tests `#include "../src/*.h"` and, for four of them, compile a source file directly
(e.g. `tests/CMakeLists.txt:92` adds `../src/movementkeys.cpp ../src/hook.cpp ../src/debug.cpp`).

## Switches

`config.ini` does not govern the build; the build switches are CMake cache variables. Both are listed because a build
option determines whether an ini key can do anything at all.

| CMake cache variable | Effect | Default | Read at |
|---|---|---|---|
| `BUILD_TESTING` | compile + register `tests/` | `ON` (CTest) | `CMakeLists.txt:38` |
| `SLUMS_RELEASE_OVERRIDE` | replaces the `VERSION` string in the stamp | empty | `cmake/BuildStamp.cmake:32-34` |
| `SLUMS_BUILD_NUMBER_OVERRIDE` | replaces the computed build number | empty | `cmake/BuildStamp.cmake:35-37` |
| `MOVEMENTKEYS_TEST_CLIENT` | client dir for the 3 WZ-loading tests + `mount_full_frame_replacement` | `${CMAKE_SOURCE_DIR}/../Addons/WeatherWork/TestClient` (does not exist) | `tests/CMakeLists.txt:90` |
| `CREATEBLOCK_TEST_EXE` | MapleStory.exe whose bytes are checked | empty | `tests/CMakeLists.txt:25` |
| `INVRESIZE_TEST_EXE`, `MOBDAMAGE_TEST_EXE`, `MELEERANGE_TEST_EXE`, `MOBATTACKFIX_TEST_EXE`, `HPMP_TEST_EXE`, `MAPOBJ_TEST_EXE` | same, one cache var each | `Z:/MSv83/SlumsMS-dev/client-1.5.0-fixes2/exe-copy/MapleStory.exe` | `tests/CMakeLists.txt:33,42,51,60,68,173` |
| `FT_DISABLE_ZLIB/BZIP2/PNG/HARFBUZZ/BROTLI`, `SKIP_INSTALL_ALL` | trim FreeType to what ImGui needs | `ON`, `FORCE` | `external/CMakeLists.txt:18-23` |

Per-option build switches (all default `OFF`, `CMakeLists.txt:3-18`):

| Option | Adds to `injector` | Hard requirement |
|---|---|---|
| `MAPLEROOT_MOUNT_INFO` | `mountinfo.cpp` (`src/CMakeLists.txt:43-46`) | — |
| `MAPLEROOT_MOVEMENT_KEYS` | `movementkeys.cpp` (`:47-50`) | — |
| `MAPLEROOT_INV_RESIZE` | `invresize.cpp` (`:51-54`) | — |
| `MAPLEROOT_HUD_METERS` | `hud_meters.cpp` (`:55-61`) | `FATAL_ERROR` without `MAPLEROOT_INTEGRATION` (`:56-58`) |
| `MAPLEROOT_DISPLAY_TOGGLES` | `display.cpp` (`:62-68`) | `FATAL_ERROR` without INTEGRATION (`:63-65`) |
| `MAPLEROOT_DAMAGE_FORMAT` | `damagefmt.cpp` (`:69-75`) | `FATAL_ERROR` without INTEGRATION (`:70-72`) |
| `MAPLEROOT_CRASH_REPORT` | `crash_report.cpp` (`:76-79`) | — |
| `MAPLEROOT_DAILY_CHECKIN` | `dailycheckin.cpp` (`:80-83`) | — |
| `MAPLEROOT_SET_EQUIP` | `setitem.cpp setstatbonus.cpp` (`:84-88`) | — |
| `MAPLEROOT_MONSTER_BOOK` | 4 `monsterbook*.cpp` (`:89-92`) | — |
| `MAPLEROOT_COLOR_PRISM` | `weapontint.cpp coloringprism.cpp coloringprism_hooks.cpp` (`:93-96`) | — |
| `MAPLEROOT_WEATHER` | 11 `weather*.cpp` + `lamps.cpp` (`:100-105`) | — |
| `MAPLEROOT_CASHSHOP_WINDOW` | `cashshopwnd.cpp` (`:106-109`) | — |
| `MAPLEROOT_ATTACK_DIAG` | `attackdiag.cpp` (`:110-113`) | — |
| `MAPLEROOT_SHUTDOWN_SMOKE_TEST` | define only (`:114-116`) | `FATAL_ERROR` without INTEGRATION (`CMakeLists.txt:19-21`) |
| `MAPLEROOT_INTEGRATION` | `mapleroot.cpp crash_diagnostics.cpp bounce.cpp d3d9host.cpp settings_overlay.cpp overlay.cpp hud_vitals.cpp hud_native.cpp uitour.cpp` + `mapleroot_companion` + 2 DLL copies (`:117-131`) | pulls in `mapleroot/` (`CMakeLists.txt:31-33`) |

`scripts/build.ps1:11` enables: INTEGRATION, CASHSHOP_WINDOW, WEATHER, MOVEMENT_KEYS, MOUNT_INFO, DAILY_CHECKIN,
SET_EQUIP, MONSTER_BOOK, COLOR_PRISM, ATTACK_DIAG, INV_RESIZE. It leaves HUD_METERS, DISPLAY_TOGGLES, DAMAGE_FORMAT,
CRASH_REPORT and SHUTDOWN_SMOKE_TEST `OFF`; `grep -rn "HUD_METERS|DISPLAY_TOGGLES|DAMAGE_FORMAT|CRASH_REPORT" scripts/ .github/ release/`
returns nothing, so those four never ship from the scripted path.

`config.ini` keys that a build option gates (defaults are the code defaults, which differ from the ini default):

| Key | Default | Read at | Only does anything when |
|---|---|---|---|
| `[clienthax] invresize` | `true` (`value_or(true)`) | `src/invresize.cpp:548-552` | `MAPLEROOT_INV_RESIZE` |
| `[clienthax] jumpkeydown` | off — needs literal `true` or `1` | `src/midair.cpp:16-17` | always built (`src/midair.cpp` is unconditional, `src/CMakeLists.txt:24`); TEST only (ticket 126, `4fd703d`) |
| `[clienthax] mapobjguard` | `true` | `src/lamps.cpp:1496-1506` | `MAPLEROOT_WEATHER` (`src/lamps.cpp` is in that source block, `src/CMakeLists.txt:100-105`) |
| `[debug] attackdiag` | `0` = off | `src/attackdiag.cpp:211-215` | `MAPLEROOT_ATTACK_DIAG` |

No build or test path reads `config.ini` (`grep config.ini` over `CMakeLists.txt`, `src/CMakeLists.txt`,
`tests/CMakeLists.txt`, `scripts/*.ps1`, `.github/workflows/build.yml` hits only the option descriptions at
`CMakeLists.txt:12-13`); the single textual mention in tests is a case label, `tests/attackstop_patch_test.cpp:96`.
There is no legacy-key fallback in this subsystem — no CMake option or cache variable has a deprecated alias.

## Invariants

| # | Invariant | Evidence |
|---|---|---|
| 1 | Submodules must be initialised before configure | `CMakeLists.txt:29`, `scripts/build.ps1:5-6`, `AGENTS.md:169-170` |
| 2 | The four `dependencies.json` files must hash exactly; a mismatch stops the build before configure | `/root/work/client/src-164-data/scripts/prepare-dependencies.ps1:6-7` |
| 3 | `VERSION` must match `X.Y.Z[-tag]`, minor ≤ 98, patch ≤ 9 | `cmake/BuildStamp.cmake:16-24` |
| 4 | `SLUMS_BUILD_NUMBER` must fit both 32 bits and 16 bits (VERSIONINFO) | `cmake/BuildStamp.cmake:39-41`, `src/CMakeLists.txt:38-40` |
| 5 | `SLUMS_RELEASE` ≤ 40 chars, `[0-9A-Za-z._+-]` only | `cmake/BuildStamp.cmake:42-48` |
| 6 | Reusing a build folder must not keep an old stamp | `cmake/BuildStamp.cmake:38` (`CMAKE_CONFIGURE_DEPENDS`) |
| 7 | `MAPLEROOT_SHUTDOWN_SMOKE_TEST` is meaningless without `MAPLEROOT_INTEGRATION` | `CMakeLists.txt:19-21` |
| 8 | HUD_METERS / DISPLAY_TOGGLES / DAMAGE_FORMAT cannot build without INTEGRATION | `src/CMakeLists.txt:56-58,63-65,70-72` |
| 9 | `slums.dll` and `SlumsMS.exe` land in `build/<Config>/`, never `build/` | `CMakeLists.txt:25-26` |
| 10 | Golden-packet tests mirror the server and must change together | `tests/bounce_rules_test.cpp:1-2`, `tests/setitem_proto_test.cpp:2`, `tests/weapontint_proto_test.cpp:2`, `tests/buildstamp_proto_test.cpp:2`; rule at `AGENTS.md:14-15` |
| 11 | A failed configure, build or test stops the script; no stale DLL is reused | `scripts/build.ps1:14,16,18` |
| 12 | `SlumsMS.exe` is not reproducible → a publish ships only `slums.dll`, `Custom.wz`, `slums-build.txt` | `AGENTS.md:191` (review repo); `docs/research-121-crash-report.md:19,230` |

## Origin

| Path | Origin | Evidence |
|---|---|---|
| `CMakeLists.txt` | **base (Kaentake)** + modified | `git log --follow`: `de776d0` 2025-08-26 Initial commit, `7d36862` 2025-08-27 WzLib, `91bccb5` 2025-12-09 workflow; the 16 `MAPLEROOT_*` options were added later by checkpoints |
| `CMakePresets.json` | **base (Kaentake)** | `de776d0` 2025-08-26, untouched since apart from `7d36862`; `docs/PROVENANCE.md:3` names `joshhmann/kaentake` at `2a43a01e7f4c…` as the client history start |
| `external/CMakeLists.txt` | **base** + modified | `de776d0` → `7d36862` (WzLib v83_DX8to9) → `bf5e510` 2026-10-25 (FreeType for ImGui) |
| `cmake/BuildStamp.cmake` | **custom** | `66026a3` 2026-10-21 "Report the client build at login (CP 0x3738 build stamp)", `35ff96f` 2026-10-30 (b205) — no Kaentake feature reports a build |
| `scripts/build.ps1` | **custom** | `6119130` 2026-10-18 checkpoint, `f1b64a6` 2026-10-19 (one-DLL rewrite), `019ebc0` 2026-10-19 (DAILY_CHECKIN) |
| `scripts/prepare-dependencies.ps1`, `dependencies.json` | **custom** | `6119130`; `f1b64a6` added the ijl15/Discord entries |
| `tests/` | **custom** | `6119130` 2026-10-18 checkpoint is the first commit of `tests/CMakeLists.txt`; the tree grew to 40 tests by `719820f` 2026-10-20 |
| `mapleroot/CMakeLists.txt` | **custom** | `f1b64a6` 2026-10-19 creates it; comment at `:1-3` records that the companion used to ship as a separate `ijl15.dll` proxy |
| `release/baseline.json`, `release/build-verification.json` | **custom** | `6119130` 2026-10-18, `d82253c` 2026-10-18 (pin asset inputs) |
| `.github/workflows/build.yml` | **custom** | `6119130` 2026-10-18 only |
| `vendor/ijl15/ijl15.dll` | **third-party binary** (Intel JPEG library) | `/root/work/client/src-164-data/dependencies.json:14-17`; copied next to `slums.dll` at `src/CMakeLists.txt:125-130` |
| `vendor/…CppWinRT…nupkg` | **third-party binary**, effectively unused by CMake | only `mapleroot/ijl15/ijl15.vcxproj:3,338` imports it, and that project is no longer built (`scripts/build.ps1` has no MSBuild step since `f1b64a6`) |

Historical detail: at checkpoint `6119130`, `scripts/build.ps1` was 22 lines, built `mapleroot/ijl15/ijl15.vcxproj` with
`MSBuild /p:MapleRootIntegration=true`, used `build-baseline`, and produced `Kaentake.dll` / `Kaentake.exe`. The
one-DLL commit `f1b64a6` removed the MSBuild step, renamed the outputs and moved to `build/` — but left
`.github/workflows/build.yml:18-21` pointing at the old names (see Known issues).

## Tests and QA

40 unique `add_test` names across 40 test executables (47 `add_test` calls; 7 tests have an if/else pair for the
optional `MapleStory.exe` argument). Registration depends on configure state:

| Configure state | Tests registered |
|---|---|
| All options `OFF`, no Data (a plain `cmake` without `build.ps1`) | **28** |
| `scripts/build.ps1` flags, no Data tree | **36** |
| `scripts/build.ps1 -TestClient <client with the 4 images>` | **40** (all) |

The 12 that a bare configure drops: 8 are option-gated (`attackdiag_remote_attack_rules`, `colorprism_wire_format`,
`colorprism_item_text`, `colorprism_window_layout`, `colorprism_swap_walk`, `mount_preview_layout`,
`setequip_wire_format`, `weather_animated_portal_colors`) and 4 need Data/TestClient — `movementkeys_wz`,
`weather_rope_sway_no_box`, `mount_full_frame_replacement`, `inlink_broken_link_no_crash`.

**The README's "five tests" / "seven checks" rule is stale.** `README.md:20` ("Without external Data, only the five
tests that do not require game assets are registered") and `README.md:14` ("all seven checks") were written at
`6119130`, when `tests/CMakeLists.txt` really registered 5 without Data and 7 with it (simulated from
`git show 6119130:tests/CMakeLists.txt`). Today the same two states give 36 and 40.

Every test, what it proves, and whether it needs game assets:

| ctest | Proves | Needs assets? | `add_test` |
|---|---|---|---|
| `buildstamp_wire_format` | CP `0x3738` body bytes equal the server's golden `ClientVersionGateTest` packet | no | `tests/CMakeLists.txt:4` |
| `buildshow_text` | window title / login label formatting (`SLUMS_RELEASE`, build) | no | `:7` |
| `checked_hook_transactions` | hook install/uninstall transaction semantics | no | `:10` |
| `expanded_quickslot_bounds` | rewritten quickslot instruction bounds | no | `:13` |
| `avatar_feature_dependencies` | avatar feature dependency ordering | no | `:16` |
| `petequip_flag_bounds` | which pets overflow the 128-bit pet flag | no | `:19` |
| `proposal_any_gender` | engagement `jne`/NOP patch bytes (exe check optional) | exe optional | `:22` |
| `createblock_never_blocked` | "high population" `je`→`jmp` patch (exe check optional, default empty) | exe optional | `:26-30` |
| `invresize_sites` | resizable-inventory grid math + 14 layout operands (exe check optional) | exe optional | `:35-39` |
| `mobdamage_low_defence` | low-defence branch patch + defence term | exe optional | `:44-48` |
| `meleerange_guard` | `GetMeleeAttackRange` entry guard | exe optional | `:53-57` |
| `mobattackfix_wz_property_epointer` | b251 fatal `IWzProperty` cast site + guard decision | exe optional | `:62-66` |
| `hpmp_sites` | 4-byte HP/MP site table and patched forms | exe optional | `:70-74` |
| `d3d9host_adapter_fallback` | `CreateDevice` retry ladder + HRESULT log names | no | `:77` |
| `customwz_image_prefix` | which path component names a `Custom.wz` image | no | `:80` |
| `overlay_hud_layout` | HUD fractions round-trip, widgets stay on screen | no | `:82` |
| `uilayout_move_stock_ui` | movable-UI packing + clamping | no | `:85` |
| `uitour_first_run` | first-run tour trigger logic | no | `:88` |
| `movementkeys_wz` | direction bindings against the real `UIWindow.img`/`UI.wz`; `LoadLibraryW(<argv1>/PCOM.dll)` | **yes** (`Data/UI/UIWindow.img` or `UI.wz`, `tests/CMakeLists.txt:91`) | `:95` |
| `weather_animated_portal_colors` | weather object policy table | no | `:100` |
| `weather_rope_sway_no_box` | rope/ladder sway frames keep only source pixels | **yes** (`Data/Map/Obj/connect.img`, `:103`) | `:107` |
| `inlink_broken_link_no_crash` | an unfillable linked canvas costs a tile, not the client | **yes** (`Data/Map/Tile/allInvisibleTile.img`, `:110`) | `:114` |
| `mount_preview_layout` | mount preview fit math, scale/box bounds | no | `:119` |
| `mount_full_frame_replacement` | multipart mount frames fit the box | **yes** (`Data/Character/TamingMob/01902002.img`, `:122`) | `:126` |
| `setequip_wire_format` | SetEquip decode equals `SetItemPacketsTest.GOLDEN_*` | no | `:131` |
| `colorprism_wire_format` | prism decode equals `ColorPrismPacketsTest` golden | no | `:136` |
| `colorprism_item_text` | name/desc override answers only `name`/`desc` for 5782000 | no | `:138` |
| `colorprism_window_layout` | every control lands on the measured backdrop rects | no | `:140` |
| `colorprism_swap_walk` | swap walk against a fake WZ tree shaped like `01702200.img` | no | `:142` |
| `attackdiag_remote_attack_rules` | which remote attacks the diagnostic logs | no | `:147` |
| `attackstop_moveattack_sites` | move-attack stop site table + shipped config matrix | no | `:151` |
| `ui_wheel_direction` | wheel up is negative (`GET_WHEEL_DELTA / -120`) | no | `:154` |
| `bounce_doubletap_and_zones` | double-tap jump, block zones, LP `0x3737` golden bytes | no | `:157` |
| `hud_meters_exp_dps` | EXP/hour + DPS aggregation policy | no | `:160` |
| `window_borderless_place` | borderless placement + config switch | no | `:162` |
| `display_toggle_rules` | display/perf toggle rules | no | `:164` |
| `midair_sites_policy` | in-air gate encodings + keydown policy | no | `:166` |
| `damagefmt_short_numbers` | K/M/B formatting + size slider | no | `:168` |
| `crash_report_classify_scrub` | crash classifier and report scrubber | no | `:170` |
| `mapobj_guard_skips_missing_art` | missing-art decision table + log wording + stock sites | exe optional | `:175-179` |

What the suite does **not** cover: nothing loads `slums.dll` into a game process, nothing exercises the launcher, and
no test reads `config.ini` — the four ini-gated features are tested only at the header/policy level
(`src/invresize.cpp:551`, `src/lamps.cpp:1503`, `src/midair.cpp:17`, `src/attackdiag.cpp:212` are not reachable from
`tests/`). `MAPLEROOT_HUD_METERS`, `MAPLEROOT_DISPLAY_TOGGLES` and `MAPLEROOT_DAMAGE_FORMAT` still register their tests
unconditionally (`:159-160`, `:163-164`, `:167-168`), so those pass while the shipping build never compiles the code
they describe.

A real client must confirm by hand, per `docs/evidence/*.json` (all added `6119130`, 2026-10-18):

| Evidence file | Confirmed on a real client | Limitations recorded there |
|---|---|---|
| `docs/evidence/mount-info-acceptance-20261018.json` | mount/saddle equip + riding, multipart Red Draco fits without clipping, long names, page switches, clean exit code 0; `native_tests: 7`, marker `navel-composed-v3` | isolated tested scope |
| `docs/evidence/movement-keys-acceptance-20261018.json` | four draggable directions, WASD, chat typing without movement, relog persistence, SHOP search, FM rain/night/snow portal flicker, clean exit; `native_tests: 5`, `binary_anchors_verified: 9` | broader key combos / long sessions not established |
| `docs/evidence/weather-time-acceptance-20261018.json` | day/night, rain, snow suppression indoors, resolution change, SHOP open during weather, channel change, clean exit | not deployed to live; weather combat/debuff/physics excluded; Henesys lamp filament warning |
| `docs/evidence/cashshop-window-persistence-exit-20261018.json` | purchases + NX survive relog, exit code 0, cleanup markers `CASHSHOP_RESOURCES_RELEASED` / `SESSION_CLEANUP_BEGIN` / `SESSION_PROCESS_EXIT` | private cash-shop client on 8487 |

## Known issues

| Card | Impact on this subsystem |
|---|---|
| b251 | Fixed by the mob-attack guard; the test `mobattackfix_wz_property_epointer` (`tests/CMakeLists.txt:63-65`) covers it, and `src/CMakeLists.txt` was last touched by its commit `3e640f1` 2026-11-04. No build-system defect, but the fix is only in builds that include `src/mobattackfix.cpp` (`src/CMakeLists.txt:20`, unconditional). |
| b270 | Launcher news / `manifest.json` without `news[]`. Not in this repo's build; `tools/package_runtime.py:21-25` here builds an unrelated `manifest.json` for the runtime zip. |
| b274 | Boss portals never send portal-enter. Nothing in the build or tests addresses client packet sending; no test covers portal entry. |
| e14 | In-game Slums Exchange window — not built, no option, no test. |
| L15 | Client-side fixes + bonds window, "owner will send client fixes later". No build target. |

`NEW-FINDING:` `.github/workflows/build.yml:18-21` still uploads `build-baseline/Release/Kaentake.dll`,
`Kaentake.exe` and `mapleroot/build/Integrated/ijl15.dll`. Since `f1b64a6` (2026-10-19) the script builds into
`build/` and produces `slums.dll` / `SlumsMS.exe` (`scripts/build.ps1:10,19`), so every CI run uploads zero files (or
fails at `upload-artifact` with no-files-found), and the workflow has not been touched since `6119130`. CI therefore
proves nothing about the shipped DLL.

`NEW-FINDING:` `CMakeLists.txt:1` declares `cmake_minimum_required(VERSION 3.14)`, but `scripts/build.ps1:17` uses
`ctest --test-dir`, which needs CMake ≥ 3.20. A CMake 3.14–3.19 user passes the declared floor and then fails at the
test step with an unknown-argument error.

`NEW-FINDING:` `/root/work/client/src-164-data/dependencies.json:2-5` pins and `/root/work/client/src-164-data/scripts/prepare-dependencies.ps1:9-13` extracts the CppWinRT nupkg, but no
CMake target consumes it — the only importer is `mapleroot/ijl15/ijl15.vcxproj:3,338`, and `scripts/build.ps1` stopped
building that project in `f1b64a6`. The 632 KB package is verified, extracted and then unused.

`NEW-FINDING:` `tools/package_runtime.py:7` asserts `a.runtime/'Kaentake.dll'` against
`release/baseline.json`'s `accepted_artifacts['Kaentake.dll']`. The build no longer emits a `Kaentake.dll`
(`src/CMakeLists.txt:139` sets `OUTPUT_NAME slums`), so this packager cannot run against a current build.

`NEW-FINDING:` `src/launcher.rc:10-11,24,28` hard-codes `FILEVERSION 1,2,0,0` / `"1.2.0.0"`, while `slums.dll`'s
resource is generated from `VERSION` (`src/slums.rc.in:5-6,19`). Explorer reports a 1.2.0.0 starter next to a
1.6.4.10740 DLL, and because the exe is shipped unchanged (`AGENTS.md:191`) that mismatch persists across releases.

`NEW-FINDING:` `tests/CMakeLists.txt:90` defaults `MOVEMENTKEYS_TEST_CLIENT` to
`${CMAKE_SOURCE_DIR}/../Addons/WeatherWork/TestClient`, a path that exists in no checkout (`/root/work/client` has no
`Addons/`). Three WZ tests plus `mount_full_frame_replacement` are therefore silently absent from every build that does
not pass `-TestClient`, and CTest reports success.

`NEW-FINDING:` reproducibility is structurally impossible for the current build: `mapleroot/ijl15/theactualstuff/xorstr.h:58`
keys the string obfuscation with `__TIME__` (used at `mapleroot/ijl15/DllMain.cpp:108`), and
`/root/work/client/src-164-data/mapleroot/ijl15/theactualstuff/lazy_import.h:73` hashes `__TIME__ __DATE__`. `DllMain.cpp` is compiled into `mapleroot_companion`
(`mapleroot/CMakeLists.txt:22`), so two builds of the same commit differ byte-for-byte. No `/Brepro`,
`SOURCE_DATE_EPOCH`, `CMAKE_MSVC_RUNTIME_LIBRARY` or `target_compile_options` for `/Zi`-free release exists anywhere
(`grep` over all `*.txt`/`*.cmake`: the only `target_compile_options` is `mapleroot/CMakeLists.txt:50`). This is the
technical reason behind the `AGENTS.md:191` rule; the rule is right, the code is the constraint.

`NEW-FINDING:` `release/baseline.json:12-18` records `client_flags` as five options, but `scripts/build.ps1:11` now
enables eleven. The baseline file describes the 2026-10-18 checkpoint, not what ships today; nothing regenerates it,
and `release/build-verification.json` still says `client_tests: 7` / `client_source_only_tests: 5` (a 40-test suite).

`NEW-FINDING:` `tests/CMakeLists.txt:159-168` registers `hud_meters_exp_dps`, `display_toggle_rules` and
`damagefmt_short_numbers` unconditionally, but the code they describe (`src/hud_meters.cpp`, `src/display.cpp`,
`src/damagefmt.cpp`) is only compiled under options that `scripts/build.ps1:11` never sets. Green tests, absent feature.

`NEW-FINDING:` there is no `install()` rule and no packaging step in this repo (`grep -rn "install(" *.txt`: 0 hits);
`tools/package_runtime.py` is the only packager and it is stale (above). Actual publishing happens from the *server*
repo (`tools/release/stage-client:79-84,133-136`), which refuses a changed `SlumsMS.exe` and asserts the file count —
so this repo's build output is only valid if that external script accepts it.

## State

**working, with a stale release path.** Evidence: the configure/build/test chain is complete and self-checking
(`scripts/build.ps1:14-18`); 40 test targets exist and 36 register with the shipped flag set; `cmake/BuildStamp.cmake`
produces `1.6.4-rc3` → `10740`, matching commit `9b5dcda` and the server's expectation of a monotonic build; the
`slums.dll` version resource really is generated from `VERSION` (`src/slums.rc.in:5-6`). Nothing here was built on this
host (Linux, no MSVC, submodules empty), so this is a static verdict.

**partial:** CI. The workflow runs the script but uploads paths from before the one-DLL rename.

**partial:** packaging. No `install()` rule; the one in-repo packager targets a file name the build stopped emitting.

**broken (by inspection):** byte-identical rebuilds. `__TIME__`/`__DATE__` in the companion guarantee divergence, so
the "ship only the changed files" rule (`AGENTS.md:191`) is currently the only safe option and any future attempt to
publish a rebuilt `SlumsMS.exe` will ship a differing binary.

**unknown:** whether `prep-build-rc1.ps1` sets anything beyond `scripts/build.ps1:11` — `CRASH_REPORT` is the live
candidate: no in-repo script enables it, and `docs/architecture/client/crash-report.md:8-11` states that a Release
`slums.dll` therefore carries no crash reporter at all.

## Open questions

1. Which flag set does the *release* actually use? `AGENTS.md:171` points at an off-repo `prep-build-rc1.ps1`; the
   in-repo script enables 11 of 16 options, and 4 documented features (HUD meters, display toggles, damage format,
   crash report) are enabled by neither. If `prep-build-rc1.ps1` turns any of them on, this repo cannot reproduce the
   shipped DLL.
2. Is `.github/workflows/build.yml` intended to be live at all? It has not been touched since the checkpoint and its
   artifact paths cannot resolve. Nothing in `AGENTS.md` or `README.md` mentions CI as a gate.
3. Why is the CppWinRT nupkg still pinned and extracted after `f1b64a6` dropped the only consumer? Removing it would
   delete 632 KB and one dependency check, but that is a code change, not a docs one.
4. Should `tests/CMakeLists.txt:90` fail loudly (or `FATAL_ERROR`) when `MOVEMENTKEYS_TEST_CLIENT` does not exist,
   instead of silently dropping 4 tests? The README's "five tests" claim suggests the silent drop was once intentional.
5. Does anything downstream read `slums-build.txt` besides `tools/release/stage-client:91`? The launcher shows it as
   the client version (`README.md:34`, `stage-client:15-16`), so an empty or stale file would mislabel a release, and no
   test asserts its contents.
