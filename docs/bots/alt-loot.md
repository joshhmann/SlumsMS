# alt-loot (1.6.7): expected outcome

Card: alt-qol-batch group D. Feature switch `PartyCompanionPolicy.Feature.LOOT` (key `loot`): owned alts ON by
default, party regulars OFF unless `-Dslums.companion.loot.regulars=true`.

Written before the code (repo rule).

## The line for the notes

> Alts now pick up only their own kills by default; type @alts loot everything to have them loot everything.

That command works exactly as written on this base: `@alts loot everything` is read by `AltsCommand` → `PartyBotOrders.read` →
`BotCommandParser`, the same order said in party chat ("loot everything") also works, and the mode is saved in
`companionsettings` so it survives a relog. A named line works too ("@alts jane loot everything").

## Why

An owned alt today picks up its own drops **and its party's** (`OwnedAltLoot.mayTake`): its owner's kills, another
alt's kills, a party regular's kills. On a shared map that means the alt hoovers the owner's loot as well, which is
not what most owners want. The owner decided (8 Nov 2026, "own-kills default OK") that an alt with no saved setting
picks up only its own kills; anybody who liked the old behaviour types one line and has it back.

There is also no way for an owner to say "sell the junk you are carrying" (junk is only ever sold automatically on a
population bot's town stop, `BotBagRoom`), and an upgrade the alt loots that would help the owner sits in the alt's
bag until the owner asks "any upgrades?".

## Expected outcome

### 1. A loot mode per companion, saved in `CompanionSettings.loot`

| mode | stored as | picks up |
| --- | --- | --- |
| everything | `everything` | every drop the loot rules allow (today's behaviour) |
| my kills | `my kills` | only drops of a mob it hit or that were credited to it, plus mesos and the quest items it needs |
| quest only | `quest only` | mesos and the quest items it needs |
| like you | `like you` | everything except the etc junk a junk-skipping bot leaves (`GrindLoot.isJunk`), plus mesos and quest items |
| nothing | `nothing` | nothing at all - not even mesos |

- **No saved setting resolves to "my kills"** for an owned alt (`CompanionSettings.DEFAULT.lootMode()` is `""`, and a
  setting put back to its default deletes the row; `""` therefore means "no choice", never "everything"). With the
  switch off for a companion nothing is resolved and the old behaviour stands.
- Chat verbs, read only when the switch is on for a companion in the party (exactly as the wave 3 settings verbs
  are): `loot everything`, `loot my kills`, `loot quest`, `loot nothing`, `loot like me`. Each saves and the
  companion answers with the mode now in force.
- The companion card (group B) carries one clickable line that flips through the modes and saves.
- Applied in the shared layer: `OwnedAltLoot.mayTake` for the alt path, and `GrindLoot.botMayTake` for party
  regulars (`CompanionLootMode.allows`), so both kinds run the same rule.

### 2. "Sell junk", only when the owner says so

Never automatic for an alt. `sell junk` makes the companion look for a shop NPC on the map it is standing on:

- no shop NPC there: it says so ("there's no shop on this map") and nothing is sold;
- one there: it walks to it and sells what the protection list allows, then reports what it sold and the mesos.

The protection list (`CompanionSellJunk.keep`) never sells:

- quest items (of its own or its owner's active quests);
- scrolls;
- potions, ammo and stars/bullets;
- cash and untradeable items;
- anything `SaleRules.whyNot` refuses;
- equips (unless the owner says `sell junk equips too`);
- with `sell junk equips too`: an equip that has been scrolled (upgrade slots used), or one that
  `GearUpgrades` scores as an upgrade for the companion or its owner.

So by default it sells only junk ETC and USE items. The sale goes through `BotBagRoom`'s sell path
(`BotBagRoom.sellAll`, the same candidate scan, the same `BotYieldCaps` meso credit and `EconomyCounters` record as
the town stop), so the mesos are exactly what today's junk sale gives and no price or rate changes.

### 3. An upgrade offer after looting

After an alt loots an equip that `GearUpgrades` scores as an upgrade for its **owner** (or a sibling alt), it says
once: "found a <item>, better than yours, want it?". One offer per `CompanionUpgradeOffer.OFFER_GAP_MS` (2 min), the
same item is never offered twice, and `offers off` silences it (`CompanionSettings.offers`). On the owner's "yes" the
item goes over in a normal trade window (`BotGiveTrade`).

### 4. A regular is unchanged with the defaults

`PartyCompanionPolicy.on(LOOT, regular)` is false unless `-Dslums.companion.loot.regulars=true`, so a party regular
loots exactly as it does today with the default switches, and the mode applies to it only with the switch on.

## Proof (each test fails before the change)

- `CompanionLootModeTest`:
  - `noSavedSettingIsMyKillsForAnAltAndEverythingWithTheSwitchOff` - `""` resolves to MY_KILLS for an owned alt and
    EVERYTHING (i.e. no narrowing) for a regular with the switch off;
  - `eachModePicksAndSkipsWithTheMesoAndQuestExemptions` - a party drop: picked in EVERYTHING, left in MY_KILLS; its
    own kill picked in MY_KILLS; a mob it never hit left; mesos and its needed quest items picked in every mode
    except NOTHING; etc junk left in LIKE_YOU;
  - `modesAreStoredAndReadBack` - the five chat words map to the five modes and back.
- `OwnedAltLootTest.theDefaultModeLeavesTheOwnersDropsOnTheFloor` / `...lootEverythingTakesThemAgain` - with no saved
  setting an alt leaves a party member's drop; with "everything" stored it takes it.
- `CompanionSellJunkTest`:
  - `theProtectionListKeepsScrollsEquipsAndQuestItemsAndSellsEtcJunk` - the sale sells a junk ETC stack, keeps a
    scroll, an equip and a quest item;
  - `sellJunkEquipsTooSellsAPlainEquipButKeepsAScrolledOneAndAnUpgrade` - the scrolled piece (upgrade slots used) and
    the piece `GearUpgrades` scores as an upgrade stay; a plain equip goes;
  - `noShopOnTheMapSaysSoAndSellsNothing`.
- `CompanionUpgradeOfferTest`:
  - `anUpgradeForTheOwnerIsOfferedOnce` - the first settle says the line, a second settle for the same item says
    nothing;
  - `theRateLimitHoldsAndTheToggleSilencesIt` - inside `OFFER_GAP_MS` a second item is not offered, after it a new
    one is, and `offers off` says nothing.
- `CompanionLootChatTest` - `@alts loot everything` parses, saves and answers; `sell junk`, `sell junk equips too` and
  `offers off` parse; a regular with the defaults is unchanged and reads them with the regulars switch on.
- `GrindLootLootModeTest` - a regular with the default switches takes a drop its mode would skip (unchanged), and
  with `-Dslums.companion.loot.regulars=true` and "nothing" stored it leaves it.

## What existing characters keep

Nothing is taken away from a character, an inventory or a saved setting. The change is a filter on which **floor
drops a companion walks to**, and one new command; no item, meso, level, stat, quest or stored setting is deleted or
rewritten.

- Inventory, mesos, quest progress, rebirth progress: untouched. The new sale only ever runs when the owner types
  `sell junk`, and even then it keeps scrolls, potions, ammo, quest items, cash and untradeable items and (by default)
  every equip - the same rule the town stop applies today. Its mesos are credited exactly as the town stop's are.
- Saved settings: no existing row is reset. `companionsettings` gains one column value inside the JSON blob
  (`lootMode`, written since wave 3 and never read until now) and one new key (`offers`, default true). An alt with no
  row keeps having no row; wave 3's "default not written" rule and `isDefault()` are unchanged.
- **The one behaviour change is deliberate and is the owner's decision**: an owned alt with no saved loot setting
  stops taking drops that were not its kill (its owner's, a sibling's, a party regular's). It is one line to undo
  (`@alts loot everything`), and it is the sentence quoted at the top of this file.

Migration: **none needed, because** nothing in an existing row changes shape: `lootMode` has been in the stored JSON
since migration 069 and parses to `""` (no choice) for every existing row, which resolves to "my kills"; `offers`
is missing from an old row and parses to its default (on). No SQL is added or changed by this branch, and no
database is touched.

## Out of scope

- Population bots' habits (`BotHabits.Loot`) are not changed; a regular only gets the new mode with
  `-Dslums.companion.loot.regulars=true`, and even then the junk habit it already has still applies first.
- No new shop protocol: the sale is the NPC counter path/bag path that already exists.
- The owner's own pickup habits are not stored anywhere in this codebase, so "like you" is read as "like a bot whose
  habit is to leave etc junk": mesos, equips, usables and quest items are taken, etc junk is left. It is stated here
  so it is not a silent invention.
- `OwnedAltCompanion`, `GCFollow`, `BotBuffEffects`, `BotPartySupport`, `PartyCompanionPolicy` and `BotBuffDriver`
  are untouched.
