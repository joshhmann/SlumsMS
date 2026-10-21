# 36: Fast/slow chat brain (GLiNER Decide + Qwen)

**Area:** Real bots / chat
**Depends on:** 1.3.0 (bot chat as it ships); pairs with 14 (companion commands) and 21 (world events)
**Status:** phase 1 shipped in v1.4.0 (branch `chat-natural`, via `test-rc1`)

## Today

- **Decide:** `BotChatRouting` calls GLiNER Decide (`BOT_DECIDE_URL` + `/v1/systemone`) for one question only: which
  kind of player a request is aimed at ("need a bless" → cleric).
- **Everything else** is rules, plus the LLM (`Qwen3.8-9B`, 2 slots, to be replaced by the 4B "Dory") for replies.
- **Probe of 2026-10-18 (comment in `BotChatRouting`):**
  - Decide failed at picking one of several players: it chose the same option 0/8 times.
  - It also failed at telling chatter from replies.
  - It is good at small questions with few labels.

## What

Make Decide the fast "system one" for social decisions, and keep the 9B for the replies that are worth it.

| Fast (Decide) | Slow (LLM) |
|---|---|
| Should any bot react to this line? (yes/no) | Writing a real reply |
| Line type: party invite, trade, question, greeting, insult, help, joke, chatter | Whisper conversations |
| Tone: friendly, joking, annoyed, hostile | Memory and gossip summaries |
| Response tier: ignore, emote, scripted line, LLM | |
| Companion command intent (ticket 14): follow, stay, loot, buff, attack, go to | |
| Action intent for ticket 37: party, follow/stay, trade, where-do-you-train, come-with-me, none | |
| World event reaction type (ticket 21) | |

- **Gate:**
  - Only lines that Decide sends to the LLM tier (plus whispers addressed to a bot) take an LLM slot.
  - Track slot use and the queue on the dashboard (ticket 35).
- **Small questions only:**
  - 2–6 labels per call, chained when needed.
  - Never "pick one of N players"; that stays a rule (`BotAddressee` and recency).
- **Budgets:**
  - Every call runs off the game thread with a tight timeout (about 150 ms).
  - Cache per normalised line, and rate-limit per channel.
  - On a timeout or error, fall back to today's rules. The bot just answers less cleverly.
- **Evaluation:**
  - Build a labelled set of real chat lines from the test server and live (anonymised; no names).
  - Measure each Decide question against the set before turning it on.
  - Keep any question it is bad at as a rule.
- **Never:** combat, movement, pathing or the economy. Those stay deterministic code.

## Optional cloud model (owner OK'd an API such as DeepSeek, 2026-10-20)

- The brain already speaks any OpenAI-compatible API (`BOT_LLM_*`; the key goes in env `SOLOMAPLING_LLM_API_KEY`,
  never in a file in the repo).
- Uses, in order of value:
  1. **Overflow:** when both local Qwen slots are busy, send the reply to the cloud model instead of a scripted line.
  2. **Notes and gossip** (`BOT_LLM_NOTES_MODEL`): rarer, heavier calls that gain most from a bigger model.
  3. **Yardstick:** run the labelled chat set through cloud, Qwen 9B and Dory to see what quality the local models
     leave on the table.
- Cost is small: a bot reply is about 300-500 input and 20-40 output tokens, a fraction of a cent per call; check the
  provider's current prices before turning it on. Cap calls per hour anyway.
- Player lines leave the LAN when it's used; mention it to friends.

## Done when

- On a busy test channel, most lines are handled by Decide with scripted lines or emotes.
- LLM slot use stays under its budget with no reply backlog.
- Each Decide question has a measured accuracy on the labelled set.
- Bot replies read as more fitting to the owner, who checks them in game.

## Phase 1 (branch `chat-natural`, 2026-10-20)

**Built:**

- `ChatDecide` (BotBrainSystem): one call to `/v1/systemone` carries several small questions. Virtual thread, deadline
  400 ms (`-Dsolomapling.chat.decideTimeoutMs`), cache per normalised line (2,048 lines, 30 min), global rate limit
  8 calls/s (`-Dsolomapling.chat.decidePerSecond`). Off with `-Dsolomapling.chat.decide=false` or no
  `BOT_DECIDE_URL`. On a timeout, an error or a full budget the answer is empty and the rules answer alone.
- `ChatTriage`: rules first (a rule match is precise and wins), then Decide for what the rules left open when its
  confidence is at least 0.4 (`-Dsolomapling.chat.intentMin`, `-Dsolomapling.chat.conMin`). Filler and small talk
  never call Decide. Three questions:
  - **Line kind / response tier** (ignore, emote, small talk, real talk): **rules only.** Decide could not do it.
  - **Action intent** (party, follow, come_with_me, trade, where_train, none): rules + Decide.
  - **Is it a con?** (normal, scam, manipulation, harassment, rwt): rules + Decide (ticket 37 soft layer).
- `BotConversation` is the gate: only real talk and action outcomes take an LLM slot. Filler gets an emote or
  nothing, thanks/bye (and a greeting after the first line) a scripted line, a flagged con a scripted brush-off.
- `ChatStats`: every answered line is logged at INFO as `Bot chat reply tier=LLM|SCRIPTED|EMOTE|IGNORED|FLAGGED ms=..
  bot=.. (kind intent=../source con=../source -> outcome)`, with an hourly summary line (`Bot chat last hour: ...`,
  including flags, actions and Decide calls/failures/cache hits). `-Dsolomapling.chat.logReplies=false` drops the
  per-line log.
- Dashboard (ticket 35): `live.json` gets a `chat` block (tiers since start / this hour / last hour with avg and max
  latency, flags, actions, Decide counters, the LLM's slot line); the Health tab shows it as a table.
- No new `config.yaml` keys.

**Labelled set and evaluation:** `src/test/resources/chat/labelled-lines.tsv` (80 lines, used to tune the rules and
the Decide criteria) and `holdout-lines.tsv` (40 lines written afterwards and never tuned on). Realistic v83 chat, no
real player names. Harness: `ChatDecideEvaluation` (test-scope main, not in the suite; one call per line, 2 at a
time). `ChatTriageTest` keeps the rule numbers from regressing offline.

**Accuracy against the live Decide endpoint (192.168.0.17:8900, 2026-10-20, 120 calls, p50 173 ms, p90 189 ms,
3 questions per call):**

| Question | Labels | Set | Decide alone | Rules alone | Rules + Decide >= 0.4 (shipped) |
|---|---|---|---|---|---|
| tier: filler vs reply | 2 | tuning / held-out | 31% / 28% | 100% / 88% | rules only |
| intent | 6 | tuning / held-out | 84% / 85% | 94% / 88% | 90% / 90% |
| con (5-way) | 5 | tuning / held-out | 90% / 93% | 95% / 75% | see flags |
| con flag precision | - | tuning / held-out | 95% / 100% | 100% / 100% | 96% / 100% |
| con flag recall | - | tuning / held-out | 80% / 86% | 84% / 29% | 92% / 79% |

- The tier question is worse than chance, so it stays a rule (as the ticket says).
- The rules look best on the tuning set because they were written against it; the held-out set shows the honest
  picture: rules alone catch 29% of cons, rules + Decide 79%, with no false flag on a normal held-out line.
- One false flag on the tuning set: "ugh i keep dying to zombie mushrooms" as harassment at 0.52. That is why a
  Decide-only flag below 0.6 (`-Dsolomapling.chat.flagNoteMin`) gets a brush-off but writes no memory note.
- Intent misses are mostly begging lines read as "trade"; they are flagged as scams anyway, and a trade request
  never moves items.

**Not yet:** ambient lines nobody addressed (bots still only hear lines aimed at them), companion intents (ticket
14), world events (21), the opt-in anonymised log for a bigger set, and a busy test channel to check "most lines
handled by Decide/scripted" and LLM slot use.
