# Custom.wz and the data tools

## Purpose

The v83 client renders its UI and effects out of `Data/*.wz`, files nobody in this project rebuilds. Everything the
SlumsMS client adds that is *data* — window art, item icons, damage glyphs, monster-book pages, mob attack-type
leaves, a world-map link — ships in one small PKG1 file, `src/Custom.wz`, mounted at runtime over the stock tree by
`src/resman.cpp` and merged into it. The tools under `tools/wz/` are the only sanctioned way to edit that file
(`AGENTS.md:181-182` in the server repo).

`src/Custom.wz` today: 925487 bytes, sha256 `c2c4268d03ad5e84b7d9d24effdb4a1788cb0c10b4ea43c191f946daa3126311`,
673 images — 662 `Mob/<id>.img` (72 bytes each, one `attackN/info/type` leaf) plus 11 others:

| Image | Nodes | Written by |
|---|---|---|
| `UI/UIWindow.img` | `SysOpt` (backgrnd 299x396) | inherited from Kaentake (`docs/KAENTAKE-UPSTREAM.md:5`) |
| `UI/UIWindowEx.img` | `MobHPRatioInfo`, `SkillCooldownNumber`, `DailyCheckin`, `MonsterBook`, `ColorPrism` | Kaentake + `add_daily_checkin_art.py`, `add_monster_book.py`, `add_color_prism.py` |
| `String/MonsterBookEx.img` | 652 mob entries | `add_monster_book.py` |
| `String/ToolTipHelp.img` | `Mapobject/{211040600,211060000}/0/Desc` | `set_lkc_tooltip.py` |
| `Item/Cash/0578.img` | `05782000/info` icon+iconRaw | `add_color_prism.py` |
| `Effect/BasicEff.img` | 8 sets (`NoRed0/1`, `NoBlue0/1`, `NoViolet0/1`, `NoCri0/1`), each with canvases `10`,`11`,`12`,`13` = `.` `K` `M` `B` (32 total) | `add_damage_glyphs.py` |
| `Map/WorldMap/WorldMap080.img` | `MapLink/2` (Grandis) | `add_worldmap_link.py` |
| `Map/Obj/acc10.img` | `timeTemple/pillar_old/0..10`, `timeTemple/door/10` | `add_time_temple_art.py` |
| `Skill/{000,1000,2000}.img` | `skill/0001007`, `10010007`, `20001007` (Maker) | `add_maker_skill.py` |
| `Mob/<id>.img` × 662 | `attackN/info/type` (703 leaves) | `add_mob_attack_type.py` (b251) |

The overlay is not a patch layer of its own: it is *merged into stock images at load time*, so a Custom.wz image
that also exists in `Data` is never served whole (`src/customwz_policy.h:5-12`).

## Entry points

| Where | What |
|---|---|
| `src/resman.cpp:108` | `void CWvsApp::InitializeResMan_hook()` — creates the `Custom` namespace, loads `Custom.wz`, indexes it |
| `src/resman.cpp:37` | `raw__OnGetLocalObject_hook` (NameSpace.dll) — the miss path: answers a failed stock lookup from Custom.wz |
| `src/resman.cpp:65` | `raw_Serialize_hook` (PCOM.dll) — merges Custom.wz leaves into the stock property as it serializes |
| `src/resman.cpp:206-211` | `GetAddressByPattern("NAMESPACE.DLL", "B8 ?? ?? ?? ?? E8 ?? ?? ?? ?? 81 EC 80")` and `("PCOM.DLL", "B8 ?? ?? ?? ?? E8 ?? ?? ?? ?? 83 EC 68")` + `ATTACH_HOOK` |
| `src/resman.cpp:260-263` | `AttachResManMod()` — the two `ATTACH_HOOK`s; declared `src/hook.h:57`, installed `src/hook.h:162-165` |
| `src/customwz_policy.h:19`, `:33` | `CustomWz::ImagePrefix()`, `CustomWz::TopDir()` — the policy the two hooks consult |
| `src/CMakeLists.txt:142-146` | POST_BUILD `copy_if_different` of `src/Custom.wz` into `build/Release` |
| `tools/wz/<tool>.py` | the writers; every one parses, patches and atomically replaces `src/Custom.wz` |
| `tools/wz/check_map_refs.py:417` | `main()` — read-only audit of the *stock* Data, not of Custom.wz |

## How it works

### A. Editing Custom.wz (the only allowed route)

`AGENTS.md` (server repo) `:181-182`: "`Custom.wz` changes only go through the `tools/wz` scripts, with semantic
checks that every other image is unchanged." Each writer follows the same sequence — `add_maker_skill.py` is
named as the model (`docs/sessions/EXTERNAL-AGENTS.md:197`):

1. **Build the wanted node in Python.** Art comes from `assets/` (`assets/colorprism/`, `assets/dailycheckin/UI.wz`,
   `assets/monsterbook/*.png`, `assets/worldmap/grandis-link.png`) or from the stock image itself
   (`add_time_temple_art.py:41` copies `pillar/<n>` → `pillar_old/<n>`).
2. **Pin the stock node by hash.** `add_maker_skill.py:35-39` holds the three stock skill hashes and
   `stock_nodes()` exits `stock {img}/skill/{sid} is not the GMS v83 node` (`:47`) on any drift;
   `add_worldmap_link.py:48` (`check_stock`) pins the stock links `{0:WorldMap144, 1:WorldMap179}`, the BaseImg
   origin `(320,235)` and `WorldMap180`'s parent.
3. **Snapshot every image's `semantic_hash` before touching anything.** `before = {p: semantic_hash(wz.image(p))
   for p, _ in wz.images()}` (`add_maker_skill.py:74`, `add_time_temple_art.py:59`, `add_mob_attack_type.py:155`,
   `add_monster_book.py:230`). `semantic_hash` is sha256 of `semantic()`: it drops the `'key'`/`'zlib'` encoding
   noise, replaces raw bytes with `{sha256,size}` and adds a `pixels` hash for canvases
   (`tools/wz/native_wz.py:378`, `:392`) — so two encodings of the same image compare equal.
4. **Round-trip check.** `write_img` → `parse_img` → `semantic_hash(reparsed) != semantic_hash(node)` →
   `sys.exit('... does not round-trip')` (`add_color_prism.py:110`, `add_time_temple_art.py:68`,
   `add_mob_attack_type.py:168-169`, `add_monster_book.py:253-254`).
5. **Write to a temp file, never in place.** `wz.put_image(path, blob, replace=...)`, `wz.save(tmp)` with
   `tmp = tempfile.mkstemp(suffix='.wz', dir=CUSTOM.parent)`; `Archive.save()` refuses to overwrite its input
   (`tools/wz/native_wz.py:526` "Never overwrite an input WZ") and re-checks directory/image layout drift
   (`:552`, `:570`). Images that were not touched are written back from the original bytes (`Archive.raw`,
   `:491`), which is why an edit does not re-encode the other 672 images.
6. **Semantic check that every *other* image is unchanged, then atomic replace.** Re-open the temp file and compare:
   - `add_maker_skill.py:105-109` `changed` (every `before` path not in `targets`), `wrong` (landed hash),
     `set(after)-set(before)-targets` — any non-empty → `os.unlink(tmp)` + exit. `os.replace(tmp, CUSTOM)` `:110`.
   - `add_time_temple_art.py`/`set_lkc_tooltip.py`/`add_worldmap_link.py`: `changed or set(after)-set(before)-{IMG} or after.get(IMG)
     != hash` (`add_time_temple_art.py:76-79`, `set_lkc_tooltip.py:75-78`, `add_worldmap_link.py:96-99`).
   - `add_monster_book.py:263-286`: per-image loop, dropped `Mob/` images must really be gone (`:270-272`),
     targets must land, and the *pre-existing* `UIWindowEx.img` children are compared one by one (`:280-286`).
   - `add_color_prism.py:117-130` / `add_daily_checkin_art.py:71-84`: for the image it extends, only the new node is
     excluded from the child-by-child comparison; everything else must hash equal.
   - Final step prints the new file sha256 (`add_color_prism.py:132-134`).
7. `--check` on every tool re-runs steps 1–3 and exits 1 with a one-line reason instead of writing
   (`add_color_prism.py:99-101`, `add_maker_skill.py:82-84`, `add_monster_book.py:245-249`, …). It is the
   pre-release gate; nothing in CI calls it.

`tools/wz/native_wz.py:1-3` is vendored unchanged from the BunsMS release tooling (`tools/wz_release/native_wz.py`),
needs pycryptodome, and is strict by design: PKG1/v83 only, unsupported tags, duplicate children and bad
lengths/offsets fail closed, "No network access, live-tree writes, automatic repairs or fallback fake nodes"
(`:4-11`). AES key/IVs `:25-26`.

### B. How the overlay reaches the client at run time

1. `CWvsApp::InitializeResMan_hook` (`src/resman.cpp:108`) runs the MapleRoot initializer when
   `MAPLEROOT_INTEGRATION` is on, else stock `CWvsApp::InitializeResMan` (`:110-114`).
2. It creates a writable root name space, a child `NameSpace` object named `Custom`, and keeps it as
   `g_pCustomNameSpace` (`:117-126`).
3. It initialises an `IWzFileSystem` on the game folder (`GetModuleFileNameA` + `Dir_upDir`, `:129-135`), takes
   `fs->item[L"Custom.wz"]` (`:140`), `pPackage->Init(L"83", L"Custom", pArchive)` (`:141`) and
   `g_pCustomNameSpace->Mount(L"/", pPackage, 1)` (`:142`). **There is no existence check on `Custom.wz`**
   (`docs/research-121-crash-report.md:23-24`, `:153-156`).
4. It walks the whole Custom namespace with an explicit stack of `_NewEnum`s and pushes every leaf path into
   `g_vecOverrides`, then sorts it (`:145-174`) — the lookup table the miss hook binary-searches.
5. It classifies every Custom.wz *image* (only paths where `ImagePrefix(p) == p`, `:186`) as stock or not:
   `sFile = <gamedir>\Data\<path>` and `sPackage = <gamedir>\<TopDir>.wz`; `bStock` if either exists
   (`:187-191`). Stock images go into `g_vecStockImages` (`:192-194`); the log line is
   `"Custom.wz: %ls %s"` with `merges into stock` / `is served whole` (`:198`) plus the counts (`:201`).
6. Two hooks are installed: NameSpace.dll's `raw__OnGetLocalObject` (`:206-207`) and PCOM.dll's
   `CWzProperty::Serialize` (`:210-211`), then `VerifyKaentakeHooks("Custom.wz serialization and namespace")`
   (`:213`) — a failed hook aborts start-up under `MAPLEROOT_INTEGRATION` (`src/mapleroot.cpp:30-35`).
7. **Miss path** (`raw__OnGetLocalObject_hook`, `:37-57`): a successful stock lookup always wins
   (`:44`); a miss is answered from Custom.wz only if the exact path is in `g_vecOverrides` (`:47`) **and** its
   image is not a stock image (`:52-55`). That guard is the fix for the MapleRoot double mount (game folder first,
   `./Data` second — `/root/work/client/src-164-data/mapleroot/ijl15/MapleHook.cpp:166-197`), which used to hand out Custom.wz's partial copy of
   `Effect/BasicEff.img` instead of the stock one (`src/customwz_policy.h:5-12`: rc3-rc5 damage digits missing
   `0`..`9`, 1.6.0.2 Arcane boss `Mob/<id>.img` info without `maxHP`).
8. **Merge path** (`raw_Serialize_hook`, `:65-104`): when a stock property with a Custom.wz counterpart
   serializes, the hook enumerates the Custom counterpart's children: a leaf/sub-property is `Add`ed
   (`:89` with `bNoReplace = false`, i.e. an existing stock value is replaced — `src/customwz_policy.h:10-11`); a
   property child is added *whole* only when the stock property has nothing at that name
   (`this->item[sNext]` throws → `vStock.vt == VT_EMPTY`, `:94-101`) — the 1.6.1-rc11 case,
   `Map/WorldMap/WorldMap080.img/MapLink/2`. Movement-key icons are injected from the same hook
   (`:69` → `src/movementkeys.cpp:164`).
9. `CWvsApp::CleanUp_hook` (`:221-257`) frees both vectors (`:244-245`) and `Unlink(L"Custom")` (`:249`), so a
   second session re-reads the file.

### C. Packaging into the patch layer

`Custom.wz` is not compiled; it is copied next to the DLL by `src/CMakeLists.txt:142-146` and then shipped as a
file. The server repo's staging tool takes it from there: `tools/release/stage-client:36-37`
(`WZ="$BUILD_DIR\\Custom.wz"` default), `:50-73` hashes `slums.dll`, `slums-build.txt` and `Custom.wz` **on the build
host** (read-only, `step 1` at `:50`), `:77` re-hashes after the copy (`step 2`), and `:95-138` writes into a copy
of the current layer with `rm -f slums.dll Custom.wz slums-build.txt` first so nothing is written through a link
(`:120`), then re-hashes and compares.
The allow-list has an `extra Custom.wz` row (`tools/release/client-allow.txt:49`). `tools/package_runtime.py` does
**not** cover `Custom.wz`: its baseline (`release/baseline.json`) lists only the addon `Data/` files, so a stale
Custom.wz in a runtime package is undetectable there.

### D. Tools table

| Tool | Purpose | Checks | When it must run |
|---|---|---|---|
| `tools/wz/native_wz.py` | strict v83 PKG1/IMG reader/writer + `semantic`/`semantic_hash` (`:378`, `:392`) | PKG1 magic, version, directory cycles, entry name validity/dupes, offsets, layout drift, never overwrite input (`:426-435`, `:440`, `:526`, `:552`, `:570`) | library; no CLI |
| `tools/wz/add_color_prism.py` | `UI/UIWindowEx.img/ColorPrism` (backgrnd/look 301x406, 3 slider tracks 176x8, `BtReset/{normal,pressed,mouseOver}/0` 47x18) + `Item/Cash/0578.img` (icon origin rewritten to `(0,h)`) | asset sizes `:41-44`, round-trip `:110`, per-child compare excluding `ColorPrism` `:117-123`, all other images `:124-126`, landed `:127-130` | when the prism art or item icon changes; `--check` before a client release |
| `tools/wz/add_daily_checkin_art.py` | copies `UIWindow.img/DailyCheckin` out of `assets/dailycheckin/UI.wz` into `UI/UIWindowEx.img/DailyCheckin` | backgrnd must be 513x346 (`:45`), round-trip `:64`, sibling compare `:71-81`, landed `:82-84` | when the check-in art changes |
| `tools/wz/add_damage_glyphs.py` | 32 canvases `Effect/BasicEff.img/<set>/{10,11,12,13}` = `.` `K` `M` `B`, drawn in each stock set's own gradient | needs `--stock-basiceff` (`:143`); compares only the rewritten `IMG` hash (`:158-163`), **no other-image compare** | whenever `MAPLEROOT_DAMAGE_FORMAT` ships; re-run if the stock glyph style changes |
| `tools/wz/add_maker_skill.py` | `Skill/{000,1000,2000}.img/skill/{0001007,10001007,20001007}` from a stock `Skill.wz` | stock node hash pinned (`:35-39`, `:47`), optional `--client-skill` "absent/stock/DIFFERENT" check (`:53-61`), round-trip, `changed`/`wrong`/extra (`:105-109`) | when the Maker skill must exist client-side (ticket 112); re-run if the client's own `Data/Skill` ever gains the node |
| `tools/wz/add_mob_attack_type.py` | one 72-byte `Mob/<id>.img` per mob holding `attackN/info/type`, from `tools/wz/mob_attack_type.json` (703 rows / 662 mobs) | refuses a type outside `(1,2)` (`:48`, `:149`); removes stale `Mob/` images (`:157`); `changed` (non-Mob), `wrong`, `extra` all empty (`:178-183`) | whenever `mob_attack_type.json` changes (`--scan <Data/Mob>` rebuilds it) — the b251 data fix |
| `tools/wz/add_monster_book.py` | `String/MonsterBookEx.img` (652 mobs: `map/<i>`, `reward/<i>`, `episode`) + `UI/UIWindowEx.img/MonsterBook/search/{item,mob}Box{0,1}` 122x17; **removes** every `Mob/<id>.img` | `format==1` (`:216`), every card mob has a page (`:221`), placeholder-map warning (`:224`), search art 122x17 (`:197-204`), per-image compare (`:263-275`), dropped mobs gone (`:270`), UIWindowEx siblings untouched (`:280-286`) | whenever the server's `drop_data` changes — rebuild with the server's `tools/monsterbook/build_book.py` first (`docs/monsterbook.md:44`) |
| `tools/wz/add_time_temple_art.py` | `Map/Obj/acc10.img/timeTemple/pillar_old/0..10` + `door/10`, copied from the stock image, for the nine newer maps (272000000, 17021, 18021, 321190500, 327090010, 327090320, 328000000, 328090100, 328090200) | `--stock` required (`:53`); refuses if stock already has them (`:43-44`); round-trip; other-image guard (`:76-79`) | once per stock Data set; `--check` after a Data refresh |
| `tools/wz/add_worldmap_link.py` | `Map/WorldMap/WorldMap080.img/MapLink/2` → `WorldMap180`, toolTip "Grandis", art origin `(102,3)` | `check_stock` pins links/origins/parent (`:48-60`), round-trip, landed, other-image guard (`:96-99`) | when the world-map link art or the stock page set changes |
| `tools/wz/set_lkc_tooltip.py` | replaces `String/ToolTipHelp.img/Mapobject/{211040600,211060000}/0/Desc` (the "must have at least 1 rebirth" warning) | optional `--stock` check that the warning is still there (`:39-47`), round-trip, other-image guard (`:75-78`) | once; re-check against a refreshed Data |
| `tools/wz/check_map_refs.py` | read-only audit: every map's `Back/<bS>.img/<no>`, `Obj/<oS>.img/<l0>/<l1>/<l2>`, `Tile/<tS>.img/<u>/<no>` resolves in the client's Data | classes `note`/`fixed`/`fatal` (`:205-370`); a missing node or originless canvas is a null deref on map load (`:1-21`); `--list` writes a crash list (`:478-493`) | after any Data change and before shipping maps that the client has never loaded |
| `tools/wz/make_prism_reset_button.py` | **art generator**: `assets/colorprism/BtReset/*.png` from the client's own `UI/Basic.img` (letters R E S E T from BtReceive/BtSend/BtDel) | self-check prints how far a re-painted CANCEL deviates from stock (`:125-128`) | only when the reset-button art must change; not part of a data edit |
| `tools/wz/make_worldmap_link_art.py` | **art generator**: `assets/worldmap/grandis-link.png` (86x90 at page 218,232) | none beyond `--preview` | only when that art must change |
| `tools/wz/test_add_mob_attack_type.py` | unittest for `classify`/`needs_type`/`mob_image` | circle+ball→2, circle→1, "no range" and "only `r`" reported not guessed, type 4 reported, written image holds only the leaf | manual only — **no test runner invokes it** |
| `tools/fm-tv/add_clip.py` | appends/replaces/drops a clip in `UI/MapleTV.img/TVmedia` (240x180 RGB565, format 513, origin `(0,180)`); the client plays `rand() % count` per map entry (`CNpc::Init 0x006D0F0A`, count at `CMapleTVMan+0x3C0`) | renumbers on `--drop` so `TVmedia/0..count-1` stay contiguous; prints file size | when the FM TV content changes; stages the *stock* `Data/UI/MapleTV.img` (allow-listed `client-allow.txt:44`), not Custom.wz |
| `tools/login-logo/set_logo.py` | replaces both frames of `Map/Obj/login.img/Title/logo/0` (397x219, origin `(198,180)`) with `assets/brand/slumsms-wordmark.png` | keeps stock delays/alpha; `LIFT,LEFT = 45,20` checked on the 800x600 login 2026-10-26 (`:22-25`) | when the brand art changes; stages the stock `Data/Map/Obj/login.img` |
| `tools/package_runtime.py` | builds the runtime zip (runtime files + `Client/Data/...` from the core archive + addon overlay + `config.ini` + `BASELINE.json`) | output must not exist (`:5`), Kaentake.dll hash (`:7`), core archive hash (`:8`), every addon file hash (`:9-10`), `..` traversal assert (`:17`), `testzip()` (`:26`) | on a runtime package build; **does not check `Custom.wz`** |

## Data

- `src/Custom.wz` (925487 B, PKG1 v83/GMS key). 673 images: 662 `Mob/` + 11 others (table above).
- `tools/wz/mob_attack_type.json` — 703 rows `{mob, attack, type}` over 662 mobs; the only source
  `add_mob_attack_type.py` reads. `--scan <Data/Mob>` rebuilds it, `--scan --merge` appends.
- `assets/monsterbook/monsterbook.json` — `format: 1`, `cards: 610`, `mobs: 1345`, `drop_rows: 29601`; produced by
  the **server repo's** `tools/monsterbook/build_book.py` from `drop_data`, `Map.wz` life and `Mob.wz` info.
  Re-run both when `drop_data` changes (`docs/monsterbook.md:44`).
- `assets/monsterbook/{itemBox0,itemBox1,mobBox0,mobBox1}.png` (122x17), `assets/colorprism/*` (`ColorPrism.img`,
  `0578.img`, `Cash.img`, `backgrnd*.png`, `track*.png`, `BtReset/*.png`, `authored-provenance.json`),
  `assets/dailycheckin/UI.wz`, `assets/worldmap/grandis-link.png`, `assets/brand/*.png`.
- Nodes the client reads back, and what a missing node degrades to:

| Node | Reader | If absent |
|---|---|---|
| `Effect/BasicEff.img/<set>/<10..13>` | `src/damagefmt.cpp:41` (probe loop `:37-50`) | `g_glyphs = 0`, short numbers stay off, log `Damage numbers: glyph %ls missing in Custom.wz` |
| `UI/UIWindowEx.img/DailyCheckin/backgrnd` | `src/dailycheckin.cpp:267` | falls back to `UI/UIWindow.img/DailyCheckin/backgrnd` (`:268`) |
| `String/MonsterBookEx.img` | `src/monsterbook.cpp:157-174` via StringPool 0x8BF (`kSpMonsterBookImg`, one `push 0x8BF` at `0x00684F71`) | `get_object_or_empty` probe fails → stock `String/MonsterBook.img` stays |
| `UI/UIWindowEx.img/MonsterBook/search/*` | `src/monsterbook_search.cpp:710-711` | field art missing |
| `UI/UIWindowEx.img/ColorPrism/{backgrnd,backgrndLook,track*,BtReset/*/0}` | `src/coloringprism.cpp:1012-1013`, `:244-246`, `:1030` | `LoadSprite` returns null |
| `UI/UIWindowEx.img/{MobHPRatioInfo,SkillCooldownNumber}` | `src/mobhptag.cpp:27,33`, `src/tempstat.cpp:14,17` | Kaentake behaviour |
| `UI/UIWindowEx.img` itself | not stock → served whole | — |

## Switches

There is **no** `config.ini` key that turns Custom.wz on or off: `grep GetPrivateProfile src/*.cpp` finds 14 readers
and none of them names the overlay. The overlay is unconditional once `slums.dll` runs, and `src/resman.cpp:140`
loads the file without checking it exists.

| Switch | Kind | Default | Where read | Effect on this subsystem |
|---|---|---|---|---|
| `MAPLEROOT_INTEGRATION` | CMake | OFF (`CMakeLists.txt:6`) | `src/resman.cpp:110-114`, `src/CMakeLists.txt:117` | OFF still installs both hooks; ON adds MapleRoot's mounts (the miss-order the policy guards against) plus `VerifyKaentakeHooks` |
| `MAPLEROOT_MOVEMENT_KEYS` | CMake | OFF (`CMakeLists.txt:4`) | `src/resman.cpp:68-70` | turns on the second injection that rides the Serialize hook (`InjectMovementKeyIcons`) |
| `MAPLEROOT_DAMAGE_FORMAT` | CMake | OFF (`CMakeLists.txt:16`) | `src/CMakeLists.txt:69-75` | compiles the reader of `Effect/BasicEff.img/*/10..13` |
| `MAPLEROOT_DAILY_CHECKIN`, `MONSTER_BOOK`, `COLOR_PRISM` | CMake | OFF (`CMakeLists.txt:8,10,11`) | `src/CMakeLists.txt:80,89,93` | compile the readers of the corresponding Custom.wz nodes |
| `[clienthax] mapobjguard` | config.ini | `true` (`/root/work/client/src-164-data/config.example.ini:56`) | `src/lamps.cpp:1496-1506` | the containment for missing map art (the reason `add_time_temple_art.py` exists), not a Custom.wz switch |
| `[clienthax] mobattackfix` | config.ini | `true` (`/root/work/client/src-164-data/config.example.ini:46`) | `src/mobattackfix.cpp:59-64` | the containment for the stock attack-info throw; the *data* fix is `add_mob_attack_type.py` |

Every `scripts/build.ps1:11` configure turns all the addon options **ON**, so a released DLL always contains the
readers — but they still degrade gracefully when the node is absent (table above).

## Invariants

1. **A stock image is never served whole.** `raw__OnGetLocalObject_hook` returns the stock miss unchanged when
   `ImagePrefix(path)` is in `g_vecStockImages` (`src/resman.cpp:52-55`); only the Serialize hook may contribute to
   it. Tested by `customwz_image_prefix` (`tests/customwz_policy_test.cpp:17-23`).
2. **`ImagePrefix` returns a prefix only for a real image path.** `len > 4 && ends with ".img"`, else `""`
   (directories and the bare `.img` name are not images) — `src/customwz_policy.h:19-30`.
3. **`TopDir` is the first path segment or `""`** — used to probe `<Top>.wz` in the game folder
   (`src/customwz_policy.h:33-37`, `src/resman.cpp:189-191`).
4. **Only leaf/value additions and *absent* sub-folders cross into a stock image.** A sub-folder stock already has
   is left to merge one level down; one it lacks is added whole (`src/resman.cpp:92-101`).
5. **Every Custom.wz edit is semantically verified against every other image before the file is replaced**
   (`add_maker_skill.py:105-109` and the per-tool variants); the replace is `os.replace` on a temp file in the same
   directory, so a crash mid-run leaves the old file intact.
6. **Stock nodes are pinned by hash** where reading them is the point (`add_maker_skill.py:35-39`,
   `add_worldmap_link.py:48-60`, `set_lkc_tooltip.py:39-47`) — a refreshed Data package fails the tool instead of
   silently baking the wrong art.
7. **`native_wz` fails closed**: no fallback nodes, no repairs, refuses to write over its input
   (`tools/wz/native_wz.py:4-11`, `:526`).
8. **Untouched images keep their original bytes** — `Archive.save` writes `Archive.raw(entry)` (`:568-571`), so an
   edit does not re-encode the other 672 images.

## Origin

| Part | Verdict | Evidence |
|---|---|---|
| `src/Custom.wz`, `UI/UIWindow.img/SysOpt` | **base (Kaentake)** | `docs/KAENTAKE-UPSTREAM.md:5`; `git log --follow src/Custom.wz` tail = `5cb4d8e 2026-01-21 iw2d "Add Custom.wz with expanded sysopt"`, `c9566b7`, `7ea4099 "Change Custom.wz structure"`, `a163e1d 2026-03-23 v3.3.0` |
| `src/resman.cpp` | **base (Kaentake)** | `4fd8045 2025-10-01 iw2d "Add resman.cpp to load Custom.wz and apply overrides"`; `docs/KAENTAKE-UPSTREAM.md`, README `:141` "Kaentake owns startup/resolution and the Custom namespace" |
| `src/customwz_policy.h` + the stock-image guard + sub-folder merge | **custom (ours)** | `fa25a42 2026-10-28 "Custom.wz: never serve a stock image whole; merge its leaves instead"`, `5068f3b 2026-10-29`; header comment `:5-12` names our rc3-rc5 / 1.6.0.2 regressions |
| `tools/wz/native_wz.py` | **vendored, unmodified** | `:1-3` "Vendored unchanged from the BunsMS release tooling (tools/wz_release/native_wz.py)" |
| `tools/wz/add_*.py`, `set_lkc_tooltip.py`, `check_map_refs.py` | **custom (ours)** | all authored `2026-10-20`…`2026-11-04`: `2ae71a3`, `a726657`, `c5957f4`, `50a2db8 "Maker skill nodes back in the client through Custom.wz (ticket 112)"`, `ebf96ce`, `5068f3b`, `210fb6c`, `a462b34`, `68caea7`/`117d43b` (b251), `3968c5b`/`0d417b3` |
| Monster Book addon (data + art) | **modified port** | `docs/PROVENANCE.md:11` "ported from the 'MonsterBook Publish' package … search-field art comes from the package's `reference/searchfields`" |
| Coloring Prism addon | **modified port + our art** | `docs/PROVENANCE.md:13` "`after/` client half of the 'ColorPrism TrueColor Fix'"; art "BunsMS-authored procedural art (`authored-provenance.json`; icon traced from the GMS 5782000 icon)" |
| `tools/wz/make_prism_reset_button.py`, `make_worldmap_link_art.py` | **custom (ours)** | `fd0556c 2026-10-21`, `5068f3b 2026-10-29`; `assets/colorprism/authored-provenance.json` "Original BunSMS procedural UI artwork; not recovered bundled ColorPrism art." |
| `tools/fm-tv/add_clip.py` | **custom (ours)** | `e1e6e9c`/`d572d89 2026-10-25` |
| `tools/login-logo/set_logo.py`, `assets/brand/*` | **custom (ours)** | `2533561`/`79e753c 2026-10-26`; `assets/brand/README.md:3` "Made for the owner (2026-10-26)" |
| `tools/package_runtime.py` | **custom (ours)** | `6119130`/`d82253c 2026-10-18` |

## Tests and QA

**Automated (native, `ctest` via `scripts/build.ps1:17-18`):**

| Test | Where | Covers |
|---|---|---|
| `customwz_image_prefix` | `tests/CMakeLists.txt:79-80`, `tests/customwz_policy_test.cpp` | `ImagePrefix` for digit paths, the image itself, nested `Item/Cash/0578.img/05782000/info/icon`, directories, `UI/.img/x`, empty; `TopDir` |
| `movementkeys_wz` | `tests/CMakeLists.txt:91-96` (needs `MOVEMENTKEYS_TEST_CLIENT` with `Data/UI/UIWindow.img`) | `InjectMovementKeyIcons` against a real client Data — the other consumer of the Serialize hook |
| `mapobj_guard_skips_missing_art` | `tests/CMakeLists.txt:172-178` | the containment for missing map art (needs `MAPOBJ_TEST_EXE`) |
| `colorprism_item_text` | `tests/CMakeLists.txt:138` | the item name that must match the server's `tools/colorprism/install_wz.py` |
| `damagefmt_short_numbers` | `tests/CMakeLists.txt:168` | the formatter that produces the glyph codes `10..13` |
| `movementkeys_wz`, `mount_*`, … | 47 `add_test` calls total | — |

**Not covered by any automated test:**

- No `add_test` runs *any* Python tool. `tools/wz/test_add_mob_attack_type.py` is a unittest that nothing invokes;
  `check_map_refs.py` is read-only and manual.
- No test asserts that `src/Custom.wz` on disk contains the nodes the C++ expects — the DLLs degrade to stock, so a
  stale Custom.wz is invisible to `ctest`.
- `scripts/build.ps1` never invokes a WZ tool and never hashes `Custom.wz`; `tools/package_runtime.py` does not
  cover it; `tools/release/stage-client` hashes the file it is handed but does not compare it to the build output.
- The *runtime* behaviour of the merge (a stock image with a Custom leaf, a whole folder added, the "served whole"
  case) is only in `src/resman.cpp`; `tests/customwz_policy_test.cpp` tests the policy functions, not the hooks.

**A real client must confirm by hand:**

1. Damage numbers: with `MAPLEROOT_DAMAGE_FORMAT`, hit something for ≥ 1000 and check `.`/`K`/`M`/`B` render in the
   set's own gradient and that plain `0`..`9` are unchanged (the regression `src/customwz_policy.h:7-8` describes).
2. Color Prism: open the window (art from Custom.wz), press RESET, and look at 05782000 in the cash inventory.
3. Daily Check-In: the 513x346 window draws with the right backdrop; the stock fallback is not used.
4. Monster Book: open a page for Lucid 8880150 / Will 8880302 / Verus Hilla 8880415 and check HP still prints (the
   1.6.1-rc7 `defaultHP` regression, `docs/monsterbook.md:41`); open the search tab and see the two 122x17 boxes.
5. b251: enter Rose Garden Abandoned Garden 211080100 and Von Leon's castle with `mobattackfix=false` to prove the
   `Mob/<id>.img/attackN/info/type` leaves are what removes the throw.
6. Time Temple 272000000 and the eight newer maps: pillars render (with `mapobjguard=false` to prove the art, not the
   guard, is what fixes it).
7. World map (Temple of Time page): the Grandis link appears and clicking it moves to `WorldMap180`.
8. Login screen: the SlumsMS wordmark sits where the MapleStory logo sat.
9. `--check` on every tool reports "already has" / exit 0 against the shipped file.

## Known issues

| Card | What | Client-side relevance |
|---|---|---|
| b251 (`/root/work/STATUS-1.6.4.md:72`) | crash entering Rose Garden 211080100 / Von Leon's castle | fixed in data by `tools/wz/add_mob_attack_type.py` (`68caea7`/`117d43b`, 2026-11-04) plus the `mobattackfix` guard; owner deferred the remainder to another patch |
| b270 (`:75`) | launcher news: Live publish wrote `manifest.json` without `news[]` | launcher packaging, not Custom.wz — but the same publish path ships `Custom.wz`, so a bad manifest and a stale WZ would fail together |
| b274 (`:76`) | Will's and Magnus's boss portals never send portal-enter | triage ticket 171 inconclusive; no WZ tool involved |
| L15 (`:106`) | client-side fixes and the bonds window | later lane |
| e14 (`:124`) | in-game Slums Exchange window | later, 1.6.4+/1.7.0 |

`NEW-FINDING:` `src/resman.cpp:140` — `fs->item[L"Custom.wz"]` is used with no existence check; a missing or renamed
`Custom.wz` therefore throws from the item getter (whether it throws is marked UNVERIFIED in
`docs/research-121-crash-report.md:156`) instead of producing a named `MISSING_ASSET Custom.wz` dialog
(`docs/research-121-crash-report.md:153-156`, `:266`).

`NEW-FINDING:` `tools/wz/add_damage_glyphs.py:157-171` — this is the one writer that does **not** run the
every-other-image comparison. It rewrites the whole `Effect/BasicEff.img` in Custom.wz and only compares that image's
own hash. Because `write_img` is called on a freshly built node, any other child of `Effect/BasicEff.img` that a
future version of the tool forgets to carry would be dropped silently. It is correct today (the Custom image holds
only the 32 glyph canvases), but it violates invariant 5 and would not notice.

`NEW-FINDING:` `tools/wz/add_damage_glyphs.py` needs `--stock-basiceff` on **every** run, including `--check`, and
`add_time_temple_art.py:53` needs `--stock`; there is no recorded path or hash for those stock inputs anywhere in the
repo, so a `--check` run cannot be reproduced from repo contents alone.

`NEW-FINDING:` `tools/wz/test_add_mob_attack_type.py` is not registered in `tests/CMakeLists.txt` and no script runs
it; the classifier that decides which 703 leaves go into the shipped file has no automated gate.

`NEW-FINDING:` `release/baseline.json` does not list `Custom.wz`, so `tools/package_runtime.py:7-10` cannot detect a
stale overlay in a runtime package; the only hash of the overlay is the one `tools/release/stage-client` prints at
stow time.

`NEW-FINDING:` `tools/wz/add_mob_attack_type.py` removes **every** `Mob/` image that is not in the wanted set
(`:157`), and `add_monster_book.py:238` removes all `Mob/` images unconditionally. The two tools therefore keep
deleting each other's domain unless run in the order monster-book → attack-type: `add_mob_attack_type.py` would drop
nothing of the book (it owns all `Mob/`), but a later `add_monster_book.py` run wipes all 662 attack-type images and
the b251 fix silently disappears from the file.

`NEW-FINDING:` `docs/research-119-120.md:327` still lists "whether the merge reaches `Effect/BasicEff.img/No*`
sub-properties" as an open question, while `src/customwz_policy.h:10-11` and `src/resman.cpp:92-101` now answer it
(added, or replaced since `bNoReplace` is false); the doc is stale.

## State

**working, with two cuts of the pipeline that are not automated.**

Evidence: the overlay loads and merges on every start (`src/resman.cpp:108-214`, `:37-104`), the policy that keeps
stock images whole is implemented and unit-tested (`src/customwz_policy.h:19-37`, `customwz_image_prefix` in
`tests/CMakeLists.txt:79-80`), all 673 images are present in `src/Custom.wz` (sha256
`c2c4268d…a3126311`), every writer round-trips, verifies the other images and replaces atomically, and the file is
copied into `build/Release` by the build (`src/CMakeLists.txt:142-146`) and staged by
`tools/release/stage-client:36-37,50-123`.

The b251 data fix is **partial by construction**: the `Mob/<id>.img` leaves are in the file and switchable only by
shipping or not shipping them (`mobattackfix` is the code-side switch, `/root/work/client/src-164-data/config.example.ini:46`), and the owner
deferred the card's remainder (`/root/work/STATUS-1.6.4.md:72`). The attack-type classifier has no test gate, and no Python tool
runs in CI, so "the file is up to date" is only ever known by a human running `--check`.

## Open questions

1. Does `resman.cpp:140` throw, return null, or silently mount an empty package when `Custom.wz` is missing? Marked
   UNVERIFIED in `docs/research-121-crash-report.md:156`; nothing in the repo settles it.
2. Does `this->Add(name, value, false)` with `bNoReplace=false` really *overwrite* an existing stock value, or only
   add? `src/customwz_policy.h:10-11` asserts overwrite; the guidebook's book-page test
   (`docs/guidebook/CLIENT-STEP.md:42-47`) was written precisely because this was unknown, and no test answers it.
3. Which tool owns `Mob/<id>.img` — the attack-type leaves (662 images today) or the book's (removed)? The two tools
   delete each other's output; no document states the order.
4. `tools/wz/native_wz.py` is vendored "unchanged" from `tools/wz_release/native_wz.py`, which is not in this
   repository. There is no recorded sha256 for the upstream copy, so "unchanged" cannot be re-verified here.
5. `add_maker_skill.py` pins three stock `Skill.wz` node hashes and `add_monster_book.py` references the repack's
   `String/MonsterBook.img` sha256 `f808835b…` in prose (`:35-37`); neither is checked against the Data package that
   actually ships, and that package stays external (`docs/PROVENANCE.md:9`).
6. Should `Custom.wz` be added to `release/baseline.json` so `tools/package_runtime.py` can gate it? Nothing records
   a decision either way.
