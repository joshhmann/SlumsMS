# 112: How players could get the Maker skill (Dalair's merge gate)

Status: (c) done in 1.6.0 - Dalair's merge is level only (owner 2026-10-27), no Maker needed. (a)/(b) remain for 1.6.1.

Owner, 2026-10-27: Dalair's equipment merge (NPC 9000040) needs level 160 (110 for Cygnus) **and Maker level 3**
(`Character.meetsMergeGate`, owner decision 2026-10-26). Nobody can reach Maker 3 on this server, so the merge is
closed to everyone as built (t-np-4 prints this as a NOTE). The owner wants a note on how players could get Maker,
not a code change yet. Research only; no code changed for this ticket.

## The skill

| Skill id | Who | Notes |
|---|---|---|
| 1007 (`0001007`) | Explorers (jobs 100-522) | Master level 3 |
| 10001007 | Cygnus Knights (1100-1512) | `Noblesse.MAKER` |
| 20001007 | Aran (2100-2112) | `Legend.MAKER` |
| 20011007 | Evan (2200-2218) | `Evan.MAKER`; Evan is still a trial (ticket 98) |

The server looks it up as `(jobId / 1000) * 10000000 + 1007` (`MakerService.getMakerSkillLevel`,
`NpcMakerCraftingService.getMakerSkillLevel`).

## Why nobody has it

Checked 2026-10-27, read-only:

- **TEST server Skill.wz** (`/opt/mapleroot-dev/persistence-test/Server/wz/Skill.wz/000.img.xml`, `1000.img.xml`,
  `2000.img.xml`): no `0001007`, `10001007` or `20001007` node. MapleRoot deleted them (it crafts at NPCs instead,
  `NpcMakerCraftingService` / `scripts/npc/interactions/shared/MakerCrafting.js`). The strings are still there
  (`String.wz/Skill.img.xml` has "Maker" for 0001007 and 10001007), and `Etc.wz/ItemMake.img.xml` (the recipes) is
  intact. Live's folder is the same MapleRoot data (not opened for this note).
- **Client** (`/srv/slums-patch/layers/base/v83-2026.10.19/files/Data/Skill/000.img`, `1000.img`, `2000.img`,
  decoded with `tools/clientcheck/wzscan.py`): no Maker node either (28 / 27 / 29 skills, none ending in 1007). No
  client layer (1.2.1 to 1.5.0) replaces those files. The client still has the rest of the Maker feature: the
  `UI/UIWindow.img/Maker` window (also in the 1.5.0 layer's UIWindow.img) and `Etc/ItemMake.img`.
- **Stock data still exists**: the Cosmic reference dump on 164 (`/opt/cosmicms/wz/Skill.wz`, also
  `/opt/astravia-reference` and `/opt/maplesolo-merge`) has all three nodes: icons, `invisible=1`, `disable=1`, levels
  1-3 pointing at the h1/h2/h3 strings. That is the source for option (a).
- **Quests**: `server.quest.HiddenQuests` hides Moren's chain 6029-6036 for every job ("no Maker skill 1007", 1.5.2
  quest sweep, ticket 82), so no one can start them. Without the hide they would be worse: 6029's act gives skill 1007
  level 1, and `AbstractPlayerInteraction.teachSkill` silently skips a skill missing from Skill.wz, so players would
  finish the chain and get nothing.
- **Server code is ready**: `MakerSkillHandler` (the Maker window's packets), `MakerService` and the quest end
  scripts `scripts/quest/6033.js` (teaches level 2) and `6036.js` (teaches level 3) are all present.

## The original route (GMS v83 / stock Cosmic data)

The NPC is **Moren** (2110004; the owner calls him Meren) in Magatia (map 261000002).

| Maker level | Quests | Requirements |
|---|---|---|
| 1 | 6029 "Meren's New Discovery" with three lessons: 6030 Carson (2111000, Alcadno), 6031 Hughes the Fuse (2012017, map 200082301), 6032 Moren | level 45, a 1st-job+ Explorer/Cygnus/Aran/Evan; 10,000 mesos per lesson. Reward: Maker 1 (master 3), 50,000 EXP, "A Guide to Using the Maker Skill" (4161037) |
| 2 | 6033 "Meren's Second Round of Teaching" | level 75, 6029 done; craft an Intermediate Monster Crystal 1 (4260003) with Maker **after** starting the test. Reward: Maker 2, 230,000 EXP |
| 3 | 6034 "What Meren Dropped", 6035 "The Owner of the Note", 6036 "A Surprise Outcome" | level 105, 6033 done; take the Doodly Paper (4031979) to NPC 2112017 and back, then craft a Gold Anvil (4031980) with Maker. Reward: Maker 3 |

Level 2 and 3 are tests of the Maker skill itself (craft a crystal, craft the anvil), so the original route only
works if the skill works in the client.

## Options

### (a) Bring Maker back: skill data in server and client WZ, unhide the quests
- **Server**: copy the three (four with Evan's 20011007 in `2001.img.xml`) skill nodes from the Cosmic dump into the
  server's `Skill.wz` XML; deploy with the jar like other data. Remove 6029-6036 from `HiddenQuests`.
- **Client patch**: yes. A client layer replacing `Data/Skill/000.img`, `1000.img`, `2000.img` (~4 MB each, plus
  `2001.img` for Evan) with the Maker node added, written with an img editor on Silvia (HaRepacker-style; we have a
  reader, `wzscan.py`, not a writer). Without it the skill is invisible in the client and can't open the Maker
  window. Ships in MMO order (client first), RC/Test channel, real-client check, hash-diff rule before Live.
- **Effort**: M (data S, client layer S-M, QA M: walk 6029-6036 on TEST with a real client, craft with the Maker
  window, check `MakerSkillHandler` against the client's packets, which nobody has exercised on this build).
- **Risks**: the Maker window is a second crafting path next to MapleRoot's NPC crafting, with its own economy
  (monster crystals, gem/stimulator recipes, `USE_MAKER_PERMISSIVE_ATKUP`): needs the owner's economy say-so. A
  client that hasn't updated sees an unknown skill id (should be harmless for an invisible skill, but untested). Any
  code reading Skill.wz skill counts (skill catalog, bot skill lists) will see a new beginner skill. Bots: decide
  whether they ever get it (probably not).

### (b) A custom route granting Maker levels, server only
- Keep the skill out of the client. Give players a "Maker level" the server stores itself (a quest record such as an
  unused custom quest id's progress, or a column), granted by a short custom chain at Moren (reuse 6029-6036's text,
  replace the crafting tests with things that work here: hand in NPC-crafted items, level 45/75/105 gates, mesos).
  `MakerService.getMakerSkillLevel` and `NpcMakerCraftingService.getMakerSkillLevel` return the higher of the real
  skill (always 0 today) and the stored level, so Dalair's gate and MapleRoot's NPC-crafting Maker lines read it.
- **Client patch**: none. The quest log can't show a custom chain unless it reuses existing Quest.wz ids, so either
  reuse 6029-6036 (hide only the steps that need the skill, and override their acts) or make it an NPC dialogue at
  Moren with no quest-log entry.
- **Effort**: S-M (a script, a stored level, the two lookups, tests, a `!qa` scenario and an auto-QA check replacing
  t-np-4's NOTE).
- **Risks**: the Maker window still doesn't exist (players reading old guides will look for it); the requirements are
  ours to invent and balance; the stored level must survive name changes/alts the same way skills do.

### (c) Change Dalair's gate to level only
- `Character.meetsMergeGate`: drop `makerLevel >= 3` (level 160, 110 for Cygnus). Update `DalairMigrationTest`,
  `EquipmentMergeCapTest`, Probes160 t-np-4 and Dalair's dialogue text in `scripts/npc/9000040.js`.
- **Client patch**: none. **Effort**: S (an hour with tests).
- **Risks**: the merge opens sooner to anyone at 160 (the 3-merge cap still holds); loses the "earn it" step the owner
  asked for on 2026-10-26.

## Recommendation

**(b) for 1.6.1**: it opens the merge with a real earn-it step and needs no client patch, which keeps 1.6.1 server-only.
If the owner wants the merge open right away, do **(c)** first (S) and add (b) on top later. Keep **(a)** for a release
that already ships a client layer (1.7.0 client work) and only if the owner wants the Maker window's crafting economy
back; it needs his economy go.
