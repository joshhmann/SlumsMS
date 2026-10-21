#!/bin/bash
# SlumsMS release step 3 (on the host, 164): rehearse a staged release on a fresh copy of live, then clean up.
# Normally started from the workstation with `tools/release/rehearse <version>`; staged copy runs from
# /opt/mapleroot-deployments/v<version>/tools/rehearse-host.sh.
#
#   rehearse-host.sh <version> [--source live|nightly|<dump.sql.gz>] [--server-src DIR] [--unit-src FILE]
#                    [--login-port 8499] [--channel-base 8585] [--baseline 60] [--soak 120] [--keep] [--dry-run]
#
# What it does, live untouched (live is only read: mysqldump --single-transaction, rsync of the Server folder):
#  1. DB copy: --source live (default) dumps live's `mapleroot`; nightly = newest /opt/mapleroot/backups/nightly dump;
#     or a dump file. Loaded into scratch schema rh_<version> in the TEST DB (127.0.0.1:3307) with its own user.
#     If the copy's ledger lacks migrations the release expects live to have (an older dump), they are applied first
#     ("catch-up", listed in the receipt).
#  2. Server copy: live Server/ without wz/ logs/ .git, wz/ symlinked read-only; config.yaml pointed at the scratch
#     schema, LLM/decide URLs blanked. Transient unit slumsms-rh-<version> on spare ports with CPUQuota=200% Nice=19
#     MemoryMax=6G, ExecStart taken from a copy of live's unit (so UNIT_EDITS are rehearsed too).
#  3. Boot the old version (as live runs now), snapshot player rows, then the staged deploy.sh --dry-run and deploy.sh
#     with overrides, soak, rollback.sh --dry-run and rollback.sh, snapshot again: player rows must match.
#  4. Cleanup: unit stopped, schema and user dropped, Server copy/backups/dump deleted. Kept: $R/receipt/ and
#     /opt/mapleroot-deployments/v<version>/REHEARSAL.md.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd); . "$here/lib.sh"
VERSION=${1:-}; shift || true
valid_version "$VERSION" || die "usage: rehearse-host.sh <version> [options]"
SOURCE=live SERVER_SRC="/opt/mapleroot/MapleRoot Full Repack/Server" UNIT_SRC=/etc/systemd/system/slumsms.service
LOGIN_PORT=8499 CHANNEL_BASE=8585 BASELINE=60 SOAK=120 KEEP=0 DRY_RUN=0
while [ $# -gt 0 ]; do case $1 in
  --source) SOURCE=$2; shift;; --server-src) SERVER_SRC=$2; shift;; --unit-src) UNIT_SRC=$2; shift;;
  --login-port) LOGIN_PORT=$2; shift;; --channel-base) CHANNEL_BASE=$2; shift;; --baseline) BASELINE=$2; shift;;
  --soak) SOAK=$2; shift;; --keep) KEEP=1;; --dry-run) DRY_RUN=1;; *) die "unknown option $1";; esac; shift; done
require_host
D=$DEPLOY_ROOT/v$VERSION
tag=$(echo "$VERSION" | tr '.-' '__')
R=/opt/mapleroot-dev/rehearsal-$VERSION
SCHEMA=rh_$tag DBUSER=rh_$tag UNIT=slumsms-rh-$tag
ROOTCNF=/opt/mapleroot-dev/solomapling-test/secrets/root.cnf
TESTDB=(--defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307)
NIGHTLY=/opt/mapleroot/backups/nightly
RC=$R/receipt
LIVE_DB=buns-clean-db-1

step "checks"
[ -f "$D/deploy.sh" ] && [ -f "$D/rollback.sh" ] && [ -f "$D/staging/SHA256SUMS" ] || die "$VERSION is not staged in $D (run tools/release/stage)"
(cd "$D/staging" && sha256sum -c --quiet SHA256SUMS) || die "staging does not match SHA256SUMS"
[ -f "$ROOTCNF" ] || die "no test DB credentials $ROOTCNF"
mysql "${TESTDB[@]}" -N -e "SELECT 1" > /dev/null || die "test DB 127.0.0.1:3307 not reachable"
[ -z "$(mysql "${TESTDB[@]}" -N -e "SHOW DATABASES LIKE '$SCHEMA'")" ] || die "scratch schema $SCHEMA already exists (an earlier rehearsal not cleaned up?)"
! systemctl is-active --quiet "$UNIT" || die "unit $UNIT already running"
for p in $LOGIN_PORT $(seq "$CHANNEL_BASE" $((CHANNEL_BASE + 9))); do
  ! ss -ltn | has ":$p " || die "port $p is in use; pick --login-port/--channel-base"
done
case $LOGIN_PORT in 8485|8489|8479) die "port $LOGIN_PORT belongs to live or a test server";; esac
[ -d "$SERVER_SRC/wz" ] && [ -f "$SERVER_SRC/config.yaml" ] || die "no server folder at $SERVER_SRC"
[ -f "$UNIT_SRC" ] || die "no unit file $UNIT_SRC"
[ ! -e "$R/Server" ] || die "$R/Server exists (an earlier rehearsal not cleaned up?)"
case $SOURCE in
  live) SRC_DESC="fresh mysqldump --single-transaction of live mapleroot ($LIVE_DB)";;
  nightly) SRC_FILE=$(ls -1t "$NIGHTLY"/mapleroot-*.sql.gz | head -1); SRC_DESC="newest nightly dump $SRC_FILE";;
  *) SRC_FILE=$SOURCE; [ -f "$SRC_FILE" ] || die "no dump $SRC_FILE"; SRC_DESC="dump file $SRC_FILE";;
esac
echo "source: $SRC_DESC"
echo "copy: $R (schema $SCHEMA, unit $UNIT, login $LOGIN_PORT, channels $CHANNEL_BASE+)"
if [ "$DRY_RUN" = 1 ]; then
  step "DRY RUN OK: would load the source into $SCHEMA, copy $SERVER_SRC, boot the old version, run deploy.sh --dry-run, deploy.sh, soak ${SOAK}s, rollback.sh --dry-run, rollback.sh, compare snapshots, clean up; receipt $RC"
  exit 0
fi

mkdir -p "$RC"
[ -z "$(ls -A "$RC")" ] || { mv "$RC" "$R/receipt-$(date -u +%Y%m%dT%H%M%SZ)"; mkdir -p "$RC"; }
exec > >(tee -a "$RC/rehearse.log") 2>&1
declare -A T
now() { date +%s.%N; }
dur() { awk -v a="$1" -v b="$2" 'BEGIN{printf "%.1f", b-a}'; }
tsql() { mysql "${TESTDB[@]}" -N "$SCHEMA"; }
cleanup() {
  local rc=$?
  if [ "$KEEP" = 1 ]; then echo "--keep: left $UNIT, $SCHEMA and $R as they are"; return; fi
  echo "[cleanup] stopping $UNIT, dropping $SCHEMA and $DBUSER, deleting the copy"
  systemctl stop "$UNIT" 2>/dev/null || true; systemctl reset-failed "$UNIT" 2>/dev/null || true
  mysql "${TESTDB[@]}" -e "DROP DATABASE IF EXISTS \`$SCHEMA\`; DROP USER IF EXISTS '$DBUSER'@'%';" || true
  rm -rf "$R/Server" "$R/backup" "$R/source.sql.gz" "$R/db.cnf" "$R/slumsms.service" "$R/start.sh"
  echo "[cleanup] done (exit $rc); kept $RC"
}
trap cleanup EXIT

step "1. DB copy: $SRC_DESC"
T[src0]=$(now)
if [ "$SOURCE" = live ]; then
  docker exec "$LIVE_DB" sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers mapleroot' 2>/dev/null | gzip > "$R/source.sql.gz"
else
  if [ "$SOURCE" = nightly ] && [ -f "$NIGHTLY/SHA256SUMS" ] && grep -q "$(basename "$SRC_FILE")" "$NIGHTLY/SHA256SUMS"; then
    (cd "$NIGHTLY" && grep "$(basename "$SRC_FILE")" SHA256SUMS | sha256sum -c --quiet) || die "nightly dump fails its SHA256SUMS"
  fi
  cp "$SRC_FILE" "$R/source.sql.gz"
fi
gzip -t "$R/source.sql.gz"; zcat "$R/source.sql.gz" | tail -1 | has 'Dump completed' || die "source dump is incomplete"
# A dump of a whole schema carries CREATE DATABASE/USE `mapleroot`; drop them so nothing loads outside the scratch schema.
bad=$(zcat "$R/source.sql.gz" | grep -E '^(USE|CREATE DATABASE)' | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' || true)
[ -z "$bad" ] || die "dump names another schema: $bad"
T[src1]=$(now)
DBPASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
mysql "${TESTDB[@]}" -e "CREATE DATABASE \`$SCHEMA\`; CREATE USER '$DBUSER'@'%' IDENTIFIED BY '$DBPASS'; GRANT ALL ON \`$SCHEMA\`.* TO '$DBUSER'@'%';"
zcat "$R/source.sql.gz" | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' | tsql
T[load1]=$(now)
echo "loaded: $(echo "SELECT COUNT(*) FROM characters;" | tsql) characters, $(echo "SELECT COUNT(*) FROM accounts;" | tsql) accounts"

step "catch-up: ledger the release expects live to have"
want=$(sed -n 's/^LEDGER_BEFORE=//p' "$D/deploy.sh" | tr -d "'")
have=$(echo "SELECT IFNULL(GROUP_CONCAT(migration_id ORDER BY migration_id),'') FROM mapleroot_integration_migrations;" | tsql)
echo "copy: $have"; echo "want: $want"
catchup=()
for id in ${want//,/ }; do
  case ",$have," in *",$id,"*) continue;; esac
  f=$(grep -l "VALUES ('$id')" "$D/staging/migrations/"*.sql | grep -v rollback | head -1)
  [ -n "$f" ] || die "the copy lacks $id and staging has no migration for it"
  tsql < "$f"; catchup+=("$id"); echo "catch-up applied $id ($(basename "$f"))"
done
for id in ${have//,/ }; do case ",$want," in *",$id,"*) ;; *) die "the copy has $id, which the release does not expect (live is ahead of the release conf?)";; esac; done

step "2. Server copy"
T[cp0]=$(now)
mkdir -p "$R/Server"
rsync -a --exclude /wz --exclude /logs --exclude /.git --exclude /reports "$SERVER_SRC/" "$R/Server/"
ln -s "$SERVER_SRC/wz" "$R/Server/wz"
C=$R/Server/config.yaml
sed -i -E \
  -e "s#^([[:space:]]*DB_HOST:).*(\r?)\$#\1 127.0.0.1\2#" \
  -e "s#^([[:space:]]*DB_URL_FORMAT:).*(\r?)\$#\1 \"jdbc:mysql://%s:3307/$SCHEMA\"\2#" \
  -e "s#^([[:space:]]*DB_USER:).*(\r?)\$#\1 $DBUSER\2#" \
  -e "s#^([[:space:]]*DB_PASS:).*(\r?)\$#\1 \"$DBPASS\"\2#" \
  -e "s#^([[:space:]]*BOT_LLM_BASE_URL:).*(\r?)\$#\1 \"\"\2#" \
  -e "s#^([[:space:]]*BOT_DECIDE_URL:).*(\r?)\$#\1 \"\"\2#" "$C"
grep -qE "DB_URL_FORMAT: \"jdbc:mysql://%s:3307/$SCHEMA\"" "$C" && grep -qE "^[[:space:]]*DB_USER: $DBUSER" "$C" || die "could not point the copy's config.yaml at $SCHEMA"
grep -E '^[[:space:]]*(DB_HOST|DB_URL_FORMAT|DB_USER|BOT_LLM_BASE_URL|BOT_DECIDE_URL):' "$C" | tr -d '\r'
cp "$UNIT_SRC" "$R/slumsms.service"
cat > "$R/start.sh" <<S
#!/bin/bash
# Start the rehearsal copy with live's ExecStart (from the unit copy, after any deploy edits) on scratch ports.
set -euo pipefail
cmd=\$(sed -n 's/^ExecStart=//p' "$R/slumsms.service" | tail -1)
echo "\$cmd" | grep -q -- '-Dmapleroot.login-port=' && echo "\$cmd" | grep -q -- '-Dmapleroot.channel-base-port=' || { echo "ExecStart lacks the port flags"; exit 1; }
cmd=\$(echo "\$cmd" | sed -E 's/-Dmapleroot.login-port=[0-9]+/-Dmapleroot.login-port=$LOGIN_PORT/; s/-Dmapleroot.channel-base-port=[0-9]+/-Dmapleroot.channel-base-port=$CHANNEL_BASE/; s/ -Dslums.attackcapture=[a-z]+//')
systemctl reset-failed $UNIT 2>/dev/null || true
echo "start: \$cmd"
systemd-run --unit=$UNIT -p CPUQuota=200% -p Nice=19 -p MemoryMax=6G -p SuccessExitStatus=143 -p TimeoutStopSec=5min \\
  -p WorkingDirectory="$R/Server" \$cmd
S
chmod +x "$R/start.sh"
T[cp1]=$(now)
du -sh "$R/Server" | cut -f1 | sed 's/^/copy size: /'

export SRV=$R/Server B=$R/backup LOG=$RC/deploy.log RECEIPT=$RC/DEPLOY-RECEIPT.md JOURNAL_UNIT=$UNIT UNIT_FILE=$R/slumsms.service
export SQL_CMD="mysql --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 -N $SCHEMA"
export DUMP_CMD="mysqldump --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 --lock-tables --routines --triggers $SCHEMA"
export STOP_CMD="systemctl stop $UNIT || true" START_CMD="bash $R/start.sh" IS_ACTIVE_CMD="systemctl is-active --quiet $UNIT"
export DAEMON_RELOAD_CMD=true LOGIN_PORT CHANNEL_BASE
export MONITOR=""   # a copy of live: no maintenance file, no public post
env | grep -E '^(SRV|B|LOG|RECEIPT|JOURNAL_UNIT|UNIT_FILE|SQL_CMD|DUMP_CMD|STOP_CMD|START_CMD|IS_ACTIVE_CMD|DAEMON_RELOAD_CMD|LOGIN_PORT|CHANNEL_BASE|MONITOR)=' | sort > "$RC/env.txt"

snapshot() {
  tsql <<'Q'
SELECT 'char', c.id, c.name, a.name, c.world, c.level, c.exp, c.meso, c.map, c.job, c.gm FROM characters c JOIN accounts a ON a.id=c.accountid ORDER BY c.id;
SELECT 'items_players', COUNT(*) FROM inventoryitems WHERE characterid < 1000000000;
SELECT 'equips_players', COUNT(*) FROM inventoryequipment e JOIN inventoryitems i ON i.inventoryitemid=e.inventoryitemid WHERE i.characterid < 1000000000;
SELECT 'items_per_char', characterid, COUNT(*) FROM inventoryitems WHERE characterid < 1000000000 GROUP BY characterid ORDER BY characterid;
SELECT 'skills_per_char', characterid, COUNT(*) FROM skills WHERE characterid < 1000000000 GROUP BY characterid ORDER BY characterid;
SELECT 'storages', storageid, accountid, world, meso FROM storages ORDER BY storageid;
SELECT 'accounts', COUNT(*) FROM accounts;
SELECT 'ledger', GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;
Q
}
bots() { echo "SELECT 'botstore', (SELECT COUNT(*) FROM botstore), 'bot items', (SELECT COUNT(*) FROM inventoryitems WHERE characterid >= 1000000000);" | tsql 2>/dev/null || echo "no bot tables"; }
wait_online() {  # $1 since
  for i in $(seq 1 120); do journalctl -u "$UNIT" --since "$1" --no-pager | has "is now online" && return 0; sleep 1; done; return 1
}
errs_since() { journalctl -u "$UNIT" --since "$1" --no-pager | grep -cE 'ERROR|Exception' || true; }

step "3a. boot the old version (what live runs now) for ${BASELINE}s"
s0=$(date '+%Y-%m-%d %H:%M:%S'); T[old0]=$(now)
bash "$R/start.sh"
wait_online "$s0" || { journalctl -u "$UNIT" --since "$s0" --no-pager | tail -30; die "old version did not come online"; }
T[old1]=$(now)
journalctl -u "$UNIT" --since "$s0" --no-pager | grep -E "SlumsMS v|Starting world|is now online" | tee "$RC/old-boot.txt"
sleep "$BASELINE"
old_errs=$(errs_since "$s0"); echo "old version ERROR/Exception lines: $old_errs"
snapshot > "$RC/state-before.tsv"; bots | tee "$RC/bots-before.txt"

step "3b. deploy.sh --dry-run against the copy"
LOG=$RC/deploy-dryrun.log bash "$D/deploy.sh" --dry-run > "$RC/deploy-dryrun.out" 2>&1 || { cat "$RC/deploy-dryrun.out"; die "deploy.sh --dry-run failed on the copy"; }
tail -12 "$RC/deploy-dryrun.out"

step "3c. deploy.sh (stops the old version with its bots, like live)"
T[dep0]=$(now)
deploy_rc=0; bash "$D/deploy.sh" > "$RC/deploy.out" 2>&1 || deploy_rc=$?
T[dep1]=$(now)
grep -E '^\[|ABORT|FAIL|ok |downtime' "$RC/deploy.out" || true
[ "$deploy_rc" = 0 ] || echo "deploy.sh exited $deploy_rc"
s1=$(sed -n 's/^\[\([0-9:]*\) .*\] start$/\1/p' "$RC/deploy.out" | tail -1)
snapshot > "$RC/state-after-deploy.tsv"; bots | tee "$RC/bots-after-deploy.txt"

step "3d. soak ${SOAK}s on the new version"
sleep "$SOAK"
if [ -n "$s1" ]; then
  e1=$(date -u -d "$(date -u +%F) $s1" +%s)
  new_errs=$(journalctl -u "$UNIT" --since "@$e1" --no-pager | grep -cE 'ERROR|Exception' || true)
else new_errs="n/a (deploy.sh never reached its start step)"; fi
echo "new version ERROR/Exception lines since its start: $new_errs"
systemctl show "$UNIT" -p MemoryCurrent | sed 's/^/new version /'

step "3e. rollback.sh --dry-run, then rollback.sh"
LOG=$RC/rollback-dryrun.log bash "$D/rollback.sh" --dry-run > "$RC/rollback-dryrun.out" 2>&1 || { cat "$RC/rollback-dryrun.out"; die "rollback.sh --dry-run failed"; }
tail -4 "$RC/rollback-dryrun.out"
T[rb0]=$(now)
LOG=$RC/rollback.log RECEIPT=$RC/ROLLBACK-RECEIPT.md bash "$D/rollback.sh" > "$RC/rollback.out" 2>&1 || { tail -30 "$RC/rollback.out"; die "rollback.sh failed"; }
T[rb1]=$(now)
grep -E '^\[|ABORT|WARN|rolled back' "$RC/rollback.out" || true
sleep 20
rb_errs=$(journalctl -u "$UNIT" --since "@${T[rb0]%.*}" --no-pager | grep -cE 'ERROR|Exception' || true)
journalctl -u "$UNIT" --since "-90s" --no-pager | grep -E "SlumsMS v|Starting world|is now online" | tail -4
snapshot > "$RC/state-after-rollback.tsv"; bots | tee "$RC/bots-after-rollback.txt"
systemctl stop "$UNIT" || true

step "4. compare player rows: before vs after rollback"
same=PASS
diff "$RC/state-before.tsv" "$RC/state-after-rollback.tsv" > "$RC/diff-before-vs-rollback.txt" || same=FAIL
diff "$RC/state-before.tsv" "$RC/state-after-deploy.tsv" > "$RC/diff-before-vs-deploy.txt" || true
echo "player rows before == after rollback: $same"; cat "$RC/diff-before-vs-rollback.txt"
deploy_result=$(grep -m1 -oE 'Result: \*\*[^*]*\*\*' "$RC/DEPLOY-RECEIPT.md" 2>/dev/null | sed 's/Result: //; s/\*//g' || echo "no receipt")
overall=PASS
[ "$deploy_rc" = 0 ] && [ "$same" = PASS ] && [ "$deploy_result" = PASS ] || overall=FAIL
{
  echo "# SlumsMS $VERSION rehearsal on a copy of live (live untouched)"
  echo
  echo "- When: $(date -u -d @"$t0" '+%Y-%m-%d %H:%M') - $(date -u '+%H:%M UTC') on $(hostname). **Result: $overall**"
  echo "- Staged: commit $(cat "$D/staging/COMMIT"), jar $(sha256sum "$D/staging/Cosmic.jar" | cut -c1-16)"
  echo "- DB source: $SRC_DESC -> scratch schema \`$SCHEMA\` in the test DB (3307)"
  [ "${#catchup[@]}" = 0 ] || echo "- Catch-up (source older than the release's expected live ledger): ${catchup[*]}"
  echo "- Server: copy of \`$SERVER_SRC\` (wz symlinked), unit copy of \`$UNIT_SRC\`; transient \`$UNIT\` login $LOGIN_PORT, channels $CHANNEL_BASE+, CPUQuota=200% Nice=19 MemoryMax=6G; LLM/decide URLs blanked"
  echo "- Ran the staged \`$D/deploy.sh\` and \`rollback.sh\` (each \`--dry-run\` first) with the overrides in env.txt"
  echo
  echo "| Step | Seconds |"
  echo "|---|---|"
  echo "| source dump/copy | $(dur "${T[src0]}" "${T[src1]}") |"
  echo "| load into scratch schema | $(dur "${T[src1]}" "${T[load1]}") |"
  echo "| copy Server/ | $(dur "${T[cp0]}" "${T[cp1]}") |"
  echo "| old version start -> online | $(dur "${T[old0]}" "${T[old1]}") |"
  echo "| deploy.sh total (incl. the smoke-test wait) | $(dur "${T[dep0]}" "${T[dep1]}") |"
  echo "| rollback.sh total | $(dur "${T[rb0]}" "${T[rb1]}") |"
  echo
  echo "deploy.sh receipt (DEPLOY-RECEIPT.md):"
  echo
  sed 's/^/> /' "$RC/DEPLOY-RECEIPT.md" 2>/dev/null || echo "> (none: deploy.sh exited $deploy_rc)"
  echo
  echo "rollback.sh receipt (ROLLBACK-RECEIPT.md):"
  echo
  sed 's/^/> /' "$RC/ROLLBACK-RECEIPT.md" 2>/dev/null || true
  echo
  echo "## Checks"
  echo "- old version boot: $(tr '\n' ' ' < "$RC/old-boot.txt" | grep -oE 'SlumsMS v[^ ]*' | head -1), ERROR/Exception lines $old_errs"
  echo "- deploy.sh exit $deploy_rc; smoke $deploy_result; new version ERROR/Exception lines over the soak: $new_errs"
  echo "- after rollback: ERROR/Exception lines $rb_errs"
  echo "- player rows before == after rollback: **$same** (state-before.tsv vs state-after-rollback.tsv)"
  echo "- bots: before \`$(cat "$RC/bots-before.txt" | tr '\t' ' ')\`, after deploy \`$(cat "$RC/bots-after-deploy.txt" | tr '\t' ' ')\`, after rollback \`$(cat "$RC/bots-after-rollback.txt" | tr '\t' ' ')\`"
  echo "- before vs after deploy (expected changes from this release's migrations):"
  echo; sed 's/^/    /' "$RC/diff-before-vs-deploy.txt"; echo
  echo "## Cleanup"
  echo "$([ "$KEEP" = 1 ] && echo "--keep: unit, schema and copy left in place" || echo "unit stopped; schema $SCHEMA and user $DBUSER dropped; Server copy, dumps and backups deleted. Kept: $RC")"
} > "$RC/REHEARSAL.md"
cp "$RC/REHEARSAL.md" "$D/REHEARSAL.md"
cat "$RC/REHEARSAL.md"
step "rehearsal $overall; receipt $RC/REHEARSAL.md (copy in $D/REHEARSAL.md)"
[ "$overall" = PASS ]
