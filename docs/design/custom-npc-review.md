# USE_ENABLE_CUSTOM_NPC_SCRIPT: per-NPC cost review

Date: 2026-10-25. Ticket 102 (item 5). Review only: the flag is **not** flipped by this change. Owner asked for it ON after
this review. `config.yaml` has it `false` on TEST and LIVE (`USE_ENABLE_CUSTOM_NPC_SCRIPT: false`, and
`USE_STARTER_MERGE: true`, `USE_ENABLE_SOLO_EXPEDITIONS: true`, `USE_CPQ: true` on both).

## What the flag controls

One global boolean, read only by NPC scripts (nothing in `src/main` besides the config field). Turning it on turns on
**all** of the scripts below at once. Each stands in 15-22 towns except where noted, so a bad one is everywhere.
`9000041` (Donation Box) has its gate commented out and already works; it is not part of this review.

Costs below come from the scripts and from TEST's database and WZ (read-only). "Faucet" means it creates items or
mesos from little; "sink" means it removes mesos or items.

## Summary and recommendation

| NPC | Script | Recommendation |
|---|---|---|
| Coco 9000017 | Chaos Scroll 60% synthesis | **On with a change**: cap the quantity per talk (100 now) |
| Agent E 9000036 | accessory crafter | **On with a change**: fix the belt/medal menu, decide the medal pool |
| Dalair 9000040 | equipment merge | **Keep off** (unbounded stat growth), or on only with `USE_STARTER_MERGE: false` and a per-item merge cap |
| Maple TV 9201088 | scroll generator | **On with a change**: limit it per day, or drop the cheap ETC inputs |
| Asia 2082014 | shop | **Keep off / remove the gate**: the shop does not exist; turning it on breaks the stock quest talk |
| T-1337 9201101 | shop | **Keep off / remove the gate**: the shop does not exist; it opens the Maple Island starter weapons |
| Spiegelmann 2042000/1/2 | ore refining (menu option 4) | **On as is** |

Because the flag is all-or-nothing, the practical order is: (1) first remove the flag test from Asia and T-1337
(restores the stock behaviour, nothing lost), (2) add a separate gate for Dalair's merge (for example a new
`USE_CUSTOM_MERGE` or a hard "off" until redesigned), (3) apply the Coco / generator / Agent E changes, (4) then flip
the flag. The flag itself is a config change on a server restart, which is the owner's go on Live.

## NPC by NPC

### Coco 9000017 (21 maps): Chaos Scroll of Goodness 60% (2049100)

- Gives: one Chaos Scroll 60% per run, 1 to 100 per talk (`sendGetNumber(..., 1, 1, 100)`), no daily or lifetime limit.
- Costs per scroll: 1,200,000 mesos plus 100 Halloween Candies (4031203), 60 Flint (4001356), 40 Coconut (4000136),
  80 Zombie's Lost Gold Tooth (4000082), 10 Maple Leaf (4001126), 8 A set of Match Cards (4080100), 200 Leather (4000021),
  120 Soft Feather (4003005).
- Drop sources on TEST: Halloween Candies only from 3 mobs at 7%; Flint from 1 mob at 7%; Gold Tooth from 1 mob at 2%
  (about 4,000 kills for the 80 teeth); Coconut from reactors; Maple Leaf is a global drop; Match Cards have no drop row.
  So one scroll is a real grind and the recipe is bottlenecked by single-mob drops. It is a net mesos sink as well.
- Risk: Chaos Scroll 60% is a high-value tradable item. The 100-per-talk cap does not matter while the drops are the
  bottleneck, but a stockpile (or an event that hands out Candies or Flint) would convert straight into scrolls.
  Match Cards having no drop row is worth a check: if players cannot get them the NPC is simply unusable, if a shop sells
  them cheaply they are not a real gate.
- Recommendation: **on with a change**. Cap at about 5 per talk (a one-line change to the `sendGetNumber` max), and add a
  daily limit if the owner wants it harder. Check where Match Cards and Coconuts come from before announcing it.

### Agent E 9000036 (22 maps): accessory crafter

- Gives: pendants (9), face accessories (10), eye accessories (5), rings (7), and a random belt or medal.
- Costs: materials plus 10,000 to 500,000 mesos each; the top item is Spiegelmann's Necklace of Chaos (1122058) from
  Spiegelmann's Necklace + 4003002 + 4000413 + 2,500,000 mesos. Rings (Lilin's, Spiegelmann's, Circle of Ancient
  Thought/Strength) cost only 10,000 to 20,000 mesos plus 1 to 2 of each ingredient, which is very cheap for rings
  with stats: check the ingredient availability before turning it on.
- Bugs: (a) the belt/medal menu builds `"#L" + i` with `i` undefined (line ~108), so the "Try it!" option is `#Lundefined#`;
  (b) the random belt/medal pool is 1132005-1132016 and 1142000-1142142 (about 190 ids) and includes medals that are not
  meant to be obtainable (event, GM and quest medals), for 15,000 mesos and 2 to 10 common feathers; (c) the face
  accessory cost array has 14 entries for 10 items (harmless).
- Risk: medium. The belt/medal roll can hand out rare cosmetic medals very cheaply; everything else is a sink.
- Recommendation: **on with a change**: fix the menu index, restrict the random pool to belts (1132005-1132016) or
  remove the roll, and look at the ring costs.

### Dalair, equipment merge 9000040 (19 maps)

- Not the same NPC as Dalair 9000066 (medal quests, placed by migration 026).
- Gives: for 50,000 mesos, destroys every upgradeable equip in the inventory from a chosen slot onward and adds
  `sqrt(sum of their stats)` (log for defence stats) to the equipped gear, capped only by `MAX_EQUIPMNT_STAT: 150000`.
  Merged gear becomes untradable.
- Requirement: `USE_STARTER_MERGE: true` means **no Maker level 3 and no level 160 requirement** (anyone may use it).
- Risk: **large**. Nothing limits how often it runs and an already merged piece can be merged into again (the merge flag
  only stops it being used as a source). Stat growth is a function of how many cheap equips you can feed in, so
  junk-gear farming or NPC-shop equips turn into permanent stats on your main gear for 50,000 mesos a run. It is also
  destructive without a confirmation of which items go (everything after the chosen slot).
- Recommendation: **keep off**. If the owner wants it, set `USE_STARTER_MERGE: false` (level and Maker gate; config only) and add a
  per-item merge count cap; both need the merge to be gated separately from this flag.

### Maple TV scroll generator (mapleTV.js, opens scroll_generator on 9201088)

- Gives: one random scroll per run. Supplies you place (Stiff Feather, Soft Feather, Flaming Feather, The Magic Rock,
  The Summoning Rock, Monster Card) and mesos add "buckets", plus free buckets from your Monster Book; 6 buckets is the
  maximum and gives the best tier (job weapon scrolls, lowest success category).
- Cost per bucket (before the Monster Book contribution): 2,800,000 mesos, or about 50 Stiff Feathers, 25 Soft Feathers,
  19 Flaming Feathers, 19 Magic Rocks or Summoning Rocks, or 100 Monster Cards. A full 6-bucket prize is about 16.8 million
  mesos or roughly 110 to 300 common ETC drops. The Magic and Summoning Rocks drop from 107 and 111 mobs.
- Risk: medium. The meso route is a sink, but the ETC route turns very common drops into weapon scrolls with no daily
  limit, which is a steady faucet of scrolls. The Monster Book bucket is free, so a maxed book makes the minimum
  one-supply-bucket prize better than its price suggests.
- Recommendation: **on with a change**: add a daily limit (for example 5 prizes a day) or remove the cheap inputs
  (Stiff, Soft, Flaming Feathers, Rocks) and keep mesos plus Monster Cards.

### Asia 2082014 and T-1337 9201101

- With the flag on, both call `cm.openShopNPC(id)`. There is **no shop row** for either id on TEST (the `shops` table has
  110 rows, none for 2082014 or 9201101), so `openShopNPC` logs "Shop ID is missing" and falls back to shop 11000, the Maple
  Island starter weapon shop (Sword 50 mesos and similar).
- Asia: turning the flag on also hides his stock quest dialogue ("follow the ship's bow to my sister", quest 3749), which
  is a regression for players on that quest.
- T-1337: with the flag off he uses `sendDefault()` (the stock talk); on, he opens junk.
- Recommendation: **keep off, and remove the flag test from both scripts** so the flag no longer touches them (stock
  behaviour). If the owner wants shops there, they need their own shop rows first.

### Spiegelmann ore refining (2042000, 2042001, 2042002; menu option 4)

- Gives: refines mineral, jewel and crystal ores in bulk (10 ores in, 1 plate/jewel/crystal out), and combines the
  seven plates into a Moon Rock (4011007) or the nine jewels into a Star Rock (4021009).
- Cost: seven times the usual fee (`feeMultiplier = 7.0`), for example 7 x 300 per plate batch, 10,000 x 7 per Moon Rock
  and 15,000 x 7 per Star Rock. It never creates anything the normal refiners do not, it only removes mesos.
- It is the only way to get Moon Rock and Star Rock at present (Arwen 1032100 is gated at level 3000 and Nimble does not
  craft them). With the flag off, option 4 is not shown.
- With `USE_CPQ: true` on both servers, the menu option is shown beside the carnival entry. (When `USE_CPQ` is false it is
  the only thing these NPCs do.)
- Risk: none seen; net mesos sink.
- Recommendation: **on as is**.

## What I checked and did not

- Read the scripts, the config, TEST's `shops`/`drop_data`/`drop_data_global`/`reactordrops`, TEST's String.wz item
  names and `Character.mergeAllItemsFromName`. Nothing was run in game and nothing was changed on TEST or LIVE.
- Item prices on the open market were not looked at, so "high-value" is judged from the item, not the Bera prices.
- I did not check whether a merged item can be merged again in game; it follows from the code (`getUpgradeableEquipped`
  does not look at the merge flag) but is untested.
