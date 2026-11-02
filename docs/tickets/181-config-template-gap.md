# Ticket 181: the shipped config template is not a working template, so a fresh install boots with 13 boot-critical fields at 0

**Status:** open for 1.6.4, **merged into `163-integration` and not yet in `release-1.6.4`**. Filed 2026-11-02 09:04 UTC
(01:04 PST) from the 1.6.4 content hunt. The lane's two commits `fc17b56c4` and `3e451ef3a` are now on
`163-integration` as merge `1c168eff9` ("merge 164-configtemplate: make config.example.yaml a complete, bootable
template"); the full suite recorded for that merge is `Tests run: 10483, Failures: 0, Errors: 0, Skipped: 146`, BUILD
SUCCESS — exactly +4 over `ea1981231` (`10479 / 0 / 0 / 146`), and all four are
`config.ConfigTemplateCompletenessTest`. Every acceptance item below is met on `163-integration`; the one still open is
the forward merge into `release-1.6.4` ("Merge hazard"), which is why this ticket stays open.

Line numbers below are as of `release-1.6.4` tip `c0e0a2cd7` unless a branch is named. The lane was cut from
`163-integration` at `c589cf49d`, whose numbers differ by a few lines; grep the symbol, not the number. The two
branches also differ in the field set (359 fields here, 358 there) — see "Merge hazard".

## What is wrong

`README.md:10` tells a new operator to copy `config.example.yaml` to `config.yaml` and start the server. The shipped
file does not describe the configuration the server reads:

- On `release-1.6.4` it is 9 lines: an empty `worlds: []` plus `CHARSET`, `DB_HOST`, `DB_USER`, `DB_PASS`,
  `DB_URL_FORMAT`, `DB_CONNECTION_TIMEOUT_MS`.
- On the lane's base (`c589cf49d`) it was 10 lines and carried `PARTY_SEARCH_REENTRY_LIMIT` instead of
  `DB_CONNECTION_TIMEOUT_MS`.
- `ServerConfig.java` on this branch declares **359** fields (`getDeclaredFields()` minus statics, and
  `grep -cE '^\s+(public|private|protected)\s' src/main/java/config/ServerConfig.java` agree). The shipped template
  sets exactly **6** keys under `server:` — `CHARSET`, `DB_HOST`, `DB_USER`, `DB_PASS`, `DB_URL_FORMAT`,
  `DB_CONNECTION_TIMEOUT_MS` — so **353 of 359** are unset, plus every field of the one `WorldConfig` (the template's
  `worlds:` is an empty list).
- Provenance of the "358" in the lane's report: it was counted on `163-integration`, which declares **358** `ServerConfig`
  fields because it does not have `DB_CONNECTION_TIMEOUT_MS` — `grep -cE '^\s+(public|private|protected)\s'
  src/main/java/config/ServerConfig.java` is 358 on `163-integration` and 359 on `release-1.6.4`. Both numbers are
  right for their branch; `release-1.6.4`'s 359 is the one this ticket's 353-of-359 figure uses. (A type-token grep
  cannot span the space in `public Map<String, String>` and gives 354 here, dropping the five map fields at
  `ServerConfig.java:219` `BOT_LLM_EXTRA_BODY`, `:220` `BOT_LLM_EXTRA_HEADERS`, `:239` `BOT_LLM_CLOUD_EXTRA_BODY`,
  `:241` `BOT_LLM_CLOUD_EXTRA_HEADERS` and `:565` `NPCS_SCRIPTABLE`; the lane's test reflects, so it always counts
  exactly.)

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

**On `163-integration` there is nothing left to do.** The merge landed as `1c168eff9`, and the one conflict was in
`config.example.yaml`, on the four `FAMILY_*` keys: `164-familycfg` had appended them at lines 10-18, out of source
order, and the lane already listed them in source order. Resolution: the lane's file, which is the only copy that keeps
the source order. (The two merge commits that exist for this lane carry the same parents and subject; the landed
`1c168eff9` does **not** carry `164-familycfg`'s four per-key comments, the unattached `7ace56ad0` does. Neither
changes a key or a value.) Verified on `163-integration` afterwards: 358 declared `ServerConfig`
fields against 358 `server:` keys, `comm` in both directions empty, no duplicate key anywhere, key order identical to
source declaration order, and `yaml.safe_load` gives 358 server keys plus one world carrying all 13 `WorldConfig` keys.

`DB_CONNECTION_TIMEOUT_MS` must stay **absent** on `163-integration`: the field does not exist there (`grep` exits 1;
0 occurrences on `163-integration` and on `164-configtemplate`, 1 on `release-1.6.4`, where it arrived in `1225800eb`,
which is not an ancestor of `163-integration`). Adding the key there would fail
`ConfigTemplateCompletenessTest.everyServerFieldAppearsInTheTemplate`, which reports an extra key as "not a
ServerConfig field (typo, or a removed field)". `163-integration` hard-codes the value instead:
`src/main/java/tools/DatabaseConnection.java:137` `config.setConnectionTimeout(SECONDS.toMillis(10));` — so on that
branch the key has nowhere to go. Both branches are consistent; they are just different.

The hazard is therefore only in the **forward merge `163-integration` → `release-1.6.4`**:

- `release-1.6.4` has `DB_CONNECTION_TIMEOUT_MS` as a real field with an inline default (359 fields, not 358:
  `ServerConfig.java:18` `public int DB_CONNECTION_TIMEOUT_MS = 30000;`, read through `connectionTimeoutMs()` at
  `src/main/java/tools/DatabaseConnection.java:201-203` and applied at `:220`), and its own `config.example.yaml:8-9`
  carries the key and the comment.
- `163-integration`'s template lists none of it, so a wholesale take of that file overwrites a 9-line template that had
  the key and the key is **lost**.
- `ConfigTemplateCompletenessTest` then makes the merge self-correcting, but only if someone runs it: on `release-1.6.4`
  the test sees 359 fields and a 358-key template and fails until the key is back. Restore it as
  `DB_CONNECTION_TIMEOUT_MS: 30000`, with the existing wording at `config.example.yaml:8-9` ("How long a thread waits for
  a pooled connection before its work fails (ms). 0 = 30000 (Hikari's default).").
- Cheapest confirmation afterwards: `grep -n DB_CONNECTION_TIMEOUT_MS config.example.yaml` returns one line, and
  `ConfigTemplateCompletenessTest` is 4/4 green against the merged template with 359/359 keys.

Do not "fix" this twice in opposite directions: the key is wrong on `163-integration` and required on `release-1.6.4`,
because the two branches default the same timeout in two different places (field versus hard-coded `SECONDS.toMillis(10)`
in `DatabaseConnection.java:137`).

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

Met on `163-integration` at `1c168eff9` (358/358 keys, test 4/4 green, suite `10483 / 0 / 0 / 146`):

- `config.example.yaml` lists every `ServerConfig` and `WorldConfig` field and contains a bootable `worlds:` entry.
- `ConfigTemplateCompletenessTest` fails against the shipped template and passes against the new one.
- The 13 fields above are non-zero in the template and match their code defaults.

Still open, and the only reason this ticket is not closed:

- `DB_CONNECTION_TIMEOUT_MS` survives the **forward merge** into `release-1.6.4` — the key is absent on
  `163-integration` by design and must be restored there (value `30000`, template line `config.example.yaml:8-9`'s
  wording), with `ConfigTemplateCompletenessTest` green at 359/359 on the merged tree.

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

- **Card:** Config | the shipped config template omits 353 of 359 keys and every world, so a fresh install boots into 0-valued boot-critical fields | S2 | lane Server | closer: merged into `163-integration` at `1c168eff9` (`fc17b56c4` + `3e451ef3a`; suite `10483 / 0 / 0 / 146`, delta +4 = `ConfigTemplateCompletenessTest` 4/4, fail-before 4/4 with `IllegalStateException: config.yaml has no 'worlds:' entries`). Remaining proof for the card: the forward merge into `release-1.6.4` keeps `DB_CONNECTION_TIMEOUT_MS: 30000` (see "Merge hazard") and `ConfigTemplateCompletenessTest` is green there at 359/359.
- **Patch notes (draft, player words):** "A fresh install now starts with a complete configuration, so a new server no longer dies on the first player's map entry or loses every drop." (staff-facing install note; it is not a player-visible change — use "staff only, no line" if the book prefers that)
- **Confign line:** none — the template is a file, not a switch; nothing in `docs/release/1.6.4.conf` changes and `CONFIG_APPEND` stays empty.
- **Test-tab row:** test: `ConfigTemplateCompletenessTest` | how: copy `config.example.yaml` to `config.yaml` and boot a clean server; a player logs in and changes map without a stack trace, and drops do not vanish on the next sweep.
