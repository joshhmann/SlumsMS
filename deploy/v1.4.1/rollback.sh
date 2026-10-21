#!/bin/bash
# SlumsMS 1.4.1 rollback: back to 1.4.0 with deploy.sh's backup, keeping player progress.
#
#   bash /opt/mapleroot-deployments/v1.4.1/rollback.sh --dry-run   # checks only
#   bash /opt/mapleroot-deployments/v1.4.1/rollback.sh
#
# Stops; puts back the 1.4.0 jar, config.yaml (COLOR_PRISM_ENABLED goes away with it), scripts, src/main and
# cashshop from backup/server-runtime.tgz and the WZ file(s) the portal fix touched from backup/wz-before.tgz;
# starts. 1.4.1 has no migrations, so the database is left as is (ledger 001-010; dye rows made on 1.4.1 stay in
# equip_tints / character_look_tints and come back if 1.4.1 is deployed again; 1.4.0 runs with the prism off and
# does not read them). The deploy's files are moved to backup/rolled-back-<time>/, never deleted. Full restore
# instead: backup/mapleroot.sql.gz (loses progress since the deploy). The rehearsal overrides the same variables
# as deploy.sh.
set -euo pipefail
DRY_RUN=0
for a in "$@"; do case $a in --dry-run) DRY_RUN=1;; *) echo "usage: $0 [--dry-run]"; exit 2;; esac; done
VERSION=1.4.1
D=${D:-/opt/mapleroot-deployments/v1.4.1}
ST=${ST:-$D/staging}
LEDGER="001-preserve-arcane-for-classic-fm,002-classic-fm-return,003-solomapling-bots,004-daily-checkin,005-bot-real-stats,006-bot-yield-caps,007-bot-starter-stash,008-world-bera,009-gm-audit,010-color-prism"

SRV=${SRV:-"/opt/mapleroot/MapleRoot Full Repack/Server"}
B=${B:-$D/backup}
if [ "$DRY_RUN" = 1 ]; then LOG=${LOG:-$D/rollback-dryrun.log}; else LOG=${LOG:-$D/rollback.log}; fi
RECEIPT=${RECEIPT:-$D/ROLLBACK-RECEIPT.md}
JOURNAL_UNIT=${JOURNAL_UNIT:-slumsms}
SQL_CMD=${SQL_CMD:-"docker exec -i buns-clean-db-1 sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N mapleroot'"}
STOP_CMD=${STOP_CMD:-"systemctl stop slumsms"}
START_CMD=${START_CMD:-"systemctl start slumsms"}
IS_ACTIVE_CMD=${IS_ACTIVE_CMD:-"systemctl is-active --quiet slumsms"}
ONLINE_TIMEOUT=${ONLINE_TIMEOUT:-120}
MONITOR=${MONITOR-/usr/local/bin/slumsms-monitor}
MONITOR_TARGET=${MONITOR_TARGET:-live}
MAINT_MINUTES=${MAINT_MINUTES:-5}
MAINT_REASON=${MAINT_REASON:-}

exec > >(tee -a "$LOG") 2>&1
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
has() { grep -c "$@" > /dev/null; }
sql() { eval "$SQL_CMD" 2>&1 | { grep -v 'Using a password' || true; }; }
die() { echo "ABORT: $*"; exit 1; }
maint() {
  if [ -z "$MONITOR" ] || [ ! -x "$MONITOR" ]; then echo "(no slumsms-monitor${MONITOR:+ at $MONITOR}: maintenance $1 skipped)"; return 0; fi
  if ! has -F 'def cmd_maintenance' "$MONITOR"; then echo "(the installed slumsms-monitor has no maintenance command: no public post)"; return 0; fi
  local extra=()
  if [ "$1" = start ]; then extra=(--minutes "$MAINT_MINUTES"); if [ -n "$MAINT_REASON" ]; then extra+=(--reason "$MAINT_REASON"); fi; fi
  timeout 60 "$MONITOR" maintenance "$MONITOR_TARGET" "$@" "${extra[@]}" || echo "WARN: slumsms-monitor maintenance $1 failed (carrying on)"
}
now() { date +%s.%N; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
ledger() { echo "SELECT IFNULL(GROUP_CONCAT(migration_id ORDER BY migration_id),'') FROM mapleroot_integration_migrations;" | sql | tail -1; }
W="$SRV/wz"
declare -A T

echo "== SlumsMS $VERSION rollback$([ "$DRY_RUN" = 1 ] && echo ' (DRY RUN: checks only, nothing is stopped or changed)') =="
step "checks"
for f in server-runtime.tgz old-jar.sha256 config.yaml wz-before.tgz wz-before.list; do [ -f "$B/$f" ] || die "no $B/$f (deploy.sh never got past its backup step?)"; done
tar -tzf "$B/server-runtime.tgz" | has -x 'target/Cosmic.jar' || die "server-runtime.tgz has no target/Cosmic.jar"
tar -tzf "$B/server-runtime.tgz" | has -x 'config.yaml' || die "server-runtime.tgz has no config.yaml"
[ "$(tar -tzf "$B/wz-before.tgz" | wc -l)" = "$(wc -l < "$B/wz-before.list")" ] || die "wz-before.tgz does not hold every listed file"
l=$(ledger); echo "ledger: $l"
[ "$l" = "$LEDGER" ] || echo "WARN: ledger is not 001-010; 1.4.1 ran no migrations, so look at what changed it before starting 1.4.0"
eval "$IS_ACTIVE_CMD" && echo "server is running (will be stopped)" || echo "server is NOT running now"
if [ "$DRY_RUN" = 1 ]; then
  step "DRY RUN OK. A real run would: stop; move the deploy's jar, config.yaml, scripts, src/main, cashshop and WZ file(s) to $B/rolled-back-<time>/; restore them from $B/server-runtime.tgz and $B/wz-before.tgz; start; wait for 'is now online'. No SQL."
  exit 0
fi

step "maintenance mode on + public notice"
maint start
step "stop"
T[stop0]=$(now)
eval "$STOP_CMD"
for i in $(seq 1 300); do eval "$IS_ACTIVE_CMD" || break; sleep 1; done
eval "$IS_ACTIVE_CMD" && die "server still active after stop"
T[stop1]=$(now)

aside="$B/rolled-back-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$aside/src" "$aside/wz"

step "restore jar, config.yaml, scripts, src/main, cashshop, WZ"
mv "$SRV/scripts" "$aside/scripts"; mv "$SRV/src/main" "$aside/src/main"
if [ -d "$SRV/cashshop" ]; then mv "$SRV/cashshop" "$aside/cashshop"; fi
cp "$SRV/target/Cosmic.jar" "$aside/Cosmic.jar"; cp "$SRV/config.yaml" "$aside/config.yaml"
tar -xzf "$B/server-runtime.tgz" -C "$SRV"
(cd "$SRV" && sha256sum -c "$B/old-jar.sha256") || die "restored jar hash mismatch"
cmp -s "$B/config.yaml" "$SRV/config.yaml" || die "restored config.yaml differs from the backup copy"
# WZ: copy the deploy's versions aside, then put the backed-up ones back
(cd "$W" && tar -cf - $(cat "$B/wz-before.list")) | tar -xf - -C "$aside/wz"
tar -xzf "$B/wz-before.tgz" -C "$W"
echo "WZ: $(wc -l < "$B/wz-before.list") file(s) restored"
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
  echo "- Ran: $(date -u -d @"${t0}" '+%Y-%m-%d %H:%M:%S UTC') on $(hostname); ledger \`$l\`; jar $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16)"
  echo "- Stop $(dur "${T[stop0]}" "${T[stop1]}") s, restore $(dur "${T[stop1]}" "${T[restore1]}") s, start -> online $(dur "${T[restore1]}" "${T[up]}") s; downtime $(dur "${T[stop0]}" "${T[up]}") s"
  echo "- The deploy's files: \`$aside\`. Log: \`$LOG\`"
} > "$RECEIPT"
cat "$RECEIPT"
step "done"
