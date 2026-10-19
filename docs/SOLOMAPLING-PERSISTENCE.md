# SoloMapling playerbots: persistence merge

Branch `solomapling-persistence`, cut from `baseline-20261018-r1`. Not deployed anywhere.

This branch brings the owner's enhanced SoloMapling fork (`joshhmann/SoloMapling`, upstream
`47b61b53` plus 45 commits) onto the live baseline. The baseline already carried upstream `47b61b53`. This is the
difference between the two.

## What it adds

- **Bots that persist.** They have a durable store, a roster of players who log in and out on a daily curve, and
  resume exactly where they stopped. Some quit and some come back. Bots never go through `saveCharToDB`, so the
  baseline's save guards stay as they are.
- **Chat through any OpenAI-compatible endpoint,** with a scripted fallback. Personas, memory notes and gossip.
  Fuzzy names ("hey Resme"). Whispers reach bots. Menus open in the NPC window (`scripts/npc/botmenu.js`).
- **Navigation that learns from bot failures and from real players' jumps.** It also learns where players stand.
  The data lives under `cache/bot-nav/`.
- **Value-safe bot trade, shop and drop paths** (`BotHoldings`, conjured drops), plus a hard 6-member cap on
  parties enforced under the party lock.

## Off by default: what disabled means

Nothing here runs unless `-Dsolomapling.enabled=true` and `-Dsolomapling.template-id=<id>` are set
(`MapleRootSolo.isEnabled()`). With SoloMapling off:

- Movement learning, the name reservation on character creation, the whisper "did you mean" hint and the bot
  shutdown/save sweep are all skipped.
- `!roster` and `!brain` stay unregistered, like the other bot commands.
- No bot exists, so every `isBot` branch (trade, shops, autosave, drops) takes the player path.

Changes that apply whether or not bots are on (none of them changes the wire format):

| File | Change |
| --- | --- |
| `Party`, `World` | The 6-member cap is enforced inside `addMember` under the party lock. A join that loses the race for the last slot gets the normal "party is full" message. |
| `Inventory.removeItem` | Read, modify and remove happen under the tab's lock (same result as `removeSlot`). |
| `Character` ring lists, `PacketCreator.addRingInfo` | Ring getters return snapshots, and the ring info is written from one snapshot, so the count always matches the rings that follow it (fixes a `ConcurrentModificationException`). The bytes on the wire are unchanged. |
| `ItemInformationProvider`, `XMLDomMapleData` | Caches are thread-safe for concurrent WZ reads. |

## MapleRoot adaptations made during the merge

- **Bots are identified only by their headless `BotClient`,** as on the baseline. Stored bots use character ids
  `1000000000 + slot`. Store sweeps also require an id in that range, so the console character is never stored.
- **64-bit EXP:**
  - Offscreen progression keeps `long` EXP.
  - `botstore.Exp` is `BIGINT UNSIGNED`.
  - Bot level-ups use MapleRoot's `ExpTable`.
- **MapleRoot's own code is kept as it was:**
  - Drop logic, spawn rate (the offscreen reward budget reads MapleRoot's rate) and `getSlotMax(Character, ...)`.
  - The drop packet layout. Drop delays still use the baseline's timer-based overloads.
  - `isAlreadyPickedUp`, the attack broadcast, and `HiredMerchant.isPersistent()`.
- **Merchant and shop bot purchases** go through `BotHoldings`. A player buying from a bot stall is unchanged.

## Database

`database/migrations/003-solomapling-bots.sql` adds `botstore`, `botroster` and `botmemory`, and records itself in
`mapleroot_integration_migrations`. It only adds tables, and applying it twice changes nothing.

`003-solomapling-bots.rollback.sql` drops the three tables. It also deletes the items stored under bot ids
(`>= 1000000000`); real characters are untouched.

As with 001 and 002: stop the game and take a backup first.

`005-bot-real-stats.sql` (real bots, tickets 05/06; 004 belongs to the daily check-in series) adds nullable stat,
HP/MP, AP, SP and `BuildLevel` columns to `botstore` and a `botskills` table. A NULL `BuildLevel` means the bot has
never been built: the next time it loads, its build is back-filled once from its level and job and written with its
next save. Bots of a job that is not a stock job (MapleRoot custom classes, Evan, 5th tiers) are never built and keep
NULL. Applying it twice changes nothing. `005-bot-real-stats.rollback.sql` drops the table and the columns; bots keep
their identity, level, job and items. Without 005 the server still runs and simply stores no builds.

Builds (`soloMapling/ArtificialPlayer/BotProgressionSystem`): stock Explorers, Cygnus Knights and Aran up to level
200. AP goes to each class's primary stat with the secondary held at its requirement. SP follows a per-job priority
list. Jobs advance at 10 (8 for magicians), 30, 70 and 120. A skill Skill.wz lacks is skipped, and the WZ ids are used
where `constants.skills` disagrees with them (Power Strike, Slash Blast, Iron Body, Nimble Body, Keen Eyes and two
Night Walker skills).

## Configuration

These are the keys added to `ServerConfig`. All are optional, and the defaults apply when a key is missing:

- **Population:** `BOT_DIRECTOR_*`, `BOT_ROSTER_*`
- **Chat model:** `BOT_LLM_*`
- **Decision model:** `BOT_DECIDE_*`
- **Menus:** `BOT_MENU_WINDOW`

Automatic population still needs `-Dsolomapling.autopopulate=true`. `BOT_POPULATION_SCALE` (default 1.0) shrinks the
seed waves (training cohorts and town presence) for a pilot on a small host; 0.03 seeds about 3%.

Bot template loads are limited to three at a time. `loadCharFromDB` holds one pooled connection while its nested
loads take more, and with MapleRoot's 10-connection pool, ten unbounded parallel spawns deadlocked the pool. That
happened on the first isolated boot.

The bot data files (names, dialogue packs, item pools) are read from the source tree. Run the server from the full
checkout, as the live installation already does.

## Verification so far

- `mvn test` with CI's `config.example.yaml`: 2,310 tests, 0 failures, 1 skipped (a nav case that needs real WZ).
  The baseline alone runs 2,055, all passing.
- **Tests adapted to MapleRoot:**
  - Bot ids moved above `1000000000`.
  - A real player in a test now has a real client.
  - Offscreen yields are doubled, to match MapleRoot's spawn rate.
  - EXP is 64-bit.
  - Reflection is used for MapleRoot's private `Character()` constructor.
  - The drop packet is built without a delay.
- **Isolated headless boots on 164, 2026-10-19:**
  - Setup: `/opt/mapleroot-dev/persistence-test`, schema `mapleroot_persist` on 3307, login port 8489, channels
    7985-7992, `BOT_POPULATION_SCALE: 0.03`, flat curve with a peak of 40, nice 19 and a 70% CPU cap.
  - Migration 003 applied twice, the second run a no-op. `botstore.Exp` is `bigint unsigned`.
  - Boot 1 (seeding): 751 bots in 73 s, no errors. 818 bots stored, 89 enrolled in the roster, 5,685 items under bot
    ids, 0 bot rows in `characters`.
  - Boot 2: the roster loaded with 89 members and the director's boot fill brought 40 online. The shutdown recorded
    all 40 sessions (login and logout times, activity, personality, persona).
  - Boot 3: 40 logins. Of the 20 that had been online in boot 2, 17 ended the session on the map they logged out on,
    and the rest had walked to a neighbouring map. The other 20 were roster members logging in for the first time.
    The shutdown saved everything and went offline in 12 s.
  - Fixed along the way:
    - The pool deadlock (see Configuration).
    - `Client.announceBossHpBar` threw for the shared `BotClient`. The bot client now ignores boss HP bars. The
      exception during map dispose had also stalled shutdown forever on `Channel.finishedShutdown()`.
  - Notes for whoever runs the next boot:
    - The test config had `SHUTDOWNHOOK: false`; live has `true`. Bots are saved on shutdown only when it is `true`.
    - Exit status 143 is the JVM leaving on SIGTERM after a clean shutdown.
    - `RankingLoginTask` throws a `NullPointerException` on the cloned test data (also on the town test, not on
      live). It predates this branch.
- **Not yet done:**
  - A population and scale run on MapleRoot data.
