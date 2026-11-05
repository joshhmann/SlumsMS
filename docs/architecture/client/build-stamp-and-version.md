# Build stamp and client version

## Purpose

Every `slums.dll` carries a numeric build and a release string, both derived from the client repo's `VERSION` file at
configure time, and reports them to the login server as CP `0x3738 CLIENT_BUILD_STAMP` immediately before every
`CP_CheckPassword` (`src/buildstamp_proto.h:3-5`, `src/bypass.cpp:435`). The server compares that build with
`MIN_CLIENT_BUILD` and refuses older clients at login with an update popup
(`src/main/java/net/server/handlers/login/ClientVersionGate.java:18-27`). The same two values are printed for humans
in three places — window title, login-screen corner, PE version resource — so a screenshot identifies the client
someone runs (`src/buildshow_text.h:2-5`, `src/slums.rc.in:17-25`).

The stamp is the wire contract that lets the server ship **behaviour-changing** client patches: 4-byte HP/MP from
build 10600 (`src/main/java/constants/game/GameConstants.java:27-34`), custom NPC templates from 10610
(`src/main/java/server/life/CustomNpcGate.java:17`), the damage rank from 10690
(`src/main/java/config/ServerConfig.java:53`), the 1.6.4 map guards from 10740 (`docs/release/1.6.4.conf:279-284`). It
is a *soft* version check: the old v83 handshake version is untouched.

## Entry points

| Side | Where | What |
|---|---|---|
| client build | `CMakeLists.txt:24` | `include(cmake/BuildStamp.cmake)`, before `add_subdirectory(src)` (`:36`) and before `add_subdirectory(tests)` (`:37-40`) |
| client build | `cmake/BuildStamp.cmake:14` | `file(READ .../VERSION)` — the only input |
| client build | `src/CMakeLists.txt:33` | `target_compile_definitions(injector PRIVATE SLUMS_BUILD_NUMBER=... "SLUMS_RELEASE=\"...\"")` |
| client build | `src/CMakeLists.txt:34-35` | `file(GENERATE .../slums-build.txt "release=...\nbuild=...")` |
| client build | `src/CMakeLists.txt:41-42` | `configure_file(src/slums.rc.in -> slums.rc)` + `target_sources` (version resource) |
| client runtime | `src/bypass.cpp:475` | `ATTACH_HOOK(CLogin::SendCheckPasswordPacket, ..._hook)` installs the sender |
| client runtime | `src/bypass.cpp:421` | `CLogin::SendCheckPasswordPacket_hook` — sends the stamp, then the password |
| client runtime | `src/bypass.cpp:399` | `ClientDataCaps()` — probes `Npc/*.img` to fill caps bit 0 |
| client runtime | `src/buildshow.cpp:100` | `BuildShow_Tick()` — login-corner label, called from `CWvsApp::CallUpdate_hook` (`src/bypass.cpp:249`) |
| client runtime | `src/system.cpp:186` | window title via `BuildShow::WindowTitle` |
| server | `src/main/java/net/opcodes/RecvOpcode.java:31` | `CLIENT_BUILD_STAMP(0x3738)` |
| server | `src/main/java/net/PacketProcessor.java:121` | `registerHandler(RecvOpcode.CLIENT_BUILD_STAMP, new ClientBuildStampHandler())` |
| server | `src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:14` | the handler (`package net.server.handlers.login`; no file under `net/server/handlers/`) |
| server | `src/main/java/net/server/handlers/login/LoginPasswordHandler.java:74` | the gate call, after the password verifies |
| release | `tools/release/set-min-client-build:1-40` | rewrites `MIN_CLIENT_BUILD:` in a live `config.yaml` |
| release | `tools/release/templates/deploy.body.sh:123`, `:243` | dry-run then real `set-min-client-build` in the generated deploy |
| release | `tools/release/stage-client:91-93` | reads the built `slums-build.txt` and warns on a release mismatch |

## How it works

Numbered end to end, configure → wire → refusal.

1. **VERSION is the single source.** `VERSION` holds one line, currently `1.6.4-rc3`. `cmake/BuildStamp.cmake:14-15`
   reads and strips it; `:16-18` requires `^([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.]+)?$`, so the release is
   `major.minor.patch` plus an optional `-suffix` (`-rc1`, `-rc3`).
2. **Build number formula** (`cmake/BuildStamp.cmake:22-25`): `major*10000 + (minor+1)*100 + patch*10`.
   `VERSION 1.6.4-rc3` → `10000 + 700 + 40 = 10740`; the suffix does not participate, so `1.6.4-rc1`, `-rc2` and
   `-rc3` all stamp 10740. Guards: minor must be ≤ 98 and patch ≤ 9 (`:22-24`), the result must be a non-negative
   32-bit int (`:39-41`), the release may only use `[0-9A-Za-z._+-]` (`:42-44`) and be ≤ 40 chars (`:45-48`, "the
   server ignores longer stamps"). Status line: `slums.dll build stamp: <release> (build <n>)` (`:49`).
3. **Overrides** (`cmake/BuildStamp.cmake:32-37`): `-DSLUMS_RELEASE_OVERRIDE=` / `-DSLUMS_BUILD_NUMBER_OVERRIDE=`
   replace the computed values; both are `if(...)` on the cache variable, so they are sticky until `-U`. Checks in
   `:39-48` run **after** the overrides, so a bad override also aborts the configure.
4. **Configure dependency** (`cmake/BuildStamp.cmake:38`): `VERSION` is appended to
   `CMAKE_CONFIGURE_DEPENDS`, so a reused build directory re-runs the stamp logic when the file changes; a stale
   stamp cannot be left behind by relinking alone.
5. **Values reach the compiler and the release tools.** `src/CMakeLists.txt:33` defines `SLUMS_BUILD_NUMBER` and
   `SLUMS_RELEASE` on the `injector` target (= `slums.dll`); `:34-35` writes `$<CONFIG>/slums-build.txt`
   (`release=`, `build=`); `:38-40` refuses a build > 65535 because `VERSIONINFO` fields are 16-bit; `:41-42`
   generates `slums.rc`.
6. **Compile-time contract.** `src/buildstamp_proto.h:16-21` `#error`s when either macro is missing, so a target that
   forgets the definitions fails to compile; `:29` `static_assert(kSlumsBuildNumber > 0)` (0 means "no stamp" to the
   server). `:23-27` fix opcode `0x3738`, format `2`, caps bit 0, and the two constants. `:33-38`
   `BuildStamp_EncodeValues(packet, build, release, caps)` writes `Encode2(format)`, `Encode4(build)`,
   `EncodeStr(release)`, `Encode4(caps)`; `:41-43` `BuildStamp_Encode(packet, caps)` calls it with the module's own
   numbers.
7. **Caps are probed at first login only.** `src/bypass.cpp:399-419` memoises into `static uint32_t caps`
   (sentinel `0xFFFFFFFF`), starts at `kCapCustomNpcData`, and for each of the 16 ids in
   `src/buildstamp_proto.h:46-47` calls `get_rm()->GetObjectA(L"Npc/%07d.img")`; a `VT_UNKNOWN`/`VT_DISPATCH`
   result or a throw clears the bit and breaks, logging
   `Build stamp: %ls is missing, so custom NPCs stay off for this client`. Final line: `Build stamp: caps 0x%08X`.
8. **Send order** (`src/bypass.cpp:421-451`): repeat guard `if (m_bRequestSent) return 0;` (`:422`), the two
   `RemoveAll` calls at `0x005FDDE3`/`0x005FDF26` and `CSystemInfo::Init` at `0x00A54BD0`, then the stamp packet
   (`:433-437`, `BuildStamp_Encode` at `:435`) via `CClientSocket::SendPacket` at `0x0049637B`, then
   `CP_CheckPassword` (opcode 1: id, password, 16-byte MachineId, game-start mode) through the same call at `:449`.
   Same TCP connection, no reply — ordering is the whole guarantee.
9. **Server parse** (`src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:38-68`): needs
   `2 + 4 + 2` bytes, format must be 1 or 2 (`:43-45`), build must be non-negative (`:46-49`), the release length
   must be 0..40 and fit (`:50-58`), the release is sanitised with
   `.replaceAll("[^0-9A-Za-z._+-]", "")` (`:59`), and format 2 needs 4 more bytes for caps (`:61-66`). Anything
   malformed returns `null` and the handler silently keeps the old values (`:25-30`); `validateState` requires
   `!c.isLoggedIn()` (`:20-22`).
10. **Stamp lands on the session.** `Client.setClientStamp(format, build, release, caps)`
    (`src/main/java/client/Client.java:1285-1290`) stores all four; format < 2 forces caps to 0. Fields default to
    build 0 / release `""` / format 0 (`:127-131`). The channel session gets the login session's stamp through
    `ClientStampCarry.remember` (`src/main/java/client/Client.java:1184`) and `.restore`
    (`src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:147`,
    `src/main/java/net/server/coordinator/session/ClientStampCarry.java:24-43`).
11. **Gate on a verified password** (`src/main/java/net/server/handlers/login/LoginPasswordHandler.java:74`): runs
    only for loginok ∈ {0, 7, 23, -23, -10} (`src/main/java/net/server/handlers/login/ClientVersionGate.java:76-78`) —
    never on a wrong password, never before the "already logged in" kick, never in the `AUTOMATIC_REGISTER` branch
    without a GM level (`src/main/java/net/server/handlers/login/LoginPasswordHandler.java:79` passes the constant
    `accountId -> 0` lambda).
12. **Verdict** (`src/main/java/net/server/handlers/login/ClientVersionGate.java:99-123`): QA-cap check first
    (`:102-108`), then `MIN_CLIENT_BUILD <= 0 || build >= MIN_CLIENT_BUILD` → allow (`:110-112`), then the optional GM
    bypass read from the database (`:113-116`, `accountGmLevel` at `:126-140`, `SELECT MAX(gm) FROM characters WHERE
    accountid = ?`, because `Client.getGMLevel()` is still 0 at this point, `:90-94`). Refusal logs `Client version
    gate: refused '<account>' from <addr>: client build N (no stamp) < minimum M` (`:118-119`) and sends two packets:
    `PacketCreator.serverNotice(1, message)` (`:120`) and `getLoginFailed(1)` (`:121`).
13. **What the player sees.** Reason 1 is chosen because `CLogin::OnCheckPasswordResult` (0x5F9891) shows no dialog
    for it — the only text is our notice popup, and the login box becomes usable again
    (`src/main/java/net/server/handlers/login/ClientVersionGate.java:23-27`, `:31-32`). Text = `CLIENT_UPDATE_MESSAGE`,
    default `Your game is out of date: open the SlumsMS launcher to update.`
    (`src/main/java/config/ServerConfig.java:62`); a blank/absent message falls back to the same literal
    (`src/main/java/net/server/handlers/login/ClientVersionGate.java:67-70`).
14. **Release hook** (`tools/release/set-min-client-build`): `MIN_CLIENT_BUILD: N` under `server:` in the live
    `config.yaml`, run while the server is stopped, after the client is published (`:7-12`). It must appear exactly
    once (`:26-28`), keeps the old indent and CRLF (`:13-14`), refuses to lower without `--allow-lower` (`:34`),
    is idempotent (`:35`), and `--show`/`--dry-run` exist (`:30`, `:37`). The value originates in
    `docs/release/<ver>.conf` (`MIN_CLIENT_BUILD=...`) and is threaded through `tools/release/gen-deploy:19,86` into
    the generated `deploy.sh` (`tools/release/templates/deploy.body.sh:123`, `:243`).
15. **Humans see the same numbers.** Window title `SlumsMS <release> (<build>)` (`src/buildshow_text.h:12-14`, set in
    `src/system.cpp:186`); login corner `SlumsMS <release> · build <build>` in white Dotum 12 with shadow
    (`src/buildshow_text.h:19-25`, drawn by `src/buildshow.cpp:60-96`, bottom-right at `sw-w-6, sh-h-6`); the PE
    resource (`src/slums.rc.in:5-6`, `:19`, `:23-24`); and the crash report headline
    `Client: %s (build %d)` (`src/crash_report.cpp:125-126`).

### A client with no stamp

A pre-1.4.2 `slums.dll` (1.4.1 and older) never sends CP `0x3738` (`src/buildstamp_proto.h:11-12`). The server's
fields stay at their initial values — build 0, release `""`, format 0 (`src/main/java/client/Client.java:127-131`) —
so:

| Gate | With no stamp | Source |
|---|---|---|
| `MIN_CLIENT_BUILD` | refused whenever it is > 0; log says `client build 0 (no stamp) < minimum M` | `src/main/java/net/server/handlers/login/ClientVersionGate.java:110`, `:118-119` |
| GM bypass | applies if `MIN_CLIENT_BUILD_GM_BYPASS` and the account's max `characters.gm` > 0 | `src/main/java/net/server/handlers/login/ClientVersionGate.java:52`, `:113-116` |
| QA cap | not refused (caps 0) | `src/main/java/net/server/handlers/login/ClientVersionGate.java:81-83` |
| custom NPCs | allowed only if build ≥ `CUSTOM_NPC_MIN_BUILD` (format-1 fallback) | `src/main/java/server/life/CustomNpcGate.java:60-65` |
| damage rank | allowed only if build ≥ `DAMAGE_RANK_MIN_BUILD` | `src/main/java/server/life/DamageRank.java:63-68` |
| UI tip line | allowed only if build ≥ 10600 | `src/main/java/soloMapling/menus/GuideMenu.java:34`, `:160` |
| `@bug` client log upload | never asked (needs format ≥ 2 and caps bit 1) | `src/main/java/server/reports/BugClientLogs.java:67-69` |

So the answer is: build 0, and on any server that has a gate set, the login is refused with the update popup. The one
exemption written into the code is the GM bypass.

## Data

**Wire — CP `0x3738` (client → server, no reply).** Little-endian throughout.

| Offset | Size | Field | Client writer | Server reader |
|---|---|---|---|---|
| 0 | 2 | opcode `0x3738` | `src/buildstamp_proto.h:23` | `src/main/java/net/opcodes/RecvOpcode.java:31` |
| 2 | 2 | format (1 or 2) | `:34` (`kBuildStampFormat` = 2) | `src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:42-45` |
| 4 | 4 | build (`int`) | `:35` | `:46-49` |
| 8 | 2 + n | release length + ASCII release (≤ 40) | `:36` | `:50-59` |
| 8 + 2 + n | 4 | caps (`int`, format 2 only) | `:37` | `:61-66` |

Golden bytes (both repos assert the same literal): format 2, build 10402, `"1.4.2"`, caps 1 →
`0200a22800000500312e342e3201000000` (`tests/buildstamp_proto_test.cpp:35`,
`src/test/java/net/server/handlers/login/ClientVersionGateTest.java:99`). Format 1 (no caps) →
`0100a22800000500312e342e32` (`src/test/java/net/server/handlers/login/ClientVersionGateTest.java:76`).

**Caps bits.**

| Bit | Name | Meaning | Set by | Read by |
|---|---|---|---|---|
| 0 | `kCapCustomNpcData` / `CAP_CUSTOM_NPC_DATA` | all 16 `Npc/*.img` resolve, so custom NPC spawns are safe | `src/buildstamp_proto.h:25`, probed in `src/bypass.cpp:399-419` | `src/main/java/server/life/CustomNpcGate.java:23`, `:60-65` |
| 1 | `CAPS_LOG_UPLOAD` | client will answer LP `0x373A` with CP `0x373B` after `@bug` | **no producer in this branch** (see Known issues) | `src/main/java/server/reports/BugClientLogs.java:40`, `:67-69` |
| 31 | `CAP_QA_BUILD` | a `SLUMS_QA_MODULE` QA build, must not reach live | not in this branch (`2f17894`, only on `client-170-*`/`client-162-*`) | `src/main/java/net/server/handlers/login/ClientVersionGate.java:37`, `:81-83` |

**Custom NPC ids the caps bit stands for** (`src/buildstamp_proto.h:46-47`): `9990000` (Slums-chan) plus `2985..2999`
(15 Lazy Penguins) — 16 entries, asserted in `tests/buildstamp_proto_test.cpp:50-51`; mirrored server-side as the
config default `CUSTOM_NPC_IDS = "9990000,2985-2999"` (`src/main/java/config/ServerConfig.java:70`) parsed by
`CustomNpcGate.isCustom`.

**Build artifacts.** `build/Release/slums-build.txt` = `release=<VERSION text>\nbuild=<n>\n`
(`src/CMakeLists.txt:34-35`); the release tooling parses it with `tools/release/stage_client_lib.py:16-24`
(`release`, and `build` only when it matches `[0-9]{1,9}`), then `stage-client:91-93` prints
`client build stamp: release <r>, build <b>` and warns when `release`'s `major.minor.patch` differs from the version
being staged.

**PE version resource** (`src/slums.rc.in`): `FILEVERSION`/`PRODUCTVERSION` = `major,minor,patch,build` (`:5-6`),
`FileVersion` string `major.minor.patch.build` (`:19`), `ProductVersion` = the release string (`:23`),
`SlumsBuild` = the build (`:24`), `FileDescription` "SlumsMS client (based on Kaentake)" (`:18`),
`LegalCopyright` "SlumsMS; Kaentake by its upstream authors" (`:25`).

**Release history.**

| Client | Build | How the number came about | Evidence |
|---|---|---|---|
| 1.4.2 | 10402 | first stamped client, old formula `major*10000+minor*100+patch` | `src/buildstamp_proto.h` golden `tests/buildstamp_proto_test.cpp:35`; commit `66026a3` |
| 1.5.0 | 10500 | same formula | commit `94bf3b0` ("VERSION 1.5.0 (build 10500)") |
| 1.6.0 | 10600 | same formula; the 4-byte HP/MP requirement | `docs/release/1.6.0.conf:54`; `README.md:29-31` |
| 1.6.0.2 | 10601 | override, VERSION stayed `1.6.0` | `docs/release/1.6.0.3.conf:4`; `docs/research-121-crash-report.md:139` |
| 1.6.1 rc8 / rc9 | 10680 / 10690 | overrides, VERSION still `1.6.0` | `docs/release/1.6.1.conf:259` |
| 1.6.1 rc11 / rc12 | 10700 / 10710 | overrides; rc11 was the shipped 1.6.1 client | `docs/release/1.6.1.conf:258-263`; `docs/release/1.6.1-notes.md:68` |
| 1.6.2 rc1..rc5 | 10720 | `VERSION 1.6.2` under the **new** formula, no override (b205) | `35ff96f`; `README.md:29-30` ("1.6.2 -> 10720; 1.6.2-rc1 is also 10720"); `docs/release/1.6.2.conf:274` |
| 1.6.2 rc6 (= shipped) | 10721 | `-DSLUMS_BUILD_NUMBER_OVERRIDE=10721` by owner call | `docs/release/1.6.2-deploy-steps.md:121-124`; `docs/release/1.6.2.conf:281` |
| 1.6.3 | (no new client) | gate stayed at 10721 — "No new client is planned: MIN_CLIENT_BUILD stays 10721" | `docs/release/1.6.3.conf:8-9`, `:165` |
| 1.6.4 rc1/rc2/rc3 | 10740 | `VERSION 1.6.4-rc*` under the new formula | `9b5dcda`; `docs/release/1.6.4.conf:284`; `docs/release/1.6.4-playbook.md:17` |
| 1.7.0 (planned) | 10800 | formula | `cmake/BuildStamp.cmake:8` |

Formula change, one line: until 1.6.0 the build was `major*10000 + minor*100 + patch`; from 1.6.2 it is
`major*10000 + (minor+1)*100 + patch*10`, which gives every new build room above the 1.6.1 candidates
(10700/10710) and leaves 10721..10729 for a 1.6.x hotfix override (`cmake/BuildStamp.cmake:6-9`, ticket b205, owner
2026-10-30).

## Switches

No `config.ini` key governs the stamp. `config.example.ini` has only the sections `[general]`, `[discord]`,
`[clienthax]`, `[weather]`, `[graphics]`, `[config]` (`config.example.ini:1,4,7,60,68,84`) and none of its keys are
read by `src/buildstamp_proto.h`, `src/bypass.cpp:399-451` or `src/buildshow.cpp`. Nothing falls back to a legacy key:
there is no older stamp key to fall back to. Switching happens at two other layers.

**Client, CMake cache:**

| Switch | Default | Read at | Effect |
|---|---|---|---|
| `SLUMS_RELEASE_OVERRIDE` | unset | `cmake/BuildStamp.cmake:32-34` | replaces the release string (wire + title + resource) |
| `SLUMS_BUILD_NUMBER_OVERRIDE` | unset | `cmake/BuildStamp.cmake:35-37` | replaces the build number; sticky until `-U` |
| `MAPLEROOT_INTEGRATION` | `OFF` (`CMakeLists.txt:6`) | `CMakeLists.txt:31-33` | not stamp-specific, but the standard build turns it on (`scripts/build.ps1:11`) |

**Client, build-tree only (not on this branch):** `-DSLUMS_QA_MODULE=ON` would stamp `<release>-qa` and caps bit 31
(commit `2f17894`, message + `docs/qa/client-scripts/README.md:63-64`); no file in the current tree mentions it, so it
cannot be switched on here.

**Server, `config.yaml` under `server:` / `ServerConfig`:**

| Key | Code default | Read at | Effect |
|---|---|---|---|
| `MIN_CLIENT_BUILD` | `0` (gate off) | `src/main/java/config/ServerConfig.java:59-61`; used `src/main/java/net/server/handlers/login/ClientVersionGate.java:48-49`, `:110` | the minimum build |
| `CLIENT_UPDATE_MESSAGE` | `"Your game is out of date: open the SlumsMS launcher to update."` | `src/main/java/config/ServerConfig.java:62`; used `src/main/java/net/server/handlers/login/ClientVersionGate.java:67-70`, `:120` | popup text; blank → the default literal |
| `MIN_CLIENT_BUILD_GM_BYPASS` | `false` | `src/main/java/config/ServerConfig.java:63-64`; used `src/main/java/net/server/handlers/login/ClientVersionGate.java:52`, `:113` | GM accounts (max `characters.gm` > 0) may log in below the minimum |
| `CUSTOM_NPC_MIN_BUILD` | `10610` | `src/main/java/config/ServerConfig.java:71`; used `src/main/java/server/life/CustomNpcGate.java:60-65` | fallback for format-1/absent stamps |
| `DAMAGE_RANK_MIN_BUILD` | `10690` | `src/main/java/config/ServerConfig.java:53`; used `src/main/java/server/life/DamageRank.java:63-68` | who receives LP `0x3739` |
| `DAMAGE_RANK_MIN_MOB_HP` | `5000000` | `src/main/java/config/ServerConfig.java:54` | not stamp-related, listed because it shares the build comment |

`config.example.yaml:82-86` mirrors the first five with the same values.

**Server, system property:** `-Dslums.client.allowQa=true` — `ClientVersionGate.ALLOW_QA`
(`src/main/java/net/server/handlers/login/ClientVersionGate.java:38-39`), read in `:96` via `Boolean.getBoolean`; off
by default; TEST/RC only.

**Release conf:** `MIN_CLIENT_BUILD=<n>` in `docs/release/<ver>.conf`, applied through
`tools/release/gen-deploy:19,86` → `deploy.body.sh:123,243` → `tools/release/set-min-client-build`.

## Invariants

1. **Build > 0 and monotonic per release.** `src/buildstamp_proto.h:29` `static_assert(kSlumsBuildNumber > 0)`; the
   formula only grows with `VERSION` and the override room (10721..10729) is below the next release's number
   (`cmake/BuildStamp.cmake:6-9`).
2. **Release is 1..40 chars of `[0-9A-Za-z._+-]`.** Checked at configure (`cmake/BuildStamp.cmake:42-48`) and
   re-checked at parse (`src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:17`, `:56-58`);
   `tests/buildstamp_proto_test.cpp:54` asserts the built value fits.
3. **The stamp goes out before the password on the same connection, always.** One `SendPacket` pair inside one hook,
   with the stamp first (`src/bypass.cpp:433-450`); a repeat attempt is suppressed by `m_bRequestSent` (`:422`).
   The server relies on this: it has no other source for the build.
4. **A malformed stamp never overwrites a good one and never fails the login by itself.**
   `ClientBuildStampHandler.parse` returns `null` on any inconsistency and `handlePacket` then does nothing
   (`src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:25-30`); the gate sees build 0 and applies the
   ordinary rule.
5. **Format 1 ⇒ caps 0.** `Client.setClientStamp` clears caps when the format is < 2
   (`src/main/java/client/Client.java:1289`), and the gate/`CustomNpcGate` fall back to a build comparison rather than
   trusting caps (`src/main/java/server/life/CustomNpcGate.java:60-65`).
6. **The gate only runs on a verified password and before side effects.** `passwordVerified` (`:76-78`) plus the
   placement at `src/main/java/net/server/handlers/login/LoginPasswordHandler.java:74-78` (before the loginok-7 kick) and
   `:79-83` (before `AUTOMATIC_REGISTER` inserts). `VersionGateOrderTest` pins the order by making any DB access an
   error.
7. **A refusal is two packets, popup then silent failure.** `serverNotice(1, ...)` + `getLoginFailed(1)`
   (`src/main/java/net/server/handlers/login/ClientVersionGate.java:105-106`, `:120-121`); reason 1 is the only one the
   v83 client renders without its own dialog (`:23-27`).
8. **`MIN_CLIENT_BUILD` never decreases in the automated path.** `set-min-client-build:34` refuses without
   `--allow-lower`; rollback restores the old value with the old jar
   (`tools/release/templates/rollback.body.sh:132`).
9. **The stamp fits a PE resource.** `src/CMakeLists.txt:38-40` aborts when the build exceeds 65535, because
   `VERSIONINFO` fields are 16-bit.
10. **Overrides are visible.** Every build writes `slums-build.txt` next to `slums.dll` (`src/CMakeLists.txt:34-35`),
    so an override-stamped build is identifiable after the fact; the release tool prints it (`stage-client:91-93`).

## Origin

| Piece | Origin | Evidence |
|---|---|---|
| `VERSION`, `CP 0x3738`, `cmake/BuildStamp.cmake`, the override path | **custom** (SlumsMS) | `66026a3` 2026-10-21 "Report the client build at login (CP 0x3738 build stamp)"; `git log --follow` shows only that commit plus `35ff96f` for the file |
| build formula `(minor+1)*100 + patch*10` | **custom**, b205 | `35ff96f` 2026-10-30 "Plain 1.6.2 builds stamp 1.6.2 / 10720 without override flags (b205)" |
| format 2 + caps bit 0 | **custom** (ticket: custom NPC imgs, 2026-10-28) | `10a386e` 2026-10-28 in `src/buildstamp_proto.h` |
| `src/buildshow.cpp`, `src/buildshow_text.h`, `src/slums.rc.in` | **custom** | `9a1ebea` 2026-10-25 "Show the release and build in the title, on the login screen and in slums.dll"; later `08630a6`, `fde27d3` |
| server `ClientBuildStampHandler`, `ClientVersionGate`, `tools/release/set-min-client-build`, `ClientVersionGateTest` | **custom** | all four are created by `82d4831b4` 2026-10-21 "Refuse logins from outdated clients (MIN_CLIENT_BUILD)" (each file's `git log --follow` ends there); later edits: `4af751a64` (handler, test), `5a14d2abc` (set-min-client-build), `d5103db2f` / `bfe9c4a6d` / `045756de5` (gate) |
| format-2 server support | **custom** | `4af751a64` 2026-10-28 "Gate custom NPCs by client data; accept build stamp format 2 with caps" |
| QA-cap refusal (bit 31, `-Dslums.client.allowQa`) | **custom**, ticket 166/170 | `045756de5` 2026-10-30 "Refuse the QA client build at login unless the server allows it (TEST/RC only)" |
| base client, hooks, addresses | **base** (Kaentake / MapleRoot Full Repack) | `docs/PROVENANCE.md` (baseline MapleRoot Full Repack + joshhmann/kaentake at `2a43a01e`); the hook macro and `MEMBER_HOOK` style at `src/bypass.cpp:395` are the upstream pattern, the stamp inside it is ours |

`docs/KAENTAKE-UPSTREAM.md` contains only Kaentake usage/config text and no build-stamp material, so there is no
upstream ancestor for the stamp.

## Tests and QA

**Client (ctest, registered unconditionally in `tests/CMakeLists.txt`):**

| Test | File | What it asserts |
|---|---|---|
| `buildstamp_wire_format` | `tests/buildstamp_proto_test.cpp` (`tests/CMakeLists.txt:1-4`) | golden bytes format 2/10402/caps 1 (`:35`, `:42-43`); packet length `2+4+2+len+4`, first two bytes `02 00`, last byte 0, caps bit 0 == 1 (`:46-49`); 16 NPC ids `9990000..2999` (`:50-51`); build == `SLUMS_BUILD_NUMBER` > 0 (`:52-53`); release length 1..40 (`:54`); opcode `0x3738` (`:55`) |
| `buildshow_text` | `tests/buildshow_test.cpp` (`tests/CMakeLists.txt:6-7`) | title `SlumsMS 1.6.0 (10600)` and the rc form keeping its suffix (`:18-21`); login label with the middle dot (`:24-25`); truncation inside an 8-char buffer (`:26-28`) |

`scripts/build.ps1` has **no stamp-specific check**: it prepares dependencies (`:4-5`), requires VS x86 tools
(`:7-9`), configures (`:11-14`), builds Release (`:15-16`), runs ctest (`:17-18`) and prints the output dir (`:19`).
Stamp validation reaches it only through the configure `FATAL_ERROR`s (`cmake/BuildStamp.cmake:16-48`) and the
`src/CMakeLists.txt:38-40` 16-bit check, which abort step `:13-14` with `Client configuration failed`.

**Server (JUnit):**

| Test class | Methods | Covers |
|---|---|---|
| `src/test/java/net/server/handlers/login/ClientVersionGateTest.java` | `offByDefault` `:35`, `aClientBelowTheMinimumOrWithoutAStampIsRefused` `:43`, `gmBypassOnlyWhenEnabled` `:52`, `blankMessageFallsBackToTheDefault` `:59`, `stampParses` `:66`, `goldenBytesFromTheClient` `:75`, `malformedOrUnknownStampsAreIgnored` `:83`, `formatTwoCarriesCaps` `:97`, `formatOneStillParsesWithNoCaps` `:111` | default gate off; build 0 and build N-1 refused; GM bypass; message fallback; the same golden bytes as the client test (`:76`, `:99`, `:106`); unknown format 3, format 2 without caps, negative build, 41-char release, 3-byte packet, truncated packet, newline in the release |
| `src/test/java/net/server/handlers/login/VersionGateOrderTest.java` | `anOutdatedClientIsRefusedBeforeTheLiveSessionIsKicked` `:105`, `autoRegisterDoesNotCreateAnAccountForAnOutdatedClient` `:115`, `onlyAVerifiedPasswordReachesTheGate` `:126`, `gmBypassUsesTheAccountsRealGmLevel` `:138`, `gmLevelIsReadOnlyWhenNeeded` `:148`, `accountGmLevelIsTheHighestCharacterLevel` `:157` | ordering: 2 packets + session closed + no `finishLogin`; no account row; loginok set membership; bypass reads the DB level |
| `src/test/java/net/server/handlers/login/QaBuildGateTest.java` | `theBitIsThirtyOneAndTheSwitchName` `:43`, `aQaBuildIsRefusedByDefaultEvenWithTheBuildGateOffAndForAGm` `:50`, `testAndRcAcceptItWithTheSwitch` `:59`, `anOrdinaryClientIsUnaffected` `:66` | bit 31 refusal regardless of `MIN_CLIENT_BUILD`/GM; the `allowQa` switch; normal caps pass |

**Auto-QA:** `t-vg-1` "An old client is told to update" (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:777-778`
→ `GameAutoQaWorld.clientGate` `:478-481`). It evaluates `ClientVersionGate.verdictFor` against the server's real
`MIN_CLIENT_BUILD`: PASS requires build N ALLOWED, N+1 ALLOWED, N-1 `REFUSED: ...`, and FAILs outright when the gate
is off (`src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:483-495`).

**Release-tool tests:** `tools/release/test_stage_client_lib.py:6-16` (the `release=`/`build=` parser: exact bytes
`:7-8`, CRLF/blank/unknown lines `:10-11`, a missing, negative or garbage `build=` `:13-16`);
`tools/qa-scripts/test_qascript.py:222-225` asserts the literal `CAP_QA_BUILD = 0x80000000` still exists in the gate
source.

**Not covered, and what a real client must confirm by hand:**

- No test builds the DLL and connects to a server, so the *ordering* "stamp arrives before the password" is only
  guaranteed by code reading (`src/bypass.cpp:433-449`) and by the server being stateless per connection. Confirm by
  logging in with a build one below the minimum and reading `Client version gate: refused ... (no stamp)` vs
  `client build N (<release>)` in the server log.
- `ClientDataCaps` (`src/bypass.cpp:399-419`) has no native test: the 16 img lookups, the throw path and the
  `VT_UNKNOWN` path are only exercised on a real client. Confirm with a `Data/Npc` missing `9990000.img` — the log
  must say `Npc/9990000.img is missing` and the server must frame Cody instead of the custom NPC.
- The popup itself: no test renders it. Confirm on a v83 client that reason 1 clears the login box with no stock
  dialog, and that the message is the configured text.
- The login-corner label is only tested as a *string* (`tests/buildshow_test.cpp`); the layer, the origin and the
  resolution-change rebuild (`src/buildshow.cpp:60-109`) are unchecked.
- `-DSLUMS_RELEASE_OVERRIDE` / `-DSLUMS_BUILD_NUMBER_OVERRIDE` have no test; the sticky-cache behaviour (`-U`) is
  documented only in `cmake/BuildStamp.cmake:12-13`.
- No tool compares the `MIN_CLIENT_BUILD` in `docs/release/<ver>.conf` with the staged client's `slums-build.txt`
  `build=` (see Known issues).
- The QA-cap path (`CAP_QA_BUILD`, `allowQa`) has tests but no producer in this branch, so it is untestable
  end-to-end here.

## Known issues

| Ref | Problem | Evidence |
|---|---|---|
| card b251 | Client crash entering Rose Garden: Abandoned Garden (211080100). Unrelated to the stamp, but the 1.6.4 gate at 10740 is what keeps pre-guard clients out of that map | `/root/work/STATUS-1.6.4.md:72`; `docs/release/1.6.4.conf:279-283` |
| card b270 | Launcher news manifest written without `news[]`. Adjacent: the launcher reads `slums-build.txt` as the client version, so a broken publish also breaks the version shown | `/root/work/STATUS-1.6.4.md:75`; `tools/ops/client-mcp-win/src/driver.py:525-528` |
| card b274 | Boss portals never send a portal-enter. No stamp interaction found; listed for the lane | `/root/work/STATUS-1.6.4.md:76` |
| card L15, card e14 | Client-side fixes / in-game exchange window: not stamp work, no version dependency found in this subsystem | `/root/work/STATUS-1.6.4.md:106`, `:124` |
| NEW-FINDING | Client README says the stamp is "format 1" while the code sends format 2. `README.md:24` reads `CP 0x3738 (format 1, int build, string release)`, but `src/buildstamp_proto.h:24` sets `kBuildStampFormat = 2` and caps are always written (`src/bypass.cpp:435`). A reader trusting the README will look for a 3-field packet that no current client sends | `README.md:24` vs `src/buildstamp_proto.h:24` |
| NEW-FINDING | The server's own release README repeats both stale facts: the shape `short format=1, int build, string release` (`:45-46`) and the pre-b205 formula `major*10000 + minor*100 + patch` (`:47`). A release engineer reading it would compute 10604 for 1.6.4 (old formula) instead of 10740, i.e. twenty builds below every candidate since 1.6.1 | `tools/release/README.md:45-47` vs `cmake/BuildStamp.cmake:25` |
| NEW-FINDING | Stale formula comment inside the wire header: "build is monotonic (VERSION major*10000 + minor*100 + patch ...)" — the code has used `(minor+1)*100 + patch*10` since b205, so the comment describes numbers nobody has built since 1.6.1 | `src/buildstamp_proto.h:11` vs `cmake/BuildStamp.cmake:25` |
| NEW-FINDING | Caps bit 1 (`CAPS_LOG_UPLOAD`) has no producer on this branch: the server asks a "supporting" client for logs after `@bug` (`src/main/java/server/reports/BugClientLogs.java:40`, `:67-69`; `src/main/java/net/server/channel/handlers/BugLogUploadHandler.java:14-16`) but no client file sets that bit, and `git log -S kCapLogUpload / -S 0x373B` over the client repo's `src/` finds nothing. The feature is server-side only, silently inactive | `src/main/java/server/reports/BugClientLogs.java:67-69`; no counterpart in `src/buildstamp_proto.h:25-26` |
| NEW-FINDING | Caps bit 31 (`CAP_QA_BUILD`) is likewise absent from this branch: the gate refuses a build nothing produces. It lives only on `client-170-qa-module`/`client-162-*` (`2f17894`, not an ancestor of `client-164-rc3`), while `docs/qa/client-scripts/README.md:63-64` and `tools/qa-scripts/test_qascript.py:222-225` treat it as present | `git merge-base --is-ancestor 2f17894 HEAD` → false; `src/main/java/net/server/handlers/login/ClientVersionGate.java:37` |
| NEW-FINDING | Nothing cross-checks the release conf's `MIN_CLIENT_BUILD` against the client actually staged. `stage-client:91-93` prints the stamp and only warns when `release`'s `major.minor.patch` differs from the version being staged; a conf/gate mismatch (e.g. `MIN_CLIENT_BUILD=10740` with a client actually built from `VERSION 1.6.3`, which stamps 10730) surfaces only as the `warn` at `:93`, never as a failure, so the deploy goes through and every 1.6.4 player is refused; it is caught only by a human reading two files. `stage_client_lib.py` parses `build=` and nothing consumes it for the gate | `tools/release/stage-client:91-93`; `docs/release/1.6.4.conf:284` |
| NEW-FINDING | An override-stamped build is not recoverable from git: tags `v1.6.0.2` and `v1.6.1-rc1..rc9` all carry `VERSION` = `1.6.0`, so the shipped numbers 10601/10680/10690/10700/10710 exist only in `slums-build.txt`, the release docs and PE resources. Rebuilding any such tag today produces 10600 with the old formula (and would produce a different number under the current formula) | `git show v1.6.1-rc9:VERSION` → `1.6.0`; `docs/release/1.6.1.conf:258-259`; `docs/release/1.6.0.3.conf:4` |
| NEW-FINDING | `src/slums.rc.in:18` still says "based on Kaentake" in `FileDescription` and `:25` splits the copyright, while `docs/PROVENANCE.md` records a MapleRoot Full Repack baseline plus Kaentake — the resource names one upstream of two | `src/slums.rc.in:18`, `:25`; `docs/PROVENANCE.md` |

## State

**working.** The chain is exercised end to end in production releases: `VERSION` → configure guards → compile
definitions → packet → gate → popup, with both sides pinned to the same golden bytes
(`tests/buildstamp_proto_test.cpp:35` = `src/test/java/net/server/handlers/login/ClientVersionGateTest.java:99`) and a
live gate at 10740 for 1.6.4 (`docs/release/1.6.4.conf:284`). Client numbers were confirmed shipped at 10600 (1.6.0),
10700 (1.6.1), 10721 (1.6.2) and 10740 (1.6.4 RC) by the release docs cited in the history table.

Partial corners, stated plainly:

- **QA build switch (caps bit 31): partial / not on this branch.** Server side implemented and tested
(`src/main/java/net/server/handlers/login/ClientVersionGate.java:37-41`, `QaBuildGateTest`), client side exists only
on other branches, so the feature is compiled-but-unreachable here.
- **Log-upload cap (bit 1): partial / server only.** `BugClientLogs` will never ask this client for logs.
- **The docs of the wire shape: stale in three places** (`README.md:24`, `tools/release/README.md:45-47`,
  `src/buildstamp_proto.h:11`), while the code and both test suites agree on format 2.
- **Verification by hand is still required for the send order and the caps probe** — no automated test covers
  `src/bypass.cpp:399-451`.

## Open questions

1. Where did 10601 (1.6.0.2) come from? No commit records the override; `VERSION` never held `1.6.0.2`, and the only
   evidence is `docs/release/1.6.0.3.conf:4` and `docs/research-121-crash-report.md:139`. Not reconstructible from
   this repo.
2. Should a 1.6.4 hotfix use the 10741..10749 override room, or is the room reserved for 1.6.2 only as
   `cmake/BuildStamp.cmake:9` says ("1.6.2 hotfix -> 10721..10729")? Nothing states the rule for later minor lines.
3. Is the format-1 path meant to stay forever? The server still accepts it
   (`src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:15`, `:43`) and `CustomNpcGate`/`DamageRank`
   keep build fallbacks for it, but no gate lower than 10600 has been honoured since 1.6.0, so the fallback is
   unreachable on live. Whether it should be removed is a policy call, not visible in code.
4. `MIN_CLIENT_BUILD_GM_BYPASS` reads the account's max `characters.gm`
   (`src/main/java/net/server/handlers/login/ClientVersionGate.java:126-140`) although the account-level GM flag lives
   on `accounts.gm` in most v83 schemas; that the characters table is authoritative here is taken from the comment at
   `:125` and not verified against the schema in this repo.
5. The 1.6.4 conf header says "No new client: MIN_CLIENT_BUILD stays 10721" (`docs/release/1.6.4.conf:10`) while line
   284 sets 10740 after the owner's 2026-11-05 decision (`:279-283`). The line wins; whether the header is meant to be
   corrected before the go/no-go is the owner's call.
