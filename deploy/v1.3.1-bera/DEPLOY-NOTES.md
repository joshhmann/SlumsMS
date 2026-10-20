# SlumsMS 1.3.1 (ticket 41): live world becomes Bera

Prepared 2026-10-20, **not run**. Live edits need the owner's go. Design and rehearsal evidence:
`docs/tickets/41-world-name-bera.md` (branch `world-bera`).

## Run

```
bash /opt/mapleroot-deployments/v1.3.1-bera/deploy.sh
```

Pick a quiet moment (the script prints players online before it stops anything). Expected downtime about 30 s:
stop with ~1,100 bots ~22 s, backup ~3 s, migration < 1 s, staging < 1 s, start to online ~5 s.

## What it changes

| What | Before | After |
|---|---|---|
| Jar | 1.3.0 (`91d7450b...`) | 1.3.1 (hash in `staging/SHA256SUMS`) |
| `config.yaml` above `server:` | 21 world entries, Scania first, `WORLDS: 1` | one entry `id: 1` (Bera), Scania's rates, 8 channels |
| DB | world 0 | migration `008-world-bera`: characters, storages, playernpcs, playernpcs_field, plife, worldtransfers 0 -> 1 |
| scripts, `src/main` data | 1.3.0 | same files, restaged |
| Unit, flags, ports, template id | 8485, 7585-7592, `template-id=6` | unchanged |

The script aborts **before stopping live** if: a staged file's hash differs, the config's worlds block is not the
one this staging was made from (`9428c6c04531e04b`), the ledger is not exactly 001,002,003,005,006,007, any character
is outside world 0, or a world transfer is pending. After the stop it aborts (and says to run `rollback.sh`) if the
ledger or world counts are wrong after 008.

## Smoke test (printed by the script)

- `SlumsMS v1.3.1 (MapleStory v83) starting up.`
- `Starting world 1 (Bera)`, `Channel 1: Listening on port 7585` ... `Channel 8: ... 7592`, `Finished loading world 1`
- `Cosmic is now online`; then 0 `Starting world 0` lines and 0 ERROR/Exception lines
- `characters by world 1 4` (or more if players made characters)

In game (owner): the login screen lists only Bera, with Bera's button/icon; josh and jimbo's characters are there
with their items; bots in Henesys and the FM; FM shops open; channel change works. Live has no GM account yet: make
one after the deploy (new characters are created in Bera).

## Rollback

```
bash /opt/mapleroot-deployments/v1.3.1-bera/rollback.sh
```

Stops, runs `008-world-bera.rollback.sql` (world 1 -> 0, ledger entry removed), restores the 1.3.0 jar, config.yaml,
scripts and `src/main` from `backup/server-runtime.tgz`, starts. Keeps player progress. The 1.3.0 jar stops on the
`id:` key, so never put the old jar back without the old config. Full restore instead: `backup/mapleroot.sql.gz`
(loses progress since the deploy).

## Files

- `deploy.sh`, `rollback.sh` (also in the repo under `deploy/v1.3.1-bera/`)
- `staging/`: `Cosmic.jar`, `008-world-bera.sql`, `008-world-bera.rollback.sql`, `scripts.tar`, `srcdata.tar`,
  `config-worlds-bera.yaml`, `COMMIT`, `SHA256SUMS`
- `backup/`, `deploy.log` appear when the script runs.
