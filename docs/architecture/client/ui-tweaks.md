# UI tweaks (hud, overlay, uilayout, invresize, bounce, weather, mobdamage)

## Purpose

Seven client-side feature groups that change what the player sees or can drag, all applied by `slums.dll` to the stock v83 `MapleStory.exe` (fixed image base `0x400000`) as byte patches, code caves and detours (`docs/architecture/client/README.md`).

| Area | What it is for |
|---|---|
| hud | HP/MP/EXP/level modules that read the stock status bar's own data, so the stock strip can be hidden and the numbers rebuilt in stock art (`src/hud_native.cpp:1-40`, `src/hud_vitals.cpp:1-13`); plus EXP-rate and DPS meters (`src/hud_meters.cpp`). |
| overlay | The ImGui layer itself: theme, font, per-widget fractions, freeze/Edit UI, the Ctrl+F10 settings window (`src/overlay.cpp:1-12`). |
| uilayout | Move the stock status bar, quick slot and their button rows; first-run tour (`src/uilayout.cpp:1-12`, `src/uitour.cpp`). |
| invresize | Drag the item inventory from 4x6 to 4-16 cols x 3-14 rows (`src/invresize.cpp:1-20`). |
| bounce | Double-tap jump and server-driven bounce-blocked maps (`src/bounce.cpp`, `src/bounce_rules.h`). |
| weather | Server clock/sky → map tint, injected clouds/rain/snow, puddles, drifts, splashes, foliage sway (`src/weather.cpp:8-48`). |
| mobdamage | Stop low defence from *adding* mob touch damage (`src/mobdamage.cpp`). |

Related addon windows that ride the same overlay/dispatcher plumbing are documented under `How it works` → *Attached windows*.

## Entry points

Every patch is installed from one function, in a fixed order (`src/hook.h:111-305`). The lines that matter here:

| Installer | Line in `src/hook.h` | Feature |
|---|---|---|
| `AttachWeatherMod` / `AttachWeatherWindMod` | :194-195 | weather (then `VerifyKaentakeHooks("Weather/time/lamps")`) |
| `AttachMonsterBook*` (4 calls) | :210-213 | book, found-in, drops, search — drops before search |
| `AttachWeaponTintMod` / `AttachColoringPrismMod` / `AttachColoringPrismHostHooks` | :217-219 | weapon tint + prism window |
| `AttachToolTipMod` / `AttachIconIconMod` | :225 / :230 | equip tooltip, pet icon |
| `AttachHudMetersMod` | :251 (inside `#ifdef MAPLEROOT_HUD_METERS`) | EXP/DPS modules |
| `AttachDamageFormatMod` | :261 | short damage numbers |
| `AttachInvResizeMod` | :265 | inventory resize |
| `AttachUiLayoutMod` | :271 | stock UI mover |
| `AttachMobDamageMod` | :273 | low-defence rule |
| `AttachBounceMod` | :293 (inside `#ifdef MAPLEROOT_INTEGRATION`) | bounce |
| `AttachMobHpTagMod` | :201-208, `#ifndef MAPLEROOT_INTEGRATION` | mob HP tag (MapleRoot supplies its own in the integration build) |

Runtime entry points outside the install list:

- `AttachD3D9Host` (`src/d3d9host.cpp:296-312`) creates the ImGui context, then `Overlay_Setup` (`src/d3d9host.cpp:153`) and `UiTour_Setup(g_bSettingsWindow && ReadIniBool("clienthax","uitour",true))` (`src/d3d9host.cpp:304`). `DrawOverlay` (`src/d3d9host.cpp:160`) is called from the `EndScene` detour (`src/d3d9host.cpp:185`); the gate is `if (!g_bVisible && !(g_bSettingsWindow && (Overlay_HasHud() || !Overlay_IsFrozen() || UiTour_Wants()))) return;` (`src/d3d9host.cpp:162`).
- `D3D9Host_HandleMessage` (`src/d3d9host.cpp:269`): Ctrl+F10 toggles the settings window (:273), Ctrl+F11 calls `Overlay_ToggleFreeze` (:277), Esc closes the window (:281) and otherwise reaches `UiTour_HandleEscape` (:285).
- Main-thread ticks from `CWvsApp::CallUpdate_hook` (`src/bypass.cpp:243-269`): `UiLayout_Tick()` (:245), weather frames gated on `Weather::IsFieldActive()` / `HasFallingSky()` (:248-258), `CashShopWnd_Tick()` (:263), `SetItem_Tick()` (:266), `MonsterBook_Tick()` (:269).
- Inbound packets are routed by `src/packetdispatcher.cpp`: cash shop 0x3731 (:36-38), check-in 0x3733 (:43-45), set item 0x3727 (:50-52), monster book 0x3735 (:57-60), boss damage rank 0x3739 (:65), weapon tint 0x372F (:73-76), bounce zones 0x3737 (:81-83), weather 0x373D and lamp preview 0x373F (:88-92).

## How it works

### hud

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/hud_vitals.cpp`, `src/overlay.cpp`, `src/overlay_policy.h` | Optional HP/MP/EXP bars drawn by ImGui in the top-left, label inside the bar ("HP 25000 / 50073 (49%)") | none (per-widget `hp_on`/`mp_on`/`exp_on` in the registry) | working |
| `src/hud_native.cpp` | Replaces the stock bottom strip with separately movable HP/MP/EXP gauge modules and the level/name plate, built from the bar's own sprites | `[clienthax] nativehud`, default **1** (`src/hud_native.cpp:388-392`) — undocumented | working |
| `src/hud_meters.cpp`, `src/hud_meters_policy.h` | EXP-rate and DPS widgets, extras row with party damage ranks | none — widgets `exprate` / `dps` exist only under `MAPLEROOT_HUD_METERS`, off by default (`src/overlay.cpp:60-69`); online switch is the ini key `clienthax`→`nativehud` only | working |

1. `hud_vitals` does not hook the bar. It reads what `CUIStatusBar::Draw` (`0x008D80A4`) was given: HP/MP from `GW_CharacterStat` +0x61/+0x71 through the `_ZtlSecureFuse<short>` at `0x004746DD` that `hpmp.cpp` already detours to return the whole 4-byte value, max HP/MP from `CWvsContext` +0x211C/+0x2128 through `_ZtlSecureFuse<long>` `0x00416563` (`src/hud_vitals.cpp:1-13`). `SehReadVitals` (:63) is an SEH leaf; `FrameVitals` (:100) caches per `ImGui::GetFrameCount()`; `Waiting` (:112) shows "Waiting for a character..." before a character exists; `HudVitals_Read` (:136) exports the same numbers to the native HUD.
2. `hud_native` builds once, from artefacts already on the bar window: the plate canvas layer at `+0xCD0`, the gauge strip at `+0xCD4`, the three grey covers at `+0xBB8/0xBBC/0xBC0/0xBC4` (`src/hud_native.cpp:50-52`). Rows 36-69 of the plate art are the level plate (x 0-209) and gauge plate (209-563); the gauge interior is 31 px high, strip x 218, 2 px down (`:57`). `UpdateGauge` (:236) shrinks the cover, `UpdateLevel` (:313) writes the level/job/name, `UpdateChatRow` (:348) keeps the chat input row, then `SetStockPiecesVisible(false)` (:204).
3. `HudNative_Tick` (:397-432) bails when `!NativeHudEnabled() || g_bFailed`, rebuilds when the bar pointer changes, and on a build failure logs "Native HUD: could not build the modules; the stock bar stays" and latches `g_bFailed`. The whole body is in a `catch` that does `DropAll()`, `SetStockPiecesVisible(true)` and `LOG_ONCE("Native HUD: drawing failed; the stock bar is back")` (:428-431).
4. `hud_meters` detours `CMob::OnHit` `0x00668B83` (thiscall, 13 stack args, `ret 0x34`) and only counts a hit when the attacker id equals `CUserLocal+0x11A8` and `a5 > 0` (`src/hud_meters.cpp:51-89`). Party ranks arrive out of band on LP 0x3739 and show for `kRankShowMs = 15000` (:39) under the player's own DPS. Level-ups are inferred, because the EXP table and the EXP value both move: `if (g_lastNext >= 0 && next != g_lastNext && exp < g_lastExp) ++g_levelCounter;` (:86).
5. The meters' arithmetic is header-only and tested: `ExpTracker` (15-minute window, `PerHour` returns 0 until a 60 s span, gains carried across a level-up) and `DpsMeter` (10 s window, `if (damage <= 0) return;`) in `src/hud_meters_policy.h:25-90`.

### overlay

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/overlay.cpp`, `src/overlay_policy.h` | ImGui theme, font, the HUD widgets, the Ctrl+F10 "SlumsMS HUD" window, grid overlay while editing | `[graphics] d3d9host` (default true, `src/d3d9host.cpp:297`) and `[clienthax] settingswindow` (default true, :303) gate everything here | working |
| `src/settings_overlay.cpp` | The window shell itself (`SettingsOverlay_Draw`, :12) | as above | working |
| `src/uitour.cpp`, `src/uitour_policy.h` | First-run Edit UI card and pulsing grips | `[clienthax] uitour`, default true (`src/d3d9host.cpp:304`) | working |
| `src/damagefmt.cpp`, `src/damagefmt_policy.h` | Damage numbers printed as 12.3K / 1.23M / 2.14B | none — registry value `damage_short` under `HKCU\Software\SlumsMS\Overlay`, default **off** (`src/damagefmt.cpp:61`) | working |
| `src/tooltip.cpp` | Equip tooltip body: category, attack speed, stat rows, knock-back rate, slip/cold, upgrades; plus SET EFFECT / PIECES EQUIPPED rows | none | working |
| `src/itemicon.cpp` | 1 px frame + boss-pet icon on the active pet's slot | none | working |
| `src/lamps.cpp` | Lamp light pools, warm-up ramp, scenery relight, and the map-object art guard | `[clienthax] mapobjguard`, default true (`src/lamps.cpp:1498-1501`) | working |

1. `LoadPrefs` (`src/overlay.cpp:88-110`) reads `theme`, `scale`, `snap`, then per widget `<id>_on/_x/_y/_w/_h/_text/_alpha`; every position is a screen fraction out of `kFrac = 10000` (`src/overlay_policy.h:24`) and `FromFractions` (:20) clamps a widget back on screen. `g_bFrozen = true` at load (`src/overlay.cpp:91`) — "Edit UI is a temporary mode: every session starts with the layout fixed".
2. `ApplyTheme` (:123) samples `UI/UIWindow.img/Item/FullBackgrnd` so the modules wear stock colours (cream `#EEEEDD`, frame `#99BBCC`, steel `#557799`, text `#333333`). `Overlay_BeforeFrame` (:224) rebakes the font atlas when the target pixel size moves by ≥1; `FontPixels` is `clamp(15*screenH/600 * scale/100, 11..40)` (`src/overlay_policy.h:36`), the atlas is `clamp(base*MaxTextPct/100, 11..96)`, and `FontGlobalScale = base/atlasPx`.
3. `Overlay_DrawHud` (:237) draws only in the field (:240), draws faint grid lines every `kGridFrac` when unfrozen and snapping, calls `UiLayout_DrawGrips` first, and adds `NoTitleBar|NoMove|NoResize|NoInputs` while frozen so clicks reach the game. Height is derived from the row count, so a widget is exactly as tall as its content when unfrozen. `UiTour_Draw` runs last.
4. `Overlay_DrawSettings` (:315-386) is the module table (enable / text size 60-200 / opacity 0-100 / reset), then "Edit UI (Ctrl+F11)..." (:351), "Snap to grid while editing" (:354), "HUD size" (:355), "Look" (:360), "Reset UI layout" (:364-377 — widgets + `UiLayout_Reset` + `InvResize_ResetSize`), "Show the UI tour again" (:379), then `Display_DrawSettings` (:381) and `DamageFmt_DrawSettings` (:384).
5. `UiTour` is once per PC: `UiTour_Wants` returns `g_dwSeen < kVersion && InField()` (`src/uitour.cpp:108`) with `kVersion 1` / `kDelayMs 1500` / `kPulseMs 9000` (`src/uitour_policy.h:13-15`); `MarkSeen` (:40) writes `tourSeen`; `UiTour_Replay` (:134) is the settings-window row; `UiTour_HandleEscape` (:138) marks it seen on Esc.
6. Short damage numbers: the call at `0x00437DFB` (`CAnimationDisplayer::Effect_HP` formatting `"%d"` with `ZXString<char>::Format` `0x00445B4B`) is redirected — site table `src/damagefmt_policy.h:61` `{0x00437DFB, {0xE8,0x4B,0xDD,0x00,0x00}}`. Values ≥10000 become glyphs 10-13 via `ShortDamage`/`EncodeGlyphs` (`.`→`:` , `K`→`;`, `M`→`<`, `B`→`=`). `GlyphsPresent` (:34) checks 8 digit sets x 4 sprites in `Effect/BasicEff.img`; a missing glyph logs "Damage numbers: glyph %ls missing in Custom.wz; short numbers stay off".
7. Tooltip: `SetToolTip_Equip_Basic_hook` (`src/tooltip.cpp:40`) rebuilds the equip body from `GW_ItemSlotEquip` (nItemID +0xC, nRUC +0x28, niSTR +0x34 stride 8 out to nAttribute +0xAC, :12-30), with the SET EFFECT / PIECES EQUIPPED rows deliberately limited to two because `AddInfoEx` rows have no bounds check (:97-105). `AttachToolTipMod` (:115) also hooks `DrawToolTip_Equip` and `ClearToolTip`, then `AttachSetStatBonusMod` (`src/setstatbonus.cpp:108`) so the Ability window and the client's damage maths include the set bonus.
8. `itemicon` is a naked hook at `0x0081DEE9` around `CItemInfo__DrawItemIconForSlot_helper` (`src/itemicon.cpp:30,51,56`) — because it sits inside the slot-icon path, it follows invresize's `GetSlotRect` replacement for free.

### uilayout

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/uilayout.cpp`, `src/uilayout_policy.h` | Status bar, quick slots, main buttons, icon row, chat input each get a grip in Edit UI and can be dragged; saved per character | none player-facing (`[debug] uistate` is QA-only) | working |
| `src/uitour.cpp`, `src/uitour_policy.h` | First-run card + grip pulse | `[clienthax] uitour` (see overlay) | working |
| `src/cashshopwnd.cpp`, `src/cashshopwnd.h` | In-client cash shop window instead of the stock cash-shop *stage* | none | working |
| `src/quickslot_patch.h` | Expanded quick-slot key page bounds | none | working |

1. The stock bar hangs off origin vectors built by `resolution.cpp` and placed by `CWndMan::ResetOrgWindow` (`src/wvs/wndman.h`): `ms_pOrgStatusBar`, `ms_pOrgQuickSlot`. `ResetOrgWindow` asks `UiLayout_Offset(int e, int& dx, int& dy)` (`src/uilayout.cpp:342-364`) per element, so a move is one `RelMove` of that origin — no per-control patching.
2. Nine elements, `kElemCount`, split three ways: status bar and quick slot are origin vectors (`kStatusBar`, `kQuickSlot`); the button groups are control ids 1000/1001/1002/1007, 2000-2003/1005/1006/1009 and 1011/1012/1004; the native modules are `kNativeHp/Mp/Exp/Level` (`src/uilayout_policy.h:20-22`, table in `src/uilayout.cpp:52-79`). Controls are looked up by **id**, not pointer, because the client destroys and recreates them (the chat edit box every open/close) — `StockCtl`, `ForEachBarControl` (:120), `ForEachGrouped` (:144).
3. Drag → `ClampWithin` → `Reapply()` (`ResetOrgWindow` + `ApplyGroups`, :104-107); on release `ToFrac` → `OverlayPolicy::Snap` when `Overlay_SnapOn()` → `Save(e)` → `Reapply()` (:543-640). `Save` (:87-101) writes `Pack(offset)` as a `REG_DWORD`, or `RegDeleteKeyValueA` when the offset is 0.
4. Per character: `UiLayout_OnLoadCharacter` (:515-531) is called from the `CConfig::LoadCharacter` hook, reads `w<world>_c<id>_<suffix>` from `HKCU\Software\SlumsMS\Layout` (`kSuffix`/`kLabel`, :44-46), and `Reapply()`s. This exists because the client rewrites its own `g0_` registry keys on every field entry (`docs/160-ui-adjust-plan.md`).
5. Clicks: `BarHitTest_hook` (:446-465) wraps `CUIStatusBar::HitTest` `0x008DE7DA` and returns `2` + `ppCtrl` when the point lands on a **moved** grouped control, because the stock hit test throws away anything above the bar strip (local y ≥ 507, or ≥ 427 right of x647). `AttachUiLayoutMod` (:469) verifies the stock bytes `{0x55,0x8B,0xEC,0xA1,0x0C,0xC2,0xBE,0x00}` at that address first, else logs "UI layout: CUIStatusBar::HitTest has unexpected bytes; moved buttons click only near the bar".
6. `UiLayout_Tick` (:506-514): `ApplyGroups()` when a character is in the field, then `HudNative_Tick()` under `MAPLEROOT_INTEGRATION`, then `WriteUiState()` when `[debug] uistate=1`. `WriteUiState` (:379-444) writes `slums-ui-state.json` every 500 ms by tmp+rename with stage, screen, `chat_open` and the rects of bar/chat/quick slot/buttons/icon row — a QA driver contract, never for players.
7. `cashshopwnd` has **no hooks** (`src/cashshopwnd.cpp:25-33`). v83 opens the cash shop as a stage change (`SET_CASH_SHOP 0x7F`); the server instead answers `CP 0x28 ENTER_CASHSHOP` with LP 0x3731 OPEN, `CashShopWnd_HandleSync` (:3399) fills the catalogue under `g_mtx` on the receive thread and raises `g_bWantOpen` (:3416), and `CashShopWnd_Tick` (:3540) creates the window on the main thread. Taxonomy is read from `Etc.wz/Category.img` and limits are `kMaxEntriesPerChunk 2048` (:117-119). Actions go out on `kCashShopActionOpcode 0x3730` (`src/cashshopwnd.h:20`).

### invresize

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/invresize.cpp`, `src/invresize_patch.h` | Item inventory window is resizable by right edge / bottom edge / corner grip, 4-16 cols x 3-14 rows, max 120 cells, background composited from `Item/FullBackgrnd` at runtime | `[clienthax] invresize`, default true (`src/invresize.cpp:548-556`); off → "Inventory resize: off in config.ini ([clienthax] invresize=false); stock window kept" (:677-679) and nothing at all is patched | working |

1. `AttachInvResizeMod` (:675-706) gates on `EnabledInConfig`, verifies the stock bytes, forces narrow mode by patching the only `m_bFull` read (the call at `0x0081C4BE` to `0x0049F5DA` becomes `xor eax,eax`), seeds the stock 4x6 immediates so the first window is right before the config is read, then `PatchJmp`s `GetSlotRect` `0x0081E2C8` and `GetSlotPositionFromPoint` `0x0081DB7E` and detours `OnCreate` `0x0081C6C9` / `Draw` `0x0081DC20`.
2. Replacing those two functions is what makes the resize coherent: five and four callers respectively, none doing their own arithmetic, so icons, tooltips, drag, drop, double-click and right-click all move together (`src/invresize.cpp:1-20`).
3. Geometry is header-only and asserted: `kColPitch 36`, `kRowPitch 34`, `kGridLeft 8`, `kGridTop 50`, `WndW = 36*cols+31`, `WndH = 34*rows+85`, stock 4x6 = 175x289, `kMaxCells 120` with three `static_assert`s that the value still fits the `Draw` imm8 and the scroll-bias imm8 (`src/invresize_patch.h:98-121`).
4. `InvResize_HandleMouseMessage` (:558) records a pending size from the `CWndMan::TranslateMessage` hook; `InvResize_Tick` commits it with `CWnd::Destroy` + `CreateWnd`, the same way the client's own expand button does. Size persists per character as `w<world>_c<id>` = `(cols<<8)|rows` in `HKCU\Software\SlumsMS\Inventory` (`src/invresize.cpp:335-338`, `PackSize`/`UnpackSize` `src/invresize_patch.h:136-149`); anything invalid reads as stock 4x6.
5. `SehTabSlotCount` (:87-105) reads the real tab index 1..5 from `+0x5E4` and the tab array `GW_CharacterData+0x447`. 1.6.0 had taken the index for 0..4, so Cash (5) read as zero slots and drew empty for every player with resize on (`src/invresize_patch.h:91-95`).

### bounce

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/bounce.cpp`, `src/bounce_rules.h`, `src/bounce_zones_builtin.inc` | Jump again mid-air → Ninja Bounce; skills are refused on the server's jump-quest maps; skill impulse differs per job family | `[clienthax] doubletapjump`, default **false** (`src/bounce.cpp:231`) | partial — zones and impulses always run, double-tap is opt-in |
| `mapleroot/ijl15/SkillEdits/Activeskill.h` | call sites that ask the client for the impulse and the veto | — | working |

1. MapleRoot already rewrites the jump-skill branch at `0x0096792A` into `CUserLocal::DoActiveSkill_BoundJump` `0x0096BE38`; its cave at `0x0096BF0B` accepts the bounce ids next to `14101004` and rewrites the impulse immediates per use. SlumsMS hooks into that, it does not duplicate it.
2. Double-tap: `AttachBounceMod` (:230-250) redirects the single `CUserLocal::Jump` call at `0x0094F5A7` to `JumpKeyThunk` (:127-133), a naked stub that captures the key message's `lParam` and jumps to `JumpKeyCall` (:108-125). Only when `g_doubleTap` is true; unexpected bytes log "Bounce: unexpected bytes at 0x%08X, double-tap jump NOT installed".
3. `JumpKeyCall` accepts a fresh press (lParam bit 30 clear), requires airborne-by-client-physics through `ReadAirState` (:90-106: no foothold, not on a ladder/rope, not swimming or flying), and then asks `DoActiveSkill` for the bounce of its job family. The stock one-bound-jump-per-airtime flag at `CUserLocal+0x2B58` still applies.
4. `SlumsBounce_BlockSkill` (:164-183) is called at the top of MapleRoot's `DoActiveSkill` detour (`mapleroot/ijl15/SkillEdits/Activeskill.h:620`): all six universal bounce ids are refused on a blocked map, and Flash Jump (`4111006`/`14101004`) only where the zone category says so. Ninja Bounce on the ground is a no-op, refused before any MP, cooldown or animation. Hermit Flash Jump branches at `0x0096BF02` **before** MapleRoot's cave, so the cave never sets its impulse — this function writes it inline instead.
5. `SlumsBounce_ApplyImpulse` (:147-162) rewrites three immediates per skill and family: `kImpulseLeftVx 0x0096C00A+1`, `kImpulseRightVx 0x0096C021+3`, `kImpulseVy 0x0096C031+1` (`src/bounce_rules.h:178-180`). Defaults: MapleRoot Ninja `{-683,606,-688}`, Vertical `{-1,0,-1200}`, stock Flash Jump `{-350,350,-250}` (:192-194); job families 4xx and 14xx keep their native values.
6. Wire, LP 0x3737 (`kBounceZonesSyncOpcode`, `src/bounce_rules.h:132`): version 1 = `version(1), nativeBounce(4), n(2) ranges, m(2) flash ranges`, each pair `first/last` inclusive int32, capped at `kMaxZoneRanges 4096` (:134) and rejected unless `nativeBounce` is 0 or a Ninja Bounce. Version 2 = `version(1), count(1)=4`, then four `(leftVx,rightVx,vy)` in the order ninjaNative, ninjaOther, verticalNative, verticalOther, each ≤ `kMaxImpulse 5000` (:181-182). `BounceZones_HandleSync` (:185-229) detects v2 by `bytes[2] == kBounceImpulsesVersion` and on a malformed packet logs "Bounce: malformed ... keeping the current values/list" — it never clears state.
7. Built-in fallback list: 16 inclusive ranges (`src/bounce_zones_builtin.inc:3-18`), generated from the server's `bounce-zones.yaml` `jump_quests` by `tools/bounce/gen_client_zones.py` and checked against the server's golden packet (header note, line 2).

### weather

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/weather.cpp/.h`, `weather_profiles.inc`, `weather_palettes.inc` | Whole-map tint, night curve, sky colour, bare-sky mode, lightning | none | working |
| `src/weatherfx.cpp/.h`, `weatherwind.cpp` | Cloud sheets, rain sheets, dust, rainbow, fog, native snow, wind direction | none | working |
| `src/weatheraccum.cpp`, `weatherpuddle.cpp`, `weathersplash.cpp` | Snow drifts / footprints, growing puddles, ground splash | none | working |
| `src/weathersway.cpp`, `weather_object_policy.h` | Plants, trees, hanging signs, ropes and ladders bend in the wind | `[weather] sway` default true, `[weather] ropesway` default true (`src/weathersway.cpp:871-884`) | working |
| `src/weathersound.cpp` | Rain / wind ambience with a volume ramp | none | working |
| `src/weathermove.cpp` / `weathermove_disabled.cpp` | Slippery footing in snow | **none exists** | broken — the real implementation is not compiled |
| `src/weathercanvas.cpp/.h` | Shear/squash projection used by the sway | none | partial — pixel path behind a compile-time kill switch |
| `src/lamps.cpp` | Lamps light up as the map darkens | `[clienthax] mapobjguard` (unrelated but same file) | working |

1. Protocol: LP 0x373D, 26 bytes after the opcode — `minuteOfDay(2) msPerGameMinute(4) sky(1) flags(1) skyElapsedSec(4) tint r/g/b(3) rainbowSecsLeft(2)` with optional `palette(1) skyElapsedMs(4) skyToken(4)` (`src/weather.h:6-30`). `Weather_HandleWorldState` (`src/weather.cpp:588`) is receive-thread safe: `SetWorldState` (:455-520) only writes shadow state, clamping minute to 0..1439, `msPerGameMinute` to ≥100 ("a runaway clock is worse than a slow one"), `uSky >= SKY_COUNT` to clear, all-zero tint as "not sent" (black would multiply the map away). Flags: `FLAG_SNAP 0x01`, `FLAG_FROZEN 0x02` (GM froze the clock — stop the local copy), `FLAG_BARESKY 0x04` (`src/weather.h:109-118`).
2. `SKY_CLEAR..SKY_SANDSTORM` (9 values) **is** the wire enumeration, owned by `server/src/main/java/server/weather/WeatherProfile.java`; `PaletteId` (27 ids) is `WeatherPalette.java`'s order. `kProfiles[]` is `#include "weather_profiles.inc"` with `static_assert(_countof(kProfiles) == Weather::SKY_COUNT)` and name anchors on rows 0/2/4/7/8, so inserting a sky mid-table is a compile error (`src/weather.cpp:55-73`).
3. Sky → visuals: `Update_hook` (:2149) runs a tint pass that eases `boost`/tint with `NIGHT_CATCHUP_STEP` so a GM weather change fades rather than snaps; `WeatherFx::TickLightning` runs **before** the colour pass because lightning is folded into `Weather_EffectiveNight()` (:939) — deliberately not `Weather::NightLevel()`, since a storm at noon is dark. The colour is re-asserted every frame as `IWzGr2DLayer::color = Argb(1.0f, EffectiveNight)` because per-tile animators overwrite it otherwise; the gate is `if (!bNeutral || !g_bNeutral || bChasing || Lamps_HasLights())` (:2256), with `Lamps_HasLights` in it because `Lamps_Update` is the only driver of the photocell ramp — without it a daytime storm followed by clear left six of Henesys' nine lamps stuck at full brightness.
4. Per-map correctness instead of a map list: `FieldHasSky(CMapLoadable*)` (:861) reads the field's own property; `WeatherOwnsObjectColor(bank, group, category)` (`src/weather_object_policy.h`) declines signboards, `market`, `potal0/1` and `back` so animated emissive door glows keep the engine's colour, and strips a leading `ClassicFM_`.
5. Ground modules place sprites by passing `CreateAnimLayer` **no origin, no overlay, plain world coordinates**; both alternatives (parent to the avatar's under-face layer, place at the world camera) were tried and are wrong (`src/weatherfx.h`). `WeatherSplash_SetIntensity` (`src/weathersplash.cpp:302`) is driven from the 30 ms logic tick while `WeatherSplash_Frame` (:367) spawns every **rendered** frame, otherwise the spawn rate beats against the frame rate. `weatheraccum` seeds from `Weather::SkyElapsedSec()` so walking into a 10-minute snow map shows 10 minutes of drifts, and `ACCUM_MAX 70` (`src/weatheraccum.cpp:56`) is nudged one step per frame so growth and melt are the same code. `weatherpuddle` grows through 3 tiers x 3 variants, capped at 26, `PUDDLE_RISE_MS 75000`, `PUDDLE_DRY_MS 40000`, `PUDDLE_MIN_GAP 56` (`src/weatherpuddle.cpp:40-60`) — the gap replaced a 96 px slot lattice that made heavy rain read as a continuous pale strip.
6. Sway (`src/weathersway.cpp`): the sprite is sheared in pixels and switched between 11 frames (odd, so a true upright exists; 7 for sprites over `SWAY_BIG_PIXELS`), all padded to one size, one layer per frame toggling `visible` — never `RemoveCanvas`/`InsertCanvas` on an engine-owned layer. `BuildFrames` (:651) hides the original only after every replacement exists; frames are cached by WZ-path hash. `SwayKindEnabled` (:876-884) reads `sway`/`ropesway` once per process and logs the pair; `sway=false` leaves everything as drawn, `ropesway=false` leaves ropes/ladders alone while plants still bend.
7. Physics footing is present but dead: `WeatherMove_Frame` writes the engine's own friction table (`friction` indexes 15/16 of `CPhysicalSpace2D`, singleton `0x00BEBFA0`, accessor `0x00A45D42`), every write absolute from a captured stock value, never scaled in place (`src/weathermove.cpp:7-30`, `:152-168`, `WeatherMove_Frame` :194, `WeatherMove_Restore` :268). `src/CMakeLists.txt:102-103` compiles **`weathermove_disabled.cpp`** instead, whose whole body is `void WeatherMove_Frame() {}` / `void WeatherMove_Restore() {}` under the comment "Visual-only weather: never write the game physics table." Walk-speed scaling was removed from this design entirely and is now a real `-10 SPEED` server buff (`server/src/main/java/server/weather/WeatherDebuff.java`), which also shows in the stat window; both would slow a blizzard twice.
8. `weathercanvas`: `#define WEATHER_PIXEL_PATH_ENABLED 0` (`src/weathercanvas.cpp:37-40`); `WeatherCanvas_HasPixelPath` (:199-215) documents why — enabling the raw-pixel shear activates a branch that had never executed, and its first in-game test hung on Leafre; two `CanvasLock` unbalanced-unlock defects were fixed afterwards but "that is a hypothesis, not a verified result, and a hang is not an acceptable thing to ship on a hypothesis". `WeatherCanvas_Project` with `bWantPixels=false` uses `CopyEx` and gives identical geometry, which is what ships.
9. Teardown: `Weather_Shutdown` (:2297) calls `ReleaseField` (:1981) plus every module's `ReleaseResources`, empties the vectors and logs `WEATHER_RESOURCES_RELEASED`; `RestoreBack` re-arms on every `LoadMap` because resolution/zoom rebuilds do not pass through `LoadMap` (`src/weather.cpp:8-48`).

### mobdamage

| Files | What it changes on screen | config.ini switches | State |
|---|---|---|---|
| `src/mobdamage.cpp`, `src/mobdamage_patch.h` | Mob touch damage no longer *increases* when the player's defence is below the job/level standard | `[clienthax] lowdefensepenalty`, default false (`src/mobdamage.cpp:34-38`) — false = the fix is on | working |
| `src/mobhptag.cpp` | Mob HP percentage tag | none | working, but only in non-integration builds (`src/hook.h:201-208`) |
| `src/monsterbook*.cpp/.h` | Book: colour icons, 0-counters, art/HP/MP, all four tabs; drop % under each Dropping icon; Found In → world map; local search | none | working |
| `src/setitem.cpp/.h`, `src/tooltip_setitem.inl`, `src/setstatbonus.cpp` | SET EFFECT / PIECES EQUIPPED tooltip rows and a docked pieces tooltip; set bonus added to cached totals | none | working |
| `src/mountinfo.cpp/.h`, `mountinfo_layout.h`, `mountinfo_render.h` | Mount preview in the item tooltip, composed from the player's own avatar | none | working |
| `src/coloringprism.cpp/.h`, `coloringprism_hooks.cpp`, `coloringprism_layout.h`, `coloringprism_text.h` | Colour-tuning window for cash item 5782000 with live avatar preview | none | working |
| `src/weapontint.cpp/.h`, `weapontint_proto.h`, `weapontint_walk.h` | The worn cash weapon actually renders in the chosen hue | none | working |
| `src/dailycheckin.cpp/.h` | 7x4 daily check-in window | none | working |

1. `mobdamage` is a single branch rewrite. Stock `CalcDamage` reads `StandardPDD.img` (`0x00792F75`, table `0x00BEDF58`, 6 job classes x levels 0..200) and branches at `0x0079328D`; below the standard the subtraction goes **negative** and the "reduction" adds damage scaled by the player's stats (a level-200 admin with 32767 stats and 7 defence took ~900 from a snail of PADamage 12). `BelowStandardCave` (`src/mobdamage.cpp:23`) jumps from `0x0079328D` (site bytes `{0x3B,0x7D,0x1C,0x89,0x45,0x10,0x7C,0x5E}`, `src/mobdamage_patch.h:41`) and does `mov [ebp+0x10],eax; cmp edi,[ebp+0x1C]; jge resume; mov [ebp+0x1C],edi`, resuming at `0x00793295` — the standard is lowered to the player's PDD so the ≥ standard branch reduces by `(s*0.00125+0.28)*PDD`, never negative.
2. `AttachMobDamageMod` (:42-63) `Classify`s the site first; anything but the exact stock bytes logs "Mob damage: unexpected client bytes at 0x%08X; stock rule kept" and patches nothing. `KeepStockRule` (:34) reads `lowdefensepenalty` via `ParseIniBool`, default false, and when true logs "Mob damage: stock low-defence penalty kept ([clienthax] lowdefensepenalty=true)".
3. The addon windows share one pattern: **no new opcode detour**, one opcode pair, and routing through the shared dispatcher. Book query 0x3734 / result 0x3735 (`src/monsterbook.h:46-47`), set item 0x3726/0x3727 (`src/setitem.h:20-21`), weapon tint 0x372E/0x372F (`src/weapontint.h:101-102`), check-in 0x3732/0x3733 (`src/dailycheckin.cpp:88-89`), cash shop 0x3730/0x3731 (`src/cashshopwnd.h:20-21`). All sit in the server's private `0x372x`/`0x373x` block (`server/src/main/java/net/opcodes/RecvOpcode.java:25-29`, `SendOpcode.java:25-34`).
4. The book was closed by five single-branch writes, not by new drawing code: `kCardIconOwnedBranch 0x00864959` forced unconditional, `kCardCountSkip 0x00864E5A` NOP'd so the 0 counter draws, `kBasicInfoArtGate 0x00865F9C`, `kBasicInfoHpMpGate 0x00866551` (`src/monsterbook.cpp:56-59`, `LoadBook` `0x0068487C` hooked at :164). Drops (`src/monsterbook_drops.cpp:1204`) own `0x00861E5C`/`0x00865782`/`0x00866C2C`; found-in (`src/monsterbook_foundin.cpp:983`) owns three 5-byte call rewrites on `IWzCanvas::DrawTextA` `0x004277AD`; search (`src/monsterbook_search.cpp:6418`) owns `GetSlotCard` `0x00867A5B`, `OnButtonClicked` `0x00862009`, `Redraw` `0x00863DF1`. Drops must install before search because search reads the 16-per-page Dropping patch (`src/hook.h:209`).
5. Drop percentages are the only server-owned number: C2S `[byte 0][int mobId]`, S2C `[byte 0][int mobId][short n][n x (int itemId, int ppm)]` with ppm **after** the player's live rates, so percent = ppm/10000.0; the reply is cached per mob id and nothing is drawn until it lands (`src/monsterbook_drops.cpp:20-32`).
6. Weapon tint is runtime-only and deliberately transient: v83 has no dye system, so the weapon's WZ canvases are cloned, transformed in HSV and swapped into the tree only for the duration of the detoured `PrepareActionLayer(pAvatar)` (`src/weapontint.cpp:1-26`; `kAddr_CAvatar_PrepareActionLayer 0x00453AD1`, `kAddr_CAvatar_BuildFaceLayer 0x00453696`, :91-94). Recolouring the cached canvas in place (shared — would tint for everyone) and substituting inside `get_unknown`/`Ztl_variant_t::GetUnknown` (string compare on the hottest resource path) were both rejected.
7. The prism window owns one item id, `kItemColoringPrism 5782000` (`src/coloringprism.cpp:64`), and sends nothing on Cancel or the title chip; Confirm consumes the prism, RESET+Confirm sends an identity tint as a *restore* so the server may refuse without consuming. It installs no detour of its own — `AttachColoringPrismHostHooks` (`src/coloringprism_hooks.cpp:124`) hangs on the host's existing cash-item-use hooks (`get_consume_cash_item_type 0x004863D5`, `CDraggableItem_OnDropped 0x004EF140`) because a second detour there would break their owner.

## Data

Registry (all `HKEY_CURRENT_USER\Software\SlumsMS\...`), because the client rewrites its own `g0_` keys on field entry:

| Store | Root | Values | Written by |
|---|---|---|---|
| `Overlay` | `src/overlay.cpp:24` | `theme`, `scale`, `snap`, `<id>_on/_x/_y/_w/_h/_text/_alpha` (fraction x/10000) | `LoadPrefs` :88, `SaveLook` :110, `SaveLayout` :116 |
| `Overlay` | `src/uitour.cpp:22` | `tourSeen` (seen version) | `MarkSeen` :40 |
| `Overlay` | `src/damagefmt.cpp:26` | `damage_short` | `LoadPrefs` :57 / `AttachDamageFormatMod` :86 writes at :82 |
| `Layout` | `src/uilayout.cpp:43` | `w<world>_c<id>_<bar\|qs\|btn\|icons\|chat\|hp\|mp\|exp\|lv>` = packed signed offset | `Save` :87-101, read :515-531 |
| `Inventory` | `src/invresize.cpp:335` | `w<world>_c<id>` = `(cols<<8)\|rows` | `InvResize_OnLoadCharacter` :615 / drag commit |

Wire contract (client side → server side):

| Feature | Opcode | Sizes / notes |
|---|---|---|
| Weather | LP 0x373D | 26 bytes after opcode (`src/weather.h:6-30`); `server/.../SendOpcode.java:25` |
| Lamp preview | LP 0x373F | `src/packetdispatcher.cpp:88-92`; `SendOpcode.java:26` |
| Bounce zones | LP 0x3737 | v1 = 9 + n*8 + m*8 bytes; v2 = 1 + 1 + 4*12 (`src/bounce_rules.h:132-201`) |
| Boss damage rank | LP 0x3739 | `src/packetdispatcher.cpp:65` → `HudMeters_HandleDamageRank` |
| Cash shop | CP 0x3730 / LP 0x3731 | `src/cashshopwnd.h:20-21` |
| Check-in | CP 0x3732 / LP 0x3733 | `src/dailycheckin.cpp:88-93` |
| Set item | CP 0x3726 / LP 0x3727 | `src/setitem.h:20-21` |
| Weapon tint | CP 0x372E / LP 0x372F | `src/weapontint.h:101-102` |
| Monster book | CP 0x3734 / LP 0x3735 | `src/monsterbook.h:46-47` |

Stock exe addresses referenced by this document, all verified present in the owner's IDB (image base `0x400000`, exe sha256 `86e4e6be…ac90`): `0x008D80A4` status-bar Draw, `0x008DE7DA` HitTest, `0x0081E2C8`/`0x0081DB7E` inventory slot rect/point, `0x0081C6C9`/`0x0081DC20` item window create/draw, `0x00668B83` `CMob::OnHit`, `0x0079328D` CalcDamage branch, `0x00437DFB` damage-number format call, `0x0094F5A7` jump call, `0x0096BE38`/`0x0096BF0B` bound-jump routine/cave, `0x00BEBF98` `CUserLocal*`, `0x00BEBFA0` `CPhysicalSpace2D`.

WZ data these features need beyond stock v83, all shipped in `Custom.wz` and generated by `tools/wz/`: `Effect/WeatherParticles.img` items 5120995-5120999 (`add_weather_items.py`, `build_weather_particles.py`) because stock `weather/flower` is also Floral Fest and `weather/snow` is both Blizzard and Snow; `Effect/WeatherAccum.img`, `Effect/WeatherPuddle.img`, `Effect/WeatherSplash.img` (`build_weather_*.py`); sprites 10-13 of the eight `NoRed/NoBlue/NoViolet/NoCri` digit sets (`tools/wz/add_damage_glyphs.py`); `UI/UIWindowEx.img/ColorPrism`; `UI/UIWindowEx.img/DailyCheckin/backgrnd`.

## Switches

| Key | Section | Default | Read at | Effect when changed |
|---|---|---|---|---|
| `nativehud` | `clienthax` | 1 (**not documented** in `config.example.ini`) | `src/hud_native.cpp:390`, cached in a `static` | 0 keeps the stock status bar strip |
| `invresize` | `clienthax` | true (`config.example.ini:34`) | `src/invresize.cpp:548-556` | false patches nothing and logs at :677 |
| `doubletapjump` | `clienthax` | false (`config.example.ini:29`) | `src/bounce.cpp:231` | false still installs block zones + impulses, only the jump-call redirect is skipped |
| `lowdefensepenalty` | `clienthax` | false (`config.example.ini:39`) | `src/mobdamage.cpp:34-38` | true keeps the stock (adding) penalty |
| `settingswindow` | `clienthax` | true (`config.example.ini:48`) | `src/d3d9host.cpp:303` | false disables Ctrl+F10/F11 and the whole overlay draw gate (`src/d3d9host.cpp:162`) |
| `uitour` | `clienthax` | true (`config.example.ini:51`) | `src/d3d9host.cpp:304` | false never shows the tour, even once |
| `mapobjguard` | `clienthax` | true (`config.example.ini:56`) | `src/lamps.cpp:1498-1501`, read once | false lets a missing-art map object fail as stock |
| `sway` | `weather` | true (`config.example.ini:65`) | `src/weathersway.cpp:871-884`, read once | false → nothing bends |
| `ropesway` | `weather` | true (`config.example.ini:66`) | same | false → ropes/ladders stay as the map draws them, plants still bend |
| `d3d9host` | `graphics` | true (`config.example.ini:81`) | `src/d3d9host.cpp:297` | false → no ImGui at all: no HUD modules, no grips, no settings window; saved layout still applies |
| `adapterfallback` | `graphics` | true (`config.example.ini:82`) | `src/d3d9host.cpp:302` | device-creation retry across adapters |
| `uistate` | `debug` | 0 (**QA only**, not documented) | `src/uilayout.cpp:368` | 1 writes `slums-ui-state.json` every 500 ms for the client-mcp driver |

Non-ini switches: the damage-number toggle is the registry value `damage_short` (`src/damagefmt.cpp:61`), and every widget's visibility/size is registry state edited in the Ctrl+F10 window. Build switches are CMake options, all defaulting OFF (`CMakeLists.txt:3-18`); `scripts/build.ps1:14` turns on INTEGRATION, CASHSHOP_WINDOW, WEATHER, MOVEMENT_KEYS, MOUNT_INFO, DAILY_CHECKIN, SET_EQUIP, MONSTER_BOOK, COLOR_PRISM, ATTACK_DIAG and INV_RESIZE. `MAPLEROOT_HUD_METERS` and `MAPLEROOT_DAMAGE_FORMAT` are **not** in that list, so the EXP/DPS meters and the short damage numbers are absent from the default build.

## Invariants

- **Verify bytes before patching.** Every patch site classifies its stock bytes first and refuses on anything else: `AttachUiLayoutMod` (`src/uilayout.cpp:469`), `AttachMobDamageMod` (`src/mobdamage.cpp:42`), `AttachBounceMod` (`src/bounce.cpp:230`), invresize `TargetsMatch` (`src/invresize.cpp:675`). A mismatch is a log line, never a crash.
- **No new opcode detour.** Every addon window routes through `packetdispatcher.cpp`; only the dispatcher owns the receive path (`src/cashshopwnd.cpp:33`, `src/coloringprism.cpp` head).
- **Receive thread writes nothing shared but shadow state.** `SetWorldState` (`src/weather.cpp:455`), `CashShopWnd_HandleSync` (`src/cashshopwnd.cpp:3399` → `g_bWantOpen`) and `WeatherSound_SetWanted` (`src/weathersound.cpp:133`) only record; the main thread applies.
- **World coordinates for ground sprites.** `CreateAnimLayer` with no origin and no overlay is the only correct placement for splash/accum/puddle (`src/weatherfx.h`).
- **Absolute physics writes.** `WeatherMove_Frame` derives every value from a captured stock value; scaling in place would compound each frame (`src/weathermove.cpp:152-168`).
- **Engine layer colours are re-asserted every frame**, because per-tile animators overwrite `IWzGr2DLayer::color` (`src/weather.cpp:8-48`).
- **Never `RemoveCanvas`/`InsertCanvas` on an engine-owned layer** — sway toggles `visible` between pre-built frames (`src/weathersway.cpp` head).
- **Capacity is static-asserted.** `kMaxCells 120` must fit the Draw imm8 and the scroll-bias imm8 (`src/invresize_patch.h:108-111`); `_countof(kProfiles) == SKY_COUNT` with name anchors (`src/weather.cpp:58-73`).
- **Positions are fractions**, `kFrac = 10000`, always clamped back on screen (`src/overlay_policy.h:20`, `:59`; `src/uilayout_policy.h:31-91`).
- **Frozen means click-through.** `NoInputs` while frozen, and `g_bFrozen = true` at every session start (`src/overlay.cpp:91`, `:237`).
- **Per-character state keyed by world+character**, never by name: `w%d_c%u` (`src/uilayout.cpp:84`, `src/invresize.cpp:338`).

## Origin

| Area | Origin | Evidence |
|---|---|---|
| hud (vitals/native/overlay) | custom | first commit `11a9bde 2026-10-25 "Add an ImGui HUD with a movable HP/MP/EXP widget"`, `2ee0ead 2026-10-25` (hud_native); no entry in `docs/PROVENANCE.md` |
| hud meters | custom | `4e02c13 2026-10-28 "EXP rate and DPS HUD modules (ticket 118)"`; design notes `docs/research-118-122.md` |
| damagefmt | custom | `ebf96ce 2026-10-28` (ticket 119); glyph art added by this repo's `tools/wz/add_damage_glyphs.py` |
| uilayout | custom | `0247c3e 2026-10-25 "Move the stock status bar and quick slot with Edit UI (ticket 91)"`; design history `docs/160-ui-adjust-plan.md` |
| uitour | custom | `4764317 2026-10-27 "First-run Edit UI tour (1.6.1)"` |
| settings_overlay / d3d9host | custom | `bc58352 2026-10-25 "Host Direct3D 9 for Parsec diagnosis and an in-game settings window"` |
| invresize | **base-derived (third party)** | `docs/PROVENANCE.md:15` — ported from the community `invresize` mod, no licence file, owner confirmed public share 2026-10-25; this repo moved addresses/stock bytes into a testable header, logs instead of a message box, and added the `[clienthax] invresize` switch. First commit `997a0fc 2026-10-25` |
| bounce | custom on a MapleRoot base | `8cb9909 2026-10-21 "Add double-tap jump and server-driven bounce block zones"`; the skill-rewrite machinery it extends lives in `mapleroot/ijl15/SkillEdits/Activeskill.h` (MapleRoot companion, `docs/PROVENANCE.md:1-9`) |
| weather core / fx / accum / puddle / splash / sway / sound / wind / lamps | **modified** port | `6119130 2026-10-18 "Checkpoint accepted MapleRoot client integration and addons"`; `src/weather.cpp:8-48` lists exactly what was removed from the ported package: F6/F7/F8 hotkeys, the 14-entry hardcoded field list, the injected night-desert backdrop |
| weathermove | custom, **not built** | `src/CMakeLists.txt:102-103` compiles `weathermove_disabled.cpp`; `weathermove.cpp` is unreferenced by the build |
| weathercanvas | modified | `src/weathercanvas.cpp:37-40` kill switch added by this repo |
| tooltip / itemicon | modified | `5132b8d 2026-03-14 "Show equip stat delta"` (Kaentake-era, extended by `59f300e` SetEquip), `73025c9 2026-04-01 "Indicators for active/boss pet in inventory"` |
| mobdamage | custom | `48508d7 2026-10-25 "Stop low defence from adding damage when mobs touch you"` |
| monsterbook (4 files) | **base-derived (third party package)** | `docs/PROVENANCE.md:11` — ported from the MonsterBook Publish package, client half written for the Kaentake client mod, search-field art from the package; first commit `c5957f4 2026-10-20`; spec `docs/monsterbook.md` |
| coloringprism / weapontint | **modified** (package `after/` half) | `docs/PROVENANCE.md:13` — from the ColorPrism TrueColor Fix package; `coloringprism_hooks.cpp` and `weapontint_proto.h` are new here; window art and the 5782000 icon are BunsMS-authored procedural art (`assets/colorprism`, icon traced from GMS 5782000); first commit `719820f 2026-10-20` |
| setitem | custom | `59f300e 2026-10-20` |
| dailycheckin | **modified** port | `src/dailycheckin.cpp:1-26` — "adapted from the dailycheckin package: custom opcodes 0x3732/0x3733 and the shared packet dispatcher instead of its own ProcessPacket detour"; first commit `019ebc0 2026-10-19` |
| mountinfo | custom | `6119130 2026-10-18`; no provenance entry |
| cashshopwnd | custom | `6119130 2026-10-18`; `docs/PROJECT-STATUS.md` records it as "experimental, production integration off" at the time |
| mapobj guard (in `lamps.cpp`) | custom | `src/lamps.cpp:1496-1497` cites the `272000000` evidence in `src/mapobj_guard.h` |

Per the repository convention (`docs/architecture/base-vs-custom.md`), client Origin claims rest on `git log --follow` plus `docs/PROVENANCE.md`; the Cosmic diff tool does not cover client C++ or WZ data.

## Tests and QA

`ctest` targets that cover these areas (`tests/CMakeLists.txt`), plus what they actually assert:

| ctest name | add_test line | Covers |
|---|---|---|
| `overlay_hud_layout` | :82 | `src/overlay_policy.h` — fraction round-trip, on-screen clamping, `FontPixels`, `Snap`, `BarLabel` formatting |
| `uilayout_move_stock_ui` | :85 | `src/uilayout_policy.h` — `Pack`/`Unpack` clamp, `PixelOffset` vs the caller screen, `ClampWithin` |
| `uitour_first_run` | :88 | `src/uitour_policy.h` — `ShouldOffer` (version, delay), `Pulse` timing |
| `invresize_sites` | :36 / :38 | `invresize_patch.h` geometry + stock-byte classification; runs bytes-only without `${INVRESIZE_TEST_EXE}` |
| `mobdamage_low_defence` | :45 / :47 | `mobdamage_patch.h` `Classify` original/patched/unknown and the cave's relative jump |
| `weather_animated_portal_colors` | :100 | `weather_object_policy.h` — 15 exit codes over `WeatherOwnsObjectColor` / `WeatherSwayKindEnabled` |
| `weather_rope_sway_no_box` | :107 | rope/ladder sway leaves no clear box; needs `${MOVEMENTKEYS_TEST_CLIENT}` |
| `bounce_doubletap_and_zones` | :157 | `bounce_rules.h` — `ZoneSet` merge/`Contains`, both zone packet versions, malformed rejection, impulse caps, built-in list vs the server's golden packet |
| `hud_meters_exp_dps` | :160 | `hud_meters_policy.h` — `ExpTracker` across a level-up, `PerHour` gating, `DpsMeter` window and `damage<=0` |
| `damagefmt_short_numbers` | :168 | `damagefmt_policy.h` — `ShortDamage`/`EncodeGlyphs` thresholds and glyph mapping |
| `mapobj_guard_skips_missing_art` | :176 / :178 | `mapobj_guard.h` HRESULT classification; optional `${MAPOBJ_TEST_EXE}` |
| `mount_preview_layout`, `mount_full_frame_replacement` | :119 / :126 | `mountinfo_layout.h` fit maths; the second needs an exe |
| `setequip_wire_format` | :131 | set-item packet codec |
| `colorprism_wire_format`, `colorprism_item_text`, `colorprism_window_layout`, `colorprism_swap_walk` | :136-142 | tint packet, item tooltip text, window layout, walk frames |
| `expanded_quickslot_bounds` | :13 | `src/quickslot_patch.h` |

`scripts/build.ps1` configures with all the feature options on (:14), builds Release, then runs `ctest --test-dir $buildDir -C Release --output-on-failure` and throws on any failure; `-TestClient` additionally passes `-DMOVEMENTKEYS_TEST_CLIENT`, which is what enables the four WZ-backed tests above.

Must be confirmed by hand in a real client (nothing in `ctest` can):

1. Grips appear and drag only with `[graphics] d3d9host=1`; saved positions still apply with it off.
2. A moved Menu button still opens the menu; a moved chat input still takes focus and text.
3. Native HUD gauges drain in the same frame as the stock numbers would, and hiding it (`nativehud=0`) restores the stock strip.
4. Inventory resize: drag to 16x14, close and reopen, relog — same size; Cash tab (5) shows its slots; a locked cell refuses a drop.
5. Damage glyphs render at 10.000+ with all four colours (NoRed/NoBlue/NoViolet/NoCri) — a missing glyph must leave short numbers off rather than draw blanks.
6. A jump-quest map refuses Ninja and Vertical Bounce, and refusal costs no MP.
7. Weather: rain/snow/dust appear only outdoors, the clock keeps running while a GM freeze flag is set, and lamps follow a storm-then-clear transition (the Henesys regression).
8. Mob touch damage from a low-defence character never exceeds the mob's PADamage-derived range.

Not covered anywhere: the ImGui layer itself (no headless test), the weather visual result, cash-shop/check-in window layout, and any of the byte-patch sites against a *different* exe build — every test that "checks bytes" only checks the table, not the running process.

## Known issues

From `/root/work/STATUS-1.6.4.md`, client lane:

| Card | Sev | Bearing on this document |
|---|---|---|
| b251 | S2 | Rose Garden Abandoned Garden / Von Leon crash — fixed by `mobattackfix.cpp` (b251), **not** one of these seven areas; it is a `CMob` animation-cache EPointer bug, adjacent to `hud_meters`' `CMob::OnHit` detour only in that both patch `CMob`. Tracked in `docs/architecture/client/crash-guards.md`. |
| b270 | S3 | Launcher news `manifest.json` missing `news[]` — launcher lane, **out of scope** here. |
| b274 | S3 | Will's / Magnus's boss portals never send portal-enter (ticket 171); classification still inconclusive (client map data vs. script) — **unrelated to these seven areas**; nothing in `uilayout`/`invresize` touches portal activation. |
| e14 | later | In-game Slums Exchange window (tickers, order books, NX desk quote) — Client+Server, 1.6.4+/1.7.0, **no code yet**. It would be a new window in the `cashshopwnd`/`monsterbook` family: same `0x372x`/`0x373x` opcode-pair + dispatcher pattern. |
| L15 | later | "Client-side fixes and the bonds window" — the bonds (relationship) window is a new UI window in the same family and **not started**; owner will send fixes later. |

Documented as deferred in `docs/160-ui-adjust-plan.md` and still true in the code:

- The status bar moves **vertically only** — its art is 1920 px wide, wider than most screens (`docs/160-ui-adjust-plan.md:89`).
- ImGui modules draw **over** stock windows (inventory, stat window), because the overlay draws after the game in `EndScene` (`src/d3d9host.cpp:185`); hiding under stock UI needs walking `CWndMan::ms_lpWindow`, deferred to 1.7.0 (`:90`).
- Modules still use ImGui styling that only imitates stock window colours (`ApplyTheme` `src/overlay.cpp:123`); real stock-art modules are 1.7.0 (`:91`).
- The game menu still pops up at its stock place above the bar, not next to a moved Menu button (`:105`).
- Parsec device creation and the exit-time `C0000005` in `slums.dll` cleanup are parked (owner decision, `docs/160-ui-adjust-plan.md`).

NEW-FINDING: `config.example.ini` documents none of `[clienthax] nativehud` (`src/hud_native.cpp:390`, default **1**), the registry value `damage_short` (`src/damagefmt.cpp:61`), `[debug] uistate` (`src/uilayout.cpp:368`) or `[debug] attackdiag` (`src/attackdiag.cpp:212`) — a default-on behaviour (the native HUD replaces the stock bar) has no discoverable switch for players or QA.

NEW-FINDING: `src/CMakeLists.txt:102-103` compiles `weathermove_disabled.cpp`, so the entire 278-line footing implementation in `src/weathermove.cpp` is dead code with no config key to reach it — the feature is neither "off by config" nor "deleted", it is unreachable in every build this repo produces.

NEW-FINDING: `src/dailycheckin.cpp:26` instructs "AttachDailyCheckinMod() is the single install entry — call it from your hook installer", but that symbol does not exist and no `src/hook.h` entry calls it; the window is created lazily by `EnsureWindow` (`src/dailycheckin.cpp:393`) from the inbound snapshot (`:547`), and `SetItem_Tick` is likewise ticked from `src/bypass.cpp:266` with no installer. The stale comment invites a future editor to add a duplicate install path.

NEW-FINDING: `src/invresize.cpp:675-706` forces narrow mode by patching the only `m_bFull` read (`0x0081C4BE` → `xor eax,eax`), so the stock 4-tab "full" inventory view is unreachable whenever resize is on — a deliberate-looking trade-off that is not stated in `README.md`'s invresize section.

NEW-FINDING: `MAPLEROOT_HUD_METERS` and `MAPLEROOT_DAMAGE_FORMAT` are absent from `scripts/build.ps1:14`, so the EXP/DPS widgets and the short damage numbers — both shipped, both documented in `README.md` — are compiled out of the release build while `docs/research-118-122.md`/`119-120.md` describe them as delivered.

NEW-FINDING: `src/hud_meters.cpp:34` keeps `g_levelCounter` as a stand-in for the real level, and `:86` infers a level-up from `next != g_lastNext && exp < g_lastExp`; a GM level change or an EXP-table change without an EXP drop will be miscounted, and the EXP-rate module has no other level source.

## State

| Area | State | Evidence |
|---|---|---|
| hud (native + vitals) | working | default-on (`src/hud_native.cpp:388-392`), builds from the bar's own art (:397-432), test `overlay_hud_layout` + `hud_meters_exp_dps` |
| hud meters (EXP/DPS) | partial | code and tests complete, but `MAPLEROOT_HUD_METERS` is not enabled by `scripts/build.ps1:14` |
| overlay | working | `overlay_hud_layout`; shipped since 1.6.0; `g_bFrozen` reset every session is intentional |
| uilayout | working | `uilayout_move_stock_ui`; grips need `d3d9host`; stock-byte check with a logged fallback |
| uitour | working | `uitour_first_run`; gated by `[clienthax] uitour` |
| damagefmt | partial | logic and glyph baking exist, but the switch is registry-only and off by default, and the build option is not enabled in `scripts/build.ps1` |
| invresize | working | `invresize_sites`; `[clienthax] invresize` default true; tab-5 bug fixed in 1.6.0 |
| bounce (zones + impulses) | working | `bounce_doubletap_and_zones`; applied inside the MapleRoot detour (`mapleroot/ijl15/SkillEdits/Activeskill.h:563,620`) |
| bounce (double-tap) | partial | compiled and tested, but `[clienthax] doubletapjump` defaults **false** (`src/bounce.cpp:231`), so the path is unexercised in a default install |
| weather (sky/tint/fx/accum/puddle/splash/sway/sound) | working | `weather_animated_portal_colors`, `weather_rope_sway_no_box`; server side `server/weather/*` |
| weather (footing) | broken | `weathermove.cpp` not compiled (`src/CMakeLists.txt:102-103`) and no switch exists |
| weather (pixel shearing / cast shadows) | partial | compile-time kill switch `WEATHER_PIXEL_PATH_ENABLED 0` (`src/weathercanvas.cpp:37-40`); shadows have no implementation at all |
| mobdamage | working | `mobdamage_low_defence`; byte-classified site with a logged fallback |
| monsterbook / setitem / mountinfo / prism / tint / check-in / cash shop | working | one ctest each; all routed through `packetdispatcher.cpp` |

## Open questions

- Which byte addresses in this document are confirmed against the 1.6.4 exe specifically? The provenance note says every address was re-checked against sha256 `86e4e6be…ac90`, but that check is not reproducible on this host (no `MapleStory.exe`, no Windows toolchain).
- Do the v2 bounce impulses reach a client that has never received a v1 zone list? `BounceZones_HandleSync` (`src/bounce.cpp:185-229`) detects v2 by a byte offset, so a server sending only v2 would leave the built-in fallback list in force with server impulses — not verified against a live server.
- Is `weathermove` meant to ship at all? The disabled stub says "Visual-only weather", the 278-line implementation says footing is designed and claimed safe, and `WeatherDebuff.java` already covers the slow. Nothing in the repo records the decision to shelve it.
- Should `nativehud` be documented in `config.example.ini`, or is the intent that the native HUD is unconditional? Either way the current state (switch exists, undocumented) is unintentional-looking.
- Was the 4-tab "full" inventory view meant to survive resize? The forced narrow mode patch is the only `m_bFull` read in the client, so there is no half-measure available without more sites.
- Who owns `docs/research-118-122.md`'s claim that the meters "ship"? The code is gated behind a build option the release script does not set.
- `src/uilayout.cpp:379`'s `slums-ui-state.json` contract is consumed by an external QA driver (client-mcp); its field set is not versioned anywhere in this repo.
