# 09: Buy supplies at NPC shops

**Area:** Real bots  
**Depends on:** 03,08  
**Status:** closed 2026-10-25 (done in v1.3.0, branch shops).

## What

Bots walk to town shops and buy with their own mesos (cosmic_agents shop and supplies capabilities, behind gateways).

## Done when

Mesos go down and items go up; ledgered.

## Result

`BotShopping` is installed as ticket 08's `BotResupply` hook at SoloMapling start-up (`MapleRootSolo.start`).

- **Where it shops (`ShopCatalog`).** The shop list comes from the server's own data:
  - It takes the NPCs on the town map and on the maps up to two portals away in the same id block. For example,
    Henesys 100000000 covers its market 100000100 and stores 1000001xx, but not the hunting fields.
  - An NPC counts as a shop when `ShopFactory.getShopForNPC` (the `shops`/`shopitems` tables) gives it one. Its
    listing is that `Shop`'s items in client order, at the listed prices.
  - Sleepywood's only shop is in the regular sauna (105040401), which only an NPC teleport reaches. It is listed for
    Sleepywood, and the existing sauna trip is used to get there.
  - Results are cached per map and per town. `!reloadshops` clears the cache.
- **When it goes.** On the town stop, after the rest (`BotSupplies.arriveInTown`), a bot goes shopping if:
  - a shortage sent it back, or
  - it holds less than its ammo kit, or less than half its potion kit.
  
  A stack that is merely short of full does not count. The bot picks the town shop that sells the most of what it
  needs (ammo it can fire counts most). `TrainingBot` then walks it there with `GCMovement`: a bot on screen visibly
  walks up beside the shopkeeper, and an offscreen bot uses the coarse planner.
  - If the shop is on the town map, the bot walks there straight away.
  - If the shop is on a store map, that map becomes the first stop of the itinerary.
  - In Sleepywood, the bot takes the sauna trip.
  
  The bot buys when it arrives, or after 25 s on the shop's map. Then it browses as before. The hook only buys at
  the shop the bot was sent to and only on that map: no remote purchases. A trip that fails leaves the bot with
  nothing, and 08's melee fallback still applies.
- **How it buys.** Purchases mirror `Shop.buy` and `Shop.recharge`, without the packets:
  - Potions and arrows cost the listed price per unit. Each transaction buys at most one stack, and only if it fits
    (`checkSpace`, here `BotHoldings.canHold`).
  - A star or bullet buy is a whole stack (`getSlotMax` with Claw/Gun Mastery) for the listed price.
  - A recharge refills a stack the bot holds for ceil(WZ `unitPrice` x missing).
  - Mesos leave through `BotHoldings.spendMeso`. Items arrive through `grantItem`, or the new
    `BotHoldings.rechargeHeld`. The ledger records both, and the normal store sweep writes them.
  - Buying is a meso sink: none of it passes the ticket 15 caps.
- **What it buys.**
  - **Ammo,** only when the main attack fires any (Soul Arrow bowmen buy none). The goal is 1,000 + 40 x level shots,
    capped at 4,000. The bot first recharges its own star or bullet stacks that are at least as good as anything the
    shop sells (an Ilbi it looted). Then it buys the best ammo the shop sells that its weapon fires at its level:
    most attack first, then cheapest. On MapleRoot's data, a stack is 9,999 Subi plus mastery for 500 mesos, so
    one stack covers the goal.
  - **Potions.** It only considers plain potions that restore a flat amount of HP or of MP (never an Elixir). Among
    those within 1.5x of the cheapest price per point, it picks the one whose heal is nearest a quarter of its max.
    This gives Red, then Orange, then White for HP, and Blue, then Mana Elixir for MP. Where a shop sells foods
    (Pure Water, Unagi), stronger bots buy those.
  - **Amounts.** HP potions: 40 + 3 x level (50-400). MP potions for a mage: 60 + 4 x level (80-500). For another bot
    whose attack costs MP: 20 + 2 x level (30-250).
  - **Order.** Essentials come first (a stack of ammo, a third of each potion goal). Then it fills the full kit.
  - **Budget.** It spends only what is above a reserve. The reserve is the larger of a quarter of its mesos and 200 per
    level, which leaves enough for fares.
  - A bot that cannot afford ammo goes without. It fights in melee (ticket 08) until it can buy some.
- **Ledger read-out.** Each visit logs `[Shop]`: the NPC, map, distance from the NPC, on screen or offscreen, what
  was bought, the cost and the mesos left. `!botyield` gains a line with the totals since boot: visits, mesos spent,
  items bought, stars recharged, stashes granted.
- **Starter stash (`StarterStash`), migration `007-bot-starter-stash.sql`** (with a rollback file):
  - **Why.** Before 08, bots never used up supplies, so stored bots hold none. A claw bot without stars hits for
    2 damage.
  - **Who gets it.** Every stored bot whose new nullable `botstore.StockedAt` is NULL gets the stash once, on
    adoption, after its build and weapon are restored. Every newly minted bot gets it in its first write.
  - **What it holds.** Its own kit, using the rules above: shop ammo it may fire (Subi, Bullet from level 10, or
    Arrows for Bow or Crossbow), as whole stacks (at most two) or `ammoGoal` arrows. Plus potions from a general
    store's list.
  - **Recording it.** `StockedAt` (epoch ms) is written in the same transaction as the items. That covers
    `writeRow`, `insertRow`, and the debit route's item write.
  - **Not counted.** The stash bypasses the ticket 15 yield caps. It comes in through `BotHoldings.grantItem`, so
    it is ledgered.
  - **Without 007.** No stash is given (logged once), because nothing could record it.
  - **Rollback.** The rollback drops the column and leaves the items. Re-applying 007 would stash every bot again.
- **08 hook split.** `BotSupplies.visitTown` is now `arriveInTown`, which rests the bot and returns its needs, plus
  `restock`, which offers the needs to the hook and then settles the melee fallback. `visitTown` still runs both.
- **Merge.** `origin/release-1.3.0` is merged in first. The only conflict was this ticket's 08 file. The
  `TrainingBot` offscreen loot path still draws from the offscreen budget, then passes `BotYieldCaps`
  (map and daily), then calls `BotGear.afterLoot`. 08's affordable-share accrual sits in front of it.
- **Tests:** 2,675 run, 0 failures (release-1.3.0 + supplies merged: 2,661). 14 are new:
  - `BotShoppingTest` (12) covers:
    - the potion progression;
    - the best ammo per weapon and level;
    - listed price and stacks, the ledger debit, the reserve, and essentials before the kit;
    - star stacks at the listed price and recharge at `unitPrice`;
    - arrows by the unit;
    - no budget and a full tab;
    - the town's shops (two portals deep, fields excluded, Sleepywood's sauna);
    - not walking for a part-empty stack;
    - buying only at the headed shop;
    - stash contents, and the migration text.
  - `BotStoreStashTest` (2) covers `StockedAt` written with the row, and nothing without 007.

## Headless check on 164

The run used `/opt/mapleroot-dev/real-stats-test/shops-run`, on ports 8499/8105, against a scratch schema
`mapleroot_shops`:
- The schema was loaded from a host-side `mysqldump` of `mapleroot_realstats` (`realstats-before-shops.sql.gz`).
  Migrations 006 and 007 were applied, then dropped afterwards. `Server/`'s jar and the shared schema were not touched.
- For the run, the even-numbered claw, bow and crossbow bots were seeded (`shops-seed.sql`) so that they run out
  during it:
  - `StockedAt` set, so they get no stash;
  - 300 shots, 20 Orange and 20 Blue potions;
  - at least 50,000 mesos.

Every other bot got its stash on boot.

- **Offscreen, 22 min (`shops-offscreen-run.log`).**
  - 567 stashes, and 0 errors from the supply code.
  - Every bot that went to town for supplies bought at a real shop, 37-55 px from the NPC.
  - Hermit toastboi ran its 300 Subi dry with Avenger (4111005) and went to town for ammo at 20:33:57. At
    20:36:44 it recharged 10,199 Subi at El Nath's Department Store (npc 2022001, 211000102) and bought 147 Mana
    Elixirs and 234 Unagi, for 358,739 of its 8.8m mesos. By 20:40 it was throwing Avenger again (ammo
    10,199 -> 10,167).
  - Bowman Papusama (level 13, no Soul Arrow) ran out of arrows. In Perion's store it bought 1,520 Arrows for Bow,
    plus Lemons and Orange Potions, for 13,160 mesos.
  - Ranger CurseStar96 and hunter thepepe ran out of MP with Arrow Bomb. Each bought its MP and HP kit (229,770 and
    131,216 mesos) and was firing again afterwards.
  - An earlier 15-minute run on the same setup showed the same with assassin iiclock: Lucky Seven (4001344) ran
    out, it recharged 10,199 stars for 10,199 mesos, and it was back at 39 swings a minute with ammo
    10,015 -> 9,875.
- **On screen, 13 min (`shops-observed-run.log`).**
  - This run used a temporary, uncommitted `-Dslums.verify.observeAll` switch, as in tickets 07 and 08. It
    continued from the offscreen run's database.
  - 138 stashes went only to bots not stashed before. No bot got a second stash, and `StockedAt` was set for 899
    of 1,048 rows at the end.
  - Bots walked up to the shopkeeper (30-57 px) and bought there.
  - Hermit LostPanda: out of stars on screen, then recharged at Path of Time's grocer (2040051).
  - Bowman GoldEternal (level 16): bought 1,640 arrows and its potions for 23,760 of 56,380 mesos, keeping the reserve.
  - Assassin DarkOwnz (Sleepywood): in 08 it found no ammo in town and fought on in melee. Now it rode the sauna
    trip to the sauna's shop (1061002, 105040401), recharged 10,199 Subi and bought its potions.
  - At shutdown the stored stacks showed the stars being thrown again on screen: DarkOwnz 9,855, LostPanda 9,627,
    GoldEternal 1,287 arrows.
- Earlier attempts on the same setup are kept in `shops-run/prelim/`.

## Open issues

- **Few town trips are for supplies.** With the stash, most bots carry hours of supplies: a stack is 9,999 on
  MapleRoot's data. Most town trips are still the 10-20 minute grind cycle, not a shortage.
- **Mesos.** Many stored bots hold 0 mesos (avg 166k, mostly a few rich ones). Until they earn some, a broke bot
  that runs out cannot buy and falls back to melee, as 08 has it.
- **Selling to NPCs.** Ticket 12 is not done. Bots never sell, so worse stars they own (an empty Subi stack once
  they recharge Ilbis) keep their slot.
- **The shop walk.** It ends at a point beside the NPC, which may need a jump. If the bot has not arrived after
  25 s, it buys anyway once it is on the shop's map.
- **Potions bought for HP or MP alone.** The bot never buys Elixirs or percentage potions, even where they would
  be cheaper per point at high level.
