# Backups, rotation, off-box copy, restore drill (ticket 45)

Everything here runs on 164 as root. Installing it on live is the owner's step (`install.sh`, below); nothing in this
folder stops, restarts or reconfigures the game server or its database.

| File | Installed as | What |
|---|---|---|
| `slumsms-nightly-backup` | `/usr/local/bin/` | v2 of the nightly job: DB dump (as v1) + `bot-nav-<ts>.tar.gz` of `cache/bot-nav/{learned,stuck,heat,observed,players}`, both in `SHA256SUMS`, 14 days kept (never fewer than the newest 3), optional encrypted off-box push |
| `slumsms-rotate-backups` | `/usr/local/bin/` | Keeps the newest 3 deploys' pre-deploy material, prunes test-server `backup-*` / `Cosmic.jar.pre-*` older than 14 days. Dry run by default |
| `slumsms-restore-drill` | `/usr/local/bin/` | Newest nightly → scratch schema on the test DB → copy of live booted on spare ports → checks → cleanup |
| `move-test-db-datadir.sh` | (run by hand in a window) | Moves the test DB datadir to `/opt/mapleroot-dev/integration-db/mysql` |
| `slumsms-backup.{service,timer}` | `/etc/systemd/system/` | Same schedule as before (11:00 UTC) |
| `slumsms-backup-rotate.{service,timer}` | `/etc/systemd/system/` | Daily 11:30 UTC |
| `slumsms-restore-drill.{service,timer}` | `/etc/systemd/system/` | Monthly, 1st at 12:00 UTC (enabled only with `install.sh --with-drill`) |
| `slumsms-backup.conf.example` | `/etc/slumsms-backup.conf` | All settings; defaults are live's |

## Install (owner)

Copy this folder to 164 and run, as root:

```
bash /opt/mapleroot-dev/t45-backup-kit/install.sh            # add --with-drill for the monthly drill timer
```

It saves the old script and units to `/var/lib/slumsms-backup/prev-<ts>/` first (`install.sh --undo <that dir>` puts
them back), creates `/etc/slumsms-backup.conf` and the off-box key if missing, enables the rotation timer in dry-run
mode and prints today's rotation list. Add `--backup-now` to run the new nightly once immediately.

## Nightly backup v2

```
/opt/mapleroot/backups/nightly/
  mapleroot-<ts>.sql.gz      mysqldump --lock-all-tables --routines --triggers --databases mapleroot
  bot-nav-<ts>.tar.gz        ./learned ./stuck ./heat ./observed ./players from the live server's cache/bot-nav
                             (~70 KB compressed before players/ existed; players/ is capped at 64 MB by the server)
  SHA256SUMS                 both files; lines of pruned files are dropped
```

The graph caches (`v68`, `deep`) are not backed up: they are rebuilt from wz. Learned books are not written
atomically by the server, so the script copies the folders first and re-copies any `.json` that does not parse.

Restore by hand: `zcat mapleroot-<ts>.sql.gz | mysql …` (it recreates schema `mapleroot`; check the migration ledger
against the jar) and `tar -C "<Server>/cache/bot-nav" -xzf bot-nav-<ts>.tar.gz` with the server stopped.
`slumsms-restore-drill` shows the whole sequence against a scratch schema.

## Rotation

Roots and rules (`slumsms-rotate-backups`, `KEEP_DEPLOYS=3`, `TEST_KEEP_DAYS=14`):

- `/opt/mapleroot/backups/pre-*`: newest 3 by the UTC stamp in the name are kept, older ones deleted whole.
- `/opt/mapleroot-deployments/*`: only finished deploys count (a folder with `deploy.log`, `DEPLOYMENT.md`,
  `deployment.json` or `backup/`; staged or dry-run release folders are left alone). The newest 3 (by newest file
  inside) are kept; in older ones only `backup/`,
  `staging/`, `*.bundle`, `*.tgz`, `*.tar*`, `*.sql*`, `*.jar` go. DEPLOYMENT.md, logs and
  scripts stay as the record. `PreviousServer/` is protected (below) and stays.
- `/opt/mapleroot-dev/*/` (depth ≤ 4): `backup-*` and `Cosmic.jar.pre-*` older than 14 days; never under a `mysql/`
  datadir or `integration-db/`.
- Never: `backups/nightly`, the other files in `backups/`, the live server folder.
- Protected paths (`PROTECT_PATHS`, default `PreviousServer`): never listed as candidates and refused again at delete
  time, and neither is a folder that contains one (e.g. a `backup/` holding a `PreviousServer/`). A bare name matches
  that name anywhere under the roots; an entry with a `/` is a glob on the full path, e.g.
  `PROTECT_PATHS="PreviousServer /opt/mapleroot-deployments/baseline-*/staging"`. Setting it in the conf replaces the
  default, so keep `PreviousServer` in the list. The owner decided on 2026-10-20 to keep
  `mapleroot-deployments/baseline-20261018-r1/PreviousServer` (541 MB). Each run prints the protected items with their
  size. `bash test-rotate-protect.sh` checks this in a temp folder (dry run and `--apply`).

Safety: every run prints the list and records when each item was first listed (`/var/lib/slumsms-backup/rotate-plan.tsv`).
`--apply` (or `ROTATE_MODE=apply`) deletes only items an earlier run listed at least `GRACE_HOURS` (20) ago, never an
item on the run that first lists it. Read the list with `journalctl -u slumsms-backup-rotate`.

## Off-box copy: what the owner must provide

The push is built and tested (to a local folder) but disabled until `OFFBOX_DEST` is set. Each night it encrypts the
dump and the nav archive with `gpg --symmetric --cipher-algo AES256` using the passphrase in
`/root/.config/slumsms-backup/offbox.key` (created by `install.sh`, mode 600, exists only on 164) and copies
`*.gpg` plus `SHA256SUMS-<ts>` (plaintext and ciphertext hashes) to the destination. A failed push leaves the local
backup and makes the unit exit 3 (shows as failed in `systemctl --failed`).

To turn it on the owner provides:

1. **A target host and folder** reachable over SSH from 164 (a NAS, another box, a VPS, or the desktop with Windows
   OpenSSH Server). ~2 MB per night today; 14 nights ≈ 30 MB, growing with the world.
2. **An SSH login for 164 on it**: a dedicated user with write access to that folder only. Create a key on 164
   (`ssh-keygen -t ed25519 -N '' -f /root/.ssh/slumsms_offbox`), add the `.pub` to that user's authorized_keys,
   accept the host key once (`ssh -i /root/.ssh/slumsms_offbox user@host true`).
3. **Retention on the far side** (the push only adds files): e.g. a cron/Task Scheduler job deleting `*.gpg` older
   than 30 days. Note rsync with `--partial-dir` needs rsync on the far end; for Windows set `OFFBOX_METHOD=scp`.
4. **A copy of the key off this host.** `cat /root/.config/slumsms-backup/offbox.key` → password manager. If 164 is
   lost, the off-box copies are useless without it.

Then in `/etc/slumsms-backup.conf`:

```
OFFBOX_DEST=user@host:/path/to/slumsms-backups
OFFBOX_METHOD=rsync      # or scp
OFFBOX_SSH_OPTS="-o BatchMode=yes -o ConnectTimeout=20 -i /root/.ssh/slumsms_offbox"
```

and test with `systemctl start slumsms-backup.service; journalctl -u slumsms-backup -n 20`.

Decrypt anywhere with gpg: `gpg --batch --pinentry-mode loopback --passphrase-file offbox.key -d -o x.sql.gz x.sql.gz.gpg`.
The drill takes the encrypted files directly: `slumsms-restore-drill --dump …sql.gz.gpg --nav …tar.gz.gpg`
(copy them back to 164 first; it checks them against `SHA256SUMS-<ts>` next to them).

## Restore drill

`slumsms-restore-drill [--dump FILE] [--nav FILE | --nav-from-live] [--minutes N] [--keep]`

1. Newest `mapleroot-*.sql.gz` and `bot-nav-*.tar.gz` in the nightly folder (or the given files, `.gpg` decrypted
   with the off-box key), checked against SHA256SUMS, gzip and "Dump completed".
2. Restored into `mapleroot_drill_<date>` on the test DB (127.0.0.1:3307) with the dump's `CREATE DATABASE`/`USE`
   lines removed (so nothing can land in another schema), a throwaway DB user, then the migrations live's ledger has
   and the dump lacks (from `/opt/mapleroot-deployments/*/staging/`; live's ledger is read with one SELECT).
3. Live `Server/` copied to `/opt/mapleroot-dev/restore-drill/run-<ts>/` (no wz/logs/.git/BotLog; wz is a symlink and
   the units see `/opt/mapleroot` and `/opt/mapleroot-deployments` read-only), nav archive unpacked into it,
   config.yaml pointed at the scratch schema, bot LLM URLs blanked.
4. The live jar's `NavStuckReport` reads the restored learned/stuck files; then the copy boots with live's own java
   flags on login 8539 / channels 8805-8812 as `slumsms-restore-drill` (`CPUQuota=200% Nice=19 MemoryMax=6G`), the
   login handshake is checked (v83), it runs 7 minutes, and stops gracefully.
5. Checks: world/SoloMapling/bot store/roster/director lines, roster count = restored `botroster` rows, 0
   ERROR/Exception, player characters/items/skills/storages identical before and after, and every learned book the
   server rewrote still carries the restored edges (it loaded them rather than starting over).
6. Drops the schema and user, deletes the run folder. Receipt kept in `/opt/mapleroot-dev/restore-drill/receipts/<ts>/`
   (`drill.log`, `RESULT`, `state-before/after.tsv`, `server.log`, `learned-check.txt`, `navreport/`).

Exit 0 = PASS, 1 = a check failed, 2 = could not run.

## Moving the test DB datadir (needs a window)

Why: `/opt/mapleroot-dev/solomapling-test/mysql` looks like an old test copy but holds every test schema; one
"clean up old test folders" and they are gone. Target: `/opt/mapleroot-dev/integration-db/mysql` (same ZFS dataset,
so the move is a rename). Secrets stay in `solomapling-test/secrets` (scripts reference `root.cnf` there).

Plan:

1. `move-test-db-datadir.sh` (no args): preflight only. Shows the container settings, schemas and which test
   servers are running. Changes nothing.
2. Window (2-5 minutes, test only; live's DB on 3309 is a different container): note how the running test servers
   were started (`systemctl cat slums-dashboard-test` etc.; persistence-test: `start-test.sh`), stop them.
3. `move-test-db-datadir.sh --go`: stops the container (`docker stop -t 120`), `mv` the datadir, renames the old
   container to `mapleroot-integration-db-old` (kept, stopped), starts a new `mapleroot-integration-db` from the
   same image ID with the same MYSQL_* env, port 127.0.0.1:3307, memory 3G/4G swap, command and secrets mount, and
   the new datadir; waits for it and compares the schema inventory. Receipt in
   `/opt/mapleroot-dev/integration-db-move-<ts>/`.
4. Restart the test servers; update `docs/ops/lxc125-architecture.md` (test DB row).
5. Rollback any time before removing the old container: `move-test-db-datadir.sh --rollback`.
6. After a few good days: `docker rm mapleroot-integration-db-old`.

Note: the container has restart policy `no` today (it does not come back after a host reboot); the script keeps
that. Add `--restart unless-stopped` to the `docker run` in the script if the owner wants it to.
