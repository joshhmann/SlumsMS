# Content atlas schema v1 (draft, 2026-11-07)

One machine-readable catalogue of SlumsMS content for staff, QA expected outcomes, the quest/reward audit and the
R&D analyser. Not on the public site; the staff view is admin-gated. Built by `tools/atlas/build_atlas.py` from our
own TEST server WZ copy, SELECT-only TEST DB exports and the repo's scripts/yaml. No player data, no secrets.

## Layout
`data/atlas/<release>/` : `meta.json`, `quests.json`, `npcs.json`, `items.json`, `mobs.json`, `maps.json`,
`scripts.json`, `pq.json`, `bosses.json`. Each kind file is `{ "<id>": row, ... }`, ids as strings, keys sorted,
UTF-8, 1-space indent (stable diffs between releases).

## Rules for every row
- `id`: number (the key as a number). `name`: plain text. `reach`: `player | gm | event | unobtainable` (a flag, never
  a filter; the site's reachable-only cut is not used).
- `src`: `[{kind, path, line}]`, at least one entry. `kind` is `wz`, `script`, `yaml`, `java`, `doc` or `db-export`.
  `path` is relative to the repo (script, yaml, java, doc) or to the WZ root (`Quest.wz/Check.img.xml`). `line` is where
  the record starts. A `db-export` source has `{kind, snapshot, date, table, key}` and no line. If a line cannot be
  placed the field is `null` (never guessed).
- Refs are typed and forward only: `{"k":"item","id":4001016}`. `k` is `item|mob|map|npc|quest|script|skill|pq|boss`.
  No `used_by` is stored; consumers compute back-references.
- Text is plain strings (no markup): `name`, `desc`, quest dialogue. A string that is Korean-only has a sibling
  `lang: "ko"` on that row.
- Rows carry only facts a source backs ("no source, no fact").

## meta.json
`schema_version` (semver; additive = minor, rename/removal = major, announced to consumers first), `release`,
`server_commit`, `wz_sha` (sha256 over the sorted per-file hashes of the WZ copy), `db_snapshot` (name, date, tables),
`built_at` (UTC), `counts` per kind.

## Kinds
- **quests**: `{id,name,reach,start_npc:ref,end_npc:ref,area,requirements:{min_level,max_level,jobs,quests:[{ref,state}],
  items:[{ref,count}],mobs:[{ref,count}],field_enter:ref,meso,start_date,end_date,interval},rewards:{exp,meso,fame,
  items:[{ref,count,prop,job}],skills,buffs,next_quest:ref,quest_states:[...]},start_script:ref,end_script:ref,
  text:{start:[...],end:[...],progress:[...]},chain:{prev:[ref],next:[ref]},src}`.
- **npcs**: `{id,name,reach,maps:[{ref,x,y}],shop:[{ref(item),price}],quests_start:[ref],quests_end:[ref],script:ref,src}`.
- **items**: `{id,name,desc,type,req_level,req_job,stats,price,drops:[{ref(mob),chance,min,max}],shops:[{ref(npc),price}],
  quest_reward:[ref],quest_require:[ref],scripts:[ref],recipes:[...],slums_changed,reach,src}`.
- **mobs**: `{id,name,level,hp,mp,exp,boss,maps:[{ref,count}],drops:[{ref(item),chance,min,max}],reach,src}`.
- **maps**: `{id,name,street,mobs,npcs,portals:[{name,type,target_map:ref,target_portal,script:ref}],on_user_enter:ref,
  reach,src}`.
- **scripts**: `{id (path without extension, e.g. "quest/1021"),path,kind (npc|quest|portal|reactor|event|item|map),
  lines,header,calls:[{fn,args,line}],mentions:[{k,id,line}],src}`. `calls` are the item/quest/warp/npc/event calls
  (`gainItem`, `forceStartQuest`, `forceCompleteQuest`, `warp`, `teachSkill`, `startQuest`, `completeQuest`, `gainExp`,
  `gainMeso`, `spawnMonster`, `dropItems`, `getEventManager`, ...); `args` is the argument source text, trimmed;
  `mentions` are numeric literals that resolve to a known id in a call that fixes their kind (an item id given to
  `gainItem` is `{k:"item"}`), plus quest ids in `isQuestStarted/Completed`.
- **pq / bosses**: keyed by the yaml `id:` (`zakumpq`), stages by 1-based position. Two source classes per row: the
  bots' yaml view (`src kind yaml`, cite file:line and commit; "not game truth") and the real gates (`scripts/event/
  <Name>.js`, NPC scripts, `ExpeditionType.java`, `ExpeditionBossLog`, `RebirthBosses.java`).

## Not in v1
Skills, pets, cash shop catalogue, reactors as their own kind. Added later as minor bumps.
