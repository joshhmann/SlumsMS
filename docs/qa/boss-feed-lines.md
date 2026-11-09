# Boss feed lines (1.6.7, branch 167-boss-feed-lines, batch 1)

Expected outcome, written before the code. Observe only: no gameplay changes. Plan:
`/root/work/agent-runs/serverdev/plans/boss-feed-lines-167.md`; analyser contract:
`tools/qa-cards/contract.yaml` and the zakum, horntail and pianus cards on rnd-qa-cards.

## Rules

- One line per event, never one per mob death or per tick.
- Character ids only. The only name is the run's leader tag `[<content>, leader <name>]` that the PQ lines already use.
- Two sinks per line:
  1. the QA feed (QaLog topic MECHANICS, only while a runner polls), with the run's human member ids as the line's characters;
  2. the server log at INFO, logger name `BossFeed` (always, so a live fight can be read afterwards).
- `soloMapling.qa.BossFeed`: `line(eim, text)` for event bosses, `field(map, text)` for field bosses, `on()` is cheap.
  Scripts reach it through `eim.feed(text)` on EventInstanceManager (no Java imports in scripts).
- BOSS lines ride the MECHANICS topic for now (a new QaLog topic is outside this batch's fence); the text is the
  same either way, so moving them is a one-constant change.
- S is whole seconds since the instance was set up (scripts store the start in eim property `feedT0`).

## Event bosses (MECHANICS, `RUN boss ...`, tag `[zakum|chaoszakum|horntail, leader X]`)

| Outcome | Hook | Line |
|---|---|---|
| arms_down | `monsterKilled`, the last of the 8 arms | `RUN boss zakum arms down: all 8 after S s [zakum, leader X]` |
| body_chain | `monsterKilled`, body 8800000 / 8800001 dies | `RUN boss zakum body 8800001 up after S s [...]` (then 8800002) |
| boss_defeated | script `clearPQ` | `RUN boss zakum cleared after S s: defeatedBoss=1 [...]` |
| rewards | `monsterKilled` after the gainItem loop | `RUN boss zakum rewards: item 4001126 x20 to K members, B bots skipped [...]` |
| cleanup | `end(eim)` | `RUN boss zakum cleanup: M members moved to 211042400, instance disposed [...]` |

Chaos Zakum: the same with `chaoszakum`, bodies 8800101 / 8800102, arms 8800103-8800110.

| Outcome | Hook | Line |
|---|---|---|
| head1 / head2 | `monsterKilled`, 8810000 / 8810001 | `RUN boss horntail head 1 down after S s [horntail, leader X]` (and head 2) |
| parts_down | `monsterKilled`, the 8th of 8810002-8810009 | `RUN boss horntail parts down: all 8 after S s [...]` |
| boss_defeated | script `clearPQ` | `RUN boss horntail cleared after S s: defeatedBoss=1 [...]` |
| rewards | `monsterKilled` after the loop | `RUN boss horntail rewards: exp E each (rate-scaled), item 4001126 x40 to K members, B bots skipped [...]` (E = the base 107545760 before each member's rate) |
| cleanup | `end(eim)` | `RUN boss horntail cleanup: M members moved to 240050600, instance disposed [...]` |

Not in this batch (hook is outside the fence): Zakum `spawn` (reactor 2111001.js) and Horntail `main_start`
(reactor 2401000). Both are listed under questions in the report.

## Field boss (Pianus 8510000 R / 8520000 L, map 230040420; BOSS prefix)

| Line | Hook |
|---|---|
| `BOSS spawn pianus R map 230040420 source timer\|gm` | MapleMap.spawnMonster; `gm` only inside the !pianus command |
| `BOSS kill pianus R after S s, damagers K` | Monster.killBy; S since its spawn, K = characters in its damage list |
| `BOSS reward pianus R exp E to K members` | Monster.killBy; E = the mob's base exp, K = damagers on the map |
| `BOSS summon <boss> bloody-boom N` | MobSkill.summonMonsters, N = mobs actually spawned |
| `BOSS immune <boss> weapon\|magic 35 s` | MobSkill PHYSICAL_IMMUNE / MAGIC_IMMUNE, only when the status was applied |
| `BOSS heal <boss> +H hp (now P%)` | MobSkill HEAL_M, H = HP the casting boss actually gained |
| `BOSS skills <boss> heals H (+X hp), summons S (N mobs), immunes I` | Monster.killBy, once per boss death |

`<boss>` is `pianus R` / `pianus L` for those ids and `mob <id>` for any other `isBoss()` mob.
Summon, immune and heal lines fire only for `isBoss()` monsters. Rate limits per monster and skill kind: feed 3 s,
log 30 s; the summary line on death covers what the log skipped. A non-boss mob writes nothing.

## Volume

Event bosses about 10 lines (about 1.5 KB) per run. Skill lines: log at most 2 per minute per monster per kind, so a
30-minute Horntail is at most about 1,600 lines (240 KB) worst case, under 20 KB realistically.

## What existing characters keep

Nothing changes for any player or character: log and feed lines only.
