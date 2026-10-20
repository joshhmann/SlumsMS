# 08: Real potion and ammo use

**Area:** Real bots  
**Depends on:** 07  
**Status:** done 2026-10-20 on branch `supplies` (from `bots-integration`), not yet merged

## What

Bots drink potions when low and consume arrows, stars and bullets, observed and offscreen; running out changes behaviour.

## Done when

Inventory counts go down while grinding; out of pots leads to resupply or retreat.

## Result

New package `soloMapling.ArtificialPlayer.BotSupplySystem`. Everything a bot uses up leaves its inventory and its
ledger together (`BotHoldings.consumeHeld`), so a drunk potion or a thrown star is a debit the store must write.

- **HP and MP are real (`BotVitals`).** A mob's touch (`BotContactDamage`, rolled by the shared `MobContactDamage`) now
  comes off the bot's HP, never below 1: bots still do not die. A skill costs its WZ `mpCon` with the server's Element
  Amplification rule (`StatEffect.calcMPChange`), plus its `hpCon`. The bot drinks real potions from its USE tab like
  a player with auto-pot: under half HP, under 30% MP, or short of the MP for the next cast. It picks the potion that
  covers the gap with the least waste, and does not spend an Elixir on MP alone. Alchemist counts. Recovery is a
  player's 10-second tick (10 HP, 3 + INT/10 MP, plus the recovery passives). A town stop rests the bot to full.
- **Ammo is real (`BotAmmo`, `SwingSupply`).** These follow `RangedAttackHandler`'s rules:
  - The bot fires from the first USE slot that fits the weapon, is allowed at its level (crystal ilbis from 50,
    bullet tiers) and holds enough for the swing.
  - A swing fires the skill's `bulletCount` (`bulletConsume` when the skill sets one), doubled under Shadow Partner.
  - Soul Arrow needs no arrows. Shadow Stars needs a stack but throws none of it, and each cast costs its 200 stars
    for the buff's duration.
  - Stars and bullets stay in their slot at 0 to be recharged; arrows leave the slot.
  - The packet draws the projectile actually fired, not the cosmetic `ThrowingStarSelector` star. That star is now
    only what the bot would buy.
- **Ammo feeds the ticket 07 formula.** `BotDamage` no longer adds a made-up projectile. The server's totals count the
  first stack the bot carries (`Character.reapplyLocalStats`), and `BotAmmo.syncStats` re-derives them when that
  changes (a stack runs out, the first ammo is looted).
- **Running out.**
  - A cast the bot cannot pay for, even after a potion, becomes the plain attack: a plain shot if it still has ammo,
    else the weapon swung in melee.
  - With too few for the skill it makes a plain shot. With no ammo it swings its ranged weapon in melee and closes to
    melee reach (`BotAttackDriver.attackReachX`).
  - `BotSupplies.shortage` sends a grinding `TrainingBot` to town when it is under a quarter HP with no HP potion,
    short of its main attack's MP with no MP potion, or out of ammo for its ranged attack. It says a `NearDeath`,
    `OutOfMana` or `OutOfAmmo` line if anyone is watching.
- **Hook for ticket 09.**
  - Each town stop (`TrainingBot` IN_TOWN → `BotSupplies.visitTown`) rests the bot. It then offers a `SupplyNeeds`
    to `BotResupply`: the shortage, the ammo to buy, ammo held, star or bullet stacks below their recharge size
    (slot max plus Claw/Gun Mastery), HP and MP potions held, and whether the main attack uses MP.
  - Ticket 09 calls `BotResupply.install(...)`. The default buys nothing.
  - A bot whose town stop found it no ammo fights in melee until ammo turns up, instead of walking back for it every
    time.
- **Offscreen (`OffscreenSupplies`).** Each grind stretch costs what the same fight costs on screen:
  - `OffscreenCombat.Rate` now carries swings a minute, the attack swung, its route and the mob's attack. Its estimate
    swings what the bot's ammo allows.
  - Per swing: the attack's ammo and MP.
  - Mob touches: one per ten kills, measured on screen, capped by the 2.5 s invulnerability, each the average of
    the on-screen roll.
  - Recovery ticks throughout, and potions are drunk at the same thresholds. Fractions carry over between stretches.
  - When a supply runs out part way, only the affordable share is ground: `TrainingBot` applies that share to its
    EXP and loot. The bot is left where running out leaves it, so the same shortage check sends it to town.
- **Tests:** 2570 run, 0 failures (17 new in `BotSuppliesTest`). They cover potion choice, the ledger debit, the
  1 HP floor, Elixir sparing, Element Amplification, the MP fallback, recovery and rest. Ammo tests cover the
  handler's rules, stack order, stars at 0 against arrows leaving the slot, the melee fallback, Shadow Partner, Soul
  Arrow and Shadow Stars. The offscreen tests cover the swing-rate ammo bill with a partial share, the touch average
  with potion use, and the retreat at a quarter HP. The last tests cover shortages, the town stop's needs and the
  default hook. `BotDamageTest`'s claw test now expects no made-up star.
- **Headless check on 164** (`/opt/mapleroot-dev/real-stats-test`, ports 8499/8105):
  - It ran in its own scratch copy of the realstats database: the pre-travel dump, which the `travel` run had taken
    and which was still running on the shared one. That copy was dropped afterwards.
  - The even-numbered roster bots were seeded with 200 white and 200 blue potions and a stack or two of their
    weapon's ammo (`supplies-seed.sql.used`). Stored bots otherwise held no items.
  - `Server/`'s jar was never replaced.
  - Logs: `supplies-offscreen-run.log` and `supplies-observed-run.log`. The observed run used a temporary,
    uncommitted `-Dslums.verify.observeAll` switch, as in ticket 07. 0 errors from the supply code.
  - Offscreen, about 10 minutes, 33 bots: assassins threw 52 ilbis a minute (Lucky Seven, 26 swings), hermits 20,
    and Soul Arrow bowmen fired none. MP potions went at 1-3 a minute for skill users, HP potions at 0.2-5 a minute.
    16 bots left for town on a shortage: rangers and hunters out of MP, warriors at a quarter HP without potions,
    ranged bots out of ammo.
  - Observed, about 8 minutes, 31 bots: assassins used 44-81 stars a minute and a busy hermit 24-93. DarkOwnz ran
    its 500 ilbis dry in about 6 minutes, went to town, found no ammo and fought on in melee. MP potions went at 0.5-12
    a minute (a ranger spamming Arrow Bomb). Melee bots barely needed HP potions.
  - The touch rate was 0.6 a kill in melee and 0.2 at range at first. That drank 3-6 HP potions a minute offscreen
    against about 0 on screen. It is now one in ten kills, in line with the slow drift the observed melee bots showed
    (a lv 80 Dragon Knight at ~47 kills a minute lost ~130 HP a minute net of recovery).
  - Ammo and MP agree between the two runs within about 2-4x. Offscreen swings are capped by ticket 07's 30 kills a
    minute, while observed bots ran 40-50 kills a minute on easy maps.
- **Open issues:**
  - **Until ticket 09, a ranged bot without ammo barely progresses.** Stored bots hold no items, and the melee swing
    of a claw or gun does single-digit damage to a mob of its level (an out-of-ammo assassin: 2 damage a swing, 0.3
    kills a minute). Bows, crossbows and mages are hurt less: Soul Arrow, or potions. Ship 08 with 09, or with a
    starter kit.
  - Buff casts cost nothing: Soul Arrow's, Shadow Partner's and Meditation's MP are not charged. Shadow Stars' 200
    stars are. Infinity and Concentrate are not applied to MP costs: they are cooldown buffs, and taking them as
    always up would make 4th-job mages free to cast.
  - Mob touches are the only damage. Magic attacks and mob skills do not hit bots (unchanged from before), and the
    damage roll ignores the bot's defence.
  - The town stop rests the bot to full at once instead of sitting it out.
  - During the check, the `mapleroot-integration-db` container on 164 was restarted at 00:29:53 UTC by something
    outside this run (restart count 0, exit 0, not OOM). The server reconnected.
