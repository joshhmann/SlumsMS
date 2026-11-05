# Commands and GM levels

## Purpose

Every line a player types in the chat box that starts with `@` (and, for GMs, `!`) is a command: a Java class keyed by
name in one registry, gated by the character's GM level, run on the packet thread. The same registry also serves the
staff quick-chat packets (`/h`, `/u`, `/alertall`), the in-game `@help` window, the QA menus and the bot messenger
console. This file maps that path: chat line → handler → lookup → rank check → `Command.execute`, plus where the GM
level comes from, how a new command registers, and what is audited.

## Entry points

| Entry | Location | What reaches it |
|---|---|---|
| `RecvOpcode.GENERAL_CHAT` | src/main/java/net/PacketProcessor.java:146 → `new GeneralChatHandler()` | every typed chat line, including `@`/`!` commands |
| `RecvOpcode.ADMIN_COMMAND` | src/main/java/net/PacketProcessor.java:255 → `new AdminCommandHandler()` | the client's GM slash commands (`/h`, `/u`, `/send`, `/kill`, …), packet-driven, not chat text |
| `RecvOpcode.ADMIN_CHAT` | src/main/java/net/PacketProcessor.java:289 → `new AdminChatHandler()` | `/alertall`, `/alertch`, `/alertmap` server notices |
| `CommandsExecutor.handle` | src/main/java/client/command/CommandsExecutor.java:115 | the four callers below |
| ↳ chat | src/main/java/net/server/channel/handlers/GeneralChatHandler.java:45-46 | `if (CommandsExecutor.isCommand(c, s)) CommandsExecutor.getInstance().handle(c, s);` |
| ↳ QA menu | src/main/java/soloMapling/menus/QaMenu.java:67 | NPC-window clicks replayed as typed commands (javadoc :24: "so the GM audit, refusals and QA logs are the command's") |
| ↳ settings menu | src/main/java/soloMapling/menus/SettingsMenu.java:36 | same pattern for `@settings` (`@romance`, `@quiet`, …) |
| ↳ bot messenger | src/main/java/soloMapling/server/MapleMessengerConsole.java:358 (`chatCommandViaMMC`), console verb `cmd` at :136-144 | a GM drives another character's client through the bot console |
| boot pre-warm | src/main/java/net/server/Server.java:1028 (`CommandsExecutor.getInstance();`) | builds the registry (and its log warnings) before any client connects |

Dispatch and its exception boundary: `Client.channelRead` reads the opcode, resolves the handler, then calls
`handler.handlePacket(packet, this)` inside `try { … } catch (final Throwable t)` at src/main/java/client/Client.java:209-231
(catch at :222, logged at :224-225). A command that throws therefore disconnects nothing, but also sends no reply.

## How it works

1. **Chat line arrives.** `GeneralChatHandler.handlePacket` reads the string (src/main/java/net/server/channel/handlers/GeneralChatHandler.java:31-32).
2. **Spam gate.** `if (spamBlocked(chr.isGM(), chr.getAutobanManager().getLastChatAt(), System.currentTimeMillis()))` → re-enable actions and return
   (src/main/java/net/server/channel/handlers/GeneralChatHandler.java:34-37). `spamBlocked` is `!gm && lastSpam + CHAT_SPAM_MS > now` with
   `CHAT_SPAM_MS = 200` (:19, :26-28). GMs are never throttled, and the stamp is the real clock (`chatted()`,
   src/main/java/client/autoban/AutobanManager.java:100-107) rather than the 777 ms server tick the javadoc at
   src/main/java/net/server/channel/handlers/GeneralChatHandler.java:21-25 says the old code used.
3. **Length gate.** `s.length() > Byte.MAX_VALUE && !chr.isGM()` → `AutobanFactory.PACKET_EDIT.alert(...)`, `log.warn`, `c.disconnect(true, false)`
   (src/main/java/net/server/channel/handlers/GeneralChatHandler.java:38-43). A non-GM command line longer than 127 chars kills the session.
4. **Heading test.** `CommandsExecutor.isCommand(client, content)` (src/main/java/client/command/CommandsExecutor.java:58-64):
   GMs (`isGM()` = `gmLevel > 1`, src/main/java/client/Character.java:7578-7580) may use `@` or `!`; everyone else only `@`
   (`USER_HEADING`/`GM_HEADING` at :47-48). A line whose first char is none of those — or `/`, the client's own slash
   command — falls through to ordinary chat at src/main/java/net/server/channel/handlers/GeneralChatHandler.java:47-92.
5. **Executor lock.** `handle` takes the client's action semaphore, so one command runs at a time per client
   (src/main/java/client/command/CommandsExecutor.java:115-125; `tryacquireClient`/`releaseClient` at
   src/main/java/client/Client.java:1454-1466). A concurrent attempt replies
   "Try again in a while... Latest commands are currently being processed." (:124).
6. **Jail gate.** `client.getPlayer().getMapId() == MapId.JAIL` → "You do not have permission to use commands while in jail."
   (src/main/java/client/command/CommandsExecutor.java:128-131; `MapId.JAIL = 300000012`, src/main/java/constants/id/MapId.java:7). This blocks all commands, `@help` included.
7. **Parse.** Strip the heading and split once on a single space: `splitedMessage = message.substring(1).split("[ ]", 2)`
   (src/main/java/client/command/CommandsExecutor.java:132-136). The raw remainder is stored on the character with
   `setLastCommandMessage(splitedMessage[1])` (:138, comment: "thanks Tochi & Nulliphite for noticing string messages being
   marshalled lowercase"); name and params are lowercased separately (:139-140).
8. **Lookup.** `registeredCommands.get(commandName)`; miss → yellow message
   `"Command '<name>' is not available. See @commands for a list of available commands."` (:142-146).
9. **Rank check.** `client.getPlayer().gmLevel() < command.getRank()` → audit `"denied"`, message
   "You do not have permission to use this command." (:147-151).
10. **Params.** Empty remainder → `new String[]{}`, else the lowercased split array (:153-157).
11. **Execute.** `command.execute(client, params)` in `try`; `RuntimeException` → audit `"error"` and rethrow (:161-164);
    success → audit `"ok"` + `log.info("Chr {} used command {}", …)` (:165-166). Exceptions surface at
    src/main/java/client/Client.java:222.
12. **Audit.** `audit()` (:173-184) skips when `rank <= 0 && player.gmLevel() <= 0`, then logs
    `"GM audit: {} (account {}, gm {}) {} !{} {} on map {} ch {}"` and queues a `GmAudit.Entry` (:182-184).
13. **Reply.** Commands answer themselves (`player.yellowMessage`, `dropMessage`, or an NPC window such as `@help` →
    `openNpc(NpcId.STEWARD, "commands")`, src/main/java/client/command/commands/gm0/HelpCommand.java:37,
    `NpcId.STEWARD = 9201143` at src/main/java/constants/id/NpcId.java:7).

`@help` itself: `scripts/npc/commands.js` pulls `CommandsExecutor.getInstance().getGmCommands()` (:17-19), lists one tier per
GM level (`levels = ["Common","Donator","JrGM","GM","SuperGM","Developer","Admin"]`, :14), shows only tiers up to the
player's level (`for (var i = 0; i <= cm.getPlayer().gmLevel(); i++)`, :44), and picks the `@` heading for Common/Donator and
`!` for the rest (:50). `getGmCommands()` (src/main/java/client/command/CommandsExecutor.java:99-113) skips any command whose
`listed()` is false — the only override is `@nx` (src/main/java/client/command/commands/gm0/NxCommand.java:19-22:
`return ExchangeService.exchange() != null;`), so live with the desk halted/off leaves `@nx` out of the list while the
command still answers (src/main/java/client/command/Command.java:39-42 carries the hook and its javadoc).

### GM levels: directory → rank → example → safety

The directory name and the registered rank are *conventions*, not the same thing: the rank passed to `addCommand` is what
the gate at src/main/java/client/command/CommandsExecutor.java:147 enforces. Ranks 0 and 1 are not GM at all
(`isGM()` needs `gmLevel > 1`).

| Level | Directory | Registered rank | Registered names | Sample commands | Safety |
|---|---|---|---|---|---|
| 0 | `commands/gm0` | 0 (`addCommand(String,Class)`, src/main/java/client/command/CommandsExecutor.java:201-205) | 74 | `@help`/`@commands` (:235), `@nx` (:239), `@bonds` (:296), `@goto` (:301), `@bug`/`@reportbug` (:273) | player-facing; audited only when the character is also a GM (`rank <= 0 && gmLevel <= 0` skip, :175-178) |
| 1 | `commands/gm1` | 1 | 2 | `!buffme` (:324), `!rate` (:325) | still not `isGM()` — a level-1 account is a player for every other guard |
| 2 | `commands/gm2` | 2 | 58 | `!warp` (:349), `!item` (:354), `!level` (:356), `!jail` (:363), `!gachalist` (:368), `!weather` (:506, gated) | first rank that counts as GM; every use is audited |
| 3 | `commands/gm3` | 3 | 103 | `!ban` (:497), `!notice` (:487), `!spawn` (:446), `!killall` (:486), `!qa` (:378, gated), `!mechanics`/`!mech` (:400, live on) | audited; `!ban`/`!resetpw`/`!invite` are the account-touching set |
| 4 | `commands/gm4` | 4 | 26 | `!econstats` (:527), `!servermessage` (:528), all rate setters (:531-537), `!itemvac` (:538), boss spawns (:540-547) | audited; also the rank where anything the bot block registers lands (all `rank 4`, :418-443) |
| 5 | `commands/gm5` | 5 | 6 | `!debug` (:560), `!showpackets` (:562), `!iplist` (:565), `!showsessions` (:564) | audited; exposes player IPs and packet streams |
| 6 | `commands/gm6` | 6 | 19 | `!setgmlevel` (:573), `!shutdown` (:579), `!saveall` (:575), `!warpworld` (:574), `!addchannel`/`!addworld` (:585-586) | audited; the only rank that can change GM levels or the world shape |

Totals: 271 `addCommand` statements registering 288 names, 65/74 at Lv0, 2/2 at Lv1, 53/58 at Lv2, 101/103 at Lv3,
26/26 at Lv4, 6/6 at Lv5, 18/19 at Lv6 (counted over non-comment lines of
src/main/java/client/command/CommandsExecutor.java:232-592). Not all are live: the conditional blocks below remove names
when their flag is absent.

### Registering a new command

There is **no annotation, no scanning and no file-name convention**. Registration is explicit and hand-written:

1. Write a class extending `client.command.Command` in the directory for its level (`src/main/java/client/command/commands/gmN/`),
   override `public void execute(Client client, String[] params)` and set a description in an initializer block
   (`{ setDescription("..."); }`) — `Command` at src/main/java/client/command/Command.java:28-45 declares `protected int rank`,
   `protected String description`, the abstract `execute`, `getDescription`, `listed()` (:39-42), `setRank`/`getRank` (:48-54)
   and `joinStringFrom` (:56-66).
2. Add one call in the matching `registerLvNCommands()` method: `registerLv0Commands` src/main/java/client/command/CommandsExecutor.java:232,
   `registerLv1Commands` :318, `registerLv2Commands` :331, `registerLv3Commands` :408, `registerLv4Commands` :524,
   `registerLv5Commands` :557, `registerLv6Commands` :570. The private constructor runs all seven in order (:66-75).
3. Pick an overload: `addCommand(String, Class)` → rank 0 (:201), `addCommand(String[], Class)` → rank 0 for every alias (:195),
   `addCommand(String, int, Class)` (:213), `addCommand(String[] aliases, int rank, Class)` (:207).
4. `addCommand(String,int,Class)` (:213-231) does the work: duplicate name → `log.warn("Error on register command with name: {}. Already exists.", syntax)`
   and return (:214-217); lowercase the key (:219); `addCommandInfo` (:186-192) instantiates a **throwaway** instance only to read
   `getDescription()` and pushes name + description into the current `levelCommandsCursor` pair; then
   `commandClass.getDeclaredConstructor().newInstance()` **once** (:223, comment "thanks Halcyon for noticing commands getting
   reinstanced every call"), `setRank(rank)` (:224), `registeredCommands.put` (:226). Reflection failure →
   `log.warn("Failed to create command instance", e)` (:227-228) and the command is silently missing.
5. Every class needs a public no-arg constructor (reflection at :223 and :188). A `Command` with only a parameterised
   constructor cannot register.
6. Conditional registration goes inline, wrapped in its feature's `isEnabled()` test (see Switches) — there is no
   post-registration filter except `listed()`.

## Data

| Store | What | Where |
|---|---|---|
| `registeredCommands` | `HashMap<String, Command>` name → singleton instance, rank baked in by `setRank` | src/main/java/client/command/CommandsExecutor.java:50 |
| `commandsNameDesc` | `List<Pair<List<String>,List<String>>>` — one pair per GM level for `@help`; index = level 0..6 | :51, appended at the end of each `registerLvN` method (:314, :327, :405, :521, :554, :567, :591) |
| `levelCommandsCursor` | the pair being filled by the current tier method | :52 |
| `Character.lastCommandMessage` | raw remainder of the line, case preserved | set at src/main/java/client/command/CommandsExecutor.java:138, stored in src/main/java/client/Character.java:12881-12885 |
| `characters.gm` | the persisted GM level, 0..6 | read at src/main/java/client/Character.java:8262 and :8382, written in the characters UPDATE at :9907 |
| `gm_command_audit` | every above-player-rank command and every GM command: UTC time, account id/name, character id/name, gm level, command, args (512), command rank, map, channel, ip, outcome (`ok`/`denied`/`error`) | database/migrations/009-gm-audit.sql:15-33; written from src/main/java/client/command/GmAudit.java:42-44 (`record Entry`) |
| `gm_level_changes` | GM-level changes recorded next to the audit table | database/migrations/009-gm-audit.sql:35- |
| GM code paths that read `getLastCommandMessage()` (case-sensitive arguments) | `!gm` src/main/java/client/command/commands/gm0/GmCommand.java:56, `@bug` src/main/java/client/command/commands/gm0/ReportBugCommand.java:97, `!music` src/main/java/client/command/commands/gm3/MusicCommand.java:61, `!notice` src/main/java/client/command/commands/gm3/NoticeCommand.java:40, `!servermessage` src/main/java/client/command/commands/gm4/ServerMessageCommand.java:38 | — |
| GM level in the session | `Client.gmlevel` (separate field), set from the account's highest character | src/main/java/client/Client.java:1306-1313 (`getGMLevel`/`setGMLevel`), filled at src/main/java/net/server/Server.java:1870-1889 |

`GmAudit` (custom, 191 lines) is an off-thread writer: bounded queue (default 10 000, `-Dslumsms.gmAudit.queue`),
daemon thread `"gm-audit-writer"`, batches of 200 (`BATCH`, src/main/java/client/command/GmAudit.java:37), arg clamp
`MAX_ARGS = 512` (:35), counters `dropped`/`written`/`pending`, and `useSinkForTest` (:179) so the test needs no DB.

## Switches

`-D` properties (no `config.yaml` key gates a command as a whole):

| Property | Default | Read at | Effect |
|---|---|---|---|
| `slumsms.gmAudit` | `true` (`!"false".equalsIgnoreCase(...)`) | src/main/java/client/command/GmAudit.java:63 | off = audit rows are dropped before the queue (log line at src/main/java/client/command/CommandsExecutor.java:180 still prints) |
| `slumsms.gmAudit.queue` | `10000`, floor 16 | src/main/java/client/command/GmAudit.java:100 | bounded-queue capacity |
| `weather.enabled` | `false` | src/main/java/client/command/CommandsExecutor.java:505 | registers `!weather` (rank 2) and `!lamp` (rank 3); live does not pass it |
| `slums.qa` | off | src/main/java/soloMapling/qa/QaSuite.java:33, `isEnabled()` :77-78 = property **or** `AttackCapture.isEnabled()` | registers `!qa`, `!botcombat`, `!godlydrop` (src/main/java/client/command/CommandsExecutor.java:378-381) and enables `!botwatch` (:389-391) |
| `slums.attackcapture` | off | src/main/java/soloMapling/server/AttackCapture.java:48 | registers `!classtest`/`!botshowcase`/`!botground` (:373-377) and indirectly `!qa` |
| `slums.autoqa` | off | src/main/java/soloMapling/autoqa/AutoQa.java:26 (also `ServerConfig.AUTOQA_ENABLED`, src/main/java/config/ServerConfig.java:206, default `false`) | registers `!autoqa` (:386-388), `!botwatch`, and the two shadow commands below |
| `slums.socialtrace` | unset | src/main/java/soloMapling/autoqa/SocialTrace.java:50 (tested at src/main/java/client/command/CommandsExecutor.java:392) | `!socialtrace` when auto-QA is off |
| `slums.decide.shadow` | unset | src/main/java/soloMapling/ArtificialPlayer/BotConvo/DecideShadow.java:74 (tested at :395) | `!decideshadow` when auto-QA is off |
| `slums.botlife.preview` | off | src/main/java/client/command/commands/gm3/BotWhyCommand.java:23 | with QA on, registers `!botwhy` (:382-384) |
| `slums.debugtools` | off, forced value wins over the QA default | src/main/java/soloMapling/server/DebugTools.java:20, `isEnabled()` :26-32 | registers `!navprobe`, `!botstatus` (src/main/java/client/command/CommandsExecutor.java:467-470) |
| `slums.killcalib` | off | src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/KillCalibration.java:40 | registers `!killcalib` (:471-473); live passes `-Dslums.killcalib=true` |
| `slums.mechanics` (+ `slums.mechanics.<row>`) | off | src/main/java/soloMapling/mechanics/Mechanics.java:20, `enabled()` :131-133 | registers `!mechanics`/`!mech` (:398-401); live passes `-Dslums.mechanics=true` |
| `slums.bonds` | off | src/main/java/soloMapling/ArtificialPlayer/RelationView.java:30-32 (`enabled()`), OFF_TEXT :23 | `@bonds` replies "Bonds are not available yet." when off (src/main/java/client/command/commands/gm0/BondsCommand.java:19-26); live passes `-Dslums.bonds=true` |
| `solomapling.enabled` **and** a template id | off | src/main/java/soloMapling/server/MapleRootSolo.java:16-18 | registers the whole bot block — 25 names, all rank 4 — at src/main/java/client/command/CommandsExecutor.java:418-443 |
| `slums.weblogin.dir`, `slums.weblogin.group` | unset / `slums-login` | src/main/java/client/command/commands/gm0/WebLoginCommand.java:45, :215 | `@weblogin` is **not registered** (src/main/java/client/command/CommandsExecutor.java:270-272 explains); the class survives for its revoked-file check |
| `slumsms.gm.allowGiving` | off | src/main/java/client/GmGiving.java:11 (used at src/main/java/server/Trade.java:640-643, src/main/java/net/server/channel/handlers/MesoDropHandler.java:46, src/main/java/client/inventory/manipulator/InventoryManipulator.java:912) | without it a GM cannot trade or drop items — the guard that stops `!item` becoming a dupe path |

`config.yaml` keys that change command *behaviour* (fields of src/main/java/config/ServerConfig.java):
`MINIMUM_GM_LEVEL_TO_DROP` (:586, default 4 — a lower GM may not drop items, src/main/java/net/server/channel/handlers/MesoDropHandler.java:51),
`BLOCK_GENERATE_CASH_ITEM` (:386, src/main/java/client/command/commands/gm2/ItemCommand.java:64), `USE_WHOLE_SERVER_RANKING` (:387),
`MAXIMUM_TRANSACTIONS_FOR_BUYBACK` (:595, src/main/java/client/command/commands/gm0/BuyBackCommand.java:34),
`AUTOQA_ENABLED` (:206). The `//Commands Configuration` section (:385) holds no command-level on/off key.

Live (`docs/release/1.6.4.conf:271`, `UNIT_EDITS`) runs with, among the command-relevant flags: `-Dslums.bonds=true`,
`-Dslums.killcalib=true`, `-Dslums.mechanics=true` + per-row flags, `-Dslums.exchange=true`/`.desk=true`,
`-Dslums.achievements=true`, `-Dslums.ownedalts.marry=true`. Live passes **no** `-Dslums.qa`, `-Dslums.autoqa`,
`-Dweather.enabled`, `-Dslums.debugtools`, `-Dslums.socialtrace`, `-Dslums.decide.shadow`, so `!qa`, `!autoqa`,
`!weather`/`!lamp`, `!navprobe`, `!botstatus`, `!botcombat`, `!godlydrop`, `!socialtrace`, `!decideshadow` do not exist there.
Live also never passes `-Dslumsms.gmAudit=false`, so auditing is on. TEST's `start-test.sh` is on mapletest and was not
read: **TEST: unknown** for every row above.

## Invariants

- **Rank is enforced from the registry, not the directory.** `addCommand(syntax, rank, class)` is the only place `setRank` runs
  (src/main/java/client/command/CommandsExecutor.java:224); the gate at :147 compares `gmLevel()` with that value. A class's
  package is decorative.
- **`isGM()` means `gmLevel > 1`.** Ranks 0 and 1 are reachable by plain players, so anything at rank ≤ 1 must be safe for
  every account (src/main/java/client/Character.java:7578-7580).
- **One instance per name.** Commands are instantiated once at boot (:223) and reused; per-player state in a `Command` field
  is shared by the whole server. Two spellings of the same behaviour must be two `addCommand` calls (aliases via `String[]`).
- **First registration wins.** A duplicate name logs a warning and is ignored (:214-217) — order inside a tier method decides.
- **Names are case-folded.** Lookup key and params are lowercased (:139-140); a command that needs the original casing must read
  `player.getLastCommandMessage()` (set at :138).
- **Every command is audited unless it is a plain player command.** `audit()` returns early only for `rank <= 0 && gmLevel <= 0`
  (:175-178), so `@nx` used by a GM is recorded too, with outcome `ok`, `denied` or `error`.
- **Jail blocks commands before the name is read** (:128-131), so no command can be used to leave jail.
- **A GM's chat is never throttled and never truncated** (src/main/java/net/server/channel/handlers/GeneralChatHandler.java:26-28, :38).
- **`ADMIN_COMMAND` is GM-gated at the handler**, `if (!c.getPlayer().isGM()) return;` (src/main/java/net/server/channel/handlers/AdminCommandHandler.java:51) —
  the packet carries no rank, so a level-2 GM may emit any of its modes.
- **The registry is built before the world is usable** (src/main/java/net/server/Server.java:1028) and re-built per
  `CommandsExecutor` instance only in tests (`freshForTest`, src/main/java/client/command/CommandsExecutor.java:94-96).

## Origin

| Path | Origin | Evidence |
|---|---|---|
| src/main/java/client/command/CommandsExecutor.java | **modified** | oldest commit `03d13cb21 2026-10-18 Establish deployable MapleRoot server baseline`; differs from `/root/workspace/cosmicms` (405 diff lines) |
| src/main/java/client/command/Command.java | **modified** | `listed()` (:39-42) and its javadoc are the only addition; commit `d1fd2f3cd 2026-10-28 @help leaves @nx out while the NX exchange isn't open on this server` |
| src/main/java/client/command/CommandHelpers.java | **custom** | 21 lines, absent from `/root/workspace/cosmicms`; oldest commit `03d13cb21` (it arrived with the repack's own tree, never upstream) |
| src/main/java/client/command/GmAudit.java | **custom** | single commit `4b0edf1c7 2026-10-20 Add GM safety: slumsms-gm tool, reserved names, command audit`; absent upstream |
| src/main/java/net/server/channel/handlers/GeneralChatHandler.java | **modified** | `03d13cb21` baseline + `b1e1b6fb7`, `b27adfdb5`, `350eaf80c`; 75 diff lines vs cosmic (spam helper, QaLog, hidden-GM bot skip, `MapleRootSolo.runHook`) |
| src/main/java/net/server/channel/handlers/AdminCommandHandler.java | **modified** | `03d13cb21` + `febe3752a 2026-10-31 Refuse a blocked map to a direct getMap instead of serving Henesys`; adds null checks on `/send` (:128-134) and the 3-arg `killMonster` call (:143) |
| src/main/java/net/server/channel/handlers/AdminChatHandler.java | **base** | byte-identical to `/root/workspace/cosmicms`; only `03d13cb21` in its history |
| src/main/java/client/command/commands/gm0/NxCommand.java | **custom** | `a926ab7bd 2026-10-27 …`, `6a622db1e`, `d1fd2f3cd`; the package has 116 files absent upstream |
| src/main/java/client/command/commands/gm0/BondsCommand.java | **custom** | `63007f61a 2026-11-04 Add @bonds: read-only relationship list behind -Dslums.bonds (default off)` |
| src/main/java/client/command/commands/gm3/{QaCommand,AutoQaCommand,MechanicsCommand,BotWhyCommand,…}.java | **custom** | `ade430132 2026-10-21 Add the !qa test-scenario suite…`; `bfe9c4a6d 2026-10-22 Auto-QA phases 2-3…` |
| src/main/java/client/command/commands/gm1/* | **modified layout** | 5 upstream classes (`BossHp`, `Goto`, `MobHp`, `WhatDropsFrom`, `WhoDrops`) exist only in the base tree; ours live in `gm0`/`gm2`. `tools/cosmicdiff/classifications.json` classifies `java-moved gm1/*` as **DELIBERATE** ("MapleRoot moved the command class to another GM tier") |
| registration comment/deliberate absences | **deliberate** | `tools/cosmicdiff/classifications.json`: `java-commented` on CommandsExecutor (:264) DELIBERATE ("Same GM-1 tier reshuffle"); `java-commented-lines` DELIBERATE (owner 2026-10-21: "@gacha stays unregistered"); `command-unregistered gacha` DELIBERATE; `command-unregistered buffme` **RESTORED** ("registered at GM level 1 again"); `devtest` / `gm6/DevtestCommand.java` **UPSTREAM-NEW** (added to Cosmic 2024-05-21, never taken here) |
| whole-package counts | — | 169 java files shared with cosmic, 35 differ, 116 ours-only, 7 base-only (`gm1` ×5, `gm3/GiveVpCommand.java`, `gm6/DevtestCommand.java`) |

Commented-out registrations still present in src/main/java/client/command/CommandsExecutor.java: `starforce`/`sf` (:243),
`craft` (:251), `damageskin` (:303), `sell`/`resetap`/`resetsp` (:305-307, inside `/* */`), `orebag` (:311), and the whole
`whatdropsfrom`/`whodrops`/`goto` block in `registerLv1Commands` (:321-323) — the commands themselves are registered at rank 0
(:309-310, :301). `webLogin` is documented as dropped for 1.6.0 (:270-272) and `autologin` as removed in 1.5.1 for security (:312).

## Tests and QA

| Layer | What it covers | Where |
|---|---|---|
| Unit, registry | `hasCommand`/`rankForTest`/`classForTest`/`freshForTest` seams (src/main/java/client/command/CommandsExecutor.java:77-96) drive registration assertions | src/test/java/client/command/AchievementsRegisteredTest.java, BuffMeRegisteredTest.java, InviteRegisteredTest.java, MenuCommandsRegisteredTest.java, TpRegisteredTest.java, WebLoginRegisteredTest.java, CosmicCommandsRegisteredTest.java, NxHiddenFromHelpTest.java, OwnedAltCommandsTest.java |
| Unit, gating | `!qa` absent without a flag, present at rank 3 with `-Dslums.qa=true` | src/test/java/client/command/QaCommandGatingTest.java:12-35 |
| Unit, `@help` | `@nx` missing from the Common tier while the exchange is closed, still rank 0, names and descriptions stay paired | src/test/java/client/command/NxHiddenFromHelpTest.java:15-24 |
| Unit, removal | `autologin` is not registered at any tier, and no login/PIC path reads the flag | src/test/java/client/command/AutoLoginRemovedTest.java:17-33 |
| Unit, audit | entries reach the sink in order on the `gm-audit-writer` thread; a stuck sink never blocks the caller and a full queue drops + counts | src/test/java/client/command/GmAuditTest.java:40-71 |
| Unit, per command | 18 classes under src/test/java/client/command/commands/{gm0,gm1,gm2,gm3,gm4}/ (e.g. InviteCommandTest, ResetPwCommandTest, RateCommandTest, GmHideCommandsTest, BotWhyCommandTest, ZakumPqCommandTest) | — |
| `!qa` scenarios | 45 `*Scenarios.java` aggregated by `QaScenarios.all()` (src/main/java/soloMapling/qa/QaScenarios.java:76-96); `ExchangeScenarios` and `BondScenarios` drive `@nx`/`@bonds` as text through the real handler | src/main/java/soloMapling/qa/ |
| Auto-QA checks | 197 `c.add(...)` checks in src/main/java/soloMapling/autoqa/AutoQaCatalog.java; the convention "`YELLOW `" = a command answer is documented at :51, command-driven checks include `t-nx-1` (:1200), `t-nx-2` (:1213), `t-xc-1` (:1277) | synthetic players go through `GeneralChatHandler` (src/main/java/soloMapling/autoqa/SyntheticPlayer.java:51-61, src/main/java/soloMapling/autoqa/SyntheticPlayers.java:250-261) |
| Manual, scratch server | ticket 43 records level 2 `!whereami` allowed and `!setgmlevel` denied, both rows in `gm_command_audit` | docs/tickets/43-gm-security-audit.md:41-48 |

Not covered by any test: `AdminCommandHandler` (no test class mentions it), `AdminChatHandler`, the jail gate, the
`tryacquireClient` lock/refusal path, the chat spam gate, the 127-char disconnect, `@help`'s JS side
(`scripts/npc/commands.js`), and the actual `gm_command_audit` INSERT (the tests inject a sink).
`GmAudit`'s writer thread and queue overflow are unit-tested; the DB write is not.

## Known issues

| Card | One line |
|---|---|
| b281 (/root/work/STATUS-1.6.4.md:38) | With the NX desk on, `@nx tickers` / `@nx book` still describe NX as an order book — in-game text defect, TRIAGE (ticket 171). |
| b285 (/root/work/STATUS-1.6.4.md:81) | Ticket 178: an alt may offer an upgrade for a sibling alt, but the trade command refuses it unless the owner is online. |
| L14 (/root/work/STATUS-1.6.4.md:105) | Players cannot yet see their bots' relationship status; lane is the Relationship window (`@bonds`), stage 1 open. |
| /root/work/STATUS-1.6.4.md:12 | `-Dslums.bonds` is ON for live but "fix the gap first (alts that are out must show in `@bonds`)". |
| e02 (/root/work/STATUS-1.6.4.md:112) | Idea, later: `@botme` alts should accept their owner's invites (family, guild). |
| e12 (/root/work/STATUS-1.6.4.md:122) | The Power Elixir ticker reads `PELIXIR` in `@nx` / `!exchange`; owner named the label, alias wanted. |
| ticket 279 (docs/tickets/279-dead-code-cleanup.md:45-66) | `!weekly` (`BossLogCommand`) works by opening NPC 3004030 with script `3004015`; the old body is commented out at :25-63 and the F08 audit finding is stale — park unless a rewrite is wanted. |
| ticket 43 (docs/tickets/43-gm-security-audit.md) | GM hide is audited but bots still notice a hidden GM (backlog note in the ticket). |
| NEW-FINDING: src/main/java/net/server/channel/handlers/AdminCommandHandler.java:67-75 | `case 0x01` (`/d`, clear inventory) has `return;` **inside** the slot loop, after the first iteration — it clears at most slot 1 and leaves the packet handler; the `//TODO What is the point of this loop?` next to it suggests this was never resolved. Base code (the file's only local changes are :128-134 and :143), so it is also an upstream defect. |
| NEW-FINDING: src/main/java/net/server/channel/handlers/AdminCommandHandler.java:136-145 | The `/kill` mode iterates `for (int x = 0; x < amount; x++) monsterx.get(x)` over the map's monster list without bounding `amount`; asking for more than exist throws `IndexOutOfBoundsException`, swallowed by src/main/java/client/Client.java:222. |
| NEW-FINDING: src/main/java/client/command/CommandsExecutor.java:368 | `gachalist` is registered with rank 0 inside `registerLv2Commands`, while its class sits in `commands/gm2/` — so `@help` lists it under "Common" and players keep it after a rank change. |
| NEW-FINDING: src/main/java/client/command/CommandsExecutor.java:444-445 | `gacharoll` / `gachasim` (classes in `commands/gm4/`) are registered at rank 3 inside `registerLv3Commands`, so GM 3 can roll and simulate the gachapon; the directory convention says 4. |
| NEW-FINDING: src/main/java/client/command/CommandsExecutor.java:242 | `invite` is a rank-0 registration of a `gm3` class; the GM branch is decided *inside* `InviteCommand.execute` by `player.gmLevel() >= GM_LEVEL` (src/main/java/client/command/commands/gm3/InviteCommand.java:33 and :46, `GM_LEVEL = 3` at src/main/java/soloMapling/site/auth/InviteRules.java:25) rather than by the rank gate. Works, but audited as a rank-0 command, so a plain player's `@invite` is only logged when the char is a GM. |
| NEW-FINDING: src/main/java/client/command/CommandsExecutor.java:373-505 | Feature-gated registrations read system properties at **construction** time (the singleton is built at src/main/java/net/server/Server.java:1028), so flipping `-Dslums.qa`/`-Dslums.autoqa`/`-Dweather.enabled` requires a restart; `commands.js` reads the same static list, so `@help` cannot show a command that appears later. |

## State

**working** — evidence: the flow runs through the real packet path (src/main/java/net/PacketProcessor.java:146 →
src/main/java/net/server/channel/handlers/GeneralChatHandler.java:45-46), the registry is exercised by 14 registry/gating
test classes plus 18 per-command test classes under src/test/java/client/command/, `!qa`/`!autoqa` drive commands against a live server in
the QA suite (docs/ops/qa-suite.md:12, :852), and ticket 43 records a scratch-server run where a level-2 GM was allowed
`!whereami` and denied `!setgmlevel` with both rows in `gm_command_audit` (docs/tickets/43-gm-security-audit.md:41-48).
Partial edges: `AdminCommandHandler`'s `/d` and `/kill` modes carry the base defects above, and live's flag set makes
`!qa`/`!autoqa`/`!weather`/debug commands non-existent by design, so none of those paths can be exercised on live.
Not verified by this review: the `gm_command_audit` INSERT against a real database (tests inject a sink), and TEST's flag
set (unknown).

## Open questions

- TEST's `start-test.sh` is on mapletest and was not read, so which of `slums.qa`, `slums.autoqa`, `weather.enabled`,
  `slums.debugtools`, `slums.socialtrace`, `slums.decide.shadow` TEST passes is unknown.
- `-Dsolomapling.enabled` and the template id required by src/main/java/soloMapling/server/MapleRootSolo.java:16-18 are not in
  `docs/release/1.6.4.conf`; where live sets them (if at all) could not be determined here, so whether live registers the
  rank-4 bot block (src/main/java/client/command/CommandsExecutor.java:418-443) is unknown. Live's `-Dslums.bonds=true` implies
  bots do run, so the flag is probably set outside this conf.
- Whether the `gm_command_audit` write path has ever been exercised against the production schema (migration 009) — the only
  evidence is the ticket's scratch-server note; no test or log line was found that shows a live INSERT.
- `docs/release/1.6.4.conf:33-40` lists `-Dslums.bonds=true` as an experimental flag while `UNIT_EDITS` (:271) passes it;
  `docs/architecture/switches.md:70-72` already records that doc drift. Which block is authoritative for release notes was not
  settled here.
- The audit table has an `ip` column fed from `client.getRemoteAddress()` (src/main/java/client/command/CommandsExecutor.java:183);
  whether that value is ever null for a proxied login (it is caught and logged as a warning at src/main/java/client/Client.java:195-200)
  could not be established.
