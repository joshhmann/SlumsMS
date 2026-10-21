# ---- body: tools/release/templates/rollback.body.sh (the same for every release; the settings are above) ----
#
# Undoes deploy.sh with its backup and keeps player progress: runs this release's migration rollbacks (newest first,
# only those in the ledger) instead of restoring the dump, then puts back the old jar, config.yaml, scripts and
# src/main from backup/server-runtime.tgz (jar and config always go back together) and the old unit. The files the
# deploy left are moved to backup/rolled-back-<time>/, not deleted. Full restore instead: backup/mapleroot.sql.gz
# (loses progress since the deploy). The rehearsal overrides the same variables as deploy.sh.
set -euo pipefail
DRY_RUN=0
for a in "$@"; do case $a in --dry-run) DRY_RUN=1;; *) echo "usage: $0 [--dry-run]"; exit 2;; esac; done
ST=${ST:-$D/staging}
SRV=${SRV:-"/opt/mapleroot/MapleRoot Full Repack/Server"}
B=${B:-$D/backup}
if [ "$DRY_RUN" = 1 ]; then LOG=${LOG:-$D/rollback-dryrun.log}; else LOG=${LOG:-$D/rollback.log}; fi
RECEIPT=${RECEIPT:-$D/ROLLBACK-RECEIPT.md}
JOURNAL_UNIT=${JOURNAL_UNIT:-slumsms}
UNIT_FILE=${UNIT_FILE:-/etc/systemd/system/slumsms.service}
SQL_CMD=${SQL_CMD:-"docker exec -i buns-clean-db-1 sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N mapleroot'"}
STOP_CMD=${STOP_CMD:-"systemctl stop slumsms"}
START_CMD=${START_CMD:-"systemctl start slumsms"}
IS_ACTIVE_CMD=${IS_ACTIVE_CMD:-"systemctl is-active --quiet slumsms"}
DAEMON_RELOAD_CMD=${DAEMON_RELOAD_CMD:-"systemctl daemon-reload"}
ONLINE_TIMEOUT=${ONLINE_TIMEOUT:-120}
# Maintenance mode + the public "going down" / "back up" post (slumsms-monitor, tickets 42/46). Soft: a missing or
# failing monitor never stops the deploy. The rehearsal sets MONITOR="" (a copy of live must not post).
MONITOR=${MONITOR-/usr/local/bin/slumsms-monitor}
MONITOR_TARGET=${MONITOR_TARGET:-live}
MAINT_MINUTES=${MAINT_MINUTES:-5}
MAINT_REASON=${MAINT_REASON:-}

exec > >(tee -a "$LOG") 2>&1
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
has() { grep -c "$@" > /dev/null; }   # like grep -q, but reads all input (grep -q + pipefail = SIGPIPE false negatives)
sql() { eval "$SQL_CMD" 2>&1 | { grep -v 'Using a password' || true; }; }
die() { echo "ABORT: $*"; exit 1; }
maint() {   # maint start|end [--dry-run]: never fails the script
  if [ -z "$MONITOR" ] || [ ! -x "$MONITOR" ]; then echo "(no slumsms-monitor${MONITOR:+ at $MONITOR}: maintenance $1 skipped)"; return 0; fi
  local extra=()
  if [ "$1" = start ]; then extra=(--minutes "$MAINT_MINUTES"); if [ -n "$MAINT_REASON" ]; then extra+=(--reason "$MAINT_REASON"); fi; fi
  timeout 60 "$MONITOR" maintenance "$MONITOR_TARGET" "$@" "${extra[@]}" || echo "WARN: slumsms-monitor maintenance $1 failed (carrying on)"
}
now() { date +%s.%N; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
ledger() { echo "SELECT IFNULL(GROUP_CONCAT(migration_id ORDER BY migration_id),'') FROM mapleroot_integration_migrations;" | sql | tail -1; }
in_ledger() { echo "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id='$1';" | sql | tail -1; }
declare -A T

echo "== SlumsMS $VERSION rollback$([ "$DRY_RUN" = 1 ] && echo ' (DRY RUN: checks only, nothing is stopped or changed)') =="
step "checks"
[ -f "$B/server-runtime.tgz" ] && [ -f "$B/old-jar.sha256" ] || die "no backup at $B (deploy.sh never got past its backup step?)"
tar -tzf "$B/server-runtime.tgz" | has -x 'target/Cosmic.jar' || die "server-runtime.tgz has no target/Cosmic.jar"
tar -tzf "$B/server-runtime.tgz" | has -x 'config.yaml' || die "server-runtime.tgz has no config.yaml"
for f in "${MIGRATION_FILES[@]}"; do [ -f "$ST/migrations/$f.rollback.sql" ] || die "no migrations/$f.rollback.sql"; done
if [ "${#UNIT_EDITS[@]}" -gt 0 ]; then [ -f "$B/slumsms.service" ] || die "no unit backup $B/slumsms.service"; fi
l=$(ledger); echo "ledger: $l"
todo=()
for (( i=${#MIGRATION_IDS[@]}-1; i>=0; i-- )); do
  [ "$(in_ledger "${MIGRATION_IDS[$i]}")" = 1 ] && todo+=("$i")
done
echo "migration rollbacks to run: $(for i in "${todo[@]}"; do printf '%s ' "${MIGRATION_IDS[$i]}"; done)"
eval "$IS_ACTIVE_CMD" && echo "server is running (will be stopped)" || echo "server is NOT running now"
if [ "$DRY_RUN" = 1 ]; then
  step "DRY RUN OK. A real run would: stop; run those rollbacks; expect ledger '$LEDGER_BEFORE'; move scripts/ and src/main/ to $B/rolled-back-<time>/; restore jar, config.yaml, scripts, src/main from $B/server-runtime.tgz$([ "${#UNIT_EDITS[@]}" -gt 0 ] && echo "; restore the unit from $B/slumsms.service"); start; wait for 'is now online'."
  exit 0
fi

step "maintenance mode on + public notice (no new post if the deploy's is still standing)"
maint start
step "stop"
T[stop0]=$(now)
eval "$STOP_CMD"
for i in $(seq 1 300); do eval "$IS_ACTIVE_CMD" || break; sleep 1; done
eval "$IS_ACTIVE_CMD" && die "server still active after stop"
T[stop1]=$(now)

step "migration rollbacks"
for i in "${todo[@]}"; do
  sql < "$ST/migrations/${MIGRATION_FILES[$i]}.rollback.sql"
  [ "$(in_ledger "${MIGRATION_IDS[$i]}")" = 0 ] || die "${MIGRATION_IDS[$i]} still in the ledger after its rollback"
  echo "rolled back ${MIGRATION_IDS[$i]}"
done
l=$(ledger); echo "ledger: $l"
[ "$l" = "$LEDGER_BEFORE" ] || echo "WARN: ledger is '$l', expected '$LEDGER_BEFORE' - check before starting players"

step "restore jar, config.yaml, scripts, src/main$([ "${#UNIT_EDITS[@]}" -gt 0 ] && echo ', unit')"
aside="$B/rolled-back-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$aside/src"
mv "$SRV/scripts" "$aside/scripts"; mv "$SRV/src/main" "$aside/src/main"
cp "$SRV/target/Cosmic.jar" "$aside/Cosmic.jar"; cp "$SRV/config.yaml" "$aside/config.yaml"
tar -xzf "$B/server-runtime.tgz" -C "$SRV"
(cd "$SRV" && sha256sum -c "$B/old-jar.sha256") || die "restored jar hash mismatch"
cmp -s "$B/config.yaml" "$SRV/config.yaml" || die "restored config.yaml differs from the backup copy"
if [ "${#UNIT_EDITS[@]}" -gt 0 ]; then
  cp "$B/slumsms.service" "$UNIT_FILE"; eval "$DAEMON_RELOAD_CMD"; grep -E "ExecStart|TimeoutStopSec" "$UNIT_FILE"
fi
echo "the deploy's files are in $aside"
T[restore1]=$(now)

step "start"
START_TS=$(date '+%Y-%m-%d %H:%M:%S')
eval "$START_CMD"
online=0
for i in $(seq 1 "$ONLINE_TIMEOUT"); do
  journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | has "is now online" && { online=1; break; }
  sleep 1
done
T[up]=$(now)
journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | grep -E "SlumsMS v|Starting world|is now online|failed to load" || true
[ "$online" = 1 ] || die "not online after ${ONLINE_TIMEOUT}s; look at journalctl -u $JOURNAL_UNIT, or restore $B/mapleroot.sql.gz"
maint end
{
  echo "# SlumsMS $VERSION rollback receipt"
  echo
  echo "- Ran: $(date -u -d @"${t0}" '+%Y-%m-%d %H:%M:%S UTC') on $(hostname); ledger now \`$l\`; jar $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16)"
  echo "- Stop $(dur "${T[stop0]}" "${T[stop1]}") s, rollbacks + restore $(dur "${T[stop1]}" "${T[restore1]}") s, start -> online $(dur "${T[restore1]}" "${T[up]}") s; downtime $(dur "${T[stop0]}" "${T[up]}") s"
  echo "- The deploy's files: \`$aside\`. Log: \`$LOG\`"
} > "$RECEIPT"
cat "$RECEIPT"
step "done"
