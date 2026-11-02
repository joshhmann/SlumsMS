# Ticket 182: Papulatus, Scarga and Showa dereference a bot's headless client in the clear-reward loop (F01)

**Status:** open for 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4 content hunt, where the boss /
expedition audit (`/root/work/agent-runs/server/s-boss-audit/`, rows F01) rated it impact 4 and "FIX". Line numbers
are as of `release-1.6.4` tip `c0e0a2cd7` unless a commit is named; the audit ran on `163-integration`, so a few
`src/main/java` numbers differ there and should be found by grep rather than trusted.

## The defect

Three expedition clear handlers hand their leaf/item reward to every member of `eim.getPlayers()` through that member's
client, with no bot check:

- `scripts/event/PapulatusBattle.js:238-240`
  ```js
  party = eim.getPlayers()
  for (var i = 0; i < party.size(); i++)
  eim.getPlayers().get(i).getClient().getAbstractPlayerInteraction().gainItem(4001126, 5);
  ```
- `scripts/event/ScargaBattle.js:203-205` — the same loop, `gainItem(4001006, 30)`
- `scripts/event/ShowaBattle.js:217-219` — the same loop, `gainItem(4001006, 1)`

These loops are fork-added; stock Cosmic has no reward loop in these three scripts at all.

## Why it throws

A population bot's client is one shared headless `BotClient` per channel:
`src/main/java/client/BotClient.java:10-16` constructs it with `super(null, -1, "bot", null, world, channel)` and
nothing ever calls `setPlayer` on it. So `getClient().getPlayer()` is `null` for every bot, and the call chain is:

1. `scripting/AbstractPlayerInteraction.gainItem(...)` (`:623`, one of the overloads from `:599`) reaches
   `InventoryManipulator.checkSpace(c, id, quantity, "")` at `:680`;
2. `client/inventory/manipulator/InventoryManipulator.java:466-474` maps `c.getPlayer() == null` to `false` (the 1.5.2
   bot-safe twin — it returns the deliberate null, it does not crash);
3. back in `gainItem`, `:681` is
   `c.getPlayer().dropMessage(1, "Your inventory is full. Please remove an item from your ...")` — `c` here is the bot's
   shared `BotClient`, so `c.getPlayer()` is `null` — **NullPointerException at
   `scripting/AbstractPlayerInteraction.java:681`**. (The audit pins `:711-712` on `163-integration`, where the same
   statement sits later in the method; grep `Your inventory is full`.)

## What a player sees

`eim.clearPQ()` runs before the loop in all three scripts, so the clear itself, the boss-log row and
`setEventCleared` survive; the item half does not. The loop aborts at the first bot in iteration order, so every member
after it silently gets nothing. Bots get ids at or above 1 000 000 000 (`Character.java:744`), so in the `HashMap` copy
that `getPlayers()` returns they usually sort last — which means the common case is "the player got theirs, a bot
member threw into the log". When the bot sorts first, the player loses the reward too.

Scarga loses one more write, because it sits after the loop: `scripts/event/ScargaBattle.js:208`
`eim.setIntProperty("defeatedBoss", killed + 1);` never runs, so the kill counter stops advancing for the rest of the
instance.

## Reachable

All three are expedition recruiters that exist in the data and are placed on maps, and their mobs exist in `Mob.wz`:

| boss | recruiter script | NPC | map | entry map | mob |
|---|---|---|---|---|---|
| Papulatus | `scripts/npc/2041024.js` | 2041024 | `Map.wz/Map/Map2/220080000.img.xml` | 220080001 | 8500002 |
| Scarga | `scripts/npc/9270047.js` | 9270047 | `Map.wz/Map/Map5/551030100.img.xml` | 551030200 | 9420544 / 9420549 |
| Showa | `scripts/npc/9120201.js` | 9120201 | `Map.wz/Map/Map8/801040004.img.xml` | 801040100 | 9400114 / 9400300 |

A bot becomes a member automatically: `createExpedition` (`scripting/AbstractPlayerInteraction.java:1168`, four-arg
form at `:1172`) runs the `MapleRootSolo.runHook` at `:1185-1187` that calls
`ExpeditionBots.autoJoin` (`soloMapling/ArtificialPlayer/BotPartySystem/ExpeditionBots.java:65-120`), which registers
every eligible party bot; `registerExpeditionTeam` (`scripting/event/EventInstanceManager.java:448-456`, method at
`:448`) then puts them
inside the instance, so they are in `eim.getPlayers()` when the boss dies. Papulatus allows a solo expedition, so a
lone player is safe — the moment he has one party bot, the loop reaches it.

## Why these three

Four scripts doing the same thing were given the guard in 1.6.1: `scripts/event/ZakumBattle.js:211`,
`PinkBeanBattle.js:267` and `VonLeonBattle.js:255` as
`if (party.get(i).isBot()) continue; // 1.6.1: a bot has no client to hand leaves to (it threw and stopped the loop)`,
and `HorntailBattle.js:228` as the block form `if (!player.isBot()) {`. (`CygnusBattle.js:234` has it too, but that
boss has no mob data yet.)

Papulatus, Scarga and Showa were missed. The audit also names four more unguarded loops:
`scripts/event/LatanicaBattle.js:239` and `MahaBattle.js:149` (reachable, same one-line fix — include them), and
`CastellanBattle.js:238` and `KrexelBattle.js:229` (unreachable until F09's mob data exists; add the guard anyway so the
next person who lands that data does not inherit the bug).

## Acceptance

- Each of the three (plus Latanica and Maha) clear path skips a bot member instead of dereferencing its client.
- A test in `src/test/java/scripting/`, modelled on the existing `AldolScargaScriptTest` (which already builds a real
  `Expedition` with `ExpeditionBotsAccess` and a `BotClient`-backed bot), loads each `scripts/event/*.js`, builds
  `eim.getPlayers()` as one player plus one bot, calls `monsterKilled(mob, eim)` with the boss mob, and asserts: no
  exception, the bot is skipped, and the player's inventory received the item. For Scarga it also asserts
  `defeatedBoss` advanced.
- Fail-before: with the guard removed, the same test reproduces the `NullPointerException` at
  `scripting/AbstractPlayerInteraction.java:712` through `InventoryManipulator.checkSpace`.
- `AllScriptsLoadTest` stays green.

## For the release book

- **Card:** Expeditions | a bot in the party makes the Papulatus/Scarga/Showa clear reward throw mid-loop, so members after it get nothing and Scarga stops counting kills | S3 | lane Server | closer: one `isBot()` guard per loop in `scripts/event/{Papulatus,Scarga,Showa,Latanica,Maha}Battle.js`, proven by a `src/test/java/scripting` test that drives each clear with one player plus one bot (fail-before: NPE at `AbstractPlayerInteraction.java:712`).
- **Patch notes (draft, player words):** "Bossing with bots in your party no longer swallows the clear reward."
- **Confign line:** none — script fix, no switch.
- **Test-tab row:** test: Papulatus / Scarga / Showa with one party bot | how: take a party bot into each of the three expeditions, kill the boss, and confirm the leaf/item arrives and nothing is logged as an exception (Scarga: `!bosslog` still shows the kill).
