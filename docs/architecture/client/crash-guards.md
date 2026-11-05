# Crash guards (client)

## Purpose

The v83 client has no containment for a failed WZ lookup: a `_com_error` raised inside map load or packet
handling leaves through a frame that catches nothing, so the process dies and the session ends. SlumsMS ships
five guards, one classifier and one diagnostic that convert those exits into a logged, bounded failure:

| Guard | What it contains | Where |
|---|---|---|
| mob attack-info patch | a `QueryInterface` returning `E_POINTER` during lazy mob attack-info build | `src/mobattackfix.cpp:68`, `src/mobattackfix_patch.h:66-117` |
| map-object guard | a map object whose art the client's `Data` lacks | `src/mapobj_guard.h:1-103`, `src/lamps.cpp:1535-1617` |
| linked-canvas guard | a linked canvas that cannot be resolved, snapshotted, created or filled | `src/inlink_guard.h:5-21`, `src/inlink_guard.cpp:22-89` |
| melee-range guard | a remote melee attack whose afterimage pose has no range record | `src/meleerange.cpp:47-75`, `src/meleerange_patch.h:8-55` |
| checked hook transaction | a hook transaction left half-installed (Detours BEGIN/COMMIT) | `src/checked_hook.h:13-23`, `src/hook.cpp:80-100` |
| crash classifier + report | turns a surviving crash into `crash_reports/LATEST_CRASH.txt` (off in shipped builds) | `src/crash_classify.h:1-152`, `src/crash_report.cpp:96-163` |
| attack diagnostic | makes the *dropped* remote attacks visible without changing them | `src/attackdiag.cpp:211-224` |

Two of the five guards are containment for data defects that were also fixed in data: Rose Garden 211080100
(b251) got `Mob/<id>.img/attackN/info/type` written into `src/Custom.wz` (`68caea7`, `tools/wz/add_mob_attack_type.py:1-29`)
and 272000000 got `timeTemple/pillar_old/*` (`210fb6c`). The guards stay for the residual class, not the one map.

## Entry points

| Where | What |
|---|---|
| `src/mobattackfix.cpp:68` | `void AttachMobAttackFixMod()` — switch check, byte classify, 5-byte patch |
| `src/mobattackfix.cpp:43-57` | `__declspec(naked) void QueryCastCave()` — the patched call target |
| `src/lamps.cpp:1535` | `void* CMapLoadable::MakeObj_hook(int nLayer, IWzProperty* pObjProp)` — where the map-object guard lives |
| `src/lamps.cpp:1498-1506` | `static bool MapObjGuardEnabled()` — reads `[clienthax] mapobjguard` once per process |
| `src/lamps.cpp:1526-1533` | `static void LogSkippedObj(int, IWzProperty*, long)` |
| `src/inlink.cpp:31-33` | `void HandleLinkProperty(IWzCanvasPtr)` — the only caller of the guard |
| `src/inlink_guard.h:15` | `bool FillLinkedCanvas(IWzCanvasPtr pCanvas)` — never throws |
| `src/inlink_guard.h:21` | `void SetLinkedCanvasResMan(InlinkResManAccessor)` — test seam |
| `src/meleerange.cpp:47` | `void __fastcall GetMeleeAttackRange_hook(void*, void*, void*, int, RECT*)` |
| `src/meleerange.cpp:60` | `void AttachMeleeRangeMod()` |
| `src/checked_hook.h:13` | `template <class Ops> HookResult ChangeHook(Ops&, bool install, void** target, void* detour)` |
| `src/hook.cpp:80`, `:91` | `bool AttachHook(void**, void*)`, `bool DetachHook(void**, void*)` |
| `src/crash_report.cpp:155-163` | `LONG WINAPI SlumsTopLevelFilter(EXCEPTION_POINTERS*)` |
| `src/system.cpp:26-36` | `SetUnhandledExceptionFilter_hook` — calls `CrashReport_Wrap` on the game's filter (`src/system.cpp:33`) |
| `src/crash_diagnostics.cpp:27`, `:105-109` | `OnException` VEH + `InstallCrashDiagnostics()` (`AddVectoredExceptionHandler(1, OnException)`) |
| `src/attackdiag.cpp:211` | `void AttachAttackDiagMod()` — returns before hooking when `[debug] attackdiag` is absent or `<= 0` (`:212-215`) |
| `src/hook.h:152-156`, `:277-283`, `:296-302` | install order: inlink, melee range, mob attack fix, attack diagnostic |

Install order matters only for failure visibility: `AttachClientInlink()` is followed by
`VerifyKaentakeHooks("AttachClientInlink")` (`src/hook.h:154-156`) and `AttachMeleeRangeMod()` likewise
(`src/hook.h:279-281`), but `AttachMobAttackFixMod()` has **no** verify (`src/hook.h:282-283`) — a failed
`PatchMemory` there is silent, while a failed `ATTACH_HOOK` elsewhere increments `hookFailures` and, under
`MAPLEROOT_INTEGRATION`, aborts start-up through `Fail("A required Kaentake hook failed; see integration.log")`
(`src/mapleroot.cpp:30-35`).

## How it works

**1. Mob attack-info patch (b251).** Stock `_com_ptr_t<IWzProperty>::operator=` (`0x004052AD`) accepts only
`E_NOINTERFACE` as a tolerated failure (`src/mobattackfix_patch.h:38-42`); any other HRESULT is converted to a
`_com_error` that is raised at `0x0067FE44` from the lazy attack builder (`0x0067FD81`, reached from the cache
getter `0x00529E7B`) and dies uncaught (`src/mobattackfix_patch.h:8-50`). Steps:

1. `AttachMobAttackFixMod()` returns early when `KeepStockThrow()` is true (`src/mobattackfix.cpp:70-73`).
2. `Classify()` memcmps the five site bytes at `kAddr_Site 0x0067FE33` against `E8 75 54 D8 FF`
   (`src/mobattackfix_patch.h:81-86`, `:99-103`): `Original` → patch, `Patched` (`current[0] == 0xE8`) →
   leave alone, anything else → `Unknown` and keep the stock throw (`src/mobattackfix.cpp:75-84`).
3. The patch itself is a same-length rel32 `call QueryCastCave` written by `PatchMemory`
   (`src/mobattackfix.cpp:85-87`; `PatchMemory` = `VirtualProtect(PAGE_EXECUTE_READWRITE)` → `memcpy` → restore,
   `src/hook.cpp:157-162`).
4. The cave (`src/mobattackfix.cpp:43-57`) calls the stock callee, compares `eax` against `80004003h`, and only
   for `E_POINTER` replaces the return address with `kAddr_ToleratedPath 0x0067FE49` — the branch stock uses
   for `E_NOINTERFACE`. Everything else falls through to the stock compare at `0x0067FE38`. It writes no
   register but the stack slot and ends in `ret 4`, so the callee sees the exact stock frame.
5. The tolerated path is a real state: `004052AD` clears its destination on failure
   (`src/mobattackfix_patch.h:38-42`), `0067FD81` hands back a null out-object, `00529E7B` returns 0, and every
   caller tests for 0 (`0066A7A7`, `0066A517`, `00667A09` … — the list is in the `3e640f1` commit body). The
   attack entry is left empty; nothing dereferences it.

Decision logic is unit-tested against the whole `0x80004000..0x80004005` range (`tests/mobattackfix_patch_test.cpp:56-90`):
exactly one HRESULT is redirected, and every redirected HRESULT is one stock would raise on
(`MobAttackFix::StockRaises`, `src/mobattackfix_patch.h:115-117`).

**2. Map-object guard.** `CMapLoadable::MakeObj` (`0x0063AD16`) is called once per object from `0x0063ACB5`;
its return address `0x0063ACBA` loads its own local (`8B 45 F0`) and **ignores `eax`** — the object is inserted
into the layer by `MakeObj` itself (`src/mapobj_guard.h:35-42`, `src/lamps.cpp:1604-1607`). The art lookup
precedes that insert, so a throw leaves the layer one object short, never half-built. Steps:

1. `MakeObj_hook` runs the weather/lamp capture first (`src/lamps.cpp:1539`), then checks
   `MapObjGuardEnabled()`; off = a plain stock call (`src/lamps.cpp:1601-1603`).
2. The stock call is wrapped in `try`/`catch (const _com_error& e)` (`src/lamps.cpp:1608-1610`).
3. `MapObjGuard::Classify(e.Error())` skips exactly `E_POINTER`, `E_FAIL`, `STG_E_FILENOTFOUND`,
   `STG_E_PATHNOTFOUND` (`src/mapobj_guard.h:48-69`).
4. `Skip` → `LogSkippedObj` writes `map obj: skipped Obj/<oS>.img/<l0>/<l1>/<l2> on layer N: <reason>; the object
   is left out, the map stays` (`src/lamps.cpp:1531-1532`) and the hook returns `nullptr`
   (`src/lamps.cpp:1611-1613`). `Propagate` → bare `throw;` (`src/lamps.cpp:1615`).

The hook is attached by `Lamps_Attach()` (`src/lamps.cpp:2633-2635`) from `AttachWeatherMod()`
(`src/weather.cpp:2285`), so the guard only exists in `MAPLEROOT_WEATHER` builds, where the same hook also
serves the lamps feature (`src/CMakeLists.txt:100-105`).

**3. Linked-canvas guard.** Two detours feed every canvas link into `HandleLinkProperty` (`src/inlink.cpp:31-33`):
`get_unknown_hook` on `0x00414ADA` (`src/inlink.cpp:35-43`) and `Ztl_variant_t__GetUnknown_hook` on
`0x004032B2` (`src/inlink.cpp:45-50`), both attached in `AttachClientInlink` (`src/inlink.cpp:64-65`). Each
calls stock first, then queries `IWzCanvas` and only then resolves links, so an unresolvable link never blocks
the stock return. `FillLinkedCanvas` (`src/inlink_guard.cpp:22-89`):

1. null canvas → `false` (`:23-25`).
2. inside one `try`: read `property`, then each of `_inlink`, `_outlink`, `source` as `VT_BSTR` (`:26-41`).
3. `GetObjectA(...).GetUnknown()` + `QueryInterface(IWzCanvas)`; failure logs `inlink: unresolved ...` and
   continues to the next key (`:45-49`).
4. `GetSnapshot` (needs positive width/height), `get_rawCanvas`, `raw_Create`, `raw_AddRawCanvas` each guarded
   with `continue` + a named log line (`:55-75`).
5. `origin` is read through a null check — the b103 shape (a minimap canvas with no `origin`) is simply left with
   `cx = cy = 0` (`:77-83`).
6. any `_com_error` escaping the above → `inlink: gave up (0x%08lX)` and `false` (`:86-89`).

Failure returns `false` and leaves the canvas untouched: a broken link costs one tile, not the session
(`src/inlink_guard.h:8-12`). There is no config switch on this path.

**4. Melee-range guard.** `CUserRemote::OnMeleeAttack` (`0x00980C90`) takes the range from
`CActionMan::GetMeleeAttackRange(Ztl_bstr_t, int, RECT&)` (`0x00414440`), whose data comes from
`GetWeaponAfterImage` (`0x0040AC78`) — a `ZArray` of `0xA2` action slots of `0x30` bytes, of which only the
first `0xA20` bytes (54 slots) are zeroed (`src/meleerange_patch.h:8-18`). A pose with no data sits in an
uninitialised slot whose `_ZtlSecureFuse<long>` (`0x00416563`) checksum never matches and throws out of packet
processing (`src/meleerange_patch.h:14-16`). Steps:

1. `AttachMeleeRangeMod()` classifies the 10-byte entry against
   `B8 3C 78 A7 00 / E8 4E C7 64 00` (`src/meleerange_patch.h:40-46`, `:51-55`); `Detoured`/`Unknown` → keep
   stock behaviour with a log line (`src/meleerange.cpp:64-69`).
2. `ATTACH_HOOK` detours the function (`src/meleerange.cpp:73`).
3. The hook copies the afterimage name under a 20-line cap (`src/meleerange.cpp:18-19`, `:49`), calls stock
   inside `__try`, and `ThrowFilter` accepts only `0xE06D7363` (`src/meleerange.cpp:34-45`).
4. On the caught C++ throw it does `SetRectEmpty(rc)` and logs once per 20 (`src/meleerange.cpp:51-55`) — the
   attack is still drawn, without a range rectangle.

**5. Checked hook transaction.** Detours requires BEGIN → UPDATE_THREAD → ATTACH → COMMIT; a failure between
BEGIN and COMMIT leaves the process with a transaction the next hook call would inherit. `ChangeHook`
(`src/checked_hook.h:13-23`) makes the sequence one value: `{87, "validate"}` for a null target/detour, `begin`,
`update`, `attach`/`detach` (each with `ops.abort()` on failure), then `commit` — and it commits *even on
error*, because Commit is what consumes the transaction (`src/checked_hook.h:10-11`). `AttachHook`
(`src/hook.cpp:80-90`) turns a false result into `InterlockedIncrement(&hookFailures)` plus
`HOOK FAILED phase=%s error=%ld target=%p detour=%p`; `GetKaentakeHookFailureCount()` (`src/hook.cpp:79`) is
what `VerifyKaentakeHooks` reads to abort start-up.

**6. Crash classifier and report.** Armed only in `MAPLEROOT_CRASH_REPORT` builds (`src/CMakeLists.txt:76-79`),
which `scripts/build.ps1:11` does **not** enable. Two collectors feed it:

1. the VEH `OnException` writes `crash.log` lines for first-chance exceptions, capped at 16 per class, and notes
   the last C++ HRESULT in `0x20000000..0x2200000E` / `0x8003|0x8004|0x8007` families
   (`src/crash_diagnostics.cpp:27-43`, `:44-49`); it never swallows an exception (`:102`).
2. `src/resman.cpp:37-43` records the last missing/found data path, `src/resolution.cpp:123-140` the current map.

`SetUnhandledExceptionFilter_hook` wraps the game's filter with `CrashReport_Wrap` (`src/system.cpp:31-34`),
so `SlumsTopLevelFilter` runs first, writes `crash_reports\LATEST_CRASH.txt` through a `.tmp` + `MoveFileExA`
rename (`src/crash_report.cpp:144-152`), and then always hands the exception to the game's own filter
(`src/crash_report.cpp:162`). Writing uses static buffers and kernel32 only, because the heap may already be
broken (`src/crash_report.cpp:1-11`). `CrashClassify::Classify` (`src/crash_classify.h:77-99`) maps the facts to
17 causes in a fixed first-match order, and `FormatHeadline` (`:139-150`) adds the module or missing asset only
when it adds information.

**7. Attack diagnostic.** Three detours (`src/attackdiag.cpp:221-223`) decode every remote attack, print an early
`DROP` line *before* calling stock when the packet is truncated or the pose is outside the 162-entry action
table (`src/attackdiag.cpp:124-131`), and then report stock's own verdict from `CUser::CheckAttackAction`.
Nothing about drawing changes (`src/attackdiag.cpp:1-9`).

## Data

| Item | Value | Source |
|---|---|---|
| Attack-info cast site | `0x0067FE33`, 5 bytes `E8 75 54 D8 FF` | `src/mobattackfix_patch.h:69`, `:81-86` |
| Tolerated path | `0x0067FE49` (`lea eax,[ebp-0xA4]`), 6 bytes | `src/mobattackfix_patch.h:71`, `:150` |
| Stock compare / raise | `0x0067FE38` (17 bytes), `0x00A5FDE4` (never patched) | `src/mobattackfix_patch.h:70`, `:72`, `:135` |
| Callee contract | `0x004052AD` clears its destination; epilogue at `0x00405305` | `src/mobattackfix_patch.h:68`, `:160` |
| IID pinned | `IWzProperty {986515D9-0A0B-4929-8B4F-718682177B92}` at `0x00BD8308` | `src/mobattackfix_patch.h:76` |
| MakeObj entry / call / return | `0x0063AD16`, `0x0063ACB5` (`E8 5C 00 00 00`), `0x0063ACBA` (`8B 45 F0`) | `src/mapobj_guard.h:34-42` |
| Guarded HRESULTs | `0x80004003`, `0x80004005`, `0x80030002`, `0x80030003` | `src/mapobj_guard.h:48-51` |
| Melee range entry | `0x00414440`, 10 bytes `B8 3C 78 A7 00 E8 4E C7 64 00` | `src/meleerange_patch.h:34`, `:40-46` |
| Afterimage table | `0x0040AC78`, `0xA2` slots × `0x30`, `0xA20` bytes zeroed | `src/meleerange_patch.h:12-16` |
| C++ throw code | `0xE06D7363` | `src/meleerange_patch.h:37` |
| Attack hook sites | `0x009803AB`, `0x0092EDB2`, `0x00980A81` | `src/attackdiag.cpp:6-9`, `:20-28` |
| Low-memory threshold | `64ull << 20` | `src/crash_classify.h:78` |
| Log files | `integration.log` (DebugMessage), `crash.log`, `attackdiag.log`, `crash_reports\LATEST_CRASH.txt` | `src/crash_diagnostics.cpp:50-51`, `src/attackdiag.cpp:216`, `src/crash_report.cpp:145` |

Every address above is build-specific: MapleStory.exe sha256 `86e4e6be…ac90`, image base `0x400000`
(`src/mobattackfix_patch.h:56`, `src/meleerange_patch.h:24`). All four guard headers pin the *stock bytes* they
expect so a different exe is detected, not corrupted: `Classify()` in `src/mobattackfix_patch.h:99-103`,
`src/meleerange_patch.h:51-55`, `src/mapobj_guard.h:35-42` (tested, not runtime-checked).

## Switches

| Key | Default | Read at | Effect |
|---|---|---|---|
| `[clienthax] mobattackfix` | `true` (absent key ⇒ on) | `src/mobattackfix.cpp:60-64` (`GetPrivateProfileStringA`, `ParseIniBool(value).value_or(true)`) | `false` keeps the stock `E_POINTER` throw: the client closes on the affected map (`config.example.ini:40-46`) |
| `[clienthax] mapobjguard` | `true` | `src/lamps.cpp:1498-1506` (read once per process, same `value_or(true)`) | `false` = rethrow every failed map object (`config.example.ini:52-56`) |
| `[debug] attackdiag` | `0` (absent ⇒ off) | `src/attackdiag.cpp:212` (`GetPrivateProfileIntA`) | `1` = dropped attacks, `2` = every remote attack; `<= 0` returns before any hook (`:213-215`). **Not present in `config.example.ini`** — documented only in `src/attackdiag.cpp:1-4` and `docs/research-126-midair.md:65` |
| build option `MAPLEROOT_ATTACK_DIAG` | `OFF` | `CMakeLists.txt:12`, `src/CMakeLists.txt:110-112`; on in `scripts/build.ps1:11` | absent ⇒ `AttachAttackDiagMod` is not compiled |
| build option `MAPLEROOT_WEATHER` | `OFF` | `CMakeLists.txt:5`, `src/CMakeLists.txt:100-105`; on in `scripts/build.ps1:11` | absent ⇒ **map-object guard does not exist** (it lives in `lamps.cpp`) |
| build option `MAPLEROOT_CRASH_REPORT` | `OFF` | `CMakeLists.txt:17`, `src/CMakeLists.txt:76-79`; **not set by `scripts/build.ps1:11`** | absent ⇒ no report writer, no resource note hooks |
| build option `MAPLEROOT_INTEGRATION` | `OFF` | `CMakeLists.txt:6`, `src/CMakeLists.txt:117-124`; on in `scripts/build.ps1:11` | gates `crash_diagnostics.cpp` (VEH/`crash.log`) and `VerifyKaentakeHooks` |

No key falls back to a legacy name: each of the two `[clienthax]` keys is read directly from `config.ini`
(`src/constants.h:5` → `".\\" CONSTANTS_CONFIG_NAME`). `inlink_guard` and `meleerange` have **no** switch — they
are unconditional in every build.

## Invariants

- **A guard never changes the success path.** The mob cave reproduces the stock frame exactly and ends `ret 4`
  (`src/mobattackfix.cpp:43-57`); the map-object hook calls stock unchanged and only converts a caught throw
  (`src/lamps.cpp:1608-1615`); the inlink guard fills the canvas the way stock expects when the link resolves
  (`src/inlink_guard.cpp:77-84`); the melee hook returns stock's rectangle whenever stock does not throw.
- **A guard never swallows an exception it was not written for.** Mob: only `80004003` is redirected
  (`src/mobattackfix_patch.h:109-111`), verified over `0x80004000..0x80004005`
  (`tests/mobattackfix_patch_test.cpp:56-90`). Map object: four HRESULTs (`src/mapobj_guard.h:59-69`), everything
  else `throw;` (`src/lamps.cpp:1615`). Melee: only `0xE06D7363` (`src/meleerange.cpp:34-36`), everything else
  `EXCEPTION_CONTINUE_SEARCH`. Inlink: only `_com_error` (`src/inlink_guard.cpp:86`).
- **Byte-check before patch.** Both patchers classify the site first and refuse on `Unknown`
  (`src/mobattackfix.cpp:74-84`, `src/meleerange.cpp:64-69`); neither writes over a site it does not recognise.
- **Failures are attributed.** Every refusal and every skip is one `DebugMessage` line naming the site, the
  HRESULT or the WZ path (`src/mobattackfix.cpp:77`, `:80`, `src/lamps.cpp:1531-1532`,
  `src/inlink_guard.cpp:57`, `:62`, `:87`, `src/meleerange.cpp:54`), with a 20-line cap on the melee path
  (`src/meleerange.cpp:19`) so a repeating bot pose cannot flood the log.
- **The crash path may not allocate.** `crash_report.cpp` uses static buffers and kernel32 only
  (`src/crash_report.cpp:1-11`), and always forwards to the game's filter (`:162`).
- **Report writing is atomic.** `.tmp` → `FlushFileBuffers` → `MoveFileExA(MOVEFILE_REPLACE_EXISTING)`
  (`src/crash_report.cpp:150-152`); a half-written report is impossible.

| Guard | Crash class it prevents | How it fails safe |
|---|---|---|
| `mobattackfix` | `_com_error E_POINTER (0x80004003)` raised at `0x0067FE44` from the lazy mob attack-info builder; uncaught ⇒ process exit (b251) | routes one call to the stock `E_NOINTERFACE` path; the attack record is left null and every caller tests null (`src/mobattackfix_patch.h:38-42`). Byte mismatch ⇒ no patch, stock throw kept |
| `mapobj_guard` (+ `lamps.cpp`) | `_com_error` (`E_POINTER`/`E_FAIL`/`STG_E_*`) out of `CMapLoadable::MakeObj` during map load; uncaught ⇒ client closes on entry (272000000) | returns `nullptr` for that one object; `MakeObj`'s caller ignores `eax` (`8B 45 F0`), so the layer is one object short. Other HRESULTs rethrown; switch off = stock |
| `inlink_guard` | `_com_error E_POINTER` from a linked minimap canvas with no `origin` (b103, rc12), and any failed link lookup, out of map load | null-checked `origin`, per-step `continue`, one `catch` that logs and returns `false`; canvas left exactly as it was |
| `meleerange` | C++ throw (`_ZtlSecureFuse<long>` on an uninitialised afterimage slot) out of `CUserRemote::OnMeleeAttack` packet processing ⇒ session ends silently | `__try` around stock, `SetRectEmpty` and one log line; the attack is drawn without a range |
| `checked_hook` | a Detours transaction left open by a failed ATTACH, which the next hook call would inherit — silent target corruption or a crash on shutdown | every phase returns `{error, phase}`, `abort` on mid-phase error, `commit` always; failure increments a counter that aborts start-up under `MAPLEROOT_INTEGRATION` |
| `crash_classify`/`crash_report` | no crash is prevented — the crash becomes a readable `LATEST_CRASH.txt` instead of a bare exit | static buffers, `__try`/`__except` around the writer, always hands the exception on (`src/crash_report.cpp:155-163`) |
| `attackdiag` | none — read-only by design (`src/attackdiag.cpp:4`) | off unless a key is set; each hook calls stock unchanged |

## Origin

| Part | Origin | Evidence |
|---|---|---|
| `src/mobattackfix.cpp`, `src/mobattackfix_patch.h`, `tests/mobattackfix_patch_test.cpp` | **custom** | single commit `3e640f1 2026-11-04 Rose Garden 211080100 no longer closes the client (b251): mob attack-info guard`; the chain was traced against the shipped exe, not copied (`src/mobattackfix_patch.h:8-50`). `docs/KAENTAKE-UPSTREAM.md` and `docs/PROJECT-STATUS.md` mention neither b251 nor mob attack info (greps empty) |
| `src/mapobj_guard.h` + the `MakeObj_hook` in `src/lamps.cpp` | **custom** (guard); **modified** (lamps) | `a1e48df 2026-11-04 Map load: a missing map object costs one object, not the client (272000000)` adds the header and the hook; `lamps.cpp` itself is repo-authored weather code (`src/CMakeLists.txt:100-105`) |
| `src/inlink_guard.h`, `src/inlink_guard.cpp` | **custom** on a **base** hook | `1812c10 2026-03-19 Add checks for linked canvases in inlink.cpp` is the Kaentake-era ancestor; `74068b3 2026-10-30` (b103 origin guard) and `e2be634 2026-10-31` split it into `inlink_guard.cpp` with the never-throw contract (`src/inlink_guard.h:5-12`). `src/inlink.cpp` is base Kaentake code (`docs/PROVENANCE.md:3`) |
| `src/meleerange.cpp`, `src/meleerange_patch.h` | **custom** | `20f29c1 2026-10-25 Keep the client open when a remote melee attack has no range for its pose`; the header carries the full afterimage-table analysis (`src/meleerange_patch.h:8-18`) |
| `src/attackdiag.cpp`, `src/attackdiag_rules.h` | **custom** | `34c1c28 2026-10-21 Log the other characters' attacks the client drops, with the reason` (ticket 32) |
| `src/checked_hook.h` | **modified from the MapleRoot companion's pattern** | arrives with `6119130 2026-10-18 Checkpoint accepted MapleRoot client integration and addons`; consumed by `src/hook.cpp:3` and `mapleroot/ijl15/Funcs.cpp:3`, so it is the shared transaction wrapper for both the Kaentake hooks and the companion's IJL15 hooks |
| `src/crash_classify.h`, `src/crash_report.*`, `src/crash_scrub.h`, `src/crash_diagnostics.cpp` | **custom** | `3e77cc2 / 16521d2 / 20a09b9` all `2026-10-28` (ticket 121), designed in `docs/research-121-crash-report.md`; the classifier is header-only and Windows-free so it can be tested natively (`src/crash_classify.h:1-7`) |
| Data-side closures of the same cards | **custom** | `68caea7` + `117d43b` (Custom.wz `Mob/<id>.img/attackN/info/type`, `tools/wz/add_mob_attack_type.py:1-29`), `210fb6c` (acc10 `timeTemple/pillar_old`) |

## Tests and QA

| ctest name | Wiring | Covers |
|---|---|---|
| `checked_hook_transactions` | `tests/CMakeLists.txt:9-10` | every phase of `ChangeHook` returns `{123, phase}`, exact ops sequence, null-argument rejection (`tests/checked_hook_test.cpp:17-42`) |
| `mobattackfix_wz_property_epointer` | `tests/CMakeLists.txt:59-65` (exe from `MOBATTACKFIX_TEST_EXE`, default `Z:/MSv83/SlumsMS-dev/client-1.5.0-fixes2/exe-copy/MapleStory.exe`) | patch shape, decision table, and — with the exe — the stock bytes, the callee's `eax`-ignored contract, the IID, the action-table index and the cache load (`tests/mobattackfix_patch_test.cpp:34-148`) |
| `mapobj_guard_skips_missing_art` | `tests/CMakeLists.txt:172-178` (`MAPOBJ_TEST_EXE`) | four skips, three propagates, `Describe`/`ReasonLabel`, `call+5 == return`, and the PE bytes with the exe (`tests/mapobj_guard_test.cpp:57-111`) |
| `meleerange_guard` | `tests/CMakeLists.txt:50-56` | entry-byte classify, detour/unknown handling, and with the exe the `0xA2`/`0xA20` afterimage facts (`tests/meleerange_patch_test.cpp:28-85`) |
| `inlink_broken_link_no_crash` | `tests/CMakeLists.txt:110-114`, only when `MOVEMENTKEYS_TEST_CLIENT` has `Data/Map/Tile/allInvisibleTile.img` | no-link, real link pixel copy, the b103 no-origin shape, proof that a missing image throws (the pre-fix hazard), broken-link table (`tests/inlink_guard_test.cpp:99-168`) |
| `crash_report_classify_scrub` | `tests/CMakeLists.txt:169-170` | 15 classification cases plus the module-class check, `FormatHeadline` strings, advice, scrub rules (`tests/crash_report_test.cpp:32-69`) |
| `attackdiag_remote_attack_rules` | `tests/CMakeLists.txt:145-147` under `MAPLEROOT_ATTACK_DIAG` | the drop-reason rules in `attackdiag_rules.h` (`tests/attackdiag_rules_test.cpp:35+`) |

Build-script checks: `scripts/build.ps1:13-18` configures, builds and runs the whole ctest suite with
`--output-on-failure`; a single failing test fails the build. CI runs the same script on `windows-2022`
(`.github/workflows/build.yml`). `README.md:20` states the split honestly: without external `Data` only the
five tests that need no game assets are registered, so `inlink_broken_link_no_crash` and the three exe-backed
byte tests silently degrade to their no-exe variants (or disappear, for inlink).

**What a real client must confirm by hand** (none of this is covered by ctest):

1. b251: enter 211080100, let the Rose Garden mobs attack repeatedly, change channel and re-enter
   (`/root/work/SlumsMS-164-client-map-ticket/docs/tickets/181-client-map-integrity-and-crashes.md:239-241`); also 211080700, which the client's
   `String.wz` has no name for.
2. Map objects: 272000000 and the eight maps that place `timeTemple/pillar_old` (`210fb6c`), plus whichever map
   still lacks art — confirm the map loads, the console has the `map obj: skipped` line, and no half-built object
   is visible.
3. The attack that is now tolerated must not become a *wrong* attack: b251's rejected hypothesis list
   (`/root/work/SlumsMS-164-client-map-ticket/docs/tickets/181-client-map-integrity-and-crashes.md:49`) is why the fix was required to show the valid-layer
   case, and the fix leaves the entry null rather than guessing a range.
4. `[clienthax] mobattackfix=false` / `mapobjguard=false` must still reproduce the stock crash (fail-before
   evidence, `/root/work/SlumsMS-164-client-map-ticket/docs/tickets/181-client-map-integrity-and-crashes.md:239`).
5. Melee: two clients on one map, remote player attacking in a pose with no afterimage, for longer than the
   20-line cap.

## Known issues

- **b251 (S2, `/root/work/STATUS-1.6.4.md:72`)** — still listed open: "Client crash entering Rose Garden: Abandoned Garden
  (211080100) … Routing stays". Two independent fixes landed on 2026-11-04: the data fix `68caea7` /
  `117d43b` (Custom.wz `attackN/info/type`) and the code guard `3e640f1`. The server still blocks the map (server repo paths below):
  `src/main/resources/soloMapling/map-routing.tsv:93-99` marks 211080100-700 `client-crash`, and
  `Probes164.mapRouting` asserts they fall back to 211080000 (`src/main/java/soloMapling/autoqa/Probes164.java:462-467`).
  The card cannot close until that table is regenerated and a QA build proves the map.
- **b270 (S3, `/root/work/STATUS-1.6.4.md:75`)**, **b274 (S3, `:76`)**, **L15 (`:106`)**, **e14 (`:124`)** — client-lane
  cards unrelated to these guards; listed here only because the card set is the client lane.
- **NEW-FINDING: `src/crash_report.cpp:167`** — `InstallCrashReport(){}` is an empty no-op with no caller; the
  declared API in `src/crash_report.h:8` implies installation that does not exist. The filter is installed
  through `CrashReport_Wrap` in `src/system.cpp:33` instead, so the function is dead code that suggests a
  second wiring path.
- **NEW-FINDING: `src/crash_scrub.h:5`** — the header says "tests/crash_scrub_test.cpp checks it"; there is no
  such file (`tests/CMakeLists.txt` registers `crash_report_classify_scrub` against `crash_report_test.cpp`). The
  scrub rules *are* tested, inside `tests/crash_report_test.cpp:25`, `:69`, so this is a stale pointer, not a
  coverage hole.
- **NEW-FINDING: `src/crash_diagnostics.cpp:37-38`, `:67-68`** — the "last C++ error" note accepts only
  `0x20000000..0x2200000E` and high words `0x8003`/`0x8004`/`0x8007`. Both crash classes this document exists
  for are plain `0x8000`-high-word HRESULTs — `E_POINTER 0x80004003` (b251, b103) and `E_FAIL 0x80004005` — so
  `Last C++ error:` in the report (`src/crash_report.cpp:139-141`) reads `00000000` for exactly the failures that
  matter most here. (`0x80004003 & 0xFFFF0000 == 0x80000000`.) The `ResumeFor`/`StockRaises` pair in
  `src/mobattackfix_patch.h:109-117` shows how precisely these codes are otherwise handled.
- **NEW-FINDING: `src/mapobj_guard.h:22-24` / `src/lamps.cpp:1496-1497`** — the map-object guard is compiled
  only inside `MAPLEROOT_WEATHER` (`src/CMakeLists.txt:100-105`). A build without the weather addon — the
  documented way to configure a minimal client (`CMakeLists.txt:5`) — silently ships **without** the
  missing-art guard, while `/root/work/client/src-164-data/README.md:125-131` presents it as a client behaviour.
- **NEW-FINDING: `src/hook.h:282-283`** — `AttachMobAttackFixMod()` is the only guard install without a
  `VerifyKaentakeHooks` call; its `PatchMemory` result is never checked, so a `VirtualProtect` failure leaves the
  client unpatched with only a log line.
- **NEW-FINDING: `src/attackdiag.cpp:212`** — `[debug] attackdiag` is absent from `config.example.ini`, so the
  only shipped diagnostic for the attack path is undiscoverable to a player or QA lead reading the config file.
- **NEW-FINDING: `src/crash_classify.h:86`** — `GraphicsDriver` requires `ExceptionCode == 0xC0000005`; a driver
  crash reported as `0xC0000096`, `0xC000001D` or `0xC00000FD` falls through to `InvalidCode`/`StackOverflow`
  even when the faulting module is `nvwgf2um`/`d3d9`. The module class is computed but not used for those codes.
- The 44 mobs whose attacks have no `range` node stay unbuildable by the v83 builder (`117d43b` commit body,
  `tools/wz/add_mob_attack_type.py:28-29`); the mobattackfix cave tolerates that failure rather than fixing the
  data, so those attacks still fire no attack-info entry.
- Crash-report collection is **off in shipped builds** (`scripts/build.ps1:11` omits `MAPLEROOT_CRASH_REPORT`),
  so `crash_reports/LATEST_CRASH.txt` — the file `src/mapobj_guard.h:15` cites as its QA evidence — will not be
  produced by any release binary as configured today.

## State

**working** for the five guards, **partial** for the crash report, **stale** for one header comment. Evidence:

| Part | State | Evidence |
|---|---|---|
| `mobattackfix` | **working** for the site it patches; **partial** as a b251 closure | byte-checked, decision table covers the whole HRESULT neighbourhood, and the branch it redirects to is proven to be a state callers handle (`src/mobattackfix_patch.h:38-42`); but no real-client proof is recorded, the server still blocks 211080100 (`map-routing.tsv:93`), and `3e640f1`'s own fix was shown insufficient on its first QA run (`68caea7` body: "the 3e640f1 cave did not catch it") |
| `mapobj_guard` | **working** for 272000000's `acc10` case | QA trace 2026-11-04 07:38:30Z recorded in `src/mapobj_guard.h:15`, `a1e48df` message; `timeTemple/crack` still has no stock sibling and stays skipped by design (`210fb6c` body) |
| `inlink_guard` | **working** | native test proves the pre-fix throw and the guarded behaviour (`tests/inlink_guard_test.cpp:138-168`); b103 was a live rc12 crash (`src/inlink_guard.h:8-12`) |
| `meleerange` | **working** | reproduced twice on TEST 2026-10-26 with a `crash.log` stack (`src/meleerange_patch.h:8-18`); the guard converts the throw to an empty rect |
| `checked_hook` | **working** | every phase unit-tested (`tests/checked_hook_test.cpp:17-42`); used by both `src/hook.cpp` and the companion |
| `crash_report` | **partial** | classifier/scrub tested, writer correct but `MAPLEROOT_CRASH_REPORT` is off in the shipped configuration (`scripts/build.ps1:11`), and `InstallCrashReport` is dead (`src/crash_report.cpp:167`) |
| `attackdiag` | **working** as a diagnostic, no switch discoverability | rules tested (`tests/attackdiag_rules_test.cpp:35+`), three hooks attach only when enabled (`src/attackdiag.cpp:212-223`) |

## Open questions

- Did the 211080100 QA pass that motivated `68caea7` include a *client* run after the cave, or only the trace?
  The commit body reports the cave failing on `wztrace-20261104T072629Z.log`, and no later QA note for the map
  is in `docs/` — `docs/evidence/` holds four JSONs (cashshop, mount info, movement keys, weather) and no
  guard evidence.
- With `210fb6c`'s data fix in Custom.wz, is the `mobattackfix` cave still reachable for 211080100, or does it now
  only cover the 44 no-range mobs (`117d43b` body)? Nothing records an attempt to disable the switch and retest
  the map — which is the cleanest way to answer it.
- Whether the v83 client's own top-level filter (`0x00796FDD` region, `docs/research-121-crash-report.md:322`)
  would ever handle a `_com_error`; `docs/research-121-crash-report.md:323` lists this as unverified, and it
  decides whether an unguarded `_com_error` is a dialog or a CRT abort (exit 3).
- Whether `CrashReport_NoteMissing` (`src/crash_report.cpp:179-184`) ever sees a non-ASCII path: it stores `'?'`
  per non-ASCII character into a 96-byte ASCII buffer, so a long Korean path could collide two different assets
  in the report.
- The 51 broken-link maps recorded in `/root/work/SlumsMS-164-client-map-ticket/docs/tickets/181-client-map-integrity-and-crashes.md:241`
  (100020549-100020599) have no repair-or-exclusion evidence; the inlink guard makes them survivable, but nothing
  records whether they were re-visited after `e2be634`.
