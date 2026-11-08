# alt-chat-reports (1.6.7, group A): expected outcome

Card: alt-qol-batch group A, "the chat side of companions". Switch: `PartyCompanionPolicy.Feature.CHAT_REPORTS`
(key `reports`): owned alts ON, party regulars OFF (they turn on with `-Dslums.companion.reports.regulars=true`).

Written before the code.

## Why

Every verb a player can give a companion is an action ("grind", "follow", "drop pots"). Nothing answers a question,
nothing warns the player that a companion is nearly out of pots, a mistyped verb is silently ignored, a full bag is
reported once per fill and never again, and "drop pots" empties a companion's bag the moment it is typed. This card
gives the chat side the same verbs a player already knows, plus the two guards (low pots, confirm the drop).

## Expected outcome

All of it only when `PartyCompanionPolicy.on(Feature.CHAT_REPORTS, companion)` is true for the companion that answers.

1. **Reports.** Three new verbs, answered by the target companion to the asker through the existing reply path
   (owned alt: `OwnedAltOrders.say`; party bot: `BotSpeak`), never broadcast to the whole map:
   - `pots` / `pots?` / `how many pots` -> "I have 120 hp pots and 40 mp pots"; with one side empty
     "I have 120 hp pots, no mp pots" / "no hp pots, 40 mp pots"; with neither "no pots on me rn".
     Counts come from `BotVitals.potionCount(c, true/false)` (the supply numbers `CompanionSupplies` already reads).
   - `stats` -> "lv45 cleric, hp 320/500 mp 900/1000, 40 str 60 dex 82 int 30 luk" and, only when the companion has
     unspent AP, ", 3 ap free" on the end.
   - `slots` / `inv` -> "equip 12/24, use 30/48, etc 31/48, setup 0/8", used/total per tab, read from the existing
     `Inventory` (`getSlotLimit()` / `getNumFreeSlot()`); no new inventory scanner.
   The verbs work both as `@alts pots` and as party chat addressed to the companion ("Jane pots").
2. **Did you mean.** A word in a line that is *addressed* to a companion, is not a known verb, is at least 3 letters
   and is 1 edit (3-5 letters) or up to 2 edits (6+ letters) away from a known verb gets "did you mean 'grind'?" from
   that companion. "Addressed" is the same test `BotAddressee` uses: the line starts with the companion's name (score
   >= `BotAddressee.ASK`), or the line is an `@alts` order. Ordinary party chat, common chat words ("hello", "yes",
   "lol", the deny list from cosmic_agents' typo suggester) and words that are already verbs never fire. A line that
   parses as an order never reaches the hint.
3. **Low-pots note.** The grind/follow acknowledgement gets " (only 4 HP pots)" appended when that pot count is under
   `LOW_NOTE_AT = 20`; MP only when the job uses MP (`BotSupplies.needs(c).usesMp()`). At exactly 20 there is no note.
   Owned alts append it to the ack (`OwnedAltOrders`); party regulars (only with the `regulars` switch on) say it as
   one extra short line after the ack, because their ack text lives in `FollowerBot`, which this card does not touch.
4. **Bag full.** `OwnedAltLoot.fullLine` keeps its text ("My Etc inventory is full, I'm leaving those drops on the
   ground.") but a tab is reported at most once per 5 minutes (`FULL_SAY_MS = 300_000`), not just once until the bag
   has room again. A companion that fills, empties and fills the same tab within the window stays quiet the second
   time.
5. **Confirm before drop.** `drop <category>` asks first: "just to confirm, drop my <category>? say yes". Only a
   "yes" (yes / yeah / yep / y / ok / sure / confirm / do it) from the same player within 30 seconds does the drop, on
   the same category, and the companion then says "k, dropping my <category>". Any other line from that player cancels
   it, and so does passing 30 seconds; the items stay where they are. On a map with `FieldLimit.DROP_LIMIT` the
   companion refuses instead: "can't drop here, this map doesn't allow drops."
6. **Regulars keep their old behaviour.** With the defaults (alts on, regulars off) a party regular gets no reports, no
   typo hint, no note, no confirmation and no bag-full window change: its lines are byte-for-byte what 1.6.6 said.

## What existing characters keep

Nothing is taken away. No stat, item, quest, setting or saved value is written by this card at all:

- reports only read (`BotVitals`, `Inventory`, `AbstractCharacterObject` getters);
- the typo hint and the low-pots note only add a chat line;
- the bag-full window shortens *how often* the same line is said, never the loot behaviour (the alt still leaves the
  drops on the ground; it just stops repeating itself);
- the drop confirmation makes "drop" stricter: before its change, an alt dropped at once; now the same command needs a
  "yes" within 30 s. No stored data is touched, and the drop itself runs the same `InventoryManipulator.drop` path with
  the same item selection.

Migration: none needed, because this card adds no table, no column, no config key and no saved state. The new switch is
the existing `-Dslums.companion.reports.alts|.regulars` system property of `PartyCompanionPolicy`, which already exists
in the branch; nothing is written to `config.yaml` (strict config: an unknown key would abort boot).

Test of the keep rule: a test loads a mocked existing owned alt (level, job, stats, pots, items, mesos as they are
today), asks `pots` / `stats` / `slots`, and asserts the answers match the character's own numbers and that no setter
on the character was touched (Mockito `verifyNoMoreInteractions` on the mutators / no state change).

## Proof (tests, each fails before the change)

- `CompanionReportsTest.reportsComeFromTheCompanionsOwnNumbers`: pots/stats/slots text from a mocked inventory and
  character (fails: the verbs do not parse, no line is said).
- `CompanionReportsTest.typoHintOnAnAddressedLine`: "grnd" addressed to an alt answers "did you mean 'grind'?"
  (fails: nothing is said).
- `CompanionReportsTest.noTypoHintOnUnaddressedChat`: the same "grnd" as plain party chat says nothing.
- `CompanionReportsTest.lowNoteUnderTwentyOnly`: ack at 19 HP pots carries the note, at 20 it does not.
- `OwnedAltLootTest.aFullTabIsReportedOncePerWindow`: the same full tab twice inside 5 minutes is reported once, and
  again after the window.
- `CompanionItemsTest.dropNeedsYesWithinThirtySeconds`: no drop before the "yes", the drop after it, another player's
  "yes" does not count, 31 s is too late, a `DROP_LIMIT` map refuses.
- `CompanionReportsTest.regularWithDefaultsIsUnchanged`: a party regular (not an owned alt) gets no report, no hint and
  no confirmation with the default switches, and gets them with `-Dslums.companion.reports.regulars=true`.

Run: `mvn -q -o test -Dtest=CompanionReportsTest+OwnedAltLootTest+CompanionItemsTest -Dsurefire.failIfNoSpecifiedTests=false
-Dwz-path=/root/work/SlumsMS-152-maps/wz`, then the full suite.

## Ports

- BotStory (`https://github.com/Incluwin/BotStory.git` @ `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`):
  `AgentDialogueReportFormatter.potionCount/stats`, `AgentInventoryDialogueReporter.slotsReport`,
  `AgentSupplyDialogueReporter.grindStartMessage`, `AgentCommandTypoSuggester.suggest` + `CHAT_DENYLIST` + bounded
  Levenshtein.
- cosmic_agents (`https://github.com/Kiwiism/cosmic_agents.git` @ `3c7e094357329932734a860e5a831d57add27a00`):
  `AgentDialogueCatalog.DROP_OR_TRADE_PROMPTS` (the "just to confirm ... say yes" shape),
  `AgentPendingChatActionFlow.handle` (one pending action, anything else cancels),
  `AgentChatCommandClassifier.LOGOUT_CONFIRM_PATTERN` (the yes words), `AgentPassiveLootService` (a cooldown on the
  bag-full warning), `AgentInventoryDropService.dropCategory` (`FieldLimit.DROP_LIMIT` refusal).
Both AGPL-3.0; licence headers kept in the new files, entries in `docs/PROVENANCE.md`.
