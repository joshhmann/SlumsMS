# Login handlers

## Purpose

Turn a TCP connection on the login port into an authenticated account session, then hand the client a world/channel
list and a character selection that ends in a per-character channel connection. The login server process part
(`net.server.handlers.login`) owns password/PIN/PIC checks, the build gate, automatic registration and the "which
channel socket do I give this player" decision; the channel side (`PlayerLoggedinHandler`) finishes the transition into
the world.

## Entry points

| Opcode / event | Handler | Registered |
|---|---|---|
| TCP accept on the login port | `net.netty.LoginServerInitializer.initChannel` | src/main/java/net/netty/LoginServerInitializer.java:14 |
| `PONG` | `KeepAliveHandler` (validateState always true) | src/main/java/net/PacketProcessor.java:111, src/main/java/net/server/handlers/KeepAliveHandler.java:28-37 |
| `CUSTOM_PACKET` | `CustomPacketHandler` (GM level 4 echo) | src/main/java/net/PacketProcessor.java:112, src/main/java/net/server/handlers/CustomPacketHandler.java:29-40 |
| `CLIENT_BUILD_STAMP` | `ClientBuildStampHandler` | src/main/java/net/PacketProcessor.java:121 |
| `LOGIN_PASSWORD` | `LoginPasswordHandler` | src/main/java/net/PacketProcessor.java:122 |
| `GUEST_LOGIN` | `GuestLoginHandler` | src/main/java/net/PacketProcessor.java:132 |
| `ACCEPT_TOS` | `AcceptToSHandler` | src/main/java/net/PacketProcessor.java:116 |
| `AFTER_LOGIN` | `AfterLoginHandler` | src/main/java/net/PacketProcessor.java:117 |
| `SERVERSTATUS_REQUEST` | `ServerStatusRequestHandler` | src/main/java/net/PacketProcessor.java:125 |
| `SERVERLIST_REQUEST` / `SERVERLIST_REREQUEST` | `ServerlistRequestHandler` | src/main/java/net/PacketProcessor.java:124, :118 |
| `RELOG` | `RelogRequestHandler` | src/main/java/net/PacketProcessor.java:123 |
| `CHARLIST_REQUEST` | `CharlistRequestHandler` | src/main/java/net/PacketProcessor.java:119 |
| `VIEW_ALL_CHAR` | `ViewAllCharHandler` | src/main/java/net/PacketProcessor.java:129 |
| `CHAR_SELECT` | `CharSelectedHandler` | src/main/java/net/PacketProcessor.java:120 |
| `CHAR_SELECT_WITH_PIC` | `CharSelectedWithPicHandler` | src/main/java/net/PacketProcessor.java:134 |
| `REGISTER_PIC` | `RegisterPicHandler` | src/main/java/net/PacketProcessor.java:133 |
| `VIEW_ALL_WITH_PIC` | `ViewAllCharSelectedWithPicHandler` | src/main/java/net/PacketProcessor.java:136 |
| `VIEW_ALL_PIC_REGISTER` | `ViewAllCharRegisterPicHandler` | src/main/java/net/PacketProcessor.java:137 |
| `REGISTER_PIN` | `RegisterPinHandler` | src/main/java/net/PacketProcessor.java:131 |
| `SET_GENDER` | `SetGenderHandler` | src/main/java/net/PacketProcessor.java:135 |
| `CHECK_CHAR_NAME` / `CREATE_CHAR` / `DELETE_CHAR` | `CheckCharNameHandler` / `CreateCharHandler` / `DeleteCharHandler` | src/main/java/net/PacketProcessor.java:126-128 |
| `PLAYER_LOGGEDIN` (channel socket) | `PlayerLoggedinHandler` | src/main/java/net/PacketProcessor.java:156 |
| `CHANGE_CHANNEL` (channel socket) | `ChangeChannelHandler` | src/main/java/net/PacketProcessor.java:144 |
| `WORLD_TRANSFER` (channel socket) | `TransferWorldHandler` | src/main/java/net/PacketProcessor.java:143 |
| `STRANGE_DATA` | `LoginRequiringNoOpHandler` (singleton, requires logged in) | src/main/java/net/PacketProcessor.java:145, src/main/java/net/server/handlers/LoginRequiringNoOpHandler.java:28-40 |

Dispatch is one place: `Client.channelRead` looks the opcode up, then calls `handler.validateState(this)` and only then
`handlePacket` (src/main/java/client/Client.java:210-221); a `Throwable` from a handler is logged ("Error in packet
handler {}") and swallowed (src/main/java/client/Client.java:222-227). The default `validateState` is `c.isLoggedIn()`
(src/main/java/net/AbstractPacketHandler.java:29-31); login-phase handlers override it to `!c.isLoggedIn()` (for example
src/main/java/net/server/handlers/login/LoginPasswordHandler.java:50-52, src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:20-22,
src/main/java/net/server/handlers/login/AcceptToSHandler.java:14-16, src/main/java/net/server/handlers/login/RelogRequestHandler.java:31-33).

## How it works

1. **Connection.** `Server` reads the port once (`Integer.getInteger("mapleroot.login-port", 8485)`,
   src/main/java/net/server/Server.java:1018) and starts the login listener (src/main/java/net/server/Server.java:1019-1021). On accept,
   `LoginServerInitializer.initChannel` creates a `Client` bound to `LoginServer.WORLD_ID = -1`,
   `CHANNEL_ID = -1` (src/main/java/net/netty/LoginServer.java:10-11) with a fresh session id
   (src/main/java/net/netty/LoginServerInitializer.java:18-21), seeds the per-IP "last account" cache
   (src/main/java/net/netty/LoginServerInitializer.java:22 → src/main/java/client/Client.java:1380-1404) and refuses the socket outright when the per-IP login limit
   is hit (`canStartLoginSession`, src/main/java/net/netty/LoginServerInitializer.java:24-27 → SessionCoordinator.canStartLoginSession,
   src/main/java/net/server/coordinator/session/SessionCoordinator.java:127-141, using `LOGIN_MAX_SESSIONS_PER_IP` and
   `LOGIN_MAX_CONNECTS_PER_IP` / `LOGIN_CONNECT_WINDOW_SECONDS`).
2. **Build stamp.** Before the password packet the client sends `CLIENT_BUILD_STAMP`; `ClientBuildStampHandler.parse`
   validates format ∈ {1,2}, a non-negative build, a ≤40-char release string and the caps word for format 2
   (src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:38-68) and stores it on the client
   (`Client.setClientStamp`, src/main/java/client/Client.java:1285). A malformed stamp is silently dropped, which makes
   the client look like build 0 (src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:26-29).
3. **Server status.** `ServerStatusRequestHandler` answers with the world's capacity band
   (`World.getWorldCapacityStatus`: 2 = full, 1 = ≥80 % of `channels × CHANNEL_LOAD`, 0 = free) or 2 when the world does
   not exist (src/main/java/net/server/handlers/login/ServerStatusRequestHandler.java:34-42,
   src/main/java/net/server/world/World.java:663-681).
4. **World list.** `ServerlistRequestHandler` emits one `getServerList` per running world, the end marker, the home
   world preselection and the recommended list (src/main/java/net/server/handlers/login/ServerlistRequestHandler.java:40-64;
   ticket 41 removed the hardcoded world 0 — `selectWorld(homeWorldId)` at :61).
5. **Password.** `LoginPasswordHandler` reads login/password, skips 6 bytes and builds the `Hwid` from the next 4
   (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:63-70), then calls
   `Client.login(login, pwd, hwid, false)` (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:71). That method:
   counts the attempt and closes the session past 4 (src/main/java/client/Client.java:699-705); selects
   `id, password, gender, banned, pin, pic, characterslots, tos, ip, language` from `accounts` by name
   (src/main/java/client/Client.java:708-710); returns 3 for a banned account (src/main/java/client/Client.java:731-733); maps
   `AccountPasswords.check` to `23`/`-23` (ToS not accepted, legacy hash) / `0` / `-10` (bcrypt migration due) / `4`
   (wrong password) (src/main/java/client/Client.java:735-743, src/main/java/client/AccountPasswords.java:42-60); and only then turns a
   verified result into `7` "already logged in" via `alreadyLoggedInCheck` (src/main/java/client/Client.java:747, :791-796). On 0 or 4 it
   asks the session coordinator for permission and maps the refusal to a login-failed code
   (src/main/java/client/Client.java:759-784: `REMOTE_LOGGEDIN`→17, `REMOTE_REACHED_LIMIT`→13, `REMOTE_PROCESSING`→10,
   `MANY_ACCOUNT_ATTEMPTS`→16, other→8).
6. **Build gate.** With a verified password (0/7/23/-23/-10) `ClientVersionGate.refuseIfOutdated` runs *before* the
   already-logged-in kick and before automatic registration (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:72-77).
   The gate refuses a QA-caps build unless `-Dslums.client.allowQa` is set (src/main/java/net/server/handlers/login/ClientVersionGate.java:100-108), then a
   build below `MIN_CLIENT_BUILD` with no GM bypass (src/main/java/net/server/handlers/login/ClientVersionGate.java:109-122); both paths send a
   `serverNotice` + `getLoginFailed(1)` and close the login session.
7. **Automatic registration.** When `AUTOMATIC_REGISTER` is on and the account does not exist (`loginok == 5`), the
   handler re-runs the gate with GM level forced to 0, asks `AutoRegisterGuard.check` (password length, reserved name,
   per-IP cooldown, optional per-IP cap) (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:78-88; src/main/java/net/server/handlers/login/AutoRegisterGuard.java:52-83), inserts
   `accounts (name, password, birthday, tempban)` with `AccountPasswords.hashForNewAccount`
   (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:89-95; src/main/java/client/AccountPasswords.java:63-65), records the IP, fires the Discord webhook and then
   re-runs `c.login` in the `finally` (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:101-114).
8. **Already-logged-in kick.** `loginok == 7` clears `accounts.loggedin`, walks every world for the account's
   characters (ticket 41, src/main/java/net/server/handlers/login/LoginPasswordHandler.java:132), removes them from channel and map and disconnects the old
   session, then closes the stale login client found through `Server.getConnectedClient` (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:117-156,
   src/main/java/net/server/Server.java:214-225).
9. **Bans and finish.** Bcrypt migration (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:165-176), banned IP/MAC → 3, tempban → temp-ban
   packet, `loginok == 3` → permanent-ban packet, any other non-zero → `getLoginFailed` + `closeLoginSession`
   (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:178-196). Success requires `c.finishLogin() == 0` (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:197-202 →
   src/main/java/client/Client.java:616-629), which writes `LOGIN_LOGGEDIN` and then sends `getAuthSuccess`, registers the 10-minute login
   idle entry and the account→client map (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:223-227, src/main/java/net/server/Server.java:2024-2031, :214-219).
10. **ToS.** When `tos == 0` the login result is 23/-23 and the client shows the terms; `ACCEPT_TOS` →
    `AcceptToSHandler` requires exactly one byte `1`, calls `Client.acceptToS`, then `finishLogin` and re-sends
    `getAuthSuccess` (src/main/java/net/server/handlers/login/AcceptToSHandler.java:19-29).
11. **PIN.** `getAuthSuccess` carries the PIN enable byte (`ENABLE_PIN && !canBypassPin ? 0 : 1`) and the PIC mode byte
    (0 register, 1 ask, 2 disabled) (src/main/java/tools/PacketCreator.java:704-705). The client's `AFTER_LOGIN` packets
    drive `AfterLoginHandler`: (1,1) asks for or registers the PIN, (1,0) checks it, (2,0) checks then registers, (0,5)
    closes the session back to `LOGIN_NOTLOGGEDIN` (src/main/java/net/server/handlers/login/AfterLoginHandler.java:33-63).
    `Client.checkPin` is a no-op when PIN is off or bypassed, counts failures, closes the session past 5 and registers a
    bypass entry on success (src/main/java/client/Client.java:647-662). `REGISTER_PIN` → `RegisterPinHandler` sets the
    PIN and always ends the login session (src/main/java/net/server/handlers/login/RegisterPinHandler.java:35-49).
12. **Character list.** `CHARLIST_REQUEST` → `CharlistRequestHandler` reads an unused byte, the world byte and the
    channel byte, answers status 2 when the world is unknown/full or the channel does not exist, then stores
    world/channel and calls `Client.sendCharList` (src/main/java/net/server/handlers/login/CharlistRequestHandler.java:35-55,
    src/main/java/client/Client.java:380-382 → `PacketCreator.getCharList`, src/main/java/tools/PacketCreator.java:880-892:
    status byte, per-character entries, PIC mode byte, slot count). `VIEW_ALL_CHAR` → `ViewAllCharHandler` gates on
    `canRequestCharlist`, loads `Server.loadAccountCharlist`, caps at 60 characters (client crash at 61), pads the last
    row and sends `showAllCharacter` + one `showAllCharacterInfo` per world
    (src/main/java/net/server/handlers/login/ViewAllCharHandler.java:35-57).
13. **Character select without PIC.** `CHAR_SELECT` → `CharSelectedHandler`: reads charId, MACs and the host string,
    builds the `Hwid` (invalid → `getAfterLoginError(17)`), applies MAC/HWID bans, asks
    `SessionCoordinator.attemptGameSession`, requires `Server.haveCharacterEntry`, sets the world from
    `getCharacterWorld`, refuses a full world and a missing socket, then `unregisterLoginState` +
    `setCharacterOnSessionTransitionState(charId)` and sends `getServerIP(addr, port, charId)`
    (src/main/java/net/server/handlers/login/CharSelectedHandler.java:53-109; src/main/java/net/server/Server.java:1978-1992 records the
    IP→charId hand-off, src/main/java/client/Client.java:1180-1185 sets `LOGIN_SERVER_TRANSITION` and remembers the build stamp in
    `ClientStampCarry`).
14. **Character select with PIC.** `CHAR_SELECT_WITH_PIC` reads the PIC *first*, then charId (:32-34), checks bans and
    character ownership before the PIC, and only on `checkPic` success does the attempt/socket/transition sequence,
    else it answers `wrongPic` (src/main/java/net/server/handlers/login/CharSelectedWithPicHandler.java:32-93).
    `REGISTER_PIC` and the two view-all variants repeat the same shape; `RegisterPicHandler` stores the PIC only when
    the account has none and closes the session otherwise (src/main/java/net/server/handlers/login/RegisterPicHandler.java:51-95).
    View-all variants pick a random channel in the character's world instead of the requested one
    (src/main/java/net/server/handlers/login/ViewAllCharSelectedHandler.java:98-104, src/main/java/net/server/handlers/login/ViewAllCharSelectedWithPicHandler.java:72-73).
15. **PlayerLoggedin (channel socket).** The client connects to the advertised port and sends `PLAYER_LOGGEDIN` with the
    character id. `PlayerLoggedinHandler` takes the per-account lock, resolves world/channel (channel 1 as fallback),
    takes the HWID from the login-session cache for newcomers/owned alts or from the existing character
    (src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:102-140), validates the in-transition
    character id (:142-145), restores the carried build stamp (:147), loads the character (:156-171), requires login
    state `LOGIN_SERVER_TRANSITION` (:195-207) and only then writes `LOGIN_LOGGEDIN` (:208); the rest of the method
    places the character in the channel and map, restores buffs/diseases/cooldowns, keymaps, quests and rates
    (:219-505).
16. **Channel change.** `CHANGE_CHANNEL` → `ChangeChannelHandler` reads the target channel, stamps the autoban manager,
    disconnects on a same-channel request and ignores the packet while a cash shop/minigame/player shop is open
    (src/main/java/net/server/channel/handlers/ChangeChannelHandler.java:36-49). `Client.changeChannel` saves the
    character, moves buffs to `PlayerBuffStorage`, sets the session transition and sends `getChannelChange`
    (src/main/java/client/Client.java:1658-1721).
17. **Relog and world transfer.** `RELOG` → `RelogRequestHandler` answers `getRelogResponse` from a non-logged-in
    session (src/main/java/net/server/handlers/login/RelogRequestHandler.java:29-38,
    src/main/java/tools/PacketCreator.java:1201). `WORLD_TRANSFER` → `TransferWorldHandler` checks the birthday, the
    `ALLOW_CASHSHOP_WORLD_TRANSFER` switch, world count, character eligibility and the `worldtransfers` table
    (pending row → 6, cooldown → 7, ready → 0) (src/main/java/net/server/channel/handlers/TransferWorldHandler.java:40-78).

Login-guard summary:

| Guard | Enforced by | Evidence |
|---|---|---|
| Autologin | **Nothing** — removed in 1.5.1; the `autologin` column is still read but no decision uses it | src/main/java/net/server/handlers/login/LoginPasswordHandler.java:162-163, src/main/java/tools/PacketCreator.java:888, src/main/java/client/command/CommandsExecutor.java:312, src/main/java/client/Client.java:1385-1394 |
| Password (attempt cap 4) | `Client.login` + `AccountPasswords.check` | src/main/java/client/Client.java:697-758, :699-705; src/main/java/client/AccountPasswords.java:42-60 |
| Password verified before anything else | `Client.alreadyLoggedInCheck` | src/main/java/client/Client.java:747, :791-796 |
| PIC | `Client.checkPic` (off/bypass short-circuit, 5 tries, bypass entry) | src/main/java/client/Client.java:680-695 |
| PIC mode advertised to the client | `PacketCreator.getAuthSuccess` / `getCharList` | src/main/java/tools/PacketCreator.java:705, :889 |
| PIN | `Client.checkPin` | src/main/java/client/Client.java:647-662 |
| PIN prompts/registration | `AfterLoginHandler`, `RegisterPinHandler` | src/main/java/net/server/handlers/login/AfterLoginHandler.java:33-63, src/main/java/net/server/handlers/login/RegisterPinHandler.java:35-49 |
| Build stamp / min build | `ClientVersionGate` + `ClientBuildStampHandler` | src/main/java/net/server/handlers/login/ClientVersionGate.java:100-123, src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:20-69 |
| QA client build | `ClientVersionGate.qaBuildRefused` (`-Dslums.client.allowQa`) | src/main/java/net/server/handlers/login/ClientVersionGate.java:81-83, :100-108 |
| Per-IP login connection cap | `SessionCoordinator.canStartLoginSession` | src/main/java/net/server/coordinator/session/SessionCoordinator.java:127-141 |
| Multi-client / HWID association | `SessionCoordinator.attemptLoginSession` / `attemptGameSession` | src/main/java/net/server/coordinator/session/SessionCoordinator.java:167-238 |
| Banned IP / MAC / HWID | `LoginPasswordHandler` (IP/MAC) and the select handlers (MAC/HWID) | src/main/java/net/server/handlers/login/LoginPasswordHandler.java:178-181, src/main/java/net/server/handlers/login/CharSelectedHandler.java:77-80 |
| Character belongs to the account / IP→charId hand-off | `Server.haveCharacterEntry`, `Server.validateCharacteridInTransition` | src/main/java/net/server/Server.java:1518-1526, :1978-1992 (switch `USE_IP_VALIDATION`) |
| Login idle timeout (10 min) | `Server.registerLoginState` + `disconnectIdlesOnLoginState` | src/main/java/net/server/Server.java:2024-2041, :2042-2069 |

## Data

| Store | Key → value | Written/read at |
|---|---|---|
| `accounts` row | `id, name, password, gender, banned, pin, pic, characterslots, tos, ip, language, loggedin, lastlogin, birthday, tempban` | src/main/java/client/Client.java:708-743, :894-921, :923-961 |
| `accounts.ip` as "last address seen" | also the counter source for the per-IP registration cap | src/main/java/net/server/handlers/login/AutoRegisterGuard.java:103-112 |
| `worldtransfers` | pending/cooldown row per character | src/main/java/net/server/channel/handlers/TransferWorldHandler.java:59-72 |
| `SessionCoordinator.onlineClients` | accountId → `Client` | src/main/java/net/server/coordinator/session/SessionCoordinator.java:62, :108-121 |
| `SessionCoordinator.onlineRemoteHwids` | set of HWIDs currently in a session | src/main/java/net/server/coordinator/session/SessionCoordinator.java:63, :184-191, :218-229 |
| `SessionCoordinator.hostHwidCache` | remote host (and bare remote address) → `Hwid` | src/main/java/net/server/coordinator/session/SessionCoordinator.java:65, :202-203, :314-322 |
| `SessionCoordinator.loginLimiter` | remote address → open sessions/connect timestamps | src/main/java/net/server/coordinator/session/SessionCoordinator.java:64, :127-141 |
| `LoginStorage.loginHistory` | accountId → list of attempt instants | src/main/java/net/server/coordinator/login/LoginStorage.java:37-53 |
| `LoginBypassCoordinator.loginBypass` | (Hwid, accId) → (picAllowed, expiry) | src/main/java/net/server/coordinator/login/LoginBypassCoordinator.java:49-75 |
| `Server.connectedClients` | account name → login `Client` | src/main/java/net/server/Server.java:214-225 |
| `Server.transitioningChars` | remote host → charId awaiting the channel socket | src/main/java/net/server/Server.java:1967-2022 |
| `Server.inLoginState` | `Client` → expiry (now + 600000) | src/main/java/net/server/Server.java:2024-2041 |
| `ClientStampCarry.pending` | charId → build stamp across the socket hop | src/main/java/net/server/coordinator/session/ClientStampCarry.java:24-43 |

Packets the login server sends: `LOGIN_STATUS` (`getAuthSuccess` src/main/java/tools/PacketCreator.java:680-708,
`getLoginFailed` :608-614), `SELECT_CHARACTER_BY_VAC` (`getAfterLoginError` :636-640, short reason),
`SERVERLIST` (:764), end-of-list (:801), `SERVERSTATUS` (:816), `SERVER_IP` (:830), `CHANGE_CHANNEL` (:848),
`CHARLIST` (:880), PIN/PIC response packets (:726-748), `RELOG_RESPONSE` (:1201), `serverNotice` (:1228).

## Switches

| Switch | Default | Effect on this subsystem | Evidence |
|---|---|---|---|
| `ENABLE_PIN` (config.yaml) | none declared in `ServerConfig` → yaml value | turns the PIN prompt and `checkPin` on | src/main/java/config/ServerConfig.java:34, src/main/java/client/Client.java:648 |
| `ENABLE_PIC` (config.yaml) | none declared | turns PIC registration/ask on | src/main/java/config/ServerConfig.java:33, src/main/java/client/Client.java:681 |
| `BYPASS_PIC_EXPIRATION` / `BYPASS_PIN_EXPIRATION` (config.yaml, minutes) | none declared | how long a successful check skips the next prompt; 0 = never register | src/main/java/config/ServerConfig.java:36-37, src/main/java/net/server/coordinator/login/LoginBypassCoordinator.java:62-74 |
| `AUTOMATIC_REGISTER` (config.yaml) | none declared | account is created on a first login instead of returning "not registered" | src/main/java/config/ServerConfig.java:39, src/main/java/net/server/handlers/login/LoginPasswordHandler.java:78 |
| `AUTO_REGISTER_MIN_PASSWORD_LENGTH` | 6 | minimum password for auto-registration | src/main/java/config/ServerConfig.java:42, src/main/java/net/server/handlers/login/AutoRegisterGuard.java:63 |
| `AUTO_REGISTER_MAX_ACCOUNTS_PER_IP` | 0 (no cap; owner 2026-10-26 chose any number per IP) | per-IP account cap | src/main/java/config/ServerConfig.java:43-44, src/main/java/net/server/handlers/login/AutoRegisterGuard.java:73-80 |
| `AUTO_REGISTER_IP_COOLDOWN_MINUTES` | 1 | gap between new accounts from one address | src/main/java/config/ServerConfig.java:44, src/main/java/net/server/handlers/login/AutoRegisterGuard.java:69-72 |
| `MIN_CLIENT_BUILD` (config.yaml) | 0 in `ServerConfig` (gate off) | build gate; clients without a stamp count as 0 | src/main/java/config/ServerConfig.java:61, src/main/java/net/server/handlers/login/ClientVersionGate.java:47-53 |
| `CLIENT_UPDATE_MESSAGE` | "Your game is out of date: open the SlumsMS launcher to update." | notice text sent with the silent refusal | src/main/java/config/ServerConfig.java:62, src/main/java/net/server/handlers/login/ClientVersionGate.java:67-70 |
| `MIN_CLIENT_BUILD_GM_BYPASS` | false | GM characters (highest `characters.gm` on the account) may log in on an old build | src/main/java/config/ServerConfig.java:64, src/main/java/net/server/handlers/login/ClientVersionGate.java:113-125 |
| `-Dslums.client.allowQa=true` | false | accepts the QA client build (caps bit 31) | src/main/java/net/server/handlers/login/ClientVersionGate.java:37-41, :96 |
| `BCRYPT_MIGRATION` (config.yaml) | none declared | rehashes a legacy password to bcrypt at login (`loginok -10/-23`) | src/main/java/config/ServerConfig.java:71, src/main/java/net/server/handlers/login/LoginPasswordHandler.java:165-176, src/main/java/client/AccountPasswords.java:63-65 |
| `DETERRED_MULTICLIENT` (config.yaml) | none declared | when off, `attemptLoginSession`/`attemptGameSession` accept everything and just record the HWID | src/main/java/config/ServerConfig.java:73, src/main/java/net/server/coordinator/session/SessionCoordinator.java:168-171, :201-205 |
| `MAX_ALLOWED_ACCOUNT_HWID` | none declared | how many distinct HWIDs may use one account | src/main/java/config/ServerConfig.java:78, src/main/java/net/server/coordinator/session/SessionCoordinator.java:85 |
| `MAX_ACCOUNT_LOGIN_ATTEMPT` / `LOGIN_ATTEMPT_DURATION` | none declared | per-account attempt budget in `LoginStorage` | src/main/java/config/ServerConfig.java:79-80, src/main/java/net/server/coordinator/login/LoginStorage.java:39-53 |
| `LOGIN_MAX_SESSIONS_PER_IP` | 8 | open login sessions per address | src/main/java/config/ServerConfig.java:83, src/main/java/net/server/coordinator/session/SessionCoordinator.java:131-133 |
| `LOGIN_MAX_CONNECTS_PER_IP` | 30 | new login connections per address per window | src/main/java/config/ServerConfig.java:85, src/main/java/net/server/coordinator/session/SessionCoordinator.java:131-133 |
| `LOGIN_CONNECT_WINDOW_SECONDS` | 60 | window in seconds | src/main/java/config/ServerConfig.java:86, src/main/java/net/server/coordinator/session/SessionCoordinator.java:133 |
| `LANHOST` / `LOCALHOST` | none declared | the address handed to LAN/local clients instead of the configured one | src/main/java/config/ServerConfig.java:90-91, src/main/java/net/server/Server.java:352-371 |
| `USE_IP_VALIDATION` (config.yaml) | none declared | when false the IP→charId hand-off check always passes | src/main/java/config/ServerConfig.java:299, src/main/java/net/server/Server.java:1978-1992 |
| `ALLOW_CASHSHOP_WORLD_TRANSFER` | none declared | world transfer availability | src/main/java/config/ServerConfig.java:373, src/main/java/net/server/channel/handlers/TransferWorldHandler.java:50 |
| `WORLD_TRANSFER_COOLDOWN` | `NAME_CHANGE_COOLDOWN` (30 days) | cooldown between transfers | src/main/java/config/ServerConfig.java:416, src/main/java/net/server/channel/handlers/TransferWorldHandler.java:68 |
| `-Dmapleroot.login-port` | 8485 | login listener port | src/main/java/net/server/Server.java:1018 |

Live (docs/release/1.6.4.conf) sets `MIN_CLIENT_BUILD=10740` (:284, with an owner note dated 2026-11-05 ~09:30 UTC that
the 1.6.4 client is the minimum for everyone) while the same file's header still says "MIN_CLIENT_BUILD stays 10721"
(:10); `/root/work/OWNER-DECISIONS.md:43` repeats 10721. The conf has no `ENABLE_PIC`, `ENABLE_PIN`,
`AUTOMATIC_REGISTER`, `DETERRED_MULTICLIENT` or `LOGIN_*` keys, so live runs whatever `ServerConfig`'s yaml holds;
those values are not in this repo. TEST's `start-test.sh` lives elsewhere, so TEST's flag set is unknown here.

## Invariants

- A handler only runs for the state its `validateState` allows; login-phase handlers require `!isLoggedIn()`, the
  channel hand-off handler also requires `!isLoggedIn()` (src/main/java/client/Client.java:217,
  src/main/java/net/AbstractPacketHandler.java:29-31, src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:93-95).
- `LOGIN_LOGGEDIN` (2) is written in exactly two places on the login path: `Client.finishLogin` (src/main/java/client/Client.java:623) and
  `PlayerLoggedinHandler` after the transition check (src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:208). `updateLoginState(2)` also
  overwrites any other online client for that account (src/main/java/client/Client.java:896-898 → src/main/java/net/server/coordinator/session/SessionCoordinator.java:108-121).
- The "already logged in" answer (7) is only produced after the password matched (src/main/java/client/Client.java:791-796).
- A `LOGIN_SERVER_TRANSITION` older than 30 s is treated as not logged in and reset (src/main/java/client/Client.java:941-948).
- Client-supplied world ids are ignored on select: the world comes from `Server.getCharacterWorld` and the character
  must belong to the account (`haveCharacterEntry`) before any socket is sent
  (src/main/java/net/server/handlers/login/CharSelectedHandler.java:83-88, src/main/java/net/server/handlers/login/ViewAllCharSelectedHandler.java:56, :85-90).
- The channel socket must present the same charId the login socket registered for that remote host
  (`validateCharacteridInTransition`, src/main/java/net/server/Server.java:1978-1992), and `PlayerLoggedinHandler` requires the account row to
  still say `LOGIN_SERVER_TRANSITION` (src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:195-207).
- The build stamp survives the socket hop through `ClientStampCarry` (src/main/java/client/Client.java:1180-1185,
  src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:147).
- A character list is capped at 60 entries (src/main/java/net/server/handlers/login/ViewAllCharHandler.java:35).

## Origin

| File | Origin | Evidence |
|---|---|---|
| All handlers except the four below, plus `KeepAliveHandler`, `CustomPacketHandler`, `LoginRequiringNoOpHandler`, `ChangeChannelHandler`, `LoginStorage`, `LoginBypassCoordinator` | base (byte-identical to Cosmic) | `diff -q` against /root/workspace/cosmicms/<path> identical |
| `LoginPasswordHandler` | modified | 142 diff lines vs upstream: version gate, auto-register, kick-by-world, webhook |
| `PlayerLoggedinHandler` | modified | 108 diff lines: owned-alt login lease, stamp restore, ticket-41 world handling |
| `ServerlistRequestHandler` | modified | 26 diff lines: extracted `serverListPackets`, home-world preselection (ticket 41) |
| `ViewAllCharHandler` | modified | 6 diff lines |
| `CreateCharHandler` / `DeleteCharHandler` | modified | 56 / 12 diff lines (item whitelist; ticket 189 marriage block at src/main/java/net/server/handlers/login/DeleteCharHandler.java:42-45, :66-69) |
| `TransferWorldHandler` | modified | 8 diff lines |
| `PacketProcessor` | modified | 182 diff lines (handler table rebuilt) |
| `AutoRegisterGuard`, `ClientBuildStampHandler`, `ClientVersionGate` | custom | absent from the upstream tree |

Oldest commits for the package: `git log --follow` on src/main/java/net/server/handlers/login reaches 59330e4fe
(2026-10-20), e07a2f1f1 (2026-10-19) and 03d13cb21 (2026-10-18, the base-v83 repack). The three root handlers and
`net/server/coordinator/login` exist only since 03d13cb21.

## Tests and QA

| Test | Covers |
|---|---|
| src/test/java/net/server/handlers/login/ClientVersionGateTest.java:34-116 | gate off by default, refusal below minimum/no stamp, GM bypass, blank message fallback, stamp parsing |
| src/test/java/net/server/handlers/login/VersionGateOrderTest.java:104-156 | gate before the live-session kick, gate before auto-registration, only a verified password reaches the gate, GM bypass reads the account's real level |
| src/test/java/net/server/handlers/login/QaBuildGateTest.java:42-66 | caps bit 31, QA build refused by default, accepted with the switch |
| src/test/java/net/server/handlers/login/AutoRegisterGuardTest.java:35-77 | short password, reserved name, per-IP cap, cooldown |
| src/test/java/net/server/handlers/login/RegistrationWebhookTest.java:7-13 | webhook skipped/built without touching account state |
| src/test/java/net/server/handlers/login/ServerlistRequestHandlerTest.java:43-44 | one world listed and preselected |
| src/test/java/client/LoginKickTest.java:13-29 | wrong password on an online account stays 4; right password on an online account is 7; offline keeps its result |
| src/test/java/client/command/AutoLoginRemovedTest.java:19-27 | `@autologin` is not a command; no login/PIC decision reads the flag |
| src/test/java/net/server/handlers/login/CreateCharLookTest.java | creation look validation |

Auto-QA: `t-vg-1` "An old client is told to update" (probe `clientGate`, auto)
(src/main/java/soloMapling/autoqa/AutoQaCatalog.java:777-779), `t-ch-1` channel split (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:780-781) and
the manual `t-cc-1` character-creation check on the login screen (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:775). No `!qa` scenario under
src/main/java/soloMapling/qa drives a login.

Not covered: nothing exercises the real login packet sequence end to end — no test opens a login socket, sends
`CLIENT_BUILD_STAMP` + `LOGIN_PASSWORD` + `CHARLIST_REQUEST` + `CHAR_SELECT` and asserts the socket hop; `Client.login`
is only unit-tested through `alreadyLoggedInCheck`; PIN/PIC prompt state machines (`AfterLoginHandler`,
`RegisterPinHandler`, `SetGenderHandler`) have no test; `getInetSocket` address rewriting (LAN/local, `channels` map
miss) is untested; `LoginBypassCoordinator` has no test at all, which is why the mismatch below survived.

## Known issues

- b268 (S2, Ops, branch 162-log-silence): the ops login-port check stayed green while Live was hung; the monitor was
  extended to game-loop log silence (/root/work/STATUS-1.6.4.md:61).
- b284 / b286 (S3, QA tool): auto-QA lane interference and lost batched ready events — they affect `t-vg-1`/`t-ch-1`
  verdict reliability, not the login code (/root/work/STATUS-1.6.4.md:80, :82).
- Ticket 104 (launcher login) is an EXPLORE with level 1 pulled in: a one-time game pass in place of the password is
  the proposed safe shape; nothing of it is in the server yet (docs/tickets/104-launcher-login.md:1-30).
- `MIN_CLIENT_BUILD` contradiction: docs/release/1.6.4.conf:10 says 10721, :284 sets 10740, and
  /root/work/OWNER-DECISIONS.md:43 says unchanged at 10721. Whichever ships, the header text is wrong.
- NEW-FINDING: src/main/java/net/server/coordinator/login/LoginBypassCoordinator.java:77-81 builds the removal key as
  `Pair<String, Integer>` while the map is keyed by `Pair<Hwid, Integer>` and `registerLoginBypassEntry` only ever
  inserts `Pair<Hwid, Integer>` (:65). `Pair.equals` compares the left values by `equals` after a `getClass` guard
  (src/main/java/tools/Pair.java:100-110), so a `String` never equals an `Hwid` (a record,
  src/main/java/net/server/coordinator/session/Hwid.java:5): `unregisterLoginBypassEntry` never removes anything, so
  `!enableauth` (src/main/java/client/command/commands/gm0/EnableAuthCommand.java:39) cannot clear a bypass.
- NEW-FINDING: src/main/java/net/server/Server.java:355 calls `getIP(world, channel).split(":")` *outside* the
  `try` at :362-366, and `getIP` (src/main/java/net/server/Server.java:342-350) returns `null` when the world has no entry for that channel,
  so selecting such a channel throws a `NullPointerException` the try/catch cannot see. The three callers only
  null-check the returned array
  (src/main/java/net/server/handlers/login/CharSelectedHandler.java:95-99, src/main/java/net/server/handlers/login/CharSelectedWithPicHandler.java:70-74, src/main/java/net/server/handlers/login/ViewAllCharSelectedHandler.java:106-110), so
  the exception surfaces as the generic "Error in packet handler" log in src/main/java/client/Client.java:222-227.
- NEW-FINDING: src/main/java/net/server/coordinator/session/SessionCoordinator.java:278-281 makes `closeSession`
  null-safe only in appearance: `fetchInTransitionSessionClient` returns `null` when the host has no game-session HWID
  (:258-264), and the next line dereferences it (`client.getHwid()`, :283). No current caller passes `null`, so this is
  latent, not live.
- NEW-FINDING: src/main/java/client/Client.java:1385-1394 still selects `autologin`/`autoch`/`autochar` into fields that
  no login decision reads. Dead columns kept after the 1.5.1 autologin removal; either delete the query columns or the
  comment at src/main/java/net/server/handlers/login/LoginPasswordHandler.java:162-163 becomes misleading.

## State

working — the flow described above is the one live runs: every step is a registered handler with a working packet
builder, the guards are unit-tested (ClientVersionGateTest, VersionGateOrderTest, LoginKickTest, AutoRegisterGuardTest,
ServerlistRequestHandlerTest), and the four defects listed are on edge paths (`!enableauth` on a bypass entry,
a missing channel entry, a null client, dead columns) rather than the connect→charlist→select→channel path. No evidence
here for TEST's configuration, so "working" is a statement about the code, not about TEST/live settings.

## Open questions

- Live's actual `ENABLE_PIC`, `ENABLE_PIN`, `BYPASS_*_EXPIRATION`, `AUTOMATIC_REGISTER`, `DETERRED_MULTICLIENT`,
  `USE_IP_VALIDATION` and `LOGIN_*` values: not in docs/release/1.6.4.conf and `config.yaml` is not readable here.
- TEST's `-D` set (`start-test.sh` is on another host); in particular whether TEST passes
  `-Dslums.client.allowQa=true` today, which docs/qa/client-scripts/README.md:64, :244 says it needs.
- Which `MIN_CLIENT_BUILD` actually goes up: 10721 (conf header, /root/work/OWNER-DECISIONS.md:43) or 10740 (conf :284).
- Whether `DETERRED_MULTICLIENT` is on anywhere: with it off, both the HWID ban checks and
  `MAX_ALLOWED_ACCOUNT_HWID` are bypassed (src/main/java/net/server/coordinator/session/SessionCoordinator.java:168-171, :201-205).
- Whether `Pair`'s `getClass` key comparison is intentional; if it is, the only caller of
  `unregisterLoginBypassEntry` is dead code and should be removed with the method.
