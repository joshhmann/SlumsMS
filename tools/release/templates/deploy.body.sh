# ---- body: tools/release/templates/deploy.body.sh (the same for every release; the settings are above) ----
#
# Order: pre-checks (live still up; any failure aborts before the stop) -> stop -> backup (DB with table locks,
# runtime, unit) -> ledger-checked migrations -> stage jar + scripts + src/main data + config together -> unit ->
# start -> smoke test -> receipt. After the stop every abort says "server is DOWN" and what to run.
#
# Phases (tools/release/release runs them one by one, with the client publish between stop-backup and apply-start).
# Without --phase the script runs everything in one go exactly as before (maintenance end right after "is now online").
#   --phase stop-backup   pre-checks, maintenance on + "going down" post, stop, backup. Server left DOWN on the old
#                         version, nothing installed.
#   --phase apply-start   migrations, jar/scripts/data/config/unit, start, wait for "is now online". Maintenance stays
#                         ON (the caller ends it with one combined "back up, press Play" post).
#   --phase smoke         smoke test + receipt (exit 1 on FAIL).
#   --phase restart-old   after a failure BEFORE apply-start: start the untouched old version, wait online,
#                         maintenance off ("back up"). Refuses once apply-start has begun (then: rollback.sh).
# State between phases: markers .stop-backup.done, .apply-start.started, .apply-start.done, .start-ts, .timings in $B.
#
# The rehearsal runs this same file on a copy of live and overrides SRV, B, LOG, RECEIPT, SQL_CMD, DUMP_CMD, STOP_CMD,
# START_CMD, IS_ACTIVE_CMD, DAEMON_RELOAD_CMD, JOURNAL_UNIT, UNIT_FILE, LOGIN_PORT, CHANNEL_BASE and MONITOR. Live uses
# the defaults below.
set -euo pipefail
DRY_RUN=0 PHASE=all
usage() { echo "usage: $0 [--dry-run | --phase stop-backup|apply-start|smoke|restart-old]"; exit 2; }
while [ $# -gt 0 ]; do case $1 in --dry-run) DRY_RUN=1;; --phase) PHASE=${2:-}; shift;; *) usage;; esac; shift; done
case $PHASE in all|stop-backup|apply-start|smoke|restart-old) ;; *) usage;; esac
[ "$DRY_RUN" = 0 ] || [ "$PHASE" = all ] || { echo "--dry-run checks a whole run; it takes no --phase"; exit 2; }
ST=${ST:-$D/staging}
SRV=${SRV:-"/opt/mapleroot/MapleRoot Full Repack/Server"}
B=${B:-$D/backup}
if [ "$DRY_RUN" = 1 ]; then LOG=${LOG:-$D/deploy-dryrun.log}; else LOG=${LOG:-$D/deploy.log}; fi
RECEIPT=${RECEIPT:-$D/RECEIPT.md}
JOURNAL_UNIT=${JOURNAL_UNIT:-slumsms}
UNIT_FILE=${UNIT_FILE:-/etc/systemd/system/slumsms.service}
SQL_CMD=${SQL_CMD:-"docker exec -i buns-clean-db-1 sh -c 'mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -N mapleroot'"}
DUMP_CMD=${DUMP_CMD:-"docker exec buns-clean-db-1 sh -c 'mysqldump -uroot -p\"\$MYSQL_ROOT_PASSWORD\" --lock-tables --routines --triggers mapleroot'"}
STOP_CMD=${STOP_CMD:-"systemctl stop slumsms"}
START_CMD=${START_CMD:-"systemctl start slumsms"}
IS_ACTIVE_CMD=${IS_ACTIVE_CMD:-"systemctl is-active --quiet slumsms"}
DAEMON_RELOAD_CMD=${DAEMON_RELOAD_CMD:-"systemctl daemon-reload"}
LOGIN_PORT=${LOGIN_PORT:-8485}
CHANNEL_BASE=${CHANNEL_BASE:-7585}
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
down() { echo "ABORT: $*"
  if [ -e "$B/.apply-start.started" ]; then echo "ABORT: the server is DOWN - run: bash $D/rollback.sh"
  else echo "ABORT: the server is DOWN on the old version (nothing installed yet) - run: bash $D/deploy.sh --phase restart-old"; fi
  [ -n "$MONITOR" ] && echo "(maintenance mode stays on: alerts are quiet; rollback.sh ends it, or: $MONITOR maintenance $MONITOR_TARGET end)"
  exit 1; }
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
head_hash() { awk '/^server:/{exit} {print}' "$1" | sha256sum | cut -c1-16; }
LEDGER_AFTER=$LEDGER_BEFORE
for id in "${MIGRATION_IDS[@]}"; do LEDGER_AFTER=$(printf '%s\n' ${LEDGER_AFTER//,/ } "$id" | LC_ALL=C sort | paste -sd,); done
C="$SRV/config.yaml"
declare -A T
mark() { T[$1]=$(now); echo "$1=${T[$1]}" >> "$B/.timings"; }   # timings survive between phases
if [ "$PHASE" != all ] && [ -f "$B/.timings" ]; then while IFS== read -r k v; do T[$k]=$v; done < "$B/.timings"; fi
wait_online() {   # sets online=1 once the journal since START_TS says "is now online"
  online=0
  for i in $(seq 1 "$ONLINE_TIMEOUT"); do
    journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | has "is now online" && { online=1; break; }
    sleep 1
  done
}
J() { journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager; }

echo "== SlumsMS $VERSION deploy$([ "$DRY_RUN" = 1 ] && echo ' (DRY RUN: pre-checks only, nothing is stopped or changed)')$([ "$PHASE" != all ] && echo " (phase $PHASE)") =="
echo "staging $ST (commit $COMMIT, jar $JAR_SHA16); server $SRV"

if [ "$PHASE" = restart-old ]; then
  step "restart the OLD version (nothing of $VERSION installed)"
  [ ! -e "$B/.apply-start.started" ] || die "apply-start has begun: files may be half installed. Run: bash $D/rollback.sh"
  if [ -f "$B/old-jar.sha256" ]; then (cd "$SRV" && sha256sum -c --quiet "$B/old-jar.sha256") || die "the installed jar is not the backed-up old one; run rollback.sh"; fi
  if eval "$IS_ACTIVE_CMD"; then echo "server is already running"
  else
    START_TS=$(date '+%Y-%m-%d %H:%M:%S'); eval "$START_CMD"; wait_online
    J | grep -E "SlumsMS v|Starting world|is now online|failed to load" || true
    [ "$online" = 1 ] || { J | tail -40; die "old version not online after ${ONLINE_TIMEOUT}s; look at journalctl -u $JOURNAL_UNIT"; }
  fi
  maint end
  step "old version back up; $VERSION was NOT installed (backup in $B kept; move it away before a new deploy.sh run)"
  exit 0
fi
if [ "$PHASE" = apply-start ]; then
  step "phase checks (server down after stop-backup)"
  (cd "$ST" && sha256sum -c --quiet SHA256SUMS) || down "staged files do not match SHA256SUMS"
  [ -f "$B/.stop-backup.done" ] || die "--phase stop-backup has not completed ($B/.stop-backup.done missing)"
  [ ! -e "$B/.apply-start.started" ] || die "apply-start already ran once on this backup; run rollback.sh (or stage a new version)"
  ! eval "$IS_ACTIVE_CMD" || die "the server is running; apply-start expects it stopped by --phase stop-backup"
fi
if [ "$PHASE" = smoke ]; then
  [ -f "$B/.apply-start.done" ] && [ -f "$B/.start-ts" ] || die "--phase apply-start has not completed"
  START_TS=$(cat "$B/.start-ts")
fi

if [ "$PHASE" = all ] || [ "$PHASE" = stop-backup ]; then
step "pre-checks (live still up)"
(cd "$ST" && sha256sum -c --quiet SHA256SUMS) || die "staged files do not match SHA256SUMS"
[ -f "$C" ] && [ -f "$SRV/target/Cosmic.jar" ] && [ -d "$SRV/scripts" ] && [ -d "$SRV/src/main" ] \
  || die "server folder is missing config.yaml, target/Cosmic.jar, scripts/ or src/main/"
grep -q '^server:' "$C" || die "no server: line in config.yaml"
# Validate value edits before stopping; repeat after CONFIG_HEAD/APPEND in apply-start.
[ -z "$MIN_CLIENT_BUILD" ] || "$ST/set-min-client-build" "$C" "$MIN_CLIENT_BUILD" --dry-run
if [ -n "$CHANNELS$CHANNEL_LOAD" ]; then
  "$ST/set-release-config" "$C" --channels "$CHANNELS" --channel-load "$CHANNEL_LOAD" --dry-run
fi
if [ -n "$CONFIG_HEAD" ]; then
  h=$(head_hash "$C"); [ "$h" = "$CONFIG_HEAD_BEFORE" ] \
    || die "config.yaml block above server: changed ($h, expected $CONFIG_HEAD_BEFORE); re-make $CONFIG_HEAD"
fi
if [ -n "$CONFIG_APPEND" ]; then
  for k in $(grep -oE '^[[:space:]]+[A-Z][A-Z0-9_]*:' "$ST/$CONFIG_APPEND" | tr -d ' :\t'); do
    ! grep -qE "^[[:space:]]+$k:" "$C" || die "config.yaml already has $k (this deploy would add it twice)"
  done
fi
if [ "${#UNIT_EDITS[@]}" -gt 0 ]; then
  [ -f "$UNIT_FILE" ] || die "no unit file $UNIT_FILE"
  for e in "${UNIT_EDITS[@]}"; do
    [ "$(sed -e "$e" "$UNIT_FILE" | sha256sum)" != "$(sha256sum < "$UNIT_FILE")" ] || die "unit edit '$e' changes nothing in $UNIT_FILE"
  done
fi
if [ -n "$NAV_CACHE_SRC" ]; then [ -d "$NAV_CACHE_SRC" ] || die "nav cache source $NAV_CACHE_SRC missing"; fi
for f in "${MIGRATION_FILES[@]}"; do [ -f "$ST/migrations/$f.sql" ] && [ -f "$ST/migrations/$f.rollback.sql" ] || die "staging lacks migrations/$f(.rollback).sql"; done
l=$(ledger); echo "ledger: $l"
[ "$l" = "$LEDGER_BEFORE" ] || die "migration ledger is not the expected '$LEDGER_BEFORE'"
for p in "${PRECHECK_SQL[@]}"; do
  label=${p%%|*}; rest=${p#*|}; q=${rest%|*}; want=${rest##*|}
  got=$(echo "$q" | sql | tail -1); echo "check $label: $got"
  [ "$got" = "$want" ] || die "pre-check '$label' is '$got', expected '$want'"
done
echo "SELECT 'players online', COUNT(*) FROM accounts WHERE loggedin > 0;" | sql
[ ! -e "$B/mapleroot.sql.gz" ] || die "$B already holds a backup (an earlier run?). Move it away first; never overwrite a backup"
[ "$DRY_RUN" = 1 ] || mkdir -p "$B"   # a dry run creates nothing (stage treats an existing backup/ as deployed)
free_mb=$(df -Pm "$(dirname "$B")" | awk 'NR==2{print $4}')
[ "$free_mb" -gt 4096 ] || die "only ${free_mb} MB free for the backup"
echo "old jar: $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16)  new jar: $JAR_SHA16"
eval "$IS_ACTIVE_CMD" && echo "server is running (will be stopped)" || echo "server is NOT running now"

if [ "$DRY_RUN" = 1 ]; then
  step "DRY RUN OK: every pre-check passed. A real run would now:"
  echo "  stop ($STOP_CMD); back up DB with table locks, jar/config/scripts/src/main, unit -> $B"
  echo "  migrations: ${MIGRATION_IDS[*]:-none} (ledger $LEDGER_BEFORE -> $LEDGER_AFTER)"
  echo "  stage Cosmic.jar ($JAR_SHA16), scripts.tar ($(wc -l < "$ST/scripts.list") files), srcdata.tar ($(wc -l < "$ST/srcdata.list") files)"
  [ -n "$CONFIG_HEAD" ] && echo "  replace config.yaml above server: with $CONFIG_HEAD"
  [ -n "$CONFIG_APPEND" ] && echo "  append to config.yaml: $(grep -oE '^[[:space:]]+[A-Z][A-Z0-9_]*:' "$ST/$CONFIG_APPEND" | tr -d ' :\t' | paste -sd' ')"
  for e in "${UNIT_EDITS[@]}"; do echo "  unit: sed '$e'"; done
  [ -n "$NAV_CACHE_SRC" ] && echo "  nav cache from $NAV_CACHE_SRC"
  echo "  start ($START_CMD), wait for 'is now online', smoke test ${SMOKE_SECONDS}s, receipt $RECEIPT"
  echo "  maintenance mode on before the stop and off once online, with these public posts:"
  maint start --dry-run
  exit 0
fi

fi   # pre-checks

if [ "$PHASE" = all ] || [ "$PHASE" = stop-backup ]; then
step "maintenance mode on + public notice (back in about $MAINT_MINUTES min)"
maint start
step "stop"
mark stop0
eval "$STOP_CMD"
for i in $(seq 1 300); do eval "$IS_ACTIVE_CMD" || break; sleep 1; done
eval "$IS_ACTIVE_CMD" && down "server still active after stop"
mark stop1

step "backup: DB with table locks, runtime, unit"
eval "$DUMP_CMD" 2>/dev/null | gzip > "$B/mapleroot.sql.gz"
gzip -t "$B/mapleroot.sql.gz" && [ "$(zcat "$B/mapleroot.sql.gz" | tail -1 | grep -c 'Dump completed')" = 1 ] || down "DB backup is incomplete"
ls -la "$B/mapleroot.sql.gz"
tar -czf "$B/server-runtime.tgz" -C "$SRV" target/Cosmic.jar config.yaml scripts src/main
tar -tzf "$B/server-runtime.tgz" > /dev/null || down "runtime backup unreadable"
cp "$C" "$B/config.yaml"
[ -f "$UNIT_FILE" ] && cp "$UNIT_FILE" "$B/slumsms.service" || true
sha256sum "$SRV/target/Cosmic.jar" > "$B/old-jar.sha256"
echo "$LEDGER_BEFORE" > "$B/ledger-before.txt"
mark bak1
touch "$B/.stop-backup.done"
[ "$PHASE" = all ] || { step "phase stop-backup done: server DOWN on the old version, backup in $B"; exit 0; }
fi   # stop-backup

if [ "$PHASE" = all ] || [ "$PHASE" = apply-start ]; then
touch "$B/.apply-start.started"

step "migrations (ledger-checked): ${MIGRATION_IDS[*]:-none}"
for i in "${!MIGRATION_FILES[@]}"; do
  f=${MIGRATION_FILES[$i]}; id=${MIGRATION_IDS[$i]}
  if [ "$(in_ledger "$id")" = 1 ]; then echo "$id already in the ledger - skipped"; continue; fi
  sql < "$ST/migrations/$f.sql"
  [ "$(in_ledger "$id")" = 1 ] || down "$id did not record itself in the ledger"
  echo "applied $id"
done
l=$(ledger); echo "ledger: $l"
[ "$l" = "$LEDGER_AFTER" ] || down "ledger after migrations is '$l', expected '$LEDGER_AFTER'"
mark mig1

step "stage jar, scripts, src/main data, config (jar and config go together)"
cp "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar"
tar -xf "$ST/scripts.tar" -C "$SRV"
tar -xf "$ST/srcdata.tar" -C "$SRV"
if [ -n "$CONFIG_HEAD" ]; then
  { cat "$ST/$CONFIG_HEAD"; awk 'f||/^server:/{f=1; print}' "$C"; } > "$C.new"
  grep -q '^server:' "$C.new" || down "new config has no server: block"
  mv "$C.new" "$C"
fi
if [ -n "$CONFIG_APPEND" ]; then
  crlf=$(grep -c $'\r$' "$C" || true)
  [ -n "$(tail -c1 "$C")" ] && { [ "$crlf" -gt 0 ] && printf '\r\n' >> "$C" || printf '\n' >> "$C"; }
  if [ "$crlf" -gt 0 ]; then sed 's/\r\?$/\r/' "$ST/$CONFIG_APPEND" >> "$C"; else cat "$ST/$CONFIG_APPEND" >> "$C"; fi
  tail -n "$(wc -l < "$ST/$CONFIG_APPEND")" "$C"
fi
# release publishes the client between stop-backup and apply-start: the gate lands before start.
[ -z "$MIN_CLIENT_BUILD" ] || "$ST/set-min-client-build" "$C" "$MIN_CLIENT_BUILD" || down "client gate edit failed"
if [ -n "$CHANNELS$CHANNEL_LOAD" ]; then
  "$ST/set-release-config" "$C" --channels "$CHANNELS" --channel-load "$CHANNEL_LOAD" || down "channel config edit failed"
fi
if [ -n "$NAV_CACHE_SRC" ]; then mkdir -p "$SRV/cache/bot-nav"; cp -a "$NAV_CACHE_SRC" "$SRV/cache/bot-nav/"; fi
cmp -s "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar" || down "staged jar did not land"
if [ "${#UNIT_EDITS[@]}" -gt 0 ]; then
  for e in "${UNIT_EDITS[@]}"; do sed -i -e "$e" "$UNIT_FILE"; done
  grep -E "ExecStart|TimeoutStopSec" "$UNIT_FILE"
  eval "$DAEMON_RELOAD_CMD"
fi
mark stage1

step "start"
START_TS=$(date '+%Y-%m-%d %H:%M:%S'); echo "$START_TS" > "$B/.start-ts"
eval "$START_CMD"
wait_online
mark up
J | grep -E "SlumsMS v|Starting world|Finished loading world|Listening on port|is now online|automatic population|failed to load" || true
[ "$online" = 1 ] || { J | tail -40; down "not online after ${ONLINE_TIMEOUT}s"; }
echo "downtime (stop -> online): $(dur "${T[stop0]}" "${T[up]}") s"
touch "$B/.apply-start.done"
if [ "$PHASE" = all ]; then maint end
else step "phase apply-start done: online; maintenance stays on (the caller ends it)"; exit 0; fi
fi   # apply-start

step "smoke test (${SMOKE_SECONDS}s)"
sleep "$SMOKE_SECONDS"
fail=()
for s in "${EXPECT_LOG[@]}"; do if J | has -F -- "$s"; then echo "ok   log has '$s'"; else echo "FAIL log lacks '$s'"; fail+=("missing '$s'"); fi; done
for s in "${FORBID_LOG[@]}"; do n=$(J | grep -cF -- "$s" || true); if [ "$n" = 0 ]; then echo "ok   no '$s'"; else echo "FAIL $n x '$s'"; fail+=("$n x '$s'"); fi; done
errs=$(J | grep -cE 'ERROR|Exception' || true); echo "ERROR/Exception lines: $errs"
[ "$errs" = 0 ] || { J | grep -E 'ERROR|Exception' | head -10; fail+=("$errs ERROR/Exception lines"); }
for p in $LOGIN_PORT $CHANNEL_BASE; do
  if ss -ltn | has ":$p "; then echo "ok   listening on $p"; else echo "FAIL nothing on $p"; fail+=("port $p"); fi
done
l=$(ledger); [ "$l" = "$LEDGER_AFTER" ] || fail+=("ledger now '$l'")
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
mark smoke1

result=PASS; [ "${#fail[@]}" = 0 ] || result="FAIL (${fail[*]})"
{
  echo "# SlumsMS $VERSION deploy receipt"
  echo
  echo "- Result: **$result**"
  echo "- Ran: $(date -u -d @"${t0}" '+%Y-%m-%d %H:%M:%S UTC') on $(hostname), server folder \`$SRV\`"
  echo "- Commit $COMMIT, jar $(sha256sum "$SRV/target/Cosmic.jar" | cut -c1-16) (was $(cut -c1-16 "$B/old-jar.sha256"))"
  echo "- Ledger: \`$LEDGER_BEFORE\` -> \`$l\`"
  echo "- Backup: \`$B\` (mapleroot.sql.gz $(du -h "$B/mapleroot.sql.gz" | cut -f1), server-runtime.tgz $(du -h "$B/server-runtime.tgz" | cut -f1))"
  echo "- Log: \`$LOG\`"
  echo
  echo "| Step | Seconds |"
  echo "|---|---|"
  echo "| stop | $(dur "${T[stop0]}" "${T[stop1]}") |"
  echo "| backup | $(dur "${T[stop1]}" "${T[bak1]}") |"
  echo "| migrations | $(dur "${T[bak1]}" "${T[mig1]}") |"
  echo "| stage | $(dur "${T[mig1]}" "${T[stage1]}") |"
  echo "| start -> is now online | $(dur "${T[stage1]}" "${T[up]}") |"
  echo "| **downtime (stop -> online)** | **$(dur "${T[stop0]}" "${T[up]}")** |"
  echo "| smoke test | $(dur "${T[up]}" "${T[smoke1]}") |"
  echo
  echo "Smoke: expected log lines: $(printf "'%s' " "${EXPECT_LOG[@]}"); forbidden: $(printf "'%s' " "${FORBID_LOG[@]}"); ERROR/Exception lines: $errs."
  echo "Rollback: \`bash $D/rollback.sh\`"
} > "$RECEIPT"
cat "$RECEIPT"
[ "$result" = PASS ] || { step "smoke test FAILED; the server is up. Decide: fix forward or bash $D/rollback.sh"; exit 1; }
step "done. ${IN_GAME_CHECK}"
