# alt-settings (1.6.7): expected outcome

Card: alt-qol-batch group B. Feature switch `PartyCompanionPolicy.Feature.SETTINGS` (key `settings`): owned alts ON
by default, party regulars OFF unless `-Dslums.companion.settings.regulars=true`.

Written before the code (repo rule).

## Why

Today an owned alt heals and supports on its own: `OwnedAltCompanion` calls `BotPartySupport.tick` (a Cleric's Heal
plus its buff potions) and `BotBuffDriver.selfBuff` (its class buffs) on every tick, and `BotVitals` drinks a recovery
potion when the bot is under its line. There is no way for the owner to say "stop healing, I am doing that", "no party
buffs", "save the buff potions" or "drink HP pots at 60, not 50", and nothing an owner chooses survives a relog: the
only per-player bot choices kept across restarts are `@quiet`, `@romance off` and drama (`botsocialopt`, `botaffinity`).

## Expected outcome

1. **A settings store per (owner, companion).** `soloMapling/companions/CompanionSettings` is one record - heals,
   support, buff pots, the HP and MP potion percentages (clamped 10..90; 0 = the bot's own line, i.e. today's
   behaviour) and the loot mode string (group D stores it; nothing here reads it) - with a JSON round trip.
   `CompanionSettingsStore` keeps it: one row in `companionsettings` (`OwnerId`, `CompanionId`, `SettingsJson`,
   `UpdatedAt`; migration 069) read once, written only on a change and always off the game thread through
   `BotDbGate`, exactly as `SocialGate` keeps `@quiet`. A companion the owner never touched has **no row**, and a
   setting put back to its defaults deletes the row again ("default not written"). Without migration 069 the server
   still runs: the store logs once and keeps the settings in memory until a restart.
2. **Chat toggles** to a companion, answered with the new state and saved:
   - `heals on` / `heals off`;
   - `support on|off` (alias `buffs on|off`, `buff on|off`);
   - `buff pots on|off`;
   - `pots at 60` (the HP percentage; `hp pots at 60` too) and `mp pots at 40` (the MP one);
   - `card` opens its card (see 3).
   The words are read only when the switch is on for a companion in the party, exactly as wave 2 reads `pots` /
   `stats` / `slots`, so with the defaults an ordinary "heals off" in party chat stays chat. Bare `pots` stays the
   wave 2 report; only `pots at <n>` is a threshold order. The new words are in the same `VERBS` table the typo
   suggester reads, so "did you mean 'heals'?" learns them without a second list.
3. **The gates live in the shared layer**, not in `OwnedAltCompanion`: `BotPartySupport.tick` consults the companion's
   heals and buff-pots switches, `BotBuffDriver` skips its party buffs when support is off (its self-buffs are
   untouched), and `BotVitals.hpPotAt` / `mpPotAt` return the owner's percentage when one was set - through the one
   read method `CompanionSettingsStore.hpPercent(Character)` / `mpPercent(Character)`, not a second threshold table.
   With no setting, every one of them behaves exactly as before (including the boss-run lines).
4. **A card window** per alt: `@alts card <name>`, or `<name> card` in party chat, opens a `MenuWindow` page (the
   `@settings` window, `slumsmenu.js`, no new packet) headed with the alt's level and job, its HP/MP and its potion
   counts, then the three switches as clickable lines that flip and save, and the two percentages as lines that open
   the text order for them. A click redraws the card with the new state.
5. **A regular is unchanged with the defaults** and changes with `-Dslums.companion.settings.regulars=true`.

## Proof (each test fails before the change)

- `CompanionSettingsTest.settingsRoundTripThroughJson` / `...clampsThePercentagesToTenToNinety` - a settings record
  survives `toJson`/`parse` field for field, and 5 -> 10, 95 -> 90, 0 stays "auto". (1)
- `CompanionSettingsStoreTest.anUntouchedCompanionUsesTheDefaultsAndWritesNoRow` - an alt with no row reads the
  defaults and the memory store holds nothing; a change writes one row, putting it back deletes it. (1)
- `CompanionSettingsStoreTest.aChosenSettingSurvivesAReload` - "heals off" is still off after
  `reloadForTest()` (a restart reading the store again). (1)
- `BotPartySupportTest.healsOffStopsTheHealForThatCompanionOnly` - with `heals off` stored for alt A, A's tick heals
  nothing while alt B still heals; with the switch off for the whole feature both are as before. (2, 3)
- `CompanionSettingSupportTest.supportOffSkipsThePartyBuffsAndKeepsTheSelfBuffs` - with support off the party buff is not cast, the self-buff is.
- `BotVitalsOwnerLineTest.theOwnersPercentagesMoveThePotionLines` - an HP percentage of 60 makes the bot drink at 55%, and 0
  leaves `hpPotAt` at today's value.
- `CompanionCardMenuTest.theCardListsTheTogglesAndAClickFlipsOne` - the page carries the level/job line, the potion
  counts and the three switch lines; clicking "Party heals: ON" saves heals off and the redrawn page says OFF.
- `CompanionSettingsStoreTest.aRestartedServerReadsTheStoredSettingsWithoutTheOwnerDoingAnything` (Bots' audit) -
  after a restart, with nobody typing anything, the bot's own first read loads the table, so "heals off" stays off.
- `CompanionSettingsChatTest` - "heals off" / "support off" / "buff pots off" / "pots at 60" / "mp pots at 40" parse
  to their verbs and args, save, and answer; a regular is unchanged with the defaults and reacts with the regulars
  switch on. (2, 5)

## What existing characters keep

Nothing is taken away. Every new field's default is today's behaviour: heals on, support on, buff pots on, and the
potion percentages unset, so `BotVitals` keeps its own line (habit 40-60%, 50% offscreen, 70/50% in a boss run). No
item, level, stat, quest or saved setting is read, moved or rewritten. An existing owned alt has no row in
`companionsettings`, so it loads with the defaults and behaves exactly as it did before this change; a player's
existing `@quiet` / `@romance` / drama rows are a different table and are not touched.

Migration: `database/migrations/069-companion-settings.sql` creates `companionsettings` only (no existing table,
column or row changes; idempotent, ledger row `069-companion-settings`), with `.rollback.sql` dropping it. Nothing is
applied to any database by this branch.

## Out of scope

The loot mode's behaviour (group D): this branch only stores the string. Formation and stance orders stay
session-only. `OwnedAltCompanion`, `GCFollow`, `OwnedAltLoot` and `GrindLoot` are untouched.

## Who wrote what (finished by Bots, 8 Nov 2026)

The DeepSeek helper stopped early three times on the official provider (16:43 an empty turn, 16:52 a malformed tool
call, 16:55 an empty turn). The owner chose to have Bots finish it. The helper's work is kept as its own commits:
- `8cdd7732e` (this file's expected outcome);
- `c64f5d7c6` (store, migration 069, the three shared gates and their tests);
- `2a6c50b75` (its unfinished chat verbs, orders and card, committed unchanged by Bots).

Bots added, on top:
- **The restart load.** Nothing read the table after a restart until the owner used `@alts` or a toggle, so a bot's
  tick saw the defaults and a stored "heals off" quietly came back on. `CompanionSettingsStore.of` now queues one load
  off the tick's thread when nothing is loaded yet. There is one queued load at a time, and after a failed read at most
  one a minute.
- **Tests:**
  - `CompanionSettingsChatTest`: the verbs, the regular's defaults, the answers, the typo hint learning the new words,
    and `card jane`;
  - `CompanionCardMenuTest`;
  - the restart test above.
- **Help text.** The `@alts` help now lists the settings. `@alts card <name>` needed no new code: "card jane" is read
  as the card order for Jane.

Bots also checked the helper's committed gates. With `BotPartySupport`, `BotBuffDriver` and `BotVitals` put back to
the base, its three gate tests fail (`:170`, `:64`, `:41`).

Known limits:
- The bots' table is keyed by companion. A regular followed by two leaders (only with the regulars switch on) keeps
  the last leader's choice.
- In the first moments after a restart, before the load lands, a bot reads the defaults.
