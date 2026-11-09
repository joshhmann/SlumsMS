# ---- body: tools/release/templates/deploy.body.sh (the same for every release; the settings are above) ----
#
# Order: pre-checks (live still up; any failure aborts before the stop) -> stop -> backup (DB with table locks,
# runtime incl. cashshop/catalog.tsv, unit) -> ledger-checked migrations -> stage jar + scripts + src/main data +
# config (+ the Cash Shop catalogue when CASHSHOP_CATALOG is set) together -> unit ->
# start -> smoke test -> receipt. WZ files (staging/wz-targets.txt, from the conf's WZ_TARGETS/WZ_REPLACE) are backed
# up in stop-backup ($B/wz-backup), staged replacements (staging/wz/) are copied over $SRV/wz in apply-start, and the
# extra steps (staging/extra/, run between the two phases by release/release-live.sh) may edit the listed files in
# place; rollback.sh and restart-old put the backed-up files back. After the stop every abort says "server is DOWN" and what to run.
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
# b377: the in-game "restarting in 1 minute" notice through the server's ops endpoint (OpsNoticeApi), posted before the
# stop and independent of MONITOR. Port and secret file come from the unit's own -Dslums.ops.* flags; the secret goes to
# curl on stdin, never on a command line. Soft: no endpoint or a refused post never stops the deploy. The rehearsals set
# NOTICE_SECONDS=0 (their copy of the unit names live's port). NOTICE_SECONDS=0 skips the notices and the wait.
# Owner 9 Nov 2026: warn players 5 minutes ahead (first notice), again 1 minute ahead, and once more at 10 s.
NOTICE_SECONDS=${NOTICE_SECONDS:-300}
NOTICE_TEXT=${NOTICE_TEXT:-"The server restarts in 5 minutes for an update. Please find a safe spot; you'll be back in about a minute."}
NOTICE_MID=${NOTICE_MID:-"The server restarts in 1 minute for an update."}
NOTICE_LAST=${NOTICE_LAST:-"Restarting now, see you in a minute."}

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
ops_flag() { { grep -o -- "-Dslums\.ops\.$1=[^ \"]*" "$UNIT_FILE" 2>/dev/null || true; } | head -1 | cut -d= -f2-; }
notice() {   # notice "<text>": one in-game notice; true only when the server answered ok. Never fails the script.
  [ "$NOTICE_SECONDS" -gt 0 ] || return 1
  local port file out
  port=$(ops_flag port); file=$(ops_flag secretFile)
  if [ -z "$port" ] || [ -z "$file" ] || [ ! -r "$file" ]; then echo "(no ops notice endpoint on the unit: in-game notice skipped)"; return 1; fi
  out=$(printf 'X-Slums-Ops: %s\n' "$(tr -d '\r\n' < "$file")" \
        | timeout 10 curl -sS -H @- --data-binary "$1" "http://127.0.0.1:$port/notice" 2>&1) \
    || { echo "WARN: in-game notice not sent (carrying on): $out"; return 1; }
  echo "in-game notice: $out"
  case $out in *'"ok":true'*) return 0;; *) return 1;; esac
}
now() { date +%s.%N; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
ledger() { echo "SET SESSION group_concat_max_len=1048576; SELECT IFNULL(GROUP_CONCAT(migration_id ORDER BY migration_id),'') FROM mapleroot_integration_migrations;" | sql | tail -1; }
in_ledger() { echo "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id='$1';" | sql | tail -1; }
head_hash() { awk '/^server:/{exit} {print}' "$1" | sha256sum | cut -c1-16; }
LEDGER_AFTER=$LEDGER_BEFORE
for id in "${MIGRATION_IDS[@]}"; do LEDGER_AFTER=$(printf '%s\n' ${LEDGER_AFTER//,/ } "$id" | LC_ALL=C sort | paste -sd,); done
C="$SRV/config.yaml"
WZT="$ST/wz-targets.txt"   # WZ files this release writes (relative to wz/); absent = the release touches no WZ
wz_restore() {   # put back the WZ files stop-backup saved (and remove the ones that did not exist); idempotent
  [ -f "$WZT" ] && [ -d "$B/wz-backup" ] || return 0
  local rel
  while IFS= read -r rel || [ -n "$rel" ]; do
    [ -n "$rel" ] || continue
    if grep -qxF -- "$rel" "$B/wz-absent.txt" 2>/dev/null; then rm -f "$SRV/wz/$rel"
    else mkdir -p "$SRV/wz/$(dirname "$rel")"; cp -p "$B/wz-backup/$rel" "$SRV/wz/$rel"; fi
  done < "$WZT"
}
UE_ARGS=(); for f in "${UNIT_FLAGS_ABSENT[@]}"; do UE_ARGS+=(--absent "$f"); done
for f in "${UNIT_FLAGS_PRESENT[@]}"; do UE_ARGS+=(--present "$f"); done
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
  wz_restore   # the extra steps may have half-written a WZ file
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
SET_ARGS=(); for kv in "${SERVER_SET[@]}"; do SET_ARGS+=(--set "$kv"); done
[ "${#SET_ARGS[@]}" = 0 ] || "$ST/set-release-config" "$C" "${SET_ARGS[@]}" --dry-run
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
  # in order, on a copy: an edit that changes nothing at its step, or an UNIT_FLAGS_ABSENT/PRESENT flag in the wrong state
  # afterwards, aborts here (before the stop). A removal edit works like any other: s# -Dflag=value##g (b328).
  "$ST/unit-edit" --check "$UNIT_FILE" "${UE_ARGS[@]}" -- "${UNIT_EDITS[@]}" || die "unit edits do not apply cleanly to $UNIT_FILE"
fi
if [ -f "$WZT" ]; then
  [ -d "$SRV/wz" ] || die "server folder has no wz/"
  while IFS= read -r rel || [ -n "$rel" ]; do
    [ -n "$rel" ] || continue
    case $rel in /*|..|../*|*/..|*/../*) die "bad WZ target '$rel'";; esac
    [ -f "$ST/wz/$rel" ] || [ -f "$SRV/wz/$rel" ] || echo "wz target $rel is not in $SRV/wz yet (an extra step must create it; rollback removes it)"
  done < "$WZT"
fi
if [ -n "$NAV_CACHE_SRC" ]; then [ -d "$NAV_CACHE_SRC" ] || die "nav cache source $NAV_CACHE_SRC missing"; fi
if [ -n "$CASHSHOP_CATALOG" ]; then   # the Cash Shop catalogue goes live with the jar (tools/cashshop)
  [ -s "$ST/$CASHSHOP_CATALOG" ] || die "staging lacks the catalogue $CASHSHOP_CATALOG"
  [ -d "$SRV/cashshop" ] || die "server folder has no cashshop/ (the catalogue's working dir)"
fi
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
  [ "${#SERVER_SET[@]}" -gt 0 ] && echo "  config.yaml server: values (set-release-config --set): ${SERVER_SET[*]}"
  for e in "${UNIT_EDITS[@]}"; do echo "  unit: sed '$e'"; done
  for f in "${UNIT_FLAGS_ABSENT[@]}"; do echo "  unit: $f must be gone afterwards"; done
  [ ! -f "$WZT" ] || echo "  wz: back up $(wc -l < "$WZT") target file(s); copy $(find "$ST/wz" -type f 2>/dev/null | wc -l) staged file(s) over $SRV/wz"
  [ -n "$NAV_CACHE_SRC" ] && echo "  nav cache from $NAV_CACHE_SRC"
  [ -n "$CASHSHOP_CATALOG" ] && echo "  cashshop/catalog.tsv from staging ($(sha256sum "$ST/$CASHSHOP_CATALOG" | cut -c1-16); now $( [ -f "$SRV/cashshop/catalog.tsv" ] && sha256sum "$SRV/cashshop/catalog.tsv" | cut -c1-16 || echo none))"
  echo "  start ($START_CMD), wait for 'is now online', smoke test ${SMOKE_SECONDS}s, receipt $RECEIPT"
  if [ "$NOTICE_SECONDS" -gt 0 ] && [ -n "$(ops_flag port)" ]; then
    echo "  in game, ${NOTICE_SECONDS}s before the stop: '$NOTICE_TEXT';$([ "$NOTICE_SECONDS" -gt 60 ] && echo " 60s before: '$NOTICE_MID';") 10s before: '$NOTICE_LAST'"
  else
    echo "  no in-game notice (NOTICE_SECONDS=$NOTICE_SECONDS, ops port on the unit: $(ops_flag port))"
  fi
  echo "  maintenance mode on before the stop and off once online, with these public posts:"
  maint start --dry-run
  exit 0
fi

fi   # pre-checks

if [ "$PHASE" = all ] || [ "$PHASE" = stop-backup ]; then
step "in-game notice: restart in ${NOTICE_SECONDS}s"
if notice "$NOTICE_TEXT"; then
  if [ "$NOTICE_SECONDS" -gt 60 ]; then   # the 1-minute reminder, then the last 10 s
    sleep $((NOTICE_SECONDS - 60)); notice "$NOTICE_MID" || true; sleep 50
  elif [ "$NOTICE_SECONDS" -gt 10 ]; then
    sleep $((NOTICE_SECONDS - 10))
  fi
  notice "$NOTICE_LAST" || true
  sleep $(( NOTICE_SECONDS < 10 ? NOTICE_SECONDS : 10 ))
fi
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
rt=(target/Cosmic.jar config.yaml scripts src/main)
[ -f "$SRV/cashshop/catalog.tsv" ] && rt+=(cashshop/catalog.tsv)   # the catalogue goes back with the old jar
tar -czf "$B/server-runtime.tgz" -C "$SRV" "${rt[@]}"
tar -tzf "$B/server-runtime.tgz" > /dev/null || down "runtime backup unreadable"
# P2-8: the monitor's VERSION_FILE. Keep the old one with the backup (rollback.sh puts it back); written below, after the smoke.
if [ -f "$SRV/SLUMSMS_VERSION" ]; then cp -p "$SRV/SLUMSMS_VERSION" "$B/SLUMSMS_VERSION.old"; else : > "$B/SLUMSMS_VERSION.absent"; fi
cp "$C" "$B/config.yaml"
if [ -f "$WZT" ]; then   # the WZ files this release writes: saved while the server is down, before any extra step
  mkdir -p "$B/wz-backup"; : > "$B/wz-absent.txt"
  while IFS= read -r rel || [ -n "$rel" ]; do
    [ -n "$rel" ] || continue
    if [ -f "$SRV/wz/$rel" ]; then mkdir -p "$B/wz-backup/$(dirname "$rel")"; cp -p "$SRV/wz/$rel" "$B/wz-backup/$rel"
    else echo "$rel" >> "$B/wz-absent.txt"; fi
  done < "$WZT"
  echo "wz backup: $(find "$B/wz-backup" -type f | wc -l) file(s) saved, $(wc -l < "$B/wz-absent.txt") not present"
fi
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
  touch "$B/.migration-$id.attempted"
  sql < "$ST/migrations/$f.sql" || down "$id failed (possibly after partial DDL); run rollback.sh"
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
if [ "${#SERVER_SET[@]}" -gt 0 ]; then
  SET_ARGS=(); for kv in "${SERVER_SET[@]}"; do SET_ARGS+=(--set "$kv"); done
  "$ST/set-release-config" "$C" "${SET_ARGS[@]}" || down "server config edit failed"
fi
if [ -n "$NAV_CACHE_SRC" ]; then mkdir -p "$SRV/cache/bot-nav"; cp -a "$NAV_CACHE_SRC" "$SRV/cache/bot-nav/"; fi
if [ -d "$ST/wz" ]; then   # staged WZ replacements; the originals are in $B/wz-backup (rollback.sh puts them back)
  while IFS= read -r rel || [ -n "$rel" ]; do
    [ -f "$ST/wz/$rel" ] || continue
    mkdir -p "$SRV/wz/$(dirname "$rel")"; cp -p "$ST/wz/$rel" "$SRV/wz/$rel"
    cmp -s "$ST/wz/$rel" "$SRV/wz/$rel" || down "staged WZ file $rel did not land"
    echo "wz: $rel replaced"
  done < "$WZT"
fi
if [ -n "$CASHSHOP_CATALOG" ]; then
  cp "$ST/$CASHSHOP_CATALOG" "$SRV/cashshop/catalog.tsv"
  cmp -s "$ST/$CASHSHOP_CATALOG" "$SRV/cashshop/catalog.tsv" || down "staged catalogue did not land"
fi
cmp -s "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar" || down "staged jar did not land"
if [ "${#UNIT_EDITS[@]}" -gt 0 ]; then
  "$ST/unit-edit" --apply "$UNIT_FILE" "${UE_ARGS[@]}" -- "${UNIT_EDITS[@]}" || down "unit edits failed"
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
errs=$(J | grep -E 'ERROR|Exception' | grep -cvE '\] WARN ' || true); echo "ERROR/Exception lines: $errs"
[ "$errs" = 0 ] || { J | grep -E 'ERROR|Exception' | grep -vE '\] WARN ' | head -10; fail+=("$errs ERROR/Exception lines"); }
for p in $LOGIN_PORT $CHANNEL_BASE; do
  if ss -ltn | has ":$p "; then echo "ok   listening on $p"; else echo "FAIL nothing on $p"; fail+=("port $p"); fi
done
l=$(ledger); [ "$l" = "$LEDGER_AFTER" ] || fail+=("ledger now '$l'")
# b251 routing guard: the running jar's table still blocks every required map, and nothing swapped the table.
table=$(unzip -p "$SRV/target/Cosmic.jar" soloMapling/map-routing.tsv 2>/dev/null)
for id in "${ROUTE_REQUIRED[@]}"; do
  if printf '%s\n' "$table" | has -P "^$id\t"; then echo "ok   map $id still routed"; else echo "FAIL map $id not routed"; fail+=("route $id"); fi
done
pid=$(systemctl show -p MainPID --value "$JOURNAL_UNIT" 2>/dev/null)
if [ -n "$pid" ] && [ "$pid" != 0 ] && tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null | has -F 'slums.maps.routing.table'; then
  echo "FAIL the server runs with -Dslums.maps.routing.table (TEST-only)"; fail+=("routing.table override")
else echo "ok   no routing table override"; fi
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | sql
mark smoke1

result=PASS; [ "${#fail[@]}" = 0 ] || result="FAIL (${fail[*]})"
# P2-8: only a passed smoke advertises the new version (line 1 version, line 2 commit); atomic, so the monitor never reads half.
if [ "$result" = PASS ]; then
  printf '%s\n%s\n' "$VERSION" "$COMMIT" > "$SRV/SLUMSMS_VERSION.tmp" && mv -f "$SRV/SLUMSMS_VERSION.tmp" "$SRV/SLUMSMS_VERSION" \
    && echo "version file: $SRV/SLUMSMS_VERSION = $VERSION" || echo "WARN: could not write $SRV/SLUMSMS_VERSION (the monitor falls back to VERSION=)"
fi
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
