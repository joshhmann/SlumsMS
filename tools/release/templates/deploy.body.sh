# ---- body: tools/release/templates/deploy.body.sh (the same for every release; the settings are above) ----
#
# Order: pre-checks (live still up; any failure aborts before the stop) -> stop -> backup (DB with table locks,
# runtime, unit) -> ledger-checked migrations -> stage jar + scripts + src/main data + config together -> unit ->
# start -> smoke test -> receipt. After the stop every abort says "server is DOWN - run rollback.sh".
#
# The rehearsal runs this same file on a copy of live and overrides SRV, B, LOG, RECEIPT, SQL_CMD, DUMP_CMD, STOP_CMD,
# START_CMD, IS_ACTIVE_CMD, DAEMON_RELOAD_CMD, JOURNAL_UNIT, UNIT_FILE, LOGIN_PORT and CHANNEL_BASE. Live uses the
# defaults below.
set -euo pipefail
DRY_RUN=0
for a in "$@"; do case $a in --dry-run) DRY_RUN=1;; *) echo "usage: $0 [--dry-run]"; exit 2;; esac; done
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

exec > >(tee -a "$LOG") 2>&1
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
has() { grep -c "$@" > /dev/null; }   # like grep -q, but reads all input (grep -q + pipefail = SIGPIPE false negatives)
sql() { eval "$SQL_CMD" 2>&1 | { grep -v 'Using a password' || true; }; }
die() { echo "ABORT: $*"; exit 1; }
down() { echo "ABORT: $*"; echo "ABORT: the server is DOWN - run: bash $D/rollback.sh"; exit 1; }
now() { date +%s.%N; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
ledger() { echo "SELECT IFNULL(GROUP_CONCAT(migration_id ORDER BY migration_id),'') FROM mapleroot_integration_migrations;" | sql | tail -1; }
in_ledger() { echo "SELECT COUNT(*) FROM mapleroot_integration_migrations WHERE migration_id='$1';" | sql | tail -1; }
head_hash() { awk '/^server:/{exit} {print}' "$1" | sha256sum | cut -c1-16; }
LEDGER_AFTER=$LEDGER_BEFORE
for id in "${MIGRATION_IDS[@]}"; do LEDGER_AFTER=$(printf '%s\n' ${LEDGER_AFTER//,/ } "$id" | LC_ALL=C sort | paste -sd,); done
C="$SRV/config.yaml"
declare -A T

echo "== SlumsMS $VERSION deploy$([ "$DRY_RUN" = 1 ] && echo ' (DRY RUN: pre-checks only, nothing is stopped or changed)') =="
echo "staging $ST (commit $COMMIT, jar $JAR_SHA16); server $SRV"
step "pre-checks (live still up)"
(cd "$ST" && sha256sum -c --quiet SHA256SUMS) || die "staged files do not match SHA256SUMS"
[ -f "$C" ] && [ -f "$SRV/target/Cosmic.jar" ] && [ -d "$SRV/scripts" ] && [ -d "$SRV/src/main" ] \
  || die "server folder is missing config.yaml, target/Cosmic.jar, scripts/ or src/main/"
grep -q '^server:' "$C" || die "no server: line in config.yaml"
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
mkdir -p "$B"; free_mb=$(df -Pm "$B" | awk 'NR==2{print $4}')
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
  exit 0
fi

step "stop"
T[stop0]=$(now)
eval "$STOP_CMD"
for i in $(seq 1 300); do eval "$IS_ACTIVE_CMD" || break; sleep 1; done
eval "$IS_ACTIVE_CMD" && down "server still active after stop"
T[stop1]=$(now)

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
T[bak1]=$(now)

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
T[mig1]=$(now)

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
if [ -n "$NAV_CACHE_SRC" ]; then mkdir -p "$SRV/cache/bot-nav"; cp -a "$NAV_CACHE_SRC" "$SRV/cache/bot-nav/"; fi
cmp -s "$ST/Cosmic.jar" "$SRV/target/Cosmic.jar" || down "staged jar did not land"
if [ "${#UNIT_EDITS[@]}" -gt 0 ]; then
  for e in "${UNIT_EDITS[@]}"; do sed -i -e "$e" "$UNIT_FILE"; done
  grep -E "ExecStart|TimeoutStopSec" "$UNIT_FILE"
  eval "$DAEMON_RELOAD_CMD"
fi
T[stage1]=$(now)

step "start"
START_TS=$(date '+%Y-%m-%d %H:%M:%S')
eval "$START_CMD"
online=0
for i in $(seq 1 "$ONLINE_TIMEOUT"); do
  journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager | has "is now online" && { online=1; break; }
  sleep 1
done
T[up]=$(now)
J() { journalctl -u "$JOURNAL_UNIT" --since "$START_TS" --no-pager; }
J | grep -E "SlumsMS v|Starting world|Finished loading world|Listening on port|is now online|automatic population|failed to load" || true
[ "$online" = 1 ] || { J | tail -40; down "not online after ${ONLINE_TIMEOUT}s"; }
echo "downtime (stop -> online): $(dur "${T[stop0]}" "${T[up]}") s"

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
T[smoke1]=$(now)

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
