# Network and packets

## Purpose

Carry the base v83 wire protocol between a real client (or a headless test client) and the game server: accept TCP on a login port and one port per channel, exchange the unencrypted hello, encrypt/decrypt every following packet, and route each inbound opcode to exactly one handler. One `Client` object per socket owns the netty `Channel` and the opcode → handler table for its world/channel. Outbound traffic goes the other way through a single `sendPacket` funnel that is also the hook for the custom-NPC filter and QA packet metering.

## Entry points

| What | Where |
|---|---|
| Login listener starts | src/main/java/net/server/Server.java:1018 (`Integer.getInteger("mapleroot.login-port", 8485)`), :1019, :1063-1067 |
| Channel listeners start | src/main/java/net/server/channel/Channel.java:172-176 (called from :144) |
| Login accept | src/main/java/net/netty/LoginServer.java:19-28 → src/main/java/net/netty/LoginServerInitializer.java:13-30 |
| Channel accept | src/main/java/net/netty/ChannelServer.java:20-30 → src/main/java/net/netty/ChannelServerInitializer.java:22-39 |
| Shared pipeline build | src/main/java/net/netty/ServerChannelInitializer.java:49-70 |
| Inbound dispatch | src/main/java/client/Client.java:203-231 (`channelRead`) → src/main/java/net/PacketProcessor.java:71-77 |
| Outbound | src/main/java/client/Client.java:1632-1650 (`sendPacket`) |
| Idle / keepalive | src/main/java/net/netty/ServerChannelInitializer.java:62 → src/main/java/client/Client.java:233-238, :1250-1266 |
| Inactivity reaper | src/main/java/net/server/task/TimeoutTask.java:17-28, scheduled src/main/java/net/server/world/World.java:213 |
| Handler registration | src/main/java/net/PacketProcessor.java:110-295 |
| Version gate | src/main/java/net/server/handlers/login/ClientVersionGate.java:86-123, called src/main/java/net/server/handlers/login/LoginPasswordHandler.java:74-82 |

## How it works

### 1. Accept and pipeline

1. `Server.start` reads the login port from `-Dmapleroot.login-port` (default 8485) and calls `initLoginServer` (src/main/java/net/server/Server.java:1018-1019, :1063-1067); `LoginServer.start` builds a `ServerBootstrap` with two `NioEventLoopGroup`s and `childHandler(new LoginServerInitializer())`, then `bind(port)` (src/main/java/net/netty/LoginServer.java:20-27). `Channel` does the same per channel port: `BASE_PORT = Integer.getInteger("mapleroot.channel-base-port", 7585)` and `channelPort(base, slot, channel) = base + (channel-1) + slot*100` (src/main/java/net/server/channel/Channel.java:64-69), started at :172-176 through `ChannelServer.start` (src/main/java/net/netty/ChannelServer.java:20-30). No `ChannelOption` (backlog, `SO_TIMEOUT`, `TCP_NODELAY`) is set anywhere under src/main/java/net/netty.
2. `LoginServerInitializer.initChannel` reads the peer IP, gets the login `PacketProcessor` (`getLoginServerProcessor`, world −1/channel −1), allocates a session id from the shared `AtomicLong sessionId = 7777` (src/main/java/net/netty/ServerChannelInitializer.java:33), resolves the address through `getRemoteAddress`, builds `Client.createLoginClient`, then gates the connection on `SessionCoordinator.canStartLoginSession` and closes the socket on refusal (src/main/java/net/netty/LoginServerInitializer.java:14-29). The channel variant resolves `getChannelServerProcessor(world, channel)` and closes the socket when `Server.getInstance().getChannel(world, channel) == null` (src/main/java/net/netty/ChannelServerInitializer.java:23-38).
3. `getRemoteAddress` prefers `IpAddresses.evaluateRemoteAddress(hostAddress)` so loopback/LAN peers are rewritten to the configured public host (src/main/java/net/netty/ServerChannelInitializer.java:35-47; the same helper is also used in `Client.channelActive`, src/main/java/client/Client.java:189-200).
4. `initPipeline` generates the send and receive IVs, writes the hello, and only then installs handlers (src/main/java/net/netty/ServerChannelInitializer.java:49-54). The hello is deliberately unencrypted: it is written at :52 via `PacketCreator.getHello(VERSION, sendIv, recvIv)` (0x0E, version, 1, byte 49, recv IV, send IV, byte 8 — src/main/java/tools/PacketCreator.java:569-579) before `PacketCodec` exists in the pipeline, so it passes through as raw bytes.
5. `setUpHandlers` appends `IdleStateHandler(0, 0, 30)`, then `PacketCodec`, then the `Client` itself; when the packet-logging switch is on it inserts `SendPacketLogger`/`ReceivePacketLogger` directly before `Client` (src/main/java/net/netty/ServerChannelInitializer.java:60-69).

### 2. Decrypt and route

1. `PacketDecoder` (a `ReplayingDecoder`) reads the 4-byte header, rejects it unless `receiveCypher.isValidHeader(header)` — throwing `InvalidPacketHeaderException` (src/main/java/net/encryption/PacketDecoder.java:20-25), derives the body length from the header (src/main/java/net/encryption/PacketDecoder.java:43-47), reads it, runs `MapleAESOFB.crypt` then `MapleCustomEncryption.decryptData`, and emits a `ByteBufInPacket` (src/main/java/net/encryption/PacketDecoder.java:27-32).
2. `Client.channelRead` casts to `InPacket`, reads the opcode as a little-endian short, looks the handler up in its own `PacketProcessor`, optionally logs the id, and runs `validateState` before `handlePacket` (src/main/java/client/Client.java:203-231). There is no allow-list by opcode beyond the table itself: an opcode with no entry (or a handler refusing `validateState`) is silently dropped, and `updateLastPacket` still runs at :230.
3. `PacketProcessor.getHandler` is a bounds-checked array read: `if (packetId < 0 || packetId >= handlers.length) return null;` — fixed from upstream's `>` bound which let the first id past the end throw (src/main/java/net/PacketProcessor.java:71-77).
4. `PacketHandler` is a two-method interface (`handlePacket(InPacket, Client)`, `validateState(Client)`) at src/main/java/net/PacketHandler.java:27-29; the default `validateState` is `c.isLoggedIn()` (src/main/java/net/AbstractPacketHandler.java:27-31). Exceptions thrown by a handler are caught per-packet and logged with the character/map/packet, so one bad packet does not kill the loop (src/main/java/client/Client.java:222-227).
5. `Client.notePlayerAction` records the time for a whitelisted set of player-driven opcodes (`PLAYER_ACTIONS`, src/main/java/client/Client.java:280-330) — mob/pet movement and passive damage are deliberately excluded.

### 3. Handler registration (the table is in code, not generated)

`PacketProcessor` is one instance per (world, channel) pair, cached in `static final Map<String, PacketProcessor> instances` keyed `world + " " + channel` (src/main/java/net/PacketProcessor.java:39, :87-96). The constructor sizes `handlers` from the largest `RecvOpcode` value: `new PacketHandler[maxRecvOp + 1]` (src/main/java/net/PacketProcessor.java:45-53) — 65535 slots, because `MAPLETV(0xFFFE)` exists (src/main/java/net/opcodes/RecvOpcode.java:195). `reset(channel)` reallocates the array and re-registers everything (:98-108).

| Mechanism | Where | Count |
|---|---|---|
| `handlers[code.getValue()] = handler` (direct array write) | src/main/java/net/PacketProcessor.java:79-85 | 177 active `registerHandler(RecvOpcode…)` calls |
| Common, every server | `registerCommonHandlers` | 2 — `PONG`→`KeepAliveHandler`, `CUSTOM_PACKET`→`CustomPacketHandler` (:110-113) |
| Login server only (`channel < 0`) | `registerLoginHandlers` | 22 (:115-138), incl. `CLIENT_BUILD_STAMP`→`ClientBuildStampHandler` (:121) |
| Channel servers | `registerChannelHandlers` | 153 (:140-295) |
| Commented out | `//registerHandler(RecvOpcode.MTS_OPERATION, …)` (:227) | 1 |
| Handler instances carrying dependencies | `PLAYER_LOGGEDIN`→`PlayerLoggedinHandler(channelDeps.noteService())` (:156), `FREDRICK_ACTION`→`FredrickHandler(channelDeps.fredrickProcessor())` (:282) | 2 |

`registerHandler` also guards the array write with `catch (ArrayIndexOutOfBoundsException)` + `log.error` (:79-85), so a mis-sized opcode would show up at boot as a log line rather than a crash.

**The dispatch table is hand-written in code.** There is no annotation scanning, no `ServiceLoader`, no `Reflections`, no `Class.forName` and no code generator: three private method bodies of `PacketProcessor` are the whole registration surface, and a grep of `src/main/java/net` for those reflective mechanisms returns nothing. Adding an opcode means adding an entry to `RecvOpcode` and one `registerHandler` line (and, for a channel opcode, a handler class); the array index is the enum value, so the two cannot drift.

### 4. Sending a packet

1. Game code builds an `OutPacket` through `OutPacket.create(SendOpcode)`, which constructs a `ByteBufOutPacket` writing the opcode little-endian in the constructor (src/main/java/net/packet/OutPacket.java:20-22, src/main/java/net/packet/ByteBufOutPacket.java:20-24). Payload writers are LE (`writeShort`→`writeShortLE` at :53) and `writeString` writes a short length plus `CharsetConstants.CHARSET` bytes (:73).
2. `Client.sendPacket` is the single funnel: it first filters `NpcOutPacket` through `server.life.CustomNpcGate.filter(packet, clientStampFormat, clientBuild, clientCaps)` and returns early if the client must not see that NPC (src/main/java/client/Client.java:1632-1639); `NpcOutPacket` is a byte-identical subclass whose only job is that tag (src/main/java/net/packet/NpcOutPacket.java:5-16). A `PacketMeter`, if attached, is notified with the packet (:1640-1643) — this is what QA's `pe-1` uses to count sync packets and bytes (src/main/java/soloMapling/qa/MapEntryMeter.java:80-105).
3. The write happens under the fair `announcerLock`: `ioChannel.writeAndFlush(packet)` (:1644-1649). Note this is *not* the `encoderLock`, which is only used by `finishLogin` and `tryacquireEncoder` (:617/:625, :1470/:1478).
4. In the pipeline, `PacketEncoder` (a `MessageToByteEncoder<Packet>`) takes `getBytes()`, writes the encoded 4-byte header from `MapleAESOFB.getPacketHeader(length)`, encrypts in place with `MapleCustomEncryption.encryptData` then `sendCypher.crypt`, and writes the body (src/main/java/net/encryption/PacketEncoder.java:15-27).

### 5. Encryption state

`PacketCodec` is a `CombinedChannelDuplexHandler<PacketDecoder, PacketEncoder>` built from a `ClientCyphers` pair (src/main/java/net/encryption/PacketCodec.java:5-7). `ClientCyphers.of` uses `(short)(0xFFFF - VERSION)` for the send cipher and `VERSION` for the receive cipher (src/main/java/net/encryption/ClientCyphers.java:14-17), `VERSION = 83` (src/main/java/constants/net/ServerConstants.java:8). IVs are `{82,48,120,rand}` send and `{70,114,122,rand}` receive (src/main/java/net/encryption/InitializationVector.java:14-20). `MapleAESOFB` holds the AES/ECB cipher over a hard-coded 32-byte key and XORs in 0x5B0/0x5B4-byte blocks, rotating the IV once per `crypt` call (src/main/java/net/encryption/MapleAESOFB.java:109-139); header validation is a IV-XOR check against the version (`checkPacket`, :161-171). `MapleCustomEncryption` is the six-round byte shuffle applied inside that (:38, :72).

### 6. Keepalive, idle and the inactivity reaper

1. `IdleStateHandler(0, 0, 30)` fires a `READER_IDLE`/`ALL_IDLE` event after 30 s of no reads (src/main/java/net/netty/ServerChannelInitializer.java:28, :62). `Client.userEventTriggered` forwards it to `checkIfIdle` (src/main/java/client/Client.java:233-238).
2. `checkIfIdle` sends `SEND.PING` (`PacketCreator.getPing()`, src/main/java/tools/PacketCreator.java:586-588), stamps `pingedAt`, and schedules a check 15 s later: if `lastPong < pingedAt` and the channel is still active it logs `"Disconnected {} due to idling"`, resets the login state, and calls `disconnectSession` (src/main/java/client/Client.java:1250-1266).
3. A client that answers sends `PONG`, handled by `KeepAliveHandler` → `c.pongReceived()` which sets `lastPong` (src/main/java/net/server/handlers/KeepAliveHandler.java:28-32, src/main/java/client/Client.java:1246-1248). `PONG` has no login-state requirement (`validateState` returns `true`), so it works pre-login.
4. Independently, `TimeoutTask` sweeps every character in the world every 10 s (registered at src/main/java/net/server/world/World.java:213) and disconnects any whose `client.getLastPacket()` is older than `TIMEOUT_DURATION` (src/main/java/net/server/task/TimeoutTask.java:17-27). Bots are skipped at :22; the bot and synthetic clients also neutralize the netty-level path (`BotClient.checkIfIdle` is a no-op at src/main/java/client/BotClient.java:131-134, `SyntheticClient.checkIfIdle` at src/main/java/client/SyntheticClient.java:356-358, and both overrides `getLastPacket` to "now").
5. Transport failure paths: `channelInactive` → `closeMapleSession` (src/main/java/client/Client.java:253-274), `exceptionCaught` treats `InvalidPacketHeaderException` as "kill the session" and `IOException` as an ordinary close (:240-251).

### 7. Client build gating

1. The client sends `RecvOpcode.CLIENT_BUILD_STAMP` (0x3738, src/main/java/net/opcodes/RecvOpcode.java:31) just before the login packet on the same connection. `ClientBuildStampHandler` parses format 1 (build + release) or format 2 (plus a caps int), sanitizes the release string, and stores them on the `Client`; a missing stamp means build 0, a malformed one is ignored (src/main/java/net/server/handlers/login/ClientBuildStampHandler.java:15-68). It is the only login handler whose `validateState` is `!isLoggedIn` (:20).
2. `LoginPasswordHandler` runs the gate as soon as the password verifies, before the loginok-7 kick and before `AUTOMATIC_REGISTER`, and again inside the register branch with GM level pinned to 0 (src/main/java/net/server/handlers/login/LoginPasswordHandler.java:71-82).
3. `ClientVersionGate.refuseIfOutdated` refuses QA-caps builds unless `-Dslums.client.allowQa` is set, then refuses builds below `MIN_CLIENT_BUILD` unless the account's highest character GM level qualifies under `MIN_CLIENT_BUILD_GM_BYPASS`, sending a notice/failed-login pair and logging the refusal (src/main/java/net/server/handlers/login/ClientVersionGate.java:86-123, GM level query :126-137).
4. Channel sessions are new `Client` objects, so the stamp is carried across the handover in `ClientStampCarry` (remembered at src/main/java/client/Client.java:1184, restored at src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:147; the class is src/main/java/net/server/coordinator/session/ClientStampCarry.java:14-47). Without it a channel session reads as build 0 and the custom-NPC filter would strip every custom NPC.

### 8. Autoban on packet-level misbehaviour

Handlers that detect a crafted or impossible packet call `AutobanFactory.<TYPE>.alert(...)` or `.autoban(...)` (e.g. src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:83, :129, :162, :243, :419, :852; src/main/java/client/processor/npc/DueyProcessor.java:384-408). `alert` only acts when `USE_AUTOBAN` is on and logs a line when `USE_AUTOBAN_LOG` is on (src/main/java/client/autoban/AutobanFactory.java:95-106); `autoban` calls `chr.autoban(...)` under the same switch (:108-112). `AutobanManager.addPoint` accumulates per-type points, halves them after expiry, and bans when the type's maximum is reached (src/main/java/client/autoban/AutobanManager.java:38-67); repeated identical timestamps disconnect the client (:131-145).

## Data

| Type | Role | Where |
|---|---|---|
| `Packet` | `byte[] getBytes()` — the common ancestor | src/main/java/net/packet/Packet.java:3-5 |
| `InPacket` | reads: `readByte/readShort/readInt/readLong/readPos/readString/readBytes/skip/available/seek/getPosition/copy` | src/main/java/net/packet/InPacket.java:5-18 |
| `ByteBufInPacket` | netty `ByteBuf` impl; all multi-byte reads are LE (`readShortLE` etc.), `available()` is `readableBytes()`, `toString` is a hex dump with a `_` at the reader index | src/main/java/net/packet/ByteBufInPacket.java:22-100 |
| `OutPacket` | writes plus `static create(SendOpcode)` | src/main/java/net/packet/OutPacket.java:7-22 |
| `ByteBufOutPacket` | `@NotThreadSafe`; opcode written LE in ctor; `getBytes` via `ByteBufUtil` | src/main/java/net/packet/ByteBufOutPacket.java:12-35 |
| `NpcOutPacket` | `ByteBufOutPacket` tagged with an NPC template id + offset, used only by `CustomNpcGate` | src/main/java/net/packet/NpcOutPacket.java:5-20 |
| `RecvOpcode` | 186 constants, custom addon block first (0x3713-0x373B), then base v83 from `LOGIN_PASSWORD(0x01)` | src/main/java/net/opcodes/RecvOpcode.java:24-34 |
| `SendOpcode` | 319 constants, custom sync block first (0x3727-0x373F), then base from `LOGIN_STATUS(0x00)` | src/main/java/net/opcodes/SendOpcode.java:24-36 |
| `OpcodeConstants` | builds `Map<Integer,String>` name tables from both enums at boot, used by the packet loggers | src/main/java/constants/net/OpcodeConstants.java:31-43, called src/main/java/net/server/Server.java:1027 |
| `Client` | one per socket; owns `ioChannel`, `packetProcessor`, `lastPacket`, `lastPong`, `clientBuild/clientRelease/clientStampFormat/clientCaps`, `packetMeter` | src/main/java/client/Client.java:81-135, :1617-1630 |

Numeric constants that matter on the wire: `VERSION = 83` and `SLUMSMS_VERSION = "1.6.4"` (src/main/java/constants/net/ServerConstants.java:8-10); hello opcode 0x0E (src/main/java/tools/PacketCreator.java:571); idle time 30 s (src/main/java/net/netty/ServerChannelInitializer.java:28); ping grace 15 s (src/main/java/client/Client.java:1265); packet-log thresholds 3,000 B inbound and 50,000 B outbound (src/main/java/net/packet/logging/InPacketLogger.java:16, src/main/java/net/packet/logging/OutPacketLogger.java:17).

## Switches

| Switch | Kind | Default | Effect | Live |
|---|---|---|---|---|
| `mapleroot.login-port` | `-D` | 8485 | login listener port (src/main/java/net/server/Server.java:1018) | not set in docs/release/1.6.4.conf → 8485 |
| `mapleroot.channel-base-port` | `-D` | 7585 | channel ports `base + slot*100 + channel-1` (src/main/java/net/server/channel/Channel.java:64-69) | not set → 7585 |
| `slums.client.allowQa` | `-D` | off | accepts QA-caps builds in the version gate (src/main/java/net/server/handlers/login/ClientVersionGate.java:39, :100-108) | not in 1.6.4.conf's `UNIT_EDITS` (:271) → off |
| `USE_DEBUG_SHOW_PACKET` | config.yaml | `false` (config.example.yaml:204) | installs `SendPacketLogger`/`ReceivePacketLogger` in the pipeline (src/main/java/net/netty/ServerChannelInitializer.java:29, :66-69) | not in 1.6.4.conf → falls back to config.yaml (unread here) |
| `USE_DEBUG_SHOW_RCVD_PACKET` | config.yaml | `false` (config.example.yaml:202) | one `log.debug("Received packet id …")` per packet, minus the ignored set (src/main/java/client/Client.java:213-215); toggled at runtime by `@showpackets` (src/main/java/client/command/commands/gm5/ShowPacketsCommand.java:37) | same fallback |
| `TIMEOUT_DURATION` | config.yaml (long, ms) | 3600000 (config.example.yaml:500) | inactivity reaper threshold (src/main/java/net/server/task/TimeoutTask.java:23) | same fallback |
| `USE_AUTOBAN` | config.yaml | `false` (config.example.yaml:230) | enables points/bans from `AutobanFactory`/`AutobanManager` (src/main/java/client/autoban/AutobanFactory.java:96, :109; src/main/java/client/autoban/AutobanManager.java:39) | same fallback |
| `USE_AUTOBAN_LOG` | config.yaml | `true` (config.example.yaml:231) | logs `Autoban alert`/`Autoban` lines even when bans are off (:102-105, src/main/java/client/autoban/AutobanManager.java:63-66) | same fallback |
| `MIN_CLIENT_BUILD` | config.yaml | 0 = gate off (src/main/java/config/ServerConfig.java:61, config.example.yaml:82) | minimum client build accepted (src/main/java/net/server/handlers/login/ClientVersionGate.java:110) | **10740** (docs/release/1.6.4.conf:284) |
| `MIN_CLIENT_BUILD_GM_BYPASS` | config.yaml | `false` (config.example.yaml:84) | lets GM accounts through the build gate (src/main/java/net/server/handlers/login/ClientVersionGate.java:113) | same fallback |
| `CLIENT_UPDATE_MESSAGE` | config.yaml | "Your game is out of date: open the SlumsMS launcher to update." (src/main/java/config/ServerConfig.java:62) | refusal text | same fallback |
| `CUSTOM_NPC_IDS`, `CUSTOM_NPC_MIN_BUILD` | config.yaml | `"9990000,2985-2999"`, 10610 (config.example.yaml:85-86) | which NPC templates the gate withholds from old clients (src/main/java/client/Client.java:1635) | same fallback |
| `LOGIN_MAX_SESSIONS_PER_IP` / `LOGIN_MAX_CONNECTS_PER_IP` / `LOGIN_CONNECT_WINDOW_SECONDS` | config.yaml | 8 / 30 / 60 (config.example.yaml:95-96, src/main/java/config/ServerConfig.java:83-86) | login accept gate (src/main/java/net/server/coordinator/session/SessionCoordinator.java:127-141) | same fallback |

`SERVER_SET`/`UNIT_EDITS` in docs/release/1.6.4.conf (:201, :271) name no debug-packet, autoban or timeout values; those keys therefore come from live's `config.yaml`, whose contents are not readable here. TEST's `start-test.sh` lives on another host and is not in this repo — TEST's values are unknown.

## Invariants

1. **A packet's handler index is its opcode value.** `handlers[code.getValue()]` (src/main/java/net/PacketProcessor.java:81) and the array is sized from the maximum enum value (:45-53); no id outside `[0, length)` is dispatched (:72).
2. **Encryption state advances exactly once per packet, in one direction.** `crypt` mutates the IV and is `synchronized` (src/main/java/net/encryption/MapleAESOFB.java:109, :133); the decoder and encoder hold different `MapleAESOFB` instances (src/main/java/net/encryption/ClientCyphers.java:14-17).
3. **The hello is the only unencrypted write on a connection** (src/main/java/net/netty/ServerChannelInitializer.java:52 vs :63, pipeline has no codec yet).
4. **`validateState` runs before `handlePacket` for every inbound packet** (src/main/java/client/Client.java:217), so a handler may assume its own precondition holds.
5. **One writer per socket.** All outbound bytes go through `Client.sendPacket` and its `announcerLock` (:1644-1649); `BotClient` overrides `sendPacket` to do nothing because it has no channel (src/main/java/client/BotClient.java:25-28).
6. **Custom NPC packets are never sent to a client that cannot render them** — `NpcOutPacket` is filtered and may be dropped or substituted before the write (src/main/java/client/Client.java:1633-1639).
7. **A channel session always has a build stamp** — carried through `ClientStampCarry` at the handover (src/main/java/net/server/channel/handlers/PlayerLoggedinHandler.java:147).
8. **Bots are outside the network layer.** `BotClient` passes `null` for the processor and `"bot"` as the address (src/main/java/client/BotClient.java:16); the reaper skips them (src/main/java/net/server/task/TimeoutTask.java:22).

## Origin

| Piece | Classification | Evidence |
|---|---|---|
| `net/netty/*` (Server/Login/Channel server, initializers, `AbstractServer`, `InvalidPacketHeaderException`) | base, one file modified | `diff -q` vs /root/workspace/cosmicms identical for `ChannelServerInitializer.java`, `LoginServer.java`, `ChannelServer.java`, `AbstractServer.java`, `InvalidPacketHeaderException.java`; `ServerChannelInitializer.java` and `LoginServerInitializer.java` differ (added `IpAddresses` rewrite at :40, added `getMostRecentAccountFromIP` at :22) |
| `net/encryption/*` (7 files) | base, unmodified | all `diff -q` identical |
| `net/packet/*` interfaces and `ByteBufInPacket`/`ByteBufOutPacket` | base, modified | `InPacket.java` adds `copy()`; `ByteBufInPacket.java` drops `readUnsignedByte`/`equals` and adds `copy()` under `// Additive SoloMapling APIs` (:102-106); `ByteBufOutPacket.java` drops `equals` |
| `net/packet/NpcOutPacket.java` | custom | absent upstream |
| `net/PacketProcessor.java` | base, modified | bound fix (ours `packetId < 0 || packetId >= handlers.length`, base `> handlers.length`); base additionally registers `USE_INNER_PORTAL` and active `MTS_OPERATION`; ours adds `CLIENT_BUILD_STAMP` + 6 addon opcodes + `USE_HYPERROCK` |
| `net/opcodes/RecvOpcode.java`, `SendOpcode.java` | base, modified | addon blocks at :25-32 / :25-35 have no upstream counterpart |
| `net/server/handlers/login/ClientBuildStampHandler.java`, `ClientVersionGate.java` | custom | absent upstream; first commit `82d4831b4 2026-10-21 "Refuse logins from outdated clients (MIN_CLIENT_BUILD)"`, stamp format 2 added `4af751a64 2026-10-28` |
| `net/server/handlers/{KeepAliveHandler,CustomPacketHandler,LoginRequiringNoOpHandler}.java` | base, unmodified | `diff -q` identical |
| `net/packet/logging/*` (5 files) | base, unmodified | `diff -q` identical |
| `net/server/task/TimeoutTask.java` | base, modified | bot skip added at :22 |
| `client/autoban/AutobanFactory.java`, `AutobanManager.java` | base, modified | logging restored (classifications.json `java-commented:…AutobanFactory.java::L102` → `RESTORED`, owner 2026-10-21) and the real-clock chat stamp added (`064441949 2026-10-24`) |
| `net/PacketProcessor.java` MTS entry | deliberate | tools/cosmicdiff/classifications.json `java-commented-lines:src/main/java/net/PacketProcessor.java` DELIBERATE — "MTS_OPERATION unregistered: the MTS button is the SlumsMS/MapleRoot Free Market button (EnterMTSHandler)" |

All oldest-commit probes land on `03d13cb21 2026-10-18 "Establish deployable MapleRoot server baseline"` (the repack baseline) for `PacketProcessor.java`, `ServerChannelInitializer.java`, `PacketDecoder.java`, `ByteBufInPacket.java`, `TimeoutTask.java`, `InPacketLogger.java`; later commits are ours.

## Tests and QA

| Test | Covers |
|---|---|
| src/test/java/net/PacketProcessorBoundsTest.java:11-27 | the fixed bound: `getHandler(length)`, `(short)-1`, `Short.MIN_VALUE` return null instead of throwing (javadoc records the upstream bug) |
| src/test/java/net/InnerPortalRegistrationTest.java:12-29 | `USE_DOOR` resolves to a handler while `USE_INNER_PORTAL` (0x65) must resolve to none (ticket 164) |
| src/test/java/net/packet/ByteBufInPacketTest.java:32-183 | every read method, `skip/available/seek/getPosition`, `getBytes` repeatability, `toString` position marker |
| src/test/java/net/packet/ByteBufOutPacketTest.java | opcode written by the ctor, `getBytes`, truncation of `writeByte/Int/Short` |
| src/test/java/net/server/handlers/login/ClientVersionGateTest.java:35-111 | gate off by default, below-minimum and no-stamp refused, GM bypass, blank message, stamp parse incl. golden bytes and format-2 caps |
| src/test/java/net/server/handlers/login/QaBuildGateTest.java:43-66 | caps bit 31, `-Dslums.client.allowQa`, GM interplay |
| src/test/java/net/server/handlers/login/VersionGateOrderTest.java:105-157 | refusal happens before the live session is kicked, auto-register does not create accounts for old clients, GM level read only when needed |
| src/test/java/client/SyntheticClientTest.java, src/test/java/client/command/AutoLoginRemovedTest.java:31, src/test/java/soloMapling/autoqa/SyntheticPlayerActivityTest.java:16-29, src/test/java/soloMapling/ArtificialPlayer/PlayerBusyTest.java:137-143 | synthetic client behaviour and the "activity resets the AFK clock" contract |

Auto-QA: `t-vg-1 "An old client is told to update"` (src/main/java/soloMapling/autoqa/AutoQaCatalog.java:777-779) runs the `clientGate` probe (src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:193, :478-495), which calls `ClientVersionGate.verdictFor` at min−1/min/min+1 and reports FAIL when `MIN_CLIENT_BUILD <= 0`.

`!qa` scenarios: `pe-1` measures a GM's map-entry packet cost using `Client.PacketMeter` (src/main/java/soloMapling/qa/QaScenarios.java:859-920, src/main/java/soloMapling/qa/MapEntryMeter.java:80-105); `SyntheticPlayers.handle` replays a packet body through the real table for headless players (src/main/java/soloMapling/autoqa/SyntheticPlayers.java:249-260).

**Not covered anywhere:** the netty accept path and pipeline order, the hello handshake, a full AES/IV/custom-cipher round trip, `IdleStateHandler`/ping-pong disconnect, `TimeoutTask`, the packet-logger switches, an end-to-end send through `PacketEncoder`, and the version gate over a real socket (all gate tests call the static helpers). `PacketProcessorBoundsTest` is the only test that touches the table, and it asserts the bounds behaviour rather than which handler each opcode resolves to.

## Known issues

| Id | Issue |
|---|---|
| `b298` (S2, /root/work/STATUS-1.6.4.md:48) | Crafted packets skip the leader check for guild / alliance / party actions — handlers trust packet-supplied leader ids. Security, closer Server Dev / external worker; tracked in docs/tickets/182-server-code-audit-1-6-4.md:59. |
| NEW-FINDING: src/main/java/net/packet/logging/OutPacketLogger.java:25 | `ctx.write(msg)` drops the caller's `ChannelPromise`, so `Client.sendPacket`'s `writeAndFlush` (src/main/java/client/Client.java:1646) never completes and outbound failures are invisible while logging is on. The log line is also emitted before the write (:20-25), so a failed write still reads as a sent packet. |
| NEW-FINDING: src/main/java/client/autoban/AutobanFactory.java:96, :109 | With `USE_AUTOBAN` false (the shipped default, config.example.yaml:230) every `alert`/`autoban` call is a no-op and only `USE_AUTOBAN_LOG` lines remain, so packet-edit detection produces log noise and nothing else. tools/cosmicdiff/classifications.json:39-42 already flags the restored log line as a log-volume risk. |
| NEW-FINDING: src/main/java/net/PacketProcessor.java:227 | `MTS_OPERATION` is unregistered while `RecvOpcode.MTS_OPERATION` is still declared and is listed in `Client.PLAYER_ACTIONS` (src/main/java/client/Client.java:330); a client still sending it is silently dropped. Deliberate per tools/cosmicdiff/classifications.json:113-120, but the enum entry and the action-set membership are now dead references. |
| NEW-FINDING: src/main/java/net/encryption/MapleAESOFB.java:155 | `getPacketLength` has no production caller — `PacketDecoder` uses its own `decodePacketLength` (src/main/java/net/encryption/PacketDecoder.java:43-47), so the header-length rule exists twice and only one copy is tested. |
| NEW-FINDING: src/main/java/client/Client.java:1301 | `copyClientStampFrom` has no caller; it was superseded by `ClientStampCarry` (src/main/java/net/server/coordinator/session/ClientStampCarry.java:37). Dead twin of the live carry path. |
| NEW-FINDING: docs/release/1.6.4.conf:10 vs :284 | The header still says "No new client: MIN_CLIENT_BUILD stays 10721" while :284 assigns 10740 and :282-283 records the owner decision behind it. The file contradicts itself; treat :284 as current. |
| NEW-FINDING: src/main/java/net/netty/ServerChannelInitializer.java:33 | `sessionId` is a single `static AtomicLong` shared by the login server and every channel, so channel session ids are not comparable across restarts and are only monotonic inside one JVM. Not wrong today, but it makes "session id" a process-local counter rather than a connection identity. |

## State

**working** — for the core path. Evidence: the accept → hello → codec → `Client.channelRead` → `PacketProcessor.getHandler` → handler chain is complete and consistent in the source (src/main/java/net/netty/ServerChannelInitializer.java:49-70, src/main/java/client/Client.java:203-231, src/main/java/net/PacketProcessor.java:71-85); the dispatch table covers 177 opcodes with an explicit bounds fix and a regression test (src/test/java/net/PacketProcessorBoundsTest.java:14-27); the outbound path is a single funnel with tests for both buffer implementations (src/test/java/net/packet/ByteBufInPacketTest.java, ByteBufOutPacketTest.java). **Partial** for observability (`OutPacketLogger` promise loss above) and for autoban (inert under the shipped default). **Unknown** for anything requiring a live socket here: no test or harness in this repo exercises the pipeline, so "working" is inferred from code, not from an observed connection.

## Open questions

1. Does the live `config.yaml` set `USE_DEBUG_SHOW_PACKET`, `USE_DEBUG_SHOW_RCVD_PACKET`, `TIMEOUT_DURATION` or `USE_AUTOBAN`? docs/release/1.6.4.conf names none of them, and reading `config.yaml` is out of bounds for this review.
2. What does TEST run? `start-test.sh` lives on another host; `MIN_CLIENT_BUILD` on TEST is only known second-hand from docs/tickets/166-auto-qa-client.md and the comment at src/main/java/soloMapling/autoqa/GameAutoQaWorld.java:476.
3. Does the real client retry `CLIENT_BUILD_STAMP` on a channel connection? `ClientStampCarry` assumes it does not (src/main/java/net/server/coordinator/session/ClientStampCarry.java:36-41 “a session that already has one keeps it”), but nothing in this repo shows the client side.
4. Is the 30 s idle + 15 s ping grace short enough to drop players on a laggy connection before TCP notices? No tuning note was found; the reaper's 1 h default (`TIMEOUT_DURATION`) is an independent and much looser second gate.
5. Should `MTS_OPERATION` be removed from `RecvOpcode` and `Client.PLAYER_ACTIONS`, or does a client still send it? The classifications entry calls the unregistration deliberate; the enum entry and action membership look left behind.
