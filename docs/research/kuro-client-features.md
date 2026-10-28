# Kuro (PlayKuro) client: new feature ideas for SlumsMS

Date: 2026-10-28. Static reading only: `strings` (ASCII and UTF-16) on `dinput8.dll` and `KuroClient.exe`, their ini files,
and a parse of `UI.wz`, `EzorsiaV2_UI.wz` and `Etc.wz` with `native_wz.py`, compared against stock v83 UI XML
(`/root/workspace/cosmicms/wz/UI.wz`). Nothing of theirs was run. This note lists **ideas and how they likely work**.
Nothing here copies their art, scripts or code. Opcode numbers are theirs and only show that a custom packet exists.
Ours would be different.

Read with: `files/NOTES.md` (the earlier write-up: rates, PP currency, housing, poker/chess, Endless Abyss, transmog,
item lock and so on). That material is **not repeated** here. This file covers what the DLL and WZ add on top of it.

## How their client is built (useful context)

- **One big hook DLL** (`dinput8.dll`, a proxy DLL loaded by the exe) with a Dear ImGui overlay called "the widget"
  (F9, tabs: Combat / Expedition / Progress / Extras). It also hooks many native windows directly, so a feature can show
  either as an ImGui window or as a "native" v83-style window drawn from WZ art (`CDailyLoginWnd`, `CMonsterBookWindow`,
  `COnlinePlayersWnd`, `CHyperLegionWnd`, `CScrollWorkshopWindow`, `CStorageWnd`, `CMilestoneRewardsWindow`,
  `CCitizenshipWindow`, `CCubeStackWindow`, `CUIDamageSkinPicker`, `CUIFusionAnvil`, ...). Config:
  `UseLegacyWidget`, "Use the previous dark %s window instead of the new one". So they shipped ImGui first and moved
  the popular windows to WZ art later. This matches our "ImGui HUD first" direction.
- **Transport:** the widget began as a **separate TCP connection** to a "DPS widget server" ("Auto-connect DPS widget",
  "legacy untagged TCP", "Not connected to leaderboard server!"). Later it moved to **custom in-game opcodes negotiated
  at login** ("CAPABILITY sent (version %u, flags 0x%02X)", "Custom packet v2 is not negotiated yet", REQUEST_STATE
  resync on inbox overflow). Named widget messages: ONLINE_PLAYERS_RESPONSE 0xC0, TOGGLE_IGNORE_ITEM / IGNORE_LIST_UPDATE
  0x92, DAILY_LOGIN_DATA_RESPONSE 0x84, GUILD_STORAGE_* 0xE0-0xE9, SEARCH_INVENTORY_RESPONSE 0xE5, CASH_SHOP_* 0xB0-0xB3,
  plus raw 0x17A/0x17C/0x17F/0x180/0xD9/0xFA. **Lesson for us:** a single versioned "capability" handshake plus one
  custom opcode family with sub-types saves adding a new opcode for every feature. Our CP 0x3738 build stamp is a natural
  place for that handshake.
- Every hook is guarded by byte signatures ("signature drifted", "client bytes differ from v83 baseline, nothing
  installed") and is skipped on mismatch instead of crashing. That habit is worth copying.
- Extra WZ (`UI.wz` against stock v83): new `UIWindow.img/DamageRank`, `DamageSkin`, `MilestoneRewards`, `Synthesizing`;
  `Skill/CoolTime2`; `Trunk/backgrnd2`; `KeyConfig/ctrl`; `MobGage/backgrnd5-6, SmartMobnotice`; `Basic.img/BtHyper`; new
  images `New.img` (battleStats main/mini, beautyRoom), `Citizenship.img` (contract, badge), `CharacterInfo.img`
  (RemoteInfo, ItemList, ItemListEntry, PetInfo, Citizenship); about 235 extra ChatBalloon and 265 extra NameTag styles
  (newer cash styles brought back to v83). `EzorsiaV2_UI.wz` adds `Widget.img`, `DailyLogin.img`, `HyperStats.img`,
  `Legion.img`, `OreStorage.img`, `GuildStorage.img`, `KuroPrism.img`, `KuroAndroid.img`, `StandardPDD.img`. `Etc.wz`
  is close to stock.

## Ranked list of NEW ideas

Size: S = a day or two, M = about a week, L = a sprint or more. "Split" is client / server / data.

### 1. Map "Mob Info" panel with a per-item loot filter. NEEDS SERVER
- **For players:** one window lists every monster in the **current map** (level, HP, elemental weak/strong), and clicking
  one shows its drops. Each drop has a toggle: **ignore this item for auto-loot/pet pickup**, plus IGNORE ALL / ENABLE ALL.
  Mesos can't be ignored. No more pets filling the bag with junk Etc.
- **Evidence:** "List the monsters of your current map", "Loading map monsters...", "Filter targets...", "Strong/resists",
  "Lightning / Poison / Darkness", "No target selected. Drop intel will appear here once you choose a mob.", "IGNORE ALL",
  "ENABLE ALL", "Click to DISABLE auto-loot", "Mesos auto-loot cannot be disabled", "Sending TOGGLE_IGNORE_ITEM (0x92)",
  "Received IGNORE_LIST_UPDATE (0x92)". Widget combat tab icons `pickOn/pickOff` ("Auto Pickup", "Pick ON/OFF").
- **How:** ImGui window. The map mob list and drop list come from the server (we already have the Monster Book drop
  data). The ignore list is **stored and enforced on the server** in the pet/auto-loot pickup handler, and synced back to
  the client so the toggles show their state. A global "pickup on/off" switch goes in the HUD.
- **Size for us:** M. Client: one ImGui window that reuses Monster Book search and drop rendering. Server: a per-character
  ignore set (DB table), checked in pet pickup, plus two packets. Data: none.

### 2. Online Players window (whisper, invite, teleport). NEEDS SERVER
- **For players:** a list of who is online (name, level, job, channel), filtered by **in your guild / party / buddy
  list**, auto-refreshed every 10 s. Each row has whisper, party invite, and **teleport to that player for 1 VIP Teleport
  Rock** (with a confirmation).
- **Evidence:** `COnlinePlayersWnd`, "Online Players###OnlinePlayers", ONLINE_PLAYERS_RESPONSE (0xC0), "In your guild",
  "In your party", "On your buddy list", "Auto-refresh 10s", "Showing %d of %d players", "Invite to party",
  "Teleport (1 VIP Rock)", "TP = 1 VIP Rock", "That player is in a different world.", "That party member is not in your
  channel.", "Teleporting to that map is not allowed."
- **How:** the server sends a snapshot of online characters. The client renders it and reuses the native
  whisper and party-invite packets. Teleport reuses the VIP Rock "to player" logic on the server, with field-limit
  checks.
- **Bots:** this is our biggest win and our biggest risk. With 3,000 bots the list needs a "players only / include bots"
  filter (or bots shown as a count), or it becomes noise. It also makes a natural place to find a bot to party with.
- **Size:** M. Client: ImGui list. Server: snapshot packet, filtering, rate limit. Data: none.

### 3. Change character without logging out, plus launcher direct-login. NEEDS SERVER
- **For players:** a "Change Character" button returns you to character select in a few seconds, with no password
  re-entry. The launcher can also start the client **straight into a chosen character** (it skips login, world and
  channel select).
- **Evidence:** "[GoBackToLogin] Sending RETURN_TO_CHAR_SELECT widget command...", "Missing relog token",
  "Preparing relog...", "Switching character", "Character switch timed out", widget `extras/changeCharacter`;
  "[LauncherAutoLogin] ... auto_login.dat ... Deleted auto_login.dat after reading", "Ignoring incomplete direct
  character id", "Direct character select dispatched.", "Saved world/channel button ids". `KuroClient.exe`:
  "autologin: launcher credentials read (characterId=%d, channel=%d)".
- **How:** the server gives out a one-time **relog token** and moves the session back to the login server, and the
  client replays login with the token, then selects the character. For direct login, the launcher writes a short-lived
  credential/token file that the DLL reads once and deletes, then drives the native login steps.
- **Size:** M. Client: the login-flow driver (the hard part is waiting until each native stage is ready). Server: token
  issue/consume, return-to-login handler. Launcher: pass token, world, channel and character id. Use a token, never a
  stored password.

### 4. Boss attempts tracker and expedition death counter in the HUD. Server data mostly exists
- **For players:** a HUD panel lists bosses with today's or this week's remaining entries (Normal/Easy Balrog, Showa
  Boss, Scarga & Targa, Zakum, Horntail, Chaos Horntail, Pink Bean, Papulatus, Von Leon, ...). During a boss run, the
  HUD counts party deaths, with an opt-in "Show death count to others".
- **Evidence:** "Boss Attempts", "##Bosses", that boss list next to it; "[ExpeditionDeathCounter]", "%d Deaths:%d",
  "Show death count to others"; "Daily reset in %s", "Weekly reset in %s".
- **How:** the server pushes the boss-log snapshot (we already have `@bosslog`) on login and after each entry. The death
  counter needs a server broadcast per death in the boss map.
- **Size:** S-M. Client: one HUD module. Server: a snapshot packet built from the existing boss log, plus a death event.

### 5. Server notice with "Reply to GM", and merchant sale toasts. NEEDS SERVER
- **For players:** GMs can pop a **SERVER NOTICE** in the overlay. Players can **reply once** (5-500 characters), and the
  reply reaches GMs. Separately, a small **[MERCHANT SALE]** toast appears when your hired merchant sells something.
- **Evidence:** "SERVER NOTICE", "Reply to GM...", "Please do not spam. You can reply once to this notice, or use @gm
  <message> in chat.", "Message too short (5+ chars).", "Reply sent. One reply per notice.", "Widget packet mode required
  to reply to GMs."; "[MERCHANT SALE]", "##MerchantNotif%zu".
- **How:** server-to-client notice packet with an id. The client sends the reply with that id, and the server stores it
  for GMs (we could forward replies to Discord). Merchant sale: the server already knows when a sale happens, so it sends
  a toast packet to the owner if online.
- **Why for us:** a cheap tester-feedback channel for a friends server. Merchant toasts make the bot economy feel alive
  ("a bot just bought your Ilbi").
- **Size:** S each. Client: toast and notice modules. Server: two small packets plus storage for replies.

### 6. Party-wide damage ranking window (native style). NEEDS SERVER (extends ticket 118)
- **For players:** a KMS-style "Damage Rank" window during expeditions shows each member's share of damage with
  bars, a skill breakdown with icons, Reset / Switch / Auto buttons, and "Showing previous expedition results" after the
  run.
- **Evidence:** `UIWindow.img/DamageRank` (backgrndmax/min/center/bottom, gauge self/common, iconCommonAtk,
  iconUnknownSkill, BtReset/BtSwitch/BtAuto, title1/2), "Open native DamageRank", "Expedition Rankings",
  "Join an expedition to see damage rankings here.", "Rankings will appear once damage is dealt.", config
  "Enable the F12 damage rank UI's native input hooks".
- **How:** ticket 118 measures your own damage on the client. A ranking needs the **server** to total damage per
  party member per boss and broadcast it every second ("Updates every second during battle"). Kuro rebuilt the KMS
  window with WZ art. We would draw our own art or use ImGui.
- **Size:** M on top of 118. Server: per-instance damage totals and a periodic packet. Bots in parties would show up
  too, which is fun.

### 7. Better "view other player" window: equipped item list, pet info. NEEDS SERVER
- **For players:** double-clicking a player opens a larger info window with a scrollable **list of their equipped items
  (hover for full tooltips)**, a pet panel (level, closeness, fullness, pet equip), and party/trade buttons.
- **Evidence:** `CharacterInfo.img` (new): RemoteInfo (button:party, button:trade, button:toggleItemlist, button:showPet),
  ItemList/ItemListEntry, PetInfo (type, level, closeness, fullness, equip). "[UserInfoEquipTooltip] Kuro item list hooks
  decode=", "CUIUserInfo::Draw hook".
- **How:** the server appends an equipment list (and pet stats) to the character info response, gated by a capability
  bit. The client hooks `CUIUserInfo` to draw the extra panel and reuses the native item tooltip.
- **Size:** M. Client: CUIUserInfo hook and panel. Server: an extended char-info packet. Data: our own panel art.
  Players like inspecting gear, and inspecting bots' gear is a nice touch.

### 8. Party buffs shown on the party HP bars. NEEDS SERVER
- **For players:** the party HP window is widened and shows each member's **active buffs** as icons, so you can see
  who still needs Hyper Body or Holy Symbol.
- **Evidence:** "Party buff snapshot received: members=", "CUIPartyHP::Create ... fixedWidth ... maxIconsPerRow",
  "native party hp create size expanded".
- **How:** the server sends a per-member buff snapshot when buffs change. The client hooks `CUIPartyHP::Create/Draw`
  to widen the window and paint stock buff icons.
- **Size:** M. Good with support bots (Priests handing out buffs).

### 9. Borderless "fake fullscreen", DPI-aware mode, Alt+F4 confirm, alt-tab catch-up. Client only
- **For players:** windowed mode that looks fullscreen (title bar pushed off-screen) with fast alt-tab and no freezes.
  There's a DPI-aware option for crisp 2560x1440 on scaled displays, a "Are you sure you want to quit?" prompt on Alt+F4,
  and no burst of catch-up ticks after returning from alt-tab.
- **Evidence:** config comments "Fake fullscreen mode", "so the title bar is hidden above the screen, giving a fullscreen
  appearance", "while keeping the benefits of windowed mode (faster alt-tab, no freezes)", "Enable DPI-aware mode",
  "When true, allows native resolutions like 2560x1440 on scaled displays"; "WndProc: Alt+F4 -> WM_CLOSE", "close
  confirmation"; "[AltTabCatchUp] Installed one-tick resume guard".
- **How:** CreateWindowEx/SetWindowPos hooks, SetProcessDpiAware, a WM_CLOSE handler, and a guard on the main update
  loop that limits catch-up to one tick.
- **Size:** S. Not found in our client repo (only ImGui's own fullscreen code). Helps the Parsec and virtual-display case too.

### 10. Megaphone chat QoL. Client only (ticket 123 doesn't cover it)
- **For players:** (a) hover an item shown in an **Item Megaphone** in chat to get the full item tooltip; (b) click a
  megaphone line to whisper its sender (ear icon); (c) the unused "Spouse" chat target becomes **"Megaphone"**: typing
  with it selected uses a Super Megaphone from your cash inventory (nothing is sent if you have none).
- **Evidence:** "[ChatItemMegaTooltip] show item=", "[ChatMegaphoneWhisper] chat bar now whispers",
  ";1 = megaphones sent from that target allow click-to-whisper (ear icon)", "installed: Spouse chat target now sends a
  Super Megaphone (5072000)", "[Megaphone] You need a Super Megaphone in your Cash inventory".
- **How:** hooks on chat-line decode and draw (keep the item payload, show `CUIToolTip` on hover), plus
  `CUIStatusBar::SetWhisperTarget`, and a remap of the chat target to send the normal use-cash-item megaphone packet.
- **Size:** S each. Note that `src/resolution.cpp` already mentions megaphones, so check overlap first.

### 11. Hotkey QoL: nearest return scroll, and pouch/scripted items on hotkeys. Client, small server check
- **For players:** one hotkey that always uses the **right town return scroll** (the nearest town), and the ability to
  put items the client normally refuses on hotkeys (Mineral Pouch, scripted items).
- **Evidence:** "[NearestScrollHotkey] Return scroll hotkey patches installed", "mainfunc.nearest_scroll_hotkey";
  "[ConsumeCategoryHotkey] Mineral Pouch (2101203) + scripted item (243) hotkey patches installed", patch sites
  `CDraggableItem::MapFuncKey`, `CUserLocal::UseFuncKeyMapped`.
- **How:** patch the item-type checks in the func-key mapping and the use dispatch, and choose the scroll id at use time
  from the current map's return map. The server already accepts the normal use packets; just confirm the handlers.
- **Size:** S.

### 12. Sell price on tooltips and inventory search. Client only
- **For players:** equip tooltips show the **NPC sell price**, and a search box finds an item in your inventory or
  storage by name or id.
- **Evidence:** "[NpcEquipSellPrice] ... CItemInfo::GetItemPrice hook"; "Search your inventory (name or ID)",
  "Search your inventory for the typed name (Enter)", "Search stored items", "SEARCH_INVENTORY_RESPONSE (0xE5)".
- **How:** a tooltip hook reads `price` from Item.wz. Kuro searches on the server, but our client can search its own
  inventory locally.
- **Size:** S. The search fits ticket 127's storage work.

### 13. Small toggles not in ticket 120/123 yet. Client only
- **For players:** hide the **quest lightbulb**, show/hide **[MapleTip]** lines, show/hide the "not enough HP/MP to use
  this skill" chat warnings, **summons drawn behind the player**, **hide other players' full-body anime cash
  costumes**, and a **cap on how many other players animate fancy cosmetics at once** (your own always animate).
- **Evidence:** "Hide Lightbulb (Quest Helper)", "Show MapleTips in chat", "Show HP/MP skill warnings in chat" (in
  chat_config.ini: showMapleTips, showSkillHpWarningChat, showSkillMpWarningChat), "Summons behind player / Re-summon
  after changing the layer order", "Hide anime cash skins on other players", "Max animated players: How many OTHER players
  may animate their dyes at once". Opacity keys `ChairOpacity`, `OwnDamageOpacity`, `MobEffectOpacity`, `PetOpacity`,
  `SummonOpacity`, `SkillOpacity`. Their note: "Boss and monster attack effects are never affected" and "Smoke Screen's
  smoke always stays visible", so gameplay-relevant effects stay visible.
- **Size:** S each. Add these items to ticket 120's scope. The animation cap pairs with our Coloring Prism.

### 14. Guild storage with an activity log. NEEDS SERVER
- **For players:** a shared guild bank (items and mesos) with a "Latest 50 storage actions" log (who stored or took what),
  meso transfer, and a search.
- **Evidence:** "Guild Storage##GuildStorageMain", GUILD_STORAGE_RESPONSE (0xE0) ... GUILD_STORAGE_LOG_RESPONSE (0xE9),
  "Move meso from your wallet into guild storage", "Take meso out of guild storage", "Latest 50 storage actions",
  "Stored by: %s", `EzorsiaV2_UI.wz/GuildStorage.img`.
- **How:** server-side storage per guild with rank permissions and an audit log. The client window is the ImGui
  storage view reused.
- **Size:** M. Server is most of it (DB, permissions, log, no-dupe care). Check whether ticket 127 already covers it.

### 15. Level milestone rewards window. NEEDS SERVER
- **For players:** a native-style window lists level milestones (10, 30, ... 200, 300) with a reward each and a Claim
  button ("Ready to claim. Click to claim it.").
- **Evidence:** `UIWindow.img/MilestoneRewards`, `CMilestoneRewardsWindow`, "Claiming Level %d reward...",
  "Level 10 requires", "Level 200 requires", "send 0xD9 milestoneLevel=%d", "No level milestone data is available.".
- **How:** the server keeps a claimed-levels set per character (or per account) and a reward table. The client shows the
  snapshot and sends a claim. It shares UI with our daily check-in.
- **Size:** S-M. Good for new players and alts. It also makes the weekly bonus from NOTES easy to add.

### 16. Server quiz / trivia event with overlay opt-in. NEEDS SERVER
- **For players:** a GM or scheduled **quiz event**: a prompt asks you to join or skip, then questions appear with answer
  buttons, and you can quit the event at any time.
- **Evidence:** "Kuro Quiz", "KuroQuiz question", "Join##KuroQuizJoin", "Skip##KuroQuizOptOut", "Quit Event",
  "kuroquiz_action=opt_in", "Missing quiz question id.", "Answer sent.", "already answered". (Stock `Etc.wz/OXQuiz.img`
  also exists, so stock OX quiz data can be reused.)
- **How:** the server runs the event (questions, timers, scoring, prizes) and pushes question packets. The client
  shows a small ImGui card. It works server-wide without moving people to an OX map.
- **Size:** M. Bots can answer (some right, some wrong), so the event is never empty.

### 17. Free Market channel change from inside FM rooms. Client and server
- **For players:** change channel while inside FM rooms 1-22 without walking back to the FM entrance.
- **Evidence:** "[FreeMarketChannelChange] FM rooms 910000001-910000022 may change channel".
- **How:** patch the client's field-limit check for those maps, and make sure the server allows the change-channel
  request there and places you in the same room.
- **Size:** S. It helps with merchant-browsing across channels, which matters with bot merchants.

### 18. Theoretical DPM calculator ("Analytical DPM"). NEEDS SERVER (or a client-only version)
- **For players:** pick a learned skill and see its **modelled damage per minute** from your real stats, crit, booster,
  Speed Infusion and dice, plus cast cadence and effective targets.
- **Evidence:** "Analytical DPM", "Choose a learned skill, then run the server model.", "Snapshot sent; waiting for model
  response", "Attack: WATK %d MATK %d Weapon %s", "Crit: %d%% chance / %d%% damage", "+ Spell Booster", "+ Dice",
  "Cadence: %.2f casts/min  Effective targets: %d/%d", "1-minute DPM / %d targets".
- **How:** the client sends a stat snapshot and skill id, and the server runs the same damage formula it uses for
  validation and returns DPM. That is a nice way to reuse server code.
- **Size:** M-L. A theorycrafting toy. Do it after ticket 118.

### 19. Revive choice: "revive here" or "revive in town". NEEDS SERVER (economy)
- **For players:** on death, choose to revive **at this map** (costs something or uses a charm) or in town.
- **Evidence:** "Would you like to revive at this map?", "Would you like to revive in town?" (also listed in NOTES as
  "revive in place").
- **How:** hook the native death dialog (`CUIRevive`) to add a second button, and handle the revive-in-place request
  on the server.
- **Size:** S-M. Needs the owner's call on cost.

### 20. Use a party member's Mystic Door from the party window. Client and server
- **For players:** "Use %s's Mystic Door?" from the party list, without hunting for the door on the map.
- **Evidence:** "Use %s's Mystic Door?", "Mystic Door is not available right now.", NOTES ("Mystic Door use from the
  party window").
- **How:** the client sends the door-enter request for that member's door, and the server checks map, party and door.
- **Size:** S-M. Handy with Priest bots.

### 21. Battle Stats panel and crit rows on equip tooltips. NEEDS SERVER (snapshot)
- **For players:** a small panel (with a full and a mini view) of computed combat stats: crit chance and crit damage,
  weapon/magic attack, and so on. Equip tooltips also gain crit rows.
- **Evidence:** `New.img/battleStats` (main, mini), "Crit: awaiting server snapshot", "[EquipCritTooltip] appended native
  AddInfoEx rows".
- **How:** the server sends computed totals (the client doesn't know server-side bonuses). The client renders a panel and
  adds tooltip rows.
- **Size:** M. It overlaps the crit/range addon candidate in MR-015, so do it together.

### 22. HUD colour themes, including high contrast. Client only
- **For players:** pick a HUD theme (Crimson, Forest Mint, Arctic Blue, **High Contrast**) or the classic v83-style skin.
- **Evidence:** "Crimson HUD", "Forest Mint", "Arctic Blue", "High Contrast", "Switch widget color schema for comfort and
  accessibility.", "The widget and its windows in the game's own v83 style."
- **Size:** S. It's an ImGui style swap in our HUD Edit UI, and helps accessibility.

### 23. Town citizenship: a community goal. NEEDS SERVER, L
- **For players:** sign an "Oath of Citizenship" for a town (meso fee). Your character info shows a town badge and a
  contribution gauge, and the server counts citizens toward a shared goal: "Every 100 citizens unlocks a celebration: +1
  all stat for every citizen, up to +100" ("%d / 10,000 citizens signed").
- **Evidence:** `Citizenship.img` (contract, badge 0-9), `CharacterInfo.img/Citizenship` (contribute gauge, gradeUpIcon),
  `CCitizenshipWindow`, `CCitizenshipContract`, the strings quoted.
- **How:** a server-side town membership with a fee, a contribution score and a global counter that grants a passive
  stat buff. The client shows a contract window and a badge.
- **Size:** L. Interesting social glue, but bots would inflate the counter: count real accounts only, or make bot
  citizens a deliberate part of the design.

### 24. Referral program ("Expert Board") with an invite link. NEEDS SERVER and website
- **For players:** veterans enrol as "Experts", share an invite link, and see their recruits' progress. Milestones
  pay out ("Expert Program - Milestone", "Recent:", "waiting on playtime").
- **Evidence:** `[ExpertBoard]`, "Become an Expert", "Invite link copied to the clipboard.", "No recruits yet. Share your
  invite link to bring in a new player.", REQUEST_BOARD, "BOARD role=%u season=%d recruits=%u".
- **Size:** M (website signup tracking plus server rewards). Low priority for a friends server, but cheap if
  sign-ups grow.

### 25. Account-wide "Legion" bonuses and Hyper Stats windows. NEEDS SERVER, L
- **For players:** a panel next to Character Stats shows account-wide bonuses earned by your **other characters'
  levels** (Legion), and Hyper Stat levels.
- **Evidence:** `CHyperLegionWnd`, "Legion & Hyper Stats", "No Legion bonuses yet.", "Every Hyper Stat is at level 10.",
  `EzorsiaV2_UI.wz/Legion.img`, `HyperStats.img`, `Basic.img/BtHyper`, config "Hyper Stats / Legion Bonuses windows next
  to Character Stats".
- **Size:** L (it's a game system, not just UI). It pairs with the "alt EXP bonus" idea in NOTES: both reward playing
  many characters. It needs a decision from the owner.

## Additions to existing tickets (not new tickets)

- **118 EXP/DPS meter:** Kuro shows EPS/EPM/EPH, DPS/DPM/DPH, KPM (kills per minute), "Time to Level: %.1f hours",
  "Level in: %02d:%02d", auto-start on login, and pop-out windows (`expPopoutX/Y`, `damagePopoutX/Y`).
- **120 display toggles:** see idea 13 for the exact list. Plus `map_rendering_settings.ini`: HideBackground, HideTiles,
  HideObjects ("Warning: You won't see where you can stand!", "Change maps to take effect").
- **122 pet potion window:** "Force exact pet autopot alerts" works around the native 5% slider steps (max 95%) by
  rewriting the slider values; "Sync From Client" and "Reset to HP 50%, MP 30%".
- **127 storage/batch:** auto-deposit when storage opens, deposit by category, Shift+Click to deposit a whole stack,
  Maker quantity prompt ("How many to make in a row, stops at the first failure"), Maker drawing materials from Ore Storage
  ("Auto-crafted from your ores"), and the Scroll Workshop with stop on fail or at N successes and an offer to use a White
  Scroll.
- **125 party board:** Kuro has "Optional: highlight roles you want in the party", "View Applications", and "Copies are
  locked inside expeditions".
- **121 crash report:** their `LATEST_CRASH.txt` says in plain words which kind of fault happened and what evidence
  supports it.

## Already have (seen in their client, skipped)

Discord Rich Presence (MapleRoot has the Discord SDK), boss HP bar (`theactualstuff/BossHP.cpp`), WASD/movement keys,
the ImGui HUD with HP/MP/EXP, Monster Book with drops (they kept a "CLASSIC" card-book button next to their drop
encyclopedia, which is worth copying), damage skins, coloring prism, daily check-in, chat tabs (123), controller (124),
the in-field cash shop and attack while moving.

## Seen but not recommended

Anti-cheat (TPM attestation, process blocklist for LOIC/HOIC/AutoHotkey/ArtMoney), donation points and the donation
hall, poker for donation points, the "Minecraft Social" window (a block-world toy over their widget socket), and their
post-Big-Bang class ports (Phantom Impeccable Memory, Arcane Symbols, 5th job) and chair stacking or housing. Those are
already covered in NOTES or are a content decision for the owner.

## Top 5

1. **Mob Info and loot filter (1):** the most-felt daily QoL, and it builds on our Monster Book drop data.
2. **Online Players window (2):** makes the bot population visible and useful (whisper, party, teleport). It needs a
   bot filter.
3. **Change character without relog, plus launcher direct-login (3):** saves time on every alt swap and suits our
   launcher. Use a token, not a password.
4. **Boss attempts tracker and death counter (4):** small, since `@bosslog` data exists, and it puts the info in front of
   players.
5. **GM notice with reply, and merchant sale toasts (5):** both are S, give a tester-feedback channel, and make the bot
   economy noticeable.

Close behind: the borderless window and DPI fix (9, S and client only), the party damage ranking (6, on top of 118) and
the extra toggles for ticket 120 (13).
