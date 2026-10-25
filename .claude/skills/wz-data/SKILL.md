---
name: wz-data
description: Use when reading, parsing, querying or writing tooling against the game's WZ data (wz/*.wz/*.img.xml): Skill, Item, Mob, Map, Npc and String data. Triggers include "what does WZ key X mean", "read skill/mob/item data from WZ", "scan Skill.wz", "where does the server load <stat> from", "verify a value against WZ", or writing an export or debug script over the WZ tables.
---

Adapted from nutnnut/Cosmic `.claude/skills/wz-data/SKILL.md` (nutnnut), AGPL-3.0, with our paths.

# WZ data (`wz/*.wz/*.img.xml`)

The server does not read binary `.wz` files. It reads a pre-extracted XML dump: each `*.wz` is a directory and each
`*.img` is one `*.img.xml` file.

## Where the dump lives

- Runtime folder: `<server folder>/wz/`. On the test server that is
  `/opt/mapleroot-dev/persistence-test/Server/wz` (on 164, read it over ssh; never edit it). Live has its own copy
  under its server folder: read-only for agents, and reading it is fine, writing is the owner's.
- The git repo does NOT contain `wz/`, so a fresh worktree or GitHub CI has none. Point a run at a copy with
  `-Dwz-path=/path/to/wz` (`provider.wz.WZFiles`, default `wz` relative to the working directory). Tests that need
  it must call `soloMapling.WzTestData.assumePresent("Skill.wz")` (or the right folder) so they skip when it is
  missing; `mvn -o test -Dwz-path=...` then runs them.
- `wz/` must match the jar being deployed (AGENTS.md deploy notes). The Custom.wz client file is separate and is
  binary; the server only sees what is in this XML tree.

## Layout and naming

```
wz/
  Skill.wz/   200.img.xml 210.img.xml 1000.img.xml ...   (file = job prefix)
  String.wz/  Skill.img.xml  Mob.img.xml  Map.img.xml  Npc.img.xml  Eqp.img.xml ...  (names/descriptions by id)
  Mob.wz/ Item.wz/ Map.wz/ Npc.wz/ Character.wz/ Quest.wz/ Reactor.wz/ ...
```

- Access a file by path WITHOUT `.xml`: `getData("210.img")`, `getData("Skill.img")`.
- Skill.wz: each file is a job prefix (`210.img.xml` holds `2101xxx`/`2110xxx`). Inside, a `skill` node holds one
  `imgdir` per skill id.
- String.wz holds all the human-readable text (`name`, `desc`, level blurbs `h1..hN`). The numeric files have none.
- Map.img in String.wz is two levels deep: region folder, then map id with `streetName` and `mapName`.
  `!search map` and `!warp <name>` read it (`MapSearchHelper`).

## XML node grammar

Files are minified onto ONE line. Tag = type, `name` attribute = key, `value` (or `x`/`y`) = payload
(`provider.wz.XMLDomMapleData`).

| Tag | Meaning |
|---|---|
| `imgdir` | container with children, no value |
| `int` / `short` | `value=`; hex and floats are handled by `GameConstants.parseNumber` |
| `float` / `double` | `value=` |
| `string` | `value=` |
| `vector` | `x=` `y=`, read as a `Point` |
| `canvas` | sprite; bytes are stripped in our dump, only the node and its `vector name="origin"` remain |
| `uol` | a reference to another node (path string): resolve it yourself |
| `null` | presence-only marker |

A skill subtree (pretty-printed here; the file is one line):

```xml
<imgdir name="2101005">
  <canvas name="icon" .../>            <!-- sprite, ignore -->
  <string name="elemAttr" value="s"/>   <!-- element: i/f/l/s/h/p -->
  <imgdir name="level">
    <imgdir name="1"><int name="mpCon" value="10"/><int name="mad" value="12"/><int name="prop" value="31"/><int name="time" value="4"/></imgdir>
    <imgdir name="2"> ... </imgdir>      <!-- one node per skill level -->
  </imgdir>
</imgdir>
```

Siblings of `level` whose mere presence matters (see `SkillFactory`): `info`, `common`, `action`, `prepare/action`,
`hit`, `ball`, `effect`.

## Key reference (skill data, read by `server.StatEffect` and `client.SkillFactory`)

| Key | Meaning |
|---|---|
| `time` | duration in SECONDS for skills (item effects store it in ms); `StatEffect` stores milliseconds. A missing `time` means duration -1 (then x1000 = -1000), the "infinite rebuff" trap: check the key exists before trusting a duration. |
| `mpCon` / `hpCon` | MP / HP cost |
| `mad` / `pad` | magic / physical attack |
| `damage` | percent damage multiplier |
| `mobCount` | max targets; 2 or more means area attack |
| `lt` / `rb` | left-top / right-bottom `vector`s = the skill hitbox |
| `range` | reach in pixels |
| `prop` | proc chance, percent |
| `mastery` | weapon mastery percent |
| `attackCount` | hits per target |
| `bulletCount` / `bulletConsume` | projectiles fired / ammo used |
| `skillType` | top level, not per level: 2 = buff; 1 and 3 = non-attack |
| `elemAttr` | element letter |

`isBuff` is NOT a WZ key. `SkillFactory.loadFromData` derives it from `skillType == 2`, or an `effect` node with no
`hit`/`ball`, or an `alert2` action, or a large hardcoded skill-id switch. Never rebuild that in a script: load the
real `Skill`/`StatEffect` and ask it.

## Server read path

`DataProviderFactory.getDataProvider(WZFiles.SKILL)` gives an `XMLWZFile` (lazy: each `.img.xml` is parsed when first
asked for) wrapping `XMLDomMapleData`. Navigate with:

- `data.getChildByPath("level/1/time")`: `/`-separated, `..` goes up, `null` if any segment is missing.
- `data.getChildren()` or `for (Data d : data)` to iterate.
- `provider.DataTool.getInt(path, data, def)`, `getString`, `getPoint`, `getIntConvert` (also strips a trailing `%`).
  Use these; they null-guard and coerce. Raw casts throw.

WZ reads can return null under heavy load, so always pass a default and tolerate null. The DOM parse is guarded by a
lock in our fork; do not add another parser path.

## Gotchas when writing tooling over the raw XML

1. One giant line per file: reading it directly is useless. Slice a subtree (regex, then newline on `><`), or go
   through the loaded objects.
2. Skill ids are `<imgdir name="(\d{4,})">`. Three-digit `imgdir`s are job folders or level indices.
3. `SkillFactory.getSkill(id)` returns null until `SkillFactory.loadAllSkills()` has run. Call it first in a test or
   exporter.
4. String.img descriptions need a tempered regex so a skill without a desc does not borrow the next skill's text:
   `<imgdir name="(\d{3,})">(?:(?!<imgdir name="\d{3,}).)*?<string name="desc" value="([^"]*)"`. Short ids are
   zero-padded in places (`getSkillName` prepends `000` for 4-digit ids).
5. Python 3 is available on 187 (run untrusted XML through `python3 -I`); for anything that needs the loaded
   objects write a JUnit test instead (a debug dump that always passes, writing under `tmp/`).
6. Verify ids and values against String.wz or the DB; never guess.
7. Most numeric skill keys (`damage`, `range`, `mobCount`, `mpCon`, `attackCount`) repeat under EACH `level/<n>` and
   can change per level. A first-match slice silently reads level 1. Melee weapon reach is not in Skill.wz: it is the
   per-action `lt`/`rb` in `Character.wz/Afterimage/<weapon>.img.xml`.
8. Our dump has canvas bytes stripped, so you cannot extract icons from it.
