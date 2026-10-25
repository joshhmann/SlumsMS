# Ticket 68: audit MapleRoot's changes to stock Cosmic for broken v83 features

Status: audit done 2026-10-26, three fixes on `151-*` branches, owner questions and the config flag table below.
Live untouched; findings ship in 1.5.1 or later.

## Why

Three player-facing bugs reported in the first day of 1.5.0 were all MapleRoot changes to Cosmic, present since our
baseline commit `03d13cb21`, never touched by our own work and never covered by our QA:
- `EventInstanceManager.clearPQ` assumed an expedition: every non-boss PQ/event clear crashed (fixed `039d13164`).
- Face/hair id ranges in `ItemInformationProvider`/`ItemConstants`: salons and face/eye coupons said "no options"
  (fixed `1fe365733`).
- `ENABLE_PIC: false` (Cosmic default true): characters could not be deleted (config, on for 1.5.1).
There are likely more of the same kind.

## Do

1. Diff our tree against stock Cosmic (`/root/work/cosmic-triage/Cosmic`; the old `SlumsMS-cosmicdiff` worktree has
   earlier notes) for `src/main/java` (handlers, scripting, server, client, constants), `scripts/` (npc, quest, event,
   portal, reactor) and `config.yaml` defaults. Ignore our own SlumsMS packages (`soloMapling.*`) and files we
   changed on purpose (check `git log` for each: anything only touched by `03d13cb21` is MapleRoot's).
2. For each MapleRoot change: what it does, whether it can break a stock v83 feature (null assumptions, hard-coded
   id ranges that MapleRoot's own data exceeds, disabled flags, removed handlers), and a verdict: keep, fix, or ask
   the owner (anything that changes rates, economy or content).
3. Fix the clear bugs on `151-<topic>` branches with tests that fail before the fix; list the rest in this ticket.
4. Check config flags that differ from Cosmic's defaults and list them with what each does for players.

## Done when

A table in this ticket (file, change, risk, verdict, fix commit) covering every MapleRoot-only change, fixes merged
into release-1.5.1 with tests, and owner questions listed.

## How this pass was done

Three trees, read side by side: stock Cosmic `afba85827` (`/root/work/cosmic-triage/Cosmic`), the MapleRoot import
`03d13cb21` (`/root/work/cosmic-triage/base`) and `origin/release-1.5.1` `5b628dda9` (`/root/work/SlumsMS-cosmic-audit`).
A line that only `03d13cb21` touched is MapleRoot's; anything later is ours and is out of scope except where noted. Four
read-only passes (handlers + server, tools + constants, `scripts/`, `config.yaml`) produced candidates, and every
candidate that became a fix was re-read against both trees by hand first. One candidate from the passes did not
survive that check (mob-skill chance roll, see "Checked and not broken").

## Fixed (1.5.1)

| branch | commit | what was wrong | what a player saw |
|---|---|---|---|
| `151-script-refs` | `053f0f78d` | `scripts/npc/9900000.js:23` opened `"stylist"`, the file is `scripts/npc/STYLIST.js`; `AbstractScriptManager:49` checks `Files.exists` on a case-sensitive Linux filesystem, `NPCScriptManager:65` builds `"npc/" + name + ".js"`, and a miss falls back to the NPC's own script (`NPCScriptManager:141`). Two more references named no file: `STYLIST.js` opened a `SpecialStyle` script nobody wrote (its `#L7#` entry is commented out at `:215`, so unreachable) and `9977779.js` had a Weapon Crafting branch that loaded a missing script and then built the mineral interaction (the option is not in `selectionMenuOptions` at `:18`, so unreachable). | The hub's Stylist button reopened the hub menu, forever; the 22 KB saloon script behind it was unreachable. |
| `151-mountbuff` | `f3c86ac8f` | `tools/PacketCreator.java:2855-2868` wrote the character id of `GIVE_FOREIGN_BUFF` only `if (mount.getItemId() == 1932000)`. Cosmic writes it unconditionally, and so do our six other `GIVE_FOREIGN_BUFF` writers (`:2989`, `:3020`, `:3103`, `:3131`, `:3150`, `:5435`). Every other mount's packet was four bytes short, so the client read the low half of the stat mask as the character id and misaligned the mask; the battleship at level 2+ (`StatEffect:1245`, 1932001+) was short too. | Other players never saw your mount. |
| `151-const-gaps` | `619a25e32` | `constants/id/MapId.java:89` set `ARAN_TUTORIAL_START = MAPLEROOT_HUB` (993910000) while `ARAN_TUTORIAL_MAX` stayed `914000500`, so the range check at `src/main/java/net/server/channel/handlers/AbstractDealDamageHandler.java:720`
  (`chr.getMapId() >= ARAN_TUTORIAL_START && ... <= ARAN_TUTORIAL_MAX`) can never be true. `constants/game/GameConstants.java:96-115` lost Cosmic's last `GOTO_AREAS` entry `put("fm", MapId.FM_ENTRANCE)`. | Nothing visible; both are dead paths a GM hits (`@goto fm` answered "Area not available"). |

Tests, each failing before and passing after:
`src/test/java/scripting/ScriptReferencesTest.java` (`everyScriptReferenceResolvesToAScriptFile`),
`src/test/java/tools/MonsterRidingPacketTest.java` (`mountPacketOpensWithTheCharacterIdForEveryMount`),
`src/test/java/constants/game/MapConstantsTest.java` (`aranTutorialConstantPointsIntoTheTutorialRange`,
`gotoCanSendAGmToTheFreeMarket`).

## Open, ask the owner

| # | where | what MapleRoot did | risk | why it is not fixed here |
|---|---|---|---|---|
| 1 | `net/server/channel/handlers/UseItemHandler.java:55`, `:106-118` and `net/server/channel/handlers/ChangeMapHandler.java:146-153` | While `inExpedition` is set, the normal potion path is skipped and whatever USE item was clicked is applied to **every character on the map** (`chr.getMap().getCharacters()`), capped by `potionCount`. `ChangeMapHandler` sets `inExpedition = true` on entering `211070100`/`211070101` and `potionCount = 100` when warping from `211070000`; the reset sits in the alive, non-GM portal branch, so it is never touched by death-respawn, a GM warp or a script warp. | In the Zakum maps one player's potion heals the whole map, and a town scroll there warps the whole map. Dying inside leaves the flag set: potions then keep healing the map anywhere, and once `potionCount` reaches 0 they stop working at all (the normal path stays skipped); re-entering from `211070000` refunds 100 every time. | Whether expedition potions should be group potions is a design choice; the *leak* of the two flags is a plain bug and needs a fix wherever the owner wants the flags reset. |
| 2 | `net/PacketProcessor.java:227` (+ `net/server/channel/handlers/EnterMTSHandler.java:35-79`, `config/ServerConfig.java:202`) | `MTS_OPERATION` is not registered (the line is commented out), and the MTS button is redirected to `MAPLEROOT_HUB`. The shipped config has `USE_MTS: true`. | The Maple Trading System is gone and nothing reads `USE_MTS`, so the flag lies. The redirect does not check `FieldLimit.CANNOTMIGRATE`, `USE_MTS` or level, so it works from maps that forbid migration. | Deliberate hub redirect; whether MTS comes back is an owner decision. |
| 3 | `tools/PacketCreator.java:2830-2833` | `giveBuff` sets `special` / `p.skip(3)` for `MONSTER_RIDING` and `HOMING_BEACON` only when `buffid != 1932000 && buffid != 5221006`; Cosmic pads for those stats always. | The player's own buff packet for the battleship and for Homing Beacon is three bytes shorter than every other mount/beacon buff. If the client needs the pad, those two buffs misparse. | Cannot be settled from the code: it is a packet to our own client, and guessing wrong breaks the buff bar. Needs one ride in game (see below). |

## Checked and not broken (do not "restore" these)

- **Mob skill chance.** `server/life/MobSkill.java:192` `applyEffect` applies a disease/buff without rolling the WZ
  `prop` chance. Stock Cosmic is identical (no `makeChanceResult()` at the entry in either tree; the only uses are
  `:256`, `:261`, `:472`), and both callers (`MoveLifeHandler:99-104`, `TakeDamageHandler:161-163`) are
  byte-identical. A scout pass reported a deleted entry gate; `git diff afba85827 5b628dda9 -- MobSkill.java` shows no
  such hunk. Leave it.
- **Battleship HP.** `TakeDamageHandler:268` comments out `decreaseBattleshipHp` and `Character.decreaseBattleshipHp`
  is gone, so the ship never breaks. Documented DELIBERATE with the owner's 2026-10-21 decision to keep
  (`docs/release/cosmic-diff-1.4.1.md:99`).
- **`MakerProcessor` -> `MakerService`.** A move, not a rewrite: the success/stat math and `removeMakersRecipe` are
  unchanged, `RemoveOddMakerRegents` still removes what it removed before, and the `createStatus` guard now matches
  what `ItemConstants.isAbleToCreate` reports. Our tree also has Cosmic's `ScrollHandler` back.
- **Packet bodies.** `addQuestInfo` (`PacketCreator:325-350`), `addItemInfo` (`:354-455`), `addInventoryInfo`
  (`:456-489`), `updateParty`/`addPartyStatus` (`:3825-3940`) and `addAttackBody` (`:2375-2399`) are identical to
  Cosmic.
- **`addCharStats` writes EXP as a long** (`PacketCreator:171`) where Cosmic writes an int. Safe because we ship our
  own patched client.
- **`ExpTable` long[] (256 entries)** and the `ItemConstants` widenings (`isMedal`, rate coupons, town-scroll bound,
  storage 255). Data-fit, and the salon ranges were already fixed in `1fe365733`.
- **`ItemId.isChair` -> `itemId / 10000 == 301`.** A fix, not a break: our catalog has chairs past Cosmic's
  `3011000` ceiling (`cashshop/catalog.tsv:7049-7053`).
- **`RecvOpcode` registrations.** Every handler Cosmic registers is registered here except `MTS_OPERATION`; ours also
  fixes `getHandler` (`PacketProcessor:72` bounds check).

Post-v83 leftovers that cannot run on v83 data (no action, never port): the monster-id blocks MapleRoot added to
`MobSkill.applyEffect` (`8840000` Von Leon, `8240098`/`8240099` Lotus, `8880000`/`8880002`/`8880010` Lucid/Will - note
`:206-209` counts `8210006` inside the `8210007` branch), `scripts/npc/2491006.js` needing an event manager
`NormalLotusBattle`, and the `MapId`/`MobId` constants for post-v83 maps.

## Config flags that differ from Cosmic's defaults

Cosmic side: `afba85827:config.yaml`. Ours: the 1.5.1 deploy config,
`/opt/mapleroot-dev/persistence-test/Server/config.yaml` on the test host, read 2026-10-26. `config.yaml` is not
tracked (`/config.yaml` at `.gitignore:7`, `docs/DEPLOYMENT.md:11`), so no repository file can show these values: the
deploy file is the only truth. 102 keys differ; 45 change what a player gets and are listed here. The other 57 are
operational (`BOT_*` and its LLM endpoints, `DB_*`, `HOST`/`LANHOST`/`LOCALHOST`, logging) and change no stock rule.

One of the ticket-68 fixes is **not on TEST yet**: that copy still has `ENABLE_PIC: false`, so deleting a character
still fails there until the next deploy of the 1.5.1 config (`ENABLE_PIC: true`).

### Rates, EXP and progression

| key | Cosmic | 1.5.1 | what a player sees |
|---|---|---|---|
| channels / worlds | 3 / 21 | 5 / 1 | five channels in one world (Bera) |
| exp_rate / meso_rate / drop_rate / boss_drop_rate / quest_rate | 10 / 10 / 10 / 10 / 5 | 3 / 10 / 4 / 3 / 1 | EXP 3x, meso 10x, common drops 4x, boss drops 3x, quest exp/meso x1 |
| USE_QUEST_RATE | false | true | the quest rates above apply at all |
| PARTY_BONUS_EXP_RATE | 1.0 | 2.0 | party exp share doubled |
| EXP_SPLIT_LEVEL_INTERVAL / EXP_SPLIT_LEECH_INTERVAL | 5 / 5 | 35 / 35 | party exp is shareable across 35 levels either way |
| TOT_MOB_QUEST_REQUIREMENT | 0 | 200 | kill quests pre-fill to 799/999 kills |
| MAX_EQUIPMNT_LVLUP_STAT_UP / MAX_EQUIPMNT_STAT | 10000 / 32767 | 3 / 150000 | a level-up equip rolls at most 3 points; stats go to 150000 |
| CHANNEL_LOAD | 100 | 125 | login capacity 5 x 125 = 625 |
| TIMEZONE | GMT | GMT-6 | daily resets happen on GMT-6 |
| USE_REBIRTH_SYSTEM | false | true | rebirth exists: rates x3/x2/x1 (first/second/third), final x1, reset to level 10, NPC 1300009 |
| USE_ADD_SLOTS_BY_LEVEL | false | true | non-GMs gain inventory slots every 20 levels |
| USE_FULL_ARAN_SKILLSET | false | true | Aran learns the full skill set at each advancement |
| USE_STARTER_MERGE | false | true | no effect: the key is declared (`ServerConfig.java:273`) and never read |
| CREATE_GUILD_MIN_PARTNERS | 6 | 1 | a guild can be founded alone |

### Items, drops and economy

| key | Cosmic | 1.5.1 | what a player sees |
|---|---|---|---|
| RESPAWN_INTERVAL | 10000 | 5000 | mobs respawn on a 5 s tick |
| ITEM_EXPIRE_TIME / ITEM_LIMIT_ON_MAP | 180000 / 200 | 660000 / 450 | loot lies 11 min; up to 450 drops a map |
| USE_ERASE_UNTRADEABLE_DROP | true | false | untradeable items stay untradeable, but may be dropped and looted |
| USE_MULTIPLE_SAME_EQUIP_DROP | true | false | a mob drops one copy of a multi-roll equip |
| USE_SUPPLY_RATE_COUPONS | true | false | rate coupons are filtered out of the Cash Shop and refused (`CashShopWindowHandler:76`, `CashOperationHandler:91`) |
| AUTOLOOT_ITEM_ID | - | 5590000 | an "autoloot" cash item exists and loots for its holder |
| MAXIMUM_TRANSACTIONS_FOR_BUYBACK | - | 5 | buy-back keeps the last 5 transactions |
| USE_BUYBACK_WITH_MESOS | true | false | buy-back is paid in NX, not meso |
| BUYBACK_COOLDOWN_MINUTES | 7 | 1 | buy-back can be used again after a minute |
| BUYBACK_MESO_MULTIPLIER | 1000 | 5 | irrelevant while buy-back is NX-only |
| STARFORCE_BOOST_PERCENTAGE / STARFORCE_MAX_MESO_COST / STARFORCE_MAX_SPELL_TRACE_COST | - | 0.2 / 50000000 / 150 | star force is enabled with a 20 % boost, 50 m meso and 150 traces a step |
| BACKPACK_MAX_ITEMS_PER_PAGE / BACKPACK_MAX_STORAGE_PER_ITEM / BACKPACK_MAX_UNIQUE_ITEMS / BACKPACK_MINIMUM_SEARCH_LENGTH | - | 8 / -1 / -1 / 3 | the bag search shows 8 items a page and needs 3 characters |
| JUMPQUEST_BASE_EXP_REWARD / MESOS / CUSTOM_EXP_MESOS | - | 10000 / 100000 / 1 | jump quests pay 10k exp and 100k meso |

### Content and systems

| key | Cosmic | 1.5.1 | what a player sees |
|---|---|---|---|
| USE_FAMILY_SYSTEM | true | false | the family window is refused |
| USE_DUEY | true | false | Duey says the service is off |
| USE_FISHING_SYSTEM | false | true | fishing chairs and fishing areas work |
| USE_MAP_OWNERSHIP_SYSTEM | false | true | players can claim maps |
| DAILY_CHECKIN_ENABLED / COLOR_PRISM_ENABLED | - | true / true | a daily check-in and the coloring prism exist |
| USE_ENABLE_FULL_RESPAWN | false | true | maps refill to their full spawn count with a player present |
| USE_ENABLE_SOLO_EXPEDITIONS / USE_ENABLE_DAILY_EXPEDITIONS | false / false | true / true | a boss expedition can start with one player, under daily entry limits |
| EXPEDITION_HWID_LIMIT | - | 4 | four expedition entries per machine per day |
| USE_ENABLE_RECALL_EVENT | false | true | a disconnected player rejoins their event |
| USE_AUTOAGGRO_NEARBY | false | true | aggro'd mobs keep acquiring nearby players |
| USE_PERFECT_GM_SCROLL | true | false | GM scrolls can fail and use slots |
| USE_ANTI_IMMUNITY_CRASH | false | true | Crash strips immunity when no reflect is up |
| USE_UNDISPEL_HOLY_SHIELD | false | true | Dispel does nothing under Holy Shield |
| USE_COMPULSORY_AUTOPOT | true | false | a pet drinks one potion per trigger instead of filling to the ratio |
| PET_AUTOHP_RATIO / PET_AUTOMP_RATIO | 0.99 / 0.99 | 0.52 / 0.52 | pets drink at 52 % |
| PETS_NEVER_HUNGRY | false | true | pets and mounts never lose fullness |
| PQ_BONUS_EXP_RATE | 0.5 | 10.0 | inert: gated on `EVENT_END_TIMESTAMP` 1428897600000 (2015) in both configs |

### Login, commands and social

| key | Cosmic | 1.5.1 | what a player sees |
|---|---|---|---|
| ENABLE_PIC / ENABLE_PIN | true / true | true / false | PIC asked at login and on delete (on TEST still `false`, see above); no PIN |
| BYPASS_PIC_EXPIRATION | 20 | 0 | the PIC is demanded on every login (no 20-minute HWID bypass) |
| DETERRED_MULTICLIENT | false | true | one client per PC; a second gets `REMOTE_LOGGEDIN` |
| USE_CHARACTER_ACCOUNT_CHECK | false | true | logging in a second character of the account disconnects the first |
| MINIMUM_GM_LEVEL_TO_TRADE / _TO_DROP / _TO_USE_STORAGE / _TO_USE_DUEY | 4 / 4 / 4 / 4 | 1 / 1 / 1 / 1 | a GM level 1 may trade, drop, use storage and use Duey |
| USE_ANNOUNCE_NX_COUPON_LOOT | false | true | "You have earned N X NX" popup on an NX-card pickup |
| ALLOW_CASHSHOP_WORLD_TRANSFER | true | false | cash-shop world transfer is refused |
| USE_MTS | false | true | no effect: nothing reads the flag, the MTS button warps to the hub (open #2) |
| USE_THREAD_TRACKER | - | false | no player effect: it only gates the server's internal lock tracker (`TrackerWriteLock:67`) |

Two of the flags above do nothing: `USE_STARTER_MERGE` is declared and never read, and `USE_MTS` is read nowhere
(open #2). Both are left as they are rather than guessed at.

### Key names that do not match Cosmic's

- `USE_ENHANCED_CLNSLATE` (Cosmic) vs `USE_ENHANCED_CLEAN_SLATE` (ours, `ServerConfig.java:331`). Only the second name
  is read, so a config written with Cosmic's spelling is silently ignored. Both are `false`, so nothing breaks today.
- `NPCS_SCRIPTABLE` is read as `USE_NPCS_SCRIPTABLE` by both trees and both set it `true`; no action.

## Owner questions

1. Zakum potions (open #1): should a potion used in the expedition maps heal the whole map? If not, that whole
   `inExpedition` path goes; if yes, it still needs the two flags cleared on death and on every map change.
2. Is the MTS staying gone (open #2)? If yes, drop `USE_MTS` from the config and the dead `MTS_OPERATION` registration
   instead of leaving a flag that does nothing.
3. Battleship HP stays gone (already decided 2026-10-21) - confirm it still holds for 1.5.1.
4. `USE_FAMILY_SYSTEM`, `USE_DUEY`, `MINIMUM_GM_LEVEL_TO_TRADE=1` and `MINIMUM_GM_LEVEL_TO_DROP=1`: deliberate?
   A GM-level-1 character can trade and drop, and players cannot see Duey or the family window even though both are
   stock v83 features.

## In-game checks this audit cannot make (packet to our own client)

1. Ride a cash mount in a party or next to a friend: the other player must see the mount (fix `f3c86ac8f`; if it was
   already visible on 1.5.0, the client tolerates the short packet and the fix is still the correct layout).
2. Ride the battleship, take a hit, and use Homing Beacon: the buff bar must not glitch (open #3 - the 3-byte pad).
3. Hub -> Stylist: the saloon menu must open (fix `053f0f78d`).
4. `@goto fm` as a GM, and `@goto fm` as a player (the player must be told it is not available).
5. Zakum: potion use with two characters on the map (open #1).
