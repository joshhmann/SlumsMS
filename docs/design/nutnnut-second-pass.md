# nutnnut/Cosmic second pass (2026-10-25): companions, owned alts, party chat, buy requests

Ticket 87. Source: nutnnut/Cosmic (AGPL-3.0, like us), `origin/dev` 77e0c5bc9 (2026-08-28, still their latest), plus
`master`, `experimental` and the side branches. Ours: `152-integration` (live 1.5.2 plus docs). This was read only:
nothing from their tree was built or run, and nothing of ours was changed except this doc and ticket 87. The first-pass
reports (scratchpad `nutnnut-bots-report.md`, `nutnnut-misc-report.md`, 2026-10-24) are not repeated here.

Credit "nutnnut/Cosmic <sha>" in every port commit and in the patch notes. Never port their web admin
(`BotWorldGraphWebServer`, port 8089, no auth).

**Scope update during the pass (owner, through the coordinator):** owned alts are in 1.6.0. Rules:
- Same account and same world only.
- Summoned with `@botme <alt>` and sent away with `@dismiss <alt>`. With no name, the command lists your alts. "come
  here" in party chat brings them over.
- Alts share the cap of 5 companions with bot friends.
- They log off when the owner does. There is no offline autopilot.
- They level only with the owner (party EXP). No auto job advance and no auto questing.
- Items move only through normal, logged Trade. There is no shared bag.
- They join PQs and bosses as full party members.

The ranked list and section 1 cover this.

**Name clash to keep in mind when reading their code:** their `@botme` hands *your own* character to autopilot while
you log off (commit 7ed634eb5). Their summon command is `@spawnbot` (gm3). Our `@botme <alt>` means their
`@spawnbot <alt>`. The owner's no-offline-autopilot rule means we do not want their `@botme` at all.

## Take for 1.6.0, ranked

| # | What | Their sha (credit) | Ours today | Effort | Risk |
|---|---|---|---|---|---|
| 1 | **Owned-alt identity, login and save path** (ticket 13). Load the alt with a headless client that *does* save. Do one atomic register per character id. A real login on an online alt takes the live object over and removes all bot state. On dismiss or owner logout, save once and finish the save before the account can log in again. See 1.1. | `2608eba61` (spawn), `535152753` + `Character.newClient` hook (takeover), `e9b063ea2` (one save, not two), the double-register fix in `kb_bot_double_register_botpop_race.md` (`spawningBotIds` + `registryLock`) | Not started. `Character.saveCharToDB` returns early for any `BotClient` (`Character.java:9633`, `:9651`), so an alt loaded the way our bots are would **never save**. `Character.newClient` (`:956`) has no bot cleanup hook. | M-L | **High** (lost progress, item rollback or duplication) |
| 2 | **Follow anywhere, including PQ and boss instances.** Same-map tailing and portal travel are already ours. Add: compare map *objects*, not map ids; hold outside an instance you are not registered in; warp fallback after N failed hops; a dead companion lies at the tomb while a teammate in that map is alive. | `ca61a4470` (hold outside instance), `c12a06fc1` (follow travel, `MAX_FOLLOW_TRAVEL_HOPS=4`), `55bf77af6` (PQ death) | `GCFollow.tick` compares `getMapId()` only (`GCFollow.java:99`). A bot in the *static* map 103000800 counts as "same map" as a leader inside the KPQ instance of 103000800, and channels are ignored. OPQ already warps to `leader.getMap()` (`OPQOrchestrator.java:475-481`), which is the right primitive. | M | Med |
| 3 | **Party invite accepted or declined like a person**, for alts and bot friends. An alt accepts only its owner. A bot friend weighs affinity, busy, level gap and personality, and declines in words or ignores the invite. Raise the follower cap to 5 per player. | `81bac4cd7` (`BotSocialManager.acceptsInvite`, `BotSocialMath.respondToOffer`) | We are ahead on the feelings side: `BotRecruitManager.rollPartyAsk` has chances, grudges and a decline cooldown, and `GuildBossRuns.freeForInvite` exists. But `FOLLOWER_CAP = 30` is world-wide (`BotRecruitManager.java:26`); we need a per-player cap of 5 (alts plus friends). | S-M | Low |
| 4 | **Party chat hook** (ticket 85). Feed `MultiChatHandler` type 1 into the bot dispatcher, and route the answer back on the channel it came in on (party, map or whisper). | `17411d918` (hook), `ad590e280` (`ReplyChannel` routing) | Buddy and guild chat are hooked (`MultiChatHandler.java:63`, `:70`); party chat is not. Our convo, triage, LLM and SayDo stack is far ahead of theirs; only the hook and the reply channel are new. | S (hook) + M (PQ coordination lines) | Low |
| 5 | **Command vocabulary for alts** (the owner's list). Movement and formations: follow, stay, patrol, move here, spread, stack, snap. Supplies: need pots, ammo, return scrolls. Spare books and scrolls go to whoever can use them. "any upgrades?" compares against the owner's gear. Everything is offered through Trade. | `8fd725404` (formations), `6cb8d3517` (pot share), `60ab5b4b4` (ammo), `fdd8ac137` (`BotOfferManager`), `e3b8a12d5` (scrolls), `6ac55d218` (books), `2019ceb0e` (`BotTradePacing`) | Ticket 14's parser covers follow, stay and grind. `BotGearPlanner` exists. No sharing yet. | M | Med (trade dupes: vanilla `Trade` only) |
| 6 | **KPQ worker roles** (alts and bot friends). Their step machine, adapted: bots do Cloto's coupon questions and stage 1-4 grunt work, the human does the leader duties. | `6067c1cee` (`BotScript*`), `700e97f34` (KPQ stage 1), `7db09aa39` (stage 5) | KPQ exists only as lobby chat; `vb-3` stages a pretend party. **Do not copy their shortcuts:** `exchangeCoupons` removes coupons and adds the pass directly, skipping Cloto; stage 5 calls `eim.giveEventReward` directly. Use the real NPC and portal path through our bot NPC-script runner. | M | Med |
| 7 | **Zakum prequest worker roles.** In the run a player leads, bots split the 7 key rooms by party index and drop the keys at the leader's feet, and one bot gives hints. | `335e3c38a` (`BotZakumPqRun.tickSupervised`), `f19fed568`; KB `kb_bot_zakum_prequest.md`, `kb_bot_pq_supervised_run_stucks.md` | `!zakumpq` staging plus `ZakumPrequest` (1.5.2). No bot roles. Their `claimShareAndLeave` grid-claims Breath of Fire without talking to Aura: don't copy that. | M | Med |
| 8 | **Megaphone buy requests** (ticket 86). Their code does **not** read megaphones. Reuse the parts that stand alone: the `S>`/`B>` grammar, the price parser, the item-name resolution rule (exact name, else exactly one substring hit, else reject), a shout bus where `claim()` lets exactly one bot answer, and a 2-6 s think before acting. | `722719702` (`BotMarketGrammar`), `b00401fda` (m prices), `5342622c3` (claim), `bankShoutDecision` in `BotShoutTradeManager` | `ChatTriage.TRADE` recognises trade talk; `ConvoEntities` resolves item names; `@price` and HaggleBrain do the pricing. The new part is the hook in `UseCashItemHandler` and the whisper reply. | M | Med (meso faucet: see 2) |
| 9 | **zMass wall rule (graph v70).** Client-true walls: parse foothold layer and zMass, add `wallCollidesForMover`, and stop scanning walls on ground walks. Ship it in its own rc after rc1, not in rc1. | `43b9f06c8` | We still use `Foothold.isCollidableWall` (`Foothold.java:121`; `BotPhysicsEngine.java:2707`; `BotNavigationGraphProvider.java:738`), `GRAPH_VERSION = 69`. Divergence ledger NN-011. | M | **High** (every map's collision changes; cold graph cache) |
| 10 | Small fix found on our side: `BotBlockList` is an unsynchronised `ArrayList` mutated from every bot's trade tick (`isBlocked` calls `removeExpiredEntries`; `addToBlockList` adds). | — (their audit pointed at it) | A race: ConcurrentModificationException or a lost entry. It is rare, because trades are rare. | S | Low; fine in 1.5.x |

Not urgent: nothing here is a live bug in 1.5.2. Two notes are worth flagging:
- Item 1's save gap would **lose alt progress** if someone wires alts through `BotClient` the way roster bots are
  wired.
- `MapleMap.respawn` has a dormant bug (section 4) that would fire if anyone ever calls `setSpawnRateMultiplier`.

## 1. Companions and owned alts

### 1.1 Owned-alt identity, login and save (owner rules mapped)

**Their path (origin/dev):**
- **Who may control a character:** `BotOwnershipService.ensureCanControl` (`bots/BotOwnershipService.java:57`)
  allows a registered owner (`bot_owners` table, `@registerbot`, `dfb2140e5`) and auto-registers any character on the
  same account.
- **Summon:**
  - `BotManager.spawnBotForOwner` (`BotManager.java:635`) refuses a character a real player is playing ("currently
    being played by a real player").
  - Otherwise `loadOfflineBot` (`:1109`) builds `new BotClient(world, channel)`, runs
    `Character.loadCharFromDB(charId, botClient, true)`, sets the account id, restores diseases from buff storage,
    spawns the alt at the nearest safe ground point, and calls `newClient(botClient)`.
  - The alt joins the party through `partyUp` (`:720`), a server-side join with no invite packet.
- **Single session:** a real login on a character that is online as a bot goes through stock
  `PlayerLoggedinHandler`. It finds the live `Character` in `PlayerStorage` and calls `player.newClient(c)`. Their
  `Character.newClient` (`Character.java:999-1002`) calls `BotManager.cleanupBotRuntimeState(this)` when the old
  client was a `BotClient` and the new one is not. There is no reload from the DB, so the bot's state at that moment
  carries straight over and no race window opens.
- **The races they hit:**
  - **Double register** (`kb_bot_double_register_botpop_race.md`, fix on `experimental` 2026-06-20). Two workers
    spawned the same character id at the same time, giving two tick tasks on one character that thrashed between
    positions, plus an NPE at shutdown through `offerBestGearToSibling`. Fix: a `spawningBotIds` set
    (add-or-return-false, then re-check), and the dedup sweep plus the add done inside `synchronized(registryLock)`
    for *every* register path.
  - **Double save at logout** (`e9b063ea2`). `saveCharToDB(true)` followed by `disconnect()` saved twice from two
    threads about 0.6 s apart. Fix: let `disconnect` do the only save.
  - **Save deadlocks** (`6014449cc`, `c6051a15f`, `76f66275d`). The real cause was missing indexes, not
    parallelism. We have those indexes: migration 023.
- **Real-player takeovers vs population bots** (`4da112fa5`). Both have `owner == bot`, so they needed an explicit flag
  (`commandAutopilot`) to keep breaks, gacha, the FM and auto-scrolling off real characters.
  `kb_bot_self_owned_owner_assumptions.md` lists the class of bug "code assumes the owner is a separate online human".

**What it means for us (owner rules → design):**

| Owner rule | Their equivalent | What we build |
|---|---|---|
| Same account, same world | `ensureCanControl` auto-allows same account; any world | `accountid == owner.accountid AND world == owner.world`. No `bot_owners` table and no `@registerbot`/`@takebotowner` (they are GM tools and cross-account). |
| `@botme <alt>` / `@dismiss <alt>` / list / "come here" | `@spawnbot` (gm3), chat `dismiss X`, party-invite-an-offline-name spawn (`59f9bc091`) | gm0 `@botme`, `@dismiss`; the party-invite spawn is a nice extra (inviting your offline alt summons it). "come here" goes through the party-chat hook (rank 4) plus a ticket 14 order. |
| Shares the 5-companion cap | None (no cap) | A per-player companion counter (alts plus bot friends) replaces the world-wide `FOLLOWER_CAP`. Reserve a slot at accept time, as `BotRecruitManager.tryReserveFollower` already does. |
| Logs off when the owner does; no offline autopilot | `handleOwnerOfflineOrDead` sends bots to town and they keep playing | Hook the owner's disconnect: for each alt, stop the BotSM, leave the party, **save once**, remove it from storage. All of this must finish before the account's login lock releases (see the race below). |
| Levels only with the owner; no auto job advance or questing | Autopilot job advance and quest piggyback | An alt runs only the companion policy: follow, fight, loot, resupply on request. No director, no brain day-plan, no job or quest errands. Job advance stays a manual act when the owner logs in on the alt. |
| Items only through logged Trade | Pot and gear shares already trade; their trade guards (`BotTradePacing`, offer reservations) | Every alt item move is a vanilla `Trade` with the owner, a party member or another alt, and gets our trade log plus `GmAudit`-style logging. No direct inventory moves, not even between sibling alts (stricter than the first-pass report suggested). |
| PQs and bosses as full party members | Members registered by stock `registerParty` if on the leader's map | Same: the alt must be on the leader's map at start (rank 2 makes it so). Expedition boss rooms register members one by one, so check each boss's entry script. |

**Our specific gaps (each needs a test):**
1. **Saving.** `Character.saveCharToDB(..)` returns early when `isBot(this)` (`client instanceof BotClient`), and
   `Character.isLoggedin()` stays false for bots, so autosave skips them too. An alt therefore needs one of these:
   - an `OwnedAltClient` that extends `BotClient` and is excluded from the early return, or
   - a per-character `ownedAlt` flag that the save guard reads.

   Then `loggedIn = true` so the hourly autosave covers it. Roster bots keep their own `BotStore` path.
2. **Identity checks.** About 56 call sites use `BotHelpers.isArtificial(id)` (id above `BOT_BASE_ID = 1e9`). An alt
   has a normal id, so all of them treat it as a human. Mostly that is right: director logout protection
   (`BotPopulationDirector.mayLogOut` counts it as a real member, which is fine) and party EXP. About 191 sites use
   `isBot(Character)` (the `BotClient` check), so all of those treat it as a bot. Audit every call that decides chat
   routing ("a bot sender is never read as giving orders"), affinity, the per-player caps, auto-QA's real-player
   counts, and `isVisibleRealPlayer`. The cleanest approach is a third predicate, `BotHelpers.isOwnedAlt(chr)`, and an
   explicit decision at each call site that matters. This is their "self-owned owner assumptions" bug class, the other
   way round.
3. **Takeover on login.** Our `Character.newClient` (`Character.java:956`) has no hook. Today nobody can log into an
   online bot, because roster ids are never on a real account. With alts it becomes possible as soon as the owner logs
   off main and picks the alt quickly. Port their hook: when the old client was the alt's headless one, stop its BotSM
   and GCMovement sessions and drop it from the companion registries. Also, `PlayerLoggedinHandler.java:135` reads
   `player.getClient().getHwid()` from the live character, and a `BotClient` has no HWID, so take it from the login
   session instead.
4. **The save race the rules create.** Because alts log off when the owner does, the owner's disconnect and every
   alt's disconnect save at the same moment. If the owner logs back in on an alt before that alt's save has committed,
   there are two possible outcomes:
   - The alt is still in `PlayerStorage`. Login takes over the live object (fine, once gap 3 is fixed).
   - The alt has been removed but its save is still in flight. `loadCharFromDB` then reads stale rows. A traded item
     comes back (a dupe) or disappears.

   The fix: run the alt's save synchronously inside the owner's disconnect, before
   `updateLoginState(LOGIN_NOTLOGGEDIN)`, or have login refuse a character id that has a save pending (a
   `pendingSave` set that stock `CharacterSaveService` registrations check). Test: owner logs off, then logs in on the
   alt within 1 s, 50 times, and the inventory checksums match.
5. **Atomic register.** Use their `spawningBotIds` plus registry-lock shape, so that a double `@botme` or an invite
   racing a summon can never start two BotSMs on one alt.

### 1.2 Piece by piece

| Piece | What it is (theirs) | Ours | Port / adapt / skip | Effort | Risk |
|---|---|---|---|---|---|
| Party join | `partyUp` (`BotManager.java:720`): server-side join, leaves any old party, creates the leader's party if there is none, never grants ownership | Bots accept through `BotPartyQueue` and `BotPartyCommands.botAcceptPartyInvite` (real invite flow) | **Keep ours** (a real invite reads as a person). Use a server join only for `@botme` alts. | S | Low |
| Invite accept/decline | `BotSocialManager.acceptsInvite` (`:363`, `81bac4cd7`): an owned bot accepts only its owner; a self-owned bot runs `BotSocialMath.respondToOffer` (level gap, `partyMistakeChance`, `acceptChance`, then DECLINE or IGNORE by chattiness) | `BotRecruitManager.rollPartyAsk` (chances, grudge, cooldown, sub-10 block), `GuildBossRuns.freeForInvite` | **Adapt:** add their "ignore" outcome (silence, as a person does) and the level-gap leech rule to ours; extend from the chat ask to the plain UI invite from any player; feed in ticket 60's busy state. | S-M | Low |
| Chat recruit | `PARTY_REQUEST_PATTERN` (about 19 aliases), nearest eligible bot, 8 s cooldown, "nah im good / soloing rn"; the bot asks "wanna party?" first (`e676eba6a`) | Dialogue-driven recruit (Social, Training and Follower bots), ChatTriage | **We are ahead;** take only their alias list as test input. | S | — |
| Affinity | `BotFamiliarityManager` samples party time, but **nothing reads it** | `BotAffinitySystem`, couples, rivals | **Skip** (ours is real). | — | — |
| Command dispatcher | `BotChatManager.handleChat`: 135 patterns, target by exact name, slot or 2+ letter prefix (`BotCommandParser.java:48`) | Ticket 14 `BotCommandParser` (port of the cosmic_agents parser plus a piece-of-name tier) | **Keep ours.** Add verbs for alts only (rank 5). Bot friends get a small, natural set (come, wait, follow, go ahead, brb) and may refuse. | M | Low |
| `PartyAutopilotState` | `bots/PartyAutopilotState.java` (30 LOC, `6769a7525`): the leader publishes `{mapId, dest, leader, objectiveByCharId}`, members sync, detours (job advance, ZPQ, ToT, quest, FM, gacha) preempt without forking the plan, idle-leech over-level rule (`b0297b1d9`), party-wide breaks | No shared plan; `TrainingBot` and `BotPartyLogic` | **Mostly skip for 1.6.0.** Alts have no autopilot and companions follow the human. Keep the shape for ticket 60, when bot-only groups get a joint day plan ("grind together"). Take the **idle-leech rule** only if the owner wants bot parties to look like leech-free play. | M (later) | Med |
| Logout protection | `partyHasRealPlayer` guards 6 logout and break paths | `BotPopulationDirector.mayLogOut` already refuses while partied with a real player (`:445-451`), `FollowerBot` never logs out | **Have it.** | — | — |
| Graceful leave | `6a743aeb6`: goodbye in party chat, leave, disconnect 5-15 s later | `BotLogout` goodbye beat | **Have it.** | — | — |
| Follow across maps | `syncFollowMap` (`:6674`): hold outside an instance the bot is not registered in (`ca61a4470`); `tickFollowTravel` with legal portals, at most 4 hops; then a direct warp | `GCFollow` (our GreenCatMS port of their code, 400 ms poll) plus `GCTravel` (ferries, rides) | **Port the instance and channel parts** (rank 2): compare `MapleMap` identity; if the leader is in an EIM, go in only through the event's own path; a member that misses the start waits at the door, as a person would. Add a hop-count fallback warp only for normal maps. | M | Med |
| Same-map catch-up | Teleport if beyond 8000 px Manhattan distance, or 600 px out of bounds; ×2 while grinding | Driver plus the UNSTUCK hop, frozen-air watchdog (`GCMovementDriver.java:752`) | **Have it.** | — | — |
| Formations | `FORMATION_PATTERN`: stagger, split, random, stack, spread, left, right, tight(30), loose(120), `snap <px>` (`8fd725404`) | None | **Port** for alts; bot friends just spread naturally (a small offset per slot). | S | None |
| PQ death | `55bf77af6`: lie at the tomb while a live event mate is in the map, then the script's `revivePlayer` path | `BotDeaths` | **Port the rule**, so a companion never warps out of an instance alive or dead except the way a player would. | S | Low |
| KPQ | `BotScript` step machine (`6067c1cee`), stage 1 coupons (`700e97f34`), stage 5 reward (`7db09aa39`); stages 2-4 just follow | Lobby chat only | **Adapt** (rank 6) without their NPC bypasses. | M | Med |
| OPQ / LPQ | **No code** | `OPQOrchestrator` (bots run OPQ with each other) | Make the orchestrator accept a human leader with alts and friends as the workers. Its `leader.getMap()` warp is the instance primitive rank 2 needs. | M | Med |
| ZPQ | `BotZakumPqRun` (`335e3c38a`): supervised mode, room partition by index, key courier, spokesbot hints | `!zakumpq` | **Adapt** (rank 7). | M | Med |
| Item sharing | "need pot": the richest same-map sibling gives a third through a trade (`6cb8d3517`); ammo for bows and crossbows only (`60ab5b4b4`); rocks between bots (`0aa374f30`); scrolls and books to the cohort (`e3b8a12d5`, `6ac55d218`); offer, then yes/no, then trade (`BotOfferManager`); reservations, `BotTradePacing`, slots restored after a cancel | `BotGifts` only | **Port the offer flow** for alts (rank 5), always through `Trade`. Bot friends offer spare pots only ("want some pots?"), never on command. | M | Med |
| Gear sharing | "any upgrades?" (`RECOMMENDED_GEAR_PATTERN`), recipient order self, owner, sibling (`fdd8ac137`) | `BotGearPlanner` (cut-down port of their optimiser) | **Port** the recipient-inventory compare onto `BotGearPlanner`, for alts only. | M | Low |

### 1.3 Conflicts with "bots act like people, never say they're AI"

Their stack is written for an *owner* who knows the bots are bots. These parts must not come over as they are:
- **Words that give it away:**
  - `INFO_REFUSAL_REPLIES` "ask my owner" (`BotChatManager.java:1868-1871`)
  - the status suffix "(last decided 6h ago: <reason>)" (`BotAutopilotManager.java:1390-1398`), which anyone nearby
    can trigger
  - the help line "commands: follow, stop, …" (`:1996`)
  - "taking it from here" / "autopilot time" (`@botme`)
  - the yellow notice "Bot 'X' spawned"
  - debug dumps into chat ("debug: route %s, atk speed…", `BotCombatManager.java:3273`, gated by owner, not GM level)
  - the "possible replies" overlay (`BotPrompt.showOptions`, `6fd0512a0`)
- **Behaviour that gives it away:**
  - instant obedience to a 135-pattern vocabulary from anyone in the party
  - passes appearing without Cloto ("Here's your pass!" after `exchangeCoupons`)
  - rewards with no NPC talk (stage 5, Breath of Fire)
  - bots answering stat queries ("inv", "stats", "exp") with machine-readable numbers
- **Their LLM guard is open:** the prompt forbids "I'm a bot", but the reject block in `BotLlmReplyManager.java:155-161`
  is commented out, so a reply that says it is still gets sent. Ours (content guard plus auto-QA judge) is stronger;
  keep it.

**Rules for us:**
- **Alts** are the player's own characters, so commands are fine. But replies go to party chat, which friends read, so
  they use the same human voice (lowercase, short, the say-do rule): "omw", "k waiting", "here u go".
- **Bot friends** take requests, not commands. They may refuse ("nah im grinding"), they leave when ticket 60's brain
  says their own plans win, and they never list commands or numbers.
- **GM-only:** debug output stays in `!botwhy` and the yellow GM notices, never in a bot's mouth.
- **Notices to the owner** (summon failed, cap reached) are system messages that never use the word "bot": "Jason is
  already with you.", "Your party is full."

## 2. Party chat and buy requests

**Party chat (ticket 85):**
- **Their hook:** `MultiChatHandler.java:69` calls `BotManager.handleChat(player, text, ReplyChannel.PARTY)`, after
  `world.partyChat` (`17411d918`).
- **Replies:** `botReply` (`BotManager.java:7585`) sends them back on the incoming channel:
  - PARTY uses `botSayParty`, which is `worldServer.partyChat`, and falls back to map chat with no party.
  - WHISPER goes back to the commander.
  - The channel routing came in with `ad590e280`.
- **Content:** almost all answers are templates. Their LLM answers only the bot's own owner, only when addressed by
  name, and only when no command matched. It runs on Ollama `qwen3.5:0.8b` with 24 tokens, one global request at a
  time (busy means the line is dropped), and no per-sender cooldown.
- **Verdict:** port only the hook and the reply-channel idea. The content is ours: ConvoSense, ChatTriage, routing
  (name > clear reply > …), SayDo, the LLM budget and guard. Also new for ticket 85:
  - PQ and boss coordination lines tied to real state ("wait for me" sets STAY, "ready?" answered from HP/MP and
    position).
  - Bot-only parties chatting among themselves at a low rate.
  - A cv-* auto-QA check.

**Megaphones and buy requests (ticket 86):**
- **Nothing in their tree reads megaphones**, smegas or item, avatar or triple megaphones. `UseCashItemHandler.java:294-371`
  and `:539` just broadcast.
- **Their shout trading is same-map map chat only.** `BotMarketGrammar` (309 LOC, `722719702`) and its `OFFER` regex
  require `>`, and the price must be the last token. "WTB ilbis" without `>` doesn't parse.
- **Price parsing:** `BotChatManager.parseMesoAmount` (`:3150`) handles "100k", "1m", "2.5m" and "1,500,000", clamped
  to `Integer.MAX_VALUE`.
- **Item names:** `ItemInformationProvider.getItemDataByName` is a case-insensitive substring scan: an exact name wins,
  exactly one substring hit wins, and several hits are rejected. No aliases ("ilbis"), no fuzzy matching.
- **Coordination:** `BotMarketShoutBus` (118 LOC) keeps shouts per map for 3 minutes, at most 32 per map, and its
  `claim()` lets exactly one bot answer (`5342622c3`).
- **Contact is a trade invite on the same map only. Their bots never whisper**, yet their shout suffixes say "pm me",
  and a stranger's whisper to a bot is dropped silently. Don't copy either.
- **Pricing** goes through their belief and consensus tables (`bot_market_*`, `BotMarketBook`, `BotMarketMath`). Skip
  all of that; we have `@price` and HaggleBrain.

**Ticket 86 design from this:**
1. Hook megaphone use (`UseCashItemHandler`, every megaphone type) into a `BuyRequestBus`. Parse with a grammar like
   theirs, but accept the forms people really megaphone: `B>`, `WTB`, `buying`, `lf`, with the price optional and
   anywhere in the line.
2. Resolve item names with `ConvoEntities` plus a small alias table (ilbis, zhelm, bwg…). An ambiguous name gets no
   answer, rather than a guess.
3. Holders are bots that have the item in their inventory or in FM shop stock (`BotHoldings`, `MerchantStock`),
   checked when the request comes in.
4. Use `claim()` so at most 1-2 bots answer, after a 20-90 s human delay. Re-check that the item is still held before
   whispering.
5. The whisper names a price inside the HaggleBrain band and ticket 56's caps. Then it is a meet and trade, or "my
   shop is in FM 6". A player's whisper back must reach that bot (today strangers' whispers do reach our bots, through
   `BotWhispers`).
6. No answers to sell megaphones, no answer for an item no bot holds, and a per-player limit (for example 3 answered
   requests an hour) against spam.

## 3. First-pass leftovers, re-checked against today's code

| Item | Today | Verdict |
|---|---|---|
| zMass wall rule (their v70, `43b9f06c8`) | Not ported: `isCollidableWall` is still used (`Foothold.java:121`), our `MapFactory.java:216-218` walks foothold layer and group but stores neither, ground steps still scan walls (`BotPhysicsEngine.java:794` → `:812`), `GRAPH_VERSION = 69` | **1.6.0, its own rc after rc1.** Port together: Foothold `layer`/`zMass`, `wallCollidesForMover` (theirs at dev `BotPhysicsEngine.java:2595`), no wall scan on ground walks, v70. Before and after on TEST: edge diff on real WZ, with Deity Room 600020100 and 193000000 as regression maps, and a real-client look. Pre-bake the cache. |
| Frozen-air watchdog (`7c4a2d517`) | **Have it:** `GCMovementDriver.java:752` (30 motionless airborne ticks), logged as `NavStuckLog.AIR_FROZEN`, plus a fall-off-map recovery | Done. |
| Blocked-position watchdog refinements (in `43b9f06c8`) | We have the older version (`BotNavigationManager.java:293`, counting only on `edgeReused`; `clearNavigationState` resets it at `BotMovementManager.java:209-212`). We have no committed-route system, so their 12-minute freeze can't happen here. | **Later**, and only if `NavStuckReport` shows repeated `*-pos` stalls with no GAVE_UP. S: drop the `edgeReused` gate and the reset. |
| Indexes | **Done in migration 023**, which covers more than "characterid only": `queststatus`, `questprogress`, `keymap`, `savedlocations`, `trocklocations`, `buddies`, `skillmacros` (characterid), `area_info(charid)`, **`inventoryitems(accountid)`** and **`drop_data(itemid, chance, dropperid)`** | Done. |
| Save path: signature skip + batching (`5e568f3ed`, `22b6a141e`, `feda7cf31`, `76f66275d`, `rewriteBatchedStatements`) | None: no signatures, no `rewriteBatched`, per-row `saveItemsCommon` (`ItemFactory.java:199`) | **Later (1.6.x),** after a 3,000-bot soak shows the hourly autosave or shutdown is slow. Alts add at most about 75 saving characters, which is nothing. Their own KB says the deadlocks came from the missing indexes, which we have. |
| `MapleMap` lock-free monster index (`481309326`, `98a3351c1`) | `getAllMonsters()` (`MapleMap.java:1381`) is still a read-lock scan into a `LinkedList` | **Later;** profile a 50-bot map in the soak first. |
| `TimerManager` pool (4 → `max(4, cores)`) | Still 4 (`TimerManager.java:62`) | **Measure in the soak** (queue depth); then it is a one-line change. |
| World-map export for Hidden Street | Not ported. Their `worldmap.html` and `/api/worldmaps` sit on the unauthenticated server. | **1.6.0, optional,** as part of ticket 78 phase 2's Maps section: reuse only the idea (maps laid over the `WorldMap.wz` continent images, ARRIVAL-closure reachability, scripted-warp and PQ edges) in a **static** export from our data. Never their server. M. |
| Perf KB lessons (cold `decide()` GC storm, warm-gate latch, `nanoTime` on HPET VMs, `jfr view hot-methods`) | We have `docs/perf/capacity-soak-2026-10.md` and nothing on these | **Now, as reading** for the soak checklist. I did not check the clocksource on 164 (that read was refused here); someone with the owner's OK should run `cat /sys/devices/system/clocksource/clocksource0/current_clocksource` there. |
| Tailscale host selection (`TAILSCALEHOST`, 100.64.0.0/10) | None | **Skip** unless a friend joins over Tailscale or ZeroTier. |

## 4. The side branches and the global-drops fix

| Branch / fix | What | Do we have it? | Right for us? |
|---|---|---|---|
| `fix-monster-crystal-level` (`cced80991`, = P0nk/Cosmic PR #275) | `getCrystalForLevel(getMonsterLevel(dropperid) - 1)` → no `-1`: an off-by-one put every crystal boundary one level high (a level-51 mob dropped Basic 1). | **Yes:** `ItemInformationProvider.java:2433` (came with the Cosmic backport, `fbccd29d8`). | Yes. It is a real bug fix, unrelated to our Black/Dark only-raise change (`43547f72b`). The first pass was wrong to call it an economy change. |
| `fix-queststatus-notstarted-bloat`: NOT_STARTED skip (`85249c8f0`) | Don't save empty NOT_STARTED rows | **Yes, and stricter:** `Character.java:5509 isEmptyQuestPlaceholder()` also keeps rows with a completion time or expiry; used at `:9986`; `QuestPlaceholderSaveTest`. | Yes. |
| same branch: quest 21010 (= Cosmic PR #334) | The reward gave 3 of 2000022 and 2000023 while the dialog promised 5 | **Yes:** `scripts/quest/21010.js:86-87` gives 5. | Yes. The WZ `Act` for 21010 is empty, so the reward is script-only. |
| same branch: `server_queue` (= Cosmic PR #314) | Remove `server_queue` from the character-delete table list, because newer Cosmic schemas dropped the table | **No:** `Character.java:3042` still lists it. Our base schema still creates it (`database/sql/1-db_database.sql:17473`). | **Later, harmless.** Needed only if a DB is ever built from current Cosmic SQL. The live DB was not checked (needs the owner's go). Fold it into a schema-cleanup pass. |
| `mob-rate` (`87f13e3da`, `d00f08af5`, `af1ac3fe3`, `0db01030a`) | A world mob-rate float, a per-spawn-point max, `!mobrate`/`!mobpoint`, bosses capped at 1 per point. The "full respawn calc" commit fixes only their own first commit. Stock is correct. | No | **Skip** (custom gameplay; the owner decides density). |
| Dormant bug found on our side | `MapleMap.respawn` runs `if (spawnPoint.shouldSpawn() \|\| getSpawnRateMultiplier() != 1)`. Any multiplier other than 1 bypasses `denySpawn`, `mobTime < 0` and the boss and one-per-point limits. The non-full branch also applies the multiplier twice. Nothing calls `setSpawnRateMultiplier` today, only `BotYieldCaps.java:418` reads it. | — | Fix before anyone uses the multiplier (their boss cap is the right idea). Not urgent. |
| Global-drops thread fix (P0nk #271, `dbf1a1bb3`) | `computeIfAbsent` returning an immutable list, and the map copies before shuffling | **Yes, stronger:** `MonsterInformationProvider.java:55` (ConcurrentHashMap), `:75-80`; copy at `MapleMap.java:848` | Yes. |

## 5. Their audit of us, re-checked

Their audits are of upstream SoloMapling:
- `docs/bot/solomapling-audit.md` (`5cdbbe867`, 2026-06-14)
- `kb_bot_solomapling_trade_behavior.md` (`e54a5d59f`, 2026-07-04)
- `kb_bot_solomapling_grind_v03.md` (`b66443739`, 2026-07-09)

All three were deleted from `dev` by `356fbfeb1`; read them at `356fbfeb1^`. They found no outright bugs. Claims that
still hold today:
- **"Decline permanently block-lists the partner"** (`BotTradeSM.declineTradeOffer`, `BotTradeSM.java:370-371`). It is
  not permanent: `BotBlockList.addToBlockList` defaults to 300 s. But **the list is an unsynchronised `ArrayList`**
  shared by every bot's trade tick (`BotBlockList.java:78-81`; `isBlocked` removes, `addToBlockList` adds). Fix: a
  ConcurrentHashMap keyed by (bot, trader) holding an expiry. S, 1.5.x or 1.6.0. Also, a companion should not
  block-list its owner or partymates for 5 minutes after one bad trade, so exempt party members.
- **Shop haggling, three strikes and you're banned** (`ShopOfferResponse.java:30` calls `shop.banPlayer`). The ban is
  permanent for the life of that shop. It is fine for strangers, but a friend or companion should get a cooldown, not
  a ban. Low priority; economy area, so it needs the owner's say.
- **FM buying takes the first acceptable stall and never compares** (`FMBot.shouldPurchaseItem`, `FMBot.java:847`,
  called per shop at `:532`). Still true. It is economy, so only if the owner asks (it touches "a busy FM").
- **Fragmented pricing (YAML, WZ, sine wave) and the naive `cost/successRate` scroll model.** Still true. Economy work
  is deprioritised.
- **Rest spots have no position safety** (break spots don't avoid ledges where mobs spawn), and there are no map
  archetype grind styles. These are behaviour ideas for ticket 60's grind and break options, not bugs. Later.
- **No longer true:** the recorded-packet movement replay (we moved to GCMoveSystem); "no gear optimisation"
  (`BotGearPlanner`); merchant skin variety (we have it).

## Method

- Three read-only research passes over their `origin/dev` (companions and PQ; chat and market; side branches and
  watchdogs), plus a direct read of their owned-alt load and login path (`BotManager.java:635-760`, `:1095-1135`,
  `Character.java:995-1010`, `PlayerLoggedinHandler.java:120-240`) and their KB on the register and save races.
- Every "ours" claim was checked against `152-integration` with grep and a read of the file.
- Not run: nothing was built or tested.
- No live DB queries. The clocksource check on 164 was refused.
