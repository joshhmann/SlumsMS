# Ticket 113: NX ↔ meso exchange ("like forex")

Status: **round 2 built** (branch `161-exch2`, merged into `release-1.6.1`): placing in game (`@nx`) and from the site
while logged in, payouts straight to an online character, a preview/confirm step, 1% fee from each side, staff
reversal, house-funded regulars. Behind `-Dslums.exchange` (default off): **on on TEST**, **off in the 1.6.1 release
conf** (owner 2026-10-28). Checks: `!qa run xc-1`, auto-QA `t-xc-1`. Round 1 was the scaffold (branch `161-exchange`).
The 1.6.1 headline (docs/roadmap/1.6.1.md, row "NX ↔ meso exchange (headline)").

## Decisions (owner, 2026-10-28)

1. **Fee: 1% of the mesos side, paid by both sides** (owner 2026-10-27). Built as `feeBps=100` charged to *each*
   side of a fill: the seller receives the fill's mesos less 1%, the buyer pays the fill's mesos plus 1%. Both go to
   the FEES bucket, a meso sink that stays on the books (2% of every fill's mesos in total). A buy reserves its own
   fee when it is placed (FEEHOLD) and gets back what it did not use. If the owner meant 1% *in total* (0.5% each),
   that is `-Dslums.exchange.feeBps=50`.
2. **Settlement: fills settle at any time against escrow** (as the scaffold already did).
3. **Placing in game too.** Built: `@nx` (the item-exchange `@exchange` stays as it is), and the site can place while
   the player is logged in on the character the order names. The escrow leaves the LIVE character (mesos) and the
   account's live NX (the CashShop object) under the character's save lock, in the same transaction as the ledger
   (see "Placing while logged in" below). Payouts to an online player land in the live character at once; offline
   payouts as before.
4. **Limits: the defaults.** 100-50,000 NX in lots of 100; 5 open orders, 20 a day, 10 s apart; expiry 72 h.
5. **Regulars: 3, quoting ±3/6/9% around the rate, 500 NX each, funded only from a staff house wallet**
   (`-Dslums.exchange.houseAccount`, default 0 = none = the regulars stay idle). They never create money (every
   order escrows from their HOUSE, which only the house account's wallet funds) and **are excluded from the official
   rate**: no fill with a regular on either side counts (the scaffold still counted player-vs-regular fills).
6. **Confirmation: a site confirm step** (the Website session builds the UI; contract below: preview -> confirm with a
   60 s token). Staff: `!exchange halt`, plus **manual reversal** `!exchange reverse <fillId>` (GM level 4, only while
   halted, logged), which writes compensating ledger entries.

## Owner decisions so far (2026-10-27)

- An **order book**: players post buy and sell orders at their own price in **mesos per 1,000 NX**. Orders match when
  prices cross. The rate floats freely.
- Traders are players plus a few **regulars** posting small orders so the book is never empty. Regulars never move
  the official rate alone.
- Settlement goes through the game server only. The website never touches the database.

## Design

### Order book (pure, `soloMapling.Economy.exchange.OrderBook`)
- Limit orders only. Every order is for a whole number of NX at a price in mesos per 1,000 NX.
- Price-time priority: best price first, then the oldest order. A fill trades at the resting (maker) order's price.
- A fill of `q` NX at price `p` moves `floor(q * p / 1000)` mesos. The buyer pays exactly what the seller receives, so
  a fill never creates or destroys anything.
- A buy order holds `floor(nx * limit / 1000)` mesos when placed and pays each fill from that. The hold always covers
  the rest at the limit, so it can never go negative (floor(a) + floor(b) <= floor(a + b), and a fill is never priced
  above the limit). Whatever is left when the order closes goes back to the buyer. Sizes come in lots of 100 NX, so
  no fill rounds down to 0 mesos.
- Self-trade prevention ("cancel newest"): if the best counter order is the taker's own, the rest of the taker is
  cancelled. Fills it already made stand.
- An expired resting order never fills. Matching closes it as `EXPIRED`.

### Money: ledger, escrow, payout (`Ledger`, `Exchange`, `ExchangeStore`)
Every NX and meso the exchange touches moves as a posting from one bucket to another. Each posting is one row of the
append-only `exchange_ledger`.

| Bucket | What | Id |
|---|---|---|
| WALLET | the player's own money: `accounts.nxCredit` (NX) and `characters.meso` (mesos) | account / character |
| ESCROW | what an open order holds: NX for a sell, mesos for a buy (held, not spendable) | order |
| PAYOUT | settled money waiting to reach a wallet | account (NX) / character (mesos) |
| HOUSE | a regular's float | regular |
| FEES | mesos taken as fees (0 while the fee is 0) | 0 |

1. **Place**: wallet → escrow, the order row, every fill the order makes at once, and their settlement, all in **one
   transaction**.
2. **Fill**: escrow → payout for both sides in that same transaction: the seller's NX to the buyer, the buyer's mesos
   to the seller (minus any fee). A fill never needs the counterparty to be offline.
3. **Cancel, expiry or a full fill**: whatever is left in the order's escrow → its owner's payout.
4. **Collect**: payout → wallet. Offline accounts through the database (every tick and before each place); a logged-in
   player straight into the live character (every tick, and right after their own place or cancel). A wallet is
   never filled past 2,147,483,647; anything that doesn't fit stays in payout.

### Placing while logged in (round 2; `LiveWallet`, `GameLiveWallet`)
The game keeps mesos (`Character.meso`) and NX credit (`CashShop.nxCredit`) in memory and saves them as absolute
values. A live place therefore runs, for the account's playing character (not a bot, not an owned alt):
1. take the character's monitor. `Character.saveCharToDB` is `synchronized`, so no save (autosave, the channel
   change's, the logout's) can run until the place is done;
2. check it is still the live character: logged in, on its channel's player list, not in transition (channel change
   or logout), not in the Cash Shop; else `TRY_AGAIN`;
3. save the character, so the database holds exactly what memory holds;
4. take the escrow from memory, exactly or nothing (`Character.takeMesoExact` / `CashShop.takeNxCredit`, atomic with
   `gainMeso` / `gainCash`; a buy also takes its fee reserve);
5. one transaction: lock the account row and require `loggedin = 2` (a channel change or logout writes that column and
   so waits for the lock; one already in flight refuses with `TRY_AGAIN`), write the order, escrow, fills, ledger and
   the wallet's **delta** (`meso = meso - x`, `nxCredit = nxCredit - x`, guarded never negative), commit;
6. if the transaction failed, give the escrow back in memory; payouts the place earned at once land in memory.

A payout to an online player is the same the other way round: the transaction (payout → wallet delta) first, then the
same amount into memory; if memory refuses after the commit (a gain raced the cap) a second transaction puts it back in
payout.

Why no dupe:
- **Save after escrow**: memory already lacks the escrow, so a later save writes the right value. Spending everything
  and logging out cannot bring it back (tests `saveAfterEscrowCannotDupe`).
- **Crash between the memory change and the commit**: the database still holds the saved values and no escrow; memory
  is gone with the process. A crash after the commit leaves saved values minus the escrow plus the escrow
  (`aCrashBetweenTheMemoryChangeAndTheCommitLeavesBothConsistent`, `aCrashAfterTheCommitLosesNothing`, and the MySQL IT
  that fails every write statement of a live place).
- **Channel change**: in flight it refuses (`loggedin` is 1, or the character is off its channel); right after, the new
  channel loads the wallet without the escrow, and refunds land there (`channelChangeNeverDupes`).
- **Logout mid-place**: the logout's save waits for the lock, then saves memory with the change
  (`logoutMidPlaceWaitsAndSavesTheChange`).
- **Site and game at once**: every place goes through the one synchronized `Exchange` and the character's lock
  (`concurrentSiteAndInGamePlacingConserves`: 6 threads, site places, previews and confirms, cancels, payouts,
  spending in memory; totals in memory and in the database the same, nothing negative).
- Lock order is always exchange, then character, then database rows; nothing that holds a character's lock calls the
  exchange.

Every write is all or nothing. The in-memory book changes only after the store has accepted the batch. If the store
refuses or crashes, the book rolls back to the copy taken before.

### Where the NX and mesos come from (decided; reasoning)
- **NX**: the account's `nxCredit` (CashShop `NX_CREDIT`, type 1). It is account-wide already, and it is what the Cash
  Shop spends.
- **Mesos**: the inventory of a **named character on the same account**. A buy pays from that character, and a sell's
  proceeds go to that character. This is the simplest choice that maps onto a real column (`characters.meso`). Mesos
  in storage are per account and world, and using them would add a second meso source and a second set of game paths
  that change them (storage NPC, trades). The site already identifies characters.
- **Wallets change only while the whole account is offline.** The game keeps `meso` and `nxCredit` in memory and
  saves them as absolute values (`Character.saveCharToDB`, `CashShop.save`). Writing a wallet under a logged-in
  player would be overwritten on the next save, which means a dupe or a loss. The store locks the account row
  (`SELECT loggedin ... FOR UPDATE`) in the same transaction and refuses if `loggedin != 0`. The login sets that
  column with an `UPDATE`, which waits for the lock, and loads the character only afterwards, so a login can never
  read a wallet that a transaction is halfway through changing. Logout saves the character before it sets
  `loggedin = 0`. The game's own view (any character of the account in a world) is checked too. Escrow moves out of
  the wallet **before** the player can log in. So a logged-in player spending all their mesos can never spend the
  escrow, and the save-on-logout cannot bring it back (test `spendingWhileEscrowedCannotDupe`).
- Round 1 consequence (replaced in round 2): placing needed the player logged out (`LOG_OUT_FIRST`). Now a logged-in
  player places through the live character (above); `LOG_OUT_FIRST` remains only when the order names a different
  character than the one being played, or for the offline path when the game says the account is in a world.

### Recovery
At start-up `Exchange.recover()` rebuilds the book from the open orders and reconciles two things:
- every stored exchange balance must equal the ledger summed for that bucket;
- every open order's escrow must equal what the order holds, and no closed order may hold escrow.

Any mismatch **halts** the exchange. It logs an error and fixes nothing automatically. Order, fill, sequence and batch
ids continue from the database.

### Official rate (`OfficialRate`)
The official rate is the VWAP (price x NX / NX) of the fills in the last 24 h. **Every fill with a regular on either
side is left out** (owner 2026-10-28). There is no
rate with fewer than 3 counted fills. It is published in `exchange-book.json`. It does **not** feed `NxRate` yet:
`NxRate` is unchanged.

### Regulars (`Regulars`)
Regular *i* bids at reference x (1 − i x spread) and asks at reference x (1 + i x spread), with a fixed size. The
reference is the official rate, else the mid of the book, else nothing: regulars never make up a first price. Their
orders take escrow from HOUSE buckets. **Only the house account's wallet funds a house**: `!exchange fund <regular>
<nx|meso> <amount>` from a character logged in on `-Dslums.exchange.houseAccount` (live), or `Exchange.fundHouse` from
that wallet offline; `!exchange unfund <regular>` cancels its orders and pays its whole house back. So regulars never
mint NX or mesos. A regular that can't afford a quote doesn't post it. Defaults: 3 regulars, but idle while no house
account is named.

### Site interface (`ExchangeSite`): the same file channel as the Buy/Sell board
- Inbox `exchange-requests.json` (the site writes it). Request types are `preview` (character, side, price, nx),
  `confirm` (token), `cancel` (orderId), `mine`, `book` and `trades`. Each has an `id`, `account` and `createdAt`.
  A bare `place` is refused (`BAD_REQUEST`, "use preview then confirm").
- **Character by name (1.6.1)**: `preview` and `mine` take `characterName` instead of, or together with, the numeric
  `character`. The name is resolved inside the request's account (case-insensitive, exact, main world 1 only); a name
  or id that is not on that account or not in the main world answers `NOT_OWNER`; both given but different characters
  answers `BAD_INPUT`; neither on a preview is `BAD_REQUEST`. On `mine` the character (either form) is an optional
  filter, so only that character's orders are returned (no filter = all of the account's, as before).
- **Pickup speed (1.6.1)**: the server stats `exchange-requests.json` every 2 s (mtime and size) and processes it when
  it changed; the full 15 s poll (`pollSec`) remains as a fallback and re-reads the file even if the stamp looks the
  same. Every request id is still answered once, so neither path reprocesses. Worst case from the site writing a
  request to the server answering it is about 2 s (the outbox is written in the same tick), so a 60 s confirm token
  has the whole window for the player's click plus the site's own polling.
- **Confirm step** (owner 2026-10-28): `preview` checks everything a place checks (size, price, limits, the right
  character if logged in, funds if logged in), simulates the match against the book as it is, moves nothing, and
  answers `data = {token, expiresAt, side, price, nx, takes:{asset, amount}, feeBpsEachSide, matchNow:{nx, mesos, fee,
  youGet:{asset, amount}}, restsNx, cancelledBySelfTrade}`. The site shows that and, on the player's yes, sends
  `confirm` with the token within 60 s (`-Dslums.exchange.confirmSec`). The token is the order's request id, so the
  same token confirmed twice returns the same order. An unknown, expired or other account's token is
  `TOKEN_EXPIRED` (tokens live in memory: a restart expires them). The game's `@nx` uses the same two steps.
- Outbox `exchange-responses.json` (private) keeps the last 500 answers, matched by id. Outbox `exchange-book.json`
  (public, no names) holds the depth (20 levels a side), the official rate and the last 50 trades.
- Each request id is answered once. A request older than 10 minutes is answered `STALE` and not acted on. A place is
  idempotent by request id, even across restarts.
- Error codes: `BAD_REQUEST`, `STALE`, `HALTED`, `UNAVAILABLE`, `LOG_OUT_FIRST`, `INSUFFICIENT_FUNDS`, `NOT_OWNER`,
  `NOT_FOUND`, `LIMIT_OPEN`, `LIMIT_DAY`, `COOLDOWN`, `DUPLICATE`, `ERROR`, `TOKEN_EXPIRED`, `TRY_AGAIN` (changing
  channel, logging out or in the Cash Shop).
- The site is trusted to send the logged-in account id, the same way it is for `posts.json`. The server checks that
  the character belongs to that account.

### Player command: `@nx`
`@nx` (market summary), `@nx sell|buy <NX> <price>` (preview; prices take `1.2m`/`500k`), `@nx confirm`, `@nx orders`,
`@nx cancel <id>`, `@nx book`. Text only, so it works anywhere and auto-QA drives it.

### GM command
`!exchange status|book|halt [reason]|resume|fills [n]|fund <regular> <nx|meso> <amount>|unfund <regular>` is for GM
level 3 and up; `!exchange reverse <fillId>` needs GM level 4 and a halted exchange. `@exchange` is still the players'
item exchange NPC; the command passes these sub-commands to the NX exchange when a GM types them. **Halt** is the
emergency stop: no new orders, no fills, no expiry and no payouts. Cancels still work, because escrow to payout stays
inside the exchange.
**Reverse** undoes one fill with compensating ledger postings (`why = REVERSE`; nothing is edited): the NX back to the
seller, the mesos and both fees back to the buyer, into their payouts (or HOUSE for a regular). Each amount comes from
where the fill put it: the payout if it is still there, else the wallet it was collected into (that player must be
logged out: `PLAYER_ONLINE`). Once per fill (`ALREADY_REVERSED`); every halt, resume, fund and reverse is logged.

### Database: migration `038-nx-exchange` (+ `.rollback.sql`)
Four new tables and no changes to existing ones: `exchange_orders`, `exchange_fills`, `exchange_escrow` (every
exchange-held balance, `CHECK amount >= 0`) and `exchange_ledger` (append-only; the code only INSERTs, and a test
checks for that). The rollback drops them. **Only roll back once every balance is 0**, otherwise held NX and mesos are
lost. It is not yet listed in any `docs/release/<version>.conf`: add it to `1.6.1.conf` when that release is cut.

### Switches (JVM properties only; no config.yaml keys)
| Property | Default |
|---|---|
| `slums.exchange` | false |
| `slums.exchange.lotNx` / `minNx` / `maxNx` | 100 / 100 / 50,000 |
| `slums.exchange.minPrice` / `maxPrice` (mesos per 1,000 NX) | 1,000 / 100,000,000 |
| `slums.exchange.feeBps` (from each side) | 100 |
| `slums.exchange.maxOpen` / `perDay` / `cooldownSec` | 5 / 20 / 10 |
| `slums.exchange.expiryHours` | 72 |
| `slums.exchange.regulars` / `regularSpreadBps` / `regularNx` | 3 / 300 / 500 |
| `slums.exchange.houseAccount` (0 = none: regulars idle) | 0 |
| `slums.exchange.confirmSec` | 60 |
| `slums.exchange.rateWindowHours` / `rateMinFills` | 24 / 3 |
| `slums.exchange.pollSec` | 15 |

The inbox and outbox folders are the board's (`-Dslums.board.inbox` / `-Dslums.board.outbox`).

## What's built

| File | What |
|---|---|
| `src/main/java/soloMapling/Economy/exchange/OrderBook.java` | the pure order-book engine |
| `Ledger.java`, `ExchangeStore.java` | buckets, postings and the store contract |
| `JdbcExchangeStore.java` | the MySQL store: one transaction per batch, offline lock, guarded debits |
| `MemoryExchangeStore.java` | the same rules in memory, with crash injection and a model of the game's login/save |
| `Exchange.java` | place, cancel, expire, collect, halt, recover, reconcile, regulars, fund house |
| `OfficialRate.java`, `Regulars.java` | the VWAP and the regulars' quotes |
| `ExchangeSite.java`, `ExchangeService.java` | site contract, poll thread, outbox files; started from `Server` when switched on |
| `client/command/commands/gm3/NxExchangeCommand.java` | `!exchange status/book/halt/resume` (through `gm0/ExchangeCommand`) |
| `database/migrations/038-nx-exchange(.rollback).sql` | the four tables (round 2 needs no schema change: FEEHOLD and REVERSE are new values in existing VARCHAR columns) |
| `LiveWallet.java`, `GameLiveWallet.java` | round 2: a logged-in player's wallet, the character's save lock |
| `ExchangeChat.java`, `client/command/commands/gm0/NxCommand.java` | round 2: `@nx` |
| `ExchangeQa.java`, `soloMapling/qa/ExchangeScenarios.java`, `Probes160.nxExchange` | round 2: `!qa run xc-1`, auto-QA t-xc-1 |
| `Character.takeMesoExact/giveMesoExact`, `CashShop.takeNxCredit/giveNxCredit` | round 2: exact, atomic memory changes (`gainCash` is now synchronized) |

Not built yet: the site pages (the Website session builds them on the contract above), a trade PIN, the `NxRate`
feed. Not checked on a real client: the `@nx` lines and the meso/NX display after a live place or payout (the code
sends the meso stat update; the NX credit shows in the Cash Shop).

## Tests
- `OrderBookTest` (23): crossing, price then time priority, partial fills on both sides, self-trade prevention, cancel,
  double cancel and wrong owner, expiry (including an expired maker met during matching), bad orders, deep copy and
  restore order, depth. Two property tests: random trading over 6 seeds x 4,000 steps checks NX and meso conservation
  and invariants after every step, and the buy hold covers the rest under any split of fills.
- `ExchangeTest` (24): escrow on place, settlement into payouts and then wallets, partial fills, `LOG_OUT_FIRST`, a
  login that races the game check (refused by the store), insufficient funds rolling the book back, `NOT_OWNER`, size
  and price rules, limits (open/day/cooldown), an idempotent request id (also after a restart), cancel once only
  (including 16 concurrent cancels), expiry, halt, a **crash at each of the 10 steps** of a matching place (store and
  book unchanged), crashes during cancel and collect, restart recovery with unique ids, a tampered balance halting
  start-up, **spending while escrowed cannot dupe**, fees, the wallet cap, regulars (funding, quotes, the rate), and 6
  threads trading concurrently with conservation.
- `OfficialRateTest` (4), `RegularsTest` (3), `ExchangeSiteTest` (3, now through preview + confirm),
  `ExchangeMigrationTest` (3).
- Round 2, `ExchangeLiveTest` (18): live buy and sell escrow (database = memory after), not enough in memory,
  another character / the Cash Shop refuse, fills/cancels/expiry paying an online player into memory, a give refused
  after the commit going back to payout, the wallet cap online, **save after escrow**, **a crash at every step of a
  live place** (memory given back, database alone consistent), **a crash after the commit**, **channel change** (in
  flight, racing the write, and after), **logout mid-place**, **site + game placing concurrently**, preview/confirm
  (nothing moves, once per token, expiry, another account), `@nx` end to end, a fee raised after placing, reversal
  (halted only, once, from wallets when collected, `PLAYER_ONLINE`), regulars idle without a house and funded only from
  it, excluded from the rate. Every test checks NX and meso totals in memory and in the database, the ledger
  reconciliation and no negative balance. `ExchangeScenarioTest` (xc-1 registered, refuses when off).
- `JdbcExchangeStoreIT` now 5: plus a live place/payout/reversal on MySQL (`loggedin` 2 vs 1, delta = memory, both
  fees, reversal from wallets, reconcile) and a crash at every write statement of a live place. Passed on MySQL 8.0 on
  2026-10-28.
- `JdbcExchangeStoreIT` (3, opt-in; never part of `mvn test`) runs against a throwaway MySQL 8 schema. It applies 038
  twice; runs place/fill/cancel/collect with exact balances; checks `NOT_OWNER`, `INSUFFICIENT_FUNDS` and a NULL
  `nxCredit`; refuses a logged-in account **and waits for an uncommitted login, then refuses**; crashes at every write
  statement of a matching place (24+, all rolled back); recovers; halts on a tampered balance; and rolls back twice.
  Run it with
  `mvn -o test -Dtest=JdbcExchangeStoreIT "-DargLine=-Dslums.exchange.it.url=jdbc:mysql://127.0.0.1:PORT/DB?user=..&password=..&allowPublicKeyRetrieval=true&useSSL=false"`.
  It passed on MySQL 8.4 on 2026-10-27.

## Open questions for the owner (round 1; answered 2026-10-28 in "Decisions" above, except 9 and 10)

1. **Fees**: none, or a sink? **0% at launch** (the fee machinery exists: `feeBps`, paid by whoever receives the
   mesos, into a FEES sink). Consider 1% later as a meso sink once the soak shows volume.
2. **Order size**: **100 to 50,000 NX in lots of 100**. 1,000 NX is about one Cash Shop outfit piece.
3. **Limits and cooldowns**: **5 open orders, 20 a day, 10 s between orders** per account.
4. **Expiry**: **72 h**, with 0 meaning good till cancelled. Expired escrow returns automatically.
5. **Settlement timing**: built as: fills at any time, wallet changes only while logged out, and placing needs a
   logout. Alternative: allow placing in town while logged in, through the game object (more code and dupe risk).
   **Keep it as built for 1.6.1.**
6. **Confirmation**: trade PIN or in-game confirm? **In-game confirm the first time an account uses the exchange, then
   none**, or an optional PIN. *Not built.*
7. **Staff undo window**: **none automatic**. Halt and a ledger-based manual reversal by staff (a reversal is new
   postings, never edits). *Not built.*
8. **Regulars**: how many and how wide? **3 regulars, ±3/6/9% spread, 500 NX each**. Where does their float come from
   (a staff account's NX and mesos, owner's call), and how much? Their quotes start only once the book has a price.
9. **The site's trust**: the site sends the account id, as the board already does. OK, or should the game re-check a
   signed site session token (ticket 105's auth API)?
10. **Deleted characters**: mesos waiting in payout for a deleted character stay there for staff to move. OK?

## TEST soak plan (multi-day, on TEST only)
1. Deploy with `-Dslums.exchange=true` (only on TEST), apply 038, and make sure `!exchange status` shows `running`.
2. Day 1, scripted: 3 or 4 test accounts place crossing and non-crossing orders through `exchange-requests.json`. Check
   each response, `!exchange book`, and the database (sum of nxCredit + meso + exchange_escrow per asset, before and
   after, equal).
3. Fund 2 regulars from a staff account and set `-Dslums.exchange.regulars=2`. Check the quotes appear around the
   mid and that the official rate ignores regular-to-regular fills.
4. The dupe checks: log in while an order is escrowed and spend mesos, then fill it from another account, then log
   out and check the payout arrives once. Try placing while logged in (`LOG_OUT_FIRST`). Log in at the same moment as
   a place.
5. Restart TEST mid-soak with open orders and payouts waiting. Recovery must report 0 mismatches and the book must
   match. Kill -9 once during a batch of places.
6. `!exchange halt` then `resume`. Let some orders expire over a night.
7. Daily: run the reconcile query (ledger sums against `exchange_escrow`), the conservation sums, and grep the log for
   `NX exchange`. Pass criteria: 3 days without a mismatch, a lost meso or NX, or a stuck payout.
