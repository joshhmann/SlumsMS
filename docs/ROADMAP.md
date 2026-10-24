# SlumsMS roadmap: 1.5.x, 1.6.0, 1.7.0 (as agreed with the owner, 2026-10-24)

The repo copy of the roadmap (the claude.ai page https://claude.ai/artifact/Up4b1XZCejv87FkrxErveG is Claude-only).
Releases ship "when ready", rc by rc, with the routine in `docs/release/RUNBOOK.md`. Owner decisions already made are
in `docs/HANDOFF.md` section 4; don't re-ask them. Effort: S/M/L/XL. Kind: **data** = server data and scripts, no
client work; **patch** = a small slums.dll hook; **client RE** = big client reverse-engineering.

## 1.5.x: hotfixes on top of 1.5.0 (now)

Branch `release-1.5.1` from `release-1.5.0`. Only fixes and tuning, no new systems. The list is `docs/HANDOFF.md`
section 3 (bot walks away mid-chat, Guide pages too long, bots covering NPC tags, overlap/foothold, PQ bots, shutdown
noise, auto-QA flakes, roster growth settings back, memory watch), plus launcher 1.5.2.

## 1.6.0 "Life in town"

Hobbies and goals that people and bots share. All of it works on the current v83 client except the Evan test.

| Item | What | Kind | Size |
|---|---|---|---|
| **The bot brain (headline)** | Bots weigh needs, situation and personality to choose their day: grind or move on, restock (and actually go when they say "brb pots"), hang out, help a friend, show off in a low map, join a PQ, log off. Archetype blend per bot that drifts with life events, 1-2 goals each. Friends pull hard, rivals push away. Bots do what they say. `!botwhy <name>` shows the reasoning. Design: `docs/tickets/60-bot-brain.md`. | data | L |
| Smarter chat routing | 1.5.0 logs every routing decision (`RouteLog` JSONL). Tune a weighted picker on those logs, labelled offline by DeepSeek, Decide model settles close calls. Keep the owner's order (name > facing > clear reply > closest in bubble > nobody). | data | M |
| Fishing | Sit on the Fishing Chair (3011000) at a pond or Henesys docks; fish, junk and rare items on a timer. Bots fish and brag. Rewards small and tunable, never a meso farm (owner picks items). | data | S-M |
| Achievements and medals | ~30 starter goals (first 4th job, 100 fish, Zakum clear, wedding, 30-day streak) awarding v83 medals/titles. Bots earn them too. GM command to grant/preview. | data | S-M |
| Daily dungeon | Monster Park style: 4-6 rooms on existing maps, chest at the end, once a day per character, built for you plus friend bots. | data | M |
| Chaos Zakum and Chaos Horntail | Ported from later data, run by copies of the expedition scripts, bot guilds stage runs. Check every mob for fields/elements v83 lacks (they crash the client on hit). | data | M |
| Evan test | GM-only trial on a separate branch that never ships in 1.6.0: can the character and dragon animate together without crashing? Yes → Evan in 1.7.0; no → an Evan skill cover. | patch | L |
| Parsec / virtual display fix | dgVoodoo2 wrapper (`docs/tickets/39-client-dgvoodoo-wrapper.md`) so error -2147467259 goes away. | patch | M |
| Client settings window | A real in-client settings window instead of hand-editing `config.ini`. | patch | M |
| Client visual QA | The client MCP (`docs/tickets/61-client-mcp.md`, `docs/qa/CLIENT-MCP.md`) as a routine part of each rc. | tooling | M |

Carried over from 1.5.0: grow the chat line bank past 10k from the owner's votes; find why some bots land on the same
spot on unrelated maps after a map change; auto-QA flake fixes; retire the Electron launcher once everyone is on
Tauri.

Order: **rc1** the bot brain, then fishing and medals as options it chooses. **rc2** daily dungeon and chaos bosses,
chat routing tuning. **Alongside** the Evan test (own branch), dgVoodoo2 and the settings window (client line).
Then QA and release as in the runbook.

## 1.7.0 "New lands"

| Item | What | Kind | Size |
|---|---|---|---|
| Lion King's Castle and Empress Cygnus | Maps, mobs, NPCs and gear from Rulaxing's Community v83 repack, rebalanced. The pack has no portals, scripts or cash items, so quests and boss scripts are ours. Von Leon and Cygnus become bot guild expedition targets. | data | L |
| Neo Tokyo | MapleRoyals-style endgame (Vergamot, Dunas, Nibergen, Auf Haven, Silver Coin crafting). Port the maps first. | data | L |
| Evan | Full class if the 1.6.0 test passed (creation, 10 job levels, dragon, quests, bots that play it); else an Evan skill cover on a magician job. | client RE | XL |
| Client quality-of-life | Small slums.dll patches (higher resolution, known popups, movement fixes) using MapleEzorsia-v2 and v83 address lists as reference; each checked on its own. | patch | M |
| Small extras | Hyper Teleport Rock, NPC crafting, damage skins (server-wide first). | data/patch | S-M |
| Browser client research spike | Can SlumsMS run in a browser? Research only, a written answer, no shipping. | research | S |
| Chat routing encoder (only if 1.6.0 tuning hits a ceiling) | Fine-tune Decide's encoder on SlumsMS chat (MPC-BERT style), A/B against the current one. | data | M |

Order: **rc1** Lion King's Castle with its boss and bot raids. **rc2** Cygnus and Neo Tokyo. **rc3** Evan or the skill
cover, plus the client fixes.

## Owner decides later (ask when the item starts)

Fishing rewards and rarity; the ~30 medals; chaos boss difficulty for 5-15 friends plus bots; Evan go/no-go after
the test.

## Left out on purpose (don't start these)

Dual Blade, Cannoneer, Mercedes, Demon Slayer, Resistance (no v83 ports exist, only skill covers); Familiars and
Monster Life (need windows the client lacks); Potential and cubes (economy impact). No economy/ledger work without
the owner asking.

## Risks

Ported mobs can crash the client on hit (test each one); Evan may not work on v83 (the test finds out cheaply); new
regions add memory and load (the 3k soak sets the budget: live is `-Xmx6G` and crept ~80 MB/h old-gen in the soak).
