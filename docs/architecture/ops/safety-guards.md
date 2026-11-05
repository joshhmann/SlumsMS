# Safety guards

## Purpose

The checks that stand between an operator or an agent and damage: the strict config loader, the gates that run before
anything is staged, the rehearsal that has to PASS, the allowlists that keep unintended files off the patch feed, and
the path/host refusals in the TEST tools. Some are code with a cited line; some exist only as prose in `AGENTS.md`, and
the difference matters — a prose rule is advice, not a check.

## Entry points

| Command / unit | Arguments | Runs where |
|---|---|---|
| `tools/release/build` | `[--skip-tests] [--allow-dirty] [--dry-run]` | workstation (`tools/release/build:4,17`) |
| `tools/release/stage` | `<version> [--conf FILE] [--replace] [--allow-version-mismatch] [--allow-skipped-tests] [--dry-run]` | workstation (`tools/release/stage:4,24`) |
| `tools/release/rehearse` | `<version> [--source live\|nightly\|<file>] [--soak N] [--keep] [--dry-run] [--login-port P] [--channel-base P]` | workstation, then 164 (`tools/release/rehearse:12-13`) |
| `tools/release/hotfix-parity` | `<live-branch> [release-ref]` | any checkout (`tools/release/hotfix-parity:7-8`) |
| `bash <deploy-root>/v<ver>/deploy.sh` | `--dry-run`, `--phase stop-backup\|apply-start\|smoke\|restart-old` | 164 (`tools/release/templates/deploy.body.sh:24`) |
| `bash <deploy-root>/v<ver>/release-live.sh` | `--check` \| `--go` | 164 (`tools/release/release-live.sh:2-5,22`) |
| `tools/release/release` | `<version> [--dry-run] [--yes] [--no-packs] [--no-client]` | workstation (`tools/release/release:52,57,157-159`) |
| `tools/release/publish-client` | `<client-version> --source <host dir> [--dry-run]` | workstation (`tools/release/publish-client:5-6`) |
| `tools/ops/deploy-test` | `[--feature] [--skip-build] [--dry-run] [--since C] [--no-migrations]` | workstation → TEST (`tools/ops/deploy-test:5`) |
| `deploy/slumsms.service.example` | template for a *new* server unit | copied by hand (`README.md:28`) |

## How it works

"Does not catch" is the honest half of every row.

| Guard | Prevents | Enforced at | Does not catch |
|---|---|---|---|
| Strict YAML load | booting a build that does not know a key | `src/main/java/config/YamlConfig.java:22-29` | a *missing* key (JVM default), or a `-D` override |
| Template completeness test | a new field with no documented value | `src/test/java/config/ConfigTemplateCompletenessTest.java:68-74,76-87` | a field declared but never read, or a wrong value |
| Dirty tree + green suite | a jar that matches no commit, or a red release | `tools/release/build:24,42-43` | tests that pass and do not cover the change |
| Stage mismatch set | staging a stale jar, hash or version | `tools/release/stage:31-46` | staging from the wrong host, or another branch |
| `SERVER_SET` type check | an unknown or wrongly typed key reaching live's config | `tools/release/gen-deploy:60-70` | `CONFIG_APPEND` keys, which are never type-checked |
| `routing-required.txt` | losing a map route, or swapping the routing table | `tools/release/gen-deploy:48-51`, `templates/deploy.body.sh:289-296` | a server started by hand without the smoke test |
| Rehearsal PASS triple | shipping a deploy/rollback that loses player rows | `tools/release/rehearse-host.sh:232,274` | nothing forces anyone to run `rehearse` |
| `REHEARSAL.md` grep | releasing with no rehearsal receipt | `tools/release/release-live.sh:26-27` | a FAIL rehearsal whose smoke line says PASS (below) |
| TTY + typed `yes` | an unattended release from an agent | `tools/release/release:157-159` | `release-live.sh` and `deploy.sh` have no such gate |
| `down()` recovery text | leaving the server down with no next step | `templates/deploy.body.sh:56-60` | an operator who ignores the printed command |
| `apply-start` single-shot | re-applying over a half-applied backup | `templates/deploy.body.sh:107-109` | edits made outside the script |
| Backup never overwritten, >4096 MB free | destroying the only rollback point | `templates/deploy.body.sh:158,161` | other files filling the disk (card b299) |
| Dump `Dump completed` + tar listable | restoring from a truncated backup | `templates/deploy.body.sh:196,201` | corruption after the check passes |
| Smoke `EXPECT`/`FORBID`/`ERROR` | a jar that boots but logs errors | `templates/deploy.body.sh:280-283` | errors that appear after the smoke window |
| Migration `.attempted` marker | guessing a half-applied migration | `templates/rollback.body.sh:66` | SQL run by hand |
| `client-allow.txt` hash-diff | unexpected or dropped client files | `tools/release/publish-client:103,120`; `verify-client.py:203,233` | any difference listed in the allowlist |
| Live endpoint check | sending live players to a test server | `tools/release/verify-client.py:129-150` | an RC layer promoted outside the tools |
| `testhost.py` allowlist + port | QA tools reaching live | `tools/ops/client-mcp-win/src/testhost.py:56-67` | 164 paths, and tools that never source `lib.sh` |
| `deploy-test` path/unit gate | deploying this checkout over live | `tools/ops/deploy-test:42,88-90` | a live path spelled differently from the two patterns |
| `deploy-test` boot-error gate | leaving a broken TEST build running | `tools/ops/deploy-test:120-123` | `ERROR` lines logged later |
| Restore-drill schema/DSN | restoring over the live database | `deploy/backup/slumsms-restore-drill:137,225-226` | — |
| Unit template `Restart`/`UMask` | world-readable data, a dead server staying dead | `deploy/slumsms.service.example:12-15` | live's real unit is not this file (`HOLES.md:62`) |
| Channel key rule (prose) | a dev-key client on the live feed | `AGENTS.md:199-201` | nothing in the repo enforces it (below) |

**Config strictness in detail.** The loader is `YamlConfig.loadConfig`, a plain
`new YamlReader(Files.newBufferedReader(Path.of("config.yaml")))` plus `reader.read(YamlConfig.class)`
(`src/main/java/config/YamlConfig.java:14,22-23`) — no `ignoreUnknownProperties` anywhere in `src/main/java/config`.
yamlbeans 1.17 (`pom.xml:66,77-79`) throws `YamlReader$YamlReaderException` ("Unable to find property …"); that extends
`IOException`, so it lands in the catch that rethrows
`RuntimeException("Could not successfully parse config file config.yaml: …")` (`YamlConfig.java:28-29`). The static
field (`:15`) initializes on first use, which is `Server.main` (`src/main/java/net/server/Server.java:1156-1158`), so the
boot dies rather than starting without the setting. A *missing* key is the opposite case: the field keeps its JVM
default, `0` or `false`, unless it has an inline initialiser (`config.example.yaml:5-13`). A new key is therefore
introduced in one of three ways: (a) a `ServerConfig` field with an inline default — `DB_CONNECTION_TIMEOUT_MS = 30000`
(`ServerConfig.java:18`), `DAILY_CHECKIN_ENABLED = false` (`:46`), `STARFORCE_BOOST_PERCENTAGE2 = 0.2` (`:604`); (b) a
`-D` system property, the choice documented at `src/main/java/soloMapling/ArtificialPlayer/BotYieldCaps.java:45-46`
("the server's YAML is strict and an unknown key stops the boot"); (c) `SERVER_SET`/`CONFIG_APPEND` **only after** the
jar that knows the key is deployed (`gen-deploy:64-65`, dup refused at `deploy.body.sh:133-137`). Never the config file
first — `AGENTS.md:110-112`.

**The live path, step by step.**

1. **Host gate.** `require_workstation` dies when `hostname` is `maplestory` (`tools/release/lib.sh:24-26`); the
   rehearsal calls `require_host` (`tools/release/rehearse-host.sh:31`); `die` exits 1 (`lib.sh:13`).
2. **Manifest gate.** `gen-deploy` refuses duplicate `SERVER_SET` keys (`:62`), reserved
   `CHANNEL_LOAD|MIN_CLIENT_BUILD|CHANNELS` (`:63`), type mismatches (`:66-69`) and a key already in `CONFIG_APPEND`
   (`:70`); an unknown key dies "the strict config would refuse it" (`:64-65`); output is checked with `bash -n`
   (`:96-98`).
3. **Rehearsal.** On 164 it refuses to run unless the version is staged and matches `SHA256SUMS` (`:43-44`), the
   scratch schema/unit/ports are free (`:46-50`), the login port is not `8485|8489|8479` (`:52`) and no `$R/Server` is
   left over (`:55`). It reads live with `mysqldump --single-transaction` (`:89`), rewrites the copy's ports and every
   live dir through `rehearsal_cmd` (`tools/release/lib.sh:32-44`) and blanks `MONITOR` so no maintenance file or public
   post can appear (`:159`). PASS needs all three: `deploy_rc = 0`, player rows identical before vs after rollback, and
   a PASS receipt (`:232`); the exit code follows `overall` (`:274`).
4. **Live start.** `release-live.sh` needs `deploy.sh`, `rollback.sh`, a `REHEARSAL.md` PASS, `slumsms` active and
   >5 GB free (`:26-32`). `tools/release/release` refuses `--inject` outside a rehearsal env (`:63`), `--host-env` with
   the live feed (`:65`) and re-checks the staged scripts (`:110-113`).
5. **On the host.** `deploy.sh` pre-checks while the server is still up, then stop → backup → apply → start → smoke →
   receipt; order and phase meanings are stated at `templates/deploy.body.sh:3-17`.
6. **Client publish.** `publish-client` copies `tools/release/client-allow.txt` to the host (`:103`) and runs
   `verify-client.py --allow … --previous manifest.json`; a mismatch dies "hash-diff or live endpoint check failed:
   nothing published" (`:120`). On the live feed it also compares `config.ini` host/port with the live layer
   (`verify-client.py:129-150`), overridable only by `ALLOW_LIVE_ENDPOINT_CHANGE=1` (`publish-client:106-113`).
7. **TEST tools.** Every tool that sources `tools/ops/lib.sh` resolves its host through `testhost.py`
   (`tools/ops/lib.sh:12-15`); a value outside the allowlist raises `Refused` before any ssh (`testhost.py:56-58`).

## Data

| Path | Written by | When |
|---|---|---|
| `/opt/mapleroot-deployments/v<ver>/` (`DEPLOY_ROOT`, `lib.sh:5`) | `stage` | each stage |
| `<deploy-root>/v<ver>/REHEARSAL.md`, `DEPLOY-RECEIPT.md`, `ROLLBACK-RECEIPT.md` | `rehearse-host.sh:271`, deploy/rollback bodies | each run |
| `$B/mapleroot.sql.gz`, `$B/slumsms.service`, markers `.stop-backup.done`, `.apply-start.started`, `.migration-<id>.attempted` | `deploy.sh` | once per release |
| `/opt/mapleroot-dev/rehearsal-<ver>/receipt/` (`state-before.tsv`, `state-after-*.tsv`, `*.out`) | `rehearse-host.sh:162-236` | each rehearsal |
| `/opt/mapleroot-dev/persistence-test/deploys/<stamp>/` (`Cosmic.jar.before`, `data.before.tgz`, `db-before.sql.gz`) | `deploy-test:98-106` | each TEST deploy |
| `tools/release/routing-required.txt`, `client-allow.txt`, `hotfix-ported.txt` | hand-edited | per change, in the diff |

Secret material, by path only: `/root/slums-patch-access-key.txt`, the dev key, `/root/.config/slumsms-launcher/tauri-signing.key`, `/root/.config/slumsms/cloud-llm.env`, `/etc/slums-persistence-test/cloud-llm.env`, `/etc/slumsms-monitor/live.env`, `/etc/nginx/slums-patch*.htpasswd` (`AGENTS.md:28-33`).

## Switches

| Switch | Home | Default | Effect |
|---|---|---|---|
| `SLUMS_TEST_HOST` | `testhost.py:21` | `192.168.0.111` (`:24`) | which TEST box the tools reach; anything else is refused (`:56-58`) |
| `RELEASE_HOST` | `tools/release/lib.sh:4` | `root@192.168.0.164` | stage/rehearse/publish target — **live by default** |
| `DEPLOY_ROOT` | `tools/release/lib.sh:5` | `/opt/mapleroot-deployments` | where versions are staged |
| `PATCH_DIR` / `LIVE_PATCH_DIR` | `publish-lib.sh:2`, `publish-client:108` | `/srv/slums-patch` | which feed is written; equal values switch the live checks on |
| `ALLOW_LIVE_ENDPOINT_CHANGE` | `publish-client:110-112` | unset | permits a deliberate live endpoint move |
| `MONITOR` | `rehearse-host.sh:159` | live binary | blanked in rehearsal so no public post happens |
| `--login-port`, `--channel-base` | `rehearse-host.sh:26` | `8499`, `8585` | rehearsal ports |

## Invariants

- Unknown keys abort the boot, missing keys do not (`YamlConfig.java:22-29` vs `config.example.yaml:5-13`); the only
  lenient read is the charset bootstrap, which sets `setIgnoreUnknownProperties(true)`
  (`src/main/java/constants/string/CharsetConstants.java:66`) and so cannot pre-check a typo (`HOLES.md:178`).
- `config.example.yaml` must list every field or `ConfigTemplateCompletenessTest` fails
  (`:68-74` server, `:76-87` worlds), because a missing key is silently `0` — measured breaks at `MapleMap.java:990`
  (`ITEM_EXPIRE_CHECK`), `:1025,5512` (`ITEM_LIMIT_ON_MAP`), `Character.java:2112-2113` (`MAP_VISITED_SIZE`) and
  `MonsterAggroCoordinator.java:93,98` (`MOB_STATUS_AGGRO_INTERVAL`).
- A rehearsal may not take a fixed login port (`rehearse-host.sh:52`); a TEST tool may not name a live path
  (`deploy-test:42`); a restore may not use schema `mapleroot` or a DSN on `:3309`
  (`slumsms-restore-drill:137,225-226`).
- A live client layer must point at the live server (`verify-client.py:129-150`, `AGENTS.md:145`); the only escape is
  `ALLOW_LIVE_ENDPOINT_CHANGE=1` plus `--allow-endpoint-change` (`publish-client:110-112`).
- `routing-required.txt:4` — "Remove a line only when the client fix for that map is live": the list is a promise about
  the client, not about the server, so deleting a line early re-opens b251 for live players.
- 164 is **inside** the TEST allowlist (`testhost.py:23`; docstring `:4-5` "The played TEST stays on 164 until the
  cutover"), so the host guard alone does not keep a tool off live — the path check is the second stop.

## Origin

| File | First commit |
|---|---|
| `src/main/java/config/YamlConfig.java`, `deploy/slumsms.service.example` | `03d13cb21` (baseline) |
| `tools/release/{rehearse,rehearse-host.sh,gen-deploy,templates/deploy.body.sh,verify-client.py,client-allow.txt}` | `9d437b372` |
| `tools/release/routing-required.txt` | `3482b808c` |
| `tools/release/hotfix-parity` | `5804ad9f8` |
| `tools/release/release-live.sh` | `758ff4a76` |
| `tools/ops/deploy-test`, `AGENTS.md` | `383b0df55` |
| `tools/ops/lib.sh` | `243fd5591` |
| `tools/ops/client-mcp-win/src/testhost.py` | `ad8a48f58` |

## Tests and QA

| Test | Covers | Does not cover |
|---|---|---|
| `tools/release/test_rehearsal_cmd.py:1-18` | `rehearsal_cmd` moves login 8485, channel 7585 and every live dir off live | `rehearse-host.sh` itself — nothing starts it |
| `tools/release/test_release_config.py:186` | `SERVER_SET` must name a real `ServerConfig` field | key introduction via `CONFIG_APPEND` |
| `tools/release/test_release_recovery.py:41-53` | migration/ledger behaviour on SQLite stubs | the deploy bodies' shell guards |
| `tools/release/test_publish_client_rerun.py` | rerun/reuse of an already-published layer | `client-allow.txt` content |
| `tools/ops/test_testhost.sh:33-34` | refusals for `mapletest`, `slumsms.asslorde.com`, `10.0.0.9`, empty | a live *path* reached through an allowed host |
| `tools/ops/client-mcp-win/tests/test_qatools.py:259-276` | another box and port 8485 refused | — |
| `src/test/java/config/StarForceBoostDefaultTest.java:8-13` | a documented default is what a missing key yields | whether live's config has the key (card b296) |

Nothing tests `tools/release/hotfix-parity`, the content of `routing-required.txt` / `client-allow.txt`, or any
`deploy.body.sh` / `rollback.body.sh` guard.

## Known issues

- b296 — `STARFORCE_BOOST_PERCENTAGE2` is absent from the production config; the code default (0.2,
  `ServerConfig.java:604`) applies silently (`/root/work/STATUS-1.6.4.md:46`).
- b251 — Rose Garden 211080100 stays blocked in the live routing table until the client fix ships, which is why the id
  is pinned in `tools/release/routing-required.txt:5`.
- b299 — 164's root disk reached 95 %; the free-space gates (`deploy.body.sh:161`, `release-live.sh:32`) are the only
  automated protection against a backup filling it.
- NEW-FINDING (prose only): the channel key rule — "Live `/srv/slums-patch/` (player key), Test `/launcher-test/` and
  RC `/launcher-rc/` (dev key only)" — has no enforcement in the repo. `AGENTS.md:199-201` and
  `docs/architecture/launcher/channels-and-auth.md:88-93` are the whole guard; no script in `tools/release/` reads an
  htpasswd file or records which credential a publish used.
- NEW-FINDING (prose only): `hotfix-parity` is invoked by nothing. `AGENTS.md:244` and `docs/release/RUNBOOK.md:27`
  say to run it before staging; `stage` and `release` never call it, so a release missing a live hotfix stages and
  rehearses clean — exactly the failure it was written for (`hotfix-parity:3`, the 1.6.0 / 1.5.3 x5 job fees).
- NEW-FINDING (gate that cannot match): "rehearse must PASS" exists as runbook prose (`RUNBOOK.md:30`,
  `AGENTS.md:142`) and as `grep -q "rehearsal PASS\|smoke PASS"` in `release-live.sh:27`, but the receipt writes
  `**Result: PASS**` (`rehearse-host.sh:236`) and `smoke $deploy_result` (`:262`) — only the smoke substring can match,
  so a FAIL rehearsal passes this gate whenever `deploy.sh`'s own smoke said PASS.
- NEW-FINDING (stale guard): the port blacklist lost an entry. `deploy/v1.4.1/rehearse.sh:44` guards
  `8485|8489|8479|8509`; `tools/release/rehearse-host.sh:52` guards only `8485|8489|8479`, while the soak box uses
  login 8509 (`tools/soak/README.md:12`). Only the `ss -ltn` busy check (`:50`) stops a collision, and it is empty
  when the soak server is down.

## State

- **Working (code, from the lines cited above):** build/stage refusals, `SERVER_SET` type check, routing allowlist at
  generate and at smoke, the rehearsal PASS triple and its exit code, `down()` recovery text, backup-never-overwrite,
  `apply-start` single-shot, `client-allow.txt` hash-diff, the live endpoint check, `testhost.py` host/port refusal,
  `deploy-test`'s path refusal and boot-error gate, `ConfigTemplateCompletenessTest`.
- **Partial:** config strictness is real only for *unknown* keys — missing keys stay at JVM defaults by design
  (`config.example.yaml:5-13`), so the guard is "keep the template complete", not "the loader catches it".
- **Weak as written:** the `REHEARSAL.md` grep (`release-live.sh:27`) matches only the `smoke PASS` substring of the
  `Checks` line, never the `**Result: PASS**` line the runbook means, and the `rehearse-host.sh:52` port list.
- **Prose only:** the channel key split, `hotfix-parity` invocation, "the owner runs `deploy.sh`" (`AGENTS.md:140`),
  and the secret rules (`AGENTS.md:28-33`).

### Can an agent reach live by accident?

What blocks it, in the order an agent meets it: (1) `require_workstation` / `require_host` keep the release tools off
164 (`lib.sh:24-26`); (2) `stage` writes to `/opt/mapleroot-deployments`, never to `/opt/mapleroot/…/Server`;
(3) `release-live.sh` needs a `REHEARSAL.md` PASS and an active `slumsms` (`:26-28`); (4) `tools/release/release` needs
a TTY and the word `yes` (`:157-159`); (5) the QA/ops tools go through `testhost.py` (`testhost.py:56-67`);
(6) writing the Live feed needs a credential that is not in the repo (path only: `/root/slums-patch-access-key.txt`),
so a publish cannot succeed without borrowing it.

Where nothing blocks it: `deploy.sh` and `rollback.sh` are plain bash in a version folder with **no** hostname check
and **no** confirmation — `grep` finds no `[ -t 0 ]` and no `read -r -p` in `deploy.body.sh`; `release-live.sh` has
neither (`:13` is only `set -uo pipefail`), so root on 164 can go from `--go` to `--phase stop-backup` unattended.
`RELEASE_HOST` defaults to live (`lib.sh:4`), `PATCH_DIR` can be pointed at the live feed from any shell with
`ALLOW_LIVE_ENDPOINT_CHANGE` in the environment, and 164 is itself an allowed TEST host (`testhost.py:23`). The
protection against a careless agent is therefore convention: the documented commands start on the workstation. Nothing
checks the intent of a command typed *on* 164.

## Open questions

- Is the dropped `8509` in `rehearse-host.sh:52` deliberate (soak retired) or drift from the 1.4.x copy? Two documents
  disagree on the authoritative list: `docs/architecture/ops/hosts.md:123` quotes the 1.4.1 guard with 8509 included,
  while the shipped tool omits it.
- `AGENTS.md:142` and `RUNBOOK.md:30` say a rehearsal must PASS, but the machine gate cannot match the receipt text
  (`release-live.sh:27` vs `rehearse-host.sh:236`). Which side changes — the grep or the receipt wording?
- `ConfigTemplateCompletenessTest`'s javadoc (`:26-34`) and `config.example.yaml:5-7` both call the loader one with "no
  strictness", while `AGENTS.md:110` says an unknown key aborts boot. Both are true of different cases (missing vs
  unknown key) and no single sentence states both, so a reader of either file alone gets half the rule.
- Is `hotfix-parity` run in practice under time pressure? Nothing records it: no receipt field, no timestamp check on
  `hotfix-ported.txt`.
