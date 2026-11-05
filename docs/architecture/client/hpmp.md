# HP/MP (4-byte stats, 99,999)

## Purpose

Stock v83 stores and sends HP, MaxHP, MP and MaxMP as 16-bit values capped at 30,000. SlumsMS 1.6.0 reads and writes
them as 32-bit values capped at 99,999, on both sides of the wire:

- client: `src/hpmp.cpp` + `src/hpmp_patch.h` rewrite 87 sites inside `MapleStory.exe` so the nine packet fields are
  read with `CInPacket::Decode4`, the four `GW_CharacterStat` slots hold a raw `int`, and the ~57 places that
  truncate a read-back value to 16 bits stop truncating (`src/hpmp_patch.h:23-25`, `README.md:93`).
- server: the nine fields are written with `writeInt`
  (`src/main/java/tools/PacketCreator.java:168-171`, `:1011-1013`, `:1056`, `:1074`) and clamped to
  `GameConstants.MAX_HPMP = 99_999` (`src/main/java/constants/game/GameConstants.java:34`).

The contract is a hard coupling: the server has no per-client branch
(`src/main/java/tools/PacketCreator.java:166-167`), so a client and a server that disagree on the field width
mis-parse every packet from the first HP field onward (`docs/release/1.6.0-hpmp-4byte.md:11-13`).

## Entry points

| Where | What |
|---|---|
| `src/hook.h:73` | `void AttachHpMpMod();` — declaration |
| `src/hook.h:74` | `void HpMp_Verify();` — declaration |
| `src/hook.h:147-148` | install call; unguarded (the `#ifdef MAPLEROOT_INTEGRATION` at `:145` closes before it), so it runs in every build |
| `src/hook.h:303` | `HpMp_Verify()` — last call of `AttachClientHooks()`, after every other mod |
| `src/hpmp.cpp:53` | `void AttachHpMpMod()` — verify-then-patch |
| `src/hpmp.cpp:75` | `void HpMp_Verify()` — post-install re-check |
| `src/hpmp.cpp:20` | `int __fastcall FakeTear(int value, int* slot)` — replacement for `_ZtlSecureTear<short>` |
| `src/hpmp.cpp:27` | `int __cdecl FuseShort_hook(const void* slot, unsigned int checksum)` — detour body |
| `src/hpmp.cpp:40` | `void RefuseToStart(const char* problem, uint32_t va, const char* what)` |
| `src/hpmp_patch.h:60-152` | `constexpr Site kSites[]` — the 87-row table |
| `src/hpmp_patch.h:198` | `SiteState Classify(const Site&, const unsigned char*, uint32_t)` |
| `src/CMakeLists.txt:21` | `hpmp.cpp` compiled into the `injector` target (= `slums.dll`) |
| `src/bypass.cpp:434-436` | CP `0x3738` build stamp sent before `CP_CheckPassword` (`src/buildstamp_proto.h:23`) — the number the server gate reads |

## How it works

**Wire shape (old vs new).**

| Field | Stock (v83) | SlumsMS 1.6.0 | Server writer |
|---|---|---|---|
| GW_CharacterStat HP | `writeShort` / `Decode2` | `writeInt` / `Decode4` | `src/main/java/tools/PacketCreator.java:168` |
| GW_CharacterStat MaxHP | short | int | `:169` |
| GW_CharacterStat MP | short | int | `:170` |
| GW_CharacterStat MaxMP | short | int | `:171` |
| SET_FIELD plain map change HP | short, after map(4)+portal(1) | int | `:1056` (and `:1074` for the spawn-point variant) |
| STAT_CHANGED mask 0x400/0x800/0x1000/0x2000 | short | int | `:1011-1013` via `isFourByteHpMpStat` (`:1029-1031`) |

Unchanged and deliberately still 2 bytes (`docs/release/1.6.0-hpmp-4byte.md:29-30`): party door record (party result
`0x25` shorts), remote ranged attack shot point, `CUserRemote::OnReceiveHP` (already `int,int`,
`src/main/java/tools/PacketCreator.java:3967-3972`), and the incoming heal over time CP `0x59`
(`src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:46`, `:64`;
`src/main/java/net/opcodes/RecvOpcode.java:110`; client side `src/hpmp_patch.h:20-21`).

**Storage change (why the stats become raw ints).** `GW_CharacterStat` keeps each of the four stats as a
`ZtlSecure<short>` — 4 data bytes plus a checksum byte at +4 (`src/hpmp_patch.h:9-11`), slots HP +0x61, MaxHP +0x69,
MP +0x71, MaxMP +0x79 (`src/hpmp_patch.h:9`, and the slot immediates `0x61/0x69/0x71/0x79` re-asserted in
`tests/hpmp_patch_test.cpp:127-130`). Every store goes through `_ZtlSecureTear<short>` at `0x004E80EB`
(`src/hpmp_patch.h:41`); all 17 call sites are redirected to `FakeTear`, which writes the whole 32-bit value and
returns checksum 0 (`src/hpmp.cpp:20-23`, table rows `src/hpmp_patch.h:72-88`). `_ZtlSecureFuse<short>` at
`0x004746DD` (`src/hpmp_patch.h:42`) is detoured: checksum 0 returns `*(const int*)slot`, anything else calls the
stock function (`src/hpmp.cpp:27-31`). Stock `Fuse` leaves the upper half of `eax` undefined, which is why the
detour exists rather than relying on the stock return (`src/hpmp.cpp:25-26`).

**Install sequence.** Numbered, as executed:

1. `AttachHpMpMod` (`src/hpmp.cpp:53`) is called from `AttachClientHooks` after `AttachClientBypass`
   (`src/hook.h:133-134`) and *before* `InitializeMapleRoot` (`src/hook.h:150`), so the site check sees stock bytes
   rather than another mod's bytes (`src/hook.h:146`). The call at `src/hook.h:148` sits outside every `#ifdef`.
2. Fuse entry check: `memcmp(0x004746DD, kFuseEntry, 8)`, expected `55 8B EC 51 51 8B 45 08`
   (`src/hpmp.cpp:56-58`, constant `src/hpmp_patch.h:47`). Mismatch ⇒ refuse `"found unexpected bytes (another
   hook?)"`.
3. Every table row is `Classify`d and must be `SiteState::Original` (`src/hpmp.cpp:59-63`); otherwise refuse
   `"found unexpected bytes"` at that VA. Nothing has been patched at this point (`src/hpmp.cpp:55`).
4. `ATTACH_HOOK(g_fuseShort, FuseShort_hook)` (`src/hpmp.cpp:64-66`) — the macro at `src/hook.h:8-13` wraps
   `AttachHook(&target, detour)` and returns the bool that is tested; failure ⇒ refuse `"could not hook"`.
5. All 87 rows are patched with `PatchMemory(va, PatchedBytes(s, &FakeTear, b), s.len)` (`src/hpmp.cpp:67-71`;
   `PatchMemory` at `src/hook.cpp:157`, declared `src/hook.h:328`); `PatchedBytes` at `src/hpmp_patch.h:174-194`.
6. `DebugMessage("HP/MP: 4-byte HP/MP up to %d (%u sites)", ...)` (`src/hpmp.cpp:72`).
7. Every other mod installs (`src/hook.h:149-302`), then `HpMp_Verify` (`src/hook.h:303`) — the last statement of
   `AttachClientHooks` (`src/hook.h:304`): the Fuse entry must now
   start `0xE9` (`src/hpmp.cpp:77`) and every row must classify `Patched` (`src/hpmp.cpp:78-82`), else refuse
   `"lost its hook"` / `"was overwritten by another patch"`.
8. Any refusal path prints a message box — "SlumsMS cannot start: the 4-byte HP/MP patch %s at 0x%08X (%s). …
   This client would misread HP and MP from the server." — and calls `ExitProcess(1)` (`src/hpmp.cpp:40-49`).

**Patch forms per row kind** (`src/hpmp_patch.h:174-194`):

| Kind | Count | Rows | Before → after |
|---|---|---|---|
| `Decode` | 9 | `src/hpmp_patch.h:62-70` | `E8 rel32` to `CInPacket::Decode2` (`0x0042470C`, `src/hpmp_patch.h:39`) → `E8 rel32` to `Decode4` (`0x00406629`, `:40`) |
| `Tear` | 17 | `src/hpmp_patch.h:72-88` | `E8 rel32` to `0x004E80EB` → `E8 rel32` to `&FakeTear` |
| `Widen` | 57 | `src/hpmp_patch.h:90-146` | `0F BF modrm …` → `8B modrm … 90`; or `66 85/3B modrm` → `85/3B modrm 90` |
| `Cap` | 4 | `src/hpmp_patch.h:148-151` | imm32 `30 75 00 00` (= 30000) → `9F 86 01 00` (= 99999) |

`Decode` rows are the nine wire fields: `GW_CharacterStat::Decode` HP/MaxHP/MP/MaxMP (`0x004E2B9A/BAE/BC2/BD6`),
`CStage::OnSetField` HP (`0x0077621A`), and `DecodeChangeStat` HP/MaxHP/MP/MaxMP (`0x004E30DA/F4`,
`0x004E310E/128`) — `src/hpmp_patch.h:62-70`, matching the server's nine fields above. `Widen` rows are the 16-bit
read-backs of the four slots (stat window `CUIStat::Draw`/details `0x008CBFDB…0x008CE007`, status bar
`0x008D822D/237`, party HP `0x009203F7`, pet HP/MP `0x0095BA30`/`0x0095BC7B`, level-up `0x0078D383/D39E`,
`DoActiveSkill` restore `0x00967B94`/`0x00967BA2`), derived by tracing every Fuse of the four slots (69 reads,
`src/hpmp_patch.h:23-25`). `Cap` rows raise the four 30,000 immediates (`src/hpmp_patch.h:148-151`): `sub_78D46C`
level-up gains (`0x0078D8D2`), `sub_77EC9F` stat calc (`0x0077F1A0`), stat-window AP→MaxHP/MaxMP
(`0x008CD657`, `0x008CD6EB`).

**Build-number rule.** `slums.dll` carries build `major*10000 + (minor+1)*100 + patch*10`
(`cmake/BuildStamp.cmake:25`, e.g. 1.6.2 → 10720, 1.6.4 → 10740), written to `slums-build.txt` next to the DLL
(`cmake/BuildStamp.cmake:13`). The 10600 in the gate is the pre-1.6.0 formula's number for 1.6.0
(`major*10000 + minor*100 + patch`, recorded at `cmake/BuildStamp.cmake:6`); under the current formula 1.6.0 would be
10700, so 10600 survives only as the historical floor that the 1.6.0 gate and `AGENTS.md:172` refer to. The repo rule:
no build without the patch may use a number ≥ 10600 (`AGENTS.md:172`). The stamp is sent as CP `0x3738` format 2
before the password packet (`src/bypass.cpp:433-436`, `src/buildstamp_proto.h:23-24`, `:34`); a client without the
packet counts as build 0 (`src/buildstamp_proto.h:12`).

**What breaks when the two sides disagree.** The server writes 4 bytes unconditionally
(`src/main/java/tools/PacketCreator.java:166-167`). An old client reads 2, so the stream desynchronises at the first
HP field and stays shifted: wrong stats, a broken character list, probably a crash on login or first map change
(`docs/release/1.6.0-hpmp-4byte.md:11-13`). The inverse — a 10600+ client against a server that still writes shorts —
is not detected at all: the client reads 4 bytes from a 2-byte field, same shift in the other direction, and no
message-box guard covers it (see Known issues). The only protection is the login gate: `MIN_CLIENT_BUILD`
(`src/main/java/config/ServerConfig.java:61`) enforced by `ClientVersionGate.outdated`
(`src/main/java/net/server/handlers/login/ClientVersionGate.java:47-53`), which refuses by sending the notice then
`getLoginFailed(SILENT_LOGIN_FAILED = 1)` (`:120-121`, constant `:32`; `message()` at `:67-70`).

## Data

| Item | Value | Source |
|---|---|---|
| `kAddr_Decode2` | `0x0042470C` (`CInPacket::Decode2`) | `src/hpmp_patch.h:39` |
| `kAddr_Decode4` | `0x00406629` (`CInPacket::Decode4`) | `src/hpmp_patch.h:40` |
| `kAddr_TearShort` | `0x004E80EB` (`_ZtlSecureTear<short>`) | `src/hpmp_patch.h:41` |
| `kAddr_FuseShort` | `0x004746DD` (`_ZtlSecureFuse<short>`) | `src/hpmp_patch.h:42` |
| `kMaxValue` | `99999` | `src/hpmp_patch.h:43`† |
| `kStockCap` | `30000` (declared, never referenced) | `src/hpmp_patch.h:44` |
| Fuse entry bytes | `55 8B EC 51 51 8B 45 08` | `src/hpmp_patch.h:47` |
| `kSiteCount` | 87 (9 Decode + 17 Tear + 57 Widen + 4 Cap) | `src/hpmp_patch.h:154`, verified in `tests/hpmp_patch_test.cpp:55-57` |
| Slot offsets | HP +0x61, MaxHP +0x69, MP +0x71, MaxMP +0x79 (`ZtlSecure<short>`, checksum +4) | `src/hpmp_patch.h:9-11` |
| Image base / exe identity | `0x400000`, `MapleStory.exe` sha256 `86e4e6be…ac90` | `src/hpmp_patch.h:30` |
| Server cap | `MAX_HPMP = 99_999` | `src/main/java/constants/game/GameConstants.java:34` |
| Stat masks | HP 0x400, MaxHP 0x800, MP 0x1000, MaxMP 0x2000 | `src/main/java/client/Stat.java:34-37`, mirror `src/main/java/tools/PacketCreator.java:1028-1031` |
| Send opcode | `SendOpcode.STAT_CHANGED` (`updatePlayerStats`) | `src/main/java/tools/PacketCreator.java:981-984` |
| Client build stamp | CP `0x3738`, format 2, then `build(4)`, release string, `caps(4)` | `src/buildstamp_proto.h:23-24`, `:33-38`, sent `src/bypass.cpp:433-436` |

† `kMaxValue` and `kStockCap` are the last two constants of the same block (`src/hpmp_patch.h:43-44`), after the four
`kAddr_*` VAs.

Storage: raw 32-bit value in the four `GW_CharacterStat` slots with checksum 0; MaxHP/MaxMP copies in the calculated
stat (`sub_77EC9F`) and `CWvsContext` +0x211C/+0x2128 are already `ZtlSecure<long>` and need no storage change
(`src/hpmp_patch.h:15-16`; client HUD reads the HP/MP pair through the detoured `_ZtlSecureFuse<short>` at
`src/hud_vitals.cpp:76-79` and the max pair through `_ZtlSecureFuse<long>` at `:80-81`).

`GW_CharacterStat::Encode` (`sub_4E2829`) is *not* widened (`src/hpmp_patch.h:21`) — the client never encodes these
stats back to the server, so the outbound character record keeps the 2-byte form.

## Switches

| Key | Default | Read at | Notes |
|---|---|---|---|
| — (none) | — | — | **There is no client switch.** `hpmp.cpp`/`hpmp_patch.h` contain no `config.ini` read (no `GetPrivateProfile*`/`ReadIni` hits in either file), no `#ifdef`, and no CMake option (`src/CMakeLists.txt:21` compiles it unconditionally). The mod is always on. |
| `MIN_CLIENT_BUILD` (server) | `0` = gate off | `src/main/java/config/ServerConfig.java:61`; enforced `src/main/java/net/server/handlers/login/ClientVersionGate.java:47-53`, refused at `:114-121` | Set per release conf: 10600 (`docs/release/1.6.0.conf:54`, and the three hotfixes `docs/release/1.6.0.1.conf:12`,
`docs/release/1.6.0.2.conf:10`, `docs/release/1.6.0.3.conf:13`), 10700 (`docs/release/1.6.1.conf:263`), 10721 (`docs/release/1.6.2.conf:281`, `docs/release/1.6.3.conf:165`), 10740 (`docs/release/1.6.4.conf:284`). Every shipped conf sets it ≥ 10600, so no release to date served a pre-4-byte client through its own config; the 0 default only bites an installation whose `config.yaml` was never touched. Applied by `tools/release/set-min-client-build` (refuses to lower without `--allow-lower`, `tools/release/set-min-client-build:15`). |
| `MIN_CLIENT_BUILD_GM_BYPASS` (server) | `false` | `src/main/java/config/ServerConfig.java:64`; `.../ClientVersionGate.java:53` | `true` lets a GM on an old client into the 4-byte stream (`docs/release/1.6.0-hpmp-4byte.md:18-19`) — the gate no longer protects that login. |
| `CLIENT_UPDATE_MESSAGE` (server) | "Your game is out of date: open the SlumsMS launcher to update." | `src/main/java/config/ServerConfig.java:62`; used `.../ClientVersionGate.java:67-70` | Only text; no client behaviour. |
| `SLUMS_BUILD_NUMBER_OVERRIDE` (client build) | unset | `cmake/BuildStamp.cmake:35-37` | One-off build number, sticky in the CMake cache until `-U` (`cmake/BuildStamp.cmake:12-13`); the release path uses the formula at `:25`. The 1.6.1 rcs used it (rc11 10700, rc12 10710, `:7`). |

No key falls back to a legacy key in this subsystem: the client half has no key at all, and the server keys are new
1.6.0 additions (`src/main/java/config/ServerConfig.java:58-64`).

## Invariants

1. **All-or-nothing patching.** Everything is checked before anything is written (`src/hpmp.cpp:55-66`), and every
   site is re-checked after all mods (`src/hpmp.cpp:75-83`). Either failure exits the process
   (`src/hpmp.cpp:48`). Rationale in `src/hpmp_patch.h:27-28`: a half-widened client misreads every stat packet.
2. **The nine Decode sites are the wire contract.** The client table has exactly 9 `Decode` rows and the test
   asserts that count with the comment "9 decode sites (the wire contract)" (`tests/hpmp_patch_test.cpp:55`). The
   server's nine writes are `src/main/java/tools/PacketCreator.java:168-171` (GW_CharacterStat ×4),
   `:1056`/`:1074` (map change HP), and the four masks at `:1011-1013`.
3. **Checksum 0 means "raw int".** `FakeTear` returns 0 (`src/hpmp.cpp:20-23`) and `FuseShort_hook` treats 0 as the
   raw-int marker (`src/hpmp.cpp:28-30`). A real `ZtlSecure` checksum is never 0 in practice, and an all-zero slot
   would make the stock Fuse throw (`src/hpmp_patch.h:13-14`).
4. **The client never teaches the server its width.** No per-client branch exists on the server
   (`src/main/java/tools/PacketCreator.java:166-167`); agreement is enforced only by build stamp + gate.
5. **Cap is 99,999 on both sides.** Client imm32 patches to 99999 (`src/hpmp_patch.h:178-182`) and server clamps to
   `MAX_HPMP` at `src/main/java/client/AbstractCharacterObject.java:273`, `:281`, `:320-321`,
   `src/main/java/client/Character.java:10648`, `:10660`.
6. **Build number ≥ 10600 implies the patch.** `AGENTS.md:172`; each release conf sets the gate at or above the
   client that carries it — `docs/release/1.6.0.conf:54` (10600), `docs/release/1.6.1.conf:263` (10700),
   `docs/release/1.6.4.conf:282-284` (10740, and "the 1.6.4 client is the minimum for everyone"). The 1.6.4 draft's
   header still says "No new client: MIN_CLIENT_BUILD stays 10721" (`docs/release/1.6.4.conf:10`) while line 284 was
   raised to 10740 by the owner on 2026-11-05 — the two lines disagree and the conf must be re-read at freeze.
7. **Stats not on the wire stay 2 bytes.** Party HP is `int,int` already (`src/main/java/tools/PacketCreator.java:3967`),
   incoming CP `0x59` stays `readShort` (`src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:46`, `:64`).
8. **Sites are exe-specific.** The whole table is valid only for `MapleStory.exe` sha256 `86e4e6be…ac90`
   (`src/hpmp_patch.h:30`) — a different exe fails the Fuse-entry or per-site check and refuses to start rather than
   patching the wrong instructions.

## Origin

| Part | Origin | Evidence |
|---|---|---|
| `src/hpmp.cpp`, `src/hpmp_patch.h`, `tests/hpmp_patch_test.cpp` | **custom** (new files; no upstream ancestor) | one commit introduces all three — `6b68a31 2026-10-25 Read HP, MaxHP, MP and MaxMP as 4 bytes, up to 99,999` (`git log --follow -- src/hpmp_patch.h`, same commit for `src/hpmp.cpp`). Not upstream Kaentake/MapleRoot: `docs/KAENTAKE-UPSTREAM.md` is Kaentake's usage/config readme and mentions no HP/MP work, and `docs/PROVENANCE.md:17` files the technique under "follows the community write-up", not under an upstream project. |
| Approach (FakeTear, checksum-0 Fuse detour, Decode2→Decode4, widened reads, 99,999 cap) | **custom**, from a community write-up | `docs/PROVENANCE.md:17`: follows the "4-byte HP/MP expansion" write-up (`4byte-hp-mp-GW_CharacterStat.md` in the mods inbox, collected 2026-10-25, "a description of the approach, no code"); every site re-derived against sha256 `86e4e6be…ac90`; 15 of its sites dropped (they truncate job/LUK/INT/AP/equip stats), ~20 added; two of its sites were positions, not HP/MP. |
| Site table itself | **custom**, re-derived | `src/hpmp_patch.h:4-7`, `:30`; the 22-entry blocklist of upstream-mod sites that must stay stock is asserted in `tests/hpmp_patch_test.cpp:58-64`. |
| Server side (`PacketCreator`, `GameConstants.MAX_HPMP`, `HpMpPool`, `ClientVersionGate`) | **custom** | server-side `Character`/`AbstractCharacterObject` HP/MP pools and 99,999 clamps are SlumsMS additions (`src/main/java/client/AbstractCharacterObject.java:313-321` comment: stock Cosmic packed the four into one `long` of 16-bit fields); the wire memo names branch `160-hpmp-int` (`docs/release/1.6.0-hpmp-4byte.md:3`). |
| HUD/Fuse callers that depend on the detour | **modified** | `src/hud_vitals.cpp:6-8` (comment: Fuse<short> is called as returning `int`), `:32` (`FuseShort_t = int(...)`), `:42-45` (`StockOrDetoured` accepts either the stock bytes or a `0xE9` detour, so the HUD works with and without hpmp), `src/hud_native.cpp:46` ("returns int with hpmp.cpp's detour"), `docs/160-ui-adjust-plan.md:45`. |

## Tests and QA

**ctest (client repo).**

| Test | Wiring | Covers |
|---|---|---|
| `hpmp_sites` | `tests/CMakeLists.txt:67-74`; `add_test` at `:71` with `${HPMP_TEST_EXE}`, else `:73` without an exe | `tests/hpmp_patch_test.cpp` |

`tests/hpmp_patch_test.cpp` runs three checks:

- `CheckTable` (`:34-65`): per-kind counts exactly 9/17/**4** (`:55-57`), no overlapping sites (`:40`), every `Widen`
  row is a valid `movsx` or `66`-prefixed form with the expected length (`:42-53`), and the 22-entry blocklist of
  sites that must **not** be in the table because they truncate job/LUK/INT/AP/equip stats (`:58-64`).
- `CheckForms` (`:67-96`): the six patch encodings (`:69-86`) and `Classify` round-trip original / patched /
  unknown for every row (`:87-95`).
- `CheckClient` (`:112-136`), only when an exe is passed: Fuse entry bytes (`:114`), Tear entry `55 8B EC` (`:115`),
  Tear's `ret` at `0x004E8151` (`:116`), every row's stock bytes (`:117-121`), the nine decode→tear adjacencies
  (`:122-132`), and the four cap instruction opcodes (`:133-135`). Success prints
  `hpmp: 87 sites, forms and client bytes OK` (`:145`).

The exe path is a cache variable, default
`Z:/MSv83/SlumsMS-dev/client-1.5.0-fixes2/exe-copy/MapleStory.exe` (`tests/CMakeLists.txt:68-69`); with the path
missing the test still runs, but then only checks the table and forms (`:147`).

`scripts/build.ps1:17-18` runs `ctest --test-dir $buildDir -C Release --output-on-failure` and throws
`Native tests failed` on any failure, so `hpmp_sites` gates every native build that runs the script.

**Server QA.**

| Where | What |
|---|---|
| `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1242-1244` | `t-hp-1` "MaxHP/MaxMP past 30,000 reach the client, capped at 99,999", lane-only, 2 min, probe `hpMp4Byte` |
| `src/main/java/soloMapling/autoqa/Probes160.java:836-884` (registered `:66`, dispatched `:86`) | builds a STAT packet for MAXHP 50,000 / MAXMP 45,000 and decodes it back; `!setmaxhp 50000 45000`, checks the heard STAT carries values > 32,767; `!setmaxhp 150000` must stop at 99,999; restores values in `finally` |
| `src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:474-495` (`clientGate` `:478-482`, `clientGateLines` `:484-495`) | `t-vg-1`: the gate must refuse `min-1` and allow `min`/`min+1`; FAILs when `MIN_CLIENT_BUILD <= 0` — "1.6.0 needs 10600 for 4-byte HP/MP" (`:485-487`) |
| `src/test/java/client/SyntheticClientStatTest.java:23-39` | `MAXHP=50000` / `MAXHP=99999` / mixed-with-STR round-trips, plus the null cases, through `SyntheticClient.decodeHpMpStats` (`src/main/java/client/SyntheticClient.java:213`) |
| `docs/ops/qa-suite.md:1027` | `t-hp-1` row; run line at `:1021` (`tools/ops/autoqa-run oa-1,tw-1,hp-1,…`) |

**Manual QA the release memo requires** (`docs/release/1.6.0-hpmp-4byte.md:37-44`): `!setmaxhp 50000` then check
bars/stat window across map change, channel change and relog; damage and potions above 32,767; Hyper Body on top;
`!setmaxhp 99999` and `!setmaxhp 100000` (clamps); and an old client (below 10600) refused at login with the update
message.

**Not covered.** There is no automated test that a real client and a real server agree on the field widths — the
`hpmp_sites` test only proves the table matches one exe, and `t-hp-1` uses a synthetic decoder
(`src/main/java/client/SyntheticClient.java:213`) rather than the C++ client. There is no test of `FuseShort_hook`'s
checksum-0 branch, of `FakeTear`, or of the refuse-to-start paths (`src/hpmp.cpp:40-49`, `:77-82`) — those are
verified only by the message box existing in code. The `Widen` semantics (that each rewritten instruction is the
right one) rest on the original derivation, not on a test. Nothing checks that the client build number actually
exceeds 10600; `AGENTS.md:172` is a written rule with no automated enforcement.

## Known issues

| Ref | Issue |
|---|---|
| NEW-FINDING: `src/hpmp_patch.h:44` | `constexpr int kStockCap = 30000;` is declared and never referenced anywhere in the client repo (`grep -rn kStockCap src tests` → one hit). Dead constant next to the value it documents; the four cap rows carry the literal `{0x30,0x75,0x00,0x00}` instead (`src/hpmp_patch.h:148-151`). |
| NEW-FINDING: `src/hpmp.cpp:53` / `src/hpmp_patch.h:60` | The patch is unconditional: no `config.ini` key, no CMake option (`src/CMakeLists.txt:21`), no check of the connected server's version. A 1.6.4 client pointed at a pre-1.6.0 server (which writes 2-byte HP) will silently misread every stat packet — the mirror image of the failure mode the release memo only describes for old clients (`docs/release/1.6.0-hpmp-4byte.md:11-13`). The server-side gate exists; the client-side one does not. |
| NEW-FINDING: `src/main/java/config/ServerConfig.java:61` | `MIN_CLIENT_BUILD = 0` (gate off) is the default and `MIN_CLIENT_BUILD_GM_BYPASS = false` (`:64`) is the only brake. A 1.6.x jar on a server whose `config.yaml` does not set the key serves old clients misaligned packets; the memo lists this trap (`docs/release/1.6.0-hpmp-4byte.md:17-19`) but nothing fails the boot. `t-vg-1` (`src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:474-495`) only reports it in QA. |
| NEW-FINDING: `src/main/java/client/Character.java:7791-7793` | The GM job branch of level-up still adds exactly `30000` HP and `30000` MP per level (`Job.GM` at `:7791`), which was the stock 30,000 cap expressed as a gain. With `MAX_HPMP = 99_999` (`src/main/java/constants/game/GameConstants.java:34`) a GM can now level three times before hitting the cap; the other job branches use small random ranges (`:7781-7790`), so this one is the only one written against the old cap. It also means GM HP/MP growth stops mattering at level 4 instead of ramping, and the values are inconsistent with every other job's per-level gain. |
| `src/hpmp_patch.h:21` (documented, not a defect) | Incoming CP `0x59` stays 2 bytes on both sides (`src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:46`, `:64`) and the handler additionally ignores MP heals of 1000 or more (`:65`). Both are stock v83 behaviour that the memo keeps (`docs/release/1.6.0-hpmp-4byte.md:29-30`); the consequence is that map recovery (sauna, etc.) is proportionally weaker for a 99,999 pool than it was for a 30,000 one. Nothing here looks broken; recorded so the balance effect is not a surprise. |
| NEW-FINDING: `src/main/java/tools/PacketCreator.java:119` | `showHpHealed(int cid, int amount)` writes the heal amount with `p.writeByte(amount)`, so any heal over 255 (map recovery `77 * map.getRecovery() * 1.5`, `src/main/java/net/server/channel/handlers/HealOvertimeHandler.java:54`) shows a truncated number in the floating heal text. Not caused by the 4-byte work — the packet is 1 byte in stock v83 too — but with pools at 99,999 the truncation is far more visible, and the HP itself is credited with the full `short` (`HealOvertimeHandler.java:46`, `:60`), so the number shown and the number healed disagree. |
| NEW-FINDING: `src/buildstamp_proto.h:11` | The comment states the build is "VERSION major*10000 + minor*100 + patch", but `cmake/BuildStamp.cmake:25` computes `major*10000 + (minor+1)*100 + patch*10`. The comment predates the formula change (`cmake/BuildStamp.cmake:6-9`) and now misdescribes it — reading it, 1.6.4 looks like 10064 instead of 10740. |
| NEW-FINDING: `docs/release/1.6.4.conf:10` vs `:284` | The 1.6.4 conf contradicts itself on the gate: the header says "No new client: MIN_CLIENT_BUILD stays 10721 (nothing on this branch changes what the client reads)" (`:10`), while `MIN_CLIENT_BUILD=10740` at `:284` was raised by the owner on 2026-11-05 for the b300/b251 guards. Line 284 is authoritative (last assignment wins in a sourced bash conf, `:2-3`), but a reader who follows `:10` will stage the wrong gate. |
| `docs/160-ui-adjust-plan.md:45` | `hud_vitals.cpp` must call Fuse with the `int` return type so the HUD widget shows HP over 32,767; done at `src/hud_vitals.cpp:32` but with no test — the `overlay_hud_layout` ctest (`tests/CMakeLists.txt:82`) checks layout, not the value width. |

STATUS-1.6.4.md has no card for this subsystem: a grep for `hpmp`, `4-byte`, `99999`, `setmaxhp` returns nothing, so
none of b251, b270, b274, e14 or L15 concern HP/MP. b251 (client crash entering 211080100) touches the same
patch-file family (`src/mobattackfix.cpp`) but a different patch, and is not an HP/MP defect.

## State

**working.**

Evidence: both halves are implemented and in production. The client compiles the 87-site table into `slums.dll`
(`src/CMakeLists.txt:21`), refuses to start on any byte mismatch (`src/hpmp.cpp:40-49`, `:55-66`), and re-verifies
after the other mods (`src/hpmp.cpp:75-83`, `src/hook.h:303`). The table, the patch forms, the blocklist and (with an
exe supplied) every stock byte are asserted by `hpmp_sites` (`tests/hpmp_patch_test.cpp:34-136`,
`tests/CMakeLists.txt:67-74`) and that test gates every `scripts/build.ps1` run (`scripts/build.ps1:17-18`). The
server writes the nine fields as 4 bytes (`src/main/java/tools/PacketCreator.java:168-171`, `:1011-1013`, `:1056`,
`:1074`) and clamps at `MAX_HPMP` (`src/main/java/constants/game/GameConstants.java:34`). Every release since 1.6.0
pins `MIN_CLIENT_BUILD` at or above 10600 (`docs/release/1.6.0.conf:54`, `docs/release/1.6.1.conf:263`,
`docs/release/1.6.2.conf:281`, `docs/release/1.6.3.conf:165`, `docs/release/1.6.4.conf:284`), and two auto-QA checks
cover the behaviour and the gate (`t-hp-1`, `t-vg-1`).

Residual risk (why the label is *working* with caveats rather than *verified*): nothing automated exercises the
client↔server pair — `t-hp-1` decodes with a Java stand-in (`src/main/java/client/SyntheticClient.java:213`), not the
C++ client; the `FuseShort_hook` checksum-0 branch, `FakeTear` and the refuse-to-start paths have no test
(`src/hpmp.cpp:20-31`, `:40-49`, `:77-82`); the memo's agreement checks are a hand checklist
(`docs/release/1.6.0-hpmp-4byte.md:37-44`); and the client cannot be switched off or made to reject an old server,
so a mismatched pair fails silently on the client side (see Known issues).

## Open questions

1. Was any build ≥ 10600 ever published whose exe differed from sha256 `86e4e6be…ac90` (`src/hpmp_patch.h:30`)? The
   table refuses to patch any other exe, but nothing in the repo pins the launcher's distributed exe hash to that
   value, so a mismatched install would fail at startup with "found unexpected bytes".
2. `FakeTear` returns checksum 0 (`src/hpmp.cpp:20-23`) and `FuseShort_hook` treats 0 as raw int (`:28-30`). Is there
   any code path that stores a legitimately checksummed 0 short in one of the four slots (leaving checksum 0 by
   accident), which the detour would then misread as a raw 32-bit value? No test covers this.
3. Is the `t-hp-1` probe (`src/main/java/soloMapling/autoqa/Probes160.java:836`) ever run against the real client on
   TEST, or only against `SyntheticClient.decodeHpMpStats` (`src/main/java/client/SyntheticClient.java:213`)? The
   catalog marks it lane-only with a 2-minute budget (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:1242-1244`);
   the run log was not read for this review.
4. Why does the client keep `GW_CharacterStat::Encode` (`sub_4E2829`) at 2 bytes (`src/hpmp_patch.h:21`) — is the
   outbound character record genuinely never carrying HP/MP back to the server in v83, and if so is that guaranteed
   or just observed?
5. The `Widen` table has 57 rows out of 69 traced Fuse reads (`src/hpmp_patch.h:23-25`). Which 12 reads were left
   alone and why? The header does not enumerate them, and the test only validates the rows present.
