# Injector and hooks

## Purpose

`slums.dll` is the whole client modification layer: a 32-bit DLL injected into the stock 2008 v83 `MapleStory.exe`
before the login screen, which rewrites fixed-address bytes, installs detours on game and Win32 functions, mounts
`Custom.wz` over the stock data, and redirects the socket to the SlumsMS server. The game cannot be rebuilt, so every
behaviour change is a patch at a VA in an executable pinned by sha256 and image base `0x400000`
(`src/createblock_patch.h:29`).

Two layers exist:

- `src/injector.cpp` — the DLL entry point and the only place that reads `[config] host`/`port`.
- `src/hook.h`/`src/hook.cpp` — the detour and byte-patch primitives plus `AttachClientHooks()`, the single ordered
  table of every client-side modification.

The MapleRoot companion (`mapleroot/ijl15/`, formerly a separate `ijl15.dll`) is now linked into the same DLL
(`src/mapleroot.cpp:7`) and keeps its own patch table, driven by its own INI reader (`mapleroot/ijl15/YourStuff.h:600-657`).

## Entry points

| Entry | File:line | Reached from |
|---|---|---|
| `DllMain(DLL_PROCESS_ATTACH)` | `src/injector.cpp:45-59` | `DetourCreateProcessWithDllExA` in the starter (`src/launcher.cpp:14`) |
| `ProcessCommandLine()` | `src/injector.cpp:17-27` | `DllMain` `src/injector.cpp:49` |
| `ProcessConfigFile()` | `src/injector.cpp:29-42` | `DllMain` `src/injector.cpp:50` |
| `AttachSystemHooks()` | `src/system.cpp:305-323` | `DllMain` `src/injector.cpp:51` |
| `SetUnhandledExceptionFilter_hook` | `src/system.cpp:26-36` | any `SetUnhandledExceptionFilter` call in the exe |
| `AttachClientHooks()` | `src/hook.h:129-304` | first-stage gate `src/system.cpp:28-29` |
| `AttachClientBypass()` | `src/bypass.cpp:467-485` | `src/hook.h:134` |
| `InitializeMapleRoot()` | `src/mapleroot.cpp:43-58` | `src/hook.h:150` |
| `AttachCustomPacketMod()` | `src/packetdispatcher.cpp:105` | `src/bypass.cpp:468-470` (conditional) |
| `AttachResManMod()` | `src/resman.cpp:260-263` | `src/hook.h:163` |
| `AttachClientInlink()` | `src/inlink.cpp:56-66` | `src/hook.h:153` |
| `AttachStringPoolMod()` | `src/stringpool.cpp:52-54` | `src/hook.h:158` |
| `AttachCreateBlockMod()` | `src/createblock.cpp:14-30` | `src/hook.h:245` |
| `AttachMidAirMod()` | `src/midair.cpp:14-27` | `src/hook.h:258` |
| `AttachMovementKeysMod()` | `src/movementkeys.cpp:188-219` | `src/hook.h:140` (compile-time) |
| `doini()` (companion patches) | `mapleroot/ijl15/YourStuff.h:600-657` | `HaxMaple()` `mapleroot/ijl15/MapleHook.cpp:222` |

`DummyExport` (`src/injector.cpp:12`) exists only so the DLL has an export the injector can name.

## How it works

### 1. Process start (outside this subsystem)

`src/launcher.cpp:14` calls `DetourCreateProcessWithDllExA("MapleStory.exe", …, CREATE_SUSPENDED, …, "slums.dll")` and
resumes the thread at `src/launcher.cpp:22`, so the DLL is loaded before the game executes any code.

### 2. `DllMain` — load-time phase

1. `DisableThreadLibraryCalls` (`src/injector.cpp:48`).
2. `ProcessCommandLine()` (`:49`) — if `CONSTANTS_USE_COMMAND_LINE` (`src/constants.h:10`, TRUE) and
   `sscanf_s(GetCommandLineA(), "%s %d", …) == 2` (`src/injector.cpp:21`), the exe arguments override host/port. Because
   the DLL is injected into `MapleStory.exe`, argv[0] is the game path, so this only fires when the launcher passes a
   host and port.
3. `ProcessConfigFile()` (`:50`) — returns immediately when `!CONSTANTS_USE_CONFIG_FILE || g_sServerHost`
   (`src/injector.cpp:30`): **the command line wins over the ini**. Otherwise `GetPrivateProfileStringA("config", "host",
   …, ".\\config.ini")` (`:34`) then `port` (`:38`) with `atoi` (`:41`). A missing `host` leaves both globals null/0.
4. `AttachSystemHooks()` (`:51`) — reads the two `[graphics]` keys, then installs the Win32/infrastructure detours.
5. Return `TRUE` (`:58`). Nothing is patched into the game's code yet; the game is still running its own CRT init.

### 3. `AttachSystemHooks` — infrastructure before the game window exists

Order in `src/system.cpp:305-323`:

1. `g_bBorderless = ReadGraphicsSwitch("borderless")` (`:306`) — reader at `src/system.cpp:68-72` (`[graphics]` +
   `WindowPolicy::IniTrue`, `src/window_policy.h:27-31`).
2. `dpiaware` (`:307-310`) → `SetProcessDPIAware()` before any window exists.
3. `#ifdef MAPLEROOT_INTEGRATION`: `InstallCrashDiagnostics()` and `MessageBoxA`/`MessageBoxW` hooks (`:311-315`) that
   mirror every dialog to `integration.log` (`src/system.cpp:295-303`).
4. `ATTACH_HOOK` on `SetUnhandledExceptionFilter` (`:316`), `CreateMutexA` (`:317`), `CreateWindowExA` (`:318`),
   `RegCreateKeyExA` (`:319`), `WSPStartup` (`:320`).
5. `#ifdef MAPLEROOT_INTEGRATION`: `AttachD3D9Host()` (`:322`) — must precede the game's first Direct3D call.

What those hooks do:

- `CreateMutexA_hook` (`src/system.cpp:42-51`) renames the single-instance mutex `WvsClientMtx` to
  `WvsClientMtx-<pid>` (`:44-48`), so two clients can run and each has its own mutex.
- `CreateWindowExA_hook` (`src/system.cpp:179-194`) acts only on class `MapleStoryClass` (`:180`), rewrites the title to
  `BuildShow::WindowTitle` (`:186`), applies `g_bBorderless ? WS_POPUP : 0xCA0000` (`:187`), re-sets the title after
  creation (`:189`), subclasses the window (`:190`), then `Borderless_Apply()` (`:192`, impl `:198-…`).
- `RegCreateKeyExA_hook` (`src/system.cpp:213`) forces `HKEY_CURRENT_USER` so settings never land in `HKLM`.
- `WSPStartup_hook` (`src/system.cpp:283-289`) saves the provider table and replaces `lpWSPConnect`/`lpWSPGetPeerName`
  (`:286-287`). `WSPConnect_hook` (`:256-275`) matches the destination against `g_asOriginalAddress` — the three Nexon
  v83 addresses plus `127.0.0.1` (`:223-228`) — and then: remembers the original address (`:263`), runs
  `ResolveServerAddress` once via `InitOnceExecuteOnce` (`:264-265`), overwrites `sin_addr` (`:267`) and `sin_port`
  (`:270`). Host resolution prefers `InetPtonA`, falls back to `getaddrinfo` (`:237-248`), and on failure shows
  `Could not find the server "<host>". Check your internet connection and the host in config.ini.` (`:250-251`).
  `WSPGetPeerName_hook` (`:277-281`) reports the Nexon address back so the client's own logging does not leak the real
  server IP.

### 4. Second-stage gate — `AttachClientHooks`

`SetUnhandledExceptionFilter_hook` (`src/system.cpp:26-36`) checks `_ReturnAddress() == 0x00796FDD` (`:28`), i.e. it is
being called from `ZExceptionHandler`'s constructor, which runs after the dynamic initializers for
`ZAllocEx<T>::_s_alloc` have populated the game's allocator globals. Only then does it call `AttachClientHooks()`
(`:29`). This ordering is deliberate: the ZAllocEx addresses the DLL pins (`src/injector.cpp:7-10`,
`0x00BF0B00`/`0x00BF0A90`/`0x00BF0BA8`) are the game's allocator vtable slots and must exist before any ZTL object is
constructed by a hook.

`AttachClientHooks` is guarded by a `static bool initialized` (`src/hook.h:130-132`), so a repeated trigger is a no-op.

### 5. `AttachClientHooks` — the ordered client patch table

Exactly as written in `src/hook.h:129-304`:

| # | Step | Line | Guard |
|---|---|---|---|
| 1 | `AttachClientBypass()` | `:134` | always |
| 2 | `AttachMountInfoMod()` + verify | `:136-137` | `MAPLEROOT_MOUNT_INFO` |
| 3 | `AttachMovementKeysMod()` + verify | `:140-141` | `MAPLEROOT_MOVEMENT_KEYS` |
| 4 | verify group "AttachClientBypass" | `:144` | `MAPLEROOT_INTEGRATION` |
| 5 | `AttachHpMpMod()` | `:148` | always |
| 6 | `InitializeMapleRoot()` | `:150` | `MAPLEROOT_INTEGRATION` |
| 7 | `AttachClientInlink()` + verify | `:153-155` | always / integration verify |
| 8 | `AttachStringPoolMod()` + verify | `:158-160` | always |
| 9 | `AttachResManMod()` + verify | `:163-165` | always |
| 10 | `InstallAvatarFeatures(...)` (storage, chair, item effect) | `:172-190` | always |
| 11 | `AttachResolutionMod()` + verify | `:192`, `:199` | always |
| 12 | `AttachWeatherMod()`/`AttachWeatherWindMod()` + verify | `:194-196` | `MAPLEROOT_WEATHER` |
| 13 | `AttachMobHpTagMod()` | `:203` | `!MAPLEROOT_INTEGRATION` |
| 14 | Monster Book: found-in, drops, search + verify | `:210-214` | `MAPLEROOT_MONSTER_BOOK` |
| 15 | Color Prism: tint, window, host hooks + verify | `:217-222` | `MAPLEROOT_COLOR_PRISM` |
| 16 | `AttachToolTipMod()` + verify | `:225-227` | always |
| 17 | `AttachIconIconMod()` + verify | `:230-232` | always |
| 18 | `AttachPetEquipFlagMod()` + verify | `:235-237` | always |
| 19 | `AttachProposalGenderMod()` + verify | `:240-242` | always |
| 20 | `AttachCreateBlockMod()` + verify | `:245-247` | always |
| 21 | `AttachHudMetersMod()` | `:251` | `MAPLEROOT_HUD_METERS` |
| 22 | `AttachDisplayMod()` | `:255` | `MAPLEROOT_DISPLAY_TOGGLES` |
| 23 | `AttachMidAirMod()` | `:258` | always (self-gates on ini) |
| 24 | `AttachDamageFormatMod()` | `:261` | `MAPLEROOT_DAMAGE_FORMAT` |
| 25 | `AttachInvResizeMod()` + verify | `:265-267` | `MAPLEROOT_INV_RESIZE` |
| 26 | `AttachUiLayoutMod()` | `:271` | always |
| 27 | `AttachMobDamageMod()` + verify | `:273-275` | always |
| 28 | `AttachMeleeRangeMod()` + verify | `:278-280` | always |
| 29 | `AttachMobAttackFixMod()` | `:283` | always (self-gates on ini) |
| 30 | `AttachTempStatMod()` | `:286` | `!MAPLEROOT_INTEGRATION` |
| 31 | `AttachBounceMod()` + verify | `:293-294` | `MAPLEROOT_INTEGRATION` |
| 32 | `AttachAttackDiagMod()` + verify | `:298-301` | `MAPLEROOT_ATTACK_DIAG` |
| 33 | `HpMp_Verify()` | `:303` | always, last |

Two ordering constraints are stated in the source and matter:

- HP/MP is patched **before** MapleRoot and every other mod "so every HP/MP site is checked against the stock client"
  (`src/hook.h:146-147`).
- Monster Book drops before search, because the search module reads the 16-per-page Dropping patch
  (`src/hook.h:209`).

`VerifyKaentakeHooks(group)` (`src/mapleroot.cpp:30-35`) checks the cumulative failure counter and, if non-zero, logs
`Required hook group failed: <group>` and aborts startup through `Fail` (`:24-28`) → `ErrorMessage` +
`ExitProcess(ERROR_DLL_INIT_FAILED)`. `HpMp_Verify()` (`src/hpmp.cpp:75-84`) does the same for its own table but with
its own message: every mismatch calls `RefuseToStart` (`src/hpmp.cpp:40-49`), which shows
`SlumsMS cannot start: the 4-byte HP/MP patch <problem> at 0x<va> (<what>) … Repair or reinstall the game with the
launcher.` and `ExitProcess(1)`.

### 6. The patch primitives

| Primitive | File:line | Mechanism |
|---|---|---|
| `AttachHook(&target, detour)` | `src/hook.cpp:80-89` | Microsoft Detours transaction via `NativeDetourOps` (`:70-77`); failure increments `hookFailures` (`:69`, `:84`) and logs `HOOK FAILED phase=… error=… target=… detour=…` (`:85-86`) |
| `DetachHook` | `src/hook.cpp:91-99` | same, `DetourDetach` |
| `PatchMemory(addr, val, size)` | `src/hook.cpp:157-162` | `VirtualProtect` → `PAGE_EXECUTE_READWRITE`, `memcpy`, restore |
| `Patch1/4/Str` | `src/hook.h:334/339/344` | typed `PatchMemory` wrappers |
| `PatchNop` | `src/hook.h:349-354` | malloc + `memset 0x90` + `PatchMemory` |
| `PatchJmp` | `src/hook.h:358-361` | `0xE9` + rel32 |
| `PatchCall` | `src/hook.h:364-372` | `0xE8` + rel32, optional NOP pad; refuses `uSize < 5` |
| `PatchRetZero` | `src/hook.h:377-379` | writes `33 C0 C3` (`xor eax,eax; ret`) |
| `GetAddress(module, proc)` | `src/hook.cpp:109` | `GetModuleHandle`/`LoadLibrary` + `GetProcAddress` |
| `GetAddressByPattern(module, pat)` | `src/hook.cpp:125` | `ParsePattern` (`:24`, `??` = wildcard) + `FindPattern` (`:47`) |
| `PatchAllByPattern` | `src/hook.cpp:164` | scan-and-write every match |
| `VMTHook` | `src/hook.cpp:102-107` | swaps a vtable slot — **no callers anywhere in `src/`, `mapleroot/` or `tools/`** |

Note that `PatchMemory` and the `Patch*` templates do **no** byte verification. Only the modules that keep an expected-byte
table (`hpmp`, `createblock`, `midair`, `movementkeys`, `attackstop`, `invresize`, `mobdamage`, `meleerange`,
`mapobj_guard`, …) check stock bytes first. The patches in `AttachClientBypass` and `system.cpp` do not.

`AttachHook` failures are counted, not fatal by themselves; fatality comes from the `VerifyKaentakeHooks` call that
follows, and only in `MAPLEROOT_INTEGRATION` builds. In a non-integration build nothing calls `Fail`, so a failed detour
is logged and the client continues without that feature.

### 7. Companion patches (`mapleroot/ijl15/YourStuff.h`)

`doini()` (`mapleroot/ijl15/YourStuff.h:600-657`) reads `config.ini` with its own `INIReader` (`:602`) and applies, in
file order: the split attack-stop trio via `ApplyMoveAttackSettings` (`:628-641`, `iniBool` at `:632-634`), `jumpshoot`
E9 at `0x009539FA` (`:643-647`), `jumpmagic` E9 at `0x009559E5` (`:649-653`), and
`Hook_CWvsApp__EnableWinkey(reader.GetBoolean("clienthax","enablewinkey","true"))`. `nobulb` (`PatchNop 0x00A08D5B`,
5 bytes) and `customcolor` (three 4-byte writes) live earlier in the same function. This is the second, parallel patch
table: the same `config.ini`, a different reader, and no shared verify step.

### 8. Game runs

From then on the game executes with detours in place. Per-map and per-session work happens inside the hooks:
`CWvsApp::InitializeResMan_hook` (`src/resman.cpp:108`) mounts `Custom` and merges its leaves,
`CWvsApp::CleanUp_hook` (`src/resman.cpp:221-257`) unmounts and releases the canvas library, and every incoming packet
passes through `ProcessPacketHook` (`src/packetdispatcher.cpp:32-99`) first.

## Data

| Item | File:line | Detail |
|---|---|---|
| `g_sServerHost` | `src/injector.cpp:14` | `char*`, `_strdup`d from argv or ini; null when neither sets it |
| `g_nServerPort` | `src/injector.cpp:15` | `long`; 0 = "do not rewrite the port" (`src/system.cpp:269`) |
| `g_ServerAddress` / `g_bServerAddressResolved` | `src/system.cpp:232-233` | resolved once, first connect |
| `g_uNexonAddress` | `src/system.cpp:221` | original destination, replayed by `WSPGetPeerName_hook` |
| `g_ProcTable` | `src/system.cpp:220` | the provider's function table, chained, not replaced |
| `g_asOriginalAddress` | `src/system.cpp:223-228` | `63.251.217.2/3/4` + `127.0.0.1` |
| `hookFailures` | `src/hook.cpp:69` | `volatile LONG`, cumulative over the process |
| `initialized` | `src/hook.h:130` | makes `AttachClientHooks` idempotent |
| `g_pCustomNameSpace` | `src/resman.cpp:126`, cleared `:252` | mounted `Custom` namespace |
| `g_ownedCanvasModule` | `src/inlink.cpp:59-61` | the `CANVAS.DLL` handle the DLL owns and frees (`:68-82`) |
| `dispatchGate`, `shuttingDown` | `src/packetdispatcher.cpp:30-31` | serialize addon packet handlers; stop after shutdown |
| `ms_aKey` / `ms_aString` | `src/stringpool.cpp:15-16` | `0x00B001EC` / `0x00BDC9D4`, the game's encoded string pool tables |
| `integration.log` | `src/debug.cpp:14-16` | opened `"a"` per message, only under `MAPLEROOT_INTEGRATION` |

The `Custom` namespace is mounted read-mostly over the stock root: `AddObject(L"Custom", …)` (`src/resman.cpp:125-126`),
then `pPackage->Init(L"83", L"Custom", pArchive)` and `Mount(L"/", pPackage, 1)` (`:141-142`), with the archive taken
from the folder above the exe (`src/resman.cpp:128-140`: `GetModuleFileNameA` → `Dir_upDir` → `fs->item[L"Custom.wz"]`). Which paths may be served whole is decided by `src/customwz_policy.h`:
`ImagePrefix` (`:19-29`) and `TopDir` (`:32-35`) — an image with a stock file is merged leaf by leaf, one without is
served whole (`src/customwz_policy.h:1-14`).

## Switches

All `config.ini` keys that govern this subsystem. "Reader" is the code that consumes the value.

| Key | Default | Reader (file:line) | Effect |
|---|---|---|---|
| `[config] host` | `127.0.0.1` (`config.example.ini:85`; the compiled fallback is `CONSTANTS_DEFAULT_HOST`, `src/constants.h:9`) | `src/injector.cpp:34`, fallback `src/system.cpp:236` | server host, resolved once |
| `[config] port` | `8485` (`config.example.ini:86`) | `src/injector.cpp:38`, applied `src/system.cpp:270` | login port; 0 leaves the stock port |
| `[graphics] borderless` | `false` (`config.example.ini:74`) | `src/system.cpp:306` via `:68-72` | `WS_POPUP` window, centred |
| `[graphics] dpiaware` | `false` (`config.example.ini:75`) | `src/system.cpp:307` | `SetProcessDPIAware()` |
| `[clienthax] jumpkeydown` | `false` (`config.example.ini:10`) | `src/midair.cpp:16-17` | NOP the keydown air gate |
| `[clienthax] attackwalk` | `false` (`config.example.ini:22`) | `mapleroot/ijl15/YourStuff.h:632-641` | attack-stop site 1 |
| `[clienthax] attackjump` | `true` (`config.example.ini:23`) | same | attack-stop site 3 |
| `[clienthax] chargemove` | `true` (`config.example.ini:24`) | same | attack-stop site 2 |
| `[clienthax] moveattack` | (absent from the example; `value_or(true)`) | `src/attackstop_patch.h:109-116` | **legacy fallback** for `attackjump`/`chargemove` only |
| `[clienthax] jumpshoot` | `true` (companion default) | `mapleroot/ijl15/YourStuff.h:643` | E9 at `0x009539FA` |
| `[clienthax] jumpmagic` | `true` (companion default) | `mapleroot/ijl15/YourStuff.h:649` | E9 at `0x009559E5` |
| `[clienthax] enablewinkey` | `true` (companion default) | `mapleroot/ijl15/YourStuff.h` end of `doini` | replaces the unconditional `EnableWinKey` patch in integration builds |

Only `host`/`port`, `[graphics]` and `jumpkeydown` are read by `src/`; the rest of the companion's keys
(`nobulb`, `customcolor`, `jumpshoot`, `jumpmagic`, `enablewinkey`, chair/skill opacity, …) are read by the companion's
`INIReader`, not by `GetPrivateProfileStringA`.

### Patch → site → switch → default

Every write this subsystem performs, with the gate that controls it. "—" means there is no switch.

| Patch | File | VA / offset | Switch | Default |
|---|---|---|---|---|
| `ShowStartUpWndModal` → `ret 0` | `src/bypass.cpp:481` | `0x009F18C9` | — | **always on** |
| `ShowAdBalloon` → `ret 0` | `src/bypass.cpp:482` | `0x00422C7E` | — | **always on** |
| `SendHSLog` → `ret 0` | `src/bypass.cpp:483` | `0x009F191B` | — | **always on** |
| `ZExceptionHandler::UnhandledExceptionFilter` → `0xEB` | `src/bypass.cpp:484` | `0x00797074` | — | **always on** |
| `CWvsApp::EnableWinKey` → `ret 0` | `src/bypass.cpp:479` | `0x009FEC62` | — (only `#ifndef MAPLEROOT_INTEGRATION`) | **always on in non-integration builds**; companion hooks the same VA instead, `[clienthax] enablewinkey` = `true` |
| create-block `je` → `jmp` | `src/createblock.cpp:27-28` | `0x005F6440` (site `0x005F6425` + `kWriteOffset`), 1 byte `74`→`EB` | — | **always on** |
| Title string #1163 → `SlumsMS` | `src/stringpool.cpp:53` | pool `0x00BDC9D4` index 1163 | — | **always on** |
| `CANVAS.DLL` `raw_Serialize` detour + 2 `GetUnknown` detours | `src/inlink.cpp:62-65` | pattern `B8 ???????? E8 ???????? 83 EC 6C` | — | **always on** |
| `Custom` namespace mount + `CWzProperty::Serialize` merge | `src/resman.cpp:260-263` | patterns in `NAMESPACE.DLL` / `PCOM.DLL` (`:206`, `:210`) | — | **always on** |
| Custom packet dispatch detour | `src/packetdispatcher.cpp:105` | `0x004965F1` | — (compile-time opcode set) | on when any addon/INTEGRATION is built (`src/bypass.cpp:468-470`) |
| HP/MP 4-byte widening | `src/hpmp.cpp:53-73` | ~60 sites, table in `src/hpmp_patch.h` | — | **always on** (verified last, `src/hook.h:303`) |
| mutex rename | `src/system.cpp:42-51` | `KERNEL32!CreateMutexA` | — | always on |
| window style + title + subclass | `src/system.cpp:179-194` | `USER32!CreateWindowExA` | `[graphics] borderless` | `false` (style `0xCA0000`) |
| force `HKCU` | `src/system.cpp:213` | `ADVAPI32!RegCreateKeyExA` | — | always on |
| socket redirect | `src/system.cpp:283-289` | `WS2_32!WSPStartup` | `[config] host`/`port` | `127.0.0.1` / `8485` (`port` 0 = no rewrite) |
| DPI awareness | `src/system.cpp:307-310` | `SetProcessDPIAware` | `[graphics] dpiaware` | `false` |
| keydown air gate NOPs | `src/midair.cpp:19-26` | `0x0096A954`, 6 bytes | `[clienthax] jumpkeydown` | `false` |
| attack-stop site 1 (`keydown skill movable`) | `src/attackstop_patch.h:47` | `0x0095F97A`, `7F 2C`→`EB 59` | `[clienthax] attackwalk` | `false` |
| attack-stop site 2 (`WorkUpdateActive`) | `src/attackstop_patch.h:48` | `0x009CBFB0`, `7E`→`EB` | `[clienthax] chargemove` | `true` (or legacy `moveattack`) |
| attack-stop site 3 (`Jump` one-time gate) | `src/attackstop_patch.h:49-50` | `0x0094C3BB`, 6 bytes → 6×`90` | `[clienthax] attackjump` | `true` (or legacy `moveattack`) |
| shoot-in-air `E9` | `mapleroot/ijl15/YourStuff.h:643-647` | `0x009539FA` → `0x00953A11` | `[clienthax] jumpshoot` | `true` |
| magic-in-air `E9` | `mapleroot/ijl15/YourStuff.h:649-653` | `0x009559E5` → `0x00955A20` | `[clienthax] jumpmagic` | `true` |
| no-bulb NOPs | `mapleroot/ijl15/YourStuff.h:616-618` | `0x00A08D5B`, 5 bytes | `[clienthax] nobulb` | `true` |
| orange colour case | `mapleroot/ijl15/YourStuff.h:620-624` | `0x0098B70C+1`, `0x008C4944+1`, `0x008AA6CB+1` | `[clienthax] customcolor` | `true` |
| bindable movement keys (4 detours + jmp + nop + `Patch4`) | `src/movementkeys.cpp:206-217` | `0x00836619`, `0x00836C82`, `0x00836C5F`, `0x0059A25A`, `0x0059B00A`, `0x00833FB0` | — (compile-time `MAPLEROOT_MOVEMENT_KEYS`) | on when the option is built |

Four rows carry no gate at all and are written without any stock-byte check: the four `src/bypass.cpp:481-484` patches.
The create-block, movement-keys and attack-stop rows have no ini key either, but each validates the bytes or pointer it
expects before writing (`src/createblock.cpp:17-26`, `src/movementkeys.cpp:189-205`, `src/attackstop_patch.h:124-129`).
The string-pool row writes into a runtime table (`src/stringpool.cpp:15-16, 48`), not into exe code, so there is nothing
to verify; the inlink and resman rows resolve their targets by pattern and log a hard error when the pattern is missing
(`src/hook.cpp:150-153`).

Fallback rules worth naming:

- Command line beats `[config]` (`src/injector.cpp:30`).
- `attackjump`/`chargemove` fall back to the legacy `moveattack`; `attackwalk` deliberately does **not**
  (`src/attackstop_patch.h:110-116`, documented at `config.example.ini:18-21`).
- `mobattackfix`, `mapobjguard`, `lowdefensepenalty`, `invresize`, weather keys, `d3d9host`/`adapterfallback` are read
  by their own modules, outside this document's scope.

There is **no ini key** for anything in the next section.

## Invariants

- **Patch sites are absolute VAs into one exe build.** Image base is `0x400000` and the exe is pinned by sha256
  `86e4e6be…ac90` (`src/createblock_patch.h:29`, repeated in every `*_patch.h`). Nothing in `src/` verifies the running
  exe at runtime — the sha256 appears only in comments (see Known issues).
- **`AttachClientHooks` runs once, and only from the `ZExceptionHandler` frame** (`src/system.cpp:28`,
  `src/hook.h:131`). Running it earlier breaks the `ZAllocEx` globals it pins (`src/injector.cpp:7-10`).
- **HP/MP goes first among the feature modules** (`src/hook.h:146-148`) so its sites are validated against stock bytes.
- **`HpMp_Verify()` is last** (`src/hook.h:303`) and is the one check that can never be compiled out; a mismatch is a
  hard `ExitProcess(1)` (`src/hpmp.cpp:47-48`).
- **A failed detour is fatal only in integration builds**, and only if a `VerifyKaentakeHooks` group follows it
  (`src/mapleroot.cpp:30-35`). Non-integration builds log and continue.
- **`PatchMemory` is unverified by construction** (`src/hook.cpp:157-162`): the caller must have checked bytes.
- **The socket rewrite matches only the four stock destinations** (`src/system.cpp:223-228,259-262`). A client whose
  `CWvsApp` connects anywhere else is not redirected.
- **Host resolution happens on the first connect, never in `DllMain`** (`src/system.cpp:230-231`) — the loader lock is
  held during `DLL_PROCESS_ATTACH`.
- **`Custom.wz` never replaces a stock image wholesale** when the stock file exists (`src/customwz_policy.h:1-14`) —
  the rc3-rc5 damage-digit and the 1.6.0.2 Arcane-boss regressions came from violating this.
- **The companion is linked in, not loaded** (`src/mapleroot.cpp:7`), so bridge version `0x00010001`
  (`src/mapleroot.cpp:53`) is a self-check, and a stale `ijl15.dll` on disk is pure noise — except that it patches the
  same VAs first, which is why the attack-stop sites are written only over the exact bytes the writer expects
  (`src/attackstop_patch.h:124-129`).

## Origin

| Area | Origin | Evidence |
|---|---|---|
| `injector.cpp`, `hook.cpp`, `resman.cpp` | **base (Kaentake)** | `git log --follow` bottom commit `de776d0 2025-08-26 "Initial commit"` (injector) and `6003540 2025-08-27 "Add system hooks and helper functions and macros"` (hook/system); `docs/PROVENANCE.md:3` — history begins at `joshhmann/kaentake` `2a43a01e7f4cdd75cda08ab533049230760d7cfa` |
| `resman.cpp` Custom.wz mounting | **base**, later modified | `git log` first real commit `4fd8045 2025-10-01 "Add resman.cpp to load Custom.wz and apply overrides"`; the whole-file/merge policy is ours (`src/customwz_policy.h:1-14`) |
| `bypass.cpp` patch block | **base**, extended | `git log` newest `10a386e 2026-10-28 "Build stamp format 2: caps bit 0 says the custom NPC imgs load"` |
| `system.cpp` hooks | **base**, extended | `CreateMutexA`/`WSP*`/`CreateWindowEx` from `6003540`; `[graphics]` policy, title, borderless and D3D9 hosting added later (newest `88cf6b5 2026-10-28`) |
| `hook.h` `AttachClientHooks` table | **modified** | first `6003540 2025-08-27`, newest `3e640f1 2026-11-04` (b251 mob attack-info guard); the module list grew with each addon |
| `checked_hook.h`, `patch_common.h`, `mapleroot.cpp`, `packetdispatcher.cpp`, `movementkeys.cpp` | **custom** | all first appear in `6119130 2026-10-18 "Checkpoint accepted MapleRoot client integration and addons"`; `docs/PROJECT-STATUS.md:24` — "Kaentake handles startup, networking, resolution and Custom.wz. The MapleRoot companion retains gameplay extensions through bridge ABI 0x00010001." |
| `createblock.cpp` | **custom** | `6590386 2026-10-22`; rationale in the header, not in a ticket (`src/createblock_patch.h:8-27`) |
| `midair.cpp`, `attackstop_patch.h` | **custom split of a companion feature** | attack-stop sites come from MapleRoot's single `moveattack` write, split into three keys (`src/attackstop_patch.h:45-53`, `config.example.ini:13-24`); history `44ab18f`/`53fd583 2026-10-21` |
| `stringpool.cpp` | **custom** | first `f3f6b56 2026-02-27 "Implement AttachStringPoolMod"`, encode fix `5fd4e4e 2026-04-02 "Fix stringpool encode error (#11)"`, carried over in `f1b64a6 2026-10-19 "Ship the client as one slums.dll started by SlumsMS.exe"` |
| `inlink.cpp`, `inlink_guard.cpp` | **custom** | both landed in `e2be634 2026-10-31`; the guard exists because b103 (rc12) crashed the client on a linked minimap canvas (`src/inlink_guard.h:8-12`) |
| `mapleroot/ijl15/**` | **MapleRoot upstream** | `docs/PROVENANCE.md:3` — "The companion source is the MapleRoot ijl15 source port, with only relative project paths adjusted" |

`docs/PROVENANCE.md` names neither `injector.cpp` nor `hook.cpp`; their Kaentake ancestry is proven only by git history.

## Tests and QA

Compiled-in tests (all `add_test` in `tests/CMakeLists.txt`) that cover this subsystem's pure logic:

| Test | Line | Covers |
|---|---|---|
| `checked_hook_transactions` | `tests/CMakeLists.txt:10` | `integration::ChangeHook` phase/error/commit semantics (`src/checked_hook.h:13-24`) — validate 87, begin/update/attach/abort/commit ordering |
| `createblock_never_blocked` | `:27`/`:29` | `CreateBlock::Classify`/`ShouldPatch` and the patched-byte image; the optional form takes a real exe path (`CREATEBLOCK_TEST_EXE`, `:25`) |
| `midair_sites_policy` | `:166` | the three midair sites' stock bytes and jump targets (`src/midair_patch.h:26-28`) |
| `attackstop_moveattack_sites` | `:151` | site bytes, classification and the ini precedence table (`src/attackstop_patch.h:45-53,109-129`) |
| `movementkeys_wz` | `:95` | the WZ-side key/label data; needs `MAPLEROOT_MOVEMENT_KEYS` and a test client with `UIWindow.img`/`UI.wz` (`:91`, `MOVEMENTKEYS_TEST_CLIENT` default `:90`) |
| `customwz_image_prefix` | `:80` | `CustomWz::ImagePrefix`/`TopDir` (`src/customwz_policy.h:19-35`) |
| `inlink_broken_link_no_crash` | `:114` | `FillLinkedCanvas` returning without throwing; needs `allInvisibleTile.img` (`:110`) |
| `window_borderless_place` | `:162` | `window_policy.h` placement/ini parsing used by `src/system.cpp:68-72` |
| `mapobj_guard_skips_missing_art` | `:176`/`:178` | the missing-art HRESULT filter used by the same map-load hook family |

Build script checks (`scripts/build.ps1`): submodule init must succeed (`:5-6`), VS2022 x86 tools must exist (`:7-9`),
the tree must configure with `MAPLEROOT_INTEGRATION=ON` plus the addon set (`:10-12`), build Release (`:15`), and pass
ctest (`:17-18`, throw `'Native tests failed'`). The root `CMakeLists.txt` defaults every option to `OFF`, and
`SHUTDOWN_SMOKE_TEST` without `INTEGRATION` is a `FATAL_ERROR`.

**Not covered — must be confirmed by hand on a real Windows client:**

- `DllMain` itself, `ProcessCommandLine`, `ProcessConfigFile`, and the "command line wins over ini" rule.
- Every `ATTACH_HOOK` in `AttachSystemHooks` — the mutex rename, window subclassing, `HKCU` forcing, and the whole
  `WSP*` chain (no test constructs a socket).
- The second-stage gate (`_ReturnAddress() == 0x00796FDD`) and the `initialized` idempotence.
- `AttachHook`/`DetachHook` transaction behaviour against real Detours (`checked_hook_test.cpp` uses a fake `Ops`).
- `VerifyKaentakeHooks` → `Fail` → `ExitProcess` reachability, and that `integration.log` actually receives
  `SESSION_START` / `SESSION_FEATURES_READY` / `HOOK FAILED`.
- `Custom.wz` mounting and the merge-vs-whole decision against real archives (`customwz_image_prefix` tests the string
  function only).
- `PatchMemory` page-protection behaviour and every unconditional patch in `AttachClientBypass`.
- The companion's `doini()` table.

There is no automated check anywhere that the exe being patched is the exe the addresses were derived from.

## Known issues

- **Unconditional patches — no off-switch at all.** `src/bypass.cpp:481-484` writes `ShowStartUpWndModal`
  (`0x009F18C9`), `ShowAdBalloon` (`0x00422C7E`), `SendHSLog` (`0x009F191B`) and
  `ZExceptionHandler::UnhandledExceptionFilter` (`0x00797074 ← 0xEB`) with no ini key; `EnableWinKey`
  (`0x009FEC62`) is unconditional only in builds without the companion (`src/bypass.cpp:478-480`). None of these four
  byte-verified first, unlike the addon tables. If a site ever differs (different exe build, another injector), the
  client silently writes four bytes into whatever is there.
- **`NEW-FINDING:` no runtime exe verification exists.** `grep` for `sha256`/`ImageBase` across `src/` hits comments
  only (`src/createblock_patch.h:29`, `src/hpmp_patch.h:30`, `src/mapobj_guard.h:33`, `src/meleerange_patch.h:24`,
  `src/mobattackfix_patch.h:56`, and others). Every patch trusts base `0x400000` and fixed offsets. This contradicts
  `docs/architecture/client/README.md:40-41` ("The DLL verifies the exe (sha256 and image base), then applies its patch
  tables") — the intended design exists only as a comment.
- **`NEW-FINDING:` `VMTHook` is dead code** — declared `src/hook.h:322`, defined `src/hook.cpp:102-107`, and no caller
  in `src/`, `mapleroot/` or `tools/`.
- **`NEW-FINDING:` `src/midair_patch.h:1-10` still says "SCAFFOLD … nothing patches them yet"** while
  `src/midair.cpp:14-27` patches `kKeydownAirExit` under `[clienthax] jumpkeydown`. Stale header.
- **`NEW-FINDING:` two independent `config.ini` readers with different parsers.** `src/` uses
  `GetPrivateProfileStringA` + `WindowPolicy::IniTrue` (`src/window_policy.h:27-31`), the companion uses its own
  `INIReader` (`mapleroot/ijl15/YourStuff.h:602`). Only `src/attackstop_patch.h:71-74` mirrors the companion's
  `GetBoolean` semantics, and it does so by convention, not by sharing code.
- **`NEW-FINDING:` `AttachClientBypass` patches are not covered by `VerifyKaentakeHooks` groups of their own** — the
  `"AttachClientBypass"` verify at `src/hook.h:144` only checks that the *detours* attached, not that `PatchRetZero`
  writes took effect (there is nothing to check: they are blind writes).
- **`NEW-FINDING:` `UnhandledExceptionFilter` is rewritten at `0x00797074` to `0xEB` ("fix process hang",
  `src/bypass.cpp:484`)** while `MAPLEROOT_CRASH_REPORT` separately wraps the filter (`src/system.cpp:31-34`,
  `src/CMakeLists.txt:76-78`). Two mechanisms touch the same exception path with no shared test, and the build script
  turns neither on (`scripts/build.ps1:11`).
- **b251** (`/root/work/STATUS-1.6.4.md:72`) — client crash entering Rose Garden 211080100; the attack-info guard is
  `AttachMobAttackFixMod` (`src/hook.h:283`), default on (`config.example.ini:46`). Routed but not closed.
- **b270** (`/root/work/STATUS-1.6.4.md:75`) — launcher wrote `manifest.json` without `news[]`; launcher-side, outside this
  subsystem, listed because the client lane owns it.
- **b274** (`/root/work/STATUS-1.6.4.md:76`) — Will's/Magnus boss portals never send a portal-enter; inconclusive, partially a
  client map-data question.
- **L15** (`/root/work/STATUS-1.6.4.md:106`) and **e14** (`:124`) — deferred client work (client fixes + bonds window;
  Slums Exchange window).
- **Ticket history is not in the repo.** `b103`'s rationale exists only as a comment (`src/inlink_guard.h:8-12`);
  `docs/tickets/` does not exist in the client tree, so ticket ids in comments cannot be resolved from source.

## State

**working** for the hook infrastructure, with one partial area.

- Evidence of use: the ordered table is complete and self-checking in integration builds
  (`src/hook.h:129-304` plus `VerifyKaentakeHooks` at `src/mapleroot.cpp:30-35`); the 1.6.4-rc3 tree builds through
  `scripts/build.ps1` with that table; `docs/evidence/movement-keys-acceptance-20261018.json` records
  `status: "passed for isolated tested scope"`, 7 items, 5 native tests, 9 binary anchors verified, with
  `remaining_scope: "Broader key combinations/accessibility layouts and long-session testing not established"`.
- **partial**: `AttachClientBypass`'s four byte patches and the `CreateWindowEx`/`WSP` hooks are unconditional, blind,
  and unverified — compiled and switchable-free by construction, but a change to the exe build would corrupt them
  silently. That is the risk this document names.
- **partial**: `VMTHook` is compiled but unused; `src/midair_patch.h`'s header comment is stale.

## Open questions

- Was a runtime exe checksum ever implemented and then removed? No such code is in `src/`, yet
  `docs/architecture/client/README.md:40-41` describes it as the first step. `git log -S` over the file history would
  answer it; not done here.
- Which of the four unconditional patches in `src/bypass.cpp:481-484` are load-bearing for SlumsMS (and would break the
  game if dropped) versus cosmetic? Nothing in the source says; the owner would have to confirm.
- Does `0x00796FDD` hold on every exe the project ships against, or only on sha256 `86e4e6be…ac90`? If the gate never
  fires, `AttachClientHooks` never runs and the client is silently stock — no log line distinguishes that from "hooks
  installed".
- `[clienthax] moveattack` is honoured by `src/attackstop_patch.h:110` but absent from `config.example.ini`; is any
  deployed `config.ini` still relying on it, and is the fallback meant to be removed at some point?
- `ATTACK_DIAG`'s `[debug] attackdiag` (`src/attackdiag.cpp:212`) and `[debug] uistate` (`src/uilayout.cpp:368`) are
  read but undocumented in `config.example.ini` — debug-only by intent, or drift?
