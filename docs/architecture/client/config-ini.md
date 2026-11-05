# config.ini (client configuration)

## Purpose

`config.ini` beside the game executable is the only configuration file `slums.dll` reads: it carries the login
server address and every runtime switch for the patch features. It is shipped as `/root/work/client/src-164-data/config.example.ini`
(86 numbered lines, no trailing newline, `/root/work/client/src-164-data/config.example.ini:1-86`) and copied into a package as `config.ini` by
`tools/package_runtime.py:13,20`. The client only ever reads it — there is no `WritePrivateProfileString` anywhere in
the tree, so the file belongs to the player and the launcher.

Two independent readers consume it, with different syntax and different failure modes: the bundled **inih** parser
(`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h`, used only by the MapleRoot companion's `doini()` and by `WinHook.cpp`) and the **Win32
profile API** (`GetPrivateProfileStringA` / `GetPrivateProfileIntA`, used by every `src/` switch). The same key can be
read only by one or the other, and the two disagree about inline comments and repeated keys — see `## Invariants`.

All paths are relative (`".\\config.ini"` or `"config.ini"`), so the file that is read is the one in the process's
working directory. The launcher starts the game with the install folder as the working directory
(`tauri/core/src/play.rs:86,96`, documented at `tauri/core/src/game_config.rs:55-57`).

## Entry points

| Phase | Where | What is read |
|---|---|---|
| process attach | `src/injector.cpp:47-51` `DllMain` → `ProcessCommandLine()` `:49`, `ProcessConfigFile()` `:50`, `AttachSystemHooks()` `:51` | `[config] host`, `[config] port`; `[graphics] borderless`, `[graphics] dpiaware`; `[graphics] d3d9host`, `adapterfallback`, `[clienthax] settingswindow`, `[clienthax] uitour` |
| first client hook | `src/system.cpp:26-36` `SetUnhandledExceptionFilter_hook` → `AttachClientHooks()` (`src/hook.h:129-304`) → `InitializeMapleRoot()` `src/hook.h:150` → `src/mapleroot.cpp:55` → `/root/work/client/src-164-data/mapleroot/ijl15/DllMain.cpp:196` `HaxMaple` → `/root/work/client/src-164-data/mapleroot/ijl15/MapleHook.cpp:222` `doini()` | every inih key: `[general] resolution`, `[discord] enableactivitystatus`, `[clienthax] nobulb/customcolor/moveattack/attackwalk/attackjump/chargemove/jumpshoot/jumpmagic/enablewinkey` |
| same hook, later in the table | `src/hook.h:258,265,273,283,293,298` | `[clienthax] jumpkeydown`, `invresize`, `lowdefensepenalty`, `mobattackfix`, `doubletapjump`, `[debug] attackdiag` |
| first use (lazy static) | `src/weathersway.cpp:877-878`, `src/lamps.cpp:1499-1504`, `src/hud_native.cpp:389-390`, `src/uilayout.cpp:367-368` | `[weather] sway`, `[weather] ropesway`, `[clienthax] mapobjguard`, `[clienthax] nativehud`, `[debug] uistate` |

Nothing re-reads the file later: every key is read once per process. This is why the launcher refuses to write game
options while `SlumsMS.exe` or `MapleStory.exe` runs (`tauri/core/src/game_config.rs:15,147-149`).

## How it works

1. **Address first, in `DllMain`.** `ProcessCommandLine()` parses `GetCommandLineA()` as `"%s %d"`
   (`src/injector.cpp:23`) and wins outright — `ProcessConfigFile()` returns immediately if a host is already set
   (`src/injector.cpp:30`). Otherwise `[config] host` is read (`:34`) and then `[config] port` (`:38`, `atoi` at
   `:41`) into `g_sServerHost` / `g_nServerPort` (`src/injector.cpp:14-15`).
2. **The address is applied at the first connect.** `WSPStartup_hook` (`src/system.cpp:283-288`) replaces
   `lpWSPConnect`; `WSPConnect_hook` (`:256-275`) rewrites only connections aimed at a stock v83 address
   (`g_asOriginalAddress`, `:223-228`). The host is resolved once through `INIT_ONCE` (`:235-254`): `InetPtonA` for a
   literal, else `getaddrinfo`; failure shows "Could not find the server …" (`:250-251`) and leaves the packet alone.
   The port is overwritten only when `g_nServerPort` is non-zero (`:269-271`), and `WSPGetPeerName_hook` (`:277-281`)
   puts the original address back so the game never sees the redirect.
3. **Graphics switches at attach.** `AttachSystemHooks` (`src/system.cpp:305-324`) reads `[graphics] borderless`
   (`:306`) and `dpiaware` (`:307-310`), then installs its hooks and calls `AttachD3D9Host()`
   (`:322`, `src/d3d9host.cpp:296-313`) which reads `d3d9host`, `adapterfallback`, `settingswindow`, `uitour`.
4. **Feature switches at the client hook.** `AttachClientHooks` (`src/hook.h:129`) runs once (static guard `:130-132`)
   when the client calls `SetUnhandledExceptionFilter` from `0x00796FDD`, then walks its patch table in a fixed order;
   each attach reads its own key and either patches or returns.
5. **inih switches in `doini()`.** `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:600-657` constructs one `INIReader("config.ini")`
   (`:602`) and — only if `ParseError() == 0` (`:603`) — applies `resolution`, `enableactivitystatus`, `nobulb`,
   `customcolor`, the four movement keys, `jumpshoot`, `jumpmagic` and `enablewinkey` via `WriteByte`/`WriteValue`/
   `PatchNop` at the addresses in the key table. A patch whose site already holds other bytes is skipped rather than
   forced (`src/attackstop_patch.h:118-133`, `src/midair.cpp:19-22`).
6. **Failure is fatal.** `InitializeMapleRoot` turns a `MapleRoot_Initialize` failure into `Fail("Feature initialization
   failed")` → `ErrorMessage` + `ExitProcess(ERROR_DLL_INIT_FAILED)` (`src/mapleroot.cpp:24-28,55`).

## Data

### The file

| Property | Value | Evidence |
|---|---|---|
| Name | `config.ini` | `src/constants.h:5` (`CONSTANTS_CONFIG_NAME`) |
| Location | working directory of the process | `".\\" CONSTANTS_CONFIG_NAME` at every Win32 read site; launcher sets CWD `tauri/core/src/play.rs:86,96` |
| Format | INI; UTF-8 BOM tolerated by inih, `;`/`#` comments | `/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:66-68,200-206,209-212` |
| Written by the client | never | no `WritePrivateProfileString` in the tree |
| Written by the launcher | game options (12 keys) + `[config] host/port` | `tauri/core/src/game_config.rs:142-166,120-128` |
| Shipped template | `/root/work/client/src-164-data/config.example.ini:1-86`, copied verbatim as `config.ini` | `tools/package_runtime.py:20` |

### Two readers

| | inih (`INIReader`) | Win32 profile API |
|---|---|---|
| Code | `/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:311-459` | `GetPrivateProfileStringA` / `GetPrivateProfileIntA` |
| Used by | `doini()` (`/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:602`), `Hook_CreateWindowExA` (`/root/work/client/src-164-data/mapleroot/ijl15/WinHook.cpp:2,16`) | every `src/` switch |
| Separator | `=` **or** `:` (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:244`) | `=` only |
| Inline comment | `;` after whitespace is stripped (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:143-158,249-253`) | part of the value |
| Repeated key | values joined with `"\n"` (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:454-456`) → a boolean no longer parses → default | first line wins |
| Continuation | indented line appends to the previous key (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:213-227`) | not supported |
| Bad line | `ParseError() != 0`, and `doini` then applies **nothing** (`/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:603`) | per-key; other lines unaffected |

### Keys by section

`when`: **attach** = `DllMain`; **hook** = inside `AttachClientHooks`; **lazy** = cached static on first use. Every key
is read once per process.

#### `[config]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `host` | absent; guest default `"127.0.0.1"` (`src/constants.h:9`, used `src/system.cpp:236`) | `src/injector.cpp:34` | attach | login host for the v83 redirect; a name (resolved via `getaddrinfo`) or an IPv4 literal | launcher: `merge_server` takes it from the channel layer (`tauri/core/src/game_config.rs:112-128`, called `tauri/core/src/install.rs:569`) |
| `port` | absent → `0` → port untouched | `src/injector.cpp:38-41` | attach | `htons` into `sin_port` of connections to a stock address (`src/system.cpp:269-271`) | same as `host` |
| `host`/`port` from the command line | — | `src/injector.cpp:17-27` | attach, **before** the file | `slums.dll`'s own image name is `argv[0]`; `argv[1]`/`argv[2]` become host/port and suppress both ini reads (`:30`) | starter forwards its command line unchanged (`src/launcher.cpp:14`) |

#### `[general]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `resolution` | `5` shipped (`/root/work/client/src-164-data/config.example.ini:2`); reader default `1` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:608` — **compiled out**: `#ifndef MAPLEROOT_KAENTAKE` (`:607`) with `MAPLEROOT_KAENTAKE` always defined (`/root/work/client/src-164-data/mapleroot/CMakeLists.txt:49`) | — | **dead key.** `setResolution` (`/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:479-505`, 1=800x600 … 5=1920x1080) is never called from here; the live resolution is the registry value `soScreenResolution` (`src/resolution.cpp:177-192`) | nobody; not exposed by the launcher (launcher `/root/work/launcher-165-server/README.md:266`) |
| `center` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/WinHook.cpp:16` | — | **dead key.** Only `Hook_CreateWindowExA` reads it, and `HaxWinApi()` is called only from the non-single-DLL `DllMain` (`/root/work/client/src-164-data/mapleroot/ijl15/DllMain.cpp:215`), excluded by `MAPLEROOT_SINGLE_DLL` (`/root/work/client/src-164-data/mapleroot/CMakeLists.txt:49`, guard `/root/work/client/src-164-data/mapleroot/ijl15/DllMain.cpp:208`) | nobody |
| `width`, `height` | — | none; the reads are commented out (`/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:604-605`) | — | not read at all | nobody |

#### `[discord]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `enableactivitystatus` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:611-614` (inih `GetBoolean`) | hook | starts the Discord rich-presence updater (`beginUpdates`) | launcher option `discord` (`tauri/core/src/game_options.rs:70-71`) |

#### `[clienthax]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `jumpkeydown` | `false` | `src/midair.cpp:16-17` — **strict**: only `true` (any case) or `1`; `yes`/`on` are rejected (`:17`) | hook (`src/hook.h:258`, always compiled, `src/CMakeLists.txt:24`) | 6 NOPs at `MidAir::kKeydownAirExit`, after a stock-byte check (`src/midair.cpp:19-25`); keydown attacks may start in the air. TEST only (`src/midair.cpp:2`) | nobody (not a launcher option) |
| `nobulb` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:616-618` | hook (inih) | `PatchNop(0x00A08D5B, 5)` — no quest lightbulb popup | launcher `nobulb` (`game_options.rs:74-75`) |
| `customcolor` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:620-624` | hook (inih) | orange name/level and skill text: `WriteValue(0x0098B70C+1, 0xFFFF5D00)`, `WriteByte(0x008c4944+1, 25)`, `WriteByte(0x008AA6CB+1, 25)` | launcher `customcolor` (`:76-77`) |
| `moveattack` | absent (= `true`) | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:631-641` → `integration::ResolveMoveAttackSettings` (`src/attackstop_patch.h:109-116`) | hook (inih) | **legacy**; only a fallback for `attackjump` and `chargemove`. Read, never written | launcher reads it, never writes or removes it (`game_options.rs:96-97`, `:503-524`) |
| `attackwalk` | `false` | same (`src/attackstop_patch.h:112`) | hook (inih) | keeps walk input during attacks — patch `0x009CBFB0`, 1 byte `7E`→`EB` (`attackstop_patch.h:45-53`). Never follows `moveattack` | launcher `attackwalk` (`game_options.rs:78-79`) |
| `attackjump` | `true` (= `moveattack` when absent) | `src/attackstop_patch.h:113` | hook (inih) | jump during attacks — `0x0094C3BB`, 6 bytes `0F 8F 12 01 00 00`→6×`90` | launcher `attackjump` (`:80-81`) |
| `chargemove` | `true` (= `moveattack` when absent) | `src/attackstop_patch.h:114` | hook (inih) | move while charging keydown skills — `0x0095F97A`, 2 bytes `7F 2C`→`EB 59` | launcher `chargemove` (`:82-83`) |
| `jumpshoot` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:643-647` | hook (inih) | `E9` + rel32 at `0x009539FA` → `0x00953A11` (`TryDoingShootAttack`) | launcher `jumpshoot` (`:86-87`) |
| `jumpmagic` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:649-653` | hook (inih) | `E9` + rel32 at `0x009559E5` → `0x00955A20` | launcher `jumpmagic` (`:88-89`) |
| `enablewinkey` | `true` | `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:655` | hook (inih) | `Hook_CWvsApp__EnableWinkey` — Windows key works in game | launcher `winkey` (`:72-73`) |
| `doubletapjump` | `false` | `src/bounce.cpp:231` (`ReadIniBool`, `:135-139`) | hook (`src/hook.h:293`, `MAPLEROOT_INTEGRATION`) | second jump press in the air uses the Ninja Bounce skill; off = nothing installed (`bounce.cpp:234`) | launcher `doubletapjump` (`:84-85`), read as `Reader::Profile` |
| `invresize` | `true` | `src/invresize.cpp:548-552` (`EnabledInConfig`), used `:677` | hook (`src/hook.h:265`, `MAPLEROOT_INV_RESIZE`) | drag-resize the item inventory 4-16 × 3-14; false logs "Inventory resize: off in config.ini … stock window kept" (`:678`) | launcher: not an option; the key is left alone |
| `lowdefensepenalty` | `false` (fix on) | `src/mobdamage.cpp:34-38` | hook (`src/hook.h:273`) | `true` keeps the stock rule (low defence adds damage); false installs the cave at `0x0079328D` | nobody (not a launcher option) |
| `mobattackfix` | `true` (fix on) | `src/mobattackfix.cpp:60-64` | hook (`src/hook.h:283`) | `false` keeps the stock `E_POINTER` throw; true tolerates it at `0x0067FE33` (b251) | nobody (not a launcher option) |
| `settingswindow` | `true` | `src/d3d9host.cpp:302` | attach (`src/system.cpp:322`) | the Ctrl+F10/F11 HUD window; false also kills `uitour` (`:303`) and all HUD input/draw (`:270`, `:163`) | nobody |
| `uitour` | `true` | `src/d3d9host.cpp:303` | attach | first-run Edit UI tour (`src/uitour.cpp:103-106`), once per PC (`HKCU\Software\SlumsMS\Overlay\tourSeen`, `src/uitour.cpp:23`) | nobody |
| `mapobjguard` | `true` | `src/lamps.cpp:1498-1506` | lazy (static `const bool`, read once) | a map object whose art is missing costs that one object instead of the session (map 272000000 evidence in `src/mapobj_guard.h`) | nobody |
| `nativehud` | `1` (on) | `src/hud_native.cpp:388-392` (`GetPrivateProfileIntA`) | lazy (static `s_on`) | stock-art HP/MP/EXP modules; 0 disables. Built only under `MAPLEROOT_INTEGRATION` (`src/CMakeLists.txt:120`) | nobody — **undocumented key** (not in `/root/work/client/src-164-data/config.example.ini`, not in `/root/work/client/src-164-data/README.md`) |

#### `[weather]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `sway` | `true` | `src/weathersway.cpp:871-878` (`ReadWeatherIniBool`), resolved `src/weather_object_policy.h:19` | lazy (two function statics, first call) | false: nothing bends (no generated frames at all) | nobody |
| `ropesway` | `true` | same (`:878`) | lazy | false: ropes and ladders keep the map's own layer, plants still bend | nobody |

Both are logged once: `weathersway: config [weather] sway=%d ropesway=%d` (`src/weathersway.cpp:880-884`).

#### `[graphics]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `borderless` | `false` | `src/system.cpp:306` via `ReadGraphicsSwitch` (`:68-72`) + `WindowPolicy::IniTrue` (`src/window_policy.h:27-31`) | attach | `WS_POPUP`, client-sized, centred on the monitor (`src/system.cpp:99-120,187`, `PlaceBorderless` `:75-90`); the frame the game restores on a resolution change is stripped again | launcher `borderless` (`game_options.rs:90-91`), `Reader::Profile` |
| `dpiaware` | `false` | `src/system.cpp:307-310` | attach | `SetProcessDPIAware()` before any window — pixel-for-pixel on a scaled display | launcher `dpiaware` (`:92-93`) |
| `d3d9host` | `true` | `src/d3d9host.cpp:297` | attach | detours `Direct3DCreate9`, logs adapters to `integration.log`, hosts the ImGui overlay; false logs "D3D9: host off" (`:298`) and returns before reading `adapterfallback`/`settingswindow`/`uitour` | nobody |
| `adapterfallback` | `true` | `src/d3d9host.cpp:301` | attach, only when `d3d9host` is on | retry `CreateDevice` on the other adapters, then software vertex processing (`src/d3d9host_policy.h`) | nobody |

#### `[debug]`

| Key | Default | Read by | when | Effect | Written by |
|---|---|---|---|---|---|
| `attackdiag` | `0` | `src/attackdiag.cpp:212` (`GetPrivateProfileIntA`) | hook (`src/hook.h:298`, only if `MAPLEROOT_ATTACK_DIAG`) | 0 off; 1 log dropped remote attacks; 2 log every remote attack; appends to `attackdiag.log` (`:216-220`) | nobody |
| `uistate` | `0` | `src/uilayout.cpp:367-368` | lazy | QA only: writes `slums-ui-state.json` twice a second for the client-MCP driver (`src/uilayout.cpp:358-360`) | nobody |

#### Present in the file, read by nothing

| Entry | Line | Why it does nothing |
|---|---|---|
| `ChairOpacity=255` | `/root/work/client/src-164-data/config.example.ini:57` | no reader in `src/` or `mapleroot/`; sits under the `[clienthax]` header (no section transition before it), so it would be a `[clienthax]` key |
| `SkillOpacity=255` | `/root/work/client/src-164-data/config.example.ini:58` | same |

## Switches

The file's keys *are* the switches (full table under `## Data`). What governs whether a key exists at all:

| Build option (all `OFF` by default, `CMakeLists.txt:3-18`) | Keys that only exist with it | In the shipped build |
|---|---|---|
| `MAPLEROOT_INTEGRATION` (`src/CMakeLists.txt:117-131`) | `[clienthax] doubletapjump`, `settingswindow`, `uitour`, `nativehud`; all of `[graphics] d3d9host`/`adapterfallback` | ON (`scripts/build.ps1:11`) |
| `MAPLEROOT_INV_RESIZE` (`src/CMakeLists.txt:51-54`) | `[clienthax] invresize` | ON |
| `MAPLEROOT_ATTACK_DIAG` (`src/CMakeLists.txt:110-113`) | `[debug] attackdiag` | ON |
| `MAPLEROOT_WEATHER` (`src/CMakeLists.txt:100-105`) | `[weather] sway`, `ropesway`, and `[clienthax] mapobjguard` (in `lamps.cpp`) | ON |
| `mapleroot_companion` (`mapleroot/CMakeLists.txt:46-49`) | every inih key — `discord`, `nobulb`, `customcolor`, the movement four, `jumpshoot`, `jumpmagic`, `enablewinkey` | always linked |
| `MAPLEROOT_KAENTAKE` (`/root/work/client/src-164-data/mapleroot/CMakeLists.txt:49`) | removes `[general] resolution` and the `[general] center` hook | always defined |

Delete one of those options and the matching key becomes silently inert (no error, no log).

Precedence, top to bottom:

| Setting | Beats | Evidence |
|---|---|---|
| command-line `argv[1]`/`argv[2]` | `[config] host`/`port` | `src/injector.cpp:30` |
| a site's own key (`attackwalk`/`attackjump`/`chargemove`) | `[clienthax] moveattack` | `src/attackstop_patch.h:101-116` |
| `[clienthax] moveattack` (legacy, default true) | nothing — it is the fallback for `attackjump`/`chargemove` only | same; `attackwalk` opts out (`:106-108`) |
| key present and parseable | the default in the client source | every reader |

Boolean dialects — they are **not** interchangeable:

| Reader | True | False | Anything else |
|---|---|---|---|
| inih `GetBoolean` (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:428-439`) | `true` `yes` `on` `1` | `false` `no` `off` `0` | the caller's default |
| `integration::ParseIniBool` (`src/attackstop_patch.h:74-79`) | same eight | same eight | `nullopt` → the caller's default |
| `WindowPolicy::IniTrue` (`src/window_policy.h:27-31`, `[graphics]` only) | `1` `true` `yes` `on` | everything else, including empty | false |
| `src/midair.cpp:17` (`jumpkeydown` only) | `true` (any case), `1` | everything else | off |

Legacy-key fallbacks: exactly one — `attackjump` and `chargemove` fall back to `[clienthax] moveattack` when absent
(`src/attackstop_patch.h:109-116`). No other key has a legacy alias.

Launcher side (read-only repo, `/root/work/launcher-165-server`; the older `/root/work/launcher-tauri` checkout is a
superseded snapshot — `GAME_OPTIONS` has 10 entries at `tauri/core/src/game_options.rs:47` and `game_config.rs` has no
`merge_server`, so cite 165 only):

| Behaviour | Evidence |
|---|---|
| Owns 12 options; writes **all 12** explicitly on any change (`explicit_game_options`) | `tauri/core/src/game_options.rs:69-94,530-537` |
| Changing any of `attackwalk`/`attackjump`/`chargemove` writes all three | `tauri/core/src/game_options.rs:100,506-511` |
| `[clienthax] moveattack` is read, never written or removed | `tauri/core/src/game_options.rs:96-97,529` |
| `[config] host`/`port` come from the channel layer (`merge_server`); everything else in the file is byte-for-byte | `tauri/core/src/game_config.rs:112-128` |
| …except when the player set the server by hand since the launcher last did — then the file is kept as-is | `tauri/core/src/install.rs:563-570` (`hand_edited` at `:568`) |
| The player's file is never replaced by Update or Repair; it is installed only when missing | launcher `/root/work/launcher-165-server/README.md:230` (repo root), `tauri/core/src/install.rs:111-123,557-577` |
| Refuses to write while the game runs | `tauri/core/src/game_config.rs:147-149` |
| Reads `doubletapjump`/`borderless`/`dpiaware` the way `GetPrivateProfileStringA` does (`Reader::Profile`), everything else as inih | `tauri/core/src/game_options.rs:37-42,335-341` |

Keys the launcher must **preserve** (it does not understand them, so it must not rewrite or drop them):
`[general] resolution`, `[general] center`, `[clienthax] ChairOpacity`, `SkillOpacity`, `jumpkeydown`, `invresize`,
`lowdefensepenalty`, `mobattackfix`, `settingswindow`, `uitour`, `mapobjguard`, `nativehud`, all of `[weather]`, all of
`[debug]`, plus every comment, unknown key, line ending and the BOM. `/root/work/launcher-165-server/README.md:230` of the launcher states the
player-owned rule; `tauri/core/src/game_options.rs:1-17,379-400` states the byte-preserving edit rule.

## Invariants

1. **The client never writes the file.** No `WritePrivateProfileString` exists in the tree; the HUD explicitly
   points at the launcher for game options (`src/settings_overlay.cpp:5-6,22`).
2. **Relative path, so the working directory decides which file is read.** Both readers open `config.ini` (or
   `.\config.ini`) relative to the CWD (`src/constants.h:5`); the launcher must start the game with the install folder
   as its working directory (`tauri/core/src/play.rs:86,96`; the invariant is spelled out at
   `tauri/core/src/game_config.rs:55-57`).
3. **`doini()` is all-or-nothing.** One unparseable line anywhere in the file makes `ParseError() != 0` and every inih
   key silently falls back to its default — `nobulb`, `customcolor`, the movement four, `jumpshoot`, `jumpmagic`,
   `enablewinkey`, `discord` (`/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:603`). The `GetPrivateProfileStringA` keys are unaffected,
   because they re-read the file per key. Noted in `docs/research-126-midair.md:233`.
4. **The Win32 keys do not see inline comments.** `mobattackfix=true ; b251` reads as `true ; b251`, which
   `ParseIniBool` rejects, so the key silently becomes its default. The inih keys tolerate the same trailing comment
   when a space precedes `;` (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:143-158`). The launcher compensates by dropping inline comments when it
   writes a `Reader::Profile` key (`tauri/core/src/game_options.rs:379-381`).
5. **A repeated key flips an inih boolean back to its default.** inih joins repeats with `"\n"`
   (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:454-456`), which no boolean spelling matches; the Win32 reader takes the first occurrence. The
   launcher mirrors both behaviours and drops repeats when it edits (`tauri/core/src/game_options.rs:290-306,442-497`).
6. **`[config] port` is only read after a host.** `ProcessConfigFile` returns before reading the port when the host
   key is missing or empty (`src/injector.cpp:34-36`), and the port is read only after a host was stored (`:37-41`); a
   missing/empty port leaves `g_nServerPort` at `0`, which `WSPConnect_hook` treats as "leave the port alone"
   (`src/system.cpp:269`). A host without a port therefore connects
   to the stock v83 port.
7. **Only connections to the stock addresses are redirected** — `63.251.217.2/.3/.4` and `127.0.0.1`
   (`src/system.cpp:223-228`); `WSPGetPeerName_hook` restores the original peer so the client's own logic is unchanged
   (`:277-281`).
8. **A failed host resolution is never silent**: the user gets `Could not find the server "…"` naming
   `config.ini` (`src/system.cpp:250-251`), and the connect proceeds to the stock address.
9. **A patch is applied only over stock bytes.** Every switchable site classifies the current bytes first and leaves a
   foreign patch alone (`src/attackstop_patch.h:118-133`, `src/midair.cpp:19-22`, `src/mobattackfix.cpp:74-80`,
   `src/invresize.cpp:681-683`).
10. **Defaults are triplicated**: client source, `/root/work/client/src-164-data/config.example.ini`, and the launcher's `GAME_OPTIONS` fallbacks. The
    launcher pins the 12 it owns to the client source with a test (`tauri/core/src/game_options.rs:724-746`); nothing
    pins the other ~15 keys, so a change in the client default alone leaves the example file stale.
11. **Key and section names are case-insensitive in both readers** (inih lowercases in `MakeKey`,
    `/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:441-447`; the Win32 profile API is case-insensitive) and the launcher's parser lowercases the same way
    (`tauri/core/src/game_options.rs:282-284`).

## Origin

| File | Origin | Evidence |
|---|---|---|
| `/root/work/client/src-164-data/config.example.ini` | **custom** (ours, derived from the MapleRoot checkpoint) | `git log`: `6119130 2026-10-18 "Checkpoint accepted MapleRoot client integration and addons"` → `d82253c 2026-10-18` → `53fd583 2026-10-21 "Split moveattack into attackwalk, attackjump and chargemove"` → `221af9c 2026-10-23`. 19 commits touch it. The key set with `[clienthax]`/`[graphics]` is ours; there is no upstream `/root/work/client/src-164-data/config.example.ini` |
| `/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h` | **base** — vendored inih | only commit `6119130 2026-10-18`; header says "inih and INIReader are released under the New BSD license" (`/root/work/client/src-164-data/mapleroot/ijl15/INIReader.h:3`, `:99-106`), i.e. it arrived with the MapleRoot companion checkout (`docs/PROVENANCE.md:3`) |
| `src/injector.cpp` (`[config]` reader, command-line precedence) | **base** (Kaentake), unmodified in behaviour | `git log --follow`: `de776d0 2025-08-26 "Initial commit"` … `ccb160d 2025-08-31`; `docs/KAENTAKE-UPSTREAM.md:11-23` documents command line over `config.ini` and the `[config] host/port` example before our tree starts |
| `src/system.cpp` (`borderless`/`dpiaware`) | **base + custom** | base `6003540 2025-08-27`; our block is `a1607ae 2026-10-28 "Borderless window and DPI-aware mode ([graphics] borderless / dpiaware, both off)"` |
| `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h`, `/root/work/client/src-164-data/mapleroot/ijl15/WinHook.cpp` | **base** (MapleRoot companion), `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h` modified by us | `WinHook.cpp` is a single checkpoint commit `6119130`; `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h` gained `44ab18f 2026-10-21 "Proof: let config undo MapleRoot moveattack so attacks stop walking"` and `53fd583` (the three-key split). `docs/PROVENANCE.md:3` — "the companion source is the MapleRoot ijl15 source port, with only relative project paths adjusted" |
| `src/attackstop_patch.h` | **custom** | `44ab18f 2026-10-21`, `53fd583 2026-10-21` |
| `src/window_policy.h` | **custom** | `a1607ae 2026-10-28` |
| `src/d3d9host.cpp` | **custom** | `bc58352 2026-10-25 "Host Direct3D 9 for Parsec diagnosis and an in-game settings window"` |
| `src/weathersway.cpp`, `src/lamps.cpp` | **base** (MapleRoot weather addon) | first commit `6119130`, the same checkpoint |
| `src/midair.cpp` | **custom** | `4fd703d 2026-10-28 "Mid-air keydown attacks: [clienthax] jumpkeydown (default off, TEST only)"` |
| `src/bounce.cpp` | **custom** | `8cb9909 2026-10-21 "Add double-tap jump and server-driven bounce block zones"` |
| `src/invresize.cpp` | **custom** | `997a0fc 2026-10-25` |
| `src/mobdamage.cpp` | **custom** | `48508d7 2026-10-25 "Stop low defence from adding damage when mobs touch you"` |
| `src/mobattackfix.cpp` | **custom** | `3e640f1 2026-11-04 "Rose Garden 211080100 no longer closes the client (b251)"` |
| `src/attackdiag.cpp` | **custom** | `34c1c28 2026-10-21 "Log the other characters' attacks the client drops, with the reason"` |
| `src/hud_native.cpp`, `src/uilayout.cpp` | **custom** | `2ee0ead 2026-10-25`, `0247c3e 2026-10-25` |
| launcher `tauri/core/src/game_config.rs`, `game_options.rs` | **custom** (launcher repo) | `merge_server` was added for the 2026-11-05 RC/TEST server move; the comment at `game_config.rs:116-119` records that 1.6.4 and older never touched an existing `config.ini` |

Baseline for "ours": the client history begins at `joshhmann/kaentake 2a43a01e…` (`docs/PROVENANCE.md:3`).

## Tests and QA

Native tests (`ctest`, names from `tests/CMakeLists.txt`) that pin config.ini semantics:

| Test | Line | What it proves |
|---|---|---|
| `attackstop_moveattack_sites` | `:151` | `ParseIniBool` spellings; a 13-row precedence table including `{{T,F,T,T}, false,true,true, "shipped config.ini"}` (`tests/attackstop_patch_test.cpp:96`); per-site write decisions including "foreign patch kept"; an 8-mask end-to-end fake image (`:119+`) |
| `window_borderless_place` | `:162` | `WindowPolicy::IniTrue` accepts `true`/`1`/`ON` and rejects `false`/empty/null (`tests/window_policy_test.cpp:18-26`); `Place` geometry |
| `weather_animated_portal_colors` | `:100` | the `sway`/`ropesway` kill-switch matrix (`tests/weather_object_policy_test.cpp:11-18`), `MAPLEROOT_WEATHER` only |
| `bounce_doubletap_and_zones` | `:157` | `doubletapjump` off ⇒ nothing installed; the zone rules |
| `midair_sites_policy` | `:166` | the keydown-gate patch and its `jumpkeydown` policy (no ini read) |
| `mapobj_guard_skips_missing_art` | `:176`/`:178` | the guarded vs stock path |
| `invresize_sites`, `mobdamage_low_defence`, `mobattackfix_wz_property_epointer`, `attackdiag_remote_attack_rules`, `uilayout_move_stock_ui`, `uitour_first_run`, `d3d9host_adapter_fallback` | `:36`,`:45`,`:63`,`:147`,`:85`,`:88`,`:77` | each feature's policy layer with the switch on and off |

Launcher tests (`cargo test -p slums-core`): `fallbacks_match_the_client` pins the 12 fallbacks to the client files
(`tauri/core/src/game_options.rs:724-746`); `merge_server_takes_only_host_and_port_from_the_channel`
(`tauri/core/src/game_config.rs:240`); repeated keys, dropped inline comments for `doubletapjump`, every option written
explicitly, `moveattack=false` ⇒ all three written, and "Unknown game option" rejection
(`tauri/core/src/game_options.rs:776-790,792-833,835-844,846-854`).

Build script checks: `scripts/build.ps1:11` configures with `INTEGRATION`, `WEATHER`, `INV_RESIZE`, `ATTACK_DIAG`
(and others) ON, then `cmake --build` and `ctest --output-on-failure` (`:13-18`) — so every key that needs one of
those options is compiled in and tested in the shipped build.

**Not covered by any test:** nothing parses a real `config.ini` in the C++ suite — no test reads the file at all. In
particular there is no coverage of `[config] host`/`[port]` (command-line precedence, host-without-port, resolution
failure), `[debug] attackdiag`/`uistate` beyond their policy helpers, `nativehud`, the `[clienthax]` inih keys'
all-or-nothing failure, or the difference between inih and `GetPrivateProfileStringA` inline-comment handling. A real
client must confirm by hand: the login lands on the `[config]` server (not the stock one), `integration.log` shows the
`weathersway:` and `D3D9: host on` lines with the intended values, and a deliberately broken line (e.g. a stray `[`)
disables the inih effects while the `[graphics]` switches keep working.

## Known issues

| Id | Card / source | State |
|---|---|---|
| b251 | `/root/work/STATUS-1.6.4.md:72` | Rose Garden 211080100 client crash; fixed in 1.6.4 by `[clienthax] mobattackfix` being on by default (`src/mobattackfix.cpp:60-64`, `/root/work/client/src-164-data/config.example.ini:40-46`) — the only shipped bug fixed by a config key |
| b270 | `/root/work/STATUS-1.6.4.md:75` | launcher news manifest, not the client |
| b274 | `/root/work/STATUS-1.6.4.md:76` | boss portals never send portal-enter; unrelated to config.ini |
| L15 | `/root/work/STATUS-1.6.4.md:106` | client fixes and the bonds window, later |
| e14 | `/root/work/STATUS-1.6.4.md:124` | in-game Slums Exchange window, client+server, later |

`NEW-FINDING:` `/root/work/client/src-164-data/config.example.ini:57-58` ships `ChairOpacity=255` and `SkillOpacity=255` with no reader anywhere in
the tree; they sit under the `[clienthax]` header with no section transition, so they read as `[clienthax]` keys. Dead
weight in a player-facing file, and the launcher's own README already calls them ignored.

`NEW-FINDING:` `/root/work/client/src-164-data/config.example.ini:2` ships `resolution=5` for a key this build cannot read: the read is compiled out
by `MAPLERoot_KAENTAKE`-style guard at `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:607` and the option is always defined
(`/root/work/client/src-164-data/mapleroot/CMakeLists.txt:49`). A player editing it sees no effect; the live resolution is the registry
`soScreenResolution` (`src/resolution.cpp:179`).

`NEW-FINDING:` `src/hud_native.cpp:390` reads `[clienthax] nativehud` (default on) and the key appears in neither
`/root/work/client/src-164-data/config.example.ini` nor `/root/work/client/src-164-data/README.md`, against the repo rule "document keys in README and config.example.ini"
(`AGENTS.md:13`).

`NEW-FINDING:` `/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:603` makes every inih key hostage to the whole file's syntax. A single
malformed line silently reverts `nobulb`, `customcolor`, `attackjump`, `chargemove`, `jumpshoot`, `jumpmagic`,
`enablewinkey` and `discord` to their defaults with no message; only `docs/research-126-midair.md:233` mentions it. The
`GetPrivateProfileStringA` switches keep working, so the failure is partial and confusing.

`NEW-FINDING:` `src/midair.cpp:17` has its own boolean parser: `yes`, `on`, `TRUE` — `_stricmp` accepts `TRUE` and
`1`, but `yes`/`on` are rejected, unlike every other switch. A player writing `jumpkeydown=on` gets silence.

`NEW-FINDING:` `src/injector.cpp:34-40` reads `port` only after `host` succeeds, and `src/system.cpp:269` skips the port
rewrite when it is `0`. An ini with `host=` and no `port=` therefore connects on the stock v83 port instead of
reporting a configuration error.

`NEW-FINDING:` `src/injector.cpp:23` parses `argv[1]`/`argv[2]` as host/port unconditionally. The starter passes its
own command line through untouched (`src/launcher.cpp:14`), so any other launch argument is consumed as a host name and
suppresses `[config]`.

`NEW-FINDING:` the launcher README's Game options table (`/root/work/launcher-165-server/README.md:253-264`) lists 10
of the 12 options (no `borderless`/`dpiaware`) and gives `doubletapjump` as "on" while the client default and the
launcher table are `false` (`tauri/core/src/game_options.rs:84`). Two lines above the player-owned rule the same README
still asserts "The launcher does not rewrite client INIs" (`/root/work/client/src-164-data/README.md:59`) and "installed once and never overwritten"
(`:58`), both contradicted by `merge_server` (`tauri/core/src/game_config.rs:120-128`) writing `[config] host`/`port`
into an existing file. The module doc explains the change (`game_config.rs:114-119`, owner decision 2026-11-05); the
README sentences were not updated.

## State

**working** — both readers are live in the shipped build and every switch either patches or logs its decision. All
`src/` readers are compiled in under the flags `scripts/build.ps1:11` passes; the inih block runs on every launch
through `InitializeMapleRoot`. Verification for this assessment is line-level reading of both repos plus the green
`ctest` set; no client run was performed here.

Sub-parts that are not working: `[general] resolution` and `[general] center` are dead in this build (compiled out,
`/root/work/client/src-164-data/mapleroot/ijl15/YourStuff.h:607`, `/root/work/client/src-164-data/mapleroot/ijl15/DllMain.cpp:208-222`), and `ChairOpacity`/`SkillOpacity` have no
reader at all. They are shipped in the example file anyway.

## Open questions

1. Should `/root/work/client/src-164-data/config.example.ini` drop `resolution` and `ChairOpacity`/`SkillOpacity`, or keep them as documentation of the
   MapleRoot surface? Neither the client nor the launcher consumes them, but `docs/KAENTAKE-UPSTREAM.md:5` still
   describes the resolution selector as a feature of the install.
2. Is the `doini` all-or-nothing gate acceptable? Making `doini` read key-by-key with `GetPrivateProfileStringA`
   instead of inih would remove the single-point failure, but it would also drop inih's `:` separator and continuation
   support that existing player files may rely on. No decision recorded.
3. Does the Win32 profile API tolerate a UTF-8 BOM? `/root/work/client/src-164-data/config.example.ini` has none and the launcher preserves whatever it
   finds (`tauri/core/src/game_options.rs:270-280`), but nothing documents how `GetPrivateProfileStringA` treats a BOM
   before `[general]`, so a BOM-edited file's `[general]` section is unverified.
4. Nothing pins defaults for the ~15 keys the launcher does not own (`lowdefensepenalty`, `mobattackfix`, `invresize`,
   `settingswindow`, `uitour`, `mapobjguard`, `nativehud`, `sway`, `ropesway`, `d3d9host`, `adapterfallback`, plus
   `[config]`/`[debug]`). A client default change would leave `/root/work/client/src-164-data/config.example.ini` and `/root/work/client/src-164-data/README.md` stale with no test
   to catch it — the launcher test only covers its own 12.
5. `src/attackdiag.cpp:212` and `src/uilayout.cpp:368` read `[debug]` with `GetPrivateProfileIntA`, so `uistate=on`
   is `0` (off) while every other switch would accept `on` as true. Whether that is intentional for QA keys is
   unrecorded.
