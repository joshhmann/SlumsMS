#!/bin/bash
# SlumsMS 1.4.0 rehearsal on a copy of live (runs on 164; live is only read). Runs the STAGED deploy.sh and
# rollback.sh from /opt/mapleroot-deployments/v1.4.0 against the copy, with overrides, plus the 1.4.0 checks.
#
#   bash /opt/mapleroot-deployments/v1.4.0/rehearse.sh [--source nightly|live|<dump.sql.gz>] [--login-port 8519]
#        [--channel-base 8885] [--soak 60]
#
# Like tools/release/rehearse-host.sh, but: the copy gets its OWN wz/ (deploy.sh edits WZ files; a symlink would
# write into live's), it lives in /opt/mapleroot-dev/release-1.4.0-rehearsal and is left there (unit stopped), and it
# also proves: migrations re-run as no-ops, the rollback SQLs (and their re-run), live's characters log in end to end
# (tools/soak/CharLoadProbe, scratch passwords), and the 1.4.0 feature checks.
set -euo pipefail
SOURCE=nightly LOGIN_PORT=8519 CHANNEL_BASE=8885 SOAK=60
while [ $# -gt 0 ]; do case $1 in
  --source) SOURCE=$2; shift;; --login-port) LOGIN_PORT=$2; shift;; --channel-base) CHANNEL_BASE=$2; shift;;
  --soak) SOAK=$2; shift;; *) echo "unknown option $1"; exit 2;; esac; shift; done
VERSION=1.4.0
D=/opt/mapleroot-deployments/v$VERSION
R=/opt/mapleroot-dev/release-1.4.0-rehearsal
RC=$R/receipt
SERVER_SRC="/opt/mapleroot/MapleRoot Full Repack/Server"
UNIT_SRC=/etc/systemd/system/slumsms.service
SCHEMA=rh_1_4_0 DBUSER=rh_1_4_0 UNIT=slumsms-rh-1-4-0
ROOTCNF=/opt/mapleroot-dev/solomapling-test/secrets/root.cnf
TESTDB=(--defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307)
NIGHTLY=/opt/mapleroot/backups/nightly
LIVE_DB=buns-clean-db-1
PROBE_PW=rh140-probe
t0=$(date +%s); step() { echo "[$(date -u +%H:%M:%S) +$(( $(date +%s) - t0 ))s] $*"; }
has() { grep -c "$@" > /dev/null; }
die() { echo "ABORT: $*"; exit 1; }
tsql() { mysql "${TESTDB[@]}" -N "$SCHEMA" 2>&1; }
declare -A RES

step "checks"
[ "$(hostname)" = maplestory ] || die "run on 164"
[ -f "$D/deploy.sh" ] && [ -f "$D/rollback.sh" ] && (cd "$D/staging" && sha256sum -c --quiet SHA256SUMS) || die "$D is not staged"
[ ! -e "$D/deploy.log" ] && [ ! -e "$D/backup" ] || die "$D has a deploy.log or backup: it was run for real?"
mysql "${TESTDB[@]}" -N -e "SELECT 1" > /dev/null || die "test DB 127.0.0.1:3307 not reachable"
[ -z "$(mysql "${TESTDB[@]}" -N -e "SHOW DATABASES LIKE '$SCHEMA'")" ] || die "scratch schema $SCHEMA exists (DROP DATABASE $SCHEMA first)"
! systemctl is-active --quiet "$UNIT" || die "unit $UNIT already running"
for p in $LOGIN_PORT $(seq "$CHANNEL_BASE" $((CHANNEL_BASE + 9))); do ! ss -ltn | has ":$p " || die "port $p is in use"; done
case $LOGIN_PORT in 8485|8489|8479|8509) die "port $LOGIN_PORT belongs to live or a test server";; esac
mkdir -p "$R"; [ ! -e "$R/Server" ] || die "$R/Server exists (move it away first)"
case $SOURCE in
  live) SRC_DESC="fresh mysqldump --single-transaction of live mapleroot";;
  nightly) SRC_FILE=$(ls -1t "$NIGHTLY"/mapleroot-*.sql.gz | head -1); SRC_DESC="newest nightly dump $SRC_FILE";;
  *) SRC_FILE=$SOURCE; [ -f "$SRC_FILE" ] || die "no dump $SRC_FILE"; SRC_DESC="dump file $SRC_FILE";;
esac
if [ -e "$RC" ]; then mv "$RC" "$R/receipt-$(date -u +%Y%m%dT%H%M%SZ)"; fi
mkdir -p "$RC"
exec > >(tee -a "$RC/rehearse.log") 2>&1
echo "source: $SRC_DESC; copy $R; schema $SCHEMA; unit $UNIT; login $LOGIN_PORT, channels $CHANNEL_BASE+"

step "1. DB copy into the scratch schema $SCHEMA (test DB 3307)"
if [ "$SOURCE" = live ]; then
  docker exec "$LIVE_DB" sh -c 'mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" --single-transaction --routines --triggers mapleroot' 2>/dev/null | gzip > "$R/source.sql.gz"
else
  if [ -f "$NIGHTLY/SHA256SUMS" ] && grep -q "$(basename "$SRC_FILE")" "$NIGHTLY/SHA256SUMS"; then
    (cd "$NIGHTLY" && grep "$(basename "$SRC_FILE")" SHA256SUMS | sha256sum -c --quiet) || die "nightly dump fails its SHA256SUMS"
    echo "nightly dump matches its SHA256SUMS"
  fi
  cp "$SRC_FILE" "$R/source.sql.gz"
fi
gzip -t "$R/source.sql.gz"; zcat "$R/source.sql.gz" | tail -1 | has 'Dump completed' || die "source dump is incomplete"
bad=$(zcat "$R/source.sql.gz" | grep -E '^(USE|CREATE DATABASE)' | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' || true)
[ -z "$bad" ] || die "dump names another schema: $bad"
DBPASS=$(head -c 18 /dev/urandom | base64 | tr -d '/+=')
mysql "${TESTDB[@]}" -e "CREATE DATABASE \`$SCHEMA\`; CREATE USER '$DBUSER'@'%' IDENTIFIED BY '$DBPASS'; GRANT ALL ON \`$SCHEMA\`.* TO '$DBUSER'@'%';"
zcat "$R/source.sql.gz" | grep -vE '^(USE `mapleroot`;|CREATE DATABASE .*`mapleroot`)' | mysql "${TESTDB[@]}" "$SCHEMA"
echo "loaded: $(echo "SELECT COUNT(*) FROM characters;" | tsql) characters, $(echo "SELECT COUNT(*) FROM accounts;" | tsql) accounts; ledger $(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | tsql)"

step "2. Server copy (own wz/), config pointed at $SCHEMA"
mkdir -p "$R/Server"
nice -n 19 rsync -a --exclude /logs --exclude /.git --exclude /reports --exclude /wz.tar.gz --exclude '/wz-backup-*' "$SERVER_SRC/" "$R/Server/"
[ -d "$R/Server/wz/Mob.wz" ] && [ ! -L "$R/Server/wz" ] || die "wz copy failed"
C=$R/Server/config.yaml
sed -i -E \
  -e "s#^([[:space:]]*DB_HOST:).*(\r?)\$#\1 127.0.0.1\2#" \
  -e "s#^([[:space:]]*DB_URL_FORMAT:).*(\r?)\$#\1 \"jdbc:mysql://%s:3307/$SCHEMA\"\2#" \
  -e "s#^([[:space:]]*DB_USER:).*(\r?)\$#\1 $DBUSER\2#" \
  -e "s#^([[:space:]]*DB_PASS:).*(\r?)\$#\1 \"$DBPASS\"\2#" \
  -e "s#^([[:space:]]*BOT_LLM_BASE_URL:).*(\r?)\$#\1 \"\"\2#" \
  -e "s#^([[:space:]]*BOT_DECIDE_URL:).*(\r?)\$#\1 \"\"\2#" "$C"
grep -qE "DB_URL_FORMAT: \"jdbc:mysql://%s:3307/$SCHEMA\"" "$C" && grep -qE "^[[:space:]]*DB_USER: $DBUSER" "$C" || die "could not point the copy at $SCHEMA"
cp "$UNIT_SRC" "$R/slumsms.service"
cat > "$R/start.sh" <<S
#!/bin/bash
# Start the rehearsal copy with live's ExecStart on scratch ports, low priority.
set -euo pipefail
cmd=\$(sed -n 's/^ExecStart=//p' "$R/slumsms.service" | tail -1)
cmd=\$(echo "\$cmd" | sed -E 's/-Dmapleroot.login-port=[0-9]+/-Dmapleroot.login-port=$LOGIN_PORT/; s/-Dmapleroot.channel-base-port=[0-9]+/-Dmapleroot.channel-base-port=$CHANNEL_BASE/; s/ -Dslumsms.announceFile=[^ ]+//')
echo "\$cmd" | grep -q -- "-Dmapleroot.login-port=$LOGIN_PORT" || { echo "port override failed"; exit 1; }
systemctl reset-failed $UNIT 2>/dev/null || true
systemd-run --unit=$UNIT -p CPUQuota=200% -p Nice=19 -p MemoryMax=6G -p SuccessExitStatus=143 -p TimeoutStopSec=5min \\
  -p WorkingDirectory="$R/Server" \$cmd
S
chmod +x "$R/start.sh"

export D SRV=$R/Server B=$R/backup LOG=$RC/deploy.log RECEIPT=$RC/DEPLOY-RECEIPT.md JOURNAL_UNIT=$UNIT UNIT_FILE=$R/slumsms.service
export SQL_CMD="mysql --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 -N $SCHEMA"
export DUMP_CMD="mysqldump --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 --lock-tables --routines --triggers $SCHEMA"
export TABLE_DUMP_CMD="mysqldump --defaults-extra-file=$ROOTCNF -h127.0.0.1 -P3307 --single-transaction $SCHEMA %TABLES%"
export STOP_CMD="systemctl stop $UNIT || true" START_CMD="bash $R/start.sh" IS_ACTIVE_CMD="systemctl is-active --quiet $UNIT"
export LOGIN_PORT CHANNEL_BASE MONITOR=""
env | grep -E '^(SRV|B|LOG|RECEIPT|JOURNAL_UNIT|SQL_CMD|DUMP_CMD|TABLE_DUMP_CMD|STOP_CMD|START_CMD|IS_ACTIVE_CMD|LOGIN_PORT|CHANNEL_BASE|MONITOR)=' | sort > "$RC/env.txt"

snapshot() {
  tsql <<'Q'
SELECT 'char', c.id, c.name, a.name, c.world, c.level, c.exp, c.meso, c.map, c.job, c.gm FROM characters c JOIN accounts a ON a.id=c.accountid ORDER BY c.id;
SELECT 'items_per_char', characterid, COUNT(*) FROM inventoryitems WHERE characterid < 1000000000 GROUP BY characterid ORDER BY characterid;
SELECT 'skills_per_char', characterid, COUNT(*) FROM skills WHERE characterid < 1000000000 GROUP BY characterid ORDER BY characterid;
SELECT 'storages', storageid, accountid, world, meso FROM storages ORDER BY storageid;
SELECT 'accounts', COUNT(*) FROM accounts;
SELECT 'ledger', GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;
Q
}
wait_online() { for i in $(seq 1 150); do journalctl -u "$UNIT" --since "$1" --no-pager | has "is now online" && return 0; sleep 1; done; return 1; }
J() { journalctl -u "$UNIT" --since "$1" --no-pager; }

step "3. boot the old version (live's jar) for a baseline"
s0=$(date '+%Y-%m-%d %H:%M:%S')
bash "$R/start.sh"
wait_online "$s0" || { J "$s0" | tail -30; die "old version did not come online"; }
J "$s0" | grep -E "SlumsMS v|Starting world|is now online" | tee "$RC/old-boot.txt"
sleep 20
RES[old_errs]=$(J "$s0" | grep -cE 'ERROR|Exception' || true)
snapshot > "$RC/state-before.tsv"

step "4. staged deploy.sh --dry-run, then deploy.sh"
LOG=$RC/deploy-dryrun.log bash "$D/deploy.sh" --dry-run > "$RC/deploy-dryrun.out" 2>&1 || { cat "$RC/deploy-dryrun.out"; die "deploy.sh --dry-run failed on the copy"; }
tail -14 "$RC/deploy-dryrun.out"
deploy_rc=0; bash "$D/deploy.sh" > "$RC/deploy.out" 2>&1 || deploy_rc=$?
grep -E '^\[|ABORT|FAIL|ok |downtime|applied|added|changed|parse ok|WZ|installed' "$RC/deploy.out" || true
RES[deploy]=$deploy_rc
[ "$deploy_rc" = 0 ] || { tail -30 "$RC/deploy.out"; die "deploy.sh failed on the copy (exit $deploy_rc); copy left as is for a look"; }
s1=$(sed -n 's/^\[\([0-9:]*\) .*\] start$/\1/p' "$RC/deploy.out" | tail -1)
S1="$(date +%F) $s1"

step "5. 1.4.0 checks on the running copy"
chk() { if [ "$2" = "$3" ]; then echo "PASS $1 ($2)"; RES["c:$1"]=PASS; else echo "FAIL $1: got '$2', want '$3'"; RES["c:$1"]="FAIL($2)"; fi; }
L=$(J "$S1")
chk "boots as 1.4.0" "$(echo "$L" | grep -c 'SlumsMS v1.4.0 (MapleStory v83) starting up')" 1
chk "world 1 Bera" "$(echo "$L" | grep -c 'Starting world 1 (Bera)')" 1
chk "no world 0" "$(echo "$L" | grep -c 'Starting world 0' || true)" 0
chk "catalog rows" "$(echo "$L" | grep -oE 'Cash Shop catalog: [0-9]+ rows' | head -1)" "Cash Shop catalog: $(sed -n 's/^CATALOG_ROWS=//p' "$D/deploy.sh") rows"
chk "SoloMapling data" "$(echo "$L" | grep -oE 'SoloMapling data: [0-9]+/[0-9]+ resolved' | head -1)" "SoloMapling data: 24/24 resolved"
chk "config flags" "$(python3 -c 'import sys,yaml; s=yaml.safe_load(open(sys.argv[1]))["server"]; print(s.get("DAILY_CHECKIN_ENABLED"), s.get("COLOR_PRISM_ENABLED"))' "$C")" "True True"
chk "1.4.0 tables" "$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('daily_checkin','gm_command_audit','gm_level_changes','equip_tints','character_look_tints');" | tsql)" 5
chk "ledger" "$(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | tsql)" "001-preserve-arcane-for-classic-fm,002-classic-fm-return,003-solomapling-bots,004-daily-checkin,005-bot-real-stats,006-bot-yield-caps,007-bot-starter-stash,008-world-bera,009-gm-audit,010-color-prism"
chk "prism not disabled" "$(echo "$L" | grep -cE 'migration 010|Coloring Prism stays off|Coloring Prism tables' || true)" 0
chk "prism WZ item" "$( [ -f "$R/Server/wz/Item.wz/Cash/0578.img.xml" ] && grep -c '<imgdir name="5782000"' "$R/Server/wz/String.wz/Cash.img.xml")" 1
chk "mob values pending after apply" "$(python3 "$D/staging/tools/data-align/wz_set_values.py" --wz "$R/Server/wz" --tsv "$D/staging/tools/data-align/mob-attack-client-values.tsv" --column client_value 2>/dev/null | tail -1 | grep -oE ': [0-9]+ value' )" ": 0 value"
echo "SELECT 'characters by world', world, COUNT(*) FROM characters GROUP BY world;" | tsql

step "5b. live's characters log in end to end (scratch passwords only)"
chars=$(echo "SELECT CONCAT(a.name,':',c.id,':',c.map) FROM characters c JOIN accounts a ON a.id=c.accountid WHERE c.world=1 ORDER BY c.id;" | tsql)
banned=$(echo "SELECT IFNULL(GROUP_CONCAT(id),'') FROM accounts WHERE banned=1 AND id IN (SELECT accountid FROM characters);" | tsql)
echo "accounts banned on live (unbanned in the scratch copy for the probe, banned again after): ${banned:-none}"
echo "UPDATE accounts SET password='$PROBE_PW', loggedin=0, banned=0 WHERE id IN (SELECT accountid FROM characters);" | tsql
P=$R/probe; mkdir -p "$P/bin"
cp "$D/staging/tools/soak/LoadGen.java" "$D/staging/tools/soak/CharLoadProbe.java" "$P/"
nice -n 19 javac -nowarn -cp "$R/Server/target/Cosmic.jar" -d "$P/bin" "$P/LoadGen.java" "$P/CharLoadProbe.java"
probe_rc=0; nice -n 19 java -cp "$P/bin:$R/Server/target/Cosmic.jar" CharLoadProbe 127.0.0.1 "$LOGIN_PORT" 1 "$PROBE_PW" $chars | tee "$RC/charload.txt" || probe_rc=$?
[ -z "$banned" ] || echo "UPDATE accounts SET banned=1 WHERE id IN ($banned);" | tsql
chk "characters load ($(echo "$chars" | wc -w))" "$(grep -c '^OK' "$RC/charload.txt")" "$(echo "$chars" | wc -w)"

step "5c. soak ${SOAK}s"
sleep "$SOAK"
RES[new_errs]=$(J "$S1" | grep -cE 'ERROR|Exception' || true)
J "$S1" | grep -E 'ERROR|Exception' | head -10 > "$RC/new-errors.txt" || true
echo "new version ERROR/Exception lines since its start: ${RES[new_errs]}"
systemctl show "$UNIT" -p MemoryCurrent
snapshot > "$RC/state-after-deploy.tsv"

step "6. idempotency: every migration a second time, WZ steps and flags again (server stopped)"
systemctl stop "$UNIT" || true
for f in 004-daily-checkin 009-gm-audit 010-color-prism; do
  before=$(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()) FROM mapleroot_integration_migrations;" | tsql)
  out=$(tsql < "$D/staging/migrations/$f.sql" || true)
  after=$(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id), (SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()) FROM mapleroot_integration_migrations;" | tsql)
  if [ -z "$(echo "$out" | grep -E '^ERROR' || true)" ] && [ "$before" = "$after" ]; then chk "re-run $f" ok ok; else chk "re-run $f" "$out / $before -> $after" "no error, no change"; fi
done
chk "WZ prism re-run" "$(python3 "$D/staging/tools/colorprism/install_wz.py" --wz "$R/Server/wz" | tail -1)" "nothing to do"

step "7. rollback.sh --dry-run, then rollback.sh (boots the old version again)"
bash "$R/start.sh"; wait_online "$(date '+%Y-%m-%d %H:%M:%S' -d '-5 sec')" || true   # rollback.sh stops a running server, like live
LOG=$RC/rollback-dryrun.log bash "$D/rollback.sh" --dry-run > "$RC/rollback-dryrun.out" 2>&1 || { cat "$RC/rollback-dryrun.out"; die "rollback.sh --dry-run failed"; }
tail -3 "$RC/rollback-dryrun.out"
rb0=$(date '+%Y-%m-%d %H:%M:%S')
rb_rc=0; LOG=$RC/rollback.log RECEIPT=$RC/ROLLBACK-RECEIPT.md bash "$D/rollback.sh" > "$RC/rollback.out" 2>&1 || rb_rc=$?
grep -E '^\[|ABORT|WARN|rolled back|removed|restored|ledger' "$RC/rollback.out" || true
RES[rollback]=$rb_rc
sleep 20
RES[rb_errs]=$(J "$rb0" | grep -cE 'ERROR|Exception' || true)
J "$rb0" | grep -E "SlumsMS v|Starting world|is now online" | tail -3
chk "rolled back to 1.3.1" "$(J "$rb0" | grep -c 'SlumsMS v1.3.1')" 1
chk "ledger after rollback" "$(echo "SELECT GROUP_CONCAT(migration_id ORDER BY migration_id) FROM mapleroot_integration_migrations;" | tsql)" "001-preserve-arcane-for-classic-fm,002-classic-fm-return,003-solomapling-bots,005-bot-real-stats,006-bot-yield-caps,007-bot-starter-stash,008-world-bera"
chk "1.4.0 tables gone" "$(echo "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('daily_checkin','gm_command_audit','gm_level_changes','equip_tints','character_look_tints');" | tsql)" 0
chk "WZ back to live's" "$(cd "$R/Server/wz" && sha256sum $(cat "$R/backup/wz-before.list") | sha256sum | cut -c1-16)" "$(cd "$SERVER_SRC/wz" && sha256sum $(cat "$R/backup/wz-before.list") | sha256sum | cut -c1-16)"
chk "prism item image removed" "$([ -e "$R/Server/wz/Item.wz/Cash/0578.img.xml" ] && echo present || echo absent)" absent
chk "config back to live's" "$(cmp -s "$R/backup/config.yaml" "$C" && echo same)" same
snapshot > "$RC/state-after-rollback.tsv"
systemctl stop "$UNIT" || true
for f in 010-color-prism 009-gm-audit 004-daily-checkin; do
  out=$(tsql < "$D/staging/migrations/$f.rollback.sql" || true)
  chk "rollback SQL re-run $f" "$(echo "$out" | grep -cE '^ERROR' || true)" 0
done

step "8. compare"
diff "$RC/state-before.tsv" "$RC/state-after-rollback.tsv" > "$RC/diff-before-vs-rollback.txt" && same=PASS || same=FAIL
diff "$RC/state-before.tsv" "$RC/state-after-deploy.tsv" > "$RC/diff-before-vs-deploy.txt" || true
echo "player rows before == after rollback: $same"; cat "$RC/diff-before-vs-rollback.txt"
size=$(du -sh "$R" | cut -f1)
fails=0; while IFS= read -r k; do [ "${RES[$k]}" = PASS ] || fails=$((fails+1)); done < <(printf '%s\n' "${!RES[@]}" | grep '^c:')
overall=PASS; [ "$deploy_rc" = 0 ] && [ "$rb_rc" = 0 ] && [ "$fails" = 0 ] && [ "${RES[new_errs]}" = 0 ] || overall=FAIL
{
  echo "# SlumsMS $VERSION rehearsal on a copy of live (live untouched)"
  echo
  echo "- $(date -u -d @"$t0" '+%Y-%m-%d %H:%M') - $(date -u '+%H:%M UTC') on $(hostname). **Result: $overall**"
  echo "- Staged: commit $(cat "$D/staging/COMMIT"), jar $(sha256sum "$D/staging/Cosmic.jar" | cut -c1-16)"
  echo "- DB: $SRC_DESC -> scratch schema \`$SCHEMA\` (test DB 3307, user $DBUSER)"
  echo "- Server: copy of live's Server/ incl. its own wz/ at \`$R/Server\` ($size total in $R); unit $UNIT (transient, CPUQuota=200% Nice=19 MemoryMax=6G), login $LOGIN_PORT, channels $CHANNEL_BASE+"
  echo "- deploy.sh exit $deploy_rc, rollback.sh exit $rb_rc; ERROR/Exception lines: old boot ${RES[old_errs]}, 1.4.0 ${RES[new_errs]}, after rollback ${RES[rb_errs]}"
  echo "- Player rows before == after rollback: **$same**"
  echo
  echo "## Checks"
  while IFS= read -r k; do echo "- ${RES[$k]} ${k#c:}"; done < <(printf '%s\n' "${!RES[@]}" | grep '^c:' | sort)
  echo
  echo "## Characters logged in on 1.4.0"
  sed 's/^/    /' "$RC/charload.txt"
  echo
  echo "## Deploy receipt"
  sed 's/^/> /' "$RC/DEPLOY-RECEIPT.md" 2>/dev/null || echo "> (none)"
  echo
  echo "## Rollback receipt"
  sed 's/^/> /' "$RC/ROLLBACK-RECEIPT.md" 2>/dev/null || echo "> (none)"
  echo
  echo "## Before vs after deploy (player rows)"
  sed 's/^/    /' "$RC/diff-before-vs-deploy.txt"
  echo
  echo "Left in place (unit stopped): $R ($size), schema $SCHEMA. Clean up: rm -rf $R; mysql ... -e 'DROP DATABASE $SCHEMA; DROP USER $DBUSER'"
} > "$RC/REHEARSAL.md"
cp "$RC/REHEARSAL.md" "$D/REHEARSAL.md"
cat "$RC/REHEARSAL.md"
step "rehearsal $overall"
[ "$overall" = PASS ]
