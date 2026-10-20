#!/bin/bash
# SlumsMS 1.3.1 rollback (ticket 41): back to 1.3.0 on Scania (world 0). Undoes deploy.sh with its backup.
#
#   bash /opt/mapleroot-deployments/v1.3.1-bera/rollback.sh
#
# Keeps player progress made since the deploy: it runs the 008 rollback (world 1 -> 0) instead of restoring the
# dump. Restores the 1.3.0 jar, config.yaml (the 1.3.0 jar stops on the new `id:` key, so both go back together),
# scripts and src/main from backup/server-runtime.tgz. The rehearsal overrides the same variables as deploy.sh.
set -euo pipefail
D=/opt/mapleroot-deployments/v1.3.1-bera
ST=$D/staging
SRV=${SRV:-"/opt/mapleroot/MapleRoot Full Repack/Server"}
B=${B:-$D/backup}
LOG=${LOG:-$D/rollback.log}
JOURNAL_UNIT=${JOURNAL_UNIT:-slumsms}
SQL_CMD=${SQL_CMD:-"docker exec -i buns-clean-db-1 sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N mapleroot'"}
STOP_CMD=${STOP_CMD:-"systemctl stop slumsms"}
START_CMD=${START_CMD:-"systemctl start slumsms"}

exec > >(tee -a "$LOG") 2>&1
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
sql() { eval "$SQL_CMD" 2>&1 | { grep -v 'Using a password' || true; }; }
[ -f "$B/server-runtime.tgz" ] || { echo "no backup at $B"; exit 1; }

step "stop"
eval "$STOP_CMD"
step "migration 008 rollback (world 1 -> 0)"
sql < "$ST/008-world-bera.rollback.sql"
echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | sql
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
step "restore 1.3.0 jar, config.yaml, scripts, src/main"
tar -xzf "$B/server-runtime.tgz" -C "$SRV"
sha256sum -c "$B/old-jar.sha256"
step "start"
START_TS=$(date '+%Y-%m-%d %H:%M:%S')
eval "$START_CMD"
for i in $(seq 1 90); do journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | grep -q "is now online" && break; sleep 1; done
journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | grep -E "SlumsMS v|Starting world|is now online" || true
step "done"
