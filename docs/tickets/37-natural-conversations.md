# 37: Natural conversations (talk window on demand)

**Area:** Real bots / chat
**Depends on:** 36 (fast/slow chat brain); 14 (companion commands) for companion actions
**Status:** open

## Today

Saying a bot's name opens `BotMenus` as an NPC talk window (Maple Administrator, NPC 9010000) with the bot type's
options plus "Let's talk" and "Where do you train?". The owner likes the window but wants conversations to feel more
natural.

**Owner feedback (1.3.0 test, 2026-10-20):** the window works, but popping up while training or fighting is annoying.
Quick option if wanted before the rest of this ticket: open the window only in towns and when the player hasn't
attacked or been hit in the last few seconds; otherwise the bot just answers in chat (the same as
`BOT_MENU_WINDOW: false`, which falls back to a reply plus the numbered hint).

## What

1. **Natural by default (server only):**
   - Naming a bot gets a chat reply in a normal speech bubble, not a window.
   - **Attention:** after that, the bot keeps listening to that player for a short while.
     - Follow-up lines without its name still go to it.
     - The bot drifts off when the player goes quiet, walks away or talks to someone else.
     - Uses the existing addressee/"u talking to me?" logic.
   - **Actions from what's said,** classified by Decide (ticket 36) and done for real:
     - "wanna party?" → accept or invite;
     - "where do you train?" → answer, maybe "come with me";
     - "follow me" → companions (ticket 14);
     - "trade?" → the existing trade system.
   - Whispers stay as they are.
2. **The window on demand:**
   - **Double-click a bot:** the client's character-info request opens the bot menu, with no client change. The
     owner decides whether it replaces the info window or follows it.
   - **Later (client):** a small ImGui "nearby bots" panel in `slums.dll` with Talk, Party and Follow buttons and a
     hotkey.
   - Keep `BOT_MENU_WINDOW` and the numbered chat hint as fallbacks.
3. **Menus only where choices are real:** a merchant's stock, a party-quest signup, a service bot's options.
4. **Replies that read like a 2008 player (live since 1.3.0, owner feedback 2026-10-20: "it was so cool i talked to
   someone"; next step is making it feel natural every time):**
   - **See what answered:** log each reply's tier (LLM, scripted, emote, ignored) and latency at INFO with a per-hour
     summary, and show it on the dashboard (ticket 35). Today the live log can't tell a Qwen reply from a scripted one.
   - **Voice:** short lowercase lines, era slang and typos, no "**bold**", no assistant phrasing, never "as an AI".
     Tighten the persona prompt per bot (job, level, map, mood, personality) and extend `ReplyCleaner` to catch
     assistant-isms; a reply that fails the check falls back to a scripted line.
   - **Grounding:** the prompt carries what the bot actually knows: its map, level, job, party, what it's doing, recent
     chat on the map, and its notes and gossip about the player (memory), so replies match the world.
   - **Timing:** a typing delay by length, sometimes a pause or a "brb", sometimes no answer while it's mid-fight, the
     way people answer.
   - **Continuity:** the bot remembers the thread across a few lines and greets returning players by what it knows.
   - **Data for tuning:** keep an opt-in, anonymised log of player lines and bot replies (no names, no account data) as
     a labelled set for ticket 36 and a possible LoRA for the chat model.
   - **Model:** Qwen 9B (`qwen9b` at 192.168.0.17:8095, thinking off) on live; Dory (4B) when it's ready. Compare both on
     the same labelled set.

## Done when

The owner can chat with a bot naturally over several lines without a window popping up, and the replies read like a
real player from the era, never like an assistant. Asking for things in plain words gets them done. The dashboard shows
which tier answered and how fast. The menu opens on double-click (and later with the hotkey).
