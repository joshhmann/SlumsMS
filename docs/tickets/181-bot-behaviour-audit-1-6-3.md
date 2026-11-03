# 181: Fix the bot behaviour defects the 1.6.2 audit found

Requested by the owner, 2026-11-03 (UTC and PT).

Target: not a 1.6.3 release gate and it expands nothing in the 1.6.3 candidate; the audit finished after the 1.6.2
switch set was closed, and this ticket is its tracking copy. Fixes enter a release only through the normal gate —
ticket 168 for a frozen release, otherwise the next release's scope. Two items are marked as an economy choice and
need the owner's decision before anyone writes code; a number is never chosen by an implementer.

## Goal

Make the bot population behave correctly where it currently behaves wrongly, in severity order, without changing
economy numbers, rates or caps on the authority of an audit.

## Review basis and limits

Source read at **`c0e0a2cd7c2845985738cd4d077d0c5dd1b974df`** (`SlumsMS-163-release` tip when the audit closed).
Basis: six read-only audit documents and their merged copy under `/root/work/audit/` —
`behaviour-train.md` (7 findings), `behaviour-market.md` (14), `behaviour-social.md` (11), `behaviour-life.md` (8),
`behaviour-progress.md` (8), `behaviour-sweep.md` (12) — and `BEHAVIOUR.md`, which carries all 60: **2 S1, 20 S2,
38 S3**.

**Nothing was built, run, deployed or queried** — no Maven run, no server, no DB, no TEST or live access. Every claim
is source reading, and every S1 and S2 quotation below was re-read against the tree at that sha. The six documents
were deduped against `/root/work/audit/AUDIT.md` (24 findings) and
[163](163-bot-audit-1-6-2.md) (58), so nothing here re-reports either; the nearest overlap is 163's
`src/main/java/soloMapling/ArtificialPlayer/BotMakerSystem/BotMaker.java:155-215` batch dead-end, which is a different defect from P2 below. `BEHAVIOUR.md` §"Refuted — do not
re-report" is the record of what was checked and cleared (24 findings plus 34 files read clean); do not reopen those.

Coverage note: the four owned-alt files were read and cleared (`OwnedAltCombat`, `OwnedAltLoot`, `OwnedAltOrders`,
`OwnedAltInvite`, listed in `BEHAVIOUR.md` §"Refuted"), so all 60 rows are population-bot behaviour; alt-specific
defects are 163's (`OwnedAltService`, `OwnedAltBackend`, `OwnedAltCompanion`).

Severity was re-graded uniformly, overriding the worker grades:

- **down, bot-side loss:** `prog#P1` and `prog#P2` (S1 → S2), `social#1` (S1 → S2).
- **up, player-visible economy break:** `market#F-07` (S2 → S1), `market#F-08` and `market#F-14` (S3 → S2).
- **down, unreachable today and not a loss:** `market#F-02` (S2 → S3); `market#F-01` was S3 in the source and stays
  S3 (unreachable — `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:520` skips bot stalls).
- **S1 unchanged:** `market#F-03`.

## S1 — stop the bleeding

### `market#F-03` — an accepted stall offer rewrites the listing price with no floor

**Mechanism.** `src/main/java/soloMapling/FreeMarket/ShopOfferSystem/ShopOfferResponse.java:144`:

```java
offer.getShopItem().setPrice(Math.toIntExact(finalPrice));
```

The only guard is `ShopMath.isSaleablePrice(finalPrice)` — a sanity check on the number, not a price. `PriceBand`
(the clamp direct trades already use) is never consulted, and nothing compares the accepted price to what the bot
paid or to `BotShops.askFor`'s ask. The AFK path at `:85` does the same thing five to thirty minutes later from a
captured `ShopItem`. `OfferEvaluator` accepts at ≥0.95 of the ask and counters at ≥0.80, so a published price can end
at 70% of the ask through a counter the player accepts. Bots and real players are served out of one stall, so a
player's 70% offer can be accepted between two bot sales.

**What a player sees.** A stall that takes 70% if the price is named; the item then sits on the shelf at that lower
price for the next buyer. The visible ask becomes a suggestion.

**Smallest fix.** Both writers go through one helper that returns
`Math.max(request, PriceBand.of(BOT_SELLS, stackValue, friend).low())` and never less than the bot's own paid price
for a resale lot. Two call sites, one function.

**Proof.** A test that accepts an offer at 70% of a listing's ask and asserts
`shopItem.getPrice() >= PriceBand.of(BOT_SELLS, ...).low()` — fails today (it gets the 70% price). A second test
asserting the listing never falls below `paid` for a `BotConsignment` lot — fails today.

**Owner decision, not an implementer's:** the floor's number and whether an under-band offer gets a counter or a
refusal are economy choices (see Work order, step 5).

### `market#F-07` — twenty merchant bots mint an item per sale

**Mechanism.** `src/main/java/soloMapling/ArtificialPlayer/BotTypes/SellingMerchantBot.java:109-117`:

```java
FMItem currItem = itemsToSell.get(itemIndex);
if (currItem != null) {
    Item item = generateItem(currItem.getItemId(), 1, 1);
    Integer askingPrice = askingPriceFor(item);
    if (askingPrice != null) {
        stock(item, askingPrice);
        return;
    }
}
```

`generateItem` builds a brand-new item on every trade visit; `stock` lists it at
`priceAdjustmentRules((int) (rawValue * 0.9))` (`:140`). Nothing counts what is created, no budget is charged, and the
"market value" it prices against is the book these same sales feed.

**Reachability.** The twenty are live at boot, not only on a GM command. Waves 2, 4 and 7 of
`environmentLoadStartup()` (`src/main/java/soloMapling/Environment/EnvironmentManager.java:110`) ask
`spawnMerchBotsBatch` for 2+2+2, 3+2+3 and 2+2+2 selling merchants (`:134-136`, `:158-160`, `:183-185`), scaled by
`BOT_FM_ENVIRONMENT_SCALE`, which is `1.0` in `src/main/java/config/ServerConfig.java:145` and overridden nowhere in
`docs/release/1.6.3.conf`, so all 20 spawn on 910000000, spread over the channels. Boot reaches it from
`src/main/java/soloMapling/server/MapleRootSolo.java:51-53` (`MethodScheduler.runAfterDelay(...)` under
`-Dsolomapling.autopopulate`), and the live unit carries that flag (`docs/ops/lxc125-architecture.md:24`,
`deploy/monitor/README.md:56`). Twenty is a floor: `!env spawnmerchantbots` re-runs
`spawnMerchBotsInFMEntrance()` (`src/main/java/soloMapling/Environment/EnvironmentManager.java:619-645`, from
`src/main/java/client/command/commands/gm4/EnvironmentCommand.java:386`, command `env` registered at
`src/main/java/client/command/CommandsExecutor.java:427`) for 20 more per run, and a finished NX merchant
`convertBack()`s into one (`src/main/java/soloMapling/ArtificialPlayer/BotTypes/NXMerchantBot.java:249-252`).

**What a player sees.** Scrolls — v83's most economically load-bearing consumable — available without limit from
NPC-like bots at a 10% discount to the published market, regenerating instantly. Scroll prices drift down until the
bots' own 0.9× rule pins them, and `ScrollBudget`'s rationing is bypassed because the faucet is a bot.

**Smallest fix.** Stop generating on demand: draw from the same pool `BotConsignment`/`BotShops` draw from and refuse
to stock when the pool is empty. Minimum viable interim: one counter per bot per day plus
`EconomyLedger.minted(ITEM, "merchant-bot")` on every `stock`.

**Proof.** A test that calls the stocking path twice against an empty pool and asserts the second call stocks nothing
— it stocks a fresh item today. At day scale: items minted by `SellingMerchantBot == 0`.

**Owner decision, not an implementer's:** whether merchant bots may mint at all, and the daily cap if they may, is an
economy choice.

## S2 — the world visibly misbehaving

Grouped as the audit groups them. Detail, quotes and proofs: `/root/work/audit/BEHAVIOUR.md` §"Detail".

### Training

| id | path:line | mechanism | fix |
|---|---|---|---|
| `train#F1` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:747`, `:752`, `:1585`, `:1630` | Death respawns into the map's return town, but the town stop plans shops from `homeMapId`, so the bot buys in another region or buys nothing. | Take `chr.getMapId()` as the shopping town when it has a `TOWN_SHOPS` entry, falling back to `homeMapId`. |
| `train#F2` | `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotShopping.java:499-503`, `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotSupplies.java:161-171` | A restock that bought nothing returns nothing, so a bot below ~200×level mesos loops town→shop→train→town. | `RestockOutcome{BOUGHT, PARTIAL, NOTHING}`; on `NOTHING`, refuse a resupply trip for 10 minutes. |

### Market

| id | path:line | mechanism | fix |
|---|---|---|---|
| `market#F-04` | `src/main/java/soloMapling/FreeMarket/ShopOfferSystem/ShopOfferSystem.java:25`, `:123` | `lockedItems` is only added to — one accepted offer silences that stall slot for the life of the JVM. | `PendingOffers` keyed `(ownerId, index)` with release on close, sale and timeout. |
| `market#F-05` | `src/main/java/soloMapling/FreeMarket/ShopOfferSystem/ShopOfferResponse.java:84-93` | The AFK accept fires 5–30 minutes later against a captured listing it never reserved or re-checked. | Hold the item on accept; the scheduled task verifies item id, owner and index, else tells the player it is gone. |
| `market#F-08` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:714` | `makeRoom` searches the bag by item id, so a pre-existing copy of the purchase is destroyed. | Track the purchased slot, or tag the purchase, and match on that. |
| `market#F-14` | `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:574` | "Unknown value" and "worthless" are one branch, so an unvalued equip is destroyed. | Destroy only when `value != null && value <= 0`; keep and count on `null`. |

### Social

| id | path:line | mechanism | fix |
|---|---|---|---|
| `social#1` + `#3` | `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1389-1391`, `:1440-1447` | The busy check drops the line after `SayDo.said` and after the history and whisper trail are written, so the bot remembers saying what the player never got. | Busy check above both writes; write history and `SayDo` only when delivery ran; retry once after the busy window. |
| `social#2` | `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1430` vs `:1046` | The liveness stop tests that *a* session exists for the pair, not that it is *this* session, so a stale reply lands in a new conversation. | Capture the session and compare identity, as `:1046` already does. |
| `social#4` | `src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/ReplyCleaner.java:55` vs `:123` | Validation permits two lines; `clean()` sends the first non-blank one only. | Pick one: reject two lines, or join them in `clean()`. |
| `social#5` | `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:61`, `src/main/java/soloMapling/ArtificialPlayer/BotChatPrompt.java:258` | Whisper and map chat share one history, so a whisper is answered in public and the prompt's last user turn can be the wrong channel's. | Tag turns with the channel and build the prompt from the channel being served. |
| `social#6` | `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1007`, `src/main/java/soloMapling/ArtificialPlayer/BotConvo/ConvoSwitches.java:32` | The one-chat-start-per-player-per-hour cap only runs when `ConvoSwitches.planner()` is on, which is off by default. | Apply the cap unconditionally in `start()`; the switch governs planning, not the ceiling. |

### Life and quests

| id | path:line | mechanism | fix |
|---|---|---|---|
| `life#F1` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1319` | A dwell's only exit consults `freeForAutomaticLifeActivity`, false for any party holding a real player, online or not. | A hard deadline that does not consult the predicate, or test presence rather than membership. |
| `life#F2` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:3036` | `LifeTownSlots.release(getChr().getId())` runs above the null guard, so an NPE skips the rest of teardown and leaks the reservation. | `Character chr = getChr(); if (chr == null) return;` at the top. |
| `life#F4` | `src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:489-491`, `:571-576` | "Cannot complete" is written into the quest map as COMPLETED, permanently, and burns the day's slot. | Make `record` a pure log call; only write the quest map after the gate returns true. |

### Progression and gear

| id | path:line | mechanism | fix |
|---|---|---|---|
| `prog#P1` | `src/main/java/soloMapling/ArtificialPlayer/BotDecoratorSystem/BotEquipChecker.java:101-104`, `src/main/java/soloMapling/ArtificialPlayer/BotDecoratorSystem/BotDecorateEquips.java:47-53`, `src/main/java/soloMapling/ArtificialPlayer/BotCustomization.java:86-89` | A `SLOT_LONGCOAT` overall leaves `hasTop`/`hasPants` false, so the bot reads naked, is given coat and pants, and the overall is removed and discarded. | `isNaked` also tests the overall slot; decoration fills only empty slots and bags what it replaces. |
| `prog#P2` | `src/main/java/soloMapling/ArtificialPlayer/BotMakerSystem/BotMaker.java:386`, `src/main/java/soloMapling/ArtificialPlayer/BotGearSystem/BotGear.java:285-297` | The junk test scores against today's wear set, so anything above the bot's level scores 0 and is disassembled (`maker.botBatch` is off by default). | `GearScoring.holdingValue` — value at the piece's own `reqLevel` — as the junk predicate. |
| `prog#P3` | `src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotProgression.java:283`, `:377`, `src/main/java/client/Character.java:7691` | SP is credited to the current job's book but the bot reads and writes only `getRemainingSps()[0]`, so ~270 points at level 120 are unusable. | One owner of the five books: capture sums, apply writes the current book and zeroes the rest. |

### Cross-cutting

| id | path:line | mechanism | fix |
|---|---|---|---|
| `sweep#B1` | `src/main/java/soloMapling/ArtificialPlayer/BotBlockList.java:78`, `:81`, `:101`, `:152-156`, `:169` | A plain `ArrayList` in a static singleton, written on the trade thread and iterated on the bot tick — `ConcurrentModificationException` mid-trade. | `Set<BlockKey>` on `ConcurrentHashMap.newKeySet()`, expiry on read plus a sweep. |
| `sweep#R1` | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:60`, `:81-83` | A message is dequeued, then a dispatch throw is printed and swallowed — the player's line is gone. | Deliver before dequeueing permanently, or re-queue once with a counter; log through the repo logger. |

## S3 — debt, leaks and dead answers

38 rows. Detail and proofs: `/root/work/audit/BEHAVIOUR.md`; the source document is named in its `id`.

| id | file:line | claim |
|---|---|---|
| `train#F3` | `src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotProgression.java:185-191` | Offscreen level-ups never call `BotSkillBooks.soon`; the observed path does. |
| `train#F4` | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/OffscreenProgression.java:35-43` | EXP above `LEVEL_CAP` is banked, so the bot reports a level it can never reach. |
| `train#F5` | `src/main/java/soloMapling/ArtificialPlayer/BotAttackSystem/OffscreenCombat.java:88-96` | The pace cache key omits `mobsOnMap` and the map's EXP multiplier. |
| `train#F6` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/TrainingBot.java:1981`, `:2865-2869` | `Math.max(1, level)` erases the "no mobs here" sentinel, making the `currentMobLevel > 0` guard dead. |
| `train#F7` | `src/main/java/soloMapling/ArtificialPlayer/BotGrindSystem/TrainingMapChooser.java:134` vs `:155`, `:187` | Three definitions of "one more bot fits"; `saturated()` exists in two of five strategies. |
| `market#F-01` | `src/main/java/server/maps/HiredMerchant.java:929-975` | `botBuy` never credits a bot seller; unreachable today because `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:520` skips bot stalls. |
| `market#F-02` | `src/main/java/server/maps/HiredMerchant.java:932` | `botBuy` lacks `buy`'s bundles/exist guards and casts quantity to `(short)`; both callers pass 1. |
| `market#F-06` | `src/main/java/soloMapling/Economy/MesoSinks.java:89` | The price book samples gross while the seller nets price minus fee, so every fair value drifts up by the tax. |
| `market#F-09` | `src/main/java/soloMapling/Economy/BotBotTrade.java:444` | A failed `takeBack` is log-only: the seller keeps both mesos and item, the buyer is refunded. |
| `market#F-10` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBotWallet.java:27` | 10,000,000 mesos per bot per day are minted outside the ledger (~1.5e10/day fleet-wide). |
| `market#F-11` | `src/main/java/soloMapling/ArtificialPlayer/BotTypes/FMBot.java:555` | Bot→bot purchases bypass the buyer's wealth and stock caps. |
| `market#F-12` | `src/main/java/soloMapling/Economy/BotShops.java:275` | A refused resale listing is destroyed instead of re-pooled. |
| `market#F-13` | `src/main/java/soloMapling/Economy/BotDirectTrade.java:311-320` | A direct-trade sale credits the wallet without `noteMesoEarned`, so the ledger is blind to it. |
| `social#7` | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/MapChatLog.java:100` → `src/main/java/soloMapling/ArtificialPlayer/BotChatPrompt.java:249` | Third parties' real names can enter another player's reply prompt. |
| `social#8` | `src/main/java/soloMapling/ArtificialPlayer/BotConvo/DecideShadow.java` | The shadow judge has no budget, is given the wrong text, and defaults to keeping it. |
| `social#9` | `src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/ChatDecide.java` (`takeBudget`) | The decide budget is 8/s world-wide with no per-player share and a silent fallback to scripted rules. |
| `social#10` | `src/main/java/soloMapling/ArtificialPlayer/BotRecognition.java:34`, `:148` | `lastGreetOnSight` is never pruned; only `resetForTest` clears it. |
| `social#11` | `src/main/java/soloMapling/ArtificialPlayer/BotAffinitySystem/AffinityReactions.java:638` vs `:606` | The tone judge is live for romance, shadow for reply quality, and a third dead copy still exists. |
| `life#F3` | `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeActivityQueue.java:159` | A reentrant `finish` delivers one activity's end record as the next activity's. |
| `life#F5` | `src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuests.java:321` | `ranked` refuses any quest with an off-map mob, so the scorer's detour pricing is unreachable. |
| `life#F6` | `src/main/java/soloMapling/ArtificialPlayer/BotQuestSystem/BotQuestLog.java:149`, `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeMemory.java:504`, `src/main/java/soloMapling/ArtificialPlayer/TrendIndex.java:138` | Three different definitions of "a day" in three files. |
| `life#F7` | `src/main/java/soloMapling/ArtificialPlayer/BotConvo/BotTalk.java:540-545` | A 90-second say-do promise, refreshed on every talk, starves the life tick of a popular bot. |
| `life#F8` | `src/main/java/soloMapling/ArtificialPlayer/BotLifeSystem/LifeRuntime.java:236`, `:404`; `src/main/java/soloMapling/ArtificialPlayer/BotWanderSystem/BotWanderSystem.java:37`, `:211` | Duplicated `FREE_PHASES` literals and a 400 ms poll per town bot. |
| `prog#P4` | `src/main/java/soloMapling/ArtificialPlayer/BotScrollSystem/BotScrolling.java:306-308` | The EV scroll planner is handed constants instead of the gear facts. |
| `prog#P5` | `src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/JobErrand.java` (`mayAdvance`) | A graph route search runs inside `synchronized (bot)` on the progression path. |
| `prog#P6` | `src/main/java/soloMapling/ArtificialPlayer/BotDecoratorSystem/BotDecorationQueue.java` (`decorateBot`) | The deferred queue re-dresses bots that are already dressed, blind to what they wear. |
| `prog#P7` | `src/main/java/soloMapling/ArtificialPlayer/BotProgressionSystem/BotSkillPlan.java` (`Entry`) | No prerequisite model: entry order is the only guarantee a prerequisite precedes its skill. |
| `prog#P8` | `src/main/java/soloMapling/ArtificialPlayer/BotDecoratorSystem/BotDecorate.java:63-78` | The census branch roll is discarded for every bot born below the branch level. |
| `sweep#B2` | `src/main/java/soloMapling/ArtificialPlayer/BotMegaphoneSystem/MegaReactions.java:63`, `:159` | `playerLast` is written for every megaphone and never pruned, while `botLast` is pruned at `:152`. |
| `sweep#B3` | `src/main/java/soloMapling/achievements/AchievementService.java:286-288` | Cache overflow calls `cache.clear()`, dropping every character's rows at once. |
| `sweep#B4` | `src/main/java/soloMapling/ArtificialPlayer/BotFamily.java:78`, `:278`, `:300`, `:305` | `pairAsked` gains an entry per pair and loses one only in the QA-only forget path. |
| `sweep#B5` | `src/main/java/soloMapling/ArtificialPlayer/BotGuildSystem/BotGuilds.java:93`, `:1312`, `:1929-1933` | `nextAt` keys accumulate per player, guild and pair; exactly one removal site exists. |
| `sweep#B6` | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/MapChatLog.java:40`, `:65`, `:81`, `:204` | `byMap` is keyed by channel and map and never drops a map's deque. |
| `sweep#B7` | `src/main/java/soloMapling/Gachapon/BotGacha.java:55`, `:118`, `:245-246` | The day and walk registries are cleared only by test hooks. |
| `sweep#R2` | `src/main/java/soloMapling/ArtificialPlayer/BotSupplySystem/BotVitals.java:50-51`, `:489-491` vs `src/main/java/soloMapling/ArtificialPlayer/BotGeneration.java:435-436` | Locks and regen carry are released only from `BotAttackDriver.clearBot`, which despawn does not call. |
| `sweep#R3` | `src/main/java/soloMapling/ArtificialPlayer/BotChatterSystem/BotChatter.java:274`, `:317`, `:331` | `ENGAGED` self-heals on a stale deadline; `COOLDOWN` has no equivalent cleanup. |
| `sweep#R4` | `src/main/java/soloMapling/ArtificialPlayer/BotMenus.java:46`, `:62`, `:151-152` | An open menu holds the player id and a `BiConsumer` until the player closes or logs out. |
| `sweep#D1` | `src/main/java/soloMapling/ArtificialPlayer/BotMegaphoneSystem/BotNoiseFilter.java:105` | `namesViewer` has no callers. |

Paths in these tables are repo-relative. Where a row names one file and then `:line` segments, the segments belong to
that file.

## Systems worth changing, not just fixes

The audit ranks thirteen; the top four by value ÷ cost, each with the cheapest experiment. The full list, including
the switch-only proposals it dropped, is in `BEHAVIOUR.md` §"Better systems". Fan-out and skyline work stays in 163.

1. **One wear predicate, and decoration that only fills empty slots.** `WearRules.wearable(worn, wear)` becomes the
   single constraint for `BotGearPlanner.Search` (`:331`), `BotGearOptimizer` (`:469`) and `BotGear.apply`; `isNaked`
   tests any of top/pants/overall; `EquipItem` bags what it replaces. Three private copies of "can this bot wear this"
   already disagree about overalls and shields, and the dresser writes through a function that cannot tell "slot empty"
   from "slot wrong". P1 and P6 both disappear, and the drift cannot recur. ~2 days.
   *Cheapest experiment:* property-test 1,000 recorded (worn, bag) pairs through the three predicates and count
   disagreements — P1's overall case is one today.
2. **A receipt on the supply trip.** `restock` returns `RestockOutcome{BOUGHT, PARTIAL, NOTHING}`; the town phase
   refuses a resupply trip for 10 minutes after `NOTHING`. The system already knows it bought nothing and throws that
   away, so the only feedback loop is walking there again; F2 and the crowding it causes are downstream. ~0.5 day.
   *Cheapest experiment:* log `bought nothing` per bot-hour against `supplies: out` per bot-hour; at 1:1 the loop
   already drives the town cycle.
3. **`BotBlockList` as a concurrent map.** `Set<BlockKey>` on `ConcurrentHashMap.newKeySet()`, expiry on read plus a
   sweep. A static singleton list cannot survive two threads, and both threads already exist, so B1 is structural
   rather than a missed `synchronized`. ~0.5 day.
   *Cheapest experiment:* the two-thread add/sweep test (B1's proof) — it throws today.
4. **One owned-lifetime sweep for static maps.** `BoundedMaps.prune(map, soft, hard, predicate)` (already used by
   `src/main/java/soloMapling/ArtificialPlayer/BotEpisodes.java:173` and `src/main/java/soloMapling/ArtificialPlayer/BotFameRequest.java:128`) applied to `playerLast`, `pairAsked`, `nextAt`, `byMap`,
   `COOLDOWN`, `openByPlayer` and the gacha registries; `removeBotFromServer` becomes the single documented exit.
   B2 and B4–B7, R3 and R4 are one defect with six names — a map keyed on an id with no owner to remove it — and the
   seventh omission is invisible in review because there is no place to look. ~1.5 days.
   *Cheapest experiment:* the "every registry has an exit" reflection test; it enumerates today's six.

## What needs a server to confirm

No measurement here was taken. Each row is a claim a read-only audit cannot settle.

| claim | observation that settles it |
|---|---|
| F-03's real cost — how many stall sales come from an offer at all | One hour of `[ShopOfferSystem] Accepted:` prices against `BotShops.askFor`'s ask, joined on item id |
| F-07's volume — scrolls entering from `SellingMerchantBot` versus `ScrollBudget`'s NPC buys | One day of item-mint lines grouped by source (needs Better systems #9 or a temporary counter) |
| P1's reach — the share of bots wearing a 105xxxx overall | One population census with a counter where `getItem(SLOT_TOP) != null` and an `equipTopBottom` counter |
| P2's coverage — the fraction of carried equips passing `MakerService.canDisassemble` | `townBatch` dry-run count with `maker.botBatch=true` on a copy |
| P3's visibility — whether any surface renders `getRemainingSps()` | `@botinfo` whispered at a second-job bot |
| `life#F1` reachable — a bot in `LIFE_DWELL` with a real player in its party | A capture around `lifeNote("ARRIVED activity=")` with the party list |
| `life#F2` reachable — `getChr()` null at `stopScheduledTask` on a live stop path | Whether the leak shows as towns under-filling over a day, or is one-line hardening only |
| `sweep#B1` — whether a completing trade collides with another bot's tick | A few hundred rapid trade accepts under load, watching for `ConcurrentModificationException` |
| `sweep#R2` — which bot types leave via `removeBotFromServer` versus `stopScheduledTask` | A heap histogram of `java.lang.Object` and `double[]` after a 30-minute invasion |
| `sweep#B3`/`B4` — whether 6,000 character ids and 8,000 family pairs are reached | The two map sizes after a day of live churn |
| `social#7` — whether a third party's name reaches another player's screen | A transcript scan for names not in the addressee's party or map |
| `social#9` — whether the 8/s decide budget is ever exhausted | `ChatStats.decideSkipped()` hourly at 1,600 bots |
| The 250 ms combat tick at 1,600 grinders | `t-bt-1` already fails (25.3 ms against a 20 ms budget); the tick itself is a measurement, not a reading |

## Work order and completion

1. **`market#F-03` first,** behind its own switch, default OFF: the single price-floor helper, both writers routed
   through it, plus the fail-before tests above. The switch's conf line goes in `docs/release/<v>.conf`; it is not
   turned on for live.
2. **`market#F-07` second,** also behind its own switch, default OFF: stop `generateItem` on demand, draw from the
   existing pool, and add the mint ledger line. Both S1 switches stay OFF until the owner decides the two numbers in
   step 5 — the code lands, the economy behaviour does not change.
3. **The S2 batch, split by subsystem** so each lands with its own fail-before test: market offers and listings
   (`F-04`, `F-05`), social delivery (`social#1`+`#3`, `#2`, `#4`, `#5`, `#6`), life and quests (`life#F1`, `F2`,
   `F4`), gear (`P1`, `P2`, `P3`, plus `F-08`, `F-14`), training (`train#F1`, `F2`), concurrency (`sweep#B1`, `R1`).
   Smallest safe order is the ones a player sees on screen first; `P1` and the dresser are one change.
4. **Keep 163 and this ticket from double-counting.** 163 owns the 58 launch findings (buff levels, Temple steps,
   PQ registry, alt lifecycle, skyline); this ticket owns the 60 audit rows. If a fix touches both, it is recorded
   once, in 163, and this table points at it.
5. **Record every economy question in `REPORT.md` instead of choosing a number:** the stall-offer price floor
   (`F-03`), the merchant-bot item mint and its cap (`F-07`), and the gross-versus-net market sample (`F-06`) that
   moves every fair value if fixed. Each is one question with 2–4 options, recommended first.
6. **Do the S3 rows only as the systems in "Systems worth changing" land** — most of them are that section's
   consequence, and fixing them individually re-creates the drift. `sweep#D1` (dead code) can go with any commit.

For any copied source, pin and credit the upstream commit and record it in `docs/PROVENANCE.md` under the existing
porting rules. Follow repository license requirements. No runtime merge, deployment, live access or additional
feature adoption is authorized by creating this ticket.
