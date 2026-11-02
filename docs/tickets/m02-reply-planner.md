# m02: a reply to a player is decided before it is worded

Target: 1.6.2. Status: stage A implemented behind a switch that is off by default (2026-10-31). Stage B (party and PQ
asks, bot-started chats, and the follow-ups on repetition) is not started.

Read AGENTS.md and `docs/sessions/EXTERNAL-AGENTS.md` first. This comes from the chat-model lane's brief
(`/root/work/agent-runs/chat/c-ctxprompt/BRIEF.md`) and the owner's answers of 2026-10-31, which decide every question
below; it does not authorize Live, a release merge, or switching anything on.

## Why

Four days of TEST traffic (2026-10-28..31, 768 replies to players in `logs/convo/*.jsonl`) show that replies to players
were *not* decided from what was true:

- 20 replies said the bot was grinding where it stood, in a town (e.g. "yo again! just grindin spores", "still grinding
  toward 70"), on maps with no monsters. b240 fixed the bot-to-bot version of this; the reply path still had it.
- Every greeting collapsed to one shape ("yo again lol just chillin in henesys"). The single most-repeated reply of the
  four days was the scripted `chatting with u rn` answer, said by 13 different bots.
- All 98 sampled prompts carried two typing styles at once: `BotChatPrompt.VOICE_BASE` and the persona's own
  "Your typing: ..." line, plus a mood line, an affinity line, a contract and a state snapshot that could contradict
  each other (4 romance contradictions and 4 mood contradictions in the sampled prompts).
- The prompt itself was long: about 3,800 characters of context, so the local model wrote flavour instead of answering.

The owner's rule for 1.6.2: the game decides the move and the facts, the model only phrases it, and the bank and
scripts say the same move without a model.

## Design

`ReplyPlanner` (new, `soloMapling.ArtificialPlayer.BotConvo`) is the decision. It never touches the game and it does not
word anything.

- `View` is everything the move may be decided from, read once by `BotReplyView.of(...)`: the bot (level, job, place,
  monsters here, what it is doing, in a PQ, busy), the player (level, job, whether it is a bot), the town flag, the bond
  (stranger, known, friend, wary, dating and the one memory behind it), the spots a player of their level trains at, the
  bot's own spot, whether the bot already spoke in this chat, and the triage read of the player's line. Unreadable
  pieces are null and drop their fact; nothing here guesses.
- `plan(View)` returns one `Move` and its `facts`, or null. Null means free chat: the reply takes exactly the prompt it
  took before this ticket. Order matters and is tested:
  1. a bot talking to a bot, or any line the action layer owns (intent other than NONE) is never planned;
  2. a greeting (rule triage says so, or the planner's own reader catches "hey hows it going"), which is `HELLO` from a
     stranger, `HELLO_BACK` from someone the bot knows, and `SMALL_TALK` if the bot already spoke in this chat;
  3. a training question with a real spot for the player's level is `TRAIN_SPOT` - unless the line only asks about
     levels ("what lvl r u" also matches the train pattern);
  4. level talk (a level statement, a grats, the gap) is `LEVEL_TALK`, using only the two real numbers;
  5. "what are you doing / where are you" is `ACTIVITY`, answered from the place and the monsters, never "grinding" in a
     town;
  6. anything else while the bot is busy (grinding, a PQ) is a `BUSY` one-liner;
  7. otherwise null.
- `prompt(View, Plan)` is the whole phrasing prompt: one voice line (the persona's, when the persona switch is on,
  else `DEFAULT_VOICE`; `ConvoTune` examples are no longer appended a second time), the facts as the only claims, one
  instruction for the move, and one rules sentence. No mood line, no affinity line, no second style, no state snapshot
  block, no contract: those were the contradiction sources.
- `scripts(View, Move)` is the same move without a model: bank-quality lines that only say what the plan says. They are
  what the reply falls back to when the model is not there, and they are covered by the replay check.
- The plan is used in `BotConversation.ask` only through `planReply(outcome)`: the planner switch on **and** the action
  outcome NONE. With the switch off, that gate is false, `plan` is never consulted and the prompt assembly is the old
  one (the one substitution on that path, `voiceLine`, is the identical expression moved into `BotReplyView` so both
  prompts cannot drift).
- `TRAIN_SPOT` and `LEVEL_TALK` rebuild the state snapshot with the player's spots (`mindFor(..., world=true)`), because
  the grounding checker rejects a line that names a spot it does not know. Without that the model would be right and the
  checker wrong.

## Switch

`-Dsolomapling.convo.planner=true`, read by `ConvoSwitches.planner()` (a default-off reader; not `on()`, which defaults
true). No `config.yaml` key, so no config change can stop a boot. TEST turns it on with the flag in
`start-test.sh`; the release conf line and the version notes are the lane session's to write.

## Tests

- `ReplyPlannerTest` (35 tests, 36 with the cv-13 one below): one test per branch and per precedence rule, the facts of
  each move, the bond and its one memory, "never grinding in a town", scripts that say the same move, the third-bot
  rule, the switch default, the `planReply` gate (off for every outcome; on for NONE only), and the prompt length with
  eight real persona voices (median 830 characters, longest 904 - before this ticket about 3,800).
- `ReplyReplayTest` + `ReplyReplay` (the section-6 replay check): the four days are checked in as a fixture
  (`src/test/resources/chat/replies-to-players-2026-10-28-31.jsonl`, 768 rows: 565 llm, 108 bank, 95 scripted), re-read
  with the rule triage and the planner in the order they happened. `ReplyReplay.main` prints the tables below.
- `StateOfMindTest`, `BankClaimsTest` and `ReplyPlannerTest` also carry the cv-13 fixes below (a "here too" claim, the
  bank door in a town, and a hello that names the mob), each of them failing on the base commit.

## Replay: before and after

Before = what the bots actually said. After = the planner's own scripts for the same rows (the model side of "after" can
only be measured on TEST traffic, because it needs the model).

| | before | after |
| --- | --- | --- |
| replies to players, four days | 768 | - |
| on a town map | 474 (392 with the player's line recorded) | - |
| say the bot is grinding in a town | 20 | 0 |
| worst single reply of the four days | 13 bots: "chatting with u rn" | 9 bots: "hi" |
| said word for word by 3+ bots inside 10 minutes | 0 | 0 |

Coverage of the recorded traffic: of the 392 town rows that have the player's line, 116 are planned (100 greetings, 16
"what are you doing"). The other 276, by rule triage: `where r u` 56 (the action layer answers it), party 26,
come-with-me 16, follow 2, bye 1, emote 1, guild asks, PQ coordination ("go left"), and plain chatter ("lol ok") - plus
82 bot-started lines on a town map with no player line recorded at all (stage B). The rule triage alone reads fewer
greetings than the live decide model, so real coverage is higher than this floor.

The 10-minute column is the honest measure of "repeated": this fixture squeezes four days into one instant, so the
four-day column overstates what a player would meet. A third bot is kept off a script line by
`ReplyPlanner.fresh`: a line two other bots said in the last ten minutes is passed over while another fits. That ring is
the planner's own and deletable: the shared delivery-point guard belongs to s-chatguards (branch `162-chatguards` has no
commits yet, so there was nothing to reuse at the time).

## cv-13 wording fixes (2026-11-01)

Two replies from the TEST run of 2026-11-01 17:33-17:34 UTC, found by the client lane's QA pass
(`/root/work/agent-runs/client/evidence/m02-cv13-retry-20261101/qa-lines.txt`):

- Henesys, town, "hey": `Yo AqClient, you training here too?`, `[scripted, bank#859]`, with **no** `CONVO PLAN` line
  before it. The bank line says the bot trains where it stands, on a map with no monsters.
- Henesys Hunting Ground I, "hey": planned `hello_back`, then `[llm] hey! lolz long time xd` - the mob the bot was
  killing was neither in the facts nor asked for, so the line named nothing.

Fixes, and why they are where they are:

1. **The greeting shortcut is not the planner's**, so the bank door has to hold the rule: `BotConversation.ask()` answers
   `GREETING` through `bank(...)`/`ConvoLines.line(...)` (before the planner) when the two greeted recently or the bot
   already spoke. `ConvoLines.townGuard(bot)` is now passed into `ChatBank.pick` as another `Predicate<String>`, and it
   refuses any row `StateOfMind.claimsGrindingInTown` rejects while the bot stands in `StateOfMind.TOWNS`; every other
   map keeps the whole bank. The rule itself learned one shape it missed: `HERE_TOO` ("you training **here too**?"),
   because "here" is the bot's own map and "too" puts it at it, whatever the person of the verb - `SECOND_PERSON` used to
   read it as a question about the player. `StateOfMind.mayClaimActivity(text)` is the cheap pre-screen for that scan
   (the bank walks up to 4,000 rows per pick); `GRIND_ANY` is deliberately not widened, so "i train at kerning subway"
   stays a place, not a claim about here.
2. **A hello on a map with monsters names the mob**: `plan(View)` for `HELLO`/`HELLO_BACK` now carries
   `mobInstruction(v)` ("Name the mob you are killing (<mob>s) ..."), `place(v, f)` states the mob even when the map has
   no name in `ConvoEntities` ("you are killing <mob> here"), and the scripts for both moves are `mobGreetings(...)` -
   every one names the mob, and none of them is longer than 40 characters. Town greetings keep `greetingNow(...)`.

Tests (fail before, pass after - each one was run against the base commit):

- `StateOfMindTest.b240AskingThePlayerIfTheyTrainHereTooIsAClaimForTheBot`
- `BankClaimsTest.aTownBotNeverPicksALineThatSaysItTrainsHere` (the same bank, in Henesys and on the hunting ground)
- `ReplyPlannerTest.aHelloOnAMobMapNamesTheMob`

### A TRAIN_SPOT reply names a spot on the leveling path (same run, item 3)

`toasttty` (lv 34) answered "where should i train" with `i train at swampy land in a deep forest`: map 105040000, which
`LevelingPath.yaml` lists for no band at all. `BotReplyView.mySpot` took the bot's *current* map whenever the bot was
grinding, so any grind map it wandered onto became a training claim, and the plan put it both in the facts ("you train
at ...") and in the scripted fallback ("i train at ...").

`mySpot` now asks the path: `TrainingTips.spotName(mapId, level, jobId)` returns what players call the map when the
bot's own band (level and job group) trains there, else null - and then the bot uses the best spot for its level, as it
already did in a town or when idle. `realSpot` is now `spotName(...) != null`, so there is one lookup. A tip is
unaffected: `playerSpots` always came from `TrainingTips.spotsFor`, and the asker's band is still the only source for
"try <spot>".

- `TrainSpotReplyTest.aBotGrindingOffThePathStillNamesATrainingSpotFromItsOwnBand` (base: `Swampy Land in a Deep
  Forest`)
- `TrainSpotReplyTest.aTrainSpotReplyOnlyNamesSpotsOnThePath` (base: the fact `you train at Swampy Land in a Deep
  Forest`, and an `i train at ...` script saying it)
- `TrainSpotReplyTest.aBotGrindingOnAPathMapNamesThatMap` (a bot standing on 105040100 names it in the path's words,
  "Hunting Ground in the Deep Forest", not the map title with its numeral)

## Stage B (not started)

Party and PQ asks as planned moves, bot-started chats (about once per player per hour, only when not busy, respecting
`@botdrama off` and `@romance off`), and the follow-ups: what to do about repetition in bot-to-bot threads, and the
player's progress since last met (needs a `BotMemory` fact; no new table without the lane's ok).

## Not done / open

- Not switched on anywhere. TEST needs `-Dsolomapling.convo.planner=true` in `start-test.sh` and an auto-QA check; the
  release conf line and version notes belong to the lane session.
- The model side of the after table needs a TEST run with the switch on; the grounding checker and `retryGrounded` still
  guard it.
- Repetition in bot-to-bot thread lines (6,191 lines in the four days) is untouched: out of scope here.
