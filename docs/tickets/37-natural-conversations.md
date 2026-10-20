# 37: Natural conversations (talk window on demand)

**Area:** Real bots / chat
**Depends on:** 36 (fast/slow chat brain); 14 (companion commands) for companion actions
**Status:** open

## Today

Saying a bot's name opens `BotMenus` as an NPC talk window (Maple Administrator, NPC 9010000) with the bot type's
options plus "Let's talk" and "Where do you train?". The owner likes the window but wants conversations to feel more
natural.

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

## Done when

The owner can chat with a bot naturally over several lines without a window popping up. Asking for things in plain
words gets them done. The menu opens on double-click (and later with the hotkey).
