# 54: Messenger and memos with bots

**Area:** Real bots / Social
**Kind:** BUILD (small)
**Effort:** S
**Depends on:** 49
**Status:** open, low priority

## Today

- **Players:**
  - Maple Messenger (`MessengerHandler`, `Messenger`) is stock, plus a GM 4+ console hook.
  - Memos (`NoteActionHandler`, `NoteService`, `notes` table) are stock. Live has 0 notes.
- **Bots:**
  - A messenger invite to a bot is ignored and expires.
  - Messenger chat is not routed to bots.
  - Bots never send memos.

## What

- A buddy bot (ticket 49) accepts a messenger invite from its player. Lines addressed to it go through
  `BotConversation`. It leaves after a few minutes idle, or when it logs out.
- Bots may leave **one** memo for a buddy player who is offline, sometimes, after a notable event ("hit 70 today!").
  - At most one a week per pair.
  - Memos are stored and capped per player.
- Bots never send memos to players who are not their buddies.

## Done when

- A buddy bot joins a messenger and answers once per addressed line.
- An offline player can receive at most one bot memo a week from each buddy bot.

## Risks

- Memo spam is the main risk; cap it hard.
- LLM cost: scripted memo templates only.
- Never reveal AI.
