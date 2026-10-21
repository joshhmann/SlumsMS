# 49: Bots as buddies (accept, send, buddy chat, online status)

**Area:** Real bots / Social
**Kind:** BUILD (plus a small FIX for the stuck "pending" entry)
**Effort:** M
**Depends on:** 36/37 (chat brain), 26 (recognition), the bot-name reservation from SoloMapling 4fc53172
**Status:** open

Research: [docs/real-bots/social-systems-research.md](../real-bots/social-systems-research.md).

## Today

- **Players:** the buddy list is stock Cosmic and works. Live has 2 rows, one mutual pair.
- **Adding an online bot:** `World.requestBuddyAdd` queues a request on the bot and the bot's client drops it. The
  player sees the bot as "pending" forever.
  - The player's save writes a `buddies` row with `buddyid` = the bot.
  - `BuddyList.loadFromDb` joins `characters`, so that row disappears at the player's next login.
- **Adding an offline bot:** "A character called X does not exist", because bots have no `characters` row.
- **Whispers:** they already reach the bot's chat brain (`WhisperHandler.whisperBot` → `BotConversation`).
  `/find` finds online bots.
- **Not routed:** buddy chat (`MultiChatHandler` type 0) never reaches bots.
- **Online status:** bots never call `World.loggedOn` or `loggedOff`, so a buddy bot would never show online or
  offline.

## What

- **Storage:**
  - Keep `buddies` as is (it has no foreign key). Store **only bot↔player pairs**, never bot↔bot.
  - `BuddyList.loadFromDb` and the two name lookups in `BuddylistModifyHandler` fall back to `botstore`
    (id = `BOT_ID_BASE + BotId`) when `characters` has no row.
  - On bot login, load its player buddies; on bot login and logout, call the stock buddy notify path, so the player's
    list shows the bot's channel.
- **Accepting:** a request from a player is answered in character after a human delay (10 s to a few minutes; the
  next login if the bot is offline).
  - Accept when the bot knows the player (`BotRecognition.knows`, an own `botmemory` note, or N lines of friendly
    talk), when the persona is sociable, and when the bot has fewer than about 20 player buddies.
  - Otherwise leave it pending a while, then decline (delete the pending row).
  - Remember a decline in `botmemory`.
- **Sending:** a bot may send a request only after a good conversation. At most one ever per player, plus one retry
  after 7 days if the player ignored it.
  - Never to a player who declined or deleted the bot.
  - World cap: N bot requests per hour.
  - Never bot to bot.
- **Buddy chat:** route `MultiChat` type 0 to buddy bots through `BotConversation`, answering by buddy chat.
  - Only the bot addressed by name, or the single online bot buddy, answers.
  - One answer per line at most, through the same Decide gate and LLM concurrency limit.
- **Meet-ups:**
  - "where r u": the bot answers with its map's street name, or a vague answer while grinding.
  - "come to X / meet me": a `BotCommitments` clause, or a follow invite through the party flow (ticket 51).
- **Removal:** the player deletes the bot and both rows go. The bot never re-adds.
- **GM:** `!botbuddies <bot|player>` lists a bot's buddies or a player's bot buddies.

## Done when

- A player adds an online bot it has chatted with. The bot accepts within minutes, shows online with a channel,
  shows offline when it logs out, and is still there after the player relogs and after a server restart.
- A player adds an offline bot by name. The bot answers at its next login.
- Buddy chat to a bot gets one in-character reply, and only from the addressed bot.
- No bot↔bot `buddies` rows exist, and a bot never sends a second request after a decline or delete.
- Unit tests cover the decision rules, the name fallback and the row cap.

## Risks

- **DB growth:** rows are only per real player, at most 2 × 20 per player. Never seed rows for thousands of bots.
- **Packet spam:**
  - Bot login and logout notifies only that bot's buddies.
  - The roster director logs out hundreds of bots an hour, but each player has at most about 20 bot buddies. Watch
    the per-player notify rate and debounce rapid relogs.
- **LLM cost:** buddy chat is a new input stream. Keep the per-player rate limit and the 2-concurrent cap, and fall
  back to scripted short replies.
- **Never reveal AI:** the existing voice rules and ticket 47 content review apply. Buddy chat is more private, so
  log it like whispers.
- **Consent and creepiness:**
  - Bots do not spam requests or message players who never spoke to them.
  - A bot never sends an unprompted buddy chat more than about once a session.
- **Name collision:** a real character with a bot's name breaks name-based adds. Port the SoloMapling bot-name
  reservation first.
