# Social systems and the bot economy: research (2026-10-21)

Research only. Nothing in `src/main/java` or `scripts/` was changed. Base: branch `release-1.4.0` (ae3c91cb7),
compared with stock Cosmic at `/root/workspace/cosmicms`. Live numbers are read-only `SELECT`s on the live
`mapleroot` schema (container `buns-clean-db-1`) and the live `config.yaml`, taken 2026-10-21 about 10:10 UTC.

Tickets: [49](../tickets/49-bot-buddies.md) buddies, [50](../tickets/50-guilds.md) guilds,
[51](../tickets/51-bot-party-chat-meetups.md) parties, [52](../tickets/52-marriage-and-rings.md) marriage and rings,
[53](../tickets/53-family.md) family, [54](../tickets/54-messenger-and-memos.md) messenger and memos,
[55](../tickets/55-duey-storage-fredrick.md) Duey, storage and Fredrick,
[56](../tickets/56-bot-fm-buy-cap-and-meso-sink.md) the economy pick.

## How this was checked

- **Opcodes:** every `registerHandler` in Cosmic's `PacketProcessor` is also registered in ours. Ours adds six
  (cash-shop window, daily check-in, monster book query, set item, Hyper Rock, weapon tint).
  `PacketProcessor.java` is shorter only because of import and formatting changes.
- **Handler bodies:** every social and economy handler was diffed with `diff -w`. Buddies, guilds, alliance, BBS,
  rings, weddings, family, messenger, party, Duey, storage, NPC shop, Owl and hired merchant are **identical to Cosmic
  or differ only in imports**. There are four exceptions, all deliberate SlumsMS hooks:
  - `WhisperHandler`: a whisper to a bot goes to its chat brain.
  - `PartyOperationHandler`: a party invite to a bot goes to `BotPartyQueue`.
  - `PlayerInteractionHandler`: chat in a bot's shop goes to the haggling system.
  - `MessengerHandler`: a GM 4+ messenger console.
- **Services:** `Guild`, `Alliance`, `GuildPackets`, `Messenger`, `Family`, `FamilyEntry`, `Ring` and `Marriage`
  are identical to Cosmic, as are `InviteCoordinator` and the wedding events (`WeddingCathedral.js`,
  `WeddingChapel.js`). `Party` adds a race-proof 6-member cap.
- **Scripts:** every guild NPC (2010007 Heracle, 2010008 emblem, 2010009 Lenario) and Amoria NPC
  (9201000-9201143) matches Cosmic. One exception: `9201001.js` (Nana) has the whole script pasted twice. The second
  copy wins and only fixes a typo, so it is harmless.
- **Client:** the SlumsMS DLL source (`/root/work/client/src`) touches none of these systems. It adds buddy and guild
  chat colour pickers to the overlay, and that is all. v83 handles all of these windows natively.

So the repack did **not** strip the social systems. What blocks them is configuration, the replaced Cash Shop, and
the fact that bots have no `characters` rows.

## The one structural fact: bots are not in `characters`

Bots live in `botstore`, `botroster` and `botmemory` with ids `1,000,000,000 + BotId`. A bot is a `Character` in
memory, built from the `SMTemplate` row, and **has no `characters` row**. Every stock social system looks people up
by joining or keying on `characters`:

| System | What breaks for a bot |
|---|---|
| Buddies | `BuddyList.loadFromDb` joins `characters`, so a saved bot buddy disappears at the player's next login. Adding an offline bot looks it up in `characters`: "does not exist". |
| Guilds | Members load from `characters.guildid`; rank and guild id are columns there. |
| Family | `family_character.cid` has a foreign key to `characters(id)`. |
| Marriage | `marriages` and `rings` have no foreign keys, but `characters.partnerId` and `marriageItemId` hold the state. |

Every bot-side ticket below therefore needs one decision: **(a)** read bot names and levels from `botstore` where
the stock code reads `characters` (small, read-path patches), or **(b)** give "social" bots shadow `characters`
rows. (b) clashes with name checks, rankings, autosave and world transfer. The tickets assume **(a)**.

A second shared fact: bots do not call `World.loggedOn` or `loggedOff` (`BotGeneration` only adds them to the
map, channel and storage). Buddy, guild and family online indicators therefore never fire for bots.

## Per system

### Buddies: works for players; bots missing (ticket 49, M)

- **Players:** stock code, complete. Live has 2 rows (one mutual pair, characters 7 and 8).
- **Bots:**
  - Adding an **online** bot runs `requestBuddyAdd`, which queues a request on the bot. The bot's client drops the
    packet, so the player sees "pending" forever. The row the player's save writes (`buddyid` = the bot) is
    dropped at the player's next login by the `characters` join.
  - Adding an **offline** bot gives "does not exist".
  - Whispers already reach the chat brain (`WhisperHandler.whisperBot`, then `BotConversation.say`; a "busy"
    whisper without a brain).
  - Buddy chat (`MultiChatHandler` type 0) is not routed to bots.
- **Dependency:** a player can still create a character with an online bot's name. The fix exists only in the
  SoloMapling repo (commit 4fc53172) and is not ported to SlumsMS.

### Guilds and alliances: complete in code, unused (ticket 50, S fix + L build)

- **Players:**
  - Stock code. Live has 0 guilds, 0 alliances and 0 characters with a guild.
  - Live config: `CREATE_GUILD_MIN_PARTNERS: 1` (Cosmic default 6), cost 1.5m, expansion 500k-5m.
- **Likely player bug (FIX):**
  - `Guild.getEligiblePlayersForGuild` adds **every** character on Heracle's map (200000301) that is not in a party
    or guild, and bots count.
  - The leader's match confirmation goes to all of them and needs every one to accept. `MatchCheckerCoordinator`
    has no timeout, and bots never answer. So creating a guild hangs while any bot stands in the guild
    headquarters, and the match only clears when someone leaves or logs out.
  - Not reproduced in game. The code path is clear.
- **Bots:**
  - A guild invite sent to a bot gets no answer and expires after the `InviteCoordinator` timeout. Harmless.
  - Guild chat (`MultiChat` type 2) is not routed to bots. Bots never join, found or chat in guilds.
  - `BotPersona` already lists "finding a guild" as an interest, and the dialogue context resolver has
    `{PLAYER_GUILD}`.

### Parties: works, bots partly (ticket 51, S-M)

- **Players:** stock code plus the 6-cap race fix.
- **Bots:**
  - A player invites a bot after asking in map chat. `BotRecruitManager` rolls accept or decline, arms an accept
    window and turns the bot into a `FollowerBot`.
  - Ticket 14's party commands (`jason follow`, `1 stop`) work through map chat.
- **Missing:**
  - Party chat (`MultiChat` type 1) is not routed to bots, so orders typed in party chat do nothing.
  - Bots never invite a player.
  - No "meet me at X" path exists outside the follower flow.

### Marriage, engagement and couple/friendship rings: code complete, Cash Shop gap (ticket 52, M)

- **Players:**
  - Moony (9201000) engagement boxes, Nana's proof quests, the Amoria wedding NPCs, the Cathedral and Chapel events,
    and the wedding packets are all stock and complete.
  - Wedding tickets 5251000-5251003 are in the SlumsMS Cash Shop catalogue.
  - Live has 0 marriages and 0 rings.
  - Never tried end to end on SlumsMS: **verify**.
- **Gap:**
  - The real Cash Shop is replaced. The button opens the SlumsMS window (`cashshop.window.enabled`) or MapleRoot's
    NPC 9010004.
  - The catalogue builder deliberately leaves out every ring that needs a partner record: crush 11120xx,
    friendship 11128xx and wedding 1112803-09 (`tools/cashshop/catalog_rules.py`, "no partner flow in the window").
  - So **couple and friendship rings cannot be bought**. Stock `CashOperationHandler` action 0x1D / 0x23 handles
    them, but the window never sends it.
- **Bots:**
  - A proposal to a bot is a packet the bot ignores, and the invite expires. Nothing happens.
  - Bots have no partner state.

### Family: off (ticket 53, M)

- **Live config:** `USE_FAMILY_SYSTEM: false` (Cosmic default true).
- **What the flag does:**
  - `OpenFamilyHandler`, `FamilyAddHandler` and `FamilyUseHandler` return early.
  - `World` and `Server` skip the family load and reset tasks.
  - The v83 Family button does nothing.
- **Gaps in stock Cosmic:** the six EXP and drop entitlements and Family Bonding are `//not implemented`. The rep is
  never spent and no buff is given. Only Family Reunion and Summon work.
- **Data:** live has 0 rows in `family_character`.
- **Bots:** the foreign key to `characters` blocks bot entries.

### Messenger and memos: works (ticket 54, S)

- **Players:**
  - Messenger is stock, plus a GM 4+ console hook.
  - Memos (`NoteActionHandler`, `notes` table) are stock. Live has 0 notes.
- **Bots:** a messenger invite to a bot is ignored and expires. Bots never use memos.

### Duey, storage, Fredrick (ticket 55, S)

- **Duey:**
  - Live config: `USE_DUEY: false` (Cosmic default true).
  - `NPCTalkHandler` still opens the Duey window for NPC 9010009, but `DueyHandler` refuses every action. Players
    see a window where nothing works.
- **Storage:**
  - Works. Live has 5 storages and 0 stored mesos. Storage starts at 48 slots (MapleRoot change).
  - Missing: `scripts/npc/2110000.js` (Ariant's storage keeper) exists in Cosmic and not here.
- **Fredrick:** `fredrickRetrieveItems` lacks Cosmic's `merchant.clearItems()` dupe guard after the delete. The NPC
  script (9030000) refuses while a merchant is open, which covers the obvious path. Restore it anyway.
  - **Hand-off:** this sits in the cosmic-diff agent's area, so check its report before restoring.

## Economy

### Current state

- **Rates (live):** EXP 3x, meso 10x, drop 4x, boss drop 3x. NPC buy and sell prices are not scaled.
- **Bot meso intake (ticket 15, live):**
  - `BotYieldCaps` gates every mob drop and offscreen share with a per-map leaky bucket.
  - The daily cap is max(100k, level x 10k) x meso rate: 9.6m a day for a level 96 bot.
  - `botyield` holds one row per bot for the current day.
- **Bot spending today:**
  - Pots and ammo (`BotShopping`, ticket 09).
  - Ferry and taxi fares (`GCTravel`).
  - The 4th-job package (`BotProgression`).
  - Buys in shops (`FMBot`).
- **Not built:** NPC selling (ticket 12) and scrolling and gachapon (ticket 11). Neither mints or burns anything yet.
- **FM:**
  - `ArtificialFreeMarket` opens in-memory `HiredMerchantArtificial` shops with minted stock priced from static
    YAML (`itemPool/itemConfig`).
  - `FMBot` walks the FM rooms and buys from **any** shop, bot or player, when ask ÷ YAML value ≤ 1.2. The odds
    run from 100% at ≤ 0.7 down to 5% at 1.2.
  - There is no daily or per-player cap. The mesos come from the bot's own purse (`BotHoldings.spendMeso`).
- **NPC shops (live):** 110 shops and 3,795 items. 9 items are priced in Pitch (white-scroll style shops), and none
  are free.

### Live data (read-only aggregates)

| What | Value |
|---|---|
| Bots stored (`botstore`) | 3,488. Tiers: S 1,066, A 1,022, B 690, D 367, C 343 |
| Mesos held by bots | **17.69 billion**. Average 5.07m, max 19.1m; average 11.7m at level 90+ |
| Bot meso intake, 2026-10-20 | **7.83 billion** (1,515 bots, max 9.6m each = the cap) |
| Bot meso intake, 2026-10-21 so far | 4.91 billion (982 bots) |
| Real characters | 7. Mesos 27.9m, one character holding 26.8m |
| Player shops / hired merchants / Fredrick / Duey / MTS | 0 / 0 / 0 / 0 / 0 |
| Storage mesos | 0 |
| Roster activities | grind 1,823, social 384, townwanderer 329, idle 10 |

### What this means

- Bots mint about 8 billion mesos a day against 28 million held by players. Inside the bot world that is
  harmless; bot mesos are inert.
- **It becomes a player faucet the day a player opens a shop in the FM.** Any item listed at or below its YAML value
  sells to an `FMBot` at near-certain odds, with no cap. That is effectively an NPC that buys anything at "market
  value", backed by 17.7 billion mesos.
- Ticket 38's "bots buy from real players only what their plan needs, within a daily cap" is the missing piece. It
  is cheap to do on its own, ahead of the rest of 38.

### Recommendation (the owner deprioritised economy work, so this is advice only)

1. **Ticket 56, part A (S): cap what bots pay real players.** Do this before players use the FM.
   - Per-player and world daily caps on `FMBot` purchases from non-bot shops.
   - Bots never buy an item a bot would not use.
   - Ledger the totals.
2. **Ticket 56, part B (S-M): a bot wealth ceiling.** Mesos above about 3x the band's savings goal go to sinks
   (ticket 38's rule). Until then, a flat ceiling per band stops `botstore` from growing without limit.
3. Leave the rest of 38 (price book from real trades, keep/list/NPC loot, basket index) until real players trade.

## Also found (not fixed)

- **Real Cash Shop replaced** by the SlumsMS window or NPC 9010004. Partner rings are left out (see 52), along with
  the other item groups listed in `catalog.tsv`'s header.
- **The MTS button** warps to the MapleRoot hub (`EnterMTSHandler`, 90 vs 274 lines in Cosmic). This is a MapleRoot
  design choice; `USE_MTS: true` is otherwise unused.
- **`scripts/npc/2040022.js`** is a MapleRoot menu (rooty / donor shop / JQ rewards / damage skin / server info). In
  Cosmic it is the Ludibrium refining NPC for level 30-50 weapons with stimulators, and that refiner is gone.
  `9900000.js` is shrunk too (a GM NPC; likely intentional).
- **`scripts/npc/2110000.js`** (Ariant storage) is missing. See 55.
- **Handlers that differ from Cosmic** (pets: `MovePetHandler`, `PetLootHandler`, `PetAutoPotHandler`; items:
  `UseCashItemHandler`, `UseCatchItemHandler`, `UseItemHandler`, `ScrollHandler`; `EventInstanceManager` and
  `EventManager`). These look like MapleRoot features, not empty stubs. `ScrollHandler` is no longer empty on
  1.4.0. Left to the cosmic-diff agent.
- **Missing from 1.4.0:** the GM 1 commands `goto`, `whodrops`, `whatdropsfrom`, `mobhp`, `bosshp`, plus
  `DatabaseMigrations`, `ExpLogger`, `BanishInfo` and `MakeCharInfo`. Left to the cosmic-diff agent.
- **PQs, Monster Carnival, minigames, mounts and beauty NPCs:** handlers are registered and their bodies match
  Cosmic. `USE_CPQ: true`. These were not exercised in game.
