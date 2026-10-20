# 36: Fast/slow chat brain (GLiNER Decide + Qwen)

**Area:** Real bots / chat
**Depends on:** 1.3.0 (bot chat as it ships); pairs with 14 (companion commands) and 21 (world events)
**Status:** open

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

## Done when

- On a busy test channel, most lines are handled by Decide with scripted lines or emotes.
- LLM slot use stays under its budget with no reply backlog.
- Each Decide question has a measured accuracy on the labelled set.
- Bot replies read as more fitting to the owner, who checks them in game.
