# Ticket 80: port nutnnut/Cosmic's bot-scale stability fixes (1.5.2)

Status: closed 2026-10-25 (done in 1.5.2, commit 922510f92, migration 023).
"nutnnut/Cosmic <sha>" in each port commit and in the patch notes). Research notes: the fork is mostly its own bot
system (not portable); these core fixes were found running 700+ bots and apply to our 3,000.

## Take (each small; strip their `server.bots`/BotManager hooks; soak on TEST with full bots)

1. Lock order in `Character.updateActiveEffects`, `cancelBuffStats`, `cancelAllBuffs` soft branch: prtLock before
   effLock (AB-BA hang at 714 bots). `c9ba4f4da`, `8a2d4adaf`. Our `Character` diverges ~3.3k lines: place by hand.
2. Thread-safety: `Inventory.list()` returns a snapshot (`6050d1d9d`); `getSummonsValues()` copy on a
   ConcurrentHashMap (`ab7253d97`); `getFriendshipRings` sorts a copy (`51daa42f1`); `PartySearchEchelon` mutating a
   HashMap under a read lock, crash every ~15 s (`dfe03664a`).
3. Disposed characters: `sendPacket` drops packets to a disposed character instead of an NPE that kills a map
   broadcast (`b1707d11b`); `HiredMerchant.broadcastToVisitors` purges logged-out visitors (`0a9f08bf3`); null guard in
   `EquipSlot.getFromTextSlot` (stops an item-pickup dupe, `56a43dcea`); then the warp-unwind part of `ae5753df0`
   adapted to our bots.
4. Quest-status bloat: don't save empty NOT_STARTED rows (`87fe36eda`); check live with `SHOW INDEX` and add the
   characterid indexes and `drop_data(itemid, chance, dropperid)` from their migrations `028`/`029`/`032` if missing.
5. Shutdown autosave skipped while the world shuts down (`e61767a42`); Warrior skill id constants (`6d68a5d77`).
6. Nice to have: cache failed monster loads in LifeFactory (log once per bad id, `3c094c453`).

## Skip

Their bot system, custom gameplay (godly stats, scroll bonus, progressive EXP, tradeable untradeables, level 250),
the Wind Walk change (reverses a deliberate HeavenMS fix), the QuestActionHandler distance removal.

## Later (1.6.x ideas)

Save-path performance (skip unchanged quests/inventory), map/boot performance (lock-free monster index, equip-stats
disk cache), GM commands (`!warp <map name>`, `!zakumpq` staging, `!deletechar`, `!expdebug`), and ideas from their
bot system (LLM memory, market web page, A* nav with swim/slip/rope).

## Done when

Each fix has a test (fail-before/pass-after where possible), the full suite passes, and a 3,000-bot soak on TEST
runs for several hours with no deadlock, no new ERRORs and no queststatus growth from empty rows.
