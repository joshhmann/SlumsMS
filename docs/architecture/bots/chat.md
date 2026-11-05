# Bot chat

## Purpose

Every word a bot says to a real player: reading the player's line, deciding what the bot does about it, wording the
answer (model or line bank), keeping the words consistent with the action the game already took, and putting the line
on the wire as an ordinary map/buddy/party/whisper chat packet.

Three layers, deliberately separated:

| Layer | Question it answers | Code |
| --- | --- | --- |
| Read | What did the player mean, and is it abuse? | `BotBrainSystem/ChatTriage`, `BotConvo/ConvoSense`, `PlayerChatMeaning`, `ChatScope` |
| Act | What does the bot do (party, trade, follow, where-train)? | `BotChatActions`, `BotConvo/ConvoActions`, `ClaimActions` |
| Say | How is it worded, in the bot's own voice, without claiming anything untrue? | `BotConvo/ReplyPlanner`, `BotChatPrompt`, `BotBrainSystem/{ChatRouter,OpenAICompatibleBrain}`, `BotConvo/{SayDo,StateOfMind,ReplyCleaner}` |

The design rule behind all of it: **nothing waits on a model**. `BotBrain.SCRIPTED`
(`src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/BotBrain.java:40-60`) answers `Optional.empty()` for every
call, and empty always means "use the scripted dialogue" — so with no endpoint configured, down, slow or rate-limited,
the same code path produces a bank line and the bot still answers.

## Entry points

| Trigger | Where | What it does |
| --- | --- | --- |
| Player map chat | `src/main/java/net/server/channel/handlers/GeneralChatHandler.java:52-54` (QaLog CHAT), `:56` (hidden GM ignored), `:59-61` (`MapChatLog.record`), `:64-65` (`BotDrama.onPlayerChat` early return), `:66-67` (`MessageQueue.getInstance().addMessage("primary", …)`), `:68` (`BotBuffRequestHandler.tryHandle`) | queues the line for the bot dispatcher |
| Dispatcher tick | `src/main/java/soloMapling/ArtificialPlayer/BotMessagingSystem/Dispatcher.java:44` (2 s `scheduleAtFixedRate`), `:54` `run`, `:102` `dispatch` | picks the addressee, routes, calls `BotConversation` |
| Routing rules | `BotMessagingSystem/ChatRoute.java:169` `decide` (`Step` enum `:60`), `:41/:43/:45` speaker/reply/thread-join windows, `:51/:56` hearing bubble 400×150 px | name → facing → thread → reply → closest |
| Player whispers | `src/main/java/net/server/channel/handlers/WhisperHandler.java:109` `CharacterStorage.getBotById`, `:113` `BotConversation.say(brain, user, message, null, whisperBack)`, `:114-117` 1-in-3 busy whisper (`BUSY_WHISPERS` `:101`) | one conversation, delivered back as a whisper |
| Party chat | `src/main/java/net/server/channel/handlers/MultiChatHandler.java:67` → `BotPartySystem/BotPartyChat.java:64` `onPartyChat`, `:94` `BotConversation.talk(..., sayInParty)` | answers on the party channel |
| Buddy chat | `MultiChatHandler.java:63` → `BotBuddies.buddyChat` (`BotBuddies.java:823`), `BotConversation.talk(brain, player, line, reply -> …)` (`:859-867`) | answers on the buddy channel, `SpeechGuard` + `MessyChat.forWhisper` |
| Bot-to-bot | `BotConvo/BotTalk.start()` and `BotConvo/BotThreads.start()`, both at `src/main/java/soloMapling/Environment/EnvironmentManager.java:147-148` | separate engines, never the player brain |
| Ambient chatter (canned packs) | `BotChatterSystem/BotChatter.maybeStartChatter` (`BotChatter.java:76`) from `BotTypes/SocialBot.java:202` and `BotTypes/TownWandererBot.java:154`; forced by GM `!env chatter fire` (`client/command/commands/gm4/EnvironmentCommand.java:293`) | two-bot exchange, no player involved |
| Cluster conversations | `ConversationManager.getInstance().start()` at `EnvironmentManager.java:146` | 2-4 line bot-only scenes near players |
| GM `!brain status` / `!brain ask <line>` | `src/main/java/client/command/commands/gm4/BrainCommand.java:13-18` | endpoint, model, free slots, answered/fell-back |
| GM `!decideshadow status` / `disagree` | `client/command/commands/gm3/DecideShadowCommand.java:22,55,67` | shadow-model comparison report |
| GM `!qa run cv-N` | `client/command/commands/gm3/QaCommand.java:15-18` → `src/main/java/soloMapling/qa/ConvoScenarios.java` | staged conversation scenarios |
| Auto-QA | `t-cv-1…t-cv-12`, `t-pl-1`, `t-cq-1` in `src/main/java/soloMapling/autoqa/AutoQaCatalog.java:437-561`; `t-bb-2` `:185` | nightly chat assertions |
| Dashboard | `src/main/java/soloMapling/dashboard/DashboardExporter.java:174` `ChatStats.snapshot()` | live counters |
| Config | `BOT_LLM_*`, `BOT_DECIDE_*` in `src/main/java/config/ServerConfig.java:226-289`; `-D` switches in `BotConvo/ConvoSwitches.java:18-53` | see Switches |

## How it works

1. **The line is queued, not answered inline.** `GeneralChatHandler.java:66-67` appends a `ChatMessage` to the
   `"primary"` `MessageQueue`; `Dispatcher.run` (`Dispatcher.java:54`) drains the whole queue every 2 s, one retry
   (`RETRIES` `:34`), requeuing a failed line once (`drain` `:74`).
2. **Addressee selection.** `Dispatcher.dispatch` (`:102`) → `ChatRoute.decide` (`ChatRoute.java:169`): a bot named in
   the line wins (`Step.NAME`/`NAME_ASK`), else the bot the player is already talking to / facing, else the thread the
   line answers, else `CLOSEST`. `BotMenus.natural()` (`Dispatcher.java:171`, `src/main/java/soloMapling/ArtificialPlayer/BotMenus.java:100`, default on)
   makes a named bot chat instead of opening its NPC menu; the decide model is only consulted for the ambiguous case and
   only above `BOT_DECIDE_ASK` (0.55, `Dispatcher.java:264`, `ServerConfig.java:289`).
3. **`BotConversation.start`** (`src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:231-273`) resolves the
   session, appends the player's turn to the history (`HISTORY_TURNS` 8, `:46`), records what the bot itself just said
   on the map so "yes" answers the right line (`heard`, `:249-257`), and runs `ask(...)` **off the caller's thread**
   (`:265`). A bot's own line never enters here: `refusesBot` (`:174`, `:206-215`) hands bot-to-bot lines to
   `BotTalk`.
4. **No model, no session.** `say(...)` when `BotBrains.get().isLive()` is false holds the bot near the player and
   returns false so the caller answers from its script (`BotConversation.java:176-183`).
5. **Read the line.** `ask` (`:560`) runs, in order: `ConvoSense.read(line, ChatDecide.get(), ChatDecide.getTone(), …)`
   (`:574-576`; `ConvoSense.read` `:302`/`:362`) → `ChatTriage.rules` for kind/intent/con
   (`BotBrainSystem/ChatTriage.java:331`), and only when kind is `TALK` and a slot is still open does it ask the decide
   model (`ChatTriage.classify` `:342`, `askNow` `:350`) and merge with the confidence thresholds
   `solomapling.chat.intentMin` / `conMin` (0.4, `:353-358`, defaults read at `:379`). `ChatScope.scan` (`BotBrainSystem/ChatScope.java:56/:64`)
   keeps quoted, reported, negated, narrated and warning text out of the intent; `ContentGuard.flirty` feeds the romance
   flag.
6. **Side systems read the same line first.** `BotStateOfMind.offer`, `haggling`, `RudeStreak.onLine`,
   `AffinityReactions.judgeTone`, `BotDrama.onPlayerLine` (which can end the ask outright with a `SCRIPTED` tier,
   `:599-605`), `BotGuilds.answerApplication` (`:611-616`).
7. **Abuse short-circuits.** `if (triage.flagged()) onFlagged(...)` (`:607-610`, body `:1201-1247`): no model call at all,
   `ChatStats.flagged(kind)` (`BotBrainSystem/ChatStats.java:95`), an optional `BotMemory` note, then an in-character
   brush-off, silence, or the "answered the ask" path — all `Tier.SCRIPTED`/`Tier.FLAGGED`.
8. **Small kinds never reach the model.** `ask`'s kind switch (`:640-690`): `IGNORE` → `IGNORED` "filler"; `EMOTE` →
   emote or a scripted whisper; `THANKS`/`BYE` → scripted; `GREETING` → small talk if `RepeatGuard.greetedRecently`
   (`BotConvo/RepeatGuard.java:73`), else a real reply; a grinding bot may ignore a busy line by chance
   (`solomapling.chat.busyIgnoreChance` 0.1, `BotConversation.java:676-686`, `:1585`).
9. **Act.** `BotChatActions.act(bot, chr, intent, line)` (`src/main/java/soloMapling/ArtificialPlayer/BotChatActions.java:120`)
   switches on the intent (`:133-141`) and returns `Result(outcome, fact, scripted, mustMention)` (`:59`), logging
   `ChatStats.action(intent:outcome)` (`:143-145`). It happens *before* any wording, so the game's answer is truth and
   the model may only phrase it. For intents the contract layer handles, `ConvoActions.onIntent` runs instead
   (`BotConvo/ConvoActions.java:105`, gated by `ConvoSwitches.on("contract")`).
10. **Plan the move (m02).** When `ConvoSwitches.planner()` is on and the outcome is plain talk or a party/follow the
    gates already answered (`planReply`, `BotConversation.java:841-845`), `BotReplyView.of` + `ReplyPlanner.plan`
    (`BotConvo/BotReplyView.of`, caller `BotConversation.java:696-703`, `ReplyPlanner.plan` `:286`) decide a `Move`
    (`ReplyPlanner.java:31`: HELLO, HELLO_BACK, SMALL_TALK, ACTIVITY, TRAIN_SPOT, LEVEL_TALK, BUSY, PARTY_YES, PARTY_NO)
    plus the exact facts it may state. Everything else returns `null` and keeps the pre-m02 prompt.
11. **Build the prompt.** Plan on → `ReplyPlanner.prompt(view, plan)` (`:711`, `:528`; rules `:554`, facts prefixed
    "True right now, and the only things you may claim:"). Plan off → `BotChatPrompt.system(...)`
    (`src/main/java/soloMapling/ArtificialPlayer/BotChatPrompt.java:82`) + optional `StateOfMind.prompt` (`:714-719`) +
    `ConvoContract.instructions()` when there is no action (`:719-721`). Either way
    `BotChatPrompt.conversation(system, history, heardTranscript, …)` (`:728-729`, `BotChatPrompt.java:258`).
12. **Route the call.** `BotBrains.get()` (`BotBrainSystem/BotBrains.java:19`) is one `ChatRouter`
    (`BotBrains.fromConfig` `:65`; blank `BOT_LLM_BASE_URL` → `BotBrain.SCRIPTED`). `ChatRouter.reply`
    (`BotBrainSystem/ChatRouter.java:170`) → `route(priority)` (`:143`) → `cloudFirst` (`:131`) → `localRoute` (`:157`),
    then CLOUD / LOCAL / BANK (below). The future is answered on the virtual-thread executor of the brain, never on the
    game thread.
13. **Guard the words.** `OpenAICompatibleBrain.call` (`:212`) cleans with `ReplyCleaner.chat`
    (`BotBrainSystem/ReplyCleaner.java:85` → `voiceProblem` `:49`: several lines, >160 chars, assistant phrasing,
    markdown, emojis, real-world reference, brand, `ContentGuard.aiTalk` `:79`, then `ContentGuard.problem` `:46`).
    A rejected draft is a `fail(...)` and therefore an empty answer, not a bad line.
14. **Ground the claims.** `grounded(...)` (`BotConversation.java:939-956`) asks `StateOfMind.judge` (`:987`) whether
    the draft contradicts the bot's own snapshot; a reject logs `GROUNDING REJECT` (`qaGrounding` `:958-967`) and
    `retryGrounded` (`:968-1000`) re-asks once with "(Not that: …Write a new one-line reply…)", then falls back to
    `StateOfMind.safeLine` (`:941`).
15. **Deliver, all checks again.** `deliverChecked` (`:1455-1570`): `VoiceStyler.style` (`:1463-1467`),
    `guardClaims`/`ClaimActions.guardReply` (`:1472`, `BotConversation.java:1304`; `SAYDO GUARD` log),
    `SpeechGuard.check` (unfilled `{player}`/`{map}` slot → `IGNORED`, `:1474`,
    `BotConvo/SpeechGuard.java:56`), `RepeatGuard.noteGreeting` (`:1483`), `WhisperTrail.of` (`:1495`),
    `HumanTiming.plan` (`:1504`, `BotConvo/HumanTiming.java:65` — 1-6 s floor, per-char typing), `SayDo.said`
    (`:1549`, `BotConvo/SayDo.java:242` — the bot now owes the fact it claimed), `ClaimActions.start` for a brb
    (`:1550-1553`), `ChatStats.reply(tier, latency, bot, detail)` + `ConvoQuality.said` (`:1558-1563`).
16. **Emit.** Map chat: `SocialCommands.BotFullChat` (`BotCommandsPack/SocialCommands.java:22-37`) →
    `BotNoiseFilter.broadcastBotChat(fakechar, PacketCreator.getChatText(id, typed, isGM, (byte) 0), typed)`
    (`src/main/java/soloMapling/ArtificialPlayer/BotMegaphoneSystem/BotNoiseFilter.java:105`) →
    `AttentionHops.onPublicLine`. Ambient lines: `BotAmbientSpeak`/`tryAmbientSpeak` (`SocialCommands.java:40-65`,
    gated by `BotChatTuning.allowAmbient`). Bubbles: `BotChatbubble` (`:85-92`). Whisper/buddy/party replace the sink with
    `deliver`/`sayInParty`.
17. **Bank fallback everywhere.** Empty brain answer → proposal path (`:738-771`) → not fits → `bank(...)`
    (`:785-790`) → `action.scripted` (`:791`) → caller fallback → plan line (`ReplyPlanner.line`, `:795-802`) →
    `fallbackScript` (`:815-834`: factual activity answer, else the bank key for kind/intent/line). Every one of these is
    `Tier.SCRIPTED`; the line is always said.

### Routing and budget, per priority

`ChatTriage` intents act, the router only words. Which channel a reply may use:

| Priority (whose line) | Local slots free | Route when the cloud is not first | Cloud possible? |
| --- | --- | --- | --- |
| `PLAYER` (`Priority` enum `Priority.java:8-14`) | 0 free or local EWMA > `BOT_LLM_LOCAL_SLOW_MS` (2500) | LOCAL if a slot frees up | yes — overflow (`ChatRouter.java:162`), or always in `PLAYERS`/`ALL` mode (`:131-138`) |
| `PARTNER` (buddy/party talk) | ≥1 | LOCAL (`:163`) | only in `ALL` mode below 80 % of the budget |
| `THREAD` (bot-to-bot thread) | == max | LOCAL, `THREAD_MAX_TOKENS` 140 (`:46`, `:164`) | only in `ALL` mode below 80 % |
| `AMBIENT` (legacy `complete`, on-sight roast) | == max **and** no player line for 10 s (`AMBIENT_QUIET_MS` `:42`) | LOCAL | never (`ChatRouter.complete` `:326-330`) |
| forced off | — | `BANK` when `-Dsolomapling.convo.forceBank=true` (`route` `:143-145`) | no |

### Budget limits (`CloudBudget`) and content limits (`ContentGuard`)

| Limit | Value | What happens at it | File:line |
| --- | --- | --- | --- |
| Daily cloud spend | `BOT_LLM_CLOUD_DAILY_BUDGET_USD` (1.0 default, 2.0 live) | `canSpend` false ⇒ the cloud is not even in `cloudFirst`; every call goes local. `dailyUsd <= 0` disables the cloud entirely | `CloudBudget.java:83`, `:92` |
| Worst-case reservation | `budget.cost((chars+1)/2+16, maxOut)` at the reply's max tokens | `reserve` false ⇒ `BANK` for this line, nothing spent | `ChatRouter.java:179-182`, `CloudBudget.java:92-99` |
| Real settlement | provider `usage` tokens × price/Mtok, cache hits at the cached price | charged in full even when it exceeds the reservation (rc6 note) | `CloudBudget.java:100-107`, `:116-127` |
| Low-priority share | 80 % of the day (`LOW_PRIORITY_BUDGET_SHARE`) | `ALL` mode stops sending `PARTNER`/`THREAD`/`AMBIENT` to the cloud at 80 %; `PLAYER` may use the last 20 % | `ChatRouter.java:44`, `:131-138` |
| Day boundary | server local day (`ZoneId.systemDefault`), persisted to `data/convo/cloud-budget.json` | spend resets at midnight; a restart keeps the day's total | `CloudBudget.java:69`, `:207-232`, `BotBrains.java:55-60` |
| Local concurrency | `BOT_LLM_MAX_CONCURRENT` (2), fair semaphore (`slots` `:60`, `:77`); cloud hardcoded 4 | no free slot before the deadline ⇒ empty answer ⇒ BANK (`:224`) | `BotBrains.java:80` |
| Per-player rate | `BOT_LLM_PLAYER_MIN_INTERVAL_MS` (1500) | `fail("rate limited")` ⇒ empty ⇒ BANK (`complete` `:90-97`, `reply` `:113-119`) | `OpenAICompatibleBrain.java:62` |
| Draft shape | one line, ≤160 raw chars, ≤70 sent, no markdown/emoji/thinking block | `voiceProblem` ⇒ `fail` ⇒ empty ⇒ BANK | `ReplyCleaner.java:14`, `:43`, `:49`, `:85` |
| Voice | assistant phrasing, AI talk, brand, real-world/era anachronism | `ContentGuard.aiTalk` then `ContentGuard.problem` ("explicit", "slur", "off-limits insult") | `ReplyCleaner.java:49-84`, `ContentGuard.java:46`, `:79` |
| Emotes/flirt | a line with >1 emoji is refused; `ContentGuard.flirty` feeds the romance flag, never blocks chat | `flirty` only steers tone | `ReplyCleaner.java:49-84`, `ContentGuard.java:92` |

### Intent → responder → tier → code

| Intent / kind (`ChatTriage`) | Responder (game decision) | Tier / budget it consumes | File:line |
| --- | --- | --- | --- |
| `IGNORE` ("" / brb / afk / asdf) | nothing, `IGNORED` "filler" | none | `ChatTriage.java:102`, `BotConversation.java:642` |
| `EMOTE` (lol, ^^, nice) | emote or scripted whisper | `EMOTE`/`SCRIPTED` | `ChatTriage.java:104`, `BotConversation.java:645-655` |
| `GREETING` | bank small talk, else a planned HELLO / HELLO_BACK | local LLM, `PLAYER` | `ChatTriage.java:107`, `BotConversation.java:663-673`, `ReplyPlanner.plan` `ReplyPlanner.java:303-307` (SMALL_TALK when the bot already spoke, else HELLO for a stranger / HELLO_BACK) |
| `THANKS` / `BYE` | bank line | `SCRIPTED` | `ChatTriage.java:109-111`, `BotConversation.java:657-661` |
| `TALK` + `Intent.NONE` | free chat, plan or pre-m02 prompt | local LLM, `PLAYER` (cloud overflow) | `BotConversation.java:711-731` |
| `Intent.PARTY` | `BotChatActions.party` — accept/decline/wary/already; chance `SOCIAL_ACCEPT_CHANCE`/`TRAINING_ACCEPT_CHANCE` capped 0.95 | LLM may word `PARTY_YES`/`PARTY_NO` only | `BotChatActions.java:171-217`, `:133`, `ReplyPlanner.java:335-345` |
| `Intent.FOLLOW` / `COME_WITH_ME` | same `party(..., true)`; follow only for `SocialBot` | as above (`FOLLOW_ACCEPTED`/`FOLLOW_DECLINED`) | `BotChatActions.java:134`, `:175-184` |
| `Intent.TRADE` | `BotChatActions.trade` → `TRADE_SHOP` (merchant), `BotDirectTrade.chat` → `TRADE_OFFER`/`TRADE_DECLINED` | action `SCRIPTED`; wording may be LLM | `BotChatActions.java:233-253`, `:135` |
| `Intent.WHERE_TRAIN` | `whereTrain` — the bot's own spot list | scripted (`TRAIN_SPOT` only when the planner words it) | `BotChatActions.java:279`, `:136` |
| `Intent.WHERE_AT` | `whereAt` — the bot's map | scripted | `BotChatActions.java:293`, `:137` |
| `Intent.QUESTS` / `RECOMMEND_QUEST` | `quests(bot, player)` | scripted | `BotChatActions.java:263`, `:138-139` |
| life-runtime party map (`103000800`, `221023200`, `200080101`, `930000000`, `261000021`) | deferred: `PARTY_DECLINED`, fact `PENDING_LIFE:` , line "lemme check a sec" | `SCRIPTED` | `BotChatActions.java:125-131` |
| `Con.SCAM` / `MANIPULATION` / `HARASSMENT` / `RWT` | `onFlagged`: no model, note, brush-off or silence | `FLAGGED` / `SCRIPTED` | `BotConversation.java:1201-1247`, `ChatTriage.java:154-172` |
| action reply that fails `fits` (says yes to a refusal) | bank line for the outcome | `SCRIPTED` | `BotChatActions.java:72-114`, `BotConversation.java:785-790` |

### DecideShadow (measured, never spoken)

`BotConvo/DecideShadow.java:44-70` runs candidate classifiers beside the production one and writes their answers to
`data/autoqa/decide-shadow/<UTC day>.jsonl` (`:529-531`); nothing it says ever reaches a bot. Built-in models (`:261`,
never read keys): `gliclass` `192.168.0.17:8911`, `onejev` `192.168.0.17:8910` (`OneJev1.6-0.8B`), `jev`
`https://api.typesafe.ai` (`jev-latest`, key file **not read by this document**), `gliner` `192.168.0.17:8900` (the
production sanity check). Off unless `-Dslums.decide.shadow=<names>` (`:74`, `fromProperties` `:228-258`); `queue` 256,
`timeoutMs` 2000, per-job deadline `solomapling.chat.decideTimeoutMs` 400, `current` default `gliner`. Rows are offered
from `ConvoSense` (`ConvoSense.java:392` → `DecideShadow.offer` `:327`) on the caller's thread with no I/O, dropped when
the queue is full (`:347`). `-Dslums.decide.shadow.text=false` keeps only the line length (live, ticket 108), so live
rows cannot be replayed. Reports: `!decideshadow status|disagree`
(`client/command/commands/gm3/DecideShadowCommand.java:22,55,67`), `DecideShadowReport.summarize/disagreements/json`,
replay `DecideShadowReplay.main` over `logs/convo/transcript-*.jsonl`, CLI `tools/convo/decide-shadow`.

### Canned chatter (no player, no model)

`BotChatter` (`BotChatterSystem/BotChatter.java:38`) pairs a `SocialBot`/`TownWandererBot`
(`isParticipant` `:183`) with the nearest participant within 180 px (`:47`, `findPartner` `:152`) at a 10 % chance per
idle tick scaled by `BotChatTuning.multiplier` (`:104`; town ×1.5, hunting ×0.15, dungeon ×0.01,
`src/main/java/soloMapling/ArtificialPlayer/BotChatTuning.java:63`), 30 s cooldown (`:54`), 22 s cap (`:52`), 2.2-3.8 s
turn pause (`:57-58`), 25 % emote (`:60`); a fallback exchange (`:66`) if the pack cannot resolve. Lines come from
`BotDialoguePack/TownChatterDialogue.yaml` (`TownChatterLines.java:21`, `randomExchange` `:49`, deck-shuffled) with
`{MAP}` resolved against the speaking bot and unresolvable lines dropped (`speakResolved` `:214`). The rest of the
canned corpus is `BotDialoguePack/*.yaml` (28 packs, 14 059 lines; e.g. `ConversationDialogue.yaml` 2 937,
`TrainingBotDialogue.yaml` 1 656, `SocialBotDialogue.yaml` 1 118), read through
`BotDialogueHandler.getDialogueCon/getRandomResolvedLine` (`BotDialogueHandler.java:176`, `:249`, SayDo-backed pick
`:321`).

## Data

| Path | Written by | Contents |
| --- | --- | --- |
| `data/convo/cloud-budget.json` | `CloudBudget.save` (`BotBrainSystem/CloudBudget.java:225`), path from `BotBrains.java:55-60` | day, spent, calls, cache hit/miss tokens, cap |
| `logs/convo/transcript-<day>.jsonl` | `ConvoQuality` (`BotConvo/ConvoQuality.java:89`) | every said line, tier, context; player as a hash |
| `data/convo/ratings.jsonl`, `tune.json`, `bank-weights.json` | `ConvoQuality` (`:81`, `ConvoTune.java:51`) | `!rate` feedback, keep/blacklist weights |
| `data/convo/quality/` | `ConvoDailyScore` (`:260`) | daily scored sample |
| `data/convo/routing/<day>.jsonl` | `RouteLog` (`BotMessagingSystem/RouteLog.java:57`) | routing decisions; only with `-Dsolomapling.convo.routeLog=true` or auto-QA |
| `data/autoqa/saydo/<day>.jsonl` | `SayDo` (`SayDo.java:696-698`) | every claim and whether the bot backed it |
| `data/autoqa/decide-shadow/<day>.jsonl` | `DecideShadow` (`:529-531`) | shadow classifier answers |
| `src/main/java/soloMapling/ArtificialPlayer/BotConvo/chatbank.bin` | source resource (`ChatBank.java:42`, `:171`) | the line bank the fallbacks pick from |
| `src/test/resources/chat/*.tsv` / `*.json` | tests | labelled lines, holdouts, decide answers |

In-memory: `ChatStats` counters (`ChatStats.java:26` `Tier {LLM, SCRIPTED, EMOTE, IGNORED, FLAGGED}`, per-endpoint
`Endpoint` `:108`, cloud and decide counters `:200-246`), one `Session` per (bot, player) of at most 8 turns
(`BotConversation.java:46`) expiring after 60 s idle (`:47`, sweeper `:1600-1616`), `SayDo` open claims (2000 / 4 per
bot, 400 recent, 30 s dedupe, `SayDo.java:50-53`).

## Switches

`config.yaml` keys (defaults are `ServerConfig.java`'s; `config.yaml` itself is not in this repo):

| Key | Default | Live | Notes |
| --- | --- | --- | --- |
| `BOT_LLM_BASE_URL` | `""` (`ServerConfig.java:226`) | set, `192.168.0.17:8095/v1` (qwen9b) — the rollback (`AGENTS.md:55`) | blank ⇒ `BotBrain.SCRIPTED`, chat never calls out |
| `BOT_LLM_MODEL` | `""` (`:228`) | qwen9b model id | |
| `BOT_LLM_MAX_TOKENS` | 40 (`:229`) | live | one line; THREAD bumps to 140 (`ChatRouter.java:46`) |
| `BOT_LLM_TIMEOUT_MS` | 7000 (`:231`) | live | also the connect timeout (`OpenAICompatibleBrain.java:76`) |
| `BOT_LLM_MAX_CONCURRENT` | 2 (`:232`) | live | local `Semaphore`, fair (`:80`); cloud hardcodes 4 (`BotBrains.java:78-92`) |
| `BOT_LLM_PLAYER_MIN_INTERVAL_MS` | 1500 (`:233`) | live | per-player rate limit → `fail("rate limited")` (`:115`) |
| `BOT_LLM_CLOUD_BASE_URL` | `""` (`:240`) | `https://api.deepseek.com` (`docs/release/1.6.2-config-append.yaml:5`) | last-row cloud switch of 1.6.2 |
| `BOT_LLM_CLOUD_MODEL` | `""` (`:242`) | `deepseek-flash` (append `:6`) | |
| `BOT_LLM_CLOUD_MODE` | `overflow` (`:260`) | `all` (append `:9`) | unknown values ⇒ `overflow` (`ChatRouter.java:59`) |
| `BOT_LLM_CLOUD_DAILY_BUDGET_USD` | 1.0 (`:244`) | 2.0 (append `:10`) | hard cap, reserved before the call |
| `BOT_LLM_CLOUD_PRICE_IN/OUT_PER_MTOK` | 0.30 / 1.20 (`:246`, `:251`) | live | DeepSeek peak prices 2026-10-22; off-peak is half, so the day's spend over-estimates |
| `BOT_LLM_CLOUD_PRICE_IN_CACHED_PER_MTOK` | -1 (`:250`) | 0.006 (append `:11`) | negative ⇒ hits billed as normal input |
| `BOT_LLM_CLOUD_TIMEOUT_MS` | 6000 (`:243`) | live | |
| `BOT_LLM_LOCAL_SLOW_MS` | 2500 (`:262`) | live | EWMA above this ⇒ a `PLAYER` line overflows to the cloud |
| `BOT_MENUS_DECIDE_ASK`/`BOT_DECIDE_ASK` | 0.55 (`:289`) | live | minimum confidence to let decide route a line |
| `BOT_DECIDE_URL` | `""` (`:281`) | `http://192.168.0.17:8913` (`docs/release/1.6.4.conf:202`) | intent+con classifier (`jev-latest`, `:203`) |
| `BOT_DECIDE_TONE_URL` | `""` (`:283`) | `http://192.168.0.17:8910` (`:202`) | 1.6.1 split; unset ⇒ no tone call |
| `BOT_DECIDE_TIMEOUT_MS` | 600 (`:288`) | live | clamped ≥50 ms per call |

`-D` switches (all default-off unless stated):

| Property | Default | Live | Effect |
| --- | --- | --- | --- |
| `solomapling.convo.planner` | false (`ConvoSwitches.java:32`) | **true** (`docs/release/1.6.4.conf:267,271`) | m02: plan the move before wording |
| `solomapling.convo.planner` in 1.6.2 | false | off in the 1.6.2 freeze (`:252`) | |
| `solomapling.convo.router` | true (`:18`) | on | off ⇒ `LOCAL` only (`ChatRouter.java:147`) |
| `solomapling.convo.forceBank` | false (`ConvoSwitches.java:23`) | off | every line scripted, no LLM call at all (`ChatRouter.java:145-148`) |
| `solomapling.convo.{contract,sense,card,persona,styler,timing,bank,threads,quality,stateOfMind,grounding}` | true (`:18`) | on | individual layers; `stateOfMind=false` drops the snapshot (`StateOfMind.promptOn` `StateOfMind.java:48`), `grounding=false` the checker (`checkOn` `:53`) |
| `solomapling.convo.intentMin` / `toneMin` | 0.4 / 0.8 (`ConvoSense.java:385`, doc `:25-26`) | on | decide-confidence thresholds in `ConvoSense`; its con threshold is `solomapling.chat.conMin` (`:386`) |
| `solomapling.chat.decide` / `decideTimeoutMs` / `decidePerSecond` | true when URL set / 400 / 8 (`ChatDecide.java:32-34`) | on | classifier client |
| `solomapling.chat.intentMin` / `conMin` | 0.4 / 0.4 (`ChatTriage.java:353-358`, `threshold` `:379`) | on | triage thresholds |
| `solomapling.chat.replyTemperatureMax` | 0.7 (`OpenAICompatibleBrain.java:279-282`) | on | temperature cap for replies |
| `solomapling.chat.typingScale` | 1.0 (`BotConversation.java:1574`) | on | typing-delay multiplier |
| `solomapling.chat.logReplies` | true (`ChatStats.java:18,87`) | on | per-reply log line |
| `solomapling.chat.busyIgnoreChance` | 0.1 (`BotConversation.java:1585`) | on | grinding bot ignoring a line |
| `solomapling.chat.ambientMapGapMs` / `ambientWorldPerMinute` | 3000 / 60 (`BotChatTuning.java:93,104`) | on | ambient line budget |
| `solomapling.chat.townMultiplier` / `huntingMultiplier` / `dungeonMultiplier` | 1.5 / 0.15 / 0.01 (`BotChatTuning.java:63`) | on | chatter/ambient scaling by map type |
| `solomapling.chat.natural` | true (`BotMenus.java:100`) | on | naming a bot chats instead of opening its menu |
| `solomapling.chat.doubleClickMenu` | `after` (`CharInfoRequestHandler.java:42`) | on | menu on double-click |
| `solomapling.messyChat` | true (`BotConvo/MessyChat.java:34`) | on | typos/casing on said lines |
| `slums.saydo` | true (`SayDo.java:163`) | on | say-do verification (off ⇒ no verdicts) |
| `slums.bottalk` (+ `slums.bottalk.llmPer10Min` 6) | true in 1.6.4 (`BotTalk.java:161`); llm cap 6 | **true** (`docs/release/1.6.4.conf:271`) | bot-to-bot engine |
| `slums.decide.shadow` (+ `.queue`, `.timeoutMs`, `.text`, `.current`, `.url/.model/.keyFile`) | unset = off (`DecideShadow.java:74`) | off (live runs were 1.6.0, ticket 108) | shadow classifiers, logged only |
| `solomapling.convo.routeLog` | off (`RouteLog.java:29`) | off | write routing JSONL |
| `solomapling.partyChat` | true (`BotPartyChat.java:58`) | on | party-chat replies |

**Which LLM runs where.** Live 1.6.0.4 wrote bot replies with `qwen9b` on `192.168.0.17:8095/v1` (2 slots) and
classified with GLiNER Decide on `:8900` (`AGENTS.md:55`). From 1.6.2 live replies move to the DeepSeek 4.1 Flash API
through the `BOT_LLM_CLOUD_*` keys — mode `all`, $2/day, key only in the unit's `EnvironmentFile`
(`docs/release/1.6.2-config-append.yaml:1-11`; names of the key files are not reproduced here) — and the classifiers
move to `:8913` (intent+con) and `:8910` (tone) with model `jev-latest` (`docs/release/1.6.4.conf:201-203`); `qwen9b`
and `:8900` stay as the rollback (`:184-186`). TEST: unknown — `start-test.sh` is not reachable from this repo, but
`AGENTS.md:55` says TEST already ran the DeepSeek path.

**Network needs.** Only three paths leave the process: `OpenAICompatibleBrain` (local qwen9b or the DeepSeek cloud) at
`/chat/completions` (`OpenAICompatibleBrain.java:76-78`), `ChatDecide` at `/v1/systemone` (`ChatDecide.java:192-197`),
and `DecideShadow`'s models (TEST only). Everything else — bank, packs, chatter, planner scripts, guards — is local.

**When the LLM is down:** the call fails or returns empty (`ChatRouter.java:206` `cloudFallbacks++` →
`viaLocal(afterCloud(priority))` `:207`; `OpenAICompatibleBrain.fail` `:376`), so a `PLAYER` line goes to the local
model if a slot frees inside its deadline, else `BANK` → empty reply → `BotConversation`'s scripted chain
(`:785-834`). The player still gets an answer, marked `Tier.SCRIPTED` and logged `llm fell back`. With the local base URL
blank the brain is `SCRIPTED` and `say(...)` never even opens a session (`BotConversation.java:176-183`); the scripted
greeting/actions still answer through `talk(...)`. Decide down ⇒ `ChatTriage.rules` only (`classify` `:342-357`), tone
never applied.

## Invariants

- A bot never mentions being an AI/bot/model (`ContentGuard.AI_TALK` `:83`, `ReplyCleaner.voiceProblem` `:49-75`); the
  whole draft is dropped instead of being sent.
- The game's decision precedes the words: `BotChatActions.act` runs before the prompt, and a reply that contradicts the
  outcome is replaced by the outcome's bank line (`fits` `BotChatActions.java:72`, `BotConversation.java:785-790`).
- A claim that the bot cannot back is either retried or refused; `SayDo` resolves every open claim against real events
  (`SayDo.judge` `:406`) and a failed verdict is logged, never hidden.
- A line with an unfilled `{slot}` is never sent (`SpeechGuard.java:56`, `// b247` at `SocialCommands.java:23`).
- No repeat of the same line on the same map inside 15 min, no re-greeting inside 10 min (`RepeatGuard.java:116`, `:73`).
- Search and packets: a bot's own `Character` is the actor, `getClient().getPlayer()` is null; chat reaches the world via
  `BotNoiseFilter.broadcastBotChat` (`:105`), never through a client connection.
- The cloud is capped before it is called: worst case reserved (`ChatRouter.java:179-182`), settled at real cost
  (`CloudBudget.settle` `:108/:116`), and low priorities stop at 80 % of the day's budget (`:44`, `:138`).
- Nothing on the player path blocks the game thread: `BotConversation.start` dispatches `ask` to
  `ExecutorServiceManager.runAsync` (`:263-272`) and the brain answers on its own virtual-thread executor.

## Origin

All **custom**. `git log --follow` tails (oldest first) show every file born in or after the SoloMapling fork, not
inherited from upstream MapleRoot:

| File | Oldest commits |
| --- | --- |
| `BotConversation.java` | `1512c08a6` 2026-10-19 "Merge the SoloMapling persistence fork onto the live baseline"; `29577d23d` 2026-10-20 "Chat fast/slow brain and natural actions, phase 1 (tickets 36, 37)" |
| `ChatRouter.java`, `CloudBudget.java` | `ea9265f0c` 2026-10-22 "Ticket 59 phase 3: the router and the cloud budget" |
| `ChatTriage.java`, `BotChatActions.java` | `29577d23d` 2026-10-20 |
| `ConvoSense.java` | `18a7942e3` 2026-10-22 "Ticket 59 phase 1: reply + action contract, gates, actions, GLiNER intent/tone/entities" |
| `ReplyPlanner.java` | `b08470e96` 2026-10-31 "Decide a bot's reply to a player before wording it (m02, switch off)" |
| `SayDo.java` | `93dfde640` 2026-10-23 "Make bots do what they say…" |
| `DecideShadow.java` | `37c384e2c` 2026-10-27 "Shadow-run gliclass, OneJev and Jev against the current chat model on TEST" |
| `BotChatter.java`, `BotDialogueHandler.java`, `TownChatterDialogue.yaml` | `03d13cb21` 2026-10-18 "Establish deployable MapleRoot server baseline" (the earliest SoloMapling commit — these are SoloMapling files laid down with it) |

Nothing here is a modified upstream chat path.

## Tests and QA

Unit tests (`src/test/java/soloMapling/ArtificialPlayer/`), 15 in `BotBrainSystem/` and 59 in `BotConvo/`:

| Area | Classes |
| --- | --- |
| Triage/read | `BotBrainSystem/ChatTriageTest`, `ChatDecideTest`, `ChatDecideContextTest`, `ChatDecideEvaluation`, `ChatLabelledSet`, `ChatVoiceSample`, `DecideProvenanceTest`, `BotConvo/ConvoSenseTest`, `ConvoSenseSplitTest`, `ConvoSenseEvaluation`, `PlayerChatMeaningRegressionTest`, `StateOfMindTest` |
| Routing/budget | `BotBrainSystem/ChatRouterTest`, `CloudBudgetChargeTest`, `CloudCachePriceTest`, `ChatStatsTest`, `OpenAICompatibleBrainTest`, `DecideBenchmarkBridge(+Test)` |
| Wording/guards | `BotBrainSystem/ReplyCleanerTest`, `BotConvo/ReplyPlannerTest`, `ReplyReplayTest`, `ReplyReplay`, `SayDoTest`, `SpeechGuardTest`, `RepeatGuardTest`, `NameGuardTest`, `NameGuardReport` (helper), `MessyChatTest`, `HumanTimingTest`, `ClaimRulesTest`, `VoiceStylerTest`, `ScriptBackingTest`, `ChannelMentionsTest`, `BotChatActionsTest`, `BotChatPromptTest`, `BotChatTuningTest` |
| Whole conversation | `soloMapling/ArtificialPlayer/BotConversationFlowTest`, `BotConversationHeardTest`, `BotConversationHoldTest`, `PersonalSpaceChatTest`, `BotTalkGuardTest`, `ConversationScriptBackingTest`, `ConvoEvalPromptsTest`; `BotConvo/ConvoConversationFlowTest`, `ConvoTalkFallbackTest`, `ConvoGatesTest`, `ConvoContractTest`, `ConvoActionsTest`, `ConvoQualityTest`, `ConvoDailyScoreTest`, `ConvoTuneTest`, `ConvoPersonaTest`, `ConvoEntitiesTest`, `ConvoScopeTest`, `BankClaimsTest`, `ChatBankTest`, `DecideShadowTest`, `DecideShadowSelfCheckTest`, `LfgPartyTest`, `WhisperTrailTest`, `TalkBrainTest`, `TalkPlannerTest`, `TalkQaForcedYesTest`, `TalkQaFreePairTest`, `TrainSpotReplyTest`, `LevelTalkTest`, `TrainingTipsTest`, `PromiseIntentsTest`, `PartnerMemoryTest`, `RelationshipCardTest` |
| Bot-to-bot | `BotConvo/BotTalkBudgetTest`, `BotTalkGreetBackTest`, `BotTalkInviteToMakeTest`, `BotTalkSayDoTest`, `BotTalkSocialActsTest`, `BotTalkTickPerfTest`, `BotTalkTickThreadTest`, `BotThreadsTest`, `BotThreadsAfterTest`, `BotThreadsBeefTest`, `BotThreadsDoerTest`, `BotThreadsPartiedAskTest`, `BotThreadsTuneTest`, `SpanishAccentTest` |
| Packs | `soloMapling/ArtificialPlayer/BotDialogueHandlerTest`, `BotDialogueBackedPickTest`, `DialoguePackCheckTest`, `DialogueSlotResolutionTest`, `BotDialoguePack/DialogueBrandTest` |

Fixtures: `src/test/resources/chat/{labelled-lines.tsv, convo-lines.tsv, holdout-lines.tsv, convo-holdout.tsv,
convo-decide-answers.json, convo-holdout-answers.json, replies-fixture.jsonl, thread-lines-2026-10-23-25.jsonl}`.
**These tests were not run for this document** (no builds allowed in this review).

Auto-QA check ids (`AutoQaCatalog.java`): `t-bb-2` `:185` (buddy chat), `t-cv-1` `:437` (plain-word requests,
`Expects.sayDo(130)`), `t-cv-2` `:452` (haggle), `t-cv-3` `:460` (apology/insult/flirt), `t-cv-4` `:477` (four voices,
human timing ≥ 900 ms), `t-cv-5` `:485` (join a bot thread), `t-cv-6` `:496` (`!rate`), `t-cv-7` `:503` (bank only, no
`llm` tag, `Expects.sayDo(130)`), `t-cv-8` `:513` (bot stays within 150 px), `t-cv-9` `:521`
(`MEANING PASS refusals-stories=4 warnings=3`), `t-cv-10` `:528` (party chat), `t-cv-11` `:538` (both decide endpoints,
`SPLIT PASS`), `t-cv-12` `:545` (rude spread), `t-cq-1` `:553` (daily quality), `t-pl-1` `:559` (planner promises).

`!qa` scenarios (`src/main/java/soloMapling/qa/ConvoScenarios.java`, GM at Henesys `:26`): `cv-1` `:208`, `cv-2` `:235`,
`cv-3` `:265`, `cv-4` `:283`, `cv-5` `:308`, `cv-6` `:336`, `cv-8` `:363`, `cv-9` `:388`, `cv-10` `:39`, `cv-11` `:416`,
`cv-12` `:78`, `cv-13` `:474` (refuses unless `-Dsolomapling.convo.planner=true`, `:482`).

Not covered: the speech of `BotTalk`/`BotThreads` under prompt changes (only `BotTalk*`/`BotThreads*` unit tests), the
cloud's actual cost accounting against a provider bill, and any live end-to-end run — no check exercises the DeepSeek
endpoint from CI.

## Known issues

| Id | One line |
| --- | --- |
| b099 | Bots invent game facts (wrong maps/mobs) and stay wrong after a correction (`/root/work/STATUS-1.6.4.md:55`) |
| b101 | Bots' answers contradict their own state — where they are, what is there (`:56`) |
| b240 | "Regular bots say they're grinding while standing in town" (`:59`) — planner path 1.6.2 + 163-m02words |
| b276 | QA feed misses map-scoped PLAN/ROUTE lines — QA-tool defect, not product (`:78`) |
| b284 | Auto-QA at 10 lanes gives false failures: 8 of the 22:00 run's FAILs pass when rerun serially (`:80`) — the chat checks are among them |
| b285 | Ticket 178: an alt can offer an upgrade for a sibling alt, but the trade command refuses it unless the owner is present (`:81`) — bot trade, reached through the same `BotChatActions.trade` path |
| b287 | t-xc-5: a PANIC-style scroll trader holding a Chaos/White Scroll never lists it (`:83`) — bot economy lane, only adjacent to chat |

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/OpenAICompatibleBrain.java:62` — `lastCallByPlayer`
is a plain `ConcurrentHashMap` that is only ever read and written (`:90-97`, `:113-119`), never evicted; on a server with 3,000 bots and an unbounded roster
of player ids it grows without eviction (small, but it is a leak by construction).

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotChatActions.java:125-131` — the life-runtime party deferral
is gated on a hardcoded set of five map ids; a `PARTY` ask on any other map takes the immediate accept/decline path even
when `LifeRuntime` would have wanted to answer.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotBrainSystem/ChatDecide.java:416-421` — the decision cache key is
`normalise(line)` + the last ≤5 earlier lines + the question names, so a pair's first line (no earlier context) is cached
globally for 30 min (`:53-54`): the same "sure" or "u there" asked to two different bots, on two different maps, reuses
one intent/con answer, and `ChatStats.decideCacheHit` (`:250`) counts it as a hit rather than as a possibly wrong one.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotConversation.java:1574` — `typingDelay(...)` is defined but has
no caller anywhere in `src/main/java` (`grep -rn "typingDelay"` finds only the declaration); the live path uses
`HumanTiming.typingMs` (`BotConvo/HumanTiming.java:53`). Dead code that still documents itself as the delay rule is a trap
for the next reader of this area.

NEW-FINDING: `src/main/java/soloMapling/ArtificialPlayer/BotConvo/HumanTiming.java:84-87` — the "late" plan picks
`late = 15_000 + rng.nextInt(45_001)`, i.e. up to exactly 60 000 ms, while the auto-QA reply wait is
`SAID_CAP_S = 60` s (`src/main/java/soloMapling/autoqa/AutoQaCatalog.java:74`). The 4 % `lateChance` tail therefore ends
at the same instant the check gives up, so the slowest legitimate replies can never be observed — the comment at
`AutoQaCatalog.java:69-73` records exactly this ("~3% of replies late (up to ~55 s; t-cv-7 timed out at 12 s on 10-28)"),
and the wait was raised from 12 s to 60 s rather than the tail being capped below it.

## State

**partial.** The read → act → say pipeline, the guards, the bank fallback, the budget and the QA suite are implemented and
unit-tested (`BotBrainSystem` 15 + `BotConvo` 59 test classes, `AutoQaCatalog.java:437-561`). Two things keep it short of
"working": the model still gainsays its own state in production (b099, b101, b240 marked *shipped, waiting for proof* in
`/root/work/STATUS-1.6.4.md:55-59`), and the planner — the mechanism that was supposed to fix exactly that — only went
on for live on 2026-11-05 (`docs/release/1.6.4.conf:267`) after being frozen off for the 11-02 run (`:252`), with its
proof being a single TEST pass of `t-pl-1`/`t-cv-1`. Evidence of the gaps: `AutoQaCatalog.java:69-74` (chat waits, b222),
`/root/work/STATUS-1.6.4.md:78-83` (QA-feed and 10-lane false failures).

## Open questions

- Does the live `deepseek-flash` prefix cache actually hit? `docs/release/1.6.2-deploy-steps.md:181` notes a sub-half-hour
  cache ratio implying no prefix reuse; `BOT_LLM_CLOUD_PRICE_IN_CACHED_PER_MTOK` (0.006) and the hourly
  `cloudCacheHitPercent` line (`ChatStats.java:233`) are the only instruments, and no operator read-out was found here.
- Is `BOT_LLM_CLOUD_MODE=all` (live, append `:9`) the intended steady state, or was it a 1.6.2 experiment? In `all` every
  `PLAYER` line goes to the cloud first, so `qwen9b` only serves `PARTNER`/`THREAD`/`AMBIENT` and failures.
- Should the life-runtime party deferral (`BotChatActions.java:125-131`) be map-set driven at all, or derived from
  `LifeRuntime` state?
- What is TEST's actual chat wiring today? `start-test.sh` is unreachable from this repo; `AGENTS.md:55` is the only
  statement that TEST is on DeepSeek.
- Is `DecideShadow` expected back on for 1.6.4? It is off in `docs/release/1.6.4.conf` and the `text=false` mode exists
  only for live runs that no longer happen.
