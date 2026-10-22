# 59: Natural bot conversations (reply + action contract, memory cards, personas, timing, threads, quality loop)

**Area:** Real bots / chat
**Depends on:** 36 (fast/slow chat brain), 37 (natural conversations), 58 (affinity), 1.5.0 drama, guilds, economy
**Status:** designed and built on `bots-convo-150` (from `test-1.5.0-rc4`, owner decision 2026-10-22: "squeeze it in,
we're not in a rush"). Not deployed.

## Goal

A real player can talk to any bot over several lines and it reads like a 2008 player who knows them: it remembers
what happened between them, it has its own way of typing, it answers at human speed, and when it says "omw" it walks
there. Bots on a busy town map now and then talk to each other, and a player can jump in. The owner can rate lines in
game, sees a daily scored sample, and bad patterns feed back into the prompts. The LLM stays cheap and bounded: real
players first, a local model first, a capped cloud overflow, and a large pre-generated line bank whenever the LLM is
busy, slow, over budget or down.

## Architecture

```
player line -> Dispatcher / whisper / buddy chat -> BotConversation.ask
  1. ConvoSense (fast, no LLM)      rules, then ONE Decide call: intent (15 labels) + tone (4) + con (5)
                                    entities by rules against String.wz names and the players on the map
  2. con flag?                      brush-off (bank), memory note                  (ticket 37, unchanged)
  3. drama / guild application      scripted scene answers                          (unchanged)
  4. tone -> affinity               kind / rude / flirty move the bot's feelings   (no LLM call)
  5. intent -> ConvoGates -> ConvoActions   server decides and DOES it (party, come, buff, quote ...)
  6. reply text                     ChatRouter: local Qwen -> cloud overflow (players only) -> ChatBank
       prompt = persona card + relationship memory card + situation + outcome + contract
       model returns text + at most one [do:action]; the tag is checked against the gates again
  7. VoiceStyler                    persona casing / abbreviations / typos on every line (LLM, bank, scripted)
  8. HumanTiming                    typing delay, split lines, late reply / brb, ignore while fighting
  9. ConvoQuality                   remembers the last line per player (!rate), transcript, daily score
```

New code lives in `soloMapling.ArtificialPlayer.BotConvo` (one package, one switch per part). `BotConversation`
keeps its job as the session holder and calls into it; `ChatTriage`, `BotChatActions`, `ScriptedReplies` stay as the
lower layer and the fallback.

## 1. Reply + action contract (owner decision 1)

**Shape.** Every model reply is one chat line plus at most one action tag at the end:

```
ya sure omw [do:come]
lol no [do:none]          (or no tag at all: most replies)
```

- `ConvoContract.parse(raw)` takes the first `[do:<name> <arg>?]`, strips every tag, and returns
  `Reply(text, action, arg)`. An unknown name is NONE (counted). A JSON object (`{"say":..,"do":..}`) is read the same
  way, because small models sometimes answer in JSON. The text then goes through `ReplyCleaner` as today.
- A tag only proposes. Nothing in the tag, the text or the player's line can skip a gate.

**Two ways an action happens:**

1. **Intent first (the common case).** `ConvoSense` reads what the player wants. `ConvoGates` decides yes or no with
   the facts, `ConvoActions` does it, and the prompt's last line says what the game did ("The game already did this,
   and your reply must say so: ..."). The model's tag must match the decided action; a reply whose words contradict
   the outcome (a "sure" to a refused party) is replaced by a bank line for that intent and outcome (ticket 37's
   `fits`).
2. **Model proposal (no intent found).** The player just talks ("these drakes keep killing me lol") and the model
   tags `[do:help]`. Only the **post-hoc list** below may start this way, and only when the gates pass. A refused
   proposal keeps the text only if it promises nothing; otherwise the bank's "declined" line for that action replaces
   it. Economy actions (trade, haggle, quote, gift) and romance steps (date) **never** start from a model tag: they
   need the player's own words.

**Whitelist and gates** (`ConvoAction`, `ConvoGates`; every gate is code; "busy" = `PlayerBusy`):

| Family | Action | Does (existing system) | Gates | Post-hoc |
|---|---|---|---|---|
| Social | `party` | arms the invite window / invites (`BotRecruitManager.rollPartyAsk`) | bot type answers invites, not in a party, not trading, not wary/hostile, recruit caps and roll | yes |
| | `follow` | party -> follower (`SocialBot`/`TrainingBot.naturalAction`) | as party, follower cap 30 | no |
| | `buddy` | stock buddy request (`BotBuddies.offerAsFriend`) | known/friend, player's daily cap, world hourly cap, list space, not wary | yes |
| | `guild` | application answer + real invite (`BotGuilds.answerApplication`) | guild rules (leader/officer, feelings, level) | no |
| | `leave_guild` | none in 1.5.0: words only ("nah im staying") | always refused (guild quits stay `GuildRules`') | no |
| | `date` | `BotRomance` ask-out rules | `@romance on`, not taken, `RomanceRules.saysYes` | no |
| | `come` | walks there (`GCMovement.travelTo` to the player's map or a named map; `WorldEventSteer.hold` while there) | bot is a TrainingBot/SocialBot/wanderer free for a scene, not in an event, not hostile, map reachable, one walk per pair per 10 min | yes (player's own map only) |
| Helpful | `buff` / `heal` | casts its real buff on the player (`BotBuffRequestHandler.grantFromBot`) | job has it, same map and in range, buff cooldown | yes |
| | `help` | stays on / walks to the player's map and grinds next to them (hold 10 min) | TrainingBot, map has mobs in its level band, not hostile, same as `come` | yes |
| | `guide` | walks to the named map ahead of the player ("follow me") | named map resolved and reachable, not hostile, one guide per pair per 10 min | no |
| Economy | `trade` / `quote` | names a price, keeps the quote (`BotDirectTrade.chat`) | direct trades on, bot trades in person, busy, hostile refuses, `PriceBand` decides the number | no |
| | `haggle` | counter inside the band (`BotDirectTrade.chat` -> `PriceBand.respond`) | as trade; the band and its max rounds are code | no |
| | `gift` | a small gift from its own purse (`BotGifts.give`) | friend, `gifts.*` caps (7-day pair, account/day, world/day), wealth | no |
| Mood | `apologize` | words + `KIND_CHAT` | always allowed | yes |
| | `makeup` | a rival's grudge eases: `APOLOGY` event (only after the player said sorry) | bot is rival/nemesis, player line is an apology, `@botdrama` on, APOLOGY cooldown | yes |
| | `insult` | game-only trash talk (words) | bot is rival/nemesis or the player was rude, `@botdrama` on, `ContentGuard` | yes |
| | `flirt` | words + small `ROMANCE` event for a partner | `@romance on`, dating or `RomanceRules.mayFlirt` | yes |

Never on the list: giving items or mesos outside `BotGifts` and the trade window, warping anyone, anything a con flag
touched. A flagged line never reaches the LLM or an action.

**The player's tone moves affinity without an LLM call.** The same Decide call answers tone (kind, rude, flirty,
neutral). Kind -> `KIND_CHAT`, rude -> `RUDE_CHAT` (x2 for harassment), flirty -> `KIND_CHAT` for an opted-in player
the bot may flirt with, else nothing. Apology intent to a rival -> the `makeup` gate above. `AffinityReactions` used
to ask Decide a second time for the tone; it now reuses this answer.

## 2. Relationship memory card (owner decision 2)

`RelationshipCard.build(...)`, in words only, never numbers, at most ~450 characters:

```
About Pinky: you're good friends (you partied a lot). You remember: we did LPQ on Tuesday; she gave you 3 elixirs
yesterday; you heard from xKaiz she KS'd him at Ant Tunnel. You're dating her. Same guild (Maplers).
Now: Ant Tunnel I, zombie mushrooms around, evening, grinding. World event: double EXP weekend.
```

- **Feelings:** `AffinityState` plus the faded score in bands ("you barely know them", "you like them", "good
  friends", "some beef", "your nemesis, can't stand them"). `@botdrama off` never shows hostility.
- **2-4 facts:** `BotMemory.recall` (own notes before hearsay, newest first, hearsay marked "heard from X"), each
  with an age in words from `createdAt` ("earlier today", "yesterday", "on Tuesday", "last week").
- **Romance stage** (`BotRomance.with`), **guild relation** (same guild, rival or at war, allied, none).
- **Situation:** map, up to two mob kinds on the map, time of day (server zone), an active world event
  (`WorldEvents`), what the bot is doing.

The card replaces the scattered one-line additions (affinity, couple, guild, drama prompt lines); the drama rules
those lines carried ("never flirt with X", "PG-13") stay in the rules block.

## 3. Personas (owner decision 3)

`ConvoPersona.of(botSlot)`: stable, seeded by the bot's slot (no DB column, no migration), on top of the stored
`BotPersona` (vibe, interests) and `BotPersonality` (chattiness).

| Field | Values |
|---|---|
| voice | `LOWER` lowercase + u/ur/lol/brb (55%), `PROPER` proper typer (18%), `LEET` xX kid (8%), `KOREAN` flavour (7%: "^^", "kekeke", "-nim"), `SPANISH` flavour (7%: "jaja", "porfa", "q"), `TERSE` few words, no punctuation (5%) |
| typo rate | 0-6% per word, voice-dependent (proper 0-1%, leet 3-6%) |
| chattiness | from traits (0..1): reply length, split-message and thread odds |
| job pride | 0..1: brags about its class, defends it in threads |
| guild loyalty | 0..1: backs its guild in beef threads, refuses poaching harder |
| catchphrases | 1-3 from the voice's pool ("ez", "rip", "gg no re", "kekeke", "jaja ok", "Indeed.") |

- **Prompt:** the persona card says the voice in one sentence with two example lines of that voice.
- **`VoiceStyler`:** a light post-processor run on every bot line (LLM, bank, scripted): casing, abbreviations
  (you->u for `LOWER`, the reverse for `PROPER`), the voice's laugh, a catchphrase now and then, typos at the
  persona's rate (adjacent swap or dropped letter, never in a protected word: a price, a map key word, a name).
  Deterministic per (bot, line) so a retry reads the same.

## 4. Human timing (owner decision 4)

`HumanTiming.plan(line, elapsedMs, persona, ctx, rng)` returns the parts to send:

- Typing delay 1-6 s scaled by length: `1000 + len x (55..85 ms)`, clamped to 6 s, minus the time the brain already
  took, **but never below a human floor** of `600 ms + 45 ms x len` from the moment the player's line arrived.
- Sometimes split into 2 messages (chattiness-scaled, ~15%, lines over 28 characters with a natural break).
- Sometimes late: 4% a reply 15-60 s later; 2% a "brb" first, then the reply 20-60 s later with "back," or "sry".
  Never for an action outcome (the action and its words must stay together) and never in whispers.
- Ignores the player while fighting: a grinding bot with mobs in range skips a follow-up line 15% of the time
  (ticket 37's 10% busy-ignore, now only when it really fights). Never the first line and never an action.

## 5. Bot-to-bot threads (owner decision 5)

`BotThreads`, a 30 s tick:

- **Where:** maps with a visible real player: towns and the FM (weight 1), grind maps rarely (weight 0.1). At most one
  thread per map every 3-6 minutes, 6 world-wide per 10 minutes.
- **Who:** two idle bots on the map (not mid-chat with a player, not in a `ConversationManager` script, not in a
  scene).
- **Topic** from the world: guild beef (bots of guilds at war or rivals, `GuildWars`), gossip (a passable
  `BotMemory` note, never about the player who is watching), LFG (levels and a PQ that fits), trade talk (an FM
  item and its `PriceBook` value), training.
- **Lines:** 2-4. One LLM call writes the whole exchange (`THREAD` priority: only when the local model has a spare
  slot); otherwise the bank's thread lines or a cut of a `ConversationDialogue` script. Timed with `HumanTiming`.
- **Jump in:** while a thread is live (and 45 s after), a player's line on the map that names no one goes to the
  thread's last speaker as a normal conversation, with "you were just talking with X about Y" in its card.

## 6. Quality loop (owner decision 6)

- **`!rate good|bad [note]`** (GM level 1+): rates the last line a bot said to you (map chat, whisper, buddy chat or a
  thread you joined, within 10 min). The record: bot, line, source (llm / bank id / scripted), voice, intent, your
  note. Appended to `data/convo/ratings.jsonl`.
- **Bank lines:** `bad` multiplies the line's weight by 0.25; a second `bad` blacklists it. `good` x1.5 (capped at 3).
  Weights live in `data/convo/bank-weights.json` and load at boot.
- **Auto-tune (conservative):** `data/convo/tune.json`, reviewable and hand-editable:
  - `dontSay`: phrases a reply may not contain (checked with the voice check; a hit falls back to the bank). A bad
    rating's note adds a phrase only when it is quoted: `!rate bad too formal "certainly"`.
  - `badExamples` (newest 20): bad LLM lines with notes; the newest 3 go in the prompt as "never write lines like".
  - `goodExamples` (newest 10): good lines; 2 go in the prompt as voice examples.
  - `!rate reload` re-reads the file after a hand edit.
- **Transcripts:** every bot line said to a real player (and thread lines) goes to `logs/convo/transcript-<date>.jsonl`
  with the player's line, the bot, voice, tier, latency, action; the player is a short hash, never a name.
- **Daily score job** (`ConvoDailyScore`, 04:30 server time, or `!rate score`): samples up to 60 exchanges of the day
  before, scores each 0-1 on naturalness (length, casing for its voice, no echo, no repeat, era words), in character
  (matches its voice and persona), never-AI (no AI/bot/model reveal) and ContentGuard, and writes
  `data/convo/quality/<date>.json` plus `latest.json` (summary, per-tier and per-voice averages, ratings of the day,
  the worst 10, the samples). The owner builds the page from that JSON; `docs/convo/quality-sample.json` is a sample
  of the format.

## 7. Router and budget (owner decision 7)

`ChatRouter` implements `BotBrain` over the local brain and an optional cloud brain (both `OpenAICompatibleBrain`).

| Priority | Who | Local Qwen | Cloud | Else |
|---|---|---|---|---|
| `PLAYER` | a reply to a real player's line to the bot | yes, queues within the deadline | **overflow only:** local slots all busy or recent local latency over `BOT_LLM_LOCAL_SLOW_MS`, and budget left | bank |
| `PARTNER` | party / buddy / guild / partner channel replies | only with a free slot | no | bank |
| `THREAD` | bot-to-bot threads | only when every slot is free | no | bank / scripts |
| `AMBIENT` | greetings on sight, ambient lines | only when every slot is free and no player reply in the last 10 s | no | bank |

- **Budget:** cost = tokens x the configured price per million tokens (the response's `usage` when present, else
  characters / 4). Day = server local day. At the cap (default $1.00) the cloud is skipped until midnight. The GM
  line (`!botbrain`-style describe and the dashboard's chat block) shows spend, calls and the cap.
- **Keys:** `BOT_LLM_CLOUD_API_KEY` in config, or env `SOLOMAPLING_LLM_CLOUD_API_KEY`. Shipped empty: no cloud
  until the owner sets it.
- Off-screen bots never call the LLM: every LLM path needs a real player on the map (or in the chat).

## 8. Pre-generated line bank (owner requirement, 2026-10-22)

A large bank of lines (target 100k+, grows over time) used whenever the LLM is busy, slow, over budget or down, so
bots never go silent or repeat the same few lines.

- **Index:** intent (the 15 ConvoSense intents plus `greeting`, `thanks`, `bye`, brush-offs, action outcomes like
  `party_yes` / `party_no` / `come_yes` / `quote` / `declined`, and thread topics), tone, affinity (`friend`,
  `neutral`, `rival`, `nemesis`, `partner`, `ex`), voice (the six voices), situation tags (`town`, `fm`, `grind`,
  `boss`, `pq`, `party`, `guild`, `trade`, `haggle`, `date`, `breakup`, `war`, `event`, `night`).
- **Slots** filled at runtime: `{player} {map} {mob} {item} {level} {job} {guild} {price} {partner}`. A line whose
  slot cannot be filled is not picked. `{price}` only comes from `PriceBand` (the bank never invents a number).
- **Actions:** a bank line is picked by the intent and outcome the server already decided, so the words match the
  behaviour (a `come_yes` line only after the walk started).
- **Selection:** weighted random over the cell (exact voice and affinity first, then relaxing: any voice + styler,
  then neutral affinity, then fewer tags); a per-player (last 200) and per-bot (last 60) recently-used memory; the
  `!rate` weights. `VoiceStyler` runs on top.
- **Storage:** `soloMapling/ArtificialPlayer/BotConvo/chatbank.bin` (gzip TSV), packed in the jar like the dialogue
  packs (`DataFiles`; `-Dsolomapling.dataDir` overrides it with a bigger generated bank, no rebuild). Loaded lazily
  on first use into one byte array of UTF-8 text with offsets, a byte per index field, a tag bitmask per line, and
  an int[] per (intent, affinity) cell. Measured heap: see the bank section below.
- **Generation:** `tools/chatbank/` (`ChatBankGen`, a Java main in the server jar so it reuses `ContentGuard` and the
  voice check exactly):
  - seeds from the dialogue packs (`ScriptedReplies`, `ConversationDialogue`, `DramaDialogue`, `AffinityDialogue`,
    `TownChatterDialogue`, `GuildDialogue`, `ShopOfferDialogue`) with mapped tags;
  - generates per cell in batches with local Qwen, **one request at a time, only when llama.cpp's `/slots` shows
    every slot idle**, a pause between calls, optional hours window (`--hours 2-8`); the cloud provider can be used
    with `--cloud` inside `--budget-usd`;
  - every line: `ContentGuard`, the voice check (no AI reveal, no assistant phrasing, era words), length cap 70 after
    slot filling, slot whitelist, exact and near-duplicate check (normalised text, then 3-gram Jaccard >= 0.75 within
    the cell);
  - resumable: `tools/chatbank/state/` keeps accepted lines, rejects and per-cell progress; `--pack` writes the bank.
  - A tiny first batch ships now to prove the pipeline; the owner runs the big generation.

## 9. GLiNER Decide: few-label questions only (owner decision 8)

**What the endpoint can do (probed 2026-10-22):** `/v1/systemone` supports only `choice` (2..n labels, one answer
with a confidence) and `score` (an ordinal level). There is no entity/span question, so **entities are rules**:
`ConvoEntities` matches map, mob and item names against String.wz (with a small alias table: fm, kpq, ludi, sleepy,
henny, nlc...) and player names against the characters on the map.

**Probe** (`tools/convo/decide_probe.py`, set `src/test/resources/chat/convo-lines.tsv`, 59 hand-written lines,
2 requests at a time):

| Question | Labels | Decide alone | Latency |
|---|---|---|---|
| intent, one flat question | 16 (question and chatter apart) | 73% | 458 ms p50 with 8 questions in the call |
| intent, family first (5) then the family's own (2-6) | 5 -> 2-6 | family 85%, intent 68% | same call |
| **intent, one flat question (shipped)** | **15** (question + chatter = talk) | **76%** | **171 ms p50, 175 p90** with 3 questions |
| tone | 4 | 69% | same call |

- Chaining did not help: the second question loses what the first got right. Flat 15 labels with rules first is the
  shipped design, asked in the same call as tone and the existing con question (3 questions, ~170 ms).
- The misses are mostly talk read as a mood ("ugh i died again" -> insult at 0.34) and near neighbours (trade vs
  haggle, come vs follow, guide vs come). So: **rules first** for every intent with a precise rule, Decide only for
  what they leave open, at confidence >= 0.5, and **never for economy or romance intents** (rules only), because a
  wrong one would act. The rules + Decide numbers are replayed offline by `ConvoSenseTest` from the recorded answers
  (`convo-decide-answers.json`) and listed in the build section below.

## 10. Config keys and switches

`config.yaml` is strict, so new keys are `ServerConfig` fields with safe defaults (absent = default):

| Key | Default | What |
|---|---|---|
| `BOT_LLM_CLOUD_BASE_URL` | `""` | OpenAI-compatible cloud endpoint for overflow; blank = no cloud |
| `BOT_LLM_CLOUD_API_KEY` | `""` | or env `SOLOMAPLING_LLM_CLOUD_API_KEY` |
| `BOT_LLM_CLOUD_MODEL` | `""` | e.g. `deepseek-chat` |
| `BOT_LLM_CLOUD_TIMEOUT_MS` | `6000` | |
| `BOT_LLM_CLOUD_DAILY_BUDGET_USD` | `1.0` | hard daily cap |
| `BOT_LLM_CLOUD_PRICE_IN_PER_MTOK` | `0.28` | USD per million input tokens (check the provider's price) |
| `BOT_LLM_CLOUD_PRICE_OUT_PER_MTOK` | `0.42` | USD per million output tokens |
| `BOT_LLM_LOCAL_SLOW_MS` | `2500` | local latency (EWMA) above which player replies overflow |

Rollout switches (system properties, all default on except where noted):
`solomapling.convo.contract`, `.sense` (the 3-question Decide call), `.card`, `.persona`, `.styler`, `.timing`,
`.bank`, `.threads`, `.router`, `.quality`; `solomapling.convo.forceBank=true` (LLM off for chat: bank and scripts
only, for comparison); `solomapling.convo.dataDir` (`data/convo`); `solomapling.convo.threadGapMin` (3) / `Max` (6);
`solomapling.convo.scoreHour` (4).

## 11. Migrations

None. Ratings, weights, the tune file, transcripts and the daily scores are files (append-only JSONL and JSON) in
the server's working directory; the bank ships in the jar. If the owner later wants ratings in the DB, the next free
id is 017 (with a rollback file and the `mapleroot_integration_migrations` ledger row, as in `tools/release/lib.sh`).

## 12. Rollout

1. Test server with the defaults (cloud off): contract, cards, personas, timing, bank, threads, quality on.
2. Owner plays the `cv-*` scenarios and rates lines for a few days; review `data/convo/tune.json` and the daily JSON.
3. Owner sets the cloud key and budget on the test server; watch the spend line.
4. Live with the same switches; any part can be turned off with its property without a rebuild.

## 13. QA scenarios (`!qa`, docs/ops/qa-suite.md)

| Id | What |
|---|---|
| `cv-1` | Ask a staged friend bot by chat to party, follow, come to a map: SENSE, GATE, ACTION and the walk in the log |
| `cv-2` | Haggle by chat with a bot that carries an item: the quote, your counter, the band's answer |
| `cv-3` | Apology and insult with a rival bot: tone, affinity EVENT lines, makeup gate |
| `cv-4` | Persona and timing: four bots of four voices; each reply's voice, styling and timing plan |
| `cv-5` | A bot thread on your map (`!qa do thread [topic]`); jump in and the last speaker answers |
| `cv-6` | `!rate good|bad` on the last line: the record, bank weight or tune file change |
| `cv-7` | Bank mode: LLM off for chat while it runs; compare replies (`!qa do llm` flips back) |

## Done when

The owner talks to bots over several lines and they remember him, sound like different people, answer at human
speed, and do what they say. Threads show up in towns and he can join them. `!rate` works and the daily JSON is
there. With the LLM off the bank keeps bots talking without repeats. The cloud spend never passes its cap.
