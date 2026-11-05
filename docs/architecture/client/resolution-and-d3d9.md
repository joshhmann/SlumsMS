# Resolution and the Direct3D 9 host

## Purpose

Four features that decide what the window and the frame buffer look like, all in `slums.dll`:

- **Resolution switch** (`src/resolution.cpp`, 810 lines). The stock v83 client is hard-wired to 800x600: the window manager, every control origin vector, the view range, the weather canvases and the limited-view overlay all assume it. `src/resolution.cpp` adds a five-entry resolution combobox to the stock System Options window and then re-centres or rescales the ~40 sites inside `MapleStory.exe` and `Gr2D_DX8.dll` that baked 800/600 in (`src/resolution.cpp:29-31`, `:657-710`, `:713-810`).
- **Direct3D 9 host** (`src/d3d9host.cpp`, `src/d3d9host_policy.h`). The client renders through d3d8to9 (`d3d8.dll` imports `d3d9!Direct3DCreate9`). The host detours that export, logs every adapter and every failing adapter query to `integration.log`, retries `CreateDevice` on other adapters and then with software vertex processing, and hosts a Dear ImGui 1.91.4 overlay on `EndScene`/`Reset` (`src/d3d9host.cpp:1-17`). Its stated driver is the unexplained start-up failure `-2147467259` (E_FAIL) seen when a Parsec virtual display is the primary monitor (`src/d3d9host_policy.h:5-11`).
- **Borderless / DPI-aware window** (`src/system.cpp`, `src/window_policy.h`). `[graphics] borderless` replaces the frame with `WS_POPUP` and keeps the window exactly client-sized and centred; `[graphics] dpiaware` calls `SetProcessDPIAware` before any window exists (`src/system.cpp:305-310`). Header rationale: "fake fullscreen … Nothing is scaled" (`src/window_policy.h:5-8`).
- **Display toggles** (`src/display.cpp`, `src/display_policy.h`). One live toggle, "hide other players' cash and set effects", applied by detouring `CUser::LoadLayer` (`src/display.cpp:71-79`). The category/owner policy header is otherwise a scaffold: `src/display_policy.h:3` says "SCAFFOLD", `:5-9` says the capture hooks come next.

`src/ui_wheel.h` does **not** belong to this subsystem — it is cash-shop input (`src/cashshopwnd.cpp:1592`); it is listed here only because the assignment named it (see "Data").

There is no wire contract: nothing in these files sends or reads a packet. The server side of the client identity (build stamp) is a different subsystem.

## Entry points

| Entry | What starts it | File:line |
|---|---|---|
| `DllMain` → `AttachSystemHooks()` | every injection | `src/injector.cpp:44-53` |
| `AttachResolutionMod()` | called once from `AttachClientHooks()`, unguarded (source is always compiled) | `src/hook.h:191-192`; body `src/resolution.cpp:713` |
| `AttachDisplayMod()` | `#ifdef MAPLEROOT_DISPLAY_TOGGLES` | `src/hook.h:254-255`; body `src/display.cpp:92` |
| `AttachD3D9Host()` | `AttachSystemHooks()`, "before the game creates its Direct3D objects" | `src/system.cpp:322`; body `src/d3d9host.cpp:296` |
| `g_bBorderless` / `SetProcessDPIAware()` | `AttachSystemHooks()` | `src/system.cpp:306`, `:307-310` |
| `set_stage_hook` (0x00777347) | the game changes stage — the point where the resolution is (re)applied | `src/resolution.cpp:121`, `:133-148` |
| `CUISysOpt::OnCreate_hook` (0x00994163) | player opens System Options; builds the resolution combobox | `src/resolution.cpp:201-223` |
| `CConfig::ApplySysOpt_hook` (0x0049EA33) | player presses OK in System Options | `src/resolution.cpp:187-192` |
| `WndProc_hook` → `D3D9Host_HandleMessage` | every window message; Ctrl+F10 / Ctrl+F11 / Esc | `src/system.cpp:93-95`; `src/d3d9host.cpp:269` |
| `Direct3DCreate9_hook` | the game's first IDirect3D9 creation | `src/d3d9host.cpp:260-265` |
| `EndScene_hook` / `Reset_hook` (device slots 42 / 16) | every frame / every device reset | `src/d3d9host.cpp:184-200`, `:202-212` |
| `CUser_LoadLayer_hook` (0x00941417) | a character loads an effect layer | `src/display.cpp:71-79` |
| `Display_DrawSettings()` | the "Display" rows of the Ctrl+F10 window | `src/overlay.cpp:380-382` |

## How it works

1. **Bootstrap order.** `DllMain` runs `ProcessCommandLine()`, `ProcessConfigFile()`, then `AttachSystemHooks()` (`src/injector.cpp:44-53`). `AttachSystemHooks` reads `[graphics] borderless` into `g_bBorderless` and `[graphics] dpiaware` into `SetProcessDPIAware()` (both before any window exists), installs the crash/MessageBox/mutex/`CreateWindowExA`/registry/`WSPStartup` hooks, and last calls `AttachD3D9Host()` (`src/system.cpp:305-323`). `AttachClientHooks()` — reached from `SetUnhandledExceptionFilter_hook` when `_ReturnAddress()` is `0x00796FDD` — installs `AttachResolutionMod()` (`src/hook.h:191-192`) and, when the option is on, `AttachDisplayMod()` (`src/hook.h:254-255`).

2. **The host attaches to the D3D9 import, not to the game.** `AttachD3D9Host` refuses when `[graphics] d3d9host` is false (`src/d3d9host.cpp:297-300`), reads `[graphics] adapterfallback` (default true) into `g_bFallback` and `[clienthax] settingswindow` (default true) into `g_bSettingsWindow` (`:301-302`), arms the Edit UI tour (`:303`), resolves `Direct3DCreate9` from `GetModuleHandleA("d3d9.dll")` + `GetProcAddress` (`:304-309`) and detours it (`:310-313`). `d3d9.dll` is guaranteed to be loaded at `DllMain` time only because of the forced import at `src/d3d9host.cpp:35-37` (`g_pForceD3D9Import = &Direct3DCreate9`).

3. **First IDirect3D9 → full adapter survey.** `Direct3DCreate9_hook` logs the result and calls `HookDirect3D9` (`src/d3d9host.cpp:260-265`), which caches vtable slots 8/9/10/14/16 (`GetAdapterDisplayMode`, `CheckDeviceType`, `CheckDeviceFormat`, `GetDeviceCaps`, `CreateDevice`), calls `LogAdapters` through the originals **before** hooking (`:252`), then hooks all five (`:253-257`). `LogAdapters` writes one line per adapter: name, display mode, HAL caps, `[primary]` flag from `GetAdapterMonitor`/`GetMonitorInfoA` (`src/d3d9host.cpp:125-141`). Each failing hook call is logged once per (call, adapter, HRESULT) triple by `LogFailureOnce` (`:88`, used at `:99`, `:106`, `:113`, `:119`); `CheckDeviceFormat` skips `D3DERR_NOTAVAILABLE` because probing is normal (`:113`).

4. **CreateDevice fallback.** `CreateDevice_hook` copies the asked parameters, logs them (adapter, type, focus window, behaviour flags, back-buffer size, windowed, format) and calls the original (`src/d3d9host.cpp:215-224`). On failure with `g_bFallback` set it walks `D3D9Host::FallbackAttempts(adapter, GetAdapterCount(), behavior)` (`:226`), retrying each attempt and logging its HRESULT (`:228-230`); on the first success it restores `*pp` and stops (`:231-235`). The order is defined in the header-only policy: other adapters with the *same* flags first, then the requested adapter with software vertex processing, then the remaining adapters with software vertex processing (`src/d3d9host_policy.h:34-47`), where `WithSoftwareVP` clears `D3DCREATE_HARDWARE_VERTEXPROCESSING`/`MIXED` and sets `SOFTWARE` (`:29-31`).

5. **Overlay on the created device.** On success `HookDevice` stores the device and window, caches slots 16/42 (`Reset`, `EndScene`), detours both and records `g_bDeviceHooked` (`src/d3d9host.cpp:202-212`). Nothing is allocated until the first draw: `InitImGui` runs lazily inside `DrawOverlay` (`:160-182`), sets `io.IniFilename = nullptr` so no `imgui.ini` appears next to the game (`:150`), and initialises Win32 + DX9 backends (`:154-155`). `DrawOverlay` early-outs unless the HUD window is visible, a HUD widget is on, Edit UI is unfrozen or the tour wants it (`:163`); it rebuilds the font and invalidates the device objects when the rebake happens (`:169`); on any exception it closes the overlay and logs once (`:178-181`). `Reset_hook` invalidates the device objects before the reset and recreates them after, logging any failed reset with size/windowed/HRESULT (`:189-200`).

6. **Input arbitration.** `WndProc_hook` gives every message to `D3D9Host_HandleMessage` first and swallows it when that returns true (`src/system.cpp:93-95`). The handler requires `g_bDeviceHooked && g_bSettingsWindow` (`src/d3d9host.cpp:270`), handles Ctrl+F10 (F10 arrives as `WM_SYSKEYDOWN`; `bCtrlFresh` requires the key to be a new press) to toggle the window, Ctrl+F11 to freeze the HUD, Esc to close the window or replay the tour (`:271-286`), always forwards to `ImGui_ImplWin32_WndProcHandler` (`:289`), and then withholds only mouse messages when `io.WantCaptureMouse` and key messages when `io.WantCaptureKeyboard` (`:291-292`).

7. **Resolution is applied at stage transitions, not in the options dialog.** `ApplySysOpt_hook` calls `set_screen_resolution(g_cbResolution->m_nSelect, true)` when the player applies video options (`src/resolution.cpp:187-192`). For a field load, `set_stage_hook` calls `set_screen_resolution(g_nResolution, 0)` **before** `set_stage` when the incoming stage is a `CField` (RTTI `0x00BED758`) (`:136-137`); for every stage that is not a `CInterStage` (RTTI `0x00BED874`) it calls `set_stage` first and then resets to index 0 / 800x600 (`:143-146`).

8. **What one resolution change does.** `set_screen_resolution` maps index → size (0 = 800x600 default, 1 = 1024x768, 2 = 1366x768, 3 = 1600x900, 4 = 1920x1080) (`src/resolution.cpp:657-677`). If the size actually changed it calls `CWzGr2D::ScreenResolution(w,h)` and gives up on any failure (`:678-682`), then in order: stores the new globals (`:683-684`); calls `Borderless_Apply()` because "the game's resize put its frame back" (`:685`); under `MAPLEROOT_INTEGRATION` calls `UpdateMapleRootResolution` and logs `SESSION_RESOLUTION width=%d height=%d` (`:686-689`); sets `g_nAdjustCenterY = (h - 600) / 2` (`:690`); re-runs `CWndMan::ResetOrgWindow()` and re-centres the quest dialog (`:691-696`); repositions the buff icons (`:697-699`); and rebuilds the field view range plus `CMapLoadable::ReloadBack` at `0x00644491` (`:700-705`). Only then does `bSave` write `g_nResolution` (`:707-709`).

9. **`CWzGr2D::ScreenResolution` is reimplemented, not called.** The client cannot call the real one: `ScreenResolution` returns `E_INVALIDARG` for a zero size, `S_OK` when the size is unchanged, `E_FAIL` when the object is not initialised or `FindScreenMode` was not resolved, and otherwise stores the mode and sets `m_hrErrorCode = 0x88760869` (D3DERR_DEVICENOTRESET) so the game recreates its device (`src/resolution.cpp:626-639`). `FindScreenMode` is found by pattern in `GR2D_DX8.DLL` (`:807`).

10. **Origin vectors carry the layout.** Every stock window is positioned from one of nine origin vectors. `CWndMan::Constructor_hook` creates them plus `ms_pOrgStatusBar`, `ms_pOrgScreenMsg`, `ms_pOrgQuickSlot` (`src/resolution.cpp:256-265`) and `ResetOrgWindow` places them: `m_pOrgWindow` at the screen centre and, per origin `i`, `nX = -w/2 + (i%3==1 ? (w-800)/2 : i%3==2 ? (w-800) : 0)`, `nY = -h/2 - adjust_cy + (i/3==1 ? (h-600)/2 : i/3==2 ? (h-600) : 0)` (`src/wvs/wndman.h:33-51`). With `CONSTANTS_CENTER_STATUSBAR` false (the shipped value, `src/constants.h:7`) the status bar and quick slot hang off `Origin_LB`, screen messages off `Origin_RB`, and above 800 px wide the screen message moves up 10 px and the quick slot by `(152,68)` (`src/wvs/wndman.h:57-71`).

11. **Caller-identity dispatch.** The stock client asks for an origin through one function that has no arguments distinguishing the caller, so both hooks switch on `_ReturnAddress()`: `GetOrgWindow_hook` maps 10 call sites plus two address ranges to specific origins (`src/resolution.cpp:277-314`), and `CWnd::CreateWnd_hook` maps 15 call sites so that clocks, dialogs, skill effects, revive and channel-shift windows are created on the right origin (`:316-347`). Unknown callers fall back to `m_pOrgWindow`.

12. **Geometry that is clamped or rescaled.** Cursor: `SetCursorVectorPos_hook` re-centres the vector cursor by `(-w/2, -h/2-adjust_cy)` and `SetCursorPos_hook` clamps to the screen before `ClientToScreen` (`src/resolution.cpp:243-253`). Windows: `CWnd__OnMoveWnd_hook` reimplements dragging with 10 px snapping to sibling windows and screen edges and a 15 px cursor threshold (`:359-426`). Dialogs: `CreateUtilDlgEx_hook` centres on `sRectQuestDlg` and clamps (`:438-443`); context menus are placed at the cursor with height `15*(m_nBtNumber+2)` and clamped (`:474-486`). View: `RestoreViewRange_hook` reads `VRLeft/VRTop/VRRight/VRBottom` from the field info, defaults to `m_rcMBR ±20/60/190`, insets by `w/2`, `h/2`, adds `adjust_cy`, and collapses to the midpoint when inverted (`:529-547`). Canvases: the weather wrap-clip and the limited-view copy hooks shift the 800x600 origin to 1920x1080 (`:561-579`); screen messages widen to `SCREEN_MESSAGE_WIDTH` 400 and drop 78 px when the quick slot is up (`:582-596`).

13. **Byte patches in the exe.** `AttachResolutionMod` writes the values the reimplementations cannot reach: System Options ok/cancel shift 372 (`src/resolution.cpp:722`), `CWvsApp::CreateWndManager` 1920/1080 (`:723-724`), the tooltip bounds check becomes `mov eax,[g_nScreenWidth]`/`[g_nScreenHeight]` (`:741-744`), `CUIStatusBar` `0x008CFD50` width 1920 (`:755`), 13 weather-layer immediates plus a 6-byte call hook (`:763-776`), six limited-view immediates plus three call hooks (`:779-787`), four screen-message sites (`:790-793`), three slide-notice widths (`:796-798`), a 15-byte call at `0x00533705` for the boss-HP tag (`:801`), `CUIEquip::m_pUIPetEquip` offset `0x5B4` (`:804`), and a `PatchJmp` on `Gr2D_DX8.DLL`'s centre-Y arithmetic (`:808-810`). Every patch site is a fixed VA or a module pattern; nothing is self-verifying (see Known issues).

14. **Borderless keeps the frame off after every resize.** `CreateWindowExA_hook` only touches class `MapleStoryClass`; it rewrites the title with the release/build stamp, uses `g_bBorderless ? WS_POPUP : 0xCA0000`, and installs `WndProc_hook` (`src/system.cpp:179-194`). `Borderless_Apply` is called both from the creation hook and from `set_screen_resolution`, strips `kFrameBits` (`WS_CAPTION|WS_THICKFRAME|WS_SYSMENU|WS_MINIMIZEBOX|WS_MAXIMIZEBOX`, `:63`), forces `WS_POPUP`, sizes the window to the game resolution with `SWP_FRAMECHANGED`, then centres it (`:198-207`). In the message hook, `WM_STYLECHANGING` strips the frame bits and forces `WS_POPUP` again (`:99-104`), `WM_WINDOWPOSCHANGING` forces `cx/cy` to the game resolution unless the flags say no-size, the window is minimising, or `wp->x <= -32000` (`:105-113`), and `WM_WINDOWPOSCHANGED` re-centres through `PlaceBorderless` (`:114-120`). `PlaceBorderless` asks `WindowPolicy::Place` for a centred position on the default monitor and moves only when the position differs (`:75-90`); `Place` centres and pins an oversized window to the monitor's top-left (`src/window_policy.h:20-24`). The same hook owns the frameless chrome: dragging on `HTCAPTION` (`:143-156`), close on `HTCLOSE` via `PostQuitMessage(0)` (`:157-160`), minimise on `HTMINBUTTON` unless Ctrl (`:161-162`), and swallowed right-button drags (`:167-174`).

15. **The display toggle acts at layer load, and never keeps the layer.** `AttachDisplayMod` first compares the ten stock bytes at `0x00941417` (`src/display.cpp:35`) and stays off when they differ, logging "Display: CUser::LoadLayer is not the stock code; display toggles stay off" (`:92-95`). The hook computes whether the UOL is a cash/set effect (`Effect/ItemEff.img` or `Effect/SetEff.img`, `:63-65`), calls the original, and — only for a layer that loaded, on a character that is not `*0x00BEBF98` — reads the registry pref once and sets `layer->visible` (`:71-79`, `:55-61`). It deliberately stores no reference: keeping one left characters drawn headless until the client closed (b250, `:54`). The pref is `HKCU\Software\SlumsMS\Overlay\hide_others_cash` (`:36`, `:41-52`).

16. **The companion also needs the size.** Under `MAPLEROOT_INTEGRATION`, `set_screen_resolution` calls `UpdateMapleRootResolution` → `MapleRoot_SetResolution` in `ijl15.dll` (`src/mapleroot.cpp:63-65`), which rejects anything outside 800x600..1920x1080 and rewrites four skill-notice immediates with `width - 100` (`mapleroot/ijl15/DllMain.cpp:180-189`). Startup goes the other way: `InitializeMapleRoot` checks the bridge version `0x00010001` and passes `get_screen_width()/get_screen_height()` in (`src/mapleroot.cpp:43-58`).

## Data

**In-process state** (`src/resolution.cpp:34-38`):

| Symbol | Type | Initial | Meaning |
|---|---|---|---|
| `g_cbResolution` | `ZRef<CCtrlComboBox>` | null | the System Options combobox, nulled on dialog destruction (`:225-228`) |
| `g_nResolution` | `int` | 0 | the selected index; persisted through `CConfig` |
| `g_nScreenWidth` / `g_nScreenHeight` | `int` | 800 / 600 | current client size; read by everything else via `get_screen_width()`/`get_screen_height()` (`src/wvs/util.h:26-28`) |
| `g_nAdjustCenterY` | `int` | 0 | `(height - 600) / 2`, added to every vertical centring (`src/resolution.cpp:51-53`, `:690`) |
| `ms_pOrgWindowEx[9]`, `ms_pOrgStatusBar`, `ms_pOrgScreenMsg`, `ms_pOrgQuickSlot` | `IWzVector2DPtr` | created in `CWndMan::Constructor_hook` | the origin vectors all stock windows hang from (`src/wvs/wndman.h:15-18`) |
| `sRectQuestDlg` | `RECT` at `0x00BE2DF0` | game-owned | quest dialog centre, re-centred on resize (`src/resolution.cpp:436`, `:694-696`) |

**Supported modes** — the only sizes the client can produce (index ← combobox item, `src/resolution.cpp:211-217`, `:661-676`):

| Index | Size | Combobox label | Evidence |
|---|---|---|---|
| 0 | 800x600 | `800 x 600` | default `src/resolution.cpp:658-659`; the reset size `:145-146` |
| 1 | 1024x768 | `1024 x 768` | `:661-664` |
| 2 | 1366x768 | `1366 x 768` | `:665-668` |
| 3 | 1600x900 | `1600 x 900` | `:669-672` |
| 4 | 1920x1080 | `1920 x 1080` | `:673-676`; `SCREEN_WIDTH_MAX`/`SCREEN_HEIGHT_MAX` `:29-30` |

**Not supported** (each is a hard ceiling, not a gap in a switch):

| Not supported | Why | Evidence |
|---|---|---|
| any size outside the five | `set_screen_resolution` has no other case; the switch falls through to 800x600 | `src/resolution.cpp:660-677` |
| 1280x720 | absent from the list although the dead companion table has it as its own index 2 | `src/resolution.cpp:661-676` vs `mapleroot/ijl15/YourStuff.h:479-500` |
| index outside 0..4 | clamped when loaded | `GetOpt_Int(..., 0, 4)` `src/resolution.cpp:179` |
| above 1920x1080 or below 800x600 for the companion | rejected | `mapleroot/ijl15/DllMain.cpp:180-189` |
| exclusive fullscreen | only borderless `WS_POPUP`; the game is never switched to a fullscreen device | `src/system.cpp:198-207`, `src/window_policy.h:5-8` |
| any scaling or stretching | "Nothing is scaled"; the frame buffer is resized, not scaled | `src/window_policy.h:5-8` |
| per-monitor DPI awareness | only process-wide `SetProcessDPIAware` | `src/system.cpp:307-310` |
| runtime adapter/driver diagnosis | logging only; no device is created on another display | `src/d3d9host.cpp:123-143` |
| any fallback through DXVK or dgVoodoo | explicitly out of scope | `docs/160-ui-adjust-plan.md:133-134` |
| display toggles by default | compiled out unless `-DMAPLEROOT_DISPLAY_TOGGLES=ON`, and then only one toggle is live | `src/CMakeLists.txt:62-68`, `src/display_policy.h:3-9` |

**Persisted state**:

| Store | Key | Written | Read |
|---|---|---|---|
| client registry via `CConfig` (`GLOBAL_OPT`) | `soScreenResolution` (0..4) | `src/resolution.cpp:184` | `src/resolution.cpp:179` |
| client registry via `CConfig` (`GLOBAL_OPT`) | `uiWndX<i>`, `uiWndY<i>` for i < 34 | game's own `SaveGlobal` | `src/resolution.cpp:158-175` |
| `HKCU\Software\SlumsMS\Overlay` | `hide_others_cash` (DWORD) | `src/display.cpp:49-52` | `src/display.cpp:41-47` |
| `HKCU\Software\SlumsMS\Overlay` | HUD placement fractions, `tourSeen` | `src/overlay.cpp` | `src/overlay.cpp:24`, `src/overlay_policy.h:15` |
| `integration.log` | `D3D9:`, `SESSION_RESOLUTION`, `Borderless:` lines | `src/d3d9host.cpp`, `src/resolution.cpp:688`, `src/system.cpp:205-206` | humans |

The HUD layout is stored as screen fractions (`kFrac = 10000`, snap grid `kGridFrac = 125` = 1.25 %) so it survives a resolution change; `tests/overlay_policy_test.cpp:21-26` round-trips 800x600 → 1920x1080 (`96,108,576,162`).

`src/ui_wheel.h:5-14` (own file, adjacent subsystem): `CWndMan::ProcessMouse` builds `nWheel` from `WM_MOUSEWHEEL` as `GET_WHEEL_DELTA_WPARAM / -120` at `0x009E3DE9`, so wheel-up arrives negative and a sub-120 notch truncates to 0; `WheelRowStep` returns -1/0/+1 and its only caller is `src/cashshopwnd.cpp:1592`.

## Switches

Every `config.ini` key that governs this subsystem. All are read through `GetPrivateProfileStringA(".\\config.ini")`; the client-side booleans use `integration::ParseIniBool` (`src/attackstop_patch.h:74-79`, wrapped by `ReadIniBool` `src/d3d9host.cpp:41-45`) or `WindowPolicy::IniTrue` (`src/window_policy.h:27-31`).

| Key | Section | Default | Read at | Behaviour |
|---|---|---|---|---|
| `d3d9host` | `[graphics]` | `true` | `src/d3d9host.cpp:297` | false: no `Direct3DCreate9` detour, no adapter log, no `CreateDevice` retry, no overlay. Logs "D3D9: host off" |
| `adapterfallback` | `[graphics]` | `true` | `src/d3d9host.cpp:301` | false: log the failure only, no retries (host and overlay stay on) |
| `settingswindow` | `[clienthax]` | `true` | `src/d3d9host.cpp:302` | false: no HUD window; Ctrl+F10 **and** Ctrl+F11 do nothing (`:270`), and the HUD does not draw at all (`:163`) |
| `uitour` | `[clienthax]` | `true` | `src/d3d9host.cpp:303` | false: first-run Edit UI tour never arms; only effective with `settingswindow=true` |
| `borderless` | `[graphics]` | `false` | `src/system.cpp:306` (`ReadGraphicsSwitch` `:68-72`) | true: `WS_POPUP`, client-sized, centred, frameless drag/close/minimise |
| `dpiaware` | `[graphics]` | `false` | `src/system.cpp:307-310` | true: `SetProcessDPIAware()` before any window exists |
| `resolution` | `[general]` | `5` shipped in `config.example.ini:2` | **nothing** — legacy key, see below | dead in this build |

Legacy key: `[general] resolution` is read only by `mapleroot/ijl15/YourStuff.h:600-608` (`reader.GetInteger("general", "resolution", 1)` → `setResolution`, scale 1..5 with 2 = 1280x720), and that block is wrapped in `#ifndef MAPLEROOT_KAENTAKE` (`mapleroot/ijl15/YourStuff.h:607`) while `mapleroot/CMakeLists.txt:49-50` always defines `MAPLEROOT_KAENTAKE`. The key therefore falls back to nothing at all: the live resolution is the registry `soScreenResolution`, and `config.example.ini:2` ships a value (`5`) that no reader in this build can consume. No other key falls back to a legacy key in this subsystem.

Build-time switches, all `OFF` by default (`CMakeLists.txt:3-18`):

| Option | Effect on this subsystem | Origin |
|---|---|---|
| `MAPLEROOT_INTEGRATION` | compiles `d3d9host.cpp`, `settings_overlay.cpp`, `overlay.cpp` and the ImGui backends, defines `MAPLEROOT_INTEGRATION` (which enables `Borderless_Apply`'s caller, the `D3D9Host_HandleMessage` call, `UpdateMapleRootResolution` and `NoteCurrentField`) | `src/CMakeLists.txt:117-124` |
| `MAPLEROOT_DISPLAY_TOGGLES` | adds `display.cpp` + the "Display" rows; hard-requires `MAPLEROOT_INTEGRATION` ("MAPLEROOT_DISPLAY_TOGGLES needs MAPLEROOT_INTEGRATION (the HUD window)") | `src/CMakeLists.txt:62-68` |
| `MAPLEROOT_CRASH_REPORT` | adds `NoteCurrentField()` to `set_stage_hook` | `src/resolution.cpp:123-131` |
| `MAPLEROOT_INV_RESIZE` | adds `InvResize_OnLoadCharacter` to `CConfig::LoadCharacter_hook` | `src/resolution.cpp:158-166` |

`scripts/build.ps1:11` passes `MAPLEROOT_INTEGRATION=ON` but **not** `MAPLEROOT_DISPLAY_TOGGLES`, so the shipped build has the host and borderless/DPI but no display toggles.

Runtime keys, not ini: Ctrl+F10 (HUD window), Ctrl+F11 (freeze/Edit UI), Esc (close window, else tour), `HKCU\Software\SlumsMS\Overlay\hide_others_cash`.

## Invariants

| # | Invariant | Evidence |
|---|---|---|
| 1 | 800x600 is the floor and the reset target: leaving the field for any non-`CInterStage` stage forces index 0 | `src/resolution.cpp:143-146` |
| 2 | The resolution is applied to a `CField` **before** `set_stage`, and after it for everything else | `src/resolution.cpp:136-137` |
| 3 | `g_nAdjustCenterY` is always `(height - 600) / 2` and is added to vertical centring everywhere; it is never negative and never removed without a recompute | `src/resolution.cpp:690`, `src/wvs/wndman.h:35`, `:45`, `:545-546` |
| 4 | UI window positions are kept within `-5 .. width-6` / `-5 .. height-6`; anything outside is replaced by `get_default_position` | `src/resolution.cpp:151-156`, `:171-173` |
| 5 | A resolution change that produces the same size is a no-op (no `ScreenResolution`, no `Borderless_Apply`, no view rebuild) but still persists the index | `src/resolution.cpp:678`, `:707-709` |
| 6 | `ScreenResolution` never reports success without triggering a device reset: a real change sets `m_hrErrorCode = 0x88760869` | `src/resolution.cpp:637-638` |
| 7 | A failed `ScreenResolution` leaves both globals and the persisted index untouched | `src/resolution.cpp:679-682` |
| 8 | The stock UI is only moved through origin vectors; no window's own coordinates are rewritten on resize | `src/wvs/wndman.h:33-73`, `src/resolution.cpp:690-696` |
| 9 | Patch sites are installed in one fixed order, and every 15-byte / 6-byte call is padded with `NOP` so no stock instruction half-executes | `src/resolution.cpp:713-810`, `src/hook.h:363-372` |
| 10 | With `borderless` on, the window is always exactly the game resolution: no `WM_WINDOWPOSCHANGING` may resize it except a minimise | `src/system.cpp:105-113`, `:202-203` |
| 11 | `borderless` off means `WndProc_hook` still runs (title, D3D9 messages) but never touches the style | `src/system.cpp:99-104`, `:187` |
| 12 | The overlay allocates nothing until it is first needed and draws only when visible, a widget is on, Edit UI is on or the tour wants it | `src/d3d9host.cpp:13`, `:163-165` |
| 13 | The overlay withholds only what it captures: mouse when `WantCaptureMouse`, keys when `WantCaptureKeyboard`; a frozen HUD holds nothing | `src/d3d9host.cpp:288-293` |
| 14 | The display toggle never keeps a layer reference; it writes `visible` once, at load | `src/display.cpp:54-61` |
| 15 | The display hook refuses to run unless the ten stock bytes at `0x00941417` are unchanged | `src/display.cpp:35`, `:92-95` |
| 16 | The companion never receives a size outside 800x600..1920x1080 | `mapleroot/ijl15/DllMain.cpp:180-189` |
| 17 | Ctrl+F10/F11 require a *fresh* Ctrl press (`!(lParam & (1<<30))`), so autorepeat does not flicker the window | `src/d3d9host.cpp:271-276` |

## Origin

| File | Origin | Evidence |
|---|---|---|
| `src/resolution.cpp` | **base** (Kaentake), lightly modified by us | `docs/KAENTAKE-UPSTREAM.md:5` — Custom.wz "extend the system option dialog to include a combobox for selecting the resolution in game"; `git log --follow`: `7d36862 2025-08-27 "WzLib dependency to v83_DX8to9 branch, add stub for AttachResolutionMod"` → `608c7f1 2025-08-30 "Implement changing resolution"` (author iw2d, before our tree starts). Our own changes are +32 lines over `2a43a01..HEAD`: the crash-report note, `InvResize_OnLoadCharacter`, `UiLayout_OnLoadCharacter`, `Borderless_Apply` and the integration block (`0247c3e 2026-10-25`, `20a09b9 2026-10-28`, `88cf6b5 2026-10-28 "Borderless: keep the window frameless and client-sized after a resolution change"`) |
| `src/wvs/wndman.h`, `src/wvs/config.h` | **base** | added by `f98c4c0 2026-04-02 "Port common changes from custom-ui branch"` / `817d83e 2026-04-04`, i.e. before our history begins at `2a43a01 2026-06-05`; our delta on `wndman.h` is +12/-4 (`UiLayout_Offset` calls) |
| `src/system.cpp` | **base** + **custom** | base `6003540 2025-08-27 "Add system hooks and helper functions and macros"`; `+153/-4` ours, of which borderless/DPI/cursor/drag is `a1607ae 2026-10-28 "Borderless window and DPI-aware mode ([graphics] borderless / dpiaware, both off)"` |
| `src/d3d9host.cpp`, `src/d3d9host.h` | **custom** | created `bc58352 2026-10-25 "Host Direct3D 9 for Parsec diagnosis and an in-game settings window"`; `11a9bde`, `464352b` same day; `0247c3e` added the Edit UI grips |
| `src/d3d9host_policy.h` | **custom** | only commit `bc58352`; header-only specifically so the policy is testable off Windows (`:3`) |
| `src/window_policy.h` | **custom** | `a1607ae 2026-10-28` (ticket 137, owner pick 2026-10-28, `:3`) |
| `src/display_policy.h` | **custom** | `b6279d3 2026-10-28 "Scaffold ticket 120: display and performance toggles"` |
| `src/display.cpp` | **custom** | `302bd40 2026-10-28 "Display toggles: hide other players' cash and set effects (ticket 120, first toggle)"`, fixed by `8eee9ac 2026-10-31 "...no reference to other characters' effect layers (b250)"` |
| `src/ui_wheel.h` | **custom**, adjacent | `6ce4a21 2026-10-21 "Scroll the cash shop the right way with the mouse wheel"`; not display code |
| `mapleroot/ijl15/DllMain.cpp`, `mapleroot/ijl15/YourStuff.h` | **base** (MapleRoot companion) | `docs/PROVENANCE.md:3` — starting install is the MapleRoot Full Repack and the companion is the MapleRoot ijl15 source port; the `MAPLEROOT_KAENTAKE` bridge block is ours |

Baseline for "ours": the client history begins at `joshhmann/kaentake 2a43a01e7f4cdd75cda08ab533049230760d7cfa` (`docs/PROVENANCE.md:3`), commit 191 of 271.

## Tests and QA

| ctest name | Test file | What it proves | Registered at |
|---|---|---|---|
| `d3d9host_adapter_fallback` | `tests/d3d9host_policy_test.cpp` | fallback order `{{1,hardware},{0,software},{1,software}}` (`:19-21`); one adapter keeps its other flags (`:24-26`); an already-software request never repeats itself (`:29-31`); `WithSoftwareVP(mixed\|0x2)` (`:34`); `HrName(0x80004005)=="E_FAIL"` ("the Parsec start-up error"), `NOTAVAILABLE`, unknown → `"?"` (`:36-38`) | `tests/CMakeLists.txt:76-77` |
| `window_borderless_place` | `tests/window_policy_test.cpp` | `Place` for 1920x1080@1920x1080 → `(0,0)`, 1366x768 → `(277,156)`, second monitor `{1920,0,4480,1440}` 2560x1440 → `(1920,0)`, oversized 1920x1080 on 1280x720 → `(0,0)` (`:18-24`); `IniTrue` accepts `true/1/ON` and rejects `false`/empty/null (`:26`) | `tests/CMakeLists.txt:161-162` |
| `display_toggle_rules` | `tests/display_policy_test.cpp` | every (Category,Owner) pair defaults to `{true,255}` (`:17-24`); `hideBack` hides only Back; others' effects at 40 % → alpha 102 while mine stays 255; `petPct=0` hides only others' pets; `hideOthersCash`; `ApplyAlpha(0x80FF0000,128)==0x40FF0000`; animation cap 3 (`:26-34`) | `tests/CMakeLists.txt:163-164` |
| `ui_wheel_direction` | `tests/ui_wheel_test.cpp` | client wheel sign (`d/-120`), up → -1, down → +1, sub-notch → 0, 360 → -1 (`:6-16`) | `tests/CMakeLists.txt:153-154` |
| `overlay_hud_layout` | `tests/overlay_policy_test.cpp` | HUD fractions round-trip at 800x600 and scale to 1920x1080 (`:21-26`) — the piece of "resolution" that is actually unit-tested | `tests/CMakeLists.txt:81-82` |

All five are unconditional (no CMake option guard) and run on any host: they only include the header-only policy files. `scripts/build.ps1:17` runs `ctest --test-dir $buildDir -C Release --output-on-failure` and throws `Native tests failed` on any red test; `/root/work/client/src-164-data/README.md:14-20` documents "all seven checks" with `-TestClient`, and "without external Data, only the five tests that do not require game assets are registered".

**Not covered by any automated test:**

- `src/resolution.cpp` in its entirety — 810 lines, 64 hook and immediate-patch statements, no test compiles it (`grep` over `tests/` finds no `resolution.cpp`; no test name mentions it). Only `overlay_policy_test` touches the word "resolution" in a comment.
- `src/d3d9host.cpp` — the fallback *policy* is tested, the retry loop, logging, ImGui lifecycle and message arbitration are not.
- `src/system.cpp` borderless/DPI — only `WindowPolicy::Place`/`IniTrue` are; the style stripping, size forcing and frameless drag/close/minimise are untested.
- `src/display.cpp` — only the policy is tested; the `CUser::LoadLayer` detour and the byte check are not.
- Anything requiring Windows, the exe, `Gr2D_DX8.DLL` or a real device.

**What a real client must confirm by hand** (nothing below has an automated check):

1. Every one of the five combobox entries changes the size, and the stock HUD stays on screen — `docs/PROJECT-STATUS.md:26` records user confirmation for 800x600, 1366x768 and 1920x1080 only; 1024x768 and 1600x900 have never been reported.
2. Resize while in a field: view range, weather, limited-view darkness, buff icons, screen messages and the boss HP tag all land correctly, and `integration.log` shows `SESSION_RESOLUTION width=… height=…`.
3. Log out to character select and back in — the size survives (`docs/PROJECT-STATUS.md:141` lists this as still outstanding).
4. Borderless: alt-tab, drag by the title strip, `HTCLOSE` quits, `HTMINBUTTON` minimises, restore keeps the client size, and a resolution change afterwards keeps the frame off.
5. DPI-aware at 125–200 %: the game is pixel-for-pixel and the mouse maps where it is drawn.
6. Ctrl+F10 / Ctrl+F11 / Esc, and that a focused HUD window does not steal movement or chat keys.
7. With a Parsec or other virtual display as primary: does `D3D9:` logging name the failing call, and does `adapterfallback` get the client up? `/root/work/client/src-164-data/README.md:150` currently documents "disconnect Parsec → launch → reconnect" as the only working answer.
8. Display toggles build (`-DMAPLEROOT_DISPLAY_TOGGLES=ON`): another player's cash/set effect is hidden on load, and `integration.log` says "Display: toggles attached" rather than the stock-code refusal.

## Known issues

**Ticket cards** — `/root/work/STATUS-1.6.4.md` client-lane rows are `b251` (S2 client crash entering Rose Garden 211080100, `:72`), `b270` (S3 launcher news manifest, `:75`), `b274` (S3 Will's/Magnus's boss portals, `:76`), `L15` (client-side fixes and the bonds window, `:106`) and `e14` (in-game Slums Exchange window, `:124`). **None of them concerns resolution, D3D9, borderless or display toggles** — this subsystem has no open card.

- **Parsec start-up `-2147467259` (E_FAIL)** — the reason `d3d9host.cpp` exists, still unexplained: "Which call fails is not known yet" (`src/d3d9host_policy.h:6-8`). The only working answer is procedural: disconnect Parsec, launch, reconnect (`/root/work/client/src-164-data/README.md:150`). Resolution changes, a GPU-preference override and removing the DirectX wrapper were all tried and reverted (`/root/work/client/src-164-data/README.md:148-150`). The owner has parked it as "easily bypassable" (`docs/160-ui-adjust-plan.md:133-134`).
- **Exit-time crash on Parsec** — `C0000005` in `slums.dll`'s exit cleanup, seen once while the owner was on Parsec, parked with the above and not linked to the host by evidence (`docs/160-ui-adjust-plan.md:133-134`).
- **Display toggles are a scaffold** — `src/display_policy.h:3` says "SCAFFOLD"; only `hideOthersCash` has a hook (`src/display.cpp:68-79`), the other eight preferences in `Prefs` (`:19-26`) have no capture path, and `docs/research-119-120.md:303` lists pets/summons and skill-effect opacity as the remaining order of work. Not built by `scripts/build.ps1`.
- **`NEW-FINDING: src/resolution.cpp:807-810 — a failed module pattern is written through as null.** `GetAddressByPattern` returns `nullptr` when `GR2D_DX8.DLL` is absent, unloaded or the pattern is not found (`src/hook.cpp:149-153`), and the result is passed straight to `PatchJmp` via `CWzGr2D__AdjustCenterY_jmp = nullptr` (`:808-809`). `PatchJmp` → `Patch1` → `PatchMemory` calls `VirtualProtect` then `memcpy` on that pointer (`src/hook.cpp:157-163`, `src/hook.h:357-361`), so a differently built `Gr2D_DX8.DLL` turns the whole resolution subsystem into an access violation at injection rather than a logged refusal — unlike `AttachDisplayMod`, which checks its bytes first (`src/display.cpp:92-95`). No byte or null check guards the 806 fixed-VA patches either.
- **`NEW-FINDING: config.example.ini:2 ships `[general] resolution=5`, a key nothing reads in this build** (the reader is compiled out at `mapleroot/ijl15/YourStuff.h:607`), and its 1..5 scale would mean 1920x1080 while the live scale is 0..4, where 5 would be out of range. A player who edits it sees nothing happen and no log line explaining why.
- **`NEW-FINDING: the two mode tables disagree on 1280x720.** The companion's dead table has it (`mapleroot/ijl15/YourStuff.h:479-500`, index 2), the live client's does not (`src/resolution.cpp:661-676`) — so any future re-enable of the legacy key would pick a mode the client cannot display.
- **`NEW-FINDING: 1024x768 and 1600x900 have never been reported working.** `docs/PROJECT-STATUS.md:26` confirms only 800x600, 1366x768 and 1920x1080, and nothing in the patch table is mode-specific, so the risk is in the layout rather than the device path.
- **`NEW-FINDING: resolution has zero automated coverage** (see Tests and QA). 42 byte patches and 22 hooks in `src/resolution.cpp` are validated only by the fact that the client starts.
- **`NEW-FINDING: src/display.cpp:41-47 latches the pref once.** `g_loaded` makes `LoadPrefs` a no-op after the first call, so a registry edit made by anything other than this DLL (including a second client instance) is not seen until restart; `Display_DrawSettings` calls `LoadPrefs` on every frame for no effect (`src/display.cpp:84-90`).
- **`NEW-FINDING: src/d3d9host.cpp:305 keeps the host alive only through the forced import.** `g_pForceD3D9Import` (`:35-37`) is the only reason `d3d9.dll` is loaded at `DllMain`; the guard at `:304-309` degrades to "d3d9.dll is not loaded; host off", which reads like a driver problem rather than a link-order change.
- **`NEW-FINDING: the overlay's catch-all leaves ImGui mid-frame.** `src/d3d9host.cpp:178-181` closes the window on an exception after `NewFrame` may already have run, and only logs once, so a recurring failure is invisible after the first line. Recovery depends on ImGui's own `NewFrame`/`EndFrame` pairing rather than on a reset here.

## State

| Piece | State | Evidence |
|---|---|---|
| Resolution switch | **working** for 800x600 / 1366x768 / 1920x1080; **partial** for 1024x768 and 1600x900 (unverified) | user-confirmed modes and 26 quick-slot hit targets `docs/PROJECT-STATUS.md:26`; confirmed again with the weather build `:151`; no automated test at all |
| Resolution persistence across logout | **unknown** | listed as outstanding `docs/PROJECT-STATUS.md:141`; still unproven in `docs/PROJECT-STATUS.md:29-30` |
| D3D9 host — logging and `CreateDevice` fallback | **partial** | policy is unit-tested (`tests/d3d9host_policy_test.cpp:19-38`), the shipped build enables the host (`scripts/build.ps1:11` turns on `MAPLEROOT_INTEGRATION`), but the failure it was written for is still unexplained (`src/d3d9host_policy.h:6-8`) and no run has been shown to recover via the fallback |
| ImGui overlay / HUD window | **working** in the shipped build | README documents Ctrl+F10/Esc and the switch table (`/root/work/client/src-164-data/README.md:97-103`); feature-complete code path `src/d3d9host.cpp:146-182`, `src/settings_overlay.cpp:14-28`; no automated test |
| Borderless window | **partial** | implemented and switchable (`src/system.cpp:198-207`, `a1607ae`), off by default, and no record of a real-client run in the repo docs |
| DPI-aware | **partial** | one-line implementation (`src/system.cpp:307-310`), off by default, untested |
| Display toggles | **partial (scaffold)** | one toggle wired (`src/display.cpp:71-79`), compiled out of the shipped build (`src/CMakeLists.txt:62-68` not passed by `scripts/build.ps1:11`), policy only exercised natively |
| `ui_wheel.h` (adjacent) | **working** | single caller `src/cashshopwnd.cpp:1592`; behaviour covered by `ui_wheel_direction` |

A patch that compiles but cannot be switched on or has never been exercised on a real client is called partial above, not working.

## Open questions

1. **Which call returns `E_FAIL` under Parsec?** `src/d3d9host_policy.h:6-8` says it is unknown; no captured `integration.log` from a failing start exists in the repo to answer it.
2. **Does the fallback actually rescue that case,** or does the client fail before `CreateDevice` (e.g. in `Gr2D_DX8`'s own adapter enumeration)? Nothing in the repo distinguishes the two.
3. **Do 1024x768 and 1600x900 lay out correctly?** Only three of five modes have ever been reported; the misplacement risk is in `get_default_position`'s hardcoded 800x600-era coordinates (`src/resolution.cpp:55-118`) and the origin-vector arithmetic, not in the device path.
4. **Is the resolution restored after a full logout to login?** `docs/PROJECT-STATUS.md:141` leaves it open and `set_stage_hook` resets to 800x600 on every non-field stage (`src/resolution.cpp:143-146`) — whether the login screen then jumps back to the saved size depends on when `CConfig::LoadGlobal` runs relative to the first field load, which is not documented anywhere in the tree.
5. **Which `Gr2D_DX8.DLL` builds are the two patterns valid for?** `src/resolution.cpp:807-808` matches two byte patterns but nothing records the DLL version or hash they were derived from, and there is no fallback if they miss (see the null-write finding).
6. **Is the exit-time `C0000005` in `slums.dll`'s cleanup the same root cause as the Parsec start-up failure?** `docs/160-ui-adjust-plan.md:133-134` parks both together but records no evidence linking them.
7. **Does anything need the 1280x720 mode** that the client dropped but the companion's dead table still has? No ticket or doc states that it was intentionally excluded.
8. **Should the display toggles ship in 1.6.4?** They are complete enough to build but excluded from `scripts/build.ps1` with no recorded decision; `docs/research-119-120.md:303` still lists remaining work.
