# alt-chat-reports (1.6.7, group A): expected outcome

Card: alt-qol-batch group A, "the chat side of companions". Switch: `PartyCompanionPolicy.Feature.CHAT_REPORTS`
(key `reports`): owned alts ON, party regulars OFF (they turn on with `-Dslums.companion.reports.regulars=true`).

Written before the code; texts and test names below are the ones that landed.

## Why

Every verb a player can give a companion is an action ("grind", "follow", "drop pots"). Nothing answers a question,
nothing warns the player that a companion is nearly out of pots, a mistyped verb is silently ignored, a full bag is
reported once per fill and never again, and "drop pots" empties a companion's bag the moment it is typed. This card
gives the chat side the same verbs a player already knows, plus the two guards (low pots, confirm the drop).

## Expected outcome

All of it only when `PartyCompanionPolicy.on(Feature.CHAT_REPORTS, companion)` is true for the companion that answers.

1. **Reports** (`CompanionReports`). Three new verbs, answered by one companion to the player who asked, through the
   existing reply path (owned alt: `OwnedAltOrders.say` -> `OwnedAltCompanion.say`; party bot: `BotSpeak`), never
   broadcast to the map. `PartyBotOrders.read` only offers the verbs when a companion would answer them
   (`reportsReadable`: the switch is on for one of the player's alts, or for regulars):
   - `pots` / `pots?` / `potions` / `how many pots` -> "I have 120 hp pots and 40 mp pots"; with one side empty
     "I have 120 hp pots, no mp pots" or "no hp pots, 40 mp pots"; with neither "no pots on me rn". Counts come from
     `BotVitals.potionCount(c, true/false)` (the pot-count code `BotVitals` already has; `SupplyShare` /
     `CompanionSupplies` read the same number).
   - `stats` -> "lv42 warrior, hp 500/800 mp 100/200, 30 str 4 dex 4 int 4 luk", plus ", 3 ap free" only when the
     companion has unspent AP.
   - `slots` / `inv` / `inventory` -> "equip 12/24, use 30/48, etc 31/48, setup 0/8": used/total per tab from the
     existing `Inventory` (`getSlotLimit()`, `getNumFreeSlot()`); no new inventory scanner.
   The verbs work as `@alts pots` (targets every companion, the first that can answer speaks) and as party chat
   addressed to a companion ("Jane pots").
2. **Did you mean** (`CompanionReports.typoSuggestion` + `PartyBotOrders.typoHint`). A line that is *addressed* to a
   companion - an `@alts` line (`AltsCommand`), or a line that opens with the companion's name by the chat-routing
   rules (`BotAddressee.resolve` over the first word, `ASK` certainty) - whose first usable word is not a verb, is at
   least 3 letters, is not a common chat word and is within 1 edit (3-5 letters) or 2 edits (6+ letters) of a known
   verb gets "did you mean 'grind'?" from that companion. Ordinary party chat never fires (the parser does not see it
   as an order and nobody is addressed), a line that parses as an order never reaches the hint, and the deny list is
   BotStory's (`hello`, `hey`, `yes`, `ok`, `lol`, `what`, ... plus `yo`, `oi`, `pls`, `now`, `lets`).
3. **Low-pots note** (`CompanionReports.lowNote` / `lowLine`). The grind/follow acknowledgement gets
   " (only 4 HP pots)" appended when HP or MP pots are under `LOW_NOTE_AT = 20` (MP only when the job uses MP,
   `BotSupplies.needs(c).usesMp()`); at exactly 20 nothing. " (only 19 HP pots and 3 MP pots)" / " (only 3 MP pots)"
   when both / only MP are low. Owned alts append it in `OwnedAltOrders.ack`; party regulars (only with the
   `regulars` switch on) say "only 4 HP pots" as one extra short line after their own ack, because their ack text is
   inside `FollowerBot`/`TrainingBot`, which this card does not touch.
4. **Bag full** (`OwnedAltLoot`). `fullLine` keeps its text ("My Etc inventory is full, I'm leaving those drops on
   the ground.") but each tab is named at most once per `CompanionReports.FULL_SAY_MS = 300_000`, keyed by alt and
   tab, instead of once per fill-until-it-has-room-again. Loot itself is untouched: the alt still leaves the drops
   on the ground; it only stops repeating itself inside the window.
5. **Confirm before drop** (`CompanionReports.confirmFirst` / `confirmDrop`, `CompanionItems.drop`). `drop <category>`
   asks first: "just to confirm, drop my pots? say yes". Only a yes from the same player within `CONFIRM_MS = 30_000`
   does the drop, and the companion then says "k, dropping my pots". A yes is the whole line, made of yes words
   (yes, yep, yeah, yea, y, ok, okay, sure, confirm, do it, go ahead, go for it) and polite filler ("yes please",
   "ok thanks", "sure then") and nothing else, so "ok i'll be back in five" is a sentence and cancels. Any other line
   from that player cancels it, and so does the 30 s timeout; the items stay where they are and the line stays ordinary chat. On a `FieldLimit.DROP_LIMIT` map the
   companion refuses instead: "drops are off on this map, i can't drop anything here". The map is checked again at the yes (wave 2 audit): a drop limited map destroys what lands on it, so an alt that walked into a Free Market room after asking refuses with the same line and keeps its stacks.
6. **Regulars keep their old behaviour.** With the defaults a party regular gets no report verb, no typo hint, no
   note, no confirmation and no bag-full window: `read` does not offer the report verbs, `typoHint`/`answer` return
   false, `ack` adds nothing, `maySayFull` says every tick as before. Everything above turns on for regulars with
   `-Dslums.companion.reports.regulars=true`.

## What existing characters keep

No stat, item, quest, setting or saved value is written by this card at all:

- reports only read (`BotVitals.potionCount`, `Inventory`, `AbstractCharacterObject` getters);
- the typo hint and the low-pots note only add a chat line;
- the bag-full window changes *how often* one line is said, never the loot behaviour;
- the drop confirmation makes "drop" stricter (a yes within 30 s); the drop itself runs the same
  `InventoryManipulator.drop` path on the same items.

Migration: none needed, because this card adds no table, no column, no config key and no saved state. The switch is
the existing `-Dslums.companion.reports.alts|.regulars` system property of `PartyCompanionPolicy`; nothing goes into
`config.yaml` (strict config: an unknown key would abort boot).

Tested: `CompanionReportsTest.anExistingCharacterKeepsEverythingItHas` asks `pots`/`stats`/`slots` on a mocked alt
loaded with today's numbers (level, job, hp/mp, stats, AP, mesos, items), gets exactly those numbers back, twice the
same, and `setLevel` is never called.

## Proof (tests, each fails before the change; fail-before messages recorded in REPORT-deepseek.md)

- reports: `CompanionReportsTest.potsSaysBothCounts`, `potsSaysSoWhenThereAreNone`, `statsIsOneLine`,
  `statsLeavesOutFreeApWhenThereIsNone`, `slotsSaysUsedOutOfTotal` (text from a mocked character/inventory),
  `anAnswerGoesOutOnlyWithTheSwitchOnForThatCompanion` (reply only for a companion the switch is on for),
  `potsIsAnOrderOnlyWhenACompanionWouldAnswerIt` (`read` offers the verbs for an alt, not for a regular by default,
  and again with `regulars`), `anExistingCharacterKeepsEverythingItHas` (keep rule).
- typo hint: `aMistypedOrderAddressedToACompanionGetsTheHint` ("jane grnd" -> "did you mean 'grind'?"),
  `anAtAltsLineGetsTheHintWithoutTheName`, `ordinaryPartyChatThatTheParserIgnoresStaysChat` (no hint),
  `aRegularWithTheDefaultSwitchesGetsNoHint` (+ with `regulars`).
- low note: `CompanionReportsTest.theLowNoteStartsAtNineteen` (19 -> note, 20 -> none),
  `theLowNoteCountsMpOnlyForAJobThatUsesIt`, `OwnedAltOrdersAckTest.theGrindAckSaysThePotsAreNearlyOut`,
  `aFullBagOfPotsNeedsNoNote`, `theNoteIsForGrindAndFollowOnly`,
  `aRegularWithTheDefaultSwitchesHearsTheSameAckAsBefore`.
- bag full: `OwnedAltLootTest.aFullTabIsReportedOncePerWindow`.
- confirm before drop: `CompanionReportsTest.aDropWaitsForAYesFromTheSamePlayerInsideTheWindow`,
  `aYesAfterTheWindowDoesNothing`, `anythingButAYesCancels`, `aYesBuriedInASentenceDoesNotDrop`,
  `aDropLimitedMapRefusesWithALine`;
  `CompanionItemsTest.dropAsksFirstAndOnlyTheYesDropsIt` (through `CompanionItems.drop`),
  `aDropThatNobodyConfirmsLeavesTheBagAlone`.

Run: `mvn -q -o test -Dtest='CompanionReportsTest,OwnedAltOrdersAckTest,OwnedAltLootTest,CompanionItemsTest'
-Dsurefire.failIfNoSpecifiedTests=false -Dwz-path=/root/work/SlumsMS-152-maps/wz`, then the full suite.

## Ports

- BotStory (`https://github.com/Incluwin/BotStory.git` @ `81a3b1dfb3b6ec1619be4ced43ae0a8b6882b364`):
  `server/agents/capabilities/dialogue/AgentDialogueReportFormatter.java` (potionCount, stats),
  `AgentInventoryDialogueReporter.java` (slotsReport), `AgentSupplyDialogueReporter.java` (grindStartMessage),
  `server/agents/commands/AgentCommandTypoSuggester.java` (suggest, MAX_DISTANCE, CHAT_DENYLIST, bounded Levenshtein).
- cosmic_agents (`https://github.com/Kiwiism/cosmic_agents.git` @ `3c7e094357329932734a860e5a831d57add27a00`):
  `AgentDialogueCatalog.java` (the "just to confirm ... say yes" prompts), `AgentPendingChatActionFlow.java` (one
  pending action; anything else cancels), `AgentChatCommandClassifier.java` (the yes words),
  `AgentPassiveLootService.java` (a cooldown on the bag-full warning),
  `AgentInventoryDropService.java` (`FieldLimit.DROP_LIMIT` refusal).
Both AGPL-3.0; the source headers are quoted at the top of `CompanionReports.java`, and `docs/PROVENANCE.md` has the
entry.
