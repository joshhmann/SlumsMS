# Slums Exchange item catalog (proposal)

Generated 2026-11-03 for ticket 270 from the item data in `/root/slums-nav-v70/wz` (v83), the item pool in
`src/main/java/soloMapling/itemPool/itemConfig/*.yaml` (version 1), and a read-only snapshot of live `mapleroot`:
`pricesamples` over the last 7 days and `inventoryitems` split into player rows (characterid present in
`characters`) and bot rows (characterid absent). Nothing here is behaviour; it is the list ticket 270 works from.
Companion evidence: `agent-runs/server/exchange-items-audit/REPORT.md`.

## Ticker ids

Every id is exactly 8 characters, the limit of the `varchar(8)` columns `exchange_orders.ticker`,
`exchange_fills.ticker`, `exchange_ledger.asset` and `exchange_escrow.asset` (verified in `information_schema`):

| Class | Rule | Example |
|---|---|---|
| Scroll | `S` + item id | `S2040805` |
| Use item | `U` + item id | `U2000005` |
| ETC | `E` + item id | `E4006001` |
| Already live | unchanged | `NX` `MESO` `LEAF` `CHAOS` `WS` `ELIXIR` |

## State rules used below

- **ON** — an anchor resolves today: the item pool has a price for it, or its 7-day board average is at least
  1,000. `FairValue` can quote it (`FairValue.java:158-199`).
- **NEEDS PRICE** — tradeable and someone holds it, but no pool price and no board at 1,000 or more. Today the
  ticker would be silently dropped (`FairValue.java:101-107`). It becomes ON as soon as a price exists (pool row,
  `-Dslums.exchange.<id>.anchor`, or the derived default in §5 of the ticket).
- **DARK** — tradeable, priced nowhere, held by nobody. Registered, quotes nothing, costs nothing.
- **SKIP** — `tradeBlock=1` (untradeable, cannot be listed) or a quest item.

`pool` = static anchor from the item pool; `board 7d` = live average of `pricesamples` with the sample count;
`players` = units held / characters holding.

## 1. Scrolls

`Item.wz/Consume/0204.img.xml` holds **756** scroll ids.

| state | ids | note |
|---|---|---|
| ON | 107 | 70 pool-priced, 37 board-priced |
| NEEDS PRICE | 136 | tradeable, held, no anchor |
| DARK | 304 | tradeable, unpriced, unheld |
| SKIP | 209 | `tradeBlock=1` |
| **tradeable total** | **547** | |

With the derived default anchor (ticket §5) every tradeable scroll becomes ON; without it only the first row
can quote.

### 1.1 Every tradeable scroll, by slot (547 rows)

#### Cape — 44

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2041039 | `S2041039` | Dark Scroll for Cape for DEX 30% | DEX+3 | 30% | — | 245.5k (4) | — | 6 | ON |
| 2041038 | `S2041038` | Dark Scroll for Cape for DEX 70% | DEX+2 | 70% | — | 2.61M (17) | 1/1 | 28 | ON |
| 2041031 | `S2041031` | Dark Scroll for Cape for HP 30% | HP+20 | 30% | — | — | — | — | DARK |
| 2041030 | `S2041030` | Dark Scroll for Cape for HP 70% | HP+10 | 70% | — | — | — | 2 | NEEDS PRICE |
| 2041037 | `S2041037` | Dark Scroll for Cape for INT 30% | INT+3 | 30% | — | 250k (2) | — | 3 | ON |
| 2041036 | `S2041036` | Dark Scroll for Cape for INT 70% | INT+2 | 70% | — | 283.3k (6) | — | 9 | ON |
| 2041041 | `S2041041` | Dark Scroll for Cape for LUK 30% | LUK+3 | 30% | — | 450k (2) | — | 3 | ON |
| 2041040 | `S2041040` | Dark Scroll for Cape for LUK 70% | LUK+2 | 70% | — | — | — | 2 | NEEDS PRICE |
| 2041033 | `S2041033` | Dark Scroll for Cape for MP 30% | MP+20 | 30% | — | — | — | — | DARK |
| 2041032 | `S2041032` | Dark Scroll for Cape for MP 70% | MP+10 | 70% | — | — | — | — | DARK |
| 2041027 | `S2041027` | Dark Scroll for Cape for Magic DEF 30% | DEF+3 M.DEF+5 MP+10 | 30% | — | — | — | — | DARK |
| 2041026 | `S2041026` | Dark Scroll for Cape for Magic DEF 70% | DEF+1 M.DEF+2 | 70% | — | — | — | — | DARK |
| 2041035 | `S2041035` | Dark Scroll for Cape for STR 30% | STR+3 | 30% | — | 128.7k (12) | — | — | ON |
| 2041034 | `S2041034` | Dark Scroll for Cape for STR 70% | STR+2 | 70% | — | 221.4k (10) | — | 13 | ON |
| 2041029 | `S2041029` | Dark Scroll for Cape for Weapon DEF 30% | DEF+5 M.DEF+3 HP+10 | 30% | — | — | — | — | DARK |
| 2041028 | `S2041028` | Dark Scroll for Cape for Weapon DEF 70% | DEF+3 M.DEF+1 | 70% | — | — | — | — | DARK |
| 2041020 | `S2041020` | Scroll for Cape for DEX 10% | DEX+3 | 10% | 300k | 272.9k (10) | 12/3 | 33 | ON |
| 2041018 | `S2041018` | Scroll for Cape for DEX 100% | DEX+1 | 100% | — | — | 2/2 | 1 | NEEDS PRICE |
| 2041019 | `S2041019` | Scroll for Cape for DEX 60% | DEX+2 | 60% | 1.60M | 1.49M (6) | — | 12 | ON |
| 2041008 | `S2041008` | Scroll for Cape for HP 10% | HP+20 | 10% | 100k | — | — | 18 | ON |
| 2041006 | `S2041006` | Scroll for Cape for HP 100% | HP+5 | 100% | — | — | — | 1 | NEEDS PRICE |
| 2041007 | `S2041007` | Scroll for Cape for HP 60% | HP+10 | 60% | 100k | — | 1/1 | 5 | ON |
| 2041017 | `S2041017` | Scroll for Cape for INT 10% | INT+3 | 10% | 330k | 301.6k (7) | — | 28 | ON |
| 2041015 | `S2041015` | Scroll for Cape for INT 100% | INT+1 | 100% | — | — | 12/2 | — | NEEDS PRICE |
| 2041016 | `S2041016` | Scroll for Cape for INT 60% | INT+2 | 60% | 1.60M | 1.35M (5) | 5/2 | 26 | ON |
| 2041023 | `S2041023` | Scroll for Cape for LUK 10% | LUK+3 | 10% | 350k | 331.1k (2) | 1/1 | 50 | ON |
| 2041021 | `S2041021` | Scroll for Cape for LUK 100% | LUK+1 | 100% | — | — | 3/3 | 1 | NEEDS PRICE |
| 2041022 | `S2041022` | Scroll for Cape for LUK 60% | LUK+2 | 60% | 1.65M | 1.52M (12) | 9/1 | 235 | ON |
| 2041011 | `S2041011` | Scroll for Cape for MP 10% | MP+20 | 10% | — | — | — | 22 | NEEDS PRICE |
| 2041009 | `S2041009` | Scroll for Cape for MP 100% | MP+5 | 100% | — | — | 3/2 | 9 | NEEDS PRICE |
| 2041010 | `S2041010` | Scroll for Cape for MP 60% | MP+10 | 60% | — | — | — | 8 | NEEDS PRICE |
| 2041002 | `S2041002` | Scroll for Cape for Magic DEF 10% | DEF+3 M.DEF+5 MP+10 | 10% | — | — | — | 21 | NEEDS PRICE |
| 2041000 | `S2041000` | Scroll for Cape for Magic DEF 100% | M.DEF+1 | 100% | — | — | — | — | DARK |
| 2041066 | `S2041066` | Scroll for Cape for Magic DEF 100% | DEF+2 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2041068 | `S2041068` | Scroll for Cape for Magic Def. 50% | DEF+4 M.DEF+5 | 50% | — | — | — | — | DARK |
| 2041001 | `S2041001` | Scroll for Cape for Magic Def. 60% | DEF+1 M.DEF+3 | 60% | — | — | — | 4 | NEEDS PRICE |
| 2041014 | `S2041014` | Scroll for Cape for STR 10% | STR+3 | 10% | 200k | 186.6k (38) | — | 54 | ON |
| 2041012 | `S2041012` | Scroll for Cape for STR 100% | STR+1 | 100% | — | — | 1/1 | — | NEEDS PRICE |
| 2041013 | `S2041013` | Scroll for Cape for STR 60% | STR+2 | 60% | 1.50M | 1.31M (38) | 5/2 | 100 | ON |
| 2041005 | `S2041005` | Scroll for Cape for Weapon DEF 10% | DEF+5 M.DEF+3 HP+10 | 10% | — | — | — | 8 | NEEDS PRICE |
| 2041003 | `S2041003` | Scroll for Cape for Weapon DEF 100% | DEF+1 | 100% | — | — | — | — | DARK |
| 2041067 | `S2041067` | Scroll for Cape for Weapon DEF 100% | DEF+3 M.DEF+2 | 100% | — | — | — | — | DARK |
| 2041004 | `S2041004` | Scroll for Cape for Weapon DEF 60% | DEF+3 M.DEF+1 | 60% | — | — | 23/3 | 2 | NEEDS PRICE |
| 2041069 | `S2041069` | Scroll for Cape for Weapon Def. 50% | DEF+5 M.DEF+4 | 50% | — | — | — | — | DARK |

#### Shield — 31

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040905 | `S2040905` | Dark Scroll for Shield for DEF 30% | DEF+5 M.DEF+3 HP+10 | 30% | — | — | — | — | DARK |
| 2040904 | `S2040904` | Dark Scroll for Shield for DEF 70% | DEF+2 M.DEF+1 | 70% | — | — | — | 3 | NEEDS PRICE |
| 2040909 | `S2040909` | Dark Scroll for Shield for HP 30% | HP+30 | 30% | — | — | — | — | DARK |
| 2040908 | `S2040908` | Dark Scroll for Shield for HP 70% | HP+15 | 70% | — | — | — | 2 | NEEDS PRICE |
| 2040907 | `S2040907` | Dark Scroll for Shield for LUK 30% | LUK+3 | 30% | — | 819.3k (12) | — | 35 | ON |
| 2040906 | `S2040906` | Dark Scroll for Shield for LUK 70% | LUK+2 | 70% | — | 1.24M (15) | — | 19 | ON |
| 2040922 | `S2040922` | Dark Scroll for Shield for Magic ATT 30% | M.ATT+3 INT+2 | 30% | — | 4.64M (5) | — | 5 | ON |
| 2040921 | `S2040921` | Dark Scroll for Shield for Magic ATT 70% | M.ATT+2 INT+1 | 70% | — | 2.82M (10) | — | 13 | ON |
| 2040917 | `S2040917` | Dark Scroll for Shield for Weapon ATT 30% | ATT+3 STR+2 | 30% | — | 3.45M (30) | — | 15 | ON |
| 2040916 | `S2040916` | Dark Scroll for Shield for Weapon ATT 70% | ATT+2 STR+1 | 70% | — | 5.39M (7) | — | 8 | ON |
| 2040902 | `S2040902` | Scroll for Shield for DEF 10% | DEF+5 M.DEF+3 HP+10 | 10% | 100k | — | 27/6 | 3 | ON |
| 2040900 | `S2040900` | Scroll for Shield for DEF 100% | DEF+1 | 100% | — | — | — | — | DARK |
| 2040936 | `S2040936` | Scroll for Shield for DEF 100% | DEF+2 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2040943 | `S2040943` | Scroll for Shield for DEF 50% | DEF+5 M.DEF+4 | 50% | — | — | — | — | DARK |
| 2040901 | `S2040901` | Scroll for Shield for DEF 60% | DEF+2 M.DEF+1 | 60% | 100k | — | — | 52 | ON |
| 2040928 | `S2040928` | Scroll for Shield for HP 10% | HP+30 | 10% | — | — | — | 50 | NEEDS PRICE |
| 2040926 | `S2040926` | Scroll for Shield for HP 100% | HP+5 | 100% | — | — | — | — | DARK |
| 2040927 | `S2040927` | Scroll for Shield for HP 60% | HP+15 | 60% | — | — | — | — | DARK |
| 2040925 | `S2040925` | Scroll for Shield for LUK 10% | LUK+3 | 10% | — | — | 3/2 | 13 | NEEDS PRICE |
| 2040923 | `S2040923` | Scroll for Shield for LUK 100% | LUK+1 | 100% | — | — | — | 3,576 | NEEDS PRICE |
| 2040924 | `S2040924` | Scroll for Shield for LUK 60% | LUK+2 | 60% | — | — | 5/2 | — | NEEDS PRICE |
| 2040920 | `S2040920` | Scroll for Shield for Magic ATT 10% | M.ATT+3 INT+2 | 10% | — | — | — | — | DARK |
| 2040918 | `S2040918` | Scroll for Shield for Magic ATT 100% | M.ATT+1 | 100% | — | — | — | — | DARK |
| 2040919 | `S2040919` | Scroll for Shield for Magic ATT 60% | M.ATT+2 INT+1 | 60% | — | — | — | — | DARK |
| 2040933 | `S2040933` | Scroll for Shield for STR 10% | STR+3 | 10% | — | — | — | 2 | NEEDS PRICE |
| 2040929 | `S2040929` | Scroll for Shield for STR 100% | STR+1 | 100% | — | — | — | — | DARK |
| 2040932 | `S2040932` | Scroll for Shield for STR 30% | STR+3 | 30% | — | — | — | — | DARK |
| 2040931 | `S2040931` | Scroll for Shield for STR 60% | STR+2 | 60% | — | — | — | — | DARK |
| 2040930 | `S2040930` | Scroll for Shield for STR 70% | STR+2 | 70% | — | — | 5/2 | — | NEEDS PRICE |
| 2040915 | `S2040915` | Scroll for Shield for Weapon ATT 10% | ATT+3 STR+2 | 10% | — | — | — | — | DARK |
| 2040914 | `S2040914` | Scroll for Shield for Weapon ATT 60% | ATT+2 STR+1 | 60% | — | — | — | — | DARK |

#### Special — 31

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040603 | `S2040603` | GM Scroll for Bottomwear for DEF 100% | DEF+5 M.DEF+3 HP+10 | 100% | — | — | — | — | DARK |
| 2044503 | `S2044503` | GM Scroll for Bow for ATT 100% | ATT+5 DEX+1 | 100% | — | — | — | — | DARK |
| 2041024 | `S2041024` | GM Scroll for Cape for Magic DEF 100% | DEF+3 M.DEF+5 MP+10 | 100% | — | — | — | — | DARK |
| 2041025 | `S2041025` | GM Scroll for Cape for Weapon DEF 100% | DEF+5 M.DEF+3 HP+10 | 100% | — | — | — | — | DARK |
| 2044703 | `S2044703` | GM Scroll for Claw for ATT 100% | ATT+5 LUK+1 | 100% | — | — | — | — | DARK |
| 2044603 | `S2044603` | GM Scroll for Crossbow for ATT 100% | ATT+5 DEX+1 | 100% | — | — | — | — | DARK |
| 2043303 | `S2043303` | GM Scroll for Dagger for ATT 100% | ATT+5 LUK+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2040303 | `S2040303` | GM Scroll for Earring for INT 100% | M.ATT+5 INT+3 M.DEF+1 | 100% | — | — | — | — | DARK |
| 2040807 | `S2040807` | GM Scroll for Gloves for ATT 100% | ATT+3 | 100% | — | — | — | — | DARK |
| 2040806 | `S2040806` | GM Scroll for Gloves for DEX 100% | DEX+3 | 100% | — | — | — | — | DARK |
| 2044911 | `S2044911` | GM Scroll for Gun for ATT 100% | ATT+5 | 100% | — | — | — | — | DARK |
| 2040007 | `S2040007` | GM Scroll for Helmet for HP 100% | HP+30 | 100% | — | — | — | — | DARK |
| 2044818 | `S2044818` | GM Scroll for Knuckle for ATT 100% | ATT+5 | 100% | — | — | — | — | DARK |
| 2043103 | `S2043103` | GM Scroll for One-Handed Axe for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2043203 | `S2043203` | GM Scroll for One-Handed BW for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2043003 | `S2043003` | GM Scroll for One-Handed Sword for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2040506 | `S2040506` | GM Scroll for Overall Armor for DEX 100% | DEX+5 Speed+1 | 100% | — | — | — | — | DARK |
| 2044403 | `S2044403` | GM Scroll for Pole Arm for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2040903 | `S2040903` | GM Scroll for Shield for DEF 100% | DEF+5 M.DEF+3 HP+10 | 100% | — | — | — | — | DARK |
| 2040709 | `S2040709` | GM Scroll for Shoes for DEX 100% | Speed+1 | 100% | — | — | — | — | DARK |
| 2040710 | `S2040710` | GM Scroll for Shoes for Jump 100% | DEX+3 Speed+1 Jump+5 | 100% | — | — | — | — | DARK |
| 2040711 | `S2040711` | GM Scroll for Shoes for Speed 100% | Speed+3 | 100% | — | — | — | — | DARK |
| 2044303 | `S2044303` | GM Scroll for Spear for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2043803 | `S2043803` | GM Scroll for Staff for Magic ATT 100% | M.ATT+5 INT+3 M.DEF+1 | 100% | — | — | — | — | DARK |
| 2040403 | `S2040403` | GM Scroll for Topwear for DEF 100% | DEF+5 M.DEF+3 HP+10 | 100% | — | — | — | — | DARK |
| 2044103 | `S2044103` | GM Scroll for Two-handed Axe for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2044203 | `S2044203` | GM Scroll for Two-handed BW for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2044003 | `S2044003` | GM Scroll for Two-handed Sword for ATT 100% | ATT+5 STR+3 DEF+1 | 100% | — | — | — | — | DARK |
| 2043703 | `S2043703` | GM Scroll for Wand for Magic ATT 100% | M.ATT+5 INT+3 M.DEF+1 | 100% | — | — | — | — | DARK |
| 2049113 | `S2049113` | Normal Witch Scroll | — | — | — | — | — | — | DARK |
| 2049114 | `S2049114` | Witch's Belt Scroll | — | — | — | — | — | — | DARK |

#### Helmet — 30

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040015 | `S2040015` | Dark Scroll for Helmet for Accuracy 30% | DEX+2 | 30% | — | — | — | — | DARK |
| 2040014 | `S2040014` | Dark Scroll for Helmet for Accuracy 70% | DEX+1 | 70% | — | — | — | 5 | NEEDS PRICE |
| 2040009 | `S2040009` | Dark Scroll for Helmet for DEF 30% | DEF+5 M.DEF+3 | 30% | — | — | — | — | DARK |
| 2040008 | `S2040008` | Dark Scroll for Helmet for DEF 70% | DEF+2 M.DEF+1 | 70% | — | — | — | — | DARK |
| 2040030 | `S2040030` | Dark Scroll for Helmet for DEX 30% | DEX+3 | 30% | — | — | — | 3 | NEEDS PRICE |
| 2040028 | `S2040028` | Dark Scroll for Helmet for DEX 70% | DEX+2 | 70% | — | — | — | — | DARK |
| 2040011 | `S2040011` | Dark Scroll for Helmet for HP 30% | HP+30 | 30% | — | — | — | — | DARK |
| 2040010 | `S2040010` | Dark Scroll for Helmet for HP 70% | HP+10 | 70% | — | — | — | — | DARK |
| 2040013 | `S2040013` | Dark Scroll for Helmet for INT 30% | INT+3 | 30% | — | 261.4k (52) | — | 31 | ON |
| 2040012 | `S2040012` | Dark Scroll for Helmet for INT 70% | INT+2 | 70% | — | 933.8k (39) | — | 75 | ON |
| 2040016 | `S2040016` | Scroll for Helmet for Accuracy 10% | DEX+2 | 10% | — | — | — | — | DARK |
| 2040018 | `S2040018` | Scroll for Helmet for Accuracy 100% | — | 100% | — | — | — | — | DARK |
| 2040017 | `S2040017` | Scroll for Helmet for Accuracy 60% | DEX+1 | 60% | — | — | — | — | DARK |
| 2040002 | `S2040002` | Scroll for Helmet for DEF 10% | DEF+5 M.DEF+3 | 10% | — | — | — | 14 | NEEDS PRICE |
| 2040000 | `S2040000` | Scroll for Helmet for DEF 100% | DEF+1 | 100% | — | — | — | — | DARK |
| 2040006 | `S2040006` | Scroll for Helmet for DEF 100% | DEF+5 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2040041 | `S2040041` | Scroll for Helmet for DEF 100% | DEF+2 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2040045 | `S2040045` | Scroll for Helmet for DEF 50% | DEF+5 M.DEF+4 | 50% | — | — | — | — | DARK |
| 2040001 | `S2040001` | Scroll for Helmet for DEF 60% | DEF+2 M.DEF+1 | 60% | — | — | 1/1 | — | NEEDS PRICE |
| 2040031 | `S2040031` | Scroll for Helmet for DEX 10% | DEX+3 | 10% | — | — | — | 1 | NEEDS PRICE |
| 2040027 | `S2040027` | Scroll for Helmet for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2040029 | `S2040029` | Scroll for Helmet for DEX 60% | DEX+2 | 60% | — | — | 2/2 | — | NEEDS PRICE |
| 2040005 | `S2040005` | Scroll for Helmet for HP 10% | HP+30 | 10% | 100k | — | — | 3 | ON |
| 2040003 | `S2040003` | Scroll for Helmet for HP 100% | HP+5 | 100% | — | — | — | — | DARK |
| 2040042 | `S2040042` | Scroll for Helmet for HP 100% | HP+15 | 100% | — | — | — | — | DARK |
| 2040046 | `S2040046` | Scroll for Helmet for HP 50% | HP+35 | 50% | — | — | — | — | DARK |
| 2040004 | `S2040004` | Scroll for Helmet for HP 60% | HP+10 | 60% | 100k | — | 2/2 | — | ON |
| 2040026 | `S2040026` | Scroll for Helmet for INT 10% | INT+3 | 10% | — | — | 2/1 | 7 | NEEDS PRICE |
| 2040024 | `S2040024` | Scroll for Helmet for INT 100% | INT+1 | 100% | — | — | 2/1 | — | NEEDS PRICE |
| 2040025 | `S2040025` | Scroll for Helmet for INT 60% | INT+2 | 60% | — | — | 3/2 | 312 | NEEDS PRICE |

#### Overall Armor — 30

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040511 | `S2040511` | Dark Scroll for Overall Armor for DEF 30% | DEF+5 M.DEF+3 HP+10 | 30% | — | — | — | — | DARK |
| 2040510 | `S2040510` | Dark Scroll for Overall Armor for DEF 70% | DEF+2 M.DEF+2 | 70% | — | — | — | — | DARK |
| 2040509 | `S2040509` | Dark Scroll for Overall Armor for DEX 30% | DEX+5 Speed+1 | 30% | — | 2.33M (7) | — | 12 | ON |
| 2040508 | `S2040508` | Dark Scroll for Overall Armor for DEX 70% | DEX+2 | 70% | — | 570.8k (35) | — | 51 | ON |
| 2040519 | `S2040519` | Dark Scroll for Overall Armor for INT 30% | INT+5 M.DEF+3 MP+10 | 30% | — | 2.23M (7) | — | 10 | ON |
| 2040518 | `S2040518` | Dark Scroll for Overall Armor for INT 70% | INT+2 M.DEF+1 | 70% | — | 1.15M (6) | — | 12 | ON |
| 2040521 | `S2040521` | Dark Scroll for Overall Armor for LUK 30% | LUK+5 | 30% | — | 1.92M (18) | — | 21 | ON |
| 2040520 | `S2040520` | Dark Scroll for Overall Armor for LUK 70% | LUK+2 | 70% | — | 1M (1) | — | 2 | ON |
| 2040505 | `S2040505` | Scroll for Overall Armor for DEF 10% | DEF+5 M.DEF+3 HP+10 | 10% | — | — | — | 69 | NEEDS PRICE |
| 2040503 | `S2040503` | Scroll for Overall Armor for DEF 100% | DEF+1 | 100% | — | — | — | — | DARK |
| 2040539 | `S2040539` | Scroll for Overall Armor for DEF 100% | DEF+2 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2040507 | `S2040507` | Scroll for Overall Armor for DEF 30% | DEF+5 M.DEF+3 HP+10 | 30% | — | — | — | — | DARK |
| 2040543 | `S2040543` | Scroll for Overall Armor for DEF 50% | DEF+5 M.DEF+4 | 50% | — | — | — | — | DARK |
| 2040504 | `S2040504` | Scroll for Overall Armor for DEF 60% | DEF+2 M.DEF+1 | 60% | — | — | 3/2 | — | NEEDS PRICE |
| 2040502 | `S2040502` | Scroll for Overall Armor for DEX 10% | DEX+5 Speed+1 | 10% | 350k | 311.3k (40) | 5/3 | 58 | ON |
| 2040500 | `S2040500` | Scroll for Overall Armor for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2040538 | `S2040538` | Scroll for Overall Armor for DEX 100% | DEX+2 | 100% | — | — | — | — | DARK |
| 2040542 | `S2040542` | Scroll for Overall Armor for DEX 50% | DEX+5 Speed+1 | 50% | — | — | — | — | DARK |
| 2040501 | `S2040501` | Scroll for Overall Armor for DEX 60% | DEX+2 | 60% | 900k | 838.3k (27) | 7/5 | 27 | ON |
| 2040514 | `S2040514` | Scroll for Overall Armor for INT 10% | INT+5 M.DEF+3 MP+10 | 10% | 1M | 934.6k (24) | — | 31 | ON |
| 2040512 | `S2040512` | Scroll for Overall Armor for INT 100% | INT+1 | 100% | — | — | — | — | DARK |
| 2040513 | `S2040513` | Scroll for Overall Armor for INT 60% | INT+2 M.DEF+1 | 60% | 3M | 3.04M (8) | — | 16 | ON |
| 2040517 | `S2040517` | Scroll for Overall Armor for LUK 10% | LUK+5 | 10% | — | — | — | — | DARK |
| 2040515 | `S2040515` | Scroll for Overall Armor for LUK 100% | LUK+1 | 100% | — | — | — | — | DARK |
| 2040516 | `S2040516` | Scroll for Overall Armor for LUK 60% | LUK+2 | 60% | — | — | 2/2 | 1 | NEEDS PRICE |
| 2040534 | `S2040534` | Scroll for Overall Armor for STR 10% | STR+5 DEF+3 HP+5 | 10% | — | — | 12/4 | — | NEEDS PRICE |
| 2040530 | `S2040530` | Scroll for Overall Armor for STR 100% | STR+1 | 100% | — | — | 9/4 | — | NEEDS PRICE |
| 2040533 | `S2040533` | Scroll for Overall Armor for STR 30% | STR+5 DEF+3 HP+5 | 30% | — | — | — | — | DARK |
| 2040532 | `S2040532` | Scroll for Overall Armor for STR 60% | STR+2 DEF+1 | 60% | — | — | 1/1 | — | NEEDS PRICE |
| 2040531 | `S2040531` | Scroll for Overall Armor for STR 70% | STR+2 DEF+1 | 70% | — | — | — | — | DARK |

#### Bottomwear — 27

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040605 | `S2040605` | Dark Scroll for Bottomwear for DEF 30% | DEF+5 M.DEF+3 HP+10 | 30% | — | — | — | — | DARK |
| 2040604 | `S2040604` | Dark Scroll for Bottomwear for DEF 70% | DEF+2 M.DEF+1 | 70% | — | — | — | 1 | NEEDS PRICE |
| 2040611 | `S2040611` | Dark Scroll for Bottomwear for DEX 30% | DEX+3 Speed+3 | 30% | — | 3.21M (26) | — | 15 | ON |
| 2040610 | `S2040610` | Dark Scroll for Bottomwear for DEX 70% | DEX+2 Speed+1 | 70% | — | 2.22M (20) | — | 29 | ON |
| 2040609 | `S2040609` | Dark Scroll for Bottomwear for HP 30% | HP+30 | 30% | — | — | — | — | DARK |
| 2040608 | `S2040608` | Dark Scroll for Bottomwear for HP 70% | HP+15 | 70% | — | — | — | — | DARK |
| 2040607 | `S2040607` | Dark Scroll for Bottomwear for Jump 30% | Jump+4 | 30% | — | — | — | — | DARK |
| 2040606 | `S2040606` | Dark Scroll for Bottomwear for Jump 70% | Jump+2 | 70% | — | — | — | — | DARK |
| 2040602 | `S2040602` | Scroll for Bottomwear for DEF 10% | DEF+5 M.DEF+3 HP+10 | 10% | 100k | — | 72/1 | 39,298 | ON |
| 2040600 | `S2040600` | Scroll for Bottomwear for DEF 100% | DEF+1 | 100% | — | — | — | 5,449 | NEEDS PRICE |
| 2040630 | `S2040630` | Scroll for Bottomwear for DEF 100% | DEF+2 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2040629 | `S2040629` | Scroll for Bottomwear for DEF 50% | DEF+5 M.DEF+4 | 50% | — | — | — | — | DARK |
| 2040601 | `S2040601` | Scroll for Bottomwear for DEF 60% | DEF+2 M.DEF+1 | 60% | 100k | — | — | 701 | ON |
| 2040612 | `S2040612` | Scroll for Bottomwear for DEX 10% | DEX+3 Speed+3 | 10% | — | — | — | — | DARK |
| 2040627 | `S2040627` | Scroll for Bottomwear for DEX 10% | DEX+3 Speed+1 | 10% | — | — | 10/4 | — | NEEDS PRICE |
| 2040614 | `S2040614` | Scroll for Bottomwear for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2040623 | `S2040623` | Scroll for Bottomwear for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2040626 | `S2040626` | Scroll for Bottomwear for DEX 30% | DEX+3 Speed+1 | 30% | — | — | — | — | DARK |
| 2040613 | `S2040613` | Scroll for Bottomwear for DEX 60% | DEX+2 Speed+1 | 60% | — | — | — | — | DARK |
| 2040625 | `S2040625` | Scroll for Bottomwear for DEX 60% | DEX+2 | 60% | — | — | — | — | DARK |
| 2040624 | `S2040624` | Scroll for Bottomwear for DEX 70% | DEX+2 | 70% | — | — | — | 4 | NEEDS PRICE |
| 2040622 | `S2040622` | Scroll for Bottomwear for HP 10% | HP+30 | 10% | — | — | — | — | DARK |
| 2040620 | `S2040620` | Scroll for Bottomwear for HP 100% | HP+5 | 100% | — | — | — | — | DARK |
| 2040621 | `S2040621` | Scroll for Bottomwear for HP 60% | HP+15 | 60% | — | — | 1/1 | — | NEEDS PRICE |
| 2040619 | `S2040619` | Scroll for Bottomwear for Jump 10% | Jump+4 | 10% | — | — | — | — | DARK |
| 2040617 | `S2040617` | Scroll for Bottomwear for Jump 100% | Jump+1 | 100% | — | — | — | — | DARK |
| 2040618 | `S2040618` | Scroll for Bottomwear for Jump 60% | Jump+2 | 60% | — | — | 2/1 | — | NEEDS PRICE |

#### Topwear — 27

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040405 | `S2040405` | Dark Scroll for Topwear for DEF 30% | DEF+5 M.DEF+3 HP+10 | 30% | — | — | — | — | DARK |
| 2040404 | `S2040404` | Dark Scroll for Topwear for DEF 70% | DEF+2 M.DEF+1 | 70% | — | — | — | 16 | NEEDS PRICE |
| 2040409 | `S2040409` | Dark Scroll for Topwear for HP 30% | HP+30 | 30% | — | — | 4/2 | — | NEEDS PRICE |
| 2040408 | `S2040408` | Dark Scroll for Topwear for HP 70% | HP+15 | 70% | — | — | — | 3 | NEEDS PRICE |
| 2040411 | `S2040411` | Dark Scroll for Topwear for LUK 30% | LUK+3 | 30% | — | 1.32M (36) | — | 12 | ON |
| 2040426 | `S2040426` | Dark Scroll for Topwear for LUK 30% | LUK+3 | 30% | — | — | — | — | DARK |
| 2040410 | `S2040410` | Dark Scroll for Topwear for LUK 70% | LUK+2 | 70% | — | 1.54M (23) | — | 27 | ON |
| 2040424 | `S2040424` | Dark Scroll for Topwear for LUK 70% | LUK+2 | 70% | — | — | — | 9 | NEEDS PRICE |
| 2040407 | `S2040407` | Dark Scroll for Topwear for STR 30% | STR+3 | 30% | — | 252.7k (26) | — | 57 | ON |
| 2040406 | `S2040406` | Dark Scroll for Topwear for STR 70% | STR+2 | 70% | — | 590.4k (27) | — | 42 | ON |
| 2040402 | `S2040402` | Scroll for Topwear for DEF 10% | DEF+5 M.DEF+3 HP+10 | 10% | 100k | — | — | 22 | ON |
| 2040400 | `S2040400` | Scroll for Topwear for DEF 100% | DEF+1 | 100% | — | — | — | — | DARK |
| 2040430 | `S2040430` | Scroll for Topwear for DEF 100% | DEF+2 M.DEF+3 | 100% | — | — | — | — | DARK |
| 2040429 | `S2040429` | Scroll for Topwear for DEF 50% | DEF+5 M.DEF+4 | 50% | — | — | — | — | DARK |
| 2040401 | `S2040401` | Scroll for Topwear for DEF 60% | DEF+2 M.DEF+1 | 60% | 100k | — | 4/2 | 76 | ON |
| 2040422 | `S2040422` | Scroll for Topwear for HP 10% | HP+30 | 10% | — | — | 2/2 | 1 | NEEDS PRICE |
| 2040420 | `S2040420` | Scroll for Topwear for HP 100% | HP+5 | 100% | — | — | — | — | DARK |
| 2040421 | `S2040421` | Scroll for Topwear for HP 60% | HP+15 | 60% | — | — | 2/1 | — | NEEDS PRICE |
| 2040412 | `S2040412` | Scroll for Topwear for LUK 10% | LUK+3 | 10% | — | — | 4/2 | — | NEEDS PRICE |
| 2040427 | `S2040427` | Scroll for Topwear for LUK 10% | LUK+3 | 10% | — | — | 1/1 | 1 | NEEDS PRICE |
| 2040414 | `S2040414` | Scroll for Topwear for LUK 100% | LUK+1 | 100% | — | — | — | — | DARK |
| 2040423 | `S2040423` | Scroll for Topwear for LUK 100% | LUK+1 | 100% | — | — | — | — | DARK |
| 2040413 | `S2040413` | Scroll for Topwear for LUK 60% | LUK+2 | 60% | — | — | — | — | DARK |
| 2040425 | `S2040425` | Scroll for Topwear for LUK 60% | LUK+2 | 60% | — | — | 1/1 | — | NEEDS PRICE |
| 2040419 | `S2040419` | Scroll for Topwear for STR 10% | STR+3 | 10% | — | — | 4/1 | — | NEEDS PRICE |
| 2040417 | `S2040417` | Scroll for Topwear for STR 100% | STR+1 | 100% | — | — | 14/5 | — | NEEDS PRICE |
| 2040418 | `S2040418` | Scroll for Topwear for STR 60% | STR+2 | 60% | — | — | 1/1 | 1 | NEEDS PRICE |

#### Earring — 25

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040307 | `S2040307` | Dark Scroll for Earring for DEX 30% | DEX+3 | 30% | — | 4.19M (23) | — | 26 | ON |
| 2040306 | `S2040306` | Dark Scroll for Earring for DEX 70% | DEX+2 | 70% | — | 5.04M (3) | 1/1 | 3 | ON |
| 2040305 | `S2040305` | Dark Scroll for Earring for INT 30% | M.ATT+5 INT+3 M.DEF+1 | 30% | — | 4.06M (11) | — | 3 | ON |
| 2040304 | `S2040304` | Dark Scroll for Earring for INT 70% | M.ATT+2 INT+1 | 70% | — | 951.7k (4) | — | 9 | ON |
| 2040310 | `S2040310` | Scroll for Earring for DEF 10% | DEF+3 M.DEF+3 | 10% | — | — | — | 1 | NEEDS PRICE |
| 2040312 | `S2040312` | Scroll for Earring for DEF 100% | DEF+1 | 100% | — | — | — | — | DARK |
| 2040311 | `S2040311` | Scroll for Earring for DEF 60% | DEF+1 M.DEF+1 | 60% | — | — | — | — | DARK |
| 2040318 | `S2040318` | Scroll for Earring for DEX 10% | DEX+3 | 10% | — | — | 2/1 | 1,372 | NEEDS PRICE |
| 2040316 | `S2040316` | Scroll for Earring for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2040317 | `S2040317` | Scroll for Earring for DEX 60% | DEX+2 | 60% | — | — | — | — | DARK |
| 2040328 | `S2040328` | Scroll for Earring for HP 10% | HP+30 | 10% | — | — | — | 1 | NEEDS PRICE |
| 2040324 | `S2040324` | Scroll for Earring for HP 100% | HP+5 | 100% | — | — | 1/1 | 1 | NEEDS PRICE |
| 2040327 | `S2040327` | Scroll for Earring for HP 30% | HP+30 | 30% | — | — | — | — | DARK |
| 2040326 | `S2040326` | Scroll for Earring for HP 60% | HP+15 | 60% | — | — | 7/3 | — | NEEDS PRICE |
| 2040325 | `S2040325` | Scroll for Earring for HP 70% | HP+15 | 70% | — | — | — | — | DARK |
| 2040302 | `S2040302` | Scroll for Earring for INT 10% | M.ATT+5 INT+3 M.DEF+1 | 10% | 1.30M | 1.12M (63) | 1/1 | 117 | ON |
| 2040300 | `S2040300` | Scroll for Earring for INT 100% | M.ATT+1 | 100% | — | — | — | — | DARK |
| 2040334 | `S2040334` | Scroll for Earring for INT 100% | M.ATT+2 INT+2 | 100% | — | — | — | — | DARK |
| 2040333 | `S2040333` | Scroll for Earring for INT 50% | M.ATT+5 INT+3 M.DEF+2 | 50% | — | — | — | — | DARK |
| 2040301 | `S2040301` | Scroll for Earring for INT 60% | M.ATT+2 INT+1 | 60% | 2.50M | 1.54M (59) | 4/2 | 106 | ON |
| 2040323 | `S2040323` | Scroll for Earring for LUK 10% | LUK+3 | 10% | — | — | 1/1 | — | NEEDS PRICE |
| 2040319 | `S2040319` | Scroll for Earring for LUK 100% | LUK+1 | 100% | — | — | — | 3 | NEEDS PRICE |
| 2040322 | `S2040322` | Scroll for Earring for LUK 30% | LUK+3 | 30% | — | — | — | 2 | NEEDS PRICE |
| 2040321 | `S2040321` | Scroll for Earring for LUK 60% | LUK+2 | 60% | — | — | 1/1 | — | NEEDS PRICE |
| 2040320 | `S2040320` | Scroll for Earring for LUK 70% | LUK+2 | 70% | — | — | 1/1 | 918 | NEEDS PRICE |

#### Gloves — 24

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040811 | `S2040811` | Dark Scroll for Gloves for ATT 30% | ATT+3 | 30% | — | 1.38M (18) | — | 28 | ON |
| 2040810 | `S2040810` | Dark Scroll for Gloves for ATT 70% | ATT+2 | 70% | — | 1.79M (8) | — | 11 | ON |
| 2040809 | `S2040809` | Dark Scroll for Gloves for DEX 30% | DEX+3 | 30% | — | — | — | 1 | NEEDS PRICE |
| 2040808 | `S2040808` | Dark Scroll for Gloves for DEX 70% | DEX+1 | 70% | — | — | — | — | DARK |
| 2040813 | `S2040813` | Dark Scroll for Gloves for HP 30% | HP+30 | 30% | — | — | — | — | DARK |
| 2040812 | `S2040812` | Dark Scroll for Gloves for HP 70% | HP+15 | 70% | — | — | — | — | DARK |
| 2040815 | `S2040815` | Dark Scroll for Gloves for Magic ATT 30% | M.ATT+3 INT+3 M.DEF+1 | 30% | — | 2.10M (31) | — | 3 | ON |
| 2040814 | `S2040814` | Dark Scroll for Gloves for Magic ATT 70% | M.ATT+1 INT+1 | 70% | — | 1.64M (9) | — | 11 | ON |
| 2040805 | `S2040805` | Scroll for Gloves for ATT 10% | ATT+3 | 10% | 1.20M | 1.19M (95) | 125/4 | 68 | ON |
| 2040803 | `S2040803` | Scroll for Gloves for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2040830 | `S2040830` | Scroll for Gloves for ATT 100% | ATT+2 | 100% | — | — | — | — | DARK |
| 2040834 | `S2040834` | Scroll for Gloves for ATT 50% | ATT+3 | 50% | — | — | — | — | DARK |
| 2040804 | `S2040804` | Scroll for Gloves for ATT 60% | ATT+2 | 60% | 3M | 2.78M (55) | 5/3 | 48 | ON |
| 2040802 | `S2040802` | Scroll for Gloves for DEX 10% | DEX+3 | 10% | 100k | — | — | 1 | ON |
| 2040800 | `S2040800` | Scroll for Gloves for DEX 100% | — | 100% | — | — | — | — | DARK |
| 2040829 | `S2040829` | Scroll for Gloves for DEX 100% | DEX+2 | 100% | — | — | — | — | DARK |
| 2040833 | `S2040833` | Scroll for Gloves for DEX 50% | DEX+3 | 50% | — | — | — | — | DARK |
| 2040801 | `S2040801` | Scroll for Gloves for DEX 60% | DEX+1 | 60% | 100k | — | — | — | ON |
| 2040825 | `S2040825` | Scroll for Gloves for HP 10% | HP+30 | 10% | — | — | — | 1 | NEEDS PRICE |
| 2040823 | `S2040823` | Scroll for Gloves for HP 100% | HP+5 | 100% | — | — | 1/1 | — | NEEDS PRICE |
| 2040824 | `S2040824` | Scroll for Gloves for HP 60% | HP+15 | 60% | — | — | 1/1 | — | NEEDS PRICE |
| 2040816 | `S2040816` | Scroll for Gloves for Magic ATT 10% | M.ATT+3 INT+3 M.DEF+1 | 10% | — | — | — | — | DARK |
| 2040818 | `S2040818` | Scroll for Gloves for Magic ATT 100% | M.ATT+1 | 100% | — | — | — | — | DARK |
| 2040817 | `S2040817` | Scroll for Gloves for Magic ATT 60% | M.ATT+1 INT+1 | 60% | — | — | — | — | DARK |

#### Shoes — 24

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040713 | `S2040713` | Dark Scroll for Shoes for DEX 30% | Speed+1 | 30% | — | — | — | — | DARK |
| 2040712 | `S2040712` | Dark Scroll for Shoes for DEX 70% | — | 70% | — | — | — | 1 | NEEDS PRICE |
| 2040715 | `S2040715` | Dark Scroll for Shoes for Jump 30% | DEX+3 Speed+1 Jump+5 | 30% | — | 2.20M (58) | — | 49 | ON |
| 2040714 | `S2040714` | Dark Scroll for Shoes for Jump 70% | DEX+1 Jump+2 | 70% | — | 625.5k (11) | — | 34 | ON |
| 2040717 | `S2040717` | Dark Scroll for Shoes for Speed 30% | Speed+3 | 30% | — | — | — | — | DARK |
| 2040716 | `S2040716` | Dark Scroll for Shoes for Speed 70% | Speed+2 | 70% | — | — | — | — | DARK |
| 2040760 | `S2040760` | Scroll for Shoes for ATT 10% | ATT+3 | 10% | — | — | — | — | DARK |
| 2040758 | `S2040758` | Scroll for Shoes for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2040759 | `S2040759` | Scroll for Shoes for ATT 60% | ATT+2 | 60% | — | — | — | — | DARK |
| 2040702 | `S2040702` | Scroll for Shoes for DEX 10% | Speed+1 | 10% | 100k | — | 2/2 | 7 | ON |
| 2040700 | `S2040700` | Scroll for Shoes for DEX 100% | — | 100% | — | — | — | — | DARK |
| 2040740 | `S2040740` | Scroll for Shoes for DEX 100% | — | 100% | — | — | — | — | DARK |
| 2040755 | `S2040755` | Scroll for Shoes for DEX 50% | Speed+2 | 50% | — | — | — | — | DARK |
| 2040701 | `S2040701` | Scroll for Shoes for DEX 60% | — | 60% | 100k | — | 2/2 | 1 | ON |
| 2040705 | `S2040705` | Scroll for Shoes for Jump 10% | DEX+3 Speed+1 Jump+5 | 10% | 500k | 451.9k (56) | 2/1 | 76 | ON |
| 2040703 | `S2040703` | Scroll for Shoes for Jump 100% | Jump+1 | 100% | — | — | — | — | DARK |
| 2040741 | `S2040741` | Scroll for Shoes for Jump 100% | DEX+2 Jump+2 | 100% | — | — | — | — | DARK |
| 2040756 | `S2040756` | Scroll for Shoes for Jump 50% | Speed+1 Jump+6 | 50% | — | — | — | — | DARK |
| 2040704 | `S2040704` | Scroll for Shoes for Jump 60% | DEX+1 Jump+2 | 60% | 1M | 881.6k (48) | 2/2 | 33 | ON |
| 2040708 | `S2040708` | Scroll for Shoes for Speed 10% | Speed+3 | 10% | 100k | — | — | 20 | ON |
| 2040706 | `S2040706` | Scroll for Shoes for Speed 100% | Speed+1 | 100% | — | — | — | — | DARK |
| 2040742 | `S2040742` | Scroll for Shoes for Speed 100% | Speed+2 | 100% | — | — | — | — | DARK |
| 2040757 | `S2040757` | Scroll for Shoes for Speed 50% | Speed+3 Jump+1 | 50% | — | — | — | — | DARK |
| 2040707 | `S2040707` | Scroll for Shoes for Speed 60% | Speed+2 | 60% | 100k | — | — | 21 | ON |

#### Belts — 20

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2041317 | `S2041317` | Dark Scroll for Belts for DEX 30% | DEX+3 | 30% | — | — | — | — | DARK |
| 2041316 | `S2041316` | Dark Scroll for Belts for DEX 70% | DEX+2 | 70% | — | — | — | — | DARK |
| 2041315 | `S2041315` | Dark Scroll for Belts for INT 30% | INT+3 | 30% | — | — | — | — | DARK |
| 2041314 | `S2041314` | Dark Scroll for Belts for INT 70% | INT+2 | 70% | — | — | — | — | DARK |
| 2041319 | `S2041319` | Dark Scroll for Belts for LUK 30% | LUK+3 | 30% | — | — | — | — | DARK |
| 2041318 | `S2041318` | Dark Scroll for Belts for LUK 70% | LUK+2 | 70% | — | — | — | — | DARK |
| 2041313 | `S2041313` | Dark Scroll for Belts for STR 30% | STR+3 | 30% | — | — | — | — | DARK |
| 2041312 | `S2041312` | Dark Scroll for Belts for STR 70% | STR+2 | 70% | — | — | — | — | DARK |
| 2041308 | `S2041308` | Scroll for Belts for DEX 10% | DEX+3 | 10% | — | — | — | — | DARK |
| 2041306 | `S2041306` | Scroll for Belts for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2041307 | `S2041307` | Scroll for Belts for DEX 60% | DEX+2 | 60% | — | — | — | 1 | NEEDS PRICE |
| 2041305 | `S2041305` | Scroll for Belts for INT 10% | INT+3 | 10% | — | — | — | — | DARK |
| 2041303 | `S2041303` | Scroll for Belts for INT 100% | INT+1 | 100% | — | — | — | — | DARK |
| 2041304 | `S2041304` | Scroll for Belts for INT 60% | INT+2 | 60% | — | — | — | — | DARK |
| 2041311 | `S2041311` | Scroll for Belts for LUK 10% | LUK+3 | 10% | — | — | — | — | DARK |
| 2041309 | `S2041309` | Scroll for Belts for LUK 100% | LUK+1 | 100% | — | — | — | — | DARK |
| 2041310 | `S2041310` | Scroll for Belts for LUK 60% | LUK+2 | 60% | — | — | — | — | DARK |
| 2041302 | `S2041302` | Scroll for Belts for STR 10% | STR+3 | 10% | — | — | — | — | DARK |
| 2041300 | `S2041300` | Scroll for Belts for STR 100% | STR+1 | 100% | — | — | — | — | DARK |
| 2041301 | `S2041301` | Scroll for Belts for STR 60% | STR+2 | 60% | — | — | — | — | DARK |

#### Rings — 19

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2041117 | `S2041117` | Dark Scroll for Rings for DEX 30% | DEX+3 | 30% | — | — | — | — | DARK |
| 2041116 | `S2041116` | Dark Scroll for Rings for DEX 70% | DEX+2 | 70% | — | — | — | — | DARK |
| 2041115 | `S2041115` | Dark Scroll for Rings for INT 30% | INT+3 | 30% | — | — | — | — | DARK |
| 2041114 | `S2041114` | Dark Scroll for Rings for INT 70% | INT+2 | 70% | — | — | — | — | DARK |
| 2041119 | `S2041119` | Dark Scroll for Rings for LUK 30% | LUK+3 | 30% | — | — | — | — | DARK |
| 2041118 | `S2041118` | Dark Scroll for Rings for LUK 70% | LUK+2 | 70% | — | — | — | — | DARK |
| 2041113 | `S2041113` | Dark Scroll for Rings for STR 30% | STR+3 | 30% | — | — | — | — | DARK |
| 2041112 | `S2041112` | Dark Scroll for Rings for STR 70% | HP+10 | 70% | — | — | — | — | DARK |
| 2041108 | `S2041108` | Scroll for Rings for DEX 10% | DEX+3 | 10% | — | — | — | — | DARK |
| 2041106 | `S2041106` | Scroll for Rings for DEX 100% | DEX+1 | 100% | — | — | — | — | DARK |
| 2041107 | `S2041107` | Scroll for Rings for DEX 60% | DEX+2 | 60% | — | — | — | — | DARK |
| 2041105 | `S2041105` | Scroll for Rings for INT 10% | INT+3 | 10% | — | — | — | — | DARK |
| 2041103 | `S2041103` | Scroll for Rings for INT 100% | INT+1 | 100% | — | — | — | — | DARK |
| 2041104 | `S2041104` | Scroll for Rings for INT 60% | INT+2 | 60% | — | — | — | — | DARK |
| 2041111 | `S2041111` | Scroll for Rings for LUK 10% | LUK+3 | 10% | — | — | — | — | DARK |
| 2041109 | `S2041109` | Scroll for Rings for LUK 100% | LUK+1 | 100% | — | — | — | — | DARK |
| 2041110 | `S2041110` | Scroll for Rings for LUK 60% | LUK+2 | 60% | — | — | — | — | DARK |
| 2041102 | `S2041102` | Scroll for Rings for STR 10% | STR+3 | 10% | — | — | — | — | DARK |
| 2041101 | `S2041101` | Scroll for Rings for STR 60% | STR+2 | 60% | — | — | — | — | DARK |

#### One-Handed Sword — 17

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2043005 | `S2043005` | Dark Scroll for One-Handed Sword for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2043004 | `S2043004` | Dark Scroll for One-Handed Sword for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | 2 | NEEDS PRICE |
| 2043007 | `S2043007` | Dark Scroll for One-Handed Sword for Magic ATT 30% | M.ATT+2 INT+2 M.DEF+1 | 30% | — | — | — | — | DARK |
| 2043006 | `S2043006` | Dark Scroll for One-Handed Sword for Magic ATT 70% | M.ATT+1 INT+1 | 70% | — | — | — | 2 | NEEDS PRICE |
| 2043002 | `S2043002` | Scroll for One-Handed Sword for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 450k | 402.6k (6) | 2/1 | 5 | ON |
| 2043000 | `S2043000` | Scroll for One-Handed Sword for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2043023 | `S2043023` | Scroll for One-Handed Sword for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2043022 | `S2043022` | Scroll for One-Handed Sword for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2043001 | `S2043001` | Scroll for One-Handed Sword for ATT 60% | ATT+2 STR+1 | 60% | 1.30M | 1.17M (16) | — | 18 | ON |
| 2043019 | `S2043019` | Scroll for One-Handed Sword for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | 3/2 | 5 | NEEDS PRICE |
| 2043015 | `S2043015` | Scroll for One-Handed Sword for Accuracy 100% | — | 100% | — | — | 1/1 | — | NEEDS PRICE |
| 2043018 | `S2043018` | Scroll for One-Handed Sword for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | — | — | DARK |
| 2043017 | `S2043017` | Scroll for One-Handed Sword for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | — | — | DARK |
| 2043016 | `S2043016` | Scroll for One-Handed Sword for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |
| 2043008 | `S2043008` | Scroll for One-Handed Sword for Magic ATT 10% | M.ATT+2 INT+2 M.DEF+1 | 10% | — | — | — | — | DARK |
| 2043010 | `S2043010` | Scroll for One-Handed Sword for Magic ATT 100% | M.ATT+1 | 100% | — | — | — | — | DARK |
| 2043009 | `S2043009` | Scroll for One-Handed Sword for Magic ATT 60% | M.ATT+1 INT+1 | 60% | — | — | — | — | DARK |

#### One-Handed BW — 14

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2043205 | `S2043205` | Dark Scroll for One-Handed BW for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2043204 | `S2043204` | Dark Scroll for One-Handed BW for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | — | DARK |
| 2043202 | `S2043202` | Scroll for One-Handed BW for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 350k | 330k (1) | — | 7 | ON |
| 2043200 | `S2043200` | Scroll for One-Handed BW for ATT 100% | ATT+1 | 100% | — | — | 2/1 | — | NEEDS PRICE |
| 2043217 | `S2043217` | Scroll for One-Handed BW for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2043220 | `S2043220` | Scroll for One-Handed BW for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2043201 | `S2043201` | Scroll for One-Handed BW for ATT 60% | ATT+2 STR+1 | 60% | 1M | 920k (12) | — | 22 | ON |
| 2043214 | `S2043214` | Scroll for One-Handed BW for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | 7/5 | 5 | NEEDS PRICE |
| 2043210 | `S2043210` | Scroll for One-Handed BW for Accuracy 100% | — | 100% | — | — | — | 2 | NEEDS PRICE |
| 2043219 | `S2043219` | Scroll for One-Handed BW for Accuracy 15% | ATT+3 DEX+3 | 15% | — | — | — | — | DARK |
| 2043213 | `S2043213` | Scroll for One-Handed BW for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | — | — | DARK |
| 2043212 | `S2043212` | Scroll for One-Handed BW for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | — | 1 | NEEDS PRICE |
| 2043218 | `S2043218` | Scroll for One-Handed BW for Accuracy 65% | ATT+1 DEX+2 | 65% | — | — | — | — | DARK |
| 2043211 | `S2043211` | Scroll for One-Handed BW for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Accessory — 12

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2049203 | `S2049203` | Dark Scroll for Accessory for DEX 30% | DEX+3 | 30% | — | — | — | — | DARK |
| 2049202 | `S2049202` | Dark Scroll for Accessory for DEX 70% | DEX+2 | 70% | — | — | — | — | DARK |
| 2049209 | `S2049209` | Dark Scroll for Accessory for HP 30% | HP+30 | 30% | — | — | — | — | DARK |
| 2049208 | `S2049208` | Dark Scroll for Accessory for HP 70% | HP+10 | 70% | — | — | — | — | DARK |
| 2049205 | `S2049205` | Dark Scroll for Accessory for INT 30% | INT+3 | 30% | — | — | — | — | DARK |
| 2049204 | `S2049204` | Dark Scroll for Accessory for INT 70% | INT+2 | 70% | — | — | 2/2 | — | NEEDS PRICE |
| 2049207 | `S2049207` | Dark Scroll for Accessory for LUK 30% | LUK+3 | 30% | — | — | — | — | DARK |
| 2049206 | `S2049206` | Dark Scroll for Accessory for LUK 70% | LUK+2 | 70% | — | — | 2/1 | — | NEEDS PRICE |
| 2049211 | `S2049211` | Dark Scroll for Accessory for MP 30% | MP+30 | 30% | — | — | — | — | DARK |
| 2049210 | `S2049210` | Dark Scroll for Accessory for MP 70% | MP+10 | 70% | — | — | — | — | DARK |
| 2049201 | `S2049201` | Dark Scroll for Accessory for STR 30% | STR+3 | 30% | — | — | — | — | DARK |
| 2049200 | `S2049200` | Dark Scroll for Accessory for STR 70%  | STR+2 | 70% | — | — | 1/1 | — | NEEDS PRICE |

#### One-Handed Axe — 12

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2043105 | `S2043105` | Dark Scroll for One-Handed Axe for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2043104 | `S2043104` | Dark Scroll for One-Handed Axe for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | — | DARK |
| 2043102 | `S2043102` | Scroll for One-Handed Axe for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 350k | — | — | 882 | ON |
| 2043100 | `S2043100` | Scroll for One-Handed Axe for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2043117 | `S2043117` | Scroll for One-Handed Axe for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2043120 | `S2043120` | Scroll for One-Handed Axe for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2043101 | `S2043101` | Scroll for One-Handed Axe for ATT 60% | ATT+2 STR+1 | 60% | 1M | 1000k (4) | — | 12 | ON |
| 2043114 | `S2043114` | Scroll for One-Handed Axe for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | — | 1 | NEEDS PRICE |
| 2043110 | `S2043110` | Scroll for One-Handed Axe for Accuracy 100% | — | 100% | — | — | — | 2 | NEEDS PRICE |
| 2043113 | `S2043113` | Scroll for One-Handed Axe for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | — | — | DARK |
| 2043112 | `S2043112` | Scroll for One-Handed Axe for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | — | 1 | NEEDS PRICE |
| 2043111 | `S2043111` | Scroll for One-Handed Axe for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Pole Arm — 12

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044405 | `S2044405` | Dark Scroll for Pole Arm for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2044404 | `S2044404` | Dark Scroll for Pole Arm for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | 15 | NEEDS PRICE |
| 2044402 | `S2044402` | Scroll for Pole Arm for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 450k | 457k (3) | 1/1 | 17 | ON |
| 2044400 | `S2044400` | Scroll for Pole Arm for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044417 | `S2044417` | Scroll for Pole Arm for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2044420 | `S2044420` | Scroll for Pole Arm for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2044401 | `S2044401` | Scroll for Pole Arm for ATT 60% | ATT+2 STR+1 | 60% | 1.30M | 1.20M (1) | 3/2 | 1 | ON |
| 2044414 | `S2044414` | Scroll for Pole Arm for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | — | 1 | NEEDS PRICE |
| 2044410 | `S2044410` | Scroll for Pole Arm for Accuracy 100% | — | 100% | — | — | — | — | DARK |
| 2044413 | `S2044413` | Scroll for Pole Arm for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | 1/1 | — | NEEDS PRICE |
| 2044412 | `S2044412` | Scroll for Pole Arm for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | 1/1 | 41 | NEEDS PRICE |
| 2044411 | `S2044411` | Scroll for Pole Arm for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Spear — 12

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044305 | `S2044305` | Dark Scroll for Spear for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | 13 | NEEDS PRICE |
| 2044304 | `S2044304` | Dark Scroll for Spear for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | 4 | NEEDS PRICE |
| 2044302 | `S2044302` | Scroll for Spear for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 500k | 487.5k (4) | 2/1 | 17 | ON |
| 2044300 | `S2044300` | Scroll for Spear for ATT 100% | ATT+1 | 100% | — | — | 7/1 | — | NEEDS PRICE |
| 2044317 | `S2044317` | Scroll for Spear for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2044320 | `S2044320` | Scroll for Spear for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2044301 | `S2044301` | Scroll for Spear for ATT 60% | ATT+2 STR+1 | 60% | 1.50M | 1.38M (8) | 4/2 | 13 | ON |
| 2044314 | `S2044314` | Scroll for Spear for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | 3/3 | 2 | NEEDS PRICE |
| 2044310 | `S2044310` | Scroll for Spear for Accuracy 100% | — | 100% | — | — | — | — | DARK |
| 2044313 | `S2044313` | Scroll for Spear for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | 1/1 | — | NEEDS PRICE |
| 2044312 | `S2044312` | Scroll for Spear for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | — | — | DARK |
| 2044311 | `S2044311` | Scroll for Spear for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Knuckle — 11

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044802 | `S2044802` | Scroll for Knuckle for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 350k | 380k (5) | 2/2 | 8 | ON |
| 2044800 | `S2044800` | Scroll for Knuckle for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044815 | `S2044815` | Scroll for Knuckle for ATT 100% | ATT+2 STR+1 | 100% | — | — | — | — | DARK |
| 2044804 | `S2044804` | Scroll for Knuckle for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | 4 | NEEDS PRICE |
| 2044801 | `S2044801` | Scroll for Knuckle for ATT 60% | ATT+2 STR+1 | 60% | 1M | 958.3k (6) | 3/3 | 9 | ON |
| 2044803 | `S2044803` | Scroll for Knuckle for ATT 70% | ATT+2 STR+1 | 70% | — | — | 1/1 | 2 | NEEDS PRICE |
| 2044809 | `S2044809` | Scroll for Knuckle for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | — | — | DARK |
| 2044805 | `S2044805` | Scroll for Knuckle for Accuracy 100% | — | 100% | — | — | — | — | DARK |
| 2044808 | `S2044808` | Scroll for Knuckle for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | — | — | DARK |
| 2044807 | `S2044807` | Scroll for Knuckle for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | — | 1 | NEEDS PRICE |
| 2044806 | `S2044806` | Scroll for Knuckle for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Eye Accessory — 10

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040203 | `S2040203` | Dark Scroll for Eye Accessory for Accuracy 30% | DEX+1 | 30% | — | — | — | — | DARK |
| 2040204 | `S2040204` | Dark Scroll for Eye Accessory for Accuracy 70% | — | 70% | — | — | — | 2 | NEEDS PRICE |
| 2040208 | `S2040208` | Dark Scroll for Eye Accessory for INT 30% | INT+3 M.DEF+2 | 30% | — | — | — | — | DARK |
| 2040209 | `S2040209` | Dark Scroll for Eye Accessory for INT 70% | INT+1 M.DEF+1 | 70% | — | — | — | 3 | NEEDS PRICE |
| 2040200 | `S2040200` | Scroll for Eye Accessory for Accuracy 10% | DEX+1 | 10% | — | — | — | — | DARK |
| 2040202 | `S2040202` | Scroll for Eye Accessory for Accuracy 100% | — | 100% | — | — | 5/3 | — | NEEDS PRICE |
| 2040201 | `S2040201` | Scroll for Eye Accessory for Accuracy 60% | — | 60% | — | — | — | — | DARK |
| 2040205 | `S2040205` | Scroll for Eye Accessory for INT 10% | INT+3 M.DEF+2 | 10% | — | — | — | 12 | NEEDS PRICE |
| 2040207 | `S2040207` | Scroll for Eye Accessory for INT 100% | INT+1 | 100% | — | — | — | — | DARK |
| 2040206 | `S2040206` | Scroll for Eye Accessory for INT 60% | INT+1 M.DEF+1 | 60% | — | — | — | — | DARK |

#### Face Accessory — 10

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040108 | `S2040108` | Dark Scroll for Face Accessory for Avoidability 30% | DEX+2 | 30% | — | — | — | — | DARK |
| 2040109 | `S2040109` | Dark Scroll for Face Accessory for Avoidability 70% | DEX+1 | 70% | — | — | — | 6 | NEEDS PRICE |
| 2040103 | `S2040103` | Dark Scroll for Face Accessory for HP 30% | HP+30 | 30% | — | — | — | 3 | NEEDS PRICE |
| 2040104 | `S2040104` | Dark Scroll for Face Accessory for HP 70% | HP+15 | 70% | — | — | — | 7 | NEEDS PRICE |
| 2040105 | `S2040105` | Scroll for Face Accessory for Avoidability 10% | DEX+2 | 10% | — | — | — | — | DARK |
| 2040107 | `S2040107` | Scroll for Face Accessory for Avoidability 100% | — | 100% | — | — | 1/1 | 6 | NEEDS PRICE |
| 2040106 | `S2040106` | Scroll for Face Accessory for Avoidability 60% | DEX+1 | 60% | — | — | — | — | DARK |
| 2040100 | `S2040100` | Scroll for Face Accessory for HP 10% | HP+30 | 10% | — | — | — | — | DARK |
| 2040102 | `S2040102` | Scroll for Face Accessory for HP 100% | HP+5 | 100% | — | — | 1/1 | — | NEEDS PRICE |
| 2040101 | `S2040101` | Scroll for Face Accessory for HP 60% | HP+15 | 60% | — | — | — | — | DARK |

#### Pet Equip. — 10

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2048012 | `S2048012` | Scroll for Pet Equip. for DEX 60% | DEX+1 | 60% | — | — | — | 10 | NEEDS PRICE |
| 2048011 | `S2048011` | Scroll for Pet Equip. for INT 60% | INT+1 | 60% | — | — | — | 18,783 | NEEDS PRICE |
| 2048005 | `S2048005` | Scroll for Pet Equip. for Jump 10% | Jump+3 | 10% | 100k | — | — | 1 | ON |
| 2048003 | `S2048003` | Scroll for Pet Equip. for Jump 100% | Jump+1 | 100% | — | — | — | 1 | NEEDS PRICE |
| 2048004 | `S2048004` | Scroll for Pet Equip. for Jump 60% | Jump+2 | 60% | 100k | — | — | 15 | ON |
| 2048013 | `S2048013` | Scroll for Pet Equip. for LUK 60% | LUK+1 | 60% | — | — | 1/1 | 23,901 | NEEDS PRICE |
| 2048010 | `S2048010` | Scroll for Pet Equip. for STR 60% | STR+1 | 60% | — | — | — | — | DARK |
| 2048002 | `S2048002` | Scroll for Pet Equip. for Speed 10% | Speed+3 | 10% | 100k | — | — | 7 | ON |
| 2048000 | `S2048000` | Scroll for Pet Equip. for Speed 100% | Speed+1 | 100% | — | — | 3/2 | 428 | NEEDS PRICE |
| 2048001 | `S2048001` | Scroll for Pet Equip. for Speed 60% | Speed+2 | 60% | 100k | — | 1/1 | 27 | ON |

#### Bow — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044505 | `S2044505` | Dark Scroll for Bow for ATT 30% | ATT+5 DEX+1 | 30% | — | — | — | 1 | NEEDS PRICE |
| 2044504 | `S2044504` | Dark Scroll for Bow for ATT 70% | ATT+2 | 70% | — | — | — | — | DARK |
| 2044502 | `S2044502` | Scroll for Bow for ATT 10% | ATT+5 DEX+1 | 10% | 500k | 456.5k (6) | 1/1 | 4 | ON |
| 2044500 | `S2044500` | Scroll for Bow for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044512 | `S2044512` | Scroll for Bow for ATT 100% | ATT+2 | 100% | — | — | — | — | DARK |
| 2044513 | `S2044513` | Scroll for Bow for ATT 50% | ATT+5 STR+1 DEX+1 | 50% | — | — | — | — | DARK |
| 2044501 | `S2044501` | Scroll for Bow for ATT 60% | ATT+2 | 60% | 1.50M | 1.36M (10) | — | 9 | ON |

#### Claw — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044705 | `S2044705` | Dark Scroll for Claw for ATT 30% | ATT+5 LUK+1 | 30% | — | — | — | — | DARK |
| 2044704 | `S2044704` | Dark Scroll for Claw for ATT 70% | ATT+2 | 70% | — | — | — | — | DARK |
| 2044702 | `S2044702` | Scroll for Claw for ATT 10% | ATT+5 LUK+1 | 10% | 500k | 450.6k (54) | 3/2 | 17,518 | ON |
| 2044700 | `S2044700` | Scroll for Claw for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044712 | `S2044712` | Scroll for Claw for ATT 100% | ATT+2 | 100% | — | — | — | — | DARK |
| 2044713 | `S2044713` | Scroll for Claw for ATT 50% | ATT+5 DEX+1 LUK+1 | 50% | — | — | — | — | DARK |
| 2044701 | `S2044701` | Scroll for Claw for ATT 60% | ATT+2 | 60% | 1.50M | 1.28M (49) | 6/2 | 50 | ON |

#### Crossbow — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044605 | `S2044605` | Dark Scroll for Crossbow for ATT 30% | ATT+5 DEX+1 | 30% | — | — | — | — | DARK |
| 2044604 | `S2044604` | Dark Scroll for Crossbow for ATT 70% | ATT+2 | 70% | — | — | — | 1 | NEEDS PRICE |
| 2044602 | `S2044602` | Scroll for Crossbow for ATT 10% | ATT+5 DEX+1 | 10% | 500k | 492.1k (9) | 3/2 | 8 | ON |
| 2044600 | `S2044600` | Scroll for Crossbow for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044612 | `S2044612` | Scroll for Crossbow for ATT 100% | ATT+2 | 100% | — | — | — | — | DARK |
| 2044613 | `S2044613` | Scroll for Crossbow for ATT 50% | ATT+5 STR+1 DEX+1 | 50% | — | — | — | — | DARK |
| 2044601 | `S2044601` | Scroll for Crossbow for ATT 60% | ATT+2 | 60% | 1.50M | 1.39M (9) | 1/1 | 6 | ON |

#### Dagger — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2043305 | `S2043305` | Dark Scroll for Dagger for ATT 30% | ATT+5 LUK+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2043304 | `S2043304` | Dark Scroll for Dagger for ATT 70% | ATT+2 LUK+1 | 70% | — | — | — | 5 | NEEDS PRICE |
| 2043302 | `S2043302` | Scroll for Dagger for ATT 10% | ATT+5 LUK+3 DEF+1 | 10% | 550k | 508.6k (49) | 2/1 | 53 | ON |
| 2043300 | `S2043300` | Scroll for Dagger for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2043312 | `S2043312` | Scroll for Dagger for ATT 100% | ATT+2 LUK+2 | 100% | — | — | — | — | DARK |
| 2043313 | `S2043313` | Scroll for Dagger for ATT 50% | ATT+5 DEX+1 LUK+3 | 50% | — | — | — | — | DARK |
| 2043301 | `S2043301` | Scroll for Dagger for ATT 60% | ATT+2 LUK+1 | 60% | 1.60M | 1.50M (54) | 14/5 | 105 | ON |

#### Gun — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044902 | `S2044902` | Scroll for Gun for ATT 10% | ATT+5 DEX+1 | 10% | 350k | 312k (5) | 5/5 | 50 | ON |
| 2044900 | `S2044900` | Scroll for Gun for ATT 100% | ATT+1 | 100% | — | — | — | 1 | NEEDS PRICE |
| 2044908 | `S2044908` | Scroll for Gun for ATT 100% | ATT+2 | 100% | — | — | — | — | DARK |
| 2044904 | `S2044904` | Scroll for Gun for ATT 30% | ATT+5 DEX+1 | 30% | — | — | 16/4 | 10 | NEEDS PRICE |
| 2044910 | `S2044910` | Scroll for Gun for ATT 50% | ATT+5 DEX+1 LUK+1 | 50% | — | — | — | — | DARK |
| 2044901 | `S2044901` | Scroll for Gun for ATT 60% | ATT+2 | 60% | 1M | 950k (4) | 1/1 | 21 | ON |
| 2044903 | `S2044903` | Scroll for Gun for ATT 70% | ATT+2 | 70% | — | — | — | 2 | NEEDS PRICE |

#### Staff — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2043805 | `S2043805` | Dark Scroll for Staff for Magic ATT 30% | M.ATT+5 INT+3 M.DEF+1 | 30% | — | — | — | — | DARK |
| 2043804 | `S2043804` | Dark Scroll for Staff for Magic ATT 70% | M.ATT+2 INT+1 | 70% | — | — | — | — | DARK |
| 2043802 | `S2043802` | Scroll for Staff for Magic ATT 10% | M.ATT+5 INT+3 M.DEF+1 | 10% | 450k | 428.7k (15) | 1/1 | 1,403 | ON |
| 2043800 | `S2043800` | Scroll for Staff for Magic ATT 100% | M.ATT+1 | 100% | — | — | — | 16 | NEEDS PRICE |
| 2043812 | `S2043812` | Scroll for Staff for Magic ATT 100% | M.ATT+2 INT+2 | 100% | — | — | — | — | DARK |
| 2043801 | `S2043801` | Scroll for Staff for Magic ATT 60% | M.ATT+2 INT+1 | 60% | 1.40M | 1.25M (12) | 1/1 | 103 | ON |
| 2043813 | `S2043813` | Scroll for Staff for Magic Att. 50% | M.ATT+5 INT+3 LUK+1 | 50% | — | — | — | — | DARK |

#### Two-handed Axe — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044105 | `S2044105` | Dark Scroll for Two-handed Axe for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2044104 | `S2044104` | Dark Scroll for Two-handed Axe for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | 2 | NEEDS PRICE |
| 2044102 | `S2044102` | Scroll for Two-handed Axe for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 350k | 330k (1) | — | 11 | ON |
| 2044100 | `S2044100` | Scroll for Two-handed Axe for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044117 | `S2044117` | Scroll for Two-handed Axe for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2044120 | `S2044120` | Scroll for Two-handed Axe for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2044101 | `S2044101` | Scroll for Two-handed Axe for ATT 60% | ATT+2 STR+1 | 60% | 1M | 950k (2) | 4/2 | 4 | ON |

#### Two-handed BW — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044205 | `S2044205` | Dark Scroll for Two-handed BW for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2044204 | `S2044204` | Dark Scroll for Two-handed BW for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | 1 | NEEDS PRICE |
| 2044202 | `S2044202` | Scroll for Two-handed BW for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 350k | 341.7k (2) | — | 5 | ON |
| 2044200 | `S2044200` | Scroll for Two-handed BW for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044217 | `S2044217` | Scroll for Two-handed BW for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2044220 | `S2044220` | Scroll for Two-handed BW for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2044201 | `S2044201` | Scroll for Two-handed BW for ATT 60% | ATT+2 STR+1 | 60% | 1M | 923.6k (7) | 11/5 | 28 | ON |

#### Two-handed Sword — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044005 | `S2044005` | Dark Scroll for Two-handed Sword for ATT 30% | ATT+5 STR+3 DEF+1 | 30% | — | — | — | — | DARK |
| 2044004 | `S2044004` | Dark Scroll for Two-handed Sword for ATT 70% | ATT+2 STR+1 | 70% | — | — | — | 3 | NEEDS PRICE |
| 2044002 | `S2044002` | Scroll for Two-handed Sword for ATT 10% | ATT+5 STR+3 DEF+1 | 10% | 450k | 430k (5) | — | — | ON |
| 2044000 | `S2044000` | Scroll for Two-handed Sword for ATT 100% | ATT+1 | 100% | — | — | — | — | DARK |
| 2044025 | `S2044025` | Scroll for Two-handed Sword for ATT 100% | ATT+2 STR+2 | 100% | — | — | — | — | DARK |
| 2044028 | `S2044028` | Scroll for Two-handed Sword for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |
| 2044001 | `S2044001` | Scroll for Two-handed Sword for ATT 60% | ATT+2 STR+1 | 60% | 1.30M | 1.22M (3) | 11/4 | 25 | ON |

#### Wand — 7

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2043705 | `S2043705` | Dark Scroll for Wand for Magic ATT 30% | M.ATT+5 INT+3 M.DEF+1 | 30% | — | — | — | 1 | NEEDS PRICE |
| 2043704 | `S2043704` | Dark Scroll for Wand for Magic ATT 70% | M.ATT+2 INT+1 | 70% | — | — | — | — | DARK |
| 2043702 | `S2043702` | Scroll for Wand for Magic ATT 10% | M.ATT+5 INT+3 M.DEF+1 | 10% | 500k | 490.2k (10) | — | 12 | ON |
| 2043700 | `S2043700` | Scroll for Wand for Magic ATT 100% | M.ATT+1 | 100% | — | — | 329/5 | 71,948 | NEEDS PRICE |
| 2043712 | `S2043712` | Scroll for Wand for Magic ATT 100% | M.ATT+2 INT+2 | 100% | — | — | — | — | DARK |
| 2043701 | `S2043701` | Scroll for Wand for Magic ATT 60% | M.ATT+2 INT+1 | 60% | 1.50M | 1.40M (11) | 27/3 | 517 | ON |
| 2043713 | `S2043713` | Scroll for Wand for Magic Att. 50% | M.ATT+5 INT+3 LUK+1 | 50% | — | — | — | — | DARK |

#### Two-Handed Axe — 5

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044114 | `S2044114` | Scroll for Two-Handed Axe for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | 4/3 | — | NEEDS PRICE |
| 2044110 | `S2044110` | Scroll for Two-Handed Axe for Accuracy 100% | — | 100% | — | — | 1/1 | 3 | NEEDS PRICE |
| 2044113 | `S2044113` | Scroll for Two-Handed Axe for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | 2/2 | — | NEEDS PRICE |
| 2044112 | `S2044112` | Scroll for Two-Handed Axe for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | 1/1 | 1 | NEEDS PRICE |
| 2044111 | `S2044111` | Scroll for Two-Handed Axe for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Two-Handed BW — 5

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044214 | `S2044214` | Scroll for Two-Handed BW for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | 3/3 | 1 | NEEDS PRICE |
| 2044210 | `S2044210` | Scroll for Two-Handed BW for Accuracy 100% | — | 100% | — | — | 12/3 | — | NEEDS PRICE |
| 2044213 | `S2044213` | Scroll for Two-Handed BW for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | — | — | DARK |
| 2044212 | `S2044212` | Scroll for Two-Handed BW for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | — | — | DARK |
| 2044211 | `S2044211` | Scroll for Two-Handed BW for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |

#### Two-Handed Sword — 5

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044013 | `S2044013` | Dark Scroll for Two-Handed Sword for Accuracy 30% | ATT+3 DEX+3 | 30% | — | — | — | — | DARK |
| 2044011 | `S2044011` | Dark Scroll for Two-Handed Sword for Accuracy 70% | ATT+1 DEX+2 | 70% | — | — | — | — | DARK |
| 2044014 | `S2044014` | Scroll for Two-Handed Sword for Accuracy 10% | ATT+3 DEX+3 | 10% | — | — | — | 4 | NEEDS PRICE |
| 2044010 | `S2044010` | Scroll for Two-Handed Sword for Accuracy 100% | — | 100% | — | — | — | 3 | NEEDS PRICE |
| 2044012 | `S2044012` | Scroll for Two-Handed Sword for Accuracy 60% | ATT+1 DEX+2 | 60% | — | — | 18/5 | — | NEEDS PRICE |

#### Special (Clean Slate) — 4

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2049000 | `S2049000` | Clean Slate Scroll 1% | — | 1% | — | — | 8/3 | 1 | NEEDS PRICE |
| 2049003 | `S2049003` | Clean Slate Scroll 20% | — | 20% | — | — | — | 19 | NEEDS PRICE |
| 2049001 | `S2049001` | Clean Slate Scroll 3% | — | 3% | — | — | — | — | DARK |
| 2049002 | `S2049002` | Clean Slate Scroll 5% | — | 5% | — | — | — | — | DARK |

#### Earrings — 2

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2040309 | `S2040309` | Dark Scroll for Earrings for DEF 30% | DEF+3 M.DEF+3 | 30% | — | — | — | — | DARK |
| 2040308 | `S2040308` | Dark Scroll for Earrings for DEF 70% | DEF+1 M.DEF+1 | 70% | — | — | — | 1 | NEEDS PRICE |

#### Knuckles — 1

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044817 | `S2044817` | Scroll for Knuckles for ATT 50% | ATT+5 STR+3 DEX+1 | 50% | — | — | — | — | DARK |

#### Ring — 1

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2041100 | `S2041100` | Scroll for Ring for STR 100% | STR+1 | 100% | — | — | — | — | DARK |

#### Spears — 1

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2044318 | `S2044318` | Scroll for Spears for Accuracy 65% | ATT+1 DEX+2 | 65% | — | — | — | — | DARK |

#### Special (Chaos) — 1

| id | ticker | name | effect | succ | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|---|
| 2049100 | `S2049100` | Chaos Scroll 60% | — | 60% | — | — | 300/11 | 2,213 | NEEDS PRICE |

### 1.2 Untradeable scrolls (209, SKIP)

2040019, 2040020, 2040021, 2040022, 2040023, 2040043, 2040044, 2040110, 2040111, 2040112, 2040113, 2040114, 2040115, 2040116, 2040117, 2040118, 2040119, 2040313, 2040314, 2040315, 2040329, 2040330, 2040331, 2040335, 2040336, 2040337, 2040338, 2040339, 2040340, 2040415, 2040416, 2040431, 2040432, 2040433, 2040434, 2040435, 2040436, 2040522, 2040523, 2040524, 2040525, 2040526, 2040527, 2040528, 2040529, 2040540, 2040541, 2040615, 2040616, 2040631, 2040632, 2040633, 2040634, 2040635, 2040636, 2040718, 2040719, 2040720, 2040721, 2040722, 2040723, 2040727, 2040728, 2040729, 2040730, 2040731, 2040732, 2040733, 2040734, 2040735, 2040736, 2040737, 2040738, 2040739, 2040819, 2040820, 2040821, 2040822, 2040826, 2040831, 2040832, 2040910, 2040911, 2040912, 2040937, 2040938, 2040939, 2040940, 2040941, 2040942, 2041042, 2041043, 2041044, 2041045, 2041046, 2041047, 2041048, 2041049, 2041050, 2041051, 2041052, 2041053, 2041054, 2041055, 2041056, 2041057, 2041058, 2041059, 2041060, 2041061, 2041062, 2041200, 2041211, 2041212, 2043011, 2043012, 2043013, 2043021, 2043024, 2043025, 2043106, 2043107, 2043108, 2043116, 2043118, 2043119, 2043206, 2043207, 2043208, 2043216, 2043306, 2043307, 2043308, 2043311, 2043706, 2043707, 2043708, 2043711, 2043806, 2043807, 2043808, 2043811, 2044006, 2044007, 2044008, 2044015, 2044024, 2044026, 2044027, 2044106, 2044107, 2044108, 2044116, 2044118, 2044119, 2044206, 2044207, 2044208, 2044216, 2044218, 2044219, 2044306, 2044307, 2044308, 2044316, 2044319, 2044406, 2044407, 2044408, 2044416, 2044418, 2044419, 2044506, 2044507, 2044508, 2044511, 2044606, 2044607, 2044608, 2044611, 2044706, 2044707, 2044708, 2044711, 2044810, 2044811, 2044812, 2044813, 2044814, 2044816, 2044905, 2044906, 2044907, 2044909, 2048006, 2048007, 2048008, 2048009, 2049101, 2049102, 2049103, 2049104, 2049105, 2049106, 2049107, 2049108, 2049109, 2049110, 2049112

## 2. Use items

Shortlist families: potions (200), food/buff (201/202), cure (205), arrows/stars/bullets (206/207/233).
Skill books (229), summon sacks (210) and 243/244/245 event items are out of scope.

An item can only be ON if its anchor is at least 1,000 (`FairValue.TOKEN_FLOOR`), which is why arrows and basic
potions — NPC price 1–10 mesos — cannot be tickers even though players hold tens of thousands: the exchange has
no way to represent a dust market. Stars, buff foods and elixirs can.

| id | ticker | name | family | pool | board 7d (n) | players | bots | state |
|---|---|---|---|---|---|---|---|---|
| 2000001 | `U2000001` | Orange Potion | potion | — | — | 487/6 | 401,151 | NEEDS PRICE |
| 2000002 | `U2000002` | White Potion | potion | — | — | 3,527/9 | 2,844,259 | NEEDS PRICE |
| 2000003 | `U2000003` | Blue Potion | potion | — | — | 891/9 | 264,409 | NEEDS PRICE |
| 2000004 | `U2000004` | Elixir | potion | 4k | 3.7k (72) | 4,001/13 | 3,774,600 | ON |
| 2000005 | `U2000005` | Power Elixir | potion | 9.5k | 9k (72) | 7,357/16 | 693,642 | ON |
| 2000006 | `U2000006` | Mana Elixir | potion | — | — | 9,559/13 | 2,411,136 | NEEDS PRICE |
| 2001002 | `U2001002` | Red Bean Sundae | potion | 3k | 2.8k (61) | 919/2 | 4,832 | ON |
| 2000000 | `U2000000` | Red Potion | potion | — | — | 269/8 | 77,527 | NEEDS PRICE |
| 2050004 | `U2050004` | All Cure Potion | cure | 7k | 6.6k (74) | 1,296/6 | 1,865,566 | ON |
| 2012000 | `U2012000` | Drake's Blood | food | 20k | 18.2k (61) | 22/1 | 972 | ON |
| 2012002 | `U2012002` | Sap of Ancient Tree | food | 20k | 18.5k (52) | 6/1 | 359 | ON |
| 2020000 | `U2020000` | Salad | food/buff | — | — | — | 1,069 | NEEDS PRICE |
| 2020001 | `U2020001` | Fried Chicken | food/buff | — | — | — | 6,428 | NEEDS PRICE |
| 2020002 | `U2020002` | Cake | food/buff | — | — | 3/1 | — | NEEDS PRICE |
| 2020012 | `U2020012` | Melting Cheese | food/buff | 4k | 3.7k (68) | 68/2 | 409 | ON |
| 2020013 | `U2020013` | Reindeer Milk | food/buff | 4k | 3.7k (60) | 5,232/10 | 409 | ON |
| 2020014 | `U2020014` | Sunrise Dew | food/buff | 6k | 5.5k (59) | 15/3 | 194 | ON |
| 2020015 | `U2020015` | Sunset Dew | food/buff | 6k | 5.3k (65) | 1,378/9 | 1,587 | ON |
| 2020016 | `U2020016` | Cheesecake | food/buff | — | — | 4/1 | — | NEEDS PRICE |
| 2022003 | `U2022003` | Unagi | food/buff | — | — | 19,254/11 | 25,931 | NEEDS PRICE |
| 2022000 | `U2022000` | Pure Water | food/buff | — | — | 1,000/1 | 26,653 | NEEDS PRICE |
| 2022244 | `U2022244` | Smore | food/buff | — | — | 893/5 | 5,604 | NEEDS PRICE |
| 2022245 | `U2022245` | Heartstopper | food/buff | 900k | 1.39M (49) | — | 594 | ON |
| 2022121 | `U2022121` | Gelt Chocolate | food/buff | 1.50M | 2.22M (30) | — | 54 | ON |
| 2022179 | `U2022179` | Onyx Apple | food/buff | 1.50M | 2.28M (42) | 5/1 | 441 | ON |
| 2022273 | `U2022273` | Ssiws Cheese | food/buff | 1.30M | 1.79M (55) | — | 543 | ON |
| 2022282 | `U2022282` | Naricain's Demon Elixir | food/buff | 2M | — | — | 370 | ON |
| 2060000 | `U2060000` | Arrow for Bow | arrow | — | — | 20,297/9 | 3,185,783 | NEEDS PRICE |
| 2061000 | `U2061000` | Arrow for Crossbow | arrow | — | — | 11,371/8 | 5,363,969 | NEEDS PRICE |
| 2070000 | `U2070000` | Subi Throwing-Stars | star | — | — | — | 8,272,270 | NEEDS PRICE |
| 2070005 | `U2070005` | Steely Throwing-Knives | star | — | 6.72M (4) | 16,435/3 | 19 | ON |
| 2070016 | `U2070016` | Crystal Ilbi Throwing-Stars | star | — | — | 4,395/1 | — | NEEDS PRICE |
| 2330000 | `U2330000` | Bullet | bullet | — | — | 529/2 | 14 | NEEDS PRICE |
| 2330001 | `U2330001` | Split Bullet | bullet | — | — | 9,622/1 | 2 | NEEDS PRICE |
| 2330002 | `U2330002` | Mighty Bullet | bullet | — | — | — | 13 | NEEDS PRICE |
| 2330003 | `U2330003` | Vital Bullet | bullet | — | — | 40,796/1 | 4 | NEEDS PRICE |
| 2330004 | `U2330004` | Shiny Bullet | bullet | — | — | 20,000/2 | — | NEEDS PRICE |
| 2330005 | `U2330005` | Eternal Bullet | bullet | — | — | 19,999/2 | 8 | NEEDS PRICE |

16 of these are ON today: `U2000004`, `U2000005`, `U2001002`, `U2050004`, `U2012000`, `U2012002`, `U2020012`, `U2020013`, `U2020014`, `U2020015`, `U2022245`, `U2022121`, `U2022179`, `U2022273`, `U2022282`, `U2070005`.

## 3. ETC

`etc.yaml` prices **55** ETC ids, all tradeable — that is the ETC set ticket 270 enables.

| id | ticker | name | pool | band 0.25–4× | board 7d (n) | players | bots |
|---|---|---|---|---|---|---|---|
| 4006001 | `E4006001` | The Summoning Rock | 10k | 2.5k–40k | 9.2k (46) | 3,130/13 | 1,359,168 |
| 4030012 | `E4030012` | Monster Card | 5k | 1.2k–20k | 4.5k (7) | 2,264/11 | 40,107,602 |
| 4004003 | `E4004003` | LUK Crystal Ore | 20k | 5k–80k | 18.1k (17) | 1,444/10 | 75,176 |
| 4006000 | `E4006000` | The Magic Rock | 10k | 2.5k–40k | 9.1k (39) | 1,200/10 | 1,155,867 |
| 4010001 | `E4010001` | Steel Ore | 2k | 500–8k | 1.7k (38) | 1,067/11 | 134,186 |
| 4010000 | `E4010000` | Bronze Ore | 2k | 500–8k | 1.9k (23) | 739/8 | 108,003 |
| 4020003 | `E4020003` | Emerald Ore | 3k | 750–12k | 2.5k (11) | 739/10 | 267,192 |
| 4010004 | `E4010004` | Silver Ore | 3k | 750–12k | 3.2k (13) | 714/13 | 30,617 |
| 4020008 | `E4020008` | Black Crystal Ore | 10k | 2.5k–40k | 9.5k (65) | 609/6 | 1,611,261 |
| 4004001 | `E4004001` | Wisdom Crystal Ore | 20k | 5k–80k | 19k (4) | 594/9 | 590,565 |
| 4020006 | `E4020006` | Topaz Ore | 5k | 1.2k–20k | 4.6k (8) | 527/5 | 806,728 |
| 4004002 | `E4004002` | DEX Crystal Ore | 20k | 5k–80k | 20.3k (12) | 512/4 | 189,677 |
| 4004004 | `E4004004` | Dark Crystal Ore | 40k | 10k–160k | 37.2k (59) | 475/3 | 1,163,356 |
| 4020001 | `E4020001` | Amethyst Ore | 3k | 750–12k | 2.6k (15) | 441/9 | 71,674 |
| 4020007 | `E4020007` | Diamond Ore | 10k | 2.5k–40k | 9k (41) | 424/6 | 600,096 |
| 4020000 | `E4020000` | Garnet Ore | 3k | 750–12k | 3k (12) | 369/7 | 273,148 |
| 4010003 | `E4010003` | Adamantium Ore | 3k | 750–12k | 3k (13) | 344/8 | 42,291 |
| 4010006 | `E4010006` | Gold Ore | 5k | 1.2k–20k | 4.7k (14) | 343/8 | 107,234 |
| 4020002 | `E4020002` | AquaMarine Ore | 3k | 750–12k | 2.5k (19) | 338/7 | 163,933 |
| 4010005 | `E4010005` | Orihalcon Ore | 5k | 1.2k–20k | 4.7k (15) | 333/7 | 140,453 |
| 4020004 | `E4020004` | Opal Ore | 5k | 1.2k–20k | 4.2k (8) | 270/5 | 617,702 |
| 4004000 | `E4004000` | Power Crystal Ore | 20k | 5k–80k | 18.4k (37) | 246/7 | 378,467 |
| 4020005 | `E4020005` | Sapphire Ore | 5k | 1.2k–20k | 4.4k (11) | 165/4 | 508,100 |
| 4010002 | `E4010002` | Mithril Ore | 3k | 750–12k | 2.9k (23) | 160/8 | 128,448 |
| 4030011 | `E4030011` | Omok Piece : Pig | 5k | 1.2k–20k | 5k (11) | 129/3 | 6,902 |
| 4005002 | `E4005002` | DEX Crystal | 250k | 62.5k–1M | 226.5k (23) | 9/1 | 33 |
| 4005001 | `E4005001` | Wisdom Crystal | 250k | 62.5k–1M | 213.6k (24) | 7/1 | 32 |
| 4005004 | `E4005004` | Dark Crystal | 450k | 112.5k–1.80M | 405k (29) | 4/1 | 36 |
| 4021003 | `E4021003` | Emerald | 35k | 8.8k–140k | 32.1k (11) | 4/2 | 17 |
| 4021007 | `E4021007` | Diamond | 120k | 30k–480k | 114.3k (10) | 4/3 | 14 |
| 4021008 | `E4021008` | Black Crystal | 120k | 30k–480k | 111.3k (25) | 4/3 | 42 |
| 4011006 | `E4011006` | Gold Plate | 55k | 13.8k–220k | 47.4k (22) | 3/1 | 37 |
| 4021000 | `E4021000` | Garnet | 35k | 8.8k–140k | 36.9k (5) | 3/2 | 27 |
| 4021001 | `E4021001` | Amethyst | 35k | 8.8k–140k | 33.3k (23) | 3/2 | 68 |
| 4021002 | `E4021002` | AquaMarine | 35k | 8.8k–140k | 34.2k (7) | 3/2 | 13 |
| 4021005 | `E4021005` | Sapphire | 55k | 13.8k–220k | 56.4k (8) | 3/2 | 17 |
| 4021006 | `E4021006` | Topaz | 55k | 13.8k–220k | 50.2k (13) | 3/2 | 15 |
| 4001017 | `E4001017` | Eye of Fire | 5M | 1.25M–20M | 4.19M (12) | 2/1 | 17 |
| 4005000 | `E4005000` | Power Crystal | 250k | 62.5k–1M | 223.7k (14) | 2/2 | 17 |
| 4011000 | `E4011000` | Bronze Plate | 25k | 6.2k–100k | 23.8k (28) | 2/1 | 54 |
| 4011001 | `E4011001` | Steel Plate | 25k | 6.2k–100k | 22.8k (19) | 2/1 | 38 |
| 4011002 | `E4011002` | Mithril Plate | 35k | 8.8k–140k | 31k (16) | 2/1 | 31 |
| 4011003 | `E4011003` | Adamantium Plate | 35k | 8.8k–140k | 29.4k (8) | 2/1 | 13 |
| 4011004 | `E4011004` | Silver Plate | 35k | 8.8k–140k | 32.1k (12) | 2/1 | 22 |
| 4011005 | `E4011005` | Orihalcon Plate | 55k | 13.8k–220k | 50.2k (8) | 2/1 | 16 |
| 4021004 | `E4021004` | Opal | 55k | 13.8k–220k | 52k (17) | 2/1 | 31 |
| 4005003 | `E4005003` | LUK Crystal | 250k | 62.5k–1M | 231.6k (18) | 1/1 | 27 |
| 4031179 | `E4031179` | Piece of Cracked Dimension | 5M | 1.25M–20M | 5.20M (1) | 1/1 | 5 |
| 4001019 | `E4001019` | Orbis Rock Scroll | 10k | 2.5k–40k | 9.2k (27) | — | 63 |
| 4001020 | `E4001020` | Eos Rock Scroll | 10k | 2.5k–40k | 9k (33) | — | 56 |
| 4030010 | `E4030010` | Omok Piece : Octopus | 5k | 1.2k–20k | 4.3k (8) | — | 14,593 |
| 4030013 | `E4030013` | Omok Piece : Bloctopus | 5k | 1.2k–20k | 4.5k (14) | — | 85,355 |
| 4030014 | `E4030014` | Omok Piece : Pink Teddy | 5k | 1.2k–20k | 4.2k (12) | — | 961,570 |
| 4030015 | `E4030015` | Omok Piece : Panda Teddy | 5k | 1.2k–20k | 4.3k (12) | — | 725,096 |
| 4030016 | `E4030016` | Omok Piece : Trixter | 5k | 1.2k–20k | 5k (19) | — | 39 |

### 3.1 Player-held ETC with no price (109 ids, NEEDS PRICE)

These are the hoards the audit found — feathers, powders, monster cards, quest drops. They cannot be tickers
until someone prices them in `etc.yaml`. The powder case is the sharpest: 4007001 Magic Powder (White) is held
1,445 units by 11 characters and has no pool row at all.

| id | ticker | name | players | bots |
|---|---|---|---|---|
| 4000647 | `E4000647` | Mutant Tiru Feather | 13,433/6 | 0 |
| 4000648 | `E4000648` | Mutant Tiguru Feather | 2,580/6 | 0 |
| 4000712 | `E4000712` | Gravi Stonegar Guard | 2,142/6 | 0 |
| 4001006 | `E4001006` | Flaming Feather | 1,980/14 | 183 |
| 4001126 | `E4001126` | Maple Leaf | 1,662/14 | 213,626 |
| 4007001 | `E4007001` | Magic Powder (White) | 1,445/11 | 41,275 |
| 4000458 | `E4000458` | Red Heart | 1,238/2 | 0 |
| 4000644 | `E4000644` | Mutant Slime Bubble | 948/3 | 0 |
| 4000021 | `E4000021` | Leather | 832/10 | 292,478 |
| 4007004 | `E4007004` | Magic Powder (Yellow) | 707/6 | 70,744 |
| 4007000 | `E4007000` | Magic Powder (Brown) | 667/5 | 2,458 |
| 4007007 | `E4007007` | Magic Powder (Black) | 585/6 | 2,269 |
| 4003004 | `E4003004` | Stiff Feather | 555/4 | 10,503,772 |
| 4007003 | `E4007003` | Magic Powder (Green) | 547/7 | 1,832 |
| 4000024 | `E4000024` | Fire Boar's Tooth | 424/1 | 415,660 |
| 4007002 | `E4007002` | Magic Powder (Blue) | 341/4 | 14,387 |
| 4000018 | `E4000018` | Firewood | 334/3 | 292,479 |
| 4007006 | `E4007006` | Magic Powder (Red) | 329/3 | 1,785 |
| 4130004 | `E4130004` | One-Handed Blunt Weapon Forging Stimulator | 308/6 | 13,792 |
| 4130000 | `E4130000` | Gloves Production Stimulator | 272/5 | 2,098 |
| 4130001 | `E4130001` | Shoes Production Stimulator | 242/5 | 25,438 |
| 4000525 | `E4000525` | Kid Mannequin's Bunny Suit | 235/1 | 0 |
| 4007005 | `E4007005` | Magic Powder (Purple) | 230/4 | 55,766 |
| 4130006 | `E4130006` | Two-Handed Axe Forging Stimulator | 225/4 | 35,541 |
| 4260005 | `E4260005` | Intermediate Monster Crystal 3 | 215/1 | 53 |
| 4000020 | `E4000020` | Wild Boar Tooth | 174/1 | 4,211 |
| 4000443 | `E4000443` | Cracked Hourglass | 173/2 | 109 |
| 4000528 | `E4000528` | Latest Hits Compilation | 162/1 | 0 |
| 4000541 | `E4000541` | Mango Bubble | 148/1 | 0 |
| 4000073 | `E4000073` | Solid Horn | 144/2 | 985 |
| 4031203 | `E4031203` | Halloween Candies | 133/1 | 4,912 |
| 4000011 | `E4000011` | Mushroom Spore | 129/3 | 2,382 |
| 4000195 | `E4000195` | Seedling | 118/2 | 1,029 |
| 4000000 | `E4000000` | Blue Snail Shell | 111/4 | 2,794 |
| 4003005 | `E4003005` | Soft Feather | 111/2 | 10,499,894 |
| 4130010 | `E4130010` | Wand Production Stimulator | 106/6 | 29,176 |
| 4030009 | `E4030009` | Omok Table | 98/2 | 2,332,072 |
| 4000459 | `E4000459` | Black Armor Fragment | 97/1 | 0 |
| 4020009 | `E4020009` | Piece of Time | 97/2 | 8 |
| 4000529 | `E4000529` | Greatest Oldies | 86/1 | 0 |
| 4130018 | `E4130018` | Armor Production Stimulator | 85/2 | 189 |
| 4000026 | `E4000026` | Lupin Doll | 83/1 | 723 |
| 4010007 | `E4010007` | Lidium Ore | 82/3 | 16,931 |
| 4000457 | `E4000457` | Red Helmet | 78/1 | 0 |
| 4000646 | `E4000646` | Mutant Tino Feather | 77/6 | 0 |
| 4000245 | `E4000245` | Dragon Scale | 75/4 | 2,545 |
| 4000244 | `E4000244` | Dragon Spirit | 74/4 | 2,532 |
| 4000017 | `E4000017` | Pig's Head | 73/3 | 306 |
| 4000539 | `E4000539` | Cherry Bubble | 73/1 | 0 |
| 4130012 | `E4130012` | Bow Production Stimulator | 72/5 | 260 |
| 4000016 | `E4000016` | Red Snail Shell | 66/3 | 35,115 |
| 4000543 | `E4000543` | Jr. Pepe Keychain | 66/1 | 0 |
| 4260004 | `E4260004` | Intermediate Monster Crystal 2 | 66/1 | 51 |
| 4130016 | `E4130016` | Knuckler Production Stimulator | 63/7 | 21,711 |
| 4130020 | `E4130020` | Bottomwear Production Stimulator | 63/5 | 4 |
| 4000540 | `E4000540` | Melon Bubble | 62/1 | 0 |
| 4000010 | `E4000010` | Slime Bubble | 55/2 | 29,825 |
| 4130014 | `E4130014` | Dagger Forging Stimulator | 50/4 | 3,011 |
| 4000542 | `E4000542` | Yeti Keychain | 48/1 | 0 |
| 4000196 | `E4000196` | Wooden Board | 42/1 | 323 |

…and 49 more (all in the live query, none held by more than 15 units).

145 further ETC ids are untradeable or quest items (SKIP).

## 4. Totals

| class | ON today | NEEDS PRICE | DARK | SKIP |
|---|---|---|---|---|
| scrolls | 107 | 136 | 304 | 209 |
| use | 16 | 22 | 0 | 0 |
| ETC | 55 | 109 | 0 | 145 |
| **total** | **178** | **267** | **304** | **354** |

