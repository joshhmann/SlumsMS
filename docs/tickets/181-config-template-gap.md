# Ticket 181: the shipped config template is not a working template, so a fresh install boots with 13 boot-critical fields at 0

**Status:** open for 1.6.4. Filed 2026-11-02 09:04 UTC (01:04 PST) from the 1.6.4 content hunt; the fix is
already written, tested and green on branch `164-configtemplate` (worktree `/root/work/SlumsMS-164-configtemplate`,
commits `fc17b56c4` and `3e451ef3a`) and is waiting to be merged. This is the highest-value item in the hunt:
nothing else on this list is finished work sitting unmerged.

Line numbers below are as of `release-1.6.4` tip `c0e0a2cd7`, which is the tree this ticket targets. The lane was cut
from `163-integration` at `c589cf49d`, whose numbers differ by a few lines; grep the symbol, not the number.

## What is wrong

`README.md:10` tells a new operator to copy `config.example.yaml` to `config.yaml` and start the server. The shipped
file does not describe the configuration the server reads:

- On `release-1.6.4` it is 9 lines: an empty `worlds: []` plus `CHARSET`, `DB_HOST`, `DB_USER`, `DB_PASS`,
  `DB_URL_FORMAT`, `DB_CONNECTION_TIMEOUT_MS`.
- On the lane's base (`c589cf49d`) it was 10 lines and carried `PARTY_SEARCH_REENTRY_LIMIT` instead of
  `DB_CONNECTION_TIMEOUT_MS`.
- `ServerConfig.java` declares **359** fields (`getDeclaredFields()` minus statics, and
  `grep -cE '^\s+(public|private|protected)\s' src/main/java/config/ServerConfig.java` agree). The shipped template
  sets exactly **6** keys under `server:` — `CHARSET`, `DB_HOST`, `DB_USER`, `DB_PASS`, `DB_URL_FORMAT`,
  `DB_CONNECTION_TIMEOUT_MS` — so **353 of 359** are unset, plus every field of the one `WorldConfig` (the template's
  `worlds:` is an empty list).
- Provenance of the "358" that appears in the lane's report: the real count is 359, and a type-token grep that cannot
  span the space in `public Map<String, String>` gives 354, because it drops the five map fields at
  `ServerConfig.java:219` (`BOT_LLM_EXTRA_BODY`), `:220` (`BOT_LLM_EXTRA_HEADERS`), `:239` (`BOT_LLM_CLOUD_EXTRA_BODY`),
  `:241` (`BOT_LLM_CLOUD_EXTRA_HEADERS`) and `:565` (`NPCS_SCRIPTABLE`). So 358 is one short of the truth and must not be
  copied forward; the lane's own test uses reflection and is not affected by either miscount.

`src/main/java/config/YamlConfig.java:19-29` is the whole loader: `loadConfig()` at `:21` builds a plain
`YamlReader` at `:22`, calls `reader.read(YamlConfig.class)` at `:23`, and its only error handling is two `catch`
blocks that wrap `FileNotFoundException` and `IOException` in a `RuntimeException`. There is no strictness and
no per-key defaulting, so an absent key does not fall back to a documented value: the field keeps its JVM default,
which is `0` or `false` for every field whose declaration has no inline initialiser. A missing key is not an error and
not a warning. The server boots, and misbehaves later.

## What the 0s do (all three measured in this tree)

The template cannot express these, and each one is a boot or first-login failure:

1. `ITEM_EXPIRE_CHECK` (declared `src/main/java/config/ServerConfig.java:407`, no initialiser). `TimerManager.register`
   ends in `ses.scheduleAtFixedRate(new LoggingSaveRunnable(r), delay, repeatTime, MILLISECONDS)`
   (`src/main/java/server/TimerManager.java:97-99`), and a 0 period throws `IllegalArgumentException` there. The caller
   is `MapleMap.java:990` inside `startItemMonitor()` (opens at `:949`), reached from `MapleMap.java:2891` in
   `addPlayer` under `if (chrSize == 1)`. So the **first player to enter any map** throws out of `addPlayer`.
2. `MAP_VISITED_SIZE` (`ServerConfig.java:409`). `Character.java:2112` is
   `if (lastVisitedMaps.size() == YamlConfig.config.server.MAP_VISITED_SIZE) {` followed by `lastVisitedMaps.remove(0)`;
   at 0 the list is empty and the removal throws `IndexOutOfBoundsException` on the character's first map change.
3. `MOB_STATUS_AGGRO_INTERVAL` (`ServerConfig.java:417`). `MonsterAggroCoordinator.java:90-93` registers a monitor with
   the field as both period and delay, so it throws the same way at boot; the division at `:98`
   (`(now - lastStopTime) / MOB_STATUS_AGGRO_INTERVAL`) would divide by zero but is never reached because `:93` throws
   first.

The lane's test names thirteen such fields (its `ZERO_BREAKS_THE_READ_SITE` list): `ITEM_EXPIRE_TIME`,
`KITE_EXPIRE_TIME`, `ITEM_MONITOR_TIME`, `LOCK_MONITOR_TIME`, `ITEM_EXPIRE_CHECK`, `ITEM_LIMIT_ON_MAP`,
`MAP_VISITED_SIZE`, `MAP_DAMAGE_OVERTIME_INTERVAL`, `MAP_DAMAGE_OVERTIME_COUNT`, `MOB_STATUS_MONITOR_PROC`,
`MOB_STATUS_MONITOR_LIFE`, `MOB_STATUS_AGGRO_PERSISTENCE`, `MOB_STATUS_AGGRO_INTERVAL`.

## The finished fix on `164-configtemplate`

- `fc17b56c4` rewrites `config.example.yaml` from 10 lines to **535**, listing every `ServerConfig` and `WorldConfig`
  field in source order, and adds one bootable `worlds:` entry (`id 0`, one channel, exp/meso/drop rates and the rest
  set to plausible values) so the file is a running configuration rather than a shape.
- `3e451ef3a` adds `src/test/java/config/ConfigTemplateCompletenessTest.java` (4 tests): every reflective field of
  `ServerConfig`/`WorldConfig` appears in the template; the boot-critical fields are non-zero; the `worlds:` list is
  non-empty and each entry is complete; the template parses with the same `YamlReader` the server uses and produces a
  `YamlConfig` whose values equal the template text.

Recorded proof (from `/root/work/agent-runs/server/164-configtemplate/REPORT.md`):

- fail-before, template reverted: `[ERROR] Tests run: 4, Failures: 4`, including
  `IllegalStateException: config.yaml has no 'worlds:' entries; at least one world is needed.`
- pass-after: `[INFO] Tests run: 4, Failures: 0`
- `config.*Test` aggregate: `Tests run: 14, Failures: 0`

That is the shape this ticket's acceptance wants: the test fails on the shipped template and passes on the new one.

## Merge hazard

The lane was cut at `c589cf49d`. `release-1.6.4`'s tip (via `163-integration`, `6cea00b4e`) has since given the four
family fields their initialisers — `ServerConfig.java:486-489`, `FAMILY_REP_PER_KILL = 4`,
`FAMILY_REP_PER_BOSS_KILL = 20`, `FAMILY_REP_PER_LEVELUP = 200`, `FAMILY_MAX_GENERATIONS = 1000`, with the comment
"0 is not a neutral value: it pays no reputation at all and turns the generation cap into a wall that refuses every
join". The lane's template already carries the matching keys at `config.example.yaml:422-425` with exactly those four
values, and the lane's own `ServerConfig.java` still has the four fields bare. So on `163-integration` this is at worst
a small textual conflict, and **the lane's template is the file to keep**.

One real gap when merging onto `release-1.6.4` rather than `163-integration`, and it is confirmed, not suspected:

- The lane's rewritten `config.example.yaml` (`3e451ef3a:config.example.yaml`) has **zero** matches for
  `connection_timeout` (`grep -n -i connection_timeout` → exit 1, no output). It lists 358 `server:` keys.
- `DB_CONNECTION_TIMEOUT_MS` arrived in `1225800eb` ("Keep refused bot writes and departures instead of dropping them"),
  which is **not** an ancestor of `3e451ef3a` (`git merge-base --is-ancestor 1225800eb 3e451ef3a` → exit 1). The lane
  never saw the field.
- Comparing this branch's 359 field names with the lane's 358 keys, `comm -23` (in the template's field set, missing
  from the lane) is exactly `DB_CONNECTION_TIMEOUT_MS` and `comm -13` (in the lane, not a field) is empty. So the lane's
  template is 359 − exactly that one field.

So the merge worker must restore the key, not "check whether it is there": add `DB_CONNECTION_TIMEOUT_MS: 30000` to the
merged `server:` section at the same value and in the same comment style as the current
`config.example.yaml:8-9` ("How long a thread waits for a pooled connection before its work fails (ms). 0 = 30000
(Hikari's default)."). With it restored, `ConfigTemplateCompletenessTest.everyServerFieldAppearsInTheTemplate` covers
359/359 instead of passing with a hole — the test compares the template's keys against the reflected field names, so a
missing key fails it, and that is the check to run. A second, cheapest confirmation after the merge:
`grep -n DB_CONNECTION_TIMEOUT_MS config.example.yaml` returns one line.

## This bug class has hit the repo five times

Each one was a shipped key whose absence silently made a subsystem inert, found after the fact:

1. `PARTY_SEARCH_REENTRY_LIMIT` — bare `public int PARTY_SEARCH_REENTRY_LIMIT;` at `ServerConfig.java:394` on this
   branch; `163-integration` has since given it `= 10` on the same line. Any config that omits the key still reads 0
   here, which is why it was the first field the template documented.
2. The four `FAMILY_*` fields — muted the whole family system until `6cea00b4e`.
3. `ITEM_EXPIRE_TIME` — every drop expired on the next sweep.
4. `MAP_VISITED_SIZE` — first map change threw.
5. `MOB_STATUS_AGGRO_INTERVAL` — aggro monitor never started (or boot died, depending on which 0 hit first).

The template test is the only mechanism in the repo that stops the sixth.

## Acceptance

- `config.example.yaml` lists every `ServerConfig` and `WorldConfig` field and contains a bootable `worlds:` entry.
- `ConfigTemplateCompletenessTest` fails against the shipped template and passes against the new one.
- `DB_CONNECTION_TIMEOUT_MS` survives the merge.
- The 13 fields above are non-zero in the template and match their code defaults.

## The lane's four recommendations (separate owner questions, not part of this merge)

From the lane report, each one is a decision the owner has to make; none of them blocks the template merge:

1. Give `ITEM_EXPIRE_CHECK`, `MAP_VISITED_SIZE` and `MOB_STATUS_AGGRO_INTERVAL` inline initialisers, so a hand-written
   config that omits them still boots. (The template documents them; the field default does not protect them.)
2. Put a floor under the `REBIRTH_*_RATE` assignment, so a config that removes a rate cannot make rebirths free.
3. Cap the jumpquest chest reward (`JUMPQUEST_CUSTOM_EXP_MESOS_REWARD`, `ServerConfig.java:460`; the gate is
   `=== 1` at `scripts/npc/1063000.js:38` and `1063001.js:38`, with no cooldown of its own — the template ships it at
   `0`, i.e. off, which is the safe value until it is capped).
4. Drop the four keys that are declared but never read, so the template does not promise a switch that does nothing.

## For the release book

- **Card:** Config | the shipped config template omits 353 of 359 keys and every world, so a fresh install boots into 0-valued boot-critical fields | S2 | lane Server | closer: merge `164-configtemplate` (`fc17b56c4`, `3e451ef3a`), restore `DB_CONNECTION_TIMEOUT_MS` (see "Merge hazard"), and run `ConfigTemplateCompletenessTest` — 4/4 fail on the old template with `IllegalStateException: config.yaml has no 'worlds:' entries`, 4/4 pass on the new one, `config.*Test` 14/0.
- **Patch notes (draft, player words):** "A fresh install now starts with a complete configuration, so a new server no longer dies on the first player's map entry or loses every drop." (staff-facing install note; it is not a player-visible change — use "staff only, no line" if the book prefers that)
- **Confign line:** none — the template is a file, not a switch; nothing in `docs/release/1.6.4.conf` changes and `CONFIG_APPEND` stays empty.
- **Test-tab row:** test: `ConfigTemplateCompletenessTest` | how: copy `config.example.yaml` to `config.yaml` and boot a clean server; a player logs in and changes map without a stack trace, and drops do not vanish on the next sweep.
