#!/bin/bash
# SlumsMS 1.3.1 live deploy, ticket 41: the live world becomes Bera (world id 1) instead of Scania (0).
# docs/tickets/41-world-name-bera.md. Stops on the first error. Every check that can fail runs BEFORE the stop.
#
#   bash /opt/mapleroot-deployments/v1.3.1-bera/deploy.sh
#
# What changes: jar (1.3.1), config.yaml worlds block (one entry, id: 1), migration 008 (world 0 -> 1), scripts and
# the src/main bot data (identical to 1.3.0, staged anyway). The unit, its flags, the ports (8485, 7585-7592) and
# the template id (6) do not change. Rollback: rollback.sh next to this file.
#
# The rehearsal ran this same file on a copy of live; it overrides SRV, B, LOG, SQL_CMD, DUMP_CMD, STOP_CMD,
# START_CMD and JOURNAL_UNIT. Live uses the defaults below.
set -euo pipefail
D=/opt/mapleroot-deployments/v1.3.1-bera
ST=$D/staging
SRV=${SRV:-"/opt/mapleroot/MapleRoot Full Repack/Server"}
B=${B:-$D/backup}
LOG=${LOG:-$D/deploy.log}
JOURNAL_UNIT=${JOURNAL_UNIT:-slumsms}
UNIT_FILE=${UNIT_FILE:-/etc/systemd/system/slumsms.service}
SQL_CMD=${SQL_CMD:-"docker exec -i buns-clean-db-1 sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N mapleroot'"}
DUMP_CMD=${DUMP_CMD:-"docker exec buns-clean-db-1 sh -c 'mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --lock-tables --routines --triggers mapleroot'"}
STOP_CMD=${STOP_CMD:-"systemctl stop slumsms"}
START_CMD=${START_CMD:-"systemctl start slumsms"}
# sha256 (first 16) of everything above `server:` in live's config.yaml on 2026-10-20 (21 worlds entries, Scania first,
# WORLDS: 1). If it changed, the staged worlds block may be stale: stop and look.
CONFIG_HEAD_BEFORE=9428c6c04531e04b
LEDGER_BEFORE="001-preserve-arcane-for-classic-fm,002-classic-fm-return,003-solomapling-bots,005-bot-real-stats,006-bot-yield-caps,007-bot-starter-stash"

exec > >(tee -a "$LOG") 2>&1
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
sql() { eval "$SQL_CMD" 2>&1 | { grep -v 'Using a password' || true; }; }
die() { echo "ABORT: $*"; exit 1; }
C="$SRV/config.yaml"

step "pre-checks (live still up)"
(cd "$ST" && sha256sum -c --quiet SHA256SUMS) || die "staged files do not match SHA256SUMS"
head_now=$(awk '/^server:/{exit} {print}' "$C" | sha256sum | cut -c1-16)
[ "$head_now" = "$CONFIG_HEAD_BEFORE" ] || die "config.yaml worlds block changed ($head_now); re-make staging/config-worlds-bera.yaml"
grep -q '^server:' "$C" || die "no server: line in config.yaml"
ledger=$(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | sql | tail -1)
echo "ledger: $ledger"
[ "$ledger" = "$LEDGER_BEFORE" ] || die "migration ledger is not 001,002,003,005,006,007"
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
others=$(echo "SELECT COUNT(*) FROM characters WHERE world <> 0;" | sql | tail -1)
[ "$others" = "0" ] || die "$others characters are not in world 0; 008 assumes a single world 0"
pending=$(echo "SELECT COUNT(*) FROM worldtransfers WHERE completionTime IS NULL;" | sql | tail -1)
[ "$pending" = "0" ] || die "$pending pending world transfers"
echo "SELECT 'players online', COUNT(*) FROM accounts WHERE loggedin > 0;" | sql
echo "staged commit: $(cat "$ST/COMMIT")"

step "stop"
T_DOWN=$(date +%s.%N)
eval "$STOP_CMD"

step "backup: DB with table locks, runtime, unit"
mkdir -p "$B"
eval "$DUMP_CMD" 2>/dev/null | gzip > "$B/mapleroot.sql.gz"
gzip -t "$B/mapleroot.sql.gz"; ls -la "$B/mapleroot.sql.gz"
tar -czf "$B/server-runtime.tgz" -C "$SRV" target/Cosmic.jar config.yaml scripts src/main
cp "$C" "$B/config.yaml"
[ -f "$UNIT_FILE" ] && cp "$UNIT_FILE" "$B/slumsms.service" || true
sha256sum "$SRV/target/Cosmic.jar" > "$B/old-jar.sha256"

step "migration 008 (world 0 -> 1)"
sql < "$ST/008-world-bera.sql"
ledger=$(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | sql | tail -1)
echo "ledger: $ledger"
[ "$ledger" = "$LEDGER_BEFORE,008-world-bera" ] || die "ledger after 008 is wrong; server is DOWN - run rollback.sh"
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
left=$(echo "SELECT (SELECT COUNT(*) FROM characters WHERE world=0)+(SELECT COUNT(*) FROM storages WHERE world=0)+(SELECT COUNT(*) FROM playernpcs WHERE world=0)+(SELECT COUNT(*) FROM playernpcs_field WHERE world=0)+(SELECT COUNT(*) FROM plife WHERE world=0);" | sql | tail -1)
[ "$left" = "0" ] || die "$left world-0 rows left after 008; server is DOWN - run rollback.sh"

step "stage jar, scripts, src/main data, config worlds block (jar and config go together)"
cp "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar"
tar -xf "$ST/scripts.tar" -C "$SRV"
tar -xf "$ST/srcdata.tar" -C "$SRV"
{ cat "$ST/config-worlds-bera.yaml"; awk 'f||/^server:/{f=1; print}' "$C"; } > "$C.bera"
grep -q '^server:' "$C.bera" || die "new config has no server: block"
mv "$C.bera" "$C"
awk '/^server:/{exit} {print}' "$C" | grep -E 'id:|channels:|server_message'
grep -E '^  WORLDS:' "$C"

step "start"
START_TS=$(date '+%Y-%m-%d %H:%M:%S')
eval "$START_CMD"
for i in $(seq 1 90); do journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | grep -q "is now online" && break; sleep 1; done
T_UP=$(date +%s.%N)
journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager \
  | grep -E "SlumsMS v|Starting world|Finished loading world|Listening on port|is now online|automatic population|failed to load" || true
awk -v a="$T_DOWN" -v b="$T_UP" 'BEGIN{printf "downtime (stop -> online): %.1f s\n", b-a}'

step "smoke test (expect: SlumsMS v1.3.1, 'Starting world 1 (Bera)', no 'Starting world 0', 0 ERROR lines)"
sleep 30
journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | grep -c "Starting world 0" || true
echo "ERROR/Exception lines so far: $(journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | grep -cE 'ERROR|Exception' || true)"
ss -ltn | grep -E ':(8485|7585|7592) ' || true
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
step "done. In game: the login screen shows Bera with its icon; josh/jimbo characters listed; bots in Henesys/FM."
