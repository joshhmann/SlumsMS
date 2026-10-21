# 26: Bot chat follow-ups from the persistence work

**Area:** Real bots  
**Depends on:** 16  
**Status:** shipped in v1.4.0 (branch `chat-polish`, via `test-rc2`)

## What

Three small items deferred during the persistence series:
- bots greet a remembered player using their memory notes;
- merchant bots know their own stock when chatting (stock goes into the LLM prompt);
- WZ-dependent tests are skipped (not failed) when `wz/` is missing.

## Done when

Each item has a test; nothing chat-side regresses.

## Built (branch `chat-polish`, 2026-10-20)

- **Greeting a remembered player** (`BotRecognition`):
  - A bot "knows" a player when it holds a note of its own about them (`botmemory`, `DIRECT`) that is not a grudge
    and it is not wary of them. Hearsay alone never counts: being recognised by someone you never met is creepy.
  - In a chat, the bot's first reply to a known player gets a prompt hint: greet them like someone you recognise,
    bring up at most ONE light remembered thing as a casual question, never list what you know, never anything bad.
    The scripted fallback (no LLM, or it failed) is a "hey again" line ("oh hey josh", "yo long time").
  - On sight: a roaming (GCMovement) bot whose player reaction fires may instead say hi first to a known player,
    through the brain with the same hint (or the scripted line). It opens the chat and the bot listens, so the
    player's answer reaches it. Rare by design: chance 0.5 per reaction (`-Dsolomapling.chat.recognizeChance`), not
    within 30 min of its newest note about them (`-Dsolomapling.chat.recognizeAfterMin`), at most once per bot and
    player per 6 h (`-Dsolomapling.chat.recognizeEveryHours`), never mid-chat; the existing 2-4 min per-player
    reaction cooldown still applies.
- **Merchants know their stock** (`MerchantStock`): read from the bot's live trade state (selling: item and asking
  price; buying: item and offer; the NX merchant: its 10k code for 50m).
  - It goes into every merchant chat prompt ("do not make up other items or prices").
  - "what are you selling?" (trade intent; new rules: "what do u sell", "do you sell", "for sale", "whatcha got",
    "ur price") answers with the real item and price; a model reply that does not name the item is replaced by the
    scripted line ("selling scroll for gloves for att 60%, 12m"). Nothing typed opens a trade or NPC window.
  - With no LLM configured, a stock question to a merchant still gets that scripted answer instead of its hint.
- **WZ-dependent tests skip** without `wz/`: `WzTestData.assumePresent("Map.wz")` (JUnit assumption, reported as
  skipped). The nav tests already did this; they now share the helper, which has its own test.
- No new `config.yaml` keys. Tests: `BotRecognitionTest`, `MerchantStockTest`, `WzTestDataTest`, and additions to
  `BotConversationFlowTest`, `BotChatActionsTest`, `BotChatPromptTest`, `ChatTriageTest`.

**Try in game:** chat with a bot until it has notes about you (a minute of real talk, then wait a minute for the
chat to end), come back 30+ minutes later and say "hey <name>": it should greet you like it knows you. Walk past
roaming bots you have talked to before: now and then one says hi first. In the Free Market ask a merchant "what are
you selling?": it should name the item it is advertising and the price.
