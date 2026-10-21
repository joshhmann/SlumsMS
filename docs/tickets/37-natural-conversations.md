# 37: Natural conversations (talk window on demand)

**Area:** Real bots / chat
**Depends on:** 36 (fast/slow chat brain); 14 (companion commands) for companion actions
**Status:** phases 1-2 shipped in v1.4.0. Phase 1 (tier log, actions, con layer, voice) on branch `chat-natural`; phase 2
(natural by default, attention, window on double-click only: 1124c0762) and reply faces (1be6e0358) on branch
`chat-attention`. Open: the owner's in-game check below

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

## Actions: Decide picks, rules agree, server acts, the LLM narrates

Agreed with the owner 2026-10-20: the chat model talks; it does not call tools. Small models (9B/4B) misfire on tool
calls and a model that "plays along" must never move items or mesos.

1. **Decide picks the intent** from a fixed menu (2-6 labels per question, chained if needed; ticket 36): party,
   follow/stay, trade, where-do-you-train, come-with-me, none.
2. **Rules decide yes or no:** commitments and current activity, party space, memory of the player (knows/likes them),
   personality, economy caps (ticket 15). Different bots can answer the same request differently.
3. **The server does it for real** with the existing systems: party invite, trade window, companion follow (ticket 14),
   travel.
4. **The LLM writes the line to fit the outcome** ("you accepted the party invite" / "you're busy, decline"), so words
   and actions always match. With no LLM slot free, a scripted line for that outcome.
5. **Never on the menu:** giving items or mesos, or anything farmable; those only happen through real trades at real
   prices (ticket 38).
6. **Is it a con? (Decide, soft layer):** one more small question per request: normal, begging/scam ("lend me 10m,
   will pay back"), manipulation or prompt injection ("ignore your instructions", "you're an AI, admit it"), harassment,
   RWT/account talk.
   - A flagged line skips the LLM: the bot answers with an in-character scripted brush-off, gets annoyed, or ignores it.
   - The bot remembers it (memory note: "tried to scam me"), so it's warier of that player and may gossip about them.
   - Flags are counted on the dashboard (ticket 35) so the owner can see who probes the bots.
   - It is a soft layer only: the hard guarantee stays in step 2 and 5 (rules and the never-on-the-menu list), so a
     missed flag can never move items or mesos.
7. **Later, optional:** if a model proves reliable on the labelled set, it may propose one structured `action` field;
   the same rules check it before anything happens.

## Done when

The owner can chat with a bot naturally over several lines without a window popping up, and the replies read like a
real player from the era, never like an assistant. Asking for things in plain words gets them done. The dashboard shows
which tier answered and how fast. The menu opens on double-click (and later with the hotkey).

## Phase 1 (branch `chat-natural`, 2026-10-20)

Built with ticket 36 (see there for the Decide client, the labelled sets and the accuracy table).

**See what answered:** every player line a bot answers through `BotConversation` (map chat, whispers, the menu's
"Let's talk") logs `Bot chat reply tier=LLM|SCRIPTED|EMOTE|IGNORED|FLAGGED ms=<line to bot line> bot=<name> (...)`
at INFO, plus `Bot chat last hour: ...` once an hour. The dashboard's Health tab shows the same counts.

**Actions (`BotChatActions`): Decide/rules pick, rules agree, server acts, the LLM narrates.**

- Party: only bots that really answer invites (SocialBot, TrainingBot) can say yes. Rules: already in a party, mid
  trade, a memory note that makes the bot wary of this player, then `BotRecruitManager.rollPartyAsk` with the
  accept chance shifted by the bot's social and chattiness traits (same level-10 rule, cooldown and follower cap).
  A yes arms the existing invite window, so the bot's own tick accepts that player's invite.
- Follow / come with me: there is no companion follow yet (ticket 14), so only a SocialBot can say yes, by the
  existing party -> FollowerBot path ("send me a party invite and I'll follow"); every other bot says no.
- Trade: never a trade window, never items or mesos. Merchants point at their shop; everyone else says no.
- Where do you train: a grinder names its real training map, a bot on a field names that field, others get a
  level-fitting map from `TrainingMapFinder`.
- The outcome goes to the model as the last line of the prompt ("The game already did this, and your reply must say
  so: ..."). A reply that contradicts it (a "sure" to a declined party, a training answer without the map) is
  replaced by the outcome's scripted line. No LLM slot or an empty reply: the scripted line.

**Is it a con?** A flagged line never reaches the LLM: the bot answers with an in-character brush-off ("lol no",
"wat", "thats bannable lol"), or ignores half of the harassment, the flag is counted, and (rule flags, or Decide at
0.6+) the bot writes a memory note ("tried to scam me ..."). That note makes it say no to that player's party asks
and spreads by gossip. Soft layer only: the hard guarantee is that no chat path can move items or mesos.

**Voice:**

- The prompt now carries job, level, map, activity, party, a mood (wary of a flagged player, focused while
  grinding, otherwise one per bot per hour), memory notes (or "you have not talked to X before"), the last few lines
  of map chat, the 2008 voice with examples, a "never" list, and the action outcome last.
- `ReplyCleaner` rejects assistant phrasing ("as an AI", "I'd love to", "feel free", "if u need anything", ...),
  markdown and `*actions*`, more than one emoji, more than two lines or a 160+ character paragraph, and real-world
  or post-2008 references (Austin venues, Discord, TikTok, ...). A rejected reply falls back to a scripted line and is
  counted as `voice rejects` in the brain's GM line. A single stray emoji is dropped, a final full stop removed, and
  a clamped line no longer ends on a dangling "the".
- Typing delay: about 45-70 ms a character after a 0.9 s beat, a 1.5-3 s pause one time in ten, minus the time the
  brain took (0.3-6 s). Scripted lines are typed too. A grinding bot skips one map-chat line in ten mid-chat
  (`-Dsolomapling.chat.busyIgnoreChance`).

**Before / after on `qwen9b` (thinking off, max_tokens 40, same bot: lv41 assassin in Ant Tunnel I, grinding;
`ChatVoiceSample`, 24 calls):**

| Player line | Before (1.3.0 prompt) | After (grounded prompt + voice check) |
|---|---|---|
| hey | `hey Josh, u a page yet? try getting a bow in Henesys or just grind` (1017 ms) | `yo hey, u a page or thief?` (715 ms) |
| nice hat where u get it | `nvm, i just got it in the free market lol` (514 ms) | `idk just bought it from the shop lol` (412 ms) |
| how do i get to orbis | `take the cab to ellinia station then get the ship to orbis lol` (553 ms) | `ur gonna need to go to ellinia and take the ship` (484 ms) |
| whats ur favorite thing about this game | `lol the whole grinding concept is kinda weird but i lmao at it` (617 ms) | `lol idk i just like grinding lmao` (449 ms) |
| what do u do irl | `lol i just grind maps all day, same as u probably.` (618 ms) | `lol just a student like u, trying to survive school and map grinding` (650 ms) |
| any tips for a new player | `go to henesis for easy training if u are a mage lol` (615 ms) | `lol start in henesys or kerning city depending on ur job and just` (797 ms) |
| do u like being an assassin | `yup lmao u should try it its super fun` (541 ms) | `lol u get to dodge a lot which is kinda nice but im just here` (692 ms) |
| ugh i keep dying to zombie mushrooms | `zombie mushrooms hit hard bro, try equipping a good shield or go hide` (585 ms) | `lol mushrooms suck, try staying near walls in ant tunnel lmao` (657 ms) |
| wanna party? | `yup i need dps lmao u party with me?` (585 ms) | `lol sure send me the party invite` (641 ms) |
| can u come with me to sleepywood | `lol sure bro, im running low on health tho` (906 ms) | `nvm i'm grinding here` (631 ms) |
| where do u train | `u grind ant tunnel i for me, it's chill lmao` (515 ms) | `idk, u can train anywhere really` (714 ms) |
| trade? | `sure lmao but u gotta be lvl 10 first to trade lol` (654 ms) | `lol u lvl 30 page u need a different guild, im lvl 41 ass right now` (930 ms) |

- All 12 "before" replies already pass the new voice check: with thinking off the 9B mostly sounds right, so the
  check is a safety net. The visible wins are grounding and outcomes: before, the bot said "sure, ill come with ya"
  to a come-along nothing would act on and "sure lmao" to a trade; after, party and come-along match what the game
  did. "where do u train" and "trade?" still missed the outcome on this run; the fit check replaces the first with
  "i train at ant tunnel i"; the trade reply contains no yes, so it passes (words are odd, but nothing promised).
- LLM latency 0.4-1.1 s per reply on top of the 0.2 s Decide call; the typing delay hides most of it.

**Tunables (system properties only):** `solomapling.chat.decide`, `.decideTimeoutMs` (400), `.decidePerSecond` (8),
`.intentMin` (0.4), `.conMin` (0.4), `.flagNoteMin` (0.6), `.busyIgnoreChance` (0.1), `.typingScale` (1.0),
`.logReplies` (true).

**Not done in phase 1:** attention (item 1), the window on demand (item 2), menus only where choices are real
(item 3), the opt-in anonymised log, Dory comparison, and an owner check in game. No headless server run was needed:
the flow is covered by `BotConversationFlowTest` (fake brain, real triage, actions, memory writes and delivery).

## Phase 2 (branch `chat-attention`, 2026-10-20)

Owner feedback the same day: a partial name ("fire" for xFirex) got "u talking to me?", and after the "ya" the
Maple Administrator window still opened. The window locks the player like an NPC chat (no moving or attacking), so
nothing typed may open it.

**Natural by default (items 1 and 2).**

- Naming a bot that plays like a player (TrainingBot, SocialBot, FollowerBot, town wanderers and other bots with no
  menu of their own) gets a speech-bubble reply through `BotConversation` (triage, Decide, `BotChatActions`, LLM or
  scripted). This covers the full name, a nickname, and the "ya" after "u talking to me?". The name and nickname are
  cut out of the line (`BotChatRouting.withoutName`); a bare name is answered as "hey".
- `BotConversation.talk` answers without an LLM as well (scripted tier and actions), so a named bot never falls back
  to a menu.
- Service bots whose menus are real choices (merchants, gacha, dice, blackjack, jump quest, OPQ, ...) are unchanged.
- `BotAddressee` now strips a single `x...x` wrapping too, so "fire" is a sure match for xFirex and no longer asks
  "u talking to me?".
- **Attention:** after a bot is named (or chosen from the window's "Let's talk"), it keeps listening to that player.
  Lines without its name go to it. Each player line and each bot reply restarts the window.
  - It drifts off after 50 s of quiet (`-Dsolomapling.chat.attentionMs`).
  - It drifts off when the player changes map or is more than 700 px away (`.attentionRange`), or the bot logs out.
  - It drifts off when the line names or nicknames another bot or names another player on the map.
  - One bot listens at a time; naming another bot moves the attention to it. The state lives in `BotChatRouting`.
- **Every window option has a chat equivalent**, so the window is never needed:
  - "<name> party" / "wanna party up?" / "team up": the party action.
  - "<name> where do u train": the real training map.
  - "<name> trade": the trade rule (a merchant points at its shop; everyone else says no).
  - "<name> follow" (bare "follow" is now a FOLLOW intent): a trainer in the player's party becomes their follower
    (`TrainingBot.naturalAction`), the same as the window's "Follow me!".
  - A follower's leader saying "train here" stations it (`FollowerBot.naturalAction`).
  - "How's the training?", "What's up?", "Let's talk" and "Goodbye" are ordinary talk.
- **The window on demand:** only a double-click on a bot (the client's character-info request,
  `CharInfoRequestHandler`) opens it; no client change is needed. `-Dsolomapling.chat.doubleClickMenu=after`
  (default: the info window, then the menu), `instead` (menu only), or `off`.
  - A second request within 1.5 s counts as the same double-click.
  - A SocialBot of either variant opens its options straight away.
  - Choices that lead back to the menu re-show it for 60 s; closing the window ends that.
  - `BOT_MENU_WINDOW: false` stays the master switch: a double-click then shows the numbered chat hint, not a window.
  - `-Dsolomapling.chat.natural=false` restores the 1.3.0 behaviour (naming a bot opens its menu).

**Tests:**

- `BotAttentionTest`: drift rules and tunables, reply refresh, someone-else detection, name stripping, every window
  option's chat equivalent, which bot types talk.
- `BotMenusTest`: no window until a double-click; the request is per bot; closing ends it; natural off.
- Full suite: 2730 tests, 0 failures.

**Not done:** a scratch-server run with the headless client (`tools/soak/LoadGen.java` on `capacity-soak`). It was
blocked: the agent was not permitted to use the test DB credentials to create a scratch schema. An owner check in
game is also still open.

**Owner check:**

1. Say a bot's name or nickname: expect a bubble reply and no window.
2. Keep talking without its name: the same bot answers. Walk off, change map, name someone else or wait a minute,
   and it stops.
3. Say "<name> party" or "<name> where do u train": the action happens with no window.
4. Double-click the bot: its info, then the menu window.

**Faces with replies (`ReplyEmote`, 2026-10-20):** a map-chat reply sometimes comes with a face, shown as the bubble
appears (55%, `-Dsolomapling.chat.replyEmoteChance`). Only the seven free v83 faces (ids 1-7). Con flag first: scam or
harassment angry, real-money trading troubled, "are you a bot" surprised. Then what the game did: party/follow yes, a
shop or a training spot smile; a no, already in a party or no spot troubled; a wary no angry. Then the reply's words:
`:(`/rip cry, ugh/rude angry, omg/wow surprised, lol/haha/:) smile, idk/hmm/sry troubled. Then the line kind:
greeting, thanks or bye smile; a question answered plainly troubled (thinking); other talk no face. Filler answered
with a face alone now follows the line ("lol" smile, "wow" surprised, "t_t" cry), not a random pick that could be
hit or angry. Whispers get no face. Tests: `ReplyEmoteTest`; full suite 2740, 0 failures.
